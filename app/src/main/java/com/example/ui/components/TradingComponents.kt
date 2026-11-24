package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.feed.live.FeedType
import com.example.data.feed.live.LiveConnectionStatus
import com.example.data.feed.live.SegmentBadgeMode
import com.example.domain.engine.RiskMath
import com.example.domain.model.AccountState
import com.example.domain.model.FeedConnectionState
import com.example.domain.model.InstrumentType
import com.example.domain.model.MarketSegment
import com.example.domain.model.ValidityState
import com.example.ui.theme.LocalTradingColors
import com.example.ui.theme.MonoPriceMedium
import com.example.ui.theme.MonoPriceSmall
import java.math.BigDecimal

@Composable
fun SegmentBadge(segment: MarketSegment, modifier: Modifier = Modifier) {
    val tradingColors = LocalTradingColors.current
    val (bg, fg) = when (segment) {
        MarketSegment.NSE -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.primary
        MarketSegment.BSE -> tradingColors.violetContainer to tradingColors.violet
        MarketSegment.CRYPTO -> tradingColors.cryptoCyan.copy(alpha = 0.16f) to tradingColors.cryptoCyan
        MarketSegment.MCX -> tradingColors.amberContainer to tradingColors.amber
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = bg
    ) {
        Text(
            text = segment.label,
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
fun InstrumentTypeBadge(type: InstrumentType, modifier: Modifier = Modifier) {
    val tradingColors = LocalTradingColors.current
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(0.5.dp, tradingColors.border)
    ) {
        Text(
            text = type.shortLabel.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
fun ValidityStateBadge(state: ValidityState, modifier: Modifier = Modifier) {
    val tradingColors = LocalTradingColors.current
    val (bg, fg) = when (state) {
        ValidityState.ACTIVE -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.primary
        ValidityState.TRIGGERED -> tradingColors.bullishContainer to tradingColors.bullish
        ValidityState.EXPIRED -> tradingColors.amberContainer to tradingColors.amber
        ValidityState.CANCELLED -> tradingColors.bearishContainer to tradingColors.bearish
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = bg
    ) {
        Text(
            text = state.name,
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
        )
    }
}

@Composable
fun FeedHealthBadge(
    badge: SegmentBadgeMode,
    modifier: Modifier = Modifier
) {
    val tradingColors = LocalTradingColors.current
    val (bg, fg) = when (badge) {
        SegmentBadgeMode.LIVE -> tradingColors.bullishContainer to tradingColors.bullish
        SegmentBadgeMode.DELAYED -> tradingColors.amberContainer to tradingColors.amber
        SegmentBadgeMode.SIMULATED -> MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurfaceVariant
        SegmentBadgeMode.SUSPENDED -> tradingColors.bearishContainer to tradingColors.bearish
    }
    Surface(
        modifier = modifier.testTag("feed_badge_${badge.name.lowercase()}"),
        shape = RoundedCornerShape(6.dp),
        color = bg
    ) {
        Text(
            text = badge.label,
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
fun FeedLostWarningBanner(
    detail: String,
    onReconnect: () -> Unit,
    onSwitchToSimulated: () -> Unit,
    headline: String = "FEED LOST — risk controls suspended",
    modifier: Modifier = Modifier
) {
    val tradingColors = LocalTradingColors.current
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("feed_lost_warning_banner"),
        shape = RoundedCornerShape(14.dp),
        color = tradingColors.bearishContainer,
        border = BorderStroke(1.dp, tradingColors.bearish.copy(alpha = 0.6f))
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.WarningAmber,
                    contentDescription = "Feed Lost Warning",
                    tint = tradingColors.bearish,
                    modifier = Modifier.size(20.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = headline,
                        style = MaterialTheme.typography.labelLarge,
                        color = tradingColors.bearish,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = detail.ifBlank { "Open positions detected. Live tick halted; simulated fallback blocked to prevent false SL/TSL triggers." },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onReconnect,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("banner_reconnect_button"),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = tradingColors.bearish,
                        contentColor = Color.White
                    )
                ) {
                    Text("Reconnect Live", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = onSwitchToSimulated,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("banner_switch_simulated_button"),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, tradingColors.bearish)
                ) {
                    Text(
                        "Switch to Simulated",
                        style = MaterialTheme.typography.labelMedium,
                        color = tradingColors.bearish,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
fun SparklineChart(
    points: List<BigDecimal>,
    isPositive: Boolean,
    modifier: Modifier = Modifier,
    strokeWidthDp: Float = 2.2f
) {
    val tradingColors = LocalTradingColors.current
    val lineColor = if (isPositive) tradingColors.bullish else tradingColors.bearish

    Canvas(modifier = modifier) {
        if (points.size < 2) return@Canvas
        val values = points.map { it.toFloat() }
        val minVal = values.minOrNull() ?: 0f
        val maxVal = values.maxOrNull() ?: 1f
        val range = (maxVal - minVal).takeIf { it > 0.0001f } ?: 1f

        val stepX = size.width / (values.size - 1).coerceAtLeast(1)
        val padY = size.height * 0.1f
        val drawHeight = size.height - padY * 2

        val linePath = Path()
        val fillPath = Path()

        values.forEachIndexed { idx, v ->
            val x = idx * stepX
            val normalized = (v - minVal) / range
            val y = padY + drawHeight * (1f - normalized)
            if (idx == 0) {
                linePath.moveTo(x, y)
                fillPath.moveTo(x, size.height)
                fillPath.lineTo(x, y)
            } else {
                linePath.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
            if (idx == values.lastIndex) {
                fillPath.lineTo(x, size.height)
                fillPath.close()
            }
        }

        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(lineColor.copy(alpha = 0.28f), lineColor.copy(alpha = 0.0f)),
                startY = 0f,
                endY = size.height
            )
        )
        drawPath(
            path = linePath,
            color = lineColor,
            style = Stroke(width = strokeWidthDp.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        val lastY = padY + drawHeight * (1f - (values.last() - minVal) / range)
        drawCircle(
            color = lineColor,
            radius = 3.dp.toPx(),
            center = Offset(size.width, lastY)
        )
    }
}

@Composable
fun PortfolioPulseHeader(
    account: AccountState,
    unrealisedPnl: BigDecimal,
    feedState: FeedConnectionState,
    istClockText: String,
    onOpenFeedSettings: () -> Unit,
    onRefreshLive: () -> Unit = {},
    activeFeedType: FeedType = FeedType.SIMULATED,
    liveConnectionStatus: LiveConnectionStatus = LiveConnectionStatus.DISCONNECTED,
    riskControlsSuspended: Boolean = false,
    cryptoUnrealisedPnl: BigDecimal = BigDecimal.ZERO,
    modifier: Modifier = Modifier
) {
    val tradingColors = LocalTradingColors.current
    val totalMtm = account.realisedPnl.add(unrealisedPnl)
    val cryptoMtm = account.cryptoRealisedPnl.add(cryptoUnrealisedPnl)
    val mtmColor by animateColorAsState(
        targetValue = if (totalMtm >= BigDecimal.ZERO) tradingColors.bullish else tradingColors.bearish,
        animationSpec = tween(250),
        label = "mtmColor"
    )
    val cryptoMtmColor = if (cryptoMtm >= BigDecimal.ZERO) tradingColors.bullish else tradingColors.bearish
    val dotColor = when {
        riskControlsSuspended -> tradingColors.bearish
        activeFeedType == FeedType.SIMULATED -> tradingColors.bullish
        liveConnectionStatus == LiveConnectionStatus.CONNECTED -> tradingColors.bullish
        else -> tradingColors.amber
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, tradingColors.border)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "INDIA PAPER CAPITAL (₹)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = RiskMath.formatPortfolioInr(account.availableMargin),
                        style = MonoPriceMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "INDIA NET MTM",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = RiskMath.formatPortfolioInr(totalMtm, signed = true),
                        style = MonoPriceMedium,
                        color = mtmColor
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "CRYPTO ($): ${RiskMath.formatPortfolioUsd(account.cryptoAvailableMargin)}",
                    style = MonoPriceSmall,
                    color = tradingColors.amber
                )
                Text(
                    text = "MTM ${RiskMath.formatPortfolioUsd(cryptoMtm, signed = true)}",
                    style = MonoPriceSmall,
                    color = cryptoMtmColor
                )
            }

            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(dotColor)
                    )
                    Spacer(Modifier.width(6.dp))
                    val statusLabel = when {
                        riskControlsSuspended -> "FEED LOST • $istClockText"
                        activeFeedType == FeedType.SIMULATED -> "Simulated • $istClockText"
                        liveConnectionStatus == LiveConnectionStatus.CONNECTED -> "${activeFeedType.label} • $istClockText"
                        liveConnectionStatus == LiveConnectionStatus.CONNECTING ||
                            liveConnectionStatus == LiveConnectionStatus.RECONNECTING -> "${activeFeedType.label} (${liveConnectionStatus.name})"
                        else -> "${activeFeedType.label} (Fallback) • $istClockText"
                    }
                    Text(
                        text = statusLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (riskControlsSuspended) tradingColors.bearish else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("refresh_live_quotes_button")
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(onClick = onRefreshLive),
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer
                    ) {
                        Box(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = "Refresh Live Quotes",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    Surface(
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("open_feed_settings_button")
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(onClick = onOpenFeedSettings),
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Tune,
                                contentDescription = "Feed & Simulation Settings",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "Sim / Feed",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EmptyStatePanel(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    val tradingColors = LocalTradingColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            modifier = Modifier.size(64.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, tradingColors.border)
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
