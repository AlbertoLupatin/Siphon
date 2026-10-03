package app.siphon.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.siphon.SiphonApp
import app.siphon.data.AppSettings
import app.siphon.data.Preset
import app.siphon.engine.Engine
import app.siphon.ui.components.SectionLabel
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(snackbar: SnackbarHostState) {
    val context = LocalContext.current
    val app = remember { SiphonApp.get(context) }
    val s by app.settings.settings.collectAsState(initial = null)
    val engine by Engine.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var updating by remember { mutableStateOf(false) }
    val settings = s ?: return
    fun set(f: (AppSettings) -> AppSettings) = scope.launch { app.settings.update(f) }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = 760.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 24.dp)
        ) {
            Text("Settings", style = MaterialTheme.typography.headlineMedium)

            SectionLabel("Defaults for new downloads")
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("Quality", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Preset.entries.filter { it != Preset.Custom }.forEach { p ->
                            FilterChip(
                                selected = settings.defaultPreset == p,
                                onClick = { set { it.copy(defaultPreset = p) } },
                                label = { Text(p.label) },
                            )
                        }
                    }
                }
                HorizontalDivider()
                Toggle("Prefer H.264 / AAC in MP4", "Plays in every app. Above 1080p YouTube only has VP9/AV1.", settings.compatibleMp4) {
                    set { s -> s.copy(compatibleMp4 = it) }
                }
                Toggle("Embed metadata", "Title, artist, date, description", settings.embedMetadata) { set { s -> s.copy(embedMetadata = it) } }
                Toggle("Embed thumbnail", "Cover art for audio, poster for video", settings.embedThumbnail) { set { s -> s.copy(embedThumbnail = it) } }
                Toggle("Embed chapters", null, settings.embedChapters) { set { s -> s.copy(embedChapters = it) } }
                Toggle("Embed subtitles", "Languages below, when available", settings.subtitles) { set { s -> s.copy(subtitles = it) } }
                if (settings.subtitles) {
                    SettingField("Subtitle languages", settings.subtitleLangs, "e.g. en.*,it.*  —  or  all") {
                        set { s -> s.copy(subtitleLangs = it) }
                    }
                }
                Toggle("SponsorBlock", "Cut sponsor, self-promo and interaction segments", settings.sponsorBlock) {
                    set { s -> s.copy(sponsorBlock = it) }
                }
            }

            SectionLabel("Queue")
            Card {
                Stepper("Simultaneous downloads", settings.maxConcurrent, 1..4) { set { s -> s.copy(maxConcurrent = it) } }
                Stepper("Fragments per download", settings.concurrentFragments, 1..16) { set { s -> s.copy(concurrentFragments = it) } }
                ListItem(
                    headlineContent = { Text("Save location") },
                    supportingContent = { Text("Internal storage › Download › Siphon") },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }

            SectionLabel("Advanced")
            Card {
                SettingField(
                    "Extra yt-dlp arguments", settings.extraArgs,
                    "Appended to every download, e.g.  --limit-rate 5M  --cookies-from-browser …",
                ) { set { s -> s.copy(extraArgs = it) } }
            }

            SectionLabel("Engine")
            Card {
                ListItem(
                    headlineContent = { Text("yt-dlp") },
                    supportingContent = {
                        Text(
                            when (val e = engine) {
                                Engine.State.Initializing -> "Preparing…"
                                is Engine.State.Updating -> "Updating from ${e.fromVersion}…"
                                is Engine.State.Ready -> "Version ${e.version}"
                                is Engine.State.Failed -> "Failed to start: ${e.message}"
                            }
                        )
                    },
                    trailingContent = {
                        if (updating || engine is Engine.State.Updating) CircularProgressIndicator(Modifier.width(24.dp))
                        else Button(
                            enabled = engine is Engine.State.Ready,
                            onClick = {
                                updating = true
                                scope.launch {
                                    val msg = runCatching { Engine.update(context, settings.nightly) }.fold(
                                        { if (it) "yt-dlp updated" else "yt-dlp is already up to date" },
                                        { "Update failed: ${it.message}" },
                                    )
                                    app.settings.update { it.copy(lastUpdateCheck = System.currentTimeMillis()) }
                                    updating = false
                                    snackbar.showSnackbar(msg)
                                }
                            },
                        ) { Text("Update now") }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Channel", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    SingleChoiceSegmentedButtonRow {
                        listOf(false to "Stable", true to "Nightly").forEachIndexed { i, (nightly, label) ->
                            SegmentedButton(
                                selected = settings.nightly == nightly,
                                onClick = { set { it.copy(nightly = nightly) } },
                                shape = SegmentedButtonDefaults.itemShape(i, 2),
                            ) { Text(label) }
                        }
                    }
                }
                Toggle("Auto-update daily", "Sites change often; a fresh yt-dlp fixes most failures", settings.autoUpdate) {
                    set { s -> s.copy(autoUpdate = it) }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun Toggle(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.padding(vertical = 0.dp),
    )
}

@Composable
private fun Stepper(title: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.FilledTonalIconButton(
                    onClick = { onChange((value - 1).coerceIn(range)) }, enabled = value > range.first,
                ) { Text("−") }
                Text("$value", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 12.dp))
                androidx.compose.material3.FilledTonalIconButton(
                    onClick = { onChange((value + 1).coerceIn(range)) }, enabled = value < range.last,
                ) { Text("+") }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

/** Keeps local text state so typing is not fought by DataStore round-trips; commits every change. */
@Composable
private fun SettingField(label: String, value: String, hint: String, onCommit: (String) -> Unit) {
    var text by remember { mutableStateOf(value) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; onCommit(it) },
        label = { Text(label) },
        supportingText = { Text(hint) },
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        singleLine = true,
    )
}
