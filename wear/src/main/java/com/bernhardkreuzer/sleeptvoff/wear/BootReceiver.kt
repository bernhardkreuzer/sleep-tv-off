package com.bernhardkreuzer.sleeptvoff.wear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters

/**
 * Nach einem Neustart der Watch geht die Registrierung verloren.
 * Die Anmeldung kann laut Google mehr als 10 Sekunden dauern, deshalb läuft
 * sie über den WorkManager und nicht direkt im BroadcastReceiver.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        WorkManager.getInstance(context)
            .enqueue(OneTimeWorkRequest.Builder(RegisterWorker::class.java).build())
    }
}

class RegisterWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        return try {
            val status = PassiveRegistration.registerBlocking(applicationContext)
            WearStatus.setRegistration(applicationContext, status)
            Result.success()
        } catch (e: Exception) {
            WearStatus.setRegistration(applicationContext, "Fehler: ${e.message}")
            Result.retry()
        }
    }
}
