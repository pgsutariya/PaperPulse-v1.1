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
import com.example.domain.analysis.SessionCalendar
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class IntradayOrbVwapEvaluator : StrategyEvaluator {
    override val strategy: SignalStrategy = SignalStrategy.INTRADAY

    private val supportedTimeframes = listOf(Timeframe.M15, Timeframe.M5)

    override fun evaluate(context: SignalContext): TradeSignal? {
        for (tf in supportedTimeframes) {
            val candles = context.candlesByTimeframe[tf].orEmpty()
            val minBars = if (tf == Timeframe.M15) 18 else 22
            if (candles.size < minBars) continue

            // Session = current trading day in the exchange's zone (IST for NSE/BSE/MCX, UTC for crypto).
            // Never fall back to the whole history: an "opening range" from older days is meaningless.
            val lastDay = SessionCalendar.sessionDayKey(candles.last().timestamp.toEpochMilli(), context.segment)
            val activeSession = candles.filter {
                val ts = it.timestamp.toEpochMilli()
                SessionCalendar.sessionDayKey(ts, context.segment) == lastDay &&
                    SessionCalendar.isMarketOpen(ts, context.segment)
            }
            if (activeSession.size < 4) continue

            val orbCount = if (tf == Timeframe.M15) 1 else 3
            if (activeSession.size <= orbCount + 1) continue

            val orbWindow: List<Candle> = activeSession.take(orbCount)
            val orbHigh = orbWindow.maxOf { it.high }
            val orbLow = orbWindow.minOf { it.low }

            val closes = candles.map { it.close }
            val rsi = TechnicalIndicators.rsi(closes, 14)
            val atr = TechnicalIndicators.atr(candles, 14)
            val sessionVwap = TechnicalIndicators.vwap(activeSession)

            val last = candles.lastIndex
            val curr = candles[last]
            val prev = candles[last - 1]
            val rsiCurr = rsi[last] ?: continue
            val atrCurr = atr[last] ?: continue
            val vwapCurr = sessionVwap.lastOrNull() ?: continue
            if (atrCurr <= 0.0 || curr.close <= 0.0) continue

            val postOrb = activeSession.drop(orbCount)
            if (postOrb.size < 2) continue

            val hadBullBreakout = postOrb.dropLast(1).any { it.close > orbHigh }
            val retestBull = (curr.low <= orbHigh + 0.5 * atrCurr || prev.low <= orbHigh + 0.5 * atrCurr) &&
                curr.low >= orbLow - 0.25 * atrCurr &&
                curr.close > orbHigh &&
                curr.close >= curr.open
            val longSetup = hadBullBreakout && retestBull && curr.close > vwapCurr && rsiCurr >= 60.0

            val hadBearBreakout = postOrb.dropLast(1).any { it.close < orbLow }
            val retestBear = (curr.high >= orbLow - 0.5 * atrCurr || prev.high >= orbLow - 0.5 * atrCurr) &&
                curr.high <= orbHigh + 0.25 * atrCurr &&
                curr.close < orbLow &&
                curr.close <= curr.open
            val shortSetup = hadBearBreakout && retestBear && curr.close < vwapCurr && rsiCurr <= 40.0

            val direction = when {
                longSetup -> SignalDirection.LONG
                shortSetup -> SignalDirection.SHORT
                else -> continue
            }

            val entry = curr.close
            val slDouble = if (direction == SignalDirection.LONG) {
                val orbSl = orbHigh - 0.6 * atrCurr
                val atrSl = entry - 1.5 * atrCurr
                min(orbSl, atrSl).coerceAtLeast(entry * 0.5)
            } else {
                val orbSl = orbLow + 0.6 * atrCurr
                val atrSl = entry + 1.5 * atrCurr
                max(orbSl, atrSl)
            }

            val risk = max(abs(entry - slDouble), 0.5 * atrCurr)
            val t1Double = if (direction == SignalDirection.LONG) entry + risk else entry - risk
            val t2Double = if (direction == SignalDirection.LONG) entry + risk * 2.0 else entry - risk * 2.0
            if (t1Double <= 0.0 || t2Double <= 0.0) continue

            val strongMomentum = if (direction == SignalDirection.LONG) rsiCurr >= 65.0 else rsiCurr <= 35.0
            val confidence = if (strongMomentum && tf == Timeframe.M15) {
                SignalConfidence.HIGH
            } else {
                SignalConfidence.MEDIUM
            }

            val reasons = listOf(
                "Opening Range (${String.format("%.2f", orbLow)}–${String.format("%.2f", orbHigh)}) breakout + retest rejection",
                "Price ${if (direction == SignalDirection.LONG) "above" else "below"} session VWAP (${String.format("%.2f", vwapCurr)})",
                "RSI(14) confirmation at ${String.format("%.1f", rsiCurr)}",
                "Stop at ${String.format("%.2f", slDouble)} (move SL to breakeven after T1)"
            )

            val ref = context.currentLtp
            return TradeSignal(
                id = "${context.symbol}_${strategy.name}_${tf.name}_${direction.name}_${curr.timestamp.epochSecond}",
                symbol = context.symbol,
                segment = context.segment,
                strategy = strategy,
                direction = direction,
                timeframe = tf,
                entryPrice = TechnicalIndicators.roundPrice(entry, ref),
                stopLoss = TechnicalIndicators.roundPrice(slDouble, ref),
                target1 = TechnicalIndicators.roundPrice(t1Double, ref),
                target2 = TechnicalIndicators.roundPrice(t2Double, ref),
                riskRewardRatio = 2.0,
                confidence = confidence,
                reasons = reasons,
                generatedAt = context.now,
                expiresAt = context.now.plusSeconds(6 * 3600L)
            )
        }
        return null
    }
}
