package com.multiroute

import android.content.Context
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
import com.multiroute.util.AppLocaleHelper

class MainActivity : ComponentActivity() {
  override fun attachBaseContext(newBase: Context) {
    val config = AppLocaleHelper.legacyOverrideConfiguration(
      newBase,
      RouteConfigProvider.getLanguage(newBase)
    )
    if (config != null) {
      applyOverrideConfiguration(config)
      super.attachBaseContext(newBase.createConfigurationContext(config))
    } else {
      super.attachBaseContext(newBase)
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    AppLocaleHelper.applyLanguage(this, RouteConfigProvider.getLanguage(this))
    com.multiroute.theme.ThemeSettings.load(this)
    enableEdgeToEdge()
    setContent {
      MultiRouteTheme { Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { MainNavigation() } }
    }
  }
}
