package com.example.igp_cycling_heatmap.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalRouteStoreTest {
    @Test
    fun extractsRideIdFromDownloadedFileName() {
        assertEquals(
            "8113153",
            extractRideIdFromFitFileName(
                "iGPSPORT_unknown_户外骑行_igp8113153.fit",
            ),
        )
    }

    @Test
    fun extractsRideIdFromMediaStoreDuplicateFileNames() {
        assertEquals(
            "8113153",
            extractRideIdFromFitFileName(
                "iGPSPORT_unknown_户外骑行_igp8113153 (1).fit",
            ),
        )
        assertEquals(
            "8113153",
            extractRideIdFromFitFileName(
                "iGPSPORT_unknown_户外骑行_igp8113153(2).FIT",
            ),
        )
    }

    @Test
    fun ignoresFileNamesWithoutTrailingRideId() {
        assertNull(extractRideIdFromFitFileName("iGPSPORT_unknown_户外骑行.fit"))
    }
}
