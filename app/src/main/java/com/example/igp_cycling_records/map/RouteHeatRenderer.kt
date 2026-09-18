package com.example.igp_cycling_heatmap.map

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.tan

data class RoutePoint(
    val latitude: Double,
    val longitude: Double,
)

data class HeatData(
    val starts: LongArray,
    val ends: LongArray,
    val counts: IntArray,
    val colors: IntArray,
    val maxCount: Int,
    val geoJson: String,
)

internal data class DensitySegment(
    val startLat: Double,
    val startLng: Double,
    val endLat: Double,
    val endLng: Double,
    val density: Int,
    val routeIndex: Int,
)

private data class ProjectedPoint(
    val x: Double,
    val y: Double,
)

private data class ProjectedSegment(
    val start: ProjectedPoint,
    val end: ProjectedPoint,
    val routeIndex: Int,
) {
    val midX = (start.x + end.x) / 2.0
    val midY = (start.y + end.y) / 2.0
}

object RouteHeatRenderer {
    private const val QUANTIZE = 1_000_000.0
    private const val LAT_OFFSET = 90_000_000
    private const val LNG_OFFSET = 180_000_000
    private const val LAT_BITS = 28
    private const val LNG_BITS = 29
    private const val LNG_MASK = (1L shl LNG_BITS) - 1L

    fun render(routes: List<List<RoutePoint>>): HeatData? {
        val segments = buildDensitySegments(routes)
        if (segments.isEmpty()) return null

        val orderedSegments = segments
        val starts = LongArray(orderedSegments.size)
        val ends = LongArray(orderedSegments.size)
        val counts = IntArray(orderedSegments.size)
        val routeIndexes = IntArray(orderedSegments.size)
        orderedSegments.forEachIndexed { index, segment ->
            starts[index] = packPoint(segment.startLat, segment.startLng)
            ends[index] = packPoint(segment.endLat, segment.endLng)
            counts[index] = segment.density
            routeIndexes[index] = segment.routeIndex
        }
        val maxCount = (counts.maxOrNull() ?: 1).coerceAtLeast(2)
        val colors = IntArray(segments.size) { index ->
            thermalColor(counts[index], maxCount)
        }
        return HeatData(
            starts = starts,
            ends = ends,
            counts = counts,
            colors = colors,
            maxCount = maxCount,
            geoJson = buildHeatGeoJson(
                starts,
                ends,
                counts,
                colors,
                routeIndexes,
                maxCount,
            ),
        )
    }

    fun renderPreview(routes: List<List<RoutePoint>>): HeatData? {
        val capacity = routes.sumOf { route -> (route.size - 1).coerceAtLeast(0) }
        if (capacity == 0) return null
        val starts = LongArray(capacity)
        val ends = LongArray(capacity)
        val routeIndexes = IntArray(capacity)
        var size = 0

        routes.forEachIndexed { routeIndex, route ->
            route.zipWithNext().forEach { (start, end) ->
                if (!isDrawableSourceSegment(start, end)) return@forEach
                starts[size] = packPoint(start.latitude, start.longitude)
                ends[size] = packPoint(end.latitude, end.longitude)
                routeIndexes[size] = routeIndex
                size++
            }
        }
        if (size == 0) return null
        val counts = IntArray(size) { 1 }
        val colors = IntArray(size) { thermalColor(count = 1, maxCount = 2) }
        return HeatData(
            starts = starts.copyOf(size),
            ends = ends.copyOf(size),
            counts = counts,
            colors = colors,
            maxCount = 2,
            geoJson = buildHeatGeoJson(
                starts.copyOf(size),
                ends.copyOf(size),
                counts,
                colors,
                routeIndexes.copyOf(size),
                maxCount = 2,
            ),
        )
    }

    fun latitude(packed: Long): Double = unpackLatitude(packed)

    fun longitude(packed: Long): Double = unpackLongitude(packed)

    private fun packPoint(lat: Double, lng: Double): Long {
        val latValue = (lat * QUANTIZE).roundToInt() + LAT_OFFSET
        val lngValue = (lng * QUANTIZE).roundToInt() + LNG_OFFSET
        return (latValue.toLong() shl LNG_BITS) or lngValue.toLong()
    }

    private fun unpackLatitude(packed: Long): Double {
        val latRaw = ((packed shr LNG_BITS) and ((1L shl LAT_BITS) - 1L)).toInt()
        return (latRaw - LAT_OFFSET) / QUANTIZE
    }

    private fun unpackLongitude(packed: Long): Double {
        val lngRaw = (packed and LNG_MASK).toInt()
        return (lngRaw - LNG_OFFSET) / QUANTIZE
    }

    private fun thermalColor(count: Int, maxCount: Int): Int {
        val ratio = heatRatio(count, maxCount)
        val blue = rgb(0, 80, 255)
        val cyan = rgb(0, 230, 255)
        val yellow = rgb(255, 230, 0)
        val red = rgb(255, 35, 20)
        val baseColor = when {
            ratio < 0.33f -> interpolateColor(blue, cyan, ratio / 0.33f)
            ratio < 0.66f -> interpolateColor(cyan, yellow, (ratio - 0.33f) / 0.33f)
            else -> interpolateColor(yellow, red, (ratio - 0.66f) / 0.34f)
        }
        val alpha = (145 + 110 * ratio).toInt().coerceIn(0, 255)
        return argb(
            alpha,
            colorRed(baseColor),
            colorGreen(baseColor),
            colorBlue(baseColor),
        )
    }

    private fun heatRatio(count: Int, maxCount: Int): Float =
        if (maxCount <= 1) {
            0f
        } else {
            (ln(count.toDouble()) / ln(maxCount.toDouble())).toFloat()
        }.coerceIn(0f, 1f)

    private fun interpolateColor(start: Int, end: Int, ratio: Float): Int {
        val inverse = 1f - ratio
        return rgb(
            (colorRed(start) * inverse + colorRed(end) * ratio).toInt().coerceIn(0, 255),
            (colorGreen(start) * inverse + colorGreen(end) * ratio).toInt().coerceIn(0, 255),
            (colorBlue(start) * inverse + colorBlue(end) * ratio).toInt().coerceIn(0, 255),
        )
    }

    private fun rgb(red: Int, green: Int, blue: Int): Int =
        argb(255, red, green, blue)

    private fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int =
        (alpha shl 24) or (red shl 16) or (green shl 8) or blue

    private fun colorRed(color: Int): Int = color shr 16 and 0xFF

    private fun colorGreen(color: Int): Int = color shr 8 and 0xFF

    private fun colorBlue(color: Int): Int = color and 0xFF

    private fun buildHeatGeoJson(
        starts: LongArray,
        ends: LongArray,
        counts: IntArray,
        colors: IntArray,
        routeIndexes: IntArray,
        maxCount: Int,
    ): String {
        if (starts.isEmpty()) return EMPTY_FEATURE_COLLECTION
        val json = StringBuilder(starts.size * 64)
        json.append("{\"type\":\"FeatureCollection\",\"features\":[")
        var index = 0
        var firstFeature = true
        while (index < starts.size) {
            val count = counts[index]
            val routeIndex = routeIndexes[index]
            if (!firstFeature) json.append(',')
            firstFeature = false
            json.append("{\"type\":\"Feature\",\"properties\":{\"count\":")
                .append(count)
                .append(",\"ratio\":")
                .append(heatRatio(count, maxCount))
                .append(",\"color\":\"")
                .append(cssColor(colors[index]))
                .append("\"},\"geometry\":{\"type\":\"LineString\",\"coordinates\":[[")
                .append(longitude(starts[index])).append(',')
                .append(latitude(starts[index])).append(']')
            var previousEnd = starts[index]
            while (index < starts.size &&
                counts[index] == count &&
                routeIndexes[index] == routeIndex &&
                starts[index] == previousEnd
            ) {
                json.append(",[")
                    .append(longitude(ends[index])).append(',')
                    .append(latitude(ends[index])).append(']')
                previousEnd = ends[index]
                index++
            }
            json.append("]}}")
        }
        return json.append("]}").toString()
    }

    private fun cssColor(color: Int): String {
        val alpha = (color ushr 24 and 0xFF) / 255.0
        return "rgba(${colorRed(color)},${colorGreen(color)},${colorBlue(color)},$alpha)"
    }

    private const val EMPTY_FEATURE_COLLECTION =
        "{\"type\":\"FeatureCollection\",\"features\":[]}"
}

private const val EARTH_RADIUS_METERS = 6_378_137.0
private const val MAX_MERCATOR_LATITUDE = 85.05112878
private const val MIN_SEGMENT_LENGTH_METERS = 0.5
private const val MAX_SOURCE_GAP_METERS = 50_000.0
private const val DENSITY_RADIUS_METERS = 20.0
private const val DENSITY_BUCKET_METERS = 50.0

internal fun buildDensitySegments(routes: List<List<RoutePoint>>): List<DensitySegment> {
    val sourceSegments = mutableListOf<ProjectedSegment>()

    routes.forEachIndexed { routeIndex, route ->
        val validPoints = route.asSequence()
            .filter { point ->
                point.latitude.isFinite() && point.longitude.isFinite() &&
                    point.latitude in -90.0..90.0 && point.longitude in -180.0..180.0
            }
            .map(::project)
            .toList()
        splitRouteAtGaps(validPoints).forEach { section ->
            section.zipWithNext().forEach { (start, end) ->
                val length = distance(start, end)
                if (length in MIN_SEGMENT_LENGTH_METERS..MAX_SOURCE_GAP_METERS) {
                    sourceSegments += ProjectedSegment(start, end, routeIndex)
                }
            }
        }
    }
    if (sourceSegments.isEmpty()) return emptyList()

    val densityIndex = buildDensityIndex(sourceSegments)
    return sourceSegments.map { segment ->
        val start = unproject(segment.start)
        val end = unproject(segment.end)
        DensitySegment(
            startLat = start.latitude,
            startLng = start.longitude,
            endLat = end.latitude,
            endLng = end.longitude,
            density = densityAtSegment(segment, sourceSegments, densityIndex),
            routeIndex = segment.routeIndex,
        )
    }
}

private fun buildDensityIndex(
    segments: List<ProjectedSegment>,
): Map<Long, List<Int>> {
    val indexesByBucket = HashMap<Long, MutableList<Int>>()
    segments.forEachIndexed { index, segment ->
        val projectedRadius = DENSITY_RADIUS_METERS / groundScaleAtY(segment.midY)
        val minCellX = floor(
            (minOf(segment.start.x, segment.end.x) - projectedRadius) / DENSITY_BUCKET_METERS,
        ).toInt()
        val maxCellX = floor(
            (maxOf(segment.start.x, segment.end.x) + projectedRadius) / DENSITY_BUCKET_METERS,
        ).toInt()
        val minCellY = floor(
            (minOf(segment.start.y, segment.end.y) - projectedRadius) / DENSITY_BUCKET_METERS,
        ).toInt()
        val maxCellY = floor(
            (maxOf(segment.start.y, segment.end.y) + projectedRadius) / DENSITY_BUCKET_METERS,
        ).toInt()
        for (cellX in minCellX..maxCellX) {
            for (cellY in minCellY..maxCellY) {
                indexesByBucket.getOrPut(packCell(cellX, cellY)) { mutableListOf() } += index
            }
        }
    }
    return indexesByBucket
}

private fun densityAtSegment(
    segment: ProjectedSegment,
    segments: List<ProjectedSegment>,
    densityIndex: Map<Long, List<Int>>,
): Int {
    val nearbyRoutes = HashSet<Int>()
    densityIndex[bucketKey(segment.midX, segment.midY)].orEmpty().forEach { candidateIndex ->
        val candidate = segments[candidateIndex]
        if (pointToSegmentDistance(segment.midX, segment.midY, candidate) <=
            DENSITY_RADIUS_METERS
        ) {
            nearbyRoutes += candidate.routeIndex
        }
    }
    return nearbyRoutes.size.coerceAtLeast(1)
}

private fun pointToSegmentDistance(
    pointX: Double,
    pointY: Double,
    segment: ProjectedSegment,
): Double {
    val deltaX = segment.end.x - segment.start.x
    val deltaY = segment.end.y - segment.start.y
    val lengthSquared = deltaX * deltaX + deltaY * deltaY
    val ratio = if (lengthSquared == 0.0) {
        0.0
    } else {
        ((pointX - segment.start.x) * deltaX +
            (pointY - segment.start.y) * deltaY) / lengthSquared
    }.coerceIn(0.0, 1.0)
    val closestX = segment.start.x + deltaX * ratio
    val closestY = segment.start.y + deltaY * ratio
    return hypot(pointX - closestX, pointY - closestY) *
        groundScaleAtY((pointY + closestY) / 2.0)
}

private fun splitRouteAtGaps(points: List<ProjectedPoint>): List<List<ProjectedPoint>> {
    if (points.size < 2) return emptyList()
    val sections = mutableListOf<List<ProjectedPoint>>()
    var current = mutableListOf(points.first())
    points.drop(1).forEach { point ->
        if (distance(current.last(), point) > MAX_SOURCE_GAP_METERS) {
            if (current.size >= 2) sections += current
            current = mutableListOf(point)
        } else {
            current += point
        }
    }
    if (current.size >= 2) sections += current
    return sections
}

private fun project(point: RoutePoint): ProjectedPoint {
    val latitude = point.latitude.coerceIn(-MAX_MERCATOR_LATITUDE, MAX_MERCATOR_LATITUDE)
    val x = EARTH_RADIUS_METERS * Math.toRadians(point.longitude)
    val y = EARTH_RADIUS_METERS * ln(tan(PI / 4.0 + Math.toRadians(latitude) / 2.0))
    return ProjectedPoint(x, y)
}

private fun isDrawableSourceSegment(start: RoutePoint, end: RoutePoint): Boolean {
    return distance(project(start), project(end)) in
        MIN_SEGMENT_LENGTH_METERS..MAX_SOURCE_GAP_METERS
}

private fun unproject(point: ProjectedPoint): RoutePoint {
    val longitude = Math.toDegrees(point.x / EARTH_RADIUS_METERS)
    val latitude = Math.toDegrees(2.0 * atan(exp(point.y / EARTH_RADIUS_METERS)) - PI / 2.0)
    return RoutePoint(latitude, longitude)
}

private fun distance(first: ProjectedPoint, second: ProjectedPoint): Double =
    hypot(second.x - first.x, second.y - first.y) *
        groundScaleAtY((first.y + second.y) / 2.0)

private fun groundScaleAtY(y: Double): Double {
    val latitudeRadians = 2.0 * atan(exp(y / EARTH_RADIUS_METERS)) - PI / 2.0
    return cos(latitudeRadians).coerceAtLeast(0.01)
}

private fun bucketKey(x: Double, y: Double): Long = packCell(
    floor(x / DENSITY_BUCKET_METERS).toInt(),
    floor(y / DENSITY_BUCKET_METERS).toInt(),
)

private fun packCell(x: Int, y: Int): Long =
    (x.toLong() shl 32) xor (y.toLong() and 0xFFFF_FFFFL)
