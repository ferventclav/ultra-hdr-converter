package com.example.ultrahdr.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val OneUiDarkColorScheme = darkColorScheme(
    primary = OneUiBlue,
    onPrimary = Color.White,
    primaryContainer = OneUiDarkAccentTint,
    onPrimaryContainer = OneUiDarkTextPrimary,
    background = OneUiDarkBg,
    onBackground = OneUiDarkTextPrimary,
    surface = OneUiDarkCard,
    onSurface = OneUiDarkTextPrimary,
    surfaceVariant = OneUiDarkContainer,
    onSurfaceVariant = DarkUhdrColors.textSecondary,
    outline = OneUiDarkDivider,
    outlineVariant = OneUiDarkDivider,
    error = DarkUhdrColors.danger
)

private val OneUiLightColorScheme = lightColorScheme(
    primary = OneUiBlue,
    onPrimary = Color.White,
    primaryContainer = OneUiLightAccentTint,
    onPrimaryContainer = OneUiLightTextPrimary,
    background = OneUiLightBg,
    onBackground = OneUiLightTextPrimary,
    surface = OneUiLightCard,
    onSurface = OneUiLightTextPrimary,
    surfaceVariant = OneUiLightContainer,
    onSurfaceVariant = LightUhdrColors.textSecondary,
    outline = OneUiLightDivider,
    outlineVariant = OneUiLightDivider,
    error = LightUhdrColors.danger
)

/**
 * One UI's type scale, in the system font (One UI Sans on a Galaxy), as the Knowledge app uses it:
 * a 36sp bold enlarged header, 20sp bold titles, 18sp row titles, 14sp supporting text.
 */
private val UhdrTypography = Typography(
    // Enlarged header title.
    displaySmall = TextStyle(fontSize = 36.sp, lineHeight = 41.sp, fontWeight = FontWeight.Bold),
    // Collapsed app bar title, dialog titles.
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold),
    // Row titles.
    titleMedium = TextStyle(fontSize = 18.sp, lineHeight = 23.sp, fontWeight = FontWeight.Normal),
    titleSmall = TextStyle(fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold),
    // Dialog text.
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.Normal),
    // Info lines under a title, captions.
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal),
    // Buttons.
    labelLarge = TextStyle(fontSize = 17.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold),
    // Card sub-headers ("How it works").
    labelMedium = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold),
    // Chips on photos.
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold)
)

/** The One UI corner is 28dp on every card, row group and dialog. Photo tiles use 14dp. */
private val UhdrShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun UltraHdrTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(LocalUhdrColors provides if (darkTheme) DarkUhdrColors else LightUhdrColors) {
        MaterialTheme(
            colorScheme = if (darkTheme) OneUiDarkColorScheme else OneUiLightColorScheme,
            typography = UhdrTypography,
            shapes = UhdrShapes,
            content = content
        )
    }
}

/** Shortcut: `UhdrTheme.colors.textSecondary`. */
object UhdrTheme {
    val colors: UhdrColors
        @Composable
        @ReadOnlyComposable
        get() = LocalUhdrColors.current
}
