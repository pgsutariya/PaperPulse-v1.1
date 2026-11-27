package com.example.domain.strategy

import com.example.domain.signal.Candle
import com.example.domain.signal.TechnicalIndicators

/**
 * Chart Scanner - ported from the `trader-in-scanner` / `trader-scanner` skills.
 *
 * Two stages, exactly as the packs do it:
 *  - [findDailySetups] runs over completed daily closes and tags the five named setups.
 *  - [openingRangeBreakout] runs over intraday bars and finds the first bar to CLOSE above
 *    the opening range high.
 *
 * A scan is a list of charts that match a rule, not a list of stocks to buy, and a setup
 * on a chart is not a prediction.
 */
object SetupScanner {

    /** The five daily setups the packs scan for. */
    enum class DailySetup(val key: String, val label: String) {
        BREAKOUT_20D("breakout_20d", "20-day closing-high breakout"),
        BULL_FLAG("bull_flag", "bull flag"),
        RECLAIM_MA20("reclaim_ma20", "reclaimed the 20-day average"),
        OVERSOLD("oversold", "oversold (RSI under 30)"),
        OVERSOLD_BOUNCE("oversold_bounce", "oversold bounce")
    }

    /** Minimum completed daily closes needed before any setup can be judged. */
    const val MIN_DAILY_CLOSES = 30

    data class DailyScan(
        val setups: List<DailySetup>,
        val rsi: Double?,
        val rsiPrev: Double?,
        val high20: Double,
        val ma20: Double,
        val runPct: Double,
        val range5Pct: Double
    ) {
        val hasSetup: Boolean get() = setups.isNotEmpty()
    }

    /**
     * Tag the daily setups on [closes], oldest first. Pass only *completed* daily bars -
     * today's forming bar must be excluded or the breakout flags will flicker intraday.
     *
     * Returns null when there is not enough history.
     */
    fun findDailySetups(closes: List<Double>): DailyScan? {
        val n = closes.size
        if (n < MIN_DAILY_CLOSES) return null

        val last = closes[n - 1]
        if (last <= 0.0) return null

        val high20 = closes.subList(n - 21, n - 1).max()
        val ma20 = closes.subList(n - 20, n).average()
        val ma20Prev = closes.subList(n - 21, n - 1).average()
        val run = closes[n - 6] / closes[n - 16] - 1.0
        val window5 = closes.subList(n - 5, n)
        val range5 = (window5.max() - window5.min()) / last

        val rsi = TechnicalIndicators.rsi(closes).lastOrNull { it != null }
        val rsiPrev = TechnicalIndicators.rsi(closes.subList(0, n - 1)).lastOrNull { it != null }

        val tags = mutableListOf<DailySetup>()
        if (last > high20) tags += DailySetup.BREAKOUT_20D
        // A bull flag is a strong run that has gone quiet: +10% over ten bars, then a tight week.
        if (run > 0.10 && range5 < 0.05) tags += DailySetup.BULL_FLAG
        if (closes[n - 2] < ma20Prev && last > ma20) tags += DailySetup.RECLAIM_MA20
        if (rsi != null && rsi < 30.0) tags += DailySetup.OVERSOLD
        if (rsiPrev != null && rsiPrev < 30.0 && last > closes[n - 2]) tags += DailySetup.OVERSOLD_BOUNCE

        return DailyScan(
            setups = tags, rsi = rsi, rsiPrev = rsiPrev, high20 = high20, ma20 = ma20,
            runPct = run * 100.0, range5Pct = range5 * 100.0
        )
    }

    data class OpeningRange(
        val high: Double,
        val low: Double,
        val bars: Int,
        val averageVolume: Double
    )

    data class Breakout(
        val barIndex: Int,
        val price: Double,
        val openingRangeHigh: Double,
        val openingRangeLow: Double,
        val pctOver: Double,
        val volumeMultiple: Double?
    )

    /**
     * The opening range over the first [rangeBars] bars of [bars] (which must be one
     * session's bars, oldest first). Returns null when the range is not complete yet.
     */
    fun openingRange(bars: List<Candle>, rangeBars: Int): OpeningRange? {
        if (rangeBars <= 0 || bars.size < rangeBars) return null
        val window = bars.subList(0, rangeBars)
        return OpeningRange(
            high = window.maxOf { it.high },
            low = window.minOf { it.low },
            bars = rangeBars,
            averageVolume = window.map { it.volume }.average()
        )
    }

    /**
     * Cheap first pass on closes alone: could this chart possibly have broken out?
     *
     * If no bar after the range even closed at or above the highest close *inside* the
     * range, it cannot have closed above the range high, so the expensive check is skipped.
     * The packs use this to cut a few thousand symbols down to a handful before fetching
     * full OHLC.
     */
    fun couldBreakOut(closes: List<Double>, rangeBars: Int): Boolean {
        if (rangeBars <= 0 || closes.size <= rangeBars) return false
        val top = closes.subList(0, rangeBars).max()
        return closes.subList(rangeBars, closes.size).any { it >= top }
    }

    /**
     * The first completed bar to CLOSE above the opening-range high.
     *
     * Closing above is deliberate: an intrabar spike that falls back is not a breakout.
     * When [minVolumeMultiple] is set, a bar whose volume is under that multiple of the
     * range's average is skipped as too thin to trust.
     */
    fun openingRangeBreakout(
        bars: List<Candle>,
        rangeBars: Int,
        minVolumeMultiple: Double? = null
    ): Breakout? {
        val range = openingRange(bars, rangeBars) ?: return null
        for (i in rangeBars until bars.size) {
            val bar = bars[i]
            if (bar.close <= range.high) continue
            val volumeMultiple = if (range.averageVolume > 0.0) bar.volume / range.averageVolume else null
            if (minVolumeMultiple != null && (volumeMultiple == null || volumeMultiple < minVolumeMultiple)) {
                continue
            }
            return Breakout(
                barIndex = i,
                price = bar.close,
                openingRangeHigh = range.high,
                openingRangeLow = range.low,
                pctOver = (bar.close / range.high - 1.0) * 100.0,
                volumeMultiple = volumeMultiple
            )
        }
        return null
    }
}
