package com.example.ui.screens.coach

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.GridOn
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.ShowChart
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.domain.ai.AiStrategyCoachPersona
import com.example.domain.ai.StrategyCoachMessage
import com.example.ui.components.guardGlobalHotkeysOnFocus
import com.example.ui.theme.LocalTradingColors
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiStrategyCoachesScreen(
    onBack: () -> Unit,
    onOpenAiSettings: () -> Unit,
    viewModel: AiStrategyCoachesViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val tradingColors = LocalTradingColors.current
    val listState = rememberLazyListState()

    LaunchedEffect(state.messages.size, state.messages.lastOrNull()?.text?.length) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "AI Strategy Coaches",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${state.connectionStatus.provider.shortLabel} • ${state.connectionStatus.activeModel}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("strategy_coaches_back")
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (state.messages.isNotEmpty()) {
                        IconButton(
                            onClick = viewModel::clearConversation,
                            modifier = Modifier.testTag("strategy_coaches_clear")
                        ) {
                            Icon(Icons.Rounded.DeleteOutline, contentDescription = "Clear Coaching Chat")
                        }
                    }
                    IconButton(
                        onClick = onOpenAiSettings,
                        modifier = Modifier.testTag("strategy_coaches_settings")
                    ) {
                        Icon(Icons.Rounded.Tune, contentDescription = "AI Provider Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .testTag("ai_strategy_coaches_screen")
        ) {
            // Horizontal Specialist Coach Selector Cards
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AiStrategyCoachPersona.entries.forEach { persona ->
                    val selected = state.selectedPersona == persona
                    CoachPersonaCard(
                        persona = persona,
                        selected = selected,
                        onClick = { viewModel.selectPersona(persona) }
                    )
                }
            }

            // Live Grounded Telemetry Strip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ContextPill("Positions: ${state.openPositionsCount}")
                ContextPill("Signals: ${state.activeSignalsCount}")
                ContextPill("Journal: ${state.closedTradesCount}")
                if (state.globalTotalCount > 0) {
                    ContextPill("Global: ${state.globalAdvancingCount}/${state.globalTotalCount} Up")
                }
                state.fiiNetCr?.let { fii ->
                    val sign = if (fii >= 0) "+" else ""
                    ContextPill("FII: $sign₹${String.format(Locale.US, "%.0f", fii)} Cr")
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(top = 6.dp),
                color = tradingColors.border
            )

            // Main Coaching Stream / Welcome Prompts
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag("strategy_coaches_message_list"),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item(key = "coach_intro_${state.selectedPersona.name}") {
                    ActiveCoachBanner(
                        persona = state.selectedPersona,
                        onPickPrompt = { viewModel.sendQuestion(it) },
                        enabled = !state.isGenerating
                    )
                }

                items(state.messages, key = { it.id }) { msg ->
                    CoachMessageBubble(
                        message = msg,
                        onConfigureAi = onOpenAiSettings
                    )
                }
            }

            // Bottom Input Composer
            Surface(
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, tradingColors.border),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = state.inputDraft,
                        onValueChange = viewModel::updateDraft,
                        placeholder = {
                            Text(
                                "Ask ${state.selectedPersona.shortTitle} Coach...",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        maxLines = 3,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .guardGlobalHotkeysOnFocus()
                            .testTag("strategy_coach_input")
                    )
                    FilledIconButton(
                        onClick = { viewModel.sendQuestion() },
                        enabled = state.inputDraft.isNotBlank() && !state.isGenerating,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("strategy_coach_send")
                    ) {
                        if (state.isGenerating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Send to AI Coach")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CoachPersonaCard(
    persona: AiStrategyCoachPersona,
    selected: Boolean,
    onClick: () -> Unit
) {
    val tradingColors = LocalTradingColors.current
    val accent = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant

    Surface(
        modifier = Modifier
            .width(156.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .testTag("coach_persona_${persona.name.lowercase()}"),
        shape = RoundedCornerShape(14.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = if (selected) 1.5.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else tradingColors.border
        )
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = personaIcon(persona),
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(18.dp)
                )
                Surface(
                    shape = RoundedCornerShape(5.dp),
                    color = accent.copy(alpha = 0.14f)
                ) {
                    Text(
                        text = persona.specialtyBadge,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = accent,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                        maxLines = 1
                    )
                }
            }
            Text(
                text = persona.title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = persona.subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ActiveCoachBanner(
    persona: AiStrategyCoachPersona,
    onPickPrompt: (String) -> Unit,
    enabled: Boolean
) {
    val tradingColors = LocalTradingColors.current
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, tradingColors.border),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
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
                Text(
                    text = "${persona.title} Ready",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = persona.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                persona.focusAreas.forEach { tag ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
                    ) {
                        Text(
                            text = tag,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }
            Text(
                text = "QUICK COACHING PLAYBOOKS",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                persona.quickPrompts.forEachIndexed { idx, prompt ->
                    AssistChip(
                        onClick = { onPickPrompt(prompt) },
                        enabled = enabled,
                        label = {
                            Text(
                                text = prompt,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("coach_quick_prompt_${persona.name.lowercase()}_$idx")
                    )
                }
            }
        }
    }
}

@Composable
private fun CoachMessageBubble(
    message: StrategyCoachMessage,
    onConfigureAi: () -> Unit
) {
    val tradingColors = LocalTradingColors.current
    val align = if (message.isUser) Alignment.CenterEnd else Alignment.CenterStart
    val containerColor = when {
        message.isUser -> MaterialTheme.colorScheme.primaryContainer
        message.isError -> tradingColors.bearishContainer
        else -> MaterialTheme.colorScheme.surface
    }
    val borderColor = when {
        message.isUser -> MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
        message.isError -> tradingColors.bearish.copy(alpha = 0.5f)
        else -> tradingColors.border
    }

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = align
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = containerColor,
            border = BorderStroke(1.dp, borderColor),
            modifier = Modifier
                .fillMaxWidth(if (message.isUser) 0.88f else 0.98f)
                .testTag("coach_message_${if (message.isUser) "user" else "ai"}")
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = if (message.isUser) "You" else message.persona.title,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (message.isError) tradingColors.bearish else MaterialTheme.colorScheme.primary
                    )
                    if (message.isStreaming) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 1.8.dp
                        )
                    }
                }

                if (message.isStreaming && message.text.isBlank()) {
                    Text(
                        text = "Analyzing your portfolio, live signals & institutional flows...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = message.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (message.isError) tradingColors.bearish else MaterialTheme.colorScheme.onSurface
                    )
                }

                if (message.isError) {
                    OutlinedButton(
                        onClick = onConfigureAi,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.testTag("coach_error_configure_ai")
                    ) {
                        Text("Configure AI Provider", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun ContextPill(label: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

private fun personaIcon(persona: AiStrategyCoachPersona): ImageVector = when (persona) {
    AiStrategyCoachPersona.INTRADAY_SCALPER -> Icons.Rounded.Bolt
    AiStrategyCoachPersona.SWING_BREAKOUT -> Icons.Rounded.ShowChart
    AiStrategyCoachPersona.OPTIONS_HEDGER -> Icons.Rounded.GridOn
    AiStrategyCoachPersona.VALUE_FUNDAMENTAL -> Icons.Rounded.Insights
    AiStrategyCoachPersona.RISK_PSYCHOLOGY -> Icons.Rounded.Shield
}
