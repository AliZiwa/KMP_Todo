package dev.aliziwa.todo.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Seed = Color(0xFF3F6DF0)
private val SeedDark = Color(0xFFADC6FF)

private val Light = lightColorScheme(
    primary = Seed,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE4FF),
    onPrimaryContainer = Color(0xFF001848),
    secondary = Color(0xFF5A5D72),
    tertiary = Color(0xFF75546F),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    surface = Color(0xFFFDFBFF),
    surfaceVariant = Color(0xFFE2E1EC),
    onSurfaceVariant = Color(0xFF45464F),
)

private val Dark = darkColorScheme(
    primary = SeedDark,
    onPrimary = Color(0xFF002A77),
    primaryContainer = Color(0xFF003DA6),
    onPrimaryContainer = Color(0xFFDCE4FF),
    secondary = Color(0xFFC3C5DD),
    tertiary = Color(0xFFE3BADA),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    surface = Color(0xFF1B1B1F),
    surfaceVariant = Color(0xFF45464F),
    onSurfaceVariant = Color(0xFFC6C6D0),
)

/** Shared Material 3 theme. Platforms wrap [dev.aliziwa.todo.App] which applies this. */
@Composable
fun TodoTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}
