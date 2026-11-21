package com.example.ui.screens.optionchain

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.example.data.repository.MarketDataRepository
import com.example.domain.engine.OptionChainRow
import com.example.domain.engine.OptionChainSnapshot
import com.example.domain.engine.OptionGreeksCalculator
import com.example.domain.engine.OptionLegQuote
import com.example.domain.engine.TokenLiveTick
import com.example.domain.model.OptionType
import com.example.ui.navigation.OptionChainRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.math.BigDecimal
import javax.inject.Inject

enum class OptionChainViewMode(val label: String) {
    ALL("All (OI + Greeks)"),
    OI_LTP("OI & LTP"),
    GREEKS("Greeks (IV Δ Θ Γ ν)")
}

data class OptionChainUiState(
    val selectedUnderlying: String = "NIFTY",
    val selectedExpiry: String? = null,
    val strikeWindow: Int = 10,
    val viewMode: OptionChainViewMode = OptionChainViewMode.ALL,
    val isLiveAutoRefresh: Boolean = true,
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
    val snapshot: OptionChainSnapshot? = null,
    val openingToken: String? = null,
    val liveTickCount: Int = 0
)

@HiltViewModel
class OptionChainViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val marketDataRepository: MarketDataRepository
) : ViewModel() {

    private val initialUnderlying = runCatching {
        savedStateHandle.toRoute<OptionChainRoute>().initialUnderlying
    }.getOrDefault("NIFTY").ifBlank { "NIFTY" }.uppercase()

    private val _uiState = MutableStateFlow(OptionChainUiState(selectedUnderlying = initialUnderlying))
    val uiState: StateFlow<OptionChainUiState> = _uiState.asStateFlow()

    private val _openTradeEvents = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val openTradeEvents: SharedFlow<String> = _openTradeEvents.asSharedFlow()

    // Baseline OI per contract key ("exchangeType:token") for live session ΔOI tracking
    private val baselineOiMap = HashMap<String, Long>()
    private var pollJob: Job? = null

    init {
        observeLiveStreamTicks()
        startLiveLoop()
    }

    fun selectUnderlying(underlying: String) {
        val clean = underlying.trim().uppercase()
        if (clean.isBlank() || clean == _uiState.value.selectedUnderlying) return
        baselineOiMap.clear()
        _uiState.update {
            it.copy(
                selectedUnderlying = clean,
                selectedExpiry = null,
                isLoading = true,
                errorMessage = null,
                snapshot = null
            )
        }
        startLiveLoop()
    }

    fun selectExpiry(expiry: String) {
        if (expiry == _uiState.value.selectedExpiry) return
        baselineOiMap.clear()
        _uiState.update {
            it.copy(
                selectedExpiry = expiry,
                isLoading = true,
                errorMessage = null
            )
        }
        startLiveLoop()
    }

    fun selectStrikeWindow(window: Int) {
        if (window == _uiState.value.strikeWindow) return
        _uiState.update { it.copy(strikeWindow = window, isRefreshing = true) }
        startLiveLoop()
    }

    fun setViewMode(mode: OptionChainViewMode) {
        _uiState.update { it.copy(viewMode = mode) }
    }

    fun toggleAutoRefresh() {
        val next = !_uiState.value.isLiveAutoRefresh
        _uiState.update { it.copy(isLiveAutoRefresh = next) }
        if (next) {
            startLiveLoop()
        } else {
            pollJob?.cancel()
        }
    }

    fun manualRefresh(forceMasterReload: Boolean = false) {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true, errorMessage = null) }
            if (forceMasterReload) {
                marketDataRepository.refreshScripMaster()
            }
            fetchSnapshotOnce()
        }
    }

    fun openLegForPaperTrade(leg: OptionLegQuote) {
        if (_uiState.value.openingToken != null) return
        viewModelScope.launch {
            _uiState.update { it.copy(openingToken = leg.symbolToken) }
            val added = marketDataRepository.addOptionLegToWatchlist(leg)
            _uiState.update { it.copy(openingToken = null) }
            added.onSuccess { inst ->
                _openTradeEvents.emit(inst.symbol)
            }.onFailure { err ->
                _uiState.update { it.copy(errorMessage = err.message ?: "Could not open ${leg.tradingSymbol}") }
            }
        }
    }

    private fun startLiveLoop() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            fetchSnapshotOnce()
            while (isActive && _uiState.value.isLiveAutoRefresh) {
                delay(3_000L)
                if (_uiState.value.isLiveAutoRefresh) {
                    fetchSnapshotOnce(silent = true)
                }
            }
        }
    }

    private suspend fun fetchSnapshotOnce(silent: Boolean = false) {
        val current = _uiState.value
        if (!silent) {
            _uiState.update {
                it.copy(
                    isLoading = it.snapshot == null,
                    isRefreshing = it.snapshot != null,
                    errorMessage = null
                )
            }
        }
        val result = marketDataRepository.fetchLiveOptionChain(
            underlying = current.selectedUnderlying,
            expiry = current.selectedExpiry,
            strikeWindow = current.strikeWindow,
            previousOiMap = baselineOiMap.toMap()
        )
        result.onSuccess { snap ->
            // Seed baseline OI for any newly seen contract so ΔOI reflects live changes
            snap.rows.forEach { row ->
                row.call?.let { c ->
                    if (c.openInterest > 0L && !baselineOiMap.containsKey(c.tokenKey)) {
                        baselineOiMap[c.tokenKey] = c.openInterest
                    }
                }
                row.put?.let { p ->
                    if (p.openInterest > 0L && !baselineOiMap.containsKey(p.tokenKey)) {
                        baselineOiMap[p.tokenKey] = p.openInterest
                    }
                }
            }
            _uiState.update {
                it.copy(
                    selectedExpiry = snap.expiry,
                    snapshot = snap,
                    isLoading = false,
                    isRefreshing = false,
                    errorMessage = null
                )
            }
        }.onFailure { err ->
            _uiState.update {
                it.copy(
                    isLoading = false,
                    isRefreshing = false,
                    errorMessage = err.message ?: "Could not load live option chain"
                )
            }
        }
    }

    private fun observeLiveStreamTicks() {
        viewModelScope.launch {
            marketDataRepository.observeOptionTokenTicks().collect { tick ->
                applyLiveTokenTick(tick)
            }
        }
    }

    private fun applyLiveTokenTick(tick: TokenLiveTick) {
        val snap = _uiState.value.snapshot ?: return
        var matched = false
        val spotDouble = snap.spotPrice.toDouble()
        val timeYears = OptionGreeksCalculator.parseExpiryToYears(snap.expiry, tick.timestampMillis)

        fun patchLeg(leg: OptionLegQuote?): OptionLegQuote? {
            if (leg == null || leg.tokenKey != tick.key) return leg
            matched = true
            val newLtp = tick.ltp.takeIf { it > BigDecimal.ZERO } ?: leg.ltp
            val newOi = tick.openInterest?.takeIf { it > 0L } ?: leg.openInterest
            val baseOi = baselineOiMap[leg.tokenKey] ?: newOi.also {
                if (it > 0L) baselineOiMap[leg.tokenKey] = it
            }
            val newOiDelta = if (newOi > 0L && baseOi > 0L) newOi - baseOi else leg.oiChange
            val newVol = tick.volume?.takeIf { it > 0L } ?: leg.volume
            val updatedGreeks = if (leg.greeks?.isBrokerSupplied == true && newLtp.compareTo(leg.ltp) == 0) {
                leg.greeks
            } else {
                OptionGreeksCalculator.computeGreeks(
                    spot = spotDouble,
                    strike = leg.strikePrice.toDouble(),
                    timeToExpiryYears = timeYears,
                    optionPrice = newLtp.toDouble(),
                    isCall = leg.optionType == OptionType.CE
                )
            }
            return leg.copy(
                ltp = newLtp,
                openInterest = newOi,
                oiChange = newOiDelta,
                volume = newVol,
                greeks = updatedGreeks
            )
        }

        val updatedRows = snap.rows.map { row ->
            val nextCall = patchLeg(row.call)
            val nextPut = patchLeg(row.put)
            if (nextCall !== row.call || nextPut !== row.put) {
                row.copy(call = nextCall, put = nextPut)
            } else {
                row
            }
        }

        if (!matched) return

        val totalCallOi = updatedRows.sumOf { it.call?.openInterest ?: 0L }
        val totalPutOi = updatedRows.sumOf { it.put?.openInterest ?: 0L }
        val pcr = if (totalCallOi > 0L) totalPutOi.toDouble() / totalCallOi.toDouble() else snap.pcr

        _uiState.update { state ->
            state.copy(
                snapshot = snap.copy(
                    rows = updatedRows,
                    totalCallOi = totalCallOi,
                    totalPutOi = totalPutOi,
                    pcr = pcr,
                    updatedAtMillis = tick.timestampMillis
                ),
                liveTickCount = state.liveTickCount + 1
            )
        }
    }

    companion object {
        val PRESET_UNDERLYINGS = listOf(
            "NIFTY",
            "BANKNIFTY",
            "SENSEX",
            "FINNIFTY",
            "MIDCPNIFTY",
            "BANKEX",
            "NATURALGAS",
            "CRUDEOIL",
            "GOLD",
            "SILVER",
            "RELIANCE",
            "HDFCBANK",
            "INFY",
            "SBIN",
            "ICICIBANK",
            "TCS"
        )
    }
}
