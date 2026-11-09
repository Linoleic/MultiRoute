package com.multiroute.wifi

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.test.runTest

class SecondaryNetworkPolicyTest {
    @Test fun savedEncryptedNetworkConnectsWithoutAnExtractedPassword() = runTest {
        assertEquals(SecondaryNetworkPolicy.Entry.CONNECT,
            SecondaryNetworkPolicy.entry(false, true, false) { 7 })
    }

    @Test fun savedOpenNetworkWithIdZeroConnectsFromTheEntry() = runTest {
        assertEquals(SecondaryNetworkPolicy.Entry.CONNECT,
            SecondaryNetworkPolicy.entry(false, false, false) { 0 })
    }

    @Test fun aospUnsavedNetworksGoToSettingsEvenWithACachedPassword() = runTest {
        assertEquals(SecondaryNetworkPolicy.Entry.SYSTEM_SETTINGS,
            SecondaryNetworkPolicy.entry(false, true, true) { null })
        assertEquals(SecondaryNetworkPolicy.Entry.SYSTEM_SETTINGS,
            SecondaryNetworkPolicy.entry(false, false, false) { null })
    }

    @Test fun xiaomiSavedNetworkDoesNotAskForAnExtractedPassword() = runTest {
        assertEquals(SecondaryNetworkPolicy.Entry.CONNECT,
            SecondaryNetworkPolicy.entry(true, true, false) { 4 })
    }

    @Test fun xiaomiProvisioningRetainsThePasswordEntryForNewEncryptedNetworks() = runTest {
        assertEquals(SecondaryNetworkPolicy.Entry.PASSWORD,
            SecondaryNetworkPolicy.entry(true, true, false) { null })
        assertEquals(SecondaryNetworkPolicy.Entry.CONNECT,
            SecondaryNetworkPolicy.entry(true, true, true) { null })
        assertEquals(SecondaryNetworkPolicy.Entry.CONNECT,
            SecondaryNetworkPolicy.entry(true, false, false) { null })
    }

    @Test fun savedOpenNetworkWithIdZeroDoesNotRequireAPassword() = runTest {
        var lookups = 0
        assertTrue(SecondaryNetworkPolicy.canConnect(false) { lookups++; 0 })
        assertEquals(1, lookups)
    }

    @Test fun networkMissingFromSystemConfigurationsIsRejected() = runTest {
        assertFalse(SecondaryNetworkPolicy.canConnect(false) { null })
        assertFalse(SecondaryNetworkPolicy.canConnect(false) { -1 })
    }

    @Test fun xiaomiProvisioningDoesNotRequireASavedConfigurationLookup() = runTest {
        assertTrue(SecondaryNetworkPolicy.canConnect(true) { error("Vendor provisioning must not depend on the saved list") })
    }

    @Test fun dualWifiIsDisabledOnlyWhenExplicitlyDisabledAndInactive() {
        val inactive = DualWifiStatus(
            capability = DualWifiCapability(modeActive = false),
            secondaryConnected = false,
            isConnecting = false
        )
        assertFalse(inactive.isDualWifiActive)
        assertFalse(SecondaryNetworkPolicy.isDualWifiEnabled(isExplicitlyDisabled = true, status = inactive))
        assertTrue(SecondaryNetworkPolicy.isDualWifiEnabled(isExplicitlyDisabled = false, status = inactive))
    }

    @Test fun dualWifiRemainsEnabledForSelectionWhenLiveStateIsActiveDespiteStaleDisablePref() {
        val viaModeActive = DualWifiStatus(capability = DualWifiCapability(modeActive = true))
        val viaConnectedSecondary = DualWifiStatus(secondaryConnected = true, secondarySsid = "R3G")
        val viaConnecting = DualWifiStatus(isConnecting = true, connectingSsid = "merciful")

        assertTrue(viaModeActive.isDualWifiActive)
        assertTrue(viaConnectedSecondary.isDualWifiActive)
        assertTrue(viaConnecting.isDualWifiActive)

        assertTrue(SecondaryNetworkPolicy.isDualWifiEnabled(isExplicitlyDisabled = true, status = viaModeActive))
        assertTrue(SecondaryNetworkPolicy.isDualWifiEnabled(isExplicitlyDisabled = true, status = viaConnectedSecondary))
        assertTrue(SecondaryNetworkPolicy.isDualWifiEnabled(isExplicitlyDisabled = true, status = viaConnecting))
    }
}
