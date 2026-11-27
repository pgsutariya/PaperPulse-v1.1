package com.example.domain.strategy

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Rebalancer - ported from the `trader-rebalance` skill.
 *
 * Values every holding, measures how far each has drifted from its target weight, and
 * proposes only the trades needed to pull the outliers back. Positions inside the band are
 * left alone on purpose: churning them costs brokerage and STT for no change in exposure.
 *
 * Sells are planned before buys, so the cash they release is available to the buys in the
 * same pass. Whole shares only - NSE cash equity has no fractional quantity.
 */
object PortfolioRebalancer {

    data class Holding(val symbol: String, val shares: Int)

    data class DriftRow(
        val symbol: String,
        val shares: Int,
        val price: BigDecimal,
        val value: BigDecimal,
        val currentPct: BigDecimal,
        val targetPct: BigDecimal,
        /** Percentage points away from target: positive means overweight. */
        val drift: BigDecimal,
        val targetValue: BigDecimal,
        val tracked: Boolean,
        val action: String
    )

    data class Trade(
        val symbol: String,
        val isBuy: Boolean,
        val quantity: Int,
        val price: BigDecimal,
        val value: BigDecimal,
        val why: String
    )

    data class Plan(
        val totalValue: BigDecimal,
        val cash: BigDecimal,
        val cashBuffer: BigDecimal,
        val rows: List<DriftRow>,
        val trades: List<Trade>,
        /** Things the trader should know: scaled buys, skipped odd lots, capped orders. */
        val notes: List<String>
    )

    class RebalanceError(message: String) : IllegalArgumentException(message)

    /**
     * @param targetWeights symbol -> weight. Weights are relative; they are normalised.
     * @param bandPct only trade a holding once it is this many percentage points off target.
     * @param sellUntracked sell positions that have no target at all.
     */
    fun plan(
        holdings: List<Holding>,
        prices: Map<String, BigDecimal>,
        targetWeights: Map<String, BigDecimal>,
        rules: TraderRules,
        cash: BigDecimal,
        bandPct: BigDecimal = BigDecimal("5"),
        sellUntracked: Boolean = false
    ): Plan {
        val held = holdings.filter { it.shares > 0 }.associate { it.symbol.uppercase() to it.shares }
        val weights = targetWeights.mapKeys { it.key.uppercase() }.filterValues { it > BigDecimal.ZERO }
        val universe = (held.keys + weights.keys).sortedWith(
            compareByDescending<String> { weights[it] ?: BigDecimal.ZERO }.thenBy { it }
        )

        val missing = universe.filter { prices[it] == null || prices.getValue(it) <= BigDecimal.ZERO }
        if (missing.isNotEmpty()) {
            throw RebalanceError("no price for ${missing.joinToString(", ")}")
        }

        val value = universe.associateWith { sym ->
            prices.getValue(sym).multiply(BigDecimal(held[sym] ?: 0)).setScale(2, RoundingMode.HALF_UP)
        }
        val total = value.values.fold(cash) { a, v -> a.add(v) }
        if (total <= BigDecimal.ZERO) throw RebalanceError("the account is empty: nothing to rebalance")

        val buffer = total.multiply(rules.cashBufferPct).divide(TraderRules.HUNDRED, 2, RoundingMode.HALF_UP)
        val cap = total.multiply(rules.maxPositionPct).divide(TraderRules.HUNDRED, 2, RoundingMode.HALF_UP)
        val targets = cappedTargets(weights, total.subtract(buffer), cap)

        val rows = mutableListOf<DriftRow>()
        val sells = mutableListOf<Triple<String, BigDecimal, Boolean>>() // symbol, rupees, sellAll
        val buys = mutableListOf<Pair<String, BigDecimal>>()
        val notes = mutableListOf<String>()
        val whyOf = mutableMapOf<String, String>()

        for (sym in universe) {
            val tracked = sym in weights
            val tgt = targets[sym] ?: BigDecimal.ZERO
            val curPct = value.getValue(sym).multiply(TraderRules.HUNDRED)
                .divide(total, 2, RoundingMode.HALF_UP)
            val tgtPct = tgt.multiply(TraderRules.HUNDRED).divide(total, 2, RoundingMode.HALF_UP)
            val drift = curPct.subtract(tgtPct)
            var action = "hold"

            if (!tracked) {
                if (sellUntracked && (held[sym] ?: 0) > 0) {
                    sells += Triple(sym, value.getValue(sym), true)
                    whyOf[sym] = "not in your targets; selling the whole position"
                    action = "sell all"
                } else {
                    action = "untracked, left alone"
                }
            } else if (drift.abs() > bandPct) {
                val delta = tgt.subtract(value.getValue(sym))
                val why = "drifted ${drift.setScale(1, RoundingMode.HALF_UP).toPlainString()} points " +
                    "from its ${tgtPct.setScale(1, RoundingMode.HALF_UP).toPlainString()}% target " +
                    "(band ${bandPct.stripTrailingZeros().toPlainString()})"
                whyOf[sym] = why
                if (delta < BigDecimal.ZERO) {
                    sells += Triple(sym, delta.negate(), false)
                    action = "sell"
                } else {
                    buys += sym to delta
                    action = "buy"
                }
            }

            rows += DriftRow(
                symbol = sym, shares = held[sym] ?: 0, price = prices.getValue(sym),
                value = value.getValue(sym), currentPct = curPct, targetPct = tgtPct,
                drift = drift, targetValue = tgt, tracked = tracked, action = action
            )
        }

        val trades = mutableListOf<Trade>()
        var cashAfter = cash

        // Sells first: they fund the buys in the same pass.
        for ((sym, rupeesIn, sellAll) in sells) {
            val price = prices.getValue(sym)
            var rupees = rupeesIn
            rules.maxOrderInr?.let { maxOrder ->
                if (!sellAll && rupees > maxOrder) {
                    notes += "$sym sell cut to your max order; run again after it fills"
                    rupees = maxOrder
                }
            }
            val qty = if (sellAll) {
                held[sym] ?: 0
            } else {
                minOf(rupees.divide(price, 0, RoundingMode.FLOOR).toInt(), held[sym] ?: 0)
            }
            val gross = price.multiply(BigDecimal(qty)).setScale(2, RoundingMode.HALF_UP)
            if (qty <= 0 || gross < rules.minOrderInr) {
                notes += "$sym sell is under your minimum order: skipped"
                continue
            }
            cashAfter = cashAfter.add(gross)
            trades += Trade(sym, isBuy = false, quantity = qty, price = price, value = gross,
                why = whyOf[sym] ?: "rebalance")
        }

        // Buys share whatever is free above the buffer; if that is short, scale them down
        // together rather than filling the first few and starving the rest.
        val spendable = cashAfter.subtract(buffer).max(BigDecimal.ZERO)
        val wanted = buys.fold(BigDecimal.ZERO) { a, b -> a.add(b.second) }
        var scale = BigDecimal.ONE
        if (wanted > spendable && wanted > BigDecimal.ZERO) {
            scale = spendable.divide(wanted, 6, RoundingMode.DOWN)
            notes += "buys scaled to ${scale.multiply(TraderRules.HUNDRED).setScale(0, RoundingMode.DOWN)
                .toPlainString()}%: only that much is free above your cash buffer"
        }
        for ((sym, wantRupees) in buys) {
            val price = prices.getValue(sym)
            var rupees = wantRupees.multiply(scale)
            rules.maxOrderInr?.let { maxOrder ->
                if (rupees > maxOrder) {
                    notes += "$sym buy cut to your max order; run again after it fills"
                    rupees = maxOrder
                }
            }
            val qty = rupees.divide(price, 0, RoundingMode.FLOOR).toInt()
            val gross = price.multiply(BigDecimal(qty)).setScale(2, RoundingMode.HALF_UP)
            if (qty < 1 || gross < rules.minOrderInr) {
                notes += "$sym buy does not reach one whole share or your minimum order: skipped"
                continue
            }
            cashAfter = cashAfter.subtract(gross)
            trades += Trade(sym, isBuy = true, quantity = qty, price = price, value = gross,
                why = whyOf[sym] ?: "rebalance")
        }

        return Plan(
            totalValue = total.setScale(2, RoundingMode.HALF_UP),
            cash = cash.setScale(2, RoundingMode.HALF_UP),
            cashBuffer = buffer,
            rows = rows, trades = trades, notes = notes
        )
    }

    /**
     * Rupee target per symbol, proportional to weight, with none over [cap].
     * Anything trimmed by the cap is shared out among the rest, repeatedly, until no
     * target breaches it - otherwise capping one name would silently under-invest the book.
     */
    internal fun cappedTargets(
        weights: Map<String, BigDecimal>,
        investable: BigDecimal,
        cap: BigDecimal
    ): Map<String, BigDecimal> {
        val out = mutableMapOf<String, BigDecimal>()
        val active = weights.toMutableMap()
        var left = investable
        while (active.isNotEmpty()) {
            val weightSum = active.values.fold(BigDecimal.ZERO) { a, w -> a.add(w) }
            if (weightSum <= BigDecimal.ZERO) break
            val trial = active.mapValues { (_, w) ->
                left.multiply(w).divide(weightSum, 2, RoundingMode.HALF_UP)
            }
            val over = trial.filterValues { it > cap }.keys
            if (over.isEmpty()) {
                out.putAll(trial)
                break
            }
            for (sym in over) {
                out[sym] = cap
                left = left.subtract(cap)
                active.remove(sym)
            }
            if (left <= BigDecimal.ZERO) {
                active.keys.forEach { out[it] = BigDecimal.ZERO }
                break
            }
        }
        return out
    }
}
