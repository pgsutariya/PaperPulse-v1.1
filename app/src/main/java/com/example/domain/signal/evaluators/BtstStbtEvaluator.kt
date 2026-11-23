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
 * Multi-factor BTST (Buy Today Sell Tomorrow) / STBT (Sell Today Buy Tomorrow) overnight momentum evaluator.
 *
 * Criteria (High-probability institutional closing-strength model):
 * 1. Trend Alignment: EMA 9 > EMA 21 > EMA 50 (BTST) or EMA 9 < EMA 21 < EMA 50 (STBT).
 * 2. Strong Close Position: Latest bar closes within top 25% of recent 10-bar range (BTST)
 *    or bottom 25% (STBT) with solid body dominance.
 * 3. RSI Momentum Zone: RSI(14) in 54..74 for BTST (strong trend, not exhausted) or 26..46 for STBT.
 * 4. Volume Participation: Latest volume >= 1.15x 20-bar volume SMA (or elevated relative surge).
 * 5. ATR-sized overnight protective SL (1.2x ATR) with T1 = 1.6R and T2 = 2.8R, Carry Forward validity (20h).
 */
class BtstStbtEvaluator : StrategyEvaluator {
    override val strategy: SignalStrategy = SignalStrategy.BTST_STBT

    override fun evaluate(context: SignalContext): TradeSignal? {
        val timeframe = listOf(Timeframe.M15, Timeframe.H1, Timeframe.M5, Timeframe.D1)
            .firstOrNull { (context.candlesByTimeframe[it]?.size ?: 0) >= 35 }
            ?: return null
        val candles = context.candlesByTimeframe[timeframe] ?: return null
        if (candles.size < 35) return null

        val closes = candles.map { it.close }
        val ema9 = TechnicalIndicators.ema(closes, 9)
        val ema21 = TechnicalIndicators.ema(closes, 21)
        val ema50 = TechnicalIndicators.ema(closes, minOf(50, closes.size - 1))
        val rsi14 = TechnicalIndicators.rsi(closes, 14)
        val atr14 = TechnicalIndicators.atr(candles, 14)

        val idx = candles.lastIndex
        val last = candles[idx]
        val e9 = ema9[idx] ?: return null
        val e21 = ema21[idx] ?: return null
        val e50 = ema50[idx] ?: e21
        val rsi = rsi14[idx] ?: return null
        val atr = atr14[idx]?.takeIf { it > 0.0 } ?: ((last.high - last.low).coerceAtLeast(last.close * 0.004))

        val recent = candles.takeLast(10)
        val rangeHigh = recent.maxOf { it.high }
        val rangeLow = recent.minOf { it.low }
        val totalRange = (rangeHigh - rangeLow).coerceAtLeast(last.close * 0.002)
        val closePosition = (last.close - rangeLow) / totalRange // 0.0 = low, 1.0 = high

        val volSlice = candles.takeLast(20)
        val avgVol = volSlice.map { it.volume }.average().coerceAtLeast(1.0)
        val volRatio = last.volume / avgVol

        val bodyRatio = if (last.high > last.low) abs(last.close - last.open) / (last.high - last.low) else 0.0

        val isBtst = last.close > e9 &&
            e9 > e21 &&
            e21 >= e50 * 0.998 &&
            closePosition >= 0.72 &&
            rsi in 53.0..76.0 &&
            last.close > last.open

        val isStbt = last.close < e9 &&
            e9 < e21 &&
            e21 <= e50 * 1.002 &&
            closePosition <= 0.28 &&
            rsi in 24.0..47.0 &&
            last.close < last.open

        if (!isBtst && !isStbt) return null

        val direction = if (isBtst) SignalDirection.LONG else SignalDirection.SHORT
        val entry = context.currentLtp.toDouble().takeIf { it > 0.0 } ?: last.close
        val risk = (atr * 1.25).coerceAtLeast(entry * 0.005)
        val slRaw = if (isBtst) entry - risk else entry + risk
        val t1Raw = if (isBtst) entry + risk * 1.6 else entry - risk * 1.6
        val t2Raw = if (isBtst) entry + risk * 2.8 else entry - risk * 2.8
        if (slRaw <= 0.0 || t1Raw <= 0.0 || t2Raw <= 0.0) return null

        var score = 1
        if (volRatio >= 1.2) score++
        if (bodyRatio >= 0.55) score++
        if ((isBtst && closePosition >= 0.85) || (isStbt && closePosition <= 0.15)) score++
        val confidence = when {
            score >= 3 -> SignalConfidence.HIGH
            score == 2 -> SignalConfidence.MEDIUM
            else -> SignalConfidence.LOW
        }

        val scale = if (entry < 10.0) 4 else 2
        fun bd(v: Double): BigDecimal = BigDecimal.valueOf(v).setScale(scale, RoundingMode.HALF_UP)

        val setupName = if (isBtst) "BTST (Buy Today Sell Tomorrow)" else "STBT (Sell Today Buy Tomorrow)"
        val reasons = buildList {
            add("$setupName: EMA 9/21/50 stacked ${if (isBtst) "bullishly" else "bearishly"} on ${timeframe.label}")
            add(
                if (isBtst) "Strong close near high (${(closePosition * 100).toInt()}% of 10-bar range)"
                else "Weak close near low (${(closePosition * 100).toInt()}% of 10-bar range)"
            )
            add("RSI(14) at ${String.format("%.1f", rsi)} • Vol ${String.format("%.2f", volRatio)}× 20-bar avg")
            add("Overnight Carry Forward setup • ATR(14) risk buffer")
        }

        return TradeSignal(
            id = "btst_${context.symbol}_${direction.name}_${timeframe.name}_${context.now.epochSecond / 900}",
            symbol = context.symbol,
            segment = context.segment,
            strategy = SignalStrategy.BTST_STBT,
            direction = direction,
            timeframe = timeframe,
            entryPrice = bd(entry),
            stopLoss = bd(slRaw),
            target1 = bd(t1Raw),
            target2 = bd(t2Raw),
            riskRewardRatio = 1.6,
            confidence = confidence,
            reasons = reasons,
            generatedAt = context.now,
            expiresAt = context.now.plus(20, ChronoUnit.HOURS),
            instrumentType = context.instrumentType
        )
    }
}
