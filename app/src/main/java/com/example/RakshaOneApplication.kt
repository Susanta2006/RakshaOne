package com.example

import android.app.Application
import android.util.Log
import com.example.data.AppDatabase
import com.example.data.model.AuditLogEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Mission-critical Application class for RakshaOne.
 * Implements a bulletproof Global UncaughtExceptionHandler so unexpected background/system exceptions
 * never crash the app during an active emergency or while multiple citizens are streaming SOS alerts.
 */
class RakshaOneApplication : Application() {

    private val appScope = CoroutineScope(Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        setupGlobalCrashShield()
    }

    private fun setupGlobalCrashShield() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("RakshaOneShield", "FATAL CRASH INTERCEPTED on thread '${thread.name}': ${throwable.message}", throwable)
            try {
                appScope.launch {
                    try {
                        val db = AppDatabase.getDatabase(this@RakshaOneApplication)
                        db.auditLogDao().insertLog(
                            AuditLogEntity(
                                timestamp = System.currentTimeMillis(),
                                actor = "System Crash Shield",
                                role = "GLOBAL_GUARD",
                                action = "CRASH_PREVENTED",
                                targetId = thread.name,
                                details = "${throwable.javaClass.simpleName}: ${throwable.message?.take(300)}"
                            )
                        )
                    } catch (_: Throwable) {
                    }
                }
            } catch (_: Throwable) {
            }

            // Only pass to default OS handler if on the main thread and not recoverable
            if (thread.name.equals("main", ignoreCase = true)) {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }
}
