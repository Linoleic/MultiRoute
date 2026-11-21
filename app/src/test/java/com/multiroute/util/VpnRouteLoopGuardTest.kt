package com.multiroute.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnRouteLoopGuardTest {
    @Test
    fun exactPositiveOwnerUidIsSkipped() {
        assertTrue(
            VpnRouteLoopGuard.shouldSkip(
                isVpn = true,
                ownerUid = 10532,
                candidateUid = 10532,
                candidatePackageNames = listOf("org.example.client")
            )
        )
    }

    @Test
    fun ordinaryAppIsNotSkippedForMatchingAppIdAlone() {
        assertFalse(
            VpnRouteLoopGuard.shouldSkip(
                isVpn = true,
                ownerUid = 10532,
                candidateUid = 99910532,
                candidatePackageNames = listOf("org.example.ordinary"),
                vpnOwnerPackageNames = listOf("org.example.vpn")
            )
        )
    }

    @Test
    fun sameVpnPackagePreservesCrossUserAppIdSafety() {
        assertTrue(
            VpnRouteLoopGuard.shouldSkip(
                isVpn = true,
                ownerUid = 10532,
                candidateUid = 99910532,
                candidatePackageNames = listOf("org.example.vpn", "org.example.companion"),
                vpnOwnerPackageNames = listOf("org.example.vpn")
            )
        )
    }

    @Test
    fun knownVpnClientNameIsSkippedOnVpnRoute() {
        assertTrue(
            VpnRouteLoopGuard.shouldSkip(
                isVpn = true,
                ownerUid = 20001,
                candidateUid = 20555,
                candidatePackageNames = listOf("com.example.SingBoxClient")
            )
        )
    }

    @Test
    fun knownVpnClientNameIsNotSkippedForNonVpnRoute() {
        assertFalse(
            VpnRouteLoopGuard.shouldSkip(
                isVpn = false,
                ownerUid = 20001,
                candidateUid = 20555,
                candidatePackageNames = listOf("com.example.clash")
            )
        )
    }
}
