package com.bernhardkreuzer.sleeptvoff.mobile

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager

/** Führt das Ausschalten aus und schreibt das Ergebnis ins Protokoll. Blockiert. */
object TvOff {
    fun execute(context: Context, reason: String): SamsungTv.Result {
        val result = SamsungTv.turnOff(Prefs.tvIp(context), Prefs.tvToken(context))
        // Manche Modelle vergeben bei jeder Verbindung ein neues Token.
        if (result.ok && !result.token.isNullOrBlank() && result.token != Prefs.tvToken(context)) {
            Prefs.setPairing(context, result.token)
        }
        Prefs.log(context, "$reason: ${result.message}")
        return result
    }
}

/** Plant das Ausschalten per AlarmManager, damit es auch im Doze-Modus pünktlich passiert. */
object TimerScheduler {

    fun schedule(context: Context, atMs: Long) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val intent = pendingIntent(context)
        val exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()
        if (exactAllowed) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, intent)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, intent)
        }
        Prefs.setPendingAt(context, atMs)
    }

    /** @return true, wenn tatsächlich ein Timer lief. */
    fun cancel(context: Context): Boolean {
        val wasPending = Prefs.pendingAt(context) > System.currentTimeMillis()
        context.getSystemService(AlarmManager::class.java).cancel(pendingIntent(context))
        Prefs.setPendingAt(context, 0L)
        return wasPending
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, TvOffReceiver::class.java)
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

/** Der Timer ist abgelaufen: Fernseher ausschalten. */
class TvOffReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()
        val wakeLock = app.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sleeptvoff:tvoff")
        wakeLock.acquire(20_000L)

        Thread {
            try {
                Prefs.setPendingAt(app, 0L)
                if (Prefs.enabled(app)) {
                    TvOff.execute(app, "Timer abgelaufen")
                } else {
                    Prefs.log(app, "Timer abgelaufen, Automatik ist aber aus")
                }
            } catch (t: Throwable) {
                Prefs.log(app, "Fehler beim Ausschalten: ${t.message ?: t.javaClass.simpleName}")
            } finally {
                if (wakeLock.isHeld) wakeLock.release()
                pending.finish()
            }
        }.start()
    }
}
