package com.example.domain.signal.evaluators

import com.example.domain.analysis.AdvancedIndicators
import com.example.domain.signal.SignalConfidence
import com.example.domain.signal.SignalContext
import com.example.domain.signal.SignalDirection
import com.example.domain.signal.SignalStrategy
import com.example.domain.signal.StrategyEvaluator
import com.example.domain.signal.TechnicalIndicators
import com.example.domain.signal.Timeframe
import com.example.domain.signal.TradeSignal
import kotlin.math.max

class SwigHybridEvaluator : StrategyEvaluator {
    override val strategy: SignalStrategy = SignalStrategy.SWIG

    private val higherTimeframes = listOf(Timeframe.D1, Timeframe.H4, Timeframe.H1)

    override fun evaluate(context: SignalContext): TradeSignal? {
        val htfPair = higherTimeframes.firstNotNullOfOrNull { tf ->
            val list = context.candlesByTimeframe[tf]
            if (list != null && list.size >= 52) tf to list else null
        } ?: return null

        val ltfCandles = context.candlesByTimeframe[Timeframe.M15].orEmpty()
        if (ltfCandles.size < 24) return null

        val (htf, htfCandles) = htfPair
        val htfCloses = htfCandles.map { it.close }
        val htfEma50 = TechnicalIndicators.ema(htfCloses, 50)
        val htfEma21 = TechnicalIndicators.ema(htfCloses, 21)
        val htfRsi = TechnicalIndicators.rsi(htfCloses, 14)

        val htfLast = htfCandles.lastIndex
        val htfClose = htfCandles[htfLast].close
        val htf50Curr = htfEma50[htfLast] ?: return null
        val htf50Prev = htfEma50[htfLast - 2] ?: return null
        val htf21Curr = htfEma21[htfLast] ?: return null
        val htfRsiCurr = htfRsi[htfLast] ?: return null

        val htfBias = when {
            htfClose > htf50Curr && htf21Curr > htf50Curr && htf50Curr >= htf50Prev && htfRsiCurr in 50.0..75.0 -> SignalDirection.LONG
            htfClose < htf50Curr && htf21Curr < htf50Curr && htf50Curr <= htf50Prev && htfRsiCurr in 25.0..50.0 -> SignalDirection.SHORT
            else -> return null
        }

        val ltfCloses = ltfCandles.map { it.close }
        val ltfEma9 = TechnicalIndicators.ema(ltfCloses, 9)
        val ltfEma21 = TechnicalIndicators.ema(ltfCloses, 21)
        val ltfRsi = TechnicalIndicators.rsi(ltfCloses, 14)
        val ltfAtr = TechnicalIndicators.atr(ltfCandles, 14)
        // Session-anchored VWAP (resets each trading day) instead of an arbitrary rolling window.
        val ltfVwap = AdvancedIndicators.vwapWithBands(ltfCandles, context.segment).map { it?.vwap }

        val ltfLast = ltfCandles.lastIndex
        val ltfClose = ltfCandles[ltfLast].close
        val ltf9Curr = ltfEma9[ltfLast] ?: return null
        val ltf21Curr = ltfEma21[ltfLast] ?: return null
        val ltfRsiCurr = ltfRsi[ltfLast] ?: return null
        val ltfAtrCurr = ltfAtr[ltfLast] ?: return null
        val ltfVwapCurr = ltfVwap.lastOrNull() ?: return null
        if (ltfClose <= 0.0 || ltfAtrCurr <= 0.0) return null

        val ltfTrigger = when {
            ltf9Curr > ltf21Curr && ltfClose > ltfVwapCurr && ltfRsiCurr in 52.0..75.0 -> SignalDirection.LONG
            ltf9Curr < ltf21Curr && ltfClose < ltfVwapCurr && ltfRsiCurr in 25.0..48.0 -> SignalDirection.SHORT
            else -> return null
        }

        // Strict alignment check: HTF swing bias must match LTF intraday trigger
        if (htfBias != ltfTrigger) return null

        val entry = ltfClose
        val slDistance = max(1.5 * ltfAtrCurr, entry * 0.002)
        val slDouble = if (htfBias == SignalDirection.LONG) {
            (entry - slDistance).coerceAtLeast(entry * 0.4)
        } else {
            entry + slDistance
        }
        val t1Double = if (htfBias == SignalDirection.LONG) entry + slDistance * 1.5 else (entry - slDistance * 1.5).coerceAtLeast(entry * 0.1)
        val t2Double = if (htfBias == SignalDirection.LONG) entry + slDistance * 2.5 else (entry - slDistance * 2.5).coerceAtLeast(entry * 0.05)

        val ltfPrev9 = ltfEma9[ltfLast - 1]
        val ltfPrev21 = ltfEma21[ltfLast - 1]
        val freshLtfCross = ltfPrev9 != null && ltfPrev21 != null &&
            ((htfBias == SignalDirection.LONG && ltfPrev9 <= ltfPrev21) ||
                (htfBias == SignalDirection.SHORT && ltfPrev9 >= ltfPrev21))

        val confidence = if (freshLtfCross || htf == Timeframe.D1) SignalConfidence.HIGH else SignalConfidence.MEDIUM

        val reasons = listOf(
            "${htf.label} Swing bias ${htfBias.name} (EMA21 ${if (htfBias == SignalDirection.LONG) ">" else "<"} EMA50, RSI ${String.format("%.1f", htfRsiCurr)})",
            "15m trigger aligned with ${htf.label} bias (EMA9/EMA21 + VWAP)",
            "15m RSI momentum at ${String.format("%.1f", ltfRsiCurr)}",
            "ATR-scaled 1.5x SL (${String.format("%.2f", slDouble)}) • Target 2.5R"
        )

        val ref = context.currentLtp
        return TradeSignal(
            id = "${context.symbol}_${strategy.name}_${Timeframe.M15.name}_${htfBias.name}_${ltfCandles[ltfLast].timestamp.epochSecond}",
            symbol = context.symbol,
            segment = context.segment,
            strategy = strategy,
            direction = htfBias,
            timeframe = Timeframe.M15,
            entryPrice = TechnicalIndicators.roundPrice(entry, ref),
            stopLoss = TechnicalIndicators.roundPrice(slDouble, ref),
            target1 = TechnicalIndicators.roundPrice(t1Double, ref),
            target2 = TechnicalIndicators.roundPrice(t2Double, ref),
            riskRewardRatio = 2.5,
            confidence = confidence,
            reasons = reasons,
            generatedAt = context.now,
            expiresAt = context.now.plusSeconds(24 * 3600L)
        )
    }
}
