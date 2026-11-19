package com.bernhardkreuzer.sleeptvoff.wear

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Kleiner Statusspeicher, damit die Watch-Oberfläche etwas anzeigen kann. */
object WearStatus {
    private const val FILE = "sleeptvoff_wear"

    private fun sp(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun registration(context: Context): String =
        sp(context).getString("registration", null) ?: "Noch nicht registriert"

    fun setRegistration(context: Context, text: String) {
        sp(context).edit().putString("registration", text).apply()
    }

    fun lastEvent(context: Context): String =
        sp(context).getString("lastEvent", null) ?: "Noch kein Ereignis"

    fun setLastEvent(context: Context, text: String) {
        sp(context).edit().putString("lastEvent", text).apply()
    }

    fun clock(timeMs: Long): String =
        SimpleDateFormat("dd.MM. HH:mm", Locale.GERMANY).format(Date(timeMs))
}
