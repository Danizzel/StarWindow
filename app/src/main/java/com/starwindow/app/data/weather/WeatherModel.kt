package com.starwindow.app.data.weather

import kotlinx.serialization.Serializable

/**
 * Die Wettermodelle, zwischen denen sich wählen lässt.
 *
 * **Warum überhaupt mehrere.** Für die Frage „welche Nacht der nächsten zwei Wochen" gibt es nur
 * eine Antwort: ein globales Modell, das so weit reicht. Für die Frage „reißt es heute Abend über
 * meinem Garten auf" ist dasselbe Modell die schlechteste Wahl — ein 25-km-Gitter kennt weder das
 * Tal noch den Bergrücken daneben, und genau daran entscheidet sich in Mitteleuropa, ob eine Nacht
 * klar wird. Deshalb steht hier beides nebeneinander: Kurzfristmodelle mit ein bis zwei Kilometern
 * Maschenweite und wenigen Tagen Reichweite, und ECMWF für die Planung.
 *
 * Die Auswahl ist bewusst kurz und auf das ausgerichtet, was die Bewölkung kleinräumig trifft.
 * Auflösung und Abdeckung stehen hier fest, weil sie sich praktisch nie ändern; wann ein Modell
 * zuletzt gerechnet hat, wird dagegen live abgefragt ([WeatherModelStatus]) — geraten wäre bei
 * genau der Zahl das Falsche.
 */
@Serializable
enum class WeatherModel(
    /** Bezeichner für den `models=`-Parameter der Vorhersage-Schnittstelle. */
    val id: String,
    /** Pfad unter `/data/…/static/meta.json`, wo der Lauf-Zeitpunkt steht. */
    val metaPath: String,
    val label: String,
    val provider: String,
    /** Maschenweite in Kilometern — das eine Maß, an dem hier alles hängt. */
    val resolutionKm: Double,
    val coverage: String,
    /** Ein Satz dazu, wofür man dieses Modell nimmt. */
    val about: String,
    /**
     * Wie viele Tage angefragt werden.
     *
     * Nur eine Sparmaßnahme für die Anfrage: Wer mehr verlangt, als ein Modell hergibt, bekommt
     * den Rest als Spalte aus Nullwerten. Was tatsächlich vorliegt, entscheidet sich beim
     * Auswerten der Antwort, nicht hier.
     */
    val requestDays: Int,
) {
    ICON_CH1(
        id = "meteoswiss_icon_ch1",
        metaPath = "meteoswiss_icon_ch1",
        label = "ICON-CH1",
        provider = "MeteoSchweiz",
        resolutionKm = 1.0,
        coverage = "Alpenraum",
        about = "Das feinste Gitter über den Alpen. Löst einzelne Täler auf – dort, wo sich Nebel " +
            "und Föhnlücken auf wenigen Kilometern entscheiden, ist kein anderes Modell näher dran.",
        requestDays = 2,
    ),
    AROME_HD(
        id = "meteofrance_arome_france_hd",
        metaPath = "meteofrance_arome_france_hd",
        label = "AROME HD",
        provider = "Météo-France",
        resolutionKm = 1.5,
        coverage = "Frankreich, Benelux, Südwestdeutschland, Alpenrand",
        about = "Sehr fein und stark bei konvektiver Bewölkung. Führt keine Gesamtbedeckung – die " +
            "App rechnet dann mit der dichtesten der drei Schichten.",
        requestDays = 3,
    ),
    HARMONIE(
        id = "knmi_harmonie_arome_europe",
        metaPath = "knmi_harmonie_arome_europe",
        label = "HARMONIE",
        provider = "KNMI",
        resolutionKm = 2.0,
        coverage = "Nordwesteuropa, Nordsee, Norddeutschland",
        about = "Rechnet stündlich neu und ist damit das aktuellste Modell der Liste – für die " +
            "Küste und die norddeutsche Tiefebene die erste Wahl.",
        requestDays = 3,
    ),
    ICON_D2(
        id = "icon_d2",
        metaPath = "dwd_icon_d2",
        label = "ICON-D2",
        provider = "DWD",
        resolutionKm = 2.2,
        coverage = "Deutschland, Alpen, Nachbarländer",
        about = "Das Kurzfristmodell des DWD. Für heute Abend und morgen die genaueste Aussage " +
            "über Wolkenlücken, die es frei gibt – dafür reicht es nur gut zwei Tage.",
        requestDays = 3,
    ),
    ICON_EU(
        id = "icon_eu",
        metaPath = "dwd_icon_eu",
        label = "ICON-EU",
        provider = "DWD",
        resolutionKm = 7.0,
        coverage = "Europa",
        about = "Der Kompromiss des DWD: deutlich feiner als ein globales Gitter und reicht doch " +
            "über das Wochenende. Für die meisten Nächte die vernünftigste Wahl.",
        requestDays = 6,
    ),
    ECMWF_IFS(
        id = "ecmwf_ifs025",
        metaPath = "ecmwf_ifs025",
        label = "ECMWF IFS",
        provider = "ECMWF",
        resolutionKm = 25.0,
        coverage = "global",
        about = "Grob im Gitter, aber als einziges 15 Tage weit – das Modell für die Frage, an " +
            "welchem Abend der nächsten zwei Wochen man sich den Wecker stellt.",
        requestDays = 15,
    );

    /** „2,2 km" — die Maschenweite, wie sie am Chip steht. */
    val resolutionLabel: String
        get() = if (resolutionKm < 10) {
            "%.1f km".format(java.util.Locale.GERMAN, resolutionKm)
        } else {
            "%.0f km".format(java.util.Locale.GERMAN, resolutionKm)
        }

    companion object {
        /**
         * Voreinstellung.
         *
         * ECMWF, obwohl es das gröbste ist: Die Ansicht öffnet mit der Liste der nächsten zwei
         * Wochen, und die kann kein anderes Modell füllen. Wer weiß, dass es um heute Abend geht,
         * schaltet mit einem Tipp auf ICON-D2 um.
         */
        val DEFAULT = ECMWF_IFS

        /** Reihenfolge in der Auswahl: das feinste Gitter zuerst. */
        val ORDERED: List<WeatherModel> = entries.sortedBy { it.resolutionKm }
    }
}

/** Die Ecken eines Modellgebiets. */
data class ModelBounds(
    val minLatitudeDeg: Double,
    val minLongitudeDeg: Double,
    val maxLatitudeDeg: Double,
    val maxLongitudeDeg: Double,
) {
    fun contains(latitudeDeg: Double, longitudeDeg: Double): Boolean =
        latitudeDeg in minLatitudeDeg..maxLatitudeDeg &&
            longitudeDeg in minLongitudeDeg..maxLongitudeDeg
}

/**
 * Was der Dienst über den letzten Lauf eines Modells sagt.
 *
 * [dataEndMillis] ist ausdrücklich das Ende **dieses Laufs** und nicht das Ende der Vorhersage:
 * Der DWD rechnet ICON-EU um 00, 06, 12 und 18 UTC auf 120 Stunden, dazwischen aber Kurzläufe auf
 * 30 Stunden. Die Vorhersage-Schnittstelle setzt beide zusammen und reicht damit weiter, als der
 * jüngste Lauf allein. Wie weit sie wirklich reicht, steht deshalb am geladenen Lauf und nicht hier.
 */
data class WeatherModelStatus(
    val model: WeatherModel,
    /** Zeitpunkt, für den der Lauf gestartet ist (00, 06, 12, 18 UTC und so weiter). */
    val runMillis: Long?,
    /** Ab wann das Ergebnis abrufbar war — das ist „zuletzt aktualisiert". */
    val availableSinceMillis: Long?,
    /** Wie weit dieser Lauf reicht. */
    val dataEndMillis: Long?,
    /** Abstand zwischen zwei Läufen in Sekunden. */
    val updateIntervalSeconds: Int?,
    /** Zeitschritt der Rohdaten in Sekunden; ECMWF rechnet dreistündlich, die Anzeige ist stündlich. */
    val stepSeconds: Int?,
    val bounds: ModelBounds?,
) {
    /** True, wenn das Modellgebiet den Ort enthält. Ohne bekannte Grenzen wird nicht behauptet. */
    fun covers(latitudeDeg: Double, longitudeDeg: Double): Boolean =
        bounds?.contains(latitudeDeg, longitudeDeg) ?: true

    /** Wie alt der Lauf ist. */
    fun ageMillis(nowMillis: Long = System.currentTimeMillis()): Long? =
        availableSinceMillis?.let { (nowMillis - it).coerceAtLeast(0L) }

    /** Läuft der Lauf schon länger als sein eigenes Intervall, steht der nächste an. */
    fun isOverdue(nowMillis: Long = System.currentTimeMillis()): Boolean {
        val age = ageMillis(nowMillis) ?: return false
        val interval = updateIntervalSeconds ?: return false
        return age > interval * 1000L * 2
    }
}
