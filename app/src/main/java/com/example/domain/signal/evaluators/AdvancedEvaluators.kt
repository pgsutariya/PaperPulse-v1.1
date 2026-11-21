package com.example.domain.signal.evaluators

import com.example.domain.analysis.AdvancedIndicators
import com.example.domain.analysis.EmaCrossRetestEngine
import com.example.domain.analysis.LiquiditySide
import com.example.domain.analysis.LiquidityStatus
import com.example.domain.analysis.MdrStage
import com.example.domain.analysis.PivotPeriod
import com.example.domain.analysis.SetupOutcome
import com.example.domain.analysis.SetupState
import com.example.domain.analysis.SmcAnalyzer
import com.example.domain.analysis.StructureTrend
import com.example.domain.analysis.TradingStyle
import com.example.domain.analysis.TrapKind
import com.example.domain.signal.SignalConfidence
import com.example.domain.signal.SignalContext
import com.example.domain.signal.SignalDirection
import com.example.domain.signal.SignalStrategy
import com.example.domain.signal.StrategyEvaluator
import com.example.domain.signal.TechnicalIndicators
import com.example.domain.signal.Timeframe
import com.example.domain.signal.TradeSignal
import com.example.domain.signal.evaluators.SignalBuilders.fmt
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * EMA crossover → retest → break entry (9/21 for Scalping/Sniper/Intraday, 50/200 for
 * Swing/Short-term/Investing/Long-term). Fires only on the bar that triggers (or the bar after),
 * while the trade is still open.
 */
class EmaCrossRetestEvaluator(private val style: TradingStyle) : StrategyEvaluator {
    override val strategy: SignalStrategy = style.strategy

    override fun evaluate(context: SignalContext): TradeSignal? {
        for (tf in style.timeframes) {
            val candles = context.candlesByTimeframe[tf].orEmpty()
            if (candles.size < EmaCrossRetestEngine.minimumBars(style)) continue
            val setup = EmaCrossRetestEngine.latest(candles, style, tf, context.segment) ?: continue
            if (setup.state != SetupState.TRIGGERED || setup.outcome != SetupOutcome.OPEN) continue
            val trig = setup.triggerIndex ?: continue
            if (trig < candles.lastIndex - 1) continue
            val entry = setup.entry ?: continue
            val sl = setup.stopLoss ?: continue
            val t1 = setup.targets.firstOrNull { it.ratio == 1.0 }?.price ?: continue
            val t2 = setup.targets.firstOrNull { it.ratio == 2.0 }?.price ?: continue

            // Trend filter: 9/21 setups aligned with EMA 200 (when available) get more confidence.
            val closes = candles.map { it.close }
            val ema200 = TechnicalIndicators.ema(closes, 200)[candles.lastIndex]
            val isLong = setup.direction == SignalDirection.LONG
            val trendAligned = ema200 == null || (if (isLong) entry > ema200 else entry < ema200)
            val confidence = when {
                setup.volumeConfirmed && trendAligned && (!style.preferKillZone || setup.inKillZone) -> SignalConfidence.HIGH
                setup.volumeConfirmed || trendAligned -> SignalConfidence.MEDIUM
                else -> SignalConfidence.LOW
            }
            val reasons = buildList {
                add("${style.label} • ${style.pairLabel} crossover-retest-break (${tf.label})")
                addAll(setup.reasons)
                add("RR ladder: " + setup.targets.joinToString("  ") { "${it.label}→${fmt(it.price)}" })
                if (ema200 != null && style.fastPeriod == 9) add(if (trendAligned) "With EMA200 trend" else "Against EMA200 trend")
            }
            return SignalBuilders.build(
                context = context,
                strategy = strategy,
                timeframe = tf,
                direction = setup.direction,
                signalCandle = candles[trig],
                entry = entry,
                stopLoss = sl,
                target1 = t1,
                target2 = t2,
                confidence = confidence,
                reasons = reasons,
                expiryBars = 12
            )
        }
        return null
    }
}

/**
 * SMC / ICT model: HTF bias (structure + AMD on 1H/4H) aligned with an LTF MDR sequence
 * (liquidity sweep → displacement → rebalance into FVG) inside a kill zone, entered from a
 * discount (longs) / premium (shorts) location. Targets the pending opposite liquidity pool.
 */
class SmcIctEvaluator : StrategyEvaluator {
    override val strategy: SignalStrategy = SignalStrategy.SMC_ICT

    private val ltfs = listOf(Timeframe.M5, Timeframe.M15, Timeframe.M3)
    private val htfs = listOf(Timeframe.H4, Timeframe.H1)

    override fun evaluate(context: SignalContext): TradeSignal? {
        val htfTf = htfs.firstOrNull { (context.candlesByTimeframe[it]?.size ?: 0) >= 60 } ?: return null
        val htfCandles = context.candlesByTimeframe[htfTf].orEmpty()
        val htf = SmcAnalyzer.analyze(htfCandles, htfTf, context.segment)
        val htfBias = when {
            htf.trend != StructureTrend.RANGING -> htf.trend
            else -> htf.amd.bias
        }
        if (htfBias == StructureTrend.RANGING) return null

        for (tf in ltfs) {
            val candles = context.candlesByTimeframe[tf].orEmpty()
            if (candles.size < 60) continue
            val smc = SmcAnalyzer.analyze(candles, tf, context.segment)
            val mdr = smc.mdr
            if (mdr.stage != MdrStage.REBALANCED) continue
            val bullish = mdr.bullish ?: continue
            if (bullish != (htfBias == StructureTrend.BULLISH)) continue
            val last = candles.last()
            val range = smc.dealingRange ?: continue
            if (bullish && !range.isDiscount(last.close)) continue
            if (!bullish && !range.isPremium(last.close)) continue
            // Rebalance must be recent (within last 3 bars) and the candle must react in our direction.
            val reacted = if (bullish) last.close > last.open else last.close < last.open
            if (!reacted) continue

            val atr = TechnicalIndicators.atr(candles, 14).lastOrNull() ?: continue
            val sweepIdx = mdr.sweepIndex ?: continue
            val sweepCandle = candles[sweepIdx]
            val entry = last.close
            val sl = if (bullish) sweepCandle.low - 0.1 * atr else sweepCandle.high + 0.1 * atr
            val risk = abs(entry - sl)
            if (risk <= 0.0) continue
            val opposingLiquidity = smc.liquidity
                .filter { it.status == LiquidityStatus.PENDING }
                .filter { if (bullish) it.side == LiquiditySide.BUY_SIDE && it.price > entry else it.side == LiquiditySide.SELL_SIDE && it.price < entry }
                .minByOrNull { abs(it.price - entry) }
            val t1 = if (bullish) entry + risk else entry - risk
            val liqTarget = opposingLiquidity?.price
            val t2 = if (liqTarget != null && abs(liqTarget - entry) >= 1.5 * risk) liqTarget else if (bullish) entry + 2.0 * risk else entry - 2.0 * risk

            val institutionalPoi = smc.zones.any { it.institutional && it.isActive && it.isBullish == bullish && it.contains(mdr.rebalanceZone?.mid ?: entry) }
            val confidence = when {
                mdr.inKillZone && institutionalPoi -> SignalConfidence.HIGH
                mdr.inKillZone || institutionalPoi -> SignalConfidence.MEDIUM
                else -> SignalConfidence.LOW
            }
            val reasons = buildList {
                add("${htfTf.label} bias ${htfBias.name} (structure ${htf.trend.name}, AMD ${htf.amd.phase.label})")
                add("MDR on ${tf.label}: ${if (bullish) "SSL" else "BSL"} swept @ ${fmt(mdr.sweptLevel ?: 0.0)} → displacement → rebalance into FVG")
                add("Entry from ${range.zoneOf(entry)} of dealing range (EQ ${fmt(range.equilibrium)})")
                add(if (mdr.inKillZone) "Inside ${mdr.killZoneName} kill zone" else "Outside kill zones")
                if (institutionalPoi) add("Institutional order block / POI confluence")
                opposingLiquidity?.let { add("Draw on liquidity: ${it.name} @ ${fmt(it.price)}") }
            }
            return SignalBuilders.build(context, strategy, tf, if (bullish) SignalDirection.LONG else SignalDirection.SHORT,
                last, entry, sl, t1, t2, confidence, reasons, expiryBars = 12)
        }
        return null
    }
}

/** Trap trading: fade a failed breakout/breakdown (trapped breakout traders fuel the reversal). */
class TrapReversalEvaluator : StrategyEvaluator {
    override val strategy: SignalStrategy = SignalStrategy.TRAP
    private val tfs = listOf(Timeframe.M5, Timeframe.M15, Timeframe.H1)

    override fun evaluate(context: SignalContext): TradeSignal? {
        for (tf in tfs) {
            val candles = context.candlesByTimeframe[tf].orEmpty()
            if (candles.size < 40) continue
            val swings = AdvancedIndicators.swingPoints(candles, 3, 3)
            val trap = SmcAnalyzer.traps(candles, swings).lastOrNull() ?: continue
            if (trap.trapIndex != candles.lastIndex) continue
            val atr = TechnicalIndicators.atr(candles, 14).lastOrNull() ?: continue
            val bullTrap = trap.kind == TrapKind.BULL_TRAP
            val extreme = if (bullTrap) {
                candles.subList(trap.breakIndex, trap.trapIndex + 1).maxOf { it.high }
            } else {
                candles.subList(trap.breakIndex, trap.trapIndex + 1).minOf { it.low }
            }
            val entry = candles.last().close
            val sl = if (bullTrap) extreme + 0.1 * atr else extreme - 0.1 * atr
            val risk = abs(entry - sl)
            if (risk <= 0.0 || risk > 3.0 * atr) continue
            val dir = if (bullTrap) SignalDirection.SHORT else SignalDirection.LONG
            val t1 = if (bullTrap) entry - risk else entry + risk
            val t2 = if (bullTrap) entry - 2.0 * risk else entry + 2.0 * risk
            val volMa = AdvancedIndicators.volumeSma(candles, 20)
            val trappedVolume = (volMa[trap.breakIndex] ?: 0.0).let { it > 0.0 && candles[trap.breakIndex].volume > it }
            val deltaFlip = AdvancedIndicators.barDelta(candles.last()).let { if (bullTrap) it < 0 else it > 0 }
            val confidence = when {
                trappedVolume && deltaFlip -> SignalConfidence.HIGH
                trappedVolume || deltaFlip -> SignalConfidence.MEDIUM
                else -> SignalConfidence.LOW
            }
            val reasons = listOf(
                "${trap.kind.label} at ${fmt(trap.level)} on ${tf.label}",
                if (trappedVolume) "Breakout bar volume above 20 MA — traders trapped" else "Breakout on average volume",
                if (deltaFlip) "Order-flow delta flipped against the breakout" else "Delta not yet confirming",
                "SL beyond trap extreme ${fmt(extreme)}"
            )
            return SignalBuilders.build(context, strategy, tf, dir, candles.last(), entry, sl, t1, t2, confidence, reasons, expiryBars = 8)
        }
        return null
    }
}

/**
 * Pivot trading: standard floor pivots + CPR from the previous session. Long on a close back
 * above the CPR top / R1 with volume (targets next R levels); short on a close below CPR bottom / S1.
 */
class PivotBreakoutEvaluator : StrategyEvaluator {
    override val strategy: SignalStrategy = SignalStrategy.PIVOT
    private val tfs = listOf(Timeframe.M15, Timeframe.M5)

    override fun evaluate(context: SignalContext): TradeSignal? {
        for (tf in tfs) {
            val candles = context.candlesByTimeframe[tf].orEmpty()
            if (candles.size < 30) continue
            val pv = AdvancedIndicators.previousPeriodPivots(candles, context.segment, PivotPeriod.DAILY) ?: continue
            val last = candles.last()
            val prev = candles[candles.lastIndex - 1]
            val atr = TechnicalIndicators.atr(candles, 14).lastOrNull() ?: continue
            val volOk = AdvancedIndicators.isVolumeAboveMa(candles, candles.lastIndex)
            val levels = pv.namedLevels().sortedBy { it.second }
            val crossedUp = levels.lastOrNull { (_, lvl) -> prev.close <= lvl && last.close > lvl }
            val crossedDown = levels.firstOrNull { (_, lvl) -> prev.close >= lvl && last.close < lvl }
            val (dir, broken) = when {
                crossedUp != null && last.close > pv.pivot -> SignalDirection.LONG to crossedUp
                crossedDown != null && last.close < pv.pivot -> SignalDirection.SHORT to crossedDown
                else -> continue
            }
            val isLong = dir == SignalDirection.LONG
            val entry = last.close
            val sl = if (isLong) min(broken.second, last.low) - 0.25 * atr else max(broken.second, last.high) + 0.25 * atr
            val risk = abs(entry - sl)
            if (risk <= 0.0) continue
            val nextLevels = if (isLong) levels.filter { it.second > entry + 0.5 * risk } else levels.filter { it.second < entry - 0.5 * risk }.reversed()
            val t1 = nextLevels.getOrNull(0)?.second ?: if (isLong) entry + risk else entry - risk
            val t2Candidate = nextLevels.getOrNull(1)?.second ?: if (isLong) entry + 2 * risk else entry - 2 * risk
            val t2 = if (isLong) max(t1, t2Candidate) else min(t1, t2Candidate)
            val narrowCpr = pv.cprWidthPercent < 0.25
            val confidence = when {
                volOk && narrowCpr -> SignalConfidence.HIGH
                volOk || narrowCpr -> SignalConfidence.MEDIUM
                else -> SignalConfidence.LOW
            }
            val reasons = listOf(
                "Close ${if (isLong) "above" else "below"} ${broken.first} ${fmt(broken.second)} (prev-day pivots, ${tf.label})",
                "P ${fmt(pv.pivot)} • R1 ${fmt(pv.r1)} • S1 ${fmt(pv.s1)} • CPR ${fmt(pv.cprBottom)}–${fmt(pv.cprTop)} (${String.format(java.util.Locale.US, "%.2f", pv.cprWidthPercent)}%)",
                if (narrowCpr) "Narrow CPR → trending day expected" else "Wide CPR → range day risk",
                if (volOk) "Volume above 20 MA" else "Volume below 20 MA",
                "Trading ${if (entry > pv.pivot) "above" else "below"} pivot → ${if (entry > pv.pivot) "bullish" else "bearish"} sentiment"
            )
            return SignalBuilders.build(context, strategy, tf, dir, last, entry, sl, t1, t2, confidence, reasons, expiryBars = 10)
        }
        return null
    }
}

/**
 * Quant mean reversion: in a mean-reverting regime (Hurst < 0.5), enter when the 20-bar z-score
 * re-enters from beyond ±2σ. Target 1 = the mean, Target 2 = the opposite 1σ band.
 */
class QuantMeanReversionEvaluator : StrategyEvaluator {
    override val strategy: SignalStrategy = SignalStrategy.QUANT_MEAN_REVERSION
    private val tfs = listOf(Timeframe.M15, Timeframe.H1, Timeframe.D1)

    override fun evaluate(context: SignalContext): TradeSignal? {
        for (tf in tfs) {
            val candles = context.candlesByTimeframe[tf].orEmpty()
            if (candles.size < 80) continue
            val closes = candles.map { it.close }
            val z = AdvancedIndicators.zScore(closes, 20)
            val zNow = z.last() ?: continue
            val zPrev = z[z.lastIndex - 1] ?: continue
            val hurst = AdvancedIndicators.hurstExponent(closes.takeLast(128)) ?: continue
            if (hurst >= 0.5) continue
            val long = zPrev < -2.0 && zNow > -2.0
            val short = zPrev > 2.0 && zNow < 2.0
            if (!long && !short) continue
            val sma = TechnicalIndicators.sma(closes, 20).last() ?: continue
            val bb = TechnicalIndicators.bollingerBands(closes, 20, 1.0).last() ?: continue
            val atr = TechnicalIndicators.atr(candles, 14).lastOrNull() ?: continue
            val entry = closes.last()
            val recent = candles.takeLast(5)
            val sl = if (long) recent.minOf { it.low } - 0.25 * atr else recent.maxOf { it.high } + 0.25 * atr
            val t1 = sma
            val t2 = if (long) max(bb.upper, sma) else min(bb.lower, sma)
            val dir = if (long) SignalDirection.LONG else SignalDirection.SHORT
            val confidence = when {
                hurst < 0.4 -> SignalConfidence.HIGH
                hurst < 0.45 -> SignalConfidence.MEDIUM
                else -> SignalConfidence.LOW
            }
            val reasons = listOf(
                "Z-score(20) re-entered from ${fmt(zPrev)} to ${fmt(zNow)} on ${tf.label}",
                "Hurst exponent ${fmt(hurst)} → mean-reverting regime",
                "T1 = 20-SMA mean ${fmt(sma)}, T2 = opposite 1σ band",
                "Statistical edge: |z| > 2 occurs ~5% of the time under normality"
            )
            return SignalBuilders.build(context, strategy, tf, dir, candles.last(), entry, sl, t1, t2, confidence, reasons, expiryBars = 10)
        }
        return null
    }
}

/**
 * Quant breakout: volatility squeeze (Bollinger bandwidth in its lowest 20th percentile) released by a
 * close beyond the 20-bar Donchian channel on above-average volume, in a persistent regime (Hurst ≥ 0.5).
 */
class QuantBreakoutEvaluator : StrategyEvaluator {
    override val strategy: SignalStrategy = SignalStrategy.QUANT_BREAKOUT
    private val tfs = listOf(Timeframe.M15, Timeframe.H1, Timeframe.H4, Timeframe.D1)

    override fun evaluate(context: SignalContext): TradeSignal? {
        for (tf in tfs) {
            val candles = context.candlesByTimeframe[tf].orEmpty()
            if (candles.size < 80) continue
            val closes = candles.map { it.close }
            val bw = AdvancedIndicators.bollingerBandwidth(closes, 20, 2.0)
            val squeezeRecently = (1..5).any { back ->
                val idx = bw.size - 1 - back
                idx > 30 && (AdvancedIndicators.percentileRankOfLast(bw.subList(0, idx + 1), 120) ?: 100.0) <= 20.0
            }
            if (!squeezeRecently) continue
            val dc = AdvancedIndicators.donchian(candles, 20).last() ?: continue
            val last = candles.last()
            val volOk = AdvancedIndicators.isVolumeAboveMa(candles, candles.lastIndex)
            val up = last.close > dc.upper
            val down = last.close < dc.lower
            if ((!up && !down) || !volOk) continue
            val hurst = AdvancedIndicators.hurstExponent(closes.takeLast(128))
            if (hurst != null && hurst < 0.45) continue
            val atr = TechnicalIndicators.atr(candles, 14).lastOrNull() ?: continue
            val entry = last.close
            val sl = if (up) max(dc.middle, entry - 2.0 * atr) else min(dc.middle, entry + 2.0 * atr)
            val risk = abs(entry - sl)
            if (risk <= 0.0) continue
            val t1 = if (up) entry + 1.5 * risk else entry - 1.5 * risk
            val t2 = if (up) entry + 3.0 * risk else entry - 3.0 * risk
            val reg = AdvancedIndicators.linearRegression(closes, 50)
            val slopeOk = reg != null && (if (up) reg.slope > 0 else reg.slope < 0)
            val confidence = when {
                slopeOk && (hurst ?: 0.5) >= 0.55 -> SignalConfidence.HIGH
                slopeOk -> SignalConfidence.MEDIUM
                else -> SignalConfidence.LOW
            }
            val reasons = listOf(
                "Bollinger squeeze (bandwidth ≤ 20th percentile) released on ${tf.label}",
                "Close ${if (up) "above" else "below"} 20-bar Donchian ${fmt(if (up) dc.upper else dc.lower)} with volume > 20 MA",
                "Hurst ${hurst?.let { fmt(it) } ?: "n/a"} • 50-bar regression slope ${reg?.let { fmt(it.slopePercentPerBar(50)) } ?: "n/a"}%/bar (R² ${reg?.let { fmt(it.r2) } ?: "n/a"})",
                "SL at channel midline / 2×ATR, targets 1.5R and 3R"
            )
            return SignalBuilders.build(context, strategy, tf, if (up) SignalDirection.LONG else SignalDirection.SHORT,
                last, entry, sl, t1, t2, confidence, reasons, expiryBars = 10)
        }
        return null
    }
}
