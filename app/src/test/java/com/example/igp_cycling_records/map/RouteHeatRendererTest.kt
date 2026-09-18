package com.example.igp_cycling_heatmap.map

import org.maplibre.geojson.FeatureCollection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteHeatRendererTest {
    companion object {
        private const val BASE_LATITUDE = 31.2304
        private const val BASE_LONGITUDE = 121.4737
        private const val METERS_PER_LATITUDE_DEGREE = 111_320.0
    }

    @Test
    fun parallelRoutesWithinTwentyMeters_keepTheirCoordinatesAndGainDensity() {
        val eastbound = horizontalRoute(latitudeMeters = 0.0, reverse = false)
        val westbound = horizontalRoute(latitudeMeters = 15.0, reverse = true)

        val singleDirection = buildDensitySegments(listOf(eastbound))
        val dense = buildDensitySegments(listOf(eastbound, westbound))

        assertEquals(singleDirection.size * 2, dense.size)
        assertTrue(dense.all { it.density == 2 })
        assertTrue(
            dense.all { segment ->
                val latitude = latitudeMeters(segment.startLat)
                kotlin.math.abs(latitude) < 0.1 || kotlin.math.abs(latitude - 15.0) < 0.1
            },
        )
    }

    @Test
    fun parallelRoutesThirtyMetersApart_remainSeparate() {
        val first = horizontalRoute(latitudeMeters = 0.0, reverse = false)
        val second = horizontalRoute(latitudeMeters = 30.0, reverse = true)

        val singleDirection = buildDensitySegments(listOf(first))
        val separate = buildDensitySegments(listOf(first, second))

        assertEquals(singleDirection.size * 2, separate.size)
        assertTrue(separate.all { it.density == 1 })
    }

    @Test
    fun oppositeDirectionsWithShiftedSamples_gainDensityAlongMostOfTheRoad() {
        val eastbound = horizontalPoints(latitudeMeters = 0.0, reverse = false)
        val shiftedWestbound = (-53..67 step 10).map { longitudeMeters ->
            RoutePoint(latitudeDegrees(15.0), longitudeDegrees(longitudeMeters.toDouble()))
        }.reversed()

        val dense = buildDensitySegments(listOf(eastbound, shiftedWestbound))
        val denseLength = dense.count { it.density == 2 } * 10.0

        assertTrue(denseLength >= 180.0)
    }

    @Test
    fun perpendicularRoutesAtCrossing_keepTheirGeometryAndIncreaseLocalDensity() {
        val horizontal = horizontalRoute(latitudeMeters = 0.0, reverse = false)
        val vertical = listOf(
            RoutePoint(latitudeDegrees(-60.0), BASE_LONGITUDE),
            RoutePoint(latitudeDegrees(60.0), BASE_LONGITUDE),
        )

        val segments = buildDensitySegments(listOf(horizontal, vertical))

        assertTrue(segments.any { it.density == 1 })
        assertTrue(segments.any { it.density == 2 })
    }

    @Test
    fun oneRouteOnlyContributesOnceToDensity() {
        val outbound = horizontalPoints(latitudeMeters = 0.0, reverse = false)
        val inbound = horizontalPoints(latitudeMeters = 5.0, reverse = true)
        val route = outbound + inbound

        val segments = buildDensitySegments(listOf(route))

        assertTrue(segments.all { it.density == 1 })
    }

    @Test
    fun rightAngle_keepsTheOriginalCorner() {
        val route = listOf(
            RoutePoint(latitudeDegrees(0.0), longitudeDegrees(-20.0)),
            RoutePoint(latitudeDegrees(0.0), longitudeDegrees(0.0)),
            RoutePoint(latitudeDegrees(20.0), longitudeDegrees(0.0)),
        )

        val segments = buildDensitySegments(listOf(route))

        assertTrue(segments.isNotEmpty())
        assertTrue(
            segments.all { segment ->
                kotlin.math.abs(segment.startLat - segment.endLat) < 1e-9 ||
                    kotlin.math.abs(segment.startLng - segment.endLng) < 1e-9
            },
        )
        assertTrue(
            segments.any { segment ->
                kotlin.math.abs(latitudeMeters(segment.endLat)) < 0.1 &&
                    kotlin.math.abs(
                        (segment.endLng - BASE_LONGITUDE) *
                            METERS_PER_LATITUDE_DEGREE *
                            kotlin.math.cos(Math.toRadians(BASE_LATITUDE)),
                    ) < 0.1
            },
        )
    }

    @Test
    fun implausibleGpsJump_isNotRenderedOrResampled() {
        val localSection = horizontalPoints(latitudeMeters = 0.0, reverse = false)
        val distantSection = horizontalPoints(latitudeMeters = 0.0, reverse = false).map {
            RoutePoint(it.latitude + 1.0, it.longitude)
        }

        val segments = buildDensitySegments(listOf(localSection + distantSection))

        assertTrue(segments.size < 30)
        assertTrue(
            segments.none { segment ->
                kotlin.math.abs(segment.endLat - segment.startLat) > 0.1
            },
        )
    }

    @Test
    fun previewRoute_producesVisibleHeatData() {
        val route = horizontalPoints(latitudeMeters = 0.0, reverse = false)

        val preview = RouteHeatRenderer.renderPreview(listOf(route))

        assertNotNull(preview)
        requireNotNull(preview)
        assertEquals(route.size - 1, preview.starts.size)
        assertTrue(preview.colors.all { color -> color ushr 24 > 0 })
    }

    @Test
    fun densityRoute_producesVisibleHeatData() {
        val route = horizontalPoints(latitudeMeters = 0.0, reverse = false)

        val heatData = RouteHeatRenderer.render(listOf(route))

        assertNotNull(heatData)
        requireNotNull(heatData)
        assertTrue(heatData.starts.isNotEmpty())
        assertTrue(heatData.colors.all { color -> color ushr 24 > 0 })
        assertTrue(
            heatData.starts.all { packed ->
                RouteHeatRenderer.latitude(packed) in
                    (BASE_LATITUDE - 0.001)..(BASE_LATITUDE + 0.001)
            },
        )
        val features = FeatureCollection.fromJson(heatData.geoJson).features().orEmpty()
        assertEquals(1, features.size)
        assertTrue(features.all { feature -> feature.hasProperty("ratio") })
    }

    @Test
    fun threeHundredRoutes_keepTheirOriginalSegments() {
        val routes = (0 until 300).map { routeIndex ->
            (-10_000..10_000 step 1_000).map { longitudeMeters ->
                RoutePoint(
                    latitudeDegrees(routeIndex * 30.0),
                    longitudeDegrees(longitudeMeters.toDouble()),
                )
            }
        }

        val segments = buildDensitySegments(routes)

        assertEquals(routes.sumOf { it.size - 1 }, segments.size)
    }

    private fun horizontalRoute(latitudeMeters: Double, reverse: Boolean): List<RoutePoint> {
        val points = horizontalPoints(latitudeMeters, reverse = false)
        return if (reverse) points.reversed() else points
    }

    private fun horizontalPoints(latitudeMeters: Double, reverse: Boolean): List<RoutePoint> {
        val points = (-60..60 step 10).map { longitudeMeters ->
            RoutePoint(latitudeDegrees(latitudeMeters), longitudeDegrees(longitudeMeters.toDouble()))
        }
        return if (reverse) points.reversed() else points
    }

    private fun latitudeDegrees(meters: Double): Double =
        BASE_LATITUDE + meters / METERS_PER_LATITUDE_DEGREE

    private fun longitudeDegrees(meters: Double): Double =
        BASE_LONGITUDE + meters /
            (METERS_PER_LATITUDE_DEGREE * kotlin.math.cos(Math.toRadians(BASE_LATITUDE)))

    private fun latitudeMeters(degrees: Double): Double =
        (degrees - BASE_LATITUDE) * METERS_PER_LATITUDE_DEGREE
}
