package com.example.data.repository

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.example.data.feed.SimulatedFeed
import com.example.data.feed.live.ActiveFeedInstance
import com.example.data.feed.live.AngelOnePriceFeed
import com.example.data.feed.live.AngelScripSearchResult
import com.example.data.feed.live.BrokerFeedSettings
import com.example.data.feed.live.FeedType
import com.example.data.feed.live.LiveConnectionStatus
import com.example.data.feed.live.LiveFeedFactory
import com.example.data.feed.live.SecureBrokerCredentialsStore
import com.example.data.feed.live.SegmentBadgeMode
import com.example.data.feed.live.SegmentFeedConfig
import com.example.data.feed.live.SegmentFeedStatus
import com.example.data.local.AccountDao
import com.example.data.local.InstrumentDao
import com.example.data.local.InstrumentEntity
import com.example.data.local.PositionDao
import com.example.data.local.SeedData
import com.example.data.local.toDomain
import com.example.domain.engine.OptionChainSnapshot
import com.example.domain.engine.OptionLegQuote
import com.example.domain.engine.TokenLiveTick
import com.example.domain.model.FeedConnectionState
import com.example.domain.model.FeedSourceType
import com.example.domain.model.Instrument
import com.example.domain.model.InstrumentType
import com.example.domain.model.MarketSegment
import com.example.domain.model.PriceTick
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MarketDataRepositoryImpl @Inject constructor(
    private val instrumentDao: InstrumentDao,
    private val positionDao: PositionDao,
    private val accountDao: AccountDao,
    private val simulatedFeed: SimulatedFeed,
    private val feedFactory: LiveFeedFactory,
    private val credentialsStore: SecureBrokerCredentialsStore,
    private val appScope: CoroutineScope
) : MarketDataRepository {

    private val _ticks = MutableSharedFlow<PriceTick>(extraBufferCapacity = 512)
    override val ticks: Flow<PriceTick> = _ticks.asSharedFlow()

    private val _segmentConfigs = MutableStateFlow(credentialsStore.getCurrent().segmentConfigs)
    override val segmentConfigs: StateFlow<Map<MarketSegment, SegmentFeedConfig>> =
        _segmentConfigs.asStateFlow()

    private val _activeFeedType = MutableStateFlow(credentialsStore.getCurrent().selectedFeed)
    override val activeFeedType: StateFlow<FeedType> = _activeFeedType.asStateFlow()

    private val _liveConnectionStatus = MutableStateFlow(LiveConnectionStatus.DISCONNECTED)
    override val liveConnectionStatus: StateFlow<LiveConnectionStatus> =
        _liveConnectionStatus.asStateFlow()

    private val _liveStatusMessage = MutableStateFlow("Multi-segment orchestrator initialized")
    override val liveStatusMessage: StateFlow<String> = _liveStatusMessage.asStateFlow()

    private val _riskControlsSuspended = MutableStateFlow(false)
    override val riskControlsSuspended: StateFlow<Boolean> = _riskControlsSuspended.asStateFlow()

    private val _suspendedSegments = MutableStateFlow<Set<MarketSegment>>(emptySet())
    override val suspendedSegments: StateFlow<Set<MarketSegment>> = _suspendedSegments.asStateFlow()

    private val _suspendedBannerMessage = MutableStateFlow("")
    override val suspendedBannerMessage: StateFlow<String> = _suspendedBannerMessage.asStateFlow()

    private val _connectionState = MutableStateFlow<FeedConnectionState>(
        FeedConnectionState.Connected(FeedSourceType.SIMULATED, "Multi-Segment Feeds")
    )
    override val connectionState: StateFlow<FeedConnectionState> = _connectionState.asStateFlow()

    private val _feedStatusMap = MutableStateFlow(
        FeedType.entries.associateWith { LiveConnectionStatus.DISCONNECTED }
    )

    private val _segmentStatuses = MutableStateFlow(
        MarketSegment.entries.associateWith { seg ->
            SegmentFeedStatus(
                segment = seg,
                badge = SegmentBadgeMode.SIMULATED,
                lastTickMillis = null,
                detail = "Initializing..."
            )
        }
    )
    override val segmentStatuses: StateFlow<Map<MarketSegment, SegmentFeedStatus>> =
        _segmentStatuses.asStateFlow()

    override val brokerSettings: StateFlow<BrokerFeedSettings> = credentialsStore.settings

    override val instruments: Flow<List<Instrument>> =
        instrumentDao.observeAll().map { entities -> entities.map { it.toDomain() } }

    private val activeInstances = ConcurrentHashMap<MarketSegment, ActiveFeedInstance>()
    private val segmentLiveStatuses = ConcurrentHashMap<MarketSegment, Pair<LiveConnectionStatus, String>>()
    private val symbolToSegment = ConcurrentHashMap<String, MarketSegment>()
    private val lastTickTimestamps = ConcurrentHashMap<MarketSegment, Long>()
    private val _openPositionSegments = MutableStateFlow<Set<MarketSegment>>(emptySet())

    @Volatile
    private var cachedUniverse: List<Instrument> = emptyList()

    @Volatile
    private var isAppInBackground = false

    init {
        attachProcessLifecycleObserver()

        // Seed initial data, populate universe, and start all configured segment feeds concurrently
        appScope.launch(Dispatchers.IO) {
            ensureSeeded()
            val universe = instrumentDao.getAllOnce().map { it.toDomain() }
            populateUniverse(universe)

            val savedSettings = credentialsStore.getCurrent()
            simulatedFeed.tickIntervalMs = savedSettings.simulatedTickIntervalMs
            simulatedFeed.start()

            val currentConfigs = _segmentConfigs.value
            currentConfigs.values.forEach { cfg ->
                if (cfg.isEnabled && !activeInstances.containsKey(cfg.segment)) {
                    startSegmentInstance(cfg.segment, cfg.feedType)
                } else if (!cfg.isEnabled && !segmentLiveStatuses.containsKey(cfg.segment)) {
                    segmentLiveStatuses[cfg.segment] = LiveConnectionStatus.DISCONNECTED to "Disabled"
                }
            }
            refreshFeedHealthState()
        }

        // Observe open position segments for per-segment safety suspension checks
        appScope.launch(Dispatchers.IO) {
            positionDao.observeAll().collect { positions ->
                _openPositionSegments.value = positions.map { it.segment }.toSet()
                refreshFeedHealthState()
            }
        }

        // Fallback simulator ticks (used ONLY for live-configured segments that dropped with NO open positions)
        appScope.launch(Dispatchers.IO) {
            simulatedFeed.ticks.collect { tick ->
                handleIncomingTick(tick, fromLiveFeed = false, fromFallbackSimulator = true)
            }
        }
    }

    override fun observeInstrument(symbol: String): Flow<Instrument?> {
        return instrumentDao.observeBySymbol(symbol).map { it?.toDomain() }
    }

    override fun observeFeedStatus(): Flow<Map<FeedType, LiveConnectionStatus>> =
        _feedStatusMap.asStateFlow()

    override suspend fun ensureSeeded() {
        if (instrumentDao.count() == 0) {
            instrumentDao.upsertAll(SeedData.seedInstruments())
        } else {
            val existingSymbols = instrumentDao.getAllOnce().map { it.symbol }.toSet()
            val missingStockOptions = SeedData.stockOptionInstruments().filter { it.symbol !in existingSymbols }
            if (missingStockOptions.isNotEmpty()) {
                instrumentDao.upsertAll(missingStockOptions)
            }
        }
        if (accountDao.getAccount() == null) {
            accountDao.upsert(SeedData.defaultAccount())
        }
        ensureUniverseLoaded()
    }

    override fun setFeedForSegment(segment: MarketSegment, feedType: FeedType) {
        if (!feedType.supportsSegment(segment)) return
        val newCfg = SegmentFeedConfig(segment = segment, feedType = feedType, isEnabled = true)
        val updated = _segmentConfigs.value.toMutableMap().apply { put(segment, newCfg) }
        _segmentConfigs.value = updated
        credentialsStore.updateSegmentConfig(newCfg)

        if (!isAppInBackground) {
            startSegmentInstance(segment, feedType)
        }
        refreshFeedHealthState()
    }

    override fun enableFeed(feedType: FeedType) {
        val updated = _segmentConfigs.value.toMutableMap()
        feedType.coveredSegments.forEach { seg ->
            val newCfg = SegmentFeedConfig(segment = seg, feedType = feedType, isEnabled = true)
            updated[seg] = newCfg
            if (!isAppInBackground) {
                startSegmentInstance(seg, feedType)
            }
        }
        _segmentConfigs.value = updated
        credentialsStore.updateSegmentConfigs(updated)
        refreshFeedHealthState()
    }

    override fun disableFeed(feedType: FeedType) {
        val updated = _segmentConfigs.value.toMutableMap()
        MarketSegment.entries.forEach { seg ->
            val current = updated[seg]
            if (current != null && current.feedType == feedType) {
                updated[seg] = current.copy(isEnabled = false)
                activeInstances.remove(seg)?.stop()
                segmentLiveStatuses[seg] = LiveConnectionStatus.DISCONNECTED to "Feed disabled"
            }
        }
        _segmentConfigs.value = updated
        credentialsStore.updateSegmentConfigs(updated)
        refreshFeedHealthState()
    }

    override suspend fun setActiveFeed(feedType: FeedType) {
        if (feedType == FeedType.SIMULATED) {
            // Switch any suspended/live segments cleanly to SIMULATED when requested from warning banner
            val targets = _suspendedSegments.value.ifEmpty { MarketSegment.entries.toSet() }
            targets.forEach { seg ->
                setFeedForSegment(seg, FeedType.SIMULATED)
            }
        } else {
            enableFeed(feedType)
        }
    }

    override suspend fun enableLiveForAllSupportedSegments() {
        val updated = _segmentConfigs.value.toMutableMap().apply {
            put(MarketSegment.NSE, SegmentFeedConfig(MarketSegment.NSE, FeedType.ANGEL_ONE_LIVE, true))
            put(MarketSegment.BSE, SegmentFeedConfig(MarketSegment.BSE, FeedType.ANGEL_ONE_LIVE, true))
            put(MarketSegment.MCX, SegmentFeedConfig(MarketSegment.MCX, FeedType.ANGEL_ONE_LIVE, true))
            put(MarketSegment.CRYPTO, SegmentFeedConfig(MarketSegment.CRYPTO, FeedType.BINANCE_LIVE, true))
        }
        _segmentConfigs.value = updated
        credentialsStore.updateSegmentConfigs(updated)
        if (!isAppInBackground) {
            // Stop previous non-matching segment handles cleanly before starting live handles
            listOf(MarketSegment.NSE, MarketSegment.BSE, MarketSegment.MCX).forEach { seg ->
                activeInstances.remove(seg)?.stop()
            }
            listOf(MarketSegment.NSE, MarketSegment.BSE, MarketSegment.MCX).forEach { seg ->
                startSegmentInstance(seg, FeedType.ANGEL_ONE_LIVE)
            }
            if (activeInstances[MarketSegment.CRYPTO]?.feedType != FeedType.BINANCE_LIVE) {
                startSegmentInstance(MarketSegment.CRYPTO, FeedType.BINANCE_LIVE)
            }
        }
        refreshFeedHealthState()
    }

    override suspend fun updateBrokerSettings(settings: BrokerFeedSettings) {
        credentialsStore.updateSettings(settings)
        simulatedFeed.tickIntervalMs = settings.simulatedTickIntervalMs
        _segmentConfigs.value = settings.segmentConfigs
        ensureUniverseLoaded()

        // Phase 1: Stop all existing segment instances first so shared broker ref-counts reach 0
        // and flush stale session tokens before re-authenticating once with updated credentials.
        settings.segmentConfigs.values.forEach { cfg ->
            activeInstances.remove(cfg.segment)?.stop()
            if (!cfg.isEnabled) {
                segmentLiveStatuses[cfg.segment] = LiveConnectionStatus.DISCONNECTED to "Disabled"
            }
        }
        // Phase 2: Start enabled segment instances
        if (!isAppInBackground) {
            settings.segmentConfigs.values.forEach { cfg ->
                if (cfg.isEnabled) {
                    startSegmentInstance(cfg.segment, cfg.feedType)
                }
            }
        }
        refreshFeedHealthState()
    }

    override suspend fun reconnectActiveFeed() {
        if (isAppInBackground) return
        val liveSegments = MarketSegment.entries.mapNotNull { seg ->
            val cfg = _segmentConfigs.value[seg]
            if (cfg != null && cfg.isEnabled && cfg.feedType != FeedType.SIMULATED) cfg else null
        }
        // Phase 1: Stop all active live handles first so shared broker ref-counts reach 0 cleanly
        liveSegments.forEach { cfg ->
            activeInstances.remove(cfg.segment)?.stop()
        }
        // Phase 2: Re-open one clean connection per shared FeedType
        liveSegments.forEach { cfg ->
            startSegmentInstance(cfg.segment, cfg.feedType)
        }
        refreshFeedHealthState()
    }

    override suspend fun reconnectSegment(segment: MarketSegment) {
        val cfg = _segmentConfigs.value[segment] ?: return
        val enabledCfg = cfg.copy(isEnabled = true)
        val updated = _segmentConfigs.value.toMutableMap().apply { put(segment, enabledCfg) }
        _segmentConfigs.value = updated
        credentialsStore.updateSegmentConfig(enabledCfg)
        if (!isAppInBackground) {
            if (enabledCfg.feedType == FeedType.SIMULATED) {
                startSegmentInstance(segment, enabledCfg.feedType)
            } else {
                // Shared broker feeds (e.g. Angel One across NSE/BSE/MCX) are reference-counted;
                // stop all segments sharing this feedType first so ref-count reaches 0 cleanly.
                val peerSegments = updated.values
                    .filter { it.isEnabled && it.feedType == enabledCfg.feedType }
                    .map { it.segment }
                peerSegments.forEach { seg -> activeInstances.remove(seg)?.stop() }
                peerSegments.forEach { seg -> startSegmentInstance(seg, enabledCfg.feedType) }
            }
        }
        refreshFeedHealthState()
    }

    override suspend fun nudgePrice(symbol: String, percentDelta: BigDecimal) {
        val segment = symbolToSegment[symbol] ?: instrumentDao.getBySymbol(symbol)?.segment ?: return
        val instance = activeInstances[segment]
        val cfg = _segmentConfigs.value[segment]
        val status = segmentLiveStatuses[segment]?.first ?: LiveConnectionStatus.DISCONNECTED
        if (instance != null && cfg != null && (cfg.feedType == FeedType.SIMULATED || status == LiveConnectionStatus.CONNECTED)) {
            instance.nudgePrice(symbol, percentDelta)
        } else {
            simulatedFeed.nudgePrice(symbol, percentDelta)
        }
    }

    override suspend fun searchLiveInstruments(
        query: String,
        segmentFilter: MarketSegment?,
        typeFilter: InstrumentType?
    ): Result<List<AngelScripSearchResult>> {
        val angel = feedFactory.getAngelOneFeed()
            ?: return Result.failure(IllegalStateException("Angel One live feed unavailable"))
        return angel.searchLiveScrips(query, segmentFilter, typeFilter)
    }

    override suspend fun addLiveInstrument(result: AngelScripSearchResult): Result<Instrument> {
        val angel = feedFactory.getAngelOneFeed()
            ?: return Result.failure(IllegalStateException("Angel One live feed unavailable"))

        val existing = instrumentDao.getBySymbol(result.symbol)
        val quote = angel.fetchQuoteForToken(result.exchange, result.symbolToken)

        val resolvedLtp = quote?.ltp?.takeIf { it > BigDecimal.ZERO }
            ?: existing?.ltp?.takeIf { it > BigDecimal.ZERO }
            ?: BigDecimal("100.00")
        val resolvedPrevClose = quote?.close?.takeIf { it > BigDecimal.ZERO }
            ?: existing?.previousClose?.takeIf { it > BigDecimal.ZERO }
            ?: resolvedLtp
        val resolvedHigh = quote?.high?.takeIf { it > BigDecimal.ZERO }
            ?: existing?.dayHigh?.max(resolvedLtp)
            ?: resolvedLtp
        val resolvedLow = quote?.low?.takeIf { it > BigDecimal.ZERO }
            ?: existing?.dayLow?.min(resolvedLtp)
            ?: resolvedLtp

        val initialSpark = existing?.sparkline?.takeIf { it.size >= 4 }
            ?: listOf(resolvedPrevClose, resolvedLow, resolvedHigh, resolvedLtp)

        val entity = InstrumentEntity(
            symbol = result.symbol,
            displayName = result.displayName,
            segment = result.segment,
            type = result.instrumentType,
            lotSize = result.lotSize.coerceAtLeast(1),
            tickSize = result.tickSize,
            ltp = resolvedLtp,
            previousClose = resolvedPrevClose,
            dayHigh = resolvedHigh,
            dayLow = resolvedLow,
            volume = existing?.volume ?: 0L,
            expiryLabel = result.expiryLabel,
            strikePrice = result.strikePrice,
            optionType = result.optionType,
            supportsIntraday = true,
            supportsCarryForward = true,
            sparkline = initialSpark,
            lastTickDirection = resolvedLtp.compareTo(resolvedPrevClose)
        )

        instrumentDao.upsertAll(listOf(entity))
        val updatedUniverse = instrumentDao.getAllOnce().map { it.toDomain() }
        populateUniverse(updatedUniverse)

        angel.registerAndSubscribeToken(
            AngelOnePriceFeed.SmartApiTokenMapping(
                symbol = result.symbol,
                exchangeType = result.exchangeTypeCode,
                token = result.symbolToken
            )
        )

        return Result.success(entity.toDomain())
    }

    override suspend fun fetchLiveOptionChain(
        underlying: String,
        expiry: String?,
        strikeWindow: Int,
        previousOiMap: Map<String, Long>
    ): Result<OptionChainSnapshot> {
        val angel = feedFactory.getAngelOneFeed()
            ?: return Result.failure(IllegalStateException("Angel One live feed unavailable"))
        return angel.fetchLiveOptionChainSnapshot(
            underlying = underlying,
            requestedExpiry = expiry,
            strikeWindow = strikeWindow,
            previousOiMap = previousOiMap
        )
    }

    override suspend fun addOptionLegToWatchlist(leg: OptionLegQuote): Result<Instrument> {
        return addLiveInstrument(
            AngelScripSearchResult(
                exchange = leg.exchange,
                tradingSymbol = leg.tradingSymbol,
                symbol = leg.symbol,
                symbolToken = leg.symbolToken,
                displayName = "${leg.exchange} Option • ${leg.tradingSymbol}",
                segment = leg.segment,
                instrumentType = InstrumentType.OPTIONS,
                lotSize = leg.lotSize,
                tickSize = leg.tickSize,
                expiryLabel = leg.expiryLabel,
                strikePrice = leg.strikePrice,
                optionType = leg.optionType,
                exchangeTypeCode = leg.exchangeTypeCode
            )
        )
    }

    override fun observeOptionTokenTicks(): Flow<TokenLiveTick> {
        return feedFactory.getAngelOneFeed()?.tokenTicks ?: emptyFlow()
    }

    override suspend fun refreshScripMaster(): Result<Int> {
        val angel = feedFactory.getAngelOneFeed()
            ?: return Result.failure(IllegalStateException("Angel One live feed unavailable"))
        return angel.ensureScripMasterLoaded(forceRefresh = true)
    }

    fun onAppBackground() {
        isAppInBackground = true
        activeInstances.values.forEach { instance ->
            if (instance.feedType != FeedType.SIMULATED) {
                instance.stop()
            }
        }
    }

    fun onAppForeground() {
        isAppInBackground = false
        _segmentConfigs.value.values.forEach { cfg ->
            if (cfg.isEnabled && cfg.feedType != FeedType.SIMULATED) {
                startSegmentInstance(cfg.segment, cfg.feedType)
            }
        }
        refreshFeedHealthState()
    }

    private fun startSegmentInstance(segment: MarketSegment, feedType: FeedType) {
        activeInstances.remove(segment)?.stop()
        val bundle = feedFactory.create(
            feedType = feedType,
            simulatedTickIntervalMs = credentialsStore.getCurrent().simulatedTickIntervalMs
        )
        val instance = ActiveFeedInstance(
            segment = segment,
            feedType = feedType,
            feed = bundle.feed,
            liveStatusSource = bundle.liveStatusSource,
            statusDetailSource = bundle.statusDetailSource,
            scope = appScope,
            symbolToSegment = { symbol -> symbolToSegment[symbol] },
            onTick = { tick, fromLiveFeed ->
                handleIncomingTick(tick, fromLiveFeed = fromLiveFeed, fromFallbackSimulator = false)
            },
            onStatusUpdated = { seg, status, detail ->
                segmentLiveStatuses[seg] = status to detail
                refreshFeedHealthState()
            }
        )
        if (cachedUniverse.isNotEmpty()) {
            instance.setUniverse(cachedUniverse)
        }
        activeInstances[segment] = instance
        instance.start()
    }

    private suspend fun ensureUniverseLoaded() {
        if (cachedUniverse.isEmpty() || symbolToSegment.isEmpty()) {
            val universe = instrumentDao.getAllOnce().map { it.toDomain() }
            populateUniverse(universe)
        }
    }

    private fun populateUniverse(universe: List<Instrument>) {
        cachedUniverse = universe
        universe.forEach { symbolToSegment[it.symbol] = it.segment }
        simulatedFeed.setUniverse(universe)
        activeInstances.values.forEach { it.setUniverse(universe) }
    }

    private fun attachProcessLifecycleObserver() {
        runCatching {
            val mainLooper = Looper.getMainLooper()
            if (mainLooper != null) {
                Handler(mainLooper).post {
                    runCatching {
                        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                            override fun onStart(owner: LifecycleOwner) = onAppForeground()
                            override fun onStop(owner: LifecycleOwner) = onAppBackground()
                        })
                    }
                }
            }
        }
    }

    private suspend fun handleIncomingTick(
        tick: PriceTick,
        fromLiveFeed: Boolean,
        fromFallbackSimulator: Boolean
    ) {
        val existing = instrumentDao.getBySymbol(tick.symbol)
        val segment = symbolToSegment[tick.symbol] ?: existing?.segment?.also {
            symbolToSegment[tick.symbol] = it
        }
        val config = segment?.let { _segmentConfigs.value[it] }
        val liveStatus = segment?.let { segmentLiveStatuses[it]?.first } ?: LiveConnectionStatus.DISCONNECTED

        val allowed = shouldEmitSegmentTick(
            segment = segment,
            fromLiveFeed = fromLiveFeed,
            fromFallbackSimulator = fromFallbackSimulator,
            config = config,
            liveStatus = liveStatus,
            openPositionSegments = _openPositionSegments.value
        )
        if (!allowed || segment == null) return

        val ts = tick.timestampMillis.takeIf { it > 0L } ?: System.currentTimeMillis()
        lastTickTimestamps[segment] = ts

        if (existing != null) {
            val direction = tick.ltp.compareTo(existing.ltp)
            val updatedSpark = when {
                tick.sparkline != null && tick.sparkline.size >= 4 -> tick.sparkline
                direction != 0 -> (existing.sparkline + tick.ltp).takeLast(24)
                else -> existing.sparkline
            }
            val updated = existing.copy(
                ltp = tick.ltp,
                previousClose = tick.previousClose ?: existing.previousClose,
                dayHigh = tick.dayHigh ?: existing.dayHigh.max(tick.ltp),
                dayLow = tick.dayLow ?: existing.dayLow.min(tick.ltp),
                volume = tick.volume ?: existing.volume,
                sparkline = updatedSpark,
                lastTickDirection = if (direction != 0) direction else existing.lastTickDirection
            )
            instrumentDao.update(updated)
        }

        refreshFeedHealthState()
        _ticks.emit(tick)
    }

    private fun refreshFeedHealthState() {
        val configs = _segmentConfigs.value
        val openSegs = _openPositionSegments.value
        val suspendedSet = mutableSetOf<MarketSegment>()

        val segMap = MarketSegment.entries.associateWith { seg ->
            val cfg = configs[seg] ?: SegmentFeedConfig(seg, FeedType.SIMULATED, true)
            val (rawStatus, rawDetail) = segmentLiveStatuses[seg] ?: (
                if (cfg.feedType == FeedType.SIMULATED && cfg.isEnabled) {
                    LiveConnectionStatus.CONNECTED to "Simulated 1s feed"
                } else {
                    LiveConnectionStatus.DISCONNECTED to "Disconnected"
                }
                )
            val ts = lastTickTimestamps[seg]

            val badge = when {
                cfg.feedType == FeedType.SIMULATED && cfg.isEnabled -> SegmentBadgeMode.SIMULATED
                cfg.isEnabled && rawStatus == LiveConnectionStatus.CONNECTED -> SegmentBadgeMode.LIVE
                seg in openSegs && rawStatus != LiveConnectionStatus.CONNECTING -> {
                    suspendedSet.add(seg)
                    SegmentBadgeMode.SUSPENDED
                }
                else -> SegmentBadgeMode.DELAYED
            }

            val detail = when (badge) {
                SegmentBadgeMode.LIVE -> "${cfg.feedType.label} Live"
                SegmentBadgeMode.SIMULATED -> "Simulated 1s feed"
                SegmentBadgeMode.SUSPENDED -> "FEED LOST — ${seg.label} risk controls suspended ($rawDetail)"
                SegmentBadgeMode.DELAYED -> when {
                    !cfg.isEnabled -> "Feed disabled — simulated fallback"
                    rawStatus == LiveConnectionStatus.CONNECTING && seg in openSegs ->
                        "${cfg.feedType.label} connecting (risk controls paused during handshake)"
                    rawStatus == LiveConnectionStatus.CONNECTING ->
                        "${cfg.feedType.label} connecting..."
                    else -> "${cfg.feedType.label} offline ($rawDetail) — simulated fallback"
                }
            }

            SegmentFeedStatus(
                segment = seg,
                badge = badge,
                lastTickMillis = ts,
                detail = detail,
                activeFeedType = cfg.feedType,
                connectionStatus = if (cfg.feedType == FeedType.SIMULATED && cfg.isEnabled) {
                    LiveConnectionStatus.CONNECTED
                } else {
                    rawStatus
                },
                isEnabled = cfg.isEnabled
            )
        }

        _segmentStatuses.value = segMap
        _suspendedSegments.value = suspendedSet
        _riskControlsSuspended.value = suspendedSet.isNotEmpty()
        _suspendedBannerMessage.value = if (suspendedSet.isNotEmpty()) {
            val names = MarketSegment.entries.filter { it in suspendedSet }.joinToString("/") { it.label }
            "FEED LOST — $names risk controls suspended."
        } else {
            ""
        }

        // Update independent per-FeedType connection status map
        val feedStatusMap = FeedType.entries.associateWith { ft ->
            val matching = segMap.values.filter { it.activeFeedType == ft && it.isEnabled }
            when {
                matching.isEmpty() -> LiveConnectionStatus.DISCONNECTED
                matching.any { it.connectionStatus == LiveConnectionStatus.CONNECTED } -> LiveConnectionStatus.CONNECTED
                matching.any { it.connectionStatus == LiveConnectionStatus.RECONNECTING } -> LiveConnectionStatus.RECONNECTING
                matching.any { it.connectionStatus == LiveConnectionStatus.CONNECTING } -> LiveConnectionStatus.CONNECTING
                matching.any { it.connectionStatus == LiveConnectionStatus.ERROR } -> LiveConnectionStatus.ERROR
                else -> LiveConnectionStatus.DISCONNECTED
            }
        }
        _feedStatusMap.value = feedStatusMap

        // Summary connection status for legacy header/badges
        val anyLiveConnected = segMap.values.any { it.badge == SegmentBadgeMode.LIVE }
        val anyConnecting = segMap.values.any {
            it.activeFeedType != FeedType.SIMULATED &&
                it.isEnabled &&
                (it.connectionStatus == LiveConnectionStatus.CONNECTING ||
                    it.connectionStatus == LiveConnectionStatus.RECONNECTING)
        }
        _liveConnectionStatus.value = when {
            anyLiveConnected -> LiveConnectionStatus.CONNECTED
            anyConnecting -> LiveConnectionStatus.RECONNECTING
            suspendedSet.isNotEmpty() -> LiveConnectionStatus.ERROR
            else -> LiveConnectionStatus.DISCONNECTED
        }

        val liveLabels = segMap.values
            .filter { it.badge == SegmentBadgeMode.LIVE }
            .map { "${it.segment.label}: ${it.activeFeedType.label}" }
        _liveStatusMessage.value = when {
            suspendedSet.isNotEmpty() -> _suspendedBannerMessage.value
            liveLabels.isNotEmpty() -> liveLabels.joinToString(" • ")
            else -> "Simulated / fallback active"
        }

        val primaryLive = segMap.values.firstOrNull { it.badge == SegmentBadgeMode.LIVE }?.activeFeedType
            ?: FeedType.SIMULATED
        _activeFeedType.value = primaryLive
        _connectionState.value = when {
            suspendedSet.isNotEmpty() -> FeedConnectionState.Disconnected(
                FeedSourceType.SIMULATED,
                _suspendedBannerMessage.value
            )
            anyConnecting && !anyLiveConnected -> FeedConnectionState.Connecting(FeedSourceType.SIMULATED)
            else -> FeedConnectionState.Connected(
                FeedSourceType.SIMULATED,
                _liveStatusMessage.value
            )
        }
    }

    override suspend fun ensureEquityInstrument(
        symbol: String,
        displayName: String,
        ltp: BigDecimal,
        previousClose: BigDecimal,
        dayHigh: BigDecimal,
        dayLow: BigDecimal,
        volume: Long,
        segment: MarketSegment
    ) {
        val cleanSymbol = symbol.trim().uppercase()
        if (cleanSymbol.isBlank()) return
        val existing = instrumentDao.getBySymbol(cleanSymbol)
        if (existing != null) return
        val safeLtp = ltp.max(BigDecimal("0.05"))
        val safePrev = previousClose.takeIf { it > BigDecimal.ZERO } ?: safeLtp
        val safeHigh = dayHigh.max(safeLtp)
        val safeLow = dayLow.takeIf { it > BigDecimal.ZERO }?.min(safeLtp) ?: safeLtp
        val spark = listOf(
            safePrev,
            safePrev.add(safeLtp).divide(BigDecimal("2"), 2, java.math.RoundingMode.HALF_UP),
            safeLow,
            safeHigh,
            safeLtp
        )
        val entity = InstrumentEntity(
            symbol = cleanSymbol,
            displayName = displayName.ifBlank { cleanSymbol },
            segment = segment,
            type = InstrumentType.STOCK,
            lotSize = 1,
            tickSize = BigDecimal("0.05"),
            ltp = safeLtp,
            previousClose = safePrev,
            dayHigh = safeHigh,
            dayLow = safeLow,
            volume = volume.coerceAtLeast(1L),
            expiryLabel = null,
            strikePrice = null,
            optionType = null,
            supportsIntraday = true,
            supportsCarryForward = true,
            sparkline = spark,
            lastTickDirection = if (safeLtp >= safePrev) 1 else -1
        )
        instrumentDao.upsertAll(listOf(entity))
        val updatedUniverse = instrumentDao.getAllOnce().map { it.toDomain() }
        populateUniverse(updatedUniverse)
    }

    companion object {
        /**
         * Pure per-segment tick routing & safety predicate:
         * - Simulated-configured segment accepts ticks only from its Simulated ActiveFeedInstance.
         * - Live-configured segment accepts live ticks when enabled.
         * - Fallback SimulatedFeed ticks for a live-configured segment are allowed ONLY when:
         *   (live feed is disabled OR not CONNECTED) AND NO open positions exist in THIS segment.
         *   If open positions exist in THIS segment, fallback is blocked so ExecutionEngine pauses
         *   risk evaluation for this segment without affecting other segments.
         */
        internal fun shouldEmitSegmentTick(
            segment: MarketSegment?,
            fromLiveFeed: Boolean,
            fromFallbackSimulator: Boolean,
            config: SegmentFeedConfig?,
            liveStatus: LiveConnectionStatus,
            openPositionSegments: Set<MarketSegment>
        ): Boolean {
            if (segment == null || config == null) return false
            if (config.feedType == FeedType.SIMULATED) {
                return config.isEnabled && !fromLiveFeed && !fromFallbackSimulator
            }
            if (fromLiveFeed) {
                return config.isEnabled
            }
            if (!fromFallbackSimulator) return false
            if (config.isEnabled && liveStatus == LiveConnectionStatus.CONNECTED) return false
            return segment !in openPositionSegments
        }
    }
}
