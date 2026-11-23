package com.example.risk

import android.location.Location

/**
 * Five-Subsystem Deterministic Risk Intelligence Engine & Centralized Non-Bypassable Policy Gate.
 *
 * Core Invariants:
 * 1. Travel shakes and road bumps (car/auto/bus/train vibration) NEVER trigger SOS or R3.
 * 2. Observability loss (GPS unavailable, network offline, low battery) NEVER creates R2/R3 alone.
 * 3. R3 — CRITICAL emergency occurs ONLY upon explicit user SOS confirmation
 *    (SOS Countdown completion, Instant SOS button, or explicit Duress PIN).
 * 4. Emergency Escalation Policy Gate: IF riskLevel == R3_CRITICAL THEN policeEscalationEligible = true ELSE false.
 */
object EmergencyEscalationPolicyEngine {

    fun isPoliceEscalationEligible(assessment: RiskAssessmentResult?): Boolean {
        if (assessment == null) return false
        return assessment.riskLevel == RiskLevel.R3_CRITICAL &&
            assessment.actionFlags.policeEscalationEligible &&
            isExplicitCriticalTrigger(assessment.triggerOrigin)
    }

    fun isPoliceEscalationEligible(riskLevel: RiskLevel, triggerOrigin: TriggerOrigin): Boolean {
        return riskLevel == RiskLevel.R3_CRITICAL && isExplicitCriticalTrigger(triggerOrigin)
    }

    fun isExplicitCriticalTrigger(origin: TriggerOrigin): Boolean {
        return origin == TriggerOrigin.USER_EXPLICIT_SOS_COUNTDOWN ||
            origin == TriggerOrigin.USER_EXPLICIT_INSTANT_SOS ||
            origin == TriggerOrigin.USER_EXPLICIT_DURESS_PIN
    }

    @Throws(PolicyViolationException::class)
    fun requirePoliceEscalationAuthorized(assessment: RiskAssessmentResult?, incidentId: String?) {
        if (incidentId.isNullOrBlank()) {
            throw PolicyViolationException("POLICY_BLOCKED: Police/112 escalation requires a persisted incidentId.")
        }
        if (assessment == null) {
            throw PolicyViolationException("POLICY_BLOCKED: Missing RiskAssessmentResult for incident $incidentId.")
        }
        if (!isPoliceEscalationEligible(assessment)) {
            throw PolicyViolationException(
                "POLICY_BLOCKED: Risk level ${assessment.riskLevel.name} (origin=${assessment.triggerOrigin.name}) is ineligible for police/112 escalation. ONLY R3_CRITICAL with explicit SOS authorization is allowed."
            )
        }
    }
}

class RiskIntelligenceEngine {

    /**
     * Subsystem 1 (Location Freshness Intelligence)
     */
    fun classifyLocationIntelligence(
        location: Location?,
        batteryPercent: Int,
        nowMillis: Long = System.currentTimeMillis()
    ): LocationIntelligenceSnapshot {
        val safeBattery = batteryPercent.coerceIn(0, 100)
        val lowBatteryPreSync = safeBattery <= 15

        if (location == null || (location.latitude == 0.0 && location.longitude == 0.0)) {
            return LocationIntelligenceSnapshot(
                latitude = null,
                longitude = null,
                accuracyMeters = 0f,
                speedKmh = 0f,
                capturedAtMillis = 0L,
                ageSeconds = Long.MAX_VALUE,
                freshness = LocationFreshness.UNAVAILABLE,
                batteryPercent = safeBattery,
                isLowBatteryPreSync = lowBatteryPreSync
            )
        }

        val fixTime = if (location.time > 0L) location.time else nowMillis
        val ageSec = ((nowMillis - fixTime) / 1000L).coerceAtLeast(0L)
        val freshness = when {
            ageSec <= 30L && location.accuracy <= 65f -> LocationFreshness.LIVE
            ageSec <= 180L -> LocationFreshness.RECENT
            ageSec <= 900L -> LocationFreshness.STALE
            else -> LocationFreshness.LAST_KNOWN
        }
        return LocationIntelligenceSnapshot(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracyMeters = location.accuracy.coerceAtLeast(0f),
            speedKmh = if (location.hasSpeed()) (location.speed * 3.6f).coerceAtLeast(0f) else 0f,
            capturedAtMillis = fixTime,
            ageSeconds = ageSec,
            freshness = freshness,
            batteryPercent = safeBattery,
            isLowBatteryPreSync = lowBatteryPreSync
        )
    }

    /**
     * Subsystems 1–4 Execution:
     * Perception -> Context Intelligence -> Risk Reasoning -> Decision Policy Engine
     */
    fun evaluate(
        observation: PerceptionObservation,
        incidentId: String? = null
    ): RiskAssessmentResult {
        // Subsystem 2: Context Intelligence Engine
        val locSnap = observation.locationSnapshot
        val isMovingFast = maxOf(locSnap.speedKmh, observation.sustainedTravelSpeedKmh) >= 6.0f
        val isTravelVibration = observation.travelBumpFilterEnabled &&
            observation.dynamicJerkG >= 1.25f &&
            (isMovingFast || (!observation.explicitSosButtonClicked && !observation.explicitInstantSosClicked && !observation.explicitDuressPinEntered))

        val obsNotes = mutableListOf<String>()
        if (locSnap.freshness == LocationFreshness.UNAVAILABLE || locSnap.freshness == LocationFreshness.STALE || locSnap.freshness == LocationFreshness.LAST_KNOWN) {
            obsNotes.add("GPS freshness=${locSnap.freshness.name}")
        }
        if (!observation.isNetworkOnline) {
            obsNotes.add("Network offline (Room queue armed)")
        }
        if (locSnap.isLowBatteryPreSync) {
            obsNotes.add("Battery low (${locSnap.batteryPercent}%) — pre-sync recommended")
        }

        val context = ContextAssessment(
            isTravelingInVehicle = isMovingFast,
            isTravelBumpSuppressed = isTravelVibration,
            isObservabilityDegraded = obsNotes.isNotEmpty(),
            observabilityNotes = obsNotes,
            contextSummary = buildString {
                if (isMovingFast) append("In transit (${locSnap.speedKmh.toInt()} km/h) • ")
                if (isTravelVibration) append("Travel bump noise filtered • ")
                if (obsNotes.isEmpty()) append("Telemetry nominal") else append(obsNotes.joinToString("; "))
            }
        )

        // Subsystem 3 & 4: Risk Reasoning & Non-Bypassable Decision Policy Engine
        val assessmentId = "RISK-${observation.timestampMillis}-${(100..999).random()}"

        // EXPLICIT SOS OVERRIDE ONLY -> R3_CRITICAL
        if (observation.explicitSosButtonClicked || observation.explicitInstantSosClicked || observation.explicitDuressPinEntered) {
            val triggerOrigin = when {
                observation.explicitDuressPinEntered -> TriggerOrigin.USER_EXPLICIT_DURESS_PIN
                observation.explicitInstantSosClicked -> TriggerOrigin.USER_EXPLICIT_INSTANT_SOS
                else -> TriggerOrigin.USER_EXPLICIT_SOS_COUNTDOWN
            }
            val reasons = mutableListOf(triggerOrigin.name)
            if (locSnap.hasCoordinates) reasons.add("LOCATION_${locSnap.freshness.name}")
            if (observation.inHighRiskZone) reasons.add("HIGH_RISK_ZONE_CONTEXT")
            if (!observation.isNetworkOnline) reasons.add("OFFLINE_QUEUE_PLUS_BLE_ACTIVE")

            return RiskAssessmentResult(
                assessmentId = assessmentId,
                incidentId = incidentId,
                riskLevel = RiskLevel.R3_CRITICAL,
                triggerOrigin = triggerOrigin,
                confidence = 1.0f,
                reasonCodes = reasons,
                explanation = "Explicit SOS activation confirmed by user (${triggerOrigin.name}). Full critical response unlocked.",
                preventiveGuidance = "Capturing audio, video & photo evidence for your Trusted Contacts, initiating Bluetooth + Hexagonal rescue broadcast, and unlocking 112 dispatch.",
                context = context,
                actionFlags = PolicyActionFlags(
                    policeEscalationEligible = true,
                    notifyTrustedContactsSmsWhatsApp = true,
                    enableTrustedMediaSharing = true,
                    enableBluetoothHexBroadcast = true,
                    enableFirestoreHexBroadcast = true,
                    enableEvidenceCapture = true,
                    requireUser112Interaction = true
                ),
                evaluatedAtMillis = observation.timestampMillis
            )
        }

        // Trip Corridor Breach + High-Risk Zone context -> R2_HIGH (Trusted contacts advisory ONLY, NEVER Police/112)
        if (observation.routeCorridorBreached && observation.inHighRiskZone) {
            return RiskAssessmentResult(
                assessmentId = assessmentId,
                incidentId = incidentId,
                riskLevel = RiskLevel.R2_HIGH,
                triggerOrigin = TriggerOrigin.ROUTE_DEVIATION_ADVISORY,
                confidence = 0.78f,
                reasonCodes = listOf("ROUTE_CORRIDOR_BREACH", "HIGH_RISK_ZONE_ENTRY", "POLICE_GATE_BLOCKED_BELOW_R3"),
                explanation = "Route corridor breach detected inside user-marked risk zone (${observation.highRiskZoneName ?: "caution zone"}). No SOS button pressed.",
                preventiveGuidance = "Check in with Trusted Contacts or press SOS immediately if you feel unsafe. 112 is not contacted automatically.",
                context = context,
                actionFlags = PolicyActionFlags(
                    policeEscalationEligible = false,
                    notifyTrustedContactsSmsWhatsApp = true,
                    enableTrustedMediaSharing = false,
                    enableBluetoothHexBroadcast = false,
                    enableFirestoreHexBroadcast = false,
                    enableEvidenceCapture = false,
                    requireUser112Interaction = false
                ),
                evaluatedAtMillis = observation.timestampMillis
            )
        }

        // Single route deviation, prolonged stationary stop, or caution zone -> R1_ATTENTION
        if (observation.routeCorridorBreached || observation.prolongedStopSeconds >= 120L || observation.inHighRiskZone) {
            val reasons = mutableListOf<String>()
            val origin = when {
                observation.routeCorridorBreached -> {
                    reasons.add("ROUTE_CORRIDOR_BREACH")
                    TriggerOrigin.ROUTE_DEVIATION_ADVISORY
                }
                observation.prolongedStopSeconds >= 120L -> {
                    reasons.add("PROLONGED_STATIONARY_STOP")
                    TriggerOrigin.STATIONARY_ANOMALY_CHECK
                }
                else -> {
                    reasons.add("CAUTION_ZONE_PROXIMITY")
                    TriggerOrigin.GEOFENCE_ADVISORY
                }
            }
            return RiskAssessmentResult(
                assessmentId = assessmentId,
                incidentId = incidentId,
                riskLevel = RiskLevel.R1_ATTENTION,
                triggerOrigin = origin,
                confidence = 0.62f,
                reasonCodes = reasons,
                explanation = "Trip or caution-zone advisory detected. Monitoring heightened without triggering emergency escalation.",
                preventiveGuidance = "Confirm trip check-in or press SOS if assistance is needed.",
                context = context,
                actionFlags = PolicyActionFlags(
                    policeEscalationEligible = false,
                    notifyTrustedContactsSmsWhatsApp = false,
                    enableTrustedMediaSharing = false,
                    enableBluetoothHexBroadcast = false,
                    enableFirestoreHexBroadcast = false,
                    enableEvidenceCapture = false,
                    requireUser112Interaction = false
                ),
                evaluatedAtMillis = observation.timestampMillis
            )
        }

        // Travel bumps / shakes or observability loss -> Strictly R0_NORMAL (or R1_ATTENTION observability note only)
        if (isTravelVibration) {
            return RiskAssessmentResult(
                assessmentId = assessmentId,
                incidentId = incidentId,
                riskLevel = RiskLevel.R0_NORMAL,
                triggerOrigin = TriggerOrigin.TRAVEL_BUMP_OBSERVED,
                confidence = 0.94f,
                reasonCodes = listOf("TRAVEL_BUMP_SUPPRESSED", "EXPLICIT_SOS_ONLY_POLICY"),
                explanation = "Device vibration (${String.format(java.util.Locale.US, "%.1f", observation.dynamicJerkG)}g) classified as normal travel/handling motion. No emergency detected.",
                preventiveGuidance = "Travel bump guard active. Press the SOS button whenever you actually need help.",
                context = context,
                actionFlags = PolicyActionFlags(
                    policeEscalationEligible = false,
                    notifyTrustedContactsSmsWhatsApp = false,
                    enableTrustedMediaSharing = false,
                    enableBluetoothHexBroadcast = false,
                    enableFirestoreHexBroadcast = false,
                    enableEvidenceCapture = false,
                    requireUser112Interaction = false
                ),
                evaluatedAtMillis = observation.timestampMillis
            )
        }

        val nominalReasons = mutableListOf("NOMINAL_MONITORING")
        if (obsNotes.isNotEmpty()) nominalReasons.add("OBSERVABILITY_DEGRADED_NO_ESCALATION")
        return RiskAssessmentResult(
            assessmentId = assessmentId,
            incidentId = incidentId,
            riskLevel = RiskLevel.R0_NORMAL,
            triggerOrigin = TriggerOrigin.NONE,
            confidence = 0.97f,
            reasonCodes = nominalReasons,
            explanation = if (obsNotes.isEmpty()) "All safety telemetry nominal." else "Nominal state (${obsNotes.joinToString(", ")}); observability loss does not trigger escalation.",
            preventiveGuidance = "Shield ready. Tap SOS anytime to initiate trusted-contact alerts, Bluetooth rescue, and 112 assistance.",
            context = context,
            actionFlags = PolicyActionFlags(
                policeEscalationEligible = false,
                notifyTrustedContactsSmsWhatsApp = false,
                enableTrustedMediaSharing = false,
                enableBluetoothHexBroadcast = false,
                enableFirestoreHexBroadcast = false,
                enableEvidenceCapture = false,
                requireUser112Interaction = false
            ),
            evaluatedAtMillis = observation.timestampMillis
        )
    }
}
