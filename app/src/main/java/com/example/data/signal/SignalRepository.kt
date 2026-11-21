package com.example.data.signal

import com.example.data.local.InstrumentDao
import com.example.data.local.SavedSignalDao
import com.example.data.local.SignalDao
import com.example.data.local.toDomain
import com.example.data.local.toEntity
import com.example.data.local.toSavedEntity
import com.example.data.history.HistoricalCandleRepository
import com.example.data.repository.MarketDataRepository
import com.example.domain.model.Instrument
import com.example.domain.signal.SignalConfidence
import com.example.domain.signal.SignalContext
import com.example.domain.signal.SignalDirection
import com.example.domain.signal.SignalEngine
import com.example.domain.signal.SignalStrategy
import com.example.domain.signal.Timeframe
import com.example.domain.signal.TradeSignal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

data class SignalUiItem(
    val signal: TradeSignal,
    val userStatus: String,
    val isSaved: Boolean
)

@Singleton
class SignalRepository @Inject constructor(
    private val marketDataRepository: MarketDataRepository,
    private val instrumentDao: InstrumentDao,
    private val candleAggregator: CandleAggregator,
    private val signalDao: SignalDao,
    private val savedSignalDao: SavedSignalDao,
    private val signalEngine: SignalEngine,
    private val appScope: CoroutineScope,
    private val historicalCandles: HistoricalCandleRepository
) {
    private val instrumentCache = ConcurrentHashMap<String, Instrument>()
    private val lastEvalMillis = ConcurrentHashMap<String, Long>()
    private val dismissedSignatures = ConcurrentHashMap<String, Long>()

    private val historyMutex = Mutex()
    private val historyFetchedAt = ConcurrentHashMap<String, Long>()

    /** Re-emits periodically so signals disappear when they expire even without new DB writes. */
    private val expiryClock: Flow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(30_000L)
        }
    }

    val activeSignals: Flow<List<SignalUiItem>> = combine(
        signalDao.observeAll(),
        savedSignalDao.observeSavedIds(),
        expiryClock
    ) { entities, savedIds, _ ->
        val savedSet = savedIds.toSet()
        val now = Instant.now()
        entities.mapNotNull { entity ->
            if (entity.userStatus == "DISMISSED") return@mapNotNull null
            val domain = entity.toDomain()
            if (domain.expiresAt != null && !domain.expiresAt.isAfter(now)) {
                return@mapNotNull null
            }
            SignalUiItem(
                signal = domain,
                userStatus = entity.userStatus,
                isSaved = domain.id in savedSet
            )
        }.sortedWith(
            compareByDescending<SignalUiItem> { it.signal.confidence.rank }
                .thenByDescending { it.signal.generatedAt.epochSecond }
        )
    }

    val savedSignals: Flow<List<SignalUiItem>> = savedSignalDao.observeAll().map { entities ->
        entities.map { entity ->
            SignalUiItem(
                signal = entity.toDomain(),
                userStatus = "SAVED",
                isSaved = true
            )
        }
    }

    fun observeSignalsForSymbol(symbol: String): Flow<List<SignalUiItem>> = combine(
        signalDao.observeForSymbol(symbol),
        savedSignalDao.observeSavedIds(),
        expiryClock
    ) { entities, savedIds, _ ->
        val savedSet = savedIds.toSet()
        val now = Instant.now()
        entities.mapNotNull { entity ->
            if (entity.userStatus == "DISMISSED") return@mapNotNull null
            val domain = entity.toDomain()
            if (domain.expiresAt != null && !domain.expiresAt.isAfter(now)) return@mapNotNull null
            SignalUiItem(domain, entity.userStatus, domain.id in savedSet)
        }
    }

    init {
        appScope.launch(Dispatchers.Default) {
            marketDataRepository.ensureSeeded()
            val universe = instrumentDao.getAllOnce().map { it.toDomain() }
            universe.forEach {
                instrumentCache[it.symbol] = it
                candleAggregator.registerSegment(it.symbol, it.segment)
            }
            candleAggregator.warmUpFromDatabaseOrSeed(universe)
            // Initial scan once on warmed-up candles
            universe.forEach { evaluateSymbol(it, Instant.now()) }
            // Replace synthetic warm-up bars with real exchange history where available, then rescan.
            backfillHistory(universe)
        }

        // Collect live/simulated ticks cleanly without ever blocking ExecutionEngine
        appScope.launch(Dispatchers.Default) {
            marketDataRepository.ticks.collect { tick ->
                val inst = instrumentCache[tick.symbol]
                    ?: instrumentDao.getBySymbol(tick.symbol)?.toDomain()?.also { instrumentCache[it.symbol] = it }
                if (inst != null) {
                    instrumentCache[tick.symbol] = inst.copy(ltp = tick.ltp)
                    if (candleAggregator.segmentOf(tick.symbol) == null) {
                        candleAggregator.registerSegment(tick.symbol, inst.segment)
                    }
                }
                val completed = candleAggregator.onTick(tick)
                if (completed.isNotEmpty() && inst != null) {
                    val now = Instant.ofEpochMilli(
                        tick.timestampMillis.takeIf { it > 0L } ?: System.currentTimeMillis()
                    )
                    val prevEval = lastEvalMillis[tick.symbol] ?: 0L
                    if (now.toEpochMilli() - prevEval >= MIN_EVAL_INTERVAL_MS) {
                        lastEvalMillis[tick.symbol] = now.toEpochMilli()
                        evaluateSymbol(inst.copy(ltp = tick.ltp), now)
                    }
                }
            }
        }
    }

    suspend fun refreshAllSignals() {
        val universe = instrumentDao.getAllOnce().map { it.toDomain() }
        universe.forEach { instrumentCache[it.symbol] = it }
        candleAggregator.warmUpFromDatabaseOrSeed(universe)
        val now = Instant.now()
        signalDao.deleteExpired(now.toEpochMilli())
        universe.forEach { evaluateSymbol(it, now) }
    }

    suspend fun acceptSignal(signalId: String) {
        signalDao.updateStatus(signalId, "ACCEPTED")
    }

    suspend fun dismissSignal(signal: TradeSignal) {
        dismissedSignatures[signature(signal)] = System.currentTimeMillis()
        signalDao.updateStatus(signal.id, "DISMISSED")
    }

    suspend fun toggleSaveSignal(signal: TradeSignal, currentlySaved: Boolean) {
        if (currentlySaved) {
            savedSignalDao.remove(signal.id)
        } else {
            savedSignalDao.save(signal.toSavedEntity())
        }
    }

    suspend fun getRecentCandles(symbol: String, timeframe: Timeframe): List<com.example.domain.signal.Candle> {
        return candleAggregator.getCandles(symbol, includeInProgress = true)[timeframe].orEmpty()
    }

    /**
     * Loads real exchange history for one symbol/timeframe (used by the chart). Cached for a few
     * minutes so switching timeframes back and forth does not hammer the APIs.
     */
    suspend fun loadHistory(symbol: String, timeframe: Timeframe, force: Boolean = false): Result<Int> {
        val inst = getInstrumentSnapshot(symbol)
            ?: return Result.failure(IllegalArgumentException("Unknown symbol $symbol"))
        candleAggregator.registerSegment(inst.symbol, inst.segment)
        val key = "$symbol|${timeframe.name}"
        val last = historyFetchedAt[key] ?: 0L
        if (!force && System.currentTimeMillis() - last < HISTORY_TTL_MS) {
            return Result.success(candleAggregator.getCandles(symbol)[timeframe]?.size ?: 0)
        }
        return historyMutex.withLock {
            historicalCandles.fetch(inst, timeframe).map { candles ->
                if (candles.isNotEmpty()) {
                    candleAggregator.seedHistory(symbol, timeframe, candles)
                    candleAggregator.markRealHistory(symbol, timeframe)
                }
                historyFetchedAt[key] = System.currentTimeMillis()
                candles.size
            }
        }
    }

    fun hasRealHistory(symbol: String, timeframe: Timeframe): Boolean =
        candleAggregator.hasRealHistory(symbol, timeframe)

    private suspend fun backfillHistory(universe: List<Instrument>) {
        universe.forEach { inst ->
            var anyLoaded = false
            for (tf in BACKFILL_TIMEFRAMES) {
                val res = loadHistory(inst.symbol, tf)
                if (res.isFailure) {
                    // No source for this instrument (e.g. no Angel One credentials): skip remaining TFs.
                    break
                }
                if ((res.getOrNull() ?: 0) > 0) anyLoaded = true
                delay(BACKFILL_THROTTLE_MS)
            }
            if (anyLoaded) {
                evaluateSymbol(instrumentCache[inst.symbol] ?: inst, Instant.now())
            }
        }
    }

    suspend fun getInstrumentSnapshot(symbol: String): Instrument? {
        return instrumentCache[symbol] ?: instrumentDao.getBySymbol(symbol)?.toDomain()?.also {
            instrumentCache[it.symbol] = it
        }
    }

    private suspend fun evaluateSymbol(instrument: Instrument, now: Instant) {
        signalDao.deleteExpired(now.toEpochMilli())
        // Evaluate on CLOSED candles only: the forming bar repaints and, right after a bucket roll,
        // is a single-tick bar that would corrupt every crossover / pattern check.
        val candlesByTf = candleAggregator.getCandles(instrument.symbol, includeInProgress = false)
        val context = SignalContext(
            symbol = instrument.symbol,
            segment = instrument.segment,
            candlesByTimeframe = candlesByTf,
            currentLtp = instrument.ltp,
            now = now
        )
        val generated = signalEngine.evaluate(context)
        if (generated.isEmpty()) return

        generated.forEach { sig ->
            val dismissedAt = dismissedSignatures[signature(sig)]
            val suppressed = dismissedAt != null && (now.toEpochMilli() - dismissedAt) < DISMISS_COOLDOWN_MS
            if (!suppressed) {
                signalDao.replacePreviousActive(sig.symbol, sig.strategy, sig.id)
                signalDao.upsert(sig.toEntity("ACTIVE"))
            }
        }
    }

    private fun signature(signal: TradeSignal): String =
        "${signal.symbol}|${signal.strategy.name}|${signal.direction.name}"

    companion object {
        private const val MIN_EVAL_INTERVAL_MS = 3_000L
        private const val DISMISS_COOLDOWN_MS = 15 * 60_000L
        private const val HISTORY_TTL_MS = 5 * 60_000L
        private const val BACKFILL_THROTTLE_MS = 400L
        private val BACKFILL_TIMEFRAMES = listOf(
            Timeframe.M1, Timeframe.M3, Timeframe.M5, Timeframe.M15,
            Timeframe.H1, Timeframe.H4, Timeframe.D1, Timeframe.W1
        )
    }
}
