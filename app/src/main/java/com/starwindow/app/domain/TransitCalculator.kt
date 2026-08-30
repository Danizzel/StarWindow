package com.starwindow.app.domain

import com.starwindow.app.core.astro.AstroTime
import com.starwindow.app.core.astro.CoordinateTransforms
import com.starwindow.app.core.astro.Equatorial
import com.starwindow.app.core.astro.LunarEphemeris
import com.starwindow.app.core.astro.ObserverLocation
import com.starwindow.app.core.astro.Precession
import com.starwindow.app.core.astro.Horizontal
import com.starwindow.app.core.geometry.SkyWindow
import com.starwindow.app.data.catalog.SkyObject
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/** Wie brauchbar ein Durchgang ist — die Frage, die die reine Geometrie offenlässt. */
enum class TransitDarkness(val label: String) {
    /** Vollständig in astronomischer Dunkelheit. */
    DARK("dunkel"),

    /** Teils dunkel, teils Dämmerung — der Anfang oder das Ende ist zu hell. */
    PARTLY("teils Dämmerung"),

    /** Kein Anteil in astronomischer Dunkelheit. */
    BRIGHT("zu hell"),
}

/** One continuous stretch during which an object sits inside the window. */
data class TransitInterval(
    val enterMillis: Long,
    val exitMillis: Long,
    /** True when the object was already inside when the search started. */
    val clippedAtStart: Boolean,
    /** True when the object was still inside when the search ended. */
    val clippedAtEnd: Boolean,
    /** Highest altitude reached while inside, and when. */
    val bestAltitudeDeg: Double,
    val bestMillis: Long,
    /**
     * Wie viel des Durchgangs in astronomischer Dunkelheit liegt.
     *
     * Die Angabe, die aus einer geometrischen Aussage eine brauchbare macht: Ein Durchgang um
     * 14 Uhr sieht ohne sie genauso aus wie einer um zwei Uhr nachts.
     */
    val darkMillis: Long = 0L,
    /** Höhe des Mondes in der Mitte des Durchgangs; unter null stört er nicht. */
    val moonAltitudeDeg: Double = -90.0,
    /** Beleuchteter Anteil des Mondes in Prozent, zur selben Zeit. */
    val moonIlluminationPercent: Double = 0.0,
) {
    val durationMillis: Long get() = exitMillis - enterMillis

    val darkness: TransitDarkness
        get() = when {
            darkMillis <= 0L -> TransitDarkness.BRIGHT
            // Ein Rest von einer Minute Dämmerung macht aus einer dunklen Stunde keine halbe
            // Sache; erst wenn ein nennenswerter Teil hell ist, gehört das gesagt.
            darkMillis >= durationMillis * FULLY_DARK_FRACTION -> TransitDarkness.DARK
            else -> TransitDarkness.PARTLY
        }

    /**
     * Der Mond steht dabei über dem Horizont und ist hell genug, dass es auffällt.
     *
     * Dieselbe Schwelle wie in der Jahresplanung: unter 30 % beleuchtet ist er ein Lichtpunkt, kein
     * Störlicht.
     */
    val moonInterferes: Boolean get() = moonAltitudeDeg > 0.0 && moonIlluminationPercent >= 30.0

    /** Kurzfassung für die Liste: „dunkel · Mond 87 %". */
    val conditionLabel: String get() = buildString {
        append(darkness.label)
        if (moonInterferes) {
            append(" · Mond ").append(Math.round(moonIlluminationPercent)).append(" %")
        }
    }

    private companion object {
        /** Ab diesem Anteil gilt ein Durchgang als ganz dunkel. */
        const val FULLY_DARK_FRACTION = 0.9
    }
}

/** Everything the search found for one catalogue object. */
data class ObjectTransit(
    val obj: SkyObject,
    val intervals: List<TransitInterval>,
) {
    val totalDurationMillis: Long get() = intervals.sumOf { it.durationMillis }
    val firstEntryMillis: Long get() = intervals.minOf { it.enterMillis }
    val isInsideAtStart: Boolean get() = intervals.any { it.clippedAtStart }

    /** Wie viel der Durchgänge insgesamt in der Dunkelheit liegt. */
    val darkDurationMillis: Long get() = intervals.sumOf { it.darkMillis }

    /** True, wenn wenigstens ein Durchgang überhaupt in der Dunkelheit liegt. */
    val hasDarkTime: Boolean get() = darkDurationMillis > 0L

    /**
     * Der Durchgang, auf den es ankommt: der mit der meisten dunklen Zeit.
     *
     * Bei Gleichstand — etwa wenn keiner davon in die Nacht fällt — entscheidet die Dauer, damit
     * auch dann der aussagekräftigste oben steht statt des ersten.
     */
    val bestInterval: TransitInterval?
        get() = intervals.maxWithOrNull(
            compareBy<TransitInterval> { it.darkMillis }.thenBy { it.durationMillis }
        )
}

data class TransitSearchResult(
    val window: SkyWindow,
    val fromMillis: Long,
    val toMillis: Long,
    val transits: List<ObjectTransit>,
    val objectsConsidered: Int,
    val computeMillis: Long,
)

/**
 * Works out which catalogue objects drift through a window, and for how long.
 *
 * The window is fixed to the horizon, the sky rotates past it, so this is a straightforward sweep
 * over time: sample the position of every candidate on a coarse grid, then bisect around each
 * crossing to pin the entry and exit times down to a second. Sampling beats solving analytically
 * here because the window can be an arbitrary polygon — there is no closed form to solve against.
 *
 * Positions include atmospheric refraction, matching what the camera saw when the window was drawn.
 */
class TransitCalculator(
    /** Coarse sampling step. 60 s ≈ 0.25° of sky rotation, small next to any usable window. */
    private val stepSeconds: Long = 60L,
    /** Bisection depth for the entry/exit times; 12 steps take 60 s down to well under a second. */
    private val refineIterations: Int = 12,
) {

    suspend fun search(
        window: SkyWindow,
        objects: List<SkyObject>,
        fromMillis: Long,
        toMillis: Long,
    ): TransitSearchResult = withContext(Dispatchers.Default) {
        val started = System.currentTimeMillis()
        require(toMillis > fromMillis) { "Der Suchzeitraum muss positiv sein" }

        val latitude = window.observer.latitudeDeg
        val longitude = window.observer.longitudeDeg
        val inside = window.shape.membershipTest()
        val bounds = window.altitudeBounds()

        // Two rejections before any object is scanned over time, because the scan is what costs:
        // each survivor is sampled several hundred times, each non-survivor not at all.
        //
        // Sonne und Mond sind von beiden ausgenommen, und zwar zwangsläufig: Beide Prüfungen
        // rechnen mit einer festen Deklination, und der Mond hat keine — er läuft im Lauf eines
        // Monats über 57° Breite, also über weit mehr, als eine Vorprüfung an Spielraum
        // verkraftet. Kosten tut die Ausnahme nichts: Es sind zwei Objekte.
        val windowDec = declinationOfDirection(window.shape.center(), latitude)
        val reach = window.shape.angularRadiusDeg() + DECLINATION_MARGIN_DEG
        val candidates = objects.filter {
            it.isMoving || (it.canReachDeclination(windowDec, reach) && it.canReach(bounds, latitude))
        }

        // Precession is computed once for the whole search and each object is brought to date once,
        // rather than inside the scan: it shifts by 0.0001 arcseconds over a night, so recomputing
        // it per sample would cost thousands of trigonometric calls for no change in the answer.
        val precession = Precession.forEpoch(fromMillis)

        // Every object is scanned independently of every other, so the work splits across cores
        // without any coordination. `inside` is a pure closure over immutable geometry and is
        // shared deliberately: building one tangent plane per chunk would undo the point of
        // `membershipTest` existing.
        // Die Dämmerung hängt nur am Zeitraum und am Ort, nicht am Katalog — einmal gerechnet und
        // dann für jeden der mehreren hundert Durchgänge nur noch geschnitten.
        val dark = DarkSpans.over(fromMillis, toMillis, window.observer)

        val transits = scanInParallel(candidates) { obj ->
            val intervals = if (obj.isMoving) {
                movingIntervalsFor(obj, inside, latitude, longitude, fromMillis, toMillis)
            } else {
                intervalsFor(obj.positionAt(precession), inside, latitude, longitude, fromMillis, toMillis)
            }
            if (intervals.isEmpty()) {
                null
            } else {
                ObjectTransit(obj, intervals.map { it.withConditions(dark, window.observer) })
            }
        }.sortedBy { it.firstEntryMillis }

        TransitSearchResult(
            window = window,
            fromMillis = fromMillis,
            toMillis = toMillis,
            transits = transits,
            objectsConsidered = candidates.size,
            computeMillis = System.currentTimeMillis() - started,
        )
    }

    /** Which of the given objects sit inside the window right now. */
    fun objectsInsideAt(
        window: SkyWindow,
        objects: List<SkyObject>,
        atMillis: Long,
    ): List<Pair<SkyObject, Horizontal>> {
        val inside = window.shape.membershipTest()
        val lst = AstroTime.lstDeg(atMillis, window.observer.longitudeDeg)
        val precession = Precession.forEpoch(atMillis)
        return objects.mapNotNull { obj ->
            val position = CoordinateTransforms.apparentHorizontalAtLst(
                if (obj.isMoving) obj.positionAtMillis(atMillis) else obj.positionAt(precession),
                window.observer.latitudeDeg,
                lst,
            )
            if (inside(position)) obj to position else null
        }
    }

    /**
     * Dasselbe für ein Objekt, dessen Position eine Funktion der Zeit ist.
     *
     * Der einzige Unterschied ist, **wann** die Position gerechnet wird: bei einem Katalogobjekt
     * einmal vor dem Scan, hier bei jeder Abtastung neu. Das ist teurer — eine Mondposition sind
     * dreißig Reihenglieder statt einer Drehung —, aber es sind zwei Objekte, und für sie gibt es
     * keine Abkürzung: Der Mond wandert in einer Stunde um seinen eigenen Durchmesser weiter, und
     * ein Fenster ist oft nicht viel größer.
     *
     * Ebenso wird feiner abgetastet. Die 60 Sekunden für ein Katalogobjekt sind 0,25° Erddrehung;
     * beim Mond kommt seine Eigenbewegung dazu, aber sie läuft der Drehung entgegen und
     * verlangsamt ihn — die Schrittweite bleibt damit auf der sicheren Seite. Sie wird trotzdem
     * halbiert, weil der Fehler beim Ein- und Austritt sonst gerade an der Kante liegt, an der die
     * Frage „zieht der Mond durch mein Fenster" entschieden wird.
     */
    private fun movingIntervalsFor(
        obj: SkyObject,
        inside: (Horizontal) -> Boolean,
        latitudeDeg: Double,
        longitudeDeg: Double,
        fromMillis: Long,
        toMillis: Long,
    ): List<TransitInterval> {
        val positionAt: (Long) -> Horizontal = { millis ->
            positionAt(obj.positionAtMillis(millis), latitudeDeg, longitudeDeg, millis)
        }
        return IntervalScanner.scan(
            fromMillis = fromMillis,
            toMillis = toMillis,
            stepMillis = stepSeconds * 500L,
            refineIterations = refineIterations,
            isInside = { millis -> inside(positionAt(millis)) },
            score = { millis -> positionAt(millis).altitudeDeg },
        ).map { interval ->
            TransitInterval(
                enterMillis = interval.enterMillis,
                exitMillis = interval.exitMillis,
                clippedAtStart = interval.clippedAtStart,
                clippedAtEnd = interval.clippedAtEnd,
                bestAltitudeDeg = interval.bestScore,
                bestMillis = interval.bestMillis,
            )
        }
    }

    private fun intervalsFor(
        equatorialOfDate: Equatorial,
        inside: (Horizontal) -> Boolean,
        latitudeDeg: Double,
        longitudeDeg: Double,
        fromMillis: Long,
        toMillis: Long,
    ): List<TransitInterval> = IntervalScanner.scan(
        fromMillis = fromMillis,
        toMillis = toMillis,
        stepMillis = stepSeconds * 1000L,
        refineIterations = refineIterations,
        isInside = { millis -> inside(positionAt(equatorialOfDate, latitudeDeg, longitudeDeg, millis)) },
        score = { millis -> positionAt(equatorialOfDate, latitudeDeg, longitudeDeg, millis).altitudeDeg },
    ).map { interval ->
        TransitInterval(
            enterMillis = interval.enterMillis,
            exitMillis = interval.exitMillis,
            clippedAtStart = interval.clippedAtStart,
            clippedAtEnd = interval.clippedAtEnd,
            bestAltitudeDeg = interval.bestScore,
            bestMillis = interval.bestMillis,
        )
    }

    private fun positionAt(
        equatorialOfDate: Equatorial,
        latitudeDeg: Double,
        longitudeDeg: Double,
        millis: Long,
    ): Horizontal = CoordinateTransforms.apparentHorizontalAtLst(
        equatorialOfDate,
        latitudeDeg,
        AstroTime.lstDeg(millis, longitudeDeg),
    )

    /**
     * Runs [scan] over every candidate, spread across the available cores.
     *
     * Below [PARALLEL_THRESHOLD] candidates it stays on one thread: splitting a list of eighty
     * costs more in coroutine setup than the scan itself takes, and the whole point of the
     * declination filter above is that most searches now land in exactly that range.
     */
    private suspend fun <T : Any> scanInParallel(
        candidates: List<SkyObject>,
        scan: (SkyObject) -> T?,
    ): List<T> {
        if (candidates.size < PARALLEL_THRESHOLD) return candidates.mapNotNull(scan)

        val workers = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
        val chunkSize = (candidates.size + workers - 1) / workers
        return coroutineScope {
            candidates.chunked(chunkSize)
                .map { chunk -> async { chunk.mapNotNull(scan) } }
                .awaitAll()
                .flatten()
        }
    }

    private companion object {
        /** Below this many candidates the split costs more than it saves. */
        const val PARALLEL_THRESHOLD = 200
    }
}

/**
 * Cheap rejection: an object's altitude oscillates between two fixed values set by its declination
 * and the observer's latitude. If that band misses the window's altitude band, no amount of Earth
 * rotation will ever bring it inside.
 */
private fun SkyObject.canReach(
    altitudeBounds: ClosedFloatingPointRange<Double>,
    latitudeDeg: Double,
): Boolean {
    val maxAltitude = 90.0 - abs(latitudeDeg - decDeg)
    val minAltitude = abs(latitudeDeg + decDeg) - 90.0
    return maxAltitude >= altitudeBounds.start && minAltitude <= altitudeBounds.endInclusive
}

/**
 * The declination a horizon-fixed direction sits at.
 *
 * This is the observation that makes the whole search cheap, and it is worth stating plainly: a
 * window is nailed to the horizon, so as the sky turns underneath it, its **right ascension drifts
 * but its declination never changes**. A gap over the garage looks out at one fixed band of
 * declination for as long as it exists.
 *
 * From the standard transformation, with the hour angle eliminated:
 *
 *     sin δ = sin φ · sin h + cos φ · cos h · cos A
 */
internal fun declinationOfDirection(direction: Horizontal, latitudeDeg: Double): Double {
    val lat = Math.toRadians(latitudeDeg)
    val altitude = Math.toRadians(direction.altitudeDeg)
    val azimuth = Math.toRadians(direction.azimuthDeg)
    val sinDec = sin(lat) * sin(altitude) + cos(lat) * cos(altitude) * cos(azimuth)
    return Math.toDegrees(asin(sinDec.coerceIn(-1.0, 1.0)))
}

/**
 * Whether the object's declination band can reach the window's at all.
 *
 * Far sharper than the altitude test on its own, and it is the difference between a search that
 * takes a second and one that takes a moment. The altitude test only asks whether the object ever
 * climbs to the right *height*; it says nothing about direction, so from Berlin a circumpolar
 * object at +80° passes it for a window facing south at 45° — it does reach that altitude, just
 * never anywhere near that azimuth. Comparing declinations rejects it immediately, because the two
 * declinations are both constants.
 *
 * The margin covers what the comparison glosses over: refraction lifts an object by up to half a
 * degree near the horizon, catalogue positions are J2000 while the window is of date (0.4° of
 * precession), and a polygon's angular radius is measured to its furthest corner.
 */
private fun SkyObject.canReachDeclination(windowDecDeg: Double, reachDeg: Double): Boolean =
    abs(decDeg - windowDecDeg) <= reachDeg

/** Refraction, precession and a little room to spare. */
private const val DECLINATION_MARGIN_DEG = 1.5

/**
 * Ergänzt einen Durchgang um Dämmerung und Mondstand.
 *
 * Der Mond wird in der **Mitte** des Durchgangs ausgewertet und nicht an seinem Anfang: Ein
 * Durchgang dauert Minuten bis Stunden, der Mond steht in dieser Zeit ungefähr gleich, und die
 * Mitte ist der Zeitpunkt, der für den ganzen Abschnitt am wenigsten daneben liegt.
 */
private fun TransitInterval.withConditions(
    dark: DarkSpans,
    observer: ObserverLocation,
): TransitInterval {
    val middle = (enterMillis + exitMillis) / 2
    val moon = LunarEphemeris.at(middle)
    return copy(
        darkMillis = dark.overlap(enterMillis, exitMillis),
        moonAltitudeDeg = LunarEphemeris.topocentricAltitudeDeg(moon, observer, middle),
        moonIlluminationPercent = LunarEphemeris.illuminationAt(middle).percent,
    )
}
