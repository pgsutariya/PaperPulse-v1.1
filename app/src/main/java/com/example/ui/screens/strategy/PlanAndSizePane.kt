package com.example.ui.screens.strategy

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.domain.strategy.PositionSizer
import com.example.ui.theme.LocalTradingColors
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

enum class PlanStopRuleKind(val label: String) {
    PERCENT("% under entry"),
    ATR("ATR multiple"),
    OR_MID("OR Midpoint"),
    OR_LOW("OR Low"),
    PRICE("Fixed price")
}

enum class PlanTargetRuleKind(val label: String) {
    R_MULTIPLE("R multiple"),
    PRICE("Fixed price")
}

/**
 * Interactive UI for [com.example.domain.strategy.ExitPlanner] and [PositionSizer].
 * Lets the trader fix stop + target before entering, sizes the position against all four
 * independent risk ceilings (max loss, position cap, max order, cash buffer), and sends
 * the exact entry, SL, target, and quantity directly into the Order Ticket.
 */
@Composable
internal fun PlanAndSizePane(
    state: StrategyWorkbenchUiState,
    viewModel: StrategyWorkbenchViewModel,
    onSendPlannedOrder: (symbol: String, entry: String, stop: String, target: String, quantity: Int) -> Unit
) {
    val colors = LocalTradingColors.current
    val currency = state.instruments.firstOrNull { it.symbol == state.planSymbol }?.segment?.currencySymbol ?: "₹"

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("workbench_plan_and_size")
    ) {
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                colors = CardDefaults.cardColors(containerColor = colors.cardElevated)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "Exit Planner & Position Sizer",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Fix your stop and target before entering, then size the share count against your max loss, position cap, max order, and cash buffer.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        WorkbenchPickerButton(
                            label = "Symbol",
                            selected = state.planSymbol.takeIf { it.isNotBlank() },
                            options = state.instruments.map { it.symbol },
                            optionLabel = { it },
                            onPick = viewModel::setPlanSymbol
                        )
                        OutlinedButton(
                            onClick = viewModel::fillPlannerFromMarket,
                            modifier = Modifier.testTag("workbench_plan_fill_ltp")
                        ) {
                            Text("Fill LTP / ATR")
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        WorkbenchNumberField(
                            label = "Entry Price",
                            value = state.planEntry,
                            onValueChange = viewModel::setPlanEntry,
                            modifier = Modifier.weight(1f)
                        )
                        WorkbenchNumberField(
                            label = "Max Risk %",
                            value = state.planMaxLossPct,
                            onValueChange = viewModel::setPlanMaxLossPct,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "Stop Rule",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        PlanStopRuleKind.entries.forEach { kind ->
                            FilterChip(
                                selected = state.planStopRule == kind,
                                onClick = { viewModel.setPlanStopRule(kind) },
                                label = { Text(kind.label) }
                            )
                        }
                    }

                    when (state.planStopRule) {
                        PlanStopRuleKind.PERCENT -> {
                            WorkbenchNumberField(
                                label = "Stop % under entry",
                                value = state.planStopValue,
                                onValueChange = viewModel::setPlanStopValue,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        PlanStopRuleKind.ATR -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                WorkbenchNumberField(
                                    label = "ATR multiple",
                                    value = state.planStopValue,
                                    onValueChange = viewModel::setPlanStopValue,
                                    modifier = Modifier.weight(1f)
                                )
                                WorkbenchNumberField(
                                    label = "ATR (14)",
                                    value = state.planAtr,
                                    onValueChange = viewModel::setPlanAtr,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        PlanStopRuleKind.OR_MID, PlanStopRuleKind.OR_LOW -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                WorkbenchNumberField(
                                    label = "OR High",
                                    value = state.planOrHigh,
                                    onValueChange = viewModel::setPlanOrHigh,
                                    modifier = Modifier.weight(1f)
                                )
                                WorkbenchNumberField(
                                    label = "OR Low",
                                    value = state.planOrLow,
                                    onValueChange = viewModel::setPlanOrLow,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        PlanStopRuleKind.PRICE -> {
                            WorkbenchNumberField(
                                label = "Stop Price",
                                value = state.planStopValue,
                                onValueChange = viewModel::setPlanStopValue,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "Target Rule",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PlanTargetRuleKind.entries.forEach { kind ->
                            FilterChip(
                                selected = state.planTargetRule == kind,
                                onClick = { viewModel.setPlanTargetRule(kind) },
                                label = { Text(kind.label) }
                            )
                        }
                    }
                    WorkbenchNumberField(
                        label = if (state.planTargetRule == PlanTargetRuleKind.R_MULTIPLE) "Target R multiple (e.g. 2)" else "Target Price",
                        value = state.planTargetValue,
                        onValueChange = viewModel::setPlanTargetValue,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = viewModel::runPlanAndSize,
                        modifier = Modifier.testTag("workbench_plan_run_button")
                    ) {
                        Text("Plan & Size Trade")
                    }
                }
            }
        }

        state.planError?.let { err ->
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.bearishContainer)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "Plan Refused",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = colors.bearish
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = err,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.bearish
                        )
                    }
                }
            }
        }

        state.planResult?.let { plan ->
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.cardElevated)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "Exit Plan • ${state.planSymbol}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        if (plan.stopDetail.isNotBlank()) {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = plan.stopDetail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        PlanStatRow("Entry", planMoney(plan.entry, currency))
                        PlanStatRow("Stop (${plan.stopLabel})", planMoney(plan.stop, currency), colors.bearish)
                        PlanStatRow("Target (${plan.targetLabel})", planMoney(plan.target, currency), colors.bullish)
                        PlanStatRow("Risk / share", planMoney(plan.riskPerShare, currency))
                        PlanStatRow(
                            "Reward : Risk",
                            "${plan.rewardRisk.stripTrailingZeros().toPlainString()} : 1",
                            if (plan.rewardRisk >= BigDecimal("1.5")) colors.bullish else colors.amber
                        )
                    }
                }
            }
        }

        state.sizingResult?.let { sizing ->
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.cardElevated)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Position Size",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = if (sizing.isTradeable) colors.bullishContainer else colors.bearishContainer,
                                border = BorderStroke(
                                    1.dp,
                                    (if (sizing.isTradeable) colors.bullish else colors.bearish).copy(alpha = 0.4f)
                                )
                            ) {
                                Text(
                                    text = if (sizing.isTradeable) {
                                        "${sizing.quantity} sh • bound by ${sizing.boundBy}"
                                    } else {
                                        "NO ORDER"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (sizing.isTradeable) colors.bullish else colors.bearish,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }

                        Spacer(Modifier.height(8.dp))
                        PlanStatRow("Recommended Qty", "${sizing.quantity} shares", if (sizing.isTradeable) colors.bullish else colors.bearish)
                        PlanStatRow("Order Value", planMoney(sizing.cost, currency))
                        PlanStatRow("Max Loss at Stop", planMoney(sizing.maxLoss, currency), colors.bearish)
                        PlanStatRow("Sizing Price (${sizing.entrySource.name.lowercase(Locale.US)})", planMoney(sizing.entry, currency))

                        sizing.refusal?.let { refusal ->
                            Spacer(Modifier.height(8.dp))
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = colors.bearishContainer,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = refusal,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.bearish,
                                    modifier = Modifier.padding(10.dp)
                                )
                            }
                        }

                        HorizontalDivider(Modifier.padding(vertical = 10.dp))
                        Text(
                            text = "Four Risk Ceilings",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(6.dp))
                        sizing.limits.forEach { limit ->
                            LimitCeilingRow(limit)
                        }

                        if (sizing.isTradeable) {
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = {
                                    onSendPlannedOrder(
                                        sizing.symbol,
                                        sizing.entry.toPlainString(),
                                        sizing.stop.toPlainString(),
                                        sizing.target.toPlainString(),
                                        sizing.quantity
                                    )
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("workbench_send_to_ticket_button")
                            ) {
                                Text("Send ${sizing.quantity} ${sizing.symbol} to Order Ticket")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LimitCeilingRow(limit: PositionSizer.Limit) {
    val colors = LocalTradingColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = if (limit.binds) "${limit.name.uppercase(Locale.US)} (BINDING)" else limit.name.uppercase(Locale.US),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (limit.binds) FontWeight.Bold else FontWeight.Medium,
                color = if (limit.binds) colors.amber else MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "${limit.shares} sh",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = if (limit.binds) colors.amber else MaterialTheme.colorScheme.onSurface
            )
        }
        Text(
            text = limit.how,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PlanStatRow(label: String, value: String, valueColor: Color = Color.Unspecified) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = valueColor
        )
    }
}

private fun planMoney(v: BigDecimal, currencySymbol: String = "₹"): String =
    currencySymbol + v.setScale(2, RoundingMode.HALF_UP).toPlainString()
