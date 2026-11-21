package com.example.ui.screens.journal

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Book
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.domain.engine.MarketClock
import com.example.domain.engine.RiskMath
import com.example.domain.model.TradeJournalEntry
import com.example.ui.components.AiPortfolioCoachCard
import com.example.ui.components.EmptyStatePanel
import com.example.ui.components.SegmentBadge
import com.example.ui.theme.LocalTradingColors
import com.example.ui.theme.MonoPriceMedium
import com.example.ui.theme.MonoPriceSmall
import java.math.BigDecimal

@Composable
fun JournalScreen(
    viewModel: JournalViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val tradingColors = LocalTradingColors.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(8.dp))

        // Summary Analytics Card
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, tradingColors.border)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("NET REALISED P&L", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val netColor = if (state.netRealisedPnl >= BigDecimal.ZERO) tradingColors.bullish else tradingColors.bearish
                        Text(RiskMath.formatPortfolioInr(state.netRealisedPnl, signed = true), style = MonoPriceMedium, color = netColor)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("LIVE UNREALISED", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val unColor = if (state.unrealisedPnl >= BigDecimal.ZERO) tradingColors.bullish else tradingColors.bearish
                        Text(RiskMath.formatPortfolioInr(state.unrealisedPnl, signed = true), style = MonoPriceMedium, color = unColor)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Gross: ${RiskMath.formatPortfolioInr(state.grossPnl, signed = true)}",
                        style = MonoPriceSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Charges: ${RiskMath.formatPortfolioInr(state.totalCharges)}",
                        style = MonoPriceSmall,
                        color = tradingColors.amber
                    )
                    Text(
                        text = "Win Rate: ${state.winRatePercent}% (${state.entries.size})",
                        style = MonoPriceSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(key = "ai_coach_card") {
                AiPortfolioCoachCard(
                    coachState = state.coachState,
                    onAskQuestion = viewModel::askAiCoach
                )
            }

            if (state.entries.isEmpty()) {
                item(key = "journal_empty_panel") {
                    EmptyStatePanel(
                        icon = Icons.Rounded.Book,
                        title = "No closed trades yet",
                        subtitle = "Closed positions appear here with simulated brokerage & taxes, net P&L, exit trigger reason, and trade notes."
                    )
                }
            } else {
                items(state.entries, key = { it.id }) { entry ->
                    JournalEntryCard(
                        entry = entry,
                        onSaveNotes = { notes -> viewModel.saveNotes(entry.id, notes) }
                    )
                }
            }
        }
    }
}

@Composable
private fun JournalEntryCard(
    entry: TradeJournalEntry,
    onSaveNotes: (String) -> Unit
) {
    val tradingColors = LocalTradingColors.current
    val isWin = entry.netPnl >= BigDecimal.ZERO
    val netColor = if (isWin) tradingColors.bullish else tradingColors.bearish
    var editingNotes by remember { mutableStateOf(false) }
    var notesDraft by remember(entry.notes) { mutableStateOf(entry.notes) }

    Surface(
        modifier = Modifier.fillMaxWidth().testTag("journal_entry_${entry.symbol}"),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, tradingColors.border)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    SegmentBadge(entry.segment)
                    Text(entry.symbol, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("${entry.side.name} ${entry.quantity}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    text = RiskMath.formatSignedCurrency(entry.netPnl, entry.segment),
                    style = MonoPriceMedium,
                    color = netColor
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Entry ${RiskMath.formatPrice(entry.entryPrice, entry.segment)} -> Exit ${RiskMath.formatPrice(entry.exitPrice, entry.segment)}",
                    style = MonoPriceSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = entry.exitReason.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Gross ${RiskMath.formatSignedCurrency(entry.grossPnl, entry.segment)} • Fees ${RiskMath.formatPrice(entry.charges, entry.segment)}",
                    style = MonoPriceSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "${MarketClock.formatIstDate(entry.closedAtMillis)} ${MarketClock.formatIstTime(entry.closedAtMillis)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (editingNotes) {
                OutlinedTextField(
                    value = notesDraft,
                    onValueChange = { notesDraft = it },
                    label = { Text("Trade Setup / Reflection Notes") },
                    modifier = Modifier.fillMaxWidth().testTag("journal_notes_input_${entry.id}")
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { editingNotes = false }) { Text("Cancel") }
                    Button(
                        onClick = {
                            onSaveNotes(notesDraft)
                            editingNotes = false
                        },
                        modifier = Modifier.testTag("save_journal_notes_${entry.id}")
                    ) {
                        Text("Save Note")
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = entry.notes.ifBlank { "No notes recorded." },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (entry.notes.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { editingNotes = true }) {
                        Text(if (entry.notes.isBlank()) "Add Note" else "Edit Note")
                    }
                }
            }
        }
    }
}
