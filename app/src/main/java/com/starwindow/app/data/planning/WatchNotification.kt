package com.starwindow.app.data.planning

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.starwindow.app.MainActivity
import com.starwindow.app.R
import com.starwindow.app.domain.WatchHit
import java.time.ZoneId

/**
 * Die Meldung „heute Nacht passt es".
 *
 * **Eine Benachrichtigung, nicht eine je Objekt.** Bei stabilem Hochdruck erfüllen an einem Abend
 * gern drei oder vier vorgemerkte Ziele die Bedingung gleichzeitig — vier einzelne Meldungen
 * hintereinander wären dieselbe Nachricht, viermal ausgesprochen. Aufgezählt wird deshalb in einer,
 * das beste Fenster zuerst.
 *
 * Anders als bei den Terminerinnerungen steht das Wetter hier nicht als Zusatz dabei, sondern ist
 * Teil des Grundes: Ohne brauchbare Vorhersage entsteht diese Meldung gar nicht erst.
 */
object WatchNotifications {

    fun post(context: Context, hits: List<WatchHit>, zone: ZoneId) {
        if (hits.isEmpty()) return
        if (!ReminderNotifications.canPost(context)) return

        val notification = NotificationCompat.Builder(context, WatchScheduler.CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title(hits))
            .setContentText(collapsed(hits, zone))
            .setStyle(NotificationCompat.BigTextStyle().bigText(expanded(hits, zone)))
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }
    }

    private fun title(hits: List<WatchHit>): String =
        if (hits.size == 1) {
            "Heute Nacht: ${hits.first().watch.label}"
        } else {
            "${hits.size} vorgemerkte Ziele stehen heute Nacht gut"
        }

    private fun collapsed(hits: List<WatchHit>, zone: ZoneId): String =
        if (hits.size == 1) {
            hits.first().headline(zone)
        } else {
            hits.take(LISTED).joinToString(" · ") { "${it.watch.label}: ${it.headline(zone)}" }
        }

    private fun expanded(hits: List<WatchHit>, zone: ZoneId): String =
        if (hits.size == 1) {
            hits.first().detail(zone)
        } else {
            buildString {
                hits.take(LISTED).forEach { hit ->
                    append(hit.watch.label).append("\n  ").append(hit.headline(zone)).append("\n")
                }
                if (hits.size > LISTED) {
                    append("und ").append(hits.size - LISTED).append(" weitere\n")
                }
                append("\nPrognose: ").append(hits.first().outlook.headline)
                append("\n").append(hits.first().outlook.source)
            }
        }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        WATCH_REQUEST_CODE,
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /**
     * Feste ID: Eine zweite Meldung derselben Nacht — etwa wenn der Wiederholungsversuch mehr
     * findet als der erste — ersetzt die vorige, statt sich danebenzustellen.
     */
    private const val NOTIFICATION_ID = 0x5741

    private const val WATCH_REQUEST_CODE = 1

    /** So viele werden namentlich genannt; der Rest wird gezählt. */
    private const val LISTED = 3
}
