package com.starwindow.app.domain

import com.starwindow.app.data.weather.WeatherPlace
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sqrt

/** Eine Stufe der Bortle-Skala. */
data class BortleClass(
    val level: Int,
    val title: String,
    /** Was man am Himmel sieht — die Stufe ist ursprünglich über genau solche Beobachtungen definiert. */
    val sky: String,
    /** Was das für die Fotografie heißt. */
    val consequence: String,
    /** Typische Grenzgröße für das bloße Auge. */
    val nakedEyeLimit: String,
)

/**
 * Die Bortle-Skala und eine Schätzung dazu.
 *
 * **Zur Ehrlichkeit vorweg:** Ein belastbarer Bortle-Wert kommt aus einem Lichtatlas — aus
 * Satellitenmessungen der Aufhellung, Gitterpunkt für Gitterpunkt. So ein Atlas liegt hier nicht
 * vor, und ihn über eine kostenpflichtige Schnittstelle nachzuladen passt weder zur Katalogstrategie
 * dieser App noch dazu, dass sie sonst ohne Netz rechnet. Was hier steht, ist deshalb eine
 * **Abschätzung aus Einwohnerzahl und Entfernung** — gut genug, um Dorf von Großstadt zu
 * unterscheiden, und nicht gut genug, um zwei Beobachtungsplätze 20 km auseinander gegeneinander
 * abzuwägen. Sie kennt außerdem nur *einen* Ort: die Lichtglocke der Großstadt hinter dem nächsten
 * Hügel taucht in ihr nicht auf. Deswegen lässt sich der Wert von Hand setzen, und deswegen steht
 * überall dabei, woher er kommt.
 *
 * Das Modell hat zwei Teile. **Im Ort** hängt die Aufhellung an der Größe der Ortschaft — von
 * Weiler (Stufe 4) bis Großstadt (Stufe 9); die Schwellen sind an gemessenen Werten in Mitteleuropa
 * ausgerichtet. **Außerhalb** greift Walkers Gesetz: die Aufhellung fällt mit der 2,5. Potenz der
 * Entfernung. Beide Teile treffen sich am Rand der bebauten Fläche, so dass der Verlauf stetig ist
 * und in beide Richtungen monoton — mehr Einwohner heißt heller, mehr Abstand heißt dunkler.
 */
object BortleScale {

    val CLASSES: List<BortleClass> = listOf(
        BortleClass(
            level = 1,
            title = "Ausgezeichneter Dunkelhimmel",
            sky = "Zodiakallicht und Gegenschein sichtbar, die Milchstraße wirft Schatten.",
            consequence = "Alles ist möglich; die Belichtung begrenzt nur noch das Rauschen der Kamera.",
            nakedEyeLimit = "7,6–8,0 mag",
        ),
        BortleClass(
            level = 2,
            title = "Typischer Dunkelhimmel",
            sky = "Milchstraße stark strukturiert, M33 direkt sichtbar.",
            consequence = "Schwache Nebel in RGB ohne Filter erreichbar.",
            nakedEyeLimit = "7,1–7,5 mag",
        ),
        BortleClass(
            level = 3,
            title = "Ländlicher Himmel",
            sky = "Am Horizont deutet sich Aufhellung an, die Milchstraße bleibt beeindruckend.",
            consequence = "Guter Kompromiss; Galaxien und Reflexionsnebel funktionieren.",
            nakedEyeLimit = "6,6–7,0 mag",
        ),
        BortleClass(
            level = 4,
            title = "Übergang Land zu Vorstadt",
            sky = "Lichtglocken über den Ortschaften, Milchstraße über dem Horizont blass.",
            consequence = "Breitband geht noch, Gradienten müssen herausgerechnet werden.",
            nakedEyeLimit = "6,1–6,5 mag",
        ),
        BortleClass(
            level = 5,
            title = "Vorstadthimmel",
            sky = "Milchstraße nur nahe dem Zenit und nur schwach.",
            consequence = "Schmalband ist im Vorteil; Galaxien brauchen lange Gesamtbelichtungen.",
            nakedEyeLimit = "5,6–6,0 mag",
        ),
        BortleClass(
            level = 6,
            title = "Heller Vorstadthimmel",
            sky = "Milchstraße verschwunden, der Himmel wirkt graugrün.",
            consequence = "Ohne Schmalbandfilter lohnt sich fast nur noch der Mond.",
            nakedEyeLimit = "5,5 mag",
        ),
        BortleClass(
            level = 7,
            title = "Übergang Vorstadt zu Stadt",
            sky = "Der ganze Himmel ist grau aufgehellt, hellere Wolken leuchten.",
            consequence = "Schmalband ja, Breitband praktisch nicht mehr.",
            nakedEyeLimit = "5,0 mag",
        ),
        BortleClass(
            level = 8,
            title = "Stadthimmel",
            sky = "Man kann draußen Zeitung lesen, M31 ist bestenfalls zu erahnen.",
            consequence = "Nur Mond, Planeten und Schmalband mit schmalen Filtern.",
            nakedEyeLimit = "4,5 mag",
        ),
        BortleClass(
            level = 9,
            title = "Innenstadt",
            sky = "Nur die hellsten Sternbilder sind auszumachen.",
            consequence = "Deep Sky nur noch als Schmalband-Experiment.",
            nakedEyeLimit = "4,0 mag",
        ),
    )

    fun forLevel(level: Int): BortleClass = CLASSES[(level.coerceIn(1, 9)) - 1]

    /** Geschätzte Stufe für einen Ort, oder null wenn die Einwohnerzahl fehlt. */
    fun estimate(place: WeatherPlace): Int? {
        val population = place.population?.takeIf { it > 0 } ?: return null
        return levelFor(brightnessRatio(population, place.populationDistanceKm))
    }

    /** Die Stufe, die angezeigt wird: gesetzter Wert vor Schätzung. */
    fun resolve(place: WeatherPlace): Int? = place.bortleOverride ?: estimate(place)

    /**
     * Künstliche Himmelsaufhellung im Verhältnis zum natürlichen Nachthimmel.
     *
     * 1,0 heißt: der Himmel ist doppelt so hell wie ohne jede Beleuchtung. Innerhalb der bebauten
     * Fläche gilt der Wert der Ortsgröße, außerhalb fällt er nach Walker mit `d^-2,5`.
     */
    fun brightnessRatio(population: Int, distanceKm: Double): Double {
        val inside = insideRatio(population)
        val radiusKm = settlementRadiusKm(population)
        if (distanceKm <= radiusKm) return inside
        return inside * (distanceKm / radiusKm).pow(-2.5)
    }

    /**
     * Aufhellung mitten im Ort, allein aus der Einwohnerzahl.
     *
     * Die Werte sind die geometrische Mitte des jeweiligen Bandes der Stufen 4 bis 9 — ein Weiler
     * liegt bei 4, eine Kleinstadt bei 6, eine Großstadt bei 9.
     */
    fun insideRatio(population: Int): Double = when {
        population < 500 -> 0.07
        population < 5_000 -> 0.20
        population < 25_000 -> 0.47
        population < 100_000 -> 0.93
        population < 500_000 -> 1.87
        else -> 3.50
    }

    /**
     * Radius der bebauten Fläche aus der Einwohnerzahl, bei 1500 Einwohnern je Quadratkilometer —
     * grob die Dichte einer europäischen Stadt außerhalb des Zentrums.
     */
    fun settlementRadiusKm(population: Int): Double =
        sqrt(population / (SETTLEMENT_DENSITY_PER_KM2 * PI)).coerceAtLeast(0.3)

    /**
     * Aufhellungsverhältnis → Bortle-Stufe.
     *
     * Die Schwellen folgen den Klassengrenzen des Weltatlas der Lichtverschmutzung (Falchi u. a.),
     * die ebenfalls über das Verhältnis zum natürlichen Himmel definiert sind.
     */
    fun levelFor(ratio: Double): Int = when {
        ratio < 0.01 -> 1
        ratio < 0.02 -> 2
        ratio < 0.04 -> 3
        ratio < 0.12 -> 4
        ratio < 0.33 -> 5
        ratio < 0.66 -> 6
        ratio < 1.32 -> 7
        ratio < 2.64 -> 8
        else -> 9
    }

    private const val SETTLEMENT_DENSITY_PER_KM2 = 1500.0
}
