package com.starwindow.app.domain

import com.starwindow.app.data.catalog.SkyObject
import kotlin.math.abs

/**
 * What the viewfinder overlay is allowed to draw.
 *
 * The overlay projects every object it is handed from sky coordinates to screen pixels, **every
 * frame**. Handing it the catalogue would cost twenty-two thousand trigonometric transforms fifty
 * times a second, and would be pointless even if it were free: ten thousand markers on a phone
 * screen are not a star chart, they are a grey wash with the sky somewhere behind it.
 *
 * So the viewfinder does not show the catalogue. It shows two things, and nothing else:
 *
 * * **Targets** — what someone would actually photograph, ranked by [PhotographicInterest]. A few
 *   hundred at most, and they are the reason to hold the phone up in the first place.
 * * **Bright stars** — not targets, but the frame of reference. They are what a person recognises
 *   the sky by, what the star calibration measures against, and what the field-of-view check needs:
 *   aim at a known star, see whether its marker sits on it. Take them away and the overlay becomes
 *   unverifiable.
 *
 * Everything else — the anonymous fourteenth-magnitude galaxies, the six thousand unnamed
 * sixth-magnitude stars — is reachable through the search field and through tracking, which is
 * where a specific object is looked for anyway. Nobody finds NGC 5387 by scanning a viewfinder.
 *
 * Three cuts, in this order, each making the next cheaper:
 *
 * 1. **Latitude.** What never rises here can never appear in a frame. Free, and cannot remove
 *    something the user could have seen.
 * 2. **Worth drawing.** A target, or a star bright enough to orient by.
 * 3. **A hard ceiling**, split between the two groups so a rich patch of sky cannot crowd out the
 *    stars that make the rest legible.
 *
 * Pure Kotlin in the domain layer: a judgement about data, and the kind that is worth a test.
 */
object OverlaySelection {

    /**
     * How many markers the overlay will draw at most.
     *
     * Chosen for legibility rather than for a frame budget. A phone screen is about 1000 dp tall
     * and a marker with its label needs roughly 80 by 20 dp of clear space; past a few hundred
     * visible at once they start colliding no matter how fast they are drawn. Since only the part
     * of the sky in front of the camera is ever on screen — a twentieth of the celestial sphere or
     * so — this budget puts a few dozen in view, which is about what a chart shows.
     */
    const val MAX_OBJECTS = 400

    /**
     * Of those, at most this many stars.
     *
     * A reserved share rather than a free-for-all: pointed at Cygnus, brightness alone would fill
     * the whole budget with stars and drop the nebulae that are the reason for looking there.
     */
    const val MAX_STARS = 150

    /**
     * How bright a star has to be to earn a marker.
     *
     * Fourth magnitude is roughly what is visible from a suburban garden and comfortably what the
     * camera shows in a night-mode frame — the stars a person can actually match a marker against.
     * Below that the overlay would be marking things the user cannot see, which helps nobody and
     * is exactly the noise this class exists to remove.
     */
    const val STAR_MAGNITUDE_LIMIT = 4.2

    /**
     * A deep sky object needs at least this much photographic interest to be drawn.
     *
     * Set just above what a name alone is worth, so that an entry with a name but no size and no
     * brightness — all the catalogue knows about some of them — does not earn a marker on the
     * strength of being named. In practice the ceiling usually bites first: pointed at the northern
     * sky, the four hundredth best target still scores around fifty. This is the floor for the
     * cases where it does not, such as an empty patch of southern sky.
     */
    const val MIN_TARGET_SCORE = 35.0

    /**
     * The catalogue reduced to what is worth drawing over the camera image.
     *
     * @param latitudeDeg the observer's latitude, or null while the position is still unknown — in
     *   which case nothing is cut on it, since without a latitude every declination is possible.
     * @param magnitudeLimit the user's setting. It still applies, but only ever tightens: raising
     *   it cannot conjure up anonymous galaxies, it only lets in fainter *targets*.
     */
    fun select(
        catalog: List<SkyObject>,
        latitudeDeg: Double?,
        magnitudeLimit: Double,
    ): List<SkyObject> {
        val reachable = catalog.filter { latitudeDeg == null || everRises(it, latitudeDeg) }

        val stars = reachable.asSequence()
            .filter { it.type.isStar }
            .filter { (it.magnitude ?: 99.0) <= minOf(STAR_MAGNITUDE_LIMIT, magnitudeLimit) }
            .sortedBy { it.magnitude ?: 99.0 }
            .take(MAX_STARS)
            .toList()

        val targets = reachable.asSequence()
            .filter { !it.type.isStar }
            .filter { isWorthDrawing(it, magnitudeLimit) }
            .sortedByDescending { PhotographicInterest.score(it) }
            .take(MAX_OBJECTS - stars.size)
            .toList()

        // Brightest first, so the overlay draws the most prominent markers first and any future
        // collision handling has them to keep.
        return (stars + targets).sortedBy { it.magnitude ?: 99.0 }
    }

    /**
     * Whether a deep sky object earns a marker in the viewfinder.
     *
     * The magnitude limit applies only where the catalogue has a magnitude. Refusing everything
     * without one would drop most large nebulae — the California Nebula has no useful integrated
     * magnitude and is three degrees across — which is the opposite of the intent.
     */
    fun isWorthDrawing(obj: SkyObject, magnitudeLimit: Double): Boolean {
        if (!PhotographicInterest.isPhotoTarget(obj)) return false
        obj.magnitude?.let { if (it > magnitudeLimit) return false }
        return PhotographicInterest.score(obj) >= MIN_TARGET_SCORE
    }

    /**
     * Whether the object ever gets above the horizon at this latitude.
     *
     * Its highest altitude is 90° minus the angle between latitude and declination, which is the
     * whole of the geometry: an object 30° from the observer's zenith circle culminates 30° down.
     */
    fun everRises(obj: SkyObject, latitudeDeg: Double): Boolean =
        90.0 - abs(latitudeDeg - obj.decDeg) > 0.0
}
