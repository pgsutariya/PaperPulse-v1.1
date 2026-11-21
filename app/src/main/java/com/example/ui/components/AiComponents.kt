package com.example.ui.components

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.domain.signal.AiSignalBias
import com.example.domain.signal.AiSignalSecondOpinion
import com.example.domain.signal.AiSignalVerdict
import com.example.domain.signal.AiUiState
import com.example.ui.theme.LocalTradingColors
import com.example.ui.theme.MonoPriceSmall

@Composable
fun AiSecondOpinionCard(
    title: String,
    subtitle: String,
    ctaLabel: String,
    aiState: AiUiState<AiSignalSecondOpinion>,
    onAnalyze: () -> Unit,
    ctaTestTag: String,
    modifier: Modifier = Modifier
) {
    val tradingColors = LocalTradingColors.current

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
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
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Column {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (aiState is AiUiState.Success) {
                    IconButton(onClick = onAnalyze, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = "Re-run AI Analysis",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            when (aiState) {
                AiUiState.Idle -> {
                    OutlinedButton(
                        onClick = onAnalyze,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(ctaTestTag),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(ctaLabel, fontWeight = FontWeight.SemiBold)
                    }
                }

                AiUiState.Loading -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Text(
                            text = "Evaluating candles, R:R & invalidation levels...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                is AiUiState.Streaming -> {
                    Text(
                        text = aiState.partialText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                is AiUiState.Error -> {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = tradingColors.bearishContainer
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.ErrorOutline,
                                    contentDescription = "AI Error",
                                    tint = tradingColors.bearish,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = aiState.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = tradingColors.bearish
                                )
                            }
                            OutlinedButton(
                                onClick = onAnalyze,
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                Text("Retry Analysis", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }

                is AiUiState.Success -> {
                    val data = aiState.data
                    val (verdictBg, verdictFg) = when (data.verdict) {
                        AiSignalVerdict.ALIGNED -> tradingColors.bullishContainer to tradingColors.bullish
                        AiSignalVerdict.CAUTION -> tradingColors.amberContainer to tradingColors.amber
                        AiSignalVerdict.CONFLICTING -> tradingColors.bearishContainer to tradingColors.bearish
                    }
                    val biasColor = when (data.bias) {
                        AiSignalBias.BULLISH -> tradingColors.bullish
                        AiSignalBias.BEARISH -> tradingColors.bearish
                        AiSignalBias.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(shape = RoundedCornerShape(6.dp), color = verdictBg) {
                            Text(
                                text = data.verdict.label.uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                color = verdictFg,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh
                        ) {
                            Text(
                                text = "${data.bias.label} Bias",
                                style = MaterialTheme.typography.labelSmall,
                                color = biasColor,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = "Score ${data.confidenceScore}/100",
                            style = MonoPriceSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Text(
                        text = data.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surface
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "INVALIDATION RISK",
                                style = MaterialTheme.typography.labelSmall,
                                color = tradingColors.bearish,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = data.keyRiskInvalidation,
                                style = MaterialTheme.typography.bodySmall
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "RISK & EXECUTION GUIDANCE",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = data.executionGuidance,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    if (data.riskChecklist.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            data.riskChecklist.take(3).forEach { checkItem ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.CheckCircle,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Text(
                                        text = checkItem,
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
    }
}

@Composable
fun AiPortfolioCoachCard(
    coachState: AiUiState<String>,
    onAskQuestion: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val tradingColors = LocalTradingColors.current
    var promptText by remember { mutableStateOf("") }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Column {
                    Text(
                        text = "AI Trading Coach (Advisory)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Audits open positions, TSL/OCO discipline & closed journal P&L",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    "Audit Open Risk" to "Audit my current open positions, stop-losses, trailing targets, and intraday 15:15 IST exposure.",
                    "Review Closed Trades" to "Review my closed trade journal P&L, brokerage drag, exit reasons, and recurring mistakes.",
                    "Top Signal Focus" to "Which active signals align best with prudent risk-reward right now and why?"
                ).forEach { (chipLabel, fullPrompt) ->
                    AssistChip(
                        onClick = { onAskQuestion(fullPrompt) },
                        label = { Text(chipLabel, style = MaterialTheme.typography.labelMedium) }
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = promptText,
                    onValueChange = { promptText = it },
                    placeholder = { Text("Ask AI Coach about your trades or risk...") },
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("ai_coach_prompt_input")
                )
                Button(
                    onClick = {
                        val query = promptText.trim()
                        if (query.isNotEmpty()) {
                            onAskQuestion(query)
                            promptText = ""
                        }
                    },
                    enabled = promptText.isNotBlank() && coachState !is AiUiState.Loading,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
                    modifier = Modifier.testTag("ai_coach_ask_button")
                ) {
                    Icon(Icons.Rounded.Send, contentDescription = "Ask AI Coach", modifier = Modifier.size(18.dp))
                }
            }

            when (coachState) {
                AiUiState.Idle -> Unit
                AiUiState.Loading -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(
                            text = "Analyzing portfolio, positions & journal...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                is AiUiState.Streaming -> {
                    HorizontalDivider(color = tradingColors.border)
                    Text(
                        text = coachState.partialText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                is AiUiState.Success -> {
                    HorizontalDivider(color = tradingColors.border)
                    Text(
                        text = coachState.data,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                is AiUiState.Error -> {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = tradingColors.bearishContainer
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ErrorOutline,
                                contentDescription = "Coach Error",
                                tint = tradingColors.bearish,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = coachState.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = tradingColors.bearish
                            )
                        }
                    }
                }
            }
        }
    }
}
