package com.example.ui.screens.heatmaps

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Analytics
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.domain.market.HeatmapAssetNode
import com.example.domain.market.HeatmapMarketType
import com.example.domain.market.HeatmapScaleMetric
import com.example.domain.market.SquarifiedTreemapEngine
import com.example.domain.market.TreemapRect
import com.example.ui.screens.market.FundamentalAnalysisDialog
import com.example.ui.theme.LocalTradingColors
import com.example.ui.theme.MonoPriceMedium
import com.example.ui.theme.MonoPriceSmall
import java.util.Locale

/**
 * Top-level Market Heatmaps tab visualizing NSE Equities, Crypto, and Combined markets
 * using a true squarified treemap color-coded by percentage change and scaled by
 * Market Capitalization, Volume, or Equal weight.
 */
@Composable
fun HeatmapsScreen(
    onOpenInstrument: (String) -> Unit,
    onOpenChart: (String) -> Unit,
    viewModel: HeatmapsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val tradingColors = LocalTradingColors.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .testTag("heatmaps_screen"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Header Row: Title + Live Sync Status + Refresh
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Market Treemap Heatmaps",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = state.lastSyncLabel.ifBlank { "Live NSE & Binance Crypto feeds" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(
                onClick = viewModel::refresh,
                enabled = !state.isRefreshing,
                modifier = Modifier.testTag("heatmaps_refresh_button")
            ) {
                if (state.isRefreshing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Rounded.Refresh, contentDescription = "Refresh Heatmap Feeds")
                }
            }
        }

        // Market Type Selector: NSE Equities | Crypto | NSE + Crypto
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            HeatmapMarketType.entries.forEach { market ->
                val selected = state.marketType == market
                FilterChip(
                    selected = selected,
                    onClick = { viewModel.selectMarketType(market) },
                    label = {
                        Text(
                            text = market.label,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                        )
                    },
                    modifier = Modifier.testTag("heatmap_market_${market.name.lowercase()}"),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.primary
                    )
                )
            }
        }

        // Scale By Controls: Market Cap | Volume | Equal
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "SIZE BY:",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            HeatmapScaleMetric.entries.forEach { metric ->
                val selected = state.scaleMetric == metric
                FilterChip(
                    selected = selected,
                    onClick = { viewModel.selectScaleMetric(metric) },
                    label = { Text(metric.label, style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.testTag("heatmap_scale_${metric.name.lowercase()}"),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = tradingColors.violetContainer,
                        selectedLabelColor = tradingColors.violet
                    )
                )
            }
        }

        // Breadth Summary & Color Heat Scale Card
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, tradingColors.border)
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val total = (state.advancingCount + state.decliningCount).coerceAtLeast(1)
                val advFraction = state.advancingCount.toFloat() / total.toFloat()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "▲ ${state.advancingCount} Advancing",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = tradingColors.bullish
                    )
                    Text(
                        text = String.format(Locale.US, "Avg %+.2f%%", state.avgChangePercent),
                        style = MonoPriceSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (state.avgChangePercent >= 0.0) tradingColors.bullish else tradingColors.bearish
                    )
                    Text(
                        text = "▼ ${state.decliningCount} Declining",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = tradingColors.bearish
                    )
                }

                // Breadth Ratio Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                ) {
                    if (advFraction > 0.01f) {
                        Box(
                            Modifier
                                .fillMaxHeight()
                                .weight(advFraction)
                                .background(tradingColors.bullish)
                        )
                    }
                    if (1f - advFraction > 0.01f) {
                        Box(
                            Modifier
                                .fillMaxHeight()
                                .weight(1f - advFraction)
                                .background(tradingColors.bearish)
                        )
                    }
                }

                // Color Gradient Legend (-5% -> 0% -> +5%)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("-5%", style = MonoPriceSmall, color = tradingColors.bearish)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(
                                        Color(0xFFB71C1C),
                                        Color(0xFFD32F2F),
                                        Color(0xFF334155),
                                        Color(0xFF15803D),
                                        Color(0xFF00C853)
                                    )
                                )
                            )
                    )
                    Text("+5%", style = MonoPriceSmall, color = tradingColors.bullish)
                }
            }
        }

        // Sector / Crypto Category Filter Strip
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilterChip(
                selected = state.selectedGroup == null,
                onClick = { viewModel.selectGroup(null) },
                label = { Text("All (${state.nodes.size})", style = MaterialTheme.typography.labelSmall) },
                modifier = Modifier.testTag("heatmap_group_all")
            )
            state.availableGroups.forEach { grp ->
                val isSelected = state.selectedGroup == grp
                FilterChip(
                    selected = isSelected,
                    onClick = { viewModel.selectGroup(if (isSelected) null else grp) },
                    label = { Text(grp, style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.testTag("heatmap_group_${grp.lowercase().replace(" ", "_")}")
                )
            }
        }

        // True Squarified Treemap Container
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("squarified_treemap_canvas"),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            border = BorderStroke(1.dp, tradingColors.border)
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(6.dp)
                    .height(440.dp)
            ) {
                val containerW = maxWidth
                val containerH = maxHeight
                val aspectRatio = remember(containerW, containerH) {
                    (containerW.value / containerH.value.coerceAtLeast(1f)).coerceIn(0.5f, 2.5f)
                }
                val rects: List<TreemapRect> = remember(state.nodes, state.scaleMetric, aspectRatio) {
                    SquarifiedTreemapEngine.layout(
                        nodes = state.nodes,
                        scaleMetric = state.scaleMetric,
                        containerAspectRatio = aspectRatio
                    )
                }

                rects.forEach { rect ->
                    val tileW = containerW * rect.width
                    val tileH = containerH * rect.height
                    val tileBg = treemapHeatColor(rect.node.changePercent)
                    val tileBorder = treemapHeatBorder(rect.node.changePercent)

                    Surface(
                        modifier = Modifier
                            .offset(x = containerW * rect.left, y = containerH * rect.top)
                            .size(width = tileW, height = tileH)
                            .padding(1.5.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { viewModel.selectNode(rect.node) }
                            .semantics {
                                contentDescription =
                                    "${rect.node.name}, ${String.format(Locale.US, "%+.2f%%", rect.node.changePercent)}, ${rect.node.priceFormatted}"
                            }
                            .testTag("treemap_tile_${rect.node.symbol}"),
                        shape = RoundedCornerShape(8.dp),
                        color = tileBg,
                        border = BorderStroke(0.8.dp, tileBorder)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 4.dp, vertical = 3.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            when {
                                tileW >= 88.dp && tileH >= 64.dp -> {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                                        ) {
                                            Text(
                                                text = rect.node.displaySymbol,
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = Color.White,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            if (state.marketType == HeatmapMarketType.COMBINED) {
                                                Text(
                                                    text = if (rect.node.isCrypto) "CRYPTO" else "NSE",
                                                    fontSize = 8.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color.White.copy(alpha = 0.85f),
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(3.dp))
                                                        .background(Color.Black.copy(alpha = 0.25f))
                                                        .padding(horizontal = 3.dp, vertical = 0.5.dp)
                                                )
                                            }
                                        }
                                        Text(
                                            text = String.format(Locale.US, "%+.2f%%", rect.node.changePercent),
                                            style = MonoPriceSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White,
                                            maxLines = 1
                                        )
                                        Text(
                                            text = rect.node.priceFormatted,
                                            fontSize = 10.sp,
                                            color = Color.White.copy(alpha = 0.9f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (tileH >= 84.dp) {
                                            Text(
                                                text = if (state.scaleMetric == HeatmapScaleMetric.VOLUME) {
                                                    "Vol ${rect.node.volumeLabel}"
                                                } else {
                                                    rect.node.marketCapLabel
                                                },
                                                fontSize = 9.sp,
                                                color = Color.White.copy(alpha = 0.78f),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }

                                tileW >= 54.dp && tileH >= 40.dp -> {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        Text(
                                            text = rect.node.displaySymbol,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = String.format(Locale.US, "%+.1f%%", rect.node.changePercent),
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Color.White.copy(alpha = 0.92f),
                                            maxLines = 1
                                        )
                                    }
                                }

                                else -> {
                                    Text(
                                        text = rect.node.displaySymbol,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Clip
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Top Gainer & Top Loser Quick Highlight Row
        if (state.topGainer != null && state.topLoser != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ExtremeMoverTile(
                    title = "TOP GAINER",
                    node = state.topGainer!!,
                    accent = tradingColors.bullish,
                    container = tradingColors.bullishContainer,
                    onClick = { viewModel.selectNode(state.topGainer!!) },
                    modifier = Modifier.weight(1f)
                )
                ExtremeMoverTile(
                    title = "TOP LOSER",
                    node = state.topLoser!!,
                    accent = tradingColors.bearish,
                    container = tradingColors.bearishContainer,
                    onClick = { viewModel.selectNode(state.topLoser!!) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(Modifier.height(8.dp))
    }

    // Tapped Treemap Tile Detail Dialog
    state.selectedNode?.let { node ->
        HeatmapNodeDetailDialog(
            node = node,
            onOpenFundamentals = if (!node.isCrypto) {
                { viewModel.openFundamentals(node.symbol) }
            } else null,
            onOpenChart = { viewModel.prepareAndNavigate(node, onOpenChart) },
            onTradeNow = { viewModel.prepareAndNavigate(node, onOpenInstrument) },
            onDismiss = viewModel::dismissNode
        )
    }

    // Fundamental Analysis Dialog when launched from NSE Heatmap Tile
    state.selectedFundamentalStock?.let { stock ->
        FundamentalAnalysisDialog(
            stock = stock,
            liveDetails = state.selectedFundamentalDetails,
            isLoadingDetails = state.isLoadingFundamentalDetails,
            onRefreshDetails = viewModel::refreshFundamentalDetails,
            onOpenChart = { viewModel.prepareStockAndNavigate(stock, onOpenChart) },
            onTradeNow = { viewModel.prepareStockAndNavigate(stock, onOpenInstrument) },
            onDismiss = viewModel::closeFundamentals
        )
    }
}

@Composable
private fun ExtremeMoverTile(
    title: String,
    node: HeatmapAssetNode,
    accent: Color,
    container: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = container,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.4f))
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = accent
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = node.displaySymbol,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = String.format(Locale.US, "%+.2f%%", node.changePercent),
                    style = MonoPriceSmall,
                    fontWeight = FontWeight.Bold,
                    color = accent
                )
            }
            Text(
                text = "${node.priceFormatted} • ${node.groupLabel}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun HeatmapNodeDetailDialog(
    node: HeatmapAssetNode,
    onOpenFundamentals: (() -> Unit)?,
    onOpenChart: () -> Unit,
    onTradeNow: () -> Unit,
    onDismiss: () -> Unit
) {
    val tradingColors = LocalTradingColors.current
    val isUp = node.changePercent >= 0.0
    val chgColor = if (isUp) tradingColors.bullish else tradingColors.bearish

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .testTag("heatmap_node_dialog_${node.symbol}"),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, tradingColors.border)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = node.displaySymbol,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (node.isCrypto) tradingColors.violetContainer else MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Text(
                                    text = if (node.isCrypto) "CRYPTO" else "NSE",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (node.isCrypto) tradingColors.violet else MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh
                            ) {
                                Text(
                                    text = node.groupLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Text(
                            text = node.name,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("close_heatmap_node_dialog")
                    ) {
                        Icon(Icons.Rounded.Close, contentDescription = "Close Details")
                    }
                }

                // Price + Change Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = node.priceFormatted,
                        style = MonoPriceMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isUp) tradingColors.bullishContainer else tradingColors.bearishContainer
                    ) {
                        Text(
                            text = String.format(Locale.US, "%+.2f%%", node.changePercent),
                            style = MonoPriceMedium,
                            color = chgColor,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }

                // Market Cap & Volume Metrics
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    NodeStatChip("Market Cap", node.marketCapLabel, Modifier.weight(1f))
                    NodeStatChip("24h / Day Vol", node.volumeLabel, Modifier.weight(1f))
                }

                // Technical Snapshot (for NSE stocks)
                if (node.rsi14 != null && node.maSignal != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        NodeStatChip("RSI (14)", String.format(Locale.US, "%.1f", node.rsi14), Modifier.weight(1f))
                        NodeStatChip("MA Signal", node.maSignal.shortLabel, Modifier.weight(1f))
                    }
                }

                HorizontalDivider(color = tradingColors.border)

                if (onOpenFundamentals != null) {
                    OutlinedButton(
                        onClick = onOpenFundamentals,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("heatmap_open_fundamentals_${node.symbol}"),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Rounded.Insights, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Fundamental Scorecard & Ratios")
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onOpenChart,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("heatmap_open_chart_${node.symbol}"),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Rounded.Analytics, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Chart")
                    }
                    Button(
                        onClick = onTradeNow,
                        modifier = Modifier
                            .weight(1.2f)
                            .testTag("heatmap_trade_now_${node.symbol}"),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Trade ${node.displaySymbol}", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun NodeStatChip(label: String, value: String, modifier: Modifier = Modifier) {
    val tradingColors = LocalTradingColors.current
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(0.8.dp, tradingColors.border)
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = value,
                style = MonoPriceSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun treemapHeatColor(changePct: Double): Color {
    val clamped = changePct.coerceIn(-5.0, 5.0)
    val neutral = Color(0xFF334155)
    return if (clamped >= 0.0) {
        val t = (clamped / 5.0).toFloat()
        if (t < 0.35f) {
            lerp(neutral, Color(0xFF166534), t / 0.35f)
        } else {
            lerp(Color(0xFF166534), Color(0xFF00B85C), (t - 0.35f) / 0.65f)
        }
    } else {
        val t = (-clamped / 5.0).toFloat()
        if (t < 0.35f) {
            lerp(neutral, Color(0xFF991B1B), t / 0.35f)
        } else {
            lerp(Color(0xFF991B1B), Color(0xFFDC2626), (t - 0.35f) / 0.65f)
        }
    }
}

private fun treemapHeatBorder(changePct: Double): Color {
    return when {
        changePct >= 2.0 -> Color(0xFF4ADE80).copy(alpha = 0.55f)
        changePct <= -2.0 -> Color(0xFFF87171).copy(alpha = 0.55f)
        else -> Color.White.copy(alpha = 0.14f)
    }
}
