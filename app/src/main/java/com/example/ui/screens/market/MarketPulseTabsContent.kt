package com.example.ui.screens.market

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.domain.engine.RiskMath
import com.example.domain.market.FiiDiiDashboardData
import com.example.domain.market.FiiDiiSessionRecord
import com.example.domain.market.GlobalMarketQuote
import com.example.domain.market.GlobalRegion
import com.example.domain.market.MarketCapTier
import com.example.domain.market.MoverCategory
import com.example.domain.market.ScreenerFilterState
import com.example.domain.market.ScreenerPreset
import com.example.domain.market.ScreenerSortBy
import com.example.domain.market.StockMarketSnapshot
import com.example.domain.model.MarketSegment
import com.example.ui.theme.LocalTradingColors
import com.example.ui.theme.MonoPriceMedium
import com.example.ui.theme.MonoPriceSmall
import java.util.Locale
import kotlin.math.abs

@Composable
internal fun MarketMoversTab(
    selectedCategory: MoverCategory,
    movers: List<StockMarketSnapshot>,
    onSelectCategory: (MoverCategory) -> Unit,
    onOpenFundamentals: (StockMarketSnapshot) -> Unit,
    onTradeStock: (StockMarketSnapshot) -> Unit
) {
    val tradingColors = LocalTradingColors.current

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MoverCategory.entries.forEach { cat ->
                FilterChip(
                    selected = selectedCategory == cat,
                    onClick = { onSelectCategory(cat) },
                    label = { Text(cat.shortLabel) },
                    modifier = Modifier.testTag("mover_category_${cat.name.lowercase()}"),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.primary
                    )
                )
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(key = "mover_section_title") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = selectedCategory.label.uppercase(Locale.US),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "${movers.size} stocks • Tap for Fundamentals",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            items(movers, key = { "${selectedCategory.name}_${it.symbol}" }) { stock ->
                StockIntelRowCard(
                    stock = stock,
                    highlightCategory = selectedCategory,
                    onOpenFundamentals = { onOpenFundamentals(stock) },
                    onTrade = { onTradeStock(stock) }
                )
            }
        }
    }
}

@Composable
internal fun FiiDiiTab(
    data: FiiDiiDashboardData?,
    isRefreshing: Boolean = false,
    onRefreshFlow: () -> Unit = {}
) {
    val tradingColors = LocalTradingColors.current
    if (data == null) return

    val latest = data.latest
    val fiiPositive = latest.fiiNetCr >= 0.0
    val diiPositive = latest.diiNetCr >= 0.0
    val combinedPositive = latest.combinedNetCr >= 0.0

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("market_flow_tab_content"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "fii_dii_summary") {
            Surface(
                shape = RoundedCornerShape(16.dp),
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
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "MARKET FLOW — FII / DII TURNOVER (${latest.dateLabel})",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = data.sourceLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (combinedPositive) tradingColors.bullishContainer else tradingColors.bearishContainer
                            ) {
                                Text(
                                    text = if (data.isLiveFromNse) "NSE LIVE" else "PROVISIONAL",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (combinedPositive) tradingColors.bullish else tradingColors.bearish,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                            TextButton(
                                onClick = onRefreshFlow,
                                enabled = !isRefreshing,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                modifier = Modifier.testTag("market_flow_sync_nse")
                            ) {
                                Text(if (isRefreshing) "Syncing..." else "Sync NSE", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }

                    // Institutional Sentiment Pill
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (combinedPositive) tradingColors.bullishContainer else tradingColors.amberContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = data.sentimentLabel,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (combinedPositive) tradingColors.bullish else tradingColors.amber
                            )
                            Text(
                                text = "Net ${formatSignedCr(latest.combinedNetCr)}",
                                style = MonoPriceSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (combinedPositive) tradingColors.bullish else tradingColors.bearish
                            )
                        }
                    }

                    // Side-by-side FII and DII Cards
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        InstitutionFlowCard(
                            title = "FII / FPI",
                            buyCr = latest.fiiBuyCr,
                            sellCr = latest.fiiSellCr,
                            netCr = latest.fiiNetCr,
                            accent = if (fiiPositive) tradingColors.bullish else tradingColors.bearish,
                            modifier = Modifier.weight(1f)
                        )
                        InstitutionFlowCard(
                            title = "DII (Domestic)",
                            buyCr = latest.diiBuyCr,
                            sellCr = latest.diiSellCr,
                            netCr = latest.diiNetCr,
                            accent = if (diiPositive) tradingColors.bullish else tradingColors.bearish,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    HorizontalDivider(color = tradingColors.border)

                    // 7-Session Cumulative Strip
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("7-SESSION FII NET", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                text = formatSignedCr(data.mtdFiiNetCr),
                                style = MonoPriceMedium,
                                color = if (data.mtdFiiNetCr >= 0) tradingColors.bullish else tradingColors.bearish
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("7-SESSION DII NET", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                text = formatSignedCr(data.mtdDiiNetCr),
                                style = MonoPriceMedium,
                                color = if (data.mtdDiiNetCr >= 0) tradingColors.bullish else tradingColors.bearish
                            )
                        }
                    }
                }
            }
        }

        // Visual 7-Day Grouped FII vs DII Net Flow Bar Chart
        item(key = "fii_dii_chart") {
            FiiDiiNetFlowBarChart(sessions = data.recentSessions.reversed())
        }

        // Session History Table with Daily Buy / Sell / Net Turnover
        item(key = "fii_dii_table_header") {
            Text(
                text = "DAILY NSE BUY / SELL / NET TURNOVER (₹ CRORE)",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        items(data.recentSessions, key = { "fiidii_${it.dateLabel}" }) { row ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, tradingColors.border)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = row.dateLabel,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (row.combinedNetCr >= 0) tradingColors.bullishContainer else tradingColors.bearishContainer
                        ) {
                            Text(
                                text = "Combined ${formatSignedCr(row.combinedNetCr)}",
                                style = MonoPriceSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (row.combinedNetCr >= 0) tradingColors.bullish else tradingColors.bearish,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "FII Net ${formatSignedCr(row.fiiNetCr)}",
                                style = MonoPriceSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (row.fiiNetCr >= 0) tradingColors.bullish else tradingColors.bearish
                            )
                            Text(
                                text = "Buy ₹${String.format(Locale.US, "%,.0f", row.fiiBuyCr)} • Sell ₹${String.format(Locale.US, "%,.0f", row.fiiSellCr)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                            Text(
                                text = "DII Net ${formatSignedCr(row.diiNetCr)}",
                                style = MonoPriceSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (row.diiNetCr >= 0) tradingColors.bullish else tradingColors.bearish
                            )
                            Text(
                                text = "Buy ₹${String.format(Locale.US, "%,.0f", row.diiBuyCr)} • Sell ₹${String.format(Locale.US, "%,.0f", row.diiSellCr)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InstitutionFlowCard(
    title: String,
    buyCr: Double,
    sellCr: Double,
    netCr: Double,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.4f))
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = formatSignedCr(netCr),
                style = MonoPriceMedium,
                color = accent,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Buy ₹${String.format(Locale.US, "%,.0f", buyCr)} Cr",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Sell ₹${String.format(Locale.US, "%,.0f", sellCr)} Cr",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun FiiDiiNetFlowBarChart(sessions: List<FiiDiiSessionRecord>) {
    val tradingColors = LocalTradingColors.current
    val maxAbs = sessions.maxOfOrNull { maxOf(abs(it.fiiNetCr), abs(it.diiNetCr)) }?.coerceAtLeast(500.0) ?: 1000.0
    val fiiColor = MaterialTheme.colorScheme.primary
    val diiColor = tradingColors.violet
    val zeroLineColor = tradingColors.border

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, tradingColors.border)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "7-DAY FII vs DII NET FLOW (₹ CR)",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ChartKeyDot("FII", fiiColor)
                    ChartKeyDot("DII", diiColor)
                }
            }

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(130.dp)
            ) {
                val midY = size.height / 2f
                drawLine(
                    color = zeroLineColor,
                    start = Offset(0f, midY),
                    end = Offset(size.width, midY),
                    strokeWidth = 1.dp.toPx()
                )
                if (sessions.isEmpty()) return@Canvas
                val groupWidth = size.width / sessions.size
                val barW = (groupWidth * 0.28f).coerceAtMost(18.dp.toPx())
                val gap = 3.dp.toPx()
                val maxHalfH = (size.height * 0.44f)

                sessions.forEachIndexed { idx, rec ->
                    val centerX = groupWidth * idx + groupWidth / 2f

                    val fiiH = ((abs(rec.fiiNetCr) / maxAbs) * maxHalfH).toFloat().coerceAtLeast(4f)
                    val fiiTop = if (rec.fiiNetCr >= 0) midY - fiiH else midY
                    drawRoundRect(
                        color = if (rec.fiiNetCr >= 0) fiiColor else tradingColors.bearish,
                        topLeft = Offset(centerX - barW - gap / 2f, fiiTop),
                        size = Size(barW, fiiH),
                        cornerRadius = CornerRadius(4f, 4f)
                    )

                    val diiH = ((abs(rec.diiNetCr) / maxAbs) * maxHalfH).toFloat().coerceAtLeast(4f)
                    val diiTop = if (rec.diiNetCr >= 0) midY - diiH else midY
                    drawRoundRect(
                        color = if (rec.diiNetCr >= 0) diiColor else tradingColors.amber,
                        topLeft = Offset(centerX + gap / 2f, diiTop),
                        size = Size(barW, diiH),
                        cornerRadius = CornerRadius(4f, 4f)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                sessions.forEach { rec ->
                    Text(
                        text = rec.dateLabel.substringBefore(" "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ChartKeyDot(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color)
        )
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun GlobalMarketsTab(
    selectedRegion: GlobalRegion,
    quotes: List<GlobalMarketQuote>,
    onSelectRegion: (GlobalRegion) -> Unit
) {
    val tradingColors = LocalTradingColors.current
    val advancing = quotes.count { it.changePercent > 0.0 }
    val declining = quotes.count { it.changePercent < 0.0 }
    val riskOn = advancing >= declining

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            GlobalRegion.entries.forEach { reg ->
                FilterChip(
                    selected = selectedRegion == reg,
                    onClick = { onSelectRegion(reg) },
                    label = { Text(reg.label) },
                    modifier = Modifier.testTag("global_region_${reg.name.lowercase()}"),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.primary
                    )
                )
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag("global_markets_list"),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(key = "global_pulse_banner") {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (riskOn) tradingColors.bullishContainer else tradingColors.bearishContainer,
                    border = BorderStroke(
                        1.dp,
                        (if (riskOn) tradingColors.bullish else tradingColors.bearish).copy(alpha = 0.4f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Rounded.Public,
                                contentDescription = null,
                                tint = if (riskOn) tradingColors.bullish else tradingColors.bearish,
                                modifier = Modifier.size(18.dp)
                            )
                            Column {
                                Text(
                                    text = if (riskOn) "GLOBAL SENTIMENT: RISK-ON" else "GLOBAL SENTIMENT: CAUTIOUS",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (riskOn) tradingColors.bullish else tradingColors.bearish
                                )
                                Text(
                                    text = "$advancing advancing • $declining declining benchmarks",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            items(quotes.chunked(2), key = { row -> row.joinToString("_") { it.symbol } }) { pair ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    pair.forEach { q ->
                        GlobalQuoteCard(quote = q, modifier = Modifier.weight(1f))
                    }
                    if (pair.size == 1) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun GlobalQuoteCard(quote: GlobalMarketQuote, modifier: Modifier = Modifier) {
    val tradingColors = LocalTradingColors.current
    val isUp = quote.changePercent >= 0.0
    val accent = if (isUp) tradingColors.bullish else tradingColors.bearish
    val container = if (isUp) tradingColors.bullishContainer else tradingColors.bearishContainer

    Surface(
        modifier = modifier.testTag("global_card_${quote.symbol.lowercase().replace(" ", "_")}"),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, tradingColors.border)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = quote.symbol,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Surface(
                    shape = RoundedCornerShape(5.dp),
                    color = if (quote.isLive) tradingColors.bullishContainer else MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Text(
                        text = if (quote.isLive) "LIVE" else quote.region.label.uppercase(Locale.US),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (quote.isLive) tradingColors.bullish else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                    )
                }
            }
            Text(
                text = quote.name,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${quote.currency}${String.format(Locale.US, "%,.2f", quote.price)}",
                style = MonoPriceMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = container
            ) {
                Text(
                    text = String.format(Locale.US, "%+.2f (%+.2f%%)", quote.change, quote.changePercent),
                    style = MonoPriceSmall,
                    color = accent,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun StockIntelRowCard(
    stock: StockMarketSnapshot,
    highlightCategory: MoverCategory?,
    onOpenFundamentals: () -> Unit,
    onTrade: () -> Unit
) {
    val tradingColors = LocalTradingColors.current
    val isUp = stock.changePercent >= 0.0
    val chgColor = if (isUp) tradingColors.bullish else tradingColors.bearish
    val chgBg = if (isUp) tradingColors.bullishContainer else tradingColors.bearishContainer
    val score = stock.scorecard.overallScore
    val scoreColor = when {
        score >= 80 -> tradingColors.bullish
        score >= 65 -> MaterialTheme.colorScheme.primary
        else -> tradingColors.amber
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onOpenFundamentals)
            .testTag("stock_intel_card_${stock.symbol}"),
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
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1.25f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = stock.symbol,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = scoreColor.copy(alpha = 0.16f)
                        ) {
                            Text(
                                text = "Score $score",
                                style = MaterialTheme.typography.labelSmall,
                                color = scoreColor,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        if (stock.isNew52WeekHigh) {
                            Surface(shape = RoundedCornerShape(5.dp), color = tradingColors.bullishContainer) {
                                Text(
                                    text = "52W HIGH",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = tradingColors.bullish,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                )
                            }
                        } else if (stock.isNew52WeekLow) {
                            Surface(shape = RoundedCornerShape(5.dp), color = tradingColors.bearishContainer) {
                                Text(
                                    text = "52W LOW",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = tradingColors.bearish,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }
                    Text(
                        text = "${stock.companyName} • ${stock.sector}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = RiskMath.formatPrice(stock.ltp, MarketSegment.NSE),
                        style = MonoPriceMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Surface(shape = RoundedCornerShape(6.dp), color = chgBg) {
                        Text(
                            text = String.format(Locale.US, "%+.2f%%", stock.changePercent),
                            style = MonoPriceSmall,
                            color = chgColor,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            // Compact Fundamental & Volume Telemetry Strip
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "P/E ${String.format(Locale.US, "%.1f", stock.livePeRatio)} • ROE ${String.format(Locale.US, "%.1f", stock.profile.roePercent)}%",
                    style = MonoPriceSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val volLabel = when (highlightCategory) {
                    MoverCategory.TOP_VOLUME -> "Vol ${formatVol(stock.volume)} (${String.format(Locale.US, "%.1fx", stock.relativeVolume)})"
                    MoverCategory.WEEK_52_HIGH -> "52W H ${RiskMath.formatPrice(stock.fiftyTwoWeekHigh, MarketSegment.NSE)}"
                    MoverCategory.WEEK_52_LOW -> "52W L ${RiskMath.formatPrice(stock.fiftyTwoWeekLow, MarketSegment.NSE)}"
                    else -> "Vol ${formatVol(stock.volume)}"
                }
                Text(
                    text = volLabel,
                    style = MonoPriceSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // 52W Position Mini Bar
            LinearProgressIndicator(
                progress = { stock.fiftyTwoWeekRangePosition },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = if (stock.fiftyTwoWeekRangePosition >= 0.7f) tradingColors.bullish else MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHigh
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onOpenFundamentals,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Rounded.Insights, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Fundamentals", style = MaterialTheme.typography.labelSmall)
                }
                OutlinedButton(
                    onClick = onTrade,
                    modifier = Modifier.weight(0.85f),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "Trade",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

private fun formatSignedCr(value: Double): String {
    val sign = if (value >= 0) "+" else "-"
    return "${sign}₹${String.format(Locale.US, "%,.0f", abs(value))} Cr"
}

private fun formatVol(vol: Long): String {
    return when {
        vol >= 10_000_000L -> String.format(Locale.US, "%.2fCr", vol / 10_000_000.0)
        vol >= 100_000L -> String.format(Locale.US, "%.1fL", vol / 100_000.0)
        vol >= 1_000L -> String.format(Locale.US, "%.1fK", vol / 1_000.0)
        else -> vol.toString()
    }
}
