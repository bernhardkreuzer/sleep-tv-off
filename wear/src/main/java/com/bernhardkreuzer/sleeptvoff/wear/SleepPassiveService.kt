package com.bernhardkreuzer.sleeptvoff.wear

import androidx.health.services.client.PassiveListenerService
import androidx.health.services.client.data.UserActivityInfo
import androidx.health.services.client.data.UserActivityState

/**
 * Empfängt die Zustandswechsel der Health Services (schläft / wach / Training).
 *
 * Achtung: Die Watch liefert diese Ereignisse gebündelt und teils verzögert.
 * stateChangeTime ist der tatsächliche Zeitpunkt des Wechsels und kann daher
 * in der Vergangenheit liegen. Das Handy rechnet den Timer ab diesem Zeitpunkt.
 */
class SleepPassiveService : PassiveListenerService() {

    override fun onUserActivityInfoReceived(info: UserActivityInfo) {
        val changedAt = info.stateChangeTime.toEpochMilli()
        val state = when (info.userActivityState) {
            UserActivityState.USER_ACTIVITY_ASLEEP -> PhoneSync.STATE_ASLEEP
            UserActivityState.USER_ACTIVITY_PASSIVE,
            UserActivityState.USER_ACTIVITY_EXERCISE -> PhoneSync.STATE_AWAKE
            else -> null // UNKNOWN, z. B. Watch abgelegt: nichts tun
        }

        val label = when (state) {
            PhoneSync.STATE_ASLEEP -> "Eingeschlafen"
            PhoneSync.STATE_AWAKE -> "Wach"
            else -> "Unbekannt"
        }
        WearStatus.setLastEvent(this, "$label (${WearStatus.clock(changedAt)})")

        if (state != null) {
            PhoneSync.send(this, state, changedAt)
        }
    }
}
