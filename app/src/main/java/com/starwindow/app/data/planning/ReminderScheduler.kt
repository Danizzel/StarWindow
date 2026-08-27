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
import java.time.ZoneId

/**
 * Puts the reminders for a planned night into the system alarm queue, and takes them out again.
 *
 * `AlarmManager` rather than WorkManager: this is a handful of one-shot alarms at a wall-clock
 * time, which is exactly what alarms are, and it needs no new dependency.
 *
 * The alarms are deliberately **inexact**. A reminder a week before an observing night does not
 * care about the minute, and asking for `SCHEDULE_EXACT_ALARM` — a permission the user has to grant
 * in system settings and which Google reviews for — to be a quarter of an hour more punctual about
 * "next Friday looks good" would be a bad trade. `setAndAllowWhileIdle` still fires through Doze,
 * which is the part that actually matters on a phone that sits untouched for days.
 *
 * Every alarm is keyed by [requestCode], derived from the session id and the lead, so rescheduling
 * replaces rather than duplicates and cancelling can find exactly the one alarm it means.
 */
class ReminderScheduler(context: Context) {

    private val appContext = context.applicationContext
    private val alarmManager = appContext.getSystemService<AlarmManager>()

    /**
     * Rebuilds every alarm for one session.
     *
     * Cancel-then-schedule rather than diffing: there are at most four alarms per session, and a
     * diff would be more code than the thing it optimises — with the failure mode of leaving a
     * stale alarm behind, which shows up as a notification for a night the user already deleted.
     */
    fun reschedule(session: PlannedSession, zone: ZoneId = ZoneId.systemDefault()) {
        cancel(session)
        ensureChannel()

        val now = System.currentTimeMillis()
        for (lead in session.reminders) {
            val trigger = lead.triggerMillis(session.date, zone)
            if (trigger <= now) continue
            val intent = pendingIntent(session, lead, create = true) ?: continue
            runCatching {
                alarmManager?.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, intent)
            }.onFailure {
                // A denied or unavailable alarm must not take the plan down with it; the entry is
                // still in the calendar, it just will not announce itself.
                Log.w(TAG, "Erinnerung konnte nicht gesetzt werden", it)
            }
        }
    }

    fun cancel(session: PlannedSession) {
        for (lead in ReminderLead.entries) {
            pendingIntent(session, lead, create = false)?.let { pending ->
                alarmManager?.cancel(pending)
                pending.cancel()
            }
        }
    }

    /** Re-arms everything, after a reboot or when the plan is first loaded. */
    fun rescheduleAll(sessions: List<PlannedSession>, zone: ZoneId = ZoneId.systemDefault()) {
        sessions.forEach { reschedule(it, zone) }
    }

    /**
     * The notification channel.
     *
     * Created on demand rather than at application start: an app that has never been asked for a
     * reminder should not appear in the notification settings offering one.
     */
    fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = appContext.getSystemService<NotificationManager>() ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Beobachtungstermine",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Erinnerungen an vorgemerkte Nächte."
            }
        )
    }

    private fun pendingIntent(
        session: PlannedSession,
        lead: ReminderLead,
        create: Boolean,
    ): PendingIntent? {
        val intent = Intent(appContext, ReminderReceiver::class.java).apply {
            action = ACTION_REMIND
            putExtra(EXTRA_SESSION_ID, session.id)
            putExtra(EXTRA_OBJECT_ID, session.objectId)
            putExtra(EXTRA_LABEL, session.objectLabel)
            putExtra(EXTRA_DATE, session.dateEpochDay)
            putExtra(EXTRA_LEAD, lead.name)
            putExtra(EXTRA_NOTE, session.note)
            putExtra(EXTRA_HOURS, session.usableHours)
        }
        val flags = PendingIntent.FLAG_IMMUTABLE or
            if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE
        return PendingIntent.getBroadcast(appContext, requestCode(session.id, lead), intent, flags)
    }

    companion object {
        private const val TAG = "ReminderScheduler"

        const val CHANNEL_ID = "observation_reminders"
        const val ACTION_REMIND = "com.starwindow.app.REMIND"

        const val EXTRA_SESSION_ID = "sessionId"
        const val EXTRA_OBJECT_ID = "objectId"
        const val EXTRA_LABEL = "label"
        const val EXTRA_DATE = "dateEpochDay"
        const val EXTRA_LEAD = "lead"
        const val EXTRA_NOTE = "note"
        const val EXTRA_HOURS = "hours"

        /**
         * A stable, collision-resistant id for one (session, lead) pair.
         *
         * The session id is a UUID, so its hash is spread evenly; mixing the lead's ordinal into
         * the low bits keeps a session's four alarms distinct from each other without letting two
         * sessions collide any more often than their hashes already do.
         */
        fun requestCode(sessionId: String, lead: ReminderLead): Int =
            (sessionId.hashCode() * 8) + lead.ordinal
    }
}
