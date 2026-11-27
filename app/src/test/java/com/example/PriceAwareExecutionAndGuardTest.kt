package com.example

import com.example.domain.engine.PriceAwareExecutionDecision
import com.example.domain.engine.PriceAwareExecutionService
import com.example.domain.model.Instrument
import com.example.domain.model.InstrumentType
import com.example.domain.model.MarketSegment
import com.example.domain.model.OrderSide
import com.example.domain.model.OrderType
import com.example.ui.components.GlobalHotkeyGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class PriceAwareExecutionAndGuardTest {

    private fun sampleInstrument(
        ltp: String,
        volume: Long = 1_500_000L,
        lotSize: Int = 1,
        type: InstrumentType = InstrumentType.STOCK
    ) = Instrument(
        symbol = "RELIANCE",
        displayName = "Reliance Industries",
        segment = MarketSegment.NSE,
        type = type,
        lotSize = lotSize,
        tickSize = BigDecimal("0.05"),
        ltp = BigDecimal(ltp),
        previousClose = BigDecimal("100.00"),
        dayHigh = BigDecimal("112.00"),
        dayLow = BigDecimal("88.00"),
        volume = volume
    )

    @Test
    fun buySignalEntry100_whenLtpIs110_routesAsRestingLimitAtEntry() {
        val inst = sampleInstrument(ltp = "110.00")
        val plan = PriceAwareExecutionService.evaluate(
            instrument = inst,
            side = OrderSide.BUY,
            quantity = 10,
            requestedOrderType = OrderType.BRACKET,
            targetEntryPrice = BigDecimal("100.00"),
            userLimitPrice = null,
            userTriggerPrice = null,
            slippageTolerancePercent = 0.35,
            priceAwareGuardEnabled = true
        )

        assertEquals(PriceAwareExecutionDecision.PROTECTED_LIMIT_AT_ENTRY, plan.decision)
        assertTrue(plan.wasConvertedToProtectedOrder)
        assertEquals(OrderType.BRACKET, plan.effectiveOrderType)
        assertEquals(0, BigDecimal("100.00").compareTo(plan.effectiveLimitPrice))
    }

    @Test
    fun buySignalEntry100_whenLtpIs90_routesAsStopLimitTriggerAtEntry() {
        val inst = sampleInstrument(ltp = "90.00")
        val plan = PriceAwareExecutionService.evaluate(
            instrument = inst,
            side = OrderSide.BUY,
            quantity = 10,
            requestedOrderType = OrderType.BRACKET,
            targetEntryPrice = BigDecimal("100.00"),
            userLimitPrice = BigDecimal("100.00"),
            userTriggerPrice = null,
            slippageTolerancePercent = 0.35,
            priceAwareGuardEnabled = true
        )

        assertEquals(PriceAwareExecutionDecision.PROTECTED_STOP_LIMIT_ENTRY, plan.decision)
        assertTrue(plan.wasConvertedToProtectedOrder)
        assertEquals(OrderType.SL_L, plan.effectiveOrderType)
        assertEquals(0, BigDecimal("100.00").compareTo(plan.effectiveTriggerPrice))
        assertEquals(0, BigDecimal("100.35").compareTo(plan.effectiveLimitPrice))
    }

    @Test
    fun sellSignalEntry100_whenLtpIs90_preventsUnfavorableChaseWithRestingLimit() {
        val inst = sampleInstrument(ltp = "90.00")
        val plan = PriceAwareExecutionService.evaluate(
            instrument = inst,
            side = OrderSide.SELL,
            quantity = 10,
            requestedOrderType = OrderType.MARKET,
            targetEntryPrice = BigDecimal("100.00"),
            userLimitPrice = null,
            userTriggerPrice = null,
            slippageTolerancePercent = 0.35,
            priceAwareGuardEnabled = true
        )

        assertEquals(PriceAwareExecutionDecision.PROTECTED_LIMIT_AT_ENTRY, plan.decision)
        assertEquals(OrderType.LIMIT, plan.effectiveOrderType)
        assertEquals(0, BigDecimal("100.00").compareTo(plan.effectiveLimitPrice))
    }

    @Test
    fun sellSignalEntry100_whenLtpIs110_waitsViaStopLimitTriggerAtEntry() {
        val inst = sampleInstrument(ltp = "110.00")
        val plan = PriceAwareExecutionService.evaluate(
            instrument = inst,
            side = OrderSide.SELL,
            quantity = 10,
            requestedOrderType = OrderType.BRACKET,
            targetEntryPrice = BigDecimal("100.00"),
            userLimitPrice = BigDecimal("100.00"),
            userTriggerPrice = null,
            slippageTolerancePercent = 0.35,
            priceAwareGuardEnabled = true
        )

        assertEquals(PriceAwareExecutionDecision.PROTECTED_STOP_LIMIT_ENTRY, plan.decision)
        assertEquals(OrderType.SL_L, plan.effectiveOrderType)
        assertEquals(0, BigDecimal("100.00").compareTo(plan.effectiveTriggerPrice))
        assertEquals(0, BigDecimal("99.65").compareTo(plan.effectiveLimitPrice))
    }

    @Test
    fun signalEntry100_whenLtpAndBookWithinTolerance_allowsEntryZoneExecution() {
        val inst = sampleInstrument(ltp = "100.10")
        val plan = PriceAwareExecutionService.evaluate(
            instrument = inst,
            side = OrderSide.BUY,
            quantity = 10,
            requestedOrderType = OrderType.BRACKET,
            targetEntryPrice = BigDecimal("100.00"),
            userLimitPrice = null,
            userTriggerPrice = null,
            slippageTolerancePercent = 0.50,
            priceAwareGuardEnabled = true
        )

        assertEquals(PriceAwareExecutionDecision.WITHIN_ENTRY_ZONE, plan.decision)
        assertFalse(plan.wasConvertedToProtectedOrder)
        assertEquals(OrderType.BRACKET, plan.effectiveOrderType)
        assertTrue(plan.orderBook.bestAsk >= plan.orderBook.bestBid)
    }

    @Test
    fun thinOrderBook_largeOrderSweepsBookBeyondTolerance_convertsToProtectedLimit() {
        val illiquidOption = sampleInstrument(
            ltp = "100.00",
            volume = 20_000L,
            lotSize = 50,
            type = InstrumentType.OPTIONS
        )
        // Quantity far exceeds top-5 ask liquidity (6 * 16 * 50 = 4800)
        val plan = PriceAwareExecutionService.evaluate(
            instrument = illiquidOption,
            side = OrderSide.BUY,
            quantity = 15_000,
            requestedOrderType = OrderType.MARKET,
            targetEntryPrice = BigDecimal("100.00"),
            userLimitPrice = null,
            userTriggerPrice = null,
            slippageTolerancePercent = 0.25,
            priceAwareGuardEnabled = true
        )

        assertTrue(plan.bookFillEstimate.exhaustedBook)
        assertEquals(PriceAwareExecutionDecision.PROTECTED_LIMIT_AT_ENTRY, plan.decision)
        assertNotNull(plan.effectiveLimitPrice)
    }

    @Test
    fun keyboardAwareNavigationGuard_locksOnImeOrModalOrInputFocus() {
        val guard = GlobalHotkeyGuard()
        assertFalse(guard.isNavigationLocked)

        guard.setImeVisible(true)
        assertTrue(guard.isNavigationLocked)
        assertTrue(guard.isSuppressed)

        guard.setImeVisible(false)
        assertFalse(guard.isNavigationLocked)

        guard.acquireModalLock()
        assertTrue(guard.isNavigationLocked)

        guard.acquireInputFocus()
        guard.releaseModalLock()
        assertTrue("Still locked while input focused", guard.isNavigationLocked)

        guard.releaseInputFocus()
        assertFalse("Unlocked once IME, modal, and inputs all clear", guard.isNavigationLocked)
    }
}
