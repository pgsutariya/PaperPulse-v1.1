package com.example.domain.analysis

import com.example.domain.model.MarketSegment
import com.example.domain.signal.Candle
import com.example.domain.signal.SignalDirection
import com.example.domain.signal.SignalStrategy
import com.example.domain.signal.TechnicalIndicators
import com.example.domain.signal.Timeframe
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Risk:reward ladder used for every crossover-retest setup (1:1 … 1:3). */
val DEFAULT_RR_LADDER: List<Double> = listOf(1.0, 1.25, 1.5, 1.75, 2.0, 2.25, 2.5, 2.75, 3.0)

data class RrTarget(val ratio: Double, val price: Double) {
    val label: String
        get() = "1:" + if (ratio % 1.0 == 0.0) ratio.toInt().toString() else ratio.toString().trimEnd('0')
}

object RiskRewardLadder {
    fun build(
        entry: Double,
        stopLoss: Double,
        direction: SignalDirection,
        ratios: List<Double> = DEFAULT_RR_LADDER
    ): List<RrTarget> {
        val risk = abs(entry - stopLoss)
        if (risk <= 0.0) return emptyList()
        return ratios.map { r ->
            val px = if (direction == SignalDirection.LONG) entry + risk * r else entry - risk * r
            RrTarget(r, px)
        }.filter { it.price > 0.0 }
    }
}

enum class TriggerMode { CROSSOVER_CANDLE, RETEST_CONFIRMATION_CANDLE }

enum class SlMode { STRUCTURAL_WIDE, TIGHT }

/**
 * Trading styles for the EMA crossover → retest → break entry model.
 * 9/21: Scalping, Sniper, Intraday. 50/200: Swing, Short-term, Investing, Long-term.
 */
enum class TradingStyle(
    val label: String,
    val fastPeriod: Int,
    val slowPeriod: Int,
    val timeframes: List<Timeframe>,
    val strategy: SignalStrategy,
    val triggerMode: TriggerMode,
    val slMode: SlMode,
    val maxBarsAfterCross: Int,
    val requireVolumeAboveMa: Boolean,
    val preferKillZone: Boolean,
    val slBufferAtr: Double
) {
    SCALPING("Scalping", 9, 21, listOf(Timeframe.M1, Timeframe.M3), SignalStrategy.EMA_9_21_SCALP,
        TriggerMode.CROSSOVER_CANDLE, SlMode.TIGHT, 25, false, false, 0.10),
    SNIPER("Sniper", 9, 21, listOf(Timeframe.M1, Timeframe.M3, Timeframe.M5), SignalStrategy.EMA_9_21_SNIPER,
        TriggerMode.RETEST_CONFIRMATION_CANDLE, SlMode.TIGHT, 20, true, true, 0.05),
    INTRADAY("Intraday", 9, 21, listOf(Timeframe.M5, Timeframe.M15), SignalStrategy.EMA_9_21_INTRADAY,
        TriggerMode.CROSSOVER_CANDLE, SlMode.STRUCTURAL_WIDE, 30, false, false, 0.15),
    SWING("Swing", 50, 200, listOf(Timeframe.H1, Timeframe.H4), SignalStrategy.EMA_50_200_SWING,
        TriggerMode.CROSSOVER_CANDLE, SlMode.STRUCTURAL_WIDE, 60, false, false, 0.25),
    SHORT_TERM("Short-term", 50, 200, listOf(Timeframe.H4, Timeframe.D1), SignalStrategy.EMA_50_200_SHORT_TERM,
        TriggerMode.CROSSOVER_CANDLE, SlMode.STRUCTURAL_WIDE, 60, false, false, 0.25),
    INVESTING("Investing", 50, 200, listOf(Timeframe.D1), SignalStrategy.EMA_50_200_INVESTING,
        TriggerMode.CROSSOVER_CANDLE, SlMode.STRUCTURAL_WIDE, 80, false, false, 0.30),
    LONG_TERM("Long-term", 50, 200, listOf(Timeframe.W1, Timeframe.MN1), SignalStrategy.EMA_50_200_LONG_TERM,
        TriggerMode.CROSSOVER_CANDLE, SlMode.STRUCTURAL_WIDE, 80, false, false, 0.30);

    val pairLabel: String get() = "EMA $fastPeriod/$slowPeriod"

    companion object {
        fun forTimeframe(tf: Timeframe): List<TradingStyle> = entries.filter { tf in it.timeframes }
    }
}

enum class SetupState(val label: String) {
    AWAITING_RETEST("Crossover marked — waiting for retest of EMA zone"),
    AWAITING_BREAK("Retest done — waiting for close beyond trigger"),
    TRIGGERED("Triggered — entry active"),
    INVALIDATED("Invalidated — structure broken"),
    EXPIRED("Expired — no trigger in time")
}

enum class SetupOutcome { OPEN, STOPPED, TARGET_HIT }

data class EmaCrossSetup(
    val style: TradingStyle,
    val timeframe: Timeframe,
    val direction: SignalDirection,
    val crossIndex: Int,
    val crossHigh: Double,
    val crossLow: Double,
    val retestIndex: Int?,
    /** Lowest low (long) / highest high (short) printed during the retest. */
    val retestExtreme: Double?,
    val triggerLevel: Double,
    val triggerIndex: Int?,
    val state: SetupState,
    val entry: Double?,
    val stopLoss: Double?,
    val targets: List<RrTarget>,
    val volumeConfirmed: Boolean,
    val inKillZone: Boolean,
    val nearestOpposingLevel: Double?,
    val maxRReached: Double,
    val outcome: SetupOutcome,
    val outcomeIndex: Int?,
    val reasons: List<String>
) {
    val risk: Double? get() = if (entry != null && stopLoss != null) abs(entry - stopLoss) else null
}

data class BacktestStats(
    val trades: Int,
    val wins: Int,
    val losses: Int,
    val open: Int,
    val winRate: Double,
    val expectancyR: Double,
    val profitFactor: Double,
    val maxDrawdownR: Double,
    val netR: Double,
    val targetRatio: Double
) {
    val kellyFraction: Double get() = AdvancedIndicators.kellyFraction(winRate, targetRatio)
}

/**
 * EMA crossover → retest → break-of-crossover-candle entry model.
 *
 * Buy (bullish crossover): fast EMA crosses above slow EMA; the crossover candle high is marked.
 * Price must pull back into the fast/slow EMA zone without breaking structure (no close below both
 * the crossover-candle low and the slow EMA). A candle that CLOSES above the trigger (crossover-candle
 * high, or the retest confirmation candle high for Sniper) is the entry. SL goes below the retest
 * swing low or just below the slow EMA. Targets follow the 1:1 … 1:3 ladder. Sell is the mirror image.
 */
object EmaCrossRetestEngine {

    /** Latest setup (pending or triggered) for the style on the given candles, or null if no crossover. */
    fun latest(
        candles: List<Candle>,
        style: TradingStyle,
        timeframe: Timeframe,
        segment: MarketSegment?,
        ratios: List<Double> = DEFAULT_RR_LADDER
    ): EmaCrossSetup? = scan(candles, style, timeframe, segment, ratios, onlyLatest = true).lastOrNull()

    /** Every crossover in the history, each run through the state machine (used for backtests). */
    fun history(
        candles: List<Candle>,
        style: TradingStyle,
        timeframe: Timeframe,
        segment: MarketSegment?,
        ratios: List<Double> = DEFAULT_RR_LADDER
    ): List<EmaCrossSetup> = scan(candles, style, timeframe, segment, ratios, onlyLatest = false)

    fun minimumBars(style: TradingStyle): Int = style.slowPeriod + 5

    private fun scan(
        candles: List<Candle>,
        style: TradingStyle,
        timeframe: Timeframe,
        segment: MarketSegment?,
        ratios: List<Double>,
        onlyLatest: Boolean
    ): List<EmaCrossSetup> {
        if (candles.size < minimumBars(style)) return emptyList()
        val closes = candles.map { it.close }
        val fast = TechnicalIndicators.ema(closes, style.fastPeriod)
        val slow = TechnicalIndicators.ema(closes, style.slowPeriod)
        val atr = TechnicalIndicators.atr(candles, 14)
        val volMa = AdvancedIndicators.volumeSma(candles, 20)
        val swings = AdvancedIndicators.swingPoints(candles, 3, 3)

        val crosses = mutableListOf<Pair<Int, SignalDirection>>()
        for (i in 1 until candles.size) {
            val f0 = fast[i - 1] ?: continue
            val s0 = slow[i - 1] ?: continue
            val f1 = fast[i] ?: continue
            val s1 = slow[i] ?: continue
            if (f0 <= s0 && f1 > s1) crosses.add(i to SignalDirection.LONG)
            if (f0 >= s0 && f1 < s1) crosses.add(i to SignalDirection.SHORT)
        }
        if (crosses.isEmpty()) return emptyList()
        val startK = if (onlyLatest) crosses.lastIndex else 0
        return (startK until crosses.size).map { k ->
            val (crossIdx, dir) = crosses[k]
            val nextCross = crosses.getOrNull(k + 1)?.first ?: candles.size
            runStateMachine(candles, crossIdx, dir, nextCross, style, timeframe, segment, fast, slow, atr, volMa, swings, ratios)
        }
    }

    @Suppress("LongParameterList")
    private fun runStateMachine(
        candles: List<Candle>,
        crossIdx: Int,
        dir: SignalDirection,
        endExclusive: Int,
        style: TradingStyle,
        timeframe: Timeframe,
        segment: MarketSegment?,
        fast: List<Double?>,
        slow: List<Double?>,
        atr: List<Double?>,
        volMa: List<Double?>,
        swings: List<SwingPoint>,
        ratios: List<Double>
    ): EmaCrossSetup {
        val isLong = dir == SignalDirection.LONG
        val cc = candles[crossIdx]
        val crossHigh = cc.high
        val crossLow = cc.low
        val fallbackAtr = (cc.high - cc.low).coerceAtLeast(cc.close * 0.0005)

        var state = SetupState.AWAITING_RETEST
        var retestIdx: Int? = null
        var retestExtreme: Double? = null
        var triggerLevel = if (isLong) crossHigh else crossLow
        var triggerIdx: Int? = null
        var entry: Double? = null
        var sl: Double? = null
        var volumeOk = false
        val reasons = mutableListOf<String>()

        val last = endExclusive - 1
        var j = crossIdx + 1
        while (j <= last && state != SetupState.TRIGGERED) {
            val c = candles[j]
            val f = fast[j]
            val s = slow[j]
            val a = atr[j] ?: fallbackAtr
            if (f == null || s == null) { j++; continue }
            if (j - crossIdx > style.maxBarsAfterCross) {
                state = SetupState.EXPIRED
                break
            }
            // Structure break invalidates the setup at any time before the trigger.
            val structureBroken = if (isLong) {
                c.close < crossLow && c.close < s - 0.1 * a
            } else {
                c.close > crossHigh && c.close > s + 0.1 * a
            }
            if (structureBroken) {
                state = SetupState.INVALIDATED
                break
            }
            val zoneTop = max(f, s) + 0.1 * a
            val zoneBottom = min(f, s) - 0.1 * a
            val touchesZone = if (isLong) c.low <= zoneTop else c.high >= zoneBottom

            when (state) {
                SetupState.AWAITING_RETEST -> {
                    if (touchesZone) {
                        state = SetupState.AWAITING_BREAK
                        retestIdx = j
                        retestExtreme = if (isLong) c.low else c.high
                        if (style.triggerMode == TriggerMode.RETEST_CONFIRMATION_CANDLE) {
                            triggerLevel = if (isLong) c.high else c.low
                        }
                    }
                }
                SetupState.AWAITING_BREAK -> {
                    val broke = if (isLong) c.close > triggerLevel else c.close < triggerLevel
                    if (broke) {
                        val ma = volMa[j]
                        volumeOk = ma != null && ma > 0.0 && c.volume > ma
                        if (style.requireVolumeAboveMa && !volumeOk) {
                            // Sniper needs participation; keep waiting for a confirming candle.
                            retestExtreme = updateExtreme(retestExtreme, c, isLong)
                        } else {
                            state = SetupState.TRIGGERED
                            triggerIdx = j
                            entry = c.close
                            val structural = retestExtreme ?: if (isLong) c.low else c.high
                            val bufferPx = style.slBufferAtr * a
                            val slFromStructure = if (isLong) structural - bufferPx else structural + bufferPx
                            val slFromEma = if (isLong) s - bufferPx else s + bufferPx
                            val chosen = when (style.slMode) {
                                SlMode.STRUCTURAL_WIDE -> if (isLong) min(slFromStructure, slFromEma) else max(slFromStructure, slFromEma)
                                SlMode.TIGHT -> {
                                    val tighter = if (isLong) max(slFromStructure, slFromEma) else min(slFromStructure, slFromEma)
                                    val valid = if (isLong) tighter < c.close else tighter > c.close
                                    if (valid) tighter else if (isLong) min(slFromStructure, slFromEma) else max(slFromStructure, slFromEma)
                                }
                            }
                            // Minimum risk floor of 0.2×ATR so targets are not inside the spread/noise.
                            val minRisk = 0.2 * a
                            sl = if (isLong) min(chosen, c.close - minRisk) else max(chosen, c.close + minRisk)
                        }
                    } else {
                        if (style.triggerMode == TriggerMode.RETEST_CONFIRMATION_CANDLE && touchesZone) {
                            // Deeper retest: confirmation candle moves to the newest retest bar.
                            triggerLevel = if (isLong) c.high else c.low
                        }
                        retestExtreme = updateExtreme(retestExtreme, c, isLong)
                    }
                }
                else -> Unit
            }
            j++
        }
        if ((state == SetupState.AWAITING_RETEST || state == SetupState.AWAITING_BREAK) && endExclusive < candles.size) {
            // An opposite crossover printed before the trigger.
            state = SetupState.INVALIDATED
        }

        val e = entry
        val stop = sl
        val targets = if (e != null && stop != null) RiskRewardLadder.build(e, stop, dir, ratios) else emptyList()

        // Post-trigger tracking: R reached, stop-out or final target.
        var maxR = 0.0
        var outcome = SetupOutcome.OPEN
        var outcomeIdx: Int? = null
        val tIdx = triggerIdx
        if (e != null && stop != null && tIdx != null) {
            val risk = abs(e - stop)
            val finalTarget = targets.lastOrNull()?.price
            for (k in (tIdx + 1) until candles.size) {
                val c = candles[k]
                val hitSl = if (isLong) c.low <= stop else c.high >= stop
                val favourable = if (isLong) c.high - e else e - c.low
                if (hitSl) {
                    outcome = SetupOutcome.STOPPED
                    outcomeIdx = k
                    break
                }
                if (risk > 0) maxR = max(maxR, favourable / risk)
                if (finalTarget != null && (if (isLong) c.high >= finalTarget else c.low <= finalTarget)) {
                    outcome = SetupOutcome.TARGET_HIT
                    outcomeIdx = k
                    break
                }
            }
        }

        val endTs = candles[min(last, candles.lastIndex)].timestamp.toEpochMilli()
        val kz = SessionCalendar.activeKillZone(endTs, segment)
        val nearest = nearestOpposingSwing(swings, candles, isLong, e ?: candles[min(last, candles.lastIndex)].close)

        reasons.add("${style.pairLabel} ${if (isLong) "bullish" else "bearish"} crossover on ${timeframe.label} — crossover candle ${if (isLong) "high" else "low"} ${fmt(if (isLong) crossHigh else crossLow)}")
        retestIdx?.let { reasons.add("Retest of EMA ${style.fastPeriod}/${style.slowPeriod} zone, swing ${if (isLong) "low" else "high"} ${fmt(retestExtreme ?: 0.0)}") }
        if (state == SetupState.TRIGGERED) {
            reasons.add("Close ${if (isLong) "above" else "below"} trigger ${fmt(triggerLevel)} (${if (style.triggerMode == TriggerMode.CROSSOVER_CANDLE) "crossover candle" else "retest confirmation candle"})")
            reasons.add("SL ${fmt(stop ?: 0.0)} (${if (isLong) "below retest low / slow EMA" else "above retest high / slow EMA"})")
            reasons.add(if (volumeOk) "Breakout volume above 20-bar volume MA" else "Breakout volume below 20-bar MA (lower conviction)")
        }
        if (style.preferKillZone) {
            reasons.add(if (kz != null) "Inside ${kz.name} kill zone" else "Outside kill zones (reduce size)")
        }
        nearest?.let { reasons.add("Nearest ${if (isLong) "resistance" else "support"} ${fmt(it)} — consider partial exit there or on opposite crossover") }

        return EmaCrossSetup(
            style = style,
            timeframe = timeframe,
            direction = dir,
            crossIndex = crossIdx,
            crossHigh = crossHigh,
            crossLow = crossLow,
            retestIndex = retestIdx,
            retestExtreme = retestExtreme,
            triggerLevel = triggerLevel,
            triggerIndex = triggerIdx,
            state = state,
            entry = e,
            stopLoss = stop,
            targets = targets,
            volumeConfirmed = volumeOk,
            inKillZone = kz != null,
            nearestOpposingLevel = nearest,
            maxRReached = maxR,
            outcome = outcome,
            outcomeIndex = outcomeIdx,
            reasons = reasons
        )
    }

    private fun updateExtreme(current: Double?, c: Candle, isLong: Boolean): Double =
        if (isLong) min(current ?: c.low, c.low) else max(current ?: c.high, c.high)

    private fun nearestOpposingSwing(swings: List<SwingPoint>, candles: List<Candle>, isLong: Boolean, ref: Double): Double? {
        val from = max(0, candles.size - 200)
        return if (isLong) {
            swings.filter { it.isHigh && it.index >= from && it.price > ref }.minByOrNull { it.price }?.price
        } else {
            swings.filter { !it.isHigh && it.index >= from && it.price < ref }.maxByOrNull { it.price }?.price
        }
    }

    /**
     * Backtests every historical crossover-retest trigger with a fixed R target: a trade wins if the
     * target is reached before the stop (stop assumed first when both fall inside the same candle).
     */
    fun backtest(candles: List<Candle>, setups: List<EmaCrossSetup>, targetRatio: Double): BacktestStats {
        var wins = 0
        var losses = 0
        var openTrades = 0
        var equity = 0.0
        var peak = 0.0
        var maxDd = 0.0
        var grossWin = 0.0
        var grossLoss = 0.0
        setups.filter { it.state == SetupState.TRIGGERED }.forEach { s ->
            val e = s.entry ?: return@forEach
            val stop = s.stopLoss ?: return@forEach
            val t = s.triggerIndex ?: return@forEach
            val isLong = s.direction == SignalDirection.LONG
            val risk = abs(e - stop)
            if (risk <= 0) return@forEach
            val target = if (isLong) e + risk * targetRatio else e - risk * targetRatio
            var result: Double? = null
            for (k in (t + 1) until candles.size) {
                val c = candles[k]
                val slHit = if (isLong) c.low <= stop else c.high >= stop
                val tgHit = if (isLong) c.high >= target else c.low <= target
                if (slHit) { result = -1.0; break }
                if (tgHit) { result = targetRatio; break }
            }
            when {
                result == null -> openTrades++
                result > 0 -> { wins++; grossWin += result }
                else -> { losses++; grossLoss += -result }
            }
            if (result != null) {
                equity += result
                peak = max(peak, equity)
                maxDd = max(maxDd, peak - equity)
            }
        }
        val closed = wins + losses
        val winRate = if (closed > 0) wins.toDouble() / closed else 0.0
        return BacktestStats(
            trades = closed + openTrades,
            wins = wins,
            losses = losses,
            open = openTrades,
            winRate = winRate,
            expectancyR = if (closed > 0) (grossWin - grossLoss) / closed else 0.0,
            profitFactor = if (grossLoss > 0) grossWin / grossLoss else if (grossWin > 0) Double.POSITIVE_INFINITY else 0.0,
            maxDrawdownR = maxDd,
            netR = equity,
            targetRatio = targetRatio
        )
    }

    private fun fmt(v: Double): String = String.format(java.util.Locale.US, "%.2f", v)
}
