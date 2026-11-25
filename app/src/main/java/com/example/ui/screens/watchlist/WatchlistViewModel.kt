package com.example.ui.screens.watchlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.alerts.PriceAlert
import com.example.data.alerts.PriceAlertCondition
import com.example.data.alerts.PriceAlertRepository
import com.example.data.feed.live.AngelScripSearchResult
import com.example.data.feed.live.FeedType
import com.example.data.feed.live.LiveConnectionStatus
import com.example.data.feed.live.SegmentFeedStatus
import com.example.data.news.MarketNewsArticle
import com.example.data.news.MarketNewsFeedState
import com.example.data.news.MarketNewsRepository
import com.example.data.news.WatchlistNewsGroundingEngine
import com.example.data.repository.MarketDataRepository
import com.example.data.repository.TradingRepository
import com.example.domain.engine.MarketClock
import com.example.domain.engine.RiskMath
import com.example.domain.model.AccountState
import com.example.domain.model.FeedConnectionState
import com.example.domain.model.FeedSourceType
import com.example.domain.model.Instrument
import com.example.domain.model.InstrumentType
import com.example.domain.model.MarketSegment
import com.example.domain.model.OrderSide
import com.example.domain.model.Position
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.math.BigDecimal
import javax.inject.Inject

data class WatchlistUiState(
    val instruments: List<Instrument> = emptyList(),
    val searchQuery: String = "",
    val selectedSegment: MarketSegment? = null,
    val selectedType: InstrumentType? = null,
    val accountState: AccountState = AccountState(BigDecimal("1000000"), BigDecimal("1000000"), BigDecimal.ZERO),
    val unrealisedPnl: BigDecimal = BigDecimal.ZERO,
    val cryptoUnrealisedPnl: BigDecimal = BigDecimal.ZERO,
    val feedState: FeedConnectionState = FeedConnectionState.Connecting(FeedSourceType.SIMULATED),
    val activeFeedType: FeedType = FeedType.SIMULATED,
    val liveConnectionStatus: LiveConnectionStatus = LiveConnectionStatus.DISCONNECTED,
    val liveStatusMessage: String = "",
    val segmentStatuses: Map<MarketSegment, SegmentFeedStatus> = emptyMap(),
    val riskControlsSuspended: Boolean = false,
    val suspendedBannerMessage: String = "",
    val istClockText: String = MarketClock.formatCurrentIstClock(),
    val hasAngelOneCredentials: Boolean = false,
    val liveSearchResults: List<AngelScripSearchResult> = emptyList(),
    val isLiveSearching: Boolean = false,
    val liveSearchError: String? = null,
    val addingToken: String? = null,
    val watchlistNewsState: MarketNewsFeedState = MarketNewsFeedState(),
    val watchlistNewsSymbols: List<String> = emptyList(),
    val selectedNewsSymbol: String? = null,
    val filteredWatchlistNews: List<MarketNewsArticle> = emptyList(),
    val priceAlerts: List<PriceAlert> = emptyList(),
    val activeAlertsBySymbol: Map<String, List<PriceAlert>> = emptyMap(),
    val ltpBySymbol: Map<String, BigDecimal> = emptyMap()
)

@HiltViewModel
class WatchlistViewModel @Inject constructor(
    private val marketDataRepository: MarketDataRepository,
    tradingRepository: TradingRepository,
    private val marketNewsRepository: MarketNewsRepository,
    private val priceAlertRepository: PriceAlertRepository
) : ViewModel() {

    private val searchQuery = MutableStateFlow("")
    private val selectedSegment = MutableStateFlow<MarketSegment?>(null)
    private val selectedType = MutableStateFlow<InstrumentType?>(null)
    private val selectedNewsSymbol = MutableStateFlow<String?>(null)

    private val _liveSearchState = MutableStateFlow(LiveSearchSnapshot())
    private val _openSymbolEvents = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val openSymbolEvents: SharedFlow<String> = _openSymbolEvents.asSharedFlow()

    private var liveSearchJob: Job? = null
    @Volatile
    private var latestAllInstruments: List<Instrument> = emptyList()

    init {
        viewModelScope.launch {
            val initialInstruments = marketDataRepository.instruments.first { it.isNotEmpty() }
            latestAllInstruments = initialInstruments
            marketNewsRepository.refreshForWatchlist(
                instruments = initialInstruments,
                focusSymbol = null,
                force = false
            )
        }
    }

    private val clockFlow = flow {
        while (true) {
            emit(MarketClock.formatCurrentIstClock())
            delay(1000L)
        }
    }

    private val newsBundleFlow = combine(
        marketNewsRepository.state,
        selectedNewsSymbol,
        priceAlertRepository.allAlerts
    ) { newsState, newsSymbol, alerts ->
        NewsAndAlertsBundle(newsState, newsSymbol, alerts)
    }

    private val feedHealthFlow = combine(
        marketDataRepository.activeFeedType,
        marketDataRepository.liveConnectionStatus,
        marketDataRepository.liveStatusMessage,
        marketDataRepository.segmentStatuses,
        marketDataRepository.riskControlsSuspended,
        marketDataRepository.suspendedBannerMessage,
        marketDataRepository.brokerSettings
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val segStatuses = values[3] as Map<MarketSegment, SegmentFeedStatus>
        val settings = values[6] as com.example.data.feed.live.BrokerFeedSettings
        FeedHealthSnapshot(
            activeFeed = values[0] as FeedType,
            liveStatus = values[1] as LiveConnectionStatus,
            statusMessage = values[2] as String,
            segmentStatuses = segStatuses,
            riskControlsSuspended = values[4] as Boolean,
            suspendedBannerMessage = values[5] as String,
            hasAngelCreds = settings.hasAngelOneCredentials()
        )
    }

    val uiState: StateFlow<WatchlistUiState> = combine(
        marketDataRepository.instruments,
        searchQuery,
        selectedSegment,
        selectedType,
        tradingRepository.accountState,
        tradingRepository.positions,
        marketDataRepository.connectionState,
        feedHealthFlow,
        clockFlow,
        _liveSearchState,
        newsBundleFlow
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val allInstruments = values[0] as List<Instrument>
        latestAllInstruments = allInstruments
        val query = values[1] as String
        val segmentFilter = values[2] as MarketSegment?
        val typeFilter = values[3] as InstrumentType?
        val account = values[4] as AccountState
        @Suppress("UNCHECKED_CAST")
        val positions = values[5] as List<Position>
        val feedState = values[6] as FeedConnectionState
        val health = values[7] as FeedHealthSnapshot
        val istClock = values[8] as String
        val liveSearch = values[9] as LiveSearchSnapshot
        val newsBundle = values[10] as NewsAndAlertsBundle
        val newsState = newsBundle.newsState
        val newsSymbol = newsBundle.selectedSymbol
        val allAlerts = newsBundle.alerts

        val ltpMap = allInstruments.associate { it.symbol to it.ltp }
        var indiaUnrealised = BigDecimal.ZERO
        var cryptoUnrealised = BigDecimal.ZERO
        positions.forEach { pos ->
            val ltp = ltpMap[pos.symbol] ?: pos.averageEntryPrice
            val pnl = RiskMath.calculateUnrealisedPnl(
                entryPrice = pos.averageEntryPrice,
                ltp = ltp,
                quantity = pos.quantity,
                isLong = pos.side == OrderSide.BUY
            )
            if (pos.segment == MarketSegment.CRYPTO) {
                cryptoUnrealised = cryptoUnrealised.add(pnl)
            } else {
                indiaUnrealised = indiaUnrealised.add(pnl)
            }
        }

        val filtered = allInstruments.filter { inst ->
            val matchesQuery = query.isBlank() ||
                inst.symbol.contains(query, ignoreCase = true) ||
                inst.displayName.contains(query, ignoreCase = true)
            val matchesSegment = segmentFilter == null || inst.segment == segmentFilter
            val matchesType = typeFilter == null || inst.type == typeFilter
            matchesQuery && matchesSegment && matchesType
        }

        val activeNewsScope = if (filtered.isNotEmpty()) filtered else allInstruments
        val watchlistSymbols = WatchlistNewsGroundingEngine.extractWatchlistSymbols(activeNewsScope)
        val effectiveNewsSymbol = newsSymbol?.takeIf { sym ->
            watchlistSymbols.any { it.equals(sym, ignoreCase = true) }
        }
        val filteredNews = WatchlistNewsGroundingEngine.filterArticlesForWatchlist(
            articles = newsState.articles,
            watchlist = activeNewsScope,
            selectedSymbol = effectiveNewsSymbol,
            watchlistOnly = true
        )

        val activeAlertsBySymbol = allAlerts
            .filter { it.isActive }
            .groupBy { it.symbol }

        WatchlistUiState(
            instruments = filtered,
            searchQuery = query,
            selectedSegment = segmentFilter,
            selectedType = typeFilter,
            accountState = account,
            unrealisedPnl = indiaUnrealised,
            cryptoUnrealisedPnl = cryptoUnrealised,
            feedState = feedState,
            activeFeedType = health.activeFeed,
            liveConnectionStatus = health.liveStatus,
            liveStatusMessage = health.statusMessage,
            segmentStatuses = health.segmentStatuses,
            riskControlsSuspended = health.riskControlsSuspended,
            suspendedBannerMessage = health.suspendedBannerMessage,
            istClockText = istClock,
            hasAngelOneCredentials = health.hasAngelCreds,
            liveSearchResults = liveSearch.results,
            isLiveSearching = liveSearch.isSearching,
            liveSearchError = liveSearch.error,
            addingToken = liveSearch.addingToken,
            watchlistNewsState = newsState,
            watchlistNewsSymbols = watchlistSymbols,
            selectedNewsSymbol = effectiveNewsSymbol,
            filteredWatchlistNews = filteredNews,
            priceAlerts = allAlerts,
            activeAlertsBySymbol = activeAlertsBySymbol,
            ltpBySymbol = ltpMap
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = WatchlistUiState()
    )

    fun hasNotificationPermission(): Boolean = priceAlertRepository.hasSystemNotificationPermission()

    fun createPriceAlert(
        instrument: Instrument,
        targetPrice: BigDecimal,
        condition: PriceAlertCondition,
        note: String
    ) {
        viewModelScope.launch {
            priceAlertRepository.createAlert(
                instrument = instrument,
                targetPrice = targetPrice,
                condition = condition,
                note = note
            )
        }
    }

    fun deletePriceAlert(alertId: String) {
        viewModelScope.launch {
            priceAlertRepository.deleteAlert(alertId)
        }
    }

    fun rearmPriceAlert(alert: PriceAlert) {
        viewModelScope.launch {
            val ltp = latestAllInstruments.firstOrNull { it.symbol == alert.symbol }?.ltp ?: alert.targetPrice
            priceAlertRepository.rearmAlert(alert, ltp)
        }
    }

    fun clearTriggeredPriceAlerts() {
        viewModelScope.launch {
            priceAlertRepository.clearTriggeredAlerts()
        }
    }

    fun onSelectNewsSymbol(symbol: String?) {
        selectedNewsSymbol.value = symbol?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun refreshWatchlistNews(focusSymbol: String? = selectedNewsSymbol.value, force: Boolean = true) {
        viewModelScope.launch {
            val currentList = latestAllInstruments.ifEmpty {
                marketDataRepository.instruments.first()
            }
            if (currentList.isNotEmpty()) {
                marketNewsRepository.refreshForWatchlist(
                    instruments = currentList,
                    focusSymbol = focusSymbol,
                    force = force
                )
            }
        }
    }

    fun onSearchChange(query: String) {
        searchQuery.value = query
        triggerLiveSearch()
    }

    fun onQuickLiveSearch(query: String, typeHint: InstrumentType? = null) {
        selectedSegment.value = null
        selectedType.value = typeHint
        searchQuery.value = query
        triggerLiveSearch(immediate = true)
    }

    fun onSegmentSelected(segment: MarketSegment?) {
        selectedSegment.value = if (selectedSegment.value == segment) null else segment
        triggerLiveSearch()
    }

    fun onTypeSelected(type: InstrumentType?) {
        selectedType.value = if (selectedType.value == type) null else type
        triggerLiveSearch()
    }

    private fun triggerLiveSearch(immediate: Boolean = false) {
        liveSearchJob?.cancel()
        val q = searchQuery.value.trim()
        val seg = selectedSegment.value
        val type = selectedType.value

        if (q.length < 2 || seg == MarketSegment.CRYPTO || !marketDataRepository.brokerSettings.value.hasAngelOneCredentials()) {
            _liveSearchState.value = LiveSearchSnapshot()
            return
        }

        liveSearchJob = viewModelScope.launch {
            if (!immediate) delay(350L)
            _liveSearchState.value = _liveSearchState.value.copy(isSearching = true, error = null)
            val result = marketDataRepository.searchLiveInstruments(
                query = q,
                segmentFilter = seg,
                typeFilter = type
            )
            result.onSuccess { list ->
                _liveSearchState.value = _liveSearchState.value.copy(
                    results = list,
                    isSearching = false,
                    error = if (list.isEmpty()) "No Angel One scrips found for \"$q\"" else null
                )
            }.onFailure { err ->
                _liveSearchState.value = _liveSearchState.value.copy(
                    results = emptyList(),
                    isSearching = false,
                    error = err.message ?: "Live Angel One search failed"
                )
            }
        }
    }

    fun addAndOpenLiveInstrument(result: AngelScripSearchResult) {
        if (_liveSearchState.value.addingToken != null) return
        viewModelScope.launch {
            _liveSearchState.value = _liveSearchState.value.copy(addingToken = result.symbolToken, error = null)
            val added = marketDataRepository.addLiveInstrument(result)
            _liveSearchState.value = _liveSearchState.value.copy(addingToken = null)
            added.onSuccess { inst ->
                _openSymbolEvents.emit(inst.symbol)
            }.onFailure { err ->
                _liveSearchState.value = _liveSearchState.value.copy(
                    error = err.message ?: "Could not add ${result.tradingSymbol}"
                )
            }
        }
    }

    fun reconnectFeed() {
        viewModelScope.launch {
            marketDataRepository.reconnectActiveFeed()
        }
    }

    fun switchToSimulatedFeed() {
        viewModelScope.launch {
            marketDataRepository.setActiveFeed(FeedType.SIMULATED)
        }
    }

    private data class LiveSearchSnapshot(
        val results: List<AngelScripSearchResult> = emptyList(),
        val isSearching: Boolean = false,
        val error: String? = null,
        val addingToken: String? = null
    )

    private data class NewsAndAlertsBundle(
        val newsState: MarketNewsFeedState,
        val selectedSymbol: String?,
        val alerts: List<PriceAlert>
    )

    private data class FeedHealthSnapshot(
        val activeFeed: FeedType,
        val liveStatus: LiveConnectionStatus,
        val statusMessage: String,
        val segmentStatuses: Map<MarketSegment, SegmentFeedStatus>,
        val riskControlsSuspended: Boolean,
        val suspendedBannerMessage: String,
        val hasAngelCreds: Boolean
    )
}
