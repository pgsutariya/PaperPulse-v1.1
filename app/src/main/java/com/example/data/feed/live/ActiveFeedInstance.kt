package com.example.data.feed.live

import com.example.data.feed.PriceFeed
import com.example.domain.model.Instrument
import com.example.domain.model.MarketSegment
import com.example.domain.model.PriceTick
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.math.BigDecimal

/**
 * Segment-scoped active feed wrapper.
 * Owns its own [PriceFeed] instance (and therefore its own WebSocket connection,
 * [BackoffStrategy] reconnection state, and 500ms per-instrument throttle timer),
 * forwarding matching segment ticks into the shared downstream orchestrator callback.
 */
class ActiveFeedInstance(
    val segment: MarketSegment,
    val feedType: FeedType,
    private val feed: PriceFeed,
    private val liveStatusSource: StateFlow<LiveConnectionStatus>?,
    private val statusDetailSource: StateFlow<String>?,
    private val scope: CoroutineScope,
    private val symbolToSegment: (String) -> MarketSegment?,
    private val onTick: suspend (PriceTick, fromLiveFeed: Boolean) -> Unit,
    private val onStatusUpdated: (MarketSegment, LiveConnectionStatus, String) -> Unit
) {
    private val _connectionStatus = MutableStateFlow(
        if (feedType == FeedType.SIMULATED) LiveConnectionStatus.CONNECTED
        else LiveConnectionStatus.DISCONNECTED
    )
    val connectionStatus: StateFlow<LiveConnectionStatus> = _connectionStatus.asStateFlow()

    private val _statusDetail = MutableStateFlow(
        if (feedType == FeedType.SIMULATED) "Simulated 1s feed active" else "Idle"
    )
    val statusDetail: StateFlow<String> = _statusDetail.asStateFlow()

    private var ticksJob: Job? = null
    private var statusJob: Job? = null
    private var started = false

    fun setUniverse(instruments: List<Instrument>) {
        feed.setUniverse(instruments)
    }

    fun start() {
        if (started) return
        started = true

        ticksJob = feed.ticks
            .filter { tick -> symbolToSegment(tick.symbol) == segment }
            .throttlePerInstrument(500L)
            .onEach { tick ->
                onTick(tick, feedType != FeedType.SIMULATED)
            }
            .launchIn(CoroutineScope(scope.coroutineContext + Dispatchers.Default))

        if (liveStatusSource != null && statusDetailSource != null) {
            statusJob = kotlinx.coroutines.flow.combine(
                liveStatusSource,
                statusDetailSource
            ) { st, detail -> st to detail }
                .onEach { (st, detail) ->
                    _connectionStatus.value = st
                    _statusDetail.value = detail
                    onStatusUpdated(segment, st, detail)
                }
                .launchIn(CoroutineScope(scope.coroutineContext + Dispatchers.Default))
        } else {
            val st = if (feedType == FeedType.SIMULATED) LiveConnectionStatus.CONNECTED else LiveConnectionStatus.DISCONNECTED
            val detail = if (feedType == FeedType.SIMULATED) "Simulated 1s feed active" else "Idle"
            _connectionStatus.value = st
            _statusDetail.value = detail
            onStatusUpdated(segment, st, detail)
        }

        feed.start()
    }

    fun stop() {
        started = false
        ticksJob?.cancel()
        ticksJob = null
        statusJob?.cancel()
        statusJob = null
        feed.stop()
        _connectionStatus.value = LiveConnectionStatus.DISCONNECTED
        _statusDetail.value = "Stopped"
        onStatusUpdated(segment, LiveConnectionStatus.DISCONNECTED, "Stopped")
    }

    suspend fun nudgePrice(symbol: String, percentDelta: BigDecimal) {
        feed.nudgePrice(symbol, percentDelta)
    }
}
