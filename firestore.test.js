const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_123";
const BOB_UID = "bob_456";
const CHARLIE_UID = "charlie_789";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

function buildValidProfile(uid, ts) {
  return {
    userId: uid,
    displayName: "Asha Rao",
    email: "asha@example.com",
    phone: "+919876543210",
    bloodGroup: "O+",
    allergies: "None",
    medicalNotes: "No chronic conditions",
    homeAddress: "12 MG Road",
    workAddress: "Tech Park",
    autoSendSmsOnSos: true,
    sirenBeaconEnabled: true,
    createdAt: ts,
    updatedAt: ts,
  };
}

function buildValidDispatch(
  dispatchId,
  ownerUid,
  ts,
  authorizedEmails = ["guardian@example.com"],
  authorizedUids = []
) {
  return {
    dispatchId,
    ownerUid,
    ownerName: "Susanta Banik",
    ownerPhone: "+918119098684",
    alertType: "ANTI_SNATCH",
    status: "ACTIVE",
    latitude: 23.8357,
    longitude: 91.2699,
    accuracyMeters: 6.5,
    mapsUrl: "https://maps.google.com/?q=23.8357,91.2699",
    locationSummary: "Agartala • Lat: 23.83570, Lng: 91.26990",
    batteryPercent: 74,
    medicalSummary: "Blood: O+ • Allergies: None",
    evidenceSummary: "2 Video(s) + 4 Photo(s) (F:2,R:2) + 1 Audio(.m4a)",
    frontPhotoBase64: "/9j/4AAQSkZJRgABAQAAAQABAAD/",
    rearPhotoBase64: "/9j/4AAQSkZJRgABAQAAAQABAAD/",
    authorizedEmails,
    authorizedPhones: ["+919876543210"],
    authorizedUids,
    deviceLocked: false,
    createdAt: ts,
    updatedAt: ts,
  };
}

test("Unauthenticated user cannot read or write profiles", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).get());
  await assertFails(unauthDb.collection("users").get());
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).set(buildValidProfile(ALICE_UID, new Date(Date.now() - 1000)))
  );
});

test("Owner can create, read, and partially update their own safety profile", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const created = new Date(Date.now() - 10000);

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).set(buildValidProfile(ALICE_UID, created))
  );

  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).get());

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).update({
      phone: "+919811112222",
      bloodGroup: "B+",
      updatedAt: new Date(Date.now() - 1000),
    })
  );
});

test("Rejects cross-user reads, writes, ghost fields, immutable tampering, and collection listing", async () => {
  await testEnv.withSecurityRulesDisabled(async (context) => {
    const ts = new Date(Date.now() - 10000);
    await context.firestore().collection("users").doc(BOB_UID).set(buildValidProfile(BOB_UID, ts));
  });

  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const ts = new Date(Date.now() - 5000);

  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).set(buildValidProfile(ALICE_UID, ts)));
  await assertFails(aliceDb.collection("users").doc(BOB_UID).get());
  await assertFails(aliceDb.collection("users").doc(BOB_UID).update({ phone: "000", updatedAt: new Date() }));
  await assertFails(aliceDb.collection("users").get());
  await assertFails(
    aliceDb.collection("users").doc("alice_ghost").set({
      ...buildValidProfile(ALICE_UID, ts),
      isAdmin: true,
    })
  );
});

test("EmergencyDispatch: owner lifecycle and verified guardian read enforcement", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID, {
    email: "alice@example.com",
    email_verified: true,
  }).firestore();
  const ts = new Date(Date.now() - 10000);

  await assertSucceeds(
    aliceDb.collection("emergency_dispatches").doc("INC-2026-1001").set(
      buildValidDispatch("INC-2026-1001", ALICE_UID, ts, ["guardian@example.com"], [BOB_UID])
    )
  );
  await assertSucceeds(
    aliceDb.collection("emergency_dispatches").doc("INC-2026-1001").update({
      status: "RESOLVED",
      updatedAt: new Date(Date.now() - 1000),
    })
  );
  await assertFails(
    aliceDb.collection("emergency_dispatches").doc("INC-2026-1001").update({
      status: "ACTIVE",
      updatedAt: new Date(Date.now() - 500),
    })
  );

  const guardianDb = testEnv.authenticatedContext(BOB_UID, {
    email: "other@example.com",
    email_verified: true,
  }).firestore();
  await assertSucceeds(
    guardianDb.collection("emergency_dispatches")
      .where("authorizedUids", "array-contains", BOB_UID)
      .get()
  );
  const strangerDb = testEnv.authenticatedContext(CHARLIE_UID, {
    email: "stranger@example.com",
    email_verified: true,
  }).firestore();
  await assertFails(strangerDb.collection("emergency_dispatches").doc("INC-2026-1001").get());
  await assertFails(strangerDb.collection("emergency_dispatches").doc("INC-2026-1001").delete());
  await assertSucceeds(aliceDb.collection("emergency_dispatches").doc("INC-2026-1001").delete());
});

test("PhoneDirectory: exact key lookup succeeds for signed-in users, list denied, spoofing denied", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  const ts = new Date(Date.now() - 2000);

  await assertSucceeds(
    aliceDb.collection("phone_directory").doc("919876543210").set({
      phoneKey: "919876543210",
      uid: ALICE_UID,
      displayName: "Alice",
      email: "alice@example.com",
      appInstalled: true,
      updatedAt: ts,
    })
  );

  // Exact key get succeeds for Bob; collection list & UID spoofing fail
  await assertSucceeds(bobDb.collection("phone_directory").doc("919876543210").get());
  await assertFails(bobDb.collection("phone_directory").get());
  await assertFails(unauthDb.collection("phone_directory").doc("919876543210").get());
  await assertFails(
    bobDb.collection("phone_directory").doc("919876543210").set({
      phoneKey: "919876543210",
      uid: ALICE_UID,
      displayName: "Spoof",
      email: "bob@example.com",
      appInstalled: true,
      updatedAt: ts,
    })
  );
});

test("NON-BYPASSABLE POLICY: rejects R0/R1/R2 and non-explicit triggers in emergency_escalation_events; allows explicit R3_CRITICAL", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const ts = new Date(Date.now() - 2000);

  for (const forbiddenLevel of ["R0_NORMAL", "R1_ATTENTION", "R2_HIGH"]) {
    await assertFails(
      aliceDb.collection("emergency_escalation_events").doc(`INC-${forbiddenLevel}`).set({
        incidentId: `INC-${forbiddenLevel}`,
        ownerUid: ALICE_UID,
        assessmentId: "ASSESS-1",
        riskLevel: forbiddenLevel,
        triggerOrigin: "USER_EXPLICIT_INSTANT_SOS",
        policeEscalationEligible: true,
        policeStatus: "ELIGIBLE",
        deliveryState: "PENDING",
        webPortalUrl: "rakshaone://dispatch/INC",
        dossierToken: "TOK-1234",
        createdAt: ts,
        updatedAt: ts,
      })
    );
  }

  // Non-explicit origin (e.g. travel bump) rejected even if labeled R3_CRITICAL
  await assertFails(
    aliceDb.collection("emergency_escalation_events").doc("INC-BUMP-SPOOF").set({
      incidentId: "INC-BUMP-SPOOF",
      ownerUid: ALICE_UID,
      assessmentId: "ASSESS-2",
      riskLevel: "R3_CRITICAL",
      triggerOrigin: "TRAVEL_BUMP_OBSERVED",
      policeEscalationEligible: true,
      policeStatus: "ELIGIBLE",
      deliveryState: "PENDING",
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Explicit R3_CRITICAL SOS succeeds
  await assertSucceeds(
    aliceDb.collection("emergency_escalation_events").doc("INC-R3-VALID").set({
      incidentId: "INC-R3-VALID",
      ownerUid: ALICE_UID,
      assessmentId: "ASSESS-3",
      riskLevel: "R3_CRITICAL",
      triggerOrigin: "USER_EXPLICIT_INSTANT_SOS",
      policeEscalationEligible: true,
      policeStatus: "AWAITING_USER_ACTION",
      deliveryState: "PENDING",
      webPortalUrl: "rakshaone://dispatch/INC-R3-VALID?token=TOK-R3",
      dossierToken: "TOK-R3",
      createdAt: ts,
      updatedAt: ts,
    })
  );
});

test("Citizen KYC & Zero-Start Trust Score: blocks self-verification and pre-seeded high points; allows peer endorsement", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const ts = new Date(Date.now() - 2000);

  // Alice cannot self-seed VERIFIED or non-zero points
  await assertFails(
    aliceDb.collection("citizens").doc(ALICE_UID).set({
      uid: ALICE_UID,
      verificationStatus: "VERIFIED",
      trustScore: 75,
      isVolunteerActive: true,
      updatedAt: ts,
    })
  );

  // Alice creates zero-start UNVERIFIED profile
  await assertSucceeds(
    aliceDb.collection("citizens").doc(ALICE_UID).set({
      uid: ALICE_UID,
      displayName: "Alice",
      verificationStatus: "UNVERIFIED",
      trustScore: 0,
      acceptedHelpsCount: 0,
      completedHelpsCount: 0,
      declinedHelpsCount: 0,
      isVolunteerActive: false,
      badgeLevel: "Unverified Citizen",
      endorsementCode: "RK-ALICE1",
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Alice cannot self-promote to VERIFIED
  await assertFails(
    aliceDb.collection("citizens").doc(ALICE_UID).update({
      verificationStatus: "VERIFIED",
      updatedAt: new Date(),
    })
  );

  // Alice submits PENDING KYC verification
  await assertSucceeds(
    aliceDb.collection("citizen_verifications").doc("VER-ALICE-1").set({
      verificationId: "VER-ALICE-1",
      ownerUid: ALICE_UID,
      docType: "Aadhaar",
      docLastFourHint: "4321",
      endorsementCode: "RK-ALICE1",
      status: "PENDING",
      endorserUid: null,
      endorserName: null,
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Alice CANNOT endorse herself
  await assertFails(
    aliceDb.collection("citizen_verifications").doc("VER-ALICE-1").update({
      status: "VERIFIED",
      endorserUid: ALICE_UID,
      endorserName: "Self",
      updatedAt: new Date(),
    })
  );

  // Peer Bob can query pending code and endorse Alice
  await assertSucceeds(
    bobDb.collection("citizen_verifications")
      .where("endorsementCode", "==", "RK-ALICE1")
      .where("status", "==", "PENDING")
      .get()
  );
  await assertSucceeds(
    bobDb.collection("citizen_verifications").doc("VER-ALICE-1").update({
      status: "VERIFIED",
      endorserUid: BOB_UID,
      endorserName: "Bob Guardian",
      updatedAt: new Date(),
    })
  );
  await assertSucceeds(
    bobDb.collection("citizens").doc(ALICE_UID).update({
      uid: ALICE_UID,
      verificationStatus: "VERIFIED",
      endorserUid: BOB_UID,
      badgeLevel: "Verified Community Responder",
      updatedAt: new Date(),
    })
  );
});

test("DossierAccess: tokenized R3 get allowed, collection listing blocked, non-R3 blocked", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  const ts = new Date(Date.now() - 2000);

  await assertFails(
    aliceDb.collection("dossier_access").doc("TOK-BAD-R2").set({
      tokenId: "TOK-BAD-R2",
      incidentId: "INC-2",
      ownerUid: ALICE_UID,
      riskLevel: "R2_HIGH",
      expiresAtMillis: Date.now() + 3600000,
      revoked: false,
      createdAt: ts,
    })
  );

  await assertSucceeds(
    aliceDb.collection("dossier_access").doc("TOK-SECRET-123").set({
      tokenId: "TOK-SECRET-123",
      incidentId: "INC-R3-99",
      ownerUid: ALICE_UID,
      riskLevel: "R3_CRITICAL",
      victimName: "Alice",
      expiresAtMillis: Date.now() + 3600000,
      revoked: false,
      createdAt: ts,
    })
  );

  await assertSucceeds(unauthDb.collection("dossier_access").doc("TOK-SECRET-123").get());
  await assertFails(unauthDb.collection("dossier_access").get());
});

test("HexRescueRequest: active feed query and responder pickup lifecycle", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const ts = new Date(Date.now() - 2000);

  await assertSucceeds(
    aliceDb.collection("hex_rescue_requests").doc("HEX-100").set({
      requestId: "HEX-100",
      alertId: "HEX-100",
      incidentId: "INC-100",
      victimUid: ALICE_UID,
      victimName: "Alice",
      latitude: 23.8357,
      longitude: 91.2699,
      currentRadiusMeters: 750,
      expansionStage: 1,
      dangerType: "R3 CRITICAL SOS",
      locationDescription: "Agartala",
      evidenceSummary: "Audio + Cam",
      lifecycleState: "REQUEST_BROADCAST",
      compassSector: "N-NE",
      policeStatus: "AWAITING_USER_ACTION",
      acceptedResponderUid: null,
      acceptedResponderName: null,
      createdAt: ts,
      updatedAt: ts,
    })
  );

  await assertSucceeds(
    bobDb.collection("hex_rescue_requests")
      .where("lifecycleState", "in", [
        "REQUEST_CREATED",
        "RESPONDERS_DISCOVERED",
        "REQUEST_BROADCAST",
        "RESPONDER_ACCEPTED",
        "EN_ROUTE",
        "ARRIVED",
      ])
      .get()
  );

  await assertSucceeds(
    bobDb.collection("hex_rescue_requests").doc("HEX-100").update({
      lifecycleState: "RESPONDER_ACCEPTED",
      acceptedResponderUid: BOB_UID,
      acceptedResponderName: "Bob",
      acceptedResponderPhone: "+919876543210",
      acceptedResponderSelfieB64: "/9j/4AAQSkZJRg==",
      acceptedResponderLat: 23.836,
      acceptedResponderLng: 91.270,
      updatedAt: new Date(),
    })
  );
  await assertSucceeds(
    bobDb.collection("hex_rescue_requests").doc("HEX-100").update({
      lifecycleState: "EN_ROUTE",
      updatedAt: new Date(),
    })
  );

  // Only victim may delete hex_rescue_requests when purging the evidence vault
  await assertFails(bobDb.collection("hex_rescue_requests").doc("HEX-100").delete());
  await assertSucceeds(aliceDb.collection("hex_rescue_requests").doc("HEX-100").delete());
});

test("LiveRadarPin: owner write and authorized guardian-only read", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID, {
    email: "alice@example.com",
    email_verified: true,
  }).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID, {
    email: "father@example.com",
    email_verified: true,
  }).firestore();
  const strangerDb = testEnv.authenticatedContext(CHARLIE_UID, {
    email: "stranger@example.com",
    email_verified: true,
  }).firestore();
  const ts = new Date();

  await assertSucceeds(
    aliceDb.collection("live_radar").doc(ALICE_UID).set({
      ownerUid: ALICE_UID,
      ownerName: "Alice",
      relationshipHint: "Daughter",
      lat: 23.8357,
      lng: 91.2699,
      bearing: 90.0,
      speed: 1.5,
      battery: 82,
      sharingActive: true,
      isEmergency: false,
      statusNote: "Live Sharing Active",
      authorizedUids: [BOB_UID],
      authorizedEmails: ["father@example.com"],
      updatedAt: ts,
    })
  );

  await assertSucceeds(bobDb.collection("live_radar").doc(ALICE_UID).get());
  await assertFails(strangerDb.collection("live_radar").doc(ALICE_UID).get());
  await assertFails(
    bobDb.collection("live_radar").doc(ALICE_UID).update({ battery: 10, updatedAt: new Date() })
  );
});

test("TrustedContacts & AI Safety State: owner-only two-way cloud sync and tier bounds", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const ts = new Date();

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("trusted_contacts").doc("p_919876543210").set({
      contactDocId: "p_919876543210",
      ownerUid: ALICE_UID,
      name: "Papa",
      relationship: "Father",
      phone: "+919876543210",
      email: "papa@example.com",
      priority: 1,
      hasWhatsApp: true,
      allow24x7Location: true,
      updatedAt: ts,
    })
  );
  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).collection("trusted_contacts").get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("trusted_contacts").doc("p_919876543210").get());

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("ai_safety_state").doc("profile").set({
      ownerUid: ALICE_UID,
      sovereigntyTier: 3,
      autonomousGuardEnabled: true,
      confirmedSafeCount: 4,
      confirmedThreatCount: 6,
      fakeCallsTriggeredByAi: 2,
      calibrationBiases: [0.05, 0.0, 0.02, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0],
      updatedAt: ts,
    })
  );
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("ai_safety_state").doc("profile").set({
      ownerUid: ALICE_UID,
      sovereigntyTier: 9,
      autonomousGuardEnabled: true,
      confirmedSafeCount: 0,
      confirmedThreatCount: 0,
      fakeCallsTriggeredByAi: 0,
      calibrationBiases: [],
      updatedAt: ts,
    })
  );
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("ai_safety_state").doc("profile").get());
});

test("EvidenceVault & FAKE_CALL_GUARD: chunked auto-upload readable by guardians only and clean purge on resolution", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID, {
    email: "alice@example.com",
    email_verified: true,
  }).firestore();
  const guardianDb = testEnv.authenticatedContext(BOB_UID, {
    email: "guardian@example.com",
    email_verified: true,
  }).firestore();
  const strangerDb = testEnv.authenticatedContext(CHARLIE_UID, {
    email: "stranger@example.com",
    email_verified: true,
  }).firestore();
  const ts = new Date();

  const fakeCallDispatch = buildValidDispatch("FAKE-INC-1", ALICE_UID, ts, ["guardian@example.com"], [BOB_UID]);
  fakeCallDispatch.alertType = "FAKE_CALL_GUARD";
  await assertSucceeds(
    aliceDb.collection("emergency_dispatches").doc("FAKE-INC-1").set(fakeCallDispatch)
  );

  await assertSucceeds(
    aliceDb.collection("evidence_vault").doc("FAKE-INC-1_audio_deadbeef_0").set({
      chunkId: "FAKE-INC-1_audio_deadbeef_0",
      incidentId: "FAKE-INC-1",
      ownerUid: ALICE_UID,
      mediaKind: "AUDIO",
      fileName: "evidence_FAKE-INC-1.m4a",
      mimeType: "audio/mp4",
      chunkIndex: 0,
      totalChunks: 1,
      sha256: "deadbeef",
      dataBase64: "AAAAIGZ0eXBNNEEgAAAAAE00QSBpc29t",
      authorizedUids: [BOB_UID],
      authorizedEmails: ["guardian@example.com"],
      createdAt: ts,
    })
  );

  // Oversized chunk rejected
  await assertFails(
    aliceDb.collection("evidence_vault").doc("FAKE-INC-1_video_bad_0").set({
      chunkId: "FAKE-INC-1_video_bad_0",
      incidentId: "FAKE-INC-1",
      ownerUid: ALICE_UID,
      mediaKind: "VIDEO",
      fileName: "evidence.mp4",
      mimeType: "video/mp4",
      chunkIndex: 0,
      totalChunks: 1,
      sha256: "deadbeef",
      dataBase64: "A".repeat(260005),
      authorizedUids: [BOB_UID],
      authorizedEmails: ["guardian@example.com"],
      createdAt: ts,
    })
  );

  // Guardian can query & read; stranger cannot; only owner can delete
  await assertSucceeds(
    guardianDb.collection("evidence_vault")
      .where("incidentId", "==", "FAKE-INC-1")
      .where("authorizedUids", "array-contains", BOB_UID)
      .get()
  );
  await assertFails(strangerDb.collection("evidence_vault").doc("FAKE-INC-1_audio_deadbeef_0").get());
  await assertFails(guardianDb.collection("evidence_vault").doc("FAKE-INC-1_audio_deadbeef_0").delete());
  await assertSucceeds(aliceDb.collection("evidence_vault").doc("FAKE-INC-1_audio_deadbeef_0").delete());
});

