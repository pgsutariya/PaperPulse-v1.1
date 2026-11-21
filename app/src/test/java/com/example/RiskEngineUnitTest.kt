package com.example

import com.example.domain.engine.MarketClock
import com.example.domain.engine.RiskMath
import com.example.domain.model.MarketSegment
import com.example.domain.model.OrderSide
import com.example.domain.model.RiskInputMode
import com.example.domain.model.TrailMode
import com.example.domain.model.ValidityType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.ZonedDateTime

class RiskEngineUnitTest {

    private val tick = BigDecimal("0.05")

    @Test
    fun `trailing SL ratchets favourably for long and never retraces`() {
        val initialSl = BigDecimal("980.00")
        // Price moves up to 1020 with 10 absolute trail -> SL moves up to 1010
        val up1 = RiskMath.calculateTrailingSl(
            currentSl = initialSl,
            ltp = BigDecimal("1020.00"),
            isLong = true,
            trailMode = TrailMode.ABSOLUTE,
            trailValue = BigDecimal("10.00"),
            tickSize = tick
        )
        assertEquals(BigDecimal("1010.00"), up1)

        // Price retraces down to 1012 -> SL must stay at 1010.00, NOT move down to 1002.00
        val retrace = RiskMath.calculateTrailingSl(
            currentSl = up1,
            ltp = BigDecimal("1012.00"),
            isLong = true,
            trailMode = TrailMode.ABSOLUTE,
            trailValue = BigDecimal("10.00"),
            tickSize = tick
        )
        assertEquals(BigDecimal("1010.00"), retrace)
    }

    @Test
    fun `trailing SL ratchets favourably for short and never moves up`() {
        val initialSl = BigDecimal("1020.00")
        val down1 = RiskMath.calculateTrailingSl(
            currentSl = initialSl,
            ltp = BigDecimal("980.00"),
            isLong = false,
            trailMode = TrailMode.ABSOLUTE,
            trailValue = BigDecimal("10.00"),
            tickSize = tick
        )
        assertEquals(BigDecimal("990.00"), down1)

        // Price bounces to 985 -> SL must stay 990.00
        val bounce = RiskMath.calculateTrailingSl(
            currentSl = down1,
            ltp = BigDecimal("985.00"),
            isLong = false,
            trailMode = TrailMode.ABSOLUTE,
            trailValue = BigDecimal("10.00"),
            tickSize = tick
        )
        assertEquals(BigDecimal("990.00"), bounce)
    }

    @Test
    fun `trailing target arms at target without exiting and exits on pullback`() {
        val initialTarget = BigDecimal("1050.00")
        // Price hits initial target 1050 with 10 trail -> arms at 1040, does NOT exit immediately
        val armed = RiskMath.evaluateTrailingTarget(
            ltp = BigDecimal("1050.00"),
            initialTarget = initialTarget,
            isLong = true,
            alreadyActivated = false,
            currentPeak = null,
            currentExitTrigger = null,
            trailMode = TrailMode.ABSOLUTE,
            trailValue = BigDecimal("10.00"),
            tickSize = tick
        )
        assertTrue(armed.activated)
        assertFalse(armed.shouldExitNow)
        assertEquals(BigDecimal("1040.00"), armed.exitTrigger)

        // Price continues to 1070 -> exit trigger ratchets up to 1060
        val ratcheted = RiskMath.evaluateTrailingTarget(
            ltp = BigDecimal("1070.00"),
            initialTarget = initialTarget,
            isLong = true,
            alreadyActivated = armed.activated,
            currentPeak = armed.peakPrice,
            currentExitTrigger = armed.exitTrigger,
            trailMode = TrailMode.ABSOLUTE,
            trailValue = BigDecimal("10.00"),
            tickSize = tick
        )
        assertFalse(ratcheted.shouldExitNow)
        assertEquals(BigDecimal("1060.00"), ratcheted.exitTrigger)

        // Price pulls back to 1059 -> triggers exit!
        val exited = RiskMath.evaluateTrailingTarget(
            ltp = BigDecimal("1059.00"),
            initialTarget = initialTarget,
            isLong = true,
            alreadyActivated = ratcheted.activated,
            currentPeak = ratcheted.peakPrice,
            currentExitTrigger = ratcheted.exitTrigger,
            trailMode = TrailMode.ABSOLUTE,
            trailValue = BigDecimal("10.00"),
            tickSize = tick
        )
        assertTrue(exited.shouldExitNow)
    }

    @Test
    fun `slippage and percentage SL resolution use BigDecimal accurately`() {
        val slippedBuy = RiskMath.applySlippage(BigDecimal("1000.00"), OrderSide.BUY, 10, tick)
        assertEquals(BigDecimal("1001.00"), slippedBuy)

        val slPrice = RiskMath.resolveStopLossPrice(
            entryPrice = BigDecimal("1000.00"),
            side = OrderSide.BUY,
            mode = RiskInputMode.PERCENT,
            value = BigDecimal("2.0"),
            tickSize = tick
        )
        assertEquals(BigDecimal("980.00"), slPrice)
    }

    @Test
    fun `intraday square-off window detects 15_15 IST`() {
        val beforeSquareOff = ZonedDateTime.of(2026, 11, 18, 15, 14, 0, 0, MarketClock.IST_ZONE)
            .toInstant().toEpochMilli()
        val atSquareOff = ZonedDateTime.of(2026, 11, 18, 15, 15, 0, 0, MarketClock.IST_ZONE)
            .toInstant().toEpochMilli()

        assertFalse(MarketClock.isIntradaySquareOffTime(MarketSegment.NSE, beforeSquareOff))
        assertTrue(MarketClock.isIntradaySquareOffTime(MarketSegment.NSE, atSquareOff))
    }

    @Test
    fun `gtt expiry extends N days across sessions`() {
        val now = ZonedDateTime.of(2026, 11, 18, 10, 0, 0, 0, MarketClock.IST_ZONE)
            .toInstant().toEpochMilli()
        val expiry = MarketClock.computeExpiryMillis(ValidityType.GTT, MarketSegment.NSE, gttDays = 7, nowMillis = now)
        val diffDays = (expiry - now) / 86_400_000L
        assertEquals(7L, diffDays)
    }
}
