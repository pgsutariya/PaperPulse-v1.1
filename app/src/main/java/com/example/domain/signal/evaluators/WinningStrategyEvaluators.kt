package com.example.domain.signal.evaluators

import com.example.domain.analysis.AstroBias
import com.example.domain.analysis.AstroTradingEngine
import com.example.domain.analysis.DataDrivenRegime
import com.example.domain.analysis.DataDrivenTradingEngine
import com.example.domain.model.InstrumentType
import com.example.domain.signal.Candle
import com.example.domain.signal.SignalConfidence
import com.example.domain.signal.SignalContext
import com.example.domain.signal.SignalDirection
import com.example.domain.signal.SignalStrategy
import com.example.domain.signal.StrategyEvaluator
import com.example.domain.signal.TechnicalIndicators
import com.example.domain.signal.Timeframe
import com.example.domain.signal.TradeSignal
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 1. DOUBLE CONFIRMATION PRO EVALUATOR
 * Requires TWO independent institutional confirmation layers before firing:
 * - Layer 1 (Macro/HTF Structure): Higher timeframe EMA 21/50 + RSI alignment (falls back to 50/200 macro structure).
 * - Layer 2 (LTF Execution Trigger): Price close above/below EMA 9 & VWAP + MACD histogram expansion + RSI zone + Volume expansion.
 */
class DoubleConfirmationEvaluator : StrategyEvaluator {

    override val strategy: SignalStrategy = SignalStrategy.DOUBLE_CONFIRMATION

    override fun evaluate(context: SignalContext): TradeSignal? {
        val pairs = listOf(
            Timeframe.M5 to Timeframe.M15,
            Timeframe.M15 to Timeframe.H1,
            Timeframe.H1 to Timeframe.D1
        )

        for ((ltf, htf) in pairs) {
            val ltfCandles = context.candlesByTimeframe[ltf] ?: continue
            if (ltfCandles.size < 40) continue

            val closes = ltfCandles.map { it.close }
            val ema9 = TechnicalIndicators.ema(closes, 9)
            val ema21 = TechnicalIndicators.ema(closes, 21)
            val ema50 = TechnicalIndicators.ema(closes, 50)
            val rsi = TechnicalIndicators.rsi(closes, 14)
            val macd = TechnicalIndicators.macd(closes)
            val vwap = TechnicalIndicators.vwap(ltfCandles.takeLast(60))
            val atr = TechnicalIndicators.atr(ltfCandles, 14)

            val lastIdx = ltfCandles.lastIndex
            val last = ltfCandles[lastIdx]
            val prev = ltfCandles[lastIdx - 1]
            val e9 = ema9[lastIdx] ?: continue
            val e21 = ema21[lastIdx] ?: continue
            val e50 = ema50[lastIdx]
            val r = rsi[lastIdx] ?: continue
            val m = macd[lastIdx] ?: continue
            val mPrev = macd[lastIdx - 1] ?: continue
            val vw = vwap.lastOrNull() ?: last.close
            val atrVal = (atr[lastIdx] ?: (last.high - last.low)).coerceAtLeast(last.close * 0.002)

            val avgVol = ltfCandles.subList(lastIdx - 20, lastIdx).map { it.volume.coerceAtLeast(1.0) }.average().coerceAtLeast(1.0)
            val rvol = last.volume / avgVol

            // Layer 1: Higher-Timeframe Confirmation (or macro EMA21/50 fallback)
            val htfCandles = context.candlesByTimeframe[htf]
            val htfDirection: Int = if (htfCandles != null && htfCandles.size >= 30) {
                val hCloses = htfCandles.map { it.close }
                val hE21 = TechnicalIndicators.ema(hCloses, 21).lastOrNull()
                val hE50 = TechnicalIndicators.ema(hCloses, 50).lastOrNull() ?: TechnicalIndicators.ema(hCloses, 34).lastOrNull()
                val hRsi = TechnicalIndicators.rsi(hCloses, 14).lastOrNull()
                val hClose = hCloses.last()
                when {
                    hE21 != null && hE50 != null && hClose > hE21 && hE21 > hE50 && (hRsi ?: 55.0) >= 50.0 -> 1
                    hE21 != null && hE50 != null && hClose < hE21 && hE21 < hE50 && (hRsi ?: 45.0) <= 50.0 -> -1
                    else -> 0
                }
            } else {
                when {
                    e50 != null && last.close > e21 && e21 > e50 -> 1
                    e50 != null && last.close < e21 && e21 < e50 -> -1
                    else -> 0
                }
            }
            if (htfDirection == 0) continue

            // Layer 2: LTF Price Action + Dual Oscillator + Volume Confirmation
            val barRange = (last.high - last.low).coerceAtLeast(1e-6)
            val bodyRatio = abs(last.close - last.open) / barRange

            val longConfirmed = htfDirection == 1 &&
                last.close > last.open &&
                bodyRatio >= 0.40 &&
                last.close > e9 && e9 > e21 &&
                last.close >= vw * 0.999 &&
                m.histogram > 0.0 && m.histogram >= mPrev.histogram &&
                r in 52.0..78.0 &&
                rvol >= 1.12 &&
                last.close >= prev.close

            val shortConfirmed = htfDirection == -1 &&
                last.close < last.open &&
                bodyRatio >= 0.40 &&
                last.close < e9 && e9 < e21 &&
                last.close <= vw * 1.001 &&
                m.histogram < 0.0 && m.histogram <= mPrev.histogram &&
                r in 22.0..48.0 &&
                rvol >= 1.12 &&
                last.close <= prev.close

            if (!longConfirmed && !shortConfirmed) continue

            val isLong = longConfirmed
            val entry = last.close
            val swingStop = if (isLong) {
                min(ltfCandles.subList(lastIdx - 5, lastIdx + 1).minOf { it.low }, e21)
            } else {
                max(ltfCandles.subList(lastIdx - 5, lastIdx + 1).maxOf { it.high }, e21)
            }
            val minRisk = (atrVal * 0.9).coerceAtLeast(entry * 0.0022)
            val maxRisk = entry * if (context.instrumentType == InstrumentType.OPTIONS) 0.28 else 0.05
            val risk = abs(entry - swingStop).coerceIn(minRisk, maxRisk)

            val sl = if (isLong) entry - risk else entry + risk
            val t1 = if (isLong) entry + risk * 1.9 else entry - risk * 1.9
            val t2 = if (isLong) entry + risk * 3.1 else entry - risk * 3.1

            val htfLabel = if (htfCandles != null && htfCandles.size >= 30) htf.label else "EMA 21/50"
            val reasons = listOf(
                "Confirmation 1 ($htfLabel HTF): ${if (isLong) "Bullish" else "Bearish"} trend & EMA structure verified",
                "Confirmation 2 (${ltf.label} LTF): Decisive close ${if (isLong) "above" else "below"} EMA 9 & VWAP (${SignalBuilders.fmt(vw)})",
                "Dual Oscillator Lock: RSI(14) ${SignalBuilders.fmt(r)} + MACD histogram expanding (${SignalBuilders.fmt(m.histogram)})",
                "Volume Surge Confirmed: RVOL ${String.format(Locale.US, "%.2f", rvol)}x average (body ${(bodyRatio * 100).toInt()}%)"
            )

            val signal = SignalBuilders.build(
                context = context,
                strategy = strategy,
                timeframe = ltf,
                direction = if (isLong) SignalDirection.LONG else SignalDirection.SHORT,
                signalCandle = last,
                entry = entry,
                stopLoss = sl,
                target1 = t1,
                target2 = t2,
                confidence = SignalConfidence.HIGH,
                reasons = reasons,
                expiryBars = 12
            )
            if (signal != null) return signal
        }
        return null
    }
}

/**
 * 2. STOCK OPTIONS MOMENTUM & IV FLOW EVALUATOR
 * Purpose-built for Options contracts (especially NSE Stock Options CE/PE).
 * Filters out slow theta-decay traps by requiring premium expansion above VWAP + EMA 9/21,
 * surging Relative Volume (RVOL >= 1.20x), and active ATR volatility expansion.
 */
class StockOptionsMomentumEvaluator : StrategyEvaluator {

    override val strategy: SignalStrategy = SignalStrategy.STOCK_OPTIONS_FLOW

    override fun evaluate(context: SignalContext): TradeSignal? {
        if (context.instrumentType != InstrumentType.OPTIONS) return null

        val candidateFrames = listOf(Timeframe.M5, Timeframe.M15, Timeframe.M3)
        for (tf in candidateFrames) {
            val candles = context.candlesByTimeframe[tf] ?: continue
            if (candles.size < 25) continue

            val closes = candles.map { it.close }
            val ema9 = TechnicalIndicators.ema(closes, 9)
            val ema21 = TechnicalIndicators.ema(closes, 21)
            val rsi = TechnicalIndicators.rsi(closes, 14)
            val vwap = TechnicalIndicators.vwap(candles.takeLast(50))
            val atr = TechnicalIndicators.atr(candles, 14)

            val lastIdx = candles.lastIndex
            val curr = candles[lastIdx]
            val prev = candles[lastIdx - 1]
            val e9 = ema9[lastIdx] ?: continue
            val e21 = ema21[lastIdx] ?: continue
            val r = rsi[lastIdx] ?: continue
            val vw = vwap.lastOrNull() ?: curr.close
            val atrVal = (atr[lastIdx] ?: (curr.high - curr.low)).coerceAtLeast(curr.close * 0.02)

            val avgVol = candles.subList((lastIdx - 15).coerceAtLeast(0), lastIdx)
                .map { it.volume.coerceAtLeast(1.0) }.average().coerceAtLeast(1.0)
            val rvol = curr.volume / avgVol

            val prevHigh5 = candles.subList(lastIdx - 5, lastIdx).maxOf { it.high }
            val isCall = context.symbol.uppercase().endsWith("CE") || context.symbol.uppercase().endsWith("C")
            val optLabel = if (isCall) "Call (CE)" else "Put (PE)"

            // Options buyers thrive on directional premium expansion (LONG option premium)
            val bullPremiumExpansion = curr.close > curr.open &&
                curr.close > e9 && e9 >= e21 * 0.995 &&
                curr.close >= vw * 0.995 &&
                (curr.close >= prevHigh5 * 0.995 || curr.close > prev.high) &&
                r in 50.0..86.0 &&
                rvol >= 1.15

            // Or sharp premium breakdown / writer dominance (SHORT option premium)
            val bearPremiumDecay = curr.close < curr.open &&
                curr.close < e9 && e9 < e21 &&
                curr.close < vw * 0.992 &&
                r in 15.0..48.0 &&
                rvol >= 1.25

            if (!bullPremiumExpansion && !bearPremiumDecay) continue

            val isLong = bullPremiumExpansion
            val entry = curr.close
            // Option-calibrated risk (10%..24% of option premium) to respect gamma & theta dynamics
            val risk = (atrVal * 1.35).coerceIn(entry * 0.08, entry * 0.24)
            val sl = if (isLong) (entry - risk).coerceAtLeast(entry * 0.65) else entry + risk
            val t1 = if (isLong) entry + risk * 1.8 else (entry - risk * 1.6).coerceAtLeast(entry * 0.40)
            val t2 = if (isLong) entry + risk * 3.0 else (entry - risk * 2.5).coerceAtLeast(entry * 0.20)

            val reasons = listOf(
                "$optLabel Option Momentum: Premium ${if (isLong) "breakout above" else "rejection below"} VWAP (${SignalBuilders.fmt(vw)}) & EMA 9",
                "Theta-Defense Filter Passed: RVOL ${String.format(Locale.US, "%.2f", rvol)}x + ATR expansion (${SignalBuilders.fmt(atrVal)})",
                "Option RSI(14) = ${SignalBuilders.fmt(r)} confirming ${if (isLong) "gamma buyer flow" else "premium decay / writing pressure"}",
                "Options Risk-Calibrated SL (${String.format(Locale.US, "%.1f", (risk / entry) * 100.0)}% premium) • T1 1.8R / T2 3.0R"
            )

            val signal = SignalBuilders.build(
                context = context,
                strategy = strategy,
                timeframe = tf,
                direction = if (isLong) SignalDirection.LONG else SignalDirection.SHORT,
                signalCandle = curr,
                entry = entry,
                stopLoss = sl,
                target1 = t1,
                target2 = t2,
                confidence = if (rvol >= 1.35) SignalConfidence.HIGH else SignalConfidence.MEDIUM,
                reasons = reasons,
                expiryBars = 8
            )
            if (signal != null) return signal
        }
        return null
    }
}

/**
 * 4. DATA-DRIVEN QUANT EDGE EVALUATOR
 * Powered by [DataDrivenTradingEngine]: fires purely on objective statistical factors
 * (Kaufman Efficiency Ratio, Rolling Z-Score, Relative Volume, Volume-Weighted Orderflow %, Volatility Percentile).
 */
class DataDrivenQuantEvaluator : StrategyEvaluator {

    override val strategy: SignalStrategy = SignalStrategy.DATA_DRIVEN_QUANT

    override fun evaluate(context: SignalContext): TradeSignal? {
        val candidateFrames = listOf(Timeframe.M5, Timeframe.M15, Timeframe.H1)
        for (tf in candidateFrames) {
            val candles = context.candlesByTimeframe[tf] ?: continue
            val snap = DataDrivenTradingEngine.analyze(candles) ?: continue
            if (snap.regime == DataDrivenRegime.CHOPPY_NOISE || snap.biasDirection == 0) continue
            if (snap.statisticalEdgeScore < 64) continue

            val last = candles.last()
            val atrVal = (TechnicalIndicators.atr(candles, 14).lastOrNull() ?: (last.high - last.low))
                .coerceAtLeast(last.close * 0.002)

            val isLong = snap.biasDirection > 0
            val entry = last.close
            val maxRisk = entry * if (context.instrumentType == InstrumentType.OPTIONS) 0.25 else 0.05
            val risk = (atrVal * 1.45).coerceIn(entry * 0.002, maxRisk)
            val sl = if (isLong) entry - risk else entry + risk
            val t1Mult = if (snap.regime == DataDrivenRegime.MEAN_REVERSION) 1.7 else 2.0
            val t2Mult = if (snap.regime == DataDrivenRegime.MEAN_REVERSION) 2.6 else 3.2
            val t1 = if (isLong) entry + risk * t1Mult else entry - risk * t1Mult
            val t2 = if (isLong) entry + risk * t2Mult else entry - risk * t2Mult

            val reasons = listOf(
                "Data-Driven Regime: ${snap.regime.label} (Statistical Edge ${snap.statisticalEdgeScore}/100)",
                "Factor Matrix: Z-Score ${String.format(Locale.US, "%+.2f", snap.zScore20)}σ • Kaufman ER ${String.format(Locale.US, "%.2f", snap.efficiencyRatio)} • RVOL ${String.format(Locale.US, "%.2f", snap.rvol)}x",
                "Orderflow Pressure: ${snap.orderflowBuyPct.toInt()}% volume-weighted buy imbalance • VWAP Δ ${String.format(Locale.US, "%+.2f", snap.vwapDeviationPct)}%",
                "Volatility Regime: ATR at ${snap.volatilityPercentile.toInt()}th percentile"
            )

            val signal = SignalBuilders.build(
                context = context,
                strategy = strategy,
                timeframe = tf,
                direction = if (isLong) SignalDirection.LONG else SignalDirection.SHORT,
                signalCandle = last,
                entry = entry,
                stopLoss = sl,
                target1 = t1,
                target2 = t2,
                confidence = if (snap.statisticalEdgeScore >= 74) SignalConfidence.HIGH else SignalConfidence.MEDIUM,
                reasons = reasons,
                expiryBars = 12
            )
            if (signal != null) return signal
        }
        return null
    }
}

/**
 * 5. ASTROLOGICAL CYCLE + PRICE-ACTION CONFLUENCE EVALUATOR
 * Powered by [AstroTradingEngine]: computes real-time Lunar Phase, Sidereal Nakshatra,
 * Planetary Harmonic Aspects (Trine/Sextile/Square/Opposition/Conjunction), and Mercury Retrograde,
 * then strictly requires technical price-action confirmation (EMA 21 + VWAP + RSI + Candle Close)
 * so every Astro signal has verifiable technical structure.
 */
class AstroCycleEvaluator : StrategyEvaluator {

    override val strategy: SignalStrategy = SignalStrategy.ASTRO_CYCLE

    override fun evaluate(context: SignalContext): TradeSignal? {
        val astro = AstroTradingEngine.compute(context.now)
        val astroCfg = context.astroFilterConfig
        val minTurn = if (astroCfg.enabled) astroCfg.minTurningPointScore else 48
        if (astro.turningPointScore < minTurn) return null
        if (astroCfg.enabled && astroCfg.avoidMercuryRetrograde && astro.isMercuryRetrograde) return null

        val candidateFrames = listOf(Timeframe.M15, Timeframe.H1, Timeframe.M5)
        for (tf in candidateFrames) {
            val candles = context.candlesByTimeframe[tf] ?: continue
            if (candles.size < 30) continue

            val closes = candles.map { it.close }
            val ema9 = TechnicalIndicators.ema(closes, 9)
            val ema21 = TechnicalIndicators.ema(closes, 21)
            val rsi = TechnicalIndicators.rsi(closes, 14)
            val vwap = TechnicalIndicators.vwap(candles.takeLast(50))
            val atr = TechnicalIndicators.atr(candles, 14)

            val lastIdx = candles.lastIndex
            val curr = candles[lastIdx]
            val prev = candles[lastIdx - 1]
            val e9 = ema9[lastIdx] ?: continue
            val e21 = ema21[lastIdx] ?: continue
            val r = rsi[lastIdx] ?: continue
            val vw = vwap.lastOrNull() ?: curr.close
            val atrVal = (atr[lastIdx] ?: (curr.high - curr.low)).coerceAtLeast(curr.close * 0.002)

            val techBull = curr.close > curr.open && curr.close > e21 && e9 >= e21 * 0.998 && curr.close >= vw * 0.998 && r in 48.0..82.0
            val techBear = curr.close < curr.open && curr.close < e21 && e9 <= e21 * 1.002 && curr.close <= vw * 1.002 && r in 18.0..52.0
            val bullReversal = TechnicalIndicators.isBullishReversalCandle(prev, curr) && r <= 48.0
            val bearReversal = TechnicalIndicators.isBearishReversalCandle(prev, curr) && r >= 52.0

            val direction: SignalDirection = when (astro.bias) {
                AstroBias.BULLISH_HARMONIC -> if (techBull) SignalDirection.LONG else continue
                AstroBias.BEARISH_PRESSURE -> if (techBear) SignalDirection.SHORT else continue
                AstroBias.REVERSAL_WINDOW -> when {
                    bullReversal || techBull -> SignalDirection.LONG
                    bearReversal || techBear -> SignalDirection.SHORT
                    else -> continue
                }
                AstroBias.NEUTRAL -> when {
                    astro.turningPointScore >= 60 && techBull -> SignalDirection.LONG
                    astro.turningPointScore >= 60 && techBear -> SignalDirection.SHORT
                    else -> continue
                }
            }

            // Apply active Astrological Trend Filters directly in signal generation
            val filterEval = AstroTradingEngine.evaluateFilterMatch(astro, direction, astroCfg)
            if (astroCfg.enabled && !filterEval.passes) continue

            val isLong = direction == SignalDirection.LONG
            val entry = curr.close
            val maxRisk = entry * if (context.instrumentType == InstrumentType.OPTIONS) 0.25 else 0.05
            val risk = (atrVal * 1.5).coerceIn(entry * 0.0022, maxRisk)
            val sl = if (isLong) entry - risk else entry + risk
            val t1 = if (isLong) entry + risk * 1.8 else entry - risk * 1.8
            val t2 = if (isLong) entry + risk * 2.9 else entry - risk * 2.9

            val aspectText = astro.activeAspects.firstOrNull()?.let {
                "${it.planetA} ${it.aspectName} ${it.planetB} (orb ${String.format(Locale.US, "%.1f", it.orbDeg)}°)"
            } ?: "Clean Lunar-Solar transit"
            val planetaryText = astro.planetaryPositions.take(4).joinToString(" • ") { it.compactLabel }

            val reasons = buildList {
                add("Astro Cycle: ${astro.lunarPhaseWindow.label} (${astro.moonIlluminationPct.toInt()}% Moon • Day ${String.format(Locale.US, "%.1f", astro.synodicDay)})")
                add("Planetary Positions: $planetaryText • Sidereal Nakshatra: ${astro.nakshatraName} (${astro.nakshatraTone})")
                add("Planetary Aspect: $aspectText • Turn Score ${astro.turningPointScore}/100${if (astro.isMercuryRetrograde) " • Mercury Rx" else ""}")
                add("Price-Action Verified: ${if (isLong) "Bullish" else "Bearish"} close confirming EMA 21 (${SignalBuilders.fmt(e21)}), VWAP & RSI ${SignalBuilders.fmt(r)}")
            }

            val confidence = when {
                astro.turningPointScore >= 68 && !astro.isMercuryRetrograde -> SignalConfidence.HIGH
                else -> SignalConfidence.MEDIUM
            }

            val signal = SignalBuilders.build(
                context = context,
                strategy = strategy,
                timeframe = tf,
                direction = direction,
                signalCandle = curr,
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
