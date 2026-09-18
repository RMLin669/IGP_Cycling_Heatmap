package com.example.igp_cycling_heatmap.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class RouteSourceMode {
    DOWNLOADED,
    IMPORTED,
    ALL,
}

data class LoadedRoute(
    val rideId: String,
    val fileName: String,
    val points: List<FitPoint>,
    val source: FitFileSource,
)

data class RouteLibrary(
    val routes: List<LoadedRoute>,
    val downloadedCount: Int,
    val importedCount: Int,
    val deduplicatedCount: Int,
    val duplicateCount: Int,
    val totalDistanceKm: Double,
)

class RouteLoader(context: Context) {
    companion object {
        private const val MAX_TOTAL_RENDER_POINTS = 60_000
        private const val MIN_POINTS_PER_ROUTE = 100
        private const val MAX_POINTS_PER_ROUTE = 1_000
    }

    private val appContext = context.applicationContext
    private val store = LocalRouteStore(appContext)

    suspend fun loadLibrary(
        mode: RouteSourceMode,
        onProgress: (String) -> Unit = {},
    ): RouteLibrary = withContext(Dispatchers.IO) {
        val downloadedFiles = store.downloadedFiles()
        val importedFiles = store.importedFiles()
        val sourceFiles = when (mode) {
            RouteSourceMode.DOWNLOADED -> downloadedFiles
            RouteSourceMode.IMPORTED -> importedFiles
            RouteSourceMode.ALL -> store.listFitFiles()
        }
        if (sourceFiles.isEmpty()) {
            return@withContext RouteLibrary(
                routes = emptyList(),
                downloadedCount = downloadedFiles.size,
                importedCount = importedFiles.size,
                deduplicatedCount = 0,
                duplicateCount = 0,
                totalDistanceKm = 0.0,
            )
        }

        val deduplicated = dedupeByContent(sourceFiles)
        val uniqueFiles = deduplicated.first
        val duplicateCount = deduplicated.second
        val maxPointsPerRoute = (
            MAX_TOTAL_RENDER_POINTS / uniqueFiles.size.coerceAtLeast(1)
        ).coerceIn(MIN_POINTS_PER_ROUTE, MAX_POINTS_PER_ROUTE)
        onProgress("正在解析 ${uniqueFiles.size} 个 FIT 文件")

        val routes = mutableListOf<LoadedRoute>()
        var originalTotalDistanceKm = 0.0
        uniqueFiles.forEachIndexed { index, file ->
            try {
                val bytes = store.readBytes(file) ?: return@forEachIndexed
                val points = FitRouteParser.parse(bytes)
                if (points.size < 2) return@forEachIndexed

                originalTotalDistanceKm += routeDistanceKm(points)
                val simplifiedPoints = simplifyRoutePoints(
                    points = points,
                    minDistanceMeters = 10.0,
                    maxPoints = maxPointsPerRoute,
                )
                routes += LoadedRoute(
                    rideId = file.rideId,
                    fileName = file.fileName,
                    points = simplifiedPoints,
                    source = file.source,
                )
                onProgress("已解析 ${index + 1}/${uniqueFiles.size}: ${file.fileName}")
            } catch (e: Exception) {
                Log.w("RouteLoader", "解析失败 ${file.fileName}: ${e.message}")
            }
        }

        RouteLibrary(
            routes = routes,
            downloadedCount = downloadedFiles.size,
            importedCount = importedFiles.size,
            deduplicatedCount = uniqueFiles.size,
            duplicateCount = duplicateCount,
            totalDistanceKm = originalTotalDistanceKm,
        )
    }

    private fun dedupeByContent(
        files: List<LocalFitFile>,
    ): Pair<List<LocalFitFile>, Int> {
        val seen = mutableSetOf<String>()
        val unique = mutableListOf<LocalFitFile>()
        var duplicates = 0
        files.forEach { file ->
            val bytes = store.readBytes(file)
            val fingerprint = bytes?.let { sha256(it) } ?: file.fileName
            if (seen.add(fingerprint)) {
                unique += file
            } else {
                duplicates++
            }
        }
        return unique to duplicates
    }

    private fun routeDistanceKm(points: List<FitPoint>): Double {
        var distance = 0.0
        points.zipWithNext().forEach { (start, end) ->
            distance += haversineKm(start.lat, start.lng, end.lat, end.lng)
        }
        return distance
    }

    private fun simplifyRoutePoints(
        points: List<FitPoint>,
        minDistanceMeters: Double,
        maxPoints: Int,
    ): List<FitPoint> {
        if (points.size <= 2) return points

        var thresholdMeters = minDistanceMeters
        var simplified = simplifyByMinDistance(points, thresholdMeters)
        while (simplified.size > maxPoints) {
            thresholdMeters *= 1.6
            simplified = simplifyByMinDistance(points, thresholdMeters)
        }
        return simplified
    }

    private fun simplifyByMinDistance(
        points: List<FitPoint>,
        minDistanceMeters: Double,
    ): List<FitPoint> {
        val result = mutableListOf(points.first())
        var last = points.first()
        for (point in points.drop(1)) {
            if (hasMinimumDistanceMeters(last, point, minDistanceMeters)) {
                result += point
                last = point
            }
        }
        if (result.last() != points.last()) {
            result += points.last()
        }
        return result
    }

    private fun haversineKm(
        lat1: Double,
        lng1: Double,
        lat2: Double,
        lng2: Double,
    ): Double = haversineDistanceKm(lat1, lng1, lat2, lng2)

    private fun sha256(bytes: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}

internal fun hasMinimumDistanceMeters(
    start: FitPoint,
    end: FitPoint,
    minDistanceMeters: Double,
): Boolean {
    val distanceKm = haversineDistanceKm(start.lat, start.lng, end.lat, end.lng)
    return distanceKm * 1_000.0 >= minDistanceMeters
}

private fun haversineDistanceKm(
    lat1: Double,
    lng1: Double,
    lat2: Double,
    lng2: Double,
): Double {
    val earthRadiusKm = 6371.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLng = Math.toRadians(lng2 - lng1)
    val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
        Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
        Math.sin(dLng / 2) * Math.sin(dLng / 2)
    return earthRadiusKm * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
}
