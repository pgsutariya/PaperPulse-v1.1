package com.example.ui.screens.chart

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CandlestickChart
import androidx.compose.material.icons.rounded.CloseFullscreen
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.ShowChart
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.domain.analysis.AiRenkoConfig
import com.example.domain.analysis.BreakoutKind
import com.example.domain.analysis.ChartAnalysis
import com.example.domain.analysis.EmaCrossSetup
import com.example.domain.analysis.LiquiditySide
import com.example.domain.analysis.LiquidityStatus
import com.example.domain.analysis.MdrStage
import com.example.domain.analysis.RedBarBreakoutStatus
import com.example.domain.analysis.RenkoConviction
import com.example.domain.analysis.SetupState
import com.example.domain.analysis.StyleSetupReport
import com.example.domain.engine.MarketClock
import com.example.domain.signal.SignalDirection
import com.example.domain.signal.Timeframe
import com.example.ui.components.AiSecondOpinionCard
import com.example.ui.components.SegmentBadge
import com.example.ui.navigation.InstrumentDetailRoute
import com.example.ui.theme.LocalTradingColors
import com.example.ui.theme.MonoPriceSmall
import java.util.Locale
import kotlin.math.abs

private fun f2(v: Double?): String = v?.let {
    when {
        abs(it) >= 1000 -> String.format(Locale.US, "%,.2f", it)
        abs(it) >= 1 -> String.format(Locale.US, "%.2f", it)
        else -> String.format(Locale.US, "%.4f", it)
    }
} ?: "--"

private fun big(v: Double): String = when {
    abs(v) >= 1e7 -> String.format(Locale.US, "%.2fCr", v / 1e7)
    abs(v) >= 1e5 -> String.format(Locale.US, "%.2fL", v / 1e5)
    abs(v) >= 1e3 -> String.format(Locale.US, "%.1fK", v / 1e3)
    else -> String.format(Locale.US, "%.0f", v)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChartScreen(
    onBack: () -> Unit,
    onTrade: (InstrumentDetailRoute) -> Unit,
    onOpenFeedSettings: () -> Unit = {},
    viewModel: ChartViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = LocalTradingColors.current
    // TradingView-inspired high-contrast chart palette
    val palette = ChartPalette(
        bull = Color(0xFF26A69A),
        bear = Color(0xFFEF5350),
        amber = colors.amber,
        violet = colors.violet,
        cyan = Color(0xFF29B6F6),
        grid = Color(0xFF2A2E39),
        text = Color(0xFFD1D4DC),
        muted = Color(0xFF787B86),
        primary = Color(0xFF2962FF),
        canvasBg = Color(0xFF131722),
        axisDivider = Color(0xFF2A2E39)
    )

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(state.symbol, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        state.instrument?.let { SegmentBadge(it.segment) }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("chart_back_button")) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (state.chartEngine == ChartEngineMode.CANVAS) {
                        IconButton(
                            onClick = viewModel::toggleMeasureMode,
                            modifier = Modifier.testTag("chart_measure_toggle")
                        ) {
                            Icon(
                                Icons.Rounded.Straighten,
                                contentDescription = "Measure time & price range",
                                tint = if (state.measureMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                    IconButton(
                        onClick = { viewModel.zoom(zoomIn = true) },
                        modifier = Modifier.testTag("chart_zoom_in")
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = "Zoom in")
                    }
                    IconButton(
                        onClick = { viewModel.zoom(zoomIn = false) },
                        modifier = Modifier.testTag("chart_zoom_out")
                    ) {
                        Icon(Icons.Rounded.Remove, contentDescription = "Zoom out")
                    }
                    IconButton(
                        onClick = viewModel::toggleExpanded,
                        modifier = Modifier.testTag("chart_expand_toggle")
                    ) {
                        Icon(
                            if (state.isExpanded) Icons.Rounded.CloseFullscreen else Icons.Rounded.OpenInFull,
                            contentDescription = if (state.isExpanded) "Collapse chart" else "Expand chart fullscreen"
                        )
                    }
                    IconButton(onClick = viewModel::reloadHistory, modifier = Modifier.testTag("chart_reload_history")) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Reload history")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        if (state.isExpanded) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ChartEngineToggleRow(
                    selected = state.chartEngine,
                    onSelect = viewModel::setChartEngine
                )
                ChipRow {
                    Timeframe.entries.forEach { tf ->
                        FilterChip(
                            selected = state.timeframe == tf,
                            onClick = { viewModel.setTimeframe(tf) },
                            label = { Text(tf.label) }
                        )
                    }
                }
                if (state.chartEngine == ChartEngineMode.CANVAS && state.measureMode) {
                    Text(
                        "Measure Mode ON — tap two candles on chart for Δ Price, Δ %, Bars & Time Range",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                val analysis = state.analysis
                if (analysis == null) {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Text("Building candles…")
                    }
                } else {
                    val window = ChartWindow.of(analysis.candles.size, state.visibleBars, state.panBars)
                    val focus = analysis.setups.firstOrNull { r ->
                        val s = r.latest
                        s != null && s.state != SetupState.INVALIDATED && s.state != SetupState.EXPIRED
                    }?.latest
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = palette.canvasBg,
                        border = BorderStroke(1.dp, palette.axisDivider),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .testTag("panel_chart_container")
                    ) {
                        Box(Modifier.fillMaxSize()) {
                            if (state.chartEngine == ChartEngineMode.TRADINGVIEW) {
                                TradingViewChartPanel(
                                    symbol = state.symbol,
                                    analysis = analysis,
                                    overlays = state.overlays,
                                    lowerPane = state.lowerPane,
                                    focusSetup = focus,
                                    chartStyle = state.tvChartStyle,
                                    logScale = state.tvLogScale,
                                    magnetCrosshair = state.tvMagnetCrosshair,
                                    zoomCommandSeq = state.tvZoomCommandSeq,
                                    zoomDirection = state.tvZoomDirection,
                                    onSelectChartStyle = viewModel::setTvChartStyle,
                                    onToggleLogScale = viewModel::toggleTvLogScale,
                                    onToggleMagnetCrosshair = viewModel::toggleTvMagnetCrosshair,
                                    onFallbackToCanvas = { viewModel.setChartEngine(ChartEngineMode.CANVAS) },
                                    modifier = Modifier.fillMaxSize().padding(2.dp)
                                )
                            } else {
                                Column(Modifier.fillMaxSize().padding(2.dp)) {
                                    PriceChartCanvas(
                                        analysis = analysis,
                                        overlays = state.overlays,
                                        window = window,
                                        focusSetup = focus,
                                        palette = palette,
                                        measureMode = state.measureMode,
                                        measureStartIndex = state.measureStartIndex,
                                        measureEndIndex = state.measureEndIndex,
                                        selectedPatternId = state.selectedPattern?.id,
                                        onPatternTap = viewModel::selectPattern,
                                        onMeasureTap = viewModel::onMeasureTap,
                                        onPinchZoom = viewModel::zoomByScale,
                                        onPanBars = { delta -> viewModel.panByBars(delta, analysis.candles.size) },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .weight(0.76f)
                                            .testTag("chart_price_canvas")
                                    )
                                    LowerPaneCanvas(
                                        analysis = analysis,
                                        pane = state.lowerPane,
                                        window = window,
                                        showDivergences = state.overlays.divergences,
                                        palette = palette,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .weight(0.24f)
                                    )
                                }
                            }
                            if (state.overlays.chartPatterns && analysis.chartPatterns.isNotEmpty()) {
                                ChartPatternFloatingStrip(
                                    patterns = analysis.chartPatterns,
                                    selectedPatternId = state.selectedPattern?.id,
                                    onSelectPattern = viewModel::selectPattern,
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .padding(
                                            top = if (state.chartEngine == ChartEngineMode.TRADINGVIEW) 46.dp else 24.dp,
                                            start = 8.dp,
                                            end = 66.dp
                                        )
                                )
                                ChartPatternBreakoutTooltip(
                                    pattern = state.selectedPattern,
                                    aiState = state.selectedPatternAiState,
                                    onVerifyWithAi = viewModel::verifyPatternWithAi,
                                    onTradeBreakout = { pat ->
                                        viewModel.dismissPatternTooltip()
                                        onTrade(viewModel.buildPatternOrderRoute(pat))
                                    },
                                    onDismiss = viewModel::dismissPatternTooltip,
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(horizontal = 10.dp, vertical = 10.dp)
                                )
                            }
                        }
                    }
                }
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ChipRow {
                Timeframe.entries.forEach { tf ->
                    FilterChip(
                        selected = state.timeframe == tf,
                        onClick = { viewModel.setTimeframe(tf) },
                        label = { Text(tf.label) },
                        modifier = Modifier.testTag("chart_tf_${tf.name}")
                    )
                }
            }
            ChipRow {
                Text("HTF overlay", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.timeframe.higherTimeframes.forEach { htf ->
                    FilterChip(
                        selected = state.higherTimeframe == htf,
                        onClick = { viewModel.setHigherTimeframe(htf) },
                        label = { Text("${state.timeframe.label} → ${htf.label}") }
                    )
                }
                FilterChip(
                    selected = state.higherTimeframe == null,
                    onClick = { viewModel.setHigherTimeframe(null) },
                    label = { Text("Off") }
                )
            }
            ChipRow {
                OverlayKey.entries.forEach { key ->
                    FilterChip(
                        selected = isOverlayOn(state.overlays, key),
                        onClick = { viewModel.toggleOverlay(key) },
                        label = { Text(key.label) },
                        modifier = Modifier.testTag("chart_overlay_${key.name.lowercase(Locale.US)}")
                    )
                }
            }

            if (state.overlays.renkoSupertrend) {
                AiRenkoControlsStrip(
                    config = state.renkoConfig,
                    analysis = state.analysis,
                    onBrickMultChange = viewModel::setRenkoBrickMultiplier,
                    onAtrSensitivityChange = viewModel::setRenkoAtrSensitivity,
                    onAtrPeriodSelect = viewModel::setRenkoAtrPeriod,
                    onToggleAdaptive = viewModel::toggleRenkoAdaptiveMode,
                    onReset = viewModel::resetRenkoDefaults
                )
            }

            if (state.isLoadingHistory) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (state.historyNote.isNotBlank()) {
                Text(state.historyNote, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.chartEngine == ChartEngineMode.CANVAS && state.measureMode) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Time & Price Measure Tool active — tap two candles on the chart to inspect Δ Price, Δ %, Bars & Duration.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }

            ChartEngineToggleRow(
                selected = state.chartEngine,
                onSelect = viewModel::setChartEngine
            )

            val analysis = state.analysis
            if (analysis == null) {
                Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
                    Text("Building candles… (need at least 30 bars on ${state.timeframe.label})")
                }
                return@Column
            }

            val window = ChartWindow.of(analysis.candles.size, state.visibleBars, state.panBars)
            val focus = analysis.setups.firstOrNull { r ->
                val s = r.latest
                s != null && s.state != SetupState.INVALIDATED && s.state != SetupState.EXPIRED
            }?.latest

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = palette.canvasBg,
                border = BorderStroke(1.dp, palette.axisDivider),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("panel_chart_container")
            ) {
                Box(Modifier.fillMaxWidth()) {
                    if (state.chartEngine == ChartEngineMode.TRADINGVIEW) {
                        TradingViewChartPanel(
                            symbol = state.symbol,
                            analysis = analysis,
                            overlays = state.overlays,
                            lowerPane = state.lowerPane,
                            focusSetup = focus,
                            chartStyle = state.tvChartStyle,
                            logScale = state.tvLogScale,
                            magnetCrosshair = state.tvMagnetCrosshair,
                            zoomCommandSeq = state.tvZoomCommandSeq,
                            zoomDirection = state.tvZoomDirection,
                            onSelectChartStyle = viewModel::setTvChartStyle,
                            onToggleLogScale = viewModel::toggleTvLogScale,
                            onToggleMagnetCrosshair = viewModel::toggleTvMagnetCrosshair,
                            onFallbackToCanvas = { viewModel.setChartEngine(ChartEngineMode.CANVAS) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(520.dp)
                                .padding(2.dp)
                        )
                    } else {
                        Column(Modifier.padding(2.dp)) {
                            PriceChartCanvas(
                                analysis = analysis,
                                overlays = state.overlays,
                                window = window,
                                focusSetup = focus,
                                palette = palette,
                                measureMode = state.measureMode,
                                measureStartIndex = state.measureStartIndex,
                                measureEndIndex = state.measureEndIndex,
                                selectedPatternId = state.selectedPattern?.id,
                                onPatternTap = viewModel::selectPattern,
                                onMeasureTap = viewModel::onMeasureTap,
                                onPinchZoom = viewModel::zoomByScale,
                                onPanBars = { delta -> viewModel.panByBars(delta, analysis.candles.size) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(380.dp)
                                    .testTag("chart_price_canvas")
                            )
                            LowerPaneCanvas(
                                analysis = analysis,
                                pane = state.lowerPane,
                                window = window,
                                showDivergences = state.overlays.divergences,
                                palette = palette,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(120.dp)
                            )
                        }
                    }

                    if (state.overlays.chartPatterns && analysis.chartPatterns.isNotEmpty()) {
                        ChartPatternFloatingStrip(
                            patterns = analysis.chartPatterns,
                            selectedPatternId = state.selectedPattern?.id,
                            onSelectPattern = viewModel::selectPattern,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(
                                    top = if (state.chartEngine == ChartEngineMode.TRADINGVIEW) 46.dp else 24.dp,
                                    start = 8.dp,
                                    end = 66.dp
                                )
                        )
                        ChartPatternBreakoutTooltip(
                            pattern = state.selectedPattern,
                            aiState = state.selectedPatternAiState,
                            onVerifyWithAi = viewModel::verifyPatternWithAi,
                            onTradeBreakout = { pat ->
                                viewModel.dismissPatternTooltip()
                                onTrade(viewModel.buildPatternOrderRoute(pat))
                            },
                            onDismiss = viewModel::dismissPatternTooltip,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(horizontal = 10.dp, vertical = 10.dp)
                        )
                    }
                }
            }

            if (state.chartEngine == ChartEngineMode.CANVAS) {
                val maxPan = (analysis.candles.size - state.visibleBars).coerceAtLeast(0)
                if (maxPan > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Scroll back", style = MaterialTheme.typography.labelSmall)
                        Slider(
                            value = state.panBars.toFloat().coerceIn(0f, maxPan.toFloat()),
                            onValueChange = { viewModel.setPan(it.toInt()) },
                            valueRange = 0f..maxPan.toFloat(),
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                        )
                    }
                }
            }

            ChipRow {
                LowerPane.entries.forEach { pane ->
                    FilterChip(
                        selected = state.lowerPane == pane,
                        onClick = { viewModel.setLowerPane(pane) },
                        label = { Text(pane.label) }
                    )
                }
            }

            LegendRow(palette)

            AiSecondOpinionCard(
                title = "AI Chart Structure Read",
                subtitle = "Interprets ${state.timeframe.label} SMC trend, zones, volume delta & recent candles",
                ctaLabel = "Analyze Chart with AI",
                aiState = state.aiChartState,
                onAnalyze = viewModel::analyzeChartWithAi,
                ctaTestTag = "chart_ai_analyze_button",
                connectionStatus = state.aiConnectionStatus,
                onSwitchModel = viewModel::switchAiModel,
                onOpenAiSettings = onOpenFeedSettings
            )

            AiChartPatternsPanel(
                analysis = analysis,
                selectedPatternId = state.selectedPattern?.id,
                onSelectPattern = viewModel::selectPattern,
                onTradeBreakout = { pat -> onTrade(viewModel.buildPatternOrderRoute(pat)) }
            )

            SetupsPanel(
                reports = analysis.setups,
                riskPercent = state.riskPercent,
                onRiskChange = viewModel::setRiskPercent,
                quantityFor = viewModel::suggestedQuantity,
                onTrade = { setup, ratio -> viewModel.buildOrderRoute(setup, ratio)?.let(onTrade) }
            )
            AiRenkoSupertrendPanel(analysis)
            RedBarIndicatorPanel(analysis)
            StructurePanel(analysis)
            LiquidityPanel(analysis)
            SessionModelPanel(state)
            PivotPanel(analysis)
            VolumeFlowPanel(analysis)
            DivergencePanel(analysis)
            QuantPanel(analysis)
            Text(
                "Paper-trading analytics only — not investment advice. Signals are computed on closed candles; " +
                    "on the simulator, history is synthetic until real exchange history loads.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 24.dp)
            )
        }
    }
}

private fun isOverlayOn(o: ChartOverlays, key: OverlayKey): Boolean = when (key) {
    OverlayKey.CHART_PATTERNS -> o.chartPatterns
    OverlayKey.RENKO_SUPERTREND -> o.renkoSupertrend
    OverlayKey.RED_BAR -> o.redBar
    OverlayKey.FIRST_RED_RANGE -> o.firstRedRange
    OverlayKey.EMA_9_21 -> o.ema921
    OverlayKey.EMA_50_200 -> o.ema50200
    OverlayKey.VWAP -> o.vwap
    OverlayKey.VWAP_BANDS -> o.vwapBands
    OverlayKey.HTF -> o.htfShadow
    OverlayKey.PIVOTS -> o.pivots
    OverlayKey.SMC -> o.smcZones
    OverlayKey.LIQUIDITY -> o.liquidity
    OverlayKey.STRUCTURE -> o.structure
    OverlayKey.PD -> o.premiumDiscount
    OverlayKey.VP -> o.volumeProfile
    OverlayKey.DIVERGENCE -> o.divergences
    OverlayKey.SETUP -> o.setupLevels
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChartEngineToggleRow(
    selected: ChartEngineMode,
    onSelect: (ChartEngineMode) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("panel_chart_engine_toggle"),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(
                text = "Chart Engine",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = if (selected == ChartEngineMode.TRADINGVIEW) {
                    "TradingView Lightweight Charts • Pinch, Log, Magnet & Heikin Ashi"
                } else {
                    "Native Canvas • Measure Tool, HTF Shadow & Volume Profile"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        SingleChoiceSegmentedButtonRow {
            ChartEngineMode.entries.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = selected == mode,
                    onClick = { onSelect(mode) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = ChartEngineMode.entries.size
                    ),
                    icon = {
                        Icon(
                            imageVector = if (mode == ChartEngineMode.TRADINGVIEW) {
                                Icons.Rounded.CandlestickChart
                            } else {
                                Icons.Rounded.ShowChart
                            },
                            contentDescription = null
                        )
                    },
                    modifier = Modifier.testTag(
                        if (mode == ChartEngineMode.TRADINGVIEW) "chart_engine_tradingview" else "chart_engine_canvas"
                    ),
                    label = {
                        Text(
                            text = mode.label,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        content()
    }
}

@Composable
private fun LegendRow(palette: ChartPalette) {
    ChipRow {
        LegendDot("AI Patterns", Color(0xFF00E5A3))
        LegendDot("AI Renko ST", Color(0xFF00E676))
        LegendDot("1st Red / 12:45", palette.bear)
        LegendDot("EMA9", palette.cyan)
        LegendDot("EMA21", palette.amber)
        LegendDot("EMA50", palette.violet)
        LegendDot("EMA200", palette.text)
        LegendDot("VWAP ±1/2/3σ", palette.primary)
        LegendDot("FVG", palette.violet)
        LegendDot("★ Institutional", palette.amber)
    }
}

@Composable
private fun LegendDot(label: String, color: Color) {
    Text("● $label", color = color, style = MaterialTheme.typography.labelSmall)
}

@Composable
private fun Panel(title: String, content: @Composable ColumnScope.() -> Unit) {
    val colors = LocalTradingColors.current
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, colors.border),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun KeyValue(key: String, value: String, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(key, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MonoPriceSmall, color = valueColor, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SetupsPanel(
    reports: List<StyleSetupReport>,
    riskPercent: Double,
    onRiskChange: (Double) -> Unit,
    quantityFor: (EmaCrossSetup) -> Int,
    onTrade: (EmaCrossSetup, Double) -> Unit
) {
    val colors = LocalTradingColors.current
    Panel("EMA CROSSOVER → RETEST → BREAK SETUPS") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Risk per trade ${String.format(Locale.US, "%.1f", riskPercent)}%", style = MaterialTheme.typography.bodySmall)
            Slider(
                value = riskPercent.toFloat(),
                onValueChange = { onRiskChange(it.toDouble()) },
                valueRange = 0.25f..3f,
                modifier = Modifier.weight(1f).padding(start = 8.dp)
            )
        }
        reports.forEach { report ->
            val s = report.latest
            Text(
                "${report.style.label} • ${report.style.pairLabel}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            if (s == null) {
                Text("No ${report.style.pairLabel} crossover in loaded history.", style = MaterialTheme.typography.bodySmall)
            } else {
                val isLong = s.direction == SignalDirection.LONG
                val dirColor = if (isLong) colors.bullish else colors.bearish
                KeyValue("Direction", if (isLong) "BUY (bullish cross)" else "SELL (bearish cross)", dirColor)
                KeyValue("State", s.state.label, if (s.state == SetupState.TRIGGERED) dirColor else MaterialTheme.colorScheme.onSurface)
                KeyValue("Crossover candle H / L", "${f2(s.crossHigh)} / ${f2(s.crossLow)}")
                KeyValue("Trigger (close beyond)", f2(s.triggerLevel), colors.amber)
                s.retestExtreme?.let { KeyValue(if (isLong) "Retest swing low" else "Retest swing high", f2(it)) }
                s.entry?.let { KeyValue("Entry", f2(it)) }
                s.stopLoss?.let { KeyValue("Stop loss", f2(it), colors.bearish) }
                if (s.state == SetupState.TRIGGERED) {
                    KeyValue("Max R reached / outcome", "${String.format(Locale.US, "%.2f", s.maxRReached)}R • ${s.outcome.name}")
                    KeyValue("Suggested qty @ ${String.format(Locale.US, "%.1f", riskPercent)}% risk", quantityFor(s).toString())
                }
                KeyValue("Volume > MA20 on break", if (s.volumeConfirmed) "Yes" else "No")
                s.nearestOpposingLevel?.let { KeyValue(if (isLong) "Nearest resistance" else "Nearest support", f2(it)) }
                if (s.targets.isNotEmpty() && (s.state == SetupState.TRIGGERED || s.state == SetupState.AWAITING_BREAK)) {
                    Text("Risk : Reward ladder (tap to prefill order)", style = MaterialTheme.typography.labelSmall)
                    ChipRow {
                        s.targets.forEach { t ->
                            AssistChip(
                                onClick = { onTrade(s, t.ratio) },
                                label = { Text("${t.label}  ${f2(t.price)}") }
                            )
                        }
                    }
                } else if (s.state == SetupState.AWAITING_BREAK || s.state == SetupState.AWAITING_RETEST) {
                    OutlinedButton(onClick = { onTrade(s, 2.0) }) {
                        Text("Prefill stop-entry order at trigger (1:2)")
                    }
                }
                s.reasons.forEach { r ->
                    Text("• $r", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            report.backtest?.let { bt ->
                Text(
                    "Backtest on loaded history @1:${String.format(Locale.US, "%.0f", bt.targetRatio)}: ${bt.trades} trades • " +
                        "win ${String.format(Locale.US, "%.0f", bt.winRate * 100)}% • expectancy ${String.format(Locale.US, "%.2f", bt.expectancyR)}R • " +
                        "PF ${if (bt.profitFactor.isInfinite()) "∞" else String.format(Locale.US, "%.2f", bt.profitFactor)} • " +
                        "max DD ${String.format(Locale.US, "%.1f", bt.maxDrawdownR)}R • Kelly ${String.format(Locale.US, "%.0f", bt.kellyFraction * 100)}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun AiRenkoControlsStrip(
    config: AiRenkoConfig,
    analysis: ChartAnalysis?,
    onBrickMultChange: (Double) -> Unit,
    onAtrSensitivityChange: (Double) -> Unit,
    onAtrPeriodSelect: (Int) -> Unit,
    onToggleAdaptive: () -> Unit,
    onReset: () -> Unit
) {
    val colors = LocalTradingColors.current
    val st = analysis?.renkoSupertrend
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, colors.border),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("renko_controls_card")
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "AI RENKO ST CONTROLS",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (st != null && st.currentTrail != null) {
                        val bull = st.currentBullish
                        val sigColor = if (bull) colors.bullish else colors.bearish
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = sigColor.copy(alpha = 0.16f),
                            border = BorderStroke(1.dp, sigColor.copy(alpha = 0.45f)),
                            modifier = Modifier.testTag("renko_live_signal_badge")
                        ) {
                            Text(
                                text = "${if (bull) "▲ BUY" else "▼ SELL"} • Trail ${f2(st.currentTrail)}",
                                style = MonoPriceSmall,
                                fontWeight = FontWeight.Bold,
                                color = sigColor,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                TextButton(
                    onClick = onReset,
                    modifier = Modifier.testTag("renko_reset_button")
                ) {
                    Text("Reset", style = MaterialTheme.typography.labelSmall)
                }
            }

            // Brick Size Multiplier Slider
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val brickPts = st?.currentBrickSize?.takeIf { it > 0.0 }?.let { " (${f2(it)})" } ?: ""
                Text(
                    text = "Brick ${String.format(Locale.US, "%.2f", config.brickSizeMultiplier)}x$brickPts",
                    style = MonoPriceSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Slider(
                    value = config.brickSizeMultiplier.toFloat(),
                    onValueChange = { onBrickMultChange(it.toDouble()) },
                    valueRange = 0.30f..2.50f,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("renko_brick_size_slider")
                )
            }

            // ATR Sensitivity Slider
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val effFactor = st?.currentFactor?.let { " (Eff ${String.format(Locale.US, "%.2f", it)}x)" } ?: ""
                Text(
                    text = "ATR Sens ${String.format(Locale.US, "%.1f", config.atrSensitivity)}x$effFactor",
                    style = MonoPriceSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Slider(
                    value = config.atrSensitivity.toFloat(),
                    onValueChange = { onAtrSensitivityChange(it.toDouble()) },
                    valueRange = 1.2f..4.5f,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("renko_atr_sensitivity_slider")
                )
            }

            // ATR Period + Adaptive Volatility Toggle Chips
            ChipRow {
                Text("ATR", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                listOf(7, 10, 14, 21).forEach { period ->
                    FilterChip(
                        selected = config.atrPeriod == period,
                        onClick = { onAtrPeriodSelect(period) },
                        label = { Text("$period") },
                        modifier = Modifier.testTag("renko_atr_period_$period")
                    )
                }
                FilterChip(
                    selected = config.adaptiveMode,
                    onClick = onToggleAdaptive,
                    label = { Text(if (config.adaptiveMode) "Adaptive AI: ON" else "Adaptive AI: OFF") },
                    modifier = Modifier.testTag("renko_adaptive_toggle")
                )
            }
        }
    }
}

@Composable
private fun AiRenkoSupertrendPanel(a: ChartAnalysis) {
    val colors = LocalTradingColors.current
    val st = a.renkoSupertrend
    Panel("AI RENKO SUPERTREND • INSTITUTIONAL ENGINE") {
        val trendColor = if (st.currentBullish) colors.bullish else colors.bearish
        KeyValue("Renko Trend", if (st.currentBullish) "BULLISH ↑" else "BEARISH ↓", trendColor)
        KeyValue("Adaptive Trail Stop", f2(st.currentTrail), trendColor)
        KeyValue("Adaptive Brick Size", "${f2(st.currentBrickSize)} (${st.totalBricksFormed} bricks)")
        KeyValue(
            "Volatility Regime (ATR7/21)",
            "${st.volatilityRegime.label} • ${String.format(Locale.US, "%.2f", st.volatilityRatio)}x"
        )
        KeyValue("Adaptive Supertrend Factor", String.format(Locale.US, "%.2fx ATR", st.currentFactor))
        KeyValue(
            "ADX(14) Chop Filter",
            st.currentAdx?.let {
                "${String.format(Locale.US, "%.1f", it)} • ${if (st.isChoppyMarket) "CHOPPY (Caution)" else "TRENDING"}"
            } ?: "--",
            if (st.isChoppyMarket) colors.amber else colors.bullish
        )
        KeyValue(
            "EMA 21/50 Confirmation",
            if (st.emaAligned) "ALIGNED" else "Counter-trend / Neutral",
            if (st.emaAligned) colors.bullish else MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (st.signals.isNotEmpty()) {
            Text("Recent Adaptive Renko Flips", style = MaterialTheme.typography.labelSmall)
            st.signals.takeLast(3).reversed().forEach { sig ->
                val barsAgo = (a.candles.lastIndex - sig.index).coerceAtLeast(0)
                val sigCol = when {
                    sig.conviction == RenkoConviction.CHOP_FILTERED -> colors.amber
                    sig.isBullish -> colors.bullish
                    else -> colors.bearish
                }
                KeyValue(
                    "${if (sig.isBullish) "BUY" else "SELL"} (${barsAgo}b ago)",
                    "${f2(sig.price)} • ${sig.conviction.label}",
                    sigCol
                )
            }
        }
    }
}

@Composable
private fun RedBarIndicatorPanel(a: ChartAnalysis) {
    val colors = LocalTradingColors.current
    val rb = a.redBar
    Panel("RED BAR INDICATOR • FIRST RED & 12:45 BREAKOUT") {
        val fr = rb.firstRedRange
        if (fr == null) {
            Text("No red reference candle detected in current session yet.", style = MaterialTheme.typography.bodySmall)
        } else {
            val statusColor = when (fr.status) {
                RedBarBreakoutStatus.BREAKOUT_UP -> colors.bullish
                RedBarBreakoutStatus.BREAKDOWN_DOWN -> colors.bearish
                RedBarBreakoutStatus.INSIDE_RANGE -> colors.amber
            }
            KeyValue("First Red Candle (${MarketClock.formatIstShortTime(fr.timestampMillis)})", "${f2(fr.low)} – ${f2(fr.high)}")
            KeyValue("First Red 50% Retracement", f2(fr.midpoint), MaterialTheme.colorScheme.primary)
            KeyValue("First Red Status", fr.status.label, statusColor)
        }

        val md = rb.midday1245Range
        if (md != null) {
            val mdColor = when (md.status) {
                RedBarBreakoutStatus.BREAKOUT_UP -> colors.bullish
                RedBarBreakoutStatus.BREAKDOWN_DOWN -> colors.bearish
                RedBarBreakoutStatus.INSIDE_RANGE -> colors.amber
            }
            KeyValue("12:45 IST Range", "${f2(md.low)} – ${f2(md.high)}", colors.amber)
            KeyValue("12:45 Breakout Status", md.status.label, mdColor)
        } else {
            KeyValue("12:45 IST Range", "Awaiting 12:45 IST bar", MaterialTheme.colorScheme.onSurfaceVariant)
        }

        KeyValue(
            "Price vs Volume Bar",
            "${if (rb.latestPriceCandleRed) "Red Price" else "Green Price"} • ${if (rb.latestVolumeBarRed) "Red Vol" else "Green Vol"}",
            if (rb.hasLatestVolumePriceMismatch) colors.amber else MaterialTheme.colorScheme.onSurface
        )
        if (rb.hasLatestVolumePriceMismatch) {
            Text(
                "Note: Price candle compares Close vs Open; Volume bar compares Close vs Prev Close.",
                style = MaterialTheme.typography.labelSmall,
                color = colors.amber
            )
        }
        rb.latestInsideBar?.let { ib ->
            val barsAgo = (a.candles.lastIndex - ib.index).coerceAtLeast(0)
            KeyValue(
                "Latest Inside Bar",
                "${if (ib.isRed) "Red IB" else "Green IB"} (${f2(ib.low)}–${f2(ib.high)}) • ${barsAgo}b ago",
                if (ib.isRed) colors.bearish else colors.bullish
            )
        }
        if (rb.reversalZones.isNotEmpty()) {
            val latestZone = rb.reversalZones.last()
            KeyValue(
                "Bearish Reversal Zones (${rb.reversalZones.size})",
                "${latestZone.label} ${f2(latestZone.bottom)}–${f2(latestZone.top)}",
                colors.bearish
            )
        }
    }
}

@Composable
private fun StructurePanel(a: ChartAnalysis) {
    val colors = LocalTradingColors.current
    val smc = a.smc
    Panel("SMC / ICT • STRUCTURE, ZONES & POI") {
        KeyValue("Market structure", smc.trend.name)
        smc.structureBreaks.lastOrNull()?.let { KeyValue("Last break", "${it.label} @ ${f2(it.level)}", if (it.bullish) colors.bullish else colors.bearish) }
        smc.dealingRange?.let { r ->
            KeyValue("Dealing range", "${f2(r.low)} – ${f2(r.high)}")
            KeyValue("Price location", r.zoneOf(a.lastClose))
            KeyValue("Equilibrium (50%)", f2(r.equilibrium))
            KeyValue("OTE long (62–79%)", "${f2(r.oteLongBottom)} – ${f2(r.oteLongTop)}", colors.bullish)
            KeyValue("OTE short (62–79%)", "${f2(r.oteShortBottom)} – ${f2(r.oteShortTop)}", colors.bearish)
        }
        KeyValue("Active demand zones / OBs", smc.activeDemand.size.toString(), colors.bullish)
        KeyValue("Active supply zones / OBs", smc.activeSupply.size.toString(), colors.bearish)
        KeyValue("Institutional zones ★", smc.zones.count { it.institutional && it.isActive }.toString(), colors.amber)
        Text("Points of Interest (unmitigated, nearest first)", style = MaterialTheme.typography.labelSmall)
        if (smc.pointsOfInterest.isEmpty()) {
            Text("None in discount (below price) / premium (above price) right now.", style = MaterialTheme.typography.bodySmall)
        }
        smc.pointsOfInterest.forEach { z ->
            KeyValue(
                if (z.institutional) "${z.kind.label} ★" else z.kind.label,
                "${f2(z.bottom)} – ${f2(z.top)}",
                if (z.isBullish) colors.bullish else colors.bearish
            )
        }
        val events = (smc.traps.takeLast(3).map { "${it.kind.label} @ ${f2(it.level)}" } +
            smc.breakouts.takeLast(4).map { e ->
                val tag = when (e.kind) {
                    BreakoutKind.BREAKOUT_UP, BreakoutKind.BREAKOUT_DOWN -> "Breakout"
                    BreakoutKind.REVERSAL_UP, BreakoutKind.REVERSAL_DOWN -> "Reversal"
                }
                "$tag: ${e.kind.label} @ ${f2(e.level)}"
            })
        if (events.isNotEmpty()) {
            Text("Traps • Breakouts • Reversals", style = MaterialTheme.typography.labelSmall)
            events.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun LiquidityPanel(a: ChartAnalysis) {
    val colors = LocalTradingColors.current
    Panel("LIQUIDITY (BSL / SSL)") {
        val levels = a.smc.liquidity.sortedByDescending { it.price }
        if (levels.isEmpty()) Text("No liquidity pools detected yet.", style = MaterialTheme.typography.bodySmall)
        levels.take(12).forEach { lv ->
            val col = when (lv.status) {
                LiquidityStatus.PENDING -> if (lv.side == LiquiditySide.BUY_SIDE) colors.bearish else colors.bullish
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            KeyValue("${if (lv.side == LiquiditySide.BUY_SIDE) "BSL" else "SSL"} • ${lv.name}", "${f2(lv.price)} • ${lv.status.label}", col)
        }
        Text(
            "Pending = resting stops not yet taken (draw on liquidity). Swept = wick through & close back (stop-hunt). Broken = accepted beyond.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SessionModelPanel(state: ChartUiState) {
    val colors = LocalTradingColors.current
    Panel("AMD (HTF session bias) • MDR (LTF kill-zone model)") {
        val kz = state.analysis?.killZone
        KeyValue("Kill zone now", kz?.labelIst ?: "Outside kill zones", if (kz != null) colors.amber else MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.amdFrames.isEmpty()) Text("Waiting for 1H/4H/1D candles…", style = MaterialTheme.typography.bodySmall)
        state.amdFrames.forEach { f ->
            KeyValue("AMD ${f.timeframe.label}", f.amd.phase.label)
            Text(
                "  Acc. range ${f2(f.amd.accumulationLow)} – ${f2(f.amd.accumulationHigh)} • bias ${f.amd.bias.name} • ${f.amd.note}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        state.mdrFrames.forEach { f ->
            val m = f.mdr
            val col = when (m.stage) {
                MdrStage.REBALANCED -> if (m.bullish == true) colors.bullish else colors.bearish
                MdrStage.DISPLACED -> colors.amber
                else -> MaterialTheme.colorScheme.onSurface
            }
            KeyValue("MDR ${f.timeframe.label}", m.stage.label, col)
            if (m.stage != MdrStage.WAITING) {
                Text(
                    buildString {
                        append("  ${if (m.bullish == true) "Bullish" else "Bearish"} • swept ${f2(m.sweptLevel)}")
                        m.rebalanceZone?.let { append(" • FVG ${f2(it.bottom)}–${f2(it.top)}") }
                        if (m.inKillZone) append(" • in ${m.killZoneName}")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun PivotPanel(a: ChartAnalysis) {
    val colors = LocalTradingColors.current
    val pv = a.pivots
    Panel("PIVOT LEVELS${pv?.let { " (${it.period.label})" } ?: ""}") {
        if (pv == null) {
            Text("Need one completed prior period.", style = MaterialTheme.typography.bodySmall)
        } else {
            KeyValue("Prior H / L / C", "${f2(pv.high)} / ${f2(pv.low)} / ${f2(pv.close)}")
            KeyValue("R3 / R2 / R1", "${f2(pv.r3)} / ${f2(pv.r2)} / ${f2(pv.r1)}", colors.bearish)
            KeyValue("Pivot P", f2(pv.pivot), MaterialTheme.colorScheme.primary)
            KeyValue("S1 / S2 / S3", "${f2(pv.s1)} / ${f2(pv.s2)} / ${f2(pv.s3)}", colors.bullish)
            KeyValue("CPR (BC – TC)", "${f2(pv.cprBottom)} – ${f2(pv.cprTop)} (${String.format(Locale.US, "%.2f", pv.cprWidthPercent)}%)")
            KeyValue("Camarilla S4/S3/R3/R4", "${f2(pv.camS4)} / ${f2(pv.camS3)} / ${f2(pv.camR3)} / ${f2(pv.camR4)}")
            val above = a.lastClose > pv.pivot
            KeyValue("Sentiment", if (above) "Above pivot → bullish" else "Below pivot → bearish", if (above) colors.bullish else colors.bearish)
        }
    }
}

@Composable
private fun VolumeFlowPanel(a: ChartAnalysis) {
    val colors = LocalTradingColors.current
    val of = a.orderFlow
    Panel("VOLUME • ORDER FLOW • DELTA") {
        val lastVol = a.candles.lastOrNull()?.volume ?: 0.0
        KeyValue("Volume / MA20", "${big(lastVol)} / ${big(a.volumeMa20.lastOrNull() ?: 0.0)}", if (a.volumeAboveMa) colors.bullish else MaterialTheme.colorScheme.onSurface)
        KeyValue("Volume above 20 MA", if (a.volumeAboveMa) "YES" else "no")
        val obvNow = a.obv.lastOrNull() ?: 0.0
        val obvPrev = a.obv.getOrNull(a.obv.size - 11) ?: obvNow
        KeyValue("OBV (10-bar trend)", "${big(obvNow)} ${if (obvNow >= obvPrev) "↑" else "↓"}", if (obvNow >= obvPrev) colors.bullish else colors.bearish)
        val efi = a.elderForce13.lastOrNull()
        KeyValue("Elder Force (13)", efi?.let { big(it) } ?: "--", if ((efi ?: 0.0) >= 0) colors.bullish else colors.bearish)
        KeyValue("Delta (last bar)", big(of.lastDelta), if (of.lastDelta >= 0) colors.bullish else colors.bearish)
        KeyValue("Cumulative delta", big(of.sessionCumDelta), if (of.sessionCumDelta >= 0) colors.bullish else colors.bearish)
        KeyValue(
            "Buy / Sell vol (20 bars)",
            "${big(of.buyVolume20)} / ${big(of.sellVolume20)} (${if (of.imbalanceRatio.isInfinite()) "∞" else String.format(Locale.US, "%.2f", of.imbalanceRatio)}x)"
        )
        KeyValue("Absorption (2× vol, small range)", if (of.absorption) "DETECTED" else "no", if (of.absorption) colors.amber else MaterialTheme.colorScheme.onSurface)
        KeyValue("Delta source", if (of.usesTrueDelta) "True aggressor side" else "Estimated (close location)")
        a.volumeProfile?.let { vp ->
            KeyValue("Volume profile POC", f2(vp.poc), colors.amber)
            KeyValue("Value area (70%)", "${f2(vp.valueAreaLow)} – ${f2(vp.valueAreaHigh)}")
        }
        KeyValue("ATR 7 / ATR 14", "${f2(a.atr7.lastOrNull())} / ${f2(a.atr14.lastOrNull())}")
        a.vwap.lastOrNull()?.let { v ->
            KeyValue("VWAP ±1σ / ±2σ", "${f2(v.vwap)} • ${f2(v.lower(1.0))}–${f2(v.upper(1.0))} • ${f2(v.lower(2.0))}–${f2(v.upper(2.0))}")
        }
    }
}

@Composable
private fun DivergencePanel(a: ChartAnalysis) {
    val colors = LocalTradingColors.current
    Panel("DIVERGENCES (RSI • VOLUME • DELTA)") {
        if (a.divergences.isEmpty()) Text("No divergences on recent swings.", style = MaterialTheme.typography.bodySmall)
        a.divergences.sortedByDescending { it.toIndex }.take(8).forEach { d ->
            val barsAgo = a.candles.lastIndex - d.toIndex
            KeyValue(d.kind.label, "${f2(d.toPrice)} • $barsAgo bars ago", if (d.kind.bullish) colors.bullish else colors.bearish)
        }
    }
}

@Composable
private fun QuantPanel(a: ChartAnalysis) {
    val q = a.quant
    Panel("QUANT / MATHEMATICAL MODELS") {
        KeyValue("Regime", q.regime)
        KeyValue("Z-score (20)", q.zScore?.let { String.format(Locale.US, "%.2f", it) } ?: "--")
        KeyValue("Hurst exponent", q.hurst?.let { String.format(Locale.US, "%.2f", it) } ?: "--")
        q.regression?.let { r ->
            KeyValue("Lin. regression slope (50)", String.format(Locale.US, "%.3f%%/bar", r.slopePercentPerBar(50)))
            KeyValue("Regression R²", String.format(Locale.US, "%.2f", r.r2))
        }
        KeyValue("ROC (10)", q.rocPercent?.let { String.format(Locale.US, "%.2f%%", it) } ?: "--")
        KeyValue("Realised vol (20, per bar)", q.realisedVolPercent?.let { String.format(Locale.US, "%.2f%%", it) } ?: "--")
        KeyValue("BB-width percentile (squeeze < 20)", q.squeezePercentile?.let { String.format(Locale.US, "%.0f", it) } ?: "--")
        Text(
            "Models: z-score mean reversion (|z| > 2 re-entry), Donchian + Bollinger-squeeze breakout, Hurst regime filter, " +
                "linear-regression trend, Kelly fraction & fixed-fractional sizing from setup backtests.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
