package app.siphon.ui.queue

import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import app.siphon.ui.components.formatEta
import app.siphon.ui.components.openFile

@Composable
fun QueuePane(modifier: Modifier = Modifier, onOpenLibrary: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { SiphonApp.get(context).downloads }
    val items by repo.items.collectAsStateWithLifecycle()
    val active = items.filter { it.status.isActive }.sortedBy { it.createdAt }
    val recent = items.filterNot { it.status.isActive }.sortedByDescending { it.finishedAt ?: it.createdAt }.take(8)
    var logFor by remember { mutableStateOf<DownloadItem?>(null) }

    Column(modifier) {
        Row(Modifier.padding(start = 24.dp, end = 12.dp, top = 24.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Queue", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (active.size > 1) TextButton(onClick = repo::cancelAll) { Text("Cancel all") }
        }
        if (active.isEmpty() && recent.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(Icons.Outlined.CloudDownload, "Nothing downloading", "Downloads you start show up here.")
            }
            return@Column
        }
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (active.isEmpty()) item {
                Text(
                    "No active downloads", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp),
                )
            }
            items(active, key = { it.id }) { item ->
                QueueCard(item, onCancel = { repo.cancel(item.id) }, onRetry = {}, onOpen = {}, onLog = { logFor = item })
            }
            if (recent.isNotEmpty()) {
                item(key = "recent-header") {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                        Text(
                            "Recent", style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f).padding(start = 8.dp),
                        )
                        TextButton(onClick = onOpenLibrary) { Text("Library") }
                    }
                }
                items(recent, key = { it.id }) { item ->
                    QueueCard(
                        item,
                        onCancel = {},
                        onRetry = { repo.retry(item.id) },
                        onOpen = { item.outputs.firstOrNull()?.let { openFile(context, it) } },
                        onLog = { logFor = item },
                    )
                }
            }
        }
    }
    logFor?.let { LogDialog(it, { logFor = null }, context) }
}

@Composable
private fun QueueCard(
    item: DownloadItem,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onOpen: () -> Unit,
    onLog: () -> Unit,
) {
    val active = item.status.isActive
    Card(
        Modifier.fillMaxWidth().animateContentSize().clickable(enabled = item.status == Status.Done, onClick = onOpen),
        colors = CardDefaults.cardColors(
            containerColor = if (active) MaterialTheme.colorScheme.surfaceContainerHigh
            else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Thumbnail(item.thumbnail, item.isAudio, Modifier.width(112.dp), corner = 8.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                when (item.status) {
                    Status.Queued -> MetaLine(listOf("Waiting", item.options.preset.label))
                    Status.Running, Status.Processing -> {
                        MetaLine(
                            listOf(
                                item.stage,
                                "%.0f%%".format(item.progress).takeIf { item.status == Status.Running },
                                item.speed, formatEta(item.etaSec),
                            )
                        )
                        Spacer(Modifier.height(8.dp))
                        if (item.status == Status.Processing || item.progress <= 0f) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(progress = { item.progress / 100f }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                    Status.Done -> MetaLine(listOf("Saved", item.outputs.size.takeIf { it > 1 }?.let { "$it files" }, item.options.preset.label))
                    Status.Failed -> Text(
                        item.error ?: "Failed", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    Status.Canceled -> MetaLine(listOf("Canceled"))
                }
            }
            Spacer(Modifier.width(4.dp))
            when (item.status) {
                Status.Queued, Status.Running, Status.Processing ->
                    IconButton(onClick = onCancel) { Icon(Icons.Outlined.Close, "Cancel") }
                Status.Done -> Icon(
                    Icons.Outlined.CheckCircle, "Done", tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(12.dp),
                )
                Status.Failed, Status.Canceled -> Row {
                    if (item.status == Status.Failed) IconButton(onClick = onLog) { Icon(Icons.Outlined.Terminal, "Log") }
                    IconButton(onClick = onRetry) { Icon(Icons.Outlined.Refresh, "Retry") }
                }
            }
        }
    }
}
