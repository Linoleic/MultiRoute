package com.multiroute.ui.main

import com.multiroute.util.DiagnosticInfo
import com.multiroute.util.ModuleStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class ModuleIndicatorTest {
    private val ready = DiagnosticInfo(isRootGranted = true, moduleStatus = ModuleStatus.ACTIVE)

    @Test fun readyRequiresRootAndVerifiedModule() {
        assertEquals(ModuleIndicator.READY, moduleIndicator(ready, false, false))
        assertEquals(ModuleIndicator.ERROR, moduleIndicator(ready.copy(isRootGranted = false), false, false))
        listOf(ModuleStatus.NOT_ACTIVE, ModuleStatus.PARTIAL, ModuleStatus.STALE).forEach {
            assertEquals(ModuleIndicator.ERROR, moduleIndicator(ready.copy(moduleStatus = it), false, false))
        }
        assertEquals(ModuleIndicator.RESTART_REQUIRED, moduleIndicator(ready.copy(moduleStatus = ModuleStatus.OUTDATED), false, false))
        assertEquals(ModuleIndicator.UNKNOWN, moduleIndicator(ready.copy(moduleStatus = ModuleStatus.LEGACY_UNVERIFIED), false, false))
        assertEquals(ModuleIndicator.ERROR, moduleIndicator(ready.copy(isRootGranted = false, moduleStatus = ModuleStatus.OUTDATED), false, false))
    }

    @Test fun unreadAndFailedChecksNeverClaimReady() {
        assertEquals(ModuleIndicator.CHECKING, moduleIndicator(null, false, false))
        assertEquals(ModuleIndicator.UNKNOWN, moduleIndicator(null, false, true))
        assertEquals(ModuleIndicator.UNKNOWN, moduleIndicator(ready, false, true))
        assertEquals(ModuleIndicator.CHECKING, moduleIndicator(ready, true, false))
        assertEquals(ModuleIndicator.CHECKING, moduleIndicator(ready, true, true))
    }

    @Test fun noRulesDoesNotMeanModuleFailure() {
        assertEquals(ModuleIndicator.READY, moduleIndicator(ready.copy(kernelRulesCount = 0), false, false))
    }
}

