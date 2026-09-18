package com.example.igp_cycling_heatmap.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteLoaderTest {
    @Test
    fun minimumDistance_usesMeters() {
        val start = FitPoint(lat = 0.0, lng = 120.0)
        val aboutFiveMetersAway = FitPoint(lat = 0.0, lng = 120.000045)
        val aboutTwentyMetersAway = FitPoint(lat = 0.0, lng = 120.00018)

        assertFalse(hasMinimumDistanceMeters(start, aboutFiveMetersAway, 10.0))
        assertTrue(hasMinimumDistanceMeters(start, aboutTwentyMetersAway, 10.0))
    }
}
