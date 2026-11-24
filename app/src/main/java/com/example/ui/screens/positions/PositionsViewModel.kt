package com.example.ui.screens.positions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.feed.live.FeedType
import com.example.data.feed.live.SegmentBadgeMode
import com.example.data.feed.live.SegmentFeedStatus
import com.example.data.repository.MarketDataRepository
import com.example.data.repository.TradingRepository
import com.example.domain.engine.RiskMath
import com.example.domain.model.AccountState
import com.example.domain.model.CapitalTransaction
import com.example.domain.model.Instrument
import com.example.domain.model.MarketSegment
import com.example.domain.model.OrderSide
import com.example.domain.model.Position
import com.example.domain.model.TrailMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.math.BigDecimal
import javax.inject.Inject

data class PositionWithLivePnl(
    val position: Position,
    val ltp: BigDecimal,
    val unrealisedPnl: BigDecimal,
    val supportsCarryForward: Boolean,
    val feedBadge: SegmentBadgeMode = SegmentBadgeMode.SIMULATED
)

data class PositionsUiState(
    val items: List<PositionWithLivePnl> = emptyList(),
    val totalUnrealisedPnl: BigDecimal = BigDecimal.ZERO,
    val cryptoUnrealisedPnl: BigDecimal = BigDecimal.ZERO,
    val realisedPnl: BigDecimal = BigDecimal.ZERO,
    val cryptoRealisedPnl: BigDecimal = BigDecimal.ZERO,
    val virtualCapital: BigDecimal = BigDecimal.ZERO,
    val availableMargin: BigDecimal = BigDecimal.ZERO,
    val cryptoVirtualCapital: BigDecimal = BigDecimal.ZERO,
    val cryptoAvailableMargin: BigDecimal = BigDecimal.ZERO,
    val capitalTransactions: List<CapitalTransaction> = emptyList(),
    val riskControlsSuspended: Boolean = false,
    val suspendedBannerMessage: String = "",
    val liveStatusMessage: String = ""
)

@HiltViewModel
class PositionsViewModel @Inject constructor(
    private val tradingRepository: TradingRepository,
    private val marketDataRepository: MarketDataRepository
) : ViewModel() {

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val feedHealthFlow = combine(
        marketDataRepository.segmentStatuses,
        marketDataRepository.riskControlsSuspended,
        marketDataRepository.suspendedBannerMessage,
        marketDataRepository.liveStatusMessage
    ) { statuses, suspended, bannerMsg, msg -> FeedHealthData(statuses, suspended, bannerMsg, msg) }

    val uiState: StateFlow<PositionsUiState> = combine(
        tradingRepository.positions,
        marketDataRepository.instruments,
        tradingRepository.accountState,
        tradingRepository.capitalTransactions,
        feedHealthFlow
    ) { positions: List<Position>,
        instruments: List<Instrument>,
        account: AccountState,
        capitalTxns: List<CapitalTransaction>,
        health: FeedHealthData ->
        val instMap: Map<String, Instrument> = instruments.associateBy { it.symbol }
        val segStatuses = health.statuses
        var indiaUnrealised = BigDecimal.ZERO
        var cryptoUnrealised = BigDecimal.ZERO
        val enriched = positions.map { pos ->
            val inst = instMap[pos.symbol]
            val ltp = inst?.ltp ?: pos.averageEntryPrice
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
            PositionWithLivePnl(
                position = pos,
                ltp = ltp,
                unrealisedPnl = pnl,
                supportsCarryForward = inst?.supportsCarryForward ?: true,
                feedBadge = segStatuses[pos.segment]?.badge ?: SegmentBadgeMode.SIMULATED
            )
        }
        PositionsUiState(
            items = enriched,
            totalUnrealisedPnl = indiaUnrealised,
            cryptoUnrealisedPnl = cryptoUnrealised,
            realisedPnl = account.realisedPnl,
            cryptoRealisedPnl = account.cryptoRealisedPnl,
            virtualCapital = account.virtualCapital,
            availableMargin = account.availableMargin,
            cryptoVirtualCapital = account.cryptoVirtualCapital,
            cryptoAvailableMargin = account.cryptoAvailableMargin,
            capitalTransactions = capitalTxns,
            riskControlsSuspended = health.suspended,
            suspendedBannerMessage = health.bannerMsg,
            liveStatusMessage = health.msg
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = PositionsUiState()
    )

    fun saveInlineRiskControls(
        positionId: String,
        slPrice: BigDecimal?,
        targetPrice: BigDecimal?,
        isTslEnabled: Boolean,
        tslMode: TrailMode,
        tslValue: BigDecimal,
        isTrailingTargetEnabled: Boolean,
        trailingTargetMode: TrailMode,
        trailingTargetValue: BigDecimal
    ) {
        viewModelScope.launch {
            val res = tradingRepository.updatePositionRiskControls(
                positionId = positionId,
                newSlPrice = slPrice,
                newTargetPrice = targetPrice,
                isTslEnabled = isTslEnabled,
                tslMode = tslMode,
                tslValue = tslValue,
                isTrailingTargetEnabled = isTrailingTargetEnabled,
                trailingTargetMode = trailingTargetMode,
                trailingTargetValue = trailingTargetValue
            )
            res.onSuccess { _messages.emit("Updated OCO risk controls") }
                .onFailure { _messages.emit(it.message ?: "Failed to update risk controls") }
        }
    }

    fun averagePosition(positionId: String, additionalQuantity: Int) {
        viewModelScope.launch {
            tradingRepository.averageRunningPosition(positionId, additionalQuantity)
                .onSuccess { _messages.emit(it) }
                .onFailure { _messages.emit(it.message ?: "Averaging failed") }
        }
    }

    fun addCapital(amount: BigDecimal, note: String) {
        viewModelScope.launch {
            tradingRepository.addCapital(amount, note)
                .onSuccess { _messages.emit(it) }
                .onFailure { _messages.emit(it.message ?: "Failed to add funds") }
        }
    }

    fun reduceCapital(amount: BigDecimal, note: String) {
        viewModelScope.launch {
            tradingRepository.reduceCapital(amount, note)
                .onSuccess { _messages.emit(it) }
                .onFailure { _messages.emit(it.message ?: "Failed to reduce funds") }
        }
    }

    fun addCryptoCapital(amount: BigDecimal, note: String) {
        viewModelScope.launch {
            tradingRepository.addCryptoCapital(amount, note)
                .onSuccess { _messages.emit(it) }
                .onFailure { _messages.emit(it.message ?: "Failed to add USD funds") }
        }
    }

    fun reduceCryptoCapital(amount: BigDecimal, note: String) {
        viewModelScope.launch {
            tradingRepository.reduceCryptoCapital(amount, note)
                .onSuccess { _messages.emit(it) }
                .onFailure { _messages.emit(it.message ?: "Failed to reduce USD funds") }
        }
    }

    fun resetAccount(
        newCapital: BigDecimal,
        newCryptoCapital: BigDecimal = BigDecimal("10000.00")
    ) {
        viewModelScope.launch {
            tradingRepository.resetPaperAccount(newCapital, newCryptoCapital)
            _messages.emit(
                "Paper account reset: ${RiskMath.formatPortfolioInr(newCapital)} & ${RiskMath.formatPortfolioUsd(newCryptoCapital)}"
            )
        }
    }

    fun convertToCarryForward(positionId: String) {
        viewModelScope.launch {
            tradingRepository.convertProductToCarryForward(positionId)
                .onSuccess { _messages.emit(it) }
                .onFailure { _messages.emit(it.message ?: "Conversion failed") }
        }
    }

    fun exitPosition(positionId: String) {
        viewModelScope.launch {
            tradingRepository.exitPositionAtMarket(positionId)
                .onSuccess { _messages.emit("Position squared off at market") }
                .onFailure { _messages.emit(it.message ?: "Exit failed") }
        }
    }

    fun simulateIntradaySquareOff() {
        viewModelScope.launch {
            val count = tradingRepository.triggerSimulatedSquareOff()
            _messages.emit(
                if (count > 0) "Intraday Square-Off (15:15 Eq/Fut • 15:40 Opt) executed for $count position(s)"
                else "No open Intraday positions; DAY orders cancelled"
            )
        }
    }

    fun reconnectFeed() {
        viewModelScope.launch {
            marketDataRepository.reconnectActiveFeed()
            _messages.emit("Reconnecting live price feed...")
        }
    }

    fun switchToSimulatedFeed() {
        viewModelScope.launch {
            marketDataRepository.setActiveFeed(FeedType.SIMULATED)
            _messages.emit("Switched suspended segments to Simulated Feed")
        }
    }

    private data class FeedHealthData(
        val statuses: Map<MarketSegment, SegmentFeedStatus>,
        val suspended: Boolean,
        val bannerMsg: String,
        val msg: String
    )
}
