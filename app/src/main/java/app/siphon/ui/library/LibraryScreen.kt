package app.siphon.ui.library

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.siphon.data.DownloadItem
import app.siphon.data.Status
import app.siphon.ui.components.EmptyState
import app.siphon.ui.components.Facts
import app.siphon.ui.components.Stamp
import app.siphon.ui.components.Thumbnail
import app.siphon.ui.components.formatDate
import app.siphon.ui.components.formatDuration
import app.siphon.ui.components.formatSize

class LibraryCallbacks(
    val open: (DownloadItem) -> Unit,
    val openExternal: (DownloadItem) -> Unit,
    val share: (DownloadItem) -> Unit,
    val copyLink: (DownloadItem) -> Unit,
    val retry: (DownloadItem) -> Unit,
    val showLog: (DownloadItem) -> Unit,
    val remove: (DownloadItem) -> Unit,
    val deleteFile: (DownloadItem) -> Unit,
    val clearFinished: () -> Unit,
)

private enum class Shelf(val label: String) { All("All"), Video("Video"), Audio("Audio"), Unfinished("Didn't finish") }

private fun Shelf.matches(it: DownloadItem) = when (this) {
    Shelf.All -> true
    Shelf.Video -> it.status == Status.Done && !it.isAudio
    Shelf.Audio -> it.status == Status.Done && it.isAudio
    Shelf.Unfinished -> it.status == Status.Failed || it.status == Status.Canceled
}

@Composable
fun LibraryScreen(items: List<DownloadItem>, wide: Boolean, cb: LibraryCallbacks) {
    var shelf by rememberSaveable { mutableStateOf(Shelf.All) }
    var query by rememberSaveable { mutableStateOf("") }
    var deleteFor by remember { mutableStateOf<DownloadItem?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    val finished = items.filterNot { it.status.isActive }
    val saved = finished.filter { it.status == Status.Done }
    val shown = finished
        .filter(shelf::matches)
        .filter { query.isBlank() || it.title.contains(query, true) || it.uploader?.contains(query, true) == true }
        .sortedByDescending { it.finishedAt ?: it.createdAt }
    val gutter = if (wide) 40.dp else 20.dp

    LazyVerticalGrid(
        columns = GridCells.Adaptive(if (wide) 264.dp else 200.dp),
        contentPadding = PaddingValues(start = gutter, end = gutter, top = 28.dp, bottom = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Library", style = MaterialTheme.typography.headlineMedium)
                        Text(
                            if (saved.isEmpty()) "Nothing saved yet"
                            else "${saved.size} saved, ${formatSize(saved.sumOf { d -> d.outputs.sumOf { it.size } })} in Download › Siphon",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (wide) SearchField(query, { query = it }, Modifier.width(320.dp))
                    IconButton(onClick = { confirmClear = true }, enabled = finished.isNotEmpty()) {
                        Icon(Icons.Outlined.DeleteSweep, "Clear the list")
                    }
                }
                if (!wide) SearchField(query, { query = it }, Modifier.fillMaxWidth().padding(top = 16.dp))
                Row(
                    Modifier.padding(top = 18.dp, bottom = 4.dp).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Shelf.entries.forEach { s ->
                        val n = finished.count(s::matches)
                        FilterChip(
                            selected = shelf == s, onClick = { shelf = s },
                            label = { Text(if (s == Shelf.All) s.label else "${s.label}  $n", maxLines = 1) },
                            shape = RoundedCornerShape(12.dp),
                        )
                    }
                }
            }
        }

        if (shown.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) {
                    if (finished.isEmpty()) EmptyState("Your library is empty", "Finished downloads collect here, newest first.")
                    else EmptyState("No matches", "Try another filter or search term.", icon = Icons.Outlined.Search)
                }
            }
        }
        items(shown, key = { it.id }) { item ->
            LibraryCard(item, cb, onDelete = { deleteFor = item })
        }
    }

    deleteFor?.let { item ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text("Delete this file?") },
            text = { Text("“${item.title}” will be removed from Download › Siphon. This can't be undone.") },
            confirmButton = { TextButton(onClick = { cb.deleteFile(item); deleteFor = null }) { Text("Delete file") } },
            dismissButton = { TextButton(onClick = { deleteFor = null }) { Text("Keep") } },
        )
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Clear the list?") },
        text = { Text("Everything is removed from this list. Saved files stay in Download › Siphon.") },
        confirmButton = { TextButton(onClick = { cb.clearFinished(); confirmClear = false }) { Text("Clear list") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Keep") } },
    )
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit, modifier: Modifier) {
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = modifier) {
        TextField(
            value = query, onValueChange = onChange, singleLine = true,
            placeholder = { Text("Search titles and channels") },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            trailingIcon = if (query.isNotEmpty()) ({ IconButton(onClick = { onChange("") }) { Icon(Icons.Outlined.Close, "Clear search") } }) else null,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
            ),
        )
    }
}

@Composable
private fun LibraryCard(item: DownloadItem, cb: LibraryCallbacks, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val done = item.status == Status.Done
    Surface(
        onClick = { if (done) cb.open(item) else cb.showLog(item) },
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(Modifier.padding(8.dp)) {
            Thumbnail(
                item.thumbnail, item.isAudio, Modifier.fillMaxWidth(), corner = 16.dp, muted = !done,
                badge = {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        (item.outputs.firstOrNull { it.isPlayable } ?: item.outputs.firstOrNull())
                            ?.name?.substringAfterLast('.')?.uppercase()?.let { Stamp(it) }
                        formatDuration(item.durationSec)?.let { Stamp(it) }
                    }
                },
            )
            Row(Modifier.padding(start = 8.dp, top = 12.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(item.title, style = MaterialTheme.typography.titleSmall, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(6.dp))
                    if (done) {
                        Facts(item.uploader, formatSize(item.outputs.sumOf { it.size }))
                        val resumeAt = item.resume?.positionMs?.takeIf { it > 0 }
                        if (resumeAt != null) Facts("Resume at ${formatDuration((resumeAt / 1000).toInt())}", color = MaterialTheme.colorScheme.secondary)
                        else Facts(formatDate(item.finishedAt ?: item.createdAt))
                    } else {
                        Text(
                            if (item.status == Status.Canceled) "Canceled" else item.error ?: "Failed",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                            maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "More actions") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        @Composable
                        fun entry(label: String, icon: ImageVector, action: () -> Unit) =
                            DropdownMenuItem(text = { Text(label) }, leadingIcon = { Icon(icon, null) }, onClick = { menu = false; action() })
                        if (done) {
                            entry("Play", Icons.Outlined.PlayArrow) { cb.open(item) }
                            entry("Open in another app", Icons.AutoMirrored.Outlined.OpenInNew) { cb.openExternal(item) }
                            entry("Share", Icons.Outlined.Share) { cb.share(item) }
                        }
                        entry(if (done) "Download again" else "Try again", Icons.Outlined.Refresh) { cb.retry(item) }
                        entry("Copy link", Icons.Outlined.ContentCopy) { cb.copyLink(item) }
                        entry("Show yt-dlp output", Icons.Outlined.Terminal) { cb.showLog(item) }
                        HorizontalDivider()
                        entry("Remove from list", Icons.Outlined.RemoveCircleOutline) { cb.remove(item) }
                        if (done) entry("Delete file", Icons.Outlined.Delete, onDelete)
                    }
                }
            }
        }
    }
}
