package com.example.ui.screens.watchlist

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GridOn
import androidx.compose.material.icons.rounded.Newspaper
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.NotificationsNone
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.alerts.PriceAlert
import com.example.data.feed.live.AngelScripSearchResult
import com.example.data.feed.live.SegmentBadgeMode
import com.example.domain.engine.RiskMath
import com.example.domain.model.Instrument
import com.example.domain.model.InstrumentType
import com.example.domain.model.MarketSegment
import com.example.ui.components.EmptyStatePanel
import com.example.ui.components.FeedHealthBadge
import com.example.ui.components.FeedLostWarningBanner
import com.example.ui.components.InstrumentTypeBadge
import com.example.ui.components.PortfolioPulseHeader
import com.example.ui.components.PriceAlertsManagerDialog
import com.example.ui.components.SegmentBadge
import com.example.ui.components.SetPriceAlertDialog
import com.example.ui.components.SparklineChart
import com.example.ui.components.WatchlistNewsFeedSection
import com.example.ui.theme.LocalTradingColors
import com.example.ui.theme.MonoPriceMedium
import com.example.ui.theme.MonoPriceSmall
import java.math.BigDecimal

@Composable
fun WatchlistScreen(
    onInstrumentClick: (String) -> Unit,
    onOpenFeedSettings: () -> Unit,
    onOpenOptionChain: (String) -> Unit = {},
    onOpenNews: () -> Unit = {},
    viewModel: WatchlistViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val tradingColors = LocalTradingColors.current
    var alertTargetSymbol by remember { mutableStateOf<String?>(null) }
    var showAlertsManager by remember { mutableStateOf(false) }

    val alertTargetInstrument = remember(alertTargetSymbol, state.instruments) {
        alertTargetSymbol?.let { sym -> state.instruments.firstOrNull { it.symbol == sym } }
    }

    LaunchedEffect(Unit) {
        viewModel.openSymbolEvents.collect { symbol ->
            onInstrumentClick(symbol)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(8.dp))

        PortfolioPulseHeader(
            account = state.accountState,
            unrealisedPnl = state.unrealisedPnl,
            cryptoUnrealisedPnl = state.cryptoUnrealisedPnl,
            feedState = state.feedState,
            istClockText = state.istClockText,
            onOpenFeedSettings = onOpenFeedSettings,
            onRefreshLive = viewModel::reconnectFeed,
            activeFeedType = state.activeFeedType,
            liveConnectionStatus = state.liveConnectionStatus,
            riskControlsSuspended = state.riskControlsSuspended
        )

        if (state.riskControlsSuspended) {
            Spacer(Modifier.height(10.dp))
            FeedLostWarningBanner(
                headline = state.suspendedBannerMessage.ifBlank { "FEED LOST — risk controls suspended" },
                detail = "Live stream unreachable for open position(s); risk triggers paused to avoid false fills. Other markets remain active. Tap Switch to Simulated to run offline.",
                onReconnect = viewModel::reconnectFeed,
                onSwitchToSimulated = viewModel::switchToSimulatedFeed
            )
        }

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = viewModel::onSearchChange,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("watchlist_search_input"),
            placeholder = {
                Text(
                    if (state.hasAngelOneCredentials) {
                        "Search live NSE/BSE stocks, NIFTY/BANKNIFTY/SENSEX options..."
                    } else {
                        "Search NSE, BSE, Crypto, MCX symbols..."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = "Search Instruments"
                )
            },
            trailingIcon = {
                if (state.searchQuery.isNotEmpty()) {
                    IconButton(onClick = { viewModel.onSearchChange("") }) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Clear search"
                        )
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = tradingColors.border
            )
        )

        Spacer(Modifier.height(8.dp))

        // Live Option Chain (Greeks + OI) Quick Launcher Strip
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AssistChip(
                onClick = onOpenNews,
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Newspaper,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                },
                label = {
                    Text(
                        text = if (state.filteredWatchlistNews.isNotEmpty()) {
                            "Watchlist News (${state.filteredWatchlistNews.size})"
                        } else {
                            "Watchlist News"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                },
                modifier = Modifier.testTag("watchlist_open_news_feed")
            )
            val activeAlertCount = state.priceAlerts.count { it.isActive }
            AssistChip(
                onClick = { showAlertsManager = true },
                leadingIcon = {
                    Icon(
                        imageVector = if (activeAlertCount > 0) {
                            Icons.Rounded.NotificationsActive
                        } else {
                            Icons.Rounded.NotificationsNone
                        },
                        contentDescription = null,
                        tint = if (activeAlertCount > 0) tradingColors.amber else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                },
                label = {
                    Text(
                        text = if (activeAlertCount > 0) "Price Alerts ($activeAlertCount)" else "Price Alerts",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (activeAlertCount > 0) tradingColors.amber else MaterialTheme.colorScheme.primary
                    )
                },
                modifier = Modifier.testTag("watchlist_open_price_alerts")
            )
            AssistChip(
                onClick = { onOpenOptionChain("NIFTY") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.GridOn,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                },
                label = {
                    Text(
                        text = "Live Option Chain (OI & Greeks)",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                },
                modifier = Modifier.testTag("watchlist_open_option_chain")
            )
            listOf("BANKNIFTY", "SENSEX", "NATURALGAS", "CRUDEOIL").forEach { ocSym ->
                AssistChip(
                    onClick = { onOpenOptionChain(ocSym) },
                    label = { Text("$ocSym Chain", style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.testTag("watchlist_chain_${ocSym.lowercase()}")
                )
            }
        }

        if (state.hasAngelOneCredentials) {
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.Bolt,
                    contentDescription = null,
                    tint = tradingColors.bullish,
                    modifier = Modifier.size(16.dp)
                )
                listOf(
                    "NATURALGAS" to ("NATURALGAS" to null),
                    "NATGASMINI" to ("NATGASMINI" to null),
                    "CRUDEOIL" to ("CRUDEOIL" to null),
                    "GOLD" to ("GOLD" to null),
                    "SILVER" to ("SILVER" to null),
                    "COPPER" to ("COPPER" to null),
                    "NIFTY Opt" to ("NIFTY" to InstrumentType.OPTIONS),
                    "BANKNIFTY Opt" to ("BANKNIFTY" to InstrumentType.OPTIONS),
                    "SENSEX Opt" to ("SENSEX" to InstrumentType.OPTIONS),
                    "INFY" to ("INFY" to InstrumentType.STOCK),
                    "SBIN" to ("SBIN" to InstrumentType.STOCK)
                ).forEach { (label, pair) ->
                    AssistChip(
                        onClick = { viewModel.onQuickLiveSearch(pair.first, pair.second) },
                        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.testTag("quick_live_${label.lowercase().replace(" ", "_")}")
                    )
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        // Market segment filter row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = state.selectedSegment == null,
                onClick = { viewModel.onSegmentSelected(null) },
                label = { Text("All Markets") },
                modifier = Modifier.testTag("filter_segment_all"),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.primary
                )
            )
            MarketSegment.entries.forEach { segment ->
                FilterChip(
                    selected = state.selectedSegment == segment,
                    onClick = { viewModel.onSegmentSelected(segment) },
                    label = { Text(segment.label) },
                    modifier = Modifier.testTag("filter_segment_${segment.name.lowercase()}"),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.primary
                    )
                )
            }
        }

        // Instrument type filter row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = state.selectedType == null,
                onClick = { viewModel.onTypeSelected(null) },
                label = { Text("All Types") },
                modifier = Modifier.testTag("filter_type_all")
            )
            InstrumentType.entries.forEach { type ->
                FilterChip(
                    selected = state.selectedType == type,
                    onClick = { viewModel.onTypeSelected(type) },
                    label = { Text(type.fullLabel) },
                    modifier = Modifier.testTag("filter_type_${type.name.lowercase()}")
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        val showLiveSection = state.isLiveSearching || state.liveSearchResults.isNotEmpty() || state.liveSearchError != null

        if (state.instruments.isEmpty() && !showLiveSection) {
            EmptyStatePanel(
                icon = Icons.AutoMirrored.Rounded.TrendingUp,
                title = "No matching instruments",
                subtitle = if (state.hasAngelOneCredentials) {
                    "Type at least 2 characters (e.g. NIFTY, BANKNIFTY, INFY, SENSEX) to search live Angel One stocks & index options."
                } else {
                    "Try clearing your market or type filters to browse NSE, BSE, Crypto, and MCX."
                }
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (showLiveSection) {
                    item(key = "live_search_header") {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "ANGEL ONE LIVE SEARCH (TAP TO ADD & TRADE)",
                                style = MaterialTheme.typography.labelSmall,
                                color = tradingColors.bullish,
                                fontWeight = FontWeight.Bold
                            )
                            if (state.isLiveSearching) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp
                                )
                            }
                        }
                    }

                    state.liveSearchError?.let { err ->
                        item(key = "live_search_error") {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = err,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(10.dp)
                                )
                            }
                        }
                    }

                    items(state.liveSearchResults, key = { "live_${it.exchange}_${it.symbolToken}" }) { result ->
                        LiveSearchResultCard(
                            result = result,
                            isAdding = state.addingToken == result.symbolToken,
                            onAddAndTrade = { viewModel.addAndOpenLiveInstrument(result) }
                        )
                    }
                }

                if (!showLiveSection && state.watchlistNewsSymbols.isNotEmpty()) {
                    item(key = "watchlist_grounded_news_section") {
                        WatchlistNewsFeedSection(
                            feedState = state.watchlistNewsState,
                            watchlistSymbols = state.watchlistNewsSymbols,
                            selectedNewsSymbol = state.selectedNewsSymbol,
                            articles = state.filteredWatchlistNews,
                            onSelectNewsSymbol = viewModel::onSelectNewsSymbol,
                            onRefreshGrounded = { sym -> viewModel.refreshWatchlistNews(focusSymbol = sym, force = true) },
                            onOpenSymbol = onInstrumentClick,
                            onOpenFullFeed = onOpenNews
                        )
                    }
                }

                if (state.instruments.isNotEmpty() && showLiveSection) {
                    item(key = "watchlist_saved_header") {
                        Text(
                            text = "MY WATCHLIST",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }

                items(state.instruments, key = { it.symbol }) { instrument ->
                    val badge = state.segmentStatuses[instrument.segment]?.badge ?: SegmentBadgeMode.SIMULATED
                    val activeAlerts = state.activeAlertsBySymbol[instrument.symbol].orEmpty()
                    InstrumentRowCard(
                        instrument = instrument,
                        feedBadge = badge,
                        activeAlerts = activeAlerts,
                        onClick = { onInstrumentClick(instrument.symbol) },
                        onSetPriceAlert = { alertTargetSymbol = instrument.symbol },
                        onFilterNewsForSymbol = { viewModel.onSelectNewsSymbol(instrument.symbol) }
                    )
                }
            }
        }
    }

    if (alertTargetInstrument != null) {
        val alertsForSymbol = state.priceAlerts.filter { it.symbol == alertTargetInstrument.symbol }
        SetPriceAlertDialog(
            instrument = alertTargetInstrument,
            existingAlerts = alertsForSymbol,
            hasNotificationPermission = viewModel.hasNotificationPermission(),
            onCreateAlert = { targetPrice, condition, note ->
                viewModel.createPriceAlert(alertTargetInstrument, targetPrice, condition, note)
            },
            onDeleteAlert = viewModel::deletePriceAlert,
            onRearmAlert = viewModel::rearmPriceAlert,
            onDismiss = { alertTargetSymbol = null }
        )
    }

    if (showAlertsManager) {
        PriceAlertsManagerDialog(
            alerts = state.priceAlerts,
            ltpBySymbol = state.ltpBySymbol,
            onRearmAlert = viewModel::rearmPriceAlert,
            onDeleteAlert = viewModel::deletePriceAlert,
            onClearTriggered = viewModel::clearTriggeredPriceAlerts,
            onOpenSymbol = onInstrumentClick,
            onDismiss = { showAlertsManager = false }
        )
    }
}

@Composable
private fun LiveSearchResultCard(
    result: AngelScripSearchResult,
    isAdding: Boolean,
    onAddAndTrade: () -> Unit
) {
    val tradingColors = LocalTradingColors.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("live_search_card_${result.symbolToken}")
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = !isAdding, onClick = onAddAndTrade),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, tradingColors.bullish.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    SegmentBadge(segment = result.segment)
                    InstrumentTypeBadge(type = result.instrumentType)
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = tradingColors.bullishContainer
                    ) {
                        Text(
                            text = result.exchange,
                            style = MaterialTheme.typography.labelSmall,
                            color = tradingColors.bullish,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    if (result.lotSize > 1) {
                        Text(
                            text = "LOT ${result.lotSize}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(5.dp))
                Text(
                    text = result.tradingSymbol,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = listOfNotNull(
                        result.expiryLabel?.let { "Exp $it" },
                        result.strikePrice?.let { "Strike ${it.stripTrailingZeros().toPlainString()}" },
                        result.optionType?.name,
                        "Token ${result.symbolToken}"
                    ).joinToString(" • "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.width(10.dp))

            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (isAdding) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = "Add and trade",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Text(
                        text = if (isAdding) "Loading..." else "Trade",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InstrumentRowCard(
    instrument: Instrument,
    feedBadge: SegmentBadgeMode,
    activeAlerts: List<PriceAlert>,
    onClick: () -> Unit,
    onSetPriceAlert: () -> Unit,
    onFilterNewsForSymbol: () -> Unit
) {
    val tradingColors = LocalTradingColors.current
    val isPositive = instrument.changePercent >= BigDecimal.ZERO
    val pctColor = if (isPositive) tradingColors.bullish else tradingColors.bearish
    var showContextMenu by remember { mutableStateOf(false) }

    val tickFlashColor by animateColorAsState(
        targetValue = when (instrument.lastTickDirection) {
            1 -> tradingColors.bullish.copy(alpha = 0.14f)
            -1 -> tradingColors.bearish.copy(alpha = 0.14f)
            else -> Color.Transparent
        },
        animationSpec = tween(durationMillis = 300),
        label = "tickFlash"
    )

    Box(modifier = Modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("instrument_card_${instrument.symbol}")
                .clip(RoundedCornerShape(14.dp))
                .pointerInput(instrument.symbol) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                                event.changes.forEach { it.consume() }
                                showContextMenu = true
                            }
                        }
                    }
                }
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { showContextMenu = true }
                ),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(
                1.dp,
                if (activeAlerts.isNotEmpty()) tradingColors.amber.copy(alpha = 0.55f) else tradingColors.border
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1.25f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        SegmentBadge(segment = instrument.segment)
                        InstrumentTypeBadge(type = instrument.type)
                        FeedHealthBadge(badge = feedBadge)
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh
                        ) {
                            Text(
                                text = "LOT ${instrument.lotSize}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = instrument.symbol,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (activeAlerts.isNotEmpty()) {
                            val firstAlert = activeAlerts.first()
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = tradingColors.amberContainer,
                                modifier = Modifier
                                    .clickable(onClick = onSetPriceAlert)
                                    .testTag("instrument_alert_pill_${instrument.symbol}")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.NotificationsActive,
                                        contentDescription = "Active price alert",
                                        tint = tradingColors.amber,
                                        modifier = Modifier.size(11.dp)
                                    )
                                    Text(
                                        text = if (activeAlerts.size == 1) {
                                            "${firstAlert.condition.symbolText}${RiskMath.formatPrice(firstAlert.targetPrice, instrument.segment)}"
                                        } else {
                                            "${activeAlerts.size} Alerts"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = tradingColors.amber,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                    Text(
                        text = instrument.displayName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                SparklineChart(
                    points = instrument.sparkline,
                    isPositive = isPositive,
                    modifier = Modifier
                        .width(64.dp)
                        .height(34.dp)
                        .padding(horizontal = 4.dp)
                )

                Column(
                    modifier = Modifier.weight(0.95f),
                    horizontalAlignment = Alignment.End
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(tickFlashColor)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = RiskMath.formatPrice(instrument.ltp, instrument.segment),
                            style = MonoPriceMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    val sign = if (isPositive) "+" else ""
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (isPositive) tradingColors.bullishContainer else tradingColors.bearishContainer
                    ) {
                        Text(
                            text = "$sign${instrument.changePercent}%",
                            style = MonoPriceSmall,
                            color = pctColor,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }

        DropdownMenu(
            expanded = showContextMenu,
            onDismissRequest = { showContextMenu = false },
            modifier = Modifier.testTag("instrument_context_menu_${instrument.symbol}")
        ) {
            DropdownMenuItem(
                text = {
                    Text(
                        text = if (activeAlerts.isNotEmpty()) {
                            "Set / Manage Price Alerts (${activeAlerts.size})"
                        } else {
                            "Set Price Alert..."
                        },
                        fontWeight = FontWeight.SemiBold
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.NotificationsActive,
                        contentDescription = null,
                        tint = tradingColors.amber
                    )
                },
                onClick = {
                    showContextMenu = false
                    onSetPriceAlert()
                },
                modifier = Modifier.testTag("context_menu_set_alert_${instrument.symbol}")
            )
            DropdownMenuItem(
                text = { Text("Open Chart & Trade") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.TrendingUp,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                onClick = {
                    showContextMenu = false
                    onClick()
                },
                modifier = Modifier.testTag("context_menu_trade_${instrument.symbol}")
            )
            DropdownMenuItem(
                text = { Text("Filter Watchlist News") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Newspaper,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                onClick = {
                    showContextMenu = false
                    onFilterNewsForSymbol()
                },
                modifier = Modifier.testTag("context_menu_news_${instrument.symbol}")
            )
        }
    }
}
