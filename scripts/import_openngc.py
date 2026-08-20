#!/usr/bin/env python3
"""Baut den Deep-Sky-Katalog aus OpenNGC.

Aufruf (die beiden CSV zuvor herunterladen):

    curl -O https://raw.githubusercontent.com/mattiaverga/OpenNGC/master/database_files/NGC.csv
    curl -O https://raw.githubusercontent.com/mattiaverga/OpenNGC/master/database_files/addendum.csv
    python3 scripts/import_openngc.py NGC.csv addendum.csv

Quelle: OpenNGC von Mattia Verga, CC-BY-SA-4.0.
        https://github.com/mattiaverga/OpenNGC

Die Auswahl zielt bewusst auf das, was mit Amateurgerät fotografierbar ist, statt auf
Vollstaendigkeit: die restlichen rund 9.000 namenlosen 15-mag-Galaxien wuerden jede
Ergebnisliste unbrauchbar machen, ohne je ein Ziel zu sein.
"""

import csv
import json
import sys
from pathlib import Path

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

# Aufnahmekriterien (Vereinigung): Eigenname, Messier, hell genug, oder gross genug.
# Gross und lichtschwach ist fuer die Fotografie ausdruecklich interessant - Kalifornien-
# oder Rosettennebel stehen in keiner visuellen Liste, sind aber Standardziele.
MAG_LIMIT = 13.0
SIZE_LIMIT_ARCMIN = 5.0


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
            return f"{prefix} {suffix}"
    return name


def identifier(row):
    """Der Name, unter dem ein Objekt bekannt ist - Messier schlaegt NGC schlaegt IC."""
    messier = (row.get("M") or "").strip()
    if messier:
        return f"M{int(messier)}"
    return own_designation(row)


def catalog_ids(row, primary):
    # Die eigene Nummer zuerst: bei einem Messier-Objekt steht sie in keiner der
    # Querverweisspalten, sonst verloere M31 seine Bezeichnung NGC 224.
    ids = [own_designation(row)]
    for key, prefix in (("M", "M"), ("NGC", "NGC "), ("IC", "IC ")):
        value = (row.get(key) or "").strip()
        if value:
            ids.append(f"{prefix}{value.lstrip('0') or '0'}")
    for extra in (row.get("Identifiers") or "").split(","):
        extra = extra.strip()
        if extra:
            ids.append(extra)
    # Reihenfolge erhalten, Duplikate und den eigenen Namen entfernen.
    return list(dict.fromkeys(i for i in ids if i and i != primary))


def wanted(row):
    if row["Type"].strip() not in TYPE_MAP:
        return False
    if (row.get("Common names") or "").strip() or (row.get("M") or "").strip():
        return True
    mag = magnitude(row)
    if mag is not None and mag <= MAG_LIMIT:
        return True
    size = number(row, "MajAx")
    return size is not None and size >= SIZE_LIMIT_ARCMIN


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
    if (row.get("Const") or "").strip():
        obj["constellation"] = row["Const"].strip()
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


def main(paths):
    # Die deutschen Namen liegen bewusst neben dem Skript und nicht im erzeugten Katalog:
    # sie sind gepflegte Zutat, keine Rohdaten, und muessen einen Neuimport ueberleben.
    german_names = json.loads(GERMAN_NAMES.read_text(encoding="utf-8"))["names"]

    objects = {}
    for path in paths:
        with open(path, encoding="utf-8") as handle:
            for row in csv.DictReader(handle, delimiter=";"):
                if not row.get("RA") or not row.get("Dec"):
                    continue
                if not wanted(row):
                    continue
                converted = convert(row, german_names)
                objects.setdefault(converted["id"], converted)

    ordered = sorted(objects.values(), key=lambda o: o.get("magnitude", 99.0))
    document = {
        "version": 1,
        "name": "Deep-Sky (OpenNGC)",
        "epoch": "J2000",
        "license": "OpenNGC, Mattia Verga, CC-BY-SA-4.0",
        "objects": ordered,
    }
    OUT.write_text(
        json.dumps(document, ensure_ascii=False, separators=(",", ":")),
        encoding="utf-8",
    )
    print(f"{len(ordered)} Objekte -> {OUT.relative_to(REPO)} ({OUT.stat().st_size / 1024:.0f} KB)")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    main(sys.argv[1:])
