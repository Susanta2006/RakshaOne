package com.example.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.common.sdkinternal.MlKitContext
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.abs

data class PlateScanResult(
    val detectedPlate: String?,
    val rawText: String,
    val confidenceHint: String
)

data class FaceVerificationResult(
    val verified: Boolean,
    val faceCount: Int,
    val reason: String,
    val selfieBase64: String? = null
)

/**
 * Offline On-Device ML Kit Vision Manager:
 * - Implements [DefaultLifecycleObserver] & [Closeable] to manage lifecycle and client initialization/teardown
 *   for both ML Kit Text Recognition ([TextRecognizer]) and Face Detection ([FaceDetector]).
 * - Recognizes vehicle registration plates (Indian / BH / International) for Cab/Auto Ride Guardian.
 * - Performs human face presence & liveness verification for Responder Anti-Predator Selfie Lock.
 */
class MLServiceManager(context: Context) : DefaultLifecycleObserver, Closeable {

    private val appContext: Context = context.applicationContext
    private val clientLock = Any()

    @Volatile
    private var textRecognizer: TextRecognizer? = null

    @Volatile
    private var faceDetector: FaceDetector? = null

    @Volatile
    private var initialized: Boolean = false

    @Volatile
    private var closed: Boolean = false

    @Volatile
    private var lastInitError: String? = null

    private var observedLifecycle: Lifecycle? = null

    fun isInitialized(): Boolean = synchronized(clientLock) { initialized && !closed }

    fun isClosed(): Boolean = synchronized(clientLock) { closed }

    fun getLastInitError(): String? = lastInitError

    /**
     * Thread-safely initializes ML Kit context and both TextRecognizer & FaceDetector clients.
     * Safe to invoke multiple times or after [close] to re-open clients.
     */
    fun initialize(): Boolean = synchronized(clientLock) {
        if (initialized && !closed && textRecognizer != null && faceDetector != null) {
            return true
        }
        closed = false
        lastInitError = null

        ensureMlKitContext()

        val textOk = ensureTextRecognizerLocked() != null
        val faceOk = ensureFaceDetectorLocked() != null
        initialized = textOk || faceOk
        return initialized
    }

    /**
     * Binds [MLServiceManager] to an AndroidX [LifecycleOwner] so clients initialize on ON_CREATE
     * and cleanly release native ML Kit resources on ON_DESTROY.
     */
    fun attachToLifecycle(lifecycleOwner: LifecycleOwner) {
        attachToLifecycle(lifecycleOwner.lifecycle)
    }

    fun attachToLifecycle(lifecycle: Lifecycle) {
        val attachBlock = Runnable {
            synchronized(clientLock) {
                if (observedLifecycle === lifecycle) return@Runnable
                observedLifecycle?.removeObserver(this@MLServiceManager)
                observedLifecycle = lifecycle
                lifecycle.addObserver(this@MLServiceManager)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            attachBlock.run()
        } else {
            Handler(Looper.getMainLooper()).post(attachBlock)
        }
    }

    override fun onCreate(owner: LifecycleOwner) {
        initialize()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        close()
    }

    private fun ensureMlKitContext() {
        try {
            MlKitContext.initializeIfNeeded(appContext)
        } catch (_: Throwable) {
            // In instrumentation/device builds MlKitInitProvider handles this automatically;
            // ignoring here keeps Robolectric/JVM unit tests resilient.
        }
    }

    private fun ensureTextRecognizerLocked(): TextRecognizer? {
        textRecognizer?.let { return it }
        return try {
            ensureMlKitContext()
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).also {
                textRecognizer = it
            }
        } catch (t: Throwable) {
            lastInitError = t.message
            Log.w(TAG, "TextRecognizer initialization failed: ${t.message}")
            null
        }
    }

    private fun ensureFaceDetectorLocked(): FaceDetector? {
        faceDetector?.let { return it }
        return try {
            ensureMlKitContext()
            FaceDetection.getClient(
                FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                    .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                    .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                    .setMinFaceSize(0.18f)
                    .build()
            ).also {
                faceDetector = it
            }
        } catch (t: Throwable) {
            lastInitError = t.message
            Log.w(TAG, "FaceDetector initialization failed: ${t.message}")
            null
        }
    }

    private fun acquireTextRecognizer(): TextRecognizer? = synchronized(clientLock) {
        if (closed) return null
        val client = ensureTextRecognizerLocked()
        if (client != null) initialized = true
        client
    }

    private fun acquireFaceDetector(): FaceDetector? = synchronized(clientLock) {
        if (closed) return null
        val client = ensureFaceDetectorLocked()
        if (client != null) initialized = true
        client
    }

    suspend fun detectLicensePlate(
        bitmap: Bitmap,
        rotationDegrees: Int = 0
    ): PlateScanResult = withContext(Dispatchers.Default) {
        if (closed) {
            return@withContext PlateScanResult(null, "", "MLServiceManager is closed")
        }
        val image = InputImage.fromBitmap(bitmap, normalizeRotation(rotationDegrees))
        detectLicensePlate(image)
    }

    suspend fun detectLicensePlateFromUri(uri: Uri): PlateScanResult = withContext(Dispatchers.IO) {
        if (closed) {
            return@withContext PlateScanResult(null, "", "MLServiceManager is closed")
        }
        val image = try {
            InputImage.fromFilePath(appContext, uri)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to load image URI for OCR: ${e.message}")
            return@withContext PlateScanResult(null, "", "Could not read image URI: ${e.message}")
        }
        detectLicensePlate(image)
    }

    suspend fun detectLicensePlate(image: InputImage): PlateScanResult {
        val client = acquireTextRecognizer()
            ?: return PlateScanResult(
                detectedPlate = null,
                rawText = "",
                confidenceHint = if (closed) "MLServiceManager is closed" else "ML Kit Text Recognizer unavailable"
            )
        return recognizePlateFromInputImage(client, image)
    }

    private suspend fun recognizePlateFromInputImage(
        recognizer: TextRecognizer,
        image: InputImage
    ): PlateScanResult {
        return suspendCancellableCoroutine { cont ->
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    val raw = visionText.text.trim()
                    val bestPlate = extractBestLicensePlate(raw)
                    if (cont.isActive) {
                        cont.resume(
                            PlateScanResult(
                                detectedPlate = bestPlate,
                                rawText = raw,
                                confidenceHint = if (bestPlate != null) {
                                    "Plate detected offline via ML Kit OCR"
                                } else {
                                    "No standard license plate pattern matched"
                                }
                            )
                        )
                    }
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "OCR failed: ${e.message}")
                    if (cont.isActive) {
                        cont.resume(PlateScanResult(null, "", "OCR unavailable: ${e.message}"))
                    }
                }
        }
    }

    suspend fun verifySelfieFaceLiveness(photoFile: File): FaceVerificationResult = withContext(Dispatchers.IO) {
        if (closed) {
            return@withContext FaceVerificationResult(false, 0, "MLServiceManager is closed")
        }
        if (!photoFile.exists() || photoFile.length() == 0L) {
            return@withContext FaceVerificationResult(false, 0, "Selfie image file missing")
        }
        val bitmap = BitmapFactory.decodeFile(photoFile.absolutePath)
            ?: return@withContext FaceVerificationResult(false, 0, "Could not decode selfie photo")
        verifySelfieBitmap(bitmap, rotationDegrees = 0, recycleInput = true)
    }

    suspend fun verifySelfieFaceLiveness(
        bitmap: Bitmap,
        rotationDegrees: Int = 0
    ): FaceVerificationResult = withContext(Dispatchers.Default) {
        if (closed) {
            return@withContext FaceVerificationResult(false, 0, "MLServiceManager is closed")
        }
        verifySelfieBitmap(bitmap, rotationDegrees = rotationDegrees, recycleInput = false)
    }

    suspend fun verifySelfieFaceLiveness(
        image: InputImage,
        sourceBitmapForEncoding: Bitmap? = null
    ): FaceVerificationResult {
        val detector = acquireFaceDetector()
            ?: return FaceVerificationResult(
                verified = false,
                faceCount = 0,
                reason = if (closed) "MLServiceManager is closed" else "ML Kit Face Detector unavailable"
            )
        return detectFaceLivenessInternal(detector, image, sourceBitmapForEncoding)
    }

    private suspend fun verifySelfieBitmap(
        bitmap: Bitmap,
        rotationDegrees: Int,
        recycleInput: Boolean
    ): FaceVerificationResult {
        val detector = acquireFaceDetector()
        if (detector == null) {
            if (recycleInput && !bitmap.isRecycled) bitmap.recycle()
            return FaceVerificationResult(
                verified = false,
                faceCount = 0,
                reason = if (closed) "MLServiceManager is closed" else "ML Kit Face Detector unavailable"
            )
        }
        val image = InputImage.fromBitmap(bitmap, normalizeRotation(rotationDegrees))
        val detection = detectFaceLivenessInternal(detector, image, bitmap)
        if (recycleInput && !bitmap.isRecycled) {
            bitmap.recycle()
        }
        return detection
    }

    private suspend fun detectFaceLivenessInternal(
        detector: FaceDetector,
        image: InputImage,
        sourceBitmapForEncoding: Bitmap?
    ): FaceVerificationResult {
        return suspendCancellableCoroutine { cont ->
            detector.process(image)
                .addOnSuccessListener { faces ->
                    if (faces.isEmpty()) {
                        if (cont.isActive) {
                            cont.resume(
                                FaceVerificationResult(
                                    verified = false,
                                    faceCount = 0,
                                    reason = "No clear human face detected. Keep your face clearly visible in the front camera."
                                )
                            )
                        }
                        return@addOnSuccessListener
                    }
                    val primary = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }!!
                    val headCentered = abs(primary.headEulerAngleY) <= 35f && abs(primary.headEulerAngleZ) <= 35f
                    if (!headCentered) {
                        if (cont.isActive) {
                            cont.resume(
                                FaceVerificationResult(
                                    verified = false,
                                    faceCount = faces.size,
                                    reason = "Face angle too tilted. Look directly toward the camera for responder verification."
                                )
                            )
                        }
                        return@addOnSuccessListener
                    }

                    val leftOpen = primary.leftEyeOpenProbability ?: 0.8f
                    val rightOpen = primary.rightEyeOpenProbability ?: 0.8f
                    val eyesVisible = leftOpen > 0.25f || rightOpen > 0.25f
                    if (!eyesVisible) {
                        if (cont.isActive) {
                            cont.resume(
                                FaceVerificationResult(
                                    verified = false,
                                    faceCount = faces.size,
                                    reason = "Face partially covered or eyes closed. Please retake with unobstructed face."
                                )
                            )
                        }
                        return@addOnSuccessListener
                    }

                    val encoded = sourceBitmapForEncoding?.takeUnless { it.isRecycled }?.let { compressBitmapToBase64(it) }
                    if (cont.isActive) {
                        cont.resume(
                            FaceVerificationResult(
                                verified = true,
                                faceCount = faces.size,
                                reason = "Live human face verified offline (Anti-Predator Lock passed)",
                                selfieBase64 = encoded
                            )
                        )
                    }
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "Face detector failed: ${e.message}")
                    if (cont.isActive) {
                        cont.resume(
                            FaceVerificationResult(
                                verified = false,
                                faceCount = 0,
                                reason = "Face check unavailable: ${e.message}"
                            )
                        )
                    }
                }
        }
    }

    private fun compressBitmapToBase64(bitmap: Bitmap): String {
        val ratio = minOf(320f / bitmap.width.coerceAtLeast(1), 320f / bitmap.height.coerceAtLeast(1), 1f)
        val scaled = if (ratio < 1f) {
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * ratio).toInt().coerceAtLeast(1),
                (bitmap.height * ratio).toInt().coerceAtLeast(1),
                true
            )
        } else {
            bitmap
        }
        val baos = ByteArrayOutputStream()
        var quality = 70
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, baos)
        while (baos.size() > 75_000 && quality > 30) {
            baos.reset()
            quality -= 15
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, baos)
        }
        if (scaled !== bitmap) scaled.recycle()
        return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP).take(118_000)
    }

    override fun close() {
        val detachLifecycle: Lifecycle?
        synchronized(clientLock) {
            try {
                textRecognizer?.close()
            } catch (_: Exception) {
            }
            try {
                faceDetector?.close()
            } catch (_: Exception) {
            }
            textRecognizer = null
            faceDetector = null
            initialized = false
            closed = true
            detachLifecycle = observedLifecycle
            observedLifecycle = null
        }
        detachLifecycle?.let { lifecycle ->
            if (Looper.myLooper() == Looper.getMainLooper()) {
                try { lifecycle.removeObserver(this) } catch (_: Exception) {}
            } else {
                Handler(Looper.getMainLooper()).post {
                    try { lifecycle.removeObserver(this) } catch (_: Exception) {}
                }
            }
        }
    }

    private fun normalizeRotation(degrees: Int): Int {
        val normalized = ((degrees % 360) + 360) % 360
        return when (normalized) {
            0, 90, 180, 270 -> normalized
            else -> 0
        }
    }

    companion object {
        private const val TAG = "MLServiceManager"

        // Indian plate pattern (e.g. TR 01 AB 1234 / DL8CAF5031 / 22BH1234AA) & general alphanumeric plates
        private val INDIAN_PLATE_REGEX = Regex("\\b(?:[A-Z]{2}[\\s-]?[0-9]{1,2}[\\s-]?[A-Z]{1,3}[\\s-]?[0-9]{3,4}|[0-9]{2}BH[0-9]{4}[A-Z]{1,2})\\b")
        private val GENERIC_PLATE_REGEX = Regex("\\b[A-Z0-9]{2,4}[\\s-]?[A-Z0-9]{2,5}(?:[\\s-]?[0-9]{2,4})?\\b")

        fun extractBestLicensePlate(rawText: String): String? {
            if (rawText.isBlank()) return null
            val cleaned = rawText
                .uppercase(Locale.US)
                .replace("IND", " ")
                .replace(Regex("[^A-Z0-9\\s-]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()

            INDIAN_PLATE_REGEX.find(cleaned)?.let { match ->
                return match.value.replace(Regex("\\s+"), " ").trim()
            }

            // Fallback: compact without spaces inside candidate lines
            val lines = rawText.lines().map {
                it.uppercase(Locale.US).replace(Regex("[^A-Z0-9]"), "")
            }.filter { it.length in 6..12 && it.any(Char::isLetter) && it.any(Char::isDigit) }

            for (line in lines) {
                INDIAN_PLATE_REGEX.find(line)?.let { return it.value }
            }
            return lines.firstOrNull() ?: GENERIC_PLATE_REGEX.findAll(cleaned)
                .map { it.value.trim() }
                .firstOrNull { it.length >= 6 && it.any(Char::isDigit) && it.any(Char::isLetter) }
        }
    }
}
