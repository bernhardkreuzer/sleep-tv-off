package com.bernhardkreuzer.sleeptvoff.mobile

import com.bernhardkreuzer.sleeptvoff.mobile.SleepTimerLogic.Decision
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService

/**
 * Empfängt den Schlafzustand von der Watch und startet bzw. stoppt den Timer.
 * onDataChanged läuft bereits in einem Hintergrund-Thread.
 */
class SleepListenerService : WearableListenerService() {

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        for (event in dataEvents) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            val item = event.dataItem
            if (item.uri.path != PATH) continue

            val map = DataMapItem.fromDataItem(item).dataMap
            val state = map.getString("state") ?: continue
            val changedAt = map.getLong("changedAt")
            val test = map.getBoolean("test", false)
            handle(state, changedAt, test)
        }
    }

    private fun handle(state: String, changedAt: Long, test: Boolean) {
        val tag = if (test) " (Test)" else ""
        when (state) {
            STATE_ASLEEP -> onAsleep(changedAt, tag)
            STATE_AWAKE -> {
                if (SleepTimerLogic.isStaleAwake(changedAt, Prefs.lastSleepStart(this))) return
                if (TimerScheduler.cancel(this)) {
                    Prefs.log(this, "Wieder wach$tag: Timer abgebrochen")
                }
            }
        }
    }

    private fun onAsleep(sleepStart: Long, tag: String) {
        // Die Health Services können denselben Zustand mehrfach melden.
        if (sleepStart == Prefs.lastSleepStart(this)) return
        Prefs.setLastSleepStart(this, sleepStart)

        val since = "Eingeschlafen um ${Prefs.clock(sleepStart)}$tag"
        if (!Prefs.enabled(this)) {
            Prefs.log(this, "$since: Automatik ist aus")
            return
        }

        val delay = Prefs.delayMin(this)
        when (val decision = SleepTimerLogic.decide(sleepStart, System.currentTimeMillis(), delay)) {
            is Decision.Schedule -> {
                TimerScheduler.schedule(this, decision.atMs)
                Prefs.log(this, "$since: TV aus um ${Prefs.clock(decision.atMs)}")
            }
            Decision.FireNow -> {
                TimerScheduler.cancel(this)
                TvOff.execute(this, "$since, $delay Min. schon vorbei")
            }
            Decision.TooLate -> {
                Prefs.log(this, "$since: Meldung kam zu spät, nichts unternommen")
            }
        }
    }

    private companion object {
        // Muss mit PhoneSync im Watch-Modul übereinstimmen.
        const val PATH = "/sleep/state"
        const val STATE_ASLEEP = "asleep"
        const val STATE_AWAKE = "awake"
    }
}
