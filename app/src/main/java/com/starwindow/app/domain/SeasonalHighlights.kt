package com.starwindow.app.domain

import com.starwindow.app.core.astro.ObserverLocation
import com.starwindow.app.core.geometry.SphericalGeometry
import com.starwindow.app.data.catalog.ObjectType
import com.starwindow.app.data.catalog.SkyObject
import java.time.LocalDate
import java.time.ZoneId

/**
 * Die vier Jahreszeiten, wie ein Astrofotograf sie zählt.
 *
 * Der Untertitel ist keine Verzierung, sondern die Antwort auf „warum ausgerechnet jetzt": Jede
 * Jahreszeit zeigt einen anderen Teil der Milchstraße, und daran hängt, was überhaupt am Himmel
 * steht. Im Frühling blickt man senkrecht aus der Scheibe heraus und sieht bis zu den fernen
 * Galaxienhaufen; im Sommer liegt das Zentrum der eigenen Galaxie im Süden und mit ihm ihre
 * Staub- und Wasserstoffwolken.
 */
enum class Season(val label: String, val subtitle: String, val story: String) {
    SPRING(
        "Frühling",
        "Galaxienzeit",
        "Der Blick geht senkrecht aus der Milchstraße heraus, kein Staub steht im Weg – die " +
            "Monate, in denen ferne Galaxien zu Dutzenden in ein Bildfeld passen.",
    ),
    SUMMER(
        "Sommer",
        // Nicht „Wasserstoff": Was am Himmel steht, sind Nebel — dass sie überwiegend aus
        // angeregtem Wasserstoff bestehen, erklärt die Farbe und gehört in die Objektbeschreibung,
        // nicht in die Überschrift einer Jahreszeit.
        "Milchstraße und Sommernebel",
        "Das Zentrum der eigenen Galaxie zieht über den Südhorizont. Die Nächte sind kurz und " +
            "selten ganz dunkel, dafür ist der Himmel so hell besetzt wie nie.",
    ),
    AUTUMN(
        "Herbst",
        "Andromeda und die großen Nebel",
        "Lange Nächte kommen zurück, während die Nachbargalaxien hoch stehen und die " +
            "Wasserstoffwolken der Cassiopeia den Zenit durchqueren.",
    ),
    WINTER(
        "Winter",
        "Helle Nebel",
        "Die längsten und klarsten Nächte des Jahres, und mit Orion die hellsten Nebel " +
            "überhaupt – die Jahreszeit, in der auch kurze Belichtungen schon etwas zeigen.",
    );

    companion object {

        /**
         * Die Jahreszeit an diesem Datum, an diesem Ort.
         *
         * Die Breite steht hier, weil die Frage südlich des Äquators umgekehrt beantwortet wird:
         * Im Januar ist in Kapstadt Sommer, und ein Hub, der ihn „Winter" nennt, verwechselt die
         * Jahreszeit mit dem Kalender des Programmierers. Der Himmel selbst rechnet ohnehin richtig
         * – dort steht im Januar Orion – nur der Name wäre falsch.
         */
        fun of(date: LocalDate, latitudeDeg: Double): Season {
            val month = if (latitudeDeg < 0.0) {
                ((date.monthValue + 5) % 12) + 1
            } else {
                date.monthValue
            }
            return when (month) {
                3, 4, 5 -> SPRING
                6, 7, 8 -> SUMMER
                9, 10, 11 -> AUTUMN
                else -> WINTER
            }
        }
    }
}

/** Wonach der Hub die Motive einer Jahreszeit in Reihen sortiert. */
enum class TargetKind(val label: String) {
    NEBULA("Nebel"),
    GALAXY("Galaxien"),
    CLUSTER("Sternhaufen"),
    OTHER("Weitere");

    companion object {
        fun of(type: ObjectType): TargetKind = when (type) {
            ObjectType.GALAXY, ObjectType.GALAXY_GROUP -> GALAXY

            // Der Orionnebel ist im Katalog ein CLUSTER_NEBULA und für jeden, der ihn fotografiert,
            // ein Nebel. Die Reihe folgt dem, was auf dem Bild zu sehen ist, nicht der Systematik.
            ObjectType.NEBULA, ObjectType.EMISSION_NEBULA, ObjectType.REFLECTION_NEBULA,
            ObjectType.DARK_NEBULA, ObjectType.PLANETARY_NEBULA, ObjectType.SUPERNOVA_REMNANT,
            ObjectType.CLUSTER_NEBULA,
            -> NEBULA

            ObjectType.OPEN_CLUSTER, ObjectType.GLOBULAR_CLUSTER -> CLUSTER

            else -> OTHER
        }
    }
}

/**
 * Ein Motiv im Hub: das Objekt, und warum es gerade jetzt dort steht.
 *
 * Die Nachtwerte kommen aus [ObservationPlanner] für eine konkrete Nacht — nicht für „den Herbst"
 * im Allgemeinen. Genau daran ändert sich die Liste von Woche zu Woche: Der Himmel dreht sich in
 * einem Monat um zwei Stunden weiter, und was Anfang September erst gegen Morgen hoch steht, steht
 * Ende Oktober schon am Abend dort.
 */
data class SeasonalTarget(
    val obj: SkyObject,
    val night: ObservationNight,
    val kind: TargetKind,
    /** 0–100, kuratiert: wie oft dieses Motiv tatsächlich fotografiert wird. */
    val popularity: Int,
    /** 0–100, wie lohnend das Objekt fotografisch überhaupt ist. */
    val interest: Double,
    /** Die Mischung aus beidem und der Nacht — die Reihenfolge im Hub. */
    val score: Double,
) {
    /** Wie lange sich in dieser Nacht belichten lässt, in Stunden. */
    val usableHours: Double get() = night.usableMillis / 3_600_000.0

    /** Steht dieses Motiv jetzt so gut, wie es an diesem Ort überhaupt stehen kann? */
    val isAtItsBest: Boolean get() = night.score >= 75.0

    /**
     * Die eine Zeile unter dem Bild: drei Zahlen, die zusammen die Nacht beschreiben.
     *
     * Stunden zuerst, weil das die Zahl ist, nach der geplant wird — sie ist die Belichtungszeit,
     * die die Nacht hergibt. Die Höhe sagt, durch wie viel Luft hindurch, der Mond, wogegen.
     */
    val headline: String
        get() = buildString {
            append("%.1f h nutzbar".format(usableHours))
            append("  ·  %.0f° hoch".format(night.bestAltitudeDeg))
            append(
                when {
                    night.moonAltitudeDeg < 0.0 -> "  ·  Mond unter dem Horizont"
                    else -> "  ·  Mond %.0f %%".format(night.moonIlluminationPercent)
                }
            )
        }
}

/**
 * Was an einem Ort in einer Jahreszeit am besten zu fotografieren ist.
 *
 * Drei Dinge entscheiden das, und keines davon allein reicht:
 *
 * 1. **Was der Himmel hergibt.** [ObservationPlanner] rechnet für eine konkrete Nacht aus, wie
 *    lange ein Objekt gleichzeitig hoch genug und im Dunkeln steht. Das ist die einzige der drei
 *    Zahlen, die vom Ort abhängt — und sie ist der Grund, warum der Hub in Hamburg etwas anderes
 *    zeigt als in Kapstadt, ohne dass irgendwo eine Liste für Kapstadt läge.
 * 2. **Was fotografisch etwas hergibt.** [PhotographicInterest] — Größe vor Helligkeit, weil eine
 *    Kamera Licht sammeln, aber keine Auflösung erfinden kann.
 * 3. **Was Menschen tatsächlich fotografieren.** Das steht in keinem Katalog. Der Orionnebel ist
 *    nach jeder rechenbaren Größe ein Objekt unter dreizehntausend; er ist trotzdem das Bild, das
 *    fast jeder als erstes macht. Diese eine Zahl ist kuratiert — siehe [Popularity].
 *
 * Die Gewichtung stellt bewusst keine der drei über die anderen: Ein berühmtes Objekt, das gerade
 * am Taghimmel steht, gehört nicht in den Hub, und eine perfekt platzierte anonyme Galaxie
 * dreizehnter Größe auch nicht.
 *
 * Reines Kotlin, kein Android — eine Beurteilung von Daten, und damit prüfbar.
 */
object SeasonalHighlights {

    /**
     * Ab welcher Höhe ein Motiv für den Hub zählt.
     *
     * Niedriger als die 30° der Jahresplanung, und das mit Absicht: Von Berlin aus erreicht der
     * Orionnebel überhaupt nur 32°, und ein Hub, der ihn deshalb aus dem Winter streicht, hat die
     * Frage falsch verstanden. Die Planung sagt „wann wird das gut"; der Hub sagt „was ist jetzt
     * da". Wie tief es steht, steht als Zahl auf der Karte.
     */
    const val MIN_ALTITUDE_DEG = 20.0

    /** Weniger als das ist keine Nacht, sondern ein Zeitfenster zum Aufbauen. */
    const val MIN_USEFUL_MILLIS = 45 * 60_000L

    /** Wie viele Objekte überhaupt durchgerechnet werden. Der Rest ist nach Größe chancenlos. */
    private const val CANDIDATE_LIMIT = 420

    /**
     * Näher beieinander als das, und es sind zwei Katalogeinträge für dasselbe Foto.
     *
     * Der Rosettennebel steht als `NGC 2237` und `NGC 2238` im Katalog, der Cirrusnebel in vier
     * Teilen. Ohne diese Regel füllt sich die Reihe mit Wiederholungen desselben Bildausschnitts.
     */
    private const val DUPLICATE_SEPARATION_DEG = 0.75

    /**
     * Die Motive der Jahreszeit, bestes zuerst.
     *
     * @param date die Nacht, für die gerechnet wird. Der Aufrufer wählt sie — heute für die
     *   laufende Jahreszeit, sonst deren Mitte; siehe [referenceDate].
     */
    fun build(
        objects: List<SkyObject>,
        observer: ObserverLocation,
        zone: ZoneId,
        date: LocalDate,
        limit: Int = 24,
    ): List<SeasonalTarget> {
        val candidates = candidates(objects)
        val scored = candidates.mapNotNull { obj ->
            val night = ObservationPlanner.nightFor(
                positionJ2000 = obj.equatorialJ2000,
                observer = observer,
                zone = zone,
                date = date,
                minAltitudeDeg = MIN_ALTITUDE_DEG,
            )
            if (night.usableMillis < MIN_USEFUL_MILLIS) return@mapNotNull null

            val popularity = Popularity.of(obj)
            val interest = PhotographicInterest.score(obj)
            SeasonalTarget(
                obj = obj,
                night = night,
                kind = TargetKind.of(obj.type),
                popularity = popularity,
                interest = interest,
                score = 0.40 * night.score + 0.35 * popularity + 0.25 * interest,
            )
        }

        return dropDuplicates(scored.sortedByDescending { it.score }).take(limit)
    }

    /**
     * Die Reihen des Hubs: eine je Art, in der Reihenfolge, in der die Jahreszeit sie füllt.
     *
     * Sortiert danach, welche Art in dieser Nacht am besten dasteht, statt nach einer festen
     * Reihenfolge — im Frühling stehen die Galaxien oben, im Winter die Nebel, und das ergibt sich
     * aus den Zahlen, ohne dass es irgendwo geschrieben stünde.
     */
    fun rows(targets: List<SeasonalTarget>): List<Pair<TargetKind, List<SeasonalTarget>>> =
        targets.groupBy { it.kind }
            .toList()
            .sortedByDescending { (_, entries) -> entries.firstOrNull()?.score ?: 0.0 }

    /**
     * Für welche Nacht eine Jahreszeit gerechnet wird.
     *
     * Läuft sie gerade, ist es heute — der Hub soll den Himmel dieser Woche zeigen, nicht den
     * Durchschnitt eines Vierteljahres. Für die anderen drei ist es die Mitte ihres nächsten
     * Auftretens, also die Nacht, die sie am ehesten repräsentiert.
     */
    fun referenceDate(season: Season, today: LocalDate, latitudeDeg: Double): LocalDate {
        if (Season.of(today, latitudeDeg) == season) return today
        var date = today
        var guard = 0
        while (Season.of(date, latitudeDeg) != season && guard < 400) {
            date = date.plusDays(1)
            guard++
        }
        // date steht jetzt auf dem ersten Tag der Jahreszeit; +45 Tage ist ihre Mitte.
        return date.plusDays(45)
    }

    /**
     * Die Vorauswahl.
     *
     * Dreizehntausend Einträge einzeln durch die Nachtrechnung zu schicken kostet mehr, als der
     * Hub wert ist, und ändert am Ergebnis nichts: Was weder groß noch bekannt ist, steht auch bei
     * perfekter Platzierung nicht in den ersten zwanzig. Alles Kuratierte kommt ungeprüft mit, denn
     * genau dort steckt das Wissen, das die berechenbaren Zahlen nicht haben.
     */
    private fun candidates(objects: List<SkyObject>): List<SkyObject> {
        // Sonne und Mond haben keine Jahreszeit — der Mond wechselt seine im Monatstakt, und die
        // Sonne ist das eine Objekt, vor dessen Fotografie gewarnt gehört.
        val targets = objects.filter { !it.isMoving && PhotographicInterest.isPhotoTarget(it) }
        val curated = targets.filter { Popularity.of(it) > 0 }
        val curatedIds = curated.map { it.id }.toSet()
        val rest = targets.asSequence()
            .filter { it.id !in curatedIds }
            .sortedByDescending { PhotographicInterest.score(it) }
            .take(CANDIDATE_LIMIT)
        return curated + rest
    }

    /** Behält von mehreren Einträgen auf demselben Fleck Himmel den bestbewerteten. */
    private fun dropDuplicates(sorted: List<SeasonalTarget>): List<SeasonalTarget> {
        val kept = mutableListOf<SeasonalTarget>()
        for (target in sorted) {
            val duplicate = kept.any { other ->
                SphericalGeometry.separationDeg(
                    target.obj.equatorialJ2000.toVector(),
                    other.obj.equatorialJ2000.toVector(),
                ) < DUPLICATE_SEPARATION_DEG
            }
            if (!duplicate) kept += target
        }
        return kept
    }
}

/**
 * Wie oft ein Motiv tatsächlich fotografiert wird, von 0 bis 100.
 *
 * Die eine Zahl in diesem Programm, die sich nicht ausrechnen lässt. Alles andere im Hub folgt aus
 * Katalogwerten und Himmelsmechanik; dass aber M42 das erste Bild fast jedes Anfängers ist und die
 * gleich große, gleich helle Galaxie nebenan nicht, ist eine Tatsache über Menschen. Sie steht hier
 * als kurze Liste statt als Formel, weil eine Formel sie nur verstecken würde.
 *
 * Zusammengetragen aus den gängigen Saisonlisten der Astrofotografie (AstroBackyard, Galactic
 * Hunter, ZWO) — also aus dem, was tatsächlich empfohlen und gezeigt wird, nicht aus einem Urteil
 * über die Objekte. Die Liste ist bewusst kurz: Sie soll die zwei Dutzend Motive tragen, die jeder
 * kennt, und den Rest der Rechnung überlassen.
 *
 * Gesucht wird über **alle** Bezeichnungen eines Eintrags und in derselben Faltung wie die Suche,
 * damit `M 45`, `M45` und `Mel022` denselben Eintrag treffen — und damit der Pferdekopfnebel
 * gefunden wird, der im Katalog `B033` heißt und `B 33` als Zweitnamen führt.
 */
object Popularity {

    /** Die Bilder, die praktisch jeder macht. */
    private val ICONIC = listOf("M42", "M31", "M45", "M51", "M13", "M8", "M20", "M16", "M27")

    /** Fester Bestandteil jeder Saisonliste. */
    private val CLASSIC = listOf(
        "M33", "M57", "M81", "M82", "M101", "M104", "M97", "M1", "M17", "M11", "M22", "M3",
        "M63", "M106", "M64", "M65", "M66", "M78", "M44", "M35", "M36", "M37", "M38",
        "NGC 7000", "NGC 6960", "NGC 6992", "NGC 2237", "NGC 869", "NGC 884", "NGC 7293",
        "IC 434", "B 33", "IC 1805", "IC 1848", "NGC 6888", "NGC 281", "NGC 7635", "NGC 1499",
        "NGC 2024", "NGC 2264", "NGC 5128", "NGC 253",
    )

    /** Bekannt und oft gezeigt, aber schon eine Stufe für Fortgeschrittene. */
    private val KNOWN = listOf(
        "M74", "M76", "M110", "M32", "M83", "M77", "M2", "M15", "M92", "M4", "M12", "M10",
        "NGC 3628", "NGC 4565", "NGC 891", "NGC 7331", "NGC 6543", "NGC 7023", "NGC 7380",
        "IC 405", "IC 410", "IC 443", "IC 1396", "IC 5070", "IC 5146", "IC 2118",
        "Sh2-155", "Sh2-129", "Sh2-171", "NGC 6820", "NGC 1333", "NGC 2170", "NGC 4038",
        "Mel 111", "NGC 6231", "NGC 3372", "NGC 2070", "NGC 6334",
    )

    private const val ICONIC_SCORE = 100
    private const val CLASSIC_SCORE = 78
    private const val KNOWN_SCORE = 56

    private val byDesignation: Map<String, Int> = buildMap {
        KNOWN.forEach { put(ObjectSearch.fold(it), KNOWN_SCORE) }
        CLASSIC.forEach { put(ObjectSearch.fold(it), CLASSIC_SCORE) }
        ICONIC.forEach { put(ObjectSearch.fold(it), ICONIC_SCORE) }
    }

    /** Der höchste Wert, den irgendeine Bezeichnung dieses Eintrags trägt, sonst 0. */
    fun of(obj: SkyObject): Int =
        obj.allIdentifiers.maxOfOrNull { byDesignation[ObjectSearch.fold(it)] ?: 0 } ?: 0
}
