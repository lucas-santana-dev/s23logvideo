package br.com.plusapps.s23logvideo.record

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

/** Saídas via MediaStore: vídeos em DCIM/LogCam, relatórios JSON em Download/LogCam. */
object MediaStoreOut {
    const val VIDEO_DIR = "DCIM/LogCam/probe"
    const val RECORD_DIR = "DCIM/LogCam"
    const val REPORT_DIR = "Download/LogCam"

    fun newVideo(context: Context, displayName: String, relativePath: String = VIDEO_DIR): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "$displayName.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, relativePath)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        return context.contentResolver.insert(
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values
        ) ?: error("MediaStore recusou criar $displayName.mp4")
    }

    fun publish(context: Context, uri: Uri) {
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        context.contentResolver.update(uri, values, null, null)
    }

    fun delete(context: Context, uri: Uri) {
        runCatching { context.contentResolver.delete(uri, null, null) }
    }

    /** Grava um texto em Download/LogCam[/sub]; devolve o caminho relativo legível. */
    fun writeReport(context: Context, fileName: String, text: String, subDir: String? = null): String {
        val rel = if (subDir == null) REPORT_DIR else "$REPORT_DIR/$subDir"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "application/json")
            put(MediaStore.Downloads.RELATIVE_PATH, rel)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: error("MediaStore recusou criar $fileName")
        resolver.openOutputStream(uri)!!.use { it.write(text.toByteArray()) }
        publish(context, uri)
        return "$rel/$fileName"
    }
}
