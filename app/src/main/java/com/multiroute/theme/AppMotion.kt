package com.multiroute.theme

import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.IntSize

/** Shared, restrained motion for recurring tool interactions. Compose applies system duration scale. */
internal object AppMotion {
    private const val FadeMillis = 140
    private const val PageMillis = 220

    fun content(): ContentTransform =
        fadeIn(tween(FadeMillis)) togetherWith fadeOut(tween(FadeMillis))

    fun hierarchy(forward: Boolean): ContentTransform {
        val direction = if (forward) 1 else -1
        return ((slideInHorizontally(tween(PageMillis)) { direction * it / 12 } + fadeIn(tween(PageMillis)))
            togetherWith (slideOutHorizontally(tween(PageMillis)) { -direction * it / 12 } + fadeOut(tween(FadeMillis))))

    }

    val size = spring<IntSize>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
    fun expand(): EnterTransition = expandVertically(size, expandFrom = Alignment.Top) + fadeIn(tween(FadeMillis))
    fun collapse(): ExitTransition = shrinkVertically(size, shrinkTowards = Alignment.Top) + fadeOut(tween(FadeMillis))
}
