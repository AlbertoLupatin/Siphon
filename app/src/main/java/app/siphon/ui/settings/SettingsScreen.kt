package app.siphon.ui.settings

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.siphon.data.AppSettings
import app.siphon.data.Preset
import app.siphon.engine.Engine
import app.siphon.ui.components.GroupLabel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    engine: Engine.State,
    updating: Boolean,
    onChange: ((AppSettings) -> AppSettings) -> Unit,
    onUpdateNow: () -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = 720.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 28.dp)
        ) {
            Text("Settings", style = MaterialTheme.typography.headlineMedium)

            GroupLabel("New downloads start with")
            Section {
                Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 14.dp)) {
                    Text("Quality", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(10.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Preset.entries.filter { it != Preset.Custom }.forEach { p ->
                            FilterChip(
                                selected = settings.defaultPreset == p,
                                onClick = { onChange { it.copy(defaultPreset = p) } },
                                label = { Text(p.label) },
                                shape = RoundedCornerShape(12.dp),
                            )
                        }
                    }
                }
                Divider()
                Toggle("Plays everywhere", "Prefer H.264 video and AAC audio in MP4. Above 1080p YouTube only offers VP9 or AV1.", settings.compatibleMp4) { v ->
                    onChange { it.copy(compatibleMp4 = v) }
                }
                Toggle("Title and artist tags", "Write title, channel, date and description into the file", settings.embedMetadata) { v ->
                    onChange { it.copy(embedMetadata = v) }
                }
                Toggle("Cover image", "Embed the thumbnail as album art or poster", settings.embedThumbnail) { v ->
                    onChange { it.copy(embedThumbnail = v) }
                }
                Toggle("Chapters", null, settings.embedChapters) { v -> onChange { it.copy(embedChapters = v) } }
                Toggle("Subtitles", "Embed the languages below when the site has them", settings.subtitles) { v ->
                    onChange { it.copy(subtitles = v) }
                }
                if (settings.subtitles) {
                    SettingField("Subtitle languages", settings.subtitleLangs, "For example en.*,it.* or all") { v ->
                        onChange { it.copy(subtitleLangs = v) }
                    }
                }
                Toggle("Skip sponsor segments", "Cut sponsor reads, self-promotion and subscribe reminders using SponsorBlock", settings.sponsorBlock) { v ->
                    onChange { it.copy(sponsorBlock = v) }
                }
            }

            GroupLabel("Queue")
            Section {
                Stepper("Downloads at the same time", settings.maxConcurrent, 1..4) { v -> onChange { it.copy(maxConcurrent = v) } }
                Stepper("Parallel connections per download", settings.concurrentFragments, 1..16) { v ->
                    onChange { it.copy(concurrentFragments = v) }
                }
                ListItem(
                    headlineContent = { Text("Files are saved to") },
                    supportingContent = { Text("Internal storage › Download › Siphon") },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }

            GroupLabel("Appearance")
            Section {
                Toggle(
                    "Use wallpaper colors",
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "Replace Siphon's brass and glass palette with your system's Material You colors"
                    else "Needs Android 12 or newer",
                    settings.materialYou,
                    enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
                ) { v -> onChange { it.copy(materialYou = v) } }
            }

            GroupLabel("yt-dlp")
            Section {
                ListItem(
                    headlineContent = {
                        Text(
                            when (engine) {
                                Engine.State.Initializing -> "Starting"
                                is Engine.State.Updating -> "Updating from ${engine.fromVersion}"
                                is Engine.State.Ready -> "Version ${engine.version}"
                                is Engine.State.Failed -> "Didn't start"
                            }
                        )
                    },
                    supportingContent = {
                        Text(
                            if (engine is Engine.State.Failed) engine.message
                            else "Sites change often. Updating fixes most failed downloads."
                        )
                    },
                    trailingContent = {
                        if (updating || engine is Engine.State.Updating) CircularProgressIndicator(Modifier.size(24.dp))
                        else Button(enabled = engine is Engine.State.Ready, onClick = onUpdateNow) { Text("Update now") }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
                Divider()
                Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Release channel", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Nightly gets site fixes days earlier",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    SingleChoiceSegmentedButtonRow {
                        listOf(false to "Stable", true to "Nightly").forEachIndexed { i, (nightly, label) ->
                            SegmentedButton(
                                selected = settings.nightly == nightly,
                                onClick = { onChange { it.copy(nightly = nightly) } },
                                shape = SegmentedButtonDefaults.itemShape(i, 2),
                            ) { Text(label) }
                        }
                    }
                }
                Toggle("Update automatically", "Check once a day when Siphon opens", settings.autoUpdate) { v ->
                    onChange { it.copy(autoUpdate = v) }
                }
                Divider()
                SettingField(
                    "Extra yt-dlp options", settings.extraArgs,
                    "Added to every download, for example --limit-rate 5M",
                ) { v -> onChange { it.copy(extraArgs = v) } }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun Section(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(vertical = 6.dp), content = content)
    }
}

@Composable
private fun Divider() = HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)

@Composable
private fun Toggle(title: String, subtitle: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange, enabled = enabled) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
private fun Stepper(title: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(onClick = { onChange((value - 1).coerceIn(range)) }, enabled = value > range.first) {
                    Icon(Icons.Outlined.Remove, "Fewer")
                }
                Text("$value", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 14.dp))
                FilledTonalIconButton(onClick = { onChange((value + 1).coerceIn(range)) }, enabled = value < range.last) {
                    Icon(Icons.Outlined.Add, "More")
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

/** Keeps local text state so typing isn't fought by DataStore round-trips; commits every change. */
@Composable
private fun SettingField(label: String, value: String, hint: String, onCommit: (String) -> Unit) {
    var text by remember { mutableStateOf(value) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; onCommit(it) },
        label = { Text(label) },
        supportingText = { Text(hint) },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
    )
}
