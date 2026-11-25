package com.example.data.feed

import com.example.data.repository.TradingPreferencesRepository
import com.example.domain.engine.MarketClock
import com.example.domain.engine.RiskMath
import com.example.domain.model.FeedConnectionState
import com.example.domain.model.FeedSourceType
import com.example.domain.model.Instrument
import com.example.domain.model.InstrumentType
import com.example.domain.model.MarketSegment
import com.example.domain.model.PriceTick
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
class SimulatedFeed private constructor(
    private val appScope: CoroutineScope,
    private val publicQuotePoller: PublicMarketQuotePoller?,
    private val prefsRepository: TradingPreferencesRepository?,
    @Suppress("UNUSED_PARAMETER") internalTag: Boolean
) : PriceFeed {

    @Inject
    constructor(
        appScope: CoroutineScope,
        publicQuotePoller: PublicMarketQuotePoller,
        prefsRepository: TradingPreferencesRepository
    ) : this(appScope, publicQuotePoller, prefsRepository, true)

    constructor(appScope: CoroutineScope) : this(appScope, null, null, false)

    private val _ticks = MutableSharedFlow<PriceTick>(extraBufferCapacity = 128)
    override val ticks: Flow<PriceTick> = _ticks.asSharedFlow()

    private val _connectionState = MutableStateFlow<FeedConnectionState>(
        FeedConnectionState.Disconnected(FeedSourceType.SIMULATED, "Initializing feed")
    )
    override val connectionState: StateFlow<FeedConnectionState> = _connectionState.asStateFlow()

    private val universe = ConcurrentHashMap<String, Instrument>()
    private val currentPrices = ConcurrentHashMap<String, BigDecimal>()
    private val publicAnchoredPrices = ConcurrentHashMap<String, BigDecimal>()
    private val cumulativeVolume = ConcurrentHashMap<String, Long>()
    private var tickerJob: Job? = null
    private var publicPollerJob: Job? = null

    @Volatile
    var tickIntervalMs: Long = 1000L

    @Volatile
    var publicLiveQuoteCount: Int = 0
        private set

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
            detail = if (publicQuotePoller != null) "Public Market API + Simulator Active" else "1s GBM Simulator Active"
        )

        // Background poller for free public NSE / BSE / MCX / Crypto live market quotes
        if (publicQuotePoller != null && publicPollerJob?.isActive != true) {
            publicPollerJob = appScope.launch(Dispatchers.IO) {
                while (isActive) {
                    val usePublic = runCatching { prefsRepository?.getConfig()?.useFreePublicMarketFeed ?: true }
                        .getOrDefault(true)
                    if (usePublic && universe.isNotEmpty()) {
                        val quotes = runCatching {
                            publicQuotePoller.fetchQuotes(universe.values.toList())
                        }.getOrDefault(emptyMap())
                        if (quotes.isNotEmpty()) {
                            publicLiveQuoteCount = quotes.size
                            val now = System.currentTimeMillis()
                            quotes.values.forEach { q ->
                                currentPrices[q.symbol] = q.ltp
                                publicAnchoredPrices[q.symbol] = q.ltp
                                _ticks.tryEmit(
                                    PriceTick(
                                        symbol = q.symbol,
                                        ltp = q.ltp,
                                        timestampMillis = now,
                                        previousClose = q.previousClose,
                                        dayHigh = q.dayHigh,
                                        dayLow = q.dayLow,
                                        volume = q.volume
                                    )
                                )
                            }
                            _connectionState.value = FeedConnectionState.Connected(
                                source = FeedSourceType.SIMULATED,
                                detail = "Live NSE/BSE/MCX Public Data (${quotes.size} symbols)"
                            )
                        }
                        delay(8_000L)
                    } else {
                        // Retry quickly while waiting for universe to finish seeding
                        delay(500L)
                    }
                }
            }
        }

        tickerJob = appScope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                universe.values.forEach { inst ->
                    val current = currentPrices[inst.symbol] ?: inst.ltp
                    val isSessionOpen = MarketClock.isMarketSessionOpen(inst.segment, inst.type, now)
                    val anchored = publicAnchoredPrices[inst.symbol]
                    // Keep smoother, lower-amplitude ticks when anchored to real public quotes or after India close
                    val baseVol = when {
                        inst.type == InstrumentType.OPTIONS -> 0.0045
                        inst.segment == MarketSegment.CRYPTO -> 0.0028
                        inst.segment == MarketSegment.MCX -> 0.0018
                        else -> 0.0014
                    }
                    val volatility = when {
                        anchored != null && !isSessionOpen -> baseVol * 0.04
                        anchored != null -> baseVol * 0.14
                        else -> baseVol
                    }
                    val shock = Random.nextDouble(-1.0, 1.0) * volatility
                    // Strongly mean-revert toward real live NSE/BSE/MCX anchor so simulated prices stay faithful to live market
                    val meanRevert = if (anchored != null && anchored > BigDecimal.ZERO) {
                        ((anchored.toDouble() - current.toDouble()) / anchored.toDouble()) * 0.45
                    } else 0.0
                    val nextRaw = current.toDouble() * (1.0 + shock + meanRevert)
                    val rounded = RiskMath.roundToTick(
                        BigDecimal.valueOf(nextRaw).max(inst.tickSize),
                        inst.tickSize
                    )
                    currentPrices[inst.symbol] = rounded
                    val perSecond = (inst.volume.coerceAtLeast(22_500L).toDouble() / 22_500.0)
                    val activity = 0.4 + Random.nextDouble() * 1.2 + kotlin.math.abs(shock) / baseVol
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
        publicPollerJob?.cancel()
        publicPollerJob = null
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
