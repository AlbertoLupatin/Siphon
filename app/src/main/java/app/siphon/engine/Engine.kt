package app.siphon.engine

import android.content.Context
import app.siphon.data.DownloadOptions
import app.siphon.data.Preset
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.YoutubeDLResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Thin coroutine-friendly facade over youtubedl-android (bundled Python + yt-dlp + ffmpeg + QuickJS). */
object Engine {
    /** Per-download metadata sidecar, see [buildDownloadRequest]. Never published. */
    const val META_FILE = "siphon-meta.jsonl"

    sealed interface State {
        data object Initializing : State
        data class Ready(val version: String) : State
        data class Updating(val fromVersion: String) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Initializing)
    val state: StateFlow<State> = _state

    private val ytdl get() = YoutubeDL.getInstance()

    suspend fun init(context: Context) = withContext(Dispatchers.IO) {
        try {
            ytdl.init(context)
            FFmpeg.getInstance().init(context)
            _state.value = State.Ready(ytdl.version(context) ?: "unknown")
        } catch (t: Throwable) {
            android.util.Log.e("Siphon", "engine init failed", t)
            _state.value = State.Failed(t.message ?: t.toString())
        }
    }

    suspend fun awaitReady() {
        val s = state.first { it is State.Ready || it is State.Failed }
        if (s is State.Failed) throw IllegalStateException("yt-dlp engine failed to start: ${s.message}")
    }

    /** @return true when a newer yt-dlp was installed. */
    suspend fun update(context: Context, nightly: Boolean): Boolean = withContext(Dispatchers.IO) {
        awaitReady()
        val before = ytdl.version(context) ?: "unknown"
        _state.value = State.Updating(before)
        try {
            val channel = if (nightly) YoutubeDL.UpdateChannel.NIGHTLY else YoutubeDL.UpdateChannel.STABLE
            val status = ytdl.updateYoutubeDL(context, channel)
            status == YoutubeDL.UpdateStatus.DONE
        } finally {
            _state.value = State.Ready(ytdl.version(context) ?: before)
        }
    }

    suspend fun fetchInfo(url: String, playlist: Boolean): MediaInfo {
        awaitReady()
        val req = YoutubeDLRequest(url).apply {
            addOption("-J")
            addOption("--flat-playlist")
            addOption(if (playlist) "--yes-playlist" else "--no-playlist")
        }
        val resp = run(req, "info-" + UUID.randomUUID()) { _, _, _ -> }
        return MediaInfoParser.parse(resp.out)
    }

    /**
     * Runs yt-dlp on IO; cancelling the calling coroutine kills the process.
     * Throws [YoutubeDL.CanceledException] on cancel and YoutubeDLException on non-zero exit.
     */
    suspend fun run(
        request: YoutubeDLRequest,
        processId: String,
        onProgress: (progress: Float, etaSec: Long, line: String) -> Unit,
    ): YoutubeDLResponse = coroutineScope {
        awaitReady()
        // execute() blocks its thread and ignores coroutine cancellation, so a sibling
        // watcher kills the process when this scope is cancelled.
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { ytdl.destroyProcessById(processId) }
        }
        try {
            withContext(Dispatchers.IO) {
                ytdl.execute(request, processId) { p, eta, line -> onProgress(p, eta, line) }
            }
        } finally {
            watcher.cancel()
        }
    }

    fun cancel(processId: String) = ytdl.destroyProcessById(processId)

    fun buildDownloadRequest(url: String, o: DownloadOptions, outDir: File): YoutubeDLRequest {
        val r = YoutubeDLRequest(url)
        val name = if (o.playlist) "%(playlist_index)03d - %(title).150B [%(id)s].%(ext)s"
        else "%(title).150B [%(id)s].%(ext)s"
        r.addOption("-o", File(outDir, name).absolutePath)
        r.addOption("--no-mtime")
        // One JSON line per finished file, written after post-processing so chapter times
        // already account for SponsorBlock cuts. Read back by the repository for the player.
        r.addCommands(listOf("--print-to-file", "after_move:%(.{filepath,chapters})j", File(outDir, META_FILE).absolutePath))
        r.addOption(if (o.playlist) "--yes-playlist" else "--no-playlist")
        r.addOption("--concurrent-fragments", o.concurrentFragments)

        when (o.preset) {
            Preset.AudioBest -> {
                r.addOption("-f", "ba/b"); r.addOption("-x")
            }
            Preset.AudioM4a -> {
                r.addOption("-f", "ba[ext=m4a]/ba/b"); r.addOption("-x")
                r.addOption("--audio-format", "m4a")
            }
            Preset.AudioMp3 -> {
                r.addOption("-f", "ba/b"); r.addOption("-x")
                r.addOption("--audio-format", "mp3"); r.addOption("--audio-quality", "0")
            }
            Preset.Custom -> {
                val id = requireNotNull(o.formatId) { "No format selected" }
                r.addOption("-f", if (o.formatIsVideoOnly) "$id+ba/$id" else id)
                if (o.compatibleMp4) r.addOption("--merge-output-format", "mp4/mkv")
            }
            else -> {
                r.addOption("-f", "bv*+ba/b")
                val res = o.preset.maxHeight?.let { "res:$it" } ?: "res"
                // H.264/AAC preferred at equal resolution so files play everywhere.
                r.addOption("-S", if (o.compatibleMp4) "$res,vcodec:h264,acodec:m4a" else res)
                if (o.compatibleMp4) r.addOption("--merge-output-format", "mp4/mkv")
            }
        }

        if (!o.preset.isAudio) {
            if (o.subtitles) {
                r.addOption("--write-subs")
                r.addOption("--sub-langs", o.subtitleLangs)
                r.addOption("--embed-subs")
            }
            if (o.embedChapters) r.addOption("--embed-chapters")
        }
        if (o.embedMetadata) r.addOption("--embed-metadata")
        if (o.embedThumbnail) {
            r.addOption("--embed-thumbnail")
            r.addOption("--convert-thumbnails", "jpg")
        }
        if (o.sponsorBlock) r.addOption("--sponsorblock-remove", "sponsor,selfpromo,interaction")

        val extra = splitArgs(o.extraArgs)
        if (extra.isNotEmpty()) r.addCommands(extra)
        return r
    }

    /** Minimal shell-style split: whitespace separated, single/double quotes group. */
    fun splitArgs(s: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var quote: Char? = null
        var inToken = false
        for (c in s) {
            when {
                quote != null && c == quote -> quote = null
                quote != null -> cur.append(c)
                c == '"' || c == '\'' -> { quote = c; inToken = true }
                c.isWhitespace() -> if (inToken) { out += cur.toString(); cur.clear(); inToken = false }
                else -> { cur.append(c); inToken = true }
            }
        }
        if (inToken) out += cur.toString()
        return out
    }
}
