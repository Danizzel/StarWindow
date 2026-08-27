#!/usr/bin/env python3
"""In welchem Sternbild steht eine Koordinate.

Nach Roman (1987), "Identification of a Constellation from Position", PASP 99, 695.
Die Grenzen sind Rechtecke in **B1875**-Koordinaten - das ist keine Marotte, sondern der
Grund, warum die Tabelle so kurz ist: Delporte hat die IAU-Grenzen 1930 bewusst entlang der
Stunden- und Deklinationskreise *jener* Epoche gezogen. In J2000 waeren dieselben Grenzen
schiefe Linien und die Zuordnung eine Polygonsuche.

Datei besorgen:

    curl -o constbnd.dat https://cdsarc.cds.unistra.fr/ftp/VI/42/data.dat

Quelle: VizieR VI/42, Nancy G. Roman, CDS.
"""

import math
from pathlib import Path

# B1875.0 als Julianisches Jahrhundert seit J2000.0 (JD 2405889.25858 bzw. 2451545.0).
T_B1875 = (2405889.25858 - 2451545.0) / 36525.0

ARCSEC = math.pi / (180.0 * 3600.0)


def _precession_matrix(t):
    """IAU-1976-Praezession von J2000 auf t (Jahrhunderte seit J2000), als Drehmatrix.

    Nur die drei Eulerwinkel zeta, z, theta; fuer eine Zuordnung, die Rechteckgrenzen von
    einer Zehntelbogenminute vergleicht, ist das um Groessenordnungen genau genug.
    """
    zeta = (2306.2181 * t + 0.30188 * t * t + 0.017998 * t ** 3) * ARCSEC
    z = (2306.2181 * t + 1.09468 * t * t + 0.018203 * t ** 3) * ARCSEC
    theta = (2004.3109 * t - 0.42665 * t * t - 0.041833 * t ** 3) * ARCSEC

    cz, sz = math.cos(zeta), math.sin(zeta)
    ct, st = math.cos(theta), math.sin(theta)
    cZ, sZ = math.cos(z), math.sin(z)

    return (
        (cz * ct * cZ - sz * sZ, -sz * ct * cZ - cz * sZ, -st * cZ),
        (cz * ct * sZ + sz * cZ, -sz * ct * sZ + cz * cZ, -st * sZ),
        (cz * st,                -sz * st,                 ct),
    )


_M_B1875 = _precession_matrix(T_B1875)


def to_b1875(ra_deg, dec_deg):
    """J2000 -> B1875, in Grad."""
    ra, dec = math.radians(ra_deg), math.radians(dec_deg)
    v = (math.cos(dec) * math.cos(ra), math.cos(dec) * math.sin(ra), math.sin(dec))
    x, y, z = (sum(row[i] * v[i] for i in range(3)) for row in _M_B1875)
    return math.degrees(math.atan2(y, x)) % 360.0, math.degrees(math.asin(max(-1.0, min(1.0, z))))


class ConstellationBoundaries:
    """Die Zuordnungstabelle, einmal gelesen."""

    def __init__(self, path):
        self.rows = []
        for line in Path(path).read_text(encoding="utf-8").splitlines():
            if not line.strip():
                continue
            ra_low, ra_high, dec_low, abbreviation = line.split()
            # Die Tabelle fuehrt RA in Stunden; hier wird durchgaengig in Grad gerechnet.
            self.rows.append((
                float(ra_low) * 15.0,
                float(ra_high) * 15.0,
                float(dec_low),
                abbreviation.strip(),
            ))

    def of(self, ra_deg, dec_deg):
        """Das Sternbild zu einer J2000-Position, oder None.

        Die Tabelle ist nach Deklination absteigend sortiert und die erste passende Zeile
        gewinnt - deshalb wird sie der Reihe nach durchgegangen und nicht durchsucht.
        """
        ra, dec = to_b1875(ra_deg, dec_deg)
        for ra_low, ra_high, dec_low, abbreviation in self.rows:
            if dec < dec_low:
                continue
            if ra_low <= ra < ra_high:
                return abbreviation
        return None
