package com.example

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.ai.FirebaseAiMarketRepository
import com.example.data.engine.ExecutionEngine
import com.example.data.engine.TradingLedgerService
import com.example.data.feed.PriceFeed
import com.example.data.feed.SimulatedFeed
import com.example.data.feed.live.BinancePriceFeed
import com.example.data.feed.live.DefaultLiveFeedFactory
import com.example.data.feed.live.FeedInstanceBundle
import com.example.data.feed.live.FeedType
import com.example.data.feed.live.LiveConnectionStatus
import com.example.data.feed.live.LiveFeedFactory
import com.example.data.feed.live.SecureBrokerCredentialsStore
import com.example.data.feed.live.SegmentBadgeMode
import com.example.data.local.PaperPulseDatabase
import com.example.data.local.SeedData
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
import com.example.domain.model.ValidityType
import com.example.domain.signal.AiSignalBias
import com.example.domain.signal.AiSignalVerdict
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
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class AiAndFeedRecoveryTest {

    private lateinit var context: Context
    private lateinit var db: PaperPulseDatabase
    private lateinit var testScope: CoroutineScope

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, PaperPulseDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        db.instrumentDao().upsertAll(SeedData.seedInstruments())
        db.accountDao().upsert(SeedData.defaultAccount())
        testScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        testScope.cancel()
        db.close()
    }

    @Test
    fun `parseSecondOpinionJson parses structured Gemini output cleanly and clamps bounds`() {
        val raw = """
            {
              "verdict": "ALIGNED",
              "bias": "BULLISH",
              "confidenceScore": 86,
              "summary": "Momentum and VWAP reclaim align with a clean 1:2.4 risk-reward.",
              "keyRiskInvalidation": "Close below 90,850 invalidates the breakout.",
              "executionGuidance": "Use OCO bracket with 0.8% TSL once price clears +1R.",
              "riskChecklist": [
                "Verify spread on BTCUSDT",
                "Confirm SL trigger distance",
                "Keep sizing within 2% capital risk"
              ]
            }
        """.trimIndent()

        val parsed = FirebaseAiMarketRepository.parseSecondOpinionJson(
            rawJson = raw,
            referenceId = "sig-1",
            symbol = "BTCUSDT"
        )

        assertEquals("sig-1", parsed.referenceId)
        assertEquals("BTCUSDT", parsed.symbol)
        assertEquals(AiSignalVerdict.ALIGNED, parsed.verdict)
        assertEquals(AiSignalBias.BULLISH, parsed.bias)
        assertEquals(86, parsed.confidenceScore)
        assertEquals(3, parsed.riskChecklist.size)
        assertTrue(parsed.keyRiskInvalidation.contains("90,850"))
    }

    @Test
    fun `binance feed prioritizes vision public endpoint before standard stream endpoints`() {
        assertTrue(BinancePriceFeed.STREAM_ENDPOINTS.first().contains("data-stream.binance.vision"))
        assertTrue(BinancePriceFeed.STREAM_ENDPOINTS.size >= 4)
    }

    @Test
    fun `initial CONNECTING handshake does not flash FEED LOST and switching CRYPTO to SIMULATED clears suspension`() = runBlocking {
        val prefs = TradingPreferencesRepository(context).apply { updateSlippageBps(0) }
        val credsStore = SecureBrokerCredentialsStore(context)
        val fakeBinance = object : PriceFeed {
            private val _ticks = MutableSharedFlow<PriceTick>(extraBufferCapacity = 16)
            override val ticks: Flow<PriceTick> = _ticks.asSharedFlow()
            private val _conn = MutableStateFlow<FeedConnectionState>(FeedConnectionState.Connecting(FeedSourceType.SIMULATED))
            override val connectionState: StateFlow<FeedConnectionState> = _conn.asStateFlow()
            val statusFlow = MutableStateFlow(LiveConnectionStatus.CONNECTING)
            val detailFlow = MutableStateFlow("Connecting WebSocket...")
            override fun start() = Unit
            override fun stop() = Unit
            override fun setUniverse(instruments: List<Instrument>) = Unit
            override suspend fun nudgePrice(symbol: String, percentDelta: BigDecimal) = Unit
            fun toBundle() = FeedInstanceBundle(this, statusFlow, detailFlow)
        }
        val okHttp = OkHttpClient.Builder().connectTimeout(200, TimeUnit.MILLISECONDS).build()
        val defaultFactory = DefaultLiveFeedFactory(okHttp, credsStore, testScope)
        val factory = object : LiveFeedFactory {
            override fun create(feedType: FeedType, simulatedTickIntervalMs: Long): FeedInstanceBundle =
                if (feedType == FeedType.BINANCE_LIVE) fakeBinance.toBundle() else defaultFactory.create(feedType, simulatedTickIntervalMs)
        }
        val repo = MarketDataRepositoryImpl(
            instrumentDao = db.instrumentDao(),
            positionDao = db.positionDao(),
            accountDao = db.accountDao(),
            simulatedFeed = SimulatedFeed(testScope),
            feedFactory = factory,
            credentialsStore = credsStore,
            appScope = testScope
        )
        repo.ensureSeeded()
        delay(50L)
        val ledger = TradingLedgerService(
            db, db.instrumentDao(), db.orderDao(), db.positionDao(),
            db.ocoLinkDao(), db.tradeJournalDao(), db.accountDao()
        )
        val engine = ExecutionEngine(
            db, db.instrumentDao(), db.orderDao(), db.positionDao(),
            ledger, repo, prefs, testScope
        ).also { it.start() }
        val tradingRepo = TradingRepository(
            db, db.instrumentDao(), db.orderDao(), db.positionDao(),
            db.ocoLinkDao(), db.tradeJournalDao(), db.accountDao(),
            prefs, ledger, engine
        )

        // Open a CRYPTO position while Binance is still CONNECTING
        val orderRes = tradingRepo.placeOrder(
            OrderPlacementRequest(
                symbol = "BTCUSDT",
                side = OrderSide.BUY,
                orderType = OrderType.MARKET,
                productType = ProductType.CARRY_FORWARD,
                quantity = 1,
                slEnabled = true,
                slMode = RiskInputMode.PERCENT,
                slValue = BigDecimal("2.0"),
                validityType = ValidityType.GTT,
                gttDays = 7
            )
        )
        assertTrue(orderRes.isSuccess)
        delay(50L)

        // Initial handshake should NOT flash FEED LOST prematurely
        assertFalse(repo.riskControlsSuspended.value)
        assertEquals(SegmentBadgeMode.DELAYED, repo.segmentStatuses.value[MarketSegment.CRYPTO]?.badge)

        // Once WebSocket fails, FEED LOST — CRYPTO risk controls suspended triggers
        fakeBinance.statusFlow.value = LiveConnectionStatus.ERROR
        fakeBinance.detailFlow.value = "HTTP 451"
        repo.reconnectSegment(MarketSegment.CRYPTO)
        // Simulate failure emission after active instance starts
        fakeBinance.statusFlow.value = LiveConnectionStatus.RECONNECTING
        delay(50L)
        assertTrue(repo.riskControlsSuspended.value)
        assertTrue(MarketSegment.CRYPTO in repo.suspendedSegments.value)
        assertEquals(SegmentBadgeMode.SUSPENDED, repo.segmentStatuses.value[MarketSegment.CRYPTO]?.badge)

        // Switching suspended segment to Simulated cleanly clears suspension
        repo.setActiveFeed(FeedType.SIMULATED)
        delay(50L)
        assertFalse(repo.riskControlsSuspended.value)
        assertEquals(SegmentBadgeMode.SIMULATED, repo.segmentStatuses.value[MarketSegment.CRYPTO]?.badge)
    }

    @Test
    fun `angel one subscription payload uses strictly 10-char alphanumeric correlationID`() {
        val payloadJson = org.json.JSONObject(com.example.data.feed.live.AngelOnePriceFeed.buildSubscriptionPayload())
        val correlationId = payloadJson.getString("correlationID")
        assertEquals(10, correlationId.length)
        assertTrue(correlationId.all { it.isLetterOrDigit() })
        assertEquals("ppulse0001", correlationId)
        assertEquals(1, payloadJson.getInt("action"))
        assertEquals(1, payloadJson.getJSONObject("params").getInt("mode"))
    }

    @Test
    fun `angel one jwt normalization prevents double Bearer and alternates query fallback`() {
        val clean = com.example.data.feed.live.AngelOnePriceFeed.normalizeJwtToken("  Bearer eyJhbGciOiJIUzI1NiJ9.test  ")
        assertEquals("eyJhbGciOiJIUzI1NiJ9.test", clean)
        assertEquals("raw.jwt.token", com.example.data.feed.live.AngelOnePriceFeed.normalizeJwtToken("bearer raw.jwt.token"))

        val dummyCreds = com.example.data.feed.live.BrokerFeedSettings(
            angelOneApiKey = "  TESTAPI1  ",
            angelOneClientCode = "  c12345  ",
            angelOnePin = "1234",
            angelOneTotpSecret = "JBSWY3DPEHPK3PXP"
        )
        val dummySession = com.example.data.feed.live.AngelOnePriceFeed.AngelSessionTokens(
            jwtToken = "Bearer dummy.jwt.value",
            feedToken = "dummy_feed_token"
        )

        val rawPrimaryReq = com.example.data.feed.live.AngelOnePriceFeed.buildSmartStreamRequest(
            creds = dummyCreds,
            session = dummySession,
            attemptIndex = 0
        )
        assertEquals("dummy.jwt.value", rawPrimaryReq.header("Authorization"))
        assertEquals("TESTAPI1", rawPrimaryReq.header("x-api-key"))
        assertEquals("C12345", rawPrimaryReq.header("x-client-code"))
        assertEquals("dummy_feed_token", rawPrimaryReq.header("x-feed-token"))
        assertFalse(rawPrimaryReq.url.toString().contains("feedToken="))

        val bearerReq = com.example.data.feed.live.AngelOnePriceFeed.buildSmartStreamRequest(
            creds = dummyCreds,
            session = dummySession,
            attemptIndex = 1
        )
        assertEquals("Bearer dummy.jwt.value", bearerReq.header("Authorization"))
        assertFalse(bearerReq.url.toString().contains("feedToken="))

        val queryFallbackReq = com.example.data.feed.live.AngelOnePriceFeed.buildSmartStreamRequest(
            creds = dummyCreds,
            session = dummySession,
            attemptIndex = 2
        )
        assertEquals("dummy.jwt.value", queryFallbackReq.header("Authorization"))
        assertTrue(queryFallbackReq.url.toString().contains("clientCode=C12345"))
        assertTrue(queryFallbackReq.url.toString().contains("feedToken=dummy_feed_token"))
        assertTrue(queryFallbackReq.url.toString().contains("apiKey=TESTAPI1"))
    }

    @Test
    fun `angel one totp generator sanitizes lowercase spaces and hyphens consistently`() {
        val fixedEpochMs = 1_700_000_000_000L
        val canonical = com.example.data.feed.live.AngelOnePriceFeed.generateTotp("JBSWY3DPEHPK3PXP", fixedEpochMs)
        val messy = com.example.data.feed.live.AngelOnePriceFeed.generateTotp("jbsw-y3dp ehpk 3pxp", fixedEpochMs)
        assertEquals(6, canonical.length)
        assertTrue(canonical.all { it.isDigit() })
        assertEquals(canonical, messy)
    }

    @Test
    fun `shared feed slot starts delegate once across NSE BSE MCX and stops only when last handle stops`() {
        var startCalls = 0
        var stopCalls = 0
        val delegate = object : PriceFeed {
            private val _ticks = MutableSharedFlow<PriceTick>()
            override val ticks: Flow<PriceTick> = _ticks.asSharedFlow()
            private val _conn = MutableStateFlow<FeedConnectionState>(FeedConnectionState.Disconnected(FeedSourceType.BROKER_ANGEL_ONE, "idle"))
            override val connectionState: StateFlow<FeedConnectionState> = _conn.asStateFlow()
            override fun start() { startCalls++ }
            override fun stop() { stopCalls++ }
            override fun setUniverse(instruments: List<Instrument>) = Unit
            override suspend fun nudgePrice(symbol: String, percentDelta: BigDecimal) = Unit
        }
        val slot = com.example.data.feed.live.SharedFeedSlot(
            delegate = delegate,
            liveStatusSource = MutableStateFlow(LiveConnectionStatus.DISCONNECTED),
            statusDetailSource = MutableStateFlow("idle")
        )

        val nseHandle = slot.createHandle()
        val bseHandle = slot.createHandle()
        val mcxHandle = slot.createHandle()

        nseHandle.start()
        bseHandle.start()
        mcxHandle.start()
        // Idempotent duplicate start on same handle must not double-increment
        nseHandle.start()
        assertEquals(1, startCalls)
        assertEquals(0, stopCalls)

        // Stopping 1 or 2 handles must keep shared broker connection alive
        mcxHandle.stop()
        assertEquals(0, stopCalls)
        bseHandle.stop()
        assertEquals(0, stopCalls)

        // Stopping last handle stops delegate once
        nseHandle.stop()
        nseHandle.stop() // idempotent
        assertEquals(1, stopCalls)

        // Restarting after reaching 0 invokes delegate.start() again
        nseHandle.start()
        assertEquals(2, startCalls)
    }

    @Test
    fun `angel one scrip parser maps NSE stocks, NFO and BFO index options, and MCX futures cleanly`() {
        val nseStock = com.example.data.feed.live.AngelOnePriceFeed.parseAngelScripResult(
            obj = org.json.JSONObject(
                """{"exchange":"NSE","tradingsymbol":"INFY-EQ","symboltoken":"1594","name":"INFOSYS","lotsize":"1","tick_size":"5","instrumenttype":""}"""
            ),
            fallbackExchange = "NSE"
        )!!
        assertEquals("INFY", nseStock.symbol)
        assertEquals(MarketSegment.NSE, nseStock.segment)
        assertEquals(com.example.domain.model.InstrumentType.STOCK, nseStock.instrumentType)
        assertEquals(1, nseStock.exchangeTypeCode)
        assertEquals(BigDecimal("0.05"), nseStock.tickSize)

        val niftyOpt = com.example.data.feed.live.AngelOnePriceFeed.parseAngelScripResult(
            obj = org.json.JSONObject(
                """{"exchange":"NFO","tradingsymbol":"NIFTY28NOV2424500CE","symboltoken":"54321","instrumenttype":"OPTIDX","lotsize":"75","strike":"2450000"}"""
            ),
            fallbackExchange = "NFO"
        )!!
        assertEquals("NIFTY28NOV2424500CE", niftyOpt.symbol)
        assertEquals(MarketSegment.NSE, niftyOpt.segment)
        assertEquals(com.example.domain.model.InstrumentType.OPTIONS, niftyOpt.instrumentType)
        assertEquals(com.example.domain.model.OptionType.CE, niftyOpt.optionType)
        assertEquals(BigDecimal("24500.00"), niftyOpt.strikePrice)
        assertEquals(75, niftyOpt.lotSize)
        assertEquals(2, niftyOpt.exchangeTypeCode)

        val sensexOpt = com.example.data.feed.live.AngelOnePriceFeed.parseAngelScripResult(
            obj = org.json.JSONObject(
                """{"exchange":"BFO","tradingsymbol":"SENSEX29NOV2480000PE","symboltoken":"889012","instrumenttype":"OPTIDX"}"""
            ),
            fallbackExchange = "BFO"
        )!!
        assertEquals("SENSEX29NOV2480000PE", sensexOpt.symbol)
        assertEquals(MarketSegment.BSE, sensexOpt.segment)
        assertEquals(com.example.domain.model.InstrumentType.OPTIONS, sensexOpt.instrumentType)
        assertEquals(com.example.domain.model.OptionType.PE, sensexOpt.optionType)
        assertEquals(BigDecimal("80000.00"), sensexOpt.strikePrice)
        assertEquals(20, sensexOpt.lotSize)
        assertEquals(4, sensexOpt.exchangeTypeCode)

        val mcxFut = com.example.data.feed.live.AngelOnePriceFeed.parseAngelScripResult(
            obj = org.json.JSONObject(
                """{"exchange":"MCX","tradingsymbol":"GOLD05DEC24FUT","symboltoken":"429009","instrumenttype":"FUTCOM","lotsize":"1"}"""
            ),
            fallbackExchange = "MCX"
        )!!
        assertEquals(MarketSegment.MCX, mcxFut.segment)
        assertEquals(com.example.domain.model.InstrumentType.FUTURES, mcxFut.instrumentType)
        assertEquals(5, mcxFut.exchangeTypeCode)
    }

    @Test
    fun `angel one exchange router targets NFO for NIFTY, BFO for SENSEX, MCX for CRUDEOIL, and NSE for stocks`() {
        assertEquals(
            "NFO",
            com.example.data.feed.live.AngelOnePriceFeed.chooseSearchExchanges("NIFTY", null, null).first()
        )
        assertEquals(
            "BFO",
            com.example.data.feed.live.AngelOnePriceFeed.chooseSearchExchanges("SENSEX", null, null).first()
        )
        assertEquals(
            listOf("MCX"),
            com.example.data.feed.live.AngelOnePriceFeed.chooseSearchExchanges("CRUDEOIL", null, null)
        )
        assertEquals(
            listOf("NSE", "NFO", "BSE", "MCX"),
            com.example.data.feed.live.AngelOnePriceFeed.chooseSearchExchanges("INFY", null, null)
        )
        assertEquals(
            listOf("MCX"),
            com.example.data.feed.live.AngelOnePriceFeed.chooseSearchExchanges("NATURALGAS", null, null)
        )
        assertEquals(
            listOf("MCX"),
            com.example.data.feed.live.AngelOnePriceFeed.chooseSearchExchanges("NATGASMINI", null, null)
        )
    }

    @Test
    fun `smartstream V2 binary packet slices at first null byte and decodes Mode 3 Snap Quote OI and volume`() {
        val optionSymbol = "SENSEX29NOV2480000PE"
        val mapping = com.example.data.feed.live.AngelOnePriceFeed.SmartApiTokenMapping(
            symbol = optionSymbol,
            exchangeType = 4, // BFO
            token = "889012"
        )
        val inst = Instrument(
            symbol = optionSymbol,
            displayName = "BFO Option • $optionSymbol",
            segment = MarketSegment.BSE,
            type = com.example.domain.model.InstrumentType.OPTIONS,
            ltp = BigDecimal("210.00"),
            previousClose = BigDecimal("200.00"),
            dayHigh = BigDecimal("220.00"),
            dayLow = BigDecimal("195.00"),
            volume = 10_000L,
            lotSize = 20,
            tickSize = BigDecimal("0.05"),
            optionType = com.example.domain.model.OptionType.PE,
            strikePrice = BigDecimal("80000.00")
        )
        val packet = java.nio.ByteBuffer.allocate(140).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
            put(3.toByte()) // Mode 3 = Snap Quote
            put(4.toByte()) // exchangeType = 4 (BFO)
            val tokenBytes = ByteArray(25)
            // Populate token + null terminator + trailing non-zero buffer garbage (classic SmartStream V2 C-string)
            "889012".toByteArray(Charsets.US_ASCII).copyInto(tokenBytes)
            tokenBytes[6] = 0
            tokenBytes[10] = 'X'.code.toByte()
            tokenBytes[11] = 'Z'.code.toByte()
            put(tokenBytes)
            putLong(1L) // sequence
            putLong(1_732_000_000_000L) // timestamp
            putLong(24575L) // 245.75 in paise
            putLong(67, 543_210L) // volumeTradeForTheDay at offset 67
            putLong(131, 1_250_000L) // openInterest at offset 131
        }.array()

        val rawTick = com.example.data.feed.live.AngelOnePriceFeed.parseSmartApiBinaryPacket(packet)!!
        assertEquals("889012", rawTick.token)
        assertEquals(BigDecimal("245.75"), rawTick.ltp)
        assertEquals(543_210L, rawTick.volume)
        assertEquals(1_250_000L, rawTick.openInterest)

        val tick = com.example.data.feed.live.AngelOnePriceFeed.parseSmartApiLtpPacket(
            bytes = packet,
            universe = mapOf(optionSymbol to inst),
            tokenLookup = { exch, tok -> if (exch == 4 && tok == "889012") mapping else null }
        )!!
        assertEquals(optionSymbol, tick.symbol)
        assertEquals(BigDecimal("245.75"), tick.ltp)
        assertEquals(543_210L, tick.volume)
    }

    @Test
    fun `option greeks calculator solves IV, Black-Scholes Greeks, Max Pain, and OI formatting accurately`() {
        val spot = 24500.0
        val strike = 24500.0
        val tYears = 14.0 / 365.0
        val fairCallPrice = com.example.domain.engine.OptionGreeksCalculator.blackScholesPrice(
            spot = spot,
            strike = strike,
            t = tYears,
            r = 0.065,
            sigma = 0.16,
            isCall = true
        )
        val callGreeks = com.example.domain.engine.OptionGreeksCalculator.computeGreeks(
            spot = spot,
            strike = strike,
            timeToExpiryYears = tYears,
            optionPrice = fairCallPrice,
            isCall = true
        )
        assertTrue("Solved IV should be near 16%", kotlin.math.abs(callGreeks.ivPercent - 16.0) < 0.25)
        assertTrue("ATM Call delta should be ~0.50-0.55", callGreeks.delta in 0.48..0.56)
        assertTrue("Gamma must be positive", callGreeks.gamma > 0.0)
        assertTrue("Theta per day must be negative for long option", callGreeks.thetaPerDay < 0.0)
        assertTrue("Vega must be positive", callGreeks.vega > 0.0)

        assertEquals("12.40L", com.example.domain.engine.OptionGreeksCalculator.formatOiCompact(1_240_000L))
        assertEquals("1.25Cr", com.example.domain.engine.OptionGreeksCalculator.formatOiCompact(12_500_000L))
        assertEquals("85.5K", com.example.domain.engine.OptionGreeksCalculator.formatOiCompact(85_500L))

        val parsedBrokerGreeks = com.example.data.feed.live.AngelOnePriceFeed.parseOptionGreekResponse(
            """
            {
              "status": true,
              "data": [
                {
                  "name": "NIFTY",
                  "expiry": "28NOV2026",
                  "strikePrice": "24500.000000",
                  "optionType": "CE",
                  "delta": "0.512400",
                  "gamma": "0.000800",
                  "theta": "-14.250000",
                  "vega": "11.400000",
                  "impliedVolatility": "14.850000"
                }
              ]
            }
            """.trimIndent()
        )
        val ce = parsedBrokerGreeks["24500.00:CE"]!!
        assertTrue(ce.isBrokerSupplied)
        assertEquals(14.85, ce.ivPercent, 0.01)
        assertEquals(0.5124, ce.delta, 0.001)
        assertEquals(-14.25, ce.thetaPerDay, 0.01)

        assertEquals(
            BigDecimal("24500.00"),
            com.example.data.feed.live.AngelScripMasterStore.parseMasterStrike("2450000.000000", "OPTIDX")
        )
        assertEquals(
            "NATURALGAS",
            com.example.data.feed.live.AngelScripMasterStore.canonicalUnderlying("Natural Gas")
        )
    }
}
