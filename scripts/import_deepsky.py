#!/usr/bin/env python3
"""Baut den Deep-Sky-Katalog aus OpenNGC und drei Nebelkatalogen.

Aufruf (die Dateien zuvor herunterladen):

    curl -O https://raw.githubusercontent.com/mattiaverga/OpenNGC/master/database_files/NGC.csv
    curl -O https://raw.githubusercontent.com/mattiaverga/OpenNGC/master/database_files/addendum.csv
    curl -o constbnd.dat https://cdsarc.cds.unistra.fr/ftp/VI/42/data.dat
    B=https://vizier.cds.unistra.fr/viz-bin/asu-tsv
    curl -o sharpless.tsv "$B?-source=VII/20/catalog&-out.max=unlimited&-out=Sh2,Diam,Form,Stars&-out.add=_RAJ2000,_DEJ2000"
    curl -o barnard.tsv   "$B?-source=VII/220A/barnard&-out.max=unlimited&-out=Barn,Diam&-out.add=_RAJ2000,_DEJ2000"
    curl -o lbn.tsv       "$B?-source=VII/9/catalog&-out.max=unlimited&-out=Seq,Diam1,Diam2,Bright,Name&-out.add=_RAJ2000,_DEJ2000"

    python3 scripts/import_deepsky.py NGC.csv addendum.csv constbnd.dat \
        sharpless.tsv barnard.tsv lbn.tsv

Quellen: OpenNGC von Mattia Verga, CC-BY-SA-4.0, https://github.com/mattiaverga/OpenNGC
         Sharpless (1959), VizieR VII/20 - die HII-Regionen, die in keinem NGC stehen.
         Barnard (1927), VizieR VII/220A - die Dunkelnebel.
         Lynds Bright Nebulae (1965), VizieR VII/9.
         Sternbildgrenzen VizieR VI/42 (Roman 1987).

Aufgenommen wird alles, was einen brauchbaren Typ und eine Position hat - fruehere Fassungen
haben bei 13 mag abgeschnitten, um die Ergebnislisten kurz zu halten. Das war die falsche
Stelle fuer diese Entscheidung: wer NGC 5387 sucht, soll es finden, und wie viel davon eine
Liste zeigt, gehoert in den Filter der Oberflaeche und nicht in den Katalog. Ausgeschlossen
bleiben nur Eintraege, hinter denen kein Objekt steht (`Dup`, `NonEx`) und Einzelsterne - die
kommen vollstaendiger aus dem Bright Star Catalogue, siehe `import_bsc.py`.
"""

import csv
import json
import math
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from constellation_boundaries import ConstellationBoundaries

REPO = Path(__file__).resolve().parent.parent
GERMAN_NAMES = Path(__file__).resolve().parent / "german_names.json"
OUT = REPO / "app/src/main/assets/catalog/deepsky.json"

# OpenNGC-Typkuerzel -> ObjectType der App.
TYPE_MAP = {
    "G": "GALAXY", "GPair": "GALAXY_GROUP", "GTrpl": "GALAXY_GROUP", "GGroup": "GALAXY_GROUP",
    "OCl": "OPEN_CLUSTER", "GCl": "GLOBULAR_CLUSTER", "Cl+N": "CLUSTER_NEBULA",
    "PN": "PLANETARY_NEBULA", "SNR": "SUPERNOVA_REMNANT",
    "HII": "EMISSION_NEBULA", "EmN": "EMISSION_NEBULA",
    "RfN": "REFLECTION_NEBULA", "DrkN": "DARK_NEBULA", "Neb": "NEBULA",
}

# So dicht beieinander sind zwei Eintraege dasselbe Objekt unter zwei Namen. Grosszuegig, weil
# ein Nebel keinen scharfen Mittelpunkt hat: Sharpless und der NGC koennen fuer dieselbe
# Wolke Positionen angeben, die eine Bogenminute auseinanderliegen, ohne dass einer irrt.
DUPLICATE_RADIUS_ARCMIN = 12.0


def number(row, key):
    value = (row.get(key) or "").strip()
    try:
        return float(value)
    except ValueError:
        return None


def magnitude(row):
    v = number(row, "V-Mag")
    return v if v is not None else number(row, "B-Mag")


def right_ascension(text):
    hours, minutes, seconds = text.split(":")
    return round((float(hours) + float(minutes) / 60 + float(seconds) / 3600) * 15.0, 4)


def declination(text):
    text = text.strip()
    sign = -1 if text.startswith("-") else 1
    degrees, minutes, seconds = text.lstrip("+-").split(":")
    return round(sign * (float(degrees) + float(minutes) / 60 + float(seconds) / 3600), 4)


def own_designation(row):
    """Die eigene Katalognummer, lesbar geschrieben: NGC0224 -> "NGC 224"."""
    name = row["Name"].strip()
    for prefix in ("NGC", "IC"):
        if name.startswith(prefix):
            suffix = name[len(prefix):].lstrip("0") or "0"
            return "%s %s" % (prefix, suffix)
    return name


def identifier(row):
    """Der Name, unter dem ein Objekt bekannt ist - Messier schlaegt NGC schlaegt IC."""
    messier = (row.get("M") or "").strip()
    if messier:
        return "M%d" % int(messier)
    return own_designation(row)


def catalog_ids(row, primary):
    # Die eigene Nummer zuerst: bei einem Messier-Objekt steht sie in keiner der
    # Querverweisspalten, sonst verloere M31 seine Bezeichnung NGC 224.
    ids = [own_designation(row)]
    for key, prefix in (("M", "M"), ("NGC", "NGC "), ("IC", "IC ")):
        value = (row.get(key) or "").strip()
        if value:
            ids.append("%s%s" % (prefix, value.lstrip("0") or "0"))
    for extra in (row.get("Identifiers") or "").split(","):
        extra = extra.strip()
        if extra:
            ids.append(extra)
    # Reihenfolge erhalten, Duplikate und den eigenen Namen entfernen.
    return list(dict.fromkeys(i for i in ids if i and i != primary))


def wanted(row):
    """Alles mit einem Typ, hinter dem ein Objekt steht.

    `Dup` sind Doppeleintraege, `NonEx` Fehleintraege des NGC, `*`/`**`/`*Ass` einzelne
    Sterne, Doppelsterne und Sternassoziationen - Sterne fuehrt `import_bsc.py`, und zwar
    vollstaendiger und mit Helligkeit.
    """
    return row["Type"].strip() in TYPE_MAP


def convert(row, german_names):
    primary = identifier(row)
    common = [n.strip() for n in (row.get("Common names") or "").split(",") if n.strip()]
    obj = {
        "id": primary,
        "name": german_names.get(primary) or (common[0] if common else ""),
        "type": TYPE_MAP[row["Type"].strip()],
        "raDeg": right_ascension(row["RA"]),
        "decDeg": declination(row["Dec"]),
    }
    for key, field in (("MajAx", "sizeArcmin"), ("MinAx", "minorAxisArcmin"),
                       ("PosAng", "positionAngleDeg"), ("SurfBr", "surfaceBrightness")):
        value = number(row, key)
        if value is not None:
            obj[field] = round(value, 2)
    mag = magnitude(row)
    if mag is not None:
        obj["magnitude"] = round(mag, 2)
    constellation = (row.get("Const") or "").strip()
    if constellation:
        # OpenNGC trennt die beiden Haelften der Schlange als `Se1`/`Se2`. Fuer die IAU und
        # fuer jeden anderen Katalog hier ist beides `Ser`, und zwei Schreibweisen fuer
        # dasselbe Sternbild wuerden jede Gruppierung danach auseinanderreissen.
        obj["constellation"] = "Ser" if constellation in ("Se1", "Se2") else constellation
    if (row.get("Hubble") or "").strip():
        obj["morphology"] = row["Hubble"].strip()
    ids = catalog_ids(row, primary)
    if ids:
        obj["catalogIds"] = ids
    # Weitere gaengige Namen anhaengen, damit die Suche sie findet.
    extra_names = [n for n in common if n != obj["name"]]
    if extra_names:
        obj["alternativeNames"] = extra_names
    return obj


def read_tsv(path):
    """Eine VizieR-Tabelle im TSV-Format als Liste von Zeilen.

    VizieR stellt der Tabelle Kommentarzeilen voran und schiebt zwischen Kopf und Daten noch
    eine Einheiten- und eine Trennzeile ein; beide sehen wie Daten aus und sind keine.
    """
    lines = [l for l in Path(path).read_text(encoding="utf-8").splitlines()
             if l.strip() and not l.startswith("#")]
    header = lines[0].split("\t")
    rows = []
    for line in lines[1:]:
        values = line.split("\t")
        if len(values) != len(header) or set(values[0].strip()) <= set("-"):
            continue
        row = dict(zip(header, (v.strip() for v in values)))
        if not row.get("_RAJ2000") or not row.get("_DEJ2000"):
            continue
        rows.append(row)
    return rows


class DuplicateIndex:
    """Findet zu einer Position das schon vorhandene Objekt.

    Ueber ein Deklinationsgitter, damit aus 12.000 Eintraegen mal 1.800 Nebeln kein
    Kreuzprodukt wird.
    """

    def __init__(self, objects):
        self.bands = {}
        for obj in objects:
            self.bands.setdefault(int(obj["decDeg"]), []).append(obj)

    def find(self, ra, dec, radius_arcmin=DUPLICATE_RADIUS_ARCMIN):
        radius = radius_arcmin / 60.0
        best, best_separation = None, radius
        for band in range(int(dec) - 1, int(dec) + 2):
            for obj in self.bands.get(band, ()):
                gap = separation(obj["raDeg"], obj["decDeg"], ra, dec)
                if gap <= best_separation:
                    best, best_separation = obj, gap
        return best

    def add(self, obj):
        self.bands.setdefault(int(obj["decDeg"]), []).append(obj)


def separation(ra1, dec1, ra2, dec2):
    """Winkelabstand in Grad."""
    phi1, phi2 = math.radians(dec1), math.radians(dec2)
    delta = math.radians(ra1 - ra2)
    cosine = math.sin(phi1) * math.sin(phi2) + math.cos(phi1) * math.cos(phi2) * math.cos(delta)
    return math.degrees(math.acos(max(-1.0, min(1.0, cosine))))


def merge_nebulae(rows, index, objects, designation, kind, size_of, boundaries, german_names):
    """Nebel eines Zusatzkatalogs einsortieren.

    Die meisten stehen laengst im NGC - Sh2-49 ist M16, B33 ist der Pferdekopfnebel. Fuer die
    wird nur die Bezeichnung nachgetragen, damit die Suche sie auch darunter findet; ein
    zweiter Eintrag waere dasselbe Objekt ein zweites Mal in jeder Ergebnisliste. Neu
    aufgenommen wird nur, was an dieser Stelle noch nichts hat.
    """
    added = aliased = 0
    for row in rows:
        name = designation(row)
        if not name:
            continue
        ra, dec = float(row["_RAJ2000"]), float(row["_DEJ2000"])

        existing = index.find(ra, dec)
        if existing is not None:
            ids = existing.setdefault("catalogIds", [])
            if name not in ids and name != existing["id"]:
                ids.append(name)
                aliased += 1
            continue

        obj = {
            "id": name,
            "name": german_names.get(name, ""),
            "type": kind,
            "raDeg": round(ra, 4),
            "decDeg": round(dec, 4),
        }
        size = size_of(row)
        if size[0] is not None:
            obj["sizeArcmin"] = round(size[0], 2)
            if len(size) > 1 and size[1] is not None:
                obj["minorAxisArcmin"] = round(size[1], 2)
        constellation = boundaries.of(ra, dec)
        if constellation:
            obj["constellation"] = constellation
        objects.append(obj)
        index.add(obj)
        added += 1
    return added, aliased


def optional(row, key):
    value = (row.get(key) or "").strip()
    try:
        return float(value)
    except ValueError:
        return None


def main(paths):
    ngc_paths = [p for p in paths if p.endswith(".csv")]
    boundary_path = next(p for p in paths if p.endswith(".dat"))
    tsv = {Path(p).stem: p for p in paths if p.endswith(".tsv")}

    # Die deutschen Namen liegen bewusst neben dem Skript und nicht im erzeugten Katalog:
    # sie sind gepflegte Zutat, keine Rohdaten, und muessen einen Neuimport ueberleben.
    german_names = json.loads(GERMAN_NAMES.read_text(encoding="utf-8"))["names"]
    boundaries = ConstellationBoundaries(boundary_path)

    objects = {}
    for path in ngc_paths:
        with open(path, encoding="utf-8") as handle:
            for row in csv.DictReader(handle, delimiter=";"):
                if not row.get("RA") or not row.get("Dec") or not wanted(row):
                    continue
                converted = convert(row, german_names)
                objects.setdefault(converted["id"], converted)

    ordered = list(objects.values())
    index = DuplicateIndex(ordered)
    report = []

    if "sharpless" in tsv:
        report.append(("Sh2", merge_nebulae(
            read_tsv(tsv["sharpless"]), index, ordered,
            lambda r: "Sh2-%s" % r["Sh2"] if r.get("Sh2") else None,
            "EMISSION_NEBULA",
            lambda r: (optional(r, "Diam"),),
            boundaries, german_names)))

    if "barnard" in tsv:
        report.append(("B", merge_nebulae(
            read_tsv(tsv["barnard"]), index, ordered,
            lambda r: "B %s" % r["Barn"] if r.get("Barn") else None,
            "DARK_NEBULA",
            lambda r: (optional(r, "Diam"),),
            boundaries, german_names)))

    if "lbn" in tsv:
        report.append(("LBN", merge_nebulae(
            read_tsv(tsv["lbn"]), index, ordered,
            lambda r: "LBN %s" % r["Seq"] if r.get("Seq") else None,
            "NEBULA",
            lambda r: (optional(r, "Diam1"), optional(r, "Diam2")),
            boundaries, german_names)))

    ordered.sort(key=lambda o: o.get("magnitude", 99.0))
    document = {
        "version": 1,
        "name": "Deep-Sky (OpenNGC, Sh2, Barnard, LBN)",
        "epoch": "J2000",
        "license": "OpenNGC, Mattia Verga, CC-BY-SA-4.0; Sharpless VII/20, Barnard VII/220A "
                   "und LBN VII/9 ueber VizieR, CDS Strasbourg",
        "objects": ordered,
    }
    OUT.write_text(json.dumps(document, ensure_ascii=False, separators=(",", ":")),
                   encoding="utf-8")

    for prefix, (added, aliased) in report:
        print("  %-4s %5d neu, %4d als weitere Bezeichnung eingetragen" % (prefix, added, aliased))
    print("%d Objekte -> %s (%.0f KB)"
          % (len(ordered), OUT.relative_to(REPO), OUT.stat().st_size / 1024))


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    main(sys.argv[1:])
