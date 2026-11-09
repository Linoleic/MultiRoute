package com.multiroute.ui.main

import android.app.Application
import com.multiroute.R
import com.multiroute.data.RouteConfigProvider
import com.multiroute.data.ScenarioStore
import com.multiroute.model.AppItem
import com.multiroute.model.CHANNEL_DEFAULT
import com.multiroute.model.ScenarioObservation
import com.multiroute.model.ScenarioProfile
import com.multiroute.model.ScenarioTrigger
import com.multiroute.util.ScenarioEngine
import com.multiroute.util.SuHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Manages routing scenario profiles, condition evaluation, manual overrides,
 * priority ordering, and per-plan override editing.
 */
internal class ScenarioDelegate(
    private val application: Application,
    private val scope: CoroutineScope,
    private val uiStateFlow: MutableStateFlow<MainUiState>,
    private val transformAllApps: ((AppItem) -> AppItem) -> Unit,
    private val onFilterApps: () -> Unit
) {

    private fun syncRules() {
        scope.launch {
            withContext(Dispatchers.IO) { SuHelper.syncAllRouteRules(application) }
        }
    }

    fun openScenarioSheet() {
        refreshScenarioState()
        uiStateFlow.update { it.copy(scenario = it.scenario.copy(showScenarioSheet = true)) }
    }

    fun closeScenarioSheet() {
        uiStateFlow.update { it.copy(scenario = it.scenario.copy(showScenarioSheet = false)) }
    }

    fun openScenarioSaveDialog() {
        uiStateFlow.update {
            it.copy(
                scenario = it.scenario.copy(showScenarioSheet = false, showScenarioSaveDialog = true)
            )
        }
    }

    fun closeScenarioSaveDialog() {
        uiStateFlow.update { it.copy(scenario = it.scenario.copy(showScenarioSaveDialog = false)) }
    }

    fun toggleScenarioManually(id: String) {
        ScenarioStore.setManualMode(application, true)
        val on = ScenarioStore.toggleManualId(application, id)
        refreshScenarioState()
        uiStateFlow.update {
            it.copy(
                snackBarMessage = application.getString(
                    if (on) R.string.scenario_switched_on
                    else R.string.scenario_switched_off
                )
            )
        }
        syncRules()
    }

    fun setScenarioManualMode() {
        ScenarioStore.setManualMode(application, true)
        refreshScenarioState()
        syncRules()
    }

    fun setScenarioAutomatic() {
        ScenarioStore.setManualMode(application, false)
        refreshScenarioState()
        uiStateFlow.update {
            it.copy(snackBarMessage = application.getString(R.string.scenario_automatic))
        }
        syncRules()
    }

    fun clearManualScenario() {
        setScenarioAutomatic()
    }

    fun deleteScenario(id: String) {
        ScenarioStore.delete(application, id)
        refreshScenarioState()
        syncRules()
    }

    fun saveCurrentAssignmentsAsScenario(name: String, trigger: ScenarioTrigger) {
        val overrides = RouteConfigProvider.getAllRules(application)
        if (name.isBlank() || overrides.isEmpty()) {
            uiStateFlow.update {
                it.copy(
                    scenario = it.scenario.copy(showScenarioSaveDialog = false),
                    snackBarMessage = application.getString(R.string.scenario_save_failed)
                )
            }
            return
        }
        ScenarioStore.upsert(
            application,
            ScenarioProfile(
                id = "s" + System.currentTimeMillis(),
                name = name.trim(),
                trigger = trigger,
                overrides = overrides
            )
        )
        refreshScenarioState()
        uiStateFlow.update {
            it.copy(
                scenario = it.scenario.copy(showScenarioSaveDialog = false),
                snackBarMessage = application.getString(R.string.scenario_saved, name.trim())
            )
        }
    }

    fun startEditingScenario(id: String) {
        val profile = ScenarioStore.load(application).firstOrNull { it.id == id } ?: return
        transformAllApps { app ->
            val override = profile.overrides[app.ruleKey]
            if (override != null) app.copy(targetChannelId = override) else app
        }
        uiStateFlow.update {
            it.copy(
                scenario = it.scenario.copy(showScenarioSheet = false, scenarioEditId = id),
                routing = it.routing.copy(isSelectionMode = true, selectedRuleKeys = profile.overrides.keys),
                snackBarMessage = application.getString(R.string.scenario_editing, profile.name)
            )
        }
        onFilterApps()
    }

    fun finishEditingScenario() {
        val base = RouteConfigProvider.getAllRules(application)
        transformAllApps { app ->
            app.copy(targetChannelId = base[app.ruleKey] ?: CHANNEL_DEFAULT)
        }
        uiStateFlow.update {
            it.copy(
                scenario = it.scenario.copy(scenarioEditId = null),
                routing = it.routing.copy(
                    isSelectionMode = false,
                    selectedRuleKeys = emptySet(),
                    showBatchAssignSheet = false
                )
            )
        }
        onFilterApps()
        refreshScenarioState()
    }

    fun planOverrideValue(channelId: String): String =
        uiStateFlow.value.channelProbe.channels.firstOrNull { it.id == channelId }
            ?.ssid?.takeIf { it.isNotBlank() }
            ?.let { ScenarioEngine.symbolicSsid(it) }
            ?: channelId

    fun addOverridesToScenario(id: String, ruleKeys: Set<String>, channelId: String) {
        val profile = ScenarioStore.load(application).firstOrNull { it.id == id } ?: return
        val overrides = profile.overrides.toMutableMap()
        val stored = planOverrideValue(channelId)
        ruleKeys.forEach { key -> overrides[key] = stored }
        ScenarioStore.upsert(application, profile.copy(overrides = overrides))
        transformAllApps { if (it.ruleKey in ruleKeys) it.copy(targetChannelId = channelId) else it }
        uiStateFlow.update {
            it.copy(
                routing = it.routing.copy(showBatchAssignSheet = false),
                snackBarMessage = application.getString(R.string.scenario_override_added, ruleKeys.size, profile.name)
            )
        }
        onFilterApps()
        refreshScenarioState()
        syncRules()
    }

    fun moveScenario(id: String, delta: Int) {
        val list = ScenarioStore.load(application).toMutableList()
        val from = list.indexOfFirst { it.id == id }
        if (from < 0) return
        val to = (from + delta).coerceIn(0, list.size - 1)
        if (to == from) return
        list.add(to, list.removeAt(from))
        ScenarioStore.save(
            application,
            list.mapIndexed { index, profile -> profile.copy(priority = (index + 1) * 10) }
        )
        uiStateFlow.update { it.copy(snackBarMessage = application.getString(R.string.scenario_moved)) }
        refreshScenarioState()
        syncRules()
    }

    fun openScenarioDetails(id: String) {
        uiStateFlow.update {
            it.copy(
                scenario = it.scenario.copy(
                    showScenarioSheet = false,
                    showScenarioDetailsDialog = true,
                    scenarioDetailsId = id
                )
            )
        }
    }

    fun closeScenarioDetails() {
        uiStateFlow.update {
            it.copy(
                scenario = it.scenario.copy(
                    showScenarioDetailsDialog = false,
                    scenarioDetailsId = null
                )
            )
        }
    }

    fun saveScenarioDetails(id: String, name: String, trigger: ScenarioTrigger) {
        val profile = ScenarioStore.load(application).firstOrNull { it.id == id } ?: return
        if (name.isBlank()) return
        ScenarioStore.upsert(application, profile.copy(name = name.trim(), trigger = trigger))
        uiStateFlow.update {
            it.copy(
                scenario = it.scenario.copy(showScenarioDetailsDialog = false, scenarioDetailsId = null),
                snackBarMessage = application.getString(R.string.scenario_updated, name.trim())
            )
        }
        refreshScenarioState()
        syncRules()
    }

    fun refreshScenarioState() {
        val profiles = ScenarioStore.load(application)
        val manualMode = ScenarioStore.isManualMode(application)
        val manualIds = ScenarioStore.getManualIds(application)
        val channels = uiStateFlow.value.channelProbe.channels
        val observation = ScenarioObservation.fromChannels(channels)
        val selected = if (manualMode) {
            ScenarioEngine.selectProfiles(profiles, observation, ScenarioEngine.SelectionMode.MANUAL, manualIds)
        } else {
            ScenarioEngine.selectProfiles(profiles, observation, ScenarioEngine.SelectionMode.AUTOMATIC)
        }
        val profile = selected.firstOrNull()
        val trigger = profile?.trigger
        val conflicts = mutableMapOf<String, Int>()
        val claimed = mutableMapOf<String, String>()
        selected.forEach { applied ->
            applied.overrides.keys.forEach { key ->
                claimed[key]?.let { previous -> conflicts[previous] = (conflicts[previous] ?: 0) + 1 }
                claimed[key] = applied.id
            }
        }
        uiStateFlow.update { state ->
            state.copy(
                scenario = state.scenario.copy(
                    scenarioProfiles = profiles,
                    manualScenarioId = manualIds.firstOrNull() ?: "",
                    scenarioManualMode = manualMode,
                    manualScenarioIds = manualIds,
                    activeScenarioIds = selected.map { it.id }.toSet(),
                    scenarioConflicts = conflicts,
                    activeScenarioId = profile?.id,
                    activeScenarioName = profile?.name,
                    activeScenarioTrigger = trigger,
                    activeScenarioSsid = trigger?.let { ScenarioEngine.matchedSsid(it, observation) },
                    activeScenarioManual = manualMode
                )
            )
        }
    }
}
