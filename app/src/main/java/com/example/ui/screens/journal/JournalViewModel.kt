package com.example.ui.screens.journal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.ai.AiMarketRepository
import com.example.data.repository.MarketDataRepository
import com.example.data.repository.TradingRepository
import com.example.data.signal.SignalRepository
import com.example.domain.engine.RiskMath
import com.example.domain.model.AccountState
import com.example.domain.model.CapitalTransaction
import com.example.domain.model.Instrument
import com.example.domain.model.MarketSegment
import com.example.domain.model.OrderSide
import com.example.domain.model.Position
import com.example.domain.model.TradeJournalEntry
import com.example.domain.signal.AiUiState
import com.example.domain.signal.InstrumentTradePerformance
import com.example.domain.signal.TradeSignal
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.math.BigDecimal
import javax.inject.Inject

data class JournalUiState(
    val entries: List<TradeJournalEntry> = emptyList(),
    val instrumentPerformance: List<InstrumentTradePerformance> = emptyList(),
    val capitalTransactions: List<CapitalTransaction> = emptyList(),
    val grossPnl: BigDecimal = BigDecimal.ZERO,
    val totalCharges: BigDecimal = BigDecimal.ZERO,
    val totalBrokerage: BigDecimal = BigDecimal.ZERO,
    val totalTaxes: BigDecimal = BigDecimal.ZERO,
    val netRealisedPnl: BigDecimal = BigDecimal.ZERO,
    val unrealisedPnl: BigDecimal = BigDecimal.ZERO,
    val cryptoGrossPnl: BigDecimal = BigDecimal.ZERO,
    val cryptoTotalCharges: BigDecimal = BigDecimal.ZERO,
    val cryptoNetRealisedPnl: BigDecimal = BigDecimal.ZERO,
    val cryptoUnrealisedPnl: BigDecimal = BigDecimal.ZERO,
    val winRatePercent: Int = 0,
    val coachState: AiUiState<String> = AiUiState.Idle
)

@HiltViewModel
class JournalViewModel @Inject constructor(
    private val tradingRepository: TradingRepository,
    marketDataRepository: MarketDataRepository,
    signalRepository: SignalRepository,
    private val aiMarketRepository: AiMarketRepository
) : ViewModel() {

    private val _coachState = MutableStateFlow<AiUiState<String>>(AiUiState.Idle)
    private var coachJob: Job? = null

    private var latestSnapshot = CoachSnapshot()

    val uiState: StateFlow<JournalUiState> = combine(
        tradingRepository.journalEntries,
        tradingRepository.positions,
        marketDataRepository.instruments,
        tradingRepository.accountState,
        signalRepository.activeSignals,
        tradingRepository.capitalTransactions,
        _coachState
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val entries = values[0] as List<TradeJournalEntry>
        @Suppress("UNCHECKED_CAST")
        val positions = values[1] as List<Position>
        @Suppress("UNCHECKED_CAST")
        val instruments = values[2] as List<Instrument>
        val account = values[3] as AccountState
        @Suppress("UNCHECKED_CAST")
        val signalItems = values[4] as List<com.example.data.signal.SignalUiItem>
        @Suppress("UNCHECKED_CAST")
        val capitalTxns = values[5] as List<CapitalTransaction>
        @Suppress("UNCHECKED_CAST")
        val coachState = values[6] as AiUiState<String>

        latestSnapshot = CoachSnapshot(
            account = account,
            positions = positions,
            instruments = instruments,
            journalEntries = entries,
            activeSignals = signalItems.map { it.signal }
        )

        val ltpMap = instruments.associate { it.symbol to it.ltp }
        var indiaUnrealised = BigDecimal.ZERO
        var cryptoUnrealised = BigDecimal.ZERO
        positions.forEach { pos ->
            val ltp = ltpMap[pos.symbol] ?: pos.averageEntryPrice
            val pnl = RiskMath.calculateUnrealisedPnl(
                entryPrice = pos.averageEntryPrice,
                ltp = ltp,
                quantity = pos.quantity,
                isLong = pos.side == OrderSide.BUY
            )
            if (pos.segment == MarketSegment.CRYPTO) {
                cryptoUnrealised = cryptoUnrealised.add(pnl)
            } else {
                indiaUnrealised = indiaUnrealised.add(pnl)
            }
        }

        val indiaEntries = entries.filter { it.segment != MarketSegment.CRYPTO }
        val cryptoEntries = entries.filter { it.segment == MarketSegment.CRYPTO }

        val gross = indiaEntries.fold(BigDecimal.ZERO) { acc, e -> acc.add(e.grossPnl) }
        val charges = indiaEntries.fold(BigDecimal.ZERO) { acc, e -> acc.add(e.charges) }
        val brokerage = indiaEntries.fold(BigDecimal.ZERO) { acc, e -> acc.add(e.brokerage) }
        val taxes = indiaEntries.fold(BigDecimal.ZERO) { acc, e ->
            acc.add(e.sttCtt).add(e.exchangeCharges).add(e.sebiCharges).add(e.stampDuty).add(e.gst)
        }
        val net = indiaEntries.fold(BigDecimal.ZERO) { acc, e -> acc.add(e.netPnl) }

        val cryptoGross = cryptoEntries.fold(BigDecimal.ZERO) { acc, e -> acc.add(e.grossPnl) }
        val cryptoCharges = cryptoEntries.fold(BigDecimal.ZERO) { acc, e -> acc.add(e.charges) }
        val cryptoNet = cryptoEntries.fold(BigDecimal.ZERO) { acc, e -> acc.add(e.netPnl) }

        val wins = entries.count { it.netPnl > BigDecimal.ZERO }
        val winRate = if (entries.isEmpty()) 0 else (wins * 100) / entries.size

        val byInstrument = entries
            .groupBy { it.symbol }
            .map { (symbol, list) ->
                val seg = list.first().segment
                val w = list.count { it.netPnl > BigDecimal.ZERO }
                val l = list.size - w
                InstrumentTradePerformance(
                    symbol = symbol,
                    segment = seg,
                    totalTrades = list.size,
                    wins = w,
                    losses = l,
                    grossPnl = list.fold(BigDecimal.ZERO) { a, e -> a.add(e.grossPnl) },
                    totalCharges = list.fold(BigDecimal.ZERO) { a, e -> a.add(e.charges) },
                    netPnl = list.fold(BigDecimal.ZERO) { a, e -> a.add(e.netPnl) }
                )
            }
            .sortedWith(compareByDescending<InstrumentTradePerformance> { it.totalTrades }.thenByDescending { it.netPnl })

        JournalUiState(
            entries = entries,
            instrumentPerformance = byInstrument,
            capitalTransactions = capitalTxns,
            grossPnl = gross,
            totalCharges = charges,
            totalBrokerage = brokerage,
            totalTaxes = taxes,
            netRealisedPnl = net,
            unrealisedPnl = indiaUnrealised,
            cryptoGrossPnl = cryptoGross,
            cryptoTotalCharges = cryptoCharges,
            cryptoNetRealisedPnl = cryptoNet,
            cryptoUnrealisedPnl = cryptoUnrealised,
            winRatePercent = winRate,
            coachState = coachState
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = JournalUiState()
    )

    fun askAiCoach(question: String) {
        val snap = latestSnapshot
        coachJob?.cancel()
        coachJob = viewModelScope.launch {
            aiMarketRepository.streamPortfolioCoach(
                question = question,
                account = snap.account,
                positions = snap.positions,
                instruments = snap.instruments,
                journalEntries = snap.journalEntries,
                activeSignals = snap.activeSignals
            ).collect { state ->
                _coachState.value = state
            }
        }
    }

    fun saveNotes(entryId: String, notes: String) {
        viewModelScope.launch {
            tradingRepository.updateJournalNotes(entryId, notes)
        }
    }

    private data class CoachSnapshot(
        val account: AccountState = AccountState(BigDecimal("1000000"), BigDecimal("1000000"), BigDecimal.ZERO),
        val positions: List<Position> = emptyList(),
        val instruments: List<Instrument> = emptyList(),
        val journalEntries: List<TradeJournalEntry> = emptyList(),
        val activeSignals: List<TradeSignal> = emptyList()
    )
}
