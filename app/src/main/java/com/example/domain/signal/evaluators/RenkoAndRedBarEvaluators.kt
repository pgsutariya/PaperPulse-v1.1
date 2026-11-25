package com.example.domain.signal.evaluators

import com.example.domain.analysis.AiRenkoConfig
import com.example.domain.analysis.AiRenkoSupertrendEngine
import com.example.domain.analysis.RedBarBreakoutStatus
import com.example.domain.analysis.RedBarEventKind
import com.example.domain.analysis.RedBarIndicatorEngine
import com.example.domain.analysis.RenkoConviction
import com.example.domain.signal.SignalConfidence
import com.example.domain.signal.SignalContext
import com.example.domain.signal.SignalDirection
import com.example.domain.signal.SignalStrategy
import com.example.domain.signal.StrategyEvaluator
import com.example.domain.signal.TechnicalIndicators
import com.example.domain.signal.Timeframe
import com.example.domain.signal.TradeSignal
import kotlin.math.abs

/**
 * Generates live trade signals from the AI Renko Supertrend Engine
 * (synthetic Renko noise-filtering + adaptive ATR Supertrend + ADX/EMA filter).
 */
class AiRenkoSupertrendEvaluator(
    private val config: AiRenkoConfig = AiRenkoConfig()
) : StrategyEvaluator {

    override val strategy: SignalStrategy = SignalStrategy.AI_RENKO_SUPERTREND

    override fun evaluate(context: SignalContext): TradeSignal? {
        val candidateFrames = listOf(Timeframe.M5, Timeframe.M15, Timeframe.H1, Timeframe.M3)
        for (tf in candidateFrames) {
            val candles = context.candlesByTimeframe[tf] ?: continue
            if (candles.size < 35) continue

            val closes = candles.map { it.close }
            val ema21 = TechnicalIndicators.ema(closes, 21)
            val ema50 = TechnicalIndicators.ema(closes, 50)
            val snap = AiRenkoSupertrendEngine.compute(candles, ema21, ema50, config)

            val lastIdx = candles.lastIndex
            val lastCandle = candles[lastIdx]
            val trail = snap.currentTrail ?: continue
            val latestFlip = snap.signals.lastOrNull()

            // Trigger on a fresh flip within the last 3 bars, OR a high-conviction EMA+ADX aligned continuation
            val isFreshFlip = latestFlip != null && (lastIdx - latestFlip.index) <= 3
            val isAlignedTrend = snap.emaAligned && !snap.isChoppyMarket && (snap.currentAdx ?: 0.0) >= 20.0
            if (!isFreshFlip && !isAlignedTrend) continue

            // Avoid taking low-ADX chop signals unless it's a fresh EMA-aligned flip
            if (snap.isChoppyMarket && !snap.emaAligned) continue

            val isLong = snap.currentBullish
            val direction = if (isLong) SignalDirection.LONG else SignalDirection.SHORT
            val entry = lastCandle.close

            // Use adaptive Renko trail as primary SL, falling back to 1.8x adaptive brick if trail is too close/far
            val rawRisk = if (isLong) entry - trail else trail - entry
            val minRisk = (entry * 0.0018).coerceAtLeast(snap.currentBrickSize * 0.8)
            val maxRisk = entry * 0.06
            val risk = rawRisk.takeIf { it in minRisk..maxRisk }
                ?: (snap.currentBrickSize * 1.8).coerceIn(minRisk, maxRisk)

            val sl = if (isLong) entry - risk else entry + risk
            val t1 = if (isLong) entry + risk * 1.8 else entry - risk * 1.8
            val t2 = if (isLong) entry + risk * 3.0 else entry - risk * 3.0

            val confidence = when {
                isFreshFlip && latestFlip.conviction == RenkoConviction.HIGH -> SignalConfidence.HIGH
                snap.emaAligned && !snap.isChoppyMarket && (snap.currentAdx ?: 0.0) >= 22.0 -> SignalConfidence.HIGH
                !snap.isChoppyMarket -> SignalConfidence.MEDIUM
                else -> SignalConfidence.LOW
            }

            val reasons = buildList {
                if (isFreshFlip) {
                    val barsAgo = lastIdx - latestFlip.index
                    add(
                        "AI Renko Supertrend ${if (isLong) "BUY" else "SELL"} flip (${barsAgo}b ago) @ ${SignalBuilders.fmt(latestFlip.price)}"
                    )
                } else {
                    add("AI Renko Supertrend ${if (isLong) "Bullish" else "Bearish"} trend continuation above/below adaptive trail ${SignalBuilders.fmt(trail)}")
                }
                add("Adaptive brick ${SignalBuilders.fmt(snap.currentBrickSize)} • ${snap.volatilityRegime.label} (Factor ${SignalBuilders.fmt(snap.currentFactor)}x)")
                snap.currentAdx?.let {
                    add("ADX(14) = ${SignalBuilders.fmt(it)} (${if (snap.isChoppyMarket) "Caution: Choppy" else "Trending confirmed"})")
                }
                if (snap.emaAligned) {
                    add("EMA 21/50 trend structure aligned with Renko direction")
                }
            }

            val signal = SignalBuilders.build(
                context = context,
                strategy = strategy,
                timeframe = tf,
                direction = direction,
                signalCandle = lastCandle,
                entry = entry,
                stopLoss = sl,
                target1 = t1,
                target2 = t2,
                confidence = confidence,
                reasons = reasons,
                expiryBars = 14
            )
            if (signal != null) return signal
        }
        return null
    }
}

/**
 * Generates live intraday signals from the Red Bar Indicator:
 * 1. First Red Candle High/Low breakout or breakdown.
 * 2. 12:45 IST Midday reference range breakout or breakdown.
 * 3. Fresh Red Bar bearish rejection / engulfing reversal zones.
 */
class RedBarBreakoutEvaluator : StrategyEvaluator {

    override val strategy: SignalStrategy = SignalStrategy.RED_BAR_BREAKOUT

    override fun evaluate(context: SignalContext): TradeSignal? {
        val candidateFrames = listOf(Timeframe.M5, Timeframe.M15, Timeframe.M3)
        for (tf in candidateFrames) {
            val candles = context.candlesByTimeframe[tf] ?: continue
            if (candles.size < 15) continue

            val snap = RedBarIndicatorEngine.compute(candles, tf, context.segment)
            val lastIdx = candles.lastIndex
            val lastCandle = candles[lastIdx]
            val atr14 = TechnicalIndicators.atr(candles, 14).lastOrNull()
                ?: (lastCandle.high - lastCandle.low).coerceAtLeast(lastCandle.close * 0.002)

            // Priority 1: Fresh First Red Bar or 12:45 IST Breakout / Breakdown (within last 4 bars)
            val recentBreakout = snap.breakoutEvents
                .lastOrNull { (lastIdx - it.index) <= 4 }

            if (recentBreakout != null) {
                val isLong = recentBreakout.kind.bullish
                val refRange = when (recentBreakout.kind) {
                    RedBarEventKind.FIRST_RED_BREAKOUT, RedBarEventKind.FIRST_RED_BREAKDOWN -> snap.firstRedRange
                    RedBarEventKind.MIDDAY_1245_BREAKOUT, RedBarEventKind.MIDDAY_1245_BREAKDOWN -> snap.midday1245Range
                } ?: continue

                val entry = lastCandle.close
                val oppositeEdge = if (isLong) refRange.low else refRange.high
                val edgeRisk = abs(entry - oppositeEdge)
                val preferredSl = if (edgeRisk <= entry * 0.035 && edgeRisk >= entry * 0.0015) {
                    oppositeEdge
                } else {
                    refRange.midpoint
                }
                val risk = abs(entry - preferredSl).coerceIn(
                    (entry * 0.0018).coerceAtLeast(atr14 * 0.6),
                    entry * 0.05
                )
                val sl = if (isLong) entry - risk else entry + risk
                val t1 = if (isLong) entry + risk * 1.6 else entry - risk * 1.6
                val t2 = if (isLong) entry + risk * 2.6 else entry - risk * 2.6

                val barsAgo = lastIdx - recentBreakout.index
                val hadInsideBar = snap.insideBars.any { abs(it.index - recentBreakout.index) <= 3 }
                val confidence = when {
                    barsAgo <= 1 && hadInsideBar -> SignalConfidence.HIGH
                    barsAgo <= 2 -> SignalConfidence.HIGH
                    else -> SignalConfidence.MEDIUM
                }

                val reasons = buildList {
                    add("${refRange.label} ${if (isLong) "Breakout ↑" else "Breakdown ↓"} (${barsAgo}b ago)")
                    add("Locked range ${SignalBuilders.fmt(refRange.low)} – ${SignalBuilders.fmt(refRange.high)} (50% mid ${SignalBuilders.fmt(refRange.midpoint)})")
                    if (hadInsideBar) add("Preceded by Inside Bar consolidation tightening")
                    if (snap.hasLatestVolumePriceMismatch) {
                        add("Price/Volume divergence: ${if (snap.latestPriceCandleRed) "Red price" else "Green price"} vs ${if (snap.latestVolumeBarRed) "Red vol" else "Green vol"}")
                    }
                }

                val signal = SignalBuilders.build(
                    context = context,
                    strategy = strategy,
                    timeframe = tf,
                    direction = if (isLong) SignalDirection.LONG else SignalDirection.SHORT,
                    signalCandle = lastCandle,
                    entry = entry,
                    stopLoss = sl,
                    target1 = t1,
                    target2 = t2,
                    confidence = confidence,
                    reasons = reasons,
                    expiryBars = 10
                )
                if (signal != null) return signal
            }

            // Priority 2: Fresh Bearish Red Bar Reversal Zone (Engulfing / Rejection in last 2 bars)
            val recentZone = snap.reversalZones.lastOrNull { (lastIdx - it.originIndex) <= 2 }
            if (recentZone != null && lastCandle.close < recentZone.top) {
                val entry = lastCandle.close
                val risk = abs(recentZone.top - entry).coerceIn(
                    (entry * 0.0018).coerceAtLeast(atr14 * 0.7),
                    entry * 0.045
                )
                val sl = entry + risk
                val t1 = entry - risk * 1.6
                val t2 = entry - risk * 2.5

                val reasons = buildList {
                    add("${recentZone.label} active (${SignalBuilders.fmt(recentZone.bottom)} – ${SignalBuilders.fmt(recentZone.top)})")
                    snap.firstRedRange?.let {
                        add("First Red Bar status: ${it.status.label}")
                    }
                    if (snap.firstRedRange?.status == RedBarBreakoutStatus.BREAKDOWN_DOWN) {
                        add("Confluent with First Red Bar bearish breakdown")
                    }
                }

                val signal = SignalBuilders.build(
                    context = context,
                    strategy = strategy,
                    timeframe = tf,
                    direction = SignalDirection.SHORT,
                    signalCandle = lastCandle,
                    entry = entry,
                    stopLoss = sl,
                    target1 = t1,
                    target2 = t2,
                    confidence = if (snap.firstRedRange?.status == RedBarBreakoutStatus.BREAKDOWN_DOWN) {
                        SignalConfidence.HIGH
                    } else {
                        SignalConfidence.MEDIUM
                    },
                    reasons = reasons,
                    expiryBars = 8
                )
                if (signal != null) return signal
            }
        }
        return null
    }
}
