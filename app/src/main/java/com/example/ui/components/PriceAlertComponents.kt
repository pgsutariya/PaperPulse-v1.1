package com.example.ui.components

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.TrendingDown
import androidx.compose.material.icons.rounded.TrendingUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.alerts.PriceAlert
import com.example.data.alerts.PriceAlertCondition
import com.example.data.alerts.PriceAlertRepository
import com.example.domain.engine.RiskMath
import com.example.domain.model.Instrument
import com.example.ui.theme.LocalTradingColors
import com.example.ui.theme.MonoPriceSmall
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

@Composable
fun SetPriceAlertDialog(
    instrument: Instrument,
    existingAlerts: List<PriceAlert>,
    hasNotificationPermission: Boolean,
    onCreateAlert: (targetPrice: BigDecimal, condition: PriceAlertCondition, note: String) -> Unit,
    onDeleteAlert: (String) -> Unit,
    onRearmAlert: (PriceAlert) -> Unit,
    onDismiss: () -> Unit
) {
    val tradingColors = LocalTradingColors.current
    val defaultTarget = remember(instrument.symbol) {
        instrument.ltp.multiply(BigDecimal("1.01")).setScale(2, RoundingMode.HALF_UP)
    }

    var targetText by remember(instrument.symbol) { mutableStateOf(defaultTarget.toPlainString()) }
    var manualCondition by remember(instrument.symbol) { mutableStateOf<PriceAlertCondition?>(null) }
    var noteText by remember(instrument.symbol) { mutableStateOf("") }
    var validationError by remember(instrument.symbol) { mutableStateOf<String?>(null) }
    var notifGranted by remember { mutableStateOf(hasNotificationPermission) }

    val parsedTarget = targetText.trim().toBigDecimalOrNull()
    val effectiveCondition = manualCondition ?: if (parsedTarget != null) {
        PriceAlertRepository.inferCondition(parsedTarget, instrument.ltp)
    } else {
        PriceAlertCondition.ABOVE
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        notifGranted = granted
    }

    fun applyPercentOffset(pct: Double) {
        val factor = BigDecimal.valueOf(1.0 + pct / 100.0)
        val next = instrument.ltp.multiply(factor).setScale(2, RoundingMode.HALF_UP)
        targetText = next.toPlainString()
        manualCondition = null
        validationError = null
    }

    fun submitAlert() {
        val price = targetText.trim().toBigDecimalOrNull()
        if (price == null || price <= BigDecimal.ZERO) {
            validationError = "Enter a valid target price above 0."
            return
        }
        if (!notifGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        onCreateAlert(price, effectiveCondition, noteText.trim())
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("set_price_alert_dialog"),
        icon = {
            Icon(
                imageVector = Icons.Rounded.NotificationsActive,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Set Price Alert", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${instrument.symbol} • LTP ${RiskMath.formatPrice(instrument.ltp, instrument.segment)}",
                    style = MonoPriceSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (!notifGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = tradingColors.amberContainer.copy(alpha = 0.65f),
                        border = BorderStroke(1.dp, tradingColors.amber.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.NotificationsOff,
                                    contentDescription = null,
                                    tint = tradingColors.amber,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "Enable system notifications for instant price alerts.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            TextButton(
                                onClick = { permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                                modifier = Modifier.testTag("price_alert_allow_notifications")
                            ) {
                                Text("Allow", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = targetText,
                    onValueChange = {
                        targetText = it
                        manualCondition = null
                        validationError = null
                    },
                    label = { Text("Target Price") },
                    placeholder = { Text(instrument.ltp.toPlainString()) },
                    singleLine = true,
                    isError = validationError != null,
                    supportingText = validationError?.let { err -> { Text(err) } } ?: {
                        if (parsedTarget != null && instrument.ltp > BigDecimal.ZERO) {
                            val pctDiff = parsedTarget.subtract(instrument.ltp)
                                .multiply(BigDecimal(100))
                                .divide(instrument.ltp, 2, RoundingMode.HALF_UP)
                            val sign = if (pctDiff >= BigDecimal.ZERO) "+" else ""
                            Text("$sign${pctDiff.toPlainString()}% from current LTP")
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("price_alert_target_input")
                )

                // Quick percentage offset chips
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(-5.0, -2.0, -1.0, 1.0, 2.0, 5.0).forEach { pct ->
                        val label = if (pct > 0) "+${pct.toInt()}%" else "${pct.toInt()}%"
                        AssistChip(
                            onClick = { applyPercentOffset(pct) },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.testTag("price_alert_offset_${label}")
                        )
                    }
                }

                // Trigger Condition Selector
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = effectiveCondition == PriceAlertCondition.ABOVE,
                        onClick = { manualCondition = PriceAlertCondition.ABOVE },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Rounded.TrendingUp,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        label = { Text("Rises ≥", style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("price_alert_condition_above"),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = tradingColors.bullishContainer,
                            selectedLabelColor = tradingColors.bullish
                        )
                    )
                    FilterChip(
                        selected = effectiveCondition == PriceAlertCondition.BELOW,
                        onClick = { manualCondition = PriceAlertCondition.BELOW },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Rounded.TrendingDown,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        label = { Text("Falls ≤", style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("price_alert_condition_below"),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = tradingColors.bearishContainer,
                            selectedLabelColor = tradingColors.bearish
                        )
                    )
                }

                OutlinedTextField(
                    value = noteText,
                    onValueChange = { if (it.length <= 80) noteText = it },
                    label = { Text("Alert Note (Optional)") },
                    placeholder = { Text("e.g. Breakout entry, Support retest") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("price_alert_note_input")
                )

                if (existingAlerts.isNotEmpty()) {
                    HorizontalDivider(color = tradingColors.border)
                    Text(
                        text = "EXISTING ALERTS FOR ${instrument.symbol} (${existingAlerts.size})",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    existingAlerts.forEach { alert ->
                        AlertMiniRow(
                            alert = alert,
                            onRearm = { onRearmAlert(alert) },
                            onDelete = { onDeleteAlert(alert.id) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = ::submitAlert,
                modifier = Modifier.testTag("price_alert_confirm_button")
            ) {
                Text("Set Alert")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("price_alert_cancel_button")
            ) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun PriceAlertsManagerDialog(
    alerts: List<PriceAlert>,
    ltpBySymbol: Map<String, BigDecimal>,
    onRearmAlert: (PriceAlert) -> Unit,
    onDeleteAlert: (String) -> Unit,
    onClearTriggered: () -> Unit,
    onOpenSymbol: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val tradingColors = LocalTradingColors.current
    val activeCount = alerts.count { it.isActive }
    val triggeredCount = alerts.count { !it.isActive }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("price_alerts_manager_dialog"),
        icon = {
            Icon(
                imageVector = Icons.Rounded.NotificationsActive,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text("Watchlist Price Alerts", fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "$activeCount active • $triggeredCount triggered. Right-click or long-press any symbol in the Watchlist to add a target price alert.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (alerts.isEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "No price alerts yet. Right-click (or long-press / tap the bell icon) on any symbol in your Watchlist to create one.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                } else {
                    alerts.forEach { alert ->
                        val ltp = ltpBySymbol[alert.symbol]
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            border = BorderStroke(
                                1.dp,
                                if (alert.isActive) tradingColors.border else tradingColors.bullish.copy(alpha = 0.45f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onDismiss()
                                    onOpenSymbol(alert.symbol)
                                }
                                .testTag("managed_alert_row_${alert.id}")
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = alert.symbol,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Surface(
                                            shape = RoundedCornerShape(5.dp),
                                            color = if (alert.isActive) {
                                                MaterialTheme.colorScheme.primaryContainer
                                            } else {
                                                tradingColors.bullishContainer
                                            }
                                        ) {
                                            Text(
                                                text = if (alert.isActive) "ACTIVE" else "HIT",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = if (alert.isActive) {
                                                    MaterialTheme.colorScheme.primary
                                                } else {
                                                    tradingColors.bullish
                                                },
                                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                            )
                                        }
                                    }
                                    val targetFmt = RiskMath.formatPrice(alert.targetPrice, alert.segment)
                                    val detailLine = if (alert.isActive && ltp != null && ltp > BigDecimal.ZERO) {
                                        val diffPct = alert.targetPrice.subtract(ltp)
                                            .multiply(BigDecimal(100))
                                            .divide(ltp, 2, RoundingMode.HALF_UP)
                                        val sign = if (diffPct >= BigDecimal.ZERO) "+" else ""
                                        "Target ${alert.condition.symbolText} $targetFmt ($sign${diffPct.toPlainString()}%)"
                                    } else if (!alert.isActive && alert.triggeredPrice != null) {
                                        "Hit @ ${RiskMath.formatPrice(alert.triggeredPrice, alert.segment)} (Target ${alert.condition.symbolText} $targetFmt)"
                                    } else {
                                        "Target ${alert.condition.symbolText} $targetFmt"
                                    }
                                    Text(
                                        text = detailLine,
                                        style = MonoPriceSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    if (alert.note.isNotBlank()) {
                                        Text(
                                            text = alert.note,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (!alert.isActive) {
                                        IconButton(
                                            onClick = { onRearmAlert(alert) },
                                            modifier = Modifier.testTag("rearm_alert_${alert.id}")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Refresh,
                                                contentDescription = "Re-arm alert",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                    IconButton(
                                        onClick = { onDeleteAlert(alert.id) },
                                        modifier = Modifier.testTag("delete_alert_${alert.id}")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.DeleteOutline,
                                            contentDescription = "Delete alert",
                                            tint = tradingColors.bearish,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                modifier = Modifier.testTag("price_alerts_manager_close")
            ) {
                Text("Done")
            }
        },
        dismissButton = {
            if (triggeredCount > 0) {
                OutlinedButton(
                    onClick = onClearTriggered,
                    modifier = Modifier.testTag("price_alerts_clear_triggered")
                ) {
                    Text("Clear Hit ($triggeredCount)")
                }
            }
        }
    )
}

@Composable
private fun AlertMiniRow(
    alert: PriceAlert,
    onRearm: () -> Unit,
    onDelete: () -> Unit
) {
    val tradingColors = LocalTradingColors.current
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, tradingColors.border),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = if (alert.isActive) Icons.Rounded.NotificationsActive else Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = if (alert.isActive) MaterialTheme.colorScheme.primary else tradingColors.bullish,
                    modifier = Modifier.size(15.dp)
                )
                Column {
                    Text(
                        text = "${alert.condition.symbolText} ${RiskMath.formatPrice(alert.targetPrice, alert.segment)} ${if (alert.isActive) "(Active)" else "(Hit)"}",
                        style = MonoPriceSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (alert.note.isNotBlank()) {
                        Text(
                            text = alert.note,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!alert.isActive) {
                    IconButton(onClick = onRearm) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = "Re-arm alert",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteOutline,
                        contentDescription = "Delete alert",
                        tint = tradingColors.bearish,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}
