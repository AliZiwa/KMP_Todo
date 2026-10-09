package dev.aliziwa.todo.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.aliziwa.todo.domain.Priority

/*
 * Warm, friendly palette: coral primary, teal secondary, sunny tertiary. Hand-tuned rather than
 * generated from a seed so the three priority colours stay distinguishable in both schemes.
 */

private val Coral = Color(0xFFF2654E)
private val CoralLight = Color(0xFFFFB4A3)
private val Teal = Color(0xFF12948B)
private val TealLight = Color(0xFF7FD7CD)
private val Sunny = Color(0xFFC27A00)
private val SunnyLight = Color(0xFFFFCF7A)

private val Light = lightColorScheme(
    primary = Coral,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBD1),
    onPrimaryContainer = Color(0xFF53180A),
    inversePrimary = CoralLight,
    secondary = Teal,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFB9EFE8),
    onSecondaryContainer = Color(0xFF00322D),
    tertiary = Sunny,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE6B5),
    onTertiaryContainer = Color(0xFF4A3100),
    error = Color(0xFFC0271C),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD5),
    onErrorContainer = Color(0xFF5C0B05),
    background = Color(0xFFFFF8F5),
    onBackground = Color(0xFF2B1A16),
    surface = Color(0xFFFFF8F5),
    onSurface = Color(0xFF2B1A16),
    surfaceVariant = Color(0xFFF6E3DB),
    onSurfaceVariant = Color(0xFF6D544D),
    surfaceTint = Coral,
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFF2EC),
    surfaceContainer = Color(0xFFFFEDE5),
    surfaceContainerHigh = Color(0xFFFCE5DC),
    surfaceContainerHighest = Color(0xFFF7DDD3),
    surfaceBright = Color(0xFFFFFBF9),
    surfaceDim = Color(0xFFEBD6CF),
    outline = Color(0xFFA08880),
    outlineVariant = Color(0xFFEBD5CC),
    inverseSurface = Color(0xFF412F2A),
    inverseOnSurface = Color(0xFFFFEDE7),
    scrim = Color(0xFF000000),
)

private val Dark = darkColorScheme(
    primary = CoralLight,
    onPrimary = Color(0xFF5A1700),
    primaryContainer = Color(0xFF8E3017),
    onPrimaryContainer = Color(0xFFFFDBD1),
    inversePrimary = Coral,
    secondary = TealLight,
    onSecondary = Color(0xFF00382F),
    secondaryContainer = Color(0xFF00544C),
    onSecondaryContainer = Color(0xFFB9EFE8),
    tertiary = SunnyLight,
    onTertiary = Color(0xFF412D00),
    tertiaryContainer = Color(0xFF5E4300),
    onTertiaryContainer = Color(0xFFFFE6B5),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF1A1211),
    onBackground = Color(0xFFF2DFD9),
    surface = Color(0xFF1A1211),
    onSurface = Color(0xFFF2DFD9),
    surfaceVariant = Color(0xFF53433E),
    onSurfaceVariant = Color(0xFFD8C2BB),
    surfaceTint = CoralLight,
    // Deliberately inverted against Material's dark ladder: this app uses
    // surfaceContainerLowest for cards everywhere, and in the dark scheme a raised card should
    // read lighter than the page, not darker.
    surfaceContainerLowest = Color(0xFF261B1A),
    surfaceContainerLow = Color(0xFF211817),
    surfaceContainer = Color(0xFF2E2220),
    surfaceContainerHigh = Color(0xFF3A2B28),
    surfaceContainerHighest = Color(0xFF473632),
    surfaceBright = Color(0xFF423836),
    surfaceDim = Color(0xFF1A1211),
    outline = Color(0xFFA08C86),
    outlineVariant = Color(0xFF53433E),
    inverseSurface = Color(0xFFF2DFD9),
    inverseOnSurface = Color(0xFF392E2B),
    scrim = Color(0xFF000000),
)

/** Big, friendly corners everywhere: cards 20dp, chips and buttons fully rounded. */
val TodoShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

val Pill = RoundedCornerShape(percent = 50)

/**
 * Rounder, slightly tighter type than the Material default: heavier display/headline weights and
 * negative tracking give the default sans a friendlier, more geometric feel.
 */
private fun todoTypography(): Typography {
    val base = Typography()
    fun TextStyle.tune(weight: FontWeight, size: Int? = null, tracking: Double = 0.0, lineHeight: Int? = null) =
        copy(
            fontFamily = FontFamily.Default,
            fontWeight = weight,
            fontSize = (size ?: fontSize.value.toInt()).sp,
            letterSpacing = tracking.sp,
            lineHeight = (lineHeight ?: (size ?: fontSize.value.toInt()).times(1.25).toInt()).sp,
        )
    return Typography(
        displayLarge = base.displayLarge.tune(FontWeight.Black, 52, -1.5),
        displayMedium = base.displayMedium.tune(FontWeight.ExtraBold, 42, -1.2),
        displaySmall = base.displaySmall.tune(FontWeight.ExtraBold, 34, -1.0),
        headlineLarge = base.headlineLarge.tune(FontWeight.ExtraBold, 30, -0.8),
        headlineMedium = base.headlineMedium.tune(FontWeight.Bold, 26, -0.6),
        headlineSmall = base.headlineSmall.tune(FontWeight.Bold, 22, -0.4),
        titleLarge = base.titleLarge.tune(FontWeight.Bold, 20, -0.3),
        titleMedium = base.titleMedium.tune(FontWeight.SemiBold, 17, -0.1),
        titleSmall = base.titleSmall.tune(FontWeight.SemiBold, 15, 0.0),
        bodyLarge = base.bodyLarge.tune(FontWeight.Medium, 16, 0.0, 23),
        bodyMedium = base.bodyMedium.tune(FontWeight.Normal, 14, 0.1, 20),
        bodySmall = base.bodySmall.tune(FontWeight.Normal, 12, 0.2, 17),
        labelLarge = base.labelLarge.tune(FontWeight.Bold, 14, 0.2),
        labelMedium = base.labelMedium.tune(FontWeight.SemiBold, 12, 0.4),
        labelSmall = base.labelSmall.tune(FontWeight.SemiBold, 11, 0.5),
    )
}

/** Colours and gradients Material's scheme has no slot for. */
@Immutable
data class TodoPalette(
    val high: Color,
    val onHigh: Color,
    val highContainer: Color,
    val medium: Color,
    val onMedium: Color,
    val mediumContainer: Color,
    val low: Color,
    val onLow: Color,
    val lowContainer: Color,
    val headerGradient: List<Color>,
    val fabGradient: List<Color>,
    val ringTrack: Color,
    val confetti: List<Color>,
) {
    fun spine(priority: Priority): Color = when (priority) {
        Priority.HIGH -> high
        Priority.MEDIUM -> medium
        Priority.LOW -> low
    }

    fun container(priority: Priority): Color = when (priority) {
        Priority.HIGH -> highContainer
        Priority.MEDIUM -> mediumContainer
        Priority.LOW -> lowContainer
    }

    fun onContainer(priority: Priority): Color = when (priority) {
        Priority.HIGH -> onHigh
        Priority.MEDIUM -> onMedium
        Priority.LOW -> onLow
    }

    val headerBrush: Brush get() = Brush.linearGradient(headerGradient)
    val fabBrush: Brush get() = Brush.linearGradient(fabGradient)
}

private val LightPalette = TodoPalette(
    high = Color(0xFFE04A3A),
    onHigh = Color(0xFF5C0B05),
    highContainer = Color(0xFFFFDAD5),
    medium = Color(0xFFBE7700),
    onMedium = Color(0xFF4A3100),
    mediumContainer = Color(0xFFFFE6B5),
    low = Color(0xFF0E9A86),
    onLow = Color(0xFF00322D),
    lowContainer = Color(0xFFB9EFE8),
    headerGradient = listOf(Color(0xFFFFE2D6), Color(0xFFFFF1E2), Color(0xFFDFF4F0)),
    fabGradient = listOf(Color(0xFFFF8A6B), Color(0xFFF2654E)),
    ringTrack = Color(0xFFF2DED7),
    confetti = listOf(
        Color(0xFFF2654E), Color(0xFF12948B), Color(0xFFE09400),
        Color(0xFFFF8A6B), Color(0xFF6FD9C4), Color(0xFFFFCF7A),
    ),
)

private val DarkPalette = TodoPalette(
    high = Color(0xFFFF8A7A),
    onHigh = Color(0xFFFFDBD1),
    highContainer = Color(0xFF6E2113),
    medium = Color(0xFFFFC65C),
    onMedium = Color(0xFFFFE6B5),
    mediumContainer = Color(0xFF523A00),
    low = Color(0xFF6FD9C4),
    onLow = Color(0xFFB9EFE8),
    lowContainer = Color(0xFF00463F),
    headerGradient = listOf(Color(0xFF3A2320), Color(0xFF2A1B1A), Color(0xFF16322F)),
    fabGradient = listOf(Color(0xFFFFB4A3), Color(0xFFFF8A6B)),
    ringTrack = Color(0xFF3B2C29),
    confetti = listOf(
        Color(0xFFFFB4A3), Color(0xFF7FD7CD), Color(0xFFFFCF7A),
        Color(0xFFFF8A7A), Color(0xFF6FD9C4), Color(0xFFFFE6B5),
    ),
)

val LocalTodoPalette = staticCompositionLocalOf { LightPalette }

/** 24-hour clock preference, so every time control can read it without threading a parameter. */
val LocalUse24HourClock = staticCompositionLocalOf { false }

/** Shared Material 3 theme. Platforms wrap [dev.aliziwa.todo.App] which applies this. */
@Composable
fun TodoTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalTodoPalette provides if (dark) DarkPalette else LightPalette) {
        MaterialTheme(
            colorScheme = if (dark) Dark else Light,
            shapes = TodoShapes,
            typography = todoTypography(),
            content = content,
        )
    }
}
