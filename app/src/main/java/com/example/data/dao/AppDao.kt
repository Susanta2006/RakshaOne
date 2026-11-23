package com.example.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.model.AuditLogEntity
import com.example.data.model.DossierAccessEntity
import com.example.data.model.EscalationWorkflowEntity
import com.example.data.model.EvidenceEntity
import com.example.data.model.HighRiskZoneEntity
import com.example.data.model.IncidentEntity
import com.example.data.model.IncidentEventEntity
import com.example.data.model.OfflineAlertEntity
import com.example.data.model.PointsLedgerEntity
import com.example.data.model.RiskAssessmentEntity
import com.example.data.model.SafetyGuideEntity
import com.example.data.model.SavedRoutineEntity
import com.example.data.model.TripEntity
import com.example.data.model.TripWaypointEntity
import com.example.data.model.TrustedContactEntity
import com.example.data.model.UserProfileEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDao {
    @Query("SELECT * FROM user_profile WHERE id = 'USER-001' LIMIT 1")
    fun getUserProfile(): Flow<UserProfileEntity?>

    @Query("SELECT * FROM user_profile WHERE id = 'USER-001' LIMIT 1")
    suspend fun getUserProfileOnce(): UserProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveUserProfile(profile: UserProfileEntity)
}

@Dao
interface TrustedContactDao {
    @Query("SELECT * FROM trusted_contacts ORDER BY priority ASC")
    fun getAllContacts(): Flow<List<TrustedContactEntity>>

    @Query("SELECT * FROM trusted_contacts ORDER BY priority ASC")
    suspend fun getAllContactsOnce(): List<TrustedContactEntity>

    @Query("SELECT * FROM trusted_contacts WHERE isEnabled = 1 ORDER BY priority ASC")
    suspend fun getActiveContacts(): List<TrustedContactEntity>

    @Query("SELECT COUNT(*) FROM trusted_contacts WHERE isEnabled = 1")
    suspend fun getActiveContactCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertContact(contact: TrustedContactEntity): Long

    @Update
    suspend fun updateContact(contact: TrustedContactEntity)

    @Query("DELETE FROM trusted_contacts WHERE id = :id")
    suspend fun deleteContact(id: Long)
}

@Dao
interface IncidentDao {
    @Query("SELECT * FROM incidents ORDER BY createdAt DESC")
    fun getAllIncidents(): Flow<List<IncidentEntity>>

    @Query("SELECT * FROM incidents WHERE status NOT IN ('RESOLVED', 'CLOSED', 'CANCELLED') ORDER BY createdAt DESC")
    fun getActiveIncidents(): Flow<List<IncidentEntity>>

    @Query("SELECT * FROM incidents WHERE status NOT IN ('RESOLVED', 'CLOSED', 'CANCELLED') ORDER BY createdAt DESC LIMIT 1")
    fun getLatestActiveIncident(): Flow<IncidentEntity?>

    @Query("SELECT * FROM incidents WHERE status NOT IN ('RESOLVED', 'CLOSED', 'CANCELLED') ORDER BY createdAt DESC LIMIT 1")
    suspend fun getLatestActiveIncidentOnce(): IncidentEntity?

    @Query("SELECT * FROM incidents WHERE status NOT IN ('RESOLVED', 'CLOSED', 'CANCELLED') ORDER BY createdAt DESC")
    suspend fun getActiveIncidentsOnce(): List<IncidentEntity>

    @Query("SELECT * FROM incidents WHERE incidentId = :id LIMIT 1")
    suspend fun getIncidentById(id: String): IncidentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertIncident(incident: IncidentEntity)

    @Update
    suspend fun updateIncident(incident: IncidentEntity)

    @Query("UPDATE incidents SET status = :newStatus, updatedAt = :timestamp WHERE incidentId = :id")
    suspend fun updateIncidentStatus(id: String, newStatus: String, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE incidents SET assignedUnit = :unit, status = 'ASSIGNED', updatedAt = :timestamp WHERE incidentId = :id")
    suspend fun assignUnit(id: String, unit: String, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE incidents SET operatorNotes = :notes, updatedAt = :timestamp WHERE incidentId = :id")
    suspend fun updateOperatorNotes(id: String, notes: String, timestamp: Long = System.currentTimeMillis())
}

@Dao
interface IncidentEventDao {
    @Query("SELECT * FROM incident_events WHERE incidentId = :incidentId ORDER BY timestamp ASC")
    fun getEventsForIncident(incidentId: String): Flow<List<IncidentEventEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(event: IncidentEventEntity)
}

@Dao
interface TripDao {
    @Query("SELECT * FROM trips ORDER BY startTime DESC")
    fun getAllTrips(): Flow<List<TripEntity>>

    @Query("SELECT * FROM trips WHERE status IN ('STARTED', 'MONITORING') ORDER BY startTime DESC LIMIT 1")
    fun getActiveTrip(): Flow<TripEntity?>

    @Query("SELECT * FROM trips WHERE status IN ('STARTED', 'MONITORING') ORDER BY startTime DESC LIMIT 1")
    suspend fun getActiveTripOnce(): TripEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrip(trip: TripEntity)

    @Update
    suspend fun updateTrip(trip: TripEntity)
}

@Dao
interface TripWaypointDao {
    @Query("SELECT * FROM trip_waypoints WHERE tripId = :tripId ORDER BY timestamp DESC")
    fun getWaypointsForTrip(tripId: String): Flow<List<TripWaypointEntity>>

    @Query("SELECT * FROM trip_waypoints WHERE tripId = :tripId ORDER BY timestamp DESC")
    suspend fun getWaypointsForTripOnce(tripId: String): List<TripWaypointEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWaypoint(waypoint: TripWaypointEntity): Long
}

@Dao
interface EvidenceDao {
    @Query("SELECT * FROM evidence ORDER BY uploadTime DESC")
    fun getAllEvidence(): Flow<List<EvidenceEntity>>

    @Query("SELECT * FROM evidence WHERE incidentId = :incidentId ORDER BY uploadTime DESC")
    fun getEvidenceForIncident(incidentId: String): Flow<List<EvidenceEntity>>

    @Query("SELECT * FROM evidence WHERE incidentId = :incidentId ORDER BY uploadTime DESC")
    suspend fun getEvidenceForIncidentOnce(incidentId: String): List<EvidenceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvidence(evidence: EvidenceEntity)

    @Query("DELETE FROM evidence WHERE incidentId = :incidentId")
    suspend fun deleteEvidenceForIncident(incidentId: String)
}

@Dao
interface AuditLogDao {
    @Query("SELECT * FROM audit_logs ORDER BY timestamp DESC")
    fun getAllLogs(): Flow<List<AuditLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: AuditLogEntity)
}

@Dao
interface SafetyGuideDao {
    @Query("SELECT * FROM safety_guides")
    fun getAllGuides(): Flow<List<SafetyGuideEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGuides(guides: List<SafetyGuideEntity>)
}

@Dao
interface OfflineAlertDao {
    @Query("SELECT * FROM offline_emergency_alerts ORDER BY timestamp DESC")
    fun getAllAlerts(): Flow<List<OfflineAlertEntity>>

    @Query("SELECT * FROM offline_emergency_alerts WHERE status = 'PENDING_CONNECTIVITY' ORDER BY timestamp ASC")
    fun getPendingAlerts(): Flow<List<OfflineAlertEntity>>

    @Query("SELECT * FROM offline_emergency_alerts WHERE status = 'PENDING_CONNECTIVITY' ORDER BY timestamp ASC")
    suspend fun getPendingAlertsOnce(): List<OfflineAlertEntity>

    @Query("SELECT COUNT(*) FROM offline_emergency_alerts WHERE status = 'PENDING_CONNECTIVITY'")
    fun getPendingCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAlert(alert: OfflineAlertEntity)

    @Update
    suspend fun updateAlert(alert: OfflineAlertEntity)

    @Query("UPDATE offline_emergency_alerts SET status = 'DISPATCHED', isDispatched = 1, lastAttemptTime = :timestamp WHERE alertId = :alertId")
    suspend fun markDispatched(alertId: String, timestamp: Long = System.currentTimeMillis())

    @Query("DELETE FROM offline_emergency_alerts WHERE alertId = :alertId")
    suspend fun deleteAlert(alertId: String)

    @Query("DELETE FROM offline_emergency_alerts WHERE incidentId = :incidentId")
    suspend fun deleteAlertsForIncident(incidentId: String)
}

@Dao
interface HighRiskZoneDao {
    @Query("SELECT * FROM high_risk_zones ORDER BY createdAt DESC")
    fun getAllZones(): Flow<List<HighRiskZoneEntity>>

    @Query("SELECT * FROM high_risk_zones WHERE isEnabled = 1")
    fun getActiveZones(): Flow<List<HighRiskZoneEntity>>

    @Query("SELECT * FROM high_risk_zones WHERE isEnabled = 1")
    suspend fun getActiveZonesOnce(): List<HighRiskZoneEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertZone(zone: HighRiskZoneEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertZones(zones: List<HighRiskZoneEntity>)

    @Query("UPDATE high_risk_zones SET isEnabled = :isEnabled WHERE zoneId = :zoneId")
    suspend fun toggleZoneEnabled(zoneId: String, isEnabled: Boolean)

    @Query("DELETE FROM high_risk_zones WHERE zoneId = :zoneId")
    suspend fun deleteZone(zoneId: String)
}

@Dao
interface SavedRoutineDao {
    @Query("SELECT * FROM saved_routines ORDER BY name ASC")
    fun getAllRoutines(): Flow<List<SavedRoutineEntity>>

    @Query("SELECT * FROM saved_routines WHERE isMonitoringActive = 1")
    fun getActiveRoutines(): Flow<List<SavedRoutineEntity>>

    @Query("SELECT * FROM saved_routines WHERE isMonitoringActive = 1")
    suspend fun getActiveRoutinesOnce(): List<SavedRoutineEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRoutine(routine: SavedRoutineEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRoutines(routines: List<SavedRoutineEntity>)

    @Update
    suspend fun updateRoutine(routine: SavedRoutineEntity)

    @Query("UPDATE saved_routines SET isMonitoringActive = :isActive WHERE routineId = :routineId")
    suspend fun setMonitoringActive(routineId: String, isActive: Boolean)

    @Query("UPDATE saved_routines SET lastKnownDeviationMeters = :deviationMeters, lastCheckTimestamp = :timestamp WHERE routineId = :routineId")
    suspend fun updateDeviation(routineId: String, deviationMeters: Float, timestamp: Long)

    @Query("DELETE FROM saved_routines WHERE routineId = :routineId")
    suspend fun deleteRoutine(routineId: String)
}

@Dao
interface RiskAssessmentDao {
    @Query("SELECT * FROM risk_assessments ORDER BY evaluatedAt DESC LIMIT 50")
    fun getRecentAssessments(): Flow<List<RiskAssessmentEntity>>

    @Query("SELECT * FROM risk_assessments WHERE incidentId = :incidentId ORDER BY evaluatedAt DESC LIMIT 1")
    suspend fun getLatestForIncident(incidentId: String): RiskAssessmentEntity?

    @Query("SELECT * FROM risk_assessments WHERE riskLevel = 'R3_CRITICAL' ORDER BY evaluatedAt DESC LIMIT 1")
    suspend fun getLatestCriticalAssessmentOnce(): RiskAssessmentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAssessment(assessment: RiskAssessmentEntity)
}

@Dao
interface EscalationWorkflowDao {
    @Query("SELECT * FROM escalation_workflows ORDER BY updatedAt DESC")
    fun getAllWorkflows(): Flow<List<EscalationWorkflowEntity>>

    @Query("SELECT * FROM escalation_workflows WHERE incidentId = :incidentId LIMIT 1")
    suspend fun getWorkflowOnce(incidentId: String): EscalationWorkflowEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWorkflow(workflow: EscalationWorkflowEntity)
}

@Dao
interface PointsLedgerDao {
    @Query("SELECT * FROM points_ledger WHERE citizenUid = :uid ORDER BY createdAt DESC")
    fun getLedgerForCitizen(uid: String): Flow<List<PointsLedgerEntity>>

    @Query("SELECT * FROM points_ledger ORDER BY createdAt DESC LIMIT 50")
    fun getAllEntries(): Flow<List<PointsLedgerEntity>>

    @Query("SELECT balanceAfter FROM points_ledger WHERE citizenUid = :uid ORDER BY id DESC LIMIT 1")
    suspend fun getLatestBalance(uid: String): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntry(entry: PointsLedgerEntity): Long
}

@Dao
interface DossierAccessDao {
    @Query("SELECT * FROM dossier_access WHERE incidentId = :incidentId AND isRevoked = 0 ORDER BY createdAt DESC LIMIT 1")
    suspend fun getActiveDossierForIncident(incidentId: String): DossierAccessEntity?

    @Query("SELECT * FROM dossier_access WHERE tokenId = :tokenId LIMIT 1")
    suspend fun getByToken(tokenId: String): DossierAccessEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDossierAccess(access: DossierAccessEntity)

    @Query("UPDATE dossier_access SET isRevoked = 1 WHERE incidentId = :incidentId")
    suspend fun revokeForIncident(incidentId: String)

    @Query("DELETE FROM dossier_access WHERE incidentId = :incidentId")
    suspend fun deleteForIncident(incidentId: String)
}

@Dao
interface LocationBreadcrumbDao {
    @Query("SELECT * FROM location_breadcrumbs ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentBreadcrumbs(limit: Int = 200): List<com.example.data.model.LocationBreadcrumbEntity>

    @Query("SELECT * FROM location_breadcrumbs WHERE timestamp >= :sinceMillis ORDER BY timestamp ASC")
    suspend fun getBreadcrumbsSince(sinceMillis: Long): List<com.example.data.model.LocationBreadcrumbEntity>

    @Query("SELECT * FROM location_breadcrumbs WHERE timestamp < :olderThanMillis ORDER BY timestamp ASC LIMIT :limit")
    suspend fun getBreadcrumbsBefore(olderThanMillis: Long, limit: Int = 2000): List<com.example.data.model.LocationBreadcrumbEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBreadcrumb(crumb: com.example.data.model.LocationBreadcrumbEntity): Long

    @Query("DELETE FROM location_breadcrumbs WHERE timestamp < :olderThanMillis")
    suspend fun pruneOlderThan(olderThanMillis: Long): Int

    @Query("SELECT COUNT(*) FROM location_breadcrumbs")
    suspend fun getCount(): Int
}

@Dao
interface LearnedPlaceDao {
    @Query("SELECT * FROM learned_places ORDER BY visitCount DESC")
    fun getAllPlaces(): Flow<List<com.example.data.model.LearnedPlaceEntity>>

    @Query("SELECT * FROM learned_places ORDER BY visitCount DESC")
    suspend fun getAllPlacesOnce(): List<com.example.data.model.LearnedPlaceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlace(place: com.example.data.model.LearnedPlaceEntity)

    @Query("DELETE FROM learned_places WHERE placeId = :placeId")
    suspend fun deletePlace(placeId: String)
}
