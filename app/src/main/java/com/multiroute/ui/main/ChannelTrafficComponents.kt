package com.multiroute.ui.main

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.multiroute.R
import com.multiroute.util.ChannelTrafficStats
import com.multiroute.util.ChannelTrafficTracker
import com.multiroute.util.TrafficPoint
import com.multiroute.util.TrafficRateFormatter
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private const val MIN_SPARKLINE_SCALE_BYTES_PER_SEC = 32L * 1024L

/**
 * Starts live interface traffic sampling while the Channels tab is visible and STARTED.
 */
@Composable
internal fun ObserveChannelTraffic(viewModel: MainScreenViewModel) {
    val lifecycle = LocalLifecycleOwner.current
    LaunchedEffect(lifecycle, viewModel) {
        lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.observeChannelTraffic()
        }
    }
}

/**
 * Compact live speed strip and 30s sparkline embedded inside each channel card.
 * Collects only its own [interfaceName] slice so 1 Hz ticks recompose strictly this strip.
 */
@Composable
fun ChannelTrafficCardSection(
    interfaceName: String,
    trafficFlow: StateFlow<Map<String, ChannelTrafficStats>>,
    modifier: Modifier = Modifier
) {
    val stats by remember(interfaceName, trafficFlow) {
        trafficFlow.map { it[interfaceName] }.distinctUntilChanged()
    }.collectAsState(initial = trafficFlow.value[interfaceName])

    val currentStats = stats
    val rxColor = MaterialTheme.colorScheme.primary
    val txColor = MaterialTheme.colorScheme.tertiary
    val rxSpeedText = if (currentStats != null && currentStats.hasSpeedSample) {
        TrafficRateFormatter.formatSpeed(currentStats.rxBytesPerSec)
    } else {
        "--"
    }
    val txSpeedText = if (currentStats != null && currentStats.hasSpeedSample) {
        TrafficRateFormatter.formatSpeed(currentStats.txBytesPerSec)
    } else {
        "--"
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CompactRateBadge(
                    isDownload = true,
                    speedText = rxSpeedText,
                    tint = rxColor
                )
                CompactRateBadge(
                    isDownload = false,
                    speedText = txSpeedText,
                    tint = txColor
                )
            }

            ChannelTrafficSparkline(
                history = currentStats?.history.orEmpty(),
                peakBytesPerSec = currentStats?.peakCombinedBytesPerSec ?: 0L,
                rxColor = rxColor,
                txColor = txColor,
                height = 34.dp
            )
        }
    }
}

/**
 * Richer live throughput card rendered inside [ChannelDetailBottomSheet].
 */
@Composable
fun ChannelTrafficDetailCard(
    interfaceName: String,
    trafficFlow: StateFlow<Map<String, ChannelTrafficStats>>,
    modifier: Modifier = Modifier
) {
    val stats by remember(interfaceName, trafficFlow) {
        trafficFlow.map { it[interfaceName] }.distinctUntilChanged()
    }.collectAsState(initial = trafficFlow.value[interfaceName])

    val rxColor = MaterialTheme.colorScheme.primary
    val txColor = MaterialTheme.colorScheme.tertiary
    val currentStats = stats

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                DetailSpeedMetric(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.traffic_download_short),
                    speedText = when {
                        currentStats == null -> "--"
                        currentStats.hasSpeedSample -> TrafficRateFormatter.formatSpeed(currentStats.rxBytesPerSec)
                        else -> stringResource(R.string.traffic_collecting)
                    },
                    isDownload = true,
                    tint = rxColor
                )
                DetailSpeedMetric(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.traffic_upload_short),
                    speedText = if (currentStats != null && currentStats.hasSpeedSample) {
                        TrafficRateFormatter.formatSpeed(currentStats.txBytesPerSec)
                    } else {
                        "--"
                    },
                    isDownload = false,
                    tint = txColor
                )
            }

            ChannelTrafficSparkline(
                history = currentStats?.history.orEmpty(),
                peakBytesPerSec = currentStats?.peakCombinedBytesPerSec ?: 0L,
                rxColor = rxColor,
                txColor = txColor,
                height = 64.dp
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LegendItem(color = rxColor, label = stringResource(R.string.traffic_download_short))
                LegendItem(color = txColor, label = stringResource(R.string.traffic_upload_short))
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))

            DetailPropertyRow(
                label = stringResource(R.string.traffic_peak_window),
                value = if (currentStats != null && currentStats.history.isNotEmpty()) {
                    "↓ ${TrafficRateFormatter.formatSpeed(currentStats.peakRxBytesPerSec)} · ↑ ${TrafficRateFormatter.formatSpeed(currentStats.peakTxBytesPerSec)}"
                } else {
                    "--"
                }
            )
            DetailPropertyRow(
                label = stringResource(R.string.traffic_total_since_boot),
                value = if (currentStats != null) {
                    "↓ ${TrafficRateFormatter.formatTotalBytes(currentStats.totalRxBytes)} · ↑ ${TrafficRateFormatter.formatTotalBytes(currentStats.totalTxBytes)}"
                } else {
                    "--"
                }
            )
        }
    }
}

@Composable
private fun CompactRateBadge(
    isDownload: Boolean,
    speedText: String,
    tint: Color
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Icon(
            imageVector = if (isDownload) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward,
            contentDescription = stringResource(
                if (isDownload) R.string.traffic_download_short else R.string.traffic_upload_short
            ),
            tint = tint,
            modifier = Modifier.size(13.dp)
        )
        Text(
            text = speedText,
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.Bold,
                fontFeatureSettings = "tnum"
            ),
            color = tint,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun DetailSpeedMetric(
    label: String,
    speedText: String,
    isDownload: Boolean,
    tint: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = tint.copy(alpha = 0.08f)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = if (isDownload) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            Text(
                text = speedText,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontFeatureSettings = "tnum"
                ),
                color = tint,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .background(color = color, shape = CircleShape)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline
        )
    }
}

/**
 * Right-aligned 30-sample dual-trace sparkline (Download RX + Upload TX).
 */
@Composable
fun ChannelTrafficSparkline(
    history: List<TrafficPoint>,
    peakBytesPerSec: Long,
    rxColor: Color,
    txColor: Color,
    height: Dp,
    modifier: Modifier = Modifier
) {
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
    val trackColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.45f)
    val maxScale = remember(peakBytesPerSec) {
        val floorAdjusted = maxOf(peakBytesPerSec, MIN_SPARKLINE_SCALE_BYTES_PER_SEC)
        (floorAdjusted.toFloat() * 1.12f).coerceAtLeast(MIN_SPARKLINE_SCALE_BYTES_PER_SEC.toFloat())
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(color = trackColor, shape = RoundedCornerShape(8.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.traffic_window_label),
                style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.8f),
                maxLines = 1,
                softWrap = false
            )
            if (peakBytesPerSec > 0L) {
                Text(
                    text = stringResource(
                        R.string.traffic_peak_badge,
                        TrafficRateFormatter.formatSpeed(peakBytesPerSec)
                    ),
                    style = TextStyle(
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        fontFeatureSettings = "tnum"
                    ),
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
        ) {
            val w = size.width
            val h = size.height
            if (w <= 0f || h <= 0f) return@Canvas

            // Midline and baseline reference guides
            drawLine(
                color = gridColor,
                start = Offset(0f, h * 0.5f),
                end = Offset(w, h * 0.5f),
                strokeWidth = 0.8.dp.toPx()
            )
            drawLine(
                color = gridColor.copy(alpha = 0.7f),
                start = Offset(0f, h),
                end = Offset(w, h),
                strokeWidth = 0.8.dp.toPx()
            )

            if (history.isEmpty()) return@Canvas

            val slots = ChannelTrafficTracker.DEFAULT_HISTORY_POINTS.coerceAtLeast(2)
            val stepX = w / (slots - 1).toFloat()
            val startSlot = (slots - history.size).coerceAtLeast(0)

            val rxPoints = ArrayList<Offset>(history.size)
            val txPoints = ArrayList<Offset>(history.size)
            for (i in history.indices) {
                val x = (startSlot + i) * stepX
                val pt = history[i]
                val rxRatio = (pt.rxBytesPerSec.toFloat() / maxScale).coerceIn(0f, 1f)
                val txRatio = (pt.txBytesPerSec.toFloat() / maxScale).coerceIn(0f, 1f)
                rxPoints.add(Offset(x, h - rxRatio * h))
                txPoints.add(Offset(x, h - txRatio * h))
            }

            if (rxPoints.size >= 2) {
                val rxLine = buildSmoothPath(rxPoints)
                val rxFill = Path().apply {
                    addPath(rxLine)
                    lineTo(rxPoints.last().x, h)
                    lineTo(rxPoints.first().x, h)
                    close()
                }
                drawPath(
                    path = rxFill,
                    brush = Brush.verticalGradient(
                        colors = listOf(rxColor.copy(alpha = 0.24f), Color.Transparent),
                        startY = 0f,
                        endY = h
                    )
                )
                drawPath(
                    path = rxLine,
                    color = rxColor,
                    style = Stroke(
                        width = 1.75.dp.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )

                val txLine = buildSmoothPath(txPoints)
                drawPath(
                    path = txLine,
                    color = txColor.copy(alpha = 0.85f),
                    style = Stroke(
                        width = 1.35.dp.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )
            }

            // Highlight latest sample point cleanly on the right edge
            drawCircle(
                color = txColor.copy(alpha = 0.9f),
                radius = 2.0.dp.toPx(),
                center = txPoints.last()
            )
            drawCircle(
                color = rxColor,
                radius = 2.4.dp.toPx(),
                center = rxPoints.last()
            )
        }
    }
}

private fun buildSmoothPath(points: List<Offset>): Path {
    val path = Path()
    if (points.isEmpty()) return path
    path.moveTo(points[0].x, points[0].y)
    if (points.size == 1) return path
    if (points.size == 2) {
        path.lineTo(points[1].x, points[1].y)
        return path
    }
    for (i in 0 until points.size - 1) {
        val current = points[i]
        val next = points[i + 1]
        val midX = (current.x + next.x) * 0.5f
        val midY = (current.y + next.y) * 0.5f
        path.quadraticTo(current.x, current.y, midX, midY)
    }
    val last = points.last()
    path.lineTo(last.x, last.y)
    return path
}
