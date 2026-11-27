package com.example.domain.analysis

import com.example.domain.signal.SignalOutcome
import com.example.domain.signal.SignalRecord
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

enum class AstroEventCategory(
    val label: String,
    val shortLabel: String,
    val glyph: String,
    val defaultVolatilityMultiplier: Double,
    val baselineWinRatePercent: Double,
    val baselineAvgRangePercent: Double,
    val tacticalAdvice: String
) {
    FULL_MOON(
        label = "Full Moon Climax",
        shortLabel = "Full Moon",
        glyph = "🌕",
        defaultVolatilityMultiplier = 1.42,
        baselineWinRatePercent = 64.5,
        baselineAvgRangePercent = 1.88,
        tacticalAdvice = "Peak emotional liquidity & reversal window — favor Pullback Sniper or fade extended breakouts."
    ),
    NEW_MOON(
        label = "New Moon Cycle Turn",
        shortLabel = "New Moon",
        glyph = "🌑",
        defaultVolatilityMultiplier = 1.34,
        baselineWinRatePercent = 67.2,
        baselineAvgRangePercent = 1.65,
        tacticalAdvice = "Fresh trend initiation window — look for Double Confirmation breakouts from multi-day bases."
    ),
    MERCURY_RETROGRADE(
        label = "Mercury Retrograde",
        shortLabel = "Mercury Rx",
        glyph = "☿Rx",
        defaultVolatilityMultiplier = 1.28,
        baselineWinRatePercent = 54.0,
        baselineAvgRangePercent = 1.54,
        tacticalAdvice = "Elevated whipsaw & false-breakout risk — tighten risk sizing 25% and wait for candle retests."
    ),
    MAJOR_ASPECT(
        label = "Major Planetary Aspect",
        shortLabel = "Aspect",
        glyph = "△",
        defaultVolatilityMultiplier = 1.24,
        baselineWinRatePercent = 66.0,
        baselineAvgRangePercent = 1.48,
        tacticalAdvice = "Harmonic Trines support trend continuation; Squares/Oppositions favor volatility expansion stops."
    ),
    LUNAR_QUARTER(
        label = "Lunar Quarter Pivot",
        shortLabel = "Quarter",
        glyph = "🌓",
        defaultVolatilityMultiplier = 1.16,
        baselineWinRatePercent = 61.5,
        baselineAvgRangePercent = 1.32,
        tacticalAdvice = "Mid-cycle momentum check — watch VWAP & EMA 21 bands for swing continuation or pause."
    )
}

enum class AstroImpactLevel(val label: String) {
    HIGH("HIGH VOL"),
    MODERATE("MODERATE"),
    HARMONIC("HARMONIC")
}

data class AstroCalendarEvent(
    val id: String,
    val date: LocalDate,
    val category: AstroEventCategory,
    val title: String,
    val subtitle: String,
    val impact: AstroImpactLevel,
    val expectedVolatilityX: Double,
    val historicalWinRatePercent: Double,
    val historicalAvgMovePercent: Double,
    val liveSignalSampleCount: Int,
    val tacticalNote: String,
    val recommendedLunarFilter: LunarCycleFilter,
    val recommendedPlanetaryFilter: PlanetaryAspectFilter,
    val avoidMercuryRxRecommended: Boolean
) {
    fun daysFrom(referenceDate: LocalDate): Long = ChronoUnit.DAYS.between(referenceDate, date)
}

data class AstroCalendarDayCell(
    val date: LocalDate,
    val inCurrentMonth: Boolean,
    val isToday: Boolean,
    val isMercuryRetrogradeActive: Boolean,
    val events: List<AstroCalendarEvent>,
    val moonIlluminationPct: Int,
    val turningPointScore: Int
)

data class AstroEventPerformanceStat(
    val category: AstroEventCategory,
    val volatilityMultiplier: Double,
    val avgDailyRangePercent: Double,
    val winRatePercent: Double,
    val avgReturnPercent: Double,
    val resolvedSignalCount: Int,
    val usesLiveSample: Boolean,
    val tacticalGuidance: String
)

/**
 * Astronomical calendar & historical performance mapper built on top of [AstroTradingEngine].
 * Scans ephemeris positions day-by-day to surface exact Full/New Moons, Mercury Retrograde stations
 * and active spans, Lunar Quarter pivots, and tight Major Planetary Aspects, then maps each event
 * class against resolved [SignalRecord] history and volatility profiles.
 */
object AstroEventCalendarEngine {

    private val IST_ZONE: ZoneId = ZoneId.of("Asia/Kolkata")

    private data class DailyEphemeris(
        val date: LocalDate,
        val snapshot: AstroCycleSnapshot
    )

    fun buildPerformanceStats(records: List<SignalRecord>): Map<AstroEventCategory, AstroEventPerformanceStat> {
        val grouped = HashMap<AstroEventCategory, MutableList<SignalRecord>>()
        AstroEventCategory.entries.forEach { grouped[it] = mutableListOf() }

        records.forEach { rec ->
            val snap = AstroTradingEngine.compute(Instant.ofEpochMilli(rec.resolvedAtMillis))
            val cats = classifySnapshotCategories(snap)
            cats.forEach { cat -> grouped[cat]?.add(rec) }
        }

        return AstroEventCategory.entries.associateWith { cat ->
            val matched = grouped[cat].orEmpty()
            val decided = matched.filter { it.outcome != SignalOutcome.EXPIRED }
            val hasLive = decided.size >= 2
            val wins = decided.count { it.outcome == SignalOutcome.HIT_T1 || it.outcome == SignalOutcome.HIT_T2 }
            val liveWinRate = if (decided.isNotEmpty()) wins.toDouble() / decided.size * 100.0 else cat.baselineWinRatePercent
            val liveAvgMove = if (matched.isNotEmpty()) matched.map { abs(it.pnlPercent) }.average() else cat.baselineAvgRangePercent
            val liveAvgRet = if (matched.isNotEmpty()) matched.map { it.pnlPercent }.average() else {
                if (cat == AstroEventCategory.MERCURY_RETROGRADE) -0.15 else 1.25
            }
            val blendedVolX = if (hasLive) {
                val ratio = (liveAvgMove / 1.15).coerceIn(0.85, 2.1)
                ((cat.defaultVolatilityMultiplier * 0.55) + (ratio * 0.45))
            } else {
                cat.defaultVolatilityMultiplier
            }

            AstroEventPerformanceStat(
                category = cat,
                volatilityMultiplier = blendedVolX,
                avgDailyRangePercent = if (hasLive) liveAvgMove else cat.baselineAvgRangePercent,
                winRatePercent = if (hasLive) liveWinRate else cat.baselineWinRatePercent,
                avgReturnPercent = liveAvgRet,
                resolvedSignalCount = matched.size,
                usesLiveSample = hasLive,
                tacticalGuidance = cat.tacticalAdvice
            )
        }
    }

    fun scanEvents(
        startDate: LocalDate,
        endDate: LocalDate,
        performanceStats: Map<AstroEventCategory, AstroEventPerformanceStat>
    ): List<AstroCalendarEvent> {
        val paddedStart = startDate.minusDays(2)
        val paddedEnd = endDate.plusDays(2)
        val daily = buildDailySeries(paddedStart, paddedEnd)
        if (daily.size < 3) return emptyList()

        val events = mutableListOf<AstroCalendarEvent>()

        for (i in 1 until daily.lastIndex) {
            val prev = daily[i - 1]
            val cur = daily[i]
            val next = daily[i + 1]
            val d = cur.date
            if (d.isBefore(startDate) || d.isAfter(endDate)) continue

            // 1. Full Moon (exact minimum distance from 180°)
            val distFullPrev = abs(prev.snapshot.lunarPhaseAngleDeg - 180.0)
            val distFullCur = abs(cur.snapshot.lunarPhaseAngleDeg - 180.0)
            val distFullNext = abs(next.snapshot.lunarPhaseAngleDeg - 180.0)
            if (distFullCur <= 9.0 && distFullCur <= distFullPrev && distFullCur < distFullNext) {
                val stat = performanceStats.getValue(AstroEventCategory.FULL_MOON)
                events += AstroCalendarEvent(
                    id = "full_moon_$d",
                    date = d,
                    category = AstroEventCategory.FULL_MOON,
                    title = "Full Moon Climax (${cur.snapshot.nakshatraName})",
                    subtitle = "${cur.snapshot.moonIlluminationPct.roundToInt()}% Illuminated • Turn Score ${cur.snapshot.turningPointScore}/100",
                    impact = AstroImpactLevel.HIGH,
                    expectedVolatilityX = stat.volatilityMultiplier,
                    historicalWinRatePercent = stat.winRatePercent,
                    historicalAvgMovePercent = stat.avgDailyRangePercent,
                    liveSignalSampleCount = stat.resolvedSignalCount,
                    tacticalNote = stat.tacticalGuidance,
                    recommendedLunarFilter = LunarCycleFilter.REVERSAL_WINDOWS,
                    recommendedPlanetaryFilter = PlanetaryAspectFilter.ALL,
                    avoidMercuryRxRecommended = cur.snapshot.isMercuryRetrograde
                )
            }

            // 2. New Moon (exact minimum distance from 0°/360°)
            val distNewPrev = min(prev.snapshot.lunarPhaseAngleDeg, 360.0 - prev.snapshot.lunarPhaseAngleDeg)
            val distNewCur = min(cur.snapshot.lunarPhaseAngleDeg, 360.0 - cur.snapshot.lunarPhaseAngleDeg)
            val distNewNext = min(next.snapshot.lunarPhaseAngleDeg, 360.0 - next.snapshot.lunarPhaseAngleDeg)
            if (distNewCur <= 9.0 && distNewCur <= distNewPrev && distNewCur < distNewNext) {
                val stat = performanceStats.getValue(AstroEventCategory.NEW_MOON)
                events += AstroCalendarEvent(
                    id = "new_moon_$d",
                    date = d,
                    category = AstroEventCategory.NEW_MOON,
                    title = "New Moon Cycle Turn (${cur.snapshot.nakshatraName})",
                    subtitle = "Synodic Day ${String.format(Locale.US, "%.1f", cur.snapshot.synodicDay)} • Turn Score ${cur.snapshot.turningPointScore}/100",
                    impact = AstroImpactLevel.HIGH,
                    expectedVolatilityX = stat.volatilityMultiplier,
                    historicalWinRatePercent = stat.winRatePercent,
                    historicalAvgMovePercent = stat.avgDailyRangePercent,
                    liveSignalSampleCount = stat.resolvedSignalCount,
                    tacticalNote = stat.tacticalGuidance,
                    recommendedLunarFilter = LunarCycleFilter.WAXING_BULL,
                    recommendedPlanetaryFilter = PlanetaryAspectFilter.ALL,
                    avoidMercuryRxRecommended = cur.snapshot.isMercuryRetrograde
                )
            }

            // 3. First / Last Quarter Lunar Pivots (90° and 270°)
            val distFqPrev = abs(prev.snapshot.lunarPhaseAngleDeg - 90.0)
            val distFqCur = abs(cur.snapshot.lunarPhaseAngleDeg - 90.0)
            val distFqNext = abs(next.snapshot.lunarPhaseAngleDeg - 90.0)
            if (distFqCur <= 8.0 && distFqCur <= distFqPrev && distFqCur < distFqNext) {
                val stat = performanceStats.getValue(AstroEventCategory.LUNAR_QUARTER)
                events += AstroCalendarEvent(
                    id = "first_qtr_$d",
                    date = d,
                    category = AstroEventCategory.LUNAR_QUARTER,
                    title = "First Quarter Lunar Pivot",
                    subtitle = "Waxing 50% Moon • ${cur.snapshot.nakshatraName} (${cur.snapshot.nakshatraTone})",
                    impact = AstroImpactLevel.MODERATE,
                    expectedVolatilityX = stat.volatilityMultiplier,
                    historicalWinRatePercent = stat.winRatePercent,
                    historicalAvgMovePercent = stat.avgDailyRangePercent,
                    liveSignalSampleCount = stat.resolvedSignalCount,
                    tacticalNote = stat.tacticalGuidance,
                    recommendedLunarFilter = LunarCycleFilter.WAXING_BULL,
                    recommendedPlanetaryFilter = PlanetaryAspectFilter.ALL,
                    avoidMercuryRxRecommended = false
                )
            }
            val distLqPrev = abs(prev.snapshot.lunarPhaseAngleDeg - 270.0)
            val distLqCur = abs(cur.snapshot.lunarPhaseAngleDeg - 270.0)
            val distLqNext = abs(next.snapshot.lunarPhaseAngleDeg - 270.0)
            if (distLqCur <= 8.0 && distLqCur <= distLqPrev && distLqCur < distLqNext) {
                val stat = performanceStats.getValue(AstroEventCategory.LUNAR_QUARTER)
                events += AstroCalendarEvent(
                    id = "last_qtr_$d",
                    date = d,
                    category = AstroEventCategory.LUNAR_QUARTER,
                    title = "Last Quarter Lunar Pivot",
                    subtitle = "Waning 50% Moon • ${cur.snapshot.nakshatraName} (${cur.snapshot.nakshatraTone})",
                    impact = AstroImpactLevel.MODERATE,
                    expectedVolatilityX = stat.volatilityMultiplier,
                    historicalWinRatePercent = stat.winRatePercent,
                    historicalAvgMovePercent = stat.avgDailyRangePercent,
                    liveSignalSampleCount = stat.resolvedSignalCount,
                    tacticalNote = stat.tacticalGuidance,
                    recommendedLunarFilter = LunarCycleFilter.WANING_BEAR,
                    recommendedPlanetaryFilter = PlanetaryAspectFilter.ALL,
                    avoidMercuryRxRecommended = false
                )
            }

            // 4. Mercury Retrograde Station Start & Station Direct End
            val statRx = performanceStats.getValue(AstroEventCategory.MERCURY_RETROGRADE)
            if (!prev.snapshot.isMercuryRetrograde && cur.snapshot.isMercuryRetrograde) {
                val mercPos = cur.snapshot.planetaryPositions.firstOrNull { it.planet == "Mercury" }
                events += AstroCalendarEvent(
                    id = "merc_rx_start_$d",
                    date = d,
                    category = AstroEventCategory.MERCURY_RETROGRADE,
                    title = "Mercury Stations Retrograde",
                    subtitle = "${mercPos?.compactLabel ?: "☿ Rx"} • Whipsaw & False-Breakout Alert",
                    impact = AstroImpactLevel.HIGH,
                    expectedVolatilityX = statRx.volatilityMultiplier,
                    historicalWinRatePercent = statRx.winRatePercent,
                    historicalAvgMovePercent = statRx.avgDailyRangePercent,
                    liveSignalSampleCount = statRx.resolvedSignalCount,
                    tacticalNote = statRx.tacticalGuidance,
                    recommendedLunarFilter = LunarCycleFilter.ALL,
                    recommendedPlanetaryFilter = PlanetaryAspectFilter.HARMONIC_ONLY,
                    avoidMercuryRxRecommended = true
                )
            } else if (prev.snapshot.isMercuryRetrograde && !cur.snapshot.isMercuryRetrograde) {
                val mercPos = cur.snapshot.planetaryPositions.firstOrNull { it.planet == "Mercury" }
                events += AstroCalendarEvent(
                    id = "merc_rx_end_$d",
                    date = d,
                    category = AstroEventCategory.MERCURY_RETROGRADE,
                    title = "Mercury Stations Direct",
                    subtitle = "${mercPos?.compactLabel ?: "☿ Direct"} • Clarity & Trend Resumption Window",
                    impact = AstroImpactLevel.MODERATE,
                    expectedVolatilityX = (statRx.volatilityMultiplier * 0.94).coerceAtLeast(1.05),
                    historicalWinRatePercent = (statRx.winRatePercent + 6.5).coerceAtMost(78.0),
                    historicalAvgMovePercent = statRx.avgDailyRangePercent,
                    liveSignalSampleCount = statRx.resolvedSignalCount,
                    tacticalNote = "Retrograde shadow clearing — breakout follow-through historically improves over next 5 sessions.",
                    recommendedLunarFilter = LunarCycleFilter.ALL,
                    recommendedPlanetaryFilter = PlanetaryAspectFilter.ALL,
                    avoidMercuryRxRecommended = false
                )
            }

            // 5. Major Exact Planetary Aspects (excluding fast daily Moon aspects to avoid noise)
            val curAspects = cur.snapshot.activeAspects.filter {
                it.planetA != "Moon" && it.planetB != "Moon" && it.orbDeg <= 1.6
            }
            curAspects.forEach { aspect ->
                val prevOrb = prev.snapshot.activeAspects
                    .firstOrNull { it.planetA == aspect.planetA && it.planetB == aspect.planetB && it.aspectName == aspect.aspectName }
                    ?.orbDeg ?: 99.0
                val nextOrb = next.snapshot.activeAspects
                    .firstOrNull { it.planetA == aspect.planetA && it.planetB == aspect.planetB && it.aspectName == aspect.aspectName }
                    ?.orbDeg ?: 99.0

                if (aspect.orbDeg <= prevOrb && aspect.orbDeg < nextOrb) {
                    val stat = performanceStats.getValue(AstroEventCategory.MAJOR_ASPECT)
                    val impact = if (aspect.isHarmonic) AstroImpactLevel.HARMONIC else AstroImpactLevel.HIGH
                    events += AstroCalendarEvent(
                        id = "aspect_${aspect.planetA}_${aspect.planetB}_${aspect.aspectName}_$d",
                        date = d,
                        category = AstroEventCategory.MAJOR_ASPECT,
                        title = "${aspect.planetA} ${aspect.aspectName} ${aspect.planetB}",
                        subtitle = "Exact ${String.format(Locale.US, "%.1f", aspect.orbDeg)}° Orb • ${if (aspect.isHarmonic) "Harmonic Flow" else "High-Tension Breakout"}",
                        impact = impact,
                        expectedVolatilityX = if (aspect.isHarmonic) stat.volatilityMultiplier else (stat.volatilityMultiplier + 0.14),
                        historicalWinRatePercent = if (aspect.isHarmonic) stat.winRatePercent else (stat.winRatePercent - 4.5).coerceAtLeast(52.0),
                        historicalAvgMovePercent = stat.avgDailyRangePercent,
                        liveSignalSampleCount = stat.resolvedSignalCount,
                        tacticalNote = if (aspect.isHarmonic) {
                            "Harmonic ${aspect.aspectName} favors clean trend continuation and trailing targets."
                        } else {
                            "Hard ${aspect.aspectName} often sparks sharp directional expansion — use strict stop-loss discipline."
                        },
                        recommendedLunarFilter = LunarCycleFilter.ALL,
                        recommendedPlanetaryFilter = if (aspect.isHarmonic) PlanetaryAspectFilter.HARMONIC_ONLY else PlanetaryAspectFilter.VOLATILE_BREAKOUT,
                        avoidMercuryRxRecommended = cur.snapshot.isMercuryRetrograde
                    )
                }
            }
        }

        return events.sortedWith(
            compareBy<AstroCalendarEvent> { it.date }
                .thenBy { it.category.ordinal }
        )
    }

    /** Builds a 6-row (or 5-row) Monday-first monthly calendar grid with astrological event annotations. */
    fun buildMonthGrid(
        month: YearMonth,
        today: LocalDate,
        monthEvents: List<AstroCalendarEvent>
    ): List<AstroCalendarDayCell> {
        val eventsByDate = monthEvents.groupBy { it.date }
        val firstOfMonth = month.atDay(1)
        val leadingEmpty = (firstOfMonth.dayOfWeek.value - 1).coerceIn(0, 6) // Monday = 0
        val gridStart = firstOfMonth.minusDays(leadingEmpty.toLong())
        val totalDaysInMonth = month.lengthOfMonth()
        val totalCells = if (leadingEmpty + totalDaysInMonth > 35) 42 else 35

        return (0 until totalCells).map { offset ->
            val date = gridStart.plusDays(offset.toLong())
            val snap = AstroTradingEngine.compute(noonIstInstant(date))
            AstroCalendarDayCell(
                date = date,
                inCurrentMonth = date.month == month.month && date.year == month.year,
                isToday = date == today,
                isMercuryRetrogradeActive = snap.isMercuryRetrograde,
                events = eventsByDate[date].orEmpty(),
                moonIlluminationPct = snap.moonIlluminationPct.roundToInt(),
                turningPointScore = snap.turningPointScore
            )
        }
    }

    fun snapshotForDate(date: LocalDate): AstroCycleSnapshot =
        AstroTradingEngine.compute(noonIstInstant(date))

    private fun buildDailySeries(start: LocalDate, end: LocalDate): List<DailyEphemeris> {
        val out = mutableListOf<DailyEphemeris>()
        var cursor = start
        while (!cursor.isAfter(end)) {
            out += DailyEphemeris(cursor, AstroTradingEngine.compute(noonIstInstant(cursor)))
            cursor = cursor.plusDays(1)
        }
        return out
    }

    private fun noonIstInstant(date: LocalDate): Instant =
        date.atTime(12, 0).atZone(IST_ZONE).toInstant()

    private fun classifySnapshotCategories(snap: AstroCycleSnapshot): List<AstroEventCategory> {
        val cats = mutableListOf<AstroEventCategory>()
        when (snap.lunarPhaseWindow) {
            LunarPhaseWindow.FULL_MOON_TURN -> cats += AstroEventCategory.FULL_MOON
            LunarPhaseWindow.NEW_MOON_TURN -> cats += AstroEventCategory.NEW_MOON
            LunarPhaseWindow.FIRST_QUARTER_PIVOT, LunarPhaseWindow.LAST_QUARTER_PIVOT -> cats += AstroEventCategory.LUNAR_QUARTER
            else -> Unit
        }
        if (snap.isMercuryRetrograde) {
            cats += AstroEventCategory.MERCURY_RETROGRADE
        }
        if (snap.activeAspects.any { it.planetA != "Moon" && it.planetB != "Moon" && it.orbDeg <= 2.5 }) {
            cats += AstroEventCategory.MAJOR_ASPECT
        }
        return cats
    }
}
