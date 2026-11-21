package com.example.data.feed.live

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import android.util.Log
import com.example.domain.engine.OptionGreeksCalculator
import com.example.domain.model.InstrumentType
import com.example.domain.model.MarketSegment
import com.example.domain.model.OptionType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.io.InputStreamReader
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import javax.net.SocketFactory

data class SpotTokenInfo(
    val underlying: String,
    val exchange: String,
    val exchangeTypeCode: Int,
    val token: String,
    val label: String
)

/**
 * Memory-safe streaming loader & local TSV disk cache for Angel One's official OpenAPIScripMaster.
 *
 * Uses [android.util.JsonReader] directly over OkHttp's byte stream (~64 KB RAM footprint) so the
 * full 40+ MB master never gets buffered into a String or JSONArray. Retains all active:
 * - MCX commodities (Futures & Options: NATURALGAS, NATGASMINI, CRUDEOIL, GOLD, SILVER, COPPER, ZINC, etc.)
 * - NFO & BFO Index & Stock Options / Futures (complete live Option Chain expiries & strikes)
 * - NSE & BSE Cash Equities (-EQ) and Spot Indices (AMXIDX)
 */
@Singleton
class AngelScripMasterStore @Inject constructor(
    @ApplicationContext private val context: Context,
    @Named("liveWs") private val okHttpClient: OkHttpClient
) {
    private val loadMutex = Mutex()

    @Volatile
    private var entries: List<ScripEntry> = emptyList()

    @Volatile
    private var optionsByUnderlying: Map<String, List<ScripEntry>> = emptyMap()

    @Volatile
    private var futuresByName: Map<String, List<ScripEntry>> = emptyMap()

    @Volatile
    private var spotIndexByUnderlying: Map<String, SpotTokenInfo> = KNOWN_INDEX_SPOTS

    @Volatile
    private var lastLoadedAtMillis: Long = 0L

    private val downloadClient: OkHttpClient by lazy {
        okHttpClient.newBuilder()
            .socketFactory(SocketFactory.getDefault())
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .pingInterval(0, TimeUnit.MILLISECONDS)
            .build()
    }

    internal data class ScripEntry(
        val token: String,
        val tradingSymbol: String,
        val name: String,
        val expiry: String,
        val strike: BigDecimal?,
        val lotSize: Int,
        val instrumentType: String,
        val exchange: String,
        val tickSize: BigDecimal
    ) {
        fun toSearchResult(): AngelScripSearchResult {
            val optType = when {
                tradingSymbol.endsWith("CE") -> OptionType.CE
                tradingSymbol.endsWith("PE") -> OptionType.PE
                else -> null
            }
            val domainType = when {
                instrumentType.startsWith("OPT") || optType != null -> InstrumentType.OPTIONS
                instrumentType.startsWith("FUT") || tradingSymbol.endsWith("FUT") -> InstrumentType.FUTURES
                else -> InstrumentType.STOCK
            }
            val segment = when (exchange) {
                "BSE", "BFO" -> MarketSegment.BSE
                "MCX" -> MarketSegment.MCX
                else -> MarketSegment.NSE
            }
            val cleanSymbol = when {
                exchange == "NSE" && tradingSymbol.endsWith("-EQ") -> tradingSymbol.removeSuffix("-EQ")
                exchange == "BSE" -> "BSE:${tradingSymbol.removeSuffix("-EQ")}"
                else -> tradingSymbol
            }
            val displayName = when (domainType) {
                InstrumentType.OPTIONS -> "$exchange Option • $tradingSymbol"
                InstrumentType.FUTURES -> "$exchange Futures • $tradingSymbol"
                InstrumentType.STOCK -> if (name.isNotBlank() && !name.equals(cleanSymbol, true)) {
                    "$name ($exchange)"
                } else {
                    "$tradingSymbol ($exchange)"
                }
            }
            return AngelScripSearchResult(
                exchange = exchange,
                tradingSymbol = tradingSymbol,
                symbol = cleanSymbol,
                symbolToken = token,
                displayName = displayName,
                segment = segment,
                instrumentType = domainType,
                lotSize = lotSize.coerceAtLeast(1),
                tickSize = tickSize,
                expiryLabel = expiry.takeIf { it.isNotBlank() },
                strikePrice = strike,
                optionType = optType,
                exchangeTypeCode = AngelOnePriceFeed.exchangeNameToCode(exchange)
            )
        }
    }

    fun isLoaded(): Boolean = entries.isNotEmpty()

    suspend fun ensureLoaded(forceRefresh: Boolean = false): Result<Int> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!forceRefresh && entries.isNotEmpty() && (now - lastLoadedAtMillis) < MEMORY_TTL_MS) {
            return@withContext Result.success(entries.size)
        }

        loadMutex.withLock {
            val currentNow = System.currentTimeMillis()
            if (!forceRefresh && entries.isNotEmpty() && (currentNow - lastLoadedAtMillis) < MEMORY_TTL_MS) {
                return@withLock Result.success(entries.size)
            }

            val cacheFile = File(context.cacheDir, CACHE_FILE_NAME)
            if (!forceRefresh && cacheFile.exists() && (currentNow - cacheFile.lastModified()) < DISK_TTL_MS) {
                val diskParsed = runCatching { readDiskCache(cacheFile) }.getOrNull()
                if (!diskParsed.isNullOrEmpty()) {
                    installIndex(diskParsed)
                    lastLoadedAtMillis = currentNow
                    Log.d(TAG, "Loaded ${diskParsed.size} Angel One scrips from local cache")
                    return@withLock Result.success(diskParsed.size)
                }
            }

            var lastErr: Throwable? = null
            for (url in SCRIP_MASTER_URLS) {
                val fetched = runCatching { downloadAndStreamParse(url) }
                val list = fetched.getOrNull()
                if (!list.isNullOrEmpty()) {
                    installIndex(list)
                    lastLoadedAtMillis = System.currentTimeMillis()
                    runCatching { writeDiskCache(cacheFile, list) }
                    Log.d(TAG, "Downloaded & indexed ${list.size} Angel One scrips from $url")
                    return@withLock Result.success(list.size)
                }
                lastErr = fetched.exceptionOrNull()
            }

            // Fallback to stale cache if offline
            if (cacheFile.exists()) {
                val stale = runCatching { readDiskCache(cacheFile) }.getOrNull()
                if (!stale.isNullOrEmpty()) {
                    installIndex(stale)
                    lastLoadedAtMillis = System.currentTimeMillis()
                    return@withLock Result.success(stale.size)
                }
            }

            Result.failure(lastErr ?: IllegalStateException("Could not download Angel One Scrip Master"))
        }
    }

    fun search(
        rawQuery: String,
        segmentFilter: MarketSegment? = null,
        typeFilter: InstrumentType? = null,
        limit: Int = 30
    ): List<AngelScripSearchResult> {
        val snapshot = entries
        if (snapshot.isEmpty()) return emptyList()

        val tokens = rawQuery.trim().uppercase()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
        if (tokens.isEmpty()) return emptyList()
        val compact = tokens.joinToString("")
        val nowMillis = System.currentTimeMillis() - 86_400_000L // allow today's expiry

        val matches = ArrayList<Pair<Int, ScripEntry>>(64)
        for (item in snapshot) {
            // Segment filter
            if (segmentFilter != null) {
                val itemSeg = when (item.exchange) {
                    "BSE", "BFO" -> MarketSegment.BSE
                    "MCX" -> MarketSegment.MCX
                    else -> MarketSegment.NSE
                }
                if (itemSeg != segmentFilter) continue
            }

            // Type filter
            if (typeFilter != null) {
                val isOpt = item.instrumentType.startsWith("OPT") ||
                    item.tradingSymbol.endsWith("CE") || item.tradingSymbol.endsWith("PE")
                val isFut = item.instrumentType.startsWith("FUT") || item.tradingSymbol.endsWith("FUT")
                val matchesType = when (typeFilter) {
                    InstrumentType.OPTIONS -> isOpt
                    InstrumentType.FUTURES -> isFut
                    InstrumentType.STOCK -> !isOpt && !isFut
                }
                if (!matchesType) continue
            }

            // Skip expired derivatives
            if (item.expiry.isNotBlank()) {
                val expMs = OptionGreeksCalculator.parseExpiryToMillis(item.expiry)
                if (expMs != null && expMs < nowMillis) continue
            }

            val sym = item.tradingSymbol
            val nm = item.name
            val matchesAllTokens = tokens.all { t -> sym.contains(t) || nm.contains(t) }
            if (!matchesAllTokens && !sym.contains(compact) && !nm.contains(compact)) continue

            // Rank: exact name/symbol > prefix match > cash/near-month futures > options
            val score = when {
                nm == compact && item.exchange == "MCX" && item.instrumentType == "FUTCOM" -> 0
                sym == "$compact-EQ" || sym == compact -> 1
                nm == compact && item.instrumentType.startsWith("FUT") -> 2
                nm == compact && item.instrumentType.startsWith("OPT") -> 3
                nm.startsWith(compact) && item.exchange == "MCX" -> 4
                sym.startsWith(compact) -> 5
                nm.startsWith(compact) -> 6
                else -> 10
            }
            matches.add(score to item)
        }

        return matches
            .sortedWith(
                compareBy<Pair<Int, ScripEntry>>(
                    { it.first },
                    { OptionGreeksCalculator.parseExpiryToMillis(it.second.expiry) ?: Long.MAX_VALUE },
                    { it.second.strike ?: BigDecimal.ZERO },
                    { it.second.tradingSymbol }
                )
            )
            .take(limit)
            .map { it.second.toSearchResult() }
    }

    fun getAvailableExpiries(underlying: String): List<String> {
        val key = canonicalUnderlying(underlying)
        val contracts = optionsByUnderlying[key].orEmpty()
        val cutoff = System.currentTimeMillis() - 86_400_000L
        return contracts
            .map { it.expiry }
            .filter { it.isNotBlank() }
            .distinct()
            .mapNotNull { exp ->
                val ms = OptionGreeksCalculator.parseExpiryToMillis(exp) ?: return@mapNotNull null
                if (ms >= cutoff) exp to ms else null
            }
            .sortedBy { it.second }
            .map { it.first }
    }

    fun getOptionChainContracts(underlying: String, expiry: String): List<AngelScripSearchResult> {
        val key = canonicalUnderlying(underlying)
        val cleanExpiry = expiry.trim().uppercase()
        return optionsByUnderlying[key].orEmpty()
            .filter { it.expiry.equals(cleanExpiry, ignoreCase = true) && it.strike != null }
            .sortedWith(compareBy<ScripEntry>({ it.strike }, { it.tradingSymbol }))
            .map { it.toSearchResult() }
    }

    fun getSpotLookup(underlying: String): SpotTokenInfo? {
        val key = canonicalUnderlying(underlying)
        // 1. Known or parsed index spot token (NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX, BANKEX)
        spotIndexByUnderlying[key]?.let { return it }

        // 2. For MCX commodities, spot reference is the nearest active MCX Future contract
        val mcxFut = findNearestActiveFuture(key, "MCX")
        if (mcxFut != null) {
            return SpotTokenInfo(
                underlying = key,
                exchange = mcxFut.exchange,
                exchangeTypeCode = mcxFut.exchangeTypeCode,
                token = mcxFut.symbolToken,
                label = mcxFut.tradingSymbol
            )
        }

        // 3. For F&O stocks, spot reference is NSE Cash Equity (-EQ)
        val eq = entries.firstOrNull {
            it.exchange == "NSE" && (it.tradingSymbol == "$key-EQ" || it.name == key)
        }
        if (eq != null) {
            return SpotTokenInfo(
                underlying = key,
                exchange = "NSE",
                exchangeTypeCode = 1,
                token = eq.token,
                label = key
            )
        }

        // 4. Fallback to nearest NFO/BFO future
        val fut = findNearestActiveFuture(key, "NFO") ?: findNearestActiveFuture(key, "BFO")
        return fut?.let {
            SpotTokenInfo(
                underlying = key,
                exchange = it.exchange,
                exchangeTypeCode = it.exchangeTypeCode,
                token = it.symbolToken,
                label = it.tradingSymbol
            )
        }
    }

    fun findNearestActiveFuture(name: String, exchange: String): AngelScripSearchResult? {
        val key = canonicalUnderlying(name)
        val cutoff = System.currentTimeMillis() - 86_400_000L
        return futuresByName[key].orEmpty()
            .filter { it.exchange.equals(exchange, ignoreCase = true) }
            .mapNotNull { entry ->
                val expMs = OptionGreeksCalculator.parseExpiryToMillis(entry.expiry) ?: Long.MAX_VALUE
                if (expMs >= cutoff) entry to expMs else null
            }
            .minByOrNull { it.second }
            ?.first
            ?.toSearchResult()
    }

    private fun downloadAndStreamParse(url: String): List<ScripEntry> {
        val req = Request.Builder()
            .url(url)
            .get()
            .addHeader("Accept", "application/json")
            .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14) PaperPulse/1.0")
            .build()

        downloadClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("Scrip master HTTP ${resp.code}")
            }
            val body = resp.body ?: throw IllegalStateException("Empty scrip master body")
            val parsed = ArrayList<ScripEntry>(16_000)
            val dynamicSpots = HashMap<String, SpotTokenInfo>(KNOWN_INDEX_SPOTS)

            JsonReader(InputStreamReader(body.byteStream(), Charsets.UTF_8)).use { reader ->
                reader.beginArray()
                while (reader.hasNext()) {
                    var token = ""
                    var symbol = ""
                    var name = ""
                    var expiry = ""
                    var strikeRaw = ""
                    var lotRaw = ""
                    var instType = ""
                    var exchSeg = ""
                    var tickRaw = ""

                    reader.beginObject()
                    while (reader.hasNext()) {
                        when (reader.nextName()) {
                            "token" -> token = readStringSafe(reader)
                            "symbol" -> symbol = readStringSafe(reader)
                            "name" -> name = readStringSafe(reader)
                            "expiry" -> expiry = readStringSafe(reader)
                            "strike" -> strikeRaw = readStringSafe(reader)
                            "lotsize" -> lotRaw = readStringSafe(reader)
                            "instrumenttype" -> instType = readStringSafe(reader)
                            "exch_seg" -> exchSeg = readStringSafe(reader)
                            "tick_size" -> tickRaw = readStringSafe(reader)
                            else -> reader.skipValue()
                        }
                    }
                    reader.endObject()

                    val exch = exchSeg.trim().uppercase()
                    val iType = instType.trim().uppercase()
                    val sym = symbol.trim().uppercase()
                    val nm = name.trim().uppercase()
                    val tok = token.trim()
                    if (tok.isBlank() || sym.isBlank()) continue

                    // Capture authoritative Spot Index tokens (AMXIDX) if present
                    if (iType == "AMXIDX") {
                        captureSpotIndex(exch, sym, nm, tok, dynamicSpots)
                        continue
                    }

                    val keep = when (exch) {
                        "MCX" -> iType == "FUTCOM" || iType == "OPTFUT"
                        "NFO" -> iType == "OPTIDX" || iType == "FUTIDX" || iType == "FUTSTK" || iType == "OPTSTK"
                        "BFO" -> iType == "OPTIDX" || iType == "FUTIDX"
                        "NSE" -> iType.isBlank() && sym.endsWith("-EQ")
                        "BSE" -> iType.isBlank() && !sym.endsWith("-BE") && !sym.endsWith("-T") && sym.length <= 16
                        else -> false
                    }
                    if (!keep) continue

                    val strike = parseMasterStrike(strikeRaw, iType)
                    val lot = lotRaw.toIntOrNull()?.takeIf { it > 0 } ?: 1
                    val tick = parseMasterTickSize(tickRaw, iType)
                    parsed.add(
                        ScripEntry(
                            token = tok,
                            tradingSymbol = sym,
                            name = nm,
                            expiry = expiry.trim().uppercase(),
                            strike = strike,
                            lotSize = lot,
                            instrumentType = iType,
                            exchange = exch,
                            tickSize = tick
                        )
                    )
                }
                reader.endArray()
            }
            spotIndexByUnderlying = dynamicSpots
            return parsed
        }
    }

    private fun installIndex(list: List<ScripEntry>) {
        entries = list
        val optMap = HashMap<String, MutableList<ScripEntry>>(256)
        val futMap = HashMap<String, MutableList<ScripEntry>>(256)
        for (e in list) {
            if (e.instrumentType.startsWith("OPT")) {
                optMap.getOrPut(canonicalUnderlying(e.name)) { ArrayList() }.add(e)
            } else if (e.instrumentType.startsWith("FUT")) {
                futMap.getOrPut(canonicalUnderlying(e.name)) { ArrayList() }.add(e)
            }
        }
        optionsByUnderlying = optMap
        futuresByName = futMap
    }

    private fun writeDiskCache(file: File, list: List<ScripEntry>) {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        BufferedWriter(FileWriter(tmp)).use { w ->
            for (e in list) {
                w.append(e.token).append('\t')
                    .append(e.tradingSymbol).append('\t')
                    .append(e.name).append('\t')
                    .append(e.expiry).append('\t')
                    .append(e.strike?.toPlainString().orEmpty()).append('\t')
                    .append(e.lotSize.toString()).append('\t')
                    .append(e.instrumentType).append('\t')
                    .append(e.exchange).append('\t')
                    .append(e.tickSize.toPlainString())
                    .append('\n')
            }
        }
        if (file.exists()) file.delete()
        tmp.renameTo(file)
    }

    private fun readDiskCache(file: File): List<ScripEntry> {
        val out = ArrayList<ScripEntry>(16_000)
        BufferedReader(FileReader(file)).use { r ->
            var line = r.readLine()
            while (line != null) {
                val p = line.split('\t')
                if (p.size >= 9) {
                    out.add(
                        ScripEntry(
                            token = p[0],
                            tradingSymbol = p[1],
                            name = p[2],
                            expiry = p[3],
                            strike = p[4].toBigDecimalOrNull(),
                            lotSize = p[5].toIntOrNull() ?: 1,
                            instrumentType = p[6],
                            exchange = p[7],
                            tickSize = p[8].toBigDecimalOrNull() ?: BigDecimal("0.05")
                        )
                    )
                }
                line = r.readLine()
            }
        }
        return out
    }

    private fun captureSpotIndex(
        exch: String,
        sym: String,
        nm: String,
        tok: String,
        out: MutableMap<String, SpotTokenInfo>
    ) {
        val mapped = when {
            exch == "NSE" && (sym == "NIFTY" || sym == "NIFTY 50" || nm == "NIFTY") -> "NIFTY"
            exch == "NSE" && (sym == "BANKNIFTY" || sym == "NIFTY BANK" || nm == "BANKNIFTY") -> "BANKNIFTY"
            exch == "NSE" && (sym == "FINNIFTY" || sym == "NIFTY FIN SERVICE" || nm == "FINNIFTY") -> "FINNIFTY"
            exch == "NSE" && (sym == "MIDCPNIFTY" || sym.contains("MID SELECT") || nm == "MIDCPNIFTY") -> "MIDCPNIFTY"
            exch == "BSE" && (sym == "SENSEX" || nm == "SENSEX") -> "SENSEX"
            exch == "BSE" && (sym == "BANKEX" || nm == "BANKEX") -> "BANKEX"
            else -> null
        }
        if (mapped != null) {
            out[mapped] = SpotTokenInfo(
                underlying = mapped,
                exchange = exch,
                exchangeTypeCode = AngelOnePriceFeed.exchangeNameToCode(exch),
                token = tok,
                label = sym
            )
        }
    }

    private fun readStringSafe(reader: JsonReader): String {
        return when (reader.peek()) {
            JsonToken.NULL -> {
                reader.nextNull()
                ""
            }
            JsonToken.STRING -> reader.nextString()
            JsonToken.NUMBER -> reader.nextString()
            JsonToken.BOOLEAN -> reader.nextBoolean().toString()
            else -> {
                reader.skipValue()
                ""
            }
        }
    }

    companion object {
        private const val TAG = "AngelScripMaster"
        private const val CACHE_FILE_NAME = "angel_scrip_master_v2.tsv"
        private const val MEMORY_TTL_MS = 6L * 3600L * 1000L
        private const val DISK_TTL_MS = 12L * 3600L * 1000L

        internal val SCRIP_MASTER_URLS = listOf(
            "https://margincalculator.angelone.in/OpenAPI_File/files/OpenAPIScripMaster.json",
            "https://margincalculator.angelbroking.com/OpenAPI_File/files/OpenAPIScripMaster.json"
        )

        internal val KNOWN_INDEX_SPOTS = mapOf(
            "NIFTY" to SpotTokenInfo("NIFTY", "NSE", 1, "99926000", "NIFTY 50"),
            "BANKNIFTY" to SpotTokenInfo("BANKNIFTY", "NSE", 1, "99926009", "NIFTY BANK"),
            "FINNIFTY" to SpotTokenInfo("FINNIFTY", "NSE", 1, "99926037", "NIFTY FIN SERVICE"),
            "MIDCPNIFTY" to SpotTokenInfo("MIDCPNIFTY", "NSE", 1, "99926074", "NIFTY MIDCAP SELECT"),
            "SENSEX" to SpotTokenInfo("SENSEX", "BSE", 3, "99919000", "SENSEX"),
            "BANKEX" to SpotTokenInfo("BANKEX", "BSE", 3, "99919012", "BANKEX")
        )

        internal fun canonicalUnderlying(raw: String): String {
            return when (val u = raw.trim().uppercase()) {
                "NIFTY 50", "NIFTY50" -> "NIFTY"
                "NIFTY BANK", "BANK NIFTY" -> "BANKNIFTY"
                "NIFTY FIN SERVICE", "FIN NIFTY" -> "FINNIFTY"
                "MIDCAP NIFTY", "MIDCAP" -> "MIDCPNIFTY"
                "NATURAL GAS", "NATGAS" -> "NATURALGAS"
                "CRUDE OIL", "CRUDE" -> "CRUDEOIL"
                else -> u
            }
        }

        internal fun parseMasterStrike(raw: String, instType: String): BigDecimal? {
            if (!instType.startsWith("OPT")) return null
            val bd = raw.toBigDecimalOrNull() ?: return null
            if (bd <= BigDecimal.ZERO) return null
            // Angel One OpenAPIScripMaster scales strike prices by 100.0 (e.g. "2450000.000000" -> 24500.00)
            return bd.divide(BigDecimal("100"), 2, RoundingMode.HALF_UP)
        }

        internal fun parseMasterTickSize(raw: String, instType: String): BigDecimal {
            val bd = raw.toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO } ?: return BigDecimal("0.05")
            return if (bd >= BigDecimal.ONE && !instType.startsWith("FUT")) {
                bd.divide(BigDecimal("100"), 2, RoundingMode.HALF_UP).max(BigDecimal("0.05"))
            } else {
                bd.setScale(2, RoundingMode.HALF_UP)
            }
        }
    }
}
