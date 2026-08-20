# StarWindow – Entwicklungsleitfaden

Dieses Dokument ist für **den Einstieg in einer neuen Sitzung** gedacht: was das Projekt ist, welche
Konventionen gelten, was schon abgesichert ist und woran man weiterarbeitet. Die offene Aufgabenliste
steht in [TODO.md](TODO.md), die Benutzersicht in [README.md](README.md).

---

## 1. Worum es geht

Mit der Handykamera einen Himmelsausschnitt einzeichnen – ein „Fenster" zwischen Dächern oder Bäumen –
und exakt in Himmelskoordinaten festhalten. Daraus berechnet die App, welche Objekte im Lauf der Nacht
hindurchziehen und wie lange.

Der Kern ist **nicht** die Objektdatenbank, sondern die Kette **Bildschirmpixel → Himmelsrichtung**.
Jede Entscheidung im Projekt ordnet sich der Frage unter: bleibt diese Kette nachvollziehbar korrekt?

---

## 2. Konventionen – bitte nicht stillschweigend ändern

Vorzeichen- und Achsfehler sind in diesem Projekt die teuerste Fehlerklasse, weil sie plausibel
aussehende, aber falsche Ergebnisse liefern. Deshalb gilt überall:

| Größe | Konvention |
|---|---|
| **Azimut** | 0° = geografisch Nord, wachsend nach Osten (90° = O, 180° = S, 270° = W) |
| **Höhe** | 0° = Horizont, +90° = Zenit |
| **Stundenwinkel** | H = LST − RA, positiv nach dem Meridiandurchgang |
| **Weltsystem (ENU)** | x = Ost, y = Nord, z = oben |
| **Displaysystem** | x = Bildschirm rechts, y = Bildschirm oben, z = aus dem Bildschirm heraus |
| **Rückkamera** | blickt entlang **−z** im Displaysystem |
| **Rotationsmatrix** | zeilenweise, bildet Display- auf Weltsystem ab: `v_welt = R · v_display` |
| **Epoche** | J2000 für Katalogdaten, ohne Präzessionskorrektur (siehe TODO) |
| **Zeit** | UTC-Millisekunden; UT1 ≈ UTC wird bewusst gleichgesetzt |

Der Sensor liefert **magnetisch** Nord. Erst `DeviceAttitude.toTrueNorth()` macht daraus geografisch
Nord (Missweisung + Kalibrierkorrektur). Wer irgendwo anders Azimute vergleicht, muss wissen, in
welchem der beiden Systeme er gerade ist.

---

## 3. Aufbau

```
app/src/main/java/com/starwindow/app/
├── core/
│   ├── astro/        Zeit, Koordinaten, Transformationen, Vec3/Rotation3
│   ├── geometry/     Kugelgeometrie, Fensterformen
│   ├── camera/       Kameraoptik, Bildschirm ⇄ Himmel, Belichtungssteuerung
│   ├── calibration/  Ausgleichsrechnung für Lage und Bildfeld
│   └── sensors/      Lage- und Standortverfolgung
├── data/
│   ├── catalog/      Katalogquellen, Objekte, Sternbilder
│   ├── images/       Himmelsausschnitte über hips2fits
│   └── windows/      Persistenz (JSON) und Einstellungen
├── domain/           Durchgangsberechnung, Sternbilder, Laufbahnen
└── ui/               Compose-Oberfläche
```

**Regel:** `core/astro`, `core/geometry`, `core/calibration` und `domain` bleiben **frei von
Android-Abhängigkeiten**. Nur so laufen die Tests als normale JVM-Unit-Tests ohne Emulator – und nur
deshalb konnte die Mathematik überhaupt abgesichert werden, während der Android-Teil noch nicht
kompilierbar war. Diese Trennung ist der wertvollste Teil der Architektur; bitte erhalten.

`core/camera/CameraIntrinsics.kt` und `SkyProjection.kt` enthalten je eine kleine Android-Insel
(`fromCharacteristics`, Belichtungsfähigkeiten). Der Rest der Dateien ist reine Mathematik.

---

## 4. Die Rechenkette im Detail

Ein Fingertipp auf Pixel (x, y) wird so zu einer Himmelsrichtung:

1. **`SkyProjection.screenToSky`** – Lochkameramodell. Strahl `(x−cx, cy−y, −f)` im Displaysystem.
   `f` ist die Brennweite in *Ansichts*pixeln, berechnet in
   `SkyProjection.focalLengthInViewPixels()` aus Sensoroptik, Stromauflösung, Displaydrehung,
   Ansichtsgröße und Kalibrierfaktor.
2. **`DeviceAttitude.displayToWorld`** – Rotation ins magnetische Weltsystem.
3. **`DeviceAttitude.toTrueNorth`** – Missweisung (Drehung um den Zenit) und anschließend die
   Kalibrierkorrektur (allgemeine kleine Drehung im Weltsystem).
4. **`CoordinateTransforms.horizontalToEquatorial`** – über Breite, Länge und Ortssternzeit nach
   RA/Dec.

Rückwärts genauso (`skyToScreen`). **Wichtig:** Gesetzte Punkte werden als *Azimut/Höhe*
gespeichert und jedes Bild neu projiziert, nie als Bildschirmkoordinaten.

Daraus folgen zwei Dinge, die nicht verwechselt werden dürfen:

* **Gegenüber der Kamera** steht das Fenster still: schwenkt man, wandert es über den Bildschirm
  und aus dem Bild heraus. Driftet eine Markierung dabei schneller oder langsamer als das
  Kamerabild, ist das der sichtbare Beweis für einen Fehler im Bildfeld.
* **Gegenüber dem Sternhimmel** steht es ebenfalls still – es folgt den Sternen *nicht*. Der
  Himmel dreht sich mit gut 15° pro Stunde hindurch, und genau das wertet `TransitCalculator`
  aus: `membershipTest()` wird einmal vor der Zeitschleife gebaut, innerhalb der Schleife ändert
  sich ausschließlich die Position der Objekte über die Sternzeit.

Wer das Fenster jemals an RA/Dec heften will (etwa zum Einrahmen eines Ziels), muss das als
eigenen Modus bauen – die Durchgangsberechnung setzt die horizontfeste Bedeutung voraus.

### Wo `FIT_CENTER` herkommt

`PreviewView` läuft bewusst mit `FIT_CENTER`, nicht mit dem voreingestellten `FILL_CENTER`. Dessen
Beschnitt hängt vom Seitenverhältnis des Geräts ab und ginge unbemerkt direkt in den
Grad-pro-Pixel-Maßstab ein. Schwarze Balken sind der Preis für eine bekannte Geometrie. Wer das
ändert, muss `PreviewFit.FILL_CENTER` konsequent bis in `focalLengthInViewPixels()` durchreichen.

---

## 5. Stand der Absicherung

| Bereich | Status |
|---|---|
| `core/astro`, `core/geometry`, `core/calibration`, `domain`, Serialisierung | **kompiliert und getestet** |
| `core/camera` (ohne Android-Insel) | **kompiliert und getestet** |
| `core/sensors`, `data/*` mit Context, `core/camera` Belichtung, gesamte `ui/` | **nie von einem Compiler gesehen** |

Grund: In der Bauumgebung, in der das Projekt entstanden ist, war Google Maven nicht erreichbar, also
gab es kein Android Gradle Plugin. Die android-freien Teile wurden stattdessen in ein reines
JVM-Gradle-Projekt kopiert und dort tatsächlich gebaut und getestet.

**Erste Handlung in einer Sitzung mit funktionierendem Android-SDK:**

```bash
./gradlew :app:testDebugUnitTest   # muss grün sein
./gradlew :app:assembleDebug       # hier sind Korrekturen zu erwarten
```

Zwei echte Fehler wurden auf diesem Weg schon gefunden und behoben (fehlender
`kotlinx.serialization.encodeToString`-Import, in sich widersprüchliche Fallback-Bildfeldwerte) –
die Methode trägt also.

---

## 6. Arbeitsweise

* **Mathematik nur mit Test ändern.** Die Tests prüfen physikalische Invarianten, nicht
  Rückgabewerte: GMST zur Epoche J2000 gegen die IAU-Konstante, Zenit = Breitengrad, erhaltene
  Winkelabstände (die Transformation *ist* eine Drehung), Kulminationshöhen, Bildschirm ⇄ Himmel als
  exakte Umkehrung, Rückkehr eines zirkumpolaren Objekts nach einem siderischen Tag. Schlägt einer
  davon fehl, ist die Physik verletzt – nicht der Test zu lockern.
* **Kein Android in der Rechenschicht.** Sonst ist die Absicherung weg.
* **Neue Katalogquellen** hinter `CatalogSource` – die Naht ist da, `CatalogRepository` mischt.
* **Kommentare erklären das Warum**, nicht das Was. Vorzeichen- und Achskonventionen gehören
  ausgeschrieben; genau daran scheitert sonst die nächste Sitzung.
* **Sprache:** Oberfläche und Dokumentation Deutsch, Code und Bezeichner Englisch.

### Ohne Gerät entwickeln

Emulator hilft nicht (kein Kompass, keine Sternkamera). Was ohne Himmel geht:

* Unit-Tests für alles Rechnerische.
* Standort von Hand setzen statt GPS.
* Bildfeld über die **Schwenk-Kalibrierung** bestimmen – die braucht nur das Gyroskop und ein
  beliebiges Merkmal im Raum, keinen Stern.

---

## 7. Entscheidungen und ihre Gründe

Damit nicht rückgebaut wird, was aus einem Grund so ist:

| Entscheidung | Grund |
|---|---|
| Kein DI-Framework, nur `AppContainer` | bei dieser Größe kostet es mehr als es spart; eine Naht zum Austauschen |
| Keine Play Services, `LocationManager` genügt | einige hundert Meter Genauigkeit reichen (≪ 0,01° Himmel), hält den Build frei von Google |
| Keine Datenbank, eine JSON-Datei | ein paar Dutzend Fenster; dafür exportierbar und von Hand lesbar |
| Fenster horizontfest in Azimut/Höhe | eine Lücke zwischen zwei Häusern *ist* horizontfest; darauf beruht die Durchgangsberechnung |
| Durchgänge abtasten statt lösen | das Fenster darf ein beliebiges Polygon sein – dafür gibt es keine geschlossene Lösung |
| Gnomonische Projektion für Punkt-in-Polygon | Großkreise werden Geraden, der ebene Test wird damit exakt; Grenze ist eine Halbkugel |
| Lage als Quaternion geglättet | der rohe Sensor zittert um ein bis zwei Grad, was bei angehefteten Markierungen sofort auffällt |
| Kalibrierung optional und mehrgleisig | bei eingeschränkter Himmelssicht muss es auch ohne Sterne gehen |
| Kataloge lokal, online nur ergänzend | die App wird nachts im Feld benutzt; alle drei Kataloge zusammen sind 184 KB gepackt |
| Deep-Sky-Auswahl statt Vollständigkeit | 9.000 namenlose 15-mag-Galaxien machen jede Ergebnisliste unbrauchbar, ohne je ein Ziel zu sein |
| Bildlader von Hand statt Bibliothek | ein Bild zur Zeit, mit dem Blatt abgebrochen – das ist innerhalb dessen, was hundert Zeilen richtig können, und hält den Build abhängigkeitsfrei |
| Kein Gaia | falscher Katalogtyp für diese Aufgabe (siehe unten) |

---

## 8. Kalibrierung – Aufbau

Bewusst **zwei getrennte Größen**, die sich nicht gegenseitig verrechnen:

* **Lagekorrektur** (`Calibration.correction`): eine kleine Drehung im Weltsystem. Fängt vor allem
  den Kompassfehler ab. Wird gemessen, indem man das **Fadenkreuz** auf etwas Bekanntes richtet –
  in der Bildmitte spielt das Bildfeld keine Rolle, deshalb ist diese Messung davon unabhängig.
* **Bildfeldfaktor** (`Calibration.fovScale`): rein optisch, unabhängig von der Nordrichtung.

Vier Wege, jeder für sich benutzbar, keiner Pflicht:

| Verfahren | misst | braucht Himmel? |
|---|---|---|
| **Sternmuster** – nacheinander bekannte Sterne anpeilen | Lage (ab 2 Sternen auch Kippfehler) | ja |
| **Peilung** – bekannten Punkt anpeilen, wahre Peilung eintippen | nur Nordrichtung | **nein** |
| **Schwenk** – Merkmal antippen, schwenken, dasselbe Merkmal erneut antippen | Bildfeld | **nein** |
| **Manuell** – Regler | Bildfeld | **nein** |

Die Schwenk-Kalibrierung ist der Grund, warum das Ganze ohne Sterne funktioniert: sie wertet nur die
*relative* Drehung zwischen zwei Antippungen aus. Die liefert das Gyroskop zuverlässig, ganz ohne
Magnetfeld und ohne Nordrichtung.

---

## 9. Warum die Kataloge lokal liegen – und warum nicht Gaia

Gemessen, nicht geschätzt (Stand August 2026):

| Datensatz | Objekte | JSON | gepackt |
|---|---:|---:|---:|
| Basiskatalog heute | 115 | 20 KB | – |
| OpenNGC vollständig, auf die benötigten Felder reduziert | 13.970 | 1,5 MB | **228 KB** |
| OpenNGC bis 13 mag (Amateurteleskop-Grenze) | 3.074 | 361 KB | **58 KB** |
| Sterne bis 6,5 mag (bloßes Auge, Yale BSC) | 9.110 | 625 KB | 139 KB |
| Sterne bis ~8 mag (Fernglas) | 42.000 | 2,9 MB | 639 KB |

**Speicherplatz ist damit nicht der Engpass.** Alles, was diese App je zeigen will, liegt gepackt
unter einem Megabyte – ein Bruchteil einer gewöhnlichen APK. Ein Online-Katalog spart hier nichts,
kostet aber Verfügbarkeit genau dann, wenn man draußen im Dunkeln steht.

**Gaia ist der falsche Katalog**, unabhängig von der Größe:

* Es ist ein *astrometrischer Punktquellenkatalog*. Die 4,84 Mio. „galaxy candidates" in DR3 sind
  schwache, punktförmige Detektionen – nicht M31 oder der Orionnebel. Ausdehnung, Typ und gängige
  Namen fehlen, also genau das, was eine Durchgangsliste braucht.
* Nach oben ist Gaia bei G ≈ 3 gesättigt, und **20 % der Sterne heller als 3 mag haben gar keinen
  Eintrag**. Ausgerechnet die Sterne, auf die man das Handy richtet, fehlen – für die
  Sternkalibrierung wäre Gaia unbrauchbar.
* Die G-Helligkeit ist nicht die visuelle Helligkeit V; für „was sehe ich" ist V die relevante Größe.
* Vollständig sind es 1,81 Mrd. Quellen. Selbst bei 20 Byte je Quelle wären das rund 36 GB.

Sinnvoll wäre Gaia allenfalls für sehr genaue Positionen einzelner Objekte – eine Frage, die diese
App nicht stellt.

Der richtige Ausbau ist deshalb: **OpenNGC** (CC-BY-SA-4.0) und ein Sternkatalog lokal mitliefern,
und Online-Abfragen (VizieR/SIMBAD TAP) nur als Ergänzung für ungewöhnlich tiefe Suchen – auf das
Deklinationsband des Fensters beschränkt und mit Plattencache.

## 10. Durchgänge und Laufbahnen

`IntervalScanner` ist die gemeinsame Grundlage: Er tastet einen Zeitraum ab und schachtelt jeden
Wechsel per Bisektion ein. `TransitCalculator` (Einzelobjekte) und `ConstellationTransitCalculator`
(Figuren) setzen beide darauf auf – die Bisektion, der Teil den man leicht subtil falsch macht,
existiert genau einmal.

Sternbilder werden als **Figuren** geführt, nicht als IAU-Flächen: Ein Fenster von wenigen Grad
enthält nie eine ganze Fläche, wohl aber einen erkennbaren Teil der Figur. Ein Sternbild gilt als
durchziehend, solange mindestens ein Figurstern im Fenster steht; die Kennzahl daneben ist, wie
viele Figursterne gleichzeitig drin waren.

`SkyTrackTest` und `ConstellationTransitTest` sichern beides ab. Der Datentest für
`constellations.json` prüft unter anderem, dass kein Figurabschnitt unplausibel lang ist – das ist
die wirksame Kontrolle über 203 von Hand eingetragene Koordinaten, weil ein Zahlendreher einen
Stern weit wegwirft und der zugehörige Abschnitt dadurch absurd lang wird.

## 11. Woran als Nächstes

Siehe [TODO.md](TODO.md). Reihenfolge dort ist bewusst gewählt; Abschnitt 1 (kompilieren,
Feldabgleich) blockiert alles andere.
