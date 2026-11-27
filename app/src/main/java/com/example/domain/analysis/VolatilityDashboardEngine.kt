package com.example.domain.analysis

import com.example.domain.signal.Candle
import com.example.domain.signal.TechnicalIndicators
import com.example.domain.signal.Timeframe
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Active tunable parameters for the Pullback Sniper mean-reversion module.
 */
data class PullbackSniperConfig(
    val minConfidenceScore: Int = 62,        // 50..85
    val bandToleranceMultiplier: Double = 1.0 // 0.60x..1.80x VWAP/EMA tolerance
)

/**
 * Shared live calibration state driving both live Signal Generation and the Strategy Backtester.
 */
data class StrategyCalibrationConfig(
    val renkoConfig: AiRenkoConfig = AiRenkoConfig(),
    val sniperConfig: PullbackSniperConfig = PullbackSniperConfig()
)

enum class VolatilityRegimeBand(val label: String, val shortLabel: String) {
    SQUEEZE("Volatility Squeeze", "SQUEEZE"),
    LOW("Calm / Low Volatility", "LOW VOL"),
    NORMAL("Balanced Regime", "NORMAL"),
    ELEVATED("Trending Expansion", "ELEVATED"),
    EXTREME("Extreme Volatility", "EXTREME")
}

data class VolatilityDataPoint(
    val index: Int,
    val timestampMillis: Long,
    val close: Double,
    val atr7: Double,
    val atr14: Double,
    val atr21: Double,
    val atr14Percent: Double,
    val atrSpread: Double,          // Fast ATR(7) - Slow ATR(21) in price units
    val atrSpreadPercent: Double,   // (Fast ATR - Slow ATR) / Close * 100
    val bbKcSpreadPercent: Double,  // BB Width % - KC Width % (< 0 indicates squeeze)
    val realizedVolPercent: Double, // Annualized 20-bar log-return realized volatility
    val isSqueeze: Boolean
)

data class VolatilityDashboardSnapshot(
    val symbol: String = "",
    val timeframe: Timeframe = Timeframe.M5,
    val points: List<VolatilityDataPoint> = emptyList(),
    val latestClose: Double = 0.0,
    val latestAtr7: Double = 0.0,
    val latestAtr14: Double = 0.0,
    val latestAtr21: Double = 0.0,
    val latestAtr14Percent: Double = 0.0,
    val latestAtrSpread: Double = 0.0,
    val latestSpreadRatio: Double = 1.0,
    val latestBbKcSpreadPercent: Double = 0.0,
    val latestRealizedVolPercent: Double = 0.0,
    val atrPercentile: Int = 50,
    val squeezeBarCount: Int = 0,
    val regime: VolatilityRegimeBand = VolatilityRegimeBand.NORMAL,
    val calibrationNote: String = "Select an instrument to analyze real-time ATR & volatility spread.",
    val recommendedCalibration: StrategyCalibrationConfig = StrategyCalibrationConfig()
)

/**
 * Computes real-time historical ATR curves (Fast 7 / Main 14 / Slow 21), ATR volatility spread,
 * Bollinger-vs-Keltner squeeze spread, annualized realized volatility, ATR percentile rank,
 * and dynamic indicator sensitivity recommendations for AI Renko Supertrend & Pullback Sniper.
 */
object VolatilityDashboardEngine {

    fun compute(
        symbol: String,
        timeframe: Timeframe,
        candles: List<Candle>,
        maxPoints: Int = 90
    ): VolatilityDashboardSnapshot {
        if (candles.size < 24) {
            return VolatilityDashboardSnapshot(
                symbol = symbol,
                timeframe = timeframe,
                calibrationNote = "Gathering historical candles for $symbol (${timeframe.label})..."
            )
        }

        val n = candles.size
        val closes = candles.map { it.close }
        val atr7 = TechnicalIndicators.atr(candles, 7)
        val atr14 = TechnicalIndicators.atr(candles, 14)
        val atr21 = TechnicalIndicators.atr(candles, 21)
        val sma20 = TechnicalIndicators.sma(closes, 20)
        val ema20 = TechnicalIndicators.ema(closes, 20)

        // Annualization scale factor based on timeframe
        val barsPerYear = when (timeframe) {
            Timeframe.M1 -> 252.0 * 375.0
            Timeframe.M3 -> 252.0 * 125.0
            Timeframe.M5 -> 252.0 * 75.0
            Timeframe.M10 -> 252.0 * 37.5
            Timeframe.M15 -> 252.0 * 25.0
            Timeframe.M30 -> 252.0 * 12.5
            Timeframe.M45 -> 252.0 * 8.3
            Timeframe.H1 -> 252.0 * 6.25
            Timeframe.H4 -> 252.0 * 2.0
            Timeframe.D1 -> 252.0
            Timeframe.W1 -> 52.0
            Timeframe.MN1 -> 12.0
        }

        val allPoints = mutableListOf<VolatilityDataPoint>()
        for (i in 20 until n) {
            val c = candles[i]
            val close = c.close.coerceAtLeast(1e-6)
            val fallbackTr = (c.high - c.low).coerceAtLeast(close * 0.001)
            val a7 = (atr7[i] ?: fallbackTr).coerceAtLeast(1e-6)
            val a14 = (atr14[i] ?: fallbackTr).coerceAtLeast(1e-6)
            val a21 = (atr21[i] ?: a14).coerceAtLeast(1e-6)

            val atr14Pct = (a14 / close) * 100.0
            val spread = a7 - a21
            val spreadPct = (spread / close) * 100.0

            // Bollinger Band width (20, 2.0) vs Keltner Channel width (20, 1.5x ATR14)
            val mean20 = sma20[i] ?: close
            var variance = 0.0
            for (k in (i - 19)..i) {
                val diff = closes[k] - mean20
                variance += diff * diff
            }
            val std20 = sqrt(variance / 20.0)
            val bbWidthPct = ((4.0 * std20) / close) * 100.0
            val kcCenter = ema20[i] ?: close
            val kcWidthPct = ((3.0 * a14) / kcCenter.coerceAtLeast(1e-6)) * 100.0
            val bbKcSpread = bbWidthPct - kcWidthPct
            val isSqueeze = bbKcSpread < 0.0

            // 20-bar realized log-return volatility (annualized %)
            var rSum = 0.0
            var rSqSum = 0.0
            var rCount = 0
            for (k in max(1, i - 19)..i) {
                val prevClose = closes[k - 1]
                val curClose = closes[k]
                if (prevClose > 0.0 && curClose > 0.0) {
                    val lr = ln(curClose / prevClose)
                    rSum += lr
                    rSqSum += lr * lr
                    rCount++
                }
            }
            val rVolPct = if (rCount >= 4) {
                val meanR = rSum / rCount
                val varR = ((rSqSum / rCount) - meanR * meanR).coerceAtLeast(0.0)
                (sqrt(varR * barsPerYear) * 100.0).coerceIn(1.0, 250.0)
            } else {
                (atr14Pct * sqrt(barsPerYear) * 0.65).coerceIn(1.0, 250.0)
            }

            allPoints += VolatilityDataPoint(
                index = i,
                timestampMillis = c.timestamp.toEpochMilli(),
                close = close,
                atr7 = a7,
                atr14 = a14,
                atr21 = a21,
                atr14Percent = atr14Pct,
                atrSpread = spread,
                atrSpreadPercent = spreadPct,
                bbKcSpreadPercent = bbKcSpread,
                realizedVolPercent = rVolPct,
                isSqueeze = isSqueeze
            )
        }

        if (allPoints.isEmpty()) {
            return VolatilityDashboardSnapshot(symbol = symbol, timeframe = timeframe)
        }

        val displayPoints = allPoints.takeLast(maxPoints)
        val latest = displayPoints.last()

        // ATR percentile rank over lookback window
        val belowOrEq = displayPoints.count { it.atr14Percent <= latest.atr14Percent }
        val percentile = ((belowOrEq.toDouble() / displayPoints.size) * 100.0).roundToInt().coerceIn(0, 100)

        // Trailing consecutive squeeze bars
        var squeezeCount = 0
        for (idx in displayPoints.lastIndex downTo 0) {
            if (displayPoints[idx].isSqueeze) squeezeCount++ else break
        }

        val spreadRatio = (latest.atr7 / latest.atr21.coerceAtLeast(1e-6)).coerceIn(0.4, 2.5)

        val regime = when {
            percentile >= 82 || spreadRatio >= 1.24 -> VolatilityRegimeBand.EXTREME
            percentile >= 65 || spreadRatio >= 1.12 -> VolatilityRegimeBand.ELEVATED
            (squeezeCount >= 2 || latest.bbKcSpreadPercent < -0.05) && spreadRatio <= 1.05 -> VolatilityRegimeBand.SQUEEZE
            percentile <= 28 || spreadRatio <= 0.88 -> VolatilityRegimeBand.LOW
            else -> VolatilityRegimeBand.NORMAL
        }

        val (recommended, note) = recommendCalibration(regime, percentile, spreadRatio, squeezeCount)

        return VolatilityDashboardSnapshot(
            symbol = symbol,
            timeframe = timeframe,
            points = displayPoints,
            latestClose = latest.close,
            latestAtr7 = latest.atr7,
            latestAtr14 = latest.atr14,
            latestAtr21 = latest.atr21,
            latestAtr14Percent = latest.atr14Percent,
            latestAtrSpread = latest.atrSpread,
            latestSpreadRatio = spreadRatio,
            latestBbKcSpreadPercent = latest.bbKcSpreadPercent,
            latestRealizedVolPercent = latest.realizedVolPercent,
            atrPercentile = percentile,
            squeezeBarCount = squeezeCount,
            regime = regime,
            calibrationNote = note,
            recommendedCalibration = recommended
        )
    }

    private fun recommendCalibration(
        regime: VolatilityRegimeBand,
        percentile: Int,
        spreadRatio: Double,
        squeezeBars: Int
    ): Pair<StrategyCalibrationConfig, String> {
        return when (regime) {
            VolatilityRegimeBand.SQUEEZE -> {
                val cfg = StrategyCalibrationConfig(
                    renkoConfig = AiRenkoConfig(
                        brickSizeMultiplier = 0.65,
                        atrSensitivity = 2.1,
                        atrPeriod = 14,
                        adaptiveMode = true
                    ),
                    sniperConfig = PullbackSniperConfig(
                        minConfidenceScore = 66,
                        bandToleranceMultiplier = 0.85
                    )
                )
                cfg to "Tight ${squeezeBars}-bar squeeze (BB inside KC). Tighten Renko bricks (0.65x) & ATR sensitivity (2.1x) to catch the breakout early while keeping Sniper selectivity high."
            }
            VolatilityRegimeBand.LOW -> {
                val cfg = StrategyCalibrationConfig(
                    renkoConfig = AiRenkoConfig(
                        brickSizeMultiplier = 0.70,
                        atrSensitivity = 2.2,
                        atrPeriod = 14,
                        adaptiveMode = true
                    ),
                    sniperConfig = PullbackSniperConfig(
                        minConfidenceScore = 64,
                        bandToleranceMultiplier = 0.90
                    )
                )
                cfg to "Low ATR (${percentile}th pct, ratio ${fmt2(spreadRatio)}x). Use compact Renko bricks (0.70x) and tighter VWAP/EMA tolerance (0.90x) so valid swings are not filtered out."
            }
            VolatilityRegimeBand.NORMAL -> {
                val cfg = StrategyCalibrationConfig(
                    renkoConfig = AiRenkoConfig(
                        brickSizeMultiplier = 0.85,
                        atrSensitivity = 2.6,
                        atrPeriod = 14,
                        adaptiveMode = true
                    ),
                    sniperConfig = PullbackSniperConfig(
                        minConfidenceScore = 62,
                        bandToleranceMultiplier = 1.00
                    )
                )
                cfg to "Balanced volatility (${percentile}th pct, ratio ${fmt2(spreadRatio)}x). Standard Brahmastra Renko (0.85x / 2.6x) and 62+ Pullback Sniper calibration provide optimal edge."
            }
            VolatilityRegimeBand.ELEVATED -> {
                val cfg = StrategyCalibrationConfig(
                    renkoConfig = AiRenkoConfig(
                        brickSizeMultiplier = 1.15,
                        atrSensitivity = 3.1,
                        atrPeriod = 14,
                        adaptiveMode = true
                    ),
                    sniperConfig = PullbackSniperConfig(
                        minConfidenceScore = 64,
                        bandToleranceMultiplier = 1.25
                    )
                )
                cfg to "Expanding volatility (${percentile}th pct, Fast/Slow ${fmt2(spreadRatio)}x). Widen Renko bricks (1.15x) & Supertrend factor (3.1x) to avoid shakeouts on trend pullbacks."
            }
            VolatilityRegimeBand.EXTREME -> {
                val cfg = StrategyCalibrationConfig(
                    renkoConfig = AiRenkoConfig(
                        brickSizeMultiplier = 1.45,
                        atrSensitivity = 3.6,
                        atrPeriod = 14,
                        adaptiveMode = true
                    ),
                    sniperConfig = PullbackSniperConfig(
                        minConfidenceScore = 68,
                        bandToleranceMultiplier = 1.45
                    )
                )
                cfg to "Extreme ATR spike (${percentile}th pct, Fast/Slow ${fmt2(spreadRatio)}x). Increase Renko noise filter (1.45x / 3.6x) and raise Sniper gate to 68+ to avoid whipsaw stops."
            }
        }
    }

    private fun fmt2(v: Double): String = String.format(java.util.Locale.US, "%.2f", v)
}
