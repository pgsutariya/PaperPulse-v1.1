package com.example.data.history

import com.example.data.feed.live.LiveFeedFactory
import com.example.domain.analysis.CandleResampler
import com.example.domain.model.Instrument
import com.example.domain.model.InstrumentType
import com.example.domain.model.MarketSegment
import com.example.domain.signal.Candle
import com.example.domain.signal.Timeframe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Real exchange history so indicators and signals are computed on market data rather than the
 * synthetic warm-up bars:
 *  - CRYPTO: Binance public klines (no key needed), including taker-buy volume → true delta.
 *  - NSE / BSE / MCX: Angel One SmartAPI getCandleData (needs Angel One credentials in Feed Settings).
 * Timeframes the exchange does not serve directly (10m/45m/4H/1W/1Mo) are resampled from a finer series.
 */
@Singleton
class HistoricalCandleRepository @Inject constructor(
    okHttpClient: OkHttpClient,
    private val feedFactory: LiveFeedFactory
) {
    private val http: OkHttpClient = okHttpClient.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .pingInterval(0, TimeUnit.MILLISECONDS)
        .build()

    suspend fun fetch(instrument: Instrument, tf: Timeframe, nowMillis: Long = System.currentTimeMillis()): Result<List<Candle>> {
        return when (instrument.segment) {
            MarketSegment.CRYPTO -> fetchBinance(instrument, tf)
            MarketSegment.NSE, MarketSegment.BSE, MarketSegment.MCX -> fetchAngel(instrument, tf, nowMillis)
        }
    }

    // ------------------------------------------------------------------ Binance

    private fun binanceSymbol(instrument: Instrument): String? {
        if (instrument.type == InstrumentType.OPTIONS) return null
        val raw = instrument.symbol.uppercase().removeSuffix("-PERP")
        return raw.takeIf { BINANCE_SYMBOL.matches(it) }
    }

    private suspend fun fetchBinance(instrument: Instrument, tf: Timeframe): Result<List<Candle>> {
        val symbol = binanceSymbol(instrument)
            ?: return Result.failure(IllegalArgumentException("${instrument.symbol} has no Binance spot history"))
        val (interval, resampleTo) = when (tf) {
            Timeframe.M1 -> "1m" to null
            Timeframe.M3 -> "3m" to null
            Timeframe.M5 -> "5m" to null
            Timeframe.M10 -> "5m" to Timeframe.M10
            Timeframe.M15 -> "15m" to null
            Timeframe.M30 -> "30m" to null
            Timeframe.M45 -> "15m" to Timeframe.M45
            Timeframe.H1 -> "1h" to null
            Timeframe.H4 -> "4h" to null
            Timeframe.D1 -> "1d" to null
            Timeframe.W1 -> "1w" to null
            Timeframe.MN1 -> "1M" to null
        }
        val limit = if (resampleTo != null) 1000 else 600
        return withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("$BINANCE_BASE/api/v3/klines?symbol=$symbol&interval=$interval&limit=$limit")
                    .get()
                    .build()
                val base = http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) error("Binance klines HTTP ${resp.code}")
                    parseBinanceKlines(resp.body?.string().orEmpty())
                }
                if (resampleTo != null) CandleResampler.resample(base, resampleTo, MarketSegment.CRYPTO) else base
            }
        }
    }

    // ------------------------------------------------------------------ Angel One

    private suspend fun fetchAngel(instrument: Instrument, tf: Timeframe, nowMillis: Long): Result<List<Candle>> {
        val angel = feedFactory.getAngelOneFeed()
            ?: return Result.failure(IllegalStateException("Angel One feed unavailable"))
        val (interval, days, resampleTo) = when (tf) {
            Timeframe.M1 -> Triple("ONE_MINUTE", 7L, null)
            Timeframe.M3 -> Triple("THREE_MINUTE", 15L, null)
            Timeframe.M5 -> Triple("FIVE_MINUTE", 30L, null)
            Timeframe.M10 -> Triple("TEN_MINUTE", 45L, null)
            Timeframe.M15 -> Triple("FIFTEEN_MINUTE", 60L, null)
            Timeframe.M30 -> Triple("THIRTY_MINUTE", 90L, null)
            Timeframe.M45 -> Triple("FIFTEEN_MINUTE", 90L, Timeframe.M45)
            Timeframe.H1 -> Triple("ONE_HOUR", 180L, null)
            Timeframe.H4 -> Triple("ONE_HOUR", 365L, Timeframe.H4)
            Timeframe.D1 -> Triple("ONE_DAY", 730L, null)
            Timeframe.W1 -> Triple("ONE_DAY", 1900L, Timeframe.W1)
            Timeframe.MN1 -> Triple("ONE_DAY", 1900L, Timeframe.MN1)
        }
        val from = nowMillis - TimeUnit.DAYS.toMillis(days)
        return angel.fetchHistoricalCandles(instrument.symbol, interval, from, nowMillis).map { base ->
            if (resampleTo != null) CandleResampler.resample(base, resampleTo, instrument.segment) else base
        }
    }

    companion object {
        private const val BINANCE_BASE = "https://data-api.binance.vision"
        private val BINANCE_SYMBOL = Regex("^[A-Z0-9]{2,15}(USDT|USDC|FDUSD|BTC|ETH)$")

        /**
         * Kline row: [openTime, open, high, low, close, volume, closeTime, quoteVolume, trades,
         * takerBuyBaseVolume, takerBuyQuoteVolume, ignore]. Delta = takerBuy − (volume − takerBuy).
         */
        internal fun parseBinanceKlines(body: String): List<Candle> {
            val rows = JSONArray(body)
            val out = ArrayList<Candle>(rows.length())
            for (i in 0 until rows.length()) {
                val r = rows.optJSONArray(i) ?: continue
                if (r.length() < 6) continue
                val openTime = r.optLong(0, -1L)
                val o = r.optString(1).toDoubleOrNull() ?: continue
                val h = r.optString(2).toDoubleOrNull() ?: continue
                val l = r.optString(3).toDoubleOrNull() ?: continue
                val c = r.optString(4).toDoubleOrNull() ?: continue
                val v = r.optString(5).toDoubleOrNull() ?: 0.0
                val takerBuy = if (r.length() > 9) r.optString(9).toDoubleOrNull() else null
                if (openTime <= 0L || c <= 0.0) continue
                out.add(
                    Candle(
                        timestamp = Instant.ofEpochMilli(openTime),
                        open = o,
                        high = h,
                        low = l,
                        close = c,
                        volume = v,
                        delta = takerBuy?.let { 2.0 * it - v }
                    )
                )
            }
            return out
        }
    }
}
