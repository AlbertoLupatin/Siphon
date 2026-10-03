package app.siphon.ui.download

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.siphon.data.DownloadOptions
import app.siphon.data.Preset
import app.siphon.engine.Engine
import app.siphon.engine.MediaFormat
import app.siphon.engine.MediaInfo
import app.siphon.ui.components.MetaLine
import app.siphon.ui.components.SectionLabel
import app.siphon.ui.components.Thumbnail
import app.siphon.ui.components.formatDuration
import app.siphon.ui.components.formatSize
import app.siphon.ui.queue.QueuePane

@Composable
fun DownloadScreen(vm: DownloadViewModel, wide: Boolean, onOpenLibrary: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    if (wide) {
        Row(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .weight(1.15f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 32.dp, vertical = 24.dp)
            ) {
                NewDownloadPane(state, vm)
            }
            VerticalDivider()
            QueuePane(Modifier.weight(0.85f).fillMaxHeight(), onOpenLibrary = onOpenLibrary)
        }
    } else {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            NewDownloadPane(state, vm)
            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            QueuePane(Modifier.fillMaxWidth().height(520.dp), onOpenLibrary = onOpenLibrary)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NewDownloadPane(state: DownloadUiState, vm: DownloadViewModel) {
    val clipboard = LocalClipboardManager.current
    val engine by Engine.state.collectAsStateWithLifecycle()

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("New download", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
        EngineChip(engine)
    }
    Spacer(Modifier.height(16.dp))

    OutlinedTextField(
        value = state.url,
        onValueChange = vm::setUrl,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("Paste a link from YouTube, Vimeo, SoundCloud, X, Instagram, Twitch…") },
        leadingIcon = { Icon(Icons.Outlined.Link, null) },
        trailingIcon = {
            if (state.url.isNotEmpty()) {
                IconButton(onClick = { vm.setUrl("") }) { Icon(Icons.Outlined.Close, "Clear") }
            } else {
                IconButton(onClick = { clipboard.getText()?.text?.let(vm::acceptText) }) {
                    Icon(Icons.Outlined.ContentPaste, "Paste")
                }
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { vm.fetch() }),
        shape = MaterialTheme.shapes.large,
    )
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.looksLikePlaylist) {
            FilterChip(
                selected = state.playlist,
                onClick = { vm.setPlaylist(!state.playlist) },
                label = { Text("Whole playlist") },
                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.PlaylistPlay, null, Modifier.size(18.dp)) },
            )
        }
        Spacer(Modifier.weight(1f))
        FilledTonalButton(onClick = vm::fetch, enabled = state.url.isNotBlank() && state.fetch !is FetchState.Loading) {
            Icon(Icons.Outlined.Search, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Get info")
        }
        if (state.fetch !is FetchState.Loaded) {
            Button(onClick = vm::download, enabled = state.url.isNotBlank()) {
                Icon(Icons.Outlined.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Quick download")
            }
        }
    }

    Spacer(Modifier.height(20.dp))
    Box(Modifier.animateContentSize()) {
        when (val f = state.fetch) {
            FetchState.Idle -> if (state.url.isBlank()) HintCard()
            FetchState.Loading -> Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Text("Reading link…", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            is FetchState.Error -> Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Row(Modifier.padding(20.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(f.message, color = MaterialTheme.colorScheme.onErrorContainer)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "You can still try Quick download, or update yt-dlp in Settings.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }
            is FetchState.Loaded -> LoadedContent(f.info, state, vm)
        }
    }
}

@Composable
private fun EngineChip(engine: Engine.State) {
    val (label, busy) = when (engine) {
        Engine.State.Initializing -> "Preparing engine…" to true
        is Engine.State.Updating -> "Updating yt-dlp…" to true
        is Engine.State.Ready -> "yt-dlp ${engine.version}" to false
        is Engine.State.Failed -> "Engine error" to false
    }
    Column(horizontalAlignment = Alignment.End) {
        Text(
            label, style = MaterialTheme.typography.labelMedium,
            color = if (engine is Engine.State.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (busy) LinearProgressIndicator(Modifier.width(120.dp).padding(top = 4.dp))
    }
}

@Composable
private fun HintCard() {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(20.dp)) {
            Text("Tip", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "Share any video page to Siphon from your browser or the YouTube app — it lands here ready to download. " +
                    "Files are saved to Download/Siphon.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LoadedContent(info: MediaInfo, state: DownloadUiState, vm: DownloadViewModel) {
    val o = state.options
    Column {
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp)) {
                Thumbnail(
                    info.thumbnail, isAudio = false, modifier = Modifier.width(240.dp),
                    badge = if (info.isPlaylist) "${info.entries.size} items" else formatDuration(info.durationSec),
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(info.title, style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(6.dp))
                    MetaLine(listOf(info.uploader, info.extractor))
                    if (info.isPlaylist) {
                        Spacer(Modifier.height(8.dp))
                        info.entries.take(4).forEachIndexed { i, e ->
                            Text(
                                "${i + 1}. ${e.title}", style = MaterialTheme.typography.bodySmall, maxLines = 1,
                                overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (info.entries.size > 4) Text("…and ${info.entries.size - 4} more", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        SectionLabel("Quality")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Preset.entries.filter { it != Preset.Custom }.forEach { p ->
                FilterChip(
                    selected = o.preset == p,
                    onClick = { vm.selectPreset(p) },
                    label = { Text(p.label) },
                    colors = if (p.isAudio) FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    ) else FilterChipDefaults.filterChipColors(),
                )
            }
        }

        if (info.formats.isNotEmpty()) {
            TextButton(onClick = vm::toggleFormats, modifier = Modifier.padding(top = 4.dp)) {
                Icon(if (state.showFormats) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
                Spacer(Modifier.width(6.dp))
                Text(
                    if (o.preset == Preset.Custom) "Exact format: ${o.formatId}"
                    else "Pick an exact format (${info.formats.size})"
                )
            }
            AnimatedVisibility(state.showFormats) {
                FormatList(info.formats, o.formatId.takeIf { o.preset == Preset.Custom }, vm::selectFormat)
            }
        }

        SectionLabel("Options")
        OptionChips(o, isAudio = o.preset.isAudio, vm::updateOptions)

        Spacer(Modifier.height(24.dp))
        Button(onClick = vm::download, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Icon(Icons.Outlined.Download, null)
            Spacer(Modifier.width(10.dp))
            Text(
                if (info.isPlaylist) "Download ${info.entries.size} items" else "Download",
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OptionChips(o: DownloadOptions, isAudio: Boolean, update: ((DownloadOptions) -> DownloadOptions) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        @Composable
        fun chip(label: String, on: Boolean, enabled: Boolean = true, f: (DownloadOptions) -> DownloadOptions) =
            FilterChip(selected = on && enabled, enabled = enabled, onClick = { update(f) }, label = { Text(label) })

        chip("H.264 MP4 (plays everywhere)", o.compatibleMp4, !isAudio) { it.copy(compatibleMp4 = !it.compatibleMp4) }
        chip("Subtitles", o.subtitles, !isAudio) { it.copy(subtitles = !it.subtitles) }
        chip("Chapters", o.embedChapters, !isAudio) { it.copy(embedChapters = !it.embedChapters) }
        chip("Thumbnail", o.embedThumbnail) { it.copy(embedThumbnail = !it.embedThumbnail) }
        chip("Metadata", o.embedMetadata) { it.copy(embedMetadata = !it.embedMetadata) }
        chip("SponsorBlock cut", o.sponsorBlock) { it.copy(sponsorBlock = !it.sponsorBlock) }
    }
}

@Composable
private fun FormatList(formats: List<MediaFormat>, selected: String?, onSelect: (MediaFormat) -> Unit) {
    val groups = remember(formats) {
        listOf(
            "Video + audio" to formats.filter { it.kind == MediaFormat.Kind.Muxed },
            "Video only (best audio is added)" to formats.filter { it.kind == MediaFormat.Kind.VideoOnly },
            "Audio only" to formats.filter { it.kind == MediaFormat.Kind.AudioOnly },
        ).map { (title, list) ->
            title to list.sortedWith(compareByDescending<MediaFormat> { it.height ?: 0 }.thenByDescending { it.tbr ?: it.abr ?: 0.0 })
        }.filter { it.second.isNotEmpty() }
    }
    Card(
        Modifier.fillMaxWidth().widthIn(max = 900.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(vertical = 8.dp)) {
            groups.forEach { (title, list) ->
                Text(
                    title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                )
                list.forEach { f ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onSelect(f) }.padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = f.id == selected, onClick = { onSelect(f) })
                        Text(
                            describe(f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                            modifier = Modifier.width(150.dp),
                        )
                        MetaLine(
                            listOf(
                                f.ext,
                                f.vcodec?.takeIf { f.hasVideo }?.substringBefore('.'),
                                f.acodec?.takeIf { f.hasAudio }?.substringBefore('.'),
                                formatSize(f.sizeBytes),
                                f.note,
                            ),
                            Modifier.weight(1f),
                        )
                        Text(
                            "#${f.id}", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(end = 12.dp),
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
