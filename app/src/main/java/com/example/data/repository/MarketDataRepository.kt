package com.example.data.repository

import com.example.data.feed.live.AngelScripSearchResult
import com.example.data.feed.live.BrokerFeedSettings
import com.example.data.feed.live.FeedType
import com.example.data.feed.live.LiveConnectionStatus
import com.example.data.feed.live.SegmentFeedConfig
import com.example.data.feed.live.SegmentFeedStatus
import com.example.domain.engine.OptionChainSnapshot
import com.example.domain.engine.OptionLegQuote
import com.example.domain.engine.TokenLiveTick
import com.example.domain.model.FeedConnectionState
import com.example.domain.model.Instrument
import com.example.domain.model.InstrumentType
import com.example.domain.model.MarketSegment
import com.example.domain.model.PriceTick
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import java.math.BigDecimal

interface MarketDataRepository {
    val instruments: Flow<List<Instrument>>
    val ticks: Flow<PriceTick>
    val connectionState: StateFlow<FeedConnectionState>

    val segmentConfigs: StateFlow<Map<MarketSegment, SegmentFeedConfig>>
    val activeFeedType: StateFlow<FeedType>
    val liveConnectionStatus: StateFlow<LiveConnectionStatus>
    val liveStatusMessage: StateFlow<String>
    val segmentStatuses: StateFlow<Map<MarketSegment, SegmentFeedStatus>>
    val riskControlsSuspended: StateFlow<Boolean>
    val suspendedSegments: StateFlow<Set<MarketSegment>>
    val suspendedBannerMessage: StateFlow<String>
    val brokerSettings: StateFlow<BrokerFeedSettings>

    fun observeInstrument(symbol: String): Flow<Instrument?>
    fun observeFeedStatus(): Flow<Map<FeedType, LiveConnectionStatus>>
    fun setFeedForSegment(segment: MarketSegment, feedType: FeedType)
    fun enableFeed(feedType: FeedType)
    fun disableFeed(feedType: FeedType)

    suspend fun ensureSeeded()
    suspend fun setActiveFeed(feedType: FeedType)
    suspend fun enableLiveForAllSupportedSegments()
    suspend fun updateBrokerSettings(settings: BrokerFeedSettings)
    suspend fun reconnectActiveFeed()
    suspend fun reconnectSegment(segment: MarketSegment)
    suspend fun nudgePrice(symbol: String, percentDelta: BigDecimal)

    suspend fun searchLiveInstruments(
        query: String,
        segmentFilter: MarketSegment? = null,
        typeFilter: InstrumentType? = null
    ): Result<List<AngelScripSearchResult>> = Result.success(emptyList())

    suspend fun addLiveInstrument(result: AngelScripSearchResult): Result<Instrument> =
        Result.failure(UnsupportedOperationException("Live instrument search unavailable"))

    suspend fun fetchLiveOptionChain(
        underlying: String,
        expiry: String? = null,
        strikeWindow: Int = 12,
        previousOiMap: Map<String, Long> = emptyMap()
    ): Result<OptionChainSnapshot> =
        Result.failure(UnsupportedOperationException("Live Option Chain unavailable"))

    suspend fun addOptionLegToWatchlist(leg: OptionLegQuote): Result<Instrument> =
        Result.failure(UnsupportedOperationException("Cannot add option contract"))

    fun observeOptionTokenTicks(): Flow<TokenLiveTick> = emptyFlow()

    suspend fun refreshScripMaster(): Result<Int> = Result.success(0)

    suspend fun ensureEquityInstrument(
        symbol: String,
        displayName: String,
        ltp: BigDecimal,
        previousClose: BigDecimal,
        dayHigh: BigDecimal,
        dayLow: BigDecimal,
        volume: Long,
        segment: MarketSegment = MarketSegment.NSE
    ) = Unit
}
