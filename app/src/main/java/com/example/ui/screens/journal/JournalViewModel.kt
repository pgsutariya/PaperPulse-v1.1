package com.example.ui.screens.journal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.ai.AiConnectionStatus
import com.example.data.ai.AiMarketRepository
import com.example.data.news.MarketNewsArticle
import com.example.data.news.MarketNewsCategory
import com.example.data.news.MarketNewsFeedState
import com.example.data.news.MarketNewsRepository
import com.example.data.news.WatchlistNewsGroundingEngine
import com.example.data.repository.MarketDataRepository
import com.example.data.repository.TradingRepository
import com.example.data.signal.SignalRepository
import com.example.domain.analytics.JournalRiskAnalytics
import com.example.domain.analytics.RiskManagementScorecard
import com.example.domain.analytics.TradeCorrelationAnalytics
import com.example.domain.analytics.TradeCorrelationMatrix
import com.example.domain.analytics.TradingIntensityHeatmapData
import com.example.domain.engine.MarketClock
import com.example.domain.engine.RiskMath
import com.example.domain.model.AccountState
import com.example.domain.model.CapitalTransaction
import com.example.domain.model.Instrument
import com.example.domain.model.MarketSegment
import com.example.domain.model.OrderSide
import com.example.domain.model.Position
import com.example.domain.model.TradeJournalEntry
import com.example.domain.signal.AiUiState
import com.example.domain.signal.InstrumentTradePerformance
import com.example.domain.signal.TradeSignal
import com.example.ui.components.CalendarDaySentiment
import com.example.ui.components.CalendarHeatmapDay
import com.example.ui.components.DailyRealizedPnlPoint
import com.example.ui.components.MonthlyHeatmapData
import com.example.ui.components.PRESET_TRADE_TAGS
import com.example.ui.components.StrategyTagPerformance
import com.example.ui.components.TradeSummaryReportMetrics
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

data class JournalUiState(
    val entries: List<TradeJournalEntry> = emptyList(),
    val filteredEntries: List<TradeJournalEntry> = emptyList(),
    val selectedTagFilter: String? = null,
    val tagCounts: Map<String, Int> = emptyMap(),
    val strategyPerformance: List<StrategyTagPerformance> = emptyList(),
    val instrumentPerformance: List<InstrumentTradePerformance> = emptyList(),
    val capitalTransactions: List<CapitalTransaction> = emptyList(),
    val indiaDailyPnl30d: List<DailyRealizedPnlPoint> = emptyList(),
    val cryptoDailyPnl30d: List<DailyRealizedPnlPoint> = emptyList(),
    val indiaCalendarHeatmap: MonthlyHeatmapData = MonthlyHeatmapData(),
    val cryptoCalendarHeatmap: MonthlyHeatmapData = MonthlyHeatmapData(),
    val canGoNextHeatmapMonth: Boolean = false,
    val indiaIntensityHeatmap: TradingIntensityHeatmapData = TradingIntensityHeatmapData(),
    val cryptoIntensityHeatmap: TradingIntensityHeatmapData = TradingIntensityHeatmapData(),
    val indiaRiskScorecard: RiskManagementScorecard = RiskManagementScorecard(),
    val cryptoRiskScorecard: RiskManagementScorecard = RiskManagementScorecard(),
    val indiaCorrelationMatrix: TradeCorrelationMatrix = TradeCorrelationMatrix(),
    val cryptoCorrelationMatrix: TradeCorrelationMatrix = TradeCorrelationMatrix(),
    val indiaTradeSummary: TradeSummaryReportMetrics = TradeSummaryReportMetrics(),
    val cryptoTradeSummary: TradeSummaryReportMetrics = TradeSummaryReportMetrics(),
    val grossPnl: BigDecimal = BigDecimal.ZERO,
    val totalCharges: BigDecimal = BigDecimal.ZERO,
    val totalBrokerage: BigDecimal = BigDecimal.ZERO,
    val totalTaxes: BigDecimal = BigDecimal.ZERO,
    val netRealisedPnl: BigDecimal = BigDecimal.ZERO,
    val unrealisedPnl: BigDecimal = BigDecimal.ZERO,
    val cryptoGrossPnl: BigDecimal = BigDecimal.ZERO,
    val cryptoTotalCharges: BigDecimal = BigDecimal.ZERO,
    val cryptoNetRealisedPnl: BigDecimal = BigDecimal.ZERO,
    val cryptoUnrealisedPnl: BigDecimal = BigDecimal.ZERO,
    val winRatePercent: Int = 0,
    val coachState: AiUiState<String> = AiUiState.Idle,
    val aiConnectionStatus: AiConnectionStatus = AiConnectionStatus(),
    val newsFeedState: MarketNewsFeedState = MarketNewsFeedState(),
    val selectedNewsCategory: MarketNewsCategory = MarketNewsCategory.ALL,
    val watchlistNewsOnly: Boolean = true,
    val watchlistNewsSymbols: List<String> = emptyList(),
    val selectedNewsSymbol: String? = null,
    val filteredNewsArticles: List<MarketNewsArticle> = emptyList()
)

@HiltViewModel
class JournalViewModel @Inject constructor(
    private val tradingRepository: TradingRepository,
    private val marketDataRepository: MarketDataRepository,
    signalRepository: SignalRepository,
    private val aiMarketRepository: AiMarketRepository,
    private val marketNewsRepository: MarketNewsRepository
) : ViewModel() {

    private val _coachState = MutableStateFlow<AiUiState<String>>(AiUiState.Idle)
    private val _selectedTagFilter = MutableStateFlow<String?>(null)
    private val _selectedNewsCategory = MutableStateFlow(MarketNewsCategory.ALL)
    private val _watchlistNewsOnly = MutableStateFlow(true)
    private val _selectedNewsSymbol = MutableStateFlow<String?>(null)
    private val _selectedHeatmapMonth = MutableStateFlow(YearMonth.now(MarketClock.IST_ZONE))
    private var coachJob: Job? = null

    private var latestSnapshot = CoachSnapshot()

    private val newsFilterFlow = combine(
        marketNewsRepository.state,
        _selectedNewsCategory,
        _watchlistNewsOnly,
        _selectedNewsSymbol
    ) { newsState, newsCat, watchlistOnly, newsSymbol ->
        NewsTabAuxState(newsState, newsCat, watchlistOnly, newsSymbol)
    }

    private val aiAndFilterFlow = combine(
        _coachState,
        aiMarketRepository.connectionStatus,
        _selectedTagFilter,
        newsFilterFlow
    ) { coach, status, tagFilter, newsAux ->
        AuxJournalState(
            coachState = coach,
            aiStatus = status,
            selectedTagFilter = tagFilter,
            newsState = newsAux.newsState,
            selectedNewsCategory = newsAux.selectedNewsCategory,
            watchlistNewsOnly = newsAux.watchlistNewsOnly,
            selectedNewsSymbol = newsAux.selectedNewsSymbol
        )
    }

    init {
        viewModelScope.launch {
            val instruments = marketDataRepository.instruments.first { it.isNotEmpty() }
            marketNewsRepository.refreshForWatchlist(
                instruments = instruments,
                focusSymbol = null,
                force = false
            )
        }
    }

    val uiState: StateFlow<JournalUiState> = combine(
        tradingRepository.journalEntries,
        tradingRepository.positions,
        marketDataRepository.instruments,
        tradingRepository.accountState,
        signalRepository.activeSignals,
        tradingRepository.capitalTransactions,
        aiAndFilterFlow,
        _selectedHeatmapMonth
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val entries = values[0] as List<TradeJournalEntry>
        @Suppress("UNCHECKED_CAST")
        val positions = values[1] as List<Position>
        @Suppress("UNCHECKED_CAST")
        val instruments = values[2] as List<Instrument>
        val account = values[3] as AccountState
        @Suppress("UNCHECKED_CAST")
        val signalItems = values[4] as List<com.example.data.signal.SignalUiItem>
        @Suppress("UNCHECKED_CAST")
        val capitalTxns = values[5] as List<CapitalTransaction>
        val aux = values[6] as AuxJournalState
        val selectedMonth = values[7] as YearMonth

        latestSnapshot = CoachSnapshot(
            account = account,
            positions = positions,
            instruments = instruments,
            journalEntries = entries,
            activeSignals = signalItems.map { it.signal }
        )

        val ltpMap = instruments.associate { it.symbol to it.ltp }
        var indiaUnrealised = BigDecimal.ZERO
        var cryptoUnrealised = BigDecimal.ZERO
        positions.forEach { pos ->
            val ltp = ltpMap[pos.symbol] ?: pos.averageEntryPrice
            val pnl = RiskMath.calculateUnrealisedPnl(
                entryPrice = pos.averageEntryPrice,
                ltp = ltp,
                quantity = pos.quantity,
                isLong = pos.side == OrderSide.BUY
            )
            if (pos.segment == MarketSegment.CRYPTO) {
                cryptoUnrealised = cryptoUnrealised.add(pnl)
            } else {
                indiaUnrealised = indiaUnrealised.add(pnl)
            }
        }

        val tagCounts = computeTagCounts(entries)
        val strategyPerformance = computeStrategyTagPerformance(entries)
        val filteredEntries = filterEntriesByTag(entries, aux.selectedTagFilter)

        // All historical performance metrics respect the active Strategy Tag filter when selected
        val filteredIndia = filteredEntries.filter { it.segment != MarketSegment.CRYPTO }
        val filteredCrypto = filteredEntries.filter { it.segment == MarketSegment.CRYPTO }

        val gross = filteredIndia.fold(BigDecimal.ZERO) { acc, e -> acc.add(e.grossPnl) }
        val charges = filteredIndia.fold(BigDecimal.ZERO) { acc, e -> acc.add(e.charges) }
        val brokerage = filteredIndia.fold(BigDecimal.ZERO) { acc, e -> acc.add(e.brokerage) }
        val taxes = filteredIndia.fold(BigDecimal.ZERO) { acc, e ->
            acc.add(e.sttCtt).add(e.exchangeCharges).add(e.sebiCharges).add(e.stampDuty).add(e.gst)
        }
        val net = filteredIndia.fold(BigDecimal.ZERO) { acc, e -> acc.add(e.netPnl) }

        val cryptoGross = filteredCrypto.fold(BigDecimal.ZERO) { acc, e -> acc.add(e.grossPnl) }
        val cryptoCharges = filteredCrypto.fold(BigDecimal.ZERO) { acc, e -> acc.add(e.charges) }
        val cryptoNet = filteredCrypto.fold(BigDecimal.ZERO) { acc, e -> acc.add(e.netPnl) }

        val wins = filteredEntries.count { it.netPnl > BigDecimal.ZERO }
        val winRate = if (filteredEntries.isEmpty()) 0 else (wins * 100) / filteredEntries.size

        val byInstrument = filteredEntries
            .groupBy { it.symbol }
            .map { (symbol, list) ->
                val seg = list.first().segment
                val w = list.count { it.netPnl > BigDecimal.ZERO }
                val l = list.size - w
                InstrumentTradePerformance(
                    symbol = symbol,
                    segment = seg,
                    totalTrades = list.size,
                    wins = w,
                    losses = l,
                    grossPnl = list.fold(BigDecimal.ZERO) { a, e -> a.add(e.grossPnl) },
                    totalCharges = list.fold(BigDecimal.ZERO) { a, e -> a.add(e.charges) },
                    netPnl = list.fold(BigDecimal.ZERO) { a, e -> a.add(e.netPnl) }
                )
            }
            .sortedWith(compareByDescending<InstrumentTradePerformance> { it.totalTrades }.thenByDescending { it.netPnl })
        val nowMillis = System.currentTimeMillis()
        val indiaDaily30d = buildDailyPnlSeries(filteredIndia, days = 30, nowMillis = nowMillis)
        val cryptoDaily30d = buildDailyPnlSeries(filteredCrypto, days = 30, nowMillis = nowMillis)
        val indiaHeatmap = buildMonthlyCalendarHeatmap(filteredIndia, selectedMonth)
        val cryptoHeatmap = buildMonthlyCalendarHeatmap(filteredCrypto, selectedMonth)
        val currentMonth = YearMonth.now(MarketClock.IST_ZONE)
        val indiaIntensity = JournalRiskAnalytics.buildTradingIntensityHeatmap(filteredIndia)
        val cryptoIntensity = JournalRiskAnalytics.buildTradingIntensityHeatmap(filteredCrypto)
        val indiaScorecard = JournalRiskAnalytics.buildRiskManagementScorecard(
            entries = filteredIndia,
            startingCapital = account.virtualCapital
        )
        val cryptoScorecard = JournalRiskAnalytics.buildRiskManagementScorecard(
            entries = filteredCrypto,
            startingCapital = account.cryptoVirtualCapital
        )
        val indiaCorrelation = TradeCorrelationAnalytics.buildCorrelationMatrix(filteredIndia)
        val cryptoCorrelation = TradeCorrelationAnalytics.buildCorrelationMatrix(filteredCrypto)
        val indiaSummary = buildTradeSummaryReport(filteredIndia)
        val cryptoSummary = buildTradeSummaryReport(filteredCrypto)

        val watchlistSymbols = WatchlistNewsGroundingEngine.extractWatchlistSymbols(instruments)
        val effectiveNewsSymbol = aux.selectedNewsSymbol?.takeIf { sym ->
            watchlistSymbols.any { it.equals(sym, ignoreCase = true) }
        }
        val watchlistFilteredNews = WatchlistNewsGroundingEngine.filterArticlesForWatchlist(
            articles = aux.newsState.articles,
            watchlist = instruments,
            selectedSymbol = effectiveNewsSymbol,
            watchlistOnly = aux.watchlistNewsOnly
        )
        val filteredNews = if (aux.selectedNewsCategory == MarketNewsCategory.ALL) {
            watchlistFilteredNews
        } else {
            watchlistFilteredNews.filter { it.category == aux.selectedNewsCategory }
        }

        JournalUiState(
            entries = entries,
            filteredEntries = filteredEntries,
            selectedTagFilter = aux.selectedTagFilter,
            tagCounts = tagCounts,
            strategyPerformance = strategyPerformance,
            instrumentPerformance = byInstrument,
            capitalTransactions = capitalTxns,
            indiaDailyPnl30d = indiaDaily30d,
            cryptoDailyPnl30d = cryptoDaily30d,
            indiaCalendarHeatmap = indiaHeatmap,
            cryptoCalendarHeatmap = cryptoHeatmap,
            canGoNextHeatmapMonth = selectedMonth.isBefore(currentMonth),
            indiaIntensityHeatmap = indiaIntensity,
            cryptoIntensityHeatmap = cryptoIntensity,
            indiaRiskScorecard = indiaScorecard,
            cryptoRiskScorecard = cryptoScorecard,
            indiaCorrelationMatrix = indiaCorrelation,
            cryptoCorrelationMatrix = cryptoCorrelation,
            indiaTradeSummary = indiaSummary,
            cryptoTradeSummary = cryptoSummary,
            grossPnl = gross,
            totalCharges = charges,
            totalBrokerage = brokerage,
            totalTaxes = taxes,
            netRealisedPnl = net,
            unrealisedPnl = indiaUnrealised,
            cryptoGrossPnl = cryptoGross,
            cryptoTotalCharges = cryptoCharges,
            cryptoNetRealisedPnl = cryptoNet,
            cryptoUnrealisedPnl = cryptoUnrealised,
            winRatePercent = winRate,
            coachState = aux.coachState,
            aiConnectionStatus = aux.aiStatus,
            newsFeedState = aux.newsState,
            selectedNewsCategory = aux.selectedNewsCategory,
            watchlistNewsOnly = aux.watchlistNewsOnly,
            watchlistNewsSymbols = watchlistSymbols,
            selectedNewsSymbol = effectiveNewsSymbol,
            filteredNewsArticles = filteredNews
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = JournalUiState()
    )

    fun switchAiModel(modelName: String) {
        aiMarketRepository.switchActiveModel(modelName)
    }

    fun selectTagFilter(tag: String?) {
        _selectedTagFilter.value = tag?.let { normalizeTag(it) }?.takeIf { it.isNotEmpty() }
    }

    fun previousHeatmapMonth() {
        _selectedHeatmapMonth.value = _selectedHeatmapMonth.value.minusMonths(1)
    }

    fun nextHeatmapMonth() {
        val currentMonth = YearMonth.now(MarketClock.IST_ZONE)
        val candidate = _selectedHeatmapMonth.value.plusMonths(1)
        if (!candidate.isAfter(currentMonth)) {
            _selectedHeatmapMonth.value = candidate
        }
    }

    fun toggleTagOnEntry(entryId: String, currentTags: List<String>, tag: String) {
        val cleaned = normalizeTag(tag)
        if (cleaned.isEmpty()) return
        val exists = currentTags.any { normalizeTag(it).equals(cleaned, ignoreCase = true) }
        val updated = if (exists) {
            currentTags.filterNot { normalizeTag(it).equals(cleaned, ignoreCase = true) }
        } else {
            currentTags + cleaned
        }
        viewModelScope.launch {
            tradingRepository.updateJournalTags(entryId, updated)
        }
    }

    fun addTagToEntry(entryId: String, currentTags: List<String>, newTag: String) {
        val cleaned = normalizeTag(newTag)
        if (cleaned.isEmpty()) return
        if (currentTags.any { normalizeTag(it).equals(cleaned, ignoreCase = true) }) return
        viewModelScope.launch {
            tradingRepository.updateJournalTags(entryId, currentTags + cleaned)
        }
    }

    fun removeTagFromEntry(entryId: String, currentTags: List<String>, tagToRemove: String) {
        val cleaned = normalizeTag(tagToRemove)
        val updated = currentTags.filterNot { normalizeTag(it).equals(cleaned, ignoreCase = true) }
        viewModelScope.launch {
            tradingRepository.updateJournalTags(entryId, updated)
        }
    }

    fun selectNewsCategory(category: MarketNewsCategory) {
        _selectedNewsCategory.value = category
    }

    fun setWatchlistNewsOnly(enabled: Boolean) {
        _watchlistNewsOnly.value = enabled
        if (!enabled) {
            _selectedNewsSymbol.value = null
        }
    }

    fun selectNewsSymbol(symbol: String?) {
        _selectedNewsSymbol.value = symbol?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun refreshNews(focusSymbol: String? = _selectedNewsSymbol.value, force: Boolean = true) {
        viewModelScope.launch {
            val instruments = latestSnapshot.instruments.ifEmpty {
                marketDataRepository.instruments.first()
            }
            if (instruments.isNotEmpty()) {
                marketNewsRepository.refreshForWatchlist(
                    instruments = instruments,
                    focusSymbol = focusSymbol,
                    force = force
                )
            } else {
                marketNewsRepository.refresh(force = force)
            }
        }
    }

    fun askAiCoach(question: String) {
        val snap = latestSnapshot
        coachJob?.cancel()
        coachJob = viewModelScope.launch {
            aiMarketRepository.streamPortfolioCoach(
                question = question,
                account = snap.account,
                positions = snap.positions,
                instruments = snap.instruments,
                journalEntries = snap.journalEntries,
                activeSignals = snap.activeSignals
            ).collect { state ->
                _coachState.value = state
            }
        }
    }

    fun saveNotes(entryId: String, notes: String) {
        viewModelScope.launch {
            tradingRepository.updateJournalNotes(entryId, notes)
        }
    }

    private data class NewsTabAuxState(
        val newsState: MarketNewsFeedState,
        val selectedNewsCategory: MarketNewsCategory,
        val watchlistNewsOnly: Boolean,
        val selectedNewsSymbol: String?
    )

    private data class AuxJournalState(
        val coachState: AiUiState<String>,
        val aiStatus: AiConnectionStatus,
        val selectedTagFilter: String?,
        val newsState: MarketNewsFeedState,
        val selectedNewsCategory: MarketNewsCategory,
        val watchlistNewsOnly: Boolean,
        val selectedNewsSymbol: String?
    )

    private data class CoachSnapshot(
        val account: AccountState = AccountState(BigDecimal("1000000"), BigDecimal("1000000"), BigDecimal.ZERO),
        val positions: List<Position> = emptyList(),
        val instruments: List<Instrument> = emptyList(),
        val journalEntries: List<TradeJournalEntry> = emptyList(),
        val activeSignals: List<TradeSignal> = emptyList()
    )

    companion object {
        private val shortDayFormatter = DateTimeFormatter.ofPattern("dd MMM", Locale.US)
        private val fullDayFormatter = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.US)
        private val monthYearFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US)

        fun buildMonthlyCalendarHeatmap(
            entries: List<TradeJournalEntry>,
            yearMonth: YearMonth
        ): MonthlyHeatmapData {
            val groupedByDate = entries
                .groupBy { entry ->
                    Instant.ofEpochMilli(entry.closedAtMillis).atZone(MarketClock.IST_ZONE).toLocalDate()
                }
                .filterKeys { YearMonth.from(it) == yearMonth }

            val dailyNetByDate = groupedByDate.mapValues { (_, list) ->
                list.fold(BigDecimal.ZERO) { acc, e -> acc.add(e.netPnl) }
            }
            val maxAbsPnl = dailyNetByDate.values
                .maxOfOrNull { it.abs().toDouble() }
                ?.takeIf { it > 0.0 }
                ?: 1.0

            val daysInMonth = yearMonth.lengthOfMonth()
            val firstDayOfWeekOffset = yearMonth.atDay(1).dayOfWeek.value - 1 // Monday=0 .. Sunday=6

            var positiveDays = 0
            var negativeDays = 0
            var neutralDays = 0
            var monthNet = BigDecimal.ZERO

            val days = (1..daysInMonth).map { dom ->
                val date = yearMonth.atDay(dom)
                val dayTrades = groupedByDate[date].orEmpty()
                val net = dailyNetByDate[date] ?: BigDecimal.ZERO
                val wins = dayTrades.count { it.netPnl > BigDecimal.ZERO }
                val losses = dayTrades.count { it.netPnl < BigDecimal.ZERO }
                val sentiment = when {
                    dayTrades.isEmpty() -> CalendarDaySentiment.NO_TRADE
                    net > BigDecimal.ZERO -> {
                        positiveDays += 1
                        CalendarDaySentiment.POSITIVE
                    }
                    net < BigDecimal.ZERO -> {
                        negativeDays += 1
                        CalendarDaySentiment.NEGATIVE
                    }
                    else -> {
                        neutralDays += 1
                        CalendarDaySentiment.NEUTRAL
                    }
                }
                monthNet = monthNet.add(net)
                val intensity = if (dayTrades.isEmpty() || sentiment == CalendarDaySentiment.NEUTRAL) {
                    0f
                } else {
                    (net.abs().toDouble() / maxAbsPnl).toFloat().coerceIn(0.15f, 1f)
                }

                CalendarHeatmapDay(
                    date = date,
                    netPnl = net,
                    tradeCount = dayTrades.size,
                    wins = wins,
                    losses = losses,
                    sentiment = sentiment,
                    intensity = intensity
                )
            }

            return MonthlyHeatmapData(
                yearMonth = yearMonth,
                monthLabel = yearMonth.format(monthYearFormatter),
                leadingEmptyCells = firstDayOfWeekOffset,
                days = days,
                positiveDays = positiveDays,
                negativeDays = negativeDays,
                neutralDays = neutralDays,
                monthNetPnl = monthNet
            )
        }

        fun normalizeTag(raw: String): String {
            val stripped = raw.trim().trimStart('#').trim()
            if (stripped.isEmpty()) return ""
            return PRESET_TRADE_TAGS.firstOrNull { it.equals(stripped, ignoreCase = true) } ?: stripped
        }

        fun computeTagCounts(entries: List<TradeJournalEntry>): Map<String, Int> {
            val canonicalByLower = LinkedHashMap<String, String>()
            val countsByLower = LinkedHashMap<String, Int>()
            entries.forEach { entry ->
                val uniqueEntryTags = LinkedHashSet<String>()
                entry.tags.forEach { tag ->
                    val cleaned = normalizeTag(tag)
                    if (cleaned.isNotEmpty()) {
                        val key = cleaned.lowercase(Locale.US)
                        if (uniqueEntryTags.add(key)) {
                            canonicalByLower.putIfAbsent(key, cleaned)
                            countsByLower[key] = (countsByLower[key] ?: 0) + 1
                        }
                    }
                }
            }
            val result = LinkedHashMap<String, Int>()
            canonicalByLower.forEach { (key, display) ->
                result[display] = countsByLower[key] ?: 0
            }
            return result
        }

        fun filterEntriesByTag(
            entries: List<TradeJournalEntry>,
            selectedTag: String?
        ): List<TradeJournalEntry> {
            val target = selectedTag?.let { normalizeTag(it) }?.takeIf { it.isNotEmpty() } ?: return entries
            return entries.filter { entry ->
                entry.tags.any { normalizeTag(it).equals(target, ignoreCase = true) }
            }
        }

        fun computeStrategyTagPerformance(entries: List<TradeJournalEntry>): List<StrategyTagPerformance> {
            if (entries.isEmpty()) return emptyList()
            val canonicalByLower = LinkedHashMap<String, String>()
            val grouped = LinkedHashMap<String, MutableList<TradeJournalEntry>>()

            entries.forEach { entry ->
                val seenInEntry = HashSet<String>()
                entry.tags.forEach { rawTag ->
                    val cleaned = normalizeTag(rawTag)
                    if (cleaned.isNotEmpty()) {
                        val key = cleaned.lowercase(Locale.US)
                        if (seenInEntry.add(key)) {
                            canonicalByLower.putIfAbsent(key, cleaned)
                            grouped.getOrPut(key) { mutableListOf() }.add(entry)
                        }
                    }
                }
            }

            return grouped.map { (key, list) ->
                val wins = list.count { it.netPnl > BigDecimal.ZERO }
                val losses = list.count { it.netPnl < BigDecimal.ZERO }
                val winRate = (wins * 100.0) / list.size.toDouble()
                val indiaList = list.filter { it.segment != MarketSegment.CRYPTO }
                val cryptoList = list.filter { it.segment == MarketSegment.CRYPTO }
                val indiaNet = indiaList.fold(BigDecimal.ZERO) { acc, e -> acc.add(e.netPnl) }
                val cryptoNet = cryptoList.fold(BigDecimal.ZERO) { acc, e -> acc.add(e.netPnl) }

                val grossWin = list.filter { it.netPnl > BigDecimal.ZERO }
                    .fold(BigDecimal.ZERO) { acc, e -> acc.add(e.netPnl) }
                val grossLossAbs = list.filter { it.netPnl < BigDecimal.ZERO }
                    .fold(BigDecimal.ZERO) { acc, e -> acc.add(e.netPnl.abs()) }
                val pf: Double? = when {
                    grossLossAbs > BigDecimal.ZERO -> grossWin.toDouble() / grossLossAbs.toDouble()
                    grossWin > BigDecimal.ZERO -> Double.POSITIVE_INFINITY
                    else -> null
                }

                StrategyTagPerformance(
                    tag = canonicalByLower[key] ?: key,
                    totalTrades = list.size,
                    wins = wins,
                    losses = losses,
                    winRatePercent = winRate,
                    indiaNetPnl = indiaNet,
                    cryptoNetPnl = cryptoNet,
                    hasIndiaTrades = indiaList.isNotEmpty(),
                    hasCryptoTrades = cryptoList.isNotEmpty(),
                    profitFactor = pf
                )
            }.sortedWith(
                compareByDescending<StrategyTagPerformance> { it.totalTrades }
                    .thenByDescending { it.combinedNetForSort }
            )
        }

        fun buildDailyPnlSeries(
            entries: List<TradeJournalEntry>,
            days: Int = 30,
            nowMillis: Long = System.currentTimeMillis()
        ): List<DailyRealizedPnlPoint> {
            val safeDays = days.coerceAtLeast(1)
            val todayIst = Instant.ofEpochMilli(nowMillis).atZone(MarketClock.IST_ZONE).toLocalDate()
            val startDate = todayIst.minusDays((safeDays - 1).toLong())

            val groupedByDate = entries.groupBy { entry ->
                Instant.ofEpochMilli(entry.closedAtMillis).atZone(MarketClock.IST_ZONE).toLocalDate()
            }

            return (0 until safeDays).map { offset ->
                val date = startDate.plusDays(offset.toLong())
                val dayTrades = groupedByDate[date].orEmpty()
                val netPnl = dayTrades.fold(BigDecimal.ZERO) { acc, e -> acc.add(e.netPnl) }
                val wins = dayTrades.count { it.netPnl > BigDecimal.ZERO }
                val losses = dayTrades.count { it.netPnl < BigDecimal.ZERO }
                DailyRealizedPnlPoint(
                    dayStartMillis = date.atStartOfDay(MarketClock.IST_ZONE).toInstant().toEpochMilli(),
                    shortDateLabel = date.format(shortDayFormatter),
                    fullDateLabel = date.format(fullDayFormatter),
                    netPnl = netPnl,
                    tradeCount = dayTrades.size,
                    wins = wins,
                    losses = losses
                )
            }
        }

        fun buildTradeSummaryReport(entries: List<TradeJournalEntry>): TradeSummaryReportMetrics {
            if (entries.isEmpty()) return TradeSummaryReportMetrics()

            val total = entries.size
            val winsList = entries.filter { it.netPnl > BigDecimal.ZERO }
            val lossesList = entries.filter { it.netPnl < BigDecimal.ZERO }
            val breakEvenCount = total - winsList.size - lossesList.size

            val totalNet = entries.fold(BigDecimal.ZERO) { acc, e -> acc.add(e.netPnl) }
            val totalCharges = entries.fold(BigDecimal.ZERO) { acc, e -> acc.add(e.charges) }
            val grossProfit = winsList.fold(BigDecimal.ZERO) { acc, e -> acc.add(e.netPnl) }
            val grossLossAbs = lossesList.fold(BigDecimal.ZERO) { acc, e -> acc.add(e.netPnl.abs()) }

            val winRate = (winsList.size * 100.0) / total.toDouble()
            val avgPnl = totalNet.divide(BigDecimal(total), 2, RoundingMode.HALF_UP)
            val avgWin = if (winsList.isEmpty()) BigDecimal.ZERO else {
                grossProfit.divide(BigDecimal(winsList.size), 2, RoundingMode.HALF_UP)
            }
            val avgLoss = if (lossesList.isEmpty()) BigDecimal.ZERO else {
                grossLossAbs.divide(BigDecimal(lossesList.size), 2, RoundingMode.HALF_UP).negate()
            }

            val profitFactor: Double? = when {
                grossLossAbs > BigDecimal.ZERO -> grossProfit.toDouble() / grossLossAbs.toDouble()
                grossProfit > BigDecimal.ZERO -> Double.POSITIVE_INFINITY
                else -> 0.0
            }

            val payoffRatio: Double? = when {
                avgLoss < BigDecimal.ZERO -> avgWin.toDouble() / avgLoss.abs().toDouble()
                avgWin > BigDecimal.ZERO -> Double.POSITIVE_INFINITY
                else -> null
            }

            val largestWin = winsList.maxOfOrNull { it.netPnl } ?: BigDecimal.ZERO
            val largestLoss = lossesList.minOfOrNull { it.netPnl } ?: BigDecimal.ZERO

            var maxWinStreak = 0
            var maxLossStreak = 0
            var curWinStreak = 0
            var curLossStreak = 0
            entries.sortedBy { it.closedAtMillis }.forEach { e ->
                when {
                    e.netPnl > BigDecimal.ZERO -> {
                        curWinStreak += 1
                        curLossStreak = 0
                        if (curWinStreak > maxWinStreak) maxWinStreak = curWinStreak
                    }
                    e.netPnl < BigDecimal.ZERO -> {
                        curLossStreak += 1
                        curWinStreak = 0
                        if (curLossStreak > maxLossStreak) maxLossStreak = curLossStreak
                    }
                    else -> {
                        curWinStreak = 0
                        curLossStreak = 0
                    }
                }
            }

            return TradeSummaryReportMetrics(
                totalTrades = total,
                winningTrades = winsList.size,
                losingTrades = lossesList.size,
                breakEvenTrades = breakEvenCount,
                winRatePercent = winRate,
                avgPnlPerTrade = avgPnl,
                avgWin = avgWin,
                avgLoss = avgLoss,
                profitFactor = profitFactor,
                payoffRatio = payoffRatio,
                largestWin = largestWin,
                largestLoss = largestLoss,
                maxWinStreak = maxWinStreak,
                maxLossStreak = maxLossStreak,
                totalNetPnl = totalNet,
                totalCharges = totalCharges
            )
        }
    }
}
