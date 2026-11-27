package com.example.domain.analysis

import com.example.domain.signal.Candle
import com.example.domain.signal.SignalDirection
import com.example.domain.signal.TechnicalIndicators
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class ChartPatternType(
    val displayName: String,
    val shortBadge: String,
    val defaultDirection: SignalDirection,
    val categoryLabel: String
) {
    BULL_FLAG("Bull Flag", "Bull Flag", SignalDirection.LONG, "Continuation"),
    BEAR_FLAG("Bear Flag", "Bear Flag", SignalDirection.SHORT, "Continuation"),
    FALLING_WEDGE("Falling Wedge", "Fall Wedge", SignalDirection.LONG, "Bullish Reversal"),
    RISING_WEDGE("Rising Wedge", "Rise Wedge", SignalDirection.SHORT, "Bearish Reversal"),
    DOUBLE_BOTTOM("Double Bottom", "Dbl Bottom", SignalDirection.LONG, "W-Reversal"),
    DOUBLE_TOP("Double Top", "Dbl Top", SignalDirection.SHORT, "M-Reversal"),
    ASCENDING_TRIANGLE("Ascending Triangle", "Asc Tri", SignalDirection.LONG, "Breakout"),
    DESCENDING_TRIANGLE("Descending Triangle", "Desc Tri", SignalDirection.SHORT, "Breakdown")
}

enum class PatternBreakoutStatus(val label: String) {
    FORMING("Forming"),
    IMMINENT_BREAKOUT("Near Breakout"),
    BREAKOUT_CONFIRMED("Breakout Active")
}

data class PatternTrendLine(
    val startIndex: Int,
    val startPrice: Double,
    val endIndex: Int,
    val endPrice: Double
) {
    fun valueAt(index: Int): Double {
        if (endIndex == startIndex) return endPrice
        val t = (index - startIndex).toDouble() / (endIndex - startIndex).toDouble()
        return startPrice + (endPrice - startPrice) * t
    }
}

data class PatternAnchor(
    val index: Int,
    val price: Double,
    val label: String
)

data class DetectedChartPattern(
    val id: String,
    val type: ChartPatternType,
    val direction: SignalDirection,
    val status: PatternBreakoutStatus,
    val confidenceScore: Int, // 50..96
    val startIndex: Int,
    val endIndex: Int,
    val upperLine: PatternTrendLine?,
    val lowerLine: PatternTrendLine?,
    val anchorPoints: List<PatternAnchor>,
    val breakoutLevel: Double,
    val targetPrice: Double,
    val invalidationPrice: Double,
    val riskRewardRatio: Double,
    val summary: String,
    val breakoutNote: String
) {
    val isBullish: Boolean get() = direction == SignalDirection.LONG
    val projectedMovePercent: Double
        get() = if (breakoutLevel > 0.0) {
            abs(targetPrice - breakoutLevel) / breakoutLevel * 100.0
        } else 0.0
}

/**
 * Geometric + statistical Chart Pattern Recognition engine.
 * Automatically scans OHLCV candles for Flags, Wedges, Double Bottoms/Tops, and Triangles,
 * projecting measured-move breakout targets and invalidation levels for chart overlays and tooltips.
 */
object ChartPatternEngine {

    private data class LineFit(
        val slope: Double,
        val intercept: Double,
        val r2: Double
    ) {
        fun y(x: Int): Double = slope * x + intercept
    }

    fun detect(candles: List<Candle>, maxPatterns: Int = 3): List<DetectedChartPattern> {
        if (candles.size < 24) return emptyList()

        val atrSeries = TechnicalIndicators.atr(candles, 14)
        val avgAtr = atrSeries.filterNotNull().takeLast(30).average()
            .takeIf { it.isFinite() && it > 0.0 }
            ?: candles.takeLast(20).map { it.high - it.low }.average().coerceAtLeast(1e-6)
        if (!avgAtr.isFinite() || avgAtr <= 0.0) return emptyList()

        val volMa = AdvancedIndicators.volumeSma(candles, 20)
        val swingsTight = AdvancedIndicators.swingPoints(candles, left = 2, right = 2)
        val swingsWide = AdvancedIndicators.swingPoints(candles, left = 3, right = 3)
        val allSwings = (swingsTight + swingsWide)
            .distinctBy { it.index to it.isHigh }
            .sortedBy { it.index }

        val candidates = mutableListOf<DetectedChartPattern>()

        detectDoubleBottoms(candles, allSwings, avgAtr, volMa).let { candidates += it }
        detectDoubleTops(candles, allSwings, avgAtr, volMa).let { candidates += it }
        detectFlags(candles, avgAtr, volMa).let { candidates += it }
        detectWedgesAndTriangles(candles, avgAtr, volMa).let { candidates += it }

        if (candidates.isEmpty()) return emptyList()

        // Keep highest-confidence non-duplicate patterns (prefer distinct pattern types & freshest setups)
        val ranked = candidates.sortedWith(
            compareByDescending<DetectedChartPattern> {
                when (it.status) {
                    PatternBreakoutStatus.BREAKOUT_CONFIRMED -> 3
                    PatternBreakoutStatus.IMMINENT_BREAKOUT -> 2
                    PatternBreakoutStatus.FORMING -> 1
                }
            }
                .thenByDescending { it.confidenceScore }
                .thenByDescending { it.endIndex }
        )

        val chosen = mutableListOf<DetectedChartPattern>()
        for (cand in ranked) {
            val sameTypeExists = chosen.any { it.type == cand.type }
            val heavyOverlap = chosen.any { existing ->
                val overlapStart = max(existing.startIndex, cand.startIndex)
                val overlapEnd = min(existing.endIndex, cand.endIndex)
                val overlapLen = (overlapEnd - overlapStart).coerceAtLeast(0)
                val minLen = min(existing.endIndex - existing.startIndex, cand.endIndex - cand.startIndex).coerceAtLeast(1)
                existing.direction == cand.direction && overlapLen.toDouble() / minLen > 0.72
            }
            if (!sameTypeExists && !heavyOverlap) {
                chosen += cand
                if (chosen.size >= maxPatterns) break
            }
        }
        return chosen
    }

    private fun detectDoubleBottoms(
        candles: List<Candle>,
        swings: List<SwingPoint>,
        avgAtr: Double,
        volMa: List<Double?>
    ): List<DetectedChartPattern> {
        val lastIdx = candles.lastIndex
        val minStart = (lastIdx - 95).coerceAtLeast(0)
        val lows = swings.filter { !it.isHigh && it.index >= minStart }
        if (lows.size < 2) return emptyList()

        val found = mutableListOf<DetectedChartPattern>()
        for (i in 0 until lows.size - 1) {
            val l1 = lows[i]
            for (j in (i + 1) until lows.size) {
                val l2 = lows[j]
                val span = l2.index - l1.index
                if (span !in 6..48) continue
                if (lastIdx - l2.index > 24) continue

                val avgLow = (l1.price + l2.price) / 2.0
                val lowDiff = abs(l1.price - l2.price)
                val tolerance = max(avgAtr * 1.15, avgLow * 0.018)
                if (lowDiff > tolerance) continue

                // Ensure no bar between l1 and l2 violates deeply below the double bottom
                var neckIdx = l1.index + 1
                var neckHigh = Double.NEGATIVE_INFINITY
                var violated = false
                for (k in (l1.index + 1) until l2.index) {
                    if (candles[k].low < min(l1.price, l2.price) - avgAtr * 0.45) {
                        violated = true
                        break
                    }
                    if (candles[k].high > neckHigh) {
                        neckHigh = candles[k].high
                        neckIdx = k
                    }
                }
                if (violated || !neckHigh.isFinite()) continue

                val height = neckHigh - avgLow
                if (height < avgAtr * 1.55) continue

                // Verify price hasn't broken back below bottoms after l2
                var postViolated = false
                for (k in (l2.index + 1)..lastIdx) {
                    if (candles[k].close < min(l1.price, l2.price) - avgAtr * 0.35) {
                        postViolated = true
                        break
                    }
                }
                if (postViolated) continue

                val lastClose = candles[lastIdx].close
                val breakout = neckHigh
                val target = breakout + height
                val invalidation = min(l1.price, l2.price) - avgAtr * 0.35
                val risk = (breakout - invalidation).coerceAtLeast(avgAtr * 0.25)
                val rr = ((target - breakout) / risk).coerceIn(0.6, 5.0)
                if (rr < 0.85) continue

                val status = when {
                    lastClose > breakout -> PatternBreakoutStatus.BREAKOUT_CONFIRMED
                    lastClose >= breakout - height * 0.28 -> PatternBreakoutStatus.IMMINENT_BREAKOUT
                    else -> PatternBreakoutStatus.FORMING
                }

                // Confidence scoring: trough symmetry + volume divergence (lighter volume on 2nd bottom) + depth
                val symmetryScore = (1.0 - (lowDiff / tolerance).coerceIn(0.0, 1.0)) * 22.0
                val depthScore = ((height / avgAtr) / 3.5).coerceIn(0.3, 1.0) * 16.0
                val v1 = candles[l1.index].volume
                val v2 = candles[l2.index].volume
                val volBonus = if (v2 <= v1 * 1.05) 8.0 else 2.0
                val lastVol = candles[lastIdx].volume
                val lastMa = volMa.getOrNull(lastIdx) ?: lastVol
                val boVolBonus = if (status != PatternBreakoutStatus.FORMING && lastVol > lastMa) 8.0 else 0.0
                val confidence = (44.0 + symmetryScore + depthScore + volBonus + boVolBonus).roundToInt().coerceIn(56, 95)

                val endIdx = min(lastIdx, l2.index + 10)
                val leadIdx = (l1.index - 4).coerceAtLeast(0)
                found += DetectedChartPattern(
                    id = "dbl_bot_${l1.index}_${l2.index}",
                    type = ChartPatternType.DOUBLE_BOTTOM,
                    direction = SignalDirection.LONG,
                    status = status,
                    confidenceScore = confidence,
                    startIndex = l1.index,
                    endIndex = endIdx,
                    upperLine = PatternTrendLine(l1.index, breakout, endIdx, breakout),
                    lowerLine = PatternTrendLine(l1.index, l1.price, l2.index, l2.price),
                    anchorPoints = listOf(
                        PatternAnchor(leadIdx, candles[leadIdx].high, "Drop"),
                        PatternAnchor(l1.index, l1.price, "Bottom 1"),
                        PatternAnchor(neckIdx, neckHigh, "Neckline"),
                        PatternAnchor(l2.index, l2.price, "Bottom 2"),
                        PatternAnchor(endIdx, candles[endIdx].close, "Trigger")
                    ),
                    breakoutLevel = breakout,
                    targetPrice = target,
                    invalidationPrice = invalidation,
                    riskRewardRatio = rr,
                    summary = "W-shaped bullish reversal with twin support troughs spaced $span bars apart and neckline at ${fmt(breakout)}.",
                    breakoutNote = if (status == PatternBreakoutStatus.BREAKOUT_CONFIRMED) {
                        "Neckline cleared above ${fmt(breakout)} — measured-move target projects to ${fmt(target)}."
                    } else {
                        "Watch for strong candle close above neckline ${fmt(breakout)} to trigger measured rally toward ${fmt(target)}."
                    }
                )
            }
        }
        return found.sortedByDescending { it.confidenceScore }.take(2)
    }

    private fun detectDoubleTops(
        candles: List<Candle>,
        swings: List<SwingPoint>,
        avgAtr: Double,
        volMa: List<Double?>
    ): List<DetectedChartPattern> {
        val lastIdx = candles.lastIndex
        val minStart = (lastIdx - 95).coerceAtLeast(0)
        val highs = swings.filter { it.isHigh && it.index >= minStart }
        if (highs.size < 2) return emptyList()

        val found = mutableListOf<DetectedChartPattern>()
        for (i in 0 until highs.size - 1) {
            val h1 = highs[i]
            for (j in (i + 1) until highs.size) {
                val h2 = highs[j]
                val span = h2.index - h1.index
                if (span !in 6..48) continue
                if (lastIdx - h2.index > 24) continue

                val avgHigh = (h1.price + h2.price) / 2.0
                val highDiff = abs(h1.price - h2.price)
                val tolerance = max(avgAtr * 1.15, avgHigh * 0.018)
                if (highDiff > tolerance) continue

                var neckIdx = h1.index + 1
                var neckLow = Double.POSITIVE_INFINITY
                var violated = false
                for (k in (h1.index + 1) until h2.index) {
                    if (candles[k].high > max(h1.price, h2.price) + avgAtr * 0.45) {
                        violated = true
                        break
                    }
                    if (candles[k].low < neckLow) {
                        neckLow = candles[k].low
                        neckIdx = k
                    }
                }
                if (violated || !neckLow.isFinite()) continue

                val height = avgHigh - neckLow
                if (height < avgAtr * 1.55) continue

                var postViolated = false
                for (k in (h2.index + 1)..lastIdx) {
                    if (candles[k].close > max(h1.price, h2.price) + avgAtr * 0.35) {
                        postViolated = true
                        break
                    }
                }
                if (postViolated) continue

                val lastClose = candles[lastIdx].close
                val breakout = neckLow
                val target = (breakout - height).coerceAtLeast(breakout * 0.25)
                val invalidation = max(h1.price, h2.price) + avgAtr * 0.35
                val risk = (invalidation - breakout).coerceAtLeast(avgAtr * 0.25)
                val rr = ((breakout - target) / risk).coerceIn(0.6, 5.0)
                if (rr < 0.85) continue

                val status = when {
                    lastClose < breakout -> PatternBreakoutStatus.BREAKOUT_CONFIRMED
                    lastClose <= breakout + height * 0.28 -> PatternBreakoutStatus.IMMINENT_BREAKOUT
                    else -> PatternBreakoutStatus.FORMING
                }

                val symmetryScore = (1.0 - (highDiff / tolerance).coerceIn(0.0, 1.0)) * 22.0
                val depthScore = ((height / avgAtr) / 3.5).coerceIn(0.3, 1.0) * 16.0
                val v1 = candles[h1.index].volume
                val v2 = candles[h2.index].volume
                val volBonus = if (v2 <= v1 * 1.05) 8.0 else 2.0
                val lastVol = candles[lastIdx].volume
                val lastMa = volMa.getOrNull(lastIdx) ?: lastVol
                val boVolBonus = if (status != PatternBreakoutStatus.FORMING && lastVol > lastMa) 8.0 else 0.0
                val confidence = (44.0 + symmetryScore + depthScore + volBonus + boVolBonus).roundToInt().coerceIn(56, 95)

                val endIdx = min(lastIdx, h2.index + 10)
                val leadIdx = (h1.index - 4).coerceAtLeast(0)
                found += DetectedChartPattern(
                    id = "dbl_top_${h1.index}_${h2.index}",
                    type = ChartPatternType.DOUBLE_TOP,
                    direction = SignalDirection.SHORT,
                    status = status,
                    confidenceScore = confidence,
                    startIndex = h1.index,
                    endIndex = endIdx,
                    upperLine = PatternTrendLine(h1.index, h1.price, h2.index, h2.price),
                    lowerLine = PatternTrendLine(h1.index, breakout, endIdx, breakout),
                    anchorPoints = listOf(
                        PatternAnchor(leadIdx, candles[leadIdx].low, "Rally"),
                        PatternAnchor(h1.index, h1.price, "Top 1"),
                        PatternAnchor(neckIdx, neckLow, "Neckline"),
                        PatternAnchor(h2.index, h2.price, "Top 2"),
                        PatternAnchor(endIdx, candles[endIdx].close, "Trigger")
                    ),
                    breakoutLevel = breakout,
                    targetPrice = target,
                    invalidationPrice = invalidation,
                    riskRewardRatio = rr,
                    summary = "M-shaped bearish reversal with twin resistance peaks spaced $span bars apart and neckline at ${fmt(breakout)}.",
                    breakoutNote = if (status == PatternBreakoutStatus.BREAKOUT_CONFIRMED) {
                        "Breakdown confirmed below ${fmt(breakout)} — measured downside projects to ${fmt(target)}."
                    } else {
                        "Watch for close below neckline ${fmt(breakout)} to confirm breakdown toward ${fmt(target)}."
                    }
                )
            }
        }
        return found.sortedByDescending { it.confidenceScore }.take(2)
    }

    private fun detectFlags(
        candles: List<Candle>,
        avgAtr: Double,
        volMa: List<Double?>
    ): List<DetectedChartPattern> {
        val lastIdx = candles.lastIndex
        var bestBull: DetectedChartPattern? = null
        var bestBear: DetectedChartPattern? = null

        val flagLengths = listOf(8, 12, 16, 20)
        val poleLengths = listOf(6, 10, 14)

        for (flagLen in flagLengths) {
            val flagEnd = lastIdx
            val flagStart = flagEnd - flagLen
            if (flagStart < 10) continue

            val upperPts = envelopePoints(candles, flagStart, flagEnd, isHigh = true)
            val lowerPts = envelopePoints(candles, flagStart, flagEnd, isHigh = false)
            if (upperPts.size < 2 || lowerPts.size < 2) continue

            val upperFit = fitLine(upperPts) ?: continue
            val lowerFit = fitLine(lowerPts) ?: continue

            val uStart = upperFit.y(flagStart)
            val uEnd = upperFit.y(flagEnd)
            val lStart = lowerFit.y(flagStart)
            val lEnd = lowerFit.y(flagEnd)
            val spreadStart = uStart - lStart
            val spreadEnd = uEnd - lEnd
            if (spreadStart <= avgAtr * 0.25 || spreadEnd <= avgAtr * 0.25) continue

            // Roughly parallel flag channel
            val spreadRatio = max(spreadStart, spreadEnd) / min(spreadStart, spreadEnd).coerceAtLeast(1e-6)
            if (spreadRatio > 1.65) continue

            val avgChannelSlope = (upperFit.slope + lowerFit.slope) / 2.0
            val flagAvgVol = (flagStart..flagEnd).map { candles[it].volume }.average()

            for (poleLen in poleLengths) {
                val poleStart = (flagStart - poleLen).coerceAtLeast(0)
                if (flagStart - poleStart < 4) continue

                val poleSlice = candles.subList(poleStart, flagStart + 1)
                val poleMin = poleSlice.minOf { it.low }
                val poleMax = poleSlice.maxOf { it.high }
                val poleHeight = poleMax - poleMin
                if (poleHeight < avgAtr * 2.2) continue

                val poleAvgVol = poleSlice.map { it.volume }.average()
                val volContraction = poleAvgVol > 0.0 && flagAvgVol < poleAvgVol * 0.95

                // Check Bull Flag: strong upward flagpole followed by slight downward/flat consolidation
                val poleStartClose = candles[poleStart].close
                val poleEndClose = candles[flagStart].close
                if (poleEndClose - poleStartClose >= avgAtr * 1.9 &&
                    avgChannelSlope <= avgAtr * 0.06 &&
                    avgChannelSlope >= -avgAtr * 0.45 &&
                    spreadEnd <= poleHeight * 0.65
                ) {
                    val flagMinLow = (flagStart..flagEnd).minOf { candles[it].low }
                    val retraceFrac = (poleMax - flagMinLow) / poleHeight.coerceAtLeast(1e-6)
                    if (retraceFrac in 0.10..0.58) {
                        val breakout = uEnd
                        val target = breakout + poleHeight * 0.85
                        val invalidation = min(lEnd, flagMinLow) - avgAtr * 0.25
                        val risk = (breakout - invalidation).coerceAtLeast(avgAtr * 0.25)
                        val rr = ((target - breakout) / risk).coerceIn(0.8, 6.0)
                        val lastClose = candles[lastIdx].close
                        val status = when {
                            lastClose > breakout -> PatternBreakoutStatus.BREAKOUT_CONFIRMED
                            lastClose >= breakout - spreadEnd * 0.30 -> PatternBreakoutStatus.IMMINENT_BREAKOUT
                            else -> PatternBreakoutStatus.FORMING
                        }

                        val fitQuality = ((upperFit.r2 + lowerFit.r2) * 0.5).coerceIn(0.0, 1.0)
                        val poleStrength = ((poleHeight / avgAtr) / 4.0).coerceIn(0.4, 1.0)
                        val confidence = (48.0 + fitQuality * 18.0 + poleStrength * 16.0 +
                            (if (volContraction) 9.0 else 2.0) +
                            (if (status != PatternBreakoutStatus.FORMING) 5.0 else 0.0)).roundToInt().coerceIn(58, 95)

                        val cand = DetectedChartPattern(
                            id = "bull_flag_${poleStart}_$flagEnd",
                            type = ChartPatternType.BULL_FLAG,
                            direction = SignalDirection.LONG,
                            status = status,
                            confidenceScore = confidence,
                            startIndex = poleStart,
                            endIndex = flagEnd,
                            upperLine = PatternTrendLine(flagStart, uStart, flagEnd, uEnd),
                            lowerLine = PatternTrendLine(flagStart, lStart, flagEnd, lEnd),
                            anchorPoints = listOf(
                                PatternAnchor(poleStart, poleMin, "Pole Base"),
                                PatternAnchor(flagStart, poleMax, "Flag Top"),
                                PatternAnchor(flagEnd, breakout, "Breakout")
                            ),
                            breakoutLevel = breakout,
                            targetPrice = target,
                            invalidationPrice = invalidation,
                            riskRewardRatio = rr,
                            summary = "Bullish impulse flagpole (+${String.format(Locale.US, "%.1f", poleHeight / avgAtr)} ATR) consolidating in a tight ${flagLen}-bar flag channel.",
                            breakoutNote = if (status == PatternBreakoutStatus.BREAKOUT_CONFIRMED) {
                                "Upper flag resistance broken at ${fmt(breakout)} — flagpole projection targets ${fmt(target)}."
                            } else {
                                "Breakout above upper flag trendline ${fmt(breakout)} unlocks continuation target ${fmt(target)}."
                            }
                        )
                        if (bestBull == null || cand.confidenceScore > bestBull.confidenceScore) {
                            bestBull = cand
                        }
                    }
                }

                // Check Bear Flag: sharp downward flagpole followed by slight upward/flat relief channel
                if (poleStartClose - poleEndClose >= avgAtr * 1.9 &&
                    avgChannelSlope >= -avgAtr * 0.06 &&
                    avgChannelSlope <= avgAtr * 0.45 &&
                    spreadEnd <= poleHeight * 0.65
                ) {
                    val flagMaxHigh = (flagStart..flagEnd).maxOf { candles[it].high }
                    val retraceFrac = (flagMaxHigh - poleMin) / poleHeight.coerceAtLeast(1e-6)
                    if (retraceFrac in 0.10..0.58) {
                        val breakout = lEnd
                        val target = (breakout - poleHeight * 0.85).coerceAtLeast(breakout * 0.25)
                        val invalidation = max(uEnd, flagMaxHigh) + avgAtr * 0.25
                        val risk = (invalidation - breakout).coerceAtLeast(avgAtr * 0.25)
                        val rr = ((breakout - target) / risk).coerceIn(0.8, 6.0)
                        val lastClose = candles[lastIdx].close
                        val status = when {
                            lastClose < breakout -> PatternBreakoutStatus.BREAKOUT_CONFIRMED
                            lastClose <= breakout + spreadEnd * 0.30 -> PatternBreakoutStatus.IMMINENT_BREAKOUT
                            else -> PatternBreakoutStatus.FORMING
                        }

                        val fitQuality = ((upperFit.r2 + lowerFit.r2) * 0.5).coerceIn(0.0, 1.0)
                        val poleStrength = ((poleHeight / avgAtr) / 4.0).coerceIn(0.4, 1.0)
                        val confidence = (48.0 + fitQuality * 18.0 + poleStrength * 16.0 +
                            (if (volContraction) 9.0 else 2.0) +
                            (if (status != PatternBreakoutStatus.FORMING) 5.0 else 0.0)).roundToInt().coerceIn(58, 95)

                        val cand = DetectedChartPattern(
                            id = "bear_flag_${poleStart}_$flagEnd",
                            type = ChartPatternType.BEAR_FLAG,
                            direction = SignalDirection.SHORT,
                            status = status,
                            confidenceScore = confidence,
                            startIndex = poleStart,
                            endIndex = flagEnd,
                            upperLine = PatternTrendLine(flagStart, uStart, flagEnd, uEnd),
                            lowerLine = PatternTrendLine(flagStart, lStart, flagEnd, lEnd),
                            anchorPoints = listOf(
                                PatternAnchor(poleStart, poleMax, "Pole Top"),
                                PatternAnchor(flagStart, poleMin, "Flag Base"),
                                PatternAnchor(flagEnd, breakout, "Breakdown")
                            ),
                            breakoutLevel = breakout,
                            targetPrice = target,
                            invalidationPrice = invalidation,
                            riskRewardRatio = rr,
                            summary = "Bearish impulse drop (-${String.format(Locale.US, "%.1f", poleHeight / avgAtr)} ATR) followed by ${flagLen}-bar counter-trend bear flag.",
                            breakoutNote = if (status == PatternBreakoutStatus.BREAKOUT_CONFIRMED) {
                                "Lower flag support lost at ${fmt(breakout)} — flagpole projection targets ${fmt(target)}."
                            } else {
                                "Close below lower flag support ${fmt(breakout)} triggers continuation move toward ${fmt(target)}."
                            }
                        )
                        if (bestBear == null || cand.confidenceScore > bestBear.confidenceScore) {
                            bestBear = cand
                        }
                    }
                }
            }
        }
        return listOfNotNull(bestBull, bestBear)
    }

    private fun detectWedgesAndTriangles(
        candles: List<Candle>,
        avgAtr: Double,
        volMa: List<Double?>
    ): List<DetectedChartPattern> {
        val lastIdx = candles.lastIndex
        val windowSizes = listOf(16, 22, 30, 38)
        val bestByType = LinkedHashMap<ChartPatternType, DetectedChartPattern>()

        for (win in windowSizes) {
            val start = lastIdx - win
            if (start < 2) continue
            val end = lastIdx

            val upperPts = envelopePoints(candles, start, end, isHigh = true)
            val lowerPts = envelopePoints(candles, start, end, isHigh = false)
            if (upperPts.size < 3 || lowerPts.size < 3) continue

            val upperFit = fitLine(upperPts) ?: continue
            val lowerFit = fitLine(lowerPts) ?: continue

            val uStart = upperFit.y(start)
            val uEnd = upperFit.y(end)
            val lStart = lowerFit.y(start)
            val lEnd = lowerFit.y(end)
            val spreadStart = uStart - lStart
            val spreadEnd = uEnd - lEnd

            if (spreadStart <= avgAtr * 1.2 || spreadEnd <= avgAtr * 0.2) continue
            // Must converge meaningfully (narrowing channel)
            val convergenceRatio = spreadStart / spreadEnd
            if (convergenceRatio < 1.28 || convergenceRatio > 6.5) continue

            val uDelta = uEnd - uStart
            val lDelta = lEnd - lStart
            val flatThresh = avgAtr * 0.55
            val slopeThresh = avgAtr * 0.70

            val patternType = when {
                // Falling Wedge: both sloping down, upper falling steeper -> bullish breakout
                uDelta <= -slopeThresh && lDelta <= -avgAtr * 0.25 && upperFit.slope < lowerFit.slope ->
                    ChartPatternType.FALLING_WEDGE
                // Rising Wedge: both sloping up, lower rising steeper -> bearish breakdown
                uDelta >= avgAtr * 0.25 && lDelta >= slopeThresh && lowerFit.slope > upperFit.slope ->
                    ChartPatternType.RISING_WEDGE
                // Ascending Triangle: flat top + rising lows
                abs(uDelta) <= flatThresh && lDelta >= slopeThresh ->
                    ChartPatternType.ASCENDING_TRIANGLE
                // Descending Triangle: flat bottom + falling highs
                abs(lDelta) <= flatThresh && uDelta <= -slopeThresh ->
                    ChartPatternType.DESCENDING_TRIANGLE
                else -> null
            } ?: continue

            val isBull = patternType.defaultDirection == SignalDirection.LONG
            val lastClose = candles[lastIdx].close
            val breakout = if (isBull) uEnd else lEnd
            val target = if (isBull) {
                breakout + spreadStart * 0.90
            } else {
                (breakout - spreadStart * 0.90).coerceAtLeast(breakout * 0.25)
            }
            val invalidation = if (isBull) {
                lEnd - avgAtr * 0.30
            } else {
                uEnd + avgAtr * 0.30
            }
            val risk = abs(breakout - invalidation).coerceAtLeast(avgAtr * 0.25)
            val rr = (abs(target - breakout) / risk).coerceIn(0.8, 6.0)
            if (rr < 1.0) continue

            val status = if (isBull) {
                when {
                    lastClose > breakout -> PatternBreakoutStatus.BREAKOUT_CONFIRMED
                    lastClose >= breakout - spreadEnd * 0.32 -> PatternBreakoutStatus.IMMINENT_BREAKOUT
                    else -> PatternBreakoutStatus.FORMING
                }
            } else {
                when {
                    lastClose < breakout -> PatternBreakoutStatus.BREAKOUT_CONFIRMED
                    lastClose <= breakout + spreadEnd * 0.32 -> PatternBreakoutStatus.IMMINENT_BREAKOUT
                    else -> PatternBreakoutStatus.FORMING
                }
            }

            // Volume compression inside converging apex strengthens breakout odds
            val firstHalfVol = (start until (start + win / 2)).map { candles[it].volume }.average()
            val secondHalfVol = ((start + win / 2)..end).map { candles[it].volume }.average()
            val volSqueeze = firstHalfVol > 0.0 && secondHalfVol < firstHalfVol * 0.95
            val lastVol = candles[lastIdx].volume
            val lastMa = volMa.getOrNull(lastIdx) ?: lastVol
            val breakoutVol = status == PatternBreakoutStatus.BREAKOUT_CONFIRMED && lastVol > lastMa

            val fitScore = ((upperFit.r2 + lowerFit.r2) * 0.5).coerceIn(0.0, 1.0) * 20.0
            val convScore = ((convergenceRatio - 1.2) / 1.8).coerceIn(0.0, 1.0) * 14.0
            val confidence = (46.0 + fitScore + convScore +
                (if (volSqueeze) 8.0 else 2.0) +
                (if (breakoutVol) 7.0 else 0.0)).roundToInt().coerceIn(57, 94)

            val summary = when (patternType) {
                ChartPatternType.FALLING_WEDGE ->
                    "Converging downward wedge over $win bars (${String.format(Locale.US, "%.1f", convergenceRatio)}x squeeze) signaling bullish breakout energy."
                ChartPatternType.RISING_WEDGE ->
                    "Converging upward wedge over $win bars (${String.format(Locale.US, "%.1f", convergenceRatio)}x squeeze) warning of bearish exhaustion breakdown."
                ChartPatternType.ASCENDING_TRIANGLE ->
                    "Flat supply ceiling near ${fmt(uEnd)} met by higher lows over $win bars — buyers coiling for upside breakout."
                ChartPatternType.DESCENDING_TRIANGLE ->
                    "Flat demand floor near ${fmt(lEnd)} pressured by lower highs over $win bars — sellers coiling for breakdown."
                else -> ""
            }
            val boNote = if (isBull) {
                if (status == PatternBreakoutStatus.BREAKOUT_CONFIRMED) {
                    "Price broke above upper trendline ${fmt(breakout)} — wedge/triangle height projects to ${fmt(target)}."
                } else {
                    "Wait for decisive close above upper trendline ${fmt(breakout)} to target ${fmt(target)}."
                }
            } else {
                if (status == PatternBreakoutStatus.BREAKOUT_CONFIRMED) {
                    "Price broke below lower support ${fmt(breakout)} — measured downside target sits at ${fmt(target)}."
                } else {
                    "Watch for breakdown close under lower trendline ${fmt(breakout)} toward ${fmt(target)}."
                }
            }

            val cand = DetectedChartPattern(
                id = "${patternType.name.lowercase(Locale.US)}_${start}_$end",
                type = patternType,
                direction = patternType.defaultDirection,
                status = status,
                confidenceScore = confidence,
                startIndex = start,
                endIndex = end,
                upperLine = PatternTrendLine(start, uStart, end, uEnd),
                lowerLine = PatternTrendLine(start, lStart, end, lEnd),
                anchorPoints = listOf(
                    PatternAnchor(upperPts.first().first, upperPts.first().second, "Upper 1"),
                    PatternAnchor(lowerPts.first().first, lowerPts.first().second, "Lower 1"),
                    PatternAnchor(upperPts.last().first, upperPts.last().second, "Upper 2"),
                    PatternAnchor(lowerPts.last().first, lowerPts.last().second, "Lower 2")
                ),
                breakoutLevel = breakout,
                targetPrice = target,
                invalidationPrice = invalidation,
                riskRewardRatio = rr,
                summary = summary,
                breakoutNote = boNote
            )

            val prev = bestByType[patternType]
            if (prev == null || cand.confidenceScore > prev.confidenceScore) {
                bestByType[patternType] = cand
            }
        }
        return bestByType.values.toList()
    }

    /** Divides [start..end] into 4 buckets and extracts extreme highs or lows for clean trendline fitting. */
    private fun envelopePoints(
        candles: List<Candle>,
        start: Int,
        end: Int,
        isHigh: Boolean
    ): List<Pair<Int, Double>> {
        val len = end - start + 1
        if (len < 6) return emptyList()
        val buckets = if (len >= 16) 4 else 3
        val step = len.toDouble() / buckets
        val pts = mutableListOf<Pair<Int, Double>>()
        for (b in 0 until buckets) {
            val bStart = (start + (b * step).toInt()).coerceIn(start, end)
            val bEnd = if (b == buckets - 1) end else (start + ((b + 1) * step).toInt() - 1).coerceIn(bStart, end)
            var bestIdx = bStart
            var bestVal = if (isHigh) candles[bStart].high else candles[bStart].low
            for (k in bStart..bEnd) {
                val v = if (isHigh) candles[k].high else candles[k].low
                if ((isHigh && v > bestVal) || (!isHigh && v < bestVal)) {
                    bestVal = v
                    bestIdx = k
                }
            }
            pts += bestIdx to bestVal
        }
        return pts.distinctBy { it.first }
    }

    private fun fitLine(points: List<Pair<Int, Double>>): LineFit? {
        if (points.size < 2) return null
        val n = points.size.toDouble()
        val meanX = points.sumOf { it.first.toDouble() } / n
        val meanY = points.sumOf { it.second } / n
        var sxx = 0.0
        var sxy = 0.0
        var syy = 0.0
        for ((xi, yi) in points) {
            val dx = xi - meanX
            val dy = yi - meanY
            sxx += dx * dx
            sxy += dx * dy
            syy += dy * dy
        }
        if (sxx <= 1e-9) return null
        val slope = sxy / sxx
        val intercept = meanY - slope * meanX
        val r2 = if (syy <= 1e-9) 1.0 else {
            val r = sxy / sqrt(sxx * syy)
            (r * r).coerceIn(0.0, 1.0)
        }
        return LineFit(slope = slope, intercept = intercept, r2 = r2)
    }

    private fun fmt(v: Double): String = when {
        abs(v) >= 1000.0 -> String.format(Locale.US, "%,.2f", v)
        abs(v) >= 1.0 -> String.format(Locale.US, "%.2f", v)
        else -> String.format(Locale.US, "%.4f", v)
    }
}
