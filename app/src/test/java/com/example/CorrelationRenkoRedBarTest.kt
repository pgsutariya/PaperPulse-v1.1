package com.example

import com.example.domain.analysis.AiRenkoConfig
import com.example.domain.analysis.AiRenkoSupertrendEngine
import com.example.domain.analysis.RedBarBreakoutStatus
import com.example.domain.analysis.RedBarEventKind
import com.example.domain.analysis.RedBarIndicatorEngine
import com.example.domain.analytics.TradeCorrelationAnalytics
import com.example.domain.engine.MarketClock
import com.example.domain.model.ExitReason
import com.example.domain.model.InstrumentType
import com.example.domain.model.MarketSegment
import com.example.domain.model.OrderSide
import com.example.domain.model.ProductType
import com.example.domain.model.TradeJournalEntry
import com.example.domain.signal.Candle
import com.example.domain.signal.CompositeSignalEngine
import com.example.domain.signal.SignalContext
import com.example.domain.signal.SignalDirection
import com.example.domain.signal.SignalProbabilityCalculator
import com.example.domain.signal.SignalStrategy
import com.example.domain.signal.Timeframe
import com.example.domain.signal.evaluators.AiRenkoSupertrendEvaluator
import com.example.domain.signal.evaluators.RedBarBreakoutEvaluator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

class CorrelationRenkoRedBarTest {

    private fun journalEntry(
        id: String,
        symbol: String,
        netPnl: String,
        dayOffset: Long
    ): TradeJournalEntry {
        val ts = ZonedDateTime.of(
            LocalDate.of(2026, 11, 1).plusDays(dayOffset),
            LocalTime.of(11, 0),
            MarketClock.IST_ZONE
        ).toInstant().toEpochMilli()
        val pnl = BigDecimal(netPnl)
        return TradeJournalEntry(
            id = id,
            symbol = symbol,
            segment = MarketSegment.NSE,
            instrumentType = InstrumentType.STOCK,
            side = OrderSide.BUY,
            productType = ProductType.INTRADAY,
            quantity = 10,
            entryPrice = BigDecimal("1000"),
            exitPrice = BigDecimal("1050"),
            grossPnl = pnl,
            charges = BigDecimal.ZERO,
            netPnl = pnl,
            exitReason = ExitReason.MANUAL_EXIT,
            notes = "",
            openedAtMillis = ts - 600_000L,
            closedAtMillis = ts
        )
    }

    @Test
    fun tradeCorrelationMatrix_detectsPositiveNegativeCorrelationsAndSectorOverExposure() {
        val entries = listOf(
            // Day 0: HDFC +500, ICICI +400, RELIANCE -300
            journalEntry("1", "HDFCBANK", "500", 0),
            journalEntry("2", "ICICIBANK", "400", 0),
            journalEntry("3", "RELIANCE", "-300", 0),
            // Day 1: HDFC -300, ICICI -250, RELIANCE +200
            journalEntry("4", "HDFCBANK", "-300", 1),
            journalEntry("5", "ICICIBANK", "-250", 1),
            journalEntry("6", "RELIANCE", "200", 1),
            // Day 2: HDFC +800, ICICI +650, RELIANCE -500
            journalEntry("7", "HDFCBANK", "800", 2),
            journalEntry("8", "ICICIBANK", "650", 2),
            journalEntry("9", "RELIANCE", "-500", 2)
        )

        val matrix = TradeCorrelationAnalytics.buildCorrelationMatrix(entries)
        assertEquals(3, matrix.symbols.size)

        val hdfcIdx = matrix.symbols.indexOf("HDFCBANK")
        val iciciIdx = matrix.symbols.indexOf("ICICIBANK")
        val relIdx = matrix.symbols.indexOf("RELIANCE")

        val hdfcIcici = matrix.cells[hdfcIdx][iciciIdx].correlation!!
        val hdfcRel = matrix.cells[hdfcIdx][relIdx].correlation!!

        assertTrue("Expected strong positive correlation between HDFCBANK and ICICIBANK, got $hdfcIcici", hdfcIcici > 0.95)
        assertTrue("Expected strong negative correlation between HDFCBANK and RELIANCE, got $hdfcRel", hdfcRel < -0.95)

        // Banking & Finance = 6 of 9 trades (67%) -> over-exposed
        val banking = matrix.sectorExposures.first { it.sector == "Banking & Finance" }
        assertTrue(banking.isOverExposed)
        assertEquals(67, banking.sharePercent)

        assertTrue(matrix.highCorrelationAlerts.any { !it.isHedge && it.sameSector })
        assertTrue(matrix.highCorrelationAlerts.any { it.isHedge })
    }

    @Test
    fun aiRenkoSupertrend_detectsTrendFlipAdaptiveBrickAndAdx() {
        val startZdt = ZonedDateTime.of(LocalDate.of(2026, 11, 10), LocalTime.of(9, 15), MarketClock.IST_ZONE)
        val candles = mutableListOf<Candle>()
        var price = 1000.0

        // 45 bars steady uptrend, then 45 bars sharp downtrend
        for (i in 0 until 90) {
            val step = if (i < 45) 6.0 else -9.5
            val open = price
            val close = (price + step).coerceAtLeast(100.0)
            val high = maxOf(open, close) + 3.0
            val low = minOf(open, close) - 3.0
            candles += Candle(
                timestamp = startZdt.plusMinutes(i * 5L).toInstant(),
                open = open,
                high = high,
                low = low,
                close = close,
                volume = 10_000.0
            )
            price = close
        }

        val snap = AiRenkoSupertrendEngine.compute(candles)
        assertTrue("Adaptive brick size must be positive", snap.currentBrickSize > 0.0)
        assertTrue("Expected Renko bricks formed", snap.totalBricksFormed > 5)
        assertNotNull("Expected ADX(14) value", snap.currentAdx)
        assertTrue("Expected strong trend ADX > 20", (snap.currentAdx ?: 0.0) > 20.0)
        assertFalse("Expected bearish trend after sustained selloff", snap.currentBullish)
        assertNotNull("Expected active trail level", snap.currentTrail)
        assertTrue("Expected at least one bearish flip signal", snap.signals.any { !it.isBullish })
    }

    @Test
    fun aiRenkoConfig_scalesBrickSizeAndAtrSensitivity() {
        val startZdt = ZonedDateTime.of(LocalDate.of(2026, 11, 10), LocalTime.of(9, 15), MarketClock.IST_ZONE)
        val candles = (0 until 60).map { i ->
            val base = 1000.0 + i * 4.5
            Candle(
                timestamp = startZdt.plusMinutes(i * 5L).toInstant(),
                open = base,
                high = base + 8.0,
                low = base - 4.0,
                close = base + 4.0,
                volume = 12_000.0
            )
        }

        val tight = AiRenkoSupertrendEngine.compute(
            candles = candles,
            config = AiRenkoConfig(brickSizeMultiplier = 0.50, atrSensitivity = 1.6, atrPeriod = 10, adaptiveMode = false)
        )
        val wide = AiRenkoSupertrendEngine.compute(
            candles = candles,
            config = AiRenkoConfig(brickSizeMultiplier = 1.80, atrSensitivity = 3.8, atrPeriod = 10, adaptiveMode = false)
        )

        assertTrue("Larger brickSizeMultiplier must produce a larger brick size", wide.currentBrickSize > tight.currentBrickSize * 2.0)
        assertTrue("Larger atrSensitivity must produce a larger Supertrend factor", wide.currentFactor > tight.currentFactor + 1.5)
        assertEquals(1.80, wide.appliedConfig.brickSizeMultiplier, 1e-6)
        assertEquals(3.8, wide.appliedConfig.atrSensitivity, 1e-6)
    }

    @Test
    fun redBarIndicator_locksFirstRedAnd1245RangesAndDetectsBreakoutInsideBarReversalZonesAndVolumeMismatch() {
        val day = LocalDate.of(2026, 11, 12)
        fun istCandle(h: Int, m: Int, o: Double, hi: Double, lo: Double, c: Double): Candle {
            return Candle(
                timestamp = ZonedDateTime.of(day, LocalTime.of(h, m), MarketClock.IST_ZONE).toInstant(),
                open = o,
                high = hi,
                low = lo,
                close = c,
                volume = 5_000.0
            )
        }

        val candles = listOf(
            // 09:15 Green candle
            istCandle(9, 15, 100.0, 104.0, 99.0, 103.5),
            // 09:20 FIRST RED CANDLE (High 105.0, Low 100.0, Mid 102.5) - Bearish engulfing of 09:15 body
            istCandle(9, 20, 104.0, 105.0, 99.5, 99.8),
            // 09:25 Inside bar inside 09:20 (High 104.0 <= 105.0, Low 101.0 >= 99.5)
            istCandle(9, 25, 102.0, 104.0, 101.0, 103.0),
            // 09:30 Breakout above First Red High (105.0)
            istCandle(9, 30, 103.0, 107.0, 102.5, 106.5),
            // 12:45 Midday reference bar (High 108.0, Low 104.0)
            istCandle(12, 45, 106.5, 108.0, 104.0, 105.0),
            // 12:50 Breakout above 12:45 High (108.0)
            istCandle(12, 50, 105.0, 110.5, 105.0, 110.0),
            // 12:55 Gap down open -> GREEN price candle (close 109.0 > open 108.2) yet RED volume bar (close 109.0 < prev close 110.0)
            istCandle(12, 55, 108.2, 109.4, 108.0, 109.0)
        )

        val snap = RedBarIndicatorEngine.compute(
            candles = candles,
            timeframe = Timeframe.M5,
            segment = MarketSegment.NSE
        )

        val firstRed = snap.firstRedRange
        assertNotNull("Expected First Red Bar to be locked", firstRed)
        assertEquals(1, firstRed!!.candleIndex)
        assertEquals(105.0, firstRed.high, 1e-6)
        assertEquals(99.5, firstRed.low, 1e-6)
        assertEquals(102.25, firstRed.midpoint, 1e-6)
        assertEquals(RedBarBreakoutStatus.BREAKOUT_UP, firstRed.status)

        val midday = snap.midday1245Range
        assertNotNull("Expected 12:45 IST Bar to be locked", midday)
        assertEquals(4, midday!!.candleIndex)
        assertEquals(108.0, midday.high, 1e-6)
        assertEquals(104.0, midday.low, 1e-6)
        assertEquals(RedBarBreakoutStatus.BREAKOUT_UP, midday.status)

        assertTrue(snap.breakoutEvents.any { it.kind == RedBarEventKind.FIRST_RED_BREAKOUT })
        assertTrue(snap.breakoutEvents.any { it.kind == RedBarEventKind.MIDDAY_1245_BREAKOUT })
        assertTrue("Expected inside bar at index 2", snap.insideBars.any { it.index == 2 })
        assertTrue("Expected bearish candle highlight on engulfing red bar", snap.bearishHighlightIndices.contains(1))
        assertTrue("Expected bearish reversal zone detected", snap.reversalZones.isNotEmpty())

        // Verify green price candle + red volume bar distinction
        assertFalse("Latest candle is green (close > open)", snap.latestPriceCandleRed)
        assertTrue("Latest volume bar is red (close < prevClose)", snap.latestVolumeBarRed)
        assertTrue("Should detect price vs volume bar mismatch", snap.hasLatestVolumePriceMismatch)
    }

    @Test
    fun aiRenkoAndRedBarEvaluators_generateValidSignalsWithWinProbability() {
        val day = LocalDate.of(2026, 11, 14)
        val startZdt = ZonedDateTime.of(day, LocalTime.of(9, 15), MarketClock.IST_ZONE)

        // Build 60 intraday 5m candles:
        // - Bar 0 green, Bar 1 First Red (high 1010, low 1002), then gentle rise and fresh breakout above 12:45 range
        val candles = mutableListOf<Candle>()
        var price = 1000.0
        for (i in 0 until 55) {
            val zdt = startZdt.plusMinutes(i * 5L)
            val candle = when (i) {
                0 -> Candle(zdt.toInstant(), 1000.0, 1006.0, 999.0, 1005.0, 15_000.0)
                1 -> Candle(zdt.toInstant(), 1005.0, 1010.0, 1001.0, 1002.0, 18_000.0) // First Red Bar
                in 2..50 -> {
                    val o = price.coerceAtLeast(1002.0)
                    val c = o + 1.8
                    price = c
                    Candle(zdt.toInstant(), o, c + 1.2, o - 0.8, c, 12_000.0)
                }
                51 -> { // Consolidation / 12:45-like bar or pullback
                    val o = price
                    val c = o - 3.0
                    price = c
                    Candle(zdt.toInstant(), o, o + 1.0, c - 1.0, c, 14_000.0)
                }
                else -> { // Strong bullish thrust
                    val o = price
                    val c = o + 5.5
                    price = c
                    Candle(zdt.toInstant(), o, c + 1.5, o - 0.5, c, 22_000.0)
                }
            }
            candles += candle
        }

        // Also create a dedicated First-Red breakout tail (18 bars) for RedBarBreakoutEvaluator
        val redBarCandles = mutableListOf<Candle>()
        for (i in 0 until 18) {
            val zdt = startZdt.plusMinutes(i * 5L)
            val c = when {
                i == 0 -> Candle(zdt.toInstant(), 500.0, 504.0, 499.0, 503.0, 10_000.0)
                i == 1 -> Candle(zdt.toInstant(), 503.0, 505.0, 498.0, 499.0, 12_000.0) // First Red (498..505)
                i < 16 -> Candle(zdt.toInstant(), 500.0, 504.0, 499.0, 502.5, 9_000.0) // Inside range
                i == 16 -> Candle(zdt.toInstant(), 502.5, 503.5, 501.0, 503.0, 8_000.0) // Inside bar
                else -> Candle(zdt.toInstant(), 503.0, 509.0, 502.5, 508.5, 20_000.0) // Fresh First Red Breakout!
            }
            redBarCandles += c
        }

        val renkoCtx = SignalContext(
            symbol = "RELIANCE",
            segment = MarketSegment.NSE,
            candlesByTimeframe = mapOf(Timeframe.M5 to candles),
            currentLtp = BigDecimal.valueOf(candles.last().close),
            now = candles.last().timestamp
        )
        val renkoSignal = AiRenkoSupertrendEvaluator().evaluate(renkoCtx)
        assertNotNull("Expected AI Renko Supertrend evaluator to emit a live signal", renkoSignal)
        assertEquals(SignalStrategy.AI_RENKO_SUPERTREND, renkoSignal!!.strategy)
        assertEquals(SignalDirection.LONG, renkoSignal.direction)
        assertTrue("LONG SL must be below entry", renkoSignal.stopLoss < renkoSignal.entryPrice)
        assertTrue("LONG T1 must be above entry", renkoSignal.target1 > renkoSignal.entryPrice)
        assertTrue("LONG T2 must be above T1", renkoSignal.target2 > renkoSignal.target1)
        val renkoProb = SignalProbabilityCalculator.estimate(renkoSignal)
        assertTrue("Win probability must be in 35..88, got $renkoProb", renkoProb in 35..88)

        val redBarCtx = SignalContext(
            symbol = "HDFCBANK",
            segment = MarketSegment.NSE,
            candlesByTimeframe = mapOf(Timeframe.M5 to redBarCandles),
            currentLtp = BigDecimal.valueOf(redBarCandles.last().close),
            now = redBarCandles.last().timestamp
        )
        val redBarSignal = RedBarBreakoutEvaluator().evaluate(redBarCtx)
        assertNotNull("Expected Red Bar evaluator to emit a live breakout signal", redBarSignal)
        assertEquals(SignalStrategy.RED_BAR_BREAKOUT, redBarSignal!!.strategy)
        assertEquals(SignalDirection.LONG, redBarSignal.direction)
        assertTrue("LONG SL must be below entry", redBarSignal.stopLoss < redBarSignal.entryPrice)
        assertTrue("LONG T1 must be above entry", redBarSignal.target1 > redBarSignal.entryPrice)
        val redBarProb = SignalProbabilityCalculator.estimate(redBarSignal)
        assertTrue("Win probability must be in 35..88, got $redBarProb", redBarProb in 35..88)

        // Verify CompositeSignalEngine includes both new evaluators
        val compositeSignals = CompositeSignalEngine().evaluate(renkoCtx)
        assertTrue(
            "Expected CompositeSignalEngine to include AI_RENKO_SUPERTREND",
            compositeSignals.any { it.strategy == SignalStrategy.AI_RENKO_SUPERTREND }
        )
        val compositeRedSignals = CompositeSignalEngine().evaluate(redBarCtx)
        assertTrue(
            "Expected CompositeSignalEngine to include RED_BAR_BREAKOUT",
            compositeRedSignals.any { it.strategy == SignalStrategy.RED_BAR_BREAKOUT }
        )
    }
}

