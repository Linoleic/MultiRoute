package com.multiroute.theme

import androidx.compose.animation.*
import androidx.compose.animation.core.FiniteAnimationSpec
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
        (fadeIn(scheme.defaultEffectsSpec()) +
            scaleIn(initialScale = 0.96f, animationSpec = scheme.defaultSpatialSpec())) togetherWith
            fadeOut(scheme.fastEffectsSpec())

    fun hierarchy(forward: Boolean): ContentTransform {
        val slideSpec = scheme.defaultSpatialSpec<IntOffset>()
        val scaleSpec = scheme.defaultSpatialSpec<Float>()
        val inEffects = scheme.defaultEffectsSpec<Float>()
        val outEffects = scheme.fastEffectsSpec<Float>()
        return if (forward) {
            (slideInHorizontally(slideSpec) { it / 5 } + fadeIn(inEffects)) togetherWith
                (slideOutHorizontally(slideSpec) { -it / 8 } +
                    scaleOut(targetScale = 0.96f, animationSpec = scaleSpec) +
                    fadeOut(outEffects))
        } else {
            (slideInHorizontally(slideSpec) { -it / 8 } +
                scaleIn(initialScale = 0.96f, animationSpec = scaleSpec) +
                fadeIn(inEffects)) togetherWith
                (slideOutHorizontally(slideSpec) { it / 5 } + fadeOut(outEffects))
        }
    }

    fun predictivePop(): ContentTransform {
        val slideSpec = scheme.defaultSpatialSpec<IntOffset>()
        val scaleSpec = scheme.defaultSpatialSpec<Float>()
        val inEffects = scheme.defaultEffectsSpec<Float>()
        val outEffects = scheme.fastEffectsSpec<Float>()
        return (scaleIn(initialScale = 0.94f, animationSpec = scaleSpec) + fadeIn(inEffects)) togetherWith
            (scaleOut(targetScale = 0.90f, animationSpec = scaleSpec) +
                slideOutHorizontally(slideSpec) { it / 6 } +
                fadeOut(outEffects))
    }

    fun itemFade(): FiniteAnimationSpec<Float> = scheme.fastEffectsSpec()
    fun itemPlacement(): FiniteAnimationSpec<IntOffset> = scheme.defaultSpatialSpec()

    val size = scheme.defaultSpatialSpec<IntSize>()
    fun expand(): EnterTransition =
        expandVertically(size, expandFrom = Alignment.Top) + fadeIn(scheme.fastEffectsSpec())

    fun collapse(): ExitTransition =
        shrinkVertically(size, shrinkTowards = Alignment.Top) + fadeOut(scheme.fastEffectsSpec())
}
