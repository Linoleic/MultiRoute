package com.multiroute.ui.main

import com.multiroute.model.AppItem
import com.multiroute.model.CHANNEL_DEFAULT
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TopologyAppFilterTest {
    private val systemApp = AppItem("com.example.system", "System", null, targetChannelId = "wlan0", isSystemApp = true)
    private val regularApp = AppItem("com.example.user", "User", null, targetChannelId = "wlan0", isSystemApp = false)

    @Test
    fun onlyAnExplicitGroupOfSystemAppsEnablesTheSystemFilter() {
        assertTrue(shouldShowSystemAppsForTopology("wlan0", listOf(systemApp)))
        assertFalse(shouldShowSystemAppsForTopology("wlan0", listOf(systemApp, regularApp)))
        assertFalse(shouldShowSystemAppsForTopology("wlan0", emptyList()))
        assertFalse(shouldShowSystemAppsForTopology(CHANNEL_DEFAULT, listOf(systemApp)))
        assertFalse(shouldShowSystemAppsForTopology(FILTER_PROXY_EGRESS, listOf(systemApp)))
    }
}
