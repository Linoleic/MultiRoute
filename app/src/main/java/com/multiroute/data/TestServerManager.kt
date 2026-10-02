package com.multiroute.data

import android.content.Context
import com.multiroute.model.TestServerConfig

object TestServerManager {
    private const val PREF_NAME = "multiroute_test_server"
    private const val KEY_PRESET_ID = "selected_preset_id"
    private const val KEY_CUSTOM_URL = "custom_url"

    fun getConfig(context: Context): TestServerConfig {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val presetId = sp.getString(KEY_PRESET_ID, TestServerConfig.DEFAULT_PRESET_ID) ?: TestServerConfig.DEFAULT_PRESET_ID
        val customUrl = sp.getString(KEY_CUSTOM_URL, "") ?: ""

        val isCustom = presetId == TestServerConfig.PRESET_CUSTOM
        val activeUrl = if (isCustom && customUrl.isNotBlank()) {
            customUrl.trim()
        } else {
            TestServerConfig.PRESETS.firstOrNull { it.id == presetId }?.url ?: TestServerConfig.DEFAULT_URL
        }

        val activeDisplayName = if (isCustom) {
            "自定义服务器"
        } else {
            TestServerConfig.PRESETS.firstOrNull { it.id == presetId }?.name ?: TestServerConfig.DEFAULT_DISPLAY_NAME
        }

        return TestServerConfig(
            selectedPresetId = presetId,
            customUrl = customUrl,
            activeUrl = activeUrl,
            activeDisplayName = activeDisplayName
        )
    }

    fun saveConfig(context: Context, presetId: String, customUrl: String? = null): TestServerConfig {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val editor = sp.edit()
        editor.putString(KEY_PRESET_ID, presetId)
        if (customUrl != null) {
            editor.putString(KEY_CUSTOM_URL, customUrl.trim())
        }
        editor.apply()
        return getConfig(context)
    }
}
