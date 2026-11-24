package com.example.ui.screens.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.analysis.ChartAnalysis
import com.example.domain.analysis.DivergenceKind
import com.example.domain.analysis.EmaCrossSetup
import com.example.domain.analysis.LiquiditySide
import com.example.domain.analysis.LiquidityStatus
import com.example.domain.analysis.SetupState
import com.example.domain.analysis.ZoneKind
import com.example.domain.engine.MarketClock
import com.example.domain.signal.SignalDirection
import java.time.Duration
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.text.font.FontWeight

/** Colours used by the chart canvases (resolved from the theme by the caller). */
data class ChartPalette(
    val bull: Color,
    val bear: Color,
    val amber: Color,
    val violet: Color,
    val cyan: Color,
    val grid: Color,
    val text: Color,
    val muted: Color,
    val primary: Color,
    val canvasBg: Color = Color(0xFF131722),
    val axisDivider: Color = Color(0xFF2A2E39)
)

/** Visible window shared by the price and the lower pane so they stay aligned. */
data class ChartWindow(val start: Int, val end: Int) {
    val count: Int get() = (end - start).coerceAtLeast(1)

    companion object {
        fun of(total: Int, visibleBars: Int, panBars: Int): ChartWindow {
            if (total <= 0) return ChartWindow(0, 0)
            val end = (total - panBars).coerceIn(1, total)
            val start = (end - visibleBars).coerceAtLeast(0)
            return ChartWindow(start, end)
        }
    }
}

private const val AXIS_DP = 58

private fun fmtPrice(v: Double): String = when {
    abs(v) >= 1000 -> String.format(Locale.US, "%.1f", v)
    abs(v) >= 10 -> String.format(Locale.US, "%.2f", v)
    else -> String.format(Locale.US, "%.4f", v)
}

private fun fmtCompactVol(v: Double): String = when {
    abs(v) >= 1e6 -> String.format(Locale.US, "%.2fM", v / 1e6)
    abs(v) >= 1e3 -> String.format(Locale.US, "%.1fK", v / 1e3)
    else -> String.format(Locale.US, "%.0f", v)
}

private fun DrawScope.safeText(
    tm: TextMeasurer,
    text: String,
    x: Float,
    y: Float,
    color: Color,
    sizeSp: Int = 9,
    bold: Boolean = false
) {
    // drawText throws if the remaining layout space is negative, so clamp inside the canvas.
    val maxX = (size.width - 28f).coerceAtLeast(0f)
    val maxY = (size.height - 14f).coerceAtLeast(0f)
    val cx = x.coerceIn(0f, maxX)
    val cy = y.coerceIn(0f, maxY)
    drawText(
        textMeasurer = tm,
        text = text,
        topLeft = Offset(cx, cy),
        style = TextStyle(
            color = color,
            fontSize = sizeSp.sp,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal
        ),
        maxLines = 1
    )
}

private fun DrawScope.drawAxisPricePill(
    tm: TextMeasurer,
    chartW: Float,
    axisW: Float,
    yPos: Float,
    label: String,
    bgColor: Color,
    textColor: Color = Color.White
) {
    val pillH = 16f
    val top = (yPos - pillH / 2f).coerceIn(1f, (size.height - pillH - 1f).coerceAtLeast(1f))
    drawRoundRect(
        color = bgColor,
        topLeft = Offset(chartW + 1f, top),
        size = Size((axisW - 2f).coerceAtLeast(24f), pillH),
        cornerRadius = CornerRadius(3f, 3f)
    )
    safeText(tm, label, chartW + 4f, top + 2f, textColor, sizeSp = 8, bold = true)
}

private fun DrawScope.seriesPath(
    values: List<Double?>,
    window: ChartWindow,
    x: (Int) -> Float,
    y: (Double) -> Float
): Path {
    val path = Path()
    var penDown = false
    for (i in window.start until window.end) {
        val v = values.getOrNull(i)
        if (v == null || v.isNaN()) {
            penDown = false
            continue
        }
        if (!penDown) {
            path.moveTo(x(i), y(v))
            penDown = true
        } else {
            path.lineTo(x(i), y(v))
        }
    }
    return path
}

private fun DrawScope.hLine(
    yPos: Float,
    fromX: Float,
    toX: Float,
    color: Color,
    width: Float = 1.2f,
    dashed: Boolean = false
) {
    if (yPos < 0f || yPos > size.height) return
    drawLine(
        color = color,
        start = Offset(fromX, yPos),
        end = Offset(toX, yPos),
        strokeWidth = width,
        pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(10f, 8f)) else null
    )
}

@Composable
fun PriceChartCanvas(
    analysis: ChartAnalysis,
    overlays: ChartOverlays,
    window: ChartWindow,
    focusSetup: EmaCrossSetup?,
    palette: ChartPalette,
    modifier: Modifier = Modifier,
    measureMode: Boolean = false,
    measureStartIndex: Int? = null,
    measureEndIndex: Int? = null,
    onMeasureTap: (Int) -> Unit = {},
    onPinchZoom: (Float) -> Unit = {},
    onPanBars: (Int) -> Unit = {}
) {
    val tm = rememberTextMeasurer()
    var crosshairIndex by remember(analysis.candles.size, measureMode) { mutableStateOf<Int?>(null) }

    val interactiveModifier = modifier
        .pointerInput(measureMode, window.start, window.end) {
            detectTapGestures { offset ->
                val axisW = AXIS_DP.dp.toPx()
                val chartW = (size.width - axisW).coerceAtLeast(1f)
                val barW = chartW / window.count
                val idx = (window.start + (offset.x / barW).toInt())
                    .coerceIn(window.start, (window.end - 1).coerceAtLeast(window.start))
                if (measureMode) {
                    onMeasureTap(idx)
                } else {
                    crosshairIndex = if (crosshairIndex == idx) null else idx
                }
            }
        }
        .pointerInput(measureMode, window.count) {
            if (!measureMode) {
                detectTransformGestures { _, pan, zoom, _ ->
                    if (abs(zoom - 1f) > 0.03f) {
                        crosshairIndex = null
                        onPinchZoom(zoom)
                    }
                    val axisW = AXIS_DP.dp.toPx()
                    val chartW = (size.width - axisW).coerceAtLeast(1f)
                    val barW = (chartW / window.count).coerceAtLeast(1f)
                    val barsShift = (pan.x / barW).roundToInt()
                    if (barsShift != 0) {
                        crosshairIndex = null
                        onPanBars(barsShift)
                    }
                }
            }
        }

    Canvas(modifier = interactiveModifier) {
        val candles = analysis.candles
        if (candles.isEmpty() || window.end <= window.start) return@Canvas
        val axisW = AXIS_DP.dp.toPx()
        val timeAxisH = 20.dp.toPx()
        val topHudH = 20.dp.toPx()
        val chartW = size.width - axisW
        val plotBottom = (size.height - timeAxisH).coerceAtLeast(topHudH + 40f)
        val plotH = (plotBottom - topHudH).coerceAtLeast(30f)
        val barW = chartW / window.count

        // TradingView dark chart background & crisp axis borders
        drawRect(palette.canvasBg, Offset.Zero, size)
        drawLine(palette.axisDivider, Offset(chartW, 0f), Offset(chartW, size.height), strokeWidth = 1f)
        drawLine(palette.axisDivider, Offset(0f, plotBottom), Offset(size.width, plotBottom), strokeWidth = 1f)

        var hi = Double.NEGATIVE_INFINITY
        var lo = Double.POSITIVE_INFINITY
        var maxVol = 0.0
        for (i in window.start until window.end) {
            hi = max(hi, candles[i].high)
            lo = min(lo, candles[i].low)
            maxVol = max(maxVol, candles[i].volume)
        }
        if (!hi.isFinite() || !lo.isFinite()) return@Canvas
        val pad = (hi - lo).coerceAtLeast(hi * 0.001) * 0.09
        hi += pad
        lo -= pad
        val span = (hi - lo).coerceAtLeast(1e-9)
        val xOf: (Int) -> Float = { i -> ((i - window.start) + 0.5f) * barW }
        val yOf: (Double) -> Float = { p -> topHudH + ((hi - p) / span * plotH).toFloat() }

        // Subtle TradingView horizontal grid + right price axis labels
        for (k in 0..4) {
            val p = lo + span * k / 4.0
            val yy = yOf(p)
            hLine(yy, 0f, chartW, palette.grid.copy(alpha = 0.45f), 0.7f)
            safeText(tm, fmtPrice(p), chartW + 5f, yy - 6f, palette.muted, sizeSp = 8)
        }

        // TradingView-style IST Time Axis (bottom strip + subtle vertical grid)
        val timeTicks = 5
        val isIntraday = analysis.timeframe.isIntraday
        for (t in 0 until timeTicks) {
            val frac = (t + 0.5f) / timeTicks
            val idx = (window.start + (frac * window.count).toInt()).coerceIn(window.start, window.end - 1)
            val tx = xOf(idx)
            drawLine(palette.grid.copy(alpha = 0.35f), Offset(tx, topHudH), Offset(tx, plotBottom), strokeWidth = 0.7f)
            val tsMillis = candles[idx].timestamp.toEpochMilli()
            val label = if (isIntraday) {
                MarketClock.formatIstShortTime(tsMillis)
            } else {
                MarketClock.formatIstDate(tsMillis).take(6)
            }
            safeText(tm, label, (tx - 16f).coerceIn(2f, chartW - 36f), plotBottom + 4f, palette.muted, 8)
        }

        // Translucent TradingView bottom volume bars (bottom 16% of plot area)
        if (maxVol > 0.0) {
            val volAreaH = plotH * 0.16f
            val volBarW = (barW * 0.68f).coerceAtLeast(1f)
            for (i in window.start until window.end) {
                val c = candles[i]
                val vh = ((c.volume / maxVol) * volAreaH).toFloat().coerceAtLeast(1f)
                val vCol = (if (c.close >= c.open) palette.bull else palette.bear).copy(alpha = 0.22f)
                drawRect(vCol, Offset(xOf(i) - volBarW / 2f, plotBottom - vh), Size(volBarW, vh))
            }
        }

        // Premium / discount shading of the dealing range
        val range = analysis.smc.dealingRange
        if (overlays.premiumDiscount && range != null) {
            val eqY = yOf(range.equilibrium)
            val topY = yOf(range.high).coerceAtLeast(0f)
            val botY = yOf(range.low).coerceAtMost(size.height)
            drawRect(palette.bear.copy(alpha = 0.06f), Offset(0f, topY), Size(chartW, (eqY - topY).coerceAtLeast(0f)))
            drawRect(palette.bull.copy(alpha = 0.06f), Offset(0f, eqY), Size(chartW, (botY - eqY).coerceAtLeast(0f)))
            hLine(eqY, 0f, chartW, palette.muted, 1f, dashed = true)
            safeText(tm, "EQ", 4f, eqY - 12f, palette.muted)
            safeText(tm, "Premium", 4f, topY + 2f, palette.bear)
            safeText(tm, "Discount", 4f, botY - 14f, palette.bull)
        }

        // Pivots + CPR
        val pv = analysis.pivots
        if (overlays.pivots && pv != null) {
            val tcY = yOf(pv.cprTop)
            val bcY = yOf(pv.cprBottom)
            drawRect(palette.primary.copy(alpha = 0.08f), Offset(0f, min(tcY, bcY)), Size(chartW, abs(bcY - tcY)))
            pv.namedLevels().forEach { (name, level) ->
                val c = when {
                    name.startsWith("R") -> palette.bear
                    name.startsWith("S") -> palette.bull
                    else -> palette.primary
                }
                val yy = yOf(level)
                hLine(yy, 0f, chartW, c.copy(alpha = 0.7f), if (name == "P") 1.6f else 1f, dashed = name != "P")
                safeText(tm, name, chartW - 22f, yy - 12f, c)
            }
        }

        // Volume profile (right-anchored histogram)
        val vp = analysis.volumeProfile
        if (overlays.volumeProfile && vp != null && vp.maxBinVolume > 0) {
            val maxLen = chartW * 0.22f
            vp.bins.forEach { bin ->
                val top = yOf(bin.priceHigh)
                val bottom = yOf(bin.priceLow)
                val len = (bin.volume / vp.maxBinVolume * maxLen).toFloat()
                val buyLen = if (bin.volume > 0) (bin.buyVolume / bin.volume).toFloat() * len else 0f
                val isPoc = vp.poc in bin.priceLow..bin.priceHigh
                val h = (bottom - top - 1f).coerceAtLeast(1f)
                drawRect(palette.bull.copy(alpha = if (isPoc) 0.45f else 0.22f), Offset(chartW - len, top), Size(buyLen, h))
                drawRect(palette.bear.copy(alpha = if (isPoc) 0.45f else 0.22f), Offset(chartW - len + buyLen, top), Size(len - buyLen, h))
            }
            hLine(yOf(vp.poc), 0f, chartW, palette.amber, 1.4f)
            safeText(tm, "POC", 4f, yOf(vp.poc) - 12f, palette.amber)
            hLine(yOf(vp.valueAreaHigh), 0f, chartW, palette.amber.copy(alpha = 0.5f), 1f, dashed = true)
            hLine(yOf(vp.valueAreaLow), 0f, chartW, palette.amber.copy(alpha = 0.5f), 1f, dashed = true)
        }

        // SMC zones: order blocks, supply/demand bases, FVGs
        if (overlays.smcZones) {
            analysis.smc.zones
                .filter { it.isActive && it.originIndex < window.end }
                .takeLast(16)
                .forEach { z ->
                    val left = xOf(max(z.originIndex, window.start)) - barW / 2f
                    val top = yOf(z.top)
                    val bottom = yOf(z.bottom)
                    val base = when (z.kind) {
                        ZoneKind.BULLISH_FVG, ZoneKind.BEARISH_FVG -> palette.violet
                        else -> if (z.isBullish) palette.bull else palette.bear
                    }
                    val alpha = if (z.mitigated) 0.07f else 0.15f
                    drawRect(base.copy(alpha = alpha), Offset(left, top), Size((chartW - left).coerceAtLeast(0f), (bottom - top).coerceAtLeast(1f)))
                    if (z.institutional) {
                        drawRect(palette.amber.copy(alpha = 0.8f), Offset(left, top), Size((chartW - left).coerceAtLeast(0f), (bottom - top).coerceAtLeast(1f)), style = Stroke(1.2f))
                    }
                    val kindTag = when (z.kind) {
                        ZoneKind.DEMAND_OB -> "Demand OB"
                        ZoneKind.SUPPLY_OB -> "Supply OB"
                        ZoneKind.DEMAND_BASE -> "Demand"
                        ZoneKind.SUPPLY_BASE -> "Supply"
                        ZoneKind.BULLISH_FVG -> "FVG+"
                        ZoneKind.BEARISH_FVG -> "FVG-"
                    }
                    val tag = if (z.institutional) "$kindTag ★" else kindTag
                    safeText(tm, tag, left + 2f, top, base, 8)
                }
        }

        // Higher-timeframe shadow candles behind the chart candles
        if (overlays.htfShadow && analysis.htfCandles.isNotEmpty()) {
            analysis.htfCandles
                .filter { it.lastBaseIndex >= window.start && it.firstBaseIndex < window.end }
                .forEach { h ->
                    val c = h.candle
                    val left = xOf(max(h.firstBaseIndex, window.start)) - barW / 2f
                    val right = xOf(min(h.lastBaseIndex, window.end - 1)) + barW / 2f
                    val bull = c.close >= c.open
                    val col = if (bull) palette.bull else palette.bear
                    val bodyTop = yOf(max(c.open, c.close))
                    val bodyBottom = yOf(min(c.open, c.close))
                    val mid = (left + right) / 2f
                    drawLine(col.copy(alpha = 0.35f), Offset(mid, yOf(c.high)), Offset(mid, yOf(c.low)), strokeWidth = 2f)
                    drawRect(col.copy(alpha = 0.10f), Offset(left, bodyTop), Size(right - left, (bodyBottom - bodyTop).coerceAtLeast(1f)))
                    drawRect(
                        col.copy(alpha = if (h.isComplete) 0.45f else 0.25f),
                        Offset(left, bodyTop),
                        Size(right - left, (bodyBottom - bodyTop).coerceAtLeast(1f)),
                        style = Stroke(1f, pathEffect = if (h.isComplete) null else PathEffect.dashPathEffect(floatArrayOf(6f, 5f)))
                    )
                }
        }

        // Candles
        val bodyW = (barW * 0.7f).coerceAtLeast(1f)
        for (i in window.start until window.end) {
            val c = candles[i]
            val bull = c.close >= c.open
            val col = if (bull) palette.bull else palette.bear
            val cx = xOf(i)
            drawLine(col, Offset(cx, yOf(c.high)), Offset(cx, yOf(c.low)), strokeWidth = 1f)
            val top = yOf(max(c.open, c.close))
            val bottom = yOf(min(c.open, c.close))
            drawRect(col, Offset(cx - bodyW / 2f, top), Size(bodyW, (bottom - top).coerceAtLeast(1f)))
        }

        // EMAs
        if (overlays.ema921) {
            drawPath(seriesPath(analysis.ema9, window, xOf, yOf), palette.cyan, style = Stroke(1.6f))
            drawPath(seriesPath(analysis.ema21, window, xOf, yOf), palette.amber, style = Stroke(1.6f))
        }
        if (overlays.ema50200) {
            drawPath(seriesPath(analysis.ema50, window, xOf, yOf), palette.violet, style = Stroke(1.8f))
            drawPath(seriesPath(analysis.ema200, window, xOf, yOf), palette.text.copy(alpha = 0.8f), style = Stroke(2f))
        }

        // VWAP + σ bands
        if (overlays.vwap) {
            drawPath(seriesPath(analysis.vwap.map { it?.vwap }, window, xOf, yOf), palette.primary, style = Stroke(1.8f))
        }
        if (overlays.vwapBands) {
            listOf(1.0 to 0.55f, 2.0 to 0.4f, 3.0 to 0.25f).forEach { (m, a) ->
                val dash = Stroke(1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
                drawPath(seriesPath(analysis.vwap.map { it?.upper(m) }, window, xOf, yOf), palette.primary.copy(alpha = a), style = dash)
                drawPath(seriesPath(analysis.vwap.map { it?.lower(m) }, window, xOf, yOf), palette.primary.copy(alpha = a), style = dash)
            }
        }

        // Liquidity pools
        if (overlays.liquidity) {
            analysis.smc.liquidity.forEach { lv ->
                val from = lv.sourceIndices.maxOrNull() ?: return@forEach
                if (from >= window.end) return@forEach
                val swept = lv.status != LiquidityStatus.PENDING
                val col = (if (lv.side == LiquiditySide.BUY_SIDE) palette.bear else palette.bull)
                    .copy(alpha = if (swept) 0.35f else 0.85f)
                val yy = yOf(lv.price)
                hLine(yy, xOf(max(from, window.start)), chartW, col, 1.1f, dashed = true)
                val statusTag = when (lv.status) {
                    LiquidityStatus.PENDING -> "pending"
                    LiquidityStatus.SWEPT -> "swept"
                    LiquidityStatus.BROKEN -> "broken"
                }
                val sideTag = if (lv.side == LiquiditySide.BUY_SIDE) "BSL" else "SSL"
                val tag = "$sideTag ${lv.name} • $statusTag"
                safeText(tm, tag, xOf(max(from, window.start)) + 2f, yy - 12f, col, 8)
            }
        }

        // Market structure: BOS / CHoCH
        if (overlays.structure) {
            analysis.smc.structureBreaks
                .filter { it.index in window.start until window.end }
                .takeLast(8)
                .forEach { br ->
                    val col = if (br.bullish) palette.bull else palette.bear
                    val yy = yOf(br.level)
                    hLine(yy, xOf(max(br.swingIndex, window.start)), xOf(br.index), col, 1.2f, dashed = !br.isChoch)
                    safeText(tm, if (br.isChoch) "CHoCH" else "BOS", xOf(br.index) - 30f, if (br.bullish) yy - 13f else yy + 1f, col, 8)
                }
        }

        // Divergence lines (price pane)
        if (overlays.divergences) {
            analysis.divergences
                .filter { it.fromIndex >= window.start && it.toIndex < window.end }
                .forEach { d ->
                    val col = if (d.kind.bullish) palette.bull else palette.bear
                    val effect = when (d.kind) {
                        DivergenceKind.VOLUME_BULLISH, DivergenceKind.VOLUME_BEARISH -> PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
                        DivergenceKind.DELTA_BULLISH, DivergenceKind.DELTA_BEARISH -> PathEffect.dashPathEffect(floatArrayOf(2f, 5f))
                        else -> null
                    }
                    drawLine(col, Offset(xOf(d.fromIndex), yOf(d.fromPrice)), Offset(xOf(d.toIndex), yOf(d.toPrice)), strokeWidth = 2f, pathEffect = effect)
                    val tag = when (d.kind) {
                        DivergenceKind.RSI_REGULAR_BULLISH, DivergenceKind.RSI_REGULAR_BEARISH -> "RSI div"
                        DivergenceKind.RSI_HIDDEN_BULLISH, DivergenceKind.RSI_HIDDEN_BEARISH -> "RSI hid"
                        DivergenceKind.VOLUME_BULLISH, DivergenceKind.VOLUME_BEARISH -> "Vol div"
                        DivergenceKind.DELTA_BULLISH, DivergenceKind.DELTA_BEARISH -> "Δ div"
                    }
                    safeText(tm, tag, xOf(d.toIndex) + 2f, yOf(d.toPrice) + (if (d.kind.bullish) 2f else -13f), col, 8)
                }
        }

        // Focused EMA crossover-retest setup levels
        val s = focusSetup
        if (overlays.setupLevels && s != null && s.state != SetupState.INVALIDATED && s.state != SetupState.EXPIRED) {
            val isLong = s.direction == SignalDirection.LONG
            if (s.crossIndex in window.start until window.end) {
                val markY = yOf(if (isLong) s.crossHigh else s.crossLow)
                drawCircle(palette.amber, radius = 4f, center = Offset(xOf(s.crossIndex), markY))
                safeText(tm, "X", xOf(s.crossIndex) - 3f, markY + (if (isLong) -14f else 3f), palette.amber, 8)
            }
            val fromX = xOf(max(s.crossIndex, window.start))
            hLine(yOf(s.triggerLevel), fromX, chartW, palette.amber, 1.4f, dashed = true)
            safeText(tm, "Trigger ${fmtPrice(s.triggerLevel)}", fromX + 2f, yOf(s.triggerLevel) - 12f, palette.amber, 8)
            s.entry?.let { e ->
                val ey = yOf(e)
                hLine(ey, fromX, chartW, palette.primary, 1.3f)
                safeText(tm, "Entry", chartW - 34f, ey - 12f, palette.primary, 8)
                drawAxisPricePill(tm, chartW, axisW, ey, fmtPrice(e), palette.primary)
            }
            s.stopLoss?.let { sl ->
                val sly = yOf(sl)
                hLine(sly, fromX, chartW, palette.bear, 1.4f)
                safeText(tm, "SL", chartW - 20f, sly + 1f, palette.bear, 8)
                drawAxisPricePill(tm, chartW, axisW, sly, fmtPrice(sl), palette.bear)
            }
            s.targets.forEachIndexed { idx, t ->
                val yy = yOf(t.price)
                if (yy in topHudH..plotBottom) {
                    hLine(yy, fromX, chartW, palette.bull.copy(alpha = 0.65f), 1.0f, dashed = true)
                    safeText(tm, t.label, chartW - 34f, yy - 11f, palette.bull, 8)
                    if (idx < 2) {
                        drawAxisPricePill(tm, chartW, axisW, yy, fmtPrice(t.price), palette.bull.copy(alpha = 0.85f))
                    }
                }
            }
        }

        // Last price TradingView dashed line + colored bull/bear axis pill
        val lastCandle = candles[window.end - 1]
        val last = lastCandle.close
        val lastBull = lastCandle.close >= lastCandle.open
        val lastColor = if (lastBull) palette.bull else palette.bear
        val lastY = yOf(last).coerceIn(topHudH + 6f, plotBottom - 6f)
        hLine(lastY, 0f, chartW, lastColor.copy(alpha = 0.7f), 1.1f, dashed = true)
        drawAxisPricePill(tm, chartW, axisW, lastY, fmtPrice(last), lastColor)

        // Interactive Tap Crosshair (TradingView style)
        val inspectIdx = crosshairIndex?.takeIf { it in window.start until window.end }
        if (inspectIdx != null && !measureMode) {
            val ic = candles[inspectIdx]
            val cx = xOf(inspectIdx)
            val cy = yOf(ic.close).coerceIn(topHudH, plotBottom)
            val crossColor = palette.muted.copy(alpha = 0.85f)
            drawLine(
                color = crossColor,
                start = Offset(cx, topHudH),
                end = Offset(cx, plotBottom),
                strokeWidth = 1f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
            )
            hLine(cy, 0f, chartW, crossColor, 1f, dashed = true)
            drawAxisPricePill(tm, chartW, axisW, cy, fmtPrice(ic.close), palette.axisDivider, palette.text)
            val tsLabel = MarketClock.formatIstShortTime(ic.timestamp.toEpochMilli())
            val timeBoxX = (cx - 22f).coerceIn(2f, (chartW - 44f).coerceAtLeast(2f))
            drawRoundRect(
                color = palette.axisDivider,
                topLeft = Offset(timeBoxX, plotBottom + 1f),
                size = Size(44f, 16f),
                cornerRadius = CornerRadius(3f, 3f)
            )
            safeText(tm, tsLabel, timeBoxX + 4f, plotBottom + 3f, palette.text, sizeSp = 8, bold = true)
        }

        // Top TradingView OHLCV HUD Bar
        val hudCandle = inspectIdx?.let { candles[it] } ?: lastCandle
        val hudBull = hudCandle.close >= hudCandle.open
        val hudColor = if (hudBull) palette.bull else palette.bear
        val barPct = if (hudCandle.open > 0.0) ((hudCandle.close - hudCandle.open) / hudCandle.open) * 100.0 else 0.0
        val pctSign = if (barPct >= 0.0) "+" else ""
        val hudText = "O ${fmtPrice(hudCandle.open)}  H ${fmtPrice(hudCandle.high)}  L ${fmtPrice(hudCandle.low)}  C ${fmtPrice(hudCandle.close)} ($pctSign${String.format(Locale.US, "%.2f", barPct)}%)  V ${fmtCompactVol(hudCandle.volume)}"
        drawRect(palette.canvasBg.copy(alpha = 0.92f), Offset(0f, 0f), Size(chartW, topHudH))
        safeText(tm, hudText, 6f, 4f, hudColor, sizeSp = 8, bold = true)

        // TradingView-style Time & Price Range Measure Tool overlay
        if (measureStartIndex != null && measureStartIndex in candles.indices) {
            val endIdx = (measureEndIndex ?: measureStartIndex).coerceIn(candles.indices)
            val i1 = min(measureStartIndex, endIdx)
            val i2 = max(measureStartIndex, endIdx)
            val c1 = candles[measureStartIndex]
            val c2 = candles[endIdx]
            val x1 = xOf(i1).coerceIn(0f, chartW)
            val x2 = xOf(i2).coerceIn(0f, chartW)
            val y1 = yOf(c1.close).coerceIn(topHudH, plotBottom)
            val y2 = yOf(c2.close).coerceIn(topHudH, plotBottom)
            val priceDelta = c2.close - c1.close
            val pctDelta = if (c1.close != 0.0) (priceDelta / c1.close) * 100.0 else 0.0
            val rangeColor = if (priceDelta >= 0) palette.bull else palette.bear

            val left = min(x1, x2)
            val top = min(y1, y2)
            val rectW = abs(x2 - x1).coerceAtLeast(4f)
            val rectH = abs(y2 - y1).coerceAtLeast(4f)

            drawRect(rangeColor.copy(alpha = 0.16f), Offset(left, top), Size(rectW, rectH))
            drawRect(rangeColor, Offset(left, top), Size(rectW, rectH), style = Stroke(1.5f))
            drawCircle(rangeColor, radius = 4.5f, center = Offset(xOf(measureStartIndex).coerceIn(0f, chartW), y1))
            drawCircle(rangeColor, radius = 4.5f, center = Offset(xOf(endIdx).coerceIn(0f, chartW), y2))

            val barsCount = abs(endIdx - measureStartIndex)
            val durMins = abs(Duration.between(c1.timestamp, c2.timestamp).toMinutes())
            val durLabel = when {
                durMins >= 1440 -> "${durMins / 1440}d ${(durMins % 1440) / 60}h"
                durMins >= 60 -> "${durMins / 60}h ${durMins % 60}m"
                else -> "${durMins}m"
            }
            val sign = if (priceDelta >= 0) "+" else ""
            val summary = "$sign${fmtPrice(priceDelta)} ($sign${String.format(Locale.US, "%.2f", pctDelta)}%) • $barsCount bars • $durLabel"
            val labelX = left.coerceIn(6f, (chartW - 190f).coerceAtLeast(6f))
            val labelY = (top - 22f).coerceIn(topHudH + 2f, (plotBottom - 22f).coerceAtLeast(topHudH + 2f))
            drawRoundRect(
                color = rangeColor,
                topLeft = Offset(labelX - 4f, labelY - 2f),
                size = Size(195f.coerceAtMost(chartW - labelX + 2f), 18f),
                cornerRadius = CornerRadius(4f, 4f)
            )
            safeText(tm, summary, labelX, labelY + 1f, Color.White, 8, bold = true)
        }
    }
}

@Composable
fun LowerPaneCanvas(
    analysis: ChartAnalysis,
    pane: LowerPane,
    window: ChartWindow,
    showDivergences: Boolean,
    palette: ChartPalette,
    modifier: Modifier = Modifier
) {
    val tm = rememberTextMeasurer()
    Canvas(modifier = modifier) {
        val candles = analysis.candles
        if (candles.isEmpty() || window.end <= window.start) return@Canvas
        drawRect(palette.canvasBg, Offset.Zero, size)
        val axisW = AXIS_DP.dp.toPx()
        val chartW = size.width - axisW
        val barW = chartW / window.count
        val xOf: (Int) -> Float = { i -> ((i - window.start) + 0.5f) * barW }
        hLine(0.5f, 0f, size.width, palette.axisDivider, 1f)
        drawLine(palette.axisDivider, Offset(chartW, 0f), Offset(chartW, size.height), strokeWidth = 1f)

        fun scaleOf(values: List<Double?>, symmetric: Boolean = false): Pair<Double, Double>? {
            var mx = Double.NEGATIVE_INFINITY
            var mn = Double.POSITIVE_INFINITY
            for (i in window.start until window.end) {
                val v = values.getOrNull(i) ?: continue
                mx = max(mx, v)
                mn = min(mn, v)
            }
            if (!mx.isFinite() || !mn.isFinite()) return null
            if (symmetric) {
                val m = max(abs(mx), abs(mn)).coerceAtLeast(1e-9)
                return -m to m
            }
            if (mx <= mn) return (mn - 1.0) to (mx + 1.0)
            return mn to mx
        }

        fun yFor(range: Pair<Double, Double>): (Double) -> Float = { v ->
            val (mn, mx) = range
            (size.height - ((v - mn) / (mx - mn)) * (size.height - 6f) - 3f).toFloat()
        }

        when (pane) {
            LowerPane.VOLUME -> {
                val vols = candles.map<com.example.domain.signal.Candle, Double?> { it.volume }
                val range = scaleOf(vols)?.let { 0.0 to it.second } ?: return@Canvas
                val y = yFor(range)
                val bodyW = (barW * 0.7f).coerceAtLeast(1f)
                for (i in window.start until window.end) {
                    val c = candles[i]
                    val ma = analysis.volumeMa20.getOrNull(i)
                    val above = ma != null && c.volume > ma
                    val col = (if (c.close >= c.open) palette.bull else palette.bear).copy(alpha = if (above) 0.95f else 0.4f)
                    val top = y(c.volume)
                    drawRect(col, Offset(xOf(i) - bodyW / 2f, top), Size(bodyW, (size.height - top).coerceAtLeast(1f)))
                }
                drawPath(seriesPath(analysis.volumeMa20, window, xOf, y), palette.amber, style = Stroke(1.5f))
                safeText(tm, "Vol • MA20 (bright = above MA)", 4f, 2f, palette.muted)
            }
            LowerPane.RSI -> {
                val range = 0.0 to 100.0
                val y = yFor(range)
                hLine(y(70.0), 0f, chartW, palette.bear.copy(alpha = 0.6f), 1f, dashed = true)
                hLine(y(30.0), 0f, chartW, palette.bull.copy(alpha = 0.6f), 1f, dashed = true)
                hLine(y(50.0), 0f, chartW, palette.grid, 0.8f)
                drawPath(seriesPath(analysis.rsi14, window, xOf, y), palette.violet, style = Stroke(1.6f))
                if (showDivergences) {
                    analysis.divergences
                        .filter { it.kind.name.startsWith("RSI") && it.fromIndex >= window.start && it.toIndex < window.end }
                        .forEach { d ->
                            val col = if (d.kind.bullish) palette.bull else palette.bear
                            drawLine(col, Offset(xOf(d.fromIndex), y(d.fromOsc)), Offset(xOf(d.toIndex), y(d.toOsc)), strokeWidth = 2f)
                        }
                }
                val last = analysis.rsi14.getOrNull(window.end - 1)
                safeText(tm, "RSI 14 ${last?.let { String.format(Locale.US, "%.1f", it) } ?: "--"}", 4f, 2f, palette.muted)
            }
            LowerPane.OBV -> {
                val obv = analysis.obv.map<Double, Double?> { it }
                val range = scaleOf(obv) ?: return@Canvas
                drawPath(seriesPath(obv, window, xOf, yFor(range)), palette.cyan, style = Stroke(1.6f))
                safeText(tm, "On-Balance Volume", 4f, 2f, palette.muted)
            }
            LowerPane.ELDER -> {
                val range = scaleOf(analysis.elderForce13, symmetric = true) ?: return@Canvas
                val y = yFor(range)
                val zero = y(0.0)
                hLine(zero, 0f, chartW, palette.grid, 1f)
                val bodyW = (barW * 0.7f).coerceAtLeast(1f)
                for (i in window.start until window.end) {
                    val v = analysis.elderForce13.getOrNull(i) ?: continue
                    val yy = y(v)
                    val col = if (v >= 0) palette.bull else palette.bear
                    drawRect(col.copy(alpha = 0.75f), Offset(xOf(i) - bodyW / 2f, min(yy, zero)), Size(bodyW, abs(zero - yy).coerceAtLeast(1f)))
                }
                safeText(tm, "Elder Force Index (13)", 4f, 2f, palette.muted)
            }
            LowerPane.DELTA -> {
                val deltas = analysis.deltas.map<Double, Double?> { it }
                val range = scaleOf(deltas, symmetric = true) ?: return@Canvas
                val y = yFor(range)
                val zero = y(0.0)
                hLine(zero, 0f, chartW, palette.grid, 1f)
                val bodyW = (barW * 0.7f).coerceAtLeast(1f)
                for (i in window.start until window.end) {
                    val v = analysis.deltas.getOrNull(i) ?: continue
                    val yy = y(v)
                    val col = if (v >= 0) palette.bull else palette.bear
                    drawRect(col.copy(alpha = 0.7f), Offset(xOf(i) - bodyW / 2f, min(yy, zero)), Size(bodyW, abs(zero - yy).coerceAtLeast(1f)))
                }
                val cvd = analysis.cumulativeDelta.map<Double, Double?> { it }
                scaleOf(cvd)?.let { r -> drawPath(seriesPath(cvd, window, xOf, yFor(r)), palette.amber, style = Stroke(1.6f)) }
                safeText(
                    tm,
                    if (analysis.orderFlow.usesTrueDelta) "Delta (aggressor) • CVD" else "Delta (estimated from bar) • CVD",
                    4f, 2f, palette.muted
                )
            }
            LowerPane.ATR -> {
                val combined = analysis.atr7.zip(analysis.atr14) { a, b -> if (a == null) b else if (b == null) a else max(a, b) }
                val range = scaleOf(combined)?.let { (it.first * 0.9) to (it.second * 1.05) } ?: return@Canvas
                val y = yFor(range)
                drawPath(seriesPath(analysis.atr7, window, xOf, y), palette.cyan, style = Stroke(1.5f))
                drawPath(seriesPath(analysis.atr14, window, xOf, y), palette.amber, style = Stroke(1.5f))
                val a7 = analysis.atr7.getOrNull(window.end - 1)
                val a14 = analysis.atr14.getOrNull(window.end - 1)
                safeText(tm, "ATR7 ${a7?.let { fmtPrice(it) } ?: "--"} • ATR14 ${a14?.let { fmtPrice(it) } ?: "--"}", 4f, 2f, palette.muted)
            }
        }
    }
}
