package app.siphon.ui.library

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.siphon.SiphonApp
import app.siphon.data.DownloadItem
import app.siphon.data.Status
import app.siphon.ui.components.EmptyState
import app.siphon.ui.components.LogDialog
import app.siphon.ui.components.MetaLine
import app.siphon.ui.components.Thumbnail
import app.siphon.ui.components.copyText
import app.siphon.ui.components.formatDate
import app.siphon.ui.components.formatDuration
import app.siphon.ui.components.formatSize
import app.siphon.ui.components.openFile
import app.siphon.ui.components.shareFiles

private enum class Filter(val label: String) { All("All"), Video("Video"), Audio("Audio"), Failed("Failed") }

@Composable
fun LibraryScreen(wide: Boolean) {
    val context = LocalContext.current
    val repo = remember { SiphonApp.get(context).downloads }
    val all by repo.items.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf(Filter.All) }
    var query by remember { mutableStateOf("") }
    var logFor by remember { mutableStateOf<DownloadItem?>(null) }
    var deleteFor by remember { mutableStateOf<DownloadItem?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    val finished = all.filterNot { it.status.isActive }
    val shown = finished
        .filter {
            when (filter) {
                Filter.All -> true
                Filter.Video -> it.status == Status.Done && !it.isAudio
                Filter.Audio -> it.status == Status.Done && it.isAudio
                Filter.Failed -> it.status == Status.Failed || it.status == Status.Canceled
            }
        }
        .filter { query.isBlank() || it.title.contains(query, true) || it.uploader?.contains(query, true) == true }
        .sortedByDescending { it.finishedAt ?: it.createdAt }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.padding(start = if (wide) 32.dp else 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Library", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                placeholder = { Text("Search") }, leadingIcon = { Icon(Icons.Outlined.Search, null) },
                modifier = Modifier.widthIn(max = if (wide) 320.dp else 180.dp),
                shape = MaterialTheme.shapes.extraLarge,
            )
            IconButton(onClick = { confirmClear = true }, enabled = finished.isNotEmpty()) {
                Icon(Icons.Outlined.DeleteSweep, "Clear list")
            }
        }
        Row(
            Modifier.padding(horizontal = if (wide) 32.dp else 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Filter.entries.forEach { f -> FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) }) }
        }

        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    Icons.Outlined.VideoLibrary,
                    if (finished.isEmpty()) "No downloads yet" else "Nothing matches",
                    if (finished.isEmpty()) "Finished downloads are listed here and saved to Download/Siphon." else "Try another filter.",
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(if (wide) 280.dp else 220.dp),
                contentPadding = PaddingValues(if (wide) 32.dp else 16.dp, 16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(shown, key = { it.id }) { item ->
                    LibraryCard(
                        item,
                        onOpen = { item.outputs.firstOrNull()?.let { openFile(context, it) } },
                        onShare = { shareFiles(context, item.outputs) },
                        onCopyLink = { copyText(context, "link", item.url) },
                        onRetry = { repo.retry(item.id) },
                        onLog = { logFor = item },
                        onRemove = { repo.remove(item.id, deleteFiles = false) },
                        onDelete = { deleteFor = item },
                    )
                }
            }
        }
    }

    logFor?.let { LogDialog(it, { logFor = null }, context) }
    deleteFor?.let { item ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text("Delete file?") },
            text = { Text("“${item.title}” will be removed from Download/Siphon.") },
            confirmButton = { TextButton(onClick = { repo.remove(item.id, deleteFiles = true); deleteFor = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleteFor = null }) { Text("Cancel") } },
        )
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Clear list?") },
        text = { Text("Removes all finished entries from the library. Files stay in Download/Siphon.") },
        confirmButton = { TextButton(onClick = { repo.clearFinished(); confirmClear = false }) { Text("Clear") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
    )
}

@Composable
private fun LibraryCard(
    item: DownloadItem,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onCopyLink: () -> Unit,
    onRetry: () -> Unit,
    onLog: () -> Unit,
    onRemove: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val done = item.status == Status.Done
    Card(Modifier.fillMaxWidth().clickable { if (done) onOpen() else onLog() }) {
        Thumbnail(
            item.thumbnail, item.isAudio, Modifier.fillMaxWidth(), corner = 0.dp,
            badge = item.outputs.firstOrNull()?.name?.substringAfterLast('.')?.uppercase()
                ?.let { ext -> listOfNotNull(formatDuration(item.durationSec), ext).joinToString(" · ") },
        )
        Row(Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                if (done) {
                    MetaLine(listOf(item.uploader, formatSize(item.outputs.sumOf { it.size })))
                    MetaLine(listOf(formatDate(item.finishedAt ?: item.createdAt)))
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(end = 4.dp).width(16.dp),
                        )
                        Text(
                            if (item.status == Status.Canceled) "Canceled" else item.error ?: "Failed",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    @Composable
                    fun entry(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, action: () -> Unit) =
                        DropdownMenuItem(text = { Text(label) }, leadingIcon = { Icon(icon, null) }, onClick = { menu = false; action() })
                    if (done) {
                        entry("Open", Icons.Outlined.OpenInNew, onOpen)
                        entry("Share", Icons.Outlined.Share, onShare)
                    }
                    entry(if (done) "Download again" else "Retry", Icons.Outlined.Refresh, onRetry)
                    entry("Copy link", Icons.Outlined.ContentCopy, onCopyLink)
                    entry("View log", Icons.Outlined.Terminal, onLog)
                    HorizontalDivider()
                    entry("Remove from list", Icons.Outlined.RemoveCircleOutline, onRemove)
                    if (done) entry("Delete file", Icons.Outlined.Delete, onDelete)
                }
            }
        }
    }
}
