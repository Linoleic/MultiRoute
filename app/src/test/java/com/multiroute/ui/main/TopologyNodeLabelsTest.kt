package com.multiroute.ui.main

import org.junit.Assert.assertEquals
import org.junit.Test

class TopologyNodeLabelsTest {
    @Test
    fun compactCellularLabelRemovesOnlyLocalizedDefaultSuffix() {
        assertEquals("SIM 2 · China Mobile", compactCellularLabel("SIM 2 · China Mobile (default data SIM)", " (default data SIM)"))
        assertEquals("SIM 2 · 中国移动", compactCellularLabel("SIM 2 · 中国移动（默认数据卡）", "（默认数据卡）"))
    }

    @Test
    fun compactCellularLabelLeavesOtherTextUntouched() {
        assertEquals("SIM 1 · Carrier", compactCellularLabel("SIM 1 · Carrier", "（默认数据卡）"))
        assertEquals(null, compactCellularLabel("（默认数据卡）", "（默认数据卡）"))
        assertEquals(null, compactCellularLabel(null, "（默认数据卡）"))
    }

    @Test
    fun compactVpnOwnerNameRemovesOnlyTrailingPackageName() {
        assertEquals("Example VPN", compactVpnOwnerName("Example VPN (com.example.vpn)"))
        assertEquals("Example VPN", compactVpnOwnerName("Example VPN"))
        assertEquals("VPN (system)", compactVpnOwnerName("VPN (system)"))
        assertEquals(null, compactVpnOwnerName("  "))
    }
}
