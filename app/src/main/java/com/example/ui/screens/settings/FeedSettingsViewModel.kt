package com.example.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.ai.AiProviderConfig
import com.example.data.ai.SecureAiConfigStore
import com.example.data.feed.live.AngelOnePriceFeed
import com.example.data.feed.live.BrokerFeedSettings
import com.example.data.feed.live.FeedType
import com.example.data.feed.live.LiveConnectionStatus
import com.example.data.feed.live.SegmentFeedConfig
import com.example.data.feed.live.SegmentFeedStatus
import com.example.data.repository.MarketDataRepository
import com.example.data.repository.TradingPreferencesRepository
import com.example.data.repository.TradingRepository
import com.example.domain.engine.RiskMath
import com.example.domain.model.AccountState
import com.example.domain.model.CapitalTransaction
import com.example.domain.model.Instrument
import com.example.domain.model.MarketSegment
import com.example.domain.model.TradingConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.net.URLEncoder
import javax.inject.Inject

data class FeedSettingsUiState(
    val config: TradingConfig = TradingConfig(),
    val brokerSettings: BrokerFeedSettings = BrokerFeedSettings(),
    val aiConfig: AiProviderConfig = AiProviderConfig(),
    val segmentConfigs: Map<MarketSegment, SegmentFeedConfig> = emptyMap(),
    val feedStatuses: Map<FeedType, LiveConnectionStatus> = emptyMap(),
    val liveConnectionStatus: LiveConnectionStatus = LiveConnectionStatus.DISCONNECTED,
    val liveStatusMessage: String = "",
    val segmentStatuses: Map<MarketSegment, SegmentFeedStatus> = emptyMap(),
    val riskControlsSuspended: Boolean = false,
    val suspendedBannerMessage: String = "",
    val instruments: List<Instrument> = emptyList(),
    val accountState: AccountState = AccountState(BigDecimal("1000000"), BigDecimal("1000000"), BigDecimal.ZERO),
    val capitalTransactions: List<CapitalTransaction> = emptyList(),
    val angelDiagnosticLoading: Boolean = false,
    val angelDiagnosticResult: String? = null
)

@HiltViewModel
class FeedSettingsViewModel @Inject constructor(
    private val marketDataRepository: MarketDataRepository,
    private val prefsRepository: TradingPreferencesRepository,
    private val tradingRepository: TradingRepository,
    private val angelOnePriceFeed: AngelOnePriceFeed,
    private val aiConfigStore: SecureAiConfigStore
) : ViewModel() {

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val _angelDiagnosticState = MutableStateFlow<Pair<Boolean, String?>>(false to null)

    private val liveFeedStateFlow = combine(
        marketDataRepository.brokerSettings,
        marketDataRepository.segmentConfigs,
        marketDataRepository.observeFeedStatus(),
        marketDataRepository.liveConnectionStatus,
        marketDataRepository.liveStatusMessage
    ) { settings, segConfigs, feedMap, connStatus, statusMsg ->
        LiveFeedSnapshot(settings, segConfigs, feedMap, connStatus, statusMsg)
    }

    private val safetyStateFlow = combine(
        marketDataRepository.segmentStatuses,
        marketDataRepository.riskControlsSuspended,
        marketDataRepository.suspendedBannerMessage,
        tradingRepository.accountState,
        tradingRepository.capitalTransactions
    ) { segStatuses, suspended, bannerMsg, account, capTxns ->
        SafetyAndCapitalSnapshot(segStatuses, suspended, bannerMsg, account, capTxns)
    }

    private val configAndAiFlow = combine(
        prefsRepository.configFlow,
        aiConfigStore.config
    ) { cfg, aiCfg -> cfg to aiCfg }

    val uiState: StateFlow<FeedSettingsUiState> = combine(
        configAndAiFlow,
        liveFeedStateFlow,
        safetyStateFlow,
        marketDataRepository.instruments,
        _angelDiagnosticState
    ) { (config, aiCfg), live, safety, instruments, diag ->
        FeedSettingsUiState(
            config = config,
            brokerSettings = live.settings,
            aiConfig = aiCfg,
            segmentConfigs = live.segConfigs,
            feedStatuses = live.feedMap,
            liveConnectionStatus = live.connStatus,
            liveStatusMessage = live.statusMsg,
            segmentStatuses = safety.statuses,
            riskControlsSuspended = safety.suspended,
            suspendedBannerMessage = safety.bannerMsg,
            instruments = instruments,
            accountState = safety.account,
            capitalTransactions = safety.capitalTransactions,
            angelDiagnosticLoading = diag.first,
            angelDiagnosticResult = diag.second
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = FeedSettingsUiState()
    )

    fun saveAiConfig(newConfig: AiProviderConfig) {
        aiConfigStore.save(newConfig)
        viewModelScope.launch {
            _messages.emit("AI provider set to ${newConfig.provider.label} (${newConfig.effectiveModel})")
        }
    }

    fun buildBrokerOAuthUrl(broker: String, apiKey: String): String {
        val cleanKey = apiKey.trim()
        val encodedRedirect = URLEncoder.encode("paperpulse://broker-callback?broker=$broker", "UTF-8")
        return when (broker) {
            "angel" -> if (cleanKey.isNotEmpty()) {
                "https://smartapi.angelbroking.com/publisher-login?api_key=$cleanKey&state=paperpulse"
            } else {
                "https://smartapi.angelbroking.com/enable-totp"
            }
            "kite" -> if (cleanKey.isNotEmpty()) {
                "https://kite.zerodha.com/connect/login?v=3&api_key=$cleanKey"
            } else {
                "https://developers.kite.trade/"
            }
            "upstox" -> if (cleanKey.isNotEmpty()) {
                "https://api.upstox.com/v2/login/authorization/dialog?response_type=code&client_id=$cleanKey&redirect_uri=$encodedRedirect"
            } else {
                "https://account.upstox.com/developer/apps"
            }
            else -> "https://smartapi.angelbroking.com/"
        }
    }

    fun onBrokerOAuthTokenCaptured(broker: String, token: String, feedToken: String?) {
        viewModelScope.launch {
            val current = marketDataRepository.brokerSettings.value
            val clean = token.trim()
            val updated = when (broker) {
                "upstox" -> {
                    val segs = current.segmentConfigs.toMutableMap().apply {
                        put(MarketSegment.NSE, SegmentFeedConfig(MarketSegment.NSE, FeedType.UPSTOX_LIVE, true))
                        put(MarketSegment.BSE, SegmentFeedConfig(MarketSegment.BSE, FeedType.UPSTOX_LIVE, true))
                        put(MarketSegment.MCX, SegmentFeedConfig(MarketSegment.MCX, FeedType.UPSTOX_LIVE, true))
                    }
                    current.copy(upstoxAccessToken = clean, segmentConfigs = segs)
                }
                "kite" -> {
                    val segs = current.segmentConfigs.toMutableMap().apply {
                        put(MarketSegment.NSE, SegmentFeedConfig(MarketSegment.NSE, FeedType.KITE_STUB, true))
                        put(MarketSegment.BSE, SegmentFeedConfig(MarketSegment.BSE, FeedType.KITE_STUB, true))
                        put(MarketSegment.MCX, SegmentFeedConfig(MarketSegment.MCX, FeedType.KITE_STUB, true))
                    }
                    current.copy(kiteAccessToken = clean, segmentConfigs = segs)
                }
                else -> {
                    val segs = current.segmentConfigs.toMutableMap().apply {
                        put(MarketSegment.NSE, SegmentFeedConfig(MarketSegment.NSE, FeedType.ANGEL_ONE_LIVE, true))
                        put(MarketSegment.BSE, SegmentFeedConfig(MarketSegment.BSE, FeedType.ANGEL_ONE_LIVE, true))
                        put(MarketSegment.MCX, SegmentFeedConfig(MarketSegment.MCX, FeedType.ANGEL_ONE_LIVE, true))
                    }
                    current.copy(segmentConfigs = segs)
                }
            }
            marketDataRepository.updateBrokerSettings(updated)
            marketDataRepository.reconnectActiveFeed()
            val suffix = if (!feedToken.isNullOrBlank()) " + feed token" else ""
            _messages.emit("${broker.uppercase()} login complete — token$suffix captured & live feed reconnecting")
        }
    }

    fun toggleTradeNotifications(enabled: Boolean) {
        viewModelScope.launch {
            prefsRepository.updateNotificationsEnabled(enabled)
            _messages.emit(if (enabled) "Trade activity notifications enabled" else "Trade activity notifications muted")
        }
    }

    fun toggleSignalNotifications(enabled: Boolean) {
        viewModelScope.launch {
            prefsRepository.updateSignalNotificationsEnabled(enabled)
            _messages.emit(if (enabled) "Signal alerts enabled" else "Signal alerts muted")
        }
    }

    fun toggleFreePublicMarketFeed(enabled: Boolean) {
        viewModelScope.launch {
            prefsRepository.updateUseFreePublicMarketFeed(enabled)
            _messages.emit(
                if (enabled) "Free Public NSE/BSE/MCX/Crypto quote feed enabled"
                else "Switched to offline simulated price generator"
            )
        }
    }

    fun addCapital(amount: BigDecimal, note: String) {
        viewModelScope.launch {
            tradingRepository.addCapital(amount, note)
                .onSuccess { _messages.emit(it) }
                .onFailure { _messages.emit(it.message ?: "Failed to add capital") }
        }
    }

    fun reduceCapital(amount: BigDecimal, note: String) {
        viewModelScope.launch {
            tradingRepository.reduceCapital(amount, note)
                .onSuccess { _messages.emit(it) }
                .onFailure { _messages.emit(it.message ?: "Failed to reduce capital") }
        }
    }

    fun addCryptoCapital(amount: BigDecimal, note: String) {
        viewModelScope.launch {
            tradingRepository.addCryptoCapital(amount, note)
                .onSuccess { _messages.emit(it) }
                .onFailure { _messages.emit(it.message ?: "Failed to add Crypto USD capital") }
        }
    }

    fun reduceCryptoCapital(amount: BigDecimal, note: String) {
        viewModelScope.launch {
            tradingRepository.reduceCryptoCapital(amount, note)
                .onSuccess { _messages.emit(it) }
                .onFailure { _messages.emit(it.message ?: "Failed to reduce Crypto USD capital") }
        }
    }

    fun testAngelOneConnection(settings: BrokerFeedSettings) {
        viewModelScope.launch {
            _angelDiagnosticState.value = true to "Testing Angel One REST login & SmartStream V2 handshake..."
            val result = angelOnePriceFeed.diagnoseConnection(settings)
            _angelDiagnosticState.value = false to result.summary
            if (result.connected) {
                val liveSegments = settings.segmentConfigs.toMutableMap().apply {
                    put(MarketSegment.NSE, SegmentFeedConfig(MarketSegment.NSE, FeedType.ANGEL_ONE_LIVE, true))
                    put(MarketSegment.BSE, SegmentFeedConfig(MarketSegment.BSE, FeedType.ANGEL_ONE_LIVE, true))
                    put(MarketSegment.MCX, SegmentFeedConfig(MarketSegment.MCX, FeedType.ANGEL_ONE_LIVE, true))
                }
                marketDataRepository.updateBrokerSettings(settings.copy(segmentConfigs = liveSegments))
                _messages.emit("Angel One verified & saved — connecting NSE/BSE/MCX live")
            }
        }
    }

    fun saveSlippageAndCharges(slippageBps: Int, flatBrokerage: BigDecimal, cryptoFeePct: BigDecimal) {
        viewModelScope.launch {
            prefsRepository.updateSlippageBps(slippageBps)
            prefsRepository.updateCharges(flatBrokerage, cryptoFeePct)
            _messages.emit("Saved slippage & simulated charge settings")
        }
    }

    fun setFeedForSegment(segment: MarketSegment, feedType: FeedType) {
        marketDataRepository.setFeedForSegment(segment, feedType)
        viewModelScope.launch {
            _messages.emit("${segment.label} switched to ${feedType.label}")
        }
    }

    fun toggleSegmentEnabled(segment: MarketSegment, currentConfig: SegmentFeedConfig) {
        if (currentConfig.isEnabled) {
            val updated = marketDataRepository.brokerSettings.value.segmentConfigs.toMutableMap()
            updated[segment] = currentConfig.copy(isEnabled = false)
            viewModelScope.launch {
                marketDataRepository.updateBrokerSettings(
                    marketDataRepository.brokerSettings.value.copy(segmentConfigs = updated)
                )
                _messages.emit("${segment.label} feed disabled")
            }
        } else {
            viewModelScope.launch {
                marketDataRepository.reconnectSegment(segment)
                _messages.emit("${segment.label} feed enabled")
            }
        }
    }

    fun enableLiveForAllSupportedSegments(draftSettings: BrokerFeedSettings? = null) {
        viewModelScope.launch {
            if (draftSettings != null) {
                val liveSegments = draftSettings.segmentConfigs.toMutableMap().apply {
                    put(MarketSegment.NSE, SegmentFeedConfig(MarketSegment.NSE, FeedType.ANGEL_ONE_LIVE, true))
                    put(MarketSegment.BSE, SegmentFeedConfig(MarketSegment.BSE, FeedType.ANGEL_ONE_LIVE, true))
                    put(MarketSegment.MCX, SegmentFeedConfig(MarketSegment.MCX, FeedType.ANGEL_ONE_LIVE, true))
                    put(MarketSegment.CRYPTO, SegmentFeedConfig(MarketSegment.CRYPTO, FeedType.BINANCE_LIVE, true))
                }
                marketDataRepository.updateBrokerSettings(draftSettings.copy(segmentConfigs = liveSegments))
            } else {
                marketDataRepository.enableLiveForAllSupportedSegments()
            }
            _messages.emit("Enabled Angel One (NSE/BSE/MCX) + Binance (Crypto)")
        }
    }

    fun saveBrokerCredentials(settings: BrokerFeedSettings) {
        viewModelScope.launch {
            val targetSegments = if (settings.hasAngelOneCredentials()) {
                settings.segmentConfigs.toMutableMap().apply {
                    listOf(MarketSegment.NSE, MarketSegment.BSE, MarketSegment.MCX).forEach { seg ->
                        val cur = get(seg)
                        if (cur == null || cur.feedType == FeedType.SIMULATED || !cur.isEnabled) {
                            put(seg, SegmentFeedConfig(seg, FeedType.ANGEL_ONE_LIVE, true))
                        }
                    }
                }
            } else {
                settings.segmentConfigs
            }
            marketDataRepository.updateBrokerSettings(settings.copy(segmentConfigs = targetSegments))
            _messages.emit("Saved encrypted broker credentials & refreshed feeds")
        }
    }

    fun switchSuspendedToSimulated() {
        viewModelScope.launch {
            marketDataRepository.setActiveFeed(FeedType.SIMULATED)
            _messages.emit("Switched suspended segments to Simulated Feed")
        }
    }

    fun reconnectFeed() {
        viewModelScope.launch {
            marketDataRepository.reconnectActiveFeed()
            _messages.emit("Reconnecting active live segment feeds...")
        }
    }

    fun nudgeSymbol(symbol: String, percentDelta: BigDecimal) {
        viewModelScope.launch {
            marketDataRepository.nudgePrice(symbol, percentDelta)
            _messages.emit("Nudged $symbol by $percentDelta%")
        }
    }

    fun triggerSquareOff() {
        viewModelScope.launch {
            val count = tradingRepository.triggerSimulatedSquareOff()
            _messages.emit("Intraday Square-Off (15:15 Eq/Fut • 15:40 Opt) completed ($count position(s) closed)")
        }
    }

    fun resetAccount(
        newCapital: BigDecimal = BigDecimal("1000000.00"),
        newCryptoCapital: BigDecimal = BigDecimal("10000.00")
    ) {
        viewModelScope.launch {
            tradingRepository.resetPaperAccount(newCapital, newCryptoCapital)
            _messages.emit("Paper account reset: India ${RiskMath.formatPortfolioInr(newCapital)} • Crypto ${RiskMath.formatPortfolioUsd(newCryptoCapital)}")
        }
    }

    private data class LiveFeedSnapshot(
        val settings: BrokerFeedSettings,
        val segConfigs: Map<MarketSegment, SegmentFeedConfig>,
        val feedMap: Map<FeedType, LiveConnectionStatus>,
        val connStatus: LiveConnectionStatus,
        val statusMsg: String
    )

    private data class SafetyAndCapitalSnapshot(
        val statuses: Map<MarketSegment, SegmentFeedStatus>,
        val suspended: Boolean,
        val bannerMsg: String,
        val account: AccountState,
        val capitalTransactions: List<CapitalTransaction>
    )
}
