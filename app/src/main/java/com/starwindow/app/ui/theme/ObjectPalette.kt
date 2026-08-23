package com.starwindow.app.ui.theme

import androidx.compose.ui.graphics.Color
import com.starwindow.app.data.catalog.ObjectType

/**
 * One colour per kind of object, used everywhere the same: in the result lists, on the symbols and
 * on the markers drawn over the camera image.
 *
 * The point is that "blue-ish round thing" means the same in the list as it does in the viewfinder,
 * so a row can be matched to a marker without reading either. The hues follow what the objects
 * roughly look like — hydrogen red for emission nebulae, dust blue for reflection nebulae — which
 * makes them easier to remember than an arbitrary assignment.
 *
 * All of them are light and desaturated enough to stay readable on the near-black night background
 * without being bright enough to hurt a dark-adapted eye.
 */
object ObjectPalette {

    val Star = Color(0xFFFFD97D)
    val Galaxy = Color(0xFFC9A7FF)
    val Emission = Color(0xFFFF8FA3)
    val Reflection = Color(0xFF8FC7FF)
    val Dark = Color(0xFF8A93AB)
    val Planetary = Color(0xFF7FE3D0)
    val Remnant = Color(0xFFFFA870)
    val OpenCluster = Color(0xFFA9E6A0)
    val GlobularCluster = Color(0xFFD9E88F)
    val Other = Color(0xFFB9C0D6)

    fun colorFor(type: ObjectType): Color = when (type) {
        ObjectType.STAR, ObjectType.DOUBLE_STAR -> Star
        ObjectType.GALAXY, ObjectType.GALAXY_GROUP -> Galaxy
        ObjectType.NEBULA, ObjectType.EMISSION_NEBULA -> Emission
        ObjectType.REFLECTION_NEBULA -> Reflection
        ObjectType.DARK_NEBULA -> Dark
        ObjectType.PLANETARY_NEBULA -> Planetary
        ObjectType.SUPERNOVA_REMNANT -> Remnant
        ObjectType.OPEN_CLUSTER, ObjectType.CLUSTER_NEBULA -> OpenCluster
        ObjectType.GLOBULAR_CLUSTER -> GlobularCluster
        ObjectType.OTHER -> Other
    }
}
