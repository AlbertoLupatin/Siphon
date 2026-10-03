package app.siphon.ui.download

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ClosedCaption
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material.icons.outlined.Search
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.siphon.data.DownloadOptions
import app.siphon.data.Preset
import app.siphon.engine.Engine
import app.siphon.engine.MediaFormat
import app.siphon.engine.MediaInfo
import app.siphon.engine.estimate
import app.siphon.ui.components.Facts
import app.siphon.ui.components.GroupLabel
import app.siphon.ui.components.Logo
import app.siphon.ui.components.Stamp
import app.siphon.ui.components.Thumbnail
import app.siphon.ui.components.formatDuration
import app.siphon.ui.components.formatSize
import coil3.compose.AsyncImage

/** Everything the download screen can ask for; implemented by [DownloadViewModel], faked in screenshot tests. */
interface DownloadActions {
    fun setUrl(url: String)
    fun acceptText(text: String)
    fun fetch()
    fun download()
    fun setPlaylist(on: Boolean)
    fun selectPreset(p: Preset)
    fun selectFormat(f: MediaFormat)
    fun toggleFormats()
    fun updateOptions(f: (DownloadOptions) -> DownloadOptions)
}

@Composable
fun DownloadScreen(
    state: DownloadUiState,
    engine: Engine.State,
    actions: DownloadActions,
    twoPane: Boolean,
    queue: @Composable (Modifier) -> Unit,
) {
    if (twoPane) {
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                Column(Modifier.widthIn(max = 860.dp).padding(start = 40.dp, end = 40.dp, top = 28.dp, bottom = 40.dp)) {
                    Composer(state, engine, actions)
                }
            }
            // The queue sits in a tray along the right edge, a step darker than the canvas.
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(topStart = 32.dp),
                modifier = Modifier.width(400.dp).fillMaxHeight().padding(top = 12.dp),
            ) { queue(Modifier.fillMaxSize()) }
        }
    } else {
        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 760.dp).padding(horizontal = 20.dp, vertical = 16.dp)) {
                Composer(state, engine, actions)
                Spacer(Modifier.height(28.dp))
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large) {
                    queue(Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 640.dp))
                }
            }
        }
    }
}

@Composable
private fun Composer(state: DownloadUiState, engine: Engine.State, actions: DownloadActions) =
    BoxWithConstraints {
        val roomy = maxWidth >= 600.dp
        Column { ComposerBody(state, engine, actions, roomy) }
    }

@Composable
private fun ComposerBody(state: DownloadUiState, engine: Engine.State, actions: DownloadActions, roomy: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("New download", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
        EngineStatus(engine)
    }
    Spacer(Modifier.height(20.dp))
    UrlBar(state, actions, roomy)
    val showPreviewless = state.url.isNotBlank() && state.fetch !is FetchState.Loaded
    if (state.looksLikePlaylist || showPreviewless) {
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state.looksLikePlaylist) {
                FilterChip(
                    selected = state.playlist,
                    onClick = { actions.setPlaylist(!state.playlist) },
                    label = { Text("Whole playlist") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Outlined.PlaylistPlay, null, Modifier.size(18.dp)) },
                )
            }
            Spacer(Modifier.weight(1f))
            if (showPreviewless) TextButton(onClick = actions::download) { Text("Download without preview") }
        }
    }

    Box(Modifier.padding(top = 20.dp).animateContentSize()) {
        when (val f = state.fetch) {
            FetchState.Idle -> if (state.url.isBlank()) Welcome()
            FetchState.Loading -> Reading(state.url, roomy)
            is FetchState.Error -> FetchError(f.message)
            is FetchState.Loaded -> Loaded(f.info, state, actions, roomy)
        }
    }
}

@Composable
private fun EngineStatus(engine: Engine.State) {
    val (label, color) = when (engine) {
        Engine.State.Initializing -> "Starting yt-dlp" to MaterialTheme.colorScheme.onSurfaceVariant
        is Engine.State.Updating -> "Updating yt-dlp" to MaterialTheme.colorScheme.secondary
        is Engine.State.Ready -> "yt-dlp ${engine.version}" to MaterialTheme.colorScheme.onSurfaceVariant
        is Engine.State.Failed -> "yt-dlp failed to start" to MaterialTheme.colorScheme.error
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(8.dp).background(
                when (engine) {
                    is Engine.State.Ready -> MaterialTheme.colorScheme.tertiary
                    is Engine.State.Failed -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.secondary
                },
                CircleShape,
            )
        )
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@Composable
private fun UrlBar(state: DownloadUiState, actions: DownloadActions, roomy: Boolean) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Surface(shape = RoundedCornerShape(32.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.height(64.dp).padding(start = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = state.url,
                onValueChange = actions::setUrl,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Paste a link to a video, song or playlist", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Icon(Icons.Outlined.Link, null) },
                trailingIcon = {
                    if (state.url.isNotEmpty()) {
                        IconButton(onClick = { actions.setUrl("") }) { Icon(Icons.Outlined.Close, "Clear link") }
                    } else {
                        IconButton(onClick = {
                            scope.launch {
                                clipboard.getClipEntry()?.clipData?.takeIf { it.itemCount > 0 }
                                    ?.getItemAt(0)?.text?.toString()?.let(actions::acceptText)
                            }
                        }) {
                            Icon(Icons.Outlined.ContentPaste, "Paste link")
                        }
                    }
                },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { actions.fetch() }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )
            val canFetch = state.url.isNotBlank() && state.fetch !is FetchState.Loading
            if (roomy) {
                Button(onClick = actions::fetch, enabled = canFetch, modifier = Modifier.height(48.dp)) { Text("Look up") }
            } else {
                FilledIconButton(onClick = actions::fetch, enabled = canFetch, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Outlined.Search, "Look up")
                }
            }
        }
    }
}

@Composable
private fun Welcome() {
    Column(Modifier.padding(top = 40.dp)) {
        Logo(64.dp)
        Spacer(Modifier.height(24.dp))
        Text(
            "Save a video or song from the web.",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.widthIn(max = 520.dp),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Paste a link above, or share one to Siphon from YouTube, your browser or any other app. " +
                "Files land in Download › Siphon.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(max = 560.dp),
        )
        Spacer(Modifier.height(28.dp))
        Text(
            "Works with YouTube, Vimeo, SoundCloud, Bandcamp, X, Instagram, TikTok, Twitch, Reddit " +
                "and about 1,800 other sites.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(max = 560.dp),
        )
    }
}

@Composable
private fun Reading(url: String, wide: Boolean) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.width(if (wide) 280.dp else 140.dp).height(if (wide) 158.dp else 79.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(18.dp))
            )
            Spacer(Modifier.width(24.dp))
            Column(Modifier.weight(1f)) {
                Text("Reading the page", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    url.substringAfter("://").substringBefore('/'), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                )
                Spacer(Modifier.height(16.dp))
                LinearProgressIndicator(
                    Modifier.width(200.dp),
                    color = MaterialTheme.colorScheme.secondary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                )
            }
        }
    }
}

@Composable
private fun FetchError(message: String) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(20.dp)) {
            Icon(Icons.Outlined.WarningAmber, null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(14.dp))
            Column {
                Text("yt-dlp couldn't read this link", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onErrorContainer)
                Spacer(Modifier.height(4.dp))
                Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                Spacer(Modifier.height(10.dp))
                Text(
                    "Check the link is public, try Download without preview, or update yt-dlp in Settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
                )
            }
        }
    }
}

@Composable
private fun MediaHeader(info: MediaInfo, wide: Boolean) {
    val container = MaterialTheme.colorScheme.surfaceContainer
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(container)) {
        if (info.thumbnail != null) {
            // Blurred still of the media itself as the backdrop, washed toward the surface colour.
            AsyncImage(
                model = info.thumbnail, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize().blur(56.dp), alpha = 0.55f,
            )
            Box(Modifier.matchParentSize().background(Brush.horizontalGradient(listOf(container.copy(alpha = 0.35f), container.copy(alpha = 0.92f)))))
        }
        val thumb = @Composable { m: Modifier ->
            Thumbnail(
                info.thumbnail, isAudio = false, modifier = m, corner = 18.dp,
                badge = {
                    val d = if (info.isPlaylist) "${info.entries.size} videos" else formatDuration(info.durationSec)
                    if (d != null) Stamp(d)
                },
            )
        }
        val text = @Composable { m: Modifier ->
            Column(m) {
                info.extractor?.let {
                    Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
                    Spacer(Modifier.height(6.dp))
                }
                Text(info.title, style = MaterialTheme.typography.headlineSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                info.uploader?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (info.isPlaylist) {
                    Spacer(Modifier.height(12.dp))
                    info.entries.take(4).forEachIndexed { i, e ->
                        Text(
                            "${i + 1}.  ${e.title}", style = MaterialTheme.typography.bodySmall, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (info.entries.size > 4) {
                        Text("and ${info.entries.size - 4} more", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (wide) {
            Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                thumb(Modifier.width(300.dp))
                Spacer(Modifier.width(28.dp))
                text(Modifier.weight(1f))
            }
        } else {
            Column(Modifier.padding(16.dp)) {
                thumb(Modifier.fillMaxWidth())
                Spacer(Modifier.height(16.dp))
                text(Modifier)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Loaded(info: MediaInfo, state: DownloadUiState, actions: DownloadActions, wide: Boolean) {
    val o = state.options
    val estimates = remember(info, o.compatibleMp4) { Preset.entries.associateWith { info.estimate(it, o.compatibleMp4) } }
    Column {
        MediaHeader(info, wide)

        GroupLabel("Video")
        val columns = if (wide) 6 else 3
        TileGrid(VIDEO_TILES.size, columns) { i, slot ->
            VIDEO_TILES[i].let { (p, title) ->
                val e = estimates.getValue(p)
                QualityTile(
                    title = title,
                    size = if (e.available) formatSize(e.sizeBytes) else "Not offered",
                    detail = when {
                        !e.available -> null
                        p == Preset.Best -> e.resolution
                        else -> e.resolution?.takeIf { it.endsWith("60") }?.let { "60 fps" }
                    },
                    selected = o.preset == p,
                    enabled = e.available,
                    audio = false,
                    onClick = { actions.selectPreset(p) },
                    modifier = slot,
                )
            }
        }

        GroupLabel("Audio only")
        TileGrid(AUDIO_TILES.size, columns) { i, slot ->
            AUDIO_TILES[i].let { (p, title) ->
                val e = estimates.getValue(p)
                QualityTile(
                    title = title,
                    size = formatSize(e.sizeBytes),
                    detail = e.resolution,
                    selected = o.preset == p,
                    enabled = true,
                    audio = true,
                    onClick = { actions.selectPreset(p) },
                    modifier = slot,
                )
            }
        }

        if (info.formats.isNotEmpty()) {
            TextButton(onClick = actions::toggleFormats, modifier = Modifier.padding(top = 10.dp)) {
                Icon(if (state.showFormats) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
                Spacer(Modifier.width(6.dp))
                Text(if (o.preset == Preset.Custom) "Using format ${o.formatId}" else "Choose a specific format")
            }
            AnimatedVisibility(state.showFormats) {
                FormatList(info.formats, o.formatId.takeIf { o.preset == Preset.Custom }, actions::selectFormat)
            }
        }

        GroupLabel("Extras")
        OptionChips(o, isAudio = o.preset.isAudio, actions::updateOptions)

        Spacer(Modifier.height(32.dp))
        val chosen = estimates.getValue(o.preset)
        val summary = when {
            info.isPlaylist -> "${info.entries.size} videos"
            o.preset == Preset.Custom -> "format ${o.formatId}"
            else -> listOfNotNull(chosen.resolution, formatSize(chosen.sizeBytes)?.let { "about $it" }).joinToString(", ")
        }
        Button(
            onClick = actions::download,
            modifier = Modifier.fillMaxWidth().height(60.dp),
            shape = RoundedCornerShape(30.dp),
        ) {
            Icon(Icons.Outlined.Download, null)
            Spacer(Modifier.width(10.dp))
            Text("Download", style = MaterialTheme.typography.titleMedium)
            if (summary.isNotBlank()) {
                Spacer(Modifier.width(12.dp))
                Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.75f))
            }
        }
    }
}

/** Equal-width tile slots, [columns] per row, so video and audio rows line up. */
@Composable
private fun TileGrid(count: Int, columns: Int, tile: @Composable (index: Int, modifier: Modifier) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        (0 until count).chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { i -> tile(i, Modifier.weight(1f)) }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private val VIDEO_TILES = listOf(
    Preset.Best to "Best", Preset.P2160 to "4K", Preset.P1440 to "1440p",
    Preset.P1080 to "1080p", Preset.P720 to "720p", Preset.P480 to "480p",
)
private val AUDIO_TILES = listOf(Preset.AudioBest to "Original", Preset.AudioM4a to "M4A", Preset.AudioMp3 to "MP3")

@Composable
private fun QualityTile(
    title: String,
    size: String?,
    detail: String?,
    selected: Boolean,
    enabled: Boolean,
    audio: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val accent = if (audio) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
    val container = when {
        selected && audio -> MaterialTheme.colorScheme.tertiaryContainer
        selected -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerLow
    }
    val onContainer = when {
        selected && audio -> MaterialTheme.colorScheme.onTertiaryContainer
        selected -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        color = container,
        border = if (selected) BorderStroke(2.dp, accent) else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                title, style = MaterialTheme.typography.titleMedium,
                color = if (enabled) onContainer else onContainer.copy(alpha = 0.38f),
            )
            val sub = if (selected) onContainer.copy(alpha = 0.8f)
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.5f)
            // Fixed three lines so every tile in the grid has the same height.
            Text(size ?: " ", style = MaterialTheme.typography.bodySmall, color = sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail ?: " ", style = MaterialTheme.typography.bodySmall, color = sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OptionChips(o: DownloadOptions, isAudio: Boolean, update: ((DownloadOptions) -> DownloadOptions) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        @Composable
        fun chip(label: String, icon: ImageVector, on: Boolean, enabled: Boolean = true, f: (DownloadOptions) -> DownloadOptions) =
            FilterChip(
                selected = on && enabled, enabled = enabled, onClick = { update(f) },
                label = { Text(label) },
                leadingIcon = { Icon(icon, null, Modifier.size(18.dp)) },
                shape = RoundedCornerShape(12.dp),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    selectedLeadingIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
            )

        chip("Plays everywhere (H.264)", Icons.Outlined.Devices, o.compatibleMp4, !isAudio) { it.copy(compatibleMp4 = !it.compatibleMp4) }
        chip("Subtitles", Icons.Outlined.ClosedCaption, o.subtitles, !isAudio) { it.copy(subtitles = !it.subtitles) }
        chip("Chapters", Icons.Outlined.Bookmarks, o.embedChapters, !isAudio) { it.copy(embedChapters = !it.embedChapters) }
        chip("Cover image", Icons.Outlined.Image, o.embedThumbnail) { it.copy(embedThumbnail = !it.embedThumbnail) }
        chip("Title and artist tags", Icons.Outlined.Sell, o.embedMetadata) { it.copy(embedMetadata = !it.embedMetadata) }
        chip("Skip sponsor segments", Icons.Outlined.ContentCut, o.sponsorBlock) { it.copy(sponsorBlock = !it.sponsorBlock) }
    }
}

@Composable
private fun FormatList(formats: List<MediaFormat>, selected: String?, onSelect: (MediaFormat) -> Unit) {
    val groups = remember(formats) {
        listOf(
            "Video with sound" to formats.filter { it.kind == MediaFormat.Kind.Muxed },
            "Video only (best audio is added)" to formats.filter { it.kind == MediaFormat.Kind.VideoOnly },
            "Audio only" to formats.filter { it.kind == MediaFormat.Kind.AudioOnly },
        ).map { (title, list) ->
            title to list.sortedWith(compareByDescending<MediaFormat> { it.height ?: 0 }.thenByDescending { it.tbr ?: it.abr ?: 0.0 })
        }.filter { it.second.isNotEmpty() }
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(vertical = 8.dp)) {
            groups.forEach { (title, list) ->
                Text(
                    title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 4.dp),
                )
                list.forEach { f ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onSelect(f) }.padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = f.id == selected, onClick = { onSelect(f) })
                        Text(describe(f), style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(110.dp))
                        Facts(
                            f.ext,
                            f.vcodec?.takeIf { f.hasVideo }?.substringBefore('.'),
                            f.acodec?.takeIf { f.hasAudio }?.substringBefore('.'),
                            formatSize(f.sizeBytes),
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            f.id, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(end = 16.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun describe(f: MediaFormat): String = when {
    f.hasVideo && f.height != null -> buildString {
        append("${f.height}p")
        f.fps?.takeIf { it > 30 }?.let { append(it.toInt()) }
    }
    f.hasVideo -> "Video"
    f.abr != null -> "${f.abr.toInt()} kbps"
    else -> "Audio"
}
