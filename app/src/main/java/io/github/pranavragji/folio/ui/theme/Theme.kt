package io.github.pranavragji.folio.ui.theme

import android.app.Activity
import android.os.Build
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.pranavragji.folio.app
import io.github.pranavragji.folio.data.Settings

// Warm paper neutrals + one sienna accent. Restraint is the design.
private val Light = lightColorScheme(
    primary = Color(0xFF9A4A26),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFADCCB),
    onPrimaryContainer = Color(0xFF3A1606),
    secondary = Color(0xFF6E5D52),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEFE4DA),
    onSecondaryContainer = Color(0xFF261A12),
    tertiary = Color(0xFF4E6354),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFFAF7F2),
    onBackground = Color(0xFF1E1B18),
    surface = Color(0xFFFAF7F2),
    onSurface = Color(0xFF1E1B18),
    surfaceVariant = Color(0xFFEDE6DD),
    onSurfaceVariant = Color(0xFF5D554D),
    outline = Color(0xFF8E857B),
    outlineVariant = Color(0xFFDCD3C8),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF5F1EA),
    surfaceContainer = Color(0xFFF0EBE3),
    surfaceContainerHigh = Color(0xFFEAE4DB),
    surfaceContainerHighest = Color(0xFFE4DDD3),
    inverseSurface = Color(0xFF33302C),
    inverseOnSurface = Color(0xFFF6F0E8),
    inversePrimary = Color(0xFFF0B48F),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFF0B48F),
    onPrimary = Color(0xFF552108),
    primaryContainer = Color(0xFF773418),
    onPrimaryContainer = Color(0xFFFFDBCA),
    secondary = Color(0xFFD7C4B6),
    onSecondary = Color(0xFF3B2E25),
    secondaryContainer = Color(0xFF3A332D),
    onSecondaryContainer = Color(0xFFEFE0D4),
    tertiary = Color(0xFFB4CCB9),
    onTertiary = Color(0xFF203527),
    background = Color(0xFF141311),
    onBackground = Color(0xFFE9E2DA),
    surface = Color(0xFF141311),
    onSurface = Color(0xFFE9E2DA),
    surfaceVariant = Color(0xFF3A3530),
    onSurfaceVariant = Color(0xFFC6BDB3),
    outline = Color(0xFF908880),
    outlineVariant = Color(0xFF3F3A35),
    surfaceContainerLowest = Color(0xFF0F0E0C),
    surfaceContainerLow = Color(0xFF1B1A17),
    surfaceContainer = Color(0xFF201E1B),
    surfaceContainerHigh = Color(0xFF2A2825),
    surfaceContainerHighest = Color(0xFF35322F),
    inverseSurface = Color(0xFFE9E2DA),
    inverseOnSurface = Color(0xFF33302C),
    inversePrimary = Color(0xFF9A4A26),
)

private fun ColorScheme.amoled() = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0A0A0A),
    surfaceContainer = Color(0xFF111111),
    surfaceContainerHigh = Color(0xFF1A1A1A),
    surfaceContainerHighest = Color(0xFF222222),
)

/** System serif (Noto Serif): zero APK bytes, hinted, crisp at every size. */
val Serif = FontFamily.Serif

private val base = Typography()
private val FolioType = Typography(
    displayLarge = base.displayLarge.copy(fontFamily = Serif, letterSpacing = (-0.5).sp),
    displayMedium = base.displayMedium.copy(fontFamily = Serif, letterSpacing = (-0.25).sp),
    displaySmall = base.displaySmall.copy(fontFamily = Serif),
    headlineLarge = base.headlineLarge.copy(fontFamily = Serif, letterSpacing = (-0.25).sp),
    headlineMedium = base.headlineMedium.copy(fontFamily = Serif),
    headlineSmall = base.headlineSmall.copy(fontFamily = Serif),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Medium),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
    titleSmall = base.titleSmall,
    bodyLarge = base.bodyLarge.copy(lineHeight = 24.sp),
    bodyMedium = base.bodyMedium,
    bodySmall = base.bodySmall,
    labelLarge = base.labelLarge,
    labelMedium = base.labelMedium,
    labelSmall = base.labelSmall.copy(letterSpacing = 0.8.sp),
)

private val FolioShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Section-header / overline style used across screens. */
val Overline: TextStyle
    @Composable get() = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp)

@Composable
fun FolioTheme(content: @Composable () -> Unit) {
    val s by app.prefs.settings.collectAsStateWithLifecycle(initialValue = Settings())
    val dark = when (s.themeMode) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
    val ctx = LocalContext.current
    val dynamic = s.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val scheme = when {
        dynamic && dark -> dynamicDarkColorScheme(ctx)
        dynamic -> dynamicLightColorScheme(ctx)
        dark -> Dark
        else -> Light
    }.let { if (dark && s.amoled) it.amoled() else it }

    // enableEdgeToEdge follows the *system* theme; keep bar icons legible when the app overrides it.
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }

    MaterialTheme(colorScheme = scheme, typography = FolioType, shapes = FolioShapes, content = content)
}
