package app.siphon.airplay

import androidx.media3.common.C
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Receives the player's decoded PCM (after speed changes) and, while an AirPlay speaker is active,
 * converts it to 44.1 kHz stereo 16-bit for [RaopClient]. Local output is muted separately.
 */
class AirPlayTap : TeeAudioProcessor.AudioBufferSink {

    @Volatile var target: RaopClient? = null

    private var sampleRate = 0
    private var channels = 0
    private var encoding = C.ENCODING_INVALID

    // Linear resampler state, carried across buffers.
    private var position = 0.0
    private var lastL = 0f
    private var lastR = 0f
    private var out = ShortArray(8192)

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        sampleRate = sampleRateHz; channels = channelCount; this.encoding = encoding
        position = 0.0
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        val client = target ?: return
        if (sampleRate <= 0 || channels <= 0) return
        val b = buffer.duplicate().order(ByteOrder.nativeOrder())
        val bytesPerSample = when (encoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_FLOAT -> 4
            else -> return
        }
        val inFrames = b.remaining() / (bytesPerSample * channels)
        if (inFrames == 0) return

        val step = sampleRate.toDouble() / RaopClient.SAMPLE_RATE
        val maxOut = (inFrames / step).toInt() + 2
        if (out.size < maxOut * 2) out = ShortArray(maxOut * 2)
        var n = 0
        val base = b.position()

        fun sample(frame: Int, ch: Int): Float {
            val c = if (channels == 1) 0 else ch
            val at = base + (frame * channels + c) * bytesPerSample
            return if (bytesPerSample == 2) b.getShort(at) / 32768f else b.getFloat(at)
        }

        // position is measured in input frames relative to this buffer; -1 refers to the last frame of the previous one.
        while (position < inFrames - 1) {
            val i = position.toInt()
            val frac = (position - i).toFloat()
            val l0 = if (i < 0) lastL else sample(i, 0)
            val r0 = if (i < 0) lastR else sample(i, 1)
            val l1 = sample(i + 1, 0)
            val r1 = sample(i + 1, 1)
            out[n * 2] = toShort(l0 + (l1 - l0) * frac)
            out[n * 2 + 1] = toShort(r0 + (r1 - r0) * frac)
            n++
            position += step
        }
        lastL = sample(inFrames - 1, 0)
        lastR = sample(inFrames - 1, 1)
        position -= inFrames
        client.write(out, n)
    }

    private fun toShort(v: Float): Short = (v.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
}
