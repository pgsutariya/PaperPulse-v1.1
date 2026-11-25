package com.example

import com.example.domain.analysis.MarketAnalyzer
import com.example.domain.model.MarketSegment
import com.example.domain.signal.Candle
import com.example.domain.signal.Timeframe
import com.example.ui.screens.chart.ChartOverlays
import com.example.ui.screens.chart.LowerPane
import com.example.ui.screens.chart.TradingViewChartPayloadBuilder
import com.example.ui.screens.chart.TvChartStyle
import com.example.ui.screens.chart.TvOhlcPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.math.sin

class TradingViewChartPayloadTest {

    private fun sampleCandles(count: Int = 80, includeDuplicateTimestamp: Boolean = false): List<Candle> {
        val baseEpoch = 1_730_000_000L
        val out = mutableListOf<Candle>()
        var price = 2500.0
        for (i in 0 until count) {
            val delta = sin(i / 4.0) * 14.0 + 1.2
            val open = price
            val close = (price + delta).coerceAtLeast(100.0)
            val high = maxOf(open, close) + 6.5
            val low = minOf(open, close) - 5.5
            val ts = Instant.ofEpochSecond(baseEpoch + i * 300L)
            out += Candle(
                timestamp = ts,
                open = open,
                high = high,
                low = low,
                close = close,
                volume = 12_000.0 + (i % 7) * 2_500.0
            )
            if (includeDuplicateTimestamp && i == 10) {
                // Deliberately insert a duplicate timestamp to verify deduplication
                out += Candle(
                    timestamp = ts,
                    open = open,
                    high = high + 2.0,
                    low = low,
                    close = close + 1.0,
                    volume = 18_000.0
                )
            }
            price = close
        }
        return out
    }

    @Test
    fun payloadBuilder_deduplicatesAndSortsTimestampsStrictlyAscending() {
        val candles = sampleCandles(count = 60, includeDuplicateTimestamp = true)
        val analysis = MarketAnalyzer.analyze(
            candles = candles,
            timeframe = Timeframe.M5,
            segment = MarketSegment.NSE,
            higherTimeframe = Timeframe.M15
        )

        val payload = TradingViewChartPayloadBuilder.build(
            symbol = "RELIANCE",
            analysis = analysis,
            overlays = ChartOverlays(ema921 = true, ema50200 = true, vwap = true, vwapBands = true, pivots = true),
            lowerPane = LowerPane.RSI,
            focusSetup = null,
            chartStyle = TvChartStyle.CANDLES,
            logScale = true,
            magnetCrosshair = true
        )

        // 60 unique timestamps despite 61 input candles
        assertEquals(60, payload.candles.size)
        for (i in 1 until payload.candles.size) {
            assertTrue(
                "Candles must be strictly ascending in time",
                payload.candles[i].time > payload.candles[i - 1].time
            )
        }

        // Markers must also be sorted non-decreasing by time
        for (i in 1 until payload.markers.size) {
            assertTrue(
                "Markers must be sorted ascending by time",
                payload.markers[i].time >= payload.markers[i - 1].time
            )
        }

        // Verify overlays, RSI lower pane, and JSON encoding
        assertTrue(payload.overlayLines.any { it.id == "ema9" })
        assertTrue(payload.overlayLines.any { it.id == "ema21" })
        assertTrue(payload.overlayLines.any { it.id == "vwap" })
        assertEquals("RSI", payload.lowerPane.paneKey)
        assertEquals(3, payload.lowerPane.priceLines.size)
        assertTrue(payload.logScale)
        assertTrue(payload.magnetCrosshair)

        val encoded = TradingViewChartPayloadBuilder.toJson(payload)
        assertTrue(encoded.contains("\"symbol\":\"RELIANCE\""))
        assertTrue(encoded.contains("\"chartStyle\":\"candles\""))
    }

    @Test
    fun heikinAshiComputation_producesSmoothedOhlcBounds() {
        val raw = listOf(
            TvOhlcPoint(time = 1000L, open = 100.0, high = 110.0, low = 95.0, close = 105.0, volume = 500.0),
            TvOhlcPoint(time = 1300L, open = 105.0, high = 115.0, low = 102.0, close = 112.0, volume = 800.0)
        )

        val ha = TradingViewChartPayloadBuilder.computeHeikinAshi(raw)
        assertEquals(2, ha.size)

        // Bar 0: haOpen = (100 + 105)/2 = 102.5, haClose = (100+110+95+105)/4 = 102.5
        assertEquals(102.5, ha[0].open, 1e-6)
        assertEquals(102.5, ha[0].close, 1e-6)
        assertEquals(110.0, ha[0].high, 1e-6)
        assertEquals(95.0, ha[0].low, 1e-6)

        // Bar 1: haOpen = (102.5 + 102.5)/2 = 102.5, haClose = (105+115+102+112)/4 = 108.5
        assertEquals(102.5, ha[1].open, 1e-6)
        assertEquals(108.5, ha[1].close, 1e-6)
        assertEquals(115.0, ha[1].high, 1e-6)
        assertEquals(102.0, ha[1].low, 1e-6)
    }
}
