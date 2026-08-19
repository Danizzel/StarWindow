package com.starwindow.app

import com.starwindow.app.core.astro.Horizontal
import com.starwindow.app.core.geometry.AltAzBoxWindow
import com.starwindow.app.core.geometry.CircleWindow
import com.starwindow.app.core.geometry.PolygonWindow
import com.starwindow.app.core.geometry.SphericalGeometry
import com.starwindow.app.core.geometry.TangentPlane
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SphericalGeometryTest {

    @Test
    fun `separation of orthogonal directions is ninety degrees`() {
        assertEquals(
            90.0,
            SphericalGeometry.separationDeg(Horizontal(0.0, 0.0), Horizontal(90.0, 0.0)),
            1e-9,
        )
        assertEquals(
            90.0,
            SphericalGeometry.separationDeg(Horizontal(0.0, 0.0), Horizontal(0.0, 90.0)),
            1e-9,
        )
    }

    @Test
    fun `separation stays accurate for very small angles`() {
        val a = Horizontal(10.0, 45.0)
        val b = Horizontal(10.0, 45.001)
        assertEquals(0.001, SphericalGeometry.separationDeg(a, b), 1e-9)
    }

    @Test
    fun `an octant of the sphere has an eighth of its area`() {
        val octant = listOf(
            Horizontal(0.0, 0.0),
            Horizontal(90.0, 0.0),
            Horizontal(0.0, 90.0),
        )
        val fullSphereSquareDeg = 4.0 * Math.PI * (180.0 / Math.PI) * (180.0 / Math.PI)
        assertEquals(
            fullSphereSquareDeg / 8.0,
            abs(SphericalGeometry.signedAreaSquareDeg(octant)),
            1e-6,
        )
    }

    @Test
    fun `a hemisphere cap is half the sphere`() {
        val fullSphereSquareDeg = 4.0 * Math.PI * (180.0 / Math.PI) * (180.0 / Math.PI)
        assertEquals(fullSphereSquareDeg / 2.0, SphericalGeometry.capAreaSquareDeg(90.0), 1e-6)
    }

    @Test
    fun `a small cap is close to the flat circle area`() {
        val radius = 2.0
        val flat = Math.PI * radius * radius
        assertEquals(flat, SphericalGeometry.capAreaSquareDeg(radius), flat * 0.001)
    }

    @Test
    fun `the altitude band above the horizon is half the visible sky`() {
        // A full 360 degree band from horizon to zenith is a hemisphere.
        val fullSphereSquareDeg = 4.0 * Math.PI * (180.0 / Math.PI) * (180.0 / Math.PI)
        assertEquals(
            fullSphereSquareDeg / 2.0,
            SphericalGeometry.altAzBoxAreaSquareDeg(360.0, 0.0, 90.0),
            1e-6,
        )
    }

    @Test
    fun `tangent plane round trips`() {
        val plane = TangentPlane(Horizontal(137.0, 41.0))
        for (dAz in -20..20 step 7) {
            for (dAlt in -20..20 step 7) {
                val point = Horizontal(137.0 + dAz, (41.0 + dAlt).coerceIn(-89.0, 89.0))
                val projected = plane.project(point)
                assertTrue(projected != null)
                val back = plane.unproject(projected!!)
                assertTrue(
                    SphericalGeometry.separationDeg(point, back) < 1e-9,
                    "round trip failed for $point",
                )
            }
        }
    }

    @Test
    fun `the far hemisphere does not project`() {
        val plane = TangentPlane(Horizontal(0.0, 0.0))
        assertTrue(plane.project(Horizontal(180.0, 0.0)) == null)
    }
}

class WindowShapeTest {

    @Test
    fun `a circle contains its centre and rejects points outside the radius`() {
        val circle = CircleWindow(Horizontal(120.0, 35.0), 5.0)
        assertTrue(circle.contains(Horizontal(120.0, 35.0)))
        assertTrue(circle.contains(Horizontal(120.0, 39.9)))
        assertFalse(circle.contains(Horizontal(120.0, 41.0)))
        assertEquals(5.0, circle.angularRadiusDeg(), 1e-9)
    }

    @Test
    fun `a circle built from two taps has the tapped radius`() {
        val center = Horizontal(200.0, 20.0)
        val edge = Horizontal(207.0, 20.0)
        val circle = CircleWindow.fromCenterAndEdge(center, edge)
        assertEquals(SphericalGeometry.separationDeg(center, edge), circle.radiusDeg, 1e-9)
        assertTrue(circle.contains(edge.copy(azimuthDeg = 206.9)))
    }

    @Test
    fun `a circle's outline sits exactly on its rim`() {
        val circle = CircleWindow(Horizontal(45.0, 60.0), 8.0)
        circle.outline(64).forEach {
            assertEquals(8.0, SphericalGeometry.separationDeg(circle.centerPoint, it), 1e-9)
        }
    }

    @Test
    fun `a box survives the zero degree seam`() {
        // From 350 degrees eastwards to 10 degrees: the short way is across north.
        val box = AltAzBoxWindow.fromCorners(Horizontal(350.0, 10.0), Horizontal(10.0, 30.0))
        assertEquals(350.0, box.azimuthStartDeg, 1e-9)
        assertEquals(20.0, box.azimuthSpanDeg, 1e-9)
        assertTrue(box.contains(Horizontal(0.0, 20.0)))
        assertTrue(box.contains(Horizontal(355.0, 11.0)))
        assertFalse(box.contains(Horizontal(180.0, 20.0)))
        assertFalse(box.contains(Horizontal(0.0, 31.0)))
    }

    @Test
    fun `a box picks the short way around regardless of tap order`() {
        val a = AltAzBoxWindow.fromCorners(Horizontal(10.0, 30.0), Horizontal(350.0, 10.0))
        assertEquals(350.0, a.azimuthStartDeg, 1e-9)
        assertEquals(20.0, a.azimuthSpanDeg, 1e-9)
    }

    @Test
    fun `a polygon contains its centroid and excludes far away points`() {
        val polygon = PolygonWindow(
            listOf(
                Horizontal(100.0, 30.0),
                Horizontal(110.0, 30.0),
                Horizontal(110.0, 40.0),
                Horizontal(100.0, 40.0),
            )
        )
        assertTrue(polygon.contains(Horizontal(105.0, 35.0)))
        assertFalse(polygon.contains(Horizontal(120.0, 35.0)))
        assertFalse(polygon.contains(Horizontal(105.0, 55.0)))
    }

    @Test
    fun `a concave polygon excludes the notch`() {
        // An L shape: the missing quadrant must not count as inside.
        val polygon = PolygonWindow(
            listOf(
                Horizontal(100.0, 30.0),
                Horizontal(112.0, 30.0),
                Horizontal(112.0, 36.0),
                Horizontal(106.0, 36.0),
                Horizontal(106.0, 42.0),
                Horizontal(100.0, 42.0),
            )
        )
        assertTrue(polygon.contains(Horizontal(102.0, 33.0)))
        assertTrue(polygon.contains(Horizontal(102.0, 40.0)))
        assertFalse(polygon.contains(Horizontal(110.0, 40.0)))
    }

    @Test
    fun `a polygon's area is close to the equivalent box`() {
        val polygon = PolygonWindow(
            listOf(
                Horizontal(100.0, 30.0),
                Horizontal(110.0, 30.0),
                Horizontal(110.0, 40.0),
                Horizontal(100.0, 40.0),
            )
        )
        val box = AltAzBoxWindow.fromCorners(Horizontal(100.0, 30.0), Horizontal(110.0, 40.0))
        // The polygon's edges are great circles and the box's are altitude parallels, so the two
        // differ slightly — but only by a couple of percent at this size.
        assertEquals(box.areaSquareDeg(), polygon.areaSquareDeg(), box.areaSquareDeg() * 0.05)
    }

    @Test
    fun `the membership test agrees with contains`() {
        val polygon = PolygonWindow(
            listOf(
                Horizontal(200.0, 10.0),
                Horizontal(215.0, 12.0),
                Horizontal(210.0, 25.0),
            )
        )
        val test = polygon.membershipTest()
        for (az in 195..220 step 2) {
            for (alt in 5..30 step 2) {
                val point = Horizontal(az.toDouble(), alt.toDouble())
                assertEquals(polygon.contains(point), test(point), "disagreement at $point")
            }
        }
    }
}
