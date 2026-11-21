package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.engine.ExecutionEngine
import com.example.data.engine.TradingLedgerService
import com.example.data.feed.live.BrokerFeedSettings
import com.example.data.feed.live.FeedType
import com.example.data.feed.live.LiveConnectionStatus
import com.example.data.feed.live.SegmentFeedConfig
import com.example.data.feed.live.SegmentFeedStatus
import com.example.data.local.PaperPulseDatabase
import com.example.data.local.SeedData
import com.example.data.local.toDomain
import com.example.data.repository.MarketDataRepository
import com.example.data.repository.TradingPreferencesRepository
import com.example.data.repository.TradingRepository
import com.example.domain.model.FeedConnectionState
import com.example.domain.model.FeedSourceType
import com.example.domain.model.Instrument
import com.example.domain.model.MarketSegment
import com.example.domain.model.OrderPlacementRequest
import com.example.domain.model.OrderSide
import com.example.domain.model.OrderStatus
import com.example.domain.model.OrderType
import com.example.domain.model.PriceTick
import com.example.domain.model.ProductType
import com.example.domain.model.RiskInputMode
import com.example.domain.model.TrailMode
import com.example.domain.model.ValidityState
import com.example.domain.model.ValidityType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExecutionEngineIntegrationTest {

    private lateinit var db: PaperPulseDatabase
    private lateinit var testScope: CoroutineScope
    private lateinit var ledgerService: TradingLedgerService
    private lateinit var executionEngine: ExecutionEngine
    private lateinit var tradingRepository: TradingRepository

    @Before
    fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PaperPulseDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        db.instrumentDao().upsertAll(SeedData.seedInstruments())
        db.accountDao().upsert(SeedData.defaultAccount())

        testScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val prefs = TradingPreferencesRepository(context)
        prefs.updateSlippageBps(0) // Deterministic fill prices for integration assertions

        val fakeMarketRepo = object : MarketDataRepository {
            override val instruments: Flow<List<Instrument>> =
                db.instrumentDao().observeAll().map { it.map { e -> e.toDomain() } }
            override val ticks: Flow<PriceTick> = MutableSharedFlow()
            override val connectionState: StateFlow<FeedConnectionState> =
                MutableStateFlow(FeedConnectionState.Connected(FeedSourceType.SIMULATED, "Test"))
            override val segmentConfigs: StateFlow<Map<MarketSegment, SegmentFeedConfig>> =
                MutableStateFlow(BrokerFeedSettings.defaultSegmentConfigs())
            override val activeFeedType: StateFlow<FeedType> = MutableStateFlow(FeedType.SIMULATED)
            override val liveConnectionStatus: StateFlow<LiveConnectionStatus> =
                MutableStateFlow(LiveConnectionStatus.DISCONNECTED)
            override val liveStatusMessage: StateFlow<String> = MutableStateFlow("Test")
            override val segmentStatuses: StateFlow<Map<MarketSegment, SegmentFeedStatus>> =
                MutableStateFlow(emptyMap())
            override val riskControlsSuspended: StateFlow<Boolean> = MutableStateFlow(false)
            override val suspendedSegments: StateFlow<Set<MarketSegment>> = MutableStateFlow(emptySet())
            override val suspendedBannerMessage: StateFlow<String> = MutableStateFlow("")
            override val brokerSettings: StateFlow<BrokerFeedSettings> =
                MutableStateFlow(BrokerFeedSettings())

            override fun observeInstrument(symbol: String): Flow<Instrument?> =
                db.instrumentDao().observeBySymbol(symbol).map { it?.toDomain() }
            override fun observeFeedStatus(): Flow<Map<FeedType, LiveConnectionStatus>> =
                MutableStateFlow(emptyMap())
            override fun setFeedForSegment(segment: MarketSegment, feedType: FeedType) = Unit
            override fun enableFeed(feedType: FeedType) = Unit
            override fun disableFeed(feedType: FeedType) = Unit
            override suspend fun ensureSeeded() = Unit
            override suspend fun setActiveFeed(feedType: FeedType) = Unit
            override suspend fun enableLiveForAllSupportedSegments() = Unit
            override suspend fun updateBrokerSettings(settings: BrokerFeedSettings) = Unit
            override suspend fun reconnectActiveFeed() = Unit
            override suspend fun reconnectSegment(segment: MarketSegment) = Unit
            override suspend fun nudgePrice(symbol: String, percentDelta: BigDecimal) = Unit
        }

        ledgerService = TradingLedgerService(
            db, db.instrumentDao(), db.orderDao(), db.positionDao(),
            db.ocoLinkDao(), db.tradeJournalDao(), db.accountDao()
        )
        executionEngine = ExecutionEngine(
            db, db.instrumentDao(), db.orderDao(), db.positionDao(),
            ledgerService, fakeMarketRepo, prefs, testScope
        )
        tradingRepository = TradingRepository(
            db, db.instrumentDao(), db.orderDao(), db.positionDao(),
            db.ocoLinkDao(), db.tradeJournalDao(), db.accountDao(),
            prefs, ledgerService, executionEngine
        )
    }

    @After
    fun tearDown() {
        testScope.cancel()
        db.close()
    }

    @Test
    fun `bracket order with TSL ratchets SL up and OCO cancels Target when TSL hits`() = runTest {
        // Seed RELIANCE LTP is 1284.50
        val res = tradingRepository.placeOrder(
            OrderPlacementRequest(
                symbol = "RELIANCE",
                side = OrderSide.BUY,
                orderType = OrderType.BRACKET,
                productType = ProductType.INTRADAY,
                quantity = 10,
                slEnabled = true,
                slMode = RiskInputMode.PRICE,
                slValue = BigDecimal("1270.00"),
                targetEnabled = true,
                targetMode = RiskInputMode.PRICE,
                targetValue = BigDecimal("1350.00"),
                tslEnabled = true,
                tslMode = TrailMode.ABSOLUTE,
                tslValue = BigDecimal("10.00"),
                validityType = ValidityType.DAY
            )
        )
        assertTrue(res.isSuccess)

        val positions = db.positionDao().getAllOnce()
        assertEquals(1, positions.size)
        val pos = positions.first()
        assertNotNull(pos.ocoGroupId)

        // Price rises to 1320.00 -> TSL must ratchet up to 1310.00
        executionEngine.evaluateTick("RELIANCE", BigDecimal("1320.00"), System.currentTimeMillis())
        val afterRise = db.positionDao().getById(pos.id)!!
        assertEquals(BigDecimal("1310.00"), afterRise.activeSlPrice)

        // Price retraces to 1315.00 -> TSL must NOT move down
        executionEngine.evaluateTick("RELIANCE", BigDecimal("1315.00"), System.currentTimeMillis())
        val afterRetrace = db.positionDao().getById(pos.id)!!
        assertEquals(BigDecimal("1310.00"), afterRetrace.activeSlPrice)

        // Price hits 1309.00 -> TSL triggers, Target order cancelled by OCO, Journal recorded
        executionEngine.evaluateTick("RELIANCE", BigDecimal("1309.00"), System.currentTimeMillis())
        assertTrue(db.positionDao().getAllOnce().isEmpty())

        val orders = tradingRepository.orders.first()
        val slLeg = orders.first { it.legRole == "SL" }
        val targetLeg = orders.first { it.legRole == "TARGET" }
        assertEquals(OrderStatus.EXECUTED, slLeg.status)
        assertEquals(ValidityState.TRIGGERED, slLeg.validityState)
        assertEquals(OrderStatus.CANCELLED, targetLeg.status)
        assertEquals(ValidityState.CANCELLED, targetLeg.validityState)

        val journal = tradingRepository.journalEntries.first()
        assertEquals(1, journal.size)
        assertTrue(journal.first().grossPnl > BigDecimal.ZERO)
    }

    @Test
    fun `intraday square-off closes intraday and preserves carry forward GTT legs`() = runTest {
        // Place Intraday DAY position on TCS
        tradingRepository.placeOrder(
            OrderPlacementRequest(
                symbol = "TCS",
                side = OrderSide.BUY,
                orderType = OrderType.MARKET,
                productType = ProductType.INTRADAY,
                quantity = 2,
                slEnabled = true,
                slMode = RiskInputMode.PERCENT,
                slValue = BigDecimal("2.0"),
                validityType = ValidityType.DAY
            )
        )
        // Place Carry Forward GTT position on HDFCBANK
        tradingRepository.placeOrder(
            OrderPlacementRequest(
                symbol = "HDFCBANK",
                side = OrderSide.BUY,
                orderType = OrderType.MARKET,
                productType = ProductType.CARRY_FORWARD,
                quantity = 5,
                slEnabled = true,
                slMode = RiskInputMode.PERCENT,
                slValue = BigDecimal("3.0"),
                validityType = ValidityType.GTT,
                gttDays = 14
            )
        )

        val squaredOff = executionEngine.triggerSimulatedSquareOff()
        assertEquals(1, squaredOff)

        val remainingPositions = db.positionDao().getAllOnce()
        assertEquals(1, remainingPositions.size)
        assertEquals("HDFCBANK", remainingPositions.first().symbol)

        val orders = tradingRepository.orders.first()
        val hdfcSl = orders.first { it.symbol == "HDFCBANK" && it.legRole == "SL" }
        assertEquals(OrderStatus.PENDING, hdfcSl.status)
        assertEquals(ValidityState.ACTIVE, hdfcSl.validityState)
    }
}
