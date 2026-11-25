package com.example.domain.analysis

import com.example.domain.engine.MarketClock
import com.example.domain.model.MarketSegment
import com.example.domain.signal.Candle
import com.example.domain.signal.Timeframe
import java.time.Instant

enum class RedBarBreakoutStatus(val label: String) {
    BREAKOUT_UP("Breakout Above High ↑"),
    BREAKDOWN_DOWN("Breakdown Below Low ↓"),
    INSIDE_RANGE("Consolidating Inside Range")
}

data class LockedRedBarRange(
    val label: String,
    val candleIndex: Int,
    val timestampMillis: Long,
    val high: Double,
    val low: Double,
    val midpoint: Double,
    val status: RedBarBreakoutStatus,
    val breakoutIndex: Int? = null,
    val breakoutPrice: Double? = null
) {
    val rangePoints: Double get() = (high - low).coerceAtLeast(0.0)
}

enum class RedBarEventKind(val shortLabel: String, val bullish: Boolean) {
    FIRST_RED_BREAKOUT("1stR BO↑", true),
    FIRST_RED_BREAKDOWN("1stR BD↓", false),
    MIDDAY_1245_BREAKOUT("12:45 BO↑", true),
    MIDDAY_1245_BREAKDOWN("12:45 BD↓", false)
}

data class RedBarEvent(
    val index: Int,
    val price: Double,
    val kind: RedBarEventKind
)

data class InsideBarEvent(
    val index: Int,
    val high: Double,
    val low: Double,
    val close: Double,
    val isRed: Boolean
)

data class VolumeBarDivergence(
    val index: Int,
    val close: Double,
    val priceCandleIsRed: Boolean,
    val volumeBarIsRed: Boolean
) {
    val description: String
        get() = if (!priceCandleIsRed && volumeBarIsRed) {
            "Green price candle + Red volume bar (close < prev close)"
        } else {
            "Red price candle + Green volume bar (close > prev close)"
        }
}

data class RedBarReversalZone(
    val originIndex: Int,
    val top: Double,
    val bottom: Double,
    val label: String,
    val isBearishResistance: Boolean
)

data class RedBarSnapshot(
    val firstRedRange: LockedRedBarRange? = null,
    val midday1245Range: LockedRedBarRange? = null,
    val breakoutEvents: List<RedBarEvent> = emptyList(),
    val insideBars: List<InsideBarEvent> = emptyList(),
    val volumePriceDivergences: List<VolumeBarDivergence> = emptyList(),
    val bearishHighlightIndices: List<Int> = emptyList(),
    val reversalZones: List<RedBarReversalZone> = emptyList(),
    val latestPriceCandleRed: Boolean = false,
    val latestVolumeBarRed: Boolean = false,
    val latestInsideBar: InsideBarEvent? = null
) {
    val hasLatestVolumePriceMismatch: Boolean
        get() = latestPriceCandleRed != latestVolumeBarRed
}

/**
 * Red Bar Indicator (First Red Candle + 12:45 IST Midday Breakout + Inside Bar & Red Volume Theory).
 *
 * Implements:
 * 1. First Red Candle after session open (09:15 IST for NSE/BSE/MCX; 00:00 IST for Crypto) with
 *    locked High, Low, 50% Retracement Midpoint, and Breakout/Breakdown tracking.
 * 2. 12:45 IST Midday Reference Candle range for afternoon breakout/breakdown setups.
 * 3. Red Volume Bar (close < prevClose) vs Red Price Candle (close < open) divergence tracking.
 * 4. Green & Red Inside Bar detection for consolidation breakouts.
 */
object RedBarIndicatorEngine {

    fun compute(
        candles: List<Candle>,
        timeframe: Timeframe,
        segment: MarketSegment?
    ): RedBarSnapshot {
        if (candles.size < 3) return RedBarSnapshot()

        val lastIndex = candles.lastIndex
        val lastCandle = candles[lastIndex]
        val prevCandle = candles[lastIndex - 1]
        val latestPriceRed = lastCandle.close < lastCandle.open
        val latestVolRed = lastCandle.close < prevCandle.close

        // Identify the latest trading session date in IST
        val latestSessionDate = Instant.ofEpochMilli(lastCandle.timestamp.toEpochMilli())
            .atZone(MarketClock.IST_ZONE)
            .toLocalDate()

        val sessionIndices = mutableListOf<Int>()
        for (i in candles.indices) {
            val zdt = Instant.ofEpochMilli(candles[i].timestamp.toEpochMilli())
                .atZone(MarketClock.IST_ZONE)
            if (zdt.toLocalDate() == latestSessionDate) {
                sessionIndices.add(i)
            }
        }
        // Fallback for daily/weekly timeframes where latest session has only 1 candle: use last 20 candles
        val activeIndices = if (sessionIndices.size >= 3) sessionIndices else (maxOf(0, candles.size - 20) until candles.size).toList()

        val isIndianMarket = segment != MarketSegment.CRYPTO
        val openMinuteOfDay = if (isIndianMarket) 9 * 60 + 15 else 0
        val middayMinuteOfDay = 12 * 60 + 45

        // 1. First Red Candle after market/session open
        val firstRedIndex = activeIndices.firstOrNull { idx ->
            val c = candles[idx]
            val zdt = Instant.ofEpochMilli(c.timestamp.toEpochMilli()).atZone(MarketClock.IST_ZONE)
            val minuteOfDay = zdt.hour * 60 + zdt.minute
            val afterOpen = !timeframe.isIntraday || minuteOfDay >= openMinuteOfDay
            afterOpen && c.close < c.open
        } ?: activeIndices.firstOrNull { candles[it].close < candles[it].open }

        val breakoutEvents = mutableListOf<RedBarEvent>()

        val firstRedRange = firstRedIndex?.let { refIdx ->
            val ref = candles[refIdx]
            val locked = evaluateLockedRange(
                label = "First Red Bar",
                refIndex = refIdx,
                refCandle = ref,
                candles = candles,
                upEventKind = RedBarEventKind.FIRST_RED_BREAKOUT,
                downEventKind = RedBarEventKind.FIRST_RED_BREAKDOWN,
                eventsOut = breakoutEvents
            )
            locked
        }

        // 2. 12:45 IST Midday Reference Candle (intraday timeframes)
        val middayIndex = if (timeframe.isIntraday) {
            activeIndices.firstOrNull { idx ->
                val zdt = Instant.ofEpochMilli(candles[idx].timestamp.toEpochMilli()).atZone(MarketClock.IST_ZONE)
                val minuteOfDay = zdt.hour * 60 + zdt.minute
                minuteOfDay in middayMinuteOfDay..(middayMinuteOfDay + 30)
            }
        } else {
            null
        }

        val midday1245Range = middayIndex?.let { refIdx ->
            val ref = candles[refIdx]
            evaluateLockedRange(
                label = "12:45 IST Bar",
                refIndex = refIdx,
                refCandle = ref,
                candles = candles,
                upEventKind = RedBarEventKind.MIDDAY_1245_BREAKOUT,
                downEventKind = RedBarEventKind.MIDDAY_1245_BREAKDOWN,
                eventsOut = breakoutEvents
            )
        }

        // 3. Inside Bars (recent 80 bars), Red Volume vs Price divergences, Bearish Highlights & Reversal Zones
        val scanStart = maxOf(1, candles.size - 80)
        val insideBars = mutableListOf<InsideBarEvent>()
        val volDivergences = mutableListOf<VolumeBarDivergence>()
        val bearishHighlights = mutableListOf<Int>()
        val reversalZones = mutableListOf<RedBarReversalZone>()

        val avgBody = (scanStart until candles.size)
            .map { kotlin.math.abs(candles[it].close - candles[it].open) }
            .average()
            .takeIf { it.isFinite() && it > 0.0 } ?: 1.0

        for (i in scanStart until candles.size) {
            val curr = candles[i]
            val prev = candles[i - 1]
            if (curr.high <= prev.high && curr.low >= prev.low) {
                insideBars += InsideBarEvent(
                    index = i,
                    high = curr.high,
                    low = curr.low,
                    close = curr.close,
                    isRed = curr.close < curr.open
                )
            }
            val priceRed = curr.close < curr.open
            val volRed = curr.close < prev.close
            if (priceRed != volRed) {
                volDivergences += VolumeBarDivergence(
                    index = i,
                    close = curr.close,
                    priceCandleIsRed = priceRed,
                    volumeBarIsRed = volRed
                )
            }

            // Highlight strong bearish price-action bars & potential supply/reversal zones
            val range = (curr.high - curr.low).coerceAtLeast(1e-9)
            val body = kotlin.math.abs(curr.close - curr.open)
            val isStrongRed = priceRed && body >= avgBody * 1.35 && (body / range) >= 0.55
            val isBearishEngulf = priceRed && prev.close > prev.open && curr.open >= prev.close && curr.close <= prev.open
            val upperWick = curr.high - maxOf(curr.open, curr.close)
            val isShootingRejection = priceRed && upperWick >= range * 0.42

            if (isStrongRed || isBearishEngulf || isShootingRejection) {
                bearishHighlights += i
                if (isBearishEngulf || isShootingRejection) {
                    reversalZones += RedBarReversalZone(
                        originIndex = i,
                        top = curr.high,
                        bottom = maxOf(curr.open, (curr.high + curr.low) / 2.0),
                        label = if (isBearishEngulf) "Red Engulf Rev" else "Red Rejection Zone",
                        isBearishResistance = true
                    )
                }
            }
        }

        return RedBarSnapshot(
            firstRedRange = firstRedRange,
            midday1245Range = midday1245Range,
            breakoutEvents = breakoutEvents.sortedBy { it.index },
            insideBars = insideBars.takeLast(12),
            volumePriceDivergences = volDivergences.takeLast(10),
            bearishHighlightIndices = bearishHighlights.takeLast(18),
            reversalZones = reversalZones.takeLast(4),
            latestPriceCandleRed = latestPriceRed,
            latestVolumeBarRed = latestVolRed,
            latestInsideBar = insideBars.lastOrNull()
        )
    }

    private fun evaluateLockedRange(
        label: String,
        refIndex: Int,
        refCandle: Candle,
        candles: List<Candle>,
        upEventKind: RedBarEventKind,
        downEventKind: RedBarEventKind,
        eventsOut: MutableList<RedBarEvent>
    ): LockedRedBarRange {
        val hi = refCandle.high
        val lo = refCandle.low
        val mid = (hi + lo) / 2.0

        var firstBreakIdx: Int? = null
        var firstBreakPrice: Double? = null

        for (i in (refIndex + 1) until candles.size) {
            val c = candles[i]
            if (c.close > hi) {
                firstBreakIdx = i
                firstBreakPrice = c.close
                eventsOut += RedBarEvent(index = i, price = c.close, kind = upEventKind)
                break
            } else if (c.close < lo) {
                firstBreakIdx = i
                firstBreakPrice = c.close
                eventsOut += RedBarEvent(index = i, price = c.close, kind = downEventKind)
                break
            }
        }

        val lastClose = candles.last().close
        val status = when {
            lastClose > hi -> RedBarBreakoutStatus.BREAKOUT_UP
            lastClose < lo -> RedBarBreakoutStatus.BREAKDOWN_DOWN
            else -> RedBarBreakoutStatus.INSIDE_RANGE
        }

        return LockedRedBarRange(
            label = label,
            candleIndex = refIndex,
            timestampMillis = refCandle.timestamp.toEpochMilli(),
            high = hi,
            low = lo,
            midpoint = mid,
            status = status,
            breakoutIndex = firstBreakIdx,
            breakoutPrice = firstBreakPrice
        )
    }
}
