package com.example.data.feed

import com.example.domain.engine.RiskMath
import com.example.domain.model.Instrument
import com.example.domain.model.MarketSegment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URLEncoder
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
 * Fetches real public market quotes without requiring paid broker API keys:
 * - NSE & BSE equities/indices via Yahoo Finance public v8 chart endpoint (.NS / .BO / ^NSEI / ^BSESN)
 * - MCX commodities via Yahoo Finance global futures proxies (GC=F, SI=F, CL=F, NG=F) converted using live USD/INR (INR=X)
 * - Crypto via Binance public REST endpoint (data-api.binance.vision/api/v3/ticker/24hr)
 */
@Singleton
class PublicMarketQuotePoller @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    @Volatile
    private var cachedUsdInr: Double = 84.5

    suspend fun fetchQuotes(instruments: List<Instrument>): Map<String, PublicMarketQuote> =
        withContext(Dispatchers.IO) {
            if (instruments.isEmpty()) return@withContext emptyMap()
            val results = mutableMapOf<String, PublicMarketQuote>()

            // 1. Binance public REST batch for Crypto instruments
            val cryptoInstruments = instruments.filter { it.segment == MarketSegment.CRYPTO }
            if (cryptoInstruments.isNotEmpty()) {
                runCatching { fetchBinanceQuotes(cryptoInstruments) }.getOrNull()?.let {
                    results.putAll(it)
                }
            }

            // 2. Refresh USD/INR rate once if MCX instruments exist
            val hasMcx = instruments.any { it.segment == MarketSegment.MCX }
            if (hasMcx) {
                runCatching { fetchYahooRaw("INR=X")?.price }.getOrNull()?.takeIf { it in 60.0..120.0 }?.let {
                    cachedUsdInr = it
                }
            }

            // 3. Yahoo Finance public chart endpoint for NSE, BSE & MCX proxies
            val nonCrypto = instruments.filter { it.segment != MarketSegment.CRYPTO }
            for (inst in nonCrypto) {
                val mapping = yahooMappingFor(inst) ?: continue
                val raw = runCatching { fetchYahooRaw(mapping.yahooSymbol) }.getOrNull() ?: continue
                if (raw.price <= 0.0) continue

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
                okHttpClient.newCall(req).execute().use { resp ->
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
        val encoded = URLEncoder.encode(yahooSymbol, "UTF-8")
        val url = "https://query1.finance.yahoo.com/v8/finance/chart/$encoded?interval=1m&range=1d"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) PaperPulse/1.0")
            .build()
        val body = okHttpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            resp.body?.string()
        } ?: return null

        val root = JSONObject(body)
        val result = root.optJSONObject("chart")
            ?.optJSONArray("result")
            ?.optJSONObject(0) ?: return null
        val meta = result.optJSONObject("meta") ?: return null
        val price = meta.optDouble("regularMarketPrice", Double.NaN)
        if (price.isNaN() || price <= 0.0) return null
        val prevClose = meta.optDouble("chartPreviousClose", Double.NaN)
            .takeIf { !it.isNaN() && it > 0.0 }
            ?: meta.optDouble("previousClose", Double.NaN).takeIf { !it.isNaN() && it > 0.0 }
        val dayHigh = meta.optDouble("regularMarketDayHigh", Double.NaN).takeIf { !it.isNaN() && it > 0.0 }
        val dayLow = meta.optDouble("regularMarketDayLow", Double.NaN).takeIf { !it.isNaN() && it > 0.0 }
        val volume = meta.optLong("regularMarketVolume", -1L).takeIf { it >= 0L }
        return YahooRawQuote(price, prevClose, dayHigh, dayLow, volume)
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
                sym == "NIFTY" || sym.startsWith("NIFTY2") && inst.type != com.example.domain.model.InstrumentType.OPTIONS ->
                    YahooMapping("^NSEI", "NSE Public Feed")
                sym == "BANKNIFTY" || sym.startsWith("BANKNIFTY2") && inst.type != com.example.domain.model.InstrumentType.OPTIONS ->
                    YahooMapping("^NSEBANK", "NSE Public Feed")
                inst.type == com.example.domain.model.InstrumentType.STOCK -> {
                    val base = sym.removePrefix("NSE:").removeSuffix("-EQ")
                    YahooMapping("$base.NS", "NSE Public Feed")
                }
                else -> null
            }
            MarketSegment.BSE -> when {
                sym.contains("SENSEX") && inst.type != com.example.domain.model.InstrumentType.OPTIONS ->
                    YahooMapping("^BSESN", "BSE Public Feed")
                inst.type == com.example.domain.model.InstrumentType.STOCK -> {
                    val base = sym.removePrefix("BSE:").removeSuffix("-EQ")
                    YahooMapping("$base.BO", "BSE Public Feed")
                }
                else -> null
            }
            MarketSegment.MCX -> when {
                // Comex Gold ($/troy oz) -> MCX Gold (₹ per 10g): oz = 31.1035g, + ~6% Indian duty/premium
                sym.startsWith("GOLD") ->
                    YahooMapping("GC=F", "MCX Public Proxy") { fx -> (fx * 10.0 / 31.1035) * 1.06 }
                // Comex Silver ($/troy oz) -> MCX Silver (₹ per 1kg): 1000/31.1035, + ~6% Indian duty
                sym.startsWith("SILVER") ->
                    YahooMapping("SI=F", "MCX Public Proxy") { fx -> (fx * 1000.0 / 31.1035) * 1.06 }
                // WTI Crude ($/bbl) -> MCX Crude (₹/bbl)
                sym.startsWith("CRUDE") ->
                    YahooMapping("CL=F", "MCX Public Proxy") { fx -> fx }
                // Henry Hub NatGas ($/MMBtu) -> MCX Natural Gas (₹/MMBtu)
                sym.startsWith("NATURALGAS") || sym.startsWith("NATGAS") ->
                    YahooMapping("NG=F", "MCX Public Proxy") { fx -> fx }
                else -> null
            }
            MarketSegment.CRYPTO -> null
        }
    }

    private fun normalizeBinancePair(symbol: String): String {
        val clean = symbol.uppercase().replace("/", "").replace("-", "")
        return if (clean.endsWith("USDT")) clean else "${clean}USDT"
    }
}
