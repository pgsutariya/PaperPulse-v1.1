package com.example.domain.analysis

import com.example.domain.model.InstrumentType
import com.example.domain.signal.Candle
import com.example.domain.signal.SignalDirection
import com.example.domain.signal.SignalStrategy
import com.example.domain.signal.TechnicalIndicators
import com.example.domain.signal.Timeframe
import com.example.domain.signal.evaluators.PullbackSniperAnalyzer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class BacktestExitReason(val label: String) {
    TARGET_1("Target 1"),
    TARGET_2("Target 2"),
    STOP_LOSS("Stop Loss"),
    RENKO_FLIP("Renko Flip"),
    TIME_EXIT("Horizon Exit")
}

data class BacktestTrade(
    val id: String,
    val strategy: SignalStrategy,
    val direction: SignalDirection,
    val entryIndex: Int,
    val exitIndex: Int,
    val entryTimeMillis: Long,
    val exitTimeMillis: Long,
    val entryPrice: Double,
    val exitPrice: Double,
    val pnlPercent: Double,
    val rMultiple: Double,
    val exitReason: BacktestExitReason,
    val convictionLabel: String
) {
    val isWin: Boolean get() = pnlPercent > 0.0
    val barsHeld: Int get() = (exitIndex - entryIndex).coerceAtLeast(1)
}

data class StrategyBacktestReport(
    val strategyName: String,
    val strategy: SignalStrategy,
    val totalTrades: Int = 0,
    val wins: Int = 0,
    val losses: Int = 0,
    val winRatePercent: Double = 0.0,
    val profitFactor: Double = 0.0,
    val netReturnPercent: Double = 0.0,
    val maxDrawdownPercent: Double = 0.0,
    val expectancyPercent: Double = 0.0,
    val avgWinPercent: Double = 0.0,
    val avgLossPercent: Double = 0.0,
    val avgRiskReward: Double = 0.0,
    val equityCurve: List<Double> = listOf(100.0),
    val trades: List<BacktestTrade> = emptyList()
)

data class DualStrategyBacktestSnapshot(
    val symbol: String = "",
    val timeframe: Timeframe = Timeframe.M5,
    val barsAnalyzed: Int = 0,
    val calibration: StrategyCalibrationConfig = StrategyCalibrationConfig(),
    val renkoReport: StrategyBacktestReport = StrategyBacktestReport(
        strategyName = "AI Renko Supertrend",
        strategy = SignalStrategy.AI_RENKO_SUPERTREND
    ),
    val sniperReport: StrategyBacktestReport = StrategyBacktestReport(
        strategyName = "Pullback Sniper",
        strategy = SignalStrategy.PULLBACK_SNIPER
    ),
    val combinedTrades: Int = 0,
    val combinedWinRatePercent: Double = 0.0,
    val combinedProfitFactor: Double = 0.0,
    val combinedNetReturnPercent: Double = 0.0
)

/**
 * Walk-forward historical Strategy Backtester for the active 'AI Renko Supertrend' and
 * 'Pullback Sniper' configurations. Evaluates historical OHLCV bars without look-ahead bias
 * to compute hypothetical win rate, profit factor, net return, max drawdown, equity curve,
 * and simulated trade log.
 */
object StrategyBacktesterEngine {

    private const val MAX_HOLD_BARS_RENKO = 28
    private const val MAX_HOLD_BARS_SNIPER = 20

    fun run(
        symbol: String,
        timeframe: Timeframe,
        candles: List<Candle>,
        instrumentType: InstrumentType = InstrumentType.STOCK,
        calibration: StrategyCalibrationConfig = StrategyCalibrationConfig()
    ): DualStrategyBacktestSnapshot {
        if (candles.size < 45) {
            return DualStrategyBacktestSnapshot(
                symbol = symbol,
                timeframe = timeframe,
                barsAnalyzed = candles.size,
                calibration = calibration
            )
        }

        val renkoTrades = backtestAiRenkoSupertrend(candles, calibration.renkoConfig)
        val sniperTrades = backtestPullbackSniper(candles, instrumentType, calibration.sniperConfig)

        val renkoReport = summarize(
            strategyName = "AI Renko Supertrend",
            strategy = SignalStrategy.AI_RENKO_SUPERTREND,
            trades = renkoTrades
        )
        val sniperReport = summarize(
            strategyName = "Pullback Sniper",
            strategy = SignalStrategy.PULLBACK_SNIPER,
            trades = sniperTrades
        )

        val allTrades = (renkoTrades + sniperTrades).sortedBy { it.entryIndex }
        val combinedWins = allTrades.count { it.isWin }
        val combinedGrossWin = allTrades.filter { it.pnlPercent > 0.0 }.sumOf { it.pnlPercent }
        val combinedGrossLoss = allTrades.filter { it.pnlPercent < 0.0 }.sumOf { abs(it.pnlPercent) }
        val combinedWinRate = if (allTrades.isNotEmpty()) (combinedWins.toDouble() / allTrades.size) * 100.0 else 0.0
        val combinedPf = computeProfitFactor(combinedGrossWin, combinedGrossLoss)
        val combinedNet = allTrades.sumOf { it.pnlPercent }

        return DualStrategyBacktestSnapshot(
            symbol = symbol,
            timeframe = timeframe,
            barsAnalyzed = candles.size,
            calibration = calibration,
            renkoReport = renkoReport,
            sniperReport = sniperReport,
            combinedTrades = allTrades.size,
            combinedWinRatePercent = combinedWinRate,
            combinedProfitFactor = combinedPf,
            combinedNetReturnPercent = combinedNet
        )
    }

    private fun backtestAiRenkoSupertrend(
        candles: List<Candle>,
        config: AiRenkoConfig
    ): List<BacktestTrade> {
        val closes = candles.map { it.close }
        val ema21 = TechnicalIndicators.ema(closes, 21)
        val ema50 = TechnicalIndicators.ema(closes, 50)
        val snap = AiRenkoSupertrendEngine.compute(candles, ema21, ema50, config)
        if (snap.signals.isEmpty()) return emptyList()

        // Prefer non-chop-filtered flips; if strict filter yields zero trades, include all flips so user sees sensitivity impact
        val usableSignals = snap.signals.filter { it.conviction != RenkoConviction.CHOP_FILTERED }
            .ifEmpty { snap.signals }

        val trades = mutableListOf<BacktestTrade>()
        var nextAllowedIndex = 0
        val signalByIndex = snap.signals.associateBy { it.index }

        for (sig in usableSignals) {
            val entryIdx = sig.index
            if (entryIdx < nextAllowedIndex || entryIdx >= candles.lastIndex) continue

            val isLong = sig.isBullish
            val entryPrice = sig.price.coerceAtLeast(1e-6)
            val rawRisk = if (isLong) entryPrice - sig.trailLevel else sig.trailLevel - entryPrice
            val minRisk = (entryPrice * 0.0018).coerceAtLeast(sig.brickSize * 0.8)
            val maxRisk = entryPrice * 0.06
            val risk = rawRisk.takeIf { it in minRisk..maxRisk }
                ?: (sig.brickSize * 1.8).coerceIn(minRisk, maxRisk)

            val sl = if (isLong) entryPrice - risk else entryPrice + risk
            val target1 = if (isLong) entryPrice + risk * 2.0 else entryPrice - risk * 2.0

            val maxExit = min(candles.lastIndex, entryIdx + MAX_HOLD_BARS_RENKO)
            var exitIdx = maxExit
            var exitPrice = candles[maxExit].close
            var exitReason = BacktestExitReason.TIME_EXIT

            for (j in (entryIdx + 1)..maxExit) {
                val bar = candles[j]
                val hitSl = if (isLong) bar.low <= sl else bar.high >= sl
                val hitT1 = if (isLong) bar.high >= target1 else bar.low <= target1
                val oppositeFlip = signalByIndex[j]?.let { it.isBullish != isLong } == true

                when {
                    hitSl -> {
                        exitIdx = j
                        exitPrice = sl
                        exitReason = BacktestExitReason.STOP_LOSS
                        break
                    }
                    hitT1 -> {
                        exitIdx = j
                        exitPrice = target1
                        exitReason = BacktestExitReason.TARGET_1
                        break
                    }
                    oppositeFlip -> {
                        exitIdx = j
                        exitPrice = bar.close
                        exitReason = BacktestExitReason.RENKO_FLIP
                        break
                    }
                }
            }

            val pnlPct = if (isLong) {
                ((exitPrice - entryPrice) / entryPrice) * 100.0
            } else {
                ((entryPrice - exitPrice) / entryPrice) * 100.0
            }
            val rMult = if (risk > 1e-9) {
                (if (isLong) exitPrice - entryPrice else entryPrice - exitPrice) / risk
            } else 0.0

            trades += BacktestTrade(
                id = "bt_renko_${entryIdx}_$exitIdx",
                strategy = SignalStrategy.AI_RENKO_SUPERTREND,
                direction = if (isLong) SignalDirection.LONG else SignalDirection.SHORT,
                entryIndex = entryIdx,
                exitIndex = exitIdx,
                entryTimeMillis = candles[entryIdx].timestamp.toEpochMilli(),
                exitTimeMillis = candles[exitIdx].timestamp.toEpochMilli(),
                entryPrice = entryPrice,
                exitPrice = exitPrice,
                pnlPercent = pnlPct,
                rMultiple = rMult,
                exitReason = exitReason,
                convictionLabel = sig.conviction.label
            )
            nextAllowedIndex = exitIdx + 1
        }
        return trades
    }

    private fun backtestPullbackSniper(
        candles: List<Candle>,
        instrumentType: InstrumentType,
        config: PullbackSniperConfig
    ): List<BacktestTrade> {
        if (candles.size < 56) return emptyList()

        val trades = mutableListOf<BacktestTrade>()
        var i = 54
        while (i < candles.lastIndex - 1) {
            val windowStart = max(0, i - 79) // 80-bar rolling context for VWAP + EMA50 + Fib
            val slice = candles.subList(windowStart, i + 1)
            val assessment = PullbackSniperAnalyzer.analyze(
                candles = slice,
                instrumentType = instrumentType,
                minConfidenceScore = config.minConfidenceScore,
                bandToleranceMultiplier = config.bandToleranceMultiplier
            )
            if (assessment == null) {
                i++
                continue
            }

            val isLong = assessment.direction == SignalDirection.LONG
            val entryPrice = assessment.entry.coerceAtLeast(1e-6)
            val sl = assessment.stopLoss
            val target1 = assessment.target1
            val risk = abs(entryPrice - sl).coerceAtLeast(entryPrice * 0.001)

            val maxExit = min(candles.lastIndex, i + MAX_HOLD_BARS_SNIPER)
            var exitIdx = maxExit
            var exitPrice = candles[maxExit].close
            var exitReason = BacktestExitReason.TIME_EXIT

            for (j in (i + 1)..maxExit) {
                val bar = candles[j]
                val hitSl = if (isLong) bar.low <= sl else bar.high >= sl
                val hitT1 = if (isLong) bar.high >= target1 else bar.low <= target1
                when {
                    hitSl -> {
                        exitIdx = j
                        exitPrice = sl
                        exitReason = BacktestExitReason.STOP_LOSS
                        break
                    }
                    hitT1 -> {
                        exitIdx = j
                        exitPrice = target1
                        exitReason = BacktestExitReason.TARGET_1
                        break
                    }
                }
            }

            val pnlPct = if (isLong) {
                ((exitPrice - entryPrice) / entryPrice) * 100.0
            } else {
                ((entryPrice - exitPrice) / entryPrice) * 100.0
            }
            val rMult = (if (isLong) exitPrice - entryPrice else entryPrice - exitPrice) / risk

            trades += BacktestTrade(
                id = "bt_sniper_${i}_$exitIdx",
                strategy = SignalStrategy.PULLBACK_SNIPER,
                direction = assessment.direction,
                entryIndex = i,
                exitIndex = exitIdx,
                entryTimeMillis = candles[i].timestamp.toEpochMilli(),
                exitTimeMillis = candles[exitIdx].timestamp.toEpochMilli(),
                entryPrice = entryPrice,
                exitPrice = exitPrice,
                pnlPercent = pnlPct,
                rMultiple = rMult,
                exitReason = exitReason,
                convictionLabel = "Conf ${assessment.confidenceScore}/100 • ${assessment.zoneType.label}"
            )
            // Advance past trade exit + 1 bar cooldown
            i = exitIdx + 2
        }
        return trades
    }

    private fun summarize(
        strategyName: String,
        strategy: SignalStrategy,
        trades: List<BacktestTrade>
    ): StrategyBacktestReport {
        if (trades.isEmpty()) {
            return StrategyBacktestReport(
                strategyName = strategyName,
                strategy = strategy
            )
        }
        val winList = trades.filter { it.pnlPercent > 0.0 }
        val lossList = trades.filter { it.pnlPercent <= 0.0 }
        val wins = winList.size
        val losses = lossList.size
        val winRate = (wins.toDouble() / trades.size) * 100.0
        val grossWin = winList.sumOf { it.pnlPercent }
        val grossLoss = lossList.sumOf { abs(it.pnlPercent) }
        val pf = computeProfitFactor(grossWin, grossLoss)

        val avgWin = if (winList.isNotEmpty()) grossWin / winList.size else 0.0
        val avgLoss = if (lossList.isNotEmpty()) grossLoss / lossList.size else 0.0
        val expectancy = trades.map { it.pnlPercent }.average()
        val avgRr = if (avgLoss > 1e-6) (avgWin / avgLoss).coerceIn(0.0, 9.99) else if (avgWin > 0.0) 3.0 else 0.0

        // Compounded equity curve starting at 100.0
        val equity = ArrayList<Double>(trades.size + 1)
        var eq = 100.0
        var peak = 100.0
        var maxDd = 0.0
        equity.add(eq)
        for (t in trades) {
            eq *= (1.0 + (t.pnlPercent / 100.0)).coerceAtLeast(0.05)
            equity.add(eq)
            if (eq > peak) peak = eq
            val dd = if (peak > 0.0) ((peak - eq) / peak) * 100.0 else 0.0
            if (dd > maxDd) maxDd = dd
        }
        val netReturn = eq - 100.0

        return StrategyBacktestReport(
            strategyName = strategyName,
            strategy = strategy,
            totalTrades = trades.size,
            wins = wins,
            losses = losses,
            winRatePercent = winRate,
            profitFactor = pf,
            netReturnPercent = netReturn,
            maxDrawdownPercent = maxDd,
            expectancyPercent = expectancy,
            avgWinPercent = avgWin,
            avgLossPercent = avgLoss,
            avgRiskReward = avgRr,
            equityCurve = equity,
            trades = trades.reversed() // newest first for ledger
        )
    }

    private fun computeProfitFactor(grossWin: Double, grossLoss: Double): Double {
        return when {
            grossWin <= 0.0 -> 0.0
            grossLoss <= 1e-6 -> 9.99 // All winning trades — cap display cleanly at 9.99
            else -> (grossWin / grossLoss).coerceIn(0.0, 9.99)
        }
    }
}
