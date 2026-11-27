package com.example.ui.navigation

import kotlinx.serialization.Serializable

@Serializable
data object WatchlistRoute

@Serializable
data object HeatmapsRoute

@Serializable
data object SignalsRoute

@Serializable
data class InstrumentDetailRoute(
    val symbol: String,
    val prefillSide: String? = null,
    val prefillEntry: String? = null,
    val prefillSl: String? = null,
    val prefillTarget: String? = null,
    val prefillProduct: String? = null,
    val prefillValidity: String? = null,
    /** Whole units, not lots. The rebalancer sends the share count it worked out. */
    val prefillQuantity: String? = null
)

@Serializable
data object PositionsRoute

@Serializable
data object OrdersRoute

@Serializable
data object JournalRoute

@Serializable
data object FeedSettingsRoute

/** Candlestick chart + indicator / SMC / strategy analysis for one instrument. */
@Serializable
data class ChartRoute(
    val symbol: String,
    val timeframe: String? = null
)

@Serializable
data class OptionChainRoute(
    val initialUnderlying: String = "NIFTY"
)

@Serializable
data object NewsRoute

@Serializable
data class MarketPulseRoute(
    val initialTab: Int = 0
)

@Serializable
data object StrategyCoachesRoute

/** Setup scanner, outcome simulator, tax lots, rebalancer and screener. */
@Serializable
data object StrategyWorkbenchRoute
