package com.multiroute.data

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import com.multiroute.model.CHANNEL_DEFAULT

class RouteConfigProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "com.multiroute.provider"
        const val PREF_NAME = "multiroute_rules"
        const val METHOD_GET_ROUTE = "getRoute"
        const val METHOD_SET_ROUTE = "setRoute"
        const val KEY_CHANNEL_ID = "channel_id"
        const val KEY_KEEP_SLAVE_WIFI_SCREEN_OFF = "keep_slave_wifi_screen_off"

        fun isKeepSlaveWifiScreenOff(context: Context): Boolean {
            val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            return sp.getBoolean(KEY_KEEP_SLAVE_WIFI_SCREEN_OFF, false)
        }

        fun setKeepSlaveWifiScreenOff(context: Context, enabled: Boolean) {
            val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            sp.edit().putBoolean(KEY_KEEP_SLAVE_WIFI_SCREEN_OFF, enabled).apply()
        }

        fun getTargetChannel(context: Context, packageName: String): String {
            val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            return sp.getString(packageName, null) ?: CHANNEL_DEFAULT
        }

        fun setTargetChannel(context: Context, packageName: String, channelId: String) {
            setTargetChannels(context, listOf(packageName), channelId)
        }

        fun setTargetChannels(context: Context, packageNames: Collection<String>, channelId: String) {
            if (packageNames.isEmpty()) return
            val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val pm = context.packageManager
            val editor = sp.edit()
            for (packageName in packageNames) {
                val uid = runCatching { pm.getPackageUid(packageName, 0) }.getOrDefault(-1)
                if (channelId == CHANNEL_DEFAULT || channelId.isEmpty()) {
                    editor.remove(packageName)
                    if (uid != -1) editor.remove("uid_$uid")
                } else {
                    editor.putString(packageName, channelId)
                    if (uid != -1) editor.putString("uid_$uid", channelId)
                }
            }
            editor.apply()
        }

        fun getAllRules(context: Context): Map<String, String> {
            val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val result = mutableMapOf<String, String>()
            sp.all.forEach { (key, value) ->
                if (!key.startsWith("uid_") && key != KEY_KEEP_SLAVE_WIFI_SCREEN_OFF && value is String) {
                    result[key] = value
                }
            }
            return result
        }

        fun clearAllRules(context: Context) {
            val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val keepScreenOff = sp.getBoolean(KEY_KEEP_SLAVE_WIFI_SCREEN_OFF, false)
            sp.edit().clear().putBoolean(KEY_KEEP_SLAVE_WIFI_SCREEN_OFF, keepScreenOff).apply()
        }
    }

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val ctx = context ?: return null
        val bundle = Bundle()

        when (method) {
            METHOD_GET_ROUTE -> {
                val packageName = arg ?: return null
                val channelId = getTargetChannel(ctx, packageName)
                bundle.putString(KEY_CHANNEL_ID, channelId)
                return bundle
            }
            METHOD_SET_ROUTE -> {
                val packageName = arg ?: return null
                val channelId = extras?.getString(KEY_CHANNEL_ID, CHANNEL_DEFAULT) ?: CHANNEL_DEFAULT
                setTargetChannel(ctx, packageName, channelId)
                bundle.putBoolean("success", true)
                return bundle
            }
        }
        return null
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0
}
