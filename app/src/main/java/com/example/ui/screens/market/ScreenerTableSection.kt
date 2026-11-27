package com.example.ui.screens.market

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.FirstPage
import androidx.compose.material.icons.rounded.LastPage
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.domain.engine.RiskMath
import com.example.domain.market.MaCrossoverSignal
import com.example.domain.market.MarketCapTier
import com.example.domain.market.PagedScreenerResult
import com.example.domain.market.RsiFilterZone
import com.example.domain.market.ScreenerFilterState
import com.example.domain.market.ScreenerPreset
import com.example.domain.market.ScreenerSortBy
import com.example.domain.market.StockMarketSnapshot
import com.example.domain.model.MarketSegment
import com.example.ui.theme.LocalTradingColors
import com.example.ui.theme.MonoPriceSmall
import java.util.Locale

/**
 * Full-featured Technical + Fundamental Stock Screener backed by a sortable, paginated data table.
 */
@Composable
internal fun StockScreenerTableTab(
    filter: ScreenerFilterState,
    pagedResult: PagedScreenerResult,
    totalUniverse: Int,
    availableSectors: List<String>,
    onQueryChange: (String) -> Unit,
    onSelectPreset: (ScreenerPreset) -> Unit,
    onSelectRsiFilter: (RsiFilterZone) -> Unit,
    onSelectMaCrossover: (MaCrossoverSignal?) -> Unit,
    onToggleAboveSma200: (Boolean) -> Unit,
    onSelectSector: (String?) -> Unit,
    onSelectCapTier: (MarketCapTier?) -> Unit,
    onMaxPeChange: (Double?) -> Unit,
    onMinRoeChange: (Double?) -> Unit,
    onMaxDebtChange: (Double?) -> Unit,
    onMinDividendChange: (Double?) -> Unit,
    onToggleNear52High: (Boolean) -> Unit,
    onTogglePositiveOnly: (Boolean) -> Unit,
    onToggleColumnSort: (ScreenerSortBy) -> Unit,
    onGoToPage: (Int) -> Unit,
    onPageSizeChange: (Int) -> Unit,
    onResetFilters: () -> Unit,
    onOpenFundamentals: (StockMarketSnapshot) -> Unit,
    onTradeStock: (StockMarketSnapshot) -> Unit
) {
    val tradingColors = LocalTradingColors.current
    var showAdvancedFilters by rememberSaveable { mutableStateOf(false) }

    val activeFilterCount = countActiveFilters(filter)
    val tableHScroll = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 16.dp)
            .testTag("stock_screener_tab")
    ) {
        // Search + Advanced Filter Drawer Toggle
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = filter.searchQuery,
                onValueChange = onQueryChange,
                placeholder = {
                    Text(
                        "Search symbol, company or sector...",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = "Search Screener") },
                trailingIcon = {
                    if (filter.searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .weight(1f)
                    .testTag("screener_search_input")
            )
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (showAdvancedFilters || activeFilterCount > 0) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surface
                },
                border = BorderStroke(
                    1.dp,
                    if (showAdvancedFilters || activeFilterCount > 0) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        tradingColors.border
                    }
                ),
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { showAdvancedFilters = !showAdvancedFilters }
                    .testTag("screener_toggle_filters")
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.FilterList,
                        contentDescription = "Toggle Fundamental and Technical Filters",
                        tint = if (showAdvancedFilters || activeFilterCount > 0) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                    if (activeFilterCount > 0) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(4.dp)
                        ) {
                            Text(
                                text = activeFilterCount.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 4.dp)
                            )
                        }
                    }
                }
            }
        }

        // 1-Tap Preset Strategies (Technical + Fundamental)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ScreenerPreset.entries.forEach { preset ->
                FilterChip(
                    selected = filter.preset == preset,
                    onClick = { onSelectPreset(preset) },
                    label = { Text(preset.label, style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.testTag("screener_preset_${preset.name.lowercase()}"),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.primary
                    )
                )
            }
        }

        // Quick Technical Criteria Strip (Always 1-tap reachable: RSI Zone + MA Crossover)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "TECHNICAL:",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            RsiFilterZone.entries.filter { it != RsiFilterZone.ANY }.forEach { zone ->
                val selected = filter.rsiFilter == zone
                FilterChip(
                    selected = selected,
                    onClick = { onSelectRsiFilter(if (selected) RsiFilterZone.ANY else zone) },
                    label = { Text(zone.shortLabel, style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.testTag("screener_rsi_${zone.name.lowercase()}"),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = tradingColors.violetContainer,
                        selectedLabelColor = tradingColors.violet
                    )
                )
            }
            MaCrossoverSignal.entries.forEach { ma ->
                val selected = filter.maCrossoverFilter == ma
                FilterChip(
                    selected = selected,
                    onClick = { onSelectMaCrossover(if (selected) null else ma) },
                    label = { Text(ma.shortLabel, style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.testTag("screener_ma_${ma.name.lowercase()}"),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = tradingColors.bullishContainer,
                        selectedLabelColor = tradingColors.bullish
                    )
                )
            }
            FilterChip(
                selected = filter.onlyAboveSma200,
                onClick = { onToggleAboveSma200(!filter.onlyAboveSma200) },
                label = { Text("Above SMA200", style = MaterialTheme.typography.labelSmall) },
                modifier = Modifier.testTag("screener_above_sma200")
            )
        }

        // Expandable Fundamental & Valuation Filters Panel
        AnimatedVisibility(visible = showAdvancedFilters) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
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
                            text = "FUNDAMENTAL & SECTOR CRITERIA",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        TextButton(
                            onClick = onResetFilters,
                            modifier = Modifier.testTag("screener_reset_filters_button")
                        ) {
                            Text("Reset All", style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    // Sector Chips
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilterChip(
                            selected = filter.selectedSector == null,
                            onClick = { onSelectSector(null) },
                            label = { Text("All Sectors", style = MaterialTheme.typography.labelSmall) }
                        )
                        availableSectors.forEach { sec ->
                            FilterChip(
                                selected = filter.selectedSector == sec,
                                onClick = { onSelectSector(if (filter.selectedSector == sec) null else sec) },
                                label = { Text(sec, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }

                    // Valuation & Quality Thresholds
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilterChip(
                            selected = filter.maxPe == 20.0,
                            onClick = { onMaxPeChange(if (filter.maxPe == 20.0) null else 20.0) },
                            label = { Text("P/E <= 20", style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.testTag("screener_pe_20")
                        )
                        FilterChip(
                            selected = filter.maxPe == 30.0,
                            onClick = { onMaxPeChange(if (filter.maxPe == 30.0) null else 30.0) },
                            label = { Text("P/E <= 30", style = MaterialTheme.typography.labelSmall) }
                        )
                        FilterChip(
                            selected = filter.minRoe == 18.0,
                            onClick = { onMinRoeChange(if (filter.minRoe == 18.0) null else 18.0) },
                            label = { Text("ROE >= 18%", style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.testTag("screener_roe_18")
                        )
                        FilterChip(
                            selected = filter.maxDebtToEquity == 0.5,
                            onClick = { onMaxDebtChange(if (filter.maxDebtToEquity == 0.5) null else 0.5) },
                            label = { Text("D/E <= 0.5", style = MaterialTheme.typography.labelSmall) }
                        )
                        FilterChip(
                            selected = filter.minDividendYield == 2.0,
                            onClick = { onMinDividendChange(if (filter.minDividendYield == 2.0) null else 2.0) },
                            label = { Text("Div >= 2%", style = MaterialTheme.typography.labelSmall) }
                        )
                        FilterChip(
                            selected = filter.onlyNear52WeekHigh,
                            onClick = { onToggleNear52High(!filter.onlyNear52WeekHigh) },
                            label = { Text("Near 52W High", style = MaterialTheme.typography.labelSmall) }
                        )
                        FilterChip(
                            selected = filter.onlyPositiveToday,
                            onClick = { onTogglePositiveOnly(!filter.onlyPositiveToday) },
                            label = { Text("Green Today", style = MaterialTheme.typography.labelSmall) }
                        )
                    }

                    // Market Cap Tier
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilterChip(
                            selected = filter.selectedCapTier == null,
                            onClick = { onSelectCapTier(null) },
                            label = { Text("All Caps", style = MaterialTheme.typography.labelSmall) }
                        )
                        MarketCapTier.entries.forEach { tier ->
                            FilterChip(
                                selected = filter.selectedCapTier == tier,
                                onClick = { onSelectCapTier(if (filter.selectedCapTier == tier) null else tier) },
                                label = { Text(tier.label, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                }
            }
        }

        // Status & Column Sort Hint Strip
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (pagedResult.allMatchingCount > 0) {
                    "Showing ${pagedResult.startIndex}–${pagedResult.endIndex} of ${pagedResult.allMatchingCount} ($totalUniverse total)"
                } else {
                    "0 of $totalUniverse stocks match"
                },
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (activeFilterCount > 0 || filter.preset != ScreenerPreset.ALL || filter.searchQuery.isNotBlank()) {
                TextButton(
                    onClick = onResetFilters,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Text("Clear", style = MaterialTheme.typography.labelSmall)
                }
            } else {
                Text(
                    text = "Tap headers to sort • Swipe →",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        // Sortable Data Table Card
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .testTag("screener_data_table"),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, tradingColors.border)
        ) {
            if (pagedResult.pageItems.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "No stocks match the active criteria",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Try relaxing an RSI, MA crossover, or valuation threshold.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Button(
                        onClick = onResetFilters,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.testTag("screener_empty_reset_button")
                    ) {
                        Text("Reset Screener Filters")
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(tableHScroll)
                ) {
                    // Table Header Row (Clickable Sortable Columns)
                    Row(
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .heightIn(min = 48.dp)
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SortableHeaderCell("Symbol", 116.dp, ScreenerSortBy.SYMBOL, filter, onToggleColumnSort, Alignment.CenterStart)
                        SortableHeaderCell("LTP", 88.dp, ScreenerSortBy.LTP, filter, onToggleColumnSort)
                        SortableHeaderCell("Chg %", 78.dp, ScreenerSortBy.CHANGE_DESC, filter, onToggleColumnSort)
                        SortableHeaderCell("RSI(14)", 78.dp, ScreenerSortBy.RSI, filter, onToggleColumnSort)
                        Box(
                            modifier = Modifier
                                .width(114.dp)
                                .padding(horizontal = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "MA Signal",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        SortableHeaderCell("P/E", 66.dp, ScreenerSortBy.PE_ASC, filter, onToggleColumnSort)
                        SortableHeaderCell("ROE %", 72.dp, ScreenerSortBy.ROE_DESC, filter, onToggleColumnSort)
                        SortableHeaderCell("Mkt Cap", 88.dp, ScreenerSortBy.MARKET_CAP_DESC, filter, onToggleColumnSort)
                        SortableHeaderCell("Volume", 78.dp, ScreenerSortBy.VOLUME_DESC, filter, onToggleColumnSort)
                        SortableHeaderCell("Score", 68.dp, ScreenerSortBy.FUNDAMENTAL_SCORE, filter, onToggleColumnSort)
                        Box(
                            modifier = Modifier
                                .width(76.dp)
                                .padding(horizontal = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Trade",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    HorizontalDivider(color = tradingColors.border)

                    // Paginated Table Data Rows
                    pagedResult.pageItems.forEachIndexed { index, stock ->
                        ScreenerTableRow(
                            stock = stock,
                            isStriped = index % 2 == 1,
                            onRowClick = { onOpenFundamentals(stock) },
                            onTradeClick = { onTradeStock(stock) }
                        )
                        if (index < pagedResult.pageItems.lastIndex) {
                            HorizontalDivider(
                                thickness = 0.6.dp,
                                color = tradingColors.border.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // Pagination Controls Footer
        ScreenerPaginationBar(
            paged = pagedResult,
            onGoToPage = onGoToPage,
            onPageSizeChange = onPageSizeChange
        )
    }
}

@Composable
private fun SortableHeaderCell(
    label: String,
    width: Dp,
    column: ScreenerSortBy,
    filter: ScreenerFilterState,
    onToggleSort: (ScreenerSortBy) -> Unit,
    alignment: Alignment = Alignment.CenterEnd
) {
    val isActive = filter.sortBy == column
    val color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    val dirLabel = if (isActive) {
        if (filter.sortAscending) "ascending" else "descending"
    } else "unsorted"

    Box(
        modifier = Modifier
            .width(width)
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable { onToggleSort(column) }
            .padding(horizontal = 6.dp)
            .semantics { contentDescription = "Sort by $label, currently $dirLabel" }
            .testTag("screener_sort_${column.name.lowercase()}"),
        contentAlignment = alignment
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.Bold,
                color = color,
                maxLines = 1
            )
            if (isActive) {
                Icon(
                    imageVector = if (filter.sortAscending) Icons.Rounded.ArrowUpward else Icons.Rounded.ArrowDownward,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(13.dp)
                )
            }
        }
    }
}

@Composable
private fun ScreenerTableRow(
    stock: StockMarketSnapshot,
    isStriped: Boolean,
    onRowClick: () -> Unit,
    onTradeClick: () -> Unit
) {
    val tradingColors = LocalTradingColors.current
    val isUp = stock.changePercent >= 0.0
    val chgColor = if (isUp) tradingColors.bullish else tradingColors.bearish
    val chgBg = if (isUp) tradingColors.bullishContainer else tradingColors.bearishContainer

    val rsi = stock.technical.rsi14
    val (rsiColor, rsiBg) = when {
        rsi >= 70.0 -> tradingColors.bearish to tradingColors.bearishContainer
        rsi <= 35.0 -> tradingColors.bullish to tradingColors.bullishContainer
        rsi >= 55.0 -> MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant to MaterialTheme.colorScheme.surfaceContainerHigh
    }

    val (maLabel, maColor, maBg) = when (stock.technical.maCrossover) {
        MaCrossoverSignal.GOLDEN_CROSS -> Triple("Golden X", tradingColors.bullish, tradingColors.bullishContainer)
        MaCrossoverSignal.BULLISH_CROSSOVER -> Triple("Bull 20>50", MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer)
        MaCrossoverSignal.BEARISH_CROSSOVER -> Triple("Bear 20<50", tradingColors.amber, tradingColors.amberContainer)
        MaCrossoverSignal.DEATH_CROSS -> Triple("Death X", tradingColors.bearish, tradingColors.bearishContainer)
    }

    val score = stock.scorecard.overallScore
    val scoreColor = when {
        score >= 80 -> tradingColors.bullish
        score >= 65 -> MaterialTheme.colorScheme.primary
        else -> tradingColors.amber
    }

    val rowBg = if (isStriped) {
        MaterialTheme.colorScheme.surfaceContainerLowest
    } else {
        Color.Transparent
    }

    Row(
        modifier = Modifier
            .background(rowBg)
            .clickable(onClick = onRowClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .testTag("screener_row_${stock.symbol}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Symbol + Sector
        Column(
            modifier = Modifier
                .width(116.dp)
                .padding(horizontal = 6.dp)
        ) {
            Text(
                text = stock.symbol,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stock.sector,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // LTP
        Text(
            text = RiskMath.formatPrice(stock.ltp, MarketSegment.NSE),
            style = MonoPriceSmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier
                .width(88.dp)
                .padding(horizontal = 6.dp)
        )

        // Change %
        Box(
            modifier = Modifier
                .width(78.dp)
                .padding(horizontal = 4.dp),
            contentAlignment = Alignment.CenterEnd
        ) {
            Surface(shape = RoundedCornerShape(6.dp), color = chgBg) {
                Text(
                    text = String.format(Locale.US, "%+.2f%%", stock.changePercent),
                    style = MonoPriceSmall,
                    fontWeight = FontWeight.Bold,
                    color = chgColor,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                    maxLines = 1
                )
            }
        }

        // RSI(14)
        Box(
            modifier = Modifier
                .width(78.dp)
                .padding(horizontal = 4.dp),
            contentAlignment = Alignment.CenterEnd
        ) {
            Surface(shape = RoundedCornerShape(6.dp), color = rsiBg) {
                Text(
                    text = String.format(Locale.US, "%.1f", rsi),
                    style = MonoPriceSmall,
                    fontWeight = FontWeight.Bold,
                    color = rsiColor,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    maxLines = 1
                )
            }
        }

        // MA Crossover Signal
        Box(
            modifier = Modifier
                .width(114.dp)
                .padding(horizontal = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(shape = RoundedCornerShape(6.dp), color = maBg) {
                Text(
                    text = maLabel,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = maColor,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                    maxLines = 1
                )
            }
        }

        // P/E
        Text(
            text = String.format(Locale.US, "%.1f", stock.livePeRatio),
            style = MonoPriceSmall,
            textAlign = TextAlign.End,
            modifier = Modifier
                .width(66.dp)
                .padding(horizontal = 6.dp)
        )

        // ROE %
        Text(
            text = String.format(Locale.US, "%.1f%%", stock.profile.roePercent),
            style = MonoPriceSmall,
            color = if (stock.profile.roePercent >= 18.0) tradingColors.bullish else MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            modifier = Modifier
                .width(72.dp)
                .padding(horizontal = 6.dp)
        )

        // Market Cap
        Text(
            text = formatMarketCapCompact(stock.profile.marketCapCr),
            style = MonoPriceSmall,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier
                .width(88.dp)
                .padding(horizontal = 6.dp)
        )

        // Volume
        Text(
            text = formatVolumeCompactTable(stock.volume),
            style = MonoPriceSmall,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier
                .width(78.dp)
                .padding(horizontal = 6.dp)
        )

        // Fundamental Score
        Box(
            modifier = Modifier
                .width(68.dp)
                .padding(horizontal = 4.dp),
            contentAlignment = Alignment.CenterEnd
        ) {
            Surface(shape = RoundedCornerShape(6.dp), color = scoreColor.copy(alpha = 0.16f)) {
                Text(
                    text = "$score",
                    style = MonoPriceSmall,
                    fontWeight = FontWeight.Bold,
                    color = scoreColor,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                )
            }
        }

        // Direct Trade Action
        Box(
            modifier = Modifier
                .width(76.dp)
                .padding(horizontal = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            OutlinedButton(
                onClick = onTradeClick,
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier
                    .heightIn(min = 36.dp)
                    .testTag("screener_trade_${stock.symbol}")
            ) {
                Text("Trade", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ScreenerPaginationBar(
    paged: PagedScreenerResult,
    onGoToPage: (Int) -> Unit,
    onPageSizeChange: (Int) -> Unit
) {
    val tradingColors = LocalTradingColors.current

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .testTag("screener_pagination_bar"),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, tradingColors.border)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Top pagination row: First / Prev / Page X of Y / Next / Last
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { onGoToPage(1) },
                        enabled = paged.currentPage > 1,
                        modifier = Modifier.testTag("screener_page_first")
                    ) {
                        Icon(Icons.Rounded.FirstPage, contentDescription = "First Page")
                    }
                    IconButton(
                        onClick = { onGoToPage(paged.currentPage - 1) },
                        enabled = paged.currentPage > 1,
                        modifier = Modifier.testTag("screener_page_prev")
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, contentDescription = "Previous Page")
                    }
                }

                Text(
                    text = "Page ${paged.currentPage} of ${paged.totalPages}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.testTag("screener_page_indicator")
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { onGoToPage(paged.currentPage + 1) },
                        enabled = paged.currentPage < paged.totalPages,
                        modifier = Modifier.testTag("screener_page_next")
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = "Next Page")
                    }
                    IconButton(
                        onClick = { onGoToPage(paged.totalPages) },
                        enabled = paged.currentPage < paged.totalPages,
                        modifier = Modifier.testTag("screener_page_last")
                    ) {
                        Icon(Icons.Rounded.LastPage, contentDescription = "Last Page")
                    }
                }
            }

            HorizontalDivider(thickness = 0.6.dp, color = tradingColors.border.copy(alpha = 0.6f))

            // Bottom row: rows per page selector
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Rows per page",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(6, 8, 12, 15).forEach { size ->
                        FilterChip(
                            selected = paged.pageSize == size,
                            onClick = { onPageSizeChange(size) },
                            label = { Text("$size", style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.testTag("screener_pagesize_$size")
                        )
                    }
                }
            }
        }
    }
}

private fun countActiveFilters(f: ScreenerFilterState): Int {
    var count = 0
    if (f.rsiFilter != RsiFilterZone.ANY) count++
    if (f.maCrossoverFilter != null) count++
    if (f.onlyAboveSma200) count++
    if (f.selectedSector != null) count++
    if (f.selectedCapTier != null) count++
    if (f.maxPe != null) count++
    if (f.minRoe != null) count++
    if (f.maxDebtToEquity != null) count++
    if (f.minDividendYield != null) count++
    if (f.onlyNear52WeekHigh) count++
    if (f.onlyPositiveToday) count++
    return count
}

private fun formatMarketCapCompact(cr: Double): String {
    return if (cr >= 100_000.0) {
        String.format(Locale.US, "₹%.1fL Cr", cr / 100_000.0)
    } else {
        String.format(Locale.US, "₹%.0f Cr", cr)
    }
}

private fun formatVolumeCompactTable(vol: Long): String {
    return when {
        vol >= 10_000_000L -> String.format(Locale.US, "%.1fM", vol / 1_000_000.0)
        vol >= 1_000_000L -> String.format(Locale.US, "%.2fM", vol / 1_000_000.0)
        vol >= 1_000L -> String.format(Locale.US, "%.0fK", vol / 1_000.0)
        else -> vol.toString()
    }
}
