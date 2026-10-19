package com.multiroute

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.multiroute.data.RouteConfigProvider
import com.multiroute.theme.MultiRouteTheme
import java.util.Locale

class MainActivity : ComponentActivity() {
  override fun attachBaseContext(newBase: Context) {
    val tag = RouteConfigProvider.getLanguage(newBase)
    if (tag.isNotEmpty()) {
      val locale = Locale.forLanguageTag(tag)
      Locale.setDefault(locale)
      val config = Configuration(newBase.resources.configuration)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        config.setLocales(LocaleList(locale))
      } else {
        @Suppress("DEPRECATION")
        config.locale = locale
      }
      applyOverrideConfiguration(config)
      super.attachBaseContext(newBase.createConfigurationContext(config))
    } else {
      super.attachBaseContext(newBase)
    }
  }

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
   * Syncs the language chosen in the settings with the platform LocaleManager on Android 13+.
   */
  private fun applySavedLanguage() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val tag = RouteConfigProvider.getLanguage(this)
    val manager = getSystemService(LocaleManager::class.java) ?: return
    val expected =
      if (tag.isEmpty()) LocaleList.getEmptyLocaleList()
      else LocaleList.forLanguageTags(tag)
    if (manager.applicationLocales != expected) {
      manager.applicationLocales = expected
    }
  }
}
