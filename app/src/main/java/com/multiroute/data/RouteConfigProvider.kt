package com.multiroute.data

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import com.multiroute.model.CHANNEL_DEFAULT

/**
 * A rule target: the display key (`pkg` for the primary user, `pkg@<userId>` for an OEM clone space
 * or work profile) plus the UID the kernel rule is built from. Carrying the UID explicitly is what
 * lets a clone install and the primary install of the same package be configured independently.
 */
data class RuleTarget(val key: String, val uid: Int)

class RouteConfigProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "com.multiroute.provider"
        const val PREF_NAME = "multiroute_rules"
        const val METHOD_GET_ROUTE = "getRoute"
        const val METHOD_SET_ROUTE = "setRoute"
        const val KEY_CHANNEL_ID = "channel_id"
        const val KEY_KEEP_SLAVE_WIFI_SCREEN_OFF = "keep_slave_wifi_screen_off"

        /**
         * Bookkeeping for the UID list this app merged into the platform's `mobile_data_preferred_uids`
         * setting. Only those UIDs are ever taken back out again, so entries owned by the system or by
         * another tool are left alone. Not a rule key: excluded from [getAllRules] and ignored by the
         * module, which only reads `uid_<n>` keys.
         */
        const val KEY_MERGED_CELLULAR_UIDS = "merged_cellular_uids"

        /**
         * Whether an assigned app resolves DNS through its assigned channel. On by default: without it an
         * app can query a resolver that is only reachable over a different link, which is a real failure
         * mode rather than a preference.
         */
        const val KEY_DNS_FOLLOWS_CHANNEL = "dns_follows_channel"

        fun isDnsFollowsChannel(context: Context): Boolean {
            val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            return sp.getBoolean(KEY_DNS_FOLLOWS_CHANNEL, true)
        }

        fun setDnsFollowsChannel(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_DNS_FOLLOWS_CHANNEL, enabled).apply()
        }

        private val SAFE_PKG_REGEX = Regex("^[a-zA-Z0-9_.]+$")
        private val SAFE_CHANNEL_REGEX = Regex("^[a-zA-Z0-9_.]{1,15}$")

        fun isValidPackageName(pkg: String): Boolean {
            return pkg.isNotEmpty() && pkg.length <= 128 && SAFE_PKG_REGEX.matches(pkg)
        }

        fun isValidChannelId(channelId: String): Boolean {
            return channelId == CHANNEL_DEFAULT || SAFE_CHANNEL_REGEX.matches(channelId)
        }

        fun isKeepSlaveWifiScreenOff(context: Context): Boolean {
            val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            return sp.getBoolean(KEY_KEEP_SLAVE_WIFI_SCREEN_OFF, false)
        }

        fun setKeepSlaveWifiScreenOff(context: Context, enabled: Boolean) {
            val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            sp.edit().putBoolean(KEY_KEEP_SLAVE_WIFI_SCREEN_OFF, enabled).apply()
        }

        fun getTargetChannel(context: Context, ruleKey: String): String {
            if (!isValidRuleKey(ruleKey)) return CHANNEL_DEFAULT
            val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            return sp.getString(ruleKey, null) ?: CHANNEL_DEFAULT
        }

        /**
         * A rule target: the display key (`pkg` for the primary user, `pkg@<userId>` for a clone space
         * or work profile) plus the UID the kernel rule is built from.
         */
        fun isValidRuleKey(key: String): Boolean {
            val (pkg, userId) = com.multiroute.util.RouteRuleBuilder.parseRuleKey(key)
            return isValidPackageName(pkg) && userId in 0..999
        }

        fun setTargetChannel(context: Context, target: RuleTarget, channelId: String) {
            setTargetChannels(context, listOf(target), channelId)
        }

        fun setTargetChannels(context: Context, targets: Collection<RuleTarget>, channelId: String) {
            if (targets.isEmpty()) return
            val sanitizedChannel = if (isValidChannelId(channelId)) channelId else CHANNEL_DEFAULT
            val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val editor = sp.edit()
            for (target in targets) {
                if (!isValidRuleKey(target.key)) continue
                if (sanitizedChannel == CHANNEL_DEFAULT || sanitizedChannel.isEmpty()) {
                    editor.remove(target.key)
                    if (target.uid > 0) editor.remove("uid_${target.uid}")
                } else {
                    editor.putString(target.key, sanitizedChannel)
                    if (target.uid > 0) editor.putString("uid_${target.uid}", sanitizedChannel)
                }
            }
            editor.apply()
        }

        /**
         * UID→channel map, i.e. exactly the `uid_<n>` keys the injected module reads. This is the single
         * source of truth for kernel rules, so clone-space UIDs are routed like any other UID.
         */
        fun getUidRules(context: Context): Map<Int, String> {
            val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val result = mutableMapOf<Int, String>()
            sp.all.forEach { (key, value) ->
                if (key.startsWith("uid_") && value is String) {
                    key.removePrefix("uid_").toIntOrNull()?.let { result[it] = value }
                }
            }
            return result
        }

        fun getAllRules(context: Context): Map<String, String> {
            val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val result = mutableMapOf<String, String>()
            sp.all.forEach { (key, value) ->
                if (!key.startsWith("uid_") &&
                    key != KEY_KEEP_SLAVE_WIFI_SCREEN_OFF &&
                    key != KEY_MERGED_CELLULAR_UIDS &&
                    value is String
                ) {
                    result[key] = value
                }
            }
            return result
        }

        fun getMergedCellularUids(context: Context): Set<Int> {
            val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val raw = sp.getString(KEY_MERGED_CELLULAR_UIDS, null) ?: return emptySet()
            return com.multiroute.util.RouteRuleBuilder.parseUidList(raw)
        }

        fun setMergedCellularUids(context: Context, uids: Collection<Int>) {
            val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val text = com.multiroute.util.RouteRuleBuilder.buildUidList(uids)
            if (text.isEmpty()) sp.edit().remove(KEY_MERGED_CELLULAR_UIDS).apply()
            else sp.edit().putString(KEY_MERGED_CELLULAR_UIDS, text).apply()
        }

        fun clearAllRules(context: Context) {
            val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val keepScreenOff = sp.getBoolean(KEY_KEEP_SLAVE_WIFI_SCREEN_OFF, false)
            val keepDnsFollows = sp.getBoolean(KEY_DNS_FOLLOWS_CHANNEL, true)
            sp.edit().clear()
                .putBoolean(KEY_KEEP_SLAVE_WIFI_SCREEN_OFF, keepScreenOff)
                .putBoolean(KEY_DNS_FOLLOWS_CHANNEL, keepDnsFollows)
                .apply()
        }
    }

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val ctx = context ?: return null
        val bundle = Bundle()

        when (method) {
            METHOD_GET_ROUTE -> {
                val ruleKey = arg ?: return null
                if (!isValidRuleKey(ruleKey)) return null
                bundle.putString(KEY_CHANNEL_ID, getTargetChannel(ctx, ruleKey))
                return bundle
            }
            METHOD_SET_ROUTE -> {
                val ruleKey = arg ?: return null
                if (!isValidRuleKey(ruleKey)) return null
                val channelId = extras?.getString(KEY_CHANNEL_ID, CHANNEL_DEFAULT) ?: CHANNEL_DEFAULT
                if (!isValidChannelId(channelId)) return null
                // Legacy entry point: only the primary-user UID can be resolved here; the app itself
                // resolves clone-space UIDs and writes them via setTargetChannel(RuleTarget).
                val (pkg, userId) = com.multiroute.util.RouteRuleBuilder.parseRuleKey(ruleKey)
                val uid = if (userId == 0) {
                    runCatching { ctx.packageManager.getPackageUid(pkg, 0) }.getOrDefault(-1)
                } else {
                    -1
                }
                setTargetChannel(ctx, RuleTarget(ruleKey, uid), channelId)
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
