package com.example.domain.engine

import com.example.domain.model.InstrumentType
import com.example.domain.model.MarketSegment
import com.example.domain.model.ValidityType
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

object MarketClock {
    val IST_ZONE: ZoneId = ZoneId.of("Asia/Kolkata")
    val INDIA_MARKET_OPEN_TIME: LocalTime = LocalTime.of(9, 15)
    val INTRADAY_SQUARE_OFF_TIME: LocalTime = LocalTime.of(15, 15)
    val SESSION_CLOSE_TIME: LocalTime = LocalTime.of(15, 30)
    val OPTIONS_SQUARE_OFF_TIME: LocalTime = LocalTime.of(15, 40)
    val OPTIONS_SESSION_CLOSE_TIME: LocalTime = LocalTime.of(15, 45)
    val MCX_OPEN_TIME: LocalTime = LocalTime.of(9, 0)
    val MCX_SESSION_CLOSE_TIME: LocalTime = LocalTime.of(23, 30)
    val MCX_SQUARE_OFF_TIME: LocalTime = LocalTime.of(23, 15)

    private val dateFormatter = DateTimeFormatter.ofPattern("dd MMM yyyy")
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val shortTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    fun nowIst(nowMillis: Long = System.currentTimeMillis()): ZonedDateTime {
        return Instant.ofEpochMilli(nowMillis).atZone(IST_ZONE)
    }

    fun isIntradaySquareOffTime(
        segment: MarketSegment,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean = isIntradaySquareOffTime(segment, InstrumentType.STOCK, nowMillis)

    fun isIntradaySquareOffTime(
        segment: MarketSegment,
        instrumentType: InstrumentType,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        if (segment == MarketSegment.CRYPTO) {
            val time = nowIst(nowMillis).toLocalTime()
            return time >= LocalTime.of(23, 55)
        }
        val ist = nowIst(nowMillis)
        val time = ist.toLocalTime()
        if (segment == MarketSegment.MCX) {
            return time >= MCX_SQUARE_OFF_TIME && time <= MCX_SESSION_CLOSE_TIME
        }
        if (instrumentType == InstrumentType.OPTIONS) {
            // India Options remain tradable until 15:40 IST and auto square-off at 15:40 IST
            return time >= OPTIONS_SQUARE_OFF_TIME && time <= OPTIONS_SESSION_CLOSE_TIME
        }
        // India Equity & Futures square-off at 15:15 IST
        return time >= INTRADAY_SQUARE_OFF_TIME && time <= SESSION_CLOSE_TIME
    }

    fun isMarketSessionOpen(
        segment: MarketSegment,
        instrumentType: InstrumentType = InstrumentType.STOCK,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        if (segment == MarketSegment.CRYPTO) return true
        val ist = nowIst(nowMillis)
        val day = ist.dayOfWeek
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) return false
        val time = ist.toLocalTime()
        return when (segment) {
            MarketSegment.MCX -> !time.isBefore(MCX_OPEN_TIME) && !time.isAfter(MCX_SESSION_CLOSE_TIME)
            MarketSegment.NSE, MarketSegment.BSE -> {
                val close = if (instrumentType == InstrumentType.OPTIONS) {
                    OPTIONS_SQUARE_OFF_TIME
                } else {
                    INTRADAY_SQUARE_OFF_TIME
                }
                !time.isBefore(INDIA_MARKET_OPEN_TIME) && !time.isAfter(close)
            }
            MarketSegment.CRYPTO -> true
        }
    }

    fun intradaySquareOffLabel(segment: MarketSegment, instrumentType: InstrumentType): String {
        return when (segment) {
            MarketSegment.CRYPTO -> "23:55 IST (24/7)"
            MarketSegment.MCX -> "23:15 IST"
            MarketSegment.NSE, MarketSegment.BSE ->
                if (instrumentType == InstrumentType.OPTIONS) "15:40 IST (Options)" else "15:15 IST"
        }
    }

    fun sessionWindowLabel(segment: MarketSegment, instrumentType: InstrumentType): String {
        return when (segment) {
            MarketSegment.CRYPTO -> "24/7 Live"
            MarketSegment.MCX -> "09:00–23:30 IST"
            MarketSegment.NSE, MarketSegment.BSE ->
                if (instrumentType == InstrumentType.OPTIONS) "09:15–15:40 IST" else "09:15–15:15 IST"
        }
    }

    fun computeExpiryMillis(
        validityType: ValidityType,
        segment: MarketSegment,
        gttDays: Int = 7,
        gtdDateMillis: Long? = null,
        nowMillis: Long = System.currentTimeMillis(),
        instrumentType: InstrumentType = InstrumentType.STOCK
    ): Long {
        val istNow = nowIst(nowMillis)
        val closeTime = when (segment) {
            MarketSegment.MCX -> MCX_SESSION_CLOSE_TIME
            MarketSegment.CRYPTO -> LocalTime.of(23, 59, 59)
            MarketSegment.NSE, MarketSegment.BSE ->
                if (instrumentType == InstrumentType.OPTIONS) OPTIONS_SQUARE_OFF_TIME else INTRADAY_SQUARE_OFF_TIME
        }
        return when (validityType) {
            ValidityType.DAY -> {
                var closeZdt = istNow.toLocalDate().atTime(closeTime).atZone(IST_ZONE)
                if (closeZdt.toInstant().toEpochMilli() <= nowMillis) {
                    closeZdt = closeZdt.plusDays(1)
                }
                closeZdt.toInstant().toEpochMilli()
            }
            ValidityType.GTT -> {
                val clampedDays = gttDays.coerceIn(1, 365)
                nowMillis + TimeUnit.DAYS.toMillis(clampedDays.toLong())
            }
            ValidityType.GTD -> {
                val pickedDate: LocalDate = if (gtdDateMillis != null) {
                    Instant.ofEpochMilli(gtdDateMillis).atZone(IST_ZONE).toLocalDate()
                } else {
                    istNow.toLocalDate().plusDays(1)
                }
                pickedDate.atTime(closeTime).atZone(IST_ZONE).toInstant().toEpochMilli()
            }
        }
    }

    fun formatIstDate(millis: Long): String {
        return Instant.ofEpochMilli(millis).atZone(IST_ZONE).format(dateFormatter)
    }

    fun formatIstTime(millis: Long): String {
        return Instant.ofEpochMilli(millis).atZone(IST_ZONE).format(timeFormatter)
    }

    fun formatIstShortTime(millis: Long): String {
        return Instant.ofEpochMilli(millis).atZone(IST_ZONE).format(shortTimeFormatter)
    }

    fun formatCurrentIstClock(nowMillis: Long = System.currentTimeMillis()): String {
        return "${nowIst(nowMillis).format(timeFormatter)} IST"
    }
}
