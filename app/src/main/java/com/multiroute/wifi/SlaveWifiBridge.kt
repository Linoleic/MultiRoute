package com.multiroute.wifi

import android.os.IBinder
import android.os.Parcel
import android.util.Log

/**
 * Privileged CLI & Binder bridge for Xiaomi / HyperOS SlaveWifiService.
 *
 * Can be invoked directly via `app_process` as root (UID 0) or called from within
 * system_server (UID 1000) by the LSPosed hook module.
 */
object SlaveWifiBridge {

    private const val TAG = "SlaveWifiBridge"
    const val DESCRIPTOR = "android.net.wifi.ISlaveWifiManager"

    const val TRANSACTION_CONNECT_TO_SLAVE_AP = 2
    const val TRANSACTION_CONNECT_TO_SLAVE_AP_WITH_BSSID = 3
    const val TRANSACTION_DISCONNECT_SLAVE_WIFI = 5
    const val TRANSACTION_SET_WIFI_SLAVE_ENABLED = 9

    /**
     * Resolves the SlaveWifiService IBinder from ServiceManager.
     */
    @JvmStatic
    fun getSlaveWifiBinder(): IBinder? = runCatching {
        val smClass = Class.forName("android.os.ServiceManager")
        val getService = smClass.getMethod("getService", String::class.java)
        getService.invoke(null, "SlaveWifiService") as? IBinder
    }.getOrNull()

    /**
     * Ensures secondary Wi-Fi STA role (ROLE_CLIENT_SECONDARY_LONG_LIVED) is enabled in SlaveWifiService.
     */
    @JvmStatic
    fun setWifiSlaveEnabled(binder: IBinder, pkgName: String = "android", enabled: Boolean = true): Boolean {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeString(pkgName)
            data.writeInt(if (enabled) 1 else 0)
            binder.transact(TRANSACTION_SET_WIFI_SLAVE_ENABLED, data, reply, 0)
            reply.readException()
            reply.readInt() != 0
        } catch (t: Throwable) {
            Log.e(TAG, "setWifiSlaveEnabled failed", t)
            false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /**
     * Connects wlan1 to the network specified by netId and optional bssid.
     */
    @JvmStatic
    fun connectToSlaveAp(binder: IBinder, netId: Int, bssid: String? = null): Boolean {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(0) // null WifiConfiguration indicates using saved netId
            data.writeInt(netId)
            if (!bssid.isNullOrEmpty()) {
                data.writeString(bssid)
                binder.transact(TRANSACTION_CONNECT_TO_SLAVE_AP_WITH_BSSID, data, reply, 0)
            } else {
                binder.transact(TRANSACTION_CONNECT_TO_SLAVE_AP, data, reply, 0)
            }
            reply.readException()
            true
        } catch (t: Throwable) {
            Log.e(TAG, "connectToSlaveAp failed (netId=$netId, bssid=$bssid)", t)
            false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /**
     * Disconnects wlan1 via SlaveWifiService.
     */
    @JvmStatic
    fun disconnectSlaveWifi(binder: IBinder): Boolean {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            binder.transact(TRANSACTION_DISCONNECT_SLAVE_WIFI, data, reply, 0)
            reply.readException()
            reply.readInt() != 0
        } catch (t: Throwable) {
            Log.e(TAG, "disconnectSlaveWifi failed", t)
            false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /**
     * Main entry point when invoked via:
     * `app_process / com.multiroute.wifi.SlaveWifiBridge [connect|disconnect|enable] [args...]`
     */
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.isEmpty()) {
            println("Usage: SlaveWifiBridge <connect|disconnect|enable> [args]")
            return
        }
        val binder = getSlaveWifiBinder()
        if (binder == null) {
            System.err.println("SlaveWifiService is not registered in ServiceManager")
            System.exit(1)
            return
        }

        when (args[0]) {
            "connect" -> {
                val netId = args.getOrNull(1)?.toIntOrNull()
                if (netId == null) {
                    System.err.println("connect requires netId argument")
                    System.exit(1)
                    return
                }
                val bssid = args.getOrNull(2)?.takeIf { it.isNotBlank() }
                // Ensure secondary STA role is active before connecting
                setWifiSlaveEnabled(binder, "android", true)
                val success = connectToSlaveAp(binder, netId, bssid)
                println("connect success=$success")
                System.exit(if (success) 0 else 2)
            }
            "disconnect" -> {
                val success = disconnectSlaveWifi(binder)
                println("disconnect success=$success")
                System.exit(if (success) 0 else 2)
            }
            "enable" -> {
                val enabled = args.getOrNull(1)?.toBooleanStrictOrNull() ?: true
                val success = setWifiSlaveEnabled(binder, "android", enabled)
                println("enable success=$success")
                System.exit(if (success) 0 else 2)
            }
            else -> {
                System.err.println("Unknown command: ${args[0]}")
                System.exit(1)
            }
        }
    }
}
