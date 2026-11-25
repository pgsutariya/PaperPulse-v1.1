package com.example.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
        SignalRecordEntity::class,
        PriceAlertEntity::class
    ],
    version = 6,
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
    abstract fun priceAlertDao(): PriceAlertDao

    companion object {
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE account_state ADD COLUMN cryptoVirtualCapital TEXT NOT NULL DEFAULT '10000.00'")
                db.execSQL("ALTER TABLE account_state ADD COLUMN cryptoAvailableMargin TEXT NOT NULL DEFAULT '10000.00'")
                db.execSQL("ALTER TABLE account_state ADD COLUMN cryptoRealisedPnl TEXT NOT NULL DEFAULT '0.00'")
                db.execSQL("ALTER TABLE capital_transactions ADD COLUMN currency TEXT NOT NULL DEFAULT 'INR'")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE trade_journal ADD COLUMN tagsCsv TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `price_alerts` (
                        `id` TEXT NOT NULL,
                        `symbol` TEXT NOT NULL,
                        `segment` TEXT NOT NULL,
                        `targetPrice` TEXT NOT NULL,
                        `condition` TEXT NOT NULL,
                        `note` TEXT NOT NULL,
                        `isActive` INTEGER NOT NULL,
                        `createdAtMillis` INTEGER NOT NULL,
                        `triggeredAtMillis` INTEGER,
                        `triggeredPrice` TEXT,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
            }
        }
    }
}
