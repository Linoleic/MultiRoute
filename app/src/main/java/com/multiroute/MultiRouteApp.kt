package com.multiroute

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import com.multiroute.data.RouteConfigProvider
import java.util.Locale

class MultiRouteApp : Application() {
  override fun attachBaseContext(base: Context) {
    val tag = RouteConfigProvider.getLanguage(base)
    val context = if (tag.isNotEmpty()) {
      val locale = Locale.forLanguageTag(tag)
      Locale.setDefault(locale)
      val config = Configuration(base.resources.configuration)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        config.setLocales(LocaleList(locale))
      } else {
        @Suppress("DEPRECATION")
        config.locale = locale
      }
      base.createConfigurationContext(config)
    } else {
      base
    }
    super.attachBaseContext(context)
  }
}
