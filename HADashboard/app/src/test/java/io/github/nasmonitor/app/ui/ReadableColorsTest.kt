package io.github.nasmonitor.app.ui

import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadableColorsTest {
    @After
    fun resetTheme() {
        applyTheme("light")
        setAccentColor("#5B7CFA")
    }

    @Test
    fun primaryActionsAndStatusTextMeetNormalTextContrastAcrossThemes() {
        for (theme in listOf("light", "dark", "purple", "glass", "aurora", "sunset")) {
            applyTheme(theme)
            for ((_, accent) in AccentOptions) {
                setAccentColor(accent.hex())
                val pairs = listOf(
                    OnAccent to AccentFill,
                    StatusSummaryInk to StatusSummarySurface,
                    StatusHealthyInk to StatusHealthySurface,
                    StatusAttentionInk to StatusAttentionSurface,
                    StatusCriticalInk to StatusCriticalSurface
                )
                for ((foreground, background) in pairs) {
                    assertTrue("$theme ${accent.hex()}", contrastRatio(foreground, background) >= 4.5f)
                }
            }
        }
    }

    @Test
    fun LightThemeSecondaryLabelsRemainReadableOnCards() {
        for (theme in listOf("light", "sunset")) {
            applyTheme(theme)
            for (surface in listOf(CardWhite, CardTint)) {
                assertTrue(contrastRatio(Muted, surface) >= 4.5f)
                assertTrue(contrastRatio(Subtle, surface) >= 4.5f)
            }
        }
    }
}
