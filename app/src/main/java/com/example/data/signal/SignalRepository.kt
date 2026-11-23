package com.example.data.signal

import com.example.data.history.HistoricalCandleRepository
import com.example.data.local.InstrumentDao
import com.example.data.local.SavedSignalDao
import com.example.data.local.SignalDao
import com.example.data.local.SignalEntity
import com.example.data.local.SignalRecordDao
import com.example.data.local.SignalRecordEntity
import com.example.data.local.toDomain
import com.example.data.local.toEntity
import com.example.data.local.toSavedEntity
import com.example.data.notifications.TradeNotificationManager
import com.example.data.repository.MarketDataRepository
import com.example.domain.engine.RiskMath
import com.example.domain.model.Instrument
import com.example.domain.signal.SignalAccuracyStats
import com.example.domain.signal.SignalConfidence
import com.example.domain.signal.SignalContext
import com.example.domain.signal.SignalDirection
import com.example.domain.signal.SignalEngine
import com.example.domain.signal.SignalOutcome
import com.example.domain.signal.SignalRecord
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
import java.math.BigDecimal
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
    private val signalRecordDao: SignalRecordDao,
    private val signalEngine: SignalEngine,
    private val appScope: CoroutineScope,
    private val historicalCandles: HistoricalCandleRepository,
    private val notificationManager: TradeNotificationManager
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

    val signalRecords: Flow<List<SignalRecord>> = signalRecordDao.observeAll().map { entities ->
        entities.map { it.toDomain() }
    }

    val signalAccuracy: Flow<SignalAccuracyStats> = signalRecords.map { records ->
        SignalAccuracyStats(
            totalResolved = records.size,
            t1Hits = records.count { it.outcome == SignalOutcome.HIT_T1 },
            t2Hits = records.count { it.outcome == SignalOutcome.HIT_T2 },
            slHits = records.count { it.outcome == SignalOutcome.HIT_SL },
            expiredCount = records.count { it.outcome == SignalOutcome.EXPIRED }
        )
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
            archiveAndPruneExpired(System.currentTimeMillis())
            universe.forEach { evaluateSymbol(it, Instant.now()) }
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
                val tickNowMillis = tick.timestampMillis.takeIf { it > 0L } ?: System.currentTimeMillis()
                // Track active signal performance (T1 / T2 / SL) and remove resolved signals from active noise
                evaluateActiveSignalOutcomes(tick.symbol, tick.ltp, tickNowMillis)

                val completed = candleAggregator.onTick(tick)
                if (completed.isNotEmpty() && inst != null) {
                    val now = Instant.ofEpochMilli(tickNowMillis)
                    val prevEval = lastEvalMillis[tick.symbol] ?: 0L
                    if (tickNowMillis - prevEval >= MIN_EVAL_INTERVAL_MS) {
                        lastEvalMillis[tick.symbol] = tickNowMillis
                        evaluateSymbol(inst.copy(ltp = tick.ltp), now)
                    }
                }
            }
        }
    }

    /**
     * Checks live LTP against active signals for [symbol].
     * - Immediately archives HIT_SL, HIT_T1, or HIT_T2 in signal_records and removes from active signals.
     * - Also upgrades recent HIT_T1 records to HIT_T2 if price continues to target2.
     */
    private suspend fun evaluateActiveSignalOutcomes(symbol: String, ltp: BigDecimal, nowMillis: Long) {
        val active = signalDao.getActiveForSymbol(symbol)
        for (entity in active) {
            if (entity.expiresAtMillis != null && entity.expiresAtMillis <= nowMillis) {
                archiveSignal(entity, SignalOutcome.EXPIRED, ltp, nowMillis)
                signalDao.deleteById(entity.id)
                continue
            }
            val isLong = entity.direction == SignalDirection.LONG
            val outcome = when {
                isLong && ltp <= entity.stopLoss -> SignalOutcome.HIT_SL
                !isLong && ltp >= entity.stopLoss -> SignalOutcome.HIT_SL
                isLong && ltp >= entity.target2 -> SignalOutcome.HIT_T2
                !isLong && ltp <= entity.target2 -> SignalOutcome.HIT_T2
                isLong && ltp >= entity.target1 -> SignalOutcome.HIT_T1
                !isLong && ltp <= entity.target1 -> SignalOutcome.HIT_T1
                else -> null
            }
            if (outcome != null) {
                archiveSignal(entity, outcome, ltp, nowMillis)
                signalDao.deleteById(entity.id)
                notificationManager.notifySignalEvent(
                    title = "Signal ${outcome.label}: ${entity.symbol}",
                    body = "${entity.direction.name} ${entity.strategy.shortLabel} (${entity.timeframe.label}) reached ${RiskMath.formatPrice(ltp, entity.segment)}"
                )
            }
        }

        // Upgrade recent T1-hit records to T2-hit if price keeps running to Target 2 within 24h
        val t1Records = signalRecordDao.getT1HitForSymbol(symbol)
        for (rec in t1Records) {
            if (nowMillis - rec.resolvedAtMillis > 24 * 60 * 60_000L) continue
            val isLong = rec.direction == SignalDirection.LONG
            val hitT2 = if (isLong) ltp >= rec.target2 else ltp <= rec.target2
            if (hitT2) {
                val pnlPct = computeSignalPnlPercent(rec.entryPrice, ltp, isLong)
                signalRecordDao.upsert(
                    rec.copy(
                        outcome = SignalOutcome.HIT_T2,
                        resolvedPrice = ltp,
                        pnlPercent = pnlPct,
                        resolvedAtMillis = nowMillis
                    )
                )
                notificationManager.notifySignalEvent(
                    title = "Signal Target 2 Hit: ${rec.symbol}",
                    body = "${rec.direction.name} ${rec.strategy.shortLabel} extended to T2 @ ${RiskMath.formatPrice(ltp, rec.segment)}"
                )
            }
        }
    }

    private suspend fun archiveAndPruneExpired(nowMillis: Long) {
        val expired = signalDao.getExpired(nowMillis)
        for (entity in expired) {
            if (entity.userStatus != "DISMISSED") {
                val ltp = instrumentCache[entity.symbol]?.ltp ?: entity.entryPrice
                archiveSignal(entity, SignalOutcome.EXPIRED, ltp, nowMillis)
            }
        }
        signalDao.deleteExpired(nowMillis)
    }

    private suspend fun archiveSignal(
        entity: SignalEntity,
        outcome: SignalOutcome,
        resolvedPrice: BigDecimal,
        nowMillis: Long
    ) {
        val isLong = entity.direction == SignalDirection.LONG
        val pnlPct = computeSignalPnlPercent(entity.entryPrice, resolvedPrice, isLong)
        signalRecordDao.upsert(
            SignalRecordEntity(
                id = entity.id,
                symbol = entity.symbol,
                segment = entity.segment,
                instrumentType = entity.instrumentType,
                strategy = entity.strategy,
                direction = entity.direction,
                timeframe = entity.timeframe,
                entryPrice = entity.entryPrice,
                stopLoss = entity.stopLoss,
                target1 = entity.target1,
                target2 = entity.target2,
                resolvedPrice = resolvedPrice,
                outcome = outcome,
                pnlPercent = pnlPct,
                confidence = entity.confidence,
                generatedAtMillis = entity.generatedAtMillis,
                resolvedAtMillis = nowMillis
            )
        )
    }

    private fun computeSignalPnlPercent(entry: BigDecimal, exit: BigDecimal, isLong: Boolean): Double {
        if (entry <= BigDecimal.ZERO) return 0.0
        val diff = if (isLong) exit.toDouble() - entry.toDouble() else entry.toDouble() - exit.toDouble()
        return (diff / entry.toDouble()) * 100.0
    }

    suspend fun clearSignalHistory() {
        signalRecordDao.clearAll()
    }

    suspend fun refreshAllSignals() {
        val universe = instrumentDao.getAllOnce().map { it.toDomain() }
        universe.forEach { instrumentCache[it.symbol] = it }
        candleAggregator.warmUpFromDatabaseOrSeed(universe)
        val now = Instant.now()
        archiveAndPruneExpired(now.toEpochMilli())
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
                if (res.isFailure) break
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
        archiveAndPruneExpired(now.toEpochMilli())
        val candlesByTf = candleAggregator.getCandles(instrument.symbol, includeInProgress = false)
        val context = SignalContext(
            symbol = instrument.symbol,
            segment = instrument.segment,
            candlesByTimeframe = candlesByTf,
            currentLtp = instrument.ltp,
            now = now,
            instrumentType = instrument.type
        )
        val generated = signalEngine.evaluate(context)
        if (generated.isEmpty()) return

        val existingIds = signalDao.getActiveForSymbol(instrument.symbol).map { it.id }.toSet()
        generated.forEach { rawSig ->
            val sig = rawSig.copy(instrumentType = instrument.type)
            val dismissedAt = dismissedSignatures[signature(sig)]
            val suppressed = dismissedAt != null && (now.toEpochMilli() - dismissedAt) < DISMISS_COOLDOWN_MS
            if (!suppressed) {
                signalDao.replacePreviousActive(sig.symbol, sig.strategy, sig.id)
                signalDao.upsert(sig.toEntity("ACTIVE"))
                if (sig.id !in existingIds && sig.confidence.rank >= SignalConfidence.MEDIUM.rank) {
                    notificationManager.notifySignalEvent(
                        title = "New ${sig.direction.name} Signal: ${sig.symbol}",
                        body = "${sig.strategy.shortLabel} (${sig.timeframe.label}) • Entry ${RiskMath.formatPrice(sig.entryPrice, sig.segment)} • T1 ${RiskMath.formatPrice(sig.target1, sig.segment)} • SL ${RiskMath.formatPrice(sig.stopLoss, sig.segment)}"
                    )
                }
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
