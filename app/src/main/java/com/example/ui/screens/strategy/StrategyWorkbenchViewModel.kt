package com.example.ui.screens.strategy

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.repository.MarketDataRepository
import com.example.data.repository.TradingPreferencesRepository
import com.example.data.repository.TradingRepository
import com.example.data.signal.SignalRepository
import com.example.domain.model.AccountState
import com.example.domain.model.Instrument
import com.example.domain.model.MarketSegment
import com.example.domain.model.Position
import com.example.domain.model.ProductType
import com.example.domain.signal.Candle
import com.example.domain.signal.Timeframe
import com.example.domain.strategy.ExitPlanner
import com.example.domain.strategy.MetricScreener
import com.example.domain.strategy.PortfolioRebalancer
import com.example.domain.strategy.PositionSizer
import com.example.domain.strategy.SavedScreenRule
import com.example.domain.strategy.SavedTarget
import com.example.domain.strategy.SavedTaxLot
import com.example.domain.strategy.ScreenMetric
import com.example.domain.strategy.SetupScanner
import com.example.domain.strategy.TaxLotTracker
import com.example.domain.strategy.TradeOutcomeSimulator
import com.example.domain.strategy.TraderRules
import com.example.domain.strategy.WorkbenchState
import com.example.domain.strategy.defaultScreenRules
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject

/** Which pane of the workbench is showing. */
enum class WorkbenchTab(val label: String) {
    SCANNER("Scanner"),
    PLAN_AND_SIZE("Plan & Size"),
    SIMULATOR("Simulator"),
    TAX_LOTS("Tax lots"),
    REBALANCE("Rebalance"),
    SCREENER("Screener")
}

/** One scanned instrument: its daily setups plus any opening-range breakout today. */
data class ScannerRow(
    val symbol: String,
    val displayName: String,
    val segment: MarketSegment,
    val ltp: BigDecimal,
    val scan: SetupScanner.DailyScan,
    val breakout: SetupScanner.Breakout?
) {
    val vsHigh20Pct: Double get() = (ltp.toDouble() / scan.high20 - 1.0) * 100.0
    val vsMa20Pct: Double get() = (ltp.toDouble() / scan.ma20 - 1.0) * 100.0
    val hasAnything: Boolean get() = scan.hasSetup || breakout != null
}

/** A harvest candidate carries its symbol, which the engine's own type does not. */
data class HarvestRow(val symbol: String, val lot: TaxLotTracker.UnrealisedLot)

data class StrategyWorkbenchUiState(
    val tab: WorkbenchTab = WorkbenchTab.SCANNER,
    val instruments: List<Instrument> = emptyList(),
    val positions: List<Position> = emptyList(),
    val account: AccountState? = null,
    val workbench: WorkbenchState = WorkbenchState(),
    val isWorking: Boolean = false,
    val message: String? = null,

    // --- scanner
    val scannerRows: List<ScannerRow> = emptyList(),
    val scannerOpeningRangeBars: Int = 3,
    val scannerMinVolumeMultiple: Double? = 1.5,
    val scannerOnlySetups: Boolean = true,
    val scannerSegment: MarketSegment? = null,
    val scannerSkippedForHistory: Int = 0,
    val scannerHasRun: Boolean = false,

    // --- plan & size
    val planSymbol: String = "",
    val planEntry: String = "",
    val planMaxLossPct: String = "1.0",
    val planStopRule: PlanStopRuleKind = PlanStopRuleKind.PERCENT,
    val planStopValue: String = "1.5",
    val planAtr: String = "",
    val planOrHigh: String = "",
    val planOrLow: String = "",
    val planTargetRule: PlanTargetRuleKind = PlanTargetRuleKind.R_MULTIPLE,
    val planTargetValue: String = "2.0",
    val planResult: ExitPlanner.Plan? = null,
    val sizingResult: PositionSizer.Sizing? = null,
    val planError: String? = null,

    // --- simulator
    val simSymbol: String = "",
    val simIsLong: Boolean = true,
    val simEntry: String = "",
    val simStop: String = "",
    val simTarget: String = "",
    val simSessionBars: Int = TradeOutcomeSimulator.SESSION_BARS,
    val simOpeningRangeBars: Int = 3,
    val simFromBar: Int = 6,
    val simReplays: Int = 10_000,
    val simSeed: Int = 19,
    val simMonteCarlo: TradeOutcomeSimulator.MonteCarloResult? = null,
    val simHistory: TradeOutcomeSimulator.HistoryResult? = null,
    val simSessionCount: Int = 0,
    val simNote: String? = null,

    // --- tax lots
    val lotSymbol: String = "",
    val saleShares: String = "",
    val salePrice: String = "",
    val saleMethod: TaxLotTracker.Method = TaxLotTracker.Method.FIFO,
    val saleLotIds: String = "",
    val saleResult: TaxLotTracker.SaleResult? = null,
    val saleError: String? = null,
    val harvestMinLoss: String = "1000",
    val harvestRows: List<HarvestRow> = emptyList(),

    // --- rebalance
    val rebalanceBandPct: String = "5",
    val rebalanceSellUntracked: Boolean = false,
    val rebalancePlan: PortfolioRebalancer.Plan? = null,
    val rebalanceError: String? = null,

    // --- screener
    val screenOnlyPassing: Boolean = false,
    val screenMissingFails: Boolean = true,
    val screenRankBy: String? = null,
    val screenResults: List<MetricScreener.Result> = emptyList(),
    val screenHasRun: Boolean = false
) {
    val visibleScannerRows: List<ScannerRow>
        get() = if (scannerOnlySetups) scannerRows.filter { it.hasAnything } else scannerRows

    val lotsForSymbol: List<SavedTaxLot>
        get() = workbench.taxLots.filter { it.symbol == lotSymbol }

    /**
     * What the account actually has free. Capital is not debited by a position's notional
     * in this app - that is tracked as used margin - so capital on its own would count the
     * same money twice when the rebalancer is deciding what it can buy.
     */
    val freeCash: BigDecimal
        get() = account?.availableMargin ?: BigDecimal.ZERO
}

@HiltViewModel
class StrategyWorkbenchViewModel @Inject constructor(
    private val marketDataRepository: MarketDataRepository,
    private val tradingRepository: TradingRepository,
    private val signalRepository: SignalRepository,
    private val preferencesRepository: TradingPreferencesRepository
) : ViewModel() {

    private val _state = MutableStateFlow(StrategyWorkbenchUiState())
    val state: StateFlow<StrategyWorkbenchUiState> = _state.asStateFlow()

    /** Daily closes are reread per scan but cached within one, so a scan walks each symbol once. */
    private val dailyCloseCache = mutableMapOf<String, List<Double>>()
    private var scannerAutoRunTriggered = false

    init {
        viewModelScope.launch {
            marketDataRepository.instruments.collect { list ->
                _state.update { s ->
                    s.copy(
                        instruments = list,
                        planSymbol = s.planSymbol.ifBlank { list.firstOrNull()?.symbol.orEmpty() },
                        simSymbol = s.simSymbol.ifBlank { list.firstOrNull()?.symbol.orEmpty() },
                        lotSymbol = s.lotSymbol.ifBlank { list.firstOrNull()?.symbol.orEmpty() }
                    )
                }
                if (list.isNotEmpty() && !scannerAutoRunTriggered && !_state.value.scannerHasRun) {
                    scannerAutoRunTriggered = true
                    runScanner()
                }
            }
        }
        viewModelScope.launch {
            tradingRepository.positions.collect { list -> _state.update { it.copy(positions = list) } }
        }
        viewModelScope.launch {
            tradingRepository.accountState.collect { acc -> _state.update { it.copy(account = acc) } }
        }
        viewModelScope.launch {
            preferencesRepository.workbenchFlow.collect { wb ->
                _state.update { it.copy(workbench = wb) }
                refreshHarvest()
            }
        }
    }

    fun selectTab(tab: WorkbenchTab) {
        _state.update { it.copy(tab = tab, message = null) }
        if (tab == WorkbenchTab.SCANNER && !_state.value.scannerHasRun) runScanner()
        if (tab == WorkbenchTab.TAX_LOTS) refreshHarvest()
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    fun showMessage(msg: String) = _state.update { it.copy(message = msg) }

    // =====================================================================
    // Setup scanner
    // =====================================================================

    fun setScannerOpeningRangeBars(bars: Int) =
        _state.update { it.copy(scannerOpeningRangeBars = bars.coerceIn(1, 30)) }

    fun setScannerMinVolumeMultiple(value: Double?) =
        _state.update { it.copy(scannerMinVolumeMultiple = value?.takeIf { v -> v > 0.0 }) }

    fun setScannerOnlySetups(only: Boolean) = _state.update { it.copy(scannerOnlySetups = only) }

    fun setScannerSegment(segment: MarketSegment?) {
        _state.update { it.copy(scannerSegment = segment) }
        runScanner()
    }

    fun runScanner() {
        val snapshot = _state.value
        viewModelScope.launch {
            _state.update { it.copy(isWorking = true) }
            dailyCloseCache.clear()
            val rows = mutableListOf<ScannerRow>()
            var skipped = 0

            val universe = snapshot.instruments
                .filter { snapshot.scannerSegment == null || it.segment == snapshot.scannerSegment }

            for (inst in universe) {
                val closes = closesFor(inst.symbol)
                val scan = SetupScanner.findDailySetups(closes)
                if (scan == null) {
                    skipped++
                    continue
                }
                val intraday = signalRepository.getRecentCandles(inst.symbol, Timeframe.M5)
                val breakout = runCatching {
                    SetupScanner.openingRangeBreakout(
                        bars = intraday,
                        rangeBars = snapshot.scannerOpeningRangeBars,
                        minVolumeMultiple = snapshot.scannerMinVolumeMultiple
                    )
                }.getOrNull()

                rows += ScannerRow(
                    symbol = inst.symbol,
                    displayName = inst.displayName,
                    segment = inst.segment,
                    ltp = inst.ltp,
                    scan = scan,
                    breakout = breakout
                )
            }

            // Anything with both a daily setup and a breakout first, then either, then
            // alphabetically, so the order does not shuffle between runs.
            rows.sortWith(
                compareByDescending<ScannerRow> {
                    (if (it.scan.hasSetup) 2 else 0) + (if (it.breakout != null) 1 else 0)
                }.thenBy { it.symbol }
            )

            _state.update {
                it.copy(
                    scannerRows = rows,
                    scannerSkippedForHistory = skipped,
                    scannerHasRun = true,
                    isWorking = false
                )
            }
        }
    }

    private suspend fun closesFor(symbol: String): List<Double> =
        dailyCloseCache.getOrPut(symbol) {
            signalRepository.getRecentCandles(symbol, Timeframe.D1).map { it.close }
        }

    // =====================================================================
    // Exit Planner & Position Sizer
    // =====================================================================

    fun setPlanSymbol(symbol: String) = _state.update {
        it.copy(planSymbol = symbol, planResult = null, sizingResult = null, planError = null)
    }

    fun setPlanEntry(v: String) = _state.update { it.copy(planEntry = v) }
    fun setPlanMaxLossPct(v: String) = _state.update { it.copy(planMaxLossPct = v) }

    fun setPlanStopRule(kind: PlanStopRuleKind) = _state.update { s ->
        val nextValue = when (kind) {
            PlanStopRuleKind.PERCENT -> if (s.planStopRule == PlanStopRuleKind.PERCENT) s.planStopValue else "1.5"
            PlanStopRuleKind.ATR -> if (s.planStopRule == PlanStopRuleKind.ATR) s.planStopValue else "1.5"
            PlanStopRuleKind.PRICE -> if (s.planStopRule == PlanStopRuleKind.PRICE) {
                s.planStopValue
            } else {
                val entry = s.planEntry.toDoubleOrNull()
                if (entry != null && entry > 0.0) fmt2(entry * 0.985) else ""
            }
            PlanStopRuleKind.OR_MID, PlanStopRuleKind.OR_LOW -> s.planStopValue
        }
        s.copy(planStopRule = kind, planStopValue = nextValue, planError = null)
    }

    fun setPlanStopValue(v: String) = _state.update { it.copy(planStopValue = v) }
    fun setPlanAtr(v: String) = _state.update { it.copy(planAtr = v) }
    fun setPlanOrHigh(v: String) = _state.update { it.copy(planOrHigh = v) }
    fun setPlanOrLow(v: String) = _state.update { it.copy(planOrLow = v) }

    fun setPlanTargetRule(kind: PlanTargetRuleKind) = _state.update { s ->
        val nextValue = when (kind) {
            PlanTargetRuleKind.R_MULTIPLE -> if (s.planTargetRule == PlanTargetRuleKind.R_MULTIPLE) s.planTargetValue else "2.0"
            PlanTargetRuleKind.PRICE -> if (s.planTargetRule == PlanTargetRuleKind.PRICE) {
                s.planTargetValue
            } else {
                val entry = s.planEntry.toDoubleOrNull()
                if (entry != null && entry > 0.0) fmt2(entry * 1.03) else ""
            }
        }
        s.copy(planTargetRule = kind, planTargetValue = nextValue, planError = null)
    }

    fun setPlanTargetValue(v: String) = _state.update { it.copy(planTargetValue = v) }

    fun fillPlannerFromMarket() {
        val s = _state.value
        val inst = s.instruments.firstOrNull { it.symbol == s.planSymbol } ?: run {
            _state.update { it.copy(message = "Pick a symbol first.") }
            return
        }
        viewModelScope.launch {
            val bars = signalRepository.getRecentCandles(s.planSymbol, Timeframe.M5)
            val atr = averageTrueRange(bars, 14)
            val openingBars = bars.take(3)
            val orHigh = openingBars.maxOfOrNull { it.high }
            val orLow = openingBars.minOfOrNull { it.low }
            val entry = inst.ltp.toDouble()
            _state.update { cur ->
                cur.copy(
                    planEntry = fmt2(entry),
                    planAtr = if (atr > 0.0) fmt2(atr) else cur.planAtr,
                    planOrHigh = orHigh?.let { fmt2(it) } ?: cur.planOrHigh,
                    planOrLow = orLow?.let { fmt2(it) } ?: cur.planOrLow,
                    planStopValue = if (cur.planStopRule == PlanStopRuleKind.PRICE && atr > 0.0) {
                        fmt2((entry - 1.5 * atr).coerceAtLeast(0.05))
                    } else {
                        cur.planStopValue
                    },
                    planError = null
                )
            }
        }
    }

    fun runPlanAndSize() {
        val s = _state.value
        val inst = s.instruments.firstOrNull { it.symbol == s.planSymbol }
        if (inst == null) {
            _state.update { it.copy(planError = "Pick a symbol first.", planResult = null, sizingResult = null) }
            return
        }
        val entry = s.planEntry.toBigDecimalOrNull() ?: inst.ltp
        if (entry <= BigDecimal.ZERO) {
            _state.update { it.copy(planError = "Entry price must be positive.", planResult = null, sizingResult = null) }
            return
        }

        val stopRule = when (s.planStopRule) {
            PlanStopRuleKind.PERCENT -> {
                val v = s.planStopValue.toBigDecimalOrNull()
                if (v == null) {
                    _state.update { it.copy(planError = "Enter a valid stop percentage.", planResult = null, sizingResult = null) }
                    return
                }
                ExitPlanner.StopRule.Percent(v)
            }
            PlanStopRuleKind.ATR -> {
                val v = s.planStopValue.toBigDecimalOrNull()
                if (v == null) {
                    _state.update { it.copy(planError = "Enter a valid ATR multiple.", planResult = null, sizingResult = null) }
                    return
                }
                ExitPlanner.StopRule.AtrMultiple(v)
            }
            PlanStopRuleKind.OR_MID -> ExitPlanner.StopRule.OpeningRangeMid
            PlanStopRuleKind.OR_LOW -> ExitPlanner.StopRule.OpeningRangeLow
            PlanStopRuleKind.PRICE -> {
                val v = s.planStopValue.toBigDecimalOrNull()
                if (v == null) {
                    _state.update { it.copy(planError = "Enter a valid stop price.", planResult = null, sizingResult = null) }
                    return
                }
                ExitPlanner.StopRule.Price(v)
            }
        }

        val targetRule = when (s.planTargetRule) {
            PlanTargetRuleKind.R_MULTIPLE -> {
                val v = s.planTargetValue.toBigDecimalOrNull()
                if (v == null) {
                    _state.update { it.copy(planError = "Enter a valid target R multiple.", planResult = null, sizingResult = null) }
                    return
                }
                ExitPlanner.TargetRule.RMultiple(v)
            }
            PlanTargetRuleKind.PRICE -> {
                val v = s.planTargetValue.toBigDecimalOrNull()
                if (v == null) {
                    _state.update { it.copy(planError = "Enter a valid target price.", planResult = null, sizingResult = null) }
                    return
                }
                ExitPlanner.TargetRule.Price(v)
            }
        }

        val planAttempt = runCatching {
            ExitPlanner.plan(
                entry = entry,
                stopRule = stopRule,
                targetRule = targetRule,
                openingRangeHigh = s.planOrHigh.toBigDecimalOrNull(),
                openingRangeLow = s.planOrLow.toBigDecimalOrNull(),
                atr = s.planAtr.toBigDecimalOrNull(),
                tickSize = inst.tickSize
            )
        }
        val plan = planAttempt.getOrElse { err ->
            _state.update { it.copy(planError = err.message ?: "Exit plan refused.", planResult = null, sizingResult = null) }
            return
        }

        val isCrypto = inst.segment == MarketSegment.CRYPTO
        val capital = if (isCrypto) {
            s.account?.cryptoVirtualCapital ?: BigDecimal("10000")
        } else {
            s.account?.virtualCapital ?: BigDecimal("100000")
        }
        val cash = if (isCrypto) {
            s.account?.cryptoAvailableMargin ?: capital
        } else {
            s.freeCash
        }
        val riskPct = s.planMaxLossPct.toBigDecimalOrNull()
            ?.takeIf { it > BigDecimal.ZERO && it <= BigDecimal("25") }
            ?: BigDecimal("1.0")
        val baseRules = workbenchRules(capital)
        val sizingRules = baseRules.copy(
            maxLossPerTradeInr = baseRules.budget.multiply(riskPct)
                .divide(TraderRules.HUNDRED, 2, RoundingMode.HALF_UP)
        )

        val sizingAttempt = runCatching {
            PositionSizer.size(
                symbol = inst.symbol,
                plan = plan,
                rules = sizingRules,
                livePrice = inst.ltp,
                cashOnHand = cash
            )
        }
        sizingAttempt.onSuccess { sizing ->
            _state.update {
                it.copy(
                    planEntry = fmt2(entry.toDouble()),
                    planResult = plan,
                    sizingResult = sizing,
                    planError = null
                )
            }
        }.onFailure { err ->
            _state.update {
                it.copy(
                    planResult = plan,
                    sizingResult = null,
                    planError = err.message ?: "Position sizing failed."
                )
            }
        }
    }

    // =====================================================================
    // Outcome simulator
    // =====================================================================

    fun setSimSymbol(symbol: String) = _state.update {
        it.copy(simSymbol = symbol, simMonteCarlo = null, simHistory = null, simNote = null)
    }

    fun setSimIsLong(isLong: Boolean) = _state.update { it.copy(simIsLong = isLong) }
    fun setSimEntry(v: String) = _state.update { it.copy(simEntry = v) }
    fun setSimStop(v: String) = _state.update { it.copy(simStop = v) }
    fun setSimTarget(v: String) = _state.update { it.copy(simTarget = v) }
    fun setSimSessionBars(v: Int) = _state.update { it.copy(simSessionBars = v.coerceIn(10, 400)) }
    fun setSimOpeningRangeBars(v: Int) = _state.update { it.copy(simOpeningRangeBars = v.coerceIn(1, 60)) }
    fun setSimFromBar(v: Int) = _state.update { it.copy(simFromBar = v.coerceAtLeast(1)) }
    fun setSimReplays(v: Int) = _state.update { it.copy(simReplays = v.coerceIn(100, 100_000)) }
    fun setSimSeed(v: Int) = _state.update { it.copy(simSeed = v) }

    /** Seed entry / stop / target from the live price and a 1.5x ATR stop at 2R. */
    fun fillSimulatorFromMarket() {
        val s = _state.value
        val inst = s.instruments.firstOrNull { it.symbol == s.simSymbol } ?: return
        viewModelScope.launch {
            val bars = signalRepository.getRecentCandles(s.simSymbol, Timeframe.M5)
            val atr = averageTrueRange(bars, 14)
            if (atr <= 0.0) {
                _state.update { it.copy(message = "Not enough 5-minute history to measure ATR.") }
                return@launch
            }
            val entry = inst.ltp.toDouble()
            val stop = if (s.simIsLong) entry - 1.5 * atr else entry + 1.5 * atr
            val target = if (s.simIsLong) entry + 3.0 * atr else entry - 3.0 * atr
            _state.update {
                it.copy(
                    simEntry = fmt2(entry),
                    simStop = fmt2(stop),
                    simTarget = fmt2(target)
                )
            }
        }
    }

    fun runSimulation() {
        val s = _state.value
        val entry = s.simEntry.toDoubleOrNull()
        val stop = s.simStop.toDoubleOrNull()
        val target = s.simTarget.toDoubleOrNull()
        if (entry == null || stop == null || target == null) {
            _state.update { it.copy(message = "Entry, stop and target all need a price.") }
            return
        }
        val risk = if (s.simIsLong) entry - stop else stop - entry
        if (risk <= 0.0) {
            val side = if (s.simIsLong) "below" else "above"
            _state.update { it.copy(message = "A ${if (s.simIsLong) "long" else "short"} needs its stop $side the entry.") }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(isWorking = true) }
            val bars = signalRepository.getRecentCandles(s.simSymbol, Timeframe.M5)
            val sessions = bars.chunked(s.simSessionBars).filter { it.size == s.simSessionBars }

            if (sessions.isEmpty()) {
                _state.update {
                    it.copy(
                        isWorking = false,
                        simMonteCarlo = null,
                        simHistory = null,
                        simSessionCount = 0,
                        simNote = "No complete session of ${s.simSessionBars} bars is loaded for " +
                            "${s.simSymbol}. Open its chart first so the 5-minute history is built, " +
                            "or lower the bars per session."
                    )
                }
                return@launch
            }

            val pool = TradeOutcomeSimulator.buildPool(sessions, s.simSessionBars)
            val mc = TradeOutcomeSimulator.monteCarlo(
                pool = pool,
                entry = entry,
                stop = stop,
                target = target,
                fromBar = s.simFromBar,
                toBar = s.simSessionBars - 1,
                replays = s.simReplays,
                seed = s.simSeed,
                isLong = s.simIsLong
            )
            val replay = TradeOutcomeSimulator.historyReplay(
                sessions = sessions,
                rangeBars = s.simOpeningRangeBars,
                stopRule = ExitPlanner.StopRule.OpeningRangeLow,
                targetR = (if (s.simIsLong) target - entry else entry - target) / risk,
                toBar = s.simSessionBars - 1,
                isLong = s.simIsLong
            )

            _state.update {
                it.copy(
                    isWorking = false,
                    simMonteCarlo = mc,
                    simHistory = replay,
                    simSessionCount = sessions.size,
                    simNote = "${sessions.size} session(s) of ${s.simSessionBars} bars from the " +
                        "loaded 5-minute history (${bars.size} bars)."
                )
            }
        }
    }

    private fun averageTrueRange(bars: List<Candle>, period: Int): Double {
        if (bars.size <= period) return 0.0
        var sum = 0.0
        for (i in bars.size - period until bars.size) {
            val c = bars[i]
            val prev = bars[i - 1]
            sum += maxOf(
                c.high - c.low,
                kotlin.math.abs(c.high - prev.close),
                kotlin.math.abs(c.low - prev.close)
            )
        }
        return sum / period
    }

    // =====================================================================
    // Tax lots
    // =====================================================================

    fun setLotSymbol(symbol: String) =
        _state.update { it.copy(lotSymbol = symbol, saleResult = null, saleError = null) }

    fun setSaleShares(v: String) = _state.update { it.copy(saleShares = v) }
    fun setSalePrice(v: String) = _state.update { it.copy(salePrice = v) }
    fun setSaleMethod(m: TaxLotTracker.Method) = _state.update { it.copy(saleMethod = m) }
    fun setSaleLotIds(v: String) = _state.update { it.copy(saleLotIds = v) }

    fun setHarvestMinLoss(v: String) {
        _state.update { it.copy(harvestMinLoss = v) }
        refreshHarvest()
    }

    fun addTaxLot(acquired: LocalDate, shares: Int, costPerShare: BigDecimal) {
        val s = _state.value
        if (s.lotSymbol.isBlank()) {
            _state.update { it.copy(message = "Pick a symbol first.") }
            return
        }
        if (shares <= 0 || costPerShare <= BigDecimal.ZERO) {
            _state.update { it.copy(message = "A lot needs a positive quantity and cost.") }
            return
        }
        val existing = s.workbench.taxLots
        var n = existing.count { it.symbol == s.lotSymbol } + 1
        while (existing.any { it.symbol == s.lotSymbol && it.id == "L$n" }) n++

        persist(
            s.workbench.copy(
                taxLots = existing + SavedTaxLot(
                    id = "L$n",
                    symbol = s.lotSymbol,
                    acquired = acquired.toString(),
                    shares = shares,
                    costPerShare = costPerShare.toPlainString()
                )
            )
        )
    }

    fun removeTaxLot(id: String) {
        val s = _state.value
        persist(
            s.workbench.copy(
                taxLots = s.workbench.taxLots.filterNot { it.symbol == s.lotSymbol && it.id == id }
            )
        )
        _state.update { it.copy(saleResult = null, saleError = null) }
    }

    fun clearLotsForSymbol() {
        val s = _state.value
        persist(s.workbench.copy(taxLots = s.workbench.taxLots.filterNot { it.symbol == s.lotSymbol }))
        _state.update { it.copy(saleResult = null, saleError = null) }
    }

    /** Turn every open carry-forward long into a lot, skipping ones already imported. */
    fun importPositionsAsLots() {
        val s = _state.value
        val held = s.positions.filter {
            it.productType == ProductType.CARRY_FORWARD &&
                it.side == com.example.domain.model.OrderSide.BUY &&
                it.quantity > 0
        }
        if (held.isEmpty()) {
            _state.update { it.copy(message = "No open carry-forward positions to import.") }
            return
        }
        val known = s.workbench.taxLots.map { it.id }.toSet()
        val fresh = held
            .filterNot { known.contains(it.id) }
            .map { pos ->
                SavedTaxLot(
                    id = pos.id,
                    symbol = pos.symbol,
                    acquired = Instant.ofEpochMilli(pos.openedAtMillis)
                        .atZone(ZoneId.of("Asia/Kolkata")).toLocalDate().toString(),
                    shares = pos.quantity,
                    costPerShare = pos.averageEntryPrice.toPlainString()
                )
            }
        if (fresh.isEmpty()) {
            _state.update { it.copy(message = "Those positions are already in the lot book.") }
            return
        }
        persist(s.workbench.copy(taxLots = s.workbench.taxLots + fresh))
        _state.update { it.copy(message = "Imported ${fresh.size} lot(s).") }
    }

    fun runSaleSimulation() {
        val s = _state.value
        val shares = s.saleShares.toIntOrNull()
        val price = s.salePrice.toBigDecimalOrNull()
        if (shares == null || price == null) {
            _state.update { it.copy(saleError = "A sale needs a quantity and a price.", saleResult = null) }
            return
        }
        val lots = s.lotsForSymbol.map { it.toEngineLot() }
        val specificIds = s.saleLotIds
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val result = runCatching {
            TaxLotTracker.simulateSale(
                lots = lots,
                shares = shares,
                price = price,
                soldOn = today(),
                method = s.saleMethod,
                lotIds = if (s.saleMethod == TaxLotTracker.Method.SPECIFIC) specificIds else emptyList()
            )
        }
        _state.update {
            it.copy(
                saleResult = result.getOrNull(),
                saleError = result.exceptionOrNull()?.message
            )
        }
    }

    private fun refreshHarvest() {
        val s = _state.value
        val minLoss = s.harvestMinLoss.toBigDecimalOrNull() ?: BigDecimal("1000")
        val priceOf = s.instruments.associate { it.symbol to it.ltp }
        val rows = s.workbench.taxLots
            .groupBy { it.symbol }
            .flatMap { (symbol, lots) ->
                val price = priceOf[symbol] ?: return@flatMap emptyList<HarvestRow>()
                TaxLotTracker
                    .harvestCandidates(lots.map { it.toEngineLot() }, price, today(), minLoss)
                    .map { HarvestRow(symbol, it) }
            }
            .sortedBy { it.lot.gain }
        _state.update { it.copy(harvestRows = rows) }
    }

    // =====================================================================
    // Rebalancer
    // =====================================================================

    fun setRebalanceBandPct(v: String) = _state.update { it.copy(rebalanceBandPct = v) }
    fun setRebalanceSellUntracked(v: Boolean) = _state.update { it.copy(rebalanceSellUntracked = v) }

    fun addRebalanceTarget(symbol: String) {
        val s = _state.value
        if (symbol.isBlank() || s.workbench.rebalanceTargets.any { it.symbol == symbol }) return
        persist(s.workbench.copy(rebalanceTargets = s.workbench.rebalanceTargets + SavedTarget(symbol, "10")))
    }

    fun setTargetWeight(symbol: String, weight: String) {
        val s = _state.value
        val others = s.workbench.rebalanceTargets.filterNot { it.symbol == symbol }
        persist(s.workbench.copy(rebalanceTargets = others + SavedTarget(symbol, weight)))
    }

    fun removeTarget(symbol: String) {
        val s = _state.value
        persist(
            s.workbench.copy(
                rebalanceTargets = s.workbench.rebalanceTargets.filterNot { it.symbol == symbol }
            )
        )
    }

    /** Equal-weight whatever carry-forward longs are open, as a starting point. */
    fun equalWeightHeld() {
        val s = _state.value
        val symbols = holdings(s).map { it.symbol }.distinct()
        if (symbols.isEmpty()) {
            _state.update { it.copy(message = "No open carry-forward positions to weight.") }
            return
        }
        val each = BigDecimal(100)
            .divide(BigDecimal(symbols.size), 2, RoundingMode.HALF_UP)
            .toPlainString()
        persist(s.workbench.copy(rebalanceTargets = symbols.map { SavedTarget(it, each) }))
    }

    private fun holdings(s: StrategyWorkbenchUiState): List<PortfolioRebalancer.Holding> =
        s.positions
            .filter {
                it.productType == ProductType.CARRY_FORWARD &&
                    it.side == com.example.domain.model.OrderSide.BUY &&
                    it.quantity > 0
            }
            .groupBy { it.symbol }
            .map { (symbol, group) -> PortfolioRebalancer.Holding(symbol, group.sumOf { it.quantity }) }

    fun runRebalance() {
        val s = _state.value
        val held = holdings(s)
        val weights = s.workbench.rebalanceTargets
            .mapNotNull { t -> t.weight.toBigDecimalOrNull()?.let { t.symbol to it } }
            .toMap()

        if (held.isEmpty() && weights.isEmpty()) {
            _state.update {
                it.copy(
                    rebalancePlan = null,
                    rebalanceError = "Nothing to rebalance: no open carry-forward positions and no targets."
                )
            }
            return
        }

        val priceOf = s.instruments.associate { it.symbol to it.ltp }
        val prices = (held.map { it.symbol } + weights.keys).distinct()
            .mapNotNull { sym -> priceOf[sym]?.let { sym to it } }
            .toMap()

        val capital = s.account?.virtualCapital ?: BigDecimal.ZERO
        val result = runCatching {
            PortfolioRebalancer.plan(
                holdings = held,
                prices = prices,
                targetWeights = weights,
                rules = workbenchRules(capital),
                cash = s.freeCash,
                bandPct = s.rebalanceBandPct.toBigDecimalOrNull() ?: BigDecimal("5"),
                sellUntracked = s.rebalanceSellUntracked
            )
        }
        _state.update {
            it.copy(
                rebalancePlan = result.getOrNull(),
                rebalanceError = result.exceptionOrNull()?.message
            )
        }
    }

    /**
     * Percentage limits come from the rule-book; the rupee ones scale off the paper capital,
     * so a Rs 10,00,000 account is not judged against the pack's Rs 1,00,000 default. This
     * mirrors what the order ticket's risk panel does.
     */
    private fun workbenchRules(capital: BigDecimal): TraderRules {
        val budget = if (capital > BigDecimal.ZERO) capital else BigDecimal("100000")
        return TraderRules(
            budget = budget,
            maxOrderInr = budget.multiply(BigDecimal("0.5")),
            minOrderInr = BigDecimal.ZERO,
            maxLossPerTradeInr = budget.multiply(BigDecimal("0.01")),
            maxDailyLossInr = budget.multiply(BigDecimal("0.03")),
            maxTradesPerDay = Int.MAX_VALUE,
            maxOrdersPerDay = Int.MAX_VALUE,
            requireStop = false,
            allowShort = true,
            noEntriesBefore = LocalTime.MIDNIGHT,
            noEntriesAfter = LocalTime.of(23, 59)
        )
    }

    // =====================================================================
    // Screener
    // =====================================================================

    fun setScreenOnlyPassing(v: Boolean) = _state.update { it.copy(screenOnlyPassing = v) }
    fun setScreenMissingFails(v: Boolean) = _state.update { it.copy(screenMissingFails = v) }
    fun setScreenRankBy(key: String?) = _state.update { it.copy(screenRankBy = key) }

    fun addScreenRule() {
        val s = _state.value
        persist(
            s.workbench.copy(
                screenRules = s.workbench.screenRules + SavedScreenRule("ltp", ">", listOf("0"))
            )
        )
    }

    fun removeScreenRule(index: Int) {
        val s = _state.value
        if (index !in s.workbench.screenRules.indices) return
        persist(
            s.workbench.copy(
                screenRules = s.workbench.screenRules.filterIndexed { i, _ -> i != index }
            )
        )
    }

    fun resetScreenRules() = persist(_state.value.workbench.copy(screenRules = defaultScreenRules()))

    fun updateScreenRule(index: Int, rule: SavedScreenRule) {
        val s = _state.value
        if (index !in s.workbench.screenRules.indices) return
        persist(
            s.workbench.copy(
                screenRules = s.workbench.screenRules.mapIndexed { i, r -> if (i == index) rule else r }
            )
        )
    }

    fun runScreen() {
        val s = _state.value
        viewModelScope.launch {
            _state.update { it.copy(isWorking = true) }
            dailyCloseCache.clear()

            val companies = s.instruments.map { inst ->
                val scan = SetupScanner.findDailySetups(closesFor(inst.symbol))
                val ltp = inst.ltp.toDouble()
                val metrics = mutableMapOf<String, Any?>(
                    ScreenMetric.LTP.key to ltp,
                    ScreenMetric.CHANGE_PCT.key to inst.changePercent.toDouble(),
                    ScreenMetric.SEGMENT.key to inst.segment.label
                )
                if (scan != null) {
                    metrics[ScreenMetric.RSI_14.key] = scan.rsi
                    metrics[ScreenMetric.VS_HIGH_20.key] = (ltp / scan.high20 - 1.0) * 100.0
                    metrics[ScreenMetric.VS_MA_20.key] = (ltp / scan.ma20 - 1.0) * 100.0
                    metrics[ScreenMetric.RANGE_5.key] = scan.range5Pct
                    metrics[ScreenMetric.RUN_10.key] = scan.runPct
                }
                MetricScreener.Company(inst.symbol, inst.displayName, metrics)
            }

            val rules = s.workbench.screenRules.mapNotNull { it.toEngineRule() }
            val results = MetricScreener.screen(
                companies = companies,
                rules = rules,
                missing = if (s.screenMissingFails) {
                    MetricScreener.MissingPolicy.FAIL
                } else {
                    MetricScreener.MissingPolicy.SKIP
                },
                rankBy = s.screenRankBy?.let { listOf(it) } ?: emptyList()
            )

            _state.update { it.copy(screenResults = results, screenHasRun = true, isWorking = false) }
        }
    }

    // =====================================================================

    private fun persist(next: WorkbenchState) {
        _state.update { it.copy(workbench = next) }
        viewModelScope.launch { preferencesRepository.updateWorkbench(next) }
        refreshHarvest()
    }

    private fun today(): LocalDate = LocalDate.now(ZoneId.of("Asia/Kolkata"))

    private fun fmt2(v: Double): String = BigDecimal(v).setScale(2, RoundingMode.HALF_UP).toPlainString()
}

private fun SavedTaxLot.toEngineLot(): TaxLotTracker.Lot = TaxLotTracker.Lot(
    id = id,
    acquired = runCatching { LocalDate.parse(acquired) }
        .getOrElse { LocalDate.now(ZoneId.of("Asia/Kolkata")) },
    shares = shares,
    costPerShare = costPerShare.toBigDecimalOrNull() ?: BigDecimal.ZERO
)

/**
 * A saved rule is strings, because that is what the editor collects. Anything that cannot
 * be read back as a rule is dropped rather than guessed at.
 */
private fun SavedScreenRule.toEngineRule(): MetricScreener.Rule? {
    val operator = MetricScreener.Op.entries.firstOrNull { it.symbol == op } ?: return null
    val want: MetricScreener.Want = when (operator) {
        MetricScreener.Op.BETWEEN -> {
            if (values.size < 2) return null
            val lo = values[0].toDoubleOrNull() ?: return null
            val hi = values[1].toDoubleOrNull() ?: return null
            MetricScreener.Want.Range(lo, hi)
        }

        MetricScreener.Op.IN, MetricScreener.Op.NOT_IN -> {
            if (values.isEmpty()) return null
            MetricScreener.Want.OneOf(values)
        }

        else -> {
            val raw = values.firstOrNull() ?: return null
            val asNumber = raw.toDoubleOrNull()
            if (asNumber != null) MetricScreener.Want.Number(asNumber) else MetricScreener.Want.Text(raw)
        }
    }
    return MetricScreener.Rule(metric, operator, want)
}
