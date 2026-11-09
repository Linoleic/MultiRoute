package com.multiroute

import android.app.Application
import com.multiroute.data.RouteConfigProvider
import com.multiroute.util.AppLocaleHelper

class MultiRouteApp : Application() {
  override fun onCreate() {
    super.onCreate()
    AppLocaleHelper.applyLanguage(this, RouteConfigProvider.getLanguage(this))
  }
}
