package com.example.domain.engine

import com.example.domain.model.MarketSegment
import com.example.domain.model.OptionType
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class OptionGreeks(
    val ivPercent: Double,
    val delta: Double,
    val gamma: Double,
    val thetaPerDay: Double,
    val vega: Double,
    val isBrokerSupplied: Boolean = false
)

data class OptionLegQuote(
    val symbol: String,
    val tradingSymbol: String,
    val exchange: String,
    val symbolToken: String,
    val exchangeTypeCode: Int,
    val segment: MarketSegment,
    val lotSize: Int,
    val tickSize: BigDecimal,
    val strikePrice: BigDecimal,
    val optionType: OptionType,
    val expiryLabel: String,
    val ltp: BigDecimal,
    val netChange: BigDecimal = BigDecimal.ZERO,
    val changePercent: BigDecimal = BigDecimal.ZERO,
    val openInterest: Long = 0L,
    val oiChange: Long = 0L,
    val volume: Long = 0L,
    val greeks: OptionGreeks? = null
) {
    val tokenKey: String get() = "$exchangeTypeCode:$symbolToken"
}

data class OptionChainRow(
    val strikePrice: BigDecimal,
    val isAtm: Boolean,
    val call: OptionLegQuote?,
    val put: OptionLegQuote?
)

data class OptionChainSnapshot(
    val underlying: String,
    val exchange: String,
    val spotPrice: BigDecimal,
    val spotChange: BigDecimal,
    val spotChangePercent: BigDecimal,
    val expiry: String,
    val availableExpiries: List<String>,
    val rows: List<OptionChainRow>,
    val atmStrike: BigDecimal,
    val totalCallOi: Long,
    val totalPutOi: Long,
    val pcr: Double,
    val maxPainStrike: BigDecimal?,
    val atmIvPercent: Double?,
    val greeksSourceLabel: String,
    val updatedAtMillis: Long
)

data class TokenLiveTick(
    val exchangeType: Int,
    val token: String,
    val ltp: BigDecimal,
    val openInterest: Long? = null,
    val volume: Long? = null,
    val timestampMillis: Long = System.currentTimeMillis()
) {
    val key: String get() = "$exchangeType:$token"
}

/**
 * Pure Kotlin Black-Scholes-Merton Option Greeks & Implied Volatility engine, plus
 * Indian F&O OI/PCR/Max-Pain analytics. Used alongside Angel One SmartAPI's optionGreek
 * endpoint so live Greeks are always available across NSE, BSE (SENSEX/BANKEX), MCX
 * (CRUDEOIL/NATURALGAS/GOLD), and after-hours sessions.
 */
object OptionGreeksCalculator {

    private const val DEFAULT_RISK_FREE_RATE = 0.065 // 6.5% India 91-day T-bill proxy
    private const val SQRT_2_PI = 2.5066282746310002
    private const val MIN_TIME_YEARS = 1.0 / (365.0 * 24.0) // 1 hour floor to prevent div-by-zero on expiry

    fun computeGreeks(
        spot: Double,
        strike: Double,
        timeToExpiryYears: Double,
        optionPrice: Double,
        isCall: Boolean,
        riskFreeRate: Double = DEFAULT_RISK_FREE_RATE,
        fallbackIvDecimal: Double = 0.18
    ): OptionGreeks {
        if (spot <= 0.0 || strike <= 0.0) {
            return OptionGreeks(0.0, if (isCall) 0.5 else -0.5, 0.0, 0.0, 0.0, isBrokerSupplied = false)
        }
        val t = max(timeToExpiryYears, MIN_TIME_YEARS)
        val solvedIv = if (optionPrice > 0.0) {
            solveImpliedVolatility(spot, strike, t, riskFreeRate, optionPrice, isCall)
                ?: fallbackIvDecimal
        } else {
            fallbackIvDecimal
        }
        val sigma = solvedIv.coerceIn(0.02, 3.0)
        return blackScholesGreeks(spot, strike, t, riskFreeRate, sigma, isCall)
    }

    fun blackScholesPrice(
        spot: Double,
        strike: Double,
        t: Double,
        r: Double,
        sigma: Double,
        isCall: Boolean
    ): Double {
        val sqrtT = sqrt(t)
        val d1 = (ln(spot / strike) + (r + 0.5 * sigma * sigma) * t) / (sigma * sqrtT)
        val d2 = d1 - sigma * sqrtT
        val discount = exp(-r * t)
        return if (isCall) {
            spot * normCdf(d1) - strike * discount * normCdf(d2)
        } else {
            strike * discount * normCdf(-d2) - spot * normCdf(-d1)
        }
    }

    fun blackScholesGreeks(
        spot: Double,
        strike: Double,
        t: Double,
        r: Double,
        sigma: Double,
        isCall: Boolean
    ): OptionGreeks {
        val safeT = max(t, MIN_TIME_YEARS)
        val sqrtT = sqrt(safeT)
        val d1 = (ln(spot / strike) + (r + 0.5 * sigma * sigma) * safeT) / (sigma * sqrtT)
        val d2 = d1 - sigma * sqrtT
        val pdfD1 = normPdf(d1)
        val discount = exp(-r * safeT)

        val delta = if (isCall) normCdf(d1) else normCdf(d1) - 1.0
        val gamma = pdfD1 / (spot * sigma * sqrtT)
        // Vega per 1% move in IV (divide standard vega by 100)
        val vega = (spot * pdfD1 * sqrtT) / 100.0
        // Theta per calendar day (divide annual theta by 365)
        val annualTheta = if (isCall) {
            -(spot * pdfD1 * sigma) / (2.0 * sqrtT) - r * strike * discount * normCdf(d2)
        } else {
            -(spot * pdfD1 * sigma) / (2.0 * sqrtT) + r * strike * discount * normCdf(-d2)
        }
        val thetaPerDay = annualTheta / 365.0

        return OptionGreeks(
            ivPercent = (sigma * 100.0).coerceIn(0.0, 300.0),
            delta = delta.coerceIn(-1.0, 1.0),
            gamma = gamma.coerceAtLeast(0.0),
            thetaPerDay = thetaPerDay,
            vega = vega.coerceAtLeast(0.0),
            isBrokerSupplied = false
        )
    }

    /**
     * Hybrid Newton-Raphson + bisection Implied Volatility solver.
     * Guaranteed bounded convergence even for deep ITM / OTM options.
     */
    fun solveImpliedVolatility(
        spot: Double,
        strike: Double,
        t: Double,
        r: Double,
        marketPrice: Double,
        isCall: Boolean
    ): Double? {
        val intrinsic = if (isCall) max(0.0, spot - strike * exp(-r * t)) else max(0.0, strike * exp(-r * t) - spot)
        if (marketPrice <= intrinsic + 1e-4) return null

        var low = 0.01
        var high = 3.0
        var sigma = 0.20

        for (i in 0 until 24) {
            val price = blackScholesPrice(spot, strike, t, r, sigma, isCall)
            val diff = price - marketPrice
            if (abs(diff) < 1e-4) return sigma

            if (diff > 0.0) high = min(high, sigma) else low = max(low, sigma)

            val sqrtT = sqrt(t)
            val d1 = (ln(spot / strike) + (r + 0.5 * sigma * sigma) * t) / (sigma * sqrtT)
            val vegaRaw = spot * normPdf(d1) * sqrtT
            sigma = if (vegaRaw > 1e-6) {
                val newton = sigma - diff / vegaRaw
                if (newton > low && newton < high) newton else 0.5 * (low + high)
            } else {
                0.5 * (low + high)
            }
        }
        return sigma.coerceIn(0.01, 3.0)
    }

    fun parseExpiryToMillis(expiryLabel: String): Long? {
        val clean = expiryLabel.trim().uppercase(Locale.US)
        if (clean.isBlank()) return null
        val patterns = listOf("ddMMMyyyy", "dd-MMM-yyyy", "ddMMMyy", "yyyy-MM-dd")
        for (pattern in patterns) {
            val parsed = runCatching {
                SimpleDateFormat(pattern, Locale.US).apply {
                    isLenient = false
                    timeZone = TimeZone.getTimeZone("Asia/Kolkata")
                }.parse(clean)?.time
            }.getOrNull()
            if (parsed != null) {
                // Expiry settles at 15:30 IST (offset +15.5 hours from midnight IST)
                return parsed + (15L * 3600L + 30L * 60L) * 1000L
            }
        }
        return null
    }

    fun parseExpiryToYears(expiryLabel: String, nowMillis: Long = System.currentTimeMillis()): Double {
        val expiryMillis = parseExpiryToMillis(expiryLabel) ?: return 7.0 / 365.0
        val diffMillis = expiryMillis - nowMillis
        if (diffMillis <= 0L) return MIN_TIME_YEARS
        return max(diffMillis.toDouble() / (365.0 * 24.0 * 3600.0 * 1000.0), MIN_TIME_YEARS)
    }

    /**
     * Computes Max Pain strike: the strike at which aggregate option buyer payoff
     * (total Call + Put intrinsic payout weighted by OI) is minimized.
     */
    fun computeMaxPain(rows: List<OptionChainRow>): BigDecimal? {
        if (rows.isEmpty()) return null
        val hasAnyOi = rows.any { (it.call?.openInterest ?: 0L) > 0L || (it.put?.openInterest ?: 0L) > 0L }
        if (!hasAnyOi) return null

        var bestStrike: BigDecimal? = null
        var minPain = Double.MAX_VALUE
        for (candidate in rows) {
            val s = candidate.strikePrice.toDouble()
            var pain = 0.0
            for (row in rows) {
                val k = row.strikePrice.toDouble()
                val callOi = row.call?.openInterest?.toDouble() ?: 0.0
                val putOi = row.put?.openInterest?.toDouble() ?: 0.0
                if (s > k && callOi > 0.0) pain += (s - k) * callOi
                if (k > s && putOi > 0.0) pain += (k - s) * putOi
            }
            if (pain < minPain) {
                minPain = pain
                bestStrike = candidate.strikePrice
            }
        }
        return bestStrike
    }

    fun formatOiCompact(oi: Long): String {
        val absVal = abs(oi)
        val sign = if (oi < 0) "-" else ""
        return when {
            absVal == 0L -> "-"
            absVal >= 10_000_000L -> "$sign${BigDecimal(absVal).divide(BigDecimal(10_000_000L), 2, RoundingMode.HALF_UP)}Cr"
            absVal >= 100_000L -> "$sign${BigDecimal(absVal).divide(BigDecimal(100_000L), 2, RoundingMode.HALF_UP)}L"
            absVal >= 1_000L -> "$sign${BigDecimal(absVal).divide(BigDecimal(1_000L), 1, RoundingMode.HALF_UP)}K"
            else -> "$sign$absVal"
        }
    }

    private fun normPdf(x: Double): Double = exp(-0.5 * x * x) / SQRT_2_PI

    /** Abramowitz & Stegun 7.1.26 approximation for standard normal CDF (max error < 7.5e-8). */
    private fun normCdf(x: Double): Double {
        val sign = if (x < 0.0) -1.0 else 1.0
        val ax = abs(x) / sqrt(2.0)
        val t = 1.0 / (1.0 + 0.3275911 * ax)
        val poly = t * (0.254829592 +
            t * (-0.284496736 +
                t * (1.421413741 +
                    t * (-1.453152027 +
                        t * 1.061405429))))
        val erf = 1.0 - poly * exp(-ax * ax)
        return 0.5 * (1.0 + sign * erf)
    }
}
