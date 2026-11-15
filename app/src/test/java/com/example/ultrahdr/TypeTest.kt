package com.example.ultrahdr

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.ultrahdr.ui.theme.OneUiSans
import com.example.ultrahdr.ui.theme.UhdrTypography
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** All text is set in One UI Sans, and moving the styles onto it kept their sizes and weights. */
class TypeTest {
    @Test
    fun everyStyleIsOneUiSans() {
        val t = UhdrTypography
        listOf(
            t.displayLarge, t.displayMedium, t.displaySmall,
            t.headlineLarge, t.headlineMedium, t.headlineSmall,
            t.titleLarge, t.titleMedium, t.titleSmall,
            t.bodyLarge, t.bodyMedium, t.bodySmall,
            t.labelLarge, t.labelMedium, t.labelSmall
        ).forEach { assertSame(OneUiSans, it.fontFamily) }
    }

    @Test
    fun theScaleIsUnchanged() {
        assertEquals(36.sp, UhdrTypography.displaySmall.fontSize)
        assertEquals(FontWeight.Bold, UhdrTypography.displaySmall.fontWeight)
        assertEquals(14.sp, UhdrTypography.bodyMedium.fontSize)
        assertEquals(17.sp, UhdrTypography.labelLarge.fontSize)
        assertEquals(FontWeight.SemiBold, UhdrTypography.labelLarge.fontWeight)
    }
}
