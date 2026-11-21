package com.example.ui.screens.positions

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
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
import com.example.domain.engine.RiskMath
import com.example.domain.model.OrderSide
import com.example.domain.model.ProductType
import com.example.domain.model.TrailMode
import com.example.ui.components.EmptyStatePanel
import com.example.ui.components.FeedHealthBadge
import com.example.ui.components.FeedLostWarningBanner
import com.example.ui.components.InstrumentTypeBadge
import com.example.ui.components.SegmentBadge
import com.example.ui.theme.LocalTradingColors
import com.example.ui.theme.MonoPriceMedium
import com.example.ui.theme.MonoPriceSmall
import java.math.BigDecimal

@Composable
fun PositionsScreen(
    onOpenDetail: (String) -> Unit,
    viewModel: PositionsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val tradingColors = LocalTradingColors.current

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

            // Summary Header Card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, tradingColors.border)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("UNREALISED P&L", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            val unrealColor = if (state.totalUnrealisedPnl >= BigDecimal.ZERO) tradingColors.bullish else tradingColors.bearish
                            Text(
                                text = RiskMath.formatPortfolioInr(state.totalUnrealisedPnl, signed = true),
                                style = MonoPriceMedium,
                                color = unrealColor
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("REALISED P&L", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            val realColor = if (state.realisedPnl >= BigDecimal.ZERO) tradingColors.bullish else tradingColors.bearish
                            Text(
                                text = RiskMath.formatPortfolioInr(state.realisedPnl, signed = true),
                                style = MonoPriceMedium,
                                color = realColor
                            )
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = viewModel::simulateIntradaySquareOff,
                        modifier = Modifier.fillMaxWidth().testTag("simulate_square_off_button"),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Rounded.Schedule, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Simulate 15:15 IST Intraday Square-Off")
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
                    items(state.items, key = { it.position.id }) { item ->
                        PositionCard(
                            item = item,
                            onOpenDetail = { onOpenDetail(item.position.symbol) },
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
}

@Composable
private fun PositionCard(
    item: PositionWithLivePnl,
    onOpenDetail: () -> Unit,
    onSaveRisk: (BigDecimal?, BigDecimal?, Boolean, TrailMode, BigDecimal, Boolean, TrailMode, BigDecimal) -> Unit,
    onConvertToCarryForward: () -> Unit,
    onExitMarket: () -> Unit
) {
    val pos = item.position
    val tradingColors = LocalTradingColors.current
    val isProfit = item.unrealisedPnl >= BigDecimal.ZERO
    val pnlColor = if (isProfit) tradingColors.bullish else tradingColors.bearish

    var expandedEditor by remember { mutableStateOf(false) }
    var slText by remember(pos.activeSlPrice) { mutableStateOf(pos.activeSlPrice?.toPlainString() ?: "") }
    var targetText by remember(pos.activeTargetPrice) { mutableStateOf(pos.activeTargetPrice?.toPlainString() ?: "") }
    var tslEnabled by remember(pos.isTslEnabled) { mutableStateOf(pos.isTslEnabled) }
    var tslMode by remember(pos.tslTrailMode) { mutableStateOf(pos.tslTrailMode) }
    var tslValueText by remember(pos.tslTrailValue) { mutableStateOf(pos.tslTrailValue.takeIf { it > BigDecimal.ZERO }?.toPlainString() ?: "0.8") }
    var ttEnabled by remember(pos.isTrailingTargetEnabled) { mutableStateOf(pos.isTrailingTargetEnabled) }
    var ttMode by remember(pos.trailingTargetTrailMode) { mutableStateOf(pos.trailingTargetTrailMode) }
    var ttValueText by remember(pos.trailingTargetTrailValue) { mutableStateOf(pos.trailingTargetTrailValue.takeIf { it > BigDecimal.ZERO }?.toPlainString() ?: "0.8") }

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
                            color = if (pos.side == OrderSide.BUY) tradingColors.bullishContainer else tradingColors.bearishContainer
                        ) {
                            Text(
                                text = "${pos.side.name} ${pos.quantity}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (pos.side == OrderSide.BUY) tradingColors.bullish else tradingColors.bearish,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
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
                    Text(
                        text = "Avg ${RiskMath.formatPrice(pos.averageEntryPrice, pos.segment)}  •  LTP ${RiskMath.formatPrice(item.ltp, pos.segment)}",
                        style = MonoPriceSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
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
                    modifier = Modifier.weight(0.85f).testTag("exit_position_button_${pos.symbol}"),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = tradingColors.bearish, contentColor = Color.White),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Text("Exit Mkt", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
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
                            onValueChange = { slText = it },
                            label = { Text("SL Price") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = targetText,
                            onValueChange = { targetText = it },
                            label = { Text("Target Price") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
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
                                onValueChange = { tslValueText = it },
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
                                onValueChange = { ttValueText = it },
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
                                slText.toBigDecimalOrNull(),
                                targetText.toBigDecimalOrNull(),
                                tslEnabled,
                                tslMode,
                                tslValueText.toBigDecimalOrNull() ?: BigDecimal("1"),
                                ttEnabled,
                                ttMode,
                                ttValueText.toBigDecimalOrNull() ?: BigDecimal("1")
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
