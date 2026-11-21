package com.example.domain.engine

import com.example.domain.model.MarketSegment
import com.example.domain.model.OrderSide
import com.example.domain.model.RiskInputMode
import com.example.domain.model.TrailMode
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

object RiskMath {

    private val HUNDRED = BigDecimal("100")
    private val TEN_THOUSAND = BigDecimal("10000")

    fun roundToTick(price: BigDecimal, tickSize: BigDecimal): BigDecimal {
        if (tickSize <= BigDecimal.ZERO) return price.setScale(2, RoundingMode.HALF_UP)
        val scale = maxOf(tickSize.scale(), 2)
        val steps = price.divide(tickSize, 0, RoundingMode.HALF_UP)
        return steps.multiply(tickSize).setScale(scale, RoundingMode.HALF_UP)
    }

    fun applySlippage(
        price: BigDecimal,
        side: OrderSide,
        slippageBps: Int,
        tickSize: BigDecimal
    ): BigDecimal {
        if (slippageBps <= 0) return roundToTick(price, tickSize)
        val factor = BigDecimal(slippageBps).divide(TEN_THOUSAND, 6, RoundingMode.HALF_UP)
        val delta = price.multiply(factor)
        val slipped = when (side) {
            OrderSide.BUY -> price.add(delta)
            OrderSide.SELL -> price.subtract(delta).max(tickSize)
        }
        return roundToTick(slipped, tickSize)
    }

    fun resolveStopLossPrice(
        entryPrice: BigDecimal,
        side: OrderSide,
        mode: RiskInputMode,
        value: BigDecimal,
        tickSize: BigDecimal
    ): BigDecimal {
        val raw = when (mode) {
            RiskInputMode.PRICE -> value
            RiskInputMode.PERCENT -> {
                val offset = entryPrice.multiply(value).divide(HUNDRED, 6, RoundingMode.HALF_UP)
                if (side == OrderSide.BUY) entryPrice.subtract(offset) else entryPrice.add(offset)
            }
        }
        return roundToTick(raw.max(tickSize), tickSize)
    }

    fun resolveTargetPrice(
        entryPrice: BigDecimal,
        side: OrderSide,
        mode: RiskInputMode,
        value: BigDecimal,
        tickSize: BigDecimal
    ): BigDecimal {
        val raw = when (mode) {
            RiskInputMode.PRICE -> value
            RiskInputMode.PERCENT -> {
                val offset = entryPrice.multiply(value).divide(HUNDRED, 6, RoundingMode.HALF_UP)
                if (side == OrderSide.BUY) entryPrice.add(offset) else entryPrice.subtract(offset)
            }
        }
        return roundToTick(raw.max(tickSize), tickSize)
    }

    /**
     * Trailing Stop Loss ratcheting invariant:
     * Long:  newSL = max(oldSL, LTP - trailAmount) -> only moves UP, never retraces.
     * Short: newSL = min(oldSL, LTP + trailAmount) -> only moves DOWN, never retraces.
     */
    fun calculateTrailingSl(
        currentSl: BigDecimal?,
        ltp: BigDecimal,
        isLong: Boolean,
        trailMode: TrailMode,
        trailValue: BigDecimal,
        tickSize: BigDecimal
    ): BigDecimal {
        val trailAmount = computeTrailAmount(ltp, trailMode, trailValue)
        val candidate = if (isLong) {
            ltp.subtract(trailAmount).max(tickSize)
        } else {
            ltp.add(trailAmount)
        }
        val roundedCandidate = roundToTick(candidate, tickSize)
        if (currentSl == null) return roundedCandidate
        return if (isLong) {
            if (roundedCandidate > currentSl) roundedCandidate else currentSl
        } else {
            if (roundedCandidate < currentSl) roundedCandidate else currentSl
        }
    }

    data class TrailingTargetUpdate(
        val activated: Boolean,
        val peakPrice: BigDecimal?,
        val exitTrigger: BigDecimal?,
        val shouldExitNow: Boolean
    )

    /**
     * Trailing Target:
     * 1. Before activation: wait until LTP reaches initialTarget. Once reached, activate and arm
     *    exitTrigger = LTP - trail (long) or LTP + trail (short). Do NOT exit immediately.
     * 2. After activation: ratchet exitTrigger favourably. If LTP pulls back to exitTrigger, exit!
     */
    fun evaluateTrailingTarget(
        ltp: BigDecimal,
        initialTarget: BigDecimal,
        isLong: Boolean,
        alreadyActivated: Boolean,
        currentPeak: BigDecimal?,
        currentExitTrigger: BigDecimal?,
        trailMode: TrailMode,
        trailValue: BigDecimal,
        tickSize: BigDecimal
    ): TrailingTargetUpdate {
        val trailAmount = computeTrailAmount(ltp, trailMode, trailValue)

        if (!alreadyActivated) {
            val targetReached = if (isLong) ltp >= initialTarget else ltp <= initialTarget
            if (!targetReached) {
                return TrailingTargetUpdate(
                    activated = false,
                    peakPrice = currentPeak,
                    exitTrigger = currentExitTrigger,
                    shouldExitNow = false
                )
            }
            val initialExit = if (isLong) {
                ltp.subtract(trailAmount).max(tickSize)
            } else {
                ltp.add(trailAmount)
            }
            return TrailingTargetUpdate(
                activated = true,
                peakPrice = ltp,
                exitTrigger = roundToTick(initialExit, tickSize),
                shouldExitNow = false
            )
        }

        // Already activated: check pullback exit FIRST or update peak?
        // If LTP hits or crosses existing exit trigger, trigger exit!
        val existingTrigger = currentExitTrigger ?: initialTarget
        val triggered = if (isLong) ltp <= existingTrigger else ltp >= existingTrigger
        if (triggered) {
            return TrailingTargetUpdate(
                activated = true,
                peakPrice = currentPeak,
                exitTrigger = existingTrigger,
                shouldExitNow = true
            )
        }

        val newPeak = when {
            currentPeak == null -> ltp
            isLong -> if (ltp > currentPeak) ltp else currentPeak
            else -> if (ltp < currentPeak) ltp else currentPeak
        }

        val candidateExit = if (isLong) {
            newPeak.subtract(trailAmount).max(tickSize)
        } else {
            newPeak.add(trailAmount)
        }
        val roundedCandidate = roundToTick(candidateExit, tickSize)
        val ratchetedExit = if (isLong) {
            if (roundedCandidate > existingTrigger) roundedCandidate else existingTrigger
        } else {
            if (roundedCandidate < existingTrigger) roundedCandidate else existingTrigger
        }

        return TrailingTargetUpdate(
            activated = true,
            peakPrice = newPeak,
            exitTrigger = ratchetedExit,
            shouldExitNow = false
        )
    }

    fun computeTrailAmount(
        referencePrice: BigDecimal,
        trailMode: TrailMode,
        trailValue: BigDecimal
    ): BigDecimal {
        return when (trailMode) {
            TrailMode.ABSOLUTE -> trailValue.max(BigDecimal.ZERO)
            TrailMode.PERCENT -> referencePrice.multiply(trailValue.max(BigDecimal.ZERO))
                .divide(HUNDRED, 6, RoundingMode.HALF_UP)
        }
    }

    fun calculateUnrealisedPnl(
        entryPrice: BigDecimal,
        ltp: BigDecimal,
        quantity: Int,
        isLong: Boolean
    ): BigDecimal {
        val diff = if (isLong) ltp.subtract(entryPrice) else entryPrice.subtract(ltp)
        return diff.multiply(BigDecimal(quantity)).setScale(2, RoundingMode.HALF_UP)
    }

    fun calculateSimulatedCharges(
        segment: MarketSegment,
        turnover: BigDecimal,
        flatBrokeragePerOrder: BigDecimal,
        cryptoFeePercent: BigDecimal
    ): BigDecimal {
        // Two legs (entry + exit)
        return when (segment) {
            MarketSegment.CRYPTO -> {
                turnover.multiply(cryptoFeePercent)
                    .divide(HUNDRED, 2, RoundingMode.HALF_UP)
                    .max(BigDecimal("0.01"))
            }
            MarketSegment.NSE, MarketSegment.BSE, MarketSegment.MCX -> {
                val brokerage = flatBrokeragePerOrder.multiply(BigDecimal("2"))
                val regulatory = turnover.multiply(BigDecimal("0.00035"))
                    .setScale(2, RoundingMode.HALF_UP)
                brokerage.add(regulatory).setScale(2, RoundingMode.HALF_UP)
            }
        }
    }

    fun formatPrice(price: BigDecimal, segment: MarketSegment): String {
        val decimals = when {
            segment == MarketSegment.CRYPTO && price < BigDecimal("10") -> 4
            else -> 2
        }
        val pattern = if (decimals == 4) "#,##0.0000" else "#,##0.00"
        val locale = if (segment == MarketSegment.CRYPTO) Locale.US else Locale.forLanguageTag("en-IN")
        val df = DecimalFormat(pattern, DecimalFormatSymbols(locale))
        return "${segment.currencySymbol}${df.format(price)}"
    }

    fun formatSignedCurrency(amount: BigDecimal, segment: MarketSegment): String {
        val sign = when {
            amount > BigDecimal.ZERO -> "+"
            amount < BigDecimal.ZERO -> "-"
            else -> ""
        }
        val absVal = amount.abs().setScale(2, RoundingMode.HALF_UP)
        val locale = if (segment == MarketSegment.CRYPTO) Locale.US else Locale.forLanguageTag("en-IN")
        val df = DecimalFormat("#,##0.00", DecimalFormatSymbols(locale))
        return "$sign${segment.currencySymbol}${df.format(absVal)}"
    }

    fun formatPortfolioInr(amount: BigDecimal, signed: Boolean = false): String {
        val sign = when {
            !signed -> if (amount < BigDecimal.ZERO) "-" else ""
            amount > BigDecimal.ZERO -> "+"
            amount < BigDecimal.ZERO -> "-"
            else -> ""
        }
        val absVal = amount.abs().setScale(2, RoundingMode.HALF_UP)
        val df = DecimalFormat("#,##0.00", DecimalFormatSymbols(Locale.forLanguageTag("en-IN")))
        return "$sign₹${df.format(absVal)}"
    }
}
