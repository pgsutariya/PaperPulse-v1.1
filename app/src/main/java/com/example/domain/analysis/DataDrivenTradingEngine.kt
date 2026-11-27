package com.example.domain.analysis

import com.example.domain.signal.Candle
import com.example.domain.signal.TechnicalIndicators
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class DataDrivenRegime(val label: String) {
    TREND_EXPANSION("Trend Expansion"),
    MEAN_REVERSION("Statistical Mean Reversion"),
    VOLATILITY_SQUEEZE("Volatility Compression Breakout"),
    CHOPPY_NOISE("Low-Edge Chop")
}

data class DataDrivenSnapshot(
    val zScore20: Double,
    val rvol: Double,
    val efficiencyRatio: Double,
    val orderflowBuyPct: Double,
    val volatilityPercentile: Double,
    val vwapDeviationPct: Double,
    val statisticalEdgeScore: Int,
    val regime: DataDrivenRegime,
    val biasDirection: Int, // +1 bullish, -1 bearish, 0 neutral
    val summaryLine: String
)

/**
 * Multi-factor quantitative data-driven trading engine.
 * Combines rolling Z-score statistics, Kaufman Efficiency Ratio (trend persistence),
 * Relative Volume (RVOL), volume-weighted orderflow buying pressure, VWAP deviation,
 * and ATR volatility expansion/compression into an objective Statistical Edge Score (0..100).
 */
object DataDrivenTradingEngine {

    fun analyze(candles: List<Candle>): DataDrivenSnapshot? {
        if (candles.size < 30) return null

        val lastIdx = candles.lastIndex
        val last = candles[lastIdx]
        val closes = candles.map { it.close }

        // 1. 20-bar Rolling Z-Score
        val window20 = closes.subList(lastIdx - 19, lastIdx + 1)
        val mean20 = window20.average()
        val variance20 = window20.sumOf { (it - mean20) * (it - mean20) } / 20.0
        val std20 = sqrt(variance20).coerceAtLeast(1e-6)
        val zScore20 = (last.close - mean20) / std20

        // 2. Relative Volume (RVOL vs trailing 20 closed bars)
        val prevVols = candles.subList((lastIdx - 20).coerceAtLeast(0), lastIdx).map { it.volume.coerceAtLeast(1.0) }
        val avgVol20 = prevVols.average().coerceAtLeast(1.0)
        val rvol = (last.volume.coerceAtLeast(1.0) / avgVol20).coerceIn(0.1, 10.0)

        // 3. Kaufman Efficiency Ratio (14-bar directional displacement / path length)
        val erPeriod = 14
        val netMove = abs(candles[lastIdx].close - candles[lastIdx - erPeriod].close)
        var pathSum = 0.0
        for (i in (lastIdx - erPeriod + 1)..lastIdx) {
            pathSum += abs(candles[i].close - candles[i - 1].close)
        }
        val efficiencyRatio = if (pathSum > 1e-9) (netMove / pathSum).coerceIn(0.0, 1.0) else 0.0

        // 4. Volume-weighted Orderflow Buying Pressure over last 5 bars (0..100%)
        var weightedBuy = 0.0
        var totalVol5 = 0.0
        for (i in (lastIdx - 4)..lastIdx) {
            val c = candles[i]
            val range = (c.high - c.low).coerceAtLeast(1e-6)
            val closePos = ((c.close - c.low) / range).coerceIn(0.0, 1.0)
            val vol = c.volume.coerceAtLeast(1.0)
            weightedBuy += closePos * vol
            totalVol5 += vol
        }
        val orderflowBuyPct = ((weightedBuy / totalVol5.coerceAtLeast(1.0)) * 100.0).coerceIn(0.0, 100.0)

        // 5. VWAP deviation %
        val vwapSeries = TechnicalIndicators.vwap(candles.takeLast(60))
        val vwapVal = vwapSeries.lastOrNull() ?: last.close
        val vwapDeviationPct = if (vwapVal > 1e-6) ((last.close - vwapVal) / vwapVal) * 100.0 else 0.0

        // 6. Volatility Percentile (current ATR(10) ranked against trailing 30 ATR values)
        val atr10 = TechnicalIndicators.atr(candles, 10)
        val recentAtrs = atr10.takeLast(30).filterNotNull()
        val currentAtr = recentAtrs.lastOrNull() ?: (last.high - last.low)
        val volatilityPercentile = if (recentAtrs.size >= 5) {
            val below = recentAtrs.count { it <= currentAtr }
            (below.toDouble() / recentAtrs.size.toDouble()) * 100.0
        } else {
            50.0
        }

        // 7. Directional Momentum Slope (5-bar return)
        val slope5 = last.close - candles[lastIdx - 5].close

        // Determine Data-Driven Regime & Bias
        val isHighPersistence = efficiencyRatio >= 0.36
        val isMeanRevExtreme = abs(zScore20) >= 1.85 && efficiencyRatio < 0.34
        val isSqueezeExpansion = volatilityPercentile >= 65.0 && rvol >= 1.30 && abs(zScore20) in 0.7..2.4

        val regime: DataDrivenRegime
        val biasDirection: Int
        var edgeScore = 40.0

        when {
            isHighPersistence && slope5 > 0 && orderflowBuyPct >= 56.0 && vwapDeviationPct >= -0.05 -> {
                regime = if (isSqueezeExpansion) DataDrivenRegime.VOLATILITY_SQUEEZE else DataDrivenRegime.TREND_EXPANSION
                biasDirection = 1
                edgeScore += (efficiencyRatio * 34.0).coerceAtMost(24.0)
                edgeScore += ((orderflowBuyPct - 50.0) * 0.55).coerceIn(0.0, 18.0)
                edgeScore += ((rvol - 1.0) * 14.0).coerceIn(0.0, 14.0)
                if (zScore20 in 0.4..2.2) edgeScore += 7.0
            }
            isHighPersistence && slope5 < 0 && orderflowBuyPct <= 44.0 && vwapDeviationPct <= 0.05 -> {
                regime = if (isSqueezeExpansion) DataDrivenRegime.VOLATILITY_SQUEEZE else DataDrivenRegime.TREND_EXPANSION
                biasDirection = -1
                edgeScore += (efficiencyRatio * 34.0).coerceAtMost(24.0)
                edgeScore += ((50.0 - orderflowBuyPct) * 0.55).coerceIn(0.0, 18.0)
                edgeScore += ((rvol - 1.0) * 14.0).coerceIn(0.0, 14.0)
                if (zScore20 in -2.2..-0.4) edgeScore += 7.0
            }
            isMeanRevExtreme && zScore20 <= -1.85 && last.close >= last.open -> {
                regime = DataDrivenRegime.MEAN_REVERSION
                biasDirection = 1
                edgeScore += (abs(zScore20) * 11.0).coerceAtMost(26.0)
                edgeScore += ((rvol - 0.9) * 12.0).coerceIn(0.0, 14.0)
                if (orderflowBuyPct >= 45.0) edgeScore += 10.0
            }
            isMeanRevExtreme && zScore20 >= 1.85 && last.close <= last.open -> {
                regime = DataDrivenRegime.MEAN_REVERSION
                biasDirection = -1
                edgeScore += (abs(zScore20) * 11.0).coerceAtMost(26.0)
                edgeScore += ((rvol - 0.9) * 12.0).coerceIn(0.0, 14.0)
                if (orderflowBuyPct <= 55.0) edgeScore += 10.0
            }
            else -> {
                regime = DataDrivenRegime.CHOPPY_NOISE
                biasDirection = 0
                edgeScore = 35.0
            }
        }

        val clampedEdge = edgeScore.roundToInt().coerceIn(0, 96)
        val summary = "${regime.label} • Edge $clampedEdge/100 • Z=${String.format(java.util.Locale.US, "%.2f", zScore20)} • RVOL=${String.format(java.util.Locale.US, "%.2f", rvol)}x • ER=${String.format(java.util.Locale.US, "%.2f", efficiencyRatio)}"

        return DataDrivenSnapshot(
            zScore20 = zScore20,
            rvol = rvol,
            efficiencyRatio = efficiencyRatio,
            orderflowBuyPct = orderflowBuyPct,
            volatilityPercentile = volatilityPercentile,
            vwapDeviationPct = vwapDeviationPct,
            statisticalEdgeScore = clampedEdge,
            regime = regime,
            biasDirection = biasDirection,
            summaryLine = summary
        )
    }
}
