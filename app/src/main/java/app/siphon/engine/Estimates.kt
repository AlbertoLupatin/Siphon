package app.siphon.engine

import app.siphon.data.Preset

/** What a preset would roughly resolve to for this media — mirrors the format/sort logic in [Engine]. */
data class PresetEstimate(val available: Boolean, val resolution: String?, val sizeBytes: Long?)

fun MediaInfo.maxHeight(): Int? = formats.filter { it.hasVideo }.mapNotNull { it.height }.maxOrNull()

fun MediaInfo.estimate(preset: Preset, compatibleMp4: Boolean): PresetEstimate {
    if (isPlaylist || formats.isEmpty()) return PresetEstimate(available = true, resolution = null, sizeBytes = null)

    val audios = formats.filter { it.kind == MediaFormat.Kind.AudioOnly }
    fun bestAudio(preferM4a: Boolean): MediaFormat? =
        audios.sortedWith(
            compareByDescending<MediaFormat> { preferM4a && it.ext == "m4a" }.thenByDescending { it.abr ?: it.tbr ?: 0.0 }
        ).firstOrNull()

    if (preset.isAudio) {
        val a = bestAudio(preferM4a = preset == Preset.AudioM4a)
            ?: formats.maxByOrNull { it.tbr ?: 0.0 }
        val kbps = a?.abr?.toInt()
        val size = when (preset) {
            // MP3 is re-encoded at V0 (~245 kbps), so the source size says little; estimate from duration.
            Preset.AudioMp3 -> durationSec?.let { it * 245_000L / 8 }
            else -> a?.sizeBytes
        }
        val label = if (preset == Preset.AudioMp3) "245 kbps" else kbps?.let { "$it kbps" }
        return PresetEstimate(true, label, size)
    }
    if (preset == Preset.Custom) return PresetEstimate(true, null, null)

    val maxH = maxHeight() ?: return PresetEstimate(true, null, null)
    val limit = preset.maxHeight
    // A preset is meaningful only if the source actually reaches that height (with ~10% tolerance for odd aspect ratios).
    val available = limit == null || maxH >= limit * 0.9
    val videos = formats.filter { it.hasVideo && it.height != null && (limit == null || it.height <= limit) }
    val topH = videos.maxOfOrNull { it.height!! } ?: return PresetEstimate(available, null, null)
    val atTop = videos.filter { it.height == topH }
    val v = atTop.sortedWith(
        compareByDescending<MediaFormat> { compatibleMp4 && it.vcodec?.startsWith("avc") == true }
            .thenByDescending { it.tbr ?: 0.0 }
    ).first()
    val fps = v.fps?.toInt()?.takeIf { it > 30 }
    val audio = if (v.hasAudio) null else bestAudio(compatibleMp4)
    val size = v.sizeBytes?.let { it + (audio?.sizeBytes ?: 0) }
    return PresetEstimate(available, "${topH}p${fps ?: ""}", size)
}
