package com.example.ui.screens.signals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.ai.AiConnectionStatus
import com.example.data.ai.AiMarketRepository
import com.example.data.repository.TradingPreferencesRepository
import com.example.data.signal.SignalRepository
import com.example.data.signal.SignalUiItem
import com.example.domain.analysis.AstroCalendarEvent
import com.example.domain.analysis.AstroCycleSnapshot
import com.example.domain.analysis.AstroEventCalendarEngine
import com.example.domain.analysis.AstroEventCategory
import com.example.domain.analysis.AstroTradingEngine
import com.example.domain.analysis.AstroTrendFilterConfig
import com.example.domain.analysis.DualStrategyBacktestSnapshot
import com.example.domain.analysis.LunarCycleFilter
import com.example.domain.analysis.PlanetaryAspectFilter
import com.example.domain.analysis.StrategyBacktesterEngine
import com.example.domain.analysis.StrategyCalibrationConfig
import com.example.domain.analysis.VolatilityDashboardEngine
import com.example.domain.analysis.VolatilityDashboardSnapshot
import java.time.LocalDate
import java.time.YearMonth
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
import com.example.domain.signal.Timeframe
import com.example.domain.signal.TradeSignal
import com.example.ui.navigation.InstrumentDetailRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

enum class SignalsTab(val label: String) {
    ACTIVE("Active"),
    SAVED("Saved"),
    HISTORY("Performance"),
    VOLATILITY("Volatility"),
    ASTRO_EVENTS("Astro Alerts")
}

enum class HistorySubTab(val label: String) {
    BACKTESTER("Backtester"),
    BY_INSTRUMENT("By Instrument"),
    SIGNAL_LOG("Signal Log")
}

enum class MinWinProbFilter(val label: String, val minPercent: Int) {
    PROB_60("60%+ Acc", 60),
    PROB_55("55%+ Acc", 55),
    PROB_50("50%+ Acc", 50),
    ALL("All", 0)
}

data class SignalsFilterState(
    val tab: SignalsTab = SignalsTab.ACTIVE,
    val assetCategory: SignalAssetCategory = SignalAssetCategory.ALL,
    val minWinProb: MinWinProbFilter = MinWinProbFilter.PROB_60,
    val strategy: SignalStrategy? = null,
    val direction: SignalDirection? = null,
    val segment: MarketSegment? = null,
    val confidence: SignalConfidence? = null
)

data class SignalsUiState(
    val filters: SignalsFilterState = SignalsFilterState(),
    val historySubTab: HistorySubTab = HistorySubTab.BACKTESTER,
    val astroFilterConfig: AstroTrendFilterConfig = AstroTrendFilterConfig(),
    val items: List<SignalUiItem> = emptyList(),
    val historyRecords: List<SignalRecord> = emptyList(),
    val instrumentPerformance: List<InstrumentSignalPerformance> = emptyList(),
    val accuracyStats: SignalAccuracyStats = SignalAccuracyStats(),
    val activeCount: Int = 0,
    val highAccuracyCount: Int = 0,
    val stockOptionsCount: Int = 0,
    val astroMatchedCount: Int = 0,
    val savedCount: Int = 0,
    val historyCount: Int = 0,
    val astroSnapshot: AstroCycleSnapshot = AstroTradingEngine.compute(),
    val astroAlerts: AstroAlertsUiState = AstroAlertsUiState(),
    val volatilityDashboard: VolatilityDashboardUiState = VolatilityDashboardUiState(),
    val strategyBacktester: StrategyBacktesterUiState = StrategyBacktesterUiState(),
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
    private val _historySubTab = MutableStateFlow(HistorySubTab.BACKTESTER)
    private val _selectedId = MutableStateFlow<String?>(null)
    private val _isRefreshing = MutableStateFlow(false)
    private val _aiStates = MutableStateFlow<Map<String, AiUiState<AiSignalSecondOpinion>>>(emptyMap())
    private val _astroCalendarPrefs = MutableStateFlow(AstroCalendarPrefs())
    private val _analyticsPrefs = MutableStateFlow(AnalyticsPrefs())
    private val _analyticsSnapshots = MutableStateFlow(AnalyticsSnapshots())

    init {
        // Real-time Volatility & Strategy Backtester updater driven by live candles and active calibration
        viewModelScope.launch {
            computeAnalytics(forceHistoryFetch = true)
            while (true) {
                delay(4_000L)
                computeAnalytics(forceHistoryFetch = false)
            }
        }
    }

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
        signalRepository.signalAccuracy,
        _historySubTab
    ) { records, stats, subTab -> Triple(records, stats, subTab) }

    private val filterAndAstroFlow = combine(
        _filters,
        signalRepository.astroFilterConfig,
        _astroCalendarPrefs
    ) { filters, astroCfg, calPrefs -> Triple(filters, astroCfg, calPrefs) }

    private val analyticsFlow = combine(
        _analyticsPrefs,
        _analyticsSnapshots,
        signalRepository.calibrationConfig,
        signalRepository.instrumentsFlow
    ) { prefs, snaps, calCfg, instruments ->
        val symbols = instruments.map { it.symbol }.ifEmpty {
            listOf("RELIANCE", "TCS", "HDFCBANK", "NIFTY", "BANKNIFTY", "BTCUSDT")
        }
        val activeSym = if (prefs.symbol in symbols) prefs.symbol else symbols.first()
        AnalyticsCombined(
            selectedSymbol = activeSym,
            selectedTimeframe = prefs.timeframe,
            availableSymbols = symbols,
            volatilitySnapshot = snaps.volatility,
            backtestSnapshot = snaps.backtest,
            calibrationConfig = calCfg,
            isLoading = snaps.isLoading
        )
    }

    val uiState: StateFlow<SignalsUiState> = combine(
        signalRepository.activeSignals,
        signalRepository.savedSignals,
        historyFlow,
        filterAndAstroFlow,
        selectionAndRefresh,
        analyticsFlow
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val active = values[0] as List<SignalUiItem>
        @Suppress("UNCHECKED_CAST")
        val saved = values[1] as List<SignalUiItem>
        @Suppress("UNCHECKED_CAST")
        val historyTriple = values[2] as Triple<List<SignalRecord>, SignalAccuracyStats, HistorySubTab>
        @Suppress("UNCHECKED_CAST")
        val filterTriple = values[3] as Triple<SignalsFilterState, AstroTrendFilterConfig, AstroCalendarPrefs>
        val meta = values[4] as MetaBits
        val analytics = values[5] as AnalyticsCombined

        val (records, stats, historySubTab) = historyTriple
        val (filters, astroCfg, calPrefs) = filterTriple
        val astroSnap = AstroTradingEngine.compute()
        val source = when (filters.tab) {
            SignalsTab.ACTIVE -> active
            SignalsTab.SAVED -> saved
            SignalsTab.HISTORY, SignalsTab.VOLATILITY, SignalsTab.ASTRO_EVENTS -> emptyList()
        }
        val filteredSignals = source.filter { item ->
            val sig = item.signal
            val astroOk = if (astroCfg.enabled) {
                AstroTradingEngine.evaluateFilterMatch(astroSnap, sig.direction, astroCfg).passes
            } else {
                true
            }
            astroOk &&
                item.winProbabilityPercent >= filters.minWinProb.minPercent &&
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
        val highAccCount = active.count { it.winProbabilityPercent >= 60 }
        val stockOptCount = active.count {
            matchesAssetCategory(it.signal.symbol, it.signal.segment, it.signal.instrumentType, it.signal.strategy, SignalAssetCategory.STOCK_OPTIONS)
        }
        val astroMatchCount = active.count {
            AstroTradingEngine.evaluateFilterMatch(astroSnap, it.signal.direction, astroCfg.copy(enabled = true)).passes
        }

        // Planetary & Lunar Event Calendar + Historical Volatility & Signal Performance Mapping
        val today = LocalDate.now()
        val perfMap = AstroEventCalendarEngine.buildPerformanceStats(records)
        val monthStart = calPrefs.displayedMonth.atDay(1).minusDays(7)
        val monthEnd = calPrefs.displayedMonth.atEndOfMonth().plusDays(7)
        val scanStart = if (monthStart.isBefore(today)) monthStart else today
        val horizonEnd = today.plusDays(95)
        val scanEnd = if (monthEnd.isAfter(horizonEnd)) monthEnd else horizonEnd
        val allAstroEvents = AstroEventCalendarEngine.scanEvents(scanStart, scanEnd, perfMap)
        val calendarCells = AstroEventCalendarEngine.buildMonthGrid(calPrefs.displayedMonth, today, allAstroEvents)
        val selectedDateSnap = AstroEventCalendarEngine.snapshotForDate(calPrefs.selectedDate)
        val selectedDateEvs = allAstroEvents.filter { it.date == calPrefs.selectedDate }
        val upcomingAll = allAstroEvents.filter { !it.date.isBefore(today) && !it.date.isAfter(today.plusDays(90)) }
        val upcomingFiltered = calPrefs.selectedCategory?.let { cat ->
            upcomingAll.filter { it.category == cat }
        } ?: upcomingAll
        val astroAlertsState = AstroAlertsUiState(
            today = today,
            displayedMonth = calPrefs.displayedMonth,
            selectedDate = calPrefs.selectedDate,
            selectedCategory = calPrefs.selectedCategory,
            calendarCells = calendarCells,
            selectedDateSnapshot = selectedDateSnap,
            selectedDateEvents = selectedDateEvs,
            upcomingEvents = upcomingFiltered,
            nextImminentEvent = upcomingAll.firstOrNull(),
            performanceStats = AstroEventCategory.entries.map { perfMap.getValue(it) },
            mutedEventIds = calPrefs.mutedEventIds,
            upcomingCount = upcomingAll.size
        )

        val volDashboardState = VolatilityDashboardUiState(
            selectedSymbol = analytics.selectedSymbol,
            selectedTimeframe = analytics.selectedTimeframe,
            availableSymbols = analytics.availableSymbols,
            snapshot = analytics.volatilitySnapshot,
            activeCalibration = analytics.calibrationConfig,
            isLoading = analytics.isLoading
        )
        val backtesterState = StrategyBacktesterUiState(
            selectedSymbol = analytics.selectedSymbol,
            selectedTimeframe = analytics.selectedTimeframe,
            availableSymbols = analytics.availableSymbols,
            snapshot = analytics.backtestSnapshot,
            isRunning = analytics.isLoading
        )

        SignalsUiState(
            filters = filters,
            historySubTab = historySubTab,
            astroFilterConfig = astroCfg,
            items = filteredSignals,
            historyRecords = filteredRecords,
            instrumentPerformance = instPerf,
            accuracyStats = filteredAccuracy,
            activeCount = active.size,
            highAccuracyCount = highAccCount,
            stockOptionsCount = stockOptCount,
            astroMatchedCount = astroMatchCount,
            savedCount = saved.size,
            historyCount = records.size,
            astroSnapshot = astroSnap,
            astroAlerts = astroAlertsState,
            volatilityDashboard = volDashboardState,
            strategyBacktester = backtesterState,
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

    fun setTab(tab: SignalsTab) {
        _filters.update { it.copy(tab = tab) }
        if (tab == SignalsTab.VOLATILITY || tab == SignalsTab.HISTORY) {
            viewModelScope.launch { computeAnalytics(forceHistoryFetch = false) }
        }
    }

    fun setHistorySubTab(subTab: HistorySubTab) {
        _historySubTab.value = subTab
        if (subTab == HistorySubTab.BACKTESTER) {
            viewModelScope.launch { computeAnalytics(forceHistoryFetch = false) }
        }
    }

    fun selectAnalyticsSymbol(symbol: String) {
        _analyticsPrefs.update { it.copy(symbol = symbol) }
        viewModelScope.launch { computeAnalytics(forceHistoryFetch = true) }
    }

    fun selectAnalyticsTimeframe(timeframe: Timeframe) {
        _analyticsPrefs.update { it.copy(timeframe = timeframe) }
        viewModelScope.launch { computeAnalytics(forceHistoryFetch = true) }
    }

    fun refreshVolatilityAndBacktest(forceHistoryFetch: Boolean = true) {
        viewModelScope.launch { computeAnalytics(forceHistoryFetch = forceHistoryFetch) }
    }

    fun updateCalibrationConfig(config: StrategyCalibrationConfig) {
        signalRepository.updateCalibrationConfig(config)
        viewModelScope.launch { computeAnalytics(forceHistoryFetch = false) }
    }

    fun applyAutoCalibrationFromVolatility() {
        val recommended = _analyticsSnapshots.value.volatility.recommendedCalibration
        signalRepository.updateCalibrationConfig(recommended)
        viewModelScope.launch { computeAnalytics(forceHistoryFetch = false) }
    }

    fun applyCalibrationAndRescanSignals() {
        refreshSignals()
        _filters.update { it.copy(tab = SignalsTab.ACTIVE) }
    }

    fun openBacktesterFromVolatility() {
        _historySubTab.value = HistorySubTab.BACKTESTER
        _filters.update { it.copy(tab = SignalsTab.HISTORY) }
        viewModelScope.launch { computeAnalytics(forceHistoryFetch = false) }
    }

    fun openVolatilityFromBacktester() {
        _filters.update { it.copy(tab = SignalsTab.VOLATILITY) }
        viewModelScope.launch { computeAnalytics(forceHistoryFetch = false) }
    }

    private suspend fun computeAnalytics(forceHistoryFetch: Boolean) {
        val prefs = _analyticsPrefs.value
        val symbol = prefs.symbol
        val tf = prefs.timeframe
        val calibration = signalRepository.calibrationConfig.value
        if (forceHistoryFetch) {
            _analyticsSnapshots.update { it.copy(isLoading = true) }
            runCatching { signalRepository.loadHistory(symbol, tf, force = false) }
        }
        val inst = signalRepository.getInstrumentSnapshot(symbol)
        val candles = signalRepository.getRecentCandles(symbol, tf)
        val instType = inst?.type ?: InstrumentType.STOCK

        val (volSnap, btSnap) = withContext(Dispatchers.Default) {
            val vol = VolatilityDashboardEngine.compute(symbol, tf, candles)
            val bt = StrategyBacktesterEngine.run(
                symbol = symbol,
                timeframe = tf,
                candles = candles,
                instrumentType = instType,
                calibration = calibration
            )
            vol to bt
        }
        _analyticsSnapshots.value = AnalyticsSnapshots(
            volatility = volSnap,
            backtest = btSnap,
            isLoading = false
        )
    }
    fun setAssetCategory(category: SignalAssetCategory) = _filters.update { it.copy(assetCategory = category) }
    fun setMinWinProbFilter(filter: MinWinProbFilter) = _filters.update { it.copy(minWinProb = filter) }
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

    fun toggleAstroFilterEnabled(enabled: Boolean) {
        val next = signalRepository.astroFilterConfig.value.copy(enabled = enabled)
        signalRepository.updateAstroFilterConfig(next)
    }

    fun setAstroLunarFilter(lunar: LunarCycleFilter) {
        val cur = signalRepository.astroFilterConfig.value
        signalRepository.updateAstroFilterConfig(cur.copy(enabled = true, lunarCycleFilter = lunar))
    }

    fun setAstroPlanetaryFilter(planetary: PlanetaryAspectFilter) {
        val cur = signalRepository.astroFilterConfig.value
        signalRepository.updateAstroFilterConfig(cur.copy(enabled = true, planetaryFilter = planetary))
    }

    fun toggleAstroAlignDirection() {
        val cur = signalRepository.astroFilterConfig.value
        signalRepository.updateAstroFilterConfig(cur.copy(enabled = true, alignSignalDirectionWithAstro = !cur.alignSignalDirectionWithAstro))
    }

    fun toggleAstroAvoidMercuryRx() {
        val cur = signalRepository.astroFilterConfig.value
        signalRepository.updateAstroFilterConfig(cur.copy(enabled = true, avoidMercuryRetrograde = !cur.avoidMercuryRetrograde))
    }

    fun setAstroMinTurningScore(score: Int) {
        val cur = signalRepository.astroFilterConfig.value
        signalRepository.updateAstroFilterConfig(cur.copy(enabled = true, minTurningPointScore = score))
    }

    fun applyAstroFiltersAndRescan() {
        val cur = signalRepository.astroFilterConfig.value
        if (!cur.enabled) {
            signalRepository.updateAstroFilterConfig(cur.copy(enabled = true))
        }
        refreshSignals()
    }

    fun previousAstroMonth() {
        _astroCalendarPrefs.update { cur ->
            val prev = cur.displayedMonth.minusMonths(1)
            cur.copy(displayedMonth = prev, selectedDate = prev.atDay(1))
        }
    }

    fun nextAstroMonth() {
        _astroCalendarPrefs.update { cur ->
            val next = cur.displayedMonth.plusMonths(1)
            cur.copy(displayedMonth = next, selectedDate = next.atDay(1))
        }
    }

    fun jumpAstroToday() {
        val today = LocalDate.now()
        _astroCalendarPrefs.update { cur ->
            cur.copy(displayedMonth = YearMonth.from(today), selectedDate = today)
        }
    }

    fun selectAstroDate(date: LocalDate) {
        _astroCalendarPrefs.update { cur ->
            cur.copy(displayedMonth = YearMonth.from(date), selectedDate = date)
        }
    }

    fun selectAstroEventCategory(category: AstroEventCategory?) {
        _astroCalendarPrefs.update { cur ->
            cur.copy(selectedCategory = if (cur.selectedCategory == category) null else category)
        }
    }

    fun toggleAstroEventAlert(eventId: String) {
        _astroCalendarPrefs.update { cur ->
            val nextMuted = if (eventId in cur.mutedEventIds) {
                cur.mutedEventIds - eventId
            } else {
                cur.mutedEventIds + eventId
            }
            cur.copy(mutedEventIds = nextMuted)
        }
    }

    fun applyAstroEventFilterToSignals(event: AstroCalendarEvent) {
        val cur = signalRepository.astroFilterConfig.value
        signalRepository.updateAstroFilterConfig(
            cur.copy(
                enabled = true,
                lunarCycleFilter = event.recommendedLunarFilter,
                planetaryFilter = event.recommendedPlanetaryFilter,
                avoidMercuryRetrograde = event.avoidMercuryRxRecommended
            )
        )
        _filters.update { it.copy(tab = SignalsTab.ACTIVE) }
        refreshSignals()
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
            SignalAssetCategory.STOCK_OPTIONS ->
                (segment == MarketSegment.NSE || segment == MarketSegment.BSE) &&
                    instrumentType == InstrumentType.OPTIONS && !isIndexName
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

    private data class AnalyticsPrefs(
        val symbol: String = "RELIANCE",
        val timeframe: Timeframe = Timeframe.M5
    )

    private data class AnalyticsSnapshots(
        val volatility: VolatilityDashboardSnapshot = VolatilityDashboardSnapshot(),
        val backtest: DualStrategyBacktestSnapshot = DualStrategyBacktestSnapshot(),
        val isLoading: Boolean = false
    )

    private data class AnalyticsCombined(
        val selectedSymbol: String,
        val selectedTimeframe: Timeframe,
        val availableSymbols: List<String>,
        val volatilitySnapshot: VolatilityDashboardSnapshot,
        val backtestSnapshot: DualStrategyBacktestSnapshot,
        val calibrationConfig: StrategyCalibrationConfig,
        val isLoading: Boolean
    )

    private data class AstroCalendarPrefs(
        val displayedMonth: YearMonth = YearMonth.now(),
        val selectedDate: LocalDate = LocalDate.now(),
        val selectedCategory: AstroEventCategory? = null,
        val mutedEventIds: Set<String> = emptySet()
    )

    private data class MetaBits(
        val selectedId: String?,
        val refreshing: Boolean,
        val aiStates: Map<String, AiUiState<AiSignalSecondOpinion>>,
        val signalNotificationsEnabled: Boolean,
        val aiStatus: AiConnectionStatus
    )
}
