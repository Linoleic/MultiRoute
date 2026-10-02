package com.multiroute.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.multiroute.util.SuHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * BroadcastReceiver triggered upon device boot or app upgrade.
 * Automatically restores Linux kernel policy routing rules (pref 14500 and LAN bypass pref 14400)
 * ensuring that routing rules persist across device reboots without requiring user interaction.
 */
class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != "android.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }

        Log.i(TAG, "Received boot/upgrade broadcast: $action. Initiating route rules restoration...")

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val success = SuHelper.syncAllRouteRules(context.applicationContext)
                Log.i(TAG, "Boot route rules sync completed. Result: $success")
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to restore route rules on boot: ${t.message}", t)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "MultiRoute-BootReceiver"
    }
}
