package com.example.data.feed

import com.example.domain.engine.RiskMath
import com.example.domain.model.FeedConnectionState
import com.example.domain.model.FeedSourceType
import com.example.domain.model.Instrument
import com.example.domain.model.PriceTick
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Optional read-only WebSocket adapter stub for Angel One SmartAPI or Upstox Market Data Feed.
 * Prices only — never places real broker orders.
 */
@Singleton
class BrokerFeed @Inject constructor(
    private val okHttpClient: OkHttpClient
) : PriceFeed {

    private val _ticks = MutableSharedFlow<PriceTick>(extraBufferCapacity = 64)
    override val ticks: Flow<PriceTick> = _ticks.asSharedFlow()

    private val _connectionState = MutableStateFlow<FeedConnectionState>(
        FeedConnectionState.Disconnected(
            source = FeedSourceType.BROKER_ANGEL_ONE,
            reason = "Configure Feed Token in Feed Settings"
        )
    )
    override val connectionState: StateFlow<FeedConnectionState> = _connectionState.asStateFlow()

    private val universe = ConcurrentHashMap<String, Instrument>()
    private var webSocket: WebSocket? = null

    var sourceType: FeedSourceType = FeedSourceType.BROKER_ANGEL_ONE
    var wsUrl: String = ""
    var clientId: String = ""
    var feedToken: String = ""

    override fun setUniverse(instruments: List<Instrument>) {
        instruments.forEach { universe[it.symbol] = it }
    }

    override fun start() {
        stop()
        if (wsUrl.isBlank() || feedToken.isBlank()) {
            _connectionState.value = FeedConnectionState.Disconnected(
                source = sourceType,
                reason = "Auth stub: enter WebSocket URL & Feed Token (no credentials stored)"
            )
            return
        }

        _connectionState.value = FeedConnectionState.Connecting(sourceType)

        val requestBuilder = Request.Builder().url(wsUrl.trim())
        when (sourceType) {
            FeedSourceType.BROKER_ANGEL_ONE -> {
                requestBuilder
                    .addHeader("Authorization", "Bearer $feedToken")
                    .addHeader("x-client-code", clientId)
                    .addHeader("x-feed-token", feedToken)
            }
            FeedSourceType.BROKER_UPSTOX -> {
                requestBuilder.addHeader("Authorization", "Bearer $feedToken")
            }
            FeedSourceType.SIMULATED -> Unit
        }

        webSocket = okHttpClient.newWebSocket(
            requestBuilder.build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    _connectionState.value = FeedConnectionState.Connected(
                        source = sourceType,
                        detail = "WS Prices Only (Paper Mode)"
                    )
                    // Stub subscription payload for SmartAPI / Upstox V2 market feed
                    val subPayload = JSONObject().apply {
                        put("action", "subscribe")
                        put("mode", "LTP")
                        put("symbols", universe.keys.toList())
                    }
                    webSocket.send(subPayload.toString())
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    parseJsonTick(text)?.let { _ticks.tryEmit(it) }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    _connectionState.value = FeedConnectionState.Disconnected(
                        source = sourceType,
                        reason = t.message ?: "WebSocket connection failed"
                    )
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    _connectionState.value = FeedConnectionState.Disconnected(
                        source = sourceType,
                        reason = reason.ifBlank { "Closed ($code)" }
                    )
                }
            }
        )
    }

    override fun stop() {
        webSocket?.close(1000, "Stopped by user")
        webSocket = null
        _connectionState.value = FeedConnectionState.Disconnected(
            source = sourceType,
            reason = "Broker feed disconnected"
        )
    }

    override suspend fun nudgePrice(symbol: String, percentDelta: BigDecimal) {
        val inst = universe[symbol] ?: return
        val factor = BigDecimal.ONE.add(
            percentDelta.divide(BigDecimal("100"), 6, java.math.RoundingMode.HALF_UP)
        )
        val nudged = RiskMath.roundToTick(inst.ltp.multiply(factor).max(inst.tickSize), inst.tickSize)
        _ticks.emit(PriceTick(symbol, nudged, System.currentTimeMillis()))
    }

    private fun parseJsonTick(raw: String): PriceTick? {
        return runCatching {
            val json = JSONObject(raw)
            val symbol = json.optString("symbol").takeIf { it.isNotBlank() } ?: return null
            val ltpStr = json.optString("ltp").takeIf { it.isNotBlank() } ?: return null
            val inst = universe[symbol] ?: return null
            val ltp = RiskMath.roundToTick(BigDecimal(ltpStr), inst.tickSize)
            PriceTick(symbol, ltp, System.currentTimeMillis())
        }.getOrNull()
    }
}
