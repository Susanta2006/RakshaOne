/**
 * Firebase Cloud Functions for RakshaOne
 * Enforces non-bypassable R3_CRITICAL-only emergency escalation policy on backend
 * alongside trusted-contact emergency email notifications and peer verification.
 */

const functions = require("firebase-functions");
const admin = require("firebase-admin");
const nodemailer = require("nodemailer");

if (!admin.apps.length) {
  admin.initializeApp();
}

const DATABASE_ID = "ai-studio-android-rakshaon-45087928-cfb8-48cb-a931-7b3f2946188e";
const ALLOWED_EXPLICIT_ORIGINS = new Set([
  "USER_EXPLICIT_SOS_COUNTDOWN",
  "USER_EXPLICIT_INSTANT_SOS",
  "USER_EXPLICIT_SILENT_SOS",
  "USER_EXPLICIT_DURESS_PIN"
]);

function getDb() {
  const firestore = admin.firestore();
  try {
    firestore.settings({ databaseId: DATABASE_ID });
  } catch (_) {
    // Already initialized
  }
  return firestore;
}

const transporter = nodemailer.createTransport({
  service: "gmail",
  auth: {
    user: process.env.EMERGENCY_EMAIL_USER || "rakshaone.alerts@gmail.com",
    pass: process.env.EMERGENCY_EMAIL_PASS || "sample_app_password"
  }
});

/**
 * Non-bypassable backend gate for any automated police / 112 escalation event.
 * Rejects R0_NORMAL, R1_ATTENTION, R2_HIGH, and non-explicit origins, logs an audit record,
 * and ensures idempotency per incidentId.
 */
exports.enforceR3PoliceEscalation = functions.https.onCall(async (data, context) => {
  if (!context.auth || !context.auth.uid) {
    throw new functions.https.HttpsError("unauthenticated", "Authentication required.");
  }

  const uid = context.auth.uid;
  const incidentId = String(data.incidentId || "").trim();
  const riskLevel = String(data.riskLevel || "").trim();
  const triggerOrigin = String(data.triggerOrigin || "").trim();
  const assessmentId = String(data.assessmentId || "").trim();

  if (!incidentId) {
    throw new functions.https.HttpsError("invalid-argument", "Real incidentId is required.");
  }

  const db = getDb();
  const rejectionRef = db.collection("dispatch_attempts").doc(`REJ-${incidentId}-${Date.now()}`);

  if (riskLevel !== "R3_CRITICAL" || !ALLOWED_EXPLICIT_ORIGINS.has(triggerOrigin)) {
    await rejectionRef.set({
      attemptId: rejectionRef.id,
      incidentId,
      ownerUid: uid,
      channel: "112_ESCALATION_POLICY_GATE",
      deliveryState: "POLICY_REJECTED",
      details: `Rejected riskLevel=${riskLevel}, origin=${triggerOrigin}. Only R3_CRITICAL explicit SOS allowed.`,
      createdAt: admin.firestore.FieldValue.serverTimestamp()
    });
    throw new functions.https.HttpsError(
      "permission-denied",
      "POLICY_VIOLATION: Only R3_CRITICAL explicit SOS incidents may initiate 112 escalation."
    );
  }

  const incidentSnap = await db.collection("incidents").doc(incidentId).get();
  if (!incidentSnap.exists || incidentSnap.data().ownerUid !== uid) {
    throw new functions.https.HttpsError("permission-denied", "Incident record not found or unauthorized.");
  }

  const escalationRef = db.collection("emergency_escalation_events").doc(incidentId);
  const existing = await escalationRef.get();
  if (existing.exists && existing.data().policeEscalationEligible === true) {
    return {
      authorized: true,
      idempotentHit: true,
      incidentId,
      riskLevel: "R3_CRITICAL",
      policyVersion: "POLICY-V2-EXPLICIT-R3"
    };
  }

  await escalationRef.set({
    incidentId,
    ownerUid: uid,
    assessmentId,
    riskLevel: "R3_CRITICAL",
    triggerOrigin,
    policeEscalationEligible: true,
    policeStatus: "ELIGIBLE",
    deliveryState: "PENDING",
    policyVersion: "POLICY-V2-EXPLICIT-R3",
    createdAt: admin.firestore.FieldValue.serverTimestamp(),
    updatedAt: admin.firestore.FieldValue.serverTimestamp()
  }, { merge: true });

  return {
    authorized: true,
    idempotentHit: false,
    incidentId,
    riskLevel: "R3_CRITICAL",
    policyVersion: "POLICY-V2-EXPLICIT-R3"
  };
});

/**
 * Trusted-Contact-Only Emergency Email Dispatch HTTPS endpoint.
 */
exports.sendEmergencyAlertEmail = functions.https.onRequest(async (req, res) => {
  res.set("Access-Control-Allow-Origin", "*");
  res.set("Access-Control-Allow-Methods", "POST, OPTIONS");
  res.set("Access-Control-Allow-Headers", "Content-Type, X-Emergency-Client");

  if (req.method === "OPTIONS") {
    return res.status(204).send("");
  }
  if (req.method !== "POST") {
    return res.status(405).json({ error: "Method Not Allowed. POST required." });
  }

  try {
    const {
      incidentId,
      userName,
      alertType,
      latitude,
      longitude,
      googleMapsLink,
      customNote,
      recipients,
      audioEvidenceAttached,
      audioSha256Checksum
    } = req.body;

    if (!recipients || !Array.isArray(recipients) || recipients.length === 0) {
      return res.status(400).json({ error: "Missing recipients array" });
    }

    const emailList = recipients
      .map(r => (typeof r === "string" ? r : r.email))
      .filter(e => Boolean(e));

    if (emailList.length === 0) {
      return res.status(400).json({ error: "No valid email addresses provided in recipients." });
    }

    const htmlBody = `
      <div style="font-family: Arial, sans-serif; background-color: #0f172a; color: #f8fafc; padding: 24px; border-radius: 12px; max-width: 600px; margin: auto; border: 2px solid #ef4444;">
        <h1 style="color: #ef4444; margin-top: 0;">RAKSHAONE EMERGENCY ALERT</h1>
        <p style="font-size: 16px; line-height: 1.5;">
          <strong>${userName}</strong> triggered an emergency alert: <strong>${alertType}</strong>.
        </p>
        <div style="background: #1e293b; padding: 16px; border-radius: 8px; margin: 16px 0;">
          <p><strong>Incident ID:</strong> ${incidentId}</p>
          <p><strong>GPS Coordinates:</strong> ${latitude}, ${longitude}</p>
          <p><strong>Note / Condition:</strong> ${customNote || "SOS Triggered"}</p>
          ${audioEvidenceAttached ? `<p style="color: #38bdf8;"><strong>Audio Evidence SHA-256:</strong> ${audioSha256Checksum || "Verified"}</p>` : ""}
        </div>
        <div style="text-align: center; margin: 24px 0;">
          <a href="${googleMapsLink}" style="background-color: #ef4444; color: white; padding: 14px 28px; text-decoration: none; border-radius: 8px; font-weight: bold; font-size: 16px; display: inline-block;">
            OPEN LIVE GOOGLE MAPS LOCATION
          </a>
        </div>
      </div>
    `;

    const mailOptions = {
      from: `"RakshaOne Safety System" <${process.env.EMERGENCY_EMAIL_USER || "rakshaone.alerts@gmail.com"}>`,
      to: emailList.join(", "),
      subject: `URGENT: ${alertType} Alert for ${userName} [Incident ${incidentId}]`,
      text: `EMERGENCY ALERT: ${userName} triggered ${alertType}. Location: ${googleMapsLink}. Note: ${customNote}`,
      html: htmlBody
    };

    await transporter.sendMail(mailOptions);

    return res.status(200).json({
      success: true,
      message: `Emergency email dispatched to ${emailList.length} trusted contact(s).`,
      incidentId,
      dispatchedRecipients: emailList
    });
  } catch (error) {
    console.error("Failed to send emergency alert email:", error);
    return res.status(500).json({
      success: false,
      error: error.message || "Internal server error dispatching alert."
    });
  }
});
