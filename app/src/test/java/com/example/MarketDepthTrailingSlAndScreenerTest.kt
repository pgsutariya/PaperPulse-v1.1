package com.example

import com.example.domain.ai.AiStrategyCoachPersona
import com.example.domain.engine.PriceAwareExecutionService
import com.example.domain.market.FundamentalAnalyzer
import com.example.domain.market.HeatmapAssetNode
import com.example.domain.market.HeatmapScaleMetric
import com.example.domain.market.LiveFundamentalDetails
import com.example.domain.market.MaCrossoverSignal
import com.example.domain.market.MarketSentimentCalculator
import com.example.domain.market.MoverCategory
import com.example.domain.market.QuarterlyFinancialResult
import com.example.domain.market.RsiFilterZone
import com.example.domain.market.ScreenerFilterState
import com.example.domain.market.ScreenerPreset
import com.example.domain.market.ScreenerSortBy
import com.example.domain.market.SquarifiedTreemapEngine
import com.example.domain.market.StockFundamentalProfile
import com.example.domain.market.StockMarketSnapshot
import com.example.domain.market.StockScreenerEngine
import com.example.domain.market.TechnicalIndicatorCalculator
import com.example.domain.market.TechnicalSnapshot
import com.example.domain.model.ExitReason
import com.example.domain.model.Instrument
import com.example.domain.model.InstrumentType
import com.example.domain.model.MarketSegment
import com.example.domain.model.OrderSide
import com.example.domain.model.Position
import com.example.domain.model.ProductType
import com.example.domain.model.TradeJournalEntry
import com.example.domain.model.TrailMode
import com.example.domain.model.ValidityType
import com.example.ui.components.TrailingSlStatus
import com.example.ui.components.TrailingSlTelemetryCalculator
import com.example.ui.screens.journal.JournalViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class MarketDepthTrailingSlAndScreenerTest {

    @Test
    fun orderBookSnapshot_hasValid5LevelLadderAndLivePressure() {
        val inst = Instrument(
            symbol = "RELIANCE",
            displayName = "Reliance Industries",
            segment = MarketSegment.NSE,
            type = InstrumentType.STOCK,
            lotSize = 1,
            tickSize = BigDecimal("0.05"),
            ltp = BigDecimal("1284.50"),
            previousClose = BigDecimal("1260.00"),
            dayHigh = BigDecimal("1290.00"),
            dayLow = BigDecimal("1258.00"),
            volume = 8_500_000L,
            lastTickDirection = 1
        )
        val book = PriceAwareExecutionService.buildOrderBookSnapshot(inst)

        assertEquals(5, book.bids.size)
        assertEquals(5, book.asks.size)
        assertTrue(book.bestAsk > book.bestBid)
        assertTrue(book.buyPressurePercent in 1..99)
        assertTrue(book.bids.all { it.ordersCount > 0 && it.quantity > 0 })
        assertTrue(book.asks.all { it.ordersCount > 0 && it.quantity > 0 })
        for (i in 1 until book.bids.size) {
            assertTrue(book.bids[i].price <= book.bids[i - 1].price)
            assertTrue(book.asks[i].price >= book.asks[i - 1].price)
        }
    }

    @Test
    fun trailingSlTelemetry_detectsLongProfitLockedAndNormalizedTrackFractions() {
        val pos = samplePosition(
            side = OrderSide.BUY,
            entry = "100.00",
            activeSl = "105.00",
            bestPrice = "112.00"
        )
        val telemetry = TrailingSlTelemetryCalculator.compute(pos, BigDecimal("110.00"))
        assertNotNull(telemetry)
        telemetry!!

        assertEquals(TrailingSlStatus.PROFIT_LOCKED, telemetry.status)
        assertTrue(telemetry.lockedPerUnit > BigDecimal.ZERO)
        assertTrue(telemetry.cushionAmount.compareTo(BigDecimal("5.00")) == 0)
        assertTrue(telemetry.nextRatchetTriggerPrice > telemetry.peakPrice)
        assertTrue(telemetry.distanceToNextRatchet > BigDecimal.ZERO)
        assertTrue(telemetry.slFraction in 0f..1f)
        assertTrue(telemetry.entryFraction in 0f..1f)
        assertTrue(telemetry.ltpFraction in 0f..1f)
        assertTrue(telemetry.peakFraction in 0f..1f)
        assertTrue(telemetry.slFraction > telemetry.entryFraction)
        assertTrue(telemetry.ltpFraction > telemetry.slFraction)
    }

    @Test
    fun trailingSlTelemetry_detectsShortProfitLockedAndNearTrigger() {
        val shortLocked = samplePosition(
            side = OrderSide.SELL,
            entry = "200.00",
            activeSl = "194.00",
            bestPrice = "188.00"
        )
        val lockedTelemetry = TrailingSlTelemetryCalculator.compute(shortLocked, BigDecimal("190.00"))!!
        assertEquals(TrailingSlStatus.PROFIT_LOCKED, lockedTelemetry.status)
        assertTrue(lockedTelemetry.lockedPerUnit.compareTo(BigDecimal("6.00")) == 0)
        assertTrue(lockedTelemetry.nextRatchetTriggerPrice < lockedTelemetry.peakPrice)
        assertTrue(lockedTelemetry.distanceToNextRatchet > BigDecimal.ZERO)

        val nearTrigger = samplePosition(
            side = OrderSide.BUY,
            entry = "100.00",
            activeSl = "99.80",
            bestPrice = "101.00"
        )
        val nearTelemetry = TrailingSlTelemetryCalculator.compute(nearTrigger, BigDecimal("100.00"))!!
        assertEquals(TrailingSlStatus.NEAR_TRIGGER, nearTelemetry.status)
    }

    @Test
    fun stockScreenerAndMovers_rankAndFilterCorrectly() {
        val gainer = sampleStock("GAIN", pe = 16.0, roe = 26.0, de = 0.1, div = 2.5, ltp = 108.0, prev = 100.0, high52 = 109.0, low52 = 70.0, vol = 9_000_000L)
        val loser = sampleStock("LOSE", pe = 48.0, roe = 9.0, de = 1.5, div = 0.2, ltp = 82.0, prev = 90.0, high52 = 140.0, low52 = 80.0, vol = 2_000_000L)
        val highVol = sampleStock("VOLX", pe = 20.0, roe = 21.0, de = 0.3, div = 1.8, ltp = 202.0, prev = 200.0, high52 = 240.0, low52 = 150.0, vol = 25_000_000L)
        val universe = listOf(gainer, loser, highVol)

        assertEquals("GAIN", StockScreenerEngine.computeMovers(universe, MoverCategory.TOP_GAINERS).first().symbol)
        assertEquals("LOSE", StockScreenerEngine.computeMovers(universe, MoverCategory.TOP_LOSERS).first().symbol)
        assertEquals("GAIN", StockScreenerEngine.computeMovers(universe, MoverCategory.WEEK_52_HIGH).first().symbol)
        assertEquals("LOSE", StockScreenerEngine.computeMovers(universe, MoverCategory.WEEK_52_LOW).first().symbol)
        assertEquals("VOLX", StockScreenerEngine.computeMovers(universe, MoverCategory.TOP_VOLUME).first().symbol)

        val qualityMatches = StockScreenerEngine.filterAndSort(
            universe,
            ScreenerFilterState(
                preset = ScreenerPreset.HIGH_ROE_QUALITY,
                maxPe = 22.0,
                maxDebtToEquity = 0.4,
                sortBy = ScreenerSortBy.ROE_DESC
            )
        )
        assertEquals(listOf("GAIN", "VOLX"), qualityMatches.map { it.symbol })
        assertTrue(gainer.scorecard.overallScore > loser.scorecard.overallScore)

        val sectorMap = StockScreenerEngine.computeSectorHeatmap(universe)
        assertEquals(1, sectorMap.size)
        assertEquals("IT", sectorMap.first().sector)
        assertEquals(3, sectorMap.first().stockCount)
        assertEquals(2, sectorMap.first().advancingCount)
        assertEquals(1, sectorMap.first().decliningCount)
        assertEquals("GAIN", sectorMap.first().topMoverSymbol)
    }

    @Test
    fun technicalIndicatorCalculator_identifiesUptrendGoldenCrossAndDowntrendDeathCross() {
        val uptrendCloses = (1..220).map { 100.0 + it * 0.9 }
        val bullish = TechnicalIndicatorCalculator.fromCloses(uptrendCloses, 298.0, 297.1)
        assertTrue("Expected RSI > 65 in steady uptrend, got ${bullish.rsi14}", bullish.rsi14 > 65.0)
        assertEquals(MaCrossoverSignal.GOLDEN_CROSS, bullish.maCrossover)
        assertTrue(bullish.aboveSma200)

        val downtrendCloses = (1..220).map { 400.0 - it * 0.9 }
        val bearish = TechnicalIndicatorCalculator.fromCloses(downtrendCloses, 202.0, 202.9)
        assertTrue("Expected RSI < 35 in steady downtrend, got ${bearish.rsi14}", bearish.rsi14 < 35.0)
        assertEquals(MaCrossoverSignal.DEATH_CROSS, bearish.maCrossover)
        assertTrue(!bearish.aboveSma200)
    }

    @Test
    fun stockScreener_filtersByTechnicalsSortsBidirectionallyAndPaginates() {
        val stocks = (1..11).map { idx ->
            val rsi = 25.0 + idx * 5.0 // 30, 35, 40, ..., 80
            val ma = if (idx % 2 == 0) MaCrossoverSignal.GOLDEN_CROSS else MaCrossoverSignal.BEARISH_CROSSOVER
            sampleStock(
                symbol = "STK${idx.toString().padStart(2, '0')}",
                pe = 10.0 + idx * 2.0,
                roe = 12.0 + idx * 1.5,
                de = 0.2,
                div = 1.5,
                ltp = 100.0 + idx * 10.0,
                prev = 100.0 + idx * 9.5,
                high52 = 250.0,
                low52 = 90.0,
                vol = 1_000_000L * idx
            ).copy(
                technical = TechnicalSnapshot(
                    rsi14 = rsi,
                    ema20 = 120.0,
                    sma50 = if (ma == MaCrossoverSignal.GOLDEN_CROSS) 110.0 else 130.0,
                    sma200 = 100.0,
                    maCrossover = ma,
                    aboveSma200 = true
                )
            )
        }

        // Filter by RSI Oversold (<= 35) -> STK01 (30), STK02 (35)
        val oversold = StockScreenerEngine.filterSortAndPaginate(
            stocks,
            ScreenerFilterState(rsiFilter = RsiFilterZone.OVERSOLD, sortBy = ScreenerSortBy.RSI, sortAscending = true)
        )
        assertEquals(listOf("STK01", "STK02"), oversold.pageItems.map { it.symbol })

        // Filter by Golden Cross -> even indices (2, 4, 6, 8, 10)
        val golden = StockScreenerEngine.filterSortAndPaginate(
            stocks,
            ScreenerFilterState(maCrossoverFilter = MaCrossoverSignal.GOLDEN_CROSS, sortBy = ScreenerSortBy.SYMBOL, sortAscending = true)
        )
        assertEquals(listOf("STK02", "STK04", "STK06", "STK08", "STK10"), golden.pageItems.map { it.symbol })

        // Pagination: 11 stocks, pageSize = 4 -> 3 pages (4, 4, 3), sorted descending by RSI
        val page2 = StockScreenerEngine.filterSortAndPaginate(
            stocks,
            ScreenerFilterState(sortBy = ScreenerSortBy.RSI, sortAscending = false, currentPage = 2, pageSize = 4)
        )
        assertEquals(11, page2.allMatchingCount)
        assertEquals(3, page2.totalPages)
        assertEquals(2, page2.currentPage)
        assertEquals(5, page2.startIndex)
        assertEquals(8, page2.endIndex)
        assertEquals(listOf("STK07", "STK06", "STK05", "STK04"), page2.pageItems.map { it.symbol })

        // Out-of-bounds page clamps cleanly to last page
        val clamped = StockScreenerEngine.filterSortAndPaginate(
            stocks,
            ScreenerFilterState(sortBy = ScreenerSortBy.RSI, sortAscending = false, currentPage = 99, pageSize = 4)
        )
        assertEquals(3, clamped.currentPage)
        assertEquals(3, clamped.pageItems.size)
        assertEquals(9, clamped.startIndex)
        assertEquals(11, clamped.endIndex)
    }

    @Test
    fun squarifiedTreemapEngine_scalesProportionallyAndFillsUnitBounds() {
        val nodes = listOf(
            HeatmapAssetNode("CRYPTO:BTCUSDT", "BTCUSDT", "BTC", "Bitcoin", "Layer 1", true, "$96,400", 96400.0, 2.5, 1_900_000_000_000.0, 48_000_000_000.0, "$1.90T", "$48B"),
            HeatmapAssetNode("CRYPTO:ETHUSDT", "ETHUSDT", "ETH", "Ethereum", "Layer 1", true, "$3,400", 3400.0, 1.8, 410_000_000_000.0, 24_000_000_000.0, "$410B", "$24B"),
            HeatmapAssetNode("NSE:RELIANCE", "RELIANCE", "RELIANCE", "Reliance", "Energy", false, "₹1,284", 1284.0, 1.2, 206_000_000_000.0, 130_000_000.0, "₹17.4L Cr", "8.4M sh"),
            HeatmapAssetNode("CRYPTO:SOLUSDT", "SOLUSDT", "SOL", "Solana", "Layer 1", true, "$238", 238.0, 4.7, 113_000_000_000.0, 6_800_000_000.0, "$113B", "$6.8B"),
            HeatmapAssetNode("NSE:INFY", "INFY", "INFY", "Infosys", "IT", false, "₹1,888", 1888.0, -0.9, 92_000_000_000.0, 144_000_000.0, "₹7.8L Cr", "6.4M sh")
        )

        val capRects = SquarifiedTreemapEngine.layout(nodes, HeatmapScaleMetric.MARKET_CAP, containerAspectRatio = 1.0f)
        assertEquals(nodes.size, capRects.size)
        val totalArea = capRects.sumOf { (it.width * it.height).toDouble() }
        assertTrue("Expected total treemap area near 1.0, got $totalArea", totalArea in 0.96..1.04)

        capRects.forEach { r ->
            assertTrue(r.left in 0f..1f)
            assertTrue(r.top in 0f..1f)
            assertTrue(r.width > 0f && r.height > 0f)
            assertTrue(r.left + r.width <= 1.01f)
            assertTrue(r.top + r.height <= 1.01f)
        }

        val btcArea = capRects.first { it.node.symbol == "BTCUSDT" }.let { it.width * it.height }
        val infyArea = capRects.first { it.node.symbol == "INFY" }.let { it.width * it.height }
        assertTrue("BTC area ($btcArea) should strictly exceed INFY area ($infyArea)", btcArea > infyArea)

        // Volume scaling should give ETH strictly larger area than RELIANCE
        val volRects = SquarifiedTreemapEngine.layout(nodes, HeatmapScaleMetric.VOLUME, containerAspectRatio = 1.2f)
        val ethVolArea = volRects.first { it.node.symbol == "ETHUSDT" }.let { it.width * it.height }
        val relVolArea = volRects.first { it.node.symbol == "RELIANCE" }.let { it.width * it.height }
        assertTrue("ETH volume area ($ethVolArea) should strictly exceed RELIANCE ($relVolArea)", ethVolArea > relVolArea)
    }

    @Test
    fun aiStrategyCoachPersonas_coverAllSpecialtiesWithGroundedPrompts() {
        val personas = AiStrategyCoachPersona.entries
        assertEquals(5, personas.size)
        personas.forEach { persona ->
            assertTrue(persona.title.isNotBlank())
            assertTrue(persona.shortTitle.isNotBlank())
            assertTrue(persona.specialtyBadge.isNotBlank())
            assertTrue(persona.focusAreas.size >= 3)
            assertTrue(persona.quickPrompts.size >= 3)
            assertTrue(persona.personaInstruction.length >= 40)
        }
    }

    @Test
    fun liveFundamentalDetails_retainsQuarterlyResultsAndMetrics() {
        val quarters = listOf(
            QuarterlyFinancialResult("Q3 FY26", 248000.0, 19840.0, 18.2, 29.3, 12.4),
            QuarterlyFinancialResult("Q2 FY26", 239500.0, 18920.0, 17.8, 28.0, 10.8),
            QuarterlyFinancialResult("Q1 FY26", 235100.0, 18400.0, 17.5, 27.2, 9.6),
            QuarterlyFinancialResult("Q4 FY25", 229800.0, 17950.0, 17.1, 26.5, 8.9)
        )
        val details = LiveFundamentalDetails(
            symbol = "RELIANCE",
            livePeRatio = 24.5,
            marketCapCr = 17_40_000.0,
            dividendYieldPercent = 0.35,
            pbRatio = 2.1,
            epsTtm = 52.4,
            roePercent = 9.8,
            debtToEquity = 0.41,
            quarterlyResults = quarters,
            isLiveFetched = true,
            fetchedAtLabel = "Yahoo Finance Live • 10:15 IST"
        )
        assertEquals(4, details.quarterlyResults.size)
        assertTrue(details.quarterlyResults.first().revenueCr > details.quarterlyResults.last().revenueCr)
        assertTrue(details.isLiveFetched)
        assertEquals("RELIANCE", details.symbol)
    }

    @Test
    fun marketSentimentCalculator_scoresBullishAndBearishFromPeAndVolume() {
        val bullishUniverse = listOf(
            sampleStock("BULL1", pe = 17.5, roe = 24.0, de = 0.2, div = 2.0, ltp = 105.0, prev = 100.0, high52 = 110.0, low52 = 80.0, vol = 6_000_000L),
            sampleStock("BULL2", pe = 19.0, roe = 22.0, de = 0.3, div = 1.8, ltp = 210.0, prev = 200.0, high52 = 220.0, low52 = 160.0, vol = 5_400_000L),
            sampleStock("BULL3", pe = 20.5, roe = 20.0, de = 0.2, div = 1.5, ltp = 306.0, prev = 300.0, high52 = 315.0, low52 = 240.0, vol = 4_800_000L)
        )
        val bullSentiment = MarketSentimentCalculator.compute(bullishUniverse)
        assertTrue("Expected bullish score >= 70, got ${bullSentiment.score}", bullSentiment.score >= 70)
        assertTrue(bullSentiment.regime.isBullish)
        assertEquals(100, bullSentiment.upVolumeRatioPercent)
        assertTrue(bullSentiment.weightedPeRatio in 17.0..21.0)
        assertTrue(bullSentiment.avgRelativeVolume > 1.5)

        val bearishUniverse = listOf(
            sampleStock("BEAR1", pe = 42.0, roe = 10.0, de = 1.4, div = 0.2, ltp = 92.0, prev = 100.0, high52 = 130.0, low52 = 90.0, vol = 6_500_000L),
            sampleStock("BEAR2", pe = 38.0, roe = 11.0, de = 1.2, div = 0.3, ltp = 186.0, prev = 200.0, high52 = 250.0, low52 = 180.0, vol = 5_800_000L),
            sampleStock("BEAR3", pe = 46.0, roe = 9.0, de = 1.6, div = 0.1, ltp = 285.0, prev = 300.0, high52 = 340.0, low52 = 280.0, vol = 5_200_000L)
        )
        val bearSentiment = MarketSentimentCalculator.compute(bearishUniverse)
        assertTrue("Expected bearish score <= 30, got ${bearSentiment.score}", bearSentiment.score <= 30)
        assertTrue(bearSentiment.regime.isBearish)
        assertEquals(0, bearSentiment.upVolumeRatioPercent)
        assertTrue(bearSentiment.weightedPeRatio > 38.0)
    }

    @Test
    fun strategyTagging_normalizesFiltersAndComputesPerStylePerformance() {
        val entries = listOf(
            sampleJournalEntry("j1", "RELIANCE", "2500.00", listOf("#scalp", "Breakout")),
            sampleJournalEntry("j2", "TCS", "-1000.00", listOf("Scalp")),
            sampleJournalEntry("j3", "INFY", "4000.00", listOf("#TrendFollow")),
            sampleJournalEntry("j4", "HDFCBANK", "1500.00", listOf("trendfollow", "Breakout"))
        )

        assertEquals("Scalp", JournalViewModel.normalizeTag("#scalp"))
        assertEquals("TrendFollow", JournalViewModel.normalizeTag("  #trendfollow  "))

        val scalpFiltered = JournalViewModel.filterEntriesByTag(entries, "#Scalp")
        assertEquals(listOf("j1", "j2"), scalpFiltered.map { it.id })

        val trendFiltered = JournalViewModel.filterEntriesByTag(entries, "TrendFollow")
        assertEquals(listOf("j3", "j4"), trendFiltered.map { it.id })

        val breakdown = JournalViewModel.computeStrategyTagPerformance(entries)
        val scalpPerf = breakdown.first { it.tag == "Scalp" }
        assertEquals(2, scalpPerf.totalTrades)
        assertEquals(1, scalpPerf.wins)
        assertEquals(1, scalpPerf.losses)
        assertEquals(50.0, scalpPerf.winRatePercent, 0.01)
        assertEquals(0, scalpPerf.indiaNetPnl.compareTo(BigDecimal("1500.00")))
        assertEquals(2.5, scalpPerf.profitFactor!!, 0.01)

        val trendPerf = breakdown.first { it.tag == "TrendFollow" }
        assertEquals(2, trendPerf.totalTrades)
        assertEquals(100.0, trendPerf.winRatePercent, 0.01)
        assertEquals(0, trendPerf.indiaNetPnl.compareTo(BigDecimal("5500.00")))
    }

    private fun sampleJournalEntry(
        id: String,
        symbol: String,
        netPnl: String,
        tags: List<String>
    ): TradeJournalEntry = TradeJournalEntry(
        id = id,
        symbol = symbol,
        segment = MarketSegment.NSE,
        instrumentType = InstrumentType.STOCK,
        side = OrderSide.BUY,
        productType = ProductType.INTRADAY,
        quantity = 10,
        entryPrice = BigDecimal("1000.00"),
        exitPrice = BigDecimal("1100.00"),
        grossPnl = BigDecimal(netPnl),
        charges = BigDecimal("40.00"),
        netPnl = BigDecimal(netPnl),
        exitReason = ExitReason.TARGET_HIT,
        notes = "",
        openedAtMillis = 1_000L,
        closedAtMillis = 2_000L,
        tags = tags
    )

    private fun samplePosition(
        side: OrderSide,
        entry: String,
        activeSl: String,
        bestPrice: String
    ): Position = Position(
        id = "pos-1",
        symbol = "RELIANCE",
        displayName = "Reliance",
        segment = MarketSegment.NSE,
        instrumentType = InstrumentType.STOCK,
        side = side,
        productType = ProductType.INTRADAY,
        quantity = 10,
        averageEntryPrice = BigDecimal(entry),
        activeSlPrice = BigDecimal(activeSl),
        activeTargetPrice = null,
        slOrderId = "sl-1",
        targetOrderId = null,
        ocoGroupId = null,
        isTslEnabled = true,
        tslTrailMode = TrailMode.PERCENT,
        tslTrailValue = BigDecimal("1.0"),
        tslBestPrice = BigDecimal(bestPrice),
        isTrailingTargetEnabled = false,
        trailingTargetTrailMode = TrailMode.PERCENT,
        trailingTargetTrailValue = BigDecimal.ZERO,
        trailingTargetActivated = false,
        trailingTargetPeakPrice = null,
        trailingTargetExitTrigger = null,
        validityType = ValidityType.DAY,
        gttDays = 7,
        expiresAtMillis = Long.MAX_VALUE,
        openedAtMillis = 0L,
        updatedAtMillis = 0L
    )

    private fun sampleStock(
        symbol: String,
        pe: Double,
        roe: Double,
        de: Double,
        div: Double,
        ltp: Double,
        prev: Double,
        high52: Double,
        low52: Double,
        vol: Long
    ): StockMarketSnapshot {
        val eps = ltp / pe
        val profile = StockFundamentalProfile(
            symbol = symbol,
            companyName = "$symbol Corp",
            sector = "IT",
            marketCapCr = 120_000.0,
            peRatio = pe,
            pbRatio = 3.2,
            evEbitda = 14.0,
            roePercent = roe,
            rocePercent = roe + 2.0,
            dividendYieldPercent = div,
            debtToEquity = de,
            currentRatio = 1.9,
            operatingMarginPercent = 24.0,
            netProfitMarginPercent = 18.0,
            revenueGrowthYoyPercent = 16.0,
            profitGrowthYoyPercent = 20.0,
            eps = eps,
            promoterHoldingPercent = 52.0,
            fiiHoldingPercent = 20.0,
            diiHoldingPercent = 18.0,
            refPrice = ltp,
            refPrevClose = prev,
            ref52WeekHigh = high52,
            ref52WeekLow = low52,
            avgVolume20D = 3_000_000L
        )
        return StockMarketSnapshot(
            profile = profile,
            ltp = BigDecimal.valueOf(ltp),
            previousClose = BigDecimal.valueOf(prev),
            dayHigh = BigDecimal.valueOf(maxOf(ltp, prev)),
            dayLow = BigDecimal.valueOf(minOf(ltp, prev)),
            volume = vol,
            fiftyTwoWeekHigh = BigDecimal.valueOf(high52),
            fiftyTwoWeekLow = BigDecimal.valueOf(low52),
            isLiveQuote = true,
            scorecard = FundamentalAnalyzer.score(profile, ltp)
        )
    }
}
