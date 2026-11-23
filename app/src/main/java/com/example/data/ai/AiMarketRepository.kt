package com.example.data.ai

import android.content.Context
import android.util.Log
import com.example.domain.engine.MarketClock
import com.example.domain.engine.RiskMath
import com.example.domain.model.AccountState
import com.example.domain.model.Instrument
import com.example.domain.model.OrderSide
import com.example.domain.model.Position
import com.example.domain.model.ProductType
import com.example.domain.model.TradeJournalEntry
import com.example.domain.signal.AiSignalBias
import com.example.domain.signal.AiSignalSecondOpinion
import com.example.domain.signal.AiSignalVerdict
import com.example.domain.signal.AiUiState
import com.example.domain.signal.Candle
import com.example.domain.signal.TradeSignal
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.math.BigDecimal
import javax.inject.Inject
import javax.inject.Singleton

interface AiMarketRepository {
    suspend fun analyzeTradeSignal(
        signal: TradeSignal,
        instrument: Instrument?,
        recentCandles: List<Candle>
    ): Result<AiSignalSecondOpinion>

    suspend fun analyzeInstrumentSetup(
        instrument: Instrument,
        side: OrderSide,
        productType: ProductType,
        entryPrice: BigDecimal,
        slPrice: BigDecimal?,
        targetPrice: BigDecimal?,
        activeSignals: List<TradeSignal>,
        recentCandles: List<Candle>
    ): Result<AiSignalSecondOpinion>

    fun streamPortfolioCoach(
        question: String,
        account: AccountState,
        positions: List<Position>,
        instruments: List<Instrument>,
        journalEntries: List<TradeJournalEntry>,
        activeSignals: List<TradeSignal>
    ): Flow<AiUiState<String>>
}

@Singleton
class FirebaseAiMarketRepository @Inject constructor(
    @ApplicationContext private val context: Context
) : AiMarketRepository {

    private val systemPrompt = """
        You are PaperPulse Quant Analyst, an objective quantitative trading & risk-control copilot for a paper-trading simulator covering Indian markets (NSE, BSE, MCX) and Crypto.
        Strict rules:
        - Paper trading advisory only. Never claim to place live broker orders or guarantee profits.
        - Focus on risk-reward asymmetry, invalidation levels, Trailing Stop Loss (TSL), Trailing Target, OCO brackets, and session constraints (NSE/BSE 15:15 IST intraday square-off vs 24/7 Crypto).
        - Be concise, direct, and grounded strictly in the provided prices, candles, signals, and journal figures.
    """.trimIndent()

    private val secondOpinionSchema = Schema.obj(
        properties = mapOf(
            "verdict" to Schema.enumeration(
                values = listOf("ALIGNED", "CAUTION", "CONFLICTING"),
                description = "Overall setup alignment assessment"
            ),
            "bias" to Schema.enumeration(
                values = listOf("BULLISH", "BEARISH", "NEUTRAL"),
                description = "Directional bias from recent price action and signal confluence"
            ),
            "confidenceScore" to Schema.integer(
                description = "Integer score from 0 to 100 reflecting setup clarity and R:R quality"
            ),
            "summary" to Schema.string(
                description = "2-3 sentence concise thesis evaluating momentum, R:R, and confluence"
            ),
            "keyRiskInvalidation" to Schema.string(
                description = "Exact price or market condition that invalidates this setup"
            ),
            "executionGuidance" to Schema.string(
                description = "Actionable paper-trading risk guidance (SL/TSL/Trailing Target/product fit)"
            ),
            "riskChecklist" to Schema.array(
                items = Schema.string(),
                description = "3 short bullet items to verify before confirming the order"
            )
        )
    )

    private val structuredModel by lazy {
        Firebase.ai.generativeModel(
            modelName = MODEL_NAME,
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = secondOpinionSchema
                temperature = 0.25f
            },
            systemInstruction = content { text(systemPrompt) }
        )
    }

    private val coachModel by lazy {
        Firebase.ai.generativeModel(
            modelName = MODEL_NAME,
            generationConfig = generationConfig {
                temperature = 0.4f
            },
            systemInstruction = content { text(systemPrompt) }
        )
    }

    override suspend fun analyzeTradeSignal(
        signal: TradeSignal,
        instrument: Instrument?,
        recentCandles: List<Candle>
    ): Result<AiSignalSecondOpinion> {
        if (!ensureFirebaseInitialized(context)) {
            return Result.failure(IllegalStateException(FIREBASE_NOT_INITIALIZED_MSG))
        }
        return try {
            val prompt = buildSignalPrompt(signal, instrument, recentCandles)
            val response = withTimeout(TIMEOUT_MS) {
                structuredModel.generateContent(prompt)
            }
            val rawJson = response.text
                ?: return Result.failure(IllegalStateException("Gemini returned an empty response."))
            val parsed = parseSecondOpinionJson(
                rawJson = rawJson,
                referenceId = signal.id,
                symbol = signal.symbol
            )
            Result.success(parsed)
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Signal analysis timed out", e)
            Result.failure(IllegalStateException("AI analysis timed out after 60s. Please retry."))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Firebase AI signal analysis failed", e)
            Result.failure(IllegalStateException(e.localizedMessage ?: "AI analysis failed."))
        }
    }

    override suspend fun analyzeInstrumentSetup(
        instrument: Instrument,
        side: OrderSide,
        productType: ProductType,
        entryPrice: BigDecimal,
        slPrice: BigDecimal?,
        targetPrice: BigDecimal?,
        activeSignals: List<TradeSignal>,
        recentCandles: List<Candle>
    ): Result<AiSignalSecondOpinion> {
        if (!ensureFirebaseInitialized(context)) {
            return Result.failure(IllegalStateException(FIREBASE_NOT_INITIALIZED_MSG))
        }
        return try {
            val prompt = buildSetupAuditPrompt(
                instrument = instrument,
                side = side,
                productType = productType,
                entryPrice = entryPrice,
                slPrice = slPrice,
                targetPrice = targetPrice,
                activeSignals = activeSignals,
                recentCandles = recentCandles
            )
            val response = withTimeout(TIMEOUT_MS) {
                structuredModel.generateContent(prompt)
            }
            val rawJson = response.text
                ?: return Result.failure(IllegalStateException("Gemini returned an empty response."))
            val parsed = parseSecondOpinionJson(
                rawJson = rawJson,
                referenceId = "setup_${instrument.symbol}_${side.name}",
                symbol = instrument.symbol
            )
            Result.success(parsed)
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Setup audit timed out", e)
            Result.failure(IllegalStateException("AI setup audit timed out after 60s. Please retry."))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Firebase AI setup audit failed", e)
            Result.failure(IllegalStateException(e.localizedMessage ?: "AI setup audit failed."))
        }
    }

    override fun streamPortfolioCoach(
        question: String,
        account: AccountState,
        positions: List<Position>,
        instruments: List<Instrument>,
        journalEntries: List<TradeJournalEntry>,
        activeSignals: List<TradeSignal>
    ): Flow<AiUiState<String>> = flow {
        if (!ensureFirebaseInitialized(context)) {
            emit(AiUiState.Error(FIREBASE_NOT_INITIALIZED_MSG))
            return@flow
        }
        emit(AiUiState.Loading)
        val prompt = buildCoachPrompt(
            question = question,
            account = account,
            positions = positions,
            instruments = instruments,
            journalEntries = journalEntries,
            activeSignals = activeSignals
        )
        val acc = StringBuilder()
        try {
            withTimeout(TIMEOUT_MS) {
                coachModel.generateContentStream(prompt).collect { chunk ->
                    val piece = chunk.text.orEmpty()
                    if (piece.isNotEmpty()) {
                        acc.append(piece)
                        emit(AiUiState.Streaming(acc.toString()))
                    }
                }
            }
            if (acc.isNotBlank()) {
                emit(AiUiState.Success(acc.toString()))
            } else {
                emit(AiUiState.Error("Gemini returned an empty response."))
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Coach stream timed out", e)
            emit(AiUiState.Error("AI Coach timed out after 60s. Please retry."))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Firebase AI coach failed", e)
            emit(AiUiState.Error(e.localizedMessage ?: "AI Coach request failed."))
        }
    }

    private fun buildSignalPrompt(
        signal: TradeSignal,
        instrument: Instrument?,
        recentCandles: List<Candle>
    ): String {
        val candleLines = recentCandles.takeLast(12).joinToString("\n") { c ->
            "O=%.2f H=%.2f L=%.2f C=%.2f V=%.0f".format(c.open, c.high, c.low, c.close, c.volume)
        }.ifBlank { "No recent candles buffered yet." }

        return buildString {
            appendLine("Audit this advisory trade signal for paper-trading execution:")
            appendLine("Symbol: ${signal.symbol} (${signal.segment.label})")
            appendLine("Strategy: ${signal.strategy.label} | Timeframe: ${signal.timeframe.label}")
            appendLine("Direction: ${signal.direction.name} | Quantitative Confidence: ${signal.confidence.label}")
            appendLine("Entry: ${RiskMath.formatPrice(signal.entryPrice, signal.segment)}")
            appendLine("Stop Loss: ${RiskMath.formatPrice(signal.stopLoss, signal.segment)}")
            appendLine("Target 1: ${RiskMath.formatPrice(signal.target1, signal.segment)} | Target 2: ${RiskMath.formatPrice(signal.target2, signal.segment)}")
            appendLine("Risk:Reward = 1:%.2f".format(signal.riskRewardRatio))
            appendLine("Triggers: ${signal.reasons.joinToString("; ")}")
            if (instrument != null) {
                appendLine("Live Quote: LTP=${RiskMath.formatPrice(instrument.ltp, instrument.segment)}, DayH=${instrument.dayHigh}, DayL=${instrument.dayLow}, Change=${instrument.changePercent}%")
            }
            appendLine("Recent Candles (oldest->newest):")
            appendLine(candleLines)
        }
    }

    private fun buildSetupAuditPrompt(
        instrument: Instrument,
        side: OrderSide,
        productType: ProductType,
        entryPrice: BigDecimal,
        slPrice: BigDecimal?,
        targetPrice: BigDecimal?,
        activeSignals: List<TradeSignal>,
        recentCandles: List<Candle>
    ): String {
        val candleSummary = recentCandles.takeLast(10).joinToString(" | ") { c ->
            "%.2f->%.2f(H%.2f/L%.2f)".format(c.open, c.close, c.high, c.low)
        }.ifBlank { "Sparkline: ${instrument.sparkline.takeLast(8).joinToString(",")}" }

        val signalSummary = activeSignals.take(3).joinToString("\n") { s ->
            "- ${s.strategy.shortLabel} (${s.timeframe.label}): ${s.direction.name} Entry=${s.entryPrice} SL=${s.stopLoss} T1=${s.target1} (${s.confidence.label})"
        }.ifBlank { "No active strategy signals for ${instrument.symbol}." }

        return buildString {
            appendLine("Perform a pre-trade risk & setup audit on this draft paper order:")
            appendLine("Instrument: ${instrument.symbol} (${instrument.displayName}) | Segment: ${instrument.segment.label} | Type: ${instrument.type.fullLabel}")
            appendLine("LTP: ${RiskMath.formatPrice(instrument.ltp, instrument.segment)} | Day Range: ${instrument.dayLow} - ${instrument.dayHigh} (${instrument.changePercent}%)")
            appendLine("Draft Side: ${side.name} | Product: ${productType.label} | Lot Size: ${instrument.lotSize}")
            appendLine("Planned Entry: ${RiskMath.formatPrice(entryPrice, instrument.segment)}")
            appendLine("Planned SL: ${slPrice?.let { RiskMath.formatPrice(it, instrument.segment) } ?: "NONE SET (High Risk)"}")
            appendLine("Planned Target: ${targetPrice?.let { RiskMath.formatPrice(it, instrument.segment) } ?: "NONE SET"}")
            appendLine("IST Clock: ${MarketClock.formatCurrentIstClock()}")
            appendLine("Active Engine Signals:\n$signalSummary")
            appendLine("Recent Price Action: $candleSummary")
        }
    }

    private fun buildCoachPrompt(
        question: String,
        account: AccountState,
        positions: List<Position>,
        instruments: List<Instrument>,
        journalEntries: List<TradeJournalEntry>,
        activeSignals: List<TradeSignal>
    ): String {
        val ltpMap = instruments.associate { it.symbol to it.ltp }
        val posText = positions.take(10).joinToString("\n") { p ->
            val ltp = ltpMap[p.symbol] ?: p.averageEntryPrice
            val pnl = RiskMath.calculateUnrealisedPnl(p.averageEntryPrice, ltp, p.quantity, p.side == OrderSide.BUY)
            "- ${p.symbol} (${p.segment.label}) ${p.side.name} ${p.quantity} [${p.productType.shortCode}] Avg=${p.averageEntryPrice} LTP=$ltp PnL=$pnl SL=${p.activeSlPrice ?: "None"} Tgt=${p.activeTargetPrice ?: "None"} TSL=${p.isTslEnabled}"
        }.ifBlank { "No open positions." }

        val journalText = journalEntries.take(12).joinToString("\n") { j ->
            "- ${j.symbol} (${j.segment.label}) ${j.side.name} ${j.quantity}: Entry=${j.entryPrice} Exit=${j.exitPrice} NetPnL=${j.netPnl} Reason=${j.exitReason.label} Note=${j.notes.ifBlank { "-" }}"
        }.ifBlank { "No closed journal trades yet." }

        val signalText = activeSignals.take(6).joinToString("\n") { s ->
            "- ${s.symbol} ${s.direction.name} ${s.strategy.shortLabel} (${s.timeframe.label}) Conf=${s.confidence.label} R:R=1:${"%.1f".format(s.riskRewardRatio)}"
        }.ifBlank { "No active signals." }

        return buildString {
            appendLine("User Request: $question")
            appendLine("\nAccount Snapshot: Available Margin=${RiskMath.formatPortfolioInr(account.availableMargin)}, Realised PnL=${RiskMath.formatPortfolioInr(account.realisedPnl, signed = true)}, IST=${MarketClock.formatCurrentIstClock()}")
            appendLine("\nOpen Positions:\n$posText")
            appendLine("\nRecent Closed Trades:\n$journalText")
            appendLine("\nTop Active Signals:\n$signalText")
            appendLine("\nRespond in concise bullet points with specific numbers from the data above and clear risk-management advice.")
        }
    }

    companion object {
        private const val TAG = "FirebaseAiMarketRepo"
        const val MODEL_NAME = "gemini-3.8-flash"
        private const val TIMEOUT_MS = 60_000L
        private const val FIREBASE_NOT_INITIALIZED_MSG =
            "Firebase is not configured in this APK build (missing app/google-services.json). Build the APK from AI Studio or configure the GOOGLE_SERVICES_JSON GitHub Actions secret to enable Gemini AI."

        internal fun parseSecondOpinionJson(
            rawJson: String,
            referenceId: String,
            symbol: String
        ): AiSignalSecondOpinion {
            val json = JSONObject(rawJson)
            val verdict = runCatching {
                AiSignalVerdict.valueOf(json.optString("verdict", "CAUTION").uppercase())
            }.getOrDefault(AiSignalVerdict.CAUTION)
            val bias = runCatching {
                AiSignalBias.valueOf(json.optString("bias", "NEUTRAL").uppercase())
            }.getOrDefault(AiSignalBias.NEUTRAL)
            val confidenceScore = json.optInt("confidenceScore", 50).coerceIn(0, 100)
            val summary = json.optString("summary").ifBlank { "No summary returned." }
            val keyInvalidation = json.optString("keyRiskInvalidation").ifBlank { "Monitor stop-loss level." }
            val guidance = json.optString("executionGuidance").ifBlank { "Verify SL and position sizing manually." }
            val checklistArray = json.optJSONArray("riskChecklist")
            val checklist = if (checklistArray != null) {
                (0 until checklistArray.length())
                    .mapNotNull { idx -> checklistArray.optString(idx).takeIf { it.isNotBlank() } }
            } else {
                emptyList()
            }

            return AiSignalSecondOpinion(
                referenceId = referenceId,
                symbol = symbol,
                verdict = verdict,
                bias = bias,
                confidenceScore = confidenceScore,
                summary = summary,
                keyRiskInvalidation = keyInvalidation,
                executionGuidance = guidance,
                riskChecklist = checklist
            )
        }
    }
}
