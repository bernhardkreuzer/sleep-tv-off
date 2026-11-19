package com.bernhardkreuzer.sleeptvoff.mobile

/**
 * Reine Rechenlogik ohne Android-Abhängigkeiten (siehe SleepTimerLogicTest).
 *
 * Die Watch meldet das Einschlafen oft erst Minuten später. Der Timer zählt
 * deshalb ab dem tatsächlichen Einschlafzeitpunkt und nicht ab dem Eintreffen
 * der Meldung.
 */
object SleepTimerLogic {

    /** Kommt die Meldung so spät, dass der Timer schon länger als das abgelaufen ist, passiert nichts. */
    const val MAX_LATE_MIN = 60

    sealed class Decision {
        /** Timer läuft noch: zu diesem Zeitpunkt ausschalten. */
        data class Schedule(val atMs: Long) : Decision()

        /** Timer ist bereits abgelaufen: sofort ausschalten. */
        object FireNow : Decision()

        /** Meldung kam viel zu spät (z. B. erst am Morgen): ignorieren. */
        object TooLate : Decision()
    }

    fun decide(
        sleepStartMs: Long,
        nowMs: Long,
        delayMin: Int,
        maxLateMin: Int = MAX_LATE_MIN
    ): Decision {
        val delayMs = delayMin * 60_000L
        // Falls die Uhr der Watch vorgeht, nie später als "jetzt + Verzögerung".
        val fireAt = minOf(sleepStartMs + delayMs, nowMs + delayMs)
        return when {
            fireAt > nowMs -> Decision.Schedule(fireAt)
            nowMs - fireAt <= maxLateMin * 60_000L -> Decision.FireNow
            else -> Decision.TooLate
        }
    }
}
