package app.siphon.data

import kotlinx.serialization.Serializable

@Serializable
enum class Preset(val label: String, val isAudio: Boolean = false, val maxHeight: Int? = null) {
    Best("Best"),
    P2160("4K", maxHeight = 2160),
    P1440("1440p", maxHeight = 1440),
    P1080("1080p", maxHeight = 1080),
    P720("720p", maxHeight = 720),
    P480("480p", maxHeight = 480),
    AudioBest("Audio (original)", isAudio = true),
    AudioM4a("Audio M4A", isAudio = true),
    AudioMp3("Audio MP3", isAudio = true),
    Custom("Custom format"),
}

/** Everything needed to (re)run a download, frozen at enqueue time. */
@Serializable
data class DownloadOptions(
    val preset: Preset = Preset.Best,
    /** yt-dlp format id when [preset] is [Preset.Custom]. */
    val formatId: String? = null,
    val formatIsVideoOnly: Boolean = false,
    val playlist: Boolean = false,
    val compatibleMp4: Boolean = true,
    val embedMetadata: Boolean = true,
    val embedThumbnail: Boolean = true,
    val embedChapters: Boolean = true,
    val subtitles: Boolean = false,
    val subtitleLangs: String = "en.*,it.*",
    val sponsorBlock: Boolean = false,
    val concurrentFragments: Int = 4,
    val extraArgs: String = "",
)

@Serializable
enum class Status { Queued, Running, Processing, Done, Failed, Canceled;
    val isActive get() = this == Queued || this == Running || this == Processing
}

@Serializable
data class Resume(val outputIndex: Int, val positionMs: Long)

@Serializable
data class Chapter(val startSec: Double, val endSec: Double, val title: String)

@Serializable
data class OutputFile(
    val uri: String,
    val name: String,
    val mime: String,
    val size: Long,
    val chapters: List<Chapter> = emptyList(),
) {
    val isAudio get() = mime.startsWith("audio/")
}

@Serializable
data class DownloadItem(
    val id: String,
    val url: String,
    val title: String,
    val thumbnail: String? = null,
    val uploader: String? = null,
    val durationSec: Int? = null,
    val extractor: String? = null,
    val options: DownloadOptions,
    val createdAt: Long,
    val finishedAt: Long? = null,
    val status: Status = Status.Queued,
    val progress: Float = 0f,
    val etaSec: Long = -1,
    val speed: String? = null,
    val stage: String? = null,
    val outputs: List<OutputFile> = emptyList(),
    val error: String? = null,
    val log: String = "",
    val resume: Resume? = null,
) {
    val isAudio get() = options.preset.isAudio
}
