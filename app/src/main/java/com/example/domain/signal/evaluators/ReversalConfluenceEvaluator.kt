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

class ReversalConfluenceEvaluator : StrategyEvaluator {
    override val strategy: SignalStrategy = SignalStrategy.REVERSAL

    private val candidateTimeframes = listOf(Timeframe.H1, Timeframe.M15)

    override fun evaluate(context: SignalContext): TradeSignal? {
        for (tf in candidateTimeframes) {
            val candles = context.candlesByTimeframe[tf].orEmpty()
            if (candles.size < 36) continue

            val closes = candles.map { it.close }
            val rsi = TechnicalIndicators.rsi(closes, 14)
            val macd = TechnicalIndicators.macd(closes)
            val williamsR = TechnicalIndicators.williamsR(candles, 14)
            val bb = TechnicalIndicators.bollingerBands(closes, 20, 2.0)
            val atr = TechnicalIndicators.atr(candles, 14)
            val volMa = TechnicalIndicators.sma(candles.map { it.volume }, 20)

            val last = candles.lastIndex
            val prev = last - 1
            val currCandle = candles[last]
            val prevCandle = candles[prev]

            val rsiCurr = rsi[last] ?: continue
            val rsiRecent = rsi.subList(max(0, last - 3), last + 1).filterNotNull()
            val macdCurr = macd[last] ?: continue
            val macdPrev = macd[prev] ?: continue
            val wrCurr = williamsR[last] ?: continue
            val wrRecent = williamsR.subList(max(0, last - 3), last + 1).filterNotNull()
            val bbCurr = bb[last] ?: continue
            val bbPrev = bb[prev] ?: continue
            val atrCurr = atr[last] ?: continue
            if (atrCurr <= 0.0 || currCandle.close <= 0.0) continue

            val volMaCurr = volMa[last] ?: 0.0
            val volumeSpike = currCandle.volume > 0.0 && volMaCurr > 0.0 && currCandle.volume >= 1.5 * volMaCurr

            val bullishCandle = TechnicalIndicators.isBullishReversalCandle(prevCandle, currCandle)
            val bearishCandle = TechnicalIndicators.isBearishReversalCandle(prevCandle, currCandle)

            // Bullish confluence checklist (must have reversal candle + >= 3 indicator confirmations)
            if (bullishCandle) {
                val bullReasons = mutableListOf("Bullish reversal candle (hammer/pin/engulfing)")
                var score = 0
                if (rsiRecent.minOrNull()?.let { it <= 32.0 } == true && rsiCurr > rsiRecent.min()) {
                    score++
                    bullReasons.add("RSI recovering from oversold (${String.format("%.1f", rsiCurr)})")
                }
                if (macdCurr.histogram > macdPrev.histogram && (macdPrev.histogram < 0.0 || macdCurr.macd > macdCurr.signal)) {
                    score++
                    bullReasons.add("MACD histogram turning positive")
                }
                if (wrRecent.minOrNull()?.let { it <= -80.0 } == true && wrCurr > wrRecent.min()) {
                    score++
                    bullReasons.add("Williams %R rebounding above -80 (${String.format("%.1f", wrCurr)})")
                }
                if (currCandle.low <= bbCurr.lower * 1.003 || prevCandle.low <= bbPrev.lower * 1.003) {
                    score++
                    bullReasons.add("Bollinger Band lower extreme test & recovery")
                }
                if (volumeSpike) {
                    score++
                    bullReasons.add("Volume surge >= 1.5x 20-bar avg")
                }

                if (score >= 3) {
                    val entry = currCandle.close
                    val slDouble = (currCandle.low - 0.5 * atrCurr).coerceAtLeast(entry * 0.4)
                    val risk = max(abs(entry - slDouble), 0.6 * atrCurr)
                    val t1Double = entry + risk * 1.5
                    val t2Double = entry + risk * 2.5
                    val confidence = if (score >= 4) SignalConfidence.HIGH else SignalConfidence.MEDIUM
                    val ref = context.currentLtp
                    return TradeSignal(
                        id = "${context.symbol}_${strategy.name}_${tf.name}_LONG_${currCandle.timestamp.epochSecond}",
                        symbol = context.symbol,
                        segment = context.segment,
                        strategy = strategy,
                        direction = SignalDirection.LONG,
                        timeframe = tf,
                        entryPrice = TechnicalIndicators.roundPrice(entry, ref),
                        stopLoss = TechnicalIndicators.roundPrice(slDouble, ref),
                        target1 = TechnicalIndicators.roundPrice(t1Double, ref),
                        target2 = TechnicalIndicators.roundPrice(t2Double, ref),
                        riskRewardRatio = 2.5,
                        confidence = confidence,
                        reasons = bullReasons,
                        generatedAt = context.now,
                        expiresAt = context.now.plusSeconds(12 * 3600L)
                    )
                }
            }

            // Bearish confluence checklist
            if (bearishCandle) {
                val bearReasons = mutableListOf("Bearish reversal candle (shooting star/pin/engulfing)")
                var score = 0
                if (rsiRecent.maxOrNull()?.let { it >= 68.0 } == true && rsiCurr < rsiRecent.max()) {
                    score++
                    bearReasons.add("RSI rolling over from overbought (${String.format("%.1f", rsiCurr)})")
                }
                if (macdCurr.histogram < macdPrev.histogram && (macdPrev.histogram > 0.0 || macdCurr.macd < macdCurr.signal)) {
                    score++
                    bearReasons.add("MACD histogram turning negative")
                }
                if (wrRecent.maxOrNull()?.let { it >= -20.0 } == true && wrCurr < wrRecent.max()) {
                    score++
                    bearReasons.add("Williams %R dropping from -20 (${String.format("%.1f", wrCurr)})")
                }
                if (currCandle.high >= bbCurr.upper * 0.997 || prevCandle.high >= bbPrev.upper * 0.997) {
                    score++
                    bearReasons.add("Bollinger Band upper extreme rejection")
                }
                if (volumeSpike) {
                    score++
                    bearReasons.add("Volume surge >= 1.5x 20-bar avg")
                }

                if (score >= 3) {
                    val entry = currCandle.close
                    val slDouble = currCandle.high + 0.5 * atrCurr
                    val risk = max(abs(slDouble - entry), 0.6 * atrCurr)
                    val t1Double = (entry - risk * 1.5).coerceAtLeast(entry * 0.1)
                    val t2Double = (entry - risk * 2.5).coerceAtLeast(entry * 0.05)
                    val confidence = if (score >= 4) SignalConfidence.HIGH else SignalConfidence.MEDIUM
                    val ref = context.currentLtp
                    return TradeSignal(
                        id = "${context.symbol}_${strategy.name}_${tf.name}_SHORT_${currCandle.timestamp.epochSecond}",
                        symbol = context.symbol,
                        segment = context.segment,
                        strategy = strategy,
                        direction = SignalDirection.SHORT,
                        timeframe = tf,
                        entryPrice = TechnicalIndicators.roundPrice(entry, ref),
                        stopLoss = TechnicalIndicators.roundPrice(slDouble, ref),
                        target1 = TechnicalIndicators.roundPrice(t1Double, ref),
                        target2 = TechnicalIndicators.roundPrice(t2Double, ref),
                        riskRewardRatio = 2.5,
                        confidence = confidence,
                        reasons = bearReasons,
                        generatedAt = context.now,
                        expiresAt = context.now.plusSeconds(12 * 3600L)
                    )
                }
            }
        }
        return null
    }
}
