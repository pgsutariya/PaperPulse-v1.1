package com.example.domain.analytics

import com.example.domain.engine.MarketClock
import com.example.domain.model.MarketSegment
import com.example.domain.model.TradeJournalEntry
import java.math.BigDecimal
import java.time.Instant
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class CorrelationCell(
    val rowSymbol: String,
    val colSymbol: String,
    val correlation: Double?, // [-1.0, 1.0], null if insufficient variance/overlap
    val sharedPeriods: Int,
    val sameSector: Boolean,
    val isDiagonal: Boolean
)

data class SectorExposure(
    val sector: String,
    val tradeCount: Int,
    val sharePercent: Int,
    val netPnl: BigDecimal,
    val symbols: List<String>,
    val isOverExposed: Boolean
)

data class CorrelatedPairAlert(
    val symbolA: String,
    val symbolB: String,
    val correlation: Double,
    val sharedPeriods: Int,
    val sameSector: Boolean,
    val isHedge: Boolean
)

data class TradeCorrelationMatrix(
    val symbols: List<String> = emptyList(),
    val sectorsBySymbol: Map<String, String> = emptyMap(),
    val tradeCountBySymbol: Map<String, Int> = emptyMap(),
    val cells: List<List<CorrelationCell>> = emptyList(),
    val sectorExposures: List<SectorExposure> = emptyList(),
    val highCorrelationAlerts: List<CorrelatedPairAlert> = emptyList(),
    val avgAbsCorrelation: Double? = null,
    val bucketModeLabel: String = "Daily P&L",
    val totalTradesAnalyzed: Int = 0
)

object InstrumentSectorClassifier {

    fun classify(symbol: String, segment: MarketSegment): String {
        val raw = symbol.uppercase(Locale.US)
            .substringAfter(":")
            .replace("-EQ", "")
            .replace("-PERP", "")
            .trim()

        if (segment == MarketSegment.CRYPTO) {
            return when {
                raw.startsWith("BTC") || raw.startsWith("ETH") || raw.startsWith("SOL") ||
                    raw.startsWith("BNB") || raw.startsWith("XRP") || raw.startsWith("ADA") ||
                    raw.startsWith("AVAX") -> "Crypto Majors"
                else -> "Crypto Alts"
            }
        }

        if (segment == MarketSegment.MCX) {
            return when {
                raw.startsWith("GOLD") || raw.startsWith("SILVER") || raw.startsWith("COPPER") ||
                    raw.startsWith("ZINC") || raw.startsWith("ALUMINIUM") -> "Metals & Bullion"
                raw.startsWith("CRUDE") || raw.startsWith("NATURALGAS") || raw.startsWith("NATGAS") -> "Energy & Oil"
                else -> "Commodities"
            }
        }

        return when {
            raw.startsWith("BANKNIFTY") || raw.startsWith("FINNIFTY") ||
                raw.startsWith("HDFCBANK") || raw.startsWith("ICICIBANK") || raw.startsWith("SBIN") ||
                raw.startsWith("KOTAKBANK") || raw.startsWith("AXISBANK") || raw.startsWith("INDUSINDBK") ||
                raw.startsWith("BAJFINANCE") || raw.startsWith("BAJAJFINSV") -> "Banking & Finance"

            raw.startsWith("NIFTY") || raw.startsWith("MIDCPNIFTY") || raw.startsWith("SENSEX") ||
                raw.startsWith("BANKEX") -> "Index Derivatives"

            raw.startsWith("TCS") || raw.startsWith("INFY") || raw.startsWith("WIPRO") ||
                raw.startsWith("HCLTECH") || raw.startsWith("TECHM") || raw.startsWith("LTIM") -> "IT Services"

            raw.startsWith("RELIANCE") || raw.startsWith("ONGC") || raw.startsWith("NTPC") ||
                raw.startsWith("POWERGRID") || raw.startsWith("BPCL") || raw.startsWith("IOC") -> "Energy & Oil"

            raw.startsWith("TATAMOTORS") || raw.startsWith("MARUTI") || raw.startsWith("M&M") ||
                raw.startsWith("BAJAJ-AUTO") || raw.startsWith("EICHERMOT") || raw.startsWith("LT") ||
                raw.startsWith("ADANIPORTS") -> "Auto & Infra"

            raw.startsWith("TATASTEEL") || raw.startsWith("HINDALCO") || raw.startsWith("JSWSTEEL") ||
                raw.startsWith("COALINDIA") -> "Metals & Mining"

            raw.startsWith("ITC") || raw.startsWith("HINDUNILVR") || raw.startsWith("NESTLEIND") ||
                raw.startsWith("TITAN") || raw.startsWith("ASIANPAINT") -> "Consumer & FMCG"

            raw.startsWith("SUNPHARMA") || raw.startsWith("DRREDDY") || raw.startsWith("CIPLA") ||
                raw.startsWith("DIVISLAB") -> "Pharma & Health"

            raw.startsWith("BHARTIARTL") -> "Telecom"

            else -> "Equities"
        }
    }
}

/**
 * Pure-Kotlin correlation engine for the Journal tab's Trade Correlation Matrix.
 * Computes pairwise Pearson correlation of realized P&L across traded instruments
 * and flags sector concentration / highly correlated asset exposure.
 */
object TradeCorrelationAnalytics {

    private const val MAX_MATRIX_SYMBOLS = 8

    fun buildCorrelationMatrix(entries: List<TradeJournalEntry>): TradeCorrelationMatrix {
        if (entries.isEmpty()) return TradeCorrelationMatrix()

        val bySymbol = entries.groupBy { it.symbol }
        val rankedSymbols = bySymbol.entries
            .sortedWith(
                compareByDescending<Map.Entry<String, List<TradeJournalEntry>>> { it.value.size }
                    .thenByDescending { entry -> entry.value.sumOf { it.netPnl.abs().toDouble() } }
            )
            .take(MAX_MATRIX_SYMBOLS)
            .map { it.key }

        val sectorsBySymbol = rankedSymbols.associateWith { sym ->
            val seg = bySymbol[sym]?.firstOrNull()?.segment ?: MarketSegment.NSE
            InstrumentSectorClassifier.classify(sym, seg)
        }
        val tradeCountBySymbol = rankedSymbols.associateWith { sym -> bySymbol[sym]?.size ?: 0 }

        // Sector concentration across ALL entries in the filtered set
        val sectorGroups = entries.groupBy { InstrumentSectorClassifier.classify(it.symbol, it.segment) }
        val totalCount = entries.size
        val sectorExposures = sectorGroups.entries
            .map { (sector, list) ->
                val share = ((list.size * 100.0) / totalCount.toDouble()).roundToInt()
                val net = list.fold(BigDecimal.ZERO) { acc, e -> acc.add(e.netPnl) }
                SectorExposure(
                    sector = sector,
                    tradeCount = list.size,
                    sharePercent = share,
                    netPnl = net,
                    symbols = list.map { it.symbol }.distinct(),
                    isOverExposed = totalCount >= 3 && share >= 50
                )
            }
            .sortedByDescending { it.tradeCount }

        // Determine time bucketing granularity: prefer IST calendar day buckets; if all trades
        // fall on a single day, automatically use 1-hour session buckets so intraday traders
        // still see meaningful co-movement correlations.
        val distinctDays = entries
            .map { Instant.ofEpochMilli(it.closedAtMillis).atZone(MarketClock.IST_ZONE).toLocalDate() }
            .distinct()
            .size

        val useHourlyBuckets = distinctDays < 2
        val bucketModeLabel = if (useHourlyBuckets) "Hourly Session P&L" else "Daily P&L"

        fun bucketKey(millis: Long): Long {
            val zdt = Instant.ofEpochMilli(millis).atZone(MarketClock.IST_ZONE)
            return if (useHourlyBuckets) {
                zdt.toLocalDate().toEpochDay() * 24L + zdt.hour.toLong()
            } else {
                zdt.toLocalDate().toEpochDay()
            }
        }

        val allBuckets = entries.map { bucketKey(it.closedAtMillis) }.distinct().sorted()
        val seriesBySymbol: Map<String, Map<Long, Double>> = rankedSymbols.associateWith { sym ->
            bySymbol[sym].orEmpty()
                .groupBy { bucketKey(it.closedAtMillis) }
                .mapValues { (_, list) -> list.sumOf { it.netPnl.toDouble() } }
        }

        val alerts = mutableListOf<CorrelatedPairAlert>()
        val offDiagAbsValues = mutableListOf<Double>()

        val cells = rankedSymbols.mapIndexed { rIdx, rowSym ->
            rankedSymbols.mapIndexed { cIdx, colSym ->
                val sameSector = sectorsBySymbol[rowSym] == sectorsBySymbol[colSym]
                if (rIdx == cIdx) {
                    CorrelationCell(
                        rowSymbol = rowSym,
                        colSymbol = colSym,
                        correlation = 1.0,
                        sharedPeriods = seriesBySymbol[rowSym]?.size ?: 0,
                        sameSector = true,
                        isDiagonal = true
                    )
                } else {
                    val mapA = seriesBySymbol[rowSym].orEmpty()
                    val mapB = seriesBySymbol[colSym].orEmpty()
                    val activeBuckets = allBuckets.filter { mapA.containsKey(it) || mapB.containsKey(it) }
                    val overlapCount = allBuckets.count { mapA.containsKey(it) && mapB.containsKey(it) }

                    val corr = if (activeBuckets.size >= 2 && overlapCount >= 1) {
                        val xs = activeBuckets.map { mapA[it] ?: 0.0 }
                        val ys = activeBuckets.map { mapB[it] ?: 0.0 }
                        pearsonCorrelation(xs, ys)
                    } else {
                        null
                    }

                    if (cIdx > rIdx && corr != null) {
                        offDiagAbsValues.add(abs(corr))
                        if (abs(corr) >= 0.65 && activeBuckets.size >= 2) {
                            alerts += CorrelatedPairAlert(
                                symbolA = rowSym,
                                symbolB = colSym,
                                correlation = corr,
                                sharedPeriods = overlapCount,
                                sameSector = sameSector,
                                isHedge = corr <= -0.65
                            )
                        }
                    }

                    CorrelationCell(
                        rowSymbol = rowSym,
                        colSymbol = colSym,
                        correlation = corr,
                        sharedPeriods = overlapCount,
                        sameSector = sameSector,
                        isDiagonal = false
                    )
                }
            }
        }

        val sortedAlerts = alerts.sortedByDescending { abs(it.correlation) }
        val avgAbs = offDiagAbsValues.takeIf { it.isNotEmpty() }?.average()

        return TradeCorrelationMatrix(
            symbols = rankedSymbols,
            sectorsBySymbol = sectorsBySymbol,
            tradeCountBySymbol = tradeCountBySymbol,
            cells = cells,
            sectorExposures = sectorExposures,
            highCorrelationAlerts = sortedAlerts,
            avgAbsCorrelation = avgAbs,
            bucketModeLabel = bucketModeLabel,
            totalTradesAnalyzed = totalCount
        )
    }

    internal fun pearsonCorrelation(xs: List<Double>, ys: List<Double>): Double? {
        if (xs.size != ys.size || xs.size < 2) return null
        val n = xs.size.toDouble()
        val meanX = xs.sum() / n
        val meanY = ys.sum() / n
        var cov = 0.0
        var varX = 0.0
        var varY = 0.0
        for (i in xs.indices) {
            val dx = xs[i] - meanX
            val dy = ys[i] - meanY
            cov += dx * dy
            varX += dx * dx
            varY += dy * dy
        }
        val denom = sqrt(varX * varY)
        if (denom <= 1e-12 || !denom.isFinite()) return null
        return (cov / denom).coerceIn(-1.0, 1.0)
    }
}
