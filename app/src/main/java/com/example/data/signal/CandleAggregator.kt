package com.example.data.signal

import com.example.data.local.CandleDao
import com.example.data.local.toDomain
import com.example.data.local.toEntity
import com.example.domain.analysis.SessionCalendar
import com.example.domain.model.Instrument
import com.example.domain.model.MarketSegment
import com.example.domain.model.PriceTick
import com.example.domain.signal.Candle
import com.example.domain.signal.Timeframe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class CompletedCandleEvent(
    val symbol: String,
    val timeframe: Timeframe,
    val candle: Candle
)

@Singleton
class CandleAggregator @Inject constructor(
    private val candleDao: CandleDao?,
    private val appScope: CoroutineScope?
) {
    private data class MutableCandle(
        val bucketStartMillis: Long,
        val open: Double,
        var high: Double,
        var low: Double,
        var close: Double,
        var volume: Double,
        var delta: Double?
    ) {
        fun toImmutable(): Candle = Candle(
            timestamp = Instant.ofEpochMilli(bucketStartMillis),
            open = open,
            high = high,
            low = low,
            close = close,
            volume = volume,
            delta = delta
        )
    }

    private val closedHistory = ConcurrentHashMap<String, MutableMap<Timeframe, ArrayDeque<Candle>>>()
    private val currentBars = ConcurrentHashMap<String, MutableMap<Timeframe, MutableCandle>>()
    private val lastCumVolume = ConcurrentHashMap<String, Long>()
    private val symbolLocks = ConcurrentHashMap<String, Any>()
    private val symbolSegments = ConcurrentHashMap<String, MarketSegment>()

    private val _completedCandles = MutableSharedFlow<CompletedCandleEvent>(extraBufferCapacity = 256)
    val completedCandles: SharedFlow<CompletedCandleEvent> = _completedCandles.asSharedFlow()

    constructor() : this(candleDao = null, appScope = null)

    /** Registers the exchange segment so bars align to the exchange session (09:15 IST for NSE/BSE, etc.). */
    fun registerSegment(symbol: String, segment: MarketSegment) {
        symbolSegments[symbol] = segment
    }

    fun segmentOf(symbol: String): MarketSegment? = symbolSegments[symbol]

    fun onTick(tick: PriceTick): List<CompletedCandleEvent> {
        val price = tick.ltp.toDouble()
        if (price <= 0.0) return emptyList()
        val ts = tick.timestampMillis.takeIf { it > 0L } ?: System.currentTimeMillis()
        val qty = tick.tradeQuantity
        val volume = if (qty != null && qty >= 0.0) qty else computeVolumeDelta(tick.symbol, tick.volume)
        val side = tick.buyerInitiated
        val signed = if (qty != null && side != null) (if (side) qty else -qty) else null
        return onTick(
            symbol = tick.symbol,
            price = price,
            volume = volume,
            timestampMillis = ts,
            signedVolume = signed
        )
    }

    fun onTick(
        symbol: String,
        price: Double,
        volume: Double,
        timestampMillis: Long,
        signedVolume: Double? = null
    ): List<CompletedCandleEvent> {
        if (price <= 0.0) return emptyList()
        val lock = symbolLocks.computeIfAbsent(symbol) { Any() }
        val completedEvents = mutableListOf<CompletedCandleEvent>()
        val segment = symbolSegments[symbol]
        val vol = volume.coerceAtLeast(0.0)

        synchronized(lock) {
            val tfHistory = closedHistory.getOrPut(symbol) { mutableMapOf() }
            val tfActive = currentBars.getOrPut(symbol) { mutableMapOf() }

            Timeframe.entries.forEach { tf ->
                val bucketStart = SessionCalendar.bucketStart(tf, timestampMillis, segment)
                val existing = tfActive[tf]
                when {
                    existing == null -> {
                        tfActive[tf] = MutableCandle(bucketStart, price, price, price, price, vol, signedVolume)
                    }
                    bucketStart > existing.bucketStartMillis -> {
                        val finalized = existing.toImmutable()
                        val queue = tfHistory.getOrPut(tf) { ArrayDeque() }
                        appendClosedLocked(queue, finalized)
                        completedEvents.add(CompletedCandleEvent(symbol, tf, finalized))
                        persistCandleAsync(symbol, tf, finalized)
                        tfActive[tf] = MutableCandle(bucketStart, price, price, price, price, vol, signedVolume)
                    }
                    bucketStart == existing.bucketStartMillis -> {
                        existing.high = max(existing.high, price)
                        existing.low = min(existing.low, price)
                        existing.close = price
                        existing.volume += vol
                        val runningDelta = existing.delta
                        existing.delta = when {
                            signedVolume == null -> runningDelta
                            runningDelta == null -> signedVolume
                            else -> runningDelta + signedVolume
                        }
                    }
                    else -> {
                        // Out-of-order stale tick; ignore safely
                    }
                }
            }
        }

        completedEvents.forEach { _completedCandles.tryEmit(it) }
        return completedEvents
    }

    fun getCandles(symbol: String, includeInProgress: Boolean = false): Map<Timeframe, List<Candle>> {
        val lock = symbolLocks.computeIfAbsent(symbol) { Any() }
        synchronized(lock) {
            val tfHistory = closedHistory[symbol].orEmpty()
            val tfActive = currentBars[symbol].orEmpty()
            return Timeframe.entries.associateWith { tf ->
                val closed = tfHistory[tf]?.toList().orEmpty()
                val active = if (includeInProgress) tfActive[tf]?.toImmutable() else null
                if (active != null) closed + active else closed
            }
        }
    }

    /**
     * Replaces the closed history of one timeframe with real exchange candles (Binance klines /
     * Angel One getCandleData). The bar that is still forming is kept out of the closed history.
     */
    fun seedHistory(symbol: String, tf: Timeframe, candles: List<Candle>, nowMillis: Long = System.currentTimeMillis()) {
        if (candles.isEmpty()) return
        val lock = symbolLocks.computeIfAbsent(symbol) { Any() }
        val segment = symbolSegments[symbol]
        val currentBucket = SessionCalendar.bucketStart(tf, nowMillis, segment)
        val sorted = candles
            .filter { it.close > 0.0 && it.high >= it.low }
            .sortedBy { it.timestamp.toEpochMilli() }
            .distinctBy { it.timestamp.toEpochMilli() }
        val closed = sorted.filter { it.timestamp.toEpochMilli() < currentBucket }.takeLast(MAX_BARS_PER_TF)
        val forming = sorted.lastOrNull()?.takeIf { it.timestamp.toEpochMilli() >= currentBucket }
        synchronized(lock) {
            val tfHistory = closedHistory.getOrPut(symbol) { mutableMapOf() }
            val queue = ArrayDeque<Candle>()
            closed.forEach { queue.addLast(it) }
            tfHistory[tf] = queue
            if (forming != null) {
                val tfActive = currentBars.getOrPut(symbol) { mutableMapOf() }
                val active = tfActive[tf]
                if (active == null || active.bucketStartMillis <= forming.timestamp.toEpochMilli()) {
                    tfActive[tf] = MutableCandle(
                        bucketStartMillis = forming.timestamp.toEpochMilli(),
                        open = forming.open,
                        high = max(forming.high, active?.high ?: forming.high),
                        low = min(forming.low, active?.low ?: forming.low),
                        close = active?.close ?: forming.close,
                        volume = forming.volume,
                        delta = forming.delta
                    )
                }
            }
        }
        val dao = candleDao ?: return
        val scope = appScope ?: return
        scope.launch(Dispatchers.IO) {
            runCatching {
                dao.deleteFor(symbol, tf)
                dao.upsertAll(closed.map { it.toEntity(symbol, tf) })
            }
        }
    }

    fun hasRealHistory(symbol: String, tf: Timeframe): Boolean = realHistoryKeys.contains("$symbol|${tf.name}")

    fun markRealHistory(symbol: String, tf: Timeframe) {
        realHistoryKeys.add("$symbol|${tf.name}")
    }

    private val realHistoryKeys: MutableSet<String> = ConcurrentHashMap.newKeySet()

    suspend fun warmUpFromDatabaseOrSeed(
        instruments: List<Instrument>,
        nowMillis: Long = System.currentTimeMillis()
    ) {
        instruments.forEach { inst ->
            registerSegment(inst.symbol, inst.segment)
            val lock = symbolLocks.computeIfAbsent(inst.symbol) { Any() }
            val toPersist = mutableListOf<com.example.data.local.CandleEntity>()
            val dbLoaded = mutableMapOf<Timeframe, List<Candle>>()

            if (candleDao != null) {
                val dao = candleDao
                Timeframe.entries.forEach { tf ->
                    val rows = runCatching { dao.getRecent(inst.symbol, tf, MAX_BARS_PER_TF) }.getOrDefault(emptyList())
                    if (rows.isNotEmpty()) {
                        dbLoaded[tf] = rows.sortedBy { it.timestampMillis }.map { it.toDomain() }
                    }
                }
            }

            synchronized(lock) {
                val tfHistory = closedHistory.getOrPut(inst.symbol) { mutableMapOf() }
                Timeframe.entries.forEach { tf ->
                    val existingQueue = tfHistory.getOrPut(tf) { ArrayDeque() }
                    val fromDb = dbLoaded[tf].orEmpty()
                    if (existingQueue.isEmpty() && fromDb.isNotEmpty()) {
                        fromDb.takeLast(MAX_BARS_PER_TF).forEach { existingQueue.addLast(it) }
                    }
                    if (existingQueue.size < WARMUP_MIN_BARS) {
                        val bootstrap = generateBootstrapCandles(inst, tf, nowMillis)
                        existingQueue.clear()
                        bootstrap.forEach { candle ->
                            existingQueue.addLast(candle)
                            toPersist.add(candle.toEntity(inst.symbol, tf))
                        }
                    }
                }
            }

            val dao = candleDao
            if (dao != null && toPersist.isNotEmpty()) {
                runCatching { dao.upsertAll(toPersist) }
            }
        }
    }

    private fun bucketBack(tf: Timeframe, latestBucket: Long, stepsBack: Int, segment: MarketSegment?): Long {
        return if (tf == Timeframe.MN1) {
            val zone = SessionCalendar.zoneFor(segment)
            Instant.ofEpochMilli(latestBucket).atZone(zone).minusMonths(stepsBack.toLong()).toInstant().toEpochMilli()
        } else {
            latestBucket - stepsBack.toLong() * tf.durationMillis
        }
    }

    /**
     * Synthetic warm-up history used only until real exchange history is fetched (or when offline).
     * Long enough for EMA 200 so the 50/200 models work on the simulator too.
     */
    private fun generateBootstrapCandles(
        instrument: Instrument,
        tf: Timeframe,
        nowMillis: Long
    ): List<Candle> {
        val barCount = BOOTSTRAP_BARS
        val latestBucket = SessionCalendar.bucketStart(tf, nowMillis, instrument.segment)
        val baseLtp = instrument.ltp.toDouble().coerceAtLeast(1.0)
        val prevClose = instrument.previousClose.toDouble().coerceAtLeast(1.0)
        val spark = instrument.sparkline.map { it.toDouble() }.ifEmpty { listOf(prevClose, baseLtp) }
        val delta = baseLtp - prevClose
        val stepBias = delta / barCount.toDouble()
        val amplitude = max(abs(delta) * 0.18, baseLtp * 0.0025)
        val baseVol = max(1200.0, (instrument.volume.toDouble() / 64.0))

        val list = ArrayList<Candle>(barCount)
        var runningOpen = prevClose
        for (i in 0 until barCount) {
            val bucketMillis = bucketBack(tf, latestBucket, barCount - i, instrument.segment)
            val sparkSample = spark[(i * spark.size) / barCount]
            val wave = kotlin.math.sin(i * 0.55) * amplitude * 0.35 + kotlin.math.sin(i * 0.07) * amplitude * 1.6
            val targetClose = if (i == barCount - 1) {
                baseLtp
            } else {
                ((prevClose + stepBias * (i + 1)) * 0.7 + sparkSample * 0.3 + wave).coerceAtLeast(0.05)
            }
            val bodyHigh = max(runningOpen, targetClose)
            val bodyLow = min(runningOpen, targetClose)
            val wick = amplitude * (0.25 + ((i % 3) * 0.12))
            val high = bodyHigh + wick
            val low = max(0.01, bodyLow - wick * 0.8)
            val vol = baseVol * (0.75 + ((i % 5) * 0.18))
            list.add(
                Candle(
                    timestamp = Instant.ofEpochMilli(bucketMillis),
                    open = runningOpen,
                    high = high,
                    low = low,
                    close = targetClose,
                    volume = vol
                )
            )
            runningOpen = targetClose
        }
        return list
    }

    /**
     * Converts the feed's cumulative day volume into per-tick volume. Unknown volume counts as 0
     * (tick-volume proxies are added by the simulator itself); a lower cumulative value means the
     * exchange day rolled over, so the new cumulative value is the increment.
     */
    private fun computeVolumeDelta(symbol: String, reportedVol: Long?): Double {
        if (reportedVol == null || reportedVol <= 0L) return 0.0
        val prev = lastCumVolume.put(symbol, reportedVol)
        return when {
            prev == null -> 0.0
            reportedVol > prev -> (reportedVol - prev).toDouble()
            reportedVol < prev -> reportedVol.toDouble()
            else -> 0.0
        }
    }

    private fun appendClosedLocked(queue: ArrayDeque<Candle>, candle: Candle) {
        if (queue.isNotEmpty() && queue.last().timestamp == candle.timestamp) {
            queue.removeLast()
        }
        queue.addLast(candle)
        while (queue.size > MAX_BARS_PER_TF) {
            queue.removeFirst()
        }
    }

    private fun persistCandleAsync(symbol: String, tf: Timeframe, candle: Candle) {
        val dao = candleDao ?: return
        val scope = appScope ?: return
        scope.launch(Dispatchers.IO) {
            runCatching {
                dao.upsert(candle.toEntity(symbol, tf))
                val cutoff = candle.timestamp.toEpochMilli() - (MAX_BARS_PER_TF.toLong() * tf.durationMillis)
                dao.pruneOlderThan(symbol, tf, cutoff)
            }
        }
    }

    companion object {
        const val MAX_BARS_PER_TF = 500
        const val WARMUP_MIN_BARS = 60
        const val BOOTSTRAP_BARS = 260
    }
}
