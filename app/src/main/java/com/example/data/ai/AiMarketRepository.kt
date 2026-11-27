package com.example.data.ai

import android.content.Context
import android.util.Log
import com.example.domain.engine.MarketClock
import com.example.domain.engine.RiskMath
import com.example.domain.ai.AiStrategyCoachPersona
import com.example.domain.market.FiiDiiDashboardData
import com.example.domain.market.GlobalMarketQuote
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
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

enum class AiConnectionState {
    IDLE,
    CONNECTING,
    CONNECTED,
    ERROR
}

data class AiConnectionStatus(
    val state: AiConnectionState = AiConnectionState.IDLE,
    val provider: AiProvider = AiProvider.FIREBASE_AI_LOGIC,
    val activeModel: String = AiProvider.FIREBASE_AI_LOGIC.defaultModel,
    val availableModels: List<String> = AiProvider.FIREBASE_AI_LOGIC.availableModels,
    val latencyMs: Long? = null,
    val message: String = "Tap Test & Sync or enter API key to auto-connect",
    val discoveredFromApi: Boolean = false
)

interface AiMarketRepository {
    val activeConfig: StateFlow<AiProviderConfig>
    val connectionStatus: StateFlow<AiConnectionStatus>

    suspend fun autoConnectAndFetchModels(overrideConfig: AiProviderConfig? = null): AiConnectionStatus

    fun switchActiveProvider(provider: AiProvider): AiProviderConfig

    fun switchActiveModel(modelName: String): AiProviderConfig

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

    suspend fun analyzeChartStructure(
        symbol: String,
        timeframeLabel: String,
        instrument: Instrument?,
        recentCandles: List<Candle>,
        structureSummary: String
    ): Result<AiSignalSecondOpinion>

    suspend fun validateChartPattern(
        symbol: String,
        timeframeLabel: String,
        instrument: Instrument?,
        pattern: com.example.domain.analysis.DetectedChartPattern,
        recentCandles: List<Candle>
    ): Result<AiSignalSecondOpinion>

    suspend fun auditOpenPositions(
        account: AccountState,
        positions: List<Position>,
        instruments: List<Instrument>
    ): Result<AiSignalSecondOpinion>

    fun streamPortfolioCoach(
        question: String,
        account: AccountState,
        positions: List<Position>,
        instruments: List<Instrument>,
        journalEntries: List<TradeJournalEntry>,
        activeSignals: List<TradeSignal>
    ): Flow<AiUiState<String>>

    fun streamStrategyCoach(
        persona: AiStrategyCoachPersona,
        question: String,
        conversationHistory: List<Pair<Boolean, String>>,
        account: AccountState,
        positions: List<Position>,
        instruments: List<Instrument>,
        journalEntries: List<TradeJournalEntry>,
        activeSignals: List<TradeSignal>,
        globalMarkets: List<GlobalMarketQuote>,
        fiiDii: FiiDiiDashboardData?
    ): Flow<AiUiState<String>>
}

@Singleton
class FirebaseAiMarketRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val aiConfigStore: SecureAiConfigStore,
    private val okHttpClient: OkHttpClient
) : AiMarketRepository {

    override val activeConfig: StateFlow<AiProviderConfig> = aiConfigStore.config

    private val _connectionStatus = MutableStateFlow(
        AiConnectionStatus(
            state = AiConnectionState.IDLE,
            provider = aiConfigStore.getCurrent().provider,
            activeModel = aiConfigStore.getCurrent().effectiveModel,
            availableModels = aiConfigStore.getCurrent().provider.availableModels
        )
    )
    override val connectionStatus: StateFlow<AiConnectionStatus> = _connectionStatus.asStateFlow()

    private val structuredModels = ConcurrentHashMap<String, GenerativeModel>()
    private val coachModels = ConcurrentHashMap<String, GenerativeModel>()
    private val pingModels = ConcurrentHashMap<String, GenerativeModel>()
    private val discoveredModelCache = ConcurrentHashMap<AiProvider, List<String>>()
    private val connectMutex = Mutex()

    private val systemPrompt = """
        You are PaperPulse Quant Analyst, an objective quantitative trading & risk-control copilot for a paper-trading simulator covering Indian markets (NSE, BSE, MCX) and Crypto.
        Strict rules:
        - Paper trading advisory only. Never claim to place live broker orders or guarantee profits.
        - Focus on risk-reward asymmetry, invalidation levels, Trailing Stop Loss (TSL), Trailing Target, OCO brackets, and session constraints (NSE/BSE 15:15 Eq/Fut & 15:40 Opt IST vs 24/7 Crypto in USD).
        - Be concise, direct, and grounded strictly in the provided prices, candles, signals, and journal figures.
    """.trimIndent()

    private val jsonSchemaInstruction = """
        Respond ONLY with a single valid JSON object (no markdown fences) matching these exact keys:
        {
          "verdict": "ALIGNED" | "CAUTION" | "CONFLICTING",
          "bias": "BULLISH" | "BEARISH" | "NEUTRAL",
          "confidenceScore": 0-100 integer,
          "summary": "2-3 concise sentences",
          "keyRiskInvalidation": "exact price or condition invalidating setup",
          "executionGuidance": "actionable paper-trading SL/TSL/Trailing Target guidance",
          "riskChecklist": ["item 1", "item 2", "item 3"]
        }
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

    private fun resolveFirebaseModelName(requested: String): String {
        val clean = requested.trim()
        return when {
            clean.isBlank() || clean.startsWith("gemini-2.") || clean.startsWith("gemini-1.") -> MODEL_NAME
            else -> clean
        }
    }

    private fun getStructuredModel(modelName: String): GenerativeModel {
        val resolved = resolveFirebaseModelName(modelName)
        return structuredModels.getOrPut(resolved) {
            Firebase.ai.generativeModel(
                modelName = resolved,
                generationConfig = generationConfig {
                    responseMimeType = "application/json"
                    responseSchema = secondOpinionSchema
                    temperature = 0.25f
                },
                systemInstruction = content { text(systemPrompt) }
            )
        }
    }

    private fun getCoachModel(modelName: String): GenerativeModel {
        val resolved = resolveFirebaseModelName(modelName)
        return coachModels.getOrPut(resolved) {
            Firebase.ai.generativeModel(
                modelName = resolved,
                generationConfig = generationConfig {
                    temperature = 0.4f
                },
                systemInstruction = content { text(systemPrompt) }
            )
        }
    }

    private fun getPingModel(modelName: String): GenerativeModel {
        val resolved = resolveFirebaseModelName(modelName)
        return pingModels.getOrPut(resolved) {
            Firebase.ai.generativeModel(
                modelName = resolved,
                generationConfig = generationConfig {
                    temperature = 0.0f
                }
            )
        }
    }

    override fun switchActiveProvider(provider: AiProvider): AiProviderConfig {
        val next = aiConfigStore.switchProvider(provider)
        val cached = discoveredModelCache[provider] ?: provider.availableModels
        _connectionStatus.value = AiConnectionStatus(
            state = AiConnectionState.IDLE,
            provider = next.provider,
            activeModel = next.effectiveModel,
            availableModels = mergeModels(cached, next.effectiveModel, provider.availableModels),
            message = "Switched AI Agent to ${provider.label}"
        )
        return next
    }

    override fun switchActiveModel(modelName: String): AiProviderConfig {
        val next = aiConfigStore.updateActiveModel(modelName)
        val prev = _connectionStatus.value
        _connectionStatus.value = prev.copy(
            provider = next.provider,
            activeModel = next.effectiveModel,
            availableModels = mergeModels(prev.availableModels, next.effectiveModel, next.provider.availableModels)
        )
        return next
    }

    override suspend fun autoConnectAndFetchModels(overrideConfig: AiProviderConfig?): AiConnectionStatus =
        connectMutex.withLock {
            val cfg = overrideConfig ?: aiConfigStore.getCurrent()
            val provider = cfg.provider
            val fallbackModels = mergeModels(
                discoveredModelCache[provider].orEmpty(),
                cfg.effectiveModel,
                provider.availableModels
            )

            // If we're already connected to the exact same provider & model on a non-forced background check, reuse status
            val existing = _connectionStatus.value
            if (overrideConfig == null &&
                existing.state == AiConnectionState.CONNECTED &&
                existing.provider == provider &&
                existing.activeModel == cfg.effectiveModel
            ) {
                return@withLock existing
            }

            _connectionStatus.value = AiConnectionStatus(
                state = AiConnectionState.CONNECTING,
                provider = provider,
                activeModel = cfg.effectiveModel,
                availableModels = fallbackModels,
                message = "Connecting to ${provider.shortLabel} API..."
            )

            val startMs = System.currentTimeMillis()
            val result: AiConnectionStatus = try {
                if (provider == AiProvider.FIREBASE_AI_LOGIC) {
                    if (!ensureFirebaseInitialized(context)) {
                        AiConnectionStatus(
                            state = AiConnectionState.ERROR,
                            provider = provider,
                            activeModel = cfg.effectiveModel,
                            availableModels = fallbackModels,
                            message = FIREBASE_NOT_INITIALIZED_MSG
                        )
                    } else {
                        val pingReply = withTimeout(55_000L) {
                            getPingModel(cfg.effectiveModel).generateContent("Reply with OK").text
                        }
                        val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                        AiConnectionStatus(
                            state = AiConnectionState.CONNECTED,
                            provider = provider,
                            activeModel = cfg.effectiveModel,
                            availableModels = provider.availableModels,
                            latencyMs = elapsed,
                            message = "Connected to Firebase AI Logic (${cfg.effectiveModel}) • ${elapsed}ms" +
                                if (pingReply.isNullOrBlank()) "" else " • Ready",
                            discoveredFromApi = true
                        )
                    }
                } else {
                    val key = cfg.apiKey.trim()
                    if (key.isBlank()) {
                        AiConnectionStatus(
                            state = AiConnectionState.IDLE,
                            provider = provider,
                            activeModel = cfg.effectiveModel,
                            availableModels = fallbackModels,
                            message = "Enter your ${provider.shortLabel} API key to auto-connect & load live models"
                        )
                    } else {
                        val fetched = withTimeout(30_000L) { fetchModelsFromApi(cfg) }
                        val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                        val merged = mergeModels(fetched, cfg.effectiveModel, provider.availableModels)
                        discoveredModelCache[provider] = merged
                        val resolvedActive = if (cfg.modelName.isBlank() && fetched.isNotEmpty()) {
                            fetched.first()
                        } else {
                            cfg.effectiveModel
                        }
                        AiConnectionStatus(
                            state = AiConnectionState.CONNECTED,
                            provider = provider,
                            activeModel = resolvedActive,
                            availableModels = merged,
                            latencyMs = elapsed,
                            message = "Connected to ${provider.shortLabel} • ${fetched.size} live model(s) synced in ${elapsed}ms",
                            discoveredFromApi = fetched.isNotEmpty()
                        )
                    }
                }
            } catch (e: TimeoutCancellationException) {
                Log.w(TAG, "AI auto-connect timed out for ${provider.name}", e)
                AiConnectionStatus(
                    state = AiConnectionState.ERROR,
                    provider = provider,
                    activeModel = cfg.effectiveModel,
                    availableModels = fallbackModels,
                    message = "${provider.shortLabel} timed out waiting for response. Tap Test & Sync to retry or switch AI provider."
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "AI auto-connect failed for ${provider.name}", e)
                AiConnectionStatus(
                    state = AiConnectionState.ERROR,
                    provider = provider,
                    activeModel = cfg.effectiveModel,
                    availableModels = fallbackModels,
                    message = if (provider == AiProvider.FIREBASE_AI_LOGIC) friendlyAiError(e)
                    else (e.message ?: "${provider.shortLabel} connection failed.")
                )
            }

            _connectionStatus.value = result
            result
        }

    private suspend fun fetchModelsFromApi(cfg: AiProviderConfig): List<String> = withContext(Dispatchers.IO) {
        val key = cfg.apiKey.trim()
        val baseUrl = cfg.effectiveBaseUrl
        if (!baseUrl.startsWith("https://")) {
            throw IllegalStateException("AI Provider base URL must use HTTPS.")
        }

        val request: Request = when (cfg.provider) {
            AiProvider.GEMINI_API -> {
                Request.Builder()
                    .url("$baseUrl/models")
                    .header("x-goog-api-key", key)
                    .get()
                    .build()
            }
            AiProvider.CLAUDE_API -> {
                Request.Builder()
                    .url("$baseUrl/models")
                    .header("x-api-key", key)
                    .header("anthropic-version", "2023-06-01")
                    .get()
                    .build()
            }
            AiProvider.DEEPSEEK_API, AiProvider.OPENAI_COMPATIBLE -> {
                val rootUrl = baseUrl.removeSuffix("/chat/completions")
                Request.Builder()
                    .url("$rootUrl/models")
                    .header("Authorization", "Bearer $key")
                    .get()
                    .build()
            }
            AiProvider.FIREBASE_AI_LOGIC -> throw IllegalStateException("Invalid provider")
        }

        val callClient = okHttpClient.newBuilder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        callClient.newCall(request).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val errMsg = runCatching {
                    val root = JSONObject(raw)
                    root.optJSONObject("error")?.optString("message")
                        ?: root.optString("message")
                        ?: raw.take(180)
                }.getOrDefault(raw.take(180))
                throw IllegalStateException("${cfg.provider.shortLabel} HTTP ${resp.code}: ${errMsg.ifBlank { resp.message }}")
            }

            val root = JSONObject(raw)
            when (cfg.provider) {
                AiProvider.GEMINI_API -> {
                    val arr = root.optJSONArray("models") ?: JSONArray()
                    val list = mutableListOf<String>()
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val methods = obj.optJSONArray("supportedGenerationMethods")
                        var supportsGen = methods == null
                        if (methods != null) {
                            for (m in 0 until methods.length()) {
                                if (methods.optString(m) == "generateContent") {
                                    supportsGen = true
                                    break
                                }
                            }
                        }
                        val name = obj.optString("name").removePrefix("models/").trim()
                        if (supportsGen && name.startsWith("gemini", ignoreCase = true) && !name.contains("embedding", ignoreCase = true)) {
                            list.add(name)
                        }
                    }
                    list.distinct().sortedWith(
                        compareByDescending<String> { it.contains("3.") || it.contains("4") }
                            .thenByDescending { it.contains("2.5") }
                            .thenBy { it }
                    ).take(20)
                }
                AiProvider.CLAUDE_API -> {
                    val arr = root.optJSONArray("data") ?: JSONArray()
                    (0 until arr.length())
                        .mapNotNull { arr.optJSONObject(it)?.optString("id")?.trim()?.takeIf(String::isNotBlank) }
                        .distinct()
                        .take(20)
                }
                AiProvider.DEEPSEEK_API, AiProvider.OPENAI_COMPATIBLE -> {
                    val arr = root.optJSONArray("data") ?: JSONArray()
                    val allIds = (0 until arr.length())
                        .mapNotNull { arr.optJSONObject(it)?.optString("id")?.trim()?.takeIf(String::isNotBlank) }
                        .distinct()
                    val chatFiltered = allIds.filterNot { id ->
                        val low = id.lowercase()
                        low.contains("embedding") || low.contains("whisper") || low.contains("tts") ||
                            low.contains("dall-e") || low.contains("moderation") || low.contains("transcribe")
                    }
                    (chatFiltered.ifEmpty { allIds }).sorted().take(25)
                }
                AiProvider.FIREBASE_AI_LOGIC -> cfg.provider.availableModels
            }
        }
    }

    private fun mergeModels(primary: List<String>, active: String, defaults: List<String>): List<String> {
        val out = LinkedHashSet<String>()
        if (active.isNotBlank()) out.add(active)
        primary.forEach { if (it.isNotBlank()) out.add(it) }
        defaults.forEach { if (it.isNotBlank()) out.add(it) }
        return out.toList()
    }

    override suspend fun analyzeTradeSignal(
        signal: TradeSignal,
        instrument: Instrument?,
        recentCandles: List<Candle>
    ): Result<AiSignalSecondOpinion> {
        val prompt = buildSignalPrompt(signal, instrument, recentCandles)
        return runStructuredAnalysis(
            prompt = prompt,
            referenceId = signal.id,
            symbol = signal.symbol
        )
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
        return runStructuredAnalysis(
            prompt = prompt,
            referenceId = "setup_${instrument.symbol}_${side.name}",
            symbol = instrument.symbol
        )
    }

    override suspend fun analyzeChartStructure(
        symbol: String,
        timeframeLabel: String,
        instrument: Instrument?,
        recentCandles: List<Candle>,
        structureSummary: String
    ): Result<AiSignalSecondOpinion> {
        val candleLines = recentCandles.takeLast(14).joinToString("\n") { c ->
            "O=%.2f H=%.2f L=%.2f C=%.2f V=%.0f".format(c.open, c.high, c.low, c.close, c.volume)
        }.ifBlank { "Candles warming up." }

        val prompt = buildString {
            appendLine("Analyze this live TradingView chart technical structure for paper trading:")
            appendLine("Symbol: $symbol | Timeframe: $timeframeLabel | IST: ${MarketClock.formatCurrentIstClock()}")
            if (instrument != null) {
                appendLine("Segment: ${instrument.segment.label} | LTP: ${RiskMath.formatPrice(instrument.ltp, instrument.segment)} | Day Range: ${instrument.dayLow} - ${instrument.dayHigh} (${instrument.changePercent}%)")
            }
            appendLine("Detected Indicators & Structure:\n$structureSummary")
            appendLine("Recent OHLCV Candles:\n$candleLines")
            appendLine("Provide directional bias, key invalidation price, optimal SL/Target placement guidance, and a 3-point execution checklist.")
        }
        return runStructuredAnalysis(
            prompt = prompt,
            referenceId = "chart_${symbol}_$timeframeLabel",
            symbol = symbol
        )
    }

    override suspend fun validateChartPattern(
        symbol: String,
        timeframeLabel: String,
        instrument: Instrument?,
        pattern: com.example.domain.analysis.DetectedChartPattern,
        recentCandles: List<Candle>
    ): Result<AiSignalSecondOpinion> {
        val candleLines = recentCandles.takeLast(16).joinToString("\n") { c ->
            "O=%.2f H=%.2f L=%.2f C=%.2f V=%.0f".format(c.open, c.high, c.low, c.close, c.volume)
        }.ifBlank { "Candles warming up." }

        val prompt = buildString {
            appendLine("Validate this automatically detected technical chart pattern for breakout execution:")
            appendLine("Symbol: $symbol | Timeframe: $timeframeLabel | IST: ${MarketClock.formatCurrentIstClock()}")
            if (instrument != null) {
                appendLine("Segment: ${instrument.segment.label} | LTP: ${RiskMath.formatPrice(instrument.ltp, instrument.segment)} | Change: ${instrument.changePercent}%")
            }
            appendLine("Detected Pattern: ${pattern.type.displayName} (${pattern.type.categoryLabel})")
            appendLine("Direction: ${pattern.direction.name} | Status: ${pattern.status.label} | Geometric Confidence: ${pattern.confidenceScore}%")
            appendLine("Breakout Level: %.2f | Measured Target: %.2f (+%.2f%%) | Invalidation/SL: %.2f | R:R = 1:%.2f".format(
                pattern.breakoutLevel,
                pattern.targetPrice,
                pattern.projectedMovePercent,
                pattern.invalidationPrice,
                pattern.riskRewardRatio
            ))
            appendLine("Engine Note: ${pattern.summary} ${pattern.breakoutNote}")
            appendLine("Recent OHLCV Candles:\n$candleLines")
            appendLine("Evaluate pattern symmetry, false-breakout risk, volume confirmation, exact breakout trigger, and invalidation discipline.")
        }
        return runStructuredAnalysis(
            prompt = prompt,
            referenceId = "pattern_${symbol}_${pattern.id}",
            symbol = symbol
        )
    }

    override suspend fun auditOpenPositions(
        account: AccountState,
        positions: List<Position>,
        instruments: List<Instrument>
    ): Result<AiSignalSecondOpinion> {
        val ltpMap = instruments.associate { it.symbol to it.ltp }
        val posLines = positions.joinToString("\n") { p ->
            val ltp = ltpMap[p.symbol] ?: p.averageEntryPrice
            val pnl = RiskMath.calculateUnrealisedPnl(p.averageEntryPrice, ltp, p.quantity, p.side == OrderSide.BUY)
            "- ${p.symbol} (${p.segment.label}) ${p.side.name} Qty=${p.quantity} [${p.productType.shortCode}] Avg=${RiskMath.formatPrice(p.averageEntryPrice, p.segment)} LTP=${RiskMath.formatPrice(ltp, p.segment)} PnL=${RiskMath.formatSignedCurrency(pnl, p.segment)} SL=${p.activeSlPrice?.let { RiskMath.formatPrice(it, p.segment) } ?: "MISSING"} Target=${p.activeTargetPrice?.let { RiskMath.formatPrice(it, p.segment) } ?: "MISSING"} TSL=${p.isTslEnabled}"
        }.ifBlank { "No open positions." }

        val prompt = buildString {
            appendLine("Audit all currently open paper-trading positions for risk exposure & missing SL/Target:")
            appendLine("IST Clock: ${MarketClock.formatCurrentIstClock()}")
            appendLine("India Wallet: Capital=${RiskMath.formatPortfolioInr(account.virtualCapital)}, Free=${RiskMath.formatPortfolioInr(account.availableMargin)}, Realised=${RiskMath.formatPortfolioInr(account.realisedPnl, signed = true)}")
            appendLine("Crypto Wallet: Capital=${RiskMath.formatPortfolioUsd(account.cryptoVirtualCapital)}, Free=${RiskMath.formatPortfolioUsd(account.cryptoAvailableMargin)}, Realised=${RiskMath.formatPortfolioUsd(account.cryptoRealisedPnl, signed = true)}")
            appendLine("Open Positions:\n$posLines")
            appendLine("Highlight any unprotected positions missing SL or Target, suggest concrete SL/Target/TSL protection, and warn about upcoming intraday square-off.")
        }
        return runStructuredAnalysis(
            prompt = prompt,
            referenceId = "positions_audit_${positions.size}",
            symbol = "PORTFOLIO"
        )
    }

    override fun streamPortfolioCoach(
        question: String,
        account: AccountState,
        positions: List<Position>,
        instruments: List<Instrument>,
        journalEntries: List<TradeJournalEntry>,
        activeSignals: List<TradeSignal>
    ): Flow<AiUiState<String>> = flow {
        val cfg = aiConfigStore.getCurrent()
        val prompt = buildCoachPrompt(
            question = question,
            account = account,
            positions = positions,
            instruments = instruments,
            journalEntries = journalEntries,
            activeSignals = activeSignals
        )
        emit(AiUiState.Loading)

        if (cfg.provider != AiProvider.FIREBASE_AI_LOGIC) {
            try {
                val startMs = System.currentTimeMillis()
                val text = withTimeout(TIMEOUT_MS) {
                    callExternalProvider(cfg, prompt, expectJson = false)
                }
                markConnected(cfg, System.currentTimeMillis() - startMs)
                emit(AiUiState.Success(text))
            } catch (e: TimeoutCancellationException) {
                emit(AiUiState.Error("${cfg.provider.shortLabel} request timed out after 60s."))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emit(AiUiState.Error(e.message ?: "${cfg.provider.shortLabel} request failed."))
            }
            return@flow
        }

        if (!ensureFirebaseInitialized(context)) {
            emit(AiUiState.Error(FIREBASE_NOT_INITIALIZED_MSG))
            return@flow
        }
        val acc = StringBuilder()
        val startMs = System.currentTimeMillis()
        try {
            withTimeout(TIMEOUT_MS) {
                getCoachModel(cfg.effectiveModel).generateContentStream(prompt).collect { chunk ->
                    val piece = chunk.text.orEmpty()
                    if (piece.isNotEmpty()) {
                        acc.append(piece)
                        emit(AiUiState.Streaming(acc.toString()))
                    }
                }
            }
            if (acc.isNotBlank()) {
                markConnected(cfg, System.currentTimeMillis() - startMs)
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
            emit(AiUiState.Error(friendlyAiError(e)))
        }
    }

    override fun streamStrategyCoach(
        persona: AiStrategyCoachPersona,
        question: String,
        conversationHistory: List<Pair<Boolean, String>>,
        account: AccountState,
        positions: List<Position>,
        instruments: List<Instrument>,
        journalEntries: List<TradeJournalEntry>,
        activeSignals: List<TradeSignal>,
        globalMarkets: List<GlobalMarketQuote>,
        fiiDii: FiiDiiDashboardData?
    ): Flow<AiUiState<String>> = flow {
        val cfg = aiConfigStore.getCurrent()
        val prompt = buildStrategyCoachPrompt(
            persona = persona,
            question = question,
            conversationHistory = conversationHistory,
            account = account,
            positions = positions,
            instruments = instruments,
            journalEntries = journalEntries,
            activeSignals = activeSignals,
            globalMarkets = globalMarkets,
            fiiDii = fiiDii
        )
        emit(AiUiState.Loading)

        if (cfg.provider != AiProvider.FIREBASE_AI_LOGIC) {
            try {
                val startMs = System.currentTimeMillis()
                val text = withTimeout(TIMEOUT_MS) {
                    callExternalProvider(cfg, prompt, expectJson = false)
                }
                markConnected(cfg, System.currentTimeMillis() - startMs)
                emit(AiUiState.Success(text))
            } catch (e: TimeoutCancellationException) {
                emit(AiUiState.Error("${cfg.provider.shortLabel} strategy coach timed out after 60s."))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emit(AiUiState.Error(e.message ?: "${cfg.provider.shortLabel} strategy coach failed."))
            }
            return@flow
        }

        if (!ensureFirebaseInitialized(context)) {
            emit(AiUiState.Error(FIREBASE_NOT_INITIALIZED_MSG))
            return@flow
        }

        val acc = StringBuilder()
        val startMs = System.currentTimeMillis()
        try {
            withTimeout(TIMEOUT_MS) {
                getCoachModel(cfg.effectiveModel).generateContentStream(prompt).collect { chunk ->
                    val piece = chunk.text.orEmpty()
                    if (piece.isNotEmpty()) {
                        acc.append(piece)
                        emit(AiUiState.Streaming(acc.toString()))
                    }
                }
            }
            if (acc.isNotBlank()) {
                markConnected(cfg, System.currentTimeMillis() - startMs)
                emit(AiUiState.Success(acc.toString()))
            } else {
                emit(AiUiState.Error("Gemini returned an empty coaching response."))
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Strategy coach stream timed out", e)
            emit(AiUiState.Error("AI Strategy Coach timed out after 60s. Please retry."))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Firebase AI strategy coach failed", e)
            emit(AiUiState.Error(friendlyAiError(e)))
        }
    }

    private suspend fun runStructuredAnalysis(
        prompt: String,
        referenceId: String,
        symbol: String
    ): Result<AiSignalSecondOpinion> {
        val cfg = aiConfigStore.getCurrent()
        val startMs = System.currentTimeMillis()
        if (cfg.provider != AiProvider.FIREBASE_AI_LOGIC) {
            return try {
                val rawText = withTimeout(TIMEOUT_MS) {
                    callExternalProvider(cfg, "$prompt\n\n$jsonSchemaInstruction", expectJson = true)
                }
                val parsed = parseSecondOpinionJson(
                    rawJson = extractJsonObject(rawText),
                    referenceId = referenceId,
                    symbol = symbol
                )
                markConnected(cfg, System.currentTimeMillis() - startMs)
                Result.success(parsed)
            } catch (e: TimeoutCancellationException) {
                Result.failure(IllegalStateException("${cfg.provider.shortLabel} analysis timed out after 60s."))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "${cfg.provider.shortLabel} analysis failed", e)
                Result.failure(IllegalStateException(e.message ?: "${cfg.provider.shortLabel} analysis failed."))
            }
        }

        if (!ensureFirebaseInitialized(context)) {
            return Result.failure(IllegalStateException(FIREBASE_NOT_INITIALIZED_MSG))
        }
        return try {
            val response = withTimeout(TIMEOUT_MS) {
                getStructuredModel(cfg.effectiveModel).generateContent(prompt)
            }
            val rawJson = response.text
                ?: return Result.failure(IllegalStateException("Gemini returned an empty response."))
            val parsed = parseSecondOpinionJson(
                rawJson = extractJsonObject(rawJson),
                referenceId = referenceId,
                symbol = symbol
            )
            markConnected(cfg, System.currentTimeMillis() - startMs)
            Result.success(parsed)
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Signal analysis timed out", e)
            Result.failure(IllegalStateException("AI analysis timed out after 60s. Please retry."))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Firebase AI analysis failed", e)
            Result.failure(IllegalStateException(friendlyAiError(e)))
        }
    }

    private fun markConnected(cfg: AiProviderConfig, latencyMs: Long) {
        val prev = _connectionStatus.value
        _connectionStatus.value = prev.copy(
            state = AiConnectionState.CONNECTED,
            provider = cfg.provider,
            activeModel = cfg.effectiveModel,
            availableModels = mergeModels(
                discoveredModelCache[cfg.provider] ?: prev.availableModels,
                cfg.effectiveModel,
                cfg.provider.availableModels
            ),
            latencyMs = latencyMs.coerceAtLeast(1L),
            message = "Connected to ${cfg.provider.shortLabel} (${cfg.effectiveModel}) • ${latencyMs.coerceAtLeast(1L)}ms"
        )
    }

    private suspend fun callExternalProvider(
        cfg: AiProviderConfig,
        prompt: String,
        expectJson: Boolean
    ): String = withContext(Dispatchers.IO) {
        val key = cfg.apiKey.trim()
        if (key.isBlank()) {
            throw IllegalStateException("Enter your ${cfg.provider.label} API key in Settings → AI Provider Configuration.")
        }
        val baseUrl = cfg.effectiveBaseUrl
        if (!baseUrl.startsWith("https://")) {
            throw IllegalStateException("AI Provider base URL must use HTTPS.")
        }
        val model = cfg.effectiveModel
        val jsonMedia = "application/json; charset=utf-8".toMediaType()

        val request: Request = when (cfg.provider) {
            AiProvider.GEMINI_API -> {
                val url = "$baseUrl/models/$model:generateContent"
                val genCfg = JSONObject().apply {
                    put("temperature", if (expectJson) 0.25 else 0.4)
                    if (expectJson) put("responseMimeType", "application/json")
                }
                val body = JSONObject().apply {
                    put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))
                    put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
                    put("generationConfig", genCfg)
                }
                Request.Builder()
                    .url(url)
                    .header("x-goog-api-key", key)
                    .post(body.toString().toRequestBody(jsonMedia))
                    .build()
            }
            AiProvider.CLAUDE_API -> {
                val url = "$baseUrl/messages"
                val body = JSONObject().apply {
                    put("model", model)
                    put("max_tokens", 1024)
                    put("temperature", if (expectJson) 0.25 else 0.4)
                    put("system", systemPrompt)
                    put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
                }
                Request.Builder()
                    .url(url)
                    .header("x-api-key", key)
                    .header("anthropic-version", "2023-06-01")
                    .post(body.toString().toRequestBody(jsonMedia))
                    .build()
            }
            AiProvider.DEEPSEEK_API, AiProvider.OPENAI_COMPATIBLE -> {
                val url = if (baseUrl.endsWith("/chat/completions")) baseUrl else "$baseUrl/chat/completions"
                val messages = JSONArray()
                    .put(JSONObject().put("role", "system").put("content", systemPrompt))
                    .put(JSONObject().put("role", "user").put("content", prompt))
                val isReasoningModel = model.contains("reasoner", ignoreCase = true) ||
                    model.startsWith("o1", ignoreCase = true) ||
                    model.startsWith("o3", ignoreCase = true)
                val body = JSONObject().apply {
                    put("model", model)
                    if (!isReasoningModel) {
                        put("temperature", if (expectJson) 0.25 else 0.4)
                    }
                    put("messages", messages)
                    if (expectJson && !isReasoningModel) {
                        put("response_format", JSONObject().put("type", "json_object"))
                    }
                }
                Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $key")
                    .post(body.toString().toRequestBody(jsonMedia))
                    .build()
            }
            AiProvider.FIREBASE_AI_LOGIC -> throw IllegalStateException("Invalid provider dispatch")
        }

        val callClient = okHttpClient.newBuilder()
            .readTimeout(45, TimeUnit.SECONDS)
            .build()

        callClient.newCall(request).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val errMsg = runCatching {
                    val root = JSONObject(raw)
                    root.optJSONObject("error")?.optString("message")
                        ?: root.optString("message")
                        ?: raw.take(180)
                }.getOrDefault(raw.take(180))
                throw IllegalStateException("${cfg.provider.shortLabel} HTTP ${resp.code}: ${errMsg.ifBlank { resp.message }}")
            }

            val root = JSONObject(raw)
            val contentText = when (cfg.provider) {
                AiProvider.GEMINI_API -> root.optJSONArray("candidates")
                    ?.optJSONObject(0)
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts")
                    ?.optJSONObject(0)
                    ?.optString("text")
                AiProvider.CLAUDE_API -> root.optJSONArray("content")
                    ?.optJSONObject(0)
                    ?.optString("text")
                AiProvider.DEEPSEEK_API, AiProvider.OPENAI_COMPATIBLE -> root.optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")
                AiProvider.FIREBASE_AI_LOGIC -> null
            }
            contentText?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("${cfg.provider.shortLabel} returned an empty response.")
        }
    }

    private fun friendlyAiError(e: Exception): String {
        val raw = e.localizedMessage.orEmpty()
        return if (raw.contains("App check", ignoreCase = true) ||
            raw.contains("AppCheck", ignoreCase = true) ||
            raw.contains("token is invalid", ignoreCase = true) ||
            raw.contains("403", ignoreCase = true)
        ) {
            "Firebase App Check attestation rejected on this build. Tap Configure AI API below to auto-connect Google Gemini API, Claude, DeepSeek, or OpenAI with your key."
        } else {
            raw.ifBlank { "AI request failed. Open AI Provider Settings to test or switch models." }
        }
    }

    private fun extractJsonObject(raw: String): String {
        val trimmed = raw.trim()
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        return if (start >= 0 && end > start) trimmed.substring(start, end + 1) else trimmed
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
            "- ${p.symbol} (${p.segment.label}) ${p.side.name} ${p.quantity} [${p.productType.shortCode}] Avg=${RiskMath.formatPrice(p.averageEntryPrice, p.segment)} LTP=${RiskMath.formatPrice(ltp, p.segment)} PnL=${RiskMath.formatSignedCurrency(pnl, p.segment)} SL=${p.activeSlPrice?.let { RiskMath.formatPrice(it, p.segment) } ?: "None"} Tgt=${p.activeTargetPrice?.let { RiskMath.formatPrice(it, p.segment) } ?: "None"}"
        }.ifBlank { "No open positions." }

        val journalText = journalEntries.take(12).joinToString("\n") { j ->
            "- ${j.symbol} (${j.segment.label}) ${j.side.name} ${j.quantity}: Entry=${RiskMath.formatPrice(j.entryPrice, j.segment)} Exit=${RiskMath.formatPrice(j.exitPrice, j.segment)} NetPnL=${RiskMath.formatSignedCurrency(j.netPnl, j.segment)} Reason=${j.exitReason.label}"
        }.ifBlank { "No closed journal trades yet." }

        val signalText = activeSignals.take(6).joinToString("\n") { s ->
            "- ${s.symbol} ${s.direction.name} ${s.strategy.shortLabel} (${s.timeframe.label}) Conf=${s.confidence.label} R:R=1:${"%.1f".format(s.riskRewardRatio)}"
        }.ifBlank { "No active signals." }

        return buildString {
            appendLine("User Request: $question")
            appendLine(
                "\nAccount Snapshot: India Free Margin=${RiskMath.formatPortfolioInr(account.availableMargin)}, India Realised=${RiskMath.formatPortfolioInr(account.realisedPnl, signed = true)} | " +
                    "Crypto Free Margin=${RiskMath.formatPortfolioUsd(account.cryptoAvailableMargin)}, Crypto Realised=${RiskMath.formatPortfolioUsd(account.cryptoRealisedPnl, signed = true)} | IST=${MarketClock.formatCurrentIstClock()}"
            )
            appendLine("\nOpen Positions:\n$posText")
            appendLine("\nRecent Closed Trades:\n$journalText")
            appendLine("\nTop Active Signals:\n$signalText")
            appendLine("\nRespond in concise bullet points with specific numbers from the data above and clear risk-management advice.")
        }
    }

    private fun buildStrategyCoachPrompt(
        persona: AiStrategyCoachPersona,
        question: String,
        conversationHistory: List<Pair<Boolean, String>>,
        account: AccountState,
        positions: List<Position>,
        instruments: List<Instrument>,
        journalEntries: List<TradeJournalEntry>,
        activeSignals: List<TradeSignal>,
        globalMarkets: List<GlobalMarketQuote>,
        fiiDii: FiiDiiDashboardData?
    ): String {
        val baseContext = buildCoachPrompt(
            question = question,
            account = account,
            positions = positions,
            instruments = instruments,
            journalEntries = journalEntries,
            activeSignals = activeSignals
        )
        val globalLines = globalMarkets.take(8).joinToString(" | ") { q ->
            "${q.symbol}: ${"%.1f".format(q.price)} (${"%+.2f%%".format(q.changePercent)})"
        }.ifBlank { "Global indices loading." }

        val fiiLine = fiiDii?.let { d ->
            "Latest (${d.latest.dateLabel}): FII Net ₹${"%.0f".format(d.latest.fiiNetCr)} Cr (Buy ₹${"%.0f".format(d.latest.fiiBuyCr)} / Sell ₹${"%.0f".format(d.latest.fiiSellCr)}), " +
                "DII Net ₹${"%.0f".format(d.latest.diiNetCr)} Cr (Buy ₹${"%.0f".format(d.latest.diiBuyCr)} / Sell ₹${"%.0f".format(d.latest.diiSellCr)}), " +
                "Sentiment: ${d.sentimentLabel}"
        } ?: "FII/DII data syncing."

        val recentHistory = conversationHistory.takeLast(6).joinToString("\n") { (isUser, msg) ->
            "${if (isUser) "Trader" else persona.shortTitle + " Coach"}: ${msg.take(360)}"
        }

        return buildString {
            appendLine("ACTIVE COACH PERSONA: ${persona.title} [${persona.specialtyBadge}]")
            appendLine(persona.personaInstruction)
            appendLine("\nGLOBAL MARKET CUES:\n$globalLines")
            appendLine("\nNSE INSTITUTIONAL MARKET FLOW (FII/DII):\n$fiiLine")
            if (recentHistory.isNotBlank()) {
                appendLine("\nRECENT COACHING CONVERSATION:\n$recentHistory")
            }
            appendLine("\n$baseContext")
            appendLine("\nStructure your coaching reply with: (1) Direct Assessment, (2) Actionable Strategy Plan with exact levels/rules, and (3) Key Risk Guardrail.")
        }
    }

    companion object {
        private const val TAG = "FirebaseAiMarketRepo"
        const val MODEL_NAME = "gemini-3.8-flash"
        private const val TIMEOUT_MS = 60_000L
        private const val FIREBASE_NOT_INITIALIZED_MSG =
            "Firebase is not configured in this APK build. Tap Configure AI API to auto-connect Gemini API, Claude, DeepSeek, or OpenAI."

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
