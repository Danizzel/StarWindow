package com.starwindow.app.data.planning

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Reads the plan off disk and re-arms every alarm in it.
 *
 * Exists as its own object because the one caller that needs it most cannot use the normal path: a
 * [BootReceiver] runs before any activity, so there is no `AppContainer` to reach into and no
 * lifecycle scope to launch from. Building a throwaway repository is cheap — one file read — and
 * far simpler than keeping the application object alive for a broadcast.
 */
object PlanReminderSync {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * @param onFinished called once the work is done, so a receiver can release its
     *   `goAsync` result instead of guessing how long to stay alive.
     */
    fun rescheduleAll(context: Context, onFinished: () -> Unit = {}) {
        scope.launch {
            try {
                val repository = PlanRepository(context)
                repository.load()
                ReminderScheduler(context).rescheduleAll(repository.sessions.value)
            } finally {
                onFinished()
            }
        }
    }
}
