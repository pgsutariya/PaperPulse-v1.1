package com.example.ui.screens.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.example.data.ai.AiConnectionStatus
import com.example.data.ai.AiMarketRepository
import com.example.data.feed.live.FeedType
import com.example.data.feed.live.SegmentBadgeMode
import com.example.data.feed.live.SegmentFeedStatus
import com.example.data.repository.MarketDataRepository
import com.example.data.repository.TradingPreferencesRepository
import com.example.data.repository.TradingRepository
import com.example.data.signal.SignalRepository
import com.example.data.signal.SignalUiItem
import com.example.domain.engine.MarketClock
import com.example.domain.engine.PriceAwareExecutionPlan
import com.example.domain.engine.PriceAwareExecutionService
import com.example.domain.engine.RiskMath
import com.example.domain.model.AccountState
import com.example.domain.model.Instrument
import com.example.domain.model.MarketSegment
import com.example.domain.model.OrderPlacementRequest
import com.example.domain.model.OrderSide
import com.example.domain.strategy.PreTradeRiskGate
import com.example.domain.strategy.TraderRules
import com.example.domain.model.OrderType
import com.example.domain.model.ProductType
import com.example.domain.model.RiskInputMode
import com.example.domain.model.TrailMode
import com.example.domain.model.TradingConfig
import com.example.domain.model.ValidityType
import com.example.domain.signal.AiSignalSecondOpinion
import com.example.domain.signal.AiUiState
import com.example.domain.signal.SignalDirection
import com.example.domain.signal.Timeframe
import com.example.domain.signal.TradeSignal
import com.example.ui.navigation.InstrumentDetailRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.math.BigDecimal
import java.math.RoundingMode
import javax.inject.Inject

data class OrderTicketDraft(
    val side: OrderSide = OrderSide.BUY,
    val orderType: OrderType = OrderType.MARKET,
    val productType: ProductType = ProductType.INTRADAY,
    val quantityText: String = "1",
    val limitPriceText: String = "",
    val triggerPriceText: String = "",
    val slEnabled: Boolean = false,
    val slMode: RiskInputMode = RiskInputMode.PERCENT,
    val slValueText: String = "1.5",
    val tslEnabled: Boolean = false,
    val tslMode: TrailMode = TrailMode.PERCENT,
    val tslValueText: String = "0.8",
    val targetEnabled: Boolean = false,
    val targetMode: RiskInputMode = RiskInputMode.PERCENT,
    val targetValueText: String = "3.0",
    val trailingTargetEnabled: Boolean = false,
    val trailingTargetMode: TrailMode = TrailMode.PERCENT,
    val trailingTargetValueText: String = "0.8",
    val validityType: ValidityType = ValidityType.DAY,
    val gttDaysText: String = "7",
    val gtdDateMillis: Long = System.currentTimeMillis() + 86_400_000L * 3L,
    val signalTargetEntryPrice: BigDecimal? = null,
    val priceAwareGuardEnabled: Boolean = true,
    val slippageTolerancePercent: Double = PriceAwareExecutionService.DEFAULT_SLIPPAGE_TOLERANCE_PERCENT,
    val initializedDefaults: Boolean = false
)

data class InstrumentDetailUiState(
    val instrument: Instrument? = null,
    val accountState: AccountState? = null,
    val config: TradingConfig = TradingConfig(),
    val draft: OrderTicketDraft = OrderTicketDraft(),
    val priceAwarePlan: PriceAwareExecutionPlan? = null,
    val isSubmitting: Boolean = false,
    val feedBadge: SegmentBadgeMode = SegmentBadgeMode.SIMULATED,
    val riskControlsSuspended: Boolean = false,
    val liveStatusMessage: String = "",
    val symbolSignals: List<SignalUiItem> = emptyList(),
    val prefilledFromSignal: Boolean = false,
    val aiAuditState: AiUiState<AiSignalSecondOpinion> = AiUiState.Idle,
    val aiConnectionStatus: AiConnectionStatus = AiConnectionStatus(),
    val orderFeedbackMessage: String? = null,
    val orderFeedbackIsError: Boolean = false,
    /**
     * Advisory pre-trade check from [com.example.domain.strategy.PreTradeRiskGate]
     * (ported from the trader skill packs). Null until there is enough of a ticket to judge.
     */
    val riskCheck: PreTradeRiskGate.Result? = null
)

sealed interface DetailEvent {
    data class ShowMessage(val message: String, val isError: Boolean = false) : DetailEvent
    data class OrderPlaced(val message: String, val goToOrdersTab: Boolean) : DetailEvent
}

@HiltViewModel
class InstrumentDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val marketDataRepository: MarketDataRepository,
    private val tradingRepository: TradingRepository,
    private val signalRepository: SignalRepository,
    private val aiMarketRepository: AiMarketRepository,
    prefsRepository: TradingPreferencesRepository
) : ViewModel() {

    private val route = savedStateHandle.toRoute<InstrumentDetailRoute>()
    private val symbol: String = route.symbol
    private val hasSignalPrefill: Boolean = route.prefillSl != null || route.prefillTarget != null || route.prefillSide != null

    private val _draft = MutableStateFlow(OrderTicketDraft())
    private val _isSubmitting = MutableStateFlow(false)
    private val _aiAuditState = MutableStateFlow<AiUiState<AiSignalSecondOpinion>>(AiUiState.Idle)
    private val _orderFeedback = MutableStateFlow<Pair<String, Boolean>?>(null)
    private val _events = MutableSharedFlow<DetailEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<DetailEvent> = _events.asSharedFlow()

    private val feedHealthFlow = combine(
        marketDataRepository.segmentStatuses,
        marketDataRepository.riskControlsSuspended,
        marketDataRepository.liveStatusMessage,
        signalRepository.observeSignalsForSymbol(symbol)
    ) { statuses, suspended, msg, sigs -> Quadruple(statuses, suspended, msg, sigs) }

    private val aiCombinedFlow = combine(
        _aiAuditState,
        aiMarketRepository.connectionStatus
    ) { audit, status -> audit to status }

    val uiState: StateFlow<InstrumentDetailUiState> = combine(
        marketDataRepository.observeInstrument(symbol),
        tradingRepository.accountState,
        prefsRepository.configFlow,
        _draft,
        _isSubmitting,
        feedHealthFlow,
        aiCombinedFlow,
        _orderFeedback
    ) { values ->
        val inst = values[0] as Instrument?
        val account = values[1] as AccountState?
        val config = values[2] as TradingConfig
        val draft = values[3] as OrderTicketDraft
        val submitting = values[4] as Boolean
        @Suppress("UNCHECKED_CAST")
        val health = values[5] as Quadruple<Map<MarketSegment, SegmentFeedStatus>, Boolean, String, List<SignalUiItem>>
        @Suppress("UNCHECKED_CAST")
        val aiPair = values[6] as Pair<AiUiState<AiSignalSecondOpinion>, AiConnectionStatus>
        @Suppress("UNCHECKED_CAST")
        val feedback = values[7] as Pair<String, Boolean>?

        if (inst != null && !draft.initializedDefaults) {
            val qtyDefault = inst.lotSize.coerceAtLeast(1).toString()
            val preSide = route.prefillSide?.let { runCatching { OrderSide.valueOf(it) }.getOrNull() } ?: draft.side
            val preProduct = route.prefillProduct?.let { runCatching { ProductType.valueOf(it) }.getOrNull() }
                ?.takeIf { it != ProductType.CARRY_FORWARD || inst.supportsCarryForward }
                ?: draft.productType
            val preValidity = route.prefillValidity?.let { runCatching { ValidityType.valueOf(it) }.getOrNull() }
                ?: draft.validityType
            val preSl = route.prefillSl
            val preTarget = route.prefillTarget
            val preEntryDecimal = route.prefillEntry?.toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO }
            val defaultOrderType = if (hasSignalPrefill) OrderType.BRACKET else draft.orderType
            val preLimit = when {
                route.prefillEntry != null -> route.prefillEntry
                defaultOrderType == OrderType.MARKET || defaultOrderType == OrderType.BRACKET -> ""
                else -> inst.ltp.toPlainString()
            }
            val preTrigger = route.prefillEntry ?: inst.ltp.toPlainString()

            _draft.update {
                it.copy(
                    side = preSide,
                    orderType = defaultOrderType,
                    productType = preProduct,
                    validityType = preValidity,
                    quantityText = route.prefillQuantity
                        ?.toIntOrNull()
                        ?.takeIf { qty -> qty > 0 }
                        ?.toString()
                        ?: qtyDefault,
                    limitPriceText = preLimit,
                    triggerPriceText = preTrigger,
                    slEnabled = preSl != null || it.slEnabled,
                    slMode = if (preSl != null) RiskInputMode.PRICE else it.slMode,
                    slValueText = preSl ?: it.slValueText,
                    targetEnabled = preTarget != null || it.targetEnabled,
                    targetMode = if (preTarget != null) RiskInputMode.PRICE else it.targetMode,
                    targetValueText = preTarget ?: it.targetValueText,
                    signalTargetEntryPrice = preEntryDecimal,
                    priceAwareGuardEnabled = preEntryDecimal != null || it.priceAwareGuardEnabled,
                    initializedDefaults = true
                )
            }
        }
        val livePlan = inst?.let { liveInst ->
            val targetEntry = draft.signalTargetEntryPrice
                ?: draft.limitPriceText.toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO && draft.orderType in setOf(OrderType.LIMIT, OrderType.BRACKET) }
            val qty = (draft.quantityText.toIntOrNull() ?: liveInst.lotSize).coerceAtLeast(1)
            PriceAwareExecutionService.evaluate(
                instrument = liveInst,
                side = draft.side,
                quantity = qty,
                requestedOrderType = draft.orderType,
                targetEntryPrice = targetEntry,
                userLimitPrice = draft.limitPriceText.toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO },
                userTriggerPrice = draft.triggerPriceText.toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO },
                slippageTolerancePercent = draft.slippageTolerancePercent,
                priceAwareGuardEnabled = draft.priceAwareGuardEnabled && draft.signalTargetEntryPrice != null
            )
        }
        val segStatus = inst?.let { health.first[it.segment] }
        val segBadge = segStatus?.badge ?: SegmentBadgeMode.SIMULATED
        val statusMsg = segStatus?.detail?.takeIf { segBadge == SegmentBadgeMode.SUSPENDED } ?: health.third
        val riskCheck = if (inst != null && account != null) {
            buildRiskCheck(inst, draft, account)
        } else {
            null
        }
        InstrumentDetailUiState(
            instrument = inst,
            accountState = account,
            config = config,
            draft = draft,
            priceAwarePlan = livePlan,
            isSubmitting = submitting,
            feedBadge = segBadge,
            riskControlsSuspended = health.second && segBadge == SegmentBadgeMode.SUSPENDED,
            liveStatusMessage = statusMsg,
            symbolSignals = health.fourth,
            prefilledFromSignal = hasSignalPrefill || draft.signalTargetEntryPrice != null,
            aiAuditState = aiPair.first,
            aiConnectionStatus = aiPair.second,
            orderFeedbackMessage = feedback?.first,
            orderFeedbackIsError = feedback?.second ?: false,
            riskCheck = riskCheck
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = InstrumentDetailUiState()
    )

    fun switchAiModel(modelName: String) {
        aiMarketRepository.switchActiveModel(modelName)
    }

    /**
     * Run the ported pre-trade gate over the ticket as it currently stands.
     *
     * Advisory only - it reports which of the trader's own rules the order would break,
     * it does not stop the order. The engine's budget-based limits are scaled to the paper
     * account so they mean something without the trader configuring anything first.
     */
    private fun buildRiskCheck(
        inst: Instrument,
        d: OrderTicketDraft,
        account: AccountState
    ): PreTradeRiskGate.Result? {
        val quantity = d.quantityText.toIntOrNull() ?: return null
        if (quantity <= 0) return null
        val entry = resolveEntry(inst, d)
        if (entry <= BigDecimal.ZERO) return null

        val isCrypto = inst.segment == MarketSegment.CRYPTO
        val capital = if (isCrypto) account.cryptoVirtualCapital else account.virtualCapital
        val cash = if (isCrypto) account.cryptoAvailableMargin else account.availableMargin
        if (capital <= BigDecimal.ZERO) return null

        // Percentage limits come from the rule-book; the rupee ones scale off the paper
        // capital so a Rs 10,00,000 account is not judged against a Rs 1,00,000 default.
        val rules = TraderRules(
            budget = capital,
            maxOrderInr = capital.multiply(BigDecimal("0.5")),
            minOrderInr = BigDecimal.ZERO,
            maxLossPerTradeInr = capital.multiply(BigDecimal("0.01")),
            maxDailyLossInr = capital.multiply(BigDecimal("0.03")),
            maxTradesPerDay = Int.MAX_VALUE,
            maxOrdersPerDay = Int.MAX_VALUE,
            requireStop = false,
            allowShort = true,
            noEntriesBefore = LocalTime.MIDNIGHT,
            noEntriesAfter = LocalTime.of(23, 59)
        )

        return PreTradeRiskGate.check(
            order = PreTradeRiskGate.ProposedOrder(
                symbol = inst.symbol,
                isBuy = d.side == OrderSide.BUY,
                quantity = quantity,
                entry = entry,
                stop = resolveStopPrice(entry, d),
                target = resolveTargetPrice(entry, d),
                isEntry = true
            ),
            rules = rules,
            account = PreTradeRiskGate.AccountSnapshot(cash = cash, equity = capital),
            livePrice = inst.ltp
        )
    }

    /** The ticket's entry price: the typed limit when there is one, else the live price. */
    private fun resolveEntry(inst: Instrument, d: OrderTicketDraft): BigDecimal =
        d.limitPriceText.toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO } ?: inst.ltp

    /** The ticket's stop in absolute price terms, or null when no stop is attached. */
    private fun resolveStopPrice(entry: BigDecimal, d: OrderTicketDraft): BigDecimal? {
        if (!d.slEnabled) return null
        val raw = d.slValueText.toBigDecimalOrNull() ?: return null
        if (raw <= BigDecimal.ZERO) return null
        if (d.slMode == RiskInputMode.PRICE) return raw
        val factor = raw.divide(BigDecimal("100"), 6, RoundingMode.HALF_UP)
        return if (d.side == OrderSide.BUY) {
            entry.multiply(BigDecimal.ONE.subtract(factor))
        } else {
            entry.multiply(BigDecimal.ONE.add(factor))
        }
    }

    /** The ticket's target in absolute price terms, or null when no target is attached. */
    private fun resolveTargetPrice(entry: BigDecimal, d: OrderTicketDraft): BigDecimal? {
        if (!d.targetEnabled) return null
        val raw = d.targetValueText.toBigDecimalOrNull() ?: return null
        if (raw <= BigDecimal.ZERO) return null
        if (d.targetMode == RiskInputMode.PRICE) return raw
        val factor = raw.divide(BigDecimal("100"), 6, RoundingMode.HALF_UP)
        return if (d.side == OrderSide.BUY) {
            entry.multiply(BigDecimal.ONE.add(factor))
        } else {
            entry.multiply(BigDecimal.ONE.subtract(factor))
        }
    }

    fun runAiSetupAudit() {
        val current = uiState.value
        val inst = current.instrument ?: return
        val d = current.draft
        val entry = resolveEntry(inst, d)
        val sl = resolveStopPrice(entry, d)
        val target = resolveTargetPrice(entry, d)

        viewModelScope.launch {
            _aiAuditState.value = AiUiState.Loading
            val candles = signalRepository.getRecentCandles(inst.symbol, Timeframe.M5)
            val result = aiMarketRepository.analyzeInstrumentSetup(
                instrument = inst,
                side = d.side,
                productType = d.productType,
                entryPrice = entry,
                slPrice = sl,
                targetPrice = target,
                activeSignals = current.symbolSignals.map { it.signal },
                recentCandles = candles
            )
            _aiAuditState.value = result.fold(
                onSuccess = { AiUiState.Success(it) },
                onFailure = { AiUiState.Error(it.message ?: "AI setup audit failed.") }
            )
        }
    }

    fun applySignalToTicket(signal: TradeSignal, useTarget2: Boolean = false) {
        val inst = uiState.value.instrument
        val side = if (signal.direction == SignalDirection.LONG) OrderSide.BUY else OrderSide.SELL
        val rawProduct = if (signal.strategy.prefersCarryForward) ProductType.CARRY_FORWARD else ProductType.INTRADAY
        val product = if (rawProduct == ProductType.CARRY_FORWARD && inst?.supportsCarryForward == false) {
            ProductType.INTRADAY
        } else {
            rawProduct
        }
        val validity = if (product == ProductType.CARRY_FORWARD) ValidityType.GTT else ValidityType.DAY
        val target = if (useTarget2) signal.target2 else signal.target1
        _orderFeedback.value = null
        _draft.update { d ->
            d.copy(
                side = side,
                orderType = OrderType.BRACKET,
                productType = product,
                validityType = validity,
                limitPriceText = signal.entryPrice.toPlainString(),
                triggerPriceText = signal.entryPrice.toPlainString(),
                signalTargetEntryPrice = signal.entryPrice,
                priceAwareGuardEnabled = true,
                slEnabled = true,
                slMode = RiskInputMode.PRICE,
                slValueText = signal.stopLoss.toPlainString(),
                targetEnabled = true,
                targetMode = RiskInputMode.PRICE,
                targetValueText = target.toPlainString()
            )
        }
        _events.tryEmit(DetailEvent.ShowMessage("Applied ${signal.strategy.shortLabel} signal @ Entry ${RiskMath.formatPrice(signal.entryPrice, signal.segment)} — Price-Aware Guard active"))
    }

    fun togglePriceAwareGuard(enabled: Boolean) {
        _orderFeedback.value = null
        _draft.update { it.copy(priceAwareGuardEnabled = enabled) }
    }

    fun updateSlippageTolerance(percent: Double) {
        _orderFeedback.value = null
        _draft.update { it.copy(slippageTolerancePercent = percent.coerceIn(0.10, 2.50)) }
    }

    fun clearSignalTargetEntry() {
        _orderFeedback.value = null
        _draft.update { it.copy(signalTargetEntryPrice = null, priceAwareGuardEnabled = false) }
    }

    fun setSide(side: OrderSide) {
        _orderFeedback.value = null
        _draft.update { it.copy(side = side) }
    }

    fun setOrderType(type: OrderType) {
        _orderFeedback.value = null
        val ltpText = uiState.value.instrument?.ltp?.takeIf { it > BigDecimal.ZERO }?.toPlainString().orEmpty()
        _draft.update { d ->
            val enableBracketLegs = type == OrderType.BRACKET
            val nextLimit = when (type) {
                OrderType.BRACKET -> d.signalTargetEntryPrice?.toPlainString() ?: ""
                OrderType.LIMIT, OrderType.SL_L, OrderType.TGT_L -> d.limitPriceText.ifBlank { d.signalTargetEntryPrice?.toPlainString() ?: ltpText }
                else -> d.limitPriceText
            }
            val nextTrigger = when (type) {
                OrderType.SL_M, OrderType.SL_L, OrderType.TGT_M, OrderType.TGT_L -> d.triggerPriceText.ifBlank { d.signalTargetEntryPrice?.toPlainString() ?: ltpText }
                else -> d.triggerPriceText
            }
            d.copy(
                orderType = type,
                limitPriceText = nextLimit,
                triggerPriceText = nextTrigger,
                slEnabled = if (enableBracketLegs) true else d.slEnabled,
                targetEnabled = if (enableBracketLegs) true else d.targetEnabled
            )
        }
    }

    fun setProductType(productType: ProductType) {
        _orderFeedback.value = null
        _draft.update { d ->
            val nextValidity = if (productType == ProductType.CARRY_FORWARD && d.validityType == ValidityType.DAY) {
                ValidityType.GTT
            } else {
                d.validityType
            }
            d.copy(productType = productType, validityType = nextValidity)
        }
    }

    fun stepQuantity(deltaLots: Int) {
        _orderFeedback.value = null
        val lot = uiState.value.instrument?.lotSize ?: 1
        val current = _draft.value.quantityText.toIntOrNull() ?: lot
        val next = (current + deltaLots * lot).coerceAtLeast(lot)
        _draft.update { it.copy(quantityText = next.toString()) }
    }

    fun updateQuantity(text: String) {
        _orderFeedback.value = null
        _draft.update { it.copy(quantityText = text.filter { ch -> ch.isDigit() }) }
    }

    fun updateLimitPrice(text: String) {
        _orderFeedback.value = null
        _draft.update { it.copy(limitPriceText = sanitizeDecimal(text)) }
    }

    fun updateTriggerPrice(text: String) {
        _orderFeedback.value = null
        _draft.update { it.copy(triggerPriceText = sanitizeDecimal(text)) }
    }

    fun selectLimitPriceFromDepth(price: BigDecimal, tappedBookSide: OrderSide) {
        val inst = uiState.value.instrument ?: return
        val rounded = RiskMath.roundToTick(price, inst.tickSize)
        _orderFeedback.value = null
        _draft.update { d ->
            val nextType = if (d.orderType == OrderType.MARKET) OrderType.LIMIT else d.orderType
            d.copy(
                orderType = nextType,
                limitPriceText = rounded.toPlainString()
            )
        }
        val sideTag = if (tappedBookSide == OrderSide.BUY) "Bid" else "Ask"
        _events.tryEmit(
            DetailEvent.ShowMessage(
                "Limit price set to ${RiskMath.formatPrice(rounded, inst.segment)} from $sideTag depth"
            )
        )
    }

    fun toggleSl(enabled: Boolean) {
        val ltp = uiState.value.instrument?.ltp
        _draft.update { d ->
            val defaultVal = if (d.slMode == RiskInputMode.PRICE && ltp != null) {
                val factor = if (d.side == OrderSide.BUY) BigDecimal("0.985") else BigDecimal("1.015")
                ltp.multiply(factor).setScale(2, RoundingMode.HALF_UP).toPlainString()
            } else {
                d.slValueText.ifBlank { "1.5" }
            }
            d.copy(
                slEnabled = enabled,
                slValueText = defaultVal,
                tslEnabled = if (!enabled) false else d.tslEnabled
            )
        }
    }

    fun setSlMode(mode: RiskInputMode) {
        val ltp = uiState.value.instrument?.ltp
        _draft.update { d ->
            val nextVal = when (mode) {
                RiskInputMode.PERCENT -> "1.5"
                RiskInputMode.PRICE -> if (ltp != null) {
                    val factor = if (d.side == OrderSide.BUY) BigDecimal("0.985") else BigDecimal("1.015")
                    ltp.multiply(factor).setScale(2, RoundingMode.HALF_UP).toPlainString()
                } else ""
            }
            d.copy(slMode = mode, slValueText = nextVal)
        }
    }

    fun updateSlValue(text: String) = _draft.update { it.copy(slValueText = sanitizeDecimal(text)) }
    fun toggleTsl(enabled: Boolean) = _draft.update { it.copy(tslEnabled = enabled) }
    fun setTslMode(mode: TrailMode) = _draft.update {
        it.copy(tslMode = mode, tslValueText = if (mode == TrailMode.PERCENT) "0.8" else "5.0")
    }
    fun updateTslValue(text: String) = _draft.update { it.copy(tslValueText = sanitizeDecimal(text)) }

    fun toggleTarget(enabled: Boolean) {
        val ltp = uiState.value.instrument?.ltp
        _draft.update { d ->
            val defaultVal = if (d.targetMode == RiskInputMode.PRICE && ltp != null) {
                val factor = if (d.side == OrderSide.BUY) BigDecimal("1.03") else BigDecimal("0.97")
                ltp.multiply(factor).setScale(2, RoundingMode.HALF_UP).toPlainString()
            } else {
                d.targetValueText.ifBlank { "3.0" }
            }
            d.copy(
                targetEnabled = enabled,
                targetValueText = defaultVal,
                trailingTargetEnabled = if (!enabled) false else d.trailingTargetEnabled
            )
        }
    }

    fun setTargetMode(mode: RiskInputMode) {
        val ltp = uiState.value.instrument?.ltp
        _draft.update { d ->
            val nextVal = when (mode) {
                RiskInputMode.PERCENT -> "3.0"
                RiskInputMode.PRICE -> if (ltp != null) {
                    val factor = if (d.side == OrderSide.BUY) BigDecimal("1.03") else BigDecimal("0.97")
                    ltp.multiply(factor).setScale(2, RoundingMode.HALF_UP).toPlainString()
                } else ""
            }
            d.copy(targetMode = mode, targetValueText = nextVal)
        }
    }

    fun updateTargetValue(text: String) = _draft.update { it.copy(targetValueText = sanitizeDecimal(text)) }
    fun toggleTrailingTarget(enabled: Boolean) = _draft.update { it.copy(trailingTargetEnabled = enabled) }
    fun setTrailingTargetMode(mode: TrailMode) = _draft.update {
        it.copy(trailingTargetMode = mode, trailingTargetValueText = if (mode == TrailMode.PERCENT) "0.8" else "5.0")
    }
    fun updateTrailingTargetValue(text: String) = _draft.update { it.copy(trailingTargetValueText = sanitizeDecimal(text)) }

    fun setValidityType(type: ValidityType) = _draft.update { it.copy(validityType = type) }
    fun updateGttDays(text: String) = _draft.update { it.copy(gttDaysText = text.filter { ch -> ch.isDigit() }) }
    fun updateGtdDateMillis(millis: Long) = _draft.update { it.copy(gtdDateMillis = millis) }

    fun nudgePrice(percentDelta: BigDecimal) {
        viewModelScope.launch {
            marketDataRepository.nudgePrice(symbol, percentDelta)
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

    private fun rejectInput(message: String) {
        _orderFeedback.value = message to true
        _events.tryEmit(DetailEvent.ShowMessage(message, isError = true))
    }

    fun placeOrder() {
        val inst = uiState.value.instrument
        if (inst == null) {
            rejectInput("Instrument data is still loading. Please try again.")
            return
        }
        if (inst.ltp <= BigDecimal.ZERO) {
            rejectInput("Waiting for valid quote (LTP) before placing paper order.")
            return
        }
        val d = _draft.value
        val qty = d.quantityText.toIntOrNull()
        if (qty == null || qty <= 0) {
            rejectInput("Enter a valid quantity")
            return
        }

        val needsLimit = d.orderType in setOf(OrderType.LIMIT, OrderType.SL_L, OrderType.TGT_L)
        val needsTrigger = d.orderType in setOf(OrderType.SL_M, OrderType.SL_L, OrderType.TGT_M, OrderType.TGT_L)

        val limitPrice = d.limitPriceText.toBigDecimalOrNull()
        val triggerPrice = d.triggerPriceText.toBigDecimalOrNull()

        if (needsLimit && (limitPrice == null || limitPrice <= BigDecimal.ZERO)) {
            rejectInput("Enter a valid limit price")
            return
        }
        if (needsTrigger && (triggerPrice == null || triggerPrice <= BigDecimal.ZERO)) {
            rejectInput("Enter a valid trigger price")
            return
        }

        val slVal = if (d.slEnabled) d.slValueText.toBigDecimalOrNull() else null
        if (d.slEnabled && (slVal == null || slVal <= BigDecimal.ZERO)) {
            rejectInput("Enter a valid Stop Loss value")
            return
        }

        val targetVal = if (d.targetEnabled) d.targetValueText.toBigDecimalOrNull() else null
        if (d.targetEnabled && (targetVal == null || targetVal <= BigDecimal.ZERO)) {
            rejectInput("Enter a valid Target value")
            return
        }

        val tslVal = if (d.slEnabled && d.tslEnabled) d.tslValueText.toBigDecimalOrNull() else null
        if (d.slEnabled && d.tslEnabled && (tslVal == null || tslVal <= BigDecimal.ZERO)) {
            rejectInput("Enter a valid Trailing SL amount")
            return
        }

        val ttVal = if (d.targetEnabled && d.trailingTargetEnabled) d.trailingTargetValueText.toBigDecimalOrNull() else null
        if (d.targetEnabled && d.trailingTargetEnabled && (ttVal == null || ttVal <= BigDecimal.ZERO)) {
            rejectInput("Enter a valid Trailing Target amount")
            return
        }

        val gttDays = (d.gttDaysText.toIntOrNull() ?: 7).coerceIn(1, 365)

        val bracketLimit = when (d.orderType) {
            OrderType.BRACKET -> d.limitPriceText.toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO }
            OrderType.MARKET -> null
            else -> limitPrice
        }
        val effectiveTriggerPrice = if (needsTrigger) triggerPrice else null

        val usePriceAwareGuard = d.priceAwareGuardEnabled && d.signalTargetEntryPrice != null
        val previewPlan = if (usePriceAwareGuard) {
            PriceAwareExecutionService.evaluate(
                instrument = inst,
                side = d.side,
                quantity = qty,
                requestedOrderType = d.orderType,
                targetEntryPrice = d.signalTargetEntryPrice,
                userLimitPrice = bracketLimit,
                userTriggerPrice = effectiveTriggerPrice,
                slippageTolerancePercent = d.slippageTolerancePercent,
                priceAwareGuardEnabled = true
            )
        } else null

        val finalOrderType = previewPlan?.effectiveOrderType ?: d.orderType
        val finalLimit = previewPlan?.effectiveLimitPrice ?: bracketLimit
        val isImmediateFill = finalOrderType == OrderType.MARKET || (finalOrderType == OrderType.BRACKET && finalLimit == null)

        viewModelScope.launch {
            _orderFeedback.value = null
            _isSubmitting.value = true
            try {
                val result = tradingRepository.placeOrder(
                    OrderPlacementRequest(
                        symbol = inst.symbol,
                        side = d.side,
                        orderType = d.orderType,
                        productType = d.productType,
                        quantity = qty,
                        limitPrice = bracketLimit,
                        triggerPrice = effectiveTriggerPrice,
                        slEnabled = d.slEnabled,
                        slMode = d.slMode,
                        slValue = slVal,
                        targetEnabled = d.targetEnabled,
                        targetMode = d.targetMode,
                        targetValue = targetVal,
                        tslEnabled = d.tslEnabled,
                        tslMode = d.tslMode,
                        tslValue = tslVal,
                        trailingTargetEnabled = d.trailingTargetEnabled,
                        trailingTargetMode = d.trailingTargetMode,
                        trailingTargetValue = ttVal,
                        validityType = d.validityType,
                        gttDays = gttDays,
                        gtdDateMillis = d.gtdDateMillis,
                        targetEntryPrice = d.signalTargetEntryPrice,
                        slippageTolerancePercent = d.slippageTolerancePercent,
                        priceAwareGuardEnabled = usePriceAwareGuard
                    )
                )
                result.onSuccess { msg ->
                    _orderFeedback.value = msg to false
                    _events.emit(DetailEvent.OrderPlaced(message = msg, goToOrdersTab = !isImmediateFill))
                }.onFailure { err ->
                    val errMsg = err.message?.takeIf { it.isNotBlank() } ?: "Order rejected"
                    _orderFeedback.value = errMsg to true
                    _events.emit(DetailEvent.ShowMessage(errMsg, isError = true))
                }
            } catch (t: Throwable) {
                val errMsg = t.message?.takeIf { it.isNotBlank() } ?: "Order failed (${t.javaClass.simpleName})"
                _orderFeedback.value = errMsg to true
                _events.emit(DetailEvent.ShowMessage(errMsg, isError = true))
            } finally {
                _isSubmitting.value = false
            }
        }
    }

    private fun sanitizeDecimal(input: String): String {
        val filtered = input.filter { it.isDigit() || it == '.' }
        val firstDot = filtered.indexOf('.')
        return if (firstDot == -1) filtered else {
            filtered.substring(0, firstDot + 1) + filtered.substring(firstDot + 1).replace(".", "")
        }
    }

    private data class Quadruple<A, B, C, D>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D
    )
}
