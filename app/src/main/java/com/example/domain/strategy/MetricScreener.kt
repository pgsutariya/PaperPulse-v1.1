package com.example.domain.strategy

/**
 * Stock Screener - ported from the `trader-screener` skill.
 *
 * Checks companies against the trader's own rules and says, for each one, exactly which
 * rule it passed or failed and by how much. The reasons are the point: a screen that only
 * returns a list of tickers cannot be argued with.
 *
 * Metrics are untyped on purpose - the pack reads whatever is in the metrics file, so the
 * engine accepts numbers and strings and reports a type mismatch rather than guessing.
 */
object MetricScreener {

    /** How a rule compares the company's metric with the wanted value. */
    enum class Op(val symbol: String) {
        GT(">"), GTE(">="), LT("<"), LTE("<="), EQ("=="), NEQ("!="),
        BETWEEN("between"), IN("in"), NOT_IN("not in")
    }

    /** What to do when a company simply has no value for the metric. */
    enum class MissingPolicy { FAIL, SKIP }

    sealed interface Want {
        data class Number(val value: Double) : Want
        data class Text(val value: String) : Want
        data class Range(val low: Double, val high: Double) : Want
        data class OneOf(val values: List<String>) : Want
    }

    data class Rule(val metric: String, val op: Op, val want: Want)

    data class Company(val ticker: String, val name: String? = null, val metrics: Map<String, Any?>)

    data class Check(val rule: Rule, val passed: Boolean?, val reason: String)

    data class Result(
        val ticker: String,
        val name: String?,
        val passed: Boolean,
        val checks: List<Check>,
        val score: Double?
    ) {
        val failures: List<Check> get() = checks.filter { it.passed == false }
        val skipped: List<Check> get() = checks.filter { it.passed == null }
    }

    /**
     * Evaluate one rule. A null `passed` means the metric was absent - the caller decides
     * whether that is a failure, via [MissingPolicy].
     */
    fun check(company: Company, rule: Rule): Check {
        val have = company.metrics[rule.metric]
        if (have == null || (have is String && have.isBlank())) {
            return Check(rule, null, "${rule.metric} is missing")
        }

        if (rule.op == Op.IN || rule.op == Op.NOT_IN) {
            val bag = (rule.want as? Want.OneOf)?.values?.map { it.uppercase() }
                ?: return Check(rule, false, "${rule.metric}: '${rule.op.symbol}' needs a list of values")
            val inside = have.toString().uppercase() in bag
            val ok = if (rule.op == Op.IN) inside else !inside
            return Check(
                rule, ok,
                "${rule.metric} $have ${if (inside) "is in" else "is not in"} ${bag.joinToString(", ")}"
            )
        }

        val number = when (have) {
            is Number -> have.toDouble()
            is String -> have.toDoubleOrNull()
            else -> null
        }

        // Text equality is allowed, but only for == and != - everything else needs a number.
        if (number == null) {
            if (rule.op == Op.EQ || rule.op == Op.NEQ) {
                val wantText = when (val w = rule.want) {
                    is Want.Text -> w.value
                    is Want.Number -> w.value.toString()
                    else -> return Check(rule, false, "${rule.metric}: cannot compare text with that value")
                }
                val same = have.toString().equals(wantText, ignoreCase = true)
                val ok = if (rule.op == Op.EQ) same else !same
                return Check(rule, ok, "${rule.metric} $have ${rule.op.symbol} $wantText")
            }
            return Check(rule, false, "${rule.metric} is '$have', not a number")
        }

        if (rule.op == Op.BETWEEN) {
            val range = rule.want as? Want.Range
                ?: return Check(rule, false, "${rule.metric}: 'between' needs a low and a high")
            val ok = number >= range.low && number <= range.high
            return Check(
                rule, ok,
                "${rule.metric} ${fmt(number)} ${if (ok) "is within" else "is outside"} " +
                    "${fmt(range.low)} to ${fmt(range.high)}"
            )
        }

        val target = when (val w = rule.want) {
            is Want.Number -> w.value
            is Want.Text -> w.value.toDoubleOrNull()
            else -> null
        } ?: return Check(rule, false, "${rule.metric}: '${rule.op.symbol}' needs a number to compare with")

        val ok = when (rule.op) {
            Op.GT -> number > target
            Op.GTE -> number >= target
            Op.LT -> number < target
            Op.LTE -> number <= target
            Op.EQ -> number == target
            Op.NEQ -> number != target
            else -> false
        }
        return Check(
            rule, ok,
            if (ok) "${rule.metric} ${fmt(number)} ${rule.op.symbol} ${fmt(target)}"
            else "${rule.metric} ${fmt(number)}, needs ${rule.op.symbol} ${fmt(target)}"
        )
    }

    /**
     * Run every rule over every company.
     *
     * Passing companies come first, ranked by [rankBy] (the sum of those metrics, highest
     * first), then by fewest failures, then alphabetically - so the order is stable.
     */
    fun screen(
        companies: List<Company>,
        rules: List<Rule>,
        missing: MissingPolicy = MissingPolicy.FAIL,
        rankBy: List<String> = emptyList()
    ): List<Result> {
        val results = companies.map { company ->
            val checks = rules.map { check(company, it) }
            val failed = checks.any { it.passed == false } ||
                (missing == MissingPolicy.FAIL && checks.any { it.passed == null })
            val score = if (rankBy.isEmpty()) null else rankBy.sumOf { key ->
                when (val v = company.metrics[key]) {
                    is Number -> v.toDouble()
                    is String -> v.toDoubleOrNull() ?: 0.0
                    else -> 0.0
                }
            }
            Result(company.ticker, company.name, !failed, checks, score)
        }
        return results.sortedWith(
            compareBy<Result> { !it.passed }
                .thenByDescending { it.score ?: 0.0 }
                .thenBy { it.failures.size }
                .thenBy { it.ticker }
        )
    }

    private fun fmt(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else String.format("%.2f", v)
}
