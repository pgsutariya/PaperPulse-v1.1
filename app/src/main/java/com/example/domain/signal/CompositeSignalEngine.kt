package com.example.domain.signal

import com.example.domain.analysis.TradingStyle
import com.example.domain.model.InstrumentType
import com.example.domain.signal.evaluators.AiRenkoSupertrendEvaluator
import com.example.domain.signal.evaluators.AsrTradingEvaluator
import com.example.domain.signal.evaluators.AstroCycleEvaluator
import com.example.domain.signal.evaluators.BtstStbtEvaluator
import com.example.domain.signal.evaluators.Confluence70Evaluator
import com.example.domain.signal.evaluators.DataDrivenQuantEvaluator
import com.example.domain.signal.evaluators.DoubleConfirmationEvaluator
import com.example.domain.signal.evaluators.Ema50RsiMacdEvaluator
import com.example.domain.signal.evaluators.EmaCrossRetestEvaluator
import com.example.domain.signal.evaluators.IntradayOrbVwapEvaluator
import com.example.domain.signal.evaluators.MomentumBreakoutEvaluator
import com.example.domain.signal.evaluators.NclTradingEvaluator
import com.example.domain.signal.evaluators.PivotBreakoutEvaluator
import com.example.domain.signal.evaluators.PullbackSniperEvaluator
import com.example.domain.signal.evaluators.QuantBreakoutEvaluator
import com.example.domain.signal.evaluators.QuantMeanReversionEvaluator
import com.example.domain.signal.evaluators.RedBarBreakoutEvaluator
import com.example.domain.signal.evaluators.SmcIctEvaluator
import com.example.domain.signal.evaluators.StockOptionsMomentumEvaluator
import com.example.domain.signal.evaluators.TrapReversalEvaluator
import com.example.domain.signal.evaluators.ReversalConfluenceEvaluator
import com.example.domain.signal.evaluators.ScalpStrategyEvaluator
import com.example.domain.signal.evaluators.SwigHybridEvaluator
import com.example.domain.signal.evaluators.SwingHeikinAshiEvaluator
import java.math.BigDecimal
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

@Singleton
class CompositeSignalEngine(
    private val evaluators: List<StrategyEvaluator>
) : SignalEngine {

    @Inject
    constructor() : this(
        listOf(
            DoubleConfirmationEvaluator(),
            PullbackSniperEvaluator(),
            StockOptionsMomentumEvaluator(),
            DataDrivenQuantEvaluator(),
            AstroCycleEvaluator(),
            AiRenkoSupertrendEvaluator(),
            RedBarBreakoutEvaluator(),
            Confluence70Evaluator(),
            MomentumBreakoutEvaluator(),
            Ema50RsiMacdEvaluator(),
            AsrTradingEvaluator(),
            NclTradingEvaluator(),
            ScalpStrategyEvaluator(),
            IntradayOrbVwapEvaluator(),
            SwingHeikinAshiEvaluator(),
            ReversalConfluenceEvaluator(),
            SwigHybridEvaluator(),
            BtstStbtEvaluator()
        ) + TradingStyle.entries.map { EmaCrossRetestEvaluator(it) } + listOf(
            SmcIctEvaluator(),
            TrapReversalEvaluator(),
            PivotBreakoutEvaluator(),
            QuantMeanReversionEvaluator(),
            QuantBreakoutEvaluator()
        )
    )

    override fun evaluate(context: SignalContext): List<TradeSignal> {
        val astroConfig = context.astroFilterConfig
        val astroSnap = if (astroConfig.enabled) {
            com.example.domain.analysis.AstroTradingEngine.compute(context.now)
        } else {
            null
        }
        return evaluators
            .mapNotNull { evaluator ->
                // One faulty evaluator (e.g. degenerate data) must never take down the whole scan.
                runCatching { evaluator.evaluate(context) }.getOrNull()
            }
            .filter { signal -> isValidSignal(signal, context) }
            .mapNotNull { signal ->
                if (astroSnap == null) {
                    signal
                } else {
                    val eval = com.example.domain.analysis.AstroTradingEngine.evaluateFilterMatch(
                        snapshot = astroSnap,
                        direction = signal.direction,
                        config = astroConfig
                    )
                    if (!eval.passes) {
                        null
                    } else {
                        val hasAstroNote = signal.reasons.any { it.contains("Astro", ignoreCase = true) }
                        if (hasAstroNote) signal else signal.copy(reasons = signal.reasons + eval.detailReason)
                    }
                }
            }
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
        val ordered = when (signal.direction) {
            SignalDirection.LONG ->
                signal.stopLoss < signal.entryPrice &&
                    signal.target1 > signal.entryPrice &&
                    signal.target2 >= signal.target1
            SignalDirection.SHORT ->
                signal.stopLoss > signal.entryPrice &&
                    signal.target1 < signal.entryPrice &&
                    signal.target2 <= signal.target1
        }
        if (!ordered) return false

        // Sanity guard against mixed seed/live candle scales (e.g. $66 SL on $241 SOLUSDT or $56k SL on $84k BTC)
        val entry = signal.entryPrice.toDouble()
        val sl = signal.stopLoss.toDouble()
        val riskPct = abs(entry - sl) / entry
        val maxAllowedRiskPct = if (context.instrumentType == InstrumentType.OPTIONS) 0.40 else 0.12
        if (riskPct < 0.0008 || riskPct > maxAllowedRiskPct) return false

        val ltp = context.currentLtp.toDouble()
        if (ltp > 0.0 && abs(entry - ltp) / ltp > 0.10) return false

        return true
    }
}
