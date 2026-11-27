package com.example

import com.example.domain.analysis.AstroEventCalendarEngine
import com.example.domain.analysis.AstroEventCategory
import com.example.domain.analysis.ChartPatternEngine
import com.example.domain.analysis.ChartPatternType
import com.example.domain.analysis.MarketAnalyzer
import com.example.domain.analysis.PatternBreakoutStatus
import com.example.domain.engine.MarketClock
import com.example.domain.model.InstrumentType
import com.example.domain.model.MarketSegment
import com.example.domain.signal.Candle
import com.example.domain.signal.SignalConfidence
import com.example.domain.signal.SignalDirection
import com.example.domain.signal.SignalOutcome
import com.example.domain.signal.SignalRecord
import com.example.domain.signal.SignalStrategy
import com.example.domain.signal.Timeframe
import com.example.ui.screens.chart.ChartOverlays
import com.example.ui.screens.chart.LowerPane
import com.example.ui.screens.chart.TradingViewChartPayloadBuilder
import com.example.ui.screens.chart.TvChartStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZonedDateTime

class ChartPatternAndAstroCalendarTest {

    private val baseStart = ZonedDateTime.of(
        LocalDate.of(2026, 11, 20),
        LocalTime.of(9, 15),
        MarketClock.IST_ZONE
    )

    private fun candleAt(
        index: Int,
        open: Double,
        high: Double,
        low: Double,
        close: Double,
        volume: Double = 10_000.0
    ): Candle = Candle(
        timestamp = baseStart.plusMinutes(index * 5L).toInstant(),
        open = open,
        high = maxOf(high, open, close),
        low = minOf(low, open, close),
        close = close,
        volume = volume
    )

    @Test
    fun chartPatternEngine_detectsDoubleBottomWithValidBreakoutAndTarget() {
        // Construct a clean W-shaped Double Bottom:
        // 0..15: gentle drift down from 112 -> 104
        // 16..20: trough 1 near 100.0 (at index 18)
        // 21..29: bounce to neckline 109.5 (peak at index 26)
        // 30..36: trough 2 near 100.3 (at index 34) with lighter volume
        // 37..42: rally above neckline 109.5 -> 111.0 with breakout volume
        val closes = listOf(
            112.0, 111.5, 111.0, 110.2, 109.8, 109.0, 108.5, 108.0,
            107.4, 106.8, 106.2, 105.5, 105.0, 104.5, 104.0, 103.2,
            102.0, 101.0, 100.0, 101.2, 102.5, 103.8, 105.0, 106.4,
            107.8, 108.6, 109.5, 108.2, 106.8, 105.4, 104.0, 102.8,
            101.6, 100.8, 100.3, 101.5, 103.0, 104.8, 106.5, 108.0,
            109.2, 110.4, 111.2
        )
        val candles = closes.mapIndexed { i, c ->
            val prev = if (i == 0) c + 0.4 else closes[i - 1]
            val low = if (i == 18) 99.6 else if (i == 34) 99.9 else minOf(prev, c) - 0.5
            val high = if (i == 26) 110.0 else maxOf(prev, c) + 0.5
            val vol = when {
                i == 18 -> 16_000.0
                i == 34 -> 11_000.0
                i >= 41 -> 22_000.0
                else -> 10_000.0
            }
            candleAt(i, prev, high, low, c, vol)
        }

        val patterns = ChartPatternEngine.detect(candles, maxPatterns = 3)
        val dblBottom = patterns.firstOrNull { it.type == ChartPatternType.DOUBLE_BOTTOM }
        assertNotNull("Expected Double Bottom to be detected, found: ${patterns.map { it.type }}", dblBottom)
        assertEquals(SignalDirection.LONG, dblBottom!!.direction)
        assertEquals(PatternBreakoutStatus.BREAKOUT_CONFIRMED, dblBottom.status)
        assertTrue("Target should exceed breakout", dblBottom.targetPrice > dblBottom.breakoutLevel)
        assertTrue("Invalidation should sit below breakout", dblBottom.invalidationPrice < dblBottom.breakoutLevel)
        assertTrue("Confidence should be in 56..95", dblBottom.confidenceScore in 56..95)
    }

    @Test
    fun chartPatternEngine_detectsBullFlagWithParallelConsolidationChannel() {
        val candles = mutableListOf<Candle>()
        // 0..19: calm base around 200
        for (i in 0 until 20) {
            val base = 200.0 + (if (i % 2 == 0) 0.5 else -0.4)
            candles += candleAt(i, base, base + 0.8, base - 0.8, base + 0.1, 8_000.0)
        }
        // 20..29: sharp bullish flagpole from 200 -> 222 on heavy volume
        var p = 200.5
        for (i in 20 until 30) {
            val next = p + 2.15
            candles += candleAt(i, p, next + 0.6, p - 0.3, next, 24_000.0)
            p = next
        }
        // 30..41: tidy 12-bar downward-sloping parallel flag channel (retracing ~28% of pole) on lighter volume
        for (k in 0 until 12) {
            val idx = 30 + k
            val center = 221.0 - k * 0.45
            val wave = if (k % 2 == 0) 1.4 else -1.4
            val open = center - wave * 0.3
            val close = center + wave * 0.3
            candles += candleAt(idx, open, center + 1.8, center - 1.8, close, 9_500.0)
        }

        val patterns = ChartPatternEngine.detect(candles, maxPatterns = 3)
        val bullFlag = patterns.firstOrNull { it.type == ChartPatternType.BULL_FLAG }
        assertNotNull("Expected Bull Flag to be detected, found: ${patterns.map { it.type }}", bullFlag)
        assertEquals(SignalDirection.LONG, bullFlag!!.direction)
        assertNotNull("Bull Flag must include upper channel line", bullFlag.upperLine)
        assertNotNull("Bull Flag must include lower channel line", bullFlag.lowerLine)
        assertTrue("Target must project above breakout", bullFlag.targetPrice > bullFlag.breakoutLevel)
        assertTrue("Confidence should be >= 58", bullFlag.confidenceScore >= 58)
    }

    @Test
    fun chartPatternEngine_detectsFallingWedgeAndSerializesIntoTradingViewPayload() {
        val candles = mutableListOf<Candle>()
        // 0..14: initial steady bars around 500
        for (i in 0 until 15) {
            val c = 500.0 + (if (i % 2 == 0) 1.0 else -1.0)
            candles += candleAt(i, c - 0.4, c + 1.5, c - 1.5, c + 0.4, 15_000.0)
        }
        // 15..36 (22 bars): converging falling wedge — upper slopes -1.3/bar, lower slopes -0.6/bar
        for (k in 0..22) {
            val idx = 15 + k
            val upper = 516.0 - k * 1.30
            val lower = 492.0 - k * 0.55
            val mid = (upper + lower) / 2.0
            val isUpperTouch = k % 2 == 0
            val close = if (k == 22) upper + 1.2 else if (isUpperTouch) upper - 0.6 else lower + 0.6
            val open = mid
            val vol = if (k < 11) 16_000.0 else if (k == 22) 24_000.0 else 9_000.0
            candles += candleAt(idx, open, upper, lower, close, vol)
        }

        val analysis = MarketAnalyzer.analyze(
            candles = candles,
            timeframe = Timeframe.M5,
            segment = MarketSegment.NSE,
            higherTimeframe = null
        )
        val wedge = analysis.chartPatterns.firstOrNull { it.type == ChartPatternType.FALLING_WEDGE }
        assertNotNull("Expected Falling Wedge in MarketAnalyzer.chartPatterns, found: ${analysis.chartPatterns.map { it.type }}", wedge)
        assertEquals(SignalDirection.LONG, wedge!!.direction)
        assertTrue("Breakout should be confirmed on final thrust bar", wedge.status == PatternBreakoutStatus.BREAKOUT_CONFIRMED)

        // Verify TradingView payload exports pattern trendlines, breakout/target price lines, and markers
        val tvPayload = TradingViewChartPayloadBuilder.build(
            symbol = "RELIANCE",
            analysis = analysis,
            overlays = ChartOverlays(chartPatterns = true),
            lowerPane = LowerPane.VOLUME,
            focusSetup = null,
            chartStyle = TvChartStyle.CANDLES,
            logScale = false,
            magnetCrosshair = true
        )
        assertTrue(
            "Expected TradingView overlay lines to contain pattern trendline",
            tvPayload.overlayLines.any { it.id.startsWith("pat_") }
        )
        assertTrue(
            "Expected TradingView priceLines to contain breakout level (BO:)",
            tvPayload.priceLines.any { it.title.startsWith("BO:") }
        )
        assertTrue(
            "Expected TradingView markers to contain pattern badge",
            tvPayload.markers.any { it.text.contains(wedge.type.shortBadge) }
        )
    }

    @Test
    fun astroEventCalendarEngine_scansUpcomingLunarRetrogradeAndAspectEventsAndBuildsMonthGrid() {
        val baseStats = AstroEventCalendarEngine.buildPerformanceStats(emptyList())
        assertEquals(AstroEventCategory.entries.size, baseStats.size)
        assertTrue(baseStats.values.all { !it.usesLiveSample && it.volatilityMultiplier > 1.0 })

        val start = LocalDate.of(2026, 11, 1)
        val end = LocalDate.of(2027, 3, 15) // ~135 days covers multiple lunations + Mercury Rx cycle
        val events = AstroEventCalendarEngine.scanEvents(start, end, baseStats)

        assertTrue("Expected at least 12 astro events over 135 days, got ${events.size}", events.size >= 12)
        assertTrue("Expected Full Moon events", events.any { it.category == AstroEventCategory.FULL_MOON })
        assertTrue("Expected New Moon events", events.any { it.category == AstroEventCategory.NEW_MOON })
        assertTrue("Expected Lunar Quarter events", events.any { it.category == AstroEventCategory.LUNAR_QUARTER })
        assertTrue("Expected Mercury Retrograde station event", events.any { it.category == AstroEventCategory.MERCURY_RETROGRADE })
        assertTrue("Expected Major Planetary Aspect event", events.any { it.category == AstroEventCategory.MAJOR_ASPECT })

        val month = YearMonth.of(2026, 11)
        val monthEvents = events.filter { YearMonth.from(it.date) == month }
        val grid = AstroEventCalendarEngine.buildMonthGrid(
            month = month,
            today = LocalDate.of(2026, 11, 25),
            monthEvents = monthEvents
        )
        assertTrue("Calendar grid must be 35 or 42 cells", grid.size == 35 || grid.size == 42)
        assertEquals("Exactly one cell should be marked today", 1, grid.count { it.isToday })
        assertEquals("Month cell count should match November days", 30, grid.count { it.inCurrentMonth })
    }

    @Test
    fun astroEventCalendarEngine_mapsResolvedSignalsToLivePerformanceStats() {
        // Find an actual Full Moon date from ephemeris scan, then attach 3 resolved SignalRecords on that date
        val baseline = AstroEventCalendarEngine.buildPerformanceStats(emptyList())
        val events = AstroEventCalendarEngine.scanEvents(
            LocalDate.of(2026, 10, 1),
            LocalDate.of(2026, 11, 30),
            baseline
        )
        val fullMoon = events.first { it.category == AstroEventCategory.FULL_MOON }
        val fullMoonMillis = fullMoon.date.atTime(12, 0).atZone(MarketClock.IST_ZONE).toInstant().toEpochMilli()

        val records = listOf(
            sampleRecord("r1", fullMoonMillis, SignalOutcome.HIT_T2, 3.4),
            sampleRecord("r2", fullMoonMillis + 60_000L, SignalOutcome.HIT_T1, 2.1),
            sampleRecord("r3", fullMoonMillis + 120_000L, SignalOutcome.HIT_SL, -1.2)
        )

        val liveStats = AstroEventCalendarEngine.buildPerformanceStats(records)
        val fmStat = liveStats.getValue(AstroEventCategory.FULL_MOON)
        assertTrue("Expected Full Moon stat to switch to live sample", fmStat.usesLiveSample)
        assertEquals(3, fmStat.resolvedSignalCount)
        // 2 wins out of 3 decided = 66.67%
        assertEquals(66.67, fmStat.winRatePercent, 0.2)
        // Avg abs move = (3.4 + 2.1 + 1.2) / 3 = 2.233%
        assertEquals(2.23, fmStat.avgDailyRangePercent, 0.05)

        val newMoonStat = liveStats.getValue(AstroEventCategory.NEW_MOON)
        assertFalse("Unmatched category should stay on baseline", newMoonStat.usesLiveSample)
    }

    private fun sampleRecord(
        id: String,
        resolvedAtMillis: Long,
        outcome: SignalOutcome,
        pnlPct: Double
    ): SignalRecord = SignalRecord(
        id = id,
        symbol = "RELIANCE",
        segment = MarketSegment.NSE,
        instrumentType = InstrumentType.STOCK,
        strategy = SignalStrategy.ASTRO_CYCLE,
        direction = SignalDirection.LONG,
        timeframe = Timeframe.M15,
        entryPrice = BigDecimal("1300.00"),
        stopLoss = BigDecimal("1285.00"),
        target1 = BigDecimal("1325.00"),
        target2 = BigDecimal("1345.00"),
        resolvedPrice = BigDecimal("1330.00"),
        outcome = outcome,
        pnlPercent = pnlPct,
        confidence = SignalConfidence.HIGH,
        generatedAtMillis = resolvedAtMillis - 1_800_000L,
        resolvedAtMillis = resolvedAtMillis
    )
}
