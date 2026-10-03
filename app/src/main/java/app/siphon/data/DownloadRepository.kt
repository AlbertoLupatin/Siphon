package app.siphon.data

import android.content.Context
import android.util.Log
import app.siphon.engine.Engine
import app.siphon.engine.MediaInfo
import app.siphon.service.DownloadService
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class DownloadRepository(
    private val context: Context,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val store = File(context.filesDir, "downloads.json")
    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<DownloadItem>> = _items

    private val jobs = ConcurrentHashMap<String, Job>()
    private val pumpLock = Mutex()

    init {
        @OptIn(FlowPreview::class)
        scope.launch(Dispatchers.IO) { items.debounce(750).collect { save(it) } }
    }

    fun enqueue(url: String, info: MediaInfo?, options: DownloadOptions): DownloadItem {
        val item = DownloadItem(
            id = UUID.randomUUID().toString(),
            url = url,
            title = info?.title ?: url,
            thumbnail = info?.thumbnail,
            uploader = info?.uploader,
            durationSec = info?.durationSec,
            extractor = info?.extractor,
            options = options,
            createdAt = System.currentTimeMillis(),
        )
        _items.update { listOf(item) + it }
        pump()
        return item
    }

    fun cancel(id: String) {
        update(id) { if (it.status.isActive) it.copy(status = Status.Canceled, stage = null, speed = null) else it }
        jobs[id]?.cancel() ?: Engine.cancel(id)
    }

    fun retry(id: String) {
        update(id) {
            it.copy(status = Status.Queued, progress = 0f, error = null, stage = null, speed = null, etaSec = -1, log = "")
        }
        pump()
    }

    fun remove(id: String, deleteFiles: Boolean) {
        cancel(id)
        val item = _items.value.firstOrNull { it.id == id } ?: return
        if (deleteFiles) scope.launch(Dispatchers.IO) { item.outputs.forEach { MediaStoreSaver.delete(context, it) } }
        _items.update { list -> list.filterNot { it.id == id } }
    }

    fun clearFinished() = _items.update { list -> list.filter { it.status.isActive } }

    fun cancelAll() = _items.value.filter { it.status.isActive }.forEach { cancel(it.id) }

    private fun update(id: String, f: (DownloadItem) -> DownloadItem) =
        _items.update { list -> list.map { if (it.id == id) f(it) else it } }

    private fun pump() {
        scope.launch {
            pumpLock.withLock {
                val max = settings.current().maxConcurrent.coerceIn(1, 4)
                val waiting = _items.value
                    .filter { it.status == Status.Queued && !jobs.containsKey(it.id) }
                    .sortedBy { it.createdAt }
                waiting.take((max - jobs.size).coerceAtLeast(0)).forEach { start(it) }
                if (_items.value.any { it.status.isActive }) DownloadService.start(context)
            }
        }
    }

    private fun start(item: DownloadItem) {
        val job = scope.launch(Dispatchers.IO) { runDownload(item) }
        jobs[item.id] = job
        job.invokeOnCompletion {
            jobs.remove(item.id)
            pump()
        }
    }

    private suspend fun runDownload(item: DownloadItem) {
        val dir = File(context.cacheDir, "dl/${item.id}").apply { deleteRecursively(); mkdirs() }
        val log = ArrayDeque<String>()
        fun logLine(l: String) {
            if (l.isBlank()) return
            log.addLast(l)
            while (log.size > 400) log.removeFirst()
        }
        update(item.id) { it.copy(status = Status.Running, stage = "Starting", progress = 0f, error = null) }
        var lastEmit = 0L
        var stage = "Starting"
        var processing = false
        try {
            val req = Engine.buildDownloadRequest(item.url, item.options, dir)
            logLine("$ yt-dlp " + req.buildCommand().joinToString(" "))
            val resp = Engine.run(req, item.id) { progress, eta, line ->
                logLine(line)
                ProgressParser.stage(line)?.let { (s, isPost) -> stage = s; processing = isPost }
                val now = System.currentTimeMillis()
                if (now - lastEmit < 250) return@run
                lastEmit = now
                update(item.id) {
                    if (!it.status.isActive) it else it.copy(
                        status = if (processing) Status.Processing else Status.Running,
                        progress = if (progress >= 0) progress else it.progress,
                        etaSec = eta,
                        speed = ProgressParser.speed(line) ?: it.speed,
                        stage = stage,
                    )
                }
            }
            resp.err?.lines()?.forEach(::logLine)

            update(item.id) { it.copy(status = Status.Processing, stage = "Saving to Downloads", speed = null) }
            val files = dir.walkTopDown()
                .filter { it.isFile && it.extension !in setOf("part", "ytdl", "temp", "json") }
                .sortedBy { it.name }
                .toList()
            if (files.isEmpty()) error("yt-dlp finished but produced no files")
            val outs = files.map { MediaStoreSaver.publish(context, it) }
            update(item.id) {
                it.copy(
                    status = Status.Done, progress = 100f, stage = null, speed = null, etaSec = -1,
                    outputs = outs, finishedAt = System.currentTimeMillis(), log = log.joinToString("\n"),
                )
            }
        } catch (e: CancellationException) {
            update(item.id) { it.copy(status = Status.Canceled, stage = null, speed = null, log = log.joinToString("\n")) }
            throw e
        } catch (e: YoutubeDL.CanceledException) {
            update(item.id) { it.copy(status = Status.Canceled, stage = null, speed = null, log = log.joinToString("\n")) }
        } catch (t: Throwable) {
            Log.w("Siphon", "download failed", t)
            val msg = t.message.orEmpty()
            msg.lines().forEach(::logLine)
            update(item.id) {
                if (it.status == Status.Canceled) it
                else it.copy(
                    status = Status.Failed, stage = null, speed = null,
                    error = ProgressParser.summarizeError(msg) ?: t.javaClass.simpleName,
                    log = log.joinToString("\n"),
                )
            }
        } finally {
            dir.deleteRecursively()
            _items.value.firstOrNull { it.id == item.id }
                ?.takeIf { it.status == Status.Done || it.status == Status.Failed }
                ?.let { DownloadService.notifyFinished(context, it) }
        }
    }

    private fun load(): List<DownloadItem> = try {
        if (!store.exists()) emptyList()
        else json.decodeFromString<List<DownloadItem>>(store.readText()).map {
            // Process died mid-download: surface it instead of silently restarting.
            if (it.status.isActive) it.copy(status = Status.Failed, error = "Interrupted — app was closed", stage = null, speed = null)
            else it
        }
    } catch (t: Throwable) {
        Log.w("Siphon", "could not read history", t); emptyList()
    }

    private fun save(list: List<DownloadItem>) {
        runCatching {
            val tmp = File(store.parentFile, store.name + ".tmp")
            tmp.writeText(json.encodeToString(list))
            tmp.renameTo(store)
        }.onFailure { Log.w("Siphon", "could not save history", it) }
    }
}

object ProgressParser {
    private val speedRe = Regex("""\bat\s+(~?\s*[\d.]+\s*[KMG]?i?B/s)""")
    private val itemRe = Regex("""Downloading (?:item|video) (\d+) of (\d+)""")

    fun speed(line: String): String? = speedRe.find(line)?.groupValues?.get(1)?.replace(" ", "")

    /** @return stage label and whether it is a post-processing step. */
    fun stage(line: String): Pair<String, Boolean>? {
        itemRe.find(line)?.let { return "Item ${it.groupValues[1]}/${it.groupValues[2]}" to false }
        return when {
            line.startsWith("[download] Destination") -> "Downloading" to false
            line.startsWith("[download]") && line.contains("has already been downloaded") -> "Already downloaded" to false
            line.startsWith("[Merger]") -> "Merging" to true
            line.startsWith("[ExtractAudio]") -> "Extracting audio" to true
            line.startsWith("[VideoConvertor]") || line.startsWith("[VideoRemuxer]") -> "Converting" to true
            line.startsWith("[SponsorBlock]") || line.startsWith("[ModifyChapters]") -> "Cutting sponsors" to true
            line.startsWith("[EmbedThumbnail]") || line.startsWith("[ThumbnailsConvertor]") -> "Embedding thumbnail" to true
            line.startsWith("[EmbedSubtitle]") -> "Embedding subtitles" to true
            line.startsWith("[Metadata]") -> "Writing metadata" to true
            line.startsWith("[FixupM3u8]") || line.startsWith("[Fixup") -> "Fixing container" to true
            else -> null
        }
    }

    fun summarizeError(msg: String): String? =
        msg.lines().lastOrNull { it.startsWith("ERROR:") }?.removePrefix("ERROR:")?.trim()
            ?: msg.lines().lastOrNull { it.isNotBlank() }?.trim()?.take(300)
}
