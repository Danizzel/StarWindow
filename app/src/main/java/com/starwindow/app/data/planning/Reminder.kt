package com.starwindow.app.data.planning

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * How far ahead of a planned night to be reminded.
 *
 * A fixed set rather than a free interval, because these are the three decisions people actually
 * make and they are decisions about *different things*: a week out is when you still have time to
 * clear the evening or drive somewhere dark, three days out is when the forecast becomes worth
 * looking at, and the day itself is when you pack the bag. A minute-precise picker would offer
 * infinite choice over a question with three answers.
 */
@Serializable
enum class ReminderLead(val daysBefore: Long, val label: String, val hint: String) {
    @SerialName("week")
    ONE_WEEK(7, "1 Woche vorher", "Zeit, den Abend freizuhalten oder eine Fahrt zu planen"),

    @SerialName("threeDays")
    THREE_DAYS(3, "3 Tage vorher", "Ab hier wird die Wettervorhersage belastbar"),

    @SerialName("day")
    ONE_DAY(1, "1 Tag vorher", "Akkus laden, Ausrüstung zusammenstellen"),

    @SerialName("sameDay")
    SAME_DAY(0, "Am Tag selbst", "Am Nachmittag, bevor es losgeht");

    /**
     * When the notification should fire, in epoch milliseconds.
     *
     * Always at [NOTIFY_TIME] local, not at "the same clock time as the night": a night belongs to
     * the evening it starts on, and a reminder that arrives at three in the morning because that is
     * when the object culminates would be worse than none.
     */
    fun triggerMillis(nightDate: LocalDate, zone: ZoneId): Long =
        nightDate.minusDays(daysBefore)
            .atTime(NOTIFY_TIME)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

    companion object {
        /**
         * Late afternoon: after a working day, and early enough that "tonight" is still actionable.
         */
        val NOTIFY_TIME: LocalTime = LocalTime.of(17, 0)

        /** The order they are offered in — furthest ahead first, like a countdown. */
        val ordered: List<ReminderLead> = listOf(ONE_WEEK, THREE_DAYS, ONE_DAY, SAME_DAY)
    }
}
