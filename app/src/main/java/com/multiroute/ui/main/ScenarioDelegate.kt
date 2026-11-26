package com.multiroute.ui.main

import android.app.Application
import com.multiroute.R
import com.multiroute.data.RouteConfigProvider
import com.multiroute.data.ScenarioStore
import com.multiroute.model.ScenarioObservation
import com.multiroute.model.ScenarioProfile
import com.multiroute.model.ScenarioTrigger
import com.multiroute.model.ScenarioVpnCondition
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
    private val uiStateFlow: MutableStateFlow<MainUiState>
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
                scenario = it.scenario.copy(showScenarioSaveDialog = true)
            )
        }
    }

    fun closeScenarioSaveDialog() {
        uiStateFlow.update { it.copy(scenario = it.scenario.copy(showScenarioSaveDialog = false)) }
    }

    fun toggleScenarioManually(id: String) {
        ScenarioStore.setMode(application, ScenarioEngine.SelectionMode.MANUAL)
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

    fun setScenarioManualMode(ids: Set<String>) {
        ScenarioStore.setManualIds(application, ids)
        ScenarioStore.setMode(application, ScenarioEngine.SelectionMode.MANUAL)
        refreshScenarioState()
        syncRules()
    }

    fun setScenarioAutomatic() {
        ScenarioStore.setMode(application, ScenarioEngine.SelectionMode.AUTOMATIC)
        refreshScenarioState()
        uiStateFlow.update {
            it.copy(snackBarMessage = application.getString(R.string.scenario_automatic))
        }
        syncRules()
    }

    fun setScenarioDisabled() {
        ScenarioStore.setMode(application, ScenarioEngine.SelectionMode.DISABLED)
        refreshScenarioState()
        uiStateFlow.update {
            it.copy(snackBarMessage = application.getString(R.string.scenario_disabled_toast))
        }
        syncRules()
    }

    fun setRoutingPaused(paused: Boolean) {
        ScenarioStore.setRoutingPaused(application, paused)
        refreshScenarioState()
        uiStateFlow.update {
            it.copy(
                snackBarMessage = application.getString(
                    if (paused) R.string.routing_paused_toast else R.string.routing_resumed_toast
                )
            )
        }
        syncRules()
    }

    fun clearManualScenario() {
        setScenarioAutomatic()
    }

    fun deleteScenario(id: String) {
        ScenarioStore.delete(application, id)
        uiStateFlow.update {
            it.copy(
                scenario = it.scenario.copy(
                    scenarioOverridesId = if (it.scenario.scenarioOverridesId == id) null else it.scenario.scenarioOverridesId
                )
            )
        }
        refreshScenarioState()
        syncRules()
    }

    fun saveCurrentAssignmentsAsScenario(
        name: String,
        trigger: ScenarioTrigger,
        bypassBaseRules: Boolean = false,
        vpnCondition: ScenarioVpnCondition = ScenarioVpnCondition.ANY
    ) {
        val overrides = if (bypassBaseRules) emptyMap() else RouteConfigProvider.getAllRules(application)
        if (name.isBlank() || (!bypassBaseRules && overrides.isEmpty())) {
            val errorRes = if (bypassBaseRules) R.string.scenario_save_failed_name else R.string.scenario_save_failed
            uiStateFlow.update {
                it.copy(
                    scenario = it.scenario.copy(showScenarioSaveDialog = false),
                    snackBarMessage = application.getString(errorRes)
                )
            }
            return
        }
        val effectiveVpn = if (trigger is ScenarioTrigger.Manual) ScenarioVpnCondition.ANY else vpnCondition
        ScenarioStore.upsert(
            application,
            ScenarioProfile(
                id = "s" + System.currentTimeMillis(),
                name = name.trim(),
                trigger = trigger,
                vpnCondition = effectiveVpn,
                bypassBaseRules = bypassBaseRules,
                overrides = overrides
            )
        )
        refreshScenarioState()
        syncRules()
        uiStateFlow.update {
            it.copy(
                scenario = it.scenario.copy(showScenarioSaveDialog = false),
                snackBarMessage = application.getString(R.string.scenario_saved, name.trim())
            )
        }
    }

    fun openScenarioOverrides(id: String) {
        refreshScenarioState()
        uiStateFlow.update {
            it.copy(
                scenario = it.scenario.copy(
                    showScenarioSheet = false,
                    scenarioOverridesId = id
                )
            )
        }
    }

    fun closeScenarioOverrides(reopenSheet: Boolean = true) {
        uiStateFlow.update {
            it.copy(
                scenario = it.scenario.copy(
                    scenarioOverridesId = null,
                    showScenarioSheet = reopenSheet
                )
            )
        }
    }

    fun planOverrideValue(channelId: String): String =
        uiStateFlow.value.channelProbe.channels.firstOrNull { it.id == channelId }
            ?.ssid?.takeIf { it.isNotBlank() }
            ?.let { ScenarioEngine.symbolicSsid(it) }
            ?: channelId

    fun setScenarioOverrides(id: String, ruleKeys: Set<String>, targetValue: String) {
        if (ruleKeys.isEmpty()) return
        val profile = ScenarioStore.load(application).firstOrNull { it.id == id } ?: return
        val overrides = profile.overrides.toMutableMap()
        val stored = planOverrideValue(targetValue)
        ruleKeys.forEach { key -> overrides[key] = stored }
        ScenarioStore.upsert(application, profile.copy(overrides = overrides))
        uiStateFlow.update {
            it.copy(
                snackBarMessage = application.getString(R.string.scenario_override_added, ruleKeys.size, profile.name)
            )
        }
        refreshScenarioState()
        syncRules()
    }

    fun removeScenarioOverride(id: String, ruleKey: String) =
        removeScenarioOverrides(id, setOf(ruleKey))

    fun removeScenarioOverrides(id: String, ruleKeys: Set<String>) {
        if (ruleKeys.isEmpty()) return
        val profile = ScenarioStore.load(application).firstOrNull { it.id == id } ?: return
        val toRemove = ruleKeys.filter { it in profile.overrides }.toSet()
        if (toRemove.isEmpty()) return
        ScenarioStore.upsert(application, profile.copy(overrides = profile.overrides - toRemove))
        val msg = if (toRemove.size > 1) {
            application.getString(R.string.scenario_override_removed_batch, toRemove.size, profile.name)
        } else {
            application.getString(R.string.scenario_override_removed, profile.name)
        }
        uiStateFlow.update { it.copy(snackBarMessage = msg) }
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

    fun reorderScenarios(orderedIds: List<String>) {
        val current = ScenarioStore.load(application)
        val reordered = ScenarioEngine.reorderProfiles(current, orderedIds)
        if (reordered == current) return
        ScenarioStore.save(application, reordered)
        uiStateFlow.update { it.copy(snackBarMessage = application.getString(R.string.scenario_moved)) }
        refreshScenarioState()
        syncRules()
    }

    fun setScenarioEnabled(id: String, enabled: Boolean) {
        val profile = ScenarioStore.load(application).firstOrNull { it.id == id } ?: return
        if (profile.enabled == enabled) return
        ScenarioStore.upsert(application, profile.copy(enabled = enabled))
        uiStateFlow.update {
            it.copy(
                snackBarMessage = application.getString(
                    if (enabled) R.string.scenario_plan_enabled_toast
                    else R.string.scenario_plan_disabled_toast,
                    profile.name
                )
            )
        }
        refreshScenarioState()
        syncRules()
    }

    fun openScenarioDetails(id: String) {
        uiStateFlow.update {
            it.copy(
                scenario = it.scenario.copy(
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

    fun saveScenarioDetails(
        id: String,
        name: String,
        trigger: ScenarioTrigger,
        bypassBaseRules: Boolean = false,
        vpnCondition: ScenarioVpnCondition = ScenarioVpnCondition.ANY
    ) {
        val profile = ScenarioStore.load(application).firstOrNull { it.id == id } ?: return
        if (name.isBlank()) return
        val effectiveVpn = if (trigger is ScenarioTrigger.Manual) ScenarioVpnCondition.ANY else vpnCondition
        ScenarioStore.upsert(
            application,
            profile.copy(
                name = name.trim(),
                trigger = trigger,
                vpnCondition = effectiveVpn,
                bypassBaseRules = bypassBaseRules
            )
        )
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
        val mode = ScenarioStore.getMode(application)
        val routingPaused = ScenarioStore.isRoutingPaused(application)
        val manualIds = ScenarioStore.getManualIds(application)
        val channels = uiStateFlow.value.channelProbe.channels
        val observation = ScenarioObservation.fromChannels(channels)
        val selected = if (routingPaused) {
            emptyList()
        } else {
            ScenarioEngine.selectProfiles(profiles, observation, mode, manualIds)
        }
        val profile = selected.firstOrNull()
        val trigger = profile?.trigger
        val conflicts = mutableMapOf<String, Int>()
        val claimed = mutableMapOf<String, String>()
        selected.forEach { applied ->
            if (applied.bypassBaseRules) {
                claimed.values.forEach { previous ->
                    if (previous != applied.id) conflicts[previous] = (conflicts[previous] ?: 0) + 1
                }
                claimed.clear()
            }
            applied.overrides.keys.forEach { key ->
                claimed[key]?.takeIf { it != applied.id }?.let { previous ->
                    conflicts[previous] = (conflicts[previous] ?: 0) + 1
                }
                claimed[key] = applied.id
            }
        }
        uiStateFlow.update { state ->
            state.copy(
                scenario = state.scenario.copy(
                    scenarioProfiles = profiles,
                    manualScenarioId = manualIds.firstOrNull() ?: "",
                    scenarioMode = mode,
                    routingPaused = routingPaused,
                    manualScenarioIds = manualIds,
                    activeScenarioIds = selected.map { it.id }.toSet(),
                    scenarioConflicts = conflicts,
                    activeScenarioId = profile?.id,
                    activeScenarioName = profile?.name,
                    activeScenarioTrigger = trigger,
                    activeScenarioSsid = trigger?.let { ScenarioEngine.matchedSsid(it, observation) },
                    activeScenarioManual = mode == ScenarioEngine.SelectionMode.MANUAL
                )
            )
        }
    }
}
