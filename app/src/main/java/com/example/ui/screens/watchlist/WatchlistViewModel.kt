package com.example.ui.screens.watchlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.feed.live.AngelScripSearchResult
import com.example.data.feed.live.FeedType
import com.example.data.feed.live.LiveConnectionStatus
import com.example.data.feed.live.SegmentFeedStatus
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
    val addingToken: String? = null
)

@HiltViewModel
class WatchlistViewModel @Inject constructor(
    private val marketDataRepository: MarketDataRepository,
    tradingRepository: TradingRepository
) : ViewModel() {

    private val searchQuery = MutableStateFlow("")
    private val selectedSegment = MutableStateFlow<MarketSegment?>(null)
    private val selectedType = MutableStateFlow<InstrumentType?>(null)

    private val _liveSearchState = MutableStateFlow(LiveSearchSnapshot())
    private val _openSymbolEvents = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val openSymbolEvents: SharedFlow<String> = _openSymbolEvents.asSharedFlow()

    private var liveSearchJob: Job? = null

    private val clockFlow = flow {
        while (true) {
            emit(MarketClock.formatCurrentIstClock())
            delay(1000L)
        }
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
        _liveSearchState
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val allInstruments = values[0] as List<Instrument>
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

        val ltpMap = allInstruments.associate { it.symbol to it.ltp }
        val totalUnrealised = positions.fold(BigDecimal.ZERO) { acc, pos ->
            val ltp = ltpMap[pos.symbol] ?: pos.averageEntryPrice
            acc.add(
                RiskMath.calculateUnrealisedPnl(
                    entryPrice = pos.averageEntryPrice,
                    ltp = ltp,
                    quantity = pos.quantity,
                    isLong = pos.side == OrderSide.BUY
                )
            )
        }

        val filtered = allInstruments.filter { inst ->
            val matchesQuery = query.isBlank() ||
                inst.symbol.contains(query, ignoreCase = true) ||
                inst.displayName.contains(query, ignoreCase = true)
            val matchesSegment = segmentFilter == null || inst.segment == segmentFilter
            val matchesType = typeFilter == null || inst.type == typeFilter
            matchesQuery && matchesSegment && matchesType
        }

        WatchlistUiState(
            instruments = filtered,
            searchQuery = query,
            selectedSegment = segmentFilter,
            selectedType = typeFilter,
            accountState = account,
            unrealisedPnl = totalUnrealised,
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
            addingToken = liveSearch.addingToken
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = WatchlistUiState()
    )

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
