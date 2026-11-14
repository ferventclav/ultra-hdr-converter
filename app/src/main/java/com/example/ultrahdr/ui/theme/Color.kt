package com.example.ultrahdr.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// One UI tokens, from his One UI design system (One UI Design Kit v4.0) and the Knowledge app.
val OneUiBlue = Color(0xFF387AFF)

// Dark: OLED black page, near-black containers.
val OneUiDarkBg = Color(0xFF010102)
val OneUiDarkCard = Color(0xFF17171A)
val OneUiDarkContainer = Color(0xFF26262A)
val OneUiDarkDivider = Color(0xFF2B2B30)
val OneUiDarkTextPrimary = Color(0xFFFFFFFF)
val OneUiDarkAccentTint = Color(0xFF13244A)

// Light: grey page, near-white containers.
val OneUiLightBg = Color(0xFFF1F1F3)
val OneUiLightCard = Color(0xFFFCFCFF)
val OneUiLightContainer = Color(0xFFEAEAEA)
val OneUiLightDivider = Color(0xFFD4D4D8)
val OneUiLightTextPrimary = Color(0xFF000000)
val OneUiLightAccentTint = Color(0xFFE5EDFF)

/**
 * Colours the Material scheme has no slot for. Secondary text is a little darker (light theme) and
 * lighter (dark theme) than the kit's #848487, so 14sp text passes 4.5:1 on containers.
 */
@Immutable
data class UhdrColors(
    /** Fills that mean "act here" or "done": the main button, progress, ticks, row icons. */
    val accent: Color,
    /** Text and icons on [accent]. */
    val onAccent: Color,
    val textSecondary: Color,
    val hairline: Color,
    /** Accent-coloured text: on a tonal button, on the page, on glass. */
    val accentInk: Color,
    val accentTint: Color,
    val success: Color,
    val danger: Color,
    /** Floating toolbar and its pills: kit glass-toolbar, 14dp blur. */
    val glassToolbar: Color,
    /** Dialogs: kit glass, 24dp blur. */
    val glassDialog: Color,
    /** Chips over photos: kit glass-photo. */
    val glassPhoto: Color,
    val linePhoto: Color,
    /** The progress rail's empty part. */
    val rail: Color
)

val LightUhdrColors = UhdrColors(
    accent = OneUiBlue,
    onAccent = Color.White,
    textSecondary = Color(0xFF6C6C72),
    hairline = Color(0x14000000),
    accentInk = Color(0xFF2E6BEB),
    accentTint = OneUiLightAccentTint,
    success = Color(0xFF1A7F42),
    danger = Color(0xFFC8372A),
    glassToolbar = Color(0x99FFFFFF),
    glassDialog = Color(0xB3FCFCFF),
    glassPhoto = Color(0x802E2E30),
    linePhoto = Color(0x40FFFFFF),
    rail = Color(0xFFE3E3E8)
)

val DarkUhdrColors = UhdrColors(
    accent = OneUiBlue,
    onAccent = Color.White,
    textSecondary = Color(0xFF9D9DA3),
    hairline = Color(0x17FFFFFF),
    accentInk = Color(0xFF6E9DFF),
    accentTint = OneUiDarkAccentTint,
    success = Color(0xFF45C278),
    danger = Color(0xFFFF6B5C),
    glassToolbar = Color(0x9917171A),
    glassDialog = Color(0xB317171A),
    glassPhoto = Color(0x802E2E30),
    linePhoto = Color(0x40FFFFFF),
    rail = Color(0xFF2E2E33)
)

val LocalUhdrColors = staticCompositionLocalOf { LightUhdrColors }
