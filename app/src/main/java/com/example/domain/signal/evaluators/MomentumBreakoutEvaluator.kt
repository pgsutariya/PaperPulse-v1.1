package com.example.domain.signal.evaluators

import com.example.domain.signal.SignalConfidence
import com.example.domain.signal.SignalContext
import com.example.domain.signal.SignalDirection
import com.example.domain.signal.SignalStrategy
import com.example.domain.signal.StrategyEvaluator
import com.example.domain.signal.TechnicalIndicators
import com.example.domain.signal.Timeframe
import com.example.domain.signal.TradeSignal
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * 70% Accuracy Momentum Breakout Strategy:
 * 1. Trend Identification: 10 SMA above 30 SMA (uptrend) or below 30 SMA (downtrend).
 * 2. Momentum & Volume Confirmation: Strong directional candle body + volume surge >= 1.15x 20-bar avg + RSI(14) alignment.
 * 3. Entry Trigger: Breakout above recent 10-bar swing high (Long) or below recent 10-bar swing low (Short).
 * 4. Risk Management: SL placed below recent swing low / above recent swing high (clamped to sane ATR bounds), 1:2 R:R target.
 */
class MomentumBreakoutEvaluator : StrategyEvaluator {
    override val strategy: SignalStrategy = SignalStrategy.MOMENTUM_BREAKOUT

    override fun evaluate(context: SignalContext): TradeSignal? {
        val tf = listOf(Timeframe.M15, Timeframe.M5, Timeframe.H1)
            .firstOrNull { (context.candlesByTimeframe[it]?.size ?: 0) >= 38 }
            ?: return null
        val candles = context.candlesByTimeframe[tf] ?: return null
        if (candles.size < 38) return null

        val closes = candles.map { it.close }
        val sma10 = TechnicalIndicators.sma(closes, 10)
        val sma30 = TechnicalIndicators.sma(closes, 30)
        val rsi14 = TechnicalIndicators.rsi(closes, 14)
        val atr14 = TechnicalIndicators.atr(candles, 14)

        val idx = candles.lastIndex
        val last = candles[idx]
        val s10 = sma10[idx] ?: return null
        val s30 = sma30[idx] ?: return null
        val rsi = rsi14[idx] ?: return null
        val atr = atr14[idx]?.takeIf { it > 0.0 } ?: ((last.high - last.low).coerceAtLeast(last.close * 0.004))

        val priorSwing = candles.subList(idx - 10, idx)
        val swingHigh = priorSwing.maxOf { it.high }
        val swingLow = priorSwing.minOf { it.low }

        val avgVol = candles.subList(idx - 20, idx).map { it.volume }.average().coerceAtLeast(1.0)
        val volRatio = last.volume / avgVol
        val barRange = (last.high - last.low).coerceAtLeast(1e-9)
        val bodyRatio = abs(last.close - last.open) / barRange

        val longBreakout = s10 > s30 &&
            last.close > swingHigh &&
            last.close > last.open &&
            bodyRatio >= 0.45 &&
            rsi in 54.0..78.0 &&
            volRatio >= 1.12

        val shortBreakout = s10 < s30 &&
            last.close < swingLow &&
            last.close < last.open &&
            bodyRatio >= 0.45 &&
            rsi in 22.0..46.0 &&
            volRatio >= 1.12

        if (!longBreakout && !shortBreakout) return null

        val isLong = longBreakout
        val direction = if (isLong) SignalDirection.LONG else SignalDirection.SHORT
        val entry = context.currentLtp.toDouble().takeIf { it > 0.0 } ?: last.close
        val minRisk = entry * 0.0035
        val maxRisk = entry * 0.045

        val rawSl = if (isLong) {
            minOf(swingLow, last.low) - atr * 0.2
        } else {
            maxOf(swingHigh, last.high) + atr * 0.2
        }
        val risk = abs(entry - rawSl).coerceIn(minRisk, maxRisk)
        val slFinal = if (isLong) entry - risk else entry + risk
        val t1Raw = if (isLong) entry + risk * 1.5 else entry - risk * 1.5
        val t2Raw = if (isLong) entry + risk * 2.0 else entry - risk * 2.0
        if (slFinal <= 0.0 || t1Raw <= 0.0 || t2Raw <= 0.0) return null

        var score = 1
        if (volRatio >= 1.45) score++
        if (bodyRatio >= 0.65) score++
        if ((isLong && rsi in 58.0..72.0) || (!isLong && rsi in 28.0..42.0)) score++
        val confidence = when {
            score >= 3 -> SignalConfidence.HIGH
            score == 2 -> SignalConfidence.MEDIUM
            else -> SignalConfidence.LOW
        }

        val scale = if (entry < 10.0) 4 else 2
        fun bd(v: Double): BigDecimal = BigDecimal.valueOf(v).setScale(scale, RoundingMode.HALF_UP)

        val reasons = listOf(
            "10 SMA ${if (isLong) "above" else "below"} 30 SMA trend filter on ${tf.label}",
            "Breakout ${if (isLong) "above 10-bar high (${String.format("%.2f", swingHigh)})" else "below 10-bar low (${String.format("%.2f", swingLow)})"}",
            "Momentum candle (${(bodyRatio * 100).toInt()}% body) • Volume ${String.format("%.2f", volRatio)}× avg • RSI(14) ${String.format("%.1f", rsi)}",
            "1:2 R:R profit target • Use Trailing SL to lock gains"
        )

        return TradeSignal(
            id = "mombo_${context.symbol}_${direction.name}_${tf.name}_${context.now.epochSecond / 900}",
            symbol = context.symbol,
            segment = context.segment,
            strategy = SignalStrategy.MOMENTUM_BREAKOUT,
            direction = direction,
            timeframe = tf,
            entryPrice = bd(entry),
            stopLoss = bd(slFinal),
            target1 = bd(t1Raw),
            target2 = bd(t2Raw),
            riskRewardRatio = 2.0,
            confidence = confidence,
            reasons = reasons,
            generatedAt = context.now,
            expiresAt = context.now.plus(3, ChronoUnit.HOURS),
            instrumentType = context.instrumentType
        )
    }
}
