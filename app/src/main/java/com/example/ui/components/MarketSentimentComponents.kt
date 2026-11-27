package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
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
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.TrendingDown
import androidx.compose.material.icons.automirrored.rounded.TrendingFlat
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.domain.market.MarketSentimentRegime
import com.example.domain.market.MarketSentimentSnapshot
import com.example.ui.theme.LocalTradingColors
import com.example.ui.theme.MonoPriceMedium
import com.example.ui.theme.MonoPriceSmall
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Real-time Market Sentiment bar docked directly into the top dashboard bar.
 * Displays live Bullish/Bearish score, market-cap-weighted live P/E ratio,
 * and live volume thrust (Up-Volume % & relative volume) with 1-tap breakdown modal.
 */
@Composable
fun MarketSentimentDashboardBar(
    sentiment: MarketSentimentSnapshot,
    isRefreshing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tradingColors = LocalTradingColors.current
    val targetAccent = when {
        sentiment.regime.isBullish -> tradingColors.bullish
        sentiment.regime.isBearish -> tradingColors.bearish
        else -> tradingColors.amber
    }
    val targetContainer = when {
        sentiment.regime.isBullish -> tradingColors.bullishContainer
        sentiment.regime.isBearish -> tradingColors.bearishContainer
        else -> tradingColors.amberContainer
    }
    val accentColor by animateColorAsState(targetValue = targetAccent, label = "sentiment_accent")
    val containerColor by animateColorAsState(targetValue = targetContainer, label = "sentiment_container")

    val pulseTransition = rememberInfiniteTransition(label = "sentiment_live_pulse")
    val dotAlpha by pulseTransition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "sentiment_dot_alpha"
    )

    val trendIcon = when {
        sentiment.regime.isBullish -> Icons.AutoMirrored.Rounded.TrendingUp
        sentiment.regime.isBearish -> Icons.AutoMirrored.Rounded.TrendingDown
        else -> Icons.AutoMirrored.Rounded.TrendingFlat
    }

    val peText = if (sentiment.weightedPeRatio > 0.0) {
        String.format(Locale.US, "P/E %.1fx", sentiment.weightedPeRatio)
    } else {
        "P/E --"
    }
    val volText = String.format(
        Locale.US,
        "Vol %.2fx • %d%% Up",
        sentiment.avgRelativeVolume,
        sentiment.upVolumeRatioPercent
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = "Market Sentiment ${sentiment.regime.label}, score ${sentiment.score} out of 100, $peText, $volText. Tap for breakdown."
            }
            .testTag("topbar_market_sentiment_pill"),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Left: Live Sentiment Pill (Regime + Score)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = containerColor,
                        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.45f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(accentColor.copy(alpha = if (sentiment.isLive) dotAlpha else 0.7f))
                            )
                            Icon(
                                imageVector = trendIcon,
                                contentDescription = null,
                                tint = accentColor,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = "${sentiment.regime.shortBadge} ${sentiment.score}",
                                style = MonoPriceSmall,
                                fontWeight = FontWeight.ExtraBold,
                                color = accentColor,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }

                    // Compact mini score bar
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = sentiment.regime.label.uppercase(Locale.US),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = accentColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${sentiment.advancingCount}▲ ${sentiment.decliningCount}▼",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }

                // Right: Real-Time P/E & Volume Metrics + Drill-down chevron
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = peText,
                            style = MonoPriceSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            softWrap = false
                        )
                        Text(
                            text = volText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                    if (isRefreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.ChevronRight,
                            contentDescription = "View Market Sentiment Breakdown",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
            HorizontalDivider(thickness = 0.5.dp, color = tradingColors.border)
        }
    }
}

@Composable
fun MarketSentimentDetailDialog(
    sentiment: MarketSentimentSnapshot,
    isRefreshing: Boolean,
    lastSyncLabel: String,
    onRefresh: () -> Unit,
    onOpenScreener: () -> Unit,
    onDismiss: () -> Unit
) {
    val tradingColors = LocalTradingColors.current
    val accentColor = when {
        sentiment.regime.isBullish -> tradingColors.bullish
        sentiment.regime.isBearish -> tradingColors.bearish
        else -> tradingColors.amber
    }
    val containerColor = when {
        sentiment.regime.isBullish -> tradingColors.bullishContainer
        sentiment.regime.isBearish -> tradingColors.bearishContainer
        else -> tradingColors.amberContainer
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .testTag("market_sentiment_detail_dialog"),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, tradingColors.border),
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Speed,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(22.dp)
                        )
                        Column {
                            Text(
                                text = "Live Market Sentiment",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${sentiment.sampleCount} NSE Leaders • Real-Time P/E & Volume",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = onRefresh,
                            enabled = !isRefreshing,
                            modifier = Modifier.testTag("sentiment_dialog_refresh")
                        ) {
                            if (isRefreshing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.Refresh,
                                    contentDescription = "Refresh Live Market Sentiment"
                                )
                            }
                        }
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.testTag("sentiment_dialog_close")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Close Market Sentiment Dialog"
                            )
                        }
                    }
                }

                // Semicircular Gauge + Overall Score
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, tradingColors.border)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SentimentArcGauge(
                            score = sentiment.score,
                            bearishColor = tradingColors.bearish,
                            neutralColor = tradingColors.amber,
                            bullishColor = tradingColors.bullish,
                            needleColor = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(126.dp)
                        )

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "${sentiment.score} / 100",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.ExtraBold,
                                color = accentColor
                            )
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = containerColor,
                                border = BorderStroke(1.dp, accentColor.copy(alpha = 0.4f))
                            ) {
                                Text(
                                    text = sentiment.regime.label.uppercase(Locale.US),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = accentColor,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        }

                        Text(
                            text = sentiment.regime.summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Component 1: Real-Time P/E Ratio (35% weight)
                SentimentFactorCard(
                    title = "REAL-TIME MARKET P/E RATIO (35% WEIGHT)",
                    badgeText = sentiment.peValuationLabel,
                    score = sentiment.peComponentScore,
                    scoreColor = scoreColor(sentiment.peComponentScore),
                    primaryMetricLabel = "Mkt-Cap Weighted P/E",
                    primaryMetricValue = String.format(Locale.US, "%.2fx", sentiment.weightedPeRatio),
                    secondaryMetricLabel = "Median Stock P/E",
                    secondaryMetricValue = String.format(Locale.US, "%.2fx", sentiment.medianPeRatio)
                )

                // Component 2: Real-Time Volume Thrust (45% weight)
                SentimentFactorCard(
                    title = "REAL-TIME VOLUME FLOW (45% WEIGHT)",
                    badgeText = "${sentiment.upVolumeRatioPercent}% Up-Volume",
                    score = sentiment.volumeComponentScore,
                    scoreColor = scoreColor(sentiment.volumeComponentScore),
                    primaryMetricLabel = "Avg Relative Volume",
                    primaryMetricValue = String.format(Locale.US, "%.2fx 20D Avg", sentiment.avgRelativeVolume),
                    secondaryMetricLabel = "Session Volume",
                    secondaryMetricValue = sentiment.totalVolumeFormatted
                )

                // Component 3: Market Breadth (20% weight)
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, tradingColors.border)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.BarChart,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "MARKET BREADTH (20% WEIGHT)",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = "${sentiment.advancingCount} Adv / ${sentiment.decliningCount} Dec",
                                style = MonoPriceSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        val total = (sentiment.advancingCount + sentiment.decliningCount).coerceAtLeast(1)
                        val advFraction = sentiment.advancingCount.toFloat() / total.toFloat()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(999.dp))
                        ) {
                            if (advFraction > 0f) {
                                Box(
                                    modifier = Modifier
                                        .weight(advFraction.coerceAtLeast(0.01f))
                                        .height(8.dp)
                                        .background(tradingColors.bullish)
                                )
                            }
                            if (1f - advFraction > 0f) {
                                Box(
                                    modifier = Modifier
                                        .weight((1f - advFraction).coerceAtLeast(0.01f))
                                        .height(8.dp)
                                        .background(tradingColors.bearish)
                                )
                            }
                        }

                        Text(
                            text = lastSyncLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Footer CTA
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Close")
                    }
                    Button(
                        onClick = {
                            onDismiss()
                            onOpenScreener()
                        },
                        modifier = Modifier.testTag("sentiment_open_screener_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.FilterList,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Open Stock Screener")
                    }
                }
            }
        }
    }
}

@Composable
private fun SentimentFactorCard(
    title: String,
    badgeText: String,
    score: Int,
    scoreColor: Color,
    primaryMetricLabel: String,
    primaryMetricValue: String,
    secondaryMetricLabel: String,
    secondaryMetricValue: String
) {
    val tradingColors = LocalTradingColors.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, tradingColors.border)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = scoreColor.copy(alpha = 0.14f)
                ) {
                    Text(
                        text = "$badgeText • $score/100",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = scoreColor,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                    )
                }
            }

            LinearProgressIndicator(
                progress = { (score / 100f).coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(999.dp)),
                color = scoreColor,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = primaryMetricLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = primaryMetricValue,
                        style = MonoPriceMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = secondaryMetricLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = secondaryMetricValue,
                        style = MonoPriceMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun SentimentArcGauge(
    score: Int,
    bearishColor: Color,
    neutralColor: Color,
    bullishColor: Color,
    needleColor: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val strokeWidth = 18.dp.toPx()
        val diameter = minOf(size.width * 0.72f, (size.height - strokeWidth) * 1.9f)
        val radius = diameter / 2f
        val centerX = size.width / 2f
        val centerY = size.height - 14.dp.toPx()
        val topLeft = Offset(centerX - radius, centerY - radius)
        val arcSize = Size(diameter, diameter)

        // Bearish segment (0..40 -> 180°..252°)
        drawArc(
            color = bearishColor.copy(alpha = 0.85f),
            startAngle = 180f,
            sweepAngle = 70f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        )
        // Neutral segment (40..60 -> 252°..288°)
        drawArc(
            color = neutralColor.copy(alpha = 0.85f),
            startAngle = 254f,
            sweepAngle = 32f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Butt)
        )
        // Bullish segment (60..100 -> 288°..360°)
        drawArc(
            color = bullishColor.copy(alpha = 0.9f),
            startAngle = 290f,
            sweepAngle = 70f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        )

        // Needle pointing to score (0 = 180 deg, 100 = 360 deg)
        val clamped = score.coerceIn(0, 100) / 100.0
        val angleRad = PI + clamped * PI
        val needleLen = radius - strokeWidth * 0.75f
        val endX = centerX + (cos(angleRad) * needleLen).toFloat()
        val endY = centerY + (sin(angleRad) * needleLen).toFloat()

        drawLine(
            color = needleColor,
            start = Offset(centerX, centerY),
            end = Offset(endX, endY),
            strokeWidth = 3.5.dp.toPx(),
            cap = StrokeCap.Round
        )
        drawCircle(
            color = needleColor,
            radius = 6.dp.toPx(),
            center = Offset(centerX, centerY)
        )
    }
}

@Composable
private fun scoreColor(score: Int): Color {
    val tradingColors = LocalTradingColors.current
    return when {
        score >= 56 -> tradingColors.bullish
        score <= 44 -> tradingColors.bearish
        else -> tradingColors.amber
    }
}
