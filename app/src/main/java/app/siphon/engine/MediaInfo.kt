package app.siphon.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/** Result of `yt-dlp -J` — either a single video or a (flat) playlist. */
data class MediaInfo(
    val id: String,
    val title: String,
    val uploader: String?,
    val thumbnail: String?,
    val durationSec: Int?,
    val extractor: String?,
    val webpageUrl: String?,
    val isPlaylist: Boolean,
    val entries: List<PlaylistEntry>,
    val formats: List<MediaFormat>,
)

data class PlaylistEntry(val title: String, val durationSec: Int?)

data class MediaFormat(
    val id: String,
    val ext: String,
    val height: Int?,
    val fps: Double?,
    val vcodec: String?,
    val acodec: String?,
    val abr: Double?,
    val tbr: Double?,
    val sizeBytes: Long?,
    val note: String?,
) {
    val hasVideo get() = vcodec != null && vcodec != "none"
    val hasAudio get() = acodec != null && acodec != "none"
    val kind: Kind
        get() = when {
            hasVideo && hasAudio -> Kind.Muxed
            hasVideo -> Kind.VideoOnly
            else -> Kind.AudioOnly
        }

    enum class Kind { Muxed, VideoOnly, AudioOnly }
}

object MediaInfoParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(raw: String): MediaInfo {
        // yt-dlp may print stray lines before the JSON document; grab the object.
        val start = raw.indexOf('{')
        require(start >= 0) { "yt-dlp returned no metadata" }
        val root = json.parseToJsonElement(raw.substring(start)).jsonObject
        val isPlaylist = root.str("_type") == "playlist"
        val entries = (root["entries"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            PlaylistEntry(o.str("title") ?: o.str("id") ?: "?", o.num("duration")?.toInt())
        }
        val formats = (root["formats"] as? JsonArray).orEmpty().mapNotNull { f ->
            val o = f as? JsonObject ?: return@mapNotNull null
            val fmt = MediaFormat(
                id = o.str("format_id") ?: return@mapNotNull null,
                ext = o.str("ext") ?: "?",
                height = o.num("height")?.toInt(),
                fps = o.num("fps"),
                vcodec = o.str("vcodec"),
                acodec = o.str("acodec"),
                abr = o.num("abr"),
                tbr = o.num("tbr"),
                sizeBytes = (o.num("filesize") ?: o.num("filesize_approx"))?.toLong(),
                note = o.str("format_note"),
            )
            // Drop storyboards / images and manifest placeholders with no streams.
            if (fmt.ext == "mhtml" || (!fmt.hasVideo && !fmt.hasAudio)) null else fmt
        }
        val thumbnail = root.str("thumbnail")
            ?: root.lastThumb()
            ?: (root["entries"] as? JsonArray)?.firstOrNull()?.let { (it as? JsonObject)?.lastThumb() }
        return MediaInfo(
            id = root.str("id") ?: "",
            title = root.str("title") ?: root.str("id") ?: "Untitled",
            uploader = root.str("uploader") ?: root.str("channel") ?: root.str("uploader_id"),
            thumbnail = thumbnail,
            durationSec = root.num("duration")?.toInt(),
            extractor = root.str("extractor_key") ?: root.str("extractor"),
            webpageUrl = root.str("webpage_url"),
            isPlaylist = isPlaylist,
            entries = entries,
            formats = formats,
        )
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content

    private fun JsonObject.num(key: String): Double? {
        val p = this[key] as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        return p.doubleOrNull ?: p.longOrNull?.toDouble()
    }

    private fun JsonObject.lastThumb(): String? =
        (this["thumbnails"] as? JsonArray)?.lastOrNull()?.let { (it as? JsonObject)?.str("url") }
}
