package com.example.data.local

import androidx.room.TypeConverter
import com.example.domain.model.ExitReason
import com.example.domain.model.InstrumentType
import com.example.domain.model.MarketSegment
import com.example.domain.model.OptionType
import com.example.domain.model.OrderSide
import com.example.domain.model.OrderStatus
import com.example.domain.model.OrderType
import com.example.domain.model.ProductType
import com.example.domain.model.RiskInputMode
import com.example.domain.model.TrailMode
import com.example.domain.model.ValidityState
import com.example.domain.model.ValidityType
import com.example.domain.signal.SignalConfidence
import com.example.domain.signal.SignalDirection
import com.example.domain.signal.SignalStrategy
import com.example.domain.signal.Timeframe
import java.math.BigDecimal

class Converters {
    @TypeConverter
    fun fromBigDecimal(value: BigDecimal?): String? = value?.toPlainString()

    @TypeConverter
    fun toBigDecimal(value: String?): BigDecimal? = value?.let { BigDecimal(it) }

    @TypeConverter
    fun fromBigDecimalList(list: List<BigDecimal>?): String {
        return list?.joinToString(",") { it.toPlainString() } ?: ""
    }

    @TypeConverter
    fun toBigDecimalList(raw: String?): List<BigDecimal> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(",").mapNotNull { part ->
            part.trim().takeIf { it.isNotEmpty() }?.let { BigDecimal(it) }
        }
    }

    @TypeConverter
    fun fromStringList(list: List<String>?): String {
        return list?.joinToString("||") ?: ""
    }

    @TypeConverter
    fun toStringList(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split("||").map { it.trim() }.filter { it.isNotEmpty() }
    }

    @TypeConverter fun fromMarketSegment(v: MarketSegment): String = v.name
    @TypeConverter fun toMarketSegment(v: String): MarketSegment = MarketSegment.valueOf(v)

    @TypeConverter fun fromInstrumentType(v: InstrumentType): String = v.name
    @TypeConverter fun toInstrumentType(v: String): InstrumentType = InstrumentType.valueOf(v)

    @TypeConverter fun fromOptionType(v: OptionType?): String? = v?.name
    @TypeConverter fun toOptionType(v: String?): OptionType? = v?.let { OptionType.valueOf(it) }

    @TypeConverter fun fromOrderSide(v: OrderSide): String = v.name
    @TypeConverter fun toOrderSide(v: String): OrderSide = OrderSide.valueOf(v)

    @TypeConverter fun fromOrderType(v: OrderType): String = v.name
    @TypeConverter fun toOrderType(v: String): OrderType = OrderType.valueOf(v)

    @TypeConverter fun fromProductType(v: ProductType): String = v.name
    @TypeConverter fun toProductType(v: String): ProductType = ProductType.valueOf(v)

    @TypeConverter fun fromOrderStatus(v: OrderStatus): String = v.name
    @TypeConverter fun toOrderStatus(v: String): OrderStatus = OrderStatus.valueOf(v)

    @TypeConverter fun fromValidityType(v: ValidityType): String = v.name
    @TypeConverter fun toValidityType(v: String): ValidityType = ValidityType.valueOf(v)

    @TypeConverter fun fromValidityState(v: ValidityState): String = v.name
    @TypeConverter fun toValidityState(v: String): ValidityState = ValidityState.valueOf(v)

    @TypeConverter fun fromRiskInputMode(v: RiskInputMode?): String? = v?.name
    @TypeConverter fun toRiskInputMode(v: String?): RiskInputMode? = v?.let { RiskInputMode.valueOf(it) }

    @TypeConverter fun fromTrailMode(v: TrailMode?): String? = v?.name
    @TypeConverter fun toTrailMode(v: String?): TrailMode? = v?.let { TrailMode.valueOf(it) }

    @TypeConverter fun fromExitReason(v: ExitReason): String = v.name
    @TypeConverter fun toExitReason(v: String): ExitReason = ExitReason.valueOf(v)

    @TypeConverter fun fromTimeframe(v: Timeframe): String = v.name
    @TypeConverter fun toTimeframe(v: String): Timeframe = Timeframe.valueOf(v)

    @TypeConverter fun fromSignalStrategy(v: SignalStrategy): String = v.name
    @TypeConverter fun toSignalStrategy(v: String): SignalStrategy = SignalStrategy.valueOf(v)

    @TypeConverter fun fromSignalDirection(v: SignalDirection): String = v.name
    @TypeConverter fun toSignalDirection(v: String): SignalDirection = SignalDirection.valueOf(v)

    @TypeConverter fun fromSignalConfidence(v: SignalConfidence): String = v.name
    @TypeConverter fun toSignalConfidence(v: String): SignalConfidence = SignalConfidence.valueOf(v)
}
