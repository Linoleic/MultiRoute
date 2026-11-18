package com.multiroute.theme

import androidx.compose.animation.*
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MotionScheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

/** Expressive spring-driven motion aligned with Material 3 Expressive defaults. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
internal object AppMotion {
    private val scheme = MotionScheme.expressive()

    fun content(): ContentTransform =
        fadeIn(scheme.fastEffectsSpec()) togetherWith fadeOut(scheme.fastEffectsSpec())

    fun hierarchy(forward: Boolean): ContentTransform {
        val direction = if (forward) 1 else -1
        val slideSpec = scheme.defaultSpatialSpec<IntOffset>()
        val fadeSpec = scheme.fastEffectsSpec<Float>()
        return (slideInHorizontally(slideSpec) { direction * it / 10 } + fadeIn(fadeSpec)) togetherWith
            (slideOutHorizontally(slideSpec) { -direction * it / 10 } + fadeOut(fadeSpec))
    }

    val size = scheme.defaultSpatialSpec<IntSize>()
    fun expand(): EnterTransition =
        expandVertically(size, expandFrom = Alignment.Top) + fadeIn(scheme.fastEffectsSpec())

    fun collapse(): ExitTransition =
        shrinkVertically(size, shrinkTowards = Alignment.Top) + fadeOut(scheme.fastEffectsSpec())
}
