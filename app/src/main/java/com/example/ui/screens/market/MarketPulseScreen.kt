package com.example.ui.screens.market

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Unified Market Pulse & Screener Hub:
 * - Tab 0: Movers (Top Gainers, Top Losers, New 52W High, New 52W Low, Top By Volume)
 * - Tab 1: FII / DII Institutional Buy/Sell Cash Flow Data
 * - Tab 2: Global Market Data (US, India, Europe, Asia, Commodities & FX)
 * - Tab 3: Stock Screener & Fundamental Analysis Tool
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarketPulseScreen(
    onBack: () -> Unit,
    onOpenInstrument: (String) -> Unit,
    onOpenChart: (String) -> Unit,
    viewModel: MarketPulseViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Market Pulse & Screener",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = state.lastSyncLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("market_pulse_back_button")
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = viewModel::refreshMarketData,
                        enabled = !state.isRefreshing,
                        modifier = Modifier.testTag("market_pulse_refresh_button")
                    ) {
                        if (state.isRefreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Rounded.Refresh, contentDescription = "Sync Live Market Data")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            PrimaryScrollableTabRow(
                selectedTabIndex = state.selectedTab.ordinal,
                containerColor = MaterialTheme.colorScheme.surface,
                edgePadding = 12.dp
            ) {
                MarketPulseTab.entries.forEach { tab ->
                    Tab(
                        selected = state.selectedTab == tab,
                        onClick = { viewModel.selectTab(tab) },
                        modifier = Modifier.testTag("market_pulse_tab_${tab.name.lowercase()}"),
                        text = {
                            Text(
                                text = tab.label,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = if (state.selectedTab == tab) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    )
                }
            }

            when (state.selectedTab) {
                MarketPulseTab.MOVERS -> MarketMoversTab(
                    selectedCategory = state.selectedMoverCategory,
                    movers = state.moversList,
                    onSelectCategory = viewModel::selectMoverCategory,
                    onOpenFundamentals = { viewModel.openFundamentals(it.symbol) },
                    onTradeStock = { stock ->
                        viewModel.prepareStockAndNavigate(stock, onOpenInstrument)
                    }
                )

                MarketPulseTab.HEATMAP -> MarketHeatmapTab(
                    stocks = state.allStocks,
                    sectorSummaries = state.sectorHeatmap,
                    onOpenFundamentals = { viewModel.openFundamentals(it.symbol) }
                )

                MarketPulseTab.FII_DII -> FiiDiiTab(
                    data = state.fiiDii,
                    isRefreshing = state.isRefreshing,
                    onRefreshFlow = viewModel::refreshMarketData
                )

                MarketPulseTab.GLOBAL -> GlobalMarketsTab(
                    selectedRegion = state.selectedGlobalRegion,
                    quotes = state.globalMarkets,
                    onSelectRegion = viewModel::selectGlobalRegion
                )

                MarketPulseTab.SCREENER -> StockScreenerTableTab(
                    filter = state.screenerFilter,
                    pagedResult = state.pagedScreener,
                    totalUniverse = state.totalUniverseCount,
                    availableSectors = state.availableSectors,
                    onQueryChange = viewModel::updateScreenerQuery,
                    onSelectPreset = viewModel::selectScreenerPreset,
                    onSelectRsiFilter = viewModel::selectRsiFilter,
                    onSelectMaCrossover = viewModel::selectMaCrossoverFilter,
                    onToggleAboveSma200 = viewModel::toggleOnlyAboveSma200,
                    onSelectSector = viewModel::selectScreenerSector,
                    onSelectCapTier = viewModel::selectCapTier,
                    onMaxPeChange = viewModel::updateMaxPe,
                    onMinRoeChange = viewModel::updateMinRoe,
                    onMaxDebtChange = viewModel::updateMaxDebtToEquity,
                    onMinDividendChange = viewModel::updateMinDividendYield,
                    onToggleNear52High = viewModel::toggleOnlyNear52WeekHigh,
                    onTogglePositiveOnly = viewModel::toggleOnlyPositiveToday,
                    onToggleColumnSort = viewModel::toggleColumnSort,
                    onGoToPage = viewModel::goToScreenerPage,
                    onPageSizeChange = viewModel::setScreenerPageSize,
                    onResetFilters = viewModel::resetScreenerFilters,
                    onOpenFundamentals = { viewModel.openFundamentals(it.symbol) },
                    onTradeStock = { stock ->
                        viewModel.prepareStockAndNavigate(stock, onOpenInstrument)
                    }
                )
            }
        }
    }

    state.selectedFundamentalStock?.let { selected ->
        FundamentalAnalysisDialog(
            stock = selected,
            liveDetails = state.selectedFundamentalDetails,
            isLoadingDetails = state.isLoadingFundamentalDetails,
            onRefreshDetails = viewModel::refreshFundamentalDetails,
            onOpenChart = {
                viewModel.closeFundamentals()
                viewModel.prepareStockAndNavigate(selected, onOpenChart)
            },
            onTradeNow = {
                viewModel.closeFundamentals()
                viewModel.prepareStockAndNavigate(selected, onOpenInstrument)
            },
            onDismiss = viewModel::closeFundamentals
        )
    }
}
