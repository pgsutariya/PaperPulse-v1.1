package com.example.ui.screens.orders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.repository.TradingRepository
import com.example.domain.model.Order
import com.example.domain.model.OrderStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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

@HiltViewModel
class OrdersViewModel @Inject constructor(
    private val tradingRepository: TradingRepository
) : ViewModel() {

    private val selectedTab = MutableStateFlow(OrderFilterTab.ALL)

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

    fun cancelOrder(orderId: String) {
        viewModelScope.launch {
            tradingRepository.cancelOrder(orderId)
        }
    }
}
