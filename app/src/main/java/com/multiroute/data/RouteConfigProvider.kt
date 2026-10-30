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

        /**
         * Whether auto failover is enabled: when an assigned channel disconnects or loses default route,
         * its assigned apps fall back to the system default network immediately.
         */
        const val KEY_AUTO_FAILOVER_ENABLED = "auto_failover_enabled"

        /**
         * Hold-off debounce delay in seconds before restoring rules or switching scenarios when a recovered channel reappears.
         * Default: 3 seconds.
         */
        const val KEY_FAILOVER_HOLD_OFF_SECONDS = "failover_hold_off_seconds"

        /**
         * Whether auto failover verifies real internet connectivity via lightweight HTTP 204 probing.
         * When enabled, interfaces that are connected to LAN but have no Internet access (e.g. captive portals
         * or broken WAN links) trigger failover to keep apps reachable. Default: true.
         */
        const val KEY_FAILOVER_PROBE_INTERNET = "failover_probe_internet"
        const val KEY_DUAL_WIFI_AUTO_RECONNECT = "dual_wifi_auto_reconnect"
        const val KEY_DUAL_WIFI_LAST_SSID = "dual_wifi_last_ssid"
        const val KEY_DUAL_WIFI_LAST_PASS = "dual_wifi_last_pass"
        const val KEY_DUAL_WIFI_LAST_FREQ = "dual_wifi_last_freq"
        const val KEY_DUAL_WIFI_LAST_BSSID = "dual_wifi_last_bssid"
        const val KEY_DUAL_WIFI_LAST_CAPS = "dual_wifi_last_caps"
        const val KEY_DUAL_WIFI_SCHEME = "dual_wifi_scheme"

        // --- Appearance / language. Stored here so the settings survive a reinstall of the rules file
        // --- and so everything the app needs at startup is read from one place.
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_DYNAMIC_COLOR = "dynamic_color"
        const val KEY_LANGUAGE = "language"

        /** `system` (default), `light` or `dark`. */
        fun getThemeMode(context: Context): String {
            val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            return sp.getString(KEY_THEME_MODE, "system") ?: "system"
        }

        fun setThemeMode(context: Context, mode: String) {
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_THEME_MODE, mode).apply()
        }

        /** Material You dynamic palette (Android 12+); on by default. */
        fun isDynamicColor(context: Context): Boolean {
            val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            return sp.getBoolean(KEY_DYNAMIC_COLOR, true)
        }

        fun setDynamicColor(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_DYNAMIC_COLOR, enabled).apply()
        }

        /** BCP-47 tag such as `zh-CN` or `en`, or empty for "follow the system". */
        fun getLanguage(context: Context): String {
            val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            return sp.getString(KEY_LANGUAGE, "") ?: ""
        }

        fun setLanguage(context: Context, tag: String) {
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_LANGUAGE, tag).apply()
        }

        fun isDnsFollowsChannel(context: Context): Boolean {
            val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            return sp.getBoolean(KEY_DNS_FOLLOWS_CHANNEL, true)
        }

        fun setDnsFollowsChannel(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_DNS_FOLLOWS_CHANNEL, enabled).apply()
        }

        fun isAutoFailoverEnabled(context: Context): Boolean {
            val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            return sp.getBoolean(KEY_AUTO_FAILOVER_ENABLED, true)
        }

        fun setAutoFailoverEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_AUTO_FAILOVER_ENABLED, enabled).apply()
        }

        fun getFailoverHoldOffSeconds(context: Context): Int {
            val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            return sp.getInt(KEY_FAILOVER_HOLD_OFF_SECONDS, 3).coerceIn(1, 15)
        }

        fun setFailoverHoldOffSeconds(context: Context, seconds: Int) {
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().putInt(KEY_FAILOVER_HOLD_OFF_SECONDS, seconds.coerceIn(1, 15)).apply()
        }

        fun isFailoverProbeInternet(context: Context): Boolean {
            val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            return sp.getBoolean(KEY_FAILOVER_PROBE_INTERNET, true)
        }

        fun setFailoverProbeInternet(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_FAILOVER_PROBE_INTERNET, enabled).apply()
        }

        fun isDualWifiAutoReconnectEnabled(context: Context): Boolean {
            val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            return sp.getBoolean(KEY_DUAL_WIFI_AUTO_RECONNECT, true)
        }

        fun setDualWifiAutoReconnectEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_DUAL_WIFI_AUTO_RECONNECT, enabled).apply()
        }

        fun saveLastSecondaryWifi(
            context: Context,
            ssid: String,
            pass: String?,
            freq: Int,
            bssid: String?,
            caps: String?
        ) {
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
                .putString(KEY_DUAL_WIFI_LAST_SSID, ssid)
                .putString(KEY_DUAL_WIFI_LAST_PASS, pass ?: "")
                .putInt(KEY_DUAL_WIFI_LAST_FREQ, freq)
                .putString(KEY_DUAL_WIFI_LAST_BSSID, bssid ?: "")
                .putString(KEY_DUAL_WIFI_LAST_CAPS, caps ?: "")
                .apply()
        }

        fun getLastSecondaryWifi(context: Context): Map<String, Any?> {
            val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val ssid = sp.getString(KEY_DUAL_WIFI_LAST_SSID, null) ?: return emptyMap()
            return mapOf(
                "ssid" to ssid,
                "pass" to sp.getString(KEY_DUAL_WIFI_LAST_PASS, ""),
                "freq" to sp.getInt(KEY_DUAL_WIFI_LAST_FREQ, 0),
                "bssid" to sp.getString(KEY_DUAL_WIFI_LAST_BSSID, null),
                "caps" to sp.getString(KEY_DUAL_WIFI_LAST_CAPS, null)
            )
        }

        fun clearLastSecondaryWifi(context: Context) {
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
                .remove(KEY_DUAL_WIFI_LAST_SSID)
                .remove(KEY_DUAL_WIFI_LAST_PASS)
                .remove(KEY_DUAL_WIFI_LAST_FREQ)
                .remove(KEY_DUAL_WIFI_LAST_BSSID)
                .remove(KEY_DUAL_WIFI_LAST_CAPS)
                .apply()
        }

        fun getDualWifiScheme(context: Context): String {
            val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            return sp.getString(KEY_DUAL_WIFI_SCHEME, "auto") ?: "auto"
        }

        fun setDualWifiScheme(context: Context, scheme: String) {
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
                .putString(KEY_DUAL_WIFI_SCHEME, scheme)
                .apply()
        }

        private val SAFE_PKG_REGEX = Regex("^[a-zA-Z0-9_.]+$")
        private val SAFE_CHANNEL_REGEX = Regex("^[a-zA-Z0-9_.]{1,15}$")

        fun isValidPackageName(pkg: String): Boolean {
            return pkg.isNotEmpty() && pkg.length <= 128 && SAFE_PKG_REGEX.matches(pkg)
        }

        fun isValidChannelId(channelId: String): Boolean {
            return channelId == CHANNEL_DEFAULT || com.multiroute.cellular.CellularTargets.isSim(channelId) || SAFE_CHANNEL_REGEX.matches(channelId)
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
            // Settings share this preference file. A setting such as dual_wifi_last_pass is not
            // a package, even though its characters are safe to pass to a shell.
            return isValidPackageName(pkg) && userId in 0..999 &&
                (pkg == "android" || Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+").matches(pkg))
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
                if (isValidRuleKey(key) && value is String && isValidChannelId(value)) {
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
            val editor = sp.edit()
            sp.all.keys.filter(::isStoredRuleKey).forEach(editor::remove)
            // Keep cellular UID ownership until the next sync can remove our platform entries.
            editor.apply()
        }

        internal fun isStoredRuleKey(key: String): Boolean =
            isValidRuleKey(key) || Regex("uid_[0-9]+").matches(key)
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
