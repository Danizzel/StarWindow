package com.starwindow.app.domain

import com.starwindow.app.data.catalog.EphemerisCatalog
import com.starwindow.app.data.catalog.ObjectType
import com.starwindow.app.data.catalog.SkyObject

/**
 * A short plain-language description of one object.
 *
 * @param whatItIs what kind of thing this is, in a sentence. Always present — it comes from the
 *   type, which every catalogue entry has.
 * @param note what makes *this* one worth looking at. Only the well-known objects have one; the
 *   catalogue runs to three thousand entries and most of them are anonymous galaxies.
 * @param traits facts derived from the entry's own numbers — how big it looks, how its light is
 *   spread, whether a filter will help. These say something for every object, including the ones
 *   nobody has ever written a sentence about.
 */
data class ObjectDescription(
    val whatItIs: String,
    val note: String?,
    val traits: List<String>,
) {

/**
 * Turns a catalogue entry into something a person can read.
 *
 * The catalogue answers "where" precisely and "what" not at all: `EMISSION_NEBULA, 6.0 mag, 120'`
 * is a complete description to the transit calculator and tells a beginner nothing about why they
 * should point a camera at it. Two layers fix that. The **type** carries almost all of the meaning
 * and can be explained once for all three thousand entries — an emission nebula is a different
 * photographic problem from a reflection nebula, and knowing which is which is worth more than any
 * single number. The **traits** then turn the entry's own figures into statements rather than
 * units: 190 arcminutes means nothing, "six times the width of the full Moon" means something.
 */
companion object {

    /**
     * @param nowMillis nur für Sonne und Mond von Belang: Ihre Notiz ist keine feste Zeile,
     *   sondern der Zustand des Augenblicks — Phase, Beleuchtung, Abstand. Eine hingeschriebene
     *   Notiz wäre an den meisten Tagen falsch.
     */
    fun describe(
        obj: SkyObject,
        note: String? = null,
        nowMillis: Long = System.currentTimeMillis(),
    ): ObjectDescription = ObjectDescription(
        whatItIs = whatItIs(obj.type),
        note = note?.takeIf { it.isNotBlank() }
            ?: obj.body?.let { EphemerisCatalog.stateText(it, nowMillis) },
        traits = traits(obj),
    )

    /** The type, explained. This is the part that answers "what am I even looking at". */
    fun whatItIs(type: ObjectType): String = when (type) {
        ObjectType.STAR ->
            "Ein Stern – eine Sonne, so weit entfernt, dass auch das größte Teleskop nur einen " +
                "Lichtpunkt daraus macht. Was man an ihm sieht, ist Farbe und Helligkeit."

        ObjectType.DOUBLE_STAR ->
            "Zwei Sonnen, die einander umkreisen. Reizvoll sind vor allem die Farbkontraste – " +
                "oft steht ein gelblicher Riese neben einem bläulichen Begleiter."

        ObjectType.GALAXY ->
            "Eine Galaxie: ein eigenes Sternsystem aus Milliarden Sonnen, Millionen Lichtjahre " +
                "entfernt. Ihr Licht ist über die ganze Fläche verteilt und deshalb schwach – " +
                "Galaxien brauchen einen dunklen Himmel, kein größeres Teleskop."

        ObjectType.GALAXY_GROUP ->
            "Mehrere Galaxien, die durch ihre Schwerkraft aneinander gebunden sind. Im Bild " +
                "stehen sie zusammen in einem Feld, oft mit Gezeitenbrücken zwischen sich."

        ObjectType.NEBULA ->
            "Eine Wolke aus Gas und Staub zwischen den Sternen – der Rohstoff, aus dem Sterne " +
                "entstehen und in den sie am Ende zurückfallen."

        ObjectType.EMISSION_NEBULA ->
            "Ein Emissionsnebel: Wasserstoff, den heiße junge Sterne zum Leuchten anregen. Er " +
                "strahlt fast sein ganzes Licht in wenigen schmalen Linien ab, vor allem im tiefen " +
                "Rot des Wasserstoffs – deshalb wirken Schmalbandfilter hier Wunder und deshalb " +
                "sieht das Auge meist nur ein blasses Grau, wo die Kamera kräftiges Rot aufnimmt."

        ObjectType.REFLECTION_NEBULA ->
            "Ein Reflexionsnebel: Staub, der das Licht benachbarter Sterne einfach zurückwirft. Er " +
                "erzeugt kein eigenes Licht, sondern streut fremdes – und weil kurze Wellenlängen " +
                "stärker gestreut werden, ist er typisch blau. Schmalbandfilter helfen hier " +
                "**nicht**, im Gegenteil: sie werfen genau das Licht weg, aus dem er besteht."

        ObjectType.DARK_NEBULA ->
            "Ein Dunkelnebel: kalter Staub, der selbst gar nichts abstrahlt. Sichtbar wird er nur " +
                "als Silhouette vor etwas Hellerem – ein Loch im Sternfeld, kein Objekt darin."

        ObjectType.PLANETARY_NEBULA ->
            "Ein Planetarischer Nebel: die abgestoßene Hülle eines sterbenden sonnenähnlichen " +
                "Sterns, in deren Mitte der ausgeglühte Kern zurückbleibt. Mit Planeten hat er " +
                "nichts zu tun – der Name stammt daher, dass er im kleinen Fernrohr wie ein " +
                "Planetenscheibchen aussieht. Klein und flächenhell, also eines der wenigen " +
                "Deep-Sky-Ziele, die auch bei Mondschein und Stadthimmel funktionieren."

        ObjectType.SUPERNOVA_REMNANT ->
            "Der Überrest einer Supernova: die zerfetzte Hülle eines explodierten massereichen " +
                "Sterns, die als dünne Schale durchs All rast. Nach Jahrtausenden bleiben " +
                "filigrane Fäden übrig, die im Bild wie Rauchschwaden wirken."

        ObjectType.OPEN_CLUSTER ->
            "Ein offener Sternhaufen: einige Dutzend bis einige hundert Sterne, die gemeinsam aus " +
                "derselben Wolke entstanden sind. Sie halten nur lose zusammen und werden sich in " +
                "einigen hundert Millionen Jahren verlaufen. Hell und schon im Fernglas schön."

        ObjectType.GLOBULAR_CLUSTER ->
            "Ein Kugelsternhaufen: Hunderttausende uralte Sterne, zu einer dichten Kugel " +
                "zusammengeballt, die den Halo unserer Galaxis umkreist. Mit rund zwölf " +
                "Milliarden Jahren gehören sie zum Ältesten, was am Himmel steht."

        ObjectType.CLUSTER_NEBULA ->
            "Ein junger Sternhaufen, der noch in der Gaswolke steckt, aus der er entstanden ist – " +
                "Sternentstehung, sozusagen auf frischer Tat ertappt."

        ObjectType.SOLAR_SYSTEM ->
            "Ein Körper des Sonnensystems: Seine Position steht in keinem Katalog, sondern wird " +
                "für jeden Zeitpunkt neu gerechnet. Er wandert damit nicht nur mit der Erddrehung " +
                "über den Himmel wie die Sterne, sondern zusätzlich vor ihnen entlang – der Mond " +
                "um seinen eigenen Durchmesser pro Stunde. Deshalb gilt jede Angabe zu ihm nur " +
                "für den Augenblick, in dem sie gemacht wurde."

        ObjectType.OTHER ->
            "Ein Eintrag des Deep-Sky-Katalogs außerhalb der gängigen Klassen."
    }

    /**
     * What the entry's own numbers say, put into words.
     *
     * Only what actually changes a decision at the eyepiece or the camera. "190 Bogenminuten" is a
     * unit; "sechsmal so breit wie der Vollmond" is a fact about the evening.
     */
    private fun traits(obj: SkyObject): List<String> = buildList {
        obj.sizeArcmin?.let { size ->
            val moons = size / MOON_DIAMETER_ARCMIN
            when {
                moons >= 2.0 -> add(
                    "Mit %.1f' erscheint es rund %.0f-mal so breit wie der Vollmond – ein Fall für " .format(size, moons) +
                        "kurze Brennweiten, im Teleskop passt nur ein Ausschnitt ins Bild."
                )
                moons >= 0.6 -> add(
                    "Mit %.0f' ist es etwa so groß wie der Vollmond.".format(size)
                )
                size >= 5.0 -> add(
                    "Mit %.0f' deutlich ausgedehnt, im Fernglas als Fleck erkennbar.".format(size)
                )
                else -> add(
                    "Mit %.1f' klein – hier lohnt sich Brennweite.".format(size)
                )
            }
        }

        obj.surfaceBrightness?.let { surface ->
            when {
                surface <= 20.0 -> add(
                    "Flächenhelligkeit %.1f mag/□' – kompakt genug, dass auch Mondschein und " .format(surface) +
                        "Stadthimmel es nicht wegwaschen."
                )
                surface >= 23.0 -> add(
                    "Flächenhelligkeit %.1f mag/□' – sein Licht ist weit verteilt und damit " .format(surface) +
                        "empfindlich gegen Aufhellung. Es braucht einen wirklich dunklen Himmel."
                )
                else -> add(
                    "Flächenhelligkeit %.1f mag/□' – unter halbwegs dunklem Himmel machbar." .format(surface)
                )
            }
        }

        if (obj.type.respondsToNarrowband) {
            add(
                "Emissionsobjekt: Ein Schmalbandfilter (H-alpha, O III) hebt es deutlich heraus und " +
                    "arbeitet auch gegen Lichtverschmutzung."
            )
        }
        if (obj.type == ObjectType.REFLECTION_NEBULA) {
            add("Kein Schmalbandfilter – er würde gerade das Licht wegfiltern, das den Nebel ausmacht.")
        }

        obj.separationArcsec?.let { separation ->
            when {
                separation < 3.0 -> add(
                    "Die beiden stehen %.1f\" auseinander – eng. Das trennt nur ein Teleskop bei " .format(separation) +
                        "ruhiger Luft, und im Handybild bleiben sie ein Punkt."
                )
                separation < 30.0 -> add(
                    "Abstand %.0f\" – im kleinen Teleskop sauber getrennt, im Fernglas grenzwertig." .format(separation)
                )
                else -> add(
                    "Abstand %.0f\" – ein weites Paar, das schon das Fernglas trennt." .format(separation)
                )
            }
        }

        obj.spectralType?.takeIf { it.isNotBlank() }?.let { add(spectralHint(it)) }

        obj.morphology?.takeIf { it.isNotBlank() }?.let { add(morphologyHint(it)) }
    }

    /**
     * The spectral class, as the one thing it says that can be seen: colour.
     *
     * A star has no shape and no size to describe — the entire visible difference between one star
     * and the next is brightness and hue, and the hue is what the spectral letter encodes. Only
     * the leading letter is read; the digit and the luminosity class behind it refine a
     * temperature that is already binned more coarsely than any eye can judge.
     */
    private fun spectralHint(spectralType: String): String {
        val colour = when (spectralType.trim().firstOrNull()?.uppercaseChar()) {
            'O', 'B' -> "bläulich-weiß und sehr heiß"
            'A' -> "rein weiß"
            'F' -> "weißlich-gelb"
            'G' -> "gelb, wie unsere Sonne"
            'K' -> "orange und deutlich kühler als die Sonne"
            'M' -> "rötlich – die kühlsten Sterne, die noch hell genug für das bloße Auge sind"
            'C', 'S' -> "tiefrot: ein Kohlenstoffstern, einer der farbigsten Anblicke überhaupt"
            else -> null
        }
        return if (colour == null) {
            "Spektralklasse $spectralType."
        } else {
            "Spektralklasse $spectralType, also $colour."
        }
    }

    /**
     * The morphological code, unpacked far enough to be useful.
     *
     * `SA(s)b` is precise and unreadable; what a photographer wants from it is "spiral, seen from
     * roughly face on, arms rather than a bar". Only the leading letters are read — the brackets
     * and suffixes carry detail that does not change what the picture will look like.
     */
    private fun morphologyHint(morphology: String): String {
        val code = morphology.trim()
        val kind = when {
            code.startsWith("E") -> "elliptisch – ein glatter, strukturloser Lichtball ohne Arme"
            code.startsWith("S0") || code.startsWith("SA0") || code.startsWith("SB0") ->
                "linsenförmig: eine Scheibe mit Zentralwulst, aber ohne ausgeprägte Spiralarme"
            code.startsWith("SB") -> "Balkenspirale – die Arme setzen an einem Balken durchs Zentrum an"
            code.startsWith("S") -> "Spiralgalaxie mit deutlichen Armen"
            code.startsWith("I") -> "irregulär, ohne erkennbare Ordnung – oft die Folge einer Begegnung"
            else -> null
        }
        return if (kind == null) "Morphologie $code." else "Typ $code, also $kind."
    }

    /** The Moon is the one angular size everyone already has a feel for. */
    private const val MOON_DIAMETER_ARCMIN = 31.0
}
}
