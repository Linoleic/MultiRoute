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
        val minimumScale = minOf(0.35f, fitScale(viewport, content))
        val nextScale = (scale * zoom).coerceIn(minimumScale, 2.5f)
        val nextOffset = centroid + (previous.offset - centroid) * (nextScale / scale) + pan
        return TopologyViewport(nextScale, nextOffset).constrained(viewport, content)
    }

    companion object {
        fun fit(viewport: IntSize, content: IntSize): TopologyViewport =
            TopologyViewport(scale = fitScale(viewport, content))

        private fun fitScale(viewport: IntSize, content: IntSize): Float {
            if (viewport.width <= 0 || viewport.height <= 0 || content.width <= 0 || content.height <= 0) return 1f
            return minOf(1f, viewport.width.toFloat() / content.width, viewport.height.toFloat() / content.height)
        }
    }
}
