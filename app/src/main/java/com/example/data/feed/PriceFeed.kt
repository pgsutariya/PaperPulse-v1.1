package com.example.data.feed

import com.example.domain.model.FeedConnectionState
import com.example.domain.model.Instrument
import com.example.domain.model.PriceTick
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.math.BigDecimal

interface PriceFeed {
    val ticks: Flow<PriceTick>
    val connectionState: StateFlow<FeedConnectionState>

    fun setUniverse(instruments: List<Instrument>)
    fun start()
    fun stop()
    suspend fun nudgePrice(symbol: String, percentDelta: BigDecimal)
}
