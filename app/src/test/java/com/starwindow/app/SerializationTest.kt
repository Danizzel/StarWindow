package com.starwindow.app

import com.starwindow.app.core.astro.Horizontal
import com.starwindow.app.core.astro.ObserverLocation
import com.starwindow.app.core.geometry.AltAzBoxWindow
import com.starwindow.app.core.geometry.CircleWindow
import com.starwindow.app.core.geometry.PolygonWindow
import com.starwindow.app.core.geometry.SkyWindow
import com.starwindow.app.core.geometry.SphericalGeometry
import com.starwindow.app.core.geometry.WindowShape
import com.starwindow.app.data.catalog.CatalogFile
import com.starwindow.app.data.catalog.ObjectType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

class SerializationTest {

    private val observer = ObserverLocation(52.52, 13.405, 34.0, 12f, manual = false)

    private fun window(shape: WindowShape) = SkyWindow(
        id = "abc",
        name = "Lücke über der Garage",
        shape = shape,
        observer = observer,
        capturedAtMillis = 1_700_000_000_000L,
        magneticDeclinationDeg = 3.7,
        compassAccuracy = 3,
        cameraFovDeg = 61.2,
        notes = "Test",
    )

    @Test
    fun `every window shape survives a JSON round trip`() {
        val shapes = listOf(
            PolygonWindow(
                listOf(Horizontal(100.0, 30.0), Horizontal(110.0, 30.0), Horizontal(105.0, 42.0))
            ),
            CircleWindow(Horizontal(200.0, 25.0), 6.5),
            AltAzBoxWindow(350.0, 20.0, 10.0, 30.0),
        )

        for (shape in shapes) {
            val original = window(shape)
            val text = json.encodeToString(original)
            val restored = json.decodeFromString<SkyWindow>(text)
            assertEquals(original, restored, "round trip failed for ${shape::class.simpleName}")
            assertEquals(shape, restored.shape)
        }
    }

    @Test
    fun `the polymorphic discriminator is the readable shape name`() {
        val text = json.encodeToString(window(CircleWindow(Horizontal(200.0, 25.0), 6.5)))
        assertTrue(text.contains("\"type\": \"circle\""), "unexpected JSON:\n$text")

        val polygon = json.encodeToString(
            window(
                PolygonWindow(
                    listOf(Horizontal(1.0, 2.0), Horizontal(3.0, 4.0), Horizontal(5.0, 6.0))
                )
            )
        )
        assertTrue(polygon.contains("\"type\": \"polygon\""), "unexpected JSON:\n$polygon")
        assertTrue(
            json.encodeToString(window(AltAzBoxWindow(0.0, 10.0, 0.0, 10.0))).contains("\"box\"")
        )
    }

    @Test
    fun `a list of windows round trips like the repository stores it`() {
        val windows = listOf(
            window(CircleWindow(Horizontal(10.0, 20.0), 3.0)),
            window(AltAzBoxWindow(100.0, 15.0, 5.0, 25.0)).copy(id = "second"),
        )
        val restored = json.decodeFromString<List<SkyWindow>>(json.encodeToString(windows))
        assertEquals(windows, restored)
    }

    @Test
    fun `the bundled catalogue parses and looks sane`() {
        val text = catalogFile().readText()
        val catalog = Json { ignoreUnknownKeys = true }.decodeFromString<CatalogFile>(text)

        assertEquals("J2000", catalog.epoch)
        assertTrue(catalog.objects.size > 100, "expected a usable catalogue, got ${catalog.objects.size}")
        assertEquals(catalog.objects.size, catalog.objects.map { it.id }.toSet().size, "duplicate ids")

        for (obj in catalog.objects) {
            assertTrue(obj.raDeg in 0.0..360.0, "${obj.id} has RA ${obj.raDeg}")
            assertTrue(obj.decDeg in -90.0..90.0, "${obj.id} has Dec ${obj.decDeg}")
            assertTrue(obj.id.isNotBlank())
        }

        // Spot checks against published J2000 positions.
        val sirius = catalog.objects.single { it.name == "Sirius" }
        assertEquals(101.287, sirius.raDeg, 0.01)
        assertEquals(-16.716, sirius.decDeg, 0.01)
        assertEquals(ObjectType.STAR, sirius.type)

        val andromeda = catalog.objects.single { it.id == "M31" }
        assertEquals(10.685, andromeda.raDeg, 0.01)
        assertEquals(41.269, andromeda.decDeg, 0.01)
        assertEquals(ObjectType.GALAXY, andromeda.type)

        val polaris = catalog.objects.single { it.name == "Polaris" }
        assertTrue(polaris.decDeg > 89.0, "Polaris should sit next to the pole")
    }

    /**
     * Unit tests run with the module directory as their working directory, so the bundled asset
     * can be read straight off disk — no Robolectric needed just to parse a JSON file.
     */
    private fun catalogFile(): File {
        val candidates = listOf(
            "src/main/assets/$CATALOG_ASSET",
            "app/src/main/assets/$CATALOG_ASSET",
        )
        return candidates.map { path -> File(path) }.firstOrNull { it.exists() }
            ?: error("Basiskatalog nicht gefunden, gesucht in: $candidates")
    }

    companion object {
        const val CATALOG_ASSET = "catalog/starwindow_core.json"
    }
}

class CalibrationSerializationTest {

    @Test
    fun `a calibration round trips including its provenance`() {
        val original = com.starwindow.app.core.calibration.Calibration.NONE
            .withAttitude(
                rotation = com.starwindow.app.core.astro.Rotation3.fromRotationVector(
                    com.starwindow.app.core.astro.Vec3(0.01, -0.02, 0.13)
                ),
                source = com.starwindow.app.core.calibration.CalibrationSource.STAR_PATTERN,
                residualDeg = 0.42,
                sampleCount = 3,
                atMillis = 1_700_000_000_000L,
            )
            .withFov(
                scale = 1.062,
                source = com.starwindow.app.core.calibration.CalibrationSource.PAN_SWEEP,
                residualDeg = 0.11,
                sampleCount = 2,
                atMillis = 1_700_000_100_000L,
            )

        val restored = json.decodeFromString<com.starwindow.app.core.calibration.Calibration>(
            json.encodeToString(original)
        )
        assertEquals(original, restored)
        assertEquals(original.correction.angleDeg(), restored.correction.angleDeg(), 1e-9)
        assertEquals(
            com.starwindow.app.core.calibration.CalibrationSource.PAN_SWEEP,
            restored.fovSource,
        )
    }

    @Test
    fun `exposure settings round trip`() {
        val original = com.starwindow.app.core.camera.ExposureSettings(
            mode = com.starwindow.app.core.camera.ExposureMode.NIGHT,
            exposureTimeNs = 500_000_000L,
            iso = 3200,
            focusAtInfinity = true,
            exposureCompensationSteps = 4,
        )
        val restored = json.decodeFromString<com.starwindow.app.core.camera.ExposureSettings>(
            json.encodeToString(original)
        )
        assertEquals(original, restored)
        assertEquals("1/2 s", restored.formatExposureTime())
    }

    @Test
    fun `an unknown future field does not break reading`() {
        // The settings blob is written by whatever version last ran; a newer one must not brick it.
        val text = """{"fovScale":1.1,"fovSource":"MANUAL","somethingNew":42}"""
        val restored = json.decodeFromString<com.starwindow.app.core.calibration.Calibration>(text)
        assertEquals(1.1, restored.fovScale, 1e-9)
    }
}

class ConstellationDataTest {

    private fun load(): com.starwindow.app.data.catalog.ConstellationFile {
        val file = listOf(
            "src/main/assets/catalog/constellations.json",
            "app/src/main/assets/catalog/constellations.json",
        ).map { File(it) }.firstOrNull { it.exists() }
            ?: error("Sternbilddatei nicht gefunden")
        return Json { ignoreUnknownKeys = true }
            .decodeFromString<com.starwindow.app.data.catalog.ConstellationFile>(file.readText())
    }

    @Test
    fun `the figure file parses and every index is valid`() {
        val data = load()
        assertEquals("J2000", data.epoch)
        assertTrue(data.constellations.size >= 25, "got ${data.constellations.size} constellations")

        val ids = data.constellations.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "duplicate constellation ids")

        for (constellation in data.constellations) {
            assertTrue(constellation.stars.isNotEmpty(), "${constellation.id} has no stars")
            assertTrue(constellation.name.isNotBlank())
            assertEquals(3, constellation.id.length, "${constellation.id} is not an IAU abbreviation")

            for (star in constellation.stars) {
                assertTrue(star.raDeg in 0.0..360.0, "${constellation.id}/${star.name}: RA ${star.raDeg}")
                assertTrue(star.decDeg in -90.0..90.0, "${constellation.id}/${star.name}: Dec ${star.decDeg}")
            }
            // Every declared line must resolve, or part of the figure would silently vanish.
            assertEquals(
                constellation.lines.size,
                constellation.segments.size,
                "${constellation.id} has line indices pointing nowhere",
            )
        }
    }

    @Test
    fun `no figure segment spans an implausible distance`() {
        // The real check on 200 hand-entered coordinates: a typo in an hour or a degree throws a
        // star far away, and the segment it belongs to becomes absurdly long. Real figures stay
        // well under twenty degrees per segment.
        val data = load()
        var worst = 0.0
        var worstLabel = ""

        for (constellation in data.constellations) {
            for ((a, b) in constellation.segments) {
                val separation = SphericalGeometry.separationDeg(
                    Horizontal(a.raDeg, a.decDeg),
                    Horizontal(b.raDeg, b.decDeg),
                )
                if (separation > worst) {
                    worst = separation
                    worstLabel = "${constellation.id}: ${a.name} – ${b.name}"
                }
            }
        }
        assertTrue(worst < 25.0, "longest segment was %.1f° ($worstLabel)".format(worst))
    }

    @Test
    fun `spot checks against published positions`() {
        val byId = load().constellations.associateBy { it.id }

        val orion = byId.getValue("Ori")
        val betelgeuse = orion.stars.single { it.name == "Beteigeuze" }
        assertEquals(88.793, betelgeuse.raDeg, 0.01)
        assertEquals(7.407, betelgeuse.decDeg, 0.01)
        assertEquals(7, orion.stars.size)

        val bigDipper = byId.getValue("UMa")
        assertEquals(7, bigDipper.stars.size, "the Wagen has seven stars")
        val dubhe = bigDipper.stars.single { it.name == "Dubhe" }
        assertEquals(165.932, dubhe.raDeg, 0.01)
        assertEquals(61.751, dubhe.decDeg, 0.01)

        val polaris = byId.getValue("UMi").stars.single { it.name == "Polaris" }
        assertTrue(polaris.decDeg > 89.0, "Polaris should sit next to the pole")

        val crux = byId.getValue("Cru")
        assertEquals(4, crux.stars.size)
        assertEquals(2, crux.segments.size, "the Southern Cross is two crossing bars")
    }

    @Test
    fun `stars shared between figures agree with each other`() {
        // Elnath belongs to both Taurus and Auriga; if the two entries disagreed, one of them is a
        // typo.
        val byId = load().constellations.associateBy { it.id }
        val fromTaurus = byId.getValue("Tau").stars.single { it.name == "Elnath" }
        val fromAuriga = byId.getValue("Aur").stars.single { it.name == "Elnath" }
        assertEquals(fromTaurus.raDeg, fromAuriga.raDeg, 1e-6)
        assertEquals(fromTaurus.decDeg, fromAuriga.decDeg, 1e-6)

        val fromAndromeda = byId.getValue("And").stars.single { it.name == "Alpheratz" }
        val fromPegasus = byId.getValue("Peg").stars.single { it.name == "Alpheratz" }
        assertEquals(fromAndromeda.raDeg, fromPegasus.raDeg, 1e-6)
        assertEquals(fromAndromeda.decDeg, fromPegasus.decDeg, 1e-6)
    }
}
