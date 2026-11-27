package com.example.domain.analysis

import com.example.domain.signal.SignalDirection
import java.time.Instant
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

enum class LunarPhaseWindow(val label: String) {
    NEW_MOON_TURN("New Moon Cycle Turn"),
    WAXING_MOMENTUM("Waxing Lunar Momentum"),
    FIRST_QUARTER_PIVOT("First Quarter Pivot"),
    FULL_MOON_TURN("Full Moon Climax Window"),
    WANING_PULLBACK("Waning Distribution Cycle"),
    LAST_QUARTER_PIVOT("Last Quarter Pivot")
}

enum class AstroBias(val label: String) {
    BULLISH_HARMONIC("Bullish Harmonic"),
    BEARISH_PRESSURE("Bearish Cycle Pressure"),
    REVERSAL_WINDOW("High-Volatility Turn Window"),
    NEUTRAL("Balanced Transit")
}

enum class LunarCycleFilter(val label: String) {
    ALL("Any Moon"),
    WAXING_BULL("Waxing Bull"),
    WANING_BEAR("Waning Bear"),
    REVERSAL_WINDOWS("New / Full Turns")
}

enum class PlanetaryAspectFilter(val label: String) {
    ALL("Any Aspect"),
    HARMONIC_ONLY("Trine / Sextile"),
    VOLATILE_BREAKOUT("Square / Opp")
}

/**
 * Interactive Astrological Trend Filters applied both to the live Signals feed
 * and directly inside the signal generation pipeline ([CompositeSignalEngine] & [AstroCycleEvaluator]).
 */
data class AstroTrendFilterConfig(
    val enabled: Boolean = false,
    val lunarCycleFilter: LunarCycleFilter = LunarCycleFilter.ALL,
    val planetaryFilter: PlanetaryAspectFilter = PlanetaryAspectFilter.ALL,
    val alignSignalDirectionWithAstro: Boolean = true,
    val avoidMercuryRetrograde: Boolean = false,
    val minTurningPointScore: Int = 50
)

data class AstroFilterEvaluation(
    val passes: Boolean,
    val alignmentScore: Int, // 0..100
    val badgeLabel: String,
    val detailReason: String
)

data class PlanetaryPosition(
    val planet: String,
    val glyph: String,
    val zodiacSign: String,
    val degreeInSign: Double,
    val longitudeDeg: Double,
    val isRetrograde: Boolean = false
) {
    val compactLabel: String
        get() = "$glyph $zodiacSign ${degreeInSign.roundToInt()}°${if (isRetrograde) " Rx" else ""}"
}

data class PlanetaryAspect(
    val planetA: String,
    val planetB: String,
    val aspectName: String,
    val angleDeg: Double,
    val orbDeg: Double,
    val isHarmonic: Boolean
)

data class AstroCycleSnapshot(
    val lunarPhaseAngleDeg: Double,
    val moonIlluminationPct: Double,
    val synodicDay: Double,
    val lunarPhaseWindow: LunarPhaseWindow,
    val nakshatraName: String,
    val nakshatraTone: String,
    val isMercuryRetrograde: Boolean,
    val planetaryPositions: List<PlanetaryPosition>,
    val activeAspects: List<PlanetaryAspect>,
    val turningPointScore: Int, // 0..100
    val bias: AstroBias,
    val summaryLine: String
)

/**
 * Astronomical / Financial Astrology (Gann, Lunar Cycle, Sidereal Nakshatra & Planetary Positions/Aspects)
 * calculation engine using standard J2000.0 Meeus orbital formulas.
 * Always paired with technical price-action confirmation before generating signals.
 */
object AstroTradingEngine {

    private const val SYNODIC_MONTH = 29.530588853
    private const val LAHIRI_AYANAMSA_J2026 = 24.22 // Sidereal offset (degrees)

    private val ZODIAC_SIGNS = listOf(
        "Ari", "Tau", "Gem", "Can", "Leo", "Vir",
        "Lib", "Sco", "Sag", "Cap", "Aqu", "Pis"
    )

    private val NAKSHATRAS = listOf(
        "Ashwini" to "Expansion",
        "Bharani" to "Volatile",
        "Krittika" to "Volatile",
        "Rohini" to "Expansion",
        "Mrigashira" to "Balanced",
        "Ardra" to "Volatile",
        "Punarvasu" to "Expansion",
        "Pushya" to "Expansion",
        "Ashlesha" to "Volatile",
        "Magha" to "Balanced",
        "Purva Phalguni" to "Expansion",
        "Uttara Phalguni" to "Expansion",
        "Hasta" to "Expansion",
        "Chitra" to "Balanced",
        "Swati" to "Expansion",
        "Vishakha" to "Balanced",
        "Anuradha" to "Expansion",
        "Jyeshtha" to "Volatile",
        "Mula" to "Volatile",
        "Purva Ashadha" to "Balanced",
        "Uttara Ashadha" to "Expansion",
        "Shravana" to "Expansion",
        "Dhanishta" to "Expansion",
        "Shatabhisha" to "Volatile",
        "Purva Bhadrapada" to "Volatile",
        "Uttara Bhadrapada" to "Balanced",
        "Revati" to "Expansion"
    )

    fun compute(instant: Instant = Instant.now()): AstroCycleSnapshot {
        val jd = instant.epochSecond.toDouble() / 86400.0 + 2440587.5
        val d = jd - 2451545.0 // Days since J2000.0

        // 1. Sun Ecliptic Longitude (Meeus low-precision, ~0.01° accuracy)
        val sunMeanLon = norm360(280.460 + 0.9856474 * d)
        val sunMeanAnom = norm360(357.528 + 0.9856003 * d)
        val sunLon = norm360(
            sunMeanLon +
                1.915 * sin(rad(sunMeanAnom)) +
                0.020 * sin(rad(2.0 * sunMeanAnom))
        )

        // 2. Moon Ecliptic Longitude (principal Meeus periodic terms, ~0.3° accuracy)
        val moonMeanLon = norm360(218.316 + 13.176396 * d)
        val moonMeanAnom = norm360(134.963 + 13.064993 * d)
        val moonArgLat = norm360(93.272 + 13.229350 * d)
        val moonLon = norm360(
            moonMeanLon +
                6.289 * sin(rad(moonMeanAnom)) +
                1.274 * sin(rad(2.0 * (moonMeanLon - sunMeanLon) - moonMeanAnom)) +
                0.658 * sin(rad(2.0 * (moonMeanLon - sunMeanLon))) -
                0.186 * sin(rad(sunMeanAnom)) -
                0.114 * sin(rad(2.0 * moonArgLat))
        )

        // 3. Lunar Phase Angle & Illumination %
        val phaseAngle = norm360(moonLon - sunLon)
        val illuminationPct = ((1.0 - cos(rad(phaseAngle))) * 50.0).coerceIn(0.0, 100.0)
        val synodicDay = (phaseAngle / 360.0) * SYNODIC_MONTH

        val lunarWindow = when {
            phaseAngle <= 18.0 || phaseAngle >= 342.0 -> LunarPhaseWindow.NEW_MOON_TURN
            phaseAngle in 78.0..102.0 -> LunarPhaseWindow.FIRST_QUARTER_PIVOT
            phaseAngle in 162.0..198.0 -> LunarPhaseWindow.FULL_MOON_TURN
            phaseAngle in 258.0..282.0 -> LunarPhaseWindow.LAST_QUARTER_PIVOT
            phaseAngle < 180.0 -> LunarPhaseWindow.WAXING_MOMENTUM
            else -> LunarPhaseWindow.WANING_PULLBACK
        }

        // 4. Sidereal Nakshatra (27 lunar mansions of 13.3333° each)
        val siderealMoon = norm360(moonLon - LAHIRI_AYANAMSA_J2026)
        val nakIndex = ((siderealMoon / (360.0 / 27.0)).toInt()).coerceIn(0, 26)
        val (nakName, nakTone) = NAKSHATRAS[nakIndex]

        // 5. Planetary Geocentric Ecliptic Longitudes
        val mercuryLon = norm360(sunLon + 22.8 * sin(rad(252.251 + 4.09233445 * d - sunMeanLon)))
        val venusLon = norm360(sunLon + 45.5 * sin(rad(181.979 + 1.60213034 * d - sunMeanLon)))
        val marsLon = norm360(355.433 + 0.52403295 * d + 10.7 * sin(rad(19.373 + 0.52402068 * d)))
        val jupiterLon = norm360(34.351 + 0.08309121 * d + 5.5 * sin(rad(20.020 + 0.08308529 * d)))
        val saturnLon = norm360(50.077 + 0.03345965 * d + 6.4 * sin(rad(317.020 + 0.03344414 * d)))

        // Mercury Retrograde check: derivative of geocentric longitude < 0
        val sunNext = norm360(280.460 + 0.9856474 * (d + 1.0))
        val mercuryLonNext = norm360(
            sunNext + 22.8 * sin(rad(252.251 + 4.09233445 * (d + 1.0) - sunNext))
        )
        val isMercuryRetrograde = signedDeltaDeg(mercuryLon, mercuryLonNext) < 0.0

        val planetaryPositions = listOf(
            toPosition("Sun", "☉", sunLon, false),
            toPosition("Moon", "☽", moonLon, false),
            toPosition("Mercury", "☿", mercuryLon, isMercuryRetrograde),
            toPosition("Venus", "♀", venusLon, false),
            toPosition("Mars", "♂", marsLon, false),
            toPosition("Jupiter", "♃", jupiterLon, false),
            toPosition("Saturn", "♄", saturnLon, false)
        )

        val aspectDefs = listOf(
            Triple("Conjunction", 0.0, true),
            Triple("Sextile", 60.0, true),
            Triple("Square", 90.0, false),
            Triple("Trine", 120.0, true),
            Triple("Opposition", 180.0, false)
        )

        val activeAspects = mutableListOf<PlanetaryAspect>()
        for (i in planetaryPositions.indices) {
            for (j in (i + 1) until planetaryPositions.size) {
                val pA = planetaryPositions[i]
                val pB = planetaryPositions[j]
                val sep = angularSep(pA.longitudeDeg, pB.longitudeDeg)
                for ((name, target, harmonic) in aspectDefs) {
                    val orb = abs(sep - target)
                    val maxOrb = if (pA.planet == "Moon" || pB.planet == "Moon") 6.5 else 5.0
                    if (orb <= maxOrb) {
                        activeAspects += PlanetaryAspect(
                            planetA = pA.planet,
                            planetB = pB.planet,
                            aspectName = name,
                            angleDeg = target,
                            orbDeg = orb,
                            isHarmonic = harmonic
                        )
                        break
                    }
                }
            }
        }
        val topAspects = activeAspects.sortedBy { it.orbDeg }.take(4)

        // 6. Compute Gann/Bradley Turning Point Score & Directional Astro Bias
        var bullPts = 0
        var bearPts = 0
        var turnPts = 35

        when (lunarWindow) {
            LunarPhaseWindow.NEW_MOON_TURN -> {
                turnPts += 28; bullPts += 14
            }
            LunarPhaseWindow.WAXING_MOMENTUM -> {
                turnPts += 14; bullPts += 18
            }
            LunarPhaseWindow.FIRST_QUARTER_PIVOT -> {
                turnPts += 20; bullPts += 8
            }
            LunarPhaseWindow.FULL_MOON_TURN -> {
                turnPts += 30; bearPts += 14
            }
            LunarPhaseWindow.WANING_PULLBACK -> {
                turnPts += 12; bearPts += 16
            }
            LunarPhaseWindow.LAST_QUARTER_PIVOT -> {
                turnPts += 20; bearPts += 10
            }
        }

        when (nakTone) {
            "Expansion" -> { bullPts += 12; turnPts += 6 }
            "Volatile" -> { bearPts += 8; turnPts += 12 }
        }

        topAspects.forEach { aspect ->
            val tightnessBonus = ((6.5 - aspect.orbDeg).coerceAtLeast(1.0) * 2.2).roundToInt()
            turnPts += tightnessBonus
            if (aspect.isHarmonic) bullPts += 9 else bearPts += 9
        }

        if (isMercuryRetrograde) {
            turnPts += 8
            bearPts += 5
        }

        val turningScore = turnPts.coerceIn(25, 98)
        val bias = when {
            lunarWindow == LunarPhaseWindow.FULL_MOON_TURN || lunarWindow == LunarPhaseWindow.NEW_MOON_TURN ->
                AstroBias.REVERSAL_WINDOW
            bullPts >= bearPts + 8 -> AstroBias.BULLISH_HARMONIC
            bearPts >= bullPts + 8 -> AstroBias.BEARISH_PRESSURE
            else -> AstroBias.NEUTRAL
        }

        val aspectStr = topAspects.firstOrNull()?.let { "${it.planetA} ${it.aspectName} ${it.planetB}" } ?: "Clean Transit"
        val rxTag = if (isMercuryRetrograde) " • Mercury Rx" else ""
        val summary = "${lunarWindow.label} (${illuminationPct.roundToInt()}% Moon) • $nakName ($nakTone) • $aspectStr$rxTag"

        return AstroCycleSnapshot(
            lunarPhaseAngleDeg = phaseAngle,
            moonIlluminationPct = illuminationPct,
            synodicDay = synodicDay,
            lunarPhaseWindow = lunarWindow,
            nakshatraName = nakName,
            nakshatraTone = nakTone,
            isMercuryRetrograde = isMercuryRetrograde,
            planetaryPositions = planetaryPositions,
            activeAspects = topAspects,
            turningPointScore = turningScore,
            bias = bias,
            summaryLine = summary
        )
    }

    /**
     * Evaluates whether a signal direction aligns with the active [AstroTrendFilterConfig].
     * Used inside both [CompositeSignalEngine] during signal generation and the Signals UI filter pipeline.
     */
    fun evaluateFilterMatch(
        snapshot: AstroCycleSnapshot,
        direction: SignalDirection,
        config: AstroTrendFilterConfig
    ): AstroFilterEvaluation {
        val isWaxing = snapshot.lunarPhaseAngleDeg < 180.0
        val isReversalWindow = snapshot.lunarPhaseWindow == LunarPhaseWindow.NEW_MOON_TURN ||
            snapshot.lunarPhaseWindow == LunarPhaseWindow.FULL_MOON_TURN ||
            snapshot.lunarPhaseWindow == LunarPhaseWindow.FIRST_QUARTER_PIVOT ||
            snapshot.lunarPhaseWindow == LunarPhaseWindow.LAST_QUARTER_PIVOT

        val hasHarmonic = snapshot.activeAspects.any { it.isHarmonic }
        val hasHardAspect = snapshot.activeAspects.any { !it.isHarmonic }

        // Directional affinity score (0..100)
        var score = 55
        if (direction == SignalDirection.LONG) {
            if (isWaxing) score += 14 else score -= 6
            if (snapshot.bias == AstroBias.BULLISH_HARMONIC) score += 16
            if (snapshot.bias == AstroBias.BEARISH_PRESSURE) score -= 14
            if (hasHarmonic) score += 8
        } else {
            if (!isWaxing) score += 14 else score -= 6
            if (snapshot.bias == AstroBias.BEARISH_PRESSURE) score += 16
            if (snapshot.bias == AstroBias.BULLISH_HARMONIC) score -= 14
            if (hasHardAspect) score += 8
        }
        if (isReversalWindow) score += 6
        if (snapshot.isMercuryRetrograde) score -= 10
        val clampedScore = score.coerceIn(15, 98)

        if (!config.enabled) {
            val passiveTag = when {
                clampedScore >= 72 -> "Astro Aligned ($clampedScore%)"
                snapshot.isMercuryRetrograde -> "Mercury Rx"
                else -> "Astro Neutral"
            }
            return AstroFilterEvaluation(
                passes = true,
                alignmentScore = clampedScore,
                badgeLabel = passiveTag,
                detailReason = "Astro Transit: ${snapshot.summaryLine}"
            )
        }

        // 1. Mercury Retrograde Guard
        if (config.avoidMercuryRetrograde && snapshot.isMercuryRetrograde) {
            return AstroFilterEvaluation(
                passes = false,
                alignmentScore = clampedScore,
                badgeLabel = "Blocked: Mercury Rx",
                detailReason = "Suppressed by Astro Filter: Mercury Retrograde active"
            )
        }

        // 2. Min Turning Point Score Gate
        if (snapshot.turningPointScore < config.minTurningPointScore) {
            return AstroFilterEvaluation(
                passes = false,
                alignmentScore = clampedScore,
                badgeLabel = "Low Turn Score",
                detailReason = "Suppressed: Astro Turn Score ${snapshot.turningPointScore} < ${config.minTurningPointScore}"
            )
        }

        // 3. Lunar Cycle Gate
        val lunarPass = when (config.lunarCycleFilter) {
            LunarCycleFilter.ALL -> true
            LunarCycleFilter.WAXING_BULL -> isWaxing && direction == SignalDirection.LONG
            LunarCycleFilter.WANING_BEAR -> !isWaxing && direction == SignalDirection.SHORT
            LunarCycleFilter.REVERSAL_WINDOWS -> isReversalWindow
        }
        if (!lunarPass) {
            return AstroFilterEvaluation(
                passes = false,
                alignmentScore = clampedScore,
                badgeLabel = "Lunar Mismatch",
                detailReason = "Filtered out by Lunar Cycle rule (${config.lunarCycleFilter.label})"
            )
        }

        // 4. Planetary Aspect Gate
        val aspectPass = when (config.planetaryFilter) {
            PlanetaryAspectFilter.ALL -> true
            PlanetaryAspectFilter.HARMONIC_ONLY -> hasHarmonic
            PlanetaryAspectFilter.VOLATILE_BREAKOUT -> hasHardAspect
        }
        if (!aspectPass) {
            return AstroFilterEvaluation(
                passes = false,
                alignmentScore = clampedScore,
                badgeLabel = "Aspect Mismatch",
                detailReason = "Filtered out by Planetary Aspect rule (${config.planetaryFilter.label})"
            )
        }

        // 5. Directional Astro Alignment Gate
        if (config.alignSignalDirectionWithAstro) {
            val directionAligned = when (snapshot.bias) {
                AstroBias.BULLISH_HARMONIC -> direction == SignalDirection.LONG
                AstroBias.BEARISH_PRESSURE -> direction == SignalDirection.SHORT
                AstroBias.REVERSAL_WINDOW -> true
                AstroBias.NEUTRAL -> if (isWaxing) direction == SignalDirection.LONG else direction == SignalDirection.SHORT
            }
            if (!directionAligned) {
                return AstroFilterEvaluation(
                    passes = false,
                    alignmentScore = clampedScore,
                    badgeLabel = "Counter-Astro",
                    detailReason = "Signal ${direction.name} conflicts with active Astro bias (${snapshot.bias.label})"
                )
            }
        }

        val topPos = snapshot.planetaryPositions.take(3).joinToString(" • ") { it.compactLabel }
        return AstroFilterEvaluation(
            passes = true,
            alignmentScore = clampedScore,
            badgeLabel = "Astro Verified ($clampedScore%)",
            detailReason = "Astro Filter Passed (${snapshot.lunarPhaseWindow.label}, $topPos, Turn ${snapshot.turningPointScore}/100)"
        )
    }

    private fun toPosition(planet: String, glyph: String, lon: Double, retrograde: Boolean): PlanetaryPosition {
        val norm = norm360(lon)
        val signIndex = ((norm / 30.0).toInt()).coerceIn(0, 11)
        val degInSign = norm - signIndex * 30.0
        return PlanetaryPosition(
            planet = planet,
            glyph = glyph,
            zodiacSign = ZODIAC_SIGNS[signIndex],
            degreeInSign = degInSign,
            longitudeDeg = norm,
            isRetrograde = retrograde
        )
    }

    private fun norm360(deg: Double): Double {
        var r = deg % 360.0
        if (r < 0.0) r += 360.0
        return r
    }

    private fun angularSep(a: Double, b: Double): Double {
        val diff = abs(norm360(a) - norm360(b))
        return if (diff > 180.0) 360.0 - diff else diff
    }

    private fun signedDeltaDeg(from: Double, to: Double): Double {
        var diff = norm360(to) - norm360(from)
        if (diff > 180.0) diff -= 360.0
        if (diff < -180.0) diff += 360.0
        return diff
    }

    private fun rad(deg: Double): Double = deg * PI / 180.0
}
