package com.multiroute.hook

import org.junit.Assert.assertFalse
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
}
