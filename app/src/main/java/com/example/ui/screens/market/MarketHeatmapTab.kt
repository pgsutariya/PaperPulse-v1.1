package com.example.ui.screens.market

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.domain.engine.RiskMath
import com.example.domain.market.SectorHeatmapSummary
import com.example.domain.market.StockMarketSnapshot
import com.example.domain.model.MarketSegment
import com.example.ui.theme.LocalTradingColors
import com.example.ui.theme.MonoPriceSmall
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

enum class HeatmapSizeMode(val label: String) {
    MARKET_CAP("Size: Mkt Cap"),
    VOLUME("Size: Volume"),
    EQUAL("Equal Grid")
}

enum class HeatmapColorMetric(val label: String) {
    DAY_CHANGE("Heat: Day %"),
    WEEK_52_STRENGTH("Heat: 52W Position")
}

/**
 * Interactive Market & Sector Heatmap:
 * - Advance/Decline market breadth indicator
 * - Interactive Sector Heat cards (tap to filter heatmap by sector)
 * - Treemap-style proportional weighted tiles colored by Day % Change or 52W Range Strength
 * - Tap any tile to open its full Fundamental Analysis & Trade modal
 */
@Composable
internal fun MarketHeatmapTab(
    stocks: List<StockMarketSnapshot>,
    sectorSummaries: List<SectorHeatmapSummary>,
    onOpenFundamentals: (StockMarketSnapshot) -> Unit
) {
    val tradingColors = LocalTradingColors.current
    var sizeMode by rememberSaveable { mutableStateOf(HeatmapSizeMode.MARKET_CAP) }
    var colorMetric by rememberSaveable { mutableStateOf(HeatmapColorMetric.DAY_CHANGE) }
    var selectedSector by rememberSaveable { mutableStateOf<String?>(null) }

    val filteredStocks = remember(stocks, selectedSector, sizeMode, colorMetric) {
        val base = if (selectedSector == null) stocks else stocks.filter { it.sector == selectedSector }
        when (sizeMode) {
            HeatmapSizeMode.MARKET_CAP -> base.sortedByDescending { it.profile.marketCapCr }
            HeatmapSizeMode.VOLUME -> base.sortedByDescending { it.volume }
            HeatmapSizeMode.EQUAL -> when (colorMetric) {
                HeatmapColorMetric.DAY_CHANGE -> base.sortedByDescending { it.changePercent }
                HeatmapColorMetric.WEEK_52_STRENGTH -> base.sortedByDescending { it.fiftyTwoWeekRangePosition }
            }
        }
    }

    val advancing = filteredStocks.count { it.changePercent >= 0.0 }
    val declining = (filteredStocks.size - advancing).coerceAtLeast(0)
    val advWeight = if (filteredStocks.isNotEmpty()) {
        (advancing.toFloat() / filteredStocks.size.toFloat()).coerceIn(0.05f, 0.95f)
    } else 0.5f

    // Group into treemap-style balanced rows (2 tiles for top mega-caps, 3 tiles for rest)
    val treemapRows = remember(filteredStocks, sizeMode) {
        buildTreemapRows(filteredStocks, sizeMode)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("market_heatmap_tab"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 1. Market Breadth & Heat Scale Legend
        item(key = "heatmap_breadth") {
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
                        Text(
                            text = if (selectedSector == null) "MARKET BREADTH HEATMAP" else "${selectedSector!!.uppercase(Locale.US)} SECTOR HEATMAP",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "▲ $advancing",
                                style = MonoPriceSmall,
                                color = tradingColors.bullish,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "▼ $declining",
                                style = MonoPriceSmall,
                                color = tradingColors.bearish,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(7.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    ) {
                        if (advancing > 0) {
                            Box(
                                Modifier
                                    .fillMaxHeight()
                                    .weight(advWeight)
                                    .background(tradingColors.bullish)
                            )
                        }
                        if (declining > 0) {
                            Box(
                                Modifier
                                    .fillMaxHeight()
                                    .weight((1f - advWeight).coerceAtLeast(0.05f))
                                    .background(tradingColors.bearish)
                            )
                        }
                    }

                    // Heat Intensity Scale Legend
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val stops = listOf(-3.0 to "-3%", -1.5 to "-1.5%", 0.0 to "0%", 1.5 to "+1.5%", 3.0 to "+3%")
                        stops.forEach { (pct, lbl) ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Box(
                                    Modifier
                                        .height(10.dp)
                                        .fillMaxWidth(0.01f)
                                )
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = heatColorForChange(pct, tradingColors.bullish, tradingColors.bearish, MaterialTheme.colorScheme.surfaceContainerHigh)
                                ) {
                                    Text(
                                        text = lbl,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White,
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 2. Mode Controls (Size By + Color Heat By)
        item(key = "heatmap_controls") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                HeatmapSizeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = sizeMode == mode,
                        onClick = { sizeMode = mode },
                        label = { Text(mode.label, style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.testTag("heatmap_size_${mode.name.lowercase()}"),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }
                HeatmapColorMetric.entries.forEach { metric ->
                    FilterChip(
                        selected = colorMetric == metric,
                        onClick = { colorMetric = metric },
                        label = { Text(metric.label, style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.testTag("heatmap_metric_${metric.name.lowercase()}")
                    )
                }
            }
        }

        // 3. Sector Performance Heat Strip
        item(key = "sector_heat_strip") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "SECTOR HEATMAP (TAP TO FILTER)",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (selectedSector == null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                        border = BorderStroke(
                            1.dp,
                            if (selectedSector == null) MaterialTheme.colorScheme.primary else tradingColors.border
                        ),
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { selectedSector = null }
                            .testTag("heatmap_sector_all")
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = "ALL SECTORS",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (selectedSector == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "${stocks.size} stocks",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    sectorSummaries.forEach { sec ->
                        val isSelected = selectedSector == sec.sector
                        val tileBg = heatColorForChange(
                            sec.weightedChangePercent,
                            tradingColors.bullish,
                            tradingColors.bearish,
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        )
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = tileBg,
                            border = BorderStroke(
                                if (isSelected) 2.dp else 0.8.dp,
                                if (isSelected) Color.White else Color.White.copy(alpha = 0.2f)
                            ),
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    selectedSector = if (isSelected) null else sec.sector
                                }
                                .testTag("heatmap_sector_${sec.sector.lowercase().replace(" ", "_")}")
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = sec.sector,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    maxLines = 1
                                )
                                Text(
                                    text = String.format(Locale.US, "%+.2f%% • %d▲/%d▼", sec.weightedChangePercent, sec.advancingCount, sec.decliningCount),
                                    style = MonoPriceSmall,
                                    color = Color.White.copy(alpha = 0.92f),
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }

        // 4. Stock Treemap / Heatmap Grid
        item(key = "stock_heatmap_header") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "STOCK HEATMAP (${filteredStocks.size} STOCKS)",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Tap any tile for Fundamentals & Trade",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        items(treemapRows, key = { row -> row.tiles.joinToString("_") { it.stock.symbol } }) { row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(row.heightDp.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                row.tiles.forEach { tile ->
                    HeatmapStockTile(
                        stock = tile.stock,
                        colorMetric = colorMetric,
                        onClick = { onOpenFundamentals(tile.stock) },
                        modifier = Modifier
                            .weight(tile.weight)
                            .fillMaxHeight()
                    )
                }
            }
        }

        item(key = "heatmap_bottom_spacer") {
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun HeatmapStockTile(
    stock: StockMarketSnapshot,
    colorMetric: HeatmapColorMetric,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tradingColors = LocalTradingColors.current
    val bgColor = when (colorMetric) {
        HeatmapColorMetric.DAY_CHANGE -> heatColorForChange(
            stock.changePercent,
            tradingColors.bullish,
            tradingColors.bearish,
            MaterialTheme.colorScheme.surfaceContainerHigh
        )
        HeatmapColorMetric.WEEK_52_STRENGTH -> heatColorFor52WeekPosition(
            stock.fiftyTwoWeekRangePosition,
            tradingColors.bullish,
            tradingColors.bearish,
            MaterialTheme.colorScheme.surfaceContainerHigh
        )
    }

    val metricReadout = when (colorMetric) {
        HeatmapColorMetric.DAY_CHANGE -> String.format(Locale.US, "%+.2f%%", stock.changePercent)
        HeatmapColorMetric.WEEK_52_STRENGTH -> "${(stock.fiftyTwoWeekRangePosition * 100f).toInt()}% 52W"
    }

    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .testTag("heatmap_tile_${stock.symbol}")
            .semantics {
                contentDescription = "${stock.symbol}, ${stock.sector}, $metricReadout, tap for fundamentals"
            },
        shape = RoundedCornerShape(10.dp),
        color = bgColor,
        border = BorderStroke(0.8.dp, Color.White.copy(alpha = 0.18f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = stock.symbol,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stock.sector,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.78f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Column {
                Text(
                    text = metricReadout,
                    style = MonoPriceSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1
                )
                Text(
                    text = RiskMath.formatPrice(stock.ltp, MarketSegment.NSE),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.86f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private data class HeatmapTileSpec(
    val stock: StockMarketSnapshot,
    val weight: Float
)

private data class HeatmapRowSpec(
    val tiles: List<HeatmapTileSpec>,
    val heightDp: Int
)

private fun buildTreemapRows(
    stocks: List<StockMarketSnapshot>,
    sizeMode: HeatmapSizeMode
): List<HeatmapRowSpec> {
    if (stocks.isEmpty()) return emptyList()
    val rows = mutableListOf<HeatmapRowSpec>()
    var idx = 0
    var rowNumber = 0
    while (idx < stocks.size) {
        val countInRow = when {
            sizeMode == HeatmapSizeMode.EQUAL -> 3
            rowNumber == 0 -> 2 // Top mega-caps get wider hero tiles
            rowNumber == 1 -> 2
            else -> 3
        }
        val slice = stocks.subList(idx, minOf(idx + countInRow, stocks.size))
        val tiles = slice.map { s ->
            val rawW = when (sizeMode) {
                HeatmapSizeMode.MARKET_CAP -> sqrt(s.profile.marketCapCr.coerceAtLeast(10_000.0)).toFloat()
                HeatmapSizeMode.VOLUME -> sqrt(s.volume.coerceAtLeast(50_000L).toDouble()).toFloat()
                HeatmapSizeMode.EQUAL -> 1f
            }
            HeatmapTileSpec(s, rawW.coerceAtLeast(0.4f))
        }
        val height = when {
            sizeMode != HeatmapSizeMode.EQUAL && rowNumber == 0 -> 108
            sizeMode != HeatmapSizeMode.EQUAL && rowNumber == 1 -> 98
            else -> 88
        }
        rows += HeatmapRowSpec(tiles = tiles, heightDp = height)
        idx += slice.size
        rowNumber++
    }
    return rows
}

private fun heatColorForChange(
    changePercent: Double,
    bullish: Color,
    bearish: Color,
    neutral: Color
): Color {
    val neutralSlate = lerp(neutral, Color(0xFF334155), 0.65f)
    val intensity = (abs(changePercent) / 3.2).toFloat().coerceIn(0f, 1f)
    return when {
        changePercent > 0.05 -> {
            val darkGreen = Color(0xFF14532D)
            val brightGreen = lerp(bullish, Color(0xFF059669), 0.3f)
            if (intensity < 0.25f) lerp(neutralSlate, darkGreen, intensity / 0.25f)
            else lerp(darkGreen, brightGreen, ((intensity - 0.25f) / 0.75f).coerceIn(0f, 1f))
        }
        changePercent < -0.05 -> {
            val darkRed = Color(0xFF7F1D1D)
            val brightRed = lerp(bearish, Color(0xFFDC2626), 0.25f)
            if (intensity < 0.25f) lerp(neutralSlate, darkRed, intensity / 0.25f)
            else lerp(darkRed, brightRed, ((intensity - 0.25f) / 0.75f).coerceIn(0f, 1f))
        }
        else -> neutralSlate
    }
}

private fun heatColorFor52WeekPosition(
    pos: Float,
    bullish: Color,
    bearish: Color,
    neutral: Color
): Color {
    val centered = ((pos - 0.5f) * 2f) * 3.2 // map 0..1 to -3.2..+3.2 scale
    return heatColorForChange(centered.toDouble(), bullish, bearish, neutral)
}
