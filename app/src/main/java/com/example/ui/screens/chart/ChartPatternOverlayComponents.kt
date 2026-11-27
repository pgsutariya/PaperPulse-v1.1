package com.example.ui.screens.chart

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.TrendingDown
import androidx.compose.material.icons.rounded.TrendingUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.domain.analysis.ChartAnalysis
import com.example.domain.analysis.DetectedChartPattern
import com.example.domain.analysis.PatternBreakoutStatus
import com.example.domain.signal.AiSignalSecondOpinion
import com.example.domain.signal.AiSignalVerdict
import com.example.domain.signal.AiUiState
import com.example.ui.theme.LocalTradingColors
import com.example.ui.theme.MonoPriceSmall
import java.util.Locale
import kotlin.math.abs

private fun fmtPatPrice(v: Double): String = when {
    abs(v) >= 1000 -> String.format(Locale.US, "%,.2f", v)
    abs(v) >= 1 -> String.format(Locale.US, "%.2f", v)
    else -> String.format(Locale.US, "%.4f", v)
}

/**
 * Interactive floating pill strip overlaid inside the chart panel so users can tap any
 * detected Flag, Wedge, Double Bottom/Top, or Triangle to open its pop-up breakout tooltip
 * in both Native Canvas and TradingView Lightweight Charts modes.
 */
@Composable
fun ChartPatternFloatingStrip(
    patterns: List<DetectedChartPattern>,
    selectedPatternId: String?,
    onSelectPattern: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (patterns.isEmpty()) return
    val colors = LocalTradingColors.current

    Row(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .testTag("chart_pattern_chip_strip"),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        patterns.forEach { pat ->
            val isSelected = pat.id == selectedPatternId
            val accent = if (pat.isBullish) colors.bullish else colors.bearish
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (isSelected) accent.copy(alpha = 0.28f) else MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(
                    width = if (isSelected) 1.5.dp else 1.dp,
                    color = if (isSelected) accent else accent.copy(alpha = 0.55f)
                ),
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onSelectPattern(pat.id) }
                    .testTag("chart_pattern_pill_${pat.type.name.lowercase(Locale.US)}")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = if (pat.isBullish) Icons.Rounded.TrendingUp else Icons.Rounded.TrendingDown,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "${pat.type.shortBadge} ${pat.confidenceScore}%",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1
                    )
                    Text(
                        text = "BO ${fmtPatPrice(pat.breakoutLevel)}",
                        style = MonoPriceSmall,
                        color = accent,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

/**
 * Pop-up breakout tooltip anchored inside the chart panel when a user taps a detected pattern
 * on the chart canvas or pattern pill strip. Displays breakout/target/invalidation geometry,
 * live Firebase AI Logic verification, and one-tap order ticket prefill.
 */
@Composable
fun ChartPatternBreakoutTooltip(
    pattern: DetectedChartPattern?,
    aiState: AiUiState<AiSignalSecondOpinion>,
    onVerifyWithAi: (DetectedChartPattern) -> Unit,
    onTradeBreakout: (DetectedChartPattern) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = pattern != null,
        enter = fadeIn() + scaleIn(initialScale = 0.95f),
        exit = fadeOut() + scaleOut(targetScale = 0.95f),
        modifier = modifier
    ) {
        if (pattern != null) {
            val colors = LocalTradingColors.current
            val dirColor = if (pattern.isBullish) colors.bullish else colors.bearish
            val dirBg = if (pattern.isBullish) colors.bullishContainer else colors.bearishContainer
            val statusColor = when (pattern.status) {
                PatternBreakoutStatus.BREAKOUT_CONFIRMED -> dirColor
                PatternBreakoutStatus.IMMINENT_BREAKOUT -> colors.amber
                PatternBreakoutStatus.FORMING -> MaterialTheme.colorScheme.onSurfaceVariant
            }

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(1.2.dp, dirColor.copy(alpha = 0.7f)),
                shadowElevation = 10.dp,
                modifier = Modifier
                    .widthIn(max = 360.dp)
                    .fillMaxWidth()
                    .testTag("chart_pattern_breakout_tooltip")
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Header row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Surface(shape = RoundedCornerShape(6.dp), color = dirBg) {
                                Text(
                                    text = if (pattern.isBullish) "BULL" else "BEAR",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = dirColor,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = pattern.type.displayName,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${pattern.status.label} • ${pattern.confidenceScore}% Match • ${pattern.type.categoryLabel}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = statusColor,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .size(32.dp)
                                .testTag("chart_pattern_tooltip_close")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Close pattern tooltip",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    // Key Breakout Levels Grid
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TooltipMetric("BREAKOUT", fmtPatPrice(pattern.breakoutLevel), colors.amber)
                        TooltipMetric(
                            "TARGET (${String.format(Locale.US, "%.1f", pattern.projectedMovePercent)}%)",
                            fmtPatPrice(pattern.targetPrice),
                            dirColor
                        )
                        TooltipMetric("INVALIDATION", fmtPatPrice(pattern.invalidationPrice), colors.bearish)
                        TooltipMetric(
                            "EST R:R",
                            "1:${String.format(Locale.US, "%.1f", pattern.riskRewardRatio)}",
                            MaterialTheme.colorScheme.primary
                        )
                    }

                    Text(
                        text = pattern.breakoutNote,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Live Firebase AI Verification Output (if requested)
                    when (aiState) {
                        is AiUiState.Loading -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                Text(
                                    text = "AI validating breakout & false-break risk…",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        is AiUiState.Success -> {
                            val op = aiState.data
                            val verdictColor = when (op.verdict) {
                                AiSignalVerdict.ALIGNED -> colors.bullish
                                AiSignalVerdict.CAUTION -> colors.amber
                                AiSignalVerdict.CONFLICTING -> colors.bearish
                            }
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                border = BorderStroke(1.dp, verdictColor.copy(alpha = 0.45f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("chart_pattern_ai_verdict")
                            ) {
                                Column(
                                    modifier = Modifier.padding(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "AI Verdict: ${op.verdict.label} (${op.confidenceScore}%)",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = verdictColor
                                        )
                                        Text(
                                            text = op.bias.name,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Text(
                                        text = op.summary,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "Trigger / Risk: ${op.executionGuidance}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                        is AiUiState.Error -> {
                            Text(
                                text = aiState.message,
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.bearish
                            )
                        }
                        AiUiState.Idle, is AiUiState.Streaming -> Unit
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    // Action Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { onVerifyWithAi(pattern) },
                            enabled = aiState !is AiUiState.Loading,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("chart_pattern_verify_ai_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.AutoAwesome,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("AI Verify", style = MaterialTheme.typography.labelSmall)
                        }
                        Button(
                            onClick = { onTradeBreakout(pattern) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = dirColor,
                                contentColor = Color.White
                            ),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("chart_pattern_trade_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Bolt,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Trade Breakout", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TooltipMetric(label: String, value: String, valueColor: Color) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MonoPriceSmall,
            fontWeight = FontWeight.Bold,
            color = valueColor
        )
    }
}

/**
 * Analytical card beneath the chart listing all auto-detected chart patterns
 * (Flags, Wedges, Double Bottoms/Tops, Triangles) with one-tap pop-up tooltip focus.
 */
@Composable
fun AiChartPatternsPanel(
    analysis: ChartAnalysis,
    selectedPatternId: String?,
    onSelectPattern: (String) -> Unit,
    onTradeBreakout: (DetectedChartPattern) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalTradingColors.current
    val patterns = analysis.chartPatterns

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, colors.border),
        modifier = modifier
            .fillMaxWidth()
            .testTag("chart_ai_patterns_panel")
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
                        imageVector = Icons.Rounded.Insights,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "AI CHART PATTERN RECOGNITION (${patterns.size})",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    text = "Tap pattern for breakout tooltip",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (patterns.isEmpty()) {
                Text(
                    text = "Scanning ${analysis.timeframe.label} candles for Bull/Bear Flags, Falling/Rising Wedges, Double Bottoms/Tops, and Triangles. No high-confidence pattern active right now.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                patterns.forEach { pat ->
                    val isSelected = pat.id == selectedPatternId
                    val accent = if (pat.isBullish) colors.bullish else colors.bearish
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isSelected) accent.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surface,
                        border = BorderStroke(
                            width = if (isSelected) 1.5.dp else 1.dp,
                            color = if (isSelected) accent else colors.border
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onSelectPattern(pat.id) }
                            .testTag("chart_pattern_row_${pat.type.name.lowercase(Locale.US)}")
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
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
                                    Text(
                                        text = pat.type.displayName,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = if (pat.isBullish) colors.bullishContainer else colors.bearishContainer
                                    ) {
                                        Text(
                                            text = "${pat.confidenceScore}% • ${pat.status.label}",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = accent,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                OutlinedButton(
                                    onClick = { onTradeBreakout(pat) },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.height(30.dp)
                                ) {
                                    Text("Prefill", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "BO: ${fmtPatPrice(pat.breakoutLevel)}",
                                    style = MonoPriceSmall,
                                    color = colors.amber,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Target: ${fmtPatPrice(pat.targetPrice)}",
                                    style = MonoPriceSmall,
                                    color = accent,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "SL: ${fmtPatPrice(pat.invalidationPrice)}",
                                    style = MonoPriceSmall,
                                    color = colors.bearish
                                )
                                Text(
                                    text = "1:${String.format(Locale.US, "%.1f", pat.riskRewardRatio)}",
                                    style = MonoPriceSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Text(
                                text = pat.summary,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
