package com.multiroute.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnUidRangePolicyTest {

    @Test
    fun cloneUserEligibilityRequiresCloneTypeAndMatchingParent() {
        assertTrue(
            VpnUidRangePolicy.isEligibleCloneUser(
                targetUserId = 10,
                hostUserId = 0,
                userType = VpnUidRangePolicy.CLONE_USER_TYPE,
                profileParentId = 0
            )
        )
        assertFalse(
            VpnUidRangePolicy.isEligibleCloneUser(
                targetUserId = 10,
                hostUserId = 0,
                userType = "android.os.usertype.profile.MANAGED",
                profileParentId = 0
            )
        )
        assertFalse(
            VpnUidRangePolicy.isEligibleCloneUser(
                targetUserId = 10,
                hostUserId = 0,
                userType = VpnUidRangePolicy.CLONE_USER_TYPE,
                profileParentId = 11
            )
        )
        assertFalse(
            VpnUidRangePolicy.isEligibleCloneUser(
                targetUserId = 0,
                hostUserId = 0,
                userType = VpnUidRangePolicy.CLONE_USER_TYPE,
                profileParentId = 0
            )
        )
    }

    @Test
    fun injectsExplicitCloneAppUidAndExcludesOwnerSystemAndDeniedUids() {
        val native = setOf(0..99999)
        val result = VpnUidRangePolicy.evaluate(
            routeUids = setOf(
                1010358, // explicitly routed clone app
                1010294, // clone instance of VPN owner app -> anti-loopback skip
                1001000, // clone system UID -> invalid app UID skip
                1010400, // denied package -> skip
                1010401, // non-clone secondary user 10 -> wait, user 11 below
                1110401, // non-clone secondary user 11 -> skip
                10358    // host user UID already covered by native 0..99999 -> skip
            ),
            hostUserId = 0,
            ownerUid = 10294,
            allowedPackages = null,
            deniedPackages = setOf("com.example.denied"),
            nativeRanges = native,
            oldRanges = native,
            wasInjected = false,
            isCloneUser = { userId -> userId == 10 },
            packagesForUid = { uid ->
                when (uid) {
                    1010358 -> listOf("com.ss.android.ugc.trill")
                    1010400 -> listOf("com.example.denied")
                    1010401 -> emptyList()
                    1110401 -> listOf("com.example.work")
                    else -> emptyList()
                }
            }
        )

        assertEquals(setOf(1010358), result.injectedUids)
        assertTrue(result.hasInjection)
        assertTrue(result.shouldPublish)
    }

    @Test
    fun enforcesAllowedApplicationsWhenNonNull() {
        val native = setOf(0..99999)
        val result = VpnUidRangePolicy.evaluate(
            routeUids = setOf(1010358, 1010359),
            hostUserId = 0,
            ownerUid = 10294,
            allowedPackages = setOf("com.allowed.app"),
            deniedPackages = emptySet(),
            nativeRanges = native,
            oldRanges = native,
            wasInjected = false,
            isCloneUser = { it == 10 },
            packagesForUid = { uid ->
                when (uid) {
                    1010358 -> listOf("com.allowed.app")
                    1010359 -> listOf("com.other.app")
                    else -> emptyList()
                }
            }
        )

        assertEquals(setOf(1010358), result.injectedUids)
        assertTrue(result.shouldPublish)
    }

    @Test
    fun idempotentNoOpWhenRangesAlreadyMatchAndHandlesRemovalRollback() {
        val native = setOf(0..99999)
        val withClone = setOf(0..99999, 1010358..1010358)

        val steady = VpnUidRangePolicy.evaluate(
            routeUids = setOf(1010358),
            hostUserId = 0,
            ownerUid = 10294,
            allowedPackages = null,
            deniedPackages = emptySet(),
            nativeRanges = native,
            oldRanges = withClone,
            wasInjected = true,
            isCloneUser = { it == 10 },
            packagesForUid = { listOf("com.ss.android.ugc.trill") }
        )
        assertFalse(steady.shouldPublish)
        assertTrue(steady.hasInjection)

        val removed = VpnUidRangePolicy.evaluate(
            routeUids = emptySet(),
            hostUserId = 0,
            ownerUid = 10294,
            allowedPackages = null,
            deniedPackages = emptySet(),
            nativeRanges = native,
            oldRanges = withClone,
            wasInjected = true,
            isCloneUser = { it == 10 },
            packagesForUid = { emptyList() }
        )
        assertTrue(removed.injectedUids.isEmpty())
        assertFalse(removed.hasInjection)
        assertTrue(removed.shouldPublish)
    }
}
