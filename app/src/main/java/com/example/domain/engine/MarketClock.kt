package com.example.domain.engine

import com.example.domain.model.MarketSegment
import com.example.domain.model.ValidityType
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

object MarketClock {
    val IST_ZONE: ZoneId = ZoneId.of("Asia/Kolkata")
    val INTRADAY_SQUARE_OFF_TIME: LocalTime = LocalTime.of(15, 15)
    val SESSION_CLOSE_TIME: LocalTime = LocalTime.of(15, 30)
    val MCX_SESSION_CLOSE_TIME: LocalTime = LocalTime.of(23, 30)
    val MCX_SQUARE_OFF_TIME: LocalTime = LocalTime.of(23, 15)

    private val dateFormatter = DateTimeFormatter.ofPattern("dd MMM yyyy")
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

    fun nowIst(nowMillis: Long = System.currentTimeMillis()): ZonedDateTime {
        return Instant.ofEpochMilli(nowMillis).atZone(IST_ZONE)
    }

    fun isIntradaySquareOffTime(
        segment: MarketSegment,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        if (segment == MarketSegment.CRYPTO) {
            // Crypto trades 24/7, but if user explicitly chooses Intraday MIS on Crypto,
            // align simulated intraday square-off at 23:55 IST or manual trigger
            val time = nowIst(nowMillis).toLocalTime()
            return time >= LocalTime.of(23, 55)
        }
        val ist = nowIst(nowMillis)
        val time = ist.toLocalTime()
        if (segment == MarketSegment.MCX) {
            // MCX runs till 23:30 IST; MIS positions are squared off in the last 15 minutes.
            return time >= MCX_SQUARE_OFF_TIME && time <= MCX_SESSION_CLOSE_TIME
        }
        // NSE/BSE square-off window between 15:15 and 15:30 IST
        return time >= INTRADAY_SQUARE_OFF_TIME && time <= SESSION_CLOSE_TIME
    }

    fun computeExpiryMillis(
        validityType: ValidityType,
        segment: MarketSegment,
        gttDays: Int = 7,
        gtdDateMillis: Long? = null,
        nowMillis: Long = System.currentTimeMillis()
    ): Long {
        val istNow = nowIst(nowMillis)
        return when (validityType) {
            ValidityType.DAY -> {
                val closeTime = when (segment) {
                    MarketSegment.MCX -> MCX_SESSION_CLOSE_TIME
                    MarketSegment.CRYPTO -> LocalTime.of(23, 59, 59)
                    MarketSegment.NSE, MarketSegment.BSE -> SESSION_CLOSE_TIME
                }
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
                val closeTime = when (segment) {
                    MarketSegment.MCX -> MCX_SESSION_CLOSE_TIME
                    MarketSegment.CRYPTO -> LocalTime.of(23, 59, 59)
                    MarketSegment.NSE, MarketSegment.BSE -> SESSION_CLOSE_TIME
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

    fun formatCurrentIstClock(nowMillis: Long = System.currentTimeMillis()): String {
        return "${nowIst(nowMillis).format(timeFormatter)} IST"
    }
}
