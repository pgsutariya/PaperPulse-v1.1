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
class TradingRepository @Inject constructor(
    private val database: PaperPulseDatabase,
    private val instrumentDao: InstrumentDao,
    private val orderDao: OrderDao,
    private val positionDao: PositionDao,
    private val ocoLinkDao: OcoLinkDao,
    private val tradeJournalDao: TradeJournalDao,
    private val accountDao: AccountDao,
    private val prefsRepository: TradingPreferencesRepository,
    private val ledgerService: TradingLedgerService,
    private val executionEngine: ExecutionEngine
) {
    val orders: Flow<List<Order>> = orderDao.observeAll().map { list -> list.map { it.toDomain() } }
    val positions: Flow<List<Position>> = positionDao.observeAll().map { list -> list.map { it.toDomain() } }
    val journalEntries: Flow<List<TradeJournalEntry>> = tradeJournalDao.observeAll().map { list -> list.map { it.toDomain() } }
    val accountState: Flow<AccountState> = accountDao.observeAccount().map { (it ?: SeedData.defaultAccount()).toDomain() }

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
            MarketClock.isIntradaySquareOffTime(instrument.segment, nowMillis)
        ) {
            return Result.failure(
                IllegalStateException("Intraday (MIS) orders are blocked during the square-off window — use Carry Forward")
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
            nowMillis = nowMillis
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
            updatedAtMillis = nowMillis
        )

        val immediateFill = request.orderType == OrderType.MARKET ||
            (request.orderType == OrderType.BRACKET && request.limitPrice == null)

        return if (immediateFill) {
            val filled = ledgerService.fillEntryOrder(entryOrder, instrument.ltp, instrument, config, nowMillis)
            if (filled) {
                Result.success("Executed ${request.side.name} ${request.quantity} ${instrument.symbol}")
            } else {
                Result.failure(IllegalStateException("Order rejected at fill: insufficient margin"))
            }
        } else {
            orderDao.upsert(entryOrder)
            Result.success("Placed ${request.orderType.label} order (${request.validityType.label})")
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
        return Result.success(Unit)
    }

    suspend fun triggerSimulatedSquareOff(): Int = executionEngine.triggerSimulatedSquareOff()

    suspend fun updateJournalNotes(entryId: String, notes: String) {
        tradeJournalDao.updateNotes(entryId, notes)
    }

    suspend fun resetPaperAccount() {
        database.withTransaction {
            positionDao.clearAll()
            orderDao.clearAll()
            ocoLinkDao.clearAll()
            tradeJournalDao.clearAll()
            accountDao.upsert(SeedData.defaultAccount())
        }
    }
}
