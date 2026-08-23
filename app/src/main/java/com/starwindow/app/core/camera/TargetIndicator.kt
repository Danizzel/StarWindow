package com.starwindow.app.core.camera

import com.starwindow.app.core.astro.Horizontal
import com.starwindow.app.core.geometry.SphericalGeometry
import kotlin.math.hypot

/**
 * How much of each edge of the view is covered by something else — the status band at the top, the
 * controls at the bottom.
 *
 * The arrow has to stay out of those, or it ends up drawn behind the very controls the user is
 * looking past. All values are in view pixels.
 */
data class EdgeInsets(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 0f,
    val bottom: Float = 0f,
) {
    operator fun plus(other: EdgeInsets) = EdgeInsets(
        left + other.left,
        top + other.top,
        right + other.right,
        bottom + other.bottom,
    )

    companion object {
        val NONE = EdgeInsets()

        fun uniform(value: Float) = EdgeInsets(value, value, value, value)
    }
}

/**
 * Where to draw the marker for a target the user is trying to find.
 *
 * @param x/[y] view pixels; on the target itself while it is visible, pinned to the edge otherwise.
 * @param onScreen true while the target is actually inside the viewfinder.
 * @param dirX/[dirY] unit vector from the centre of the view towards the target, in screen
 *   coordinates (y grows downwards) — the direction the arrow has to be rotated to.
 * @param separationDeg angle between the centre of the viewfinder and the target: how far the
 *   phone still has to swing.
 */
data class TargetMarker(
    val x: Float,
    val y: Float,
    val onScreen: Boolean,
    val dirX: Float,
    val dirY: Float,
    val separationDeg: Double,
) {
    /** Rotation for an arrow drawn pointing right (+x) in its own coordinates, in degrees. */
    val arrowRotationDeg: Float
        get() = Math.toDegrees(kotlin.math.atan2(dirY, dirX).toDouble()).toFloat()
}

/**
 * Turns "this object is somewhere out there" into something drawable.
 *
 * The interesting half is the target that is *not* in the picture. A pinhole projection says
 * nothing useful about it — behind the camera it even flips sign — so the direction is taken from
 * the target's vector in the display frame instead, whose x/y components point the way the phone
 * has to turn no matter where the target sits, including straight behind the observer. The marker
 * is then pushed out along that direction until it hits the usable rectangle, which is the standard
 * ray-versus-rectangle clamp used for off-screen indicators in games and AR.
 */
object TargetIndicator {

    fun locate(
        projection: SkyProjection,
        target: Horizontal,
        insets: EdgeInsets = EdgeInsets.NONE,
    ): TargetMarker? {
        if (!projection.isUsable) return null

        val attitude = projection.attitude
        val display = attitude.worldToDisplay(attitude.toMagneticVector(target))
        val separation = SphericalGeometry.separationDeg(projection.centerDirection, target)

        // Display frame y grows upwards, screen y downwards.
        val rawX = display.x.toFloat()
        val rawY = -display.y.toFloat()
        val length = hypot(rawX, rawY)
        // Dead centre or exactly behind: no direction to point in. Down is as good as any, and it
        // only ever shows for the fraction of a second it takes to swing past.
        val dirX = if (length < 1e-6f) 0f else rawX / length
        val dirY = if (length < 1e-6f) 1f else rawY / length

        val inFront = display.z < -1e-4
        if (inFront) {
            val point = projection.skyToScreen(target)
            if (point != null &&
                point.x >= 0f && point.x <= projection.viewWidthPx &&
                point.y >= 0f && point.y <= projection.viewHeightPx
            ) {
                // Judged against the whole view, not the inset one: an object behind a translucent
                // control band is still in the picture, and a ring is a better answer than an arrow.
                return TargetMarker(point.x, point.y, true, dirX, dirY, separation)
            }
        }

        val centerX = projection.viewWidthPx / 2f
        val centerY = projection.viewHeightPx / 2f
        val left = insets.left
        val top = insets.top
        val right = projection.viewWidthPx - insets.right
        val bottom = projection.viewHeightPx - insets.bottom

        // Distance along the ray to the first edge of the usable rectangle it runs into. Only the
        // sides the ray actually heads towards can be hit.
        var reach = Float.MAX_VALUE
        if (dirX > 1e-6f) reach = minOf(reach, (right - centerX) / dirX)
        if (dirX < -1e-6f) reach = minOf(reach, (left - centerX) / dirX)
        if (dirY > 1e-6f) reach = minOf(reach, (bottom - centerY) / dirY)
        if (dirY < -1e-6f) reach = minOf(reach, (top - centerY) / dirY)
        // Insets larger than the view itself would put the centre outside the rectangle and give a
        // negative reach; the coercion below keeps the marker on the screen regardless.
        if (reach == Float.MAX_VALUE || reach < 0f) reach = 0f

        return TargetMarker(
            x = (centerX + dirX * reach).coerceIn(0f, projection.viewWidthPx),
            y = (centerY + dirY * reach).coerceIn(0f, projection.viewHeightPx),
            onScreen = false,
            dirX = dirX,
            dirY = dirY,
            separationDeg = separation,
        )
    }
}
