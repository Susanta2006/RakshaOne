package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.dao.AuditLogDao
import com.example.data.dao.DossierAccessDao
import com.example.data.dao.EscalationWorkflowDao
import com.example.data.dao.EvidenceDao
import com.example.data.dao.HighRiskZoneDao
import com.example.data.dao.IncidentDao
import com.example.data.dao.IncidentEventDao
import com.example.data.dao.LearnedPlaceDao
import com.example.data.dao.LocationBreadcrumbDao
import com.example.data.dao.OfflineAlertDao
import com.example.data.dao.PointsLedgerDao
import com.example.data.dao.RiskAssessmentDao
import com.example.data.dao.SafetyGuideDao
import com.example.data.dao.SavedRoutineDao
import com.example.data.dao.TripDao
import com.example.data.dao.TripWaypointDao
import com.example.data.dao.TrustedContactDao
import com.example.data.dao.UserDao
import com.example.data.model.AuditLogEntity
import com.example.data.model.DossierAccessEntity
import com.example.data.model.EscalationWorkflowEntity
import com.example.data.model.EvidenceEntity
import com.example.data.model.HighRiskZoneEntity
import com.example.data.model.IncidentEntity
import com.example.data.model.IncidentEventEntity
import com.example.data.model.LearnedPlaceEntity
import com.example.data.model.LocationBreadcrumbEntity
import com.example.data.model.OfflineAlertEntity
import com.example.data.model.PointsLedgerEntity
import com.example.data.model.RiskAssessmentEntity
import com.example.data.model.SafetyGuideEntity
import com.example.data.model.SavedRoutineEntity
import com.example.data.model.TripEntity
import com.example.data.model.TripWaypointEntity
import com.example.data.model.TrustedContactEntity
import com.example.data.model.UserProfileEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(
    entities = [
        UserProfileEntity::class,
        TrustedContactEntity::class,
        IncidentEntity::class,
        IncidentEventEntity::class,
        TripEntity::class,
        EvidenceEntity::class,
        AuditLogEntity::class,
        SafetyGuideEntity::class,
        OfflineAlertEntity::class,
        HighRiskZoneEntity::class,
        SavedRoutineEntity::class,
        TripWaypointEntity::class,
        RiskAssessmentEntity::class,
        EscalationWorkflowEntity::class,
        PointsLedgerEntity::class,
        DossierAccessEntity::class,
        LocationBreadcrumbEntity::class,
        LearnedPlaceEntity::class
    ],
    version = 10,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun trustedContactDao(): TrustedContactDao
    abstract fun incidentDao(): IncidentDao
    abstract fun incidentEventDao(): IncidentEventDao
    abstract fun tripDao(): TripDao
    abstract fun tripWaypointDao(): TripWaypointDao
    abstract fun evidenceDao(): EvidenceDao
    abstract fun auditLogDao(): AuditLogDao
    abstract fun safetyGuideDao(): SafetyGuideDao
    abstract fun offlineAlertDao(): OfflineAlertDao
    abstract fun highRiskZoneDao(): HighRiskZoneDao
    abstract fun savedRoutineDao(): SavedRoutineDao
    abstract fun riskAssessmentDao(): RiskAssessmentDao
    abstract fun escalationWorkflowDao(): EscalationWorkflowDao
    abstract fun pointsLedgerDao(): PointsLedgerDao
    abstract fun dossierAccessDao(): DossierAccessDao
    abstract fun locationBreadcrumbDao(): LocationBreadcrumbDao
    abstract fun learnedPlaceDao(): LearnedPlaceDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE user_profile ADD COLUMN silentSosMode INTEGER NOT NULL DEFAULT 0")
            }
        }

        internal val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE user_profile ADD COLUMN antiSnatchEnabled INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE user_profile ADD COLUMN autoLockOnSos INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE trusted_contacts ADD COLUMN hasWhatsApp INTEGER NOT NULL DEFAULT 1")
            }
        }

        internal val MIGRATION_8_9 = object : androidx.room.migration.Migration(8, 9) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `risk_assessments` (
                        `assessmentId` TEXT NOT NULL,
                        `incidentId` TEXT NOT NULL,
                        `riskLevel` TEXT NOT NULL,
                        `triggerOrigin` TEXT NOT NULL,
                        `confidence` REAL NOT NULL,
                        `policeEscalationEligible` INTEGER NOT NULL,
                        `reasonCodesCsv` TEXT NOT NULL,
                        `explanation` TEXT NOT NULL,
                        `travelBumpSuppressed` INTEGER NOT NULL,
                        `observabilityDegraded` INTEGER NOT NULL,
                        `evaluatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`assessmentId`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `escalation_workflows` (
                        `incidentId` TEXT NOT NULL,
                        `riskLevel` TEXT NOT NULL,
                        `policeEscalationEligible` INTEGER NOT NULL,
                        `policeStatus` TEXT NOT NULL,
                        `deliveryState` TEXT NOT NULL,
                        `trustedSmsDispatched` INTEGER NOT NULL,
                        `trustedWhatsAppDispatched` INTEGER NOT NULL,
                        `bleBroadcastStarted` INTEGER NOT NULL,
                        `hexRequestId` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`incidentId`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `points_ledger` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `citizenUid` TEXT NOT NULL,
                        `delta` INTEGER NOT NULL,
                        `balanceAfter` INTEGER NOT NULL,
                        `reasonCode` TEXT NOT NULL,
                        `relatedIncidentId` TEXT,
                        `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `dossier_access` (
                        `tokenId` TEXT NOT NULL,
                        `incidentId` TEXT NOT NULL,
                        `dossierJson` TEXT NOT NULL,
                        `webUrl` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `expiresAt` INTEGER NOT NULL,
                        `isRevoked` INTEGER NOT NULL,
                        PRIMARY KEY(`tokenId`)
                    )
                    """.trimIndent()
                )
            }
        }

        internal val MIGRATION_9_10 = object : androidx.room.migration.Migration(9, 10) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE trusted_contacts ADD COLUMN allow24x7Location INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE trusted_contacts ADD COLUMN isMutuallyAccepted INTEGER NOT NULL DEFAULT 1")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `location_breadcrumbs` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `latitude` REAL NOT NULL,
                        `longitude` REAL NOT NULL,
                        `accuracyMeters` REAL NOT NULL,
                        `batteryPercent` INTEGER NOT NULL,
                        `geohash` TEXT NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        `incidentId` TEXT
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `learned_places` (
                        `placeId` TEXT NOT NULL,
                        `label` TEXT NOT NULL,
                        `latitude` REAL NOT NULL,
                        `longitude` REAL NOT NULL,
                        `radiusMeters` REAL NOT NULL,
                        `visitCount` INTEGER NOT NULL,
                        `typicalHourStart` INTEGER NOT NULL,
                        `typicalHourEnd` INTEGER NOT NULL,
                        `lastVisitedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`placeId`)
                    )
                    """.trimIndent()
                )
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "rakshaone_emergency_database"
                )
                    .addMigrations(MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10)
                    .fallbackToDestructiveMigration(false)
                    .addCallback(DatabaseCallback())
                    .build()
                INSTANCE = instance
                instance
            }
        }

        private class DatabaseCallback : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                INSTANCE?.let { database ->
                    CoroutineScope(Dispatchers.IO).launch {
                        populateInitialData(database)
                    }
                }
            }
        }

        suspend fun populateInitialData(db: AppDatabase) {
            // Do not seed fake user profiles or fake emergency contacts.
            // Users configure their own real medical ID and trusted contacts.

            // Essential Offline Safety Guides
            db.safetyGuideDao().insertGuides(
                listOf(
                    SafetyGuideEntity(
                        id = "GUIDE-001",
                        title = "What to do if being followed",
                        category = "Personal Safety",
                        description = "Standard personal protection protocol when suspecting surveillance or pursuit.",
                        steps = "1. Change direction or cross to a well-lit side of the street;2. Head immediately into a public crowded space (store, cafe, metro station);3. Do not head directly to your isolated home;4. Keep RakshaOne SOS button on standby;5. If threat escalates, call 112 or trigger SOS countdown.",
                        emergencyDialNumber = "112"
                    ),
                    SafetyGuideEntity(
                        id = "GUIDE-002",
                        title = "Medical CPR & Severe Bleeding",
                        category = "First Aid",
                        description = "Immediate emergency response steps for unresponsive individuals or heavy trauma.",
                        steps = "1. Verify consciousness and check breathing;2. Call 108 or 112 immediately;3. Place hands in center of chest and push hard & fast (100-120 bpm);4. For heavy bleeding: apply firm direct pressure with clean cloth without releasing.",
                        emergencyDialNumber = "108"
                    ),
                    SafetyGuideEntity(
                        id = "GUIDE-003",
                        title = "Cab / Solo Journey Safety Protocol",
                        category = "Journey Safety",
                        description = "Pre-ride and in-transit safety checklist for solo travelers.",
                        steps = "1. Verify license plate and driver photo before boarding;2. Start a RakshaOne monitored trip with 15-min check-in;3. Share live trip status with Priority 1 contact;4. Sit behind driver's seat to maintain visibility;5. If vehicle deviates, request immediate stop and trigger alert.",
                        emergencyDialNumber = "112"
                    ),
                    SafetyGuideEntity(
                        id = "GUIDE-004",
                        title = "Fire & Smoke Evacuation",
                        category = "Disaster",
                        description = "Protocol during building or vehicle fire incidents.",
                        steps = "1. Drop low to avoid toxic smoke inhalation;2. Feel door handles before opening; if hot, seek secondary exit;3. Never use elevators during a fire;4. Assemble at designated safety points outside;5. Call 101 or 112 for Fire & Rescue.",
                        emergencyDialNumber = "101"
                    )
                )
            )

            // Initial Audit Log
            db.auditLogDao().insertLog(
                AuditLogEntity(
                    timestamp = System.currentTimeMillis(),
                    actor = "System",
                    role = "SYSTEM",
                    action = "SYSTEM_INITIALIZED",
                    targetId = "SYS-001",
                    details = "RakshaOne risk & emergency engine initialized with encrypted local vault."
                )
            )
        }
    }
}
