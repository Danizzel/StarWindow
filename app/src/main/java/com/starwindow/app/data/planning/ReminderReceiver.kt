package com.starwindow.app.data.planning

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.starwindow.app.MainActivity
import com.starwindow.app.R
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Turns a due alarm into the notification the user actually sees.
 *
 * Everything it needs travels in the intent rather than being looked up: a receiver runs in a
 * process that may have just been created for it, and reading a JSON file off disk on the main
 * thread inside `onReceive` — which has a few seconds before the system kills it — is the classic
 * way to get a reminder that sometimes silently does not appear.
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ReminderScheduler.ACTION_REMIND) return

        val label = intent.getStringExtra(ReminderScheduler.EXTRA_LABEL).orEmpty()
        val sessionId = intent.getStringExtra(ReminderScheduler.EXTRA_SESSION_ID).orEmpty()
        val note = intent.getStringExtra(ReminderScheduler.EXTRA_NOTE).orEmpty()
        val hours = intent.getDoubleExtra(ReminderScheduler.EXTRA_HOURS, 0.0)
        val epochDay = intent.getLongExtra(ReminderScheduler.EXTRA_DATE, 0L)
        val lead = intent.getStringExtra(ReminderScheduler.EXTRA_LEAD)
            ?.let { name -> runCatching { ReminderLead.valueOf(name) }.getOrNull() }

        if (label.isEmpty() || epochDay == 0L) return

        // Android 13 onwards the user can refuse notifications outright; posting anyway throws.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val night = LocalDate.ofEpochDay(epochDay)
        val notification = NotificationCompat.Builder(context, ReminderScheduler.CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title(lead, label))
            .setContentText(summary(night, hours))
            .setStyle(NotificationCompat.BigTextStyle().bigText(body(night, hours, note)))
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(sessionId.hashCode(), notification)
        }
    }

    /** Names the lead in the title, so a week-out and a day-of reminder are told apart at a glance. */
    private fun title(lead: ReminderLead?, label: String): String = when (lead) {
        ReminderLead.ONE_WEEK -> "In einer Woche: $label"
        ReminderLead.THREE_DAYS -> "In drei Tagen: $label"
        ReminderLead.ONE_DAY -> "Morgen Nacht: $label"
        ReminderLead.SAME_DAY -> "Heute Nacht: $label"
        null -> label
    }

    private fun summary(night: LocalDate, hours: Double): String = buildString {
        append("Nacht auf ").append(dateFormat.format(night.plusDays(1)))
        if (hours > 0.0) append(" · %.1f h belichtbar".format(hours))
    }

    private fun body(night: LocalDate, hours: Double, note: String): String = buildString {
        append(summary(night, hours))
        if (note.isNotBlank()) append("\n\n").append(note)
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private companion object {
        val dateFormat: DateTimeFormatter =
            DateTimeFormatter.ofPattern("EEEE, d. MMMM", Locale.GERMAN)
    }
}

/**
 * Re-arms the alarms after a reboot.
 *
 * The system drops every alarm on shutdown, so without this a plan made in September quietly stops
 * announcing itself the first time the phone restarts — the worst kind of failure, because nothing
 * looks broken until the night has passed.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        PlanReminderSync.rescheduleAll(context.applicationContext) { pending.finish() }
    }
}
