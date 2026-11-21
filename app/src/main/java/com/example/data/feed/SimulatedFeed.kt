package com.example.data.feed

import com.example.domain.engine.RiskMath
import com.example.domain.model.FeedConnectionState
import com.example.domain.model.FeedSourceType
import com.example.domain.model.Instrument
import com.example.domain.model.InstrumentType
import com.example.domain.model.MarketSegment
import com.example.domain.model.PriceTick
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

@Singleton
class SimulatedFeed @Inject constructor(
    private val appScope: CoroutineScope
) : PriceFeed {

    private val _ticks = MutableSharedFlow<PriceTick>(extraBufferCapacity = 128)
    override val ticks: Flow<PriceTick> = _ticks.asSharedFlow()

    private val _connectionState = MutableStateFlow<FeedConnectionState>(
        FeedConnectionState.Disconnected(FeedSourceType.SIMULATED, "Initializing feed")
    )
    override val connectionState: StateFlow<FeedConnectionState> = _connectionState.asStateFlow()

    private val universe = ConcurrentHashMap<String, Instrument>()
    private val currentPrices = ConcurrentHashMap<String, BigDecimal>()
    private val cumulativeVolume = ConcurrentHashMap<String, Long>()
    private var tickerJob: Job? = null

    @Volatile
    var tickIntervalMs: Long = 1000L

    override fun setUniverse(instruments: List<Instrument>) {
        instruments.forEach { inst ->
            universe[inst.symbol] = inst
            currentPrices.putIfAbsent(inst.symbol, inst.ltp)
        }
    }

    override fun start() {
        if (tickerJob?.isActive == true) return
        _connectionState.value = FeedConnectionState.Connected(
            source = FeedSourceType.SIMULATED,
            detail = "1s GBM Simulator Active"
        )
        tickerJob = appScope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                universe.values.forEach { inst ->
                    val current = currentPrices[inst.symbol] ?: inst.ltp
                    val volatility = when {
                        inst.type == InstrumentType.OPTIONS -> 0.0045
                        inst.segment == MarketSegment.CRYPTO -> 0.0028
                        inst.segment == MarketSegment.MCX -> 0.0018
                        else -> 0.0014
                    }
                    // Slight mean reversion toward previousClose mixed with Gaussian shock
                    val shock = Random.nextDouble(-1.0, 1.0) * volatility
                    val nextRaw = current.toDouble() * (1.0 + shock)
                    val rounded = RiskMath.roundToTick(
                        BigDecimal.valueOf(nextRaw).max(inst.tickSize),
                        inst.tickSize
                    )
                    currentPrices[inst.symbol] = rounded
                    // Synthetic participation so volume-based tools (VWAP, Volume MA, OBV, Elder Force,
                    // delta, volume profile) have something meaningful to work with on the simulator.
                    val perSecond = (inst.volume.coerceAtLeast(22_500L).toDouble() / 22_500.0)
                    val activity = 0.4 + Random.nextDouble() * 1.2 + kotlin.math.abs(shock) / volatility
                    val qty = (perSecond * (tickIntervalMs / 1000.0) * activity).coerceAtLeast(1.0).toLong()
                    val cumulative = (cumulativeVolume[inst.symbol] ?: inst.volume.coerceAtLeast(0L)) + qty
                    cumulativeVolume[inst.symbol] = cumulative
                    val buyerAggressor = if (Random.nextDouble() < 0.8) shock >= 0.0 else shock < 0.0
                    _ticks.tryEmit(
                        PriceTick(
                            symbol = inst.symbol,
                            ltp = rounded,
                            timestampMillis = now,
                            volume = cumulative,
                            tradeQuantity = qty.toDouble(),
                            buyerInitiated = buyerAggressor
                        )
                    )
                }
                delay(tickIntervalMs.coerceIn(300L, 5000L))
            }
        }
    }

    override fun stop() {
        tickerJob?.cancel()
        tickerJob = null
        _connectionState.value = FeedConnectionState.Disconnected(
            source = FeedSourceType.SIMULATED,
            reason = "Feed paused"
        )
    }

    override suspend fun nudgePrice(symbol: String, percentDelta: BigDecimal) {
        val inst = universe[symbol] ?: return
        val current = currentPrices[symbol] ?: inst.ltp
        val multiplier = BigDecimal.ONE.add(
            percentDelta.divide(BigDecimal("100"), 6, java.math.RoundingMode.HALF_UP)
        )
        val nudged = RiskMath.roundToTick(current.multiply(multiplier).max(inst.tickSize), inst.tickSize)
        currentPrices[symbol] = nudged
        _ticks.emit(PriceTick(symbol, nudged, System.currentTimeMillis()))
    }
}
