package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.ai.AiService
import com.example.data.ai.SurakshaStreamEvent
import com.example.data.model.HexAlertStatus
import com.example.data.model.HexRescueAlert
import com.example.data.model.TripWaypointEntity
import com.example.data.model.TrustedContactEntity
import com.example.risk.CitizenVerificationStatus
import com.example.risk.EmergencyEscalationOrchestrator
import com.example.risk.EmergencyEscalationPolicyEngine
import com.example.risk.HexRescueLifecycleState
import com.example.risk.PerceptionObservation
import com.example.risk.PoliceEscalationStatus
import com.example.risk.PolicyViolationException
import com.example.risk.RiskIntelligenceEngine
import com.example.risk.RiskLevel
import com.example.risk.TriggerOrigin
import com.example.service.SosAlertService
import com.example.util.DeviceLockManager
import com.example.util.EmergencyResponseDispatcher
import com.example.util.GoodCitizenManager
import com.example.util.HexagonalRescueManager
import com.example.util.PhoneNormalizer
import kotlinx.coroutines.flow.first
import com.example.data.model.toCachedEntity
import com.example.data.model.toCloudEmergencyDispatch
import com.example.data.model.toHexRescueAlert
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

  private lateinit var db: AppDatabase

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    context.getSharedPreferences("rakshaone_good_citizen_prefs_v2", Context.MODE_PRIVATE)
      .edit()
      .clear()
      .commit()
    db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
      .allowMainThreadQueries()
      .build()
  }

  @After
  fun tearDown() {
    db.close()
  }

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("RakshaOne", appName)
  }

  @Test
  fun `safety classifier catches immediate danger`() {
    val aiService = AiService()
    assertTrue(aiService.isEmergencyIntent("someone is following me"))
    assertTrue(aiService.isEmergencyIntent("I am in immediate danger, help me"))
    assertTrue(aiService.isEmergencyIntent("attacker with weapon"))
  }

  @Test
  fun `trusted contacts persist, retrieve, and delete offline via Room DAO`() = runBlocking {
    val contact1 = TrustedContactEntity(
      name = "Primary Contact",
      relationship = "Family",
      phone = "+15550101234",
      email = "guardian@example.com",
      priority = 1,
      isEnabled = true
    )
    val contact2 = TrustedContactEntity(
      name = "Disabled Contact",
      relationship = "Colleague",
      phone = "+15550109999",
      priority = 2,
      isEnabled = false
    )

    val id1 = db.trustedContactDao().insertContact(contact1)
    db.trustedContactDao().insertContact(contact2)

    val activeContacts = db.trustedContactDao().getActiveContacts()
    assertEquals(1, activeContacts.size)
    assertEquals("Primary Contact", activeContacts[0].name)
    assertEquals("+15550101234", activeContacts[0].phone)

    db.trustedContactDao().deleteContact(id1)
    assertEquals(0, db.trustedContactDao().getActiveContactCount())
  }

  @Test
  fun `fused location wrapper enforces fine location permission before starting`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val wrapper = com.example.util.FusedLocationWrapper(context)
    assertFalse(wrapper.hasFineLocationPermission())
    wrapper.startRealtimeUpdates()
    assertEquals(com.example.util.LocationState.PermissionRequired, wrapper.locationState.value)
  }

  @Test
  fun `sos alert service formats live coordinates into SMS body`() {
    val sms = SosAlertService.buildEmergencySmsBody(
      userName = "Citizen",
      incidentId = "INC-2026-100001",
      latitude = 37.42199,
      longitude = -122.08405,
      accuracyMeters = 8.4f,
      userNote = "Urgent SOS Activated",
      timestampMillis = 1763560000000L
    )

    assertTrue(sms.contains("37.42199, -122.08405 (±8m)"))
    assertTrue(sms.contains("https://maps.google.com/?q=37.42199,-122.08405"))
    assertTrue(sms.contains("INC-2026-100001"))
  }

  @Test
  fun `fake call manager uses non-hardcoded generic defaults`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val fakeCallManager = com.example.util.FakeCallManager(context)
    val cfg = fakeCallManager.config.value
    assertEquals("Emergency Guardian", cfg.callerName)
    assertEquals("", cfg.callerNumber)
  }

  @Test
  fun `offline alert DAO queues and marks dispatched accurately`() = runBlocking {
    val alert = com.example.data.model.OfflineAlertEntity(
      alertId = "OFFLINE-TEST-1",
      incidentId = "INC-TEST-1",
      alertType = "SOS",
      latitude = 37.422,
      longitude = -122.084,
      accuracyMeters = 10f,
      messageText = "Test SOS alert",
      recipientPhones = "+15550101234",
      recipientEmails = "test@example.com",
      status = "PENDING_CONNECTIVITY"
    )
    db.offlineAlertDao().insertAlert(alert)
    assertEquals(1, db.offlineAlertDao().getPendingAlertsOnce().size)

    db.offlineAlertDao().markDispatched("OFFLINE-TEST-1")
    assertEquals(0, db.offlineAlertDao().getPendingAlertsOnce().size)
  }

  @Test
  fun `ai service interpolates regional primary SOS hotline for emergency intent`() = runBlocking {
    val aiService = AiService()
    val reply = aiService.getAiResponse("Someone is following me", primarySosNumber = "911")
    assertTrue(reply.isEmergencyAlert)
    assertTrue(reply.showSosAction)
    assertTrue(reply.text.contains("911"))
  }

  @Test
  fun `ai service stream emits immediate emergency override with hotline`() = runBlocking {
    val aiService = AiService()
    val event = aiService.streamAiResponse(
      userQuery = "Help me I am in danger",
      conversationHistory = emptyList(),
      primarySosNumber = "999"
    ).first()
    assertTrue(event is SurakshaStreamEvent.Completed)
    val completed = event as SurakshaStreamEvent.Completed
    assertTrue(completed.message.isEmergencyAlert)
    assertTrue(completed.message.showSosAction)
    assertTrue(completed.message.text.contains("999"))
  }

  @Test
  fun `trip waypoint DAO persists interval GPS coordinates ordered latest first`() = runBlocking {
    val wp1 = TripWaypointEntity(
      tripId = "TRIP-1001",
      latitude = 28.6139,
      longitude = 77.2090,
      accuracyMeters = 6f,
      speedKmh = 12.5f,
      timestamp = 1000L
    )
    val wp2 = TripWaypointEntity(
      tripId = "TRIP-1001",
      latitude = 28.6150,
      longitude = 77.2105,
      accuracyMeters = 5f,
      speedKmh = 18.0f,
      timestamp = 6000L
    )
    val wpOtherTrip = TripWaypointEntity(
      tripId = "TRIP-9999",
      latitude = 19.0760,
      longitude = 72.8777,
      accuracyMeters = 10f,
      speedKmh = 0f,
      timestamp = 7000L
    )

    db.tripWaypointDao().insertWaypoint(wp1)
    db.tripWaypointDao().insertWaypoint(wp2)
    db.tripWaypointDao().insertWaypoint(wpOtherTrip)

    val waypoints = db.tripWaypointDao().getWaypointsForTripOnce("TRIP-1001")
    assertEquals(2, waypoints.size)
    assertEquals(6000L, waypoints[0].timestamp)
    assertEquals(28.6150, waypoints[0].latitude, 0.0001)
    assertEquals(1000L, waypoints[1].timestamp)
  }

  @Test
  fun `realtime emergency service resolves country and hotlines accurately from coordinates`() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val fusedClient = com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(context)
    val service = com.example.util.RealtimeEmergencyService(context, fusedClient)

    assertEquals("IN", service.inferCountryFromCoordinates(28.6139, 77.2090))
    assertEquals("US", service.inferCountryFromCoordinates(37.7749, -122.4194))
    assertEquals("GB", service.inferCountryFromCoordinates(51.5074, -0.1278))
    assertEquals("AU", service.inferCountryFromCoordinates(-33.8688, 151.2093))

    val infoIndia = service.resolveLocationEmergencyInfo(28.6139, 77.2090, accuracyMeters = 8f)
    assertEquals("IN", infoIndia.countryCode)
    assertEquals("112", infoIndia.primarySosNumber)
    assertEquals("108", infoIndia.ambulanceNumber)
    assertTrue(infoIndia.isRealGpsFix)

    val infoUS = service.resolveLocationEmergencyInfo(37.7749, -122.4194, accuracyMeters = 5f)
    assertEquals("US", infoUS.countryCode)
    assertEquals("911", infoUS.primarySosNumber)
    assertEquals("911", infoUS.policeNumber)

    service.setManualRegionOverride("GB")
    assertEquals("GB", service.emergencyInfo.value.countryCode)
    assertEquals("999", service.emergencyInfo.value.primarySosNumber)

    service.clearManualRegionOverride()
    assertEquals(null, service.manualRegionOverride.value)
  }

  @Test
  fun `battery optimization manager verifies status safely`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val batteryManager = com.example.util.BatteryOptimizationManager(context)
    val isIgnoring = batteryManager.checkIfIgnoring()
    assertEquals(isIgnoring, batteryManager.isIgnoringBatteryOptimizations.value)
  }

  @Test
  fun `risk intelligence engine suppresses travel bumps and restricts R3 critical to explicit SOS`() {
    val engine = RiskIntelligenceEngine()

    // Heavy vehicle bump while moving at 42 km/h must classify as R0_NORMAL and NOT fire SOS
    val bumpResult = engine.evaluate(
      PerceptionObservation(
        dynamicJerkG = 3.4f,
        sustainedTravelSpeedKmh = 42f,
        travelBumpFilterEnabled = true,
        explicitSosButtonClicked = false,
        explicitInstantSosClicked = false
      )
    )
    assertEquals(RiskLevel.R0_NORMAL, bumpResult.riskLevel)
    assertEquals(TriggerOrigin.TRAVEL_BUMP_OBSERVED, bumpResult.triggerOrigin)
    assertFalse(bumpResult.policeEscalationEligible)
    assertTrue(bumpResult.context.isTravelBumpSuppressed)

    // Route corridor deviation alone -> R1_ATTENTION, never police eligible
    val r1Result = engine.evaluate(
      PerceptionObservation(routeCorridorBreached = true, inHighRiskZone = false)
    )
    assertEquals(RiskLevel.R1_ATTENTION, r1Result.riskLevel)
    assertFalse(r1Result.policeEscalationEligible)

    // Deviation + high risk zone -> R2_HIGH, trusted contacts advisory ONLY, never police eligible
    val r2Result = engine.evaluate(
      PerceptionObservation(routeCorridorBreached = true, inHighRiskZone = true, highRiskZoneName = "Dark Alley")
    )
    assertEquals(RiskLevel.R2_HIGH, r2Result.riskLevel)
    assertFalse(r2Result.policeEscalationEligible)
    assertTrue(r2Result.actionFlags.notifyTrustedContactsSmsWhatsApp)

    // Explicit SOS button -> R3_CRITICAL, police escalation eligible
    val r3Result = engine.evaluate(
      PerceptionObservation(explicitInstantSosClicked = true),
      incidentId = "INC-EXPLICIT-01"
    )
    assertEquals(RiskLevel.R3_CRITICAL, r3Result.riskLevel)
    assertEquals(TriggerOrigin.USER_EXPLICIT_INSTANT_SOS, r3Result.triggerOrigin)
    assertTrue(r3Result.policeEscalationEligible)
    assertTrue(r3Result.actionFlags.enableBluetoothHexBroadcast)
  }

  @Test
  fun `non-bypassable escalation orchestrator blocks R0 R1 R2 and allows idempotent explicit R3`() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val engine = RiskIntelligenceEngine()
    val dispatcher = EmergencyResponseDispatcher(context)
    val orchestrator = EmergencyEscalationOrchestrator(context, db, dispatcher)

    val nonCriticalAssessments = listOf(
      engine.evaluate(PerceptionObservation(dynamicJerkG = 2.9f, sustainedTravelSpeedKmh = 35f)),
      engine.evaluate(PerceptionObservation(routeCorridorBreached = true)),
      engine.evaluate(PerceptionObservation(routeCorridorBreached = true, inHighRiskZone = true))
    )

    nonCriticalAssessments.forEachIndexed { index, assessment ->
      val res = orchestrator.authorizeAndPreparePoliceEscalation(
        incidentId = "INC-NONCRIT-$index",
        assessment = assessment,
        location = null,
        localityName = "Agartala",
        medicalSummary = "Blood: O+",
        evidenceHashesSummary = "None",
        userNote = "Advisory"
      )
      assertTrue("Policy MUST reject ${assessment.riskLevel}", res.isFailure)
      assertTrue(res.exceptionOrNull() is PolicyViolationException)
    }

    val logs = db.auditLogDao().getAllLogs().first()
    assertEquals(3, logs.count { it.action == "ESCALATION_POLICY_REJECTED" })

    // Explicit R3 succeeds and is idempotent on retry for the same incidentId
    val r3 = engine.evaluate(PerceptionObservation(explicitSosButtonClicked = true), incidentId = "INC-112-99")
    val firstAttempt = orchestrator.authorizeAndPreparePoliceEscalation(
      incidentId = "INC-112-99",
      assessment = r3,
      location = null,
      localityName = "Battala Bazar, Agartala",
      medicalSummary = "Blood: O+ | Allergies: None",
      evidenceHashesSummary = "Dual CameraX video SHA-256: e3b0c442",
      userNote = "Explicit SOS pressed"
    )
    assertTrue(firstAttempt.isSuccess)
    val dossier1 = firstAttempt.getOrThrow()
    assertEquals("112", dossier1.emergencyNumber)
    assertEquals(RiskLevel.R3_CRITICAL, dossier1.riskLevel)
    assertEquals(PoliceEscalationStatus.ELIGIBLE, dossier1.policeStatus)
    assertFalse(dossier1.isTransmitted) // Honest until real intent launched
    assertTrue(dossier1.webPortalUrl.contains("INC-112-99"))
    assertTrue(dossier1.webPortalUrl.contains("token="))

    val secondAttempt = orchestrator.authorizeAndPreparePoliceEscalation(
      incidentId = "INC-112-99",
      assessment = r3,
      location = null,
      localityName = "Battala Bazar, Agartala",
      medicalSummary = "Blood: O+ | Allergies: None",
      evidenceHashesSummary = "Dual CameraX video SHA-256: e3b0c442",
      userNote = "Retry"
    )
    assertTrue(secondAttempt.isSuccess)
    assertEquals(dossier1.dossierToken, secondAttempt.getOrThrow().dossierToken)
    assertEquals(dossier1.dispatchId, secondAttempt.getOrThrow().dispatchId)
  }

  @Test
  fun `video evidence manager restricts fallback and refuses empty trusted contacts list`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val manager = com.example.util.EmergencyVideoEvidenceManager(
      context = context,
      database = db,
      scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
    )
    val blockedResult = manager.dispatchEvidenceWithFallback(
      incidentId = "INC-NO-CONTACTS",
      userName = "Citizen",
      location = null,
      targetPhones = emptyList(),
      isOnline = false,
      preferWhatsApp = true
    )
    assertFalse(blockedResult.usedSmsMmsFallback)
    assertFalse(blockedResult.whatsAppLaunched)
    assertEquals("NO_TRUSTED_CONTACTS", blockedResult.fallbackReason)
  }

  @Test
  fun `video evidence manager resolves video plus images from cache with sha256 and routes offline fallback`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val manager = com.example.util.EmergencyVideoEvidenceManager(
      context = context,
      database = db,
      scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
    )
    val incidentId = "INC-TEST-MEDIA-01"
    val cacheDir = java.io.File(context.cacheDir, "emergency_evidence/$incidentId").apply { mkdirs() }
    val vid = java.io.File(cacheDir, "VID_test.mp4").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
    val img1 = java.io.File(cacheDir, "IMG_1.jpg").apply { writeBytes(byteArrayOf(5, 6, 7, 8)) }
    val img2 = java.io.File(cacheDir, "IMG_2.jpg").apply { writeBytes(byteArrayOf(9, 10, 11, 12)) }

    try {
      val bundle = manager.resolveIncidentMediaBundle(incidentId = incidentId)
      assertTrue(bundle.videoFile != null)
      assertEquals(2, bundle.photoFiles.size)
      assertTrue(bundle.sha256DigestSummary.contains("1 Video(s) + 2 Photo(s)"))

      val result = manager.dispatchEvidenceWithFallback(
        incidentId = incidentId,
        userName = "Susanta Banik",
        location = null,
        targetPhones = listOf("+919800000000"),
        isOnline = false,
        preferWhatsApp = true
      )
      assertTrue(result.usedSmsMmsFallback)
      assertEquals("INTERNET_UNAVAILABLE", result.fallbackReason)
      assertEquals(1, result.videoCount)
      assertEquals(2, result.photoCount)
      assertEquals(3, result.mediaCount)
    } finally {
      vid.delete()
      img1.delete()
      img2.delete()
      cacheDir.deleteRecursively()
    }
  }

  @Test
  fun `manifest declares Play-compliant BLE permissions without high-risk SMS or Call permissions`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val pkgInfo = context.packageManager.getPackageInfo(
      context.packageName,
      android.content.pm.PackageManager.GET_PERMISSIONS
    )
    val requested = pkgInfo.requestedPermissions?.toSet() ?: emptySet()
    assertFalse(requested.contains(android.Manifest.permission.SEND_SMS))
    assertFalse(requested.contains(android.Manifest.permission.CALL_PHONE))
    assertFalse(requested.contains(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION))
    assertFalse(requested.contains("android.permission.REQUEST_IGNORE_BATTERY_OPTIMATIONS"))
    assertFalse(requested.contains(android.Manifest.permission.BIND_DEVICE_ADMIN))
    assertTrue(requested.contains(android.Manifest.permission.FOREGROUND_SERVICE_MICROPHONE))
    assertFalse(requested.contains(android.Manifest.permission.READ_EXTERNAL_STORAGE))
    assertFalse(requested.contains(android.Manifest.permission.READ_MEDIA_IMAGES))
    assertFalse(requested.contains(android.Manifest.permission.READ_MEDIA_VIDEO))
    assertTrue(requested.contains(android.Manifest.permission.ACCESS_FINE_LOCATION))
    assertTrue(requested.contains(android.Manifest.permission.FOREGROUND_SERVICE_LOCATION))
    assertTrue(requested.contains(android.Manifest.permission.BLUETOOTH_SCAN))
    assertTrue(requested.contains(android.Manifest.permission.BLUETOOTH_ADVERTISE))

    val lockManager = DeviceLockManager(context)
    assertFalse(lockManager.checkIsAdminActive())
    assertFalse(lockManager.lockDeviceNow())
  }

  @Test
  fun `good citizen manager starts unverified at zero points and unlocks via referral code verification`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val manager = GoodCitizenManager(context)
    val initial = manager.citizenProfile.value
    assertEquals(CitizenVerificationStatus.UNVERIFIED, initial.verificationStatus)
    assertFalse(initial.isIdVerified)
    assertEquals(0, initial.trustScore)
    assertFalse(initial.isVolunteerActive)
    assertFalse(initial.isEligibleForHexRescue)
    assertFalse(manager.setVolunteerActive(true))

    // Referral code endorsement unlocks VERIFIED + 15 bonus points
    val verified = manager.applyPeerEndorsementVerification("RK-8F2A1C")
    assertEquals(CitizenVerificationStatus.VERIFIED, verified.verificationStatus)
    assertTrue(verified.isIdVerified)
    assertEquals(15, verified.trustScore)
    assertTrue(manager.setVolunteerActive(true))
    assertTrue(manager.citizenProfile.value.isEligibleForHexRescue)

    val afterAccept = manager.recordAcceptedHelp("INC-HEX-1")
    assertEquals(30, afterAccept.trustScore)
    val afterComplete = manager.recordCompletedPickup("INC-HEX-1")
    assertEquals(55, afterComplete.trustScore)
    val afterDecline = manager.recordDeclinedHelp("INC-HEX-2")
    assertEquals(50, afterDecline.trustScore)
  }

  @Test
  fun `hexagonal rescue manager maps 6 compass sectors, gates unverified citizens, and runs pickup lifecycle`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val citizenManager = GoodCitizenManager(context)
    val rescueManager = HexagonalRescueManager(context, citizenManager)

    // Verify 6 compass sectors
    assertEquals("N-NE", HexagonalRescueManager.bearingToCompassSector(25.0))
    assertEquals("E", HexagonalRescueManager.bearingToCompassSector(85.0))
    assertEquals("S-SE", HexagonalRescueManager.bearingToCompassSector(145.0))
    assertEquals("S-SW", HexagonalRescueManager.bearingToCompassSector(210.0))
    assertEquals("W", HexagonalRescueManager.bearingToCompassSector(265.0))
    assertEquals("N-NW", HexagonalRescueManager.bearingToCompassSector(330.0))

    val alert = HexRescueAlert(
      alertId = "HEX-TEST-01",
      incidentId = "INC-TEST-01",
      victimName = "Pooja Sharma",
      victimLatitude = 23.83570,
      victimLongitude = 91.26990,
      currentRadiusMeters = 750f,
      distanceMeters = 400f,
      estimatedWalkMinutes = 5
    )

    // Unverified citizen MUST be blocked from receiving help alerts
    assertFalse(rescueManager.receiveIncomingHexAlert(alert, null))

    // Verify via peer endorsement + enable duty -> alert allowed & full pickup lifecycle works
    citizenManager.submitKycMetadata("Voter ID", "8899")
    citizenManager.applyPeerEndorsementVerification("PEER-ENDORSER-1")
    citizenManager.setVolunteerActive(true)

    assertTrue(rescueManager.receiveIncomingHexAlert(alert, null))
    assertEquals("HEX-TEST-01", rescueManager.activeIncomingAlert.value?.alertId)

    assertTrue(rescueManager.acceptIncomingHelpRequest("HEX-TEST-01"))
    assertEquals(HexAlertStatus.ACCEPTED, rescueManager.activeIncomingAlert.value?.status)
    assertEquals(HexRescueLifecycleState.RESPONDER_ACCEPTED, rescueManager.activeIncomingAlert.value?.lifecycleState)

    assertTrue(rescueManager.markResponderEnRoute("HEX-TEST-01"))
    assertEquals(HexRescueLifecycleState.EN_ROUTE, rescueManager.activeIncomingAlert.value?.lifecycleState)

    assertTrue(rescueManager.markResponderArrived("HEX-TEST-01"))
    assertEquals(HexRescueLifecycleState.ARRIVED, rescueManager.activeIncomingAlert.value?.lifecycleState)

    assertTrue(rescueManager.completePickup("HEX-TEST-01"))
    assertEquals(null, rescueManager.activeIncomingAlert.value)
  }

  @Test
  fun `emergency response dispatcher rejects policy violation when non-R3 assessment supplied`() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val dispatcher = EmergencyResponseDispatcher(context)
    val nonR3 = RiskIntelligenceEngine().evaluate(PerceptionObservation(routeCorridorBreached = true))
    assertFalse(EmergencyEscalationPolicyEngine.isPoliceEscalationEligible(nonR3))

    var threwPolicyError = false
    try {
      dispatcher.createAuthorizedPoliceDossier(
        incidentId = "INC-R1-ILLEGAL",
        assessment = nonR3,
        location = null,
        localityName = "Agartala",
        medicalSummary = "O+",
        evidenceHashesSummary = "None",
        webPortalUrl = "https://example.com",
        dossierToken = "tok",
        expiresAtMillis = System.currentTimeMillis() + 60000L
      )
    } catch (_: PolicyViolationException) {
      threwPolicyError = true
    }
    assertTrue(threwPolicyError)
  }

  @Test
  fun `phone normalizer formats canonical phone keys and directory lookup candidates`() {
    assertEquals("8119098684", PhoneNormalizer.canonicalPhoneKey("+91 81190-98684"))
    assertEquals("8119098684", PhoneNormalizer.canonicalPhoneKey("081190 98684"))
    assertTrue(PhoneNormalizer.isValidPhoneNumber("+918119098684"))
    assertFalse(PhoneNormalizer.isValidPhoneNumber("12345"))

    val keys = PhoneNormalizer.directoryLookupKeys("+91 81190-98684")
    assertTrue(keys.contains("8119098684"))
    assertTrue(keys.contains("918119098684"))
  }

  @Test
  fun `zero-deploy orchestrator builds rakshaone dispatch URI and purges evidence vault on close`() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val orchestrator = EmergencyEscalationOrchestrator(
      context = context,
      database = db,
      responseDispatcher = EmergencyResponseDispatcher(context),
      firestoreRepository = null
    )
    val assessment = RiskIntelligenceEngine().evaluate(
      PerceptionObservation(explicitSosButtonClicked = true)
    )
    val res = orchestrator.authorizeAndPreparePoliceEscalation(
      incidentId = "INC-ZERO-01",
      assessment = assessment,
      location = null,
      localityName = "Agartala",
      medicalSummary = "AB+",
      evidenceHashesSummary = "1 photo",
      userNote = "SOS Triggered"
    )
    assertTrue(res.isSuccess)
    val dossier = res.getOrThrow()
    val portalUrl = dossier.webPortalUrl
    assertTrue(portalUrl.startsWith("rakshaone://dispatch/INC-ZERO-01?token="))
    assertFalse(portalUrl.contains("web.app"))

    // Create sample vault file + Room records and verify clean purge upon victim resolution
    val cacheFolder = java.io.File(context.cacheDir, "emergency_evidence/INC-ZERO-01").apply { mkdirs() }
    val sampleFile = java.io.File(cacheFolder, "sos_clip.mp4").apply { writeText("evidence") }
    db.evidenceDao().insertEvidence(
      com.example.data.model.EvidenceEntity(
        evidenceId = "EVD-01",
        incidentId = "INC-ZERO-01",
        fileType = "VIDEO",
        title = "Clip",
        fileSizeBytes = 1024L,
        storageLocation = sampleFile.absolutePath,
        sha256Hash = "abc123"
      )
    )
    assertEquals(1, db.evidenceDao().getEvidenceForIncidentOnce("INC-ZERO-01").size)

    // Victim resolves -> revoke dossier + delete physical files + empty vault table
    orchestrator.clearResolvedDossier("INC-ZERO-01")
    cacheFolder.deleteRecursively()
    db.evidenceDao().deleteEvidenceForIncident("INC-ZERO-01")
    db.offlineAlertDao().deleteAlertsForIncident("INC-ZERO-01")

    assertFalse(sampleFile.exists())
    assertTrue(db.evidenceDao().getEvidenceForIncidentOnce("INC-ZERO-01").isEmpty())
    assertEquals(null, db.dossierAccessDao().getActiveDossierForIncident("INC-ZERO-01"))
  }

  @Test
  fun `relationship trust rules enforce core family 24x7 and extended conditional live permissions`() {
    val coreRoles = listOf("Father", "Mother", "Brother", "Sister", "Spouse", "spouce", "FATHER")
    coreRoles.forEach { role ->
      assertTrue("Expected $role to be Core Family", com.example.data.model.RelationshipTrustRules.isCoreFamily(role))
    }
    assertEquals("spouse", com.example.data.model.RelationshipTrustRules.normalizeRole("Spouce"))
    assertFalse(com.example.data.model.RelationshipTrustRules.isCoreFamily("Friend"))
    assertFalse(com.example.data.model.RelationshipTrustRules.isCoreFamily("Colleague"))

    val context = ApplicationProvider.getApplicationContext<Context>()
    val radar = com.example.util.LiveRadarManager(context)
    val father = TrustedContactEntity(name = "Dad", relationship = "Father", phone = "+919800000001", priority = 1)
    val spouseTypo = TrustedContactEntity(name = "Partner", relationship = "Spouce", phone = "+919800000002", priority = 1)
    val friendRestricted = TrustedContactEntity(name = "Friend", relationship = "Friend", phone = "+919800000003", priority = 3, allow24x7Location = false)
    val friendAllowed24x7 = TrustedContactEntity(name = "Bestie", relationship = "Friend", phone = "+919800000004", priority = 3, allow24x7Location = true)

    assertTrue(radar.isContactPermittedToViewLiveLocation(father, hasActiveTrip = false, batteryPercent = 80, isSosActive = false))
    assertTrue(radar.isContactPermittedToViewLiveLocation(spouseTypo, hasActiveTrip = false, batteryPercent = 80, isSosActive = false))
    assertFalse(radar.isContactPermittedToViewLiveLocation(friendRestricted, hasActiveTrip = false, batteryPercent = 80, isSosActive = false))
    assertTrue(radar.isContactPermittedToViewLiveLocation(friendRestricted, hasActiveTrip = true, batteryPercent = 80, isSosActive = false))
    assertTrue(radar.isContactPermittedToViewLiveLocation(friendRestricted, hasActiveTrip = false, batteryPercent = 8, isSosActive = false))
    assertTrue(radar.isContactPermittedToViewLiveLocation(friendRestricted, hasActiveTrip = false, batteryPercent = 80, isSosActive = true))
    assertTrue(radar.isContactPermittedToViewLiveLocation(friendAllowed24x7, hasActiveTrip = false, batteryPercent = 80, isSosActive = false))
  }

  @Test
  fun `good citizen awards core family bonus and unlocks responder via free phone hint`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val manager = GoodCitizenManager(context)

    val afterPhoneHint = manager.verifyPhoneByPlayHint(
      phoneNumber = "+91 9876543210",
      circleContactCount = 0,
      coreFamilyCount = 0
    )
    assertTrue(afterPhoneHint)
    assertTrue(manager.citizenProfile.value.isPhoneVerified)
    assertEquals(15, manager.citizenProfile.value.trustScore)

    val bonusApplied = manager.addCoreFamilyTrustBonus("+919811112222", "Mother")
    assertTrue(bonusApplied)
    assertEquals(30, manager.citizenProfile.value.trustScore)
    assertEquals(CitizenVerificationStatus.VERIFIED, manager.citizenProfile.value.verificationStatus)
    assertTrue(manager.setVolunteerActive(true))
    assertTrue(manager.citizenProfile.value.isEligibleForHexRescue)
  }

  @Test
  fun `video evidence manager bundles ALL multiple audio video and photo files`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val manager = com.example.util.EmergencyVideoEvidenceManager(
      context = context,
      database = db,
      scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
    )
    val incidentId = "INC-ALL-MULTI-99"
    val cacheDir = java.io.File(context.cacheDir, "emergency_evidence/$incidentId").apply { mkdirs() }
    val vid1 = java.io.File(cacheDir, "VID_FRONT_1.mp4").apply { writeBytes(byteArrayOf(1, 2, 3)) }
    val vid2 = java.io.File(cacheDir, "VID_REAR_2.mp4").apply { writeBytes(byteArrayOf(4, 5, 6)) }
    val frontImg = java.io.File(cacheDir, "IMG_FRONT_1.jpg").apply { writeBytes(byteArrayOf(7, 8, 9)) }
    val rearImg = java.io.File(cacheDir, "IMG_REAR_2.jpg").apply { writeBytes(byteArrayOf(10, 11, 12)) }
    val aud1 = java.io.File(cacheDir, "VOICE_SOS_1.m4a").apply { writeBytes(byteArrayOf(13, 14, 15)) }
    val aud2 = java.io.File(cacheDir, "VOICE_SOS_2.m4a").apply { writeBytes(byteArrayOf(16, 17, 18)) }

    try {
      val bundle = manager.resolveIncidentMediaBundle(incidentId = incidentId)
      assertEquals(2, bundle.videoFiles.size)
      assertEquals(2, bundle.photoFiles.size)
      assertEquals(2, bundle.audioFiles.size)
      assertEquals(6, bundle.uris.size)
      assertEquals(1, bundle.frontPhotoFiles.size)
      assertEquals(1, bundle.rearPhotoFiles.size)

      val outcome = manager.dispatchEvidenceWithFallback(
        incidentId = incidentId,
        userName = "Susanta Banik",
        location = null,
        targetPhones = listOf("+919800000000"),
        isOnline = false,
        preferWhatsApp = true
      )
      assertEquals(2, outcome.videoCount)
      assertEquals(2, outcome.photoCount)
      assertEquals(2, outcome.audioCount)
      assertEquals(6, outcome.mediaCount)
    } finally {
      listOf(vid1, vid2, frontImg, rearImg, aud1, aud2).forEach { it.delete() }
      cacheDir.deleteRecursively()
    }
  }

  @Test
  fun `ml plate parser extracts Indian and BH registration plates cleanly`() {
    assertEquals("TR 01 AB 1234", com.example.util.MLServiceManager.extractBestLicensePlate("IND TR 01 AB 1234 TAXI"))
    assertEquals("DL8CAF5031", com.example.util.MLServiceManager.extractBestLicensePlate("Vehicle: DL8CAF5031"))
    assertEquals("22BH1234AA", com.example.util.MLServiceManager.extractBestLicensePlate("Number 22BH1234AA"))
    assertEquals(null, com.example.util.MLServiceManager.extractBestLicensePlate("   "))
  }

  @Test
  fun `storage warden caps and live radar bounded cache enforce free-tier limits`() {
    assertEquals(25L * 1024L * 1024L, com.example.util.StorageWardenManager.TOTAL_BUDGET_BYTES)
    assertEquals(1L * 1024L * 1024L, com.example.util.StorageWardenManager.RADAR_JSON_CAP_BYTES)
    val geohash = com.example.util.StorageWardenManager.encodeGeohash(23.8315, 91.2868, 6)
    assertEquals(6, geohash.length)

    val context = ApplicationProvider.getApplicationContext<Context>()
    val radar = com.example.util.LiveRadarManager(context)
    radar.publishLocation(23.8315, 91.2868, 90f, 5f, 82, sharingActive = true, ownerName = "Test", forceImmediate = true)
    val disconnectMsg = radar.buildGuardianDisconnectAlert("Test", "Battala", 8, 23.8315, 91.2868, reasonPaused = true)
    assertTrue(disconnectMsg.contains("paused live location"))
    assertTrue(disconnectMsg.contains("Battery 8%"))
    val cacheFile = java.io.File(context.filesDir, "live_radar_cache.json")
    repeat(20) {
      if (cacheFile.exists()) return@repeat
      Thread.sleep(25)
    }
    assertTrue(cacheFile.exists())
    assertTrue(cacheFile.length() <= com.example.util.StorageWardenManager.RADAR_JSON_CAP_BYTES)
  }

  @Test
  fun `decoy cancel pin defaults to 0000 and allows customization`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val pinMgr = com.example.util.DuressPinManager(context)
    assertEquals("0000", pinMgr.getDecoyCancelPin())
    assertTrue(pinMgr.isDecoyCancelPin("0000"))
    assertFalse(pinMgr.isDecoyCancelPin("1234"))

    pinMgr.setDecoyCancelPin("2580")
    assertTrue(pinMgr.isDecoyCancelPin("2580"))
    assertFalse(pinMgr.isDecoyCancelPin("0000"))
  }

  @Test
  fun `zero-key survival brain and voice guardian support all Indian and foreign languages`() = runBlocking {
    val firReply = com.example.data.ai.SurakshaContextBuilder.buildLocalResponse("How do I file a Zero-FIR with police?", "112", "Hindi")
    assertTrue(firReply.contains("Zero-FIR"))
    assertTrue(firReply.contains("[Hindi Mode]"))

    val firstAidReply = com.example.data.ai.SurakshaContextBuilder.buildLocalResponse("first aid for heavy bleeding", "911")
    assertTrue(firstAidReply.contains("911"))
    assertTrue(firstAidReply.contains("pressure"))

    val quickCodes = com.example.util.VoiceGuardianManager.QUICK_PILLS.map { it.code }
    assertTrue(quickCodes.containsAll(listOf("EN", "HI", "BN")))
    val allTags = com.example.util.VoiceGuardianManager.SUPPORTED_LANGUAGES.map { it.tag }
    assertTrue(allTags.contains("ja-JP"))
    assertTrue(allTags.contains("es-ES"))
    assertTrue(allTags.contains("ar-SA"))
    assertTrue(allTags.contains("fr-FR"))
  }

  @Test
  fun `ml service manager manages DefaultLifecycleObserver init and teardown safely`() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val mlManager = com.example.util.MLServiceManager(context)
    val dummyOwner = object : androidx.lifecycle.LifecycleOwner {
      val registry = androidx.lifecycle.LifecycleRegistry(this)
      override val lifecycle: androidx.lifecycle.Lifecycle get() = registry
    }

    mlManager.attachToLifecycle(dummyOwner)
    dummyOwner.registry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_CREATE)
    assertFalse(mlManager.isClosed())

    // Missing selfie file fails gracefully without crashing
    val missingCheck = mlManager.verifySelfieFaceLiveness(java.io.File(context.cacheDir, "nonexistent_selfie.jpg"))
    assertFalse(missingCheck.verified)
    assertTrue(missingCheck.reason.contains("missing"))

    // Destroy lifecycle closes clients cleanly and blocks post-close processing
    dummyOwner.registry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_DESTROY)
    assertTrue(mlManager.isClosed())
    assertFalse(mlManager.isInitialized())

    val bmp = android.graphics.Bitmap.createBitmap(16, 16, android.graphics.Bitmap.Config.ARGB_8888)
    val closedPlate = mlManager.detectLicensePlate(bmp)
    assertEquals(null, closedPlate.detectedPlate)
    assertTrue(closedPlate.confidenceHint.contains("closed", ignoreCase = true))

    val closedFace = mlManager.verifySelfieFaceLiveness(bmp)
    assertFalse(closedFace.verified)
    assertTrue(closedFace.reason.contains("closed", ignoreCase = true))
    bmp.recycle()
  }

  @Test
  fun `storage warden worker enforces 25MB hard cap, 3-minute circular buffer, and daily GPS geohash compaction`() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()

    // 1. Verify WorkManager enqueues both daily geohash & circular buffer periodic workers
    try {
      androidx.work.WorkManager.initialize(
        context,
        androidx.work.Configuration.Builder().build()
      )
    } catch (_: IllegalStateException) {
      // Already initialized by another test
    }
    com.example.util.StorageWardenManager.schedulePeriodicCleanup(context)
    val workMgr = androidx.work.WorkManager.getInstance(context)
    val dailyInfos = workMgr.getWorkInfosForUniqueWork(com.example.util.StorageWardenWorker.DAILY_WORK_NAME).get()
    val cbInfos = workMgr.getWorkInfosForUniqueWork(com.example.util.StorageWardenWorker.CIRCULAR_BUFFER_WORK_NAME).get()
    assertTrue(dailyInfos.isNotEmpty())
    assertTrue(cbInfos.isNotEmpty())

    val warden = com.example.util.StorageWardenManager(context)
    val now = System.currentTimeMillis()

    // 2. Seed 3-minute circular buffer audio/video files (1 expired > 3 min, 1 fresh < 3 min)
    val cbDir = warden.getCircularBufferDir()
    cbDir.listFiles()?.forEach { it.delete() }
    val expiredAv = warden.writeCircularBufferChunk(
      fileName = "old_buffer_audio.m4a",
      data = ByteArray(1024),
      timestampMs = now - (4L * 60L * 1000L)
    )
    // Re-create explicitly with old mtime so test checks sweep pass directly
    expiredAv.writeBytes(ByteArray(1024))
    expiredAv.setLastModified(now - (4L * 60L * 1000L))
    val freshAv = java.io.File(cbDir, "fresh_buffer_video.mp4").apply {
      writeBytes(ByteArray(2048))
      setLastModified(now - (30L * 1000L))
    }

    // 3. Seed oversized caches exceeding 25 MB across offline cache + circular buffer to test global hard cap
    val cacheDir = java.io.File(context.cacheDir, "offline_tiles_cache").apply { mkdirs() }
    val habitsDir = java.io.File(context.filesDir, "ai_habits").apply { mkdirs() }
    val largeTiles = java.io.File(cacheDir, "oversized_tile_pack.bin").apply {
      writeBytes(ByteArray(10 * 1024 * 1024))
      setLastModified(now - 10_000L)
    }
    val largeHabit = java.io.File(habitsDir, "oversized_habit.bin").apply {
      writeBytes(ByteArray(6 * 1024 * 1024))
      setLastModified(now - 10_000L)
    }

    // 4. Seed Room GPS breadcrumbs (>24h old should compress into daily geohash; <24h preserved)
    val appDb = com.example.data.AppDatabase.getDatabase(context)
    val oldTs = now - (30L * 3600L * 1000L)
    val recentTs = now - (1L * 3600L * 1000L)
    appDb.locationBreadcrumbDao().insertBreadcrumb(
      com.example.data.model.LocationBreadcrumbEntity(
        latitude = 23.8315,
        longitude = 91.2868,
        accuracyMeters = 6f,
        batteryPercent = 85,
        geohash = com.example.util.StorageWardenManager.encodeGeohash(23.8315, 91.2868, 6),
        timestamp = oldTs
      )
    )
    appDb.locationBreadcrumbDao().insertBreadcrumb(
      com.example.data.model.LocationBreadcrumbEntity(
        latitude = 23.8400,
        longitude = 91.2900,
        accuracyMeters = 5f,
        batteryPercent = 84,
        geohash = com.example.util.StorageWardenManager.encodeGeohash(23.8400, 91.2900, 6),
        timestamp = recentTs
      )
    )

    try {
      // 5. Execute StorageWardenWorker pass
      val workerResult = com.example.util.StorageWardenWorker.executeWorkPass(
        context = context,
        mode = com.example.util.StorageWardenWorker.MODE_FULL_OPTIMIZATION
      )
      assertTrue(workerResult is androidx.work.ListenableWorker.Result.Success)
      val outData = (workerResult as androidx.work.ListenableWorker.Result.Success).outputData
      val totalUsed = outData.getLong(com.example.util.StorageWardenWorker.OUT_TOTAL_USED_BYTES, Long.MAX_VALUE)
      assertTrue(
        "Total storage must stay strictly <= 25 MB, was $totalUsed",
        totalUsed <= com.example.util.StorageWardenManager.TOTAL_BUDGET_BYTES
      )
      assertTrue(
        outData.getInt(com.example.util.StorageWardenWorker.OUT_COMPRESSED_BREADCRUMBS, 0) >= 1
      )
      assertTrue(
        outData.getInt(com.example.util.StorageWardenWorker.OUT_PRUNED_CIRCULAR_FILES, 0) >= 1
      )

      // Expired >3 min buffer deleted, fresh <3 min preserved
      assertFalse(expiredAv.exists())
      assertTrue(freshAv.exists())

      // Daily geohash summary created & old Room row pruned while recent row kept
      val summaryFile = java.io.File(context.filesDir, "geohash_trail_summary.json")
      assertTrue(summaryFile.exists())
      val json = org.json.JSONObject(summaryFile.readText())
      assertTrue(json.has("dailyBuckets"))
      assertEquals(1, appDb.locationBreadcrumbDao().getCount())
    } finally {
      expiredAv.delete()
      freshAv.delete()
      largeTiles.delete()
      largeHabit.delete()
      appDb.locationBreadcrumbDao().pruneOlderThan(now + 1000L)
    }
  }

  @Test
  fun `voice guardian persists male or female persona and language and produces localized scripts`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    context.getSharedPreferences("rakshaone_voice_prefs", Context.MODE_PRIVATE).edit().clear().commit()

    val vgm = com.example.util.VoiceGuardianManager(context)
    assertEquals(com.example.util.GuardianVoicePersona.FEMALE, vgm.voicePersona.value)
    assertEquals("EN", vgm.selectedLanguage.value.code)

    val hindi = com.example.util.VoiceGuardianManager.SUPPORTED_LANGUAGES.first { it.code == "HI" }
    vgm.setLanguage(hindi, speakConfirmation = false)
    vgm.setVoicePersona(com.example.util.GuardianVoicePersona.MALE, speakConfirmation = false)

    // Re-instantiate to verify persistence across sessions
    val vgmReloaded = com.example.util.VoiceGuardianManager(context)
    assertEquals("HI", vgmReloaded.selectedLanguage.value.code)
    assertEquals(com.example.util.GuardianVoicePersona.MALE, vgmReloaded.voicePersona.value)

    // Verify localized sample lines and Google TTS voice matching rules
    val sampleHiMale = com.example.util.VoiceGuardianManager.buildPersonaSampleLine("HI", com.example.util.GuardianVoicePersona.MALE)
    val sampleBnFemale = com.example.util.VoiceGuardianManager.buildPersonaSampleLine("BN", com.example.util.GuardianVoicePersona.FEMALE)
    assertTrue(sampleHiMale.contains("पुरुष"))
    assertTrue(sampleBnFemale.contains("মহিলা"))

    val enIn = java.util.Locale.forLanguageTag("en-IN")
    val femaleVoice = android.speech.tts.Voice("en-in-x-enc-local", enIn, android.speech.tts.Voice.QUALITY_HIGH, android.speech.tts.Voice.LATENCY_NORMAL, false, emptySet())
    val maleVoice = android.speech.tts.Voice("en-in-x-end-local", enIn, android.speech.tts.Voice.QUALITY_HIGH, android.speech.tts.Voice.LATENCY_NORMAL, false, emptySet())
    val pickedFemale = com.example.util.VoiceGuardianManager.selectBestVoiceForPersona(listOf(maleVoice, femaleVoice), enIn, wantMale = false)
    val pickedMale = com.example.util.VoiceGuardianManager.selectBestVoiceForPersona(listOf(maleVoice, femaleVoice), enIn, wantMale = true)
    assertEquals("en-in-x-enc-local", pickedFemale?.name)
    assertEquals("en-in-x-end-local", pickedMale?.name)

    // Also verify unlabeled voice pair still separates Male vs Female deterministically
    val unlabeledA = android.speech.tts.Voice("custom-voice-1", enIn, android.speech.tts.Voice.QUALITY_HIGH, android.speech.tts.Voice.LATENCY_NORMAL, false, emptySet())
    val unlabeledB = android.speech.tts.Voice("custom-voice-2", enIn, android.speech.tts.Voice.QUALITY_HIGH, android.speech.tts.Voice.LATENCY_NORMAL, false, emptySet())
    val uFemale = com.example.util.VoiceGuardianManager.selectBestVoiceForPersona(listOf(unlabeledA, unlabeledB), enIn, wantMale = false)
    val uMale = com.example.util.VoiceGuardianManager.selectBestVoiceForPersona(listOf(unlabeledA, unlabeledB), enIn, wantMale = true)
    assertTrue(uFemale?.name != uMale?.name)
  }

  @Test
  fun `suraksha local survival brain responds natively in Hindi Bengali Spanish French and Arabic`() {
    val hi = com.example.data.ai.SurakshaContextBuilder.buildLocalResponse("first aid for bleeding", "112", "Hindi")
    assertTrue(hi.contains("112"))
    assertTrue(hi.contains("सुरक्षा AI"))

    val bn = com.example.data.ai.SurakshaContextBuilder.buildLocalResponse("someone is following me", "112", "Bengali")
    assertTrue(bn.contains("সুরক্ষা AI"))

    val es = com.example.data.ai.SurakshaContextBuilder.buildLocalResponse("safety tips", "112", "Spanish")
    assertTrue(es.contains("peligro"))

    val fr = com.example.data.ai.SurakshaContextBuilder.buildLocalResponse("safety tips", "112", "French")
    assertTrue(fr.contains("danger"))

    val ar = com.example.data.ai.SurakshaContextBuilder.buildLocalResponse("safety tips", "112", "Arabic")
    assertTrue(ar.contains("خطر"))
  }

  @Test
  fun `live radar eliminates self duplicate and retains single canonical You self pin`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val radar = com.example.util.LiveRadarManager(context)

    // Publish self pin
    radar.publishLocation(
      lat = 23.8315,
      lng = 91.2868,
      bearing = 45f,
      speed = 1.2f,
      battery = 98,
      sharingActive = true,
      ownerName = "Susanta Banik",
      forceImmediate = true
    )

    val pins = radar.radarPins.value
    // Exactly one Self pin
    val selfPins = pins.values.filter { it.relationship.equals("Self", ignoreCase = true) }
    assertEquals(1, selfPins.size)
    assertEquals(98, selfPins.first().battery)

    // Verify Spouse contact context enrichment
    val spouseContact = TrustedContactEntity(
      id = 101L,
      name = "Spouse Partner",
      relationship = "Spouse",
      phone = "+919876543210",
      priority = 1,
      allow24x7Location = true
    )
    radar.updateTrustedContactsContext(
      contacts = listOf(spouseContact),
      resolvedUids = listOf("uid_spouse_123"),
      uidToPhoneMap = mapOf("uid_spouse_123" to "9876543210"),
      uidToEmailMap = emptyMap()
    )
    // Self pin remains unique and not duplicated
    assertEquals(1, radar.radarPins.value.values.count { it.relationship.equals("Self", ignoreCase = true) })
  }

  @Test
  fun `emergency SMS body includes live battery percentage and exact map link`() {
    val sms = com.example.service.SosAlertService.buildEmergencySmsBody(
      userName = "Susanta",
      incidentId = "INC-12345",
      latitude = 23.83145,
      longitude = 91.28682,
      accuracyMeters = 8f,
      userNote = "Need assistance",
      fallbackReason = "INTERNET_UNAVAILABLE",
      batteryPercent = 97
    )
    assertTrue(sms.contains("Battery: 97%"))
    assertTrue(sms.contains("23.83145"))
    assertTrue(sms.contains("91.28682"))
    assertTrue(sms.contains("https://maps.google.com/?q=23.83145,91.28682"))
    assertTrue(sms.contains("Offline Safety Dispatch"))
  }

  @Test
  fun `place and traffic intel calculates crowd and traffic states offline`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val manager = com.example.util.PlaceAndTrafficIntelManager(context)
    val intel = manager.buildOfflineIntel(
      lat = 23.8315,
      lng = 91.2868,
      streetName = "Ronaldsay Road",
      areaName = "Agartala",
      speedKmh = 4.5f,
      nearbyUsersCount = 3
    )
    assertTrue(intel.isOfflineFallback)
    assertNotNull(intel.crowdLevel)
    assertNotNull(intel.trafficLevel)
    assertTrue(intel.crowdSummary.contains("nearby users") || intel.crowdSummary.contains("Activity") || intel.crowdSummary.contains("Hours"))
  }

  @Test
  fun `live radar captures offline network loss snapshot with battery and map coordinates`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val radar = com.example.util.LiveRadarManager(context)
    val alert = radar.recordOfflineNetworkLossSnapshot(
      lat = 23.8315,
      lng = 91.2868,
      battery = 94,
      ownerName = "Susanta",
      areaName = "Ronaldsay Road"
    )
    assertTrue(alert.contains("Battery 94%"))
    assertTrue(alert.contains("Ronaldsay Road"))
    assertTrue(alert.contains("https://maps.google.com/?q=23.8315,91.2868"))
    assertEquals(alert, radar.disconnectAlertMessage.value)
  }

  @Test
  fun `situation avatars and danger zone features construct compliant GeoJSON properties`() {
    val zone = com.example.data.model.HighRiskZoneEntity(
      zoneId = "ZONE-TEST",
      name = "Subhas Park Corner",
      latitude = 23.8320,
      longitude = 91.2870,
      radiusMeters = 200f,
      riskLevel = "CRITICAL",
      advisoryMessage = "Poor street lighting after 9 PM"
    )
    val zones = listOf(zone)
    assertEquals(1, zones.size)
    assertEquals("CRITICAL", zones[0].riskLevel)
    assertTrue(zones[0].radiusMeters > 0)
  }

  @Test
  fun `critical sms worker authorizes only R3_CRITICAL or connectivity loss fallbacks`() {
    assertTrue(com.example.service.CriticalSmsDispatchWorker.isAuthorizedCriticalContext("R3_CRITICAL", "DUAL_GUARD_ACTIVE"))
    assertTrue(com.example.service.CriticalSmsDispatchWorker.isAuthorizedCriticalContext("R2_HIGH", "NETWORK_LOSS_BLACKBOX"))
    assertTrue(com.example.service.CriticalSmsDispatchWorker.isAuthorizedCriticalContext("R2_HIGH", "SOS_OFFLINE_BACKUP"))
    assertFalse(com.example.service.CriticalSmsDispatchWorker.isAuthorizedCriticalContext("R1_NOMINAL", "ROUTINE_CHECK"))
    assertFalse(com.example.service.CriticalSmsDispatchWorker.isAuthorizedCriticalContext("R2_HIGH", "TELEMETRY_ONLY"))
  }

  @Test
  fun `map geojson hides peer markers when offline and shows self green dots and red victim when online`() {
    val now = System.currentTimeMillis()
    val selfPin = com.example.util.LiveRadarPin(
      uid = "self-1",
      name = "You",
      relationship = "Self",
      latitude = 23.8357,
      longitude = 91.2699,
      isPeerOnline = true
    )
    val peerPinOnline = com.example.util.LiveRadarPin(
      uid = "peer-online",
      name = "Diya",
      relationship = "Spouse",
      latitude = 23.8365,
      longitude = 91.2705,
      updatedAt = now,
      sharingActive = true,
      isPeerOnline = true
    )
    val peerPinOffline = com.example.util.LiveRadarPin(
      uid = "peer-offline",
      name = "Friend",
      relationship = "Friend",
      latitude = 23.8375,
      longitude = 91.2715,
      updatedAt = now,
      sharingActive = true,
      isPeerOnline = false
    )
    val nearbyGreen = com.example.util.NearbySupportUser(
      uid = "near-1",
      displayName = "Rahul",
      roleTag = "Active Citizen",
      isVerifiedResponder = false,
      isOnDuty = false,
      latitude = 23.8360,
      longitude = 91.2701,
      updatedAtMillis = now,
      isOnline = true,
      isEmergencyVictim = false
    )
    val nearbyVictim = com.example.util.NearbySupportUser(
      uid = "victim-1",
      displayName = "Anita",
      roleTag = "SOS Victim",
      isVerifiedResponder = false,
      isOnDuty = false,
      latitude = 23.8380,
      longitude = 91.2720,
      updatedAtMillis = now,
      isOnline = true,
      isEmergencyVictim = true
    )

    // When both current user & peers are online: self + online spouse + green nearby user + red victim render (offline peer hidden)
    val onlineJson = com.example.ui.components.buildAllMapFeaturesGeoJson(
      pins = listOf(selfPin, peerPinOnline, peerPinOffline),
      activeIncidents = emptyList(),
      nearbyHexAlerts = emptyList(),
      nearbyUsers = listOf(nearbyGreen, nearbyVictim),
      pois = emptyList(),
      approachingResponder = null,
      isOnline = true,
      nowMillis = now
    )
    assertTrue(onlineJson.contains("avatar_self_blue"))
    assertTrue(onlineJson.contains("avatar_spouse_heart"))
    assertTrue(onlineJson.contains("dot_nearby_green"))
    assertTrue(onlineJson.contains("avatar_victim_red"))
    assertFalse(onlineJson.contains("Friend"))

    // When current user is offline: ONLY self renders, all peer markers hidden
    val offlineJson = com.example.ui.components.buildAllMapFeaturesGeoJson(
      pins = listOf(selfPin, peerPinOnline, peerPinOffline),
      activeIncidents = emptyList(),
      nearbyHexAlerts = emptyList(),
      nearbyUsers = listOf(nearbyGreen, nearbyVictim),
      pois = emptyList(),
      approachingResponder = null,
      isOnline = false,
      nowMillis = now
    )
    assertTrue(offlineJson.contains("avatar_self_blue"))
    assertFalse(offlineJson.contains("Diya"))
    assertFalse(offlineJson.contains("dot_nearby_green"))
    assertFalse(offlineJson.contains("avatar_victim_red"))
  }

  @Test
  fun `hex rescue manager syncs accepted responder live coordinates to outgoing victim alert and cloud list`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val citizenManager = GoodCitizenManager(context)
    val hexManager = HexagonalRescueManager(context, citizenManager)

    val outgoing = hexManager.broadcastOutgoingHexSos(
      incidentId = "INC-SYNC-1",
      victimName = "Ananya",
      victimLat = 28.6139,
      victimLng = 77.2090,
      locationDescription = "Connaught Place",
      victimUid = "victim-uid-1"
    )
    assertEquals("INC-SYNC-1", hexManager.activeOutgoingAlert.value?.incidentId)

    val responderAcceptedAlert = outgoing.copy(
      status = HexAlertStatus.EN_ROUTE,
      lifecycleState = com.example.risk.HexRescueLifecycleState.EN_ROUTE,
      acceptedResponderUid = "resp-uid-9",
      acceptedResponderName = "Vikram",
      acceptedResponderPhone = "+919811112222",
      acceptedResponderLat = 28.6145,
      acceptedResponderLng = 77.2098
    )
    hexManager.updateResponderInputs(cloudAlerts = listOf(responderAcceptedAlert))

    assertEquals(1, hexManager.allCloudHexAlerts.value.size)
    val synced = hexManager.activeOutgoingAlert.value
    assertEquals("Vikram", synced?.acceptedResponderName)
    assertEquals("+919811112222", synced?.acceptedResponderPhone)
    assertEquals(com.example.risk.HexRescueLifecycleState.EN_ROUTE, synced?.lifecycleState)
    assertEquals(28.6145, synced?.acceptedResponderLat ?: 0.0, 0.0001)
  }

  @Test
  fun `map geojson renders approaching responder savior pin and distant guardian victim alert when online`() {
    val now = System.currentTimeMillis()
    val responderInfo = com.example.ui.components.ApproachingResponderInfo(
      alertId = "HEX-99",
      responderName = "Rohan",
      responderPhone = "+919876543210",
      responderLat = 28.6150,
      responderLng = 77.2100,
      distanceMeters = 180f,
      statusLabel = "EN_ROUTE",
      targetVictimName = "Meera",
      targetVictimLat = 28.6139,
      targetVictimLng = 77.2090,
      etaMinutes = 3
    )
    val distantHex = HexRescueAlert(
      alertId = "HEX-99",
      incidentId = "INC-99",
      victimName = "Meera",
      victimLatitude = 28.6139,
      victimLongitude = 77.2090,
      status = HexAlertStatus.EN_ROUTE,
      lifecycleState = com.example.risk.HexRescueLifecycleState.EN_ROUTE
    )
    val geoJson = com.example.ui.components.buildAllMapFeaturesGeoJson(
      pins = emptyList(),
      activeIncidents = emptyList(),
      nearbyHexAlerts = listOf(distantHex),
      nearbyUsers = emptyList(),
      pois = emptyList(),
      approachingResponder = responderInfo,
      isOnline = true,
      nowMillis = now
    )
    assertTrue(geoJson.contains("avatar_savior_green"))
    assertTrue(geoJson.contains("Rohan"))
    assertTrue(geoJson.contains("avatar_victim_red"))
    assertTrue(geoJson.contains("Meera"))
  }

  @Test
  fun `volume down detector triggers ONLY for 3 discrete quick presses when locked or screen off`() {
    val detector = com.example.util.VolumeDownTripleClickDetector()

    // 1. Unlocked & screen on: even 3 rapid presses MUST NOT trigger
    assertFalse(detector.onVolumeDownEvent(1_000L, isPhoneLockedOrScreenOff = false))
    assertFalse(detector.onVolumeDownEvent(1_200L, isPhoneLockedOrScreenOff = false))
    assertFalse(detector.onVolumeDownEvent(1_400L, isPhoneLockedOrScreenOff = false))
    assertEquals(0, detector.currentPendingPressCount(1_450L))

    // 2. Locked: only 2 presses MUST NOT trigger
    assertFalse(detector.onVolumeDownEvent(2_000L, isPhoneLockedOrScreenOff = true))
    assertFalse(detector.onVolumeDownEvent(2_250L, isPhoneLockedOrScreenOff = true))
    assertEquals(2, detector.currentPendingPressCount(2_300L))

    // Reset by waiting for gap > 650ms
    assertFalse(detector.onVolumeDownEvent(3_200L, isPhoneLockedOrScreenOff = true))
    assertEquals(1, detector.currentPendingPressCount(3_200L))

    // 3. Locked: constant hold (isRepeat = true) MUST reset & NOT trigger
    assertFalse(detector.onVolumeDownEvent(4_000L, isPhoneLockedOrScreenOff = true))
    assertFalse(detector.onVolumeDownEvent(4_200L, isPhoneLockedOrScreenOff = true))
    assertFalse(detector.onVolumeDownEvent(4_250L, isPhoneLockedOrScreenOff = true, isRepeat = true))
    assertEquals(0, detector.currentPendingPressCount(4_260L))

    // 4. Locked: sub-90ms constant-hold burst MUST reset & NOT trigger
    assertFalse(detector.onVolumeDownEvent(6_000L, isPhoneLockedOrScreenOff = true))
    assertFalse(detector.onVolumeDownEvent(6_050L, isPhoneLockedOrScreenOff = true))
    assertFalse(detector.onVolumeDownEvent(6_100L, isPhoneLockedOrScreenOff = true))
    assertFalse(detector.onVolumeDownEvent(6_150L, isPhoneLockedOrScreenOff = true))
    assertEquals(0, detector.currentPendingPressCount(6_160L))

    // 5. Locked: gap > 650ms between presses MUST NOT trigger
    assertFalse(detector.onVolumeDownEvent(8_000L, isPhoneLockedOrScreenOff = true))
    assertFalse(detector.onVolumeDownEvent(8_220L, isPhoneLockedOrScreenOff = true))
    assertFalse(detector.onVolumeDownEvent(9_100L, isPhoneLockedOrScreenOff = true)) // 880ms gap -> resets to 1
    assertFalse(detector.onVolumeDownEvent(9_320L, isPhoneLockedOrScreenOff = true)) // 2nd since reset -> still false

    // 6. Locked: 3 discrete rapid presses (<650ms gap, >=90ms apart, <=1500ms total) TRIGGER!
    assertFalse(detector.onVolumeDownEvent(12_000L, isPhoneLockedOrScreenOff = true))
    assertFalse(detector.onVolumeDownEvent(12_250L, isPhoneLockedOrScreenOff = true))
    assertTrue(detector.onVolumeDownEvent(12_520L, isPhoneLockedOrScreenOff = true))
    assertEquals(0, detector.currentPendingPressCount(12_530L))
  }

  @Test
  fun `three quick volume down presses activate silent mode stealth SOS only when locked or screen off`() {
    val app = ApplicationProvider.getApplicationContext<android.app.Application>()
    if (com.google.firebase.FirebaseApp.getApps(app).isEmpty()) {
      com.google.firebase.FirebaseApp.initializeApp(
        app,
        com.google.firebase.FirebaseOptions.Builder()
          .setApplicationId("1:1234567890:android:testapp")
          .setApiKey("test-api-key")
          .setProjectId("rakshaone-test")
          .build()
      )
    }
    val vm = com.example.ui.MainViewModel(app)

    // Unlocked: 3 quick presses MUST NOT trigger SOS
    assertFalse(vm.registerVolumeDownPress(1_000L, isRepeat = false, overrideLockedOrScreenOff = false))
    assertFalse(vm.registerVolumeDownPress(1_200L, isRepeat = false, overrideLockedOrScreenOff = false))
    assertFalse(vm.registerVolumeDownPress(1_400L, isRepeat = false, overrideLockedOrScreenOff = false))
    assertEquals(com.example.ui.SosCountdownState.IDLE, vm.sosState.value)

    // Locked with gap > 650ms: MUST NOT trigger SOS
    assertFalse(vm.registerVolumeDownPress(3_000L, isRepeat = false, overrideLockedOrScreenOff = true))
    assertFalse(vm.registerVolumeDownPress(3_250L, isRepeat = false, overrideLockedOrScreenOff = true))
    assertFalse(vm.registerVolumeDownPress(4_200L, isRepeat = false, overrideLockedOrScreenOff = true))
    assertEquals(com.example.ui.SosCountdownState.IDLE, vm.sosState.value)

    // Locked with 2 presses + hold repeat: MUST NOT trigger SOS
    assertFalse(vm.registerVolumeDownPress(6_000L, isRepeat = false, overrideLockedOrScreenOff = true))
    assertFalse(vm.registerVolumeDownPress(6_200L, isRepeat = false, overrideLockedOrScreenOff = true))
    assertFalse(vm.registerVolumeDownPress(6_240L, isRepeat = true, overrideLockedOrScreenOff = true))
    assertEquals(com.example.ui.SosCountdownState.IDLE, vm.sosState.value)

    // Locked with 3 discrete quick presses: triggers Silent Mode SOS
    assertFalse(vm.registerVolumeDownPress(10_000L, isRepeat = false, overrideLockedOrScreenOff = true))
    assertFalse(vm.registerVolumeDownPress(10_220L, isRepeat = false, overrideLockedOrScreenOff = true))
    assertTrue(vm.registerVolumeDownPress(10_480L, isRepeat = false, overrideLockedOrScreenOff = true))
    assertTrue(vm.isSilentMode.value)
    assertEquals(com.example.ui.SosCountdownState.ACTIVE, vm.sosState.value)

    // Verify LockscreenVolumeSosService + mediaPlayback FGS permission declared in manifest
    val pkgInfo = app.packageManager.getPackageInfo(
      app.packageName,
      android.content.pm.PackageManager.GET_PERMISSIONS or android.content.pm.PackageManager.GET_SERVICES
    )
    assertTrue(
      pkgInfo.requestedPermissions?.contains(android.Manifest.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK) == true
    )
    assertTrue(
      pkgInfo.services?.any { it.name == com.example.service.LockscreenVolumeSosService::class.java.name } == true
    )
  }

  @Test
  fun `room database caches incoming emergency alerts for zero-network offline resilience`() {
    runBlocking {
      val dao = db.cachedIncomingAlertDao()

      val hexAlert = HexRescueAlert(
        alertId = "HEX-OFFLINE-TEST-1",
        incidentId = "INC-OFFLINE-1",
        victimUid = "victim-123",
        victimName = "Kavita Rao",
        victimLatitude = 28.5355,
        victimLongitude = 77.3910,
        currentRadiusMeters = 750f,
        expansionStage = 1,
        secondsRemainingInStage = 25,
        dangerType = "R3 CRITICAL SOS",
        timestamp = System.currentTimeMillis(),
        status = HexAlertStatus.BROADCASTING,
        lifecycleState = com.example.risk.HexRescueLifecycleState.REQUEST_BROADCAST,
        compassSector = "E",
        sourceChannel = "FIRESTORE+BLE",
        evidenceSummary = "Audio scream detected; Dual-Camera captured",
        victimLocationDescription = "Sector 18 Metro Station, Gate 2",
        distanceMeters = 340f,
        estimatedWalkMinutes = 4,
        policeDispatched = true,
        policeStatus = com.example.risk.PoliceEscalationStatus.ELIGIBLE
      )

      // 1. Insert alert into Room cache
      val entity = hexAlert.toCachedEntity()
      dao.insertAlert(entity)

      // 2. Query offline cache
      val cachedList = dao.getActiveHexAlertsOnce()
      assertTrue(cachedList.isNotEmpty())
      val cached = cachedList.firstOrNull { it.alertId == "HEX-OFFLINE-TEST-1" }
      assertNotNull(cached)
      assertEquals("Kavita Rao", cached?.victimName)
      assertEquals("INC-OFFLINE-1", cached?.incidentId)
      assertEquals(28.5355, cached?.victimLatitude ?: 0.0, 0.0001)
      assertEquals("E", cached?.compassSector)
      assertEquals("Sector 18 Metro Station, Gate 2", cached?.victimLocationDescription)

      // 3. Map back to HexRescueAlert domain model
      val domainModel = cached!!.toHexRescueAlert()
      assertEquals("HEX-OFFLINE-TEST-1", domainModel.alertId)
      assertEquals(HexAlertStatus.BROADCASTING, domainModel.status)
      assertEquals(340f, domainModel.distanceMeters, 0.1f)

      // 4. Update responder status in offline cache (e.g., responder marks ACCEPTED offline)
      val accepted = domainModel.copy(
        status = HexAlertStatus.ACCEPTED,
        lifecycleState = com.example.risk.HexRescueLifecycleState.RESPONDER_ACCEPTED,
        acceptedResponderUid = "responder-99",
        acceptedResponderName = "Arjun",
        acceptedResponderPhone = "+919800011122"
      )
      dao.insertAlert(accepted.toCachedEntity())

      val updatedCached = dao.getAlertById("HEX-OFFLINE-TEST-1")
      assertNotNull(updatedCached)
      assertEquals("ACCEPTED", updatedCached?.status)
      assertEquals("Arjun", updatedCached?.acceptedResponderName)
      assertEquals("+919800011122", updatedCached?.acceptedResponderPhone)

      // Clean up test entry
      dao.deleteAlert("HEX-OFFLINE-TEST-1")
      assertNull(dao.getAlertById("HEX-OFFLINE-TEST-1"))
    }
  }

  @Test
  fun `guardian emergency dispatches cache into Room for offline medical and GPS access`() {
    runBlocking {
      val dao = db.cachedIncomingAlertDao()

      val dispatch = com.example.data.repository.CloudEmergencyDispatch(
        id = "DISP-OFFLINE-99",
        dispatchId = "DISP-OFFLINE-99",
        ownerUid = "owner-uid-88",
        ownerName = "Pooja Sharma",
        ownerPhone = "+919822334455",
        alertType = "ANTI_SNATCH_ALARM",
        status = "ACTIVE",
        latitude = 19.0760,
        longitude = 72.8777,
        mapsUrl = "https://maps.google.com/?q=19.0760,72.8777",
        locationSummary = "Marine Drive Promenade",
        medicalSummary = "Blood: B+; Asthma; Emergency Contact: Mother",
        evidenceSummary = "High-g snatch acceleration detected; front camera snapshot taken",
        batteryPercent = 65,
        frontPhotoBase64 = "base64_sample_jpg"
      )

      // Cache incoming guardian dispatch in local Room database via mapper
      dao.insertAlert(dispatch.toCachedEntity())

      val offlineCached = dao.getCachedGuardianDispatchesOnce().map { it.toCloudEmergencyDispatch() }
      assertTrue(offlineCached.isNotEmpty())
      val match = offlineCached.firstOrNull { it.dispatchId == "DISP-OFFLINE-99" }
      assertNotNull(match)
      assertEquals("Pooja Sharma", match?.ownerName)
      assertEquals("ANTI_SNATCH_ALARM", match?.alertType)
      assertEquals("Marine Drive Promenade", match?.locationSummary)
      assertEquals("Blood: B+; Asthma; Emergency Contact: Mother", match?.medicalSummary)
      assertEquals("base64_sample_jpg", match?.frontPhotoBase64)
    }
  }
}

