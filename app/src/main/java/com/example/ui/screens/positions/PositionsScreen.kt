package com.example.ui.screens.positions

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.TableRows
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.domain.engine.MarketClock
import com.example.domain.engine.RiskMath
import com.example.domain.model.CapitalTransaction
import com.example.domain.model.InstrumentType
import com.example.domain.model.MarketSegment
import com.example.domain.model.OrderSide
import com.example.domain.model.ProductType
import com.example.domain.model.TrailMode
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.rounded.Close
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.ui.components.AiSecondOpinionCard
import com.example.ui.components.CompactTrailingSlRowIndicator
import com.example.ui.components.EmptyStatePanel
import com.example.ui.components.FeedHealthBadge
import com.example.ui.components.FeedLostWarningBanner
import com.example.ui.components.InstrumentTypeBadge
import com.example.ui.components.SegmentBadge
import com.example.ui.components.TrailingSlHeaderBadge
import com.example.ui.components.TrailingSlStatus
import com.example.ui.components.TrailingSlTelemetryCalculator
import com.example.ui.components.TrailingSlVisualTracker
import com.example.ui.components.guardGlobalHotkeysOnFocus
import com.example.ui.components.rememberKeyboardAwareModalLock
import com.example.ui.theme.LocalTradingColors
import com.example.ui.theme.MonoPriceMedium
import com.example.ui.theme.MonoPriceSmall
import kotlinx.coroutines.delay
import java.math.BigDecimal
import java.math.RoundingMode

private enum class PositionsViewMode {
    TABLE,
    CARDS
}

@Composable
fun PositionsScreen(
    onOpenDetail: (String) -> Unit,
    onOpenFeedSettings: () -> Unit = {},
    viewModel: PositionsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val riskEditorDraft by viewModel.riskEditorDraft.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val tradingColors = LocalTradingColors.current

    var averagingPositionId by rememberSaveable { mutableStateOf<String?>(null) }
    var showFundsDialog by rememberSaveable { mutableStateOf(false) }
    var viewMode by rememberSaveable { mutableStateOf(PositionsViewMode.TABLE) }
    var expandedTablePositionId by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { msg -> snackbarHostState.showSnackbar(msg) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            // Summary Header Card (India ₹ + Crypto $ Wallets)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, tradingColors.border)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "INDIA PORTFOLIO (₹ INR)",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        OutlinedButton(
                            onClick = { showFundsDialog = true },
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.testTag("manage_funds_button")
                        ) {
                            Icon(Icons.Rounded.AccountBalanceWallet, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Manage Funds", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("UNREALISED P&L", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            val unrealColor = if (state.totalUnrealisedPnl >= BigDecimal.ZERO) tradingColors.bullish else tradingColors.bearish
                            Text(
                                text = RiskMath.formatPortfolioInr(state.totalUnrealisedPnl, signed = true),
                                style = MonoPriceMedium,
                                color = unrealColor
                            )
                            Text(
                                text = "Cap ${RiskMath.formatPortfolioInr(state.virtualCapital)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("REALISED P&L", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            val realColor = if (state.realisedPnl >= BigDecimal.ZERO) tradingColors.bullish else tradingColors.bearish
                            Text(
                                text = RiskMath.formatPortfolioInr(state.realisedPnl, signed = true),
                                style = MonoPriceMedium,
                                color = realColor
                            )
                            Text(
                                text = "Free ${RiskMath.formatPortfolioInr(state.availableMargin)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    HorizontalDivider(color = tradingColors.border)

                    // Crypto USD Wallet Strip
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "CRYPTO WALLET ($ USD)",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = tradingColors.amber
                                )
                                Text(
                                    text = "Free ${RiskMath.formatPortfolioUsd(state.cryptoAvailableMargin)}",
                                    style = MonoPriceSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                val cUnrealColor = if (state.cryptoUnrealisedPnl >= BigDecimal.ZERO) tradingColors.bullish else tradingColors.bearish
                                val cRealColor = if (state.cryptoRealisedPnl >= BigDecimal.ZERO) tradingColors.bullish else tradingColors.bearish
                                Text(
                                    text = "Unreal: ${RiskMath.formatPortfolioUsd(state.cryptoUnrealisedPnl, signed = true)}",
                                    style = MonoPriceSmall,
                                    color = cUnrealColor
                                )
                                Text(
                                    text = "Real: ${RiskMath.formatPortfolioUsd(state.cryptoRealisedPnl, signed = true)}",
                                    style = MonoPriceSmall,
                                    color = cRealColor
                                )
                                Text(
                                    text = "Cap ${RiskMath.formatPortfolioUsd(state.cryptoVirtualCapital)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    OutlinedButton(
                        onClick = viewModel::simulateIntradaySquareOff,
                        modifier = Modifier.fillMaxWidth().testTag("simulate_square_off_button"),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Rounded.Schedule, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Simulate Intraday Square-Off (15:15 Eq/Fut • 15:40 Opt)", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            if (state.riskControlsSuspended) {
                FeedLostWarningBanner(
                    headline = state.suspendedBannerMessage.ifBlank { "FEED LOST — risk controls suspended" },
                    detail = "Live stream unreachable for open position(s); risk triggers paused to avoid false fills. Tap Switch to Simulated to resume risk controls offline.",
                    onReconnect = viewModel::reconnectFeed,
                    onSwitchToSimulated = viewModel::switchToSimulatedFeed
                )
                Spacer(Modifier.height(12.dp))
            }

            if (state.items.isEmpty()) {
                EmptyStatePanel(
                    icon = Icons.Rounded.AccountBalanceWallet,
                    title = "No open positions",
                    subtitle = "Select an instrument from the Watchlist to open an Intraday or Carry Forward paper position."
                )
            } else {
                val activeTslCount = state.items.count { it.position.isTslEnabled && it.position.activeSlPrice != null }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item(key = "positions_view_toolbar") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                FilterChip(
                                    selected = viewMode == PositionsViewMode.TABLE,
                                    onClick = { viewMode = PositionsViewMode.TABLE },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Rounded.TableRows,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    },
                                    label = { Text("Table") },
                                    modifier = Modifier.testTag("positions_view_table")
                                )
                                FilterChip(
                                    selected = viewMode == PositionsViewMode.CARDS,
                                    onClick = { viewMode = PositionsViewMode.CARDS },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Rounded.ViewAgenda,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    },
                                    label = { Text("Cards") },
                                    modifier = Modifier.testTag("positions_view_cards")
                                )
                            }
                            if (activeTslCount > 0) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = tradingColors.violetContainer,
                                    border = BorderStroke(0.8.dp, tradingColors.violet.copy(alpha = 0.45f)),
                                    modifier = Modifier.testTag("positions_active_tsl_counter")
                                ) {
                                    Text(
                                        text = "$activeTslCount TSL Active",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = tradingColors.violet,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }

                    item(key = "positions_ai_risk_audit") {
                        AiSecondOpinionCard(
                            title = "AI Portfolio Risk Audit",
                            subtitle = "Checks open SL/Target coverage, concentration & margin buffer",
                            ctaLabel = "Audit Open Positions with AI",
                            aiState = state.aiRiskAuditState,
                            onAnalyze = viewModel::runAiPortfolioRiskAudit,
                            ctaTestTag = "positions_ai_risk_audit_button",
                            connectionStatus = state.aiConnectionStatus,
                            onSwitchModel = viewModel::switchAiModel,
                            onOpenAiSettings = onOpenFeedSettings
                        )
                    }

                    if (viewMode == PositionsViewMode.TABLE) {
                        item(key = "positions_table_surface") {
                            PositionsTableCard(
                                items = state.items,
                                expandedPositionId = expandedTablePositionId,
                                editingPositionId = riskEditorDraft?.positionId,
                                onToggleExpand = { id ->
                                    expandedTablePositionId = if (expandedTablePositionId == id) null else id
                                },
                                onOpenRiskEditor = viewModel::openRiskEditor,
                                onOpenDetail = onOpenDetail,
                                onAverage = { averagingPositionId = it },
                                onConvertToCarryForward = viewModel::convertToCarryForward,
                                onExitMarket = viewModel::exitPosition
                            )
                        }
                    } else {
                        items(state.items, key = { it.position.id }) { item ->
                            PositionCard(
                                item = item,
                                isEditing = riskEditorDraft?.positionId == item.position.id,
                                onOpenRiskEditor = { viewModel.openRiskEditor(item.position.id) },
                                onOpenDetail = { onOpenDetail(item.position.symbol) },
                                onAverage = { averagingPositionId = item.position.id },
                                onConvertToCarryForward = { viewModel.convertToCarryForward(item.position.id) },
                                onExitMarket = { viewModel.exitPosition(item.position.id) }
                            )
                        }
                    }
                }
            }
        }
    }

    riskEditorDraft?.let { draft ->
        val liveItem = state.items.firstOrNull { it.position.id == draft.positionId }
        PositionRiskEditorDialog(
            draft = draft,
            liveLtp = liveItem?.ltp ?: draft.averageEntryPrice,
            onSlChange = viewModel::updateRiskEditorSl,
            onTargetChange = viewModel::updateRiskEditorTarget,
            onApplyPreset = { sl, tgt -> viewModel.applyRiskEditorPreset(sl, tgt) },
            onToggleTsl = viewModel::toggleRiskEditorTsl,
            onTslModeChange = viewModel::setRiskEditorTslMode,
            onTslValueChange = viewModel::updateRiskEditorTslValue,
            onToggleTt = viewModel::toggleRiskEditorTrailingTarget,
            onTtModeChange = viewModel::setRiskEditorTrailingTargetMode,
            onTtValueChange = viewModel::updateRiskEditorTrailingTargetValue,
            onSave = viewModel::saveRiskEditor,
            onDismiss = viewModel::dismissRiskEditor
        )
    }

    averagingPositionId?.let { posId ->
        val item = state.items.firstOrNull { it.position.id == posId }
        if (item == null) {
            averagingPositionId = null
        } else {
            val segAvailable = if (item.position.segment == MarketSegment.CRYPTO) {
                state.cryptoAvailableMargin
            } else {
                state.availableMargin
            }
            AveragePositionDialog(
                item = item,
                availableMargin = segAvailable,
                onDismiss = { averagingPositionId = null },
                onConfirm = { addQty ->
                    viewModel.averagePosition(item.position.id, addQty)
                    averagingPositionId = null
                }
            )
        }
    }

    if (showFundsDialog) {
        CapitalManagementDialog(
            virtualCapital = state.virtualCapital,
            availableMargin = state.availableMargin,
            cryptoVirtualCapital = state.cryptoVirtualCapital,
            cryptoAvailableMargin = state.cryptoAvailableMargin,
            recentTransactions = state.capitalTransactions.take(6),
            onDismiss = { showFundsDialog = false },
            onAddInr = { amt, note -> viewModel.addCapital(amt, note) },
            onReduceInr = { amt, note -> viewModel.reduceCapital(amt, note) },
            onAddUsd = { amt, note -> viewModel.addCryptoCapital(amt, note) },
            onReduceUsd = { amt, note -> viewModel.reduceCryptoCapital(amt, note) },
            onReset = { inr, usd -> viewModel.resetAccount(inr, usd) }
        )
    }
}

@Composable
private fun PositionsTableCard(
    items: List<PositionWithLivePnl>,
    expandedPositionId: String?,
    editingPositionId: String?,
    onToggleExpand: (String) -> Unit,
    onOpenRiskEditor: (String) -> Unit,
    onOpenDetail: (String) -> Unit,
    onAverage: (String) -> Unit,
    onConvertToCarryForward: (String) -> Unit,
    onExitMarket: (String) -> Unit
) {
    val tradingColors = LocalTradingColors.current

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("positions_table_card"),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, tradingColors.border)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Table Header Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "POSITION",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1.15f)
                )
                Text(
                    text = "LTP / P&L",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(0.95f)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "TRAILING SL",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = tradingColors.violet,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1.1f)
                )
            }

            HorizontalDivider(color = tradingColors.border)

            items.forEachIndexed { index, item ->
                val pos = item.position
                val isExpanded = expandedPositionId == pos.id
                val isEditing = editingPositionId == pos.id
                val isProfit = item.unrealisedPnl >= BigDecimal.ZERO
                val pnlColor = if (isProfit) tradingColors.bullish else tradingColors.bearish

                val tslTelemetry = remember(
                    pos.id,
                    pos.isTslEnabled,
                    pos.activeSlPrice,
                    pos.tslBestPrice,
                    pos.tslTrailMode,
                    pos.tslTrailValue,
                    pos.averageEntryPrice,
                    pos.quantity,
                    item.ltp
                ) {
                    TrailingSlTelemetryCalculator.compute(pos, item.ltp)
                }

                // Live LTP tick flash on each market tick
                var lastLtp by remember(pos.id) { mutableStateOf(item.ltp) }
                var tickFlashDir by remember(pos.id) { mutableStateOf(0) }
                LaunchedEffect(item.ltp) {
                    val cmp = item.ltp.compareTo(lastLtp)
                    if (cmp != 0) {
                        tickFlashDir = if (cmp > 0) 1 else -1
                        lastLtp = item.ltp
                        delay(420L)
                        tickFlashDir = 0
                    }
                }
                val ltpFlashColor by animateColorAsState(
                    targetValue = when (tickFlashDir) {
                        1 -> tradingColors.bullish.copy(alpha = 0.16f)
                        -1 -> tradingColors.bearish.copy(alpha = 0.16f)
                        else -> Color.Transparent
                    },
                    animationSpec = tween(durationMillis = 220),
                    label = "posTableLtpFlash_${pos.id}"
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (isEditing) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.16f)
                            else Color.Transparent
                        )
                        .testTag("position_table_row_${pos.symbol}")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 64.dp)
                            .clickable { onToggleExpand(pos.id) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Column 1: Symbol, Side, Qty, Avg
                        Column(
                            modifier = Modifier.weight(1.15f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = pos.symbol,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = if (pos.side == OrderSide.BUY) tradingColors.bullishContainer else tradingColors.bearishContainer
                                ) {
                                    Text(
                                        text = if (pos.side == OrderSide.BUY) "B" else "S",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (pos.side == OrderSide.BUY) tradingColors.bullish else tradingColors.bearish,
                                        fontWeight = FontWeight.ExtraBold,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                    )
                                }
                            }
                            Text(
                                text = "${pos.quantity} • ${pos.productType.shortCode}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                            Text(
                                text = "Avg ${RiskMath.formatPrice(pos.averageEntryPrice, pos.segment)}",
                                style = MonoPriceSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        // Column 2: Live LTP (tick-flashing) + Unrealised P&L
                        Column(
                            modifier = Modifier.weight(0.95f),
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = RiskMath.formatPrice(item.ltp, pos.segment),
                                style = MonoPriceSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(ltpFlashColor)
                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                            Text(
                                text = RiskMath.formatSignedCurrency(item.unrealisedPnl, pos.segment),
                                style = MonoPriceSmall,
                                fontWeight = FontWeight.Bold,
                                color = pnlColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            pos.activeTargetPrice?.let { tgt ->
                                Text(
                                    text = "Tgt ${RiskMath.formatPrice(tgt, pos.segment)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = tradingColors.bullish,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Spacer(Modifier.width(8.dp))

                        // Column 3: Dynamic Active Trailing Stop Loss Visual Indicator Cell
                        if (tslTelemetry != null) {
                            CompactTrailingSlRowIndicator(
                                symbol = pos.symbol,
                                telemetry = tslTelemetry,
                                segment = pos.segment,
                                side = pos.side,
                                onClick = { onOpenRiskEditor(pos.id) },
                                modifier = Modifier.weight(1.1f)
                            )
                        } else {
                            Surface(
                                modifier = Modifier
                                    .weight(1.1f)
                                    .heightIn(min = 48.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { onOpenRiskEditor(pos.id) }
                                    .testTag("positions_table_sl_cell_${pos.symbol}"),
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                border = BorderStroke(0.8.dp, tradingColors.border)
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                    horizontalAlignment = Alignment.End,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Text(
                                        text = if (pos.activeSlPrice != null) "FIXED SL" else "NO SL",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (pos.activeSlPrice != null) tradingColors.bearish else MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = pos.activeSlPrice?.let { RiskMath.formatPrice(it, pos.segment) } ?: "+ Add TSL",
                                        style = MonoPriceSmall,
                                        color = if (pos.activeSlPrice != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }

                    // Expandable live TSL track + quick action bar
                    AnimatedVisibility(visible = isExpanded) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (tslTelemetry != null) {
                                TrailingSlVisualTracker(
                                    symbol = pos.symbol,
                                    telemetry = tslTelemetry,
                                    segment = pos.segment,
                                    side = pos.side,
                                    trailMode = pos.tslTrailMode,
                                    trailValue = pos.tslTrailValue
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { onOpenRiskEditor(pos.id) },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)
                                ) {
                                    Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("SL / TSL", style = MaterialTheme.typography.labelSmall)
                                }
                                OutlinedButton(
                                    onClick = { onAverage(pos.id) },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)
                                ) {
                                    Icon(Icons.Rounded.Layers, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Avg", style = MaterialTheme.typography.labelSmall)
                                }
                                OutlinedButton(
                                    onClick = { onOpenDetail(pos.symbol) },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)
                                ) {
                                    Text("Ticket", style = MaterialTheme.typography.labelSmall)
                                }
                                Button(
                                    onClick = { onExitMarket(pos.id) },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = tradingColors.bearish, contentColor = Color.White),
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)
                                ) {
                                    Text("Exit", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                }
                            }
                            if (pos.productType == ProductType.INTRADAY && item.supportsCarryForward) {
                                OutlinedButton(
                                    onClick = { onConvertToCarryForward(pos.id) },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                                ) {
                                    Icon(Icons.Rounded.SwapHoriz, contentDescription = null, modifier = Modifier.size(15.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Convert to Carry Forward", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }

                if (index < items.lastIndex) {
                    HorizontalDivider(color = tradingColors.border.copy(alpha = 0.65f))
                }
            }
        }
    }
}

@Composable
private fun PositionCard(
    item: PositionWithLivePnl,
    isEditing: Boolean,
    onOpenRiskEditor: () -> Unit,
    onOpenDetail: () -> Unit,
    onAverage: () -> Unit,
    onConvertToCarryForward: () -> Unit,
    onExitMarket: () -> Unit
) {
    val pos = item.position
    val tradingColors = LocalTradingColors.current
    val isProfit = item.unrealisedPnl >= BigDecimal.ZERO
    val pnlColor = if (isProfit) tradingColors.bullish else tradingColors.bearish
    val tslTelemetry = remember(
        pos.id,
        pos.isTslEnabled,
        pos.activeSlPrice,
        pos.tslBestPrice,
        pos.tslTrailMode,
        pos.tslTrailValue,
        pos.averageEntryPrice,
        pos.quantity,
        item.ltp
    ) {
        TrailingSlTelemetryCalculator.compute(pos, item.ltp)
    }
    val cardBorderColor = when {
        isEditing -> MaterialTheme.colorScheme.primary
        tslTelemetry?.status == TrailingSlStatus.PROFIT_LOCKED -> tradingColors.bullish.copy(alpha = 0.65f)
        tslTelemetry?.status == TrailingSlStatus.NEAR_TRIGGER -> tradingColors.amber.copy(alpha = 0.70f)
        tslTelemetry != null -> tradingColors.violet.copy(alpha = 0.60f)
        else -> tradingColors.border
    }

    Surface(
        modifier = Modifier.fillMaxWidth().testTag("position_card_${pos.symbol}"),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(if (tslTelemetry != null || isEditing) 1.3.dp else 1.dp, cardBorderColor)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        SegmentBadge(pos.segment)
                        InstrumentTypeBadge(pos.instrumentType)
                        FeedHealthBadge(item.feedBadge)
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh
                        ) {
                            Text(
                                text = "${pos.productType.shortCode} • ${pos.validityType.label}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    TextButton(onClick = onOpenDetail, contentPadding = PaddingValues(0.dp)) {
                        Text(pos.symbol, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (pos.side == OrderSide.BUY) tradingColors.bullishContainer else tradingColors.bearishContainer
                    ) {
                        Text(
                            text = "${pos.side.name} • Qty: ${RiskMath.formatQtyWithLots(pos.quantity, pos.lotSize)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (pos.side == OrderSide.BUY) tradingColors.bullish else tradingColors.bearish,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Avg ${RiskMath.formatPrice(pos.averageEntryPrice, pos.segment)}  •  LTP ${RiskMath.formatPrice(item.ltp, pos.segment)}",
                        style = MonoPriceSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (pos.productType == ProductType.INTRADAY) {
                        Text(
                            text = "Auto Square-Off: ${MarketClock.intradaySquareOffLabel(pos.segment, pos.instrumentType)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = tradingColors.amber
                        )
                    }
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = RiskMath.formatSignedCurrency(item.unrealisedPnl, pos.segment),
                        style = MonoPriceMedium,
                        color = pnlColor
                    )
                    if (tslTelemetry != null) {
                        Spacer(Modifier.height(4.dp))
                        TrailingSlHeaderBadge(
                            status = tslTelemetry.status,
                            modifier = Modifier.testTag("tsl_header_badge_${pos.symbol}")
                        )
                    }
                    if (pos.ocoGroupId != null) {
                        Spacer(Modifier.height(4.dp))
                        Surface(shape = RoundedCornerShape(6.dp), color = tradingColors.violetContainer) {
                            Text(
                                text = "OCO ACTIVE",
                                style = MaterialTheme.typography.labelSmall,
                                color = tradingColors.violet,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            // Active Risk Pills
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val slAccent = when (tslTelemetry?.status) {
                    TrailingSlStatus.PROFIT_LOCKED -> tradingColors.bullish
                    TrailingSlStatus.NEAR_TRIGGER -> tradingColors.amber
                    TrailingSlStatus.BREAKEVEN -> tradingColors.cryptoCyan
                    TrailingSlStatus.TRAILING_ACTIVE -> tradingColors.violet
                    null -> tradingColors.bearish
                }
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    color = if (tslTelemetry != null) slAccent.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceContainer,
                    border = if (tslTelemetry != null) BorderStroke(1.dp, slAccent.copy(alpha = 0.45f)) else null
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        val ratchetArrow = if (pos.side == OrderSide.BUY) "↑" else "↓"
                        Text(
                            text = if (pos.isTslEnabled) "TRAILING SL $ratchetArrow" else "STOP LOSS",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (pos.isTslEnabled) FontWeight.Bold else FontWeight.Medium,
                            color = slAccent
                        )
                        Text(
                            text = pos.activeSlPrice?.let { RiskMath.formatPrice(it, pos.segment) } ?: "None",
                            style = MonoPriceSmall,
                            color = if (tslTelemetry != null) slAccent else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        val tgtLabel = when {
                            pos.trailingTargetActivated -> "TRAILING EXIT ARMED"
                            pos.isTrailingTargetEnabled -> "TRAILING TARGET"
                            else -> "TARGET"
                        }
                        Text(
                            text = tgtLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = tradingColors.bullish
                        )
                        val displayPrice = if (pos.trailingTargetActivated) {
                            pos.trailingTargetExitTrigger
                        } else {
                            pos.activeTargetPrice
                        }
                        Text(
                            text = displayPrice?.let { RiskMath.formatPrice(it, pos.segment) } ?: "None",
                            style = MonoPriceSmall
                        )
                    }
                }
            }

            if (tslTelemetry != null) {
                TrailingSlVisualTracker(
                    symbol = pos.symbol,
                    telemetry = tslTelemetry,
                    segment = pos.segment,
                    side = pos.side,
                    trailMode = pos.tslTrailMode,
                    trailValue = pos.tslTrailValue
                )
            }

            // Action Buttons Row 1: Average (+Lots) & Edit SL/Tgt
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onAverage,
                    modifier = Modifier.weight(1f).testTag("average_position_button_${pos.symbol}"),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Rounded.Layers, contentDescription = null, modifier = Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(4.dp))
                    Text("Average (+Lots)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }

                OutlinedButton(
                    onClick = onOpenRiskEditor,
                    modifier = Modifier.weight(1f).testTag("edit_risk_button_${pos.symbol}"),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Edit SL/Tgt", style = MaterialTheme.typography.labelMedium)
                }
            }

            // Action Buttons Row 2: To Carry Fwd & Exit Market
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (pos.productType == ProductType.INTRADAY && item.supportsCarryForward) {
                    OutlinedButton(
                        onClick = onConvertToCarryForward,
                        modifier = Modifier.weight(1f).testTag("convert_cf_button_${pos.symbol}"),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Rounded.SwapHoriz, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("To Carry Fwd", style = MaterialTheme.typography.labelMedium)
                    }
                }

                Button(
                    onClick = onExitMarket,
                    modifier = Modifier.weight(1f).testTag("exit_position_button_${pos.symbol}"),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = tradingColors.bearish, contentColor = Color.White),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Text("Exit Market", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun PositionRiskEditorDialog(
    draft: PositionRiskEditorDraft,
    liveLtp: BigDecimal,
    onSlChange: (String) -> Unit,
    onTargetChange: (String) -> Unit,
    onApplyPreset: (BigDecimal?, BigDecimal?) -> Unit,
    onToggleTsl: (Boolean) -> Unit,
    onTslModeChange: (TrailMode) -> Unit,
    onTslValueChange: (String) -> Unit,
    onToggleTt: (Boolean) -> Unit,
    onTtModeChange: (TrailMode) -> Unit,
    onTtValueChange: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    val tradingColors = LocalTradingColors.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    // Keyboard-aware modal lock: blocks tab switching & disables outside-tap dismissal while IME is open
    val modalLock = rememberKeyboardAwareModalLock(isOpen = true)

    val isBuy = draft.side == OrderSide.BUY
    val parsedSl = draft.slText.trim().takeIf { it.isNotEmpty() }?.toBigDecimalOrNull()
    val parsedTarget = draft.targetText.trim().takeIf { it.isNotEmpty() }?.toBigDecimalOrNull()
    val slTriggersNow = parsedSl != null && ((isBuy && parsedSl >= liveLtp) || (!isBuy && parsedSl <= liveLtp))
    val targetTriggersNow = parsedTarget != null && ((isBuy && parsedTarget <= liveLtp) || (!isBuy && parsedTarget >= liveLtp))

    Dialog(
        onDismissRequest = { if (modalLock.allowBackdropDismiss && !draft.isSaving) onDismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = modalLock.allowBackdropDismiss,
            dismissOnBackPress = !draft.isSaving
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .imePadding()
                .testTag("position_risk_editor_modal"),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, tradingColors.border)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Modify SL & Target",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${draft.symbol} • ${draft.side.name} • Avg ${RiskMath.formatPrice(draft.averageEntryPrice, draft.segment)} • Live ${RiskMath.formatPrice(liveLtp, draft.segment)}",
                            style = MonoPriceSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = {
                        focusManager.clearFocus(force = true)
                        keyboardController?.hide()
                        onDismiss()
                    }) {
                        Icon(Icons.Rounded.Close, contentDescription = "Close SL/Target editor")
                    }
                }

                // Quick 1-tap SL & Target presets anchored to live LTP / Entry
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val slFactor1 = if (isBuy) BigDecimal("0.990") else BigDecimal("1.010")
                    val slFactor2 = if (isBuy) BigDecimal("0.980") else BigDecimal("1.020")
                    val tgtFactor1 = if (isBuy) BigDecimal("1.015") else BigDecimal("0.985")
                    val tgtFactor2 = if (isBuy) BigDecimal("1.030") else BigDecimal("0.970")

                    FilterChip(
                        selected = false,
                        onClick = { onApplyPreset(liveLtp.multiply(slFactor1), null) },
                        label = { Text("SL 1%", style = MaterialTheme.typography.labelSmall) }
                    )
                    FilterChip(
                        selected = false,
                        onClick = { onApplyPreset(liveLtp.multiply(slFactor2), null) },
                        label = { Text("SL 2%", style = MaterialTheme.typography.labelSmall) }
                    )
                    FilterChip(
                        selected = false,
                        onClick = { onApplyPreset(draft.averageEntryPrice, null) },
                        label = { Text("SL Cost", style = MaterialTheme.typography.labelSmall) }
                    )
                    FilterChip(
                        selected = false,
                        onClick = { onApplyPreset(null, liveLtp.multiply(tgtFactor1)) },
                        label = { Text("Tgt 1.5%", style = MaterialTheme.typography.labelSmall) }
                    )
                    FilterChip(
                        selected = false,
                        onClick = { onApplyPreset(null, liveLtp.multiply(tgtFactor2)) },
                        label = { Text("Tgt 3%", style = MaterialTheme.typography.labelSmall) }
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = draft.slText,
                        onValueChange = onSlChange,
                        label = { Text("SL Price (${draft.segment.currencySymbol})") },
                        placeholder = { Text(if (isBuy) "< ${liveLtp.toPlainString()}" else "> ${liveLtp.toPlainString()}") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        isError = slTriggersNow,
                        modifier = Modifier
                            .weight(1f)
                            .guardGlobalHotkeysOnFocus()
                            .testTag("inline_sl_input_${draft.symbol}")
                    )
                    OutlinedTextField(
                        value = draft.targetText,
                        onValueChange = onTargetChange,
                        label = { Text("Target Price (${draft.segment.currencySymbol})") },
                        placeholder = { Text(if (isBuy) "> ${liveLtp.toPlainString()}" else "< ${liveLtp.toPlainString()}") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        isError = targetTriggersNow,
                        modifier = Modifier
                            .weight(1f)
                            .guardGlobalHotkeysOnFocus()
                            .testTag("inline_target_input_${draft.symbol}")
                    )
                }

                if (slTriggersNow || targetTriggersNow) {
                    Text(
                        text = if (slTriggersNow) {
                            "Heads up: SL is past live LTP (${RiskMath.formatPrice(liveLtp, draft.segment)}) and will trigger immediately on save."
                        } else {
                            "Heads up: Target is already past live LTP (${RiskMath.formatPrice(liveLtp, draft.segment)}) and will trigger immediately on save."
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = tradingColors.amber
                    )
                }

                draft.validationError?.let { err ->
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = tradingColors.bearishContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = err,
                            style = MaterialTheme.typography.labelSmall,
                            color = tradingColors.bearish,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Trailing SL", style = MaterialTheme.typography.titleSmall)
                    Switch(checked = draft.tslEnabled, onCheckedChange = onToggleTsl)
                }
                if (draft.tslEnabled) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        TrailMode.entries.forEach { mode ->
                            FilterChip(selected = draft.tslMode == mode, onClick = { onTslModeChange(mode) }, label = { Text(mode.label) })
                        }
                        OutlinedTextField(
                            value = draft.tslValueText,
                            onValueChange = onTslValueChange,
                            label = { Text("Trail") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.weight(1f).guardGlobalHotkeysOnFocus()
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Trailing Target", style = MaterialTheme.typography.titleSmall)
                    Switch(checked = draft.ttEnabled, onCheckedChange = onToggleTt)
                }
                if (draft.ttEnabled) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        TrailMode.entries.forEach { mode ->
                            FilterChip(selected = draft.ttMode == mode, onClick = { onTtModeChange(mode) }, label = { Text(mode.label) })
                        }
                        OutlinedTextField(
                            value = draft.ttValueText,
                            onValueChange = onTtValueChange,
                            label = { Text("Trail") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.weight(1f).guardGlobalHotkeysOnFocus()
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            focusManager.clearFocus(force = true)
                            keyboardController?.hide()
                            onDismiss()
                        },
                        enabled = !draft.isSaving,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = {
                            focusManager.clearFocus(force = true)
                            keyboardController?.hide()
                            onSave()
                        },
                        enabled = !draft.isSaving,
                        modifier = Modifier.weight(1.4f).testTag("save_inline_risk_${draft.symbol}"),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(if (draft.isSaving) "Saving..." else "Save SL / Target")
                    }
                }
            }
        }
    }
}

@Composable
private fun AveragePositionDialog(
    item: PositionWithLivePnl,
    availableMargin: BigDecimal,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    val pos = item.position
    val lot = pos.lotSize.coerceAtLeast(1)
    val tradingColors = LocalTradingColors.current
    var addQtyText by remember(pos.id) { mutableStateOf(lot.toString()) }
    val addQty = (addQtyText.toIntOrNull() ?: 0).coerceAtLeast(0)

    val newTotalQty = pos.quantity + addQty
    val oldCost = pos.averageEntryPrice.multiply(BigDecimal(pos.quantity))
    val addCost = item.ltp.multiply(BigDecimal(addQty))
    val projectedAvg = if (newTotalQty > 0) {
        oldCost.add(addCost).divide(BigDecimal(newTotalQty), 2, RoundingMode.HALF_UP)
    } else {
        pos.averageEntryPrice
    }
    val estExtraMargin = if (pos.productType == ProductType.INTRADAY && pos.instrumentType != InstrumentType.OPTIONS) {
        addCost.multiply(BigDecimal("0.25"))
    } else {
        addCost
    }
    val insufficient = estExtraMargin > availableMargin

    val modalLock = rememberKeyboardAwareModalLock(isOpen = true)

    AlertDialog(
        onDismissRequest = { if (modalLock.allowBackdropDismiss) onDismiss() },
        properties = DialogProperties(dismissOnClickOutside = modalLock.allowBackdropDismiss),
        title = {
            Column {
                Text("Average Running Trade", fontWeight = FontWeight.Bold)
                Text(
                    "${pos.symbol} • ${pos.side.name} • Lot Size $lot",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Current: ${RiskMath.formatQtyWithLots(pos.quantity, lot)} @ ${RiskMath.formatPrice(pos.averageEntryPrice, pos.segment)}",
                    style = MonoPriceSmall
                )
                Text(
                    "Live LTP: ${RiskMath.formatPrice(item.ltp, pos.segment)}",
                    style = MonoPriceSmall,
                    color = MaterialTheme.colorScheme.primary
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(
                        onClick = {
                            val cur = addQtyText.toIntOrNull() ?: lot
                            addQtyText = (cur - lot).coerceAtLeast(lot).toString()
                        },
                        modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(10.dp))
                    ) {
                        Icon(Icons.Rounded.Remove, contentDescription = "Decrease additional quantity")
                    }
                    OutlinedTextField(
                        value = addQtyText,
                        onValueChange = { addQtyText = it.filter(Char::isDigit) },
                        label = { Text("Add Qty (Lot $lot)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f).guardGlobalHotkeysOnFocus().testTag("average_qty_input")
                    )
                    IconButton(
                        onClick = {
                            val cur = addQtyText.toIntOrNull() ?: 0
                            addQtyText = (cur + lot).toString()
                        },
                        modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(10.dp))
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = "Increase additional quantity")
                    }
                }

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("New Total Qty", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(RiskMath.formatQtyWithLots(newTotalQty, lot), style = MonoPriceSmall, fontWeight = FontWeight.Bold)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Projected Avg Price", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(RiskMath.formatPrice(projectedAvg, pos.segment), style = MonoPriceSmall, fontWeight = FontWeight.Bold, color = tradingColors.bullish)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Incremental Margin", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                RiskMath.formatCurrency(estExtraMargin, pos.segment),
                                style = MonoPriceSmall,
                                color = if (insufficient) tradingColors.bearish else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
                Text(
                    "Linked SL & Target order quantities automatically update to match the new position size.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(addQty) },
                enabled = addQty > 0 && !insufficient,
                modifier = Modifier.testTag("confirm_average_position_button")
            ) {
                Text("Add & Average")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun CapitalManagementDialog(
    virtualCapital: BigDecimal,
    availableMargin: BigDecimal,
    cryptoVirtualCapital: BigDecimal,
    cryptoAvailableMargin: BigDecimal,
    recentTransactions: List<CapitalTransaction>,
    onDismiss: () -> Unit,
    onAddInr: (BigDecimal, String) -> Unit,
    onReduceInr: (BigDecimal, String) -> Unit,
    onAddUsd: (BigDecimal, String) -> Unit,
    onReduceUsd: (BigDecimal, String) -> Unit,
    onReset: (BigDecimal, BigDecimal) -> Unit
) {
    val tradingColors = LocalTradingColors.current
    var isCryptoWallet by remember { mutableStateOf(false) }
    var amountText by remember(isCryptoWallet) { mutableStateOf(if (isCryptoWallet) "2500" else "100000") }
    var noteText by remember { mutableStateOf("") }
    var resetInrText by remember { mutableStateOf("1000000") }
    var resetUsdText by remember { mutableStateOf("10000") }

    val modalLock = rememberKeyboardAwareModalLock(isOpen = true)

    AlertDialog(
        onDismissRequest = { if (modalLock.allowBackdropDismiss) onDismiss() },
        properties = DialogProperties(dismissOnClickOutside = modalLock.allowBackdropDismiss),
        title = { Text("Manage Paper Capital", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    FilterChip(
                        selected = !isCryptoWallet,
                        onClick = { isCryptoWallet = false },
                        label = { Text("India (₹ INR)") },
                        modifier = Modifier.weight(1f).testTag("wallet_tab_inr")
                    )
                    FilterChip(
                        selected = isCryptoWallet,
                        onClick = { isCryptoWallet = true },
                        label = { Text("Crypto ($ USD)") },
                        modifier = Modifier.weight(1f).testTag("wallet_tab_usd")
                    )
                }

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (isCryptoWallet) {
                            Text("Crypto Capital: ${RiskMath.formatPortfolioUsd(cryptoVirtualCapital)}", style = MonoPriceSmall, fontWeight = FontWeight.Bold)
                            Text("Free Crypto Margin: ${RiskMath.formatPortfolioUsd(cryptoAvailableMargin)}", style = MonoPriceSmall, color = tradingColors.amber)
                        } else {
                            Text("India Capital: ${RiskMath.formatPortfolioInr(virtualCapital)}", style = MonoPriceSmall, fontWeight = FontWeight.Bold)
                            Text("Free India Margin: ${RiskMath.formatPortfolioInr(availableMargin)}", style = MonoPriceSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }

                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it.filter { ch -> ch.isDigit() || ch == '.' } },
                    label = { Text(if (isCryptoWallet) "Amount ($ USD)" else "Amount (₹ INR)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().guardGlobalHotkeysOnFocus().testTag("capital_amount_input")
                )
                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    label = { Text("Note (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().guardGlobalHotkeysOnFocus()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = {
                            amountText.toBigDecimalOrNull()?.let { amt ->
                                val note = noteText.ifBlank { if (isCryptoWallet) "Crypto USD top-up" else "Capital top-up" }
                                if (isCryptoWallet) onAddUsd(amt, note) else onAddInr(amt, note)
                            }
                        },
                        modifier = Modifier.weight(1f).testTag("add_capital_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = tradingColors.bullish, contentColor = Color.White)
                    ) {
                        Text("+ Add")
                    }
                    OutlinedButton(
                        onClick = {
                            amountText.toBigDecimalOrNull()?.let { amt ->
                                val note = noteText.ifBlank { if (isCryptoWallet) "Crypto USD withdrawal" else "Capital withdrawal" }
                                if (isCryptoWallet) onReduceUsd(amt, note) else onReduceInr(amt, note)
                            }
                        },
                        modifier = Modifier.weight(1f).testTag("reduce_capital_button")
                    ) {
                        Text("- Reduce", color = tradingColors.bearish)
                    }
                }

                HorizontalDivider(color = tradingColors.border)

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = resetInrText,
                        onValueChange = { resetInrText = it.filter { ch -> ch.isDigit() || ch == '.' } },
                        label = { Text("Reset ₹") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f).guardGlobalHotkeysOnFocus()
                    )
                    OutlinedTextField(
                        value = resetUsdText,
                        onValueChange = { resetUsdText = it.filter { ch -> ch.isDigit() || ch == '.' } },
                        label = { Text("Reset $") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f).guardGlobalHotkeysOnFocus()
                    )
                    Button(
                        onClick = {
                            val targetInr = resetInrText.toBigDecimalOrNull() ?: BigDecimal("1000000")
                            val targetUsd = resetUsdText.toBigDecimalOrNull() ?: BigDecimal("10000")
                            onReset(targetInr, targetUsd)
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = tradingColors.bearish, contentColor = Color.White),
                        modifier = Modifier.testTag("reset_capital_dialog_button")
                    ) {
                        Text("Reset")
                    }
                }

                if (recentTransactions.isNotEmpty()) {
                    Text("RECENT CAPITAL RECORD", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    recentTransactions.forEach { tx ->
                        val formattedAmt = if (tx.currency.equals("USD", ignoreCase = true)) {
                            RiskMath.formatPortfolioUsd(tx.amount)
                        } else {
                            RiskMath.formatPortfolioInr(tx.amount)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                "${tx.type.label} (${tx.currency}) • ${MarketClock.formatIstDate(tx.timestampMillis)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                formattedAmt,
                                style = MonoPriceSmall,
                                color = when (tx.type) {
                                    com.example.domain.model.CapitalTransactionType.ADD -> tradingColors.bullish
                                    com.example.domain.model.CapitalTransactionType.REDUCE -> tradingColors.bearish
                                    com.example.domain.model.CapitalTransactionType.RESET -> MaterialTheme.colorScheme.primary
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    )
}

private fun sanitizeRiskDecimal(input: String): String {
    val filtered = input.filter { it.isDigit() || it == '.' }
    val firstDot = filtered.indexOf('.')
    return if (firstDot == -1) filtered else {
        filtered.substring(0, firstDot + 1) + filtered.substring(firstDot + 1).replace(".", "")
    }
}

