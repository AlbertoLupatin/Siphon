package app.siphon.ui.queue

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.siphon.data.DownloadItem
import app.siphon.data.Status
import app.siphon.ui.components.EmptyState
import app.siphon.ui.components.Facts
import app.siphon.ui.components.Thumbnail
import app.siphon.ui.components.formatEta

class QueueCallbacks(
    val cancel: (DownloadItem) -> Unit,
    val retry: (DownloadItem) -> Unit,
    val open: (DownloadItem) -> Unit,
    val showLog: (DownloadItem) -> Unit,
    val cancelAll: () -> Unit,
    val openLibrary: () -> Unit,
)

@Composable
fun QueuePane(items: List<DownloadItem>, cb: QueueCallbacks, modifier: Modifier = Modifier) {
    val running = items.filter { it.status == Status.Running || it.status == Status.Processing }.sortedBy { it.createdAt }
    val waiting = items.filter { it.status == Status.Queued }.sortedBy { it.createdAt }
    val recent = items.filterNot { it.status.isActive }.sortedByDescending { it.finishedAt ?: it.createdAt }.take(8)

    Column(modifier) {
        Row(Modifier.padding(start = 24.dp, end = 12.dp, top = 22.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Queue", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            if (running.size + waiting.size > 1) TextButton(onClick = cb.cancelAll) { Text("Cancel all") }
        }
        if (running.isEmpty() && waiting.isEmpty() && recent.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState("Nothing in the queue", "Downloads you start appear here while they brew.")
            }
            return@Column
        }
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (running.isEmpty() && waiting.isEmpty()) item(key = "idle") {
                Text(
                    "Nothing downloading right now.", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
            // One hero card for the oldest running download; the rest stay compact so the tray never fills up.
            items(running.take(1), key = { it.id }) { RunningCard(it, cb) }
            items(running.drop(1), key = { it.id }) { CompactRow(it, cb) }
            items(waiting, key = { it.id }) { CompactRow(it, cb) }
            if (recent.isNotEmpty()) {
                item(key = "recent") {
                    Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Recent", style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f).padding(start = 8.dp),
                        )
                        TextButton(onClick = cb.openLibrary) { Text("Open library") }
                    }
                }
                items(recent, key = { it.id }) { CompactRow(it, cb) }
            }
        }
    }
}

/**
 * Large card for a download in progress: the thumbnail fills with liquid as bytes arrive.
 * Stacked in the narrow tray, side by side when the queue spans a wide column.
 */
@Composable
private fun RunningCard(item: DownloadItem, cb: QueueCallbacks) {
    val processing = item.status == Status.Processing
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(24.dp), modifier = Modifier.animateContentSize()) {
        BoxWithConstraints {
            val sideBySide = maxWidth > 480.dp
            val thumb = @Composable { m: Modifier ->
                Box(m) {
                    Thumbnail(
                        item.thumbnail, item.isAudio, Modifier.fillMaxWidth(), corner = 16.dp,
                        fill = if (processing) 1f else item.progress / 100f,
                        churning = processing,
                    )
                    IconButton(
                        onClick = { cb.cancel(item) },
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(36.dp)
                            .background(Color.Black.copy(alpha = 0.5f), CircleShape),
                    ) { Icon(Icons.Outlined.Close, "Cancel download", tint = Color.White, modifier = Modifier.size(18.dp)) }
                }
            }
            val info = @Composable { m: Modifier ->
                Row(m, verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(4.dp))
                        Text(item.stage ?: "Starting", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                        Facts(item.speed, formatEta(item.etaSec))
                    }
                    Spacer(Modifier.width(12.dp))
                    if (!processing) {
                        Text(
                            "${item.progress.toInt()}%",
                            style = MaterialTheme.typography.headlineSmall.copy(fontFamily = MaterialTheme.typography.titleLarge.fontFamily),
                        )
                    }
                }
            }
            if (sideBySide) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    thumb(Modifier.width(260.dp))
                    info(Modifier.weight(1f).padding(start = 18.dp, end = 8.dp))
                }
            } else {
                Column(Modifier.padding(10.dp)) {
                    thumb(Modifier.fillMaxWidth())
                    info(Modifier.padding(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 6.dp))
                }
            }
        }
    }
}

/** One-line row for waiting and finished downloads. */
@Composable
private fun CompactRow(item: DownloadItem, cb: QueueCallbacks) {
    val failed = item.status == Status.Failed
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = item.status == Status.Done) { cb.open(item) }
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumbnail(
            item.thumbnail, item.isAudio, Modifier.width(96.dp), corner = 10.dp,
            muted = failed || item.status == Status.Canceled || item.status == Status.Queued,
            fill = when (item.status) {
                Status.Running -> item.progress / 100f
                Status.Processing -> 1f
                else -> null
            },
            churning = item.status == Status.Processing,
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            when (item.status) {
                Status.Queued -> Facts("Waiting for a free slot")
                Status.Done -> Facts(if (item.outputs.size > 1) "Saved ${item.outputs.size} files" else "Saved", color = MaterialTheme.colorScheme.tertiary)
                Status.Failed -> Text(
                    item.error ?: "Failed", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Status.Canceled -> Facts("Canceled")
                Status.Running -> Facts(item.stage, "${item.progress.toInt()}%", item.speed, color = MaterialTheme.colorScheme.secondary)
                Status.Processing -> Facts(item.stage, color = MaterialTheme.colorScheme.secondary)
            }
        }
        when (item.status) {
            Status.Running, Status.Processing -> IconButton(onClick = { cb.cancel(item) }) { Icon(Icons.Outlined.Close, "Cancel download") }
            Status.Queued -> IconButton(onClick = { cb.cancel(item) }) { Icon(Icons.Outlined.Close, "Remove from queue") }
            Status.Done -> Icon(Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.padding(12.dp))
            Status.Failed, Status.Canceled -> Row {
                if (failed) IconButton(onClick = { cb.showLog(item) }) { Icon(Icons.Outlined.Terminal, "Show yt-dlp output") }
                IconButton(onClick = { cb.retry(item) }) { Icon(Icons.Outlined.Refresh, "Try again") }
            }
        }
    }
}
