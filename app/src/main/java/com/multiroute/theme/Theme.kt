package com.multiroute.theme

import android.content.Context
import android.app.Activity
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.multiroute.data.RouteConfigProvider

private val DarkColorScheme = darkColorScheme(primary = Purple80, secondary = PurpleGrey80, tertiary = Pink80)

private val LightColorScheme = lightColorScheme(primary = Purple40, secondary = PurpleGrey40, tertiary = Pink40)

/**
 * Appearance settings as observable state, so switching the theme recomposes through the crossfade
 * below instead of restarting the activity. [load] is called once by the activity; the settings screen
 * writes both the preference and this state.
 */
object ThemeSettings {
    var mode by mutableStateOf("system")
    var dynamicColor by mutableStateOf(true)

    fun load(context: Context) {
        mode = RouteConfigProvider.getThemeMode(context)
        dynamicColor = RouteConfigProvider.isDynamicColor(context)
    }
}

@Composable
fun MultiRouteTheme(
    // The stored preference wins; "system" (the default) follows the device.
    darkTheme: Boolean = when (ThemeSettings.mode) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    },
    dynamicColor: Boolean = ThemeSettings.dynamicColor,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }
    // Animate colors, not the application subtree: theme changes must preserve navigation and scroll state.
    MaterialTheme(
        colorScheme = animatedScheme(colorSchemeFor(context, darkTheme, dynamicColor)),
        typography = Typography,
        content = content,
    )
}

private fun colorSchemeFor(context: Context, darkTheme: Boolean, dynamicColor: Boolean): ColorScheme =
    when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

@Composable
private fun themeColor(value: Color): Color = animateColorAsState(value, label = "themeColor").value

@Composable
private fun animatedScheme(target: ColorScheme): ColorScheme = target.copy(
    primary = themeColor(target.primary),
    onPrimary = themeColor(target.onPrimary),
    primaryContainer = themeColor(target.primaryContainer),
    onPrimaryContainer = themeColor(target.onPrimaryContainer),
    inversePrimary = themeColor(target.inversePrimary),
    secondary = themeColor(target.secondary),
    onSecondary = themeColor(target.onSecondary),
    secondaryContainer = themeColor(target.secondaryContainer),
    onSecondaryContainer = themeColor(target.onSecondaryContainer),
    tertiary = themeColor(target.tertiary),
    onTertiary = themeColor(target.onTertiary),
    tertiaryContainer = themeColor(target.tertiaryContainer),
    onTertiaryContainer = themeColor(target.onTertiaryContainer),
    background = themeColor(target.background),
    onBackground = themeColor(target.onBackground),
    surface = themeColor(target.surface),
    onSurface = themeColor(target.onSurface),
    surfaceVariant = themeColor(target.surfaceVariant),
    onSurfaceVariant = themeColor(target.onSurfaceVariant),
    surfaceTint = themeColor(target.surfaceTint),
    inverseSurface = themeColor(target.inverseSurface),
    inverseOnSurface = themeColor(target.inverseOnSurface),
    error = themeColor(target.error),
    onError = themeColor(target.onError),
    errorContainer = themeColor(target.errorContainer),
    onErrorContainer = themeColor(target.onErrorContainer),
    outline = themeColor(target.outline),
    outlineVariant = themeColor(target.outlineVariant),
    scrim = themeColor(target.scrim),
    surfaceBright = themeColor(target.surfaceBright),
    surfaceDim = themeColor(target.surfaceDim),
    surfaceContainer = themeColor(target.surfaceContainer),
    surfaceContainerHigh = themeColor(target.surfaceContainerHigh),
    surfaceContainerHighest = themeColor(target.surfaceContainerHighest),
    surfaceContainerLow = themeColor(target.surfaceContainerLow),
    surfaceContainerLowest = themeColor(target.surfaceContainerLowest),
)
