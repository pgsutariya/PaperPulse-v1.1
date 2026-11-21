package com.example

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.engine.ExecutionEngine
import com.example.data.engine.TradingLedgerService
import com.example.data.feed.PriceFeed
import com.example.data.feed.SimulatedFeed
import com.example.data.feed.live.AngelOnePriceFeed
import com.example.data.feed.live.BackoffStrategy
import com.example.data.feed.live.BinancePriceFeed
import com.example.data.feed.live.FeedInstanceBundle
import com.example.data.feed.live.DefaultLiveFeedFactory
import com.example.data.feed.live.FeedType
import com.example.data.feed.live.LiveConnectionStatus
import com.example.data.feed.live.LiveFeedFactory
import com.example.data.feed.live.SecureBrokerCredentialsStore
import com.example.data.feed.live.SegmentBadgeMode
import com.example.data.feed.live.reconnectingWebSocketFlow
import com.example.data.feed.live.throttlePerInstrument
import com.example.data.local.PaperPulseDatabase
import com.example.data.local.SeedData
import com.example.data.local.toDomain
import com.example.data.repository.MarketDataRepositoryImpl
import com.example.data.repository.TradingPreferencesRepository
import com.example.data.repository.TradingRepository
import com.example.domain.model.FeedConnectionState
import com.example.domain.model.FeedSourceType
import com.example.domain.model.Instrument
import com.example.domain.model.MarketSegment
import com.example.domain.model.OrderPlacementRequest
import com.example.domain.model.OrderSide
import com.example.domain.model.OrderType
import com.example.domain.model.PriceTick
import com.example.domain.model.ProductType
import com.example.domain.model.RiskInputMode
import com.example.domain.model.TrailMode
import com.example.domain.model.ValidityType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class LivePriceFeedTest {

    private lateinit var context: Context
    private lateinit var db: PaperPulseDatabase
    private lateinit var testScope: CoroutineScope
    private lateinit var prefs: TradingPreferencesRepository
    private lateinit var credsStore: SecureBrokerCredentialsStore
    private lateinit var marketRepo: MarketDataRepositoryImpl
    private lateinit var executionEngine: ExecutionEngine
    private lateinit var tradingRepository: TradingRepository

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, PaperPulseDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        db.instrumentDao().upsertAll(SeedData.seedInstruments())
        db.accountDao().upsert(SeedData.defaultAccount())

        testScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        prefs = TradingPreferencesRepository(context)
        prefs.updateSlippageBps(0)
        credsStore = SecureBrokerCredentialsStore(context)
        val okHttp = OkHttpClient.Builder()
            .dns(object : okhttp3.Dns {
                override fun lookup(hostname: String): List<java.net.InetAddress> {
                    throw java.net.UnknownHostException("Offline test DNS: $hostname")
                }
            })
            .connectTimeout(300, TimeUnit.MILLISECONDS)
            .readTimeout(300, TimeUnit.MILLISECONDS)
            .build()

        val feedFactory = DefaultLiveFeedFactory(okHttp, credsStore, testScope)
        val simFeed = SimulatedFeed(testScope)

        marketRepo = MarketDataRepositoryImpl(
            instrumentDao = db.instrumentDao(),
            positionDao = db.positionDao(),
            accountDao = db.accountDao(),
            simulatedFeed = simFeed,
            feedFactory = feedFactory,
            credentialsStore = credsStore,
            appScope = testScope
        )
        marketRepo.ensureSeeded()

        val ledger = TradingLedgerService(
            db, db.instrumentDao(), db.orderDao(), db.positionDao(),
            db.ocoLinkDao(), db.tradeJournalDao(), db.accountDao()
        )
        executionEngine = ExecutionEngine(
            db, db.instrumentDao(), db.orderDao(), db.positionDao(),
            ledger, marketRepo, prefs, testScope
        )
        tradingRepository = TradingRepository(
            db, db.instrumentDao(), db.orderDao(), db.positionDao(),
            db.ocoLinkDao(), db.tradeJournalDao(), db.accountDao(),
            prefs, ledger, executionEngine
        )
    }

    @After
    fun tearDown() {
        testScope.cancel()
        db.close()
    }

    @Test
    fun `backoff strategy doubles exponentially and caps at 60 seconds`() {
        val backoff = BackoffStrategy(
            initialDelayMs = 1_000L,
            maxDelayMs = 60_000L,
            jitterFraction = 0.0
        )
        assertEquals(1_000L, backoff.nextDelayMs(0))
        assertEquals(2_000L, backoff.nextDelayMs(1))
        assertEquals(4_000L, backoff.nextDelayMs(2))
        assertEquals(8_000L, backoff.nextDelayMs(3))
        assertEquals(16_000L, backoff.nextDelayMs(4))
        assertEquals(32_000L, backoff.nextDelayMs(5))
        assertEquals(60_000L, backoff.nextDelayMs(6))
        assertEquals(60_000L, backoff.nextDelayMs(10))
    }

    @Test
    fun `reconnectingWebSocketFlow retries with backoff when OkHttp onFailure triggers`() = runBlocking {
        val closedPort = ServerSocket(0).use { it.localPort }
        val client = OkHttpClient.Builder()
            .connectTimeout(500, TimeUnit.MILLISECONDS)
            .build()
        val observedStatuses = mutableListOf<LiveConnectionStatus>()
        var attempts = 0

        val job = launch(Dispatchers.IO) {
            reconnectingWebSocketFlow(
                client = client,
                backoff = BackoffStrategy(initialDelayMs = 40L, maxDelayMs = 200L, jitterFraction = 0.0),
                requestProvider = {
                    attempts++
                    Request.Builder().url("ws://127.0.0.1:$closedPort/stream").build()
                },
                onStatusChange = { status, _ ->
                    synchronized(observedStatuses) { observedStatuses.add(status) }
                }
            ).collect {}
        }

        withTimeout(5_000L) {
            while (attempts < 2) {
                delay(25L)
            }
        }
        job.cancel()

        assertTrue("Expected at least 2 reconnect attempts, was $attempts", attempts >= 2)
        assertTrue(observedStatuses.contains(LiveConnectionStatus.CONNECTING))
        assertTrue(observedStatuses.contains(LiveConnectionStatus.ERROR))
        assertTrue(observedStatuses.contains(LiveConnectionStatus.RECONNECTING))
    }

    @Test
    fun `binance JSON parser maps BTCUSDT combined and raw trade payloads to PriceTick`() = runBlocking {
        val universe = db.instrumentDao().getAllOnce().map { it.toDomain() }.associateBy { it.symbol }

        val combinedPayload = """
            {"stream":"btcusdt@trade","data":{"e":"trade","E":1731900001000,"s":"BTCUSDT","p":"91425.37","q":"0.12"}}
        """.trimIndent()
        val combinedTick = BinancePriceFeed.parseBinanceTradeJson(combinedPayload, universe)
        assertNotNull(combinedTick)
        assertEquals("BTCUSDT", combinedTick!!.symbol)
        assertEquals(0, BigDecimal("91425.50").compareTo(combinedTick.ltp))
        assertEquals(1731900001000L, combinedTick.timestampMillis)

        val singlePayload = """{"e":"trade","E":1731900002000,"s":"ETHUSDT","p":"3180.24"}"""
        val singleTick = BinancePriceFeed.parseBinanceTradeJson(singlePayload, universe)
        assertNotNull(singleTick)
        assertEquals("ETHUSDT", singleTick!!.symbol)
        assertEquals(0, BigDecimal("3180.20").compareTo(singleTick.ltp))

        assertNull(BinancePriceFeed.parseBinanceTradeJson("""{"s":"DOGEUSDT","p":"0.40"}""", universe))
        assertNull(BinancePriceFeed.parseBinanceTradeJson("""not json""", universe))
    }

    @Test
    fun `angel one SmartAPI parser decodes 51-byte binary LTP packet and generates 6-digit TOTP`() = runBlocking {
        val universe = db.instrumentDao().getAllOnce().map { it.toDomain() }.associateBy { it.symbol }
        val buffer = java.nio.ByteBuffer.allocate(51).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buffer.put(1.toByte())
        buffer.put(1.toByte())
        val tokenBytes = "2885".toByteArray(Charsets.US_ASCII).copyOf(25)
        buffer.put(tokenBytes)
        buffer.putLong(1L)
        buffer.putLong(1731900005000L)
        buffer.putLong(129545L)

        val tick = AngelOnePriceFeed.parseSmartApiLtpPacket(buffer.array(), universe)
        assertNotNull(tick)
        assertEquals("RELIANCE", tick!!.symbol)
        assertEquals(0, BigDecimal("1295.45").compareTo(tick.ltp))

        val otp = AngelOnePriceFeed.generateTotp("JBSWY3DPEHPK3PXP", 59_000L)
        assertEquals(6, otp.length)
        assertTrue(otp.all { it.isDigit() })
    }

    @Test
    fun `throttlePerInstrument limits emission to at most one tick per 500ms per symbol`() = runBlocking {
        val input = flowOf(
            PriceTick("BTCUSDT", BigDecimal("91000.0"), 1_000L),
            PriceTick("BTCUSDT", BigDecimal("91010.0"), 1_200L),
            PriceTick("ETHUSDT", BigDecimal("3100.0"), 1_250L),
            PriceTick("BTCUSDT", BigDecimal("91020.0"), 1_550L)
        )
        val emitted = input.throttlePerInstrument(500L).toList()
        assertEquals(3, emitted.size)
        assertEquals(BigDecimal("91000.0"), emitted[0].ltp)
        assertEquals("ETHUSDT", emitted[1].symbol)
        assertEquals(BigDecimal("91020.0"), emitted[2].ltp)
    }

    @Test
    fun `enabling BINANCE_LIVE for CRYPTO and ANGEL_ONE_LIVE for NSE BSE MCX runs both concurrently into shared ticks flow`() = runBlocking {
        testScope.cancel()
        val binanceFake = FakeControlFeed(FeedType.BINANCE_LIVE)
        val angelFake = FakeControlFeed(FeedType.ANGEL_ONE_LIVE)
        val simFake = FakeControlFeed(FeedType.SIMULATED)
        val fakeFactory = object : LiveFeedFactory {
            override fun create(feedType: FeedType, simulatedTickIntervalMs: Long): FeedInstanceBundle = when (feedType) {
                FeedType.BINANCE_LIVE -> binanceFake.toBundle()
                FeedType.ANGEL_ONE_LIVE -> angelFake.toBundle()
                else -> simFake.toBundle()
            }
        }
        val localScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val multiRepo = MarketDataRepositoryImpl(
            instrumentDao = db.instrumentDao(),
            positionDao = db.positionDao(),
            accountDao = db.accountDao(),
            simulatedFeed = SimulatedFeed(localScope),
            feedFactory = fakeFactory,
            credentialsStore = SecureBrokerCredentialsStore(context),
            appScope = localScope
        )
        multiRepo.ensureSeeded()
        delay(50L)

        multiRepo.enableFeed(FeedType.ANGEL_ONE_LIVE)
        multiRepo.enableFeed(FeedType.BINANCE_LIVE)
        binanceFake.setStatus(LiveConnectionStatus.CONNECTED, "Binance WS open")
        angelFake.setStatus(LiveConnectionStatus.CONNECTED, "Angel SmartStream open")
        delay(30L)

        val collected = mutableListOf<PriceTick>()
        val collectJob = launch(Dispatchers.Unconfined) {
            multiRepo.ticks.collect { collected.add(it) }
        }

        delay(40L)
        val now = System.currentTimeMillis()
        angelFake.emitTick(PriceTick("RELIANCE", BigDecimal("1299.50"), now))
        binanceFake.emitTick(PriceTick("BTCUSDT", BigDecimal("92500.00"), now + 10))

        withTimeout(2_000L) {
            while (collected.none { it.symbol == "RELIANCE" } || collected.none { it.symbol == "BTCUSDT" }) {
                delay(15L)
            }
        }
        collectJob.cancel()
        localScope.cancel()

        assertTrue(collected.any { it.symbol == "RELIANCE" && it.ltp.compareTo(BigDecimal("1299.50")) == 0 })
        assertTrue(collected.any { it.symbol == "BTCUSDT" && it.ltp.compareTo(BigDecimal("92500.00")) == 0 })
        assertEquals(FeedType.ANGEL_ONE_LIVE, multiRepo.segmentConfigs.value[MarketSegment.NSE]?.feedType)
        assertEquals(FeedType.BINANCE_LIVE, multiRepo.segmentConfigs.value[MarketSegment.CRYPTO]?.feedType)
    }

    @Test
    fun `disabling or dropping ANGEL_ONE_LIVE pauses NSE risk controls when position open while CRYPTO keeps triggering ExecutionEngine`() = runBlocking {
        testScope.cancel()
        val binanceFake = FakeControlFeed(FeedType.BINANCE_LIVE)
        val angelFake = FakeControlFeed(FeedType.ANGEL_ONE_LIVE)
        val fakeFactory = object : LiveFeedFactory {
            override fun create(feedType: FeedType, simulatedTickIntervalMs: Long): FeedInstanceBundle = when (feedType) {
                FeedType.BINANCE_LIVE -> binanceFake.toBundle()
                FeedType.ANGEL_ONE_LIVE -> angelFake.toBundle()
                else -> FakeControlFeed(feedType).toBundle()
            }
        }
        val localScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val multiRepo = MarketDataRepositoryImpl(
            instrumentDao = db.instrumentDao(),
            positionDao = db.positionDao(),
            accountDao = db.accountDao(),
            simulatedFeed = SimulatedFeed(localScope),
            feedFactory = fakeFactory,
            credentialsStore = SecureBrokerCredentialsStore(context),
            appScope = localScope
        )
        multiRepo.ensureSeeded()
        delay(50L)
        val ledger = TradingLedgerService(
            db, db.instrumentDao(), db.orderDao(), db.positionDao(),
            db.ocoLinkDao(), db.tradeJournalDao(), db.accountDao()
        )
        val engine = ExecutionEngine(
            db, db.instrumentDao(), db.orderDao(), db.positionDao(),
            ledger, multiRepo, prefs, localScope
        ).also { it.start() }
        val repo = TradingRepository(
            db, db.instrumentDao(), db.orderDao(), db.positionDao(),
            db.ocoLinkDao(), db.tradeJournalDao(), db.accountDao(),
            prefs, ledger, engine
        )

        multiRepo.enableFeed(FeedType.ANGEL_ONE_LIVE)
        multiRepo.enableFeed(FeedType.BINANCE_LIVE)
        angelFake.setStatus(LiveConnectionStatus.CONNECTED, "Connected")
        binanceFake.setStatus(LiveConnectionStatus.CONNECTED, "Connected")
        delay(30L)

        // Open one NSE position and one CRYPTO position with SL
        assertTrue(
            repo.placeOrder(
                OrderPlacementRequest(
                    symbol = "RELIANCE",
                    side = OrderSide.BUY,
                    orderType = OrderType.MARKET,
                    productType = ProductType.INTRADAY,
                    quantity = 2,
                    slEnabled = true,
                    slMode = RiskInputMode.PERCENT,
                    slValue = BigDecimal("2.0"),
                    validityType = ValidityType.DAY
                )
            ).isSuccess
        )
        val btcRes = repo.placeOrder(
            OrderPlacementRequest(
                symbol = "BTCUSDT",
                side = OrderSide.BUY,
                orderType = OrderType.MARKET,
                productType = ProductType.CARRY_FORWARD,
                quantity = 1,
                slEnabled = true,
                slMode = RiskInputMode.PRICE,
                slValue = BigDecimal("85000.00"),
                targetEnabled = true,
                targetMode = RiskInputMode.PRICE,
                targetValue = BigDecimal("96000.00"),
                validityType = ValidityType.GTT,
                gttDays = 7
            )
        )
        assertTrue(btcRes.isSuccess)

        // Drop / disable Angel One -> NSE risk controls suspended, CRYPTO remains LIVE & active
        angelFake.setStatus(LiveConnectionStatus.ERROR, "Angel WS dropped")
        delay(50L)

        assertTrue(MarketSegment.NSE in multiRepo.suspendedSegments.value)
        assertFalse(MarketSegment.CRYPTO in multiRepo.suspendedSegments.value)
        assertEquals(SegmentBadgeMode.SUSPENDED, multiRepo.segmentStatuses.value[MarketSegment.NSE]?.badge)
        assertEquals(SegmentBadgeMode.LIVE, multiRepo.segmentStatuses.value[MarketSegment.CRYPTO]?.badge)
        assertTrue(multiRepo.suspendedBannerMessage.value.contains("NSE"))

        // CRYPTO Target tick STILL triggers ExecutionEngine while NSE is suspended!
        binanceFake.emitTick(PriceTick("BTCUSDT", BigDecimal("96500.00"), System.currentTimeMillis() + 600L))
        withTimeout(2_000L) {
            while (db.positionDao().getAllOnce().any { it.symbol == "BTCUSDT" }) {
                delay(20L)
            }
        }
        assertTrue(db.positionDao().getAllOnce().any { it.symbol == "RELIANCE" })
        localScope.cancel()
    }

    @Test
    fun `per-segment fallback sets DELAYED on dropped segment with no open positions without affecting other segments`() = runBlocking {
        testScope.cancel()
        val binanceFake = FakeControlFeed(FeedType.BINANCE_LIVE)
        val angelFake = FakeControlFeed(FeedType.ANGEL_ONE_LIVE)
        val fakeFactory = object : LiveFeedFactory {
            override fun create(feedType: FeedType, simulatedTickIntervalMs: Long): FeedInstanceBundle = when (feedType) {
                FeedType.BINANCE_LIVE -> binanceFake.toBundle()
                FeedType.ANGEL_ONE_LIVE -> angelFake.toBundle()
                else -> FakeControlFeed(feedType).toBundle()
            }
        }
        val localScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val multiRepo = MarketDataRepositoryImpl(
            instrumentDao = db.instrumentDao(),
            positionDao = db.positionDao(),
            accountDao = db.accountDao(),
            simulatedFeed = SimulatedFeed(localScope),
            feedFactory = fakeFactory,
            credentialsStore = SecureBrokerCredentialsStore(context),
            appScope = localScope
        )
        multiRepo.ensureSeeded()
        delay(50L)
        multiRepo.enableFeed(FeedType.ANGEL_ONE_LIVE)
        multiRepo.enableFeed(FeedType.BINANCE_LIVE)
        angelFake.setStatus(LiveConnectionStatus.CONNECTED, "Connected")
        binanceFake.setStatus(LiveConnectionStatus.RECONNECTING, "Retrying Binance")
        withTimeout(2_000L) {
            while (multiRepo.segmentStatuses.value[MarketSegment.NSE]?.badge != SegmentBadgeMode.LIVE) {
                delay(20L)
            }
        }

        // No open CRYPTO position -> CRYPTO enters DELAYED simulated fallback while NSE remains LIVE
        assertFalse(multiRepo.riskControlsSuspended.value)
        assertEquals(SegmentBadgeMode.DELAYED, multiRepo.segmentStatuses.value[MarketSegment.CRYPTO]?.badge)
        assertEquals(SegmentBadgeMode.LIVE, multiRepo.segmentStatuses.value[MarketSegment.NSE]?.badge)
        assertEquals(SegmentBadgeMode.LIVE, multiRepo.segmentStatuses.value[MarketSegment.BSE]?.badge)
        assertEquals(SegmentBadgeMode.LIVE, multiRepo.segmentStatuses.value[MarketSegment.MCX]?.badge)

        localScope.cancel()
    }

    @Test
    fun `observeFeedStatus returns independent LiveConnectionStatus per FeedType`() = runBlocking {
        testScope.cancel()
        val binanceFake = FakeControlFeed(FeedType.BINANCE_LIVE)
        val angelFake = FakeControlFeed(FeedType.ANGEL_ONE_LIVE)
        val fakeFactory = object : LiveFeedFactory {
            override fun create(feedType: FeedType, simulatedTickIntervalMs: Long): FeedInstanceBundle = when (feedType) {
                FeedType.BINANCE_LIVE -> binanceFake.toBundle()
                FeedType.ANGEL_ONE_LIVE -> angelFake.toBundle()
                else -> FakeControlFeed(feedType).toBundle()
            }
        }
        val localScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val multiRepo = MarketDataRepositoryImpl(
            instrumentDao = db.instrumentDao(),
            positionDao = db.positionDao(),
            accountDao = db.accountDao(),
            simulatedFeed = SimulatedFeed(localScope),
            feedFactory = fakeFactory,
            credentialsStore = SecureBrokerCredentialsStore(context),
            appScope = localScope
        )
        multiRepo.ensureSeeded()
        delay(50L)
        multiRepo.enableFeed(FeedType.ANGEL_ONE_LIVE)
        multiRepo.enableFeed(FeedType.BINANCE_LIVE)

        angelFake.setStatus(LiveConnectionStatus.CONNECTED, "Angel Live")
        binanceFake.setStatus(LiveConnectionStatus.RECONNECTING, "Binance Retry")
        delay(40L)

        val statusMap = multiRepo.observeFeedStatus().first()
        assertEquals(LiveConnectionStatus.CONNECTED, statusMap[FeedType.ANGEL_ONE_LIVE])
        assertEquals(LiveConnectionStatus.RECONNECTING, statusMap[FeedType.BINANCE_LIVE])
        assertEquals(LiveConnectionStatus.DISCONNECTED, statusMap[FeedType.UPSTOX_LIVE])

        localScope.cancel()
    }

    @Test
    fun `feed switching preserves open ExecutionEngine position state`() = runBlocking {
        val orderRes = tradingRepository.placeOrder(
            OrderPlacementRequest(
                symbol = "RELIANCE",
                side = OrderSide.BUY,
                orderType = OrderType.MARKET,
                productType = ProductType.CARRY_FORWARD,
                quantity = 4,
                slEnabled = true,
                slMode = RiskInputMode.PRICE,
                slValue = BigDecimal("1260.00"),
                targetEnabled = true,
                targetMode = RiskInputMode.PRICE,
                targetValue = BigDecimal("1340.00"),
                tslEnabled = true,
                tslMode = TrailMode.ABSOLUTE,
                tslValue = BigDecimal("10.00"),
                validityType = ValidityType.GTT,
                gttDays = 7
            )
        )
        assertTrue(orderRes.isSuccess)
        val initialPos = db.positionDao().getAllOnce().single()

        marketRepo.setFeedForSegment(MarketSegment.CRYPTO, FeedType.BINANCE_LIVE)
        marketRepo.setFeedForSegment(MarketSegment.NSE, FeedType.SIMULATED)
        assertEquals(FeedType.BINANCE_LIVE, marketRepo.segmentConfigs.value[MarketSegment.CRYPTO]?.feedType)
        assertEquals(FeedType.SIMULATED, marketRepo.segmentConfigs.value[MarketSegment.NSE]?.feedType)

        val afterSwitchPos = db.positionDao().getById(initialPos.id)!!
        assertEquals(initialPos.averageEntryPrice, afterSwitchPos.averageEntryPrice)
        assertEquals(initialPos.activeSlPrice, afterSwitchPos.activeSlPrice)
        assertEquals(initialPos.activeTargetPrice, afterSwitchPos.activeTargetPrice)
        assertEquals(initialPos.ocoGroupId, afterSwitchPos.ocoGroupId)
        assertTrue(afterSwitchPos.isTslEnabled)
    }

    private class FakeControlFeed(private val feedType: FeedType) : PriceFeed {
        private val _ticks = MutableSharedFlow<PriceTick>(extraBufferCapacity = 64)
        override val ticks: Flow<PriceTick> = _ticks.asSharedFlow()
        private val _connectionState = MutableStateFlow<FeedConnectionState>(
            FeedConnectionState.Connecting(FeedSourceType.SIMULATED)
        )
        override val connectionState: StateFlow<FeedConnectionState> = _connectionState.asStateFlow()

        val statusFlow = MutableStateFlow(LiveConnectionStatus.CONNECTING)
        val detailFlow = MutableStateFlow("Init")

        override fun start() = Unit
        override fun stop() = Unit
        override fun setUniverse(instruments: List<Instrument>) = Unit
        override suspend fun nudgePrice(symbol: String, percentDelta: BigDecimal) = Unit

        fun setStatus(status: LiveConnectionStatus, detail: String) {
            statusFlow.value = status
            detailFlow.value = detail
        }

        suspend fun emitTick(tick: PriceTick) {
            _ticks.emit(tick)
        }

        fun toBundle() = FeedInstanceBundle(
            feed = this,
            liveStatusSource = statusFlow,
            statusDetailSource = detailFlow
        )
    }
}
