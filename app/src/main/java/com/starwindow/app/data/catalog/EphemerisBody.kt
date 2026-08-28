package com.starwindow.app.data.catalog

import com.starwindow.app.core.astro.Equatorial
import com.starwindow.app.core.astro.LunarEphemeris
import com.starwindow.app.core.astro.SolarEphemeris
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/**
 * Ein Himmelskörper, dessen Position aus einer Ephemeride kommt statt aus dem Katalog.
 *
 * Die Rechnung dahinter steht seit Längerem in `core/astro/Ephemeris.kt` und wurde bisher nur für
 * Dämmerung und Mondstörung benutzt. Was fehlte, war die Verbindung zum Katalog: „Wann zieht der
 * Mond durch mein Fenster" ist die naheliegendste Frage, die diese App bekommt, und sie ließ sich
 * nicht stellen, weil ein [SkyObject] eine feste Rektaszension hatte und die Durchgangsrechnung
 * darauf gebaut war.
 *
 * Zwei Einträge und kein dritter: Die Planeten brauchen VSOP87 und sind ein eigenes Stück Arbeit.
 * Die Struktur hier ist aber genau die, in die sie später passen — ein Aufzählungswert mehr,
 * dessen [positionAt] anders rechnet.
 */
@Serializable
enum class EphemerisBody(
    val id: String,
    val label: String,
    /**
     * Mittlerer scheinbarer Durchmesser in Bogenminuten.
     *
     * Ein Mittelwert, und das ist bei diesen beiden eine echte Näherung: Der Mond schwankt zwischen
     * 29,4' und 33,5', je nachdem wo er auf seiner Bahn steht. Für die Frage, ob er in ein Fenster
     * passt, ist das ohne Belang; für eine Aufnahme mit fester Brennweite nicht, und deshalb steht
     * es in der Beschreibung.
     */
    val meanSizeArcmin: Double,
    /** Scheinbare Helligkeit bei voller Beleuchtung. */
    val brightestMagnitude: Double,
) {
    @SerialName("SUN")
    SUN("Sonne", "Sonne", 31.9, -26.7),

    @SerialName("MOON")
    MOON("Mond", "Mond", 31.1, -12.7);

    /** Die scheinbare Position zu diesem Zeitpunkt, Äquinoktium des Datums. */
    fun positionAt(millis: Long): Equatorial = when (this) {
        SUN -> SolarEphemeris.at(millis).equatorial
        MOON -> LunarEphemeris.at(millis).equatorial
    }

    /**
     * Wie weit der Körper in einer Stunde am Himmel wandert, grob.
     *
     * Nur eine Größenordnung, und genau als solche gebraucht: Sie sagt der Durchgangsrechnung, wie
     * fein sie abtasten muss. Der Mond läuft rund ein halbes Grad pro Stunde — sein eigener
     * Durchmesser —, die Sonne ein Vierzigstel davon gegenüber den Sternen.
     */
    val degreesPerHour: Double get() = if (this == MOON) 0.55 else 0.04
}

/**
 * Sonne und Mond als Katalogeinträge.
 *
 * Sie werden dem Katalog **vorangestellt** und verhalten sich von da an wie jeder andere Eintrag:
 * Sie sind suchbar („Mond"), lassen sich verfolgen, bekommen ein Infoblatt und ziehen durch ein
 * gespeichertes Fenster. Der einzige Unterschied steht in [SkyObject.body] und wird genau dort
 * abgefragt, wo über die Zeit gerechnet wird.
 *
 * Die Zahlen im Eintrag — Helligkeit und Größe — sind Mittelwerte und stehen nur da, damit die
 * Anzeige etwas hat und die Reihenfolgen nicht ins Leere greifen. Position und Phase kommen immer
 * aus der Ephemeride.
 */
object EphemerisCatalog {

    val moon = SkyObject(
        id = "Mond",
        name = "Mond",
        type = ObjectType.SOLAR_SYSTEM,
        // Die gespeicherten Koordinaten sind ein Platzhalter und werden nie benutzt: Alles, was
        // eine Position braucht, geht über `positionAtMillis`. Sie stehen auf der Ekliptik in der
        // Mitte des Bereichs, den der Mond durchläuft, damit eine Stelle, die sie doch einmal
        // liest, wenigstens nicht grob danebenliegt.
        raDeg = 0.0,
        decDeg = 0.0,
        magnitude = EphemerisBody.MOON.brightestMagnitude,
        sizeArcmin = EphemerisBody.MOON.meanSizeArcmin,
        minorAxisArcmin = EphemerisBody.MOON.meanSizeArcmin,
        alternativeNames = listOf("Luna", "Erdmond"),
        catalogIds = emptyList(),
        source = "ephemeris",
        body = EphemerisBody.MOON,
    )

    val sun = SkyObject(
        id = "Sonne",
        name = "Sonne",
        type = ObjectType.SOLAR_SYSTEM,
        raDeg = 0.0,
        decDeg = 0.0,
        magnitude = EphemerisBody.SUN.brightestMagnitude,
        sizeArcmin = EphemerisBody.SUN.meanSizeArcmin,
        minorAxisArcmin = EphemerisBody.SUN.meanSizeArcmin,
        alternativeNames = listOf("Sol"),
        catalogIds = emptyList(),
        source = "ephemeris",
        body = EphemerisBody.SUN,
    )

    /** Mond zuerst: Er ist das Objekt, das tatsächlich fotografiert wird. */
    val all: List<SkyObject> = listOf(moon, sun)

    fun forBody(body: EphemerisBody): SkyObject = when (body) {
        EphemerisBody.MOON -> moon
        EphemerisBody.SUN -> sun
    }

    /**
     * Die Beschreibung, die im Infoblatt steht — mit den Zahlen des Augenblicks.
     *
     * Anders als bei einem Katalogobjekt lässt sich hier nichts fest hinschreiben: Beleuchtung,
     * Abstand und scheinbare Größe des Mondes ändern sich täglich, und eine feste Angabe wäre an
     * den meisten Tagen falsch.
     */
    fun stateText(body: EphemerisBody, millis: Long): String = when (body) {
        EphemerisBody.MOON -> {
            val moonPosition = LunarEphemeris.at(millis)
            val illumination = LunarEphemeris.illuminationAt(millis)
            buildString {
                append(illumination.phaseName)
                append(" · ").append(illumination.percent.roundToInt()).append(" % beleuchtet")
                append(" · ").append((moonPosition.distanceKm / 1000.0).roundToInt())
                append(" Tsd. km entfernt")
            }
        }
        EphemerisBody.SUN -> "Steht tagsüber am Himmel – niemals mit der Kamera anvisieren."
    }
}
