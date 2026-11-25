package com.example.domain.voice

import com.example.domain.model.Instrument
import com.example.domain.model.OrderPlacementRequest
import com.example.domain.model.OrderSide
import com.example.domain.model.OrderType
import com.example.domain.model.ProductType
import com.example.domain.model.RiskInputMode
import com.example.domain.model.ValidityType
import java.math.BigDecimal
import java.util.Locale

data class ParsedVoiceOrder(
    val rawTranscript: String,
    val instrument: Instrument,
    val side: OrderSide,
    val quantity: Int,
    val lotsRequested: Int?,
    val productType: ProductType,
    val orderType: OrderType,
    val limitPrice: BigDecimal? = null,
    val stopLossPrice: BigDecimal? = null,
    val targetPrice: BigDecimal? = null
) {
    fun toPlacementRequest(): OrderPlacementRequest {
        val hasBracket = stopLossPrice != null || targetPrice != null
        val effectiveType = when {
            hasBracket && orderType == OrderType.MARKET -> OrderType.BRACKET
            else -> orderType
        }
        val validity = if (productType == ProductType.CARRY_FORWARD) ValidityType.GTT else ValidityType.DAY
        return OrderPlacementRequest(
            symbol = instrument.symbol,
            side = side,
            orderType = effectiveType,
            productType = productType,
            quantity = quantity,
            limitPrice = limitPrice,
            triggerPrice = null,
            slEnabled = stopLossPrice != null,
            slMode = RiskInputMode.PRICE,
            slValue = stopLossPrice,
            targetEnabled = targetPrice != null,
            targetMode = RiskInputMode.PRICE,
            targetValue = targetPrice,
            validityType = validity,
            gttDays = 7
        )
    }
}

sealed interface VoiceOrderParseResult {
    data class Success(val order: ParsedVoiceOrder) : VoiceOrderParseResult
    data class Failure(val reason: String, val rawTranscript: String) : VoiceOrderParseResult
}

/**
 * Deterministic natural-language parser for spoken trading commands such as:
 * - "Buy 5 lots of Reliance"
 * - "Sell 2 lots of Nifty intraday"
 * - "Buy 10 shares of TCS at 4100"
 * - "Sell 1 BTCUSDT stop loss 88000 target 95000"
 */
object VoiceOrderCommandParser {

    private val numberWords = mapOf(
        "one" to "1",
        "two" to "2",
        "to" to "2",
        "three" to "3",
        "four" to "4",
        "for" to "4",
        "five" to "5",
        "six" to "6",
        "seven" to "7",
        "eight" to "8",
        "nine" to "9",
        "ten" to "10",
        "eleven" to "11",
        "twelve" to "12",
        "fifteen" to "15",
        "twenty" to "20",
        "twenty five" to "25",
        "fifty" to "50",
        "hundred" to "100"
    )

    private val aliasMap = mapOf(
        "reliance" to "RELIANCE",
        "ril" to "RELIANCE",
        "tcs" to "TCS",
        "tata consultancy" to "TCS",
        "hdfc" to "HDFCBANK",
        "hdfc bank" to "HDFCBANK",
        "tata motors" to "TATAMOTORS",
        "tatamotors" to "TATAMOTORS",
        "infosys" to "INFY",
        "infy" to "INFY",
        "icici" to "ICICIBANK",
        "icici bank" to "ICICIBANK",
        "sbi" to "SBIN",
        "state bank" to "SBIN",
        "bank nifty" to "BANKNIFTY",
        "banknifty" to "BANKNIFTY",
        "nifty" to "NIFTY",
        "nifty 50" to "NIFTY",
        "sensex" to "SENSEX",
        "gold" to "GOLD",
        "silver" to "SILVER",
        "crude" to "CRUDEOIL",
        "crude oil" to "CRUDEOIL",
        "natural gas" to "NATURALGAS",
        "natgas" to "NATURALGAS",
        "bitcoin" to "BTCUSDT",
        "btc" to "BTCUSDT",
        "ethereum" to "ETHUSDT",
        "eth" to "ETHUSDT",
        "solana" to "SOLUSDT",
        "sol" to "SOLUSDT"
    )

    fun parse(
        transcript: String,
        instruments: List<Instrument>,
        fallbackSymbol: String? = null
    ): VoiceOrderParseResult {
        val raw = transcript.trim()
        if (raw.isBlank()) {
            return VoiceOrderParseResult.Failure("No voice command detected. Try saying \"Buy 5 lots of Reliance\".", raw)
        }
        if (instruments.isEmpty()) {
            return VoiceOrderParseResult.Failure("Market watchlist is still loading.", raw)
        }

        val normalized = normalizeSpokenNumbers(raw.lowercase(Locale.US))

        // 1. Detect BUY / SELL side
        val side = when {
            Regex("\\b(buy|long|purchase|bid)\\b").containsMatchIn(normalized) -> OrderSide.BUY
            Regex("\\b(sell|short|exit)\\b").containsMatchIn(normalized) -> OrderSide.SELL
            else -> return VoiceOrderParseResult.Failure(
                "Start your command with \"Buy\" or \"Sell\" (e.g., \"Buy 5 lots of Reliance\").",
                raw
            )
        }

        // 2. Match target instrument
        val instrument = matchInstrument(normalized, instruments)
            ?: fallbackSymbol?.let { sym -> instruments.firstOrNull { it.symbol.equals(sym, ignoreCase = true) } }
            ?: return VoiceOrderParseResult.Failure(
                "Could not match an instrument in \"$raw\". Mention a symbol like Reliance, TCS, Nifty, Gold, or BTC.",
                raw
            )

        // 3. Extract optional Stop Loss, Target, and Limit price before reading quantity
        val slPrice = extractPriceAfter(normalized, listOf("stop loss", "stoploss", "sl"))
        val targetPrice = extractPriceAfter(normalized, listOf("target", "tgt", "take profit", "tp"))
        val limitPrice = extractPriceAfter(normalized, listOf("limit", "at price", "at", "@"))

        // 4. Extract quantity / lots
        val lotSize = instrument.lotSize.coerceAtLeast(1)
        val lotRegex = Regex("\\b(\\d+)\\s*(?:lot|lots)\\b")
        val unitRegex = Regex("\\b(?:buy|sell|long|short|purchase)\\s+(\\d+)\\b|\\b(\\d+)\\s*(?:share|shares|qty|quantity|unit|units|contract|contracts)\\b")

        val lotMatch = lotRegex.find(normalized)
        val (quantity, lotsRequested) = if (lotMatch != null) {
            val lots = (lotMatch.groupValues[1].toIntOrNull() ?: 1).coerceIn(1, 500)
            (lots * lotSize) to lots
        } else {
            val unitMatch = unitRegex.find(normalized)
            val rawQty = unitMatch?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() }?.toIntOrNull()
            if (rawQty != null && rawQty > 0) {
                val alignedQty = if (lotSize > 1 && rawQty < lotSize) rawQty * lotSize else rawQty
                val inferredLots = if (lotSize > 1 && alignedQty % lotSize == 0) alignedQty / lotSize else null
                alignedQty to inferredLots
            } else {
                lotSize to 1
            }
        }

        // 5. Product type (Intraday vs Carry Forward)
        val prefersCarryForward = Regex("\\b(carry forward|carry|delivery|cnc|positional|btst|overnight)\\b")
            .containsMatchIn(normalized)
        val productType = if (prefersCarryForward && instrument.supportsCarryForward) {
            ProductType.CARRY_FORWARD
        } else {
            ProductType.INTRADAY
        }

        val orderType = when {
            slPrice != null || targetPrice != null -> OrderType.BRACKET
            limitPrice != null -> OrderType.LIMIT
            else -> OrderType.MARKET
        }

        return VoiceOrderParseResult.Success(
            ParsedVoiceOrder(
                rawTranscript = raw,
                instrument = instrument,
                side = side,
                quantity = quantity.coerceAtLeast(1),
                lotsRequested = lotsRequested,
                productType = productType,
                orderType = orderType,
                limitPrice = limitPrice,
                stopLossPrice = slPrice,
                targetPrice = targetPrice
            )
        )
    }

    private fun normalizeSpokenNumbers(text: String): String {
        var out = text.replace(",", "")
        // Replace multi-word numbers first, then single words only when adjacent to trading keywords
        out = out.replace(Regex("\\btwenty\\s+five\\b"), "25")
        val qtyContextWords = setOf("one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve", "fifteen", "twenty", "fifty", "hundred")
        qtyContextWords.forEach { word ->
            val digit = numberWords[word] ?: return@forEach
            out = out.replace(Regex("\\b$word\\b(?=\\s+(?:lot|lots|share|shares|qty|quantity|unit|units|of|[a-z]))"), digit)
            out = out.replace(Regex("(?<=\\b(?:buy|sell|long|short)\\s)$word\\b"), digit)
        }
        return out
    }

    private fun extractPriceAfter(normalized: String, keywords: List<String>): BigDecimal? {
        for (kw in keywords) {
            val escaped = Regex.escape(kw)
            val match = Regex("\\b$escaped\\s+(?:of\\s+|at\\s+)?(\\d+(?:\\.\\d+)?)\\b").find(normalized)
                ?: if (kw == "@") Regex("@\\s*(\\d+(?:\\.\\d+)?)").find(normalized) else null
            val value = match?.groupValues?.getOrNull(1)?.toBigDecimalOrNull()
            if (value != null && value > BigDecimal.ZERO) {
                return value
            }
        }
        return null
    }

    private fun matchInstrument(normalized: String, instruments: List<Instrument>): Instrument? {
        // 1. Exact symbol word match (longest symbol first so BANKNIFTY beats NIFTY)
        val sortedBySymbolLen = instruments.sortedByDescending { it.symbol.length }
        for (inst in sortedBySymbolLen) {
            val symLower = inst.symbol.lowercase(Locale.US)
            if (Regex("\\b${Regex.escape(symLower)}\\b").containsMatchIn(normalized)) {
                return inst
            }
        }

        // 2. Alias match (longest alias first so "bank nifty" beats "nifty", "tata motors" beats "tata")
        val sortedAliases = aliasMap.entries.sortedByDescending { it.key.length }
        for ((alias, targetPrefix) in sortedAliases) {
            if (Regex("\\b${Regex.escape(alias)}\\b").containsMatchIn(normalized)) {
                // Prefer exact match first, then prefix match (e.g., NIFTY -> NIFTY26NOVFUT, GOLD -> GOLD...FUT)
                instruments.firstOrNull { it.symbol.equals(targetPrefix, ignoreCase = true) }?.let { return it }
                instruments.firstOrNull { it.symbol.startsWith(targetPrefix, ignoreCase = true) }?.let { return it }
            }
        }

        // 3. Display name token match
        for (inst in sortedBySymbolLen) {
            val cleanName = inst.displayName.lowercase(Locale.US)
            val firstWord = cleanName.split(" ").firstOrNull()?.takeIf { it.length >= 4 } ?: continue
            if (Regex("\\b${Regex.escape(firstWord)}\\b").containsMatchIn(normalized)) {
                return inst
            }
        }
        return null
    }
}
