package com.example.domain.signal

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class MacdPoint(
    val macd: Double,
    val signal: Double,
    val histogram: Double
)

data class StochPoint(
    val k: Double,
    val d: Double
)

data class BollingerBandPoint(
    val middle: Double,
    val upper: Double,
    val lower: Double
)

data class HeikinAshiCandle(
    val timestamp: Instant,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double
)

object TechnicalIndicators {

    fun sma(values: List<Double>, period: Int): List<Double?> {
        if (period <= 0 || values.isEmpty()) return List(values.size) { null }
        val result = MutableList<Double?>(values.size) { null }
        var sum = 0.0
        for (i in values.indices) {
            sum += values[i]
            if (i >= period) {
                sum -= values[i - period]
            }
            if (i >= period - 1) {
                result[i] = sum / period
            }
        }
        return result
    }

    fun ema(values: List<Double>, period: Int): List<Double?> {
        if (period <= 0 || values.size < period) return List(values.size) { null }
        val result = MutableList<Double?>(values.size) { null }
        val k = 2.0 / (period + 1.0)
        var initialSum = 0.0
        for (i in 0 until period) {
            initialSum += values[i]
        }
        var prevEma = initialSum / period
        result[period - 1] = prevEma
        for (i in period until values.size) {
            val current = (values[i] - prevEma) * k + prevEma
            result[i] = current
            prevEma = current
        }
        return result
    }

    fun rsi(closes: List<Double>, period: Int = 14): List<Double?> {
        if (period <= 0 || closes.size <= period) return List(closes.size) { null }
        val result = MutableList<Double?>(closes.size) { null }
        var gainSum = 0.0
        var lossSum = 0.0
        for (i in 1..period) {
            val diff = closes[i] - closes[i - 1]
            if (diff > 0) gainSum += diff else lossSum += -diff
        }
        var avgGain = gainSum / period
        var avgLoss = lossSum / period
        result[period] = computeRsiValue(avgGain, avgLoss)
        for (i in (period + 1) until closes.size) {
            val diff = closes[i] - closes[i - 1]
            val gain = if (diff > 0) diff else 0.0
            val loss = if (diff < 0) -diff else 0.0
            avgGain = ((avgGain * (period - 1)) + gain) / period
            avgLoss = ((avgLoss * (period - 1)) + loss) / period
            result[i] = computeRsiValue(avgGain, avgLoss)
        }
        return result
    }

    private fun computeRsiValue(avgGain: Double, avgLoss: Double): Double {
        if (avgLoss == 0.0 && avgGain == 0.0) return 50.0
        if (avgLoss == 0.0) return 100.0
        val rs = avgGain / avgLoss
        return 100.0 - (100.0 / (1.0 + rs))
    }

    fun macd(
        closes: List<Double>,
        fastPeriod: Int = 12,
        slowPeriod: Int = 26,
        signalPeriod: Int = 9
    ): List<MacdPoint?> {
        if (closes.size < slowPeriod + signalPeriod) return List(closes.size) { null }
        val fastEma = ema(closes, fastPeriod)
        val slowEma = ema(closes, slowPeriod)
        val macdLine = MutableList<Double?>(closes.size) { null }
        val validMacdValues = mutableListOf<Double>()
        val validIndices = mutableListOf<Int>()
        for (i in closes.indices) {
            val f = fastEma[i]
            val s = slowEma[i]
            if (f != null && s != null) {
                val m = f - s
                macdLine[i] = m
                validMacdValues.add(m)
                validIndices.add(i)
            }
        }
        val signalEma = ema(validMacdValues, signalPeriod)
        val result = MutableList<MacdPoint?>(closes.size) { null }
        for (j in validIndices.indices) {
            val sig = signalEma[j] ?: continue
            val m = validMacdValues[j]
            result[validIndices[j]] = MacdPoint(
                macd = m,
                signal = sig,
                histogram = m - sig
            )
        }
        return result
    }

    fun stochastic(
        candles: List<Candle>,
        kPeriod: Int = 14,
        dPeriod: Int = 3
    ): List<StochPoint?> {
        if (candles.size < kPeriod) return List(candles.size) { null }
        val rawK = MutableList<Double?>(candles.size) { null }
        for (i in (kPeriod - 1) until candles.size) {
            val window = candles.subList(i - kPeriod + 1, i + 1)
            val highest = window.maxOf { it.high }
            val lowest = window.minOf { it.low }
            val span = highest - lowest
            rawK[i] = if (span <= 1e-9) 50.0 else ((candles[i].close - lowest) / span) * 100.0
        }
        val result = MutableList<StochPoint?>(candles.size) { null }
        val validK = mutableListOf<Double>()
        val validIdx = mutableListOf<Int>()
        for (i in rawK.indices) {
            rawK[i]?.let {
                validK.add(it)
                validIdx.add(i)
            }
        }
        val dLine = sma(validK, dPeriod)
        for (j in validIdx.indices) {
            val dVal = dLine[j] ?: continue
            result[validIdx[j]] = StochPoint(k = validK[j], d = dVal)
        }
        return result
    }

    fun williamsR(candles: List<Candle>, period: Int = 14): List<Double?> {
        if (candles.size < period || period <= 0) return List(candles.size) { null }
        val result = MutableList<Double?>(candles.size) { null }
        for (i in (period - 1) until candles.size) {
            val window = candles.subList(i - period + 1, i + 1)
            val highest = window.maxOf { it.high }
            val lowest = window.minOf { it.low }
            val span = highest - lowest
            result[i] = if (span <= 1e-9) -50.0 else ((highest - candles[i].close) / span) * -100.0
        }
        return result
    }

    fun bollingerBands(
        closes: List<Double>,
        period: Int = 20,
        stdDevMult: Double = 2.0
    ): List<BollingerBandPoint?> {
        if (closes.size < period || period <= 1) return List(closes.size) { null }
        val mid = sma(closes, period)
        val result = MutableList<BollingerBandPoint?>(closes.size) { null }
        for (i in (period - 1) until closes.size) {
            val mean = mid[i] ?: continue
            var variance = 0.0
            for (j in (i - period + 1)..i) {
                val diff = closes[j] - mean
                variance += diff * diff
            }
            val stdDev = sqrt(variance / period)
            result[i] = BollingerBandPoint(
                middle = mean,
                upper = mean + stdDevMult * stdDev,
                lower = mean - stdDevMult * stdDev
            )
        }
        return result
    }

    fun atr(candles: List<Candle>, period: Int = 14): List<Double?> {
        if (candles.size < period + 1 || period <= 0) return List(candles.size) { null }
        val tr = MutableList(candles.size) { 0.0 }
        tr[0] = candles[0].high - candles[0].low
        for (i in 1 until candles.size) {
            val c = candles[i]
            val prevClose = candles[i - 1].close
            tr[i] = max(
                c.high - c.low,
                max(abs(c.high - prevClose), abs(c.low - prevClose))
            )
        }
        val result = MutableList<Double?>(candles.size) { null }
        var sum = 0.0
        for (i in 1..period) {
            sum += tr[i]
        }
        var prevAtr = sum / period
        result[period] = prevAtr
        for (i in (period + 1) until candles.size) {
            prevAtr = ((prevAtr * (period - 1)) + tr[i]) / period
            result[i] = prevAtr
        }
        return result
    }

    fun vwap(candles: List<Candle>): List<Double?> {
        if (candles.isEmpty()) return emptyList()
        val result = MutableList<Double?>(candles.size) { null }
        var cumPv = 0.0
        var cumVol = 0.0
        var cumTypical = 0.0
        for (i in candles.indices) {
            val c = candles[i]
            val typical = (c.high + c.low + c.close) / 3.0
            val vol = c.volume.coerceAtLeast(0.0)
            cumPv += typical * vol
            cumVol += vol
            cumTypical += typical
            result[i] = if (cumVol > 0.0) cumPv / cumVol else cumTypical / (i + 1)
        }
        return result
    }

    fun heikinAshi(candles: List<Candle>): List<HeikinAshiCandle> {
        if (candles.isEmpty()) return emptyList()
        val out = ArrayList<HeikinAshiCandle>(candles.size)
        var prevOpen = (candles[0].open + candles[0].close) / 2.0
        var prevClose = (candles[0].open + candles[0].high + candles[0].low + candles[0].close) / 4.0
        out.add(
            HeikinAshiCandle(
                timestamp = candles[0].timestamp,
                open = prevOpen,
                high = max(candles[0].high, max(prevOpen, prevClose)),
                low = min(candles[0].low, min(prevOpen, prevClose)),
                close = prevClose
            )
        )
        for (i in 1 until candles.size) {
            val c = candles[i]
            val haClose = (c.open + c.high + c.low + c.close) / 4.0
            val haOpen = (prevOpen + prevClose) / 2.0
            val haHigh = max(c.high, max(haOpen, haClose))
            val haLow = min(c.low, min(haOpen, haClose))
            out.add(HeikinAshiCandle(c.timestamp, haOpen, haHigh, haLow, haClose))
            prevOpen = haOpen
            prevClose = haClose
        }
        return out
    }

    fun isHammer(c: Candle): Boolean {
        val range = (c.high - c.low).takeIf { it > 0.0 } ?: return false
        val body = abs(c.close - c.open)
        val lowerWick = min(c.open, c.close) - c.low
        val upperWick = c.high - max(c.open, c.close)
        return lowerWick >= body * 1.8 && upperWick <= range * 0.25 && c.close >= c.open - body * 0.1
    }

    fun isShootingStar(c: Candle): Boolean {
        val range = (c.high - c.low).takeIf { it > 0.0 } ?: return false
        val body = abs(c.close - c.open)
        val upperWick = c.high - max(c.open, c.close)
        val lowerWick = min(c.open, c.close) - c.low
        return upperWick >= body * 1.8 && lowerWick <= range * 0.25 && c.close <= c.open + body * 0.1
    }

    fun isBullishEngulfing(prev: Candle, curr: Candle): Boolean {
        val prevRed = prev.close < prev.open
        val currGreen = curr.close > curr.open
        return prevRed && currGreen && curr.open <= prev.close && curr.close >= prev.open
    }

    fun isBearishEngulfing(prev: Candle, curr: Candle): Boolean {
        val prevGreen = prev.close > prev.open
        val currRed = curr.close < curr.open
        return prevGreen && currRed && curr.open >= prev.close && curr.close <= prev.open
    }

    fun isBullishReversalCandle(prev: Candle?, curr: Candle): Boolean {
        val range = (curr.high - curr.low).takeIf { it > 0.0 } ?: return false
        val lowerWick = min(curr.open, curr.close) - curr.low
        val pinBar = lowerWick >= range * 0.45 && curr.close > curr.low + range * 0.55
        return isHammer(curr) || pinBar || (prev != null && isBullishEngulfing(prev, curr))
    }

    fun isBearishReversalCandle(prev: Candle?, curr: Candle): Boolean {
        val range = (curr.high - curr.low).takeIf { it > 0.0 } ?: return false
        val upperWick = curr.high - max(curr.open, curr.close)
        val pinBar = upperWick >= range * 0.45 && curr.close < curr.high - range * 0.55
        return isShootingStar(curr) || pinBar || (prev != null && isBearishEngulfing(prev, curr))
    }

    fun roundPrice(value: Double, reference: BigDecimal): BigDecimal {
        val scale = reference.scale().coerceIn(2, 4)
        val safe = if (value.isNaN() || value.isInfinite()) reference.toDouble() else value
        return BigDecimal.valueOf(safe).setScale(scale, RoundingMode.HALF_UP)
    }
}
