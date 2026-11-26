package com.multiroute.ui.main

import org.junit.Assert.assertEquals
import org.junit.Test

class ScenarioDragOrderTest {

    @Test
    fun computeDragTargetIndexTracksMidpointsAcrossVariableHeights() {
        val heights = listOf(100f, 160f, 100f)
        val spacing = 12f
        // tops:    0, 112, 284
        // centers: 50, 192, 334

        assertEquals(0, computeDragTargetIndex(0, 80f, heights, spacing))  // bottom 180 <= 192
        assertEquals(1, computeDragTargetIndex(0, 95f, heights, spacing))  // bottom 195 > 192
        assertEquals(2, computeDragTargetIndex(0, 240f, heights, spacing)) // bottom 340 > 334

        // Moving back up from bottom (index 2, top=284)
        assertEquals(2, computeDragTargetIndex(2, -80f, heights, spacing))  // top 204 >= 192
        assertEquals(1, computeDragTargetIndex(2, -100f, heights, spacing)) // top 184 < 192
        assertEquals(0, computeDragTargetIndex(2, -240f, heights, spacing)) // top 44 < 50
    }
}
