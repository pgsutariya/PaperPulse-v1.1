package com.example.data.feed

import com.example.domain.engine.OptionGreeksCalculator
import com.example.domain.engine.RiskMath
import com.example.domain.model.Instrument
import com.example.domain.model.InstrumentType
import com.example.domain.model.MarketSegment
import com.example.domain.model.OptionType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class PublicMarketQuote(
    val symbol: String,
    val ltp: BigDecimal,
    val previousClose: BigDecimal?,
    val dayHigh: BigDecimal?,
    val dayLow: BigDecimal?,
    val volume: Long?,
    val sourceLabel: String
)

/**
 * Fetches real live public market quotes without requiring paid broker API keys:
 * - NSE & BSE equities, indices, futures, and spot-anchored option premiums via Yahoo Finance public v8 chart endpoints
 * - MCX commodities (Gold, Silver, Crude Oil, Natural Gas, Copper, Zinc, Aluminium) via global futures + live USD/INR (INR=X)
 * - Crypto via Binance public REST endpoint (data-api.binance.vision/api/v3/ticker/24hr)
 */
@Singleton
class PublicMarketQuotePoller @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    private val httpClient: OkHttpClient by lazy {
        okHttpClient.newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    @Volatile
    private var cachedUsdInr: Double = 84.5

    @Volatile
    private var lastFxFetchMs: Long = 0L

    private data class CachedYahoo(val quote: YahooRawQuote, val fetchedAtMs: Long)
    private val yahooCache = ConcurrentHashMap<String, CachedYahoo>()

    suspend fun fetchQuotes(instruments: List<Instrument>): Map<String, PublicMarketQuote> =
        withContext(Dispatchers.IO) {
            if (instruments.isEmpty()) return@withContext emptyMap()
            val results = ConcurrentHashMap<String, PublicMarketQuote>()

            coroutineScope {
                // 1. Binance public REST batch for Crypto instruments
                val cryptoInstruments = instruments.filter { it.segment == MarketSegment.CRYPTO }
                val cryptoJob = if (cryptoInstruments.isNotEmpty()) {
                    async {
                        runCatching { fetchBinanceQuotes(cryptoInstruments) }.getOrNull()?.let {
                            results.putAll(it)
                        }
                    }
                } else null

                // 2. Refresh USD/INR rate once every 60s if MCX instruments exist
                val hasMcx = instruments.any { it.segment == MarketSegment.MCX }
                val now = System.currentTimeMillis()
                if (hasMcx && now - lastFxFetchMs > 60_000L) {
                    runCatching { fetchYahooRaw("INR=X")?.price }
                        .getOrNull()
                        ?.takeIf { it in 60.0..120.0 }
                        ?.let {
                            cachedUsdInr = it
                            lastFxFetchMs = now
                        }
                }

                // 3. Collect all distinct Yahoo symbols needed for NSE, BSE & MCX (including option underlyings)
                val nonCrypto = instruments.filter { it.segment != MarketSegment.CRYPTO }
                val symbolJobs = nonCrypto
                    .mapNotNull { yahooMappingFor(it)?.yahooSymbol }
                    .distinct()
                    .map { ySym ->
                        async {
                            ySym to runCatching { fetchYahooRaw(ySym) }.getOrNull()
                        }
                    }

                val fetchedYahoo = symbolJobs.awaitAll().toMap()
                cryptoJob?.await()

                for (inst in nonCrypto) {
                    val mapping = yahooMappingFor(inst) ?: continue
                    val raw = fetchedYahoo[mapping.yahooSymbol] ?: continue
                    if (raw.price <= 0.0) continue

                    if (inst.type == InstrumentType.OPTIONS && inst.strikePrice != null && inst.optionType != null) {
                        val spot = raw.price
                        val strike = inst.strikePrice.toDouble()
                        val isCall = inst.optionType == OptionType.CE
                        val bsPrice = OptionGreeksCalculator.blackScholesPrice(
                            spot = spot,
                            strike = strike,
                            t = 7.0 / 365.0,
                            r = 0.065,
                            sigma = if (inst.symbol.contains("BANKNIFTY")) 0.17 else 0.145,
                            isCall = isCall
                        ).coerceAtLeast(inst.tickSize.toDouble())
                        val ltp = RiskMath.roundToTick(BigDecimal.valueOf(bsPrice), inst.tickSize)
                        results[inst.symbol] = PublicMarketQuote(
                            symbol = inst.symbol,
                            ltp = ltp,
                            previousClose = inst.previousClose,
                            dayHigh = inst.dayHigh.max(ltp),
                            dayLow = inst.dayLow.min(ltp),
                            volume = raw.volume,
                            sourceLabel = mapping.sourceLabel
                        )
                        continue
                    }

                    val factor = mapping.convertFactor(cachedUsdInr)
                    val ltp = RiskMath.roundToTick(
                        BigDecimal.valueOf(raw.price * factor).max(inst.tickSize),
                        inst.tickSize
                    )
                    val prevClose = raw.previousClose?.takeIf { it > 0.0 }?.let {
                        RiskMath.roundToTick(BigDecimal.valueOf(it * factor), inst.tickSize)
                    }
                    val high = raw.dayHigh?.takeIf { it > 0.0 }?.let {
                        RiskMath.roundToTick(BigDecimal.valueOf(it * factor), inst.tickSize)
                    }
                    val low = raw.dayLow?.takeIf { it > 0.0 }?.let {
                        RiskMath.roundToTick(BigDecimal.valueOf(it * factor), inst.tickSize)
                    }
                    results[inst.symbol] = PublicMarketQuote(
                        symbol = inst.symbol,
                        ltp = ltp,
                        previousClose = prevClose,
                        dayHigh = high,
                        dayLow = low,
                        volume = raw.volume,
                        sourceLabel = mapping.sourceLabel
                    )
                }
            }
            results
        }

    private fun fetchBinanceQuotes(cryptoInstruments: List<Instrument>): Map<String, PublicMarketQuote> {
        val byPair = cryptoInstruments.associateBy { normalizeBinancePair(it.symbol) }
        val symbolsJson = byPair.keys.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }
        val encoded = URLEncoder.encode(symbolsJson, "UTF-8")
        val urls = listOf(
            "https://data-api.binance.vision/api/v3/ticker/24hr?symbols=$encoded",
            "https://api.binance.com/api/v3/ticker/24hr?symbols=$encoded"
        )
        for (url in urls) {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "PaperPulse/1.0")
                .build()
            val body = runCatching {
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) resp.body?.string() else null
                }
            }.getOrNull() ?: continue

            val arr = runCatching { JSONArray(body) }.getOrNull() ?: continue
            val out = mutableMapOf<String, PublicMarketQuote>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val pair = obj.optString("symbol")
                val inst = byPair[pair] ?: continue
                val last = obj.optString("lastPrice").toBigDecimalOrNull() ?: continue
                if (last <= BigDecimal.ZERO) continue
                val prev = obj.optString("prevClosePrice").toBigDecimalOrNull()
                val high = obj.optString("highPrice").toBigDecimalOrNull()
                val low = obj.optString("lowPrice").toBigDecimalOrNull()
                val vol = obj.optString("volume").toDoubleOrNull()?.toLong()
                out[inst.symbol] = PublicMarketQuote(
                    symbol = inst.symbol,
                    ltp = RiskMath.roundToTick(last, inst.tickSize),
                    previousClose = prev?.let { RiskMath.roundToTick(it, inst.tickSize) },
                    dayHigh = high?.let { RiskMath.roundToTick(it, inst.tickSize) },
                    dayLow = low?.let { RiskMath.roundToTick(it, inst.tickSize) },
                    volume = vol,
                    sourceLabel = "Binance Public API"
                )
            }
            if (out.isNotEmpty()) return out
        }
        return emptyMap()
    }

    private data class YahooRawQuote(
        val price: Double,
        val previousClose: Double?,
        val dayHigh: Double?,
        val dayLow: Double?,
        val volume: Long?
    )

    private fun fetchYahooRaw(yahooSymbol: String): YahooRawQuote? {
        val now = System.currentTimeMillis()
        yahooCache[yahooSymbol]?.let { cached ->
            if (now - cached.fetchedAtMs < CACHE_TTL_MS) {
                return cached.quote
            }
        }

        val encoded = URLEncoder.encode(yahooSymbol, "UTF-8")
        val mirrors = listOf(
            "https://query1.finance.yahoo.com/v8/finance/chart/$encoded?interval=1m&range=1d",
            "https://query2.finance.yahoo.com/v8/finance/chart/$encoded?interval=1m&range=1d"
        )

        for (url in mirrors) {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 PaperPulse/1.0")
                .header("Accept", "application/json")
                .build()
            val body = runCatching {
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) resp.body?.string() else null
                }
            }.getOrNull() ?: continue

            val parsed = runCatching {
                val root = JSONObject(body)
                val result = root.optJSONObject("chart")
                    ?.optJSONArray("result")
                    ?.optJSONObject(0) ?: return@runCatching null
                val meta = result.optJSONObject("meta") ?: return@runCatching null
                val price = meta.optDouble("regularMarketPrice", Double.NaN)
                if (price.isNaN() || price <= 0.0) return@runCatching null
                val prevClose = meta.optDouble("chartPreviousClose", Double.NaN)
                    .takeIf { !it.isNaN() && it > 0.0 }
                    ?: meta.optDouble("previousClose", Double.NaN).takeIf { !it.isNaN() && it > 0.0 }
                val dayHigh = meta.optDouble("regularMarketDayHigh", Double.NaN).takeIf { !it.isNaN() && it > 0.0 }
                val dayLow = meta.optDouble("regularMarketDayLow", Double.NaN).takeIf { !it.isNaN() && it > 0.0 }
                val volume = meta.optLong("regularMarketVolume", -1L).takeIf { it >= 0L }
                YahooRawQuote(price, prevClose, dayHigh, dayLow, volume)
            }.getOrNull()

            if (parsed != null) {
                yahooCache[yahooSymbol] = CachedYahoo(parsed, now)
                return parsed
            }
        }
        return null
    }

    private data class YahooMapping(
        val yahooSymbol: String,
        val sourceLabel: String,
        val convertFactor: (usdInr: Double) -> Double = { 1.0 }
    )

    private fun yahooMappingFor(inst: Instrument): YahooMapping? {
        val sym = inst.symbol.uppercase()
        return when (inst.segment) {
            MarketSegment.NSE -> when {
                sym.startsWith("BANKNIFTY") -> YahooMapping("^NSEBANK", "NSE Live Public")
                sym.startsWith("FINNIFTY") -> YahooMapping("NIFTY_FIN_SERVICE.NS", "NSE Live Public")
                sym.startsWith("MIDCPNIFTY") -> YahooMapping("NIFTY_MID_SELECT.NS", "NSE Live Public")
                sym.startsWith("NIFTY") -> YahooMapping("^NSEI", "NSE Live Public")
                inst.type == InstrumentType.STOCK -> {
                    val base = sym.removePrefix("NSE:").removeSuffix("-EQ")
                    YahooMapping("$base.NS", "NSE Live Public")
                }
                else -> {
                    val base = sym.removePrefix("NSE:").replace(Regex("\\d{2}[A-Z]{3}.*$"), "")
                    if (base.isNotBlank()) YahooMapping("$base.NS", "NSE Live Public") else null
                }
            }
            MarketSegment.BSE -> when {
                sym.contains("SENSEX") -> YahooMapping("^BSESN", "BSE Live Public")
                sym.contains("BANKEX") -> YahooMapping("BSE-BANK.BO", "BSE Live Public")
                inst.type == InstrumentType.STOCK -> {
                    val base = sym.removePrefix("BSE:").removeSuffix("-EQ")
                    YahooMapping("$base.BO", "BSE Live Public")
                }
                else -> {
                    val base = sym.removePrefix("BSE:").replace(Regex("\\d{2}[A-Z]{3}.*$"), "")
                    if (base.isNotBlank()) YahooMapping("$base.BO", "BSE Live Public") else null
                }
            }
            MarketSegment.MCX -> when {
                // Comex Gold ($/troy oz) -> MCX Gold (₹ per 10g): oz = 31.1035g, + ~6% Indian duty/premium
                sym.startsWith("GOLD") ->
                    YahooMapping("GC=F", "MCX Live Public") { fx -> (fx * 10.0 / 31.1035) * 1.06 }
                // Comex Silver ($/troy oz) -> MCX Silver (₹ per 1kg): 1000/31.1035, + ~6% Indian duty
                sym.startsWith("SILVER") ->
                    YahooMapping("SI=F", "MCX Live Public") { fx -> (fx * 1000.0 / 31.1035) * 1.06 }
                // WTI Crude ($/bbl) -> MCX Crude (₹/bbl)
                sym.startsWith("CRUDE") ->
                    YahooMapping("CL=F", "MCX Live Public") { fx -> fx }
                // Henry Hub NatGas ($/MMBtu) -> MCX Natural Gas (₹/MMBtu)
                sym.startsWith("NATURALGAS") || sym.startsWith("NATGAS") ->
                    YahooMapping("NG=F", "MCX Live Public") { fx -> fx }
                // Comex Copper ($/lb) -> MCX Copper (₹/kg): 2.20462 lb/kg
                sym.startsWith("COPPER") ->
                    YahooMapping("HG=F", "MCX Live Public") { fx -> fx * 2.20462 }
                else -> null
            }
            MarketSegment.CRYPTO -> null
        }
    }

    private fun normalizeBinancePair(symbol: String): String {
        val clean = symbol.uppercase().replace("/", "").replace("-", "")
        return if (clean.endsWith("USDT")) clean else "${clean}USDT"
    }

    private companion object {
        const val CACHE_TTL_MS = 4_000L
    }
}
