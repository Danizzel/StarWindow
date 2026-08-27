#!/usr/bin/env python3
"""Baut die Sternbildfiguren aus den Linienzuegen von d3-celestial.

Aufruf (die beiden Dateien zuvor herunterladen, stars.json muss schon gebaut sein):

    curl -o constlines.json https://raw.githubusercontent.com/ofrohn/d3-celestial/master/data/constellations.lines.json
    curl -o const.json https://raw.githubusercontent.com/ofrohn/d3-celestial/master/data/constellations.json
    python3 scripts/import_constellations.py constlines.json const.json

Quelle: d3-celestial von Olaf Frohn, BSD-3-Clause. Die deutschen Sternbildnamen stehen dort
        bereits mit drin.

Die Figur, nicht die Flaeche: eine Fensterluecke von wenigen Grad enthaelt so gut wie nie
eine ganze IAU-Flaeche, aber sehr wohl den Guertel des Orion. Siehe die Klassendokumentation
von `data/catalog/Constellation.kt`.

Die Figursterne bekommen ihre Namen aus dem bereits gebauten Sternkatalog: ein Punkt der
Linienzuege ist immer ein Stern, und der steht mit Namen und Bayer-Bezeichnung schon in
`stars.json`. Sie ein zweites Mal von Hand einzutragen hiesse, zwei Quellen zu pflegen, die
auseinanderlaufen koennen.
"""

import json
import math
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
STARS = REPO / "app/src/main/assets/catalog/stars.json"
OUT = REPO / "app/src/main/assets/catalog/constellations.json"

# Zwei Punkte naeher als das sind derselbe Stern. Die Linienzuege sind auf vier Nachkomma-
# stellen gerundet und stammen aus einer anderen Quelle als der Sternkatalog, deshalb
# ueberhaupt eine Toleranz - aber eng genug, dass sie nie zwei Nachbarsterne zusammenzieht.
SAME_POINT_DEG = 0.05

# So weit darf ein Figurpunkt vom naechsten Katalogstern entfernt sein, um noch als dieser
# Stern zu gelten.
NAME_MATCH_DEG = 0.15


def load_stars():
    """Die hellen Sterne als (ra, dec, Bezeichnung), nach Helligkeit sortiert.

    Nur bis sechster Groesse: die Figuren bestehen aus Sternen, die man mit blossem Auge
    sieht, und ein zufaellig danebenstehender 8-mag-Stern waere nie gemeint.
    """
    objects = json.loads(STARS.read_text(encoding="utf-8"))["objects"]
    stars = []
    for obj in objects:
        if (obj.get("magnitude") or 99.0) > 6.0:
            continue
        label = obj["name"]
        if not label:
            # Ohne Eigennamen die Bayer-Bezeichnung, die auf jeder Sternkarte steht.
            greek = next((i for i in obj.get("catalogIds", []) if i[0] not in "0123456789HS"), None)
            label = greek or obj["id"]
        stars.append((obj["raDeg"], obj["decDeg"], label, obj.get("magnitude", 99.0)))
    stars.sort(key=lambda s: s[3])
    return stars


def separation(ra1, dec1, ra2, dec2):
    """Winkelabstand in Grad."""
    phi1, phi2 = math.radians(dec1), math.radians(dec2)
    delta = math.radians(ra1 - ra2)
    cosine = math.sin(phi1) * math.sin(phi2) + math.cos(phi1) * math.cos(phi2) * math.cos(delta)
    return math.degrees(math.acos(max(-1.0, min(1.0, cosine))))


def name_for(ra, dec, stars):
    """Der Katalogstern an dieser Stelle, oder ein leerer Name.

    Der hellste Treffer gewinnt, nicht der naechste: an einem Figurpunkt steht der Stern, den
    jemand mit blossem Auge sieht, und daneben womoeglich ein schwacher Begleiter, der zufaellig
    ein paar Bogenminuten dichter am gerundeten Punkt liegt.
    """
    best = None
    for star_ra, star_dec, label, magnitude in stars:
        # Grobfilter, damit nicht fuer jeden Punkt 5000 Winkelabstaende gerechnet werden.
        if abs(star_dec - dec) > NAME_MATCH_DEG:
            continue
        if separation(star_ra, star_dec, ra, dec) <= NAME_MATCH_DEG:
            if best is None or magnitude < best[1]:
                best = (label, magnitude)
    return best[0] if best else ""


def figure(feature, german, stars):
    """Ein Linienzug-Feature zu Sternen mit Indexpaaren.

    d3-celestial fuehrt die Zuege als GeoJSON: die Rektaszension steht als Laenge in
    -180..180 und muss zurueck in 0..360, sonst liegt der halbe Himmel im Negativen.
    """
    points, lines = [], []

    for stroke in feature["geometry"]["coordinates"]:
        previous = None
        for longitude, latitude in stroke:
            ra, dec = longitude % 360.0, latitude

            index = None
            for i, (existing_ra, existing_dec) in enumerate(points):
                if abs(existing_dec - dec) < SAME_POINT_DEG and \
                        separation(existing_ra, existing_dec, ra, dec) < SAME_POINT_DEG:
                    index = i
                    break
            if index is None:
                points.append((ra, dec))
                index = len(points) - 1

            if previous is not None and previous != index:
                lines.append([previous, index])
            previous = index

    identifier = feature["id"]
    return {
        "id": identifier,
        "name": german.get(identifier, identifier),
        "stars": [
            {"name": name_for(ra, dec, stars), "raDeg": round(ra, 4), "decDeg": round(dec, 4)}
            for ra, dec in points
        ],
        "lines": lines,
    }


def main(lines_path, names_path):
    stars = load_stars()
    german = {
        f["id"]: f["properties"].get("de") or f["properties"].get("name") or f["id"]
        for f in json.loads(Path(names_path).read_text(encoding="utf-8"))["features"]
    }
    features = json.loads(Path(lines_path).read_text(encoding="utf-8"))["features"]

    # Die Schlange steht als einziges Sternbild in zwei getrennten Stuecken am Himmel, Kopf und
    # Schwanz, und d3-celestial fuehrt sie deshalb als zwei Features unter derselben Kennung.
    # Zwei Eintraege "Ser" waeren aber ein doppelter Schluessel, und in jeder Ergebnisliste
    # stuende die Schlange zweimal. Sie werden zu einer Figur mit zwei Zuegen zusammengelegt.
    merged = {}
    for feature in features:
        built = figure(feature, german, stars)
        previous = merged.get(built["id"])
        if previous is None:
            merged[built["id"]] = built
            continue
        offset = len(previous["stars"])
        previous["stars"].extend(built["stars"])
        previous["lines"].extend([a + offset, b + offset] for a, b in built["lines"])

    constellations = [c for c in merged.values() if len(c["stars"]) >= 2]

    document = {
        "version": 1,
        "name": "Sternbildfiguren",
        "epoch": "J2000",
        "license": "Linienzuege: d3-celestial, Olaf Frohn, BSD-3-Clause",
        "constellations": constellations,
    }
    OUT.write_text(json.dumps(document, ensure_ascii=False, indent=1), encoding="utf-8")

    total = sum(len(c["stars"]) for c in constellations)
    unnamed = sum(1 for c in constellations for s in c["stars"] if not s["name"])
    print("%d Figuren mit %d Sternen (%d ohne Namen) -> %s (%.0f KB)"
          % (len(constellations), total, unnamed, OUT.relative_to(REPO),
             OUT.stat().st_size / 1024))


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(*sys.argv[1:])
