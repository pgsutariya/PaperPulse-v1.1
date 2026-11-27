package com.example.ui.screens.market

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.example.data.market.MarketIntelligenceRepository
import com.example.data.repository.MarketDataRepository
import com.example.domain.market.FiiDiiDashboardData
import com.example.domain.market.GlobalMarketQuote
import com.example.domain.market.GlobalRegion
import com.example.domain.market.LiveFundamentalDetails
import com.example.domain.market.MaCrossoverSignal
import com.example.domain.market.MarketCapTier
import com.example.domain.market.MoverCategory
import com.example.domain.market.PagedScreenerResult
import com.example.domain.market.RsiFilterZone
import com.example.domain.market.ScreenerFilterState
import com.example.domain.market.ScreenerPreset
import com.example.domain.market.ScreenerSortBy
import com.example.domain.market.SectorHeatmapSummary
import com.example.domain.market.StockMarketSnapshot
import com.example.domain.market.StockScreenerEngine
import com.example.ui.navigation.MarketPulseRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class MarketPulseTab(val label: String) {
    MOVERS("Movers"),
    HEATMAP("Heatmap"),
    FII_DII("Market Flow"),
    GLOBAL("Global"),
    SCREENER("Screener")
}

data class MarketPulseUiState(
    val selectedTab: MarketPulseTab = MarketPulseTab.MOVERS,
    val selectedMoverCategory: MoverCategory = MoverCategory.TOP_GAINERS,
    val selectedGlobalRegion: GlobalRegion = GlobalRegion.ALL,
    val screenerFilter: ScreenerFilterState = ScreenerFilterState(),
    val allStocks: List<StockMarketSnapshot> = emptyList(),
    val sectorHeatmap: List<SectorHeatmapSummary> = emptyList(),
    val moversList: List<StockMarketSnapshot> = emptyList(),
    val screenedStocks: List<StockMarketSnapshot> = emptyList(),
    val pagedScreener: PagedScreenerResult = PagedScreenerResult(
        pageItems = emptyList(),
        allMatchingCount = 0,
        currentPage = 1,
        totalPages = 1,
        pageSize = 8,
        startIndex = 0,
        endIndex = 0
    ),
    val totalUniverseCount: Int = 0,
    val globalMarkets: List<GlobalMarketQuote> = emptyList(),
    val fiiDii: FiiDiiDashboardData? = null,
    val availableSectors: List<String> = emptyList(),
    val selectedFundamentalSymbol: String? = null,
    val selectedFundamentalStock: StockMarketSnapshot? = null,
    val selectedFundamentalDetails: LiveFundamentalDetails? = null,
    val isLoadingFundamentalDetails: Boolean = false,
    val isRefreshing: Boolean = false,
    val lastSyncLabel: String = ""
)

@HiltViewModel
class MarketPulseViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val intelligenceRepository: MarketIntelligenceRepository,
    private val marketDataRepository: MarketDataRepository
) : ViewModel() {

    private val route = runCatching { savedStateHandle.toRoute<MarketPulseRoute>() }
        .getOrDefault(MarketPulseRoute())

    private val initialTab = MarketPulseTab.entries
        .getOrElse(route.initialTab) { MarketPulseTab.MOVERS }

    private val _selectedTab = MutableStateFlow(initialTab)
    private val _selectedMoverCategory = MutableStateFlow(MoverCategory.TOP_GAINERS)
    private val _selectedGlobalRegion = MutableStateFlow(GlobalRegion.ALL)
    private val _screenerFilter = MutableStateFlow(ScreenerFilterState())
    private val _selectedFundamentalSymbol = MutableStateFlow<String?>(null)
    private val _fundamentalDetailsMap = MutableStateFlow<Map<String, LiveFundamentalDetails>>(emptyMap())
    private val _isLoadingFundamentalDetails = MutableStateFlow(false)

    private val selectionFlow = combine(
        _selectedTab,
        _selectedMoverCategory,
        _selectedGlobalRegion,
        _screenerFilter,
        _selectedFundamentalSymbol
    ) { tab, moverCat, region, filter, fundSym ->
        SelectionBundle(tab, moverCat, region, filter, fundSym)
    }

    private val dataFlow = combine(
        intelligenceRepository.stocks,
        intelligenceRepository.globalMarkets,
        intelligenceRepository.fiiDiiData,
        intelligenceRepository.isRefreshing,
        intelligenceRepository.lastSyncLabel
    ) { stocks, globals, fiiDii, refreshing, syncLabel ->
        DataBundle(stocks, globals, fiiDii, refreshing, syncLabel)
    }

    private val detailsFlow = combine(
        _fundamentalDetailsMap,
        _isLoadingFundamentalDetails
    ) { map, loading -> map to loading }

    val uiState: StateFlow<MarketPulseUiState> = combine(
        selectionFlow,
        dataFlow,
        detailsFlow
    ) { sel, data, detailsPair ->
        val movers = StockScreenerEngine.computeMovers(
            universe = data.stocks,
            category = sel.moverCategory,
            limit = 14
        )
        val paged = StockScreenerEngine.filterSortAndPaginate(
            universe = data.stocks,
            filter = sel.screenerFilter
        )
        val filteredGlobals = if (sel.globalRegion == GlobalRegion.ALL) {
            data.globals
        } else {
            data.globals.filter { it.region == sel.globalRegion }
        }
        val selectedStock = sel.fundamentalSymbol?.let { sym ->
            data.stocks.firstOrNull { it.symbol.equals(sym, ignoreCase = true) }
        }
        val selectedDetails = selectedStock?.let { detailsPair.first[it.symbol.uppercase()] }

        val sectorSummary = StockScreenerEngine.computeSectorHeatmap(data.stocks)

        MarketPulseUiState(
            selectedTab = sel.tab,
            selectedMoverCategory = sel.moverCategory,
            selectedGlobalRegion = sel.globalRegion,
            screenerFilter = sel.screenerFilter.copy(currentPage = paged.currentPage),
            allStocks = data.stocks,
            sectorHeatmap = sectorSummary,
            moversList = movers,
            screenedStocks = paged.pageItems,
            pagedScreener = paged,
            totalUniverseCount = data.stocks.size,
            globalMarkets = filteredGlobals,
            fiiDii = data.fiiDii,
            availableSectors = intelligenceRepository.availableSectors,
            selectedFundamentalSymbol = sel.fundamentalSymbol,
            selectedFundamentalStock = selectedStock,
            selectedFundamentalDetails = selectedDetails,
            isLoadingFundamentalDetails = detailsPair.second,
            isRefreshing = data.isRefreshing,
            lastSyncLabel = data.lastSyncLabel
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = MarketPulseUiState(
            selectedTab = initialTab,
            availableSectors = intelligenceRepository.availableSectors
        )
    )

    init {
        viewModelScope.launch {
            marketDataRepository.instruments.collect { liveList ->
                intelligenceRepository.mergeWatchlistInstruments(liveList)
            }
        }
        refreshMarketData()
    }

    fun refreshMarketData() {
        viewModelScope.launch {
            intelligenceRepository.refreshAll()
        }
    }

    fun selectTab(tab: MarketPulseTab) {
        _selectedTab.value = tab
    }

    fun selectMoverCategory(category: MoverCategory) {
        _selectedMoverCategory.value = category
    }

    fun selectGlobalRegion(region: GlobalRegion) {
        _selectedGlobalRegion.value = region
    }

    fun openFundamentals(symbol: String) {
        _selectedFundamentalSymbol.value = symbol
        loadFundamentalDetails(symbol, forceRefresh = false)
    }

    fun refreshFundamentalDetails() {
        val sym = _selectedFundamentalSymbol.value ?: return
        loadFundamentalDetails(sym, forceRefresh = true)
    }

    private fun loadFundamentalDetails(symbol: String, forceRefresh: Boolean) {
        val snap = intelligenceRepository.findSnapshotBySymbol(symbol) ?: return
        viewModelScope.launch {
            _isLoadingFundamentalDetails.value = true
            try {
                val details = intelligenceRepository.fetchFundamentalDetails(snap, forceRefresh = forceRefresh)
                _fundamentalDetailsMap.update { it + (snap.symbol.uppercase() to details) }
            } finally {
                _isLoadingFundamentalDetails.value = false
            }
        }
    }

    fun closeFundamentals() {
        _selectedFundamentalSymbol.value = null
    }

    fun updateScreenerQuery(query: String) {
        _screenerFilter.update { it.copy(searchQuery = query, currentPage = 1) }
    }

    fun selectScreenerPreset(preset: ScreenerPreset) {
        _screenerFilter.update { current ->
            val (nextSort, nextAsc) = when (preset) {
                ScreenerPreset.RSI_OVERSOLD -> ScreenerSortBy.RSI to true
                ScreenerPreset.RSI_OVERBOUGHT -> ScreenerSortBy.RSI to false
                ScreenerPreset.GOLDEN_CROSS -> ScreenerSortBy.CHANGE_DESC to false
                ScreenerPreset.VALUE_GEMS -> ScreenerSortBy.PE_ASC to true
                ScreenerPreset.HIGH_ROE_QUALITY -> ScreenerSortBy.ROE_DESC to false
                ScreenerPreset.VOLUME_SHOCKERS -> ScreenerSortBy.VOLUME_DESC to false
                ScreenerPreset.BREAKOUT_52W_HIGH -> ScreenerSortBy.CHANGE_DESC to false
                else -> ScreenerSortBy.FUNDAMENTAL_SCORE to false
            }
            current.copy(
                preset = preset,
                sortBy = nextSort,
                sortAscending = nextAsc,
                currentPage = 1
            )
        }
    }

    fun selectRsiFilter(zone: RsiFilterZone) {
        _screenerFilter.update { it.copy(rsiFilter = zone, currentPage = 1) }
    }

    fun selectMaCrossoverFilter(signal: MaCrossoverSignal?) {
        _screenerFilter.update { it.copy(maCrossoverFilter = signal, currentPage = 1) }
    }

    fun toggleOnlyAboveSma200(enabled: Boolean) {
        _screenerFilter.update { it.copy(onlyAboveSma200 = enabled, currentPage = 1) }
    }

    fun selectScreenerSector(sector: String?) {
        _screenerFilter.update { it.copy(selectedSector = sector, currentPage = 1) }
    }

    fun selectCapTier(tier: MarketCapTier?) {
        _screenerFilter.update { it.copy(selectedCapTier = tier, currentPage = 1) }
    }

    fun updateMaxPe(maxPe: Double?) {
        _screenerFilter.update { it.copy(maxPe = maxPe, currentPage = 1) }
    }

    fun updateMinRoe(minRoe: Double?) {
        _screenerFilter.update { it.copy(minRoe = minRoe, currentPage = 1) }
    }

    fun updateMaxDebtToEquity(maxDe: Double?) {
        _screenerFilter.update { it.copy(maxDebtToEquity = maxDe, currentPage = 1) }
    }

    fun updateMinDividendYield(minYield: Double?) {
        _screenerFilter.update { it.copy(minDividendYield = minYield, currentPage = 1) }
    }

    fun toggleOnlyNear52WeekHigh(enabled: Boolean) {
        _screenerFilter.update { it.copy(onlyNear52WeekHigh = enabled, currentPage = 1) }
    }

    fun toggleOnlyPositiveToday(enabled: Boolean) {
        _screenerFilter.update { it.copy(onlyPositiveToday = enabled, currentPage = 1) }
    }

    fun toggleColumnSort(column: ScreenerSortBy) {
        _screenerFilter.update { current ->
            if (current.sortBy == column) {
                current.copy(sortAscending = !current.sortAscending, currentPage = 1)
            } else {
                val defaultAsc = column == ScreenerSortBy.SYMBOL || column == ScreenerSortBy.PE_ASC
                current.copy(sortBy = column, sortAscending = defaultAsc, currentPage = 1)
            }
        }
    }

    fun goToScreenerPage(page: Int) {
        _screenerFilter.update { it.copy(currentPage = page.coerceAtLeast(1)) }
    }

    fun setScreenerPageSize(size: Int) {
        _screenerFilter.update { it.copy(pageSize = size.coerceIn(4, 25), currentPage = 1) }
    }

    fun resetScreenerFilters() {
        _screenerFilter.update { ScreenerFilterState(pageSize = it.pageSize) }
    }

    fun prepareStockAndNavigate(snapshot: StockMarketSnapshot, onReady: (String) -> Unit) {
        viewModelScope.launch {
            intelligenceRepository.ensureTradeableStock(snapshot)
            onReady(snapshot.symbol)
        }
    }

    private data class SelectionBundle(
        val tab: MarketPulseTab,
        val moverCategory: MoverCategory,
        val globalRegion: GlobalRegion,
        val screenerFilter: ScreenerFilterState,
        val fundamentalSymbol: String?
    )

    private data class DataBundle(
        val stocks: List<StockMarketSnapshot>,
        val globals: List<GlobalMarketQuote>,
        val fiiDii: FiiDiiDashboardData,
        val isRefreshing: Boolean,
        val lastSyncLabel: String
    )
}
