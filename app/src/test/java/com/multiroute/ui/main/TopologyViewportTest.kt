package com.multiroute.ui.main

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TopologyViewportTest {
    @Test
    fun defaultViewportStartsAtOriginalScaleAndOrigin() {
        assertEquals(1f, TopologyViewport().scale)
        assertEquals(Offset.Zero, TopologyViewport().offset)
    }

    @Test
    fun fitUsesAvailableWidthOnNarrowScreens() {
        val fitted = TopologyViewport.fit(IntSize(360, 600), IntSize(672, 500))

        assertEquals(360f / 672f, fitted.scale)
        assertEquals(Offset.Zero, fitted.offset)
    }

    @Test
    fun fitUsesAvailableHeightForTallGraphs() {
        val fitted = TopologyViewport.fit(IntSize(720, 320), IntSize(672, 800))

        assertEquals(0.4f, fitted.scale)
        assertEquals(Offset.Zero, fitted.offset)
    }

    @Test
    fun fitKeepsOriginalScaleWhenWholeGraphAlreadyFitsOrHasNotBeenMeasured() {
        assertEquals(TopologyViewport(), TopologyViewport.fit(IntSize(720, 600), IntSize(672, 500)))
        assertEquals(TopologyViewport(), TopologyViewport.fit(IntSize(720, 600), IntSize.Zero))
    }

    @Test
    fun zoomKeepsContentPointUnderFocalPointBeforeApplyingPan() {
        val viewport = IntSize(width = 600, height = 500)
        val content = IntSize(width = 1200, height = 1000)
        val initial = TopologyViewport(scale = 1f, offset = Offset(-100f, -80f))
        val centroid = Offset(150f, 120f)
        val pan = Offset(10f, -5f)

        val transformed = initial.transformed(
            centroid = centroid,
            pan = pan,
            zoom = 1.5f,
            viewport = viewport,
            content = content,
        )

        val contentPointX = (centroid.x - initial.offset.x) / initial.scale
        val contentPointY = (centroid.y - initial.offset.y) / initial.scale
        assertEquals(centroid.x + pan.x, contentPointX * transformed.scale + transformed.offset.x, 0.001f)
        assertEquals(centroid.y + pan.y, contentPointY * transformed.scale + transformed.offset.y, 0.001f)
    }

    @Test
    fun transformedViewportClampsZoomAndPanToContentBounds() {
        val viewport = IntSize(width = 400, height = 300)
        val content = IntSize(width = 1000, height = 800)

        val zoomedIn = TopologyViewport(scale = 2f, offset = Offset(-100f, -100f)).transformed(
            centroid = Offset(200f, 150f),
            pan = Offset(10_000f, 10_000f),
            zoom = 10f,
            viewport = viewport,
            content = content,
        )
        assertEquals(2.5f, zoomedIn.scale)
        assertEquals(Offset.Zero, zoomedIn.offset)

        val zoomedOut = TopologyViewport(scale = 0.5f, offset = Offset(-100f, -100f)).transformed(
            centroid = Offset(200f, 150f),
            pan = Offset(-10_000f, -10_000f),
            zoom = 0.01f,
            viewport = viewport,
            content = content,
        )
        assertEquals(0.375f, zoomedOut.scale)
        assertEquals(Offset.Zero, zoomedOut.offset)
    }

    @Test
    fun wideScreenCannotZoomSmallerThanFitScale() {
        val viewport = IntSize(width = 800, height = 600)
        val content = IntSize(width = 672, height = 400)

        val zoomedOut = TopologyViewport().transformed(
            centroid = Offset(400f, 300f),
            pan = Offset.Zero,
            zoom = 0.2f,
            viewport = viewport,
            content = content,
        )

        assertEquals(1f, zoomedOut.scale)
        assertEquals(Offset.Zero, zoomedOut.offset)
    }

    @Test
    fun constrainedClampsStaleSmallerScaleUpToFitScale() {
        val viewport = IntSize(width = 800, height = 600)
        val content = IntSize(width = 672, height = 400)

        val constrained = TopologyViewport(scale = 0.5f, offset = Offset(-20f, -20f)).constrained(
            viewport = viewport,
            content = content,
        )

        assertEquals(1f, constrained.scale)
        assertEquals(Offset.Zero, constrained.offset)
    }

    @Test
    fun bottomInsetAllowsPanningBottomContentClearOfFloatingActionButton() {
        val viewport = IntSize(width = 600, height = 500)
        val content = IntSize(width = 600, height = 500)

        val constrained = TopologyViewport(scale = 1f, offset = Offset(0f, -200f)).constrained(
            viewport = viewport,
            content = content,
            bottomInset = 72,
        )

        assertEquals(Offset(0f, -72f), constrained.offset)
        assertEquals(1f, TopologyViewport.fit(viewport, content).scale)
    }

    @Test
    fun isCloseToMatchesWithinToleranceAndDetectsChanges() {
        val base = TopologyViewport(scale = 0.6f, offset = Offset(-10f, -20f))
        assertTrue(base.isCloseTo(TopologyViewport(scale = 0.605f, offset = Offset(-11f, -19f))))
        assertFalse(base.isCloseTo(TopologyViewport(scale = 0.7f, offset = Offset(-10f, -20f))))
        assertFalse(base.isCloseTo(TopologyViewport(scale = 0.6f, offset = Offset(-20f, -20f))))
    }

    @Test
    fun fittingAnOversizedGraphAllowsZoomingInGraduallyFromBelowTheNormalMinimum() {
        val viewport = IntSize(width = 400, height = 300)
        val content = IntSize(width = 2000, height = 1500)
        val fitted = TopologyViewport.fit(viewport, content)

        assertEquals(0.2f, fitted.scale)
        assertEquals(0.22f, fitted.transformed(Offset.Zero, Offset.Zero, 1.1f, viewport, content).scale, 0.001f)
    }

    @Test
    fun constrainedClampsEachAxisAndKeepsFittingAxesAtOrigin() {
        val constrained = TopologyViewport(
            scale = 1f,
            offset = Offset(20f, -900f),
        ).constrained(
            viewport = IntSize(width = 400, height = 300),
            content = IntSize(width = 800, height = 1000),
        )
        assertEquals(Offset(0f, -700f), constrained.offset)

        val oneAxisFits = TopologyViewport(
            scale = 0.35f,
            offset = Offset(-40f, -100f),
        ).constrained(
            viewport = IntSize(width = 400, height = 300),
            content = IntSize(width = 800, height = 1000),
        )
        assertEquals(Offset(0f, -50f), oneAxisFits.offset)

        val allAxesFit = TopologyViewport(
            scale = 0.5f,
            offset = Offset(-20f, -30f),
        ).constrained(
            viewport = IntSize(width = 400, height = 300),
            content = IntSize(width = 600, height = 500),
        )
        assertEquals(Offset.Zero, allAxesFit.offset)
    }
}
