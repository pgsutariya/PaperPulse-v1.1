package com.example.domain.signal

import com.example.domain.model.MarketSegment
import java.math.BigDecimal
import java.time.Instant

/**
 * Chart timeframes. [durationMillis] is exact for intraday/daily/weekly bars and an approximation
 * (30 days) for monthly bars; calendar-correct bucketing lives in
 * [com.example.domain.analysis.SessionCalendar.bucketStart].
 */
enum class Timeframe(val label: String, val durationMillis: Long) {
    M1("1m", 60_000L),
    M3("3m", 180_000L),
    M5("5m", 300_000L),
    M10("10m", 600_000L),
    M15("15m", 900_000L),
    M30("30m", 1_800_000L),
    M45("45m", 2_700_000L),
    H1("1H", 3_600_000L),
    H4("4H", 14_400_000L),
    D1("1D", 86_400_000L),
    W1("1W", 604_800_000L),
    MN1("1Mo", 2_592_000_000L);

    val isIntraday: Boolean get() = durationMillis < D1.durationMillis

    /**
     * Higher-timeframe overlay choices for this chart timeframe
     * (1m -> 3m/5m/10m, 3m -> 5m/10m/15m, 5m -> 10m/15m/30m, 15m -> 30m/45m/1H,
     * 30m -> 45m/1H/4H, 45m -> 1H/4H/1D, 1H -> 4H/1D/1W, 4H -> 1D/1W/1Mo ...).
     */
    val higherTimeframes: List<Timeframe>
        get() = when (this) {
            M1 -> listOf(M3, M5, M10)
            M3 -> listOf(M5, M10, M15)
            M5 -> listOf(M10, M15, M30)
            M10 -> listOf(M15, M30, H1)
            M15 -> listOf(M30, M45, H1)
            M30 -> listOf(M45, H1, H4)
            M45 -> listOf(H1, H4, D1)
            H1 -> listOf(H4, D1, W1)
            H4 -> listOf(D1, W1, MN1)
            D1 -> listOf(W1, MN1)
            W1 -> listOf(MN1)
            MN1 -> emptyList()
        }
}

data class Candle(
    val timestamp: Instant,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
    /**
     * Net aggressor volume (buy-initiated minus sell-initiated) when the feed reports trade side
     * (e.g. Binance trade stream). Null when unknown; analysis then estimates delta from the bar shape.
     */
    val delta: Double? = null
)

enum class SignalStrategy(val label: String, val shortLabel: String) {
    SCALP("Scalping EMA/Mom", "SCALP"),
    INTRADAY("Intraday ORB+VWAP", "INTRADAY"),
    SWING("Swing Heikin Ashi", "SWING"),
    REVERSAL("Reversal Confluence", "REVERSAL"),
    SWIG("Swig HTF+LTF Hybrid", "SWIG"),
    EMA_9_21_SCALP("EMA 9/21 Retest • Scalping", "9/21 SCALP"),
    EMA_9_21_SNIPER("EMA 9/21 Retest • Sniper", "9/21 SNIPER"),
    EMA_9_21_INTRADAY("EMA 9/21 Retest • Intraday", "9/21 INTRA"),
    EMA_50_200_SWING("EMA 50/200 Retest • Swing", "50/200 SWING"),
    EMA_50_200_SHORT_TERM("EMA 50/200 Retest • Short-term", "50/200 ST"),
    EMA_50_200_INVESTING("EMA 50/200 Retest • Investing", "50/200 INVEST"),
    EMA_50_200_LONG_TERM("EMA 50/200 Retest • Long-term", "50/200 LT"),
    SMC_ICT("SMC/ICT Sweep + CHoCH + POI", "SMC/ICT"),
    TRAP("Liquidity Trap Reversal", "TRAP"),
    PIVOT("Pivot / CPR Breakout", "PIVOT"),
    QUANT_MEAN_REVERSION("Quant Z-Score Mean Reversion", "QUANT MR"),
    QUANT_BREAKOUT("Quant Donchian + Squeeze Breakout", "QUANT BO");

    /** Suggested paper-trading product for this strategy family. */
    val prefersCarryForward: Boolean
        get() = when (this) {
            SWING, SWIG, EMA_50_200_SWING, EMA_50_200_SHORT_TERM,
            EMA_50_200_INVESTING, EMA_50_200_LONG_TERM -> true
            else -> false
        }
}

enum class SignalDirection {
    LONG,
    SHORT
}

enum class SignalConfidence(val label: String, val rank: Int) {
    LOW("Low", 1),
    MEDIUM("Medium", 2),
    HIGH("High", 3)
}

enum class SignalLifecycleState {
    ACTIVE,
    ACCEPTED,
    DISMISSED,
    EXPIRED
}

data class SignalFilterCriteria(
    val strategy: SignalStrategy? = null,
    val direction: SignalDirection? = null,
    val segment: MarketSegment? = null,
    val confidence: SignalConfidence? = null,
    val searchQuery: String = ""
)

data class TradeSignal(
    val id: String,
    val symbol: String,
    val segment: MarketSegment,
    val strategy: SignalStrategy,
    val direction: SignalDirection,
    val timeframe: Timeframe,
    val entryPrice: BigDecimal,
    val stopLoss: BigDecimal,
    val target1: BigDecimal,
    val target2: BigDecimal,
    val riskRewardRatio: Double,
    val confidence: SignalConfidence,
    val reasons: List<String>,
    val generatedAt: Instant,
    val expiresAt: Instant?
)

data class SignalContext(
    val symbol: String,
    val segment: MarketSegment,
    val candlesByTimeframe: Map<Timeframe, List<Candle>>,
    val currentLtp: BigDecimal,
    val now: Instant = Instant.now()
)

interface StrategyEvaluator {
    val strategy: SignalStrategy
    fun evaluate(context: SignalContext): TradeSignal?
}

interface SignalEngine {
    fun evaluate(context: SignalContext): List<TradeSignal>
}

enum class AiSignalVerdict(val label: String) {
    ALIGNED("Aligned"),
    CAUTION("Caution"),
    CONFLICTING("Conflicting")
}

enum class AiSignalBias(val label: String) {
    BULLISH("Bullish"),
    BEARISH("Bearish"),
    NEUTRAL("Neutral")
}

data class AiSignalSecondOpinion(
    val referenceId: String,
    val symbol: String,
    val verdict: AiSignalVerdict,
    val bias: AiSignalBias,
    val confidenceScore: Int,
    val summary: String,
    val keyRiskInvalidation: String,
    val executionGuidance: String,
    val riskChecklist: List<String> = emptyList(),
    val generatedAtMillis: Long = System.currentTimeMillis()
)

sealed interface AiUiState<out T> {
    data object Idle : AiUiState<Nothing>
    data object Loading : AiUiState<Nothing>
    data class Streaming(val partialText: String) : AiUiState<Nothing>
    data class Success<T>(val data: T) : AiUiState<T>
    data class Error(val message: String) : AiUiState<Nothing>
}
