package com.example.domain.strategy

import java.math.BigDecimal
import java.time.LocalTime

/**
 * Trading rule-book shared by the ported trader skills.
 *
 * Ported from the `trader-skills-india` / `day-trader-skill` / `trader-skill` Claude skill
 * bundles (MIT, Jake Schincariol and Ankur Goel). The Python packs keep these in a
 * `trader/config.json`; here they are a plain value type so the engines stay pure and
 * testable, and the defaults match the India pack because PaperPulse is an NSE/BSE/MCX
 * terminal.
 *
 * Every amount is in rupees. Percentages are whole numbers (10 means 10%).
 */
data class TraderRules(
    val budget: BigDecimal = BigDecimal("100000"),
    val cashBufferPct: BigDecimal = BigDecimal("10"),
    val maxPositionPct: BigDecimal = BigDecimal("75"),
    val maxOrderInr: BigDecimal? = BigDecimal("100000"),
    val minOrderInr: BigDecimal = BigDecimal("500"),
    val maxLossPerTradeInr: BigDecimal? = BigDecimal("500"),
    val maxDailyLossInr: BigDecimal? = BigDecimal("1500"),
    val maxTradesPerDay: Int = 3,
    val maxOrdersPerDay: Int = 10,
    val minRewardRisk: BigDecimal = BigDecimal("1.0"),
    val requireStop: Boolean = true,
    val noEntriesBefore: LocalTime = LocalTime.of(9, 35),
    val noEntriesAfter: LocalTime = LocalTime.of(15, 0),
    val flatBy: LocalTime = LocalTime.of(15, 10),
    val blocked: Set<String> = emptySet(),
    val allowShort: Boolean = false,
    val allowOptions: Boolean = false,
    val allowMargin: Boolean = false
) {
    /** Cash held back from [budget] and never deployed. */
    val cashBuffer: BigDecimal
        get() = budget.multiply(cashBufferPct)
            .divide(HUNDRED, 2, java.math.RoundingMode.HALF_UP)

    /** The most that may sit in any one symbol. */
    val positionCap: BigDecimal
        get() = budget.multiply(maxPositionPct)
            .divide(HUNDRED, 2, java.math.RoundingMode.HALF_UP)

    companion object {
        val HUNDRED: BigDecimal = BigDecimal("100")

        /**
         * Round-trip cost of an NSE intraday equity trade as a percentage of turnover
         * (brokerage, STT, exchange and SEBI fees, GST). A target closer than this cannot
         * pay for itself, so [ExitPlanner] refuses it.
         */
        val ROUND_TRIP_COST_PCT: BigDecimal = BigDecimal("0.12")

        /**
         * A stop nearer than this fraction of the price is inside the spread and normal
         * noise would take it out.
         */
        val MIN_RISK_PCT: BigDecimal = BigDecimal("0.05")
    }
}
