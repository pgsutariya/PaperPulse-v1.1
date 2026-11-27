package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoGraph
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.domain.engine.RiskMath
import com.example.ui.theme.LocalTradingColors
import com.example.ui.theme.MonoPriceSmall
import java.math.BigDecimal
import java.util.Locale

data class StrategyTagPerformance(
    val tag: String,
    val totalTrades: Int,
    val wins: Int,
    val losses: Int,
    val winRatePercent: Double,
    val indiaNetPnl: BigDecimal,
    val cryptoNetPnl: BigDecimal,
    val hasIndiaTrades: Boolean,
    val hasCryptoTrades: Boolean,
    val profitFactor: Double?
) {
    val combinedNetForSort: Double
        get() = indiaNetPnl.toDouble() + cryptoNetPnl.toDouble() * 85.0
}

/**
 * Interactive per-strategy performance comparison card inside Journal & Ledger.
 * Displays Win Rate, Trade Count, Profit Factor, and Net P&L for every trading style
 * (#Scalp, #TrendFollow, #Breakout, etc.), with 1-tap toggle filtering across the entire Journal.
 */
@Composable
fun StrategyPerformanceBreakdownCard(
    strategies: List<StrategyTagPerformance>,
    selectedTag: String?,
    totalTradeCount: Int,
    onSelectTag: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    val tradingColors = LocalTradingColors.current

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("strategy_performance_breakdown_card"),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, tradingColors.border)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
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
                        imageVector = Icons.Rounded.AutoGraph,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Column {
                        Text(
                            text = "STRATEGY STYLE PERFORMANCE",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (strategies.isEmpty()) {
                                "Tag trades (#Scalp, #TrendFollow, #Breakout) to compare styles"
                            } else {
                                "Tap any strategy below to filter all charts, metrics & trades"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (strategies.isNotEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = "${strategies.size} styles",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                        )
                    }
                }
            }

            if (strategies.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (totalTradeCount == 0) {
                            "Close a paper trade and assign tags like #Scalp, #TrendFollow, or #Breakout to track which trading style generates your highest win rate and profit factor."
                        } else {
                            "Tap '+ Strategy' on any closed trade below to label it as #Scalp, #TrendFollow, or #Breakout and unlock style-by-style performance analytics."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    strategies.forEach { perf ->
                        val isSelected = selectedTag.equals(perf.tag, ignoreCase = true)
                        StrategyPerformanceRow(
                            perf = perf,
                            isSelected = isSelected,
                            onClick = { onSelectTag(if (isSelected) null else perf.tag) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StrategyPerformanceRow(
    perf: StrategyTagPerformance,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val tradingColors = LocalTradingColors.current
    val winColor = when {
        perf.winRatePercent >= 55.0 -> tradingColors.bullish
        perf.winRatePercent >= 40.0 -> tradingColors.amber
        else -> tradingColors.bearish
    }
    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary else tradingColors.border
    val rowBg = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }

    val pfLabel = when {
        perf.profitFactor == null -> "PF --"
        perf.profitFactor.isInfinite() -> "PF ∞"
        else -> String.format(Locale.US, "PF %.2fx", perf.profitFactor)
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .clickable(onClick = onClick)
            .testTag("strategy_perf_row_${perf.tag.lowercase().replace(" ", "_")}"),
        shape = RoundedCornerShape(12.dp),
        color = rowBg,
        border = BorderStroke(if (isSelected) 1.5.dp else 1.dp, borderColor)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
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
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = "Active strategy filter",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Text(
                        text = "#${perf.tag}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${perf.totalTrades}T (${perf.wins}W/${perf.losses}L)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    if (perf.hasIndiaTrades) {
                        val inColor = if (perf.indiaNetPnl >= BigDecimal.ZERO) tradingColors.bullish else tradingColors.bearish
                        Text(
                            text = RiskMath.formatPortfolioInr(perf.indiaNetPnl, signed = true),
                            style = MonoPriceSmall,
                            fontWeight = FontWeight.Bold,
                            color = inColor
                        )
                    }
                    if (perf.hasCryptoTrades) {
                        val crColor = if (perf.cryptoNetPnl >= BigDecimal.ZERO) tradingColors.bullish else tradingColors.bearish
                        Text(
                            text = RiskMath.formatPortfolioUsd(perf.cryptoNetPnl, signed = true),
                            style = MonoPriceSmall,
                            fontWeight = FontWeight.Bold,
                            color = crColor
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                LinearProgressIndicator(
                    progress = { (perf.winRatePercent / 100.0).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier
                        .weight(1f)
                        .height(5.dp)
                        .clip(RoundedCornerShape(999.dp)),
                    color = winColor,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                )
                Text(
                    text = "  ${String.format(Locale.US, "%.0f", perf.winRatePercent)}% Win • $pfLabel",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = winColor
                )
            }
        }
    }
}
