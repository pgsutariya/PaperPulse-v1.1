package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Analytics
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.engine.RiskMath
import com.example.ui.theme.LocalTradingColors
import com.example.ui.theme.MonoPriceMedium
import com.example.ui.theme.MonoPriceSmall
import java.math.BigDecimal
import java.util.Locale

data class TradeSummaryReportMetrics(
    val totalTrades: Int = 0,
    val winningTrades: Int = 0,
    val losingTrades: Int = 0,
    val breakEvenTrades: Int = 0,
    val winRatePercent: Double = 0.0,
    val avgPnlPerTrade: BigDecimal = BigDecimal.ZERO,
    val avgWin: BigDecimal = BigDecimal.ZERO,
    val avgLoss: BigDecimal = BigDecimal.ZERO,
    val profitFactor: Double? = null,
    val payoffRatio: Double? = null,
    val largestWin: BigDecimal = BigDecimal.ZERO,
    val largestLoss: BigDecimal = BigDecimal.ZERO,
    val maxWinStreak: Int = 0,
    val maxLossStreak: Int = 0,
    val totalNetPnl: BigDecimal = BigDecimal.ZERO,
    val totalCharges: BigDecimal = BigDecimal.ZERO
)

@Composable
fun TradeSummaryReportCard(
    indiaReport: TradeSummaryReportMetrics,
    cryptoReport: TradeSummaryReportMetrics,
    modifier: Modifier = Modifier
) {
    val tradingColors = LocalTradingColors.current

    var userSelectedWallet by remember { mutableStateOf<Boolean?>(null) }
    val showCrypto = userSelectedWallet ?: (indiaReport.totalTrades == 0 && cryptoReport.totalTrades > 0)
    val report = if (showCrypto) cryptoReport else indiaReport

    fun formatMoney(value: BigDecimal, signed: Boolean = true): String =
        if (showCrypto) RiskMath.formatPortfolioUsd(value, signed = signed)
        else RiskMath.formatPortfolioInr(value, signed = signed)

    val profitFactorDisplay = when (val pf = report.profitFactor) {
        null -> "—"
        Double.POSITIVE_INFINITY -> "∞"
        else -> String.format(Locale.US, "%.2f", pf)
    }
    val profitFactorColor = when (val pf = report.profitFactor) {
        null -> MaterialTheme.colorScheme.onSurfaceVariant
        Double.POSITIVE_INFINITY -> tradingColors.bullish
        else -> when {
            pf >= 1.5 -> tradingColors.bullish
            pf >= 1.0 -> tradingColors.amber
            else -> tradingColors.bearish
        }
    }

    val winRateColor = when {
        report.totalTrades == 0 -> MaterialTheme.colorScheme.onSurfaceVariant
        report.winRatePercent >= 50.0 -> tradingColors.bullish
        report.winRatePercent >= 40.0 -> tradingColors.amber
        else -> tradingColors.bearish
    }

    val avgPnlColor = when {
        report.totalTrades == 0 -> MaterialTheme.colorScheme.onSurfaceVariant
        report.avgPnlPerTrade >= BigDecimal.ZERO -> tradingColors.bullish
        else -> tradingColors.bearish
    }

    val payoffDisplay = when (val pr = report.payoffRatio) {
        null -> "—"
        Double.POSITIVE_INFINITY -> "∞"
        else -> String.format(Locale.US, "%.2fx", pr)
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("journal_trade_summary_report_card")
            .semantics {
                contentDescription = "Trade Summary Report: ${report.totalTrades} trades, win rate ${String.format(Locale.US, "%.1f", report.winRatePercent)} percent, profit factor $profitFactorDisplay"
            },
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, tradingColors.border),
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header + Wallet Toggle
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
                            imageVector = Icons.Rounded.Analytics,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "TRADE SUMMARY REPORT",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = if (report.totalTrades == 0) {
                            "No closed trades in ${if (showCrypto) "Crypto ($)" else "India (₹)"}"
                        } else {
                            "${report.totalTrades} closed • ${report.winningTrades}W / ${report.losingTrades}L" +
                                if (report.breakEvenTrades > 0) " / ${report.breakEvenTrades}BE" else ""
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = !showCrypto,
                        onClick = { userSelectedWallet = false },
                        label = { Text("₹ INR", style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.testTag("summary_report_wallet_inr"),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.primary
                        )
                    )
                    FilterChip(
                        selected = showCrypto,
                        onClick = { userSelectedWallet = true },
                        label = { Text("$ USD", style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.testTag("summary_report_wallet_usd"),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }
            }

            // Win / Loss Distribution Bar
            if (report.totalTrades > 0) {
                val winFraction = (report.winningTrades.toFloat() / report.totalTrades.toFloat()).coerceIn(0f, 1f)
                val lossFraction = (report.losingTrades.toFloat() / report.totalTrades.toFloat()).coerceIn(0f, 1f)
                val beFraction = (1f - winFraction - lossFraction).coerceAtLeast(0f)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .testTag("summary_report_win_loss_bar")
                ) {
                    if (winFraction > 0f) {
                        Box(
                            modifier = Modifier
                                .weight(winFraction)
                                .fillMaxHeight()
                                .background(tradingColors.bullish)
                        )
                    }
                    if (beFraction > 0f) {
                        Box(
                            modifier = Modifier
                                .weight(beFraction)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.outline)
                        )
                    }
                    if (lossFraction > 0f) {
                        Box(
                            modifier = Modifier
                                .weight(lossFraction)
                                .fillMaxHeight()
                                .background(tradingColors.bearish)
                        )
                    }
                }
            }

            // Primary Headline KPI Row: Win Rate | Avg P&L / Trade | Profit Factor
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PrimaryReportTile(
                    label = "WIN RATE",
                    value = if (report.totalTrades == 0) "—" else "${String.format(Locale.US, "%.1f", report.winRatePercent)}%",
                    valueColor = winRateColor,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("summary_report_win_rate")
                )
                PrimaryReportTile(
                    label = "AVG P&L / TRADE",
                    value = if (report.totalTrades == 0) "—" else formatMoney(report.avgPnlPerTrade, signed = true),
                    valueColor = avgPnlColor,
                    modifier = Modifier
                        .weight(1.25f)
                        .testTag("summary_report_avg_pnl")
                )
                PrimaryReportTile(
                    label = "PROFIT FACTOR",
                    value = profitFactorDisplay,
                    valueColor = profitFactorColor,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("summary_report_profit_factor")
                )
            }

            // Secondary Breakdown Grid: Avg Win / Avg Loss & Largest Win / Largest Loss
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SecondaryReportTile(
                    label = "AVG WIN",
                    value = if (report.winningTrades == 0) "—" else formatMoney(report.avgWin, signed = true),
                    valueColor = if (report.winningTrades > 0) tradingColors.bullish else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                SecondaryReportTile(
                    label = "AVG LOSS",
                    value = if (report.losingTrades == 0) "—" else formatMoney(report.avgLoss, signed = true),
                    valueColor = if (report.losingTrades > 0) tradingColors.bearish else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SecondaryReportTile(
                    label = "LARGEST WIN",
                    value = if (report.winningTrades == 0) "—" else formatMoney(report.largestWin, signed = true),
                    valueColor = if (report.winningTrades > 0) tradingColors.bullish else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                SecondaryReportTile(
                    label = "LARGEST LOSS",
                    value = if (report.losingTrades == 0) "—" else formatMoney(report.largestLoss, signed = true),
                    valueColor = if (report.losingTrades > 0) tradingColors.bearish else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
            }

            HorizontalDivider(color = tradingColors.border)

            // Footer Analytics Strip: Payoff Ratio, Streaks, Net P&L & Fees
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Payoff (R:R): $payoffDisplay",
                    style = MonoPriceSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Streaks: ${report.maxWinStreak}W / ${report.maxLossStreak}L",
                    style = MonoPriceSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (report.totalTrades == 0) {
                Text(
                    text = "Close positions to generate live Win Rate, Average P&L per Trade, Profit Factor, Payoff Ratio, and streak metrics from your trade journal.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun PrimaryReportTile(
    label: String,
    value: String,
    valueColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
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
                style = MonoPriceMedium.copy(fontSize = 14.sp),
                color = valueColor,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SecondaryReportTile(
    label: String,
    value: String,
    valueColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, LocalTradingColors.current.border.copy(alpha = 0.55f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = value,
                style = MonoPriceSmall,
                color = valueColor,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
