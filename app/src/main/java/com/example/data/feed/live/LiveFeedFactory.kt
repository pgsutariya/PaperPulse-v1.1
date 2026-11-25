package com.example.data.feed.live

import com.example.data.feed.PriceFeed
import com.example.data.feed.PublicMarketQuotePoller
import com.example.data.feed.SimulatedFeed
import com.example.data.repository.TradingPreferencesRepository
import com.example.domain.model.FeedConnectionState
import com.example.domain.model.Instrument
import com.example.domain.model.PriceTick
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

data class FeedInstanceBundle(
    val feed: PriceFeed,
    val liveStatusSource: StateFlow<LiveConnectionStatus>?,
    val statusDetailSource: StateFlow<String>?
)

interface LiveFeedFactory {
    fun create(feedType: FeedType, simulatedTickIntervalMs: Long): FeedInstanceBundle
    fun getAngelOneFeed(): AngelOnePriceFeed? = null
}

/**
 * Reference-counted holder so multi-segment broker feeds (e.g. Angel One covering NSE, BSE, MCX)
 * establish ONE authenticated login & WebSocket connection rather than firing 3 simultaneous logins
 * that trigger broker rate-limits (1 req/sec) and session collisions.
 */
internal class SharedFeedSlot(
    val delegate: PriceFeed,
    val liveStatusSource: StateFlow<LiveConnectionStatus>,
    val statusDetailSource: StateFlow<String>
) {
    private val activeRefs = AtomicInteger(0)

    fun createHandle(): PriceFeed = Handle()

    private inner class Handle : PriceFeed {
        private val started = AtomicBoolean(false)

        override val ticks: Flow<PriceTick> get() = delegate.ticks
        override val connectionState: StateFlow<FeedConnectionState> get() = delegate.connectionState

        override fun start() {
            if (started.compareAndSet(false, true)) {
                val count = activeRefs.incrementAndGet()
                if (count == 1) {
                    delegate.start()
                }
            }
        }

        override fun stop() {
            if (started.compareAndSet(true, false)) {
                val remaining = activeRefs.decrementAndGet().coerceAtLeast(0)
                if (remaining == 0) {
                    delegate.stop()
                }
            }
        }

        override fun setUniverse(instruments: List<Instrument>) {
            delegate.setUniverse(instruments)
        }

        override suspend fun nudgePrice(symbol: String, percentDelta: BigDecimal) {
            delegate.nudgePrice(symbol, percentDelta)
        }
    }
}

@Singleton
class DefaultLiveFeedFactory private constructor(
    private val okHttpClient: OkHttpClient,
    private val credentialsStore: SecureBrokerCredentialsStore,
    private val appScope: CoroutineScope,
    private val singletonAngelOneFeed: AngelOnePriceFeed,
    private val publicQuotePoller: PublicMarketQuotePoller?,
    private val prefsRepository: TradingPreferencesRepository?,
    @Suppress("UNUSED_PARAMETER") internalTag: Boolean
) : LiveFeedFactory {

    @Inject
    constructor(
        @Named("liveWs") okHttpClient: OkHttpClient,
        credentialsStore: SecureBrokerCredentialsStore,
        appScope: CoroutineScope,
        singletonAngelOneFeed: AngelOnePriceFeed,
        publicQuotePoller: PublicMarketQuotePoller,
        prefsRepository: TradingPreferencesRepository
    ) : this(
        okHttpClient = okHttpClient,
        credentialsStore = credentialsStore,
        appScope = appScope,
        singletonAngelOneFeed = singletonAngelOneFeed,
        publicQuotePoller = publicQuotePoller,
        prefsRepository = prefsRepository,
        internalTag = true
    )

    constructor(
        okHttpClient: OkHttpClient,
        credentialsStore: SecureBrokerCredentialsStore,
        appScope: CoroutineScope
    ) : this(
        okHttpClient = okHttpClient,
        credentialsStore = credentialsStore,
        appScope = appScope,
        singletonAngelOneFeed = AngelOnePriceFeed(okHttpClient, credentialsStore, appScope),
        publicQuotePoller = null,
        prefsRepository = null,
        internalTag = false
    )

    private val sharedSlots = ConcurrentHashMap<FeedType, SharedFeedSlot>()

    override fun getAngelOneFeed(): AngelOnePriceFeed = singletonAngelOneFeed

    override fun create(feedType: FeedType, simulatedTickIntervalMs: Long): FeedInstanceBundle {
        return when (feedType) {
            FeedType.SIMULATED -> {
                val poller = publicQuotePoller
                val prefs = prefsRepository
                if (poller != null && prefs != null) {
                    val slot = sharedSlots.computeIfAbsent(FeedType.SIMULATED) {
                        val liveAnchoredSim = SimulatedFeed(appScope, poller, prefs).apply {
                            tickIntervalMs = simulatedTickIntervalMs
                        }
                        SharedFeedSlot(
                            delegate = liveAnchoredSim,
                            liveStatusSource = kotlinx.coroutines.flow.MutableStateFlow(LiveConnectionStatus.CONNECTED),
                            statusDetailSource = kotlinx.coroutines.flow.MutableStateFlow("Live NSE/BSE/MCX Public + Sim")
                        )
                    }
                    (slot.delegate as? SimulatedFeed)?.tickIntervalMs = simulatedTickIntervalMs
                    FeedInstanceBundle(slot.createHandle(), null, null)
                } else {
                    val sim = SimulatedFeed(appScope).apply {
                        tickIntervalMs = simulatedTickIntervalMs
                    }
                    FeedInstanceBundle(sim, null, null)
                }
            }
            FeedType.BINANCE_LIVE -> {
                val slot = sharedSlots.computeIfAbsent(FeedType.BINANCE_LIVE) {
                    val binance = BinancePriceFeed(okHttpClient, appScope)
                    SharedFeedSlot(binance, binance.liveStatus, binance.statusDetail)
                }
                FeedInstanceBundle(slot.createHandle(), slot.liveStatusSource, slot.statusDetailSource)
            }
            FeedType.ANGEL_ONE_LIVE -> {
                val slot = sharedSlots.computeIfAbsent(FeedType.ANGEL_ONE_LIVE) {
                    SharedFeedSlot(
                        singletonAngelOneFeed,
                        singletonAngelOneFeed.liveStatus,
                        singletonAngelOneFeed.statusDetail
                    )
                }
                FeedInstanceBundle(slot.createHandle(), slot.liveStatusSource, slot.statusDetailSource)
            }
            FeedType.UPSTOX_LIVE -> {
                val slot = sharedSlots.computeIfAbsent(FeedType.UPSTOX_LIVE) {
                    val upstox = UpstoxPriceFeed(okHttpClient, credentialsStore, appScope)
                    SharedFeedSlot(upstox, upstox.liveStatus, upstox.statusDetail)
                }
                FeedInstanceBundle(slot.createHandle(), slot.liveStatusSource, slot.statusDetailSource)
            }
            FeedType.KITE_STUB -> {
                val slot = sharedSlots.computeIfAbsent(FeedType.KITE_STUB) {
                    val kite = KitePriceFeed(okHttpClient, credentialsStore, appScope)
                    SharedFeedSlot(kite, kite.liveStatus, kite.statusDetail)
                }
                FeedInstanceBundle(slot.createHandle(), slot.liveStatusSource, slot.statusDetailSource)
            }
        }
    }
}
