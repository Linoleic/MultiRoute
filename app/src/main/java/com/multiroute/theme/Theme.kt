package com.multiroute.theme

import android.content.Context
import android.os.Build
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
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
    // Crossfade so a light/dark switch or a palette change fades instead of flashing.
    Crossfade(targetState = darkTheme to dynamicColor, label = "theme") { (dark, dynamic) ->
        MaterialTheme(
            colorScheme = colorSchemeFor(context, dark, dynamic),
            typography = Typography,
            content = content,
        )
    }
}

private fun colorSchemeFor(context: Context, darkTheme: Boolean, dynamicColor: Boolean): ColorScheme =
    when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }