package com.example.domain.market

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class MarketCapTier(val label: String) {
    LARGE_CAP("Large Cap"),
    MID_CAP("Mid Cap"),
    SMALL_CAP("Small Cap")
}

enum class MoverCategory(val label: String, val shortLabel: String) {
    TOP_GAINERS("Top Gainers Today", "Top Gainers"),
    TOP_LOSERS("Top Losers Today", "Top Losers"),
    WEEK_52_HIGH("New 52W High Stocks", "52W High"),
    WEEK_52_LOW("New 52W Low Stocks", "52W Low"),
    TOP_VOLUME("Top By Volume", "Top Volume")
}

enum class GlobalRegion(val label: String) {
    ALL("All"),
    INDIA("India"),
    US("US"),
    EUROPE("Europe"),
    ASIA("Asia"),
    MACRO("Macro & FX")
}

enum class RsiFilterZone(val label: String, val shortLabel: String) {
    ANY("Any RSI", "Any"),
    OVERSOLD("Oversold (RSI <= 35)", "Oversold <=35"),
    NEUTRAL("Neutral (35 - 65)", "Neutral"),
    BULLISH_ZONE("Bullish (55 - 70)", "Bullish 55-70"),
    OVERBOUGHT("Overbought (RSI >= 70)", "Overbought >=70")
}

enum class MaCrossoverSignal(val label: String, val shortLabel: String) {
    GOLDEN_CROSS("Golden Cross (20>50>200)", "Golden Cross"),
    BULLISH_CROSSOVER("Bullish EMA20 > SMA50", "Bullish Cross"),
    BEARISH_CROSSOVER("Bearish EMA20 < SMA50", "Bearish Cross"),
    DEATH_CROSS("Death Cross (20<50<200)", "Death Cross")
}

data class TechnicalSnapshot(
    val rsi14: Double,
    val ema20: Double,
    val sma50: Double,
    val sma200: Double,
    val maCrossover: MaCrossoverSignal,
    val aboveSma200: Boolean
) {
    val rsiZoneLabel: String
        get() = when {
            rsi14 >= 70.0 -> "Overbought"
            rsi14 <= 35.0 -> "Oversold"
            rsi14 >= 55.0 -> "Bullish"
            else -> "Neutral"
        }
}

object TechnicalIndicatorCalculator {

    /**
     * Computes Wilder RSI(14), EMA(20), SMA(50), SMA(200), and moving-average crossover signal
     * from historical daily closing prices (oldest -> newest).
     */
    fun fromCloses(closes: List<Double>, fallbackLtp: Double, fallbackPrevClose: Double): TechnicalSnapshot {
        val clean = closes.filter { !it.isNaN() && it > 0.0 }
        if (clean.size < 16) {
            return synthesizeBaseline(
                ltp = fallbackLtp,
                prevClose = fallbackPrevClose,
                high52 = fallbackLtp * 1.15,
                low52 = fallbackLtp * 0.85,
                profitGrowth = 12.0
            )
        }

        val ltp = clean.last()
        val rsi = computeRsi14(clean)
        val ema20 = computeEma(clean, 20)
        val sma50 = computeSma(clean, minOf(50, clean.size))
        val sma200 = computeSma(clean, minOf(200, clean.size))
        val above200 = ltp >= sma200
        val crossover = classifyMaCrossover(ema20, sma50, sma200)

        return TechnicalSnapshot(
            rsi14 = rsi.coerceIn(5.0, 95.0),
            ema20 = ema20,
            sma50 = sma50,
            sma200 = sma200,
            maCrossover = crossover,
            aboveSma200 = above200
        )
    }

    /**
     * Deterministic technical baseline anchored to 52-week range position, daily momentum, and
     * earnings trend when historical Yahoo candles are unavailable offline.
     */
    fun synthesizeBaseline(
        ltp: Double,
        prevClose: Double,
        high52: Double,
        low52: Double,
        profitGrowth: Double
    ): TechnicalSnapshot {
        val safeLtp = ltp.coerceAtLeast(1.0)
        val safePrev = prevClose.coerceAtLeast(1.0)
        val dayChgPct = ((safeLtp - safePrev) / safePrev) * 100.0
        val rangeSpan = (high52 - low52).coerceAtLeast(1.0)
        val pos52 = ((safeLtp - low52) / rangeSpan).coerceIn(0.0, 1.0)

        val rsi = (22.0 + pos52 * 56.0 + dayChgPct * 3.2 + (profitGrowth.coerceIn(-15.0, 35.0) * 0.12))
            .coerceIn(18.0, 86.0)

        val ema20Factor = 1.0 - ((dayChgPct * 0.008) + ((pos52 - 0.5) * 0.035))
        val sma50Factor = 1.0 - ((pos52 - 0.48) * 0.095)
        val sma200Factor = 1.0 - ((pos52 - 0.42) * 0.18)

        val ema20 = (safeLtp * ema20Factor).coerceAtLeast(0.05)
        val sma50 = (safeLtp * sma50Factor).coerceAtLeast(0.05)
        val sma200 = (safeLtp * sma200Factor).coerceAtLeast(0.05)

        return TechnicalSnapshot(
            rsi14 = rsi,
            ema20 = ema20,
            sma50 = sma50,
            sma200 = sma200,
            maCrossover = classifyMaCrossover(ema20, sma50, sma200),
            aboveSma200 = safeLtp >= sma200
        )
    }

    private fun classifyMaCrossover(ema20: Double, sma50: Double, sma200: Double): MaCrossoverSignal {
        return when {
            ema20 >= sma50 && sma50 >= sma200 -> MaCrossoverSignal.GOLDEN_CROSS
            ema20 >= sma50 -> MaCrossoverSignal.BULLISH_CROSSOVER
            ema20 < sma50 && sma50 < sma200 -> MaCrossoverSignal.DEATH_CROSS
            else -> MaCrossoverSignal.BEARISH_CROSSOVER
        }
    }

    private fun computeRsi14(closes: List<Double>): Double {
        val period = 14
        if (closes.size <= period) return 50.0
        var avgGain = 0.0
        var avgLoss = 0.0
        for (i in 1..period) {
            val delta = closes[i] - closes[i - 1]
            if (delta >= 0.0) avgGain += delta else avgLoss += abs(delta)
        }
        avgGain /= period
        avgLoss /= period

        for (i in (period + 1) until closes.size) {
            val delta = closes[i] - closes[i - 1]
            val gain = if (delta > 0.0) delta else 0.0
            val loss = if (delta < 0.0) abs(delta) else 0.0
            avgGain = ((avgGain * (period - 1)) + gain) / period
            avgLoss = ((avgLoss * (period - 1)) + loss) / period
        }

        if (avgLoss <= 1e-9) return 92.0
        val rs = avgGain / avgLoss
        return 100.0 - (100.0 / (1.0 + rs))
    }

    private fun computeEma(closes: List<Double>, period: Int): Double {
        val safeP = minOf(period, closes.size).coerceAtLeast(1)
        val k = 2.0 / (safeP + 1.0)
        var ema = closes.take(safeP).average()
        for (i in safeP until closes.size) {
            ema = (closes[i] - ema) * k + ema
        }
        return ema
    }

    private fun computeSma(closes: List<Double>, period: Int): Double {
        val safeP = minOf(period, closes.size).coerceAtLeast(1)
        return closes.takeLast(safeP).average()
    }
}

enum class ScreenerPreset(val label: String, val description: String) {
    ALL("All Stocks", "Full liquid NSE equity universe"),
    RSI_OVERSOLD("RSI Oversold", "Technical RSI(14) <= 35 mean-reversion candidates"),
    RSI_OVERBOUGHT("RSI Overbought", "Technical RSI(14) >= 70 stretched momentum"),
    GOLDEN_CROSS("Golden Cross", "EMA20 > SMA50 > SMA200 bullish alignment"),
    HIGH_ROE_QUALITY("High ROE Quality", "ROE >= 18%, ROCE >= 18%, strong moat"),
    VALUE_GEMS("Value Gems", "P/E <= 24, ROE >= 12%, healthy earnings"),
    BREAKOUT_52W_HIGH("52W High Breakout", "Within 6% of 52-week high & positive momentum"),
    VOLUME_SHOCKERS("Volume Breakout", "Relative Volume >= 1.25x 20-day average"),
    HIGH_DIVIDEND("Dividend Leaders", "Dividend Yield >= 1.5% & strong cash flow"),
    LOW_DEBT_COMPOUNDERS("Low Debt Growth", "Debt/Equity <= 0.55 & Profit Growth >= 12%")
}

enum class ScreenerSortBy(val label: String) {
    SYMBOL("Symbol"),
    LTP("LTP"),
    CHANGE_DESC("Chg %"),
    RSI("RSI(14)"),
    PE_ASC("P/E"),
    ROE_DESC("ROE %"),
    MARKET_CAP_DESC("Mkt Cap"),
    VOLUME_DESC("Volume"),
    FUNDAMENTAL_SCORE("Score")
}

data class StockFundamentalProfile(
    val symbol: String,
    val companyName: String,
    val sector: String,
    val marketCapCr: Double,
    val peRatio: Double,
    val pbRatio: Double,
    val evEbitda: Double,
    val roePercent: Double,
    val rocePercent: Double,
    val dividendYieldPercent: Double,
    val debtToEquity: Double,
    val currentRatio: Double,
    val operatingMarginPercent: Double,
    val netProfitMarginPercent: Double,
    val revenueGrowthYoyPercent: Double,
    val profitGrowthYoyPercent: Double,
    val eps: Double,
    val promoterHoldingPercent: Double,
    val fiiHoldingPercent: Double,
    val diiHoldingPercent: Double,
    val refPrice: Double,
    val refPrevClose: Double,
    val ref52WeekHigh: Double,
    val ref52WeekLow: Double,
    val avgVolume20D: Long
) {
    val marketCapTier: MarketCapTier
        get() = when {
            marketCapCr >= 100_000.0 -> MarketCapTier.LARGE_CAP
            marketCapCr >= 25_000.0 -> MarketCapTier.MID_CAP
            else -> MarketCapTier.SMALL_CAP
        }
}

enum class FundamentalVerdict(val label: String) {
    STRONG_BUY_QUALITY("STRONG FUNDAMENTALS"),
    QUALITY_COMPOUNDER("QUALITY COMPOUNDER"),
    VALUE_OPPORTUNITY("VALUE PICK"),
    BALANCED("BALANCED"),
    CAUTION("HIGH VALUATION / RISK")
}

data class FundamentalScorecard(
    val valuationScore: Int,      // 0..100
    val profitabilityScore: Int,  // 0..100
    val growthScore: Int,         // 0..100
    val financialHealthScore: Int,// 0..100
    val overallScore: Int,        // 0..100
    val verdict: FundamentalVerdict,
    val strengths: List<String>,
    val watchouts: List<String>
)

data class QuarterlyFinancialResult(
    val quarterLabel: String,
    val revenueCr: Double,
    val netProfitCr: Double,
    val operatingMarginPct: Double,
    val eps: Double,
    val yoyProfitGrowthPct: Double
)

data class LiveFundamentalDetails(
    val symbol: String,
    val livePeRatio: Double,
    val marketCapCr: Double,
    val dividendYieldPercent: Double,
    val pbRatio: Double,
    val epsTtm: Double,
    val roePercent: Double,
    val debtToEquity: Double,
    val quarterlyResults: List<QuarterlyFinancialResult>,
    val isLiveFetched: Boolean,
    val fetchedAtLabel: String
)

data class StockMarketSnapshot(
    val profile: StockFundamentalProfile,
    val ltp: BigDecimal,
    val previousClose: BigDecimal,
    val dayHigh: BigDecimal,
    val dayLow: BigDecimal,
    val volume: Long,
    val fiftyTwoWeekHigh: BigDecimal,
    val fiftyTwoWeekLow: BigDecimal,
    val isLiveQuote: Boolean,
    val scorecard: FundamentalScorecard,
    val technical: TechnicalSnapshot = TechnicalIndicatorCalculator.synthesizeBaseline(
        ltp = ltp.toDouble(),
        prevClose = previousClose.toDouble(),
        high52 = fiftyTwoWeekHigh.toDouble(),
        low52 = fiftyTwoWeekLow.toDouble(),
        profitGrowth = profile.profitGrowthYoyPercent
    )
) {
    val symbol: String get() = profile.symbol
    val companyName: String get() = profile.companyName
    val sector: String get() = profile.sector

    val change: BigDecimal get() = ltp.subtract(previousClose)

    val changePercent: Double
        get() = if (previousClose > BigDecimal.ZERO) {
            change.divide(previousClose, 6, RoundingMode.HALF_UP).toDouble() * 100.0
        } else 0.0

    val distanceFrom52WeekHighPercent: Double
        get() = if (fiftyTwoWeekHigh > BigDecimal.ZERO) {
            ((fiftyTwoWeekHigh.toDouble() - ltp.toDouble()) / fiftyTwoWeekHigh.toDouble()) * 100.0
        } else 100.0

    val distanceFrom52WeekLowPercent: Double
        get() = if (fiftyTwoWeekLow > BigDecimal.ZERO) {
            ((ltp.toDouble() - fiftyTwoWeekLow.toDouble()) / fiftyTwoWeekLow.toDouble()) * 100.0
        } else 100.0

    val fiftyTwoWeekRangePosition: Float
        get() {
            val low = fiftyTwoWeekLow.toDouble()
            val high = fiftyTwoWeekHigh.toDouble()
            val span = (high - low).coerceAtLeast(0.01)
            return ((ltp.toDouble() - low) / span).toFloat().coerceIn(0f, 1f)
        }

    val isNew52WeekHigh: Boolean get() = distanceFrom52WeekHighPercent <= 3.5
    val isNew52WeekLow: Boolean get() = distanceFrom52WeekLowPercent <= 8.5

    val relativeVolume: Double
        get() = if (profile.avgVolume20D > 0L) {
            volume.toDouble() / profile.avgVolume20D.toDouble()
        } else 1.0

    val livePeRatio: Double
        get() = if (profile.eps > 0.0) {
            (ltp.toDouble() / profile.eps).coerceIn(1.0, 250.0)
        } else profile.peRatio
}

data class CryptoMarketAsset(
    val symbol: String,       // e.g. BTCUSDT
    val ticker: String,       // e.g. BTC
    val name: String,         // e.g. Bitcoin
    val category: String,     // Layer 1, DeFi, Payments, Meme
    val priceUsd: Double,
    val previousCloseUsd: Double,
    val changePercent24h: Double,
    val volume24hUsd: Double,
    val marketCapUsd: Double,
    val isLive: Boolean
)

enum class HeatmapMarketType(val label: String) {
    NSE("NSE Equities"),
    CRYPTO("Crypto"),
    COMBINED("NSE + Crypto")
}

enum class HeatmapScaleMetric(val label: String) {
    MARKET_CAP("Market Cap"),
    VOLUME("Volume"),
    EQUAL("Equal")
}

data class HeatmapAssetNode(
    val id: String,
    val symbol: String,
    val displaySymbol: String,
    val name: String,
    val groupLabel: String,
    val isCrypto: Boolean,
    val priceFormatted: String,
    val rawPrice: Double,
    val changePercent: Double,
    val marketCapNormalizedUsd: Double, // Normalized to USD so NSE + Crypto combine proportionally
    val volumeNormalizedUsd: Double,
    val marketCapLabel: String,
    val volumeLabel: String,
    val rsi14: Double? = null,
    val maSignal: MaCrossoverSignal? = null
)

data class TreemapRect(
    val node: HeatmapAssetNode,
    val left: Float,   // 0f..1f
    val top: Float,    // 0f..1f
    val width: Float,  // 0f..1f
    val height: Float  // 0f..1f
)

/**
 * Pure squarified treemap layout engine (Bruls–Huizing–van Wijk algorithm).
 * Produces non-overlapping rectangles filling [0..1] x [0..1] with minimal aspect ratios,
 * scaling each tile proportionally to market capitalization or trading volume.
 */
object SquarifiedTreemapEngine {

    fun layout(
        nodes: List<HeatmapAssetNode>,
        scaleMetric: HeatmapScaleMetric,
        containerAspectRatio: Float = 1.15f
    ): List<TreemapRect> {
        if (nodes.isEmpty()) return emptyList()

        val weighted = nodes.map { node ->
            val rawWeight = when (scaleMetric) {
                HeatmapScaleMetric.MARKET_CAP -> node.marketCapNormalizedUsd.coerceAtLeast(1.0)
                HeatmapScaleMetric.VOLUME -> node.volumeNormalizedUsd.coerceAtLeast(1.0)
                HeatmapScaleMetric.EQUAL -> 1.0
            }
            // Gentle power compression (0.82) prevents 1 mega-cap (BTC / RELIANCE) from crushing smaller tiles into illegible slivers while preserving visual hierarchy
            val displayWeight = if (scaleMetric == HeatmapScaleMetric.EQUAL) 1.0 else Math.pow(rawWeight, 0.80)
            node to displayWeight.coerceAtLeast(0.01)
        }.sortedByDescending { it.second }

        val totalWeight = weighted.sumOf { it.second }.coerceAtLeast(0.01)
        val aspect = containerAspectRatio.coerceIn(0.4f, 3.0f).toDouble()
        val totalArea = aspect // Virtual canvas width = aspect, height = 1.0

        val scaledItems = weighted.map { (n, w) -> n to (w / totalWeight) * totalArea }
        val results = mutableListOf<TreemapRect>()

        var rx = 0.0
        var ry = 0.0
        var rw = aspect
        var rh = 1.0

        var remaining = scaledItems
        while (remaining.isNotEmpty()) {
            val shortSide = min(rw, rh).coerceAtLeast(1e-6)
            val row = mutableListOf<Pair<HeatmapAssetNode, Double>>()
            row.add(remaining.first())
            var idx = 1

            while (idx < remaining.size) {
                val candidate = remaining[idx]
                val currentWorst = worstAspectRatio(row.map { it.second }, shortSide)
                val nextWorst = worstAspectRatio(row.map { it.second } + candidate.second, shortSide)
                if (nextWorst <= currentWorst) {
                    row.add(candidate)
                    idx++
                } else {
                    break
                }
            }

            val rowArea = row.sumOf { it.second }
            val layoutVertically = rw >= rh // Place vertical strip along left edge when container is wider than tall
            if (layoutVertically) {
                val stripW = (rowArea / rh.coerceAtLeast(1e-6)).coerceIn(0.0, rw)
                var curY = ry
                for ((node, area) in row) {
                    val h = (area / stripW.coerceAtLeast(1e-6)).coerceIn(0.0, ry + rh - curY)
                    results += TreemapRect(
                        node = node,
                        left = (rx / aspect).toFloat().coerceIn(0f, 1f),
                        top = curY.toFloat().coerceIn(0f, 1f),
                        width = (stripW / aspect).toFloat().coerceIn(0f, 1f),
                        height = h.toFloat().coerceIn(0f, 1f)
                    )
                    curY += h
                }
                rx += stripW
                rw = (rw - stripW).coerceAtLeast(0.0)
            } else {
                val stripH = (rowArea / rw.coerceAtLeast(1e-6)).coerceIn(0.0, rh)
                var curX = rx
                for ((node, area) in row) {
                    val w = (area / stripH.coerceAtLeast(1e-6)).coerceIn(0.0, rx + rw - curX)
                    results += TreemapRect(
                        node = node,
                        left = (curX / aspect).toFloat().coerceIn(0f, 1f),
                        top = ry.toFloat().coerceIn(0f, 1f),
                        width = (w / aspect).toFloat().coerceIn(0f, 1f),
                        height = stripH.toFloat().coerceIn(0f, 1f)
                    )
                    curX += w
                }
                ry += stripH
                rh = (rh - stripH).coerceAtLeast(0.0)
            }

            remaining = remaining.drop(row.size)
        }
        return results
    }

    private fun worstAspectRatio(areas: List<Double>, sideLength: Double): Double {
        if (areas.isEmpty()) return Double.MAX_VALUE
        val sum = areas.sum().coerceAtLeast(1e-9)
        val maxA = areas.maxOrNull() ?: return Double.MAX_VALUE
        val minA = (areas.minOrNull() ?: 1e-9).coerceAtLeast(1e-9)
        val s2 = sideLength * sideLength
        val sum2 = sum * sum
        return max((s2 * maxA) / sum2, sum2 / (s2 * minA))
    }
}

data class FiiDiiSessionRecord(
    val dateLabel: String,
    val fiiBuyCr: Double,
    val fiiSellCr: Double,
    val fiiNetCr: Double,
    val diiBuyCr: Double,
    val diiSellCr: Double,
    val diiNetCr: Double,
    val isLiveApi: Boolean = false
) {
    val combinedNetCr: Double get() = fiiNetCr + diiNetCr
}

data class FiiDiiDashboardData(
    val latest: FiiDiiSessionRecord,
    val recentSessions: List<FiiDiiSessionRecord>,
    val mtdFiiNetCr: Double,
    val mtdDiiNetCr: Double,
    val sourceLabel: String,
    val isLiveFromNse: Boolean,
    val lastUpdatedMillis: Long
) {
    val sentimentLabel: String
        get() = when {
            latest.fiiNetCr > 500 && latest.diiNetCr > 500 -> "DUAL INSTITUTIONAL ACCUMULATION"
            latest.fiiNetCr > 500 -> "FII RISK-ON INFLOW"
            latest.diiNetCr > 800 && latest.fiiNetCr < -500 -> "DII ABSORBING FII SELLING"
            latest.fiiNetCr < -1000 && latest.diiNetCr < 0 -> "BROAD INSTITUTIONAL DISTRIBUTION"
            latest.combinedNetCr >= 0 -> "NET INSTITUTIONAL SUPPORT"
            else -> "CAUTIOUS INSTITUTIONAL FLOW"
        }
}

data class GlobalMarketQuote(
    val symbol: String,
    val yahooSymbol: String,
    val name: String,
    val region: GlobalRegion,
    val price: Double,
    val previousClose: Double,
    val currency: String,
    val sessionLabel: String,
    val isLive: Boolean
) {
    val change: Double get() = price - previousClose
    val changePercent: Double
        get() = if (previousClose > 0.0) ((price - previousClose) / previousClose) * 100.0 else 0.0
}

data class ScreenerFilterState(
    val preset: ScreenerPreset = ScreenerPreset.ALL,
    val searchQuery: String = "",
    val selectedSector: String? = null,
    val selectedCapTier: MarketCapTier? = null,
    val maxPe: Double? = null,
    val minRoe: Double? = null,
    val maxDebtToEquity: Double? = null,
    val minDividendYield: Double? = null,
    val minRvol: Double? = null,
    val onlyNear52WeekHigh: Boolean = false,
    val onlyPositiveToday: Boolean = false,
    val rsiFilter: RsiFilterZone = RsiFilterZone.ANY,
    val maCrossoverFilter: MaCrossoverSignal? = null,
    val onlyAboveSma200: Boolean = false,
    val sortBy: ScreenerSortBy = ScreenerSortBy.FUNDAMENTAL_SCORE,
    val sortAscending: Boolean = false,
    val currentPage: Int = 1,
    val pageSize: Int = 8
)

data class PagedScreenerResult(
    val pageItems: List<StockMarketSnapshot>,
    val allMatchingCount: Int,
    val currentPage: Int,
    val totalPages: Int,
    val pageSize: Int,
    val startIndex: Int, // 1-based inclusive
    val endIndex: Int    // 1-based inclusive
)

object FundamentalAnalyzer {

    fun score(profile: StockFundamentalProfile, liveLtp: Double = profile.refPrice): FundamentalScorecard {
        val dynamicPe = if (profile.eps > 0.0) liveLtp / profile.eps else profile.peRatio
        val isFinancial = profile.sector.equals("Banking", ignoreCase = true) ||
            profile.sector.equals("NBFC", ignoreCase = true)

        val peScore = when {
            dynamicPe <= 14.0 -> 94
            dynamicPe <= 20.0 -> 84
            dynamicPe <= 28.0 -> 72
            dynamicPe <= 40.0 -> 58
            dynamicPe <= 60.0 -> 42
            else -> 28
        }
        val pbScore = when {
            profile.pbRatio <= 2.5 -> 90
            profile.pbRatio <= 4.5 -> 76
            profile.pbRatio <= 8.0 -> 60
            else -> 42
        }
        val divBonus = (profile.dividendYieldPercent * 4.0).roundToInt().coerceIn(0, 14)
        val valuationScore = ((peScore * 0.65 + pbScore * 0.35).roundToInt() + divBonus).coerceIn(15, 98)

        val roePts = when {
            profile.roePercent >= 25.0 -> 96
            profile.roePercent >= 18.0 -> 86
            profile.roePercent >= 14.0 -> 74
            profile.roePercent >= 10.0 -> 60
            else -> 40
        }
        val rocePts = when {
            profile.rocePercent >= 25.0 -> 95
            profile.rocePercent >= 18.0 -> 84
            profile.rocePercent >= 13.0 -> 72
            else -> 52
        }
        val marginPts = when {
            profile.operatingMarginPercent >= 25.0 -> 92
            profile.operatingMarginPercent >= 18.0 -> 80
            profile.operatingMarginPercent >= 12.0 -> 66
            else -> 48
        }
        val profitabilityScore = (roePts * 0.45 + rocePts * 0.35 + marginPts * 0.20).roundToInt().coerceIn(20, 98)

        val revPts = when {
            profile.revenueGrowthYoyPercent >= 25.0 -> 95
            profile.revenueGrowthYoyPercent >= 15.0 -> 84
            profile.revenueGrowthYoyPercent >= 9.0 -> 70
            profile.revenueGrowthYoyPercent >= 4.0 -> 56
            else -> 38
        }
        val patPts = when {
            profile.profitGrowthYoyPercent >= 28.0 -> 96
            profile.profitGrowthYoyPercent >= 16.0 -> 84
            profile.profitGrowthYoyPercent >= 10.0 -> 72
            profile.profitGrowthYoyPercent >= 3.0 -> 55
            else -> 35
        }
        val growthScore = (revPts * 0.45 + patPts * 0.55).roundToInt().coerceIn(20, 98)

        val debtPts = if (isFinancial) {
            if (profile.roePercent >= 15.0) 84 else 72
        } else {
            when {
                profile.debtToEquity <= 0.15 -> 96
                profile.debtToEquity <= 0.45 -> 86
                profile.debtToEquity <= 0.85 -> 72
                profile.debtToEquity <= 1.40 -> 56
                else -> 38
            }
        }
        val liqPts = if (isFinancial) {
            78
        } else {
            when {
                profile.currentRatio >= 1.8 -> 92
                profile.currentRatio >= 1.3 -> 80
                profile.currentRatio >= 1.0 -> 68
                else -> 48
            }
        }
        val instSupportBonus = if ((profile.fiiHoldingPercent + profile.diiHoldingPercent) >= 35.0) 6 else 0
        val healthScore = ((debtPts * 0.65 + liqPts * 0.35).roundToInt() + instSupportBonus).coerceIn(20, 98)

        val overall = (
            valuationScore * 0.25 +
                profitabilityScore * 0.30 +
                growthScore * 0.25 +
                healthScore * 0.20
            ).roundToInt().coerceIn(1, 99)

        val verdict = when {
            overall >= 82 -> FundamentalVerdict.STRONG_BUY_QUALITY
            profitabilityScore >= 82 && growthScore >= 75 -> FundamentalVerdict.QUALITY_COMPOUNDER
            valuationScore >= 80 && overall >= 68 -> FundamentalVerdict.VALUE_OPPORTUNITY
            overall >= 60 -> FundamentalVerdict.BALANCED
            else -> FundamentalVerdict.CAUTION
        }

        val strengths = buildList {
            if (profile.roePercent >= 18.0) add("Strong return on equity (ROE ${fmt1(profile.roePercent)}%)")
            if (!isFinancial && profile.debtToEquity <= 0.35) add("Virtually debt-free balance sheet (D/E ${fmt2(profile.debtToEquity)})")
            if (profile.profitGrowthYoyPercent >= 15.0) add("Robust YoY earnings growth (+${fmt1(profile.profitGrowthYoyPercent)}%)")
            if (dynamicPe <= 22.0) add("Reasonable valuation at ${fmt1(dynamicPe)}x P/E")
            if (profile.dividendYieldPercent >= 2.0) add("Attractive dividend yield (${fmt1(profile.dividendYieldPercent)}%)")
            if (profile.operatingMarginPercent >= 22.0) add("High operating moat (${fmt1(profile.operatingMarginPercent)}% OPM)")
            if (isEmpty()) add("Established ${profile.marketCapTier.label} leader in ${profile.sector}")
        }

        val watchouts = buildList {
            if (dynamicPe >= 45.0) add("Rich valuation multiple (${fmt1(dynamicPe)}x P/E) leaves low margin of safety")
            if (!isFinancial && profile.debtToEquity >= 1.0) add("Elevated leverage (Debt/Equity ${fmt2(profile.debtToEquity)})")
            if (profile.revenueGrowthYoyPercent < 7.0) add("Modest top-line revenue growth (${fmt1(profile.revenueGrowthYoyPercent)}% YoY)")
            if (profile.profitGrowthYoyPercent < 5.0) add("Subdued recent profit momentum (${fmt1(profile.profitGrowthYoyPercent)}% YoY)")
            if (profile.dividendYieldPercent < 0.4) add("Low dividend payout (${fmt1(profile.dividendYieldPercent)}% yield)")
            if (isEmpty()) add("Monitor quarterly earnings & global macro sensitivity")
        }

        return FundamentalScorecard(
            valuationScore = valuationScore,
            profitabilityScore = profitabilityScore,
            growthScore = growthScore,
            financialHealthScore = healthScore,
            overallScore = overall,
            verdict = verdict,
            strengths = strengths.take(3),
            watchouts = watchouts.take(3)
        )
    }

    private fun fmt1(v: Double): String = String.format(java.util.Locale.US, "%.1f", v)
    private fun fmt2(v: Double): String = String.format(java.util.Locale.US, "%.2f", v)
}

object StockScreenerEngine {

    fun computeMovers(
        universe: List<StockMarketSnapshot>,
        category: MoverCategory,
        limit: Int = 12
    ): List<StockMarketSnapshot> {
        return when (category) {
            MoverCategory.TOP_GAINERS -> universe.sortedByDescending { it.changePercent }.take(limit)
            MoverCategory.TOP_LOSERS -> universe.sortedBy { it.changePercent }.take(limit)
            MoverCategory.WEEK_52_HIGH -> universe.sortedBy { it.distanceFrom52WeekHighPercent }.take(limit)
            MoverCategory.WEEK_52_LOW -> universe.sortedBy { it.distanceFrom52WeekLowPercent }.take(limit)
            MoverCategory.TOP_VOLUME -> universe.sortedByDescending { it.volume }.take(limit)
        }
    }

    fun filterAndSort(
        universe: List<StockMarketSnapshot>,
        filter: ScreenerFilterState
    ): List<StockMarketSnapshot> {
        val query = filter.searchQuery.trim().lowercase()

        val filtered = universe.filter { item ->
            val p = item.profile
            val tech = item.technical

            val matchesQuery = query.isEmpty() ||
                item.symbol.lowercase().contains(query) ||
                item.companyName.lowercase().contains(query) ||
                item.sector.lowercase().contains(query)

            val matchesPreset = when (filter.preset) {
                ScreenerPreset.ALL -> true
                ScreenerPreset.RSI_OVERSOLD -> tech.rsi14 <= 35.0
                ScreenerPreset.RSI_OVERBOUGHT -> tech.rsi14 >= 70.0
                ScreenerPreset.GOLDEN_CROSS -> tech.maCrossover == MaCrossoverSignal.GOLDEN_CROSS
                ScreenerPreset.HIGH_ROE_QUALITY -> p.roePercent >= 18.0 && p.rocePercent >= 16.0
                ScreenerPreset.VALUE_GEMS -> item.livePeRatio <= 24.0 && p.roePercent >= 12.0
                ScreenerPreset.BREAKOUT_52W_HIGH -> item.distanceFrom52WeekHighPercent <= 6.0 && item.changePercent >= 0.0
                ScreenerPreset.VOLUME_SHOCKERS -> item.relativeVolume >= 1.25
                ScreenerPreset.HIGH_DIVIDEND -> p.dividendYieldPercent >= 1.5
                ScreenerPreset.LOW_DEBT_COMPOUNDERS -> p.debtToEquity <= 0.55 && p.profitGrowthYoyPercent >= 12.0
            }

            val matchesRsi = when (filter.rsiFilter) {
                RsiFilterZone.ANY -> true
                RsiFilterZone.OVERSOLD -> tech.rsi14 <= 35.0
                RsiFilterZone.NEUTRAL -> tech.rsi14 > 35.0 && tech.rsi14 < 65.0
                RsiFilterZone.BULLISH_ZONE -> tech.rsi14 in 55.0..70.0
                RsiFilterZone.OVERBOUGHT -> tech.rsi14 >= 70.0
            }

            val matchesMa = filter.maCrossoverFilter == null || tech.maCrossover == filter.maCrossoverFilter
            val matchesSma200 = !filter.onlyAboveSma200 || tech.aboveSma200

            val matchesSector = filter.selectedSector == null || p.sector.equals(filter.selectedSector, ignoreCase = true)
            val matchesCap = filter.selectedCapTier == null || p.marketCapTier == filter.selectedCapTier
            val matchesPe = filter.maxPe == null || item.livePeRatio <= filter.maxPe
            val matchesRoe = filter.minRoe == null || p.roePercent >= filter.minRoe
            val matchesDebt = filter.maxDebtToEquity == null || p.debtToEquity <= filter.maxDebtToEquity
            val matchesDiv = filter.minDividendYield == null || p.dividendYieldPercent >= filter.minDividendYield
            val matchesRvol = filter.minRvol == null || item.relativeVolume >= filter.minRvol
            val matches52High = !filter.onlyNear52WeekHigh || item.distanceFrom52WeekHighPercent <= 5.0
            val matchesPos = !filter.onlyPositiveToday || item.changePercent > 0.0

            matchesQuery && matchesPreset && matchesRsi && matchesMa && matchesSma200 &&
                matchesSector && matchesCap && matchesPe && matchesRoe && matchesDebt &&
                matchesDiv && matchesRvol && matches52High && matchesPos
        }

        val comparator: Comparator<StockMarketSnapshot> = when (filter.sortBy) {
            ScreenerSortBy.SYMBOL -> compareBy { it.symbol }
            ScreenerSortBy.LTP -> compareBy { it.ltp.toDouble() }
            ScreenerSortBy.CHANGE_DESC -> compareBy { it.changePercent }
            ScreenerSortBy.RSI -> compareBy { it.technical.rsi14 }
            ScreenerSortBy.PE_ASC -> compareBy { it.livePeRatio }
            ScreenerSortBy.ROE_DESC -> compareBy { it.profile.roePercent }
            ScreenerSortBy.MARKET_CAP_DESC -> compareBy { it.profile.marketCapCr }
            ScreenerSortBy.VOLUME_DESC -> compareBy { it.volume }
            ScreenerSortBy.FUNDAMENTAL_SCORE -> compareBy { it.scorecard.overallScore }
        }

        return if (filter.sortAscending) {
            filtered.sortedWith(comparator)
        } else {
            filtered.sortedWith(comparator.reversed())
        }
    }

    fun filterSortAndPaginate(
        universe: List<StockMarketSnapshot>,
        filter: ScreenerFilterState
    ): PagedScreenerResult {
        val all = filterAndSort(universe, filter)
        val safePageSize = filter.pageSize.coerceIn(4, 25)
        val total = all.size
        val totalPages = if (total == 0) 1 else ((total + safePageSize - 1) / safePageSize)
        val clampedPage = filter.currentPage.coerceIn(1, totalPages)
        val fromIdx = (clampedPage - 1) * safePageSize
        val toIdx = minOf(fromIdx + safePageSize, total)
        val slice = if (fromIdx < total) all.subList(fromIdx, toIdx) else emptyList()

        return PagedScreenerResult(
            pageItems = slice,
            allMatchingCount = total,
            currentPage = clampedPage,
            totalPages = totalPages,
            pageSize = safePageSize,
            startIndex = if (total == 0) 0 else fromIdx + 1,
            endIndex = toIdx
        )
    }

    fun computeSectorHeatmap(universe: List<StockMarketSnapshot>): List<SectorHeatmapSummary> {
        return universe
            .groupBy { it.sector }
            .map { (sector, list) ->
                val totalCap = list.sumOf { it.profile.marketCapCr }.coerceAtLeast(1.0)
                val weightedChange = list.sumOf { it.changePercent * it.profile.marketCapCr } / totalCap
                val leader = list.maxByOrNull { it.changePercent }?.symbol ?: ""
                SectorHeatmapSummary(
                    sector = sector,
                    stockCount = list.size,
                    advancingCount = list.count { it.changePercent >= 0.0 },
                    decliningCount = list.count { it.changePercent < 0.0 },
                    weightedChangePercent = weightedChange,
                    totalMarketCapCr = totalCap,
                    topMoverSymbol = leader
                )
            }
            .sortedByDescending { it.weightedChangePercent }
    }
}

data class SectorHeatmapSummary(
    val sector: String,
    val stockCount: Int,
    val advancingCount: Int,
    val decliningCount: Int,
    val weightedChangePercent: Double,
    val totalMarketCapCr: Double,
    val topMoverSymbol: String
)
