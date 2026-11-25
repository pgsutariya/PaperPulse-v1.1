package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.domain.model.AccountState
import com.example.domain.model.ExitReason
import com.example.domain.model.Instrument
import com.example.domain.model.InstrumentType
import com.example.domain.model.MarketSegment
import com.example.domain.model.OptionType
import com.example.domain.model.Order
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
import java.math.BigDecimal

@Entity(tableName = "instruments")
data class InstrumentEntity(
    @PrimaryKey val symbol: String,
    val displayName: String,
    val segment: MarketSegment,
    val type: InstrumentType,
    val lotSize: Int,
    val tickSize: BigDecimal,
    val ltp: BigDecimal,
    val previousClose: BigDecimal,
    val dayHigh: BigDecimal,
    val dayLow: BigDecimal,
    val volume: Long,
    val expiryLabel: String?,
    val strikePrice: BigDecimal?,
    val optionType: OptionType?,
    val supportsIntraday: Boolean,
    val supportsCarryForward: Boolean,
    val sparkline: List<BigDecimal>,
    val lastTickDirection: Int
)

fun InstrumentEntity.toDomain(): Instrument = Instrument(
    symbol = symbol,
    displayName = displayName,
    segment = segment,
    type = type,
    lotSize = lotSize,
    tickSize = tickSize,
    ltp = ltp,
    previousClose = previousClose,
    dayHigh = dayHigh,
    dayLow = dayLow,
    volume = volume,
    expiryLabel = expiryLabel,
    strikePrice = strikePrice,
    optionType = optionType,
    supportsIntraday = supportsIntraday,
    supportsCarryForward = supportsCarryForward,
    sparkline = sparkline,
    lastTickDirection = lastTickDirection
)

fun Instrument.toEntity(): InstrumentEntity = InstrumentEntity(
    symbol = symbol,
    displayName = displayName,
    segment = segment,
    type = type,
    lotSize = lotSize,
    tickSize = tickSize,
    ltp = ltp,
    previousClose = previousClose,
    dayHigh = dayHigh,
    dayLow = dayLow,
    volume = volume,
    expiryLabel = expiryLabel,
    strikePrice = strikePrice,
    optionType = optionType,
    supportsIntraday = supportsIntraday,
    supportsCarryForward = supportsCarryForward,
    sparkline = sparkline,
    lastTickDirection = lastTickDirection
)

@Entity(tableName = "orders")
data class OrderEntity(
    @PrimaryKey val id: String,
    val symbol: String,
    val segment: MarketSegment,
    val instrumentType: InstrumentType,
    val side: OrderSide,
    val orderType: OrderType,
    val productType: ProductType,
    val quantity: Int,
    val limitPrice: BigDecimal?,
    val triggerPrice: BigDecimal?,
    val filledPrice: BigDecimal?,
    val status: OrderStatus,
    val validityType: ValidityType,
    val gttDays: Int?,
    val expiresAtMillis: Long,
    val validityState: ValidityState,
    val ocoGroupId: String?,
    val parentOrderId: String?,
    val legRole: String?,
    val attachedSlMode: RiskInputMode?,
    val attachedSlValue: BigDecimal?,
    val attachedTargetMode: RiskInputMode?,
    val attachedTargetValue: BigDecimal?,
    val attachedTslEnabled: Boolean,
    val attachedTslMode: TrailMode?,
    val attachedTslValue: BigDecimal?,
    val attachedTrailingTargetEnabled: Boolean,
    val attachedTrailingTargetMode: TrailMode?,
    val attachedTrailingTargetValue: BigDecimal?,
    val statusReason: String?,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val lotSize: Int = 1,
    val brokerage: BigDecimal = BigDecimal.ZERO,
    val sttCtt: BigDecimal = BigDecimal.ZERO,
    val exchangeCharges: BigDecimal = BigDecimal.ZERO,
    val sebiCharges: BigDecimal = BigDecimal.ZERO,
    val stampDuty: BigDecimal = BigDecimal.ZERO,
    val gst: BigDecimal = BigDecimal.ZERO,
    val totalCharges: BigDecimal = BigDecimal.ZERO
)

fun OrderEntity.toDomain(): Order = Order(
    id = id,
    symbol = symbol,
    segment = segment,
    instrumentType = instrumentType,
    side = side,
    orderType = orderType,
    productType = productType,
    quantity = quantity,
    limitPrice = limitPrice,
    triggerPrice = triggerPrice,
    filledPrice = filledPrice,
    status = status,
    validityType = validityType,
    gttDays = gttDays,
    expiresAtMillis = expiresAtMillis,
    validityState = validityState,
    ocoGroupId = ocoGroupId,
    parentOrderId = parentOrderId,
    legRole = legRole,
    attachedSlMode = attachedSlMode,
    attachedSlValue = attachedSlValue,
    attachedTargetMode = attachedTargetMode,
    attachedTargetValue = attachedTargetValue,
    attachedTslEnabled = attachedTslEnabled,
    attachedTslMode = attachedTslMode,
    attachedTslValue = attachedTslValue,
    attachedTrailingTargetEnabled = attachedTrailingTargetEnabled,
    attachedTrailingTargetMode = attachedTrailingTargetMode,
    attachedTrailingTargetValue = attachedTrailingTargetValue,
    statusReason = statusReason,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
    lotSize = lotSize,
    brokerage = brokerage,
    sttCtt = sttCtt,
    exchangeCharges = exchangeCharges,
    sebiCharges = sebiCharges,
    stampDuty = stampDuty,
    gst = gst,
    totalCharges = totalCharges
)

fun Order.toEntity(): OrderEntity = OrderEntity(
    id = id,
    symbol = symbol,
    segment = segment,
    instrumentType = instrumentType,
    side = side,
    orderType = orderType,
    productType = productType,
    quantity = quantity,
    limitPrice = limitPrice,
    triggerPrice = triggerPrice,
    filledPrice = filledPrice,
    status = status,
    validityType = validityType,
    gttDays = gttDays,
    expiresAtMillis = expiresAtMillis,
    validityState = validityState,
    ocoGroupId = ocoGroupId,
    parentOrderId = parentOrderId,
    legRole = legRole,
    attachedSlMode = attachedSlMode,
    attachedSlValue = attachedSlValue,
    attachedTargetMode = attachedTargetMode,
    attachedTargetValue = attachedTargetValue,
    attachedTslEnabled = attachedTslEnabled,
    attachedTslMode = attachedTslMode,
    attachedTslValue = attachedTslValue,
    attachedTrailingTargetEnabled = attachedTrailingTargetEnabled,
    attachedTrailingTargetMode = attachedTrailingTargetMode,
    attachedTrailingTargetValue = attachedTrailingTargetValue,
    statusReason = statusReason,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
    lotSize = lotSize,
    brokerage = brokerage,
    sttCtt = sttCtt,
    exchangeCharges = exchangeCharges,
    sebiCharges = sebiCharges,
    stampDuty = stampDuty,
    gst = gst,
    totalCharges = totalCharges
)

@Entity(tableName = "positions")
data class PositionEntity(
    @PrimaryKey val id: String,
    val symbol: String,
    val displayName: String,
    val segment: MarketSegment,
    val instrumentType: InstrumentType,
    val side: OrderSide,
    val productType: ProductType,
    val quantity: Int,
    val averageEntryPrice: BigDecimal,
    val activeSlPrice: BigDecimal?,
    val activeTargetPrice: BigDecimal?,
    val slOrderId: String?,
    val targetOrderId: String?,
    val ocoGroupId: String?,
    val isTslEnabled: Boolean,
    val tslTrailMode: TrailMode,
    val tslTrailValue: BigDecimal,
    val tslBestPrice: BigDecimal,
    val isTrailingTargetEnabled: Boolean,
    val trailingTargetTrailMode: TrailMode,
    val trailingTargetTrailValue: BigDecimal,
    val trailingTargetActivated: Boolean,
    val trailingTargetPeakPrice: BigDecimal?,
    val trailingTargetExitTrigger: BigDecimal?,
    val validityType: ValidityType,
    val gttDays: Int,
    val expiresAtMillis: Long,
    val openedAtMillis: Long,
    val updatedAtMillis: Long,
    val lotSize: Int = 1
)

fun PositionEntity.toDomain(): Position = Position(
    id = id,
    symbol = symbol,
    displayName = displayName,
    segment = segment,
    instrumentType = instrumentType,
    side = side,
    productType = productType,
    quantity = quantity,
    averageEntryPrice = averageEntryPrice,
    activeSlPrice = activeSlPrice,
    activeTargetPrice = activeTargetPrice,
    slOrderId = slOrderId,
    targetOrderId = targetOrderId,
    ocoGroupId = ocoGroupId,
    isTslEnabled = isTslEnabled,
    tslTrailMode = tslTrailMode,
    tslTrailValue = tslTrailValue,
    tslBestPrice = tslBestPrice,
    isTrailingTargetEnabled = isTrailingTargetEnabled,
    trailingTargetTrailMode = trailingTargetTrailMode,
    trailingTargetTrailValue = trailingTargetTrailValue,
    trailingTargetActivated = trailingTargetActivated,
    trailingTargetPeakPrice = trailingTargetPeakPrice,
    trailingTargetExitTrigger = trailingTargetExitTrigger,
    validityType = validityType,
    gttDays = gttDays,
    expiresAtMillis = expiresAtMillis,
    openedAtMillis = openedAtMillis,
    updatedAtMillis = updatedAtMillis,
    lotSize = lotSize
)

fun Position.toEntity(): PositionEntity = PositionEntity(
    id = id,
    symbol = symbol,
    displayName = displayName,
    segment = segment,
    instrumentType = instrumentType,
    side = side,
    productType = productType,
    quantity = quantity,
    averageEntryPrice = averageEntryPrice,
    activeSlPrice = activeSlPrice,
    activeTargetPrice = activeTargetPrice,
    slOrderId = slOrderId,
    targetOrderId = targetOrderId,
    ocoGroupId = ocoGroupId,
    isTslEnabled = isTslEnabled,
    tslTrailMode = tslTrailMode,
    tslTrailValue = tslTrailValue,
    tslBestPrice = tslBestPrice,
    isTrailingTargetEnabled = isTrailingTargetEnabled,
    trailingTargetTrailMode = trailingTargetTrailMode,
    trailingTargetTrailValue = trailingTargetTrailValue,
    trailingTargetActivated = trailingTargetActivated,
    trailingTargetPeakPrice = trailingTargetPeakPrice,
    trailingTargetExitTrigger = trailingTargetExitTrigger,
    validityType = validityType,
    gttDays = gttDays,
    expiresAtMillis = expiresAtMillis,
    openedAtMillis = openedAtMillis,
    updatedAtMillis = updatedAtMillis,
    lotSize = lotSize
)

@Entity(tableName = "oco_links")
data class OcoLinkEntity(
    @PrimaryKey val ocoGroupId: String,
    val positionId: String,
    val slOrderId: String?,
    val targetOrderId: String?,
    val isActive: Boolean
)

@Entity(tableName = "trade_journal")
data class TradeJournalEntity(
    @PrimaryKey val id: String,
    val symbol: String,
    val segment: MarketSegment,
    val instrumentType: InstrumentType,
    val side: OrderSide,
    val productType: ProductType,
    val quantity: Int,
    val entryPrice: BigDecimal,
    val exitPrice: BigDecimal,
    val grossPnl: BigDecimal,
    val charges: BigDecimal,
    val netPnl: BigDecimal,
    val exitReason: ExitReason,
    val notes: String,
    val openedAtMillis: Long,
    val closedAtMillis: Long,
    val lotSize: Int = 1,
    val brokerage: BigDecimal = BigDecimal.ZERO,
    val sttCtt: BigDecimal = BigDecimal.ZERO,
    val exchangeCharges: BigDecimal = BigDecimal.ZERO,
    val sebiCharges: BigDecimal = BigDecimal.ZERO,
    val stampDuty: BigDecimal = BigDecimal.ZERO,
    val gst: BigDecimal = BigDecimal.ZERO,
    val tagsCsv: String = ""
)

fun parseTagsCsv(csv: String): List<String> {
    if (csv.isBlank()) return emptyList()
    val seen = LinkedHashSet<String>()
    val result = mutableListOf<String>()
    csv.split("|", ",").forEach { raw ->
        val cleaned = raw.trim()
        if (cleaned.isNotEmpty() && seen.add(cleaned.lowercase())) {
            result.add(cleaned)
        }
    }
    return result
}

fun encodeTagsCsv(tags: List<String>): String {
    val seen = LinkedHashSet<String>()
    val cleaned = mutableListOf<String>()
    tags.forEach { raw ->
        val norm = raw.trim().replace("|", " ").replace(",", " ").replace(Regex("\\s+"), " ").take(28)
        if (norm.isNotEmpty() && seen.add(norm.lowercase())) {
            cleaned.add(norm)
        }
    }
    return cleaned.joinToString("|")
}

fun TradeJournalEntity.toDomain(): TradeJournalEntry = TradeJournalEntry(
    id = id,
    symbol = symbol,
    segment = segment,
    instrumentType = instrumentType,
    side = side,
    productType = productType,
    quantity = quantity,
    entryPrice = entryPrice,
    exitPrice = exitPrice,
    grossPnl = grossPnl,
    charges = charges,
    netPnl = netPnl,
    exitReason = exitReason,
    notes = notes,
    openedAtMillis = openedAtMillis,
    closedAtMillis = closedAtMillis,
    lotSize = lotSize,
    brokerage = brokerage,
    sttCtt = sttCtt,
    exchangeCharges = exchangeCharges,
    sebiCharges = sebiCharges,
    stampDuty = stampDuty,
    gst = gst,
    tags = parseTagsCsv(tagsCsv)
)

@Entity(tableName = "capital_transactions")
data class CapitalTransactionEntity(
    @PrimaryKey val id: String,
    val type: com.example.domain.model.CapitalTransactionType,
    val amount: BigDecimal,
    val balanceAfter: BigDecimal,
    val note: String,
    val timestampMillis: Long,
    val currency: String = "INR"
)

fun CapitalTransactionEntity.toDomain(): com.example.domain.model.CapitalTransaction =
    com.example.domain.model.CapitalTransaction(
        id = id,
        type = type,
        amount = amount,
        balanceAfter = balanceAfter,
        note = note,
        timestampMillis = timestampMillis,
        currency = currency
    )

@Entity(tableName = "account_state")
data class AccountStateEntity(
    @PrimaryKey val id: Int = 1,
    val virtualCapital: BigDecimal,
    val availableMargin: BigDecimal,
    val realisedPnl: BigDecimal,
    val cryptoVirtualCapital: BigDecimal = BigDecimal("10000.00"),
    val cryptoAvailableMargin: BigDecimal = BigDecimal("10000.00"),
    val cryptoRealisedPnl: BigDecimal = BigDecimal.ZERO
)

fun AccountStateEntity.toDomain(): AccountState = AccountState(
    virtualCapital = virtualCapital,
    availableMargin = availableMargin,
    realisedPnl = realisedPnl,
    cryptoVirtualCapital = cryptoVirtualCapital,
    cryptoAvailableMargin = cryptoAvailableMargin,
    cryptoRealisedPnl = cryptoRealisedPnl
)

@Entity(
    tableName = "candles",
    primaryKeys = ["symbol", "timeframe", "timestampMillis"]
)
data class CandleEntity(
    val symbol: String,
    val timeframe: com.example.domain.signal.Timeframe,
    val timestampMillis: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double
)

fun CandleEntity.toDomain(): com.example.domain.signal.Candle = com.example.domain.signal.Candle(
    timestamp = java.time.Instant.ofEpochMilli(timestampMillis),
    open = open,
    high = high,
    low = low,
    close = close,
    volume = volume
)

fun com.example.domain.signal.Candle.toEntity(
    symbol: String,
    timeframe: com.example.domain.signal.Timeframe
): CandleEntity = CandleEntity(
    symbol = symbol,
    timeframe = timeframe,
    timestampMillis = timestamp.toEpochMilli(),
    open = open,
    high = high,
    low = low,
    close = close,
    volume = volume
)

@Entity(tableName = "signals")
data class SignalEntity(
    @PrimaryKey val id: String,
    val symbol: String,
    val segment: MarketSegment,
    val strategy: com.example.domain.signal.SignalStrategy,
    val direction: com.example.domain.signal.SignalDirection,
    val timeframe: com.example.domain.signal.Timeframe,
    val entryPrice: BigDecimal,
    val stopLoss: BigDecimal,
    val target1: BigDecimal,
    val target2: BigDecimal,
    val riskRewardRatio: Double,
    val confidence: com.example.domain.signal.SignalConfidence,
    val reasons: List<String>,
    val generatedAtMillis: Long,
    val expiresAtMillis: Long?,
    val userStatus: String = "ACTIVE", // ACTIVE, ACCEPTED, DISMISSED
    val instrumentType: InstrumentType = InstrumentType.STOCK
)

fun SignalEntity.toDomain(): com.example.domain.signal.TradeSignal = com.example.domain.signal.TradeSignal(
    id = id,
    symbol = symbol,
    segment = segment,
    strategy = strategy,
    direction = direction,
    timeframe = timeframe,
    entryPrice = entryPrice,
    stopLoss = stopLoss,
    target1 = target1,
    target2 = target2,
    riskRewardRatio = riskRewardRatio,
    confidence = confidence,
    reasons = reasons,
    generatedAt = java.time.Instant.ofEpochMilli(generatedAtMillis),
    expiresAt = expiresAtMillis?.let { java.time.Instant.ofEpochMilli(it) },
    instrumentType = instrumentType
)

fun com.example.domain.signal.TradeSignal.toEntity(userStatus: String = "ACTIVE"): SignalEntity = SignalEntity(
    id = id,
    symbol = symbol,
    segment = segment,
    strategy = strategy,
    direction = direction,
    timeframe = timeframe,
    entryPrice = entryPrice,
    stopLoss = stopLoss,
    target1 = target1,
    target2 = target2,
    riskRewardRatio = riskRewardRatio,
    confidence = confidence,
    reasons = reasons,
    generatedAtMillis = generatedAt.toEpochMilli(),
    expiresAtMillis = expiresAt?.toEpochMilli(),
    userStatus = userStatus,
    instrumentType = instrumentType
)

@Entity(tableName = "saved_signals")
data class SavedSignalEntity(
    @PrimaryKey val id: String,
    val symbol: String,
    val segment: MarketSegment,
    val strategy: com.example.domain.signal.SignalStrategy,
    val direction: com.example.domain.signal.SignalDirection,
    val timeframe: com.example.domain.signal.Timeframe,
    val entryPrice: BigDecimal,
    val stopLoss: BigDecimal,
    val target1: BigDecimal,
    val target2: BigDecimal,
    val riskRewardRatio: Double,
    val confidence: com.example.domain.signal.SignalConfidence,
    val reasons: List<String>,
    val generatedAtMillis: Long,
    val expiresAtMillis: Long?,
    val savedAtMillis: Long,
    val instrumentType: InstrumentType = InstrumentType.STOCK
)

fun SavedSignalEntity.toDomain(): com.example.domain.signal.TradeSignal = com.example.domain.signal.TradeSignal(
    id = id,
    symbol = symbol,
    segment = segment,
    strategy = strategy,
    direction = direction,
    timeframe = timeframe,
    entryPrice = entryPrice,
    stopLoss = stopLoss,
    target1 = target1,
    target2 = target2,
    riskRewardRatio = riskRewardRatio,
    confidence = confidence,
    reasons = reasons,
    generatedAt = java.time.Instant.ofEpochMilli(generatedAtMillis),
    expiresAt = expiresAtMillis?.let { java.time.Instant.ofEpochMilli(it) },
    instrumentType = instrumentType
)

fun com.example.domain.signal.TradeSignal.toSavedEntity(savedAtMillis: Long = System.currentTimeMillis()): SavedSignalEntity = SavedSignalEntity(
    id = id,
    symbol = symbol,
    segment = segment,
    strategy = strategy,
    direction = direction,
    timeframe = timeframe,
    entryPrice = entryPrice,
    stopLoss = stopLoss,
    target1 = target1,
    target2 = target2,
    riskRewardRatio = riskRewardRatio,
    confidence = confidence,
    reasons = reasons,
    generatedAtMillis = generatedAt.toEpochMilli(),
    expiresAtMillis = expiresAt?.toEpochMilli(),
    savedAtMillis = savedAtMillis,
    instrumentType = instrumentType
)

@Entity(tableName = "signal_records")
data class SignalRecordEntity(
    @PrimaryKey val id: String,
    val symbol: String,
    val segment: MarketSegment,
    val instrumentType: InstrumentType,
    val strategy: com.example.domain.signal.SignalStrategy,
    val direction: com.example.domain.signal.SignalDirection,
    val timeframe: com.example.domain.signal.Timeframe,
    val entryPrice: BigDecimal,
    val stopLoss: BigDecimal,
    val target1: BigDecimal,
    val target2: BigDecimal,
    val resolvedPrice: BigDecimal,
    val outcome: com.example.domain.signal.SignalOutcome,
    val pnlPercent: Double,
    val confidence: com.example.domain.signal.SignalConfidence,
    val generatedAtMillis: Long,
    val resolvedAtMillis: Long
)

fun SignalRecordEntity.toDomain(): com.example.domain.signal.SignalRecord =
    com.example.domain.signal.SignalRecord(
        id = id,
        symbol = symbol,
        segment = segment,
        instrumentType = instrumentType,
        strategy = strategy,
        direction = direction,
        timeframe = timeframe,
        entryPrice = entryPrice,
        stopLoss = stopLoss,
        target1 = target1,
        target2 = target2,
        resolvedPrice = resolvedPrice,
        outcome = outcome,
        pnlPercent = pnlPercent,
        confidence = confidence,
        generatedAtMillis = generatedAtMillis,
        resolvedAtMillis = resolvedAtMillis
    )

