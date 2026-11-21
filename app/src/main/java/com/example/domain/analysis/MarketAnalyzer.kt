package com.example.domain.analysis

import com.example.domain.model.MarketSegment
import com.example.domain.signal.Candle
import com.example.domain.signal.TechnicalIndicators
import com.example.domain.signal.Timeframe
import kotlin.math.abs

data class QuantSnapshot(
    val zScore: Double?,
    val hurst: Double?,
    val regression: RegressionResult?,
    val rocPercent: Double?,
    val realisedVolPercent: Double?,
    val squeezePercentile: Double?,
    val regime: String
)

data class OrderFlowSnapshot(
    val lastDelta: Double,
    val sessionCumDelta: Double,
    val buyVolume20: Double,
    val sellVolume20: Double,
    val absorption: Boolean,
    val usesTrueDelta: Boolean
) {
    val imbalanceRatio: Double get() = if (sellVolume20 > 0) buyVolume20 / sellVolume20 else Double.POSITIVE_INFINITY
}

data class StyleSetupReport(
    val style: TradingStyle,
    val latest: EmaCrossSetup?,
    val backtest: BacktestStats?
)

/** Everything the chart / analysis screen needs for one symbol + timeframe. */
data class ChartAnalysis(
    val candles: List<Candle>,
    val timeframe: Timeframe,
    val segment: MarketSegment?,
    val ema9: List<Double?>,
    val ema21: List<Double?>,
    val ema50: List<Double?>,
    val ema200: List<Double?>,
    val vwap: List<VwapPoint?>,
    val volumeMa20: List<Double?>,
    val rsi14: List<Double?>,
    val atr7: List<Double?>,
    val atr14: List<Double?>,
    val obv: List<Double>,
    val elderForce13: List<Double?>,
    val deltas: List<Double>,
    val cumulativeDelta: List<Double>,
    val volumeProfile: VolumeProfile?,
    val pivots: PivotLevels?,
    val divergences: List<Divergence>,
    val higherTimeframe: Timeframe?,
    val htfCandles: List<HtfCandle>,
    val smc: SmcSnapshot,
    val setups: List<StyleSetupReport>,
    val quant: QuantSnapshot,
    val orderFlow: OrderFlowSnapshot,
    val killZone: SessionCalendar.KillZone?
) {
    val lastClose: Double get() = candles.lastOrNull()?.close ?: 0.0
    val volumeAboveMa: Boolean
        get() {
            val ma = volumeMa20.lastOrNull() ?: return false
            return ma > 0.0 && (candles.lastOrNull()?.volume ?: 0.0) > ma
        }
}

object MarketAnalyzer {

    fun analyze(
        candles: List<Candle>,
        timeframe: Timeframe,
        segment: MarketSegment?,
        higherTimeframe: Timeframe?,
        backtestRatio: Double = 2.0
    ): ChartAnalysis {
        val closes = candles.map { it.close }
        val intraday = timeframe.isIntraday
        val pivotPeriod = when {
            intraday -> PivotPeriod.DAILY
            timeframe == Timeframe.D1 -> PivotPeriod.WEEKLY
            else -> PivotPeriod.MONTHLY
        }
        val htf = higherTimeframe?.takeIf { it.durationMillis > timeframe.durationMillis }
        val htfCandles = if (htf != null) CandleResampler.resampleWithSpans(candles, htf, segment, timeframe) else emptyList()

        val styles = buildList {
            addAll(TradingStyle.forTimeframe(timeframe))
            if (none { it.fastPeriod == 9 }) add(TradingStyle.INTRADAY)
            if (none { it.fastPeriod == 50 }) add(TradingStyle.SWING)
        }
        val setups = styles.map { style ->
            val history = EmaCrossRetestEngine.history(candles, style, timeframe, segment)
            StyleSetupReport(
                style = style,
                latest = history.lastOrNull(),
                backtest = if (history.isNotEmpty()) EmaCrossRetestEngine.backtest(candles, history, backtestRatio) else null
            )
        }

        val deltas = AdvancedIndicators.deltas(candles)
        val sessionCum = AdvancedIndicators.cumulativeDelta(candles, segment, resetEachSession = intraday)
        val last20 = candles.takeLast(20)
        var buy = 0.0
        var sell = 0.0
        last20.forEach { c ->
            val d = AdvancedIndicators.barDelta(c)
            val v = c.volume.coerceAtLeast(0.0)
            buy += ((v + d) / 2.0).coerceIn(0.0, v)
            sell += ((v - d) / 2.0).coerceIn(0.0, v)
        }
        val atr14 = TechnicalIndicators.atr(candles, 14)
        val volMa = AdvancedIndicators.volumeSma(candles, 20)
        val absorption = candles.lastOrNull()?.let { c ->
            val ma = volMa.lastOrNull()
            val a = atr14.lastOrNull()
            ma != null && a != null && ma > 0 && c.volume > 2.0 * ma && (c.high - c.low) < 0.6 * a
        } ?: false

        val z = AdvancedIndicators.zScore(closes, 20).lastOrNull()
        val hurst = AdvancedIndicators.hurstExponent(closes.takeLast(256))
        val reg = AdvancedIndicators.linearRegression(closes, 50.coerceAtMost(closes.size))
        val squeeze = AdvancedIndicators.percentileRankOfLast(AdvancedIndicators.bollingerBandwidth(closes), 120)
        val regime = when {
            hurst == null -> "Insufficient data"
            hurst > 0.55 && (reg?.r2 ?: 0.0) > 0.5 -> "Trending (persistent) — favour breakouts / EMA retests"
            hurst < 0.45 -> "Mean-reverting — favour z-score fades / VWAP band reversion"
            else -> "Random-walk / mixed — reduce size, wait for confluence"
        }

        val divergences = AdvancedIndicators.rsiDivergences(candles) +
            AdvancedIndicators.volumeDivergences(candles) +
            AdvancedIndicators.deltaDivergences(candles, segment)

        return ChartAnalysis(
            candles = candles,
            timeframe = timeframe,
            segment = segment,
            ema9 = TechnicalIndicators.ema(closes, 9),
            ema21 = TechnicalIndicators.ema(closes, 21),
            ema50 = TechnicalIndicators.ema(closes, 50),
            ema200 = TechnicalIndicators.ema(closes, 200),
            vwap = AdvancedIndicators.vwapWithBands(candles, segment, resetEachSession = intraday),
            volumeMa20 = volMa,
            rsi14 = TechnicalIndicators.rsi(closes, 14),
            atr7 = TechnicalIndicators.atr(candles, 7),
            atr14 = atr14,
            obv = AdvancedIndicators.obv(candles),
            elderForce13 = AdvancedIndicators.elderForceIndex(candles, 13),
            deltas = deltas,
            cumulativeDelta = sessionCum,
            volumeProfile = AdvancedIndicators.volumeProfile(candles.takeLast(if (intraday) 300 else 200), 28),
            pivots = AdvancedIndicators.previousPeriodPivots(candles, segment, pivotPeriod),
            divergences = divergences.filter { it.toIndex >= candles.size - 150 },
            higherTimeframe = htf,
            htfCandles = htfCandles,
            smc = SmcAnalyzer.analyze(candles, timeframe, segment),
            setups = setups,
            quant = QuantSnapshot(
                zScore = z,
                hurst = hurst,
                regression = reg,
                rocPercent = AdvancedIndicators.rateOfChange(closes, 10),
                realisedVolPercent = AdvancedIndicators.realisedVolatilityPercent(closes, 20),
                squeezePercentile = squeeze,
                regime = regime
            ),
            orderFlow = OrderFlowSnapshot(
                lastDelta = deltas.lastOrNull() ?: 0.0,
                sessionCumDelta = sessionCum.lastOrNull() ?: 0.0,
                buyVolume20 = buy,
                sellVolume20 = sell,
                absorption = absorption,
                usesTrueDelta = candles.takeLast(20).any { it.delta != null }
            ),
            killZone = candles.lastOrNull()?.let {
                SessionCalendar.activeKillZone(it.timestamp.toEpochMilli(), segment)
            }
        )
    }

    /** Nearest named pivot level to [price], for quick labels. */
    fun nearestPivot(pivots: PivotLevels?, price: Double): Pair<String, Double>? =
        pivots?.namedLevels()?.minByOrNull { abs(it.second - price) }
}
