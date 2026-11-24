package com.example.data.local

import com.example.domain.model.InstrumentType
import com.example.domain.model.MarketSegment
import com.example.domain.model.OptionType
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.sin

object SeedData {
    val INITIAL_CAPITAL = BigDecimal("1000000.00")
    val INITIAL_CRYPTO_CAPITAL = BigDecimal("10000.00")

    fun defaultAccount(): AccountStateEntity = AccountStateEntity(
        id = 1,
        virtualCapital = INITIAL_CAPITAL,
        availableMargin = INITIAL_CAPITAL,
        realisedPnl = BigDecimal.ZERO.setScale(2),
        cryptoVirtualCapital = INITIAL_CRYPTO_CAPITAL,
        cryptoAvailableMargin = INITIAL_CRYPTO_CAPITAL,
        cryptoRealisedPnl = BigDecimal.ZERO.setScale(2)
    )

    fun seedInstruments(): List<InstrumentEntity> = listOf(
        // NSE Stocks
        instrument(
            symbol = "RELIANCE",
            displayName = "Reliance Industries Ltd",
            segment = MarketSegment.NSE,
            type = InstrumentType.STOCK,
            lotSize = 1,
            tickSize = "0.05",
            ltp = "1284.50",
            prevClose = "1268.20",
            volume = 8_420_110L
        ),
        instrument(
            symbol = "TCS",
            displayName = "Tata Consultancy Services",
            segment = MarketSegment.NSE,
            type = InstrumentType.STOCK,
            lotSize = 1,
            tickSize = "0.05",
            ltp = "4142.80",
            prevClose = "4165.00",
            volume = 2_190_430L
        ),
        instrument(
            symbol = "HDFCBANK",
            displayName = "HDFC Bank Ltd",
            segment = MarketSegment.NSE,
            type = InstrumentType.STOCK,
            lotSize = 1,
            tickSize = "0.05",
            ltp = "1742.35",
            prevClose = "1725.90",
            volume = 11_340_820L
        ),
        instrument(
            symbol = "TATAMOTORS",
            displayName = "Tata Motors Ltd",
            segment = MarketSegment.NSE,
            type = InstrumentType.STOCK,
            lotSize = 1,
            tickSize = "0.05",
            ltp = "812.60",
            prevClose = "798.40",
            volume = 9_512_000L
        ),
        // NSE Futures & Options
        instrument(
            symbol = "NIFTY26NOVFUT",
            displayName = "NIFTY 50 Nov Futures",
            segment = MarketSegment.NSE,
            type = InstrumentType.FUTURES,
            lotSize = 25,
            tickSize = "0.05",
            ltp = "24468.50",
            prevClose = "24355.00",
            volume = 4_850_200L,
            expiryLabel = "26 Nov 2026"
        ),
        instrument(
            symbol = "BANKNIFTY26NOVFUT",
            displayName = "BANK NIFTY Nov Futures",
            segment = MarketSegment.NSE,
            type = InstrumentType.FUTURES,
            lotSize = 15,
            tickSize = "0.05",
            ltp = "52140.00",
            prevClose = "51910.25",
            volume = 3_210_900L,
            expiryLabel = "26 Nov 2026"
        ),
        instrument(
            symbol = "NIFTY26NOV24500CE",
            displayName = "NIFTY 24500 Call Nov",
            segment = MarketSegment.NSE,
            type = InstrumentType.OPTIONS,
            lotSize = 25,
            tickSize = "0.05",
            ltp = "164.25",
            prevClose = "142.80",
            volume = 18_420_000L,
            expiryLabel = "26 Nov 2026",
            strikePrice = "24500.00",
            optionType = OptionType.CE
        ),
        instrument(
            symbol = "BANKNIFTY26NOV52000PE",
            displayName = "BANKNIFTY 52000 Put Nov",
            segment = MarketSegment.NSE,
            type = InstrumentType.OPTIONS,
            lotSize = 15,
            tickSize = "0.05",
            ltp = "298.40",
            prevClose = "334.10",
            volume = 12_910_500L,
            expiryLabel = "26 Nov 2026",
            strikePrice = "52000.00",
            optionType = OptionType.PE
        ),

        // BSE Stocks, Futures & Options
        instrument(
            symbol = "BSE:SBIN",
            displayName = "State Bank of India (BSE)",
            segment = MarketSegment.BSE,
            type = InstrumentType.STOCK,
            lotSize = 1,
            tickSize = "0.05",
            ltp = "836.90",
            prevClose = "829.15",
            volume = 1_842_000L
        ),
        instrument(
            symbol = "BSE:BHARTIARTL",
            displayName = "Bharti Airtel Ltd (BSE)",
            segment = MarketSegment.BSE,
            type = InstrumentType.STOCK,
            lotSize = 1,
            tickSize = "0.05",
            ltp = "1612.40",
            prevClose = "1624.80",
            volume = 795_400L
        ),
        instrument(
            symbol = "SENSEX26NOVFUT",
            displayName = "SENSEX Nov Futures",
            segment = MarketSegment.BSE,
            type = InstrumentType.FUTURES,
            lotSize = 10,
            tickSize = "0.05",
            ltp = "80312.00",
            prevClose = "79945.50",
            volume = 910_400L,
            expiryLabel = "27 Nov 2026"
        ),
        instrument(
            symbol = "SENSEX26NOV80000CE",
            displayName = "SENSEX 80000 Call Nov",
            segment = MarketSegment.BSE,
            type = InstrumentType.OPTIONS,
            lotSize = 10,
            tickSize = "0.05",
            ltp = "442.75",
            prevClose = "389.00",
            volume = 4_230_100L,
            expiryLabel = "27 Nov 2026",
            strikePrice = "80000.00",
            optionType = OptionType.CE
        ),

        // CRYPTO Spot, Perpetual Futures & Options
        instrument(
            symbol = "BTCUSDT",
            displayName = "Bitcoin / Tether Spot",
            segment = MarketSegment.CRYPTO,
            type = InstrumentType.STOCK,
            lotSize = 1,
            tickSize = "0.50",
            ltp = "91420.50",
            prevClose = "89850.00",
            volume = 48_210L
        ),
        instrument(
            symbol = "ETHUSDT",
            displayName = "Ethereum / Tether Spot",
            segment = MarketSegment.CRYPTO,
            type = InstrumentType.STOCK,
            lotSize = 1,
            tickSize = "0.10",
            ltp = "3184.20",
            prevClose = "3120.80",
            volume = 312_450L
        ),
        instrument(
            symbol = "SOLUSDT",
            displayName = "Solana / Tether Spot",
            segment = MarketSegment.CRYPTO,
            type = InstrumentType.STOCK,
            lotSize = 1,
            tickSize = "0.05",
            ltp = "236.45",
            prevClose = "241.10",
            volume = 1_450_000L
        ),
        instrument(
            symbol = "BTCUSDT-PERP",
            displayName = "Bitcoin Perpetual Futures",
            segment = MarketSegment.CRYPTO,
            type = InstrumentType.FUTURES,
            lotSize = 1,
            tickSize = "1.00",
            ltp = "91510.00",
            prevClose = "89910.00",
            volume = 92_100L,
            expiryLabel = "PERP"
        ),
        instrument(
            symbol = "BTC-26NOV-95000C",
            displayName = "BTC 95000 Call Option",
            segment = MarketSegment.CRYPTO,
            type = InstrumentType.OPTIONS,
            lotSize = 1,
            tickSize = "0.50",
            ltp = "1480.00",
            prevClose = "1295.00",
            volume = 6_420L,
            expiryLabel = "26 Nov 2026",
            strikePrice = "95000.00",
            optionType = OptionType.CE
        ),

        // MCX Commodities Futures & Options
        instrument(
            symbol = "GOLD26DECFUT",
            displayName = "MCX Gold Dec Futures",
            segment = MarketSegment.MCX,
            type = InstrumentType.FUTURES,
            lotSize = 1,
            tickSize = "1.00",
            ltp = "75840.00",
            prevClose = "75410.00",
            volume = 18_340L,
            expiryLabel = "05 Dec 2026"
        ),
        instrument(
            symbol = "SILVERM26DECFUT",
            displayName = "MCX Silver Mini Dec Fut",
            segment = MarketSegment.MCX,
            type = InstrumentType.FUTURES,
            lotSize = 5,
            tickSize = "1.00",
            ltp = "90450.00",
            prevClose = "91120.00",
            volume = 24_800L,
            expiryLabel = "30 Dec 2026"
        ),
        instrument(
            symbol = "CRUDEOIL26NOVFUT",
            displayName = "MCX Crude Oil Nov Fut",
            segment = MarketSegment.MCX,
            type = InstrumentType.FUTURES,
            lotSize = 100,
            tickSize = "1.00",
            ltp = "5842.00",
            prevClose = "5788.00",
            volume = 67_900L,
            expiryLabel = "19 Nov 2026"
        ),
        instrument(
            symbol = "CRUDEOIL26NOV5900CE",
            displayName = "Crude Oil 5900 Call Nov",
            segment = MarketSegment.MCX,
            type = InstrumentType.OPTIONS,
            lotSize = 100,
            tickSize = "0.10",
            ltp = "112.40",
            prevClose = "96.80",
            volume = 31_200L,
            expiryLabel = "19 Nov 2026",
            strikePrice = "5900.00",
            optionType = OptionType.CE
        ),
        instrument(
            symbol = "ZINC26NOVSTOCK",
            displayName = "MCX Copper Mini Spot Proxy",
            segment = MarketSegment.MCX,
            type = InstrumentType.STOCK,
            lotSize = 1,
            tickSize = "0.05",
            ltp = "818.25",
            prevClose = "814.00",
            volume = 14_500L,
            supportsCarryForward = false // Demonstrates intraday-only rule where product conversion is disabled
        )
    )

    private fun instrument(
        symbol: String,
        displayName: String,
        segment: MarketSegment,
        type: InstrumentType,
        lotSize: Int,
        tickSize: String,
        ltp: String,
        prevClose: String,
        volume: Long,
        expiryLabel: String? = null,
        strikePrice: String? = null,
        optionType: OptionType? = null,
        supportsCarryForward: Boolean = true
    ): InstrumentEntity {
        val ltpBd = BigDecimal(ltp)
        val prevBd = BigDecimal(prevClose)
        val high = ltpBd.max(prevBd).multiply(BigDecimal("1.006")).setScale(2, RoundingMode.HALF_UP)
        val low = ltpBd.min(prevBd).multiply(BigDecimal("0.994")).setScale(2, RoundingMode.HALF_UP)
        val spark = generateInitialSparkline(prevBd, ltpBd, symbol.hashCode())
        return InstrumentEntity(
            symbol = symbol,
            displayName = displayName,
            segment = segment,
            type = type,
            lotSize = lotSize,
            tickSize = BigDecimal(tickSize),
            ltp = ltpBd,
            previousClose = prevBd,
            dayHigh = high,
            dayLow = low,
            volume = volume,
            expiryLabel = expiryLabel,
            strikePrice = strikePrice?.let { BigDecimal(it) },
            optionType = optionType,
            supportsIntraday = true,
            supportsCarryForward = supportsCarryForward,
            sparkline = spark,
            lastTickDirection = if (ltpBd >= prevBd) 1 else -1
        )
    }

    private fun generateInitialSparkline(start: BigDecimal, end: BigDecimal, seed: Int): List<BigDecimal> {
        val count = 18
        val points = mutableListOf<BigDecimal>()
        for (i in 0 until count) {
            val t = i.toDouble() / (count - 1)
            val base = start.toDouble() + (end.toDouble() - start.toDouble()) * t
            val wave = sin(i * 0.85 + (seed % 7)) * (start.toDouble() * 0.0025)
            val value = if (i == count - 1) end.toDouble() else base + wave
            points.add(BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP))
        }
        return points
    }
}
