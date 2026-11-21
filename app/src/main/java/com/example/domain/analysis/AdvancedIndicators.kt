package com.example.domain.analysis

import com.example.domain.model.MarketSegment
import com.example.domain.signal.Candle
import com.example.domain.signal.TechnicalIndicators
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class VwapPoint(
    val vwap: Double,
    val stdDev: Double
) {
    fun upper(mult: Double) = vwap + mult * stdDev
    fun lower(mult: Double) = vwap - mult * stdDev
}

data class VolumeProfileBin(
    val priceLow: Double,
    val priceHigh: Double,
    val volume: Double,
    val buyVolume: Double,
    val sellVolume: Double
) {
    val mid: Double get() = (priceLow + priceHigh) / 2.0
}

data class VolumeProfile(
    val bins: List<VolumeProfileBin>,
    val poc: Double,
    val valueAreaHigh: Double,
    val valueAreaLow: Double,
    val totalVolume: Double
) {
    val maxBinVolume: Double get() = bins.maxOfOrNull { it.volume } ?: 0.0
}

enum class PivotPeriod(val label: String) { DAILY("Daily"), WEEKLY("Weekly"), MONTHLY("Monthly") }

/** Standard floor pivots + Central Pivot Range + Camarilla, from one prior period's H/L/C. */
data class PivotLevels(
    val period: PivotPeriod,
    val high: Double,
    val low: Double,
    val close: Double,
    val pivot: Double,
    val r1: Double,
    val r2: Double,
    val r3: Double,
    val s1: Double,
    val s2: Double,
    val s3: Double,
    val cprTop: Double,
    val cprBottom: Double,
    val camR3: Double,
    val camR4: Double,
    val camS3: Double,
    val camS4: Double
) {
    val cprWidthPercent: Double get() = if (pivot > 0) abs(cprTop - cprBottom) / pivot * 100.0 else 0.0

    /** Named levels in descending price order (for drawing and nearest-level lookups). */
    fun namedLevels(): List<Pair<String, Double>> = listOf(
        "R3" to r3, "R2" to r2, "R1" to r1, "TC" to cprTop, "P" to pivot, "BC" to cprBottom,
        "S1" to s1, "S2" to s2, "S3" to s3
    )

    companion object {
        /**
         * P = (H + L + C) / 3, R1 = 2P − L, S1 = 2P − H, R2 = P + (H − L), S2 = P − (H − L),
         * R3 = H + 2(P − L), S3 = L − 2(H − P). CPR: BC = (H + L) / 2, TC = 2P − BC.
         */
        fun fromHlc(period: PivotPeriod, h: Double, l: Double, c: Double): PivotLevels {
            val p = (h + l + c) / 3.0
            val range = h - l
            val bc = (h + l) / 2.0
            val tc = 2.0 * p - bc
            return PivotLevels(
                period = period,
                high = h,
                low = l,
                close = c,
                pivot = p,
                r1 = 2.0 * p - l,
                r2 = p + range,
                r3 = h + 2.0 * (p - l),
                s1 = 2.0 * p - h,
                s2 = p - range,
                s3 = l - 2.0 * (h - p),
                cprTop = max(tc, bc),
                cprBottom = min(tc, bc),
                camR3 = c + range * 1.1 / 4.0,
                camR4 = c + range * 1.1 / 2.0,
                camS3 = c - range * 1.1 / 4.0,
                camS4 = c - range * 1.1 / 2.0
            )
        }
    }
}

data class SwingPoint(val index: Int, val price: Double, val isHigh: Boolean)

enum class DivergenceKind(val label: String, val bullish: Boolean) {
    RSI_REGULAR_BULLISH("RSI Regular Bullish", true),
    RSI_HIDDEN_BULLISH("RSI Hidden Bullish", true),
    RSI_REGULAR_BEARISH("RSI Regular Bearish", false),
    RSI_HIDDEN_BEARISH("RSI Hidden Bearish", false),
    VOLUME_BULLISH("Volume Divergence Bullish (LL on falling volume)", true),
    VOLUME_BEARISH("Volume Divergence Bearish (HH on falling volume)", false),
    DELTA_BULLISH("Delta Divergence Bullish (LL with rising delta)", true),
    DELTA_BEARISH("Delta Divergence Bearish (HH with falling delta)", false)
}

data class Divergence(
    val kind: DivergenceKind,
    val fromIndex: Int,
    val toIndex: Int,
    val fromPrice: Double,
    val toPrice: Double,
    val fromOsc: Double,
    val toOsc: Double
)

data class RegressionResult(val slope: Double, val intercept: Double, val r2: Double) {
    /** Slope as percent of the last fitted value per bar. */
    fun slopePercentPerBar(n: Int): Double {
        val last = intercept + slope * (n - 1)
        return if (abs(last) > 1e-12) slope / last * 100.0 else 0.0
    }
}

data class DonchianPoint(val upper: Double, val lower: Double) {
    val middle: Double get() = (upper + lower) / 2.0
}

object AdvancedIndicators {

    /** Session-anchored VWAP with volume-weighted standard-deviation bands (1σ/2σ/3σ). */
    fun vwapWithBands(
        candles: List<Candle>,
        segment: MarketSegment?,
        resetEachSession: Boolean = true
    ): List<VwapPoint?> {
        if (candles.isEmpty()) return emptyList()
        val out = MutableList<VwapPoint?>(candles.size) { null }
        var day = Long.MIN_VALUE
        var cumV = 0.0
        var cumPv = 0.0
        var cumP2v = 0.0
        var count = 0
        var cumTp = 0.0
        var cumTp2 = 0.0
        for (i in candles.indices) {
            val c = candles[i]
            val key = if (resetEachSession) SessionCalendar.sessionDayKey(c.timestamp.toEpochMilli(), segment) else 0L
            if (key != day) {
                day = key
                cumV = 0.0; cumPv = 0.0; cumP2v = 0.0; count = 0; cumTp = 0.0; cumTp2 = 0.0
            }
            val tp = (c.high + c.low + c.close) / 3.0
            val v = c.volume.coerceAtLeast(0.0)
            cumV += v
            cumPv += tp * v
            cumP2v += tp * tp * v
            count++
            cumTp += tp
            cumTp2 += tp * tp
            out[i] = if (cumV > 0.0) {
                val vw = cumPv / cumV
                val variance = (cumP2v / cumV - vw * vw).coerceAtLeast(0.0)
                VwapPoint(vw, sqrt(variance))
            } else {
                val mean = cumTp / count
                VwapPoint(mean, sqrt((cumTp2 / count - mean * mean).coerceAtLeast(0.0)))
            }
        }
        return out
    }

    fun volumeSma(candles: List<Candle>, period: Int = 20): List<Double?> =
        TechnicalIndicators.sma(candles.map { it.volume }, period)

    fun isVolumeAboveMa(candles: List<Candle>, index: Int, period: Int = 20, mult: Double = 1.0): Boolean {
        if (index !in candles.indices) return false
        val ma = volumeSma(candles, period)[index] ?: return false
        return ma > 0.0 && candles[index].volume > ma * mult
    }

    /** On-Balance Volume. */
    fun obv(candles: List<Candle>): List<Double> {
        if (candles.isEmpty()) return emptyList()
        val out = ArrayList<Double>(candles.size)
        var running = 0.0
        out.add(running)
        for (i in 1 until candles.size) {
            val diff = candles[i].close - candles[i - 1].close
            running += when {
                diff > 0 -> candles[i].volume
                diff < 0 -> -candles[i].volume
                else -> 0.0
            }
            out.add(running)
        }
        return out
    }

    /** Elder Force Index = EMA(period) of (close − previous close) × volume. Default period 13. */
    fun elderForceIndex(candles: List<Candle>, period: Int = 13): List<Double?> {
        if (candles.size < 2) return List(candles.size) { null }
        val raw = ArrayList<Double>(candles.size - 1)
        for (i in 1 until candles.size) {
            raw.add((candles[i].close - candles[i - 1].close) * candles[i].volume)
        }
        val smoothed = TechnicalIndicators.ema(raw, period)
        return listOf<Double?>(null) + smoothed
    }

    /**
     * Per-bar order-flow delta. Uses the true aggressor delta when the feed provided it, otherwise
     * estimates it with the close-location value: delta ≈ volume × ((C − L) − (H − C)) / (H − L).
     */
    fun barDelta(c: Candle): Double {
        c.delta?.let { return it }
        val range = c.high - c.low
        if (range <= 1e-12) return 0.0
        return c.volume * ((c.close - c.low) - (c.high - c.close)) / range
    }

    fun deltas(candles: List<Candle>): List<Double> = candles.map { barDelta(it) }

    /** Cumulative delta, optionally reset at each session. */
    fun cumulativeDelta(candles: List<Candle>, segment: MarketSegment?, resetEachSession: Boolean): List<Double> {
        val out = ArrayList<Double>(candles.size)
        var day = Long.MIN_VALUE
        var running = 0.0
        candles.forEach { c ->
            if (resetEachSession) {
                val key = SessionCalendar.sessionDayKey(c.timestamp.toEpochMilli(), segment)
                if (key != day) {
                    day = key
                    running = 0.0
                }
            }
            running += barDelta(c)
            out.add(running)
        }
        return out
    }

    /**
     * Volume profile: each bar's volume is spread evenly over the bins its range overlaps.
     * Value area holds 70% of volume around the Point of Control.
     */
    fun volumeProfile(candles: List<Candle>, binCount: Int = 24, valueAreaPct: Double = 0.70): VolumeProfile? {
        if (candles.isEmpty() || binCount < 2) return null
        val hi = candles.maxOf { it.high }
        val lo = candles.minOf { it.low }
        if (hi <= lo) return null
        val step = (hi - lo) / binCount
        val vols = DoubleArray(binCount)
        val buys = DoubleArray(binCount)
        val sells = DoubleArray(binCount)
        candles.forEach { c ->
            val v = c.volume.coerceAtLeast(0.0)
            if (v <= 0.0) return@forEach
            val d = barDelta(c)
            val buyShare = ((v + d) / 2.0).coerceIn(0.0, v)
            val first = floor((c.low - lo) / step).toInt().coerceIn(0, binCount - 1)
            val last = floor((c.high - lo) / step).toInt().coerceIn(0, binCount - 1)
            val n = (last - first + 1).toDouble()
            for (b in first..last) {
                vols[b] += v / n
                buys[b] += buyShare / n
                sells[b] += (v - buyShare) / n
            }
        }
        val total = vols.sum()
        if (total <= 0.0) return null
        var pocIdx = 0
        for (b in 1 until binCount) if (vols[b] > vols[pocIdx]) pocIdx = b
        var lowIdx = pocIdx
        var highIdx = pocIdx
        var acc = vols[pocIdx]
        while (acc < total * valueAreaPct && (lowIdx > 0 || highIdx < binCount - 1)) {
            val up = if (highIdx < binCount - 1) vols[highIdx + 1] else -1.0
            val down = if (lowIdx > 0) vols[lowIdx - 1] else -1.0
            if (up >= down) {
                highIdx++
                acc += vols[highIdx]
            } else {
                lowIdx--
                acc += vols[lowIdx]
            }
        }
        val bins = (0 until binCount).map { b ->
            VolumeProfileBin(lo + b * step, lo + (b + 1) * step, vols[b], buys[b], sells[b])
        }
        return VolumeProfile(
            bins = bins,
            poc = bins[pocIdx].mid,
            valueAreaHigh = bins[highIdx].priceHigh,
            valueAreaLow = bins[lowIdx].priceLow,
            totalVolume = total
        )
    }

    /**
     * Pivots from the previous completed period (previous session for intraday charts,
     * previous week for daily charts, previous month for weekly charts).
     */
    fun previousPeriodPivots(
        candles: List<Candle>,
        segment: MarketSegment?,
        period: PivotPeriod
    ): PivotLevels? {
        if (candles.size < 2) return null
        val zone = SessionCalendar.zoneFor(segment)
        fun keyOf(c: Candle): Long {
            val date = c.timestamp.atZone(zone).toLocalDate()
            return when (period) {
                PivotPeriod.DAILY -> date.toEpochDay()
                PivotPeriod.WEEKLY -> date.toEpochDay().let { Math.floorDiv(it + 3, 7L) } // Monday-based weeks
                PivotPeriod.MONTHLY -> date.year * 12L + date.monthValue
            }
        }
        val currentKey = keyOf(candles.last())
        var i = candles.lastIndex
        while (i >= 0 && keyOf(candles[i]) == currentKey) i--
        if (i < 0) return null
        val prevKey = keyOf(candles[i])
        var h = Double.NEGATIVE_INFINITY
        var l = Double.POSITIVE_INFINITY
        val close = candles[i].close
        while (i >= 0 && keyOf(candles[i]) == prevKey) {
            h = max(h, candles[i].high)
            l = min(l, candles[i].low)
            i--
        }
        if (!h.isFinite() || !l.isFinite()) return null
        return PivotLevels.fromHlc(period, h, l, close)
    }

    /** Fractal swing highs/lows: a bar whose high (low) is the extreme of [left] bars before and [right] after. */
    fun swingPoints(candles: List<Candle>, left: Int = 3, right: Int = 3): List<SwingPoint> {
        val out = mutableListOf<SwingPoint>()
        if (candles.size < left + right + 1) return out
        for (i in left until candles.size - right) {
            val h = candles[i].high
            val l = candles[i].low
            var isHigh = true
            var isLow = true
            for (j in (i - left)..(i + right)) {
                if (j == i) continue
                if (candles[j].high > h || (j > i && candles[j].high == h)) isHigh = false
                if (candles[j].low < l || (j > i && candles[j].low == l)) isLow = false
                if (!isHigh && !isLow) break
            }
            if (isHigh) out.add(SwingPoint(i, h, true))
            if (isLow) out.add(SwingPoint(i, l, false))
        }
        return out
    }

    /**
     * RSI divergences (regular + hidden) between consecutive confirmed swing points within
     * [maxBarsApart] bars.
     */
    fun rsiDivergences(
        candles: List<Candle>,
        rsiPeriod: Int = 14,
        swingStrength: Int = 3,
        maxBarsApart: Int = 60
    ): List<Divergence> {
        val rsi = TechnicalIndicators.rsi(candles.map { it.close }, rsiPeriod)
        return oscillatorDivergences(
            swings = swingPoints(candles, swingStrength, swingStrength),
            osc = rsi,
            maxBarsApart = maxBarsApart,
            regularBull = DivergenceKind.RSI_REGULAR_BULLISH,
            hiddenBull = DivergenceKind.RSI_HIDDEN_BULLISH,
            regularBear = DivergenceKind.RSI_REGULAR_BEARISH,
            hiddenBear = DivergenceKind.RSI_HIDDEN_BEARISH
        )
    }

    /** Volume divergence: new price extreme printed on lower volume (exhaustion). */
    fun volumeDivergences(candles: List<Candle>, swingStrength: Int = 3, maxBarsApart: Int = 60): List<Divergence> {
        val volMa = TechnicalIndicators.sma(candles.map { it.volume }, 3)
        val swings = swingPoints(candles, swingStrength, swingStrength)
        val out = mutableListOf<Divergence>()
        val highs = swings.filter { it.isHigh }
        val lows = swings.filter { !it.isHigh }
        for (k in 1 until highs.size) {
            val a = highs[k - 1]
            val b = highs[k]
            if (b.index - a.index > maxBarsApart) continue
            val va = volMa[a.index] ?: candles[a.index].volume
            val vb = volMa[b.index] ?: candles[b.index].volume
            if (b.price > a.price && vb < va * 0.9) {
                out.add(Divergence(DivergenceKind.VOLUME_BEARISH, a.index, b.index, a.price, b.price, va, vb))
            }
        }
        for (k in 1 until lows.size) {
            val a = lows[k - 1]
            val b = lows[k]
            if (b.index - a.index > maxBarsApart) continue
            val va = volMa[a.index] ?: candles[a.index].volume
            val vb = volMa[b.index] ?: candles[b.index].volume
            if (b.price < a.price && vb < va * 0.9) {
                out.add(Divergence(DivergenceKind.VOLUME_BULLISH, a.index, b.index, a.price, b.price, va, vb))
            }
        }
        return out
    }

    /** Cumulative-delta divergence (order-flow): price makes a new extreme that delta does not confirm. */
    fun deltaDivergences(
        candles: List<Candle>,
        segment: MarketSegment?,
        swingStrength: Int = 3,
        maxBarsApart: Int = 60
    ): List<Divergence> {
        val cd = cumulativeDelta(candles, segment, resetEachSession = false)
        val swings = swingPoints(candles, swingStrength, swingStrength)
        val out = mutableListOf<Divergence>()
        val highs = swings.filter { it.isHigh }
        val lows = swings.filter { !it.isHigh }
        for (k in 1 until highs.size) {
            val a = highs[k - 1]
            val b = highs[k]
            if (b.index - a.index > maxBarsApart) continue
            if (b.price > a.price && cd[b.index] < cd[a.index]) {
                out.add(Divergence(DivergenceKind.DELTA_BEARISH, a.index, b.index, a.price, b.price, cd[a.index], cd[b.index]))
            }
        }
        for (k in 1 until lows.size) {
            val a = lows[k - 1]
            val b = lows[k]
            if (b.index - a.index > maxBarsApart) continue
            if (b.price < a.price && cd[b.index] > cd[a.index]) {
                out.add(Divergence(DivergenceKind.DELTA_BULLISH, a.index, b.index, a.price, b.price, cd[a.index], cd[b.index]))
            }
        }
        return out
    }

    private fun oscillatorDivergences(
        swings: List<SwingPoint>,
        osc: List<Double?>,
        maxBarsApart: Int,
        regularBull: DivergenceKind,
        hiddenBull: DivergenceKind,
        regularBear: DivergenceKind,
        hiddenBear: DivergenceKind
    ): List<Divergence> {
        val out = mutableListOf<Divergence>()
        val lows = swings.filter { !it.isHigh }
        val highs = swings.filter { it.isHigh }
        for (k in 1 until lows.size) {
            val a = lows[k - 1]
            val b = lows[k]
            if (b.index - a.index > maxBarsApart) continue
            val oa = osc.getOrNull(a.index) ?: continue
            val ob = osc.getOrNull(b.index) ?: continue
            when {
                b.price < a.price && ob > oa -> out.add(Divergence(regularBull, a.index, b.index, a.price, b.price, oa, ob))
                b.price > a.price && ob < oa -> out.add(Divergence(hiddenBull, a.index, b.index, a.price, b.price, oa, ob))
            }
        }
        for (k in 1 until highs.size) {
            val a = highs[k - 1]
            val b = highs[k]
            if (b.index - a.index > maxBarsApart) continue
            val oa = osc.getOrNull(a.index) ?: continue
            val ob = osc.getOrNull(b.index) ?: continue
            when {
                b.price > a.price && ob < oa -> out.add(Divergence(regularBear, a.index, b.index, a.price, b.price, oa, ob))
                b.price < a.price && ob > oa -> out.add(Divergence(hiddenBear, a.index, b.index, a.price, b.price, oa, ob))
            }
        }
        return out
    }

    // ---------------------------------------------------------------- quant / mathematical tools

    /** Rolling z-score of close versus its SMA: (C − SMA) / σ. */
    fun zScore(values: List<Double>, period: Int = 20): List<Double?> {
        val out = MutableList<Double?>(values.size) { null }
        if (period < 2) return out
        for (i in (period - 1) until values.size) {
            var sum = 0.0
            for (j in (i - period + 1)..i) sum += values[j]
            val mean = sum / period
            var sq = 0.0
            for (j in (i - period + 1)..i) {
                val d = values[j] - mean
                sq += d * d
            }
            val sd = sqrt(sq / period)
            out[i] = if (sd > 1e-12) (values[i] - mean) / sd else 0.0
        }
        return out
    }

    /** Ordinary least squares over the last [period] values (x = 0..n−1). */
    fun linearRegression(values: List<Double>, period: Int = 50): RegressionResult? {
        if (values.size < period || period < 3) return null
        val ys = values.takeLast(period)
        val n = period.toDouble()
        val meanX = (n - 1) / 2.0
        val meanY = ys.average()
        var sxy = 0.0
        var sxx = 0.0
        var syy = 0.0
        ys.forEachIndexed { i, y ->
            val dx = i - meanX
            val dy = y - meanY
            sxy += dx * dy
            sxx += dx * dx
            syy += dy * dy
        }
        if (sxx <= 0.0) return null
        val slope = sxy / sxx
        val intercept = meanY - slope * meanX
        val r2 = if (syy > 0.0) (sxy * sxy) / (sxx * syy) else 0.0
        return RegressionResult(slope, intercept, r2)
    }

    /**
     * Hurst exponent via rescaled-range analysis on log returns.
     * H > 0.55 ⇒ trending/persistent, H < 0.45 ⇒ mean-reverting, ≈0.5 ⇒ random walk.
     */
    fun hurstExponent(closes: List<Double>, minWindow: Int = 8): Double? {
        if (closes.size < minWindow * 4) return null
        val returns = ArrayList<Double>(closes.size - 1)
        for (i in 1 until closes.size) {
            if (closes[i - 1] <= 0.0 || closes[i] <= 0.0) return null
            returns.add(ln(closes[i] / closes[i - 1]))
        }
        val xs = mutableListOf<Double>()
        val ys = mutableListOf<Double>()
        var window = minWindow
        while (window <= returns.size / 2) {
            var rsSum = 0.0
            var chunks = 0
            var start = 0
            while (start + window <= returns.size) {
                val chunk = returns.subList(start, start + window)
                val mean = chunk.average()
                var cum = 0.0
                var maxCum = Double.NEGATIVE_INFINITY
                var minCum = Double.POSITIVE_INFINITY
                var sq = 0.0
                chunk.forEach { r ->
                    cum += r - mean
                    maxCum = max(maxCum, cum)
                    minCum = min(minCum, cum)
                    sq += (r - mean) * (r - mean)
                }
                val sd = sqrt(sq / window)
                if (sd > 1e-12) {
                    rsSum += (maxCum - minCum) / sd
                    chunks++
                }
                start += window
            }
            if (chunks > 0) {
                xs.add(ln(window.toDouble()))
                ys.add(ln(rsSum / chunks))
            }
            window *= 2
        }
        if (xs.size < 2) return null
        val mx = xs.average()
        val my = ys.average()
        var num = 0.0
        var den = 0.0
        for (i in xs.indices) {
            num += (xs[i] - mx) * (ys[i] - my)
            den += (xs[i] - mx) * (xs[i] - mx)
        }
        return if (den > 0) (num / den).coerceIn(0.0, 1.0) else null
    }

    /** Donchian channel of the previous [period] bars (excludes the current bar, so breakouts are detectable). */
    fun donchian(candles: List<Candle>, period: Int = 20): List<DonchianPoint?> {
        val out = MutableList<DonchianPoint?>(candles.size) { null }
        for (i in period until candles.size) {
            var hi = Double.NEGATIVE_INFINITY
            var lo = Double.POSITIVE_INFINITY
            for (j in (i - period) until i) {
                hi = max(hi, candles[j].high)
                lo = min(lo, candles[j].low)
            }
            out[i] = DonchianPoint(hi, lo)
        }
        return out
    }

    /** Bollinger bandwidth (upper − lower) / middle; low values mark a volatility squeeze. */
    fun bollingerBandwidth(closes: List<Double>, period: Int = 20, mult: Double = 2.0): List<Double?> =
        TechnicalIndicators.bollingerBands(closes, period, mult).map { bb ->
            bb?.let { if (it.middle > 0) (it.upper - it.lower) / it.middle else null }
        }

    /** Percentile rank (0..100) of the last non-null value within the trailing [lookback] values. */
    fun percentileRankOfLast(values: List<Double?>, lookback: Int = 120): Double? {
        val window = values.takeLast(lookback).filterNotNull()
        if (window.size < 10) return null
        val last = window.last()
        return window.count { it <= last }.toDouble() / window.size * 100.0
    }

    fun rateOfChange(values: List<Double>, period: Int = 10): Double? {
        if (values.size <= period) return null
        val base = values[values.size - 1 - period]
        return if (base != 0.0) (values.last() - base) / base * 100.0 else null
    }

    /** Annualised-agnostic realised volatility (σ of log returns, in %) over [period] bars. */
    fun realisedVolatilityPercent(closes: List<Double>, period: Int = 20): Double? {
        if (closes.size <= period) return null
        val tail = closes.takeLast(period + 1)
        val rets = (1 until tail.size).mapNotNull { i ->
            if (tail[i - 1] > 0 && tail[i] > 0) ln(tail[i] / tail[i - 1]) else null
        }
        if (rets.size < 2) return null
        val m = rets.average()
        return sqrt(rets.sumOf { (it - m) * (it - m) } / (rets.size - 1)) * 100.0
    }

    /** Kelly fraction f* = W − (1 − W) / R, clamped to [0, 1]. R = average win / average loss. */
    fun kellyFraction(winRate: Double, payoffRatio: Double): Double {
        if (payoffRatio <= 0.0) return 0.0
        return (winRate - (1.0 - winRate) / payoffRatio).coerceIn(0.0, 1.0)
    }

    /**
     * Fixed-fractional position sizing: quantity so that a stop-out loses [riskPercent] of capital,
     * rounded down to whole lots.
     */
    fun positionSizeForRisk(
        capital: Double,
        riskPercent: Double,
        entry: Double,
        stopLoss: Double,
        lotSize: Int
    ): Int {
        val perUnitRisk = abs(entry - stopLoss)
        if (perUnitRisk <= 0.0 || capital <= 0.0 || riskPercent <= 0.0) return 0
        val rawQty = capital * riskPercent / 100.0 / perUnitRisk
        val lot = lotSize.coerceAtLeast(1)
        return (floor(rawQty / lot) * lot).toInt().coerceAtLeast(0)
    }
}
