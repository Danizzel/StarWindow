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

    /**
     * Sonne und Mond — alles, dessen Position eine Funktion der Zeit ist.
     *
     * Eine eigene Art, weil sich für sie **jede** Frage anders beantwortet: Ihre Deklination ist
     * keine Konstante, ihre Größe und Helligkeit ändern sich, und was ein Durchgang durch ein
     * Fenster ist, muss für sie über die Zeit gerechnet statt aus einer festen Position abgeleitet
     * werden. Sie unter [OTHER] zu führen hieße, genau die Eigenschaft zu verstecken, an der
     * überall etwas anderes hängt.
     */
    @SerialName("SOLAR_SYSTEM") SOLAR_SYSTEM,

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
            SOLAR_SYSTEM -> "Sonnensystem"
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
    /**
     * Spectral classification of a star, e.g. `K1.5III`.
     *
     * Kept apart from [morphology] rather than sharing the field, because the two look alike and
     * mean nothing like each other: `S` opens a galaxy's spiral class and a star's spectral class,
     * and a shared field would have the description call an S-type giant a spiral galaxy.
     */
    val spectralType: String? = null,
    /**
     * Separation of a double star's components in arcseconds.
     *
     * The one number that decides whether a double is worth pointing at: below an arcsecond no
     * amateur instrument splits it, and past a few hundred the pair stops reading as a pair.
     */
    val separationArcsec: Double? = null,
    /** Three letter IAU constellation abbreviation. */
    val constellation: String? = null,
    /** Other designations this object is known under, for searching and for lookups. */
    val catalogIds: List<String> = emptyList(),
    /** Further common names beyond [name]. */
    val alternativeNames: List<String> = emptyList(),
    /** Which catalogue this came from, so mixed local/online results stay traceable. */
    val source: String = "local",
    /**
     * Gesetzt bei Sonne und Mond: Ihre Position kommt aus einer Ephemeride statt aus dem Katalog.
     *
     * Ein Feld statt einer Klassenhierarchie. Die Alternative wäre gewesen, [SkyObject] zu einer
     * Schnittstelle zu machen und 22.528 Einträge über eine virtuelle Methode zu führen, damit
     * zwei von ihnen sich anders verhalten — teuer an jeder Stelle, an der bisher eine
     * Datenklasse steht (Serialisierung, `copy`, Vergleich), und das für zwei Objekte. So bleibt
     * der Katalog, was er ist, die Dateien bleiben unverändert (`null` als Vorgabe), und die
     * wenigen Stellen, die tatsächlich über die Zeit rechnen, fragen [isMoving].
     */
    val body: EphemerisBody? = null,
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
    fun positionAt(precession: Precession): Equatorial =
        body?.let { return it.positionAt(precession.epochMillis) } ?: precession.toDate(equatorialJ2000)

    /**
     * Die Position zu einem Zeitpunkt, in Millisekunden.
     *
     * Für alles Feste dasselbe wie [positionAt] — nur eben mit der Präzession, die zu diesem
     * Zeitpunkt gehört. Für Sonne und Mond ist es der einzige richtige Weg: Ihre Position ist keine
     * Konstante, die man einmal auf das Datum bringt, sondern eine Funktion der Zeit, und sie
     * ändert sich innerhalb einer Nacht um Grade statt um Bogensekunden.
     *
     * Teurer als [positionAt] mit vorgerechneter Präzession, weshalb die Durchgangsrechnung
     * weiterhin nur dort je Abtastschritt neu rechnet, wo sich tatsächlich etwas bewegt.
     */
    fun positionAtMillis(millis: Long): Equatorial =
        body?.positionAt(millis) ?: positionAt(Precession.forEpoch(millis))

    /** True für Sonne und Mond: alles, was über die Zeit gerechnet werden muss. */
    val isMoving: Boolean get() = body != null

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
