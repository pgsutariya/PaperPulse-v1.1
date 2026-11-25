package com.example.ui.screens.signals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.ai.AiConnectionStatus
import com.example.data.ai.AiMarketRepository
import com.example.data.repository.TradingPreferencesRepository
import com.example.data.signal.SignalRepository
import com.example.data.signal.SignalUiItem
import com.example.domain.model.InstrumentType
import com.example.domain.model.MarketSegment
import com.example.domain.model.OrderSide
import com.example.domain.model.ProductType
import com.example.domain.model.ValidityType
import com.example.domain.signal.AiSignalSecondOpinion
import com.example.domain.signal.AiUiState
import com.example.domain.signal.InstrumentSignalPerformance
import com.example.domain.signal.SignalAccuracyStats
import com.example.domain.signal.SignalAssetCategory
import com.example.domain.signal.SignalConfidence
import com.example.domain.signal.SignalDirection
import com.example.domain.signal.SignalOutcome
import com.example.domain.signal.SignalRecord
import com.example.domain.signal.SignalStrategy
import com.example.domain.signal.TradeSignal
import com.example.ui.navigation.InstrumentDetailRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class SignalsTab(val label: String) {
    ACTIVE("Active"),
    SAVED("Saved"),
    HISTORY("Performance")
}

data class SignalsFilterState(
    val tab: SignalsTab = SignalsTab.ACTIVE,
    val assetCategory: SignalAssetCategory = SignalAssetCategory.ALL,
    val strategy: SignalStrategy? = null,
    val direction: SignalDirection? = null,
    val segment: MarketSegment? = null,
    val confidence: SignalConfidence? = null
)

data class SignalsUiState(
    val filters: SignalsFilterState = SignalsFilterState(),
    val items: List<SignalUiItem> = emptyList(),
    val historyRecords: List<SignalRecord> = emptyList(),
    val instrumentPerformance: List<InstrumentSignalPerformance> = emptyList(),
    val accuracyStats: SignalAccuracyStats = SignalAccuracyStats(),
    val activeCount: Int = 0,
    val savedCount: Int = 0,
    val historyCount: Int = 0,
    val signalNotificationsEnabled: Boolean = true,
    val selectedItem: SignalUiItem? = null,
    val selectedAiState: AiUiState<AiSignalSecondOpinion> = AiUiState.Idle,
    val aiConnectionStatus: AiConnectionStatus = AiConnectionStatus(),
    val isRefreshing: Boolean = false
)

@HiltViewModel
class SignalsViewModel @Inject constructor(
    private val signalRepository: SignalRepository,
    private val aiMarketRepository: AiMarketRepository,
    private val prefsRepository: TradingPreferencesRepository
) : ViewModel() {

    private val _filters = MutableStateFlow(SignalsFilterState())
    private val _selectedId = MutableStateFlow<String?>(null)
    private val _isRefreshing = MutableStateFlow(false)
    private val _aiStates = MutableStateFlow<Map<String, AiUiState<AiSignalSecondOpinion>>>(emptyMap())

    private val selectionAndRefresh = combine(
        _selectedId,
        _isRefreshing,
        _aiStates,
        prefsRepository.configFlow,
        aiMarketRepository.connectionStatus
    ) { selectedId, refreshing, aiStates, config, aiStatus ->
        MetaBits(selectedId, refreshing, aiStates, config.signalNotificationsEnabled, aiStatus)
    }

    private val historyFlow = combine(
        signalRepository.signalRecords,
        signalRepository.signalAccuracy
    ) { records, stats -> records to stats }

    val uiState: StateFlow<SignalsUiState> = combine(
        signalRepository.activeSignals,
        signalRepository.savedSignals,
        historyFlow,
        _filters,
        selectionAndRefresh
    ) { active, saved, historyPair, filters, meta ->
        val (records, stats) = historyPair
        val source = when (filters.tab) {
            SignalsTab.ACTIVE -> active
            SignalsTab.SAVED -> saved
            SignalsTab.HISTORY -> emptyList()
        }
        val filteredSignals = source.filter { item ->
            val sig = item.signal
            matchesAssetCategory(sig.symbol, sig.segment, sig.instrumentType, sig.strategy, filters.assetCategory) &&
                (filters.strategy == null || sig.strategy == filters.strategy) &&
                (filters.direction == null || sig.direction == filters.direction) &&
                (filters.segment == null || sig.segment == filters.segment) &&
                (filters.confidence == null || sig.confidence == filters.confidence)
        }
        val filteredRecords = records.filter { rec ->
            matchesAssetCategory(rec.symbol, rec.segment, rec.instrumentType, rec.strategy, filters.assetCategory) &&
                (filters.strategy == null || rec.strategy == filters.strategy) &&
                (filters.direction == null || rec.direction == filters.direction) &&
                (filters.segment == null || rec.segment == filters.segment) &&
                (filters.confidence == null || rec.confidence == filters.confidence)
        }
        val filteredAccuracy = if (filteredRecords.size == records.size) {
            stats
        } else {
            SignalAccuracyStats(
                totalResolved = filteredRecords.size,
                t1Hits = filteredRecords.count { it.outcome == SignalOutcome.HIT_T1 },
                t2Hits = filteredRecords.count { it.outcome == SignalOutcome.HIT_T2 },
                slHits = filteredRecords.count { it.outcome == SignalOutcome.HIT_SL },
                expiredCount = filteredRecords.count { it.outcome == SignalOutcome.EXPIRED }
            )
        }
        val instPerf = filteredRecords
            .groupBy { it.symbol }
            .map { (sym, list) ->
                InstrumentSignalPerformance(
                    symbol = sym,
                    segment = list.first().segment,
                    totalSignals = list.size,
                    t1Hits = list.count { it.outcome == SignalOutcome.HIT_T1 },
                    t2Hits = list.count { it.outcome == SignalOutcome.HIT_T2 },
                    slHits = list.count { it.outcome == SignalOutcome.HIT_SL },
                    expiredCount = list.count { it.outcome == SignalOutcome.EXPIRED },
                    avgPnlPercent = if (list.isEmpty()) 0.0 else list.map { it.pnlPercent }.average()
                )
            }
            .sortedWith(
                compareByDescending<InstrumentSignalPerformance> { it.decidedCount > 0 }
                    .thenByDescending { it.accuracyPercent }
                    .thenByDescending { it.totalSignals }
            )

        val selected = (active + saved).firstOrNull { it.signal.id == meta.selectedId }
        val selectedAi = selected?.let { meta.aiStates[it.signal.id] } ?: AiUiState.Idle
        SignalsUiState(
            filters = filters,
            items = filteredSignals,
            historyRecords = filteredRecords,
            instrumentPerformance = instPerf,
            accuracyStats = filteredAccuracy,
            activeCount = active.size,
            savedCount = saved.size,
            historyCount = records.size,
            signalNotificationsEnabled = meta.signalNotificationsEnabled,
            selectedItem = selected,
            selectedAiState = selectedAi,
            aiConnectionStatus = meta.aiStatus,
            isRefreshing = meta.refreshing
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SignalsUiState()
    )

    fun setTab(tab: SignalsTab) = _filters.update { it.copy(tab = tab) }
    fun setAssetCategory(category: SignalAssetCategory) = _filters.update { it.copy(assetCategory = category) }
    fun setStrategyFilter(strategy: SignalStrategy?) = _filters.update {
        it.copy(strategy = if (it.strategy == strategy) null else strategy)
    }
    fun setDirectionFilter(direction: SignalDirection?) = _filters.update {
        it.copy(direction = if (it.direction == direction) null else direction)
    }
    fun setSegmentFilter(segment: MarketSegment?) = _filters.update {
        it.copy(segment = if (it.segment == segment) null else segment)
    }
    fun setConfidenceFilter(confidence: SignalConfidence?) = _filters.update {
        it.copy(confidence = if (it.confidence == confidence) null else confidence)
    }

    fun toggleSignalNotifications(enabled: Boolean) {
        viewModelScope.launch {
            prefsRepository.updateSignalNotificationsEnabled(enabled)
        }
    }

    fun clearSignalHistory() {
        viewModelScope.launch {
            signalRepository.clearSignalHistory()
        }
    }

    fun selectSignal(item: SignalUiItem?) {
        _selectedId.value = item?.signal?.id
    }

    fun switchAiModel(modelName: String) {
        aiMarketRepository.switchActiveModel(modelName)
    }

    fun analyzeSignalWithAi(signal: TradeSignal) {
        viewModelScope.launch {
            _aiStates.update { it + (signal.id to AiUiState.Loading) }
            val instrument = signalRepository.getInstrumentSnapshot(signal.symbol)
            val candles = signalRepository.getRecentCandles(signal.symbol, signal.timeframe)
            val result = aiMarketRepository.analyzeTradeSignal(signal, instrument, candles)
            val nextState = result.fold(
                onSuccess = { AiUiState.Success(it) },
                onFailure = { AiUiState.Error(it.message ?: "AI analysis failed.") }
            )
            _aiStates.update { it + (signal.id to nextState) }
        }
    }

    fun acceptSignal(signalId: String) {
        viewModelScope.launch {
            signalRepository.acceptSignal(signalId)
        }
    }

    fun dismissSignal(signal: TradeSignal) {
        viewModelScope.launch {
            if (_selectedId.value == signal.id) {
                _selectedId.value = null
            }
            signalRepository.dismissSignal(signal)
        }
    }

    fun toggleSave(item: SignalUiItem) {
        viewModelScope.launch {
            signalRepository.toggleSaveSignal(item.signal, item.isSaved)
        }
    }

    fun refreshSignals() {
        viewModelScope.launch {
            _isRefreshing.value = true
            signalRepository.refreshAllSignals()
            _isRefreshing.value = false
        }
    }

    fun buildPrefilledOrderRoute(signal: TradeSignal, useTarget2: Boolean = false): InstrumentDetailRoute {
        val side = if (signal.direction == SignalDirection.LONG) OrderSide.BUY else OrderSide.SELL
        val product = if (signal.strategy.prefersCarryForward) ProductType.CARRY_FORWARD else ProductType.INTRADAY
        val validity = if (product == ProductType.CARRY_FORWARD) ValidityType.GTT else ValidityType.DAY
        val chosenTarget = if (useTarget2) signal.target2 else signal.target1
        return InstrumentDetailRoute(
            symbol = signal.symbol,
            prefillSide = side.name,
            prefillEntry = signal.entryPrice.toPlainString(),
            prefillSl = signal.stopLoss.toPlainString(),
            prefillTarget = chosenTarget.toPlainString(),
            prefillProduct = product.name,
            prefillValidity = validity.name
        )
    }

    private fun matchesAssetCategory(
        symbol: String,
        segment: MarketSegment,
        instrumentType: InstrumentType,
        strategy: SignalStrategy,
        category: SignalAssetCategory
    ): Boolean {
        val upper = symbol.uppercase()
        val isIndexName = upper.contains("NIFTY") || upper.contains("SENSEX") || upper.contains("FINNIFTY") || upper.contains("MIDCP")
        return when (category) {
            SignalAssetCategory.ALL -> true
            SignalAssetCategory.STOCK ->
                (segment == MarketSegment.NSE || segment == MarketSegment.BSE) &&
                    instrumentType == InstrumentType.STOCK && !isIndexName
            SignalAssetCategory.INDEX ->
                (segment == MarketSegment.NSE || segment == MarketSegment.BSE) && isIndexName
            SignalAssetCategory.FNO ->
                (segment == MarketSegment.NSE || segment == MarketSegment.BSE) &&
                    (instrumentType == InstrumentType.FUTURES || instrumentType == InstrumentType.OPTIONS)
            SignalAssetCategory.MCX ->
                segment == MarketSegment.MCX
            SignalAssetCategory.CRYPTO ->
                segment == MarketSegment.CRYPTO && instrumentType == InstrumentType.STOCK
            SignalAssetCategory.CRYPTO_FNO ->
                segment == MarketSegment.CRYPTO &&
                    (instrumentType == InstrumentType.FUTURES || instrumentType == InstrumentType.OPTIONS || upper.contains("PERP"))
            SignalAssetCategory.BTST_STBT ->
                strategy == SignalStrategy.BTST_STBT
        }
    }

    private data class MetaBits(
        val selectedId: String?,
        val refreshing: Boolean,
        val aiStates: Map<String, AiUiState<AiSignalSecondOpinion>>,
        val signalNotificationsEnabled: Boolean,
        val aiStatus: AiConnectionStatus
    )
}
