package com.multiroute.ui.main

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import com.multiroute.R
import com.multiroute.data.ConfigTransfer
import com.multiroute.theme.ThemeSettings
import com.multiroute.util.SuHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Manages full-configuration JSON import, preview validation, clipboard/SAF transfer,
 * backup writing, and atomic restoration.
 */
internal class ConfigTransferDelegate(
    private val application: Application,
    private val scope: CoroutineScope,
    private val uiStateFlow: MutableStateFlow<MainUiState>,
    private val onReloadAfterImport: () -> Unit
) {

    private fun clipboard(): ClipboardManager? =
        application.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    fun exportConfigToFile() {
        scope.launch {
            uiStateFlow.update { it.copy(config = it.config.copy(isConfigBusy = true)) }
            val now = System.currentTimeMillis()
            val json = withContext(Dispatchers.IO) {
                ConfigTransfer.exportJson(application, now)
            }
            val path = ConfigTransfer.exportPath(now)
            val ok = withContext(Dispatchers.IO) {
                SuHelper.writeTextAsRoot(application, path, json) &&
                    SuHelper.writeTextAsRoot(
                        application,
                        "${ConfigTransfer.EXPORT_DIR}/MultiRoute-config.json",
                        json
                    )
            }
            uiStateFlow.update {
                it.copy(
                    config = it.config.copy(isConfigBusy = false, configPath = path),
                    snackBarMessage = application.getString(
                        if (ok) R.string.config_exported else R.string.config_export_failed,
                        path
                    )
                )
            }
        }
    }

    fun exportConfigToClipboard() {
        scope.launch {
            val json = withContext(Dispatchers.IO) {
                ConfigTransfer.exportJson(application, System.currentTimeMillis())
            }
            clipboard()?.setPrimaryClip(ClipData.newPlainText("MultiRoute config", json))
            uiStateFlow.update {
                it.copy(snackBarMessage = application.getString(R.string.config_copied))
            }
        }
    }

    fun requestImportFromClipboard() {
        val text = clipboard()?.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(application)
            ?.toString()
            .orEmpty()
        stageImport(text)
    }

    fun requestImportFromFile(path: String) {
        scope.launch {
            uiStateFlow.update {
                it.copy(config = it.config.copy(isConfigBusy = true, configPath = path))
            }
            val text = withContext(Dispatchers.IO) { SuHelper.readTextAsRoot(path) }
            uiStateFlow.update { it.copy(config = it.config.copy(isConfigBusy = false)) }
            if (text.isNullOrBlank()) {
                uiStateFlow.update {
                    it.copy(snackBarMessage = application.getString(R.string.config_read_failed, path))
                }
            } else {
                stageImport(text)
            }
        }
    }

    fun importConfigFromUri(uri: Uri) {
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    application.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }.getOrNull()
            }
            if (text.isNullOrBlank()) {
                uiStateFlow.update {
                    it.copy(snackBarMessage = application.getString(R.string.config_read_failed, uri.toString()))
                }
            } else {
                stageImport(text)
            }
        }
    }

    private fun stageImport(json: String) {
        if (uiStateFlow.value.config.isConfigBusy) return
        scope.launch {
            uiStateFlow.update {
                it.copy(
                    config = it.config.copy(
                        isConfigBusy = true,
                        showConfigImportConfirm = false,
                        pendingImportJson = null,
                        configImportPreview = null,
                        configImportSections = emptySet()
                    )
                )
            }
            val preview = try {
                withContext(Dispatchers.IO) { ConfigTransfer.preview(application, json) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                null
            }
            uiStateFlow.update {
                it.copy(
                    config = it.config.copy(
                        isConfigBusy = false,
                        pendingImportJson = if (preview != null) json else null,
                        configImportPreview = preview,
                        configImportSections = preview?.document?.sections.orEmpty(),
                        showConfigImportConfirm = preview != null
                    ),
                    snackBarMessage = if (preview == null) application.getString(R.string.config_invalid) else null
                )
            }
        }
    }

    fun toggleConfigImportSection(section: ConfigTransfer.Section) {
        uiStateFlow.update { state ->
            val current = state.config.configImportSections
            state.copy(
                config = state.config.copy(
                    configImportSections = if (section in current) current - section else current + section
                )
            )
        }
    }

    fun consumeConfigAppearanceImport() {
        uiStateFlow.update { it.copy(config = it.config.copy(configAppearanceImported = false)) }
    }

    fun cancelConfigImport() {
        uiStateFlow.update {
            it.copy(
                config = it.config.copy(
                    pendingImportJson = null,
                    configImportPreview = null,
                    configImportSections = emptySet(),
                    showConfigImportConfirm = false
                )
            )
        }
    }

    fun confirmConfigImport() {
        val cfg = uiStateFlow.value.config
        if (cfg.isConfigBusy || cfg.configImportSections.isEmpty()) return
        val json = cfg.pendingImportJson ?: return
        val sections = cfg.configImportSections
        scope.launch {
            uiStateFlow.update {
                it.copy(config = it.config.copy(isConfigBusy = true, pendingImportJson = null))
            }
            val result = try {
                withContext(Dispatchers.IO) { ConfigTransfer.apply(application, json, System.currentTimeMillis(), sections) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                uiStateFlow.update {
                    it.copy(
                        config = it.config.copy(isConfigBusy = false, showConfigImportConfirm = false),
                        snackBarMessage = application.getString(R.string.config_import_failed)
                    )
                }
                return@launch
            }
            val synced = result != null && try {
                withContext(Dispatchers.IO) { SuHelper.syncAllRouteRules(application) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                false
            }
            if (result != null) {
                onReloadAfterImport()
                ThemeSettings.load(application)
            }
            uiStateFlow.update { state ->
                if (result == null) {
                    state.copy(
                        config = state.config.copy(isConfigBusy = false, showConfigImportConfirm = false),
                        snackBarMessage = application.getString(R.string.config_invalid)
                    )
                } else {
                    state.copy(
                        config = state.config.copy(
                            isConfigBusy = false,
                            showConfigImportConfirm = false,
                            configAppearanceImported = ConfigTransfer.Section.APPEARANCE in sections
                        ),
                        snackBarMessage = application.getString(
                            R.string.config_imported,
                            result.assignments,
                            result.scenarios,
                            result.skipped
                        ) + "\n" + application.getString(R.string.config_backup_saved, result.backupPath) +
                            (if (!synced) "\n" + application.getString(R.string.config_sync_failed) else "") +
                            (if (result.unavailableChannels.isNotEmpty()) "\n" + application.getString(R.string.config_offline_channels, result.unavailableChannels.joinToString()) else "")
                    )
                }
            }
        }
    }
}
