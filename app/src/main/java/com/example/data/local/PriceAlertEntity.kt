package com.example.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "price_alerts")
data class PriceAlertEntity(
    @PrimaryKey val id: String,
    val symbol: String,
    val segment: String,
    val targetPrice: String,
    val condition: String,
    val note: String = "",
    val isActive: Boolean = true,
    val createdAtMillis: Long,
    val triggeredAtMillis: Long? = null,
    val triggeredPrice: String? = null
)

@Dao
interface PriceAlertDao {
    @Query("SELECT * FROM price_alerts ORDER BY isActive DESC, COALESCE(triggeredAtMillis, createdAtMillis) DESC")
    fun observeAll(): Flow<List<PriceAlertEntity>>

    @Query("SELECT * FROM price_alerts WHERE isActive = 1 ORDER BY createdAtMillis DESC")
    fun observeActive(): Flow<List<PriceAlertEntity>>

    @Query("SELECT * FROM price_alerts WHERE isActive = 1")
    suspend fun getActiveOnce(): List<PriceAlertEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PriceAlertEntity)

    @Query("UPDATE price_alerts SET isActive = 0, triggeredAtMillis = :triggeredAtMillis, triggeredPrice = :triggeredPrice WHERE id = :id")
    suspend fun markTriggered(id: String, triggeredAtMillis: Long, triggeredPrice: String)

    @Query("UPDATE price_alerts SET isActive = 1, condition = :condition, triggeredAtMillis = NULL, triggeredPrice = NULL WHERE id = :id")
    suspend fun rearm(id: String, condition: String)

    @Query("DELETE FROM price_alerts WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM price_alerts WHERE isActive = 0")
    suspend fun deleteTriggered()
}
