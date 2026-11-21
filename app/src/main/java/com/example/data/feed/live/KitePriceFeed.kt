package com.example.data.feed.live

import com.example.data.feed.PriceFeed
import com.example.domain.engine.RiskMath
import com.example.domain.model.FeedConnectionState
import com.example.domain.model.FeedSourceType
import com.example.domain.model.Instrument
import com.example.domain.model.PriceTick
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Optional Zerodha Kite Connect (KiteTicker) WebSocket stub adapter.
 * Prices only — never places real orders.
 */
@Singleton
class KitePriceFeed @Inject constructor(
    @Named("liveWs") private val okHttpClient: OkHttpClient,
    private val credentialsStore: SecureBrokerCredentialsStore,
    private val appScope: CoroutineScope
) : PriceFeed {

    private val _ticks = MutableSharedFlow<PriceTick>(extraBufferCapacity = 64)
    override val ticks: Flow<PriceTick> = _ticks.asSharedFlow()

    private val _connectionState = MutableStateFlow<FeedConnectionState>(
        FeedConnectionState.Disconnected(FeedSourceType.SIMULATED, "Kite stub idle")
    )
    override val connectionState: StateFlow<FeedConnectionState> = _connectionState.asStateFlow()

    private val _liveStatus = MutableStateFlow(LiveConnectionStatus.DISCONNECTED)
    val liveStatus: StateFlow<LiveConnectionStatus> = _liveStatus.asStateFlow()

    private val _statusDetail = MutableStateFlow("Enter Kite API Key & Access Token")
    val statusDetail: StateFlow<String> = _statusDetail.asStateFlow()

    private val universe = ConcurrentHashMap<String, Instrument>()
    private var streamJob: Job? = null

    override fun setUniverse(instruments: List<Instrument>) {
        instruments.forEach { universe[it.symbol] = it }
    }

    override fun start() {
        if (streamJob?.isActive == true) return
        val creds = credentialsStore.getCurrent()
        if (creds.kiteApiKey.isBlank() || creds.kiteAccessToken.isBlank()) {
            _liveStatus.value = LiveConnectionStatus.ERROR
            _statusDetail.value = "Missing Kite API Key or Access Token"
            _connectionState.value = FeedConnectionState.Disconnected(
                FeedSourceType.SIMULATED,
                _statusDetail.value
            )
            return
        }

        val url = "wss://ws.kite.trade?api_key=${creds.kiteApiKey}&access_token=${creds.kiteAccessToken}"
        streamJob = appScope.launch(Dispatchers.Default) {
            reconnectingWebSocketFlow(
                client = okHttpClient,
                requestProvider = { Request.Builder().url(url).build() },
                onStatusChange = { status, detail ->
                    _liveStatus.value = status
                    _statusDetail.value = detail
                    _connectionState.value = when (status) {
                        LiveConnectionStatus.CONNECTED -> FeedConnectionState.Connected(
                            FeedSourceType.SIMULATED,
                            "KiteTicker Live (Stub)"
                        )
                        LiveConnectionStatus.CONNECTING, LiveConnectionStatus.RECONNECTING ->
                            FeedConnectionState.Connecting(FeedSourceType.SIMULATED)
                        LiveConnectionStatus.DISCONNECTED, LiveConnectionStatus.ERROR ->
                            FeedConnectionState.Disconnected(FeedSourceType.SIMULATED, detail)
                    }
                }
            )
                .mapNotNull { event ->
                    when (event) {
                        is WebSocketEvent.Open -> {
                            val sub = JSONObject().apply {
                                put("a", "subscribe")
                                put("v", JSONArray(listOf(738561, 2953217))) // RELIANCE, TCS instrument tokens
                            }
                            event.webSocket.send(sub.toString())
                            null
                        }
                        else -> null // Binary KiteTicker packet parser stubbed
                    }
                }
                .conflate()
                .throttlePerInstrument(500L)
                .flowOn(Dispatchers.Default)
                .collect { _ticks.emit(it) }
        }
    }

    override fun stop() {
        streamJob?.cancel()
        streamJob = null
        _liveStatus.value = LiveConnectionStatus.DISCONNECTED
        _statusDetail.value = "Disconnected"
        _connectionState.value = FeedConnectionState.Disconnected(
            FeedSourceType.SIMULATED,
            "Kite stub stopped"
        )
    }

    override suspend fun nudgePrice(symbol: String, percentDelta: BigDecimal) {
        val inst = universe[symbol] ?: return
        val factor = BigDecimal.ONE.add(percentDelta.divide(BigDecimal("100"), 6, RoundingMode.HALF_UP))
        val nudged = RiskMath.roundToTick(inst.ltp.multiply(factor).max(inst.tickSize), inst.tickSize)
        universe[symbol] = inst.copy(ltp = nudged)
        _ticks.emit(PriceTick(symbol, nudged, System.currentTimeMillis()))
    }
}
