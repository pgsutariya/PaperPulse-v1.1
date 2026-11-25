package com.example.ui.screens.settings

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import com.example.data.ai.AiConnectionState
import com.example.data.ai.AiProvider
import com.example.data.ai.AiProviderConfig
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.domain.engine.RiskMath
import com.example.data.feed.live.BrokerFeedSettings
import com.example.data.feed.live.FeedType
import com.example.data.feed.live.LiveConnectionStatus
import com.example.data.feed.live.SegmentBadgeMode
import com.example.data.feed.live.SegmentFeedConfig
import com.example.domain.model.MarketSegment
import com.example.ui.components.FeedHealthBadge
import com.example.ui.components.FeedLostWarningBanner
import com.example.ui.components.SegmentBadge
import com.example.ui.theme.LocalTradingColors
import com.example.ui.theme.MonoPriceSmall
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedSettingsScreen(
    onBack: () -> Unit,
    viewModel: FeedSettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val tradingColors = LocalTradingColors.current
    val context = LocalContext.current

    var pendingNotifAction by remember { mutableStateOf<String?>(null) }
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        when (pendingNotifAction) {
            "trade" -> viewModel.toggleTradeNotifications(granted)
            "signal" -> viewModel.toggleSignalNotifications(granted)
        }
        pendingNotifAction = null
    }

    var activeOAuthSession by remember { mutableStateOf<Triple<String, String, String>?>(null) }

    fun launchBrokerLogin(broker: String, title: String, apiKey: String) {
        val url = viewModel.buildBrokerOAuthUrl(broker, apiKey)
        activeOAuthSession = Triple(broker, title, url)
    }

    val saved = state.brokerSettings
    var simIntervalText by remember(saved.simulatedTickIntervalMs) {
        mutableStateOf(saved.simulatedTickIntervalMs.toString())
    }
    var angelApiKey by remember(saved.angelOneApiKey) { mutableStateOf(saved.angelOneApiKey) }
    var angelClientCode by remember(saved.angelOneClientCode) { mutableStateOf(saved.angelOneClientCode) }
    var angelPin by remember(saved.angelOnePin) { mutableStateOf(saved.angelOnePin) }
    var angelTotpSecret by remember(saved.angelOneTotpSecret) { mutableStateOf(saved.angelOneTotpSecret) }
    var upstoxApiKey by remember(saved.upstoxApiKey) { mutableStateOf(saved.upstoxApiKey) }
    var upstoxToken by remember(saved.upstoxAccessToken) { mutableStateOf(saved.upstoxAccessToken) }
    var kiteApiKey by remember(saved.kiteApiKey) { mutableStateOf(saved.kiteApiKey) }
    var kiteToken by remember(saved.kiteAccessToken) { mutableStateOf(saved.kiteAccessToken) }

    val savedAi = state.aiConfig
    var selectedAiProvider by remember(savedAi.provider) { mutableStateOf(savedAi.provider) }
    var aiApiKey by remember(savedAi.provider, savedAi.apiKey) { mutableStateOf(savedAi.apiKey) }
    var aiModelName by remember(savedAi.provider, savedAi.modelName) { mutableStateOf(savedAi.effectiveModel) }
    var aiBaseUrl by remember(savedAi.provider, savedAi.customBaseUrl) { mutableStateOf(savedAi.effectiveBaseUrl) }
    var aiAutoConnect by remember(savedAi.autoConnect) { mutableStateOf(savedAi.autoConnect) }
    var showAiKey by remember { mutableStateOf(false) }
    var modelDropdownExpanded by remember { mutableStateOf(false) }
    var customModelMode by remember(savedAi.provider) { mutableStateOf(false) }

    var slippageText by remember(state.config.slippageBps) { mutableStateOf(state.config.slippageBps.toString()) }
    var flatBrokerageText by remember(state.config.flatBrokerage) { mutableStateOf(state.config.flatBrokerage.toPlainString()) }
    var cryptoFeeText by remember(state.config.cryptoFeePercent) { mutableStateOf(state.config.cryptoFeePercent.toPlainString()) }
    var capitalDeltaText by remember { mutableStateOf("100000") }
    var cryptoDeltaText by remember { mutableStateOf("5000") }
    var capitalNoteText by remember { mutableStateOf("") }
    var resetCapitalText by remember { mutableStateOf("1000000") }
    var resetCryptoCapitalText by remember { mutableStateOf("10000") }

    var selectedNudgeSymbol by remember(state.instruments) {
        mutableStateOf(state.instruments.firstOrNull()?.symbol ?: "RELIANCE")
    }
    var editingSegment by remember { mutableStateOf<MarketSegment?>(null) }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Multi-Feed & Simulation", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("settings_back_button")) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (state.riskControlsSuspended) {
                FeedLostWarningBanner(
                    headline = state.suspendedBannerMessage.ifBlank { "FEED LOST — risk controls suspended" },
                    detail = "Affected segments are paused to avoid false triggers; unaffected markets continue.",
                    onReconnect = viewModel::reconnectFeed,
                    onSwitchToSimulated = viewModel::switchSuspendedToSimulated
                )
            }

            // 0. One-Click Broker Login & Free Public Market Data
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, tradingColors.border)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Rounded.Public, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        Text("FREE PUBLIC FEED & 1-CLICK BROKER LOGIN", style = MaterialTheme.typography.labelLarge)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Free Real Market Quotes (NSE / BSE / MCX / Crypto)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(
                                "Anchors the default feed with live Yahoo Finance & Binance public quotes. India hours: 09:15–15:15 Eq/Fut • 09:15–15:40 Options.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = state.config.useFreePublicMarketFeed,
                            onCheckedChange = viewModel::toggleFreePublicMarketFeed,
                            modifier = Modifier.testTag("free_public_feed_switch")
                        )
                    }

                    HorizontalDivider(color = tradingColors.border)

                    Text("1-CLICK BROKER OAUTH LOGIN (LIVE STREAM)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { launchBrokerLogin("angel", "Angel One", angelApiKey) },
                            modifier = Modifier.weight(1f).testTag("one_click_login_angel"),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Angel One", style = MaterialTheme.typography.labelMedium)
                        }
                        OutlinedButton(
                            onClick = { launchBrokerLogin("kite", "Zerodha Kite", kiteApiKey) },
                            modifier = Modifier.weight(1f).testTag("one_click_login_kite"),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Kite", style = MaterialTheme.typography.labelMedium)
                        }
                        OutlinedButton(
                            onClick = { launchBrokerLogin("upstox", "Upstox", upstoxApiKey) },
                            modifier = Modifier.weight(1f).testTag("one_click_login_upstox"),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Upstox", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    Text(
                        "Opens securely inside PaperPulse and auto-captures the broker redirect token to return immediately.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 0a. AI Agent, Auto-Connect & Model Switcher Configuration
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
            ) {
                val aiConn = state.aiConnectionStatus
                val isSameAgentStatus = aiConn.provider == selectedAiProvider
                val dropdownModels = remember(selectedAiProvider, aiConn.provider, aiConn.availableModels, aiModelName) {
                    val base = if (aiConn.provider == selectedAiProvider && aiConn.availableModels.isNotEmpty()) {
                        aiConn.availableModels
                    } else {
                        selectedAiProvider.availableModels
                    }
                    (listOf(aiModelName.trim()).filter { it.isNotBlank() } + base + selectedAiProvider.availableModels).distinct()
                }
                val badgeBg = when {
                    !isSameAgentStatus -> MaterialTheme.colorScheme.surfaceContainerHigh
                    aiConn.state == AiConnectionState.CONNECTED -> tradingColors.bullishContainer
                    aiConn.state == AiConnectionState.CONNECTING -> tradingColors.amberContainer
                    aiConn.state == AiConnectionState.ERROR -> tradingColors.bearishContainer
                    else -> MaterialTheme.colorScheme.surfaceContainerHigh
                }
                val badgeFg = when {
                    !isSameAgentStatus -> MaterialTheme.colorScheme.onSurfaceVariant
                    aiConn.state == AiConnectionState.CONNECTED -> tradingColors.bullish
                    aiConn.state == AiConnectionState.CONNECTING -> tradingColors.amber
                    aiConn.state == AiConnectionState.ERROR -> tradingColors.bearish
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                val badgeText = when {
                    !isSameAgentStatus -> "UNSAVED AGENT"
                    aiConn.state == AiConnectionState.CONNECTED -> "CONNECTED${aiConn.latencyMs?.let { " • ${it}ms" } ?: ""}"
                    aiConn.state == AiConnectionState.CONNECTING -> "CONNECTING..."
                    aiConn.state == AiConnectionState.ERROR -> "CONN ERROR"
                    else -> "READY"
                }

                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            Text("AI AGENT & API CONFIG", style = MaterialTheme.typography.labelLarge)
                        }
                        Surface(shape = RoundedCornerShape(8.dp), color = badgeBg) {
                            Text(
                                text = badgeText,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = badgeFg,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }

                    Text(
                        selectedAiProvider.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // AI Agent selector
                    Text("SELECT AI AGENT", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        AiProvider.entries.forEach { provider ->
                            FilterChip(
                                selected = selectedAiProvider == provider,
                                onClick = {
                                    val savedForAgent = viewModel.loadSavedDraftForAgent(provider)
                                    selectedAiProvider = provider
                                    aiApiKey = savedForAgent.apiKey
                                    aiModelName = savedForAgent.effectiveModel
                                    aiBaseUrl = savedForAgent.effectiveBaseUrl
                                    customModelMode = false
                                    val nextDraft = savedForAgent.copy(autoConnect = aiAutoConnect)
                                    viewModel.saveAiConfig(nextDraft, autoConnectNow = aiAutoConnect)
                                },
                                label = { Text(provider.shortLabel) },
                                modifier = Modifier.testTag("ai_provider_chip_${provider.name.lowercase()}")
                            )
                        }
                    }

                    // Auto-Connect to API toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Auto-Connect to API & Sync Models", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Automatically validates credentials & refreshes available models on key entry or agent switch.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = aiAutoConnect,
                            onCheckedChange = { enabled ->
                                aiAutoConnect = enabled
                                val updatedDraft = AiProviderConfig(
                                    provider = selectedAiProvider,
                                    apiKey = aiApiKey,
                                    modelName = aiModelName,
                                    customBaseUrl = aiBaseUrl,
                                    autoConnect = enabled
                                )
                                viewModel.saveAiConfig(updatedDraft, autoConnectNow = enabled)
                            },
                            modifier = Modifier.testTag("ai_auto_connect_switch")
                        )
                    }

                    if (selectedAiProvider.requiresApiKey) {
                        OutlinedTextField(
                            value = aiApiKey,
                            onValueChange = { newKey ->
                                aiApiKey = newKey
                                if (aiAutoConnect) {
                                    viewModel.scheduleAutoConnect(
                                        AiProviderConfig(
                                            provider = selectedAiProvider,
                                            apiKey = newKey,
                                            modelName = aiModelName,
                                            customBaseUrl = aiBaseUrl,
                                            autoConnect = true
                                        )
                                    )
                                }
                            },
                            label = { Text("${selectedAiProvider.shortLabel} API Key (AES-256 Encrypted)") },
                            visualTransformation = if (showAiKey) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { showAiKey = !showAiKey }) {
                                    Icon(
                                        imageVector = if (showAiKey) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                        contentDescription = if (showAiKey) "Hide API Key" else "Show API Key"
                                    )
                                }
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("ai_api_key_input")
                        )
                    } else {
                        EncryptedNoticeBanner("Uses Firebase AI Logic with App Check & reCAPTCHA Enterprise (zero API key needed). Switch Agent above to use your own Gemini, Claude, DeepSeek, or OpenAI key.")
                    }

                    // Model Switcher Dropdown dynamically populated per AI Agent
                    ExposedDropdownMenuBox(
                        expanded = modelDropdownExpanded,
                        onExpandedChange = { modelDropdownExpanded = !modelDropdownExpanded },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = aiModelName.ifBlank { selectedAiProvider.defaultModel },
                            onValueChange = {},
                            readOnly = true,
                            label = {
                                Text(
                                    "Active ${selectedAiProvider.shortLabel} Model (${dropdownModels.size} available)"
                                )
                            },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelDropdownExpanded) },
                            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                            singleLine = true,
                            modifier = Modifier
                                .menuAnchor()
                                .fillMaxWidth()
                                .testTag("ai_model_switcher_dropdown")
                        )
                        ExposedDropdownMenu(
                            expanded = modelDropdownExpanded,
                            onDismissRequest = { modelDropdownExpanded = false }
                        ) {
                            dropdownModels.forEach { modelId ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = modelId + if (modelId == selectedAiProvider.defaultModel) " (Recommended)" else "",
                                            fontWeight = if (modelId == aiModelName) FontWeight.Bold else FontWeight.Normal,
                                            color = if (modelId == aiModelName) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                        )
                                    },
                                    onClick = {
                                        aiModelName = modelId
                                        customModelMode = false
                                        modelDropdownExpanded = false
                                        viewModel.saveAiConfig(
                                            AiProviderConfig(
                                                provider = selectedAiProvider,
                                                apiKey = aiApiKey,
                                                modelName = modelId,
                                                customBaseUrl = aiBaseUrl,
                                                autoConnect = aiAutoConnect
                                            ),
                                            autoConnectNow = false
                                        )
                                    },
                                    modifier = Modifier.testTag("ai_model_option_$modelId")
                                )
                            }
                            HorizontalDivider(color = tradingColors.border)
                            DropdownMenuItem(
                                text = { Text("Enter custom model ID...", color = MaterialTheme.colorScheme.primary) },
                                onClick = {
                                    customModelMode = true
                                    modelDropdownExpanded = false
                                }
                            )
                        }
                    }

                    if (customModelMode) {
                        OutlinedTextField(
                            value = aiModelName,
                            onValueChange = { aiModelName = it },
                            label = { Text("Custom ${selectedAiProvider.shortLabel} Model ID") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("ai_model_input")
                        )
                    }

                    if (selectedAiProvider == AiProvider.OPENAI_COMPATIBLE) {
                        OutlinedTextField(
                            value = aiBaseUrl,
                            onValueChange = { aiBaseUrl = it },
                            label = { Text("HTTPS Base URL (OpenAI / OpenRouter / Groq)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("ai_base_url_input")
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(
                                "OpenAI" to "https://api.openai.com/v1",
                                "OpenRouter" to "https://openrouter.ai/api/v1",
                                "Groq" to "https://api.groq.com/openai/v1"
                            ).forEach { (presetLabel, presetUrl) ->
                                FilterChip(
                                    selected = aiBaseUrl.trimEnd('/') == presetUrl,
                                    onClick = { aiBaseUrl = presetUrl },
                                    label = { Text(presetLabel) }
                                )
                            }
                        }
                    }

                    // Live Auto-Connect Status Banner
                    if (isSameAgentStatus && aiConn.message.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = badgeBg,
                            modifier = Modifier.fillMaxWidth().testTag("ai_connection_status_banner")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (aiConn.state == AiConnectionState.CONNECTING) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = badgeFg)
                                }
                                Text(
                                    text = aiConn.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = badgeFg
                                )
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = {
                                viewModel.testAndSyncAiModels(
                                    AiProviderConfig(
                                        provider = selectedAiProvider,
                                        apiKey = aiApiKey,
                                        modelName = aiModelName,
                                        customBaseUrl = aiBaseUrl,
                                        autoConnect = aiAutoConnect
                                    )
                                )
                            },
                            enabled = aiConn.state != AiConnectionState.CONNECTING,
                            modifier = Modifier.weight(1f).testTag("test_sync_ai_button"),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Rounded.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Test & Sync")
                        }
                        Button(
                            onClick = {
                                viewModel.saveAiConfig(
                                    AiProviderConfig(
                                        provider = selectedAiProvider,
                                        apiKey = aiApiKey,
                                        modelName = aiModelName,
                                        customBaseUrl = aiBaseUrl,
                                        autoConnect = aiAutoConnect
                                    ),
                                    autoConnectNow = aiAutoConnect
                                )
                            },
                            modifier = Modifier.weight(1f).testTag("save_ai_config_button"),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Save (${selectedAiProvider.shortLabel})")
                        }
                    }
                }
            }

            // 0b. Trade Activity & Signal Notifications
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, tradingColors.border)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Rounded.NotificationsActive, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        Text("NOTIFICATIONS", style = MaterialTheme.typography.labelLarge)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Trade Activity Alerts", style = MaterialTheme.typography.titleSmall)
                            Text("Order fills, pending modifications, averaging, SL/TSL/Target exits & square-offs", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = state.config.notificationsEnabled,
                            onCheckedChange = { want ->
                                if (want && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    pendingNotifAction = "trade"
                                    notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    viewModel.toggleTradeNotifications(want)
                                }
                            },
                            modifier = Modifier.testTag("trade_notifications_switch")
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Signal & Target/SL Alerts", style = MaterialTheme.typography.titleSmall)
                            Text("New high-conviction signals, BTST/STBT alerts, and T1/T2/SL resolution updates", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = state.config.signalNotificationsEnabled,
                            onCheckedChange = { want ->
                                if (want && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    pendingNotifAction = "signal"
                                    notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    viewModel.toggleSignalNotifications(want)
                                }
                            },
                            modifier = Modifier.testTag("signal_notifications_switch")
                        )
                    }
                }
            }

            // 1. Concurrent Per-Segment Feed Matrix
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, tradingColors.border)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("SEGMENT FEED MATRIX (CONCURRENT)", style = MaterialTheme.typography.labelLarge)
                            Text(
                                text = "Each market runs its own WebSocket & fallback rules independently.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Button(
                        onClick = {
                            viewModel.enableLiveForAllSupportedSegments(
                                saved.copy(
                                    angelOneApiKey = angelApiKey,
                                    angelOneClientCode = angelClientCode,
                                    angelOnePin = angelPin,
                                    angelOneTotpSecret = angelTotpSecret,
                                    upstoxApiKey = upstoxApiKey,
                                    upstoxAccessToken = upstoxToken,
                                    kiteApiKey = kiteApiKey,
                                    kiteAccessToken = kiteToken,
                                    simulatedTickIntervalMs = (simIntervalText.toLongOrNull() ?: 1000L).coerceIn(300L, 5000L)
                                )
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("enable_live_all_segments_button"),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Rounded.Bolt, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Enable Live for All Supported Segments", fontWeight = FontWeight.Bold)
                    }

                    HorizontalDivider(color = tradingColors.border)

                    // Matrix header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("SEGMENT", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("STATUS & LAST TICK (IST)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    MarketSegment.entries.forEach { segment ->
                        val cfg = state.segmentConfigs[segment]
                            ?: SegmentFeedConfig(segment, FeedType.SIMULATED, true)
                        val segStatus = state.segmentStatuses[segment]
                        val badge = segStatus?.badge ?: SegmentBadgeMode.SIMULATED
                        val connStatus = segStatus?.connectionStatus ?: LiveConnectionStatus.DISCONNECTED
                        val tsText = segStatus?.lastTickMillis?.let { formatIstTime(it) } ?: "Waiting..."

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            border = BorderStroke(1.dp, tradingColors.border),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("segment_matrix_row_${segment.name.lowercase()}")
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        SegmentBadge(segment = segment)
                                        FeedHealthBadge(badge = badge)
                                        StatusPill(status = connStatus)
                                    }
                                    Text(
                                        text = tsText,
                                        style = MonoPriceSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = cfg.feedType.label + if (!cfg.isEnabled) " (Disabled)" else "",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = segStatus?.detail ?: "",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 3,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        OutlinedButton(
                                            onClick = { editingSegment = segment },
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.testTag("change_feed_${segment.name.lowercase()}")
                                        ) {
                                            Text("Change", style = MaterialTheme.typography.labelMedium)
                                        }
                                        OutlinedButton(
                                            onClick = { viewModel.toggleSegmentEnabled(segment, cfg) },
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.testTag("toggle_feed_${segment.name.lowercase()}")
                                        ) {
                                            Text(
                                                text = if (cfg.isEnabled) "Disable" else "Enable",
                                                style = MaterialTheme.typography.labelMedium
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    OutlinedButton(
                        onClick = viewModel::reconnectFeed,
                        modifier = Modifier.fillMaxWidth().testTag("reconnect_feed_button"),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Reconnect Active Live Feeds")
                    }
                }
            }

            // 2. Encrypted Broker Credentials & Simulator Interval
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, tradingColors.border)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("BROKER CREDENTIALS (ENCRYPTED ON-DEVICE)", style = MaterialTheme.typography.labelLarge)
                    EncryptedNoticeBanner("Credentials are encrypted via AES-256 EncryptedSharedPreferences. Crypto Binance requires no key. Never places real orders.")

                    val angelStatus = state.feedStatuses[FeedType.ANGEL_ONE_LIVE] ?: LiveConnectionStatus.DISCONNECTED
                    val angelDetail = listOf(MarketSegment.NSE, MarketSegment.BSE, MarketSegment.MCX)
                        .mapNotNull { state.segmentStatuses[it] }
                        .firstOrNull { it.activeFeedType == FeedType.ANGEL_ONE_LIVE && it.detail.isNotBlank() }
                        ?.detail

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Angel One SmartAPI (NSE / BSE / MCX)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        StatusPill(status = angelStatus)
                    }
                    if (!angelDetail.isNullOrBlank()) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (angelStatus == LiveConnectionStatus.CONNECTED) {
                                tradingColors.bullishContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = angelDetail,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (angelStatus == LiveConnectionStatus.CONNECTED) {
                                    tradingColors.bullish
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                            )
                        }
                    }
                    OutlinedTextField(
                        value = angelApiKey,
                        onValueChange = { angelApiKey = it },
                        label = { Text("SmartAPI Key") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("angel_api_key_input")
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = angelClientCode,
                            onValueChange = { angelClientCode = it },
                            label = { Text("Client Code") },
                            singleLine = true,
                            modifier = Modifier.weight(1f).testTag("angel_client_code_input")
                        )
                        OutlinedTextField(
                            value = angelPin,
                            onValueChange = { angelPin = it },
                            label = { Text("MPIN") },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            singleLine = true,
                            modifier = Modifier.weight(1f).testTag("angel_pin_input")
                        )
                    }
                    OutlinedTextField(
                        value = angelTotpSecret,
                        onValueChange = { angelTotpSecret = it },
                        label = { Text("Base32 TOTP Secret") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("angel_totp_input")
                    )

                    OutlinedButton(
                        onClick = {
                            viewModel.testAngelOneConnection(
                                saved.copy(
                                    angelOneApiKey = angelApiKey,
                                    angelOneClientCode = angelClientCode,
                                    angelOnePin = angelPin,
                                    angelOneTotpSecret = angelTotpSecret,
                                    upstoxApiKey = upstoxApiKey,
                                    upstoxAccessToken = upstoxToken,
                                    kiteApiKey = kiteApiKey,
                                    kiteAccessToken = kiteToken,
                                    simulatedTickIntervalMs = (simIntervalText.toLongOrNull() ?: 1000L).coerceIn(300L, 5000L)
                                )
                            )
                        },
                        enabled = !state.angelDiagnosticLoading,
                        modifier = Modifier.fillMaxWidth().testTag("test_angel_one_button"),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            if (state.angelDiagnosticLoading) "Testing Angel One Login & WebSocket..."
                            else "Test, Save & Connect Angel One"
                        )
                    }

                    state.angelDiagnosticResult?.let { diagText ->
                        val ok = diagText.contains("CONNECTED")
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (ok) tradingColors.bullishContainer else tradingColors.bearishContainer,
                            modifier = Modifier.fillMaxWidth().testTag("angel_diagnostic_output")
                        ) {
                            Text(
                                text = diagText,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (ok) tradingColors.bullish else tradingColors.bearish,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }

                    HorizontalDivider(color = tradingColors.border)
                    Text("Upstox V3 / Zerodha Kite (Optional)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = upstoxApiKey,
                            onValueChange = { upstoxApiKey = it },
                            label = { Text("Upstox API Key") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = upstoxToken,
                            onValueChange = { upstoxToken = it },
                            label = { Text("Upstox Token") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = kiteApiKey,
                            onValueChange = { kiteApiKey = it },
                            label = { Text("Kite API Key") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = kiteToken,
                            onValueChange = { kiteToken = it },
                            label = { Text("Kite Token") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    OutlinedTextField(
                        value = simIntervalText,
                        onValueChange = { simIntervalText = it.filter(Char::isDigit) },
                        label = { Text("Simulated Tick Interval (ms, 300 - 5000)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Button(
                        onClick = {
                            viewModel.saveBrokerCredentials(
                                saved.copy(
                                    angelOneApiKey = angelApiKey,
                                    angelOneClientCode = angelClientCode,
                                    angelOnePin = angelPin,
                                    angelOneTotpSecret = angelTotpSecret,
                                    upstoxApiKey = upstoxApiKey,
                                    upstoxAccessToken = upstoxToken,
                                    kiteApiKey = kiteApiKey,
                                    kiteAccessToken = kiteToken,
                                    simulatedTickIntervalMs = (simIntervalText.toLongOrNull() ?: 1000L).coerceIn(300L, 5000L)
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth().testTag("apply_feed_button"),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Save Credentials & Refresh Feeds")
                    }
                }
            }

            // 3. Slippage & Charges
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, tradingColors.border)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("SIMULATED SLIPPAGE & CHARGES", style = MaterialTheme.typography.labelLarge)
                    OutlinedTextField(
                        value = slippageText,
                        onValueChange = { slippageText = it.filter(Char::isDigit) },
                        label = { Text("Simulated Slippage (bps, 100 bps = 1%)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = flatBrokerageText,
                            onValueChange = { flatBrokerageText = it },
                            label = { Text("NSE/BSE/MCX Fee (₹/leg)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = cryptoFeeText,
                            onValueChange = { cryptoFeeText = it },
                            label = { Text("Crypto Fee (%)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            viewModel.saveSlippageAndCharges(
                                slippageBps = slippageText.toIntOrNull() ?: 5,
                                flatBrokerage = flatBrokerageText.toBigDecimalOrNull() ?: BigDecimal("20.00"),
                                cryptoFeePct = cryptoFeeText.toBigDecimalOrNull() ?: BigDecimal("0.10")
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Save Execution Parameters")
                    }
                }
            }

            // 4. Engine Test & Price Nudge Tools
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, tradingColors.border)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("RISK ENGINE TEST CONTROLS", style = MaterialTheme.typography.labelLarge)
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        state.instruments.take(10).forEach { inst ->
                            FilterChip(
                                selected = selectedNudgeSymbol == inst.symbol,
                                onClick = { selectedNudgeSymbol = inst.symbol },
                                label = { Text(inst.symbol) }
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("-5%" to BigDecimal("-5"), "-2%" to BigDecimal("-2"), "+2%" to BigDecimal("2"), "+5%" to BigDecimal("5")).forEach { (label, delta) ->
                            OutlinedButton(
                                onClick = { viewModel.nudgeSymbol(selectedNudgeSymbol, delta) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(label, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }

                    OutlinedButton(
                        onClick = viewModel::triggerSquareOff,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Trigger Intraday Square-Off (15:15 Eq/Fut • 15:40 Opt)")
                    }
                }
            }

            // 5. Virtual Paper Capital Management (Add / Reduce / Reset + Record)
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, tradingColors.border)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Rounded.AccountBalanceWallet, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        Text("PAPER CAPITAL MANAGEMENT", style = MaterialTheme.typography.labelLarge)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Total Capital: ${RiskMath.formatPortfolioInr(state.accountState.virtualCapital)}", style = MonoPriceSmall, fontWeight = FontWeight.Bold)
                        Text("Free Margin: ${RiskMath.formatPortfolioInr(state.accountState.availableMargin)}", style = MonoPriceSmall, color = MaterialTheme.colorScheme.primary)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = capitalDeltaText,
                            onValueChange = { capitalDeltaText = it.filter { ch -> ch.isDigit() || ch == '.' } },
                            label = { Text("Amount (₹)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = capitalNoteText,
                            onValueChange = { capitalNoteText = it },
                            label = { Text("Note") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = {
                                capitalDeltaText.toBigDecimalOrNull()?.let {
                                    viewModel.addCapital(it, capitalNoteText.ifBlank { "Capital top-up" })
                                }
                            },
                            modifier = Modifier.weight(1f).testTag("settings_add_capital_button"),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = tradingColors.bullish, contentColor = Color.White)
                        ) {
                            Text("+ Add Funds")
                        }
                        OutlinedButton(
                            onClick = {
                                capitalDeltaText.toBigDecimalOrNull()?.let {
                                    viewModel.reduceCapital(it, capitalNoteText.ifBlank { "Capital withdrawal" })
                                }
                            },
                            modifier = Modifier.weight(1f).testTag("settings_reduce_capital_button"),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("- Reduce Funds", color = tradingColors.bearish)
                        }
                    }

                    HorizontalDivider(color = tradingColors.border)

                    Text("CRYPTO WALLET ($ USD)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Crypto Capital: ${RiskMath.formatPortfolioUsd(state.accountState.cryptoVirtualCapital)}", style = MonoPriceSmall, fontWeight = FontWeight.Bold)
                        Text("Free Margin: ${RiskMath.formatPortfolioUsd(state.accountState.cryptoAvailableMargin)}", style = MonoPriceSmall, color = MaterialTheme.colorScheme.primary)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = cryptoDeltaText,
                            onValueChange = { cryptoDeltaText = it.filter { ch -> ch.isDigit() || ch == '.' } },
                            label = { Text("Crypto Amount ($)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = {
                                cryptoDeltaText.toBigDecimalOrNull()?.let {
                                    viewModel.addCryptoCapital(it, capitalNoteText.ifBlank { "Crypto USD top-up" })
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = tradingColors.bullish, contentColor = Color.White),
                            modifier = Modifier.testTag("settings_add_crypto_capital_button")
                        ) {
                            Text("+$")
                        }
                        OutlinedButton(
                            onClick = {
                                cryptoDeltaText.toBigDecimalOrNull()?.let {
                                    viewModel.reduceCryptoCapital(it, capitalNoteText.ifBlank { "Crypto USD withdrawal" })
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.testTag("settings_reduce_crypto_capital_button")
                        ) {
                            Text("-$", color = tradingColors.bearish)
                        }
                    }

                    HorizontalDivider(color = tradingColors.border)

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = resetCapitalText,
                            onValueChange = { resetCapitalText = it.filter { ch -> ch.isDigit() || ch == '.' } },
                            label = { Text("Reset India (₹)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = resetCryptoCapitalText,
                            onValueChange = { resetCryptoCapitalText = it.filter { ch -> ch.isDigit() || ch == '.' } },
                            label = { Text("Reset Crypto ($)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Button(
                        onClick = {
                            val targetInr = resetCapitalText.toBigDecimalOrNull() ?: BigDecimal("1000000")
                            val targetUsd = resetCryptoCapitalText.toBigDecimalOrNull() ?: BigDecimal("10000")
                            viewModel.resetAccount(targetInr, targetUsd)
                        },
                        modifier = Modifier.fillMaxWidth().testTag("reset_account_button"),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = tradingColors.bearish, contentColor = Color.White)
                    ) {
                        Text("Reset Paper Account (India ₹ + Crypto $)")
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    activeOAuthSession?.let { (brokerKey, brokerTitle, loginUrl) ->
        BrokerOAuthWebViewDialog(
            brokerKey = brokerKey,
            brokerTitle = brokerTitle,
            loginUrl = loginUrl,
            onTokenCaptured = { broker, token, feedToken ->
                activeOAuthSession = null
                viewModel.onBrokerOAuthTokenCaptured(broker, token, feedToken)
            },
            onDismiss = { activeOAuthSession = null }
        )
    }

    editingSegment?.let { seg ->
        val supportedFeeds = FeedType.entries.filter { it.supportsSegment(seg) }
        val currentFeed = state.segmentConfigs[seg]?.feedType ?: FeedType.SIMULATED
        AlertDialog(
            onDismissRequest = { editingSegment = null },
            title = { Text("Select Feed for ${seg.label}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    supportedFeeds.forEach { ft ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = ft == currentFeed,
                                onClick = {
                                    viewModel.setFeedForSegment(seg, ft)
                                    editingSegment = null
                                }
                            )
                            Column(modifier = Modifier.padding(start = 6.dp)) {
                                Text(ft.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text(ft.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { editingSegment = null }) {
                    Text("Close")
                }
            }
        )
    }
}

@Composable
private fun StatusPill(status: LiveConnectionStatus) {
    val tradingColors = LocalTradingColors.current
    val (bg, fg) = when (status) {
        LiveConnectionStatus.CONNECTED -> tradingColors.bullishContainer to tradingColors.bullish
        LiveConnectionStatus.CONNECTING, LiveConnectionStatus.RECONNECTING ->
            tradingColors.amberContainer to tradingColors.amber
        LiveConnectionStatus.ERROR -> tradingColors.bearishContainer to tradingColors.bearish
        LiveConnectionStatus.DISCONNECTED -> MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(shape = RoundedCornerShape(6.dp), color = bg) {
        Text(
            text = status.name,
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun EncryptedNoticeBanner(text: String) {
    val tradingColors = LocalTradingColors.current
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = tradingColors.amberContainer
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Rounded.Lock, contentDescription = null, tint = tradingColors.amber)
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

private fun formatIstTime(millis: Long): String {
    val fmt = SimpleDateFormat("HH:mm:ss", Locale.forLanguageTag("en-IN")).apply {
        timeZone = TimeZone.getTimeZone("Asia/Kolkata")
    }
    return fmt.format(Date(millis))
}
