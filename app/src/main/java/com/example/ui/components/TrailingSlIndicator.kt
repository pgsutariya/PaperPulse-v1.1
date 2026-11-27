package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.NorthEast
import androidx.compose.material.icons.rounded.SouthEast
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.domain.engine.RiskMath
import com.example.domain.model.MarketSegment
import com.example.domain.model.OrderSide
import com.example.domain.model.Position
import com.example.domain.model.TrailMode
import com.example.ui.theme.LocalTradingColors
import com.example.ui.theme.MonoPriceSmall
import kotlinx.coroutines.delay
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

enum class TrailingSlStatus(val label: String) {
    PROFIT_LOCKED("PROFIT LOCKED"),
    BREAKEVEN("BREAKEVEN"),
    NEAR_TRIGGER("NEAR TSL"),
    TRAILING_ACTIVE("TSL TRAILING")
}

/**
 * Pure live telemetry snapshot for an open position's active Trailing Stop Loss.
 * Normalized track fractions are strictly in [0f..1f] along the favorable-move axis
 * (left = stop/adverse side, right = favorable/peak side) for both LONG and SHORT positions.
 */
data class TrailingSlTelemetry(
    val currentSl: BigDecimal,
    val entryPrice: BigDecimal,
    val ltp: BigDecimal,
    val peakPrice: BigDecimal,
    val cushionAmount: BigDecimal,
    val cushionPercent: Double,
    val lockedPerUnit: BigDecimal,
    val lockedTotal: BigDecimal,
    val initialImpliedSl: BigDecimal,
    val ratchetedGain: BigDecimal,
    val nextRatchetTriggerPrice: BigDecimal,
    val distanceToNextRatchet: BigDecimal,
    val status: TrailingSlStatus,
    val slFraction: Float,
    val entryFraction: Float,
    val ltpFraction: Float,
    val peakFraction: Float
)

object TrailingSlTelemetryCalculator {

    fun compute(
        position: Position,
        ltp: BigDecimal,
        tickSize: BigDecimal = BigDecimal("0.05")
    ): TrailingSlTelemetry? {
        val currentSl = position.activeSlPrice ?: return null
        if (!position.isTslEnabled) return null

        val isLong = position.side == OrderSide.BUY
        val entry = position.averageEntryPrice
        val safeTick = if (tickSize > BigDecimal.ZERO) tickSize else BigDecimal("0.05")
        val bestPrice = if (isLong) {
            maxOf(position.tslBestPrice, ltp, entry)
        } else {
            minOf(position.tslBestPrice, ltp, entry)
        }

        val cushionAmount = if (isLong) {
            ltp.subtract(currentSl)
        } else {
            currentSl.subtract(ltp)
        }.setScale(2, RoundingMode.HALF_UP)

        val cushionPercent = if (ltp > BigDecimal.ZERO) {
            cushionAmount.divide(ltp, 6, RoundingMode.HALF_UP).toDouble() * 100.0
        } else 0.0

        val lockedPerUnit = if (isLong) {
            currentSl.subtract(entry)
        } else {
            entry.subtract(currentSl)
        }.setScale(2, RoundingMode.HALF_UP)

        val lockedTotal = lockedPerUnit.multiply(BigDecimal(position.quantity))
            .setScale(2, RoundingMode.HALF_UP)

        val initialTrailAmt = RiskMath.computeTrailAmount(
            referencePrice = entry,
            trailMode = position.tslTrailMode,
            trailValue = position.tslTrailValue
        )
        val initialImpliedSl = RiskMath.roundToTick(
            if (isLong) entry.subtract(initialTrailAmt).max(safeTick) else entry.add(initialTrailAmt),
            safeTick
        )

        val rawRatchet = if (isLong) {
            currentSl.subtract(initialImpliedSl)
        } else {
            initialImpliedSl.subtract(currentSl)
        }
        val ratchetedGain = rawRatchet.max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP)

        // Exact next market price that causes TSL to ratchet favorably by at least 1 tick
        val currentTrailAmt = RiskMath.computeTrailAmount(
            referencePrice = bestPrice,
            trailMode = position.tslTrailMode,
            trailValue = position.tslTrailValue
        )
        val nextRatchetTrigger = RiskMath.roundToTick(
            if (isLong) {
                maxOf(bestPrice.add(safeTick), currentSl.add(currentTrailAmt).add(safeTick))
            } else {
                minOf(bestPrice.subtract(safeTick), currentSl.subtract(currentTrailAmt).subtract(safeTick)).max(safeTick)
            },
            safeTick
        )
        val distanceToNextRatchet = if (isLong) {
            nextRatchetTrigger.subtract(ltp).max(BigDecimal.ZERO)
        } else {
            ltp.subtract(nextRatchetTrigger).max(BigDecimal.ZERO)
        }.setScale(2, RoundingMode.HALF_UP)

        val breakevenBand = entry.multiply(BigDecimal("0.0008")).max(safeTick)
        val status = when {
            cushionPercent <= 0.35 -> TrailingSlStatus.NEAR_TRIGGER
            lockedPerUnit > breakevenBand -> TrailingSlStatus.PROFIT_LOCKED
            lockedPerUnit.abs() <= breakevenBand -> TrailingSlStatus.BREAKEVEN
            else -> TrailingSlStatus.TRAILING_ACTIVE
        }

        // Normalize onto favorable-direction axis: 0f = adverse side, 1f = peak favorable side
        val rawPrices = listOf(currentSl, entry, ltp, bestPrice).map { it.toDouble() }
        val minP = rawPrices.minOrNull() ?: 0.0
        val maxP = rawPrices.maxOrNull() ?: 1.0
        val pad = ((maxP - minP) * 0.14).coerceAtLeast(ltp.toDouble() * 0.0025).coerceAtLeast(0.05)
        val lowBound = minP - pad
        val highBound = maxP + pad
        val span = (highBound - lowBound).coerceAtLeast(0.0001)

        fun toFraction(price: BigDecimal): Float {
            val raw = ((price.toDouble() - lowBound) / span).toFloat()
            val oriented = if (isLong) raw else (1f - raw)
            return oriented.coerceIn(0.04f, 0.96f)
        }

        return TrailingSlTelemetry(
            currentSl = currentSl,
            entryPrice = entry,
            ltp = ltp,
            peakPrice = bestPrice,
            cushionAmount = cushionAmount,
            cushionPercent = cushionPercent,
            lockedPerUnit = lockedPerUnit,
            lockedTotal = lockedTotal,
            initialImpliedSl = initialImpliedSl,
            ratchetedGain = ratchetedGain,
            nextRatchetTriggerPrice = nextRatchetTrigger,
            distanceToNextRatchet = distanceToNextRatchet,
            status = status,
            slFraction = toFraction(currentSl),
            entryFraction = toFraction(entry),
            ltpFraction = toFraction(ltp),
            peakFraction = toFraction(bestPrice)
        )
    }
}

/**
 * Compact pulsing badge displayed in the PositionCard header whenever Trailing Stop Loss is active.
 */
@Composable
fun TrailingSlHeaderBadge(
    status: TrailingSlStatus,
    modifier: Modifier = Modifier
) {
    val tradingColors = LocalTradingColors.current
    val pulseTransition = rememberInfiniteTransition(label = "tslHeaderPulse")
    val dotAlpha by pulseTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 750),
            repeatMode = RepeatMode.Reverse
        ),
        label = "tslDotAlpha"
    )

    val (fg, bg) = when (status) {
        TrailingSlStatus.PROFIT_LOCKED -> tradingColors.bullish to tradingColors.bullishContainer
        TrailingSlStatus.BREAKEVEN -> tradingColors.cryptoCyan to tradingColors.violetContainer
        TrailingSlStatus.NEAR_TRIGGER -> tradingColors.amber to tradingColors.amberContainer
        TrailingSlStatus.TRAILING_ACTIVE -> tradingColors.violet to tradingColors.violetContainer
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = bg,
        border = BorderStroke(0.8.dp, fg.copy(alpha = 0.45f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(fg.copy(alpha = dotAlpha))
            )
            Text(
                text = status.label,
                style = MaterialTheme.typography.labelSmall,
                color = fg,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * Compact, tick-reactive Trailing Stop Loss visual indicator tailored for Positions Table rows.
 * Displays the live TSL price with ratchet flash animation, directional arrow (↑/↓), pulsing status dot,
 * mini real-time cushion bar (TSL -> LTP -> Peak), and locked profit / ratcheted delta.
 */
@Composable
fun CompactTrailingSlRowIndicator(
    symbol: String,
    telemetry: TrailingSlTelemetry,
    segment: MarketSegment,
    side: OrderSide,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tradingColors = LocalTradingColors.current
    val isLong = side == OrderSide.BUY

    var previousSl by remember(symbol) { mutableStateOf(telemetry.currentSl) }
    var ratchetFlashActive by remember(symbol) { mutableStateOf(false) }

    LaunchedEffect(telemetry.currentSl) {
        if (telemetry.currentSl.compareTo(previousSl) != 0) {
            previousSl = telemetry.currentSl
            ratchetFlashActive = true
            delay(1600L)
            ratchetFlashActive = false
        }
    }

    val pulseTransition = rememberInfiniteTransition(label = "compactTslPulse_$symbol")
    val dotAlpha by pulseTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 720),
            repeatMode = RepeatMode.Reverse
        ),
        label = "compactTslDot"
    )

    val accent = when (telemetry.status) {
        TrailingSlStatus.PROFIT_LOCKED -> tradingColors.bullish
        TrailingSlStatus.BREAKEVEN -> tradingColors.cryptoCyan
        TrailingSlStatus.NEAR_TRIGGER -> tradingColors.amber
        TrailingSlStatus.TRAILING_ACTIVE -> tradingColors.violet
    }
    val container = when (telemetry.status) {
        TrailingSlStatus.PROFIT_LOCKED -> tradingColors.bullishContainer
        TrailingSlStatus.BREAKEVEN -> tradingColors.violetContainer
        TrailingSlStatus.NEAR_TRIGGER -> tradingColors.amberContainer
        TrailingSlStatus.TRAILING_ACTIVE -> tradingColors.violetContainer
    }

    val bgColor by animateColorAsState(
        targetValue = if (ratchetFlashActive) accent.copy(alpha = 0.24f) else container.copy(alpha = 0.55f),
        animationSpec = tween(durationMillis = 280),
        label = "compactTslBg"
    )
    val borderColor by animateColorAsState(
        targetValue = if (ratchetFlashActive) accent else accent.copy(alpha = 0.45f),
        animationSpec = tween(durationMillis = 280),
        label = "compactTslBorder"
    )
    val slAnim by animateFloatAsState(
        targetValue = telemetry.slFraction,
        animationSpec = tween(durationMillis = 320),
        label = "compactSlFrac"
    )
    val ltpAnim by animateFloatAsState(
        targetValue = telemetry.ltpFraction,
        animationSpec = tween(durationMillis = 240),
        label = "compactLtpFrac"
    )

    val arrow = if (isLong) "↑" else "↓"
    val trackBg = MaterialTheme.colorScheme.surfaceContainerHighest
    val ltpColor = MaterialTheme.colorScheme.primary

    Surface(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .testTag("positions_table_tsl_cell_$symbol")
            .semantics {
                contentDescription = "Active Trailing Stop Loss for $symbol at ${RiskMath.formatPrice(telemetry.currentSl, segment)}, ${telemetry.status.label}, cushion ${String.format(Locale.US, "%.2f", telemetry.cushionPercent)} percent"
            },
        shape = RoundedCornerShape(10.dp),
        color = bgColor,
        border = BorderStroke(if (ratchetFlashActive) 1.4.dp else 0.9.dp, borderColor)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
            horizontalAlignment = Alignment.End
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(accent.copy(alpha = dotAlpha))
                )
                Text(
                    text = if (ratchetFlashActive) "TSL $arrow MOVED" else "TSL $arrow",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = accent,
                    maxLines = 1
                )
            }
            Text(
                text = RiskMath.formatPrice(telemetry.currentSl, segment),
                style = MonoPriceSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // Mini real-time cushion track (TSL -> LTP)
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
            ) {
                val cy = size.height / 2f
                val h = 3.5.dp.toPx()
                val cr = CornerRadius(h / 2f, h / 2f)
                drawRoundRect(
                    color = trackBg,
                    topLeft = Offset(0f, cy - h / 2f),
                    size = Size(size.width, h),
                    cornerRadius = cr
                )
                val slX = size.width * slAnim
                val ltpX = size.width * ltpAnim
                val left = minOf(slX, ltpX)
                val width = (maxOf(slX, ltpX) - left).coerceAtLeast(3f)
                drawRoundRect(
                    color = accent.copy(alpha = 0.72f),
                    topLeft = Offset(left, cy - h / 2f),
                    size = Size(width, h),
                    cornerRadius = cr
                )
                drawCircle(color = accent, radius = 3.dp.toPx(), center = Offset(slX, cy))
                drawCircle(color = ltpColor, radius = 2.6.dp.toPx(), center = Offset(ltpX, cy))
            }
            val subText = when {
                telemetry.lockedPerUnit > BigDecimal.ZERO ->
                    "Lock ${RiskMath.formatSignedCurrency(telemetry.lockedTotal, segment)}"
                telemetry.ratchetedGain > BigDecimal.ZERO ->
                    "+${RiskMath.formatPrice(telemetry.ratchetedGain, segment)}/u"
                else ->
                    "Gap ${String.format(Locale.US, "%.2f", telemetry.cushionPercent)}%"
            }
            Text(
                text = subText,
                style = MaterialTheme.typography.labelSmall,
                color = if (telemetry.lockedPerUnit > BigDecimal.ZERO) tradingColors.bullish else accent,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Rich visual indicator embedded inside open Position cards highlighting active Trailing Stop Loss
 * levels, dynamic ratcheting flashes as market moves, live price-track Canvas (TSL -> Entry -> LTP -> Peak),
 * next-ratchet proximity, cushion distance, and locked-in profit telemetry.
 */
@Composable
fun TrailingSlVisualTracker(
    symbol: String,
    telemetry: TrailingSlTelemetry,
    segment: MarketSegment,
    side: OrderSide,
    trailMode: TrailMode,
    trailValue: BigDecimal,
    modifier: Modifier = Modifier
) {
    val tradingColors = LocalTradingColors.current
    val isLong = side == OrderSide.BUY

    // Detect dynamic TSL ratcheting as live market ticks adjust activeSlPrice
    var previousSl by remember(symbol) { mutableStateOf(telemetry.currentSl) }
    var ratchetFlashActive by remember(symbol) { mutableStateOf(false) }
    var lastRatchetStep by remember(symbol) { mutableStateOf<BigDecimal?>(null) }

    LaunchedEffect(telemetry.currentSl) {
        val delta = telemetry.currentSl.subtract(previousSl)
        if (delta.compareTo(BigDecimal.ZERO) != 0) {
            lastRatchetStep = delta
            previousSl = telemetry.currentSl
            ratchetFlashActive = true
            delay(1800L)
            ratchetFlashActive = false
        }
    }

    val accentColor = when (telemetry.status) {
        TrailingSlStatus.PROFIT_LOCKED -> tradingColors.bullish
        TrailingSlStatus.BREAKEVEN -> tradingColors.cryptoCyan
        TrailingSlStatus.NEAR_TRIGGER -> tradingColors.amber
        TrailingSlStatus.TRAILING_ACTIVE -> tradingColors.violet
    }
    val containerColor = when (telemetry.status) {
        TrailingSlStatus.PROFIT_LOCKED -> tradingColors.bullishContainer
        TrailingSlStatus.BREAKEVEN -> tradingColors.violetContainer
        TrailingSlStatus.NEAR_TRIGGER -> tradingColors.amberContainer
        TrailingSlStatus.TRAILING_ACTIVE -> tradingColors.violetContainer
    }

    val animatedBorderColor by animateColorAsState(
        targetValue = if (ratchetFlashActive) accentColor else accentColor.copy(alpha = 0.45f),
        animationSpec = tween(durationMillis = 320),
        label = "tslBorderFlash"
    )
    val animatedSurfaceColor by animateColorAsState(
        targetValue = if (ratchetFlashActive) containerColor.copy(alpha = 0.72f) else MaterialTheme.colorScheme.surfaceContainerLow,
        animationSpec = tween(durationMillis = 320),
        label = "tslSurfaceFlash"
    )

    val slAnim by animateFloatAsState(
        targetValue = telemetry.slFraction,
        animationSpec = tween(durationMillis = 360),
        label = "tslFrac"
    )
    val entryAnim by animateFloatAsState(
        targetValue = telemetry.entryFraction,
        animationSpec = tween(durationMillis = 360),
        label = "entryFrac"
    )
    val ltpAnim by animateFloatAsState(
        targetValue = telemetry.ltpFraction,
        animationSpec = tween(durationMillis = 280),
        label = "ltpFrac"
    )
    val peakAnim by animateFloatAsState(
        targetValue = telemetry.peakFraction,
        animationSpec = tween(durationMillis = 360),
        label = "peakFrac"
    )

    val trailRuleLabel = if (trailMode == TrailMode.PERCENT) {
        "${trailValue.stripTrailingZeros().toPlainString()}% trail"
    } else {
        "${segment.currencySymbol}${trailValue.stripTrailingZeros().toPlainString()} trail"
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("tsl_visual_tracker_$symbol")
            .semantics {
                contentDescription = "Active Trailing Stop Loss for $symbol at ${RiskMath.formatPrice(telemetry.currentSl, segment)}, status ${telemetry.status.label}, cushion ${String.format(Locale.US, "%.2f", telemetry.cushionPercent)} percent"
            },
        shape = RoundedCornerShape(12.dp),
        color = animatedSurfaceColor,
        border = BorderStroke(if (ratchetFlashActive) 1.5.dp else 1.dp, animatedBorderColor)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Header strip: TSL status + live ratchet pill
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val icon = when (telemetry.status) {
                        TrailingSlStatus.PROFIT_LOCKED -> Icons.Rounded.Lock
                        TrailingSlStatus.NEAR_TRIGGER -> Icons.Rounded.WarningAmber
                        else -> if (isLong) Icons.Rounded.NorthEast else Icons.Rounded.SouthEast
                    }
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = "DYNAMIC TRAILING SL ($trailRuleLabel)",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = accentColor
                    )
                }

                AnimatedVisibility(visible = ratchetFlashActive && lastRatchetStep != null) {
                    val arrow = if (isLong) "↑" else "↓"
                    val stepAbs = lastRatchetStep?.abs() ?: BigDecimal.ZERO
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = accentColor
                    ) {
                        Text(
                            text = "RATCHET $arrow ${RiskMath.formatPrice(stepAbs, segment)}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            // Live Price Track Canvas (Danger behind TSL | Active Cushion | Peak Extension)
            val trackBg = MaterialTheme.colorScheme.surfaceContainerHigh
            val dangerColor = tradingColors.bearish.copy(alpha = 0.28f)
            val peakBandColor = tradingColors.bullish.copy(alpha = 0.22f)
            val entryMarkerColor = MaterialTheme.colorScheme.onSurfaceVariant
            val ltpMarkerColor = MaterialTheme.colorScheme.primary
            val peakMarkerColor = tradingColors.bullish

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp)
            ) {
                val trackY = size.height * 0.54f
                val trackHeight = 7.dp.toPx()
                val radius = CornerRadius(trackHeight / 2f, trackHeight / 2f)

                // Base track
                drawRoundRect(
                    color = trackBg,
                    topLeft = Offset(0f, trackY - trackHeight / 2f),
                    size = Size(size.width, trackHeight),
                    cornerRadius = radius
                )

                val slX = size.width * slAnim
                val entryX = size.width * entryAnim
                val ltpX = size.width * ltpAnim
                val peakX = size.width * peakAnim

                // Adverse/stop zone behind TSL
                drawRoundRect(
                    color = dangerColor,
                    topLeft = Offset(0f, trackY - trackHeight / 2f),
                    size = Size(slX.coerceAtLeast(2f), trackHeight),
                    cornerRadius = radius
                )

                // Live trailing cushion band between TSL and LTP
                val bandStart = minOf(slX, ltpX)
                val bandWidth = (maxOf(slX, ltpX) - bandStart).coerceAtLeast(3f)
                drawRoundRect(
                    color = accentColor.copy(alpha = 0.55f),
                    topLeft = Offset(bandStart, trackY - trackHeight / 2f),
                    size = Size(bandWidth, trackHeight),
                    cornerRadius = radius
                )

                // Peak extension segment between LTP and Best Peak
                if (peakX > ltpX + 2f) {
                    drawRoundRect(
                        color = peakBandColor,
                        topLeft = Offset(ltpX, trackY - trackHeight / 2f),
                        size = Size((peakX - ltpX).coerceAtLeast(2f), trackHeight),
                        cornerRadius = radius
                    )
                }

                // Entry tick line
                drawLine(
                    color = entryMarkerColor,
                    start = Offset(entryX, trackY - 9.dp.toPx()),
                    end = Offset(entryX, trackY + 9.dp.toPx()),
                    strokeWidth = 1.6.dp.toPx()
                )

                // Peak marker
                drawCircle(
                    color = peakMarkerColor,
                    radius = 3.5.dp.toPx(),
                    center = Offset(peakX, trackY)
                )

                // Active TSL shield marker (outer ring + core)
                drawCircle(
                    color = accentColor.copy(alpha = 0.28f),
                    radius = 8.dp.toPx(),
                    center = Offset(slX, trackY)
                )
                drawCircle(
                    color = accentColor,
                    radius = 5.dp.toPx(),
                    center = Offset(slX, trackY)
                )

                // Live LTP marker
                drawCircle(
                    color = Color.White,
                    radius = 5.5.dp.toPx(),
                    center = Offset(ltpX, trackY)
                )
                drawCircle(
                    color = ltpMarkerColor,
                    radius = 3.6.dp.toPx(),
                    center = Offset(ltpX, trackY)
                )
            }

            // Track Legend Strip: Live TSL vs Peak & Next Ratchet Trigger
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "TSL ${RiskMath.formatPrice(telemetry.currentSl, segment)}",
                    style = MonoPriceSmall,
                    color = accentColor,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Next ${if (isLong) "↑" else "↓"} @ ${RiskMath.formatPrice(telemetry.nextRatchetTriggerPrice, segment)}",
                    style = MonoPriceSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Peak ${RiskMath.formatPrice(telemetry.peakPrice, segment)}",
                    style = MonoPriceSmall,
                    color = tradingColors.bullish
                )
            }

            // Live Metrics Strip: Cushion & Locked Profit / Ratcheted Gain
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "CUSHION TO TSL",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${String.format(Locale.US, "%.2f", telemetry.cushionPercent)}% (${RiskMath.formatPrice(telemetry.cushionAmount.abs(), segment)})",
                        style = MonoPriceSmall,
                        color = if (telemetry.status == TrailingSlStatus.NEAR_TRIGGER) tradingColors.amber else MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(Modifier.size(6.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.End
                ) {
                    val isProfitLocked = telemetry.lockedPerUnit > BigDecimal.ZERO
                    Text(
                        text = if (isProfitLocked) "LOCKED PROFIT" else "RATCHETED",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val metricText = if (isProfitLocked) {
                        RiskMath.formatSignedCurrency(telemetry.lockedTotal, segment)
                    } else if (telemetry.ratchetedGain > BigDecimal.ZERO) {
                        "+${RiskMath.formatPrice(telemetry.ratchetedGain, segment)}/u"
                    } else {
                        "Tracking ${if (isLong) "highs" else "lows"}"
                    }
                    Text(
                        text = metricText,
                        style = MonoPriceSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isProfitLocked) tradingColors.bullish else accentColor
                    )
                }
            }
        }
    }
}
