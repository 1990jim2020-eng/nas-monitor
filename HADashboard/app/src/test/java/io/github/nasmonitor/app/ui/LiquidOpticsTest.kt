package io.github.nasmonitor.app.ui

import org.junit.Assert.*
import org.junit.Test

class LiquidOpticsTest {
    @Test fun lensKeepsEdgesAndCenterAnchored() {
        for (extent in listOf(32f, 64f, 360f, 1200f)) {
            assertEquals(0f, refractedAxis(0f, extent, 22f), 0.001f)
            assertEquals(extent, refractedAxis(extent, extent, 22f), 0.001f)
            assertEquals(extent / 2f, refractedAxis(extent / 2f, extent, 22f), 0.001f)
        }
    }

    @Test fun meshDoesNotFoldOrEscapeAtPhoneAndTabletSizes() {
        for (extent in listOf(1f, 16f, 48f, 120f, 360f, 1024f, 2200f)) {
            var previous = -1f
            for (step in 0..200) {
                val mapped = refractedAxis(extent * step / 200, extent, 22f)
                assertTrue(mapped >= previous)
                assertTrue(mapped in 0f..extent)
                previous = mapped
            }
        }
    }

    @Test fun refractionIsSymmetricAndNotJustTransparency() {
        val left = refractedAxis(20f, 360f, 22f)
        assertTrue(left > 20f)
        assertEquals(360f - left, refractedAxis(340f, 360f, 22f), 0.001f)
        assertEquals(10f, refractedAxis(10f, 0f, 22f), 0f)
    }

    @Test fun glassAndSunsetUseLightSurfaces() {
        for (mode in listOf("light", "glass", "sunset")) assertFalse(usesDarkSurfaces(mode))
        for (mode in listOf("dark", "purple", "aurora")) assertTrue(usesDarkSurfaces(mode))
    }
}
