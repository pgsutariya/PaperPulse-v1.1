package com.example.ui.screens.market

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Analytics
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.domain.engine.RiskMath
import com.example.domain.market.FundamentalVerdict
import com.example.domain.market.LiveFundamentalDetails
import com.example.domain.market.QuarterlyFinancialResult
import com.example.domain.market.StockMarketSnapshot
import com.example.domain.model.MarketSegment
import com.example.ui.theme.LocalTradingColors
import com.example.ui.theme.MonoPriceMedium
import com.example.ui.theme.MonoPriceSmall
import java.util.Locale

@Composable
fun FundamentalAnalysisDialog(
    stock: StockMarketSnapshot,
    liveDetails: LiveFundamentalDetails? = null,
    isLoadingDetails: Boolean = false,
    onRefreshDetails: (() -> Unit)? = null,
    onOpenChart: () -> Unit,
    onTradeNow: () -> Unit,
    onDismiss: () -> Unit
) {
    val tradingColors = LocalTradingColors.current
    val p = stock.profile
    val score = stock.scorecard
    val effectiveMcapCr = liveDetails?.marketCapCr ?: p.marketCapCr
    val effectivePe = liveDetails?.livePeRatio ?: stock.livePeRatio
    val effectivePb = liveDetails?.pbRatio ?: p.pbRatio
    val effectiveEps = liveDetails?.epsTtm ?: p.eps
    val effectiveDivYield = liveDetails?.dividendYieldPercent ?: p.dividendYieldPercent
    val effectiveRoe = liveDetails?.roePercent ?: p.roePercent
    val effectiveDebt = liveDetails?.debtToEquity ?: p.debtToEquity
    val isUp = stock.changePercent >= 0.0
    val priceColor = if (isUp) tradingColors.bullish else tradingColors.bearish
    val scoreColor = when (score.verdict) {
        FundamentalVerdict.STRONG_BUY_QUALITY -> tradingColors.bullish
        FundamentalVerdict.QUALITY_COMPOUNDER -> MaterialTheme.colorScheme.primary
        FundamentalVerdict.VALUE_OPPORTUNITY -> tradingColors.cryptoCyan
        FundamentalVerdict.BALANCED -> tradingColors.amber
        FundamentalVerdict.CAUTION -> tradingColors.bearish
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 18.dp)
                .testTag("fundamental_analysis_dialog_${stock.symbol}"),
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, tradingColors.border)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = stock.symbol,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Text(
                                    text = p.sector,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh
                            ) {
                                Text(
                                    text = p.marketCapTier.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Text(
                            text = p.companyName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = RiskMath.formatPrice(stock.ltp, MarketSegment.NSE),
                                style = MonoPriceMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = String.format(Locale.US, "%+.2f%%", stock.changePercent),
                                style = MonoPriceSmall,
                                color = priceColor,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (onRefreshDetails != null) {
                            IconButton(
                                onClick = onRefreshDetails,
                                enabled = !isLoadingDetails,
                                modifier = Modifier.testTag("refresh_fundamental_dialog")
                            ) {
                                if (isLoadingDetails) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(Icons.Rounded.Refresh, contentDescription = "Refresh Live Financials")
                                }
                            }
                        }
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.testTag("close_fundamental_dialog")
                        ) {
                            Icon(Icons.Rounded.Close, contentDescription = "Close Fundamental Analysis")
                        }
                    }
                }

                // Live Financial Sync Status Pill
                liveDetails?.let { details ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (details.isLiveFetched) tradingColors.bullishContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = details.fetchedAtLabel,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = if (details.isLiveFetched) tradingColors.bullish else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = if (details.isLiveFetched) "LIVE METRICS" else "FILING BASELINE",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (details.isLiveFetched) tradingColors.bullish else MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                // Fundamental Scorecard Card
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, scoreColor.copy(alpha = 0.45f))
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
                            Column {
                                Text(
                                    text = "FUNDAMENTAL QUALITY SCORE",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = scoreColor.copy(alpha = 0.16f),
                                    modifier = Modifier.padding(top = 4.dp)
                                ) {
                                    Text(
                                        text = score.verdict.label,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = scoreColor,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = "${score.overallScore}/100",
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = scoreColor
                                )
                                Text(
                                    text = "4-Pillar Rating",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        PillarScoreBar("Valuation (P/E, P/B, Yield)", score.valuationScore, tradingColors.cryptoCyan)
                        PillarScoreBar("Profitability (ROE, ROCE, OPM)", score.profitabilityScore, tradingColors.bullish)
                        PillarScoreBar("Growth (Revenue & Profit YoY)", score.growthScore, MaterialTheme.colorScheme.primary)
                        PillarScoreBar("Balance Sheet Health (D/E, Liq)", score.financialHealthScore, tradingColors.violet)
                    }
                }

                // 52-Week High / Low Range Card
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, tradingColors.border)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "52-WEEK PRICE RANGE",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = String.format(Locale.US, "%.1f%% below 52W High", stock.distanceFrom52WeekHighPercent.coerceAtLeast(0.0)),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (stock.isNew52WeekHigh) tradingColors.bullish else MaterialTheme.colorScheme.primary
                            )
                        }
                        LinearProgressIndicator(
                            progress = { stock.fiftyTwoWeekRangePosition },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = if (stock.fiftyTwoWeekRangePosition >= 0.7f) tradingColors.bullish else MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHigh
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "52W Low ${RiskMath.formatPrice(stock.fiftyTwoWeekLow, MarketSegment.NSE)}",
                                style = MonoPriceSmall,
                                color = tradingColors.bearish
                            )
                            Text(
                                text = "52W High ${RiskMath.formatPrice(stock.fiftyTwoWeekHigh, MarketSegment.NSE)}",
                                style = MonoPriceSmall,
                                color = tradingColors.bullish
                            )
                        }
                    }
                }

                // Technical Snapshot Card (RSI(14) & Moving Averages)
                val tech = stock.technical
                val rsiAccent = when {
                    tech.rsi14 >= 70.0 -> tradingColors.bearish
                    tech.rsi14 <= 35.0 -> tradingColors.bullish
                    tech.rsi14 >= 55.0 -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
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
                            Text(
                                text = "TECHNICAL SIGNALS",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = rsiAccent.copy(alpha = 0.16f)
                            ) {
                                Text(
                                    text = "RSI ${String.format(Locale.US, "%.1f", tech.rsi14)} • ${tech.rsiZoneLabel}",
                                    style = MonoPriceSmall,
                                    color = rsiAccent,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Text(
                            text = "${tech.maCrossover.label} • ${if (tech.aboveSma200) "Above SMA200" else "Below SMA200"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                        MetricGridRow(
                            "EMA 20" to RiskMath.formatPrice(java.math.BigDecimal.valueOf(tech.ema20), MarketSegment.NSE),
                            "SMA 50" to RiskMath.formatPrice(java.math.BigDecimal.valueOf(tech.sma50), MarketSegment.NSE),
                            "SMA 200" to RiskMath.formatPrice(java.math.BigDecimal.valueOf(tech.sma200), MarketSegment.NSE)
                        )
                    }
                }

                // Valuation & Profitability Ratios Grid
                Text(
                    text = "REAL-TIME FINANCIAL METRICS",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                MetricGridRow(
                    "Market Cap" to formatCr(effectiveMcapCr),
                    "Live P/E" to String.format(Locale.US, "%.1fx", effectivePe),
                    "P/B Ratio" to String.format(Locale.US, "%.1fx", effectivePb)
                )
                MetricGridRow(
                    "EV / EBITDA" to String.format(Locale.US, "%.1fx", p.evEbitda),
                    "EPS (TTM)" to String.format(Locale.US, "₹%.1f", effectiveEps),
                    "Div Yield" to String.format(Locale.US, "%.2f%%", effectiveDivYield)
                )
                MetricGridRow(
                    "ROE" to String.format(Locale.US, "%.1f%%", effectiveRoe),
                    "ROCE" to String.format(Locale.US, "%.1f%%", p.rocePercent),
                    "OPM" to String.format(Locale.US, "%.1f%%", p.operatingMarginPercent)
                )
                MetricGridRow(
                    "Rev Growth" to String.format(Locale.US, "%+.1f%%", p.revenueGrowthYoyPercent),
                    "PAT Growth" to String.format(Locale.US, "%+.1f%%", p.profitGrowthYoyPercent),
                    "Debt / Eq" to String.format(Locale.US, "%.2f", effectiveDebt)
                )

                // Recent Quarterly Results Card
                val quarters = liveDetails?.quarterlyResults.orEmpty()
                if (isLoadingDetails && quarters.isEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(1.dp, tradingColors.border),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text(
                                text = "Fetching recent quarterly financials...",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else if (quarters.isNotEmpty()) {
                    QuarterlyResultsCard(quarters = quarters)
                }

                // Shareholding Pattern Bar
                val publicPct = (100.0 - p.promoterHoldingPercent - p.fiiHoldingPercent - p.diiHoldingPercent)
                    .coerceAtLeast(1.0)
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, tradingColors.border)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "SHAREHOLDING PATTERN",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(10.dp)
                                .clip(RoundedCornerShape(5.dp))
                        ) {
                            if (p.promoterHoldingPercent > 0.5) {
                                Box(
                                    Modifier
                                        .fillMaxHeight()
                                        .weight(p.promoterHoldingPercent.toFloat())
                                        .background(MaterialTheme.colorScheme.primary)
                                )
                            }
                            if (p.fiiHoldingPercent > 0.5) {
                                Box(
                                    Modifier
                                        .fillMaxHeight()
                                        .weight(p.fiiHoldingPercent.toFloat())
                                        .background(tradingColors.bullish)
                                )
                            }
                            if (p.diiHoldingPercent > 0.5) {
                                Box(
                                    Modifier
                                        .fillMaxHeight()
                                        .weight(p.diiHoldingPercent.toFloat())
                                        .background(tradingColors.violet)
                                )
                            }
                            Box(
                                Modifier
                                    .fillMaxHeight()
                                    .weight(publicPct.toFloat())
                                    .background(tradingColors.amber)
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            LegendItem("Promoter", p.promoterHoldingPercent, MaterialTheme.colorScheme.primary)
                            LegendItem("FII", p.fiiHoldingPercent, tradingColors.bullish)
                            LegendItem("DII", p.diiHoldingPercent, tradingColors.violet)
                            LegendItem("Public", publicPct, tradingColors.amber)
                        }
                    }
                }

                // Strengths & Watchouts
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    score.strengths.forEach { point ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                tint = tradingColors.bullish,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = point,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                    score.watchouts.forEach { risk ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                Icons.Rounded.WarningAmber,
                                contentDescription = null,
                                tint = tradingColors.amber,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = risk,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                HorizontalDivider(color = tradingColors.border)

                // Direct Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onOpenChart,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("fundamental_open_chart_${stock.symbol}"),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Rounded.Analytics, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Open Chart")
                    }
                    Button(
                        onClick = onTradeNow,
                        modifier = Modifier
                            .weight(1.2f)
                            .testTag("fundamental_trade_now_${stock.symbol}"),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Trade ${stock.symbol}", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun PillarScoreBar(label: String, score: Int, color: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "$score",
                style = MonoPriceSmall,
                color = color,
                fontWeight = FontWeight.Bold
            )
        }
        LinearProgressIndicator(
            progress = { (score / 100f).coerceIn(0.02f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    }
}

@Composable
private fun MetricGridRow(
    item1: Pair<String, String>,
    item2: Pair<String, String>,
    item3: Pair<String, String>
) {
    val tradingColors = LocalTradingColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        listOf(item1, item2, item3).forEach { (label, value) ->
            Surface(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(0.8.dp, tradingColors.border)
            ) {
                Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp)) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = value,
                        style = MonoPriceSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun LegendItem(label: String, pct: Double, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(
                Modifier
                    .size(7.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color)
            )
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            text = String.format(Locale.US, "%.1f%%", pct),
            style = MonoPriceSmall,
            fontWeight = FontWeight.SemiBold
        )
    }
}

private fun formatCr(cr: Double): String {
    return if (cr >= 100_000.0) {
        String.format(Locale.US, "₹%.2fL Cr", cr / 100_000.0)
    } else {
        String.format(Locale.US, "₹%.0f Cr", cr)
    }
}

@Composable
private fun QuarterlyResultsCard(quarters: List<QuarterlyFinancialResult>) {
    val tradingColors = LocalTradingColors.current
    val maxRev = quarters.maxOfOrNull { it.revenueCr }?.coerceAtLeast(1.0) ?: 1.0

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, tradingColors.border),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("fundamental_quarterly_results_card")
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
                    text = "RECENT QUARTERLY RESULTS (₹ CR)",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Last ${quarters.size} Quarters",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // Header row
            Row(modifier = Modifier.fillMaxWidth()) {
                Text("Quarter", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1.1f))
                Text("Rev", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End, modifier = Modifier.weight(0.95f))
                Text("PAT", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End, modifier = Modifier.weight(0.9f))
                Text("OPM", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End, modifier = Modifier.weight(0.75f))
                Text("EPS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End, modifier = Modifier.weight(0.7f))
            }

            HorizontalDivider(thickness = 0.6.dp, color = tradingColors.border)

            quarters.forEach { q ->
                val patPositive = q.netProfitCr >= 0.0
                val revRatio = (q.revenueCr / maxRev).toFloat().coerceIn(0.08f, 1f)
                val patRatio = (q.netProfitCr.coerceAtLeast(0.0) / maxRev).toFloat().coerceIn(0.03f, 1f)

                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = q.quarterLabel,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1.1f),
                            maxLines = 1
                        )
                        Text(
                            text = String.format(Locale.US, "%,.0f", q.revenueCr),
                            style = MonoPriceSmall,
                            textAlign = TextAlign.End,
                            modifier = Modifier.weight(0.95f),
                            maxLines = 1
                        )
                        Text(
                            text = String.format(Locale.US, "%,.0f", q.netProfitCr),
                            style = MonoPriceSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (patPositive) tradingColors.bullish else tradingColors.bearish,
                            textAlign = TextAlign.End,
                            modifier = Modifier.weight(0.9f),
                            maxLines = 1
                        )
                        Text(
                            text = String.format(Locale.US, "%.1f%%", q.operatingMarginPct),
                            style = MonoPriceSmall,
                            textAlign = TextAlign.End,
                            modifier = Modifier.weight(0.75f),
                            maxLines = 1
                        )
                        Text(
                            text = String.format(Locale.US, "%.1f", q.eps),
                            style = MonoPriceSmall,
                            textAlign = TextAlign.End,
                            modifier = Modifier.weight(0.7f),
                            maxLines = 1
                        )
                    }

                    // Dual mini visual bar: Revenue (primary) + Net Profit (emerald/crimson)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(5.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(revRatio)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(3.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.45f))
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(patRatio)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(3.dp))
                                .background(if (patPositive) tradingColors.bullish else tradingColors.bearish)
                        )
                    }
                }
            }
        }
    }
}
