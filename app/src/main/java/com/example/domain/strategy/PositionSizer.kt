package com.example.domain.strategy

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Budget Manager - ported from the `trader-in-budget` skill.
 *
 * Sizes one long day trade against four independent limits and reports which one decided
 * the quantity. The point of naming the binding limit is that "why is my size so small?"
 * always has a concrete answer.
 *
 * Pure and currency-exact (BigDecimal throughout), so it slots in next to [com.example.domain.engine.RiskMath].
 */
object PositionSizer {

    /** One of the four ceilings on size. */
    data class Limit(
        val name: String,
        /** The rupee ceiling this limit imposes. */
        val amount: BigDecimal,
        /** What each share consumes of it (risk per share, or the entry price). */
        val perShare: BigDecimal,
        /** Whole shares this limit allows. */
        val shares: Int,
        /** Human-readable arithmetic, e.g. "Rs 500 max loss / Rs 2.40 risk a share". */
        val how: String,
        /** True when this limit is the one that set the final quantity. */
        val binds: Boolean
    )

    /** Which price the sizing was done at. */
    enum class EntrySource { PLAN, LIVE_PRICE, LIMIT }

    data class Sizing(
        val symbol: String,
        val entry: BigDecimal,
        val entrySource: EntrySource,
        val stop: BigDecimal,
        val target: BigDecimal,
        val riskPerShare: BigDecimal,
        val rewardRisk: BigDecimal,
        val cashBuffer: BigDecimal,
        val cashAvailable: BigDecimal,
        val limits: List<Limit>,
        val quantity: Int,
        val boundBy: String,
        val cost: BigDecimal,
        val maxLoss: BigDecimal,
        /**
         * Non-null when no order should be placed. The text explains which rule stopped it
         * and what to do instead; show it to the trader verbatim.
         */
        val refusal: String?
    ) {
        val isTradeable: Boolean get() = refusal == null && quantity > 0
    }

    class SizingError(message: String) : IllegalArgumentException(message)

    /**
     * @param plan the exit plan (entry, stop, target) from [ExitPlanner].
     * @param livePrice a fresh last-traded price. A market order is sized at the *higher*
     *   of it and the plan's entry, so a gap up never under-states the cost or the risk.
     * @param limitPrice set for a limit order; it then becomes the reference price.
     * @param cashOnHand actual cash, when it is less than the budget allows.
     */
    fun size(
        symbol: String,
        plan: ExitPlanner.Plan,
        rules: TraderRules,
        livePrice: BigDecimal? = null,
        limitPrice: BigDecimal? = null,
        cashOnHand: BigDecimal? = null
    ): Sizing {
        val sym = symbol.trim().uppercase()
        if (sym.isEmpty()) throw SizingError("the plan has no symbol")
        if (sym in rules.blocked) throw SizingError("$sym is on your blocked list")
        if (rules.budget <= BigDecimal.ZERO) throw SizingError("no budget set")

        val entryPlan = plan.entry.setScale(2, RoundingMode.HALF_UP)
        val reference: BigDecimal
        val source: EntrySource
        when {
            limitPrice != null -> {
                reference = limitPrice.setScale(2, RoundingMode.HALF_UP); source = EntrySource.LIMIT
            }
            livePrice != null && livePrice.setScale(2, RoundingMode.HALF_UP) > entryPlan -> {
                reference = livePrice.setScale(2, RoundingMode.HALF_UP); source = EntrySource.LIVE_PRICE
            }
            else -> {
                reference = entryPlan; source = EntrySource.PLAN
            }
        }

        val stop = plan.stop.setScale(2, RoundingMode.HALF_UP)
        val target = plan.target.setScale(2, RoundingMode.HALF_UP)
        if (stop >= reference) {
            throw SizingError(
                "the stop ${money(stop)} is not below the entry ${money(reference)}; " +
                    "a long trade needs its stop underneath"
            )
        }
        if (target <= reference) {
            throw SizingError(
                "the target ${money(target)} is not above the entry ${money(reference)}: " +
                    "the move has already happened"
            )
        }

        val risk = reference.subtract(stop)
        val reward = target.subtract(reference)

        // Cash: the budget less the buffer, or actual cash less the buffer when that is tighter.
        val cashBuffer = rules.cashBuffer
        var available = rules.budget.subtract(cashBuffer)
        var cashNote = "budget ${money(rules.budget)} minus the ${money(cashBuffer)} cash buffer"
        if (cashOnHand != null && cashOnHand.subtract(cashBuffer) < available) {
            available = cashOnHand.subtract(cashBuffer)
            cashNote = "cash ${money(cashOnHand)} minus the ${money(cashBuffer)} buffer"
        }
        available = available.max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP)

        val limits = mutableListOf<Limit>()
        rules.maxLossPerTradeInr?.let { maxLoss ->
            limits += Limit(
                name = "max loss", amount = maxLoss, perShare = risk,
                shares = sharesFor(maxLoss, risk),
                how = "${money(maxLoss)} max loss / ${money(risk)} risk a share", binds = false
            )
        }
        val cap = rules.positionCap
        limits += Limit(
            name = "position cap", amount = cap, perShare = reference,
            shares = sharesFor(cap, reference),
            how = "${plain(rules.maxPositionPct)}% of ${money(rules.budget)} = ${money(cap)} / ${money(reference)}",
            binds = false
        )
        rules.maxOrderInr?.let { maxOrder ->
            limits += Limit(
                name = "max order", amount = maxOrder, perShare = reference,
                shares = sharesFor(maxOrder, reference),
                how = "${money(maxOrder)} / ${money(reference)}", binds = false
            )
        }
        limits += Limit(
            name = "cash", amount = available, perShare = reference,
            shares = sharesFor(available, reference),
            how = "$cashNote = ${money(available)} / ${money(reference)}", binds = false
        )

        val quantity = limits.minOf { it.shares }.coerceAtLeast(0)
        val boundBy = limits.first { it.shares == quantity }.name
        val marked = limits.map { it.copy(binds = it.name == boundBy) }

        val cost = reference.multiply(BigDecimal(quantity)).setScale(2, RoundingMode.HALF_UP)
        val maxLossAtStop = risk.multiply(BigDecimal(quantity)).setScale(2, RoundingMode.HALF_UP)
        val rewardRisk = reward.divide(risk, 2, RoundingMode.HALF_UP)

        val refusal: String? = when {
            quantity == 0 && boundBy == "max loss" ->
                "one share risks ${money(risk)} (entry ${money(reference)} minus stop ${money(stop)}), more than " +
                    "your ${money(rules.maxLossPerTradeInr ?: BigDecimal.ZERO)} max loss per trade. No order. " +
                    "Skip this trade, or plan a closer stop."

            quantity == 0 ->
                "one share of $sym costs ${money(reference)}, more than your $boundBy limit " +
                    "(${marked.first { it.name == boundBy }.how}). No order."

            rewardRisk < rules.minRewardRisk ->
                "reward:risk ${plain(rewardRisk)} (target ${money(target)}, entry ${money(reference)}, " +
                    "stop ${money(stop)}) is under your minimum ${plain(rules.minRewardRisk)}, so the risk gate " +
                    "would block it. No order."

            cost < rules.minOrderInr ->
                "$quantity share${if (quantity == 1) "" else "s"} of $sym is ${money(cost)}, under your " +
                    "${money(rules.minOrderInr)} minimum order. No order."

            else -> null
        }

        return Sizing(
            symbol = sym, entry = reference, entrySource = source, stop = stop, target = target,
            riskPerShare = risk.setScale(2, RoundingMode.HALF_UP), rewardRisk = rewardRisk,
            cashBuffer = cashBuffer, cashAvailable = available, limits = marked,
            quantity = quantity, boundBy = boundBy, cost = cost, maxLoss = maxLossAtStop,
            refusal = refusal
        )
    }

    /** Whole units of [perUnit] that fit inside [amount]; 0 when one unit already overflows. */
    private fun sharesFor(amount: BigDecimal, perUnit: BigDecimal): Int {
        if (perUnit <= BigDecimal.ZERO) return 0
        if (amount <= BigDecimal.ZERO) return 0
        return amount.divide(perUnit, 0, RoundingMode.FLOOR).toInt().coerceAtLeast(0)
    }

    private fun money(v: BigDecimal): String = "Rs ${v.setScale(2, RoundingMode.HALF_UP).toPlainString()}"

    private fun plain(v: BigDecimal): String = v.stripTrailingZeros().toPlainString()
}
