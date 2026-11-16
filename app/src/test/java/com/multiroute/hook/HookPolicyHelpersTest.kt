package com.multiroute.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HookPolicyHelpersTest {

    @Test
    fun keepAlivePrecedenceFollowsTransientThenPersistentThenRemotePref() {
        assertTrue(
            HookPolicyHelpers.resolveKeepAlivePreference(
                transientProp = "1",
                persistentProp = "0",
                hasRemotePref = true,
                remotePrefValue = false
            )
        )
        assertFalse(
            HookPolicyHelpers.resolveKeepAlivePreference(
                transientProp = "0",
                persistentProp = "1",
                hasRemotePref = true,
                remotePrefValue = true
            )
        )
        assertTrue(
            HookPolicyHelpers.resolveKeepAlivePreference(
                transientProp = null,
                persistentProp = "1",
                hasRemotePref = true,
                remotePrefValue = false
            )
        )
        assertFalse(
            HookPolicyHelpers.resolveKeepAlivePreference(
                transientProp = null,
                persistentProp = "0",
                hasRemotePref = true,
                remotePrefValue = true
            )
        )
        assertTrue(
            HookPolicyHelpers.resolveKeepAlivePreference(
                transientProp = null,
                persistentProp = null,
                hasRemotePref = true,
                remotePrefValue = true
            )
        )
        assertFalse(
            HookPolicyHelpers.resolveKeepAlivePreference(
                transientProp = null,
                persistentProp = null,
                hasRemotePref = false,
                remotePrefValue = true
            )
        )
    }

    @Test
    fun serviceAndBinderMatchersIdentifyConnectivityAndDualWifiTargets() {
        assertTrue(HookPolicyHelpers.isConnectivityServiceName("com.android.server.ConnectivityService"))
        assertTrue(HookPolicyHelpers.isConnectivityServiceName("android.net.connectivity.com.android.server.ConnectivityService"))
        assertFalse(HookPolicyHelpers.isConnectivityServiceName("com.android.server.wifi.WifiService"))

        assertTrue(HookPolicyHelpers.isWifiServiceName("com.android.server.wifi.WifiService"))
        assertTrue(HookPolicyHelpers.isWifiServiceName("com.android.server.wifi.SlaveWifiService"))
        assertTrue(HookPolicyHelpers.isWifiServiceName("DualStaManager"))
        assertFalse(HookPolicyHelpers.isWifiServiceName("com.android.server.ConnectivityService"))

        assertTrue(HookPolicyHelpers.isSlaveWifiBinderName("SlaveWifiService"))
        assertTrue(HookPolicyHelpers.isSlaveWifiBinderName("DualStaService"))
        assertFalse(HookPolicyHelpers.isSlaveWifiBinderName("wifi"))
        assertFalse(HookPolicyHelpers.isSlaveWifiBinderName(null))
    }

    @Test
    fun senderAuthorizationAllowsSystemRootSelfAndAppOnly() {
        val systemServerUid = 1000
        val appUid = 10245

        assertTrue(HookPolicyHelpers.isAuthorizedSenderUid(null, systemServerUid, appUid))
        assertTrue(HookPolicyHelpers.isAuthorizedSenderUid(0, systemServerUid, appUid))
        assertTrue(HookPolicyHelpers.isAuthorizedSenderUid(1000, systemServerUid, appUid))
        assertTrue(HookPolicyHelpers.isAuthorizedSenderUid(appUid, systemServerUid, appUid))

        assertFalse(HookPolicyHelpers.isAuthorizedSenderUid(10199, systemServerUid, appUid))
        assertFalse(HookPolicyHelpers.isAuthorizedSenderUid(10199, systemServerUid, null))
        assertFalse(HookPolicyHelpers.isAuthorizedSenderUid(-1, systemServerUid, -1))
    }

    @Test
    fun wifiAdbTransitionTrackerPreventsDualWifiPingPongAndPreservesDebouncedTransitions() {
        val tracker = HookPolicyHelpers.WifiAdbTransitionTracker<String>(debounceMs = 2000L)

        // While locked, capabilities do not wake and do not consume the SSID transition
        assertNull(
            tracker.onCapabilities("wlan0", "\"Home5G\"", unlocked = false, nowMs = 1_000L) { true }
        )

        // Once unlocked, the first observation of wlan0 -> Home5G wakes
        assertEquals(
            "Home5G",
            tracker.onCapabilities("wlan0", "\"Home5G\"", unlocked = true, nowMs = 1_500L) { true }
        )

        // Secondary Wi-Fi connecting within the 2s debounce window is throttled, not swallowed
        assertNull(
            tracker.onCapabilities("wlan1", "\"Home24G\"", unlocked = true, nowMs = 2_500L) { true }
        )
        assertEquals(
            "Home24G",
            tracker.onCapabilities("wlan1", "\"Home24G\"", unlocked = true, nowMs = 3_600L) { true }
        )

        // Subsequent RSSI updates on both dual-Wi-Fi links never ping-pong
        assertNull(
            tracker.onCapabilities("wlan0", "\"Home5G\"", unlocked = true, nowMs = 7_000L) { true }
        )
        assertNull(
            tracker.onCapabilities("wlan1", "\"Home24G\"", unlocked = true, nowMs = 10_000L) { true }
        )

        // Disconnecting and reconnecting wlan0 wakes again
        tracker.onLost("wlan0")
        assertEquals(
            "Home5G",
            tracker.onCapabilities("wlan0", "\"Home5G\"", unlocked = true, nowMs = 13_000L) { true }
        )
    }
}
