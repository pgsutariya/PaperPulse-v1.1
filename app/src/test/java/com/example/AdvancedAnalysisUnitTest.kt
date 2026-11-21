package com.example

import com.example.data.signal.CandleAggregator
import com.example.domain.analysis.AdvancedIndicators
import com.example.domain.analysis.CandleResampler
import com.example.domain.analysis.EmaCrossRetestEngine
import com.example.domain.analysis.PivotLevels
import com.example.domain.analysis.PivotPeriod
import com.example.domain.analysis.RiskRewardLadder
import com.example.domain.analysis.SessionCalendar
import com.example.domain.analysis.SetupState
import com.example.domain.analysis.SmcAnalyzer
import com.example.domain.analysis.TradingStyle
import com.example.domain.analysis.ZoneKind
import com.example.domain.engine.MarketClock
import com.example.domain.model.MarketSegment
import com.example.domain.model.PriceTick
import com.example.domain.signal.Candle
import com.example.domain.signal.SignalContext
import com.example.domain.signal.SignalDirection
import com.example.domain.signal.SignalStrategy
import com.example.domain.signal.Timeframe
import com.example.domain.signal.evaluators.EmaCrossRetestEvaluator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class AdvancedAnalysisUnitTest {

    private val ist: ZoneId = ZoneId.of("Asia/Kolkata")
    private val baseTime: Instant = ZonedDateTime.of(2026, 11, 18, 9, 15, 0, 0, ist).toInstant()

    private fun istMillis(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, ist).toInstant().toEpochMilli()

    /**
     * 60-bar decline, 8-bar rally (EMA9 crosses above EMA21 on bar 66, high 107.8), 3-bar pullback into
     * the EMA zone, then a candle that closes above the crossover-candle high (bar 71).
     */
    private fun crossoverRetestBreakFixture(): List<Candle> {
        val out = mutableListOf<Candle>()
        var p = 120.0
        fun add(o: Double, h: Double, l: Double, c: Double) {
            out.add(Candle(baseTime.plusMillis(out.size * Timeframe.M5.durationMillis), o, h, l, c, 1_000.0))
        }
        repeat(60) {
            val nxt = p - 0.33
            add(p, maxOf(p, nxt) + 0.2, minOf(p, nxt) - 0.2, nxt)
            p = nxt
        }
        listOf(0.8, 1.0, 1.2, 1.3, 1.2, 1.0, 0.9, 0.8).forEach { step ->
            val nxt = p + step
            add(p, nxt + 0.2, p - 0.2, nxt)
            p = nxt
        }
        listOf(-1.0, -1.1, -0.6).forEach { step ->
            val nxt = p + step
            add(p, p + 0.1, nxt - 0.4, nxt)
            p = nxt
        }
        val nxt = p + 2.6
        add(p, nxt + 0.3, p - 0.1, nxt)
        return out
    }

    @Test
    fun `standard pivot formulas match P R1 S1 R2 S2 R3 S3 and CPR`() {
        val pv = PivotLevels.fromHlc(PivotPeriod.DAILY, h = 110.0, l = 100.0, c = 105.0)
        assertEquals(105.0, pv.pivot, 1e-9)
        assertEquals(110.0, pv.r1, 1e-9) // 2P − L
        assertEquals(100.0, pv.s1, 1e-9) // 2P − H
        assertEquals(115.0, pv.r2, 1e-9) // P + (H − L)
        assertEquals(95.0, pv.s2, 1e-9) // P − (H − L)
        assertEquals(120.0, pv.r3, 1e-9)
        assertEquals(90.0, pv.s3, 1e-9)
        assertEquals(105.0, pv.cprBottom, 1e-9)
        assertEquals(105.0, pv.cprTop, 1e-9)
    }

    @Test
    fun `risk reward ladder covers 1 to 3 in quarter steps`() {
        val ladder = RiskRewardLadder.build(entry = 100.0, stopLoss = 98.0, direction = SignalDirection.LONG)
        assertEquals(9, ladder.size)
        assertEquals(listOf("1:1", "1:1.25", "1:1.5", "1:1.75", "1:2", "1:2.25", "1:2.5", "1:2.75", "1:3"), ladder.map { it.label })
        assertEquals(102.0, ladder.first().price, 1e-9)
        assertEquals(106.0, ladder.last().price, 1e-9)
        val short = RiskRewardLadder.build(entry = 100.0, stopLoss = 101.0, direction = SignalDirection.SHORT)
        assertEquals(98.0, short.first { it.ratio == 2.0 }.price, 1e-9)
    }

    @Test
    fun `ema 9 21 crossover retest break triggers long with structural stop and ladder`() {
        val candles = crossoverRetestBreakFixture()
        val setup = EmaCrossRetestEngine.latest(candles, TradingStyle.INTRADAY, Timeframe.M5, MarketSegment.NSE)
        assertNotNull(setup)
        setup!!
        assertEquals(SignalDirection.LONG, setup.direction)
        assertEquals(66, setup.crossIndex)
        assertEquals(107.8, setup.triggerLevel, 1e-6)
        assertEquals(SetupState.TRIGGERED, setup.state)
        assertEquals(71, setup.triggerIndex)
        assertEquals(108.3, setup.entry!!, 1e-6)
        val sl = setup.stopLoss!!
        assertTrue("SL must sit below the retest swing low (105.3)", sl < 105.3)
        val risk = setup.entry!! - sl
        assertEquals(9, setup.targets.size)
        assertEquals(setup.entry!! + 2.0 * risk, setup.targets.first { it.ratio == 2.0 }.price, 1e-6)
    }

    @Test
    fun `ema crossover retest is still pending before the break candle`() {
        val candles = crossoverRetestBreakFixture().dropLast(1)
        val setup = EmaCrossRetestEngine.latest(candles, TradingStyle.INTRADAY, Timeframe.M5, MarketSegment.NSE)
        assertNotNull(setup)
        assertEquals(SetupState.AWAITING_BREAK, setup!!.state)
        assertTrue(setup.entry == null)
    }

    @Test
    fun `ema crossover retest evaluator emits signal with 1R and 2R targets`() {
        val candles = crossoverRetestBreakFixture()
        val ctx = SignalContext(
            symbol = "RELIANCE",
            segment = MarketSegment.NSE,
            candlesByTimeframe = mapOf(Timeframe.M5 to candles),
            currentLtp = BigDecimal("108.30"),
            now = candles.last().timestamp
        )
        val signal = EmaCrossRetestEvaluator(TradingStyle.INTRADAY).evaluate(ctx)
        assertNotNull(signal)
        assertEquals(SignalStrategy.EMA_9_21_INTRADAY, signal!!.strategy)
        assertEquals(SignalDirection.LONG, signal.direction)
        assertTrue(signal.stopLoss < signal.entryPrice)
        assertTrue(signal.target1 > signal.entryPrice)
        assertTrue(signal.target2 > signal.target1)
        assertEquals(2.0, signal.riskRewardRatio, 0.05)
    }

    @Test
    fun `session calendar anchors NSE bars at 09_15 IST and calendar months`() {
        val at1005 = istMillis(2026, 11, 18, 10, 5)
        assertEquals(istMillis(2026, 11, 18, 10, 0), SessionCalendar.bucketStart(Timeframe.M45, at1005, MarketSegment.NSE))
        assertEquals(istMillis(2026, 11, 18, 9, 45), SessionCalendar.bucketStart(Timeframe.M30, at1005, MarketSegment.NSE))
        assertEquals(istMillis(2026, 11, 18, 9, 15), SessionCalendar.bucketStart(Timeframe.H1, at1005, MarketSegment.NSE))
        assertEquals(istMillis(2026, 11, 18, 0, 0), SessionCalendar.bucketStart(Timeframe.D1, at1005, MarketSegment.NSE))
        assertEquals(istMillis(2026, 11, 16, 0, 0), SessionCalendar.bucketStart(Timeframe.W1, at1005, MarketSegment.NSE))
        assertEquals(istMillis(2026, 11, 1, 0, 0), SessionCalendar.bucketStart(Timeframe.MN1, at1005, MarketSegment.NSE))
        assertEquals(istMillis(2026, 11, 18, 9, 0), SessionCalendar.bucketStart(Timeframe.H1, istMillis(2026, 11, 18, 9, 40), MarketSegment.MCX))
    }

    @Test
    fun `MCX intraday square-off waits for the evening session close`() {
        assertFalse(MarketClock.isIntradaySquareOffTime(MarketSegment.MCX, istMillis(2026, 11, 18, 15, 20)))
        assertTrue(MarketClock.isIntradaySquareOffTime(MarketSegment.MCX, istMillis(2026, 11, 18, 23, 20)))
        assertTrue(MarketClock.isIntradaySquareOffTime(MarketSegment.NSE, istMillis(2026, 11, 18, 15, 20)))
    }

    @Test
    fun `resampler builds higher timeframe candles with spans`() {
        val base = (0 until 6).map { i ->
            Candle(baseTime.plusMillis(i * Timeframe.M5.durationMillis), 100.0 + i, 101.0 + i, 99.0 + i, 100.5 + i, 10.0)
        }
        val m15 = CandleResampler.resampleWithSpans(base, Timeframe.M15, MarketSegment.NSE, Timeframe.M5)
        assertEquals(2, m15.size)
        assertEquals(0, m15[0].firstBaseIndex)
        assertEquals(2, m15[0].lastBaseIndex)
        assertEquals(100.0, m15[0].candle.open, 1e-9)
        assertEquals(103.0, m15[0].candle.high, 1e-9)
        assertEquals(99.0, m15[0].candle.low, 1e-9)
        assertEquals(102.5, m15[0].candle.close, 1e-9)
        assertEquals(30.0, m15[0].candle.volume, 1e-9)
        assertTrue(m15[1].isComplete)
    }

    @Test
    fun `session vwap resets each trading day and bands bracket vwap`() {
        val day1 = (0 until 3).map { i -> Candle(baseTime.plusMillis(i * 300_000L), 100.0, 101.0, 99.0, 100.0 + i, 1_000.0) }
        val nextDay = baseTime.plusMillis(86_400_000L)
        val day2 = listOf(Candle(nextDay, 200.0, 202.0, 198.0, 200.0, 500.0))
        val vw = AdvancedIndicators.vwapWithBands(day1 + day2, MarketSegment.NSE)
        assertEquals(200.0, vw[3]!!.vwap, 1e-9)
        assertTrue(vw[2]!!.upper(1.0) >= vw[2]!!.vwap && vw[2]!!.lower(1.0) <= vw[2]!!.vwap)
    }

    @Test
    fun `obv elder force and delta follow price direction`() {
        val c = listOf(
            Candle(baseTime, 100.0, 101.0, 99.0, 100.0, 100.0),
            Candle(baseTime.plusMillis(60_000), 100.0, 103.0, 100.0, 103.0, 200.0),
            Candle(baseTime.plusMillis(120_000), 103.0, 103.0, 101.0, 101.0, 50.0)
        )
        assertEquals(listOf(0.0, 200.0, 150.0), AdvancedIndicators.obv(c))
        assertEquals(200.0, AdvancedIndicators.barDelta(c[1]), 1e-9) // close at high → all buying
        assertEquals(-50.0, AdvancedIndicators.barDelta(c[2]), 1e-9) // close at low → all selling
        val withTrueDelta = c[1].copy(delta = -30.0)
        assertEquals(-30.0, AdvancedIndicators.barDelta(withTrueDelta), 1e-9)
        val efi = AdvancedIndicators.elderForceIndex(c, 1)
        assertEquals(600.0, efi[1]!!, 1e-9)
    }

    @Test
    fun `fair value gap and volume profile point of control are detected`() {
        val c = listOf(
            Candle(baseTime, 98.0, 100.0, 97.0, 99.5, 100.0),
            Candle(baseTime.plusMillis(60_000), 99.5, 104.0, 99.0, 103.8, 400.0),
            Candle(baseTime.plusMillis(120_000), 103.8, 105.0, 102.0, 104.5, 150.0)
        )
        val fvg = SmcAnalyzer.fairValueGaps(c, 0.1)
        assertEquals(1, fvg.size)
        assertEquals(ZoneKind.BULLISH_FVG, fvg[0].kind)
        assertEquals(102.0, fvg[0].top, 1e-9)
        assertEquals(100.0, fvg[0].bottom, 1e-9)

        val vp = AdvancedIndicators.volumeProfile(c, 8)
        assertNotNull(vp)
        assertTrue(vp!!.poc in 99.0..104.0)
        assertTrue(vp.valueAreaLow <= vp.poc && vp.valueAreaHigh >= vp.poc)
    }

    @Test
    fun `z score position sizing and kelly are mathematically consistent`() {
        val values = (1..19).map { 100.0 } + listOf(110.0)
        val z = AdvancedIndicators.zScore(values, 20).last()!!
        assertTrue(z > 4.0)
        assertEquals(500, AdvancedIndicators.positionSizeForRisk(1_000_000.0, 1.0, 100.0, 80.0, 1))
        assertEquals(475, AdvancedIndicators.positionSizeForRisk(1_000_000.0, 1.0, 100.0, 79.0, 25))
        assertEquals(0.25, AdvancedIndicators.kellyFraction(0.5, 2.0), 1e-9)
    }

    @Test
    fun `aggregator keeps true aggressor delta from trade side`() {
        val agg = CandleAggregator()
        agg.registerSegment("BTCUSDT", MarketSegment.CRYPTO)
        val t0 = 1_731_900_000_000L / 60_000L * 60_000L
        agg.onTick(PriceTick("BTCUSDT", BigDecimal("100"), t0 + 1_000, tradeQuantity = 2.0, buyerInitiated = true))
        agg.onTick(PriceTick("BTCUSDT", BigDecimal("101"), t0 + 2_000, tradeQuantity = 0.5, buyerInitiated = false))
        val done = agg.onTick(PriceTick("BTCUSDT", BigDecimal("102"), t0 + 61_000, tradeQuantity = 1.0, buyerInitiated = true))
        val m1 = done.first { it.timeframe == Timeframe.M1 }.candle
        assertEquals(2.5, m1.volume, 1e-9)
        assertEquals(1.5, m1.delta!!, 1e-9)
    }
}
