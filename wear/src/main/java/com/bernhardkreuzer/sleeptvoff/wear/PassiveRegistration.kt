package com.bernhardkreuzer.sleeptvoff.wear

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.PassiveListenerConfig
import androidx.health.services.client.data.UserActivityState
import java.util.concurrent.TimeUnit

/** Meldet [SleepPassiveService] bei den Health Services an. */
object PassiveRegistration {
    const val OK = "Schlaferkennung aktiv"

    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) ==
            PackageManager.PERMISSION_GRANTED

    /** Blockiert, daher nur aus einem Hintergrund-Thread aufrufen. */
    fun registerBlocking(context: Context): String {
        if (!hasPermission(context)) return "Berechtigung fehlt"

        val client = HealthServices.getClient(context.applicationContext).passiveMonitoringClient

        val capabilities = client.getCapabilitiesAsync().get(20, TimeUnit.SECONDS)
        if (UserActivityState.USER_ACTIVITY_ASLEEP !in capabilities.supportedUserActivityStates) {
            return "Diese Watch meldet keinen Schlafzustand"
        }

        val config = PassiveListenerConfig.builder()
            .setShouldUserActivityInfoBeRequested(true)
            .build()
        client.setPassiveListenerServiceAsync(SleepPassiveService::class.java, config)
            .get(30, TimeUnit.SECONDS)
        return OK
    }
}
