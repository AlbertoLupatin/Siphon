package app.siphon.ui.theme

import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.siphon.R

/*
 * "Siphon brewer": the app is named after the vacuum coffee maker, so the palette borrows its materials.
 *   espresso  – dark surfaces          brass   – the stand; primary actions
 *   glass     – the liquid; progress   sage    – audio (the coffee leaf)
 *   cherry    – errors (the coffee cherry)
 * Light mode is cool lab glass rather than warm paper.
 */

private val Espresso = darkColorScheme(
    primary = Color(0xFFD6A24E),
    onPrimary = Color(0xFF3A2600),
    primaryContainer = Color(0xFF4D3914),
    onPrimaryContainer = Color(0xFFF7DDB0),
    secondary = Color(0xFF8FCBDD),
    onSecondary = Color(0xFF00343F),
    secondaryContainer = Color(0xFF1F4651),
    onSecondaryContainer = Color(0xFFC6EBF6),
    tertiary = Color(0xFFA9C7A0),
    onTertiary = Color(0xFF173315),
    tertiaryContainer = Color(0xFF2E3F2B),
    onTertiaryContainer = Color(0xFFD6EBCF),
    error = Color(0xFFF07C86),
    onError = Color(0xFF4A0010),
    errorContainer = Color(0xFF4E1E24),
    onErrorContainer = Color(0xFFFFD9DC),
    background = Color(0xFF1A1512),
    onBackground = Color(0xFFF1E9E2),
    surface = Color(0xFF1A1512),
    onSurface = Color(0xFFF1E9E2),
    surfaceVariant = Color(0xFF392F28),
    onSurfaceVariant = Color(0xFFB5A89C),
    surfaceContainerLowest = Color(0xFF140F0D),
    surfaceContainerLow = Color(0xFF211B17),
    surfaceContainer = Color(0xFF27201B),
    surfaceContainerHigh = Color(0xFF2F2721),
    surfaceContainerHighest = Color(0xFF392F28),
    outline = Color(0xFF76685D),
    outlineVariant = Color(0xFF3F352D),
    inverseSurface = Color(0xFFF1E9E2),
    inverseOnSurface = Color(0xFF1E1915),
    inversePrimary = Color(0xFF8A5A12),
    scrim = Color.Black,
)

private val LabGlass = lightColorScheme(
    primary = Color(0xFF8A5A12),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF4DDB3),
    onPrimaryContainer = Color(0xFF2D1C00),
    secondary = Color(0xFF1F6B80),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCDEAF3),
    onSecondaryContainer = Color(0xFF00262F),
    tertiary = Color(0xFF3F6B3A),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD3EBCB),
    onTertiaryContainer = Color(0xFF0E2A0C),
    error = Color(0xFFB3263A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDADD),
    onErrorContainer = Color(0xFF410009),
    background = Color(0xFFF1F3F2),
    onBackground = Color(0xFF1E1915),
    surface = Color(0xFFF1F3F2),
    onSurface = Color(0xFF1E1915),
    surfaceVariant = Color(0xFFD5DBDA),
    onSurfaceVariant = Color(0xFF5D544D),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFEAEDEC),
    surfaceContainer = Color(0xFFE4E8E7),
    surfaceContainerHigh = Color(0xFFDDE2E1),
    surfaceContainerHighest = Color(0xFFD5DBDA),
    outline = Color(0xFF8F857C),
    outlineVariant = Color(0xFFCFC9C2),
    inverseSurface = Color(0xFF2F2721),
    inverseOnSurface = Color(0xFFF1E9E2),
    inversePrimary = Color(0xFFD6A24E),
    scrim = Color.Black,
)

// ---- type: Fraunces (soft serif) for titles only, Manrope for everything else ----

@OptIn(ExperimentalTextApi::class)
private fun manrope(weight: Int) = Font(
    R.font.manrope,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

@OptIn(ExperimentalTextApi::class)
private fun fraunces(weight: Int) = Font(
    R.font.fraunces,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(
        FontVariation.weight(weight),
        FontVariation.Setting("opsz", 72f), // display optical size: tighter, more contrast
        FontVariation.Setting("SOFT", 100f), // rounded terminals, closer to poured glass than print
        FontVariation.Setting("WONK", 0f),
    ),
)

val Manrope = FontFamily(manrope(400), manrope(500), manrope(600), manrope(700), manrope(800))
val Fraunces = FontFamily(fraunces(400), fraunces(500), fraunces(600), fraunces(700))

private fun TextStyle.with(family: FontFamily, weight: Int, tracking: Float? = null) = copy(
    fontFamily = family,
    fontWeight = FontWeight(weight),
    letterSpacing = tracking?.sp ?: letterSpacing,
)

private val SiphonType = Typography().run {
    copy(
        displayLarge = displayLarge.with(Fraunces, 600, -1f),
        displayMedium = displayMedium.with(Fraunces, 600, -0.75f),
        displaySmall = displaySmall.with(Fraunces, 600, -0.5f),
        headlineLarge = headlineLarge.with(Fraunces, 600, -0.5f),
        headlineMedium = headlineMedium.with(Fraunces, 600, -0.25f),
        headlineSmall = headlineSmall.with(Fraunces, 600, -0.1f),
        titleLarge = titleLarge.with(Manrope, 700, -0.2f),
        titleMedium = titleMedium.with(Manrope, 700, -0.1f),
        titleSmall = titleSmall.with(Manrope, 700),
        bodyLarge = bodyLarge.with(Manrope, 500),
        bodyMedium = bodyMedium.with(Manrope, 500),
        bodySmall = bodySmall.with(Manrope, 500),
        labelLarge = labelLarge.with(Manrope, 700),
        labelMedium = labelMedium.with(Manrope, 600),
        labelSmall = labelSmall.with(Manrope, 700, 0.2f),
    )
}

// Radii follow hierarchy: big surfaces round more than the controls inside them.
private val SiphonShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/** Tokens outside the Material scheme. */
@Immutable
data class SiphonExtras(
    /** Colour of the "liquid" that fills a download as it progresses. */
    val liquid: Color,
    /** False when the user turned animations off system-wide; skip decorative motion. */
    val motion: Boolean,
)

val LocalSiphonExtras = staticCompositionLocalOf { SiphonExtras(liquid = Color(0xFF8FCBDD), motion = true) }

@Composable
fun SiphonTheme(
    materialYou: Boolean = false,
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val ctx = LocalContext.current
    val scheme: ColorScheme = when {
        materialYou && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> Espresso
        else -> LabGlass
    }
    val motion = remember(ctx) {
        runCatching { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }
            .getOrDefault(1f) > 0f
    }
    CompositionLocalProvider(LocalSiphonExtras provides SiphonExtras(liquid = scheme.secondary, motion = motion)) {
        MaterialTheme(colorScheme = scheme, typography = SiphonType, shapes = SiphonShapes, content = content)
    }
}
