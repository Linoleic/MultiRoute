package com.multiroute.hook

import org.junit.Assert.*
import org.junit.Test

class CellularFrameworkCompatTest {
    private open class HandlerOnly {
        private var mMobileDataPreferredUids: Set<Int> = setOf(10596)
        var refreshed = false
        @Suppress("unused") private fun handleMobileDataPreferredUidsChanged() {
            val desired = setOf(10596)
            if (desired == mMobileDataPreferredUids) return
            mMobileDataPreferredUids = desired
            refreshed = true
        }
    }
    private class NewRom : HandlerOnly()
    private class OldRom : HandlerOnly() {
        @Suppress("unused") fun updateMobileDataPreferredUids() { error("Should prefer handler") }
    }
    private class ThrowingRom {
        private var mMobileDataPreferredUids: Set<Int> = emptySet()
        @Suppress("unused") private fun handleMobileDataPreferredUidsChanged() { error("Native refresh failed") }
        fun cached() = mMobileDataPreferredUids
    }
    private class LegacyOnly {
        var refreshed = false
        @Suppress("unused") fun updateMobileDataPreferredUids() { refreshed = true }
    }
    private class WrongSignature {
        @Suppress("unused") fun updateMobileDataPreferredUids(uid: Int) = uid
    }

    @Test fun prefersHandlerEvenWhenOldEntryPointExists() {
        val rom = OldRom()
        CellularFrameworkCompat.refresh(rom)
        assertTrue(rom.refreshed)
    }

    @Test fun sameUidSetStillRebuildsRequestsWhenTargetChanges() {
        val rom = NewRom()
        CellularFrameworkCompat.refresh(rom)
        assertTrue(rom.refreshed)
    }

    @Test fun restoresComparisonCacheWhenNativeRefreshFailsBeforeUpdatingIt() {
        val rom = ThrowingRom()
        assertThrows(java.lang.reflect.InvocationTargetException::class.java) { CellularFrameworkCompat.refresh(rom) }
        assertTrue(rom.cached().isEmpty())
    }

    @Test fun fallsBackToOldEntryWhenHandlerIsAbsent() {
        val rom = LegacyOnly()
        CellularFrameworkCompat.refresh(rom)
        assertTrue(rom.refreshed)
    }

    @Test fun unsupportedSignatureIsNotInvoked() {
        assertNull(CellularFrameworkCompat.refreshMethod(WrongSignature::class.java))
    }
}
