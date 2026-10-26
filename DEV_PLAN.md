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
| **Epoche** | Katalogdaten in J2000; was auf den echten Himmel zeigen soll, geht über `Precession` (IAU 2006) auf das Datum. Nutation, Aberration und Eigenbewegung bleiben bewusst weg (zusammen unter 0,01°), die Refraktion kommt getrennt dazu |
| **Zeit** | UTC-Millisekunden; UT1 ≈ UTC wird bewusst gleichgesetzt |

Der Sensor liefert **magnetisch** Nord. Erst `DeviceAttitude.toTrueNorth()` macht daraus geografisch
Nord (Missweisung + Kalibrierkorrektur). Wer irgendwo anders Azimute vergleicht, muss wissen, in
welchem der beiden Systeme er gerade ist.

---

## 3. Aufbau

```
app/src/main/java/com/starwindow/app/
├── core/
│   ├── astro/        Zeit, Koordinaten, Transformationen, Vec3/Rotation3, Präzession,
│   │                 Sonne und Mond, Dämmerung
│   ├── geometry/     Kugelgeometrie, Fensterformen
│   ├── camera/       Kameraoptik, Bildschirm ⇄ Himmel, Belichtungssteuerung
│   ├── calibration/  Ausgleichsrechnung für Lage und Bildfeld
│   └── sensors/      Lage- und Standortverfolgung, Sensorfusion
├── data/
│   ├── catalog/      Katalogquellen, Objekte, Sternbilder, Objekttexte
│   ├── favorites/    Favoriten (JSON)
│   ├── gear/         Smart-Teleskope für den Fotoguide
│   ├── images/       Himmelsausschnitte über hips2fits
│   ├── planning/     Geplante Nächte, Merkliste, Alarme und Benachrichtigungen (JSON)
│   ├── tracking/     Das verfolgte Ziel, über Neustarts hinweg
│   ├── weather/      Vorhersage (Open-Meteo), Ortssuche, Ablage auf der Platte
│   └── windows/      Fenster (JSON) und Einstellungen
├── domain/           Durchgänge, Laufbahnen, Suche und Filter, Nachtbewertung, Jahresplanung,
│                     Fotoguide
└── ui/               Compose-Oberfläche: ein Paket je Bereich, dazu components/, nav/, theme/
```

Daneben steht das Modul `:baselineprofile` mit Profil-Generator und Messungen (Abschnitt 11).

**Regel:** `core/astro`, `core/geometry`, `core/calibration` und `domain` bleiben **frei von
Android-Abhängigkeiten**. Nur so laufen die Tests als normale JVM-Unit-Tests ohne Emulator – und nur
deshalb konnte die Mathematik überhaupt abgesichert werden, während der Android-Teil noch nicht
kompilierbar war. Diese Trennung ist der wertvollste Teil der Architektur; bitte erhalten.

In `core/camera` enthalten `CameraIntrinsics.kt` und `ExposureSettings.kt` je eine kleine
Android-Insel (`fromCharacteristics`); `NightVisionController.kt` steuert die laufende Kamera und ist
ganz Android. Der Rest der Dateien ist reine Mathematik.

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

Stand 26. Oktober 2026:

| Bereich | Status |
|---|---|
| Rechenschicht: `core/astro`, `core/geometry`, `core/calibration`, `domain`, die Mathematik in `core/camera` und `core/sensors` (`SkyProjection`, `AttitudeFusion`), Serialisierung | **kompiliert, JVM-Unit-Tests** – 462, alle grün |
| Android-Teil: Sensor- und Standortanbindung, `data/*` mit Context, Kamerasteuerung, gesamte `ui/` | **kompiliert** (Debug und Release mit R8), keine automatischen Tests |
| App als Ganzes auf dem Gerät | Profil-Generator und Messungen in `:baselineprofile` vorhanden, noch nicht gelaufen (11.1); Gegenprüfung am Himmel offen (TODO.md, Abschnitt 1) |

**Erste Handlung in einer neuen Sitzung:**

```bash
./gradlew :app:testDebugUnitTest   # muss grün sein
./gradlew :app:assembleDebug       # muss bauen
```

Vor einer Veröffentlichung zusätzlich `./gradlew :app:assembleRelease`: Erst dort läuft R8, und erst
dort fallen fehlende Keep-Regeln auf.

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
| Kataloge lokal, online nur ergänzend | die App wird nachts im Feld benutzt; alle drei Kataloge zusammen sind rund 1,1 MB gepackt |
| Deep-Sky-Katalog vollständig, ausgewählt wird erst in der Oberfläche | wer NGC 5387 sucht, soll es finden; was Listen und Sucher zeigen, entscheiden Filter, Sortierung und `OverlaySelection` – nicht der Import |
| Bildlader von Hand statt Bibliothek | ein Bild je sichtbarer Karte, ein kleiner Speicher- und ein Plattencache – rund 200 Zeilen, und der Build bleibt frei von einer Bildbibliothek; Grenzen siehe 11.3, P6 |
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

| Datensatz | Objekte | JSON | Parsen |
|---|---:|---:|---:|
| `deepsky.json` – OpenNGC vollständig plus Sh2, Barnard, LBN | 13.432 | 3,8 MB | 38 ms |
| `stars.json` – Yale BSC, bis rund 6,5 mag | 9.096 | 1,8 MB | 52 ms |
| `constellations.json` – alle 88 Figuren | 88 | 98 KB | – |
| Sterne bis ~8 mag (Fernglas), nicht mitgeliefert | 42.000 | 2,9 MB | – |

Die Zeiten sind auf dem Entwicklungsrechner gemessen; auf einem Telefon ist mit dem Drei- bis
Fünffachen zu rechnen, also gut einer Drittelsekunde für beide Kataloge zusammen – einmalig, in
einer Coroutine, danach im Speicher gehalten.

**Speicherplatz ist damit nicht der Engpass**, und die Rechenzeit auch nicht. Ein Online-Katalog
spart hier nichts, kostet aber Verfügbarkeit genau dann, wenn man draußen im Dunkeln steht.

Der Engpass sitzt woanders: der Sucher projiziert **pro Bild** jedes Objekt, das er zeichnen soll,
von Himmels- in Bildschirmkoordinaten. Zweiundzwanzigtausend davon fünfzigmal je Sekunde geht
nicht – und wäre auch umsonst, weil zehntausend Markierungen auf einem Telefondisplay keine
Sternkarte ergeben, sondern eine graue Fläche. Deshalb entscheidet `domain/OverlaySelection.kt`,
was überhaupt gezeichnet wird: was von dieser Breite je aufgeht, was als Fotoziel taugt oder als
Stern hell genug zur Orientierung ist (bis 4,2 mag), und davon höchstens 400, darunter bis zu 150
Sterne. Der Katalog darf vollständig sein, weil die Auswahl es nicht ist.

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

So ist es umgesetzt: **OpenNGC** (CC-BY-SA-4.0) und der Bright Star Catalogue liegen lokal bei.
Online-Abfragen (VizieR/SIMBAD TAP) wären nur eine Ergänzung für ungewöhnlich tiefe Suchen – auf das
Deklinationsband des Fensters beschränkt und mit Plattencache. Die Naht dafür ist
`RemoteCatalogSource`; angebunden ist sie noch nicht.

## 10. Durchgänge und Laufbahnen

`IntervalScanner` ist die gemeinsame Grundlage: Er tastet einen Zeitraum ab und schachtelt jeden
Wechsel per Bisektion ein. `TransitCalculator` (Einzelobjekte) und `ConstellationTransitCalculator`
(Figuren) setzen beide darauf auf – die Bisektion, der Teil den man leicht subtil falsch macht,
existiert genau einmal.

Sternbilder werden als **Figuren** geführt, nicht als IAU-Flächen: Ein Fenster von wenigen Grad
enthält nie eine ganze Fläche, wohl aber einen erkennbaren Teil der Figur. Ein Sternbild gilt als
durchziehend, solange mindestens ein Figurstern im Fenster steht; die Kennzahl daneben ist, wie
viele Figursterne gleichzeitig drin waren.

`ConstellationAndTrackTest` sichert beides ab. Der Datentest für `constellations.json`
(`ConstellationDataTest` in `SerializationTest.kt`) prüft unter anderem, dass kein Figurabschnitt
unplausibel lang ist – das ist die wirksame Kontrolle über 767 aus d3-celestial importierte
Koordinaten, weil eine falsche Stunde oder ein gekipptes Vorzeichen einen Stern weit wegwirft und
der zugehörige Abschnitt dadurch absurd lang wird.

## 11. Performance und Projektstruktur

Bestandsaufnahme vom 26. Oktober 2026, mit zwei Zielen: Die App soll sich flüssiger anfühlen, und die
Codebasis soll für Menschen leichter zu verstehen sein. 11.1 ist umgesetzt, alles danach ist offen und
nach erwartetem Nutzen geordnet. Jeder Performance-Punkt nennt den Benchmark, an dem er sich messen
lassen muss – eine Änderung, die dort nichts bewegt, war keine.

### 11.1 Umgesetzt: Release-Build mit R8 und Messgrundlage

* `release` läuft mit R8 und Resource-Shrinking (`app/build.gradle.kts`). Für Compose ist R8 ein
  Laufzeit- und kein Größenthema; die APK schrumpft nebenbei von 14,6 MB (Release ohne R8) auf 3,5 MB.
  **Flüssigkeit nur am Release- oder Benchmark-Build beurteilen**, nie am Debug-Build aus Android
  Studio.
* `profileinstaller` und das Baseline-Profile-Plugin sind eingebunden. Das Modul `:baselineprofile`
  enthält:
  * `BaselineProfileGenerator` – `startup` (Kaltstart bis zum laufenden Sucher, zugleich Startprofil
    für die DEX-Anordnung) und `journeys` (Suche → Objektblatt, jeder Reiter mit einmal Scrollen),
  * `StartupBenchmarks` – Kaltstart ohne und mit Profil,
  * `FrameBenchmarks` – `viewfinder` (fünf Sekunden ruhiger Sucher), `switchTabs`, `scrollCalendar`.

  Die Wege stehen gemeinsam in `Journeys.kt` und finden die Bedienelemente über ihre sichtbaren
  Beschriftungen. Wer einen Reiter umbenennt, muss ihn dort nachziehen.
* `app/build.gradle.kts` enthält einen kommentierten Workaround für einen Fehler des Plugins unter
  Windows: Es kopiert Kotlin-Quellordner als Text, und mit AGP 9 wird daraus der Pfad `provider(?)`.
  Kann weg, sobald das Plugin das selbst richtig macht.

Geprüft ohne Gerät: Release-Build und beide Benchmark-Varianten bauen, R8 meldet keine fehlenden
Regeln, die Serializer aller `@Serializable`-Klassen bleiben erhalten, alle 462 Unit-Tests sind grün.

**Noch offen, weil dafür ein Gerät nötig ist:**

- [ ] Profil erzeugen und einchecken (landet in `app/src/release/generated/baselineProfiles/`).
- [ ] Den R8-Build einmal von Hand durchgehen: Fenster speichern und wieder laden, Kalender,
      Merkliste, Wetter, Verfolgen. Die Benchmarks laufen auf der minifizierten Variante und decken
      einen Teil davon ab, das Laden gespeicherter Daten aber nicht.
- [ ] Ausgangswerte in die Tabelle unter 11.2 eintragen.

### 11.2 Messen

```bash
# Telefon per USB, entsperrt; ohne Root ab Android 13
./gradlew :app:generateBaselineProfile                            # Profil erzeugen, nach src/ kopieren
./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest   # alle Messungen
./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.starwindow.baselineprofile.FrameBenchmarks
```

Android Studio zeigt die Ergebnisse samt Perfetto-Traces im Testfenster. Für `viewfinder` das Telefon
still hinlegen, sonst misst man die Hand.

| Messung | Kennzahl | Ausgangswert | Gerät |
|---|---|---|---|
| `StartupBenchmarks.startupWithoutProfile` | `timeToInitialDisplayMs`, Median | – | – |
| `StartupBenchmarks.startupWithBaselineProfile` | `timeToInitialDisplayMs`, Median | – | – |
| `FrameBenchmarks.viewfinder` | `frameDurationCpuMs` P50/P90, `frameOverrunMs` P90 | – | – |
| `FrameBenchmarks.switchTabs` | wie oben | – | – |
| `FrameBenchmarks.scrollCalendar` | wie oben | – | – |

### 11.3 Offen: Performance

**P1 · Sucher: Sensoren auf dem UI-Thread und mit 50 Hz** – misst `FrameBenchmarks.viewfinder`

`OrientationTracker.attitudes()` registriert seine Listener ohne `Handler`; die ganze Fusion (Slerp,
Matrizen, Allokationen) läuft damit für jedes Ereignis auf dem UI-Thread. `SENSOR_DELAY_GAME` liefert
nominell 50 Hz: Auf 60 Hz wiederholt sich jedes sechste Bild, auf 120 Hz bewegt sich das Overlay nur
jedes zweite bis dritte. Beim Schwenken ist das als Mikroruckeln zu sehen.
→ Listener auf einen eigenen `HandlerThread` (die `AtomicReference`s für Ort und Kalibrierung sind
dafür schon da) und die Abtastperiode aus der Bildrate des Displays ableiten
(`1_000_000 / refreshRate` µs). Die Glättung rechnet mit einer Zeitkonstante und verhält sich dadurch
unverändert. Optional die Lage zusätzlich auf die Bildzeit interpolieren.

**P2 · Sucher: Astronomie in jedem Bild** – misst `FrameBenchmarks.viewfinder`

`SkyOverlay.drawCatalog` rechnet pro Bild für bis zu 400 Objekte Präzession, Horizontkoordinaten,
Refraktion und Projektion – grob 25 Winkelfunktionen und 10 Allokationen je Objekt, obwohl sich die
Positionen nur um 15″ pro Sekunde ändern. Ebenfalls pro Bild: `shape.outline(96)` in
`drawWindowShape` (`WindowTrackChart` hält die Kontur bereits vor), die Gitterlinien über `buildList`
und `String.format` am Fadenkreuz.
→ Einmal pro Sekunde Einheitsvektoren in ein `FloatArray` rechnen; der Ticker dafür existiert in
`CaptureViewModel.trackedTarget`. Pro Bild bleibt eine 3×3-Matrix mal Vektor, ohne Trigonometrie und
ohne Allokation. Kontur und Gitter ebenso zwischenspeichern.

**P3 · Reiterwechsel: 700 ms Überblendung** – misst `FrameBenchmarks.switchTabs`

Der `NavHost` in `StarWindowNavHost` legt keine Übergänge fest. Damit gilt der Standard
`fadeIn/fadeOut(tween(700))`, und zwei Bildschirme werden 0,7 s lang gleichzeitig gezeichnet.
→ Kurze Übergänge: Reiter etwa 200 ms Fade-Through, Detailseiten als Slide. Mit targetSdk 36 ist
Predictive Back auf Android 16 standardmäßig aktiv; die Rückwärtsübergänge sollten dazu passen.

**P4 · Katalog laden und durchsuchen** – misst `StartupBenchmarks`, die Suche per System-Trace

`AssetCatalogSource.load` liest 5,8 MB JSON mit `readText()` komplett in einen String, danach folgen
22.528 `copy()`-Aufrufe, parallel zum Kamerastart. `ObjectSearch.search` faltet bei jeder Eingabe alle
Kennungen und Namen aller Objekte neu. Einzelne Objekte werden an mehreren Stellen per
`objects().firstOrNull { it.id == … }` gesucht.
→ `decodeFromStream` oder ein kompaktes Format aus `scripts/import_*.py`. Für den Sucher zuerst eine
kleine Datei mit den Overlay-Kandidaten laden: `OverlaySelection` schränkt nach Breite und
Grenzgröße nur weiter ein, die Obermenge lässt sich also vorab bilden. Den Suchindex beim Laden
einmal bauen, im `CatalogRepository` eine Map nach Kennung führen.

**P5 · Listen und Zustandswechsel** – misst `FrameBenchmarks.scrollCalendar`

Ohne `key` und `contentType` sind die Abschnitte in `CalendarScreen` (Monate, Als Nächstes, Merkliste,
Vorbei), die besten Nächte in `PlanningScreen` und die Auswahlzeile in `CatalogFilterSheet`. Eigene
Animationen gibt es genau eine (`AnimatedVisibility` im Wetter).
→ Keys und `contentType` überall; `Modifier.animateItem()` für Hinzufügen und Löschen;
`AnimatedContent` für den Wechsel von „lädt" zu Inhalt.

**P6 · Kleinere Punkte**

| Stelle | Befund | Vorschlag |
|---|---|---|
| `AstroWeatherChart` | Jede Fingerbewegung zeichnet Pfade und Achsenbeschriftung neu. | Statischen Teil per `drawWithCache`, Cursor als eigene Ebene. |
| `NightVisionDialog` | Jeder Slider-Tick läuft durch `SettingsStore.update`: alle Einstellungen als JSON in die SharedPreferences. | Live nur an die Kamera, speichern in `onValueChangeFinished`. |
| `SkyImageLoader` | Cache nach Anzahl: 32 Bilder bis 768², also 20–40 MB statt der „paar hundert Kilobyte" aus dem Kommentar. Abgebrochene Downloads laufen weiter, Bilder erscheinen ohne Überblendung. | Cache nach Bytes und Crossfade – oder Coil, dann die Entscheidung in Abschnitt 7 anpassen. |
| `CameraPreview` | `COMPATIBLE` heißt TextureView: ein Bild mehr Latenz zwischen Kamera und Overlay und zusätzliche GPU-Arbeit. | `PERFORMANCE` (SurfaceView) testen; sie liegt ebenfalls unter dem Overlay. Übergänge aus P3 am Sucher prüfen. |
| `AppContainer` | Erzeugt alles in `Application.onCreate`, auch für jeden Receiver; `SettingsStore` und `TrackingStore` lesen dabei synchron auf dem UI-Thread. | `by lazy`; StrictMode im Debug-Build. |
| Standort | `CaptureViewModel` sammelt `locations()` dauerhaft im `viewModelScope`. Der Sucher ist die Wurzel des Stapels, der Empfang läuft also auch auf allen anderen Reitern; die Suche meldet zusätzlich einen eigenen Listener an. | Ein geteilter Fluss im Repository (`shareIn` mit `WhileSubscribed`), an den Lebenszyklus gebunden. |
| UiState | An zehn Stellen geht der ganze `…UiState` an Unter-Composables; `CaptureScreen` liest `hudAttitude` ganz oben, die ganze Funktion läuft dadurch etwa viermal pro Sekunde neu. | Nur die benötigten Werte übergeben, Lesezugriffe nach unten verlagern. |
| Abhängigkeiten | Stand Ende 2024: Compose-BOM 2024.12.01, Lifecycle 2.8, Navigation 2.8, CameraX 1.4. | Aktualisieren; CameraX 1.5 bringt mit `camera-compose` einen nativen Compose-Sucher. |

### 11.4 Offen: Struktur

**S1 · Gemeinsam Genutztes liegt in fremden Feature-Paketen**

* `ui/windows/TimeFormatting.kt` (genutzt von Suche, Planung, Wetter) und
  `ui/windows/ObjectInfoSheet.kt` (genutzt von der Suche) → `ui/components`.
* `ui/capture/CameraPreview` und `SkyOverlay` braucht auch die Kalibrierung → eigenes Paket, etwa
  `ui/viewfinder`.
* `ui/planning/PlanningLocation` ist keine Oberfläche und wird von Hub und Guide benutzt → `domain`.
* `ObjectDetail*` liegt in `ui/search`, ist aber von fünf Stellen aus erreichbar → eigenes Paket.
* `SettingsStore` liegt in `data/windows`, hält aber alle Einstellungen → `data/settings`.

**S2 · `data/planning` mischt Modelle, Ablage und Android-Anbindung**

Dreizehn Dateien: Modelle, zwei Repositories, drei Receiver, zwei Scheduler, zwei Benachrichtigungen
und mit `PlannedPath` ein Anwendungsfall. → Receiver, Scheduler und Benachrichtigungen in ein eigenes
Paket, `BootReceiver` in eine eigene Datei (bisher in `ReminderReceiver.kt`). Dabei mitnehmen:
`WatchCheckReceiver` legt sich ein eigenes `WatchlistRepository` an – zwei Instanzen mit getrennten
Mutexes auf derselben Datei.

**S3 · Abhängigkeitsrichtung**

`domain` importiert Modelle aus `data`, und `data/planning` und `data/weather` importieren umgekehrt
`domain` (`NightOutlook`, `WatchEvaluator`, `ObservationPlanner`) – ein Zyklus. `domain/` ist zudem
flach mit 22 Dateien. → Modelle an einen neutralen Ort, `domain` in Unterpakete (`transit`, `search`,
`planning`, `weather`, `photo`). Langfristig die Regel aus Abschnitt 3 als reine Kotlin/JVM-Module
(`:core`, `:domain`) vom Compiler prüfen lassen statt per Konvention.

**S4 · Navigation**

→ Typsichere `@Serializable`-Routen statt Zeichenketten mit `Uri.encode` und `navArgument`;
Navigation 2.8 und kotlinx.serialization sind schon eingebunden. Logik aus dem `NavHost` in die
ViewModels holen: `PlannedPath.track(container, …)` über `rememberCoroutineScope` und das Sammeln der
Einstellungen für die Kalibrierung.

**S5 · Fünfmal dieselbe Dateiablage**

`SkyWindowRepository`, `FavoritesRepository`, `PlanRepository`, `WatchlistRepository` und
`ForecastCache` lesen und schreiben jeweils selbst über eine Temp-Datei, mit leicht unterschiedlichen
Rückfallwegen. → Ein generischer `JsonFileStore<T>` auf `AtomicFile` – oder DataStore, dann auch für
`SettingsStore` und `TrackingStore`. Das Repository lädt einmal selbst, statt dass jedes ViewModel
`load()` aufruft.

**S6 · Große Bildschirmdateien, keine Previews**

`WeatherScreen` (930 Zeilen, 15 Composables), `CalendarScreen` (910), `WindowDetailScreen` (888),
`PhotoGuideScreen` (771), `CaptureScreen` (733). → Je Bildschirm in Gerüst, Karten, Blätter und
Dialoge aufteilen. Es gibt kein einziges `@Preview`, obwohl `ui-tooling-preview` eingebunden ist;
Previews für Karten, Zeilen und Diagramme anlegen.

**S7 · Doppelter Code**

* `drawLabel` samt Text-Paint-Fabrik viermal (`SkyOverlay`, `AstroWeatherChart`,
  `AltitudeCurveChart`, `WindowTrackChart`) → ein gemeinsamer Helfer für Diagramme.
* 25 `DateTimeFormatter` in 15 Dateien, `"HH:mm"` allein neunmal. `AstroWeatherChart` benutzt
  `"EEE HH:mm"` ohne `Locale` und zeigt auf englisch eingestellten Geräten „Mon" mitten im deutschen
  UI → ein zentrales Format-Objekt.
* `CaptureScreen` hat ein eigenes privates `StatusPill` neben dem aus `ui/components/Design.kt`.

**S8 · Tests**

Alle 30 Testdateien liegen flach in `com.starwindow.app` und heißen nach Themen (`AstroMathTest`,
`FilterAndSpanTest`). → Paketstruktur spiegeln, nach getesteter Klasse benennen.

**S9 · Dokumentation**

* Die README hat über 1.000 Zeilen: Anleitung, Herleitungen, Aufbau, Tests und Ausblick in einem.
* README („Projektaufbau") und Abschnitt 3 hier zeigen je einen Strukturbaum. Der in der README ist
  unvollständig (es fehlen `core/calibration`, `data/favorites`, `data/gear` und `data/images`), und
  zwei Bäume laufen früher oder später ohnehin auseinander.
* Die README nennt 410 Unit-Tests, es sind 462.

→ Kurze README; `docs/architecture.md` mit einem einzigen Strukturbaum, den Schichtregeln und dem
Datenfluss; die Herleitungen nach `docs/`; kurze Entscheidungsnotizen für Festlegungen wie „kein DI"
und „keine Datenbank".

**S10 · Kommentare**

Rund 19 % der Zeilen sind Kommentare. Die Warum-Erklärungen sind eine Stärke, aber die Sprache
wechselt: gut 60 Dateien haben deutsche, gut 80 englische KDocs, knapp 20 beides – obwohl Abschnitt 6
„Code und Bezeichner Englisch" festlegt. Dazu kommen Verlaufsnotizen („stand früher …", „used to
be …"), die in Commit-Messages gehören. → Festlegen, ob Kommentare als Code oder als Dokumentation
zählen, und neue Kommentare danach schreiben.

**S11 · Werkzeuge**

Kein `.editorconfig`, kein `.gitattributes` (Git warnt bereits vor LF/CRLF), kein ktlint oder detekt,
keine CI – siehe auch TODO.md, Abschnitt 7.

### 11.5 Reihenfolge

| Schritt | Punkte | Aufwand |
|---|---|---|
| 1 | Profil erzeugen, Ausgangswerte messen (11.1) | klein, braucht ein Gerät |
| 2 | P1, P3, Slider aus P6, Keys aus P5 | klein |
| 3 | P2, P4, Rest von P5 und P6 – jeweils gegen die Ausgangswerte | mittel |
| 4 | Struktur in kleinen Commits: erst reine Verschiebungen (S1, S2, S8), dann Zusammenführen (S5, S7, S6), zuletzt Module (S3) | laufend |

## 12. Woran als Nächstes

Siehe [TODO.md](TODO.md); für Performance und Struktur Abschnitt 11. Die Reihenfolge in TODO.md ist
bewusst gewählt; Abschnitt 1 dort (kompilieren, Feldabgleich) blockiert alles andere.
