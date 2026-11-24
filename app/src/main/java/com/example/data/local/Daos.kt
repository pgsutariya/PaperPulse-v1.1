package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.domain.model.OrderStatus
import com.example.domain.model.ValidityState
import kotlinx.coroutines.flow.Flow
import java.math.BigDecimal

@Dao
interface InstrumentDao {
    @Query("SELECT * FROM instruments ORDER BY segment ASC, symbol ASC")
    fun observeAll(): Flow<List<InstrumentEntity>>

    @Query("SELECT * FROM instruments")
    suspend fun getAllOnce(): List<InstrumentEntity>

    @Query("SELECT * FROM instruments WHERE symbol = :symbol LIMIT 1")
    fun observeBySymbol(symbol: String): Flow<InstrumentEntity?>

    @Query("SELECT * FROM instruments WHERE symbol = :symbol LIMIT 1")
    suspend fun getBySymbol(symbol: String): InstrumentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<InstrumentEntity>)

    @Update
    suspend fun update(item: InstrumentEntity)

    @Query("SELECT COUNT(*) FROM instruments")
    suspend fun count(): Int
}

@Dao
interface OrderDao {
    @Query("SELECT * FROM orders ORDER BY createdAtMillis DESC")
    fun observeAll(): Flow<List<OrderEntity>>

    @Query("SELECT * FROM orders WHERE status = :status ORDER BY createdAtMillis DESC")
    suspend fun getByStatus(status: OrderStatus): List<OrderEntity>

    @Query("SELECT * FROM orders WHERE validityState = :state ORDER BY createdAtMillis DESC")
    suspend fun getByValidityState(state: ValidityState): List<OrderEntity>

    @Query("SELECT * FROM orders WHERE symbol = :symbol AND status = 'PENDING'")
    suspend fun getPendingForSymbol(symbol: String): List<OrderEntity>

    @Query("SELECT * FROM orders WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): OrderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(order: OrderEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(orders: List<OrderEntity>)

    @Query("DELETE FROM orders")
    suspend fun clearAll()
}

@Dao
interface PositionDao {
    @Query("SELECT * FROM positions ORDER BY openedAtMillis DESC")
    fun observeAll(): Flow<List<PositionEntity>>

    @Query("SELECT * FROM positions")
    suspend fun getAllOnce(): List<PositionEntity>

    @Query("SELECT * FROM positions WHERE symbol = :symbol")
    suspend fun getBySymbol(symbol: String): List<PositionEntity>

    @Query("SELECT * FROM positions WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): PositionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(position: PositionEntity)

    @Query("DELETE FROM positions WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM positions")
    suspend fun clearAll()
}

@Dao
interface OcoLinkDao {
    @Query("SELECT * FROM oco_links WHERE ocoGroupId = :groupId LIMIT 1")
    suspend fun getByGroupId(groupId: String): OcoLinkEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(link: OcoLinkEntity)

    @Query("UPDATE oco_links SET isActive = 0 WHERE ocoGroupId = :groupId")
    suspend fun deactivate(groupId: String)

    @Query("DELETE FROM oco_links")
    suspend fun clearAll()
}

@Dao
interface TradeJournalDao {
    @Query("SELECT * FROM trade_journal ORDER BY closedAtMillis DESC")
    fun observeAll(): Flow<List<TradeJournalEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: TradeJournalEntity)

    @Query("UPDATE trade_journal SET notes = :notes WHERE id = :id")
    suspend fun updateNotes(id: String, notes: String)

    @Query("DELETE FROM trade_journal")
    suspend fun clearAll()
}

@Dao
interface AccountDao {
    @Query("SELECT * FROM account_state WHERE id = 1")
    fun observeAccount(): Flow<AccountStateEntity?>

    @Query("SELECT * FROM account_state WHERE id = 1")
    suspend fun getAccount(): AccountStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(account: AccountStateEntity)

    @Query("UPDATE account_state SET availableMargin = :margin, realisedPnl = :realised WHERE id = 1")
    suspend fun updateBalances(margin: BigDecimal, realised: BigDecimal)

    @Query("UPDATE account_state SET cryptoAvailableMargin = :margin, cryptoRealisedPnl = :realised WHERE id = 1")
    suspend fun updateCryptoBalances(margin: BigDecimal, realised: BigDecimal)
}

@Dao
interface CandleDao {
    @Query("SELECT * FROM candles WHERE symbol = :symbol AND timeframe = :timeframe ORDER BY timestampMillis DESC LIMIT :limit")
    suspend fun getRecent(
        symbol: String,
        timeframe: com.example.domain.signal.Timeframe,
        limit: Int = 200
    ): List<CandleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(candle: CandleEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(candles: List<CandleEntity>)

    @Query("DELETE FROM candles WHERE symbol = :symbol AND timeframe = :timeframe AND timestampMillis < :cutoffMillis")
    suspend fun pruneOlderThan(symbol: String, timeframe: com.example.domain.signal.Timeframe, cutoffMillis: Long)

    @Query("DELETE FROM candles WHERE symbol = :symbol AND timeframe = :timeframe")
    suspend fun deleteFor(symbol: String, timeframe: com.example.domain.signal.Timeframe)
}

@Dao
interface SignalDao {
    @Query("SELECT * FROM signals ORDER BY generatedAtMillis DESC")
    fun observeAll(): Flow<List<SignalEntity>>

    @Query("SELECT * FROM signals WHERE symbol = :symbol ORDER BY generatedAtMillis DESC")
    fun observeForSymbol(symbol: String): Flow<List<SignalEntity>>

    @Query("SELECT * FROM signals")
    suspend fun getAllOnce(): List<SignalEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(signal: SignalEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(signals: List<SignalEntity>)

    @Query("UPDATE signals SET userStatus = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String)

    @Query("DELETE FROM signals WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM signals WHERE symbol = :symbol AND userStatus != 'DISMISSED'")
    suspend fun getActiveForSymbol(symbol: String): List<SignalEntity>

    @Query("SELECT * FROM signals WHERE expiresAtMillis IS NOT NULL AND expiresAtMillis <= :nowMillis")
    suspend fun getExpired(nowMillis: Long): List<SignalEntity>

    @Query("DELETE FROM signals WHERE expiresAtMillis IS NOT NULL AND expiresAtMillis <= :nowMillis")
    suspend fun deleteExpired(nowMillis: Long)

    @Query("DELETE FROM signals WHERE symbol = :symbol AND strategy = :strategy AND id != :keepId AND userStatus = 'ACTIVE'")
    suspend fun replacePreviousActive(symbol: String, strategy: com.example.domain.signal.SignalStrategy, keepId: String)
}

@Dao
interface SavedSignalDao {
    @Query("SELECT * FROM saved_signals ORDER BY savedAtMillis DESC")
    fun observeAll(): Flow<List<SavedSignalEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(entity: SavedSignalEntity)

    @Query("DELETE FROM saved_signals WHERE id = :id")
    suspend fun remove(id: String)

    @Query("SELECT id FROM saved_signals")
    fun observeSavedIds(): Flow<List<String>>
}

@Dao
interface CapitalTransactionDao {
    @Query("SELECT * FROM capital_transactions ORDER BY timestampMillis DESC")
    fun observeAll(): Flow<List<CapitalTransactionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(tx: CapitalTransactionEntity)

    @Query("DELETE FROM capital_transactions")
    suspend fun clearAll()
}

@Dao
interface SignalRecordDao {
    @Query("SELECT * FROM signal_records ORDER BY resolvedAtMillis DESC")
    fun observeAll(): Flow<List<SignalRecordEntity>>

    @Query("SELECT * FROM signal_records WHERE symbol = :symbol AND outcome = 'HIT_T1'")
    suspend fun getT1HitForSymbol(symbol: String): List<SignalRecordEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: SignalRecordEntity)

    @Query("DELETE FROM signal_records")
    suspend fun clearAll()
}
