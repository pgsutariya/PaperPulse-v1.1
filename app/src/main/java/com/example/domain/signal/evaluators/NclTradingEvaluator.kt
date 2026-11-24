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
 * NCL Trading (Narrow Candle / Narrow Range Consolidation Breakout) Strategy:
 * 1. Detects low-volatility compression on the prior candle (NR4/NR7 narrowest range of last 4–7 bars
 *    or Inside Bar with range <= 62% of ATR(14)).
 * 2. Fires on the immediate expansion breakout candle closing cleanly above the narrow candle high (Long)
 *    or below the narrow candle low (Short), aligned with 20 EMA trend and volume expansion.
 * 3. Tight protective SL on the opposite end of the narrow compression candle -> high 1:2 / 1:3 R:R!
 */
class NclTradingEvaluator : StrategyEvaluator {
    override val strategy: SignalStrategy = SignalStrategy.NCL_TRADING

    override fun evaluate(context: SignalContext): TradeSignal? {
        val tf = listOf(Timeframe.M15, Timeframe.M5, Timeframe.H1)
            .firstOrNull { (context.candlesByTimeframe[it]?.size ?: 0) >= 30 }
            ?: return null
        val candles = context.candlesByTimeframe[tf] ?: return null
        if (candles.size < 30) return null

        val idx = candles.lastIndex
        val breakout = candles[idx]
        val narrow = candles[idx - 1]
        val mother = candles[idx - 2]

        val closes = candles.map { it.close }
        val ema20 = TechnicalIndicators.ema(closes, 20)[idx] ?: return null
        val atr = TechnicalIndicators.atr(candles, 14)[idx]?.takeIf { it > 0.0 }
            ?: ((breakout.high - breakout.low).coerceAtLeast(breakout.close * 0.004))

        val narrowRange = narrow.high - narrow.low
        if (narrowRange <= 0.0) return null

        // Check NR4 / NR7 / Inside Bar compression on `narrow`
        val prior6Ranges = (idx - 7 until idx - 1).map { candles[it].high - candles[it].low }
        val isNr7 = prior6Ranges.all { narrowRange <= it * 1.03 }
        val isNr4 = prior6Ranges.takeLast(3).all { narrowRange <= it * 1.03 }
        val isInsideBar = narrow.high <= mother.high * 1.0005 && narrow.low >= mother.low * 0.9995
        val isTightAtr = narrowRange <= atr * 0.72

        if (!isNr4 && !isInsideBar && !isTightAtr) return null

        val avgVol = candles.subList(maxOf(0, idx - 20), idx).map { it.volume }.average().coerceAtLeast(1.0)
        val volRatio = breakout.volume / avgVol
        val breakoutRange = breakout.high - breakout.low

        val longBreakout = breakout.close > narrow.high &&
            breakout.close > breakout.open &&
            breakout.close > ema20 &&
            breakoutRange >= narrowRange * 1.15 &&
            volRatio >= 1.10

        val shortBreakout = breakout.close < narrow.low &&
            breakout.close < breakout.open &&
            breakout.close < ema20 &&
            breakoutRange >= narrowRange * 1.15 &&
            volRatio >= 1.10

        if (!longBreakout && !shortBreakout) return null

        val isLong = longBreakout
        val direction = if (isLong) SignalDirection.LONG else SignalDirection.SHORT
        val entry = context.currentLtp.toDouble().takeIf { it > 0.0 } ?: breakout.close
        val minRisk = entry * 0.003
        val maxRisk = entry * 0.038

        val rawSl = if (isLong) narrow.low - atr * 0.15 else narrow.high + atr * 0.15
        val risk = abs(entry - rawSl).coerceIn(minRisk, maxRisk)
        val slFinal = if (isLong) entry - risk else entry + risk
        val t1Raw = if (isLong) entry + risk * 2.0 else entry - risk * 2.0
        val t2Raw = if (isLong) entry + risk * 3.0 else entry - risk * 3.0
        if (slFinal <= 0.0 || t1Raw <= 0.0 || t2Raw <= 0.0) return null

        val patternTag = when {
            isNr7 && isInsideBar -> "NR7 + Inside Bar"
            isNr7 -> "NR7 Narrow Candle"
            isInsideBar -> "Inside Bar Compression"
            else -> "NR4 Narrow Candle"
        }
        val confidence = when {
            (isNr7 || isInsideBar) && volRatio >= 1.35 -> SignalConfidence.HIGH
            volRatio >= 1.15 -> SignalConfidence.MEDIUM
            else -> SignalConfidence.LOW
        }

        val scale = if (entry < 10.0) 4 else 2
        fun bd(v: Double): BigDecimal = BigDecimal.valueOf(v).setScale(scale, RoundingMode.HALF_UP)

        val reasons = listOf(
            "NCL $patternTag compression (${String.format("%.0f", (narrowRange / atr) * 100)}% of ATR) on ${tf.label}",
            "Expansion breakout ${if (isLong) "above narrow high" else "below narrow low"} aligned with 20 EMA",
            "Volume surge ${String.format("%.2f", volRatio)}× avg • Tight SL behind NCL range for 1:2 / 1:3 R:R"
        )

        return TradeSignal(
            id = "ncl_${context.symbol}_${direction.name}_${tf.name}_${context.now.epochSecond / 900}",
            symbol = context.symbol,
            segment = context.segment,
            strategy = SignalStrategy.NCL_TRADING,
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
