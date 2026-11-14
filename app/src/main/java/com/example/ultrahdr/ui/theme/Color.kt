package com.example.ultrahdr.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Samsung One UI 8.5/9 design tokens (Figma One UI Design Kit v4.0)
val OneUiBlue = Color(0xFF387AFF)

// Dark theme (kit page "black")
val OneUiDarkBg = Color(0xFF010102)
val OneUiDarkCard = Color(0xFF17171A)
val OneUiDarkContainer = Color(0xFF26262A)
val OneUiDarkDivider = Color(0xFF2B2B30)
val OneUiDarkTextPrimary = Color(0xFFFFFFFF)
val OneUiDarkAccentTint = Color(0xFF13244A)

// Light theme (kit page "oneui")
val OneUiLightBg = Color(0xFFF1F1F3)
val OneUiLightCard = Color(0xFFFCFCFF)
val OneUiLightContainer = Color(0xFFEAEAEA)
val OneUiLightDivider = Color(0xFFD4D4D8)
val OneUiLightTextPrimary = Color(0xFF000000)
val OneUiLightAccentTint = Color(0xFFE5EDFF)

// Feedback
val OneUiGreen = Color(0xFF1E8E4A)
val OneUiRed = Color(0xFFD33A2C)
val OneUiAmber = Color(0xFFE08700)

/**
 * Colours the Material scheme has no slot for. Secondary text is a little darker (light theme) and
 * lighter (dark theme) than the kit's #848487, so small text passes 4.5:1 contrast on cards.
 */
@Immutable
data class UhdrColors(
    val textSecondary: Color,
    val hairline: Color,
    val segmentTrack: Color,
    val segmentThumb: Color,
    val success: Color,
    val danger: Color,
    val warning: Color,
    val accentTint: Color,
    /** The thin bright rim that makes a frosted surface read as glass. */
    val glassEdge: Color
)

val LightUhdrColors = UhdrColors(
    textSecondary = Color(0xFF68686E),
    hairline = Color(0x1F000000),
    segmentTrack = Color(0xFFE9E9EC),
    segmentThumb = Color(0xFFFFFFFF),
    success = Color(0xFF1A7F42),
    danger = Color(0xFFC8372A),
    warning = Color(0xFFA86400),
    accentTint = OneUiLightAccentTint,
    glassEdge = Color(0xCCFFFFFF)
)

val DarkUhdrColors = UhdrColors(
    textSecondary = Color(0xFF9D9DA3),
    hairline = Color(0x24FFFFFF),
    segmentTrack = Color(0xFF26262A),
    segmentThumb = Color(0xFF45454B),
    success = Color(0xFF45C278),
    danger = Color(0xFFFF6B5C),
    warning = Color(0xFFF0A63A),
    accentTint = OneUiDarkAccentTint,
    glassEdge = Color(0x33FFFFFF)
)

val LocalUhdrColors = staticCompositionLocalOf { LightUhdrColors }
