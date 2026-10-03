package app.siphon.ui.components

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.siphon.R
import app.siphon.data.DownloadItem
import app.siphon.data.OutputFile
import app.siphon.ui.theme.LocalSiphonExtras
import coil3.compose.SubcomposeAsyncImage
import java.text.DateFormat
import java.util.Date
import kotlin.math.PI
import kotlin.math.sin

private val Greyscale = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

/**
 * 16:9 media thumbnail. When [fill] is non-null the "liquid" rises from the bottom to that level (0..1) —
 * the app's signature progress treatment.
 */
@Composable
fun Thumbnail(
    url: String?,
    isAudio: Boolean,
    modifier: Modifier = Modifier,
    corner: Dp = 12.dp,
    fill: Float? = null,
    churning: Boolean = false,
    muted: Boolean = false,
    badge: (@Composable () -> Unit)? = null,
) {
    Box(
        modifier
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(corner))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
    ) {
        val placeholder = @Composable {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    if (isAudio) Icons.Outlined.Audiotrack else Icons.Outlined.Movie, null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), modifier = Modifier.size(28.dp),
                )
            }
        }
        if (url != null) {
            SubcomposeAsyncImage(
                model = url, contentDescription = null, contentScale = ContentScale.Crop,
                colorFilter = if (muted) Greyscale else null,
                modifier = Modifier.fillMaxSize(),
                loading = { placeholder() }, error = { placeholder() },
            )
        } else placeholder()
        if (fill != null) LiquidFill(fill, churning, Modifier.fillMaxSize())
        if (badge != null) Box(Modifier.align(Alignment.BottomEnd).padding(6.dp)) { badge() }
    }
}

/** Translucent liquid with a gently moving surface. Static when system animations are off. */
@Composable
fun LiquidFill(level: Float, churning: Boolean, modifier: Modifier = Modifier) {
    val extras = LocalSiphonExtras.current
    val animatedLevel by animateFloatAsState(level.coerceIn(0f, 1f), tween(450), label = "level")
    val phase = if (extras.motion) {
        val t = rememberInfiniteTransition(label = "wave")
        val p by t.animateFloat(
            0f, 1f,
            infiniteRepeatable(tween(if (churning) 900 else 2400, easing = LinearEasing), RepeatMode.Restart),
            label = "phase",
        )
        p
    } else 0f
    val liquid = extras.liquid
    Canvas(modifier) {
        val surfaceY = size.height * (1f - animatedLevel)
        val amp = if (animatedLevel in 0.01f..0.99f || churning) size.height * (if (churning) 0.04f else 0.022f) else 0f
        fun waveY(i: Int, steps: Int) = surfaceY + amp * sin(2 * PI * (i.toFloat() / steps * 1.5f + phase)).toFloat()
        val steps = 32
        // Empty part of the vessel: dimmed, so the level reads on any image.
        val empty = Path().apply {
            moveTo(0f, 0f); lineTo(size.width, 0f)
            for (i in steps downTo 0) lineTo(size.width * i / steps, waveY(i, steps))
            close()
        }
        drawPath(empty, Color.Black.copy(alpha = 0.55f))
        // Filled part: a light wash of glass colour over the untouched image.
        val full = Path().apply {
            moveTo(0f, size.height)
            for (i in 0..steps) lineTo(size.width * i / steps, waveY(i, steps))
            lineTo(size.width, size.height); close()
        }
        drawPath(full, liquid.copy(alpha = 0.22f))
        if (animatedLevel > 0.005f && animatedLevel < 0.995f || churning) {
            val crest = Path().apply {
                for (i in 0..steps) {
                    val x = size.width * i / steps
                    if (i == 0) moveTo(x, waveY(i, steps)) else lineTo(x, waveY(i, steps))
                }
            }
            drawPath(crest, liquid, style = Stroke(width = 2.5.dp.toPx()))
        }
    }
}

@Composable
fun Logo(size: Dp, modifier: Modifier = Modifier) =
    Image(painterResource(R.drawable.ic_logo), contentDescription = null, modifier = modifier.size(size))

/** Small rounded label, e.g. a duration or container format stamped on a thumbnail. */
@Composable
fun Stamp(text: String, modifier: Modifier = Modifier, onImage: Boolean = true) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = if (onImage) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .background(
                if (onImage) Color.Black.copy(alpha = 0.66f) else MaterialTheme.colorScheme.surfaceContainerHighest,
                RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** Quiet heading for a group of controls. Sentence case, no decoration. */
@Composable
fun GroupLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(top = 28.dp, bottom = 10.dp),
    )
}

/** Secondary facts laid out with spacing instead of separator glyphs. */
@Composable
fun Facts(vararg facts: String?, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        facts.filterNot { it.isNullOrBlank() }.forEach {
            Text(it!!, style = MaterialTheme.typography.bodySmall, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    action: (@Composable RowScope.() -> Unit)? = null,
) {
    Column(modifier.padding(32.dp).width(320.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (icon != null) {
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Icon(icon, null, Modifier.padding(18.dp).size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else Logo(56.dp)
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (action != null) Row(Modifier.padding(top = 16.dp), content = action)
    }
}

@Composable
fun LogDialog(item: DownloadItem, onDismiss: () -> Unit, context: Context) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("yt-dlp output", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            SelectionContainer {
                Text(
                    item.log.ifBlank { item.error ?: "Nothing was recorded for this download." },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = { TextButton(onClick = { copyText(context, "yt-dlp output", item.log) }) { Text("Copy") } },
    )
}

// ---- formatting ----

fun formatDuration(sec: Int?): String? {
    if (sec == null || sec <= 0) return null
    val h = sec / 3600; val m = (sec % 3600) / 60; val s = sec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun formatEta(sec: Long): String? = if (sec <= 0) null else "${formatDuration(sec.toInt())} left"

fun formatSize(bytes: Long?): String? {
    if (bytes == null || bytes <= 0) return null
    val units = listOf("B", "KB", "MB", "GB")
    var v = bytes.toDouble(); var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return if (i >= 3) "%.1f %s".format(v, units[i]) else "%.0f %s".format(v, units[i])
}

fun formatDate(ms: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ms))

// ---- intents ----

fun openFile(context: Context, out: OutputFile) {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(Uri.parse(out.uri), out.mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No installed app opens ${out.mime} files", Toast.LENGTH_SHORT).show()
    }
}

fun shareFiles(context: Context, outs: List<OutputFile>) {
    if (outs.isEmpty()) return
    val intent = if (outs.size == 1) {
        Intent(Intent.ACTION_SEND).setType(outs[0].mime).putExtra(Intent.EXTRA_STREAM, Uri.parse(outs[0].uri))
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(outs.map { Uri.parse(it.uri) }))
    }.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

fun copyText(context: Context, label: String, text: String) {
    val cm = context.getSystemService(android.content.ClipboardManager::class.java)
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}
