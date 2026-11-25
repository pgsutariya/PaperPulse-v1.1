package com.example.domain.analytics

import com.example.domain.engine.MarketClock
import com.example.domain.model.TradeJournalEntry
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.sqrt

data class HourlyTradingIntensity(
    val hour: Int, // 0..23 IST
    val hourLabel: String, // e.g. "09:00"
    val shortLabel: String, // e.g. "09h"
    val tradeCount: Int = 0,
    val wins: Int = 0,
    val losses: Int = 0,
    val netPnl: BigDecimal = BigDecimal.ZERO,
    val intensity: Float = 0f, // 0f..1f
    val level: Int = 0 // 0 = none, 1 = low, 2 = moderate, 3 = high, 4 = peak
) {
    val winRatePercent: Int
        get() = if (tradeCount == 0) 0 else (wins * 100) / tradeCount
}

data class TradingIntensityHeatmapData(
    val hours: List<HourlyTradingIntensity> = (0..23).map { h ->
        HourlyTradingIntensity(
            hour = h,
            hourLabel = String.format(Locale.US, "%02d:00", h),
            shortLabel = String.format(Locale.US, "%02dh", h)
        )
    },
    val totalTrades: Int = 0,
    val activeHoursCount: Int = 0,
    val maxTradesInHour: Int = 0,
    val peakHour: HourlyTradingIntensity? = null,
    val peakWindowLabel: String = "No trades yet",
    val peakWindowTradeCount: Int = 0,
    val peakWindowSharePercent: Int = 0
)

data class RiskManagementScorecard(
    val totalTrades: Int = 0,
    val calmarRatio: Double? = null,
    val sharpeRatio: Double? = null,
    val avgRiskRewardRatio: Double? = null, // Avg Win / |Avg Loss| -> displayed as 1 : X.XX
    val maxDrawdownPercent: Double = 0.0,
    val maxDrawdownAmount: BigDecimal = BigDecimal.ZERO,
    val annualizedReturnPercent: Double = 0.0,
    val totalReturnPercent: Double = 0.0,
    val expectancyPerTrade: BigDecimal = BigDecimal.ZERO,
    val distinctTradingDays: Int = 0,
    val isIntradaySharpeEstimate: Boolean = false,
    val grade: String = "—"
)

object JournalRiskAnalytics {

    /**
     * Builds a 24-hour (00:00–23:00 IST) trading frequency heatmap from closed journal trades.
     * Uses trade open timestamp (`openedAtMillis`, falling back to `closedAtMillis`) so the
     * heatmap reflects when the user actively initiates trades.
     */
    fun buildTradingIntensityHeatmap(
        entries: List<TradeJournalEntry>
    ): TradingIntensityHeatmapData {
        if (entries.isEmpty()) return TradingIntensityHeatmapData()

        val groupedByHour = entries.groupBy { entry ->
            val ts = if (entry.openedAtMillis > 0L) entry.openedAtMillis else entry.closedAtMillis
            Instant.ofEpochMilli(ts).atZone(MarketClock.IST_ZONE).hour
        }

        val maxCount = groupedByHour.values.maxOfOrNull { it.size } ?: 0
        var activeHours = 0

        val hours = (0..23).map { hour ->
            val list = groupedByHour[hour].orEmpty()
            val count = list.size
            if (count > 0) activeHours += 1
            val wins = list.count { it.netPnl > BigDecimal.ZERO }
            val losses = list.count { it.netPnl < BigDecimal.ZERO }
            val netPnl = list.fold(BigDecimal.ZERO) { acc, e -> acc.add(e.netPnl) }
            val ratio = if (maxCount == 0 || count == 0) 0f else count.toFloat() / maxCount.toFloat()
            val level = when {
                count == 0 -> 0
                ratio <= 0.25f -> 1
                ratio <= 0.50f -> 2
                ratio <= 0.78f -> 3
                else -> 4
            }
            HourlyTradingIntensity(
                hour = hour,
                hourLabel = String.format(Locale.US, "%02d:00", hour),
                shortLabel = String.format(Locale.US, "%02dh", hour),
                tradeCount = count,
                wins = wins,
                losses = losses,
                netPnl = netPnl,
                intensity = if (count == 0) 0f else ratio.coerceIn(0.2f, 1f),
                level = level
            )
        }

        val peakHour = hours.filter { it.tradeCount > 0 }.maxByOrNull { it.tradeCount }

        // Find the most active contiguous 3-hour trading window
        var bestWindowStart = 0
        var bestWindowCount = -1
        for (start in 0..21) {
            val windowSum = hours[start].tradeCount + hours[start + 1].tradeCount + hours[start + 2].tradeCount
            if (windowSum > bestWindowCount) {
                bestWindowCount = windowSum
                bestWindowStart = start
            }
        }
        val windowShare = if (entries.isEmpty() || bestWindowCount <= 0) {
            0
        } else {
            ((bestWindowCount * 100.0) / entries.size.toDouble()).toInt().coerceIn(0, 100)
        }
        val windowLabel = if (bestWindowCount > 0) {
            String.format(Locale.US, "%02d:00–%02d:00 IST", bestWindowStart, bestWindowStart + 3)
        } else {
            "No trades yet"
        }

        return TradingIntensityHeatmapData(
            hours = hours,
            totalTrades = entries.size,
            activeHoursCount = activeHours,
            maxTradesInHour = maxCount,
            peakHour = peakHour,
            peakWindowLabel = windowLabel,
            peakWindowTradeCount = bestWindowCount.coerceAtLeast(0),
            peakWindowSharePercent = windowShare
        )
    }

    /**
     * Calculates Calmar Ratio, Sharpe Ratio, Average Risk-to-Reward Ratio, Max Drawdown,
     * and Expectancy from chronological closed trade history.
     */
    fun buildRiskManagementScorecard(
        entries: List<TradeJournalEntry>,
        startingCapital: BigDecimal
    ): RiskManagementScorecard {
        if (entries.isEmpty()) return RiskManagementScorecard()

        val safeBaseCapital = if (startingCapital > BigDecimal.ZERO) {
            startingCapital.toDouble()
        } else {
            100_000.0
        }

        val chronological = entries.sortedBy { it.closedAtMillis }
        val totalNetPnl = chronological.fold(BigDecimal.ZERO) { acc, e -> acc.add(e.netPnl) }
        val expectancy = totalNetPnl.divide(BigDecimal(chronological.size), 2, RoundingMode.HALF_UP)

        // 1. Average Risk-to-Reward Ratio (Avg Win / |Avg Loss|)
        val wins = chronological.filter { it.netPnl > BigDecimal.ZERO }
        val losses = chronological.filter { it.netPnl < BigDecimal.ZERO }
        val avgWin = if (wins.isEmpty()) 0.0 else {
            wins.sumOf { it.netPnl.toDouble() } / wins.size.toDouble()
        }
        val avgLossAbs = if (losses.isEmpty()) 0.0 else {
            losses.sumOf { it.netPnl.abs().toDouble() } / losses.size.toDouble()
        }
        val avgRiskReward: Double? = when {
            avgLossAbs > 0.0 && wins.isNotEmpty() -> avgWin / avgLossAbs
            avgWin > 0.0 && losses.isEmpty() -> Double.POSITIVE_INFINITY
            losses.isNotEmpty() && wins.isEmpty() -> 0.0
            else -> null
        }

        // 2. Equity curve & Maximum Drawdown (absolute & percentage)
        var equity = safeBaseCapital
        var peakEquity = safeBaseCapital
        var maxDdAbs = 0.0
        var maxDdPct = 0.0

        val tradeReturns = ArrayList<Double>(chronological.size)
        for (trade in chronological) {
            val denom = equity.coerceAtLeast(safeBaseCapital * 0.1)
            tradeReturns.add(trade.netPnl.toDouble() / denom)
            equity += trade.netPnl.toDouble()
            if (equity > peakEquity) {
                peakEquity = equity
            }
            val ddAbs = (peakEquity - equity).coerceAtLeast(0.0)
            val ddPct = if (peakEquity > 0.0) (ddAbs / peakEquity) * 100.0 else 0.0
            if (ddAbs > maxDdAbs) maxDdAbs = ddAbs
            if (ddPct > maxDdPct) maxDdPct = ddPct
        }

        // 3. Daily return series for Sharpe Ratio (annualized √252 across trading days,
        // or intraday trade-level estimate when all trades occur on a single day)
        val byDate = chronological.groupBy { entry ->
            Instant.ofEpochMilli(entry.closedAtMillis).atZone(MarketClock.IST_ZONE).toLocalDate()
        }.toSortedMap()

        val distinctDays = byDate.size
        var isIntradayEstimate = false
        val sharpeRatio: Double? = if (distinctDays >= 2) {
            var runningDayEquity = safeBaseCapital
            val dailyReturns = byDate.values.map { dayTrades ->
                val dayPnl = dayTrades.sumOf { it.netPnl.toDouble() }
                val r = dayPnl / runningDayEquity.coerceAtLeast(safeBaseCapital * 0.1)
                runningDayEquity += dayPnl
                r
            }
            computeSharpe(dailyReturns, annualizationFactor = sqrt(252.0))
        } else if (tradeReturns.size >= 2) {
            isIntradayEstimate = true
            computeSharpe(tradeReturns, annualizationFactor = sqrt(tradeReturns.size.toDouble()))
        } else {
            null
        }

        // 4. Annualized Return & Calmar Ratio (Annualized Return % / Max Drawdown %)
        val totalReturnPct = (totalNetPnl.toDouble() / safeBaseCapital) * 100.0
        val firstDate = byDate.firstKey()
        val lastDate = byDate.lastKey()
        val elapsedDays = (ChronoUnit.DAYS.between(firstDate, lastDate).toInt() + 1).coerceAtLeast(1)
        // Use a minimum 30-day normalization floor when annualizing very short paper sessions
        // to avoid extreme blowups while preserving proportional return scaling.
        val effectiveHorizonDays = elapsedDays.coerceAtLeast(30)
        val annualizedReturnPct = (totalReturnPct * (365.0 / effectiveHorizonDays.toDouble()))
            .coerceIn(-999.9, 999.9)

        val calmarRatio: Double? = when {
            maxDdPct > 0.0001 -> annualizedReturnPct / maxDdPct
            annualizedReturnPct > 0.0 -> Double.POSITIVE_INFINITY
            annualizedReturnPct < 0.0 -> -1.0
            else -> 0.0
        }

        val grade = computeRiskGrade(
            sharpe = sharpeRatio,
            calmar = calmarRatio,
            avgRr = avgRiskReward,
            maxDdPct = maxDdPct
        )

        return RiskManagementScorecard(
            totalTrades = chronological.size,
            calmarRatio = calmarRatio,
            sharpeRatio = sharpeRatio,
            avgRiskRewardRatio = avgRiskReward,
            maxDrawdownPercent = maxDdPct,
            maxDrawdownAmount = BigDecimal.valueOf(maxDdAbs).setScale(2, RoundingMode.HALF_UP),
            annualizedReturnPercent = annualizedReturnPct,
            totalReturnPercent = totalReturnPct,
            expectancyPerTrade = expectancy,
            distinctTradingDays = distinctDays,
            isIntradaySharpeEstimate = isIntradayEstimate,
            grade = grade
        )
    }

    private fun computeSharpe(returns: List<Double>, annualizationFactor: Double): Double? {
        if (returns.size < 2) return null
        val mean = returns.average()
        val variance = returns.sumOf { (it - mean) * (it - mean) } / (returns.size - 1).toDouble()
        val stdDev = sqrt(variance)
        if (stdDev <= 1e-12) return null
        return ((mean / stdDev) * annualizationFactor).coerceIn(-99.9, 99.9)
    }

    private fun computeRiskGrade(
        sharpe: Double?,
        calmar: Double?,
        avgRr: Double?,
        maxDdPct: Double
    ): String {
        var points = 0
        var factors = 0

        if (sharpe != null && sharpe.isFinite()) {
            factors += 1
            points += when {
                sharpe >= 2.0 -> 4
                sharpe >= 1.0 -> 3
                sharpe >= 0.3 -> 2
                sharpe >= 0.0 -> 1
                else -> 0
            }
        }
        if (calmar != null) {
            factors += 1
            points += when {
                calmar.isInfinite() && calmar > 0 -> 4
                calmar >= 2.5 -> 4
                calmar >= 1.2 -> 3
                calmar >= 0.4 -> 2
                calmar >= 0.0 -> 1
                else -> 0
            }
        }
        if (avgRr != null) {
            factors += 1
            points += when {
                avgRr.isInfinite() || avgRr >= 2.0 -> 4
                avgRr >= 1.4 -> 3
                avgRr >= 1.0 -> 2
                avgRr >= 0.6 -> 1
                else -> 0
            }
        }
        // Bonus check for drawdown control
        factors += 1
        points += when {
            maxDdPct <= 2.0 -> 4
            maxDdPct <= 5.0 -> 3
            maxDdPct <= 10.0 -> 2
            maxDdPct <= 20.0 -> 1
            else -> 0
        }

        if (factors == 0) return "—"
        val avgScore = points.toDouble() / factors.toDouble()
        return when {
            avgScore >= 3.5 -> "A+"
            avgScore >= 2.9 -> "A"
            avgScore >= 2.2 -> "B"
            avgScore >= 1.4 -> "C"
            else -> "D"
        }
    }
}
