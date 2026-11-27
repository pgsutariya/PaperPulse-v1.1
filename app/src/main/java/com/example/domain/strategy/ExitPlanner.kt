package com.example.domain.strategy

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Exit Planner - ported from the `trader-in-exit` skill.
 *
 * Fixes the stop and the target *before* the entry, so the trade has a known worst case.
 * Pure: give it an entry, a stop rule and a target rule and it returns the plan or a
 * refusal explaining which rule it broke.
 */
object ExitPlanner {

    /** Where the stop goes. */
    sealed interface StopRule {
        /** Midpoint of the opening range - the pack's default. */
        data object OpeningRangeMid : StopRule

        /** Low of the opening range. */
        data object OpeningRangeLow : StopRule

        /** An explicit price. */
        data class Price(val value: BigDecimal) : StopRule

        /** A percentage under the entry. */
        data class Percent(val value: BigDecimal) : StopRule

        /** A multiple of ATR under the entry. */
        data class AtrMultiple(val multiple: BigDecimal) : StopRule
    }

    /** Where the target goes. */
    sealed interface TargetRule {
        /** A multiple of the risk (2R = twice the distance to the stop). */
        data class RMultiple(val multiple: BigDecimal) : TargetRule

        /** An explicit price. */
        data class Price(val value: BigDecimal) : TargetRule
    }

    data class Plan(
        val entry: BigDecimal,
        val stop: BigDecimal,
        val stopLabel: String,
        val stopDetail: String,
        val target: BigDecimal,
        val targetLabel: String,
        val riskPerShare: BigDecimal,
        val rewardRisk: BigDecimal
    )

    /** Thrown with a message meant to be shown to the trader as-is. */
    class PlanRefused(message: String) : IllegalArgumentException(message)

    private data class ResolvedStop(val raw: BigDecimal, val label: String, val detail: String)

    /**
     * Build the plan, or throw [PlanRefused] naming the rule that failed.
     *
     * @param openingRangeHigh/[openingRangeLow] required by the opening-range stop rules.
     * @param atr required by [StopRule.AtrMultiple]; the ATR over the bars up to the entry.
     * @param tickSize the instrument's tick; the stop is floored and the target rounded to it.
     */
    fun plan(
        entry: BigDecimal,
        stopRule: StopRule,
        targetRule: TargetRule,
        openingRangeHigh: BigDecimal? = null,
        openingRangeLow: BigDecimal? = null,
        atr: BigDecimal? = null,
        atrBars: Int = 14,
        tickSize: BigDecimal = BigDecimal("0.05")
    ): Plan {
        val e = entry.setScale(2, RoundingMode.HALF_UP)
        if (e <= BigDecimal.ZERO) throw PlanRefused("the entry price must be above Rs 0")

        // Resolved as a single expression so every rule must produce all three values.
        val resolved: ResolvedStop = when (stopRule) {
            is StopRule.OpeningRangeMid, is StopRule.OpeningRangeLow -> {
                if (openingRangeHigh == null || openingRangeLow == null) {
                    throw PlanRefused("an opening-range stop needs the opening range high and low")
                }
                val oh = openingRangeHigh.setScale(2, RoundingMode.HALF_UP)
                val ol = openingRangeLow.setScale(2, RoundingMode.HALF_UP)
                if (oh <= ol) {
                    throw PlanRefused("the opening-range high ${money(oh)} must be above its low ${money(ol)}")
                }
                if (stopRule is StopRule.OpeningRangeMid) {
                    ResolvedStop(
                        raw = oh.add(ol).divide(BigDecimal("2"), 6, RoundingMode.HALF_UP),
                        label = "opening-range midpoint",
                        detail = "(${money(oh)} + ${money(ol)}) / 2"
                    )
                } else {
                    ResolvedStop(raw = ol, label = "opening-range low", detail = "")
                }
            }

            is StopRule.Price -> ResolvedStop(raw = stopRule.value, label = "your price", detail = "")

            is StopRule.Percent -> {
                if (stopRule.value <= BigDecimal.ZERO || stopRule.value >= TraderRules.HUNDRED) {
                    throw PlanRefused("a percent stop needs a value between 0 and 100")
                }
                val factor = TraderRules.HUNDRED.subtract(stopRule.value)
                    .divide(TraderRules.HUNDRED, 8, RoundingMode.HALF_UP)
                ResolvedStop(
                    raw = e.multiply(factor),
                    label = "${plain(stopRule.value)}% under the entry",
                    detail = "${money(e)} x (1 - ${plain(stopRule.value)}%)"
                )
            }

            is StopRule.AtrMultiple -> {
                if (atr == null) throw PlanRefused("an ATR stop needs the session's bars")
                if (stopRule.multiple <= BigDecimal.ZERO) {
                    throw PlanRefused("an ATR stop needs a multiple above 0")
                }
                ResolvedStop(
                    raw = e.subtract(stopRule.multiple.multiply(atr)),
                    label = "${plain(stopRule.multiple)} x ATR($atrBars) under the entry",
                    detail = "ATR Rs ${atr.setScale(4, RoundingMode.HALF_UP).toPlainString()} over $atrBars bars"
                )
            }
        }
        val rawStop = resolved.raw
        val stopLabel = resolved.label
        var detail = resolved.detail

        // A long's stop is floored to the tick so rounding can never move it nearer the entry.
        val stop = floorToTick(rawStop, tickSize)
        if (stop.compareTo(rawStop.setScale(2, RoundingMode.HALF_UP)) != 0) {
            detail = if (detail.isEmpty()) "rounded down to the tick" else "$detail, rounded down to the tick"
        }
        if (stop >= e) {
            throw PlanRefused(
                "the stop ${money(stop)} ($stopLabel) is at or above the entry ${money(e)}: " +
                    "a long's stop goes under the entry"
            )
        }
        if (stop <= BigDecimal.ZERO) {
            throw PlanRefused("the stop works out to ${money(stop)}: pick a stop above Rs 0")
        }

        val risk = e.subtract(stop)
        val floorRisk = e.multiply(TraderRules.MIN_RISK_PCT)
            .divide(TraderRules.HUNDRED, 6, RoundingMode.HALF_UP)
        if (risk < floorRisk) {
            throw PlanRefused(
                "the stop is ${money(risk)} under the entry, less than ${plain(TraderRules.MIN_RISK_PCT)}% " +
                    "of the price (${money(floorRisk)} on ${money(e)}): too tight to survive the spread. Widen it."
            )
        }

        val target: BigDecimal
        var targetLabel: String
        when (targetRule) {
            is TargetRule.RMultiple -> {
                if (targetRule.multiple <= BigDecimal.ZERO) {
                    throw PlanRefused("the target needs an R multiple above 0, like 1R or 2R")
                }
                target = roundToTick(e.add(targetRule.multiple.multiply(risk)), tickSize)
                targetLabel = "${plain(targetRule.multiple)}R (${plain(targetRule.multiple)} times the risk)"
            }

            is TargetRule.Price -> {
                target = roundToTick(targetRule.value, tickSize)
                targetLabel = "your price"
            }
        }

        if (target <= e) {
            throw PlanRefused(
                "the target ${money(target)} is at or below the entry ${money(e)}: a long's target goes above it"
            )
        }
        val gainPct = target.subtract(e).multiply(TraderRules.HUNDRED)
            .divide(e, 4, RoundingMode.HALF_UP)
        if (gainPct <= TraderRules.ROUND_TRIP_COST_PCT) {
            throw PlanRefused(
                "the target ${money(target)} is only ${plain(gainPct.setScale(2, RoundingMode.HALF_UP))}% over " +
                    "the entry ${money(e)}, not past the ~${plain(TraderRules.ROUND_TRIP_COST_PCT)}% round-trip " +
                    "cost (brokerage, STT, exchange and SEBI fees, GST): it cannot pay. Raise it."
            )
        }

        val rewardRisk = target.subtract(e).divide(risk, 2, RoundingMode.HALF_UP)
        if (targetRule is TargetRule.Price) {
            targetLabel = "your price (${plain(rewardRisk)}R)"
        }

        return Plan(
            entry = e,
            stop = stop,
            stopLabel = stopLabel,
            stopDetail = detail,
            target = target,
            targetLabel = targetLabel,
            riskPerShare = risk.setScale(2, RoundingMode.HALF_UP),
            rewardRisk = rewardRisk
        )
    }

    private fun floorToTick(value: BigDecimal, tick: BigDecimal): BigDecimal {
        if (tick <= BigDecimal.ZERO) return value.setScale(2, RoundingMode.FLOOR)
        val steps = value.divide(tick, 0, RoundingMode.FLOOR)
        return steps.multiply(tick).setScale(2, RoundingMode.HALF_UP)
    }

    private fun roundToTick(value: BigDecimal, tick: BigDecimal): BigDecimal {
        if (tick <= BigDecimal.ZERO) return value.setScale(2, RoundingMode.HALF_UP)
        val steps = value.divide(tick, 0, RoundingMode.HALF_UP)
        return steps.multiply(tick).setScale(2, RoundingMode.HALF_UP)
    }

    private fun money(v: BigDecimal): String = "Rs ${v.setScale(2, RoundingMode.HALF_UP).toPlainString()}"

    private fun plain(v: BigDecimal): String = v.stripTrailingZeros().toPlainString()
}
