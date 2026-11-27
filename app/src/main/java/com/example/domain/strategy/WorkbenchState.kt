package com.example.domain.strategy

import kotlinx.serialization.Serializable

/**
 * The part of the strategy workbench the trader builds up by hand and expects to find
 * again: the tax-lot book, the rebalance targets and the screen's rules.
 *
 * Kept as JSON in DataStore rather than Room. It is small, it is read and written whole,
 * and it needs no schema migration when a field is added - the decoder ignores keys it
 * does not know and falls back to an empty workbench if the blob is ever unreadable.
 *
 * Money is stored as a string so a BigDecimal survives the round trip without being
 * forced through a Double on the way.
 */
@Serializable
data class WorkbenchState(
    val taxLots: List<SavedTaxLot> = emptyList(),
    val rebalanceTargets: List<SavedTarget> = emptyList(),
    val screenRules: List<SavedScreenRule> = defaultScreenRules()
)

@Serializable
data class SavedTaxLot(
    val id: String,
    val symbol: String,
    /** ISO `yyyy-MM-dd`. */
    val acquired: String,
    val shares: Int,
    val costPerShare: String
)

@Serializable
data class SavedTarget(
    val symbol: String,
    /** Relative weight; the rebalancer normalises these itself. */
    val weight: String
)

@Serializable
data class SavedScreenRule(
    val metric: String,
    /** One of [MetricScreener.Op]'s symbols. */
    val op: String,
    /** One value, two for `between`, or many for `in` / `not in`. */
    val values: List<String>
)

/**
 * A screen that starts empty tells the trader nothing, so the workbench opens with three
 * rules that describe an ordinary momentum filter and can be edited or deleted.
 */
fun defaultScreenRules(): List<SavedScreenRule> = listOf(
    SavedScreenRule("rsi14", "between", listOf("40", "70")),
    SavedScreenRule("vsMa20Pct", ">", listOf("0")),
    SavedScreenRule("changePct", ">", listOf("0"))
)

/** Metrics the screener can test, all computed from this app's own price data. */
enum class ScreenMetric(val key: String, val label: String, val numeric: Boolean) {
    LTP("ltp", "Last price", true),
    CHANGE_PCT("changePct", "Day change %", true),
    RSI_14("rsi14", "RSI 14 (daily)", true),
    VS_HIGH_20("vsHigh20Pct", "% vs 20-day high", true),
    VS_MA_20("vsMa20Pct", "% vs 20-day average", true),
    RANGE_5("range5Pct", "5-day range %", true),
    RUN_10("runPct", "10-day run %", true),
    SEGMENT("segment", "Segment", false);

    companion object {
        fun byKey(key: String): ScreenMetric? = entries.firstOrNull { it.key == key }
    }
}
