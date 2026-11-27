package com.example.domain.strategy

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalTime

/**
 * Risk Manager - ported from the `trader-in-risk` / `trader-risk` skills.
 *
 * The last gate before an order reaches the broker. Every rule that fails produces a
 * reason the trader can read; anything with a reason is BLOCKED, never silently resized.
 *
 * Warnings do not block - they are things worth knowing before saying yes.
 */
object PreTradeRiskGate {

    enum class Verdict { PASS, BLOCK }

    /** What the account looks like at the moment of the check. */
    data class AccountSnapshot(
        val cash: BigDecimal,
        val equity: BigDecimal,
        /** Shares already held per symbol, for the short-selling and position-cap checks. */
        val holdings: Map<String, Int> = emptyMap(),
        /** Current market value held per symbol. */
        val holdingValue: Map<String, BigDecimal> = emptyMap()
    )

    /** What has already happened today, for the daily caps. */
    data class DayState(
        val ordersPlaced: Int = 0,
        val entriesPlaced: Int = 0,
        /** Sum of the max-loss-at-stop of today's entries. */
        val committedLoss: BigDecimal = BigDecimal.ZERO
    )

    /** The order being checked. */
    data class ProposedOrder(
        val symbol: String,
        val isBuy: Boolean,
        val quantity: Int,
        val entry: BigDecimal,
        val stop: BigDecimal? = null,
        val target: BigDecimal? = null,
        /** True when this order opens or adds to a position (as opposed to closing one). */
        val isEntry: Boolean = true
    )

    data class Result(
        val verdict: Verdict,
        val reasons: List<String>,
        val warnings: List<String>,
        val orderValue: BigDecimal,
        val lossAtStop: BigDecimal?,
        val rewardRisk: BigDecimal?
    ) {
        val passed: Boolean get() = verdict == Verdict.PASS
    }

    /**
     * Check one order against [rules].
     *
     * @param livePrice a fresh price; an entry is measured at the *higher* of it and the
     *   plan's entry so a gap up cannot sneak past the max-loss rule.
     * @param now the current IST time, for the entry-window rules. Null skips those.
     */
    fun check(
        order: ProposedOrder,
        rules: TraderRules,
        account: AccountSnapshot,
        day: DayState = DayState(),
        livePrice: BigDecimal? = null,
        now: LocalTime? = null,
        currencySymbol: String = "Rs"
    ): Result {
        val reasons = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val sym = order.symbol.trim().uppercase()

        // ---- format -------------------------------------------------------------
        if (sym.isEmpty()) reasons += "format: no symbol"
        if (order.quantity <= 0) reasons += "format: quantity must be a positive whole number of shares"

        val reference = when {
            order.isEntry && livePrice != null && order.isBuy && livePrice > order.entry -> livePrice
            order.isEntry && livePrice != null && !order.isBuy && livePrice < order.entry -> livePrice
            else -> order.entry
        }.setScale(2, RoundingMode.HALF_UP)
        if (reference <= BigDecimal.ZERO) reasons += "format: the entry price must be above ${money(BigDecimal.ZERO, currencySymbol)}"

        val value = reference.multiply(BigDecimal(order.quantity.coerceAtLeast(0)))
            .setScale(2, RoundingMode.HALF_UP)

        // ---- blocked ------------------------------------------------------------
        if (sym in rules.blocked) reasons += "blocked: $sym is on your blocked list"

        // ---- short selling ------------------------------------------------------
        if (!order.isBuy && order.isEntry && !rules.allowShort) {
            val held = account.holdings[sym] ?: 0
            if (order.quantity > held) {
                reasons += "short: selling more $sym than you hold ($held shares); short selling is off"
            }
        }

        // ---- order size ---------------------------------------------------------
        if (order.isEntry) {
            if (value < rules.minOrderInr) {
                reasons += "size: ${money(value, currencySymbol)} is under your ${money(rules.minOrderInr, currencySymbol)} minimum order"
            }
            rules.maxOrderInr?.let { maxOrder ->
                if (value > maxOrder) {
                    reasons += "size: ${money(value, currencySymbol)} is over your ${money(maxOrder, currencySymbol)} max order"
                }
            }
        }

        // ---- stop, loss and reward:risk ----------------------------------------
        var lossAtStop: BigDecimal? = null
        var rewardRisk: BigDecimal? = null
        if (order.isEntry) {
            val stop = order.stop
            if (stop == null) {
                if (rules.requireStop) reasons += "stop: a day trade needs a stop; this order has none"
            } else if (order.isBuy && stop >= reference) {
                reasons += "stop: the stop ${money(stop, currencySymbol)} is not below the entry ${money(reference, currencySymbol)}"
            } else if (!order.isBuy && stop <= reference) {
                reasons += "stop: the stop ${money(stop, currencySymbol)} is not above the entry ${money(reference, currencySymbol)}"
            } else {
                val risk = reference.subtract(stop).abs()
                val loss = risk.multiply(BigDecimal(order.quantity)).setScale(2, RoundingMode.HALF_UP)
                lossAtStop = loss
                rules.maxLossPerTradeInr?.let { maxLoss ->
                    if (loss > maxLoss) {
                        reasons += "loss: ${money(loss, currencySymbol)} at the stop is over your ${money(maxLoss, currencySymbol)} " +
                            "max loss per trade"
                    }
                }
                order.target?.let { target ->
                    if (risk > BigDecimal.ZERO) {
                        val reward = if (order.isBuy) {
                            target.subtract(reference)
                        } else {
                            reference.subtract(target)
                        }
                        val rr = reward.divide(risk, 2, RoundingMode.HALF_UP)
                        rewardRisk = rr
                        if (reward <= BigDecimal.ZERO) {
                            val sideDir = if (order.isBuy) "above" else "below"
                            reasons += "target: ${money(target, currencySymbol)} is not $sideDir entry ${money(reference, currencySymbol)}"
                        } else if (rr < rules.minRewardRisk) {
                            reasons += "reward:risk ${plain(rr)} is under your minimum ${plain(rules.minRewardRisk)}"
                        }
                    }
                }
            }
        }

        // ---- cash and buffer ----------------------------------------------------
        if (order.isBuy && order.isEntry) {
            if (value > account.cash && !rules.allowMargin) {
                reasons += "cash: needs ${money(value, currencySymbol)} but only ${money(account.cash, currencySymbol)} cash; " +
                    "that would be margin (borrowed money)"
            } else {
                val left = account.cash.subtract(value)
                if (left < rules.cashBuffer) {
                    reasons += "cash: leaves ${money(left, currencySymbol)} cash, under your " +
                        "${plain(rules.cashBufferPct)}% buffer of ${money(rules.cashBuffer, currencySymbol)}"
                }
            }

            // ---- position cap after the fill ------------------------------------
            val existing = account.holdingValue[sym] ?: BigDecimal.ZERO
            val after = existing.add(value)
            if (account.equity > BigDecimal.ZERO) {
                val pct = after.multiply(TraderRules.HUNDRED)
                    .divide(account.equity, 1, RoundingMode.HALF_UP)
                if (pct > rules.maxPositionPct) {
                    reasons += "position: $sym would be ${money(after, currencySymbol)}, ${plain(pct)}% of the " +
                        "${money(account.equity, currencySymbol)} account, over your ${plain(rules.maxPositionPct)}% cap"
                }
            }
        }

        // ---- daily caps ---------------------------------------------------------
        if (order.isEntry) {
            if (day.entriesPlaced >= rules.maxTradesPerDay) {
                reasons += "daily: ${day.entriesPlaced} trades already today, at your " +
                    "${rules.maxTradesPerDay}-trade limit"
            }
            rules.maxDailyLossInr?.let { cap ->
                val wouldBe = day.committedLoss.add(lossAtStop ?: BigDecimal.ZERO)
                if (wouldBe > cap) {
                    reasons += "daily: ${money(wouldBe, currencySymbol)} at risk today would pass your " +
                        "${money(cap, currencySymbol)} daily loss cap (${money(day.committedLoss, currencySymbol)} already committed)"
                }
            }
        }
        if (day.ordersPlaced >= rules.maxOrdersPerDay) {
            reasons += "daily: ${day.ordersPlaced} orders already today, at your " +
                "${rules.maxOrdersPerDay}-order limit"
        }

        // ---- entry window -------------------------------------------------------
        if (order.isEntry && now != null) {
            if (now < rules.noEntriesBefore) {
                reasons += "window: no entries before ${rules.noEntriesBefore} (the open is still settling)"
            }
            if (now > rules.noEntriesAfter) {
                reasons += "window: no new entries after ${rules.noEntriesAfter}; " +
                    "everything is flat by ${rules.flatBy}"
            }
        }

        if (!order.isEntry && order.stop == null) {
            warnings += "this is an exit, so the stop and reward:risk rules were not applied"
        }

        return Result(
            verdict = if (reasons.isEmpty()) Verdict.PASS else Verdict.BLOCK,
            reasons = reasons,
            warnings = warnings,
            orderValue = value,
            lossAtStop = lossAtStop,
            rewardRisk = rewardRisk
        )
    }

    private fun money(v: BigDecimal, currencySymbol: String = "Rs"): String {
        val formatted = v.setScale(2, RoundingMode.HALF_UP).toPlainString()
        return if (currencySymbol == "$") "$$formatted" else "$currencySymbol $formatted"
    }

    private fun plain(v: BigDecimal): String = v.stripTrailingZeros().toPlainString()
}
