package com.multiroute.cellular

import org.junit.Assert.assertEquals
import org.junit.Test

class SubscriptionSelectionTest {
    @Test fun changingDefaultReversesSecondaryWithoutSlotAssumptions() {
        assertEquals(15, SubscriptionSelection.secondary(2, listOf(2, 15)))
        assertEquals(2, SubscriptionSelection.secondary(15, listOf(2, 15)))
        assertEquals(-1, SubscriptionSelection.secondary(-1, listOf(2, 15)))
        assertEquals(-1, SubscriptionSelection.secondary(9, listOf(2, 15)))
        assertEquals(-1, SubscriptionSelection.secondary(2, listOf(2)))
        assertEquals(-1, SubscriptionSelection.secondary(2, listOf(2, 15, 18)))
    }
}
