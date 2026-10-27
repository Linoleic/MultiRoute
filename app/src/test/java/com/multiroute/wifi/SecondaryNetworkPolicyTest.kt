package com.multiroute.wifi

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.test.runTest

class SecondaryNetworkPolicyTest {
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
