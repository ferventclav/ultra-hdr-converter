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
 * One type scale for the whole app, in the system font (SamsungOne on a Galaxy).
 * Sizes follow One UI: a large screen title, 17sp row titles, 13sp supporting text.
 */
private val UhdrTypography = Typography(
    displaySmall = TextStyle(fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold)
)

private val UhdrShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(32.dp)
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
