package com.example.ui.screens.detail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Analytics
import androidx.compose.material.icons.rounded.GridOn
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.domain.engine.MarketClock
import com.example.domain.engine.PriceAwareExecutionDecision
import com.example.domain.engine.PriceAwareExecutionPlan
import com.example.domain.engine.RiskMath
import com.example.domain.model.OrderSide
import com.example.domain.model.OrderType
import com.example.domain.model.ProductType
import com.example.domain.model.RiskInputMode
import com.example.domain.model.TrailMode
import com.example.domain.model.ValidityType
import com.example.domain.signal.SignalDirection
import com.example.ui.components.AiSecondOpinionCard
import com.example.ui.components.FeedHealthBadge
import com.example.ui.components.FeedLostWarningBanner
import com.example.ui.components.InstrumentTypeBadge
import com.example.ui.components.MarketDepthCard
import com.example.ui.components.SegmentBadge
import com.example.ui.components.SparklineChart
import com.example.ui.components.guardGlobalHotkeysOnFocus
import com.example.ui.components.rememberKeyboardAwareModalLock
import com.example.ui.theme.LocalTradingColors
import com.example.ui.theme.MonoPriceLarge
import com.example.ui.theme.MonoPriceMedium
import com.example.ui.theme.MonoPriceSmall
import kotlinx.coroutines.launch
import java.math.BigDecimal

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstrumentDetailScreen(
    onBack: () -> Unit,
    onOrderPlacedNavigate: (message: String, goToOrdersTab: Boolean) -> Unit,
    onOpenOptionChain: (String) -> Unit = {},
    onOpenChart: (String) -> Unit = {},
    onOpenFeedSettings: () -> Unit = {},
    viewModel: InstrumentDetailViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val tradingColors = LocalTradingColors.current
    var showGtdDatePicker by remember { mutableStateOf(false) }
    // Keyboard-aware lock keeps user inside Order Ticket while typing custom prices / SL / Target
    rememberKeyboardAwareModalLock(isOpen = true)

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is DetailEvent.ShowMessage -> launch {
                    snackbarHostState.showSnackbar(event.message)
                }
                is DetailEvent.OrderPlaced -> onOrderPlacedNavigate(event.message, event.goToOrdersTab)
            }
        }
    }

    val inst = state.instrument
    val draft = state.draft

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = inst?.symbol ?: "Instrument",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        inst?.let {
                            SegmentBadge(it.segment)
                            InstrumentTypeBadge(it.type)
                            FeedHealthBadge(state.feedBadge)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("detail_back_button")) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (inst != null) {
                        IconButton(
                            onClick = { onOpenChart(inst.symbol) },
                            modifier = Modifier.testTag("detail_open_chart_button")
                        ) {
                            Icon(Icons.Rounded.Analytics, contentDescription = "Chart & Analysis")
                        }
                    }
                    if (inst != null && inst.segment != com.example.domain.model.MarketSegment.CRYPTO) {
                        val chainUnderlying = remember(inst.symbol) {
                            inst.symbol
                                .removePrefix("BSE:")
                                .replace(Regex("\\d{2}[A-Z]{3}.*$"), "")
                                .ifBlank { "NIFTY" }
                        }
                        IconButton(
                            onClick = { onOpenOptionChain(chainUnderlying) },
                            modifier = Modifier.testTag("detail_option_chain_button")
                        ) {
                            Icon(Icons.Rounded.GridOn, contentDescription = "Open Option Chain")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        if (inst == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Loading instrument...", style = MaterialTheme.typography.bodyLarge)
            }
            return@Scaffold
        }

        val isPositive = inst.changePercent >= BigDecimal.ZERO
        val changeColor = if (isPositive) tradingColors.bullish else tradingColors.bearish

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (state.riskControlsSuspended) {
                FeedLostWarningBanner(
                    detail = state.liveStatusMessage,
                    onReconnect = viewModel::reconnectFeed,
                    onSwitchToSimulated = viewModel::switchToSimulatedFeed
                )
            }

            // 1. Live Price Header + Chart Card
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, tradingColors.border)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = inst.displayName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = RiskMath.formatPrice(inst.ltp, inst.segment),
                                style = MonoPriceLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            val sign = if (isPositive) "+" else ""
                            Text(
                                text = "${RiskMath.formatSignedCurrency(inst.change, inst.segment)} ($sign${inst.changePercent}%)",
                                style = MonoPriceSmall,
                                color = changeColor
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("H: ${RiskMath.formatPrice(inst.dayHigh, inst.segment)}", style = MonoPriceSmall, color = tradingColors.bullish)
                            Text("L: ${RiskMath.formatPrice(inst.dayLow, inst.segment)}", style = MonoPriceSmall, color = tradingColors.bearish)
                            Text("Lot: ${inst.lotSize} • Tick: ${inst.tickSize}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                text = "Session: ${MarketClock.sessionWindowLabel(inst.segment, inst.type)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    SparklineChart(
                        points = inst.sparkline,
                        isPositive = isPositive,
                        modifier = Modifier.fillMaxWidth().height(90.dp),
                        strokeWidthDp = 2.6f
                    )

                    Spacer(Modifier.height(10.dp))
                    // Quick price shock buttons for testing SL/TSL/Trailing Target in paper mode
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Sim Nudge:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        listOf("-2%" to BigDecimal("-2"), "-0.5%" to BigDecimal("-0.5"), "+0.5%" to BigDecimal("0.5"), "+2%" to BigDecimal("2")).forEach { (label, delta) ->
                            val isUp = delta > BigDecimal.ZERO
                            Surface(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { viewModel.nudgePrice(delta) },
                                color = if (isUp) tradingColors.bullishContainer else tradingColors.bearishContainer,
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isUp) tradingColors.bullish else tradingColors.bearish,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }
                }
            }

            state.priceAwarePlan?.let { plan ->
                PriceAwareExecutionCard(
                    plan = plan,
                    segment = inst.segment,
                    hasSignalTarget = draft.signalTargetEntryPrice != null,
                    guardEnabled = draft.priceAwareGuardEnabled,
                    slippageTolerancePercent = draft.slippageTolerancePercent,
                    onToggleGuard = viewModel::togglePriceAwareGuard,
                    onSlippageToleranceChange = viewModel::updateSlippageTolerance,
                    onClearSignalEntry = viewModel::clearSignalTargetEntry
                )
            }

            if (state.symbolSignals.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, tradingColors.border)
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Rounded.Bolt, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                            Text("ADVISORY SIGNALS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        state.symbolSignals.take(2).forEach { item ->
                            val sig = item.signal
                            val isLong = sig.direction == SignalDirection.LONG
                            val sigColor = if (isLong) tradingColors.bullish else tradingColors.bearish
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${sig.direction.name} • ${sig.strategy.shortLabel} (${sig.timeframe.label})",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = sigColor,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "SL ${RiskMath.formatPrice(sig.stopLoss, sig.segment)} • T1 ${RiskMath.formatPrice(sig.target1, sig.segment)} • 1:${String.format("%.1f", sig.riskRewardRatio)}",
                                        style = MonoPriceSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                TextButton(onClick = { viewModel.applySignalToTicket(sig) }) {
                                    Text("Apply")
                                }
                            }
                        }
                    }
                }
            }

            // 2. BUY / SELL Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OrderSideButton(
                    label = "BUY / LONG",
                    selected = draft.side == OrderSide.BUY,
                    activeColor = tradingColors.bullish,
                    onClick = { viewModel.setSide(OrderSide.BUY) },
                    modifier = Modifier.weight(1f).testTag("side_buy_button")
                )
                OrderSideButton(
                    label = "SELL / SHORT",
                    selected = draft.side == OrderSide.SELL,
                    activeColor = tradingColors.bearish,
                    onClick = { viewModel.setSide(OrderSide.SELL) },
                    modifier = Modifier.weight(1f).testTag("side_sell_button")
                )
            }

            // 2b. Real-Time 5-Level Market Depth (Order Book)
            state.priceAwarePlan?.orderBook?.let { liveBook ->
                MarketDepthCard(
                    orderBook = liveBook,
                    segment = inst.segment,
                    selectedLimitPrice = draft.limitPriceText.toBigDecimalOrNull(),
                    onSelectPriceForLimit = viewModel::selectLimitPriceFromDepth
                )
            }

            // 3. Order Type & Product Type Card
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, tradingColors.border)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("ORDER TYPE", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OrderType.entries.forEach { type ->
                            FilterChip(
                                selected = draft.orderType == type,
                                onClick = { viewModel.setOrderType(type) },
                                label = { Text(type.label) },
                                modifier = Modifier.testTag("order_type_${type.name.lowercase()}"),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.primary
                                )
                            )
                        }
                    }

                    Text("PRODUCT TYPE", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = draft.productType == ProductType.INTRADAY,
                            onClick = { viewModel.setProductType(ProductType.INTRADAY) },
                            label = { Text("Intraday (${MarketClock.intradaySquareOffLabel(inst.segment, inst.type)})") },
                            modifier = Modifier.weight(1f).testTag("product_intraday")
                        )
                        FilterChip(
                            selected = draft.productType == ProductType.CARRY_FORWARD,
                            onClick = { if (inst.supportsCarryForward) viewModel.setProductType(ProductType.CARRY_FORWARD) },
                            enabled = inst.supportsCarryForward,
                            label = { Text(if (inst.supportsCarryForward) "Carry Forward" else "CF Restricted") },
                            modifier = Modifier.weight(1f).testTag("product_carry_forward")
                        )
                    }

                    // Quantity + Steppers
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        IconButton(
                            onClick = { viewModel.stepQuantity(-1) },
                            modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(10.dp))
                        ) {
                            Icon(Icons.Rounded.Remove, contentDescription = "Decrease quantity")
                        }
                        OutlinedTextField(
                            value = draft.quantityText,
                            onValueChange = viewModel::updateQuantity,
                            label = { Text("Qty (Lot ${inst.lotSize})") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f).guardGlobalHotkeysOnFocus().testTag("order_quantity_input")
                        )
                        IconButton(
                            onClick = { viewModel.stepQuantity(1) },
                            modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(10.dp))
                        ) {
                            Icon(Icons.Rounded.Add, contentDescription = "Increase quantity")
                        }
                    }
                    val parsedQty = draft.quantityText.toIntOrNull() ?: 0
                    if (parsedQty > 0) {
                        Text(
                            text = "Order Quantity: ${RiskMath.formatQtyWithLots(parsedQty, inst.lotSize)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    // Conditional Limit / Trigger inputs
                    val showLimit = draft.orderType in setOf(OrderType.LIMIT, OrderType.SL_L, OrderType.TGT_L, OrderType.BRACKET)
                    val showTrigger = draft.orderType in setOf(OrderType.SL_M, OrderType.SL_L, OrderType.TGT_M, OrderType.TGT_L)
                    if (showLimit || showTrigger) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (showTrigger) {
                                OutlinedTextField(
                                    value = draft.triggerPriceText,
                                    onValueChange = viewModel::updateTriggerPrice,
                                    label = { Text("Trigger Price") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    singleLine = true,
                                    modifier = Modifier.weight(1f).guardGlobalHotkeysOnFocus().testTag("order_trigger_input")
                                )
                            }
                            if (showLimit) {
                                OutlinedTextField(
                                    value = draft.limitPriceText,
                                    onValueChange = viewModel::updateLimitPrice,
                                    label = { Text(if (draft.orderType == OrderType.BRACKET) "Entry Limit (blank=Mkt)" else "Limit Price") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    singleLine = true,
                                    modifier = Modifier.weight(1f).guardGlobalHotkeysOnFocus().testTag("order_limit_input")
                                )
                            }
                        }
                    }
                }
            }

            // 4. Risk Controls Card (SL, TSL, Target, Trailing Target, OCO)
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, tradingColors.border)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Rounded.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            Text("RISK CONTROLS", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                        }
                        if (draft.slEnabled && draft.targetEnabled) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = tradingColors.violetContainer
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(Icons.Rounded.Link, contentDescription = null, tint = tradingColors.violet, modifier = Modifier.size(14.dp))
                                    Text("OCO Linked", style = MaterialTheme.typography.labelSmall, color = tradingColors.violet)
                                }
                            }
                        }
                    }

                    // Stop Loss Block
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Stop Loss", style = MaterialTheme.typography.titleSmall)
                            Text("Protective exit on adverse move", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = draft.slEnabled,
                            onCheckedChange = viewModel::toggleSl,
                            modifier = Modifier.testTag("sl_toggle_switch")
                        )
                    }

                    if (draft.slEnabled) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            RiskInputMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = draft.slMode == mode,
                                    onClick = { viewModel.setSlMode(mode) },
                                    label = { Text(mode.label) }
                                )
                            }
                        }
                        OutlinedTextField(
                            value = draft.slValueText,
                            onValueChange = viewModel::updateSlValue,
                            label = { Text(if (draft.slMode == RiskInputMode.PERCENT) "SL Distance (%)" else "SL Trigger Price (${inst.segment.currencySymbol})") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().guardGlobalHotkeysOnFocus().testTag("sl_value_input")
                        )

                        // Trailing Stop Loss Sub-section
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Trailing Stop Loss (TSL)", style = MaterialTheme.typography.titleSmall, color = tradingColors.violet)
                                Text("Ratchets favourably only; never retraces", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = draft.tslEnabled,
                                onCheckedChange = viewModel::toggleTsl,
                                modifier = Modifier.testTag("tsl_toggle_switch")
                            )
                        }
                        if (draft.tslEnabled) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TrailMode.entries.forEach { mode ->
                                    FilterChip(
                                        selected = draft.tslMode == mode,
                                        onClick = { viewModel.setTslMode(mode) },
                                        label = { Text(mode.label) }
                                    )
                                }
                            }
                            OutlinedTextField(
                                value = draft.tslValueText,
                                onValueChange = viewModel::updateTslValue,
                                label = { Text(if (draft.tslMode == TrailMode.PERCENT) "Trail Distance (%)" else "Trail Amount (${inst.segment.currencySymbol})") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().guardGlobalHotkeysOnFocus().testTag("tsl_value_input")
                            )
                        }
                    }

                    // Target Block
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Target Profit", style = MaterialTheme.typography.titleSmall)
                            Text("Take-profit exit trigger", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = draft.targetEnabled,
                            onCheckedChange = viewModel::toggleTarget,
                            modifier = Modifier.testTag("target_toggle_switch")
                        )
                    }

                    if (draft.targetEnabled) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            RiskInputMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = draft.targetMode == mode,
                                    onClick = { viewModel.setTargetMode(mode) },
                                    label = { Text(mode.label) }
                                )
                            }
                        }
                        OutlinedTextField(
                            value = draft.targetValueText,
                            onValueChange = viewModel::updateTargetValue,
                            label = { Text(if (draft.targetMode == RiskInputMode.PERCENT) "Target Distance (%)" else "Target Price (${inst.segment.currencySymbol})") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().guardGlobalHotkeysOnFocus().testTag("target_value_input")
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Trailing Target", style = MaterialTheme.typography.titleSmall, color = tradingColors.bullish)
                                Text("Arms at Target & trails exit behind price", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = draft.trailingTargetEnabled,
                                onCheckedChange = viewModel::toggleTrailingTarget,
                                modifier = Modifier.testTag("trailing_target_toggle_switch")
                            )
                        }
                        if (draft.trailingTargetEnabled) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TrailMode.entries.forEach { mode ->
                                    FilterChip(
                                        selected = draft.trailingTargetMode == mode,
                                        onClick = { viewModel.setTrailingTargetMode(mode) },
                                        label = { Text(mode.label) }
                                    )
                                }
                            }
                            OutlinedTextField(
                                value = draft.trailingTargetValueText,
                                onValueChange = viewModel::updateTrailingTargetValue,
                                label = { Text(if (draft.trailingTargetMode == TrailMode.PERCENT) "Trailing Target Pullback (%)" else "Pullback Amount (${inst.segment.currencySymbol})") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().guardGlobalHotkeysOnFocus().testTag("trailing_target_value_input")
                            )
                        }
                    }
                }
            }

            // 5. Trade Validity Card (DAY / GTT / GTD)
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, tradingColors.border)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("TRADE VALIDITY", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ValidityType.entries.forEach { type ->
                            FilterChip(
                                selected = draft.validityType == type,
                                onClick = { viewModel.setValidityType(type) },
                                label = { Text(type.label) },
                                modifier = Modifier.weight(1f).testTag("validity_${type.name.lowercase()}")
                            )
                        }
                    }

                    when (draft.validityType) {
                        ValidityType.DAY -> {
                            Text(
                                text = "Valid for current session only. Untriggered legs auto-cancel at ${MarketClock.intradaySquareOffLabel(inst.segment, inst.type)} square-off.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        ValidityType.GTT -> {
                            OutlinedTextField(
                                value = draft.gttDaysText,
                                onValueChange = viewModel::updateGttDays,
                                label = { Text("GTT Validity Days (1 - 365)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().guardGlobalHotkeysOnFocus().testTag("gtt_days_input")
                            )
                        }
                        ValidityType.GTD -> {
                            OutlinedButton(
                                onClick = { showGtdDatePicker = true },
                                modifier = Modifier.fillMaxWidth().testTag("gtd_date_button"),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Rounded.CalendarMonth, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Valid Until: ${MarketClock.formatIstDate(draft.gtdDateMillis)} (Session Close)")
                            }
                        }
                    }
                }
            }

            // 5b. AI Pre-Trade Risk Audit (Advisory)
            AiSecondOpinionCard(
                title = "AI Pre-Trade Risk Audit",
                subtitle = "Checks planned Entry, SL, Target & product fit against recent candles",
                ctaLabel = "Audit Draft Order with AI",
                aiState = state.aiAuditState,
                onAnalyze = viewModel::runAiSetupAudit,
                ctaTestTag = "ai_audit_setup_button",
                connectionStatus = state.aiConnectionStatus,
                onSwitchModel = viewModel::switchAiModel,
                onOpenAiSettings = onOpenFeedSettings
            )

            // 6. Margin Summary & Place Order CTA
            val qty = draft.quantityText.toIntOrNull() ?: 0
            val estPrice = when (draft.orderType) {
                OrderType.MARKET -> inst.ltp
                else -> draft.limitPriceText.toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO } ?: inst.ltp
            }
            val notional = estPrice.multiply(BigDecimal(qty))
            val marginRequired = if (draft.productType == ProductType.INTRADAY && inst.type != com.example.domain.model.InstrumentType.OPTIONS) {
                notional.multiply(BigDecimal("0.25"))
            } else {
                notional
            }
            val isCrypto = inst.segment == com.example.domain.model.MarketSegment.CRYPTO
            val availableMargin = state.accountState?.let {
                if (isCrypto) it.cryptoAvailableMargin else it.availableMargin
            }
            val insufficientMargin = availableMargin != null && marginRequired > availableMargin

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, tradingColors.border)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Est. Margin Required", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            text = RiskMath.formatCurrency(marginRequired, inst.segment),
                            style = MonoPriceMedium,
                            color = if (insufficientMargin) tradingColors.bearish else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    availableMargin?.let { avail ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                text = if (isCrypto) "Available Crypto Margin ($)" else "Available Margin (₹)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = RiskMath.formatCurrency(avail, inst.segment),
                                style = MonoPriceSmall,
                                color = if (insufficientMargin) tradingColors.bearish else tradingColors.bullish
                            )
                        }
                    }
                    val estCharges = remember(inst.segment, inst.type, draft.productType, draft.side, notional, state.config) {
                        RiskMath.calculateOrderCharges(
                            segment = inst.segment,
                            instrumentType = inst.type,
                            productType = draft.productType,
                            side = draft.side,
                            turnover = notional,
                            flatBrokeragePerOrder = state.config.flatBrokerage,
                            cryptoFeePercent = state.config.cryptoFeePercent
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Est. Charges & Taxes (This Leg)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(RiskMath.formatPrice(estCharges.total, inst.segment), style = MonoPriceSmall, color = tradingColors.amber)
                    }
                    val estTaxPart = estCharges.sttCtt.add(estCharges.exchangeCharges).add(estCharges.sebiCharges).add(estCharges.stampDuty).add(estCharges.gst)
                    Text(
                        text = "Brok ${RiskMath.formatPrice(estCharges.brokerage, inst.segment)} • STT/CTT ${RiskMath.formatPrice(estCharges.sttCtt, inst.segment)} • Exch/SEBI/Stamp/GST ${RiskMath.formatPrice(estTaxPart.subtract(estCharges.sttCtt), inst.segment)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Simulated Slippage", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${state.config.slippageBps} bps", style = MonoPriceSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    state.orderFeedbackMessage?.let { feedbackMsg ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (state.orderFeedbackIsError) tradingColors.bearishContainer else tradingColors.bullishContainer,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("order_feedback_banner")
                        ) {
                            Text(
                                text = feedbackMsg,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (state.orderFeedbackIsError) tradingColors.bearish else tradingColors.bullish,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    val effectiveCtaType = state.priceAwarePlan?.takeIf {
                        draft.priceAwareGuardEnabled && draft.signalTargetEntryPrice != null
                    }?.effectiveOrderType ?: draft.orderType
                    val ctaProtectionTag = when (state.priceAwarePlan?.takeIf { draft.priceAwareGuardEnabled && draft.signalTargetEntryPrice != null }?.decision) {
                        PriceAwareExecutionDecision.PROTECTED_LIMIT_AT_ENTRY -> " (LIMIT @ ENTRY)"
                        PriceAwareExecutionDecision.PROTECTED_STOP_LIMIT_ENTRY -> " (SL-L @ ENTRY)"
                        else -> ""
                    }
                    Button(
                        onClick = viewModel::placeOrder,
                        enabled = !state.isSubmitting,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("place_paper_order_button"),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (draft.side == OrderSide.BUY) tradingColors.bullish else tradingColors.bearish,
                            contentColor = Color.White
                        )
                    ) {
                        Text(
                            text = if (state.isSubmitting) "EXECUTING..." else "PLACE PAPER ${draft.side.name} • ${effectiveCtaType.label.uppercase()}$ctaProtectionTag",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showGtdDatePicker) {
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = draft.gtdDateMillis)
        DatePickerDialog(
            onDismissRequest = { showGtdDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { viewModel.updateGtdDateMillis(it) }
                    showGtdDatePicker = false
                }) {
                    Text("Confirm")
                }
            },
            dismissButton = {
                TextButton(onClick = { showGtdDatePicker = false }) {
                    Text("Cancel")
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

@Composable
private fun PriceAwareExecutionCard(
    plan: PriceAwareExecutionPlan,
    segment: com.example.domain.model.MarketSegment,
    hasSignalTarget: Boolean,
    guardEnabled: Boolean,
    slippageTolerancePercent: Double,
    onToggleGuard: (Boolean) -> Unit,
    onSlippageToleranceChange: (Double) -> Unit,
    onClearSignalEntry: () -> Unit
) {
    val tradingColors = LocalTradingColors.current
    val decisionColor = when (plan.decision) {
        PriceAwareExecutionDecision.WITHIN_ENTRY_ZONE -> tradingColors.bullish
        PriceAwareExecutionDecision.PROTECTED_LIMIT_AT_ENTRY -> tradingColors.amber
        PriceAwareExecutionDecision.PROTECTED_STOP_LIMIT_ENTRY -> tradingColors.violet
        PriceAwareExecutionDecision.MANUAL_PASSTHROUGH -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val decisionContainer = when (plan.decision) {
        PriceAwareExecutionDecision.WITHIN_ENTRY_ZONE -> tradingColors.bullishContainer
        PriceAwareExecutionDecision.PROTECTED_LIMIT_AT_ENTRY -> tradingColors.amber.copy(alpha = 0.16f)
        PriceAwareExecutionDecision.PROTECTED_STOP_LIMIT_ENTRY -> tradingColors.violetContainer
        PriceAwareExecutionDecision.MANUAL_PASSTHROUGH -> MaterialTheme.colorScheme.surfaceContainerHigh
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            if (plan.wasConvertedToProtectedOrder) decisionColor.copy(alpha = 0.65f) else tradingColors.border
        ),
        modifier = Modifier.fillMaxWidth().testTag("price_aware_execution_card")
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                    Icon(
                        imageVector = Icons.Rounded.Shield,
                        contentDescription = null,
                        tint = decisionColor,
                        modifier = Modifier.size(18.dp)
                    )
                    Column {
                        Text(
                            text = if (hasSignalTarget) "PRICE-AWARE SIGNAL GUARD" else "ORDER BOOK LIQUIDITY",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Spread ${String.format(java.util.Locale.US, "%.2f%%", plan.orderBook.spreadPercent)} • Top Book ${plan.bookFillEstimate.topLevelCoveragePercent}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = decisionContainer
                ) {
                    Text(
                        text = plan.decision.badgeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = decisionColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            // Live Order Book Top-of-Book & VWAP Strip
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text("BEST BID", style = MaterialTheme.typography.labelSmall, color = tradingColors.bullish)
                        Text(RiskMath.formatPrice(plan.orderBook.bestBid, segment), style = MonoPriceSmall)
                    }
                }
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text("BEST ASK", style = MaterialTheme.typography.labelSmall, color = tradingColors.bearish)
                        Text(RiskMath.formatPrice(plan.orderBook.bestAsk, segment), style = MonoPriceSmall)
                    }
                }
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text("EST. VWAP FILL", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        Text(RiskMath.formatPrice(plan.estimatedBookFillPrice, segment), style = MonoPriceSmall)
                    }
                }
            }

            if (hasSignalTarget && plan.targetEntryPrice != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Target Entry ${RiskMath.formatPrice(plan.targetEntryPrice, segment)} vs LTP ${RiskMath.formatPrice(plan.ltp, segment)} (${String.format(java.util.Locale.US, "%+.2f%%", plan.deviationFromEntryPercent)})",
                            style = MonoPriceSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = decisionColor
                        )
                        Text(
                            text = "Prevent immediate market fill outside tolerance",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = guardEnabled,
                        onCheckedChange = onToggleGuard,
                        modifier = Modifier.testTag("price_aware_guard_switch")
                    )
                }

                if (guardEnabled) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = decisionContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = plan.summary,
                            style = MaterialTheme.typography.labelSmall,
                            color = decisionColor,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Slippage Tolerance: ±${String.format(java.util.Locale.US, "%.2f%%", slippageTolerancePercent)}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        TextButton(onClick = onClearSignalEntry) {
                            Text("Clear Entry Lock", style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(0.15, 0.35, 0.75, 1.50).forEach { preset ->
                            val selected = kotlin.math.abs(slippageTolerancePercent - preset) < 0.04
                            FilterChip(
                                selected = selected,
                                onClick = { onSlippageToleranceChange(preset) },
                                label = { Text("${String.format(java.util.Locale.US, "%.2f", preset)}%", style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.weight(1f).testTag("slippage_preset_${(preset * 100).toInt()}")
                            )
                        }
                    }

                    Slider(
                        value = slippageTolerancePercent.toFloat().coerceIn(0.10f, 2.50f),
                        onValueChange = { onSlippageToleranceChange(it.toDouble()) },
                        valueRange = 0.10f..2.50f,
                        modifier = Modifier.fillMaxWidth().testTag("slippage_tolerance_slider")
                    )
                }
            }
        }
    }
}

@Composable
private fun OrderSideButton(
    label: String,
    selected: Boolean,
    activeColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tradingColors = LocalTradingColors.current
    Surface(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) activeColor else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (selected) activeColor else tradingColors.border)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
