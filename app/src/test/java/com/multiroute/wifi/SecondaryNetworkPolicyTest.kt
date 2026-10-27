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
}
