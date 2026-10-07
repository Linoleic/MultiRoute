package com.multiroute.util

import com.multiroute.model.CHANNEL_DEFAULT
import com.multiroute.model.NetworkChannel
import com.multiroute.model.ScenarioObservation
import com.multiroute.model.ScenarioProfile
import com.multiroute.model.ScenarioTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The interesting rules of scenario profiles: priority, first match, manual pinning, forced-default
 * overrides and the fallback to the base assignments.
 */
class ScenarioEngineTest {

    private fun channel(iface: String, transport: String, ssid: String? = null) = NetworkChannel(
        id = iface,
        interfaceName = iface,
        transportType = transport,
        shortName = iface,
        ssid = ssid,
        ipAddress = "10.0.0.2"
    )

    private val wifi5 = channel("wlan0", "WLAN", "H3C_CA202C")
    private val wifi24 = channel("wlan1", "WLAN", "Hpkt")
    private val cellular = channel("rmnet_data3", "cellular")

    @Test
    fun testObservationFromChannels() {
        val twoWifi = ScenarioObservation.fromChannels(listOf(wifi5, wifi24, cellular))
        assertEquals(2, twoWifi.wifiLinkCount)
        assertEquals(listOf("H3C_CA202C", "Hpkt"), twoWifi.wifiSsids)
        assertTrue(twoWifi.hasCellular)

        // Cellular only: no Wi-Fi link at all, and a channel without an SSID must not be counted as one.
        val onlyCellular = ScenarioObservation.fromChannels(listOf(cellular))
        assertEquals(0, onlyCellular.wifiLinkCount)
        assertTrue(onlyCellular.wifiSsids.isEmpty())
        assertTrue(onlyCellular.hasCellular)

        val unknownSsid = ScenarioObservation.fromChannels(listOf(channel("wlan0", "WLAN", null)))
        assertEquals(1, unknownSsid.wifiLinkCount)
        assertTrue(unknownSsid.wifiSsids.isEmpty())
    }

    @Test
    fun testSsidMatchIgnoresCase() {
        val trigger = ScenarioTrigger.SsidMatch(listOf("hpkt", "OTHER"))
        assertTrue(ScenarioEngine.matches(trigger, ScenarioObservation(wifiSsids = listOf("Hpkt"))))
        assertFalse(ScenarioEngine.matches(trigger, ScenarioObservation(wifiSsids = listOf("H3C_CA202C"))))
        assertEquals(
            "Hpkt",
            ScenarioEngine.matchedSsid(trigger, ScenarioObservation(wifiSsids = listOf("Hpkt")))
        )
    }

    @Test
    fun testLinkCountAndCellularOnly() {
        val atLeastTwo = ScenarioTrigger.WifiLinkCount(min = 2)
        assertFalse(ScenarioEngine.matches(atLeastTwo, ScenarioObservation(wifiLinkCount = 1)))
        assertTrue(ScenarioEngine.matches(atLeastTwo, ScenarioObservation(wifiLinkCount = 2)))

        val exactlyOne = ScenarioTrigger.WifiLinkCount(min = 1, max = 1)
        assertTrue(ScenarioEngine.matches(exactlyOne, ScenarioObservation(wifiLinkCount = 1)))
        assertFalse(ScenarioEngine.matches(exactlyOne, ScenarioObservation(wifiLinkCount = 2)))

        assertTrue(ScenarioEngine.matches(ScenarioTrigger.CellularOnly, ScenarioObservation(wifiLinkCount = 0)))
        assertFalse(ScenarioEngine.matches(ScenarioTrigger.CellularOnly, ScenarioObservation(wifiLinkCount = 1)))
    }

    @Test
    fun testLowestPriorityWins() {
        val low = ScenarioProfile("b", "B", priority = 20, trigger = ScenarioTrigger.Always)
        val high = ScenarioProfile("c", "C", priority = 50, trigger = ScenarioTrigger.Always)
        val (chosen, _) = ScenarioEngine.selectProfile(listOf(high, low), ScenarioObservation())
        assertEquals("b", chosen?.id)
    }

    @Test
    fun testDisabledProfilesAreSkipped() {
        val disabled = ScenarioProfile(
            "a", "A", enabled = false, priority = 1, trigger = ScenarioTrigger.Always
        )
        val enabled = ScenarioProfile("b", "B", priority = 9, trigger = ScenarioTrigger.Always)
        val (chosen, _) = ScenarioEngine.selectProfile(listOf(disabled, enabled), ScenarioObservation())
        assertEquals("b", chosen?.id)

        val (none, _) = ScenarioEngine.selectProfile(listOf(disabled), ScenarioObservation())
        assertNull(none)
    }

    @Test
    fun testManualPinningWinsAndDisabledPinIsIgnored() {
        val automatic = ScenarioProfile(
            "auto", "Auto", priority = 1,
            trigger = ScenarioTrigger.SsidMatch(listOf("Hpkt")), overrides = mapOf("a" to "wlan1")
        )
        val pinned = ScenarioProfile(
            "pin", "Pinned", priority = 99,
            trigger = ScenarioTrigger.Manual, overrides = mapOf("a" to "wlan2")
        )
        val observation = ScenarioObservation(wifiSsids = listOf("Hpkt"), wifiLinkCount = 1)

        val (chosen, _) = ScenarioEngine.selectProfile(listOf(automatic, pinned), observation, manualId = "pin")
        assertEquals("pin", chosen?.id)

        val resolution = ScenarioEngine.resolve(
            base = mapOf(1 to "wlan0"),
            profiles = listOf(automatic, pinned),
            observation = observation,
            manualId = "pin",
            overridesByUid = { profile -> if (profile.id == "pin") mapOf(1 to "wlan2") else mapOf(1 to "wlan1") }
        )
        assertEquals("wlan2", resolution.effectiveRules[1])
        assertTrue(resolution.appliedManually)

        // A pin that no longer exists (or was disabled) falls back to automatic selection.
        val (fallback, _) = ScenarioEngine.selectProfile(listOf(automatic, pinned), observation, manualId = "gone")
        assertEquals("auto", fallback?.id)
        val disabledPin = pinned.copy(enabled = false)
        val (afterDisable, _) = ScenarioEngine.selectProfile(
            listOf(automatic, disabledPin), observation, manualId = "pin"
        )
        assertEquals("auto", afterDisable?.id)
    }

    @Test
    fun testOverridesCanAlsoRemoveAnAppFromRouting() {
        val base = mapOf(1001 to "wlan0", 1002 to "wlan1")
        val (effective, forced) = ScenarioEngine.applyOverrides(
            base,
            mapOf(1001 to CHANNEL_DEFAULT, 1002 to "rmnet_data3", 1003 to "wlan1")
        )
        assertFalse(effective.containsKey(1001))
        assertEquals("rmnet_data3", effective[1002])
        assertEquals("wlan1", effective[1003])
        assertEquals(1, forced)
        // Removing a UID that was not routed in the first place is not counted as a forced default.
        val (_, noneForced) = ScenarioEngine.applyOverrides(base, mapOf(9999 to CHANNEL_DEFAULT))
        assertEquals(0, noneForced)

        val (untouched, _) = ScenarioEngine.applyOverrides(base, emptyMap())
        assertEquals(base, untouched)
    }

    @Test
    fun testNoMatchKeepsTheBaseAssignments() {
        val profile = ScenarioProfile(
            "p", "P", trigger = ScenarioTrigger.SsidMatch(listOf("Hpkt")), overrides = mapOf("a" to "wlan1")
        )
        val base = mapOf(1001 to "wlan0")
        val resolution = ScenarioEngine.resolve(
            base = base,
            profiles = listOf(profile),
            observation = ScenarioObservation(wifiSsids = listOf("SomewhereElse")),
            overridesByUid = { mapOf(1001 to "wlan1") }
        )
        assertNull(resolution.activeId)
        assertEquals(base, resolution.effectiveRules)
        assertEquals(0, resolution.overridden)
    }

    @Test
    fun testMatchingPlansLayerInsteadOfReplacingEachOther() {
        // Dual Wi-Fi: one plan per link, both connected at the same time.
        val planA = ScenarioProfile(
            "a", "A", priority = 10,
            trigger = ScenarioTrigger.SsidMatch(listOf("Hpkt")), overrides = mapOf("x" to "wlan0")
        )
        val planB = ScenarioProfile(
            "b", "B", priority = 20,
            trigger = ScenarioTrigger.SsidMatch(listOf("H3C_CA202C")), overrides = mapOf("y" to "wlan1")
        )
        val resolution = ScenarioEngine.resolve(
            base = mapOf(1 to "wlan0"),
            profiles = listOf(planB, planA),
            observation = ScenarioObservation(
                wifiSsids = listOf("Hpkt", "H3C_CA202C"), wifiLinkCount = 2
            ),
            overridesByUid = { profile ->
                when (profile.id) {
                    "a" -> mapOf(1 to "wlan0", 2 to "wlan0")
                    "b" -> mapOf(2 to "wlan1")
                    else -> emptyMap()
                }
            }
        )
        assertEquals(listOf("A", "B"), resolution.activeNames)
        assertEquals("A", resolution.activeName)
        assertEquals("wlan0", resolution.effectiveRules[1])
        assertEquals("wlan1", resolution.effectiveRules[2])
    }

    @Test
    fun testLaterPlanWinsOnConflict() {
        val low = ScenarioProfile("a", "A", priority = 10, trigger = ScenarioTrigger.Always)
        val high = ScenarioProfile("b", "B", priority = 20, trigger = ScenarioTrigger.Always)
        val resolution = ScenarioEngine.resolve(
            base = mapOf(1 to "wlan0"),
            profiles = listOf(low, high),
            observation = ScenarioObservation(),
            overridesByUid = { profile -> if (profile.id == "a") mapOf(1 to "wlan1") else mapOf(1 to "wlan2") }
        )
        assertEquals("wlan2", resolution.effectiveRules[1])
        assertEquals(listOf("A", "B"), resolution.activeNames)
    }

    @Test
    fun testManualPinStillAppliesOnlyThatPlan() {
        val planA = ScenarioProfile(
            "a", "A", priority = 10,
            trigger = ScenarioTrigger.SsidMatch(listOf("Hpkt")), overrides = mapOf("x" to "wlan0")
        )
        val planB = ScenarioProfile(
            "b", "B", priority = 20,
            trigger = ScenarioTrigger.SsidMatch(listOf("H3C_CA202C")), overrides = mapOf("y" to "wlan1")
        )
        val resolution = ScenarioEngine.resolve(
            base = mapOf(1 to "wlan0"),
            profiles = listOf(planA, planB),
            observation = ScenarioObservation(wifiSsids = listOf("Hpkt", "H3C_CA202C"), wifiLinkCount = 2),
            manualId = "b",
            overridesByUid = { profile ->
                if (profile.id == "a") mapOf(1 to "wlan0", 2 to "wlan0") else mapOf(2 to "wlan1")
            }
        )
        assertEquals(listOf("B"), resolution.activeNames)
        assertTrue(resolution.appliedManually)
        assertEquals("wlan0", resolution.effectiveRules[1])
        assertEquals("wlan1", resolution.effectiveRules[2])
    }

    @Test
    fun testOverridesToMissingChannelsAreDropped() {
        val profile = ScenarioProfile(
            "p", "P", trigger = ScenarioTrigger.Always,
            overrides = mapOf("a" to "wlan1", "b" to "wlan9")
        )
        val resolution = ScenarioEngine.resolve(
            base = mapOf(1001 to "wlan0", 1002 to "wlan0"),
            profiles = listOf(profile),
            observation = ScenarioObservation(),
            overridesByUid = { mapOf(1001 to "wlan1", 1002 to "wlan9") },
            hasChannel = { it == "wlan1" }
        )
        assertEquals("wlan1", resolution.effectiveRules[1001])
        // wlan9 no longer exists, so the base assignment survives rather than pointing at a dead link.
        assertEquals("wlan0", resolution.effectiveRules[1002])
        assertEquals(1, resolution.overridden)
    }
}
