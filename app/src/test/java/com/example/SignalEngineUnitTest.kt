package com.example

import com.example.data.signal.CandleAggregator
import com.example.domain.model.MarketSegment
import com.example.domain.model.PriceTick
import com.example.domain.signal.Candle
import com.example.domain.signal.CompositeSignalEngine
import com.example.domain.signal.SignalConfidence
import com.example.domain.signal.SignalContext
import com.example.domain.signal.SignalDirection
import com.example.domain.signal.SignalStrategy
import com.example.domain.signal.StrategyEvaluator
import com.example.domain.signal.Timeframe
import com.example.domain.signal.TradeSignal
import com.example.domain.signal.evaluators.IntradayOrbVwapEvaluator
import com.example.domain.signal.evaluators.ReversalConfluenceEvaluator
import com.example.domain.signal.evaluators.ScalpStrategyEvaluator
import com.example.domain.signal.evaluators.SwigHybridEvaluator
import com.example.domain.signal.evaluators.SwingHeikinAshiEvaluator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class SignalEngineUnitTest {

    private val baseTime: Instant = ZonedDateTime.of(2026, 11, 18, 9, 15, 0, 0, ZoneId.of("Asia/Kolkata")).toInstant()

    @Test
    fun `scalping evaluator fires on clear EMA9 EMA21 crossover with EMA50 bias and momentum`() {
        val evaluator = ScalpStrategyEvaluator()
        val candles = mutableListOf<Candle>()
        // 50 steady bars sloping gently up around 100..108, then slight dip to put EMA9 below EMA21, then sharp bounce
        for (i in 0 until 54) {
            val close = when {
                i < 44 -> 100.0 + i * 0.25
                i in 44..51 -> 111.0 - (i - 43) * 0.65 // pull EMA9 below EMA21 around 105.8
                i == 52 -> 106.0
                else -> 115.5 // sharp crossover push above EMA21 and EMA50 with RSI & Stoch bullish
            }
            val open = if (i == 0) close else candles[i - 1].close
            candles.add(
                Candle(
                    timestamp = baseTime.plusMillis(i * Timeframe.M5.durationMillis),
                    open = open,
                    high = maxOf(open, close) + 0.5,
                    low = minOf(open, close) - 0.5,
                    close = close,
                    volume = 10_000.0
                )
            )
        }

        val ctx = SignalContext(
            symbol = "RELIANCE",
            segment = MarketSegment.NSE,
            candlesByTimeframe = mapOf(Timeframe.M5 to candles),
            currentLtp = BigDecimal("115.50"),
            now = candles.last().timestamp
        )

        val signal = evaluator.evaluate(ctx)
        assertNotNull("Expected SCALP LONG signal on EMA9/21 bull crossover", signal)
        assertEquals(SignalStrategy.SCALP, signal!!.strategy)
        assertEquals(SignalDirection.LONG, signal.direction)
        assertTrue(signal.stopLoss < signal.entryPrice)
        assertTrue(signal.target1 > signal.entryPrice)
        assertTrue(signal.target2 > signal.target1)
        assertTrue(signal.riskRewardRatio > 0.0)
    }

    @Test
    fun `intraday ORB VWAP evaluator fires on opening range breakout plus retest and RSI filter`() {
        val evaluator = IntradayOrbVwapEvaluator()
        val candles = mutableListOf<Candle>()
        // 15m session starting 09:15 IST: bar 0 establishes Opening Range [99.0 .. 101.0]
        candles.add(Candle(baseTime, open = 100.0, high = 101.0, low = 99.0, close = 100.4, volume = 25_000.0))
        // Bars 1..18 build steady above VWAP & break out above ORB High (101.0)
        for (i in 1..18) {
            val ts = baseTime.plusMillis(i * Timeframe.M15.durationMillis)
            val c = when {
                i < 15 -> Candle(ts, 100.4 + i * 0.08, 101.1 + i * 0.09, 100.2 + i * 0.05, 100.6 + i * 0.10, 18_000.0)
                i in 15..17 -> Candle(ts, 102.0 + (i - 15) * 0.5, 102.8 + (i - 15) * 0.5, 101.8, 102.4 + (i - 15) * 0.5, 22_000.0)
                else -> // Bar 18: retests ORB High (~101.0) and closes strongly higher with high RSI & above VWAP
                    Candle(ts, open = 102.0, high = 104.8, low = 100.9, close = 104.5, volume = 32_000.0)
            }
            candles.add(c)
        }

        val ctx = SignalContext(
            symbol = "TCS",
            segment = MarketSegment.NSE,
            candlesByTimeframe = mapOf(Timeframe.M15 to candles),
            currentLtp = BigDecimal("104.50"),
            now = candles.last().timestamp
        )

        val signal = evaluator.evaluate(ctx)
        assertNotNull("Expected INTRADAY ORB+VWAP LONG signal", signal)
        assertEquals(SignalStrategy.INTRADAY, signal!!.strategy)
        assertEquals(SignalDirection.LONG, signal.direction)
        assertTrue(signal.stopLoss < signal.entryPrice)
        assertTrue(signal.target2 > signal.target1)
    }

    @Test
    fun `swing Heikin Ashi evaluator fires only when all 4 conditions align`() {
        val evaluator = SwingHeikinAshiEvaluator()

        fun buildSwingSeries(includeLowerWickSpoil: Boolean): List<Candle> {
            val list = mutableListOf<Candle>()
            // 54 D1 candles in a healthy uptrend keeping RSI between 50 and 70 and EMA50 rising
            var price = 100.0
            for (i in 0 until 54) {
                val delta = if (i % 2 == 0) 0.80 else -0.60 // keeps EMA50 rising and RSI cleanly in 55..65
                val open = price
                val close = price + delta
                val high = maxOf(open, close) + 0.4
                val low = minOf(open, close) - 0.3
                list.add(
                    Candle(
                        timestamp = baseTime.plusMillis(i * Timeframe.D1.durationMillis),
                        open = open,
                        high = high,
                        low = low,
                        close = close,
                        volume = 50_000.0
                    )
                )
                price = close
            }
            // Replace final bar with strong green expansion OR a bar with big lower wick that fails Condition 3
            val lastIdx = list.lastIndex
            val prevClose = list[lastIdx - 1].close
            val ts = list[lastIdx].timestamp
            list[lastIdx] = if (includeLowerWickSpoil) {
                Candle(ts, open = prevClose + 0.5, high = prevClose + 1.5, low = prevClose - 8.0, close = prevClose + 1.0, volume = 50_000.0)
            } else {
                Candle(ts, open = prevClose + 0.1, high = prevClose + 1.2, low = prevClose + 0.05, close = prevClose + 1.05, volume = 65_000.0)
            }
            return list
        }

        val aligned = buildSwingSeries(includeLowerWickSpoil = false)
        val validSignal = evaluator.evaluate(
            SignalContext("INFY", MarketSegment.NSE, mapOf(Timeframe.D1 to aligned), BigDecimal("120.00"), aligned.last().timestamp)
        )
        assertNotNull("Expected SWING signal when all 4 conditions align", validSignal)
        assertEquals(SignalStrategy.SWING, validSignal!!.strategy)
        assertEquals(SignalDirection.LONG, validSignal.direction)

        val brokenWick = buildSwingSeries(includeLowerWickSpoil = true)
        val rejectedSignal = evaluator.evaluate(
            SignalContext("INFY", MarketSegment.NSE, mapOf(Timeframe.D1 to brokenWick), BigDecimal("120.00"), brokenWick.last().timestamp)
        )
        assertNull("Expected SWING evaluator NOT to fire when Heikin Ashi has lower wick", rejectedSignal)
    }

    @Test
    fun `reversal evaluator requires multi-indicator confluence and does NOT fire on RSI oversold alone`() {
        val evaluator = ReversalConfluenceEvaluator()

        // Case A: RSI oversold drift only, NO reversal hammer/engulfing candle and NO Bollinger/MACD/Vol confluence -> must be null
        val rsiOnlyBars = (0 until 45).map { i ->
            val p = 150.0 - i * 0.9
            Candle(baseTime.plusMillis(i * Timeframe.H1.durationMillis), p + 0.4, p + 0.5, p - 0.1, p, 10_000.0)
        }
        val rsiOnlyResult = evaluator.evaluate(
            SignalContext("HDFCBANK", MarketSegment.NSE, mapOf(Timeframe.H1 to rsiOnlyBars), BigDecimal("110.00"), rsiOnlyBars.last().timestamp)
        )
        assertNull("Reversal must NOT fire on RSI alone without confluence + reversal candle", rsiOnlyResult)

        // Case B: Multi-indicator confluence (steady down move pushing RSI <=30, W%R <=-80, BB lower pierce, MACD negative)
        // followed by a huge volume hammer candle turning RSI/W%R/MACD histogram up!
        val confluenceBars = mutableListOf<Candle>()
        var p = 200.0
        for (i in 0 until 44) {
            val drop = when {
                i < 15 -> -0.2
                i < 35 -> -1.4
                else -> -2.0
            }
            val open = p
            val close = p + drop
            confluenceBars.add(
                Candle(
                    timestamp = baseTime.plusMillis(i * Timeframe.H1.durationMillis),
                    open = open,
                    high = open + 0.3,
                    low = close - 0.3,
                    close = close,
                    volume = 10_000.0
                )
            )
            p = close
        }
        // Final bar: Hammer / Bullish Pin Bar with lower wick piercing lower BB, closing strong + volume spike (3x avg)
        val prevClose = confluenceBars.last().close
        val hammerTs = baseTime.plusMillis(44 * Timeframe.H1.durationMillis)
        confluenceBars.add(
            Candle(
                timestamp = hammerTs,
                open = prevClose + 1.5,
                high = prevClose + 4.8,
                low = prevClose - 6.0,
                close = prevClose + 4.5,
                volume = 35_000.0
            )
        )

        val validReversal = evaluator.evaluate(
            SignalContext("HDFCBANK", MarketSegment.NSE, mapOf(Timeframe.H1 to confluenceBars), BigDecimal("140.00"), hammerTs)
        )
        assertNotNull("Expected REVERSAL LONG signal on multi-indicator confluence", validReversal)
        assertEquals(SignalStrategy.REVERSAL, validReversal!!.strategy)
        assertEquals(SignalDirection.LONG, validReversal.direction)
        assertTrue(validReversal.reasons.size >= 4)
    }

    @Test
    fun `swig evaluator requires higher timeframe bias to match lower timeframe trigger`() {
        val evaluator = SwigHybridEvaluator()

        // Bullish D1 HTF with realistic zig-zag pullbacks (RSI in 55..68, EMA21 > EMA50)
        val d1Bull = mutableListOf<Candle>()
        var dPrice = 100.0
        for (i in 0 until 55) {
            val step = if (i % 2 == 0) 0.9 else -0.4
            val o = dPrice
            val c = dPrice + step
            d1Bull.add(Candle(baseTime.plusMillis(i * Timeframe.D1.durationMillis), o, maxOf(o, c) + 0.4, minOf(o, c) - 0.4, c, 20_000.0))
            dPrice = c
        }
        // Bullish M15 LTF with realistic pullbacks (RSI in 55..68, EMA9 > EMA21, close > VWAP)
        val m15Bull = mutableListOf<Candle>()
        var mPrice = 124.0
        for (i in 0 until 30) {
            val step = if (i == 29) 0.6 else if (i % 2 == 0) 0.4 else -0.2
            val o = mPrice
            val c = mPrice + step
            m15Bull.add(Candle(baseTime.plusMillis(i * Timeframe.M15.durationMillis), o, maxOf(o, c) + 0.2, minOf(o, c) - 0.2, c, 5_000.0))
            mPrice = c
        }
        // Bearish M15 LTF (misaligned with Bullish D1!)
        val m15Bear = mutableListOf<Candle>()
        var bPrice = 140.0
        for (i in 0 until 30) {
            val step = if (i % 2 == 0) -0.45 else 0.15
            val o = bPrice
            val c = bPrice + step
            m15Bear.add(Candle(baseTime.plusMillis(i * Timeframe.M15.durationMillis), o, maxOf(o, c) + 0.2, minOf(o, c) - 0.2, c, 5_000.0))
            bPrice = c
        }

        val alignedSignal = evaluator.evaluate(
            SignalContext(
                symbol = "BTCUSDT",
                segment = MarketSegment.CRYPTO,
                candlesByTimeframe = mapOf(Timeframe.D1 to d1Bull, Timeframe.M15 to m15Bull),
                currentLtp = BigDecimal("127.40"),
                now = m15Bull.last().timestamp
            )
        )
        assertNotNull("Expected SWIG signal when D1 bias and M15 trigger align LONG", alignedSignal)
        assertEquals(SignalStrategy.SWIG, alignedSignal!!.strategy)
        assertEquals(SignalDirection.LONG, alignedSignal.direction)

        val misalignedSignal = evaluator.evaluate(
            SignalContext(
                symbol = "BTCUSDT",
                segment = MarketSegment.CRYPTO,
                candlesByTimeframe = mapOf(Timeframe.D1 to d1Bull, Timeframe.M15 to m15Bear),
                currentLtp = BigDecimal("129.85"),
                now = m15Bear.last().timestamp
            )
        )
        assertNull("Expected SWIG evaluator to return null when HTF is LONG and LTF is SHORT", misalignedSignal)
    }

    @Test
    fun `composite signal engine filters out expired signals and enforces valid SL T1 T2 RR`() {
        val now = Instant.ofEpochSecond(2_000_000L)
        val validSignal = TradeSignal(
            id = "valid_1",
            symbol = "RELIANCE",
            segment = MarketSegment.NSE,
            strategy = SignalStrategy.SCALP,
            direction = SignalDirection.LONG,
            timeframe = Timeframe.M5,
            entryPrice = BigDecimal("1285.00"),
            stopLoss = BigDecimal("1275.00"),
            target1 = BigDecimal("1295.00"),
            target2 = BigDecimal("1300.00"),
            riskRewardRatio = 1.5,
            confidence = SignalConfidence.HIGH,
            reasons = listOf("Valid"),
            generatedAt = now.minusSeconds(60),
            expiresAt = now.plusSeconds(3600)
        )
        val expiredSignal = validSignal.copy(
            id = "expired_1",
            strategy = SignalStrategy.INTRADAY,
            expiresAt = now.minusSeconds(10)
        )
        val invalidZeroSl = validSignal.copy(
            id = "bad_sl_1",
            strategy = SignalStrategy.SWING,
            stopLoss = BigDecimal.ZERO
        )

        val engine = CompositeSignalEngine(
            listOf(
                object : StrategyEvaluator {
                    override val strategy = SignalStrategy.SCALP
                    override fun evaluate(context: SignalContext) = validSignal
                },
                object : StrategyEvaluator {
                    override val strategy = SignalStrategy.INTRADAY
                    override fun evaluate(context: SignalContext) = expiredSignal
                },
                object : StrategyEvaluator {
                    override val strategy = SignalStrategy.SWING
                    override fun evaluate(context: SignalContext) = invalidZeroSl
                }
            )
        )

        val results = engine.evaluate(
            SignalContext("RELIANCE", MarketSegment.NSE, emptyMap(), BigDecimal("1285.00"), now)
        )
        assertEquals(1, results.size)
        val sig = results.single()
        assertEquals("valid_1", sig.id)
        assertNotNull(sig.stopLoss)
        assertNotNull(sig.target1)
        assertNotNull(sig.target2)
        assertTrue(sig.riskRewardRatio > 0.0)
    }

    @Test
    fun `candle aggregator aggregates raw ticks into all timeframes and emits completed candle events`() {
        val aggregator = CandleAggregator()
        // Align to exact UTC midnight so t0 is a clean boundary for ALL timeframes (M1..D1)
        val t0 = (1_731_900_000_000L / Timeframe.D1.durationMillis) * Timeframe.D1.durationMillis
        // First M1 bar ticks
        assertTrue(aggregator.onTick(PriceTick("BTCUSDT", BigDecimal("91000.00"), t0 + 1_000L, volume = 100L)).isEmpty())
        assertTrue(aggregator.onTick(PriceTick("BTCUSDT", BigDecimal("91250.00"), t0 + 20_000L, volume = 150L)).isEmpty())
        assertTrue(aggregator.onTick(PriceTick("BTCUSDT", BigDecimal("90900.00"), t0 + 40_000L, volume = 210L)).isEmpty())

        // Cross 1m boundary -> closes M1 bar
        val completed1m = aggregator.onTick(PriceTick("BTCUSDT", BigDecimal("91150.00"), t0 + 65_000L, volume = 260L))
        assertEquals(listOf(Timeframe.M1), completed1m.map { it.timeframe })
        val closedM1 = completed1m.single().candle
        assertEquals(91000.0, closedM1.open, 0.01)
        assertEquals(91250.0, closedM1.high, 0.01)
        assertEquals(90900.0, closedM1.low, 0.01)
        assertEquals(90900.0, closedM1.close, 0.01)

        // Jump +1 day + 1m -> closes every intraday timeframe and D1 (W1/1Mo only roll on calendar week/month)
        val completedDayRoll = aggregator.onTick(
            PriceTick("BTCUSDT", BigDecimal("92000.00"), t0 + Timeframe.D1.durationMillis + 60_000L, volume = 500L)
        )
        val expectedRoll = Timeframe.entries.filter { it != Timeframe.W1 && it != Timeframe.MN1 }.toSet()
        assertEquals(expectedRoll, completedDayRoll.map { it.timeframe }.toSet())
        val stored = aggregator.getCandles("BTCUSDT")
        assertEquals(2, stored[Timeframe.M1]?.size)
        assertEquals(1, stored[Timeframe.D1]?.size)
    }
}
