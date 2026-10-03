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
    val width: Int? = null,
) {
    /**
     * YouTube-style quality class: wide films are graded by width (2048×858 is "1080p"),
     * portrait video by its shorter side. Snapped to the usual ladder.
     */
    val quality: Int?
        get() {
            val h = height ?: return null
            val w = width ?: return snap(h)
            return snap(if (w >= h) maxOf(h, w * 9 / 16) else w)
        }

    val hasVideo get() = vcodec != null && vcodec != "none"
    val hasAudio get() = acodec != null && acodec != "none"
    val kind: Kind
        get() = when {
            hasVideo && hasAudio -> Kind.Muxed
            hasVideo -> Kind.VideoOnly
            else -> Kind.AudioOnly
        }

    enum class Kind { Muxed, VideoOnly, AudioOnly }

    companion object {
        private val LADDER = listOf(4320, 2160, 1440, 1080, 720, 480, 360, 240, 144)
        fun snap(v: Int): Int = LADDER.firstOrNull { v >= it * 0.9 } ?: v
    }
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
        val duration = root.num("duration")
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
                // YouTube often omits sizes; bitrate × duration is a close estimate.
                sizeBytes = (o.num("filesize") ?: o.num("filesize_approx")
                    ?: o.num("tbr")?.let { tbr -> duration?.let { tbr * 125 * it } })?.toLong(),
                note = o.str("format_note"),
                width = o.num("width")?.toInt(),
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
            durationSec = duration?.toInt(),
            extractor = siteName(root.str("extractor_key") ?: root.str("extractor"), root.str("webpage_url")),
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

    private val SITE_NAMES = mapOf(
        "youtube" to "YouTube", "youtubetab" to "YouTube", "twitter" to "X", "soundcloud" to "SoundCloud",
        "vimeo" to "Vimeo", "instagram" to "Instagram", "tiktok" to "TikTok", "twitch" to "Twitch",
        "twitchvod" to "Twitch", "twitchclips" to "Twitch", "reddit" to "Reddit", "bandcamp" to "Bandcamp",
        "facebook" to "Facebook", "dailymotion" to "Dailymotion", "bilibili" to "Bilibili", "rai" to "RaiPlay",
    )

    /** Human site name; for the generic extractor fall back to the page's domain. */
    private fun siteName(key: String?, url: String?): String? {
        val k = key?.lowercase()?.replace(Regex("[^a-z]"), "")
        SITE_NAMES[k]?.let { return it }
        if (k == null || k == "generic") return url?.substringAfter("://")?.substringBefore('/')?.removePrefix("www.")
        return key
    }

    private fun JsonObject.lastThumb(): String? =
        (this["thumbnails"] as? JsonArray)?.lastOrNull()?.let { (it as? JsonObject)?.str("url") }
}
