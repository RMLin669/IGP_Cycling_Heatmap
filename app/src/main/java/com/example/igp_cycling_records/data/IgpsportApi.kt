package com.example.igp_cycling_heatmap.data

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

data class RideRecord(
    val id: String,
    val title: String,
    val startTime: String,
    val distanceKm: Double,
    val durationSeconds: Int,
    val downloadUrl: String?,
)

data class SyncResult(
    val remoteCount: Int,
    val downloaded: Int,
    val skipped: Int,
    val failed: Int,
    val message: String,
)

class IgpsportApi {
    companion object {
        private const val TAG = "IgpsportApi"
        const val LOGIN_URL = "https://login.passport.igpsport.cn/login?lang=zh-Hans"
        private const val ACTIVITY_URL =
            "https://prod.zh.igpsport.com/service/web-gateway/web-analyze/activity/queryMyActivity"
        private const val DOWNLOAD_URL =
            "https://prod.zh.igpsport.com/service/web-gateway/web-analyze/activity/getDownloadUrl"
        private const val PER_PAGE = 20
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }

    suspend fun getUsername(token: String): String? = withContext(Dispatchers.IO) {
        val valid = try {
            val json = requestJson(
                "$ACTIVITY_URL?pageNo=1&pageSize=1&reqType=0&sort=1",
                token,
                useAuth = true,
            )
            json?.optInt("code", -1) == 0
        } catch (e: Exception) {
            val message = e.message.orEmpty()
            // 网络异常时保守判定有效，避免因临时断网反复清空登录态；
            // 明确的 HTTP/认证失败则判定无效，触发重新登录。
            !(message.contains("HTTP", true) || message.contains("登录已过期", true))
        }
        if (!valid) return@withContext null
        parseJwtName(token)
    }

    suspend fun fetchAllActivities(token: String): List<RideRecord> = withContext(Dispatchers.IO) {
        val result = mutableListOf<RideRecord>()
        var page = 1
        while (true) {
            val json = requestJson(
                "$ACTIVITY_URL?pageNo=$page&pageSize=$PER_PAGE&reqType=0&sort=1",
                token,
                useAuth = true,
            ) ?: throw IllegalStateException("iGPSPORT 返回空响应")
            if (json.optInt("code", -1) != 0) {
                val message = json.optString(
                    "message",
                    json.optString("data", "未知错误"),
                )
                if (message.contains("token", true) || message.contains("登录")) {
                    throw IllegalStateException("iGPSPORT 登录已过期，请重新登录")
                }
                throw IllegalStateException("iGPSPORT 接口错误：$message")
            }

            val data = json.optJSONObject("data") ?: json
            val rows = data.optJSONArray("rows") ?: data.optJSONArray("list")
            if (rows == null || rows.length() == 0) break

            for (i in 0 until rows.length()) {
                val item = rows.optJSONObject(i) ?: continue
                val id = item.optString(
                    "RideId",
                    item.optString("rideId", item.optString("id", "")),
                )
                if (id.isBlank()) continue
                val distanceRaw = item.optDouble("Distance", -1.0).let {
                    if (it >= 0) it else item.optDouble("distance", -1.0)
                }.let {
                    if (it >= 0) it else item.optDouble("sportDistance", 0.0)
                }
                val distanceKm = if (distanceRaw >= 1000) distanceRaw / 1000.0 else distanceRaw
                result += RideRecord(
                    id = id,
                    title = item.optString(
                        "Title",
                        item.optString("title", item.optString("name", "骑行")),
                    ),
                    startTime = probeTimeField(item),
                    distanceKm = distanceKm,
                    durationSeconds = item.optInt(
                        "Duration",
                        item.optInt("duration", item.optInt("movingTime", 0)),
                    ),
                    downloadUrl = item.optString(
                        "DownloadUrl",
                        item.optString(
                            "downloadUrl",
                            item.optString("FileUrl", item.optString("fileUrl", "")),
                        ),
                    ).ifBlank { null },
                )
            }

            if (rows.length() < PER_PAGE) break
            page++
        }
        Log.d(TAG, "fetchAllActivities size=${result.size}")
        result
    }

    suspend fun downloadFitFile(
        token: String,
        rideId: String,
        activityDownloadUrl: String?,
    ): ByteArray = withContext(Dispatchers.IO) {
        val url = activityDownloadUrl?.takeIf { it.isNotBlank() }
            ?: run {
                val json = requestJson("$DOWNLOAD_URL/$rideId", token, useAuth = true)
                    ?: throw IllegalStateException("获取下载地址失败")
                val data = json.opt("data")
                when (data) {
                    is String -> data
                    is JSONObject -> data.optString(
                        "url",
                        data.optString("downloadUrl", ""),
                    )
                    else -> json.optString("url", json.optString("downloadUrl", ""))
                }.ifBlank { throw IllegalStateException("iGPSPORT 未返回下载地址") }
            }

        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 30_000
            readTimeout = 120_000
            setRequestProperty("User-Agent", DESKTOP_UA)
            setRequestProperty("Accept", "*/*")
            setRequestProperty("Referer", "https://app.igpsport.cn/")
        }
        try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException("FIT 下载失败：HTTP $code")
            }
            val bytes = connection.inputStream.readBytes()
            if (bytes.size < 14 ||
                bytes[8] != '.'.code.toByte() ||
                bytes[9] != 'F'.code.toByte()
            ) {
                throw IllegalStateException("下载内容不是有效 FIT 文件")
            }
            Log.d(TAG, "FIT downloaded rideId=$rideId size=${bytes.size}")
            bytes
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun requestJson(
        url: String,
        token: String,
        useAuth: Boolean,
    ): JSONObject? = withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            connectTimeout = 30_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", DESKTOP_UA)
            setRequestProperty("Accept", "application/json, text/plain, */*")
            setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
            setRequestProperty("Origin", "https://app.igpsport.cn")
            setRequestProperty("Referer", "https://app.igpsport.cn/")
            if (useAuth) setRequestProperty("Authorization", "Bearer $token")
        }
        try {
            val code = connection.responseCode
            if (code == 401 || code == 403) {
                throw IllegalStateException("iGPSPORT 登录已过期，请重新登录")
            }
            if (code !in 200..299) {
                val body = connection.errorStream?.readTextSafely().orEmpty()
                throw IllegalStateException("iGPSPORT HTTP $code ${body.take(120)}")
            }
            val body = connection.inputStream.readTextSafely()
            body.takeIf { it.isNotBlank() }?.let { JSONObject(it) }
        } finally {
            connection.disconnect()
        }
    }

    private fun probeTimeField(item: JSONObject): String {
        val keys = listOf(
            "StartTime", "startTime", "start_time", "RideDate", "rideDate",
            "ride_date", "SportTime", "sportTime", "BeginTime", "beginTime",
            "RideTime", "rideTime", "createTime", "CreateTime", "start_date",
            "startDate", "Date", "date", "Time", "time",
            "StartTimeStr", "startTimeStr", "StartDate",
        )
        for (key in keys) {
            val value = item.optString(key, "")
            if (value.isNotBlank()) return value
        }
        return ""
    }

    private fun parseJwtName(token: String): String {
        return try {
            val parts = token.split(".")
            if (parts.size < 2) return "用户"
            val payload = parts[1].replace('-', '+').replace('_', '/')
            val padded = payload + "=".repeat((4 - payload.length % 4) % 4)
            val json = JSONObject(String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8))
            json.optString("nickname").ifBlank {
                json.optString("userName").ifBlank {
                    json.optString("name").ifBlank {
                        json.optString("username").ifBlank {
                            json.optString("account").ifBlank {
                                "用户${json.optString("userId").take(6)}"
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
            "用户"
        }
    }

    private fun InputStream.readTextSafely(): String {
        return try {
            readBytes().toString(Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }

    private fun InputStream.readBytes(): ByteArray {
        val output = ByteArrayOutputStream()
        use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
            }
        }
        return output.toByteArray()
    }
}
