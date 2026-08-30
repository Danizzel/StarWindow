package com.starwindow.app

import com.starwindow.app.data.catalog.ObjectType
import com.starwindow.app.data.catalog.SkyObject
import com.starwindow.app.data.gear.FilterKind
import com.starwindow.app.data.gear.SmartTelescopes
import com.starwindow.app.domain.CatalogFilter
import com.starwindow.app.domain.DewRisk
import com.starwindow.app.domain.FramingVerdict
import com.starwindow.app.domain.GuideVerdict
import com.starwindow.app.domain.PhotoGuide
import com.starwindow.app.domain.Season
import com.starwindow.app.domain.SkyConditions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Der Orionnebel: groß, hell, und ein Emissionsobjekt — der Standardfall für den Filter. */
private val orion = SkyObject(
    id = "M42", name = "Orionnebel", type = ObjectType.CLUSTER_NEBULA,
    raDeg = 83.82, decDeg = -5.39, magnitude = 4.0, sizeArcmin = 90.0, minorAxisArcmin = 60.0,
)

/** Die Andromedagalaxie: groß, aber ein Kontinuumsstrahler — der Gegenfall. */
private val andromeda = SkyObject(
    id = "M31", name = "Andromedagalaxie", type = ObjectType.GALAXY,
    raDeg = 10.68, decDeg = 41.27, magnitude = 3.44, sizeArcmin = 177.8, minorAxisArcmin = 69.7,
    surfaceBrightness = 23.63,
)

/** Ein planetarischer Nebel von einer halben Bogenminute: für diese Geräte ein Pixelhaufen. */
private val tiny = SkyObject(
    id = "NGC 6572", name = "", type = ObjectType.PLANETARY_NEBULA,
    raDeg = 271.0, decDeg = 6.85, magnitude = 8.1, sizeArcmin = 0.2,
)

class PhotoGuideFilterTest {

    /**
     * Der Kern der Sache: Dass der Filter bei Nebeln an- und bei Galaxien ausgeht, steht nirgends
     * als Regel — es fällt aus `t ∝ Hintergrund / Signal²` heraus. Wäre die Rechnung falsch, kämen
     * genau diese beiden Fälle verkehrt heraus.
     */
    @Test
    fun `the filter goes on for an emission nebula and off for a galaxy`() {
        val nebula = plan(orion, bortle = 6)
        assertTrue(nebula.useFilter, "M42 unter Bortle 6 ohne Filter")
        assertEquals(FilterKind.DUAL_BAND, nebula.filter)
        assertTrue(nebula.filterSpeedup > 2.0, "Ersparnis nur ${nebula.filterSpeedup}")

        val galaxy = plan(andromeda, bortle = 6)
        assertFalse(galaxy.useFilter, "M31 mit Dualbandfilter")
        assertEquals(FilterKind.NONE, galaxy.filter)
        assertTrue(galaxy.filterSpeedup < 1.0, "Der Filter dürfte hier nichts sparen")
    }

    /** Auch unter dunklem Himmel bleibt der Filter für Nebel die schnellere Wahl. */
    @Test
    fun `the filter still wins for a nebula under a dark sky`() {
        assertTrue(plan(orion, bortle = 2).useFilter)
    }

    /** Ein Gerät ohne Filter kann keinen empfehlen. */
    @Test
    fun `no filter is recommended when the telescope has none`() {
        val bare = SmartTelescopes.SEESTAR_S50.copy(builtInFilter = FilterKind.NONE)
        assertFalse(PhotoGuide.plan(orion, bare, 6, 0.0, -30.0, availableHours = 6.0).useFilter)
    }
}

class PhotoGuideExposureTest {

    /** Jede Bortle-Stufe kostet Zeit, und zwar monoton. */
    @Test
    fun `a brighter sky costs integration time`() {
        val times = (1..9).map { plan(andromeda, bortle = it).minimumMinutes }
        assertEquals(times.sortedBy { it }, times, "Belichtungszeit nicht monoton: $times")
        assertTrue(times.last() > times.first() * 3, "Bortle 9 kaum teurer als Bortle 1: $times")
    }

    /**
     * Die Öffnung geht quadratisch ein: Das S30 sammelt 36 % des Lichts eines S50 und braucht
     * deshalb fast die dreifache Zeit. Wer beide Geräte kennt, erkennt daran, ob die App rechnet
     * oder rät.
     */
    @Test
    fun `a smaller aperture needs more time`() {
        val small = PhotoGuide.plan(
            andromeda, SmartTelescopes.SEESTAR_S30, 5, 0.0, -30.0, availableHours = 8.0,
        )
        val large = PhotoGuide.plan(
            andromeda, SmartTelescopes.SEESTAR_S50, 5, 0.0, -30.0, availableHours = 8.0,
        )
        val ratio = small.minimumMinutes.toDouble() / large.minimumMinutes
        assertTrue(ratio > 2.0 && ratio < 3.5, "Verhältnis $ratio passt nicht zu 30 mm gegen 50 mm")
    }

    /** Ein hoher Vollmond neben dem Objekt ist teurer als gar kein Mond. */
    @Test
    fun `a full moon next to the target costs the most`() {
        val none = PhotoGuide.moonFactor(0.0, -10.0, 40.0)
        val far = PhotoGuide.moonFactor(100.0, 60.0, 150.0)
        val near = PhotoGuide.moonFactor(100.0, 60.0, 20.0)
        assertEquals(1.0, none)
        assertTrue(near > far, "Nah am Mond muss teurer sein als weit weg: $near vs $far")
        assertTrue(far > 1.0)
    }

    /** Unter dem Horizont kostet der Mond nichts, egal wie voll er ist. */
    @Test
    fun `a moon below the horizon is free`() {
        assertEquals(1.0, PhotoGuide.moonFactor(100.0, -0.1, 30.0))
    }

    /** Heller Himmel heißt kürzere Einzelbilder, sonst läuft der Hintergrund aus dem Histogramm. */
    @Test
    fun `bright skies shorten the sub exposure`() {
        val dark = PhotoGuide.subExposure(SmartTelescopes.SEESTAR_S30, effectiveSky = 0.5)
        val bright = PhotoGuide.subExposure(SmartTelescopes.SEESTAR_S30, effectiveSky = 12.0)
        assertTrue(bright < dark, "$bright s bei hellem gegen $dark s bei dunklem Himmel")
        assertTrue(dark in SmartTelescopes.SEESTAR_S30.subExposuresSec, "$dark s gibt es nicht")
        assertTrue(bright in SmartTelescopes.SEESTAR_S30.subExposuresSec, "$bright s gibt es nicht")
    }

    /** Die Empfehlung liegt über der Mindestzeit — sonst wäre eines der beiden Worte falsch. */
    @Test
    fun `the recommendation exceeds the minimum`() {
        val plan = plan(orion, bortle = 5)
        assertTrue(plan.recommendedMinutes > plan.minimumMinutes)
        assertTrue(plan.subCount > 1)
    }
}

class PhotoGuideFramingTest {

    /** M31 ist drei Grad breit und passt in kein Bildfeld dieser Klasse. */
    @Test
    fun `andromeda needs a mosaic on every one of these telescopes`() {
        for (telescope in SmartTelescopes.ALL) {
            assertEquals(
                FramingVerdict.NEEDS_MOSAIC,
                PhotoGuide.framing(andromeda, telescope),
                "${telescope.name} will M31 in ein Bild zwängen",
            )
        }
    }

    /** Ein Objekt von einer Fünftel Bogenminute ist bei diesen Auflösungen ein Fleck. */
    @Test
    fun `a tiny planetary is not worth it`() {
        val verdict = PhotoGuide.framing(tiny, SmartTelescopes.SEESTAR_S50)
        assertEquals(FramingVerdict.TOO_SMALL, verdict)
        assertEquals(GuideVerdict.POOR, plan(tiny, bortle = 4).verdict)
    }

    /** Und dasselbe Objekt bleibt zu klein, egal wie viel Zeit die Nacht hergibt. */
    @Test
    fun `time does not fix an object that is too small`() {
        val generous = PhotoGuide.plan(
            tiny, SmartTelescopes.SEESTAR_S50, 2, 0.0, -30.0, availableHours = 12.0,
        )
        assertEquals(GuideVerdict.POOR, generous.verdict)
    }

    /** M42 füllt das kleine Bildfeld des S50 und passt bequem ins weite des S30 Pro. */
    @Test
    fun `the same object frames differently on different telescopes`() {
        assertEquals(FramingVerdict.NEEDS_MOSAIC, PhotoGuide.framing(orion, SmartTelescopes.SEESTAR_S50))
        assertEquals(FramingVerdict.GOOD, PhotoGuide.framing(orion, SmartTelescopes.SEESTAR_S30_PRO))
    }
}

class PhotoGuideDewTest {

    /** Die Taupunktdifferenz schlägt jede andere Auskunft. */
    @Test
    fun `the dew point spread decides`() {
        assertEquals(DewRisk.CERTAIN, PhotoGuide.dewRisk(1.0, 40.0, Season.WINTER))
        assertEquals(DewRisk.LIKELY, PhotoGuide.dewRisk(3.0, null, null))
        assertEquals(DewRisk.NONE, PhotoGuide.dewRisk(9.0, 99.0, Season.AUTUMN))
    }

    /** Ohne Vorhersage entscheidet die Luftfeuchte, und ohne die die Jahreszeit. */
    @Test
    fun `it falls back to humidity and then to the season`() {
        assertEquals(DewRisk.CERTAIN, PhotoGuide.dewRisk(null, 95.0, null))
        assertEquals(DewRisk.LIKELY, PhotoGuide.dewRisk(null, null, Season.AUTUMN))
    }

    /** Ein Gerät ohne Heizung bekommt einen anderen Rat als eines mit. */
    @Test
    fun `the advice matches what the telescope can do`() {
        val seestar = PhotoGuide.plan(
            orion, SmartTelescopes.SEESTAR_S50, 5, 0.0, -30.0, dewSpreadK = 1.0, availableHours = 5.0,
        )
        val dwarf = PhotoGuide.plan(
            orion, SmartTelescopes.DWARF_3, 5, 0.0, -30.0, dewSpreadK = 1.0, availableHours = 5.0,
        )
        assertTrue(seestar.dewAction!!.contains("Heizung"), seestar.dewAction!!)
        assertTrue(dwarf.dewAction!!.contains("Taukappe"), dwarf.dewAction!!)
    }

    /** Ohne Taurisiko steht kein Rat da — ein Hinweis auf nichts ist Lärm. */
    @Test
    fun `no advice when there is no dew`() {
        val plan = PhotoGuide.plan(
            orion, SmartTelescopes.SEESTAR_S50, 5, 0.0, -30.0, dewSpreadK = 12.0, availableHours = 5.0,
        )
        assertEquals(DewRisk.NONE, plan.dew)
        assertEquals(null, plan.dewAction)
    }
}

class PhotoGuideVerdictTest {

    /** Eine Nacht, die nicht reicht, wird als solche benannt statt schöngerechnet. */
    @Test
    fun `a night too short to be useful says so`() {
        val plan = PhotoGuide.plan(
            andromeda, SmartTelescopes.SEESTAR_S30, 7, 90.0, 50.0, 30.0, availableHours = 0.3,
        )
        assertEquals(GuideVerdict.POOR, plan.verdict)
        assertFalse(plan.fitsInTonight)
    }

    /**
     * Eine Empfehlung, die keine Nacht hergibt, wird als Zahl von Nächten ausgewiesen.
     *
     * Unter Bortle 8 mit Vollmond kommen zweistellige Stundenzahlen heraus — richtig gerechnet und
     * trotzdem unlesbar, solange nicht dabeisteht, dass sie über mehrere Nächte gemeint sind.
     */
    @Test
    fun `an integration longer than one night is counted in nights`() {
        val plan = PhotoGuide.plan(
            andromeda, SmartTelescopes.SEESTAR_S30, 8, 100.0, 60.0, 25.0, availableHours = 4.0,
        )
        val nights = plan.nightsNeeded
        assertTrue(nights != null && nights > 1, "nightsNeeded war $nights")
        assertTrue(nights!! * plan.availableMinutes >= plan.recommendedMinutes)
    }

    /** Passt es in eine Nacht, steht dort auch nichts. */
    @Test
    fun `one night needs no night count`() {
        val plan = PhotoGuide.plan(
            orion, SmartTelescopes.SEESTAR_S50, 3, 0.0, -30.0, availableHours = 8.0,
        )
        assertEquals(null, plan.nightsNeeded)
    }

    /** Und eine, die reicht, auch. */
    @Test
    fun `a good night is called good`() {
        val plan = PhotoGuide.plan(
            orion, SmartTelescopes.SEESTAR_S30_PRO, 4, 0.0, -20.0, availableHours = 6.0,
        )
        assertEquals(GuideVerdict.GOOD, plan.verdict)
        assertTrue(plan.fitsInTonight)
        assertEquals(1.0, plan.nightCoverage)
    }
}

class FavoritesFilterTest {

    private val conditions = SkyConditions()

    @Test
    fun `the favourites filter keeps only what is liked`() {
        val filter = CatalogFilter(onlyFavorites = true)
        assertTrue(filter.matches(orion, 40.0, conditions, setOf("M42")))
        assertFalse(filter.matches(andromeda, 40.0, conditions, setOf("M42")))
    }

    /** Ohne den Schalter interessieren die Favoriten nicht. */
    @Test
    fun `without the switch nothing is filtered by hearts`() {
        assertTrue(CatalogFilter.NONE.matches(andromeda, 40.0, conditions, emptySet()))
    }

    /** Ein eingeschalteter Filter, der nicht sichtbar wäre, ist der Fehler, den es zu vermeiden gilt. */
    @Test
    fun `the switch shows up as a chip and can be cleared`() {
        val filter = CatalogFilter(onlyFavorites = true)
        assertFalse(filter.isEmpty)
        assertEquals(1, filter.activeCount)
        val chip = filter.activeLabels.single()
        assertTrue(filter.without(chip.field).isEmpty)
    }
}

private fun plan(obj: SkyObject, bortle: Int) = PhotoGuide.plan(
    obj = obj,
    telescope = SmartTelescopes.SEESTAR_S50,
    bortle = bortle,
    moonIlluminationPercent = 0.0,
    moonAltitudeDeg = -30.0,
    availableHours = 6.0,
)
