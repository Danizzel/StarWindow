package com.starwindow.app.data.planning

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.starwindow.app.data.weather.NightWeatherSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Turns a due alarm into the notification the user actually sees.
 *
 * Everything it needs about the *appointment* travels in the intent rather than being looked up: a
 * receiver runs in a process that may have just been created for it, and reading a JSON file off
 * disk on the main thread inside `onReceive` — which has a few seconds before the system kills it —
 * is the classic way to get a reminder that sometimes silently does not appear.
 *
 * ### Drei Stufen, und die Reihenfolge ist Absicht
 *
 * 1. **Sofort:** die Erinnerung wird gepostet, ohne irgendetwas nachzuschlagen. Das ist die Zusage,
 *    die die Planung gibt, und sie hängt an nichts — nicht am Empfang, nicht am Wetterdienst, nicht
 *    daran, ob überhaupt ein Ort eingestellt ist.
 * 2. **Aus der Ablage:** der zuletzt geholte Modelllauf liegt auf der Platte und ist ohne Netz und
 *    ohne Wartezeit da. Reicht er bis in diese Nacht, wird die Benachrichtigung um die Wetterzeile
 *    ergänzt — angeschrieben mit ihrem Alter.
 * 3. **Frisch:** parallel dazu ein Abruf mit knappem Zeitbudget. Kommt er durch, ersetzt er die
 *    Zeile aus Stufe 2; kommt er nicht durch, bleibt es beim alten Stand.
 *
 * Ergänzt wird immer über dieselbe Benachrichtigungs-ID, also lautlos. Wer das Handy in der Hand
 * hat, sieht die Zeile nachwachsen; wer es in der Tasche hat, hört einen Ton und liest später den
 * fertigen Text.
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ReminderScheduler.ACTION_REMIND) return
        val notice = ReminderNotice.from(intent) ?: return
        if (!ReminderNotifications.canPost(context)) return

        // Stufe 1. Ab hier ist die Zusage eingelöst, alles Weitere ist Zugabe.
        ReminderNotifications.post(context, notice, outlook = null)

        val appContext = context.applicationContext
        val pending = goAsync()
        scope.launch {
            try {
                enrich(appContext, notice)
            } catch (e: Exception) {
                Log.i(TAG, "Wetterlage zur Erinnerung nicht ermittelbar", e)
            } finally {
                // Ohne das hält der Prozess die Broadcast-Quittung offen, bis das System ihn
                // abwürgt — und das zählt als Fehlverhalten der App, nicht als Netzproblem.
                pending.finish()
            }
        }
    }

    private suspend fun enrich(context: Context, notice: ReminderNotice) {
        val source = NightWeatherSource.forContext(context)
        if (!source.hasPlace) return

        // Stufe 2.
        val stored = runCatching { source.stored(notice.night) }.getOrNull()
        if (stored != null) ReminderNotifications.post(context, notice, stored)

        // Stufe 3. Das Zeitbudget liegt weit unter dem, was ein Alarm-Broadcast hat, und weit über
        // dem, was ein Abruf im Normalfall braucht: Es schützt nicht vor dem langsamen Netz,
        // sondern vor dem, das gar nicht mehr antwortet.
        val fresh = withTimeoutOrNull(NETWORK_BUDGET_MILLIS) {
            runCatching { source.fresh(notice.night) }.getOrNull()
        }
        // Der Vergleich der Abrufzeitpunkte spart die zweite Ausgabe, wenn das Netz nur bestätigt
        // hat, was ohnehin schon in der Ablage lag.
        if (fresh != null && fresh.fetchedAtMillis != stored?.fetchedAtMillis) {
            ReminderNotifications.post(context, notice, fresh)
        }
    }

    private companion object {
        const val TAG = "ReminderReceiver"

        /** 20 s: ein Alarm-Broadcast hat 60, ein erfolgreicher Abruf braucht unter drei. */
        const val NETWORK_BUDGET_MILLIS = 20_000L

        /**
         * Eigener Bereich statt eines Lebenszyklus — einen hat ein Empfänger nicht. Am Leben
         * gehalten wird die Arbeit von `goAsync`, nicht von diesem Bereich.
         */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

/**
 * Re-arms the alarms after a reboot.
 *
 * The system drops every alarm on shutdown, so without this a plan made in September quietly stops
 * announcing itself the first time the phone restarts — the worst kind of failure, because nothing
 * looks broken until the night has passed.
 *
 * Gilt für beide Sorten: die Erinnerungen an vorgemerkte Nächte **und** die tägliche Prüfung der
 * Merkliste.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        val appContext = context.applicationContext
        WatchScheduler(appContext).scheduleDailyCheck()
        PlanReminderSync.rescheduleAll(appContext) { pending.finish() }
    }
}
