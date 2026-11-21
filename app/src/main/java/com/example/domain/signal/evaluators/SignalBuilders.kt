package com.example.domain.signal.evaluators

import com.example.domain.signal.Candle
import com.example.domain.signal.SignalConfidence
import com.example.domain.signal.SignalContext
import com.example.domain.signal.SignalDirection
import com.example.domain.signal.SignalStrategy
import com.example.domain.signal.TechnicalIndicators
import com.example.domain.signal.Timeframe
import com.example.domain.signal.TradeSignal
import kotlin.math.abs

internal object SignalBuilders {

    /**
     * Builds a [TradeSignal] after validating geometry (SL on the losing side, targets on the
     * winning side, positive prices). Returns null when the trade is not well-formed.
     */
    fun build(
        context: SignalContext,
        strategy: SignalStrategy,
        timeframe: Timeframe,
        direction: SignalDirection,
        signalCandle: Candle,
        entry: Double,
        stopLoss: Double,
        target1: Double,
        target2: Double,
        confidence: SignalConfidence,
        reasons: List<String>,
        expiryBars: Int
    ): TradeSignal? {
        if (entry <= 0.0 || stopLoss <= 0.0 || target1 <= 0.0 || target2 <= 0.0) return null
        if (entry.isNaN() || stopLoss.isNaN() || target1.isNaN() || target2.isNaN()) return null
        val isLong = direction == SignalDirection.LONG
        val geometryOk = if (isLong) {
            stopLoss < entry && target1 > entry && target2 >= target1
        } else {
            stopLoss > entry && target1 < entry && target2 <= target1
        }
        if (!geometryOk) return null
        val risk = abs(entry - stopLoss)
        if (risk <= 0.0) return null
        val rr = abs(target2 - entry) / risk
        val ref = context.currentLtp
        val expiryMillis = (timeframe.durationMillis * expiryBars).coerceIn(15 * 60_000L, 30L * 86_400_000L)
        return TradeSignal(
            id = "${context.symbol}_${strategy.name}_${timeframe.name}_${direction.name}_${signalCandle.timestamp.epochSecond}",
            symbol = context.symbol,
            segment = context.segment,
            strategy = strategy,
            direction = direction,
            timeframe = timeframe,
            entryPrice = TechnicalIndicators.roundPrice(entry, ref),
            stopLoss = TechnicalIndicators.roundPrice(stopLoss, ref),
            target1 = TechnicalIndicators.roundPrice(target1, ref),
            target2 = TechnicalIndicators.roundPrice(target2, ref),
            riskRewardRatio = (rr * 100.0).toLong() / 100.0,
            confidence = confidence,
            reasons = reasons,
            generatedAt = context.now,
            expiresAt = context.now.plusMillis(expiryMillis)
        )
    }

    fun fmt(v: Double): String = String.format(java.util.Locale.US, "%.2f", v)
}
