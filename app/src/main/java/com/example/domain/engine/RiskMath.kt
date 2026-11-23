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

    data class OrderChargeBreakdown(
        val brokerage: BigDecimal = BigDecimal.ZERO,
        val sttCtt: BigDecimal = BigDecimal.ZERO,
        val exchangeCharges: BigDecimal = BigDecimal.ZERO,
        val sebiCharges: BigDecimal = BigDecimal.ZERO,
        val stampDuty: BigDecimal = BigDecimal.ZERO,
        val gst: BigDecimal = BigDecimal.ZERO
    ) {
        val total: BigDecimal
            get() = brokerage
                .add(sttCtt)
                .add(exchangeCharges)
                .add(sebiCharges)
                .add(stampDuty)
                .add(gst)
                .setScale(2, RoundingMode.HALF_UP)

        operator fun plus(other: OrderChargeBreakdown): OrderChargeBreakdown = OrderChargeBreakdown(
            brokerage = brokerage.add(other.brokerage).setScale(2, RoundingMode.HALF_UP),
            sttCtt = sttCtt.add(other.sttCtt).setScale(2, RoundingMode.HALF_UP),
            exchangeCharges = exchangeCharges.add(other.exchangeCharges).setScale(2, RoundingMode.HALF_UP),
            sebiCharges = sebiCharges.add(other.sebiCharges).setScale(2, RoundingMode.HALF_UP),
            stampDuty = stampDuty.add(other.stampDuty).setScale(2, RoundingMode.HALF_UP),
            gst = gst.add(other.gst).setScale(2, RoundingMode.HALF_UP)
        )
    }

    /**
     * Standard Indian (NSE / BSE / MCX) & Crypto itemized order charges and statutory taxes
     * for a single executed order leg.
     */
    fun calculateOrderCharges(
        segment: MarketSegment,
        instrumentType: com.example.domain.model.InstrumentType,
        productType: com.example.domain.model.ProductType,
        side: OrderSide,
        turnover: BigDecimal,
        flatBrokeragePerOrder: BigDecimal = BigDecimal("20.00"),
        cryptoFeePercent: BigDecimal = BigDecimal("0.10")
    ): OrderChargeBreakdown {
        if (turnover <= BigDecimal.ZERO) return OrderChargeBreakdown()

        if (segment == MarketSegment.CRYPTO) {
            val fee = turnover.multiply(cryptoFeePercent)
                .divide(HUNDRED, 2, RoundingMode.HALF_UP)
                .max(BigDecimal("0.01"))
            return OrderChargeBreakdown(brokerage = fee)
        }

        val isSell = side == OrderSide.SELL
        val isBuy = side == OrderSide.BUY

        val brokerage: BigDecimal = when {
            segment == MarketSegment.MCX ->
                turnover.multiply(BigDecimal("0.0003")).min(flatBrokeragePerOrder).setScale(2, RoundingMode.HALF_UP)
            instrumentType == com.example.domain.model.InstrumentType.OPTIONS ->
                flatBrokeragePerOrder.setScale(2, RoundingMode.HALF_UP)
            instrumentType == com.example.domain.model.InstrumentType.FUTURES ->
                turnover.multiply(BigDecimal("0.0003")).min(flatBrokeragePerOrder).setScale(2, RoundingMode.HALF_UP)
            productType == com.example.domain.model.ProductType.CARRY_FORWARD ->
                BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP) // Standard ₹0 equity delivery brokerage
            else ->
                turnover.multiply(BigDecimal("0.0003")).min(flatBrokeragePerOrder).setScale(2, RoundingMode.HALF_UP)
        }

        // STT / CTT
        val sttCtt: BigDecimal = when {
            segment == MarketSegment.MCX ->
                if (isSell) turnover.multiply(BigDecimal("0.0001")).setScale(2, RoundingMode.HALF_UP) else BigDecimal.ZERO.setScale(2)
            instrumentType == com.example.domain.model.InstrumentType.OPTIONS ->
                if (isSell) turnover.multiply(BigDecimal("0.001")).setScale(2, RoundingMode.HALF_UP) else BigDecimal.ZERO.setScale(2)
            instrumentType == com.example.domain.model.InstrumentType.FUTURES ->
                if (isSell) turnover.multiply(BigDecimal("0.0002")).setScale(2, RoundingMode.HALF_UP) else BigDecimal.ZERO.setScale(2)
            productType == com.example.domain.model.ProductType.CARRY_FORWARD ->
                turnover.multiply(BigDecimal("0.001")).setScale(2, RoundingMode.HALF_UP)
            else -> // Equity Intraday: 0.025% on sell side
                if (isSell) turnover.multiply(BigDecimal("0.00025")).setScale(2, RoundingMode.HALF_UP) else BigDecimal.ZERO.setScale(2)
        }

        // Exchange Transaction Charges
        val exchangeRate: BigDecimal = when {
            segment == MarketSegment.MCX -> BigDecimal("0.000021")
            instrumentType == com.example.domain.model.InstrumentType.OPTIONS -> BigDecimal("0.0003503")
            instrumentType == com.example.domain.model.InstrumentType.FUTURES -> BigDecimal("0.0000173")
            segment == MarketSegment.BSE -> BigDecimal("0.0000375")
            else -> BigDecimal("0.0000297") // NSE Cash
        }
        val exchangeCharges = turnover.multiply(exchangeRate).setScale(2, RoundingMode.HALF_UP)

        // SEBI Turnover Charges: ₹10 per crore (0.000001)
        val sebiCharges = turnover.multiply(BigDecimal("0.000001")).setScale(2, RoundingMode.HALF_UP)

        // Stamp Duty (buy side only)
        val stampRate: BigDecimal = when {
            !isBuy -> BigDecimal.ZERO
            segment == MarketSegment.MCX -> BigDecimal("0.00002")
            instrumentType == com.example.domain.model.InstrumentType.OPTIONS -> BigDecimal("0.00003")
            instrumentType == com.example.domain.model.InstrumentType.FUTURES -> BigDecimal("0.00002")
            productType == com.example.domain.model.ProductType.CARRY_FORWARD -> BigDecimal("0.00015")
            else -> BigDecimal("0.00003") // Equity Intraday
        }
        val stampDuty = turnover.multiply(stampRate).setScale(2, RoundingMode.HALF_UP)

        // GST: 18% on (Brokerage + Exchange Charges + SEBI Charges)
        val gst = brokerage.add(exchangeCharges).add(sebiCharges)
            .multiply(BigDecimal("0.18"))
            .setScale(2, RoundingMode.HALF_UP)

        return OrderChargeBreakdown(
            brokerage = brokerage,
            sttCtt = sttCtt,
            exchangeCharges = exchangeCharges,
            sebiCharges = sebiCharges,
            stampDuty = stampDuty,
            gst = gst
        )
    }

    fun calculateRoundTripCharges(
        segment: MarketSegment,
        instrumentType: com.example.domain.model.InstrumentType,
        productType: com.example.domain.model.ProductType,
        entrySide: OrderSide,
        entryTurnover: BigDecimal,
        exitTurnover: BigDecimal,
        flatBrokeragePerOrder: BigDecimal = BigDecimal("20.00"),
        cryptoFeePercent: BigDecimal = BigDecimal("0.10")
    ): OrderChargeBreakdown {
        val exitSide = if (entrySide == OrderSide.BUY) OrderSide.SELL else OrderSide.BUY
        val entryLeg = calculateOrderCharges(
            segment, instrumentType, productType, entrySide, entryTurnover, flatBrokeragePerOrder, cryptoFeePercent
        )
        val exitLeg = calculateOrderCharges(
            segment, instrumentType, productType, exitSide, exitTurnover, flatBrokeragePerOrder, cryptoFeePercent
        )
        return entryLeg + exitLeg
    }

    fun formatQtyWithLots(quantity: Int, lotSize: Int): String {
        val safeLot = lotSize.coerceAtLeast(1)
        return if (safeLot > 1) {
            val lots = quantity / safeLot
            val lotWord = if (lots == 1) "lot" else "lots"
            "$quantity ($lots $lotWord × $safeLot)"
        } else {
            "$quantity (Lot 1)"
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
