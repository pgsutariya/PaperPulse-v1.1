package com.example.ui.screens.signals

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ShowChart
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.domain.analysis.StrategyCalibrationConfig
import com.example.domain.analysis.VolatilityDashboardSnapshot
import com.example.domain.analysis.VolatilityDataPoint
import com.example.domain.analysis.VolatilityRegimeBand
import com.example.domain.signal.Timeframe
import com.example.ui.theme.LocalTradingColors
import com.example.ui.theme.MonoPriceMedium
import com.example.ui.theme.MonoPriceSmall
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

data class VolatilityDashboardUiState(
    val selectedSymbol: String = "RELIANCE",
    val selectedTimeframe: Timeframe = Timeframe.M5,
    val availableSymbols: List<String> = listOf("RELIANCE", "TCS", "HDFCBANK", "NIFTY", "BANKNIFTY", "BTCUSDT"),
    val snapshot: VolatilityDashboardSnapshot = VolatilityDashboardSnapshot(),
    val activeCalibration: StrategyCalibrationConfig = StrategyCalibrationConfig(),
    val isLoading: Boolean = false
)

private val VOL_TIMEFRAMES = listOf(Timeframe.M5, Timeframe.M15, Timeframe.H1, Timeframe.D1)
private val VOL_TIME_FMT = DateTimeFormatter.ofPattern("dd MMM HH:mm").withZone(ZoneId.of("Asia/Kolkata"))

@Composable
fun VolatilityDashboardTab(
    state: VolatilityDashboardUiState,
    onSelectSymbol: (String) -> Unit,
    onSelectTimeframe: (Timeframe) -> Unit,
    onRefresh: () -> Unit,
    onUpdateCalibration: (StrategyCalibrationConfig) -> Unit,
    onApplyAutoCalibration: () -> Unit,
    onApplyAndRescan: () -> Unit,
    onOpenBacktester: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tradingColors = LocalTradingColors.current
    val snap = state.snapshot
    var scrubbedIndex by remember(snap.symbol, snap.timeframe, snap.points.size) { mutableStateOf<Int?>(null) }

    val regimeColor = when (snap.regime) {
        VolatilityRegimeBand.SQUEEZE -> tradingColors.violet
        VolatilityRegimeBand.LOW -> Color(0xFF38BDF8)
        VolatilityRegimeBand.NORMAL -> tradingColors.bullish
        VolatilityRegimeBand.ELEVATED -> tradingColors.amber
        VolatilityRegimeBand.EXTREME -> tradingColors.bearish
    }

    LazyColumn(
        modifier = modifier.testTag("volatility_dashboard_tab"),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 1. Instrument & Timeframe Selector Card
        item(key = "vol_selector") {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, tradingColors.border)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Speed,
                                contentDescription = null,
                                tint = regimeColor,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "Real-Time Volatility Dashboard",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = regimeColor.copy(alpha = 0.16f)
                        ) {
                            Text(
                                text = snap.regime.shortLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = regimeColor,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                        IconButton(
                            onClick = onRefresh,
                            enabled = !state.isLoading,
                            modifier = Modifier.size(36.dp).testTag("vol_refresh_button")
                        ) {
                            Icon(Icons.Rounded.Refresh, contentDescription = "Refresh volatility series", modifier = Modifier.size(18.dp))
                        }
                    }

                    // Instrument Picker
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        state.availableSymbols.take(18).forEach { sym ->
                            FilterChip(
                                selected = state.selectedSymbol == sym,
                                onClick = { onSelectSymbol(sym) },
                                label = { Text(sym, style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.testTag("vol_symbol_$sym"),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.primary
                                )
                            )
                        }
                    }

                    // Timeframe Picker
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Interval:",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        VOL_TIMEFRAMES.forEach { tf ->
                            FilterChip(
                                selected = state.selectedTimeframe == tf,
                                onClick = { onSelectTimeframe(tf) },
                                label = { Text(tf.label, style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.testTag("vol_tf_${tf.name.lowercase()}")
                            )
                        }
                    }

                    if (state.isLoading) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }

        // 2. KPI Telemetry Grid
        item(key = "vol_kpis") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                VolKpiCard(
                    label = "ATR (14)",
                    value = String.format(Locale.US, "%.2f", snap.latestAtr14),
                    sub = "${String.format(Locale.US, "%.2f", snap.latestAtr14Percent)}% of LTP",
                    accent = tradingColors.amber,
                    modifier = Modifier.weight(1f)
                )
                VolKpiCard(
                    label = "ATR PCTL",
                    value = "${snap.atrPercentile}%",
                    sub = if (snap.squeezeBarCount > 0) "Squeeze ${snap.squeezeBarCount}b" else snap.regime.shortLabel,
                    accent = regimeColor,
                    modifier = Modifier.weight(1f)
                )
                VolKpiCard(
                    label = "SPREAD RATIO",
                    value = "${String.format(Locale.US, "%.2f", snap.latestSpreadRatio)}x",
                    sub = if (snap.latestAtrSpread >= 0) "Fast > Slow" else "Fast < Slow",
                    accent = if (snap.latestAtrSpread >= 0) tradingColors.bullish else tradingColors.violet,
                    modifier = Modifier.weight(1f)
                )
                VolKpiCard(
                    label = "REALIZED VOL",
                    value = "${String.format(Locale.US, "%.1f", snap.latestRealizedVolPercent)}%",
                    sub = "20-bar Ann.",
                    accent = Color(0xFF38BDF8),
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // 3. Interactive Historical ATR & Volatility Spread Chart Card
        item(key = "vol_chart") {
            val activePoint: VolatilityDataPoint? = scrubbedIndex?.let { snap.points.getOrNull(it) } ?: snap.points.lastOrNull()
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, tradingColors.border),
                modifier = Modifier.testTag("volatility_dashboard_chart")
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Historical ATR & Volatility Spread",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (scrubbedIndex != null) "Scrubbing bar ${scrubbedIndex!! + 1}/${snap.points.size} (tap to reset)" else "Drag or tap chart to inspect bar-level ATR & spread",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (activePoint != null) {
                            Text(
                                text = VOL_TIME_FMT.format(Instant.ofEpochMilli(activePoint.timestampMillis)),
                                style = MonoPriceSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    // Active / Scrubbed bar readout strip
                    if (activePoint != null) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "ATR7 ${fmtPrice(activePoint.atr7)}",
                                    style = MonoPriceSmall,
                                    color = Color(0xFF38BDF8)
                                )
                                Text(
                                    text = "ATR14 ${fmtPrice(activePoint.atr14)}",
                                    style = MonoPriceSmall,
                                    color = tradingColors.amber
                                )
                                Text(
                                    text = "ATR21 ${fmtPrice(activePoint.atr21)}",
                                    style = MonoPriceSmall,
                                    color = tradingColors.violet
                                )
                                Text(
                                    text = "Spd ${if (activePoint.atrSpread >= 0) "+" else ""}${fmtPrice(activePoint.atrSpread)}",
                                    style = MonoPriceSmall,
                                    color = if (activePoint.atrSpread >= 0) tradingColors.bullish else tradingColors.bearish
                                )
                            }
                        }
                    }

                    // Dual synchronized Canvas visualization (Top: ATR 7/14/21, Bottom: Volatility Spread + Squeeze)
                    if (snap.points.size >= 4) {
                        VolatilityAtrAndSpreadCanvas(
                            points = snap.points,
                            scrubbedIndex = scrubbedIndex,
                            onScrubIndex = { scrubbedIndex = it },
                            bullColor = tradingColors.bullish,
                            bearColor = tradingColors.bearish,
                            amberColor = tradingColors.amber,
                            violetColor = tradingColors.violet,
                            gridColor = tradingColors.border,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(248.dp)
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = snap.calibrationNote,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Chart Legend
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        VolLegendDot(Color(0xFF38BDF8), "ATR 7 (Fast)")
                        VolLegendDot(tradingColors.amber, "ATR 14 (Main)")
                        VolLegendDot(tradingColors.violet, "ATR 21 (Slow)")
                        VolLegendDot(tradingColors.bullish, "Expansion Spread (+)")
                        VolLegendDot(tradingColors.bearish, "Compression / Squeeze (-)")
                    }
                }
            }
        }

        // 4. Dynamic Indicator Sensitivity Calibration Card
        item(key = "vol_calibrator") {
            val cal = state.activeCalibration
            val renko = cal.renkoConfig
            val sniper = cal.sniperConfig
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, tradingColors.border),
                modifier = Modifier.testTag("volatility_calibrator_card")
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Tune,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "Dynamic Sensitivity Calibration",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        OutlinedButton(
                            onClick = onApplyAutoCalibration,
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.testTag("volatility_auto_calibrate_btn")
                        ) {
                            Icon(Icons.Rounded.AutoFixHigh, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Auto-Calibrate", style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    // Regime Guidance Note
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = regimeColor.copy(alpha = 0.11f),
                        border = BorderStroke(1.dp, regimeColor.copy(alpha = 0.35f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = snap.calibrationNote,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(10.dp)
                        )
                    }

                    // AI Renko Supertrend sliders
                    Text(
                        text = "AI RENKO SUPERTREND",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = tradingColors.bullish
                    )
                    CalibrationSliderRow(
                        label = "Brick Size Multiplier",
                        valueText = "${String.format(Locale.US, "%.2f", renko.brickSizeMultiplier)}x ATR",
                        value = renko.brickSizeMultiplier.toFloat(),
                        valueRange = 0.30f..2.50f,
                        accent = tradingColors.bullish,
                        onValueChange = { next ->
                            val rounded = (next * 20f).roundToInt() / 20.0
                            onUpdateCalibration(cal.copy(renkoConfig = renko.copy(brickSizeMultiplier = rounded)))
                        }
                    )
                    CalibrationSliderRow(
                        label = "ATR Sensitivity Factor",
                        valueText = "${String.format(Locale.US, "%.1f", renko.atrSensitivity)}x",
                        value = renko.atrSensitivity.toFloat(),
                        valueRange = 1.2f..4.5f,
                        accent = tradingColors.amber,
                        onValueChange = { next ->
                            val rounded = (next * 10f).roundToInt() / 10.0
                            onUpdateCalibration(cal.copy(renkoConfig = renko.copy(atrSensitivity = rounded)))
                        }
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Adaptive Fast/Slow ATR Scaling", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                            Text("Auto-widens bands during volatility spikes", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = renko.adaptiveMode,
                            onCheckedChange = { enabled ->
                                onUpdateCalibration(cal.copy(renkoConfig = renko.copy(adaptiveMode = enabled)))
                            }
                        )
                    }

                    HorizontalDivider(color = tradingColors.border)

                    // Pullback Sniper sliders
                    Text(
                        text = "PULLBACK SNIPER (VWAP + EMA BANDS)",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF38BDF8)
                    )
                    CalibrationSliderRow(
                        label = "Min Confidence Gate",
                        valueText = "${sniper.minConfidenceScore}/100",
                        value = sniper.minConfidenceScore.toFloat(),
                        valueRange = 50f..85f,
                        accent = Color(0xFF38BDF8),
                        onValueChange = { next ->
                            onUpdateCalibration(cal.copy(sniperConfig = sniper.copy(minConfidenceScore = next.roundToInt())))
                        }
                    )
                    CalibrationSliderRow(
                        label = "VWAP / EMA Band Tolerance",
                        valueText = "${String.format(Locale.US, "%.2f", sniper.bandToleranceMultiplier)}x",
                        value = sniper.bandToleranceMultiplier.toFloat(),
                        valueRange = 0.60f..1.80f,
                        accent = tradingColors.violet,
                        onValueChange = { next ->
                            val rounded = (next * 20f).roundToInt() / 20.0
                            onUpdateCalibration(cal.copy(sniperConfig = sniper.copy(bandToleranceMultiplier = rounded)))
                        }
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onApplyAndRescan,
                            modifier = Modifier.weight(1f).testTag("volatility_apply_rescan_btn"),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Apply & Rescan", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                        }
                        Button(
                            onClick = onOpenBacktester,
                            modifier = Modifier.weight(1f).testTag("volatility_open_backtester_btn"),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.ShowChart, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Backtest Settings", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VolatilityAtrAndSpreadCanvas(
    points: List<VolatilityDataPoint>,
    scrubbedIndex: Int?,
    onScrubIndex: (Int?) -> Unit,
    bullColor: Color,
    bearColor: Color,
    amberColor: Color,
    violetColor: Color,
    gridColor: Color,
    modifier: Modifier = Modifier
) {
    val cyanColor = Color(0xFF38BDF8)

    Canvas(
        modifier = modifier
            .pointerInput(points.size) {
                detectTapGestures { offset ->
                    val idx = ((offset.x / size.width.coerceAtLeast(1)) * (points.size - 1))
                        .roundToInt()
                        .coerceIn(0, points.lastIndex)
                    onScrubIndex(if (scrubbedIndex == idx) null else idx)
                }
            }
            .pointerInput(points.size) {
                detectHorizontalDragGestures(
                    onHorizontalDrag = { change, _ ->
                        val idx = ((change.position.x / size.width.coerceAtLeast(1)) * (points.size - 1))
                            .roundToInt()
                            .coerceIn(0, points.lastIndex)
                        onScrubIndex(idx)
                    }
                )
            }
    ) {
        val w = size.width
        val h = size.height
        if (w <= 4f || h <= 4f || points.size < 2) return@Canvas

        val padH = 8.dp.toPx()
        val plotW = (w - padH * 2).coerceAtLeast(1f)
        val topH = h * 0.56f
        val dividerY = topH + 8.dp.toPx()
        val botTop = dividerY + 8.dp.toPx()
        val botH = (h - botTop - 6.dp.toPx()).coerceAtLeast(24f)

        val stepX = plotW / (points.size - 1).coerceAtLeast(1)
        fun xFor(i: Int): Float = padH + i * stepX

        // --- TOP PANEL: Historical ATR (7, 14, 21) ---
        val atrMin = points.minOf { minOf(it.atr7, it.atr14, it.atr21) } * 0.92
        val atrMax = points.maxOf { maxOf(it.atr7, it.atr14, it.atr21) } * 1.06
        val atrRange = (atrMax - atrMin).coerceAtLeast(1e-6)
        fun yAtr(v: Double): Float {
            val frac = ((v - atrMin) / atrRange).toFloat().coerceIn(0f, 1f)
            return topH - frac * (topH - 8.dp.toPx())
        }

        // Subtle horizontal gridlines
        for (g in 0..3) {
            val gy = 8.dp.toPx() + (topH - 8.dp.toPx()) * (g / 3f)
            drawLine(
                color = gridColor.copy(alpha = 0.45f),
                start = Offset(padH, gy),
                end = Offset(w - padH, gy),
                strokeWidth = 1f
            )
        }

        // ATR 14 shaded area fill
        val atr14Line = Path()
        val atr14Fill = Path()
        val atr7Line = Path()
        val atr21Line = Path()

        points.forEachIndexed { i, pt ->
            val x = xFor(i)
            val y14 = yAtr(pt.atr14)
            val y7 = yAtr(pt.atr7)
            val y21 = yAtr(pt.atr21)
            if (i == 0) {
                atr14Line.moveTo(x, y14)
                atr14Fill.moveTo(x, topH)
                atr14Fill.lineTo(x, y14)
                atr7Line.moveTo(x, y7)
                atr21Line.moveTo(x, y21)
            } else {
                atr14Line.lineTo(x, y14)
                atr14Fill.lineTo(x, y14)
                atr7Line.lineTo(x, y7)
                atr21Line.lineTo(x, y21)
            }
        }
        atr14Fill.lineTo(xFor(points.lastIndex), topH)
        atr14Fill.close()

        drawPath(
            path = atr14Fill,
            brush = Brush.verticalGradient(
                colors = listOf(amberColor.copy(alpha = 0.28f), amberColor.copy(alpha = 0.02f)),
                startY = 0f,
                endY = topH
            )
        )
        // ATR 21 dashed slow anchor
        drawPath(
            path = atr21Line,
            color = violetColor.copy(alpha = 0.85f),
            style = Stroke(
                width = 1.6.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f),
                cap = StrokeCap.Round
            )
        )
        // ATR 14 primary curve
        drawPath(
            path = atr14Line,
            color = amberColor,
            style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round)
        )
        // ATR 7 fast responsive curve
        drawPath(
            path = atr7Line,
            color = cyanColor,
            style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round)
        )

        // Divider between top ATR panel and bottom Spread panel
        drawLine(
            color = gridColor.copy(alpha = 0.7f),
            start = Offset(padH, dividerY),
            end = Offset(w - padH, dividerY),
            strokeWidth = 1.2f
        )

        // --- BOTTOM PANEL: Volatility Spread (ATR7 - ATR21) diverging bars + Squeeze markers ---
        val maxAbsSpread = max(
            points.maxOf { abs(it.atrSpread) },
            1e-6
        ) * 1.12
        val zeroY = botTop + botH * 0.5f

        drawLine(
            color = gridColor.copy(alpha = 0.7f),
            start = Offset(padH, zeroY),
            end = Offset(w - padH, zeroY),
            strokeWidth = 1f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
        )

        val barWidth = (stepX * 0.62f).coerceIn(2f, 10.dp.toPx())
        points.forEachIndexed { i, pt ->
            val x = xFor(i)
            val norm = (pt.atrSpread / maxAbsSpread).toFloat().coerceIn(-1f, 1f)
            val barH = abs(norm) * (botH * 0.46f)
            val topY = if (norm >= 0f) zeroY - barH else zeroY
            val barColor = if (norm >= 0f) bullColor.copy(alpha = 0.82f) else bearColor.copy(alpha = 0.82f)

            drawRect(
                color = barColor,
                topLeft = Offset(x - barWidth / 2f, topY),
                size = Size(barWidth, barH.coerceAtLeast(1.5f))
            )

            // Highlight BB-inside-KC volatility squeeze bars with luminous violet dot on zero axis
            if (pt.isSqueeze) {
                drawCircle(
                    color = violetColor,
                    radius = 2.6.dp.toPx(),
                    center = Offset(x, zeroY)
                )
            }
        }

        // Crosshair overlay when scrubbing or highlighting latest bar
        val activeIdx = scrubbedIndex ?: points.lastIndex
        val cx = xFor(activeIdx)
        val activePt = points[activeIdx]
        if (scrubbedIndex != null) {
            drawLine(
                color = Color.White.copy(alpha = 0.55f),
                start = Offset(cx, 4.dp.toPx()),
                end = Offset(cx, h - 4.dp.toPx()),
                strokeWidth = 1.2.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
            )
        }
        drawCircle(
            color = amberColor,
            radius = 4.dp.toPx(),
            center = Offset(cx, yAtr(activePt.atr14))
        )
        drawCircle(
            color = cyanColor,
            radius = 3.2.dp.toPx(),
            center = Offset(cx, yAtr(activePt.atr7))
        )
    }
}

@Composable
private fun VolKpiCard(
    label: String,
    value: String,
    sub: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    val tradingColors = LocalTradingColors.current
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, tradingColors.border)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = value,
                style = MonoPriceMedium,
                color = accent,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
            Text(
                text = sub,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun CalibrationSliderRow(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    accent: Color,
    onValueChange: (Float) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.bodySmall)
            Text(valueText, style = MonoPriceSmall, color = accent, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onValueChange,
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = accent,
                activeTrackColor = accent
            ),
            modifier = Modifier.height(28.dp)
        )
    }
}

@Composable
private fun VolLegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(modifier = Modifier.size(8.dp)) {
            drawCircle(color = color)
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun fmtPrice(v: Double): String = when {
    abs(v) >= 100.0 -> String.format(Locale.US, "%.1f", v)
    abs(v) >= 1.0 -> String.format(Locale.US, "%.2f", v)
    else -> String.format(Locale.US, "%.4f", v)
}
