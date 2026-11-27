package com.example.domain.strategy

import com.example.domain.signal.Candle
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Time Machine - ported from the `trader-in-timemachine` / `trader-timemachine` skills.
 *
 * Answers "what usually happens from here?" two ways, before a rupee is risked:
 *
 *  - [monteCarlo] rebuilds the rest of the session thousands of times by resampling the
 *    instrument's own past bars, and reports how often the target, the stop or neither
 *    came first.
 *  - [historyReplay] runs the same entry/stop/target rule over every past session and
 *    reports what actually happened.
 *
 * Neither is a prediction. They describe the distribution of a rule's past behaviour.
 *
 * The Monte Carlo is seeded, so the same inputs always give the same numbers - a trader
 * comparing two plans is comparing the plans, not two different random draws.
 */
object TradeOutcomeSimulator {

    /** 9:15 to 15:30 in 5-minute bars. */
    const val SESSION_BARS = 75

    enum class Outcome { TARGET, STOP, NEITHER }

    data class MonteCarloResult(
        val replays: Int,
        val barsLeft: Int,
        val probabilityTarget: Double,
        val probabilityStop: Double,
        val probabilityNeither: Double,
        /** Fraction of replays that ended above the entry (for a long). */
        val probabilityGreen: Double,
        val medianClose: Double,
        val p5Close: Double,
        val p95Close: Double,
        /** Mean outcome expressed in R (multiples of the risk). */
        val averageR: Double,
        val rAtTarget: Double
    )

    data class ReplayTrade(
        val sessionIndex: Int,
        val entryBar: Int,
        val entry: Double,
        val stop: Double,
        val target: Double,
        val outcome: Outcome,
        val r: Double
    )

    data class HistoryResult(
        val sessions: Int,
        val triggered: Int,
        val target: Int,
        val stop: Int,
        val neither: Int,
        val green: Int,
        val averageR: Double?,
        val trades: List<ReplayTrade>
    ) {
        /** Share of triggered sessions that finished in profit. */
        val winRate: Double? get() = if (triggered > 0) green.toDouble() / triggered else null
    }

    /**
     * Per-bar pool of (open, high, low, close) ratios against the previous bar's close,
     * built from past sessions. Resampling ratios rather than prices keeps the simulation
     * scale-free, so it works the same on a Rs 50 stock and a Rs 50,000 index.
     */
    class BarPool internal constructor(internal val slots: List<List<DoubleArray>>) {
        val isEmpty: Boolean get() = slots.all { it.isEmpty() }
    }

    /**
     * Build the resampling pool from past sessions, each given oldest-bar-first.
     * Sessions shorter than two bars contribute nothing.
     */
    fun buildPool(sessions: List<List<Candle>>, sessionBars: Int = SESSION_BARS): BarPool {
        val slots = List(sessionBars) { mutableListOf<DoubleArray>() }
        for (session in sessions) {
            val n = min(session.size, sessionBars)
            for (i in 1 until n) {
                val prevClose = session[i - 1].close
                if (prevClose <= 0.0) continue
                val b = session[i]
                if (!b.open.isFinite() || !b.high.isFinite() || !b.low.isFinite() || !b.close.isFinite()) continue
                slots[i] += doubleArrayOf(
                    b.open / prevClose, b.high / prevClose, b.low / prevClose, b.close / prevClose
                )
            }
        }
        return BarPool(slots)
    }

    /**
     * Replay bars [fromBar]+1..[toBar] [replays] times.
     *
     * When a single simulated bar spans both the stop and the target, the stop is taken.
     * Without tick data there is no way to know which came first, so the simulation takes
     * the loss - an optimistic assumption here would quietly inflate every win rate.
     *
     * @param jitter how many slots either side a bar may be drawn from, so the simulation
     *   is not locked to one time-of-day pattern. 0 draws strictly from the same slot.
     */
    fun monteCarlo(
        pool: BarPool,
        entry: Double,
        stop: Double,
        target: Double,
        fromBar: Int,
        toBar: Int,
        replays: Int = 10_000,
        seed: Int = 19,
        jitter: Int = 2,
        isLong: Boolean = true
    ): MonteCarloResult? {
        if (pool.isEmpty) return null
        if (replays <= 0 || toBar <= fromBar) return null
        val risk = if (isLong) entry - stop else stop - entry
        if (risk <= 0.0) return null
        val rAtTarget = (if (isLong) target - entry else entry - target) / risk

        val rng = Random(seed)
        val sessionBars = pool.slots.size
        val outcomes = IntArray(3)
        val closes = DoubleArray(replays)
        var greens = 0
        var rSum = 0.0

        for (n in 0 until replays) {
            var price = entry
            var outcome: Outcome? = null
            for (i in (fromBar + 1)..toBar) {
                val slotIndex = if (jitter == 0) i else {
                    min(sessionBars - 1, max(1, i + rng.nextInt(-jitter, jitter + 1)))
                }
                val candidates = pool.slots.getOrNull(slotIndex)?.takeIf { it.isNotEmpty() }
                    ?: pool.slots.firstOrNull { it.isNotEmpty() }
                    ?: break
                val draw = candidates[rng.nextInt(candidates.size)]
                val high = price * draw[1]
                val low = price * draw[2]
                val close = price * draw[3]
                if (outcome == null) {
                    outcome = if (isLong) {
                        when {
                            low <= stop -> Outcome.STOP
                            high >= target -> Outcome.TARGET
                            else -> null
                        }
                    } else {
                        when {
                            high >= stop -> Outcome.STOP
                            low <= target -> Outcome.TARGET
                            else -> null
                        }
                    }
                }
                price = close
            }
            val finalOutcome = outcome ?: Outcome.NEITHER
            outcomes[finalOutcome.ordinal]++
            closes[n] = price
            val green = finalOutcome == Outcome.TARGET ||
                (finalOutcome == Outcome.NEITHER && (if (isLong) price > entry else price < entry))
            if (green) greens++
            rSum += when (finalOutcome) {
                Outcome.TARGET -> rAtTarget
                Outcome.STOP -> -1.0
                Outcome.NEITHER -> (if (isLong) price - entry else entry - price) / risk
            }
        }

        closes.sort()
        return MonteCarloResult(
            replays = replays,
            barsLeft = toBar - fromBar,
            probabilityTarget = outcomes[Outcome.TARGET.ordinal].toDouble() / replays,
            probabilityStop = outcomes[Outcome.STOP.ordinal].toDouble() / replays,
            probabilityNeither = outcomes[Outcome.NEITHER.ordinal].toDouble() / replays,
            probabilityGreen = greens.toDouble() / replays,
            medianClose = percentile(closes, 0.50),
            p5Close = percentile(closes, 0.05),
            p95Close = percentile(closes, 0.95),
            averageR = rSum / replays,
            rAtTarget = rAtTarget
        )
    }

    /**
     * Run the opening-range rule over every past session: enter on the first bar after the
     * range to close beyond it, then take whichever of the stop or target comes first,
     * else mark to the close of bar [toBar].
     */
    fun historyReplay(
        sessions: List<List<Candle>>,
        rangeBars: Int,
        stopRule: ExitPlanner.StopRule,
        targetR: Double,
        toBar: Int,
        isLong: Boolean = true
    ): HistoryResult {
        val trades = mutableListOf<ReplayTrade>()

        sessions.forEachIndexed { index, session ->
            if (session.size <= rangeBars) return@forEachIndexed
            val last = min(toBar, session.size - 1)
            if (last <= rangeBars) return@forEachIndexed

            val range = session.subList(0, rangeBars)
            val orHigh = range.maxOf { it.high }
            val orLow = range.minOf { it.low }

            val entryBar = (rangeBars..last).firstOrNull { i ->
                if (isLong) session[i].close > orHigh else session[i].close < orLow
            } ?: return@forEachIndexed

            val entry = session[entryBar].close
            val stop = resolveStop(stopRule, orHigh, orLow, entry, isLong) ?: return@forEachIndexed
            val risk = if (isLong) entry - stop else stop - entry
            if (risk <= 0.0) return@forEachIndexed
            val target = if (isLong) entry + targetR * risk else entry - targetR * risk

            var outcome: Outcome? = null
            for (i in (entryBar + 1)..last) {
                val b = session[i]
                val stopHit = if (isLong) b.low <= stop else b.high >= stop
                if (stopHit) { outcome = Outcome.STOP; break }
                val targetHit = if (isLong) b.high >= target else b.low <= target
                if (targetHit) { outcome = Outcome.TARGET; break }
            }
            val closeOut = session[last].close
            val finalOutcome = outcome ?: Outcome.NEITHER
            val r = when (finalOutcome) {
                Outcome.TARGET -> (if (isLong) target - entry else entry - target) / risk
                Outcome.STOP -> -1.0
                Outcome.NEITHER -> (if (isLong) closeOut - entry else entry - closeOut) / risk
            }
            trades += ReplayTrade(index, entryBar, entry, stop, target, finalOutcome, r)
        }

        return HistoryResult(
            sessions = sessions.size,
            triggered = trades.size,
            target = trades.count { it.outcome == Outcome.TARGET },
            stop = trades.count { it.outcome == Outcome.STOP },
            neither = trades.count { it.outcome == Outcome.NEITHER },
            green = trades.count { it.r > 0.0 },
            averageR = if (trades.isEmpty()) null else trades.sumOf { it.r } / trades.size,
            trades = trades
        )
    }

    private fun resolveStop(
        rule: ExitPlanner.StopRule,
        orHigh: Double,
        orLow: Double,
        entry: Double,
        isLong: Boolean
    ): Double? = when (rule) {
        is ExitPlanner.StopRule.OpeningRangeMid -> (orHigh + orLow) / 2.0
        is ExitPlanner.StopRule.OpeningRangeLow -> if (isLong) orLow else orHigh
        is ExitPlanner.StopRule.Price -> rule.value.toDouble()
        is ExitPlanner.StopRule.Percent -> {
            val f = rule.value.toDouble() / 100.0
            if (isLong) entry * (1 - f) else entry * (1 + f)
        }
        // An ATR stop needs the bars leading up to each historical entry, which the replay
        // does not carry; callers use a percent or opening-range rule for history.
        is ExitPlanner.StopRule.AtrMultiple -> null
    }

    private fun percentile(sorted: DoubleArray, q: Double): Double {
        if (sorted.isEmpty()) return 0.0
        val idx = min(sorted.size - 1, (q * sorted.size).toInt())
        return sorted[idx]
    }
}
