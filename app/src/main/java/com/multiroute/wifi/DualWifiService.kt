package com.multiroute.wifi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.multiroute.MainActivity
import com.multiroute.R
import com.multiroute.data.RouteConfigProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class DualWifiService : Service() {

    companion object {
        const val CHANNEL_ID = "multiroute_dual_wifi"
        const val NOTIFICATION_ID = 2001
        const val ACTION_START = "com.multiroute.action.START_DUAL_WIFI"
        const val ACTION_STOP = "com.multiroute.action.STOP_DUAL_WIFI"
        const val EXTRA_SSID = "extra_ssid"
        const val EXTRA_IFACE = "extra_iface"
        const val EXTRA_GENERATION = "extra_generation"

        @Volatile
        var isRunning: Boolean = false
            internal set

        fun start(context: Context, ssid: String, iface: String, generation: Long) {
            val intent = Intent(context, DualWifiService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SSID, ssid)
                putExtra(EXTRA_IFACE, iface)
                putExtra(EXTRA_GENERATION, generation)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, DualWifiService::class.java).apply {
                action = ACTION_STOP
            }
            context.stopService(intent)
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var monitorJob: Job? = null
    private var monitoredGeneration: Long? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
        // A stop may overtake onStartCommand after startForegroundService was accepted.
        // Acknowledge the launch before any stale-command or shutdown path can exit.
        enterForegroundForCurrentConnection()
    }


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            val generation = intent.getLongExtra(EXTRA_GENERATION, -1)
            if (!DualWifiManager.disconnectSecondaryWifi(this, expectedGeneration = generation)) {
                return ignoreStaleStart(startId)
            }
            isRunning = false
            monitorJob?.cancel()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val generation = if (intent == null) DualWifiManager.secondaryServiceGeneration()
            else if (intent.action == ACTION_START && intent.hasExtra(EXTRA_GENERATION))
                intent.getLongExtra(EXTRA_GENERATION, -1) else null
        val ssid = intent?.getStringExtra(EXTRA_SSID)
            ?: RouteConfigProvider.getLastSecondaryWifi(this)["ssid"] as? String
        if (generation == null || !DualWifiManager.isSecondaryServiceCurrent(generation) || ssid.isNullOrBlank()) {
            return ignoreStaleStart(startId)
        }
        val iface = intent?.getStringExtra(EXTRA_IFACE) ?: DualWifiManager.status.value.secondaryIface ?: "wlan1"

        enterForeground(ssid, iface, generation)

        startMonitoring(generation)
        return START_STICKY
    }

    private fun enterForeground(ssid: String, iface: String, generation: Long) {
        val notification = buildNotification(ssid, iface, generation)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

    }

    private fun enterForegroundForCurrentConnection() {
        val ssid = RouteConfigProvider.getLastSecondaryWifi(this)["ssid"] as? String ?: "Wi-Fi"
        enterForeground(ssid, DualWifiManager.status.value.secondaryIface ?: "wlan1",
            monitoredGeneration ?: DualWifiManager.secondaryServiceGeneration() ?: -1L)
    }

    private fun ignoreStaleStart(startId: Int): Int {
        // An old start/notification can arrive after a newer session is already monitored.
        enterForegroundForCurrentConnection()
        if (monitorJob?.isActive == true) return START_STICKY
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelfResult(startId)
        return START_NOT_STICKY
    }

    private fun startMonitoring(generation: Long) {
        // A successful auto-reconnect starts this service from inside the current monitor.
        // Keep that job alive instead of cancelling the connection that just succeeded.
        if (monitoredGeneration == generation && monitorJob?.isActive == true) return
        monitorJob?.cancel()
        monitoredGeneration = generation
        monitorJob = serviceScope.launch {
            while (isActive) {
                delay(12000)
                try {
                    DualWifiManager.refreshConnectionState(this@DualWifiService)
                    val status = DualWifiManager.status.value
                    if (!status.secondaryConnected && com.multiroute.data.RouteConfigProvider.isDualWifiAutoReconnectEnabled(this@DualWifiService)) {
                        android.util.Log.d("DualWifiService", "Secondary Wi-Fi dropped, attempting auto-reconnect...")
                        DualWifiManager.autoReconnectSecondary(this@DualWifiService)
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    android.util.Log.w("DualWifiService", "Monitor loop exception: ${e.message}")
                }
            }
        }
    }

    override fun onDestroy() {
        isRunning = false
        monitorJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.dual_wifi_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.dual_wifi_notification_channel_desc)
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(ssid: String, iface: String, generation: Long): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val disconnectIntent = Intent(this, DualWifiService::class.java).apply {
            action = ACTION_STOP
            putExtra(EXTRA_GENERATION, generation)
        }
        val disconnectPendingIntent = PendingIntent.getService(
            this,
            1,
            disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.dual_wifi_active_title))
            .setContentText(getString(R.string.dual_wifi_active_content, ssid, iface))
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                0,
                getString(R.string.dual_wifi_disconnect),
                disconnectPendingIntent
            )
            .build()
    }
}
