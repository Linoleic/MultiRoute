package com.multiroute.hook

/**
 * Pure policy logic deciding which clone-profile UIDs to inject into, and which explicitly
 * non-VPN-routed UIDs to subtract from, an active VPN network's UID ranges.
 */
internal object VpnUidRangePolicy {
    const val CLONE_USER_TYPE = "android.os.usertype.profile.CLONE"
    private const val PER_USER_RANGE = 100000

    data class SelectionResult(
        val desiredRanges: Set<IntRange>,
        val injectedUids: Set<Int>,
        val excludedUids: Set<Int>,
        val hasOverride: Boolean,
        val shouldPublish: Boolean
    ) {
        val hasInjection: Boolean get() = injectedUids.isNotEmpty()
    }

    fun isEligibleCloneUser(
        targetUserId: Int,
        hostUserId: Int,
        userType: String?,
        profileParentId: Int?
    ): Boolean {
        if (targetUserId <= 0 || targetUserId == hostUserId) return false
        return userType == CLONE_USER_TYPE && profileParentId == hostUserId
    }

    internal fun subtractUids(
        ranges: Collection<IntRange>,
        excludedUids: Set<Int>
    ): Set<IntRange> {
        val sortedExcluded = excludedUids
            .asSequence()
            .filter { it >= 10000 && it % PER_USER_RANGE >= 10000 }
            .sorted()
            .toList()
        if (sortedExcluded.isEmpty()) return ranges.toSet()

        val result = linkedSetOf<IntRange>()
        for (range in ranges) {
            if (range.isEmpty()) continue
            var cursor = range.first
            var exhausted = false
            for (uid in sortedExcluded) {
                if (uid < cursor) continue
                if (uid > range.last) break
                if (cursor < uid) {
                    result.add(cursor..(uid - 1))
                }
                if (uid == range.last) {
                    exhausted = true
                    break
                }
                cursor = uid + 1
            }
            if (!exhausted && cursor <= range.last) {
                result.add(cursor..range.last)
            }
        }
        return result
    }

    fun evaluate(
        routeUids: Set<Int>,
        excludedUids: Set<Int> = emptySet(),
        hostUserId: Int,
        ownerUid: Int,
        allowedPackages: Set<String>?,
        deniedPackages: Set<String>,
        nativeRanges: Collection<IntRange>,
        oldRanges: Collection<IntRange>,
        wasOverridden: Boolean,
        isCloneUser: (Int) -> Boolean,
        packagesForUid: (Int) -> List<String>
    ): SelectionResult {
        val nativeSet = nativeRanges.toSet()
        if (ownerUid < 0) {
            return SelectionResult(
                desiredRanges = nativeSet,
                injectedUids = emptySet(),
                excludedUids = emptySet(),
                hasOverride = false,
                shouldPublish = false
            )
        }
        val injected = linkedSetOf<Int>()
        val cloneUsers = mutableMapOf<Int, Boolean>()
        for (uid in routeUids.sorted()) {
            if (uid < 10000 || uid % PER_USER_RANGE < 10000) continue
            val targetUser = uid / PER_USER_RANGE
            if (targetUser == hostUserId) continue
            if (uid % PER_USER_RANGE == ownerUid % PER_USER_RANGE) continue
            val clone = cloneUsers.getOrPut(targetUser) { isCloneUser(targetUser) }
            if (!clone) continue
            if (nativeRanges.any { uid in it }) continue
            val packages = packagesForUid(uid)
            if (packages.isEmpty() || packages.any { it in deniedPackages }) continue
            if (allowedPackages != null && packages.any { it !in allowedPackages }) continue
            injected.add(uid)
        }

        val excluded = excludedUids
            .asSequence()
            .filter { uid ->
                uid >= 10000 &&
                    uid % PER_USER_RANGE >= 10000 &&
                    uid !in routeUids &&
                    nativeRanges.any { uid in it }
            }
            .sorted()
            .toCollection(linkedSetOf())

        val desiredRanges = subtractUids(nativeRanges, excluded) + injected.map { it..it }
        val hasOverride = desiredRanges != nativeSet
        val unchanged = desiredRanges == oldRanges.toSet() || (!hasOverride && !wasOverridden)
        return SelectionResult(
            desiredRanges = desiredRanges,
            injectedUids = injected,
            excludedUids = excluded,
            hasOverride = hasOverride,
            shouldPublish = !unchanged
        )
    }
}
