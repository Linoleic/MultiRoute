package com.multiroute

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.multiroute.data.RouteConfigProvider
import com.multiroute.theme.MultiRouteTheme

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    applySavedLanguage()
    com.multiroute.theme.ThemeSettings.load(this)
    enableEdgeToEdge()
    setContent {
      MultiRouteTheme { Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { MainNavigation() } }
    }
  }

  /**
   * Applies the language chosen in the settings. Per-app locales are a platform feature from Android 13
   * on (`res/xml/locales_config.xml` declares the supported ones); on older releases the choice is kept
   * but only used for resource lookups that the system already resolved.
   */
  private fun applySavedLanguage() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val tag = RouteConfigProvider.getLanguage(this)
    val manager = getSystemService(android.app.LocaleManager::class.java) ?: return
    manager.applicationLocales =
      if (tag.isEmpty()) android.os.LocaleList.getEmptyLocaleList()
      else android.os.LocaleList.forLanguageTags(tag)
  }
}
