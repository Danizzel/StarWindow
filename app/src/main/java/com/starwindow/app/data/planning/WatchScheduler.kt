package com.starwindow.app.data.planning

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.getSystemService
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Der tägliche Blick auf die Merkliste.
 *
 * **Einer statt vieler.** Die Erinnerungen an Termine bekommen jeweils einen eigenen Alarm, weil
 * jede an einem eigenen Datum hängt. Hier ist es umgekehrt: Die Bedingung „steht gut und wird klar"
 * kennt kein Datum, sie muss jeden Tag neu gestellt werden. Ein Alarm prüft deshalb die ganze
 * Liste, und ein Eintrag mehr kostet keinen weiteren.
 *
 * **Warum eine Kette und kein wiederholender Alarm.** `setRepeating` schläft im Doze-Modus mit,
 * genau an den Tagen, an denen das Handy unangetastet auf dem Tisch liegt — also gerade dann, wenn
 * die Meldung gebraucht würde. `setAndAllowWhileIdle` kommt durch, ist aber einmalig; also setzt
 * jede Prüfung die nächste. Reißt die Kette trotzdem einmal (Absturz, App-Daten gelöscht), wird sie
 * beim nächsten Öffnen des Kalenders und beim nächsten Neustart wieder angeknüpft — beide rufen
 * [scheduleDailyCheck] auf, und der Aufruf ist idempotent.
 *
 * **Warum kein WorkManager.** Er brächte Netzbedingung und Wiederholung mit, dafür eine weitere
 * Abhängigkeit, ein loses Zeitfenster statt einer Uhrzeit und eine zweite Sorte
 * Hintergrundmechanik neben den Alarmen, die es hier ohnehin schon gibt. Die Wiederholung sind die
 * zwanzig Zeilen in [scheduleRetry].
 */
class WatchScheduler(context: Context) {

    private val appContext = context.applicationContext
    private val alarmManager = appContext.getSystemService<AlarmManager>()

    /**
     * Setzt die Prüfung für den nächsten [CHECK_TIME].
     *
     * Idempotent: Ein bestehender Alarm wird ersetzt, nicht verdoppelt.
     */
    fun scheduleDailyCheck(zone: ZoneId = ZoneId.systemDefault()) {
        ensureChannel()
        val now = System.currentTimeMillis()
        val today = LocalDate.now(zone).atTime(CHECK_TIME).atZone(zone).toInstant().toEpochMilli()
        val trigger = if (today > now + LEAD_MILLIS) today else today + DAY_MILLIS
        set(trigger, attempt = 0)
    }

    /**
     * Noch einmal nachfragen, wenn beim ersten Versuch kein Netz da war.
     *
     * Die Verzögerung ist bewusst großzügig: Wer um 15 Uhr keinen Empfang hat, hat ihn um 15:01
     * meistens auch nicht. Nach [MAX_ATTEMPTS] Versuchen ist Schluss — bis 17 Uhr ohne Netz heißt,
     * dass diese Nacht ohne Vorhersage bleibt, und eine Meldung ohne sie soll es nicht geben.
     */
    fun scheduleRetry(attempt: Int) {
        if (attempt > MAX_ATTEMPTS) return
        set(System.currentTimeMillis() + RETRY_DELAY_MILLIS, attempt)
    }

    fun cancelDailyCheck() {
        for (attempt in 0..MAX_ATTEMPTS) {
            pendingIntent(attempt, create = false)?.let { pending ->
                alarmManager?.cancel(pending)
                pending.cancel()
            }
        }
    }

    /**
     * Der eigene Kanal für die Merkliste.
     *
     * Getrennt von den Terminerinnerungen, damit sich beides getrennt stummschalten lässt: Die
     * Erinnerung an einen selbst gesetzten Termin will man hören, eine Gelegenheitsmeldung
     * vielleicht nur leise.
     */
    fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = appContext.getSystemService<NotificationManager>() ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Günstige Nächte",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Meldet sich, wenn ein vorgemerktes Objekt gut steht und die Nacht klar wird."
            }
        )
    }

    private fun set(triggerMillis: Long, attempt: Int) {
        val intent = pendingIntent(attempt, create = true) ?: return
        runCatching {
            alarmManager?.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMillis, intent)
        }.onFailure {
            // Eine verweigerte Alarmsetzung darf die Merkliste nicht mitnehmen; sie meldet sich
            // dann eben nicht von selbst, bleibt aber im Kalender stehen.
            Log.w(TAG, "Prüfung der Merkliste konnte nicht gesetzt werden", it)
        }
    }

    private fun pendingIntent(attempt: Int, create: Boolean): PendingIntent? {
        val intent = Intent(appContext, WatchCheckReceiver::class.java).apply {
            action = ACTION_CHECK
            putExtra(EXTRA_ATTEMPT, attempt)
        }
        val flags = PendingIntent.FLAG_IMMUTABLE or
            if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE
        return PendingIntent.getBroadcast(appContext, REQUEST_BASE + attempt, intent, flags)
    }

    companion object {
        private const val TAG = "WatchScheduler"

        const val CHANNEL_ID = "watch_opportunities"
        const val ACTION_CHECK = "com.starwindow.app.CHECK_WATCHLIST"
        const val EXTRA_ATTEMPT = "attempt"

        /**
         * Nachmittags, nicht abends.
         *
         * Um 15 Uhr ist noch Zeit, den Abend umzuräumen, Akkus zu laden oder loszufahren. Eine
         * Meldung um 21 Uhr wäre für alles davon zu spät und für die Nacht selbst zu früh.
         */
        val CHECK_TIME: LocalTime = LocalTime.of(15, 0)

        /** Nach so langer Zeit wird ein missglückter Abruf wiederholt. */
        const val RETRY_DELAY_MILLIS = 45 * 60_000L

        /** Zwei Wiederholungen; danach ist es 16:30 und die Nacht bleibt ohne Vorhersage. */
        const val MAX_ATTEMPTS = 2

        private const val DAY_MILLIS = 24 * 3_600_000L

        /** Liegt die heutige Uhrzeit nur noch knapp voraus, wird gleich auf morgen gesetzt. */
        private const val LEAD_MILLIS = 60_000L

        /** Eigener Bereich in der Nummerierung, weit weg von den Terminerinnerungen. */
        private const val REQUEST_BASE = 900_000
    }
}
