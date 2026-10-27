package com.multiroute.util

import org.junit.Assert.*
import org.junit.Test

class HotspotUpstreamPolicyTest {
    private val ims = HotspotCandidate(1, "rmnet_data1", cellular = true, validated = true)
    private val internet = HotspotCandidate(2, "rmnet_data2", cellular = true, internet = true)

    @Test fun imsNeverWinsOverInternetRegardlessOfMapOrder() {
        listOf(listOf(ims, internet), listOf(internet, ims)).forEach {
            assertEquals(internet, HotspotUpstreamPolicy.select("cellular", it))
        }
        assertNull(HotspotUpstreamPolicy.select("cellular", listOf(ims)))
    }

    @Test fun interfacePrefixDoesNotReplaceTransportAndInternetCapabilities() {
        val fake = internet.copy(cellular = false)
        assertNull(HotspotUpstreamPolicy.select("cellular", listOf(fake)))
        assertNull(HotspotUpstreamPolicy.select("rmnet_data2", listOf(fake)))
    }

    @Test fun explicitCellularInterfaceIsAnExactMatch() {
        assertNull(HotspotUpstreamPolicy.select("rmnet_data3", listOf(internet)))
        assertEquals(internet, HotspotUpstreamPolicy.select("rmnet_data2", listOf(internet)))
    }

    @Test fun dunRequirementDoesNotAccidentallyDemandInternet() {
        val dun = ims.copy(id = 3, iface = "rmnet_data3", dun = true)
        assertEquals(dun, HotspotUpstreamPolicy.select("cellular", listOf(internet, dun), dunRequired = true))
        assertNull(HotspotUpstreamPolicy.select("cellular", listOf(internet), dunRequired = true))
    }

    @Test fun cellularPermissionAppliesToTypeAndExactInterfaceTargets() {
        listOf("cellular", "rmnet_data2").forEach {
            assertNull(HotspotUpstreamPolicy.select(it, listOf(internet), cellularPermitted = false))
        }
    }

    @Test fun partialNetworkWaitsForAddressesAndRoutes() {
        listOf(internet.copy(iface = ""), internet.copy(hasAddress = false),
            internet.copy(hasDefaultRoute = false)).forEach {
            assertNull(HotspotUpstreamPolicy.select("cellular", listOf(it)))
        }
        // VALIDATED is a preference, not a requirement that could prevent initial selection.
        assertEquals(internet, HotspotUpstreamPolicy.select("cellular", listOf(internet)))
    }

    @Test fun stableCandidateThenDefaultDataSubscriptionThenValidation() {
        val other = internet.copy(id = 5, iface = "rmnet_data5", validated = true, subscriptions = setOf(2))
        assertEquals(other, HotspotUpstreamPolicy.select("cellular", listOf(internet, other), preferredSubscription = 2))
        assertEquals(internet, HotspotUpstreamPolicy.select("cellular", listOf(other, internet), currentId = 2, preferredSubscription = 2))
        assertEquals(other, HotspotUpstreamPolicy.select("cellular", listOf(internet, other)))
        assertEquals(internet, HotspotUpstreamPolicy.select("cellular", listOf(other.copy(validated = false), internet)))
    }

    @Test fun targetAppearsDisappearsAndReturnsWithoutChangingSetting() {
        val target = HotspotCandidate(20, "testtun99")
        assertNull(HotspotUpstreamPolicy.select("testtun99", emptyList()))
        assertEquals(target, HotspotUpstreamPolicy.select("testtun99", listOf(target)))
        assertNull(HotspotUpstreamPolicy.select("testtun99", emptyList(), currentId = 20))
        val restored = target.copy(id = 21)
        assertEquals(restored, HotspotUpstreamPolicy.select("testtun99", listOf(restored), currentId = 20))
    }

    @Test fun aliasesAndDefaultDoNotChangeInterfaceSemantics() {
        assertEquals("wlan1", HotspotUpstreamPolicy.normalize(" slave_wifi "))
        assertEquals("wlan0", HotspotUpstreamPolicy.normalize("primary_wifi"))
        assertEquals(internet, HotspotUpstreamPolicy.select("mobile", listOf(internet)))
        assertNull(HotspotUpstreamPolicy.select("default", listOf(internet)))
    }

    @Test fun presetDoesNotKeepCellularUpUntilSharingStarts() {
        val policy = HotspotRequestPolicy(true, false, false)
        policy.target = "cellular"
        assertEquals(Triple(true, false, false), policy.effective())
        policy.observing = true
        assertEquals(Triple(false, false, true), policy.effective())
        policy.target = "default"
        assertEquals(Triple(true, false, false), policy.effective())
    }

    @Test fun stopReleasesOverrideEvenWithPersistentCellularTarget() {
        val policy = HotspotRequestPolicy(true, false, false)
        policy.target = "cellular"
        policy.observing = true
        policy.observing = false
        policy.onNativeTryCell(false)
        assertEquals(Triple(true, false, false), policy.effective())
    }

    @Test fun restoresLatestNativeConfigAndDoesNotReleaseNativeOwnedRequest() {
        val policy = HotspotRequestPolicy(true, false, false)
        policy.target = "cellular"
        policy.observing = true
        policy.onNativeConfig(false, true)
        policy.onNativeTryCell(true)
        assertEquals(Triple(false, true, true), policy.effective())
        policy.target = "wlan1"
        assertEquals(Triple(false, true, true), policy.effective())
        policy.onNativeTryCell(false)
        assertEquals(Triple(false, true, false), policy.effective())
    }

    @Test fun bootConfigurationMustReplaceConstructorDefaultsBeforeTemporaryOverride() {
        val policy = HotspotRequestPolicy(false, false, false)
        policy.onNativeConfig(true, false)
        policy.target = "cellular"
        policy.observing = true
        assertEquals(Triple(false, false, true), policy.effective())
        policy.observing = false
        policy.onNativeTryCell(false)
        assertEquals(Triple(true, false, false), policy.effective())
    }
}
