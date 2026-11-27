package com.example.ui.screens.strategy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import com.example.domain.model.MarketSegment
import com.example.domain.strategy.MetricScreener
import com.example.domain.strategy.PortfolioRebalancer
import com.example.domain.strategy.SavedScreenRule
import com.example.domain.strategy.ScreenMetric
import com.example.domain.strategy.SetupScanner
import com.example.domain.strategy.TaxLotTracker
import com.example.ui.theme.LocalTradingColors
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/**
 * The strategy workbench: one pane per engine ported from the trader skill packs that the
 * order ticket's risk panel does not already use.
 *
 * Nothing here computes anything itself - every number on screen comes from
 * [SetupScanner], [com.example.domain.strategy.TradeOutcomeSimulator], [TaxLotTracker],
 * [PortfolioRebalancer] or [MetricScreener], so the Kotlin and the web build stay in step.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StrategyWorkbenchScreen(
    onBack: () -> Unit,
    onOpenTicket: (symbol: String, side: String, quantity: Int) -> Unit,
    viewModel: StrategyWorkbenchViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        val msg = state.message
        if (msg != null) {
            snackbarHostState.showSnackbar(msg)
            viewModel.dismissMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Strategy Workbench") },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("workbench_back_button")) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            ScrollableTabRow(
                selectedTabIndex = state.tab.ordinal,
                edgePadding = 8.dp,
                modifier = Modifier.testTag("workbench_tabs")
            ) {
                WorkbenchTab.entries.forEach { tab ->
                    Tab(
                        selected = state.tab == tab,
                        onClick = { viewModel.selectTab(tab) },
                        text = { Text(tab.label) }
                    )
                }
            }

            if (state.isWorking) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(12.dp).testTag("workbench_progress")
                )
            }

            when (state.tab) {
                WorkbenchTab.SCANNER -> ScannerPane(state, viewModel, onOpenTicket)
                WorkbenchTab.SIMULATOR -> SimulatorPane(state, viewModel)
                WorkbenchTab.TAX_LOTS -> TaxLotPane(state, viewModel)
                WorkbenchTab.REBALANCE -> RebalancePane(state, viewModel, onOpenTicket)
                WorkbenchTab.SCREENER -> ScreenerPane(state, viewModel)
            }
        }
    }
}

// =========================================================================
// Shared bits
// =========================================================================

@Composable
private fun PaneCard(
    title: String,
    subtitle: String? = null,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = LocalTradingColors.current.cardElevated
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            if (subtitle != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun StatRow(label: String, value: String, valueColor: Color = Color.Unspecified) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = valueColor
        )
    }
}

@Composable
private fun NumberField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier
    )
}

/** A plain dropdown; the exposed-menu variant is still experimental and fiddlier than this. */
@Composable
private fun <T> PickerButton(
    label: String,
    selected: T?,
    options: List<T>,
    optionLabel: (T) -> String,
    onPick: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        OutlinedButton(onClick = { open = true }) {
            Text("$label: ${selected?.let(optionLabel) ?: "—"}", maxLines = 1)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        onPick(option)
                        open = false
                    }
                )
            }
        }
    }
}

@Composable
private fun EmptyNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 10.dp)
    )
}

private fun pct(v: Double): String = String.format("%.1f%%", v * 100)
private fun signed(v: Double): String = (if (v >= 0) "+" else "") + String.format("%.2f", v)
private fun money(v: BigDecimal): String =
    "₹" + v.setScale(2, RoundingMode.HALF_UP).toPlainString()

// =========================================================================
// Scanner
// =========================================================================

@Composable
private fun ScannerPane(
    state: StrategyWorkbenchUiState,
    viewModel: StrategyWorkbenchViewModel,
    onOpenTicket: (String, String, Int) -> Unit
) {
    val colors = LocalTradingColors.current

    LazyColumn(modifier = Modifier.fillMaxSize().testTag("workbench_scanner")) {
        item {
            PaneCard(
                title = "What is set up today",
                subtitle = "Daily setups need at least ${SetupScanner.MIN_DAILY_CLOSES} completed " +
                    "daily closes. A breakout must close above the opening range's high, not just " +
                    "spike through it."
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    NumberField(
                        label = "Range bars",
                        value = state.scannerOpeningRangeBars.toString(),
                        onValueChange = { v ->
                            v.toIntOrNull()?.let(viewModel::setScannerOpeningRangeBars)
                        },
                        modifier = Modifier.width(120.dp)
                    )
                    NumberField(
                        label = "Min vol ×",
                        value = state.scannerMinVolumeMultiple?.toString().orEmpty(),
                        onValueChange = { v -> viewModel.setScannerMinVolumeMultiple(v.toDoubleOrNull()) },
                        modifier = Modifier.width(120.dp)
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Switch(
                        checked = state.scannerOnlySetups,
                        onCheckedChange = viewModel::setScannerOnlySetups
                    )
                    Text("Only rows with a setup", style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = state.scannerSegment == null,
                        onClick = { viewModel.setScannerSegment(null) },
                        label = { Text("All") }
                    )
                    MarketSegment.entries.forEach { seg ->
                        FilterChip(
                            selected = state.scannerSegment == seg,
                            onClick = { viewModel.setScannerSegment(seg) },
                            label = { Text(seg.label) }
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = viewModel::runScanner,
                    modifier = Modifier.testTag("workbench_scan_button")
                ) {
                    Text("Rescan")
                }
                if (state.scannerSkippedForHistory > 0) {
                    Spacer(Modifier.height(6.dp))
                    EmptyNote(
                        "${state.scannerSkippedForHistory} instrument(s) skipped for want of " +
                            "daily history. Open a chart to build it."
                    )
                }
            }
        }

        if (state.visibleScannerRows.isEmpty()) {
            item {
                PaneCard(title = "Nothing to show") {
                    EmptyNote(
                        if (state.scannerHasRun) {
                            "No instrument matches. Turn off \"only rows with a setup\" to see " +
                                "everything the scanner looked at."
                        } else {
                            "Tap Rescan to look at every instrument."
                        }
                    )
                }
            }
        }

        items(state.visibleScannerRows, key = { it.symbol }) { row ->
            PaneCard(title = row.symbol, subtitle = row.displayName) {
                StatRow("Last price", money(row.ltp))
                StatRow(
                    "RSI 14 (daily)",
                    row.scan.rsi?.let { String.format("%.1f", it) } ?: "—",
                    when {
                        row.scan.rsi == null -> Color.Unspecified
                        row.scan.rsi!! < 30.0 -> colors.bullish
                        row.scan.rsi!! > 70.0 -> colors.bearish
                        else -> Color.Unspecified
                    }
                )
                StatRow(
                    "vs 20-day high",
                    signed(row.vsHigh20Pct) + "%",
                    if (row.vsHigh20Pct >= 0) colors.bullish else Color.Unspecified
                )
                StatRow(
                    "vs 20-day average",
                    signed(row.vsMa20Pct) + "%",
                    if (row.vsMa20Pct >= 0) colors.bullish else colors.bearish
                )

                Spacer(Modifier.height(8.dp))
                if (row.scan.setups.isEmpty()) {
                    EmptyNote("No daily setup.")
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.scan.setups.forEach { setup ->
                            AssistChip(
                                onClick = {},
                                label = { Text(setup.label, style = MaterialTheme.typography.labelSmall) },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = if (setup == SetupScanner.DailySetup.OVERSOLD) {
                                        colors.amberContainer
                                    } else {
                                        colors.bullishContainer
                                    }
                                )
                            )
                        }
                    }
                }

                val breakout = row.breakout
                Spacer(Modifier.height(8.dp))
                if (breakout == null) {
                    EmptyNote("No close above the opening range high today.")
                } else {
                    StatRow(
                        "Opening-range breakout",
                        "bar ${breakout.barIndex} @ " + String.format("%.2f", breakout.price),
                        colors.bullish
                    )
                    StatRow(
                        "Over the range high by",
                        String.format("%.2f%%", breakout.pctOver)
                    )
                    breakout.volumeMultiple?.let {
                        StatRow("Volume vs range average", String.format("%.2f×", it))
                    }
                }

                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = { onOpenTicket(row.symbol, "BUY", 0) }) {
                    Text("Open ticket")
                }
            }
        }
    }
}

// =========================================================================
// Simulator
// =========================================================================

@Composable
private fun SimulatorPane(
    state: StrategyWorkbenchUiState,
    viewModel: StrategyWorkbenchViewModel
) {
    val colors = LocalTradingColors.current

    LazyColumn(modifier = Modifier.fillMaxSize().testTag("workbench_simulator")) {
        item {
            PaneCard(
                title = "Replay this trade against past sessions",
                subtitle = "Monte Carlo resamples each bar slot from the session pool. When one " +
                    "simulated bar spans both the stop and the target the stop is taken — without " +
                    "tick data the order is unknowable, and assuming the win would inflate every " +
                    "win rate."
            ) {
                PickerButton(
                    label = "Symbol",
                    selected = state.simSymbol.takeIf { it.isNotBlank() },
                    options = state.instruments.map { it.symbol },
                    optionLabel = { it },
                    onPick = viewModel::setSimSymbol
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = state.simIsLong,
                        onClick = { viewModel.setSimIsLong(true) },
                        label = { Text("Long") }
                    )
                    FilterChip(
                        selected = !state.simIsLong,
                        onClick = { viewModel.setSimIsLong(false) },
                        label = { Text("Short") }
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("Entry", state.simEntry, viewModel::setSimEntry, Modifier.weight(1f))
                    NumberField("Stop", state.simStop, viewModel::setSimStop, Modifier.weight(1f))
                    NumberField("Target", state.simTarget, viewModel::setSimTarget, Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(
                        "Bars / session",
                        state.simSessionBars.toString(),
                        { v -> v.toIntOrNull()?.let(viewModel::setSimSessionBars) },
                        Modifier.weight(1f)
                    )
                    NumberField(
                        "Range bars",
                        state.simOpeningRangeBars.toString(),
                        { v -> v.toIntOrNull()?.let(viewModel::setSimOpeningRangeBars) },
                        Modifier.weight(1f)
                    )
                    NumberField(
                        "Entered on bar",
                        state.simFromBar.toString(),
                        { v -> v.toIntOrNull()?.let(viewModel::setSimFromBar) },
                        Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(
                        "Replays",
                        state.simReplays.toString(),
                        { v -> v.toIntOrNull()?.let(viewModel::setSimReplays) },
                        Modifier.weight(1f)
                    )
                    NumberField(
                        "Seed",
                        state.simSeed.toString(),
                        { v -> v.toIntOrNull()?.let(viewModel::setSimSeed) },
                        Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = viewModel::runSimulation,
                        modifier = Modifier.testTag("workbench_simulate_button")
                    ) {
                        Text("Run simulation")
                    }
                    OutlinedButton(onClick = viewModel::fillSimulatorFromMarket) {
                        Text("Fill from LTP & ATR")
                    }
                }
                state.simNote?.let {
                    Spacer(Modifier.height(8.dp))
                    EmptyNote(it)
                }
            }
        }

        state.simMonteCarlo?.let { mc ->
            item {
                PaneCard(
                    title = "Monte Carlo",
                    subtitle = "${mc.replays} replays of the ${mc.barsLeft} bars left in the " +
                        "session, seeded at ${state.simSeed} — the same seed always replays the " +
                        "same session."
                ) {
                    StatRow("Hits target", pct(mc.probabilityTarget), colors.bullish)
                    StatRow("Hits stop", pct(mc.probabilityStop), colors.bearish)
                    StatRow("Neither, open at the close", pct(mc.probabilityNeither))
                    StatRow(
                        "Green at the close",
                        pct(mc.probabilityGreen),
                        if (mc.probabilityGreen >= 0.5) colors.bullish else colors.bearish
                    )
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    StatRow(
                        "Average outcome",
                        signed(mc.averageR) + "R",
                        if (mc.averageR >= 0) colors.bullish else colors.bearish
                    )
                    StatRow("Target is worth", String.format("%.2fR", mc.rAtTarget))
                    StatRow("Close, median", String.format("%.2f", mc.medianClose))
                    StatRow(
                        "Close, 5th to 95th",
                        String.format("%.2f – %.2f", mc.p5Close, mc.p95Close)
                    )
                }
            }
        }

        state.simHistory?.let { replay ->
            item {
                PaneCard(
                    title = "History replay",
                    subtitle = "The opening-range rule run over every loaded session as it " +
                        "actually happened, stopping at the opening-range low."
                ) {
                    StatRow("Sessions", replay.sessions.toString())
                    StatRow("Triggered the rule", replay.triggered.toString())
                    StatRow("Reached target", replay.target.toString(), colors.bullish)
                    StatRow("Stopped out", replay.stop.toString(), colors.bearish)
                    StatRow("Neither", replay.neither.toString())
                    StatRow("Win rate", replay.winRate?.let { pct(it) } ?: "—")
                    StatRow(
                        "Average R",
                        replay.averageR?.let { signed(it) + "R" } ?: "—",
                        when {
                            replay.averageR == null -> Color.Unspecified
                            replay.averageR!! >= 0 -> colors.bullish
                            else -> colors.bearish
                        }
                    )
                    if (replay.triggered == 0) {
                        EmptyNote("The rule never triggered in these sessions.")
                    }
                }
            }
        }
    }
}

// =========================================================================
// Tax lots
// =========================================================================

@Composable
private fun TaxLotPane(
    state: StrategyWorkbenchUiState,
    viewModel: StrategyWorkbenchViewModel
) {
    val colors = LocalTradingColors.current
    var addDate by remember { mutableStateOf(LocalDate.now().toString()) }
    var addShares by remember { mutableStateOf("100") }
    var addCost by remember { mutableStateOf("") }

    LazyColumn(modifier = Modifier.fillMaxSize().testTag("workbench_tax_lots")) {
        item {
            PaneCard(
                title = "Lot book",
                subtitle = "Indian listed equity turns long-term only after more than 12 months, " +
                    "so a sale on the anniversary itself is still short-term. There is no " +
                    "wash-sale rule to model. FIFO is what the depositories actually apply — " +
                    "highest-cost and specific-lot are planning views, not filing methods."
            ) {
                PickerButton(
                    label = "Symbol",
                    selected = state.lotSymbol.takeIf { it.isNotBlank() },
                    options = state.instruments.map { it.symbol },
                    optionLabel = { it },
                    onPick = viewModel::setLotSymbol
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = viewModel::importPositionsAsLots,
                        modifier = Modifier.testTag("workbench_import_lots_button")
                    ) {
                        Text("Import positions")
                    }
                    TextButton(onClick = viewModel::clearLotsForSymbol) {
                        Text("Clear symbol")
                    }
                }
            }
        }

        if (state.lotsForSymbol.isEmpty()) {
            item {
                PaneCard(title = "No lots yet") {
                    EmptyNote(
                        "Nothing recorded for ${state.lotSymbol.ifBlank { "this symbol" }}. " +
                            "Add a lot below, or import your open carry-forward positions."
                    )
                }
            }
        } else {
            items(state.lotsForSymbol, key = { it.id }) { lot ->
                val price = state.instruments.firstOrNull { it.symbol == lot.symbol }?.ltp
                    ?: BigDecimal.ZERO
                val cost = (lot.costPerShare.toBigDecimalOrNull() ?: BigDecimal.ZERO)
                    .multiply(BigDecimal(lot.shares))
                val value = price.multiply(BigDecimal(lot.shares))
                val gain = value.subtract(cost)
                val term = TaxLotTracker.termFor(LocalDate.parse(lot.acquired), LocalDate.now())

                PaneCard(title = "Lot ${lot.id}", subtitle = "acquired ${lot.acquired}") {
                    StatRow("Shares", lot.shares.toString())
                    StatRow("Cost per share", "₹" + lot.costPerShare)
                    StatRow("Cost", money(cost))
                    StatRow("Market value", money(value))
                    StatRow(
                        "Unrealised",
                        money(gain),
                        if (gain >= BigDecimal.ZERO) colors.bullish else colors.bearish
                    )
                    StatRow(
                        "Term today",
                        if (term == TaxLotTracker.Term.LONG) "Long term" else "Short term",
                        if (term == TaxLotTracker.Term.LONG) colors.bullish else colors.amber
                    )
                    Spacer(Modifier.height(6.dp))
                    TextButton(onClick = { viewModel.removeTaxLot(lot.id) }) {
                        Icon(Icons.Rounded.Close, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Remove")
                    }
                }
            }
        }

        item {
            PaneCard(title = "Add a lot") {
                OutlinedTextField(
                    value = addDate,
                    onValueChange = { addDate = it },
                    label = { Text("Acquired (yyyy-MM-dd)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("Shares", addShares, { addShares = it }, Modifier.weight(1f))
                    NumberField("Cost / share", addCost, { addCost = it }, Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = {
                        val parsed = runCatching { LocalDate.parse(addDate) }.getOrNull()
                        val shares = addShares.toIntOrNull() ?: 0
                        val cost = addCost.toBigDecimalOrNull() ?: BigDecimal.ZERO
                        if (parsed == null) return@Button
                        viewModel.addTaxLot(parsed, shares, cost)
                        addCost = ""
                    },
                    modifier = Modifier.testTag("workbench_add_lot_button")
                ) {
                    Text("Add lot")
                }
            }
        }

        item {
            PaneCard(title = "What would this sale realise?") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(
                        "Shares",
                        state.saleShares,
                        viewModel::setSaleShares,
                        Modifier.weight(1f)
                    )
                    NumberField(
                        "Price",
                        state.salePrice,
                        viewModel::setSalePrice,
                        Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(8.dp))
                PickerButton(
                    label = "Method",
                    selected = state.saleMethod,
                    options = TaxLotTracker.Method.entries.toList(),
                    optionLabel = { m ->
                        when (m) {
                            TaxLotTracker.Method.FIFO -> "FIFO (what the depository does)"
                            TaxLotTracker.Method.HIGHEST_COST -> "Highest cost first (planning)"
                            TaxLotTracker.Method.SPECIFIC -> "Specific lots (planning)"
                        }
                    },
                    onPick = viewModel::setSaleMethod
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = viewModel::runSaleSimulation,
                    modifier = Modifier.testTag("workbench_simulate_sale_button")
                ) {
                    Text("Simulate sale")
                }

                state.saleError?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = colors.bearish)
                }

                state.saleResult?.let { res ->
                    Spacer(Modifier.height(10.dp))
                    StatRow("Shares sold", res.shares.toString())
                    StatRow("Proceeds", money(res.proceeds))
                    StatRow("Cost basis", money(res.basis))
                    StatRow(
                        "Total gain",
                        money(res.gain),
                        if (res.gain >= BigDecimal.ZERO) colors.bullish else colors.bearish
                    )
                    StatRow(
                        "Short term (12 months or less)",
                        money(res.shortTermGain),
                        if (res.shortTermGain >= BigDecimal.ZERO) colors.bullish else colors.bearish
                    )
                    StatRow(
                        "Long term (over 12 months)",
                        money(res.longTermGain),
                        if (res.longTermGain >= BigDecimal.ZERO) colors.bullish else colors.bearish
                    )
                    Spacer(Modifier.height(6.dp))
                    EmptyNote(
                        "The split by holding period is the part that does not change; the rates " +
                            "move with every budget, so none is applied here."
                    )
                }
            }
        }

        item {
            PaneCard(
                title = "Loss-harvest candidates",
                subtitle = "Lots across every symbol sitting on a loss of at least the threshold, " +
                    "worst first."
            ) {
                NumberField(
                    "Minimum loss",
                    state.harvestMinLoss,
                    viewModel::setHarvestMinLoss,
                    Modifier.width(160.dp)
                )
                Spacer(Modifier.height(8.dp))
                if (state.harvestRows.isEmpty()) {
                    EmptyNote("No lot is sitting on a loss that large.")
                } else {
                    state.harvestRows.forEach { row ->
                        StatRow(
                            "${row.symbol} · ${row.lot.lot.id} · ${row.lot.lot.acquired}",
                            money(row.lot.gain),
                            colors.bearish
                        )
                    }
                }
            }
        }
    }
}

// =========================================================================
// Rebalancer
// =========================================================================

@Composable
private fun RebalancePane(
    state: StrategyWorkbenchUiState,
    viewModel: StrategyWorkbenchViewModel,
    onOpenTicket: (String, String, Int) -> Unit
) {
    val colors = LocalTradingColors.current

    LazyColumn(modifier = Modifier.fillMaxSize().testTag("workbench_rebalance")) {
        item {
            PaneCard(
                title = "Pull the outliers back to target",
                subtitle = "Only holdings outside the drift band are traded: churning the ones " +
                    "already on target costs brokerage and STT for no change in exposure. Sells " +
                    "are planned before buys so the cash they release funds the buys in the same " +
                    "pass, and quantities are whole shares."
            ) {
                StatRow("Cash available", money(state.freeCash))
                Spacer(Modifier.height(8.dp))
                NumberField(
                    "Drift band (points)",
                    state.rebalanceBandPct,
                    viewModel::setRebalanceBandPct,
                    Modifier.width(180.dp)
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Switch(
                        checked = state.rebalanceSellUntracked,
                        onCheckedChange = viewModel::setRebalanceSellUntracked
                    )
                    Text("Sell holdings with no target", style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = viewModel::equalWeightHeld) {
                        Text("Equal-weight held")
                    }
                    Button(
                        onClick = viewModel::runRebalance,
                        modifier = Modifier.testTag("workbench_rebalance_button")
                    ) {
                        Text("Build plan")
                    }
                }
            }
        }

        item {
            PaneCard(title = "Target weights") {
                if (state.workbench.rebalanceTargets.isEmpty()) {
                    EmptyNote(
                        "No targets yet. \"Equal-weight held\" fills them from your open " +
                            "carry-forward positions."
                    )
                } else {
                    state.workbench.rebalanceTargets.forEach { target ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(target.symbol, modifier = Modifier.weight(1f))
                            NumberField(
                                label = "Weight",
                                value = target.weight,
                                onValueChange = { viewModel.setTargetWeight(target.symbol, it) },
                                modifier = Modifier.width(120.dp)
                            )
                            IconButton(onClick = { viewModel.removeTarget(target.symbol) }) {
                                Icon(Icons.Rounded.Close, contentDescription = "Remove target")
                            }
                        }
                    }
                }
            }
        }

        state.rebalanceError?.let { err ->
            item {
                PaneCard(title = "Cannot build a plan") {
                    Text(err, style = MaterialTheme.typography.bodySmall, color = colors.bearish)
                }
            }
        }

        state.rebalancePlan?.let { plan ->
            item {
                PaneCard(title = "Drift") {
                    StatRow("Portfolio value", money(plan.totalValue))
                    StatRow("Cash", money(plan.cash))
                    StatRow("Cash buffer held back", money(plan.cashBuffer))
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    plan.rows.forEach { row ->
                        StatRow(
                            "${row.symbol} · ${row.action}",
                            "${row.currentPct.setScale(2, RoundingMode.HALF_UP).toPlainString()}% " +
                                "→ ${row.targetPct.setScale(2, RoundingMode.HALF_UP).toPlainString()}%",
                            if (row.action == "hold") Color.Unspecified else colors.amber
                        )
                    }
                    if (plan.notes.isNotEmpty()) {
                        HorizontalDivider(Modifier.padding(vertical = 6.dp))
                        plan.notes.forEach { note ->
                            Text(
                                note,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.amber,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            item {
                PaneCard(
                    title = "Proposed trades",
                    subtitle = "Sells first — their proceeds fund the buys in the same pass."
                ) {
                    if (plan.trades.isEmpty()) {
                        EmptyNote("Every holding is inside the band. Nothing worth the brokerage.")
                    }
                }
            }

            items(plan.trades, key = { it.symbol + it.isBuy.toString() }) { trade ->
                TradeRow(trade, onOpenTicket)
            }
        }
    }
}

@Composable
private fun TradeRow(
    trade: PortfolioRebalancer.Trade,
    onOpenTicket: (String, String, Int) -> Unit
) {
    val colors = LocalTradingColors.current
    PaneCard(
        title = "${if (trade.isBuy) "BUY" else "SELL"} ${trade.quantity} ${trade.symbol}",
        subtitle = trade.why
    ) {
        StatRow("Price", money(trade.price))
        StatRow(
            "Value",
            money(trade.value),
            if (trade.isBuy) colors.bullish else colors.bearish
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                onOpenTicket(trade.symbol, if (trade.isBuy) "BUY" else "SELL", trade.quantity)
            }
        ) {
            Text("Open ticket")
        }
    }
}

// =========================================================================
// Screener
// =========================================================================

@Composable
private fun ScreenerPane(
    state: StrategyWorkbenchUiState,
    viewModel: StrategyWorkbenchViewModel
) {
    val colors = LocalTradingColors.current

    LazyColumn(modifier = Modifier.fillMaxSize().testTag("workbench_screener")) {
        item {
            PaneCard(
                title = "Screen against your own rules",
                subtitle = "Every row says which rule it passed or failed and by how much — a " +
                    "screen that returns only a list of symbols cannot be argued with. Metrics " +
                    "come from this app's own price data, so they are technical, not fundamental."
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Switch(
                        checked = state.screenMissingFails,
                        onCheckedChange = viewModel::setScreenMissingFails
                    )
                    Text("A missing metric fails the rule", style = MaterialTheme.typography.bodySmall)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Switch(
                        checked = state.screenOnlyPassing,
                        onCheckedChange = viewModel::setScreenOnlyPassing
                    )
                    Text("Only passing rows", style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(8.dp))
                PickerButton(
                    label = "Rank by",
                    selected = state.screenRankBy,
                    options = listOf("") + ScreenMetric.entries.filter { it.numeric }.map { it.key },
                    optionLabel = { key ->
                        if (key.isBlank()) "nothing (keep rule order)"
                        else ScreenMetric.byKey(key)?.label ?: key
                    },
                    onPick = { viewModel.setScreenRankBy(it.takeIf { k -> k.isNotBlank() }) }
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = viewModel::addScreenRule) { Text("Add rule") }
                    TextButton(onClick = viewModel::resetScreenRules) { Text("Reset") }
                    Button(
                        onClick = viewModel::runScreen,
                        modifier = Modifier.testTag("workbench_screen_button")
                    ) {
                        Text("Run screen")
                    }
                }
            }
        }

        itemsIndexed(state.workbench.screenRules) { index, rule ->
            ScreenRuleRow(
                rule = rule,
                onChange = { viewModel.updateScreenRule(index, it) },
                onRemove = { viewModel.removeScreenRule(index) }
            )
        }

        if (state.screenHasRun) {
            val shown = if (state.screenOnlyPassing) {
                state.screenResults.filter { it.passed }
            } else {
                state.screenResults
            }
            item {
                PaneCard(
                    title = "${state.screenResults.count { it.passed }} of " +
                        "${state.screenResults.size} pass"
                ) {
                    if (shown.isEmpty()) {
                        EmptyNote(
                            "Nothing passed. Turn off \"only passing rows\" to see why each one failed."
                        )
                    }
                }
            }
            items(shown, key = { it.ticker }) { result ->
                PaneCard(
                    title = result.ticker,
                    subtitle = result.name
                ) {
                    Text(
                        if (result.passed) "PASS" else "FAIL",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (result.passed) colors.bullish else colors.bearish
                    )
                    Spacer(Modifier.height(6.dp))
                    result.checks.forEach { check ->
                        Text(
                            (when (check.passed) {
                                true -> "✓ "
                                false -> "✗ "
                                null -> "– "
                            }) + check.reason,
                            style = MaterialTheme.typography.bodySmall,
                            color = when (check.passed) {
                                true -> colors.bullish
                                false -> colors.bearish
                                null -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.padding(vertical = 1.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ScreenRuleRow(
    rule: SavedScreenRule,
    onChange: (SavedScreenRule) -> Unit,
    onRemove: () -> Unit
) {
    PaneCard(title = ScreenMetric.byKey(rule.metric)?.label ?: rule.metric) {
        PickerButton(
            label = "Metric",
            selected = ScreenMetric.byKey(rule.metric),
            options = ScreenMetric.entries.toList(),
            optionLabel = { it.label },
            onPick = { onChange(rule.copy(metric = it.key)) }
        )
        Spacer(Modifier.height(8.dp))
        PickerButton(
            label = "Test",
            selected = MetricScreener.Op.entries.firstOrNull { it.symbol == rule.op },
            options = MetricScreener.Op.entries.toList(),
            optionLabel = { it.symbol },
            onPick = { op ->
                // The value's shape depends on the operator, so recast rather than leave a
                // single number where a range or a list belongs.
                val values = when (op) {
                    MetricScreener.Op.BETWEEN ->
                        if (rule.values.size >= 2) rule.values
                        else listOf(rule.values.firstOrNull() ?: "0", "0")

                    MetricScreener.Op.IN, MetricScreener.Op.NOT_IN -> rule.values

                    else -> listOf(rule.values.firstOrNull() ?: "0")
                }
                onChange(rule.copy(op = op.symbol, values = values))
            }
        )
        Spacer(Modifier.height(8.dp))
        when (rule.op) {
            MetricScreener.Op.BETWEEN.symbol -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(
                        "Low",
                        rule.values.getOrElse(0) { "" },
                        { v ->
                            onChange(rule.copy(values = listOf(v, rule.values.getOrElse(1) { "" })))
                        },
                        Modifier.weight(1f)
                    )
                    NumberField(
                        "High",
                        rule.values.getOrElse(1) { "" },
                        { v ->
                            onChange(rule.copy(values = listOf(rule.values.getOrElse(0) { "" }, v)))
                        },
                        Modifier.weight(1f)
                    )
                }
            }

            MetricScreener.Op.IN.symbol, MetricScreener.Op.NOT_IN.symbol -> {
                OutlinedTextField(
                    value = rule.values.joinToString(", "),
                    onValueChange = { text ->
                        onChange(
                            rule.copy(
                                values = text.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                            )
                        )
                    },
                    label = { Text("Values, comma separated") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            else -> {
                OutlinedTextField(
                    value = rule.values.firstOrNull().orEmpty(),
                    onValueChange = { onChange(rule.copy(values = listOf(it))) },
                    label = { Text("Value") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        TextButton(onClick = onRemove) {
            Icon(Icons.Rounded.Close, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text("Remove rule")
        }
    }
}
