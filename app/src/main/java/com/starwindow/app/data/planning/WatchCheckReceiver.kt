package com.starwindow.app.data.planning

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.starwindow.app.data.weather.NightWeatherSource
import com.starwindow.app.domain.WatchEvaluator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.ZoneId

/**
 * Die nachmittägliche Prüfung: Steht heute Nacht etwas Vorgemerktes gut, und wird es klar?
 *
 * Die Reihenfolge im Empfänger ist wichtig. **Zuerst** wird die Prüfung für morgen gesetzt, erst
 * **danach** wird gerechnet: Bricht die Arbeit ab — kein Netz, keine Einstellungen, eine Ausnahme
 * irgendwo —, läuft die Kette trotzdem weiter. Andersherum hätte ein einzelner schlechter Tag die
 * Merkliste für immer verstummen lassen, und niemand hätte gemerkt, warum.
 *
 * Gemeldet wird nur, wenn eine Vorhersage vorliegt. Ohne sie wird bis zu [WatchScheduler.MAX_ATTEMPTS]
 * mal nachgefragt und danach geschwiegen — eine Meldung „steht heute gut" schickt jemanden mit
 * schwerem Gepäck vor die Tür, und dafür muss die App wissen, ob es klar wird.
 */
class WatchCheckReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != WatchScheduler.ACTION_CHECK) return

        val appContext = context.applicationContext
        val attempt = intent.getIntExtra(WatchScheduler.EXTRA_ATTEMPT, 0)
        val scheduler = WatchScheduler(appContext)

        // Nur der reguläre Durchgang führt die Kette weiter; ein Wiederholungsversuch hat sie
        // bereits hinter sich und würde sie sonst um einen Tag verschieben.
        if (attempt == 0) scheduler.scheduleDailyCheck()

        val pending = goAsync()
        scope.launch {
            try {
                check(appContext, scheduler, attempt)
            } catch (e: Exception) {
                Log.i(TAG, "Prüfung der Merkliste fehlgeschlagen", e)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun check(context: Context, scheduler: WatchScheduler, attempt: Int) {
        val repository = WatchlistRepository(context)
        val watched = repository.readOnce().filter { it.enabled }
        if (watched.isEmpty()) {
            // Der letzte Eintrag wurde entfernt, während die Kette lief. Sie hier anzuhalten spart
            // dem Gerät einen täglichen Weckruf, der nichts mehr zu tun hätte.
            scheduler.cancelDailyCheck()
            return
        }

        val source = NightWeatherSource.forContext(context)
        val place = source.place
        if (place == null) {
            // Ohne Ort gibt es weder Dämmerung noch Vorhersage. Ein Wiederholungsversuch änderte
            // daran nichts — das ist eine Einstellung, kein Netzproblem.
            Log.i(TAG, "Kein Ort eingestellt, Merkliste wird nicht geprüft")
            return
        }

        val zone = place.zone ?: ZoneId.systemDefault()
        val tonight = LocalDate.now(zone)

        val outlook = withTimeoutOrNull(NETWORK_BUDGET_MILLIS) { source.fresh(tonight) }
            ?: source.stored(tonight)
        if (outlook == null) {
            scheduler.scheduleRetry(attempt + 1)
            return
        }

        val hits = WatchEvaluator.evaluateAll(
            watched = watched,
            observer = place.toObserverLocation(),
            zone = zone,
            date = tonight,
            outlook = outlook,
        )
        if (hits.isEmpty()) return

        WatchNotifications.post(context, hits, zone)
        // Erst nach dem Posten vermerken: Ein Absturz dazwischen soll lieber eine Meldung
        // wiederholen als eine ausfallen lassen.
        repository.markNotified(hits.map { it.watch.id }, tonight)
    }

    private companion object {
        const val TAG = "WatchCheckReceiver"

        /** Wie bei der Erinnerung: großzügig, aber nicht unbegrenzt. */
        const val NETWORK_BUDGET_MILLIS = 25_000L

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
