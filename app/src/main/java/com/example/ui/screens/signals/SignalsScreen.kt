package com.example.ui.screens.signals

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.signal.SignalUiItem
import com.example.domain.analysis.RiskRewardLadder
import com.example.domain.engine.MarketClock
import com.example.domain.engine.RiskMath
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.domain.signal.AiSignalSecondOpinion
import com.example.domain.signal.AiUiState
import com.example.domain.signal.InstrumentSignalPerformance
import com.example.domain.signal.SignalAccuracyStats
import com.example.domain.signal.SignalAssetCategory
import com.example.domain.signal.SignalConfidence
import com.example.domain.signal.SignalDirection
import com.example.domain.signal.SignalOutcome
import com.example.domain.signal.SignalRecord
import com.example.domain.signal.SignalStrategy
import com.example.domain.signal.TradeSignal
import com.example.ui.components.AiSecondOpinionCard
import com.example.ui.components.EmptyStatePanel
import com.example.ui.components.SegmentBadge
import com.example.ui.navigation.InstrumentDetailRoute
import com.example.ui.theme.LocalTradingColors
import com.example.ui.theme.MonoPriceMedium
import com.example.ui.theme.MonoPriceSmall
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignalsScreen(
    onCreateOrderFromSignal: (InstrumentDetailRoute) -> Unit,
    onOpenChart: (symbol: String, timeframe: String) -> Unit = { _, _ -> },
    viewModel: SignalsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val tradingColors = LocalTradingColors.current

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.toggleSignalNotifications(granted)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Advisory header + notification toggle + scan button
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, tradingColors.border)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Bolt,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Column {
                        Text(
                            text = "Advisory Trade Signals",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Auto-removes hit SL/Target • Tracks Accuracy & BTST/STBT",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                IconButton(
                    onClick = {
                        if (!state.signalNotificationsEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            viewModel.toggleSignalNotifications(!state.signalNotificationsEnabled)
                        }
                    },
                    modifier = Modifier.testTag("signals_notif_toggle")
                ) {
                    Icon(
                        imageVector = if (state.signalNotificationsEnabled) Icons.Rounded.NotificationsActive else Icons.Rounded.NotificationsOff,
                        contentDescription = if (state.signalNotificationsEnabled) "Disable signal notifications" else "Enable signal notifications",
                        tint = if (state.signalNotificationsEnabled) tradingColors.bullish else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = viewModel::refreshSignals,
                    enabled = !state.isRefreshing,
                    modifier = Modifier.testTag("signals_refresh_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = "Rescan Signals"
                    )
                }
            }
        }

        // Active / Saved / Performance tab row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            SignalsTab.entries.forEach { tab ->
                val count = when (tab) {
                    SignalsTab.ACTIVE -> state.activeCount
                    SignalsTab.SAVED -> state.savedCount
                    SignalsTab.HISTORY -> state.historyCount
                }
                val selected = state.filters.tab == tab
                FilterChip(
                    selected = selected,
                    onClick = { viewModel.setTab(tab) },
                    label = { Text("${tab.label} ($count)", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("signals_tab_${tab.name.lowercase()}"),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.primary
                    )
                )
            }
        }

        // Asset-Class Filter Row (All, Stock, Index, F&O, MCX, Crypto, Crypto F&O, BTST / STBT)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SignalAssetCategory.entries.forEach { cat ->
                FilterChip(
                    selected = state.filters.assetCategory == cat,
                    onClick = { viewModel.setAssetCategory(cat) },
                    label = { Text(cat.label) },
                    modifier = Modifier.testTag("signal_cat_${cat.name.lowercase()}"),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.primary
                    )
                )
            }
        }

        // Secondary Filter Strip (Direction, Strategy, Confidence)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SignalDirection.entries.forEach { dir ->
                FilterChip(
                    selected = state.filters.direction == dir,
                    onClick = { viewModel.setDirectionFilter(dir) },
                    label = { Text(dir.name) }
                )
            }
            SignalConfidence.entries.forEach { conf ->
                FilterChip(
                    selected = state.filters.confidence == conf,
                    onClick = { viewModel.setConfidenceFilter(conf) },
                    label = { Text("${conf.label} Conf") }
                )
            }
            SignalStrategy.entries.forEach { strategy ->
                FilterChip(
                    selected = state.filters.strategy == strategy,
                    onClick = { viewModel.setStrategyFilter(strategy) },
                    label = { Text(strategy.shortLabel) }
                )
            }
        }

        if (state.filters.tab == SignalsTab.HISTORY) {
            var showByInstrument by remember { mutableStateOf(true) }

            SignalAccuracySummaryCard(
                stats = state.accuracyStats,
                onClearHistory = viewModel::clearSignalHistory
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = showByInstrument,
                    onClick = { showByInstrument = true },
                    label = { Text("By Instrument (${state.instrumentPerformance.size})") },
                    modifier = Modifier.weight(1f).testTag("signals_perf_by_instrument_tab"),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.primary
                    )
                )
                FilterChip(
                    selected = !showByInstrument,
                    onClick = { showByInstrument = false },
                    label = { Text("Signal Log (${state.historyRecords.size})") },
                    modifier = Modifier.weight(1f).testTag("signals_perf_log_tab"),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.primary
                    )
                )
            }

            if (state.historyRecords.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    EmptyStatePanel(
                        icon = Icons.Rounded.Bolt,
                        title = "No Resolved Signal Records Yet",
                        subtitle = "When active signals hit Target 1, Target 2, Stop Loss, or Expire, they are removed from Active and tracked here for live accuracy."
                    )
                }
            } else if (showByInstrument) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(state.instrumentPerformance, key = { it.symbol }) { perf ->
                        InstrumentSignalPerformanceCard(perf = perf)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(state.historyRecords, key = { it.id }) { rec ->
                        SignalRecordCard(record = rec)
                    }
                }
            }
        } else if (state.items.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                EmptyStatePanel(
                    icon = Icons.Rounded.Bolt,
                    title = if (state.filters.tab == SignalsTab.SAVED) "No Saved Signals" else "No Matching Active Signals",
                    subtitle = "Signals auto-archive once SL or Target hits to reduce noise. Tap Refresh or switch filters to inspect more markets."
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(state.items, key = { it.signal.id }) { item ->
                    SignalCard(
                        item = item,
                        onClick = { viewModel.selectSignal(item) },
                        onToggleSave = { viewModel.toggleSave(item) },
                        onCreateOrder = {
                            viewModel.acceptSignal(item.signal.id)
                            onCreateOrderFromSignal(viewModel.buildPrefilledOrderRoute(item.signal))
                        }
                    )
                }
            }
        }
    }

    state.selectedItem?.let { item ->
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { viewModel.selectSignal(null) },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            SignalDetailSheetContent(
                item = item,
                aiState = state.selectedAiState,
                onAnalyzeAi = { viewModel.analyzeSignalWithAi(item.signal) },
                onAccept = { viewModel.acceptSignal(item.signal.id) },
                onDismiss = { viewModel.dismissSignal(item.signal) },
                onToggleSave = { viewModel.toggleSave(item) },
                onCreateOrder = { useT2 ->
                    viewModel.acceptSignal(item.signal.id)
                    viewModel.selectSignal(null)
                    onCreateOrderFromSignal(viewModel.buildPrefilledOrderRoute(item.signal, useTarget2 = useT2))
                },
                onOpenChart = {
                    viewModel.selectSignal(null)
                    onOpenChart(item.signal.symbol, item.signal.timeframe.name)
                }
            )
        }
    }
}

@Composable
private fun SignalAccuracySummaryCard(
    stats: SignalAccuracyStats,
    onClearHistory: () -> Unit
) {
    val tradingColors = LocalTradingColors.current
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, tradingColors.border),
        modifier = Modifier.fillMaxWidth().testTag("signal_accuracy_card")
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("SIGNAL PERFORMANCE & ACCURACY", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = "${String.format(Locale.US, "%.1f", stats.accuracyPercent)}% Win Accuracy (${stats.wins}/${stats.decidedCount} decided)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (stats.accuracyPercent >= 50.0) tradingColors.bullish else tradingColors.amber
                    )
                }
                if (stats.totalResolved > 0) {
                    TextButton(onClick = onClearHistory, contentPadding = PaddingValues(horizontal = 6.dp)) {
                        Icon(Icons.Rounded.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(3.dp))
                        Text("Clear", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                PriceMetricCell("TARGET 1", stats.t1Hits.toString(), tradingColors.bullish)
                PriceMetricCell("TARGET 2", stats.t2Hits.toString(), tradingColors.bullish)
                PriceMetricCell("STOP LOSS", stats.slHits.toString(), tradingColors.bearish)
                PriceMetricCell("EXPIRED", stats.expiredCount.toString(), MaterialTheme.colorScheme.onSurfaceVariant)
                PriceMetricCell("TOTAL", stats.totalResolved.toString(), MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun InstrumentSignalPerformanceCard(perf: InstrumentSignalPerformance) {
    val tradingColors = LocalTradingColors.current
    val accColor = when {
        perf.decidedCount == 0 -> MaterialTheme.colorScheme.onSurfaceVariant
        perf.accuracyPercent >= 60.0 -> tradingColors.bullish
        perf.accuracyPercent >= 45.0 -> tradingColors.amber
        else -> tradingColors.bearish
    }
    val pnlSign = if (perf.avgPnlPercent >= 0.0) "+" else ""
    val pnlColor = if (perf.avgPnlPercent >= 0.0) tradingColors.bullish else tradingColors.bearish

    Surface(
        modifier = Modifier.fillMaxWidth().testTag("signal_inst_perf_${perf.symbol}"),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, tradingColors.border)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(perf.symbol, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    SegmentBadge(perf.segment)
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (perf.accuracyPercent >= 50.0) tradingColors.bullishContainer else MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Text(
                        text = if (perf.decidedCount > 0) {
                            "${String.format(Locale.US, "%.1f", perf.accuracyPercent)}% Acc (${perf.wins}/${perf.decidedCount})"
                        } else {
                            "0 decided"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = accColor,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                PriceMetricCell("T1", perf.t1Hits.toString(), tradingColors.bullish)
                PriceMetricCell("T2", perf.t2Hits.toString(), tradingColors.bullish)
                PriceMetricCell("SL", perf.slHits.toString(), tradingColors.bearish)
                PriceMetricCell("EXP", perf.expiredCount.toString(), MaterialTheme.colorScheme.onSurfaceVariant)
                PriceMetricCell("AVG P&L", "$pnlSign${String.format(Locale.US, "%.2f", perf.avgPnlPercent)}%", pnlColor)
            }
        }
    }
}

@Composable
private fun SignalRecordCard(record: SignalRecord) {
    val tradingColors = LocalTradingColors.current
    val (outcomeBg, outcomeFg) = when (record.outcome) {
        SignalOutcome.HIT_T1, SignalOutcome.HIT_T2 -> tradingColors.bullishContainer to tradingColors.bullish
        SignalOutcome.HIT_SL -> tradingColors.bearishContainer to tradingColors.bearish
        SignalOutcome.EXPIRED -> MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurfaceVariant
    }
    val pnlSign = if (record.pnlPercent >= 0) "+" else ""
    val pnlColor = if (record.pnlPercent >= 0) tradingColors.bullish else tradingColors.bearish

    Surface(
        modifier = Modifier.fillMaxWidth().testTag("signal_record_${record.symbol}"),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, tradingColors.border)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(record.symbol, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    SegmentBadge(record.segment)
                    Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                        Text(
                            "${record.strategy.shortLabel} • ${record.timeframe.label}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Surface(shape = RoundedCornerShape(6.dp), color = outcomeBg) {
                    Text(
                        text = record.outcome.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = outcomeFg,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                PriceMetricCell("ENTRY", RiskMath.formatPrice(record.entryPrice, record.segment), MaterialTheme.colorScheme.onSurface)
                PriceMetricCell("RESOLVED", RiskMath.formatPrice(record.resolvedPrice, record.segment), outcomeFg)
                PriceMetricCell("T1 / T2", "${RiskMath.formatPrice(record.target1, record.segment)} / ${RiskMath.formatPrice(record.target2, record.segment)}", tradingColors.bullish)
                PriceMetricCell("RESULT", "$pnlSign${String.format(Locale.US, "%.2f", record.pnlPercent)}%", pnlColor)
            }
            Text(
                text = "${record.direction.name} • Resolved ${MarketClock.formatIstDate(record.resolvedAtMillis)} ${MarketClock.formatIstTime(record.resolvedAtMillis)} IST",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SignalCard(
    item: SignalUiItem,
    onClick: () -> Unit,
    onToggleSave: () -> Unit,
    onCreateOrder: () -> Unit
) {
    val sig = item.signal
    val tradingColors = LocalTradingColors.current
    val isLong = sig.direction == SignalDirection.LONG
    val dirColor = if (isLong) tradingColors.bullish else tradingColors.bearish
    val dirBg = if (isLong) tradingColors.bullishContainer else tradingColors.bearishContainer

    val (confBg, confFg) = when (sig.confidence) {
        SignalConfidence.HIGH -> tradingColors.bullishContainer to tradingColors.bullish
        SignalConfidence.MEDIUM -> tradingColors.amberContainer to tradingColors.amber
        SignalConfidence.LOW -> MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .testTag("signal_card_${sig.symbol}_${sig.strategy.name.lowercase()}"),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, tradingColors.border)
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
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = sig.symbol,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    SegmentBadge(sig.segment)
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (sig.strategy == SignalStrategy.BTST_STBT) tradingColors.violetContainer else MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = "${sig.strategy.shortLabel} • ${sig.timeframe.label}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (sig.strategy == SignalStrategy.BTST_STBT) tradingColors.violet else MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Surface(shape = RoundedCornerShape(6.dp), color = dirBg) {
                        Text(
                            text = if (sig.strategy == SignalStrategy.BTST_STBT) {
                                if (isLong) "BTST" else "STBT"
                            } else sig.direction.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = dirColor,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                        )
                    }
                    Surface(shape = RoundedCornerShape(6.dp), color = confBg) {
                        Text(
                            text = sig.confidence.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = confFg,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                        )
                    }
                    IconButton(
                        onClick = onToggleSave,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = if (item.isSaved) Icons.Rounded.Bookmark else Icons.Outlined.BookmarkBorder,
                            contentDescription = if (item.isSaved) "Remove Saved Signal" else "Save Signal",
                            tint = if (item.isSaved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // Entry / SL / T1 / T2 / R:R grid
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                PriceMetricCell("ENTRY", RiskMath.formatPrice(sig.entryPrice, sig.segment), MaterialTheme.colorScheme.onSurface)
                PriceMetricCell("STOP LOSS", RiskMath.formatPrice(sig.stopLoss, sig.segment), tradingColors.bearish)
                PriceMetricCell("TARGET 1", RiskMath.formatPrice(sig.target1, sig.segment), tradingColors.bullish)
                PriceMetricCell("TARGET 2", RiskMath.formatPrice(sig.target2, sig.segment), tradingColors.bullish)
                PriceMetricCell("R:R", String.format(Locale.US, "1:%.1f", sig.riskRewardRatio), MaterialTheme.colorScheme.primary)
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                sig.reasons.take(3).forEach { reason ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer
                    ) {
                        Text(
                            text = reason,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val statusTag = if (item.userStatus == "ACCEPTED") "Accepted • " else ""
                Text(
                    text = "${statusTag}${MarketClock.formatIstTime(sig.generatedAt.toEpochMilli())} IST",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(
                    onClick = onCreateOrder,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.testTag("create_order_signal_${sig.symbol}")
                ) {
                    Text("Create Order", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun PriceMetricCell(label: String, value: String, valueColor: Color) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MonoPriceSmall,
            color = valueColor,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun SignalDetailSheetContent(
    item: SignalUiItem,
    aiState: AiUiState<AiSignalSecondOpinion>,
    onAnalyzeAi: () -> Unit,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
    onToggleSave: () -> Unit,
    onCreateOrder: (useTarget2: Boolean) -> Unit,
    onOpenChart: () -> Unit = {}
) {
    val sig: TradeSignal = item.signal
    val tradingColors = LocalTradingColors.current
    val isLong = sig.direction == SignalDirection.LONG
    val dirColor = if (isLong) tradingColors.bullish else tradingColors.bearish

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(sig.symbol, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    SegmentBadge(sig.segment)
                }
                Text(
                    text = "${sig.strategy.label} • ${sig.timeframe.label} • ${sig.confidence.label} Confidence",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (isLong) tradingColors.bullishContainer else tradingColors.bearishContainer
            ) {
                Text(
                    text = sig.direction.name,
                    style = MaterialTheme.typography.labelLarge,
                    color = dirColor,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }

        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, tradingColors.border)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DetailRow("Advisory Entry", RiskMath.formatPrice(sig.entryPrice, sig.segment), MaterialTheme.colorScheme.onSurface)
                DetailRow("Stop Loss", RiskMath.formatPrice(sig.stopLoss, sig.segment), tradingColors.bearish)
                DetailRow("Target 1", RiskMath.formatPrice(sig.target1, sig.segment), tradingColors.bullish)
                DetailRow("Target 2", RiskMath.formatPrice(sig.target2, sig.segment), tradingColors.bullish)
                DetailRow("Max Risk:Reward", String.format(Locale.US, "1 : %.2f", sig.riskRewardRatio), MaterialTheme.colorScheme.primary)
                sig.expiresAt?.let { exp ->
                    DetailRow("Valid Until", "${MarketClock.formatIstDate(exp.toEpochMilli())} ${MarketClock.formatIstTime(exp.toEpochMilli())} IST", MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        val ladder = RiskRewardLadder.build(sig.entryPrice.toDouble(), sig.stopLoss.toDouble(), sig.direction)
        if (ladder.isNotEmpty()) {
            Text("RISK : REWARD LADDER", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, tradingColors.border)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val scale = sig.entryPrice.scale().coerceAtLeast(2)
                    ladder.forEach { t ->
                        DetailRow(
                            t.label,
                            RiskMath.formatPrice(BigDecimal.valueOf(t.price).setScale(scale, RoundingMode.HALF_UP), sig.segment),
                            tradingColors.bullish
                        )
                    }
                }
            }
        }

        OutlinedButton(
            onClick = onOpenChart,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("sheet_open_chart"),
            shape = RoundedCornerShape(10.dp)
        ) {
            Text("Open Chart & Analysis (${sig.timeframe.label})")
        }

        Text("CONFLUENCE BREAKDOWN", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            sig.reasons.forEach { reason ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = dirColor,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(reason, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        AiSecondOpinionCard(
            title = "AI Signal Second Opinion",
            subtitle = "Advisory confluence & invalidation check",
            ctaLabel = "Analyze Setup with AI",
            aiState = aiState,
            onAnalyze = onAnalyzeAi,
            ctaTestTag = "ai_analyze_signal_button"
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onAccept,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Rounded.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (item.userStatus == "ACCEPTED") "Accepted" else "Accept")
            }
            OutlinedButton(
                onClick = onToggleSave,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(
                    if (item.isSaved) Icons.Rounded.Bookmark else Icons.Outlined.BookmarkBorder,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(if (item.isSaved) "Saved" else "Save")
            }
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Dismiss")
            }
        }

        Button(
            onClick = { onCreateOrder(false) },
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .testTag("sheet_create_order_from_signal"),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = dirColor,
                contentColor = Color.White
            )
        ) {
            Text(
                text = "Create Order from Signal (Manual Confirm)",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String, valueColor: Color) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MonoPriceMedium, color = valueColor, fontWeight = FontWeight.SemiBold)
    }
}
