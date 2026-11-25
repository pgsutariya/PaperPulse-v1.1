package com.example.data.news

import com.example.domain.model.Instrument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

enum class MarketNewsCategory(val label: String) {
    ALL("All"),
    INDIA_MARKETS("India / NSE"),
    COMMODITIES("MCX / Gold"),
    CRYPTO("Crypto")
}

data class MarketNewsArticle(
    val id: String,
    val title: String,
    val summary: String,
    val source: String,
    val url: String,
    val category: MarketNewsCategory,
    val publishedAtMillis: Long,
    val relatedSymbols: List<String> = emptyList(),
    val sentiment: NewsSentiment = NewsSentiment.NEUTRAL,
    val isSearchGrounded: Boolean = false,
    val publishedLabel: String? = null
)

data class MarketNewsFeedState(
    val articles: List<MarketNewsArticle> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val lastUpdatedMillis: Long = 0L,
    val isGroundingLoading: Boolean = false,
    val groundingError: String? = null,
    val groundingBriefing: String? = null,
    val groundingSearchQueries: List<String> = emptyList(),
    val groundingSources: List<GroundedNewsSource> = emptyList(),
    val groundedSymbols: List<String> = emptyList(),
    val lastGroundedAtMillis: Long = 0L
)

private data class NewsFeedEndpoint(
    val url: String,
    val defaultSource: String,
    val category: MarketNewsCategory
)

/**
 * Fetches live financial and market news headlines combining:
 * 1) Firebase AI Logic Google Search Grounding targeted strictly at the user's watchlist symbols
 * 2) Public financial RSS feeds across NSE/BSE, MCX Commodities, and Crypto enriched with dynamic watchlist symbol matching.
 */
@Singleton
class MarketNewsRepository @Inject constructor(
    baseOkHttpClient: OkHttpClient,
    private val groundingEngine: WatchlistNewsGroundingEngine
) {
    private val httpClient: OkHttpClient = baseOkHttpClient.newBuilder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(18, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val _state = MutableStateFlow(MarketNewsFeedState())
    val state: StateFlow<MarketNewsFeedState> = _state.asStateFlow()

    private val refreshMutex = Mutex()
    private val groundingMutex = Mutex()
    @Volatile
    private var lastGroundingSignature: String = ""
    @Volatile
    private var latestRssArticles: List<MarketNewsArticle> = emptyList()
    @Volatile
    private var latestGroundedArticles: List<MarketNewsArticle> = emptyList()
    @Volatile
    private var latestWatchlist: List<Instrument> = emptyList()

    private val endpoints = listOf(
        NewsFeedEndpoint(
            url = "https://economictimes.indiatimes.com/markets/rssfeeds/1977021501.cms",
            defaultSource = "Economic Times",
            category = MarketNewsCategory.INDIA_MARKETS
        ),
        NewsFeedEndpoint(
            url = "https://news.google.com/rss/search?q=NSE+NIFTY+SENSEX+India+stock+market&hl=en-IN&gl=IN&ceid=IN:en",
            defaultSource = "Google News India",
            category = MarketNewsCategory.INDIA_MARKETS
        ),
        NewsFeedEndpoint(
            url = "https://news.google.com/rss/search?q=MCX+gold+silver+crude+oil+commodity+India&hl=en-IN&gl=IN&ceid=IN:en",
            defaultSource = "Commodities Desk",
            category = MarketNewsCategory.COMMODITIES
        ),
        NewsFeedEndpoint(
            url = "https://www.coindesk.com/arc/outboundfeeds/rss/",
            defaultSource = "CoinDesk",
            category = MarketNewsCategory.CRYPTO
        ),
        NewsFeedEndpoint(
            url = "https://news.google.com/rss/search?q=Bitcoin+Ethereum+crypto+market&hl=en-US&gl=US&ceid=US:en",
            defaultSource = "Crypto Wire",
            category = MarketNewsCategory.CRYPTO
        )
    )

    suspend fun refresh(force: Boolean = false) {
        val current = _state.value
        val now = System.currentTimeMillis()
        if (!force && latestRssArticles.isNotEmpty() && (now - current.lastUpdatedMillis) < CACHE_TTL_MS) {
            return
        }

        refreshMutex.withLock {
            val latest = _state.value
            val checkNow = System.currentTimeMillis()
            if (!force && latestRssArticles.isNotEmpty() && (checkNow - latest.lastUpdatedMillis) < CACHE_TTL_MS) {
                return
            }

            _state.value = latest.copy(isLoading = true, errorMessage = null)

            val fetched = withContext(Dispatchers.IO) {
                coroutineScope {
                    endpoints.map { ep ->
                        async { fetchSingleEndpoint(ep) }
                    }.awaitAll().flatten()
                }
            }

            if (fetched.isEmpty()) {
                _state.value = _state.value.copy(
                    isLoading = false,
                    errorMessage = if (_state.value.articles.isEmpty()) {
                        "Unable to reach live RSS market feeds. Tap Search-Ground or Refresh to retry."
                    } else {
                        null
                    }
                )
            } else {
                latestRssArticles = enrichWithWatchlistSymbols(fetched, latestWatchlist)
                val merged = mergeGroundedAndRss(latestGroundedArticles, latestRssArticles)
                _state.value = _state.value.copy(
                    articles = merged,
                    isLoading = false,
                    errorMessage = null,
                    lastUpdatedMillis = System.currentTimeMillis()
                )
            }
        }
    }

    /**
     * Pulls search-grounded market news via Firebase AI Logic ([com.google.firebase.ai.type.Tool.googleSearch])
     * specifically for the symbols currently in the user's watchlist (or a single focused watchlist symbol),
     * while refreshing and enriching live RSS feeds in parallel.
     */
    suspend fun refreshForWatchlist(
        instruments: List<Instrument>,
        focusSymbol: String? = null,
        force: Boolean = false
    ) {
        if (instruments.isNotEmpty()) {
            latestWatchlist = instruments
            // Re-enrich existing RSS articles immediately when watchlist changes
            if (latestRssArticles.isNotEmpty()) {
                latestRssArticles = enrichWithWatchlistSymbols(latestRssArticles, instruments)
                val merged = mergeGroundedAndRss(latestGroundedArticles, latestRssArticles)
                _state.value = _state.value.copy(articles = merged)
            }
        }

        val watchlistSymbols = WatchlistNewsGroundingEngine.extractWatchlistSymbols(instruments)
        if (watchlistSymbols.isEmpty()) return

        val focusKey = focusSymbol?.trim()?.uppercase(Locale.US).orEmpty()
        val signature = "${watchlistSymbols.take(12).joinToString(",")}|$focusKey"
        val now = System.currentTimeMillis()
        val cacheValid = !force &&
            signature == lastGroundingSignature &&
            latestGroundedArticles.isNotEmpty() &&
            (now - _state.value.lastGroundedAtMillis) < GROUNDING_CACHE_TTL_MS

        coroutineScope {
            val rssJob = async { refresh(force = force) }
            if (!cacheValid) {
                groundingMutex.withLock {
                    val recheckNow = System.currentTimeMillis()
                    val stillValid = !force &&
                        signature == lastGroundingSignature &&
                        latestGroundedArticles.isNotEmpty() &&
                        (recheckNow - _state.value.lastGroundedAtMillis) < GROUNDING_CACHE_TTL_MS
                    if (!stillValid) {
                        _state.value = _state.value.copy(
                            isGroundingLoading = true,
                            groundingError = null
                        )
                        val result = groundingEngine.fetchSearchGroundedNews(
                            watchlist = instruments,
                            focusSymbol = focusSymbol
                        )
                        result.onSuccess { parsed ->
                            val enrichedGrounded = enrichWithWatchlistSymbols(parsed.articles, instruments)
                            latestGroundedArticles = if (focusSymbol.isNullOrBlank()) {
                                enrichedGrounded
                            } else {
                                // Merge focused symbol stories on top of existing grounded stories
                                deduplicateAndSort(enrichedGrounded + latestGroundedArticles)
                            }
                            lastGroundingSignature = signature
                            val merged = mergeGroundedAndRss(latestGroundedArticles, latestRssArticles)
                            _state.value = _state.value.copy(
                                articles = merged,
                                isGroundingLoading = false,
                                groundingError = null,
                                groundingBriefing = parsed.briefing,
                                groundingSearchQueries = parsed.searchQueries,
                                groundingSources = parsed.sources,
                                groundedSymbols = if (!focusSymbol.isNullOrBlank()) {
                                    listOf(WatchlistNewsGroundingEngine.normalizeUnderlyingSymbol(focusSymbol))
                                } else {
                                    watchlistSymbols.take(12)
                                },
                                lastGroundedAtMillis = System.currentTimeMillis(),
                                lastUpdatedMillis = System.currentTimeMillis()
                            )
                        }.onFailure { err ->
                            _state.value = _state.value.copy(
                                isGroundingLoading = false,
                                groundingError = err.message ?: "Search-grounded news unavailable."
                            )
                        }
                    }
                }
            }
            rssJob.await()
        }
    }

    private fun enrichWithWatchlistSymbols(
        articles: List<MarketNewsArticle>,
        watchlist: List<Instrument>
    ): List<MarketNewsArticle> {
        if (watchlist.isEmpty()) return articles
        return articles.map { art ->
            val dynamicMatches = WatchlistNewsGroundingEngine.matchWatchlistSymbols(
                text = "${art.title} ${art.summary}",
                watchlist = watchlist
            )
            if (dynamicMatches.isEmpty()) {
                art
            } else {
                val combined = LinkedHashSet<String>().apply {
                    addAll(art.relatedSymbols)
                    addAll(dynamicMatches)
                }.take(4).toList()
                art.copy(relatedSymbols = combined)
            }
        }
    }

    private fun mergeGroundedAndRss(
        grounded: List<MarketNewsArticle>,
        rss: List<MarketNewsArticle>
    ): List<MarketNewsArticle> {
        val seen = HashSet<String>()
        val result = mutableListOf<MarketNewsArticle>()
        (grounded + rss.sortedByDescending { it.publishedAtMillis }).forEach { art ->
            val key = art.title.lowercase(Locale.US).replace(Regex("[^a-z0-9]"), "").take(48)
            if (key.isNotEmpty() && seen.add(key)) {
                result.add(art)
            }
        }
        return result.take(55)
    }

    private fun fetchSingleEndpoint(endpoint: NewsFeedEndpoint): List<MarketNewsArticle> {
        return runCatching {
            val request = Request.Builder()
                .url(endpoint.url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) PaperPulse/1.0")
                .header("Accept", "application/rss+xml, application/xml, text/xml;q=0.9, */*;q=0.8")
                .build()
            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val body = resp.body?.string().orEmpty()
                if (body.isBlank()) return emptyList()
                parseRssXml(
                    xml = body,
                    defaultSource = endpoint.defaultSource,
                    category = endpoint.category
                ).take(14)
            }
        }.getOrElse { emptyList() }
    }

    companion object {
        private const val CACHE_TTL_MS = 180_000L
        private const val GROUNDING_CACHE_TTL_MS = 240_000L

        private val itemRegex = Regex("<item\\b[^>]*>(.*?)</item>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        private val htmlTagRegex = Regex("<[^>]+>")
        private val whitespaceRegex = Regex("\\s+")

        private val symbolKeywordPairs = listOf(
            "RELIANCE" to listOf("reliance", "jio", "mukesh ambani"),
            "TCS" to listOf("tcs", "tata consultancy"),
            "HDFCBANK" to listOf("hdfc bank", "hdfcbank"),
            "INFY" to listOf("infosys", "infy"),
            "ICICIBANK" to listOf("icici bank", "icicibank"),
            "SBIN" to listOf("sbi", "state bank of india"),
            "TATAMOTORS" to listOf("tata motors", "tatamotors"),
            "NIFTY" to listOf("nifty", "nse"),
            "BANKNIFTY" to listOf("bank nifty", "banknifty"),
            "SENSEX" to listOf("sensex", "bse"),
            "GOLD" to listOf("gold", "bullion"),
            "SILVER" to listOf("silver"),
            "CRUDEOIL" to listOf("crude oil", "brent", "wti", "opec"),
            "NATURALGAS" to listOf("natural gas", "natgas"),
            "BTCUSDT" to listOf("bitcoin", "btc"),
            "ETHUSDT" to listOf("ethereum", "eth"),
            "SOLUSDT" to listOf("solana", "sol")
        )

        fun parseRssXml(
            xml: String,
            defaultSource: String,
            category: MarketNewsCategory,
            nowMillis: Long = System.currentTimeMillis()
        ): List<MarketNewsArticle> {
            val articles = mutableListOf<MarketNewsArticle>()
            for (match in itemRegex.findAll(xml)) {
                val block = match.groupValues[1]
                val rawTitle = extractTag(block, "title")
                val link = extractTag(block, "link").trim()
                if (rawTitle.isBlank() || !isSafeHttpUrl(link)) continue

                val explicitSource = extractTag(block, "source")
                val (cleanTitle, parsedPublisher) = splitGoogleNewsTitle(rawTitle, explicitSource.ifBlank { defaultSource })
                val rawDesc = extractTag(block, "description")
                val cleanSummary = stripHtmlAndDecode(rawDesc)
                    .let { if (it.equals(cleanTitle, ignoreCase = true)) "" else it }
                    .take(220)
                val pubDateStr = extractTag(block, "pubDate")
                val publishedAt = parseRssDateMillis(pubDateStr) ?: nowMillis
                val related = detectRelatedSymbols("$cleanTitle $cleanSummary")

                articles.add(
                    MarketNewsArticle(
                        id = "${category.name}_${link.hashCode()}_${cleanTitle.hashCode()}",
                        title = cleanTitle,
                        summary = cleanSummary,
                        source = parsedPublisher,
                        url = link,
                        category = category,
                        publishedAtMillis = publishedAt,
                        relatedSymbols = related
                    )
                )
            }
            return articles
        }

        fun detectRelatedSymbols(text: String): List<String> {
            val lower = text.lowercase(Locale.US)
            val matched = LinkedHashSet<String>()
            for ((symbol, keywords) in symbolKeywordPairs) {
                if (keywords.any { kw -> Regex("\\b${Regex.escape(kw)}\\b").containsMatchIn(lower) }) {
                    matched.add(symbol)
                }
            }
            return matched.take(3).toList()
        }

        fun deduplicateAndSort(articles: List<MarketNewsArticle>): List<MarketNewsArticle> {
            val seen = HashSet<String>()
            return articles
                .sortedByDescending { it.publishedAtMillis }
                .filter { art ->
                    val key = art.title.lowercase(Locale.US).replace(Regex("[^a-z0-9]"), "").take(48)
                    key.isNotEmpty() && seen.add(key)
                }
                .take(45)
        }

        fun formatRelativeTime(publishedAtMillis: Long, nowMillis: Long = System.currentTimeMillis()): String {
            val diffSec = ((nowMillis - publishedAtMillis) / 1000L).coerceAtLeast(0L)
            return when {
                diffSec < 60L -> "Just now"
                diffSec < 3600L -> "${diffSec / 60L}m ago"
                diffSec < 86_400L -> "${diffSec / 3600L}h ago"
                else -> "${diffSec / 86_400L}d ago"
            }
        }

        fun isSafeHttpUrl(url: String): Boolean {
            val lower = url.trim().lowercase(Locale.US)
            return lower.startsWith("https://") || lower.startsWith("http://")
        }

        private fun extractTag(block: String, tagName: String): String {
            val regex = Regex("<$tagName\\b[^>]*>(.*?)</$tagName>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            val raw = regex.find(block)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            return stripCdataAndDecode(raw)
        }

        private fun stripCdataAndDecode(raw: String): String {
            val withoutCdata = raw
                .removePrefix("<![CDATA[")
                .removeSuffix("]]>")
                .trim()
            return decodeHtmlEntities(withoutCdata)
        }

        private fun stripHtmlAndDecode(raw: String): String {
            val decoded = decodeHtmlEntities(raw)
            val stripped = htmlTagRegex.replace(decoded, " ")
            return whitespaceRegex.replace(decodeHtmlEntities(stripped), " ").trim()
        }

        private fun decodeHtmlEntities(input: String): String {
            return input
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&#8217;", "’")
                .replace("&#8216;", "‘")
                .replace("&#8220;", "“")
                .replace("&#8221;", "”")
                .replace("&nbsp;", " ")
        }

        private fun splitGoogleNewsTitle(title: String, fallbackSource: String): Pair<String, String> {
            val clean = stripHtmlAndDecode(title)
            val idx = clean.lastIndexOf(" - ")
            if (idx > 18 && idx < clean.length - 3) {
                val head = clean.substring(0, idx).trim()
                val tail = clean.substring(idx + 3).trim()
                if (tail.length in 2..40) {
                    return head to tail
                }
            }
            return clean to fallbackSource
        }

        private fun parseRssDateMillis(rawDate: String): Long? {
            val trimmed = rawDate.trim()
            if (trimmed.isBlank()) return null
            return runCatching {
                ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
            }.getOrNull() ?: runCatching {
                val alt = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
                ZonedDateTime.parse(trimmed, alt).toInstant().toEpochMilli()
            }.getOrNull()
        }
    }
}
