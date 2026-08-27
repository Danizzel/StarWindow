package com.starwindow.app.data.planning

import com.starwindow.app.AppContainer
import com.starwindow.app.core.astro.ObserverLocation
import com.starwindow.app.data.tracking.TrackedObject
import com.starwindow.app.domain.ObservationPlanner
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Turns a planned night into something the viewfinder can point at.
 *
 * The stored session holds a date and a designation, and the viewfinder needs an object plus the
 * **time span to draw the arc over**. That span is the dark window of that particular night, which
 * is not stored — storing it would mean keeping a copy of a computation that goes stale as soon as
 * the observer moves. Recomputing it costs one night's worth of arithmetic, which is nothing.
 *
 * Deliberately not a view model: it is a single resolution step between a stored plan and the
 * tracking store, needed from the navigation layer, and wrapping it in a screen-shaped object would
 * be more scaffolding than substance.
 */
object PlannedPath {

    /**
     * Points the viewfinder at this session's object, with the night's path attached.
     *
     * Falls back to the whole night if the dark window cannot be worked out — around midsummer at
     * high latitudes there may not be one, and an arc across a bright sky is still the answer to
     * "will the roof be in the way", which is what someone taps this for.
     */
    suspend fun track(container: AppContainer, session: PlannedSession): Boolean {
        val obj = container.catalogRepository.objects().firstOrNull { it.id == session.objectId }
            ?: return false

        val place = container.settingsStore.current.weatherPlace
        val observer = place?.let {
            ObserverLocation(
                latitudeDeg = it.latitudeDeg,
                longitudeDeg = it.longitudeDeg,
                elevationM = it.elevationM,
                manual = true,
            )
        } ?: container.settingsStore.current.manualLocation
            ?: container.locationTracker.lastKnown()
            ?: return false

        val zone = place?.timezoneId?.let { runCatching { ZoneId.of(it) }.getOrNull() }
            ?: ZoneId.systemDefault()

        val night = ObservationPlanner
            .plan(obj, observer, zone, session.date, days = 1)
            .firstOrNull()

        val from = night?.darkFromMillis ?: defaultEveningMillis(session.date, zone)
        val to = night?.darkToMillis ?: (from + 10 * 3_600_000L)

        container.trackingStore.track(
            TrackedObject(
                obj = obj,
                pathFromMillis = from,
                pathToMillis = to,
                pathLabel = "Nacht auf " + dateFormat.format(session.date.plusDays(1)),
            )
        )
        return true
    }

    /** Sunset-ish to sunrise-ish, for the nights that have no real darkness to offer. */
    private fun defaultEveningMillis(date: LocalDate, zone: ZoneId): Long =
        date.atTime(21, 0).atZone(zone).toInstant().toEpochMilli()

    private val dateFormat: DateTimeFormatter =
        DateTimeFormatter.ofPattern("EEE, d. MMM", Locale.GERMAN)
}
