package app.siphon.airplay

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.OutputStream
import java.math.BigInteger
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

/**
 * Minimal AirPlay 1 (RAOP) audio sender: RTSP session + RTP over UDP carrying uncompressed ALAC,
 * 44.1 kHz / 16-bit / stereo. HomePods, AirPort Expresses, Apple TVs, macOS "AirPlay Receiver" and
 * shairport-sync accept this as long as the speaker doesn't require a password.
 *
 * Pure JVM (no Android APIs) so it can be exercised against a local receiver in unit tests.
 */
class RaopClient(
    private val host: InetAddress,
    private val port: Int,
    private val encrypt: Boolean,
    private val log: (String) -> Unit = {},
) : Closeable {

    class RaopException(message: String) : Exception(message)

    private val random = SecureRandom()
    private val sessionId = random.nextInt().toLong() and 0xffffffffL
    private val clientInstance = hex(8)
    private val dacpId = hex(8)
    private val activeRemote = (random.nextInt() and 0x7fffffff).toString()
    private val ssrc = random.nextInt()

    private lateinit var rtsp: Socket
    private lateinit var input: BufferedInputStream
    private lateinit var output: OutputStream
    private var cseq = 0
    private var session: String? = null
    private lateinit var uri: String

    private val audio = DatagramSocket()
    private val control = DatagramSocket()
    private val timing = DatagramSocket()
    private var serverPort = 0
    private var serverControlPort = 0

    private val aesKey = ByteArray(16).also(random::nextBytes)
    private val aesIv = ByteArray(16).also(random::nextBytes)
    private val aes by lazy { Cipher.getInstance("AES/CBC/NoPadding") }

    private val running = AtomicBoolean(false)
    private val lock = Object()
    private var seq = random.nextInt(0x10000)
    private var rtpTime = random.nextInt().toLong() and 0xffffffffL
    private var firstPacket = true
    private var needsSync = true
    private val history = LinkedHashMap<Int, ByteArray>()

    private val ring = PcmRing(SAMPLE_RATE * 4) // frames

    // ---- public API ----

    /** Opens the session and starts streaming silence until [write] supplies audio. */
    fun connect(volume: Float) {
        val address = InetSocketAddress(host, port) // outside apply{}: Socket has its own `port`
        rtsp = Socket().apply { connect(address, 5000); soTimeout = 8000; tcpNoDelay = true }
        input = BufferedInputStream(rtsp.getInputStream())
        output = rtsp.getOutputStream()
        val local = rtsp.localAddress.hostAddress!!.substringBefore('%')
        uri = "rtsp://$local/$sessionId"

        request("OPTIONS", "*")
        val sdp = buildString {
            append("v=0\r\n")
            append("o=iTunes $sessionId 0 IN IP4 $local\r\n")
            append("s=iTunes\r\n")
            append("c=IN IP4 ${host.hostAddress}\r\n")
            append("t=0 0\r\n")
            append("m=audio 0 RTP/AVP 96\r\n")
            append("a=rtpmap:96 AppleLossless\r\n")
            append("a=fmtp:96 $FRAMES 0 16 40 10 14 2 255 0 0 $SAMPLE_RATE\r\n")
            if (encrypt) {
                append("a=rsaaeskey:${b64(rsaEncrypt(aesKey))}\r\n")
                append("a=aesiv:${b64(aesIv)}\r\n")
            }
        }
        request("ANNOUNCE", uri, mapOf("Content-Type" to "application/sdp"), sdp)
        val setup = request(
            "SETUP", uri,
            mapOf(
                "Transport" to "RTP/AVP/UDP;unicast;interleaved=0-1;mode=record;" +
                    "control_port=${control.localPort};timing_port=${timing.localPort}",
            ),
        )
        session = setup.headers["session"]?.substringBefore(';')?.trim()
        val transport = setup.headers["transport"] ?: throw RaopException("Speaker sent no transport")
        serverPort = param(transport, "server_port") ?: throw RaopException("Speaker sent no audio port")
        serverControlPort = param(transport, "control_port") ?: 0
        request("RECORD", uri, mapOf("Range" to "npt=0-", "RTP-Info" to "seq=$seq;rtptime=$rtpTime"))
        setVolume(volume)

        running.set(true)
        thread(name = "raop-timing", isDaemon = true) { timingLoop() }
        thread(name = "raop-control", isDaemon = true) { controlLoop() }
        thread(name = "raop-audio", isDaemon = true, priority = Thread.MAX_PRIORITY) { audioLoop() }
        log("connected to ${host.hostAddress}:$port, audio port $serverPort")
    }

    /** Interleaved stereo 16-bit frames at 44.1 kHz. Drops the oldest audio if the speaker falls behind. */
    fun write(samples: ShortArray, frames: Int) = ring.write(samples, frames)

    /** Drops queued audio on the speaker (pause/seek) so it doesn't play out its ~2 s buffer. */
    fun flush() {
        ring.clear()
        synchronized(lock) {
            runCatching {
                request("FLUSH", uri, mapOf("RTP-Info" to "seq=$seq;rtptime=$rtpTime"))
            }.onFailure { log("flush failed: ${it.message}") }
            firstPacket = true
            needsSync = true
        }
    }

    /** 0..1, mapped onto AirPlay's −30…0 dB range. */
    fun setVolume(v: Float) {
        val db = if (v <= 0f) -144.0 else -30.0 + 30.0 * v.coerceIn(0f, 1f)
        synchronized(lock) {
            runCatching {
                request("SET_PARAMETER", uri, mapOf("Content-Type" to "text/parameters"), "volume: %.6f\r\n".format(java.util.Locale.US, db))
            }.onFailure { log("volume failed: ${it.message}") }
        }
    }

    override fun close() {
        if (!running.getAndSet(false)) return
        runCatching { synchronized(lock) { request("TEARDOWN", uri) } }
        runCatching { rtsp.close() }
        audio.close(); control.close(); timing.close()
        log("closed")
    }

    // ---- RTSP ----

    private class Response(val status: Int, val headers: Map<String, String>, val body: String)

    private fun request(method: String, target: String, headers: Map<String, String> = emptyMap(), body: String? = null): Response {
        val bytes = body?.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder("$method $target RTSP/1.0\r\n")
        sb.append("CSeq: ${++cseq}\r\n")
        sb.append("User-Agent: iTunes/11.0.4 (Windows; N)\r\n")
        sb.append("Client-Instance: $clientInstance\r\n")
        sb.append("DACP-ID: $dacpId\r\n")
        sb.append("Active-Remote: $activeRemote\r\n")
        session?.let { sb.append("Session: $it\r\n") }
        headers.forEach { (k, v) -> sb.append("$k: $v\r\n") }
        if (bytes != null) sb.append("Content-Length: ${bytes.size}\r\n")
        sb.append("\r\n")
        output.write(sb.toString().toByteArray(Charsets.UTF_8))
        if (bytes != null) output.write(bytes)
        output.flush()

        val status = readLine().split(' ').getOrNull(1)?.toIntOrNull() ?: throw RaopException("Bad reply to $method")
        val h = mutableMapOf<String, String>()
        while (true) {
            val line = readLine()
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) h[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        val len = h["content-length"]?.toIntOrNull() ?: 0
        val content = if (len > 0) String(readExactly(len), Charsets.UTF_8) else ""
        log("$method → $status")
        when (status) {
            200 -> {}
            401 -> throw RaopException("This speaker needs a password. Turn off the password for AirPlay in the Home app.")
            403 -> throw RaopException("The speaker refused the connection. In the Home app set “Allow Speaker Access” to everyone on the same network.")
            453 -> throw RaopException("The speaker is busy playing from another device.")
            else -> throw RaopException("$method failed with status $status")
        }
        return Response(status, h, content)
    }

    private fun readExactly(n: Int): ByteArray {
        val out = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(out, off, n - off)
            if (r < 0) throw RaopException("Speaker closed the connection")
            off += r
        }
        return out
    }

    private fun readLine(): String {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) throw RaopException("Speaker closed the connection")
            if (c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
        }
        return sb.toString()
    }

    private fun param(transport: String, key: String): Int? =
        transport.split(';').firstOrNull { it.trim().startsWith("$key=") }?.substringAfter('=')?.trim()?.toIntOrNull()

    // ---- audio ----

    private fun audioLoop() {
        val frameNanos = FRAMES * 1_000_000_000L / SAMPLE_RATE
        val pcm = ShortArray(FRAMES * 2)
        var next = System.nanoTime()
        var sent = 0L
        while (running.get()) {
            val now = System.nanoTime()
            if (now < next) {
                val sleepMs = (next - now) / 1_000_000
                if (sleepMs > 0) Thread.sleep(sleepMs) else Thread.yield()
                continue
            }
            ring.read(pcm, FRAMES) // pads with silence on underrun so the RTP clock never stalls
            synchronized(lock) {
                if (needsSync || sent % (SAMPLE_RATE / FRAMES) == 0L) sendSync()
                sendAudio(pcm)
            }
            sent++
            next += frameNanos
            if (System.nanoTime() - next > 500_000_000L) next = System.nanoTime() // resync after a long stall
        }
    }

    private fun sendAudio(pcm: ShortArray) {
        val alac = Alac.encodeUncompressed(pcm, FRAMES)
        if (encrypt) encryptInPlace(alac)
        val packet = ByteArray(12 + alac.size)
        packet[0] = 0x80.toByte()
        packet[1] = (if (firstPacket) 0xE0 else 0x60).toByte()
        packet[2] = (seq shr 8).toByte(); packet[3] = seq.toByte()
        putInt(packet, 4, rtpTime)
        putInt(packet, 8, ssrc.toLong())
        System.arraycopy(alac, 0, packet, 12, alac.size)
        runCatching { audio.send(DatagramPacket(packet, packet.size, host, serverPort)) }
        history[seq] = packet
        if (history.size > 1024) history.remove(history.keys.first())
        firstPacket = false
        seq = (seq + 1) and 0xffff
        rtpTime = (rtpTime + FRAMES) and 0xffffffffL
    }

    /** Tells the speaker which RTP frame should be playing now (minus its latency). */
    private fun sendSync() {
        if (serverControlPort == 0) return
        val p = ByteArray(20)
        p[0] = (if (needsSync) 0x90 else 0x80).toByte()
        p[1] = 0xD4.toByte()
        p[2] = 0x00; p[3] = 0x07
        putInt(p, 4, (rtpTime - LATENCY) and 0xffffffffL)
        putLong(p, 8, ntpNow())
        putInt(p, 16, rtpTime)
        runCatching { control.send(DatagramPacket(p, p.size, host, serverControlPort)) }
        needsSync = false
    }

    private fun controlLoop() {
        val buf = ByteArray(1500)
        while (running.get()) {
            val pkt = DatagramPacket(buf, buf.size)
            runCatching { control.receive(pkt) }.onFailure { return }
            // 0x55: retransmit request — [80 d5 seq seq | first missed seq | count]
            if (pkt.length >= 8 && (buf[1].toInt() and 0x7f) == 0x55) {
                val first = ((buf[4].toInt() and 0xff) shl 8) or (buf[5].toInt() and 0xff)
                val count = ((buf[6].toInt() and 0xff) shl 8) or (buf[7].toInt() and 0xff)
                synchronized(lock) {
                    for (i in 0 until count) {
                        val original = history[(first + i) and 0xffff] ?: continue
                        val resend = ByteArray(4 + original.size)
                        resend[0] = 0x80.toByte(); resend[1] = 0xD6.toByte(); resend[2] = 0x00; resend[3] = 0x01
                        System.arraycopy(original, 0, resend, 4, original.size)
                        runCatching { control.send(DatagramPacket(resend, resend.size, pkt.address, pkt.port)) }
                    }
                }
            }
        }
    }

    /** Answers the speaker's NTP-style timing requests so it can line its clock up with ours. */
    private fun timingLoop() {
        val buf = ByteArray(128)
        while (running.get()) {
            val pkt = DatagramPacket(buf, buf.size)
            runCatching { timing.receive(pkt) }.onFailure { return }
            if (pkt.length < 32 || (buf[1].toInt() and 0x7f) != 0x52) continue
            val received = ntpNow()
            val reply = ByteArray(32)
            reply[0] = 0x80.toByte(); reply[1] = 0xD3.toByte(); reply[2] = 0x00; reply[3] = 0x07
            System.arraycopy(buf, 24, reply, 8, 8)  // origin = their transmit time
            putLong(reply, 16, received)
            putLong(reply, 24, ntpNow())
            runCatching { timing.send(DatagramPacket(reply, reply.size, pkt.address, pkt.port)) }
        }
    }

    // ---- crypto ----

    /** AES-128-CBC over whole 16-byte blocks only; the tail stays clear, IV restarts per packet. */
    private fun encryptInPlace(data: ByteArray) {
        val whole = data.size / 16 * 16
        if (whole == 0) return
        aes.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(aesIv))
        aes.doFinal(data, 0, whole, data, 0)
    }

    private fun rsaEncrypt(data: ByteArray): ByteArray {
        val spec = RSAPublicKeySpec(BigInteger(1, Base64.getDecoder().decode(AIRPORT_MODULUS)), BigInteger.valueOf(65537))
        val key = KeyFactory.getInstance("RSA").generatePublic(spec)
        val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher.doFinal(data)
    }

    // ---- helpers ----

    private fun hex(bytes: Int) = ByteArray(bytes).also(random::nextBytes).joinToString("") { "%02X".format(it) }
    private fun b64(b: ByteArray) = Base64.getEncoder().withoutPadding().encodeToString(b)

    companion object {
        const val SAMPLE_RATE = 44100
        const val FRAMES = 352
        /** Frames between "sent" and "heard": the speaker's playout buffer (~2 s). */
        const val LATENCY = 88200L

        /** Public half of the AirPort Express key every RAOP receiver accepts. */
        private const val AIRPORT_MODULUS =
            "59dE8qLieItsH1WgjrcFRKj6eUWqi+bGLOX1HL3U3GhC/j0Qg90u3sG/1CUtwC5vOYvfDmFI6oSFXi5ELabWJmT2dKHzBJKa3k9ok+8t9ucRqMd6DZHJ2YCCLlDRKSKv6kDqnw4UwPdpOMXziC/AMj3Z/lUVX1G7WSHCAWKf1zNS1eLvqr+boEjXuBOitnZ/bDzPHrTOZz0Dew0uowxf/+sG+NCK3eQJVxqcaJ/vEHKIVd2M+5qL71yJQ+87X6oV3eaYvt3zWZYD6z5vYTcrtij2VZ9Zmni/UAaHqn9JdsBWLUEpVviYnhimNVvYFZeCXg/IdTQ+x4IRdiXNv5hEew=="

        private const val NTP_EPOCH_OFFSET = 2_208_988_800L

        fun ntpNow(): Long {
            val ms = System.currentTimeMillis()
            val seconds = ms / 1000 + NTP_EPOCH_OFFSET
            val fraction = ((ms % 1000) shl 32) / 1000
            return (seconds shl 32) or fraction
        }

        private fun putInt(b: ByteArray, at: Int, v: Long) {
            b[at] = (v shr 24).toByte(); b[at + 1] = (v shr 16).toByte(); b[at + 2] = (v shr 8).toByte(); b[at + 3] = v.toByte()
        }

        private fun putLong(b: ByteArray, at: Int, v: Long) {
            for (i in 0 until 8) b[at + i] = (v shr (56 - 8 * i)).toByte()
        }
    }
}

/** ALAC "escape" (uncompressed) frames: header bits + raw big-endian samples. */
object Alac {
    fun encodeUncompressed(interleaved: ShortArray, frames: Int): ByteArray {
        // 3 (element tag: CPE) + 4 (instance) + 12 (unused) + 4 (partial|shift|shift|escape)
        // + frames*2*16 sample bits + 3 (END tag), rounded up to whole bytes.
        val bits = 23 + frames * 32 + 3
        val w = BitWriter((bits + 7) / 8)
        w.write(1, 3)   // ID_CPE: channel pair
        w.write(0, 4)
        w.write(0, 12)
        w.write(0, 1)   // full frame (length comes from the SDP's frameLength)
        w.write(0, 2)   // no shifted bytes
        w.write(1, 1)   // escape: samples are stored uncompressed
        for (i in 0 until frames * 2) w.write(interleaved[i].toInt() and 0xffff, 16)
        w.write(7, 3)   // ID_END
        return w.bytes
    }

    class BitWriter(size: Int) {
        val bytes = ByteArray(size)
        private var bit = 0
        fun write(value: Int, count: Int) {
            for (i in count - 1 downTo 0) {
                if ((value shr i) and 1 == 1) {
                    bytes[bit ushr 3] = (bytes[bit ushr 3].toInt() or (0x80 ushr (bit and 7))).toByte()
                }
                bit++
            }
        }
    }
}

/** Fixed-size ring of interleaved stereo frames shared between the player thread and the sender. */
class PcmRing(private val capacityFrames: Int) {
    private val data = ShortArray(capacityFrames * 2)
    private var readPos = 0
    private var size = 0

    @Synchronized
    fun write(src: ShortArray, frames: Int) {
        for (f in 0 until frames) {
            if (size == capacityFrames) { readPos = (readPos + 1) % capacityFrames; size-- } // overwrite oldest
            val w = (readPos + size) % capacityFrames
            data[w * 2] = src[f * 2]; data[w * 2 + 1] = src[f * 2 + 1]
            size++
        }
    }

    /** Fills [dst] with [frames] frames, padding with silence if not enough audio is queued. */
    @Synchronized
    fun read(dst: ShortArray, frames: Int) {
        for (f in 0 until frames) {
            if (size > 0) {
                dst[f * 2] = data[readPos * 2]; dst[f * 2 + 1] = data[readPos * 2 + 1]
                readPos = (readPos + 1) % capacityFrames; size--
            } else { dst[f * 2] = 0; dst[f * 2 + 1] = 0 }
        }
    }

    @Synchronized
    fun clear() { size = 0 }

    @Synchronized
    fun queued() = size
}
