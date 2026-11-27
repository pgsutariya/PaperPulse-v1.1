package com.example.ui.screens.orders

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.domain.engine.MarketClock
import com.example.domain.engine.OrderBookSnapshot
import com.example.domain.engine.RiskMath
import com.example.domain.model.Order
import com.example.domain.model.OrderSide
import com.example.domain.model.OrderStatus
import com.example.domain.model.ValidityType
import com.example.ui.components.EmptyStatePanel
import com.example.ui.components.MarketDepthCard
import com.example.ui.components.SegmentBadge
import com.example.ui.components.ValidityStateBadge
import com.example.ui.components.guardGlobalHotkeysOnFocus
import com.example.ui.components.rememberKeyboardAwareModalLock
import com.example.ui.theme.LocalTradingColors
import com.example.ui.theme.MonoPriceSmall
import java.math.BigDecimal

@Composable
fun OrdersScreen(
    viewModel: OrdersViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val editorDraft by viewModel.orderEditorDraft.collectAsStateWithLifecycle()
    val editorOrderBook by viewModel.editorOrderBook.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

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

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OrderFilterTab.entries.forEach { tab ->
                    val count = when (tab) {
                        OrderFilterTab.ALL -> state.pendingCount + state.executedCount + state.cancelledCount
                        OrderFilterTab.PENDING -> state.pendingCount
                        OrderFilterTab.EXECUTED -> state.executedCount
                        OrderFilterTab.CANCELLED -> state.cancelledCount
                    }
                    FilterChip(
                        selected = state.selectedTab == tab,
                        onClick = { viewModel.selectTab(tab) },
                        label = { Text("${tab.label} ($count)") },
                        modifier = Modifier.testTag("orders_tab_${tab.name.lowercase()}"),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            if (state.orders.isEmpty()) {
                EmptyStatePanel(
                    icon = Icons.AutoMirrored.Rounded.ReceiptLong,
                    title = "No orders in ${state.selectedTab.label}",
                    subtitle = "Orders placed from any instrument ticket appear here with live validity, lot size, charges & OCO state."
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(state.orders, key = { it.id }) { order ->
                        OrderRowCard(
                            order = order,
                            onEdit = { viewModel.openOrderEditor(order.id) },
                            onCancel = { viewModel.cancelOrder(order.id) }
                        )
                    }
                }
            }
        }
    }

    editorDraft?.let { draft ->
        ModifyPendingOrderDialog(
            draft = draft,
            orderBook = editorOrderBook,
            onQtyChange = viewModel::updateEditorQty,
            onStepQty = viewModel::stepEditorQty,
            onLimitChange = viewModel::updateEditorLimit,
            onTriggerChange = viewModel::updateEditorTrigger,
            onSlChange = viewModel::updateEditorSl,
            onTargetChange = viewModel::updateEditorTarget,
            onSelectDepthPrice = viewModel::selectEditorLimitFromDepth,
            onDismiss = viewModel::dismissOrderEditor,
            onSave = viewModel::saveOrderEditor
        )
    }
}

@Composable
private fun OrderRowCard(
    order: Order,
    onEdit: () -> Unit,
    onCancel: () -> Unit
) {
    val tradingColors = LocalTradingColors.current
    val isBuy = order.side == OrderSide.BUY

    Surface(
        modifier = Modifier.fillMaxWidth().testTag("order_card_${order.symbol}"),
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
                    SegmentBadge(order.segment)
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (isBuy) tradingColors.bullishContainer else tradingColors.bearishContainer
                    ) {
                        Text(
                            text = "${order.side.name} • ${RiskMath.formatQtyWithLots(order.quantity, order.lotSize)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isBuy) tradingColors.bullish else tradingColors.bearish,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh
                    ) {
                        Text(
                            text = "${order.orderType.label}${order.legRole?.let { " • $it" } ?: ""}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                ValidityStateBadge(state = order.validityState)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = order.symbol,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    val validityDetail = when (order.validityType) {
                        ValidityType.DAY -> "DAY • Expires ${MarketClock.formatIstTime(order.expiresAtMillis)}"
                        ValidityType.GTT -> "GTT (${order.gttDays ?: 7}d) • Until ${MarketClock.formatIstDate(order.expiresAtMillis)}"
                        ValidityType.GTD -> "GTD • Until ${MarketClock.formatIstDate(order.expiresAtMillis)}"
                    }
                    Text(
                        text = "${order.productType.shortCode} • Lot ${order.lotSize} • $validityDetail",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    val priceLabel = when {
                        order.filledPrice != null -> "Fill ${RiskMath.formatPrice(order.filledPrice, order.segment)}"
                        order.triggerPrice != null && order.limitPrice != null ->
                            "Trig ${RiskMath.formatPrice(order.triggerPrice, order.segment)} / Lim ${RiskMath.formatPrice(order.limitPrice, order.segment)}"
                        order.triggerPrice != null -> "Trig ${RiskMath.formatPrice(order.triggerPrice, order.segment)}"
                        order.limitPrice != null -> "Lim ${RiskMath.formatPrice(order.limitPrice, order.segment)}"
                        else -> "Market"
                    }
                    Text(text = priceLabel, style = MonoPriceSmall, fontWeight = FontWeight.SemiBold)
                    if (order.ocoGroupId != null) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            Icon(Icons.Rounded.Link, contentDescription = null, tint = tradingColors.violet, modifier = Modifier.size(12.dp))
                            Text("OCO", style = MaterialTheme.typography.labelSmall, color = tradingColors.violet)
                        }
                    }
                }
            }

            // Itemized charges & taxes strip
            if (order.totalCharges > BigDecimal.ZERO) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val taxSum = order.sttCtt.add(order.exchangeCharges).add(order.sebiCharges).add(order.stampDuty).add(order.gst)
                    Text(
                        text = "Charges ${RiskMath.formatPrice(order.totalCharges, order.segment)} (Brok ${RiskMath.formatPrice(order.brokerage, order.segment)} • Taxes & Levies ${RiskMath.formatPrice(taxSum, order.segment)})",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = order.statusReason ?: order.status.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                if (order.status == OrderStatus.PENDING) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(
                            onClick = onEdit,
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.testTag("edit_order_${order.id}")
                        ) {
                            Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                            Text(" Edit", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                        OutlinedButton(
                            onClick = onCancel,
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.testTag("cancel_order_${order.id}")
                        ) {
                            Icon(Icons.Rounded.Cancel, contentDescription = null, modifier = Modifier.size(14.dp), tint = tradingColors.bearish)
                            Text(" Cancel", style = MaterialTheme.typography.labelMedium, color = tradingColors.bearish)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModifyPendingOrderDialog(
    draft: PendingOrderEditorDraft,
    orderBook: OrderBookSnapshot?,
    onQtyChange: (String) -> Unit,
    onStepQty: (Int) -> Unit,
    onLimitChange: (String) -> Unit,
    onTriggerChange: (String) -> Unit,
    onSlChange: (String) -> Unit,
    onTargetChange: (String) -> Unit,
    onSelectDepthPrice: (BigDecimal, OrderSide) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit
) {
    val tradingColors = LocalTradingColors.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    // Keyboard-aware modal lock: prevents tab switching & accidental backdrop close while typing prices
    val modalLock = rememberKeyboardAwareModalLock(isOpen = true)
    val lot = draft.lotSize.coerceAtLeast(1)

    AlertDialog(
        onDismissRequest = { if (modalLock.allowBackdropDismiss && !draft.isSaving) onDismiss() },
        properties = DialogProperties(
            dismissOnClickOutside = modalLock.allowBackdropDismiss,
            dismissOnBackPress = !draft.isSaving
        ),
        title = {
            Column {
                Text("Modify Pending Order", fontWeight = FontWeight.Bold)
                Text(
                    "${draft.symbol} • ${draft.side.name} • ${draft.orderType.label}${draft.legRole?.let { " ($it)" } ?: ""} • Lot $lot",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(
                        onClick = { onStepQty(-1) },
                        modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(10.dp))
                    ) {
                        Icon(Icons.Rounded.Remove, contentDescription = "Decrease quantity")
                    }
                    OutlinedTextField(
                        value = draft.qtyText,
                        onValueChange = onQtyChange,
                        label = { Text("Qty (Lot $lot)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .guardGlobalHotkeysOnFocus()
                            .testTag("modify_order_qty_input")
                    )
                    IconButton(
                        onClick = { onStepQty(1) },
                        modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(10.dp))
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = "Increase quantity")
                    }
                }
                val parsedQty = draft.qtyText.toIntOrNull() ?: 0
                if (parsedQty > 0) {
                    Text(
                        text = "Order Size: ${RiskMath.formatQtyWithLots(parsedQty, lot)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                if (draft.showLimit) {
                    OutlinedTextField(
                        value = draft.limitText,
                        onValueChange = onLimitChange,
                        label = { Text("Limit Price (${draft.segment.currencySymbol})") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .guardGlobalHotkeysOnFocus()
                            .testTag("modify_order_limit_input")
                    )
                }
                if (orderBook != null && (draft.showLimit || draft.showTrigger)) {
                    MarketDepthCard(
                        orderBook = orderBook,
                        segment = draft.segment,
                        selectedLimitPrice = draft.limitText.toBigDecimalOrNull()
                            ?: draft.triggerText.toBigDecimalOrNull(),
                        onSelectPriceForLimit = onSelectDepthPrice,
                        compact = true
                    )
                }
                if (draft.showTrigger) {
                    OutlinedTextField(
                        value = draft.triggerText,
                        onValueChange = onTriggerChange,
                        label = { Text("Trigger Price (${draft.segment.currencySymbol})") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .guardGlobalHotkeysOnFocus()
                            .testTag("modify_order_trigger_input")
                    )
                }
                if (draft.canEditAttachedRisk) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = draft.slText,
                            onValueChange = onSlChange,
                            label = { Text("Attached SL") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .guardGlobalHotkeysOnFocus()
                                .testTag("modify_order_sl_input")
                        )
                        OutlinedTextField(
                            value = draft.targetText,
                            onValueChange = onTargetChange,
                            label = { Text("Attached Target") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .guardGlobalHotkeysOnFocus()
                                .testTag("modify_order_target_input")
                        )
                    }
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
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    focusManager.clearFocus(force = true)
                    keyboardController?.hide()
                    onSave()
                },
                enabled = !draft.isSaving,
                modifier = Modifier.testTag("confirm_modify_order_button")
            ) {
                Text(if (draft.isSaving) "Updating..." else "Update Order")
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    focusManager.clearFocus(force = true)
                    keyboardController?.hide()
                    onDismiss()
                },
                enabled = !draft.isSaving
            ) {
                Text("Cancel")
            }
        }
    )
}
