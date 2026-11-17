package com.multiroute.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.multiroute.adb.AdbTriggerSource
import com.multiroute.adb.WirelessAdbController
import com.multiroute.util.SuHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * BroadcastReceiver triggered upon device boot, app upgrade, unlock, Wi-Fi transition, or module
 * recovery signal. Restores Linux kernel policy routing rules and coordinates wireless ADB triggers
 * without relying on OEM-throttled JobScheduler jobs.
 */
class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != ACTION_RESTORE_RULES
        ) {
            return
        }

        val reason = intent.getStringExtra(EXTRA_RESTORE_REASON)
        val routesOnly = intent.getBooleanExtra("routes_only", false)
        Log.i(TAG, "Received restore broadcast: $action (reason=${reason ?: "boot"}, routesOnly=$routesOnly)")

        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (reason == REASON_WIFI_CONNECTED) {
                    if (!routesOnly) {
                        val adbResult = WirelessAdbController.handleTrigger(appContext, AdbTriggerSource.WIFI)
                        Log.i(TAG, "Wi-Fi wireless ADB trigger completed. Result: $adbResult")
                    }
                    return@launch
                }

                // Only non-Wi-Fi wakeups (boot/upgrade/unlock) need to push current trigger config
                // back to system_server; Wi-Fi wakeups originate from system_server itself and
                // echoing ACTION_ADB_TRIGGER_CHANGED there would risk churning trigger state.
                WirelessAdbController.publishTriggerSync(appContext)

                coroutineScope {
                    val routesDeferred = async {
                        val success = SuHelper.syncAllRouteRules(appContext)
                        Log.i(TAG, "Boot route rules sync completed. Result: $success")
                        success
                    }
                    val adbDeferred = if (!routesOnly) {
                        async {
                            val trigger = if (reason == REASON_USER_UNLOCKED) {
                                AdbTriggerSource.UNLOCK
                            } else {
                                AdbTriggerSource.BOOT
                            }
                            val adbResult = WirelessAdbController.handleTrigger(appContext, trigger)
                            Log.i(TAG, "Wireless ADB trigger ($trigger) completed. Result: $adbResult")
                            adbResult
                        }
                    } else null

                    routesDeferred.await()
                    adbDeferred?.await()
                }

                if (!routesOnly &&
                    !com.multiroute.data.RouteConfigProvider.isDualWifiDisabled(appContext) &&
                    com.multiroute.data.RouteConfigProvider.isDualWifiAutoReconnectEnabled(appContext)
                ) {
                    com.multiroute.wifi.DualWifiManager.autoReconnectSecondary(appContext)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed during boot/trigger restoration: ${t.message}", t)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "MultiRoute-BootReceiver"
        const val ACTION_RESTORE_RULES = "com.multiroute.ACTION_RESTORE_RULES"
        const val EXTRA_RESTORE_REASON = "restore_reason"
        const val REASON_BOOT = "boot"
        const val REASON_USER_UNLOCKED = "user_unlocked"
        const val REASON_WIFI_CONNECTED = "wifi_connected"
    }
}
