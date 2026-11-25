package com.example.domain.analysis

import com.example.domain.signal.Candle
import com.example.domain.signal.TechnicalIndicators
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class VolatilityRegime(val label: String) {
    EXPANSION("Expansion (High Vol)"),
    NORMAL("Normal Regime"),
    COMPRESSION("Compression (Low Vol)")
}

enum class RenkoConviction(val label: String) {
    HIGH("High (ADX + EMA Confirmed)"),
    MEDIUM("Moderate"),
    CHOP_FILTERED("Filtered (Low ADX Chop)")
}

data class RenkoSupertrendSignal(
    val index: Int,
    val isBullish: Boolean,
    val price: Double,
    val trailLevel: Double,
    val adx: Double?,
    val emaAligned: Boolean,
    val brickSize: Double,
    val adaptiveFactor: Double,
    val conviction: RenkoConviction
)

data class AiRenkoConfig(
    val brickSizeMultiplier: Double = 0.85, // 0.30x .. 2.50x ATR
    val atrSensitivity: Double = 2.6,       // 1.2x .. 4.5x ATR factor
    val atrPeriod: Int = 14,                // 7, 10, 14, or 21
    val adaptiveMode: Boolean = true
)

data class AiRenkoSupertrendSnapshot(
    val bullLine: List<Double?> = emptyList(),
    val bearLine: List<Double?> = emptyList(),
    val renkoCloses: List<Double> = emptyList(),
    val adx14: List<Double?> = emptyList(),
    val signals: List<RenkoSupertrendSignal> = emptyList(),
    val currentBullish: Boolean = true,
    val currentTrail: Double? = null,
    val currentBrickSize: Double = 0.0,
    val currentFactor: Double = 2.6,
    val currentAdx: Double? = null,
    val volatilityRatio: Double = 1.0,
    val volatilityRegime: VolatilityRegime = VolatilityRegime.NORMAL,
    val isChoppyMarket: Boolean = false,
    val emaAligned: Boolean = false,
    val totalBricksFormed: Int = 0,
    val appliedConfig: AiRenkoConfig = AiRenkoConfig()
)

/**
 * AI Renko Supertrend Indicator (Institutional Renko Engine / Brahmastra V2 style).
 *
 * Combines:
 * 1. Synthetic Renko-style noise filtering (price-movement bricks rather than fixed time noise).
 * 2. Adaptive ATR Supertrend engine comparing Fast ATR(7) vs Slow ATR(21) volatility regimes to
 *    dynamically widen or tighten brick size and trend multiplier around user-tuned parameters.
 * 3. ADX(14) choppy-market filter, EMA(21/50) alignment, and candle-close confirmation to reduce
 *    whipsaw flips during sideways consolidation.
 */
object AiRenkoSupertrendEngine {

    private const val ADX_TREND_THRESHOLD = 18.0
    private const val ADX_STRONG_THRESHOLD = 23.0

    fun compute(
        candles: List<Candle>,
        ema21: List<Double?> = emptyList(),
        ema50: List<Double?> = emptyList(),
        config: AiRenkoConfig = AiRenkoConfig()
    ): AiRenkoSupertrendSnapshot {
        val n = candles.size
        val safeBrickMult = config.brickSizeMultiplier.coerceIn(0.30, 2.50)
        val safeSensitivity = config.atrSensitivity.coerceIn(1.2, 4.5)
        val safeAtrPeriod = config.atrPeriod.coerceIn(5, 30)
        val effectiveConfig = config.copy(
            brickSizeMultiplier = safeBrickMult,
            atrSensitivity = safeSensitivity,
            atrPeriod = safeAtrPeriod
        )
        if (n < safeAtrPeriod + 8) return AiRenkoSupertrendSnapshot(appliedConfig = effectiveConfig)

        val atrFast = TechnicalIndicators.atr(candles, 7)
        val atrMain = TechnicalIndicators.atr(candles, safeAtrPeriod)
        val atrSlow = TechnicalIndicators.atr(candles, 21)
        val adxSeries = computeAdx(candles, 14)

        val bullLine = MutableList<Double?>(n) { null }
        val bearLine = MutableList<Double?>(n) { null }
        val renkoCloses = MutableList(n) { candles[it].close }
        val signals = mutableListOf<RenkoSupertrendSignal>()

        // Seed synthetic Renko anchor
        var renkoAnchor = candles[0].close
        var renkoDir = 1 // +1 up brick, -1 down brick
        var totalBricks = 0

        var finalUpper = Double.NaN
        var finalLower = Double.NaN
        var isBullish = true

        var latestBrickSize = (candles[0].high - candles[0].low).coerceAtLeast(candles[0].close * 0.001)
        var latestFactor = safeSensitivity
        var latestVolRatio = 1.0

        for (i in 0 until n) {
            val c = candles[i]
            val fast = atrFast.getOrNull(i)
            val midAtr = atrMain.getOrNull(i) ?: (c.high - c.low).coerceAtLeast(c.close * 0.001)
            val slow = atrSlow.getOrNull(i) ?: midAtr

            val volRatio = if (fast != null && slow > 1e-9) {
                (fast / slow).coerceIn(0.6, 1.8)
            } else {
                1.0
            }
            latestVolRatio = volRatio

            // Brick size responds to user brickSizeMultiplier and (when adaptiveMode is on) Fast/Slow ATR regime
            val volScale = if (effectiveConfig.adaptiveMode) volRatio.coerceIn(0.70, 1.45) else 1.0
            val brickSize = (slow * volScale * safeBrickMult)
                .coerceAtLeast(c.close * 0.0005)
            latestBrickSize = brickSize

            // Supertrend multiplier adapts around user atrSensitivity
            val adaptiveFactor = if (effectiveConfig.adaptiveMode) {
                (safeSensitivity * (0.85 + 0.30 * volRatio)).coerceIn(1.0, 5.5)
            } else {
                safeSensitivity
            }
            latestFactor = adaptiveFactor

            // Synthetic Renko brick update (1 brick continuation, 2 bricks reversal to filter noise)
            val diff = c.close - renkoAnchor
            if (renkoDir >= 0) {
                if (diff >= brickSize) {
                    val steps = (diff / brickSize).toInt().coerceAtLeast(1)
                    renkoAnchor += steps * brickSize
                    totalBricks += steps
                } else if (diff <= -2.0 * brickSize) {
                    val steps = (abs(diff) / brickSize).toInt().coerceAtLeast(1)
                    renkoAnchor -= steps * brickSize
                    renkoDir = -1
                    totalBricks += steps
                }
            } else {
                if (diff <= -brickSize) {
                    val steps = (abs(diff) / brickSize).toInt().coerceAtLeast(1)
                    renkoAnchor -= steps * brickSize
                    totalBricks += steps
                } else if (diff >= 2.0 * brickSize) {
                    val steps = (diff / brickSize).toInt().coerceAtLeast(1)
                    renkoAnchor += steps * brickSize
                    renkoDir = 1
                    totalBricks += steps
                }
            }
            renkoCloses[i] = renkoAnchor

            // Skip band emission until main ATR is warmed up
            if (i < safeAtrPeriod || atrMain[i] == null) continue

            // Blend synthetic Renko anchor with candle midpoint for smooth noise-filtered basis
            val hl2 = (c.high + c.low) / 2.0
            val basis = 0.55 * renkoAnchor + 0.45 * hl2
            val basicUpper = basis + adaptiveFactor * midAtr
            val basicLower = basis - adaptiveFactor * midAtr

            val prevClose = candles[i - 1].close
            val prevUpper = finalUpper
            val prevLower = finalLower

            finalUpper = if (prevUpper.isNaN() || basicUpper < prevUpper || prevClose > prevUpper) {
                basicUpper
            } else {
                prevUpper
            }

            finalLower = if (prevLower.isNaN() || basicLower > prevLower || prevClose < prevLower) {
                basicLower
            } else {
                prevLower
            }

            val prevBullish = isBullish
            val adxNow = adxSeries.getOrNull(i)
            val choppy = adxNow != null && adxNow < ADX_TREND_THRESHOLD

            // Candle-close + Renko-direction confirmation:
            // In choppy markets (low ADX), require Renko brick direction confirmation as well
            // to prevent whipsaw flips on brief spikes.
            val flipUp = !isBullish && c.close > finalUpper && (!choppy || renkoDir > 0)
            val flipDown = isBullish && c.close < finalLower && (!choppy || renkoDir < 0)

            if (flipUp) {
                isBullish = true
            } else if (flipDown) {
                isBullish = false
            }

            val trail = if (isBullish) finalLower else finalUpper
            if (isBullish) {
                bullLine[i] = trail
            } else {
                bearLine[i] = trail
            }

            if (i > 15 && isBullish != prevBullish) {
                val e21 = ema21.getOrNull(i)
                val e50 = ema50.getOrNull(i)
                val emaOk = when {
                    e21 != null && e50 != null -> if (isBullish) c.close >= e21 && e21 >= e50 else c.close <= e21 && e21 <= e50
                    e21 != null -> if (isBullish) c.close >= e21 else c.close <= e21
                    else -> renkoDir == (if (isBullish) 1 else -1)
                }
                val conviction = when {
                    choppy -> RenkoConviction.CHOP_FILTERED
                    (adxNow ?: 20.0) >= ADX_STRONG_THRESHOLD && emaOk -> RenkoConviction.HIGH
                    else -> RenkoConviction.MEDIUM
                }
                signals += RenkoSupertrendSignal(
                    index = i,
                    isBullish = isBullish,
                    price = c.close,
                    trailLevel = trail,
                    adx = adxNow,
                    emaAligned = emaOk,
                    brickSize = brickSize,
                    adaptiveFactor = adaptiveFactor,
                    conviction = conviction
                )
            }
        }

        val lastIdx = n - 1
        val lastAdx = adxSeries.lastOrNull()
        val lastClose = candles[lastIdx].close
        val e21 = ema21.getOrNull(lastIdx)
        val e50 = ema50.getOrNull(lastIdx)
        val lastEmaAligned = when {
            e21 != null && e50 != null -> if (isBullish) lastClose >= e21 && e21 >= e50 else lastClose <= e21 && e21 <= e50
            e21 != null -> if (isBullish) lastClose >= e21 else lastClose <= e21
            else -> false
        }

        val regime = when {
            latestVolRatio >= 1.22 -> VolatilityRegime.EXPANSION
            latestVolRatio <= 0.80 -> VolatilityRegime.COMPRESSION
            else -> VolatilityRegime.NORMAL
        }

        return AiRenkoSupertrendSnapshot(
            bullLine = bullLine,
            bearLine = bearLine,
            renkoCloses = renkoCloses,
            adx14 = adxSeries,
            signals = signals,
            currentBullish = isBullish,
            currentTrail = if (isBullish) bullLine.lastOrNull() else bearLine.lastOrNull(),
            currentBrickSize = latestBrickSize,
            currentFactor = latestFactor,
            currentAdx = lastAdx,
            volatilityRatio = latestVolRatio,
            volatilityRegime = regime,
            isChoppyMarket = lastAdx != null && lastAdx < ADX_TREND_THRESHOLD,
            emaAligned = lastEmaAligned,
            totalBricksFormed = totalBricks,
            appliedConfig = effectiveConfig
        )
    }

    /** Wilder's ADX(period) to measure trend strength and filter choppy/sideways markets. */
    internal fun computeAdx(candles: List<Candle>, period: Int = 14): List<Double?> {
        val n = candles.size
        val result = MutableList<Double?>(n) { null }
        if (n < period * 2 + 1) return result

        val tr = DoubleArray(n)
        val plusDm = DoubleArray(n)
        val minusDm = DoubleArray(n)

        for (i in 1 until n) {
            val c = candles[i]
            val p = candles[i - 1]
            tr[i] = max(c.high - c.low, max(abs(c.high - p.close), abs(c.low - p.close)))
            val upMove = c.high - p.high
            val downMove = p.low - c.low
            plusDm[i] = if (upMove > downMove && upMove > 0.0) upMove else 0.0
            minusDm[i] = if (downMove > upMove && downMove > 0.0) downMove else 0.0
        }

        var smTr = 0.0
        var smPlus = 0.0
        var smMinus = 0.0
        for (i in 1..period) {
            smTr += tr[i]
            smPlus += plusDm[i]
            smMinus += minusDm[i]
        }

        val dx = DoubleArray(n)
        fun calcDx(sTr: Double, sP: Double, sM: Double): Double {
            if (sTr <= 1e-12) return 0.0
            val pDi = 100.0 * sP / sTr
            val mDi = 100.0 * sM / sTr
            val sum = pDi + mDi
            return if (sum <= 1e-12) 0.0 else 100.0 * abs(pDi - mDi) / sum
        }

        dx[period] = calcDx(smTr, smPlus, smMinus)
        for (i in (period + 1) until n) {
            smTr = smTr - (smTr / period) + tr[i]
            smPlus = smPlus - (smPlus / period) + plusDm[i]
            smMinus = smMinus - (smMinus / period) + minusDm[i]
            dx[i] = calcDx(smTr, smPlus, smMinus)
        }

        var adxSum = 0.0
        for (i in period until (period * 2)) {
            adxSum += dx[i]
        }
        var adx = adxSum / period
        result[period * 2 - 1] = adx
        for (i in (period * 2) until n) {
            adx = ((adx * (period - 1)) + dx[i]) / period
            result[i] = adx
        }
        return result
    }
}
