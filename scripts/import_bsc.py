#!/usr/bin/env python3
"""Baut den Sternkatalog aus dem Yale Bright Star Catalogue.

Aufruf (die drei Dateien zuvor herunterladen):

    curl -o bsc5.json https://raw.githubusercontent.com/brettonw/YaleBrightStarCatalog/master/bsc5-all.json
    curl -o IAU-CSN.txt https://www.pas.rochester.edu/~emamajek/WGSN/IAU-CSN.txt
    curl -o constbnd.dat https://cdsarc.cds.unistra.fr/ftp/VI/42/data.dat
    python3 scripts/import_bsc.py bsc5.json IAU-CSN.txt constbnd.dat

Quellen: Bright Star Catalogue, 5. Ausgabe (Hoffleit & Warren 1991), VizieR V/50, gemeinfrei;
         JSON-Fassung von brettonw/YaleBrightStarCatalog.
         IAU Catalog of Star Names der WGSN, CC-BY.
         Sternbildgrenzen VizieR VI/42 (Roman 1987).

Warum genau dieser Katalog: Er endet bei etwa 6,5 mag und damit dort, wo das blosse Auge
aufhoert - das ist keine willkuerliche Grenze, sondern deckt sich mit dem, was die App
leisten soll. Jeder Stern, den jemand am Himmel sieht und auf den er das Handy richtet, ist
darin; jeder Stern darunter waere nur Ballast in Liste und Sucher. Gaia waere groesser und
ausgerechnet oben, bei den hellen Sternen, lueckenhaft.
"""

import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from constellation_boundaries import ConstellationBoundaries

REPO = Path(__file__).resolve().parent.parent
GERMAN_NAMES = Path(__file__).resolve().parent / "german_star_names.json"
OUT = REPO / "app/src/main/assets/catalog/stars.json"

# Ab dieser Trennung ist ein Doppelstern ueberhaupt als Paar zu erkennen, darunter bleibt er
# in jedem Amateurgeraet ein Punkt. Nach oben begrenzt, weil zwei Sterne zehn Bogenminuten
# auseinander niemand mehr als Paar sieht - das sind dann einfach zwei Sterne.
DOUBLE_MIN_SEPARATION_ARCSEC = 1.0
DOUBLE_MAX_SEPARATION_ARCSEC = 300.0

# Und der Begleiter muss mitspielen. Sirius *hat* einen Begleiter in elf Bogensekunden, aber
# zehn Groessenklassen schwaecher - er ist beruehmt dafuer, wie schwer er zu sehen ist. Ihn
# als "Doppelstern" auszuweisen, waere fuer jeden, der danach sucht, eine Enttaeuschung.
DOUBLE_MAX_MAGNITUDE_DIFFERENCE = 3.5

# Griechische Buchstaben ausgeschrieben. Der BSC fuehrt sie als Zeichen; auf einer
# Handytastatur tippt sie so niemand, also muss "Alpha Orionis" genauso finden wie "α Ori".
GREEK_SPELLED = {
    "α": "Alpha", "β": "Beta", "γ": "Gamma", "δ": "Delta", "ε": "Epsilon",
    "ζ": "Zeta", "η": "Eta", "θ": "Theta", "ι": "Iota", "κ": "Kappa",
    "λ": "Lambda", "μ": "My", "ν": "Ny", "ξ": "Xi", "ο": "Omikron",
    "π": "Pi", "ρ": "Rho", "σ": "Sigma", "τ": "Tau", "υ": "Ypsilon",
    "φ": "Phi", "χ": "Chi", "ψ": "Psi", "ω": "Omega",
}

# Hochgestellte Indizes, wie der BSC sie schreibt, als normale Ziffern.
SUPERSCRIPT = {"¹": "1", "²": "2", "³": "3", "⁴": "4", "⁵": "5", "⁶": "6"}

# Genitiv der Sternbildnamen - "Alpha Orionis" ist die Form, unter der ein Stern in jedem
# Buch steht, und niemand sucht nach "Alpha Ori".
GENITIVE = {
    "And": "Andromedae", "Ant": "Antliae", "Aps": "Apodis", "Aqr": "Aquarii",
    "Aql": "Aquilae", "Ara": "Arae", "Ari": "Arietis", "Aur": "Aurigae",
    "Boo": "Bootis", "Cae": "Caeli", "Cam": "Camelopardalis", "Cnc": "Cancri",
    "CVn": "Canum Venaticorum", "CMa": "Canis Majoris", "CMi": "Canis Minoris",
    "Cap": "Capricorni", "Car": "Carinae", "Cas": "Cassiopeiae", "Cen": "Centauri",
    "Cep": "Cephei", "Cet": "Ceti", "Cha": "Chamaeleontis", "Cir": "Circini",
    "Col": "Columbae", "Com": "Comae Berenices", "CrA": "Coronae Australis",
    "CrB": "Coronae Borealis", "Crv": "Corvi", "Crt": "Crateris", "Cru": "Crucis",
    "Cyg": "Cygni", "Del": "Delphini", "Dor": "Doradus", "Dra": "Draconis",
    "Equ": "Equulei", "Eri": "Eridani", "For": "Fornacis", "Gem": "Geminorum",
    "Gru": "Gruis", "Her": "Herculis", "Hor": "Horologii", "Hya": "Hydrae",
    "Hyi": "Hydri", "Ind": "Indi", "Lac": "Lacertae", "Leo": "Leonis",
    "LMi": "Leonis Minoris", "Lep": "Leporis", "Lib": "Librae", "Lup": "Lupi",
    "Lyn": "Lyncis", "Lyr": "Lyrae", "Men": "Mensae", "Mic": "Microscopii",
    "Mon": "Monocerotis", "Mus": "Muscae", "Nor": "Normae", "Oct": "Octantis",
    "Oph": "Ophiuchi", "Ori": "Orionis", "Pav": "Pavonis", "Peg": "Pegasi",
    "Per": "Persei", "Phe": "Phoenicis", "Pic": "Pictoris", "Psc": "Piscium",
    "PsA": "Piscis Austrini", "Pup": "Puppis", "Pyx": "Pyxidis", "Ret": "Reticuli",
    "Sge": "Sagittae", "Sgr": "Sagittarii", "Sco": "Scorpii", "Scl": "Sculptoris",
    "Sct": "Scuti", "Ser": "Serpentis", "Sex": "Sextantis", "Tau": "Tauri",
    "Tel": "Telescopii", "Tri": "Trianguli", "TrA": "Trianguli Australis",
    "Tuc": "Tucanae", "UMa": "Ursae Majoris", "UMi": "Ursae Minoris",
    "Vel": "Velorum", "Vir": "Virginis", "Vol": "Volantis", "Vul": "Vulpeculae",
}


def number(entry, key):
    value = (entry.get(key) or "").strip()
    try:
        return float(value)
    except ValueError:
        return None


def right_ascension(entry):
    """RAh/RAm/RAs -> Grad. Die J2000-Spalten, nicht die mit 1900 im Namen."""
    return round((float(entry["RAh"]) + float(entry["RAm"]) / 60.0
                  + float(entry["RAs"]) / 3600.0) * 15.0, 4)


def declination(entry):
    sign = -1.0 if entry["DE-"].strip() == "-" else 1.0
    return round(sign * (float(entry["DEd"]) + float(entry["DEm"]) / 60.0
                         + float(entry["DEs"]) / 3600.0), 4)


def spell_greek(text):
    """`β¹ Cygni` -> `Beta1 Cygni`. Unveraendert, wenn kein griechischer Buchstabe drinsteht."""
    spelled = "".join(SUPERSCRIPT.get(c, c) for c in text)
    for symbol, word in GREEK_SPELLED.items():
        if spelled.startswith(symbol):
            return word + spelled[len(symbol):]
    return spelled


def bayer_designations(entry):
    """Die Bayer- und Flamsteed-Bezeichnung in allen Schreibweisen, unter denen gesucht wird.

    Ein Stern traegt bis zu vier davon: `α CMa`, `Alpha Canis Majoris`, `9 CMa` und
    `9 Canis Majoris`. Welche jemand eintippt, haengt nur daran, wo er sie gelesen hat, also
    muessen alle finden. Der BSC liefert die ausgeschriebenen Formen in `BayerF` und
    `FlamsteedF` bereits fertig - sie nachzubauen hiesse, den Genitiv selbst zu bilden.
    """
    constellation = (entry.get("Constellation") or "").strip()
    if not constellation:
        return [], []

    identifiers, names = [], []

    bayer = (entry.get("Bayer") or "").strip()
    if bayer:
        identifiers.append("%s %s" % (bayer, constellation))
        identifiers.append("%s %s" % (spell_greek(bayer), constellation))
    bayer_full = (entry.get("BayerF") or "").strip()
    if bayer_full:
        names.append(bayer_full)
        spelled = spell_greek(bayer_full)
        if spelled != bayer_full:
            names.append(spelled)

    flamsteed = (entry.get("Flamsteed") or "").strip()
    if flamsteed:
        identifiers.append("%s %s" % (flamsteed, constellation))
    flamsteed_full = (entry.get("FlamsteedF") or "").strip()
    if flamsteed_full:
        names.append(flamsteed_full)

    return identifiers, names


def iau_names(path):
    """Der IAU-Namenskatalog als HR-Nummer -> (Name, HIP-Nummer).

    Feste Spaltenbreiten statt Trennzeichen: die Datei hat Spalten, die leer sein duerfen,
    und ein `split()` wuerde bei jedem Platzhalter verrutschen.

    Nur der Name wird ueber seine Spalte gelesen - er steht ganz vorne und ist rein ASCII,
    also kann nichts davor ihn verschieben. Alles dahinter wird **von rechts abgezaehlt**:
    in Spalte 5 steht der Bayer-Buchstabe als griechisches Zeichen, das in UTF-8 zwei Bytes
    belegt und eine Spalte, und je nachdem, ob man die Zeile als Bytes oder als Zeichen
    schneidet, rutscht der ganze Rest um eine Stelle. Aus HIP 11767 wird dann lautlos 1767 -
    eine gueltige, falsche Nummer. Die letzten Felder dagegen haben festen Abstand vom
    Zeilenende: ... Vmag Band HIP HD RA Dec Datum.
    """
    names = {}
    for raw in Path(path).read_bytes().splitlines():
        if not raw.strip() or raw[:1] in (b"#", b"$"):
            continue
        line = raw.decode("utf-8", "replace")
        designation = line[36:49].strip()
        if not designation.startswith("HR "):
            continue

        fields = line.split()
        if fields and fields[-1] == "*":
            fields = fields[:-1]
        if len(fields) < 5:
            continue
        hip = fields[-5]

        names[designation[3:].strip()] = (
            raw[0:18].decode("ascii", "replace").strip(),
            hip if hip.isdigit() else None,
        )
    return names


def object_type(entry):
    """Doppelstern nur, wenn er auch als Paar zu sehen ist - weit genug getrennt und mit
    einem Begleiter, der nicht im Glanz des Hauptsterns verschwindet."""
    separation = number(entry, "Sep")
    difference = number(entry, "Dmag")
    if separation is None or difference is None:
        return "STAR", None
    if not DOUBLE_MIN_SEPARATION_ARCSEC <= separation <= DOUBLE_MAX_SEPARATION_ARCSEC:
        return "STAR", None
    if difference > DOUBLE_MAX_MAGNITUDE_DIFFERENCE:
        return "STAR", None
    return "DOUBLE_STAR", separation


def convert(entry, iau, german, boundaries):
    hr = entry["HR"].strip()
    ra, dec = right_ascension(entry), declination(entry)
    iau_name, hip = iau.get(hr, (None, None))
    common = (entry.get("Common") or "").strip()

    # Der deutsche Name gewinnt, danach der offizielle IAU-Name, danach der des BSC.
    english = iau_name or common
    name = german.get(english, english) if english else ""

    kind, separation = object_type(entry)
    identifiers, alternatives = bayer_designations(entry)

    obj = {
        "id": "HR %s" % hr,
        "name": name,
        "type": kind,
        "raDeg": ra,
        "decDeg": dec,
    }

    magnitude = number(entry, "Vmag")
    if magnitude is not None:
        obj["magnitude"] = round(magnitude, 2)
    if separation is not None:
        obj["separationArcsec"] = round(separation, 1)

    spectral = (entry.get("SpType") or "").strip()
    if spectral:
        obj["spectralType"] = spectral

    constellation = (entry.get("Constellation") or "").strip() or boundaries.of(ra, dec)
    if constellation:
        obj["constellation"] = constellation

    catalog_ids = list(identifiers)
    for key, prefix in (("HD", "HD "), ("SAO", "SAO ")):
        value = (entry.get(key) or "").strip()
        if value:
            catalog_ids.append(prefix + value)
    if hip:
        catalog_ids.append("HIP " + hip)
    # Der Veraenderlichenname, aber nur als Name: die Haelfte der Eintraege ist eine blanke
    # Nummer ohne Katalogpraefix, und `6603` neben Arktur zu stellen hilft keiner Suche.
    variable = (entry.get("VarID") or "").strip()
    if variable and not variable.isdigit() and variable != "Var?":
        catalog_ids.append(variable)
    catalog_ids = [i for i in dict.fromkeys(catalog_ids) if i != obj["id"]]
    if catalog_ids:
        obj["catalogIds"] = catalog_ids

    # Der englische Name bleibt suchbar, auch wenn im Katalog die deutsche Form steht.
    extra = [n for n in ([english] if english != name else []) + [common] + alternatives
             if n and n != name]
    extra = list(dict.fromkeys(extra))
    if extra:
        obj["alternativeNames"] = extra

    return obj


def main(bsc_path, iau_path, boundary_path):
    german = json.loads(GERMAN_NAMES.read_text(encoding="utf-8"))["names"]
    iau = iau_names(iau_path)
    boundaries = ConstellationBoundaries(boundary_path)
    entries = json.loads(Path(bsc_path).read_text(encoding="utf-8"))

    objects = []
    for entry in entries:
        # Der BSC fuehrt eine Handvoll Nummern, hinter denen kein Stern mehr steht
        # (Novae, aufgeloeste Doppeleintraege). Ohne Helligkeit ist ein Eintrag hier wertlos.
        if not entry.get("HR") or not entry.get("Vmag") or not entry.get("RAh"):
            continue
        objects.append(convert(entry, iau, german, boundaries))

    objects.sort(key=lambda o: o.get("magnitude", 99.0))
    document = {
        "version": 1,
        "name": "Sterne (Bright Star Catalogue)",
        "epoch": "J2000",
        "license": "Bright Star Catalogue 5th ed., Hoffleit & Warren, VizieR V/50; "
                   "Namen: IAU WGSN, CC-BY-4.0",
        "objects": objects,
    }
    OUT.write_text(json.dumps(document, ensure_ascii=False, separators=(",", ":")),
                   encoding="utf-8")

    named = sum(1 for o in objects if o["name"])
    doubles = sum(1 for o in objects if o["type"] == "DOUBLE_STAR")
    print("%d Sterne (%d mit Namen, %d Doppelsterne) -> %s (%.0f KB)"
          % (len(objects), named, doubles, OUT.relative_to(REPO), OUT.stat().st_size / 1024))


if __name__ == "__main__":
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    main(*sys.argv[1:])
