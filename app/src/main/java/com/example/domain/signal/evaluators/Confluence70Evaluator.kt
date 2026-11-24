package com.example.domain.signal.evaluators

import com.example.domain.signal.Candle
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
import kotlin.math.max
import kotlin.math.min

/**
 * High-Probability 70% Multi-Confluence Strategy:
 * - 5 & 8 SMA crossing / stacking against 13 & 20 SMA
 * - Williams Fractals (5-bar period) swing high & low -> 50% Fibonacci Equilibrium retracement zone
 * - 50 EMA dynamic confluence + Fair Value Gap / Breaker Block overlap
 * - RSI(14) crossing its 9-period moving average signal line
 * - Target 1 at 2.0R, Target 2 toward 200 EMA / 3.5R swing liquidity
 */
class Confluence70Evaluator : StrategyEvaluator {
    override val strategy: SignalStrategy = SignalStrategy.CONFLUENCE_70

    override fun evaluate(context: SignalContext): TradeSignal? {
        val entryTf = listOf(Timeframe.M15, Timeframe.M30, Timeframe.H1, Timeframe.M5)
            .firstOrNull { (context.candlesByTimeframe[it]?.size ?: 0) >= 60 }
            ?: return null
        val candles = context.candlesByTimeframe[entryTf] ?: return null
        if (candles.size < 60) return null

        val htfCandles = listOf(Timeframe.H4, Timeframe.H1, Timeframe.D1)
            .firstNotNullOfOrNull { tf -> context.candlesByTimeframe[tf]?.takeIf { it.size >= 25 } }
            ?: candles

        val closes = candles.map { it.close }
        val sma5 = TechnicalIndicators.sma(closes, 5)
        val sma8 = TechnicalIndicators.sma(closes, 8)
        val sma13 = TechnicalIndicators.sma(closes, 13)
        val sma20 = TechnicalIndicators.sma(closes, 20)
        val ema50 = TechnicalIndicators.ema(closes, 50)
        val ema200 = if (closes.size >= 200) TechnicalIndicators.ema(closes, 200) else TechnicalIndicators.ema(closes, minOf(100, closes.size - 1))
        val rsi14 = TechnicalIndicators.rsi(closes, 14)
        val atr14 = TechnicalIndicators.atr(candles, 14)

        val idx = candles.lastIndex
        val last = candles[idx]
        val prev = candles[idx - 1]
        val s5 = sma5[idx] ?: return null
        val s8 = sma8[idx] ?: return null
        val s13 = sma13[idx] ?: return null
        val s20 = sma20[idx] ?: return null
        val e50 = ema50[idx] ?: return null
        val e200 = ema200[idx]
        val rsi = rsi14[idx] ?: return null
        val prevRsi = rsi14[idx - 1] ?: rsi
        val atr = atr14[idx]?.takeIf { it > 0.0 } ?: ((last.high - last.low).coerceAtLeast(last.close * 0.004))

        // RSI 9-period moving average (signal line)
        val recentRsi = rsi14.drop(maxOf(0, idx - 8)).filterNotNull()
        val rsiMa = if (recentRsi.size >= 5) recentRsi.average() else 50.0

        // Williams 5-bar Fractals on higher timeframe (or current TF) for 50% Fib Equilibrium
        val (swingHigh, swingLow) = findWilliamsFractalRange(htfCandles)
        val fibSpan = (swingHigh - swingLow).coerceAtLeast(atr)
        val fib50 = swingLow + fibSpan * 0.50
        val fib618Bear = swingLow + fibSpan * 0.618
        val fib382Bull = swingLow + fibSpan * 0.382

        // Check SMA 5/8 vs 13/20 crossover or fresh stack
        val fastBull = s5 > s13 && s8 > s20
        val fastBear = s5 < s13 && s8 < s20
        val recentBullCross = (1..4).any { offset ->
            val p = idx - offset
            val ps5 = sma5.getOrNull(p) ?: return@any false
            val ps13 = sma13.getOrNull(p) ?: return@any false
            ps5 <= ps13
        }
        val recentBearCross = (1..4).any { offset ->
            val p = idx - offset
            val ps5 = sma5.getOrNull(p) ?: return@any false
            val ps13 = sma13.getOrNull(p) ?: return@any false
            ps5 >= ps13
        }

        // Breaker / FVG zone check in recent 18 bars
        val hasBullishFvgOrBreaker = hasRecentFvg(candles, bullish = true) ||
            (abs(last.low - e50) <= atr * 1.1 && last.close > e50)
        val hasBearishFvgOrBreaker = hasRecentFvg(candles, bullish = false) ||
            (abs(last.high - e50) <= atr * 1.1 && last.close < e50)

        val nearFibBullZone = last.low <= fib50 * 1.012 && last.close >= fib382Bull * 0.992
        val nearFibBearZone = last.high >= fib50 * 0.988 && last.close <= fib618Bear * 1.008
        val nearEma50 = abs(last.close - e50) <= atr * 1.6

        val rsiBullSignal = rsi > rsiMa && rsi in 42.0..70.0 && rsi >= prevRsi - 1.5
        val rsiBearSignal = rsi < rsiMa && rsi in 30.0..58.0 && rsi <= prevRsi + 1.5

        var bullConfluences = 0
        if (fastBull) bullConfluences++
        if (recentBullCross) bullConfluences++
        if (nearFibBullZone) bullConfluences++
        if (nearEma50 && last.close >= e50 * 0.996) bullConfluences++
        if (hasBullishFvgOrBreaker) bullConfluences++
        if (rsiBullSignal) bullConfluences++
        if (last.close > last.open || TechnicalIndicators.isBullishReversalCandle(prev, last)) bullConfluences++

        var bearConfluences = 0
        if (fastBear) bearConfluences++
        if (recentBearCross) bearConfluences++
        if (nearFibBearZone) bearConfluences++
        if (nearEma50 && last.close <= e50 * 1.004) bearConfluences++
        if (hasBearishFvgOrBreaker) bearConfluences++
        if (rsiBearSignal) bearConfluences++
        if (last.close < last.open || TechnicalIndicators.isBearishReversalCandle(prev, last)) bearConfluences++

        val isLong = fastBull && bullConfluences >= 4 && bullConfluences > bearConfluences
        val isShort = fastBear && bearConfluences >= 4 && bearConfluences > bullConfluences
        if (!isLong && !isShort) return null

        val direction = if (isLong) SignalDirection.LONG else SignalDirection.SHORT
        val entry = context.currentLtp.toDouble().takeIf { it > 0.0 } ?: last.close
        val minRisk = entry * 0.004
        val maxRisk = entry * 0.045
        val rawRisk = (atr * 1.35).coerceIn(minRisk, maxRisk)

        val slRaw = if (isLong) {
            val structLow = min(last.low, prev.low) - atr * 0.35
            min(entry - minRisk, max(structLow, entry - rawRisk))
        } else {
            val structHigh = max(last.high, prev.high) + atr * 0.35
            max(entry + minRisk, min(structHigh, entry + rawRisk))
        }
        val risk = abs(entry - slRaw).coerceIn(minRisk, maxRisk)
        val slFinal = if (isLong) entry - risk else entry + risk
        val t1Raw = if (isLong) entry + risk * 2.0 else entry - risk * 2.0
        val ema200Magnet = e200?.takeIf {
            if (isLong) it > t1Raw && it <= entry + risk * 4.5
            else it < t1Raw && it >= entry - risk * 4.5
        }
        val t2Raw = ema200Magnet ?: if (isLong) entry + risk * 3.2 else entry - risk * 3.2
        if (slFinal <= 0.0 || t1Raw <= 0.0 || t2Raw <= 0.0) return null

        val score = if (isLong) bullConfluences else bearConfluences
        val confidence = when {
            score >= 6 -> SignalConfidence.HIGH
            score >= 5 -> SignalConfidence.MEDIUM
            else -> SignalConfidence.LOW
        }

        val scale = if (entry < 10.0) 4 else 2
        fun bd(v: Double): BigDecimal = BigDecimal.valueOf(v).setScale(scale, RoundingMode.HALF_UP)

        val reasons = buildList {
            add("SMA 5/8 ${if (isLong) "bullish" else "bearish"} alignment over SMA 13/20 on ${entryTf.label}")
            add("Williams 5-bar Fractal Fib 50% Equilibrium @ ${String.format("%.2f", fib50)}")
            add("50 EMA confluence (${String.format("%.2f", e50)}) + ${if (isLong) "Bullish" else "Bearish"} Breaker/FVG zone")
            add("RSI(14) ${String.format("%.1f", rsi)} ${if (isLong) "above" else "below"} signal MA (${String.format("%.1f", rsiMa)}) • $score/7 confluences")
        }

        return TradeSignal(
            id = "conf70_${context.symbol}_${direction.name}_${entryTf.name}_${context.now.epochSecond / 900}",
            symbol = context.symbol,
            segment = context.segment,
            strategy = SignalStrategy.CONFLUENCE_70,
            direction = direction,
            timeframe = entryTf,
            entryPrice = bd(entry),
            stopLoss = bd(slFinal),
            target1 = bd(t1Raw),
            target2 = bd(t2Raw),
            riskRewardRatio = 2.0,
            confidence = confidence,
            reasons = reasons,
            generatedAt = context.now,
            expiresAt = context.now.plus(6, ChronoUnit.HOURS),
            instrumentType = context.instrumentType
        )
    }

    private fun findWilliamsFractalRange(candles: List<Candle>): Pair<Double, Double> {
        val window = candles.takeLast(50)
        var latestFractalHigh: Double? = null
        var latestFractalLow: Double? = null
        for (i in 2 until window.size - 2) {
            val h = window[i].high
            val l = window[i].low
            if (h > window[i - 2].high && h > window[i - 1].high && h > window[i + 1].high && h > window[i + 2].high) {
                latestFractalHigh = max(latestFractalHigh ?: h, h)
            }
            if (l < window[i - 2].low && l < window[i - 1].low && l < window[i + 1].low && l < window[i + 2].low) {
                latestFractalLow = min(latestFractalLow ?: l, l)
            }
        }
        val high = latestFractalHigh ?: window.maxOf { it.high }
        val low = latestFractalLow ?: window.minOf { it.low }
        return high to low
    }

    private fun hasRecentFvg(candles: List<Candle>, bullish: Boolean): Boolean {
        val slice = candles.takeLast(18)
        for (i in 2 until slice.size) {
            if (bullish && slice[i].low > slice[i - 2].high) return true
            if (!bullish && slice[i].high < slice[i - 2].low) return true
        }
        return false
    }
}
