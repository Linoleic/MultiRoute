package com.multiroute.ui.main

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs

internal data class TopologyViewport(val scale: Float = 1f, val offset: Offset = Offset.Zero) {
    fun constrained(viewport: IntSize, content: IntSize, bottomInset: Int = 0): TopologyViewport {
        val clampedScale = scale.coerceIn(fitScale(viewport, content), MAX_SCALE)
        val minOffsetX = (viewport.width - content.width * clampedScale).coerceAtMost(0f)
        val minOffsetY = ((viewport.height - bottomInset).coerceAtLeast(0) - content.height * clampedScale).coerceAtMost(0f)
        return TopologyViewport(
            scale = clampedScale,
            offset = Offset(
                offset.x.coerceIn(minOffsetX, 0f),
                offset.y.coerceIn(minOffsetY, 0f)
            )
        )
    }

    fun transformed(
        centroid: Offset,
        pan: Offset,
        zoom: Float,
        viewport: IntSize,
        content: IntSize,
        bottomInset: Int = 0
    ): TopologyViewport {
        val previous = constrained(viewport, content, bottomInset)
        val nextScale = (previous.scale * zoom).coerceIn(fitScale(viewport, content), MAX_SCALE)
        val nextOffset = centroid + (previous.offset - centroid) * (nextScale / previous.scale) + pan
        return TopologyViewport(nextScale, nextOffset).constrained(viewport, content, bottomInset)
    }

    fun isCloseTo(other: TopologyViewport): Boolean =
        abs(scale - other.scale) < 0.01f &&
            abs(offset.x - other.offset.x) < 2f &&
            abs(offset.y - other.offset.y) < 2f

    companion object {
        const val MAX_SCALE = 2.5f

        fun fit(viewport: IntSize, content: IntSize): TopologyViewport =
            TopologyViewport(scale = fitScale(viewport, content))

        fun fitScale(viewport: IntSize, content: IntSize): Float {
            if (viewport.width <= 0 || viewport.height <= 0 || content.width <= 0 || content.height <= 0) return 1f
            return minOf(1f, viewport.width.toFloat() / content.width, viewport.height.toFloat() / content.height)
        }
    }
}
