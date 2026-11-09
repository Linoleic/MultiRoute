package com.multiroute.util

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * Coordinates in-app language changes across both Activity and Application contexts.
 */
object AppLocaleHelper {

    fun syncDefaultLocale(tag: String) {
        Locale.setDefault(resolveLocale(tag))
    }

    fun applyLanguage(context: Context, tag: String) {
        syncDefaultLocale(tag)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                val manager = context.getSystemService(LocaleManager::class.java) ?: return
                val expected =
                    if (tag.isEmpty()) LocaleList.getEmptyLocaleList()
                    else LocaleList.forLanguageTags(tag)
                if (manager.applicationLocales != expected) {
                    manager.applicationLocales = expected
                }
            }
            return
        }

        // Pre-API 33 lacks LocaleManager: keep application resources synchronized in-process.
        val appContext = context.applicationContext ?: context
        val appResources = appContext.resources
        val config = Configuration(appResources.configuration).apply {
            setLocales(LocaleList(resolveLocale(tag)))
        }
        @Suppress("DEPRECATION")
        appResources.updateConfiguration(config, appResources.displayMetrics)
    }

    fun legacyOverrideConfiguration(newBase: Context, tag: String): Configuration? {
        syncDefaultLocale(tag)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU || tag.isEmpty()) {
            return null
        }
        return Configuration(newBase.resources.configuration).apply {
            setLocales(LocaleList(Locale.forLanguageTag(tag)))
        }
    }

    private fun resolveLocale(tag: String): Locale {
        if (tag.isNotEmpty()) {
            return Locale.forLanguageTag(tag)
        }
        val systemLocales = Resources.getSystem().configuration.locales
        return if (!systemLocales.isEmpty) systemLocales[0] else Locale.getDefault()
    }
}
