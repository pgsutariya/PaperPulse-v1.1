package com.example

import com.example.domain.analysis.AiRenkoConfig
import com.example.domain.analysis.PullbackSniperConfig
import com.example.domain.analysis.StrategyBacktesterEngine
import com.example.domain.analysis.StrategyCalibrationConfig
import com.example.domain.analysis.VolatilityDashboardEngine
import com.example.domain.analysis.VolatilityRegimeBand
import com.example.domain.model.InstrumentType
import com.example.domain.signal.Candle
import com.example.domain.signal.Timeframe
import com.example.ui.components.GlobalHotkeyGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.math.sin

class VolatilityAndBacktesterTest {

    private fun buildVolatilityWaveCandles(
        count: Int = 140,
        startPrice: Double = 2400.0,
        spikeTail: Boolean = false,
        squeezeTail: Boolean = false
    ): List<Candle> {
        val startEpoch = Instant.parse("2026-05-04T03:45:00Z")
        val candles = ArrayList<Candle>(count)
        var price = startPrice
        for (i in 0 until count) {
            val wave = sin(i * 0.24) * 14.0
            val trend = if (i % 40 < 24) 3.2 else -2.1
            val isTail = i >= count - 18
            val delta = when {
                squeezeTail && isTail -> sin(i * 0.6) * 0.4
                spikeTail && isTail -> (if (i % 2 == 0) 26.0 else -18.0) + trend * 2.5
                else -> trend + wave * 0.35
            }
            val open = price
            val close = (open + delta).coerceAtLeast(100.0)
            val wick = when {
                squeezeTail && isTail -> 0.55
                spikeTail && isTail -> 22.0
                else -> 5.5 + (i % 4) * 1.4
            }
            val high = maxOf(open, close) + wick
            val low = (minOf(open, close) - wick).coerceAtLeast(50.0)
            val volume = if (spikeTail && isTail) 280_000.0 else 95_000.0 + (i % 7) * 12_000.0
            candles += Candle(
                timestamp = startEpoch.plusSeconds(i * 300L),
                open = open,
                high = high,
                low = low,
                close = close,
                volume = volume
            )
            price = close
        }
        return candles
    }

    @Test
    fun volatilityDashboard_computesAtrSpreadAndExtremeExpansion() {
        val candles = buildVolatilityWaveCandles(count = 130, spikeTail = true)
        val snap = VolatilityDashboardEngine.compute(
            symbol = "RELIANCE",
            timeframe = Timeframe.M5,
            candles = candles,
            maxPoints = 90
        )

        assertEquals("RELIANCE", snap.symbol)
        assertTrue("Expected populated volatility points", snap.points.size == 90)
        assertTrue("ATR14 should be positive", snap.latestAtr14 > 0.0)
        assertTrue("Fast ATR7 should exceed slow ATR21 on volatility spike", snap.latestAtr7 > snap.latestAtr21)
        assertTrue("ATR spread should be positive on expansion", snap.latestAtrSpread > 0.0)
        assertTrue("ATR percentile must be within 0..100", snap.atrPercentile in 0..100)
        assertTrue("Realized volatility must be positive", snap.latestRealizedVolPercent > 0.0)
        assertTrue(
            "Expected elevated or extreme regime during tail spike, got ${snap.regime}",
            snap.regime == VolatilityRegimeBand.ELEVATED || snap.regime == VolatilityRegimeBand.EXTREME
        )
        assertTrue(
            "Recommended brick size should widen above 1.0x in elevated/extreme regime",
            snap.recommendedCalibration.renkoConfig.brickSizeMultiplier > 1.0
        )
    }

    @Test
    fun volatilityDashboard_detectsSqueezeAndTightensCalibration() {
        val candles = buildVolatilityWaveCandles(count = 130, squeezeTail = true)
        val snap = VolatilityDashboardEngine.compute(
            symbol = "NIFTY",
            timeframe = Timeframe.M5,
            candles = candles,
            maxPoints = 80
        )

        assertTrue("Expected points", snap.points.isNotEmpty())
        assertTrue(
            "Expected SQUEEZE or LOW volatility regime on compressed tail, got ${snap.regime}",
            snap.regime == VolatilityRegimeBand.SQUEEZE || snap.regime == VolatilityRegimeBand.LOW
        )
        assertTrue(
            "Recommended brick size should tighten below 0.85x in low/squeeze regime",
            snap.recommendedCalibration.renkoConfig.brickSizeMultiplier < 0.85
        )
    }

    @Test
    fun strategyBacktester_evaluatesRenkoAndPullbackSniper() {
        val candles = buildVolatilityWaveCandles(count = 180)
        val tightConfig = StrategyCalibrationConfig(
            renkoConfig = AiRenkoConfig(
                brickSizeMultiplier = 0.65,
                atrSensitivity = 2.0,
                atrPeriod = 14,
                adaptiveMode = true
            ),
            sniperConfig = PullbackSniperConfig(
                minConfidenceScore = 54,
                bandToleranceMultiplier = 1.4
            )
        )
        val wideConfig = StrategyCalibrationConfig(
            renkoConfig = AiRenkoConfig(
                brickSizeMultiplier = 1.6,
                atrSensitivity = 3.8,
                atrPeriod = 14,
                adaptiveMode = false
            ),
            sniperConfig = PullbackSniperConfig(
                minConfidenceScore = 80,
                bandToleranceMultiplier = 0.7
            )
        )

        val tightSnap = StrategyBacktesterEngine.run(
            symbol = "RELIANCE",
            timeframe = Timeframe.M5,
            candles = candles,
            instrumentType = InstrumentType.STOCK,
            calibration = tightConfig
        )
        val wideSnap = StrategyBacktesterEngine.run(
            symbol = "RELIANCE",
            timeframe = Timeframe.M5,
            candles = candles,
            instrumentType = InstrumentType.STOCK,
            calibration = wideConfig
        )

        assertEquals(180, tightSnap.barsAnalyzed)
        assertTrue("Expected AI Renko Supertrend backtest trades", tightSnap.renkoReport.totalTrades > 0)
        assertTrue("Win rate must be 0..100", tightSnap.combinedWinRatePercent in 0.0..100.0)
        assertTrue("Profit factor must be non-negative", tightSnap.combinedProfitFactor >= 0.0)
        assertEquals(100.0, tightSnap.renkoReport.equityCurve.first(), 0.001)
        assertEquals(tightSnap.renkoReport.totalTrades + 1, tightSnap.renkoReport.equityCurve.size)
        assertTrue(
            "Tighter Renko settings should generate at least as many trades as wider settings",
            tightSnap.renkoReport.totalTrades >= wideSnap.renkoReport.totalTrades
        )
    }

    @Test
    fun globalHotkeyGuard_tracksNestedInputFocusAndModalSuppression() {
        val guard = GlobalHotkeyGuard()
        assertFalse(guard.isSuppressed)

        // Extra release must never dip below zero
        guard.releaseInputFocus()
        assertFalse(guard.isSuppressed)

        guard.acquireInputFocus()
        assertTrue(guard.isSuppressed)

        guard.acquireInputFocus()
        guard.releaseInputFocus()
        assertTrue("Still suppressed while one field remains focused", guard.isSuppressed)

        guard.releaseInputFocus()
        assertFalse("Suppressed clears once all fields unfocus", guard.isSuppressed)

        guard.isRouteOrModalSuppressed = true
        assertTrue("Route/modal suppression blocks global hotkeys", guard.isSuppressed)

        guard.isRouteOrModalSuppressed = false
        assertFalse(guard.isSuppressed)
    }
}
