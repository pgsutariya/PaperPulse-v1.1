package com.example.domain.analysis

import com.example.domain.model.MarketSegment
import com.example.domain.signal.Timeframe
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters

/**
 * Exchange-aware session calendar used for candle bucketing, session VWAP resets,
 * pivots (previous session HLC) and ICT kill zones.
 *
 *  - NSE / BSE cash & F&O: 09:15–15:30 IST, Mon–Fri. Intraday bars anchored at 09:15 IST.
 *  - MCX: 09:00–23:30 IST, Mon–Fri. Intraday bars anchored at 09:00 IST.
 *  - CRYPTO: 24x7, UTC day (matches Binance candles).
 */
object SessionCalendar {
    val IST: ZoneId = ZoneId.of("Asia/Kolkata")
    private const val MINUTE = 60_000L

    fun zoneFor(segment: MarketSegment?): ZoneId =
        if (segment == null || segment == MarketSegment.CRYPTO) ZoneOffset.UTC else IST

    fun sessionOpen(segment: MarketSegment?): LocalTime = when (segment) {
        MarketSegment.NSE, MarketSegment.BSE -> LocalTime.of(9, 15)
        MarketSegment.MCX -> LocalTime.of(9, 0)
        else -> LocalTime.MIDNIGHT
    }

    fun sessionClose(segment: MarketSegment?): LocalTime = when (segment) {
        MarketSegment.NSE, MarketSegment.BSE -> LocalTime.of(15, 30)
        MarketSegment.MCX -> LocalTime.of(23, 30)
        else -> LocalTime.of(23, 59, 59)
    }

    /** Offset (ms after UTC midnight) at which intraday bars of this segment are anchored. */
    private fun intradayAnchorMillis(segment: MarketSegment?): Long = when (segment) {
        MarketSegment.NSE, MarketSegment.BSE -> (3 * 60 + 45) * MINUTE // 09:15 IST = 03:45 UTC
        MarketSegment.MCX -> (3 * 60 + 30) * MINUTE // 09:00 IST = 03:30 UTC
        else -> 0L
    }

    /** Calendar-correct start of the bar containing [timestampMillis]. */
    fun bucketStart(tf: Timeframe, timestampMillis: Long, segment: MarketSegment? = null): Long {
        return when (tf) {
            Timeframe.D1 -> {
                val zone = zoneFor(segment)
                Instant.ofEpochMilli(timestampMillis).atZone(zone).toLocalDate()
                    .atStartOfDay(zone).toInstant().toEpochMilli()
            }
            Timeframe.W1 -> {
                val zone = zoneFor(segment)
                Instant.ofEpochMilli(timestampMillis).atZone(zone).toLocalDate()
                    .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    .atStartOfDay(zone).toInstant().toEpochMilli()
            }
            Timeframe.MN1 -> {
                val zone = zoneFor(segment)
                Instant.ofEpochMilli(timestampMillis).atZone(zone).toLocalDate()
                    .withDayOfMonth(1)
                    .atStartOfDay(zone).toInstant().toEpochMilli()
            }
            else -> {
                val dur = tf.durationMillis
                val anchor = intradayAnchorMillis(segment)
                Math.floorDiv(timestampMillis - anchor, dur) * dur + anchor
            }
        }
    }

    /** Trading-day key (epoch day in the segment's zone) used to reset session VWAP / ORB. */
    fun sessionDayKey(timestampMillis: Long, segment: MarketSegment?): Long {
        return Instant.ofEpochMilli(timestampMillis).atZone(zoneFor(segment)).toLocalDate().toEpochDay()
    }

    fun isWeekend(timestampMillis: Long, segment: MarketSegment?): Boolean {
        if (segment == null || segment == MarketSegment.CRYPTO) return false
        val dow = Instant.ofEpochMilli(timestampMillis).atZone(IST).dayOfWeek
        return dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY
    }

    fun isMarketOpen(timestampMillis: Long, segment: MarketSegment?): Boolean {
        if (segment == null || segment == MarketSegment.CRYPTO) return true
        if (isWeekend(timestampMillis, segment)) return false
        val t = Instant.ofEpochMilli(timestampMillis).atZone(IST).toLocalTime()
        return !t.isBefore(sessionOpen(segment)) && !t.isAfter(sessionClose(segment))
    }

    data class KillZone(val name: String, val start: LocalTime, val end: LocalTime, val zone: ZoneId) {
        fun contains(timestampMillis: Long): Boolean {
            val t = Instant.ofEpochMilli(timestampMillis).atZone(zone).toLocalTime()
            return if (start <= end) !t.isBefore(start) && t.isBefore(end) else !t.isBefore(start) || t.isBefore(end)
        }

        val labelIst: String get() = "$name (${start}–${end} ${if (zone == IST) "IST" else "UTC"})"
    }

    /**
     * ICT-style kill zones adapted per market:
     *  - NSE/BSE: Opening drive, Lunch/European open, Closing hour.
     *  - MCX: Morning, London open, New York open (energy/metals react to COMEX/NYMEX).
     *  - Crypto: Asia, London, New York (in UTC).
     */
    fun killZones(segment: MarketSegment?): List<KillZone> = when (segment) {
        MarketSegment.NSE, MarketSegment.BSE -> listOf(
            KillZone("Opening Drive", LocalTime.of(9, 15), LocalTime.of(10, 30), IST),
            KillZone("Europe Open", LocalTime.of(13, 0), LocalTime.of(14, 0), IST),
            KillZone("Closing Hour", LocalTime.of(14, 30), LocalTime.of(15, 15), IST)
        )
        MarketSegment.MCX -> listOf(
            KillZone("Morning", LocalTime.of(9, 0), LocalTime.of(10, 30), IST),
            KillZone("London Open", LocalTime.of(13, 30), LocalTime.of(15, 30), IST),
            KillZone("New York Open", LocalTime.of(18, 30), LocalTime.of(21, 0), IST)
        )
        else -> listOf(
            KillZone("Asia", LocalTime.of(0, 0), LocalTime.of(3, 0), ZoneOffset.UTC),
            KillZone("London", LocalTime.of(7, 0), LocalTime.of(10, 0), ZoneOffset.UTC),
            KillZone("New York", LocalTime.of(12, 0), LocalTime.of(15, 0), ZoneOffset.UTC)
        )
    }

    fun activeKillZone(timestampMillis: Long, segment: MarketSegment?): KillZone? =
        killZones(segment).firstOrNull { it.contains(timestampMillis) }
}
