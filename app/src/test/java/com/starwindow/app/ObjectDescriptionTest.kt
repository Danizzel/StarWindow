package com.starwindow.app

import com.starwindow.app.data.catalog.ObjectType
import com.starwindow.app.data.catalog.SkyObject
import com.starwindow.app.domain.ObjectDescription
import com.starwindow.app.domain.ObjectSearch
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun obj(
    type: ObjectType,
    id: String = "X",
    name: String = "",
    sizeArcmin: Double? = null,
    surfaceBrightness: Double? = null,
    morphology: String? = null,
) = SkyObject(
    id = id,
    name = name,
    type = type,
    raDeg = 10.0,
    decDeg = 20.0,
    sizeArcmin = sizeArcmin,
    surfaceBrightness = surfaceBrightness,
    morphology = morphology,
)

class ObjectDescriptionTest {

    @Test
    fun `every type gets an explanation, so no object is left without one`() {
        // The catalogue has three thousand entries and only a couple of hundred hand-written
        // notes; the type text is what the rest rely on.
        ObjectType.entries.forEach { type ->
            val text = ObjectDescription.whatItIs(type)
            assertTrue(text.length > 40, "$type has no real explanation: \"$text\"")
        }
    }

    @Test
    fun `the two kinds of nebula are told apart, and so is what that means for a filter`() {
        val emission = ObjectDescription.describe(obj(ObjectType.EMISSION_NEBULA))
        val reflection = ObjectDescription.describe(obj(ObjectType.REFLECTION_NEBULA))

        assertTrue(emission.whatItIs.contains("Emissionsnebel"))
        assertTrue(reflection.whatItIs.contains("Reflexionsnebel"))

        // The practical difference: a narrowband filter transforms one and ruins the other.
        assertTrue(
            emission.traits.any { it.contains("Schmalbandfilter") && !it.contains("Kein") },
            emission.traits.toString(),
        )
        assertTrue(
            reflection.traits.any { it.startsWith("Kein Schmalbandfilter") },
            reflection.traits.toString(),
        )
    }

    @Test
    fun `a dark nebula is described as a silhouette rather than as something that shines`() {
        val text = ObjectDescription.whatItIs(ObjectType.DARK_NEBULA)
        assertTrue(text.contains("Silhouette"), text)
    }

    @Test
    fun `a planetary nebula says it has nothing to do with planets`() {
        // The single most common misunderstanding in the whole catalogue.
        val text = ObjectDescription.whatItIs(ObjectType.PLANETARY_NEBULA)
        assertTrue(text.contains("Planeten"), text)
    }

    @Test
    fun `size is expressed against the Moon, which is the one angle everyone knows`() {
        val andromeda = ObjectDescription.describe(obj(ObjectType.GALAXY, sizeArcmin = 190.0))
        assertTrue(
            andromeda.traits.any { it.contains("Vollmond") },
            andromeda.traits.toString(),
        )
    }

    @Test
    fun `a small object is told to bring focal length, a large one to lose it`() {
        val small = ObjectDescription.describe(obj(ObjectType.PLANETARY_NEBULA, sizeArcmin = 0.8))
        val large = ObjectDescription.describe(obj(ObjectType.EMISSION_NEBULA, sizeArcmin = 150.0))
        assertTrue(small.traits.any { it.contains("Brennweite") }, small.traits.toString())
        assertTrue(large.traits.any { it.contains("kurze Brennweiten") }, large.traits.toString())
    }

    @Test
    fun `spread-out light is called out as needing a dark sky`() {
        val faint = ObjectDescription.describe(obj(ObjectType.GALAXY, surfaceBrightness = 24.0))
        val compact = ObjectDescription.describe(obj(ObjectType.PLANETARY_NEBULA, surfaceBrightness = 18.0))
        assertTrue(faint.traits.any { it.contains("dunklen Himmel") }, faint.traits.toString())
        assertTrue(compact.traits.any { it.contains("Stadthimmel") }, compact.traits.toString())
    }

    @Test
    fun `a morphology code is unpacked instead of repeated`() {
        val barred = ObjectDescription.describe(obj(ObjectType.GALAXY, morphology = "SB(s)b"))
        assertTrue(barred.traits.any { it.contains("Balken") }, barred.traits.toString())

        val elliptical = ObjectDescription.describe(obj(ObjectType.GALAXY, morphology = "E2"))
        assertTrue(elliptical.traits.any { it.contains("elliptisch") }, elliptical.traits.toString())
    }

    @Test
    fun `an object with nothing but a type still gets a usable description`() {
        // The anonymous fifteenth-magnitude galaxy case, which is most of the catalogue.
        val bare = ObjectDescription.describe(obj(ObjectType.GALAXY))
        assertTrue(bare.whatItIs.isNotBlank())
        assertTrue(bare.note == null)
    }

    @Test
    fun `a blank note is treated as no note at all`() {
        assertTrue(ObjectDescription.describe(obj(ObjectType.GALAXY), note = "   ").note == null)
    }
}

/**
 * The notes asset is data, and data with a typo in a key is a note that silently never appears.
 * These read the real file rather than a fixture for exactly that reason.
 */
class ObjectNotesAssetTest {

    private val notesFile = File("src/main/assets/catalog/object_notes.json").readText()

    @Test
    fun `the asset is present and carries a useful number of notes`() {
        val keys = Regex("\"([a-z0-9]+)\":\\s*\"").findAll(notesFile).count()
        assertTrue(keys > 100, "only $keys notes")
    }

    @Test
    fun `every key is already in folded form, or the lookup would never match it`() {
        // The repository folds an object's designations and looks them up directly; a key with a
        // space or a capital in it is dead weight that no object can ever reach.
        val body = notesFile.substringAfter("\"notes\"")
        Regex("\"([^\"]+)\":\\s*\"").findAll(body)
            .map { it.groupValues[1] }
            .filter { it != "starNotes" }
            .forEach { key ->
                assertEquals(ObjectSearch.fold(key), key, "key \"$key\" is not folded")
            }
    }

    @Test
    fun `the objects most likely to be looked at have a note`() {
        listOf("m31", "m42", "m45", "m13", "m51", "m57", "ngc7000", "ic434", "ngc7293")
            .forEach { key ->
                assertTrue(notesFile.contains("\"$key\""), "no note for $key")
            }
    }
}
