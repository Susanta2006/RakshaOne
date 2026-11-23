package com.example.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DualCameraCaptureResult(
    val incidentId: String,
    val frontPhotoPaths: List<String>,
    val rearPhotoPaths: List<String>,
    val videoPaths: List<String>,
    val frontVideoPath: String?,
    val rearVideoPath: String?
) {
    val allPhotoPaths: List<String> get() = frontPhotoPaths + rearPhotoPaths
    val primaryVideoPath: String? get() = rearVideoPath ?: frontVideoPath ?: videoPaths.firstOrNull()
}

/**
 * Automated Dual-Camera CameraX Emergency Evidence Manager.
 * Runs headlessly using an internal LifecycleOwner so it works from both Activity and Service/Repo contexts.
 * Captures BOTH Front Camera (attacker/snatcher face: snapshots + silent video) AND
 * Rear Camera (surroundings: snapshots + silent video) sequentially to work across 100% of Android hardware.
 */
class SosCameraVideoRecorder(private val context: Context) {

    private val tag = "SosCameraManager"
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _secondsRemaining = MutableStateFlow(0)
    val secondsRemaining: StateFlow<Int> = _secondsRemaining.asStateFlow()

    private val _lastVideoPath = MutableStateFlow<String?>(null)
    val lastVideoPath: StateFlow<String?> = _lastVideoPath.asStateFlow()

    private val _lastFrontVideoPath = MutableStateFlow<String?>(null)
    val lastFrontVideoPath: StateFlow<String?> = _lastFrontVideoPath.asStateFlow()

    private val _lastRearVideoPath = MutableStateFlow<String?>(null)
    val lastRearVideoPath: StateFlow<String?> = _lastRearVideoPath.asStateFlow()

    private val _capturedVideoPaths = MutableStateFlow<List<String>>(emptyList())
    val capturedVideoPaths: StateFlow<List<String>> = _capturedVideoPaths.asStateFlow()

    private val _frontPhotoPaths = MutableStateFlow<List<String>>(emptyList())
    val frontPhotoPaths: StateFlow<List<String>> = _frontPhotoPaths.asStateFlow()

    private val _rearPhotoPaths = MutableStateFlow<List<String>>(emptyList())
    val rearPhotoPaths: StateFlow<List<String>> = _rearPhotoPaths.asStateFlow()

    private val _capturedPhotoPaths = MutableStateFlow<List<String>>(emptyList())
    val capturedPhotoPaths: StateFlow<List<String>> = _capturedPhotoPaths.asStateFlow()

    private val _captureStatus = MutableStateFlow("Standby • Front+Rear auto camera ready")
    val captureStatus: StateFlow<String> = _captureStatus.asStateFlow()

    private var sequenceJob: Job? = null
    private var activeRecording: Recording? = null
    private var activeLifecycleOwner: HeadlessCameraLifecycleOwner? = null
    private var boundCameraProvider: ProcessCameraProvider? = null
    private var boundImageCapture: ImageCapture? = null

    fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun evidenceCacheDir(incidentId: String): File {
        val safeId = incidentId.replace(Regex("[^A-Za-z0-9_-]"), "_")
        return File(context.cacheDir, "emergency_evidence/$safeId").apply {
            if (!exists()) mkdirs()
        }
    }

    /**
     * Backward-compatible entrypoint used by legacy callers.
     */
    fun start30SecondSilentRecording(onVideoRecorded: (String?) -> Unit) {
        startAutomatedSosCapture(
            incidentId = "SOS-${System.currentTimeMillis() % 100000}",
            onPhotoCaptured = {},
            onVideoRecorded = onVideoRecorded
        )
    }

    /**
     * Automated Dual-Camera Capture upon SOS / Anti-Snatch activation:
     * Phase 1 (Front Camera): Captures 2 rapid face snapshots + silent MP4 video.
     * Phase 2 (Rear Camera): Captures 2 rapid surrounding snapshots + silent MP4 video.
     * Supports fast Anti-Snatch timings (e.g. 5s Front + 6s Rear) or full SOS timings (8s Front + 12s Rear).
     */
    fun startAutomatedSosCapture(
        incidentId: String,
        frontDurationSec: Int = 8,
        rearDurationSec: Int = 12,
        onPhotoCaptured: (File) -> Unit = {},
        onVideoRecorded: (String?) -> Unit = {},
        onDualSequenceCompleted: ((DualCameraCaptureResult) -> Unit)? = null
    ) {
        if (!hasCameraPermission()) {
            _captureStatus.value = "Camera permission not granted — grant Camera access for Front+Rear evidence"
            _isRecording.value = false
            onVideoRecorded(null)
            onDualSequenceCompleted?.invoke(
                DualCameraCaptureResult(incidentId, emptyList(), emptyList(), emptyList(), null, null)
            )
            return
        }

        if (_isRecording.value) {
            Log.w(tag, "Automated Dual-Camera capture already running.")
            return
        }

        _isRecording.value = true
        _secondsRemaining.value = frontDurationSec + rearDurationSec
        _frontPhotoPaths.value = emptyList()
        _rearPhotoPaths.value = emptyList()
        _capturedPhotoPaths.value = emptyList()
        _capturedVideoPaths.value = emptyList()
        _lastFrontVideoPath.value = null
        _lastRearVideoPath.value = null
        _captureStatus.value = "Initializing Front+Rear CameraX capture..."

        val targetDir = evidenceCacheDir(incidentId)

        sequenceJob?.cancel()
        sequenceJob = mainScope.launch {
            try {
                val cameraProvider = awaitCameraProvider()
                boundCameraProvider = cameraProvider

                val frontPhotos = mutableListOf<String>()
                val rearPhotos = mutableListOf<String>()
                val videos = mutableListOf<String>()
                var frontVidPath: String? = null
                var rearVidPath: String? = null

                val hasFront = safeHasCamera(cameraProvider, CameraSelector.DEFAULT_FRONT_CAMERA)
                val hasBack = safeHasCamera(cameraProvider, CameraSelector.DEFAULT_BACK_CAMERA)

                if (!hasFront && !hasBack) {
                    _captureStatus.value = "No camera hardware detected on device"
                    _isRecording.value = false
                    _secondsRemaining.value = 0
                    onVideoRecorded(null)
                    onDualSequenceCompleted?.invoke(
                        DualCameraCaptureResult(incidentId, emptyList(), emptyList(), emptyList(), null, null)
                    )
                    return@launch
                }

                // Stage 1: Front Camera (Attacker / Snatcher Face)
                if (hasFront && _isRecording.value) {
                    val frontResult = captureSingleLensStage(
                        cameraProvider = cameraProvider,
                        cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA,
                        lensTag = "FRONT",
                        targetDir = targetDir,
                        incidentId = incidentId,
                        videoDurationSec = frontDurationSec,
                        countdownOffsetSec = if (hasBack) rearDurationSec else 0,
                        photoCount = 2,
                        onPhotoSaved = { file ->
                            frontPhotos.add(file.absolutePath)
                            _frontPhotoPaths.value = frontPhotos.toList()
                            _capturedPhotoPaths.value = frontPhotos + rearPhotos
                            onPhotoCaptured(file)
                        }
                    )
                    if (frontResult != null) {
                        frontVidPath = frontResult
                        _lastFrontVideoPath.value = frontResult
                        _lastVideoPath.value = frontResult
                        videos.add(frontResult)
                        _capturedVideoPaths.value = videos.toList()
                    }
                    delay(250L)
                }

                // Stage 2: Rear Camera (Surroundings / Street Scene)
                if (hasBack && _isRecording.value) {
                    val rearResult = captureSingleLensStage(
                        cameraProvider = cameraProvider,
                        cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA,
                        lensTag = "REAR",
                        targetDir = targetDir,
                        incidentId = incidentId,
                        videoDurationSec = rearDurationSec,
                        countdownOffsetSec = 0,
                        photoCount = 2,
                        onPhotoSaved = { file ->
                            rearPhotos.add(file.absolutePath)
                            _rearPhotoPaths.value = rearPhotos.toList()
                            _capturedPhotoPaths.value = frontPhotos + rearPhotos
                            onPhotoCaptured(file)
                        }
                    )
                    if (rearResult != null) {
                        rearVidPath = rearResult
                        _lastRearVideoPath.value = rearResult
                        _lastVideoPath.value = rearResult
                        videos.add(rearResult)
                        _capturedVideoPaths.value = videos.toList()
                    }
                }

                _isRecording.value = false
                _secondsRemaining.value = 0
                val primaryVideo = rearVidPath ?: frontVidPath ?: videos.firstOrNull()
                _lastVideoPath.value = primaryVideo

                val totalPhotos = frontPhotos.size + rearPhotos.size
                _captureStatus.value = if (videos.isNotEmpty() || totalPhotos > 0) {
                    "Dual Camera Complete: ${videos.size} video(s), $totalPhotos photo(s) (Front:${frontPhotos.size}, Rear:${rearPhotos.size})"
                } else {
                    "Camera capture finished with no files recorded"
                }

                onVideoRecorded(primaryVideo)
                onDualSequenceCompleted?.invoke(
                    DualCameraCaptureResult(
                        incidentId = incidentId,
                        frontPhotoPaths = frontPhotos.toList(),
                        rearPhotoPaths = rearPhotos.toList(),
                        videoPaths = videos.toList(),
                        frontVideoPath = frontVidPath,
                        rearVideoPath = rearVidPath
                    )
                )
            } catch (e: Exception) {
                Log.e(tag, "Dual-camera sequence failed: ${e.message}", e)
                _isRecording.value = false
                _secondsRemaining.value = 0
                _captureStatus.value = "Camera error: ${e.localizedMessage ?: "Hardware busy"}"
                releaseCameraBinding()
                onVideoRecorded(_lastVideoPath.value)
                onDualSequenceCompleted?.invoke(
                    DualCameraCaptureResult(
                        incidentId = incidentId,
                        frontPhotoPaths = _frontPhotoPaths.value,
                        rearPhotoPaths = _rearPhotoPaths.value,
                        videoPaths = _capturedVideoPaths.value,
                        frontVideoPath = _lastFrontVideoPath.value,
                        rearVideoPath = _lastRearVideoPath.value
                    )
                )
            }
        }
    }

    private suspend fun awaitCameraProvider(): ProcessCameraProvider {
        val deferred = CompletableDeferred<ProcessCameraProvider>()
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                deferred.complete(future.get())
            } catch (e: Exception) {
                deferred.completeExceptionally(e)
            }
        }, ContextCompat.getMainExecutor(context))
        return deferred.await()
    }

    private fun safeHasCamera(provider: ProcessCameraProvider, selector: CameraSelector): Boolean {
        return try {
            provider.hasCamera(selector)
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun captureSingleLensStage(
        cameraProvider: ProcessCameraProvider,
        cameraSelector: CameraSelector,
        lensTag: String,
        targetDir: File,
        incidentId: String,
        videoDurationSec: Int,
        countdownOffsetSec: Int,
        photoCount: Int,
        onPhotoSaved: (File) -> Unit
    ): String? {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val videoFile = File(targetDir, "${lensTag}_VID_${timeStamp}_$incidentId.mp4")

        val imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setFlashMode(ImageCapture.FLASH_MODE_OFF)
            .build()
        boundImageCapture = imageCapture

        val qualitySelector = QualitySelector.fromOrderedList(
            listOf(Quality.SD, Quality.LOWEST, Quality.HD),
            FallbackStrategy.lowerQualityOrHigherThan(Quality.LOWEST)
        )
        val recorder = Recorder.Builder()
            .setQualitySelector(qualitySelector)
            .setTargetVideoEncodingBitRate(450_000)
            .build()
        val videoCapture = VideoCapture.withOutput(recorder)

        cameraProvider.unbindAll()
        activeLifecycleOwner?.markDestroyed()
        val lifecycleOwner = HeadlessCameraLifecycleOwner().apply { markStarted() }
        activeLifecycleOwner = lifecycleOwner

        var canSnapAlongsideVideo = true
        try {
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                imageCapture,
                videoCapture
            )
        } catch (bindEx: IllegalArgumentException) {
            Log.w(tag, "$lensTag rejected concurrent Image+Video bind; trying ImageCapture first then VideoCapture: ${bindEx.message}")
            canSnapAlongsideVideo = false
            boundImageCapture = null
            // Quick pre-video snapshots first
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, imageCapture)
                delay(350L)
                for (i in 1..photoCount) {
                    val photoFile = File(targetDir, "${lensTag}_IMG_${timeStamp}_$i.jpg")
                    val saved = awaitSingleSnapshot(imageCapture, photoFile)
                    if (saved != null) onPhotoSaved(saved)
                }
            } catch (_: Exception) {}

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, videoCapture)
        }

        val finalizeDeferred = CompletableDeferred<String?>()
        val outputOptions = FileOutputOptions.Builder(videoFile).build()
        _captureStatus.value = "Recording $lensTag camera video & photos..."

        activeRecording = recorder.prepareRecording(context, outputOptions)
            .start(ContextCompat.getMainExecutor(context)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        _captureStatus.value = "Capturing $lensTag face/scene (${videoDurationSec}s)..."
                    }
                    is VideoRecordEvent.Finalize -> {
                        activeRecording = null
                        if (!event.hasError() && videoFile.exists() && videoFile.length() > 0L) {
                            Log.i(tag, "$lensTag CameraX video saved: ${videoFile.absolutePath}")
                            finalizeDeferred.complete(videoFile.absolutePath)
                        } else {
                            Log.e(tag, "$lensTag CameraX finalize error=${event.error}")
                            finalizeDeferred.complete(null)
                        }
                    }
                }
            }

        // Capture concurrent snapshots while recording if concurrent bind succeeded
        val snapJob = if (canSnapAlongsideVideo) {
            mainScope.launch {
                val delays = listOf(450L, 1800L).take(photoCount)
                delays.forEachIndexed { idx, waitMs ->
                    delay(waitMs)
                    if (!_isRecording.value) return@launch
                    val photoFile = File(targetDir, "${lensTag}_IMG_${timeStamp}_${idx + 1}.jpg")
                    val saved = awaitSingleSnapshot(imageCapture, photoFile)
                    if (saved != null) onPhotoSaved(saved)
                }
            }
        } else null

        for (sec in videoDurationSec downTo 1) {
            if (!_isRecording.value) break
            _secondsRemaining.value = sec + countdownOffsetSec
            delay(1000L)
        }

        snapJob?.cancel()
        try {
            activeRecording?.stop()
        } catch (_: Exception) {}

        val savedVideoPath = withTimeoutOrNull(4500L) {
            finalizeDeferred.await()
        }

        try {
            cameraProvider.unbindAll()
        } catch (_: Exception) {}
        boundImageCapture = null
        lifecycleOwner.markDestroyed()
        activeLifecycleOwner = null

        return savedVideoPath
    }

    /**
     * Captures BOTH Front + Rear immediate snapshots into local cache.
     */
    fun captureSingleSnapshot(
        incidentId: String,
        onPhotoCaptured: (File?) -> Unit
    ) {
        if (!hasCameraPermission()) {
            _captureStatus.value = "Camera permission required to take snapshot"
            onPhotoCaptured(null)
            return
        }

        val activeCapture = boundImageCapture
        val targetDir = evidenceCacheDir(incidentId)
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())

        if (activeCapture != null && _isRecording.value) {
            val photoFile = File(targetDir, "SNAP_IMG_$timeStamp.jpg")
            captureSnapshotInternal(activeCapture, photoFile) { saved ->
                if (saved != null) {
                    _capturedPhotoPaths.value = _capturedPhotoPaths.value + saved.absolutePath
                }
                onPhotoCaptured(saved)
            }
            return
        }

        mainScope.launch {
            try {
                val cameraProvider = awaitCameraProvider()
                val newFiles = mutableListOf<File>()
                val lenses = buildList {
                    if (safeHasCamera(cameraProvider, CameraSelector.DEFAULT_FRONT_CAMERA)) {
                        add("FRONT" to CameraSelector.DEFAULT_FRONT_CAMERA)
                    }
                    if (safeHasCamera(cameraProvider, CameraSelector.DEFAULT_BACK_CAMERA)) {
                        add("REAR" to CameraSelector.DEFAULT_BACK_CAMERA)
                    }
                }
                if (lenses.isEmpty()) {
                    onPhotoCaptured(null)
                    return@launch
                }

                for ((lensName, selector) in lenses) {
                    val tempLifecycle = HeadlessCameraLifecycleOwner().apply { markStarted() }
                    try {
                        val imageCapture = ImageCapture.Builder()
                            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                            .setFlashMode(ImageCapture.FLASH_MODE_OFF)
                            .build()
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(tempLifecycle, selector, imageCapture)
                        delay(350L)
                        val file = File(targetDir, "${lensName}_IMG_$timeStamp.jpg")
                        val saved = awaitSingleSnapshot(imageCapture, file)
                        if (saved != null) {
                            newFiles.add(saved)
                            if (lensName == "FRONT") {
                                _frontPhotoPaths.value = _frontPhotoPaths.value + saved.absolutePath
                            } else {
                                _rearPhotoPaths.value = _rearPhotoPaths.value + saved.absolutePath
                            }
                            _capturedPhotoPaths.value = _capturedPhotoPaths.value + saved.absolutePath
                        }
                    } finally {
                        try { cameraProvider.unbindAll() } catch (_: Exception) {}
                        tempLifecycle.markDestroyed()
                    }
                }

                if (newFiles.isNotEmpty()) {
                    _captureStatus.value = "Captured ${newFiles.size} Front+Rear photo(s) into cache"
                    onPhotoCaptured(newFiles.last())
                } else {
                    onPhotoCaptured(null)
                }
            } catch (e: Exception) {
                Log.e(tag, "Dual snapshot capture failed: ${e.message}", e)
                onPhotoCaptured(null)
            }
        }
    }

    private suspend fun awaitSingleSnapshot(imageCapture: ImageCapture, photoFile: File): File? {
        val deferred = CompletableDeferred<File?>()
        captureSnapshotInternal(imageCapture, photoFile) { saved ->
            deferred.complete(saved)
        }
        return withTimeoutOrNull(4500L) {
            deferred.await()
        }
    }

    private fun captureSnapshotInternal(
        imageCapture: ImageCapture,
        photoFile: File,
        onDone: (File?) -> Unit
    ) {
        try {
            val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()
            imageCapture.takePicture(
                outputOptions,
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        if (photoFile.exists() && photoFile.length() > 0L) {
                            Log.i(tag, "SOS snapshot captured: ${photoFile.absolutePath}")
                            onDone(photoFile)
                        } else {
                            onDone(null)
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        Log.e(tag, "SOS snapshot capture failed: ${exception.message}", exception)
                        onDone(null)
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(tag, "Exception invoking takePicture: ${e.message}")
            onDone(null)
        }
    }

    fun stopRecording() {
        mainScope.launch {
            try {
                _isRecording.value = false
                _secondsRemaining.value = 0
                activeRecording?.stop()
                activeRecording = null
                sequenceJob?.cancel()
                sequenceJob = null
                releaseCameraBinding()
            } catch (e: Exception) {
                Log.e(tag, "Error stopping CameraX recording: ${e.message}")
                _isRecording.value = false
                _secondsRemaining.value = 0
                releaseCameraBinding()
            }
        }
    }

    private fun releaseCameraBinding() {
        mainScope.launch {
            try {
                boundCameraProvider?.unbindAll()
            } catch (_: Exception) {}
            boundImageCapture = null
            activeLifecycleOwner?.markDestroyed()
            activeLifecycleOwner = null
        }
    }

    /**
     * Clears captured video/photo state and removes the incident cache directory
     * once the victim confirms the incident is resolved.
     */
    fun clearIncidentMediaState(incidentId: String) {
        val safeId = incidentId.replace(Regex("[^A-Za-z0-9_-]"), "_")
        fun belongsToIncident(path: String): Boolean =
            path.contains(incidentId) || path.contains(safeId)

        (_frontPhotoPaths.value + _rearPhotoPaths.value + _capturedPhotoPaths.value + _capturedVideoPaths.value)
            .filter { belongsToIncident(it) }
            .forEach { path ->
                try {
                    File(path).delete()
                } catch (_: Exception) {
                }
            }

        try {
            File(context.cacheDir, "emergency_evidence/$safeId").deleteRecursively()
        } catch (_: Exception) {
        }

        _frontPhotoPaths.value = _frontPhotoPaths.value.filterNot(::belongsToIncident)
        _rearPhotoPaths.value = _rearPhotoPaths.value.filterNot(::belongsToIncident)
        _capturedPhotoPaths.value = _capturedPhotoPaths.value.filterNot(::belongsToIncident)
        _capturedVideoPaths.value = _capturedVideoPaths.value.filterNot(::belongsToIncident)
        if (_lastFrontVideoPath.value?.let(::belongsToIncident) == true) _lastFrontVideoPath.value = null
        if (_lastRearVideoPath.value?.let(::belongsToIncident) == true) _lastRearVideoPath.value = null
        if (_lastVideoPath.value?.let(::belongsToIncident) == true) _lastVideoPath.value = null
        _captureStatus.value = "Evidence vault purged after victim resolution"
    }

    private class HeadlessCameraLifecycleOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this)

        override val lifecycle: Lifecycle
            get() = registry

        fun markStarted() {
            registry.currentState = Lifecycle.State.CREATED
            registry.currentState = Lifecycle.State.STARTED
            registry.currentState = Lifecycle.State.RESUMED
        }

        fun markDestroyed() {
            try {
                if (registry.currentState != Lifecycle.State.DESTROYED) {
                    registry.currentState = Lifecycle.State.DESTROYED
                }
            } catch (_: Exception) {}
        }
    }
}
