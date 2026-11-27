package com.example.ui.screens.chart

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.example.data.ai.AiConnectionStatus
import com.example.data.ai.AiMarketRepository
import com.example.data.repository.MarketDataRepository
import com.example.data.repository.TradingPreferencesRepository
import com.example.data.repository.TradingRepository
import com.example.data.signal.SignalRepository
import com.example.domain.analysis.AdvancedIndicators
import com.example.domain.analysis.AiRenkoConfig
import com.example.domain.analysis.AmdState
import com.example.domain.analysis.ChartAnalysis
import com.example.domain.analysis.DetectedChartPattern
import com.example.domain.analysis.EmaCrossSetup
import com.example.domain.analysis.MarketAnalyzer
import com.example.domain.analysis.MdrState
import com.example.domain.analysis.SmcAnalyzer
import com.example.domain.model.Instrument
import com.example.domain.model.MarketSegment
import com.example.domain.model.OrderSide
import com.example.domain.model.ProductType
import com.example.domain.model.ValidityType
import com.example.domain.signal.AiSignalSecondOpinion
import com.example.domain.signal.AiUiState
import com.example.domain.signal.SignalDirection
import com.example.domain.signal.TechnicalIndicators
import com.example.domain.signal.Timeframe
import com.example.ui.navigation.ChartRoute
import com.example.ui.navigation.InstrumentDetailRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import javax.inject.Inject

data class ChartOverlays(
    val ema921: Boolean = true,
    val ema50200: Boolean = true,
    val vwap: Boolean = true,
    val vwapBands: Boolean = true,
    val htfShadow: Boolean = true,
    val pivots: Boolean = false,
    val smcZones: Boolean = true,
    val liquidity: Boolean = true,
    val structure: Boolean = true,
    val premiumDiscount: Boolean = false,
    val volumeProfile: Boolean = false,
    val divergences: Boolean = true,
    val setupLevels: Boolean = true,
    val renkoSupertrend: Boolean = true,
    val redBar: Boolean = true,
    val firstRedRange: Boolean = true,
    val chartPatterns: Boolean = true
)

enum class OverlayKey(val label: String) {
    CHART_PATTERNS("AI Patterns"),
    RENKO_SUPERTREND("AI Renko ST"),
    RED_BAR("Red Bar"),
    FIRST_RED_RANGE("1st Red Range"),
    EMA_9_21("EMA 9/21"),
    EMA_50_200("EMA 50/200"),
    VWAP("VWAP"),
    VWAP_BANDS("VWAP Bands"),
    HTF("HTF Shadow"),
    PIVOTS("Pivots/CPR"),
    SMC("OB/FVG/S&D"),
    LIQUIDITY("Liquidity"),
    STRUCTURE("BOS/CHoCH"),
    PD("Premium/Discount"),
    VP("Volume Profile"),
    DIVERGENCE("Divergences"),
    SETUP("Setup Levels")
}

enum class LowerPane(val label: String) {
    VOLUME("Volume + MA20"),
    RSI("RSI 14"),
    OBV("OBV"),
    ELDER("Elder Force 13"),
    DELTA("Delta / CVD"),
    ATR("ATR 7 / 14")
}

data class FrameAmd(val timeframe: Timeframe, val amd: AmdState)
data class FrameMdr(val timeframe: Timeframe, val mdr: MdrState)

data class ChartUiState(
    val symbol: String = "",
    val instrument: Instrument? = null,
    val timeframe: Timeframe = Timeframe.M5,
    val higherTimeframe: Timeframe? = null,
    val analysis: ChartAnalysis? = null,
    val amdFrames: List<FrameAmd> = emptyList(),
    val mdrFrames: List<FrameMdr> = emptyList(),
    val overlays: ChartOverlays = ChartOverlays(),
    val lowerPane: LowerPane = LowerPane.VOLUME,
    val visibleBars: Int = 120,
    val panBars: Int = 0,
    val isLoadingHistory: Boolean = false,
    val historyNote: String = "",
    val accountCapital: BigDecimal = BigDecimal.ZERO,
    val riskPercent: Double = 1.0,
    val isExpanded: Boolean = false,
    val measureMode: Boolean = false,
    val measureStartIndex: Int? = null,
    val measureEndIndex: Int? = null,
    val chartEngine: ChartEngineMode = ChartEngineMode.CANVAS,
    val tvChartStyle: TvChartStyle = TvChartStyle.CANDLES,
    val tvLogScale: Boolean = false,
    val tvMagnetCrosshair: Boolean = true,
    val tvZoomCommandSeq: Int = 0,
    val tvZoomDirection: Int = 0,
    val renkoConfig: AiRenkoConfig = AiRenkoConfig(),
    val selectedPattern: DetectedChartPattern? = null,
    val selectedPatternAiState: AiUiState<AiSignalSecondOpinion> = AiUiState.Idle,
    val aiChartState: AiUiState<AiSignalSecondOpinion> = AiUiState.Idle,
    val aiConnectionStatus: AiConnectionStatus = AiConnectionStatus()
)

private data class ComputedFrame(
    val analysis: ChartAnalysis?,
    val amd: List<FrameAmd>,
    val mdr: List<FrameMdr>
)

@HiltViewModel
class ChartViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    marketDataRepository: MarketDataRepository,
    tradingRepository: TradingRepository,
    private val tradingPreferencesRepository: TradingPreferencesRepository,
    private val signalRepository: SignalRepository,
    private val aiMarketRepository: AiMarketRepository
) : ViewModel() {

    private val route = savedStateHandle.toRoute<ChartRoute>()
    val symbol: String = route.symbol

    private val _timeframe = MutableStateFlow(
        route.timeframe?.let { name -> Timeframe.entries.firstOrNull { it.name == name } } ?: Timeframe.M5
    )
    private val _htf = MutableStateFlow(_timeframe.value.higherTimeframes.firstOrNull())
    private val _overlays = MutableStateFlow(ChartOverlays())
    private val _pane = MutableStateFlow(LowerPane.VOLUME)
    private val _visibleBars = MutableStateFlow(120)
    private val _pan = MutableStateFlow(0)
    private val _computed = MutableStateFlow(ComputedFrame(null, emptyList(), emptyList()))
    private val _loading = MutableStateFlow(false)
    private val _historyNote = MutableStateFlow("")
    private val _riskPercent = MutableStateFlow(1.0)
    private val _interactive = MutableStateFlow(InteractiveChartState())
    private val _aiChartState = MutableStateFlow<AiUiState<AiSignalSecondOpinion>>(AiUiState.Idle)
    private val _patternAiStates = MutableStateFlow<Map<String, AiUiState<AiSignalSecondOpinion>>>(emptyMap())

    private var refreshJob: Job? = null

    private val viewSettings = combine(_timeframe, _htf, _overlays, _pane, _visibleBars) { tf, htf, ov, pane, bars ->
        ViewSettings(tf, htf, ov, pane, bars)
    }

    private val statusFlow = combine(_pan, _loading, _historyNote, _riskPercent, _interactive) { pan, loading, note, risk, inter ->
        StatusBits(pan, loading, note, risk, inter)
    }

    private val aiFlow = combine(_aiChartState, aiMarketRepository.connectionStatus, _patternAiStates) { aiState, conn, patMap ->
        Triple(aiState, conn, patMap)
    }

    val uiState: StateFlow<ChartUiState> = combine(
        marketDataRepository.observeInstrument(symbol),
        tradingRepository.accountState,
        viewSettings,
        statusFlow,
        _computed,
        aiFlow
    ) { values ->
        val inst = values[0] as Instrument?
        val account = values[1] as com.example.domain.model.AccountState
        val view = values[2] as ViewSettings
        val status = values[3] as StatusBits
        val computed = values[4] as ComputedFrame
        @Suppress("UNCHECKED_CAST")
        val aiTriple = values[5] as Triple<AiUiState<AiSignalSecondOpinion>, AiConnectionStatus, Map<String, AiUiState<AiSignalSecondOpinion>>>
        val selPatId = status.interactive.selectedPatternId
        val selectedPat = selPatId?.let { id ->
            computed.analysis?.chartPatterns?.firstOrNull { it.id == id }
        }
        val selectedPatAi = selectedPat?.let { aiTriple.third[it.id] } ?: AiUiState.Idle
        ChartUiState(
            symbol = symbol,
            instrument = inst,
            timeframe = view.tf,
            higherTimeframe = view.htf,
            analysis = computed.analysis,
            amdFrames = computed.amd,
            mdrFrames = computed.mdr,
            overlays = view.overlays,
            lowerPane = view.pane,
            visibleBars = view.bars,
            panBars = status.pan,
            isLoadingHistory = status.loading,
            historyNote = status.note,
            accountCapital = account.availableMargin,
            riskPercent = status.risk,
            isExpanded = status.interactive.isExpanded,
            measureMode = status.interactive.measureMode,
            measureStartIndex = status.interactive.measureStartIndex,
            measureEndIndex = status.interactive.measureEndIndex,
            chartEngine = status.interactive.chartEngine,
            tvChartStyle = status.interactive.tvChartStyle,
            tvLogScale = status.interactive.tvLogScale,
            tvMagnetCrosshair = status.interactive.tvMagnetCrosshair,
            tvZoomCommandSeq = status.interactive.tvZoomCommandSeq,
            tvZoomDirection = status.interactive.tvZoomDirection,
            renkoConfig = status.interactive.renkoConfig,
            selectedPattern = selectedPat,
            selectedPatternAiState = selectedPatAi,
            aiChartState = aiTriple.first,
            aiConnectionStatus = aiTriple.second
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChartUiState(symbol = symbol))

    init {
        viewModelScope.launch {
            tradingPreferencesRepository.chartEngineFlow.collect { saved ->
                val mode = runCatching { ChartEngineMode.valueOf(saved) }.getOrDefault(ChartEngineMode.CANVAS)
                _interactive.update { cur ->
                    if (cur.chartEngine == mode) cur else cur.copy(chartEngine = mode)
                }
            }
        }
        startRefreshLoop(loadHistoryFirst = true)
    }

    fun switchAiModel(modelName: String) {
        aiMarketRepository.switchActiveModel(modelName)
    }

    fun analyzeChartWithAi() {
        val current = uiState.value
        val analysis = current.analysis ?: return
        val summary = buildString {
            append("Trend: ${analysis.smc.trend.name}; ")
            append("Last Close: ${analysis.lastClose}; ")
            append("Regime: ${analysis.quant.regime}; ")
            analysis.smc.structureBreaks.lastOrNull()?.let {
                append("Last Break: ${it.label} @ ${it.level}; ")
            }
            analysis.smc.dealingRange?.let {
                append("Zone: ${it.zoneOf(analysis.lastClose)}; ")
            }
            append("Active Demand Zones: ${analysis.smc.activeDemand.size}, Supply Zones: ${analysis.smc.activeSupply.size}; ")
            append("Volume > MA20: ${analysis.volumeAboveMa}; ")
            append("Cumulative Delta: ${analysis.orderFlow.sessionCumDelta}")
        }
        viewModelScope.launch {
            _aiChartState.value = AiUiState.Loading
            val res = aiMarketRepository.analyzeChartStructure(
                symbol = symbol,
                timeframeLabel = current.timeframe.label,
                instrument = current.instrument,
                recentCandles = analysis.candles.takeLast(20),
                structureSummary = summary
            )
            _aiChartState.value = res.fold(
                onSuccess = { AiUiState.Success(it) },
                onFailure = { AiUiState.Error(it.message ?: "AI chart analysis failed.") }
            )
        }
    }

    fun setTimeframe(tf: Timeframe) {
        if (tf == _timeframe.value) return
        _timeframe.value = tf
        _htf.value = tf.higherTimeframes.firstOrNull()
        _pan.value = 0
        startRefreshLoop(loadHistoryFirst = true)
    }

    fun setHigherTimeframe(htf: Timeframe?) {
        _htf.value = htf
        recomputeSoon()
    }

    fun toggleOverlay(key: OverlayKey) = _overlays.update { o ->
        when (key) {
            OverlayKey.CHART_PATTERNS -> o.copy(chartPatterns = !o.chartPatterns)
            OverlayKey.RENKO_SUPERTREND -> o.copy(renkoSupertrend = !o.renkoSupertrend)
            OverlayKey.RED_BAR -> o.copy(redBar = !o.redBar)
            OverlayKey.FIRST_RED_RANGE -> o.copy(firstRedRange = !o.firstRedRange)
            OverlayKey.EMA_9_21 -> o.copy(ema921 = !o.ema921)
            OverlayKey.EMA_50_200 -> o.copy(ema50200 = !o.ema50200)
            OverlayKey.VWAP -> o.copy(vwap = !o.vwap)
            OverlayKey.VWAP_BANDS -> o.copy(vwapBands = !o.vwapBands)
            OverlayKey.HTF -> o.copy(htfShadow = !o.htfShadow)
            OverlayKey.PIVOTS -> o.copy(pivots = !o.pivots)
            OverlayKey.SMC -> o.copy(smcZones = !o.smcZones)
            OverlayKey.LIQUIDITY -> o.copy(liquidity = !o.liquidity)
            OverlayKey.STRUCTURE -> o.copy(structure = !o.structure)
            OverlayKey.PD -> o.copy(premiumDiscount = !o.premiumDiscount)
            OverlayKey.VP -> o.copy(volumeProfile = !o.volumeProfile)
            OverlayKey.DIVERGENCE -> o.copy(divergences = !o.divergences)
            OverlayKey.SETUP -> o.copy(setupLevels = !o.setupLevels)
        }
    }

    fun selectPattern(patternId: String?) {
        _interactive.update { cur ->
            val next = if (cur.selectedPatternId == patternId) null else patternId
            cur.copy(selectedPatternId = next)
        }
    }

    fun dismissPatternTooltip() {
        _interactive.update { it.copy(selectedPatternId = null) }
    }

    fun verifyPatternWithAi(pattern: DetectedChartPattern) {
        val current = uiState.value
        val analysis = current.analysis ?: return
        viewModelScope.launch {
            _patternAiStates.update { it + (pattern.id to AiUiState.Loading) }
            val res = aiMarketRepository.validateChartPattern(
                symbol = symbol,
                timeframeLabel = current.timeframe.label,
                instrument = current.instrument,
                pattern = pattern,
                recentCandles = analysis.candles.takeLast(20)
            )
            val next = res.fold(
                onSuccess = { AiUiState.Success(it) },
                onFailure = { AiUiState.Error(it.message ?: "AI pattern verification failed.") }
            )
            _patternAiStates.update { it + (pattern.id to next) }
        }
    }

    fun buildPatternOrderRoute(pattern: DetectedChartPattern): InstrumentDetailRoute {
        val scale = uiState.value.instrument?.tickSize?.scale()?.coerceAtLeast(2) ?: 2
        fun fmt(v: Double) = BigDecimal.valueOf(v).setScale(scale, java.math.RoundingMode.HALF_UP).toPlainString()
        val isLong = pattern.direction == SignalDirection.LONG
        val product = if (uiState.value.timeframe.isIntraday) ProductType.INTRADAY else ProductType.CARRY_FORWARD
        return InstrumentDetailRoute(
            symbol = symbol,
            prefillSide = if (isLong) OrderSide.BUY.name else OrderSide.SELL.name,
            prefillEntry = fmt(pattern.breakoutLevel),
            prefillSl = fmt(pattern.invalidationPrice),
            prefillTarget = fmt(pattern.targetPrice),
            prefillProduct = product.name,
            prefillValidity = if (product == ProductType.CARRY_FORWARD) ValidityType.GTT.name else ValidityType.DAY.name
        )
    }

    fun setLowerPane(pane: LowerPane) {
        _pane.value = pane
    }

    fun setChartEngine(mode: ChartEngineMode) {
        _interactive.update {
            it.copy(
                chartEngine = mode,
                measureMode = if (mode == ChartEngineMode.TRADINGVIEW) false else it.measureMode,
                measureStartIndex = if (mode == ChartEngineMode.TRADINGVIEW) null else it.measureStartIndex,
                measureEndIndex = if (mode == ChartEngineMode.TRADINGVIEW) null else it.measureEndIndex
            )
        }
        viewModelScope.launch {
            tradingPreferencesRepository.updateChartEngine(mode.name)
        }
    }

    fun setTvChartStyle(style: TvChartStyle) {
        _interactive.update { it.copy(tvChartStyle = style) }
    }

    fun toggleTvLogScale() {
        _interactive.update { it.copy(tvLogScale = !it.tvLogScale) }
    }

    fun toggleTvMagnetCrosshair() {
        _interactive.update { it.copy(tvMagnetCrosshair = !it.tvMagnetCrosshair) }
    }

    fun setRenkoBrickMultiplier(multiplier: Double) {
        val clamped = multiplier.coerceIn(0.30, 2.50)
        _interactive.update { it.copy(renkoConfig = it.renkoConfig.copy(brickSizeMultiplier = clamped)) }
        recomputeSoon()
    }

    fun setRenkoAtrSensitivity(sensitivity: Double) {
        val clamped = sensitivity.coerceIn(1.2, 4.5)
        _interactive.update { it.copy(renkoConfig = it.renkoConfig.copy(atrSensitivity = clamped)) }
        recomputeSoon()
    }

    fun setRenkoAtrPeriod(period: Int) {
        val clamped = period.coerceIn(5, 30)
        _interactive.update { it.copy(renkoConfig = it.renkoConfig.copy(atrPeriod = clamped)) }
        recomputeSoon()
    }

    fun toggleRenkoAdaptiveMode() {
        _interactive.update { it.copy(renkoConfig = it.renkoConfig.copy(adaptiveMode = !it.renkoConfig.adaptiveMode)) }
        recomputeSoon()
    }

    fun resetRenkoDefaults() {
        _interactive.update { it.copy(renkoConfig = AiRenkoConfig()) }
        recomputeSoon()
    }

    fun zoom(zoomIn: Boolean) {
        if (_interactive.value.chartEngine == ChartEngineMode.TRADINGVIEW) {
            _interactive.update {
                it.copy(
                    tvZoomCommandSeq = it.tvZoomCommandSeq + 1,
                    tvZoomDirection = if (zoomIn) 1 else -1
                )
            }
        } else {
            _visibleBars.update { bars ->
                (if (zoomIn) bars - 20 else bars + 20).coerceIn(25, 480)
            }
        }
    }

    fun zoomByScale(scaleFactor: Float) {
        if (scaleFactor <= 0f || !scaleFactor.isFinite()) return
        _visibleBars.update { bars ->
            (bars / scaleFactor).toInt().coerceIn(25, 480)
        }
    }

    fun panByBars(deltaBars: Int, totalCandles: Int) {
        val maxPan = (totalCandles - _visibleBars.value).coerceAtLeast(0)
        _pan.update { cur -> (cur + deltaBars).coerceIn(0, maxPan) }
    }

    fun setPan(bars: Int) {
        _pan.value = bars.coerceAtLeast(0)
    }

    fun toggleExpanded() {
        _interactive.update { it.copy(isExpanded = !it.isExpanded) }
    }

    fun toggleMeasureMode() {
        _interactive.update {
            if (it.measureMode) it.copy(measureMode = false, measureStartIndex = null, measureEndIndex = null)
            else it.copy(measureMode = true, measureStartIndex = null, measureEndIndex = null)
        }
    }

    fun onMeasureTap(candleIndex: Int) {
        _interactive.update { st ->
            when {
                st.measureStartIndex == null -> st.copy(measureStartIndex = candleIndex, measureEndIndex = candleIndex)
                st.measureEndIndex == null || st.measureEndIndex == st.measureStartIndex -> st.copy(measureEndIndex = candleIndex)
                else -> st.copy(measureStartIndex = candleIndex, measureEndIndex = candleIndex)
            }
        }
    }

    fun clearMeasure() {
        _interactive.update { it.copy(measureStartIndex = null, measureEndIndex = null) }
    }

    fun setRiskPercent(percent: Double) {
        _riskPercent.value = percent.coerceIn(0.1, 5.0)
    }

    fun reloadHistory() = startRefreshLoop(loadHistoryFirst = true, force = true)

    /** Builds a prefilled order ticket for a triggered/pending setup at the chosen R multiple. */
    fun buildOrderRoute(setup: EmaCrossSetup, ratio: Double): InstrumentDetailRoute? {
        val isLong = setup.direction == SignalDirection.LONG
        val entry = setup.entry ?: setup.triggerLevel
        val sl = setup.stopLoss ?: (setup.retestExtreme ?: return null)
        val risk = kotlin.math.abs(entry - sl)
        if (risk <= 0.0) return null
        val target = if (isLong) entry + risk * ratio else entry - risk * ratio
        if (target <= 0.0) return null
        val product = if (setup.style.strategy.prefersCarryForward) ProductType.CARRY_FORWARD else ProductType.INTRADAY
        val scale = uiState.value.instrument?.tickSize?.scale()?.coerceAtLeast(2) ?: 2
        fun fmt(v: Double) = BigDecimal.valueOf(v).setScale(scale, java.math.RoundingMode.HALF_UP).toPlainString()
        return InstrumentDetailRoute(
            symbol = symbol,
            prefillSide = if (isLong) OrderSide.BUY.name else OrderSide.SELL.name,
            prefillEntry = fmt(entry),
            prefillSl = fmt(sl),
            prefillTarget = fmt(target),
            prefillProduct = product.name,
            prefillValidity = if (product == ProductType.CARRY_FORWARD) ValidityType.GTT.name else ValidityType.DAY.name
        )
    }

    /** Fixed-fractional quantity for a setup using the account's available margin as capital. */
    fun suggestedQuantity(setup: EmaCrossSetup): Int {
        val state = uiState.value
        val inst = state.instrument ?: return 0
        val entry = setup.entry ?: setup.triggerLevel
        val sl = setup.stopLoss ?: setup.retestExtreme ?: return 0
        return AdvancedIndicators.positionSizeForRisk(
            capital = state.accountCapital.toDouble(),
            riskPercent = state.riskPercent,
            entry = entry,
            stopLoss = sl,
            lotSize = inst.lotSize
        )
    }

    private fun recomputeSoon() {
        viewModelScope.launch { recompute() }
    }

    private fun startRefreshLoop(loadHistoryFirst: Boolean, force: Boolean = false) {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            if (loadHistoryFirst) {
                _loading.value = true
                val tf = _timeframe.value
                val res = signalRepository.loadHistory(symbol, tf, force = force)
                val htf = _htf.value
                // AMD frames need hourly+ history as well.
                listOf(Timeframe.H1, Timeframe.H4, Timeframe.D1).forEach { frame ->
                    if (frame != tf) signalRepository.loadHistory(symbol, frame)
                }
                if (htf != null && htf != tf) signalRepository.loadHistory(symbol, htf)
                _historyNote.value = when {
                    signalRepository.hasRealHistory(symbol, tf) -> "Real exchange history + live ticks"
                    res.isFailure -> "Simulated warm-up history (${res.exceptionOrNull()?.message ?: "no history source"}) + live ticks"
                    else -> "Live-built candles"
                }
                _loading.value = false
            }
            while (isActive) {
                recompute()
                delay(REFRESH_MS)
            }
        }
    }

    private suspend fun recompute() {
        val tf = _timeframe.value
        val htf = _htf.value
        val segment: MarketSegment? = signalRepository.getInstrumentSnapshot(symbol)?.segment
        val candles = signalRepository.getRecentCandles(symbol, tf)
        val h1 = signalRepository.getRecentCandles(symbol, Timeframe.H1)
        val h4 = signalRepository.getRecentCandles(symbol, Timeframe.H4)
        val d1 = signalRepository.getRecentCandles(symbol, Timeframe.D1)
        val ltfs = listOf(Timeframe.M1, Timeframe.M3, Timeframe.M5, Timeframe.M15).associateWith {
            signalRepository.getRecentCandles(symbol, it)
        }
        val renkoCfg = _interactive.value.renkoConfig
        val computed = withContext(Dispatchers.Default) {
            val analysis = runCatching {
                if (candles.size >= 30) {
                    MarketAnalyzer.analyze(
                        candles = candles,
                        timeframe = tf,
                        segment = segment,
                        higherTimeframe = htf,
                        renkoConfig = renkoCfg
                    )
                } else null
            }.getOrNull()
            val amd = listOf(Timeframe.H1 to h1, Timeframe.H4 to h4, Timeframe.D1 to d1)
                .filter { it.second.size >= 10 }
                .mapNotNull { (frame, list) -> runCatching { FrameAmd(frame, SmcAnalyzer.amd(list, frame, segment)) }.getOrNull() }
            val mdr = ltfs.entries
                .filter { it.value.size >= 30 }
                .mapNotNull { (frame, list) ->
                    runCatching {
                        val swings = AdvancedIndicators.swingPoints(list, 3, 3)
                        val atr = TechnicalIndicators.atr(list, 14)
                        val avgAtr = atr.filterNotNull().takeLast(50).average().takeIf { !it.isNaN() && it > 0.0 }
                            ?: list.takeLast(20).map { it.high - it.low }.average()
                        val fvgs = SmcAnalyzer.fairValueGaps(list, avgAtr * 0.1)
                        FrameMdr(frame, SmcAnalyzer.mdr(list, swings, fvgs, atr, segment, avgAtr))
                    }.getOrNull()
                }
            ComputedFrame(analysis, amd, mdr)
        }
        _computed.value = computed
    }

    private data class ViewSettings(
        val tf: Timeframe,
        val htf: Timeframe?,
        val overlays: ChartOverlays,
        val pane: LowerPane,
        val bars: Int
    )

    private data class InteractiveChartState(
        val isExpanded: Boolean = false,
        val measureMode: Boolean = false,
        val measureStartIndex: Int? = null,
        val measureEndIndex: Int? = null,
        val chartEngine: ChartEngineMode = ChartEngineMode.CANVAS,
        val tvChartStyle: TvChartStyle = TvChartStyle.CANDLES,
        val tvLogScale: Boolean = false,
        val tvMagnetCrosshair: Boolean = true,
        val tvZoomCommandSeq: Int = 0,
        val tvZoomDirection: Int = 0,
        val renkoConfig: AiRenkoConfig = AiRenkoConfig(),
        val selectedPatternId: String? = null
    )

    private data class StatusBits(
        val pan: Int,
        val loading: Boolean,
        val note: String,
        val risk: Double,
        val interactive: InteractiveChartState
    )

    companion object {
        private const val REFRESH_MS = 4_000L
    }
}
