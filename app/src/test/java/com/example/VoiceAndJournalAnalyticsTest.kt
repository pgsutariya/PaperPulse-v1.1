package com.example

import com.example.data.alerts.PriceAlertCondition
import com.example.data.alerts.PriceAlertRepository
import com.example.data.local.encodeTagsCsv
import com.example.data.local.parseTagsCsv
import com.example.data.news.GroundedNewsSource
import com.example.data.news.MarketNewsArticle
import com.example.data.news.MarketNewsCategory
import com.example.data.news.MarketNewsRepository
import com.example.data.news.NewsSentiment
import com.example.data.news.WatchlistNewsGroundingEngine
import com.example.domain.analytics.JournalRiskAnalytics
import com.example.domain.engine.MarketClock
import com.example.domain.model.ExitReason
import com.example.domain.model.Instrument
import com.example.domain.model.InstrumentType
import com.example.domain.model.MarketSegment
import com.example.domain.model.OrderSide
import com.example.domain.model.OrderType
import com.example.domain.model.ProductType
import com.example.domain.model.TradeJournalEntry
import com.example.domain.signal.SignalConfidence
import com.example.domain.signal.SignalDirection
import com.example.domain.signal.SignalOutcome
import com.example.domain.signal.SignalProbabilityCalculator
import com.example.domain.signal.SignalRecord
import com.example.domain.signal.SignalStrategy
import com.example.domain.signal.Timeframe
import com.example.domain.signal.TradeSignal
import com.example.domain.voice.VoiceOrderCommandParser
import com.example.domain.voice.VoiceOrderParseResult
import com.example.ui.components.CalendarDaySentiment
import com.example.ui.screens.journal.JournalViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

class VoiceAndJournalAnalyticsTest {

    private val sampleInstruments = listOf(
        Instrument(
            symbol = "RELIANCE",
            displayName = "Reliance Industries Ltd",
            segment = MarketSegment.NSE,
            type = InstrumentType.STOCK,
            lotSize = 1,
            tickSize = BigDecimal("0.05"),
            ltp = BigDecimal("1295.40"),
            previousClose = BigDecimal("1280.00"),
            dayHigh = BigDecimal("1302.00"),
            dayLow = BigDecimal("1279.00"),
            volume = 4_500_000L,
            supportsCarryForward = true
        ),
        Instrument(
            symbol = "NIFTY26NOVFUT",
            displayName = "Nifty 50 Futures",
            segment = MarketSegment.NSE,
            type = InstrumentType.FUTURES,
            lotSize = 25,
            tickSize = BigDecimal("0.05"),
            ltp = BigDecimal("24250.00"),
            previousClose = BigDecimal("24180.00"),
            dayHigh = BigDecimal("24310.00"),
            dayLow = BigDecimal("24150.00"),
            volume = 850_000L,
            supportsCarryForward = true
        ),
        Instrument(
            symbol = "BTCUSDT",
            displayName = "Bitcoin / Tether USD",
            segment = MarketSegment.CRYPTO,
            type = InstrumentType.STOCK,
            lotSize = 1,
            tickSize = BigDecimal("0.50"),
            ltp = BigDecimal("91500.00"),
            previousClose = BigDecimal("90800.00"),
            dayHigh = BigDecimal("92100.00"),
            dayLow = BigDecimal("90200.00"),
            volume = 12_000L,
            supportsCarryForward = true
        )
    )

    @Test
    fun parseVoiceOrder_buyFiveLotsOfReliance() {
        val result = VoiceOrderCommandParser.parse("Buy 5 lots of Reliance", sampleInstruments)
        assertTrue(result is VoiceOrderParseResult.Success)
        val order = (result as VoiceOrderParseResult.Success).order
        assertEquals(OrderSide.BUY, order.side)
        assertEquals("RELIANCE", order.instrument.symbol)
        assertEquals(5, order.quantity)
        assertEquals(5, order.lotsRequested)
        assertEquals(OrderType.MARKET, order.orderType)
        assertEquals(ProductType.INTRADAY, order.productType)
    }

    @Test
    fun parseVoiceOrder_sellTwoLotsOfNiftyIntradayMultipliesLotSize() {
        val result = VoiceOrderCommandParser.parse("Sell two lots of Nifty intraday", sampleInstruments)
        assertTrue(result is VoiceOrderParseResult.Success)
        val order = (result as VoiceOrderParseResult.Success).order
        assertEquals(OrderSide.SELL, order.side)
        assertEquals("NIFTY26NOVFUT", order.instrument.symbol)
        assertEquals(50, order.quantity)
        assertEquals(2, order.lotsRequested)
        assertEquals(ProductType.INTRADAY, order.productType)
    }

    @Test
    fun parseVoiceOrder_bracketOrderWithStopLossAndTarget() {
        val result = VoiceOrderCommandParser.parse(
            "Buy 1 BTCUSDT stop loss 88000 target 95000 carry forward",
            sampleInstruments
        )
        assertTrue(result is VoiceOrderParseResult.Success)
        val order = (result as VoiceOrderParseResult.Success).order
        assertEquals("BTCUSDT", order.instrument.symbol)
        assertEquals(OrderSide.BUY, order.side)
        assertEquals(1, order.quantity)
        assertEquals(OrderType.BRACKET, order.orderType)
        assertEquals(ProductType.CARRY_FORWARD, order.productType)
        assertEquals(BigDecimal("88000"), order.stopLossPrice)
        assertEquals(BigDecimal("95000"), order.targetPrice)

        val request = order.toPlacementRequest()
        assertTrue(request.slEnabled)
        assertTrue(request.targetEnabled)
        assertEquals(OrderType.BRACKET, request.orderType)
    }

    @Test
    fun buildDailyPnlSeries_bucketsLast30IstDaysAndZeroFillsEmptyDays() {
        val anchorDate = LocalDate.of(2026, 11, 25)
        val nowMillis = anchorDate.atTime(14, 0).atZone(MarketClock.IST_ZONE).toInstant().toEpochMilli()
        val twoDaysAgoMillis = anchorDate.minusDays(2).atTime(11, 30).atZone(MarketClock.IST_ZONE).toInstant().toEpochMilli()
        val oldMillis = anchorDate.minusDays(35).atTime(10, 0).atZone(MarketClock.IST_ZONE).toInstant().toEpochMilli()

        val entries = listOf(
            sampleJournalEntry("t1", BigDecimal("1500.00"), nowMillis),
            sampleJournalEntry("t2", BigDecimal("-400.00"), nowMillis + 60_000L),
            sampleJournalEntry("t3", BigDecimal("-950.00"), twoDaysAgoMillis),
            sampleJournalEntry("t4", BigDecimal("5000.00"), oldMillis) // Outside 30d window
        )

        val series = JournalViewModel.buildDailyPnlSeries(entries, days = 30, nowMillis = nowMillis)
        assertEquals(30, series.size)

        val todayPoint = series.last()
        assertEquals(0, todayPoint.netPnl.compareTo(BigDecimal("1100.00")))
        assertEquals(2, todayPoint.tradeCount)
        assertEquals(1, todayPoint.wins)
        assertEquals(1, todayPoint.losses)

        val twoDaysAgoPoint = series[series.lastIndex - 2]
        assertEquals(0, twoDaysAgoPoint.netPnl.compareTo(BigDecimal("-950.00")))
        assertEquals(1, twoDaysAgoPoint.tradeCount)
        assertEquals(0, twoDaysAgoPoint.wins)
        assertEquals(1, twoDaysAgoPoint.losses)

        // Yesterday had no trades -> zero-filled
        val yesterdayPoint = series[series.lastIndex - 1]
        assertEquals(0, yesterdayPoint.netPnl.compareTo(BigDecimal.ZERO))
        assertEquals(0, yesterdayPoint.tradeCount)

        // Total across 30 days excludes the 35-day-old trade
        val total30d = series.fold(BigDecimal.ZERO) { acc, pt -> acc.add(pt.netPnl) }
        assertEquals(0, total30d.compareTo(BigDecimal("150.00")))
    }

    @Test
    fun buildTradeSummaryReport_calculatesWinRateAvgPnlProfitFactorAndStreaks() {
        val base = 1_700_000_000_000L
        val entries = listOf(
            sampleJournalEntry("t1", BigDecimal("2000.00"), base + 1_000L),
            sampleJournalEntry("t2", BigDecimal("1000.00"), base + 2_000L),
            sampleJournalEntry("t3", BigDecimal("-500.00"), base + 3_000L),
            sampleJournalEntry("t4", BigDecimal("-1000.00"), base + 4_000L),
            sampleJournalEntry("t5", BigDecimal("1500.00"), base + 5_000L)
        )

        val report = JournalViewModel.buildTradeSummaryReport(entries)
        assertEquals(5, report.totalTrades)
        assertEquals(3, report.winningTrades)
        assertEquals(2, report.losingTrades)
        assertEquals(0, report.breakEvenTrades)
        assertEquals(60.0, report.winRatePercent, 0.01)
        // Net = 2000 + 1000 - 500 - 1000 + 1500 = 3000 -> avg = 600.00
        assertEquals(0, report.avgPnlPerTrade.compareTo(BigDecimal("600.00")))
        // Avg win = 4500 / 3 = 1500.00; Avg loss = -1500 / 2 = -750.00
        assertEquals(0, report.avgWin.compareTo(BigDecimal("1500.00")))
        assertEquals(0, report.avgLoss.compareTo(BigDecimal("-750.00")))
        // Profit Factor = 4500 / 1500 = 3.00
        assertEquals(3.0, report.profitFactor!!, 0.001)
        // Payoff Ratio = 1500 / 750 = 2.00
        assertEquals(2.0, report.payoffRatio!!, 0.001)
        assertEquals(0, report.largestWin.compareTo(BigDecimal("2000.00")))
        assertEquals(0, report.largestLoss.compareTo(BigDecimal("-1000.00")))
        assertEquals(2, report.maxWinStreak)
        assertEquals(2, report.maxLossStreak)
    }

    @Test
    fun buildTradeSummaryReport_handlesEmptyAndAllWinningEdgeCases() {
        val emptyReport = JournalViewModel.buildTradeSummaryReport(emptyList())
        assertEquals(0, emptyReport.totalTrades)
        assertEquals(null, emptyReport.profitFactor)
        assertEquals(null, emptyReport.payoffRatio)

        val allWins = listOf(
            sampleJournalEntry("w1", BigDecimal("400.00"), 1_700_000_000_000L),
            sampleJournalEntry("w2", BigDecimal("600.00"), 1_700_000_060_000L)
        )
        val winReport = JournalViewModel.buildTradeSummaryReport(allWins)
        assertEquals(100.0, winReport.winRatePercent, 0.01)
        assertEquals(Double.POSITIVE_INFINITY, winReport.profitFactor!!, 0.0)
        assertEquals(0, winReport.avgPnlPerTrade.compareTo(BigDecimal("500.00")))
    }

    @Test
    fun tradeTags_encodeParseFilterAndCountCaseInsensitively() {
        val encoded = encodeTagsCsv(listOf("Breakout", " breakout ", "News", "Gap Up"))
        assertEquals("Breakout|News|Gap Up", encoded)
        assertEquals(listOf("Breakout", "News", "Gap Up"), parseTagsCsv(encoded))

        val base = 1_700_000_000_000L
        val entries = listOf(
            sampleJournalEntry("e1", BigDecimal("500.00"), base, listOf("Breakout", "News")),
            sampleJournalEntry("e2", BigDecimal("-200.00"), base + 1000L, listOf("Reversal")),
            sampleJournalEntry("e3", BigDecimal("900.00"), base + 2000L, listOf("breakout"))
        )

        val counts = JournalViewModel.computeTagCounts(entries)
        assertEquals(2, counts["Breakout"])
        assertEquals(1, counts["News"])
        assertEquals(1, counts["Reversal"])

        val filtered = JournalViewModel.filterEntriesByTag(entries, "BREAKOUT")
        assertEquals(2, filtered.size)
        assertEquals(listOf("e1", "e3"), filtered.map { it.id })
    }

    @Test
    fun marketNewsRssParser_extractsHeadlinesSymbolsAndRejectsUnsafeUrls() {
        val sampleRss = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0"><channel>
              <item>
                <title><![CDATA[Reliance &amp; Nifty rally 1.8% after strong earnings - Economic Times]]></title>
                <link>https://economictimes.indiatimes.com/markets/nifty-reliance</link>
                <pubDate>Wed, 25 Nov 2026 09:30:00 GMT</pubDate>
                <description><![CDATA[<p>Reliance Industries led NSE benchmark gains today.</p>]]></description>
              </item>
              <item>
                <title>Malicious link item</title>
                <link>javascript:alert(1)</link>
                <pubDate>Wed, 25 Nov 2026 09:20:00 GMT</pubDate>
              </item>
            </channel></rss>
        """.trimIndent()

        val articles = MarketNewsRepository.parseRssXml(
            xml = sampleRss,
            defaultSource = "Fallback",
            category = MarketNewsCategory.INDIA_MARKETS
        )
        assertEquals(1, articles.size)
        val art = articles.first()
        assertEquals("Reliance & Nifty rally 1.8% after strong earnings", art.title)
        assertEquals("Economic Times", art.source)
        assertTrue(art.relatedSymbols.contains("RELIANCE"))
        assertTrue(art.relatedSymbols.contains("NIFTY"))
        assertFalse(MarketNewsRepository.isSafeHttpUrl("javascript:alert(1)"))
        assertTrue(MarketNewsRepository.isSafeHttpUrl("https://www.coindesk.com/markets"))
    }

    @Test
    fun calendarHeatmap_classifiesPositiveNegativeNeutralAndNoTradeDays() {
        val month = YearMonth.of(2026, 11)
        val day5 = LocalDate.of(2026, 11, 5).atTime(12, 0).atZone(MarketClock.IST_ZONE).toInstant().toEpochMilli()
        val day6 = LocalDate.of(2026, 11, 6).atTime(14, 0).atZone(MarketClock.IST_ZONE).toInstant().toEpochMilli()
        val day7 = LocalDate.of(2026, 11, 7).atTime(11, 0).atZone(MarketClock.IST_ZONE).toInstant().toEpochMilli()

        val entries = listOf(
            sampleJournalEntry("h1", BigDecimal("1200.00"), day5),
            sampleJournalEntry("h2", BigDecimal("-600.00"), day6),
            sampleJournalEntry("h3", BigDecimal("300.00"), day7),
            sampleJournalEntry("h4", BigDecimal("-300.00"), day7 + 60_000L) // Break-even neutral day
        )

        val heatmap = JournalViewModel.buildMonthlyCalendarHeatmap(entries, month)
        assertEquals(30, heatmap.days.size)
        assertEquals(1, heatmap.positiveDays)
        assertEquals(1, heatmap.negativeDays)
        assertEquals(1, heatmap.neutralDays)
        assertEquals(0, heatmap.monthNetPnl.compareTo(BigDecimal("600.00")))

        assertEquals(CalendarDaySentiment.POSITIVE, heatmap.days[4].sentiment)
        assertEquals(1.0f, heatmap.days[4].intensity, 0.01f)
        assertEquals(CalendarDaySentiment.NEGATIVE, heatmap.days[5].sentiment)
        assertEquals(0.5f, heatmap.days[5].intensity, 0.01f)
        assertEquals(CalendarDaySentiment.NEUTRAL, heatmap.days[6].sentiment)
        assertEquals(CalendarDaySentiment.NO_TRADE, heatmap.days[0].sentiment)
    }

    @Test
    fun signalProbabilityCalculator_returnsCalibratedPercentageAndAdaptsToLiveHistory() {
        val highSignal = TradeSignal(
            id = "sig-1",
            symbol = "RELIANCE",
            segment = MarketSegment.NSE,
            strategy = SignalStrategy.CONFLUENCE_70,
            direction = SignalDirection.LONG,
            timeframe = Timeframe.M15,
            entryPrice = BigDecimal("1300.00"),
            stopLoss = BigDecimal("1285.00"),
            target1 = BigDecimal("1330.00"),
            target2 = BigDecimal("1345.00"),
            riskRewardRatio = 2.5,
            confidence = SignalConfidence.HIGH,
            reasons = listOf("Fib 50% retest", "SMA 5/8 cross", "50 EMA support", "RSI divergence"),
            generatedAt = Instant.now(),
            expiresAt = null
        )
        val lowSignal = highSignal.copy(
            id = "sig-2",
            strategy = SignalStrategy.SCALP,
            confidence = SignalConfidence.LOW,
            reasons = listOf("Single EMA tap")
        )

        val highProb = SignalProbabilityCalculator.estimate(highSignal)
        val lowProb = SignalProbabilityCalculator.estimate(lowSignal)
        assertTrue("High-confluence probability ($highProb%) should exceed low ($lowProb%)", highProb > lowProb)
        assertTrue(highProb in 70..88)
        assertTrue(lowProb in 35..55)

        // Bayesian update with 8 consecutive SL losses pulls probability down meaningfully
        val lossRecords = (1..8).map { i ->
            SignalRecord(
                id = "rec-$i",
                symbol = "RELIANCE",
                segment = MarketSegment.NSE,
                instrumentType = InstrumentType.STOCK,
                strategy = SignalStrategy.CONFLUENCE_70,
                direction = SignalDirection.LONG,
                timeframe = Timeframe.M15,
                entryPrice = BigDecimal("1300"),
                stopLoss = BigDecimal("1285"),
                target1 = BigDecimal("1330"),
                target2 = BigDecimal("1345"),
                resolvedPrice = BigDecimal("1285"),
                outcome = SignalOutcome.HIT_SL,
                pnlPercent = -1.15,
                confidence = SignalConfidence.HIGH,
                generatedAtMillis = 1_700_000_000_000L,
                resolvedAtMillis = 1_700_000_600_000L
            )
        }
        val adaptedProb = SignalProbabilityCalculator.estimate(highSignal, lossRecords)
        assertTrue("Historical losses should reduce estimated win probability ($adaptedProb < $highProb)", adaptedProb < highProb)
    }

    @Test
    fun watchlistNewsGrounding_parsesGroundedJsonAndFiltersByWatchlistSymbols() {
        assertEquals("NIFTY", WatchlistNewsGroundingEngine.normalizeUnderlyingSymbol("NIFTY28NOV24500CE"))
        assertEquals("GOLD", WatchlistNewsGroundingEngine.normalizeUnderlyingSymbol("GOLDM"))
        assertEquals("RELIANCE", WatchlistNewsGroundingEngine.normalizeUnderlyingSymbol("RELIANCE-EQ"))
        assertEquals("BTCUSDT", WatchlistNewsGroundingEngine.normalizeUnderlyingSymbol("BTCUSDT"))

        val rawGroundedJson = """
            ```json
            {
              "briefing": "Reliance Industries leads NSE momentum while Bitcoin stabilizes near key resistance.",
              "articles": [
                {
                  "headline": "Reliance Jio reports strong ARPU growth ahead of quarterly results",
                  "summary": "Brokerages upgrade target on stronger telecom cashflows.",
                  "source": "Economic Times",
                  "url": "",
                  "symbols": ["RELIANCE"],
                  "sentiment": "BULLISH",
                  "publishedHint": "1h ago"
                },
                {
                  "headline": "Bitcoin holds key support as institutional ETF inflows rebound",
                  "summary": "Spot BTC flows turned net positive across major desks.",
                  "source": "CoinDesk",
                  "url": "https://www.coindesk.com/markets/btc-etf-inflows",
                  "symbols": ["BTCUSDT"],
                  "sentiment": "BULLISH",
                  "publishedHint": "2h ago"
                }
              ]
            }
            ```
        """.trimIndent()

        val groundedSources = listOf(
            GroundedNewsSource(
                title = "Economic Times - Reliance Jio ARPU update",
                url = "https://economictimes.indiatimes.com/markets/reliance-jio-arpu",
                domain = "economictimes.indiatimes.com"
            ),
            GroundedNewsSource(
                title = "CoinDesk - Bitcoin ETF inflows rebound",
                url = "https://www.coindesk.com/markets/btc-etf-inflows",
                domain = "coindesk.com"
            )
        )

        val parsed = WatchlistNewsGroundingEngine.parseGroundedNewsResponse(
            rawText = rawGroundedJson,
            groundingSources = groundedSources,
            searchQueries = listOf("RELIANCE NSE latest news", "Bitcoin ETF inflows today"),
            watchlist = sampleInstruments
        )

        assertTrue(parsed.briefing!!.contains("Reliance Industries"))
        assertEquals(2, parsed.articles.size)
        assertEquals(2, parsed.searchQueries.size)
        assertEquals(2, parsed.sources.size)

        val relianceArt = parsed.articles.first()
        assertTrue(relianceArt.isSearchGrounded)
        assertEquals(NewsSentiment.BULLISH, relianceArt.sentiment)
        assertEquals("https://economictimes.indiatimes.com/markets/reliance-jio-arpu", relianceArt.url)
        assertTrue(relianceArt.relatedSymbols.contains("RELIANCE"))

        val unrelatedArticle = MarketNewsArticle(
            id = "unrelated-1",
            title = "Unrelated smallcap stock announces dividend",
            summary = "Minor update for a symbol not in watchlist.",
            source = "Wire",
            url = "https://example.com/unrelated",
            category = MarketNewsCategory.INDIA_MARKETS,
            publishedAtMillis = System.currentTimeMillis(),
            relatedSymbols = listOf("SMALLCAPXYZ")
        )

        val allArticles = parsed.articles + unrelatedArticle
        val watchlistOnlyFiltered = WatchlistNewsGroundingEngine.filterArticlesForWatchlist(
            articles = allArticles,
            watchlist = sampleInstruments,
            selectedSymbol = null,
            watchlistOnly = true
        )
        assertEquals(2, watchlistOnlyFiltered.size)
        assertFalse(watchlistOnlyFiltered.any { it.id == "unrelated-1" })

        val relianceOnly = WatchlistNewsGroundingEngine.filterArticlesForWatchlist(
            articles = allArticles,
            watchlist = sampleInstruments,
            selectedSymbol = "RELIANCE",
            watchlistOnly = true
        )
        assertEquals(1, relianceOnly.size)
        assertEquals("RELIANCE", relianceOnly.first().relatedSymbols.first())
    }

    @Test
    fun priceAlert_infersDirectionAndEvaluatesTargetPriceHits() {
        val ltp = BigDecimal("1300.00")
        assertEquals(
            PriceAlertCondition.ABOVE,
            PriceAlertRepository.inferCondition(BigDecimal("1325.50"), ltp)
        )
        assertEquals(
            PriceAlertCondition.ABOVE,
            PriceAlertRepository.inferCondition(BigDecimal("1300.00"), ltp)
        )
        assertEquals(
            PriceAlertCondition.BELOW,
            PriceAlertRepository.inferCondition(BigDecimal("1275.00"), ltp)
        )

        // ABOVE triggers at >= target
        assertFalse(PriceAlertRepository.isConditionMet(PriceAlertCondition.ABOVE, BigDecimal("1325.00"), BigDecimal("1324.95")))
        assertTrue(PriceAlertRepository.isConditionMet(PriceAlertCondition.ABOVE, BigDecimal("1325.00"), BigDecimal("1325.00")))
        assertTrue(PriceAlertRepository.isConditionMet(PriceAlertCondition.ABOVE, BigDecimal("1325.00"), BigDecimal("1326.10")))

        // BELOW triggers at <= target
        assertFalse(PriceAlertRepository.isConditionMet(PriceAlertCondition.BELOW, BigDecimal("1275.00"), BigDecimal("1275.05")))
        assertTrue(PriceAlertRepository.isConditionMet(PriceAlertCondition.BELOW, BigDecimal("1275.00"), BigDecimal("1275.00")))
        assertTrue(PriceAlertRepository.isConditionMet(PriceAlertCondition.BELOW, BigDecimal("1275.00"), BigDecimal("1270.00")))
    }

    @Test
    fun tradingIntensityHeatmap_bucketsByHourAndIdentifiesPeakWindow() {
        fun millisAtIst(day: Int, hour: Int, minute: Int = 15): Long {
            return LocalDate.of(2026, 11, day)
                .atTime(hour, minute)
                .atZone(MarketClock.IST_ZONE)
                .toInstant()
                .toEpochMilli()
        }

        val entries = listOf(
            sampleJournalEntry("i1", BigDecimal("500.00"), millisAtIst(10, 9, 30)).copy(openedAtMillis = millisAtIst(10, 9, 20)),
            sampleJournalEntry("i2", BigDecimal("700.00"), millisAtIst(11, 9, 45)).copy(openedAtMillis = millisAtIst(11, 9, 30)),
            sampleJournalEntry("i3", BigDecimal("-200.00"), millisAtIst(12, 10, 15)).copy(openedAtMillis = millisAtIst(12, 10, 5)),
            sampleJournalEntry("i4", BigDecimal("400.00"), millisAtIst(13, 10, 40)).copy(openedAtMillis = millisAtIst(13, 10, 20)),
            sampleJournalEntry("i5", BigDecimal("300.00"), millisAtIst(14, 10, 55)).copy(openedAtMillis = millisAtIst(14, 10, 35)),
            sampleJournalEntry("i6", BigDecimal("-100.00"), millisAtIst(15, 14, 25)).copy(openedAtMillis = millisAtIst(15, 14, 10))
        )

        val heatmap = JournalRiskAnalytics.buildTradingIntensityHeatmap(entries)
        assertEquals(24, heatmap.hours.size)
        assertEquals(6, heatmap.totalTrades)
        assertEquals(3, heatmap.activeHoursCount)
        assertEquals(3, heatmap.maxTradesInHour)
        assertEquals(10, heatmap.peakHour?.hour)
        assertEquals(4, heatmap.hours[10].level)
        assertEquals(0, heatmap.hours[3].tradeCount)
        assertEquals(0, heatmap.hours[3].level)
        assertEquals(5, heatmap.peakWindowTradeCount) // 5 of 6 trades in the 3-hour morning window
        assertTrue(heatmap.peakWindowSharePercent >= 80)
    }

    @Test
    fun riskManagementScorecard_calculatesCalmarSharpeAvgRiskRewardAndDrawdown() {
        fun dayMillis(day: Int): Long {
            return LocalDate.of(2026, 11, day)
                .atTime(15, 0)
                .atZone(MarketClock.IST_ZONE)
                .toInstant()
                .toEpochMilli()
        }

        // Chronological equity on ₹100,000 base:
        // Day 1: +4,000 -> 104,000 (peak 104,000)
        // Day 2: -2,000 -> 102,000 (DD = 2,000 / 104,000 = 1.923%)
        // Day 3: +3,000 -> 105,000 (new peak 105,000)
        // Day 4: -1,000 -> 104,000 (DD = 1,000 / 105,000 = 0.952%)
        // Day 5: +5,000 -> 109,000 (total net = +9,000)
        val entries = listOf(
            sampleJournalEntry("r1", BigDecimal("4000.00"), dayMillis(1)),
            sampleJournalEntry("r2", BigDecimal("-2000.00"), dayMillis(2)),
            sampleJournalEntry("r3", BigDecimal("3000.00"), dayMillis(3)),
            sampleJournalEntry("r4", BigDecimal("-1000.00"), dayMillis(4)),
            sampleJournalEntry("r5", BigDecimal("5000.00"), dayMillis(5))
        )

        val scorecard = JournalRiskAnalytics.buildRiskManagementScorecard(
            entries = entries,
            startingCapital = BigDecimal("100000.00")
        )

        assertEquals(5, scorecard.totalTrades)
        assertEquals(5, scorecard.distinctTradingDays)
        assertFalse(scorecard.isIntradaySharpeEstimate)

        // Avg Win = (4000 + 3000 + 5000) / 3 = 4000; Avg Loss = (2000 + 1000) / 2 = 1500
        // Avg R:R = 4000 / 1500 = 2.6667
        assertEquals(2.6667, scorecard.avgRiskRewardRatio!!, 0.01)

        // Max Drawdown = 2000 / 104000 * 100 = 1.923%
        assertEquals(0, scorecard.maxDrawdownAmount.compareTo(BigDecimal("2000.00")))
        assertEquals(1.923, scorecard.maxDrawdownPercent, 0.02)

        // Positive Sharpe & Calmar ratios
        assertTrue("Sharpe should be positive (${scorecard.sharpeRatio})", scorecard.sharpeRatio!! > 1.0)
        assertTrue("Calmar should be positive (${scorecard.calmarRatio})", scorecard.calmarRatio!! > 1.0)
        assertEquals(0, scorecard.expectancyPerTrade.compareTo(BigDecimal("1800.00")))
        assertTrue(scorecard.grade in listOf("A+", "A"))
    }

    private fun sampleJournalEntry(
        id: String,
        netPnl: BigDecimal,
        closedAtMillis: Long,
        tags: List<String> = emptyList()
    ): TradeJournalEntry {
        return TradeJournalEntry(
            id = id,
            symbol = "RELIANCE",
            segment = MarketSegment.NSE,
            instrumentType = InstrumentType.STOCK,
            side = OrderSide.BUY,
            productType = ProductType.INTRADAY,
            quantity = 10,
            entryPrice = BigDecimal("1280.00"),
            exitPrice = BigDecimal("1295.00"),
            grossPnl = netPnl,
            charges = BigDecimal.ZERO,
            netPnl = netPnl,
            exitReason = ExitReason.MANUAL_EXIT,
            notes = "",
            openedAtMillis = closedAtMillis - 600_000L,
            closedAtMillis = closedAtMillis,
            lotSize = 1,
            tags = tags
        )
    }
}
