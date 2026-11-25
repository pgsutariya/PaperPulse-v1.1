package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.domain.analytics.RiskManagementScorecard
import com.example.domain.engine.RiskMath
import com.example.ui.theme.LocalTradingColors
import com.example.ui.theme.MonoPriceMedium
import com.example.ui.theme.MonoPriceSmall
import java.math.BigDecimal
import java.util.Locale

@Composable
fun RiskManagementScorecardCard(
    indiaScorecard: RiskManagementScorecard,
    cryptoScorecard: RiskManagementScorecard,
    modifier: Modifier = Modifier
) {
    val tradingColors = LocalTradingColors.current

    var userSelectedWallet by remember { mutableStateOf<Boolean?>(null) }
    val showCrypto = userSelectedWallet
        ?: (indiaScorecard.totalTrades == 0 && cryptoScorecard.totalTrades > 0)
    val card = if (showCrypto) cryptoScorecard else indiaScorecard

    fun formatMoney(v: BigDecimal, signed: Boolean = true): String =
        if (showCrypto) RiskMath.formatPortfolioUsd(v, signed = signed)
        else RiskMath.formatPortfolioInr(v, signed = signed)

    val (gradeBg, gradeFg) = when (card.grade) {
        "A+", "A" -> tradingColors.bullishContainer to tradingColors.bullish
        "B" -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.primary
        "C" -> tradingColors.amberContainer to tradingColors.amber
        "D" -> tradingColors.bearishContainer to tradingColors.bearish
        else -> MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("journal_risk_scorecard_card"),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, tradingColors.border),
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header: Title + Risk Grade Badge + INR/USD toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Shield,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "RISK MANAGEMENT SCORECARD",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = gradeBg,
                            modifier = Modifier.testTag("scorecard_grade_badge")
                        ) {
                            Text(
                                text = "Grade ${card.grade}",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = gradeFg,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Text(
                        text = if (card.totalTrades > 0) {
                            "${card.totalTrades} trades • ${card.distinctTradingDays} trading day(s)"
                        } else {
                            "Calmar, Sharpe & Risk-to-Reward analytics"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(
                        selected = !showCrypto,
                        onClick = { userSelectedWallet = false },
                        label = { Text("₹ INR", style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.testTag("scorecard_wallet_inr"),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.primary
                        )
                    )
                    FilterChip(
                        selected = showCrypto,
                        onClick = { userSelectedWallet = true },
                        label = { Text("$ USD", style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.testTag("scorecard_wallet_usd"),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }
            }

            if (card.totalTrades == 0) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Close at least 2 trades to compute Calmar Ratio, annualized Sharpe Ratio, Average Risk-to-Reward, and Max Drawdown for this wallet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            } else {
                // Primary 3-column Risk Ratios Grid: Calmar, Sharpe, Avg R:R
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val calmarDisplay = when {
                        card.calmarRatio == null -> "—"
                        card.calmarRatio.isInfinite() -> "∞"
                        else -> String.format(Locale.US, "%.2f", card.calmarRatio)
                    }
                    val calmarColor = when {
                        card.calmarRatio == null -> MaterialTheme.colorScheme.onSurfaceVariant
                        card.calmarRatio.isInfinite() || card.calmarRatio >= 1.5 -> tradingColors.bullish
                        card.calmarRatio >= 0.5 -> tradingColors.amber
                        else -> tradingColors.bearish
                    }
                    val calmarLabel = when {
                        card.calmarRatio == null -> "Pending"
                        card.calmarRatio.isInfinite() -> "Zero DD"
                        card.calmarRatio >= 2.5 -> "Strong"
                        card.calmarRatio >= 1.0 -> "Healthy"
                        card.calmarRatio >= 0.0 -> "Watch DD"
                        else -> "Negative"
                    }
                    val calmarProgress = when {
                        card.calmarRatio == null -> 0f
                        card.calmarRatio.isInfinite() -> 1f
                        else -> (card.calmarRatio / 3.0).toFloat().coerceIn(0f, 1f)
                    }

                    RiskRatioTile(
                        title = "CALMAR",
                        value = calmarDisplay,
                        statusLabel = calmarLabel,
                        accentColor = calmarColor,
                        progress = calmarProgress,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("scorecard_calmar_ratio")
                    )

                    val sharpeDisplay = when {
                        card.sharpeRatio == null -> "—"
                        else -> String.format(Locale.US, "%.2f", card.sharpeRatio)
                    }
                    val sharpeColor = when {
                        card.sharpeRatio == null -> MaterialTheme.colorScheme.onSurfaceVariant
                        card.sharpeRatio >= 1.2 -> tradingColors.bullish
                        card.sharpeRatio >= 0.4 -> tradingColors.amber
                        else -> tradingColors.bearish
                    }
                    val sharpeLabel = when {
                        card.sharpeRatio == null -> "Need 2+"
                        card.sharpeRatio >= 2.0 -> "Elite"
                        card.sharpeRatio >= 1.0 -> "Solid"
                        card.sharpeRatio >= 0.0 -> "Low"
                        else -> "Negative"
                    }
                    val sharpeProgress = when {
                        card.sharpeRatio == null -> 0f
                        else -> (card.sharpeRatio / 2.5).toFloat().coerceIn(0f, 1f)
                    }

                    RiskRatioTile(
                        title = "SHARPE",
                        value = sharpeDisplay,
                        statusLabel = sharpeLabel,
                        accentColor = sharpeColor,
                        progress = sharpeProgress,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("scorecard_sharpe_ratio")
                    )

                    val rrDisplay = when {
                        card.avgRiskRewardRatio == null -> "—"
                        card.avgRiskRewardRatio.isInfinite() -> "1 : ∞"
                        else -> "1 : ${String.format(Locale.US, "%.2f", card.avgRiskRewardRatio)}"
                    }
                    val rrColor = when {
                        card.avgRiskRewardRatio == null -> MaterialTheme.colorScheme.onSurfaceVariant
                        card.avgRiskRewardRatio.isInfinite() || card.avgRiskRewardRatio >= 1.5 -> tradingColors.bullish
                        card.avgRiskRewardRatio >= 1.0 -> tradingColors.amber
                        else -> tradingColors.bearish
                    }
                    val rrLabel = when {
                        card.avgRiskRewardRatio == null -> "Pending"
                        card.avgRiskRewardRatio.isInfinite() -> "No Losses"
                        card.avgRiskRewardRatio >= 1.8 -> "Asymmetric"
                        card.avgRiskRewardRatio >= 1.1 -> "Favorable"
                        else -> "Tighten SL"
                    }
                    val rrProgress = when {
                        card.avgRiskRewardRatio == null -> 0f
                        card.avgRiskRewardRatio.isInfinite() -> 1f
                        else -> (card.avgRiskRewardRatio / 2.5).toFloat().coerceIn(0f, 1f)
                    }

                    RiskRatioTile(
                        title = "AVG R : R",
                        value = rrDisplay,
                        statusLabel = rrLabel,
                        accentColor = rrColor,
                        progress = rrProgress,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("scorecard_avg_rr")
                    )
                }

                HorizontalDivider(color = tradingColors.border)

                // Secondary Risk Diagnostics Strip
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "MAX DRAWDOWN",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        val ddColor = if (card.maxDrawdownPercent <= 5.0) tradingColors.amber else tradingColors.bearish
                        Text(
                            text = "${String.format(Locale.US, "%.2f", card.maxDrawdownPercent)}% (${formatMoney(card.maxDrawdownAmount, signed = false)})",
                            style = MonoPriceSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (card.maxDrawdownPercent == 0.0) tradingColors.bullish else ddColor
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "EXPECTANCY / TRADE",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        val expColor = if (card.expectancyPerTrade >= BigDecimal.ZERO) tradingColors.bullish else tradingColors.bearish
                        Text(
                            text = formatMoney(card.expectancyPerTrade, signed = true),
                            style = MonoPriceSmall,
                            fontWeight = FontWeight.Bold,
                            color = expColor
                        )
                    }
                }

                val footnote = if (card.isIntradaySharpeEstimate) {
                    "Sharpe uses intraday trade-return variance (all trades closed on 1 day) • Calmar = Ann. Return / Max DD."
                } else {
                    "Sharpe annualized (√252) across ${card.distinctTradingDays} trading days • Calmar = Ann. Return / Max DD."
                }
                Text(
                    text = footnote,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun RiskRatioTile(
    title: String,
    value: String,
    statusLabel: String,
    accentColor: Color,
    progress: Float,
    modifier: Modifier = Modifier
) {
    val tradingColors = LocalTradingColors.current
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, tradingColors.border)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            Text(
                text = value,
                style = MonoPriceMedium,
                fontWeight = FontWeight.Bold,
                color = accentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(1.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = accentColor,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
            )
            Text(
                text = statusLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
