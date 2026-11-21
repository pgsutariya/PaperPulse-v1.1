package com.example.domain.analysis

import com.example.domain.model.MarketSegment
import com.example.domain.signal.Candle
import com.example.domain.signal.Timeframe
import java.time.Instant
import kotlin.math.max
import kotlin.math.min

/** A higher-timeframe bar together with the index span it covers on the base chart. */
data class HtfCandle(
    val candle: Candle,
    val firstBaseIndex: Int,
    val lastBaseIndex: Int,
    val isComplete: Boolean
)

object CandleResampler {

    /** Aggregates base candles into [target] bars using the exchange calendar. */
    fun resample(candles: List<Candle>, target: Timeframe, segment: MarketSegment?): List<Candle> =
        resampleWithSpans(candles, target, segment, baseTimeframe = null).map { it.candle }

    /**
     * Same as [resample] but keeps the base-index span of every bar, which the chart uses to draw
     * the HTF "shadow" candle behind the lower-timeframe candles.
     */
    fun resampleWithSpans(
        candles: List<Candle>,
        target: Timeframe,
        segment: MarketSegment?,
        baseTimeframe: Timeframe?
    ): List<HtfCandle> {
        if (candles.isEmpty()) return emptyList()
        val out = ArrayList<HtfCandle>()
        var bucket = SessionCalendar.bucketStart(target, candles[0].timestamp.toEpochMilli(), segment)
        var first = 0
        var o = candles[0].open
        var h = candles[0].high
        var l = candles[0].low
        var c = candles[0].close
        var v = candles[0].volume
        var d: Double? = candles[0].delta

        fun flush(lastIdx: Int, complete: Boolean) {
            out.add(
                HtfCandle(
                    candle = Candle(Instant.ofEpochMilli(bucket), o, h, l, c, v, d),
                    firstBaseIndex = first,
                    lastBaseIndex = lastIdx,
                    isComplete = complete
                )
            )
        }

        for (i in 1 until candles.size) {
            val k = candles[i]
            val b = SessionCalendar.bucketStart(target, k.timestamp.toEpochMilli(), segment)
            if (b != bucket) {
                flush(i - 1, true)
                bucket = b
                first = i
                o = k.open; h = k.high; l = k.low; c = k.close; v = k.volume; d = k.delta
            } else {
                h = max(h, k.high)
                l = min(l, k.low)
                c = k.close
                v += k.volume
                val runningDelta = d
                val barDelta = k.delta
                d = if (runningDelta != null && barDelta != null) runningDelta + barDelta else null
            }
        }
        // The last bucket is complete only if the next base bar would start a new HTF bar.
        val lastComplete = if (baseTimeframe == null) {
            false
        } else {
            val lastTs = candles.last().timestamp.toEpochMilli()
            val nextBaseStart = lastTs + baseTimeframe.durationMillis
            SessionCalendar.bucketStart(target, nextBaseStart, segment) != bucket
        }
        flush(candles.lastIndex, lastComplete)
        return out
    }
}
