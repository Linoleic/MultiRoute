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
            wasOverridden = false,
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
        assertTrue(result.hasOverride)
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
            wasOverridden = false,
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
            wasOverridden = true,
            isCloneUser = { it == 10 },
            packagesForUid = { listOf("com.ss.android.ugc.trill") }
        )
        assertFalse(steady.shouldPublish)
        assertTrue(steady.hasInjection)
        assertTrue(steady.hasOverride)

        val removed = VpnUidRangePolicy.evaluate(
            routeUids = emptySet(),
            hostUserId = 0,
            ownerUid = 10294,
            allowedPackages = null,
            deniedPackages = emptySet(),
            nativeRanges = native,
            oldRanges = withClone,
            wasOverridden = true,
            isCloneUser = { it == 10 },
            packagesForUid = { emptyList() }
        )
        assertTrue(removed.injectedUids.isEmpty())
        assertFalse(removed.hasInjection)
        assertFalse(removed.hasOverride)
        assertTrue(removed.shouldPublish)
    }

    @Test
    fun subtractUidsSplitsBoundariesAndIgnoresSystemUids() {
        assertEquals(
            setOf(10362..10364),
            VpnUidRangePolicy.subtractUids(
                listOf(10361..10366),
                setOf(10361, 10365, 10366)
            )
        )
        assertTrue(
            VpnUidRangePolicy.subtractUids(
                listOf(10365..10365),
                setOf(10365)
            ).isEmpty()
        )
        assertEquals(
            setOf(0..99998, 100000..(Int.MAX_VALUE - 1)),
            VpnUidRangePolicy.subtractUids(
                listOf(0..Int.MAX_VALUE),
                setOf(0, 1000, 9999, 1001000, 99999, Int.MAX_VALUE)
            )
        )
    }

    @Test
    fun excludesPhysicalRoutedUidCombinedWithCloneInjectionAndSupportsRollback() {
        val native = setOf(0..10360, 10361..10366, 10367..99999)
        val evaluated = VpnUidRangePolicy.evaluate(
            routeUids = setOf(1010358, 10365), // 10365 also in routeUids -> routeUids takes precedence
            excludedUids = setOf(10365, 10400, 1000), // 10400 excluded, 1000 system UID ignored
            hostUserId = 0,
            ownerUid = 10294,
            allowedPackages = null,
            deniedPackages = emptySet(),
            nativeRanges = native,
            oldRanges = native,
            wasOverridden = false,
            isCloneUser = { it == 10 },
            packagesForUid = { uid ->
                when (uid) {
                    1010358 -> listOf("com.ss.android.ugc.trill")
                    else -> emptyList()
                }
            }
        )
        assertEquals(setOf(1010358), evaluated.injectedUids)
        assertEquals(setOf(10400), evaluated.excludedUids)
        assertEquals(
            setOf(0..10360, 10361..10366, 10367..10399, 10401..99999, 1010358..1010358),
            evaluated.desiredRanges
        )
        assertTrue(evaluated.hasOverride)
        assertTrue(evaluated.shouldPublish)

        // Idempotent when oldRanges already matches desiredRanges
        val steady = VpnUidRangePolicy.evaluate(
            routeUids = setOf(1010358, 10365),
            excludedUids = setOf(10365, 10400),
            hostUserId = 0,
            ownerUid = 10294,
            allowedPackages = null,
            deniedPackages = emptySet(),
            nativeRanges = native,
            oldRanges = evaluated.desiredRanges,
            wasOverridden = true,
            isCloneUser = { it == 10 },
            packagesForUid = { uid ->
                when (uid) {
                    1010358 -> listOf("com.ss.android.ugc.trill")
                    else -> emptyList()
                }
            }
        )
        assertFalse(steady.shouldPublish)
        assertTrue(steady.hasOverride)

        // Rollback to native ranges when rules are cleared
        val rolledBack = VpnUidRangePolicy.evaluate(
            routeUids = emptySet(),
            excludedUids = emptySet(),
            hostUserId = 0,
            ownerUid = 10294,
            allowedPackages = null,
            deniedPackages = emptySet(),
            nativeRanges = native,
            oldRanges = evaluated.desiredRanges,
            wasOverridden = true,
            isCloneUser = { it == 10 },
            packagesForUid = { emptyList() }
        )
        assertEquals(native, rolledBack.desiredRanges)
        assertFalse(rolledBack.hasOverride)
        assertTrue(rolledBack.shouldPublish)
    }

    @Test
    fun negativeOwnerUidIsNoOp() {
        val native = setOf(0..99999)
        val result = VpnUidRangePolicy.evaluate(
            routeUids = setOf(1010358),
            excludedUids = setOf(10365),
            hostUserId = 0,
            ownerUid = -1,
            allowedPackages = null,
            deniedPackages = emptySet(),
            nativeRanges = native,
            oldRanges = native,
            wasOverridden = false,
            isCloneUser = { true },
            packagesForUid = { listOf("com.example.app") }
        )
        assertFalse(result.shouldPublish)
        assertFalse(result.hasOverride)
        assertEquals(native, result.desiredRanges)
    }
}
