package com.starwindow.app.data.tracking

import com.starwindow.app.core.geometry.SkyWindow
import com.starwindow.app.core.geometry.WindowShape
import com.starwindow.app.data.catalog.ObjectType
import com.starwindow.app.data.catalog.SkyObject
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What the viewfinder is currently leading the user to.
 *
 * Two kinds, and they behave differently in a way that matters: a catalogue object is fixed to the
 * *sky* and therefore drifts across the viewfinder as the Earth turns, while a saved window is
 * fixed to the *horizon* and does not move at all. The arrow works the same for both, but only the
 * object needs sidereal time and precession to be resolved into a direction.
 */
@Serializable
sealed interface TrackTarget {

    /** What the bar at the bottom of the viewfinder calls it. */
    val label: String

    /** Stable identity, so a list can mark the row that is currently being tracked. */
    val id: String
}

/** A star, nebula or galaxy from the catalogue. */
@Serializable
@SerialName("object")
data class TrackedObject(val obj: SkyObject) : TrackTarget {
    override val label: String get() = obj.name.ifBlank { obj.id }
    override val id: String get() = obj.id
    val type: ObjectType get() = obj.type
}

/**
 * A window the user drew earlier.
 *
 * The outline travels with the target rather than being looked up from the repository: the arrow
 * then keeps working while the list is still loading, and the viewfinder can draw the window's own
 * shape once the user has turned far enough to see it — which is the whole point of walking back to
 * a saved window.
 */
@Serializable
@SerialName("window")
data class TrackedWindow(
    val windowId: String,
    val name: String,
    val shape: WindowShape,
) : TrackTarget {
    override val label: String get() = name
    override val id: String get() = windowId

    companion object {
        fun of(window: SkyWindow) = TrackedWindow(window.id, window.name, window.shape)
    }
}
