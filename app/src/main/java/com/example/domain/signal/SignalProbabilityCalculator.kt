package com.example.domain.signal

import kotlin.math.roundToInt

/**
 * Estimates a signal's winning probability (as an integer percentage 35..88%) by combining:
 * 1. Strategy structural prior & confidence tier
 * 2. Number of confirming technical confluences and Risk:Reward geometry
 * 3. Bayesian shrinkage blend with live resolved [SignalRecord] outcomes when available
 */
object SignalProbabilityCalculator {

    private const val PRIOR_WEIGHT = 6.0
    private const val MIN_PROBABILITY = 35
    private const val MAX_PROBABILITY = 88

    fun baseStrategyPrior(strategy: SignalStrategy): Double = when (strategy) {
        SignalStrategy.CONFLUENCE_70 -> 65.0
        SignalStrategy.AI_RENKO_SUPERTREND -> 64.0
        SignalStrategy.MOMENTUM_BREAKOUT -> 63.0
        SignalStrategy.SMC_ICT -> 62.0
        SignalStrategy.RED_BAR_BREAKOUT -> 62.0
        SignalStrategy.EMA50_RSI_MACD -> 61.0
        SignalStrategy.ASR_TRADING -> 60.0
        SignalStrategy.NCL_TRADING -> 60.0
        SignalStrategy.EMA_50_200_SWING,
        SignalStrategy.EMA_50_200_SHORT_TERM,
        SignalStrategy.EMA_50_200_INVESTING,
        SignalStrategy.EMA_50_200_LONG_TERM -> 59.0
        SignalStrategy.QUANT_MEAN_REVERSION,
        SignalStrategy.QUANT_BREAKOUT -> 59.0
        SignalStrategy.EMA_9_21_SNIPER,
        SignalStrategy.EMA_9_21_INTRADAY,
        SignalStrategy.SWIG -> 58.0
        SignalStrategy.SWING,
        SignalStrategy.REVERSAL,
        SignalStrategy.TRAP,
        SignalStrategy.PIVOT -> 57.0
        SignalStrategy.INTRADAY,
        SignalStrategy.EMA_9_21_SCALP,
        SignalStrategy.SCALP,
        SignalStrategy.BTST_STBT -> 55.0
    }

    fun estimate(
        signal: TradeSignal,
        historicalRecords: List<SignalRecord> = emptyList()
    ): Int {
        var prior = baseStrategyPrior(signal.strategy)

        prior += when (signal.confidence) {
            SignalConfidence.HIGH -> 7.0
            SignalConfidence.MEDIUM -> 0.0
            SignalConfidence.LOW -> -8.0
        }

        prior += when {
            signal.reasons.size >= 4 -> 4.0
            signal.reasons.size == 3 -> 2.0
            signal.reasons.size <= 1 -> -2.0
            else -> 0.0
        }

        prior += when {
            signal.riskRewardRatio in 1.8..3.2 -> 2.0
            signal.riskRewardRatio > 4.2 -> -2.0
            signal.riskRewardRatio < 1.2 -> -3.0
            else -> 0.0
        }

        // Empirical Bayesian update from decided live signal records for this strategy / symbol
        val matchingStrategyDecided = historicalRecords.filter { rec ->
            rec.strategy == signal.strategy && rec.outcome != SignalOutcome.EXPIRED
        }
        val matchingSymbolDecided = historicalRecords.filter { rec ->
            rec.symbol.equals(signal.symbol, ignoreCase = true) && rec.outcome != SignalOutcome.EXPIRED
        }

        val combinedPool = when {
            matchingStrategyDecided.isNotEmpty() -> matchingStrategyDecided
            matchingSymbolDecided.isNotEmpty() -> matchingSymbolDecided
            else -> emptyList()
        }

        val finalEstimate = if (combinedPool.isNotEmpty()) {
            val wins = combinedPool.count {
                it.outcome == SignalOutcome.HIT_T1 || it.outcome == SignalOutcome.HIT_T2
            }
            val observedWinPct = (wins.toDouble() / combinedPool.size.toDouble()) * 100.0
            val n = combinedPool.size.toDouble()
            (prior * PRIOR_WEIGHT + observedWinPct * n) / (PRIOR_WEIGHT + n)
        } else {
            prior
        }

        return finalEstimate.roundToInt().coerceIn(MIN_PROBABILITY, MAX_PROBABILITY)
    }
}
