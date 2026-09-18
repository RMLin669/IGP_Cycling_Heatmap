package com.example.igp_cycling_heatmap.data

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

enum class FitFileSource {
    DOWNLOADED,
    IMPORTED,
}

data class LocalFitFile(
    val source: FitFileSource,
    val rideId: String,
    val fileName: String,
    val uri: Uri?,
    val legacyPath: String?,
    val privatePath: String?,
)

class LocalRouteStore(context: Context) {
    companion object {
        private const val PREFS_NAME = "igp_cycling_route_meta"
        private const val KEY_FILES = "files"
        private const val KEY_CITIES = "cities"
        private const val IMPORT_DIR = "imported_fit"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val appContext = context.applicationContext

    fun listFitFiles(): List<LocalFitFile> {
        val files = linkedMapOf<String, LocalFitFile>()
        downloadedFiles().forEach { file ->
            files["downloaded:${file.fileName}"] = file
        }
        importedFiles().forEach { file ->
            files["imported:${file.fileName}"] = file
        }
        return files.values.toList()
    }

    fun downloadedFiles(): List<LocalFitFile> {
        val files = linkedMapOf<String, LocalFitFile>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            queryMediaStoreFiles().forEach { file ->
                files[file.fileName] = file
            }
        }
        queryLegacyFiles().forEach { file ->
            if (!files.containsKey(file.fileName)) files[file.fileName] = file
        }
        return files.values.toList()
    }

    fun importedFiles(): List<LocalFitFile> {
        val dir = importedFitDirectory() ?: return emptyList()
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        return dir.listFiles { file ->
            file.isFile && file.name.endsWith(".fit", true)
        }?.map { file ->
            LocalFitFile(
                source = FitFileSource.IMPORTED,
                rideId = rideIdFromFileName(file.name),
                fileName = file.name,
                uri = null,
                legacyPath = null,
                privatePath = file.absolutePath,
            )
        }?.sortedBy { it.fileName } ?: emptyList()
    }

    fun clearImportedFiles(): Int {
        val dir = importedFitDirectory() ?: return 0
        if (!dir.exists() || !dir.isDirectory) return 0
        val files = dir.listFiles { file ->
            file.isFile && file.name.endsWith(".fit", true)
        } ?: return 0
        var deleted = 0
        files.forEach { file ->
            if (file.delete()) deleted++
        }
        return deleted
    }

    fun markDownloaded(rideId: String, fileName: String) {
        val current = readFileMap().toMutableMap()
        current[rideId] = fileName
        prefs.edit().putString(KEY_FILES, JSONObject(current).toString()).apply()
    }

    fun fileNameForRide(rideId: String): String? = readFileMap()[rideId]

    fun existingRideIds(): Set<String> {
        return listFitFiles().mapNotNull { it.rideId }.toSet()
    }

    /**
     * Returns the iGPSPORT ride ids encoded in every local FIT filename.
     *
     * This deliberately ignores the preferences metadata so that continuing a sync still works
     * after an app reinstall or when the FIT files were copied/imported from another device.
     */
    fun existingRideIdsFromFileNames(): Set<String> {
        return listFitFiles()
            .mapNotNull { extractRideIdFromFitFileName(it.fileName) }
            .toSet()
    }

    fun existingDownloadedRideIds(): Set<String> {
        return downloadedFiles().mapNotNull { it.rideId }.toSet()
    }

    fun clearDownloadedFiles(): Int {
        val deletedNames = mutableSetOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            queryMediaStoreFiles().forEach { file ->
                file.uri?.let { uri ->
                    try {
                        appContext.contentResolver.delete(uri, null, null)
                        deletedNames += file.fileName
                    } catch (_: Exception) {
                        // 单个文件删除失败时继续清理其余文件。
                    }
                }
            }
        }
        queryLegacyFiles().forEach { file ->
            if (file.fileName !in deletedNames) {
                try {
                    if (File(file.legacyPath ?: "").delete()) {
                        deletedNames += file.fileName
                    }
                } catch (_: Exception) {
                    // 单个文件删除失败时继续清理其余文件。
                }
            }
        }
        prefs.edit().remove(KEY_FILES).apply()
        return deletedNames.size
    }

    fun readBytes(file: LocalFitFile): ByteArray? {
        return try {
            if (file.privatePath != null) {
                File(file.privatePath).readBytes()
            } else if (file.uri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appContext.contentResolver.openInputStream(file.uri)?.use { it.readBytes() }
            } else if (file.legacyPath != null) {
                File(file.legacyPath).readBytes()
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun cityForRide(rideId: String): String? {
        val cities = readCities()
        return cities[rideId]?.takeIf { it.isNotBlank() }
    }

    fun saveCity(rideId: String, city: String) {
        val cities = readCities().toMutableMap()
        cities[rideId] = city
        prefs.edit().putString(KEY_CITIES, JSONObject(cities).toString()).apply()
    }

    fun importFiles(
        uris: List<Uri>,
        onProgress: (String) -> Unit = {},
    ): FitImportResult {
        val existing = listFitFiles()
        val existingFingerprints = mutableSetOf<String>()
        existing.forEach { file ->
            readBytes(file)?.let { bytes ->
                existingFingerprints += sha256(bytes)
            }
        }

        var imported = 0
        var duplicates = 0
        var failed = 0

        uris.forEachIndexed { index, uri ->
            val displayName = queryDisplayName(uri)
            onProgress("导入 ${index + 1}/${uris.size}: ${displayName.orEmpty().ifEmpty { "未知文件" }}")
            try {
                val bytes = appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("无法读取文件")
                if (bytes.isEmpty()) throw IllegalStateException("文件为空")

                val fingerprint = sha256(bytes)
                if (!existingFingerprints.add(fingerprint)) {
                    duplicates++
                    onProgress("跳过重复文件：$displayName")
                    return@forEachIndexed
                }

                val safeName = safeImportFileName(displayName)
                val target = uniqueFileInImportDir(safeName)
                target.writeBytes(bytes)
                imported++
                onProgress("已导入：${target.name}")
            } catch (e: Exception) {
                failed++
                onProgress("导入失败 ${displayName.orEmpty()}: ${e.message ?: "未知错误"}")
            }
        }

        val message = "导入完成：新增 $imported，重复 $duplicates，失败 $failed"
        onProgress(message)
        return FitImportResult(
            imported = imported,
            duplicates = duplicates,
            failed = failed,
            message = message,
        )
    }

    fun collectFitUris(treeUri: Uri): List<Uri> {
        return try {
            val uris = mutableListOf<Uri>()
            val rootDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
            walkDocumentTree(treeUri, rootDocumentId, uris)
            uris
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun walkDocumentTree(
        treeUri: Uri,
        documentId: String,
        output: MutableList<Uri>,
    ) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            documentId,
        )
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        appContext.contentResolver.query(
            childrenUri,
            projection,
            null,
            null,
            null,
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            )
            val nameColumn = cursor.getColumnIndexOrThrow(
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            )
            val mimeColumn = cursor.getColumnIndexOrThrow(
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            )
            while (cursor.moveToNext()) {
                val childDocumentId = cursor.getString(idColumn) ?: continue
                val displayName = cursor.getString(nameColumn) ?: continue
                val mimeType = cursor.getString(mimeColumn)
                if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                    walkDocumentTree(treeUri, childDocumentId, output)
                } else if (displayName.endsWith(".fit", ignoreCase = true)) {
                    output += DocumentsContract.buildDocumentUriUsingTree(
                        treeUri,
                        childDocumentId,
                    )
                }
            }
        }
    }

    private fun queryMediaStoreFiles(): List<LocalFitFile> {
        return try {
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.Downloads._ID,
                MediaStore.Downloads.DISPLAY_NAME,
            )
            val selection =
                "${MediaStore.Downloads.DISPLAY_NAME} LIKE ? AND " +
                    "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?"
            val selectionArgs = arrayOf("%.fit", "%${FileSaver.SUB_DIR}%")
            val result = mutableListOf<LocalFitFile>()
            appContext.contentResolver.query(
                collection,
                projection,
                selection,
                selectionArgs,
                null,
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                val nameColumn =
                    cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val fileName = cursor.getString(nameColumn) ?: continue
                    val uri = Uri.withAppendedPath(collection, id.toString())
                    result += LocalFitFile(
                        source = FitFileSource.DOWNLOADED,
                        rideId = rideIdFromFileName(fileName),
                        fileName = fileName,
                        uri = uri,
                        legacyPath = null,
                        privatePath = null,
                    )
                }
            }
            result
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun queryLegacyFiles(): List<LocalFitFile> {
        return try {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                FileSaver.SUB_DIR,
            )
            if (!dir.exists() || !dir.isDirectory) emptyList()
            else dir.listFiles { file ->
                file.isFile && file.name.endsWith(".fit", true)
            }?.map { file ->
                LocalFitFile(
                    source = FitFileSource.DOWNLOADED,
                    rideId = rideIdFromFileName(file.name),
                    fileName = file.name,
                    uri = null,
                    legacyPath = file.absolutePath,
                    privatePath = null,
                )
            } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun readFileMap(): Map<String, String> {
        return try {
            val json = JSONObject(prefs.getString(KEY_FILES, "{}") ?: "{}")
            val map = mutableMapOf<String, String>()
            json.keys().forEach { key ->
                map[key] = json.optString(key, "")
            }
            map
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun readCities(): Map<String, String> {
        return try {
            val json = JSONObject(prefs.getString(KEY_CITIES, "{}") ?: "{}")
            val map = mutableMapOf<String, String>()
            json.keys().forEach { key ->
                map[key] = json.optString(key, "")
            }
            map
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun rideIdFromFileName(fileName: String): String {
        val metadataId = readFileMap().entries.firstOrNull {
            it.value.equals(fileName, ignoreCase = true)
        }?.key
        if (!metadataId.isNullOrBlank()) return metadataId

        return extractRideIdFromFitFileName(fileName) ?: fileName.substringBeforeLast('.')
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            appContext.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) cursor.getString(index) else null
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            uri.lastPathSegment?.substringAfterLast('/')
        }
    }

    private fun safeImportFileName(displayName: String?): String {
        val base = displayName
            ?.substringAfterLast('/')
            ?.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_")
            ?.trim('_')
            ?.ifEmpty { "imported_route" }
            ?: "imported_route"
        return if (base.endsWith(".fit", true)) base else "$base.fit"
    }

    private fun uniqueFileInImportDir(fileName: String): File {
        val dir = importedFitDirectory() ?: File(appContext.filesDir, IMPORT_DIR)
        if (!dir.exists()) dir.mkdirs()
        val baseName = fileName.substringBeforeLast('.', fileName)
        val extension = fileName.substringAfterLast('.', "fit")
        var candidate = File(dir, fileName)
        var suffix = 2
        while (candidate.exists()) {
            candidate = File(dir, "${baseName}_$suffix.$extension")
            suffix++
        }
        return candidate
    }

    private fun importedFitDirectory(): File? {
        val external = appContext.getExternalFilesDir(null)
        return if (external != null) {
            File(external, IMPORT_DIR)
        } else {
            File(appContext.filesDir, IMPORT_DIR)
        }
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}

internal fun extractRideIdFromFitFileName(fileName: String): String? {
    val match = Regex(
        pattern = "(?:^|_)igp([0-9A-Za-z_-]+?)(?:\\s*\\(\\d+\\))?\\.fit$",
        option = RegexOption.IGNORE_CASE,
    ).find(fileName.substringAfterLast('/').trim())
    return match?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
}

data class FitImportResult(
    val imported: Int,
    val duplicates: Int,
    val failed: Int,
    val message: String,
)
