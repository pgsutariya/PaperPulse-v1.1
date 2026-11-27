package com.example.ui.screens.signals

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ShowChart
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.domain.analysis.BacktestTrade
import com.example.domain.analysis.DualStrategyBacktestSnapshot
import com.example.domain.analysis.StrategyBacktestReport
import com.example.domain.signal.SignalDirection
import com.example.domain.signal.SignalStrategy
import com.example.domain.signal.Timeframe
import com.example.ui.theme.LocalTradingColors
import com.example.ui.theme.MonoPriceMedium
import com.example.ui.theme.MonoPriceSmall
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

data class StrategyBacktesterUiState(
    val selectedSymbol: String = "RELIANCE",
    val selectedTimeframe: Timeframe = Timeframe.M5,
    val availableSymbols: List<String> = listOf("RELIANCE", "TCS", "HDFCBANK", "NIFTY", "BANKNIFTY", "BTCUSDT"),
    val snapshot: DualStrategyBacktestSnapshot = DualStrategyBacktestSnapshot(),
    val isRunning: Boolean = false
)

private enum class BacktestLedgerFilter(val label: String) {
    ALL("All Trades"),
    RENKO("AI Renko ST"),
    SNIPER("Pullback Sniper")
}

private val BT_TIMEFRAMES = listOf(Timeframe.M5, Timeframe.M15, Timeframe.H1, Timeframe.D1)
private val BT_DATE_FMT = DateTimeFormatter.ofPattern("dd MMM HH:mm").withZone(ZoneId.of("Asia/Kolkata"))

@Composable
fun StrategyBacktesterSection(
    state: StrategyBacktesterUiState,
    onSelectSymbol: (String) -> Unit,
    onSelectTimeframe: (Timeframe) -> Unit,
    onRunBacktest: () -> Unit,
    onOpenVolatilityCalibrator: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tradingColors = LocalTradingColors.current
    val snap = state.snapshot
    val renkoCfg = snap.calibration.renkoConfig
    val sniperCfg = snap.calibration.sniperConfig
    var ledgerFilter by remember { mutableStateOf(BacktestLedgerFilter.ALL) }

    val visibleTrades = remember(snap, ledgerFilter) {
        when (ledgerFilter) {
            BacktestLedgerFilter.ALL -> (snap.renkoReport.trades + snap.sniperReport.trades)
                .sortedByDescending { it.exitTimeMillis }
            BacktestLedgerFilter.RENKO -> snap.renkoReport.trades
            BacktestLedgerFilter.SNIPER -> snap.sniperReport.trades
        }
    }

    LazyColumn(
        modifier = modifier.testTag("strategy_backtester_section"),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 1. Controls & Active Calibration Summary
        item(key = "bt_header") {
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
                                imageVector = Icons.AutoMirrored.Rounded.ShowChart,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Column {
                                Text(
                                    text = "Strategy Backtester",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "${snap.barsAnalyzed} historical ${state.selectedTimeframe.label} bars • Walk-forward simulation",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        OutlinedButton(
                            onClick = onOpenVolatilityCalibrator,
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier.testTag("bt_open_vol_calibrator_btn")
                        ) {
                            Icon(Icons.Rounded.Speed, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Calibrate", style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    // Active Settings Pills
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "AI Renko ST: Brick ${String.format(Locale.US, "%.2f", renkoCfg.brickSizeMultiplier)}x • ATR ${String.format(Locale.US, "%.1f", renkoCfg.atrSensitivity)}x • ${if (renkoCfg.adaptiveMode) "Adaptive" else "Fixed"}",
                                style = MaterialTheme.typography.labelSmall,
                                color = tradingColors.bullish,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Pullback Sniper: Min Conf ${sniperCfg.minConfidenceScore}/100 • VWAP/EMA Band ${String.format(Locale.US, "%.2f", sniperCfg.bandToleranceMultiplier)}x",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF38BDF8),
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    // Symbol Chips
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
                                modifier = Modifier.testTag("bt_symbol_$sym")
                            )
                        }
                    }

                    // Timeframe + Run CTA
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BT_TIMEFRAMES.forEach { tf ->
                            FilterChip(
                                selected = state.selectedTimeframe == tf,
                                onClick = { onSelectTimeframe(tf) },
                                label = { Text(tf.label, style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.testTag("bt_tf_${tf.name.lowercase()}")
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        Button(
                            onClick = onRunBacktest,
                            enabled = !state.isRunning,
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            modifier = Modifier.testTag("bt_run_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(2.dp))
                            Text("Run", style = MaterialTheme.typography.labelMedium)
                        }
                    }

                    if (state.isRunning) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }

        // 2. Combined Hypothetical Performance Banner
        item(key = "bt_combined") {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, tradingColors.border)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    BtMetricCol(
                        label = "COMBINED WIN",
                        value = "${String.format(Locale.US, "%.1f", snap.combinedWinRatePercent)}%",
                        color = if (snap.combinedWinRatePercent >= 55.0) tradingColors.bullish else tradingColors.amber
                    )
                    BtMetricCol(
                        label = "PROFIT FACTOR",
                        value = String.format(Locale.US, "%.2f", snap.combinedProfitFactor),
                        color = if (snap.combinedProfitFactor >= 1.4) tradingColors.bullish else if (snap.combinedProfitFactor >= 1.0) tradingColors.amber else tradingColors.bearish
                    )
                    BtMetricCol(
                        label = "NET RETURN",
                        value = "${if (snap.combinedNetReturnPercent >= 0) "+" else ""}${String.format(Locale.US, "%.2f", snap.combinedNetReturnPercent)}%",
                        color = if (snap.combinedNetReturnPercent >= 0) tradingColors.bullish else tradingColors.bearish
                    )
                    BtMetricCol(
                        label = "TRADES",
                        value = snap.combinedTrades.toString(),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // 3. Side-by-Side Scorecards: AI Renko Supertrend vs Pullback Sniper
        item(key = "bt_scorecards") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StrategyReportCard(
                    report = snap.renkoReport,
                    accent = tradingColors.bullish,
                    modifier = Modifier.weight(1f).testTag("bt_renko_scorecard")
                )
                StrategyReportCard(
                    report = snap.sniperReport,
                    accent = Color(0xFF38BDF8),
                    modifier = Modifier.weight(1f).testTag("bt_sniper_scorecard")
                )
            }
        }

        // 4. Dual-Strategy Equity Curve Visualization
        item(key = "bt_equity_curve") {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, tradingColors.border),
                modifier = Modifier.testTag("backtester_equity_curve_chart")
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
                        Text(
                            text = "Hypothetical Equity Curve (Base 100)",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                text = "Renko ${String.format(Locale.US, "%.1f", snap.renkoReport.equityCurve.lastOrNull() ?: 100.0)}",
                                style = MonoPriceSmall,
                                color = tradingColors.bullish
                            )
                            Text(
                                text = "Sniper ${String.format(Locale.US, "%.1f", snap.sniperReport.equityCurve.lastOrNull() ?: 100.0)}",
                                style = MonoPriceSmall,
                                color = Color(0xFF38BDF8)
                            )
                        }
                    }

                    DualEquityCurveCanvas(
                        renkoEquity = snap.renkoReport.equityCurve,
                        sniperEquity = snap.sniperReport.equityCurve,
                        renkoColor = tradingColors.bullish,
                        sniperColor = Color(0xFF38BDF8),
                        gridColor = tradingColors.border,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(148.dp)
                    )
                }
            }
        }

        // 5. Simulated Trade Ledger Filter
        item(key = "bt_ledger_header") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BacktestLedgerFilter.entries.forEach { f ->
                    FilterChip(
                        selected = ledgerFilter == f,
                        onClick = { ledgerFilter = f },
                        label = { Text(f.label, style = MaterialTheme.typography.labelSmall) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }
            }
        }

        if (visibleTrades.isEmpty()) {
            item(key = "bt_empty_trades") {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, tradingColors.border),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "No simulated setups triggered under the current sensitivity parameters on ${state.selectedSymbol} (${state.selectedTimeframe.label}). Try lowering Pullback Sniper Min Confidence or adjusting Renko Brick Size in the Volatility calibrator.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            }
        } else {
            items(visibleTrades.take(25), key = { it.id }) { trade ->
                BacktestTradeRowCard(trade = trade)
            }
        }
    }
}

@Composable
private fun StrategyReportCard(
    report: StrategyBacktestReport,
    accent: Color,
    modifier: Modifier = Modifier
) {
    val tradingColors = LocalTradingColors.current
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.45f))
    ) {
        Column(
            modifier = Modifier.padding(11.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = report.strategyName,
                style = MaterialTheme.typography.labelMedium,
                color = accent,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Column {
                    Text("WIN RATE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = if (report.totalTrades > 0) "${String.format(Locale.US, "%.1f", report.winRatePercent)}%" else "—",
                        style = MonoPriceMedium,
                        color = if (report.winRatePercent >= 55.0) tradingColors.bullish else tradingColors.amber,
                        fontWeight = FontWeight.Bold
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("PROFIT FACTOR", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = if (report.totalTrades > 0) String.format(Locale.US, "%.2f", report.profitFactor) else "—",
                        style = MonoPriceMedium,
                        color = if (report.profitFactor >= 1.3) tradingColors.bullish else if (report.profitFactor >= 1.0) tradingColors.amber else tradingColors.bearish,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            HorizontalDivider(color = tradingColors.border)
            BtStatLine("Trades (W/L)", "${report.totalTrades} (${report.wins}W / ${report.losses}L)")
            BtStatLine(
                "Net Return",
                "${if (report.netReturnPercent >= 0) "+" else ""}${String.format(Locale.US, "%.2f", report.netReturnPercent)}%",
                valueColor = if (report.netReturnPercent >= 0) tradingColors.bullish else tradingColors.bearish
            )
            BtStatLine(
                "Max Drawdown",
                "-${String.format(Locale.US, "%.2f", report.maxDrawdownPercent)}%",
                valueColor = tradingColors.bearish
            )
            BtStatLine(
                "Expectancy / R:R",
                "${if (report.expectancyPercent >= 0) "+" else ""}${String.format(Locale.US, "%.2f", report.expectancyPercent)}% • ${String.format(Locale.US, "%.1f", report.avgRiskReward)}R"
            )
        }
    }
}

@Composable
private fun BtStatLine(label: String, value: String, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MonoPriceSmall, color = valueColor, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun BtMetricCol(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MonoPriceMedium, color = color, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun DualEquityCurveCanvas(
    renkoEquity: List<Double>,
    sniperEquity: List<Double>,
    renkoColor: Color,
    sniperColor: Color,
    gridColor: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        if (w <= 4f || h <= 4f) return@Canvas

        val padX = 10.dp.toPx()
        val padY = 10.dp.toPx()
        val plotW = (w - padX * 2).coerceAtLeast(1f)
        val plotH = (h - padY * 2).coerceAtLeast(1f)

        val allVals = (renkoEquity + sniperEquity + listOf(98.5, 100.0, 101.5))
        val minVal = allVals.minOrNull() ?: 95.0
        val maxVal = allVals.maxOrNull() ?: 105.0
        val range = (maxVal - minVal).coerceAtLeast(0.5)

        fun yFor(v: Double): Float {
            val frac = ((v - minVal) / range).toFloat().coerceIn(0f, 1f)
            return padY + (1f - frac) * plotH
        }

        // Baseline 100.0 dashed line
        val baseY = yFor(100.0)
        drawLine(
            color = gridColor.copy(alpha = 0.85f),
            start = Offset(padX, baseY),
            end = Offset(w - padX, baseY),
            strokeWidth = 1.2f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f)
        )

        fun drawCurve(series: List<Double>, color: Color, fillArea: Boolean) {
            if (series.size < 2) return
            val path = Path()
            val fillPath = Path()
            val step = plotW / (series.size - 1).coerceAtLeast(1)
            series.forEachIndexed { idx, v ->
                val x = padX + idx * step
                val y = yFor(v)
                if (idx == 0) {
                    path.moveTo(x, y)
                    fillPath.moveTo(x, baseY)
                    fillPath.lineTo(x, y)
                } else {
                    path.lineTo(x, y)
                    fillPath.lineTo(x, y)
                }
            }
            fillPath.lineTo(padX + (series.size - 1) * step, baseY)
            fillPath.close()

            if (fillArea) {
                drawPath(
                    path = fillPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(color.copy(alpha = 0.22f), color.copy(alpha = 0.01f)),
                        startY = padY,
                        endY = h - padY
                    )
                )
            }
            drawPath(
                path = path,
                color = color,
                style = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round)
            )
            val lastX = padX + (series.size - 1) * step
            val lastY = yFor(series.last())
            drawCircle(color = color, radius = 3.8.dp.toPx(), center = Offset(lastX, lastY))
        }

        drawCurve(renkoEquity, renkoColor, fillArea = true)
        drawCurve(sniperEquity, sniperColor, fillArea = false)
    }
}

@Composable
private fun BacktestTradeRowCard(trade: BacktestTrade) {
    val tradingColors = LocalTradingColors.current
    val pnlColor = if (trade.isWin) tradingColors.bullish else tradingColors.bearish
    val stratColor = if (trade.strategy == SignalStrategy.AI_RENKO_SUPERTREND) tradingColors.bullish else Color(0xFF38BDF8)

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, tradingColors.border),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(6.dp), color = stratColor.copy(alpha = 0.16f)) {
                        Text(
                            text = trade.strategy.shortLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = stratColor,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (trade.direction == SignalDirection.LONG) tradingColors.bullishContainer else tradingColors.bearishContainer
                    ) {
                        Text(
                            text = trade.direction.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (trade.direction == SignalDirection.LONG) tradingColors.bullish else tradingColors.bearish,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Text(
                        text = trade.exitReason.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = "${if (trade.pnlPercent >= 0) "+" else ""}${String.format(Locale.US, "%.2f", trade.pnlPercent)}% (${String.format(Locale.US, "%+.1f", trade.rMultiple)}R)",
                    style = MonoPriceSmall,
                    color = pnlColor,
                    fontWeight = FontWeight.Bold
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${fmtBtPrice(trade.entryPrice)} → ${fmtBtPrice(trade.exitPrice)} • ${trade.barsHeld}b",
                    style = MonoPriceSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = BT_DATE_FMT.format(Instant.ofEpochMilli(trade.exitTimeMillis)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = trade.convictionLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun fmtBtPrice(v: Double): String = when {
    abs(v) >= 1000.0 -> String.format(Locale.US, "%,.2f", v)
    abs(v) >= 1.0 -> String.format(Locale.US, "%.2f", v)
    else -> String.format(Locale.US, "%.4f", v)
}
