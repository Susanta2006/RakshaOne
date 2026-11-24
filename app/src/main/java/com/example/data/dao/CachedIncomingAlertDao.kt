package com.example.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.model.CachedIncomingAlertEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CachedIncomingAlertDao {

    @Query("SELECT * FROM cached_incoming_alerts ORDER BY timestamp DESC")
    fun getAllCachedAlerts(): Flow<List<CachedIncomingAlertEntity>>

    @Query("SELECT * FROM cached_incoming_alerts WHERE alertType IN ('HEX_RESCUE', 'BLE_BEACON') ORDER BY timestamp DESC")
    fun getCachedHexAlerts(): Flow<List<CachedIncomingAlertEntity>>

    @Query("SELECT * FROM cached_incoming_alerts WHERE alertType IN ('HEX_RESCUE', 'BLE_BEACON') ORDER BY timestamp DESC")
    suspend fun getCachedHexAlertsOnce(): List<CachedIncomingAlertEntity>

    @Query("SELECT * FROM cached_incoming_alerts WHERE alertType IN ('HEX_RESCUE', 'BLE_BEACON') AND status NOT IN ('RESOLVED', 'EXPIRED', 'DECLINED') ORDER BY timestamp DESC")
    suspend fun getActiveHexAlertsOnce(): List<CachedIncomingAlertEntity>

    @Query("SELECT * FROM cached_incoming_alerts WHERE alertType = 'GUARDIAN_DISPATCH' ORDER BY timestamp DESC")
    fun getCachedGuardianDispatches(): Flow<List<CachedIncomingAlertEntity>>

    @Query("SELECT * FROM cached_incoming_alerts WHERE alertType = 'GUARDIAN_DISPATCH' ORDER BY timestamp DESC")
    suspend fun getCachedGuardianDispatchesOnce(): List<CachedIncomingAlertEntity>

    @Query("SELECT * FROM cached_incoming_alerts WHERE alertType = 'GUARDIAN_DISPATCH' AND status IN ('ACTIVE', 'CAPTURED') ORDER BY timestamp DESC")
    suspend fun getActiveGuardianDispatchesOnce(): List<CachedIncomingAlertEntity>

    @Query("SELECT * FROM cached_incoming_alerts WHERE alertId = :alertId LIMIT 1")
    suspend fun getAlertById(alertId: String): CachedIncomingAlertEntity?

    @Query("SELECT * FROM cached_incoming_alerts WHERE incidentId = :incidentId LIMIT 1")
    suspend fun getAlertByIncidentId(incidentId: String): CachedIncomingAlertEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAlert(alert: CachedIncomingAlertEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAlerts(alerts: List<CachedIncomingAlertEntity>)

    @Update
    suspend fun updateAlert(alert: CachedIncomingAlertEntity)

    @Query("DELETE FROM cached_incoming_alerts WHERE alertId = :alertId")
    suspend fun deleteAlert(alertId: String)

    @Query("DELETE FROM cached_incoming_alerts WHERE incidentId = :incidentId")
    suspend fun deleteByIncidentId(incidentId: String)

    @Query("DELETE FROM cached_incoming_alerts WHERE status IN ('RESOLVED', 'EXPIRED') OR cachedAtMillis < :olderThanMillis")
    suspend fun clearResolvedOrExpiredAlerts(olderThanMillis: Long = System.currentTimeMillis() - 86400000L)

    @Query("DELETE FROM cached_incoming_alerts")
    suspend fun clearAll()
}
