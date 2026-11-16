package com.multiroute.hook

/**
 * Pure policy logic deciding which clone-profile UIDs to inject into an active VPN network's
 * UID ranges and whether the resulting capability update should be published.
 */
internal object VpnUidRangePolicy {
    const val CLONE_USER_TYPE = "android.os.usertype.profile.CLONE"
    private const val PER_USER_RANGE = 100000

    data class SelectionResult(
        val injectedUids: Set<Int>,
        val hasInjection: Boolean,
        val shouldPublish: Boolean
    )

    fun isEligibleCloneUser(
        targetUserId: Int,
        hostUserId: Int,
        userType: String?,
        profileParentId: Int?
    ): Boolean {
        if (targetUserId <= 0 || targetUserId == hostUserId) return false
        return userType == CLONE_USER_TYPE && profileParentId == hostUserId
    }

    fun evaluate(
        routeUids: Set<Int>,
        hostUserId: Int,
        ownerUid: Int,
        allowedPackages: Set<String>?,
        deniedPackages: Set<String>,
        nativeRanges: Collection<IntRange>,
        oldRanges: Collection<IntRange>,
        wasInjected: Boolean,
        isCloneUser: (Int) -> Boolean,
        packagesForUid: (Int) -> List<String>
    ): SelectionResult {
        if (ownerUid < 0) {
            return SelectionResult(emptySet(), hasInjection = false, shouldPublish = false)
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
        val nativeSet = nativeRanges.toSet()
        val desiredSet = nativeSet + injected.map { it..it }
        val hasInjection = desiredSet != nativeSet
        val unchanged = desiredSet == oldRanges.toSet() || (!hasInjection && !wasInjected)
        return SelectionResult(
            injectedUids = injected,
            hasInjection = hasInjection,
            shouldPublish = !unchanged
        )
    }
}
