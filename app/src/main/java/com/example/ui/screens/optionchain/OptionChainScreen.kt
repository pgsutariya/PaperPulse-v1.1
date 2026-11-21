package com.example.ui.screens.optionchain

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.domain.engine.MarketClock
import com.example.domain.engine.OptionChainRow
import com.example.domain.engine.OptionChainSnapshot
import com.example.domain.engine.OptionGreeksCalculator
import com.example.domain.engine.OptionLegQuote
import com.example.domain.engine.RiskMath
import com.example.ui.theme.LocalTradingColors
import com.example.ui.theme.MonoPriceMedium
import com.example.ui.theme.MonoPriceSmall
import java.math.BigDecimal
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OptionChainScreen(
    onBack: () -> Unit,
    onOpenInstrumentDetail: (String) -> Unit,
    viewModel: OptionChainViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val tradingColors = LocalTradingColors.current
    val listState = rememberLazyListState()
    var showCustomInput by remember { mutableStateOf(false) }
    var customSymbolText by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        viewModel.openTradeEvents.collect { symbol ->
            onOpenInstrumentDetail(symbol)
        }
    }

    // Center list near ATM strike when switching underlying or expiry
    val snap = state.snapshot
    LaunchedEffect(snap?.underlying, snap?.expiry) {
        if (snap != null) {
            val atmIdx = snap.rows.indexOfFirst { it.isAtm }
            if (atmIdx >= 0) {
                listState.scrollToItem((atmIdx - 3).coerceAtLeast(0))
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Option Chain",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (state.isLiveAutoRefresh) tradingColors.bullishContainer else MaterialTheme.colorScheme.surfaceContainerHigh
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(if (state.isLiveAutoRefresh) tradingColors.bullish else MaterialTheme.colorScheme.onSurfaceVariant)
                                )
                                Text(
                                    text = if (state.isLiveAutoRefresh) "LIVE" else "PAUSED",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (state.isLiveAutoRefresh) tradingColors.bullish else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("option_chain_back")) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showCustomInput = !showCustomInput },
                        modifier = Modifier.testTag("option_chain_custom_search")
                    ) {
                        Icon(Icons.Rounded.Search, contentDescription = "Search F&O Underlying")
                    }
                    IconButton(
                        onClick = viewModel::toggleAutoRefresh,
                        modifier = Modifier.testTag("option_chain_toggle_live")
                    ) {
                        Icon(
                            imageVector = if (state.isLiveAutoRefresh) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (state.isLiveAutoRefresh) "Pause live updates" else "Resume live updates"
                        )
                    }
                    IconButton(
                        onClick = { viewModel.manualRefresh(forceMasterReload = false) },
                        modifier = Modifier.testTag("option_chain_refresh")
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Refresh Option Chain")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .navigationBarsPadding()
        ) {
            if (showCustomInput) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = customSymbolText,
                        onValueChange = { customSymbolText = it.uppercase() },
                        placeholder = { Text("Enter underlying (e.g. TATAMOTORS, BANKEX, NATURALGAS)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f).testTag("option_chain_custom_input")
                    )
                    Button(
                        onClick = {
                            if (customSymbolText.isNotBlank()) {
                                viewModel.selectUnderlying(customSymbolText)
                                showCustomInput = false
                            }
                        },
                        modifier = Modifier.testTag("option_chain_custom_go")
                    ) {
                        Text("Load")
                    }
                }
            }

            // 1. Underlying Selector Chips (Index + MCX + Major F&O Stocks)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OptionChainViewModel.PRESET_UNDERLYINGS.forEach { sym ->
                    FilterChip(
                        selected = state.selectedUnderlying == sym,
                        onClick = { viewModel.selectUnderlying(sym) },
                        label = {
                            Text(
                                text = sym,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (state.selectedUnderlying == sym) FontWeight.Bold else FontWeight.Medium
                            )
                        },
                        modifier = Modifier.testTag("oc_underlying_${sym.lowercase()}"),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }
            }

            // 2. Expiry Selector Chips + View Mode Selector
            if (snap != null && snap.availableExpiries.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "EXP:",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold
                    )
                    snap.availableExpiries.take(8).forEach { exp ->
                        FilterChip(
                            selected = snap.expiry == exp,
                            onClick = { viewModel.selectExpiry(exp) },
                            label = { Text(exp, style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.testTag("oc_expiry_$exp")
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OptionChainViewMode.entries.forEach { mode ->
                    FilterChip(
                        selected = state.viewMode == mode,
                        onClick = { viewModel.setViewMode(mode) },
                        label = { Text(mode.label, style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.testTag("oc_mode_${mode.name.lowercase()}"),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = tradingColors.violetContainer,
                            selectedLabelColor = tradingColors.violet
                        )
                    )
                }
                Spacer(Modifier.width(4.dp))
                listOf(8, 12, 18).forEach { w ->
                    FilterChip(
                        selected = state.strikeWindow == w,
                        onClick = { viewModel.selectStrikeWindow(w) },
                        label = { Text("±$w", style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }

            // 3. Spot + PCR + Max Pain + ATM IV Analytics Banner
            if (snap != null) {
                OptionChainAnalyticsCard(
                    snapshot = snap,
                    isRefreshing = state.isRefreshing
                )
            }

            // 4. Error / Loading / Chain Table
            when {
                state.isLoading && snap == null -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.padding(24.dp)
                        ) {
                            CircularProgressIndicator()
                            Text(
                                text = "Loading ${state.selectedUnderlying} live Option Chain, OI & Greeks...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
                state.errorMessage != null && snap == null -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.padding(24.dp)
                        ) {
                            Text(
                                text = state.errorMessage.orEmpty(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = tradingColors.bearish,
                                textAlign = TextAlign.Center
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(onClick = { viewModel.manualRefresh(forceMasterReload = false) }) {
                                    Text("Retry")
                                }
                                TextButton(onClick = { viewModel.manualRefresh(forceMasterReload = true) }) {
                                    Text("Reload Scrip Master")
                                }
                            }
                        }
                    }
                }
                snap != null -> {
                    state.errorMessage?.let { warn ->
                        Text(
                            text = warn,
                            style = MaterialTheme.typography.labelSmall,
                            color = tradingColors.bearish,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp)
                        )
                    }

                    // Table Header
                    OptionChainTableHeader(viewMode = state.viewMode)

                    val maxCallOi = remember(snap.rows) {
                        snap.rows.maxOfOrNull { it.call?.openInterest ?: 0L }?.coerceAtLeast(1L) ?: 1L
                    }
                    val maxPutOi = remember(snap.rows) {
                        snap.rows.maxOfOrNull { it.put?.openInterest ?: 0L }?.coerceAtLeast(1L) ?: 1L
                    }

                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("option_chain_list"),
                        contentPadding = PaddingValues(bottom = 24.dp)
                    ) {
                        items(snap.rows, key = { it.strikePrice.toPlainString() }) { row ->
                            OptionChainStrikeRow(
                                row = row,
                                spotPrice = snap.spotPrice,
                                viewMode = state.viewMode,
                                maxCallOi = maxCallOi,
                                maxPutOi = maxPutOi,
                                openingToken = state.openingToken,
                                onTapLeg = viewModel::openLegForPaperTrade
                            )
                            HorizontalDivider(
                                thickness = 0.5.dp,
                                color = tradingColors.border.copy(alpha = 0.55f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OptionChainAnalyticsCard(
    snapshot: OptionChainSnapshot,
    isRefreshing: Boolean
) {
    val tradingColors = LocalTradingColors.current
    val isPos = snapshot.spotChange >= BigDecimal.ZERO
    val spotColor = if (isPos) tradingColors.bullish else tradingColors.bearish
    val sign = if (isPos) "+" else ""

    val pcrColor = when {
        snapshot.pcr >= 1.05 -> tradingColors.bullish
        snapshot.pcr in 0.01..0.75 -> tradingColors.bearish
        else -> MaterialTheme.colorScheme.onSurface
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, tradingColors.border)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "${snapshot.underlying} SPOT",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Bold
                        )
                        Surface(
                            shape = RoundedCornerShape(5.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                text = snapshot.exchange,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                            )
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "₹${snapshot.spotPrice.toPlainString()}",
                            style = MonoPriceMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "$sign${snapshot.spotChange.toPlainString()} ($sign${snapshot.spotChangePercent.toPlainString()}%)",
                            style = MonoPriceSmall,
                            color = spotColor
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (isRefreshing) {
                            CircularProgressIndicator(modifier = Modifier.size(11.dp), strokeWidth = 1.6.dp)
                        }
                        Text(
                            text = snapshot.greeksSourceLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = tradingColors.violet,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Text(
                        text = "IST ${MarketClock.formatIstTime(snapshot.updatedAtMillis)} • Tap CE/PE to trade",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Key Metrics Row: PCR | Max Pain | ATM IV | Total Call/Put OI
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                MetricMiniPill(
                    label = "PCR",
                    value = if (snapshot.pcr > 0.0) String.format(Locale.US, "%.2f", snapshot.pcr) else "-",
                    valueColor = pcrColor
                )
                MetricMiniPill(
                    label = "MAX PAIN",
                    value = snapshot.maxPainStrike?.stripTrailingZeros()?.toPlainString() ?: "-",
                    valueColor = MaterialTheme.colorScheme.primary
                )
                MetricMiniPill(
                    label = "ATM IV",
                    value = snapshot.atmIvPercent?.let { String.format(Locale.US, "%.1f%%", it) } ?: "-",
                    valueColor = tradingColors.violet
                )
                MetricMiniPill(
                    label = "CE / PE OI",
                    value = "${OptionGreeksCalculator.formatOiCompact(snapshot.totalCallOi)} / ${OptionGreeksCalculator.formatOiCompact(snapshot.totalPutOi)}",
                    valueColor = MaterialTheme.colorScheme.onSurface
                )
            }

            // Total Call OI vs Put OI Ratio Bar
            val totalOi = (snapshot.totalCallOi + snapshot.totalPutOi).coerceAtLeast(1L)
            val callFraction = (snapshot.totalCallOi.toFloat() / totalOi.toFloat()).coerceIn(0.05f, 0.95f)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp))
            ) {
                Box(
                    modifier = Modifier
                        .weight(callFraction)
                        .fillMaxHeight()
                        .background(tradingColors.bearish.copy(alpha = 0.8f))
                )
                Box(
                    modifier = Modifier
                        .weight((1f - callFraction).coerceAtLeast(0.05f))
                        .fillMaxHeight()
                        .background(tradingColors.bullish.copy(alpha = 0.8f))
                )
            }
        }
    }
}

@Composable
private fun MetricMiniPill(
    label: String,
    value: String,
    valueColor: Color
) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 9.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MonoPriceSmall,
            fontWeight = FontWeight.Bold,
            color = valueColor
        )
    }
}

@Composable
private fun OptionChainTableHeader(viewMode: OptionChainViewMode) {
    val tradingColors = LocalTradingColors.current
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = when (viewMode) {
                    OptionChainViewMode.OI_LTP -> "CALL OI • LTP"
                    OptionChainViewMode.GREEKS -> "CALL IV • Δ • Θ • Γ • ν"
                    OptionChainViewMode.ALL -> "CALLS (CE) • OI / LTP / GREEKS"
                },
                style = MaterialTheme.typography.labelSmall,
                color = tradingColors.bullish,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Start
            )
            Text(
                text = "STRIKE",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.width(68.dp),
                textAlign = TextAlign.Center
            )
            Text(
                text = when (viewMode) {
                    OptionChainViewMode.OI_LTP -> "LTP • PUT OI"
                    OptionChainViewMode.GREEKS -> "PUT IV • Δ • Θ • Γ • ν"
                    OptionChainViewMode.ALL -> "PUTS (PE) • OI / LTP / GREEKS"
                },
                style = MaterialTheme.typography.labelSmall,
                color = tradingColors.bearish,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.End
            )
        }
    }
}

@Composable
private fun OptionChainStrikeRow(
    row: OptionChainRow,
    spotPrice: BigDecimal,
    viewMode: OptionChainViewMode,
    maxCallOi: Long,
    maxPutOi: Long,
    openingToken: String?,
    onTapLeg: (OptionLegQuote) -> Unit
) {
    val tradingColors = LocalTradingColors.current
    val callItm = row.strikePrice < spotPrice
    val putItm = row.strikePrice > spotPrice

    val callBg = when {
        row.isAtm -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
        callItm -> tradingColors.bullish.copy(alpha = 0.08f)
        else -> Color.Transparent
    }
    val putBg = when {
        row.isAtm -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
        putItm -> tradingColors.bearish.copy(alpha = 0.08f)
        else -> Color.Transparent
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (row.isAtm) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.14f) else Color.Transparent),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // CALL Side (Left)
        OptionLegCell(
            leg = row.call,
            isCallSide = true,
            viewMode = viewMode,
            oiRatio = ((row.call?.openInterest ?: 0L).toFloat() / maxCallOi.toFloat()).coerceIn(0f, 1f),
            backgroundColor = callBg,
            isOpening = row.call != null && openingToken == row.call.symbolToken,
            onTap = { row.call?.let(onTapLeg) },
            modifier = Modifier.weight(1f)
        )

        // Center Strike Pill
        Box(
            modifier = Modifier
                .width(68.dp)
                .padding(vertical = 6.dp, horizontal = 2.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (row.isAtm) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer,
                border = if (row.isAtm) null else BorderStroke(0.5.dp, tradingColors.border)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = row.strikePrice.stripTrailingZeros().toPlainString(),
                        style = MonoPriceSmall,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        color = if (row.isAtm) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1
                    )
                    if (row.isAtm) {
                        Text(
                            text = "ATM",
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }
        }

        // PUT Side (Right)
        OptionLegCell(
            leg = row.put,
            isCallSide = false,
            viewMode = viewMode,
            oiRatio = ((row.put?.openInterest ?: 0L).toFloat() / maxPutOi.toFloat()).coerceIn(0f, 1f),
            backgroundColor = putBg,
            isOpening = row.put != null && openingToken == row.put.symbolToken,
            onTap = { row.put?.let(onTapLeg) },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun OptionLegCell(
    leg: OptionLegQuote?,
    isCallSide: Boolean,
    viewMode: OptionChainViewMode,
    oiRatio: Float,
    backgroundColor: Color,
    isOpening: Boolean,
    onTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tradingColors = LocalTradingColors.current
    val barColor = if (isCallSide) {
        tradingColors.bearish.copy(alpha = 0.18f) // Call writing = resistance
    } else {
        tradingColors.bullish.copy(alpha = 0.18f) // Put writing = support
    }

    Box(
        modifier = modifier
            .background(backgroundColor)
            .clickable(enabled = leg != null && !isOpening, onClick = onTap)
            .padding(horizontal = 6.dp, vertical = 7.dp)
    ) {
        if (leg == null) {
            Text(
                text = "—",
                style = MonoPriceSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center)
            )
            return@Box
        }

        // Proportional OI Heat Bar behind row
        if (oiRatio > 0.02f && viewMode != OptionChainViewMode.GREEKS) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(oiRatio)
                    .height(14.dp)
                    .align(if (isCallSide) Alignment.TopEnd else Alignment.TopStart)
                    .clip(RoundedCornerShape(3.dp))
                    .background(barColor)
            )
        }

        val chgPositive = leg.changePercent >= BigDecimal.ZERO
        val chgColor = if (chgPositive) tradingColors.bullish else tradingColors.bearish
        val ltpText = if (leg.ltp > BigDecimal.ZERO) {
            RiskMath.formatPrice(leg.ltp, leg.segment)
        } else {
            "—"
        }
        val oiText = OptionGreeksCalculator.formatOiCompact(leg.openInterest)
        val oiDeltaText = when {
            leg.oiChange > 0L -> "+${OptionGreeksCalculator.formatOiCompact(leg.oiChange)}"
            leg.oiChange < 0L -> OptionGreeksCalculator.formatOiCompact(leg.oiChange)
            else -> ""
        }
        val g = leg.greeks

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            when (viewMode) {
                OptionChainViewMode.OI_LTP -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isCallSide) {
                            Column(horizontalAlignment = Alignment.Start) {
                                Text(
                                    text = oiText,
                                    style = MonoPriceSmall,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                if (oiDeltaText.isNotEmpty()) {
                                    Text(
                                        text = oiDeltaText,
                                        style = MonoPriceSmall,
                                        fontSize = 9.sp,
                                        color = if (leg.oiChange >= 0L) tradingColors.bullish else tradingColors.bearish
                                    )
                                }
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = if (isOpening) "..." else ltpText,
                                    style = MonoPriceSmall,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "${if (chgPositive) "+" else ""}${leg.changePercent.toPlainString()}%",
                                    style = MonoPriceSmall,
                                    fontSize = 9.sp,
                                    color = chgColor
                                )
                            }
                        } else {
                            Column(horizontalAlignment = Alignment.Start) {
                                Text(
                                    text = if (isOpening) "..." else ltpText,
                                    style = MonoPriceSmall,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "${if (chgPositive) "+" else ""}${leg.changePercent.toPlainString()}%",
                                    style = MonoPriceSmall,
                                    fontSize = 9.sp,
                                    color = chgColor
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = oiText,
                                    style = MonoPriceSmall,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                if (oiDeltaText.isNotEmpty()) {
                                    Text(
                                        text = oiDeltaText,
                                        style = MonoPriceSmall,
                                        fontSize = 9.sp,
                                        color = if (leg.oiChange >= 0L) tradingColors.bullish else tradingColors.bearish
                                    )
                                }
                            }
                        }
                    }
                }

                OptionChainViewMode.GREEKS -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isOpening) "..." else ltpText,
                            style = MonoPriceSmall,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = g?.let { String.format(Locale.US, "IV %.1f%%", it.ivPercent) } ?: "IV -",
                            style = MonoPriceSmall,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = tradingColors.violet
                        )
                    }
                    if (g != null) {
                        Text(
                            text = String.format(
                                Locale.US,
                                "Δ%.2f  Θ%.1f  Γ%.4f  ν%.1f",
                                g.delta,
                                g.thetaPerDay,
                                g.gamma,
                                g.vega
                            ),
                            style = MonoPriceSmall,
                            fontSize = 9.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = if (isCallSide) TextAlign.Start else TextAlign.End,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                OptionChainViewMode.ALL -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isCallSide) {
                            Text(
                                text = "OI $oiText${if (oiDeltaText.isNotEmpty()) " ($oiDeltaText)" else ""}",
                                style = MonoPriceSmall,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = if (isOpening) "..." else ltpText,
                                style = MonoPriceSmall,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        } else {
                            Text(
                                text = if (isOpening) "..." else ltpText,
                                style = MonoPriceSmall,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "OI $oiText${if (oiDeltaText.isNotEmpty()) " ($oiDeltaText)" else ""}",
                                style = MonoPriceSmall,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.End,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    if (g != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = String.format(Locale.US, "IV %.1f%% • Δ%.2f", g.ivPercent, g.delta),
                                style = MonoPriceSmall,
                                fontSize = 9.sp,
                                color = tradingColors.violet,
                                maxLines = 1
                            )
                            Text(
                                text = String.format(Locale.US, "Θ%.1f ν%.1f", g.thetaPerDay, g.vega),
                                style = MonoPriceSmall,
                                fontSize = 9.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}
