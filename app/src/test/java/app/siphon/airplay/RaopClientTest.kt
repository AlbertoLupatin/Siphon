package app.siphon.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.InetAddress
import kotlin.math.PI
import kotlin.math.sin

class RaopClientTest {

    @Test
    fun alacEscapeFrameLayout() {
        val pcm = ShortArray(352 * 2) { if (it == 0) 0x1234 else 0 }
        val bytes = Alac.encodeUncompressed(pcm, 352)
        assertEquals((23 + 352 * 32 + 3 + 7) / 8, bytes.size)
        // 001 0000 000000000000 0 00 1 | first sample 0x1234 starts at bit 23
        assertArrayEquals(byteArrayOf(0x20, 0x00, 0x02, 0x24, 0x68), bytes.copyOfRange(0, 5))
    }

    @Test
    fun ringPadsWithSilenceAndDropsOldest() {
        val ring = PcmRing(4)
        ring.write(shortArrayOf(1, 1, 2, 2, 3, 3, 4, 4, 5, 5), 5) // 5 frames into 4 slots: frame 1 dropped
        val out = ShortArray(12)
        ring.read(out, 6)
        assertArrayEquals(shortArrayOf(2, 2, 3, 3, 4, 4, 5, 5, 0, 0, 0, 0), out)
    }

    /**
     * Streams a 440 Hz tone to a real receiver. Run with a local shairport-sync:
     *   shairport-sync -a SiphonTest -p 5123 -o stdout > out.pcm
     *   SIPHON_RAOP=127.0.0.1:5123 ./gradlew :app:testDebugUnitTest --tests '*RaopClientTest*'
     */
    @Test
    fun streamsToReceiver() {
        val target = System.getenv("SIPHON_RAOP")
        assumeTrue("set SIPHON_RAOP=host:port to run", target != null)
        val (host, port) = target!!.split(':')
        val encrypt = System.getenv("SIPHON_RAOP_ENCRYPT") == "1"
        val client = RaopClient(InetAddress.getByName(host), port.toInt(), encrypt) { println("raop: $it") }
        client.connect(volume = 1f)
        val rate = RaopClient.SAMPLE_RATE
        val chunk = 4410
        val buf = ShortArray(chunk * 2)
        var n = 0L
        val start = System.nanoTime()
        repeat(40) { // 4 s, fed in real time like the player would
            for (i in 0 until chunk) {
                val v = (sin(2 * PI * 440 * n++ / rate) * 12000).toInt().toShort()
                buf[i * 2] = v; buf[i * 2 + 1] = v
            }
            client.write(buf, chunk)
            val due = start + (it + 1) * 100_000_000L
            while (System.nanoTime() < due) Thread.sleep(2)
        }
        Thread.sleep(2500) // let the receiver play out its buffer
        client.close()
    }
}
