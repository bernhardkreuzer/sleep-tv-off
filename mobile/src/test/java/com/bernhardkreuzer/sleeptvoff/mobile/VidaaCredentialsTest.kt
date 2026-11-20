package com.bernhardkreuzer.sleeptvoff.mobile

import com.bernhardkreuzer.sleeptvoff.mobile.VidaaCredentials.AuthMethod
import org.junit.Assert.assertEquals
import org.junit.Test

class VidaaCredentialsTest {

    /** Werte aus einem Mitschnitt der Original-App, dokumentiert in vidaa-control. */
    @Test
    fun bekannterMitschnitt() {
        val creds = VidaaCredentials.generate("56:b8:88:4e:f7:19", 1766974704L, AuthMethod.MODERN)
        assertEquals("56:b8:88:4e:f7:19\$his\$256DBF_vidaacommon_001", creds.clientId)
        assertEquals("his\$6239759786168176024", creds.username)
        assertEquals("C3BA44782E18ABF4892AC44D79A622D2", creds.password)
    }

    @Test
    fun aeltereVarianteOhneVerschleierteZeit() {
        val creds = VidaaCredentials.generate("56:b8:88:4e:f7:19", 1766974704L, AuthMethod.LEGACY)
        assertEquals("his\$1766974704", creds.username)
    }

    @Test
    fun protokollversionBestimmtVariante() {
        assertEquals(AuthMethod.MODERN, VidaaCredentials.methodFor(null))
        assertEquals(AuthMethod.MODERN, VidaaCredentials.methodFor(3290))
        assertEquals(AuthMethod.MIDDLE, VidaaCredentials.methodFor(3285))
        assertEquals(AuthMethod.LEGACY, VidaaCredentials.methodFor(2999))
    }

    @Test
    fun macOhneTrennerWirdFormatiert() {
        assertEquals("84:c8:a0:c0:ce:8f", VidaaCredentials.normalizeUuid("84c8a0c0ce8f"))
        assertEquals("84:C8:A0:C0:CE:8F", VidaaCredentials.normalizeUuid("84:C8:A0:C0:CE:8F"))
    }
}
