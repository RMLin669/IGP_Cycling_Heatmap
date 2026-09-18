package com.example.igp_cycling_heatmap.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileOutputStream

object FileSaver {
    private const val TAG = "FileSaver"
    const val SUB_DIR = "igp_cycling_heatmap"
    val RELATIVE_PATH = "${Environment.DIRECTORY_DOWNLOADS}/$SUB_DIR"

    fun saveFit(context: Context, fileName: String, bytes: ByteArray): String? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStore(context, fileName, bytes, "application/octet-stream")
        } else {
            saveViaLegacy(fileName, bytes)
        }
    }

    fun savePng(context: Context, fileName: String, bytes: ByteArray): String? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStore(context, fileName, bytes, "image/png")
        } else {
            saveViaLegacy(fileName, bytes)
        }
    }

    private fun saveViaMediaStore(
        context: Context,
        fileName: String,
        bytes: ByteArray,
        mime: String,
    ): String? {
        val resolver = context.contentResolver
        deleteExisting(resolver, fileName)

        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri: Uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("MediaStore.insert 返回 null")
        resolver.openOutputStream(uri, "w")?.use { it.write(bytes) }
            ?: throw IllegalStateException("openOutputStream 返回 null")
        resolver.update(
            uri,
            ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
            null,
            null,
        )
        Log.d(TAG, "已保存 $fileName (${bytes.size} bytes)")
        return "下载/$SUB_DIR/$fileName"
    }

    private fun deleteExisting(resolver: android.content.ContentResolver, fileName: String) {
        try {
            resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.DISPLAY_NAME}=? AND " +
                    "${MediaStore.Downloads.RELATIVE_PATH}=?",
                arrayOf(fileName, RELATIVE_PATH),
                null,
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    resolver.delete(
                        Uri.withAppendedPath(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                            id.toString(),
                        ),
                        null,
                        null,
                    )
                }
            }
        } catch (_: Exception) {
            // 清理失败不阻塞保存
        }
    }

    private fun saveViaLegacy(fileName: String, bytes: ByteArray): String? {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            SUB_DIR,
        )
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, fileName)
        FileOutputStream(file).use { it.write(bytes) }
        return file.absolutePath
    }
}
