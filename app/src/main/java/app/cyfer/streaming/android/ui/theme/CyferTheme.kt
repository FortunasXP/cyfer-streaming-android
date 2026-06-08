package app.cyfer.streaming.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val CyferDarkColorScheme = darkColorScheme(
    primary = CyferWhite,
    onPrimary = CyferBlack,
    secondary = CyferTextSecondary,
    tertiary = CyferAccent,
    background = CyferBlack,
    surface = CyferDarkSurface,
    surfaceContainer = CyferCardSurface,
    surfaceContainerHigh = CyferCardSurfaceLight,
    onBackground = CyferWhite,
    onSurface = CyferWhite,
    onSurfaceVariant = CyferTextSecondary,
    outline = CyferTextTertiary
)

@Composable
fun CyferTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CyferDarkColorScheme,
        typography = CyferTypography,
        content = content
    )
}
