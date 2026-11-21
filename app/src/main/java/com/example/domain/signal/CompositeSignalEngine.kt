package com.example.domain.signal

import com.example.domain.analysis.TradingStyle
import com.example.domain.signal.evaluators.EmaCrossRetestEvaluator
import com.example.domain.signal.evaluators.IntradayOrbVwapEvaluator
import com.example.domain.signal.evaluators.PivotBreakoutEvaluator
import com.example.domain.signal.evaluators.QuantBreakoutEvaluator
import com.example.domain.signal.evaluators.QuantMeanReversionEvaluator
import com.example.domain.signal.evaluators.SmcIctEvaluator
import com.example.domain.signal.evaluators.TrapReversalEvaluator
import com.example.domain.signal.evaluators.ReversalConfluenceEvaluator
import com.example.domain.signal.evaluators.ScalpStrategyEvaluator
import com.example.domain.signal.evaluators.SwigHybridEvaluator
import com.example.domain.signal.evaluators.SwingHeikinAshiEvaluator
import java.math.BigDecimal
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CompositeSignalEngine(
    private val evaluators: List<StrategyEvaluator>
) : SignalEngine {

    @Inject
    constructor() : this(
        listOf(
            ScalpStrategyEvaluator(),
            IntradayOrbVwapEvaluator(),
            SwingHeikinAshiEvaluator(),
            ReversalConfluenceEvaluator(),
            SwigHybridEvaluator()
        ) + TradingStyle.entries.map { EmaCrossRetestEvaluator(it) } + listOf(
            SmcIctEvaluator(),
            TrapReversalEvaluator(),
            PivotBreakoutEvaluator(),
            QuantMeanReversionEvaluator(),
            QuantBreakoutEvaluator()
        )
    )

    override fun evaluate(context: SignalContext): List<TradeSignal> {
        return evaluators
            .mapNotNull { evaluator ->
                // One faulty evaluator (e.g. degenerate data) must never take down the whole scan.
                runCatching { evaluator.evaluate(context) }.getOrNull()
            }
            .filter { signal -> isValidSignal(signal, context) }
            .sortedWith(
                compareByDescending<TradeSignal> { it.confidence.rank }
                    .thenByDescending { it.riskRewardRatio }
            )
    }

    private fun isValidSignal(signal: TradeSignal, context: SignalContext): Boolean {
        val notExpired = signal.expiresAt == null || signal.expiresAt.isAfter(context.now)
        if (!notExpired) return false
        if (signal.entryPrice <= BigDecimal.ZERO) return false
        if (signal.stopLoss <= BigDecimal.ZERO) return false
        if (signal.target1 <= BigDecimal.ZERO) return false
        if (signal.target2 <= BigDecimal.ZERO) return false
        if (signal.riskRewardRatio <= 0.0) return false
        return when (signal.direction) {
            SignalDirection.LONG ->
                signal.stopLoss < signal.entryPrice &&
                    signal.target1 > signal.entryPrice &&
                    signal.target2 >= signal.target1
            SignalDirection.SHORT ->
                signal.stopLoss > signal.entryPrice &&
                    signal.target1 < signal.entryPrice &&
                    signal.target2 <= signal.target1
        }
    }
}
