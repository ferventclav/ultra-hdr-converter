package com.example.ultrahdr.ui.theme

import android.content.Context
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
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
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
 * One UI Sans. Galaxy phones ship it as the font family "sec", which Samsung's own apps use;
 * Android's default font there is Roboto. On other phones there is no "sec", so text falls back to
 * the phone's default font.
 */
internal val OneUiSans = FontFamily(
    listOf(FontWeight.Light, FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold)
        .map { Font(DeviceFontFamilyName("sec"), it) }
)

/** Every style in [OneUiSans], including the ones the app doesn't set, so no text is left in Roboto. */
private fun Typography.inOneUiSans(): Typography = Typography(
    displayLarge = displayLarge.copy(fontFamily = OneUiSans),
    displayMedium = displayMedium.copy(fontFamily = OneUiSans),
    displaySmall = displaySmall.copy(fontFamily = OneUiSans),
    headlineLarge = headlineLarge.copy(fontFamily = OneUiSans),
    headlineMedium = headlineMedium.copy(fontFamily = OneUiSans),
    headlineSmall = headlineSmall.copy(fontFamily = OneUiSans),
    titleLarge = titleLarge.copy(fontFamily = OneUiSans),
    titleMedium = titleMedium.copy(fontFamily = OneUiSans),
    titleSmall = titleSmall.copy(fontFamily = OneUiSans),
    bodyLarge = bodyLarge.copy(fontFamily = OneUiSans),
    bodyMedium = bodyMedium.copy(fontFamily = OneUiSans),
    bodySmall = bodySmall.copy(fontFamily = OneUiSans),
    labelLarge = labelLarge.copy(fontFamily = OneUiSans),
    labelMedium = labelMedium.copy(fontFamily = OneUiSans),
    labelSmall = labelSmall.copy(fontFamily = OneUiSans)
)

/**
 * One UI's type scale, as the Knowledge app uses it: a 36sp bold enlarged header, 20sp bold titles,
 * 18sp row titles, 14sp supporting text.
 */
internal val UhdrTypography = Typography(
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
).inOneUiSans()

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
    wallpaperColours: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colors = remember(context, darkTheme, wallpaperColours) {
        val stock = if (darkTheme) DarkUhdrColors else LightUhdrColors
        if (wallpaperColours) wallpaperAccent(context, darkTheme, stock) else stock
    }
    val scheme = (if (darkTheme) OneUiDarkColorScheme else OneUiLightColorScheme)
        .copy(primary = colors.accent, onPrimary = colors.onAccent, primaryContainer = colors.accentTint)
    CompositionLocalProvider(LocalUhdrColors provides colors) {
        MaterialTheme(
            colorScheme = scheme,
            typography = UhdrTypography,
            shapes = UhdrShapes,
            content = content
        )
    }
}

/**
 * One UI's Colour palette: the accent follows the wallpaper, as Samsung's own apps do. The shades
 * are the ones his One UI design system lists. Light: accent1 600 for fills, 700 for text, 100 for
 * tints. Dark: accent1 200 for fills and text, 800 on fills and as the tint. If any of those pairs
 * reads under 4.5:1, the stock One UI blue stays.
 */
private fun wallpaperAccent(context: Context, dark: Boolean, stock: UhdrColors): UhdrColors {
    fun shade(id: Int) = Color(context.getColor(id))
    val mine = try {
        if (dark) {
            val fill = shade(android.R.color.system_accent1_200)
            val deep = shade(android.R.color.system_accent1_800)
            stock.copy(accent = fill, onAccent = deep, accentInk = fill, accentTint = deep)
        } else {
            stock.copy(
                accent = shade(android.R.color.system_accent1_600),
                onAccent = Color.White,
                accentInk = shade(android.R.color.system_accent1_700),
                accentTint = shade(android.R.color.system_accent1_100)
            )
        }
    } catch (_: Exception) {
        return stock
    }
    val page = if (dark) OneUiDarkBg else OneUiLightBg
    val readable = contrast(mine.onAccent, mine.accent) >= 4.5f &&
        contrast(mine.accentInk, mine.accentTint) >= 4.5f &&
        contrast(mine.accentInk, page) >= 4.5f
    return if (readable) mine else stock
}

/** WCAG contrast ratio between two colours, 1 to 21. */
internal fun contrast(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
}

/** Shortcut: `UhdrTheme.colors.textSecondary`. */
object UhdrTheme {
    val colors: UhdrColors
        @Composable
        @ReadOnlyComposable
        get() = LocalUhdrColors.current
}
