package com.example

import android.app.KeyguardManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.example.data.repository.SafetyRepository
import com.example.service.FakeIncomingCallService
import com.example.ui.components.SystemPhoneCallScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.util.FakeCallState

/**
 * Dedicated full-screen Android System Phone Call screen launched by [FakeIncomingCallService].
 * Works both on unlocked screens and directly over the lock screen (`showWhenLocked` / `turnScreenOn`)
 * when triggered either manually or proactively by AI.
 */
class FakeIncomingCallActivity : ComponentActivity() {

    private lateinit var repository: SafetyRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        configureLockscreenVisibility()

        repository = SafetyRepository.getInstance(applicationContext)

        setContent {
            MyApplicationTheme {
                val callState by repository.fakeCallManager.callState.collectAsState()
                val config by repository.fakeCallManager.config.collectAsState()
                val durationSeconds by repository.fakeCallManager.callDurationSeconds.collectAsState()
                val isAudioRecording by repository.audioRecorder.isRecording.collectAsState()
                val isAudioUploading by FakeIncomingCallService.isAudioUploading.collectAsState()

                LaunchedEffect(callState) {
                    if (callState == FakeCallState.IDLE) {
                        finish()
                    }
                }

                SystemPhoneCallScreen(
                    callState = callState,
                    config = config,
                    durationSeconds = durationSeconds,
                    isAudioRecording = isAudioRecording,
                    isAudioUploading = isAudioUploading,
                    onAnswer = { FakeIncomingCallService.answerCall(applicationContext) },
                    onDeclineOrEnd = {
                        FakeIncomingCallService.endOrDeclineCall(applicationContext)
                        finish()
                    },
                    onToggleHold = { FakeIncomingCallService.toggleHold(applicationContext) },
                    onStartRecordNow = { FakeIncomingCallService.startAudioCaptureNow(applicationContext) },
                    onCircleAlertNow = { FakeIncomingCallService.dispatchCircleAlertNow(applicationContext) }
                )
            }
        }
    }

    private fun configureLockscreenVisibility() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val km = getSystemService(KeyguardManager::class.java)
            runCatching { km?.requestDismissKeyguard(this, null) }
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
    }
}
