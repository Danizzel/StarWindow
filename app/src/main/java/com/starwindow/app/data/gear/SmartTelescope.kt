package com.starwindow.app.data.gear

import kotlin.math.min

/**
 * Was ein Gerät gegen Lichtverschmutzung mitbringt.
 *
 * Die Unterscheidung ist keine Spitzfindigkeit, sondern entscheidet die halbe Empfehlung: Ein
 * **Dualband**-Filter lässt nur zwei schmale Linien durch (H-alpha und O III) und macht deshalb
 * einen Emissionsnebel unter Stadthimmel überhaupt erst möglich — und eine Galaxie unmöglich, weil
 * deren Licht ein Kontinuum ist und zu 95 % im Filter hängen bleibt. Ein **Breitband**-Filter
 * dämpft nur die Linien der Straßenbeleuchtung und ist auf beides anwendbar, hilft dafür weniger.
 */
enum class FilterKind(val label: String) {
    NONE("kein Filter"),
    BROADBAND("Breitband-LP"),
    DUAL_BAND("Dualband (Hα/O III)"),
}

/**
 * Ein Smart-Teleskop, so weit die App es kennen muss.
 *
 * Nur die Zahlen, aus denen tatsächlich eine Empfehlung folgt. Das Gewicht, der Akku und die
 * Montierung stehen nicht hier: Sie entscheiden über den Kauf, nicht über die Aufnahme.
 *
 * Die Bildfelder sind die Herstellerangaben für den Teleobjektiv-Modus in Hoch- oder Querformat;
 * gespeichert wird die lange und die kurze Kante getrennt, weil ein Motiv in **beide** passen muss
 * und die kurze deshalb über den Mosaikmodus entscheidet.
 */
data class SmartTelescope(
    val id: String,
    val brand: String,
    val model: String,
    val apertureMm: Double,
    val focalLengthMm: Double,
    /** Lange Kante des Bildfelds in Grad. */
    val fovLongDeg: Double,
    /** Kurze Kante des Bildfelds in Grad. */
    val fovShortDeg: Double,
    /** Bogensekunden je Pixel — entscheidet, ab wann ein Motiv nur noch ein Fleck ist. */
    val pixelScaleArcsec: Double,
    /** Wählbare Einzelbelichtungen in Sekunden, aufsteigend. */
    val subExposuresSec: List<Int>,
    /** Der eingebaute Filter, der sich zuschalten lässt. */
    val builtInFilter: FilterKind,
    val hasMosaic: Boolean,
    /** Eine schaltbare Heizung gegen Tau — sonst braucht es eine Taukappe. */
    val hasDewHeater: Boolean,
) {
    val name: String get() = "$brand $model"

    /** Bildfeldfläche in Quadratgrad — wie viel Himmel eine Aufnahme überhaupt fasst. */
    val fovAreaSquareDeg: Double get() = fovLongDeg * fovShortDeg

    /**
     * Wie viel Licht das Gerät im Vergleich zu einem 50-mm-Seestar sammelt.
     *
     * Quadratisch in der Öffnung, weil die Fläche zählt: Die 30 mm des S30 fangen nicht 60 %,
     * sondern 36 % des Lichts eines S50 auf — und brauchen für dasselbe Ergebnis fast die
     * dreifache Zeit. Das ist der größte einzelne Unterschied zwischen diesen Geräten und der
     * Grund, warum eine Belichtungsempfehlung ohne das Gerät wertlos ist.
     */
    val lightGrasp: Double get() = (apertureMm / REFERENCE_APERTURE_MM).let { it * it }

    /** Wie groß ein Objekt dieser Ausdehnung im Bild wird, in Pixeln entlang der langen Achse. */
    fun objectPixels(sizeArcmin: Double): Double = sizeArcmin * 60.0 / pixelScaleArcsec

    /**
     * Anteil der **kurzen** Bildfeldkante, den ein Objekt einnimmt.
     *
     * Die kurze Kante, weil ein Motiv sonst quer ins Bild gedreht werden müsste, um zu passen —
     * und das können diese Geräte im Nachführbetrieb nicht frei.
     */
    fun fillFraction(sizeArcmin: Double): Double = (sizeArcmin / 60.0) / fovShortDeg

    private companion object {
        /** Der Seestar S50 als Bezugsgerät: das meistverbreitete der Klasse. */
        const val REFERENCE_APERTURE_MM = 50.0
    }
}

/**
 * Die Geräte, die die App kennt.
 *
 * Bewusst nur Smart-Teleskope und bewusst nur diese fünf. Für ein klassisches Setup aus Optik,
 * Kamera und Montierung wäre eine Empfehlung dieser Art anmaßend — dort entscheidet der Besitzer
 * über jedes Teil einzeln und weiß mehr über seine Kombination als jede Tabelle. Ein Smart-Teleskop
 * dagegen ist genau eine bekannte Kombination mit einer Handvoll Schaltern, und die Frage „welche
 * Schalter für dieses Objekt heute Nacht" hat eine beantwortbare Antwort.
 *
 * Zahlen aus den Herstellerangaben und den einschlägigen Vergleichstests (ZWO Seestar, DwarfLab,
 * AstroBackyard, Skies & Scopes). Wo sie sich widersprachen, steht der konservativere Wert.
 */
object SmartTelescopes {

    val SEESTAR_S30 = SmartTelescope(
        id = "seestar_s30",
        brand = "ZWO",
        model = "Seestar S30",
        apertureMm = 30.0,
        focalLengthMm = 150.0,
        fovLongDeg = 2.13,
        fovShortDeg = 1.20,
        pixelScaleArcsec = 4.0,
        subExposuresSec = listOf(10, 20, 30, 60),
        builtInFilter = FilterKind.DUAL_BAND,
        hasMosaic = true,
        hasDewHeater = true,
    )

    val SEESTAR_S30_PRO = SmartTelescope(
        id = "seestar_s30_pro",
        brand = "ZWO",
        model = "Seestar S30 Pro",
        apertureMm = 30.0,
        focalLengthMm = 160.0,
        fovLongDeg = 3.98,
        fovShortDeg = 2.26,
        pixelScaleArcsec = 3.75,
        subExposuresSec = listOf(10, 20, 30, 60),
        builtInFilter = FilterKind.DUAL_BAND,
        hasMosaic = true,
        hasDewHeater = true,
    )

    val SEESTAR_S50 = SmartTelescope(
        id = "seestar_s50",
        brand = "ZWO",
        model = "Seestar S50",
        apertureMm = 50.0,
        focalLengthMm = 250.0,
        fovLongDeg = 1.29,
        fovShortDeg = 0.73,
        pixelScaleArcsec = 2.39,
        subExposuresSec = listOf(10, 20, 30),
        builtInFilter = FilterKind.DUAL_BAND,
        hasMosaic = true,
        hasDewHeater = true,
    )

    val DWARF_3 = SmartTelescope(
        id = "dwarf_3",
        brand = "DwarfLab",
        model = "DWARF 3",
        apertureMm = 35.0,
        focalLengthMm = 150.0,
        fovLongDeg = 2.93,
        fovShortDeg = 1.65,
        pixelScaleArcsec = 2.75,
        subExposuresSec = listOf(15, 30, 60, 90),
        builtInFilter = FilterKind.DUAL_BAND,
        hasMosaic = true,
        // Kein Heizelement: Das DWARF setzt auf sein abgedichtetes Gehäuse und die Restwärme des
        // Akkus. In einer Taunacht heißt das Taukappe, und die App muss das sagen statt einen
        // Schalter zu empfehlen, den es nicht gibt.
        hasDewHeater = false,
    )

    val DWARF_MINI = SmartTelescope(
        id = "dwarf_mini",
        brand = "DwarfLab",
        model = "DWARF Mini",
        apertureMm = 30.0,
        focalLengthMm = 150.0,
        fovLongDeg = 2.13,
        fovShortDeg = 1.20,
        pixelScaleArcsec = 4.0,
        subExposuresSec = listOf(15, 30, 60, 90),
        builtInFilter = FilterKind.DUAL_BAND,
        hasMosaic = true,
        hasDewHeater = false,
    )

    val ALL = listOf(SEESTAR_S30, SEESTAR_S30_PRO, SEESTAR_S50, DWARF_3, DWARF_MINI)

    val DEFAULT = SEESTAR_S50

    fun byId(id: String?): SmartTelescope = ALL.firstOrNull { it.id == id } ?: DEFAULT

    /** Das Gerät mit dem kleinsten Bildfeld — für Hinweise, die über alle Geräte gelten sollen. */
    val narrowestFovDeg: Double get() = ALL.minOf { min(it.fovLongDeg, it.fovShortDeg) }
}
