package com.example.ui.screens.orders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.repository.MarketDataRepository
import com.example.data.repository.TradingRepository
import com.example.domain.engine.OrderBookSnapshot
import com.example.domain.engine.PriceAwareExecutionService
import com.example.domain.engine.RiskMath
import com.example.domain.model.MarketSegment
import com.example.domain.model.Order
import com.example.domain.model.OrderSide
import com.example.domain.model.OrderStatus
import com.example.domain.model.OrderType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import javax.inject.Inject

enum class OrderFilterTab(val label: String) {
    ALL("All"),
    PENDING("Pending"),
    EXECUTED("Executed"),
    CANCELLED("Cancelled")
}

data class OrdersUiState(
    val orders: List<Order> = emptyList(),
    val selectedTab: OrderFilterTab = OrderFilterTab.ALL,
    val pendingCount: Int = 0,
    val executedCount: Int = 0,
    val cancelledCount: Int = 0
)

/**
 * Isolated draft state for modifying a specific pending order strictly keyed by [orderId].
 * Prevents background order list updates from resetting text inputs or closing the dialog prematurely.
 */
data class PendingOrderEditorDraft(
    val orderId: String,
    val symbol: String,
    val segment: MarketSegment,
    val side: OrderSide,
    val orderType: OrderType,
    val legRole: String?,
    val lotSize: Int,
    val qtyText: String,
    val limitText: String,
    val triggerText: String,
    val slText: String,
    val targetText: String,
    val showLimit: Boolean,
    val showTrigger: Boolean,
    val canEditAttachedRisk: Boolean,
    val isSaving: Boolean = false,
    val validationError: String? = null
)

@HiltViewModel
class OrdersViewModel @Inject constructor(
    private val tradingRepository: TradingRepository,
    private val marketDataRepository: MarketDataRepository
) : ViewModel() {

    private val selectedTab = MutableStateFlow(OrderFilterTab.ALL)
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val _orderEditorDraft = MutableStateFlow<PendingOrderEditorDraft?>(null)
    val orderEditorDraft: StateFlow<PendingOrderEditorDraft?> = _orderEditorDraft.asStateFlow()

    @Volatile
    private var latestEditorTickSize: BigDecimal = BigDecimal("0.05")

    val editorOrderBook: StateFlow<OrderBookSnapshot?> = combine(
        _orderEditorDraft,
        marketDataRepository.instruments
    ) { draft, instruments ->
        if (draft == null) return@combine null
        val liveInst = instruments.firstOrNull { it.symbol.equals(draft.symbol, ignoreCase = true) }
            ?: return@combine null
        latestEditorTickSize = liveInst.tickSize
        PriceAwareExecutionService.buildOrderBookSnapshot(liveInst)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = null
    )

    val uiState: StateFlow<OrdersUiState> = combine(
        tradingRepository.orders,
        selectedTab
    ) { allOrders, tab ->
        val filtered = when (tab) {
            OrderFilterTab.ALL -> allOrders
            OrderFilterTab.PENDING -> allOrders.filter { it.status == OrderStatus.PENDING }
            OrderFilterTab.EXECUTED -> allOrders.filter { it.status == OrderStatus.EXECUTED }
            OrderFilterTab.CANCELLED -> allOrders.filter { it.status == OrderStatus.CANCELLED }
        }
        OrdersUiState(
            orders = filtered,
            selectedTab = tab,
            pendingCount = allOrders.count { it.status == OrderStatus.PENDING },
            executedCount = allOrders.count { it.status == OrderStatus.EXECUTED },
            cancelledCount = allOrders.count { it.status == OrderStatus.CANCELLED }
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = OrdersUiState()
    )

    fun selectTab(tab: OrderFilterTab) {
        selectedTab.value = tab
    }

    fun openOrderEditor(orderId: String) {
        val order = uiState.value.orders.firstOrNull { it.id == orderId } ?: return
        if (order.status != OrderStatus.PENDING) return
        val showLimit = order.orderType in setOf(OrderType.LIMIT, OrderType.SL_L, OrderType.TGT_L, OrderType.BRACKET) || order.limitPrice != null
        val showTrigger = order.orderType in setOf(OrderType.SL_M, OrderType.SL_L, OrderType.TGT_M, OrderType.TGT_L) || order.triggerPrice != null
        val canEditAttachedRisk = order.legRole == null || order.legRole == "ENTRY"
        _orderEditorDraft.value = PendingOrderEditorDraft(
            orderId = order.id,
            symbol = order.symbol,
            segment = order.segment,
            side = order.side,
            orderType = order.orderType,
            legRole = order.legRole,
            lotSize = order.lotSize.coerceAtLeast(1),
            qtyText = order.quantity.toString(),
            limitText = order.limitPrice?.toPlainString() ?: "",
            triggerText = order.triggerPrice?.toPlainString() ?: "",
            slText = order.attachedSlValue?.toPlainString() ?: "",
            targetText = order.attachedTargetValue?.toPlainString() ?: "",
            showLimit = showLimit,
            showTrigger = showTrigger,
            canEditAttachedRisk = canEditAttachedRisk
        )
    }

    fun dismissOrderEditor() {
        if (_orderEditorDraft.value?.isSaving == true) return
        _orderEditorDraft.value = null
    }

    fun updateEditorQty(text: String) {
        _orderEditorDraft.update { it?.copy(qtyText = text.filter(Char::isDigit), validationError = null) }
    }

    fun stepEditorQty(deltaLots: Int) {
        _orderEditorDraft.update { cur ->
            if (cur == null) return@update null
            val lot = cur.lotSize.coerceAtLeast(1)
            val current = cur.qtyText.toIntOrNull() ?: lot
            val next = (current + deltaLots * lot).coerceAtLeast(lot)
            cur.copy(qtyText = next.toString(), validationError = null)
        }
    }

    fun updateEditorLimit(text: String) {
        _orderEditorDraft.update { it?.copy(limitText = sanitizeDecimal(text), validationError = null) }
    }

    fun selectEditorLimitFromDepth(price: BigDecimal, tappedSide: OrderSide) {
        val rounded = RiskMath.roundToTick(price, latestEditorTickSize)
        _orderEditorDraft.update { cur ->
            if (cur == null) return@update null
            if (cur.showLimit) {
                cur.copy(limitText = rounded.toPlainString(), validationError = null)
            } else if (cur.showTrigger) {
                cur.copy(triggerText = rounded.toPlainString(), validationError = null)
            } else {
                cur
            }
        }
    }

    fun updateEditorTrigger(text: String) {
        _orderEditorDraft.update { it?.copy(triggerText = sanitizeDecimal(text), validationError = null) }
    }

    fun updateEditorSl(text: String) {
        _orderEditorDraft.update { it?.copy(slText = sanitizeDecimal(text), validationError = null) }
    }

    fun updateEditorTarget(text: String) {
        _orderEditorDraft.update { it?.copy(targetText = sanitizeDecimal(text), validationError = null) }
    }

    fun saveOrderEditor() {
        val draft = _orderEditorDraft.value ?: return
        if (draft.isSaving) return

        val qty = draft.qtyText.toIntOrNull()
        val limit = draft.limitText.trim().takeIf { it.isNotEmpty() }?.toBigDecimalOrNull()
        val trigger = draft.triggerText.trim().takeIf { it.isNotEmpty() }?.toBigDecimalOrNull()
        val sl = draft.slText.trim().takeIf { it.isNotEmpty() }?.toBigDecimalOrNull()
        val target = draft.targetText.trim().takeIf { it.isNotEmpty() }?.toBigDecimalOrNull()

        val err = when {
            qty == null || qty <= 0 -> "Enter a valid quantity greater than 0."
            draft.showLimit && draft.orderType != OrderType.BRACKET && (limit == null || limit <= BigDecimal.ZERO) ->
                "Enter a valid Limit price greater than 0."
            draft.showTrigger && (trigger == null || trigger <= BigDecimal.ZERO) ->
                "Enter a valid Trigger price greater than 0."
            draft.slText.trim().isNotEmpty() && (sl == null || sl <= BigDecimal.ZERO) ->
                "Enter a valid Attached SL greater than 0 (or leave blank)."
            draft.targetText.trim().isNotEmpty() && (target == null || target <= BigDecimal.ZERO) ->
                "Enter a valid Attached Target greater than 0 (or leave blank)."
            else -> null
        }
        if (err != null) {
            _orderEditorDraft.update { it?.copy(validationError = err) }
            return
        }

        _orderEditorDraft.update { it?.copy(isSaving = true, validationError = null) }
        viewModelScope.launch {
            tradingRepository.modifyPendingOrder(
                orderId = draft.orderId,
                newQuantity = qty!!,
                newLimitPrice = limit,
                newTriggerPrice = trigger,
                newAttachedSlValue = sl,
                newAttachedTargetValue = target,
                updateAttachedRisk = draft.canEditAttachedRisk
            ).onSuccess { msg ->
                _orderEditorDraft.value = null
                _messages.emit(msg)
            }.onFailure { failure ->
                val msg = failure.message ?: "Failed to modify pending order"
                _orderEditorDraft.update { it?.copy(isSaving = false, validationError = msg) }
                _messages.emit(msg)
            }
        }
    }

    fun cancelOrder(orderId: String) {
        viewModelScope.launch {
            tradingRepository.cancelOrder(orderId)
            _messages.emit("Order cancelled")
        }
    }

    fun modifyPendingOrder(
        orderId: String,
        newQuantity: Int,
        newLimitPrice: BigDecimal?,
        newTriggerPrice: BigDecimal?,
        newAttachedSlValue: BigDecimal?,
        newAttachedTargetValue: BigDecimal?
    ) {
        viewModelScope.launch {
            tradingRepository.modifyPendingOrder(
                orderId = orderId,
                newQuantity = newQuantity,
                newLimitPrice = newLimitPrice,
                newTriggerPrice = newTriggerPrice,
                newAttachedSlValue = newAttachedSlValue,
                newAttachedTargetValue = newAttachedTargetValue
            ).onSuccess { msg ->
                _messages.emit(msg)
            }.onFailure { err ->
                _messages.emit(err.message ?: "Failed to modify pending order")
            }
        }
    }

    private fun sanitizeDecimal(input: String): String {
        val filtered = input.filter { it.isDigit() || it == '.' }
        val firstDot = filtered.indexOf('.')
        return if (firstDot == -1) filtered else {
            filtered.substring(0, firstDot + 1) + filtered.substring(firstDot + 1).replace(".", "")
        }
    }
}
