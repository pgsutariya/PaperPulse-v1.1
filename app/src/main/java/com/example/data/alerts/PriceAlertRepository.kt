package com.example.data.alerts

import com.example.data.local.PriceAlertDao
import com.example.data.local.PriceAlertEntity
import com.example.data.notifications.TradeNotificationManager
import com.example.data.repository.MarketDataRepository
import com.example.domain.engine.RiskMath
import com.example.domain.model.Instrument
import com.example.domain.model.MarketSegment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

enum class PriceAlertCondition(val label: String, val symbolText: String) {
    ABOVE("Price rises to or above", "≥"),
    BELOW("Price falls to or below", "≤")
}

data class PriceAlert(
    val id: String,
    val symbol: String,
    val segment: MarketSegment,
    val targetPrice: BigDecimal,
    val condition: PriceAlertCondition,
    val note: String = "",
    val isActive: Boolean = true,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val triggeredAtMillis: Long? = null,
    val triggeredPrice: BigDecimal? = null
)

data class TriggeredPriceAlertEvent(
    val alert: PriceAlert,
    val hitPrice: BigDecimal,
    val title: String,
    val message: String
)

/**
 * Manages persistent Watchlist Price Alerts and evaluates them in real time
 * against incoming market ticks, dispatching both Android system notifications
 * and in-app alert events immediately when a target price is hit.
 */
@Singleton
class PriceAlertRepository @Inject constructor(
    private val priceAlertDao: PriceAlertDao,
    private val marketDataRepository: MarketDataRepository,
    private val notificationManager: TradeNotificationManager,
    private val applicationScope: CoroutineScope
) {
    val allAlerts: Flow<List<PriceAlert>> = priceAlertDao.observeAll().map { list ->
        list.mapNotNull { it.toDomainOrNull() }
    }

    val activeAlerts: Flow<List<PriceAlert>> = priceAlertDao.observeActive().map { list ->
        list.mapNotNull { it.toDomainOrNull() }
    }

    private val _triggeredEvents = MutableSharedFlow<TriggeredPriceAlertEvent>(extraBufferCapacity = 16)
    val triggeredEvents: SharedFlow<TriggeredPriceAlertEvent> = _triggeredEvents.asSharedFlow()

    private val activeBySymbol = ConcurrentHashMap<String, List<PriceAlert>>()
    private val triggeredGuard = ConcurrentHashMap.newKeySet<String>()
    private val started = AtomicBoolean(false)

    fun startMonitoring() {
        if (!started.compareAndSet(false, true)) return

        applicationScope.launch {
            activeAlerts.collect { alerts ->
                val grouped = alerts.groupBy { it.symbol.uppercase(Locale.US) }
                activeBySymbol.clear()
                activeBySymbol.putAll(grouped)
                val activeIds = alerts.map { it.id }.toSet()
                triggeredGuard.retainAll(activeIds)
            }
        }

        applicationScope.launch {
            marketDataRepository.ticks.collect { tick ->
                evaluatePrice(tick.symbol, tick.ltp)
            }
        }

        applicationScope.launch {
            marketDataRepository.instruments.collect { instruments ->
                if (activeBySymbol.isEmpty()) return@collect
                for (inst in instruments) {
                    if (activeBySymbol.containsKey(inst.symbol.uppercase(Locale.US))) {
                        evaluatePrice(inst.symbol, inst.ltp)
                    }
                }
            }
        }
    }

    suspend fun createAlert(
        instrument: Instrument,
        targetPrice: BigDecimal,
        condition: PriceAlertCondition? = null,
        note: String = ""
    ): Result<PriceAlert> {
        if (targetPrice <= BigDecimal.ZERO) {
            return Result.failure(IllegalArgumentException("Target price must be greater than zero."))
        }
        val resolvedCondition = condition ?: inferCondition(targetPrice, instrument.ltp)
        val alert = PriceAlert(
            id = "pa_${UUID.randomUUID().toString().take(12)}",
            symbol = instrument.symbol,
            segment = instrument.segment,
            targetPrice = targetPrice,
            condition = resolvedCondition,
            note = note.trim().take(80),
            isActive = true,
            createdAtMillis = System.currentTimeMillis()
        )
        priceAlertDao.upsert(alert.toEntity())
        return Result.success(alert)
    }

    suspend fun rearmAlert(alert: PriceAlert, currentLtp: BigDecimal) {
        val nextCondition = inferCondition(alert.targetPrice, currentLtp)
        triggeredGuard.remove(alert.id)
        priceAlertDao.rearm(alert.id, nextCondition.name)
    }

    suspend fun deleteAlert(alertId: String) {
        triggeredGuard.remove(alertId)
        priceAlertDao.deleteById(alertId)
    }

    suspend fun clearTriggeredAlerts() {
        priceAlertDao.deleteTriggered()
    }

    fun hasSystemNotificationPermission(): Boolean = notificationManager.hasSystemPermission()

    private suspend fun evaluatePrice(symbol: String, currentLtp: BigDecimal) {
        if (currentLtp <= BigDecimal.ZERO) return
        val alertsForSymbol = activeBySymbol[symbol.uppercase(Locale.US)] ?: return
        for (alert in alertsForSymbol) {
            if (!alert.isActive) continue
            if (isConditionMet(alert.condition, alert.targetPrice, currentLtp)) {
                if (!triggeredGuard.add(alert.id)) continue
                triggerAlert(alert, currentLtp)
            }
        }
    }

    private suspend fun triggerAlert(alert: PriceAlert, hitPrice: BigDecimal) {
        val now = System.currentTimeMillis()
        priceAlertDao.markTriggered(
            id = alert.id,
            triggeredAtMillis = now,
            triggeredPrice = hitPrice.toPlainString()
        )
        val targetFmt = RiskMath.formatPrice(alert.targetPrice, alert.segment)
        val hitFmt = RiskMath.formatPrice(hitPrice, alert.segment)
        val title = "Price Alert Hit: ${alert.symbol}"
        val noteSuffix = if (alert.note.isNotBlank()) " • ${alert.note}" else ""
        val body = "${alert.symbol} reached $hitFmt (target ${alert.condition.symbolText} $targetFmt)$noteSuffix"

        notificationManager.notifyPriceAlert(title = title, body = body)
        _triggeredEvents.tryEmit(
            TriggeredPriceAlertEvent(
                alert = alert.copy(
                    isActive = false,
                    triggeredAtMillis = now,
                    triggeredPrice = hitPrice
                ),
                hitPrice = hitPrice,
                title = title,
                message = body
            )
        )
    }

    private fun PriceAlert.toEntity(): PriceAlertEntity = PriceAlertEntity(
        id = id,
        symbol = symbol,
        segment = segment.name,
        targetPrice = targetPrice.toPlainString(),
        condition = condition.name,
        note = note,
        isActive = isActive,
        createdAtMillis = createdAtMillis,
        triggeredAtMillis = triggeredAtMillis,
        triggeredPrice = triggeredPrice?.toPlainString()
    )

    private fun PriceAlertEntity.toDomainOrNull(): PriceAlert? {
        val seg = runCatching { MarketSegment.valueOf(segment) }.getOrNull() ?: return null
        val cond = runCatching { PriceAlertCondition.valueOf(condition) }.getOrNull() ?: PriceAlertCondition.ABOVE
        val target = targetPrice.toBigDecimalOrNull() ?: return null
        val trigPrice = triggeredPrice?.toBigDecimalOrNull()
        return PriceAlert(
            id = id,
            symbol = symbol,
            segment = seg,
            targetPrice = target,
            condition = cond,
            note = note,
            isActive = isActive,
            createdAtMillis = createdAtMillis,
            triggeredAtMillis = triggeredAtMillis,
            triggeredPrice = trigPrice
        )
    }

    companion object {
        /**
         * Determines default trigger direction relative to current LTP:
         * if target >= LTP -> ABOVE (trigger when price rises to or above target),
         * if target < LTP -> BELOW (trigger when price drops to or below target).
         */
        fun inferCondition(targetPrice: BigDecimal, currentLtp: BigDecimal): PriceAlertCondition {
            return if (targetPrice >= currentLtp) {
                PriceAlertCondition.ABOVE
            } else {
                PriceAlertCondition.BELOW
            }
        }

        fun isConditionMet(
            condition: PriceAlertCondition,
            targetPrice: BigDecimal,
            currentLtp: BigDecimal
        ): Boolean {
            return when (condition) {
                PriceAlertCondition.ABOVE -> currentLtp >= targetPrice
                PriceAlertCondition.BELOW -> currentLtp <= targetPrice
            }
        }
    }
}
