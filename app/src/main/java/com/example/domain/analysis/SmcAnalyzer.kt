package com.example.domain.analysis

import com.example.domain.model.MarketSegment
import com.example.domain.signal.Candle
import com.example.domain.signal.TechnicalIndicators
import com.example.domain.signal.Timeframe
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class StructureTrend { BULLISH, BEARISH, RANGING }

data class StructureBreak(
    val index: Int,
    val level: Double,
    val swingIndex: Int,
    val bullish: Boolean,
    /** true = Change of Character (first break against the prior trend), false = Break of Structure. */
    val isChoch: Boolean
) {
    val label: String get() = (if (isChoch) "CHoCH" else "BOS") + if (bullish) " ↑" else " ↓"
}

enum class ZoneKind(val label: String) {
    DEMAND_OB("Demand Order Block"),
    SUPPLY_OB("Supply Order Block"),
    DEMAND_BASE("Demand Zone (base)"),
    SUPPLY_BASE("Supply Zone (base)"),
    BULLISH_FVG("Bullish FVG"),
    BEARISH_FVG("Bearish FVG")
}

data class PriceZone(
    val kind: ZoneKind,
    val top: Double,
    val bottom: Double,
    val originIndex: Int,
    val mitigated: Boolean,
    val invalidated: Boolean,
    /** Displacement leg left imbalance and above-average volume: likely institutional footprint. */
    val institutional: Boolean
) {
    val isBullish: Boolean
        get() = kind == ZoneKind.DEMAND_OB || kind == ZoneKind.DEMAND_BASE || kind == ZoneKind.BULLISH_FVG
    val mid: Double get() = (top + bottom) / 2.0
    val isActive: Boolean get() = !invalidated
    fun contains(price: Double) = price in bottom..top
}

enum class LiquiditySide(val label: String) { BUY_SIDE("BSL (above highs)"), SELL_SIDE("SSL (below lows)") }

enum class LiquidityStatus(val label: String) {
    PENDING("Pending"),
    SWEPT("Swept (stop-hunt)"),
    BROKEN("Broken (accepted)")
}

data class LiquidityLevel(
    val side: LiquiditySide,
    val price: Double,
    val sourceIndices: List<Int>,
    val name: String,
    val status: LiquidityStatus,
    val sweptAtIndex: Int?
) {
    val isEqualLevel: Boolean get() = sourceIndices.size >= 2
}

data class DealingRange(
    val high: Double,
    val low: Double,
    val highIndex: Int,
    val lowIndex: Int
) {
    val equilibrium: Double get() = (high + low) / 2.0
    /** Optimal Trade Entry band (62%–79% retracement) for longs (from the high down). */
    val oteLongTop: Double get() = high - (high - low) * 0.62
    val oteLongBottom: Double get() = high - (high - low) * 0.79
    val oteShortBottom: Double get() = low + (high - low) * 0.62
    val oteShortTop: Double get() = low + (high - low) * 0.79

    fun zoneOf(price: Double): String = when {
        price > high || price < low -> "Outside range"
        price >= equilibrium + (high - low) * 0.05 -> "Premium (sell zone)"
        price <= equilibrium - (high - low) * 0.05 -> "Discount (buy zone)"
        else -> "Equilibrium"
    }

    fun isDiscount(price: Double) = price < equilibrium
    fun isPremium(price: Double) = price > equilibrium
}

enum class AmdPhase(val label: String) {
    ACCUMULATION("Accumulation"),
    MANIPULATION_UP("Manipulation ↑ (Judas swing — bearish bias)"),
    MANIPULATION_DOWN("Manipulation ↓ (Judas swing — bullish bias)"),
    DISTRIBUTION_UP("Distribution ↑ (expansion)"),
    DISTRIBUTION_DOWN("Distribution ↓ (expansion)"),
    UNKNOWN("Insufficient data")
}

data class AmdState(
    val phase: AmdPhase,
    val accumulationHigh: Double,
    val accumulationLow: Double,
    val bias: StructureTrend,
    val note: String
)

enum class MdrStage(val label: String) {
    WAITING("Waiting for manipulation"),
    MANIPULATED("Liquidity swept — waiting for displacement"),
    DISPLACED("Displacement printed — waiting for rebalance"),
    REBALANCED("Rebalance into FVG — entry window")
}

data class MdrState(
    val stage: MdrStage,
    val bullish: Boolean?,
    val sweptLevel: Double?,
    val sweepIndex: Int?,
    val displacementIndex: Int?,
    val rebalanceZone: PriceZone?,
    val inKillZone: Boolean,
    val killZoneName: String?
)

enum class TrapKind(val label: String) {
    BULL_TRAP("Bull trap (failed breakout above high)"),
    BEAR_TRAP("Bear trap (failed breakdown below low)")
}

data class TrapEvent(val kind: TrapKind, val level: Double, val breakIndex: Int, val trapIndex: Int)

enum class BreakoutKind(val label: String) {
    BREAKOUT_UP("Range breakout ↑ (volume confirmed)"),
    BREAKOUT_DOWN("Range breakdown ↓ (volume confirmed)"),
    REVERSAL_UP("Reversal ↑ (sweep + CHoCH)"),
    REVERSAL_DOWN("Reversal ↓ (sweep + CHoCH)")
}

data class BreakoutEvent(val kind: BreakoutKind, val index: Int, val level: Double)

data class SmcSnapshot(
    val trend: StructureTrend,
    val structureBreaks: List<StructureBreak>,
    val swings: List<SwingPoint>,
    val zones: List<PriceZone>,
    val liquidity: List<LiquidityLevel>,
    val dealingRange: DealingRange?,
    val amd: AmdState,
    val mdr: MdrState,
    val traps: List<TrapEvent>,
    val breakouts: List<BreakoutEvent>,
    val pointsOfInterest: List<PriceZone>
) {
    val activeDemand: List<PriceZone> get() = zones.filter { it.isActive && it.isBullish }
    val activeSupply: List<PriceZone> get() = zones.filter { it.isActive && !it.isBullish }
}

/**
 * Smart Money Concepts / ICT toolkit: market structure (BOS/CHoCH), order blocks, supply/demand
 * bases, fair value gaps, liquidity pools (equal highs/lows, PDH/PDL) with swept/pending state,
 * premium/discount dealing range, AMD session framing, MDR kill-zone model, traps and
 * breakout/reversal events.
 */
object SmcAnalyzer {

    fun analyze(
        candles: List<Candle>,
        timeframe: Timeframe,
        segment: MarketSegment?,
        swingStrength: Int = 3
    ): SmcSnapshot {
        val empty = SmcSnapshot(
            trend = StructureTrend.RANGING,
            structureBreaks = emptyList(),
            swings = emptyList(),
            zones = emptyList(),
            liquidity = emptyList(),
            dealingRange = null,
            amd = AmdState(AmdPhase.UNKNOWN, 0.0, 0.0, StructureTrend.RANGING, "Need more candles"),
            mdr = MdrState(MdrStage.WAITING, null, null, null, null, null, false, null),
            traps = emptyList(),
            breakouts = emptyList(),
            pointsOfInterest = emptyList()
        )
        if (candles.size < 30) return empty

        val atr = TechnicalIndicators.atr(candles, 14)
        val avgAtr = atr.filterNotNull().takeLast(50).average().takeIf { !it.isNaN() && it > 0.0 }
            ?: (candles.takeLast(20).map { it.high - it.low }.average())
        val swings = AdvancedIndicators.swingPoints(candles, swingStrength, swingStrength)
        val (trend, breaks) = marketStructure(candles, swings, swingStrength)
        val volMa = AdvancedIndicators.volumeSma(candles, 20)
        val fvgs = fairValueGaps(candles, avgAtr * 0.1)
        val obs = orderBlocks(candles, swings, breaks, fvgs, volMa)
        val bases = baseZones(candles, atr, volMa)
        val zones = (obs + bases + fvgs).sortedBy { it.originIndex }
        val liquidity = liquidityLevels(candles, swings, segment, timeframe, avgAtr)
        val range = dealingRange(swings, candles)
        val amd = amd(candles, timeframe, segment)
        val traps = traps(candles, swings)
        val breakouts = breakouts(candles, breaks, liquidity, volMa)
        val mdr = mdr(candles, swings, fvgs, atr, segment, avgAtr)
        val lastClose = candles.last().close
        val poi = zones.filter { z ->
            z.isActive && !z.mitigated && range != null &&
                ((z.isBullish && z.top <= lastClose && range.isDiscount(z.mid)) ||
                    (!z.isBullish && z.bottom >= lastClose && range.isPremium(z.mid)))
        }.sortedBy { abs(it.mid - lastClose) }.take(6)

        return SmcSnapshot(
            trend = trend,
            structureBreaks = breaks,
            swings = swings,
            zones = zones,
            liquidity = liquidity,
            dealingRange = range,
            amd = amd,
            mdr = mdr,
            traps = traps,
            breakouts = breakouts,
            pointsOfInterest = poi
        )
    }

    /** Walks bars in order; swing points only become usable once confirmed (index + strength). */
    fun marketStructure(
        candles: List<Candle>,
        swings: List<SwingPoint>,
        swingStrength: Int
    ): Pair<StructureTrend, List<StructureBreak>> {
        val breaks = mutableListOf<StructureBreak>()
        var trend = StructureTrend.RANGING
        var lastHigh: SwingPoint? = null
        var lastLow: SwingPoint? = null
        var highBroken = false
        var lowBroken = false
        val byConfirm = swings.groupBy { it.index + swingStrength }
        for (i in candles.indices) {
            byConfirm[i]?.forEach { sp ->
                if (sp.isHigh) {
                    lastHigh = sp
                    highBroken = false
                } else {
                    lastLow = sp
                    lowBroken = false
                }
            }
            val close = candles[i].close
            val h = lastHigh
            if (h != null && !highBroken && close > h.price) {
                breaks.add(StructureBreak(i, h.price, h.index, bullish = true, isChoch = trend == StructureTrend.BEARISH))
                trend = StructureTrend.BULLISH
                highBroken = true
            }
            val l = lastLow
            if (l != null && !lowBroken && close < l.price) {
                breaks.add(StructureBreak(i, l.price, l.index, bullish = false, isChoch = trend == StructureTrend.BULLISH))
                trend = StructureTrend.BEARISH
                lowBroken = true
            }
        }
        return trend to breaks
    }

    fun fairValueGaps(candles: List<Candle>, minSize: Double): List<PriceZone> {
        val out = mutableListOf<PriceZone>()
        for (i in 2 until candles.size) {
            val a = candles[i - 2]
            val c = candles[i]
            if (c.low > a.high && c.low - a.high >= minSize) {
                val top = c.low
                val bottom = a.high
                var touched = false
                var filled = false
                for (j in (i + 1) until candles.size) {
                    if (candles[j].low <= top) touched = true
                    if (candles[j].low <= bottom) {
                        filled = true
                        break
                    }
                }
                out.add(PriceZone(ZoneKind.BULLISH_FVG, top, bottom, i - 1, touched, filled, institutional = false))
            } else if (c.high < a.low && a.low - c.high >= minSize) {
                val top = a.low
                val bottom = c.high
                var touched = false
                var filled = false
                for (j in (i + 1) until candles.size) {
                    if (candles[j].high >= bottom) touched = true
                    if (candles[j].high >= top) {
                        filled = true
                        break
                    }
                }
                out.add(PriceZone(ZoneKind.BEARISH_FVG, top, bottom, i - 1, touched, filled, institutional = false))
            }
        }
        return out
    }

    private fun orderBlocks(
        candles: List<Candle>,
        swings: List<SwingPoint>,
        breaks: List<StructureBreak>,
        fvgs: List<PriceZone>,
        volMa: List<Double?>
    ): List<PriceZone> {
        val out = mutableListOf<PriceZone>()
        val seen = HashSet<Int>()
        breaks.forEach { br ->
            val from = br.swingIndex
            val to = br.index
            if (to - from < 1) return@forEach
            // Origin of the displacement leg: extreme opposite to the break direction.
            val originIdx = if (br.bullish) {
                (from..to).minByOrNull { candles[it].low } ?: return@forEach
            } else {
                (from..to).maxByOrNull { candles[it].high } ?: return@forEach
            }
            var obIdx = -1
            for (k in originIdx downTo max(0, originIdx - 5)) {
                val c = candles[k]
                if (br.bullish && c.close < c.open) { obIdx = k; break }
                if (!br.bullish && c.close > c.open) { obIdx = k; break }
            }
            if (obIdx < 0) obIdx = originIdx
            if (!seen.add(obIdx * 2 + (if (br.bullish) 1 else 0))) return@forEach
            val ob = candles[obIdx]
            val top = ob.high
            val bottom = ob.low
            var mitigated = false
            var invalid = false
            for (j in (to + 1) until candles.size) {
                val c = candles[j]
                if (br.bullish) {
                    if (c.low <= top) mitigated = true
                    if (c.close < bottom) { invalid = true; break }
                } else {
                    if (c.high >= bottom) mitigated = true
                    if (c.close > top) { invalid = true; break }
                }
            }
            val legHasFvg = fvgs.any { it.originIndex in obIdx..to && it.isBullish == br.bullish }
            val legVolumeHigh = (obIdx + 1..to).any { j ->
                val ma = volMa.getOrNull(j)
                ma != null && ma > 0.0 && candles[j].volume > ma * 1.2
            }
            out.add(
                PriceZone(
                    kind = if (br.bullish) ZoneKind.DEMAND_OB else ZoneKind.SUPPLY_OB,
                    top = top,
                    bottom = bottom,
                    originIndex = obIdx,
                    mitigated = mitigated,
                    invalidated = invalid,
                    institutional = legHasFvg && legVolumeHigh
                )
            )
        }
        // keep only zones that relate to known swings range (avoid noise when there are no swings)
        return if (swings.isEmpty()) emptyList() else out
    }

    /** Rally/Drop–Base–Rally/Drop supply & demand bases: 1–3 small-body candles then a displacement candle. */
    private fun baseZones(candles: List<Candle>, atr: List<Double?>, volMa: List<Double?>): List<PriceZone> {
        val out = mutableListOf<PriceZone>()
        var i = 3
        while (i < candles.size) {
            val disp = candles[i]
            val a = atr[i]
            if (a == null) { i++; continue }
            val body = abs(disp.close - disp.open)
            if (body < 1.3 * a) { i++; continue }
            var baseStart = i
            for (k in (i - 1) downTo max(0, i - 3)) {
                val ck = candles[k]
                if (abs(ck.close - ck.open) <= 0.5 * (ck.high - ck.low).coerceAtLeast(1e-12) &&
                    (ck.high - ck.low) <= 1.2 * a
                ) baseStart = k else break
            }
            if (baseStart == i) { i++; continue }
            val base = candles.subList(baseStart, i)
            val top = base.maxOf { it.high }
            val bottom = base.minOf { it.low }
            val bullish = disp.close > disp.open
            var mitigated = false
            var invalid = false
            for (j in (i + 1) until candles.size) {
                val c = candles[j]
                if (bullish) {
                    if (c.low <= top) mitigated = true
                    if (c.close < bottom) { invalid = true; break }
                } else {
                    if (c.high >= bottom) mitigated = true
                    if (c.close > top) { invalid = true; break }
                }
            }
            val ma = volMa.getOrNull(i)
            out.add(
                PriceZone(
                    kind = if (bullish) ZoneKind.DEMAND_BASE else ZoneKind.SUPPLY_BASE,
                    top = top,
                    bottom = bottom,
                    originIndex = baseStart,
                    mitigated = mitigated,
                    invalidated = invalid,
                    institutional = ma != null && ma > 0.0 && disp.volume > 1.5 * ma
                )
            )
            i++
        }
        return out
    }

    private fun liquidityLevels(
        candles: List<Candle>,
        swings: List<SwingPoint>,
        segment: MarketSegment?,
        timeframe: Timeframe,
        avgAtr: Double
    ): List<LiquidityLevel> {
        val tol = avgAtr * 0.15
        val out = mutableListOf<LiquidityLevel>()
        val recentSwings = swings.filter { it.index >= candles.size - 150 }

        fun statusFor(level: Double, buySide: Boolean, afterIdx: Int): Pair<LiquidityStatus, Int?> {
            for (j in (afterIdx + 1) until candles.size) {
                val c = candles[j]
                if (buySide && c.high > level) {
                    return if (c.close <= level) LiquidityStatus.SWEPT to j else LiquidityStatus.BROKEN to j
                }
                if (!buySide && c.low < level) {
                    return if (c.close >= level) LiquidityStatus.SWEPT to j else LiquidityStatus.BROKEN to j
                }
            }
            return LiquidityStatus.PENDING to null
        }

        fun cluster(points: List<SwingPoint>, buySide: Boolean) {
            val used = BooleanArray(points.size)
            for (a in points.indices) {
                if (used[a]) continue
                val group = mutableListOf(points[a])
                for (b in (a + 1) until points.size) {
                    if (!used[b] && abs(points[b].price - points[a].price) <= tol) {
                        group.add(points[b])
                        used[b] = true
                    }
                }
                val level = if (buySide) group.maxOf { it.price } else group.minOf { it.price }
                val lastIdx = group.maxOf { it.index }
                val (status, at) = statusFor(level, buySide, lastIdx)
                val name = when {
                    group.size >= 2 && buySide -> "Equal Highs"
                    group.size >= 2 -> "Equal Lows"
                    buySide -> "Swing High"
                    else -> "Swing Low"
                }
                out.add(LiquidityLevel(if (buySide) LiquiditySide.BUY_SIDE else LiquiditySide.SELL_SIDE, level, group.map { it.index }, name, status, at))
            }
        }
        cluster(recentSwings.filter { it.isHigh }, buySide = true)
        cluster(recentSwings.filter { !it.isHigh }, buySide = false)

        // Previous-day high / low (intraday charts) — the classic session liquidity draws.
        if (timeframe.isIntraday) {
            val lastKey = SessionCalendar.sessionDayKey(candles.last().timestamp.toEpochMilli(), segment)
            val prevDay = candles.indices.filter {
                SessionCalendar.sessionDayKey(candles[it].timestamp.toEpochMilli(), segment) < lastKey
            }
            if (prevDay.isNotEmpty()) {
                val prevKey = SessionCalendar.sessionDayKey(candles[prevDay.last()].timestamp.toEpochMilli(), segment)
                val idx = prevDay.filter {
                    SessionCalendar.sessionDayKey(candles[it].timestamp.toEpochMilli(), segment) == prevKey
                }
                val pdhIdx = idx.maxByOrNull { candles[it].high }
                val pdlIdx = idx.minByOrNull { candles[it].low }
                if (pdhIdx != null) {
                    val lvl = candles[pdhIdx].high
                    val (st, at) = statusFor(lvl, true, idx.last())
                    out.add(LiquidityLevel(LiquiditySide.BUY_SIDE, lvl, listOf(pdhIdx), "PDH", st, at))
                }
                if (pdlIdx != null) {
                    val lvl = candles[pdlIdx].low
                    val (st, at) = statusFor(lvl, false, idx.last())
                    out.add(LiquidityLevel(LiquiditySide.SELL_SIDE, lvl, listOf(pdlIdx), "PDL", st, at))
                }
            }
        }
        return out
    }

    private fun dealingRange(swings: List<SwingPoint>, candles: List<Candle>): DealingRange? {
        val lookbackStart = max(0, candles.size - 120)
        val highs = swings.filter { it.isHigh && it.index >= lookbackStart }
        val lows = swings.filter { !it.isHigh && it.index >= lookbackStart }
        val hi = highs.maxByOrNull { it.price }
        val lo = lows.minByOrNull { it.price }
        if (hi != null && lo != null && hi.price > lo.price) {
            // Extend with any price beyond the swings since then (range must contain current price action)
            val tailHigh = candles.subList(lookbackStart, candles.size).maxOf { it.high }
            val tailLow = candles.subList(lookbackStart, candles.size).minOf { it.low }
            return DealingRange(max(hi.price, tailHigh), min(lo.price, tailLow), hi.index, lo.index)
        }
        val window = candles.subList(lookbackStart, candles.size)
        val hiIdx = window.indices.maxByOrNull { window[it].high } ?: return null
        val loIdx = window.indices.minByOrNull { window[it].low } ?: return null
        if (window[hiIdx].high <= window[loIdx].low) return null
        return DealingRange(window[hiIdx].high, window[loIdx].low, hiIdx + lookbackStart, loIdx + lookbackStart)
    }

    /**
     * AMD framing for the current period (trading day for intraday charts, week for daily charts,
     * month for weekly charts). The first quarter of the period's bars defines accumulation.
     */
    fun amd(candles: List<Candle>, timeframe: Timeframe, segment: MarketSegment?): AmdState {
        val zone = SessionCalendar.zoneFor(segment)
        fun key(c: Candle): Long {
            val d = c.timestamp.atZone(zone).toLocalDate()
            return when {
                timeframe.isIntraday -> d.toEpochDay()
                timeframe == Timeframe.D1 -> Math.floorDiv(d.toEpochDay() + 3, 7L)
                else -> d.year * 12L + d.monthValue
            }
        }
        val lastKey = key(candles.last())
        val period = candles.filter { key(it) == lastKey }
        val expectedBars: Int = when {
            timeframe.isIntraday -> {
                val sessionMinutes = when (segment) {
                    MarketSegment.NSE, MarketSegment.BSE -> 375L
                    MarketSegment.MCX -> 870L
                    else -> 1440L
                }
                (sessionMinutes * 60_000L / timeframe.durationMillis).toInt().coerceAtLeast(4)
            }
            timeframe == Timeframe.D1 -> if (segment == MarketSegment.CRYPTO) 7 else 5
            else -> 4
        }
        val accBars = (expectedBars / 4).coerceAtLeast(1)
        if (period.size <= accBars) {
            val accH = period.maxOfOrNull { it.high } ?: 0.0
            val accL = period.minOfOrNull { it.low } ?: 0.0
            return AmdState(AmdPhase.ACCUMULATION, accH, accL, StructureTrend.RANGING, "Building accumulation range (${period.size}/$accBars bars)")
        }
        val acc = period.take(accBars)
        val accH = acc.maxOf { it.high }
        val accL = acc.minOf { it.low }
        val rest = period.drop(accBars)
        val sweptHighIdx = rest.indexOfFirst { it.high > accH }
        val sweptLowIdx = rest.indexOfFirst { it.low < accL }
        val last = period.last()
        val accRange = (accH - accL).coerceAtLeast(1e-9)
        return when {
            sweptHighIdx >= 0 && (sweptLowIdx < 0 || sweptHighIdx < sweptLowIdx) -> {
                // Took buy-side first. If price is back inside / below → manipulation up, bearish distribution expected.
                when {
                    last.close < accL - accRange * 0.25 -> AmdState(AmdPhase.DISTRIBUTION_DOWN, accH, accL, StructureTrend.BEARISH, "Judas swing above range, now expanding lower")
                    last.close <= accH -> AmdState(AmdPhase.MANIPULATION_UP, accH, accL, StructureTrend.BEARISH, "Buy-side swept above accumulation high and rejected")
                    else -> AmdState(AmdPhase.DISTRIBUTION_UP, accH, accL, StructureTrend.BULLISH, "Accepted above accumulation — bullish expansion")
                }
            }
            sweptLowIdx >= 0 -> {
                when {
                    last.close > accH + accRange * 0.25 -> AmdState(AmdPhase.DISTRIBUTION_UP, accH, accL, StructureTrend.BULLISH, "Judas swing below range, now expanding higher")
                    last.close >= accL -> AmdState(AmdPhase.MANIPULATION_DOWN, accH, accL, StructureTrend.BULLISH, "Sell-side swept below accumulation low and rejected")
                    else -> AmdState(AmdPhase.DISTRIBUTION_DOWN, accH, accL, StructureTrend.BEARISH, "Accepted below accumulation — bearish expansion")
                }
            }
            else -> AmdState(AmdPhase.ACCUMULATION, accH, accL, StructureTrend.RANGING, "Still inside accumulation range")
        }
    }

    /**
     * MDR (Manipulation → Displacement → Rebalance) on the most recent bars:
     * 1. Manipulation: a wick sweeps a recent swing high/low and closes back inside.
     * 2. Displacement: within 6 bars a large-bodied candle (≥ 1.5×ATR) moves away, leaving an FVG.
     * 3. Rebalance: price retraces into that FVG — this is the entry window.
     */
    fun mdr(
        candles: List<Candle>,
        swings: List<SwingPoint>,
        fvgs: List<PriceZone>,
        atr: List<Double?>,
        segment: MarketSegment?,
        avgAtr: Double
    ): MdrState {
        val lastTs = candles.last().timestamp.toEpochMilli()
        val kz = SessionCalendar.activeKillZone(lastTs, segment)
        val start = max(0, candles.size - 40)
        var best: MdrState? = null
        for (i in start until candles.size) {
            val c = candles[i]
            val priorHighs = swings.filter { it.isHigh && it.index < i - 1 && it.index >= i - 60 }
            val priorLows = swings.filter { !it.isHigh && it.index < i - 1 && it.index >= i - 60 }
            val sweptHigh = priorHighs.filter { c.high > it.price && c.close < it.price }.maxByOrNull { it.price }
            val sweptLow = priorLows.filter { c.low < it.price && c.close > it.price }.minByOrNull { it.price }
            val bullish: Boolean
            val level: Double
            when {
                sweptLow != null -> { bullish = true; level = sweptLow.price }
                sweptHigh != null -> { bullish = false; level = sweptHigh.price }
                else -> continue
            }
            var state = MdrState(MdrStage.MANIPULATED, bullish, level, i, null, null, kz != null, kz?.name)
            for (j in (i + 1)..minOf(i + 6, candles.lastIndex)) {
                val d = candles[j]
                val a = atr[j] ?: avgAtr
                val body = d.close - d.open
                val displaced = if (bullish) body >= 1.5 * a else -body >= 1.5 * a
                if (displaced) {
                    val gap = fvgs.firstOrNull { it.isBullish == bullish && it.originIndex in (j - 1)..(j + 1) }
                    state = state.copy(stage = MdrStage.DISPLACED, displacementIndex = j, rebalanceZone = gap)
                    if (gap != null) {
                        val touched = (j + 2..candles.lastIndex).any { k ->
                            if (bullish) candles[k].low <= gap.top else candles[k].high >= gap.bottom
                        }
                        if (touched && !gap.invalidated) state = state.copy(stage = MdrStage.REBALANCED)
                    }
                    break
                }
            }
            best = state
        }
        return best ?: MdrState(MdrStage.WAITING, null, null, null, null, null, kz != null, kz?.name)
    }

    /** Failed breakouts: close beyond a swing level, then a close back through it within 3 bars. */
    fun traps(candles: List<Candle>, swings: List<SwingPoint>): List<TrapEvent> {
        val out = mutableListOf<TrapEvent>()
        val start = max(0, candles.size - 120)
        swings.filter { it.index >= start - 60 }.forEach { sp ->
            val from = sp.index + 1
            for (i in from until candles.size) {
                val c = candles[i]
                if (sp.isHigh && c.close > sp.price) {
                    val trapIdx = (i + 1..minOf(i + 3, candles.lastIndex)).firstOrNull { candles[it].close < sp.price }
                    if (trapIdx != null) out.add(TrapEvent(TrapKind.BULL_TRAP, sp.price, i, trapIdx))
                    break
                }
                if (!sp.isHigh && c.close < sp.price) {
                    val trapIdx = (i + 1..minOf(i + 3, candles.lastIndex)).firstOrNull { candles[it].close > sp.price }
                    if (trapIdx != null) out.add(TrapEvent(TrapKind.BEAR_TRAP, sp.price, i, trapIdx))
                    break
                }
            }
        }
        return out.sortedBy { it.trapIndex }
    }

    private fun breakouts(
        candles: List<Candle>,
        breaks: List<StructureBreak>,
        liquidity: List<LiquidityLevel>,
        volMa: List<Double?>
    ): List<BreakoutEvent> {
        val out = mutableListOf<BreakoutEvent>()
        val donchian = AdvancedIndicators.donchian(candles, 20)
        for (i in max(21, candles.size - 120) until candles.size) {
            val dc = donchian[i] ?: continue
            val c = candles[i]
            val ma = volMa[i] ?: continue
            val strongBody = abs(c.close - c.open) >= 0.6 * (c.high - c.low)
            if (c.close > dc.upper && c.volume > ma && strongBody) out.add(BreakoutEvent(BreakoutKind.BREAKOUT_UP, i, dc.upper))
            if (c.close < dc.lower && c.volume > ma && strongBody) out.add(BreakoutEvent(BreakoutKind.BREAKOUT_DOWN, i, dc.lower))
        }
        breaks.filter { it.isChoch }.forEach { br ->
            val sweptBefore = liquidity.any { lv ->
                lv.status == LiquidityStatus.SWEPT && lv.sweptAtIndex != null &&
                    lv.sweptAtIndex in (br.index - 10)..br.index &&
                    ((br.bullish && lv.side == LiquiditySide.SELL_SIDE) || (!br.bullish && lv.side == LiquiditySide.BUY_SIDE))
            }
            if (sweptBefore) {
                out.add(BreakoutEvent(if (br.bullish) BreakoutKind.REVERSAL_UP else BreakoutKind.REVERSAL_DOWN, br.index, br.level))
            }
        }
        return out.sortedBy { it.index }
    }
}
