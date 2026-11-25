package com.example.ui.screens.positions

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import com.example.ui.components.AiSecondOpinionCard
import com.example.ui.components.EmptyStatePanel
import com.example.ui.components.FeedHealthBadge
import com.example.ui.components.FeedLostWarningBanner
import com.example.ui.components.InstrumentTypeBadge
import com.example.ui.components.SegmentBadge
import com.example.ui.theme.LocalTradingColors
import com.example.ui.theme.MonoPriceMedium
import com.example.ui.theme.MonoPriceSmall
import java.math.BigDecimal
import java.math.RoundingMode

@Composable
fun PositionsScreen(
    onOpenDetail: (String) -> Unit,
    onOpenFeedSettings: () -> Unit = {},
    viewModel: PositionsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val tradingColors = LocalTradingColors.current

    var averagingItem by remember { mutableStateOf<PositionWithLivePnl?>(null) }
    var showFundsDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { msg -> snackbarHostState.showSnackbar(msg) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = Color.Transparent
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
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
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
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
                    items(state.items, key = { it.position.id }) { item ->
                        PositionCard(
                            item = item,
                            onOpenDetail = { onOpenDetail(item.position.symbol) },
                            onAverage = { averagingItem = item },
                            onSaveRisk = { sl, tgt, tslOn, tslMode, tslVal, ttOn, ttMode, ttVal ->
                                viewModel.saveInlineRiskControls(
                                    positionId = item.position.id,
                                    slPrice = sl,
                                    targetPrice = tgt,
                                    isTslEnabled = tslOn,
                                    tslMode = tslMode,
                                    tslValue = tslVal,
                                    isTrailingTargetEnabled = ttOn,
                                    trailingTargetMode = ttMode,
                                    trailingTargetValue = ttVal
                                )
                            },
                            onConvertToCarryForward = { viewModel.convertToCarryForward(item.position.id) },
                            onExitMarket = { viewModel.exitPosition(item.position.id) }
                        )
                    }
                }
            }
        }
    }

    averagingItem?.let { item ->
        val segAvailable = if (item.position.segment == MarketSegment.CRYPTO) {
            state.cryptoAvailableMargin
        } else {
            state.availableMargin
        }
        AveragePositionDialog(
            item = item,
            availableMargin = segAvailable,
            onDismiss = { averagingItem = null },
            onConfirm = { addQty ->
                viewModel.averagePosition(item.position.id, addQty)
                averagingItem = null
            }
        )
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
private fun PositionCard(
    item: PositionWithLivePnl,
    onOpenDetail: () -> Unit,
    onAverage: () -> Unit,
    onSaveRisk: (BigDecimal?, BigDecimal?, Boolean, TrailMode, BigDecimal, Boolean, TrailMode, BigDecimal) -> Unit,
    onConvertToCarryForward: () -> Unit,
    onExitMarket: () -> Unit
) {
    val pos = item.position
    val tradingColors = LocalTradingColors.current
    val isProfit = item.unrealisedPnl >= BigDecimal.ZERO
    val pnlColor = if (isProfit) tradingColors.bullish else tradingColors.bearish

    var expandedEditor by remember(pos.id) { mutableStateOf(false) }
    var slText by remember(pos.id) { mutableStateOf(pos.activeSlPrice?.toPlainString() ?: "") }
    var targetText by remember(pos.id) { mutableStateOf(pos.activeTargetPrice?.toPlainString() ?: "") }
    var tslEnabled by remember(pos.id) { mutableStateOf(pos.isTslEnabled) }
    var tslMode by remember(pos.id) { mutableStateOf(pos.tslTrailMode) }
    var tslValueText by remember(pos.id) { mutableStateOf(pos.tslTrailValue.takeIf { it > BigDecimal.ZERO }?.toPlainString() ?: "0.8") }
    var ttEnabled by remember(pos.id) { mutableStateOf(pos.isTrailingTargetEnabled) }
    var ttMode by remember(pos.id) { mutableStateOf(pos.trailingTargetTrailMode) }
    var ttValueText by remember(pos.id) { mutableStateOf(pos.trailingTargetTrailValue.takeIf { it > BigDecimal.ZERO }?.toPlainString() ?: "0.8") }

    // Sync persisted risk values when opening the editor, without clobbering active typing on ticks
    LaunchedEffect(expandedEditor, pos.id) {
        if (expandedEditor) {
            slText = pos.activeSlPrice?.toPlainString() ?: ""
            targetText = pos.activeTargetPrice?.toPlainString() ?: ""
            tslEnabled = pos.isTslEnabled
            tslMode = pos.tslTrailMode
            tslValueText = pos.tslTrailValue.takeIf { it > BigDecimal.ZERO }?.toPlainString() ?: "0.8"
            ttEnabled = pos.isTrailingTargetEnabled
            ttMode = pos.trailingTargetTrailMode
            ttValueText = pos.trailingTargetTrailValue.takeIf { it > BigDecimal.ZERO }?.toPlainString() ?: "0.8"
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth().testTag("position_card_${pos.symbol}"),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, tradingColors.border)
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
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text(
                            text = if (pos.isTslEnabled) "TRAILING SL" else "STOP LOSS",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (pos.isTslEnabled) tradingColors.violet else tradingColors.bearish
                        )
                        Text(
                            text = pos.activeSlPrice?.let { RiskMath.formatPrice(it, pos.segment) } ?: "None",
                            style = MonoPriceSmall
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
                    onClick = { expandedEditor = !expandedEditor },
                    modifier = Modifier.weight(1f).testTag("edit_risk_button_${pos.symbol}"),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (expandedEditor) "Hide Risk" else "Edit SL/Tgt", style = MaterialTheme.typography.labelMedium)
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

            // Inline Risk Editor
            AnimatedVisibility(visible = expandedEditor) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = slText,
                            onValueChange = { slText = sanitizeRiskDecimal(it) },
                            label = { Text("SL Price (${pos.segment.currencySymbol})") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.weight(1f).testTag("inline_sl_input_${pos.symbol}")
                        )
                        OutlinedTextField(
                            value = targetText,
                            onValueChange = { targetText = sanitizeRiskDecimal(it) },
                            label = { Text("Target Price (${pos.segment.currencySymbol})") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.weight(1f).testTag("inline_target_input_${pos.symbol}")
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Trailing SL", style = MaterialTheme.typography.titleSmall)
                        Switch(checked = tslEnabled, onCheckedChange = { tslEnabled = it })
                    }
                    if (tslEnabled) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            TrailMode.entries.forEach { mode ->
                                FilterChip(selected = tslMode == mode, onClick = { tslMode = mode }, label = { Text(mode.label) })
                            }
                            OutlinedTextField(
                                value = tslValueText,
                                onValueChange = { tslValueText = sanitizeRiskDecimal(it) },
                                label = { Text("Trail") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Trailing Target", style = MaterialTheme.typography.titleSmall)
                        Switch(checked = ttEnabled, onCheckedChange = { ttEnabled = it })
                    }
                    if (ttEnabled) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            TrailMode.entries.forEach { mode ->
                                FilterChip(selected = ttMode == mode, onClick = { ttMode = mode }, label = { Text(mode.label) })
                            }
                            OutlinedTextField(
                                value = ttValueText,
                                onValueChange = { ttValueText = sanitizeRiskDecimal(it) },
                                label = { Text("Trail") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    Button(
                        onClick = {
                            onSaveRisk(
                                slText.trim().toBigDecimalOrNull(),
                                targetText.trim().toBigDecimalOrNull(),
                                tslEnabled,
                                tslMode,
                                tslValueText.trim().toBigDecimalOrNull() ?: BigDecimal("1"),
                                ttEnabled,
                                ttMode,
                                ttValueText.trim().toBigDecimalOrNull() ?: BigDecimal("1")
                            )
                            expandedEditor = false
                        },
                        modifier = Modifier.fillMaxWidth().testTag("save_inline_risk_${pos.symbol}"),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Save Inline Risk Controls")
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

    AlertDialog(
        onDismissRequest = onDismiss,
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
                        modifier = Modifier.weight(1f).testTag("average_qty_input")
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

    AlertDialog(
        onDismissRequest = onDismiss,
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
                    modifier = Modifier.fillMaxWidth().testTag("capital_amount_input")
                )
                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    label = { Text("Note (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
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
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = resetUsdText,
                        onValueChange = { resetUsdText = it.filter { ch -> ch.isDigit() || ch == '.' } },
                        label = { Text("Reset $") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
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

