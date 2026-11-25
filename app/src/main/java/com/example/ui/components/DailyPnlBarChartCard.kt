package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.ShowChart
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.engine.RiskMath
import com.example.ui.theme.LocalTradingColors
import com.example.ui.theme.MonoPriceMedium
import com.example.ui.theme.MonoPriceSmall
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

data class DailyRealizedPnlPoint(
    val dayStartMillis: Long,
    val shortDateLabel: String,
    val fullDateLabel: String,
    val netPnl: BigDecimal,
    val tradeCount: Int,
    val wins: Int,
    val losses: Int
)

/**
 * Interactive 30-day Daily Realized P&L vector bar chart built on Jetpack Compose Canvas
 * with aligned Y/X axis labels, gradient profit/loss bars, optional cumulative equity curve
 * overlay, tap/drag day scrubbing, and India (₹ INR) / Crypto ($ USD) wallet switching.
 */
@Composable
fun DailyPnlBarChartCard(
    indiaDailySeries: List<DailyRealizedPnlPoint>,
    cryptoDailySeries: List<DailyRealizedPnlPoint>,
    modifier: Modifier = Modifier
) {
    val tradingColors = LocalTradingColors.current
    val textMeasurer = rememberTextMeasurer()

    var userSelectedWallet by remember { mutableStateOf<Boolean?>(null) }
    val hasIndiaTrades = remember(indiaDailySeries) { indiaDailySeries.any { it.tradeCount > 0 } }
    val hasCryptoTrades = remember(cryptoDailySeries) { cryptoDailySeries.any { it.tradeCount > 0 } }
    val showCrypto = userSelectedWallet ?: (!hasIndiaTrades && hasCryptoTrades)

    var showCumulativeCurve by remember { mutableStateOf(true) }
    val series = if (showCrypto) cryptoDailySeries else indiaDailySeries

    var selectedIndex by remember(showCrypto, series.size) {
        mutableStateOf(
            series.indexOfLast { it.tradeCount > 0 }.takeIf { it >= 0 }
                ?: (series.lastIndex.takeIf { it >= 0 })
        )
    }

    var revealTrigger by remember(showCrypto) { mutableStateOf(0f) }
    LaunchedEffect(showCrypto, series.size) {
        revealTrigger = 0f
        revealTrigger = 1f
    }
    val barRevealProgress by animateFloatAsState(
        targetValue = revealTrigger,
        animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
        label = "pnl_bar_reveal"
    )

    val cumulativeSeries = remember(series) {
        var running = BigDecimal.ZERO
        series.map { pt ->
            running = running.add(pt.netPnl)
            running
        }
    }

    val total30d = remember(cumulativeSeries) {
        cumulativeSeries.lastOrNull() ?: BigDecimal.ZERO
    }
    val activeDays = remember(series) { series.count { it.tradeCount > 0 } }
    val greenDays = remember(series) { series.count { it.netPnl > BigDecimal.ZERO } }
    val redDays = remember(series) { series.count { it.netPnl < BigDecimal.ZERO } }
    val winDayRate = if (activeDays == 0) 0 else (greenDays * 100) / activeDays
    val bestDay = remember(series) { series.maxByOrNull { it.netPnl }?.netPnl ?: BigDecimal.ZERO }
    val worstDay = remember(series) { series.minByOrNull { it.netPnl }?.netPnl ?: BigDecimal.ZERO }
    val avgActiveDay = remember(total30d, activeDays) {
        if (activeDays == 0) BigDecimal.ZERO
        else total30d.divide(BigDecimal(activeDays), 2, RoundingMode.HALF_UP)
    }

    fun formatMoney(v: BigDecimal, signed: Boolean = true): String =
        if (showCrypto) RiskMath.formatPortfolioUsd(v, signed = signed)
        else RiskMath.formatPortfolioInr(v, signed = signed)

    fun formatCompactAxis(v: Double): String {
        val sym = if (showCrypto) "$" else "₹"
        val sign = when {
            v > 0.005 -> "+"
            v < -0.005 -> "-"
            else -> ""
        }
        val a = abs(v)
        return when {
            a >= 1e5 && !showCrypto -> "$sign$sym${String.format(Locale.US, "%.1fL", a / 1e5)}"
            a >= 1e6 -> "$sign$sym${String.format(Locale.US, "%.1fM", a / 1e6)}"
            a >= 1e3 -> "$sign$sym${String.format(Locale.US, "%.1fK", a / 1e3)}"
            a >= 1.0 -> "$sign$sym${String.format(Locale.US, "%.0f", a)}"
            else -> "${sym}0"
        }
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("journal_30d_pnl_chart_card"),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, tradingColors.border),
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header Row: Title + 30D Cumulative Net P&L + Wallet Switcher
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.BarChart,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "30-DAY REALIZED P&L",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    val totalColor = if (total30d >= BigDecimal.ZERO) tradingColors.bullish else tradingColors.bearish
                    Text(
                        text = formatMoney(total30d, signed = true),
                        style = MonoPriceMedium,
                        fontWeight = FontWeight.Bold,
                        color = totalColor
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = !showCrypto,
                        onClick = { userSelectedWallet = false },
                        label = { Text("₹ INR", style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.testTag("pnl_chart_wallet_inr"),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.primary
                        )
                    )
                    FilterChip(
                        selected = showCrypto,
                        onClick = { userSelectedWallet = true },
                        label = { Text("$ USD", style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.testTag("pnl_chart_wallet_usd"),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }
            }

            // Legend + Cumulative Curve Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LegendDot(color = tradingColors.bullish, label = "Profit ($greenDays)")
                    LegendDot(color = tradingColors.bearish, label = "Loss ($redDays)")
                    if (showCumulativeCurve) {
                        LegendDot(color = MaterialTheme.colorScheme.primary, label = "Equity")
                    }
                }
                FilterChip(
                    selected = showCumulativeCurve,
                    onClick = { showCumulativeCurve = !showCumulativeCurve },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.ShowChart,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                    },
                    label = { Text("Curve", style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.testTag("pnl_chart_curve_toggle"),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.primary
                    )
                )
            }

            // Selected Day Crosshair Callout
            val inspected = selectedIndex?.let { series.getOrNull(it) }
            val inspectedCumul = selectedIndex?.let { cumulativeSeries.getOrNull(it) }
            if (inspected != null) {
                val dayColor = when {
                    inspected.netPnl > BigDecimal.ZERO -> tradingColors.bullish
                    inspected.netPnl < BigDecimal.ZERO -> tradingColors.bearish
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, tradingColors.border.copy(alpha = 0.6f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("pnl_chart_selected_day_pill")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = inspected.fullDateLabel,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (inspected.tradeCount > 0) {
                                    "${inspected.tradeCount} trade(s) • ${inspected.wins}W / ${inspected.losses}L"
                                } else {
                                    "No closed trades on this day"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = formatMoney(inspected.netPnl, signed = true),
                                style = MonoPriceSmall,
                                color = dayColor,
                                fontWeight = FontWeight.Bold
                            )
                            if (inspectedCumul != null) {
                                Text(
                                    text = "Cumul: ${formatMoney(inspectedCumul, signed = true)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }

            // Interactive 30-Day Vector Bar Chart Canvas with Integrated Aligned Axes
            val maxPos = remember(series) { series.maxOfOrNull { it.netPnl.toDouble() }?.coerceAtLeast(0.0) ?: 0.0 }
            val minNeg = remember(series) { series.minOfOrNull { it.netPnl.toDouble() }?.coerceAtMost(0.0) ?: 0.0 }
            val hasAnyPnl = maxPos > 0.0 || minNeg < 0.0
            val axisTop = if (hasAnyPnl) max(maxPos * 1.12, abs(minNeg) * 0.25) else 100.0
            val axisBottom = if (hasAnyPnl) -max(abs(minNeg) * 1.12, maxPos * 0.25) else -100.0
            val totalSpan = (axisTop - axisBottom).coerceAtLeast(1.0)

            val cumMax = remember(cumulativeSeries) {
                cumulativeSeries.maxOfOrNull { it.toDouble() }?.coerceAtLeast(0.0) ?: 0.0
            }
            val cumMin = remember(cumulativeSeries) {
                cumulativeSeries.minOfOrNull { it.toDouble() }?.coerceAtMost(0.0) ?: 0.0
            }
            val cumSpan = max(cumMax - cumMin, 1.0)

            val bullColor = tradingColors.bullish
            val bearColor = tradingColors.bearish
            val gridColor = tradingColors.border
            val zeroLineColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
            val emptyBarColor = MaterialTheme.colorScheme.surfaceContainerHighest
            val primaryColor = MaterialTheme.colorScheme.primary
            val axisTextColor = MaterialTheme.colorScheme.onSurfaceVariant

            val axisLabelStyle = TextStyle(
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                color = axisTextColor
            )
            val topLabelText = formatCompactAxis(axisTop)
            val zeroLabelText = if (showCrypto) "$0" else "₹0"
            val bottomLabelText = formatCompactAxis(axisBottom)

            val yAxisWidthPx = remember(topLabelText, bottomLabelText) { 46.dp }

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(190.dp)
                    .semantics {
                        contentDescription = "30-day daily realized P&L bar chart. 30-day net total is ${formatMoney(total30d, signed = true)} across $activeDays active trading days."
                    }
                    .testTag("journal_30d_pnl_canvas")
                    .pointerInput(series) {
                        val yAxisPx = 46.dp.toPx()
                        fun updateSelection(xPos: Float) {
                            val plotW = (size.width.toFloat() - yAxisPx).coerceAtLeast(1f)
                            if (series.isNotEmpty()) {
                                val relX = (xPos - yAxisPx).coerceIn(0f, plotW)
                                val idx = ((relX / plotW) * series.size)
                                    .toInt()
                                    .coerceIn(0, series.lastIndex)
                                selectedIndex = idx
                            }
                        }
                        detectTapGestures { offset -> updateSelection(offset.x) }
                    }
                    .pointerInput(series) {
                        val yAxisPx = 46.dp.toPx()
                        detectHorizontalDragGestures { change, _ ->
                            change.consume()
                            val plotW = (size.width.toFloat() - yAxisPx).coerceAtLeast(1f)
                            if (series.isNotEmpty()) {
                                val relX = (change.position.x - yAxisPx).coerceIn(0f, plotW)
                                val idx = ((relX / plotW) * series.size)
                                    .toInt()
                                    .coerceIn(0, series.lastIndex)
                                selectedIndex = idx
                            }
                        }
                    }
            ) {
                val leftAxisW = yAxisWidthPx.toPx()
                val padTop = 10.dp.toPx()
                val padBottom = 22.dp.toPx()
                val plotLeft = leftAxisW
                val plotRight = size.width - 4.dp.toPx()
                val plotW = (plotRight - plotLeft).coerceAtLeast(1f)
                val plotH = (size.height - padTop - padBottom).coerceAtLeast(1f)
                val zeroY = padTop + ((axisTop / totalSpan) * plotH).toFloat()

                val dashEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 5f), 0f)

                // Top, mid-positive, zero, and bottom gridlines
                drawLine(
                    color = gridColor.copy(alpha = 0.7f),
                    start = Offset(plotLeft, padTop),
                    end = Offset(plotRight, padTop),
                    strokeWidth = 1f,
                    pathEffect = dashEffect
                )
                drawLine(
                    color = gridColor.copy(alpha = 0.7f),
                    start = Offset(plotLeft, padTop + plotH),
                    end = Offset(plotRight, padTop + plotH),
                    strokeWidth = 1f,
                    pathEffect = dashEffect
                )
                drawLine(
                    color = zeroLineColor,
                    start = Offset(plotLeft, zeroY),
                    end = Offset(plotRight, zeroY),
                    strokeWidth = 1.5f
                )

                // Pixel-aligned Y-axis tick labels
                fun drawYLabel(text: String, centerY: Float, tint: Color) {
                    val measured = textMeasurer.measure(text, style = axisLabelStyle.copy(color = tint))
                    val tx = (plotLeft - measured.size.width - 6.dp.toPx()).coerceAtLeast(0f)
                    val ty = (centerY - measured.size.height / 2f).coerceIn(0f, size.height - measured.size.height)
                    drawText(measured, topLeft = Offset(tx, ty))
                }
                drawYLabel(topLabelText, padTop, bullColor)
                if (abs(zeroY - padTop) > 14.dp.toPx() && abs((padTop + plotH) - zeroY) > 14.dp.toPx()) {
                    drawYLabel(zeroLabelText, zeroY, axisTextColor)
                }
                drawYLabel(bottomLabelText, padTop + plotH, bearColor)

                if (series.isNotEmpty()) {
                    val slotW = plotW / series.size.toFloat()
                    val barW = (slotW * 0.68f).coerceAtLeast(3f)
                    val gap = (slotW - barW) / 2f
                    val corner = CornerRadius(3.dp.toPx(), 3.dp.toPx())

                    series.forEachIndexed { idx, pt ->
                        val slotLeft = plotLeft + idx * slotW
                        val left = slotLeft + gap
                        val centerX = slotLeft + slotW / 2f
                        val v = pt.netPnl.toDouble()
                        val isSelected = selectedIndex == idx

                        if (isSelected) {
                            // Subtle vertical crosshair highlight column
                            drawRect(
                                color = primaryColor.copy(alpha = 0.10f),
                                topLeft = Offset(slotLeft, padTop),
                                size = Size(slotW, plotH)
                            )
                            drawLine(
                                color = primaryColor.copy(alpha = 0.45f),
                                start = Offset(centerX, padTop),
                                end = Offset(centerX, padTop + plotH),
                                strokeWidth = 1.dp.toPx(),
                                pathEffect = dashEffect
                            )
                        }

                        if (abs(v) < 0.005) {
                            drawRoundRect(
                                color = if (isSelected) primaryColor else emptyBarColor,
                                topLeft = Offset(left, zeroY - 1.5.dp.toPx()),
                                size = Size(barW, 3.dp.toPx()),
                                cornerRadius = corner
                            )
                        } else if (v > 0.0) {
                            val rawH = ((v / totalSpan) * plotH).toFloat().coerceAtLeast(4.dp.toPx())
                            val barH = (rawH * barRevealProgress).coerceAtLeast(2.dp.toPx())
                            val topY = zeroY - barH
                            val brush = Brush.verticalGradient(
                                colors = listOf(
                                    if (isSelected) bullColor else bullColor.copy(alpha = 0.92f),
                                    bullColor.copy(alpha = if (isSelected) 0.72f else 0.48f)
                                ),
                                startY = topY,
                                endY = zeroY
                            )
                            drawRoundRect(
                                brush = brush,
                                topLeft = Offset(left, topY),
                                size = Size(barW, barH),
                                cornerRadius = corner
                            )
                            if (isSelected) {
                                drawRoundRect(
                                    color = bullColor,
                                    topLeft = Offset(left, topY),
                                    size = Size(barW, barH),
                                    cornerRadius = corner,
                                    style = Stroke(width = 1.2.dp.toPx())
                                )
                            }
                        } else {
                            val rawH = ((abs(v) / totalSpan) * plotH).toFloat().coerceAtLeast(4.dp.toPx())
                            val barH = (rawH * barRevealProgress).coerceAtLeast(2.dp.toPx())
                            val brush = Brush.verticalGradient(
                                colors = listOf(
                                    bearColor.copy(alpha = if (isSelected) 0.72f else 0.48f),
                                    if (isSelected) bearColor else bearColor.copy(alpha = 0.92f)
                                ),
                                startY = zeroY,
                                endY = zeroY + barH
                            )
                            drawRoundRect(
                                brush = brush,
                                topLeft = Offset(left, zeroY),
                                size = Size(barW, barH),
                                cornerRadius = corner
                            )
                            if (isSelected) {
                                drawRoundRect(
                                    color = bearColor,
                                    topLeft = Offset(left, zeroY),
                                    size = Size(barW, barH),
                                    cornerRadius = corner,
                                    style = Stroke(width = 1.2.dp.toPx())
                                )
                            }
                        }
                    }

                    // Optional Cumulative Equity Curve Overlay
                    if (showCumulativeCurve && hasAnyPnl && cumulativeSeries.size >= 2) {
                        val path = Path()
                        var selectedCurvePt: Offset? = null
                        cumulativeSeries.forEachIndexed { idx, cumVal ->
                            val cx = plotLeft + idx * slotW + slotW / 2f
                            val norm = ((cumVal.toDouble() - cumMin) / cumSpan).toFloat().coerceIn(0f, 1f)
                            val cy = padTop + plotH - (norm * plotH * 0.92f + plotH * 0.04f)
                            if (idx == 0) path.moveTo(cx, cy) else path.lineTo(cx, cy)
                            if (selectedIndex == idx) selectedCurvePt = Offset(cx, cy)
                        }
                        drawPath(
                            path = path,
                            color = primaryColor,
                            style = Stroke(
                                width = 2.dp.toPx(),
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round
                            )
                        )
                        selectedCurvePt?.let { pt ->
                            drawCircle(color = primaryColor.copy(alpha = 0.25f), radius = 6.dp.toPx(), center = pt)
                            drawCircle(color = primaryColor, radius = 3.2.dp.toPx(), center = pt)
                        }
                    }

                    // Pixel-aligned X-Axis Date Labels (Start, -20d, -10d, Today)
                    if (series.size >= 4) {
                        val tickIndices = listOf(0, series.size / 3, (series.size * 2) / 3, series.lastIndex)
                        val labelY = padTop + plotH + 6.dp.toPx()
                        tickIndices.forEachIndexed { posIdx, idx ->
                            val measured = textMeasurer.measure(series[idx].shortDateLabel, style = axisLabelStyle)
                            val centerX = plotLeft + idx * slotW + slotW / 2f
                            val tx = when (posIdx) {
                                0 -> plotLeft
                                tickIndices.lastIndex -> (plotRight - measured.size.width).coerceAtLeast(plotLeft)
                                else -> (centerX - measured.size.width / 2f).coerceIn(plotLeft, plotRight - measured.size.width)
                            }
                            drawText(measured, topLeft = Offset(tx, labelY))
                        }
                    }
                }
            }

            // 4-Metric Performance History Overview Strip
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OverviewMetricTile(
                    label = "WIN DAYS",
                    value = if (activeDays > 0) "$winDayRate% ($greenDays/$activeDays)" else "0%",
                    valueColor = if (winDayRate >= 50) tradingColors.bullish else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                OverviewMetricTile(
                    label = "AVG ACTIVE",
                    value = formatMoney(avgActiveDay, signed = true),
                    valueColor = if (avgActiveDay >= BigDecimal.ZERO) tradingColors.bullish else tradingColors.bearish,
                    modifier = Modifier.weight(1f)
                )
                OverviewMetricTile(
                    label = "BEST DAY",
                    value = formatMoney(bestDay, signed = true),
                    valueColor = tradingColors.bullish,
                    modifier = Modifier.weight(1f)
                )
                OverviewMetricTile(
                    label = "WORST DAY",
                    value = formatMoney(worstDay, signed = true),
                    valueColor = if (worstDay < BigDecimal.ZERO) tradingColors.bearish else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
            }

            if (!hasAnyPnl) {
                Text(
                    text = "Close paper positions to populate daily profit & loss bars and equity history over the last 30 days. Tap or drag across bars to inspect any date.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun OverviewMetricTile(
    label: String,
    value: String,
    valueColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = value,
                style = MonoPriceSmall.copy(fontSize = 11.sp),
                color = valueColor,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
