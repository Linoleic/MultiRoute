package com.multiroute.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal object RoutingDimensions {
    val textGap = 4.dp
    val rowVerticalPadding = 8.dp
    val rowContentGap = 12.dp
    val contentPadding = 16.dp
    val touchTarget = 48.dp
    val contentMaxWidth = 760.dp
    val dualPaneMinWidth = 760.dp

    fun dualPaneLeftWidth(
        availableWidth: Dp,
        minWidth: Dp = 280.dp,
        maxWidth: Dp = 420.dp,
        fraction: Float = 0.36f
    ): Dp = (availableWidth * fraction).coerceIn(minWidth, maxWidth)
}
