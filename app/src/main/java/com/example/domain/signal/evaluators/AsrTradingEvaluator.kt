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

/**
 * ASR Trading (Automated Support & Resistance) Strategy:
 * 1. Automatically identifies high-touch horizontal Support & Resistance clusters from swing pivots (last 55 bars).
 * 2. Generates high-probability signals on:
 *    - ASR Support Bounce (wick test + bullish close / reversal candle)
 *    - ASR Resistance Rejection (wick test + bearish close / reversal candle)
 *    - ASR Breakout & Hold (clean volume-backed close beyond Resistance or Support)
 * 3. Tight ATR-clamped SL just beyond the ASR zone, with T1 = 1.6R and T2 = 2.6R.
 */
class AsrTradingEvaluator : StrategyEvaluator {
    override val strategy: SignalStrategy = SignalStrategy.ASR_TRADING

    private data class Zone(val price: Double, val touches: Int)

    override fun evaluate(context: SignalContext): TradeSignal? {
        val tf = listOf(Timeframe.M15, Timeframe.H1, Timeframe.M5)
            .firstOrNull { (context.candlesByTimeframe[it]?.size ?: 0) >= 48 }
            ?: return null
        val candles = context.candlesByTimeframe[tf] ?: return null
        if (candles.size < 48) return null

        val idx = candles.lastIndex
        val last = candles[idx]
        val prev = candles[idx - 1]
        val atr = TechnicalIndicators.atr(candles, 14)[idx]?.takeIf { it > 0.0 }
            ?: ((last.high - last.low).coerceAtLeast(last.close * 0.004))

        val lookback = candles.subList(maxOf(0, idx - 50), idx)
        val tolerance = maxOf(atr * 0.55, last.close * 0.0025)
        val supportZones = clusterPivots(findSwingLows(lookback), tolerance)
        val resistanceZones = clusterPivots(findSwingHighs(lookback), tolerance)

        val avgVol = candles.subList(maxOf(0, idx - 20), idx).map { it.volume }.average().coerceAtLeast(1.0)
        val volRatio = last.volume / avgVol

        // 1. Support Bounce (Long)
        val testedSupport = supportZones
            .filter { abs(last.low - it.price) <= tolerance * 1.15 && last.close > it.price }
            .maxByOrNull { it.touches }

        val supportBounce = testedSupport != null &&
            (last.close > last.open || TechnicalIndicators.isBullishReversalCandle(prev, last))

        // 2. Resistance Rejection (Short)
        val testedResistance = resistanceZones
            .filter { abs(last.high - it.price) <= tolerance * 1.15 && last.close < it.price }
            .maxByOrNull { it.touches }

        val resistanceReject = testedResistance != null &&
            (last.close < last.open || TechnicalIndicators.isBearishReversalCandle(prev, last))

        // 3. ASR Resistance Breakout (Long) / Support Breakdown (Short)
        val brokenResistance = resistanceZones
            .filter { prev.close <= it.price && last.close > it.price + tolerance * 0.2 }
            .maxByOrNull { it.touches }
        val resBreakout = brokenResistance != null && volRatio >= 1.15 && last.close > last.open

        val brokenSupport = supportZones
            .filter { prev.close >= it.price && last.close < it.price - tolerance * 0.2 }
            .maxByOrNull { it.touches }
        val supBreakdown = brokenSupport != null && volRatio >= 1.15 && last.close < last.open

        val (isLong, setupLabel, anchorZone) = when {
            supportBounce -> Triple(true, "ASR Support Bounce", testedSupport!!)
            resistanceReject -> Triple(false, "ASR Resistance Rejection", testedResistance!!)
            resBreakout -> Triple(true, "ASR Resistance Breakout", brokenResistance!!)
            supBreakdown -> Triple(false, "ASR Support Breakdown", brokenSupport!!)
            else -> return null
        }

        val direction = if (isLong) SignalDirection.LONG else SignalDirection.SHORT
        val entry = context.currentLtp.toDouble().takeIf { it > 0.0 } ?: last.close
        val minRisk = entry * 0.0035
        val maxRisk = entry * 0.045

        val rawSl = if (isLong) {
            minOf(anchorZone.price, last.low) - atr * 0.35
        } else {
            maxOf(anchorZone.price, last.high) + atr * 0.35
        }
        val risk = abs(entry - rawSl).coerceIn(minRisk, maxRisk)
        val slFinal = if (isLong) entry - risk else entry + risk
        val t1Raw = if (isLong) entry + risk * 1.6 else entry - risk * 1.6
        val t2Raw = if (isLong) entry + risk * 2.6 else entry - risk * 2.6
        if (slFinal <= 0.0 || t1Raw <= 0.0 || t2Raw <= 0.0) return null

        val confidence = when {
            anchorZone.touches >= 3 && volRatio >= 1.15 -> SignalConfidence.HIGH
            anchorZone.touches >= 2 || volRatio >= 1.2 -> SignalConfidence.MEDIUM
            else -> SignalConfidence.LOW
        }

        val scale = if (entry < 10.0) 4 else 2
        fun bd(v: Double): BigDecimal = BigDecimal.valueOf(v).setScale(scale, RoundingMode.HALF_UP)

        val reasons = listOf(
            "$setupLabel at auto-detected ASR zone ${String.format("%.2f", anchorZone.price)} (${anchorZone.touches} swing touches)",
            "Timeframe ${tf.label} • Volume ${String.format("%.2f", volRatio)}× 20-bar avg",
            "SL protected outside ASR liquidity zone • T1 = 1.6R, T2 = 2.6R"
        )

        return TradeSignal(
            id = "asr_${context.symbol}_${direction.name}_${tf.name}_${context.now.epochSecond / 900}",
            symbol = context.symbol,
            segment = context.segment,
            strategy = SignalStrategy.ASR_TRADING,
            direction = direction,
            timeframe = tf,
            entryPrice = bd(entry),
            stopLoss = bd(slFinal),
            target1 = bd(t1Raw),
            target2 = bd(t2Raw),
            riskRewardRatio = 1.6,
            confidence = confidence,
            reasons = reasons,
            generatedAt = context.now,
            expiresAt = context.now.plus(4, ChronoUnit.HOURS),
            instrumentType = context.instrumentType
        )
    }

    private fun findSwingHighs(candles: List<Candle>): List<Double> {
        val out = mutableListOf<Double>()
        for (i in 2 until candles.size - 2) {
            val h = candles[i].high
            if (h >= candles[i - 1].high && h >= candles[i - 2].high && h >= candles[i + 1].high && h >= candles[i + 2].high) {
                out.add(h)
            }
        }
        return out
    }

    private fun findSwingLows(candles: List<Candle>): List<Double> {
        val out = mutableListOf<Double>()
        for (i in 2 until candles.size - 2) {
            val l = candles[i].low
            if (l <= candles[i - 1].low && l <= candles[i - 2].low && l <= candles[i + 1].low && l <= candles[i + 2].low) {
                out.add(l)
            }
        }
        return out
    }

    private fun clusterPivots(levels: List<Double>, tolerance: Double): List<Zone> {
        if (levels.isEmpty()) return emptyList()
        val sorted = levels.sorted()
        val clusters = mutableListOf<MutableList<Double>>()
        for (lv in sorted) {
            val lastCluster = clusters.lastOrNull()
            if (lastCluster != null && abs(lv - lastCluster.average()) <= tolerance) {
                lastCluster.add(lv)
            } else {
                clusters.add(mutableListOf(lv))
            }
        }
        return clusters
            .filter { it.size >= 2 }
            .map { Zone(price = it.average(), touches = it.size) }
            .ifEmpty { clusters.map { Zone(price = it.average(), touches = it.size) } }
    }
}
