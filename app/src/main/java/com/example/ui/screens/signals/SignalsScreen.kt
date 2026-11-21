package com.example.ui.screens.signals

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
import com.example.domain.model.MarketSegment
import com.example.domain.signal.AiSignalSecondOpinion
import com.example.domain.signal.AiUiState
import com.example.domain.signal.SignalConfidence
import com.example.domain.signal.SignalDirection
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Advisory header + scan button
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
                            text = "Advisory only • Confirm manually before placing paper orders",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
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

        // Active / Saved tab row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SignalsTab.entries.forEach { tab ->
                val count = if (tab == SignalsTab.ACTIVE) state.activeCount else state.savedCount
                val selected = state.filters.tab == tab
                FilterChip(
                    selected = selected,
                    onClick = { viewModel.setTab(tab) },
                    label = { Text("${tab.label} ($count)") },
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

        // Multi-dimension filter strip (Strategy, Direction, Segment, Confidence)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SignalStrategy.entries.forEach { strategy ->
                FilterChip(
                    selected = state.filters.strategy == strategy,
                    onClick = { viewModel.setStrategyFilter(strategy) },
                    label = { Text(strategy.shortLabel) }
                )
            }
            SignalDirection.entries.forEach { dir ->
                FilterChip(
                    selected = state.filters.direction == dir,
                    onClick = { viewModel.setDirectionFilter(dir) },
                    label = { Text(dir.name) }
                )
            }
            MarketSegment.entries.forEach { seg ->
                FilterChip(
                    selected = state.filters.segment == seg,
                    onClick = { viewModel.setSegmentFilter(seg) },
                    label = { Text(seg.label) }
                )
            }
            SignalConfidence.entries.forEach { conf ->
                FilterChip(
                    selected = state.filters.confidence == conf,
                    onClick = { viewModel.setConfidenceFilter(conf) },
                    label = { Text("${conf.label} Conf") }
                )
            }
        }

        if (state.items.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                EmptyStatePanel(
                    icon = Icons.Rounded.Bolt,
                    title = if (state.filters.tab == SignalsTab.SAVED) "No Saved Signals" else "No Matching Signals Yet",
                    subtitle = "Tap Refresh or wait for completed candles across NSE, BSE, MCX, and Crypto."
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
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = "${sig.strategy.shortLabel} • ${sig.timeframe.label}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Surface(shape = RoundedCornerShape(6.dp), color = dirBg) {
                        Text(
                            text = sig.direction.name,
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

            // Reason chips (first 2 concise triggers)
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
            ctaLabel = "Analyze Setup with Gemini",
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
