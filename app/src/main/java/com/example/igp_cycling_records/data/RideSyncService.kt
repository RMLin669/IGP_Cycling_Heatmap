package com.example.igp_cycling_heatmap.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RideSyncService(context: Context) {
    private val appContext = context.applicationContext
    private val api = IgpsportApi()
    private val store = LocalRouteStore(appContext)
    private val prefs = PrefsManager(appContext)

    suspend fun sync(
        onProgress: (String) -> Unit,
        clearDownloadedFirst: Boolean = false,
    ): SyncResult =
        withContext(Dispatchers.IO) {
            val token = prefs.token() ?: throw IllegalStateException("请先登录 iGPSPORT")
            if (clearDownloadedFirst) {
                onProgress("正在清空本地下载的 FIT 文件")
                val deleted = store.clearDownloadedFiles()
                onProgress("已清空 $deleted 个本地下载 FIT 文件")
            }
            onProgress("正在获取 iGPSPORT 骑行记录")
            val remote = api.fetchAllActivities(token)
            onProgress("获取到 ${remote.size} 条记录，开始比对本地文件")

            val existingIds = if (clearDownloadedFirst) {
                mutableSetOf()
            } else {
                store.existingRideIdsFromFileNames().toMutableSet()
            }
            if (!clearDownloadedFirst) {
                onProgress("从本地 FIT 文件名识别到 ${existingIds.size} 条已有记录")
            }
            var downloaded = 0
            var skipped = 0
            var failed = 0

            remote.forEachIndexed { index, record ->
                if (existingIds.contains(record.id)) {
                    skipped++
                    onProgress("跳过重复记录 ${index + 1}/${remote.size}: ${record.id}")
                    return@forEachIndexed
                }
                val fileName = store.fileNameForRide(record.id)
                    ?: FileNameGenerator.generate(record)
                onProgress(
                    "下载 ${index + 1}/${remote.size}: ${fileName}",
                )
                try {
                    val bytes = api.downloadFitFile(
                        token = token,
                        rideId = record.id,
                        activityDownloadUrl = record.downloadUrl,
                    )
                    FileSaver.saveFit(appContext, fileName, bytes)
                        ?: throw IllegalStateException("保存文件失败")
                    store.markDownloaded(record.id, fileName)
                    existingIds += record.id
                    downloaded++
                } catch (e: Exception) {
                    failed++
                    Log.w("RideSyncService", "下载失败 ${record.id}: ${e.message}")
                }
            }

            val message = if (failed == 0) {
                if (clearDownloadedFirst) {
                    "重新同步完成：新增 $downloaded，跳过 $skipped"
                } else {
                    "同步完成：新增 $downloaded，跳过 $skipped"
                }
            } else {
                if (clearDownloadedFirst) {
                    "重新同步完成：新增 $downloaded，跳过 $skipped，失败 $failed"
                } else {
                    "同步完成：新增 $downloaded，跳过 $skipped，失败 $failed"
                }
            }
            onProgress(message)
            SyncResult(
                remoteCount = remote.size,
                downloaded = downloaded,
                skipped = skipped,
                failed = failed,
                message = message,
            )
        }
}
