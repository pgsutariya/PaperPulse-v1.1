package com.example.ui.screens.coach

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.ai.AiConnectionStatus
import com.example.data.ai.AiMarketRepository
import com.example.data.market.MarketIntelligenceRepository
import com.example.data.repository.MarketDataRepository
import com.example.data.repository.TradingRepository
import com.example.data.signal.SignalRepository
import com.example.domain.ai.AiStrategyCoachPersona
import com.example.domain.ai.StrategyCoachMessage
import com.example.domain.market.FiiDiiDashboardData
import com.example.domain.market.GlobalMarketQuote
import com.example.domain.model.AccountState
import com.example.domain.model.Instrument
import com.example.domain.model.Position
import com.example.domain.model.TradeJournalEntry
import com.example.domain.signal.AiUiState
import com.example.domain.signal.TradeSignal
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.util.UUID
import javax.inject.Inject

data class AiStrategyCoachesUiState(
    val selectedPersona: AiStrategyCoachPersona = AiStrategyCoachPersona.INTRADAY_SCALPER,
    val messages: List<StrategyCoachMessage> = emptyList(),
    val inputDraft: String = "",
    val isGenerating: Boolean = false,
    val connectionStatus: AiConnectionStatus = AiConnectionStatus(),
    val openPositionsCount: Int = 0,
    val activeSignalsCount: Int = 0,
    val closedTradesCount: Int = 0,
    val availableMarginInr: BigDecimal = BigDecimal("1000000"),
    val fiiNetCr: Double? = null,
    val diiNetCr: Double? = null,
    val globalAdvancingCount: Int = 0,
    val globalTotalCount: Int = 0
)

@HiltViewModel
class AiStrategyCoachesViewModel @Inject constructor(
    private val aiMarketRepository: AiMarketRepository,
    private val tradingRepository: TradingRepository,
    private val marketDataRepository: MarketDataRepository,
    private val signalRepository: SignalRepository,
    private val marketIntelligenceRepository: MarketIntelligenceRepository
) : ViewModel() {

    private val _selectedPersona = MutableStateFlow(AiStrategyCoachPersona.INTRADAY_SCALPER)
    private val _messagesByPersona = MutableStateFlow<Map<AiStrategyCoachPersona, List<StrategyCoachMessage>>>(emptyMap())
    private val _inputDraft = MutableStateFlow("")
    private val _isGenerating = MutableStateFlow(false)

    private var activeJob: Job? = null

    private data class ContextSnapshot(
        val account: AccountState,
        val positions: List<Position>,
        val instruments: List<Instrument>,
        val journal: List<TradeJournalEntry>,
        val signals: List<TradeSignal>,
        val globals: List<GlobalMarketQuote>,
        val fiiDii: FiiDiiDashboardData
    )

    private val portfolioFlow = combine(
        tradingRepository.accountState,
        tradingRepository.positions,
        marketDataRepository.instruments,
        tradingRepository.journalEntries,
        signalRepository.activeSignals
    ) { account, positions, instruments, journal, signals ->
        PortfolioPart(account, positions, instruments, journal, signals.map { it.signal })
    }

    private val contextFlow: StateFlow<ContextSnapshot> = combine(
        portfolioFlow,
        marketIntelligenceRepository.globalMarkets,
        marketIntelligenceRepository.fiiDiiData
    ) { portfolio, globals, fiiDii ->
        ContextSnapshot(
            account = portfolio.account,
            positions = portfolio.positions,
            instruments = portfolio.instruments,
            journal = portfolio.journal,
            signals = portfolio.signals,
            globals = globals,
            fiiDii = fiiDii
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = ContextSnapshot(
            account = AccountState(BigDecimal("1000000"), BigDecimal("1000000"), BigDecimal.ZERO),
            positions = emptyList(),
            instruments = emptyList(),
            journal = emptyList(),
            signals = emptyList(),
            globals = emptyList(),
            fiiDii = marketIntelligenceRepository.fiiDiiData.value
        )
    )

    val uiState: StateFlow<AiStrategyCoachesUiState> = combine(
        _selectedPersona,
        _messagesByPersona,
        _inputDraft,
        _isGenerating,
        aiMarketRepository.connectionStatus,
        contextFlow
    ) { values ->
        val persona = values[0] as AiStrategyCoachPersona
        @Suppress("UNCHECKED_CAST")
        val allMsgs = values[1] as Map<AiStrategyCoachPersona, List<StrategyCoachMessage>>
        val draft = values[2] as String
        val generating = values[3] as Boolean
        val conn = values[4] as AiConnectionStatus
        val ctx = values[5] as ContextSnapshot

        AiStrategyCoachesUiState(
            selectedPersona = persona,
            messages = allMsgs[persona].orEmpty(),
            inputDraft = draft,
            isGenerating = generating,
            connectionStatus = conn,
            openPositionsCount = ctx.positions.size,
            activeSignalsCount = ctx.signals.size,
            closedTradesCount = ctx.journal.size,
            availableMarginInr = ctx.account.availableMargin,
            fiiNetCr = ctx.fiiDii.latest.fiiNetCr,
            diiNetCr = ctx.fiiDii.latest.diiNetCr,
            globalAdvancingCount = ctx.globals.count { it.changePercent >= 0.0 },
            globalTotalCount = ctx.globals.size
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = AiStrategyCoachesUiState()
    )

    fun selectPersona(persona: AiStrategyCoachPersona) {
        if (_selectedPersona.value == persona) return
        activeJob?.cancel()
        _isGenerating.value = false
        _selectedPersona.value = persona
    }

    fun updateDraft(text: String) {
        _inputDraft.value = text
    }

    fun clearConversation() {
        activeJob?.cancel()
        _isGenerating.value = false
        val persona = _selectedPersona.value
        _messagesByPersona.update { it + (persona to emptyList()) }
    }

    fun sendQuestion(rawQuestion: String = _inputDraft.value) {
        val question = rawQuestion.trim()
        if (question.isBlank() || _isGenerating.value) return

        val persona = _selectedPersona.value
        val existing = _messagesByPersona.value[persona].orEmpty()
        val historyPairs = existing.filterNot { it.isError || it.isStreaming }.map { it.isUser to it.text }

        val userMsg = StrategyCoachMessage(
            id = UUID.randomUUID().toString(),
            isUser = true,
            text = question,
            persona = persona
        )
        val assistantMsgId = UUID.randomUUID().toString()
        val placeholderMsg = StrategyCoachMessage(
            id = assistantMsgId,
            isUser = false,
            text = "",
            persona = persona,
            isStreaming = true
        )

        _inputDraft.value = ""
        _isGenerating.value = true
        _messagesByPersona.update { map ->
            map + (persona to (existing + userMsg + placeholderMsg))
        }

        val ctx = contextFlow.value
        activeJob?.cancel()
        activeJob = viewModelScope.launch {
            aiMarketRepository.streamStrategyCoach(
                persona = persona,
                question = question,
                conversationHistory = historyPairs,
                account = ctx.account,
                positions = ctx.positions,
                instruments = ctx.instruments,
                journalEntries = ctx.journal,
                activeSignals = ctx.signals,
                globalMarkets = ctx.globals,
                fiiDii = ctx.fiiDii
            ).collect { aiState ->
                when (aiState) {
                    is AiUiState.Loading -> {
                        updateAssistantMessage(persona, assistantMsgId, text = "", isStreaming = true, isError = false)
                    }
                    is AiUiState.Streaming -> {
                        updateAssistantMessage(persona, assistantMsgId, text = aiState.partialText, isStreaming = true, isError = false)
                    }
                    is AiUiState.Success -> {
                        updateAssistantMessage(persona, assistantMsgId, text = aiState.data, isStreaming = false, isError = false)
                        _isGenerating.value = false
                    }
                    is AiUiState.Error -> {
                        updateAssistantMessage(persona, assistantMsgId, text = aiState.message, isStreaming = false, isError = true)
                        _isGenerating.value = false
                    }
                    is AiUiState.Idle -> Unit
                }
            }
        }
    }

    private fun updateAssistantMessage(
        persona: AiStrategyCoachPersona,
        msgId: String,
        text: String,
        isStreaming: Boolean,
        isError: Boolean
    ) {
        _messagesByPersona.update { map ->
            val list = map[persona].orEmpty().map { m ->
                if (m.id == msgId) m.copy(text = text, isStreaming = isStreaming, isError = isError) else m
            }
            map + (persona to list)
        }
    }

    private data class PortfolioPart(
        val account: AccountState,
        val positions: List<Position>,
        val instruments: List<Instrument>,
        val journal: List<TradeJournalEntry>,
        val signals: List<TradeSignal>
    )
}
