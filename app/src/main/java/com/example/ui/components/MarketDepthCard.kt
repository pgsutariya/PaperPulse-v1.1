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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.StackedBarChart
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.domain.engine.OrderBookLevel
import com.example.domain.engine.OrderBookSnapshot
import com.example.domain.engine.RiskMath
import com.example.domain.model.MarketSegment
import com.example.domain.model.OrderSide
import com.example.ui.theme.LocalTradingColors
import com.example.ui.theme.MonoPriceSmall
import kotlinx.coroutines.delay
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale

/**
 * Real-time Market Depth (Order Book) ladder displayed inside Order Ticket screens and modals.
 * Shows live Buy (Bid) and Sell (Ask) quantities, order counts, proportional depth bars,
 * level vs. cumulative depth view, buy/sell liquidity imbalance, spread, 1-tap Best Bid / Mid / Best Ask
 * quick limit refinement pills, and live tick flashes when book quantities update.
 */
@Composable
fun MarketDepthCard(
    orderBook: OrderBookSnapshot,
    segment: MarketSegment,
    selectedLimitPrice: BigDecimal?,
    onSelectPriceForLimit: (BigDecimal, OrderSide) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    val tradingColors = LocalTradingColors.current
    var expanded by rememberSaveable(orderBook.symbol, compact) { mutableStateOf(true) }
    var showCumulative by rememberSaveable(orderBook.symbol) { mutableStateOf(false) }

    val pulseTransition = rememberInfiniteTransition(label = "marketDepthPulse")
    val liveDotAlpha by pulseTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 850),
            repeatMode = RepeatMode.Reverse
        ),
        label = "liveDepthDot"
    )

    val buyPct = orderBook.buyPressurePercent
    val sellPct = (100 - buyPct).coerceAtLeast(1)
    val animatedBuyFraction by animateFloatAsState(
        targetValue = (buyPct / 100f).coerceIn(0.05f, 0.95f),
        animationSpec = tween(durationMillis = 300),
        label = "buyImbalanceBar"
    )

    val visibleLevels = if (compact) 3 else 5
    val visibleBids = orderBook.bids.take(visibleLevels)
    val visibleAsks = orderBook.asks.take(visibleLevels)

    val cumulativeBids = remember(visibleBids) {
        var acc = 0
        visibleBids.map { acc += it.quantity; acc }
    }
    val cumulativeAsks = remember(visibleAsks) {
        var acc = 0
        visibleAsks.map { acc += it.quantity; acc }
    }

    val maxBarQty = if (showCumulative) {
        maxOf(cumulativeBids.lastOrNull() ?: 1, cumulativeAsks.lastOrNull() ?: 1, 1)
    } else {
        maxOf(
            visibleBids.maxOfOrNull { it.quantity } ?: 1,
            visibleAsks.maxOfOrNull { it.quantity } ?: 1,
            1
        )
    }

    val spreadAmount = orderBook.bestAsk.subtract(orderBook.bestBid).max(BigDecimal.ZERO)
    val midPrice = orderBook.bestBid.add(orderBook.bestAsk)
        .divide(BigDecimal("2"), 2, RoundingMode.HALF_UP)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag(if (compact) "market_depth_modal_card" else "market_depth_card"),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, tradingColors.border)
    ) {
        Column(modifier = Modifier.padding(if (compact) 10.dp else 14.dp)) {
            // Header row (expandable)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { expanded = !expanded }
                    .testTag("market_depth_header")
                    .padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.StackedBarChart,
                        contentDescription = "Market Depth",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "MARKET DEPTH",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = tradingColors.bullishContainer
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
                                            .background(tradingColors.bullish.copy(alpha = liveDotAlpha))
                                    )
                                    Text(
                                        text = if (compact) "LIVE 3-LVL" else "LIVE 5-LVL",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = tradingColors.bullish,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                        Text(
                            text = "Spread ${RiskMath.formatPrice(spreadAmount, segment)} (${String.format(Locale.US, "%.2f", orderBook.spreadPercent)}%) • Tap price to set Limit",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Level vs Cumulative depth mode pill
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (showCumulative) tradingColors.violetContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { showCumulative = !showCumulative }
                        .testTag("market_depth_cumulative_toggle")
                ) {
                    Text(
                        text = if (showCumulative) "CUM" else "LVL",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (showCumulative) tradingColors.violet else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)
                    )
                }

                Spacer(Modifier.width(4.dp))

                Icon(
                    imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = if (expanded) "Collapse Market Depth" else "Expand Market Depth",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp)) {
                    Spacer(Modifier.height(2.dp))

                    // 1-Tap Quick Limit Pricing Strip: Best Bid | Mid | Best Ask
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        QuickBookPricePill(
                            label = "BEST BID",
                            price = orderBook.bestBid,
                            segment = segment,
                            accent = tradingColors.bullish,
                            selected = selectedLimitPrice?.compareTo(orderBook.bestBid) == 0,
                            onClick = { onSelectPriceForLimit(orderBook.bestBid, OrderSide.BUY) },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("market_depth_quick_bid")
                        )
                        QuickBookPricePill(
                            label = "MID",
                            price = midPrice,
                            segment = segment,
                            accent = MaterialTheme.colorScheme.primary,
                            selected = selectedLimitPrice?.compareTo(midPrice) == 0,
                            onClick = { onSelectPriceForLimit(midPrice, OrderSide.BUY) },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("market_depth_quick_mid")
                        )
                        QuickBookPricePill(
                            label = "BEST ASK",
                            price = orderBook.bestAsk,
                            segment = segment,
                            accent = tradingColors.bearish,
                            selected = selectedLimitPrice?.compareTo(orderBook.bestAsk) == 0,
                            onClick = { onSelectPriceForLimit(orderBook.bestAsk, OrderSide.SELL) },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("market_depth_quick_ask")
                        )
                    }

                    // Buy vs Sell Liquidity Pressure Bar
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "BUY $buyPct%",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = tradingColors.bullish
                            )
                            Text(
                                text = if (showCumulative) "CUMULATIVE BOOK DEPTH" else "BOOK IMBALANCE",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "SELL $sellPct%",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = tradingColors.bearish
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .weight(animatedBuyFraction)
                                    .background(tradingColors.bullish)
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .weight((1f - animatedBuyFraction).coerceAtLeast(0.05f))
                                    .background(tradingColors.bearish)
                            )
                        }
                    }

                    // Column headers
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(modifier = Modifier.weight(1f)) {
                            if (!compact) {
                                Text(
                                    text = "ORD",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(0.5f)
                                )
                            }
                            Text(
                                text = if (showCumulative) "CUM" else "QTY",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.End,
                                modifier = Modifier.weight(0.95f)
                            )
                            Text(
                                text = "BID",
                                style = MaterialTheme.typography.labelSmall,
                                color = tradingColors.bullish,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.End,
                                modifier = Modifier.weight(1.15f)
                            )
                        }
                        Row(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "ASK",
                                style = MaterialTheme.typography.labelSmall,
                                color = tradingColors.bearish,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1.15f)
                            )
                            Text(
                                text = if (showCumulative) "CUM" else "QTY",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.End,
                                modifier = Modifier.weight(0.95f)
                            )
                            if (!compact) {
                                Text(
                                    text = "ORD",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.End,
                                    modifier = Modifier.weight(0.5f)
                                )
                            }
                        }
                    }

                    HorizontalDivider(color = tradingColors.border)

                    // Live Dual Ladder Rows
                    val levelsCount = maxOf(visibleBids.size, visibleAsks.size)
                    for (index in 0 until levelsCount) {
                        val bid = visibleBids.getOrNull(index)
                        val ask = visibleAsks.getOrNull(index)
                        val bidQtyForBar = if (showCumulative) cumulativeBids.getOrElse(index) { 0 } else (bid?.quantity ?: 0)
                        val askQtyForBar = if (showCumulative) cumulativeAsks.getOrElse(index) { 0 } else (ask?.quantity ?: 0)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (bid != null) {
                                DepthLevelCell(
                                    level = bid,
                                    displayQty = bidQtyForBar,
                                    isBid = true,
                                    maxQty = maxBarQty,
                                    segment = segment,
                                    compact = compact,
                                    isSelected = selectedLimitPrice != null &&
                                        selectedLimitPrice.compareTo(bid.price) == 0,
                                    onClick = { onSelectPriceForLimit(bid.price, OrderSide.BUY) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("market_depth_bid_$index")
                                )
                            } else {
                                Spacer(Modifier.weight(1f))
                            }

                            if (ask != null) {
                                DepthLevelCell(
                                    level = ask,
                                    displayQty = askQtyForBar,
                                    isBid = false,
                                    maxQty = maxBarQty,
                                    segment = segment,
                                    compact = compact,
                                    isSelected = selectedLimitPrice != null &&
                                        selectedLimitPrice.compareTo(ask.price) == 0,
                                    onClick = { onSelectPriceForLimit(ask.price, OrderSide.SELL) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("market_depth_ask_$index")
                                )
                            } else {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }

                    HorizontalDivider(color = tradingColors.border)

                    // Total Bid / Ask Depth Footer
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "TOTAL BUY QTY",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = formatDepthQty(orderBook.totalBidDepth),
                                style = MonoPriceSmall,
                                color = tradingColors.bullish,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "TOTAL SELL QTY",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = formatDepthQty(orderBook.totalAskDepth),
                                style = MonoPriceSmall,
                                color = tradingColors.bearish,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickBookPricePill(
    label: String,
    price: BigDecimal,
    segment: MarketSegment,
    accent: Color,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = "$label ${RiskMath.formatPrice(price, segment)}, tap to set limit price"
            },
        shape = RoundedCornerShape(10.dp),
        color = if (selected) accent.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            width = if (selected) 1.3.dp else 0.8.dp,
            color = if (selected) accent else accent.copy(alpha = 0.30f)
        )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = accent,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
            Text(
                text = RiskMath.formatPrice(price, segment).removePrefix(segment.currencySymbol),
                style = MonoPriceSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun DepthLevelCell(
    level: OrderBookLevel,
    displayQty: Int,
    isBid: Boolean,
    maxQty: Int,
    segment: MarketSegment,
    compact: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tradingColors = LocalTradingColors.current
    val accent = if (isBid) tradingColors.bullish else tradingColors.bearish
    val fillTarget = (displayQty.toFloat() / maxQty.coerceAtLeast(1).toFloat()).coerceIn(0.08f, 1f)
    val animatedFill by animateFloatAsState(
        targetValue = fillTarget,
        animationSpec = tween(durationMillis = 240),
        label = if (isBid) "bidBar" else "askBar"
    )

    // Subtle live flash whenever order book quantity updates on a new tick
    var lastQty by remember { mutableIntStateOf(level.quantity) }
    var flashActive by remember { mutableStateOf(false) }
    LaunchedEffect(level.quantity) {
        if (level.quantity != lastQty) {
            lastQty = level.quantity
            flashActive = true
            delay(360L)
            flashActive = false
        }
    }
    val cellBg by animateColorAsState(
        targetValue = when {
            isSelected -> accent.copy(alpha = 0.20f)
            flashActive -> accent.copy(alpha = 0.14f)
            else -> MaterialTheme.colorScheme.surfaceContainerLow
        },
        animationSpec = tween(durationMillis = 220),
        label = "depthTickFlash"
    )

    val priceFormatted = RiskMath.formatPrice(level.price, segment).removePrefix(segment.currencySymbol)
    val sideDesc = if (isBid) "Bid" else "Ask"
    val barColor = accent.copy(alpha = 0.17f)

    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(cellBg)
            .drawBehind {
                val barW = (size.width * animatedFill).coerceIn(2f, size.width)
                val startX = if (isBid) size.width - barW else 0f
                drawRect(
                    color = barColor,
                    topLeft = Offset(startX, 0f),
                    size = Size(barW, size.height)
                )
            }
            .border(
                width = if (isSelected) 1.3.dp else 0.5.dp,
                color = if (isSelected) accent else Color.Transparent,
                shape = RoundedCornerShape(8.dp)
            )
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = "$sideDesc price ${RiskMath.formatPrice(level.price, segment)}, quantity $displayQty, tap to set limit price"
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.Center)
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isBid) {
                if (!compact) {
                    Text(
                        text = level.ordersCount.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.weight(0.48f)
                    )
                }
                Text(
                    text = formatCompactQty(displayQty),
                    style = MonoPriceSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    modifier = Modifier.weight(0.95f)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = priceFormatted,
                    style = MonoPriceSmall,
                    color = accent,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    modifier = Modifier.weight(1.15f)
                )
            } else {
                Text(
                    text = priceFormatted,
                    style = MonoPriceSmall,
                    color = accent,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    modifier = Modifier.weight(1.15f)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = formatCompactQty(displayQty),
                    style = MonoPriceSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    modifier = Modifier.weight(0.95f)
                )
                if (!compact) {
                    Text(
                        text = level.ordersCount.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                        maxLines = 1,
                        modifier = Modifier.weight(0.48f)
                    )
                }
            }
        }
    }
}

private fun formatCompactQty(qty: Int): String {
    return when {
        qty >= 1_000_000 -> String.format(Locale.US, "%.1fM", qty / 1_000_000.0)
        qty >= 10_000 -> String.format(Locale.US, "%.1fK", qty / 1_000.0)
        else -> NumberFormat.getIntegerInstance(Locale.US).format(qty)
    }
}

private fun formatDepthQty(qty: Int): String {
    return NumberFormat.getIntegerInstance(Locale.US).format(qty)
}
