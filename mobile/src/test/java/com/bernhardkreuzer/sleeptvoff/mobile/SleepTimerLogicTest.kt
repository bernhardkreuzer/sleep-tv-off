package com.bernhardkreuzer.sleeptvoff.mobile

import com.bernhardkreuzer.sleeptvoff.mobile.SleepTimerLogic.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

class SleepTimerLogicTest {
    private val min = 60_000L
    private val now = 1_800_000_000_000L

    @Test
    fun meldungSofort_timerLaeuftVolleZeit() {
        assertEquals(Decision.Schedule(now + 20 * min), SleepTimerLogic.decide(now, now, 20))
    }

    @Test
    fun meldungVerspaetet_restzeitWirdGeplant() {
        // Vor 12 Minuten eingeschlafen, 20 Minuten Timer: noch 8 Minuten.
        assertEquals(Decision.Schedule(now + 8 * min), SleepTimerLogic.decide(now - 12 * min, now, 20))
    }

    @Test
    fun timerSchonAbgelaufen_sofortAus() {
        assertEquals(Decision.FireNow, SleepTimerLogic.decide(now - 25 * min, now, 20))
    }

    @Test
    fun genauAmLimit_nochAus() {
        assertEquals(Decision.FireNow, SleepTimerLogic.decide(now - 80 * min, now, 20))
    }

    @Test
    fun vielZuSpaet_ignorieren() {
        // Meldung kommt erst am Morgen an.
        assertEquals(Decision.TooLate, SleepTimerLogic.decide(now - 7 * 60 * min, now, 20))
    }

    @Test
    fun watchUhrGehtVor_nieLaengerAlsVerzoegerung() {
        assertEquals(Decision.Schedule(now + 20 * min), SleepTimerLogic.decide(now + 5 * min, now, 20))
    }
}
