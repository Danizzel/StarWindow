package com.starwindow.app.core.astro

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Sonne und Mond.
 *
 * Bis hierher kam die App ohne Ephemeriden aus: Katalogobjekte stehen fest, nur der Himmel dreht
 * sich. Sonne und Mond wandern selbst, und für die Wetteransicht sind genau sie die beiden Größen,
 * die über eine Nacht entscheiden — die Sonne über die Dämmerung, der Mond über den
 * Himmelshintergrund. Deshalb stehen sie hier, in derselben Frame-Konvention wie alles andere:
 * Ergebnis ist eine [Equatorial] **zum Äquinoktium des Datums**, also fertig für
 * [CoordinateTransforms.equatorialToHorizontal] ohne Umweg über die Präzession.
 *
 * Genauigkeit: Sonne rund 0,01°, Mond rund 0,02° in der Länge (abgekürzte Reihen nach Meeus,
 * Kap. 25 und 47). Das ist besser als die Zeigegenauigkeit eines Handys und reicht für
 * Dämmerungszeiten auf wenige Sekunden.
 */

/** Sonnenstand zu einem Zeitpunkt. */
data class SunPosition(
    /** Scheinbare Position, Äquinoktium des Datums. */
    val equatorial: Equatorial,
    /** Scheinbare ekliptikale Länge in Grad. */
    val apparentLongitudeDeg: Double,
    /** Abstand Erde–Sonne in Kilometern. */
    val distanceKm: Double,
)

/** Mondstand zu einem Zeitpunkt, geozentrisch. */
data class MoonPosition(
    /** Geozentrische Position, Äquinoktium des Datums. */
    val equatorial: Equatorial,
    val eclipticLongitudeDeg: Double,
    val eclipticLatitudeDeg: Double,
    /** Abstand Erdmittelpunkt–Mondmittelpunkt in Kilometern. */
    val distanceKm: Double,
) {
    /**
     * Äquatoriale Horizontalparallaxe in Grad — knapp ein Grad, und damit der mit Abstand größte
     * Unterschied zwischen geozentrischer und beobachteter Position.
     */
    val horizontalParallaxDeg: Double
        get() = Math.toDegrees(asin((EARTH_RADIUS_KM / distanceKm).coerceIn(-1.0, 1.0)))

    companion object {
        const val EARTH_RADIUS_KM = 6378.14
    }
}

/** Beleuchtung und Phase des Mondes, aus Sonnen- und Mondstand zusammen. */
data class MoonIllumination(
    /** Beleuchteter Anteil der Mondscheibe, 0 = Neumond, 1 = Vollmond. */
    val fraction: Double,
    /** Phasenwinkel Sonne–Mond–Erde in Grad. */
    val phaseAngleDeg: Double,
    /** Elongation vom Sonnenmittelpunkt in Grad. */
    val elongationDeg: Double,
    /** True, solange der Mond zunimmt. */
    val waxing: Boolean,
) {
    val percent: Double get() = fraction * 100.0

    /** Phasenname in Klartext; die Grenzen folgen der üblichen Achtelteilung. */
    val phaseName: String
        get() = when {
            fraction < 0.02 -> "Neumond"
            fraction < 0.30 -> if (waxing) "zunehmende Sichel" else "abnehmende Sichel"
            fraction < 0.60 -> if (waxing) "erstes Viertel" else "letztes Viertel"
            fraction < 0.96 -> if (waxing) "zunehmender Mond" else "abnehmender Mond"
            else -> "Vollmond"
        }
}

object SolarEphemeris {

    /** Astronomische Einheit in Kilometern (IAU 2012). */
    const val AU_KM = 149_597_870.7

    /** Scheinbarer Sonnenstand nach Meeus, Kap. 25 (Reihe niedriger Ordnung). */
    fun at(epochMillis: Long): SunPosition {
        val t = AstroTime.julianCenturies(AstroTime.julianDate(epochMillis))

        // Geometrische mittlere Länge und mittlere Anomalie.
        val l0 = Angles.normalizeDeg(280.46646 + 36000.76983 * t + 0.0003032 * t * t)
        val m = Angles.normalizeDeg(357.52911 + 35999.05029 * t - 0.0001537 * t * t)
        val mRad = Math.toRadians(m)

        // Mittelpunktsgleichung: die Bahn ist eine Ellipse, keine Kreisbahn.
        val c = (1.914602 - 0.004817 * t - 0.000014 * t * t) * sin(mRad) +
            (0.019993 - 0.000101 * t) * sin(2 * mRad) +
            0.000289 * sin(3 * mRad)

        val trueLongitude = l0 + c
        val trueAnomaly = Math.toRadians(m + c)
        val e = 0.016708634 - 0.000042037 * t - 0.0000001267 * t * t
        val radiusAu = 1.000001018 * (1 - e * e) / (1 + e * cos(trueAnomaly))

        // Aberration und der größte Nutationsterm; beides zusammen knapp 0,006°.
        val omega = Math.toRadians(125.04 - 1934.136 * t)
        val apparentLongitude = Angles.normalizeDeg(trueLongitude - 0.00569 - 0.00478 * sin(omega))
        val obliquity = Obliquity.meanDeg(t) + 0.00256 * cos(omega)

        return SunPosition(
            equatorial = Obliquity.eclipticToEquatorial(apparentLongitude, 0.0, obliquity),
            apparentLongitudeDeg = apparentLongitude,
            distanceKm = radiusAu * AU_KM,
        )
    }

    /** Höhe der Sonne über dem Horizont, geometrisch (ohne Refraktion). */
    fun altitudeDeg(epochMillis: Long, location: ObserverLocation): Double =
        CoordinateTransforms
            .equatorialToHorizontal(at(epochMillis).equatorial, location, epochMillis)
            .altitudeDeg
}

object LunarEphemeris {

    /**
     * Geozentrischer Mondstand nach Meeus, Kap. 47, mit den 30 größten Gliedern jeder Reihe.
     *
     * Die vollständige Tabelle hat 60 Glieder für Länge und Abstand und 60 für die Breite;
     * abgeschnitten bleibt der Fehler unter etwa 0,02° in der Länge und 150 km im Abstand. Beides
     * liegt weit unter der Parallaxe, die ohnehin dazwischenliegt, und diese Reihe lässt sich noch
     * am Stück lesen — die vollständige nicht.
     */
    fun at(epochMillis: Long): MoonPosition {
        val t = AstroTime.julianCenturies(AstroTime.julianDate(epochMillis))
        val t2 = t * t
        val t3 = t2 * t
        val t4 = t3 * t

        // Mittlere Argumente: L' mittlere Länge, D Elongation, M Anomalie der Sonne,
        // M' Anomalie des Mondes, F Argument der Breite.
        val lPrime = Angles.normalizeDeg(
            218.3164477 + 481267.88123421 * t - 0.0015786 * t2 + t3 / 538841.0 - t4 / 65194000.0
        )
        val d = Angles.normalizeDeg(
            297.8501921 + 445267.1114034 * t - 0.0018819 * t2 + t3 / 545868.0 - t4 / 113065000.0
        )
        val m = Angles.normalizeDeg(
            357.5291092 + 35999.0502909 * t - 0.0001536 * t2 + t3 / 24490000.0
        )
        val mPrime = Angles.normalizeDeg(
            134.9633964 + 477198.8675055 * t + 0.0087414 * t2 + t3 / 69699.0 - t4 / 14712000.0
        )
        val f = Angles.normalizeDeg(
            93.2720950 + 483202.0175233 * t - 0.0036539 * t2 - t3 / 3526000.0 + t4 / 863310000.0
        )

        // Die Exzentrizität der Erdbahn nimmt langsam ab; Glieder mit der Sonnenanomalie im
        // Argument werden entsprechend skaliert.
        val e = 1.0 - 0.002516 * t - 0.0000074 * t2

        var sumL = 0.0
        var sumR = 0.0
        for (term in LONGITUDE_TERMS) {
            val argument = Math.toRadians(term.d * d + term.m * m + term.mPrime * mPrime + term.f * f)
            val scale = eccentricityFactor(term.m, e)
            sumL += term.sinCoefficient * sin(argument) * scale
            sumR += term.cosCoefficient * cos(argument) * scale
        }

        var sumB = 0.0
        for (term in LATITUDE_TERMS) {
            val argument = Math.toRadians(term.d * d + term.m * m + term.mPrime * mPrime + term.f * f)
            sumB += term.sinCoefficient * sin(argument) * eccentricityFactor(term.m, e)
        }

        // Zusatzglieder: Störungen durch Venus und Jupiter (A1, A2) und die Abplattung der Erde (A3).
        val a1 = Math.toRadians(Angles.normalizeDeg(119.75 + 131.849 * t))
        val a2 = Math.toRadians(Angles.normalizeDeg(53.09 + 479264.290 * t))
        val a3 = Math.toRadians(Angles.normalizeDeg(313.45 + 481266.484 * t))
        val lPrimeRad = Math.toRadians(lPrime)
        val fRad = Math.toRadians(f)
        val mPrimeRad = Math.toRadians(mPrime)

        sumL += 3958 * sin(a1) + 1962 * sin(lPrimeRad - fRad) + 318 * sin(a2)
        sumB += -2235 * sin(lPrimeRad) + 382 * sin(a3) +
            175 * sin(a1 - fRad) + 175 * sin(a1 + fRad) +
            127 * sin(lPrimeRad - mPrimeRad) - 115 * sin(lPrimeRad + mPrimeRad)

        val longitude = Angles.normalizeDeg(lPrime + sumL / 1_000_000.0)
        val latitude = sumB / 1_000_000.0
        val distanceKm = 385_000.56 + sumR / 1000.0

        return MoonPosition(
            equatorial = Obliquity.eclipticToEquatorial(longitude, latitude, Obliquity.meanDeg(t)),
            eclipticLongitudeDeg = longitude,
            eclipticLatitudeDeg = latitude,
            distanceKm = distanceKm,
        )
    }

    /**
     * Höhe des Mondes über dem Horizont, **topozentrisch**.
     *
     * Die Parallaxe ist hier keine Feinheit: Sie beträgt knapp ein Grad und drückt den Mond immer
     * nach unten, am Horizont am stärksten. Für die Frage „steht der Mond schon oder noch über dem
     * Horizont" ist das genau der Unterschied, auf den es ankommt.
     */
    fun topocentricAltitudeDeg(
        moon: MoonPosition,
        location: ObserverLocation,
        epochMillis: Long,
    ): Double {
        val geocentric = CoordinateTransforms
            .equatorialToHorizontal(moon.equatorial, location, epochMillis)
            .altitudeDeg
        return geocentric - moon.horizontalParallaxDeg * cos(Math.toRadians(geocentric))
    }

    /** Beleuchteter Anteil und Phase, aus Sonnen- und Mondstand zum selben Zeitpunkt. */
    fun illumination(sun: SunPosition, moon: MoonPosition): MoonIllumination {
        val sunRa = Math.toRadians(sun.equatorial.raDeg)
        val sunDec = Math.toRadians(sun.equatorial.decDeg)
        val moonRa = Math.toRadians(moon.equatorial.raDeg)
        val moonDec = Math.toRadians(moon.equatorial.decDeg)

        val cosElongation = sin(sunDec) * sin(moonDec) +
            cos(sunDec) * cos(moonDec) * cos(sunRa - moonRa)
        val elongation = acos(cosElongation.coerceIn(-1.0, 1.0))

        // Phasenwinkel aus dem Dreieck Sonne–Erde–Mond; der Nenner ist der Abstand des Mondes
        // abzüglich der auf die Sichtlinie projizierten Sonnenentfernung.
        val phaseAngle = atan2(
            sun.distanceKm * sin(elongation),
            moon.distanceKm - sun.distanceKm * cos(elongation),
        )
        val fraction = (1 + cos(phaseAngle)) / 2.0

        // Zunehmend heißt: der Mond steht in ekliptikaler Länge östlich der Sonne.
        val separation = Angles.normalizeDeg(moon.eclipticLongitudeDeg - sun.apparentLongitudeDeg)

        return MoonIllumination(
            fraction = fraction.coerceIn(0.0, 1.0),
            phaseAngleDeg = Math.toDegrees(phaseAngle),
            elongationDeg = Math.toDegrees(elongation),
            waxing = separation < 180.0,
        )
    }

    /** Kurzform, wenn nur der Zeitpunkt vorliegt. */
    fun illuminationAt(epochMillis: Long): MoonIllumination =
        illumination(SolarEphemeris.at(epochMillis), at(epochMillis))

    /** Glieder mit der Sonnenanomalie im Argument tragen den Faktor e hoch |m|. */
    private fun eccentricityFactor(m: Int, e: Double): Double = when (abs(m)) {
        0 -> 1.0
        1 -> e
        else -> e * e
    }

    /** Ein Glied der Reihe: Argument als Vielfache von D, M, M', F, dazu die Koeffizienten. */
    private class Term(
        val d: Int,
        val m: Int,
        val mPrime: Int,
        val f: Int,
        /** Koeffizient in 10⁻⁶ Grad, für Länge bzw. Breite. */
        val sinCoefficient: Double,
        /** Koeffizient für den Abstand; Meeus rechnet Σr in 10⁻³ km, also in Metern. */
        val cosCoefficient: Double = 0.0,
    )

    /** Meeus, Tabelle 47.A — Länge (Σl) und Abstand (Σr). */
    private val LONGITUDE_TERMS = listOf(
        Term(0, 0, 1, 0, 6288774.0, -20905355.0),
        Term(2, 0, -1, 0, 1274027.0, -3699111.0),
        Term(2, 0, 0, 0, 658314.0, -2955968.0),
        Term(0, 0, 2, 0, 213618.0, -569925.0),
        Term(0, 1, 0, 0, -185116.0, 48888.0),
        Term(0, 0, 0, 2, -114332.0, -3149.0),
        Term(2, 0, -2, 0, 58793.0, 246158.0),
        Term(2, -1, -1, 0, 57066.0, -152138.0),
        Term(2, 0, 1, 0, 53322.0, -170733.0),
        Term(2, -1, 0, 0, 45758.0, -204586.0),
        Term(0, 1, -1, 0, -40923.0, -129620.0),
        Term(1, 0, 0, 0, -34720.0, 108743.0),
        Term(0, 1, 1, 0, -30383.0, 104755.0),
        Term(2, 0, 0, -2, 15327.0, 10321.0),
        Term(0, 0, 1, 2, -12528.0, 0.0),
        Term(0, 0, 1, -2, 10980.0, 79661.0),
        Term(4, 0, -1, 0, 10675.0, -34782.0),
        Term(0, 0, 3, 0, 10034.0, -23210.0),
        Term(4, 0, -2, 0, 8548.0, -21636.0),
        Term(2, 1, -1, 0, -7888.0, 24208.0),
        Term(2, 1, 0, 0, -6766.0, 30824.0),
        Term(1, 0, -1, 0, -5163.0, -8379.0),
        Term(1, 1, 0, 0, 4987.0, -16675.0),
        Term(2, -1, 1, 0, 4036.0, -12831.0),
        Term(2, 0, 2, 0, 3994.0, -10445.0),
        Term(4, 0, 0, 0, 3861.0, -11650.0),
        Term(2, 0, -3, 0, 3665.0, 14403.0),
        Term(0, 1, -2, 0, -2689.0, -7003.0),
        Term(2, 0, -1, 2, -2602.0, 0.0),
        Term(2, -1, -2, 0, 2390.0, 10056.0),
    )

    /** Meeus, Tabelle 47.B — Breite (Σb). */
    private val LATITUDE_TERMS = listOf(
        Term(0, 0, 0, 1, 5128122.0),
        Term(0, 0, 1, 1, 280602.0),
        Term(0, 0, 1, -1, 277693.0),
        Term(2, 0, 0, -1, 173237.0),
        Term(2, 0, -1, 1, 55413.0),
        Term(2, 0, -1, -1, 46271.0),
        Term(2, 0, 0, 1, 32573.0),
        Term(0, 0, 2, 1, 17198.0),
        Term(2, 0, 1, -1, 9266.0),
        Term(0, 0, 2, -1, 8822.0),
        Term(2, -1, 0, -1, 8216.0),
        Term(2, 0, -2, -1, 4324.0),
        Term(2, 0, 1, 1, 4200.0),
        Term(2, 1, 0, -1, -3359.0),
        Term(2, -1, -1, 1, 2463.0),
        Term(2, -1, 0, 1, 2211.0),
        Term(2, -1, -1, -1, 2065.0),
        Term(0, 1, -1, -1, -1870.0),
        Term(4, 0, -1, -1, 1828.0),
        Term(0, 1, 0, 1, -1794.0),
        Term(0, 0, 0, 3, -1749.0),
        Term(0, 1, -1, 1, -1565.0),
        Term(1, 0, 0, 1, -1491.0),
        Term(0, 1, 1, 1, -1475.0),
        Term(0, 1, 1, -1, -1410.0),
        Term(0, 1, 0, -1, -1344.0),
        Term(1, 0, 0, -1, -1335.0),
        Term(0, 0, 3, 1, 1107.0),
        Term(4, 0, 0, -1, 1021.0),
        Term(4, 0, -1, 1, 833.0),
    )
}

/** Schiefe der Ekliptik und der Wechsel zwischen ekliptikalem und äquatorialem Frame. */
object Obliquity {

    /** Mittlere Schiefe der Ekliptik in Grad (IAU 1980); t in julianischen Jahrhunderten. */
    fun meanDeg(julianCenturies: Double): Double {
        val t = julianCenturies
        return 23.439291 - 0.0130042 * t - 1.64e-7 * t * t + 5.04e-7 * t * t * t
    }

    /** Ekliptikale Länge/Breite → Rektaszension/Deklination, alles in Grad. */
    fun eclipticToEquatorial(
        longitudeDeg: Double,
        latitudeDeg: Double,
        obliquityDeg: Double,
    ): Equatorial {
        val lambda = Math.toRadians(longitudeDeg)
        val beta = Math.toRadians(latitudeDeg)
        val epsilon = Math.toRadians(obliquityDeg)

        val sinDec = sin(beta) * cos(epsilon) + cos(beta) * sin(epsilon) * sin(lambda)
        val y = sin(lambda) * cos(epsilon) - tan(beta) * sin(epsilon)
        val x = cos(lambda)

        return Equatorial(
            raDeg = Angles.normalizeDeg(Math.toDegrees(atan2(y, x))),
            decDeg = Math.toDegrees(asin(sinDec.coerceIn(-1.0, 1.0))),
        )
    }
}
