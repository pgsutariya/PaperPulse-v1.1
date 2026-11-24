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
 * 50 EMA + RSI(14) + MACD(12, 26, 9) Trend-Following Confirmation Strategy:
 * - Long: Price > 50 EMA, RSI(14) in 50..70, MACD crosses above Signal line (below or near zero).
 * - Short: Price < 50 EMA, RSI(14) in 30..50, MACD crosses below Signal line (above or near zero).
 * - Stop Loss: Recent swing low (Long) / swing high (Short) clamped to sane ATR bounds.
 * - Take Profit: T1 = 1.5× SL distance, T2 = 2.0× SL distance.
 */
class Ema50RsiMacdEvaluator : StrategyEvaluator {
    override val strategy: SignalStrategy = SignalStrategy.EMA50_RSI_MACD

    override fun evaluate(context: SignalContext): TradeSignal? {
        val tf = listOf(Timeframe.M15, Timeframe.H1, Timeframe.M5)
            .firstOrNull { (context.candlesByTimeframe[it]?.size ?: 0) >= 55 }
            ?: return null
        val candles = context.candlesByTimeframe[tf] ?: return null
        if (candles.size < 55) return null

        val closes = candles.map { it.close }
        val ema50 = TechnicalIndicators.ema(closes, 50)
        val rsi14 = TechnicalIndicators.rsi(closes, 14)
        val macd = TechnicalIndicators.macd(closes, 12, 26, 9)
        val atr14 = TechnicalIndicators.atr(candles, 14)

        val idx = candles.lastIndex
        val last = candles[idx]
        val e50 = ema50[idx] ?: return null
        val rsi = rsi14[idx] ?: return null
        val curMacd = macd[idx] ?: return null
        val prevMacd = macd[idx - 1] ?: return null
        val prev2Macd = macd.getOrNull(idx - 2)
        val atr = atr14[idx]?.takeIf { it > 0.0 } ?: ((last.high - last.low).coerceAtLeast(last.close * 0.004))

        val macdBullCross = (curMacd.macd > curMacd.signal && prevMacd.macd <= prevMacd.signal) ||
            (curMacd.macd > curMacd.signal && prevMacd.histogram > 0.0 && prev2Macd != null && prev2Macd.macd <= prev2Macd.signal)

        val macdBearCross = (curMacd.macd < curMacd.signal && prevMacd.macd >= prevMacd.signal) ||
            (curMacd.macd < curMacd.signal && prevMacd.histogram < 0.0 && prev2Macd != null && prev2Macd.macd >= prev2Macd.signal)

        val isLong = last.close > e50 &&
            rsi in 50.0..70.0 &&
            macdBullCross &&
            curMacd.macd <= atr * 0.45 // Prefer crossing below or near zero line

        val isShort = last.close < e50 &&
            rsi in 30.0..50.0 &&
            macdBearCross &&
            curMacd.macd >= -atr * 0.45 // Prefer crossing above or near zero line

        if (!isLong && !isShort) return null

        val direction = if (isLong) SignalDirection.LONG else SignalDirection.SHORT
        val entry = context.currentLtp.toDouble().takeIf { it > 0.0 } ?: last.close
        val minRisk = entry * 0.0035
        val maxRisk = entry * 0.045

        val swingSlice = candles.takeLast(8)
        val swingLow = swingSlice.minOf { it.low }
        val swingHigh = swingSlice.maxOf { it.high }

        val rawSl = if (isLong) swingLow - atr * 0.2 else swingHigh + atr * 0.2
        val risk = abs(entry - rawSl).coerceIn(minRisk, maxRisk)
        val slFinal = if (isLong) entry - risk else entry + risk
        val t1Raw = if (isLong) entry + risk * 1.5 else entry - risk * 1.5
        val t2Raw = if (isLong) entry + risk * 2.0 else entry - risk * 2.0
        if (slFinal <= 0.0 || t1Raw <= 0.0 || t2Raw <= 0.0) return null

        val zeroLineBonus = if (isLong) curMacd.macd <= 0.0 else curMacd.macd >= 0.0
        val emaSlopeBonus = (ema50[idx - 5]?.let { if (isLong) e50 > it else e50 < it } ?: false)
        val confidence = when {
            zeroLineBonus && emaSlopeBonus -> SignalConfidence.HIGH
            zeroLineBonus || emaSlopeBonus -> SignalConfidence.MEDIUM
            else -> SignalConfidence.LOW
        }

        val scale = if (entry < 10.0) 4 else 2
        fun bd(v: Double): BigDecimal = BigDecimal.valueOf(v).setScale(scale, RoundingMode.HALF_UP)

        val reasons = listOf(
            "Price ${if (isLong) "above" else "below"} 50 EMA (${String.format("%.2f", e50)}) on ${tf.label}",
            "RSI(14) at ${String.format("%.1f", rsi)} inside optimal ${if (isLong) "50–70" else "30–50"} trend momentum band",
            "MACD(12,26,9) ${if (isLong) "bullish" else "bearish"} signal crossover${if (zeroLineBonus) " with zero-line confirmation" else ""}",
            "Swing-structure SL • T1 = 1.5R, T2 = 2.0R"
        )

        return TradeSignal(
            id = "ema50macd_${context.symbol}_${direction.name}_${tf.name}_${context.now.epochSecond / 900}",
            symbol = context.symbol,
            segment = context.segment,
            strategy = SignalStrategy.EMA50_RSI_MACD,
            direction = direction,
            timeframe = tf,
            entryPrice = bd(entry),
            stopLoss = bd(slFinal),
            target1 = bd(t1Raw),
            target2 = bd(t2Raw),
            riskRewardRatio = 1.5,
            confidence = confidence,
            reasons = reasons,
            generatedAt = context.now,
            expiresAt = context.now.plus(4, ChronoUnit.HOURS),
            instrumentType = context.instrumentType
        )
    }
}
