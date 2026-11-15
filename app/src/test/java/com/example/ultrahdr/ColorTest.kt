package com.example.ultrahdr

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.example.ultrahdr.ui.theme.DarkUhdrColors
import com.example.ultrahdr.ui.theme.LightUhdrColors
import com.example.ultrahdr.ui.theme.OneUiDarkBg
import com.example.ultrahdr.ui.theme.OneUiDarkCard
import com.example.ultrahdr.ui.theme.OneUiDarkContainer
import com.example.ultrahdr.ui.theme.OneUiLightBg
import com.example.ultrahdr.ui.theme.OneUiLightCard
import com.example.ultrahdr.ui.theme.OneUiLightContainer
import org.junit.Assert.assertTrue
import org.junit.Test

/** Red text and the white Failed label stay readable on everything they sit on. */
class ColorTest {
    private fun contrast(a: Color, b: Color): Float {
        val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (hi + 0.05f) / (lo + 0.05f)
    }

    private fun passes(text: Color, fill: Color, what: String) {
        val ratio = contrast(text, fill)
        assertTrue("$what is ${"%.2f".format(ratio)}:1, under 4.5:1", ratio >= 4.5f)
    }

    @Test
    fun redTextPassesWhereverItSits() {
        passes(LightUhdrColors.danger, OneUiLightContainer, "Stop on the grey button, light")
        passes(LightUhdrColors.danger, OneUiLightBg, "Error line on the page, light")
        passes(LightUhdrColors.danger, OneUiLightCard, "Error on a card, light")
        passes(DarkUhdrColors.danger, OneUiDarkContainer, "Stop on the grey button, dark")
        passes(DarkUhdrColors.danger, OneUiDarkBg, "Error line on the page, dark")
        passes(DarkUhdrColors.danger, OneUiDarkCard, "Error on a card, dark")
    }

    @Test
    fun failedLabelPassesInBothThemes() {
        passes(Color.White, LightUhdrColors.dangerFill, "Failed label, light")
        passes(Color.White, DarkUhdrColors.dangerFill, "Failed label, dark")
    }
}
