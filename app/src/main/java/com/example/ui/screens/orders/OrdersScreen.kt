package com.example.ui.screens.orders

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.domain.engine.MarketClock
import com.example.domain.engine.RiskMath
import com.example.domain.model.Order
import com.example.domain.model.OrderSide
import com.example.domain.model.OrderStatus
import com.example.domain.model.ValidityType
import com.example.ui.components.EmptyStatePanel
import com.example.ui.components.SegmentBadge
import com.example.ui.components.ValidityStateBadge
import com.example.ui.theme.LocalTradingColors
import com.example.ui.theme.MonoPriceSmall

@Composable
fun OrdersScreen(
    viewModel: OrdersViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
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
                subtitle = "Orders placed from any instrument ticket appear here with live validity & OCO state."
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
                        onCancel = { viewModel.cancelOrder(order.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun OrderRowCard(
    order: Order,
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
                            text = "${order.side.name} ${order.quantity}",
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
                        text = "${order.productType.shortCode} • $validityDetail",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    val priceLabel = when {
                        order.filledPrice != null -> "Fill ${RiskMath.formatPrice(order.filledPrice, order.segment)}"
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
