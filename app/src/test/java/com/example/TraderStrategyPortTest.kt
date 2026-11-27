package com.example

import com.example.domain.signal.Candle
import com.example.domain.strategy.ExitPlanner
import com.example.domain.strategy.MetricScreener
import com.example.domain.strategy.PortfolioRebalancer
import com.example.domain.strategy.PositionSizer
import com.example.domain.strategy.PreTradeRiskGate
import com.example.domain.strategy.SetupScanner
import com.example.domain.strategy.TaxLotTracker
import com.example.domain.strategy.TradeOutcomeSimulator
import com.example.domain.strategy.TraderRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * Covers the engines ported from the trader skill packs: the exit planner, the position
 * sizer, the pre-trade risk gate, the setup scanner and the outcome simulator.
 */
class TraderStrategyPortTest {

    private fun candle(o: Double, h: Double, l: Double, c: Double, v: Double = 1000.0, t: Long = 0) =
        Candle(Instant.ofEpochSecond(t), o, h, l, c, v)

    private val rules = TraderRules(
        budget = BigDecimal("100000"),
        cashBufferPct = BigDecimal("10"),
        maxPositionPct = BigDecimal("75"),
        maxOrderInr = BigDecimal("100000"),
        minOrderInr = BigDecimal("500"),
        maxLossPerTradeInr = BigDecimal("500"),
        maxDailyLossInr = BigDecimal("1500"),
        minRewardRisk = BigDecimal("1.0")
    )

    // ---------------------------------------------------------------- exit planner

    @Test
    fun `opening range midpoint stop and 2R target`() {
        val plan = ExitPlanner.plan(
            entry = BigDecimal("102.00"),
            stopRule = ExitPlanner.StopRule.OpeningRangeMid,
            targetRule = ExitPlanner.TargetRule.RMultiple(BigDecimal("2")),
            openingRangeHigh = BigDecimal("101.00"),
            openingRangeLow = BigDecimal("99.00"),
            tickSize = BigDecimal("0.05")
        )
        // midpoint of 99..101 is 100; risk is 2.00; a 2R target sits 4.00 above the entry
        assertEquals(0, plan.stop.compareTo(BigDecimal("100.00")))
        assertEquals(0, plan.riskPerShare.compareTo(BigDecimal("2.00")))
        assertEquals(0, plan.target.compareTo(BigDecimal("106.00")))
        assertEquals(0, plan.rewardRisk.compareTo(BigDecimal("2.00")))
    }

    @Test
    fun `stop above the entry is refused`() {
        val failure = runCatching {
            ExitPlanner.plan(
                entry = BigDecimal("100.00"),
                stopRule = ExitPlanner.StopRule.Price(BigDecimal("105.00")),
                targetRule = ExitPlanner.TargetRule.RMultiple(BigDecimal("2"))
            )
        }.exceptionOrNull()
        assertTrue(failure is ExitPlanner.PlanRefused)
        assertTrue(failure!!.message!!.contains("under the entry"))
    }

    @Test
    fun `a stop inside the spread is refused as too tight`() {
        val failure = runCatching {
            ExitPlanner.plan(
                entry = BigDecimal("1000.00"),
                // 0.02% away, under the 0.05% floor
                stopRule = ExitPlanner.StopRule.Price(BigDecimal("999.80")),
                targetRule = ExitPlanner.TargetRule.RMultiple(BigDecimal("2"))
            )
        }.exceptionOrNull()
        assertTrue(failure is ExitPlanner.PlanRefused)
        assertTrue(failure!!.message!!.contains("too tight"))
    }

    @Test
    fun `a target that cannot clear round-trip costs is refused`() {
        val failure = runCatching {
            ExitPlanner.plan(
                entry = BigDecimal("1000.00"),
                stopRule = ExitPlanner.StopRule.Price(BigDecimal("990.00")),
                // 0.05% above the entry, under the ~0.12% round-trip cost
                targetRule = ExitPlanner.TargetRule.Price(BigDecimal("1000.50"))
            )
        }.exceptionOrNull()
        assertTrue(failure is ExitPlanner.PlanRefused)
        assertTrue(failure!!.message!!.contains("round-trip cost"))
    }

    @Test
    fun `percent stop is floored to the tick so it never moves nearer the entry`() {
        val plan = ExitPlanner.plan(
            entry = BigDecimal("100.00"),
            stopRule = ExitPlanner.StopRule.Percent(BigDecimal("1.03")),
            targetRule = ExitPlanner.TargetRule.RMultiple(BigDecimal("2")),
            tickSize = BigDecimal("0.05")
        )
        // 98.97 floored to the 0.05 grid is 98.95, which is further from the entry, not nearer
        assertEquals(0, plan.stop.compareTo(BigDecimal("98.95")))
        assertTrue(plan.stop < BigDecimal("98.97"))
    }

    // ---------------------------------------------------------------- position sizer

    private fun planAt(entry: String, stop: String, target: String) = ExitPlanner.Plan(
        entry = BigDecimal(entry), stop = BigDecimal(stop), stopLabel = "test", stopDetail = "",
        target = BigDecimal(target), targetLabel = "test",
        riskPerShare = BigDecimal(entry).subtract(BigDecimal(stop)),
        rewardRisk = BigDecimal("2")
    )

    @Test
    fun `max loss is the binding limit when the stop is wide`() {
        val sizing = PositionSizer.size("RELIANCE", planAt("100.00", "95.00", "110.00"), rules)
        // Rs 500 max loss / Rs 5 risk a share = 100 shares, which is cheaper than every other cap
        assertEquals(100, sizing.quantity)
        assertEquals("max loss", sizing.boundBy)
        assertTrue(sizing.limits.first { it.name == "max loss" }.binds)
        assertEquals(0, sizing.maxLoss.compareTo(BigDecimal("500.00")))
        assertNull(sizing.refusal)
    }

    @Test
    fun `position cap is the binding limit when the stop is tight`() {
        // Risk Rs 0.50 a share would allow 1000 shares on max loss alone, but 75% of the
        // budget only buys 750, so the cap decides - and says so.
        val sizing = PositionSizer.size("RELIANCE", planAt("100.00", "99.50", "110.00"), rules)
        assertEquals(750, sizing.quantity)
        assertEquals("position cap", sizing.boundBy)
    }

    @Test
    fun `cash becomes the binding limit once the position cap is lifted`() {
        val sizing = PositionSizer.size(
            "RELIANCE", planAt("100.00", "99.50", "110.00"),
            rules.copy(maxPositionPct = BigDecimal("100"))
        )
        assertEquals(900, sizing.quantity) // (100000 - 10000 buffer) / 100
        assertEquals("cash", sizing.boundBy)
    }

    @Test
    fun `a market order is sized at the higher of the plan and a gapped-up price`() {
        val sizing = PositionSizer.size(
            "RELIANCE", planAt("100.00", "95.00", "110.00"), rules,
            livePrice = BigDecimal("104.00")
        )
        assertEquals(PositionSizer.EntrySource.LIVE_PRICE, sizing.entrySource)
        // risk is now 9.00 from the live price, so max loss allows fewer shares
        assertEquals(0, sizing.entry.compareTo(BigDecimal("104.00")))
        assertEquals(55, sizing.quantity) // floor(500 / 9)
    }

    @Test
    fun `a stale price below the plan does not shrink the risk`() {
        val sizing = PositionSizer.size(
            "RELIANCE", planAt("100.00", "95.00", "110.00"), rules,
            livePrice = BigDecimal("97.00")
        )
        assertEquals(PositionSizer.EntrySource.PLAN, sizing.entrySource)
        assertEquals(0, sizing.entry.compareTo(BigDecimal("100.00")))
    }

    @Test
    fun `one share over the max loss refuses the trade outright`() {
        val tight = rules.copy(maxLossPerTradeInr = BigDecimal("2"))
        val sizing = PositionSizer.size("RELIANCE", planAt("100.00", "95.00", "110.00"), tight)
        assertEquals(0, sizing.quantity)
        assertFalse(sizing.isTradeable)
        assertTrue(sizing.refusal!!.contains("one share risks"))
    }

    @Test
    fun `reward to risk under the minimum refuses the trade`() {
        val strict = rules.copy(minRewardRisk = BigDecimal("3.0"))
        val sizing = PositionSizer.size("RELIANCE", planAt("100.00", "95.00", "110.00"), strict)
        assertTrue(sizing.quantity > 0)
        assertFalse(sizing.isTradeable)
        assertTrue(sizing.refusal!!.contains("reward:risk"))
    }

    // ---------------------------------------------------------------- risk gate

    private val account = PreTradeRiskGate.AccountSnapshot(
        cash = BigDecimal("100000"), equity = BigDecimal("100000")
    )

    @Test
    fun `a clean order passes the gate`() {
        val result = PreTradeRiskGate.check(
            PreTradeRiskGate.ProposedOrder(
                symbol = "RELIANCE", isBuy = true, quantity = 100,
                entry = BigDecimal("100"), stop = BigDecimal("95"), target = BigDecimal("110")
            ),
            rules.copy(maxPositionPct = BigDecimal("100"), cashBufferPct = BigDecimal("0")),
            account, now = LocalTime.of(10, 0)
        )
        assertTrue(result.reasons.joinToString(), result.passed)
        assertEquals(0, result.lossAtStop!!.compareTo(BigDecimal("500.00")))
    }

    @Test
    fun `an entry with no stop is blocked`() {
        val result = PreTradeRiskGate.check(
            PreTradeRiskGate.ProposedOrder("RELIANCE", true, 10, BigDecimal("100")),
            rules, account, now = LocalTime.of(10, 0)
        )
        assertFalse(result.passed)
        assertTrue(result.reasons.any { it.startsWith("stop:") })
    }

    @Test
    fun `loss over the per-trade cap is blocked`() {
        val result = PreTradeRiskGate.check(
            PreTradeRiskGate.ProposedOrder(
                "RELIANCE", true, 500, BigDecimal("100"), BigDecimal("95"), BigDecimal("110")
            ),
            rules, account, now = LocalTime.of(10, 0)
        )
        assertFalse(result.passed)
        assertTrue(result.reasons.any { it.startsWith("loss:") })
    }

    @Test
    fun `the daily loss cap counts what is already committed`() {
        val result = PreTradeRiskGate.check(
            PreTradeRiskGate.ProposedOrder(
                "RELIANCE", true, 100, BigDecimal("100"), BigDecimal("95"), BigDecimal("110")
            ),
            rules.copy(maxPositionPct = BigDecimal("100"), cashBufferPct = BigDecimal("0")),
            account,
            day = PreTradeRiskGate.DayState(entriesPlaced = 1, committedLoss = BigDecimal("1200")),
            now = LocalTime.of(10, 0)
        )
        assertFalse(result.passed)
        assertTrue(result.reasons.any { it.contains("daily loss cap") })
    }

    @Test
    fun `entries are refused outside the trading window`() {
        val early = PreTradeRiskGate.check(
            PreTradeRiskGate.ProposedOrder(
                "RELIANCE", true, 100, BigDecimal("100"), BigDecimal("95"), BigDecimal("110")
            ),
            rules.copy(maxPositionPct = BigDecimal("100"), cashBufferPct = BigDecimal("0")),
            account, now = LocalTime.of(9, 20)
        )
        assertFalse(early.passed)
        assertTrue(early.reasons.any { it.startsWith("window:") })
    }

    @Test
    fun `selling more than held is blocked while shorting is off`() {
        val result = PreTradeRiskGate.check(
            PreTradeRiskGate.ProposedOrder("RELIANCE", false, 100, BigDecimal("100"), isEntry = true),
            rules.copy(requireStop = false),
            account.copy(holdings = mapOf("RELIANCE" to 40)),
            now = LocalTime.of(10, 0)
        )
        assertFalse(result.passed)
        assertTrue(result.reasons.any { it.startsWith("short:") })
    }

    // ---------------------------------------------------------------- setup scanner

    @Test
    fun `a new 20-day closing high is tagged as a breakout`() {
        val closes = (1..34).map { 100.0 } + listOf(130.0)
        val scan = SetupScanner.findDailySetups(closes)!!
        assertTrue(SetupScanner.DailySetup.BREAKOUT_20D in scan.setups)
    }

    @Test
    fun `a flat series has no breakout`() {
        val scan = SetupScanner.findDailySetups(List(40) { 100.0 })!!
        assertFalse(SetupScanner.DailySetup.BREAKOUT_20D in scan.setups)
    }

    @Test
    fun `too little history yields no scan at all`() {
        assertNull(SetupScanner.findDailySetups(List(20) { 100.0 }))
    }

    @Test
    fun `a strong run that goes quiet is a bull flag`() {
        // +20% over bars n-16..n-6, then a tight final week
        val closes = MutableList(40) { 100.0 }
        for (i in 25 until 35) closes[i] = 100.0 + (i - 24) * 2.0
        for (i in 35 until 40) closes[i] = 120.5
        val scan = SetupScanner.findDailySetups(closes)!!
        assertTrue(scan.runPct > 10.0)
        assertTrue(scan.range5Pct < 5.0)
        assertTrue(SetupScanner.DailySetup.BULL_FLAG in scan.setups)
    }

    @Test
    fun `breakout needs a close above the range high not just a wick`() {
        val bars = listOf(
            candle(100.0, 102.0, 99.0, 101.0),
            candle(101.0, 102.0, 100.0, 101.5),
            candle(101.5, 103.0, 101.0, 102.0), // wicks above 102 but closes at 102.0, not above
            candle(102.0, 104.0, 101.5, 103.5)  // closes above -> this is the breakout
        )
        val hit = SetupScanner.openingRangeBreakout(bars, rangeBars = 2)
        assertNotNull(hit)
        assertEquals(3, hit!!.barIndex)
        assertEquals(102.0, hit.openingRangeHigh, 1e-9)
    }

    @Test
    fun `a thin breakout bar is skipped when a volume multiple is required`() {
        val bars = listOf(
            candle(100.0, 102.0, 99.0, 101.0, v = 1000.0),
            candle(101.0, 102.0, 100.0, 101.5, v = 1000.0),
            candle(101.5, 103.0, 101.0, 102.5, v = 200.0),  // breaks out but on 0.2x volume
            candle(102.5, 104.0, 102.0, 103.5, v = 3000.0)  // breaks out on 3x volume
        )
        val hit = SetupScanner.openingRangeBreakout(bars, rangeBars = 2, minVolumeMultiple = 1.5)
        assertEquals(3, hit!!.barIndex)
    }

    @Test
    fun `the cheap close-only prefilter agrees with the full check`() {
        val bars = listOf(
            candle(100.0, 102.0, 99.0, 101.0),
            candle(101.0, 102.0, 100.0, 101.5),
            candle(101.5, 101.9, 101.0, 101.2)
        )
        assertFalse(SetupScanner.couldBreakOut(bars.map { it.close }, 2))
        assertNull(SetupScanner.openingRangeBreakout(bars, 2))
    }

    // ---------------------------------------------------------------- simulator

    private fun flatSession(start: Double, drift: Double, bars: Int = 20): List<Candle> {
        var p = start
        return (0 until bars).map {
            val o = p
            val c = p * (1.0 + drift)
            p = c
            candle(o, maxOf(o, c) * 1.001, minOf(o, c) * 0.999, c)
        }
    }

    @Test
    fun `monte carlo is deterministic for a given seed`() {
        val pool = TradeOutcomeSimulator.buildPool(List(5) { flatSession(100.0, 0.001) }, sessionBars = 20)
        val a = TradeOutcomeSimulator.monteCarlo(pool, 100.0, 98.0, 104.0, 2, 19, replays = 500, seed = 7)
        val b = TradeOutcomeSimulator.monteCarlo(pool, 100.0, 98.0, 104.0, 2, 19, replays = 500, seed = 7)
        assertEquals(a!!.probabilityTarget, b!!.probabilityTarget, 0.0)
        assertEquals(a.averageR, b.averageR, 0.0)
    }

    @Test
    fun `monte carlo probabilities sum to one`() {
        val pool = TradeOutcomeSimulator.buildPool(List(5) { flatSession(100.0, 0.002) }, sessionBars = 20)
        val r = TradeOutcomeSimulator.monteCarlo(pool, 100.0, 98.0, 104.0, 2, 19, replays = 1000, seed = 19)!!
        assertEquals(1.0, r.probabilityTarget + r.probabilityStop + r.probabilityNeither, 1e-9)
    }

    @Test
    fun `a rising pool reaches the target more often than the stop`() {
        val pool = TradeOutcomeSimulator.buildPool(List(8) { flatSession(100.0, 0.01) }, sessionBars = 20)
        val r = TradeOutcomeSimulator.monteCarlo(pool, 100.0, 95.0, 105.0, 2, 19, replays = 2000, seed = 19)!!
        assertTrue(
            "target ${r.probabilityTarget} should beat stop ${r.probabilityStop}",
            r.probabilityTarget > r.probabilityStop
        )
    }

    @Test
    fun `an unreachable stop is never hit`() {
        val pool = TradeOutcomeSimulator.buildPool(List(5) { flatSession(100.0, 0.001) }, sessionBars = 20)
        val r = TradeOutcomeSimulator.monteCarlo(pool, 100.0, 1.0, 1000.0, 2, 19, replays = 300, seed = 3)!!
        assertEquals(0.0, r.probabilityStop, 0.0)
        assertEquals(0.0, r.probabilityTarget, 0.0)
        assertEquals(1.0, r.probabilityNeither, 0.0)
    }

    @Test
    fun `history replay finds the breakout entry and takes the target`() {
        // Opening range over bars 0..1 is 99..102; bar 2 closes above it; price then runs up.
        val session = listOf(
            candle(100.0, 102.0, 99.0, 101.0),
            candle(101.0, 102.0, 100.0, 101.5),
            candle(101.5, 103.0, 101.0, 103.0),  // entry at 103.0
            candle(103.0, 110.0, 102.9, 109.0),  // target hit
            candle(109.0, 112.0, 108.0, 111.0)
        )
        val res = TradeOutcomeSimulator.historyReplay(
            sessions = listOf(session), rangeBars = 2,
            stopRule = ExitPlanner.StopRule.OpeningRangeMid, targetR = 2.0, toBar = 4
        )
        assertEquals(1, res.triggered)
        assertEquals(1, res.target)
        val t = res.trades.single()
        assertEquals(2, t.entryBar)
        assertEquals(100.5, t.stop, 1e-9)   // midpoint of 99..102
        assertEquals(2.0, t.r, 1e-9)
    }

    @Test
    fun `history replay takes the stop when price falls first`() {
        val session = listOf(
            candle(100.0, 102.0, 99.0, 101.0),
            candle(101.0, 102.0, 100.0, 101.5),
            candle(101.5, 103.0, 101.0, 103.0),  // entry at 103.0, stop 100.5
            candle(103.0, 103.1, 100.0, 100.2),  // low pierces the stop
            candle(100.2, 101.0, 99.0, 100.0)
        )
        val res = TradeOutcomeSimulator.historyReplay(
            listOf(session), 2, ExitPlanner.StopRule.OpeningRangeMid, 2.0, toBar = 4
        )
        assertEquals(1, res.stop)
        assertEquals(-1.0, res.trades.single().r, 1e-9)
    }

    @Test
    fun `a session that never breaks out contributes no trade`() {
        val session = listOf(
            candle(100.0, 102.0, 99.0, 101.0),
            candle(101.0, 102.0, 100.0, 101.5),
            candle(101.5, 101.9, 101.0, 101.2),
            candle(101.2, 101.8, 100.5, 101.0)
        )
        val res = TradeOutcomeSimulator.historyReplay(
            listOf(session), 2, ExitPlanner.StopRule.OpeningRangeMid, 2.0, toBar = 3
        )
        assertEquals(1, res.sessions)
        assertEquals(0, res.triggered)
        assertNull(res.averageR)
    }

    // ---------------------------------------------------------------- tax lots (India)

    private fun lot(id: String, acquired: String, shares: Int, costPerShare: String) =
        TaxLotTracker.Lot(id, LocalDate.parse(acquired), shares, BigDecimal(costPerShare))

    @Test
    fun `twelve months exactly is still short term`() {
        assertEquals(
            TaxLotTracker.Term.SHORT,
            TaxLotTracker.termFor(LocalDate.parse("2024-01-15"), LocalDate.parse("2025-01-15"))
        )
        assertEquals(
            TaxLotTracker.Term.LONG,
            TaxLotTracker.termFor(LocalDate.parse("2024-01-15"), LocalDate.parse("2025-01-16"))
        )
    }

    @Test
    fun `FIFO consumes the oldest lot first`() {
        val lots = listOf(lot("B", "2024-06-01", 10, "200"), lot("A", "2023-01-01", 10, "100"))
        val picked = TaxLotTracker.pickLots(lots, 15, TaxLotTracker.Method.FIFO)
        assertEquals("A", picked[0].first.id)
        assertEquals(10, picked[0].second)
        assertEquals("B", picked[1].first.id)
        assertEquals(5, picked[1].second)
    }

    @Test
    fun `highest cost first books a smaller gain than FIFO`() {
        val lots = listOf(lot("A", "2023-01-01", 10, "100"), lot("B", "2024-06-01", 10, "200"))
        val sold = LocalDate.parse("2025-06-01")
        val fifo = TaxLotTracker.simulateSale(lots, 10, BigDecimal("250"), sold, TaxLotTracker.Method.FIFO)
        val hifo = TaxLotTracker.simulateSale(lots, 10, BigDecimal("250"), sold, TaxLotTracker.Method.HIGHEST_COST)
        assertEquals(0, fifo.gain.compareTo(BigDecimal("1500.00")))
        assertEquals(0, hifo.gain.compareTo(BigDecimal("500.00")))
    }

    @Test
    fun `a sale splits the gain by holding period`() {
        val lots = listOf(lot("OLD", "2023-01-01", 10, "100"), lot("NEW", "2025-03-01", 10, "200"))
        val r = TaxLotTracker.simulateSale(lots, 20, BigDecimal("250"), LocalDate.parse("2025-06-01"))
        assertEquals(20, r.shares)
        assertEquals(0, r.gain.compareTo(BigDecimal("2000.00")))
        assertEquals(0, r.longTermGain.compareTo(BigDecimal("1500.00")))
        assertEquals(0, r.shortTermGain.compareTo(BigDecimal("500.00")))
    }

    @Test
    fun `overselling the book is refused`() {
        val failure = runCatching {
            TaxLotTracker.pickLots(listOf(lot("A", "2023-01-01", 5, "100")), 10)
        }.exceptionOrNull()
        assertTrue(failure is TaxLotTracker.TaxError)
        assertTrue(failure!!.message!!.contains("only 5 are held"))
    }

    @Test
    fun `applying a sale leaves the right remainder`() {
        val lots = listOf(lot("A", "2023-01-01", 10, "100"), lot("B", "2024-06-01", 10, "200"))
        val left = TaxLotTracker.applySale(lots, 15)
        assertEquals(1, left.size)
        assertEquals("B", left[0].id)
        assertEquals(5, left[0].shares)
    }

    @Test
    fun `harvest lists the worst losers first`() {
        val lots = listOf(
            lot("A", "2024-01-01", 10, "300"),
            lot("B", "2024-02-01", 10, "120"),
            lot("C", "2024-03-01", 10, "90")
        )
        val c = TaxLotTracker.harvestCandidates(
            lots, BigDecimal("100"), LocalDate.parse("2025-01-10"), BigDecimal("100")
        )
        assertEquals(2, c.size)
        assertEquals("A", c[0].lot.id)
        assertEquals(0, c[0].gain.compareTo(BigDecimal("-2000.00")))
    }

    // ---------------------------------------------------------------- rebalancer

    private val rbRules = TraderRules(
        budget = BigDecimal("100000"), cashBufferPct = BigDecimal.ZERO,
        maxPositionPct = BigDecimal("100"), maxOrderInr = null, minOrderInr = BigDecimal.ZERO
    )

    @Test
    fun `a balanced book proposes no trades`() {
        val plan = PortfolioRebalancer.plan(
            holdings = listOf(PortfolioRebalancer.Holding("A", 50), PortfolioRebalancer.Holding("B", 50)),
            prices = mapOf("A" to BigDecimal("100"), "B" to BigDecimal("100")),
            targetWeights = mapOf("A" to BigDecimal("50"), "B" to BigDecimal("50")),
            rules = rbRules, cash = BigDecimal.ZERO
        )
        assertTrue(plan.trades.isEmpty())
        assertTrue(plan.rows.all { it.action == "hold" })
    }

    @Test
    fun `drift beyond the band is traded back`() {
        val plan = PortfolioRebalancer.plan(
            holdings = listOf(PortfolioRebalancer.Holding("A", 80), PortfolioRebalancer.Holding("B", 20)),
            prices = mapOf("A" to BigDecimal("100"), "B" to BigDecimal("100")),
            targetWeights = mapOf("A" to BigDecimal("50"), "B" to BigDecimal("50")),
            rules = rbRules, cash = BigDecimal.ZERO
        )
        val sell = plan.trades.single { it.symbol == "A" }
        val buy = plan.trades.single { it.symbol == "B" }
        assertFalse(sell.isBuy)
        assertEquals(30, sell.quantity)
        assertTrue(buy.isBuy)
        assertEquals(30, buy.quantity)
    }

    @Test
    fun `drift inside the band is left alone`() {
        val plan = PortfolioRebalancer.plan(
            holdings = listOf(PortfolioRebalancer.Holding("A", 52), PortfolioRebalancer.Holding("B", 48)),
            prices = mapOf("A" to BigDecimal("100"), "B" to BigDecimal("100")),
            targetWeights = mapOf("A" to BigDecimal("50"), "B" to BigDecimal("50")),
            rules = rbRules, cash = BigDecimal.ZERO
        )
        assertTrue(plan.trades.isEmpty())
    }

    @Test
    fun `the position cap hands its excess to the others`() {
        val targets = PortfolioRebalancer.cappedTargets(
            mapOf("A" to BigDecimal("90"), "B" to BigDecimal("10")),
            BigDecimal("10000"), BigDecimal("5000")
        )
        assertEquals(0, targets.getValue("A").compareTo(BigDecimal("5000")))
        assertEquals(0, targets.getValue("B").compareTo(BigDecimal("5000")))
    }

    // ---------------------------------------------------------------- screener

    private fun company(ticker: String, metrics: Map<String, Any?>) =
        MetricScreener.Company(ticker, ticker, metrics)

    @Test
    fun `a company passing every rule passes`() {
        val res = MetricScreener.screen(
            listOf(company("AAA", mapOf("pe" to 15, "growth" to 20))),
            listOf(
                MetricScreener.Rule("pe", MetricScreener.Op.LT, MetricScreener.Want.Number(25.0)),
                MetricScreener.Rule("growth", MetricScreener.Op.GTE, MetricScreener.Want.Number(10.0))
            )
        )
        assertTrue(res[0].passed)
        assertTrue(res[0].failures.isEmpty())
    }

    @Test
    fun `a failure explains itself with the numbers`() {
        val res = MetricScreener.screen(
            listOf(company("AAA", mapOf("pe" to 40))),
            listOf(MetricScreener.Rule("pe", MetricScreener.Op.LT, MetricScreener.Want.Number(25.0)))
        )
        assertFalse(res[0].passed)
        assertEquals("pe 40, needs < 25", res[0].failures[0].reason)
    }

    @Test
    fun `a missing metric fails by default and can be skipped`() {
        val rules = listOf(MetricScreener.Rule("roe", MetricScreener.Op.GT, MetricScreener.Want.Number(15.0)))
        val companies = listOf(company("AAA", mapOf("pe" to 10)))
        assertFalse(MetricScreener.screen(companies, rules).first().passed)
        assertTrue(
            MetricScreener.screen(companies, rules, MetricScreener.MissingPolicy.SKIP).first().passed
        )
    }

    @Test
    fun `between is inclusive at both ends`() {
        val rule = MetricScreener.Rule("pe", MetricScreener.Op.BETWEEN, MetricScreener.Want.Range(10.0, 25.0))
        assertEquals(true, MetricScreener.check(company("A", mapOf("pe" to 25)), rule).passed)
        assertEquals(false, MetricScreener.check(company("A", mapOf("pe" to 26)), rule).passed)
    }

    @Test
    fun `a non-numeric metric under a numeric rule reports the type`() {
        val c = MetricScreener.check(
            company("A", mapOf("pe" to "n/a")),
            MetricScreener.Rule("pe", MetricScreener.Op.LT, MetricScreener.Want.Number(25.0))
        )
        assertEquals(false, c.passed)
        assertTrue(c.reason.contains("not a number"))
    }

    @Test
    fun `passing companies rank above failing ones`() {
        val res = MetricScreener.screen(
            listOf(
                company("BAD", mapOf("pe" to 90, "growth" to 1)),
                company("GOOD", mapOf("pe" to 10, "growth" to 30)),
                company("OK", mapOf("pe" to 20, "growth" to 12))
            ),
            listOf(MetricScreener.Rule("pe", MetricScreener.Op.LT, MetricScreener.Want.Number(25.0))),
            rankBy = listOf("growth")
        )
        assertEquals(listOf("GOOD", "OK", "BAD"), res.map { it.ticker })
    }
}
