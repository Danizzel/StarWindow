package com.starwindow.app.data.catalog

import com.starwindow.app.core.astro.Equatorial
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Broad classification, enough to colour-code the overlay and to filter lists. */
@Serializable
enum class ObjectType {
    @SerialName("STAR") STAR,
    @SerialName("DOUBLE_STAR") DOUBLE_STAR,
    @SerialName("GALAXY") GALAXY,
    @SerialName("NEBULA") NEBULA,
    @SerialName("PLANETARY_NEBULA") PLANETARY_NEBULA,
    @SerialName("SUPERNOVA_REMNANT") SUPERNOVA_REMNANT,
    @SerialName("OPEN_CLUSTER") OPEN_CLUSTER,
    @SerialName("GLOBULAR_CLUSTER") GLOBULAR_CLUSTER,
    @SerialName("OTHER") OTHER;

    val label: String
        get() = when (this) {
            STAR -> "Stern"
            DOUBLE_STAR -> "Doppelstern"
            GALAXY -> "Galaxie"
            NEBULA -> "Nebel"
            PLANETARY_NEBULA -> "Planetarischer Nebel"
            SUPERNOVA_REMNANT -> "Supernova-Überrest"
            OPEN_CLUSTER -> "Offener Sternhaufen"
            GLOBULAR_CLUSTER -> "Kugelsternhaufen"
            OTHER -> "Sonstiges"
        }
}

/**
 * One catalogue entry. Coordinates are J2000; proper motion is ignored because it is orders of
 * magnitude below the pointing accuracy this app can achieve.
 */
@Serializable
data class SkyObject(
    val id: String,
    val name: String,
    val type: ObjectType,
    val raDeg: Double,
    val decDeg: Double,
    /** Visual magnitude; lower is brighter. Null when the catalogue does not give one. */
    val magnitude: Double? = null,
    /** Apparent diameter of the long axis in arcminutes, for extended objects. */
    val sizeArcmin: Double? = null,
    /** Three letter IAU constellation abbreviation. */
    val constellation: String? = null,
    /** Which catalogue this came from, so mixed local/online results stay traceable. */
    val source: String = "local",
) {
    val equatorial: Equatorial get() = Equatorial(raDeg, decDeg)

    val displayName: String get() = if (name.isBlank() || name == id) id else "$id · $name"
}

/** File format of the bundled asset catalogue. */
@Serializable
data class CatalogFile(
    val version: Int = 1,
    val name: String = "",
    val epoch: String = "J2000",
    val objects: List<SkyObject> = emptyList(),
)
