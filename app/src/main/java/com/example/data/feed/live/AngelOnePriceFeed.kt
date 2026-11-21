package com.example.data.feed.live

import android.util.Log
import com.example.data.feed.PriceFeed
import com.example.domain.engine.OptionChainRow
import com.example.domain.engine.OptionChainSnapshot
import com.example.domain.engine.OptionGreeks
import com.example.domain.engine.OptionGreeksCalculator
import com.example.domain.engine.OptionLegQuote
import com.example.domain.engine.RiskMath
import com.example.domain.engine.TokenLiveTick
import com.example.domain.model.FeedConnectionState
import com.example.domain.model.FeedSourceType
import com.example.domain.model.Instrument
import com.example.domain.model.InstrumentType
import com.example.domain.model.MarketSegment
import com.example.domain.model.OptionType
import com.example.domain.model.PriceTick
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import javax.net.SocketFactory

data class AngelScripSearchResult(
    val exchange: String,       // "NSE", "NFO", "BSE", "BFO", "MCX"
    val tradingSymbol: String,  // Raw SmartAPI tradingsymbol e.g. "INFY-EQ", "NIFTY28NOV2424500CE"
    val symbol: String,         // App symbol e.g. "INFY", "BSE:SBIN", "NIFTY28NOV2424500CE"
    val symbolToken: String,
    val displayName: String,
    val segment: MarketSegment,
    val instrumentType: InstrumentType,
    val lotSize: Int,
    val tickSize: BigDecimal,
    val expiryLabel: String?,
    val strikePrice: BigDecimal?,
    val optionType: OptionType?,
    val exchangeTypeCode: Int   // 1=NSE_CM, 2=NSE_FO, 3=BSE_CM, 4=BSE_FO, 5=MCX_FO
)

data class AngelQuoteSnapshot(
    val ltp: BigDecimal,
    val open: BigDecimal? = null,
    val high: BigDecimal? = null,
    val low: BigDecimal? = null,
    val close: BigDecimal? = null,
    val netChange: BigDecimal? = null,
    val percentChange: BigDecimal? = null,
    val tradeVolume: Long? = null,
    val openInterest: Long? = null
)

/**
 * Angel One SmartAPI WebSocket 2.0 adapter for NSE / BSE / MCX.
 * Performs REST session login (API key + Client Code + PIN + TOTP) then connects to
 * wss://smartapisocket.angelone.in/smart-stream, paired with a periodic per-exchange
 * FULL quote refresh loop and streaming OpenAPIScripMaster lookup for complete MCX
 * commodities, stocks, and live Option Chain (Greeks + OI). Never places real orders.
 */
@Singleton
class AngelOnePriceFeed : PriceFeed {

    private val okHttpClient: OkHttpClient
    private val credentialsStore: SecureBrokerCredentialsStore
    private val appScope: CoroutineScope
    private val scripMasterStore: AngelScripMasterStore?

    @Inject
    constructor(
        @Named("liveWs") okHttpClient: OkHttpClient,
        credentialsStore: SecureBrokerCredentialsStore,
        appScope: CoroutineScope,
        scripMasterStore: AngelScripMasterStore
    ) {
        this.okHttpClient = okHttpClient
        this.credentialsStore = credentialsStore
        this.appScope = appScope
        this.scripMasterStore = scripMasterStore
        loadInitialTokenMappings()
    }

    constructor(
        okHttpClient: OkHttpClient,
        credentialsStore: SecureBrokerCredentialsStore,
        appScope: CoroutineScope
    ) {
        this.okHttpClient = okHttpClient
        this.credentialsStore = credentialsStore
        this.appScope = appScope
        this.scripMasterStore = null
        loadInitialTokenMappings()
    }

    private val restClient: OkHttpClient by lazy {
        okHttpClient.newBuilder()
            .socketFactory(SocketFactory.getDefault())
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .pingInterval(0, TimeUnit.MILLISECONDS)
            .build()
    }

    /**
     * Angel One SmartStream V2 uses application-level text "ping" / "pong" heartbeats rather than
     * RFC 6455 binary ping control frames; disabling OkHttp's control-frame pingInterval prevents
     * premature SocketTimeoutException disconnects.
     */
    private val smartStreamWsClient: OkHttpClient by lazy {
        okHttpClient.newBuilder()
            .pingInterval(0, TimeUnit.MILLISECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    private val _ticks = MutableSharedFlow<PriceTick>(extraBufferCapacity = 256)
    override val ticks: Flow<PriceTick> = _ticks.asSharedFlow()

    private val _tokenTicks = MutableSharedFlow<TokenLiveTick>(extraBufferCapacity = 256)
    val tokenTicks: Flow<TokenLiveTick> = _tokenTicks.asSharedFlow()

    private val _connectionState = MutableStateFlow<FeedConnectionState>(
        FeedConnectionState.Disconnected(FeedSourceType.BROKER_ANGEL_ONE, "Angel One idle")
    )
    override val connectionState: StateFlow<FeedConnectionState> = _connectionState.asStateFlow()

    private val _liveStatus = MutableStateFlow(LiveConnectionStatus.DISCONNECTED)
    val liveStatus: StateFlow<LiveConnectionStatus> = _liveStatus.asStateFlow()

    private val _statusDetail = MutableStateFlow("Enter Angel One SmartAPI credentials")
    val statusDetail: StateFlow<String> = _statusDetail.asStateFlow()

    private val universe = ConcurrentHashMap<String, Instrument>()
    private val registeredTokensBySymbol = ConcurrentHashMap<String, SmartApiTokenMapping>()
    private val registeredTokensByKey = ConcurrentHashMap<String, SmartApiTokenMapping>()
    private val sessionMutex = Mutex()
    private val authAttemptCounter = AtomicInteger(0)

    @Volatile
    private var activeWebSocket: WebSocket? = null

    @Volatile
    private var cachedSession: CachedSession? = null

    @Volatile
    private var serverClockOffsetMs: Long = 0L

    private var streamJob: Job? = null
    private var quotePollJob: Job? = null

    private fun loadInitialTokenMappings() {
        TOKEN_MAP.forEach { putTokenMapping(it) }
        credentialsStore.getCustomAngelTokens().forEach { (sym, exch, tok) ->
            putTokenMapping(SmartApiTokenMapping(sym, exch, tok))
        }
    }

    data class SmartApiTokenMapping(
        val symbol: String,
        val exchangeType: Int, // 1=NSE_CM, 2=NSE_FO, 3=BSE_CM, 4=BSE_FO, 5=MCX_FO
        val token: String
    )

    data class AngelSessionTokens(
        val jwtToken: String,
        val feedToken: String
    )

    private data class CachedSession(
        val fingerprint: String,
        val tokens: AngelSessionTokens,
        val createdAtMillis: Long
    )

    private fun putTokenMapping(mapping: SmartApiTokenMapping) {
        registeredTokensBySymbol[mapping.symbol] = mapping
        registeredTokensByKey["${mapping.exchangeType}:${mapping.token}"] = mapping
    }

    fun registerAndSubscribeToken(mapping: SmartApiTokenMapping) {
        putTokenMapping(mapping)
        credentialsStore.saveCustomAngelToken(mapping.symbol, mapping.exchangeType, mapping.token)
        val ws = activeWebSocket
        if (ws != null) {
            ws.send(buildSubscriptionPayload(listOf(mapping), mode = 1))
        }
        appScope.launch(Dispatchers.IO) {
            refreshRegisteredQuotesSnapshot()
        }
    }

    /**
     * Subscribes temporary tokens (e.g. visible Option Chain strikes + spot) on the live SmartStream
     * V2 socket in Snap Quote mode (mode = 3: LTP + OI + Volume) without saving them to Watchlist.
     */
    fun subscribeTemporaryTokens(mappings: List<SmartApiTokenMapping>, mode: Int = 3) {
        if (mappings.isEmpty()) return
        val ws = activeWebSocket ?: return
        mappings.chunked(45).forEach { batch ->
            ws.send(buildSubscriptionPayload(batch, mode = mode))
        }
    }

    override fun setUniverse(instruments: List<Instrument>) {
        val hadUniverse = universe.isNotEmpty()
        instruments.forEach { universe[it.symbol] = it }
        if (!hadUniverse && universe.isNotEmpty() && _liveStatus.value == LiveConnectionStatus.CONNECTED) {
            appScope.launch(Dispatchers.IO) {
                refreshRegisteredQuotesSnapshot()
            }
        }
    }

    override fun start() {
        if (streamJob?.isActive == true) return
        val creds = credentialsStore.getCurrent()
        if (!creds.hasAngelOneCredentials()) {
            _liveStatus.value = LiveConnectionStatus.ERROR
            _statusDetail.value = "Missing API Key, Client Code, MPIN, or TOTP Secret"
            _connectionState.value = FeedConnectionState.Disconnected(
                FeedSourceType.BROKER_ANGEL_ONE,
                _statusDetail.value
            )
            return
        }

        authAttemptCounter.set(0)
        streamJob = appScope.launch(Dispatchers.Default) {
            reconnectingWebSocketFlow(
                client = smartStreamWsClient,
                backoff = BackoffStrategy(initialDelayMs = 2_000L, maxDelayMs = 60_000L),
                appPingIntervalMs = 15_000L,
                appPingPayload = "ping",
                requestProvider = {
                    val latestCreds = credentialsStore.getCurrent()
                    val session = obtainOrRefreshSession(latestCreds)
                    buildSmartStreamRequest(
                        creds = latestCreds,
                        session = session,
                        attemptIndex = authAttemptCounter.getAndIncrement()
                    )
                },
                onFailure = { failure ->
                    activeWebSocket = null
                    if (failure.responseCode == 401 || failure.responseCode == 403) {
                        Log.w(TAG, "SmartStream auth rejected (HTTP ${failure.responseCode}): ${failure.errorHeader}")
                        if (authAttemptCounter.get() % 3 == 0) {
                            // Clear session after cycling through all 3 SmartStream auth variants
                            cachedSession = null
                        }
                    }
                },
                onStatusChange = { status, detail ->
                    _liveStatus.value = status
                    _statusDetail.value = detail
                    _connectionState.value = when (status) {
                        LiveConnectionStatus.CONNECTED -> FeedConnectionState.Connected(
                            FeedSourceType.BROKER_ANGEL_ONE,
                            "Angel One SmartStream LTP"
                        )
                        LiveConnectionStatus.CONNECTING, LiveConnectionStatus.RECONNECTING ->
                            FeedConnectionState.Connecting(FeedSourceType.BROKER_ANGEL_ONE)
                        LiveConnectionStatus.DISCONNECTED, LiveConnectionStatus.ERROR ->
                            FeedConnectionState.Disconnected(FeedSourceType.BROKER_ANGEL_ONE, detail)
                    }
                }
            )
                .mapNotNull { event ->
                    when (event) {
                        is WebSocketEvent.Open -> {
                            authAttemptCounter.set(0)
                            activeWebSocket = event.webSocket
                            val allMappings = registeredTokensBySymbol.values.toList()
                            if (allMappings.isNotEmpty()) {
                                allMappings.chunked(45).forEach { batch ->
                                    event.webSocket.send(buildSubscriptionPayload(batch, mode = 1))
                                }
                            }
                            appScope.launch(Dispatchers.IO) {
                                resolveSeededDerivativeTokens()
                                refreshRegisteredQuotesSnapshot()
                            }
                            startPeriodicQuotePoller()
                            null
                        }
                        is WebSocketEvent.Closed -> {
                            activeWebSocket = null
                            quotePollJob?.cancel()
                            null
                        }
                        is WebSocketEvent.TextMessage -> {
                            if (!event.text.equals("pong", ignoreCase = true)) {
                                Log.d(TAG, "SmartStream text frame: ${event.text.take(200)}")
                            }
                            null
                        }
                        is WebSocketEvent.BinaryMessage -> {
                            val rawBytes = event.bytes.toByteArray()
                            val rawTick = parseSmartApiBinaryPacket(rawBytes)
                            if (rawTick != null) {
                                _tokenTicks.tryEmit(rawTick)
                                val mapping = registeredTokensByKey[rawTick.key]
                                    ?: TOKEN_TO_SYMBOL[rawTick.token]
                                val inst = mapping?.let { universe[it.symbol] }
                                if (inst != null) {
                                    val rounded = RiskMath.roundToTick(rawTick.ltp, inst.tickSize)
                                    PriceTick(
                                        symbol = inst.symbol,
                                        ltp = rounded,
                                        timestampMillis = rawTick.timestampMillis,
                                        volume = rawTick.volume
                                    )
                                } else {
                                    null
                                }
                            } else {
                                null
                            }
                        }
                        else -> null
                    }
                }
                .buffer(256, BufferOverflow.DROP_OLDEST)
                .throttlePerInstrument(350L)
                .flowOn(Dispatchers.Default)
                .collect { tick ->
                    universe[tick.symbol]?.let { inst ->
                        universe[tick.symbol] = inst.copy(ltp = tick.ltp)
                    }
                    _ticks.emit(tick)
                }
        }
    }

    private fun startPeriodicQuotePoller() {
        quotePollJob?.cancel()
        quotePollJob = appScope.launch(Dispatchers.IO) {
            while (isActive && activeWebSocket != null) {
                delay(3_000L)
                if (activeWebSocket != null && _liveStatus.value == LiveConnectionStatus.CONNECTED) {
                    runCatching { refreshRegisteredQuotesSnapshot() }
                }
            }
        }
    }

    /**
     * Resolves real current-month Angel One tokens from OpenAPIScripMaster for seeded
     * Futures/MCX watchlist rows so their prices update live instead of staying frozen on stale IDs.
     */
    private suspend fun resolveSeededDerivativeTokens() {
        val store = scripMasterStore ?: return
        if (store.ensureLoaded().isFailure) return

        val seededAliases = listOf(
            Triple("NIFTY26NOVFUT", "NIFTY", "NFO"),
            Triple("BANKNIFTY26NOVFUT", "BANKNIFTY", "NFO"),
            Triple("SENSEX26NOVFUT", "SENSEX", "BFO"),
            Triple("GOLD26DECFUT", "GOLD", "MCX"),
            Triple("SILVERM26DECFUT", "SILVERM", "MCX"),
            Triple("CRUDEOIL26NOVFUT", "CRUDEOIL", "MCX")
        )
        val newlyMapped = mutableListOf<SmartApiTokenMapping>()
        for ((watchlistSym, underlying, exch) in seededAliases) {
            val liveFut = store.findNearestActiveFuture(underlying, exch) ?: continue
            val mapping = SmartApiTokenMapping(
                symbol = watchlistSym,
                exchangeType = liveFut.exchangeTypeCode,
                token = liveFut.symbolToken
            )
            putTokenMapping(mapping)
            newlyMapped.add(mapping)
        }
        val ws = activeWebSocket
        if (ws != null && newlyMapped.isNotEmpty()) {
            ws.send(buildSubscriptionPayload(newlyMapped, mode = 1))
        }
    }

    override fun stop() {
        streamJob?.cancel()
        streamJob = null
        quotePollJob?.cancel()
        quotePollJob = null
        activeWebSocket = null
        // Keep cachedSession (already fingerprinted by apiKey|clientCode|pin and 4h TTL) so
        // immediate reconnects or post-diagnostic live starts do not trigger Angel One's 1 req/s login limit.
        _liveStatus.value = LiveConnectionStatus.DISCONNECTED
        _statusDetail.value = "Disconnected"
        _connectionState.value = FeedConnectionState.Disconnected(
            FeedSourceType.BROKER_ANGEL_ONE,
            "Angel One feed stopped"
        )
    }

    override suspend fun nudgePrice(symbol: String, percentDelta: BigDecimal) {
        val inst = universe[symbol] ?: return
        val factor = BigDecimal.ONE.add(percentDelta.divide(BigDecimal("100"), 6, RoundingMode.HALF_UP))
        val nudged = RiskMath.roundToTick(inst.ltp.multiply(factor).max(inst.tickSize), inst.tickSize)
        universe[symbol] = inst.copy(ltp = nudged)
        _ticks.emit(PriceTick(symbol, nudged, System.currentTimeMillis()))
    }

    /**
     * Searches live Angel One scrips (NSE/BSE stocks, NFO/BFO index & stock options/futures,
     * and ALL MCX commodities like NATURALGAS, CRUDEOIL, GOLD, SILVER, COPPER, ZINC).
     * Combines the official OpenAPIScripMaster (complete & fast) with SmartAPI REST searchScrip fallback.
     */
    suspend fun searchLiveScrips(
        rawQuery: String,
        segmentFilter: MarketSegment? = null,
        typeFilter: InstrumentType? = null
    ): Result<List<AngelScripSearchResult>> = withContext(Dispatchers.IO) {
        val query = rawQuery.trim()
        if (query.length < 2) return@withContext Result.success(emptyList())

        val combined = LinkedHashMap<String, AngelScripSearchResult>()

        // 1. Primary: Official Angel One OpenAPIScripMaster (covers ALL MCX commodities + full F&O + equities)
        if (scripMasterStore != null) {
            scripMasterStore.ensureLoaded()
            scripMasterStore.search(query, segmentFilter, typeFilter, limit = 30).forEach { item ->
                combined.putIfAbsent("${item.exchange}:${item.symbolToken}", item)
            }
        }

        // 2. Supplement / fallback: Authenticated SmartAPI searchScrip REST endpoint
        val creds = credentialsStore.getCurrent()
        var lastError: String? = null
        if (combined.size < 10 && creds.hasAngelOneCredentials()) {
            val session = runCatching { obtainOrRefreshSession(creds) }.getOrNull()
            if (session != null) {
                val exchanges = chooseSearchExchanges(query, segmentFilter, typeFilter)
                val normalizedQuery = normalizeSearchQuery(query)
                for (exchange in exchanges) {
                    val res = executeSearchScrip(
                        creds = creds,
                        session = session,
                        exchange = exchange,
                        searchText = normalizedQuery
                    )
                    res.onSuccess { list ->
                        list.forEach { item ->
                            if (typeFilter == null || item.instrumentType == typeFilter) {
                                combined.putIfAbsent("${item.exchange}:${item.symbolToken}", item)
                            }
                        }
                    }.onFailure { err ->
                        lastError = err.message
                    }
                    if (combined.size >= 30) break
                }
            }
        } else if (combined.isEmpty() && !creds.hasAngelOneCredentials()) {
            return@withContext Result.failure(
                IllegalStateException("Enter Angel One SmartAPI credentials to search live Indian stocks & options")
            )
        }

        if (combined.isEmpty() && lastError != null) {
            return@withContext Result.failure(IllegalStateException(lastError))
        }

        val upperQ = normalizeSearchQuery(query)
        val sorted = combined.values.sortedWith(
            compareBy<AngelScripSearchResult>(
                { !(it.tradingSymbol.equals(upperQ, ignoreCase = true) || it.symbol.equals(upperQ, ignoreCase = true)) },
                { !(it.tradingSymbol.startsWith(upperQ, ignoreCase = true)) },
                {
                    when (typeFilter) {
                        InstrumentType.OPTIONS -> if (it.instrumentType == InstrumentType.OPTIONS) 0 else 1
                        InstrumentType.FUTURES -> if (it.instrumentType == InstrumentType.FUTURES) 0 else 1
                        InstrumentType.STOCK -> if (it.instrumentType == InstrumentType.STOCK) 0 else 1
                        null -> when {
                            it.exchange == "MCX" && it.instrumentType == InstrumentType.FUTURES -> 0
                            it.tradingSymbol.endsWith("-EQ") -> 0
                            it.instrumentType == InstrumentType.FUTURES -> 1
                            else -> 2
                        }
                    }
                },
                { OptionGreeksCalculator.parseExpiryToMillis(it.expiryLabel.orEmpty()) ?: Long.MAX_VALUE },
                { it.tradingSymbol }
            )
        ).take(30)

        Result.success(sorted)
    }

    suspend fun ensureScripMasterLoaded(forceRefresh: Boolean = false): Result<Int> {
        val store = scripMasterStore
            ?: return Result.failure(IllegalStateException("Scrip master unavailable"))
        return store.ensureLoaded(forceRefresh)
    }

    suspend fun getOptionChainExpiries(underlying: String): Result<List<String>> = withContext(Dispatchers.IO) {
        val store = scripMasterStore
            ?: return@withContext Result.failure(IllegalStateException("Scrip master unavailable"))
        val loaded = store.ensureLoaded()
        if (loaded.isFailure && !store.isLoaded()) {
            return@withContext Result.failure(
                loaded.exceptionOrNull() ?: IllegalStateException("Could not load Angel One Scrip Master")
            )
        }
        val expiries = store.getAvailableExpiries(underlying)
        if (expiries.isEmpty()) {
            Result.failure(IllegalStateException("No active option expiries found for ${underlying.uppercase()}"))
        } else {
            Result.success(expiries)
        }
    }

    /**
     * Fetches a live Option Chain snapshot with real LTP, Net Change, Open Interest (OI),
     * Volume, PCR, Max Pain, and Option Greeks (Angel One optionGreek API with automatic
     * Black-Scholes-Merton IV/Greeks computation for after-hours, BSE, and MCX contracts).
     */
    suspend fun fetchLiveOptionChainSnapshot(
        underlying: String,
        requestedExpiry: String? = null,
        strikeWindow: Int = 12,
        previousOiMap: Map<String, Long> = emptyMap()
    ): Result<OptionChainSnapshot> = withContext(Dispatchers.IO) {
        val store = scripMasterStore
            ?: return@withContext Result.failure(IllegalStateException("Scrip master unavailable"))
        val creds = credentialsStore.getCurrent()
        if (!creds.hasAngelOneCredentials()) {
            return@withContext Result.failure(
                IllegalStateException("Enter Angel One SmartAPI credentials in Feed Settings to load live Option Chain")
            )
        }

        val loaded = store.ensureLoaded()
        if (loaded.isFailure && !store.isLoaded()) {
            return@withContext Result.failure(
                loaded.exceptionOrNull() ?: IllegalStateException("Failed to load Angel One Scrip Master")
            )
        }

        val canonical = AngelScripMasterStore.canonicalUnderlying(underlying)
        val expiries = store.getAvailableExpiries(canonical)
        if (expiries.isEmpty()) {
            return@withContext Result.failure(
                IllegalStateException("No active option contracts found for $canonical")
            )
        }
        val expiry = requestedExpiry?.takeIf { e -> expiries.any { it.equals(e, true) } } ?: expiries.first()
        val allContracts = store.getOptionChainContracts(canonical, expiry)
        if (allContracts.isEmpty()) {
            return@withContext Result.failure(
                IllegalStateException("No option strikes found for $canonical ($expiry)")
            )
        }

        val session = try {
            obtainOrRefreshSession(creds)
        } catch (t: Throwable) {
            return@withContext Result.failure(t)
        }

        // 1. Fetch live Spot Price for the underlying (Index Spot / Stock EQ / Near-month MCX Future)
        val spotInfo = store.getSpotLookup(canonical)
        val spotQuote = spotInfo?.let { info ->
            fetchBatchQuotes(creds, session, mapOf(info.exchange to listOf(info.token)))["${info.exchange}:${info.token}"]
        }

        val distinctStrikes = allContracts.mapNotNull { it.strikePrice }.distinct().sorted()
        val spotPrice = spotQuote?.ltp?.takeIf { it > BigDecimal.ZERO }
            ?: distinctStrikes[distinctStrikes.size / 2]
        val spotClose = spotQuote?.close?.takeIf { it > BigDecimal.ZERO } ?: spotPrice
        val spotChange = spotQuote?.netChange ?: spotPrice.subtract(spotClose)
        val spotChangePct = spotQuote?.percentChange ?: if (spotClose > BigDecimal.ZERO) {
            spotChange.multiply(BigDecimal("100")).divide(spotClose, 2, RoundingMode.HALF_UP)
        } else {
            BigDecimal.ZERO
        }

        // 2. Select ±strikeWindow strikes centered around live ATM strike
        val atmStrike = distinctStrikes.minByOrNull { it.subtract(spotPrice).abs() } ?: distinctStrikes.first()
        val atmIdx = distinctStrikes.indexOf(atmStrike).coerceAtLeast(0)
        val startIdx = (atmIdx - strikeWindow).coerceAtLeast(0)
        val endIdx = (atmIdx + strikeWindow).coerceAtMost(distinctStrikes.lastIndex)
        val visibleStrikes = distinctStrikes.subList(startIdx, endIdx + 1)
        val visibleStrikeSet = visibleStrikes.toHashSet()
        val visibleContracts = allContracts.filter { it.strikePrice in visibleStrikeSet }

        // 3. Fetch FULL quotes (LTP + OI + Volume + Change) & Broker Option Greeks in parallel
        val (quotesMap, brokerGreeksMap) = coroutineScope {
            val quotesDeferred = async {
                val exchangeGroups = visibleContracts.groupBy { it.exchange }
                    .mapValues { (_, list) -> list.map { it.symbolToken }.distinct() }
                fetchBatchQuotesChunked(creds, session, exchangeGroups)
            }
            val greeksDeferred = async {
                val primaryExch = visibleContracts.firstOrNull()?.exchange ?: "NFO"
                if (primaryExch == "NFO") {
                    fetchOptionGreeks(creds, session, canonical, expiry)
                } else {
                    emptyMap()
                }
            }
            quotesDeferred.await() to greeksDeferred.await()
        }

        // Subscribe visible option strikes + spot on live SmartStream V2 WebSocket (Snap Quote mode)
        val streamMappings = buildList {
            spotInfo?.let {
                add(SmartApiTokenMapping(symbol = "SPOT:${it.underlying}", exchangeType = it.exchangeTypeCode, token = it.token))
            }
            visibleContracts.forEach { c ->
                add(SmartApiTokenMapping(symbol = c.symbol, exchangeType = c.exchangeTypeCode, token = c.symbolToken))
            }
        }
        subscribeTemporaryTokens(streamMappings, mode = 3)

        val nowMillis = System.currentTimeMillis()
        val timeToExpiryYears = OptionGreeksCalculator.parseExpiryToYears(expiry, nowMillis)
        val spotDouble = spotPrice.toDouble()
        var brokerGreeksHits = 0

        val contractsByStrikeAndType = visibleContracts.associateBy {
            "${it.strikePrice?.setScale(2, RoundingMode.HALF_UP)}:${it.optionType?.name}"
        }

        var totalCallOi = 0L
        var totalPutOi = 0L

        val rows = visibleStrikes.map { strike ->
            val strikeKey = strike.setScale(2, RoundingMode.HALF_UP)
            val ceContract = contractsByStrikeAndType["$strikeKey:CE"]
            val peContract = contractsByStrikeAndType["$strikeKey:PE"]

            fun buildLeg(contract: AngelScripSearchResult?, isCall: Boolean): OptionLegQuote? {
                if (contract == null) return null
                val q = quotesMap["${contract.exchange}:${contract.symbolToken}"]
                val ltp = q?.ltp ?: BigDecimal.ZERO
                val prevClose = q?.close?.takeIf { it > BigDecimal.ZERO } ?: ltp
                val netChg = q?.netChange ?: if (ltp > BigDecimal.ZERO) ltp.subtract(prevClose) else BigDecimal.ZERO
                val pctChg = q?.percentChange ?: if (prevClose > BigDecimal.ZERO && ltp > BigDecimal.ZERO) {
                    netChg.multiply(BigDecimal("100")).divide(prevClose, 2, RoundingMode.HALF_UP)
                } else {
                    BigDecimal.ZERO
                }
                val oi = q?.openInterest ?: 0L
                val tokenKey = "${contract.exchangeTypeCode}:${contract.symbolToken}"
                val prevOi = previousOiMap[tokenKey]
                val oiDelta = if (prevOi != null && oi > 0L) oi - prevOi else 0L
                val vol = q?.tradeVolume ?: 0L

                val brokerGreek = brokerGreeksMap["$strikeKey:${if (isCall) "CE" else "PE"}"]
                val resolvedGreeks = if (brokerGreek != null) {
                    brokerGreeksHits++
                    brokerGreek
                } else {
                    OptionGreeksCalculator.computeGreeks(
                        spot = spotDouble,
                        strike = strike.toDouble(),
                        timeToExpiryYears = timeToExpiryYears,
                        optionPrice = ltp.toDouble(),
                        isCall = isCall
                    )
                }

                return OptionLegQuote(
                    symbol = contract.symbol,
                    tradingSymbol = contract.tradingSymbol,
                    exchange = contract.exchange,
                    symbolToken = contract.symbolToken,
                    exchangeTypeCode = contract.exchangeTypeCode,
                    segment = contract.segment,
                    lotSize = contract.lotSize,
                    tickSize = contract.tickSize,
                    strikePrice = strike,
                    optionType = if (isCall) OptionType.CE else OptionType.PE,
                    expiryLabel = expiry,
                    ltp = ltp,
                    netChange = netChg,
                    changePercent = pctChg,
                    openInterest = oi,
                    oiChange = oiDelta,
                    volume = vol,
                    greeks = resolvedGreeks
                )
            }

            val callLeg = buildLeg(ceContract, isCall = true)
            val putLeg = buildLeg(peContract, isCall = false)
            totalCallOi += callLeg?.openInterest ?: 0L
            totalPutOi += putLeg?.openInterest ?: 0L

            OptionChainRow(
                strikePrice = strike,
                isAtm = strike.compareTo(atmStrike) == 0,
                call = callLeg,
                put = putLeg
            )
        }

        val pcr = if (totalCallOi > 0L) totalPutOi.toDouble() / totalCallOi.toDouble() else 0.0
        val maxPain = OptionGreeksCalculator.computeMaxPain(rows)
        val atmRow = rows.firstOrNull { it.isAtm }
        val atmIv = listOfNotNull(atmRow?.call?.greeks?.ivPercent, atmRow?.put?.greeks?.ivPercent)
            .filter { it > 0.0 }
            .takeIf { it.isNotEmpty() }
            ?.average()

        val greeksLabel = when {
            brokerGreeksHits >= rows.size -> "Angel One Greeks"
            brokerGreeksHits > 0 -> "Angel One + BSM Greeks"
            else -> "Live BSM Greeks"
        }

        Result.success(
            OptionChainSnapshot(
                underlying = canonical,
                exchange = visibleContracts.firstOrNull()?.exchange ?: "NFO",
                spotPrice = spotPrice,
                spotChange = spotChange,
                spotChangePercent = spotChangePct,
                expiry = expiry,
                availableExpiries = expiries,
                rows = rows,
                atmStrike = atmStrike,
                totalCallOi = totalCallOi,
                totalPutOi = totalPutOi,
                pcr = pcr,
                maxPainStrike = maxPain,
                atmIvPercent = atmIv,
                greeksSourceLabel = greeksLabel,
                updatedAtMillis = nowMillis
            )
        )
    }

    /**
     * Fetches the live FULL / OHLC / LTP quote for a single Angel One scrip so newly added instruments
     * immediately display real market price even before the next WebSocket tick or during off-hours.
     */
    suspend fun fetchQuoteForToken(
        exchange: String,
        symbolToken: String
    ): AngelQuoteSnapshot? = withContext(Dispatchers.IO) {
        val creds = credentialsStore.getCurrent()
        if (!creds.hasAngelOneCredentials()) return@withContext null
        val session = runCatching { obtainOrRefreshSession(creds) }.getOrNull() ?: return@withContext null
        fetchBatchQuotes(creds, session, mapOf(exchange to listOf(symbolToken)))["$exchange:$symbolToken"]
    }

    private suspend fun refreshRegisteredQuotesSnapshot() {
        val creds = credentialsStore.getCurrent()
        if (!creds.hasAngelOneCredentials()) return
        val session = runCatching { obtainOrRefreshSession(creds) }.getOrNull() ?: return
        val grouped = registeredTokensBySymbol.values
            .groupBy { exchangeCodeToName(it.exchangeType) }
            .mapValues { (_, list) -> list.map { it.token }.distinct() }
            .filterKeys { it.isNotBlank() }
        if (grouped.isEmpty()) return

        val quotes = fetchBatchQuotesChunked(creds, session, grouped)
        val now = System.currentTimeMillis()
        registeredTokensBySymbol.values.forEach { mapping ->
            val exchName = exchangeCodeToName(mapping.exchangeType)
            val snap = quotes["$exchName:${mapping.token}"] ?: return@forEach
            val inst = universe[mapping.symbol] ?: return@forEach
            if (snap.ltp > BigDecimal.ZERO) {
                val roundedLtp = RiskMath.roundToTick(snap.ltp, inst.tickSize)
                universe[mapping.symbol] = inst.copy(
                    ltp = roundedLtp,
                    previousClose = snap.close?.takeIf { it > BigDecimal.ZERO } ?: inst.previousClose,
                    dayHigh = snap.high?.takeIf { it > BigDecimal.ZERO } ?: inst.dayHigh,
                    dayLow = snap.low?.takeIf { it > BigDecimal.ZERO } ?: inst.dayLow,
                    volume = snap.tradeVolume?.takeIf { it > 0L } ?: inst.volume
                )
                _tokenTicks.tryEmit(
                    TokenLiveTick(
                        exchangeType = mapping.exchangeType,
                        token = mapping.token,
                        ltp = roundedLtp,
                        openInterest = snap.openInterest,
                        volume = snap.tradeVolume,
                        timestampMillis = now
                    )
                )
                _ticks.emit(
                    PriceTick(
                        symbol = inst.symbol,
                        ltp = roundedLtp,
                        timestampMillis = now,
                        previousClose = snap.close?.takeIf { it > BigDecimal.ZERO },
                        dayHigh = snap.high?.takeIf { it > BigDecimal.ZERO },
                        dayLow = snap.low?.takeIf { it > BigDecimal.ZERO },
                        volume = snap.tradeVolume?.takeIf { it > 0L }
                    )
                )
            }
        }
    }

    private fun executeSearchScrip(
        creds: BrokerFeedSettings,
        session: AngelSessionTokens,
        exchange: String,
        searchText: String
    ): Result<List<AngelScripSearchResult>> {
        val payload = JSONObject().apply {
            put("exchange", exchange)
            put("searchscrip", searchText)
        }
        val req = buildAuthenticatedRestRequest(
            url = "https://apiconnect.angelone.in/rest/secure/angelbroking/order/v1/searchScrip",
            payload = payload,
            creds = creds,
            session = session
        )
        return runCatching {
            restClient.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IllegalStateException("Search failed (HTTP ${resp.code})")
                }
                val json = JSONObject(body)
                if (!json.optBoolean("status", false)) {
                    val msg = json.optString("message").ifBlank { "Search failed" }
                    val code = json.optString("errorcode")
                    if (code.equals("AG8001", ignoreCase = true) || code.equals("AG8002", ignoreCase = true)) {
                        cachedSession = null
                    }
                    throw IllegalStateException(if (code.isNotBlank()) "$msg ($code)" else msg)
                }
                val dataArr = json.optJSONArray("data") ?: JSONArray()
                val out = mutableListOf<AngelScripSearchResult>()
                for (i in 0 until dataArr.length()) {
                    val obj = dataArr.optJSONObject(i) ?: continue
                    parseAngelScripResult(obj, fallbackExchange = exchange)?.let { out.add(it) }
                }
                out
            }
        }
    }

    private fun fetchBatchQuotesChunked(
        creds: BrokerFeedSettings,
        session: AngelSessionTokens,
        exchangeTokens: Map<String, List<String>>
    ): Map<String, AngelQuoteSnapshot> {
        val merged = mutableMapOf<String, AngelQuoteSnapshot>()
        // Query per exchange in batches of 45 so an invalid/closed token on one exchange
        // can never block live quotes on other exchanges!
        exchangeTokens.forEach { (exch, tokens) ->
            tokens.distinct().chunked(45).forEach { chunk ->
                merged.putAll(fetchBatchQuotes(creds, session, mapOf(exch to chunk)))
            }
        }
        return merged
    }

    private fun fetchBatchQuotes(
        creds: BrokerFeedSettings,
        session: AngelSessionTokens,
        exchangeTokens: Map<String, List<String>>
    ): Map<String, AngelQuoteSnapshot> {
        val exchObj = JSONObject()
        exchangeTokens.forEach { (exch, tokens) ->
            if (tokens.isNotEmpty()) {
                exchObj.put(exch, JSONArray(tokens))
            }
        }
        if (exchObj.length() == 0) return emptyMap()

        for (mode in listOf("FULL", "OHLC", "LTP")) {
            val payload = JSONObject().apply {
                put("mode", mode)
                put("exchangeTokens", exchObj)
            }
            val req = buildAuthenticatedRestRequest(
                url = "https://apiconnect.angelone.in/rest/secure/angelbroking/market/v1/quote/",
                payload = payload,
                creds = creds,
                session = session
            )
            val parsed = runCatching {
                restClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val body = resp.body?.string().orEmpty()
                    val json = JSONObject(body)
                    if (!json.optBoolean("status", false)) {
                        val code = json.optString("errorcode")
                        if (code.equals("AG8001", ignoreCase = true) || code.equals("AG8002", ignoreCase = true)) {
                            cachedSession = null
                        }
                        return@use null
                    }
                    val fetched = json.optJSONObject("data")?.optJSONArray("fetched") ?: return@use null
                    val result = mutableMapOf<String, AngelQuoteSnapshot>()
                    for (i in 0 until fetched.length()) {
                        val row = fetched.optJSONObject(i) ?: continue
                        val exch = row.optString("exchange").uppercase()
                        val tok = row.optString("symbolToken").ifBlank { row.optString("symboltoken") }
                        val ltpDouble = row.optDouble("ltp", Double.NaN)
                        if (exch.isBlank() || tok.isBlank() || ltpDouble.isNaN() || ltpDouble <= 0.0) continue
                        val open = row.optDouble("open", Double.NaN).takeIf { !it.isNaN() && it > 0.0 }
                            ?.let { BigDecimal.valueOf(it).setScale(2, RoundingMode.HALF_UP) }
                        val high = row.optDouble("high", Double.NaN).takeIf { !it.isNaN() && it > 0.0 }
                            ?.let { BigDecimal.valueOf(it).setScale(2, RoundingMode.HALF_UP) }
                        val low = row.optDouble("low", Double.NaN).takeIf { !it.isNaN() && it > 0.0 }
                            ?.let { BigDecimal.valueOf(it).setScale(2, RoundingMode.HALF_UP) }
                        val close = row.optDouble("close", Double.NaN).takeIf { !it.isNaN() && it > 0.0 }
                            ?.let { BigDecimal.valueOf(it).setScale(2, RoundingMode.HALF_UP) }
                        val netChg = row.optDouble("netChange", Double.NaN).takeIf { !it.isNaN() }
                            ?.let { BigDecimal.valueOf(it).setScale(2, RoundingMode.HALF_UP) }
                        val pctChg = row.optDouble("percentChange", Double.NaN).takeIf { !it.isNaN() }
                            ?.let { BigDecimal.valueOf(it).setScale(2, RoundingMode.HALF_UP) }
                        val tradeVol = row.optLong("tradeVolume", -1L).takeIf { it >= 0L }
                        val oi = row.optLong("opnInterest", -1L).takeIf { it >= 0L }
                            ?: row.optLong("openInterest", -1L).takeIf { it >= 0L }
                        result["$exch:$tok"] = AngelQuoteSnapshot(
                            ltp = BigDecimal.valueOf(ltpDouble).setScale(2, RoundingMode.HALF_UP),
                            open = open,
                            high = high,
                            low = low,
                            close = close,
                            netChange = netChg,
                            percentChange = pctChg,
                            tradeVolume = tradeVol,
                            openInterest = oi
                        )
                    }
                    result
                }
            }.getOrNull()
            if (!parsed.isNullOrEmpty()) return parsed
        }
        return emptyMap()
    }

    private fun fetchOptionGreeks(
        creds: BrokerFeedSettings,
        session: AngelSessionTokens,
        underlying: String,
        expiry: String
    ): Map<String, OptionGreeks> {
        val payload = JSONObject().apply {
            put("name", underlying.uppercase())
            put("expirydate", expiry.uppercase())
        }
        for (url in OPTION_GREEK_ENDPOINTS) {
            val req = buildAuthenticatedRestRequest(
                url = url,
                payload = payload,
                creds = creds,
                session = session
            )
            val parsed = runCatching {
                restClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val body = resp.body?.string().orEmpty()
                    parseOptionGreekResponse(body)
                }
            }.getOrNull()
            if (!parsed.isNullOrEmpty()) return parsed
        }
        return emptyMap()
    }

    private fun buildAuthenticatedRestRequest(
        url: String,
        payload: JSONObject,
        creds: BrokerFeedSettings,
        session: AngelSessionTokens
    ): Request {
        val cleanJwt = normalizeJwtToken(session.jwtToken)
        return Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .addHeader("Authorization", "Bearer $cleanJwt")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json")
            .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36")
            .addHeader("X-UserType", "USER")
            .addHeader("X-SourceID", "WEB")
            .addHeader("X-ClientLocalIP", "127.0.0.1")
            .addHeader("X-ClientPublicIP", "127.0.0.1")
            .addHeader("X-MACAddress", "02:00:00:00:00:00")
            .addHeader("X-PrivateKey", creds.angelOneApiKey.trim())
            .build()
    }

    data class DiagnosticResult(
        val connected: Boolean,
        val summary: String
    )

    /**
     * Runs an explicit 2-step live connection diagnostic (REST loginByPassword -> SmartStream WS
     * handshake) so the user can immediately see the exact HTTP code, Angel One errorcode, or
     * WebSocket x-error-message without guessing.
     */
    suspend fun diagnoseConnection(creds: BrokerFeedSettings): DiagnosticResult = withContext(Dispatchers.IO) {
        if (!creds.hasAngelOneCredentials()) {
            return@withContext DiagnosticResult(
                connected = false,
                summary = "Missing API Key, Client Code, MPIN, or TOTP Secret."
            )
        }
        val session = try {
            obtainOrRefreshSession(creds)
        } catch (t: Throwable) {
            return@withContext DiagnosticResult(
                connected = false,
                summary = "Step 1 (REST Login) FAILED: ${t.message ?: t.javaClass.simpleName}"
            )
        }

        // Step 2: Probe SmartStream V2 handshake across official variants
        var lastWsError = "Unknown handshake error"
        for (variant in 0..2) {
            val req = buildSmartStreamRequest(creds, session, attemptIndex = variant)
            val result = probeWebSocketHandshake(req)
            if (result.first) {
                delay(200L) // Allow Angel One to register clean close of diagnostic socket
                return@withContext DiagnosticResult(
                    connected = true,
                    summary = "Step 1 (REST Login): OK (JWT & FeedToken issued)\n" +
                        "Step 2 (SmartStream WS): CONNECTED (variant ${variant + 1}, HTTP 101)"
                )
            }
            lastWsError = result.second
        }
        return@withContext DiagnosticResult(
            connected = false,
            summary = "Step 1 (REST Login): OK (JWT & FeedToken issued)\n" +
                "Step 2 (SmartStream WS) FAILED: $lastWsError"
        )
    }

    private suspend fun probeWebSocketHandshake(request: Request): Pair<Boolean, String> {
        val deferred = CompletableDeferred<Pair<Boolean, String>>()
        var ws: WebSocket? = null
        ws = smartStreamWsClient.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    deferred.complete(true to "HTTP ${response.code}")
                    webSocket.close(1000, "Diagnostic complete")
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val headerErr = response?.header("x-error-message")
                        ?.takeIf { it.isNotBlank() }
                        ?: response?.header("X-Error-Message")?.takeIf { it.isNotBlank() }
                        ?: runCatching { response?.body?.string()?.trim()?.take(140)?.takeIf { it.isNotBlank() } }.getOrNull()
                    val codePart = response?.code?.let { "HTTP $it" } ?: ""
                    val msg = listOfNotNull(
                        codePart.takeIf { it.isNotBlank() },
                        headerErr ?: t.message
                    ).joinToString(" — ").ifBlank { t.javaClass.simpleName }
                    deferred.complete(false to msg)
                }
            }
        )
        val outcome = withTimeoutOrNull(10_000L) { deferred.await() }
        if (outcome == null) {
            ws.cancel()
            return false to "Timed out after 10s waiting for WebSocket handshake"
        }
        return outcome
    }

    private suspend fun obtainOrRefreshSession(creds: BrokerFeedSettings): AngelSessionTokens {
        return sessionMutex.withLock {
            val fingerprint = "${creds.angelOneApiKey.trim()}|${creds.angelOneClientCode.trim().uppercase()}|${creds.angelOnePin.trim()}"
            val existing = cachedSession
            val now = System.currentTimeMillis()
            if (existing != null &&
                existing.fingerprint == fingerprint &&
                (now - existing.createdAtMillis) < SESSION_CACHE_TTL_MS
            ) {
                return@withLock existing.tokens
            }

            val tokens = withContext(Dispatchers.IO) {
                loginSmartApiSessionWithClockSync(creds)
            }
            cachedSession = CachedSession(fingerprint, tokens, System.currentTimeMillis())
            tokens
        }
    }

    private suspend fun loginSmartApiSessionWithClockSync(creds: BrokerFeedSettings): AngelSessionTokens {
        val firstAttempt = executeLoginAcrossHosts(creds, timeMillis = System.currentTimeMillis() + serverClockOffsetMs)
        if (firstAttempt.tokens != null) {
            return firstAttempt.tokens
        }

        // If login failed with a TOTP error, determine authoritative UTC time from Angel One's
        // HTTP Date header (or Binance UTC time endpoint fallback), sync offset, and retry once.
        val isTotpError = firstAttempt.errorMessage.contains("totp", ignoreCase = true) ||
            firstAttempt.errorCode.equals("AB1050", ignoreCase = true)

        if (isTotpError) {
            val serverTime = firstAttempt.serverDateMillis ?: fetchUtcTimeFallbackMillis()
            if (serverTime != null) {
                val newOffset = serverTime - System.currentTimeMillis()
                val prevOffset = serverClockOffsetMs
                serverClockOffsetMs = newOffset
                Log.w(TAG, "Syncing Angel One TOTP clock offset: ${newOffset}ms (prev ${prevOffset}ms)")
                delay(1_150L) // Respect Angel One 1 req/s login rate limit
                val retryTime = if (kotlin.math.abs(newOffset - prevOffset) >= 1_000L) {
                    System.currentTimeMillis() + serverClockOffsetMs
                } else {
                    // Already synced; nudge across potential 30s TOTP boundary
                    System.currentTimeMillis() + serverClockOffsetMs + 15_000L
                }
                val retryAttempt = executeLoginAcrossHosts(creds, timeMillis = retryTime)
                if (retryAttempt.tokens != null) {
                    return retryAttempt.tokens
                }
                throw IllegalStateException(retryAttempt.formattedError())
            }
        }

        throw IllegalStateException(firstAttempt.formattedError())
    }

    private fun executeLoginAcrossHosts(creds: BrokerFeedSettings, timeMillis: Long): LoginAttemptResult {
        var lastResult: LoginAttemptResult? = null
        for ((idx, url) in LOGIN_ENDPOINTS.withIndex()) {
            val attempt = try {
                executeLoginRequest(url, creds, timeMillis)
            } catch (t: Throwable) {
                LoginAttemptResult(errorMessage = "${t.javaClass.simpleName}: ${t.message ?: "Network error"}")
            }
            if (attempt.tokens != null) {
                return attempt
            }
            lastResult = attempt
            // Only try the legacy fallback host on network/WAF/5xx failures — never retry broker credential rejections
            val shouldFallbackHost = idx == 0 && (
                attempt.httpCode == null ||
                    attempt.httpCode == 403 ||
                    attempt.httpCode >= 500 ||
                    attempt.errorCode.isBlank()
                )
            if (!shouldFallbackHost) {
                return attempt
            }
        }
        return lastResult ?: LoginAttemptResult(errorMessage = "SmartAPI login failed")
    }

    private fun fetchUtcTimeFallbackMillis(): Long? {
        return runCatching {
            val req = Request.Builder()
                .url("https://data-api.binance.vision/api/v3/time")
                .get()
                .build()
            restClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use resp.headers.getDate("Date")?.time
                val body = resp.body?.string().orEmpty()
                val json = JSONObject(body)
                json.optLong("serverTime", 0L).takeIf { it > 0L }
                    ?: resp.headers.getDate("Date")?.time
            }
        }.getOrNull()
    }

    private data class LoginAttemptResult(
        val tokens: AngelSessionTokens? = null,
        val httpCode: Int? = null,
        val errorCode: String = "",
        val errorMessage: String = "",
        val serverDateMillis: Long? = null
    ) {
        fun formattedError(): String {
            return if (errorCode.isNotBlank()) "$errorMessage ($errorCode)" else errorMessage
        }
    }

    private fun executeLoginRequest(
        loginUrl: String,
        creds: BrokerFeedSettings,
        timeMillis: Long
    ): LoginAttemptResult {
        val apiKey = creds.angelOneApiKey.trim()
        val clientCode = creds.angelOneClientCode.trim().uppercase()
        val pin = creds.angelOnePin.trim()
        val totp = generateTotp(creds.angelOneTotpSecret, timeMillis)

        val payload = JSONObject().apply {
            put("clientcode", clientCode)
            put("password", pin)
            put("totp", totp)
            put("state", "live")
        }
        val request = Request.Builder()
            .url(loginUrl)
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json")
            .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36")
            .addHeader("X-UserType", "USER")
            .addHeader("X-SourceID", "WEB")
            .addHeader("X-ClientLocalIP", "127.0.0.1")
            .addHeader("X-ClientPublicIP", "127.0.0.1")
            .addHeader("X-MACAddress", "02:00:00:00:00:00")
            .addHeader("X-PrivateKey", apiKey)
            .build()

        restClient.newCall(request).execute().use { response ->
            val serverDateMillis = response.headers.getDate("Date")?.time
            val bodyStr = response.body?.string().orEmpty()
            val parsedJson = runCatching { JSONObject(bodyStr) }.getOrNull()

            if (!response.isSuccessful) {
                val jsonMsg = parsedJson?.optString("message")?.takeIf { it.isNotBlank() }
                val code = parsedJson?.optString("errorcode").orEmpty()
                val isWafOrGeoBlock = response.code == 403 && (
                    parsedJson == null ||
                        bodyStr.contains("cloudflare", ignoreCase = true) ||
                        bodyStr.contains("access denied", ignoreCase = true) ||
                        bodyStr.contains("<html", ignoreCase = true)
                    )
                val msg = when {
                    jsonMsg != null -> "$jsonMsg (HTTP ${response.code})"
                    isWafOrGeoBlock -> "HTTP 403 blocked by Angel One WAF/region firewall (cloud/non-India IP may be restricted)"
                    else -> {
                        val snippet = bodyStr.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim().take(90)
                        if (snippet.isNotBlank()) "SmartAPI HTTP ${response.code}: $snippet"
                        else "SmartAPI login failed (HTTP ${response.code})"
                    }
                }
                Log.w(TAG, "SmartAPI login HTTP ${response.code}: $msg [$code]")
                return LoginAttemptResult(
                    httpCode = response.code,
                    errorCode = code,
                    errorMessage = msg,
                    serverDateMillis = serverDateMillis
                )
            }

            if (parsedJson == null) {
                return LoginAttemptResult(
                    httpCode = response.code,
                    errorMessage = "Invalid non-JSON SmartAPI login response (HTTP ${response.code})",
                    serverDateMillis = serverDateMillis
                )
            }

            if (!parsedJson.optBoolean("status", false)) {
                val msg = parsedJson.optString("message").ifBlank { "SmartAPI auth rejected" }
                val code = parsedJson.optString("errorcode").orEmpty()
                Log.w(TAG, "SmartAPI login rejected: $msg [$code]")
                return LoginAttemptResult(
                    httpCode = response.code,
                    errorCode = code,
                    errorMessage = msg,
                    serverDateMillis = serverDateMillis
                )
            }

            val data = parsedJson.optJSONObject("data")
                ?: return LoginAttemptResult(
                    httpCode = response.code,
                    errorMessage = "SmartAPI login returned empty session data",
                    serverDateMillis = serverDateMillis
                )
            val jwt = normalizeJwtToken(data.optString("jwtToken"))
            val feed = data.optString("feedToken").trim()
            if (jwt.isBlank() || feed.isBlank()) {
                return LoginAttemptResult(
                    httpCode = response.code,
                    errorMessage = "SmartAPI login missing jwtToken or feedToken",
                    serverDateMillis = serverDateMillis
                )
            }

            if (serverDateMillis != null) {
                serverClockOffsetMs = serverDateMillis - System.currentTimeMillis()
            }
            Log.d(TAG, "SmartAPI login succeeded for $clientCode")
            return LoginAttemptResult(
                tokens = AngelSessionTokens(jwtToken = jwt, feedToken = feed),
                httpCode = response.code,
                serverDateMillis = serverDateMillis
            )
        }
    }

    /**
     * Fetches historical OHLCV candles from SmartAPI getCandleData for a registered symbol.
     * [interval] is one of ONE_MINUTE, THREE_MINUTE, FIVE_MINUTE, TEN_MINUTE, FIFTEEN_MINUTE,
     * THIRTY_MINUTE, ONE_HOUR, ONE_DAY. Times are exchange (IST) times.
     */
    suspend fun fetchHistoricalCandles(
        symbol: String,
        interval: String,
        fromMillis: Long,
        toMillis: Long
    ): Result<List<com.example.domain.signal.Candle>> = withContext(Dispatchers.IO) {
        val creds = credentialsStore.getCurrent()
        if (!creds.hasAngelOneCredentials()) {
            return@withContext Result.failure(IllegalStateException("Angel One credentials not configured"))
        }
        val mapping = registeredTokensBySymbol[symbol]
            ?: return@withContext Result.failure(IllegalStateException("No Angel One token mapped for $symbol"))
        val exchange = exchangeCodeToName(mapping.exchangeType)
        if (exchange.isBlank()) {
            return@withContext Result.failure(IllegalStateException("Unknown exchange for $symbol"))
        }
        val session = runCatching { obtainOrRefreshSession(creds) }.getOrElse {
            return@withContext Result.failure(it)
        }
        val ist = java.time.ZoneId.of("Asia/Kolkata")
        val formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ist)
        val payload = JSONObject().apply {
            put("exchange", exchange)
            put("symboltoken", mapping.token)
            put("interval", interval)
            put("fromdate", formatter.format(java.time.Instant.ofEpochMilli(fromMillis)))
            put("todate", formatter.format(java.time.Instant.ofEpochMilli(toMillis)))
        }
        val request = buildAuthenticatedRestRequest(
            url = "https://apiconnect.angelone.in/rest/secure/angelbroking/historical/v1/getCandleData",
            payload = payload,
            creds = creds,
            session = session
        )
        runCatching {
            restClient.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) error("getCandleData HTTP ${resp.code}")
                val json = JSONObject(body)
                if (!json.optBoolean("status", false)) {
                    val code = json.optString("errorcode")
                    if (code.equals("AG8001", ignoreCase = true) || code.equals("AG8002", ignoreCase = true)) {
                        cachedSession = null
                    }
                    error("getCandleData failed: ${json.optString("message")} $code")
                }
                val rows = json.optJSONArray("data") ?: JSONArray()
                val out = ArrayList<com.example.domain.signal.Candle>(rows.length())
                for (i in 0 until rows.length()) {
                    val row = rows.optJSONArray(i) ?: continue
                    if (row.length() < 6) continue
                    val ts = runCatching {
                        java.time.OffsetDateTime.parse(row.getString(0)).toInstant()
                    }.getOrNull() ?: continue
                    val o = row.optDouble(1, Double.NaN)
                    val h = row.optDouble(2, Double.NaN)
                    val l = row.optDouble(3, Double.NaN)
                    val c = row.optDouble(4, Double.NaN)
                    val v = row.optDouble(5, 0.0)
                    if (o.isNaN() || h.isNaN() || l.isNaN() || c.isNaN() || c <= 0.0) continue
                    out.add(com.example.domain.signal.Candle(ts, o, h, l, c, if (v.isNaN()) 0.0 else v))
                }
                out.toList()
            }
        }
    }

    companion object {
        private const val TAG = "AngelOnePriceFeed"
        private const val SESSION_CACHE_TTL_MS = 4 * 60 * 60 * 1000L // 4 hours
        internal const val CORRELATION_ID = "ppulse0001" // Strictly 10-char alphanumeric per SmartStream V2 spec

        internal val LOGIN_ENDPOINTS = listOf(
            "https://apiconnect.angelone.in/rest/auth/angelbroking/user/v1/loginByPassword",
            "https://apiconnect.angelbroking.com/rest/auth/angelbroking/user/v1/loginByPassword"
        )

        internal val OPTION_GREEK_ENDPOINTS = listOf(
            "https://apiconnect.angelone.in/rest/secure/angelbroking/marketData/v1/optionGreek",
            "https://apiconnect.angelbroking.com/rest/secure/angelbroking/marketData/v1/optionGreek"
        )

        // Verified static Angel One tokens for seeded NSE/BSE cash equities.
        // Active current-month NFO/BFO/MCX futures & options tokens are resolved dynamically via AngelScripMasterStore.
        val TOKEN_MAP = listOf(
            SmartApiTokenMapping("RELIANCE", 1, "2885"),
            SmartApiTokenMapping("TCS", 1, "11536"),
            SmartApiTokenMapping("HDFCBANK", 1, "1333"),
            SmartApiTokenMapping("TATAMOTORS", 1, "3456"),
            SmartApiTokenMapping("BSE:SBIN", 3, "500112"),
            SmartApiTokenMapping("BSE:BHARTIARTL", 3, "532454")
        )

        private val TOKEN_TO_SYMBOL = TOKEN_MAP.associateBy { it.token }

        internal fun parseOptionGreekResponse(body: String): Map<String, OptionGreeks> {
            val json = runCatching { JSONObject(body) }.getOrNull() ?: return emptyMap()
            if (!json.optBoolean("status", false)) return emptyMap()
            val arr = json.optJSONArray("data") ?: return emptyMap()
            val out = mutableMapOf<String, OptionGreeks>()
            for (i in 0 until arr.length()) {
                val row = arr.optJSONObject(i) ?: continue
                val strike = row.optString("strikePrice").toBigDecimalOrNull()
                    ?.setScale(2, RoundingMode.HALF_UP) ?: continue
                val optType = row.optString("optionType").trim().uppercase()
                if (optType != "CE" && optType != "PE") continue
                val iv = row.optString("impliedVolatility").toDoubleOrNull() ?: 0.0
                val delta = row.optString("delta").toDoubleOrNull() ?: 0.0
                val gamma = row.optString("gamma").toDoubleOrNull() ?: 0.0
                val theta = row.optString("theta").toDoubleOrNull() ?: 0.0
                val vega = row.optString("vega").toDoubleOrNull() ?: 0.0
                out["$strike:$optType"] = OptionGreeks(
                    ivPercent = iv,
                    delta = delta,
                    gamma = gamma,
                    thetaPerDay = theta,
                    vega = vega,
                    isBrokerSupplied = true
                )
            }
            return out
        }

        internal fun exchangeNameToCode(exchange: String): Int = when (exchange.uppercase()) {
            "NSE" -> 1
            "NFO" -> 2
            "BSE" -> 3
            "BFO" -> 4
            "MCX" -> 5
            else -> 1
        }

        internal fun exchangeCodeToName(code: Int): String = when (code) {
            1 -> "NSE"
            2 -> "NFO"
            3 -> "BSE"
            4 -> "BFO"
            5 -> "MCX"
            else -> ""
        }

        internal fun normalizeSearchQuery(raw: String): String {
            // Angel One searchScrip works best with compact uppercase symbols without spaces
            return raw.trim().uppercase().replace(Regex("\\s+"), "")
        }

        internal fun chooseSearchExchanges(
            rawQuery: String,
            segmentFilter: MarketSegment?,
            typeFilter: InstrumentType?
        ): List<String> {
            val upper = rawQuery.trim().uppercase()
            val looksIndexOrOption = upper.contains("NIFTY") ||
                upper.contains("FINNIFTY") ||
                upper.contains("MIDCP") ||
                upper.endsWith("CE") ||
                upper.endsWith("PE") ||
                upper.endsWith("FUT")
            val looksSensex = upper.contains("SENSEX") || upper.contains("BANKEX")
            val looksMcx = upper.contains("GOLD") || upper.contains("SILVER") ||
                upper.contains("CRUDE") || upper.contains("NATURALGAS") ||
                upper.contains("NATGAS") || upper.contains("COPPER") ||
                upper.contains("ZINC") || upper.contains("ALUMINI") ||
                upper.contains("LEAD") || upper.contains("NICKEL") ||
                upper.contains("MENTHA") || upper.contains("BULLDEX")

            return when (segmentFilter) {
                MarketSegment.CRYPTO -> emptyList()
                MarketSegment.MCX -> listOf("MCX")
                MarketSegment.BSE -> when (typeFilter) {
                    InstrumentType.OPTIONS, InstrumentType.FUTURES -> listOf("BFO")
                    InstrumentType.STOCK -> listOf("BSE")
                    null -> if (looksSensex || looksIndexOrOption) listOf("BFO", "BSE") else listOf("BSE", "BFO")
                }
                MarketSegment.NSE -> when (typeFilter) {
                    InstrumentType.OPTIONS, InstrumentType.FUTURES -> listOf("NFO")
                    InstrumentType.STOCK -> listOf("NSE")
                    null -> if (looksIndexOrOption) listOf("NFO", "NSE") else listOf("NSE", "NFO")
                }
                null -> when {
                    looksMcx -> listOf("MCX")
                    looksSensex -> listOf("BFO", "BSE")
                    typeFilter == InstrumentType.OPTIONS || typeFilter == InstrumentType.FUTURES -> listOf("NFO", "BFO", "MCX")
                    typeFilter == InstrumentType.STOCK -> listOf("NSE", "BSE")
                    looksIndexOrOption -> listOf("NFO", "BFO", "NSE")
                    else -> listOf("NSE", "NFO", "BSE", "MCX")
                }
            }
        }

        internal fun parseAngelScripResult(
            obj: JSONObject,
            fallbackExchange: String
        ): AngelScripSearchResult? {
            val exchange = obj.optString("exchange").ifBlank { fallbackExchange }.uppercase()
            val tradingSymbol = obj.optString("tradingsymbol").trim().uppercase()
            val token = obj.optString("symboltoken").trim()
            if (tradingSymbol.isBlank() || token.isBlank()) return null

            val rawInstType = obj.optString("instrumenttype").trim().uppercase()
            val optType = when {
                tradingSymbol.endsWith("CE") -> OptionType.CE
                tradingSymbol.endsWith("PE") -> OptionType.PE
                else -> null
            }
            val instType = when {
                rawInstType.contains("OPT") || optType != null -> InstrumentType.OPTIONS
                rawInstType.contains("FUT") || tradingSymbol.endsWith("FUT") -> InstrumentType.FUTURES
                else -> InstrumentType.STOCK
            }
            val segment = when (exchange) {
                "BSE", "BFO" -> MarketSegment.BSE
                "MCX" -> MarketSegment.MCX
                else -> MarketSegment.NSE
            }

            // Build clean unique symbol for Room & Watchlist
            val cleanSymbol = when {
                exchange == "NSE" && tradingSymbol.endsWith("-EQ") -> tradingSymbol.removeSuffix("-EQ")
                exchange == "BSE" -> "BSE:${tradingSymbol.removeSuffix("-EQ")}"
                else -> tradingSymbol
            }

            val rawName = obj.optString("name").trim()
            val displayName = when {
                instType == InstrumentType.OPTIONS -> "$exchange Option • $tradingSymbol"
                instType == InstrumentType.FUTURES -> "$exchange Futures • $tradingSymbol"
                rawName.isNotBlank() && !rawName.equals(cleanSymbol, ignoreCase = true) -> "$rawName ($exchange)"
                else -> "$tradingSymbol ($exchange)"
            }

            val parsedLot = obj.optString("lotsize").toIntOrNull()?.takeIf { it > 0 }
                ?: inferDefaultLotSize(tradingSymbol, instType)

            val rawTick = obj.optString("tick_size").toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO }
            val tickSize = when {
                rawTick == null -> BigDecimal("0.05")
                rawTick >= BigDecimal("1.0") && instType != InstrumentType.FUTURES ->
                    rawTick.divide(BigDecimal("100"), 2, RoundingMode.HALF_UP).max(BigDecimal("0.05"))
                else -> rawTick.setScale(2, RoundingMode.HALF_UP)
            }

            val rawStrike = obj.optString("strike").toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO }
            val strikePrice = when {
                optType == null -> null
                rawStrike != null && rawStrike > BigDecimal("200000") ->
                    rawStrike.divide(BigDecimal("100"), 2, RoundingMode.HALF_UP)
                rawStrike != null -> rawStrike.setScale(2, RoundingMode.HALF_UP)
                else -> (
                    Regex("\\d{2}[A-Z]{3}\\d{2}(\\d+)(CE|PE)$").find(tradingSymbol)?.groupValues?.getOrNull(1)
                        ?: Regex("(\\d{3,5})(CE|PE)$").find(tradingSymbol)?.groupValues?.getOrNull(1)
                    )?.toBigDecimalOrNull()?.setScale(2, RoundingMode.HALF_UP)
            }

            val expiry = obj.optString("expiry").trim().takeIf { it.isNotBlank() }

            return AngelScripSearchResult(
                exchange = exchange,
                tradingSymbol = tradingSymbol,
                symbol = cleanSymbol,
                symbolToken = token,
                displayName = displayName,
                segment = segment,
                instrumentType = instType,
                lotSize = parsedLot,
                tickSize = tickSize,
                expiryLabel = expiry,
                strikePrice = strikePrice,
                optionType = optType,
                exchangeTypeCode = exchangeNameToCode(exchange)
            )
        }

        private fun inferDefaultLotSize(tradingSymbol: String, type: InstrumentType): Int {
            if (type == InstrumentType.STOCK) return 1
            return when {
                tradingSymbol.startsWith("BANKNIFTY") -> 30
                tradingSymbol.startsWith("FINNIFTY") -> 65
                tradingSymbol.startsWith("MIDCPNIFTY") -> 120
                tradingSymbol.startsWith("NIFTY") -> 75
                tradingSymbol.startsWith("SENSEX") -> 20
                tradingSymbol.startsWith("BANKEX") -> 30
                tradingSymbol.startsWith("CRUDEOIL") -> 100
                else -> 1
            }
        }

        internal fun normalizeJwtToken(rawToken: String): String {
            return rawToken.trim()
                .removePrefix("Bearer ")
                .removePrefix("bearer ")
                .trim()
        }

        /**
         * Builds a SmartStream V2 handshake request cycling across Angel One's 3 documented auth modes:
         * - Attempt % 3 == 0: Raw JWT in Authorization header (matches official smartapi-python SmartWebSocketV2)
         * - Attempt % 3 == 1: Bearer-prefixed JWT in Authorization header
         * - Attempt % 3 == 2: Query parameters (?clientCode=...&feedToken=...&apiKey=...) + raw JWT header
         */
        internal fun buildSmartStreamRequest(
            creds: BrokerFeedSettings,
            session: AngelSessionTokens,
            attemptIndex: Int = 0
        ): Request {
            val apiKey = creds.angelOneApiKey.trim()
            val clientCode = creds.angelOneClientCode.trim().uppercase()
            val cleanJwt = normalizeJwtToken(session.jwtToken)
            val mode = Math.floorMod(attemptIndex, 3)

            val url = if (mode == 2) {
                "https://smartapisocket.angelone.in/smart-stream".toHttpUrl().newBuilder()
                    .addQueryParameter("clientCode", clientCode)
                    .addQueryParameter("feedToken", session.feedToken)
                    .addQueryParameter("apiKey", apiKey)
                    .build()
                    .toString()
                    .replaceFirst("https://", "wss://")
            } else {
                "wss://smartapisocket.angelone.in/smart-stream"
            }

            val authHeaderValue = if (mode == 1) "Bearer $cleanJwt" else cleanJwt

            return Request.Builder()
                .url(url)
                .addHeader("Authorization", authHeaderValue)
                .addHeader("x-api-key", apiKey)
                .addHeader("x-client-code", clientCode)
                .addHeader("x-feed-token", session.feedToken)
                .build()
        }

        internal fun buildSubscriptionPayload(
            mappings: List<SmartApiTokenMapping> = TOKEN_MAP,
            mode: Int = 1
        ): String {
            val grouped = mappings.groupBy { it.exchangeType }
            val tokenList = JSONArray()
            grouped.forEach { (exchangeType, items) ->
                tokenList.put(
                    JSONObject().apply {
                        put("exchangeType", exchangeType)
                        put("tokens", JSONArray(items.map { it.token }.distinct()))
                    }
                )
            }
            return JSONObject().apply {
                put("correlationID", CORRELATION_ID)
                put("action", 1) // Subscribe
                put("params", JSONObject().apply {
                    put("mode", mode) // 1 = LTP, 2 = Quote, 3 = Snap Quote (LTP + OI + Volume)
                    put("tokenList", tokenList)
                })
            }.toString()
        }

        /**
         * Decodes SmartStream V2 Binary Packet (LTP 51B / Quote 123B / Snap Quote 379B, little-endian):
         * [0] subscriptionMode (1 byte)
         * [1] exchangeType (1 byte)
         * [2..26] token (25 bytes C-string null-terminated ASCII)
         * [27..34] sequenceNumber (int64)
         * [35..42] exchangeTimestamp (int64)
         * [43..50] lastTradedPrice (int64 in paise -> / 100)
         * [67..74] volumeTradeForTheDay (int64, Mode 2 & 3)
         * [131..138] openInterest (int64, Mode 3 Snap Quote)
         */
        fun parseSmartApiBinaryPacket(bytes: ByteArray): TokenLiveTick? {
            if (bytes.size < 51) return null
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val mode = buffer.get().toInt() and 0xFF
            val exchangeType = buffer.get().toInt() and 0xFF
            val tokenBytes = ByteArray(25)
            buffer.get(tokenBytes)
            // CRITICAL: Slice at the FIRST null byte (C-string) so trailing buffer garbage never breaks token lookup
            val nullIdx = tokenBytes.indexOf(0.toByte())
            val cleanTokenBytes = if (nullIdx >= 0) tokenBytes.copyOf(nullIdx) else tokenBytes
            val token = String(cleanTokenBytes, Charsets.US_ASCII).trim()
            if (token.isBlank()) return null

            buffer.long // sequenceNumber
            val exchangeTs = buffer.long
            val ltpPaise = buffer.long
            if (ltpPaise <= 0L) return null
            val ltp = BigDecimal(ltpPaise).divide(BigDecimal(100), 2, RoundingMode.HALF_UP)

            val volume = if (mode >= 2 && bytes.size >= 75) {
                buffer.getLong(67).takeIf { it >= 0L }
            } else {
                null
            }
            val openInterest = if (mode >= 3 && bytes.size >= 139) {
                buffer.getLong(131).takeIf { it >= 0L }
            } else {
                null
            }

            return TokenLiveTick(
                exchangeType = exchangeType,
                token = token,
                ltp = ltp,
                openInterest = openInterest,
                volume = volume,
                timestampMillis = if (exchangeTs > 0L) exchangeTs else System.currentTimeMillis()
            )
        }

        fun parseSmartApiLtpPacket(
            bytes: ByteArray,
            universe: Map<String, Instrument>,
            tokenLookup: (exchangeType: Int, token: String) -> SmartApiTokenMapping? = { _, tok -> TOKEN_TO_SYMBOL[tok] }
        ): PriceTick? {
            val raw = parseSmartApiBinaryPacket(bytes) ?: return null
            val mapping = tokenLookup(raw.exchangeType, raw.token) ?: return null
            val inst = universe[mapping.symbol] ?: return null
            val ltp = RiskMath.roundToTick(raw.ltp, inst.tickSize)
            return PriceTick(
                symbol = inst.symbol,
                ltp = ltp,
                timestampMillis = raw.timestampMillis,
                volume = raw.volume
            )
        }

        /**
         * RFC 6238 TOTP generator from Base32 secret (for Angel One SmartAPI login).
         */
        fun generateTotp(base32Secret: String, timeMillis: Long = System.currentTimeMillis()): String {
            val cleaned = base32Secret.uppercase().filter { it in "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567" }
            if (cleaned.isEmpty()) return "000000"
            val key = decodeBase32(cleaned)
            if (key.isEmpty()) return "000000"
            val counter = timeMillis / 1000L / 30L
            val data = ByteBuffer.allocate(8).putLong(counter).array()
            val mac = Mac.getInstance("HmacSHA1").apply {
                init(SecretKeySpec(key, "HmacSHA1"))
            }
            val hash = mac.doFinal(data)
            val offset = hash.last().toInt() and 0x0F
            val binary = ((hash[offset].toInt() and 0x7F) shl 24) or
                ((hash[offset + 1].toInt() and 0xFF) shl 16) or
                ((hash[offset + 2].toInt() and 0xFF) shl 8) or
                (hash[offset + 3].toInt() and 0xFF)
            val otp = binary % 1_000_000
            return otp.toString().padStart(6, '0')
        }

        private fun decodeBase32(input: String): ByteArray {
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
            var buffer = 0
            var bitsLeft = 0
            val out = mutableListOf<Byte>()
            for (ch in input) {
                val valIdx = alphabet.indexOf(ch)
                if (valIdx < 0) continue
                buffer = (buffer shl 5) or valIdx
                bitsLeft += 5
                if (bitsLeft >= 8) {
                    out.add(((buffer shr (bitsLeft - 8)) and 0xFF).toByte())
                    bitsLeft -= 8
                }
            }
            return out.toByteArray()
        }
    }
}
