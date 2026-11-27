package com.example.ui.screens.chart

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color as AndroidColor
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FitScreen
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.domain.analysis.ChartAnalysis
import com.example.domain.analysis.EmaCrossSetup
import com.example.ui.theme.LocalTradingColors
import kotlinx.coroutines.delay

/**
 * Custom WebView subclass that claims horizontal/pinch touch events while inside a
 * vertically scrollable Compose container so TradingView panning and pinch-to-zoom stay fluid.
 */
private class ChartTouchWebView(context: Context) : WebView(context) {
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_POINTER_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return super.onTouchEvent(event)
    }
}

private class PaperPulseChartBridge(
    private val mainHandler: Handler,
    private val onReady: (String) -> Unit,
    private val onError: (String) -> Unit
) {
    @JavascriptInterface
    fun onChartReady(version: String) {
        mainHandler.post { onReady(version) }
    }

    @JavascriptInterface
    fun onChartError(message: String) {
        mainHandler.post { onError(message) }
    }
}

@Composable
fun TradingViewChartPanel(
    symbol: String,
    analysis: ChartAnalysis,
    overlays: ChartOverlays,
    lowerPane: LowerPane,
    focusSetup: EmaCrossSetup?,
    chartStyle: TvChartStyle,
    logScale: Boolean,
    magnetCrosshair: Boolean,
    zoomCommandSeq: Int,
    zoomDirection: Int,
    onSelectChartStyle: (TvChartStyle) -> Unit,
    onToggleLogScale: () -> Unit,
    onToggleMagnetCrosshair: () -> Unit,
    onFallbackToCanvas: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val isDarkTheme = LocalTradingColors.current.isDark
    // The scrims and badges sit directly on top of the WebView, so they follow the
    // chart page's own background rather than the Material surface colour.
    val chartSurface = if (isDarkTheme) Color(0xFF131722) else Color(0xFFFFFFFF)
    val chartOnSurface = if (isDarkTheme) Color(0xFFD1D4DC) else Color(0xFF334155)

    var isChartReady by remember { mutableStateOf(false) }
    var chartVersion by remember { mutableStateOf("") }
    var loadError by remember { mutableStateOf<String?>(null) }
    var reloadNonce by remember { mutableIntStateOf(0) }
    var fitNonce by remember { mutableIntStateOf(0) }

    val baseHtml = remember(context) {
        context.assets.open("tradingview_lightweight_chart.html").bufferedReader().use { it.readText() }
    }

    val payloadBase64 = remember(
        symbol,
        analysis,
        overlays,
        lowerPane,
        focusSetup,
        chartStyle,
        logScale,
        magnetCrosshair,
        isDarkTheme
    ) {
        val payload = TradingViewChartPayloadBuilder.build(
            symbol = symbol,
            analysis = analysis,
            overlays = overlays,
            lowerPane = lowerPane,
            focusSetup = focusSetup,
            chartStyle = chartStyle,
            logScale = logScale,
            magnetCrosshair = magnetCrosshair,
            isDarkTheme = isDarkTheme
        )
        val json = TradingViewChartPayloadBuilder.toJson(payload)
        Base64.encodeToString(json.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }

    val webView = remember(context) {
        ChartTouchWebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            // Repainted below whenever the scheme changes; this is only the initial value.
            setBackgroundColor(AndroidColor.parseColor("#131722"))
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            settings.apply {
                @SuppressLint("SetJavaScriptEnabled")
                javaScriptEnabled = true
                domStorageEnabled = false
                databaseEnabled = false
                allowFileAccess = false
                allowContentAccess = false
                @Suppress("DEPRECATION")
                allowFileAccessFromFileURLs = false
                @Suppress("DEPRECATION")
                allowUniversalAccessFromFileURLs = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                cacheMode = WebSettings.LOAD_DEFAULT
                useWideViewPort = false
                loadWithOverviewMode = false
                builtInZoomControls = false
                displayZoomControls = false
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    // Block all external navigations inside the embedded charting WebView.
                    return true
                }
            }
            addJavascriptInterface(
                PaperPulseChartBridge(
                    mainHandler = mainHandler,
                    onReady = { ver ->
                        chartVersion = ver
                        isChartReady = true
                        loadError = null
                    },
                    onError = { msg ->
                        loadError = msg
                    }
                ),
                "PaperPulseChartBridge"
            )
        }
    }

    LaunchedEffect(isDarkTheme) {
        webView.setBackgroundColor(
            AndroidColor.parseColor(if (isDarkTheme) "#131722" else "#FFFFFF")
        )
    }

    // Load or reload the local TradingView Lightweight Charts host page
    LaunchedEffect(reloadNonce) {
        isChartReady = false
        loadError = null
        webView.loadDataWithBaseURL(
            "https://tradingview-local.paperpulse.app/",
            baseHtml,
            "text/html",
            "UTF-8",
            null
        )
        delay(12_000L)
        if (!isChartReady && loadError == null) {
            loadError = "Timed out loading official TradingView Lightweight Charts. Check internet access or switch to Native Canvas."
        }
    }

    // Push serialized chart payload whenever library is ready or analysis/overlays/style update
    LaunchedEffect(isChartReady, payloadBase64) {
        if (isChartReady) {
            webView.evaluateJavascript("window.renderPaperPulseChartBase64(\"$payloadBase64\");", null)
        }
    }

    // Forward top-bar zoom +/- actions to TradingView chart
    LaunchedEffect(zoomCommandSeq) {
        if (isChartReady && zoomCommandSeq > 0 && zoomDirection != 0) {
            webView.evaluateJavascript("window.tvZoom($zoomDirection);", null)
        }
    }

    // Forward Fit Content action
    LaunchedEffect(fitNonce) {
        if (isChartReady && fitNonce > 0) {
            webView.evaluateJavascript("window.tvResetView();", null)
        }
    }

    DisposableEffect(webView) {
        onDispose {
            webView.removeJavascriptInterface("PaperPulseChartBridge")
            webView.stopLoading()
            webView.destroy()
        }
    }

    Column(
        modifier = modifier.testTag("tradingview_chart_panel"),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // TradingView Advanced Technical Analysis Control Strip
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 6.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TvChartStyle.entries.forEach { style ->
                FilterChip(
                    selected = chartStyle == style,
                    onClick = { onSelectChartStyle(style) },
                    label = { Text(style.label, style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.testTag("tv_style_${style.jsKey}"),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.24f),
                        selectedLabelColor = MaterialTheme.colorScheme.primary
                    )
                )
            }
            FilterChip(
                selected = logScale,
                onClick = onToggleLogScale,
                label = { Text("Log", style = MaterialTheme.typography.labelSmall) },
                modifier = Modifier.testTag("tv_log_scale_toggle"),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.24f),
                    selectedLabelColor = MaterialTheme.colorScheme.primary
                )
            )
            FilterChip(
                selected = magnetCrosshair,
                onClick = onToggleMagnetCrosshair,
                label = { Text("Magnet", style = MaterialTheme.typography.labelSmall) },
                modifier = Modifier.testTag("tv_magnet_toggle"),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.24f),
                    selectedLabelColor = MaterialTheme.colorScheme.primary
                )
            )
            FilterChip(
                selected = false,
                onClick = { fitNonce++ },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.FitScreen,
                        contentDescription = "Fit chart to screen",
                        modifier = Modifier.size(16.dp)
                    )
                },
                label = { Text("Fit", style = MaterialTheme.typography.labelSmall) },
                modifier = Modifier.testTag("tv_fit_content_button")
            )
        }

        // Embedded TradingView Lightweight Charts WebView Surface
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .testTag("tradingview_webview_container")
        ) {
            AndroidView(
                factory = { webView },
                modifier = Modifier.fillMaxSize()
            )

            if (!isChartReady && loadError == null) {
                Surface(
                    color = chartSurface.copy(alpha = 0.9f),
                    modifier = Modifier.fillMaxSize()
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 2.5.dp
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "Initializing TradingView Lightweight Charts…",
                            style = MaterialTheme.typography.labelMedium,
                            color = chartOnSurface
                        )
                    }
                }
            }

            loadError?.let { err ->
                Surface(
                    color = chartSurface.copy(alpha = 0.96f),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp)
                        .testTag("tradingview_error_overlay")
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.WarningAmber,
                            contentDescription = null,
                            tint = LocalTradingColors.current.amber,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "TradingView Library Unavailable",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = chartOnSurface
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = err,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { reloadNonce++ },
                                modifier = Modifier.testTag("tradingview_retry_button")
                            ) {
                                Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.size(4.dp))
                                Text("Retry")
                            }
                            Button(
                                onClick = onFallbackToCanvas,
                                modifier = Modifier.testTag("tradingview_fallback_canvas_button")
                            ) {
                                Text("Switch to Canvas")
                            }
                        }
                    }
                }
            }

            if (isChartReady && chartVersion.isNotBlank()) {
                Surface(
                    color = chartSurface.copy(alpha = 0.8f),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 6.dp, end = 64.dp)
                ) {
                    Text(
                        text = "TV v$chartVersion",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}
