package app.siphon.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ClosedCaption
import androidx.compose.material.icons.outlined.ClosedCaptionDisabled
import androidx.compose.material.icons.outlined.Forward10
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.Replay10
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.outlined.Speaker
import androidx.compose.material.icons.outlined.ZoomInMap
import androidx.compose.material.icons.outlined.ZoomOutMap
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material.icons.automirrored.outlined.VolumeDown
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.siphon.data.Chapter
import app.siphon.ui.components.Thumbnail
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlin.math.abs

data class TrackOption(val id: String, val label: String, val detail: String?, val selected: Boolean)

/** An AirPlay speaker (or other remote output) the user can send audio to. */
data class OutputOption(val id: String, val name: String, val detail: String?, val selected: Boolean)

data class PlayerUiState(
    val title: String = "",
    val subtitle: String? = null,
    val artworkUrl: String? = null,
    val isAudio: Boolean = false,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val ended: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val bufferedMs: Long = 0,
    val chapters: List<Chapter> = emptyList(),
    val speed: Float = 1f,
    val textTracks: List<TrackOption> = emptyList(),
    val audioTracks: List<TrackOption> = emptyList(),
    val hasPrevious: Boolean = false,
    val hasNext: Boolean = false,
    val zoomed: Boolean = false,
    val outputs: List<OutputOption> = emptyList(),
    val outputSearching: Boolean = false,
    val outputNote: String? = null,
    val outputVolume: Float = 0.6f,
    val outputConnecting: Boolean = false,
) {
    val subtitlesOn get() = textTracks.any { it.selected }
    val currentChapterIndex get() = chapters.indexOfLast { it.startSec * 1000 <= positionMs }
    val remoteOutput get() = outputs.firstOrNull { it.selected && it.id != LOCAL_OUTPUT }
}

const val LOCAL_OUTPUT = "local"

interface PlayerActions {
    fun playPause()
    fun seekTo(ms: Long)
    fun seekBy(deltaMs: Long)
    fun previous()
    fun next()
    fun setSpeed(speed: Float)
    fun selectText(id: String?)
    fun selectAudio(id: String)
    fun toggleZoom()
    fun enterPip()
    fun close()
    fun openSystemOutputSwitcher()
    fun selectOutput(id: String)
    fun setOutputVolume(v: Float)
    fun searchOutputs(active: Boolean)
    fun openCaptionSettings()
}

enum class Panel { Chapters, Subtitles, Audio, Speed, Output }

private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

/**
 * Full-screen player chrome. [video] and [subtitles] are slots so the activity can plug in real
 * surfaces while screenshot tests plug in stand-ins.
 */
@Composable
fun PlayerScreen(
    state: PlayerUiState,
    actions: PlayerActions,
    inPip: Boolean,
    video: @Composable (Modifier) -> Unit,
    subtitles: @Composable (Modifier) -> Unit,
    initialControls: Boolean = true,
    initialPanel: Panel? = null,
) {
    var controls by remember { mutableStateOf(initialControls) }
    var panel by remember { mutableStateOf(initialPanel) }
    var scrubbing by remember { mutableStateOf(false) }
    var interaction by remember { mutableIntStateOf(0) }
    var seekFlash by remember { mutableStateOf<Pair<Boolean, Int>?>(null) } // forward?, id
    val focus = remember { FocusRequester() }

    // Hide the chrome after a few idle seconds while something is playing.
    LaunchedEffect(controls, state.isPlaying, panel, scrubbing, interaction) {
        if (controls && state.isPlaying && panel == null && !scrubbing && !state.isAudio) {
            delay(3500); controls = false
        }
    }
    LaunchedEffect(seekFlash) { if (seekFlash != null) { delay(650); seekFlash = null } }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(panel) { if (panel != Panel.Output) actions.searchOutputs(false) else actions.searchOutputs(true) }

    fun poke() { controls = true; interaction++ }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.Spacebar, Key.K, Key.MediaPlayPause -> { actions.playPause(); poke(); true }
                    Key.DirectionLeft, Key.J -> { actions.seekBy(-PlaybackService.SEEK_MS); poke(); true }
                    Key.DirectionRight, Key.L -> { actions.seekBy(PlaybackService.SEEK_MS); poke(); true }
                    Key.C -> { actions.selectText(if (state.subtitlesOn) null else state.textTracks.firstOrNull()?.id); true }
                    Key.Escape -> { if (panel != null) panel = null else actions.close(); true }
                    else -> false
                }
            }
    ) {
        if (state.isAudio) AudioStage(state, Modifier.fillMaxSize()) else video(Modifier.fillMaxSize())

        val subtitleLift by animateDpAsState(if (controls && !inPip) 132.dp else 24.dp, label = "subs")
        subtitles(Modifier.fillMaxSize().padding(bottom = subtitleLift, start = 24.dp, end = 24.dp))

        // Gesture layer: tap toggles chrome, double tap on either side seeks.
        Box(
            Modifier.fillMaxSize().pointerInput(state.isAudio) {
                detectTapGestures(
                    onTap = { if (panel != null) panel = null else { controls = !controls; interaction++ } },
                    onDoubleTap = { o ->
                        val forward = o.x > size.width / 2
                        actions.seekBy(if (forward) PlaybackService.SEEK_MS else -PlaybackService.SEEK_MS)
                        seekFlash = forward to ((seekFlash?.second ?: 0) + 1)
                    },
                )
            }
        )

        seekFlash?.let { (forward, _) -> SeekFlash(forward, Modifier.align(if (forward) Alignment.CenterEnd else Alignment.CenterStart)) }

        if (state.isBuffering && !inPip) {
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(56.dp), color = MaterialTheme.colorScheme.secondary)
        }

        AnimatedVisibility(controls && !inPip, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Chrome(
                state, actions,
                openPanel = { panel = it; poke() },
                onScrub = { scrubbing = it; poke() },
                onAny = ::poke,
            )
        }

        AnimatedVisibility(
            panel != null && !inPip,
            enter = slideInHorizontally { it } + fadeIn(),
            exit = slideOutHorizontally { it } + fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            panel?.let { p -> SidePanel(p, state, actions) { panel = null } }
        }
    }
}

@Composable
private fun Chrome(
    state: PlayerUiState,
    actions: PlayerActions,
    openPanel: (Panel) -> Unit,
    onScrub: (Boolean) -> Unit,
    onAny: () -> Unit,
) {
    val white = Color.White
    Box(Modifier.fillMaxSize()) {
        // Scrims keep white controls legible over bright frames.
        Box(Modifier.fillMaxWidth().height(160.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(0.72f), Color.Transparent))))
        Box(
            Modifier.fillMaxWidth().height(220.dp).align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(0.8f))))
        )

        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            // ---- top bar: title beside the actions on wide screens, under them on narrow ones ----
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val narrow = maxWidth < 600.dp
                val titleBlock = @Composable { m: Modifier ->
                    Column(m) {
                        Text(state.title, style = MaterialTheme.typography.titleLarge, color = white, maxLines = if (narrow) 2 else 1, overflow = TextOverflow.Ellipsis)
                        state.subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = white.copy(0.7f), maxLines = 1) }
                    }
                }
                val actionsRow = @Composable {
                    if (state.textTracks.isNotEmpty()) {
                        ChromeButton(if (state.subtitlesOn) Icons.Outlined.ClosedCaption else Icons.Outlined.ClosedCaptionDisabled, "Subtitles") { openPanel(Panel.Subtitles) }
                    }
                    if (state.audioTracks.size > 1) ChromeButton(Icons.Outlined.Audiotrack, "Audio language") { openPanel(Panel.Audio) }
                    TextButton(onClick = { openPanel(Panel.Speed) }) {
                        Text(speedLabel(state.speed), color = white, style = MaterialTheme.typography.titleSmall)
                    }
                    if (state.chapters.isNotEmpty()) ChromeButton(Icons.AutoMirrored.Outlined.List, "Chapters") { openPanel(Panel.Chapters) }
                    ChromeButton(Icons.Outlined.Speaker, "Play on", tint = if (state.remoteOutput != null) MaterialTheme.colorScheme.secondary else white) {
                        openPanel(Panel.Output)
                    }
                    if (!state.isAudio) ChromeButton(Icons.Outlined.PictureInPictureAlt, "Picture in picture") { actions.enterPip() }
                }
                if (narrow) {
                    Column(Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = actions::close) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Close player", tint = white) }
                            Spacer(Modifier.weight(1f))
                            actionsRow()
                        }
                        titleBlock(Modifier.padding(horizontal = 12.dp))
                    }
                } else {
                    Row(Modifier.padding(start = 8.dp, end = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = actions::close) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Close player", tint = white) }
                        titleBlock(Modifier.weight(1f).padding(start = 4.dp))
                        actionsRow()
                    }
                }
            }

            Spacer(Modifier.weight(if (state.isAudio) 2.4f else 1f))

            // ---- transport ----
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                if (state.hasPrevious) BigIcon(Icons.Outlined.SkipPrevious, "Previous") { actions.previous(); onAny() }
                BigIcon(Icons.Outlined.Replay10, "Back 10 seconds") { actions.seekBy(-PlaybackService.SEEK_MS); onAny() }
                Spacer(Modifier.width(28.dp))
                FilledIconButton(
                    onClick = { actions.playPause(); onAny() },
                    modifier = Modifier.size(84.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary),
                ) {
                    Icon(
                        when {
                            state.ended -> Icons.Outlined.Replay
                            state.isPlaying -> Icons.Outlined.Pause
                            else -> Icons.Outlined.PlayArrow
                        },
                        if (state.isPlaying) "Pause" else "Play",
                        modifier = Modifier.size(44.dp),
                    )
                }
                Spacer(Modifier.width(28.dp))
                BigIcon(Icons.Outlined.Forward10, "Forward 10 seconds") { actions.seekBy(PlaybackService.SEEK_MS); onAny() }
                if (state.hasNext) BigIcon(Icons.Outlined.SkipNext, "Next") { actions.next(); onAny() }
            }

            Spacer(Modifier.weight(1f))

            // ---- timeline ----
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 12.dp)) {
                val chapter = state.chapters.getOrNull(state.currentChapterIndex)
                if (chapter != null) {
                    Text(
                        chapter.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.clickable { openPanel(Panel.Chapters) }.padding(bottom = 2.dp),
                    )
                }
                ChapterSeekBar(
                    positionMs = state.positionMs, durationMs = state.durationMs, bufferedMs = state.bufferedMs,
                    chapters = state.chapters, onSeek = actions::seekTo, onScrubbing = onScrub,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(formatTime(state.positionMs), style = timeStyle(), color = white)
                    Spacer(Modifier.weight(1f))
                    Text("−" + formatTime((state.durationMs - state.positionMs).coerceAtLeast(0)), style = timeStyle(), color = white.copy(0.7f))
                    if (!state.isAudio) {
                        ChromeButton(if (state.zoomed) Icons.Outlined.ZoomInMap else Icons.Outlined.ZoomOutMap, if (state.zoomed) "Fit to screen" else "Fill screen") {
                            actions.toggleZoom(); onAny()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun timeStyle() = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum")

@Composable
private fun ChromeButton(icon: ImageVector, label: String, tint: Color = Color.White, onClick: () -> Unit) =
    IconButton(onClick = onClick) { Icon(icon, label, tint = tint) }

@Composable
private fun BigIcon(icon: ImageVector, label: String, onClick: () -> Unit) =
    IconButton(onClick = onClick, modifier = Modifier.size(64.dp)) { Icon(icon, label, tint = Color.White, modifier = Modifier.size(36.dp)) }

/** Seek bar split into chapter segments, with buffered range and a scrub preview. */
@Composable
fun ChapterSeekBar(
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    chapters: List<Chapter>,
    onSeek: (Long) -> Unit,
    onScrubbing: (Boolean) -> Unit,
) {
    var scrub by remember { mutableFloatStateOf(-1f) }
    val duration = durationMs.coerceAtLeast(1)
    val fraction = if (scrub >= 0) scrub else (positionMs.toFloat() / duration).coerceIn(0f, 1f)
    val played = MaterialTheme.colorScheme.primary
    val thick by animateDpAsState(if (scrub >= 0) 8.dp else 4.dp, label = "thick")

    BoxWithConstraints(Modifier.fillMaxWidth().height(44.dp)) {
        val widthPx = constraints.maxWidth.toFloat()
        if (scrub >= 0) {
            val previewMs = (scrub * duration).toLong()
            val title = chapters.lastOrNull { it.startSec * 1000 <= previewMs }?.title
            val bubbleX = (maxWidth * scrub - 60.dp).coerceIn(0.dp, maxWidth - 120.dp)
            Surface(
                color = Color.Black.copy(0.8f), shape = RoundedCornerShape(10.dp),
                modifier = Modifier.offset(x = bubbleX, y = (-44).dp).widthIn(min = 120.dp, max = 240.dp),
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Text(formatTime(previewMs), style = timeStyle(), color = Color.White)
                    title?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
        Canvas(
            Modifier.fillMaxSize()
                .pointerInput(duration) {
                    detectTapGestures { o -> onSeek(((o.x / size.width).coerceIn(0f, 1f) * duration).toLong()) }
                }
                .pointerInput(duration) {
                    detectHorizontalDragGestures(
                        onDragStart = { o -> scrub = (o.x / size.width).coerceIn(0f, 1f); onScrubbing(true) },
                        onDragEnd = { onSeek((scrub * duration).toLong()); scrub = -1f; onScrubbing(false) },
                        onDragCancel = { scrub = -1f; onScrubbing(false) },
                        onHorizontalDrag = { change, dx -> change.consume(); scrub = (scrub + dx / size.width).coerceIn(0f, 1f) },
                    )
                }
        ) {
            val h = thick.toPx()
            val y = size.height / 2 - h / 2
            val gap = 3.dp.toPx()
            // Segment boundaries in px; no chapters means one segment.
            val bounds = (chapters.map { (it.startSec * 1000 / duration).toFloat() } + 1f)
                .filter { it in 0f..1f }.distinct().sorted().let { if (it.firstOrNull() != 0f) listOf(0f) + it else it }
            val buffered = (bufferedMs.toFloat() / duration).coerceIn(0f, 1f)
            for (i in 0 until bounds.size - 1) {
                val a = bounds[i] * widthPx + if (i > 0) gap / 2 else 0f
                val b = bounds[i + 1] * widthPx - if (i < bounds.size - 2) gap / 2 else 0f
                if (b <= a) continue
                val r = CornerRadius(h / 2)
                drawRoundRect(Color.White.copy(0.25f), Offset(a, y), Size(b - a, h), r)
                val bufEnd = (buffered * widthPx).coerceIn(a, b)
                if (bufEnd > a) drawRoundRect(Color.White.copy(0.35f), Offset(a, y), Size(bufEnd - a, h), r)
                val playEnd = (fraction * widthPx).coerceIn(a, b)
                if (playEnd > a) drawRoundRect(played, Offset(a, y), Size(playEnd - a, h), r)
            }
            drawCircle(played, radius = (if (scrub >= 0) 10.dp else 7.dp).toPx(), center = Offset(fraction * widthPx, size.height / 2))
        }
    }
}

@Composable
private fun SeekFlash(forward: Boolean, modifier: Modifier) {
    Box(
        modifier.fillMaxHeight().width(220.dp)
            .background(
                Brush.horizontalGradient(
                    if (forward) listOf(Color.Transparent, Color.White.copy(0.18f)) else listOf(Color.White.copy(0.18f), Color.Transparent)
                )
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (forward) Icons.Outlined.Forward10 else Icons.Outlined.Replay10, null, tint = Color.White, modifier = Modifier.size(40.dp))
        }
    }
}

/** Audio-only playback: the artwork takes the stage over a blurred copy of itself, above the transport. */
@Composable
private fun AudioStage(state: PlayerUiState, modifier: Modifier) {
    Box(modifier) {
        if (state.artworkUrl != null) {
            AsyncImage(
                state.artworkUrl, null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(80.dp).alpha(0.5f),
            )
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(0.35f)))
        BoxWithConstraints(
            Modifier.fillMaxSize().safeDrawingPadding().padding(top = 96.dp, bottom = 280.dp),
            contentAlignment = Alignment.Center,
        ) {
            val width = minOf(maxWidth * 0.7f, maxHeight * 16f / 9f, 640.dp)
            Thumbnail(state.artworkUrl, isAudio = true, modifier = Modifier.width(width), corner = 24.dp)
        }
    }
}

@Composable
private fun SidePanel(panel: Panel, state: PlayerUiState, actions: PlayerActions, onClose: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxHeight()) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp),
            modifier = Modifier.fillMaxHeight().width(minOf(380.dp, maxWidth * 0.9f)),
        ) {
            Column(Modifier.safeDrawingPadding().padding(top = 12.dp)) {
                Row(Modifier.padding(start = 24.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when (panel) {
                            Panel.Chapters -> "Chapters"
                            Panel.Subtitles -> "Subtitles"
                            Panel.Audio -> "Audio language"
                            Panel.Speed -> "Playback speed"
                            Panel.Output -> "Play on"
                        },
                        style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "Close panel") }
                }
                Spacer(Modifier.height(8.dp))
                when (panel) {
                    Panel.Chapters -> ChapterList(state, actions)
                    Panel.Subtitles -> Column {
                        OptionRow("Off", null, !state.subtitlesOn) { actions.selectText(null) }
                        state.textTracks.forEach { t -> OptionRow(t.label, t.detail, t.selected) { actions.selectText(t.id) } }
                        TextButton(onClick = actions::openCaptionSettings, modifier = Modifier.padding(start = 12.dp, top = 8.dp)) {
                            Text("Text size and style")
                        }
                    }
                    Panel.Audio -> Column {
                        state.audioTracks.forEach { t -> OptionRow(t.label, t.detail, t.selected) { actions.selectAudio(t.id) } }
                    }
                    Panel.Speed -> Column {
                        SPEEDS.forEach { s -> OptionRow(speedLabel(s), if (s == 1f) "Normal" else null, state.speed == s) { actions.setSpeed(s) } }
                    }
                    Panel.Output -> OutputList(state, actions)
                }
            }
        }
    }
}

@Composable
private fun ChapterList(state: PlayerUiState, actions: PlayerActions) {
    val current = state.currentChapterIndex
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (current - 2).coerceAtLeast(0))
    LazyColumn(state = list) {
        itemsIndexed(state.chapters) { i, c ->
            val on = i == current
            Row(
                Modifier.fillMaxWidth().clickable { actions.seekTo((c.startSec * 1000).toLong()) }
                    .background(if (on) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                    .padding(horizontal = 24.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    formatTime((c.startSec * 1000).toLong()), style = timeStyle(),
                    color = if (on) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(72.dp),
                )
                Text(
                    c.title.ifBlank { "Chapter ${i + 1}" }, style = MaterialTheme.typography.bodyLarge,
                    color = if (on) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun OutputList(state: PlayerUiState, actions: PlayerActions) {
    Column {
        OptionRow(
            "This device", "Speakers, or Bluetooth headphones like AirPods",
            state.remoteOutput == null,
        ) { actions.selectOutput(LOCAL_OUTPUT) }
        TextButton(onClick = actions::openSystemOutputSwitcher, modifier = Modifier.padding(start = 12.dp)) {
            Text("Choose a Bluetooth device")
        }
        Text(
            "AirPlay speakers", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 24.dp, top = 20.dp, bottom = 6.dp),
        )
        val remote = state.outputs.filter { it.id != LOCAL_OUTPUT }
        remote.forEach { o ->
            OptionRow(o.name, o.detail, o.selected) { actions.selectOutput(o.id) }
            if (o.selected) {
                Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Outlined.VolumeDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Slider(
                        value = state.outputVolume, onValueChange = actions::setOutputVolume,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    )
                    Icon(Icons.AutoMirrored.Outlined.VolumeUp, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (state.outputConnecting) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.secondary)
        }
        if (remote.isEmpty()) {
            Row(Modifier.padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (state.outputSearching) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text(
                    if (state.outputSearching) "Looking for HomePods and AirPlay speakers on this network" else "None found on this network",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        state.outputNote?.let {
            Text(
                it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun OptionRow(label: String, detail: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (selected) Icon(Icons.Outlined.Check, "Selected", tint = MaterialTheme.colorScheme.primary)
    }
}

fun speedLabel(s: Float): String = (if (s % 1f == 0f) "${s.toInt()}" else "$s".trimEnd('0')) + "×"

fun formatTime(ms: Long): String {
    val total = abs(ms) / 1000
    val h = total / 3600; val m = (total % 3600) / 60; val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
