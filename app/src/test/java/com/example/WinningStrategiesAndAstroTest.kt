package com.example

import com.example.data.local.SeedData
import com.example.domain.analysis.AstroTradingEngine
import com.example.domain.analysis.DataDrivenRegime
import com.example.domain.analysis.DataDrivenTradingEngine
import com.example.domain.engine.MarketClock
import com.example.domain.model.InstrumentType
import com.example.domain.model.MarketSegment
import com.example.domain.signal.Candle
import com.example.domain.signal.CompositeSignalEngine
import com.example.domain.signal.SignalContext
import com.example.domain.signal.SignalDirection
import com.example.domain.signal.SignalProbabilityCalculator
import com.example.domain.signal.SignalStrategy
import com.example.domain.signal.Timeframe
import com.example.domain.signal.evaluators.AstroCycleEvaluator
import com.example.domain.signal.evaluators.DataDrivenQuantEvaluator
import com.example.domain.signal.evaluators.DoubleConfirmationEvaluator
import com.example.domain.signal.evaluators.PullbackSniperEvaluator
import com.example.domain.signal.evaluators.StockOptionsMomentumEvaluator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

class WinningStrategiesAndAstroTest {

    private fun steadyUptrendCandles(
        count: Int = 60,
        startPrice: Double = 1000.0,
        step: Double = 3.2,
        baseVol: Double = 10_000.0,
        finalVolBoost: Double = 1.9,
        stepMinutes: Long = 5L
    ): List<Candle> {
        val start = ZonedDateTime.of(LocalDate.of(2026, 11, 20), LocalTime.of(9, 15), MarketClock.IST_ZONE)
        var price = startPrice
        return (0 until count).map { i ->
            val drift = when {
                i == count - 1 -> step * 1.45
                i % 3 == 0 -> -step * 0.72
                else -> step
            }
            val open = price
            val close = (open + drift).coerceAtLeast(1.0)
            val high = maxOf(open, close) + step * 0.45
            val low = minOf(open, close) - step * 0.35
            val vol = if (i == count - 1) baseVol * finalVolBoost else baseVol * (0.92 + (i % 4) * 0.05)
            price = close
            Candle(
                timestamp = start.plusMinutes(i * stepMinutes).toInstant(),
                open = open,
                high = high,
                low = low,
                close = close,
                volume = vol
            )
        }
    }

    @Test
    fun astroTradingEngine_computesValidLunarPhaseNakshatraAndTurningScore() {
        val now = Instant.parse("2026-11-25T10:30:00Z")
        val snap = AstroTradingEngine.compute(now)
        assertTrue("Lunar phase angle must be in 0..360", snap.lunarPhaseAngleDeg in 0.0..360.0)
        assertTrue("Moon illumination must be in 0..100", snap.moonIlluminationPct in 0.0..100.0)
        assertTrue("Synodic day must be in 0..29.6", snap.synodicDay in 0.0..29.6)
        assertTrue("Nakshatra name must be non-blank", snap.nakshatraName.isNotBlank())
        assertTrue("Turning score must be in 25..98", snap.turningPointScore in 25..98)
        assertTrue("Summary must contain Moon info", snap.summaryLine.contains("Moon"))
    }

    @Test
    fun dataDrivenEngineAndEvaluator_detectsHighEdgeTrendAndEmitsHighAccSignal() {
        val candles = steadyUptrendCandles(count = 55, startPrice = 1200.0, step = 4.0, finalVolBoost = 2.1)
        val snap = DataDrivenTradingEngine.analyze(candles)
        assertNotNull(snap)
        assertEquals(1, snap!!.biasDirection)
        assertTrue("Expected Trend/Squeeze regime, got ${snap.regime}", snap.regime != DataDrivenRegime.CHOPPY_NOISE)
        assertTrue("Expected edge score >= 64, got ${snap.statisticalEdgeScore}", snap.statisticalEdgeScore >= 64)

        val ctx = SignalContext(
            symbol = "RELIANCE",
            segment = MarketSegment.NSE,
            candlesByTimeframe = mapOf(Timeframe.M5 to candles),
            currentLtp = BigDecimal.valueOf(candles.last().close),
            now = candles.last().timestamp
        )
        val signal = DataDrivenQuantEvaluator().evaluate(ctx)
        assertNotNull("Expected DataDrivenQuantEvaluator to emit a signal", signal)
        assertEquals(SignalStrategy.DATA_DRIVEN_QUANT, signal!!.strategy)
        assertEquals(SignalDirection.LONG, signal.direction)
        val prob = SignalProbabilityCalculator.estimate(signal)
        assertTrue("Expected Data-Driven signal win probability >= 60%, got $prob%", prob >= 60)
    }

    @Test
    fun doubleConfirmationEvaluator_verifiesHtfAndLtfDualLock() {
        // 35 bars flat base followed by 25 bars accelerating uptrend -> positive expanding MACD + RSI ~65
        val start = ZonedDateTime.of(LocalDate.of(2026, 11, 20), LocalTime.of(9, 15), MarketClock.IST_ZONE)
        val ltf = mutableListOf<Candle>()
        var p = 1500.0
        for (i in 0 until 60) {
            val delta = when {
                i < 35 -> if (i % 2 == 0) 0.8 else -0.8
                i == 59 -> 4.2 // Strong closing breakout candle
                i % 3 == 0 -> -1.4
                else -> 2.0
            }
            val open = p
            val close = p + delta
            val high = maxOf(open, close) + 0.9
            val low = minOf(open, close) - 0.9
            val vol = if (i == 59) 24_000.0 else 10_000.0
            ltf += Candle(start.plusMinutes(i * 5L).toInstant(), open, high, low, close, vol)
            p = close
        }
        val htf = steadyUptrendCandles(count = 55, startPrice = 1400.0, step = 4.5, stepMinutes = 15L)

        val ctx = SignalContext(
            symbol = "HDFCBANK",
            segment = MarketSegment.NSE,
            candlesByTimeframe = mapOf(Timeframe.M5 to ltf, Timeframe.M15 to htf),
            currentLtp = BigDecimal.valueOf(ltf.last().close),
            now = ltf.last().timestamp
        )
        val signal = DoubleConfirmationEvaluator().evaluate(ctx)
        assertNotNull("Expected DoubleConfirmationEvaluator to emit a signal", signal)
        assertEquals(SignalStrategy.DOUBLE_CONFIRMATION, signal!!.strategy)
        assertEquals(SignalDirection.LONG, signal.direction)
        val singleProb = SignalProbabilityCalculator.estimate(signal, concurringSignalCount = 1)
        val consensusProb = SignalProbabilityCalculator.estimate(signal, concurringSignalCount = 3)
        assertTrue("Expected Double Confirmation win prob >= 70%, got $singleProb%", singleProb >= 70)
        assertTrue("Expected multi-strategy consensus bonus", consensusProb > singleProb)
    }

    @Test
    fun pullbackSniperEvaluator_triggersOnGoldenZonePinBarReclaim() {
        val start = ZonedDateTime.of(LocalDate.of(2026, 11, 20), LocalTime.of(9, 15), MarketClock.IST_ZONE)
        val candles = mutableListOf<Candle>()
        var p = 1000.0
        // 55 bars steady uptrend establishing EMA 9 > EMA 21 > EMA 50
        for (i in 0 until 55) {
            val delta = if (i % 4 == 0) -1.5 else 3.2
            val open = p
            val close = p + delta
            candles += Candle(
                start.plusMinutes(i * 5L).toInstant(),
                open, maxOf(open, close) + 1.2, minOf(open, close) - 1.0, close, 12_000.0
            )
            p = close
        }
        // 3 controlled red pullback bars dipping into EMA 9–21 / Fib 38.2–61.8% zone
        for (k in 0 until 3) {
            val open = p
            val close = p - 4.0
            candles += Candle(
                start.plusMinutes((55 + k) * 5L).toInstant(),
                open, open + 0.8, close - 1.0, close, 8_500.0
            )
            p = close
        }
        // Final sniper hammer/reclaim bar: long lower wick into EMA 21 zone, strong green close back above EMA 9
        val sniperOpen = p
        val sniperLow = p - 12.0
        val sniperClose = p + 11.5
        val sniperHigh = sniperClose + 1.0
        candles += Candle(
            start.plusMinutes(58 * 5L).toInstant(),
            sniperOpen, sniperHigh, sniperLow, sniperClose, 18_000.0
        )

        val ctx = SignalContext(
            symbol = "TCS",
            segment = MarketSegment.NSE,
            candlesByTimeframe = mapOf(Timeframe.M5 to candles),
            currentLtp = BigDecimal.valueOf(candles.last().close),
            now = candles.last().timestamp
        )
        val signal = PullbackSniperEvaluator().evaluate(ctx)
        assertNotNull("Expected PullbackSniperEvaluator to emit a sniper signal", signal)
        assertEquals(SignalStrategy.PULLBACK_SNIPER, signal!!.strategy)
        assertEquals(SignalDirection.LONG, signal.direction)
        assertTrue("Pullback Sniper R:R should be >= 2.0", signal.riskRewardRatio >= 2.0)
        val prob = SignalProbabilityCalculator.estimate(signal)
        assertTrue("Expected Pullback Sniper win probability >= 65%, got $prob%", prob >= 65)
    }

    @Test
    fun stockOptionsEvaluator_emitsOnlyForOptionsAndSeedDataHasStockOptions() {
        val stockOpts = SeedData.stockOptionInstruments()
        assertTrue("Expected at least 8 NSE Stock Option contracts", stockOpts.size >= 8)
        assertTrue(stockOpts.all { it.type == InstrumentType.OPTIONS && it.segment == MarketSegment.NSE })

        val optCandles = steadyUptrendCandles(count = 35, startPrice = 22.0, step = 0.55, finalVolBoost = 1.8)
        val optCtx = SignalContext(
            symbol = "RELIANCE26NOV1300CE",
            segment = MarketSegment.NSE,
            candlesByTimeframe = mapOf(Timeframe.M5 to optCandles),
            currentLtp = BigDecimal.valueOf(optCandles.last().close),
            now = optCandles.last().timestamp,
            instrumentType = InstrumentType.OPTIONS
        )
        val optSignal = StockOptionsMomentumEvaluator().evaluate(optCtx)
        assertNotNull("Expected StockOptionsMomentumEvaluator to emit a signal for RELIANCE26NOV1300CE", optSignal)
        assertEquals(SignalStrategy.STOCK_OPTIONS_FLOW, optSignal!!.strategy)
        assertEquals(SignalDirection.LONG, optSignal.direction)
        assertTrue("Expected win probability >= 60%", SignalProbabilityCalculator.estimate(optSignal) >= 60)

        // Must NOT fire on plain equity stocks
        val stockCtx = optCtx.copy(symbol = "RELIANCE", instrumentType = InstrumentType.STOCK)
        assertNull(StockOptionsMomentumEvaluator().evaluate(stockCtx))
    }

    @Test
    fun astroCycleEvaluator_emitsVerifiedSignalWhenAstroAndPriceActionAlign() {
        // Use a Full Moon / New Moon high-turning-score instant with bullish technical alignment
        val candles = steadyUptrendCandles(count = 45, startPrice = 800.0, step = 1.6, finalVolBoost = 1.5, stepMinutes = 15L)
        // Find a timestamp within next 30 days where turningPointScore >= 62 and bias supports LONG
        val baseInstant = Instant.parse("2026-11-09T09:30:00Z") // Near New Moon window
        val astroNow = (0..29).map { baseInstant.plusSeconds(it * 86_400L) }
            .first {
                val s = AstroTradingEngine.compute(it)
                s.turningPointScore >= 60 && s.bias != com.example.domain.analysis.AstroBias.BEARISH_PRESSURE
            }

        val ctx = SignalContext(
            symbol = "SBIN",
            segment = MarketSegment.NSE,
            candlesByTimeframe = mapOf(Timeframe.M15 to candles),
            currentLtp = BigDecimal.valueOf(candles.last().close),
            now = astroNow
        )
        val signal = AstroCycleEvaluator().evaluate(ctx)
        assertNotNull("Expected AstroCycleEvaluator to emit a signal on harmonic/turning window + bullish structure", signal)
        assertEquals(SignalStrategy.ASTRO_CYCLE, signal!!.strategy)
        assertTrue(signal.reasons.any { it.contains("Astro Cycle") })
        assertTrue(signal.reasons.any { it.contains("Sidereal Nakshatra") })
        assertTrue("Expected win probability >= 60%", SignalProbabilityCalculator.estimate(signal) >= 60)

        // Verify CompositeSignalEngine integrates all new winning strategies cleanly
        val composite = CompositeSignalEngine().evaluate(ctx)
        assertTrue(composite.isNotEmpty())
    }

    @Test
    fun astroTrendFilters_gateSignalGenerationAndExposePlanetaryPositions() {
        val now = Instant.parse("2026-11-25T10:30:00Z")
        val snap = AstroTradingEngine.compute(now)
        assertEquals("Expected 7 classical planetary positions", 7, snap.planetaryPositions.size)
        assertTrue(snap.planetaryPositions.all { it.degreeInSign in 0.0..30.0 && it.zodiacSign.isNotBlank() })

        // Pick a date in Waxing Moon phase (< 180 deg) so LONG passes WAXING_BULL and SHORT is blocked
        val waxingDate = (0..29).map { Instant.parse("2026-11-01T10:00:00Z").plusSeconds(it * 86_400L) }
            .first {
                val s = AstroTradingEngine.compute(it)
                s.lunarPhaseAngleDeg in 30.0..150.0 && s.turningPointScore >= 40
            }
        val waxingSnap = AstroTradingEngine.compute(waxingDate)

        val waxingFilter = com.example.domain.analysis.AstroTrendFilterConfig(
            enabled = true,
            lunarCycleFilter = com.example.domain.analysis.LunarCycleFilter.WAXING_BULL,
            alignSignalDirectionWithAstro = false,
            minTurningPointScore = 40
        )
        val longEval = AstroTradingEngine.evaluateFilterMatch(waxingSnap, SignalDirection.LONG, waxingFilter)
        val shortEval = AstroTradingEngine.evaluateFilterMatch(waxingSnap, SignalDirection.SHORT, waxingFilter)
        assertTrue("Expected LONG to pass WAXING_BULL filter", longEval.passes)
        assertTrue("Expected SHORT to be blocked by WAXING_BULL filter", !shortEval.passes)

        // Verify CompositeSignalEngine suppresses signals when Astro filter rejects them (e.g. minTurningPointScore = 99)
        val candles = steadyUptrendCandles(count = 55, startPrice = 1200.0, step = 4.0, finalVolBoost = 2.0)
        val blockedCtx = SignalContext(
            symbol = "RELIANCE",
            segment = MarketSegment.NSE,
            candlesByTimeframe = mapOf(Timeframe.M5 to candles),
            currentLtp = BigDecimal.valueOf(candles.last().close),
            now = waxingDate,
            astroFilterConfig = waxingFilter.copy(minTurningPointScore = 99)
        )
        val blockedSignals = CompositeSignalEngine().evaluate(blockedCtx)
        assertTrue("Expected Astro filter minTurningPointScore=99 to block all signals", blockedSignals.isEmpty())

        // And when Astro filter passes, generated signals include Astro verification note
        val passingCtx = blockedCtx.copy(astroFilterConfig = waxingFilter)
        val passingSignals = CompositeSignalEngine().evaluate(passingCtx)
        assertTrue("Expected passing signals under WAXING_BULL", passingSignals.isNotEmpty())
        assertTrue(
            "Expected passing signals to carry Astro filter annotation",
            passingSignals.all { sig -> sig.reasons.any { it.contains("Astro", ignoreCase = true) } }
        )
    }

    @Test
    fun pullbackSniperModule_scoresVwapAndEmaBandMeanReversionAndEnforcesConfidenceGate() {
        val start = ZonedDateTime.of(LocalDate.of(2026, 11, 20), LocalTime.of(9, 15), MarketClock.IST_ZONE)
        val candles = mutableListOf<Candle>()
        var p = 1000.0
        for (i in 0 until 55) {
            val delta = if (i % 4 == 0) -1.5 else 3.0
            val open = p
            val close = p + delta
            candles += Candle(
                start.plusMinutes(i * 5L).toInstant(),
                open, maxOf(open, close) + 1.2, minOf(open, close) - 1.0, close, 12_000.0
            )
            p = close
        }
        // Pull back to EMA 9/21 & VWAP area
        for (k in 0 until 3) {
            val open = p
            val close = p - 3.8
            candles += Candle(
                start.plusMinutes((55 + k) * 5L).toInstant(),
                open, open + 0.7, close - 1.0, close, 8_000.0
            )
            p = close
        }
        // Sniper rejection bar with long lower wick and high snap-back volume
        candles += Candle(
            start.plusMinutes(58 * 5L).toInstant(),
            p, p + 12.0, p - 13.0, p + 11.0, 21_000.0
        )

        val assessment = com.example.domain.signal.evaluators.PullbackSniperAnalyzer.analyze(
            candles = candles,
            instrumentType = InstrumentType.STOCK,
            minConfidenceScore = 62
        )
        assertNotNull("Expected PullbackSniperAnalyzer to identify high-probability pullback", assessment)
        assertTrue("Expected confidenceScore >= 62, got ${assessment!!.confidenceScore}", assessment.confidenceScore >= 62)
        assertTrue("Expected reasons to embed Confidence Score", assessment.reasons.any { it.contains("Confidence Score:") })
        assertTrue("Expected reasons to mention VWAP band", assessment.reasons.any { it.contains("VWAP") })

        // Verify accuracy target gate suppresses setups below threshold
        val strictReject = com.example.domain.signal.evaluators.PullbackSniperAnalyzer.analyze(
            candles = candles,
            instrumentType = InstrumentType.STOCK,
            minConfidenceScore = 98
        )
        assertNull("Expected strict 98 confidence gate to reject setup", strictReject)
    }
}

