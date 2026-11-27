package com.example.data.engine

import androidx.room.withTransaction
import com.example.data.local.InstrumentDao
import com.example.data.local.InstrumentEntity
import com.example.data.local.OrderDao
import com.example.data.local.OrderEntity
import com.example.data.local.PaperPulseDatabase
import com.example.data.local.PositionDao
import com.example.data.local.PositionEntity
import com.example.data.repository.MarketDataRepository
import com.example.data.repository.TradingPreferencesRepository
import com.example.domain.engine.MarketClock
import com.example.domain.engine.RiskMath
import com.example.domain.model.ExitReason
import com.example.domain.model.OrderSide
import com.example.domain.model.OrderStatus
import com.example.domain.model.OrderType
import com.example.domain.model.ProductType
import com.example.domain.model.TradingConfig
import com.example.domain.model.ValidityState
import com.example.domain.model.ValidityType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.math.BigDecimal
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistent paper-trading execution engine.
 * Evaluates on every LTP tick and 1s clock heartbeat:
 * 1. Pending entry orders (LIMIT / SL-M / SL-L / TGT-M / TGT-L / BRACKET)
 * 2. SL triggers
 * 3. Target triggers
 * 4. Trailing Stop Loss ratcheting (never retraces)
 * 5. Trailing Target arming, ratcheting, and pullback exit
 * 6. DAY / GTT / GTD validity expiry
 * 7. Intraday 15:15 IST auto square-off
 */
@Singleton
class ExecutionEngine private constructor(
    private val database: PaperPulseDatabase,
    private val instrumentDao: InstrumentDao,
    private val orderDao: OrderDao,
    private val positionDao: PositionDao,
    private val ledgerService: TradingLedgerService,
    private val marketDataRepository: MarketDataRepository,
    private val prefsRepository: TradingPreferencesRepository,
    private val appScope: CoroutineScope,
    private val notificationManager: com.example.data.notifications.TradeNotificationManager?,
    @Suppress("UNUSED_PARAMETER") internalTag: Boolean
) {
    @Inject
    constructor(
        database: PaperPulseDatabase,
        instrumentDao: InstrumentDao,
        orderDao: OrderDao,
        positionDao: PositionDao,
        ledgerService: TradingLedgerService,
        marketDataRepository: MarketDataRepository,
        prefsRepository: TradingPreferencesRepository,
        appScope: CoroutineScope,
        notificationManager: com.example.data.notifications.TradeNotificationManager
    ) : this(
        database, instrumentDao, orderDao, positionDao, ledgerService,
        marketDataRepository, prefsRepository, appScope,
        notificationManager, true
    )

    constructor(
        database: PaperPulseDatabase,
        instrumentDao: InstrumentDao,
        orderDao: OrderDao,
        positionDao: PositionDao,
        ledgerService: TradingLedgerService,
        marketDataRepository: MarketDataRepository,
        prefsRepository: TradingPreferencesRepository,
        appScope: CoroutineScope
    ) : this(
        database, instrumentDao, orderDao, positionDao, ledgerService,
        marketDataRepository, prefsRepository, appScope, null, false
    )

    private val evaluationMutex = Mutex()

    private val _engineEvents = MutableSharedFlow<String>(extraBufferCapacity = 32)
    val engineEvents: SharedFlow<String> = _engineEvents.asSharedFlow()

    @Volatile
    private var started = false

    suspend fun <T> withEngineLock(block: suspend () -> T): T = evaluationMutex.withLock { block() }

    fun start() {
        if (started) return
        started = true

        // Self-heal any legacy open positions with orphaned OCO badges or expired protective legs
        appScope.launch(Dispatchers.IO) {
            healOrphanedRiskState(System.currentTimeMillis())
        }

        // Tick-driven evaluation
        appScope.launch(Dispatchers.IO) {
            marketDataRepository.ticks.collect { tick ->
                evaluateTick(tick.symbol, tick.ltp, tick.timestampMillis)
            }
        }

        // 1-second heartbeat for validity expiry & 15:15 Eq/Fut / 15:40 Options intraday square-off
        appScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(1000L)
                evaluateTimeEvents(System.currentTimeMillis())
            }
        }
    }

    suspend fun healOrphanedRiskState(nowMillis: Long) {
        evaluationMutex.withLock {
            val freshHorizon = nowMillis + 30L * 86_400_000L
            val openPositions = positionDao.getAllOnce()
            for (pos in openPositions) {
                database.withTransaction {
                    val current = positionDao.getById(pos.id) ?: return@withTransaction
                    val exitSide = if (current.side == OrderSide.BUY) OrderSide.SELL else OrderSide.BUY
                    var slOrderId = current.slOrderId
                    var targetOrderId = current.targetOrderId
                    var changed = false

                    if (current.activeSlPrice != null) {
                        val existingSl = slOrderId?.let { orderDao.getById(it) }
                        if (existingSl == null || existingSl.status != OrderStatus.PENDING) {
                            val restoredId = java.util.UUID.randomUUID().toString()
                            orderDao.upsert(
                                OrderEntity(
                                    id = restoredId,
                                    symbol = current.symbol,
                                    segment = current.segment,
                                    instrumentType = current.instrumentType,
                                    side = exitSide,
                                    orderType = OrderType.SL_M,
                                    productType = current.productType,
                                    quantity = current.quantity,
                                    limitPrice = null,
                                    triggerPrice = current.activeSlPrice,
                                    filledPrice = null,
                                    status = OrderStatus.PENDING,
                                    validityType = current.validityType,
                                    gttDays = current.gttDays,
                                    expiresAtMillis = maxOf(current.expiresAtMillis, freshHorizon),
                                    validityState = ValidityState.ACTIVE,
                                    ocoGroupId = current.ocoGroupId,
                                    parentOrderId = null,
                                    legRole = "SL",
                                    attachedSlMode = null,
                                    attachedSlValue = null,
                                    attachedTargetMode = null,
                                    attachedTargetValue = null,
                                    attachedTslEnabled = current.isTslEnabled,
                                    attachedTslMode = current.tslTrailMode,
                                    attachedTslValue = current.tslTrailValue,
                                    attachedTrailingTargetEnabled = false,
                                    attachedTrailingTargetMode = null,
                                    attachedTrailingTargetValue = null,
                                    statusReason = if (current.isTslEnabled) "Trailing SL Armed" else "Protective SL",
                                    createdAtMillis = nowMillis,
                                    updatedAtMillis = nowMillis,
                                    lotSize = current.lotSize
                                )
                            )
                            slOrderId = restoredId
                            changed = true
                        } else if (existingSl.expiresAtMillis < nowMillis + 86_400_000L) {
                            orderDao.upsert(existingSl.copy(expiresAtMillis = freshHorizon, updatedAtMillis = nowMillis))
                        }
                    }

                    if (current.activeTargetPrice != null) {
                        val existingTgt = targetOrderId?.let { orderDao.getById(it) }
                        if (existingTgt == null || existingTgt.status != OrderStatus.PENDING) {
                            val restoredId = java.util.UUID.randomUUID().toString()
                            orderDao.upsert(
                                OrderEntity(
                                    id = restoredId,
                                    symbol = current.symbol,
                                    segment = current.segment,
                                    instrumentType = current.instrumentType,
                                    side = exitSide,
                                    orderType = OrderType.TGT_M,
                                    productType = current.productType,
                                    quantity = current.quantity,
                                    limitPrice = null,
                                    triggerPrice = current.activeTargetPrice,
                                    filledPrice = null,
                                    status = OrderStatus.PENDING,
                                    validityType = current.validityType,
                                    gttDays = current.gttDays,
                                    expiresAtMillis = maxOf(current.expiresAtMillis, freshHorizon),
                                    validityState = ValidityState.ACTIVE,
                                    ocoGroupId = current.ocoGroupId,
                                    parentOrderId = null,
                                    legRole = "TARGET",
                                    attachedSlMode = null,
                                    attachedSlValue = null,
                                    attachedTargetMode = null,
                                    attachedTargetValue = null,
                                    attachedTslEnabled = false,
                                    attachedTslMode = null,
                                    attachedTslValue = null,
                                    attachedTrailingTargetEnabled = current.isTrailingTargetEnabled,
                                    attachedTrailingTargetMode = current.trailingTargetTrailMode,
                                    attachedTrailingTargetValue = current.trailingTargetTrailValue,
                                    statusReason = if (current.isTrailingTargetEnabled) "Trailing Target Armed" else "Target Order",
                                    createdAtMillis = nowMillis,
                                    updatedAtMillis = nowMillis,
                                    lotSize = current.lotSize
                                )
                            )
                            targetOrderId = restoredId
                            changed = true
                        } else if (existingTgt.expiresAtMillis < nowMillis + 86_400_000L) {
                            orderDao.upsert(existingTgt.copy(expiresAtMillis = freshHorizon, updatedAtMillis = nowMillis))
                        }
                    }

                    val validOcoGroupId = if (current.activeSlPrice != null && current.activeTargetPrice != null) {
                        current.ocoGroupId
                    } else {
                        null
                    }
                    if (validOcoGroupId != current.ocoGroupId || current.expiresAtMillis < nowMillis + 86_400_000L) {
                        changed = true
                    }
                    if (changed) {
                        positionDao.upsert(
                            current.copy(
                                slOrderId = slOrderId,
                                targetOrderId = targetOrderId,
                                ocoGroupId = validOcoGroupId,
                                expiresAtMillis = maxOf(current.expiresAtMillis, freshHorizon),
                                updatedAtMillis = nowMillis
                            )
                        )
                    }
                }
            }
        }
    }

    suspend fun evaluateTick(symbol: String, ltp: BigDecimal, nowMillis: Long) {
        evaluationMutex.withLock {
            val config = prefsRepository.getConfig()
            val instrument = instrumentDao.getBySymbol(symbol) ?: return

            // 1. Check validity expiry first so expired orders do not fill
            expireOrdersForTime(nowMillis)

            // 2. Evaluate pending entry orders for this symbol
            val pendingEntries = orderDao.getPendingForSymbol(symbol)
                .filter { it.legRole == null || it.legRole == "ENTRY" }
            for (order in pendingEntries) {
                if (shouldFillPendingEntry(order, ltp)) {
                    val isBuy = order.side == OrderSide.BUY
                    val execPrice = when (order.orderType) {
                        OrderType.LIMIT, OrderType.BRACKET, OrderType.SL_L, OrderType.TGT_L -> {
                            val lim = order.limitPrice
                            if (lim != null) {
                                if (isBuy) minOf(ltp, lim) else maxOf(ltp, lim)
                            } else ltp
                        }
                        else -> ltp
                    }
                    val effectiveConfig = if (order.orderType in setOf(OrderType.LIMIT, OrderType.BRACKET, OrderType.SL_L, OrderType.TGT_L) && order.limitPrice != null) {
                        config.copy(slippageBps = 0)
                    } else {
                        config
                    }
                    val filled = ledgerService.fillEntryOrder(order, execPrice, instrument, effectiveConfig, nowMillis)
                    val msg = if (filled) {
                        "${order.side.name} ${order.symbol} (${RiskMath.formatQtyWithLots(order.quantity, instrument.lotSize)}) filled @ ${RiskMath.formatPrice(execPrice, instrument.segment)}"
                    } else {
                        "${order.side.name} ${order.symbol} rejected: insufficient margin"
                    }
                    _engineEvents.tryEmit(msg)
                    notificationManager?.notifyTradeActivity(
                        title = if (filled) "Order Executed" else "Order Rejected",
                        body = msg
                    )
                }
            }

            // 3. Evaluate open positions for this symbol (TSL, SL, Target, Trailing Target)
            val positions = positionDao.getBySymbol(symbol)
            for (pos in positions) {
                evaluatePositionRisk(pos, instrument, ltp, config, nowMillis)
            }
        }
    }

    suspend fun evaluateTimeEvents(nowMillis: Long) {
        evaluationMutex.withLock {
            val config = prefsRepository.getConfig()
            expireOrdersForTime(nowMillis)

            val openPositions = positionDao.getAllOnce()
            for (pos in openPositions) {
                if (pos.productType == ProductType.INTRADAY &&
                    MarketClock.isIntradaySquareOffTime(pos.segment, pos.instrumentType, nowMillis)
                ) {
                    val ltp = instrumentDao.getBySymbol(pos.symbol)?.ltp ?: pos.averageEntryPrice
                    ledgerService.closePosition(
                        position = pos,
                        rawExitPrice = ltp,
                        exitReason = ExitReason.INTRADAY_SQUARE_OFF,
                        triggeredLegRole = null,
                        config = config,
                        nowMillis = nowMillis
                    )
                    val sqTime = MarketClock.intradaySquareOffLabel(pos.segment, pos.instrumentType)
                    val msg = "$sqTime auto square-off: ${pos.symbol} (${RiskMath.formatQtyWithLots(pos.quantity, pos.lotSize)})"
                    _engineEvents.tryEmit(msg)
                    notificationManager?.notifyTradeActivity("Intraday Auto Square-Off", msg)
                }
            }

            // Cancel untriggered DAY entry orders when session square-off is active.
            // Protective SL/TARGET legs stay alive as long as their parent position is open
            // (closing the position automatically cancels them via TradingLedgerService).
            val pendingOrders = orderDao.getByStatus(OrderStatus.PENDING)
            for (order in pendingOrders) {
                if (order.legRole == "SL" || order.legRole == "TARGET") continue
                if (order.validityType == ValidityType.DAY &&
                    MarketClock.isIntradaySquareOffTime(order.segment, order.instrumentType, nowMillis)
                ) {
                    orderDao.upsert(
                        order.copy(
                            status = OrderStatus.CANCELLED,
                            validityState = ValidityState.EXPIRED,
                            statusReason = "Session close auto-cancel (${MarketClock.intradaySquareOffLabel(order.segment, order.instrumentType)})",
                            updatedAtMillis = nowMillis
                        )
                    )
                }
            }
        }
    }

    /**
     * Allows user/QA to immediately simulate the 15:15 IST Intraday Square-Off event
     * regardless of wall-clock time, while preserving Carry Forward positions & GTT/GTD legs.
     */
    suspend fun triggerSimulatedSquareOff(): Int {
        return evaluationMutex.withLock {
            val nowMillis = System.currentTimeMillis()
            val config = prefsRepository.getConfig()
            var squaredOffCount = 0

            val openPositions = positionDao.getAllOnce()
            for (pos in openPositions) {
                if (pos.productType == ProductType.INTRADAY) {
                    val ltp = instrumentDao.getBySymbol(pos.symbol)?.ltp ?: pos.averageEntryPrice
                    ledgerService.closePosition(
                        position = pos,
                        rawExitPrice = ltp,
                        exitReason = ExitReason.INTRADAY_SQUARE_OFF,
                        triggeredLegRole = null,
                        config = config,
                        nowMillis = nowMillis
                    )
                    squaredOffCount++
                }
            }

            // Auto-cancel all untriggered DAY entry orders (preserving GTT/GTD and active protective legs of Carry Forward positions)
            val remainingOpenPositions = positionDao.getAllOnce()
            val activeProtectiveLegIds = remainingOpenPositions
                .flatMap { listOfNotNull(it.slOrderId, it.targetOrderId) }
                .toSet()
            val pendingOrders = orderDao.getByStatus(OrderStatus.PENDING)
            for (order in pendingOrders) {
                if (order.id in activeProtectiveLegIds) continue
                if (order.validityType == ValidityType.DAY || order.productType == ProductType.INTRADAY) {
                    orderDao.upsert(
                        order.copy(
                            status = OrderStatus.CANCELLED,
                            validityState = ValidityState.EXPIRED,
                            statusReason = "Intraday 15:15 IST square-off",
                            updatedAtMillis = nowMillis
                        )
                    )
                }
            }
            if (squaredOffCount > 0) {
                _engineEvents.tryEmit("Squared off $squaredOffCount Intraday position(s) at 15:15 IST")
            }
            squaredOffCount
        }
    }

    private suspend fun expireOrdersForTime(nowMillis: Long) {
        val activePending = orderDao.getByStatus(OrderStatus.PENDING)
        for (order in activePending) {
            // Never strip active Stop Loss / Target protection from an open position!
            // Protective legs remain active until triggered, manually removed, or the parent position closes.
            if (order.legRole == "SL" || order.legRole == "TARGET") {
                val hasOpenParent = positionDao.getBySymbol(order.symbol)
                    .any { it.slOrderId == order.id || it.targetOrderId == order.id }
                if (hasOpenParent) continue
            }
            if (nowMillis >= order.expiresAtMillis) {
                database.withTransaction {
                    orderDao.upsert(
                        order.copy(
                            status = OrderStatus.CANCELLED,
                            validityState = ValidityState.EXPIRED,
                            statusReason = "Expired (${order.validityType.label})",
                            updatedAtMillis = nowMillis
                        )
                    )
                    if (order.legRole == "SL" || order.legRole == "TARGET") {
                        val positions = positionDao.getBySymbol(order.symbol)
                        for (pos in positions) {
                            if (pos.slOrderId == order.id) {
                                positionDao.upsert(pos.copy(slOrderId = null, activeSlPrice = null, isTslEnabled = false, ocoGroupId = null))
                            } else if (pos.targetOrderId == order.id) {
                                positionDao.upsert(pos.copy(targetOrderId = null, activeTargetPrice = null, isTrailingTargetEnabled = false, ocoGroupId = null))
                            }
                        }
                    }
                }
            }
        }
    }

    private fun shouldFillPendingEntry(order: OrderEntity, ltp: BigDecimal): Boolean {
        val isBuy = order.side == OrderSide.BUY
        return when (order.orderType) {
            OrderType.MARKET -> true
            OrderType.LIMIT, OrderType.BRACKET -> {
                val limit = order.limitPrice ?: return false
                if (isBuy) ltp <= limit else ltp >= limit
            }
            OrderType.SL_M -> {
                val trigger = order.triggerPrice ?: return false
                if (isBuy) ltp >= trigger else ltp <= trigger
            }
            OrderType.SL_L -> {
                val trigger = order.triggerPrice ?: return false
                val limit = order.limitPrice ?: trigger
                if (isBuy) ltp >= trigger && ltp <= limit else ltp <= trigger && ltp >= limit
            }
            OrderType.TGT_M -> {
                val trigger = order.triggerPrice ?: return false
                if (isBuy) ltp <= trigger else ltp >= trigger
            }
            OrderType.TGT_L -> {
                val trigger = order.triggerPrice ?: return false
                val limit = order.limitPrice ?: trigger
                if (isBuy) ltp <= trigger && ltp <= limit else ltp >= trigger && ltp >= limit
            }
        }
    }

    private suspend fun evaluatePositionRisk(
        pos: PositionEntity,
        instrument: InstrumentEntity,
        ltp: BigDecimal,
        config: TradingConfig,
        nowMillis: Long
    ) {
        val isLong = pos.side == OrderSide.BUY
        var workingPos = pos

        // 1. Trailing Stop Loss ratcheting (strictly favourable, never retraces)
        if (workingPos.isTslEnabled && workingPos.activeSlPrice != null) {
            val ratchetedSl = RiskMath.calculateTrailingSl(
                currentSl = workingPos.activeSlPrice,
                ltp = ltp,
                isLong = isLong,
                trailMode = workingPos.tslTrailMode,
                trailValue = workingPos.tslTrailValue,
                tickSize = instrument.tickSize
            )
            val newBest = if (isLong) {
                if (ltp > workingPos.tslBestPrice) ltp else workingPos.tslBestPrice
            } else {
                if (ltp < workingPos.tslBestPrice) ltp else workingPos.tslBestPrice
            }
            if (ratchetedSl != workingPos.activeSlPrice || newBest != workingPos.tslBestPrice) {
                database.withTransaction {
                    val fresh = positionDao.getById(workingPos.id) ?: return@withTransaction
                    if (!fresh.isTslEnabled || fresh.activeSlPrice == null) return@withTransaction
                    val safeRatchet = if (isLong) {
                        maxOf(fresh.activeSlPrice, ratchetedSl)
                    } else {
                        minOf(fresh.activeSlPrice, ratchetedSl)
                    }
                    workingPos = fresh.copy(
                        activeSlPrice = safeRatchet,
                        tslBestPrice = newBest,
                        updatedAtMillis = nowMillis
                    )
                    positionDao.upsert(workingPos)
                    workingPos.slOrderId?.let { slId ->
                        orderDao.getById(slId)?.let { slOrder ->
                            orderDao.upsert(
                                slOrder.copy(
                                    triggerPrice = safeRatchet,
                                    statusReason = "TSL ratcheted @ ${RiskMath.formatPrice(safeRatchet, instrument.segment)}",
                                    updatedAtMillis = nowMillis
                                )
                            )
                        }
                    }
                }
            }
        }

        // 2. Stop Loss trigger check
        val slPrice = workingPos.activeSlPrice
        if (slPrice != null) {
            val slTriggered = if (isLong) ltp <= slPrice else ltp >= slPrice
            if (slTriggered) {
                val reason = if (workingPos.isTslEnabled) ExitReason.TSL_HIT else ExitReason.SL_HIT
                ledgerService.closePosition(
                    position = workingPos,
                    rawExitPrice = ltp,
                    exitReason = reason,
                    triggeredLegRole = "SL",
                    config = config,
                    nowMillis = nowMillis
                )
                val msg = "${reason.label}: ${workingPos.symbol} @ ${RiskMath.formatPrice(ltp, instrument.segment)}"
                _engineEvents.tryEmit(msg)
                notificationManager?.notifyTradeActivity(reason.label, msg)
                return
            }
        }

        // 3. Target & Trailing Target check
        val targetPrice = workingPos.activeTargetPrice ?: return
        if (workingPos.isTrailingTargetEnabled) {
            val update = RiskMath.evaluateTrailingTarget(
                ltp = ltp,
                initialTarget = targetPrice,
                isLong = isLong,
                alreadyActivated = workingPos.trailingTargetActivated,
                currentPeak = workingPos.trailingTargetPeakPrice,
                currentExitTrigger = workingPos.trailingTargetExitTrigger,
                trailMode = workingPos.trailingTargetTrailMode,
                trailValue = workingPos.trailingTargetTrailValue,
                tickSize = instrument.tickSize
            )
            if (update.shouldExitNow) {
                ledgerService.closePosition(
                    position = workingPos,
                    rawExitPrice = ltp,
                    exitReason = ExitReason.TRAILING_TARGET_HIT,
                    triggeredLegRole = "TARGET",
                    config = config,
                    nowMillis = nowMillis
                )
                val msg = "Trailing Target Hit: ${workingPos.symbol} @ ${RiskMath.formatPrice(ltp, instrument.segment)}"
                _engineEvents.tryEmit(msg)
                notificationManager?.notifyTradeActivity("Trailing Target Hit", msg)
                return
            }
            if (update.activated != workingPos.trailingTargetActivated ||
                update.peakPrice != workingPos.trailingTargetPeakPrice ||
                update.exitTrigger != workingPos.trailingTargetExitTrigger
            ) {
                database.withTransaction {
                    val fresh = positionDao.getById(workingPos.id) ?: return@withTransaction
                    if (!fresh.isTrailingTargetEnabled || fresh.activeTargetPrice == null) return@withTransaction
                    workingPos = fresh.copy(
                        trailingTargetActivated = update.activated,
                        trailingTargetPeakPrice = update.peakPrice,
                        trailingTargetExitTrigger = update.exitTrigger,
                        updatedAtMillis = nowMillis
                    )
                    positionDao.upsert(workingPos)
                    val triggerStr = update.exitTrigger?.let { RiskMath.formatPrice(it, instrument.segment) } ?: "--"
                    workingPos.targetOrderId?.let { tgtId ->
                        orderDao.getById(tgtId)?.let { tgtOrder ->
                            orderDao.upsert(
                                tgtOrder.copy(
                                    statusReason = if (update.activated) "Trailing Target exit @ $triggerStr" else tgtOrder.statusReason,
                                    updatedAtMillis = nowMillis
                                )
                            )
                        }
                    }
                }
            }
        } else {
            val targetHit = if (isLong) ltp >= targetPrice else ltp <= targetPrice
            if (targetHit) {
                ledgerService.closePosition(
                    position = workingPos,
                    rawExitPrice = ltp,
                    exitReason = ExitReason.TARGET_HIT,
                    triggeredLegRole = "TARGET",
                    config = config,
                    nowMillis = nowMillis
                )
                val msg = "Target Hit: ${workingPos.symbol} @ ${RiskMath.formatPrice(ltp, instrument.segment)}"
                _engineEvents.tryEmit(msg)
                notificationManager?.notifyTradeActivity("Target Profit Hit", msg)
            }
        }
    }
}
