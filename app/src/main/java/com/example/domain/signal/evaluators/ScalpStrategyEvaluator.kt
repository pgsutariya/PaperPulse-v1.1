package com.example.domain.signal.evaluators

import com.example.domain.signal.SignalConfidence
import com.example.domain.signal.SignalContext
import com.example.domain.signal.SignalDirection
import com.example.domain.signal.SignalStrategy
import com.example.domain.signal.StrategyEvaluator
import com.example.domain.signal.TechnicalIndicators
import com.example.domain.signal.Timeframe
import com.example.domain.signal.TradeSignal
import kotlin.math.max
import kotlin.math.min

class ScalpStrategyEvaluator : StrategyEvaluator {
    override val strategy: SignalStrategy = SignalStrategy.SCALP

    private val candidateTimeframes = listOf(Timeframe.M5, Timeframe.M3, Timeframe.M1)

    override fun evaluate(context: SignalContext): TradeSignal? {
        for (tf in candidateTimeframes) {
            val candles = context.candlesByTimeframe[tf].orEmpty()
            if (candles.size < 52) continue

            val closes = candles.map { it.close }
            val ema9 = TechnicalIndicators.ema(closes, 9)
            val ema21 = TechnicalIndicators.ema(closes, 21)
            val ema50 = TechnicalIndicators.ema(closes, 50)
            val rsi = TechnicalIndicators.rsi(closes, 14)
            val stoch = TechnicalIndicators.stochastic(candles, 14, 3)
            val atr = TechnicalIndicators.atr(candles, 14)

            val last = candles.lastIndex
            val prev = last - 1
            val e9Curr = ema9[last] ?: continue
            val e9Prev = ema9[prev] ?: continue
            val e21Curr = ema21[last] ?: continue
            val e21Prev = ema21[prev] ?: continue
            val e50Curr = ema50[last] ?: continue
            val rsiCurr = rsi[last] ?: continue
            val stochCurr = stoch[last]
            val atrCurr = atr[last] ?: continue

            val entry = candles[last].close
            if (entry <= 0.0 || atrCurr < entry * 0.0005) continue

            val recentBullCross = (e9Prev <= e21Prev && e9Curr > e21Curr) ||
                (last >= 2 && (ema9[last - 2] ?: Double.MAX_VALUE) <= (ema21[last - 2] ?: -Double.MAX_VALUE) && e9Prev > e21Prev && e9Curr > e21Curr)
            val recentBearCross = (e9Prev >= e21Prev && e9Curr < e21Curr) ||
                (last >= 2 && (ema9[last - 2] ?: -Double.MAX_VALUE) >= (ema21[last - 2] ?: Double.MAX_VALUE) && e9Prev < e21Prev && e9Curr < e21Curr)

            val longAligned = recentBullCross && entry > e50Curr
            val shortAligned = recentBearCross && entry < e50Curr

            val direction = when {
                longAligned -> SignalDirection.LONG
                shortAligned -> SignalDirection.SHORT
                else -> continue
            }

            val rsiMomentumOk = if (direction == SignalDirection.LONG) rsiCurr > 52.0 else rsiCurr < 48.0
            val stochMomentumOk = stochCurr != null && if (direction == SignalDirection.LONG) {
                stochCurr.k > stochCurr.d && stochCurr.k < 85.0
            } else {
                stochCurr.k < stochCurr.d && stochCurr.k > 15.0
            }
            if (!rsiMomentumOk && !stochMomentumOk) continue

            val swingWindow = candles.subList(max(0, last - 4), last + 1)
            val (slDouble, risk) = if (direction == SignalDirection.LONG) {
                val swingLow = swingWindow.minOf { it.low }
                val rawSl = min(swingLow - 0.5 * atrCurr, entry - atrCurr)
                val safeSl = rawSl.coerceAtLeast(entry * 0.5)
                safeSl to max(entry - safeSl, atrCurr * 0.5)
            } else {
                val swingHigh = swingWindow.maxOf { it.high }
                val rawSl = max(swingHigh + 0.5 * atrCurr, entry + atrCurr)
                rawSl to max(rawSl - entry, atrCurr * 0.5)
            }

            val t1Double = if (direction == SignalDirection.LONG) entry + risk * 1.0 else entry - risk * 1.0
            val t2Double = if (direction == SignalDirection.LONG) entry + risk * 1.5 else entry - risk * 1.5
            if (t1Double <= 0.0 || t2Double <= 0.0) continue

            val bothMomentum = rsiMomentumOk && stochMomentumOk
            val confidence = when {
                bothMomentum && tf == Timeframe.M5 -> SignalConfidence.HIGH
                bothMomentum || (e9Prev <= e21Prev && direction == SignalDirection.LONG) || (e9Prev >= e21Prev && direction == SignalDirection.SHORT) -> SignalConfidence.MEDIUM
                else -> SignalConfidence.LOW
            }

            val reasons = buildList {
                add("EMA9/EMA21 ${if (direction == SignalDirection.LONG) "bullish" else "bearish"} crossover (${tf.label})")
                add("Price ${if (direction == SignalDirection.LONG) "above" else "below"} EMA50 baseline (${String.format("%.2f", e50Curr)})")
                if (rsiMomentumOk) add("RSI(14) momentum aligned at ${String.format("%.1f", rsiCurr)}")
                stochCurr?.takeIf { stochMomentumOk }?.let { st ->
                    add("Stoch %K/${if (direction == SignalDirection.LONG) ">" else "<"}%D (${String.format("%.0f", st.k)}/${String.format("%.0f", st.d)})")
                }
                add("ATR volatility filter passed (${String.format("%.2f", atrCurr)})")
            }

            val expiryMillis = when (tf) {
                Timeframe.M1 -> 45 * 60_000L
                Timeframe.M3 -> 90 * 60_000L
                else -> 120 * 60_000L
            }

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
                riskRewardRatio = 1.5,
                confidence = confidence,
                reasons = reasons,
                generatedAt = context.now,
                expiresAt = context.now.plusMillis(expiryMillis)
            )
        }
        return null
    }
}
