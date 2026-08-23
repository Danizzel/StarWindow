package com.starwindow.app.data.catalog

import com.starwindow.app.core.astro.Equatorial
import com.starwindow.app.core.astro.Precession
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What kind of thing an entry is.
 *
 * Finer grained than a visual observer needs, because photographers care about the difference: an
 * emission nebula wants a narrowband filter, a reflection nebula does not, and a dark nebula is
 * only visible against something brighter behind it.
 */
@Serializable
enum class ObjectType {
    @SerialName("STAR") STAR,
    @SerialName("DOUBLE_STAR") DOUBLE_STAR,
    @SerialName("GALAXY") GALAXY,
    @SerialName("GALAXY_GROUP") GALAXY_GROUP,
    @SerialName("NEBULA") NEBULA,
    @SerialName("EMISSION_NEBULA") EMISSION_NEBULA,
    @SerialName("REFLECTION_NEBULA") REFLECTION_NEBULA,
    @SerialName("DARK_NEBULA") DARK_NEBULA,
    @SerialName("PLANETARY_NEBULA") PLANETARY_NEBULA,
    @SerialName("SUPERNOVA_REMNANT") SUPERNOVA_REMNANT,
    @SerialName("OPEN_CLUSTER") OPEN_CLUSTER,
    @SerialName("GLOBULAR_CLUSTER") GLOBULAR_CLUSTER,
    @SerialName("CLUSTER_NEBULA") CLUSTER_NEBULA,
    @SerialName("OTHER") OTHER;

    val label: String
        get() = when (this) {
            STAR -> "Stern"
            DOUBLE_STAR -> "Doppelstern"
            GALAXY -> "Galaxie"
            GALAXY_GROUP -> "Galaxiengruppe"
            NEBULA -> "Nebel"
            EMISSION_NEBULA -> "Emissionsnebel"
            REFLECTION_NEBULA -> "Reflexionsnebel"
            DARK_NEBULA -> "Dunkelnebel"
            PLANETARY_NEBULA -> "Planetarischer Nebel"
            SUPERNOVA_REMNANT -> "Supernova-Überrest"
            OPEN_CLUSTER -> "Offener Sternhaufen"
            GLOBULAR_CLUSTER -> "Kugelsternhaufen"
            CLUSTER_NEBULA -> "Sternhaufen mit Nebel"
            OTHER -> "Sonstiges"
        }

    val isStar: Boolean get() = this == STAR || this == DOUBLE_STAR

    /** Emission-line objects, the ones a narrowband filter helps with. */
    val respondsToNarrowband: Boolean
        get() = this == EMISSION_NEBULA || this == PLANETARY_NEBULA ||
            this == SUPERNOVA_REMNANT || this == CLUSTER_NEBULA
}

/**
 * One catalogue entry.
 *
 * Coordinates are J2000 and are turned into today's sky by [positionAt]; proper motion is ignored
 * because at under an arcsecond a year it stays two orders of magnitude below what a phone can
 * point to, unlike precession, which does not.
 */
@Serializable
data class SkyObject(
    val id: String,
    val name: String,
    val type: ObjectType,
    val raDeg: Double,
    val decDeg: Double,
    /** Integrated visual magnitude; lower is brighter. Null when the catalogue gives none. */
    val magnitude: Double? = null,
    /** Apparent length of the long axis in arcminutes. */
    val sizeArcmin: Double? = null,
    /** Apparent length of the short axis in arcminutes. */
    val minorAxisArcmin: Double? = null,
    /** Orientation of the long axis, degrees east of north. */
    val positionAngleDeg: Double? = null,
    /**
     * Mean surface brightness in magnitudes per square arcminute.
     *
     * For photography this says more than the integrated magnitude: M31 is a bright object at 3.4
     * mag, but spread over three degrees it is far fainter per pixel than a 9 mag planetary nebula
     * a minute across.
     */
    val surfaceBrightness: Double? = null,
    /** Morphological classification, e.g. `SA(s)b` for a galaxy. */
    val morphology: String? = null,
    /** Three letter IAU constellation abbreviation. */
    val constellation: String? = null,
    /** Other designations this object is known under, for searching and for lookups. */
    val catalogIds: List<String> = emptyList(),
    /** Further common names beyond [name]. */
    val alternativeNames: List<String> = emptyList(),
    /** Which catalogue this came from, so mixed local/online results stay traceable. */
    val source: String = "local",
) {
    /**
     * The position as the catalogue records it, epoch J2000.
     *
     * Named for its epoch rather than just `equatorial`, because the difference matters: the sky
     * has turned about 0.37° away from the J2000 grid since, and a name that hides which frame a
     * coordinate is in is how that error gets used by accident. Anything that has to point at the
     * real sky goes through [positionAt].
     */
    val equatorialJ2000: Equatorial get() = Equatorial(raDeg, decDeg)

    /** Where the object actually stands at a given moment, precession since J2000 included. */
    fun positionAt(precession: Precession): Equatorial = precession.toDate(equatorialJ2000)

    val displayName: String get() = if (name.isBlank() || name == id) id else "$id · $name"

    /** Every designation this object answers to, primary first. */
    val allIdentifiers: List<String>
        get() = (listOf(id) + catalogIds).distinct()

    /** True when the object is large enough that its shape, not just its position, matters. */
    val isExtended: Boolean get() = (sizeArcmin ?: 0.0) >= 1.0

    /**
     * How the object would sit in a window of the given angular radius: the fraction of the
     * window's width it fills. Above 1 it does not fit.
     */
    fun fillFactor(windowRadiusDeg: Double): Double? {
        val size = sizeArcmin ?: return null
        if (windowRadiusDeg <= 0.0) return null
        return (size / 60.0) / (2.0 * windowRadiusDeg)
    }

    fun matches(query: String): Boolean {
        if (query.isBlank()) return true
        val needle = query.trim().lowercase()
        return name.lowercase().contains(needle) ||
            allIdentifiers.any { it.lowercase().contains(needle) } ||
            alternativeNames.any { it.lowercase().contains(needle) }
    }
}

/** File format of the bundled asset catalogues. */
@Serializable
data class CatalogFile(
    val version: Int = 1,
    val name: String = "",
    val epoch: String = "J2000",
    /** Attribution for catalogues that require it, e.g. OpenNGC under CC-BY-SA-4.0. */
    val license: String = "",
    val objects: List<SkyObject> = emptyList(),
)
