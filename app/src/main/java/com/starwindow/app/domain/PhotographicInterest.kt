package com.starwindow.app.domain

import com.starwindow.app.data.catalog.ObjectType
import com.starwindow.app.data.catalog.SkyObject
import kotlin.math.min

/**
 * How rewarding an entry is to actually photograph.
 *
 * The catalogue holds twenty-two thousand objects and treats them as equals; a person does not.
 * Between M42 and an anonymous fourteenth-magnitude galaxy half an arcminute across lies the whole
 * difference between a target and a data point, and nothing in the catalogue row says which is
 * which. This is the one place in the app that decides it — used by the suggestion board, by the
 * "photo targets" filter, and by the viewfinder overlay, so that all three agree on what counts.
 *
 * The ranking is built around **angular size**, not brightness, and that is the whole point.
 * Brightness ranks a visual observer's night: what is bright is easy to see. A camera has the
 * opposite problem — it can integrate light for minutes but cannot invent resolution, so a faint
 * three-degree nebula is a fine photograph and a bright half-arcminute smudge is not. The
 * California Nebula appears in no visual list worth the name and is a standard target here.
 *
 * Stars sit at the bottom on purpose. They carry no structure, and a photograph of one is a dot —
 * they belong in the viewfinder for orientation and in the search for identification, not in a list
 * of things to point a camera at. Double stars are the exception the rule needs: a coloured pair
 * genuinely is a picture.
 *
 * Pure Kotlin, no Android: a judgement about data, and the kind that is worth testing.
 */
object PhotographicInterest {

    /** Below this an object is a dot on any sensor a phone or small telescope carries. */
    const val MIN_USEFUL_SIZE_ARCMIN = 1.5

    /** Past this, size stops adding: everything this large is already framed by focal length. */
    private const val SIZE_SATURATION_ARCMIN = 90.0

    /** What a named object is worth. Somebody named it because people look at it. */
    private const val NAMED_BONUS = 22.0

    /** Messier objects are the canonical target list; four centuries of agreement. */
    private const val MESSIER_BONUS = 18.0

    /**
     * How rewarding this object is as a photographic target, from 0 to roughly 100.
     *
     * Deliberately a smooth score rather than a yes/no: the same number has to order a suggestion
     * list, decide what the overlay draws when it runs out of room, and answer whether an entry is
     * a "photo target" at all. A threshold can be derived from a score; an ordering cannot be
     * recovered from a threshold.
     */
    fun score(obj: SkyObject): Double {
        if (obj.type.isStar) return starScore(obj)

        var score = sizeScore(obj)
        if (obj.name.isNotBlank()) score += NAMED_BONUS
        if (isMessier(obj)) score += MESSIER_BONUS
        score += brightnessScore(obj)
        score += typeScore(obj.type)
        return score.coerceIn(0.0, 100.0)
    }

    /**
     * Whether the object is worth offering as a target at all.
     *
     * The cut sits where a catalogue entry stops describing something anyone would frame: too
     * small to resolve and with nothing — no name, no Messier number — to say otherwise.
     */
    fun isPhotoTarget(obj: SkyObject): Boolean {
        if (obj.type == ObjectType.DOUBLE_STAR) return true
        if (obj.type.isStar) return false
        if (obj.name.isNotBlank() || isMessier(obj)) return true
        return (obj.sizeArcmin ?: 0.0) >= MIN_USEFUL_SIZE_ARCMIN
    }

    /**
     * Size, on a curve that flattens.
     *
     * The step from half an arcminute to five is the difference between a dot and a subject; the
     * step from sixty to ninety changes only which lens is used. A square root gives exactly that
     * shape without a table of thresholds pretending to be knowledge.
     */
    private fun sizeScore(obj: SkyObject): Double {
        val size = obj.sizeArcmin ?: return 0.0
        if (size < MIN_USEFUL_SIZE_ARCMIN) return 0.0
        return 42.0 * Math.sqrt(min(size, SIZE_SATURATION_ARCMIN) / SIZE_SATURATION_ARCMIN)
    }

    /**
     * Brightness, but surface brightness where the catalogue knows it.
     *
     * For an extended object the integrated magnitude is close to meaningless — M31 is a 3.4 mag
     * object spread over three degrees, and per pixel it is fainter than a 9 mag planetary nebula
     * an arcminute across. Where surface brightness exists it answers the real question; where it
     * does not, the integrated magnitude is the only thing left.
     */
    private fun brightnessScore(obj: SkyObject): Double {
        obj.surfaceBrightness?.let { surface ->
            // Roughly 20 mag/□′ is bright enough for a city sky, 25 needs a genuinely dark one.
            return ((25.0 - surface) * 4.0).coerceIn(0.0, 20.0)
        }
        val magnitude = obj.magnitude ?: return 4.0
        return ((13.0 - magnitude) * 1.6).coerceIn(0.0, 20.0)
    }

    /** A small nudge for the kinds that reward a camera more than an eye. */
    private fun typeScore(type: ObjectType): Double = when (type) {
        ObjectType.EMISSION_NEBULA, ObjectType.CLUSTER_NEBULA -> 8.0
        ObjectType.REFLECTION_NEBULA, ObjectType.SUPERNOVA_REMNANT -> 7.0
        ObjectType.GALAXY, ObjectType.PLANETARY_NEBULA, ObjectType.GLOBULAR_CLUSTER -> 5.0
        ObjectType.NEBULA, ObjectType.OPEN_CLUSTER, ObjectType.GALAXY_GROUP -> 4.0
        // A dark nebula is only visible against something brighter behind it, and whether that
        // something is there is not in the catalogue.
        ObjectType.DARK_NEBULA -> 2.0
        else -> 0.0
    }

    /**
     * Stars, kept low but not at zero.
     *
     * A bright star is not a target, but it is the thing the viewfinder is aimed at to check that
     * the projection is right, and the thing the star calibration measures against — so brightness
     * still has to order them. A wide coloured double is a genuine subject and scores as one.
     */
    private fun starScore(obj: SkyObject): Double {
        val magnitude = obj.magnitude ?: 7.0
        val brightness = ((6.5 - magnitude) * 3.0).coerceIn(0.0, 25.0)
        if (obj.type != ObjectType.DOUBLE_STAR) return brightness
        // Separable and reasonably bright: Albireo is a picture, an 8" pair at 6 mag is not.
        val separation = obj.separationArcsec ?: return brightness
        val splittable = if (separation >= 5.0) 18.0 else 6.0
        return (brightness + splittable).coerceAtMost(55.0)
    }

    private fun isMessier(obj: SkyObject): Boolean =
        obj.allIdentifiers.any { it.length in 2..4 && it.startsWith("M") && it.drop(1).all(Char::isDigit) }
}
