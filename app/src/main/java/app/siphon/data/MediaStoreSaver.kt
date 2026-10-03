package app.siphon.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.File

/** Publishes finished files to the shared Download/Siphon folder (scoped storage, no permission needed). */
object MediaStoreSaver {
    const val FOLDER = "Siphon"

    fun publish(context: Context, file: File): OutputFile {
        val mime = mimeOf(file)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: error("MediaStore insert failed for ${file.name}")
        try {
            resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out, 1 shl 20) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }
        return OutputFile(uri.toString(), file.name, mime, file.length())
    }

    fun delete(context: Context, out: OutputFile): Boolean =
        runCatching { context.contentResolver.delete(Uri.parse(out.uri), null, null) > 0 }.getOrDefault(false)

    fun exists(context: Context, out: OutputFile): Boolean = runCatching {
        context.contentResolver.query(Uri.parse(out.uri), arrayOf(MediaStore.MediaColumns._ID), null, null, null)
            ?.use { it.count > 0 } ?: false
    }.getOrDefault(false)

    fun mimeOf(file: File): String {
        val ext = file.extension.lowercase()
        return when (ext) {
            "mkv" -> "video/x-matroska"
            "opus" -> "audio/ogg"
            "m4a" -> "audio/mp4"
            "vtt" -> "text/vtt"
            "srt" -> "application/x-subrip"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }
    }
}
