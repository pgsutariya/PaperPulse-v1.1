package com.example.domain.strategy

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/**
 * Tax Lot Tracker - ported from the `trader-tax` skill, retargeted to Indian equity rules.
 *
 * What changed from the US pack and why:
 *  - The short/long boundary is **12 months** for listed equity, not the US 1-year-and-a-day.
 *  - There is **no wash-sale rule in India**, so the pack's wash-sale machinery is dropped
 *    rather than ported into something that would quietly mislead.
 *  - **FIFO is the default and the only method the depositories actually recognise** for
 *    demat equity. HIFO and specific-lot selection are kept because they answer "what
 *    would this sale cost me?", but they are labelled as planning aids, not filing methods.
 *
 * Rates are deliberately *not* hardcoded: they move with each budget, so the caller passes
 * them in and the engine reports the gain split, which is the part that does not change.
 */
object TaxLotTracker {

    /** One purchase, kept whole so its holding period is known. */
    data class Lot(
        val id: String,
        val acquired: LocalDate,
        val shares: Int,
        /** Cost per share including the charges booked on that buy. */
        val costPerShare: BigDecimal
    ) {
        val cost: BigDecimal get() = costPerShare.multiply(BigDecimal(shares)).setScale(2, RoundingMode.HALF_UP)
    }

    enum class Term { SHORT, LONG }

    /** How to choose which lots a sale consumes. */
    enum class Method {
        /** Oldest first - what the depository actually does. */
        FIFO,

        /** Highest cost first - minimises the booked gain. A planning view only. */
        HIGHEST_COST,

        /** Caller names the lots, in order. A planning view only. */
        SPECIFIC
    }

    data class LotConsumption(
        val lotId: String,
        val acquired: LocalDate,
        val shares: Int,
        val basis: BigDecimal,
        val proceeds: BigDecimal,
        val gain: BigDecimal,
        val term: Term
    )

    data class SaleResult(
        val shares: Int,
        val proceeds: BigDecimal,
        val basis: BigDecimal,
        val gain: BigDecimal,
        val shortTermGain: BigDecimal,
        val longTermGain: BigDecimal,
        val consumed: List<LotConsumption>
    )

    data class UnrealisedLot(
        val lot: Lot,
        val marketValue: BigDecimal,
        val gain: BigDecimal,
        val term: Term
    )

    class TaxError(message: String) : IllegalArgumentException(message)

    /**
     * Listed equity is long-term only once it has been held for **more than** 12 months,
     * so a sale on the anniversary itself is still short-term.
     */
    fun termFor(acquired: LocalDate, sold: LocalDate): Term =
        if (sold.isAfter(acquired.plusMonths(12))) Term.LONG else Term.SHORT

    /** Choose the lots a sale of [shares] consumes, in order, without mutating anything. */
    fun pickLots(
        lots: List<Lot>,
        shares: Int,
        method: Method = Method.FIFO,
        lotIds: List<String> = emptyList()
    ): List<Pair<Lot, Int>> {
        if (shares <= 0) throw TaxError("a sale needs a positive number of shares")
        val held = lots.sumOf { it.shares }
        if (shares > held) {
            throw TaxError("selling $shares shares but only $held are held")
        }
        val ordered: List<Lot> = when (method) {
            Method.FIFO -> lots.sortedBy { it.acquired }
            Method.HIGHEST_COST -> lots.sortedByDescending { it.costPerShare }
            Method.SPECIFIC -> {
                if (lotIds.isEmpty()) throw TaxError("specific-lot selling needs the lot ids")
                val byId = lots.associateBy { it.id }
                val unknown = lotIds.filterNot { byId.containsKey(it) }
                if (unknown.isNotEmpty()) {
                    throw TaxError("no such lot: ${unknown.joinToString(", ")}")
                }
                val named = lotIds.map { byId.getValue(it) }
                if (shares > named.sumOf { it.shares }) {
                    throw TaxError("the named lots hold fewer than $shares shares")
                }
                named
            }
        }
        val out = mutableListOf<Pair<Lot, Int>>()
        var left = shares
        for (lot in ordered) {
            if (left <= 0) break
            val take = minOf(left, lot.shares)
            out += lot to take
            left -= take
        }
        return out
    }

    /** What a sale of [shares] at [price] on [soldOn] would realise. Nothing is mutated. */
    fun simulateSale(
        lots: List<Lot>,
        shares: Int,
        price: BigDecimal,
        soldOn: LocalDate,
        method: Method = Method.FIFO,
        lotIds: List<String> = emptyList()
    ): SaleResult {
        val consumed = pickLots(lots, shares, method, lotIds).map { (lot, take) ->
            val basis = lot.costPerShare.multiply(BigDecimal(take)).setScale(2, RoundingMode.HALF_UP)
            val proceeds = price.multiply(BigDecimal(take)).setScale(2, RoundingMode.HALF_UP)
            LotConsumption(
                lotId = lot.id, acquired = lot.acquired, shares = take,
                basis = basis, proceeds = proceeds,
                gain = proceeds.subtract(basis),
                term = termFor(lot.acquired, soldOn)
            )
        }
        val proceeds = consumed.fold(BigDecimal.ZERO) { a, c -> a.add(c.proceeds) }
        val basis = consumed.fold(BigDecimal.ZERO) { a, c -> a.add(c.basis) }
        val shortGain = consumed.filter { it.term == Term.SHORT }
            .fold(BigDecimal.ZERO) { a, c -> a.add(c.gain) }
        val longGain = consumed.filter { it.term == Term.LONG }
            .fold(BigDecimal.ZERO) { a, c -> a.add(c.gain) }
        return SaleResult(
            shares = consumed.sumOf { it.shares },
            proceeds = proceeds.setScale(2, RoundingMode.HALF_UP),
            basis = basis.setScale(2, RoundingMode.HALF_UP),
            gain = proceeds.subtract(basis).setScale(2, RoundingMode.HALF_UP),
            shortTermGain = shortGain.setScale(2, RoundingMode.HALF_UP),
            longTermGain = longGain.setScale(2, RoundingMode.HALF_UP),
            consumed = consumed
        )
    }

    /** Mark every lot to [price] and split the result by holding period. */
    fun unrealised(lots: List<Lot>, price: BigDecimal, asOf: LocalDate): List<UnrealisedLot> =
        lots.map { lot ->
            val value = price.multiply(BigDecimal(lot.shares)).setScale(2, RoundingMode.HALF_UP)
            UnrealisedLot(
                lot = lot,
                marketValue = value,
                gain = value.subtract(lot.cost).setScale(2, RoundingMode.HALF_UP),
                term = termFor(lot.acquired, asOf)
            )
        }

    /**
     * Lots sitting on a loss of at least [minLoss], worst first - the candidates for
     * booking a loss against other gains before the year ends.
     */
    fun harvestCandidates(
        lots: List<Lot>,
        price: BigDecimal,
        asOf: LocalDate,
        minLoss: BigDecimal = BigDecimal("1000")
    ): List<UnrealisedLot> =
        unrealised(lots, price, asOf)
            .filter { it.gain.negate() >= minLoss }
            .sortedBy { it.gain }

    /**
     * Apply a sale to the lot list and return what is left, so a caller can keep the
     * running book. Lots are consumed in the same order [pickLots] chose.
     */
    fun applySale(
        lots: List<Lot>,
        shares: Int,
        method: Method = Method.FIFO,
        lotIds: List<String> = emptyList()
    ): List<Lot> {
        val taken = pickLots(lots, shares, method, lotIds).associate { (lot, n) -> lot.id to n }
        return lots.mapNotNull { lot ->
            val n = taken[lot.id] ?: return@mapNotNull lot
            val left = lot.shares - n
            if (left > 0) lot.copy(shares = left) else null
        }
    }
}
