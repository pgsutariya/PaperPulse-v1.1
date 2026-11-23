package com.example.data.repository

import androidx.room.withTransaction
import com.example.data.engine.ExecutionEngine
import com.example.data.engine.TradingLedgerService
import com.example.data.local.AccountDao
import com.example.data.local.InstrumentDao
import com.example.data.local.OcoLinkDao
import com.example.data.local.OcoLinkEntity
import com.example.data.local.OrderDao
import com.example.data.local.OrderEntity
import com.example.data.local.PaperPulseDatabase
import com.example.data.local.PositionDao
import com.example.data.local.SeedData
import com.example.data.local.TradeJournalDao
import com.example.data.local.toDomain
import com.example.domain.engine.MarketClock
import com.example.domain.engine.RiskMath
import com.example.domain.model.AccountState
import com.example.domain.model.ExitReason
import com.example.domain.model.InstrumentType
import com.example.domain.model.Order
import com.example.domain.model.OrderPlacementRequest
import com.example.domain.model.OrderSide
import com.example.domain.model.OrderStatus
import com.example.domain.model.OrderType
import com.example.domain.model.Position
import com.example.domain.model.ProductType
import com.example.domain.model.RiskInputMode
import com.example.domain.model.TradeJournalEntry
import com.example.domain.model.TrailMode
import com.example.domain.model.ValidityState
import com.example.domain.model.ValidityType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.math.BigDecimal
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TradingRepository private constructor(
    private val database: PaperPulseDatabase,
    private val instrumentDao: InstrumentDao,
    private val orderDao: OrderDao,
    private val positionDao: PositionDao,
    private val ocoLinkDao: OcoLinkDao,
    private val tradeJournalDao: TradeJournalDao,
    private val accountDao: AccountDao,
    private val prefsRepository: TradingPreferencesRepository,
    private val ledgerService: TradingLedgerService,
    private val executionEngine: ExecutionEngine,
    private val notificationManager: com.example.data.notifications.TradeNotificationManager?,
    @Suppress("UNUSED_PARAMETER") internalTag: Boolean
) {
    @Inject
    constructor(
        database: PaperPulseDatabase,
        instrumentDao: InstrumentDao,
        orderDao: OrderDao,
        positionDao: PositionDao,
        ocoLinkDao: OcoLinkDao,
        tradeJournalDao: TradeJournalDao,
        accountDao: AccountDao,
        prefsRepository: TradingPreferencesRepository,
        ledgerService: TradingLedgerService,
        executionEngine: ExecutionEngine,
        notificationManager: com.example.data.notifications.TradeNotificationManager
    ) : this(
        database, instrumentDao, orderDao, positionDao, ocoLinkDao,
        tradeJournalDao, accountDao, prefsRepository, ledgerService,
        executionEngine, notificationManager, true
    )

    constructor(
        database: PaperPulseDatabase,
        instrumentDao: InstrumentDao,
        orderDao: OrderDao,
        positionDao: PositionDao,
        ocoLinkDao: OcoLinkDao,
        tradeJournalDao: TradeJournalDao,
        accountDao: AccountDao,
        prefsRepository: TradingPreferencesRepository,
        ledgerService: TradingLedgerService,
        executionEngine: ExecutionEngine
    ) : this(
        database, instrumentDao, orderDao, positionDao, ocoLinkDao,
        tradeJournalDao, accountDao, prefsRepository, ledgerService,
        executionEngine, null, false
    )

    val orders: Flow<List<Order>> = orderDao.observeAll().map { list -> list.map { it.toDomain() } }
    val positions: Flow<List<Position>> = positionDao.observeAll().map { list -> list.map { it.toDomain() } }
    val journalEntries: Flow<List<TradeJournalEntry>> = tradeJournalDao.observeAll().map { list -> list.map { it.toDomain() } }
    val accountState: Flow<AccountState> = accountDao.observeAccount().map { (it ?: SeedData.defaultAccount()).toDomain() }
    val capitalTransactions: Flow<List<com.example.domain.model.CapitalTransaction>> =
        database.capitalTransactionDao().observeAll().map { list -> list.map { it.toDomain() } }

    suspend fun placeOrder(request: OrderPlacementRequest): Result<String> {
        val instrument = instrumentDao.getBySymbol(request.symbol)
            ?: return Result.failure(IllegalArgumentException("Instrument not found: ${request.symbol}"))

        if (request.quantity <= 0) {
            return Result.failure(IllegalArgumentException("Quantity must be greater than zero"))
        }
        if (request.productType == ProductType.CARRY_FORWARD && !instrument.supportsCarryForward) {
            return Result.failure(IllegalArgumentException("${instrument.symbol} only supports Intraday paper trading"))
        }

        if (instrument.type != InstrumentType.STOCK && instrument.lotSize > 1 && request.quantity % instrument.lotSize != 0) {
            return Result.failure(
                IllegalArgumentException("Quantity must be a multiple of lot size ${instrument.lotSize}")
            )
        }

        val nowMillis = System.currentTimeMillis()
        if (request.productType == ProductType.INTRADAY &&
            MarketClock.isIntradaySquareOffTime(instrument.segment, instrument.type, nowMillis)
        ) {
            val sqLabel = MarketClock.intradaySquareOffLabel(instrument.segment, instrument.type)
            return Result.failure(
                IllegalStateException("Intraday (MIS) orders are blocked during the $sqLabel square-off window — use Carry Forward")
            )
        }
        val config = prefsRepository.getConfig()
        val referencePrice = request.limitPrice ?: request.triggerPrice ?: instrument.ltp
        validateRiskGeometry(request, referencePrice)?.let { return Result.failure(IllegalArgumentException(it)) }
        val requiredMargin = ledgerService.computeRequiredMargin(
            productType = request.productType,
            price = referencePrice,
            quantity = request.quantity,
            instrumentType = instrument.type
        )
        val account = accountDao.getAccount() ?: SeedData.defaultAccount()
        if (account.availableMargin < requiredMargin) {
            return Result.failure(
                IllegalStateException(
                    "Insufficient margin: need ${RiskMath.formatPortfolioInr(requiredMargin)}, available ${RiskMath.formatPortfolioInr(account.availableMargin)}"
                )
            )
        }

        val expiresAtMillis = MarketClock.computeExpiryMillis(
            validityType = request.validityType,
            segment = instrument.segment,
            gttDays = request.gttDays,
            gtdDateMillis = request.gtdDateMillis,
            nowMillis = nowMillis,
            instrumentType = instrument.type
        )

        val estTurnover = referencePrice.multiply(BigDecimal(request.quantity))
        val estCharges = RiskMath.calculateOrderCharges(
            segment = instrument.segment,
            instrumentType = instrument.type,
            productType = request.productType,
            side = request.side,
            turnover = estTurnover,
            flatBrokeragePerOrder = config.flatBrokerage,
            cryptoFeePercent = config.cryptoFeePercent
        )

        val orderId = UUID.randomUUID().toString()
        val entryOrder = OrderEntity(
            id = orderId,
            symbol = instrument.symbol,
            segment = instrument.segment,
            instrumentType = instrument.type,
            side = request.side,
            orderType = request.orderType,
            productType = request.productType,
            quantity = request.quantity,
            limitPrice = request.limitPrice?.let { RiskMath.roundToTick(it, instrument.tickSize) },
            triggerPrice = request.triggerPrice?.let { RiskMath.roundToTick(it, instrument.tickSize) },
            filledPrice = null,
            status = OrderStatus.PENDING,
            validityType = request.validityType,
            gttDays = if (request.validityType == ValidityType.GTT) request.gttDays.coerceIn(1, 365) else null,
            expiresAtMillis = expiresAtMillis,
            validityState = ValidityState.ACTIVE,
            ocoGroupId = null,
            parentOrderId = null,
            legRole = "ENTRY",
            attachedSlMode = if (request.slEnabled) request.slMode else null,
            attachedSlValue = if (request.slEnabled) request.slValue else null,
            attachedTargetMode = if (request.targetEnabled) request.targetMode else null,
            attachedTargetValue = if (request.targetEnabled) request.targetValue else null,
            attachedTslEnabled = request.slEnabled && request.tslEnabled,
            attachedTslMode = if (request.slEnabled && request.tslEnabled) request.tslMode else null,
            attachedTslValue = if (request.slEnabled && request.tslEnabled) request.tslValue else null,
            attachedTrailingTargetEnabled = request.targetEnabled && request.trailingTargetEnabled,
            attachedTrailingTargetMode = if (request.targetEnabled && request.trailingTargetEnabled) request.trailingTargetMode else null,
            attachedTrailingTargetValue = if (request.targetEnabled && request.trailingTargetEnabled) request.trailingTargetValue else null,
            statusReason = "Waiting for ${request.orderType.label} trigger",
            createdAtMillis = nowMillis,
            updatedAtMillis = nowMillis,
            lotSize = instrument.lotSize,
            brokerage = estCharges.brokerage,
            sttCtt = estCharges.sttCtt,
            exchangeCharges = estCharges.exchangeCharges,
            sebiCharges = estCharges.sebiCharges,
            stampDuty = estCharges.stampDuty,
            gst = estCharges.gst,
            totalCharges = estCharges.total
        )

        val immediateFill = request.orderType == OrderType.MARKET ||
            (request.orderType == OrderType.BRACKET && request.limitPrice == null)

        val qtyLabel = RiskMath.formatQtyWithLots(request.quantity, instrument.lotSize)
        return if (immediateFill) {
            val filled = ledgerService.fillEntryOrder(entryOrder, instrument.ltp, instrument, config, nowMillis)
            if (filled) {
                val msg = "Executed ${request.side.name} ${instrument.symbol} • Qty $qtyLabel @ ${RiskMath.formatPrice(instrument.ltp, instrument.segment)}"
                notificationManager?.notifyTradeActivity("Trade Executed", msg)
                Result.success(msg)
            } else {
                Result.failure(IllegalStateException("Order rejected at fill: insufficient margin"))
            }
        } else {
            orderDao.upsert(entryOrder)
            val msg = "Placed ${request.orderType.label} ${request.side.name} ${instrument.symbol} • Qty $qtyLabel (${request.validityType.label})"
            notificationManager?.notifyTradeActivity("Pending Order Placed", msg)
            Result.success(msg)
        }
    }

    /**
     * Rejects SL/Target values on the wrong side of the entry (e.g. a BUY with SL above entry would
     * be stopped out on the first tick) and impossible percentages.
     */
    private fun validateRiskGeometry(request: OrderPlacementRequest, entryRef: BigDecimal): String? {
        val isBuy = request.side == OrderSide.BUY
        if (request.slEnabled) {
            val v = request.slValue ?: return "Enter a Stop Loss value"
            if (v <= BigDecimal.ZERO) return "Stop Loss must be greater than zero"
            when (request.slMode) {
                RiskInputMode.PRICE -> {
                    if (isBuy && v >= entryRef) return "Stop Loss must be below the entry price for a BUY"
                    if (!isBuy && v <= entryRef) return "Stop Loss must be above the entry price for a SELL"
                }
                RiskInputMode.PERCENT -> if (isBuy && v >= BigDecimal("100")) return "Stop Loss % must be below 100"
            }
        }
        if (request.targetEnabled) {
            val v = request.targetValue ?: return "Enter a Target value"
            if (v <= BigDecimal.ZERO) return "Target must be greater than zero"
            when (request.targetMode) {
                RiskInputMode.PRICE -> {
                    if (isBuy && v <= entryRef) return "Target must be above the entry price for a BUY"
                    if (!isBuy && v >= entryRef) return "Target must be below the entry price for a SELL"
                }
                RiskInputMode.PERCENT -> if (!isBuy && v >= BigDecimal("100")) return "Target % must be below 100 for a SELL"
            }
        }
        return null
    }

    suspend fun cancelOrder(orderId: String): Result<Unit> {
        val nowMillis = System.currentTimeMillis()
        database.withTransaction {
            val order = orderDao.getById(orderId) ?: return@withTransaction
            if (order.status != OrderStatus.PENDING) return@withTransaction

            orderDao.upsert(
                order.copy(
                    status = OrderStatus.CANCELLED,
                    validityState = ValidityState.CANCELLED,
                    statusReason = "Cancelled by user",
                    updatedAtMillis = nowMillis
                )
            )

            // If this was a protective leg on an open position, clear the link on the position
            if (order.legRole == "SL" || order.legRole == "TARGET") {
                positionDao.getBySymbol(order.symbol).forEach { pos ->
                    if (pos.slOrderId == order.id) {
                        positionDao.upsert(
                            pos.copy(
                                slOrderId = null,
                                activeSlPrice = null,
                                isTslEnabled = false,
                                updatedAtMillis = nowMillis
                            )
                        )
                    } else if (pos.targetOrderId == order.id) {
                        positionDao.upsert(
                            pos.copy(
                                targetOrderId = null,
                                activeTargetPrice = null,
                                isTrailingTargetEnabled = false,
                                trailingTargetActivated = false,
                                trailingTargetExitTrigger = null,
                                updatedAtMillis = nowMillis
                            )
                        )
                    }
                }
            }
        }
        return Result.success(Unit)
    }

    suspend fun updatePositionRiskControls(
        positionId: String,
        newSlPrice: BigDecimal?,
        newTargetPrice: BigDecimal?,
        isTslEnabled: Boolean,
        tslMode: TrailMode,
        tslValue: BigDecimal,
        isTrailingTargetEnabled: Boolean,
        trailingTargetMode: TrailMode,
        trailingTargetValue: BigDecimal
    ): Result<Unit> {
        val nowMillis = System.currentTimeMillis()
        database.withTransaction {
            val pos = positionDao.getById(positionId)
                ?: return@withTransaction
            val instrument = instrumentDao.getBySymbol(pos.symbol)
            val tickSize = instrument?.tickSize ?: BigDecimal("0.05")
            val exitSide = if (pos.side == OrderSide.BUY) OrderSide.SELL else OrderSide.BUY

            val roundedSl = newSlPrice?.let { RiskMath.roundToTick(it.max(tickSize), tickSize) }
            val roundedTarget = newTargetPrice?.let { RiskMath.roundToTick(it.max(tickSize), tickSize) }

            val ocoGroupId = if (roundedSl != null && roundedTarget != null) {
                pos.ocoGroupId ?: UUID.randomUUID().toString()
            } else {
                pos.ocoGroupId?.let { ocoLinkDao.deactivate(it) }
                null
            }

            // Update or create/cancel SL leg
            val updatedSlOrderId = when {
                roundedSl != null -> {
                    val existingSl = pos.slOrderId?.let { orderDao.getById(it) }?.takeIf { it.status == OrderStatus.PENDING }
                    val id = existingSl?.id ?: UUID.randomUUID().toString()
                    orderDao.upsert(
                        existingSl?.copy(
                            triggerPrice = roundedSl,
                            ocoGroupId = ocoGroupId,
                            attachedTslEnabled = isTslEnabled,
                            attachedTslMode = tslMode,
                            attachedTslValue = tslValue,
                            statusReason = if (isTslEnabled) "Trailing SL Updated" else "SL Updated",
                            updatedAtMillis = nowMillis
                        ) ?: OrderEntity(
                            id = id,
                            symbol = pos.symbol,
                            segment = pos.segment,
                            instrumentType = pos.instrumentType,
                            side = exitSide,
                            orderType = OrderType.SL_M,
                            productType = pos.productType,
                            quantity = pos.quantity,
                            limitPrice = null,
                            triggerPrice = roundedSl,
                            filledPrice = null,
                            status = OrderStatus.PENDING,
                            validityType = pos.validityType,
                            gttDays = pos.gttDays,
                            expiresAtMillis = pos.expiresAtMillis,
                            validityState = ValidityState.ACTIVE,
                            ocoGroupId = ocoGroupId,
                            parentOrderId = null,
                            legRole = "SL",
                            attachedSlMode = null,
                            attachedSlValue = null,
                            attachedTargetMode = null,
                            attachedTargetValue = null,
                            attachedTslEnabled = isTslEnabled,
                            attachedTslMode = tslMode,
                            attachedTslValue = tslValue,
                            attachedTrailingTargetEnabled = false,
                            attachedTrailingTargetMode = null,
                            attachedTrailingTargetValue = null,
                            statusReason = if (isTslEnabled) "Trailing SL Armed" else "Protective SL",
                            createdAtMillis = nowMillis,
                            updatedAtMillis = nowMillis
                        )
                    )
                    id
                }
                else -> {
                    pos.slOrderId?.let { slId ->
                        orderDao.getById(slId)?.takeIf { it.status == OrderStatus.PENDING }?.let {
                            orderDao.upsert(
                                it.copy(
                                    status = OrderStatus.CANCELLED,
                                    validityState = ValidityState.CANCELLED,
                                    statusReason = "Removed by user",
                                    updatedAtMillis = nowMillis
                                )
                            )
                        }
                    }
                    null
                }
            }

            // Update or create/cancel Target leg
            val updatedTargetOrderId = when {
                roundedTarget != null -> {
                    val existingTgt = pos.targetOrderId?.let { orderDao.getById(it) }?.takeIf { it.status == OrderStatus.PENDING }
                    val id = existingTgt?.id ?: UUID.randomUUID().toString()
                    orderDao.upsert(
                        existingTgt?.copy(
                            triggerPrice = roundedTarget,
                            ocoGroupId = ocoGroupId,
                            attachedTrailingTargetEnabled = isTrailingTargetEnabled,
                            attachedTrailingTargetMode = trailingTargetMode,
                            attachedTrailingTargetValue = trailingTargetValue,
                            statusReason = if (isTrailingTargetEnabled) "Trailing Target Updated" else "Target Updated",
                            updatedAtMillis = nowMillis
                        ) ?: OrderEntity(
                            id = id,
                            symbol = pos.symbol,
                            segment = pos.segment,
                            instrumentType = pos.instrumentType,
                            side = exitSide,
                            orderType = OrderType.TGT_M,
                            productType = pos.productType,
                            quantity = pos.quantity,
                            limitPrice = null,
                            triggerPrice = roundedTarget,
                            filledPrice = null,
                            status = OrderStatus.PENDING,
                            validityType = pos.validityType,
                            gttDays = pos.gttDays,
                            expiresAtMillis = pos.expiresAtMillis,
                            validityState = ValidityState.ACTIVE,
                            ocoGroupId = ocoGroupId,
                            parentOrderId = null,
                            legRole = "TARGET",
                            attachedSlMode = null,
                            attachedSlValue = null,
                            attachedTargetMode = null,
                            attachedTargetValue = null,
                            attachedTslEnabled = false,
                            attachedTslMode = null,
                            attachedTslValue = null,
                            attachedTrailingTargetEnabled = isTrailingTargetEnabled,
                            attachedTrailingTargetMode = trailingTargetMode,
                            attachedTrailingTargetValue = trailingTargetValue,
                            statusReason = if (isTrailingTargetEnabled) "Trailing Target Armed" else "Target Order",
                            createdAtMillis = nowMillis,
                            updatedAtMillis = nowMillis
                        )
                    )
                    id
                }
                else -> {
                    pos.targetOrderId?.let { tgtId ->
                        orderDao.getById(tgtId)?.takeIf { it.status == OrderStatus.PENDING }?.let {
                            orderDao.upsert(
                                it.copy(
                                    status = OrderStatus.CANCELLED,
                                    validityState = ValidityState.CANCELLED,
                                    statusReason = "Removed by user",
                                    updatedAtMillis = nowMillis
                                )
                            )
                        }
                    }
                    null
                }
            }

            if (ocoGroupId != null) {
                ocoLinkDao.upsert(
                    OcoLinkEntity(
                        ocoGroupId = ocoGroupId,
                        positionId = pos.id,
                        slOrderId = updatedSlOrderId,
                        targetOrderId = updatedTargetOrderId,
                        isActive = true
                    )
                )
            }

            positionDao.upsert(
                pos.copy(
                    activeSlPrice = roundedSl,
                    activeTargetPrice = roundedTarget,
                    slOrderId = updatedSlOrderId,
                    targetOrderId = updatedTargetOrderId,
                    ocoGroupId = ocoGroupId,
                    isTslEnabled = isTslEnabled && roundedSl != null,
                    tslTrailMode = tslMode,
                    tslTrailValue = tslValue,
                    isTrailingTargetEnabled = isTrailingTargetEnabled && roundedTarget != null,
                    trailingTargetTrailMode = trailingTargetMode,
                    trailingTargetTrailValue = trailingTargetValue,
                    trailingTargetActivated = if (isTrailingTargetEnabled && roundedTarget != null) pos.trailingTargetActivated else false,
                    trailingTargetPeakPrice = if (isTrailingTargetEnabled && roundedTarget != null) pos.trailingTargetPeakPrice else null,
                    trailingTargetExitTrigger = if (isTrailingTargetEnabled && roundedTarget != null) pos.trailingTargetExitTrigger else null,
                    updatedAtMillis = nowMillis
                )
            )
        }
        return Result.success(Unit)
    }

    suspend fun convertProductToCarryForward(positionId: String): Result<String> {
        val nowMillis = System.currentTimeMillis()
        var result: Result<String> = Result.failure(IllegalStateException("Position not found"))
        database.withTransaction {
            val pos = positionDao.getById(positionId) ?: return@withTransaction
            val instrument = instrumentDao.getBySymbol(pos.symbol)
            if (instrument == null || !instrument.supportsCarryForward) {
                result = Result.failure(IllegalStateException("${pos.symbol} does not allow Carry Forward conversion"))
                return@withTransaction
            }
            if (pos.productType == ProductType.CARRY_FORWARD) {
                result = Result.success("Already Carry Forward")
                return@withTransaction
            }

            val oldMargin = ledgerService.computeRequiredMargin(
                ProductType.INTRADAY, pos.averageEntryPrice, pos.quantity, pos.instrumentType
            )
            val newMargin = ledgerService.computeRequiredMargin(
                ProductType.CARRY_FORWARD, pos.averageEntryPrice, pos.quantity, pos.instrumentType
            )
            val extraMarginNeeded = newMargin.subtract(oldMargin).max(BigDecimal.ZERO)
            val account = accountDao.getAccount() ?: SeedData.defaultAccount()
            if (account.availableMargin < extraMarginNeeded) {
                result = Result.failure(
                    IllegalStateException(
                        "Need ${RiskMath.formatPortfolioInr(extraMarginNeeded)} extra margin to convert to Carry Forward"
                    )
                )
                return@withTransaction
            }

            // Upgrade DAY validity to GTT 7 days so protective legs persist across sessions
            val newValidityType = if (pos.validityType == ValidityType.DAY) ValidityType.GTT else pos.validityType
            val newGttDays = if (pos.validityType == ValidityType.DAY) 7 else pos.gttDays
            val newExpiry = if (pos.validityType == ValidityType.DAY) {
                MarketClock.computeExpiryMillis(ValidityType.GTT, pos.segment, gttDays = 7, nowMillis = nowMillis)
            } else {
                pos.expiresAtMillis
            }

            positionDao.upsert(
                pos.copy(
                    productType = ProductType.CARRY_FORWARD,
                    validityType = newValidityType,
                    gttDays = newGttDays,
                    expiresAtMillis = newExpiry,
                    updatedAtMillis = nowMillis
                )
            )

            listOfNotNull(pos.slOrderId, pos.targetOrderId).forEach { legId ->
                orderDao.getById(legId)?.takeIf { it.status == OrderStatus.PENDING }?.let { leg ->
                    orderDao.upsert(
                        leg.copy(
                            productType = ProductType.CARRY_FORWARD,
                            validityType = newValidityType,
                            gttDays = if (newValidityType == ValidityType.GTT) newGttDays else leg.gttDays,
                            expiresAtMillis = newExpiry,
                            updatedAtMillis = nowMillis
                        )
                    )
                }
            }

            accountDao.updateBalances(
                margin = account.availableMargin.subtract(extraMarginNeeded),
                realised = account.realisedPnl
            )
            result = Result.success("Converted ${pos.symbol} to Carry Forward (${newValidityType.label})")
        }
        return result
    }

    suspend fun exitPositionAtMarket(positionId: String): Result<Unit> {
        val pos = positionDao.getById(positionId)
            ?: return Result.failure(IllegalStateException("Position already closed"))
        val ltp = instrumentDao.getBySymbol(pos.symbol)?.ltp ?: pos.averageEntryPrice
        val config = prefsRepository.getConfig()
        ledgerService.closePosition(
            position = pos,
            rawExitPrice = ltp,
            exitReason = ExitReason.MANUAL_EXIT,
            triggeredLegRole = null,
            config = config,
            nowMillis = System.currentTimeMillis()
        )
        notificationManager?.notifyTradeActivity(
            title = "Position Squared Off",
            body = "Closed ${pos.symbol} (${RiskMath.formatQtyWithLots(pos.quantity, pos.lotSize)}) @ ${RiskMath.formatPrice(ltp, pos.segment)}"
        )
        return Result.success(Unit)
    }

    /**
     * Modifies a PENDING order (quantity, limitPrice, triggerPrice, attached SL/Target).
     * If the pending order is an SL or TARGET leg linked to an open position, also keeps the position in sync.
     */
    suspend fun modifyPendingOrder(
        orderId: String,
        newQuantity: Int,
        newLimitPrice: BigDecimal?,
        newTriggerPrice: BigDecimal?,
        newAttachedSlValue: BigDecimal? = null,
        newAttachedTargetValue: BigDecimal? = null
    ): Result<String> {
        val nowMillis = System.currentTimeMillis()
        var result: Result<String> = Result.failure(IllegalStateException("Pending order not found"))
        database.withTransaction {
            val existing = orderDao.getById(orderId) ?: return@withTransaction
            if (existing.status != OrderStatus.PENDING) {
                result = Result.failure(IllegalStateException("Only PENDING orders can be modified"))
                return@withTransaction
            }
            val instrument = instrumentDao.getBySymbol(existing.symbol)
            val tickSize = instrument?.tickSize ?: BigDecimal("0.05")
            val lotSize = instrument?.lotSize ?: existing.lotSize.coerceAtLeast(1)

            if (newQuantity <= 0) {
                result = Result.failure(IllegalArgumentException("Quantity must be greater than zero"))
                return@withTransaction
            }
            if (existing.instrumentType != InstrumentType.STOCK && lotSize > 1 && newQuantity % lotSize != 0) {
                result = Result.failure(IllegalArgumentException("Quantity must be a multiple of lot size $lotSize"))
                return@withTransaction
            }

            val roundedLimit = newLimitPrice?.takeIf { it > BigDecimal.ZERO }?.let { RiskMath.roundToTick(it, tickSize) }
            val roundedTrigger = newTriggerPrice?.takeIf { it > BigDecimal.ZERO }?.let { RiskMath.roundToTick(it, tickSize) }

            val config = prefsRepository.getConfig()
            val refPrice = roundedLimit ?: roundedTrigger ?: instrument?.ltp ?: BigDecimal.ONE
            val estCharges = RiskMath.calculateOrderCharges(
                segment = existing.segment,
                instrumentType = existing.instrumentType,
                productType = existing.productType,
                side = existing.side,
                turnover = refPrice.multiply(BigDecimal(newQuantity)),
                flatBrokeragePerOrder = config.flatBrokerage,
                cryptoFeePercent = config.cryptoFeePercent
            )

            val updated = existing.copy(
                quantity = newQuantity,
                limitPrice = roundedLimit,
                triggerPrice = roundedTrigger,
                attachedSlValue = newAttachedSlValue ?: existing.attachedSlValue,
                attachedTargetValue = newAttachedTargetValue ?: existing.attachedTargetValue,
                statusReason = "Modified by user",
                updatedAtMillis = nowMillis,
                lotSize = lotSize,
                brokerage = estCharges.brokerage,
                sttCtt = estCharges.sttCtt,
                exchangeCharges = estCharges.exchangeCharges,
                sebiCharges = estCharges.sebiCharges,
                stampDuty = estCharges.stampDuty,
                gst = estCharges.gst,
                totalCharges = estCharges.total
            )
            orderDao.upsert(updated)

            // Keep open position risk prices in sync if this is an SL/Target protective leg
            if ((existing.legRole == "SL" || existing.legRole == "TARGET") && roundedTrigger != null) {
                positionDao.getBySymbol(existing.symbol).forEach { pos ->
                    if (pos.slOrderId == existing.id) {
                        positionDao.upsert(pos.copy(activeSlPrice = roundedTrigger, updatedAtMillis = nowMillis))
                    } else if (pos.targetOrderId == existing.id) {
                        positionDao.upsert(pos.copy(activeTargetPrice = roundedTrigger, updatedAtMillis = nowMillis))
                    }
                }
            }

            val priceLabel = roundedLimit ?: roundedTrigger
            val msg = "Updated ${existing.symbol} pending order • Qty ${RiskMath.formatQtyWithLots(newQuantity, lotSize)}" +
                (priceLabel?.let { " @ ${RiskMath.formatPrice(it, existing.segment)}" } ?: "")
            result = Result.success(msg)
        }
        result.getOrNull()?.let { msg ->
            notificationManager?.notifyTradeActivity("Pending Order Updated", msg)
        }
        return result
    }

    /**
     * Averages (scales into) an open running position with additional quantity at live LTP.
     * Recalculates weighted average entry price, reserves incremental margin, updates linked SL/Target quantities,
     * and records an executed AVERAGE order with itemized brokerage & statutory taxes.
     */
    suspend fun averageRunningPosition(
        positionId: String,
        additionalQuantity: Int
    ): Result<String> {
        val nowMillis = System.currentTimeMillis()
        val config = prefsRepository.getConfig()
        var result: Result<String> = Result.failure(IllegalStateException("Position not found"))
        database.withTransaction {
            val pos = positionDao.getById(positionId) ?: return@withTransaction
            val instrument = instrumentDao.getBySymbol(pos.symbol)
                ?: run {
                    result = Result.failure(IllegalStateException("Instrument ${pos.symbol} unavailable"))
                    return@withTransaction
                }
            val lotSize = instrument.lotSize.coerceAtLeast(1)
            if (additionalQuantity <= 0) {
                result = Result.failure(IllegalArgumentException("Additional quantity must be greater than zero"))
                return@withTransaction
            }
            if (pos.instrumentType != InstrumentType.STOCK && lotSize > 1 && additionalQuantity % lotSize != 0) {
                result = Result.failure(IllegalArgumentException("Quantity must be a multiple of lot size $lotSize"))
                return@withTransaction
            }

            val fillPrice = RiskMath.applySlippage(
                price = instrument.ltp,
                side = pos.side,
                slippageBps = config.slippageBps,
                tickSize = instrument.tickSize
            )
            val extraMargin = ledgerService.computeRequiredMargin(
                productType = pos.productType,
                price = fillPrice,
                quantity = additionalQuantity,
                instrumentType = pos.instrumentType
            )
            val account = accountDao.getAccount() ?: SeedData.defaultAccount()
            if (account.availableMargin < extraMargin) {
                result = Result.failure(
                    IllegalStateException(
                        "Insufficient margin to average: need ${RiskMath.formatPortfolioInr(extraMargin)}, available ${RiskMath.formatPortfolioInr(account.availableMargin)}"
                    )
                )
                return@withTransaction
            }

            val oldCost = pos.averageEntryPrice.multiply(BigDecimal(pos.quantity))
            val addCost = fillPrice.multiply(BigDecimal(additionalQuantity))
            val newQty = pos.quantity + additionalQuantity
            val newAvg = oldCost.add(addCost).divide(
                BigDecimal(newQty),
                maxOf(instrument.tickSize.scale(), 2),
                java.math.RoundingMode.HALF_UP
            )

            val avgCharges = RiskMath.calculateOrderCharges(
                segment = pos.segment,
                instrumentType = pos.instrumentType,
                productType = pos.productType,
                side = pos.side,
                turnover = addCost,
                flatBrokeragePerOrder = config.flatBrokerage,
                cryptoFeePercent = config.cryptoFeePercent
            )

            // Log executed averaging order
            orderDao.upsert(
                OrderEntity(
                    id = UUID.randomUUID().toString(),
                    symbol = pos.symbol,
                    segment = pos.segment,
                    instrumentType = pos.instrumentType,
                    side = pos.side,
                    orderType = OrderType.MARKET,
                    productType = pos.productType,
                    quantity = additionalQuantity,
                    limitPrice = null,
                    triggerPrice = null,
                    filledPrice = fillPrice,
                    status = OrderStatus.EXECUTED,
                    validityType = pos.validityType,
                    gttDays = pos.gttDays,
                    expiresAtMillis = pos.expiresAtMillis,
                    validityState = ValidityState.TRIGGERED,
                    ocoGroupId = pos.ocoGroupId,
                    parentOrderId = null,
                    legRole = "AVERAGE",
                    attachedSlMode = null,
                    attachedSlValue = null,
                    attachedTargetMode = null,
                    attachedTargetValue = null,
                    attachedTslEnabled = false,
                    attachedTslMode = null,
                    attachedTslValue = null,
                    attachedTrailingTargetEnabled = false,
                    attachedTrailingTargetMode = null,
                    attachedTrailingTargetValue = null,
                    statusReason = "Averaged position • New Avg ${RiskMath.formatPrice(newAvg, pos.segment)}",
                    createdAtMillis = nowMillis,
                    updatedAtMillis = nowMillis,
                    lotSize = lotSize,
                    brokerage = avgCharges.brokerage,
                    sttCtt = avgCharges.sttCtt,
                    exchangeCharges = avgCharges.exchangeCharges,
                    sebiCharges = avgCharges.sebiCharges,
                    stampDuty = avgCharges.stampDuty,
                    gst = avgCharges.gst,
                    totalCharges = avgCharges.total
                )
            )

            // Update position quantity and weighted average entry price
            positionDao.upsert(
                pos.copy(
                    quantity = newQty,
                    averageEntryPrice = newAvg,
                    lotSize = lotSize,
                    updatedAtMillis = nowMillis
                )
            )

            // Sync linked pending SL & Target orders to the new total quantity
            listOfNotNull(pos.slOrderId, pos.targetOrderId).forEach { legId ->
                orderDao.getById(legId)?.takeIf { it.status == OrderStatus.PENDING }?.let { leg ->
                    orderDao.upsert(leg.copy(quantity = newQty, lotSize = lotSize, updatedAtMillis = nowMillis))
                }
            }

            accountDao.updateBalances(
                margin = account.availableMargin.subtract(extraMargin).max(BigDecimal.ZERO),
                realised = account.realisedPnl
            )

            val msg = "Averaged ${pos.symbol}: +${RiskMath.formatQtyWithLots(additionalQuantity, lotSize)} @ ${RiskMath.formatPrice(fillPrice, pos.segment)} → New Avg ${RiskMath.formatPrice(newAvg, pos.segment)}"
            result = Result.success(msg)
        }
        result.getOrNull()?.let { msg ->
            notificationManager?.notifyTradeActivity("Position Averaged", msg)
        }
        return result
    }

    suspend fun addCapital(amount: BigDecimal, note: String = "Capital top-up"): Result<String> {
        if (amount <= BigDecimal.ZERO) {
            return Result.failure(IllegalArgumentException("Amount must be greater than zero"))
        }
        val rounded = amount.setScale(2, java.math.RoundingMode.HALF_UP)
        val now = System.currentTimeMillis()
        var newBalance = BigDecimal.ZERO
        database.withTransaction {
            val account = accountDao.getAccount() ?: SeedData.defaultAccount()
            val updatedCapital = account.virtualCapital.add(rounded)
            val updatedMargin = account.availableMargin.add(rounded)
            newBalance = updatedCapital
            accountDao.upsert(
                account.copy(
                    virtualCapital = updatedCapital,
                    availableMargin = updatedMargin
                )
            )
            database.capitalTransactionDao().insert(
                com.example.data.local.CapitalTransactionEntity(
                    id = UUID.randomUUID().toString(),
                    type = com.example.domain.model.CapitalTransactionType.ADD,
                    amount = rounded,
                    balanceAfter = updatedCapital,
                    note = note.ifBlank { "Added virtual funds" },
                    timestampMillis = now
                )
            )
        }
        val msg = "Added ${RiskMath.formatPortfolioInr(rounded)} • Capital now ${RiskMath.formatPortfolioInr(newBalance)}"
        notificationManager?.notifyTradeActivity("Funds Added", msg)
        return Result.success(msg)
    }

    suspend fun reduceCapital(amount: BigDecimal, note: String = "Capital withdrawal"): Result<String> {
        if (amount <= BigDecimal.ZERO) {
            return Result.failure(IllegalArgumentException("Amount must be greater than zero"))
        }
        val rounded = amount.setScale(2, java.math.RoundingMode.HALF_UP)
        val now = System.currentTimeMillis()
        var result: Result<String> = Result.failure(IllegalStateException("Insufficient available margin"))
        database.withTransaction {
            val account = accountDao.getAccount() ?: SeedData.defaultAccount()
            if (rounded > account.availableMargin) {
                result = Result.failure(
                    IllegalStateException("Cannot withdraw more than available free margin (${RiskMath.formatPortfolioInr(account.availableMargin)})")
                )
                return@withTransaction
            }
            val updatedCapital = account.virtualCapital.subtract(rounded).max(BigDecimal.ZERO)
            val updatedMargin = account.availableMargin.subtract(rounded).max(BigDecimal.ZERO)
            accountDao.upsert(
                account.copy(
                    virtualCapital = updatedCapital,
                    availableMargin = updatedMargin
                )
            )
            database.capitalTransactionDao().insert(
                com.example.data.local.CapitalTransactionEntity(
                    id = UUID.randomUUID().toString(),
                    type = com.example.domain.model.CapitalTransactionType.REDUCE,
                    amount = rounded,
                    balanceAfter = updatedCapital,
                    note = note.ifBlank { "Withdrew virtual funds" },
                    timestampMillis = now
                )
            )
            result = Result.success("Reduced ${RiskMath.formatPortfolioInr(rounded)} • Capital now ${RiskMath.formatPortfolioInr(updatedCapital)}")
        }
        result.getOrNull()?.let { msg ->
            notificationManager?.notifyTradeActivity("Funds Reduced", msg)
        }
        return result
    }

    suspend fun triggerSimulatedSquareOff(): Int = executionEngine.triggerSimulatedSquareOff()

    suspend fun updateJournalNotes(entryId: String, notes: String) {
        tradeJournalDao.updateNotes(entryId, notes)
    }

    suspend fun resetPaperAccount(newCapital: BigDecimal = SeedData.INITIAL_CAPITAL) {
        val targetCapital = newCapital.max(BigDecimal("1000.00")).setScale(2, java.math.RoundingMode.HALF_UP)
        val now = System.currentTimeMillis()
        database.withTransaction {
            positionDao.clearAll()
            orderDao.clearAll()
            ocoLinkDao.clearAll()
            tradeJournalDao.clearAll()
            accountDao.upsert(
                com.example.data.local.AccountStateEntity(
                    id = 1,
                    virtualCapital = targetCapital,
                    availableMargin = targetCapital,
                    realisedPnl = BigDecimal.ZERO
                )
            )
            database.capitalTransactionDao().insert(
                com.example.data.local.CapitalTransactionEntity(
                    id = UUID.randomUUID().toString(),
                    type = com.example.domain.model.CapitalTransactionType.RESET,
                    amount = targetCapital,
                    balanceAfter = targetCapital,
                    note = "Paper account reset to ${RiskMath.formatPortfolioInr(targetCapital)}",
                    timestampMillis = now
                )
            )
        }
        notificationManager?.notifyTradeActivity(
            title = "Paper Account Reset",
            body = "Account reset to ${RiskMath.formatPortfolioInr(targetCapital)}"
        )
    }
}
