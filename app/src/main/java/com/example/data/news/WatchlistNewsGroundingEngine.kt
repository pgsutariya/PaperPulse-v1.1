package com.example.data.news

import android.content.Context
import android.util.Log
import com.example.data.ai.ensureFirebaseInitialized
import com.example.domain.model.Instrument
import com.example.domain.model.MarketSegment
import com.google.firebase.Firebase
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Tool
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

enum class NewsSentiment(val label: String) {
    BULLISH("Bullish"),
    BEARISH("Bearish"),
    NEUTRAL("Neutral")
}

data class GroundedNewsSource(
    val title: String,
    val url: String,
    val domain: String = ""
)

data class GroundedNewsParseResult(
    val briefing: String?,
    val articles: List<MarketNewsArticle>,
    val searchQueries: List<String> = emptyList(),
    val sources: List<GroundedNewsSource> = emptyList()
)

/**
 * Uses Firebase AI Logic with Google Search Grounding ([Tool.googleSearch]) to pull
 * real-time, verifiable market news filtered specifically to the user's active watchlist symbols.
 */
@Singleton
class WatchlistNewsGroundingEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {
    // Lazily created upon first inference per Firebase AI Logic guidelines.
    private val groundedNewsModel: GenerativeModel by lazy {
        Firebase.ai.generativeModel(
            modelName = DEFAULT_GROUNDED_MODEL,
            generationConfig = generationConfig {
                temperature = 0.2f
            },
            tools = listOf(Tool.googleSearch()),
            systemInstruction = content {
                text(
                    """
                    You are PaperPulse Market News Desk, a real-time financial news analyst with live Google Search access.
                    Your job is to search the web for the latest market-moving news strictly relevant to the user's watchlist symbols (Indian NSE/BSE equities & indices, MCX commodities, and Crypto pairs).
                    Strict rules:
                    - Always ground every headline in live Google Search results from the current market cycle.
                    - Only report real, verifiable news events (earnings, RBI/Fed/SEBI/OPEC updates, analyst calls, price breakouts, regulatory news, macro catalysts). Never fabricate news.
                    - Tag every story with the exact matching watchlist symbol(s) from the provided list.
                    - Output clean JSON only (no extra prose outside JSON).
                    """.trimIndent()
                )
            }
        )
    }

    suspend fun fetchSearchGroundedNews(
        watchlist: List<Instrument>,
        focusSymbol: String? = null
    ): Result<GroundedNewsParseResult> {
        val targetInstruments = selectTargetInstruments(watchlist, focusSymbol)
        if (targetInstruments.isEmpty()) {
            return Result.failure(IllegalStateException("Add at least one symbol to your Watchlist to pull grounded news."))
        }

        if (!ensureFirebaseInitialized(context)) {
            return Result.failure(
                IllegalStateException("Firebase AI Logic is not initialized in this build.")
            )
        }

        val prompt = buildGroundingPrompt(targetInstruments, focusSymbol)
        return try {
            val response = withTimeout(GROUNDING_TIMEOUT_MS) {
                groundedNewsModel.generateContent(prompt)
            }

            val groundingMeta = response.candidates.firstOrNull()?.groundingMetadata
            val webQueries = groundingMeta?.webSearchQueries
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.distinct()
                .orEmpty()

            val webSources = groundingMeta?.groundingChunks
                ?.mapNotNull { chunk ->
                    val web = chunk.web ?: return@mapNotNull null
                    val uri = web.uri?.trim().orEmpty()
                    if (!MarketNewsRepository.isSafeHttpUrl(uri)) return@mapNotNull null
                    val title = web.title?.trim().orEmpty().ifBlank { extractDomain(uri) }
                    GroundedNewsSource(
                        title = title,
                        url = uri,
                        domain = extractDomain(uri)
                    )
                }
                ?.distinctBy { it.url }
                .orEmpty()

            val rawText = response.text?.trim().orEmpty()
            if (rawText.isBlank() && webSources.isEmpty()) {
                return Result.failure(
                    IllegalStateException("Search-grounding tool returned an empty response. Tap Refresh to retry.")
                )
            }

            val parsed = parseGroundedNewsResponse(
                rawText = rawText,
                groundingSources = webSources,
                searchQueries = webQueries,
                watchlist = watchlist
            )

            if (parsed.articles.isEmpty() && parsed.briefing.isNullOrBlank()) {
                Result.failure(
                    IllegalStateException("No verifiable news items could be extracted for your watchlist right now.")
                )
            } else {
                Result.success(parsed)
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Search-grounded news timed out after 60s", e)
            Result.failure(
                IllegalStateException("Search-grounded news timed out after 60 seconds. Please retry.")
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Search-grounded news failed", e)
            val msg = e.localizedMessage?.takeIf { it.isNotBlank() }
                ?: "Search-grounded news failed. Check network connection and retry."
            Result.failure(IllegalStateException(msg, e))
        }
    }

    private fun selectTargetInstruments(
        watchlist: List<Instrument>,
        focusSymbol: String?
    ): List<Instrument> {
        val cleanFocus = focusSymbol?.trim()?.takeIf { it.isNotEmpty() }
        if (cleanFocus != null) {
            val normalizedFocus = normalizeUnderlyingSymbol(cleanFocus)
            val exact = watchlist.filter {
                it.symbol.equals(cleanFocus, ignoreCase = true) ||
                    normalizeUnderlyingSymbol(it.symbol).equals(normalizedFocus, ignoreCase = true)
            }
            if (exact.isNotEmpty()) return exact.take(4)
        }

        val seenRoots = LinkedHashSet<String>()
        val selected = mutableListOf<Instrument>()
        for (inst in watchlist) {
            val root = normalizeUnderlyingSymbol(inst.symbol)
            if (seenRoots.add(root)) {
                selected.add(inst)
                if (selected.size >= MAX_PROMPT_SYMBOLS) break
            }
        }
        return selected
    }

    private fun buildGroundingPrompt(
        targets: List<Instrument>,
        focusSymbol: String?
    ): String {
        val symbolLines = targets.joinToString("\n") { inst ->
            val root = normalizeUnderlyingSymbol(inst.symbol)
            "- ${inst.symbol} (root: $root, name: ${inst.displayName}, market: ${inst.segment.label})"
        }
        val focusNote = if (!focusSymbol.isNullOrBlank()) {
            "Prioritize breaking and recent news specifically for $focusSymbol."
        } else {
            "Cover the most impactful recent news across these watchlist symbols."
        }
        val targetCount = if (!focusSymbol.isNullOrBlank()) "4 to 6" else "6 to 9"

        return """
            Use Google Search to fetch the latest real-time market news for ONLY the symbols in this trader's watchlist:
            $symbolLines

            $focusNote

            Return a single valid JSON object with this exact shape:
            {
              "briefing": "2 concise sentences summarizing key live catalysts across these watchlist symbols.",
              "articles": [
                {
                  "headline": "Clear factual news headline",
                  "summary": "1-2 concise sentences explaining why this matters for price action or sentiment",
                  "source": "Publisher name (e.g. Economic Times, Reuters, Bloomberg, CoinDesk, Mint)",
                  "url": "https://... direct article link if available, otherwise empty string",
                  "symbols": ["EXACT_SYMBOL_FROM_WATCHLIST"],
                  "sentiment": "BULLISH" | "BEARISH" | "NEUTRAL",
                  "publishedHint": "e.g. Today, 2h ago, or date string"
                }
              ]
            }

            Requirements:
            - Provide $targetCount distinct, recent articles strictly relevant to the symbols listed above.
            - Every article's "symbols" array MUST contain one or more exact symbols from the watchlist above.
            - Do not invent fake URLs. If you do not have the exact article URL, set "url" to "".
        """.trimIndent()
    }

    companion object {
        private const val TAG = "WatchlistNewsGrounding"
        const val DEFAULT_GROUNDED_MODEL = "gemini-3.8-flash"
        private const val GROUNDING_TIMEOUT_MS = 60_000L
        private const val MAX_PROMPT_SYMBOLS = 12

        private val knownAliases: Map<String, List<String>> = mapOf(
            "RELIANCE" to listOf("reliance", "reliance industries", "jio", "ril"),
            "TCS" to listOf("tcs", "tata consultancy"),
            "HDFCBANK" to listOf("hdfc bank", "hdfcbank"),
            "INFY" to listOf("infosys", "infy"),
            "ICICIBANK" to listOf("icici bank", "icicibank"),
            "SBIN" to listOf("sbi", "state bank of india", "sbin"),
            "TATAMOTORS" to listOf("tata motors", "tatamotors"),
            "ITC" to listOf("itc limited", "itc stock", "itc"),
            "LT" to listOf("larsen & toubro", "larsen and toubro", "l&t"),
            "BHARTIARTL" to listOf("bharti airtel", "airtel", "bhartiartl"),
            "NIFTY" to listOf("nifty", "nifty 50", "nifty50", "nse nifty"),
            "BANKNIFTY" to listOf("bank nifty", "banknifty", "nifty bank"),
            "FINNIFTY" to listOf("finnifty", "fin nifty"),
            "SENSEX" to listOf("sensex", "bse sensex"),
            "GOLD" to listOf("gold", "mcx gold", "bullion", "xau"),
            "SILVER" to listOf("silver", "mcx silver", "xag"),
            "CRUDEOIL" to listOf("crude oil", "crudeoil", "brent", "wti", "opec"),
            "NATURALGAS" to listOf("natural gas", "naturalgas", "natgas"),
            "COPPER" to listOf("copper", "mcx copper"),
            "BTCUSDT" to listOf("bitcoin", "btc", "btcusdt"),
            "ETHUSDT" to listOf("ethereum", "ether", "eth", "ethusdt"),
            "SOLUSDT" to listOf("solana", "sol", "solusdt"),
            "BNBUSDT" to listOf("bnb", "binance coin", "bnbusdt"),
            "XRPUSDT" to listOf("xrp", "ripple", "xrpusdt")
        )

        private val optionOrFutureSuffixRegex = Regex(
            "^([A-Z&]+?)(?:\\d{2}[A-Z]{3}\\d{0,2}.*|FUT|PERP|MINI|MICRO|-EQ|-BL)$"
        )

        /**
         * Normalizes derivative / exchange tickers to their clean underlying symbol root
         * (e.g. `NIFTY28NOV24500CE` -> `NIFTY`, `RELIANCE-EQ` -> `RELIANCE`, `GOLDM` -> `GOLD`).
         */
        fun normalizeUnderlyingSymbol(symbol: String): String {
            val upper = symbol.trim().uppercase(Locale.US)
            if (upper.isEmpty()) return ""
            if (upper.endsWith("USDT")) return upper
            if (upper == "GOLDM" || upper == "GOLDMINI") return "GOLD"
            if (upper == "SILVERM" || upper == "SILVERMINI") return "SILVER"
            if (upper == "NATGASMINI") return "NATURALGAS"
            if (upper == "CRUDEOILM") return "CRUDEOIL"
            val match = optionOrFutureSuffixRegex.matchEntire(upper)
            return match?.groupValues?.getOrNull(1)?.takeIf { it.length >= 2 } ?: upper
        }

        /**
         * Returns unique, display-friendly watchlist symbol keys preserving watchlist order.
         */
        fun extractWatchlistSymbols(watchlist: List<Instrument>): List<String> {
            val seen = LinkedHashSet<String>()
            val result = mutableListOf<String>()
            watchlist.forEach { inst ->
                val canonical = normalizeUnderlyingSymbol(inst.symbol)
                if (canonical.isNotEmpty() && seen.add(canonical)) {
                    result.add(canonical)
                }
            }
            return result
        }

        /**
         * Dynamically matches news text against every instrument currently in the user's watchlist
         * (using symbol, normalized underlying root, known market aliases, and displayName keywords).
         */
        fun matchWatchlistSymbols(
            text: String,
            watchlist: List<Instrument>
        ): List<String> {
            if (text.isBlank()) return emptyList()
            val lower = text.lowercase(Locale.US)
            val matched = LinkedHashSet<String>()

            val rootToPreferredSymbol = LinkedHashMap<String, String>()
            val rootKeywords = LinkedHashMap<String, MutableSet<String>>()

            for (inst in watchlist) {
                val root = normalizeUnderlyingSymbol(inst.symbol)
                if (root.isEmpty()) continue
                rootToPreferredSymbol.putIfAbsent(root, root)
                val kwSet = rootKeywords.getOrPut(root) { LinkedHashSet() }
                kwSet.add(inst.symbol.lowercase(Locale.US))
                kwSet.add(root.lowercase(Locale.US))
                if (root.endsWith("USDT") && root.length > 4) {
                    kwSet.add(root.removeSuffix("USDT").lowercase(Locale.US))
                }
                knownAliases[root]?.let { kwSet.addAll(it) }

                val cleanDisplay = inst.displayName
                    .replace(Regex("\\b(Ltd|Limited|Industries|Corp|Corporation|Futures|Options|Spot|Index|NSE|BSE|MCX|Crypto|Perp|CE|PE)\\b", RegexOption.IGNORE_CASE), " ")
                    .replace(Regex("[^a-zA-Z0-9\\s&]"), " ")
                    .replace(Regex("\\s+"), " ")
                    .trim()
                    .lowercase(Locale.US)
                if (cleanDisplay.length >= 4) {
                    kwSet.add(cleanDisplay)
                }
            }

            for ((root, keywords) in rootKeywords) {
                val hit = keywords.any { kw ->
                    if (kw.length < 2) false
                    else Regex("\\b${Regex.escape(kw)}\\b").containsMatchIn(lower)
                }
                if (hit) {
                    matched.add(rootToPreferredSymbol[root] ?: root)
                }
            }

            return matched.take(4).toList()
        }

        /**
         * Filters articles by the user's watchlist symbols and optional focused symbol chip.
         */
        fun filterArticlesForWatchlist(
            articles: List<MarketNewsArticle>,
            watchlist: List<Instrument>,
            selectedSymbol: String? = null,
            watchlistOnly: Boolean = true
        ): List<MarketNewsArticle> {
            val watchlistRoots = extractWatchlistSymbols(watchlist).map { it.uppercase(Locale.US) }.toSet()
            val focusRoot = selectedSymbol?.trim()?.takeIf { it.isNotEmpty() }?.let { normalizeUnderlyingSymbol(it) }

            return articles.filter { art ->
                val artRoots = art.relatedSymbols
                    .map { normalizeUnderlyingSymbol(it) }
                    .toSet()

                val matchesFocus = focusRoot == null ||
                    artRoots.contains(focusRoot) ||
                    art.title.contains(focusRoot, ignoreCase = true) ||
                    art.summary.contains(focusRoot, ignoreCase = true)

                val matchesWatchlist = !watchlistOnly ||
                    watchlistRoots.isEmpty() ||
                    artRoots.any { it in watchlistRoots } ||
                    (art.isSearchGrounded && art.relatedSymbols.isNotEmpty())

                matchesFocus && matchesWatchlist
            }
        }

        fun inferCategoryForSymbol(
            symbol: String?,
            watchlist: List<Instrument>
        ): MarketNewsCategory {
            if (symbol.isNullOrBlank()) return MarketNewsCategory.INDIA_MARKETS
            val root = normalizeUnderlyingSymbol(symbol)
            val matchedInst = watchlist.firstOrNull {
                it.symbol.equals(symbol, ignoreCase = true) ||
                    normalizeUnderlyingSymbol(it.symbol).equals(root, ignoreCase = true)
            }
            return when (matchedInst?.segment) {
                MarketSegment.CRYPTO -> MarketNewsCategory.CRYPTO
                MarketSegment.MCX -> MarketNewsCategory.COMMODITIES
                MarketSegment.NSE, MarketSegment.BSE -> MarketNewsCategory.INDIA_MARKETS
                null -> when {
                    root.endsWith("USDT") -> MarketNewsCategory.CRYPTO
                    root in setOf("GOLD", "SILVER", "CRUDEOIL", "NATURALGAS", "COPPER") -> MarketNewsCategory.COMMODITIES
                    else -> MarketNewsCategory.INDIA_MARKETS
                }
            }
        }

        fun parseGroundedNewsResponse(
            rawText: String,
            groundingSources: List<GroundedNewsSource>,
            searchQueries: List<String> = emptyList(),
            watchlist: List<Instrument>,
            nowMillis: Long = System.currentTimeMillis()
        ): GroundedNewsParseResult {
            val watchlistMap = watchlist.associateBy { normalizeUnderlyingSymbol(it.symbol) }
            val jsonCandidate = extractJsonObjectString(rawText)

            if (jsonCandidate != null) {
                val parsedObj = runCatching {
                    Json.parseToJsonElement(jsonCandidate).jsonObject
                }.getOrNull()
                if (parsedObj != null) {
                    val briefing = parsedObj.optStringValue("briefing").takeIf { it.isNotEmpty() }
                    val arr = (parsedObj["articles"] as? JsonArray) ?: JsonArray(emptyList())
                    val articles = mutableListOf<MarketNewsArticle>()

                    for (i in arr.indices) {
                        val item = arr[i] as? JsonObject ?: continue
                        val headline = item.optStringValue("headline").ifBlank { item.optStringValue("title") }
                        if (headline.isBlank()) continue
                        val summary = item.optStringValue("summary").take(260)
                        val sourceName = item.optStringValue("source").ifBlank {
                            groundingSources.getOrNull(i)?.title?.take(36) ?: "Google Search Grounded"
                        }
                        val rawUrl = item.optStringValue("url")
                        val safeUrl = when {
                            MarketNewsRepository.isSafeHttpUrl(rawUrl) -> rawUrl
                            else -> findBestSourceUrl(headline, sourceName, groundingSources, i)
                        }
                        val publishedHint = item.optStringValue("publishedHint").takeIf { it.isNotEmpty() }
                        val sentiment = parseSentiment(item.optStringValue("sentiment"))

                        val explicitSymbols = mutableListOf<String>()
                        val symArr = item["symbols"] as? JsonArray
                        if (symArr != null) {
                            for (el in symArr) {
                                val sym = runCatching { el.jsonPrimitive.contentOrNull }.getOrNull()?.trim().orEmpty()
                                if (sym.isNotEmpty()) explicitSymbols.add(normalizeUnderlyingSymbol(sym))
                            }
                        }
                        item.optStringValue("symbol").takeIf { it.isNotEmpty() }?.let {
                            explicitSymbols.add(normalizeUnderlyingSymbol(it))
                        }

                        val dynamicMatched = matchWatchlistSymbols("$headline $summary", watchlist)
                        val combinedSymbols = LinkedHashSet<String>().apply {
                            explicitSymbols.forEach { sym ->
                                if (watchlistMap.isEmpty() || watchlistMap.containsKey(sym)) {
                                    add(sym)
                                }
                            }
                            addAll(dynamicMatched)
                            if (isEmpty()) addAll(explicitSymbols)
                        }.take(4).toList()

                        val category = inferCategoryForSymbol(combinedSymbols.firstOrNull(), watchlist)

                        articles.add(
                            MarketNewsArticle(
                                id = "grounded_${headline.hashCode()}_${sourceName.hashCode()}",
                                title = headline,
                                summary = summary,
                                source = sourceName,
                                url = safeUrl,
                                category = category,
                                publishedAtMillis = nowMillis - (i * 60_000L),
                                relatedSymbols = combinedSymbols,
                                sentiment = sentiment,
                                isSearchGrounded = true,
                                publishedLabel = publishedHint
                            )
                        )
                    }

                    if (articles.isNotEmpty() || !briefing.isNullOrBlank()) {
                        return GroundedNewsParseResult(
                            briefing = briefing,
                            articles = articles,
                            searchQueries = searchQueries,
                            sources = groundingSources
                        )
                    }
                }
            }

            // Fallback when model returns grounded prose alongside grounding web chunks
            val fallbackArticles = groundingSources.mapIndexed { idx, src ->
                val matched = matchWatchlistSymbols(src.title, watchlist)
                MarketNewsArticle(
                    id = "grounded_src_${src.url.hashCode()}",
                    title = src.title,
                    summary = "",
                    source = src.domain.ifBlank { "Web Source" },
                    url = src.url,
                    category = inferCategoryForSymbol(matched.firstOrNull(), watchlist),
                    publishedAtMillis = nowMillis - (idx * 60_000L),
                    relatedSymbols = matched,
                    sentiment = NewsSentiment.NEUTRAL,
                    isSearchGrounded = true,
                    publishedLabel = "Live Search"
                )
            }

            return GroundedNewsParseResult(
                briefing = rawText.replace(Regex("```[a-zA-Z]*"), "").replace("```", "").trim().take(360).takeIf { it.isNotEmpty() },
                articles = fallbackArticles,
                searchQueries = searchQueries,
                sources = groundingSources
            )
        }

        private fun JsonObject.optStringValue(key: String): String {
            return runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()?.trim().orEmpty()
        }

        private fun parseSentiment(raw: String?): NewsSentiment {
            return when (raw?.trim()?.uppercase(Locale.US)) {
                "BULLISH", "POSITIVE" -> NewsSentiment.BULLISH
                "BEARISH", "NEGATIVE" -> NewsSentiment.BEARISH
                else -> NewsSentiment.NEUTRAL
            }
        }

        private fun findBestSourceUrl(
            headline: String,
            sourceName: String,
            sources: List<GroundedNewsSource>,
            index: Int
        ): String {
            if (sources.isEmpty()) return ""
            val lowerSource = sourceName.lowercase(Locale.US)
            val sourceMatch = sources.firstOrNull { src ->
                src.title.lowercase(Locale.US).contains(lowerSource) ||
                    src.domain.lowercase(Locale.US).contains(lowerSource.replace(" ", ""))
            }
            if (sourceMatch != null) return sourceMatch.url

            val headlineTokens = headline.lowercase(Locale.US)
                .split(Regex("[^a-z0-9]+"))
                .filter { it.length >= 4 }
                .toSet()
            val tokenMatch = sources.maxByOrNull { src ->
                val titleLower = src.title.lowercase(Locale.US)
                headlineTokens.count { token -> titleLower.contains(token) }
            }
            if (tokenMatch != null) {
                val overlap = headlineTokens.count { tokenMatch.title.lowercase(Locale.US).contains(it) }
                if (overlap >= 2) return tokenMatch.url
            }

            return sources.getOrNull(index % sources.size)?.url.orEmpty()
        }

        private fun extractJsonObjectString(raw: String): String? {
            val cleaned = raw
                .replace(Regex("^\\s*```(?:json)?\\s*", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\s*```\\s*$"), "")
                .trim()
            val start = cleaned.indexOf('{')
            val end = cleaned.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            return cleaned.substring(start, end + 1)
        }

        fun extractDomain(url: String): String {
            return runCatching {
                URI(url).host?.removePrefix("www.").orEmpty()
            }.getOrDefault("")
        }
    }
}
