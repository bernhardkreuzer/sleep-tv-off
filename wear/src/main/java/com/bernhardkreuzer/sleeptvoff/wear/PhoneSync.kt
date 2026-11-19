package com.bernhardkreuzer.sleeptvoff.wear

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable

/**
 * Schickt den Schlafzustand ans Handy.
 *
 * Bewusst als DataItem und nicht als Message: Ein DataItem wird nachgeliefert,
 * falls das Handy im Moment des Ereignisses kurz nicht verbunden ist.
 */
object PhoneSync {
    const val PATH = "/sleep/state"
    const val STATE_ASLEEP = "asleep"
    const val STATE_AWAKE = "awake"

    fun send(context: Context, state: String, changedAtMs: Long, test: Boolean = false): Task<DataItem> {
        val request = PutDataMapRequest.create(PATH)
        request.dataMap.putString("state", state)
        request.dataMap.putLong("changedAt", changedAtMs)
        request.dataMap.putBoolean("test", test)
        // sentAt sorgt dafür, dass sich das DataItem immer ändert und das Handy
        // garantiert ein onDataChanged bekommt.
        request.dataMap.putLong("sentAt", System.currentTimeMillis())
        val put = request.asPutDataRequest().setUrgent()
        return Wearable.getDataClient(context.applicationContext).putDataItem(put)
    }
}
