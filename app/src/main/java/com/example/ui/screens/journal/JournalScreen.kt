package com.example.ui.screens.journal

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.Assessment
import androidx.compose.material.icons.rounded.Book
import androidx.compose.material.icons.rounded.LocalOffer
import androidx.compose.material.icons.rounded.Newspaper
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.domain.engine.MarketClock
import com.example.domain.engine.RiskMath
import com.example.domain.model.CapitalTransaction
import com.example.domain.model.CapitalTransactionType
import com.example.domain.model.TradeJournalEntry
import com.example.domain.signal.InstrumentTradePerformance
import com.example.ui.components.AddTradeTagButton
import com.example.ui.components.AiPortfolioCoachCard
import com.example.ui.components.DailyPnlBarChartCard
import com.example.ui.components.EmptyStatePanel
import com.example.ui.components.MarketNewsArticleCard
import com.example.ui.components.MarketNewsHeaderStrip
import com.example.ui.components.PnlCalendarHeatmapCard
import com.example.ui.components.RiskManagementScorecardCard
import com.example.ui.components.SearchGroundingBriefingCard
import com.example.ui.components.SegmentBadge
import com.example.ui.components.TradeCorrelationMatrixCard
import com.example.ui.components.TradeSummaryReportCard
import com.example.ui.components.TradeTagEditorDialog
import com.example.ui.components.TradeTagFilterBar
import com.example.ui.components.TradeTagPill
import com.example.ui.components.TradingIntensityHeatmapCard
import com.example.ui.components.WatchlistSymbolNewsFilterBar
import com.example.ui.theme.LocalTradingColors
import com.example.ui.theme.MonoPriceMedium
import com.example.ui.theme.MonoPriceSmall
import java.math.BigDecimal
import java.util.Locale

private enum class JournalTab(val label: String) {
    TRADES("Trades"),
    NEWS("News"),
    BY_INSTRUMENT("Symbols"),
    CAPITAL("Capital")
}

@Composable
fun JournalScreen(
    onOpenFeedSettings: () -> Unit = {},
    onOpenInstrument: (String) -> Unit = {},
    viewModel: JournalViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val tradingColors = LocalTradingColors.current
    var selectedTab by remember { mutableStateOf(JournalTab.TRADES) }
    var tagEditorEntryId by remember { mutableStateOf<String?>(null) }

    val tagEditorEntry = remember(tagEditorEntryId, state.entries) {
        tagEditorEntryId?.let { id -> state.entries.firstOrNull { it.id == id } }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(8.dp))

        // Summary Analytics Card (India ₹ + Crypto $)
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, tradingColors.border)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("INDIA NET REALISED (₹)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val netColor = if (state.netRealisedPnl >= BigDecimal.ZERO) tradingColors.bullish else tradingColors.bearish
                        Text(RiskMath.formatPortfolioInr(state.netRealisedPnl, signed = true), style = MonoPriceMedium, color = netColor)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("INDIA LIVE UNREALISED", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        text = "Win Rate: ${state.winRatePercent}% (${state.entries.size})",
                        style = MonoPriceSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "India Charges: ${RiskMath.formatPortfolioInr(state.totalCharges)}",
                        style = MonoPriceSmall,
                        color = tradingColors.amber
                    )
                    Text(
                        text = "Brok ${RiskMath.formatPortfolioInr(state.totalBrokerage)} • Tax ${RiskMath.formatPortfolioInr(state.totalTaxes)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                HorizontalDivider(color = tradingColors.border)

                // Crypto USD Performance Strip
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val cNetColor = if (state.cryptoNetRealisedPnl >= BigDecimal.ZERO) tradingColors.bullish else tradingColors.bearish
                    val cUnColor = if (state.cryptoUnrealisedPnl >= BigDecimal.ZERO) tradingColors.bullish else tradingColors.bearish
                    Text(
                        text = "CRYPTO ($): Net ${RiskMath.formatPortfolioUsd(state.cryptoNetRealisedPnl, signed = true)}",
                        style = MonoPriceSmall,
                        color = cNetColor
                    )
                    Text(
                        text = "Unreal ${RiskMath.formatPortfolioUsd(state.cryptoUnrealisedPnl, signed = true)} • Fee ${RiskMath.formatPortfolioUsd(state.cryptoTotalCharges)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = cUnColor
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // Closed Trades vs Live News vs By Instrument vs Capital Ledger Tabs
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            JournalTab.entries.forEach { tab ->
                val count = when (tab) {
                    JournalTab.TRADES -> state.entries.size
                    JournalTab.NEWS -> state.filteredNewsArticles.size
                    JournalTab.BY_INSTRUMENT -> state.instrumentPerformance.size
                    JournalTab.CAPITAL -> state.capitalTransactions.size
                }
                FilterChip(
                    selected = selectedTab == tab,
                    onClick = {
                        selectedTab = tab
                        if (tab == JournalTab.NEWS && state.newsFeedState.articles.isEmpty()) {
                            viewModel.refreshNews(force = false)
                        }
                    },
                    label = { Text("${tab.label} ($count)", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier.testTag("journal_tab_${tab.name.lowercase()}"),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.primary
                    )
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            when (selectedTab) {
                JournalTab.TRADES -> {
                    item(key = "trade_tag_filter_bar") {
                        TradeTagFilterBar(
                            totalTradeCount = state.entries.size,
                            tagCounts = state.tagCounts,
                            selectedTag = state.selectedTagFilter,
                            onSelectTag = viewModel::selectTagFilter
                        )
                    }

                    item(key = "daily_pnl_30d_chart") {
                        DailyPnlBarChartCard(
                            indiaDailySeries = state.indiaDailyPnl30d,
                            cryptoDailySeries = state.cryptoDailyPnl30d
                        )
                    }

                    item(key = "pnl_calendar_heatmap_card") {
                        PnlCalendarHeatmapCard(
                            indiaHeatmap = state.indiaCalendarHeatmap,
                            cryptoHeatmap = state.cryptoCalendarHeatmap,
                            canGoNextMonth = state.canGoNextHeatmapMonth,
                            onPreviousMonth = viewModel::previousHeatmapMonth,
                            onNextMonth = viewModel::nextHeatmapMonth
                        )
                    }

                    item(key = "trading_intensity_heatmap_card") {
                        TradingIntensityHeatmapCard(
                            indiaHeatmap = state.indiaIntensityHeatmap,
                            cryptoHeatmap = state.cryptoIntensityHeatmap
                        )
                    }

                    item(key = "risk_management_scorecard_card") {
                        RiskManagementScorecardCard(
                            indiaScorecard = state.indiaRiskScorecard,
                            cryptoScorecard = state.cryptoRiskScorecard
                        )
                    }

                    item(key = "trade_correlation_matrix_card") {
                        TradeCorrelationMatrixCard(
                            indiaMatrix = state.indiaCorrelationMatrix,
                            cryptoMatrix = state.cryptoCorrelationMatrix
                        )
                    }

                    item(key = "trade_summary_report_card") {
                        TradeSummaryReportCard(
                            indiaReport = state.indiaTradeSummary,
                            cryptoReport = state.cryptoTradeSummary
                        )
                    }

                    item(key = "ai_coach_card") {
                        AiPortfolioCoachCard(
                            coachState = state.coachState,
                            onAskQuestion = viewModel::askAiCoach,
                            connectionStatus = state.aiConnectionStatus,
                            onSwitchModel = viewModel::switchAiModel,
                            onOpenAiSettings = onOpenFeedSettings
                        )
                    }

                    if (state.entries.isEmpty()) {
                        item(key = "journal_empty_panel") {
                            EmptyStatePanel(
                                icon = Icons.Rounded.Book,
                                title = "No closed trades yet",
                                subtitle = "Closed positions appear here with custom tags (Breakout, Reversal, News), itemized brokerage & taxes, net P&L, and trade notes."
                            )
                        }
                    } else if (state.filteredEntries.isEmpty() && state.selectedTagFilter != null) {
                        item(key = "journal_tag_filtered_empty") {
                            EmptyStatePanel(
                                icon = Icons.Rounded.LocalOffer,
                                title = "No trades tagged #${state.selectedTagFilter}",
                                subtitle = "Tap + Tag on any closed trade to label it as #${state.selectedTagFilter}, or tap Clear above to view all trades."
                            )
                        }
                    } else {
                        items(state.filteredEntries, key = { it.id }) { entry ->
                            JournalEntryCard(
                                entry = entry,
                                onSaveNotes = { notes -> viewModel.saveNotes(entry.id, notes) },
                                onOpenTagEditor = { tagEditorEntryId = entry.id },
                                onFilterByTag = viewModel::selectTagFilter,
                                onRemoveTag = { tag -> viewModel.removeTagFromEntry(entry.id, entry.tags, tag) }
                            )
                        }
                    }
                }
                JournalTab.NEWS -> {
                    item(key = "journal_news_watchlist_filter") {
                        WatchlistSymbolNewsFilterBar(
                            watchlistSymbols = state.watchlistNewsSymbols,
                            watchlistOnly = state.watchlistNewsOnly,
                            selectedSymbol = state.selectedNewsSymbol,
                            onToggleWatchlistOnly = viewModel::setWatchlistNewsOnly,
                            onSelectSymbol = viewModel::selectNewsSymbol,
                            onSearchGroundSymbol = { sym -> viewModel.refreshNews(focusSymbol = sym, force = true) },
                            isGroundingLoading = state.newsFeedState.isGroundingLoading
                        )
                    }

                    item(key = "journal_news_grounding_brief") {
                        SearchGroundingBriefingCard(
                            feedState = state.newsFeedState,
                            onRetryGrounding = { viewModel.refreshNews(focusSymbol = state.selectedNewsSymbol, force = true) },
                            compact = false
                        )
                    }

                    item(key = "journal_news_header") {
                        MarketNewsHeaderStrip(
                            feedState = state.newsFeedState,
                            selectedCategory = state.selectedNewsCategory,
                            onSelectCategory = viewModel::selectNewsCategory,
                            onRefresh = { viewModel.refreshNews(focusSymbol = state.selectedNewsSymbol, force = true) }
                        )
                    }

                    val newsBusy = state.newsFeedState.isLoading || state.newsFeedState.isGroundingLoading
                    if (state.filteredNewsArticles.isEmpty() && !newsBusy) {
                        item(key = "journal_news_empty") {
                            EmptyStatePanel(
                                icon = Icons.Rounded.Newspaper,
                                title = if (state.selectedNewsSymbol != null) {
                                    "No news yet for ${state.selectedNewsSymbol}"
                                } else {
                                    "No watchlist headlines loaded yet"
                                },
                                subtitle = state.newsFeedState.groundingError
                                    ?: state.newsFeedState.errorMessage
                                    ?: "Tap Refresh above to pull Google Search-grounded headlines filtered by your Watchlist symbols."
                            )
                        }
                    } else {
                        items(state.filteredNewsArticles, key = { it.id }) { article ->
                            MarketNewsArticleCard(
                                article = article,
                                onOpenSymbol = onOpenInstrument
                            )
                        }
                    }
                }
                JournalTab.BY_INSTRUMENT -> {
                    if (state.instrumentPerformance.isEmpty()) {
                        item(key = "instrument_perf_empty") {
                            EmptyStatePanel(
                                icon = Icons.Rounded.Assessment,
                                title = "No instrument performance yet",
                                subtitle = "Close trades to track symbol-by-symbol Win Rate, Gross P&L, Charges, and Net P&L in ₹ (India) or $ (Crypto)."
                            )
                        }
                    } else {
                        items(state.instrumentPerformance, key = { it.symbol }) { perf ->
                            InstrumentTradePerformanceCard(perf = perf)
                        }
                    }
                }
                JournalTab.CAPITAL -> {
                    if (state.capitalTransactions.isEmpty()) {
                        item(key = "capital_empty_panel") {
                            EmptyStatePanel(
                                icon = Icons.Rounded.AccountBalanceWallet,
                                title = "No capital adjustments recorded",
                                subtitle = "Add, reduce, or reset your virtual paper trading capital from Positions or Settings to maintain a complete audit ledger."
                            )
                        }
                    } else {
                        items(state.capitalTransactions, key = { it.id }) { tx ->
                            CapitalTransactionCard(tx = tx)
                        }
                    }
                }
            }
        }
    }

    if (tagEditorEntry != null) {
        TradeTagEditorDialog(
            symbol = tagEditorEntry.symbol,
            currentTags = tagEditorEntry.tags,
            suggestedTags = state.tagCounts.keys.toList(),
            onToggleTag = { tag ->
                viewModel.toggleTagOnEntry(tagEditorEntry.id, tagEditorEntry.tags, tag)
            },
            onAddCustomTag = { custom ->
                viewModel.addTagToEntry(tagEditorEntry.id, tagEditorEntry.tags, custom)
            },
            onDismiss = { tagEditorEntryId = null }
        )
    }
}

@Composable
private fun InstrumentTradePerformanceCard(perf: InstrumentTradePerformance) {
    val tradingColors = LocalTradingColors.current
    val netColor = if (perf.netPnl >= BigDecimal.ZERO) tradingColors.bullish else tradingColors.bearish
    val winColor = if (perf.winRatePercent >= 50.0) tradingColors.bullish else tradingColors.amber

    Surface(
        modifier = Modifier.fillMaxWidth().testTag("instrument_trade_perf_${perf.symbol}"),
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
                    SegmentBadge(perf.segment)
                    Text(perf.symbol, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                Text(
                    text = RiskMath.formatSignedCurrency(perf.netPnl, perf.segment),
                    style = MonoPriceMedium,
                    color = netColor
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("TRADES", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${perf.totalTrades} (${perf.wins}W / ${perf.losses}L)", style = MonoPriceSmall)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("WIN RATE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${String.format(Locale.US, "%.1f", perf.winRatePercent)}%", style = MonoPriceSmall, color = winColor, fontWeight = FontWeight.Bold)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("GROSS / FEES", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "${RiskMath.formatSignedCurrency(perf.grossPnl, perf.segment)} / ${RiskMath.formatPrice(perf.totalCharges, perf.segment)}",
                        style = MonoPriceSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun JournalEntryCard(
    entry: TradeJournalEntry,
    onSaveNotes: (String) -> Unit,
    onOpenTagEditor: () -> Unit,
    onFilterByTag: (String) -> Unit,
    onRemoveTag: (String) -> Unit
) {
    val tradingColors = LocalTradingColors.current
    val isWin = entry.netPnl >= BigDecimal.ZERO
    val netColor = if (isWin) tradingColors.bullish else tradingColors.bearish
    var editingNotes by remember { mutableStateOf(false) }
    var notesDraft by remember(entry.notes) { mutableStateOf(entry.notes) }
    var showChargesBreakdown by remember { mutableStateOf(false) }

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
                Column(modifier = Modifier.weight(1f)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        SegmentBadge(entry.segment)
                        Text(entry.symbol, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        "${entry.side.name} • Qty: ${RiskMath.formatQtyWithLots(entry.quantity, entry.lotSize)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = RiskMath.formatSignedCurrency(entry.netPnl, entry.segment),
                    style = MonoPriceMedium,
                    color = netColor
                )
            }

            // Custom Trade Tags Row + Add Tag button
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                entry.tags.forEach { tag ->
                    TradeTagPill(
                        tag = tag,
                        onClick = { onFilterByTag(tag) },
                        onRemove = { onRemoveTag(tag) },
                        modifier = Modifier.testTag("entry_tag_${entry.id}_${tag.lowercase().replace(" ", "_")}")
                    )
                }
                AddTradeTagButton(
                    onClick = onOpenTagEditor,
                    modifier = Modifier.testTag("add_tag_button_${entry.id}")
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
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Gross ${RiskMath.formatSignedCurrency(entry.grossPnl, entry.segment)} • Fees ${RiskMath.formatPrice(entry.charges, entry.segment)}",
                    style = MonoPriceSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(
                    onClick = { showChargesBreakdown = !showChargesBreakdown },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                ) {
                    Text(if (showChargesBreakdown) "Hide Taxes" else "Tax Breakdown", style = MaterialTheme.typography.labelSmall)
                }
            }

            AnimatedVisibility(visible = showChargesBreakdown) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        ChargeRow("Brokerage", RiskMath.formatPrice(entry.brokerage, entry.segment))
                        ChargeRow("STT / CTT", RiskMath.formatPrice(entry.sttCtt, entry.segment))
                        ChargeRow("Exchange Txn Charges", RiskMath.formatPrice(entry.exchangeCharges, entry.segment))
                        ChargeRow("SEBI Turnover Charges", RiskMath.formatPrice(entry.sebiCharges, entry.segment))
                        ChargeRow("Stamp Duty", RiskMath.formatPrice(entry.stampDuty, entry.segment))
                        ChargeRow("GST (18%)", RiskMath.formatPrice(entry.gst, entry.segment))
                        ChargeRow("Total Charges & Taxes", RiskMath.formatPrice(entry.charges, entry.segment), bold = true)
                    }
                }
            }

            Text(
                text = "${MarketClock.formatIstDate(entry.closedAtMillis)} ${MarketClock.formatIstTime(entry.closedAtMillis)} IST",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

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

@Composable
private fun ChargeRow(label: String, value: String, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            color = if (bold) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MonoPriceSmall,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium
        )
    }
}

@Composable
private fun CapitalTransactionCard(tx: CapitalTransaction) {
    val tradingColors = LocalTradingColors.current
    val (badgeBg, badgeFg, prefix) = when (tx.type) {
        CapitalTransactionType.ADD -> Triple(tradingColors.bullishContainer, tradingColors.bullish, "+")
        CapitalTransactionType.REDUCE -> Triple(tradingColors.bearishContainer, tradingColors.bearish, "-")
        CapitalTransactionType.RESET -> Triple(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.primary, "=")
    }

    Surface(
        modifier = Modifier.fillMaxWidth().testTag("capital_tx_${tx.id}"),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, tradingColors.border)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Surface(shape = RoundedCornerShape(6.dp), color = badgeBg) {
                    Text(
                        text = "${tx.type.label} • ${tx.currency}",
                        style = MaterialTheme.typography.labelSmall,
                        color = badgeFg,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                Text(tx.note, style = MaterialTheme.typography.bodySmall)
                Text(
                    "${MarketClock.formatIstDate(tx.timestampMillis)} ${MarketClock.formatIstTime(tx.timestampMillis)} IST",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            val isUsd = tx.currency.equals("USD", ignoreCase = true)
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "$prefix${if (isUsd) RiskMath.formatPortfolioUsd(tx.amount) else RiskMath.formatPortfolioInr(tx.amount)}",
                    style = MonoPriceMedium,
                    color = badgeFg
                )
                Text(
                    text = "Bal: ${if (isUsd) RiskMath.formatPortfolioUsd(tx.balanceAfter) else RiskMath.formatPortfolioInr(tx.balanceAfter)}",
                    style = MonoPriceSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
