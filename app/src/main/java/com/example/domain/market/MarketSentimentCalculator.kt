package com.example.domain.market

import java.util.Locale
import kotlin.math.roundToInt

enum class MarketSentimentRegime(
    val label: String,
    val shortBadge: String,
    val summary: String
) {
    STRONG_BULLISH(
        label = "Strong Bullish",
        shortBadge = "BULL",
        summary = "Broad volume accumulation with supportive earnings valuation."
    ),
    BULLISH(
        label = "Bullish",
        shortBadge = "BULL",
        summary = "Constructive upside volume thrust and healthy market P/E support."
    ),
    NEUTRAL(
        label = "Neutral",
        shortBadge = "NEUT",
        summary = "Balanced buying/selling volume with fair valuation multiples."
    ),
    BEARISH(
        label = "Bearish",
        shortBadge = "BEAR",
        summary = "Distribution volume pressure or stretched P/E multiples weighing on risk."
    ),
    STRONG_BEARISH(
        label = "Strong Bearish",
        shortBadge = "BEAR",
        summary = "Heavy sell-side volume participation with weak breadth and valuation drag."
    );

    val isBullish: Boolean get() = this == STRONG_BULLISH || this == BULLISH
    val isBearish: Boolean get() = this == BEARISH || this == STRONG_BEARISH
}

data class MarketSentimentSnapshot(
    val score: Int = 50,
    val regime: MarketSentimentRegime = MarketSentimentRegime.NEUTRAL,
    val weightedPeRatio: Double = 0.0,
    val medianPeRatio: Double = 0.0,
    val peValuationLabel: String = "Fair (20–26x)",
    val peComponentScore: Int = 50,
    val avgRelativeVolume: Double = 1.0,
    val upVolumeRatioPercent: Int = 50,
    val volumeComponentScore: Int = 50,
    val advancingCount: Int = 0,
    val decliningCount: Int = 0,
    val unchangedCount: Int = 0,
    val totalVolumeFormatted: String = "0",
    val isLive: Boolean = false,
    val sampleCount: Int = 0
)

/**
 * Computes a real-time overall market Bullish/Bearish sentiment score (0..100)
 * from live market P/E ratios, up-vs-down volume thrust, relative volume participation,
 * and market breadth across the tracked NSE equity universe.
 */
object MarketSentimentCalculator {

    fun compute(stocks: List<StockMarketSnapshot>): MarketSentimentSnapshot {
        if (stocks.isEmpty()) return MarketSentimentSnapshot()

        // 1. Real-time Market P/E Ratio (Market-Cap Weighted + Median)
        val validPeStocks = stocks.filter { it.livePeRatio in 1.0..150.0 }
        val totalCapWeight = validPeStocks.sumOf { it.profile.marketCapCr.coerceAtLeast(1.0) }
        val weightedPe = if (validPeStocks.isNotEmpty() && totalCapWeight > 0.0) {
            validPeStocks.sumOf { it.livePeRatio * it.profile.marketCapCr.coerceAtLeast(1.0) } / totalCapWeight
        } else {
            22.5
        }
        val sortedPes = validPeStocks.map { it.livePeRatio }.sorted()
        val medianPe = if (sortedPes.isEmpty()) {
            weightedPe
        } else {
            val mid = sortedPes.size / 2
            if (sortedPes.size % 2 == 0) {
                (sortedPes[mid - 1] + sortedPes[mid]) / 2.0
            } else {
                sortedPes[mid]
            }
        }

        val peValuationLabel = when {
            weightedPe < 18.0 -> "Deep Value (<18x)"
            weightedPe < 22.0 -> "Attractive (18–22x)"
            weightedPe <= 26.5 -> "Fair (22–26.5x)"
            weightedPe <= 32.0 -> "Elevated (26.5–32x)"
            else -> "Stretched (>32x)"
        }

        val peScore = computePeComponentScore(weightedPe)

        // 2. Real-time Volume Thrust (Up-Volume Share + Relative Volume Participation)
        var upVolume = 0.0
        var totalVolume = 0.0
        var relVolSum = 0.0
        var advancing = 0
        var declining = 0
        var unchanged = 0

        stocks.forEach { stock ->
            val vol = stock.volume.coerceAtLeast(0L).toDouble()
            totalVolume += vol
            relVolSum += stock.relativeVolume.coerceIn(0.15, 5.0)
            when {
                stock.changePercent > 0.0 -> {
                    upVolume += vol
                    advancing += 1
                }
                stock.changePercent < 0.0 -> {
                    declining += 1
                }
                else -> {
                    upVolume += vol * 0.5
                    unchanged += 1
                }
            }
        }

        val upVolumeRatio = if (totalVolume > 0.0) {
            (upVolume / totalVolume).coerceIn(0.0, 1.0)
        } else {
            0.5
        }
        val upVolumePercent = (upVolumeRatio * 100.0).roundToInt().coerceIn(0, 100)
        val avgRelVol = (relVolSum / stocks.size.toDouble()).coerceIn(0.1, 6.0)

        // Volume conviction: direction comes from Up-Volume share, amplified when market volume
        // runs above its 20-day average (relativeVolume > 1.0).
        val directionSign = (upVolumeRatio - 0.5) * 2.0 // -1.0 .. +1.0
        val relVolBoost = (avgRelVol - 1.0).coerceIn(-0.4, 1.6) * 18.0 * directionSign
        val volumeScore = (upVolumeRatio * 100.0 + relVolBoost).roundToInt().coerceIn(5, 98)

        // 3. Breadth Confirmation (Advancing vs Declining)
        val breadthScore = (((advancing + unchanged * 0.5) / stocks.size.toDouble()) * 100.0)
            .roundToInt()
            .coerceIn(0, 100)

        // Composite weighted score: Volume (45%) + P/E Valuation Support (35%) + Breadth (20%)
        val compositeScore = (
            volumeScore * 0.45 +
                peScore * 0.35 +
                breadthScore * 0.20
            ).roundToInt().coerceIn(0, 100)

        val regime = when {
            compositeScore >= 70 -> MarketSentimentRegime.STRONG_BULLISH
            compositeScore >= 56 -> MarketSentimentRegime.BULLISH
            compositeScore >= 45 -> MarketSentimentRegime.NEUTRAL
            compositeScore >= 31 -> MarketSentimentRegime.BEARISH
            else -> MarketSentimentRegime.STRONG_BEARISH
        }

        return MarketSentimentSnapshot(
            score = compositeScore,
            regime = regime,
            weightedPeRatio = weightedPe,
            medianPeRatio = medianPe,
            peValuationLabel = peValuationLabel,
            peComponentScore = peScore,
            avgRelativeVolume = avgRelVol,
            upVolumeRatioPercent = upVolumePercent,
            volumeComponentScore = volumeScore,
            advancingCount = advancing,
            decliningCount = declining,
            unchangedCount = unchanged,
            totalVolumeFormatted = formatCompactVolume(totalVolume.toLong()),
            isLive = stocks.any { it.isLiveQuote },
            sampleCount = stocks.size
        )
    }

    private fun computePeComponentScore(weightedPe: Double): Int {
        val raw = when {
            weightedPe <= 15.0 -> 88.0
            weightedPe <= 22.0 -> 88.0 - ((weightedPe - 15.0) / 7.0) * 18.0 // 88 -> 70
            weightedPe <= 28.0 -> 70.0 - ((weightedPe - 22.0) / 6.0) * 20.0 // 70 -> 50
            weightedPe <= 36.0 -> 50.0 - ((weightedPe - 28.0) / 8.0) * 24.0 // 50 -> 26
            else -> 26.0 - ((weightedPe - 36.0).coerceAtMost(24.0) / 24.0) * 16.0 // 26 -> 10
        }
        return raw.roundToInt().coerceIn(10, 92)
    }

    private fun formatCompactVolume(volume: Long): String {
        return when {
            volume >= 1_000_000_000L -> String.format(Locale.US, "%.2fB sh", volume / 1_000_000_000.0)
            volume >= 1_000_000L -> String.format(Locale.US, "%.1fM sh", volume / 1_000_000.0)
            volume >= 1_000L -> String.format(Locale.US, "%.1fK sh", volume / 1_000.0)
            else -> "$volume sh"
        }
    }
}
