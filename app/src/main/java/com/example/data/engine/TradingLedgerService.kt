package com.example.data.engine

import androidx.room.withTransaction
import com.example.data.local.AccountDao
import com.example.data.local.InstrumentDao
import com.example.data.local.InstrumentEntity
import com.example.data.local.OcoLinkDao
import com.example.data.local.OcoLinkEntity
import com.example.data.local.OrderDao
import com.example.data.local.OrderEntity
import com.example.data.local.PaperPulseDatabase
import com.example.data.local.PositionDao
import com.example.data.local.PositionEntity
import com.example.data.local.SeedData
import com.example.data.local.TradeJournalDao
import com.example.data.local.TradeJournalEntity
import com.example.domain.engine.RiskMath
import com.example.domain.model.ExitReason
import com.example.domain.model.InstrumentType
import com.example.domain.model.OrderSide
import com.example.domain.model.OrderStatus
import com.example.domain.model.OrderType
import com.example.domain.model.ProductType
import com.example.domain.model.TrailMode
import com.example.domain.model.TradingConfig
import com.example.domain.model.ValidityState
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TradingLedgerService @Inject constructor(
    private val database: PaperPulseDatabase,
    private val instrumentDao: InstrumentDao,
    private val orderDao: OrderDao,
    private val positionDao: PositionDao,
    private val ocoLinkDao: OcoLinkDao,
    private val tradeJournalDao: TradeJournalDao,
    private val accountDao: AccountDao
) {

    fun computeRequiredMargin(
        productType: ProductType,
        price: BigDecimal,
        quantity: Int,
        instrumentType: InstrumentType
    ): BigDecimal {
        val notional = price.multiply(BigDecimal(quantity))
        return when {
            productType == ProductType.INTRADAY && instrumentType != InstrumentType.OPTIONS -> {
                notional.multiply(BigDecimal("0.25")).setScale(2, RoundingMode.HALF_UP)
            }
            else -> notional.setScale(2, RoundingMode.HALF_UP)
        }
    }

    /**
     * Fills an entry order and opens the position with its protective legs.
     * Returns false (and cancels the order) when the account no longer has the margin at fill time —
     * pending LIMIT/SL orders reserve nothing when placed, so margin must be re-checked here.
     */
    suspend fun fillEntryOrder(
        order: OrderEntity,
        rawExecutionPrice: BigDecimal,
        instrument: InstrumentEntity,
        config: TradingConfig,
        nowMillis: Long
    ): Boolean {
        return database.withTransaction {
            val fillPrice = RiskMath.applySlippage(
                price = rawExecutionPrice,
                side = order.side,
                slippageBps = config.slippageBps,
                tickSize = instrument.tickSize
            )

            val account = accountDao.getAccount() ?: SeedData.defaultAccount().also {
                accountDao.upsert(it)
            }
            val requiredMargin = computeRequiredMargin(
                productType = order.productType,
                price = fillPrice,
                quantity = order.quantity,
                instrumentType = order.instrumentType
            )
            if (account.availableMargin < requiredMargin) {
                orderDao.upsert(
                    order.copy(
                        status = OrderStatus.CANCELLED,
                        validityState = ValidityState.CANCELLED,
                        statusReason = "Rejected at fill: insufficient margin (need ${RiskMath.formatPortfolioInr(requiredMargin)})",
                        updatedAtMillis = nowMillis
                    )
                )
                return@withTransaction false
            }

            val updatedOrder = order.copy(
                filledPrice = fillPrice,
                status = OrderStatus.EXECUTED,
                validityState = ValidityState.TRIGGERED,
                statusReason = "Filled @ ${RiskMath.formatPrice(fillPrice, instrument.segment)}",
                updatedAtMillis = nowMillis
            )
            orderDao.upsert(updatedOrder)

            val resolvedSl = if (order.attachedSlMode != null && order.attachedSlValue != null) {
                RiskMath.resolveStopLossPrice(
                    entryPrice = fillPrice,
                    side = order.side,
                    mode = order.attachedSlMode,
                    value = order.attachedSlValue,
                    tickSize = instrument.tickSize
                )
            } else null

            val resolvedTarget = if (order.attachedTargetMode != null && order.attachedTargetValue != null) {
                RiskMath.resolveTargetPrice(
                    entryPrice = fillPrice,
                    side = order.side,
                    mode = order.attachedTargetMode,
                    value = order.attachedTargetValue,
                    tickSize = instrument.tickSize
                )
            } else null

            val exitSide = if (order.side == OrderSide.BUY) OrderSide.SELL else OrderSide.BUY
            val ocoGroupId = if (resolvedSl != null && resolvedTarget != null) {
                UUID.randomUUID().toString()
            } else null

            val positionId = UUID.randomUUID().toString()
            val slOrderId = resolvedSl?.let { UUID.randomUUID().toString() }
            val targetOrderId = resolvedTarget?.let { UUID.randomUUID().toString() }

            if (slOrderId != null && resolvedSl != null) {
                orderDao.upsert(
                    OrderEntity(
                        id = slOrderId,
                        symbol = order.symbol,
                        segment = order.segment,
                        instrumentType = order.instrumentType,
                        side = exitSide,
                        orderType = OrderType.SL_M,
                        productType = order.productType,
                        quantity = order.quantity,
                        limitPrice = null,
                        triggerPrice = resolvedSl,
                        filledPrice = null,
                        status = OrderStatus.PENDING,
                        validityType = order.validityType,
                        gttDays = order.gttDays,
                        expiresAtMillis = order.expiresAtMillis,
                        validityState = ValidityState.ACTIVE,
                        ocoGroupId = ocoGroupId,
                        parentOrderId = order.id,
                        legRole = "SL",
                        attachedSlMode = null,
                        attachedSlValue = null,
                        attachedTargetMode = null,
                        attachedTargetValue = null,
                        attachedTslEnabled = order.attachedTslEnabled,
                        attachedTslMode = order.attachedTslMode,
                        attachedTslValue = order.attachedTslValue,
                        attachedTrailingTargetEnabled = false,
                        attachedTrailingTargetMode = null,
                        attachedTrailingTargetValue = null,
                        statusReason = if (order.attachedTslEnabled) "Trailing SL Armed" else "Protective SL",
                        createdAtMillis = nowMillis,
                        updatedAtMillis = nowMillis
                    )
                )
            }

            if (targetOrderId != null && resolvedTarget != null) {
                orderDao.upsert(
                    OrderEntity(
                        id = targetOrderId,
                        symbol = order.symbol,
                        segment = order.segment,
                        instrumentType = order.instrumentType,
                        side = exitSide,
                        orderType = OrderType.TGT_M,
                        productType = order.productType,
                        quantity = order.quantity,
                        limitPrice = null,
                        triggerPrice = resolvedTarget,
                        filledPrice = null,
                        status = OrderStatus.PENDING,
                        validityType = order.validityType,
                        gttDays = order.gttDays,
                        expiresAtMillis = order.expiresAtMillis,
                        validityState = ValidityState.ACTIVE,
                        ocoGroupId = ocoGroupId,
                        parentOrderId = order.id,
                        legRole = "TARGET",
                        attachedSlMode = null,
                        attachedSlValue = null,
                        attachedTargetMode = null,
                        attachedTargetValue = null,
                        attachedTslEnabled = false,
                        attachedTslMode = null,
                        attachedTslValue = null,
                        attachedTrailingTargetEnabled = order.attachedTrailingTargetEnabled,
                        attachedTrailingTargetMode = order.attachedTrailingTargetMode,
                        attachedTrailingTargetValue = order.attachedTrailingTargetValue,
                        statusReason = if (order.attachedTrailingTargetEnabled) "Trailing Target Armed" else "Target Order",
                        createdAtMillis = nowMillis,
                        updatedAtMillis = nowMillis
                    )
                )
            }

            if (ocoGroupId != null) {
                ocoLinkDao.upsert(
                    OcoLinkEntity(
                        ocoGroupId = ocoGroupId,
                        positionId = positionId,
                        slOrderId = slOrderId,
                        targetOrderId = targetOrderId,
                        isActive = true
                    )
                )
            }

            positionDao.upsert(
                PositionEntity(
                    id = positionId,
                    symbol = order.symbol,
                    displayName = instrument.displayName,
                    segment = order.segment,
                    instrumentType = order.instrumentType,
                    side = order.side,
                    productType = order.productType,
                    quantity = order.quantity,
                    averageEntryPrice = fillPrice,
                    activeSlPrice = resolvedSl,
                    activeTargetPrice = resolvedTarget,
                    slOrderId = slOrderId,
                    targetOrderId = targetOrderId,
                    ocoGroupId = ocoGroupId,
                    isTslEnabled = order.attachedTslEnabled && resolvedSl != null,
                    tslTrailMode = order.attachedTslMode ?: TrailMode.ABSOLUTE,
                    tslTrailValue = order.attachedTslValue ?: BigDecimal.ZERO,
                    tslBestPrice = fillPrice,
                    isTrailingTargetEnabled = order.attachedTrailingTargetEnabled && resolvedTarget != null,
                    trailingTargetTrailMode = order.attachedTrailingTargetMode ?: TrailMode.ABSOLUTE,
                    trailingTargetTrailValue = order.attachedTrailingTargetValue ?: BigDecimal.ZERO,
                    trailingTargetActivated = false,
                    trailingTargetPeakPrice = null,
                    trailingTargetExitTrigger = null,
                    validityType = order.validityType,
                    gttDays = order.gttDays ?: 7,
                    expiresAtMillis = order.expiresAtMillis,
                    openedAtMillis = nowMillis,
                    updatedAtMillis = nowMillis
                )
            )

            accountDao.updateBalances(
                margin = account.availableMargin.subtract(requiredMargin).max(BigDecimal.ZERO),
                realised = account.realisedPnl
            )
            true
        }
    }

    suspend fun closePosition(
        position: PositionEntity,
        rawExitPrice: BigDecimal,
        exitReason: ExitReason,
        triggeredLegRole: String?,
        config: TradingConfig,
        nowMillis: Long
    ) {
        database.withTransaction {
            val currentPos = positionDao.getById(position.id) ?: return@withTransaction
            val instrument = instrumentDao.getBySymbol(currentPos.symbol)
            val tickSize = instrument?.tickSize ?: BigDecimal("0.05")
            val exitSide = if (currentPos.side == OrderSide.BUY) OrderSide.SELL else OrderSide.BUY

            val exitPrice = RiskMath.applySlippage(
                price = rawExitPrice,
                side = exitSide,
                slippageBps = config.slippageBps,
                tickSize = tickSize
            )

            val isLong = currentPos.side == OrderSide.BUY
            val grossPnl = RiskMath.calculateUnrealisedPnl(
                entryPrice = currentPos.averageEntryPrice,
                ltp = exitPrice,
                quantity = currentPos.quantity,
                isLong = isLong
            )
            val turnover = currentPos.averageEntryPrice.add(exitPrice)
                .multiply(BigDecimal(currentPos.quantity))
            val charges = RiskMath.calculateSimulatedCharges(
                segment = currentPos.segment,
                turnover = turnover,
                flatBrokeragePerOrder = config.flatBrokerage,
                cryptoFeePercent = config.cryptoFeePercent
            )
            val netPnl = grossPnl.subtract(charges).setScale(2, RoundingMode.HALF_UP)

            // Handle OCO leg state transitions
            val slOrder = currentPos.slOrderId?.let { orderDao.getById(it) }
            val targetOrder = currentPos.targetOrderId?.let { orderDao.getById(it) }

            when (triggeredLegRole) {
                "SL" -> {
                    slOrder?.let {
                        orderDao.upsert(
                            it.copy(
                                filledPrice = exitPrice,
                                status = OrderStatus.EXECUTED,
                                validityState = ValidityState.TRIGGERED,
                                statusReason = exitReason.label,
                                updatedAtMillis = nowMillis
                            )
                        )
                    }
                    targetOrder?.takeIf { it.status == OrderStatus.PENDING }?.let {
                        orderDao.upsert(
                            it.copy(
                                status = OrderStatus.CANCELLED,
                                validityState = ValidityState.CANCELLED,
                                statusReason = "Cancelled by OCO (${exitReason.label})",
                                updatedAtMillis = nowMillis
                            )
                        )
                    }
                }
                "TARGET" -> {
                    targetOrder?.let {
                        orderDao.upsert(
                            it.copy(
                                filledPrice = exitPrice,
                                status = OrderStatus.EXECUTED,
                                validityState = ValidityState.TRIGGERED,
                                statusReason = exitReason.label,
                                updatedAtMillis = nowMillis
                            )
                        )
                    }
                    slOrder?.takeIf { it.status == OrderStatus.PENDING }?.let {
                        orderDao.upsert(
                            it.copy(
                                status = OrderStatus.CANCELLED,
                                validityState = ValidityState.CANCELLED,
                                statusReason = "Cancelled by OCO (${exitReason.label})",
                                updatedAtMillis = nowMillis
                            )
                        )
                    }
                }
                else -> {
                    // Manual exit or 15:15 IST square-off: cancel any pending protective legs & log exit order
                    slOrder?.takeIf { it.status == OrderStatus.PENDING }?.let {
                        orderDao.upsert(
                            it.copy(
                                status = OrderStatus.CANCELLED,
                                validityState = ValidityState.CANCELLED,
                                statusReason = "Cancelled (${exitReason.label})",
                                updatedAtMillis = nowMillis
                            )
                        )
                    }
                    targetOrder?.takeIf { it.status == OrderStatus.PENDING }?.let {
                        orderDao.upsert(
                            it.copy(
                                status = OrderStatus.CANCELLED,
                                validityState = ValidityState.CANCELLED,
                                statusReason = "Cancelled (${exitReason.label})",
                                updatedAtMillis = nowMillis
                            )
                        )
                    }
                    orderDao.upsert(
                        OrderEntity(
                            id = UUID.randomUUID().toString(),
                            symbol = currentPos.symbol,
                            segment = currentPos.segment,
                            instrumentType = currentPos.instrumentType,
                            side = exitSide,
                            orderType = OrderType.MARKET,
                            productType = currentPos.productType,
                            quantity = currentPos.quantity,
                            limitPrice = null,
                            triggerPrice = null,
                            filledPrice = exitPrice,
                            status = OrderStatus.EXECUTED,
                            validityType = currentPos.validityType,
                            gttDays = currentPos.gttDays,
                            expiresAtMillis = currentPos.expiresAtMillis,
                            validityState = ValidityState.TRIGGERED,
                            ocoGroupId = null,
                            parentOrderId = null,
                            legRole = "EXIT",
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
                            statusReason = exitReason.label,
                            createdAtMillis = nowMillis,
                            updatedAtMillis = nowMillis
                        )
                    )
                }
            }

            currentPos.ocoGroupId?.let { ocoLinkDao.deactivate(it) }
            positionDao.deleteById(currentPos.id)

            val account = accountDao.getAccount() ?: SeedData.defaultAccount()
            val releasedMargin = computeRequiredMargin(
                productType = currentPos.productType,
                price = currentPos.averageEntryPrice,
                quantity = currentPos.quantity,
                instrumentType = currentPos.instrumentType
            )
            accountDao.updateBalances(
                margin = account.availableMargin.add(releasedMargin).add(netPnl).max(BigDecimal.ZERO),
                realised = account.realisedPnl.add(netPnl)
            )

            tradeJournalDao.insert(
                TradeJournalEntity(
                    id = UUID.randomUUID().toString(),
                    symbol = currentPos.symbol,
                    segment = currentPos.segment,
                    instrumentType = currentPos.instrumentType,
                    side = currentPos.side,
                    productType = currentPos.productType,
                    quantity = currentPos.quantity,
                    entryPrice = currentPos.averageEntryPrice,
                    exitPrice = exitPrice,
                    grossPnl = grossPnl,
                    charges = charges,
                    netPnl = netPnl,
                    exitReason = exitReason,
                    notes = "",
                    openedAtMillis = currentPos.openedAtMillis,
                    closedAtMillis = nowMillis
                )
            )
        }
    }
}
