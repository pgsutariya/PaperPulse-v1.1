package com.example.domain.model

import java.math.BigDecimal

enum class MarketSegment(val label: String, val currencySymbol: String) {
    NSE("NSE", "₹"),
    BSE("BSE", "₹"),
    CRYPTO("CRYPTO", "$"),
    MCX("MCX", "₹")
}

enum class InstrumentType(val shortLabel: String, val fullLabel: String) {
    STOCK("Stock", "Equity / Spot"),
    FUTURES("Fut", "Futures"),
    OPTIONS("Opt", "Options")
}

enum class OptionType {
    CE, PE
}

enum class OrderSide {
    BUY, SELL
}

enum class OrderType(val label: String) {
    MARKET("Market"),
    LIMIT("Limit"),
    SL_M("SL-M"),
    SL_L("SL-L"),
    TGT_M("TGT-M"),
    TGT_L("TGT-L"),
    BRACKET("Bracket")
}

enum class ProductType(val label: String, val shortCode: String) {
    INTRADAY("Intraday (MIS)", "MIS"),
    CARRY_FORWARD("Carry Forward (CNC/NRML)", "CF")
}

enum class OrderStatus {
    PENDING,
    EXECUTED,
    CANCELLED
}

enum class ValidityType(val label: String) {
    DAY("DAY"),
    GTT("GTT"),
    GTD("GTD")
}

enum class ValidityState {
    ACTIVE,
    TRIGGERED,
    EXPIRED,
    CANCELLED
}

enum class RiskInputMode(val label: String) {
    PRICE("Fixed Price"),
    PERCENT("% Move")
}

enum class TrailMode(val label: String) {
    ABSOLUTE("Abs (₹/$)"),
    PERCENT("% Trail")
}

enum class ExitReason(val label: String) {
    TARGET_HIT("Target Hit"),
    SL_HIT("Stop Loss Hit"),
    TSL_HIT("Trailing SL Hit"),
    TRAILING_TARGET_HIT("Trailing Target Hit"),
    INTRADAY_SQUARE_OFF("Intraday Auto Square-Off"),
    MANUAL_EXIT("Manual Market Exit")
}

enum class FeedSourceType(val label: String) {
    SIMULATED("Simulated Feed"),
    BROKER_ANGEL_ONE("Angel One SmartAPI (Stub)"),
    BROKER_UPSTOX("Upstox WS (Stub)")
}

sealed interface FeedConnectionState {
    data class Connected(val source: FeedSourceType, val detail: String) : FeedConnectionState
    data class Connecting(val source: FeedSourceType) : FeedConnectionState
    data class Disconnected(val source: FeedSourceType, val reason: String) : FeedConnectionState
}

data class Instrument(
    val symbol: String,
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
    val expiryLabel: String? = null,
    val strikePrice: BigDecimal? = null,
    val optionType: OptionType? = null,
    val supportsIntraday: Boolean = true,
    val supportsCarryForward: Boolean = true,
    val sparkline: List<BigDecimal> = emptyList(),
    val lastTickDirection: Int = 0 // -1 down, 0 flat, 1 up
) {
    val change: BigDecimal get() = ltp.subtract(previousClose)
    val changePercent: BigDecimal
        get() = if (previousClose.compareTo(BigDecimal.ZERO) == 0) {
            BigDecimal.ZERO
        } else {
            change.multiply(BigDecimal(100))
                .divide(previousClose, 2, java.math.RoundingMode.HALF_UP)
        }
}

data class PriceTick(
    val symbol: String,
    val ltp: BigDecimal,
    val timestampMillis: Long,
    val previousClose: BigDecimal? = null,
    val dayHigh: BigDecimal? = null,
    val dayLow: BigDecimal? = null,
    val volume: Long? = null,
    val sparkline: List<BigDecimal>? = null,
    /** Size of this individual trade when the feed streams trades (e.g. Binance), not cumulative. */
    val tradeQuantity: Double? = null,
    /** true = buyer was the aggressor, false = seller; null when unknown. Drives true order-flow delta. */
    val buyerInitiated: Boolean? = null
)

data class Order(
    val id: String,
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
    val legRole: String?, // ENTRY, SL, TARGET, EXIT, AVERAGE
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

data class Position(
    val id: String,
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

data class TradeJournalEntry(
    val id: String,
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
    val tags: List<String> = emptyList()
)

enum class CapitalTransactionType(val label: String) {
    ADD("Add Funds"),
    REDUCE("Withdraw Funds"),
    RESET("Account Reset")
}

data class CapitalTransaction(
    val id: String,
    val type: CapitalTransactionType,
    val amount: BigDecimal,
    val balanceAfter: BigDecimal,
    val note: String,
    val timestampMillis: Long,
    val currency: String = "INR"
)

data class AccountState(
    val virtualCapital: BigDecimal,
    val availableMargin: BigDecimal,
    val realisedPnl: BigDecimal,
    val cryptoVirtualCapital: BigDecimal = BigDecimal("10000.00"),
    val cryptoAvailableMargin: BigDecimal = BigDecimal("10000.00"),
    val cryptoRealisedPnl: BigDecimal = BigDecimal.ZERO
)

data class TradingConfig(
    val slippageBps: Int = 5, // 0.05% default simulated slippage
    val flatBrokerage: BigDecimal = BigDecimal("20.00"),
    val cryptoFeePercent: BigDecimal = BigDecimal("0.10"),
    val activeFeedSource: FeedSourceType = FeedSourceType.SIMULATED,
    val tickIntervalMs: Long = 1000L,
    val brokerWsUrl: String = "",
    val brokerClientId: String = "",
    val brokerFeedToken: String = "",
    val notificationsEnabled: Boolean = true,
    val signalNotificationsEnabled: Boolean = true,
    val useFreePublicMarketFeed: Boolean = true
)

data class OrderPlacementRequest(
    val symbol: String,
    val side: OrderSide,
    val orderType: OrderType,
    val productType: ProductType,
    val quantity: Int,
    val limitPrice: BigDecimal? = null,
    val triggerPrice: BigDecimal? = null,
    val slEnabled: Boolean = false,
    val slMode: RiskInputMode = RiskInputMode.PRICE,
    val slValue: BigDecimal? = null,
    val targetEnabled: Boolean = false,
    val targetMode: RiskInputMode = RiskInputMode.PRICE,
    val targetValue: BigDecimal? = null,
    val tslEnabled: Boolean = false,
    val tslMode: TrailMode = TrailMode.ABSOLUTE,
    val tslValue: BigDecimal? = null,
    val trailingTargetEnabled: Boolean = false,
    val trailingTargetMode: TrailMode = TrailMode.ABSOLUTE,
    val trailingTargetValue: BigDecimal? = null,
    val validityType: ValidityType = ValidityType.DAY,
    val gttDays: Int = 7,
    val gtdDateMillis: Long? = null
)
