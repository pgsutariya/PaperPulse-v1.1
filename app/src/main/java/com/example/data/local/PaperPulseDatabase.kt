package com.example.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        InstrumentEntity::class,
        OrderEntity::class,
        PositionEntity::class,
        OcoLinkEntity::class,
        TradeJournalEntity::class,
        CapitalTransactionEntity::class,
        AccountStateEntity::class,
        CandleEntity::class,
        SignalEntity::class,
        SavedSignalEntity::class,
        SignalRecordEntity::class
    ],
    version = 3,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class PaperPulseDatabase : RoomDatabase() {
    abstract fun instrumentDao(): InstrumentDao
    abstract fun orderDao(): OrderDao
    abstract fun positionDao(): PositionDao
    abstract fun ocoLinkDao(): OcoLinkDao
    abstract fun tradeJournalDao(): TradeJournalDao
    abstract fun capitalTransactionDao(): CapitalTransactionDao
    abstract fun accountDao(): AccountDao
    abstract fun candleDao(): CandleDao
    abstract fun signalDao(): SignalDao
    abstract fun savedSignalDao(): SavedSignalDao
    abstract fun signalRecordDao(): SignalRecordDao
}
