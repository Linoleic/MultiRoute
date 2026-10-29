package com.multiroute.cellular

/** Selects the unique active subscription other than the current default data subscription. */
object SubscriptionSelection {
    fun secondary(defaultId: Int, activeIds: List<Int>): Int =
        if (defaultId >= 0 && defaultId in activeIds) activeIds.distinct().filter { it != defaultId }.singleOrNull() ?: -1 else -1

}
