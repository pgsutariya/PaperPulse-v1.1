package com.example.ui.screens.chart

import com.example.domain.analysis.ChartAnalysis
import com.example.domain.analysis.DivergenceKind
import com.example.domain.analysis.EmaCrossSetup
import com.example.domain.analysis.LiquiditySide
import com.example.domain.analysis.LiquidityStatus
import com.example.domain.analysis.SetupState
import com.example.domain.analysis.ZoneKind
import com.example.domain.signal.SignalDirection
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.max
import kotlin.math.min

/** Selectable rendering engine for the chart panel ('panel-chart'). */
enum class ChartEngineMode(val label: String) {
    CANVAS("Canvas"),
    TRADINGVIEW("TradingView")
}

/** Series visualization modes supported inside TradingView Lightweight Charts. */
enum class TvChartStyle(val label: String, val jsKey: String) {
    CANDLES("Candles", "candles"),
    HOLLOW("Hollow", "hollow"),
    HEIKIN_ASHI("Heikin Ashi", "heikin_ashi"),
    AREA("Area", "area")
}

@Serializable
data class TvOhlcPoint(
    val time: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double
)

@Serializable
data class TvValuePoint(
    val time: Long,
    val value: Double,
    val color: String? = null
)

@Serializable
data class TvLineSeriesPayload(
    val id: String,
    val title: String,
    val color: String,
    val lineWidth: Int = 2,
    val dashed: Boolean = false,
    val data: List<TvValuePoint>
)

@Serializable
data class TvPriceLinePayload(
    val price: Double,
    val title: String,
    val color: String,
    val lineWidth: Int = 1,
    val dashed: Boolean = true
)

@Serializable
data class TvMarkerPayload(
    val time: Long,
    val position: String, // "aboveBar" | "belowBar"
    val color: String,
    val shape: String, // "arrowUp" | "arrowDown" | "circle" | "square"
    val text: String
)

@Serializable
data class TvLowerPanePayload(
    val paneKey: String,
    val title: String,
    val histogram: List<TvValuePoint> = emptyList(),
    val lines: List<TvLineSeriesPayload> = emptyList(),
    val priceLines: List<TvPriceLinePayload> = emptyList()
)

@Serializable
data class TvChartPayload(
    val symbol: String,
    val timeframeLabel: String,
    val isIntraday: Boolean,
    val chartStyle: String,
    val logScale: Boolean,
    val magnetCrosshair: Boolean,
    val candles: List<TvOhlcPoint>,
    val overlayLines: List<TvLineSeriesPayload>,
    val priceLines: List<TvPriceLinePayload>,
    val markers: List<TvMarkerPayload>,
    val lowerPane: TvLowerPanePayload
)

/**
 * Pure-Kotlin payload builder transforming PaperPulse [ChartAnalysis] into strictly ascending,
 * deduplicated JSON for the official TradingView Lightweight Charts library.
 */
object TradingViewChartPayloadBuilder {

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    private const val COLOR_BULL = "#26A69A"
    private const val COLOR_BEAR = "#EF5350"
    private const val COLOR_CYAN = "#29B6F6"
    private const val COLOR_AMBER = "#F59E0B"
    private const val COLOR_VIOLET = "#A855F7"
    private const val COLOR_LIGHT = "#D1D4DC"
    private const val COLOR_PRIMARY = "#2962FF"
    private const val COLOR_MUTED = "#787B86"

    fun build(
        symbol: String,
        analysis: ChartAnalysis,
        overlays: ChartOverlays,
        lowerPane: LowerPane,
        focusSetup: EmaCrossSetup?,
        chartStyle: TvChartStyle,
        logScale: Boolean,
        magnetCrosshair: Boolean
    ): TvChartPayload {
        // TradingView Lightweight Charts strictly requires ascending, unique epoch-second timestamps.
        // Deduplicate by timestamp (keeping the latest bar for any collision) and map original index -> epochSec.
        val dedupedIndices = ArrayList<Int>(analysis.candles.size)
        val indexToTime = HashMap<Int, Long>(analysis.candles.size)
        val seenTimes = LinkedHashMap<Long, Int>(analysis.candles.size)

        analysis.candles.forEachIndexed { idx, c ->
            val sec = c.timestamp.epochSecond
            if (sec > 0L && c.open.isFinite() && c.high.isFinite() && c.low.isFinite() && c.close.isFinite()) {
                seenTimes[sec] = idx
            }
        }

        val sortedEntries = seenTimes.entries.sortedBy { it.key }
        for ((sec, idx) in sortedEntries) {
            dedupedIndices.add(idx)
            indexToTime[idx] = sec
        }

        val rawCandles = dedupedIndices.map { idx ->
            val c = analysis.candles[idx]
            val sec = indexToTime.getValue(idx)
            TvOhlcPoint(
                time = sec,
                open = c.open,
                high = c.high,
                low = c.low,
                close = c.close,
                volume = c.volume.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
            )
        }

        val chartCandles = if (chartStyle == TvChartStyle.HEIKIN_ASHI) {
            computeHeikinAshi(rawCandles)
        } else {
            rawCandles
        }

        fun extractSeries(values: List<Double?>): List<TvValuePoint> {
            val out = ArrayList<TvValuePoint>()
            for (idx in dedupedIndices) {
                val v = values.getOrNull(idx)
                val sec = indexToTime[idx]
                if (v != null && v.isFinite() && sec != null) {
                    out.add(TvValuePoint(time = sec, value = v))
                }
            }
            return out
        }

        // Overlay indicator lines
        val overlayLines = mutableListOf<TvLineSeriesPayload>()
        if (overlays.ema921) {
            overlayLines += TvLineSeriesPayload("ema9", "EMA 9", COLOR_CYAN, 2, false, extractSeries(analysis.ema9))
            overlayLines += TvLineSeriesPayload("ema21", "EMA 21", COLOR_AMBER, 2, false, extractSeries(analysis.ema21))
        }
        if (overlays.ema50200) {
            overlayLines += TvLineSeriesPayload("ema50", "EMA 50", COLOR_VIOLET, 2, false, extractSeries(analysis.ema50))
            overlayLines += TvLineSeriesPayload("ema200", "EMA 200", COLOR_LIGHT, 2, false, extractSeries(analysis.ema200))
        }
        if (overlays.vwap) {
            overlayLines += TvLineSeriesPayload("vwap", "VWAP", COLOR_PRIMARY, 2, false, extractSeries(analysis.vwap.map { it?.vwap }))
        }
        if (overlays.vwapBands) {
            overlayLines += TvLineSeriesPayload("vwap_u1", "VWAP +1σ", "#5B8DEF", 1, true, extractSeries(analysis.vwap.map { it?.upper(1.0) }))
            overlayLines += TvLineSeriesPayload("vwap_l1", "VWAP -1σ", "#5B8DEF", 1, true, extractSeries(analysis.vwap.map { it?.lower(1.0) }))
            overlayLines += TvLineSeriesPayload("vwap_u2", "VWAP +2σ", "#3B65B8", 1, true, extractSeries(analysis.vwap.map { it?.upper(2.0) }))
            overlayLines += TvLineSeriesPayload("vwap_l2", "VWAP -2σ", "#3B65B8", 1, true, extractSeries(analysis.vwap.map { it?.lower(2.0) }))
        }
        if (overlays.renkoSupertrend) {
            overlayLines += TvLineSeriesPayload(
                id = "renko_st_bull",
                title = "AI Renko ST ↑",
                color = "#00E676",
                lineWidth = 2,
                dashed = false,
                data = extractSeries(analysis.renkoSupertrend.bullLine)
            )
            overlayLines += TvLineSeriesPayload(
                id = "renko_st_bear",
                title = "AI Renko ST ↓",
                color = "#FF5252",
                lineWidth = 2,
                dashed = false,
                data = extractSeries(analysis.renkoSupertrend.bearLine)
            )
        }

        // Horizontal Price Lines (Pivots, SMC POIs, Liquidity, Setup Levels, Volume Profile POC)
        val priceLines = mutableListOf<TvPriceLinePayload>()

        if (overlays.pivots) {
            analysis.pivots?.namedLevels()?.forEach { (name, level) ->
                if (level.isFinite()) {
                    val color = when {
                        name.startsWith("R") -> COLOR_BEAR
                        name.startsWith("S") -> COLOR_BULL
                        else -> COLOR_PRIMARY
                    }
                    priceLines += TvPriceLinePayload(
                        price = level,
                        title = name,
                        color = color,
                        lineWidth = if (name == "P") 2 else 1,
                        dashed = name != "P"
                    )
                }
            }
        }

        if (overlays.volumeProfile) {
            analysis.volumeProfile?.let { vp ->
                if (vp.poc.isFinite()) {
                    priceLines += TvPriceLinePayload(vp.poc, "POC", COLOR_AMBER, 2, false)
                }
                if (vp.valueAreaHigh.isFinite()) {
                    priceLines += TvPriceLinePayload(vp.valueAreaHigh, "VAH", COLOR_AMBER, 1, true)
                }
                if (vp.valueAreaLow.isFinite()) {
                    priceLines += TvPriceLinePayload(vp.valueAreaLow, "VAL", COLOR_AMBER, 1, true)
                }
            }
        }

        if (overlays.premiumDiscount) {
            analysis.smc.dealingRange?.let { range ->
                if (range.equilibrium.isFinite()) {
                    priceLines += TvPriceLinePayload(range.equilibrium, "EQ 50%", COLOR_MUTED, 1, true)
                }
            }
        }

        if (overlays.smcZones) {
            analysis.smc.pointsOfInterest
                .take(4)
                .forEach { z ->
                    val mid = (z.top + z.bottom) / 2.0
                    if (mid.isFinite()) {
                        val color = when (z.kind) {
                            ZoneKind.BULLISH_FVG, ZoneKind.BEARISH_FVG -> COLOR_VIOLET
                            else -> if (z.isBullish) COLOR_BULL else COLOR_BEAR
                        }
                        val label = when (z.kind) {
                            ZoneKind.DEMAND_OB -> "Demand OB"
                            ZoneKind.SUPPLY_OB -> "Supply OB"
                            ZoneKind.DEMAND_BASE -> "Demand"
                            ZoneKind.SUPPLY_BASE -> "Supply"
                            ZoneKind.BULLISH_FVG -> "FVG+"
                            ZoneKind.BEARISH_FVG -> "FVG-"
                        } + if (z.institutional) " ★" else ""
                        priceLines += TvPriceLinePayload(mid, label, color, 1, true)
                    }
                }
        }

        if (overlays.liquidity) {
            analysis.smc.liquidity
                .filter { it.status == LiquidityStatus.PENDING && it.price.isFinite() }
                .take(4)
                .forEach { lv ->
                    val side = if (lv.side == LiquiditySide.BUY_SIDE) "BSL" else "SSL"
                    val col = if (lv.side == LiquiditySide.BUY_SIDE) COLOR_BEAR else COLOR_BULL
                    priceLines += TvPriceLinePayload(lv.price, "$side ${lv.name}", col, 1, true)
                }
        }

        if (overlays.setupLevels && focusSetup != null &&
            focusSetup.state != SetupState.INVALIDATED && focusSetup.state != SetupState.EXPIRED
        ) {
            if (focusSetup.triggerLevel.isFinite()) {
                priceLines += TvPriceLinePayload(focusSetup.triggerLevel, "Trigger", COLOR_AMBER, 1, true)
            }
            focusSetup.entry?.takeIf { it.isFinite() }?.let {
                priceLines += TvPriceLinePayload(it, "Entry", COLOR_PRIMARY, 2, false)
            }
            focusSetup.stopLoss?.takeIf { it.isFinite() }?.let {
                priceLines += TvPriceLinePayload(it, "SL", COLOR_BEAR, 2, false)
            }
            focusSetup.targets.take(2).forEach { target ->
                if (target.price.isFinite()) {
                    priceLines += TvPriceLinePayload(target.price, target.label, COLOR_BULL, 1, true)
                }
            }
        }

        if (overlays.firstRedRange) {
            analysis.redBar.firstRedRange?.let { fr ->
                if (fr.high.isFinite()) priceLines += TvPriceLinePayload(fr.high, "1st Red H", COLOR_BEAR, 2, true)
                if (fr.midpoint.isFinite()) priceLines += TvPriceLinePayload(fr.midpoint, "1st Red 50%", COLOR_MUTED, 1, true)
                if (fr.low.isFinite()) priceLines += TvPriceLinePayload(fr.low, "1st Red L", COLOR_BEAR, 2, true)
            }
        }
        if (overlays.redBar) {
            analysis.redBar.midday1245Range?.let { md ->
                if (md.high.isFinite()) priceLines += TvPriceLinePayload(md.high, "12:45 H", COLOR_AMBER, 1, true)
                if (md.low.isFinite()) priceLines += TvPriceLinePayload(md.low, "12:45 L", COLOR_AMBER, 1, true)
            }
            analysis.redBar.reversalZones.takeLast(2).forEach { rz ->
                if (rz.top.isFinite()) priceLines += TvPriceLinePayload(rz.top, rz.label, "#FF5252", 1, true)
            }
        }

        // Series Markers (BOS/CHoCH, Divergences, Setup Crossover) — must be sorted ascending by time
        val markers = mutableListOf<TvMarkerPayload>()
        if (overlays.structure) {
            analysis.smc.structureBreaks.takeLast(10).forEach { br ->
                val sec = indexToTime[br.index] ?: return@forEach
                markers += TvMarkerPayload(
                    time = sec,
                    position = if (br.bullish) "aboveBar" else "belowBar",
                    color = if (br.bullish) COLOR_BULL else COLOR_BEAR,
                    shape = if (br.bullish) "arrowUp" else "arrowDown",
                    text = if (br.isChoch) "CHoCH" else "BOS"
                )
            }
        }
        if (overlays.divergences) {
            analysis.divergences.takeLast(8).forEach { d ->
                val sec = indexToTime[d.toIndex] ?: return@forEach
                val tag = when (d.kind) {
                    DivergenceKind.RSI_REGULAR_BULLISH, DivergenceKind.RSI_REGULAR_BEARISH -> "RSI Div"
                    DivergenceKind.RSI_HIDDEN_BULLISH, DivergenceKind.RSI_HIDDEN_BEARISH -> "RSI Hid"
                    DivergenceKind.VOLUME_BULLISH, DivergenceKind.VOLUME_BEARISH -> "Vol Div"
                    DivergenceKind.DELTA_BULLISH, DivergenceKind.DELTA_BEARISH -> "Δ Div"
                }
                markers += TvMarkerPayload(
                    time = sec,
                    position = if (d.kind.bullish) "belowBar" else "aboveBar",
                    color = if (d.kind.bullish) COLOR_BULL else COLOR_BEAR,
                    shape = "circle",
                    text = tag
                )
            }
        }
        if (overlays.setupLevels && focusSetup != null &&
            focusSetup.state != SetupState.INVALIDATED && focusSetup.state != SetupState.EXPIRED
        ) {
            indexToTime[focusSetup.crossIndex]?.let { sec ->
                val isLong = focusSetup.direction == SignalDirection.LONG
                markers += TvMarkerPayload(
                    time = sec,
                    position = if (isLong) "belowBar" else "aboveBar",
                    color = COLOR_AMBER,
                    shape = if (isLong) "arrowUp" else "arrowDown",
                    text = if (isLong) "EMA Cross ↑" else "EMA Cross ↓"
                )
            }
        }
        if (overlays.renkoSupertrend) {
            analysis.renkoSupertrend.signals.takeLast(10).forEach { sig ->
                val sec = indexToTime[sig.index] ?: return@forEach
                markers += TvMarkerPayload(
                    time = sec,
                    position = if (sig.isBullish) "belowBar" else "aboveBar",
                    color = if (sig.isBullish) "#00E676" else "#FF5252",
                    shape = if (sig.isBullish) "arrowUp" else "arrowDown",
                    text = if (sig.isBullish) "Renko BUY" else "Renko SELL"
                )
            }
        }
        if (overlays.firstRedRange) {
            analysis.redBar.breakoutEvents
                .filter {
                    it.kind == com.example.domain.analysis.RedBarEventKind.FIRST_RED_BREAKOUT ||
                        it.kind == com.example.domain.analysis.RedBarEventKind.FIRST_RED_BREAKDOWN
                }
                .forEach { ev ->
                    val sec = indexToTime[ev.index] ?: return@forEach
                    markers += TvMarkerPayload(
                        time = sec,
                        position = if (ev.kind.bullish) "belowBar" else "aboveBar",
                        color = if (ev.kind.bullish) COLOR_BULL else COLOR_BEAR,
                        shape = if (ev.kind.bullish) "arrowUp" else "arrowDown",
                        text = ev.kind.shortLabel
                    )
                }
        }
        if (overlays.redBar) {
            analysis.redBar.breakoutEvents
                .filter {
                    it.kind == com.example.domain.analysis.RedBarEventKind.MIDDAY_1245_BREAKOUT ||
                        it.kind == com.example.domain.analysis.RedBarEventKind.MIDDAY_1245_BREAKDOWN
                }
                .forEach { ev ->
                    val sec = indexToTime[ev.index] ?: return@forEach
                    markers += TvMarkerPayload(
                        time = sec,
                        position = if (ev.kind.bullish) "belowBar" else "aboveBar",
                        color = if (ev.kind.bullish) COLOR_BULL else COLOR_BEAR,
                        shape = if (ev.kind.bullish) "arrowUp" else "arrowDown",
                        text = ev.kind.shortLabel
                    )
                }
            analysis.redBar.reversalZones.takeLast(3).forEach { rz ->
                val sec = indexToTime[rz.originIndex] ?: return@forEach
                markers += TvMarkerPayload(
                    time = sec,
                    position = "aboveBar",
                    color = "#FF1744",
                    shape = "arrowDown",
                    text = "Red Rev"
                )
            }
            analysis.redBar.insideBars.takeLast(6).forEach { ib ->
                val sec = indexToTime[ib.index] ?: return@forEach
                markers += TvMarkerPayload(
                    time = sec,
                    position = "aboveBar",
                    color = if (ib.isRed) COLOR_BEAR else COLOR_BULL,
                    shape = "square",
                    text = "IB"
                )
            }
        }
        val sortedMarkers = markers.sortedBy { it.time }

        // Lower Oscillator / Volume Pane
        val lowerPanePayload = buildLowerPanePayload(
            analysis = analysis,
            lowerPane = lowerPane,
            dedupedIndices = dedupedIndices,
            indexToTime = indexToTime,
            extractSeries = ::extractSeries
        )

        return TvChartPayload(
            symbol = symbol,
            timeframeLabel = analysis.timeframe.label,
            isIntraday = analysis.timeframe.isIntraday,
            chartStyle = chartStyle.jsKey,
            logScale = logScale,
            magnetCrosshair = magnetCrosshair,
            candles = chartCandles,
            overlayLines = overlayLines.filter { it.data.isNotEmpty() },
            priceLines = priceLines,
            markers = sortedMarkers,
            lowerPane = lowerPanePayload
        )
    }

    fun toJson(payload: TvChartPayload): String = json.encodeToString(payload)

    internal fun computeHeikinAshi(candles: List<TvOhlcPoint>): List<TvOhlcPoint> {
        if (candles.isEmpty()) return emptyList()
        val result = ArrayList<TvOhlcPoint>(candles.size)
        var prevHaOpen = (candles[0].open + candles[0].close) / 2.0
        var prevHaClose = (candles[0].open + candles[0].high + candles[0].low + candles[0].close) / 4.0

        candles.forEachIndexed { idx, c ->
            val haClose = (c.open + c.high + c.low + c.close) / 4.0
            val haOpen = if (idx == 0) (c.open + c.close) / 2.0 else (prevHaOpen + prevHaClose) / 2.0
            val haHigh = max(c.high, max(haOpen, haClose))
            val haLow = min(c.low, min(haOpen, haClose))
            result.add(
                TvOhlcPoint(
                    time = c.time,
                    open = haOpen,
                    high = haHigh,
                    low = haLow,
                    close = haClose,
                    volume = c.volume
                )
            )
            prevHaOpen = haOpen
            prevHaClose = haClose
        }
        return result
    }

    private fun buildLowerPanePayload(
        analysis: ChartAnalysis,
        lowerPane: LowerPane,
        dedupedIndices: List<Int>,
        indexToTime: Map<Int, Long>,
        extractSeries: (List<Double?>) -> List<TvValuePoint>
    ): TvLowerPanePayload {
        return when (lowerPane) {
            LowerPane.VOLUME -> {
                val bars = dedupedIndices.mapNotNull { idx ->
                    val c = analysis.candles[idx]
                    val sec = indexToTime[idx] ?: return@mapNotNull null
                    val ma = analysis.volumeMa20.getOrNull(idx)
                    val above = ma != null && c.volume > ma
                    val color = if (c.close >= c.open) {
                        if (above) "rgba(38,166,154,0.88)" else "rgba(38,166,154,0.38)"
                    } else {
                        if (above) "rgba(239,83,80,0.88)" else "rgba(239,83,80,0.38)"
                    }
                    TvValuePoint(sec, c.volume.takeIf { it.isFinite() } ?: 0.0, color)
                }
                val maLine = TvLineSeriesPayload("vol_ma20", "Vol MA20", COLOR_AMBER, 1, false, extractSeries(analysis.volumeMa20))
                TvLowerPanePayload(
                    paneKey = lowerPane.name,
                    title = "Volume • MA20",
                    histogram = bars,
                    lines = listOf(maLine).filter { it.data.isNotEmpty() }
                )
            }
            LowerPane.RSI -> {
                val rsiLine = TvLineSeriesPayload("rsi14", "RSI 14", COLOR_VIOLET, 2, false, extractSeries(analysis.rsi14))
                TvLowerPanePayload(
                    paneKey = lowerPane.name,
                    title = "RSI (14)",
                    lines = listOf(rsiLine).filter { it.data.isNotEmpty() },
                    priceLines = listOf(
                        TvPriceLinePayload(70.0, "70", COLOR_BEAR, 1, true),
                        TvPriceLinePayload(50.0, "50", COLOR_MUTED, 1, true),
                        TvPriceLinePayload(30.0, "30", COLOR_BULL, 1, true)
                    )
                )
            }
            LowerPane.OBV -> {
                val obvLine = TvLineSeriesPayload("obv", "OBV", COLOR_CYAN, 2, false, extractSeries(analysis.obv.map { it }))
                TvLowerPanePayload(
                    paneKey = lowerPane.name,
                    title = "On-Balance Volume",
                    lines = listOf(obvLine).filter { it.data.isNotEmpty() }
                )
            }
            LowerPane.ELDER -> {
                val bars = dedupedIndices.mapNotNull { idx ->
                    val v = analysis.elderForce13.getOrNull(idx) ?: return@mapNotNull null
                    val sec = indexToTime[idx] ?: return@mapNotNull null
                    if (!v.isFinite()) return@mapNotNull null
                    TvValuePoint(sec, v, if (v >= 0.0) "rgba(38,166,154,0.8)" else "rgba(239,83,80,0.8)")
                }
                TvLowerPanePayload(
                    paneKey = lowerPane.name,
                    title = "Elder Force Index (13)",
                    histogram = bars,
                    priceLines = listOf(TvPriceLinePayload(0.0, "0", COLOR_MUTED, 1, true))
                )
            }
            LowerPane.DELTA -> {
                val bars = dedupedIndices.mapNotNull { idx ->
                    val v = analysis.deltas.getOrNull(idx) ?: return@mapNotNull null
                    val sec = indexToTime[idx] ?: return@mapNotNull null
                    if (!v.isFinite()) return@mapNotNull null
                    TvValuePoint(sec, v, if (v >= 0.0) "rgba(38,166,154,0.75)" else "rgba(239,83,80,0.75)")
                }
                val cvdLine = TvLineSeriesPayload("cvd", "CVD", COLOR_AMBER, 2, false, extractSeries(analysis.cumulativeDelta.map { it }))
                TvLowerPanePayload(
                    paneKey = lowerPane.name,
                    title = if (analysis.orderFlow.usesTrueDelta) "Delta (Aggressor) • CVD" else "Delta • CVD",
                    histogram = bars,
                    lines = listOf(cvdLine).filter { it.data.isNotEmpty() }
                )
            }
            LowerPane.ATR -> {
                val a7 = TvLineSeriesPayload("atr7", "ATR 7", COLOR_CYAN, 2, false, extractSeries(analysis.atr7))
                val a14 = TvLineSeriesPayload("atr14", "ATR 14", COLOR_AMBER, 2, false, extractSeries(analysis.atr14))
                TvLowerPanePayload(
                    paneKey = lowerPane.name,
                    title = "ATR 7 / 14",
                    lines = listOf(a7, a14).filter { it.data.isNotEmpty() }
                )
            }
        }
    }
}
