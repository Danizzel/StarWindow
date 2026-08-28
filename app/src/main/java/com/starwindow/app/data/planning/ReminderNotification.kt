package com.starwindow.app.data.planning

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.starwindow.app.MainActivity
import com.starwindow.app.R
import com.starwindow.app.domain.NightOutlook
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Alles, was eine Erinnerung über ihren Termin weiß.
 *
 * Kommt vollständig aus dem Intent und nicht aus der Datei: Ein Empfänger läuft in einem Prozess,
 * den das System für genau diesen Alarm gestartet hat, und eine JSON-Datei im Hauptthread von
 * `onReceive` zu lesen ist der klassische Weg zu einer Erinnerung, die manchmal stillschweigend
 * ausbleibt.
 */
data class ReminderNotice(
    val sessionId: String,
    val label: String,
    val night: LocalDate,
    val hours: Double,
    val note: String,
    val lead: ReminderLead?,
) {
    companion object {
        fun from(intent: Intent): ReminderNotice? {
            val label = intent.getStringExtra(ReminderScheduler.EXTRA_LABEL).orEmpty()
            val epochDay = intent.getLongExtra(ReminderScheduler.EXTRA_DATE, 0L)
            if (label.isEmpty() || epochDay == 0L) return null
            return ReminderNotice(
                sessionId = intent.getStringExtra(ReminderScheduler.EXTRA_SESSION_ID).orEmpty(),
                label = label,
                night = LocalDate.ofEpochDay(epochDay),
                hours = intent.getDoubleExtra(ReminderScheduler.EXTRA_HOURS, 0.0),
                note = intent.getStringExtra(ReminderScheduler.EXTRA_NOTE).orEmpty(),
                lead = intent.getStringExtra(ReminderScheduler.EXTRA_LEAD)
                    ?.let { name -> runCatching { ReminderLead.valueOf(name) }.getOrNull() },
            )
        }
    }
}

/**
 * Baut und postet die Benachrichtigung zu einem Termin — wahlweise mit oder ohne Wetterlage.
 *
 * **Die Reihenfolge ist die eigentliche Entscheidung.** Die Erinnerung wird zuerst ohne jede
 * Vorhersage gepostet und danach, wenn eine da ist, an derselben Stelle ergänzt. Der umgekehrte Weg
 * — erst das Netz fragen, dann posten — tauscht Verlässlichkeit gegen Ausschmückung: Ohne Empfang,
 * mit einem hängenden Abruf oder in einem Prozess, den das System nach ein paar Sekunden beendet,
 * käme dann gar nichts an. Ein Termin, an den nicht erinnert wird, ist verloren; ein Termin ohne
 * Wetterzeile ist bloß karg.
 *
 * Ergänzt wird über dieselbe Benachrichtigungs-ID mit `setOnlyAlertOnce`, also ohne zweiten Ton und
 * ohne zweiten Eintrag in der Leiste: Der Text ändert sich unter der Hand, sonst nichts.
 */
object ReminderNotifications {

    fun post(context: Context, notice: ReminderNotice, outlook: NightOutlook?) {
        if (!canPost(context)) return

        val notification = NotificationCompat.Builder(context, ReminderScheduler.CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title(notice))
            .setContentText(collapsed(notice, outlook))
            .setStyle(NotificationCompat.BigTextStyle().bigText(expanded(notice, outlook)))
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            // Die Ergänzung um das Wetter darf nicht ein zweites Mal klingeln.
            .setOnlyAlertOnce(true)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(notice.sessionId.hashCode(), notification)
        }
    }

    /** Ab Android 13 kann der Nutzer Benachrichtigungen ganz ablehnen; Posten wirft dann. */
    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /** Nennt den Vorlauf im Titel, so sind Wochen- und Tagesmeldung auf einen Blick zu trennen. */
    private fun title(notice: ReminderNotice): String = when (notice.lead) {
        ReminderLead.ONE_WEEK -> "In einer Woche: ${notice.label}"
        ReminderLead.THREE_DAYS -> "In drei Tagen: ${notice.label}"
        ReminderLead.ONE_DAY -> "Morgen Nacht: ${notice.label}"
        ReminderLead.SAME_DAY -> "Heute Nacht: ${notice.label}"
        null -> notice.label
    }

    /**
     * Die eine Zeile, die eingeklappt zu sehen ist.
     *
     * Sobald es eine Vorhersage gibt, steht sie hier — sie ist die Neuigkeit. Das Datum der Nacht
     * steht schon im Titel („Morgen Nacht"), und wer aufklappt, bekommt beides.
     */
    private fun collapsed(notice: ReminderNotice, outlook: NightOutlook?): String =
        outlook?.let { "Prognose: ${it.headline}" } ?: summary(notice)

    private fun expanded(notice: ReminderNotice, outlook: NightOutlook?): String = buildString {
        append(summary(notice))
        if (outlook != null) {
            append("\n\nPrognose: ").append(outlook.headline)
            append("\n").append(outlook.source)
            if (outlook.isDiscouraging) {
                // Der Termin wird nicht abgesagt und die Erinnerung nicht unterdrückt. Ob jemand
                // trotzdem hinausgeht — wegen einer Wolkenlücke, wegen der Fahrt, weil die
                // Vorhersage sich irrt —, ist seine Entscheidung und nicht die der App.
                append("\n\nDer Termin bleibt stehen, die Vorhersage spricht dagegen.")
            }
        }
        if (notice.note.isNotBlank()) append("\n\n").append(notice.note)
    }

    private fun summary(notice: ReminderNotice): String = buildString {
        append("Nacht auf ").append(dateFormat.format(notice.night.plusDays(1)))
        if (notice.hours > 0.0) {
            append(" · ").append("%.1f h".format(Locale.GERMAN, notice.hours)).append(" belichtbar")
        }
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private val dateFormat: DateTimeFormatter =
        DateTimeFormatter.ofPattern("EEEE, d. MMMM", Locale.GERMAN)
}
