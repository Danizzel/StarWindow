package com.starwindow.app.domain

import com.starwindow.app.data.catalog.ObjectType
import com.starwindow.app.data.catalog.SkyObject

/**
 * Which kind of thing the result list shows.
 *
 * Lives in the domain rather than next to the screen: deciding what counts as a photographic
 * target is a judgement about the data, not about layout, and here it can be tested without an
 * Android runtime.
 */
enum class ResultFilter(val label: String) {
    ALL("Alle"),
    PHOTO("Fotoziele"),
    CONSTELLATIONS("Sternbilder"),
    STARS("Sterne"),
    NEBULAE("Nebel"),
    GALAXIES("Galaxien"),
    CLUSTERS("Haufen");

    fun matches(obj: SkyObject): Boolean = when (this) {
        ALL -> true
        CONSTELLATIONS -> false
        // What someone points a camera at: something with a name and enough size to be worth
        // framing. A ninth-magnitude anonymous galaxy half an arcminute across is a fine visual
        // tick, but nobody plans a night around it.
        PHOTO -> !obj.type.isStar &&
            (obj.name.isNotBlank() || (obj.sizeArcmin ?: 0.0) >= PHOTO_MIN_SIZE_ARCMIN)
        STARS -> obj.type.isStar
        NEBULAE -> obj.type == ObjectType.NEBULA ||
            obj.type == ObjectType.EMISSION_NEBULA ||
            obj.type == ObjectType.REFLECTION_NEBULA ||
            obj.type == ObjectType.DARK_NEBULA ||
            obj.type == ObjectType.PLANETARY_NEBULA ||
            obj.type == ObjectType.SUPERNOVA_REMNANT ||
            obj.type == ObjectType.CLUSTER_NEBULA
        GALAXIES -> obj.type == ObjectType.GALAXY || obj.type == ObjectType.GALAXY_GROUP
        CLUSTERS -> obj.type == ObjectType.OPEN_CLUSTER || obj.type == ObjectType.GLOBULAR_CLUSTER
    }

    val showsConstellations: Boolean get() = this == ALL || this == CONSTELLATIONS
    val showsObjects: Boolean get() = this != CONSTELLATIONS

    companion object {
        const val PHOTO_MIN_SIZE_ARCMIN = 3.0
    }
}
