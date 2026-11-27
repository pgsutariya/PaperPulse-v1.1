package com.example.domain.engine

import com.example.domain.model.Instrument
import com.example.domain.model.InstrumentType
import com.example.domain.model.MarketSegment
import com.example.domain.model.OrderSide
import com.example.domain.model.OrderType
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Single price level in an instrument's bid/ask order book depth.
 */
data class OrderBookLevel(
    val price: BigDecimal,
    val quantity: Int,
    val cumulativeQuantity: Int,
    val ordersCount: Int = 1
)

/**
 * Top-5 level bid/ask order book liquidity snapshot used by [PriceAwareExecutionService]
 * to estimate market-impact VWAP fill price and detect thin-liquidity slippage.
 */
data class OrderBookSnapshot(
    val symbol: String,
    val ltp: BigDecimal,
    val bids: List<OrderBookLevel>,
    val asks: List<OrderBookLevel>,
    val bestBid: BigDecimal,
    val bestAsk: BigDecimal,
    val spreadPercent: Double,
    val totalBidDepth: Int,
    val totalAskDepth: Int
) {
    /** Share of total top-5 book liquidity sitting on the bid (buy) side, in 1..99%. */
    val buyPressurePercent: Int
        get() {
            val total = totalBidDepth + totalAskDepth
            if (total <= 0) return 50
            return ((totalBidDepth.toDouble() / total.toDouble()) * 100.0)
                .roundToInt()
                .coerceIn(1, 99)
        }

    /**
     * Walks the opposing side of the order book for [quantity] shares/contracts and returns
     * the liquidity-weighted VWAP execution price along with top-of-book fill coverage %.
     */
    fun estimateMarketFill(side: OrderSide, quantity: Int): BookFillEstimate {
        val levels = if (side == OrderSide.BUY) asks else bids
        val safeQty = quantity.coerceAtLeast(1)
        if (levels.isEmpty()) {
            return BookFillEstimate(
                vwapPrice = ltp,
                topLevelCoveragePercent = 100,
                levelsConsumed = 1,
                exhaustedBook = false
            )
        }
        var remaining = safeQty
        var cost = BigDecimal.ZERO
        var filled = 0
        var levelsUsed = 0
        for (level in levels) {
            if (remaining <= 0) break
            val take = minOf(remaining, level.quantity.coerceAtLeast(1))
            cost = cost.add(level.price.multiply(BigDecimal(take)))
            filled += take
            remaining -= take
            levelsUsed++
        }
        // Any overflow beyond top-5 depth incurs an extra adverse sweep penalty
        val exhausted = remaining > 0
        if (exhausted) {
            val worstLevel = levels.last().price
            val sweepFactor = if (side == OrderSide.BUY) BigDecimal("1.0035") else BigDecimal("0.9965")
            val sweepPrice = worstLevel.multiply(sweepFactor)
            cost = cost.add(sweepPrice.multiply(BigDecimal(remaining)))
            filled += remaining
        }
        val scale = max(ltp.scale(), 2)
        val vwap = cost.divide(BigDecimal(filled.coerceAtLeast(1)), scale, RoundingMode.HALF_UP)
        val topQty = levels.first().quantity.coerceAtLeast(1)
        val coverage = ((minOf(safeQty, topQty).toDouble() / safeQty.toDouble()) * 100.0)
            .roundToInt()
            .coerceIn(0, 100)
        return BookFillEstimate(
            vwapPrice = vwap,
            topLevelCoveragePercent = coverage,
            levelsConsumed = levelsUsed.coerceAtLeast(1),
            exhaustedBook = exhausted
        )
    }
}

data class BookFillEstimate(
    val vwapPrice: BigDecimal,
    val topLevelCoveragePercent: Int,
    val levelsConsumed: Int,
    val exhaustedBook: Boolean
)

enum class PriceAwareExecutionDecision(val badgeLabel: String) {
    /** Market / order-book VWAP is inside the user's slippage tolerance of Target Entry. */
    WITHIN_ENTRY_ZONE("IN ENTRY ZONE"),
    /** Market has already moved unfavorably beyond tolerance — resting LIMIT placed at entry zone. */
    PROTECTED_LIMIT_AT_ENTRY("LIMIT @ ENTRY"),
    /** Market has not yet reached breakout/breakdown entry — resting SL-LIMIT trigger placed at entry. */
    PROTECTED_STOP_LIMIT_ENTRY("SL-LIMIT @ ENTRY"),
    /** Price-aware guard disabled or no signal target entry price — honor manual order inputs directly. */
    MANUAL_PASSTHROUGH("MANUAL")
}

/**
 * Output of [PriceAwareExecutionService.evaluate] showing the exact order type, limit/trigger
 * prices, liquidity-weighted slippage estimate, and protection rationale.
 */
data class PriceAwareExecutionPlan(
    val decision: PriceAwareExecutionDecision,
    val effectiveOrderType: OrderType,
    val effectiveLimitPrice: BigDecimal?,
    val effectiveTriggerPrice: BigDecimal?,
    val targetEntryPrice: BigDecimal?,
    val ltp: BigDecimal,
    val estimatedBookFillPrice: BigDecimal,
    val deviationFromEntryPercent: Double, // signed: positive = above entry, negative = below entry
    val adverseSlippagePercent: Double,    // positive = unfavorable chase; negative = favorable/below entry
    val slippageTolerancePercent: Double,
    val orderBook: OrderBookSnapshot,
    val bookFillEstimate: BookFillEstimate,
    val wasConvertedToProtectedOrder: Boolean,
    val summary: String
)

/**
 * Cross-references a Signal's Target Entry Price with live Order Book liquidity and user-defined
 * slippage tolerance so orders NEVER execute immediately at unfavorable market prices (e.g. LTP
 * running at 110 or 90 when the signal entry is 100).
 */
object PriceAwareExecutionService {

    const val DEFAULT_SLIPPAGE_TOLERANCE_PERCENT = 0.35

    /**
     * Synthesizes a realistic 5-level bid/ask order book anchored to live LTP, tick size,
     * lot size, intraday range volatility, tick direction, and traded volume.
     */
    fun buildOrderBookSnapshot(instrument: Instrument): OrderBookSnapshot {
        val ltp = instrument.ltp.coerceAtLeast(instrument.tickSize.max(BigDecimal("0.01")))
        val tick = instrument.tickSize.takeIf { it > BigDecimal.ZERO } ?: BigDecimal("0.05")
        val lot = instrument.lotSize.coerceAtLeast(1)

        val rangePct = if (ltp > BigDecimal.ZERO) {
            instrument.dayHigh.subtract(instrument.dayLow).abs()
                .divide(ltp, 6, RoundingMode.HALF_UP)
                .toDouble() * 100.0
        } else 1.0

        val baseHalfSpreadTicks = when {
            instrument.type == InstrumentType.OPTIONS -> if (rangePct > 12.0) 3 else 2
            instrument.segment == MarketSegment.CRYPTO -> 1
            instrument.volume >= 1_000_000L -> 1
            instrument.volume >= 150_000L -> 2
            else -> 3
        }

        val bestBid = RiskMath.roundToTick(
            ltp.subtract(tick.multiply(BigDecimal(baseHalfSpreadTicks))).max(tick),
            tick
        )
        val bestAsk = RiskMath.roundToTick(
            ltp.add(tick.multiply(BigDecimal(baseHalfSpreadTicks))).max(bestBid.add(tick)),
            tick
        )

        val baseLotDepth = when {
            instrument.segment == MarketSegment.CRYPTO -> 12
            instrument.type == InstrumentType.OPTIONS -> 6
            instrument.volume >= 1_000_000L -> 45
            instrument.volume >= 200_000L -> 20
            else -> 8
        }

        val changeBias = (instrument.changePercent.toDouble() / 6.0).coerceIn(-0.18, 0.18)
        val tickBias = when (instrument.lastTickDirection) {
            1 -> 0.12
            -1 -> -0.12
            else -> 0.0
        }
        val bidPressureBias = (1.0 + changeBias + tickBias).coerceIn(0.76, 1.26)
        val askPressureBias = (1.0 - changeBias - tickBias).coerceIn(0.76, 1.26)
        val ltpTicksHash = ltp.divide(tick, 0, RoundingMode.HALF_UP).toLong()
        val volSeed = instrument.volume xor (ltpTicksHash * 31L)

        val bids = ArrayList<OrderBookLevel>(5)
        val asks = ArrayList<OrderBookLevel>(5)
        var cumBid = 0
        var cumAsk = 0
        val depthMultipliers = intArrayOf(1, 2, 3, 4, 6)

        for (lvl in 0 until 5) {
            val step = tick.multiply(BigDecimal(lvl))
            val bidPrice = RiskMath.roundToTick(bestBid.subtract(step).max(tick), tick)
            val askPrice = RiskMath.roundToTick(bestAsk.add(step), tick)

            val bidNoise = 0.85 + (((abs((volSeed + (lvl + 1) * 97L).hashCode()) % 31).toDouble()) / 100.0)
            val askNoise = 0.85 + (((abs((volSeed * 3L + (lvl + 1) * 131L).hashCode()) % 31).toDouble()) / 100.0)

            val bidLots = (baseLotDepth * depthMultipliers[lvl] * bidPressureBias * bidNoise)
                .roundToInt()
                .coerceAtLeast(1)
            val askLots = (baseLotDepth * depthMultipliers[lvl] * askPressureBias * askNoise)
                .roundToInt()
                .coerceAtLeast(1)

            val bidQty = (bidLots * lot).coerceAtLeast(lot)
            val askQty = (askLots * lot).coerceAtLeast(lot)
            val bidOrders = ((bidLots / 4) + 2 + (lvl * 2) + (abs((volSeed + lvl * 17L).hashCode()) % 5))
                .coerceIn(1, 64)
            val askOrders = ((askLots / 4) + 2 + (lvl * 2) + (abs((volSeed + lvl * 23L).hashCode()) % 5))
                .coerceIn(1, 64)

            cumBid += bidQty
            cumAsk += askQty
            bids += OrderBookLevel(
                price = bidPrice,
                quantity = bidQty,
                cumulativeQuantity = cumBid,
                ordersCount = bidOrders
            )
            asks += OrderBookLevel(
                price = askPrice,
                quantity = askQty,
                cumulativeQuantity = cumAsk,
                ordersCount = askOrders
            )
        }

        val spreadPct = if (ltp > BigDecimal.ZERO) {
            bestAsk.subtract(bestBid)
                .divide(ltp, 6, RoundingMode.HALF_UP)
                .toDouble() * 100.0
        } else 0.0

        return OrderBookSnapshot(
            symbol = instrument.symbol,
            ltp = ltp,
            bids = bids,
            asks = asks,
            bestBid = bestBid,
            bestAsk = bestAsk,
            spreadPercent = spreadPct,
            totalBidDepth = cumBid,
            totalAskDepth = cumAsk
        )
    }

    /**
     * Evaluates whether an order targeting [targetEntryPrice] can fill immediately within
     * [slippageTolerancePercent] against the live [orderBook], or must be routed as a resting
     * LIMIT / SL-LIMIT order anchored to the signal entry zone.
     */
    fun evaluate(
        instrument: Instrument,
        side: OrderSide,
        quantity: Int,
        requestedOrderType: OrderType,
        targetEntryPrice: BigDecimal?,
        userLimitPrice: BigDecimal?,
        userTriggerPrice: BigDecimal?,
        slippageTolerancePercent: Double = DEFAULT_SLIPPAGE_TOLERANCE_PERCENT,
        priceAwareGuardEnabled: Boolean = true,
        orderBook: OrderBookSnapshot = buildOrderBookSnapshot(instrument)
    ): PriceAwareExecutionPlan {
        val tick = instrument.tickSize.takeIf { it > BigDecimal.ZERO } ?: BigDecimal("0.05")
        val ltp = instrument.ltp.coerceAtLeast(tick)
        val safeTolerance = slippageTolerancePercent.coerceIn(0.05, 5.0)
        val fillEstimate = orderBook.estimateMarketFill(side, quantity)
        val estFill = fillEstimate.vwapPrice

        if (!priceAwareGuardEnabled || targetEntryPrice == null || targetEntryPrice <= BigDecimal.ZERO) {
            return PriceAwareExecutionPlan(
                decision = PriceAwareExecutionDecision.MANUAL_PASSTHROUGH,
                effectiveOrderType = requestedOrderType,
                effectiveLimitPrice = userLimitPrice,
                effectiveTriggerPrice = userTriggerPrice,
                targetEntryPrice = targetEntryPrice,
                ltp = ltp,
                estimatedBookFillPrice = estFill,
                deviationFromEntryPercent = 0.0,
                adverseSlippagePercent = 0.0,
                slippageTolerancePercent = safeTolerance,
                orderBook = orderBook,
                bookFillEstimate = fillEstimate,
                wasConvertedToProtectedOrder = false,
                summary = "Manual execution mode — order routes with your exact price settings."
            )
        }

        val entry = RiskMath.roundToTick(targetEntryPrice, tick)
        val isBuy = side == OrderSide.BUY

        val rawDevPct = estFill.subtract(entry)
            .divide(entry, 6, RoundingMode.HALF_UP)
            .toDouble() * 100.0
        val ltpDevPct = ltp.subtract(entry)
            .divide(entry, 6, RoundingMode.HALF_UP)
            .toDouble() * 100.0

        val adversePct = if (isBuy) rawDevPct else -rawDevPct
        val absLtpDevPct = abs(ltpDevPct)
        val absBookDevPct = abs(rawDevPct)

        val tolFraction = BigDecimal.valueOf(safeTolerance / 100.0)
        val maxFavorableChaseLimit = if (isBuy) {
            RiskMath.roundToTick(entry.multiply(BigDecimal.ONE.add(tolFraction)), tick)
        } else {
            RiskMath.roundToTick(entry.multiply(BigDecimal.ONE.subtract(tolFraction)).max(tick), tick)
        }

        val seg = instrument.segment

        // Case 1: Both LTP and order-book VWAP are within the user's slippage tolerance of Target Entry
        if (absLtpDevPct <= safeTolerance && absBookDevPct <= safeTolerance && !fillEstimate.exhaustedBook) {
            val effectiveLimit = when (requestedOrderType) {
                OrderType.LIMIT -> userLimitPrice ?: maxFavorableChaseLimit
                OrderType.BRACKET -> null
                OrderType.MARKET -> null
                else -> userLimitPrice
            }
            val effectiveTrigger = when (requestedOrderType) {
                OrderType.SL_M, OrderType.SL_L, OrderType.TGT_M, OrderType.TGT_L -> userTriggerPrice ?: entry
                else -> null
            }
            return PriceAwareExecutionPlan(
                decision = PriceAwareExecutionDecision.WITHIN_ENTRY_ZONE,
                effectiveOrderType = requestedOrderType,
                effectiveLimitPrice = effectiveLimit,
                effectiveTriggerPrice = effectiveTrigger,
                targetEntryPrice = entry,
                ltp = ltp,
                estimatedBookFillPrice = estFill,
                deviationFromEntryPercent = rawDevPct,
                adverseSlippagePercent = adversePct,
                slippageTolerancePercent = safeTolerance,
                orderBook = orderBook,
                bookFillEstimate = fillEstimate,
                wasConvertedToProtectedOrder = false,
                summary = "Book VWAP ${RiskMath.formatPrice(estFill, seg)} is within ±${fmt2(safeTolerance)}% of Entry ${RiskMath.formatPrice(entry, seg)} (Top-of-book ${fillEstimate.topLevelCoveragePercent}%)."
            )
        }

        // Case 2: Unfavorable / Chasing market price (e.g. BUY Entry 100, LTP 110; or SELL Entry 100, LTP 90)
        // OR thin order-book liquidity sweeps VWAP past slippage tolerance.
        if (adversePct > safeTolerance || (absLtpDevPct <= safeTolerance && fillEstimate.exhaustedBook)) {
            val restingLimit = if (isBuy) {
                val capBelowMarket = ltp.subtract(tick).max(tick)
                val candidate = if (entry < ltp) entry else minOf(maxFavorableChaseLimit, capBelowMarket)
                RiskMath.roundToTick(candidate.max(tick), tick)
            } else {
                val capAboveMarket = ltp.add(tick)
                val candidate = if (entry > ltp) entry else maxOf(maxFavorableChaseLimit, capAboveMarket)
                RiskMath.roundToTick(candidate.max(tick), tick)
            }

            val protectedType = if (requestedOrderType == OrderType.BRACKET) OrderType.BRACKET else OrderType.LIMIT

            val reason = if (fillEstimate.exhaustedBook && absLtpDevPct <= safeTolerance) {
                "Thin book liquidity sweeps VWAP to ${RiskMath.formatPrice(estFill, seg)} (+${fmt2(adversePct)}% slippage). Routed as resting ${protectedType.label} @ ${RiskMath.formatPrice(restingLimit, seg)}."
            } else {
                "LTP ${RiskMath.formatPrice(ltp, seg)} is ${fmtSigned(ltpDevPct)}% from Entry ${RiskMath.formatPrice(entry, seg)} (>${fmt2(safeTolerance)}% tolerance). Placed as resting ${protectedType.label} @ ${RiskMath.formatPrice(restingLimit, seg)} to prevent chasing."
            }

            return PriceAwareExecutionPlan(
                decision = PriceAwareExecutionDecision.PROTECTED_LIMIT_AT_ENTRY,
                effectiveOrderType = protectedType,
                effectiveLimitPrice = restingLimit,
                effectiveTriggerPrice = null,
                targetEntryPrice = entry,
                ltp = ltp,
                estimatedBookFillPrice = estFill,
                deviationFromEntryPercent = rawDevPct,
                adverseSlippagePercent = adversePct,
                slippageTolerancePercent = safeTolerance,
                orderBook = orderBook,
                bookFillEstimate = fillEstimate,
                wasConvertedToProtectedOrder = true,
                summary = reason
            )
        }

        // Case 3: Price is on the opposite side of Entry beyond tolerance
        // (e.g. BUY Signal Entry 100 while LTP is running at 90; or SELL Signal Entry 100 while LTP is 110).
        // Route as resting SL-LIMIT (Trigger = Entry 100, Limit = Entry ± tolerance) so it waits for
        // price to reach the Signal Entry Zone rather than filling early at LTP.
        val triggerPrice = entry
        val protectedLimit = maxFavorableChaseLimit
        return PriceAwareExecutionPlan(
            decision = PriceAwareExecutionDecision.PROTECTED_STOP_LIMIT_ENTRY,
            effectiveOrderType = OrderType.SL_L,
            effectiveLimitPrice = protectedLimit,
            effectiveTriggerPrice = triggerPrice,
            targetEntryPrice = entry,
            ltp = ltp,
            estimatedBookFillPrice = estFill,
            deviationFromEntryPercent = rawDevPct,
            adverseSlippagePercent = adversePct,
            slippageTolerancePercent = safeTolerance,
            orderBook = orderBook,
            bookFillEstimate = fillEstimate,
            wasConvertedToProtectedOrder = true,
            summary = "LTP ${RiskMath.formatPrice(ltp, seg)} has not reached Entry ${RiskMath.formatPrice(entry, seg)} (${fmtSigned(ltpDevPct)}%). Placed as SL-LIMIT (Trigger ${RiskMath.formatPrice(triggerPrice, seg)}, Cap ${RiskMath.formatPrice(protectedLimit, seg)}) so it won't fill early at LTP."
        )
    }

    private fun fmt2(v: Double): String = String.format(java.util.Locale.US, "%.2f", v)
    private fun fmtSigned(v: Double): String = String.format(java.util.Locale.US, "%+.2f", v)
}
