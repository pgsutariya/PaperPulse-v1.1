package com.example.ui.screens.signals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.ai.AiMarketRepository
import com.example.data.signal.SignalRepository
import com.example.data.signal.SignalUiItem
import com.example.domain.model.MarketSegment
import com.example.domain.model.OrderSide
import com.example.domain.model.ProductType
import com.example.domain.model.ValidityType
import com.example.domain.signal.AiSignalSecondOpinion
import com.example.domain.signal.AiUiState
import com.example.domain.signal.SignalConfidence
import com.example.domain.signal.SignalDirection
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
    ACTIVE("Active Signals"),
    SAVED("Saved")
}

data class SignalsFilterState(
    val tab: SignalsTab = SignalsTab.ACTIVE,
    val strategy: SignalStrategy? = null,
    val direction: SignalDirection? = null,
    val segment: MarketSegment? = null,
    val confidence: SignalConfidence? = null
)

data class SignalsUiState(
    val filters: SignalsFilterState = SignalsFilterState(),
    val items: List<SignalUiItem> = emptyList(),
    val activeCount: Int = 0,
    val savedCount: Int = 0,
    val selectedItem: SignalUiItem? = null,
    val selectedAiState: AiUiState<AiSignalSecondOpinion> = AiUiState.Idle,
    val isRefreshing: Boolean = false
)

@HiltViewModel
class SignalsViewModel @Inject constructor(
    private val signalRepository: SignalRepository,
    private val aiMarketRepository: AiMarketRepository
) : ViewModel() {

    private val _filters = MutableStateFlow(SignalsFilterState())
    private val _selectedId = MutableStateFlow<String?>(null)
    private val _isRefreshing = MutableStateFlow(false)
    private val _aiStates = MutableStateFlow<Map<String, AiUiState<AiSignalSecondOpinion>>>(emptyMap())

    private val selectionAndRefresh = combine(
        _selectedId,
        _isRefreshing,
        _aiStates
    ) { selectedId, refreshing, aiStates ->
        Triple(selectedId, refreshing, aiStates)
    }

    val uiState: StateFlow<SignalsUiState> = combine(
        signalRepository.activeSignals,
        signalRepository.savedSignals,
        _filters,
        selectionAndRefresh
    ) { active, saved, filters, selMeta ->
        val (selectedId, refreshing, aiStates) = selMeta
        val source = when (filters.tab) {
            SignalsTab.ACTIVE -> active
            SignalsTab.SAVED -> saved
        }
        val filtered = source.filter { item ->
            val sig = item.signal
            (filters.strategy == null || sig.strategy == filters.strategy) &&
                (filters.direction == null || sig.direction == filters.direction) &&
                (filters.segment == null || sig.segment == filters.segment) &&
                (filters.confidence == null || sig.confidence == filters.confidence)
        }
        val selected = (active + saved).firstOrNull { it.signal.id == selectedId }
        val selectedAi = selected?.let { aiStates[it.signal.id] } ?: AiUiState.Idle
        SignalsUiState(
            filters = filters,
            items = filtered,
            activeCount = active.size,
            savedCount = saved.size,
            selectedItem = selected,
            selectedAiState = selectedAi,
            isRefreshing = refreshing
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SignalsUiState()
    )

    fun setTab(tab: SignalsTab) = _filters.update { it.copy(tab = tab) }
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

    fun selectSignal(item: SignalUiItem?) {
        _selectedId.value = item?.signal?.id
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
}
