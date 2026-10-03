package app.siphon.player

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.media.MediaRouter2
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Rational
import android.view.SurfaceView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.SubtitleView
import app.siphon.R
import app.siphon.SiphonApp
import app.siphon.data.DownloadItem
import app.siphon.ui.theme.SiphonTheme
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

class PlayerActivity : ComponentActivity(), PlayerActions {

    private val repo by lazy { SiphonApp.get(this).downloads }
    private val outputs by lazy { SiphonApp.get(this).outputs }
    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var item: DownloadItem? = null
    private val state = MutableStateFlow(PlayerUiState())
    private var inPip by mutableStateOf(false)

    private var surface: SurfaceView? = null
    private var frame: AspectRatioFrameLayout? = null
    private var subtitleView: SubtitleView? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            refresh()
            if (events.contains(Player.EVENT_IS_PLAYING_CHANGED)) {
                if (player.isPlaying) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); savePosition() }
                updatePip()
            }
        }
        override fun onCues(cueGroup: CueGroup) { subtitleView?.setCues(cueGroup.cues) }
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) {
                frame?.setAspectRatio(videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height)
            }
            updatePip()
        }
    }

    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getStringExtra(EXTRA_PIP_ACTION)) {
                PIP_PLAY_PAUSE -> playPause()
                PIP_BACK -> seekBy(-PlaybackService.SEEK_MS)
                PIP_FORWARD -> seekBy(PlaybackService.SEEK_MS)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        item = intent.getStringExtra(EXTRA_ID)?.let(repo::find)
        immersive()
        ContextCompat.registerReceiver(this, pipReceiver, IntentFilter(ACTION_PIP), ContextCompat.RECEIVER_NOT_EXPORTED)

        setContent {
            SiphonTheme(dark = true) {
                val s by state.collectAsStateWithLifecycle()
                PlayerScreen(
                    state = s,
                    actions = this,
                    inPip = inPip,
                    video = { m ->
                        AndroidView(
                            factory = { ctx ->
                                AspectRatioFrameLayout(ctx).apply {
                                    val sv = SurfaceView(ctx)
                                    addView(sv)
                                    surface = sv
                                    frame = this
                                    controller?.setVideoSurfaceView(sv)
                                }
                            },
                            modifier = m,
                        )
                    },
                    subtitles = { m ->
                        AndroidView(
                            factory = { ctx ->
                                SubtitleView(ctx).apply {
                                    setUserDefaultStyle()      // follows Android's caption settings
                                    setUserDefaultTextSize()
                                    subtitleView = this
                                }
                            },
                            modifier = m,
                        )
                    },
                )
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { while (true) { refresh(); delay(250) } }
                launch { outputs.state.collect { refresh() } }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        item = repo.find(id)
        controller?.let { load(it, force = true) }
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val f = MediaController.Builder(this, token).buildAsync()
        future = f
        f.addListener({
            val c = runCatching { f.get() }.getOrNull() ?: return@addListener
            controller = c
            c.addListener(listener)
            surface?.let(c::setVideoSurfaceView)
            load(c, force = false)
            refresh()
        }, MoreExecutors.directExecutor())
    }

    override fun onStop() {
        savePosition()
        controller?.let { c ->
            // Video stops when it leaves the screen; audio keeps playing in the background.
            if (!inPip && !isChangingConfigurations && !state.value.isAudio && outputs.state.value.active == null) c.pause()
            c.removeListener(listener)
            c.clearVideoSurface()
        }
        future?.let(MediaController::releaseFuture)
        controller = null
        super.onStop()
    }

    override fun onDestroy() {
        unregisterReceiver(pipReceiver)
        super.onDestroy()
    }

    /** Loads [item] into the session unless it is already the current media (e.g. reopened from the notification). */
    private fun load(c: MediaController, force: Boolean) {
        val current = c.currentMediaItem?.mediaId
        val it = item ?: current?.substringBefore('/')?.let(repo::find)?.also { found -> item = found } ?: return
        if (!force && current?.startsWith(it.id + "/") == true) return
        val playable = it.outputs.withIndex().filter { (_, out) -> out.isPlayable }
        val media = playable.map { (i, out) ->
            MediaItem.Builder()
                .setMediaId("${it.id}/$i")
                .setUri(out.uri)
                .setMimeType(out.mime)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(if (playable.size > 1) cleanName(out.name) else it.title)
                        .setArtist(it.uploader)
                        .setArtworkUri(it.thumbnail?.toUri())
                        .build()
                )
                .build()
        }
        if (media.isEmpty()) return
        val resume = it.resume?.takeIf { r -> r.positionMs > 5_000 }
        val startIndex = playable.indexOfFirst { (i, _) -> i == resume?.outputIndex }
        if (startIndex >= 0) c.setMediaItems(media, startIndex, resume!!.positionMs) else c.setMediaItems(media)
        c.prepare()
        c.play()
    }

    /** Position in [DownloadItem.outputs] of the playing item (media ids are "itemId/outputIndex"). */
    private fun currentOutput(c: Player): Int? = c.currentMediaItem?.mediaId?.substringAfter('/')?.toIntOrNull()

    private fun cleanName(file: String) =
        file.substringBeforeLast('.').replace(Regex("""^\d{2,4} - """), "").replace(Regex("""\s*\[[^\]]+]$"""), "")

    private fun refresh() {
        val c = controller ?: return
        val it = item
        val output = currentOutput(c)?.let { i -> it?.outputs?.getOrNull(i) }
        val (text, audio) = tracks(c.currentTracks)
        val out = outputs.state.value
        state.value = state.value.copy(
            title = c.mediaMetadata.title?.toString() ?: it?.title.orEmpty(),
            subtitle = c.mediaMetadata.artist?.toString(),
            artworkUrl = it?.thumbnail,
            isAudio = output?.isAudio ?: (c.videoSize == VideoSize.UNKNOWN),
            isPlaying = c.isPlaying,
            isBuffering = c.playbackState == Player.STATE_BUFFERING,
            ended = c.playbackState == Player.STATE_ENDED,
            positionMs = c.currentPosition,
            durationMs = c.duration.takeIf { d -> d != C.TIME_UNSET } ?: 0,
            bufferedMs = c.bufferedPosition,
            chapters = output?.chapters.orEmpty(),
            speed = c.playbackParameters.speed,
            textTracks = text,
            audioTracks = audio,
            hasPrevious = c.hasPreviousMediaItem(),
            hasNext = c.hasNextMediaItem(),
            outputs = out.devices.map { d -> OutputOption(d.id, d.name, d.detail, d.id == out.active?.id) },
            outputVolume = out.volume,
            outputConnecting = out.connecting,
            outputSearching = out.searching,
            outputNote = out.message,
        )
    }

    private fun tracks(t: Tracks): Pair<List<TrackOption>, List<TrackOption>> {
        val text = mutableListOf<TrackOption>()
        val audio = mutableListOf<TrackOption>()
        t.groups.forEachIndexed { g, group ->
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                val f = group.getTrackFormat(i)
                val option = TrackOption("$g:$i", label(f, group.type, text.size + audio.size), detail(f, group.type), group.isTrackSelected(i))
                when (group.type) {
                    C.TRACK_TYPE_TEXT -> if (!isChapterTrack(f)) text += option
                    C.TRACK_TYPE_AUDIO -> audio += option
                }
            }
        }
        return text to audio
    }

    /** ffmpeg writes MP4 chapters as a QuickTime text track too; it is not a subtitle. */
    private fun isChapterTrack(f: Format) = f.label.equals("Chapters", true) || f.id?.contains("chap", true) == true

    private fun label(f: Format, type: Int, n: Int): String {
        val lang = f.language?.takeIf { it != "und" && it != C.LANGUAGE_UNDETERMINED }
            ?.let { Locale.forLanguageTag(it).getDisplayName(Locale.getDefault()).replaceFirstChar(Char::titlecase) }
        return f.label ?: lang ?: if (type == C.TRACK_TYPE_AUDIO) "Audio ${n + 1}" else "Subtitles ${n + 1}"
    }

    private fun detail(f: Format, type: Int): String? = when (type) {
        C.TRACK_TYPE_AUDIO -> when (f.channelCount) {
            1 -> "Mono"; 2 -> "Stereo"; 6 -> "5.1"; 8 -> "7.1"; else -> null
        }
        else -> if (f.selectionFlags and C.SELECTION_FLAG_FORCED != 0) "Forced" else null
    }

    private fun savePosition() {
        val c = controller ?: return
        val id = c.currentMediaItem?.mediaId?.substringBefore('/') ?: return
        val ended = c.playbackState == Player.STATE_ENDED || (c.duration > 0 && c.duration - c.currentPosition < 10_000)
        repo.savePosition(id, currentOutput(c) ?: 0, if (ended) 0 else c.currentPosition)
    }

    private fun immersive() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    // ---- picture in picture ----

    private fun pipParams(): PictureInPictureParams? {
        val c = controller ?: return null
        val v = c.videoSize
        val ratio = if (v.width > 0 && v.height > 0) {
            Rational(v.width, v.height).let { r -> if (r.toFloat() > 2.39f) Rational(239, 100) else if (r.toFloat() < 0.42f) Rational(100, 239) else r }
        } else Rational(16, 9)
        fun action(icon: Int, title: String, code: String, req: Int) = RemoteAction(
            Icon.createWithResource(this, icon), title, title,
            PendingIntent.getBroadcast(this, req, Intent(ACTION_PIP).setPackage(packageName).putExtra(EXTRA_PIP_ACTION, code), PendingIntent.FLAG_IMMUTABLE),
        )
        val b = PictureInPictureParams.Builder()
            .setAspectRatio(ratio)
            .setActions(
                listOf(
                    action(R.drawable.ic_pip_back, "Back 10 seconds", PIP_BACK, 1),
                    action(if (c.isPlaying) R.drawable.ic_pip_pause else R.drawable.ic_pip_play, if (c.isPlaying) "Pause" else "Play", PIP_PLAY_PAUSE, 2),
                    action(R.drawable.ic_pip_forward, "Forward 10 seconds", PIP_FORWARD, 3),
                )
            )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            b.setAutoEnterEnabled(c.isPlaying && !state.value.isAudio)
            b.setSeamlessResizeEnabled(true)
        }
        return b.build()
    }

    private fun updatePip() { pipParams()?.let { runCatching { setPictureInPictureParams(it) } } }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && controller?.isPlaying == true && !state.value.isAudio) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
    }

    // ---- PlayerActions ----

    override fun playPause() {
        val c = controller ?: return
        when {
            c.playbackState == Player.STATE_ENDED -> { c.seekTo(0); c.play() }
            c.isPlaying -> c.pause()
            else -> c.play()
        }
    }

    override fun seekTo(ms: Long) { controller?.seekTo(ms.coerceAtLeast(0)) }
    override fun seekBy(deltaMs: Long) { controller?.let { it.seekTo((it.currentPosition + deltaMs).coerceAtLeast(0)) } }
    override fun previous() { controller?.seekToPreviousMediaItem() }
    override fun next() { controller?.seekToNextMediaItem() }
    override fun setSpeed(speed: Float) { controller?.setPlaybackSpeed(speed) }

    override fun selectText(id: String?) {
        val c = controller ?: return
        val b = c.trackSelectionParameters.buildUpon()
        if (id == null) {
            b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).clearOverridesOfType(C.TRACK_TYPE_TEXT)
        } else {
            val (g, i) = id.split(':').map(String::toInt)
            val group = c.currentTracks.groups.getOrNull(g) ?: return
            b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
        }
        c.trackSelectionParameters = b.build()
    }

    override fun selectAudio(id: String) {
        val c = controller ?: return
        val (g, i) = id.split(':').map(String::toInt)
        val group = c.currentTracks.groups.getOrNull(g) ?: return
        c.trackSelectionParameters = c.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i)).build()
    }

    override fun toggleZoom() {
        val zoom = !state.value.zoomed
        frame?.resizeMode = if (zoom) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
        state.value = state.value.copy(zoomed = zoom)
    }

    override fun enterPip() { pipParams()?.let { runCatching { enterPictureInPictureMode(it) } } }

    override fun close() {
        savePosition()
        controller?.pause()
        finish()
    }

    override fun openSystemOutputSwitcher() {
        val shown = Build.VERSION.SDK_INT >= 34 && runCatching { MediaRouter2.getInstance(this).showSystemOutputSwitcher() }.getOrDefault(false)
        if (shown) return
        val panel = Intent("com.android.settings.panel.action.MEDIA_OUTPUT")
            .putExtra("com.android.settings.panel.extra.PACKAGE_NAME", packageName)
        if (runCatching { startActivity(panel) }.isFailure) runCatching { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
    }

    override fun selectOutput(id: String) {
        if (id == LOCAL_OUTPUT) outputs.useLocal() else outputs.use(id)
    }

    override fun setOutputVolume(v: Float) = outputs.setVolume(v)

    override fun searchOutputs(active: Boolean) { if (active) outputs.startDiscovery() else outputs.stopDiscovery() }

    override fun openCaptionSettings() { runCatching { startActivity(Intent(Settings.ACTION_CAPTIONING_SETTINGS)) } }

    companion object {
        private const val EXTRA_ID = "item"
        private const val ACTION_PIP = "app.siphon.PIP"
        private const val EXTRA_PIP_ACTION = "action"
        private const val PIP_PLAY_PAUSE = "pp"
        private const val PIP_BACK = "back"
        private const val PIP_FORWARD = "fwd"

        fun start(context: Context, itemId: String) {
            context.startActivity(
                Intent(context, PlayerActivity::class.java).putExtra(EXTRA_ID, itemId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
