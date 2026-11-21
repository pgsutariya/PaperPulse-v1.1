package com.example.domain.signal.evaluators

import com.example.domain.signal.SignalConfidence
import com.example.domain.signal.SignalContext
import com.example.domain.signal.SignalDirection
import com.example.domain.signal.SignalStrategy
import com.example.domain.signal.StrategyEvaluator
import com.example.domain.signal.TechnicalIndicators
import com.example.domain.signal.Timeframe
import com.example.domain.signal.TradeSignal
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class SwingHeikinAshiEvaluator : StrategyEvaluator {
    override val strategy: SignalStrategy = SignalStrategy.SWING

    private val swingTimeframes = listOf(Timeframe.D1, Timeframe.H4)

    override fun evaluate(context: SignalContext): TradeSignal? {
        for (tf in swingTimeframes) {
            val candles = context.candlesByTimeframe[tf].orEmpty()
            if (candles.size < 54) continue

            val closes = candles.map { it.close }
            val ema50 = TechnicalIndicators.ema(closes, 50)
            val rsi = TechnicalIndicators.rsi(closes, 14)
            val atr = TechnicalIndicators.atr(candles, 14)
            val ha = TechnicalIndicators.heikinAshi(candles)

            val last = candles.lastIndex
            val e50Curr = ema50[last] ?: continue
            val e50SlopeBase = ema50[last - 3] ?: continue
            val rsiCurr = rsi[last] ?: continue
            val atrCurr = atr[last] ?: continue
            val haCurr = ha[last]
            val entry = candles[last].close
            if (entry <= 0.0 || atrCurr < entry * 0.002) continue

            val haRange = (haCurr.high - haCurr.low).takeIf { it > 0.0 } ?: continue
            val lowerWick = min(haCurr.open, haCurr.close) - haCurr.low
            val upperWick = haCurr.high - max(haCurr.open, haCurr.close)

            // Condition 1: Close aligned with EMA 50
            // Condition 2: EMA 50 slope aligned
            // Condition 3: Strong Heikin Ashi candle (no adverse wick)
            // Condition 4: RSI in healthy trend zone (50..70 long, 30..50 short) + ATR expansion
            val strongBullHa = haCurr.close > haCurr.open &&
                lowerWick <= haRange * 0.05 &&
                (haCurr.close - haCurr.open) >= haRange * 0.45
            val strongBearHa = haCurr.close < haCurr.open &&
                upperWick <= haRange * 0.05 &&
                (haCurr.open - haCurr.close) >= haRange * 0.45

            val longOk = entry > e50Curr &&
                e50Curr > e50SlopeBase &&
                strongBullHa &&
                rsiCurr in 50.0..70.0

            val shortOk = entry < e50Curr &&
                e50Curr < e50SlopeBase &&
                strongBearHa &&
                rsiCurr in 30.0..50.0

            val direction = when {
                longOk -> SignalDirection.LONG
                shortOk -> SignalDirection.SHORT
                else -> continue
            }

            val recentAtrVals = atr.subList(max(0, last - 10), last + 1).filterNotNull()
            val avgAtr = if (recentAtrVals.isNotEmpty()) recentAtrVals.average() else atrCurr
            if (atrCurr < avgAtr * 0.75) continue

            val swingWindow = candles.subList(max(0, last - 5), last + 1)
            val slDouble = if (direction == SignalDirection.LONG) {
                val swingLow = swingWindow.minOf { it.low }
                swingLow.coerceIn(entry - 2.0 * atrCurr, entry - 1.5 * atrCurr)
                    .coerceAtLeast(entry * 0.4)
            } else {
                val swingHigh = swingWindow.maxOf { it.high }
                swingHigh.coerceIn(entry + 1.5 * atrCurr, entry + 2.0 * atrCurr)
            }

            val risk = max(abs(entry - slDouble), 1.0 * atrCurr)
            val t1Double = if (direction == SignalDirection.LONG) entry + risk * 2.0 else entry - risk * 2.0
            val t2Double = if (direction == SignalDirection.LONG) entry + risk * 3.0 else entry - risk * 3.0
            if (t1Double <= 0.0 || t2Double <= 0.0) continue

            val haPrev = ha[last - 1]
            val twoHaStreak = if (direction == SignalDirection.LONG) haPrev.close > haPrev.open else haPrev.close < haPrev.open
            val confidence = if (twoHaStreak && tf == Timeframe.D1) SignalConfidence.HIGH else SignalConfidence.MEDIUM

            val reasons = listOf(
                "Strong ${if (direction == SignalDirection.LONG) "bullish" else "bearish"} Heikin Ashi bar (no ${if (direction == SignalDirection.LONG) "lower" else "upper"} wick)",
                "Price & rising/falling EMA50 (${String.format("%.2f", e50Curr)}) trend aligned on ${tf.label}",
                "RSI(14) in healthy swing zone (${String.format("%.1f", rsiCurr)})",
                "ATR(14) volatility filter confirmed (${String.format("%.2f", atrCurr)})"
            )

            val ref = context.currentLtp
            return TradeSignal(
                id = "${context.symbol}_${strategy.name}_${tf.name}_${direction.name}_${candles[last].timestamp.epochSecond}",
                symbol = context.symbol,
                segment = context.segment,
                strategy = strategy,
                direction = direction,
                timeframe = tf,
                entryPrice = TechnicalIndicators.roundPrice(entry, ref),
                stopLoss = TechnicalIndicators.roundPrice(slDouble, ref),
                target1 = TechnicalIndicators.roundPrice(t1Double, ref),
                target2 = TechnicalIndicators.roundPrice(t2Double, ref),
                riskRewardRatio = 3.0,
                confidence = confidence,
                reasons = reasons,
                generatedAt = context.now,
                expiresAt = context.now.plusSeconds(14L * 86_400L)
            )
        }
        return null
    }
}
