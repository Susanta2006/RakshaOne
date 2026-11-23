# RakshaOne — AI-Powered Emergency Response & Civilian Dispatch Platform

RakshaOne is a military-grade, offline-resilient emergency response and family safety platform built with Kotlin and Jetpack Compose for Android. It features real-time MapLibre 3D mapping, hexagonal community rescue dispatch, autonomous offline black-box protection, and zero-API-key AI intelligence.

---

## Core Features & Architecture

### 1. Real-Time 3D MapLibre Dispatch & Situation Avatars
- **MapLibre 3D Liberty Vector Map**: 45° pitch street view with 3D buildings and viewport-aligned halo layers.
- **Dynamic Situation-Adaptive Avatars**:
  - **Self (Electric Blue)**: Shows live GPS position, movement speed, heading bearing, and device battery %.
  - **Victim in Distress (Alert Red)**: Automatically transforms into a pulsating Red Avatar (`avatar_victim_red`) when SOS is triggered.
  - **Savior / Rescuer (Emerald Green)**: When a nearby verified citizen or guardian accepts your rescue request, they appear as an Emerald Green Hero Avatar (`avatar_savior_green`) with live approaching vector lines and distance tracking.
  - **Nearby Active Users (Green Dots)**: Overlays nearby RakshaOne citizens and verified responders in real-time for community solace.
  - **Core Family & Spouse**: Dedicated gold family badges and heart spouse badges (`avatar_spouse_heart`) tracking 24/7.
- **Place, Street & Traffic Intelligence HUD**:
  - Real-time reverse geocoded street and area names.
  - Traffic corridor flow lines (Green/Amber/Red).
  - Crowd density scoring (Quiet/Moderate/Crowded).
  - One-tap navigation and dialing for nearby Police Stations and Hospitals.
- **Reported Danger Zones**:
  - Highlights reported high-risk/unsafe zones with red translucent hazard polygons.
  - Proximity warning banner alerts user when within 400 meters of a danger zone.

### 2. Autonomous Offline Black-Box & R3_CRITICAL Dispatch
- **Dead-Man's Network Loss Snapshot**: Automatically captures current GPS coordinates, battery percentage, and timestamps when internet connectivity drops during an active alert or trip.
- **Bluetooth LE Mesh Relay**: Relays emergency SOS packets across 6 directional sectors within 500 meters using zero internet.
- **R3_CRITICAL Automated Dispatch**: Strictly policy-gated emergency engine that triggers silent background SMS (`SmsManager`) and foreground notifications during critical life-safety threats.

### 3. AI Safety Guardian & Local ML
- **MLServiceManager**: On-device OCR for license plate recognition (ML Kit Text Recognition) and face liveness verification (ML Kit Face Detection) for volunteer responders.
- **Voice Guardian Co-Pilot**: Multi-lingual voice recognition and TTS in English, Hindi, and Bengali with secret distress phrase triggers.
- **Storage Warden Worker**: WorkManager background task enforcing a 25-Gb/25MB secure storage cap via daily geohash compression and 3-minute circular video/audio buffer management.

### 4. Good Citizen & Trusted Circle Management
- **Core Family Members**: Father, Mother, Brother, Sister, and Spouse prioritized with 24/7 active tracking.
- **Trust Ledger & KYC**: Zero-start trust score ledger with peer endorsement referral codes.

---

## APK Distribution
- **Downloadable Binary**: `RakshaOne.apk` (Root directory)
- **Minimum SDK**: 26 (Android 8.0+)
- **Target / Compile SDK**: Android 34 / 35
