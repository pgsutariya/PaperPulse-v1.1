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
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Live Crypto price feed using Binance combined public WebSocket stream (no authentication).
 * Rotates between Binance's global public market-data stream (data-stream.binance.vision)
 * and standard stream.binance.com endpoints to avoid regional HTTP 451 blocks.
 */
@Singleton
class BinancePriceFeed @Inject constructor(
    @Named("liveWs") private val okHttpClient: OkHttpClient,
    private val appScope: CoroutineScope
) : PriceFeed {

    private val _ticks = MutableSharedFlow<PriceTick>(extraBufferCapacity = 128)
    override val ticks: Flow<PriceTick> = _ticks.asSharedFlow()

    private val _connectionState = MutableStateFlow<FeedConnectionState>(
        FeedConnectionState.Disconnected(FeedSourceType.SIMULATED, "Binance feed idle")
    )
    override val connectionState: StateFlow<FeedConnectionState> = _connectionState.asStateFlow()

    private val _liveStatus = MutableStateFlow(LiveConnectionStatus.DISCONNECTED)
    val liveStatus: StateFlow<LiveConnectionStatus> = _liveStatus.asStateFlow()

    private val _statusDetail = MutableStateFlow("Disconnected")
    val statusDetail: StateFlow<String> = _statusDetail.asStateFlow()

    private val universe = ConcurrentHashMap<String, Instrument>()
    private val endpointCursor = AtomicInteger(0)
    private var streamJob: Job? = null

    override fun setUniverse(instruments: List<Instrument>) {
        instruments.forEach { universe[it.symbol] = it }
    }

    override fun start() {
        if (streamJob?.isActive == true) return
        endpointCursor.set(0)

        streamJob = appScope.launch(Dispatchers.Default) {
            reconnectingWebSocketFlow(
                client = okHttpClient,
                backoff = BackoffStrategy(initialDelayMs = 1_000L, maxDelayMs = 30_000L),
                requestProvider = {
                    val streams = buildStreamPath(universe.keys)
                    val idx = endpointCursor.getAndIncrement()
                    val baseUrl = STREAM_ENDPOINTS[idx % STREAM_ENDPOINTS.size]
                    Request.Builder().url("$baseUrl/stream?streams=$streams").build()
                },
                onStatusChange = { status, detail ->
                    _liveStatus.value = status
                    _statusDetail.value = detail
                    _connectionState.value = when (status) {
                        LiveConnectionStatus.CONNECTED -> FeedConnectionState.Connected(
                            FeedSourceType.SIMULATED,
                            "Binance Live WS"
                        )
                        LiveConnectionStatus.CONNECTING, LiveConnectionStatus.RECONNECTING ->
                            FeedConnectionState.Connecting(FeedSourceType.SIMULATED)
                        LiveConnectionStatus.DISCONNECTED, LiveConnectionStatus.ERROR ->
                            FeedConnectionState.Disconnected(FeedSourceType.SIMULATED, detail)
                    }
                }
            )
                .mapNotNull { event ->
                    if (event is WebSocketEvent.TextMessage) {
                        parseBinanceTradeJson(event.text, universe)
                    } else {
                        null
                    }
                }
                .conflate()
                .throttlePerInstrument(500L)
                .flowOn(Dispatchers.Default)
                .collect { tick ->
                    emitSpotAndDerived(tick)
                }
        }
    }

    override fun stop() {
        streamJob?.cancel()
        streamJob = null
        _liveStatus.value = LiveConnectionStatus.DISCONNECTED
        _statusDetail.value = "Disconnected"
        _connectionState.value = FeedConnectionState.Disconnected(
            FeedSourceType.SIMULATED,
            "Binance feed stopped"
        )
    }

    override suspend fun nudgePrice(symbol: String, percentDelta: BigDecimal) {
        val inst = universe[symbol] ?: return
        val factor = BigDecimal.ONE.add(percentDelta.divide(BigDecimal("100"), 6, RoundingMode.HALF_UP))
        val nudged = RiskMath.roundToTick(inst.ltp.multiply(factor).max(inst.tickSize), inst.tickSize)
        universe[symbol] = inst.copy(ltp = nudged)
        _ticks.emit(PriceTick(symbol, nudged, System.currentTimeMillis()))
    }

    private suspend fun emitSpotAndDerived(tick: PriceTick) {
        universe[tick.symbol]?.let { inst ->
            universe[tick.symbol] = inst.copy(ltp = tick.ltp)
        }
        _ticks.emit(tick)

        // Keep seeded Crypto Perpetual & Option synced with live BTCUSDT
        if (tick.symbol == "BTCUSDT") {
            universe["BTCUSDT-PERP"]?.let { perp ->
                val perpLtp = RiskMath.roundToTick(tick.ltp.multiply(BigDecimal("1.0008")), perp.tickSize)
                universe["BTCUSDT-PERP"] = perp.copy(ltp = perpLtp)
                _ticks.emit(PriceTick("BTCUSDT-PERP", perpLtp, tick.timestampMillis))
            }
            universe["BTC-26NOV-95000C"]?.let { opt ->
                val strike = opt.strikePrice ?: BigDecimal("95000")
                val intrinsic = tick.ltp.subtract(strike).max(BigDecimal.ZERO)
                val timeVal = tick.ltp.multiply(BigDecimal("0.016"))
                val optLtp = RiskMath.roundToTick(intrinsic.add(timeVal).max(opt.tickSize), opt.tickSize)
                universe["BTC-26NOV-95000C"] = opt.copy(ltp = optLtp)
                _ticks.emit(PriceTick("BTC-26NOV-95000C", optLtp, tick.timestampMillis))
            }
        }
    }

    companion object {
        private val SUPPORTED_SPOT = listOf("btcusdt", "ethusdt", "solusdt")

        internal val STREAM_ENDPOINTS = listOf(
            "wss://data-stream.binance.vision:9443",
            "wss://data-stream.binance.vision:443",
            "wss://stream.binance.com:9443",
            "wss://stream.binance.com:443"
        )

        fun buildStreamPath(symbols: Collection<String>): String {
            val lower = symbols.map { it.lowercase() }.filter { it in SUPPORTED_SPOT }
            val active = lower.ifEmpty { SUPPORTED_SPOT }
            return active.joinToString("/") { "$it@trade" }
        }

        /**
         * Parses both Binance combined-stream payloads:
         *   {"stream":"btcusdt@trade","data":{"e":"trade","E":1700000000,"s":"BTCUSDT","p":"91420.50"}}
         * and raw single-stream trade payloads:
         *   {"e":"trade","E":1700000000,"s":"BTCUSDT","p":"91420.50"}
         */
        fun parseBinanceTradeJson(
            raw: String,
            universe: Map<String, Instrument>
        ): PriceTick? {
            return runCatching {
                val root = JSONObject(raw)
                val data = root.optJSONObject("data") ?: root
                val rawSymbol = data.optString("s").uppercase().takeIf { it.isNotBlank() } ?: return null
                val priceStr = data.optString("p").takeIf { it.isNotBlank() } ?: return null
                val inst = universe[rawSymbol] ?: return null
                val rawPrice = BigDecimal(priceStr)
                if (rawPrice <= BigDecimal.ZERO) return null
                val rounded = RiskMath.roundToTick(rawPrice, inst.tickSize)
                val eventTs = data.optLong("E", 0L).takeIf { it > 0L } ?: System.currentTimeMillis()
                val qty = data.optString("q").toDoubleOrNull()?.takeIf { it > 0.0 }
                // "m" = buyer is the market maker → the SELLER was the aggressor.
                val buyerIsMaker = if (data.has("m")) data.optBoolean("m") else null
                PriceTick(
                    symbol = inst.symbol,
                    ltp = rounded,
                    timestampMillis = eventTs,
                    tradeQuantity = qty,
                    buyerInitiated = buyerIsMaker?.let { !it }
                )
            }.getOrNull()
        }
    }
}
