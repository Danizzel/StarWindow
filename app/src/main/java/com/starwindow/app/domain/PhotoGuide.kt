package com.starwindow.app.domain

import com.starwindow.app.data.catalog.SkyObject
import com.starwindow.app.data.gear.FilterKind
import com.starwindow.app.data.gear.SmartTelescope
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin

/** Wie sicher in dieser Nacht Tau auf der Frontlinse landet. */
enum class DewRisk(val label: String) {
    NONE("kein Tau zu erwarten"),
    POSSIBLE("Tau gegen Morgen möglich"),
    LIKELY("Tau wahrscheinlich"),
    CERTAIN("Tau so gut wie sicher"),
    ;

    val needsAction: Boolean get() = this != NONE
}

/** Ob das Motiv ins Bildfeld passt — und ob es darin überhaupt etwas hergibt. */
enum class FramingVerdict(val label: String) {
    TOO_SMALL("zu klein für diese Optik"),
    SMALL("klein im Bild"),
    GOOD("füllt das Bild gut"),
    TIGHT("passt knapp"),
    NEEDS_MOSAIC("größer als das Bildfeld"),
}

/** Das Gesamturteil, das oben auf der Karte steht. */
enum class GuideVerdict(val label: String) {
    GOOD("Lohnt sich heute Nacht"),
    MARGINAL("Geht, wird aber knapp"),
    POOR("Heute Nacht eher nicht"),
}

/**
 * Der fertige Plan für ein Motiv, ein Gerät und eine Nacht.
 *
 * Alles, was auf dem Bildschirm steht, ist hier ausgerechnet — der Bildschirm formatiert nur noch.
 */
data class PhotoPlan(
    val obj: SkyObject,
    val telescope: SmartTelescope,
    val bortle: Int,
    val verdict: GuideVerdict,

    val framing: FramingVerdict,
    /** Anteil der kurzen Bildfeldkante, den das Objekt einnimmt. */
    val fillFraction: Double,
    /** Länge des Objekts im Bild, in Pixeln. */
    val objectPixels: Double,
    val needsMosaic: Boolean,

    val useFilter: Boolean,
    val filter: FilterKind,
    /** Um wie viel der Filter die nötige Belichtungszeit verkürzt (oder verlängert). */
    val filterSpeedup: Double,

    val subExposureSec: Int,
    /** Ab hier ist ein Bild vorzeigbar. */
    val minimumMinutes: Int,
    /** Ab hier holt weiteres Belichten kaum noch etwas heraus. */
    val recommendedMinutes: Int,
    /** Was die Nacht an diesem Ort überhaupt hergibt. */
    val availableMinutes: Int,

    val dew: DewRisk,
    /** Was gegen den Tau zu tun ist, oder null, wenn nichts zu tun ist. */
    val dewAction: String?,
) {
    /** Wie viele Einzelbilder die empfohlene Zeit bedeutet. */
    val subCount: Int get() = (recommendedMinutes * 60 / subExposureSec).coerceAtLeast(1)

    /** Reicht die Nacht für ein brauchbares Bild? */
    val fitsInTonight: Boolean get() = availableMinutes >= minimumMinutes

    /** Wie viel von der empfohlenen Zeit die Nacht hergibt, 0 bis 1. */
    val nightCoverage: Double
        get() = if (recommendedMinutes <= 0) 1.0
        else (availableMinutes.toDouble() / recommendedMinutes).coerceIn(0.0, 1.0)

    /**
     * Über wie viele Nächte die empfohlene Zeit zu verteilen ist, oder null für eine einzige.
     *
     * Unter einem hellen Himmel und bei Mond kommen Zeiten heraus, die in keine Nacht passen — und
     * das ist keine Panne der Rechnung, sondern die Praxis: Belichtungen werden über Nächte hinweg
     * gestapelt, das ist der übliche Weg zu einem tiefen Bild. Nur muss die Zahl dann auch als das
     * ausgewiesen werden, was sie ist, sonst liest sie sich als Unsinn.
     */
    val nightsNeeded: Int?
        get() {
            if (availableMinutes <= 0) return null
            val nights = Math.ceil(recommendedMinutes.toDouble() / availableMinutes).toInt()
            return nights.takeIf { it > 1 }
        }
}

/**
 * Was mit diesem Gerät, an diesem Ort, in dieser Nacht einzustellen ist.
 *
 * Ein Smart-Teleskop hat eine Handvoll Schalter — Filter, Belichtungslänge, Mosaik, Heizung — und
 * jede Anleitung im Netz beantwortet sie als Faustregel („bei Nebeln Filter an"). Faustregeln sind
 * hier aber falsch, weil die richtige Antwort von vier Dingen gleichzeitig abhängt: vom Objekt, vom
 * Gerät, vom Himmel über dem Beobachtungsort und vom Mond dieser Nacht. Ein Dualbandfilter unter
 * Bortle 7 macht aus einer aussichtslosen Nacht eine gute; derselbe Filter auf einer Galaxie
 * verlängert die nötige Belichtungszeit um das Fünfzehnfache.
 *
 * Deshalb wird hier gerechnet statt nachgeschlagen, und zwar über **eine** Größe: wie lange
 * belichtet werden muss, um ein bestimmtes Signal-Rausch-Verhältnis zu erreichen. Für den
 * Himmelshintergrund als Rauschquelle gilt
 *
 * ```
 * SNR ∝ Signal · √t / √Hintergrund   ⟹   t ∝ Hintergrund / Signal²
 * ```
 *
 * Alles Weitere fällt daraus heraus: Der Filter dämpft den Hintergrund **und** das Signal, und
 * welche der beiden Dämpfungen überwiegt, entscheidet ganz von allein, ob er zu empfehlen ist —
 * ohne dass irgendwo „bei Nebeln an" stünde. Der Mond und die Bortle-Stufe gehen als Hintergrund
 * ein, die Öffnung als Signal. Damit beantwortet dieselbe Formel auch, warum ein S30 fast die
 * dreifache Zeit eines S50 braucht.
 *
 * Reines Kotlin, kein Android — prüfbar, und das ist bei einer Empfehlung, die Stunden am Feld
 * kostet, kein Luxus.
 */
object PhotoGuide {

    /**
     * Himmelshelligkeit je Bortle-Stufe in mag/arcsec², nach Bortles eigener Tabelle.
     *
     * Von hier aus wird alles Weitere relativ gerechnet, denn nur das Verhältnis zählt: Zwischen
     * Stufe 4 und Stufe 8 liegen dreizehn Mal so viel Hintergrundlicht und damit dreizehn Mal so
     * viel Belichtungszeit für dasselbe Bild.
     */
    private val SKY_BRIGHTNESS = mapOf(
        1 to 22.0, 2 to 21.7, 3 to 21.5, 4 to 21.3,
        5 to 20.8, 6 to 20.3, 7 to 19.5, 8 to 18.5, 9 to 17.8,
    )

    /** Die Stufe, auf die alle Angaben bezogen sind: der Übergang vom Land zur Vorstadt. */
    const val REFERENCE_BORTLE = 4

    /**
     * Flächenhelligkeit, bei der ein Objekt als „normal schwer" gilt.
     *
     * Bezugswert, kein Grenzwert — die Einheit folgt dem, was der Katalog in
     * [SkyObject.surfaceBrightness] führt und was [PhotographicInterest] bereits voraussetzt.
     */
    private const val REFERENCE_SURFACE_BRIGHTNESS = 21.5

    /** Minuten für ein brauchbares Bild: Bezugsgerät, Bezugshimmel, Bezugsobjekt, kein Mond. */
    private const val BASE_MINIMUM_MINUTES = 6.0

    /** Ab dem Vielfachen der Mindestzeit holt weiteres Belichten kaum noch etwas heraus. */
    private const val RECOMMENDED_MULTIPLE = 2.5

    /** Wie stark ein Vollmond dicht neben dem Objekt den Hintergrund anhebt. */
    private const val MOON_MAX_FACTOR = 12.0

    /** Unter so vielen Pixeln ist ein Objekt ein Fleck und kein Motiv. */
    private const val MIN_USEFUL_PIXELS = 40.0

    /** Bis hierhin bleibt es ein Detail im Bild statt das Bild. */
    private const val SMALL_PIXELS = 120.0

    /**
     * Rechnet den Plan.
     *
     * @param moonSeparationDeg Abstand zwischen Objekt und Mond. Null, wenn unbekannt — dann wird
     *   ein mittlerer Abstand angenommen, was ehrlicher ist, als den Mond zu ignorieren.
     * @param dewSpreadK Taupunktdifferenz aus der Vorhersage. Null, wenn keine vorliegt; dann
     *   entscheidet die Jahreszeit, und der Text sagt das auch.
     * @param availableHours wie lange das Objekt heute Nacht hoch genug und im Dunkeln steht.
     */
    fun plan(
        obj: SkyObject,
        telescope: SmartTelescope,
        bortle: Int,
        moonIlluminationPercent: Double,
        moonAltitudeDeg: Double,
        moonSeparationDeg: Double? = null,
        dewSpreadK: Double? = null,
        humidityPercent: Double? = null,
        season: Season? = null,
        availableHours: Double = 0.0,
    ): PhotoPlan {
        val sky = skyFactor(bortle) * moonFactor(
            moonIlluminationPercent, moonAltitudeDeg, moonSeparationDeg,
        )

        // Beide Möglichkeiten werden gerechnet, und die schnellere gewinnt. Dass daraus für Nebel
        // „Filter an" und für Galaxien „Filter aus" wird, ist ein Ergebnis und keine Regel.
        val unfiltered = exposureFactor(obj, sky, FilterKind.NONE)
        val filtered = exposureFactor(obj, sky, telescope.builtInFilter)
        val useFilter = telescope.builtInFilter != FilterKind.NONE && filtered < unfiltered * 0.9
        val factor = if (useFilter) filtered else unfiltered

        val minimum = BASE_MINIMUM_MINUTES * factor / telescope.lightGrasp
        val minimumMinutes = minimum.coerceIn(5.0, 600.0).toInt()
        val recommendedMinutes = (minimum * RECOMMENDED_MULTIPLE).coerceIn(10.0, 1200.0).toInt()

        val effectiveSky = sky / (if (useFilter) telescope.builtInFilter.skySuppression else 1.0)
        val framing = framing(obj, telescope)
        val dew = dewRisk(dewSpreadK, humidityPercent, season)

        return PhotoPlan(
            obj = obj,
            telescope = telescope,
            bortle = bortle,
            verdict = verdict(framing, telescope, minimumMinutes, availableHours),
            framing = framing,
            fillFraction = obj.sizeArcmin?.let { telescope.fillFraction(it) } ?: 0.0,
            objectPixels = obj.sizeArcmin?.let { telescope.objectPixels(it) } ?: 0.0,
            needsMosaic = framing == FramingVerdict.NEEDS_MOSAIC ||
                framing == FramingVerdict.TIGHT,
            useFilter = useFilter,
            filter = if (useFilter) telescope.builtInFilter else FilterKind.NONE,
            filterSpeedup = unfiltered / filtered,
            subExposureSec = subExposure(telescope, effectiveSky),
            minimumMinutes = minimumMinutes,
            recommendedMinutes = recommendedMinutes,
            availableMinutes = (availableHours * 60).toInt().coerceAtLeast(0),
            dew = dew,
            dewAction = dewAction(dew, telescope),
        )
    }

    /** Wie viel heller der Himmel ist als auf der Bezugsstufe. */
    fun skyFactor(bortle: Int): Double {
        val level = bortle.coerceIn(1, 9)
        val here = SKY_BRIGHTNESS.getValue(level)
        val reference = SKY_BRIGHTNESS.getValue(REFERENCE_BORTLE)
        return 10.0.pow(0.4 * (reference - here))
    }

    /**
     * Was der Mond zum Hintergrund beiträgt, als Faktor über dem mondlosen Himmel.
     *
     * Drei Dinge gehen ein, und alle drei sind für jeden nachvollziehbar, der einmal bei Vollmond
     * draußen war: **wie voll** er ist (überproportional, denn ein halber Mond ist weit weniger als
     * halb so hell wie ein voller), **wie hoch** er steht (am Horizont wird sein Licht von der
     * Luftmasse weggenommen) und **wie weit weg** vom Motiv er steht (das Streulicht ist um ihn
     * herum am stärksten). Unter dem Horizont trägt er nichts bei — dann ist es schlicht eine
     * mondlose Nacht.
     */
    fun moonFactor(
        illuminationPercent: Double,
        altitudeDeg: Double,
        separationDeg: Double?,
    ): Double {
        if (altitudeDeg <= 0.0) return 1.0
        val phase = (illuminationPercent / 100.0).coerceIn(0.0, 1.0)
        if (phase < 0.05) return 1.0
        val height = sin(Math.toRadians(altitudeDeg.coerceIn(0.0, 90.0)))
        val proximity = separationDeg
            ?.let { (1.0 - it / 180.0).coerceIn(0.0, 1.0) }
            ?: 0.5
        return 1.0 + MOON_MAX_FACTOR * phase.pow(2.0) * height * (0.35 + 0.65 * proximity)
    }

    /**
     * Die relative Belichtungszeit: `t ∝ Hintergrund / Signal²`.
     *
     * Hier steckt die ganze Filterentscheidung. Der Dualbandfilter nimmt dem Hintergrund das
     * Vierfache — und einer Galaxie neun Zehntel ihres Lichts, weil deren Strahlung ein Kontinuum
     * ist und nicht in zwei schmalen Linien liegt. Weil das Signal quadratisch eingeht, verliert er
     * damit auf Breitbandobjekten haushoch gegen sich selbst.
     */
    private fun exposureFactor(obj: SkyObject, sky: Double, filter: FilterKind): Double {
        val signal = filter.transmissionFor(obj)
        return sky / filter.skySuppression / (signal * signal) * difficulty(obj)
    }

    /**
     * Wie schwer dieses Objekt an sich ist, bezogen auf ein durchschnittliches.
     *
     * Über die **Flächenhelligkeit**, nicht über die Gesamthelligkeit: Ein Sensor sieht pro Pixel,
     * und M31 verteilt seine 3,4 mag über drei Grad. Wo der Katalog keine Flächenhelligkeit führt,
     * wird sie aus Helligkeit und Ausdehnung geschätzt — dieselbe Rechnung, die der Katalog selbst
     * angestellt hätte.
     */
    private fun difficulty(obj: SkyObject): Double {
        val surface = obj.surfaceBrightness ?: estimateSurfaceBrightness(obj) ?: return 1.0
        return 10.0.pow(0.4 * (surface - REFERENCE_SURFACE_BRIGHTNESS)).coerceIn(0.3, 8.0)
    }

    /** Helligkeit auf die Fläche verteilt; die Ellipse ist die übliche Näherung für die Ausdehnung. */
    private fun estimateSurfaceBrightness(obj: SkyObject): Double? {
        val magnitude = obj.magnitude ?: return null
        val major = obj.sizeArcmin ?: return null
        if (major <= 0.0) return null
        val minor = obj.minorAxisArcmin ?: major
        val areaArcmin = Math.PI / 4.0 * major * minor
        if (areaArcmin <= 0.0) return null
        // + 8,89 mag rechnet von Quadratbogenminuten auf Quadratbogensekunden um: 2,5·log₁₀(3600).
        return magnitude + 2.5 * log10(areaArcmin) + 8.89
    }

    /**
     * Wie lang ein Einzelbild sein darf.
     *
     * Nicht die Gesamtzeit, sondern der Schutz vor einem ausgefressenen Hintergrund: Je heller der
     * Himmel, desto früher läuft das Histogramm nach rechts, und ein überbelichteter Hintergrund
     * lässt sich in keiner Nachbearbeitung wiederherstellen. Zwei kürzere Bilder bringen dasselbe
     * Signal wie ein doppelt so langes, solange das Ausleserauschen klein bleibt — und bei diesen
     * Sensoren ist es das.
     */
    fun subExposure(telescope: SmartTelescope, effectiveSky: Double): Int {
        val ceiling = when {
            effectiveSky >= 8.0 -> 10
            effectiveSky >= 3.0 -> 20
            effectiveSky >= 1.2 -> 30
            else -> 60
        }
        return telescope.subExposuresSec.filter { it <= ceiling }.maxOrNull()
            ?: telescope.subExposuresSec.min()
    }

    /** Passt das Motiv, und ist es groß genug, um eines zu sein? */
    fun framing(obj: SkyObject, telescope: SmartTelescope): FramingVerdict {
        val size = obj.sizeArcmin ?: return FramingVerdict.SMALL
        val pixels = telescope.objectPixels(size)
        if (pixels < MIN_USEFUL_PIXELS) return FramingVerdict.TOO_SMALL
        val fill = telescope.fillFraction(size)
        return when {
            fill > 1.0 -> FramingVerdict.NEEDS_MOSAIC
            fill > 0.75 -> FramingVerdict.TIGHT
            pixels < SMALL_PIXELS -> FramingVerdict.SMALL
            else -> FramingVerdict.GOOD
        }
    }

    /**
     * Wie sicher Tau kommt.
     *
     * Die Taupunktdifferenz ist die belastbare Zahl: Unterschreitet die Lufttemperatur den Taupunkt
     * um weniger als zwei Kelvin, beschlägt eine nach oben gerichtete Frontlinse innerhalb einer
     * Stunde — sie kühlt durch Abstrahlung gegen den Nachthimmel unter die Lufttemperatur ab. Ohne
     * Vorhersage bleibt die Jahreszeit, und die ist ein schwaches Argument: klare Herbstnächte sind
     * die schlimmsten, weil lange Nacht und feuchte Luft zusammenkommen.
     */
    fun dewRisk(dewSpreadK: Double?, humidityPercent: Double?, season: Season?): DewRisk {
        dewSpreadK?.let { spread ->
            return when {
                spread < 2.0 -> DewRisk.CERTAIN
                spread < 4.0 -> DewRisk.LIKELY
                spread < 7.0 -> DewRisk.POSSIBLE
                else -> DewRisk.NONE
            }
        }
        humidityPercent?.let { humidity ->
            return when {
                humidity >= 92.0 -> DewRisk.CERTAIN
                humidity >= 85.0 -> DewRisk.LIKELY
                humidity >= 70.0 -> DewRisk.POSSIBLE
                else -> DewRisk.NONE
            }
        }
        return when (season) {
            Season.AUTUMN -> DewRisk.LIKELY
            Season.SPRING, Season.SUMMER -> DewRisk.POSSIBLE
            Season.WINTER -> DewRisk.POSSIBLE
            null -> DewRisk.POSSIBLE
        }
    }

    private fun dewAction(risk: DewRisk, telescope: SmartTelescope): String? = when {
        !risk.needsAction -> null
        telescope.hasDewHeater && risk == DewRisk.POSSIBLE ->
            "Die Anti-Tau-Heizung in der App bereithalten – sie zieht spürbar Akku, also erst " +
                "einschalten, wenn die Bilder weicher werden."
        telescope.hasDewHeater ->
            "Anti-Tau-Heizung von Anfang an einschalten und mit vollem Akku oder Powerbank starten; " +
                "eine einmal beschlagene Linse wird in derselben Nacht nicht mehr klar."
        risk == DewRisk.POSSIBLE ->
            "Beim ${telescope.model} gibt es keine Heizung: Eine Taukappe kostet nichts an Akku " +
                "und ist die einzige Vorsorge, die hier bleibt."
        else ->
            "Beim ${telescope.model} gibt es keine Heizung. Taukappe aufsetzen und die Frontlinse " +
                "stündlich prüfen – bei diesen Bedingungen reicht das Gehäuse allein nicht."
    }

    private fun verdict(
        framing: FramingVerdict,
        telescope: SmartTelescope,
        minimumMinutes: Int,
        availableHours: Double,
    ): GuideVerdict {
        val available = availableHours * 60.0
        return when {
            framing == FramingVerdict.TOO_SMALL -> GuideVerdict.POOR
            framing == FramingVerdict.NEEDS_MOSAIC && !telescope.hasMosaic -> GuideVerdict.POOR
            available < minimumMinutes * 0.6 -> GuideVerdict.POOR
            available < minimumMinutes -> GuideVerdict.MARGINAL
            else -> GuideVerdict.GOOD
        }
    }
}

/** Wie viel Hintergrundlicht der Filter wegnimmt. */
private val FilterKind.skySuppression: Double
    get() = when (this) {
        FilterKind.NONE -> 1.0
        FilterKind.BROADBAND -> 1.6
        FilterKind.DUAL_BAND -> 4.0
    }

/**
 * Wie viel vom Licht **dieses** Objekts durch den Filter kommt.
 *
 * Der eine Wert, an dem die ganze Empfehlung hängt: Ein Emissionsnebel strahlt in genau den Linien,
 * die der Dualbandfilter durchlässt, und verliert fast nichts. Eine Galaxie strahlt ein Kontinuum
 * über das ganze sichtbare Spektrum und verliert fast alles.
 */
private fun FilterKind.transmissionFor(obj: SkyObject): Double = when (this) {
    FilterKind.NONE -> 1.0
    FilterKind.BROADBAND -> if (obj.type.respondsToNarrowband) 0.9 else 0.75
    FilterKind.DUAL_BAND -> if (obj.type.respondsToNarrowband) 0.85 else 0.12
}
