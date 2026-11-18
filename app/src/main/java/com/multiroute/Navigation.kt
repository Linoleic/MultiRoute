package com.multiroute

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.multiroute.theme.AppMotion
import com.multiroute.theme.ThemeSettings
import com.multiroute.ui.main.MainScreen
import com.multiroute.ui.wifi.DualWifiScreen

@Composable
fun MainNavigation() {
  val backStack = rememberNavBackStack(Main)

  NavDisplay(
    backStack = backStack,
    onBack = { backStack.removeLastOrNull() },
    transitionSpec = { AppMotion.hierarchy(forward = true) },
    popTransitionSpec = { AppMotion.hierarchy(forward = false) },
    predictivePopTransitionSpec = {
      if (ThemeSettings.predictiveBack) AppMotion.predictivePop()
      else AppMotion.hierarchy(forward = false)
    },
    entryProvider =
      entryProvider {
        entry<Main> {
          MainScreen(onItemClick = { navKey -> backStack.add(navKey) }, modifier = Modifier.fillMaxSize())
        }
        entry<DualWifi> {
          DualWifiScreen(onBack = { backStack.removeLastOrNull() }, modifier = Modifier.fillMaxSize())
        }
      },
  )
}
