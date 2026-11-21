package com.example.data.feed.live

import com.example.domain.model.MarketSegment

enum class FeedType(
    val label: String,
    val description: String,
    val coveredSegments: Set<MarketSegment>
) {
    SIMULATED(
        label = "Simulated Feed",
        description = "Built-in 1s offline GBM simulator (Default)",
        coveredSegments = setOf(MarketSegment.NSE, MarketSegment.BSE, MarketSegment.CRYPTO, MarketSegment.MCX)
    ),
    BINANCE_LIVE(
        label = "Binance Live",
        description = "Public WebSocket trade stream (Crypto — no API key)",
        coveredSegments = setOf(MarketSegment.CRYPTO)
    ),
    ANGEL_ONE_LIVE(
        label = "Angel One SmartAPI",
        description = "Authenticated SmartStream WS (NSE, BSE, MCX)",
        coveredSegments = setOf(MarketSegment.NSE, MarketSegment.BSE, MarketSegment.MCX)
    ),
    UPSTOX_LIVE(
        label = "Upstox V3 Live",
        description = "Authenticated Market Data Feed V3 (Protobuf)",
        coveredSegments = setOf(MarketSegment.NSE, MarketSegment.BSE, MarketSegment.MCX)
    ),
    KITE_STUB(
        label = "Zerodha Kite (Stub)",
        description = "Optional KiteTicker WebSocket adapter stub",
        coveredSegments = setOf(MarketSegment.NSE, MarketSegment.BSE, MarketSegment.MCX)
    );

    fun supportsSegment(segment: MarketSegment): Boolean = segment in coveredSegments
}

enum class LiveConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    ERROR
}

typealias FeedConnectionStatus = LiveConnectionStatus

enum class SegmentBadgeMode(val label: String) {
    LIVE("LIVE"),
    DELAYED("DELAYED"),
    SIMULATED("SIMULATED"),
    SUSPENDED("FEED LOST")
}

data class SegmentFeedConfig(
    val segment: MarketSegment,
    val feedType: FeedType,
    val isEnabled: Boolean = true
)

data class SegmentFeedStatus(
    val segment: MarketSegment,
    val badge: SegmentBadgeMode,
    val lastTickMillis: Long? = null,
    val detail: String = "",
    val activeFeedType: FeedType = FeedType.SIMULATED,
    val connectionStatus: LiveConnectionStatus = LiveConnectionStatus.DISCONNECTED,
    val isEnabled: Boolean = true
)

data class BrokerFeedSettings(
    val selectedFeed: FeedType = FeedType.SIMULATED,
    val segmentConfigs: Map<MarketSegment, SegmentFeedConfig> = defaultSegmentConfigs(),
    val angelOneApiKey: String = "",
    val angelOneClientCode: String = "",
    val angelOnePin: String = "",
    val angelOneTotpSecret: String = "",
    val upstoxApiKey: String = "",
    val upstoxAccessToken: String = "",
    val kiteApiKey: String = "",
    val kiteAccessToken: String = "",
    val simulatedTickIntervalMs: Long = 1000L
) {
    fun hasAngelOneCredentials(): Boolean =
        angelOneApiKey.isNotBlank() &&
            angelOneClientCode.isNotBlank() &&
            angelOnePin.isNotBlank() &&
            angelOneTotpSecret.isNotBlank()

    fun hasUpstoxCredentials(): Boolean = upstoxAccessToken.isNotBlank()

    fun hasKiteCredentials(): Boolean = kiteApiKey.isNotBlank() && kiteAccessToken.isNotBlank()

    companion object {
        fun defaultSegmentConfigs(hasAngelOneCreds: Boolean = false): Map<MarketSegment, SegmentFeedConfig> {
            val indianFeed = if (hasAngelOneCreds) FeedType.ANGEL_ONE_LIVE else FeedType.SIMULATED
            return mapOf(
                MarketSegment.NSE to SegmentFeedConfig(MarketSegment.NSE, indianFeed, true),
                MarketSegment.BSE to SegmentFeedConfig(MarketSegment.BSE, indianFeed, true),
                MarketSegment.MCX to SegmentFeedConfig(MarketSegment.MCX, indianFeed, true),
                MarketSegment.CRYPTO to SegmentFeedConfig(MarketSegment.CRYPTO, FeedType.BINANCE_LIVE, true)
            )
        }
    }
}
