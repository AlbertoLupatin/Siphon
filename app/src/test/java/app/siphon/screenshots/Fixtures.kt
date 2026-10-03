package app.siphon.screenshots

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import app.siphon.data.DownloadItem
import app.siphon.data.DownloadOptions
import app.siphon.data.MediaStoreSaver
import app.siphon.data.OutputFile
import app.siphon.data.Preset
import app.siphon.data.Status
import app.siphon.engine.MediaFormat
import app.siphon.engine.MediaInfo
import coil3.Image
import coil3.asImage

/** Realistic-looking data for design review. Thumbnails are generated scenes, keyed by fake URL. */
object Fixtures {

    private val scenes = mapOf(
        "thumb://meadow" to intArrayOf(0xFF86C5E8.toInt(), 0xFFB9E2F0.toInt(), 0xFF5E9B3C.toInt(), 0xFFF6E27A.toInt()),
        "thumb://dusk" to intArrayOf(0xFF2B1E4A.toInt(), 0xFFE07A5F.toInt(), 0xFF3D2C3E.toInt(), 0xFFF2CC8F.toInt()),
        "thumb://studio" to intArrayOf(0xFF1F2A33.toInt(), 0xFF3C5A6E.toInt(), 0xFF101820.toInt(), 0xFF9AD1D4.toInt()),
        "thumb://desert" to intArrayOf(0xFFF4C27A.toInt(), 0xFFF9E3B5.toInt(), 0xFFC8743A.toInt(), 0xFFFFFFFF.toInt()),
        "thumb://forest" to intArrayOf(0xFF0F2E26.toInt(), 0xFF2D5A44.toInt(), 0xFF173A2E.toInt(), 0xFFB8D8A8.toInt()),
        "thumb://neon" to intArrayOf(0xFF140A2A.toInt(), 0xFF6B2FA3.toInt(), 0xFF1C0F35.toInt(), 0xFFFF5FA2.toInt()),
    )

    fun image(url: String): Image? {
        val c = scenes[url] ?: return null
        val w = 640; val h = 360
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, h * 0.62f, c[0], c[1], Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w.toFloat(), h * 0.62f, p)
        p.shader = LinearGradient(0f, h * 0.62f, 0f, h.toFloat(), c[2], darker(c[2]), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, h * 0.6f, w.toFloat(), h.toFloat(), p)
        p.shader = RadialGradient(w * 0.7f, h * 0.38f, h * 0.22f, c[3], c[3] and 0x00FFFFFF, Shader.TileMode.CLAMP)
        canvas.drawCircle(w * 0.7f, h * 0.38f, h * 0.22f, p)
        p.shader = null; p.color = darker(c[2])
        canvas.drawOval(w * 0.08f, h * 0.55f, w * 0.48f, h * 0.78f, p)
        return bmp.asImage()
    }

    private fun darker(c: Int): Int {
        val r = (c shr 16 and 0xFF) * 6 / 10; val g = (c shr 8 and 0xFF) * 6 / 10; val b = (c and 0xFF) * 6 / 10
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun fmt(id: String, ext: String, h: Int?, v: String?, a: String?, mb: Double?, fps: Double? = 30.0, abr: Double? = null) =
        MediaFormat(id, ext, h, if (h != null) fps else null, v ?: "none", a ?: "none", abr, null, mb?.let { (it * 1024 * 1024).toLong() }, null)

    val bunny = MediaInfo(
        id = "aqz-KE-bpKQ",
        title = "Big Buck Bunny 60fps 4K – Official Blender Foundation Short Film",
        uploader = "Blender",
        thumbnail = "thumb://meadow",
        durationSec = 635,
        extractor = "YouTube",
        webpageUrl = "https://www.youtube.com/watch?v=aqz-KE-bpKQ",
        isPlaylist = false,
        entries = emptyList(),
        formats = listOf(
            fmt("18", "mp4", 360, "avc1.42001E", "mp4a.40.2", 21.0),
            fmt("140", "m4a", null, null, "mp4a.40.2", 9.8, abr = 129.0),
            fmt("251", "webm", null, null, "opus", 11.4, abr = 152.0),
            fmt("134", "mp4", 360, "avc1.4d401e", null, 14.0),
            fmt("135", "mp4", 480, "avc1.4d401f", null, 26.0),
            fmt("298", "mp4", 720, "avc1.4d4020", null, 61.0, fps = 60.0),
            fmt("299", "mp4", 1080, "avc1.64002a", null, 246.4, fps = 60.0),
            fmt("303", "webm", 1080, "vp9", null, 188.0, fps = 60.0),
            fmt("308", "webm", 1440, "vp9", null, 512.0, fps = 60.0),
            fmt("315", "webm", 2160, "vp9", null, 1210.0, fps = 60.0),
        ),
    )

    private fun item(
        id: String, title: String, thumb: String, status: Status, preset: Preset = Preset.P1080,
        progress: Float = 0f, uploader: String = "Blender", minutesAgo: Long = 0, ext: String = "mp4", mb: Long = 0,
        stage: String? = null, speed: String? = null, eta: Long = -1, error: String? = null, duration: Int = 600,
    ): DownloadItem {
        val now = 1_791_000_000_000L
        return DownloadItem(
            id = id, url = "https://example.com/$id", title = title, thumbnail = thumb, uploader = uploader,
            durationSec = duration, extractor = "YouTube", options = DownloadOptions(preset = preset),
            createdAt = now - minutesAgo * 60_000 - 1, finishedAt = if (status.isActive) null else now - minutesAgo * 60_000,
            status = status, progress = progress, etaSec = eta, speed = speed, stage = stage, error = error,
            outputs = if (status == Status.Done) listOf(
                OutputFile("content://media/$id", "$title.$ext", MediaStoreSaver.mimeOf(java.io.File("x.$ext")), mb * 1024 * 1024)
            ) else emptyList(),
        )
    }

    val queue = listOf(
        item("a", "Big Buck Bunny 60fps 4K – Official Blender Foundation Short Film", "thumb://meadow", Status.Running,
            progress = 62f, stage = "Downloading", speed = "8.4MiB/s", eta = 41),
        item("b", "Cosmos Laundromat – First Cycle", "thumb://dusk", Status.Processing, stage = "Merging"),
        item("c", "Spring – Blender Open Movie", "thumb://forest", Status.Queued),
        item("d", "Late night jazz session, live from the studio", "thumb://studio", Status.Done, Preset.AudioM4a,
            uploader = "Blue Room", minutesAgo = 12, ext = "m4a", mb = 58, duration = 3540),
        item("e", "Sintel – Open Movie by Blender Foundation", "thumb://desert", Status.Done, minutesAgo = 50, mb = 412),
        item("f", "Private livestream replay", "thumb://neon", Status.Failed, minutesAgo = 90,
            error = "This live event will begin in a few moments."),
        item("g", "Tears of Steel", "thumb://forest", Status.Done, Preset.P720, minutesAgo = 180, mb = 233),
        item("h", "Elephants Dream – remastered soundtrack", "thumb://dusk", Status.Done, Preset.AudioMp3,
            uploader = "Orange Open Movie", minutesAgo = 400, ext = "mp3", mb = 9, duration = 654),
        item("i", "Caminandes 3: Llamigos", "thumb://desert", Status.Canceled, minutesAgo = 600),
    )
}
