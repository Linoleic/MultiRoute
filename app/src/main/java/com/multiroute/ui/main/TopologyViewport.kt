package com.multiroute.ui.main

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize

internal data class TopologyViewport(val scale: Float = 1f, val offset: Offset = Offset.Zero) {
    fun constrained(viewport: IntSize, content: IntSize): TopologyViewport = copy(
        offset = Offset(
            offset.x.coerceIn((viewport.width - content.width * scale).coerceAtMost(0f), 0f),
            offset.y.coerceIn((viewport.height - content.height * scale).coerceAtMost(0f), 0f)
        )
    )

    fun transformed(centroid: Offset, pan: Offset, zoom: Float, viewport: IntSize, content: IntSize): TopologyViewport {
        val previous = constrained(viewport, content)
        val nextScale = (scale * zoom).coerceIn(0.35f, 2.5f)
        val nextOffset = centroid + (previous.offset - centroid) * (nextScale / scale) + pan
        return TopologyViewport(nextScale, nextOffset).constrained(viewport, content)
    }
}
