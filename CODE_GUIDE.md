# StarWindow – Code-Handbuch

Dieses Dokument erklärt, **wie der Code von StarWindow aufgebaut ist und wie er funktioniert**, Paket für
Paket und Klasse für Klasse. Es soll reichen, um Fehler selbst zu finden und zu beheben oder Funktionen zu
ändern, ohne vorher den ganzen Quelltext zu lesen.

| Dokument | Wofür |
|---|---|
| [README.md](README.md) | Was die App kann, aus Sicht der Benutzung, mit Herleitungen |
| [DEV_PLAN.md](DEV_PLAN.md) | Konventionen, Architekturentscheidungen, Performance- und Strukturplan |
| [TODO.md](TODO.md) | Offene Aufgaben nach Dringlichkeit |
| **CODE_GUIDE.md** (dieses) | Wie der Code arbeitet: Pakete, Klassen, Abläufe, Stellschrauben, Fehlersuche |

**Stand:** 17. November 2026, Commit `ec26150` (Branch `claude/android-sky-observation-app-5ukrnw`).
Zeilennummern stehen bewusst nicht im Text, weil sie bei jeder Änderung veralten. Verwiesen wird über
Datei und Funktionsname; die Dateiüberschriften sind Links. Wer Code ändert, sollte den passenden
Abschnitt hier mitpflegen, vor allem die Tabellen in Kapitel 6 und 8.

**So liest man es:** Für den Einstieg die Kapitel 1, 3 und 4 lesen (zusammen etwa eine halbe Stunde),
dann gezielt das Paket in Kapitel 5, an dem man arbeitet. Wer einen konkreten Fehler sucht, beginnt mit der
Tabelle „Symptom → wo nachsehen" in [Kapitel 8](#8-kochbuch-typische-änderungen-und-fehlersuche).

## Inhalt

1. [Das Projekt in fünf Minuten](#1-das-projekt-in-fünf-minuten)
2. [Bauen, starten, testen](#2-bauen-starten-testen)
3. [Architektur](#3-architektur): Schichten, tatsächliche Abhängigkeiten, `AppContainer`, wiederkehrende Muster, Konventionen
4. [Die wichtigsten Abläufe](#4-die-wichtigsten-abläufe): App-Start, Navigation, Rechenkette, Sucherbild, Fenster, Durchgänge, Suche, Planung, Erinnerung, Merkliste, Wetter
5. [Paketreferenz](#5-paketreferenz): jedes Paket, jede Datei, jede wichtige Klasse
6. [Daten, Dateien und Netz](#6-daten-dateien-und-netz): was wo gespeichert wird, Netzzugriffe, Android-Komponenten, Kataloge und Import-Skripte
7. [Tests](#7-tests)
8. [Kochbuch: typische Änderungen und Fehlersuche](#8-kochbuch-typische-änderungen-und-fehlersuche)
9. [Auffälligkeiten und bekannte Baustellen](#9-auffälligkeiten-und-bekannte-baustellen)
10. [Glossar](#10-glossar)

---

## 1. Das Projekt in fünf Minuten

### 1.1 Was die App macht

StarWindow ist eine Android-App (Kotlin, Jetpack Compose) für Himmelsbeobachtung und Astrofotografie. Die
Kernidee: Man zeichnet mit der Handykamera einen Himmelsausschnitt ein, ein **Fenster** zwischen Dächern,
Bäumen oder Bergen. Die App speichert dieses Fenster horizontfest in Azimut und Höhe und berechnet daraus,
**welche Objekte im Lauf der Nacht hindurchziehen und wie lange**. Darum herum gibt es:

* einen **Sucher**: Kamerabild mit eingeblendeten Objekten, Gradnetz und Zielpfeil zu einem gesuchten Objekt,
* eine **Katalogsuche** über rund 22.500 Objekte (Sterne bis ~6,5 mag, OpenNGC, Sharpless, Barnard, LBN, Sonne, Mond),
* **Wetter für die Nacht** (Open-Meteo, sechs Modelle) mit Nachtbewertung, Dämmerung, Mond und Bortle-Schätzung,
* **Jahresplanung** („in welchen Nächten steht M31 gut?"), einen **Kalender** mit Erinnerungen und eine **Merkliste**,
  die sich von selbst meldet, wenn ein Ziel abends gut steht und die Nacht klar wird,
* die Reiter **Motive** (beste Ziele der Jahreszeit) und **Guide** (Aufnahmeeinstellungen für Smart-Teleskope).

Alles Rechnerische läuft offline. Ins Netz gehen nur Wetter, Ortssuche und Objektbilder.

### 1.2 Bildschirme und was dahinter steckt

| Bildschirm (Route) | UI: Screen / ViewModel | Fachlogik (`domain`, `core`) | Daten (`data`, `core/sensors`) |
|---|---|---|---|
| **Sucher** (`capture`, Start) | `CaptureScreen` / `CaptureViewModel` | `SkyProjection`, `TargetIndicator`, `OverlaySelection`, `SkyTrackBuilder` | `OrientationTracker`, `LocationTracker`, `CatalogRepository`, `SkyWindowRepository`, `TrackingStore`, `SettingsStore` |
| **Motive** (`hub`) | `StargazingHubScreen` / `StargazingHubViewModel` | `SeasonalHighlights` (→ `ObservationPlanner.nightFor`, `PhotographicInterest`, `Popularity`) | `CatalogRepository`, `ObjectNotesRepository`, `SkyImageLoader`, `SettingsStore` |
| **Guide** (`guide`) | `PhotoGuideScreen` / `PhotoGuideViewModel` | `PhotoGuide`, `ObservationPlanner.nightFor`, `BortleScale`, `ObjectSearch` | `SmartTelescopes`, `CatalogRepository`, `FavoritesRepository`, `WeatherRepository.lastKnown`, `SettingsStore` |
| **Kalender** (`calendar`) | `CalendarScreen` / `CalendarViewModel` (+ `SessionSheet`, `WatchSheet`) | `NightOutlooks`, `ObservationPlanner.nightFor` | `PlanRepository`, `WatchlistRepository`, `WatchScheduler`, `WeatherRepository` |
| **Wetter** (`weather`) | `WeatherScreen` / `WeatherViewModel` | `AstroWeather`, `BortleScale`, `Twilight`, `Ephemeris` | `WeatherRepository`, `ForecastCache`, `PlaceLookup`, `LocationTracker`, `SettingsStore` |
| **Fenster** (`windows`) | `WindowListScreen` / `WindowListViewModel` | – | `SkyWindowRepository`, `TrackingStore` |
| Fensterdetail (`windows/{windowId}`) | `WindowDetailScreen` / `WindowDetailViewModel` | `TransitCalculator`, `ConstellationTransitCalculator`, `TransitListView`, `SkyTrackBuilder`, `WindowAccuracy`, `ObjectDescription` | `SkyWindowRepository`, `CatalogRepository`, `ConstellationRepository`, `ObjectNotesRepository` |
| Suche (`search`) | `ObjectSearchScreen` / `ObjectSearchViewModel` (+ `CatalogFilterSheet`) | `ObjectSearch`, `CatalogFilter`, `TonightBoard`, `Feasibility`, `TransitCalculator` | `CatalogRepository`, `FavoritesRepository`, `SkyWindowRepository`, `TrackingStore`, `LocationTracker` |
| Objektblatt (`objects/{objectId}`) | `ObjectDetailScreen` / `ObjectDetailViewModel` | `ObjectDescription`, `SkyTrackBuilder` | `CatalogRepository`, `ObjectNotesRepository`, `FavoritesRepository`, `WatchlistRepository`, `TrackingStore` |
| Jahresplanung (`planning/{objectId}`) | `PlanningScreen` / `PlanningViewModel` | `ObservationPlanner` | `PlanRepository`, `CatalogRepository`, `SettingsStore`, `LocationTracker` |
| Kalibrierung (`calibration`) | `CalibrationScreen` / `CalibrationViewModel` | `AttitudeFit`, `FieldOfViewSolver`, `CalibrationTrust` | `SettingsStore`, `OrientationTracker`, `LocationTracker`, `CatalogRepository` |
| *Hintergrund, ohne UI* | `ReminderReceiver`, `WatchCheckReceiver`, `BootReceiver` | `NightOutlook`, `WatchEvaluator` | `NightWeatherSource`, `PlanRepository`, `WatchlistRepository`, `ForecastCache` |

Die sechs fett gedruckten Ziele sind die Reiter der unteren Leiste, die übrigen erreicht man aus ihnen.

### 1.3 Zahlen

| | Dateien | Zeilen |
|---|---:|---:|
| `core/` (Mathematik, Sensoren, Kamera) | 22 | 3.972 |
| `data/` (Repositories, Persistenz, Netz, Alarme) | 33 | 4.163 |
| `domain/` (Fachlogik) | 22 | 4.784 |
| `ui/` (Compose) | 48 | 15.975 |
| Wurzel (`StarWindowApplication.kt`, `MainActivity.kt`) | 2 | 110 |
| **App-Code gesamt** | **127** | **29.004** |
| Unit-Tests (`app/src/test`) | 30 | 7.508 (462 Tests) |
| Modul `baselineprofile` | 4 | ~260 |

Die Zeilen enthalten viele Kommentare (rund 19 %). Die langen KDocs am Dateianfang erklären meist, **warum**
etwas so gebaut ist, und sind oft die beste Dokumentation einer Klasse.

---

## 2. Bauen, starten, testen

### 2.1 Befehle

Unter Windows heißt der Wrapper `gradlew.bat` (PowerShell: `.\gradlew.bat …`), sonst `./gradlew`.

| Zweck | Befehl | Hinweis |
|---|---|---|
| Alle Unit-Tests (JVM, ohne Gerät) | `gradlew :app:testDebugUnitTest` | Muss grün sein, bevor man etwas anderes anfasst (462 Tests). |
| Eine Testklasse | `gradlew :app:testDebugUnitTest --tests "com.starwindow.app.TransitCalculatorTest"` | Die Testklassen heißen anders als ihre Dateien, siehe [Kapitel 7](#7-tests). |
| Debug-APK | `gradlew :app:assembleDebug` | |
| Release-APK mit R8 | `gradlew :app:assembleRelease` | Erst hier läuft R8, und erst hier fallen fehlende Keep-Regeln auf (Serialisierung!). |
| Baseline Profile erzeugen | `gradlew :app:generateBaselineProfile` | Braucht ein entsperrtes Telefon per USB. |
| Start- und Bildzeiten messen | `gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest` | Ebenfalls Gerät, Details in [DEV_PLAN.md §11.2](DEV_PLAN.md). |

Flüssigkeit nur an einem Release- oder Benchmark-Build beurteilen: Ohne R8 läuft Compose spürbar
langsamer (Kommentar in `app/build.gradle.kts`).

### 2.2 Werkzeuge und Versionen

| Komponente | Version / Wert | Wo festgelegt |
|---|---|---|
| Gradle | 9.4.1 | `gradle/wrapper/gradle-wrapper.properties` |
| Android Gradle Plugin | 9.2.1 | `gradle/libs.versions.toml` |
| Kotlin | 2.3.21 (JVM-Ziel 17) | `libs.versions.toml`, `app/build.gradle.kts` |
| `compileSdk` / `targetSdk` / `minSdk` | 36 / 36 / 26 | `app/build.gradle.kts` |
| Compose-BOM (Material 3) | 2024.12.01 | `libs.versions.toml` |
| Navigation Compose / Lifecycle | 2.8.5 / 2.8.7 | `libs.versions.toml` |
| CameraX (core, camera2, lifecycle, view) | 1.4.1 | `libs.versions.toml` |
| kotlinx.serialization-json | 1.7.3 | `libs.versions.toml` |
| JUnit 4 + `kotlin-test-junit` | 4.13.2 | `libs.versions.toml` |
| Baseline-Profile-Plugin / Macrobenchmark | 1.5.0 | `libs.versions.toml` |

Bewusst **nicht** enthalten: DI-Framework, Datenbank, Netzwerkbibliothek, Bildbibliothek, Google Play
Services. Die Gründe stehen in [DEV_PLAN.md §7](DEV_PLAN.md); jede dieser Stellen ist eine einzelne Naht
(`AppContainer`, die JSON-Repositories, `HttpURLConnection` in `data/weather` und `data/images`,
`SkyImageLoader`, `LocationManager` in `LocationTracker`).

### 2.3 Gradle-Dateien

| Datei | Inhalt |
|---|---|
| [`settings.gradle.kts`](settings.gradle.kts) | Zwei Module: `:app` und `:baselineprofile`. Repositories `google()` und `mavenCentral()`. |
| [`build.gradle.kts`](build.gradle.kts) | Nur Plugin-Deklarationen (`apply false`); Versionen kommen aus dem Versionskatalog. |
| [`gradle/libs.versions.toml`](gradle/libs.versions.toml) | Versionskatalog. Eine Abhängigkeit aktualisiert man hier, nicht in den Moduldateien. |
| [`app/build.gradle.kts`](app/build.gradle.kts) | Namespace/`applicationId` `com.starwindow.app`, SDK-Stände, `release` mit `isMinifyEnabled` und `isShrinkResources`, Compose, `baselineProfile { dexLayoutOptimization = true }`, ein Windows-Workaround für das Baseline-Profile-Plugin (`androidComponents.finalizeDsl`, setzt die Kotlin-Quellordner der Buildtypen `nonMinifiedRelease`/`benchmarkRelease` ausgeschrieben), Abhängigkeiten. |
| [`app/proguard-rules.pro`](app/proguard-rules.pro) | Keep-Regeln, damit R8 die generierten Serializer der `@Serializable`-Klassen nicht entfernt. |
| [`gradle.properties`](gradle.properties) | JVM-Speicher, paralleler Build, Build-Cache, Configuration-Cache. `android.builtInKotlin=false` und `android.newDsl=false`: AGP 9 brächte eigenes Kotlin mit, das Projekt bleibt aber beim Kotlin-Gradle-Plugin, weil Compose- und Serialization-Plugin daran hängen (TODO: vor AGP 10 umstellen). |
| `local.properties` | SDK-Pfad; legt Android Studio selbst an, ist nicht eingecheckt. |

### 2.4 Ohne Gerät entwickeln

Ein Emulator hilft kaum: Er hat weder Kompass noch eine Kamera, die Sterne sieht. Das Manifest verlangt
Kamera, Kompass und Beschleunigungssensor sogar als Pflicht-Features (`uses-feature … required="true"`).
Was ohne Himmel geht:

* alles Rechnerische über die Unit-Tests (die Rechenschicht hat absichtlich keine Android-Abhängigkeit),
* Standort von Hand setzen statt GPS (Einstellungen in `SettingsStore`),
* Bildfeld über die Schwenk-Kalibrierung bestimmen – sie braucht nur Gyroskop und ein beliebiges Merkmal
  im Raum.

---

## 3. Architektur

### 3.1 Schichten

```
┌───────────────────────────────────────────────────────────────────────────┐
│ ui/*      Compose-Bildschirme + ViewModels, ein Paket je Bereich             │
│           ui/nav (Routen, untere Leiste) · ui/components · ui/theme          │
└──────────────┬──────────────────────────┬─────────────────────────┬────────┘
               │                          │                         │
               ▼                          ▼                         │
┌───────────────────────────┐   ┌──────────────────────────────┐    │
│ domain/                    │◄──┤ data/*                        │    │
│ Fachlogik, Android-frei:   │   │ Repositories, JSON-Dateien,   │    │
│ Durchgänge, Suche, Auswahl,│──►│ SharedPreferences, Netz,      │    │
│ Nachtbewertung, Planung,   │   │ Alarme, Receiver,             │    │
│ Motive, Fotoguide          │   │ Benachrichtigungen            │    │
└──────────────┬────────────┘   └───────────────┬──────────────┘    │
               ▼                                ▼                   ▼
┌───────────────────────────────────────────────────────────────────────────┐
│ core/*    astro · geometry · calibration       (reine Mathematik)           │
│           sensors · camera                     (Mathematik + Android-Insel) │
└───────────────────────────────────────────────────────────────────────────┘
```

**Die eine Regel, die zählt:** `core/astro`, `core/geometry`, `core/calibration` und `domain` importieren
nichts aus `android.*`. Nur deshalb laufen die 462 Tests als normale JVM-Tests ohne Emulator. Stand heute
hält sich der Code daran (geprüft per Grep). Android steckt ausschließlich in:

| Ort | Android-Teil |
|---|---|
| `core/sensors` | `OrientationTracker` (SensorManager), `LocationTracker` (LocationManager). `AttitudeFusion` und `DeviceAttitude` sind reine Mathematik und getestet (`DeviceAttitude` benutzt nur zwei `SensorManager`-Konstanten, die der Compiler einsetzt). |
| `core/camera` | `NightVisionController` (Camera2-Interop) sowie je eine `fromCharacteristics`-Funktion in `CameraIntrinsics.kt` und `ExposureSettings.kt`. `SkyProjection` und `TargetIndicator` sind reine Mathematik. |
| `data/*` | Dateien unter `filesDir`/`cacheDir`, SharedPreferences, Assets, AlarmManager, BroadcastReceiver, Notifications. |
| `ui/*` | Compose, CameraX, ViewModels. |

Wer in `domain` oder `core/astro|geometry|calibration` etwas aus Android braucht (etwa `Context`), hat die
falsche Stelle erwischt: Die Android-Seite gehört nach `data/` oder `ui/` und reicht reine Werte herunter.

### 3.2 Tatsächliche Paketabhängigkeiten

Ermittelt aus den `import`-Zeilen (Stand dieses Dokuments). Gelesen als „Paket links importiert aus …“:

| Paket | importiert aus |
|---|---|
| `core/astro` | – |
| `core/geometry` | `core/astro` |
| `core/calibration` | `core/astro`, `core/geometry` |
| `core/sensors` | `core/astro`, `core/calibration` (`OrientationTracker` → `Calibration`) |
| `core/camera` | `core/astro`, `core/geometry`, `core/sensors` |
| `data/catalog` | `core/astro`, **`domain`** (`ObjectNotesRepository` → `ObjectSearch`) |
| `data/favorites`, `data/gear`, `data/images` | – |
| `data/tracking` | `core/geometry`, `data/catalog` |
| `data/windows` | `core/astro`, `core/calibration`, `core/camera`, `core/geometry`, **`data/weather`** (`SettingsStore` hält `WeatherModel`, `WeatherPlace`) |
| `data/weather` | `core/astro`, **`data/windows`** (`NightWeatherSource` → `SettingsStore`), **`domain`** (`NightOutlook`) |
| `data/planning` | `core/astro`, `data/catalog`, `data/tracking`, `data/weather`, **`domain`** (`ObservationPlanner`, `NightOutlook`, `WatchEvaluator`, `WatchHit`), dazu `AppContainer`, `MainActivity`, `R` |
| `domain` | `core/astro`, `core/geometry`, `core/sensors` (nur `AttitudeFusion` in `WindowAccuracy`), **`data/catalog`**, **`data/gear`**, **`data/planning`**, **`data/weather`** (nur Modellklassen) |
| `ui/theme` | `data/catalog` (`ObjectType` für die Objektpalette) |
| `ui/components` | `core/camera`, `data/catalog`, `data/images`, `domain`, **`ui/capture`** (`SkyViewport` → `PreviewStreamInfo`), `ui/theme` |
| `ui/capture` | `core/*`, `data/catalog`, `data/tracking`, `data/windows`, `domain`, `ui/components`, `ui/theme` |
| `ui/calibration` | `core/astro`, `core/calibration`, `core/geometry`, `core/sensors`, `data/catalog`, `data/windows`, `ui/capture`, `ui/components`, `ui/theme` |
| `ui/windows` | `core/astro`, `core/geometry`, `data/catalog`, `data/images`, `data/tracking`, `data/windows`, `domain`, `ui/components`, `ui/theme` |
| `ui/search` | `core/astro`, `core/geometry`, `core/sensors`, `data/catalog`, `data/favorites`, `data/planning`, `data/tracking`, `data/windows`, `domain`, `ui/components`, `ui/theme`, `ui/windows` |
| `ui/planning` | `core/astro`, `core/sensors`, `data/catalog`, `data/planning`, `data/weather`, `data/windows`, `domain`, `ui/components`, `ui/theme`, `ui/windows` |
| `ui/hub` | `core/sensors`, `data/catalog`, `data/images`, `data/windows`, `domain`, `ui/components`, `ui/planning`, `ui/theme` |
| `ui/guide` | `core/astro`, `core/geometry`, `core/sensors`, `data/catalog`, `data/favorites`, `data/gear`, `data/weather`, `data/windows`, `domain`, `ui/components`, `ui/planning`, `ui/theme` |
| `ui/weather` | `core/astro`, `core/sensors`, `data/weather`, `data/windows`, `domain`, `ui/components`, `ui/theme`, `ui/windows` |
| `ui/nav` | `data/planning` (`PlannedPath`), alle Bildschirm-Pakete, `ui/theme` |

Daraus folgen drei **Zyklen**, die man kennen sollte, bevor man Klassen verschiebt (siehe auch
[DEV_PLAN.md §11.4 S1–S3](DEV_PLAN.md)):

1. `domain` ↔ `data`: `domain` benutzt die Modellklassen aus `data` (`SkyObject`, `ObjectType`,
   `Constellation`, `WeatherForecast`, `WatchedObject`, `SmartTelescope` …), umgekehrt rufen
   `data/catalog`, `data/weather` und `data/planning` Fachlogik aus `domain` auf.
2. `data/windows` ↔ `data/weather`: `SettingsStore` speichert Wettermodell und Ort, `NightWeatherSource`
   liest dafür den `SettingsStore`.
3. `ui/components` ↔ `ui/capture`: `SkyViewport` braucht `PreviewStreamInfo` aus dem Sucher.

Dazu kommen geteilte Helfer, die in einem Feature-Paket liegen: `ui/windows` liefert `formatClock`,
`formatDuration` (`TimeFormatting.kt`) und `ObjectInfo…` (`ObjectInfoSheet.kt`) an Suche, Planung und
Wetter; `ui/planning/PlanningLocation` wird von Hub und Guide benutzt. Kotlin stört das nicht (ein Modul),
es macht aber das Verschieben in eigene Gradle-Module später aufwendiger.

### 3.3 `AppContainer`: wer was erzeugt

Es gibt kein DI-Framework. [`AppContainer`](app/src/main/java/com/starwindow/app/StarWindowApplication.kt)
wird einmal in `StarWindowApplication.onCreate()` gebaut und erzeugt **alle** langlebigen Objekte sofort
(nicht `lazy`). Erreichbar ist er über die Erweiterung `Context.appContainer`.

| Feld | Klasse | Aufgabe |
|---|---|---|
| `orientationTracker` | `core/sensors/OrientationTracker` | Lage des Telefons aus den Rotationsvektor-Sensoren (Kreisel + Kompass) und dem Magnetometer, geglättet, als Flow |
| `locationTracker` | `core/sensors/LocationTracker` | Standort über `LocationManager`, als Flow |
| `windowRepository` | `data/windows/SkyWindowRepository` | gespeicherte Fenster (eine JSON-Datei) |
| `catalogRepository` | `data/catalog/CatalogRepository` | Sterne (`stars.json`) und Deep-Sky (`deepsky.json`) aus den Assets, gemischt |
| `constellationRepository` | `data/catalog/ConstellationRepository` | 88 Sternbildfiguren (`constellations.json`) |
| `objectNotesRepository` | `data/catalog/ObjectNotesRepository` | Begleittexte zu bekannten Objekten (`object_notes.json`) |
| `favoritesRepository` | `data/favorites/FavoritesRepository` | Favoriten |
| `settingsStore` | `data/windows/SettingsStore` | alle Einstellungen (Ort, Kalibrierung, Nachtsicht, Wettermodell …) |
| `trackingStore` | `data/tracking/TrackingStore` | das verfolgte Ziel: ein Objekt (optional mit der Bahn einer geplanten Nacht) oder ein Fenster |
| `planRepository` | `data/planning/PlanRepository` | geplante Beobachtungsnächte samt Erinnerungen |
| `watchlistRepository` | `data/planning/WatchlistRepository` | Merkliste |
| `watchScheduler` | `data/planning/WatchScheduler` | Alarm für die tägliche Merklisten-Prüfung |
| `skyImageLoader` | `data/images/SkyImageLoader` | Objektbilder über hips2fits, Speicher- und Plattencache in `cacheDir` |
| `forecastCache` | `data/weather/ForecastCache` | Vorhersage auf der Platte (`filesDir`), damit eine Erinnerung im frisch gestarteten Prozess etwas über die Nacht sagen kann |
| `weatherRepository` | `data/weather/WeatherRepository` | Open-Meteo-Abfragen, mit `forecastCache` als Plattenspeicher |
| `placeLookup` | `data/weather/PlaceLookup` | Ortssuche (Geocoding) |
| `transitCalculator` | `domain/TransitCalculator` | Durchgänge einzelner Objekte durch ein Fenster |
| `constellationTransitCalculator` | `domain/ConstellationTransitCalculator` | Durchgänge von Sternbildfiguren |

**Neue Abhängigkeit hinzufügen:** ein `val` im `AppContainer` anlegen, im `factory(…)` des ViewModels aus
dem Container holen und dem ViewModel-Konstruktor als einzelnen Parameter übergeben (nie den ganzen
Container – Ausnahme ist `PlannedPath.track(container, …)`).

### 3.4 Muster, die sich überall wiederholen

**ViewModel mit Fabrik.** Jedes ViewModel hat eine `companion object`-Fabrik, die aus dem Container nur die
benötigten Teile zieht. Der Konstruktor kennt damit keinen `Context`:

```kotlin
class WindowListViewModel(
    private val repository: SkyWindowRepository,
    private val trackingStore: TrackingStore,
) : ViewModel() {
    // …
    companion object {
        fun factory(container: AppContainer) = viewModelFactory {
            initializer { WindowListViewModel(container.windowRepository, container.trackingStore) }
        }
    }
}
```

Im `NavHost` wird es mit `viewModel(factory = XxxViewModel.factory(container))` geholt; ViewModels mit
Argument (`objectId`, `windowId`) bekommen zusätzlich `key = id`, damit zwei Objektblätter nicht dasselbe
ViewModel teilen.

**Ein UiState je Bildschirm.** Es gibt je Bildschirm eine `data class …UiState` (`CaptureUiState`,
`WindowDetailUiState`, `CalendarUiState`, `WeatherUiState` …), die das ViewModel als `StateFlow`
bereitstellt; der Bildschirm sammelt sie mit `collectAsStateWithLifecycle()`. Änderungen an der Anzeige
beginnen deshalb fast immer mit einem neuen Feld im UiState.

**Repositories über JSON-Dateien.** Fenster, Favoriten, Termine, Merkliste und die Vorhersage liegen je in
einer JSON-Datei (kotlinx.serialization), werden in einem `StateFlow` gehalten und über eine Temp-Datei
geschrieben; Einstellungen und Ziel liegen in SharedPreferences. Details und Dateinamen:
[Kapitel 6](#6-daten-dateien-und-netz).

**Rechnen abseits des UI-Threads.** Die Fachlogik in `domain` ist reines Kotlin; die ViewModels rufen sie
in Coroutinen auf (die Durchgangsrechnung ist `suspend`). Wer neue schwere Rechnungen einbaut, sollte sie
genauso in `domain` als reine Funktion anlegen und vom ViewModel aus starten.

**Texte stehen im Code.** `res/values/strings.xml` enthält nur `app_name`; alle Beschriftungen sind
deutsche String-Literale in den Composables und in den Benachrichtigungsklassen. Eine Beschriftung findet
man also per Volltextsuche. Vorsicht: Die Benchmarks in `baselineprofile/…/Journeys.kt` finden Bedienelemente
über ihre sichtbaren Texte – wer einen Reiter umbenennt, muss ihn dort nachziehen.

**Kommentare erklären das Warum.** Viele Dateien beginnen mit einem langen KDoc, der begründet, warum etwas
so gebaut ist. Diese Kommentare sind oft die beste Dokumentation einer Klasse und sollten bei Änderungen
mitgepflegt werden. Sprache gemischt Deutsch/Englisch.

### 3.5 Konventionen in Kurzform

Vollständig in [DEV_PLAN.md §2](DEV_PLAN.md). Vorzeichen- und Achsfehler sind in diesem Projekt die
teuerste Fehlerklasse, weil sie plausible, aber falsche Ergebnisse liefern.

| Größe | Konvention |
|---|---|
| Azimut | 0° = geografisch Nord, wachsend nach Osten (90° O, 180° S, 270° W) |
| Höhe | 0° = Horizont, +90° = Zenit |
| Stundenwinkel | H = LST − RA, positiv nach dem Meridiandurchgang |
| Weltsystem | ENU: x = Ost, y = Nord, z = oben |
| Displaysystem | x = rechts, y = oben, z = aus dem Bildschirm heraus; die Rückkamera blickt entlang −z |
| Rotationsmatrix | bildet Display auf Welt ab: `v_welt = R · v_display` (zeilenweise gespeichert) |
| Epoche | Kataloge in J2000; alles, was auf den echten Himmel zeigen soll, geht über `Precession` auf das Datum. Nutation, Aberration, Eigenbewegung bewusst weggelassen (zusammen < 0,01°); Refraktion getrennt |
| Zeit | UTC-Millisekunden (`Long`); UT1 ≈ UTC |
| Nordrichtung | Der Sensor liefert **magnetisch** Nord; erst `DeviceAttitude.toTrueNorth()` macht daraus geografisch Nord (Missweisung + Kalibrierkorrektur) |
| Fensterpunkte | werden als Azimut/Höhe gespeichert und in jedem Bild neu projiziert, **nie** als Bildschirmkoordinaten |

---

## 4. Die wichtigsten Abläufe

Die Pfeilketten zeigen, welche Klassen und Funktionen nacheinander durchlaufen werden. Sie sind der
schnellste Weg, um bei einem Fehler die richtige Datei zu finden. Details zu jeder Klasse stehen in der
[Paketreferenz](#5-paketreferenz).

### 4.1 App-Start

```
Android startet den Prozess
 └► StarWindowApplication.onCreate()
      └► AppContainer(this)            erzeugt ALLE Repositories/Tracker sofort (nicht lazy);
                                       SettingsStore und TrackingStore lesen dabei SharedPreferences
 └► MainActivity.onCreate()
      ├► enableEdgeToEdge(), FLAG_KEEP_SCREEN_ON (Bildschirm bleibt an)
      └► setContent { StarWindowTheme { Surface(Night) { StarWindowNavHost() } } }
           └► NavHost(startDestination = "capture")
                └► CaptureViewModel.factory(container) → CaptureViewModel.init
                     ├► settingsStore.settings sammeln → Kalibrierung an OrientationTracker
                     ├► catalogRepository.objects()   (lädt beim ersten Aufruf stars.json + deepsky.json)
                     ├► trackingStore.target sammeln, windowRepository.load()
                     └► startLocationUpdates() → LocationTracker.locations()
                └► CaptureScreen: fragt beim ersten Betreten CAMERA + Standort an
```

Auch wenn ein **Alarm oder der Neustart** die App weckt (Erinnerung, Merkliste, `BOOT_COMPLETED`), läuft
zuerst `StarWindowApplication.onCreate()` und baut den ganzen `AppContainer` – erst dann der Receiver.

### 4.2 Navigation

```
StarWindowBottomBar (6 Reiter) ──► switchTo(route)
    navigate(route) { popUpTo("capture") { saveState = true }; launchSingleTop; restoreState }
    → Der Sucher bleibt immer unten im Stapel; ein Reiterwechsel ersetzt nur das Ziel darüber.

Detailziele (ohne Leiste):  search · objects/{objectId} · planning/{objectId} · windows/{windowId} · calibration
    navController.navigate(...) / popBackStack()

„Verfolgen", „Im Sucher zeigen", „Pfad zeigen", Fenster-Fadenkreuz:
    1. Ziel in TrackingStore schreiben (TrackedObject / TrackedWindow)
    2. navController.popBackStack("capture", inclusive = false)
    → Der Sucher liest das Ziel aus dem TrackingStore, nicht aus einem Navigationsargument.
```

### 4.3 Vom Fingertipp zur Himmelsrichtung (Rechenkette)

Das ist der Kern der App ([DEV_PLAN.md §4](DEV_PLAN.md)). Wer hier etwas ändert, braucht die Tests in
`ProjectionTest`, `CalibrationTest` und `AstroMathTest`.

```
Pixel (x, y) in der Overlay-View
 │  SkyProjection.screenToSky(x, y)                                   core/camera
 │    Strahl im Displaysystem: (x − cx, cy − y, −f)
 │    f = SkyProjection.focalLengthInViewPixels(intrinsics, Stream, Displaydrehung,
 │                                               View-Größe, FIT_CENTER, fovScale)
 ▼
 │  DeviceAttitude.displayToWorld(v) = R · v                          core/sensors
 │    R aus OrientationTracker (Kreisel-Lage + Kompass-Kurs, geglättet), Welt = ENU mit MAGNETISCH Nord
 ▼
 │  DeviceAttitude.toTrueNorth(v)
 │    correction · aboutZenith(Missweisung)   Missweisung aus GeomagneticField, correction = Kalibrierung
 ▼
Horizontal(azimuthDeg, altitudeDeg)  – so wird ein Fensterpunkt gespeichert
 │  (nur bei Bedarf) CoordinateTransforms.horizontalToEquatorial(…, location, epochMillis)   core/astro
 ▼
Equatorial(raDeg, decDeg) zum Datum   (→ Precession.toJ2000 für Katalogvergleich)
```

Rückwärts (Katalogobjekt → Pixel), wie es das Overlay in jedem Bild macht:

```
SkyObject.positionAt(precession)              J2000 → Datum (Sonne/Mond: Ephemeride)
 → CoordinateTransforms.apparentHorizontalAtLst(eq, Breite, LST)   inkl. Refraktion
 → SkyProjection.skyToScreen(horizontal)
      DeviceAttitude.toMagneticVector  →  worldToDisplay (Rᵀ)  →  Lochkamera (z < 0 = vor der Kamera)
 → ScreenPoint oder null (hinter der Kamera)
```

### 4.4 Ein Bild im Sucher

```
Sensorereignis (GAME_ROTATION_VECTOR, ~50 Hz, Main-Thread)
 └► OrientationTracker.FusionState.onPrimary
      Slerp-Glättung (AttitudeFusion) · Kurs vom Kompass · Missweisung · Kalibrierung · Displaydrehung
 └► Flow<DeviceAttitude> (conflate)
 └► CaptureViewModel.attitude  (stateIn)            hudAttitude = dieselbe Lage, sample(250) für Texte
 └► CaptureScreen: attitudeState wird NICHT in der Komposition gelesen …
 └► SkyOverlay-Canvas: … sondern erst im Draw-Lambda (nur Neuzeichnen, keine Rekomposition)
      SkyProjection(attitude, viewport.focalPx, Breite, Höhe)
      Zeichenreihenfolge:
        drawGraticule → drawCatalog (je Objekt: apparentHorizontalAtLst → skyToScreen)
        → drawWindowShape (outline(96)) → Kalibrier-Hervorhebung
        → verfolgtes Ziel: Kontur / drawSkyTrack / drawTargetMarker (TargetIndicator.locate)
        → drawAnchors → drawCrosshair

Daneben:
  Kamera:   CameraPreview → PreviewStreamInfo → rememberSkyViewport → focalPx
  Katalog:  CaptureViewModel.refreshOverlayCatalog → OverlaySelection.select (≤ 400 Objekte)
  Ziel:     CaptureViewModel.trackedTarget = combine(TrackingStore, Standort, Sekundenticker) → SkyTarget
```

### 4.5 Ein Fenster einzeichnen und speichern

```
Tipp auf den Sucher (CaptureScreen, detectTapGestures)
 └► SkyProjection(attitude, focalPx, …).screenToSky(x, y)        → Horizontal (Az/Höhe)
 └► CaptureViewModel.addAnchor(direction)
 └► CaptureUiState.shape = buildShape(mode, anchors)
        POLYGON ≥ 3 Punkte → PolygonWindow   (Warnung bei isSelfIntersecting)
        CIRCLE  2 Punkte   → CircleWindow.fromCenterAndEdge
        BOX     2 Punkte   → AltAzBoxWindow.fromCorners
 └► „Fenster speichern" → SaveWindowDialog → CaptureViewModel.saveWindow(name, visibleFovDeg)
        SkyWindow(id, name, shape, observer, capturedAtMillis,
                  Missweisung, Kompassgüte, Kalibrierrest, headingHeld…, cameraFovDeg)
 └► SkyWindowRepository.save → filesDir/sky_windows.json
```

### 4.6 Fenster öffnen → Durchgangsliste

```
Reiter „Fenster" → WindowListScreen (WindowListViewModel.windows ← SkyWindowRepository.windows)
 └► Tipp auf Karte → navigate("windows/{id}") → WindowDetailViewModel.factory(container, id)
 └► init: windowRepository.load() + find(id) → search()
      from = jetzt + startHoursOffset, until = jetzt + endHoursOffset   (Vorgabe 0 … 24 h)
      CatalogRepository.objectsVisibleFrom(Breite, Grenzgröße)          grober Vorfilter
      TransitCalculator.search(window, objects, from, until)            Dispatchers.Default
         membershipTest() einmal · Deklinations-/Höhenband-Vorfilter · Precession einmal
         DarkSpans.over(...) · je Objekt IntervalScanner.scan (60 s, Bisektion 12×)
         withConditions → Dunkelzeit, Mondhöhe, Mondbeleuchtung
      ConstellationTransitCalculator.search(window, constellations, from, until)
 └► _uiState (result, constellations) → rebuildTracks() (SkyTrackBuilder.forInterval)
 └► WindowDetailScreen: visibleObjects = TransitListView.build(transits, filter, query, sort, onlyDark)
      WindowTrackChart(tracks) · HighlightRow · ConstellationCard · TransitCard
```

Neu gerechnet wird beim Öffnen, beim Loslassen des Zeitraum-Schiebers und bei Änderung der Grenzgröße.
Filter, Suchtext, Sortierung und „nur nachts" arbeiten nur auf dem vorhandenen Ergebnis.

### 4.7 Objekt suchen und im Sucher wiederfinden

```
Sucher: SearchPill → navigate("search") → ObjectSearchViewModel
 └► setQuery → recompute(debounce = 180 ms) → ObjectSearch.search(query, candidates, observer, sort)
      ohne Eingabe und ohne Filter: TonightBoard.build (Jetzt im Fenster / Steht hoch / Kommt noch)
 └► Tipp → navigate("objects/{id}") → ObjectDetailViewModel (Beschreibung, Höhenkurve, Status)
 └► „Track" → trackingStore.track(TrackedObject(obj)) → popBackStack("capture")
 └► CaptureViewModel.trackedTarget (jede Sekunde) → SkyTarget(direction, …)
 └► SkyOverlay.drawTargetMarker → TargetIndicator.locate → Ring auf dem Ziel oder Pfeil am Rand
```

### 4.8 Jahresplanung → Termin → Erinnerung

```
Objektblatt „Planung" → navigate("planning/{id}") → PlanningViewModel
 └► PlanningLocation.resolve (Wetterort → manueller Ort → letzte GPS-Position)
 └► ObservationPlanner.plan(obj, observer, zone, heute, 365 Tage) → bestNights, season, isYearRound
 └► Tipp auf eine Nacht → togglePlan → PlanRepository.add(PlannedSession)   (noch ohne Erinnerung)
                                       → filesDir/observation_plan.json
Kalender → SessionSheet → Schalter → CalendarViewModel.toggleReminder(lead)
 └► PlanRepository.toggleReminder → Datei schreiben → ReminderScheduler.reschedule(session)
 └► AlarmManager.setAndAllowWhileIdle(RTC_WAKEUP, Nacht − daysBefore um 17:00 Gerätezeit)
      ⋮  (Tage später, evtl. in einem frisch gestarteten Prozess)
 └► ReminderReceiver.onReceive
      1. ReminderNotifications.post(ohne Wetter)                 sofort, ohne Netz und Datei
      2. goAsync → NightWeatherSource.stored(night)              Platten-Cache → gleiche ID neu posten
      3. withTimeoutOrNull(20 s) { fresh(night) }                Netz → gleiche ID neu posten
Neustart:          BootReceiver → PlanReminderSync.rescheduleAll + WatchScheduler.scheduleDailyCheck
Kalender öffnen:   CalendarViewModel.init → PlanRepository.rescheduleAll()  (repariert verlorene Alarme)
```

### 4.9 Merkliste („sag Bescheid, wenn es passt")

```
Objektblatt „Bescheid geben" → ObjectDetailViewModel.toggleWatch
 └► WatchlistRepository.add → filesDir/watchlist.json → WatchScheduler.scheduleDailyCheck (15:00)
      ⋮  täglich
 └► WatchCheckReceiver.onReceive
      (attempt == 0) zuerst den Alarm für morgen setzen  – sonst würde eine Ausnahme die Kette beenden
      goAsync → WatchlistRepository(neue Instanz).readOnce()
             → NightWeatherSource.fresh(heute) ?: stored(heute)      ohne Ergebnis: Retry +45 min, max. 2×
             → WatchEvaluator.evaluateAll  (Ruhezeit · Wetterurteil · ObservationPlanner.nightFor ·
                                            längste Überschneidung ≥ minUsableHours)
             → WatchNotifications.post (eine Sammelmeldung) → markNotified(heute)
```

### 4.10 Wetter-Reiter → Nachtbewertung

```
Reiter „Wetter" → WeatherViewModel.init
 ├► Modell aus SettingsStore; gespeicherter Ort → selectPlace, sonst useCurrentLocation
 │     useCurrentLocation: LocationTracker.lastKnown → PlaceLookup.describe (Geocoder + Ortssuche)
 ├► refreshModelStatuses → WeatherRepository.modelStatuses (meta.json je Modell, parallel)
 └► load() → WeatherRepository.forecast(place, model)                       Dispatchers.IO
        Speicher-Cache (30 min) → Open-Meteo (volle, dann reduzierte Variablenliste)
        → bei Netzfehler alter Speicher- oder Platten-Cache (ForecastCache)
    → AstroWeather.nights(forecast, location)                              Dispatchers.Default
        je Stunde: Sonne/Mond (Ephemeris) → rate() = Dunkelheit · Wolken · Mond · Tau/Wind
        je Nacht:  Twilight.nightOf → clearDarkMillis, usableDarkMillis → verdict, stargazingRating
    → WeatherUiState.nights → WeatherScreen → NightRatingCard · AstroWeatherChart · NightDetails
```

---

## 5. Paketreferenz

Jeder Abschnitt beginnt mit Zweck und Abhängigkeiten des Pakets. Danach folgt je Datei eine Tabelle der
wichtigen Elemente, dann **Funktionsweise** (Ablauf, Einheiten, Konstanten, Threading), **Zusammenspiel**
(wer ruft was auf) und **Ändern/Fallstricke** (was man mitändern muss, welche Tests es absichern). Am Ende
jedes Pakets stehen typische Änderungen als Schritt-für-Schritt-Hinweise. Alle Pfade beziehen sich auf
`app/src/main/java/com/starwindow/app/`.

| Paket | Inhalt |
|---|---|
| [Wurzelpaket](#pkg-root) | `StarWindowApplication` mit `AppContainer`, `MainActivity`, Manifest |
| [`core/astro`](#pkg-core-astro) | Zeit und Sternzeit, Koordinaten, Horizont ⇄ Äquator, Refraktion, Präzession, Sonne/Mond, Dämmerung |
| [`core/geometry`](#pkg-core-geometry) | Fensterformen, Kugelgeometrie, Punkt-im-Polygon über gnomonische Projektion |
| [`core/sensors`](#pkg-core-sensors) | Lage des Telefons (Sensorfusion), Missweisung, Standort |
| [`core/camera`](#pkg-core-camera) | Kameraoptik, Pixel ⇄ Himmel, Nachtbelichtung, Zielpfeil |
| [`core/calibration`](#pkg-core-calibration) | Lagekorrektur, Bildfeldfaktor, Vertrauen in eine Kalibrierung |
| [`data/catalog`](#pkg-data-catalog) | Katalogmodell `SkyObject`, Asset-Kataloge, Sonne/Mond, Sternbilder, Objektnotizen |
| [`data/windows`](#pkg-data-windows) | Ablage der Fenster und **alle Einstellungen** (`SettingsStore`) |
| [`data/tracking`](#pkg-data-tracking) | Das verfolgte Ziel des Suchers |
| [`data/favorites`](#pkg-data-favorites) | Favoriten |
| [`data/images`](#pkg-data-images) | Objektbilder über hips2fits |
| [`data/gear`](#pkg-data-gear) | Daten der Smart-Teleskope |
| [`data/weather`](#pkg-data-weather) | Open-Meteo, Wettermodelle, Vorhersage-Cache, Ortssuche |
| [`data/planning`](#pkg-data-planning) | Termine, Merkliste, Alarme, Receiver, Benachrichtigungen |
| [`domain` (Teil 1)](#pkg-domain-1) | Durchgänge, Laufbahnen, Suche, Filter, Auswahl für den Sucher |
| [`domain` (Teil 2)](#pkg-domain-2) | Machbarkeit, Jahresplanung, Nachtbewertung, Bortle, Merkliste, Motive, Fotoguide |
| [`ui/nav`](#pkg-ui-nav) | Routen, `NavHost`, untere Leiste |
| [`ui/theme`](#pkg-ui-theme) | Farben, Formen, Abstände, Typografie, Objektpalette |
| [`ui/components`](#pkg-ui-components) | Design-Bausteine, Objektzeilen und -symbole, Bildgeometrie des Suchers |
| [`ui/capture`](#pkg-ui-capture) | Sucher: Kamerabild, Overlay, Fenster einzeichnen |
| [`ui/calibration`](#pkg-ui-calibration) | Kalibrierbildschirm |
| [`ui/windows`](#pkg-ui-windows) | Fensterliste, Fensterdetail mit Durchgängen, Diagramme, Infoblatt |
| [`ui/search`](#pkg-ui-search) | Katalogsuche, Filter, Objektblatt |
| [`ui/planning`](#pkg-ui-planning) | Kalender, Jahresplanung, Termin- und Merklisten-Sheets |
| [`ui/hub`](#pkg-ui-hub) | Motive der Jahreszeit |
| [`ui/guide`](#pkg-ui-guide) | Fotoguide |
| [`ui/weather`](#pkg-ui-weather) | Wetteransicht, Nachtdiagramm, Mondphase |
| [Modul `baselineprofile`](#pkg-baselineprofile) | Startprofil und Leistungsmessungen |
| [Ressourcen und R8](#pkg-resources) | `res/`, Backup-Regeln, Keep-Regeln |

`domain` ist ein einziges, flaches Paket mit 22 Dateien; der Übersicht halber ist es hier in zwei Abschnitte
geteilt.

<a id="pkg-root"></a>

### Wurzelpaket `com.starwindow.app` — Einstieg, `AppContainer`, Manifest

Hier beginnt die App: die `Application`-Klasse mit dem handgeschriebenen Service-Locator `AppContainer`
und die einzige `Activity`. Beide sind klein; wichtig ist vor allem, was sie festlegen.

**Android-Abhängigkeiten:** ja. **Benutzt:** alle Repositories und Tracker aus `core/sensors`, `data/*`,
`domain`. **Wird benutzt von:** jedem ViewModel (über `factory(container)`), `ui/nav/StarWindowNavHost`,
einigen Screens direkt (`LocalContext.current.appContainer`), `data/planning/PlannedPath`.

#### [`StarWindowApplication.kt`](app/src/main/java/com/starwindow/app/StarWindowApplication.kt)

| Element | Art | Aufgabe |
|---|---|---|
| `AppContainer(context)` | class | Erzeugt alle langlebigen Objekte als `val`s (Tabelle in [Kapitel 3.3](#33-appcontainer-wer-was-erzeugt)) |
| `StarWindowApplication` | class (`Application`) | Baut in `onCreate()` den `AppContainer`; Feld `container` (`lateinit`, privater Setter) |
| `Context.appContainer` | Erweiterungs-`val` | `(applicationContext as StarWindowApplication).container`, von überall mit einem `Context` erreichbar |

**Funktionsweise:** Alle Felder werden beim Erzeugen des Containers in Deklarationsreihenfolge gebaut, also
in `Application.onCreate()` auf dem Main-Thread. Dabei lesen `SettingsStore` und `TrackingStore` ihre
SharedPreferences synchron. Teuer ist das nicht, weil die eigentlichen Daten erst später geladen werden:
der Katalog beim ersten `catalogRepository.objects()`, die JSON-Dateien beim ersten `load()` eines
Repositories. `forecastCache` bekommt die Datei `filesDir/forecast_cache.json`, `weatherRepository` diesen
Cache als Plattenspeicher, `catalogRepository` die zwei Asset-Quellen (Sterne, Deep Sky).

**Ändern/Fallstricke:**
* Es gibt **mehr als eine Instanz** mancher Klassen: Die Receiver bauen sich eigene (`NightWeatherSource.forContext`
  mit eigenem `WeatherRepository`, `WatchCheckReceiver` mit eigenem `WatchlistRepository`, `PlanReminderSync`
  mit eigenem `PlanRepository`). Wer Zustand in einem Repository hält, muss damit rechnen
  ([Kapitel 9](#9-auffälligkeiten-und-bekannte-baustellen)).
* Weil der Container auch für jeden Receiver-Start komplett gebaut wird, schlägt DEV_PLAN §11.3 P6 `by lazy` vor.
* Einige Screens holen sich Teile am ViewModel vorbei direkt (`skyImageLoader` in `WindowDetailScreen`,
  `ObjectDetailScreen`, `StargazingHubScreen`, `PhotoGuideScreen`; `favoritesRepository` in `PhotoGuideScreen`).

#### [`MainActivity.kt`](app/src/main/java/com/starwindow/app/MainActivity.kt)

Die einzige Activity. `onCreate` ruft `enableEdgeToEdge()` (vor `super.onCreate`), setzt
`FLAG_KEEP_SCREEN_ON` (der Bildschirm soll beim Ausrichten nicht ausgehen) und den Compose-Inhalt
`StarWindowTheme { Surface(color = StarWindowColors.Night) { StarWindowNavHost() } }`. Intents werden nicht
ausgewertet: Benachrichtigungen öffnen die App ohne Ziel, es gibt keine Deep Links.

Im Manifest steht `configChanges="orientation|screenSize|screenLayout|keyboardHidden"`. Die Activity wird
beim Drehen also **nicht** neu erzeugt. Deshalb lesen `rememberDisplayRotationDegrees`,
`PreviewStreamInfo.rotationDegreesFor` und `OrientationTracker.remapForDisplay` die Displaydrehung laufend
neu. Wer das ändert, muss diese drei Stellen prüfen.

#### [`AndroidManifest.xml`](app/src/main/AndroidManifest.xml)

| Eintrag | Wert | Hinweis |
|---|---|---|
| Berechtigungen | `CAMERA`, `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED` | Zur Laufzeit erfragt: Kamera und Standort in `CaptureScreen`, Benachrichtigungen in `CalendarScreen` und `ObjectDetailScreen`. `ACCESS_NETWORK_STATE` wird im Code nicht abgefragt. |
| Pflicht-Features | `camera.any`, `sensor.compass`, `sensor.accelerometer` | Geräte ohne Kompass werden im Store ausgefiltert; `location.gps` ist optional |
| `MainActivity` | Launcher, `screenOrientation="fullUser"`, `configChanges` s. o. | |
| `.data.planning.ReminderReceiver` | `exported="false"` | Terminerinnerungen (Action `com.starwindow.app.REMIND`) |
| `.data.planning.WatchCheckReceiver` | `exported="false"` | Tägliche Merklisten-Prüfung (Action `com.starwindow.app.CHECK_WATCHLIST`) |
| `.data.planning.BootReceiver` | `exported="true"`, `BOOT_COMPLETED` | Setzt nach einem Neustart alle Alarme neu; die Klasse steht in `ReminderReceiver.kt` |
| Backup | `fullBackupContent`, `dataExtractionRules` | Gesichert werden nur `sky_windows.json` und die Einstellungen ([Kapitel 6.1](#61-was-wo-gespeichert-wird)) |

<a id="pkg-core-astro"></a>

### `core/astro` — Zeit, Koordinaten, Präzession, Sonne/Mond, Dämmerung

Das mathematische Fundament der App: Zeit (Julianisches Datum, Sternzeit), die Umrechnung Horizont ↔ Äquator, Refraktion, die Präzession von J2000 auf das Datum, eine eigene 3×3-Rotationsklasse sowie Ephemeriden für Sonne und Mond und die Dämmerungs-/Auf-/Untergangszeiten einer Nacht. Fast jede Fachberechnung (Durchgangssuche, Sucher-Overlay, Kalibrierung, Wetter/Nachtbewertung) geht über diese Klassen. Wichtigste Einstiegspunkte: `CoordinateTransforms.apparentHorizontalAtLst` (wo steht ein Objekt sichtbar am Himmel), `Precession.forEpoch(...).toDate(...)` (Katalogposition → Datum), `SolarEphemeris.at` / `LunarEphemeris.at` und `Twilight.nightOf`.
Alle Winkel werden in **Grad** übergeben und zurückgegeben (Parameternamen enden auf `Deg`); intern wird nur für die Trigonometrie in Radiant gewandelt. Rektaszension ist ebenfalls in Grad (`raDeg`, 1 h = 15°), Stunden gibt es nur über `Equatorial.raHours` und in der Formatierung. Zeit ist immer UTC in Millisekunden seit 1970 (`epochMillis`).

**Android-Abhängigkeiten:** nein (nur `kotlin.math`, `java.time` in `Twilight.kt`, `kotlinx.serialization`-Annotationen in `Coordinates.kt`). **Benutzt:** nichts außerhalb des Pakets. **Wird benutzt von:** `core/geometry`, `core/calibration` (`Rotation3`), `core/sensors` (`DeviceAttitude`, `OrientationTracker`, `AttitudeFusion`), `core/camera/TargetIndicator`, `data/catalog` (`SkyObject`, `Constellation`, `EphemerisBody`), praktisch allen Klassen in `domain/` (`TransitCalculator`, `ConstellationTransitCalculator`, `ObservationPlanner`, `TonightBoard`, `SkyTrack`, `ObjectSearch`, `AstroWeather`, `DarkSpans`, `NightOutlook`, `Feasibility` …) und vielen ViewModels/Screens in `ui/`.

#### [`AstroTime.kt`](app/src/main/java/com/starwindow/app/core/astro/AstroTime.kt)

Zeitumrechnungen: Unix-Millisekunden ↔ Julianisches Datum, Julianische Jahrhunderte, mittlere Sternzeit, Stundenwinkel. UT1 wird mit UTC gleichgesetzt (Fehler ≤ ~0,9 s ≈ 0,004° Himmelsdrehung).

| Element | Art | Aufgabe |
|---|---|---|
| `AstroTime` | object | Sammlung der Zeitfunktionen |
| `J2000_JD` | const | `2451545.0` = 2000-01-01 12:00 TT (hier UTC) |
| `julianDate(epochMillis)` / `epochMillis(julianDate)` | fun | JD = ms / 86 400 000 + 2440587.5 bzw. Umkehrung (schneidet mit `toLong()` ab) |
| `julianCenturies(jd)` | fun | T = (JD − 2451545) / 36525 — Eingangsgröße aller Reihen (Präzession, Sonne, Mond, Ekliptikschiefe) |
| `gmstDeg(epochMillis)` | fun | Greenwich Mean Sidereal Time in Grad, [0, 360) |
| `lstDeg(epochMillis, longitudeDeg)` | fun | Lokale Sternzeit = GMST + geografische Länge (Ost positiv) |
| `hourAngleDeg(lstDeg, raDeg)` | fun | H = LST − RA, gefaltet auf (−180, 180]; negativ = Objekt steigt noch |
| `SECONDS_PER_DEGREE_OF_HOUR_ANGLE` | const | 86 400 / 360.98564736629 ≈ 239,3 s — Dauer eines Grads Himmelsdrehung |

**Funktionsweise:** `gmstDeg` benutzt die IAU-1982-Formel (wie Meeus Gl. 12.4): `280.46061837 + 360.98564736629·d + 0.000387933·T² − T³/38 710 000` mit d = Tage seit J2000. Es ist die *mittlere* Sternzeit (keine Nutation/Gleichung der Äquinoktien) – passend dazu wird auch bei der Präzession nur auf das mittlere Äquinoktium gerechnet.

**Zusammenspiel:** `CoordinateTransforms` holt sich daraus die LST. `domain/TransitCalculator` und `ConstellationTransitCalculator` rufen `lstDeg` für jeden Abtastpunkt auf (die Präzession dagegen nur einmal pro Suche); `ObservationPlanner` übersetzt mit `SECONDS_PER_DEGREE_OF_HOUR_ANGLE` Stundenwinkel in Wanduhrzeit.

**Ändern/Fallstricke:** `AstroMathTest` sichert JD bei J2000, GMST bei J2000, Sterntaglänge (~4 min kürzer) und LST-Längenabhängigkeit. Längen sind **ostpositiv** – westliche Längen müssen negativ übergeben werden.

#### [`Coordinates.kt`](app/src/main/java/com/starwindow/app/core/astro/Coordinates.kt)

Die Grund-Datentypen aller Winkelrechnungen: Horizontal- und Äquatorkoordinaten, Beobachterstandort, ein 3D-Vektor und Winkel-Helfer.

| Element | Art | Aufgabe |
|---|---|---|
| `Horizontal(azimuthDeg, altitudeDeg)` | data class, `@Serializable` | Richtung am lokalen Himmel; Azimut 0° = geografisch Nord, 90° = Ost; Höhe 0° = Horizont |
| `Horizontal.toVector()` / `fromVector(v)` | fun | Einheitsvektor im ENU-System: `(cos h·sin A, cos h·cos A, sin h)`; Rückweg über `atan2(x, y)` und `asin(z)` |
| `Horizontal.normalized()` | fun | Azimut auf [0, 360), Höhe auf [−90, 90] geklemmt |
| `Equatorial(raDeg, decDeg)` | data class, `@Serializable` | Rektaszension/Deklination in Grad; `raHours` = RA/15 |
| `Equatorial.toVector()` / `fromVector(v)` | fun | Vektor im Äquatorsystem (x → Frühlingspunkt, z → Himmelsnordpol); wird für die Präzession als Rotation gebraucht |
| `ObserverLocation` | data class, `@Serializable` | Breite/Länge (Ost positiv) in Grad, `elevationM`, `accuracyM`, `manual`; `UNKNOWN` = Greenwich als Fallback |
| `ObserverLocation.distanceKmTo(other)` | fun | Großkreisabstand über Einheitsvektoren und `atan2(‖a×b‖, a·b)`, `KM_PER_DEGREE = 111.195` |
| `Vec3` | data class | `length`, `normalized()` (Nullvektor → `(0,0,1)`), `dot`, `cross`, `+`, `−`, `* Skalar` |
| `Angles` | object | `normalizeDeg` → [0, 360), `wrapDeg180` → (−180, 180], `deltaDeg` (kleinste Azimutdifferenz), `formatDeg`, `formatRa` („12h 34.5m"), `formatDec` („+41° 16.0'") |

**Funktionsweise:** Alle sphärischen Rechnungen der App laufen über Vektoren statt über Winkelpaare, um Probleme an der 0°/360°-Naht und am Zenit/Pol zu vermeiden. `Horizontal.toVector()` setzt die ENU-Konvention aus DEV_PLAN §2 um (x = Ost, y = Nord, z = oben).

**Zusammenspiel:** `Horizontal`/`Equatorial`/`ObserverLocation` stecken in nahezu jeder Datenklasse (z. B. `SkyWindow`, `SkyObject`, Kalibrierung, Planung) und werden mit kotlinx.serialization in die JSON-Dateien geschrieben. `distanceKmTo` nutzt `core/calibration/CalibrationTrust` (gilt die Kompasskalibrierung hier noch?) und `data/weather/PlaceLookup`.

**Ändern/Fallstricke:** Feldnamen der `@Serializable`-Klassen sind Teil des Speicherformats (`sky_windows.json` u. a.) – Umbenennen bricht alte Dateien (`SerializationTest`). Der KDoc von `Equatorial` behauptet noch, J2000 und „of date" würden gleich behandelt; seit `Precession` existiert, stimmt das nicht mehr – welcher Rahmen gemeint ist, hängt vom Aufrufer ab (Katalog = J2000, Ephemeriden/Transformationen = Datum). `elevationM` wird in `core/astro` nicht verwendet (entgegen dem KDoc keine Refraktions-/Kimmtiefenkorrektur); genutzt wird sie u. a. in `OrientationTracker` und den Wetterklassen. `distanceKmTo` ist in `WindowQualityTest` abgesichert (Städteabstand, 1° ≈ 111 km, Datumsgrenze).

#### [`CoordinateTransforms.kt`](app/src/main/java/com/starwindow/app/core/astro/CoordinateTransforms.kt)

Die Umrechnung Horizont ↔ Äquator, die Refraktion und Kulminationshöhen. Hier sind die Vorzeichenkonventionen (Azimut ab Nord über Ost, H = LST − RA) in Formeln gegossen.

| Element | Art | Aufgabe |
|---|---|---|
| `horizontalToEquatorial(h, location, epochMillis)` | fun | Azimut/Höhe → RA/Dec **zum Äquinoktium des Datums** |
| `horizontalToEquatorialJ2000(...)` | fun | Dasselbe, anschließend mit `Precession.forEpoch(...).toJ2000` nach J2000 – Form zum Speichern/Anzeigen |
| `equatorialToHorizontal(eq, location, epochMillis)` | fun | RA/Dec (Datum!) → Azimut/Höhe, geometrisch |
| `equatorialToHorizontalAtLst(eq, latitudeDeg, lstDeg)` | fun | Dasselbe mit fertiger LST – billig für Zeitschleifen |
| `refractionDeg(trueAltitudeDeg)` | fun | Refraktion nach Bennett in Grad (zu addieren) |
| `apparentHorizontalAtLst(...)` | fun | geometrische Position + Refraktion = scheinbare Position |
| `maxAltitudeDeg(dec, lat)` / `minAltitudeDeg(dec, lat)` | fun | obere Kulmination `90 − abs(φ − δ)`, untere `abs(φ + δ) − 90` |

**Funktionsweise:**
- Äquator → Horizont: `sin h = sin δ sin φ + cos δ cos H cos φ`, Azimut `A = atan2(−cos δ sin H, sin δ cos φ − cos δ cos H sin φ)`; diese atan2-Form liefert direkt Azimut ab Nord über Ost. Horizont → Äquator ist die symmetrische Formel, H ergibt sich aus atan2, RA = LST − H.
- `refractionDeg`: Bennett, `R[′] = 1 / tan(h + 7.31/(h + 4.4))`, Eingabe unten bei −0,9° geklemmt, Ergebnis auf [0°, 0,6°] geklemmt (am Horizont ≈ 0,575°, am Zenit ≈ 0). Keine Druck-/Temperaturkorrektur.
- `asin`-Argumente werden auf [−1, 1] geklemmt, um NaN durch Rundung zu vermeiden.

**Zusammenspiel:** Hauptaufrufer der Variante mit Refraktion ist der Durchgangs-Scan (`TransitCalculator`, `ConstellationTransitCalculator`), außerdem `SkyOverlay`, `CaptureViewModel`, `CalibrationViewModel`, `ObservationPlanner`, `SkyTrack`, `TonightBoard`, `ObjectSearch`. `SolarEphemeris.altitudeDeg` und `LunarEphemeris.topocentricAltitudeDeg` benutzen die geometrische Variante. `SkyWindow.centerEquatorialAtCapture` benutzt `horizontalToEquatorialJ2000`.

**Ändern/Fallstricke:** Die Eingabe von `equatorialToHorizontal*` muss bereits auf das Datum präzessiert sein (`Precession.toDate` bzw. `SkyObject.positionAt`); Sonne/Mond kommen schon „of date". Refraktion **nur einmal** anwenden – nicht zusätzlich in Aufrufern. Der KDoc von `maxAltitudeDeg` verspricht −90 für nie aufgehende Objekte; tatsächlich liefert die Formel einfach einen negativen Wert. Tests: `AstroMathTest` (Zenit ↔ Deklination, Pol im Norden auf Breitenhöhe, Ostpunkt auf dem Äquator, Round-Trip, Kulminationshöhe, Refraktion am Horizont/Zenit), `PrecessionTest` („Suche wendet die Präzession wirklich an").

#### [`Precession.kt`](app/src/main/java/com/starwindow/app/core/astro/Precession.kt)

Präzession nach **IAU 2006 (P03, Capitaine et al. 2003)**: bringt J2000-Katalogpositionen auf das mittlere Äquinoktium des Datums und zurück. Ohne sie läge 2025 jede Katalogposition ~0,37° daneben – und die Sternbild-Kalibrierung würde diesen Fehler in die Kompasskorrektur einbauen.

| Element | Art | Aufgabe |
|---|---|---|
| `Precession` | class (privater Konstruktor) | hält `julianCenturies` und die `Rotation3` J2000 → Datum |
| `forEpoch(epochMillis)` / `forJulianCenturies(t)` | fun (companion) | Fabriken; `t == 0.0` liefert `NONE` |
| `NONE` | val | Identität (keine Präzession) |
| `toDate(j2000)` / `toJ2000(ofDate)` | fun | Rotation bzw. Umkehrrotation über `Equatorial.toVector()` |
| `angleDeg`, `yearsSinceJ2000` | val | Gesamtdrehwinkel, Jahre seit J2000 (Diagnose/Tests) |
| `epochMillis` | val | rechnet den Zeitpunkt aus `julianCenturies` zurück – gebraucht von Sonne/Mond |

**Funktionsweise:** Die drei Winkel ζ_A, z_A, θ_A werden als Polynome 5. Grades in T (Bogensekunden) berechnet und zu `Rz(z_A) · Ry(−θ_A) · Rz(ζ_A)` zusammengesetzt (über `Rotation3.fromRotationVector`, rechts zuerst angewandt). Die Matrix wirkt auf Richtungsvektoren, die Rückrichtung ist die transponierte Matrix. Bewusst weggelassen: Nutation (≤ 9″), jährliche Aberration (≤ 20,5″), Eigenbewegung – zusammen < 0,01°. Refraktion kommt getrennt in `CoordinateTransforms`.

**Zusammenspiel:** `SkyObject.positionAt(precession)` (data/catalog) ist der zentrale Konsument: für Katalogobjekte `toDate(equatorialJ2000)`, für Sonne/Mond (`EphemerisBody`) wird stattdessen `precession.epochMillis` benutzt, um die Ephemeride zu diesem Zeitpunkt zu rechnen. Die Suchen bauen die Präzession **einmal pro Suche** (`Precession.forEpoch(fromMillis)` in `TransitCalculator`), nicht pro Abtastpunkt.

**Ändern/Fallstricke:** Wer `Precession.NONE` an ein Sonne/Mond-`SkyObject` übergibt, bekommt deren Position zum Zeitpunkt J2000 (weil `epochMillis` dann J2000 ist). `PrecessionTest` sichert: Identität bei J2000, Winkeltreue, Rate ≈ 50″/Jahr, Ekliptikpol bleibt fast fest, ekliptikale Breite bleibt, ~1/3° bis Mitte der 2020er, Round-Trip Datum ↔ J2000, Pol wandert Richtung Polaris, Vektor-Round-Trip.

#### [`Rotation.kt`](app/src/main/java/com/starwindow/app/core/astro/Rotation.kt)

`Rotation3`: eigene 3×3-Rotationsmatrix in `Double` (zeilenweise gespeichert), getrennt von den `FloatArray`-Sensormatrizen von Android. Eine Quaternion-Klasse gibt es in diesem Paket nicht.

| Element | Art | Aufgabe |
|---|---|---|
| `Rotation3(m: DoubleArray)` | data class | 9 Komponenten, `get(row, col)`; `equals`/`hashCode` über Array-Inhalt |
| `apply(v)` / `inverseApply(v)` / `inverse()` | fun | R·v, Rᵀ·v, transponierte Matrix |
| `times(other)` | operator | Verkettung: `(a * b)·v == a(b(v))` |
| `angleDeg()` / `isIdentity` | fun / val | Drehwinkel aus der Spur `acos((Sp − 1)/2)`; Identität bei < 1e-9° |
| `IDENTITY` | val | Einheitsmatrix |
| `fromRotationVector(omega)` | fun | Rodrigues-Formel: Richtung = Achse, Länge = Winkel in **Radiant** |
| `aboutZenith(deg)` | fun | reine Kursdrehung um die z-Achse: jede Richtung behält ihre Höhe und gewinnt `deg` Azimut |
| `between(from, to)` | fun | kürzeste Drehung von einer Richtung auf eine andere |

**Funktionsweise:** `aboutZenith` ist so gewählt, dass im ENU-System (Azimut = atan2(Ost, Nord)) ein positiver Winkel den Azimut **erhöht** (`[c, s, 0; −s, c, 0; 0, 0, 1]`). `between` behandelt den antiparallelen Fall mit einer 180°-Drehung um eine beliebige Senkrechte.

**Zusammenspiel:** `core/calibration` (`AttitudeFit`, `Calibration`, `FieldOfViewSolver`) und `core/sensors` (`DeviceAttitude` für die Missweisung über `aboutZenith`, `OrientationTracker`) sowie `Precession`.

**Ändern/Fallstricke:** Die Vorzeichen von `aboutZenith` sind in `CalibrationTest` (Klasse `Rotation3Test`) abgesichert: +17° Azimut, Rodrigues-Round-Trip, Verkettung zweier Zenitdrehungen, Winkel der Identität. Wer die Speicherordnung (zeilenweise) ändert, muss `apply`, `inverseApply`, `times` gemeinsam anpassen.

#### [`Ephemeris.kt`](app/src/main/java/com/starwindow/app/core/astro/Ephemeris.kt)

Sonne und Mond nach abgekürzten Reihen aus Meeus („Astronomical Algorithms", Kap. 25 und 47), plus Mondbeleuchtung und Ekliptik ↔ Äquator. Ergebnisse sind `Equatorial` **zum Äquinoktium des Datums**, also direkt für `equatorialToHorizontal` geeignet (nicht noch einmal präzessieren!).

| Element | Art | Aufgabe |
|---|---|---|
| `SunPosition` | data class | `equatorial`, `apparentLongitudeDeg`, `distanceKm` |
| `MoonPosition` | data class | geozentrisch: `equatorial`, ekliptikale Länge/Breite, `distanceKm`; `horizontalParallaxDeg = asin(6378.14 km / Abstand)` |
| `MoonIllumination` | data class | `fraction` (0…1), `phaseAngleDeg`, `elongationDeg`, `waxing`, `percent`, `phaseName` (deutscher Klartext) |
| `SolarEphemeris.at(epochMillis)` | fun | scheinbarer Sonnenstand; `AU_KM = 149 597 870.7` |
| `SolarEphemeris.altitudeDeg(ms, location)` | fun | geometrische Sonnenhöhe (ohne Refraktion) |
| `LunarEphemeris.at(epochMillis)` | fun | geozentrischer Mondstand |
| `LunarEphemeris.topocentricAltitudeDeg(moon, location, ms)` | fun | Mondhöhe mit Parallaxe |
| `LunarEphemeris.illumination(sun, moon)` / `illuminationAt(ms)` | fun | Beleuchtung/Phase |
| `Obliquity.meanDeg(T)` / `eclipticToEquatorial(λ, β, ε)` | fun | mittlere Ekliptikschiefe (IAU 1980) und Frame-Wechsel |

**Funktionsweise:**
- **Sonne** (Meeus Kap. 25, niedrige Genauigkeit, ≈ 0,01°): mittlere Länge L0 und Anomalie M, Mittelpunktsgleichung C (drei Glieder), wahre Länge, Radius aus Exzentrizität. Scheinbare Länge = wahre Länge − 0,00569° (Aberration) − 0,00478°·sin Ω (größter Nutationsterm); Schiefe = mittlere Schiefe + 0,00256°·cos Ω. Breite = 0.
- **Mond** (Meeus Kap. 47, ≈ 0,02° in Länge, ≈ 150 km im Abstand): Fundamentalargumente L′, D, M, M′, F; jeweils die **30 größten Glieder** der Tabellen 47.A (Länge Σl und Abstand Σr) und 47.B (Breite Σb), Glieder mit M im Argument werden mit E bzw. E² skaliert (`eccentricityFactor`); Zusatzglieder A1 (Venus), A2 (Jupiter), A3 (Abplattung). Koeffizienten in 10⁻⁶ Grad bzw. Metern. Umrechnung mit der **mittleren** Schiefe (ohne Nutation).
- **Parallaxe:** `topocentricAltitudeDeg = h_geo − π·cos(h_geo)` – Näherung, die nur die Höhe korrigiert (nicht den Azimut, keine Abplattung). Die `equatorial`-Position des Mondes selbst bleibt geozentrisch.
- **Beleuchtung:** Elongation ψ aus RA/Dec beider Körper; Phasenwinkel `i = atan2(R·sin ψ, Δ − R·cos ψ)` (Meeus 48.3); `fraction = (1 + cos i)/2`; zunehmend, wenn die ekliptikale Länge des Mondes weniger als 180° östlich der Sonne liegt. `phaseName`-Grenzen: < 0,02 Neumond, < 0,30 Sichel, < 0,60 Viertel, < 0,96 zu-/abnehmend, sonst Vollmond.

**Zusammenspiel:** `Twilight` (Auf-/Untergänge), `data/catalog/EphemerisBody` (Sonne/Mond als „bewegte" Katalogobjekte für die Durchgangssuche), `domain/AstroWeather`, `DarkSpans`, `ObservationPlanner`, `Feasibility`, `TransitCalculator`, `ui/guide/PhotoGuideViewModel`, `ui/weather/MoonPhaseDisc`.

**Ändern/Fallstricke:** Für mehr Genauigkeit beim Mond die Tabellen `LONGITUDE_TERMS`/`LATITUDE_TERMS` um die restlichen Meeus-Glieder ergänzen (Spalten: D, M, M′, F, sin-Koeffizient, cos-Koeffizient). Wo Mondpositionen ohne `topocentricAltitudeDeg` in Horizontkoordinaten umgerechnet werden (z. B. über `EphemerisBody.positionAt`), fehlt die Parallaxe von bis zu ~1°. Tests: `EphemerisTest` (Meeus-Rechenbeispiele für Sonne und Mond, Deklination = Schiefe zur Sonnenwende, RA = 0 zur Tagundnachtgleiche, Abstandsgrenzen, Beleuchtung bei Neu-/Vollmond, zu-/abnehmend, Parallaxe), `EphemerisTransitTest` (Sonne/Mond in der Durchgangssuche).

#### [`Twilight.kt`](app/src/main/java/com/starwindow/app/core/astro/Twilight.kt)

Dämmerungsphasen und die Zeiten einer Nacht (Sonnenunter-/-aufgang, Dämmerungsgrenzen, Mondauf-/-untergang), gesucht durch Abtasten und Halbieren.

| Element | Art | Aufgabe |
|---|---|---|
| `TwilightPhase` | enum | `DAY`, `CIVIL`, `NAUTICAL`, `ASTRONOMICAL`, `ASTRONOMICAL_DARKNESS` mit deutschem `label` und Obergrenze `sunAltitudeCeilingDeg` |
| `TwilightPhase.forSunAltitude(deg)` | fun | Phase zur Sonnenhöhe: > −0,833 Tag, > −6 bürgerlich, > −12 nautisch, > −18 astronomisch, sonst dunkel |
| `NightTimes` | data class | alle Zeitpunkte als `Long?` (null = findet nicht statt), `lowestSunAltitudeDeg`; abgeleitet `darkFromMillis`, `darkToMillis`, `darkDurationMillis`, `isDark(ms)` |
| `Twilight.HORIZON_DEG` | const | `−0.833` = Horizontrefraktion (~34′) + Sonnenradius (~16′) |
| `Twilight.nightOf(date, location, zone)` | fun | Nacht = **Mittag bis Mittag** des Folgetags in der Zeitzone **des Ortes** |
| `Twilight.between(from, to, location)` | fun | dieselbe Rechnung für ein beliebiges Fenster |

**Funktionsweise:**
1. Sonnenhöhe (`SolarEphemeris.altitudeDeg`, geometrisch) und topozentrische Mondhöhe werden im Raster `SAMPLE_STEP_MILLIS = 5 min` über das Fenster abgetastet (plus Endpunkt).
2. `firstCrossing` sucht für jede Schwelle den **ersten** Vorzeichenwechsel in der gewünschten Richtung (fallend = Untergang/Abenddämmerung, steigend = Aufgang/Morgendämmerung).
3. `refine` halbiert das 5-min-Intervall `REFINE_ITERATIONS = 14`-mal → Auflösung ≈ 18 ms.
4. Schwellen: Sonnenunter-/aufgang und Mondauf-/untergang bei −0,833°, bürgerliche Dämmerung endet bei −6°, nautische bei −12°, astronomische bei −18°. „Dunkel" (`isDark`) heißt: zwischen astronomischer Abend- und Morgendämmerung.
Pro Aufruf ≈ 2 × 289 Abtastungen plus 10 × 14 Halbierungsschritte – rein rechnend, ohne Threading; Aufrufer laufen selbst im Hintergrund.

**Zusammenspiel:** `domain/AstroWeather` (Nachtzeiten, `TwilightPhase` je Stunde, `HORIZON_DEG` in der Helligkeitsbewertung), `NightOutlook`, `DarkSpans`, `ObservationPlanner`, die Wetter-UI (`TwilightBand`, `AstroWeatherChart`, `NightRatingCard`, `WeatherScreen`).

**Ändern/Fallstricke:** Die Schwellenwerte stehen dreifach im Code: in den `TwilightPhase`-Konstanten, in `forSunAltitude` und als Literale in `between` – wer eine Grenze ändert, muss alle drei Stellen anpassen. Für den Mond wird dieselbe −0,833°-Schwelle benutzt (der Mondradius ist ähnlich groß). Weil nur der *erste* Durchgang zählt, kann ein zweiter Mondaufgang im Fenster nicht erscheinen (bewusst). In hohen Breiten bleiben Felder null (Polartag/-nacht, keine astronomische Dunkelheit im Sommer nördlich von ~48,5°). Tests: `EphemerisTest` (Phasen zur Sonnenhöhe, Winternacht Berlin in richtiger Reihenfolge, keine Dunkelheit zur Sommersonnenwende in Berlin, Tromsø im Juni/Dezember, Sonne steht zur gemeldeten Zeit wirklich auf der Schwelle, `isDark`, Mondauf-/-untergang am Horizont), indirekt `ObservationPlannerTest`, `NightOutlookTest`, `AstroWeatherTest`.

#### Typische Änderungen in diesem Bereich
- **Genauere Sternzeit (scheinbar statt mittlerer):** `AstroTime.gmstDeg` um die Gleichung der Äquinoktien erweitern; konsistent dazu wäre Nutation in `Precession` nötig. `AstroMathTest` anpassen (GMST-Konstante bei J2000).
- **Refraktion abhängig von Druck/Temperatur:** `CoordinateTransforms.refractionDeg` um Parameter erweitern (Faktor `P/1010 · 283/(273+T)` nach Meeus/Bennett) und alle Aufrufer von `apparentHorizontalAtLst` (siehe Grep) mit Wetterdaten versorgen; Refraktionstest in `AstroMathTest`.
- **Neue Dämmerungsschwelle / andere Definition von „dunkel":** `TwilightPhase` (Konstante + `forSunAltitude`), `Twilight.between` (neues Feld in `NightTimes`), ggf. `NightTimes.darkFromMillis/darkToMillis`; Konsumenten in `domain/AstroWeather`, `DarkSpans`, `ui/weather/TwilightBand` prüfen; `EphemerisTest` erweitern.
- **Planeten hinzufügen:** neue Ephemeride analog `SolarEphemeris` (Ergebnis `Equatorial` zum Datum) in `Ephemeris.kt`; Anbindung als bewegter Körper in `data/catalog/EphemerisBody` (`positionAt`), damit `SkyObject.positionAt` und die Durchgangssuche sie finden.
- **Mondparallaxe auch im Azimut/in der Durchgangssuche:** topozentrische Korrektur auf Äquatorkoordinaten (Meeus Kap. 40) in `LunarEphemeris` ergänzen und in `EphemerisBody.positionAt` verwenden (braucht dann den Beobachterort).

<a id="pkg-core-geometry"></a>

### `core/geometry` — Fensterformen und Geometrie auf der Kugel

Beschreibt die „Fenster" – Himmelsausschnitte, festgelegt in **Horizontkoordinaten** (geografischer Azimut/Höhe), die stehen bleiben, während der Himmel hindurchzieht – und stellt die sphärische Geometrie bereit, mit der entschieden wird, ob eine Richtung im Fenster liegt. Einstiegspunkte: `SkyWindow` (das gespeicherte Fenster mit Metadaten), die Formen `PolygonWindow`, `CircleWindow`, `AltAzBoxWindow` hinter dem `sealed interface WindowShape`, deren `membershipTest()` (der Hot-Path der Durchgangssuche), sowie `SphericalGeometry` und `TangentPlane`.

**Android-Abhängigkeiten:** nein (`kotlin.math`, `kotlinx.serialization`). **Benutzt:** `core/astro` (`Horizontal`, `Equatorial`, `ObserverLocation`, `Vec3`, `Angles`, `CoordinateTransforms`). **Wird benutzt von:** `data/windows/SkyWindowRepository` (Persistenz), `domain/TransitCalculator`, `ConstellationTransitCalculator`, `WindowAccuracy`, `SeasonalHighlights`, `ui/capture/CaptureViewModel` (Fenster zeichnen), `ui/windows/*` (Liste, Detail, `WindowTrackChart`), `core/calibration/AttitudeFit`, `core/camera/TargetIndicator`, `core/sensors/AttitudeFusion`/`OrientationTracker` (`SphericalGeometry.angleBetweenDeg`), `ui/calibration`, `ui/guide`.

#### [`SkyWindow.kt`](app/src/main/java/com/starwindow/app/core/geometry/SkyWindow.kt)

Die drei Fensterformen, ihre gemeinsame Schnittstelle und die gespeicherte Datenklasse `SkyWindow`.

| Element | Art | Aufgabe |
|---|---|---|
| `WindowShape` | sealed interface, `@Serializable` | `anchors`, `center()`, `contains(point)`, `membershipTest()`, `areaSquareDeg()`, `outline(segments)`, `angularRadiusDeg()` |
| `DEFAULT_OUTLINE_SEGMENTS` | const | `24` – Stützpunkte der Kontur |
| `PolygonWindow(anchors)` | data class, `@SerialName("polygon")` | Freiform, ≥ 3 Ecken (sonst `require`-Fehler), auch konkav |
| `PolygonWindow.isSelfIntersecting` | val | erkennt „Fliege" (Ecken in falscher Reihenfolge getippt) |
| `CircleWindow(centerPoint, radiusDeg)` | data class, `@SerialName("circle")` | Kugelkappe; `fromCenterAndEdge(center, edge)` aus zwei Taps |
| `AltAzBoxWindow(azimuthStartDeg, azimuthSpanDeg, altitudeMinDeg, altitudeMaxDeg)` | data class, `@SerialName("box")` | Azimut/Höhe-Rechteck; Start = Westkante, Span nach Osten; `fromCorners(a, b)` |
| `SkyWindow` | data class, `@Serializable` | `id`, `name`, `shape`, `observer`, `capturedAtMillis` + Messqualität: `magneticDeclinationDeg`, `compassAccuracy`, `calibrationResidualDeg`, `headingHeld`, `headingHeldSeconds`, `cameraFovDeg`, `notes` |
| `SkyWindow.centerHorizontal` / `centerEquatorialAtCapture()` | val / fun | Fenstermitte; deren RA/Dec in **J2000** zum Aufnahmezeitpunkt |
| `slerp(a, b, t)` / `perpendicularBasis(c)` | internal fun | Großkreis-Interpolation; zwei Senkrechte („ungefähr Ost", „ungefähr oben") zu einer Richtung |

**Funktionsweise:**
- **Serialisierung:** `WindowShape` ist polymorph; kotlinx.serialization schreibt den Diskriminator `"type": "polygon" | "circle" | "box"` (Standardname `type`, Json-Konfiguration in `SkyWindowRepository`: `prettyPrint`, `ignoreUnknownKeys`, `encodeDefaults`). Gespeichert werden nur die Konstruktorfelder: beim Polygon die getippten Ecken, beim Kreis Mittelpunkt + Radius, bei der Box die vier Zahlen. `anchors` von Kreis und Box sind abgeleitete Getter und werden nicht serialisiert.
- **`PolygonWindow.membershipTest()`:** baut einmal eine `TangentPlane` um den Schwerpunkt (`SphericalGeometry.centroid`) und projiziert alle Ecken gnomonisch. Liegt eine Ecke auf der abgewandten Halbkugel (≥ 90° vom Zentrum), liefert der Test **immer false**. Sonst gibt er eine Closure zurück, die den Punkt projiziert und den Even-Odd-Strahltest `containsPoint` auf der Ebene macht. Da die gnomonische Projektion Großkreise auf Geraden abbildet, sind die Polygonkanten auf der Kugel Großkreisbögen. `contains()` ruft intern jedes Mal `membershipTest()` neu auf (teuer) – in Schleifen immer `membershipTest()` einmal holen und wiederverwenden.
- **`CircleWindow.contains`:** Winkelabstand zum Mittelpunkt ≤ Radius. **`AltAzBoxWindow.contains`:** `normalizeDeg(az − azimuthStartDeg) ≤ azimuthSpanDeg` und Höhe zwischen min/max – so funktioniert die **0°/360°-Naht** (z. B. Start 350°, Span 20° deckt 350°…10°). `fromCorners` wählt immer den kürzeren Weg im Azimut (`wrapDeg180`), egal in welcher Reihenfolge getippt wurde. Diese Formen nutzen die Default-`membershipTest()` (`{ contains(it) }`).
- **`outline`:** Polygon: je Kante `max(segments / Eckenzahl, 2)` Punkte per `slerp` (Großkreis). Kreis: `max(segments, 8)` Punkte auf dem Rand über `perpendicularBasis`. Box: vier Kanten mit je `max(segments/4, 2)` Punkten entlang konstanter Höhe bzw. konstantem Azimut, danach `normalized()`. Die Liste ist geschlossen gemeint (letzter Punkt verbindet zum ersten), der Startpunkt wird nicht wiederholt.
- **Fläche (`areaSquareDeg`, Quadratgrad):** Polygon = Betrag von `SphericalGeometry.signedAreaSquareDeg`, Kreis = Kappenformel, Box = exakte Bandformel.
- **`angularRadiusDeg`:** größter Winkelabstand Zentrum → Konturpunkt (Kreis: direkt `radiusDeg`). Die Durchgangssuche nutzt ihn für eine Vorabfilterung über die Deklination.
- **`isSelfIntersecting`:** projiziert wie oben und ruft `hasSelfIntersection()`; bei Ecken auf der Rückseite → false (nicht beurteilbar).

**Zusammenspiel:** `CaptureViewModel` baut je nach Zeichenmodus `PolygonWindow(anchors)`, `CircleWindow.fromCenterAndEdge` bzw. `AltAzBoxWindow.fromCorners` und warnt über `isSelfIntersecting`. `SkyWindowRepository` speichert `List<SkyWindow>` als `sky_windows.json`. `TransitCalculator`/`ConstellationTransitCalculator` holen `membershipTest()` einmal pro Suche und teilen die Closure zwischen parallelen Workern (sie ist zustandslos). `WindowDetailScreen`/`WindowListScreen` zeigen `centerEquatorialAtCapture()`. `WindowAccuracy` (domain) wertet die Messqualitätsfelder aus.

**Ändern/Fallstricke:** `@SerialName`-Werte und Feldnamen sind das Dateiformat – nicht umbenennen (sonst landen alte Dateien als `.corrupt`). Neue optionale Felder in `SkyWindow` immer mit Default (Rückwärtskompatibilität; `ignoreUnknownKeys` deckt nur den umgekehrten Fall – neue Datei, alte App – ab). Polygone, die mehr als eine Halbkugel umspannen, funktionieren nicht (Containment immer false). Tests: `GeometryTest` (Kreis enthält Zentrum/Radius aus zwei Taps/Kontur auf dem Rand, Box über die Nullnaht und kürzester Weg, Polygon enthält Schwerpunkt, konkave Kerbe ausgeschlossen, Polygonfläche ≈ Box, `membershipTest` == `contains`), `WindowQualityTest` (Fliege erkannt, Dreieck nie, konkav ≠ Kreuzung, Fliege = falsche Fläche), `SerializationTest` (JSON-Round-Trip aller Formen, Diskriminatorname, Liste wie im Repository), `TransitTest`/`ConstellationAndTrackTest`/`EphemerisTransitTest` (Formen in der Suche).

#### [`SphericalGeometry.kt`](app/src/main/java/com/starwindow/app/core/geometry/SphericalGeometry.kt)

Geometrie-Helfer auf der Kugel, die gnomonische Tangentialebene und die planaren Polygon-Tests.

| Element | Art | Aufgabe |
|---|---|---|
| `SphericalGeometry.separationDeg(a, b)` | fun | Winkelabstand über `atan2(‖a×b‖, a·b)` – auch für winzige Winkel genau; für `Horizontal` und `Vec3` |
| `SphericalGeometry.centroid(points)` | fun | normierte Vektorsumme; bei Summe ≈ 0 der erste Punkt |
| `SphericalGeometry.signedAreaSquareDeg(points)` | fun | vorzeichenbehaftete Polygonfläche (Vorzeichen = Umlaufsinn) |
| `SphericalGeometry.capAreaSquareDeg(r)` | fun | `2π(1 − cos r)` sr in Quadratgrad |
| `SphericalGeometry.altAzBoxAreaSquareDeg(span, min, max)` | fun | exakt: `Δaz[rad] · (sin max − sin min)` sr |
| `SphericalGeometry.angleBetweenDeg(a, b)` | fun | grobe `acos`-Variante (für Sensorcode) |
| `TangentPlane(center)` | class | gnomonische Projektion um eine Richtung; `project(point)` → `PlanarPoint?`, `unproject(point)` |
| `PlanarPoint(x, y)` | data class | Ebenenpunkt in Einheiten tan(Winkel) (≈ Radiant für kleine Winkel); x ≈ Ost, y ≈ oben |
| `List<PlanarPoint>.containsPoint(p)` | Extension-fun | Even-Odd-Strahltest (Strahl nach +x) |
| `List<PlanarPoint>.hasSelfIntersection()` | Extension-fun | prüft alle nicht benachbarten Kantenpaare auf echte Kreuzung |

**Funktionsweise:**
- **`TangentPlane`:** Basis `east = normalize(up × c)`, `north = normalize(c × east)` (am Zenit/Nadir ersatzweise mit `(0,1,0) × c`). `project`: `d = v·c`; bei `d ≤ 1e-6` (Rückseite oder Horizont der Ebene) `null`, sonst `(v·east/d, v·north/d)`. `unproject`: `c + east·x + north·y`, normiert über `Horizontal.fromVector`.
- **Fläche:** Dreiecksfächer ab dem ersten Punkt, jeder Exzess nach Van Oosterom & Strackee: `E = 2·atan2(a·(b×c), 1 + a·b + b·c + c·a)`. Vorzeichenbehaftet, daher auch für konkave Polygone korrekt; bei einer „Fliege" heben sich die Lappen teilweise auf (deshalb die Schleifenerkennung).
- **Schleifenerkennung:** `hasSelfIntersection` vergleicht Kante i mit allen Kanten j ≥ i+2 (außer erster/letzter, die benachbart sind); `segmentsCross` verlangt **strikt** entgegengesetzte Vorzeichen der Kreuzprodukte auf beiden Seiten – Berührungen an Ecken und kollineare Überlappungen zählen nicht. < 4 Punkte → nie. Aufwand O(n²).
- **`containsPoint`:** klassischer Ray-Casting-Test (Kante straddelt die y-Linie des Punktes, Schnitt-x rechts vom Punkt → Zustand umschalten). Punkte genau auf der Kante sind nicht eindeutig zugeordnet.

**Zusammenspiel:** `PolygonWindow` (Containment, Schleife, Fläche, Zentrum), `CircleWindow` (`separationDeg`, `capAreaSquareDeg`), `ui/windows/WindowTrackChart` zeichnet Fenster und Objektbahnen in einer `TangentPlane` um die Fenstermitte. `separationDeg` wird breit benutzt (Kalibrierung, `TargetIndicator`, `SeasonalHighlights`, `PhotoGuideViewModel`, `CalibrationViewModel`); `angleBetweenDeg` von `AttitudeFusion`/`OrientationTracker`.

**Ändern/Fallstricke:** Die gnomonische Projektion ist nur für die Halbkugel um das Zentrum gültig und verzerrt nach außen stark – für Fenster bis einige 10° unkritisch. Wer `TangentPlane`-Achsen ändert, beeinflusst die Darstellung in `WindowTrackChart`. Tests: `GeometryTest` (90° zwischen Orthogonalen, Genauigkeit bei kleinen Winkeln, Oktant = 1/8 Kugel, Halbkugelkappe, kleine Kappe ≈ ebener Kreis, Band über dem Horizont = halber sichtbarer Himmel, Tangentialebenen-Round-Trip, Rückseite projiziert nicht), `WindowQualityTest` (Selbstüberschneidung), `AstroMathTest` (Abstand in beiden Frames gleich).

#### Typische Änderungen in diesem Bereich
- **Neue Fensterform (z. B. Ellipse):** neue `@Serializable data class` mit eindeutigem `@SerialName` in `SkyWindow.kt`, die `WindowShape` implementiert (`anchors`, `center`, `contains`, `areaSquareDeg`, `outline`, ggf. `membershipTest`/`angularRadiusDeg` überschreiben). Dann: Zeichenmodus und Konstruktion in `ui/capture/CaptureViewModel` (`DrawMode`), Darstellung in `ui/windows` prüfen, `GeometryTest` und `SerializationTest` („every window shape survives a JSON round trip") erweitern. Da das Interface `sealed` ist, melden `when`-Ausdrücke über `WindowShape` fehlende Zweige beim Kompilieren.
- **Polygon-Containment beschleunigen oder ändern:** nur `PolygonWindow.membershipTest()` bzw. `containsPoint` anfassen; `GeometryTest` („membership test agrees with contains", konkave Kerbe) muss grün bleiben. Die Closure muss zustandslos bleiben, weil `TransitCalculator` sie parallel nutzt.
- **Fehlerhafte Fenster vor dem Speichern abfangen (z. B. Ecke > 90° vom Zentrum):** Prüfung analog `isSelfIntersecting` in `PolygonWindow` ergänzen und in `CaptureViewModel` neben der bestehenden Schleifenwarnung auswerten.
- **Neues Metadatenfeld in `SkyWindow`:** Feld **mit Default-Wert** ergänzen, damit alte `sky_windows.json` lesbar bleiben; Anzeige in `WindowDetailScreen`, Bewertung ggf. in `domain/WindowAccuracy`.

<a id="pkg-core-sensors"></a>

### `core/sensors` — Lage des Telefons, Sensorfusion, Missweisung, Standort

Dieses Paket liefert die zwei Live-Eingänge der App: die **Lage** des Telefons als `Flow<DeviceAttitude>` (`OrientationTracker.attitudes()`) und den **Standort** als `Flow<ObserverLocation>` (`LocationTracker.locations()`). Die Fusionsmathematik liegt getrennt und testbar in `AttitudeFusion`. `DeviceAttitude` ist das Ergebnisobjekt: Rotationsmatrix im Displaysystem, Missweisung, Kalibrierkorrektur und Magnetfeld-Diagnose, dazu die Umrechnungen ins wahre Nord.

**Android-Abhängigkeiten:** `AttitudeFusion` nein. `DeviceAttitude` nur über die Konstanten `SensorManager.SENSOR_STATUS_*`, die beim Kompilieren eingesetzt werden, deshalb laufen die JVM-Tests. `OrientationTracker` ja (`SensorManager`, `GeomagneticField`, `DisplayManager`). `LocationTracker` ja (`LocationManager`, Berechtigungen). **Benutzt:** `core/astro` (`Rotation3`, `Vec3`, `Horizontal`, `ObserverLocation`), `core/calibration` (`Calibration`), Coroutines-`callbackFlow`. **Wird benutzt von:** `AppContainer` (je eine Instanz von `OrientationTracker` und `LocationTracker`), `CaptureViewModel`, `CalibrationViewModel`, `ObjectSearchViewModel`, `ObjectDetailViewModel`, `WeatherViewModel`, `PlanningLocation`, `PlannedPath` (Standort). `core/camera` nutzt `DeviceAttitude`.

#### [`DeviceAttitude.kt`](app/src/main/java/com/starwindow/app/core/sensors/DeviceAttitude.kt)

Unveränderlicher Lagezustand eines Sensorereignisses. Enthält die Grundrechenarten der Kette Display ↔ Welt ↔ wahres Nord.

| Element | Art | Aufgabe |
|---|---|---|
| `AttitudeSource` | enum | `GYRO_WITH_COMPASS_HEADING` („Kreisel + Kompass"), `FUSED_COMPASS`, `GEOMAGNETIC` (ohne Kreisel) |
| `DeviceAttitude` | data class | Felder siehe unten; `equals`/`hashCode` von Hand (wegen `FloatArray`) |
| `displayToWorld(v)` / `worldToDisplay(v)` | fun | `R·v` bzw. `Rᵀ·v` (magnetisches ENU) |
| `toTrueNorth(worldVector)` / `toMagneticVector(horizontal)` | fun | magnetische Welt → wahres, kalibriertes `Horizontal` und zurück |
| `cameraDirection` / `uncalibratedCameraDirection` | val | Richtung von `(0,0,−1)`, mit bzw. ohne `correction` (Missweisung ist in beiden enthalten) |
| `worldFromDisplay` | val (lazy) | Matrix als `Rotation3` in double, für die Kalibrierlöser |
| `rollDeg` | val | Neigung des Horizonts, `atan2(r[6], r[7])` (im App-Code derzeit nur in Tests genutzt) |
| `headingHeld`, `headingDriftDeg` | val | Nord wird nur vom Kreisel getragen bzw. Driftbudget dafür |
| `fieldDeviationMicroTesla`, `hasFieldStrengthDistortion`, `hasFieldDirectionDistortion`, `isMagneticallyDisturbed` | val | Magnetfeld-Diagnose gegen das Modell |
| `needsCompassCalibration` | val | Genauigkeit < `MEDIUM` **und** Hard-Iron ≥ 40 µT → „Acht schwenken" |
| `IDENTITY` | companion | flach auf dem Rücken, Oberkante nach magnetisch Nord |

**Felder:** `rotationMatrix` (FloatArray, 9 Werte, zeilenweise, `v_welt = R·v_display`, Welt = Ost/**magnetisch** Nord/oben). `magneticDeclinationDeg` (Grad, Ost positiv, aus `GeomagneticField`). `accuracy` (`SENSOR_STATUS_*` des Kompasses). `timestampMs` (Wanduhr). `correction` (`Rotation3`, Kalibrierung). `source`. `fieldMicroTesla`/`expectedFieldMicroTesla`, `inclinationDeg`/`expectedInclinationDeg` (gemessene bzw. erwartete Inklination, nach unten positiv), `hardIronMicroTesla`, `headingHeldSeconds`.

**Funktionsweise:** `trueFromMagnetic = correction · Rotation3.aboutZenith(magneticDeclinationDeg)` wird einmal pro Objekt `lazy` gebaut: erst die Missweisung, dann die Kalibrierkorrektur im wahren Weltsystem. `toTrueNorth` wendet die Drehung an und wandelt per `Horizontal.fromVector` um. `toMagneticVector` nutzt `inverseApply`.

**Zusammenspiel:** Erzeugt nur in `OrientationTracker.FusionState.onPrimary`. Verbraucht von `SkyProjection`, `TargetIndicator`, den Kalibrierlösern (`worldFromDisplay`, `uncalibratedCameraDirection`) und der Statusanzeige in `CaptureScreen`/`CalibrationScreen`.

**Ändern/Fallstricke:** Wer ein Feld hinzufügt, muss `equals` **und** `hashCode` von Hand ergänzen. Missweisung und Korrektur sind schon im Objekt enthalten, Verbraucher dürfen sie nicht ein zweites Mal anwenden. Tests: `ProjectionTest.kt` (eben nach Norden, Missweisung verschiebt nur den Azimut, `displayToWorld`/`worldToDisplay` invers, Roll), `CalibrationTest.kt` (Korrektur verschiebt den Azimut, unkalibrierte Richtung ignoriert die Korrektur, behält aber die Missweisung), `MagneticDirectionTest.kt` (Driftbudget, Inklinationstest).

#### [`AttitudeFusion.kt`](app/src/main/java/com/starwindow/app/core/sensors/AttitudeFusion.kt)

Reine Arithmetik der Sensorfusion (`object`): zeitkonstantenbasierte Quaternion-Glättung, Kursversatz-Filter zwischen Kreisel- und Kompassrahmen und Plausibilitätstests für das Magnetfeld.

| Element | Art | Aufgabe |
|---|---|---|
| `alphaFor(dt, tau)` | fun | Gewicht `1 − e^(−dt/τ)`; `dt ≤ 0` → 0, `τ ≈ 0` → 1 |
| `timeConstantFor(rateDegPerSecond)` | fun | linear zwischen `STILL_TIME_CONSTANT_S = 0.30` (≤ 1,5 °/s) und `FAST_TIME_CONSTANT_S = 0.02` (≥ 25 °/s) |
| `angleBetweenDeg(a, b)` | fun | Winkel zwischen zwei Einheitsquaternionen `2·acos(abs(a·b))` |
| `slerpInto(current, target, t)` | fun | Slerp an Ort und Stelle; wählt das nähere Vorzeichen, linear ab `dot > 0.9995`, danach neu normiert |
| `headingOffsetDeg(magneticFrame, gyroFrame)` | fun | Drehung um die Vertikale `D = M·Gᵀ`, Winkel aus der ersten Spalte: `atan2(D[1,0], D[0,0])` |
| `applyHeading(frame, headingDeg, out)` | fun | dreht die Gerät→Welt-Matrix um die Welt-Vertikale (Zeilen x/y mischen, z bleibt) |
| `blendHeadingDeg(current, target, alpha)` | fun | zirkuläre Mischung über den kurzen Weg, Ergebnis in (−180, 180] |
| `isFieldPlausible(measured, expected)` | fun | Toleranz `0,30·expected + 5 µT`; ohne Modellwert immer wahr |
| `inclinationDeg(fieldDevice, deviceToWorld)` | fun | Inklination `atan2(−up, horizontal)` des ins ENU gedrehten Feldvektors; `null` bei Nullfeld |
| `isInclinationPlausible` / `INCLINATION_TOLERANCE_DEG = 12.0` | fun / const | Inklinationstest |
| `heldHeadingDriftDeg(s)` | fun | `s/60 · GYRO_DRIFT_DEG_PER_MINUTE (0.5)`, begrenzt auf `MAX_HELD_DRIFT_DEG = 30.0` |
| `HARD_IRON_NOTABLE_MICRO_TESLA = 40` | const | Schwelle für `needsCompassCalibration` |
| `HEADING_TIME_CONSTANT_S = 10.0` | const | Zeitkonstante des Kursversatz-Filters |
| `HEADING_UPDATE_MAX_RATE_DEG_S = 8.0` | const | über dieser Drehrate wird der Kursversatz nicht nachgeführt |

**Funktionsweise:** Es ist ein Komplementärfilter für genau einen Freiheitsgrad. `GAME_ROTATION_VECTOR` (Kreisel + Beschleunigung) trägt die Lage, `ROTATION_VECTOR` (mit Magnetometer) liefert nur den Kursversatz. Weil die Schwerkraft beide Rahmen in Neigung und Roll festlegt, unterscheiden sie sich nur durch eine Drehung um „oben". Dieser Winkel wird mit τ = 10 s tiefpassgefiltert. Die Glättung der Lage selbst hängt von der Zeitkonstante ab, nicht von der Abtastrate. Sie verhält sich darum bei 30 Hz und bei 200 Hz gleich und wird beim Schwenken fast abgeschaltet. Ausreißer im Kompass bewegen den Kurs dank der langen Zeitkonstante kaum. Grobe Störungen werden über die Feldstärke- und Inklinationstests abgewiesen, das Gating dafür sitzt in `OrientationTracker`.

**Ändern/Fallstricke:** Matrizen sind im Plattformformat: zeilenweise, Gerät→Welt. `GYRO_DRIFT_DEG_PER_MINUTE` ist laut Kommentar ein Platzhalter, bis echte Messungen vorliegen. Tests: `AttitudeFusionTest.kt` (Alpha bei einer Zeitkonstante, ratenunabhängige Glättung, still vs. Schwenk, Monotonie, Quaternionwinkel, Kursversatz exakt rückgewonnen und wieder angewandt, Mischung über die Naht, verrauschter Kompass und Einzelausreißer bewegen den Kurs kaum, Feldstärketest), `MagneticDirectionTest.kt` (Inklination unabhängig von der Haltung, ±90°, magnetischer Äquator, Nullfeld, Eisen, das nur die Richtung verbiegt, Südhalbkugel, Driftbudget).

#### [`OrientationTracker.kt`](app/src/main/java/com/starwindow/app/core/sensors/OrientationTracker.kt)

Verdrahtet die Android-Sensoren mit `AttitudeFusion` und gibt einen `Flow<DeviceAttitude>` aus. Eine Instanz lebt im `AppContainer` und wird von mehreren ViewModels geteilt.

| Element | Art | Aufgabe |
|---|---|---|
| `OrientationTracker(context)` | class | hält `SensorManager`, `DisplayManager` und die `AtomicReference`s für Ort und Kalibrierung |
| `isAvailable` | val | gibt es `ROTATION_VECTOR` oder `GEOMAGNETIC_ROTATION_VECTOR`? (Kompass ist Pflicht, Kreisel nicht) |
| `updateLocation(observer)` / `updateCalibration(value)` | fun | schreiben die `AtomicReference`s; wirken ab dem nächsten Ereignis |
| `attitudes(samplingPeriodUs = SENSOR_DELAY_GAME)` | fun | `callbackFlow` + `conflate()`; schließt sofort, wenn kein Kompass da ist |
| `FusionState` | private inner class | Zustand zwischen Ereignissen: `onField`, `onCompass`, `onPrimary`, `updateHeading`, `fieldLooksLikeEarth`, `refreshLocationDerived`, `refreshCalibration` |
| `quaternionFromEvent` | internal fun (companion) | normiert Ereigniswerte auf genau 4 Komponenten (w ergänzen, wenn es fehlt) |
| `remapForDisplay(input, rotation, output)` | internal fun (companion) | `SensorManager.remapCoordinateSystem` je `Surface.ROTATION_*` |
| `slerpInto` | internal fun (companion) | Weiterleitung an `AttitudeFusion` (für ältere Tests) |
| `compassAccuracyLabel(accuracy)` | top-level fun | „hoch/mittel/niedrig/unzuverlässig" |

**Funktionsweise:**
1. **Sensorwahl** in `attitudes()`: Der Kompass ist `TYPE_ROTATION_VECTOR`, ersatzweise `TYPE_GEOMAGNETIC_ROTATION_VECTOR`. Die Bewegung kommt aus `TYPE_GAME_ROTATION_VECTOR`, falls vorhanden (`fusing = true`), primärer Sensor ist dann die Bewegung. Sonst ist der Kompass der primäre Sensor. Das Magnetometer ist `TYPE_MAGNETIC_FIELD_UNCALIBRATED`, ersatzweise `TYPE_MAGNETIC_FIELD`.
2. **Registrierung:** Der primäre Sensor läuft mit `samplingPeriodUs` (Standard `SENSOR_DELAY_GAME`, ca. 50 Hz). Kompass (nur bei Fusion) und Magnetometer laufen mit `SENSOR_DELAY_UI`. Es gibt **keinen `Handler`**, die Callbacks und die ganze Fusion laufen also auf dem Main-Thread (DEV_PLAN §11.3 P1). `awaitClose` meldet alles ab. `conflate()` verwirft Zwischenwerte, wenn der Sammler zu langsam ist.
3. **`onField`:** Beim unkalibrierten Sensor gilt Feld = `raw − bias` (Werte 0–2 minus 3–5) und `hardIronMicroTesla = |bias|`. Danach wird `fieldMicroTesla` als Betrag berechnet.
4. **`onCompass`:** Quaternion → `compassFrame`. Die Genauigkeit wird aus dem Ereignis übernommen. `onAccuracyChanged` setzt sie für alle Sensoren außer `GAME_ROTATION_VECTOR`.
5. **`onPrimary`:** `dt` aus den Ereigniszeitstempeln, begrenzt auf 0…0,5 s. Drehrate = `angleBetweenDeg(prev, neu)/dt`. Dann folgt Slerp der geglätteten Quaternion mit `alphaFor(dt, timeConstantFor(rate))` und daraus `getRotationMatrixFromVector`. `refreshLocationDerived()` berechnet über `GeomagneticField` (WMM) Missweisung, erwartete Feldstärke (nT → µT) und Inklination neu, aber nur wenn sich die `ObserverLocation`-Instanz geändert hat (Identitätsvergleich). Ohne Ort gilt Missweisung 0. Die Inklination wird mit der Kreiselmatrix berechnet. Bei Fusion folgen `updateHeading(dt)` und `applyHeading`. Bis zum ersten Kompasswert gibt `onPrimary` **nichts** aus. Danach kommen `remapForDisplay` mit der aktuellen Displaydrehung (`DisplayManager.getDisplay(DEFAULT_DISPLAY).rotation`), `refreshCalibration()` (nur bei neuer `Calibration`-Instanz) und `emit(DeviceAttitude(...))`.
6. **`updateHeading`:** Der erste Kompasswert wird ohne Prüfung übernommen. Danach wird der Kursversatz nur nachgeführt, wenn die Genauigkeit ≥ `MEDIUM` ist, das Feld wie das Erdfeld aussieht (Stärke und Inklination) **und** die Drehrate ≤ 8 °/s liegt. Sonst bleibt der alte Versatz, und Nord wird vom Kreisel getragen. `headingHeldSeconds` zählt nur bei Störung oder schlechter Genauigkeit hoch, nicht beim bloßen Schwenken.

**Zusammenspiel:** `CaptureViewModel` und `CalibrationViewModel` sammeln `attitudes()` per `stateIn(WhileSubscribed(5000))` und dünnen für das HUD per `sample(250)` aus. Beide rufen bei jeder Einstellungsänderung `updateCalibration` auf. `updateLocation` ruft nur `CaptureViewModel.applyObserver` auf (manueller Ort hat Vorrang vor GPS).

**Ändern/Fallstricke:** Wer die Listener auf einen `HandlerThread` verlegt (P1), muss nichts an den `AtomicReference`s ändern. Die Callbacks von `FusionState` laufen dann aber auf diesem Thread, und `trySend` ist threadsicher. Die Abtastrate ergibt sich derzeit aus `SENSOR_DELAY_GAME`. Missweisung und Kalibrierung werden nur bei neuer **Instanz** neu berechnet (`===`). Wer die Werte veränderlich macht, umgeht damit den Cache. `remapForDisplay` mit der Displaydrehung ist der einzige Ort, an dem Querformat berücksichtigt wird. Der Verlauf der Fusion ist nicht unit-getestet, nur die Bausteine in `AttitudeFusion`. `AttitudeFusionTest` greift über die `internal`-Funktionen auf Teile zu.

#### [`LocationTracker.kt`](app/src/main/java/com/starwindow/app/core/sensors/LocationTracker.kt)

Standort über den plattformeigenen `LocationManager`, ohne Play Services.

| Element | Art | Aufgabe |
|---|---|---|
| `LocationTracker(context)` | class | verwendet den Application-Context |
| `hasPermission` | val | `ACCESS_FINE_LOCATION` **oder** `ACCESS_COARSE_LOCATION` |
| `lastKnown()` | fun | jüngster `getLastKnownLocation` über alle aktiven Provider (`getProviders(true)`), sonst `null` |
| `locations(minTimeMs = 10_000, minDistanceM = 50f)` | fun | `callbackFlow`: sendet sofort `lastKnown()`, dann Updates von `GPS_PROVIDER` und `NETWORK_PROVIDER` (sofern vorhanden) auf dem Main-Looper; `conflate()` |
| `Location.toObserverLocation()` | private fun | Breite/Länge, Höhe (oder 0), Genauigkeit, `manual = false` |

**Ändern/Fallstricke:** Ohne Berechtigung schließt der Flow sofort, ohne Fehler und ohne Wert. Deshalb muss `CaptureViewModel.startLocationUpdates()` nach dem Erteilen der Berechtigung neu aufgerufen werden. `SecurityException` wird abgefangen und beendet den Flow still. GPS- und Netzwerkwerte kommen gemischt und ungefiltert an, ein neuerer Wert ersetzt den älteren, auch wenn er ungenauer ist. Keine Unit-Tests (Android-API).

#### Typische Änderungen in diesem Bereich
- **Overlay ruckelt beim Schwenken / Sensorik vom Main-Thread nehmen (P1):** In `OrientationTracker.attitudes()` einen `HandlerThread` starten, `registerListener(listener, sensor, period, handler)` verwenden und den Thread in `awaitClose` beenden. Optional `samplingPeriodUs` aus der Bildrate des Displays ableiten (`1_000_000 / refreshRate`).
- **Overlay zittert im Stillstand bzw. hängt beim Schwenken nach:** `STILL_TIME_CONSTANT_S`, `FAST_TIME_CONSTANT_S`, `STILL_RATE_DEG_S`, `FAST_RATE_DEG_S` in `AttitudeFusion`. Danach `AttitudeFusionTest` laufen lassen (Monotonie, still vs. Schwenk).
- **Kompassstörungen zu empfindlich oder zu lax erkannt:** `isFieldPlausible` (Toleranz 30 % + 5 µT), `INCLINATION_TOLERANCE_DEG`, `HEADING_UPDATE_MAX_RATE_DEG_S`, `HEADING_TIME_CONSTANT_S`. Die Warntexte stehen in `CaptureScreen`/`CalibrationScreen`.
- **Neues Diagnosefeld an `DeviceAttitude`:** Feld mit Standardwert, `equals`/`hashCode` ergänzen, in `FusionState` füllen und beim `emit` übergeben.
- **Standortgenauigkeit oder -frequenz ändern:** Parameter von `locations()` an den Aufrufstellen (`CaptureViewModel`, `CalibrationViewModel`, `ObjectSearchViewModel`) oder die Standardwerte. Wer Werte nach Genauigkeit filtern will, macht das in `onLocationChanged`.

<a id="pkg-core-camera"></a>

### `core/camera` — Kameraoptik, Lochkameraprojektion, Nachtbelichtung, Zielpfeil

Dieses Paket verbindet das Kamerabild mit dem Himmel. `CameraIntrinsics` liest aus, welchen Bildwinkel die Linse hat. `SkyProjection` rechnet damit Bildschirmpixel in Azimut/Höhe um und zurück: Lochkameramodell plus Lage aus `core/sensors`. `TargetIndicator` macht daraus einen Zielmarker oder einen Randpfeil. `ExposureSettings` und `NightVisionController` steuern die Belichtung des Suchers bei Nacht.

**Android-Abhängigkeiten:** teilweise. `CameraIntrinsics.fromCharacteristics` und `ExposureCapabilities.fromCharacteristics` lesen `CameraCharacteristics` (Camera2). `NightVisionController` ist ganz Android (CameraX `Camera`, Camera2-Interop). `SkyProjection` und `TargetIndicator` sind reine Mathematik, hängen aber über `DeviceAttitude` an `core/sensors`. **Benutzt:** `core/astro` (`Horizontal`, `Vec3`), `core/geometry` (`SphericalGeometry`), `core/sensors` (`DeviceAttitude`). **Wird benutzt von:** `ui/capture` (`CameraPreview`, `CaptureScreen`, `SkyOverlay`, `CaptureViewModel`, `CaptureDialogs`), `ui/components/SkyViewport.kt`, `ui/calibration`. Die Persistenz läuft über die Einstellungen (`ExposureSettings` ist `@Serializable`).

#### [`CameraIntrinsics.kt`](app/src/main/java/com/starwindow/app/core/camera/CameraIntrinsics.kt)

Beschreibt die Optik der gebundenen Rückkamera, also die Bildwinkel über die volle aktive Sensorfläche. Daraus berechnet die Klasse die Brennweite in Pixeln eines Vorschau-Streams.

| Element | Art | Aufgabe |
|---|---|---|
| `CameraIntrinsics` | data class | `cameraId`, `horizontalFovDeg`/`verticalFovDeg` (lange/kurze Sensorseite, Grad), `focalLengthMm`, `sensorWidthMm`/`sensorHeightMm`, `isEstimate` |
| `focalLengthInStreamPixels(streamWidth, streamHeight)` | fun | Brennweite in Stream-Pixeln (siehe unten) |
| `FALLBACK` | val (companion) | Ersatzwerte für ein Objektiv mit etwa 26 mm Kleinbildäquivalent: 67,06° × 53,13°, 4,3 mm Brennweite, Sensor 5,7 × 4,3 mm, `isEstimate = true` |
| `fromCharacteristics(cameraId, characteristics)` | fun (companion, Android) | liest `LENS_INFO_AVAILABLE_FOCAL_LENGTHS` (erster Eintrag) und `SENSOR_INFO_PHYSICAL_SIZE` aus |

**Funktionsweise:** `fromCharacteristics` skaliert die physische Sensorgröße mit dem Verhältnis `SENSOR_INFO_ACTIVE_ARRAY_SIZE`/`SENSOR_INFO_PIXEL_ARRAY_SIZE` auf die aktive Fläche. Danach gilt `fov = 2·atan(Ausdehnung / (2·f))`. Fehlen Brennweite oder Sensorgröße, liefert die Funktion `FALLBACK` mit der echten `cameraId`. `focalLengthInStreamPixels` nimmt an, dass der Stream ein mittiger Ausschnitt mit dem Seitenverhältnis des Streams ist. Ist der Stream mindestens so breit wie der Sensor (`streamAspect ≥ tan(hFov/2)/tan(vFov/2)`), gilt die volle Breite und die Funktion liefert `(w/2)/tan(hFov/2)`. Andernfalls gilt die volle Höhe und sie liefert `(h/2)/tan(vFov/2)`.

**Zusammenspiel:** `ui/capture/CameraPreview.kt` erzeugt die Intrinsics für die tatsächlich gebundene `cameraId` (über `Camera2CameraInfo`) und gibt sie in `PreviewStreamInfo` weiter. `SkyProjection.focalLengthInViewPixels` ruft `focalLengthInStreamPixels` auf.

**Ändern/Fallstricke:** Die beiden Winkel in `FALLBACK` müssen zu den Millimeterwerten passen, sonst ist jede Pixel-Winkel-Umrechnung verzerrt. Bei Telefonen mit mehreren Linsen zählt nur die tatsächlich gebundene Kamera. Abgesichert durch `CameraIntrinsicsTest` (in `ProjectionTest.kt`): Ein voller Stream behält den horizontalen Bildwinkel, ein breiterer Stream wird oben und unten beschnitten.

#### [`ExposureSettings.kt`](app/src/main/java/com/starwindow/app/core/camera/ExposureSettings.kt)

Enthält die gewünschte Belichtung des Suchers (`ExposureSettings`, persistiert) und die Grenzen des Geräts (`ExposureCapabilities`, die beim Binden gelesen werden).

| Element | Art | Aufgabe |
|---|---|---|
| `ExposureMode` | enum (`@Serializable`) | `AUTO` / `NIGHT`, mit deutschem `label` |
| `ExposureSettings` | data class (`@Serializable`) | `mode`, `exposureTimeNs` (Standard 250 ms), `iso` (1600), `focusAtInfinity` (true), `exposureCompensationSteps` (0) |
| `formatExposureTime()` | fun | „1/4 s" unter 1 s, sonst „2,0 s" (Dezimaltrennzeichen hängt von der Locale ab) |
| `ExposureSettings.AUTO`, `nightDefault(capabilities)` | companion | Automatik bzw. Nachtvorgabe (250 ms / ISO 1600), auf das Gerät begrenzt |
| `ExposureCapabilities` | data class | Manuelle Sensorsteuerung ja/nein, Belichtungs- und ISO-Bereich, `maxFrameDurationNs`, Fokusdistanz setzbar, Bereich und Schrittweite der Belichtungskorrektur |
| `clampExposureTime`, `clampIso`, `clampCompensation`, `frameDurationFor` | fun | begrenzen die Werte auf die Gerätegrenzen |
| `supportsNightMode` / `supportsExposureCompensation` | val | `supportsManualSensor` bzw. ob der Korrekturbereich nicht leer ist |
| `ExposureCapabilities.NONE`, `fromCharacteristics` | companion | alles aus bzw. aus Camera2 gelesen (`REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR`, `SENSOR_INFO_EXPOSURE_TIME_RANGE`, `SENSOR_INFO_SENSITIVITY_RANGE`, `SENSOR_INFO_MAX_FRAME_DURATION`, `LENS_INFO_MINIMUM_FOCUS_DISTANCE`, `CONTROL_AE_COMPENSATION_*`) |

**Ändern/Fallstricke:** `supportsFocusDistance` ist `minFocusDistance > 0`. Ein Fixfokus-Objektiv meldet 0 und lässt sich nicht verstellen. `frameDurationFor` gibt die Belichtungszeit zurück, höchstens aber `maxFrameDurationNs`. Neue Felder in `ExposureSettings` brauchen einen Standardwert, sonst lassen sich alte gespeicherte Einstellungen nicht mehr lesen. `SerializationTest.kt` prüft den Roundtrip und `formatExposureTime` („1/2 s").

#### [`NightVisionController.kt`](app/src/main/java/com/starwindow/app/core/camera/NightVisionController.kt)

Wendet `ExposureSettings` auf die laufende Kamera an. Das geht über `Camera2CameraControl.setCaptureRequestOptions`, also ohne neues Binden und ohne Schwarzbild.

| Element | Art | Aufgabe |
|---|---|---|
| `NightVisionController(camera, capabilities)` | class (`@OptIn(ExperimentalCamera2Interop)`) | hält die CameraX-`Camera` und die Gerätegrenzen |
| `apply(settings)` | fun | begrenzt die Werte, sendet die Capture-Request-Optionen und gibt die tatsächlich angewandten Einstellungen zurück |

**Funktionsweise:** Zuerst begrenzt `clamp` Zeit, ISO und Kompensation. `focusAtInfinity` bleibt nur, wenn das Gerät die Fokusdistanz setzen kann. Bei `NIGHT` mit `supportsManualSensor` setzt `apply` `CONTROL_AE_MODE_OFF`, `SENSOR_EXPOSURE_TIME`, `SENSOR_SENSITIVITY` und `SENSOR_FRAME_DURATION = frameDurationFor(t)`. Sonst setzt es `CONTROL_AE_MODE_ON`. Für den Fokus gilt: entweder `CONTROL_AF_MODE_OFF` mit `LENS_FOCUS_DISTANCE = 0` (Dioptrien, also unendlich) oder `CONTINUOUS_PICTURE`. Die Belichtungskorrektur läuft getrennt über `cameraControl.setExposureCompensationIndex`. Im Nachtmodus ohne manuelle Sensorsteuerung wird sie voll ausgefahren (`exposureCompensationRange.last`), sonst gilt der eingestellte Wert. Fehler fängt `runCatching` ab und schreibt sie nur ins Log (`Log.w`).

**Zusammenspiel:** `CameraPreview` erzeugt den Controller nach `bindToLifecycle` und ruft `apply` in einem `LaunchedEffect(nightVision, exposure)` auf. Die Nachtvorgabe kommt aus `CaptureViewModel` über `ExposureSettings.nightDefault`.

**Ändern/Fallstricke:** Der Rückgabewert von `apply` wird in `CameraPreview` derzeit nicht ausgewertet. Die UI zeigt also die gewünschten Werte, nicht die begrenzten. Abgelehnte Anfragen erscheinen nur im Logcat (Tag `NightVisionController`). Für diese Klasse gibt es keine Unit-Tests.

#### [`SkyProjection.kt`](app/src/main/java/com/starwindow/app/core/camera/SkyProjection.kt)

Das Herzstück der Kette Pixel → Himmel und zurück: ein Lochkameramodell, das mit einer `DeviceAttitude` gedreht wird.

| Element | Art | Aufgabe |
|---|---|---|
| `ScreenPoint` | data class | View-Pixel, Ursprung oben links, y wächst nach unten |
| `PreviewFit` | enum | `FIT_CENTER` / `FILL_CENTER`; muss zum `PreviewView.scaleType` passen |
| `SkyProjection(attitude, focalPx, viewWidthPx, viewHeightPx)` | class | Projektion für ein Bild |
| `isUsable` | val | `focalPx > 1` und View-Größe > 0 |
| `visibleHorizontalFovDeg` / `visibleVerticalFovDeg` / `degreesPerPixel` | val | sichtbarer Bildwinkel bzw. Grad pro Pixel in Bildmitte |
| `centerDirection` | val | `attitude.cameraDirection` |
| `screenToSky(x, y)` / `screenToSky(ScreenPoint)` | fun | Pixel → wahres Azimut/Höhe |
| `skyToScreen(direction)` | fun | wahres Azimut/Höhe → Pixel; `null`, wenn hinter der Kamera |
| `isOnScreen(direction, marginPx)` | fun | liegt die Richtung im Sucher (mit Rand)? |
| `focalLengthInViewPixels(...)` | fun (companion) | Brennweite in View-Pixeln, aus Intrinsics, Stream, Drehung, View-Größe, Fit und `fovScale` |

**Funktionsweise (vorwärts, `screenToSky`):**
1. Strahl im Displaysystem: `(x − cx, cy − y, −focalPx)` mit `cx, cy` = halbe View-Größe. Das y-Vorzeichen dreht sich, weil die Bildschirm-y-Achse nach unten zeigt, die des Displaysystems nach oben. Die Rückkamera blickt entlang −z.
2. `attitude.displayToWorld(ray)` ergibt einen Vektor im ENU-System mit **magnetischem** Nord.
3. `attitude.toTrueNorth(v)` wendet `correction · aboutZenith(Missweisung)` an und wandelt in ein `Horizontal` um (Azimut 0° = geografisch Nord, Grad).

**Rückwärts (`skyToScreen`):** `toMagneticVector` (inverse Korrektur und Missweisung), dann `worldToDisplay` (transponierte Matrix). Bei `z > −1e-4` ist das Ergebnis `null`. Sonst gilt `scale = focalPx / −z` und `x = cx + dx·scale`, `y = cy − dy·scale`.

**Brennweite (`focalLengthInViewPixels`):** `f_stream = intrinsics.focalLengthInStreamPixels(w, h)` (der Stream liegt in Sensororientierung vor). Bei `rotationDegrees` von 90° oder 270° werden Breite und Höhe des angezeigten Bildes vertauscht. Die Skalierung ist `min(viewW/dispW, viewH/dispH)` bei `FIT_CENTER`, `max(...)` bei `FILL_CENTER`. Ergebnis: `f_stream · scale / fovScale.coerceIn(0.5, 2.0)`. Ein `fovScale > 1` heißt, der echte Bildwinkel ist breiter, die Brennweite also kürzer. `rotationDegrees` kommt aus `PreviewStreamInfo.rotationDegreesFor(displayRotation)` = `(sensorOrientation − displayRotation) mod 360` in `CameraPreview.kt`.

**Zusammenspiel:** `ui/components/SkyViewport.rememberSkyViewport` berechnet `focalPx` (mit `fovScale`) und `baselineFocalPx` (mit 1.0) und rechnet bei jeder Änderung von Stream, View-Größe, `fovScale` oder Displaydrehung neu. `CaptureScreen` (Tippen → `addAnchor(screenToSky(...))`) und `SkyOverlay` erzeugen pro Bild eine `SkyProjection`. `TargetIndicator` nutzt sie ebenfalls.

**Ändern/Fallstricke:** `PreviewView` läuft in `CameraPreview` mit `FIT_CENTER`. Wer das ändert, muss den `fit`-Parameter in `SkyViewport` mitziehen, sonst stimmt die Skalierung nicht. Die Grenzen 0,5…2,0 für `fovScale` stehen hier ein zweites Mal, zusätzlich zu `Calibration.MIN_FOV_SCALE`/`MAX_FOV_SCALE`. Bildschirmmitte und View-Größe beziehen sich auf die Overlay-View, nicht auf den Stream. Die Letterbox-Balken stecken also schon in `scale`. Linsenverzeichnung wird nicht modelliert. `ProjectionTest.kt` sichert ab: Bildmitte = Kamerarichtung, Bildrand = halber Bildwinkel, `screenToSky`/`skyToScreen` sind zueinander invers, hinter der Kamera gibt es `null`, Roll, Portrait-Drehung und Kalibrierfaktor. `CalibrationTest` prüft, dass die Kette auch mit Korrektur exakt invers bleibt.

#### [`TargetIndicator.kt`](app/src/main/java/com/starwindow/app/core/camera/TargetIndicator.kt)

Berechnet, wo und in welche Richtung der Marker für ein gesuchtes Ziel gezeichnet wird: auf dem Ziel oder als Pfeil am Rand.

| Element | Art | Aufgabe |
|---|---|---|
| `EdgeInsets` | data class | verdeckte Ränder (HUD oben, Bedienleiste unten) in View-Pixeln; `plus`, `NONE`, `uniform` |
| `TargetMarker` | data class | `x`, `y`, `onScreen`, Einheitsrichtung `dirX`/`dirY` (Bildschirmkoordinaten), `separationDeg`; `arrowRotationDeg = atan2(dirY, dirX)` |
| `TargetIndicator.locate(projection, target, insets)` | fun (object) | liefert den Marker oder `null`, wenn die Projektion unbrauchbar ist |

**Funktionsweise:** Die Richtung kommt aus dem Zielvektor im Displaysystem (`worldToDisplay(toMagneticVector(target))`), nicht aus der Projektion. So stimmt sie auch hinter dem Beobachter: `dirX = x/|xy|`, `dirY = −y/|xy|`. Ist der Vektor genau in der Mitte oder genau hinten, gilt „nach unten". Liegt das Ziel vor der Kamera und im **ganzen** View, also auch hinter einem Bedienband, gilt `onScreen = true`, und der Marker sitzt auf dem projizierten Punkt. Sonst wird ein Strahl von der Bildmitte mit dem um `insets` verkleinerten Rechteck geschnitten (ray-vs-rectangle). Ist die Reichweite negativ (Insets größer als der View), wird sie auf 0 gesetzt. Zum Schluss begrenzt `coerceIn` auf den View.

**Zusammenspiel:** `ui/capture/SkyOverlay.kt` ruft `locate` mit `chromeInsets` aus `CaptureScreen` auf, für ein verfolgtes Objekt oder Fenster aus dem `TrackingStore`.

**Ändern/Fallstricke:** Mit `TargetIndicatorTest.kt` abgesichert: Mitte, knapp daneben, Insets, zu große Insets, Pfeilrichtung, Ziel hinter dem Beobachter, Marker bleibt immer im View, Abstand und Pfeildrehung, unbrauchbare Projektion.

#### Typische Änderungen in diesem Bereich
- **Overlay sitzt am Rand falsch, in der Mitte richtig:** Das ist ein Bildwinkelproblem, kein Lageproblem. Prüfen: `CameraIntrinsics.fromCharacteristics` (aktive Fläche), `PreviewFit` gegen `PreviewView.scaleType` in `CameraPreview.kt`, `rotationDegreesFor`. Danach Bildfeld per Schwenk kalibrieren (`FieldOfViewSolver`).
- **Auf `FILL_CENTER` (Vollbild ohne Balken) umstellen:** `scaleType` in `CameraPreview.kt` und `fit` in `SkyViewport.rememberSkyViewport` gemeinsam ändern. `ProjectionTest` erweitern.
- **Neuen Belichtungsparameter (z. B. Weißabgleich) hinzufügen:** Feld mit Standardwert in `ExposureSettings`, Grenze in `ExposureCapabilities.fromCharacteristics`, Begrenzung in `NightVisionController.clamp`, `setCaptureRequestOption` in `apply`, Regler in `ui/capture/CaptureDialogs.kt`. `SerializationTest` ergänzen.
- **Zielpfeil soll anderen Bereichen ausweichen:** `EdgeInsets` am Aufrufer (`CaptureScreen` → `SkyOverlay`) erweitern. `TargetIndicator` selbst muss dafür nicht geändert werden.

<a id="pkg-core-calibration"></a>

### `core/calibration` — Lagekorrektur, Bildfeldfaktor und deren Vertrauenswürdigkeit

Hier liegen die Datenstruktur der Kalibrierung (`Calibration`, persistiert in den Einstellungen) und die reine Mathematik der Kalibrierverfahren. `AttitudeFit` löst die Lagekorrektur aus angepeilten Referenzen (Sternmuster, Peilung). `FieldOfViewSolver` bestimmt die echte Brennweite aus einem Schwenk. `CalibrationTrust` bewertet, ob eine gespeicherte Lagekorrektur am aktuellen Ort und zur aktuellen Zeit noch gilt. Die beiden Größen `correction` und `fovScale` werden absichtlich getrennt gemessen und nie gegeneinander verrechnet (DEV_PLAN §8).

**Android-Abhängigkeiten:** nein. **Benutzt:** `core/astro` (`Rotation3`, `Vec3`, `Horizontal`, `Angles`, `ObserverLocation`), `core/geometry` (`SphericalGeometry`), kotlinx.serialization. **Wird benutzt von:** `ui/calibration/CalibrationViewModel` und `CalibrationScreen`, `ui/capture/CaptureScreen` (Vertrauensanzeige), `core/sensors/OrientationTracker` (`Calibration.correction`), `ui/components/SkyViewport` indirekt über `fovScale`.

#### [`Calibration.kt`](app/src/main/java/com/starwindow/app/core/calibration/Calibration.kt)

Das persistierte Kalibrierergebnis: Lagekorrektur als Achse-Winkel-Vektor, Bildfeldfaktor und dazu je Herkunft, Restfehler, Anzahl der Messungen, Zeitpunkt und (nur bei der Lage) den Messort.

| Element | Art | Aufgabe |
|---|---|---|
| `CalibrationSource` | enum (`@Serializable`) | `NONE`, `MANUAL`, `STAR_PATTERN`, `LANDMARK_BEARING`, `PAN_SWEEP`; `label`, `worksWithoutSky` |
| `Calibration` | data class (`@Serializable`) | `correctionX/Y/ZRad` (Achse·Winkel in rad, im Weltsystem mit wahrem Nord), `fovScale`, `attitude*`- und `fov*`-Metadaten, `attitudeLatitudeDeg`/`attitudeLongitudeDeg` |
| `correction` | val | `Rotation3.fromRotationVector(...)` |
| `hasAttitudeCorrection` / `hasFovCorrection` | val | Quelle ≠ `NONE` (bei der Lage zusätzlich keine Identität) |
| `correctionAngleDeg`, `headingOffsetDeg` | val | Gesamtwinkel bzw. Azimutverschiebung am Horizont (−180…180], „mein Kompass lag so weit daneben" |
| `withAttitude(rotation, source, residualDeg, sampleCount, atMillis, measuredAt)` | fun | Lagekorrektur setzen (inkl. Messort) |
| `withFov(scale, ...)` | fun | Faktor setzen, begrenzt auf `MIN_FOV_SCALE = 0.5` … `MAX_FOV_SCALE = 2.0` |
| `clearAttitude()` / `clearFov()` | fun | jeweils nur eine Hälfte zurücksetzen |
| `rotationVectorOf(rotation)` | fun (companion) | Matrix → Achse-Winkel-Vektor, Umkehrung von `fromRotationVector` |

**Funktionsweise:** Die Korrektur wird als Drehvektor gespeichert, damit das JSON klein bleibt und sich serialisieren lässt. `rotationVectorOf` liest die Achse aus dem schiefsymmetrischen Anteil der Matrix. Bei etwa 180° (sin ≈ 0) nimmt die Funktion willkürlich die z-Achse, damit kein NaN entsteht.

**Zusammenspiel:** `CalibrationViewModel` erzeugt die Werte über `withAttitude`/`withFov` und speichert sie im `SettingsStore`. Die ViewModels geben sie per `OrientationTracker.updateCalibration` weiter, und jede `DeviceAttitude` trägt dann `correction`. `fovScale` fließt über `SkyViewport` in `focalLengthInViewPixels`.

**Ändern/Fallstricke:** Neue Felder brauchen Standardwerte (Persistenz). `CalibrationTest.kt` (Abschnitt Persistenz) prüft: Roundtrip der Drehung, `headingOffsetDeg`, Begrenzung des Faktors, „eine Hälfte löschen lässt die andere stehen", `worksWithoutSky`. `SerializationTest.kt` (`CalibrationSerializationTest`) prüft den JSON-Roundtrip.

#### [`AttitudeFit.kt`](app/src/main/java/com/starwindow/app/core/calibration/AttitudeFit.kt)

Ausgleichsrechnung für die Lagekorrektur (Wahba-Problem) aus Paaren „gemessene Richtung / Referenzrichtung".

| Element | Art | Aufgabe |
|---|---|---|
| `DirectionPair(measured, reference, label)` | data class | beide in wahrem Nord; `measured` muss **unkalibriert** sein; `errorDeg` = Winkelabstand |
| `AttitudeFitResult` | data class | `rotation`, `residualDeg` (RMS), `worstResidualDeg`, `sampleCount`, `baselineDeg` (größter Abstand zweier Referenzen); `correctsTiltToo` = ≥ 2 Paare und Basis ≥ 10° |
| `AttitudeFit.solve(pairs, iterations = 400)` | fun | volle Drei-Achsen-Lösung |
| `AttitudeFit.solveHeadingOnly(pairs)` | fun | nur Drehung um den Zenit (Peilung) |
| `Rotation3.headingOffsetDeg()` | Extension-fun | Azimutverschiebung einer Drehung am Horizont |

**Funktionsweise:** `solve` ist ein iterativer Gradientenabstieg statt einer SVD. Die Startdrehung ist `IDENTITY`. In jeder Iteration wird `ω = mittel(R·mᵢ × rᵢ)` gebildet, dann `R ← fromRotationVector(ω) · R`. Das Kreuzprodukt zeigt entlang der Achse, die mᵢ auf rᵢ zudreht. Sein Betrag ist etwa der Sinus des Restfehlers. Bei `|ω| < 1e-12` überspringt die Schleife die Aktualisierung. Mit einem Paar wird die Richtung nur verschoben (exakt für diese Richtung). Erst ab zwei gut getrennten Paaren ist auch die Kippung bestimmt. `solveHeadingOnly` bildet das zirkuläre Mittel der Azimutdifferenzen (Sinus/Kosinus-Summen, damit 0/360° nicht stört) und gibt `Rotation3.aboutZenith(offset)` zurück. `null` gibt es bei leeren Paaren oder wenn sich die Differenzen genau aufheben. Restfehler und Basis werden für beide Varianten in `result()` über `atan2(|a×b|, a·b)` berechnet.

**Zusammenspiel:** Das Sternmuster in `CalibrationViewModel` ruft `solve` auf, die Peilung `solveHeadingOnly`. Die gemessene Richtung ist jeweils `DeviceAttitude.uncalibratedCameraDirection` (das Fadenkreuz). Danach folgt `Calibration.withAttitude(...)`.

**Ändern/Fallstricke:** Gibt man bereits korrigierte Richtungen als `measured` ein, fittet die Korrektur gegen sich selbst. Deshalb immer `uncalibratedCameraDirection` verwenden. Abgesichert in `CalibrationTest.kt`: ein Paar ist exakt, mehrere Paare finden Kurs- und Kippfehler, große Fehler konvergieren, Rauschen wird gemittelt, Peilung lässt die Höhe unberührt, Mittelung über die 0°-Naht, keine Paare ergeben `null`.

#### [`FieldOfViewSolver.kt`](app/src/main/java/com/starwindow/app/core/calibration/FieldOfViewSolver.kt)

Bestimmt die echte Brennweite in View-Pixeln aus mehreren Antippungen **desselben** festen Merkmals während eines Schwenks. Braucht keinen Kompass und keinen Himmel, nur die relative Drehung.

| Element | Art | Aufgabe |
|---|---|---|
| `PanSighting(worldFromDisplay, offsetXPx, offsetYPx)` | data class | Lage beim Antippen (darf die rohe magnetische Matrix sein) und Abstand von der Bildmitte; y **nach oben** positiv |
| `FovFitResult` | data class | `focalPx`, `residualDeg`, `sampleCount`, `panAngleDeg`, `screenSpanPx` |
| `FovFitProblem` | enum | `TOO_FEW_SIGHTINGS`, `PAN_TOO_SMALL`, `POINTS_TOO_CLOSE`, `NO_AGREEMENT`, jeweils mit deutscher `message` |
| `FovFitException(problem)` | class | Träger des Problems in `Result.failure` |
| `FieldOfViewSolver.solve(sightings, baselineFocalPx)` | fun | `Result<FovFitResult>` |
| `scatterDeg(sightings, focalPx)` | internal fun | RMS-Winkelabstand der Weltrichtungen von ihrem Mittel |
| `fieldOfViewDeg(focalPx, extentPx)` | fun | `2·atan(extent/(2f))` |
| `FovFitResult.isTrustworthy()` | Extension-fun | erfüllt alle drei Schwellen (derzeit im App-Code ungenutzt) |

**Funktionsweise:**
1. Ablehnung bei weniger als 2 Antippungen oder `baselineFocalPx ≤ 0` (beides `TOO_FEW_SIGHTINGS`).
2. Schwenkwinkel = größter Winkel zwischen den Kameraachsen `R·(0,0,−1)`. Unter `MIN_PAN_ANGLE_DEG = 4.0` gilt `PAN_TOO_SMALL`. Das wird absichtlich vor dem Bildschirmabstand geprüft.
3. Größter Bildschirmabstand der Antippungen. Unter `MIN_SCREEN_SPAN_PX = 80.0` gilt `POINTS_TOO_CLOSE`.
4. Suche über `ln f` im Bereich `[baseline/4, baseline·4]`: grobes Raster mit 241 Stützstellen, dann 80 Schritte Goldener Schnitt im Intervall ±1 Rasterschritt um das Minimum. Kostenfunktion ist `scatterDeg`. Jede Antippung ergibt die Weltrichtung `R·normalize(offX, offY, −f)`, und die richtige Brennweite lässt alle Richtungen zusammenfallen.
5. Restfehler über `MAX_RESIDUAL_DEG = 1.5` ergibt `NO_AGREEMENT` (vermutlich verschiedene Merkmale angetippt).

**Zusammenspiel:** `CalibrationViewModel.capturePanSighting` baut die `PanSighting` aus `DeviceAttitude.worldFromDisplay` und dem Tippabstand. `applyPanSweep` ruft `solve(…, baselineFocalPx)` mit der unkalibrierten Brennweite aus `SkyViewport` auf und speichert `fovScale = baselineFocalPx / fit.focalPx` per `withFov(…, PAN_SWEEP, …)`.

**Ändern/Fallstricke:** Die Weltrichtung muss mit derselben Lochkamera-Konvention wie in `SkyProjection.screenToSky` gebildet werden (y nach oben, −z). Weicht die Konvention ab, kommt eine falsche Brennweite heraus. Der Faktor wirkt umgekehrt: kürzere gemessene Brennweite heißt `fovScale > 1`. Abgesichert in `CalibrationTest.kt`: sauberer Schwenk, Unabhängigkeit vom Kompasskurs, diagonaler Schwenk, mehr als zwei Antippungen, zu kleiner Schwenk, zwei verschiedene Merkmale, langer Schwenk mit gleichem Bildpunkt, eine einzelne Antippung.

#### [`CalibrationTrust.kt`](app/src/main/java/com/starwindow/app/core/calibration/CalibrationTrust.kt)

Bewertet eine gespeicherte **Lage**korrektur nach Ort und Alter. Sie wird nie automatisch verworfen, der Benutzer bekommt nur einen Hinweis.

| Element | Art | Aufgabe |
|---|---|---|
| `CalibrationTrust` | enum | `NONE`, `FRESH`, `AGING`, `STALE`, `MOVED`, jeweils mit `label` und `explanation` (Deutsch); `isQuestionable` (AGING/STALE/MOVED), `needsRemeasuring` (STALE/MOVED) |
| `Calibration.trustAt(observer, nowMillis)` | Extension-fun | Bewertung |
| `Calibration.attitudeLocation()` | Extension-fun | Messort als `ObserverLocation` oder `null` |
| `MOVED_KM = 5.0`, `AGING_MILLIS = 2 Tage`, `STALE_MILLIS = 14 Tage` | private const | Schwellen |

**Funktionsweise:** Ohne Lagekorrektur gilt `NONE`. Sind beide Orte bekannt und liegen sie mehr als 5 km auseinander, gilt `MOVED`, und zwar vor jeder Altersprüfung („Entfernung schlägt Alter"). Danach gilt: Zeitstempel ≤ 0 → `AGING`, älter als 14 Tage → `STALE`, älter als 2 Tage → `AGING`, sonst `FRESH`. Ist der Messort unbekannt (alte Daten), wird nur nach dem Alter bewertet.

**Zusammenspiel:** `CaptureScreen` und `CalibrationScreen` rufen `trustAt(state.observer, System.currentTimeMillis())` für die Statusanzeige auf. Die Live-Erkennung von Magnetstörungen ist davon getrennt und liegt in `core/sensors`.

**Ändern/Fallstricke:** Der `fov`-Teil wird nicht bewertet. Die Schwellen sind `private` in dieser Datei. Abgesichert durch `CalibrationTrustTest` (in `WindowQualityTest.kt`, Dateiname passt nicht zum Inhalt): keine Korrektur, frisch, älter/veraltet, Ortswechsel schlägt Alter, Gang durch den Garten löst keine Warnung aus, ohne Messort zählt nur das Alter, `needsRemeasuring` nur bei STALE/MOVED.

#### Typische Änderungen in diesem Bereich
- **Schwellen für „veraltet/anderer Ort" ändern:** `MOVED_KM`, `AGING_MILLIS`, `STALE_MILLIS` in `CalibrationTrust.kt`. Die Texte in `CalibrationTrust.explanation` anpassen, denn `STALE` nennt dort „zwei Wochen".
- **Schwenk-Kalibrierung toleranter oder strenger machen:** Konstanten `MIN_PAN_ANGLE_DEG`, `MIN_SCREEN_SPAN_PX`, `MAX_RESIDUAL_DEG` in `FieldOfViewSolver`. Danach die Ablehnungstests in `CalibrationTest.kt` laufen lassen.
- **Neues Kalibrierverfahren:** Wert in `CalibrationSource` mit `label`/`worksWithoutSky` ergänzen. Die Mathematik hier als reine Funktion anlegen und testen. Die Aufnahmelogik kommt in `CalibrationViewModel`, das Ergebnis über `withAttitude`/`withFov` speichern.
- **Lagekorrektur auch beim Ortswechsel automatisch zurücksetzen:** Statt nur zu warnen, in `CaptureViewModel`/`CalibrationViewModel` bei `trustAt == MOVED` `clearAttitude()` speichern. Das widerspricht allerdings dem Kommentar in `CalibrationTrust.kt`, also bewusst entscheiden.

<a id="pkg-data-catalog"></a>

### `data/catalog` — Katalogmodell, Katalogquellen, Sonne/Mond, Sternbilder, Objektnotizen

Das Paket definiert, was ein Himmelsobjekt in der App ist (`SkyObject`, `ObjectType`), lädt die mitgelieferten JSON-Kataloge aus den Assets und hält sie zusammengeführt im Speicher (`CatalogRepository`). Zusätzlich stellt es Sonne und Mond als „bewegliche" Katalogeinträge bereit (`EphemerisBody`, `EphemerisCatalog`), lädt die Sternbildfiguren (`ConstellationRepository`) und die handgeschriebenen Objektbeschreibungen (`ObjectNotesRepository`). Einstiegspunkte sind fast immer `container.catalogRepository.objects()` bzw. `objectsVisibleFrom(...)`.

**Android-Abhängigkeiten:** teilweise. `SkyObject.kt`, `EphemerisBody.kt` und `CatalogRepository.kt` sind reines Kotlin (JVM-testbar); `CatalogSource.kt` (`AssetCatalogSource`), `Constellation.kt` (`ConstellationRepository`) und `ObjectNotesRepository.kt` brauchen `android.content.Context` (Assets), letzteres zusätzlich `android.util.Log`. **Benutzt:** `core/astro` (`Equatorial`, `Precession`, `SolarEphemeris`, `LunarEphemeris`), `domain/ObjectSearch.fold`, kotlinx.serialization, Coroutines. **Wird benutzt von:** praktisch der ganzen App – `domain/*` (Transit-, Planungs-, Such-, Filter-, Fotoguide-Logik arbeiten auf `SkyObject`), allen Katalog-ViewModels (`CaptureViewModel`, `ObjectSearchViewModel`, `ObjectDetailViewModel`, `PlanningViewModel`, `StargazingHubViewModel`, `PhotoGuideViewModel`, `WindowDetailViewModel`, `CalibrationViewModel`), `data/tracking`, `data/planning`.

#### [`SkyObject.kt`](app/src/main/java/com/starwindow/app/data/catalog/SkyObject.kt)

Das zentrale Datenmodell eines Katalogeintrags, die Objektart als Enum und das Dateiformat der Katalog-Assets. Alle drei sind `@Serializable` und werden direkt aus `stars.json`/`deepsky.json` dekodiert.

| Element | Art | Aufgabe |
|---|---|---|
| `ObjectType` | enum | Objektart: `STAR`, `DOUBLE_STAR`, `GALAXY`, `GALAXY_GROUP`, `NEBULA`, `EMISSION_NEBULA`, `REFLECTION_NEBULA`, `DARK_NEBULA`, `PLANETARY_NEBULA`, `SUPERNOVA_REMNANT`, `OPEN_CLUSTER`, `GLOBULAR_CLUSTER`, `CLUSTER_NEBULA`, `SOLAR_SYSTEM` (Sonne/Mond), `OTHER`. `@SerialName` = Enumname, d. h. genau der String im JSON. |
| `ObjectType.label` | Property | Deutscher Anzeigename („Emissionsnebel" …). |
| `ObjectType.isStar` | Property | `true` für `STAR` und `DOUBLE_STAR`. |
| `ObjectType.respondsToNarrowband` | Property | `true` für Emissionsnebel, PN, SNR, Haufen mit Nebel – wird z. B. vom Fotoguide für Filterempfehlungen genutzt. |
| `SkyObject` | data class | Ein Katalogeintrag (Felder siehe unten). |
| `SkyObject.equatorialJ2000` | Property | Katalogposition als `Equatorial(raDeg, decDeg)`, Epoche J2000. |
| `SkyObject.positionAt(precession)` | fun | Position zum Datum der übergebenen `Precession`; bei Sonne/Mond stattdessen Ephemeride zu `precession.epochMillis`. |
| `SkyObject.positionAtMillis(millis)` | fun | Position zu einem UTC-Zeitpunkt (ms): Ephemeride für bewegliche Körper, sonst `positionAt(Precession.forEpoch(millis))`. |
| `SkyObject.isMoving` | Property | `body != null` – Sonne/Mond. |
| `SkyObject.displayName` | Property | `"$id · $name"`, oder nur `id`, wenn der Name leer oder gleich der ID ist. |
| `SkyObject.allIdentifiers` | Property | `id` + `catalogIds`, entdoppelt, primäre zuerst. |
| `SkyObject.isExtended` | Property | `sizeArcmin >= 1.0`. |
| `SkyObject.fillFactor(windowRadiusDeg)` | fun | Anteil der Fensterbreite (2 × Radius), den das Objekt einnimmt; > 1 = passt nicht; `null` ohne Größe. |
| `SkyObject.matches(query)` | fun | Einfache Teilstring-Suche (klein geschrieben) über Name, Bezeichnungen, Alternativnamen. In `src/main` nicht benutzt – die App-Suche läuft über `domain/ObjectSearch`. |
| `CatalogFile` | data class | Wurzel der Katalog-Assets: `version`, `name`, `epoch` (Default `"J2000"`), `license`, `objects`. |

**Felder von `SkyObject`** (alle Winkel in Grad, sofern nicht anders angegeben):

| Feld | Typ | Bedeutung |
|---|---|---|
| `id` | `String` | Primäre Bezeichnung und eindeutiger Schlüssel in der ganzen App (Routen `objects/{objectId}`, Favoriten, Merkliste, Pläne). Sterne: `HR <nr>`; Deep Sky: `M31`, `NGC 7000`, `IC 434`, `Sh2-101`, `B033`, `LBN …`, sonstige OpenNGC-Namen; Sonne/Mond: `"Sonne"`, `"Mond"`. |
| `name` | `String` | Gebräuchlicher (möglichst deutscher) Name, darf leer sein. |
| `type` | `ObjectType` | Objektart. |
| `raDeg`, `decDeg` | `Double` | Rektaszension 0–360°, Deklination −90…+90°, **J2000**. |
| `magnitude` | `Double?` | Integrierte visuelle Helligkeit (mag); `null` = unbekannt. |
| `sizeArcmin`, `minorAxisArcmin` | `Double?` | Große / kleine Achse in Bogenminuten. |
| `positionAngleDeg` | `Double?` | Lage der großen Achse, Grad von Nord über Ost. |
| `surfaceBrightness` | `Double?` | Mittlere Flächenhelligkeit, mag/arcmin². |
| `morphology` | `String?` | Morphologischer Typ (Galaxien, z. B. `Sb`). |
| `spectralType` | `String?` | Spektraltyp (Sterne, z. B. `A0Va`); bewusst getrennt von `morphology`. |
| `separationArcsec` | `Double?` | Abstand der Komponenten eines Doppelsterns in Bogensekunden. |
| `constellation` | `String?` | IAU-Kürzel (`Ori`, `UMa`, `Ser` …). |
| `catalogIds` | `List<String>` | Weitere Bezeichnungen (NGC/IC/M, HD, SAO, HIP, Bayer/Flamsteed, Sh2, LBN, PGC …). |
| `alternativeNames` | `List<String>` | Weitere Namen (englische Form, ausgeschriebene Bayer-Namen …). |
| `source` | `String` | Herkunft; wird von `AssetCatalogSource` beim Laden auf `CatalogFile.name` gesetzt, bei Sonne/Mond `"ephemeris"`. Default `"local"`. |
| `body` | `EphemerisBody?` | Nur bei Sonne/Mond gesetzt; steuert die zeitabhängige Position. In den Dateien nie vorhanden (Default `null`). |

**Funktionsweise:** `positionAt` ist der einzige korrekte Weg zur Himmelsposition „heute": Die J2000-Koordinate wird über `Precession.toDate` gedreht (Eigenbewegung wird ignoriert). Die Durchgangsrechnung rechnet einmal eine `Precession` für die Nacht vor und ruft `positionAt` für feste Objekte; nur für `isMoving`-Objekte wird je Abtastschritt `positionAtMillis` aufgerufen (siehe `domain/TransitCalculator`, `TonightBoard`, `ObservationPlanner`).

**Zusammenspiel:** Wird von `AssetCatalogSource` dekodiert, von `CatalogRepository` gemischt; `TrackedObject` speichert ein komplettes `SkyObject` in den SharedPreferences.

**Ändern/Fallstricke:**
- `raDeg`/`decDeg` direkt zu verwenden heißt J2000 statt Datum (~0,37° Fehler heute). Für alles, was auf den realen Himmel zeigt, `positionAt`/`positionAtMillis` nehmen (abgesichert in `PrecessionTest`, „the search really applies precession …").
- Bei Sonne/Mond sind `raDeg = decDeg = 0.0` Platzhalter. Code, der `decDeg` direkt liest (z. B. Sichtbarkeitsfilter), muss `isMoving` vorher abfragen – so geschehen in `CatalogRepository.objectsVisibleFrom`, `OverlaySelection.everRises`, `TransitCalculator`.
- Neue Felder müssen einen Default haben, sonst scheitert das Dekodieren der vorhandenen Assets und gespeicherter `TrackedObject`s. Die Assets werden mit `ignoreUnknownKeys = true` gelesen, unbekannte Zusatzfelder im JSON schaden also nicht.
- Neue `ObjectType`-Werte brauchen einen `label`-Zweig (der `when` ist erschöpfend) und Anpassungen in `ui/components/ObjectSymbol.kt`, `ui/theme/ObjectPalette.kt`, `domain/ObjectDescription` u. a.; außerdem im `TYPE_MAP` von `scripts/import_deepsky.py`.
- Tests: `SerializationTest` (`DeepSkyCatalogTest`: `fillFactor`, `matches`, `allIdentifiers` an M31), `PrecessionTest`, `EphemerisTransitTest`.

#### [`CatalogRepository.kt`](app/src/main/java/com/starwindow/app/data/catalog/CatalogRepository.kt)

Hält den aus allen Quellen zusammengeführten Katalog (~22.500 Einträge) im Speicher und liefert ihn sortiert nach Helligkeit. Keine Datenbank, kein Plattencache.

| Element | Art | Aufgabe |
|---|---|---|
| `CatalogRepository(sources)` | class | Bekommt die Liste der `CatalogSource`s (im `AppContainer`: Sterne- und Deep-Sky-Asset). |
| `objects()` | suspend fun | Alle Objekte, beim ersten Aufruf geladen und dann gecacht. |
| `objectsVisibleFrom(latitudeDeg, magnitudeLimit)` | suspend fun | Vorfilter: nur Objekte, die von dieser Breite überhaupt aufgehen und hell (oder groß) genug sind. |
| `refresh()` | suspend fun | Lädt alle Quellen neu und ersetzt den Cache (derzeit ohne Aufrufer). |
| `MIN_SIZE_WITHOUT_MAGNITUDE_ARCMIN` | const | `3.0` – Mindestgröße für Einträge ohne Helligkeit. |

**Funktionsweise:**
1. `objects()` nimmt einen `Mutex`; ist `cached` leer, wird `loadAll()` ausgeführt. Damit lädt auch bei vielen gleichzeitigen ersten Aufrufen nur einer – die anderen warten. Lazy: geladen wird erst beim ersten Zugriff (typischerweise beim Start des Suchers durch `CaptureViewModel`).
2. `loadAll()`: `EphemerisCatalog.all` (Mond, Sonne) **plus** `load()` jeder Quelle; fehlgeschlagene Quellen (`Result.failure`) werden still übergangen (`getOrNull`). Danach `distinctBy { it.id }` (bei gleicher ID gewinnt der zuerst gelieferte Eintrag: Ephemeride, dann Sterne, dann Deep Sky) und `sortedBy { magnitude ?: 99.0 }` (ohne Helligkeit ans Ende). Durch die Sortierung steht am Ende die Sonne (−26,7) vor dem Mond (−12,7).
3. Das Lesen und Parsen der Dateien läuft in `AssetCatalogSource.load` auf `Dispatchers.IO`; `distinctBy`/`sortedBy` laufen auf dem Dispatcher des Aufrufers.
4. `objectsVisibleFrom`: maximale Kulminationshöhe `90 − |Breite − Dekl.|` muss > 0 sein (für `isMoving` immer 90). Helligkeitsgrenze `magnitudeLimit` (`null` = keine Grenze): Objekte mit Helligkeit müssen ≤ Grenze sein; Objekte **ohne** Helligkeit kommen durch, wenn `sizeArcmin >= 3'` (damit Sh2-/LBN-Nebel nicht verschwinden).

**Zusammenspiel:** Aufgerufen von `WindowDetailViewModel` (`objectsVisibleFrom` für die Durchgangsrechnung) und von den in der Paketübersicht genannten ViewModels über `objects()`.

**Ändern/Fallstricke:** Der Klassenkommentar spricht noch von „a few thousand entries" – tatsächlich sind es ~22.500. Ein Fehlschlag einer Quelle ist unsichtbar (kein Log) – wer einen „leeren Katalog" sucht, sollte in `AssetCatalogSource.load` loggen. Wer einen Plattencache für Online-Quellen will, ist laut Kommentar hier richtig.

#### [`CatalogSource.kt`](app/src/main/java/com/starwindow/app/data/catalog/CatalogSource.kt)

Schnittstelle für Katalogquellen und ihre zwei Implementierungen: Asset-Datei (aktiv) und Online-Platzhalter (inaktiv).

| Element | Art | Aufgabe |
|---|---|---|
| `CatalogSource` | interface | `id`, `displayName`, `suspend fun load(): Result<List<SkyObject>>`. |
| `AssetCatalogSource(context, assetPath)` | class | Liest eine `CatalogFile` aus `assets/`, setzt `source` jedes Eintrags auf `CatalogFile.name` (oder `displayName`, wenn leer). `id = "asset:$assetPath"`. |
| `STARS_ASSET` / `DEEP_SKY_ASSET` | const | `"catalog/stars.json"` / `"catalog/deepsky.json"`. `DEFAULT_ASSET = STARS_ASSET`. |
| `RemoteCatalogSource` | class | Platzhalter für VizieR/SIMBAD-TAP; `load()` liefert immer `Result.failure(NotImplementedError)`. Wird nirgends instanziiert. |

**Funktionsweise:** `load()` läuft in `withContext(Dispatchers.IO)`, liest die ganze Datei als String (`readText`) und dekodiert mit `Json { ignoreUnknownKeys = true }`; alle Ausnahmen landen über `runCatching` im `Result`.

**Ändern/Fallstricke:** Das komplette Einlesen von 3,9 MB (`deepsky.json`) als String und Dekodieren ist der teuerste Schritt beim ersten Katalogzugriff. Eine neue Asset-Datei braucht nur eine weitere `AssetCatalogSource` im `AppContainer`; IDs dürfen sich nicht mit den bestehenden Dateien überschneiden (sonst gewinnt still die erste Quelle; für Sterne vs. Deep Sky abgesichert in `DeepSkyCatalogTest` „the two bundled sources do not overlap").

#### [`EphemerisBody.kt`](app/src/main/java/com/starwindow/app/data/catalog/EphemerisBody.kt)

Macht Sonne und Mond zu normalen Katalogeinträgen, deren Position aus der Ephemeride (`core/astro`) statt aus festen Koordinaten kommt.

| Element | Art | Aufgabe |
|---|---|---|
| `EphemerisBody` | enum | `SUN("Sonne", "Sonne", 31.9', −26.7 mag)`, `MOON("Mond", "Mond", 31.1', −12.7 mag)`; Felder `id`, `label`, `meanSizeArcmin`, `brightestMagnitude`. |
| `EphemerisBody.positionAt(millis)` | fun | Scheinbare Position, Äquinoktium des Datums: `SolarEphemeris.at(millis).equatorial` bzw. `LunarEphemeris.at(millis).equatorial`. |
| `EphemerisBody.degreesPerHour` | Property | Grobe Eigenbewegung: Mond `0.55`, Sonne `0.04` °/h – Hinweis für die Abtastfeinheit der Durchgangsrechnung. |
| `EphemerisCatalog.moon` / `.sun` | val | Fertige `SkyObject`s mit `id`/`name` „Mond"/„Sonne", `type = SOLAR_SYSTEM`, Platzhalterkoordinaten 0/0, Mittelwerten für Helligkeit/Größe, Alternativnamen („Luna", „Erdmond" / „Sol"), `source = "ephemeris"`, `body` gesetzt. |
| `EphemerisCatalog.all` | val | `listOf(moon, sun)` – wird dem Katalog vorangestellt. |
| `EphemerisCatalog.forBody(body)` | fun | Eintrag zu einem `EphemerisBody` (derzeit ohne Aufrufer in `src/main`). |
| `EphemerisCatalog.stateText(body, millis)` | fun | Infoblatt-Text: Mond mit Phase, % beleuchtet und Entfernung in Tsd. km (`LunarEphemeris.illuminationAt`); Sonne mit Warnhinweis. Benutzt von `domain/ObjectDescription`. |

**Ändern/Fallstricke:** Planeten würden als weiterer Enumwert mit eigenem `positionAt`-Zweig hinzukommen (plus Eintrag in `EphemerisCatalog`, `forBody`, `stateText`, `degreesPerHour`). Da `TrackedObject` den `body` als `"SUN"`/`"MOON"` speichert, dürfen die `@SerialName`s nicht umbenannt werden. Tests: `EphemerisTransitTest` (Mond-Position ≠ Platzhalter, Bewegung ~0,5°/h, Sonne ~1°/Tag, Mond wird nie wegen Deklination verworfen, Durchgang des Mondes durch ein Fenster).

#### [`Constellation.kt`](app/src/main/java/com/starwindow/app/data/catalog/Constellation.kt)

Modell und Lader der Sternbild-**Figuren** (Strichfiguren, nicht IAU-Grenzen) aus `catalog/constellations.json`.

| Element | Art | Aufgabe |
|---|---|---|
| `FigureStar` | data class | Ein Figurstern: `name`, `raDeg`, `decDeg` (J2000); `equatorialJ2000`, `positionAt(precession)`. |
| `Constellation` | data class | `id` (IAU-Kürzel, z. B. `Ori`), `name` (deutsch), `stars`, `lines` (Indexpaare in `stars`). |
| `Constellation.segments` | Property | `lines` als Sternpaare; fehlerhafte Paare (Länge ≠ 2, Index außerhalb) werden verworfen statt zu crashen. |
| `ConstellationFile` | data class | Dateiwurzel: `version`, `name`, `epoch`, `constellations`. |
| `ConstellationRepository(context, assetPath)` | class | Lädt die Datei einmal (`Dispatchers.IO`) und cacht das Ergebnis; bei Fehler leere Liste. |

**Funktionsweise/Fallstricke:** Anders als `CatalogRepository` ohne `Mutex` – parallele erste Aufrufe laden ggf. doppelt (harmlos). Ein Ladefehler wird als `emptyList()` **dauerhaft** gecacht und nicht geloggt. Benutzt von `WindowDetailViewModel` (Sternbilder, die durch das Fenster ziehen, via `ConstellationTransitCalculator`). Tests: `ConstellationAndTrackTest` (Segmente, kaputte Indizes), `SerializationTest` → `ConstellationDataTest` (Datei parst, Indizes gültig, keine unplausibel langen Segmente, Positionsstichproben, gemeinsame Sterne stimmen zwischen Figuren überein).

#### [`ObjectNotesRepository.kt`](app/src/main/java/com/starwindow/app/data/catalog/ObjectNotesRepository.kt)

Liefert den handgeschriebenen Beschreibungssatz zu einem Objekt aus `catalog/object_notes.json`; Objekte ohne Notiz bekommen den generierten Text aus `domain/ObjectDescription`.

| Element | Art | Aufgabe |
|---|---|---|
| `ObjectNotesFile` | private data class | `version`, `notes` (Bezeichnung → Text), `starNotes` (Sternname → Text). |
| `noteFor(obj)` | suspend fun | Notiz oder `null`. |

**Funktionsweise:** Beim ersten Aufruf (doppelt geprüftes Laden unter `Mutex`, IO auf `Dispatchers.IO`) werden beide Maps eingelesen und ihre Schlüssel mit `ObjectSearch.fold` normalisiert (klein, Umlaute ausgeschrieben, nur Buchstaben/Ziffern, führende Nullen je Zahl entfernt – `M 45`, `M45`, `Mel022` → `m45`/`mel22`). `noteFor` probiert zuerst alle `allIdentifiers` gegen `notes`, dann `name` und `alternativeNames` gegen `starNotes`. Lesefehler werden geloggt (`Log.w`, Tag `ObjectNotes`) und durch eine leere Datei ersetzt.

**Zusammenspiel/Fallstricke:** Aufrufer: `ObjectDetailViewModel`, `WindowDetailViewModel` (jeweils `ObjectDescription.describe(obj, note)`). `starNotes` sind über den (deutschen) Namen verschlüsselt – wird ein Stern im Import umbenannt, findet er seine Notiz nur noch, wenn der alte Name als `alternativeNames` erhalten bleibt. Der Klassenkommentar nennt „three thousand entries" (veraltet).

#### Typische Änderungen in diesem Bereich
- **Neues Feld in `SkyObject`:** Feld mit Default-Wert in `SkyObject` ergänzen → im passenden Importskript (`import_bsc.py`/`import_deepsky.py`) erzeugen → Asset neu bauen → Anzeige in `domain/ObjectDescription` bzw. `ui/windows/ObjectInfoSheet.kt` / `ui/search/ObjectDetailScreen.kt`. Gespeicherte `TrackedObject`s lesen das Feld dank Default weiter.
- **Neue Katalogquelle anbinden:** `CatalogSource` implementieren (bei Netz: Cache mitdenken, `Result.failure` bei Fehler) → in `AppContainer` in die Liste von `CatalogRepository` aufnehmen. Reihenfolge der Liste entscheidet bei ID-Kollisionen. Für eine weitere Asset-Datei genügt `AssetCatalogSource(context, "catalog/xyz.json")`.
- **Neuen beweglichen Körper (Planet):** Enumwert in `EphemerisBody` + Rechnung in `positionAt` + `SkyObject` in `EphemerisCatalog` (in `all`, `forBody`, `stateText`) + `degreesPerHour`.
- **Objektbeschreibung ergänzen:** Eintrag in `object_notes.json` unter `notes` (Schlüssel beliebige Schreibweise einer Bezeichnung, wird gefaltet) bzw. `starNotes` (Sternname).

<a id="pkg-data-windows"></a>

### `data/windows` — Gespeicherte Fenster und App-Einstellungen

Enthält die Ablage der eingezeichneten Fenster (`SkyWindowRepository`) und – historisch bedingt, laut DEV_PLAN §11.4 S1 eigentlich ein eigenes Paket – den Speicher aller App-Einstellungen (`SettingsStore`).

**Android-Abhängigkeiten:** ja (`Context`, SharedPreferences, `filesDir`, `Log`). **Benutzt:** `core/geometry/SkyWindow`, `core/astro/ObserverLocation`, `core/calibration/Calibration`, `core/camera/ExposureSettings`, `data/weather` (`WeatherPlace`, `WeatherModel`). **Wird benutzt von:** `CaptureViewModel` (Fenster speichern, Sucher-Einstellungen), `WindowListViewModel`, `WindowDetailViewModel`, `ObjectSearchViewModel`, `CalibrationViewModel`, `WeatherViewModel`, `PhotoGuideViewModel`, `StargazingHubViewModel`, `StarWindowNavHost`, `data/planning/PlannedPath`.

#### [`SettingsStore.kt`](app/src/main/java/com/starwindow/app/data/windows/SettingsStore.kt)

Alle Einstellungen als ein serialisierter Block in den SharedPreferences, angeboten als `StateFlow<Settings>`.

| Element | Art | Aufgabe |
|---|---|---|
| `Settings` | data class | Alle Einstellungen (Tabelle unten). |
| `SettingsStore.settings` | `StateFlow<Settings>` | Aktueller Stand, reaktiv. |
| `current` | Property | `settings.value`. |
| `setXxx(...)` | fun | Je ein Setter pro Feld (s. u.), alle über `update`. |
| `update(transform)` | fun | Wendet die Transformation an, speichert das ganze JSON, setzt den StateFlow. |

| Feld in `Settings` | Typ | Default | Bedeutung / Setter |
|---|---|---|---|
| `magnitudeLimit` | `Double` | `6.0` | Grenzgröße für die Objektauswahl im Sucher (gelesen von `CaptureViewModel` → `OverlaySelection.select`). `setMagnitudeLimit` (begrenzt auf 0–12) hat derzeit keinen Aufrufer, der Wert bleibt also 6,0. |
| `showGraticule` | `Boolean` | `true` | Gradnetz im Sucher (`setShowGraticule`). |
| `showCatalogOverlay` | `Boolean` | `true` | Katalogobjekte im Sucher (`setShowCatalogOverlay`). |
| `manualLocation` | `ObserverLocation?` | `null` | Von Hand gesetzter Beobachtungsort statt GPS (`setManualLocation`). |
| `calibration` | `Calibration` | `Calibration.NONE` | Kalibrierung von Sichtfeld/Ausrichtung (`setCalibration`). |
| `exposure` | `ExposureSettings` | `ExposureSettings.AUTO` | Kamerabelichtung des Suchers (`setExposure`). |
| `weatherPlace` | `WeatherPlace?` | `null` | Ort der Wettervorhersage, getrennt vom Beobachtungsort (`setWeatherPlace`). Trägt selbst ein `bortleOverride`. |
| `weatherModel` | `WeatherModel` | `WeatherModel.DEFAULT` (= `ECMWF_IFS`) | Wettermodell (`setWeatherModel`). |
| `telescopeId` | `String?` | `null` | Gewähltes Smart-Teleskop, aufgelöst über `SmartTelescopes.byId` (`setTelescope`). |
| `bortleOverride` | `Int?` | `null` | Manuelle Bortle-Stufe 1–9 (`setBortleOverride`); wird vom Fotoguide nur benutzt, wenn kein `weatherPlace` gesetzt ist – sonst landet der Wert in `weatherPlace.bortleOverride`. |
| `guideObjectId` | `String?` | `null` | Zuletzt im Fotoguide gewähltes Objekt (`setGuideObject`). |

**Funktionsweise:** SharedPreferences `starwindow_settings`, Schlüssel `settings_json`, JSON mit `encodeDefaults = true` und `ignoreUnknownKeys = true`. Gelesen synchron im Konstruktor; unlesbares JSON → Log + `Settings()` (der defekte Wert bleibt bis zum nächsten Schreiben stehen). `update` ist nicht synchronisiert (Read-Modify-Write auf `state.value`) und für Aufrufe vom Main-Thread gedacht; geschrieben wird per `apply`.

**Ändern/Fallstricke:** Jede Änderung schreibt den kompletten Block – bei Slidern teuer (DEV_PLAN §11.3 P6, `NightVisionDialog`). Neue Felder brauchen einen Default, sonst scheitert das Lesen alter Blöcke und **alle** Einstellungen fallen auf Default zurück. Umbenennen eines Felds verliert dessen Wert still. Verschachtelte Typen (`Calibration`, `ExposureSettings`) müssen rückwärtskompatibel bleiben; abgesichert in `SerializationTest` → `CalibrationSerializationTest` (Kalibrierung und Belichtung überstehen JSON-Rundreise, unbekanntes Zusatzfeld bricht das Lesen nicht).

#### [`SkyWindowRepository.kt`](app/src/main/java/com/starwindow/app/data/windows/SkyWindowRepository.kt)

Ablage der eingezeichneten Fenster als eine JSON-Datei.

| Element | Art | Aufgabe |
|---|---|---|
| `windows` | `StateFlow<List<SkyWindow>>` | Alle Fenster, neueste (`capturedAtMillis`) zuerst. |
| `load()` | suspend fun | Datei lesen (IO, `Mutex`). |
| `save(window)` | suspend fun | Ersetzt ein Fenster gleicher `id` oder fügt es hinzu, sortiert, schreibt. |
| `delete(id)` | suspend fun | Entfernt und schreibt. |
| `find(id)` | fun | Momentaufnahme aus dem StateFlow. |
| `exportJson()` | suspend fun | Aktuelle Liste als JSON-String (derzeit ohne Aufrufer). |

**Funktionsweise:** Datei `filesDir/sky_windows.json`, Inhalt eine JSON-Liste von `SkyWindow` (`prettyPrint`, `encodeDefaults`, `ignoreUnknownKeys`); die Fensterform ist polymorph serialisiert (Format siehe `core/geometry`). Schreiben über `sky_windows.json.tmp` + `renameTo`, Rückfall: Inhalt in die Zieldatei schreiben (nicht atomar). Defekte Datei → `sky_windows.json.corrupt`, leere Liste, `Log.w`.

**Zusammenspiel/Fallstricke:** `CaptureViewModel` speichert neue Fenster, `WindowListViewModel.delete` löscht und ruft dabei `trackingStore.clearWindow(id)`. Wie bei den Favoriten muss jedes ViewModel `load()` selbst aufrufen; `find` liefert vor dem ersten `load()` `null`. Tests: `SerializationTest` (jede Fensterform übersteht die Rundreise, Diskriminator ist der lesbare Formname, „a list of windows round trips like the repository stores it").

#### Typische Änderungen in diesem Bereich
- **Neue Einstellung:** Feld mit Default in `Settings` → Setter in `SettingsStore` (über `update`) → im ViewModel aus `settingsStore.settings` lesen und den Setter aufrufen. Keine Migration nötig, solange der Default sinnvoll ist.
- **Neues Feld in `SkyWindow`:** in `core/geometry` mit Default ergänzen; `SkyWindowRepository` braucht keine Änderung. Bestehende Dateien lesen weiter.
- **Fensterexport anbieten:** `exportJson()` existiert bereits und muss nur von einer UI (z. B. Teilen-Intent) aufgerufen werden.

<a id="pkg-data-tracking"></a>

### `data/tracking` — Verfolgtes Ziel des Suchers

„Verfolgen" heißt: Der Sucher (Kamera-Bildschirm) zeigt einen Pfeil am Bildrand zu genau **einem** Ziel – einem Katalogobjekt oder einem gespeicherten Fenster – und zeichnet es ein, sobald es im Bild ist. Optional wird für ein Objekt zusätzlich die Bahn über eine Zeitspanne (eine geplante Nacht) gezeichnet. Das Paket hält dieses eine Ziel persistent.

**Android-Abhängigkeiten:** `TrackTarget.kt` nein; `TrackingStore.kt` ja (SharedPreferences, `Log`). **Benutzt:** `core/geometry` (`SkyWindow`, `WindowShape`), `data/catalog`. **Wird benutzt von:** `CaptureViewModel`/`CaptureScreen`/`SkyOverlay` (Anzeige), `ObjectDetailViewModel` (Verfolgen/Beenden), `ObjectSearchViewModel` (Markierung), `WindowListViewModel` und `WindowDetailViewModel` (Fenster verfolgen, beim Löschen aufräumen), `data/planning/PlannedPath` („Pfad zeigen" aus der Planung).

#### [`TrackTarget.kt`](app/src/main/java/com/starwindow/app/data/tracking/TrackTarget.kt)

Versiegelte, serialisierbare Hierarchie der möglichen Ziele.

| Element | Art | Aufgabe |
|---|---|---|
| `TrackTarget` | sealed interface | `label` (Anzeige in der Statusleiste), `id` (zum Markieren in Listen). |
| `TrackedObject` | data class, `@SerialName("object")` | Komplettes `SkyObject` (`obj`) plus optional `pathFromMillis`/`pathToMillis` (UTC-ms) und `pathLabel` (z. B. „Nacht auf Fr, 16. Okt"); `hasPath`, `type`. |
| `TrackedWindow` | data class, `@SerialName("window")` | `windowId`, `name`, `shape` (`WindowShape`, horizontfest); `of(window)` baut es aus einem `SkyWindow`. |

**Fallstricke:** Gespeichert wird die Zeitspanne, nicht die Bahnpunkte – die Bahn wird im Sucher neu berechnet (`SkyTrackBuilder.overSpan`; siehe `PlannedPathTest`). Das polymorphe JSON trägt den Diskriminator `"type"` mit `"object"`/`"window"`; diese Namen nicht ändern, sonst sind gespeicherte Ziele unlesbar (sie würden dann verworfen, s. u.).

#### [`TrackingStore.kt`](app/src/main/java/com/starwindow/app/data/tracking/TrackingStore.kt)

| Element | Art | Aufgabe |
|---|---|---|
| `target` | `StateFlow<TrackTarget?>` | Aktuelles Ziel oder `null`. |
| `current` | Property | `target.value`. |
| `track(target)` | fun | Speichert und setzt das Ziel (ersetzt ein vorheriges). |
| `clear()` | fun | Entfernt das Ziel. |
| `clearWindow(windowId)` | fun | Entfernt das Ziel nur, wenn es genau dieses Fenster ist (beim Löschen eines Fensters). |

**Funktionsweise:** SharedPreferences `starwindow_tracking`, Schlüssel `target_json`, Wert das ganze `TrackTarget` als JSON (`encodeDefaults = true`, `ignoreUnknownKeys = true`). Es wird das komplette Objekt bzw. die komplette Fensterform gespeichert, nicht nur eine ID – so bleibt das Ziel auch bei Katalogänderung oder während die Fensterliste lädt gültig. Gelesen wird synchron im Konstruktor (also beim Erzeugen des `AppContainer` auf dem Main-Thread, DEV_PLAN §11.3 P6). Unlesbares JSON wird geloggt, der Schlüssel gelöscht und ohne Ziel gestartet. Schreiben per `prefs.edit { }` (asynchrones `apply`). Kein Mutex – alle Aufrufe kommen vom Main-Thread.

**Ändern/Fallstricke:** Weil ein Snapshot des `SkyObject` gespeichert wird, zeigt ein verfolgtes Objekt nach einem Katalog-Neuimport die alten Daten, bis es neu verfolgt wird. Ein neuer Zieltyp braucht eine weitere `@Serializable`-Klasse mit eigenem `@SerialName` und Anpassungen in `CaptureScreen`/`SkyOverlay`.

<a id="pkg-data-favorites"></a>

### `data/favorites` — Favoriten („Herz")

Speichert die Objekt-IDs, die der Nutzer als Favorit markiert hat – bewusst getrennt von der Merkliste (`WatchlistRepository`), die Bedingungen und Alarme trägt. Ein Favorit ist nur eine Markierung zum Sortieren/Hervorheben.

**Android-Abhängigkeiten:** ja (`Context.filesDir`, `android.util.Log`). **Benutzt:** kotlinx.serialization, Coroutines. **Wird benutzt von:** `ObjectDetailViewModel` (Umschalten, Anzeige), `ObjectSearchViewModel` (Favoritenfilter), `PhotoGuideViewModel` und `PhotoGuideScreen`.

#### [`FavoritesRepository.kt`](app/src/main/java/com/starwindow/app/data/favorites/FavoritesRepository.kt)

| Element | Art | Aufgabe |
|---|---|---|
| `favorites` | `StateFlow<List<String>>` | IDs der Favoriten, zuletzt hinzugefügte zuerst. |
| `load()` | suspend fun | Liest die Datei (IO, unter `Mutex`) in den StateFlow. |
| `toggle(objectId)` | suspend fun | Fügt vorne hinzu bzw. entfernt; schreibt die Datei, aktualisiert den Flow; gibt zurück, ob das Objekt jetzt Favorit ist. |
| `isFavorite(objectId)` | fun | Momentaufnahme aus `favorites.value` (nicht reaktiv). |

**Funktionsweise:** Datei `filesDir/favorites.json`, Inhalt ein JSON-Array von Strings (Objekt-IDs, z. B. `["M31","HR 7001"]`). Schreiben atomar über `favorites.json.tmp` + `renameTo`; schlägt das Umbenennen fehl, wird kopiert (`copyTo(overwrite = true)`) und die Temp-Datei gelöscht. Eine unlesbare Datei wird nach `favorites.json.corrupt` umbenannt und mit leerer Liste weitergemacht.

**Ändern/Fallstricke:** Es gibt keinen automatischen Ladevorgang – jedes ViewModel ruft `load()` selbst auf (DEV_PLAN §11.4 S5). `load()` überschreibt den Zustand ungeprüft mit dem Dateiinhalt. Die IDs hängen an `SkyObject.id`; ändert ein Neuimport eine ID, verwaist der Favorit (er wird dann einfach nicht mehr gefunden). `isFavorite` in einem Composable (so in `PhotoGuideScreen`) löst bei Änderung keine Neukomposition aus. Kein eigener Test.

<a id="pkg-data-images"></a>

### `data/images` — Himmelsbilder (hips2fits)

Lädt Vorschaubilder eines Himmelsausschnitts vom CDS-Dienst hips2fits, mit Speicher- und Plattencache. Bewusst ohne Bildbibliothek (Coil o. ä.).

**Android-Abhängigkeiten:** ja (`Bitmap`, `BitmapFactory`, `Log`). **Benutzt:** `HttpURLConnection`, Coroutines. **Wird benutzt von:** `ui/components/SkyImage.kt` (`rememberSkyImage`), darüber `StargazingHubScreen` (384 bzw. 768 px), `PhotoGuideScreen` (512 px), `ObjectInfoSheet` (512 px); erzeugt im `AppContainer` mit `context.cacheDir`.

#### [`SkyImageLoader.kt`](app/src/main/java/com/starwindow/app/data/images/SkyImageLoader.kt)

| Element | Art | Aufgabe |
|---|---|---|
| `SkyImageRequest` | data class | `raDeg`, `decDeg`, `fieldOfViewDeg` (Bildbreite in Grad), `sizePx` (Default 512, quadratisch). |
| `SkyImageRequest.cacheKey` | Property | `"%.4f_%.4f_%.4f_%d"` (Locale.ROOT), Punkte durch `-` ersetzt – Dateiname und Speicherschlüssel. |
| `SkyImageLoader(cacheDir, baseUrls, survey)` | class | Lader mit Caches und Ausweichadresse. |
| `load(request)` | suspend fun | `Result<Bitmap>`: Speicher → Platte → Netz; wirft nie. |
| `urlFor(request, baseUrl)` | fun | Baut die Abruf-URL (öffentlich für Tests). |
| `clearCache()` | fun | Leert Speicher- und Plattencache (derzeit ohne Aufrufer). |
| `DEFAULT_BASE_URLS` | val | `https://alasky.cds.unistra.fr/hips-image-services/hips2fits`, `https://alaskybis.cds.unistra.fr/hips-image-services/hips2fits`. |
| `DEFAULT_SURVEY` | const | `"CDS/P/DSS2/color"`. |
| `frameForObject(sizeArcmin)` | fun | Bildbreite: `(sizeArcmin ?: 10) · 2,2 / 60`, begrenzt auf 0,15°–5°. |

**Funktionsweise:**
1. `load` läuft auf `Dispatchers.IO`. Zuerst `memoryCache` (LRU-`LinkedHashMap` mit `accessOrder = true`, max. `MEMORY_CACHE_SIZE = 32` Bitmaps, Zugriffe in `synchronized`).
2. Plattencache: `cacheDir/sky_images/<cacheKey>.jpg`. Existiert die Datei und ist nicht leer, wird sie dekodiert; ist sie nicht dekodierbar, wird sie gelöscht. Keine Größenbegrenzung – Android darf `cacheDir` selbst leeren.
3. Netz: `download` probiert die Adressen ab `preferredHost` (`@Volatile`, merkt sich die zuletzt erfolgreiche). Weitergeschaltet wird nur bei `IOException` (Netzfehler, und HTTP ≥ 500 als `ServiceUnavailable`); bei 4xx wirft `fetch` eine `IllegalStateException`, die sofort abbricht.
4. URL-Parameter: `hips=<survey URL-codiert>&ra=&dec=&fov=` (5 Nachkommastellen) `&width=&height=<sizePx>&projection=TAN&format=jpg`. Timeouts: Verbindung 5 s, Lesen 20 s; User-Agent `StarWindow/0.1 (Android)`; Redirects werden verfolgt.
5. Die Antwort wird direkt in die Cachedatei gestreamt, dann dekodiert und in den Speichercache gelegt; bei jedem Fehler wird die Datei gelöscht und mit `Log.i` protokolliert.

**Ändern/Fallstricke:** Der Kommentar spricht von „ein paar hundert Kilobyte" Speichercache; bei 32 Bitmaps bis 768 px sind es laut DEV_PLAN §11.3 P6 eher 20–40 MB. Die Anfrage nutzt die J2000-Koordinaten aus `SkyObject` (passend für hips2fits/ICRS). Abgebrochene Composables brechen den Download nicht ab. Wer Coil einführen will, ersetzt diese Klasse und `rememberSkyImage`. Tests: `SerializationTest` → `SkyImageRequestTest` (URL enthält Koordinaten und Rahmen, beide Endpunkte bekannt, gleiche Anfrage auf jedem Endpunkt, Cache-Schlüssel dateisystemtauglich und unterscheidend, `frameForObject`-Grenzen).

<a id="pkg-data-gear"></a>

### `data/gear` — Smart-Teleskope

Statische Gerätedaten für den Fotoguide: welche Smart-Teleskope die App kennt und welche Kennzahlen (Bildfeld, Pixelskala, Belichtungsstufen, Filter) in die Empfehlung eingehen. Keine Persistenz – gewählt wird über `Settings.telescopeId`.

**Android-Abhängigkeiten:** nein. **Benutzt:** nichts. **Wird benutzt von:** `domain/PhotoGuide` (über `PhotoGuideViewModel`), `ui/guide/*`.

#### [`SmartTelescope.kt`](app/src/main/java/com/starwindow/app/data/gear/SmartTelescope.kt)

| Element | Art | Aufgabe |
|---|---|---|
| `FilterKind` | enum | `NONE`, `BROADBAND` („Breitband-LP"), `DUAL_BAND` („Dualband (Hα/O III)"), mit `label`. |
| `SmartTelescope` | data class | `id`, `brand`, `model`, `apertureMm`, `focalLengthMm`, `fovLongDeg`/`fovShortDeg` (Bildfeldkanten in Grad), `pixelScaleArcsec` (″/px), `subExposuresSec` (wählbare Einzelbelichtungen, aufsteigend), `builtInFilter`, `hasMosaic`, `hasDewHeater`. |
| `name` / `fovAreaSquareDeg` | Property | `"$brand $model"` / Bildfeldfläche in Quadratgrad. |
| `lightGrasp` | Property | Lichtsammelvermögen relativ zu 50 mm Öffnung (Seestar S50): `(apertureMm / 50)²`. |
| `objectPixels(sizeArcmin)` | fun | Objektgröße in Pixeln: `sizeArcmin·60 / pixelScaleArcsec`. |
| `fillFraction(sizeArcmin)` | fun | Anteil der **kurzen** Bildfeldkante. |
| `SmartTelescopes` | object | Gerätedaten: `SEESTAR_S30`, `SEESTAR_S30_PRO`, `SEESTAR_S50`, `DWARF_3`, `DWARF_MINI`; `ALL`, `DEFAULT = SEESTAR_S50`, `byId(id)` (unbekannt/`null` → `DEFAULT`), `narrowestFovDeg` (derzeit ohne Aufrufer). |

Gerätewerte (Öffnung / Brennweite / Bildfeld / ″/px / Belichtungen / Taubeheizung): S30 30 mm/150 mm/2,13°×1,20°/4,0/10–60 s/ja; S30 Pro 30/160/3,98×2,26/3,75/10–60 s/ja; S50 50/250/1,29×0,73/2,39/10–30 s/ja; DWARF 3 35/150/2,93×1,65/2,75/15–90 s/nein; DWARF Mini 30/150/2,13×1,20/4,0/15–90 s/nein. Alle mit Dualband-Filter und Mosaikmodus.

**Ändern/Fallstricke:** Neues Gerät = neue Konstante + Aufnahme in `ALL`; die `id` wird in `Settings.telescopeId` gespeichert und darf sich nicht ändern. `PhotoGuideTest` prüft u. a., dass empfohlene Einzelbelichtungen in `subExposuresSec` vorkommen, die Rahmungsurteile (S50 braucht für Orion Mosaik, S30 Pro nicht) und den Taukappen-Hinweis beim DWARF 3.

<a id="pkg-data-weather"></a>

### `data/weather` — Open-Meteo, Wettermodelle, Vorhersage-Cache

Dieses Paket holt Wettervorhersagen, Modellzustände und Ortstreffer von Open-Meteo (ohne HTTP-Bibliothek, über `HttpURLConnection`), wandelt sie in die App-Modelle `WeatherForecast`/`WeatherHour`/`WeatherPlace` und hält sie in zwei Caches (Arbeitsspeicher in `WeatherRepository`, Platte in `ForecastCache`). `WeatherModel` legt die sechs wählbaren Modelle fest. `NightWeatherSource` ist der schmale Zugang für die Receiver (Erinnerung, Merkliste), `PlaceLookup` macht aus einer GPS-Position einen benannten Ort mit Einwohnerzahl (für die Bortle-Schätzung). Zentraler Einstieg: `WeatherRepository` aus dem `AppContainer`.

**Android-Abhängigkeiten:** teilweise. `OpenMeteo`, `WeatherModel`, `WeatherModels` sind reines Kotlin/JVM; `WeatherRepository` und `ForecastCache` nur `android.util.Log`; `PlaceLookup` nutzt `android.location.Geocoder`; `NightWeatherSource` braucht `Context` (über `SettingsStore`). **Benutzt:** `core/astro.ObserverLocation`, `data/windows.SettingsStore`, `domain.NightOutlook`/`NightOutlooks` (Zyklus, DEV_PLAN S3), kotlinx.serialization, Coroutines. **Wird benutzt von:** `AppContainer` (`forecastCache`, `weatherRepository`, `placeLookup`), `ui/weather/WeatherViewModel` (+ `ModelPicker`), `ui/planning/CalendarViewModel`, `ui/guide/PhotoGuideViewModel`, `data/planning` (`ReminderReceiver`, `WatchCheckReceiver`), `domain` (`AstroWeather`, `NightOutlook`, `BortleScale` u. a. auf den Modellklassen), `SettingsStore` (speichert `WeatherPlace` und `WeatherModel`).

#### [`WeatherModels.kt`](app/src/main/java/com/starwindow/app/data/weather/WeatherModels.kt)

Die Datenmodelle für Ort, Stundenwert und Modelllauf (trotz des Namens nicht die Modellliste – die steht in `WeatherModel.kt`).

| Element | Art | Aufgabe |
|---|---|---|
| `WeatherPlace` | `@Serializable data class` | `name`, `region?`, `country?`, `latitudeDeg`, `longitudeDeg`, `elevationM` (0), `timezoneId?` (IANA), `population?`, `populationDistanceKm` (0.0), `bortleOverride: Int?` |
| `label` / `shortLabel` | Properties | „Name, Region, Land" ohne Doppelungen / „Name, Land" |
| `zone` | Property | `ZoneId.of(timezoneId)` oder `null` |
| `toObserverLocation()` / `fromObserver(location, name)` | fun | Umwandlung von/nach `ObserverLocation` (`manual = true`; Vorgabename „Aktuelle Position") |
| `WeatherHour` | `@Serializable data class` | `millis` (UTC-Epoch-ms) plus optionale Werte: `cloudTotalPercent`, `cloudLow/Mid/HighPercent`, `temperatureC`, `dewPointC`, `humidityPercent`, `windSpeedKmh`, `windGustsKmh`, `precipitationMm`, `pressureHpa`, `jetStreamKmh` (Wind auf 250 hPa als Seeing-Indikator) |
| `effectiveCloudPercent` | Property | Gesamtbedeckung, sonst die **dichteste** der drei Schichten |
| `dewSpreadK` | Property | Temperatur − Taupunkt |
| `WeatherForecast` | data class (nicht serialisierbar wegen `ZoneId`) | `place`, `zone`, `model`, `hours`, `fetchedAtMillis`; `isEmpty`, `firstMillis`, `lastMillis` |

**Ändern/Fallstricke:** Alle `WeatherHour`-Werte sind nullable mit Vorgabe `null` – fehlend ≠ 0. So bleiben alte Cache-Einträge lesbar. `WeatherPlace` wird von `SettingsStore` gespeichert; neue Felder brauchen Vorgaben. Der KDoc zu `populationDistanceKm` spricht von „null", der Typ ist aber `Double` mit Vorgabe `0.0`. Abgesichert: `AstroWeatherTest` (`WeatherHourTest`: Taupunktdifferenz, Rückfall auf dichteste Schicht, keine Bewölkung → `null`).

#### [`WeatherModel.kt`](app/src/main/java/com/starwindow/app/data/weather/WeatherModel.kt)

Die Liste der wählbaren Wettermodelle mit festen Eigenschaften, dazu Modellgebiet und Laufzustand.

| Element | Art | Aufgabe |
|---|---|---|
| `WeatherModel` | `@Serializable enum` | Felder: `id` (Wert für `models=`), `metaPath` (Pfad zu `meta.json`), `label`, `provider`, `resolutionKm` (Maschenweite), `coverage` (Text), `about`, `requestDays` (angefragte Tage) |
| `resolutionLabel` | Property | „2,2 km" bzw. „25 km" |
| `DEFAULT` | companion `val` | `ECMWF_IFS` |
| `ORDERED` | companion `val` | nach `resolutionKm` aufsteigend (Reihenfolge im Picker) |
| `ModelBounds` | data class | `min/maxLatitudeDeg`, `min/maxLongitudeDeg`; `contains(lat, lon)` |
| `WeatherModelStatus` | data class | `runMillis`, `availableSinceMillis`, `dataEndMillis`, `updateIntervalSeconds`, `stepSeconds`, `bounds`; `covers()` (ohne Grenzen `true`), `ageMillis()`, `isOverdue()` (Alter > 2 × Intervall) |

Die sechs Modelle:

| Enum | `id` | `metaPath` | Anbieter | km | `requestDays` | Gebiet |
|---|---|---|---|---|---|---|
| `ICON_CH1` | `meteoswiss_icon_ch1` | gleich | MeteoSchweiz | 1,0 | 2 | Alpenraum |
| `AROME_HD` | `meteofrance_arome_france_hd` | gleich | Météo-France | 1,5 | 3 | Frankreich, Benelux, SW-Deutschland (keine Gesamtbedeckung) |
| `HARMONIE` | `knmi_harmonie_arome_europe` | gleich | KNMI | 2,0 | 3 | Nordwesteuropa |
| `ICON_D2` | `icon_d2` | `dwd_icon_d2` | DWD | 2,2 | 3 | Deutschland + Nachbarn |
| `ICON_EU` | `icon_eu` | `dwd_icon_eu` | DWD | 7,0 | 6 | Europa |
| `ECMWF_IFS` | `ecmwf_ifs025` | gleich | ECMWF | 25 | 15 | global |

**Ändern/Fallstricke:** Das Enum wird über den Kotlin-Namen serialisiert (in `SettingsStore` und `forecast_cache.json`) – Umbenennen bricht gespeicherte Auswahl und Cache. `requestDays` ist nur eine Sparmaßnahme; was wirklich vorliegt, ergibt `OpenMeteo.trimTrailingGap`. Die Gebietsgrenzen stehen **nicht** hier fest, sondern kommen live aus `meta.json`. Plausibilität aller Modelle prüft `AstroWeatherTest` (`OpenMeteoTest` „every model has a plausible definition"), Laufalter „the run age is measured from its publication".

#### [`OpenMeteo.kt`](app/src/main/java/com/starwindow/app/data/weather/OpenMeteo.kt)

Reiner URL-Bau und Antwort-Parser für Open-Meteo, ohne Netz – damit testbar.

| Element | Art | Aufgabe |
|---|---|---|
| `FORECAST_URL` | const | `https://api.open-meteo.com/v1/forecast` |
| `GEOCODING_URL` | const | `https://geocoding-api.open-meteo.com/v1/search` |
| `META_URL` | const | `https://api.open-meteo.com/data` |
| `SOURCE_LABEL` / `MAX_FORECAST_DAYS` | const | `open-meteo.com` / 15 |
| `forecastUrls(lat, lon, model)` | fun | zwei URLs: erst mit `FULL_VARIABLES`, dann mit `CORE_VARIABLES` |
| `forecastUrl(lat, lon, model, variables)` | fun | eine Vorhersage-URL |
| `metaUrl(model)` | fun | `…/data/<metaPath>/static/meta.json` |
| `geocodingUrl(query, count = 8)` | fun | Ortssuche |
| `parseForecast(body, place, model)` | fun | JSON → `WeatherForecast` |
| `parseModelStatus(body, model)` | fun | `meta.json` → `WeatherModelStatus` |
| `parsePlaces(body)` | fun | Geocoding-JSON → `List<WeatherPlace>` (leer bei keinem Treffer) |

**Funktionsweise:**
- **Vorhersage-URL:** `latitude`/`longitude` mit 5 Nachkommastellen (`Locale.ROOT`), `hourly=<Variablen kommagetrennt>`, `models=<model.id>`, `wind_speed_unit=kmh`, `timezone=auto`, `timeformat=unixtime`, `forecast_days=<requestDays, begrenzt 1…15>`. `FULL_VARIABLES`: `cloud_cover`, `cloud_cover_low/mid/high`, `temperature_2m`, `dew_point_2m`, `relative_humidity_2m`, `precipitation`, `wind_speed_10m`, `wind_gusts_10m`, `pressure_msl`, `wind_speed_250hPa`. `CORE_VARIABLES` (Rückfall, weil Open-Meteo die ganze Anfrage ablehnt, wenn eine Variable fehlt): die vier Bewölkungen, `temperature_2m`, `dew_point_2m`, `precipitation`, `wind_speed_10m`.
- **Parser:** `error == true` → Ausnahme mit `reason`; kein `hourly` → Ausnahme. Je Index von `hourly.time` (Sekunden UTC → ×1000) wird ein `WeatherHour` gebaut; jede Spalte wird per `getOrNull(index)` gelesen, d. h. fehlende Spalten und `null`-Einträge bleiben `null` (Lücken bleiben Lücken). `elevation` und `timezone` der Antwort überschreiben die Werte des Orts (Höhe des Gitterpunkts). Zone: Name aus der Antwort → `place.zone` → fester `utc_offset_seconds` → Gerätezone. `fetchedAtMillis = System.currentTimeMillis()`.
- **Kurzfristmodell-Abschnitt (`trimTrailingGap`):** Open-Meteo füllt angefragte Stunden nach Modellende mit leeren Werten; abgeschnitten wird alles nach der letzten Stunde mit `effectiveCloudPercent != null`. Ganz ohne Bewölkung → leere Liste (wertet `WeatherRepository` als „außerhalb des Modellgebiets").
- **meta.json:** `last_run_initialisation_time`, `last_run_availability_time`, `data_end_time` (Sekunden → ms), `update_interval_seconds`, `temporal_resolution_seconds`, `crs_wkt`. Das Gebiet wird per Regex `BBOX[a, b, c, d]` aus dem WKT gelesen, Reihenfolge minLat, minLon, maxLat, maxLon; fehlt/ändert sich das Format → `bounds = null`.
- **Geocoding:** `name=<URL-kodiert, getrimmt>`, `count` (1…20), `language=de`, `format=json`; übernommen werden `name`, `admin1` → `region`, `country`, Koordinaten, `elevation` (sonst 0), `timezone`, `population`.

**Ändern/Fallstricke:** Neue Größe: in `FULL_VARIABLES` (ggf. `CORE_VARIABLES`), `HourlyDto` (`@SerialName` = Open-Meteo-Name), `WeatherHour` und `parseForecast` gleichzeitig ergänzen. Abgesichert durch `AstroWeatherTest` → `OpenMeteoTest`: URL-Inhalt („the forecast names its model and asks for what it can carry", „a short range model is not asked for two weeks"), meta.json („the metadata says when the model last ran and where it is valid", „metadata without a usable area claims nothing"), Abschneiden („empty hours at the end of a short model are cut off", „a run that is entirely outside its area yields nothing"), Spaltenzuordnung und Lücken („hourly columns are matched to their timestamps", „a gap in a column stays a gap", „a column the run does not carry is simply absent"), Höhe, Fehlermeldung, Ortssuche, Zeitzonen-Rückfall.

#### [`WeatherRepository.kt`](app/src/main/java/com/starwindow/app/data/weather/WeatherRepository.kt)

Netzabruf und Speicher-Cache für Vorhersage, Modellzustand und Ortssuche; nutzt `ForecastCache` als Rückfall.

| Element | Art | Aufgabe |
|---|---|---|
| `OutsideModelAreaException(model)` | class (`IOException`) | Ort liegt außerhalb des Modellgebiets – kein Netzfehler |
| `WeatherRepository(fetch = ::httpGet, diskCache: ForecastCache? = null)` | class | `fetch` ist in Tests austauschbar |
| `forecast(place, model, forceReload = false)` | suspend fun → `Result<WeatherForecast>` | Speicher-Cache → Netz (zwei URLs) → Speicher-/Platten-Cache als Rückfall |
| `lastKnown(place, model, fallbackToAnyModel)` | suspend fun | ohne Netz: Speicher → Platte (gleiches Modell) → Platte (beliebiges Modell, optional) |
| `modelStatus(model, forceReload)` | suspend fun → `Result` | `meta.json` holen, 5 min gecacht |
| `modelStatuses(models = ORDERED)` | suspend fun | alle Zustände parallel (`async`/`awaitAll`), fehlgeschlagene fehlen in der Map |
| `searchPlaces(query)` | suspend fun → `Result` | Ortssuche; leere Eingabe → leere Liste ohne Abruf |
| `cached(place, model)` | fun | nur Speicher-Cache |

**Funktionsweise:**
- Alle öffentlichen suspend-Funktionen laufen in `withContext(Dispatchers.IO)`.
- **Speicher-Cache:** `LinkedHashMap` mit Schlüssel `"<model.id>_<lat %.3f>_<lon %.3f>"`, höchstens `MAX_CACHED_PLACES = 8` Einträge (älteste fliegt), gültig `CACHE_TTL_MILLIS = 30 min` ab `fetchedAtMillis`. `statusCache` (`HashMap`) gültig `STATUS_TTL_MILLIS = 5 min`.
- **`forecast`:** frischer Speicher-Eintrag und kein `forceReload` → sofort zurück. Sonst nacheinander beide URLs aus `OpenMeteo.forecastUrls`; leere Vorhersage → `OutsideModelAreaException`, nächste URL; Erfolg → `remember` + `diskCache.store` → `success`. `CancellationException` wird weitergereicht, andere Fehler geloggt. Nach zwei Fehlschlägen (außer „außerhalb des Gebiets"): veralteter Speicher-Eintrag, dann `diskCache.load` (gleiches Modell). Sonst `Result.failure`.
- **`httpGet`:** `HttpURLConnection`, `CONNECT_TIMEOUT_MS = 8 000`, `READ_TIMEOUT_MS = 15 000`, Redirects an, `User-Agent: StarWindow/0.1 (Android)`, `Accept: application/json`. Bei Status außerhalb 200–299 wird der Fehlerstrom gelesen und `"reason"` per Regex als Fehlermeldung übernommen.

**Zusammenspiel:** `WeatherViewModel` (`forecast`, `modelStatus`/`modelStatuses`, `searchPlaces`), `CalendarViewModel` (`forecast` für die Wetterzeile der Termine), `PhotoGuideViewModel` (`lastKnown` mit `fallbackToAnyModel`), `NightWeatherSource` (`lastKnown`, `forecast`), `PlaceLookup` (`searchPlaces`).

**Ändern/Fallstricke:** `cache` und `statusCache` sind nicht synchronisiert, werden aber auf dem IO-Pool genutzt – `modelStatuses` schreibt `statusCache` aus parallelen Koroutinen. Die Receiver bauen sich über `NightWeatherSource.forContext` eine **eigene** Instanz mit leerem Speicher-Cache (nur die Platte ist geteilt). Der Abruf ist blockierend; ein `withTimeoutOrNull` des Aufrufers bricht ihn nicht sofort ab, im schlimmsten Fall dauert `forecast` etwa 2 × (8 + 15) s. Es gibt keine Unit-Tests für `WeatherRepository` selbst; getestet ist der Parser.

#### [`ForecastCache.kt`](app/src/main/java/com/starwindow/app/data/weather/ForecastCache.kt)

Platten-Cache der zuletzt geholten Modellläufe, damit Receiver und Offline-Ansicht etwas zu sagen haben.

| Element | Art | Aufgabe |
|---|---|---|
| `ForecastCache(file)` | class | Datei wird von außen übergeben (`filesDir/forecast_cache.json`) |
| `store(forecast)` | suspend fun | Eintrag für (Ort, Modell) ersetzen, neuester vorn, höchstens 3 Einträge; Fehler nur loggen |
| `load(place, model)` | suspend fun | Eintrag für genau diesen Ort + dieses Modell |
| `loadAny(place)` | suspend fun | erster Eintrag für den Ort, egal welches Modell |
| `clear()` | suspend fun | Datei löschen (derzeit kein Aufrufer im Hauptcode gefunden) |
| `Entry` | private `@Serializable data class` | `key`, `place`, `model`, `zoneId` (String), `fetchedAtMillis`, `hours` |
| `FILE_NAME` / `MAX_ENTRIES` | const | `forecast_cache.json` / 3 |

**Funktionsweise:** Format: JSON-Array von `Entry`, ohne `prettyPrint`, `explicitNulls = false` (fehlende Werte als fehlende Schlüssel), `encodeDefaults = false`, `ignoreUnknownKeys`. Schlüssel `"<lat %.3f>_<lon %.3f>_<model.id>"` (Achtung: andere Reihenfolge als der Speicher-Cache-Schlüssel). Schreiben über `<name>.tmp` + `renameTo`, Rückfall wie bei den Repositories. Eine unlesbare Datei wird **gelöscht** (nicht umbenannt). `toForecast` löst die Zone auf: gespeicherte Kennung → `place.zone` → Gerätezone. Alles auf `Dispatchers.IO` unter einem `Mutex` (pro Instanz – Receiver-Instanzen haben eigene Mutexe auf dieselbe Datei).

**Zusammenspiel:** Geschrieben von `WeatherRepository.forecast` nach jedem erfolgreichen Abruf, gelesen von `WeatherRepository.forecast` (Rückfall) und `lastKnown`.

#### [`NightWeatherSource.kt`](app/src/main/java/com/starwindow/app/data/weather/NightWeatherSource.kt)

Bündelt Einstellungen und Wetterzugriff für die Receiver und liefert direkt ein `NightOutlook` für eine Nacht.

| Element | Art | Aufgabe |
|---|---|---|
| `NightWeatherSource(settings, repository)` | class | |
| `place` | Property | `settings.current.weatherPlace`, sonst `manualLocation` als `WeatherPlace`; sonst `null` (kein GPS) |
| `hasPlace` | Property | `place != null` |
| `stored(date, nowMillis)` | suspend fun | `repository.lastKnown(place, settings.weatherModel, fallbackToAnyModel = true)` → Outlook |
| `fresh(date, nowMillis)` | suspend fun | `repository.forecast(place, settings.weatherModel)` (inkl. dessen Platten-Rückfall) → Outlook |
| `forContext(context)` | companion fun | neue Instanz aus `SettingsStore(appContext)` + `WeatherRepository(diskCache = ForecastCache(filesDir/forecast_cache.json))` |

**Funktionsweise:** Der Outlook entsteht in `NightOutlooks.forDate(forecast, place.toObserverLocation(), date, zone = place.zone ?: forecast.zone, nowMillis)`; `null`, wenn der Lauf die Nacht nicht abdeckt.

**Zusammenspiel:** `ReminderReceiver.enrich`, `WatchCheckReceiver.check`. Die Bewertung (`NightOutlook`, `headline`, `source`, `verdict`) liegt in `domain/NightOutlook.kt` (Tests: `NightOutlookTest`).

**Ändern/Fallstricke:** Wird nicht aus dem `AppContainer` bezogen, sondern pro Broadcast neu gebaut. Wer die Ortswahl der Benachrichtigungen ändern will, ändert `place`.

#### [`PlaceLookup.kt`](app/src/main/java/com/starwindow/app/data/weather/PlaceLookup.kt)

Macht aus einer GPS-Position einen benannten `WeatherPlace` mit Einwohnerzahl und Entfernung zur Ortschaft (Grundlage der Bortle-Schätzung).

| Element | Art | Aufgabe |
|---|---|---|
| `PlaceLookup(context, repository)` | class | |
| `describe(location): WeatherPlace` | suspend fun | schlägt nie fehl; auf `Dispatchers.IO` |
| `MAX_MATCH_DISTANCE_KM` | const | 50 km |

**Funktionsweise:** (1) `reverseGeocode`: Android-`Geocoder` (`Locale.GERMANY`, synchrone, ab API 33 abgekündigte `getFromLocation`), Name = `locality ?: subAdminArea ?: adminArea`; ohne Geocoder/Treffer → `WeatherPlace.fromObserver(location)`. (2) `repository.searchPlaces(name)` (Open-Meteo-Geocoding), Treffer innerhalb 50 km, nächster gewinnt; keiner → Position mit dem Geocoder-Namen. (3) Ergebnis: Name, Region, Land, Zeitzone, Einwohnerzahl vom Treffer, **Koordinaten und Höhe von der eigenen Position**, `populationDistanceKm` = Abstand.

**Zusammenspiel:** `WeatherViewModel.useCurrentLocation` (über `AppContainer.placeLookup`). Keine Unit-Tests.

#### Typische Änderungen in diesem Bereich
- **Neues Wettermodell aufnehmen:** Eintrag in `WeatherModel` mit `id` (Open-Meteo-`models=`-Wert) und `metaPath` (Pfad unter `/data/…/static/meta.json`, kann vom `id` abweichen, vgl. `icon_d2` → `dwd_icon_d2`), `resolutionKm`, `coverage`, `about`, `requestDays` (≤ 15). `ORDERED` sortiert automatisch. Prüfen, ob das Modell alle `FULL_VARIABLES` führt (sonst greift der `CORE_VARIABLES`-Rückfall) und ob es eine Gesamtbedeckung hat (sonst `effectiveCloudPercent`-Rückfall). `OpenMeteoTest` „every model has a plausible definition" laufen lassen.
- **Neue Wettergröße abfragen:** Open-Meteo-Name in `OpenMeteo.FULL_VARIABLES` (nur bei Kernbedarf auch in `CORE_VARIABLES`), Spalte in `HourlyDto` mit `@SerialName`, nullable Feld mit Vorgabe `null` in `WeatherHour`, Zuordnung in `parseForecast`. Auswertung in `domain/AstroWeather` bzw. Anzeige in `ui/weather`. Alte Cache-Einträge bleiben lesbar (fehlender Schlüssel → `null`). Test in `OpenMeteoTest` ergänzen.
- **Cache-Dauer ändern:** `WeatherRepository.CACHE_TTL_MILLIS` (Vorhersage im Speicher), `STATUS_TTL_MILLIS` (Modellzustand), `MAX_CACHED_PLACES`; Plattenumfang `ForecastCache.MAX_ENTRIES`. Der Platten-Cache hat **keine** Gültigkeitsdauer; das Alter wird über `fetchedAtMillis` angezeigt (`NightOutlook`).
- **Zeitbudget der Receiver anpassen:** `ReminderReceiver.NETWORK_BUDGET_MILLIS`, `WatchCheckReceiver.NETWORK_BUDGET_MILLIS`; wirklich begrenzend sind die Socket-Timeouts in `WeatherRepository` (`CONNECT_TIMEOUT_MS`, `READ_TIMEOUT_MS`).
- **Ortssuche anpassen (Sprache, Trefferzahl):** `OpenMeteo.geocodingUrl`; Übernahme der Felder in `parsePlaces`/`GeocodeResultDto`.

<a id="pkg-data-planning"></a>

### `data/planning` — Termine, Erinnerungen, Merkliste

Dieses Paket hält alles, was der Nutzer **für später** festlegt: geplante Beobachtungsnächte (`PlannedSession`, gespeichert von `PlanRepository`) und die Merkliste (`WatchedObject`, gespeichert von `WatchlistRepository`). Dazu gehört die Android-Hintergrundmechanik, die daraus Benachrichtigungen macht: `ReminderScheduler`/`ReminderReceiver` für Terminerinnerungen, `WatchScheduler`/`WatchCheckReceiver` für die tägliche Merklisten-Prüfung, `BootReceiver` für das Wiederaufsetzen nach einem Neustart. `PlannedPath` übersetzt einen Termin in einen Bahnbogen für den Sucher. Einstiegspunkte aus der Oberfläche sind `PlanRepository` und `WatchlistRepository` (über `AppContainer`), aus dem System die drei Receiver.

**Android-Abhängigkeiten:** ja (`Context`, `AlarmManager`, `PendingIntent`, `BroadcastReceiver`/`goAsync`, `NotificationManager`/`NotificationChannel`, `NotificationCompat`, `Log`). **Benutzt:** `data/weather` (`NightWeatherSource`), `domain` (`NightOutlook`, `WatchEvaluator`/`WatchHit`, `ObservationPlanner`), `data/catalog` (`SkyObject`, `ObjectType`), `data/tracking` (`TrackedObject`, `trackingStore`), `core/astro` (`ObserverLocation`, `Equatorial`), `AppContainer`, `MainActivity`. **Wird benutzt von:** `StarWindowApplication`/`AppContainer`, `ui/planning/CalendarViewModel`, `ui/planning/PlanningViewModel`, `ui/planning/WatchSheet` (nur `WatchScheduler.CHECK_TIME`), `ui/search/ObjectDetailViewModel`, `ui/nav/StarWindowNavHost` (`PlannedPath`), `domain/WatchEvaluator` und `domain/NightOutlook`-Tests (Modelle `WatchedObject`, `WeatherDemand`). Das ist der in DEV_PLAN §11.4 S3 beschriebene Zyklus data↔domain.

#### [`PlannedSession.kt`](app/src/main/java/com/starwindow/app/data/planning/PlannedSession.kt)

Datenmodell eines Termins: ein Objekt in einer Nacht. Speichert bewusst die beim Planen berechneten Werte mit, damit der Kalender nichts neu rechnen muss.

| Element | Art | Aufgabe |
|---|---|---|
| `PlannedSession` | `@Serializable data class` | Felder: `id` (UUID-String), `objectId` (Katalogkennung, z. B. `M31`), `objectLabel`, `dateEpochDay` (Abend, an dem die Nacht beginnt), `usableMinutes`, `bestAltitudeDeg`, `moonIlluminationPercent`, `note`, `reminders: Set<ReminderLead>`, `createdAtMillis`. Alle außer `id`, `objectId`, `objectLabel`, `dateEpochDay` haben Vorgaben. |
| `date` / `usableHours` / `hasNote` | abgeleitete Properties | `LocalDate.ofEpochDay`, Minuten/60, `note.isNotBlank()` |
| `pendingReminders(zone, nowMillis)` | fun | Vorläufe, deren Auslösezeit noch in der Zukunft liegt, weiteste zuerst |
| `nextReminder(zone, nowMillis)` | fun | der früheste noch ausstehende Vorlauf oder `null` |

**Ändern/Fallstricke:** Das Datum ist ein Epochentag (kein `LocalDate`), damit die Datei zeitzonenunabhängig bleibt. Neue Felder **immer mit Vorgabewert** anlegen, sonst sind alte Dateien nicht mehr lesbar (abgesichert in `PlanningPersistenceTest` „an entry from an older version still loads", `ReminderTest` „an entry without a reminders field still loads"). Die Felder sind eine Momentaufnahme vom Zeitpunkt der Planung und werden später nicht aktualisiert.

#### [`Reminder.kt`](app/src/main/java/com/starwindow/app/data/planning/Reminder.kt)

Feste Auswahl an Erinnerungs-Vorläufen und die Berechnung des Auslösezeitpunkts.

| Element | Art | Aufgabe |
|---|---|---|
| `ReminderLead` | `@Serializable enum` | `ONE_WEEK` (7 Tage, `@SerialName("week")`), `THREE_DAYS` (3, `"threeDays"`), `ONE_DAY` (1, `"day"`), `SAME_DAY` (0, `"sameDay"`); jeweils mit `daysBefore`, `label`, `hint` für die UI |
| `triggerMillis(nightDate, zone)` | fun | `nightDate - daysBefore` um `NOTIFY_TIME` in der übergebenen Zone, als Epoch-ms |
| `NOTIFY_TIME` | `val` (companion) | `17:00` Ortszeit |
| `ordered` | `val` (companion) | Anzeige-Reihenfolge: Woche → 3 Tage → 1 Tag → am Tag |

**Ändern/Fallstricke:** Die `@SerialName`-Werte stehen in `observation_plan.json` – nie umbenennen (`ReminderTest` „the reminder leads serialise under stable names"). In Intents wird dagegen `lead.name` (der Kotlin-Name, z. B. `ONE_WEEK`) übertragen und in `ReminderNotice.from` per `valueOf` zurückgelesen. `ReminderScheduler.requestCode` nutzt `ordinal`, deshalb neue Einträge nur hinten anfügen und höchstens 8 Einträge (siehe dort). Uhrzeit und Tagesberechnung sind in `ReminderTest` (`ReminderLeadTest`) abgesichert.

#### [`PlanRepository.kt`](app/src/main/java/com/starwindow/app/data/planning/PlanRepository.kt)

Persistenz der Termine und zentrale Stelle, die die Alarme mit der Datei synchron hält. Eine Instanz liegt im `AppContainer` (`planRepository`); `PlanReminderSync` erzeugt beim Neustart eine Wegwerf-Instanz.

| Element | Art | Aufgabe |
|---|---|---|
| `PlanRepository(context)` | class | Datei `filesDir/observation_plan.json`, `Mutex`, `MutableStateFlow<List<PlannedSession>>` |
| `sessions` | `StateFlow` | alle Termine, nach `dateEpochDay` sortiert |
| `load()` | suspend fun | Datei lesen → `sessions` |
| `add(session)` | suspend fun | ersetzt einen Eintrag mit gleicher `objectId` + `dateEpochDay`, schreibt, setzt Alarme für `session` neu |
| `remove(id)` / `removeFor(objectId, date)` | suspend fun | Alarme abbrechen, Eintrag entfernen, schreiben |
| `setNote(id, note)` | suspend fun | Notiz ändern **und** Alarme neu setzen (die Notiz steckt im Intent) |
| `toggleReminder(id, lead)` | suspend fun | Vorlauf ein-/ausschalten, Alarme neu setzen |
| `rescheduleAll()` | fun | alle Alarme aus dem aktuellen Zustand neu setzen (idempotent) |
| `ensureNotificationChannel()` | fun | delegiert an `ReminderScheduler.ensureChannel` |
| `forDate`, `isPlanned`, `datesFor` | fun | synchrone Abfragen auf `sessions.value` |
| `newId()` | companion fun | `UUID.randomUUID().toString()` |

**Funktionsweise:** Jede ändernde Methode läuft auf `Dispatchers.IO` unter `mutex.withLock`: neue Liste bilden → `writeFile` → `state.value` setzen → `ReminderScheduler.reschedule`/`cancel`. `writeFile` schreibt nach `observation_plan.json.tmp` und benennt um; schlägt `renameTo` fehl, wird der Inhalt direkt in die Zieldatei kopiert (dann nicht mehr atomar). `readFile` benennt eine nicht lesbare Datei in `observation_plan.json.corrupt` um und liefert eine leere Liste. JSON-Konfiguration: `prettyPrint`, `ignoreUnknownKeys`, `encodeDefaults`. Format: ein JSON-Array von `PlannedSession`.

**Zusammenspiel:** `PlanningViewModel.togglePlan` ruft `add`/`removeFor` (mit `newId()`), `CalendarViewModel` ruft `load`, `rescheduleAll` (beim Öffnen des Kalenders), `toggleReminder`, `setNote`, `remove`, `ensureNotificationChannel` (beim Öffnen eines Termin-Blatts).

**Ändern/Fallstricke:** Jede neue ändernde Methode muss mit `scheduler.reschedule` oder `cancel` enden, sonst laufen Alarm und Datei auseinander. Wird ein Feld geändert, das im Intent steckt (`objectLabel`, `note`, `usableHours`, `dateEpochDay`), muss neu geplant werden. `load()` wird von mehreren ViewModels aufgerufen und überschreibt den Zustand jeweils mit dem Dateistand (DEV_PLAN S5 schlägt vor, dass das Repository selbst einmal lädt). `add` bricht die Alarme eines **ersetzten** Eintrags mit anderer `id` nicht ab; die UI ruft `add` derzeit nur, wenn noch nichts geplant ist. Gemeinsames Speichermuster mit `WatchlistRepository`, `ForecastCache`, `SkyWindowRepository`, `FavoritesRepository` (DEV_PLAN S5). Serialisierung der Liste: `PlanningPersistenceTest`.

#### [`ReminderScheduler.kt`](app/src/main/java/com/starwindow/app/data/planning/ReminderScheduler.kt)

Trägt die Erinnerungen eines Termins in den `AlarmManager` ein bzw. aus und legt den Benachrichtigungskanal für Termine an.

| Element | Art | Aufgabe |
|---|---|---|
| `reschedule(session, zone = systemDefault)` | fun | `cancel` → `ensureChannel` → für jeden Vorlauf mit Auslösezeit in der Zukunft einen Alarm setzen |
| `cancel(session)` | fun | für **alle** `ReminderLead.entries` den PendingIntent suchen (`FLAG_NO_CREATE`), `alarmManager.cancel` + `pending.cancel` |
| `rescheduleAll(sessions, zone)` | fun | `reschedule` für jeden Termin |
| `ensureChannel()` | fun | Kanal `observation_reminders` („Beobachtungstermine", `IMPORTANCE_DEFAULT`) anlegen, falls nicht vorhanden (ab API 26) |
| `requestCode(sessionId, lead)` | companion fun | `sessionId.hashCode() * 8 + lead.ordinal` |
| `CHANNEL_ID`, `ACTION_REMIND` (`com.starwindow.app.REMIND`), `EXTRA_*` | const | Kanal, Intent-Action und Intent-Extras |

**Funktionsweise:** Alarme sind bewusst **inexakt**: `setAndAllowWhileIdle(RTC_WAKEUP, trigger, pi)` – feuert auch im Doze-Modus, aber ohne Minutengenauigkeit; es wird keine `SCHEDULE_EXACT_ALARM`-Berechtigung verlangt. Ausnahmen beim Setzen werden geloggt und geschluckt. Der Broadcast-Intent richtet sich explizit an `ReminderReceiver` und trägt alle Termindaten als Extras: `sessionId`, `objectId`, `label`, `dateEpochDay`, `lead` (`lead.name`), `note`, `hours` (`usableHours`). PendingIntent-Flags: `FLAG_IMMUTABLE` plus `FLAG_UPDATE_CURRENT` (anlegen) bzw. `FLAG_NO_CREATE` (suchen). Zeitzone ist standardmäßig die **Gerätezone**.

**Zusammenspiel:** Aufgerufen von `PlanRepository` (jede Änderung) und `PlanReminderSync` (Neustart). Liefert Intents an `ReminderReceiver`.

**Ändern/Fallstricke:** Wer den Intent um ein Feld erweitert, muss `ReminderNotice.from` anpassen. Die Request-Codes müssen je (Termin, Vorlauf) eindeutig sein – `ReminderTest` (`ReminderSchedulerKeyTest`) prüft verschiedene Vorläufe eines Termins und 500 verschiedene Termine. Der Faktor 8 erlaubt höchstens 8 `ReminderLead`-Einträge. Hinweis: `reschedule` ruft `ensureChannel` auch für Termine ohne Erinnerung auf; der Kanal entsteht also, sobald überhaupt ein Termin existiert und der Kalender geöffnet wird.

#### [`PlanReminderSync.kt`](app/src/main/java/com/starwindow/app/data/planning/PlanReminderSync.kt)

Kleines `object` für den Neustart: liest den Plan von der Platte und setzt alle Alarme neu, ohne `AppContainer`.

| Element | Art | Aufgabe |
|---|---|---|
| `PlanReminderSync.rescheduleAll(context, onFinished)` | fun | auf eigenem `CoroutineScope(SupervisorJob() + Dispatchers.IO)`: neues `PlanRepository` → `load()` → `ReminderScheduler.rescheduleAll`; `onFinished` im `finally` |

**Zusammenspiel:** Einziger Aufrufer ist `BootReceiver`, der über `onFinished` sein `goAsync`-Ergebnis freigibt.

#### [`ReminderNotification.kt`](app/src/main/java/com/starwindow/app/data/planning/ReminderNotification.kt)

Liest die Termindaten aus dem Intent und baut die sichtbare Erinnerung, mit oder ohne Wetterzeile.

| Element | Art | Aufgabe |
|---|---|---|
| `ReminderNotice` | data class | `sessionId`, `label`, `night: LocalDate`, `hours`, `note`, `lead: ReminderLead?` |
| `ReminderNotice.from(intent)` | companion fun | Extras auslesen; `null`, wenn `label` leer oder `dateEpochDay == 0` |
| `ReminderNotifications.post(context, notice, outlook)` | fun | Benachrichtigung bauen und posten; `outlook: NightOutlook?` ergänzt die Prognose |
| `ReminderNotifications.canPost(context)` | fun | unter API 33 immer `true`, sonst Prüfung von `POST_NOTIFICATIONS` |

**Funktionsweise:** Titel je Vorlauf („In einer Woche: …", „In drei Tagen: …", „Morgen Nacht: …", „Heute Nacht: …"). Eingeklappt steht `"Prognose: " + outlook.headline`, ohne Prognose die Zusammenfassung „Nacht auf ‹Folgetag› · x,x h belichtbar". Ausgeklappt (`BigTextStyle`): Zusammenfassung, Prognose mit `outlook.source`, bei `outlook.isDiscouraging` der Satz „Der Termin bleibt stehen, die Vorhersage spricht dagegen.", danach die Notiz. Notification-ID ist `sessionId.hashCode()` – alle Vorläufe eines Termins und alle Ergänzungen ersetzen denselben Eintrag; `setOnlyAlertOnce(true)` verhindert einen zweiten Ton beim Nachtragen. Tippen öffnet `MainActivity` (PendingIntent-Request-Code `0`, `CLEAR_TOP|SINGLE_TOP`), ohne Ziel-Route. Kanal: `ReminderScheduler.CHANNEL_ID`.

**Ändern/Fallstricke:** `EXTRA_OBJECT_ID` wird zwar gesendet, aber hier nicht gelesen. Ein Deep-Link in den Kalender existiert nicht. Datumsformat „EEEE, d. MMMM" (deutsch); angezeigt wird der **Folgetag** („Nacht auf …").

#### [`ReminderReceiver.kt`](app/src/main/java/com/starwindow/app/data/planning/ReminderReceiver.kt)

Enthält zwei Receiver: `ReminderReceiver` macht aus einem fälligen Alarm die Benachrichtigung; `BootReceiver` setzt nach dem Neustart alle Alarme neu.

| Element | Art | Aufgabe |
|---|---|---|
| `ReminderReceiver` | `BroadcastReceiver` | prüft `ACTION_REMIND`, baut `ReminderNotice`, postet sofort, ergänzt dann asynchron das Wetter |
| `enrich(context, notice)` | private suspend fun | Stufe 2 (Ablage) und Stufe 3 (frischer Abruf) |
| `NETWORK_BUDGET_MILLIS` | const | `20_000` ms Zeitbudget für den frischen Abruf |
| `scope` | companion `val` | `CoroutineScope(SupervisorJob() + Dispatchers.IO)` |
| `BootReceiver` | `BroadcastReceiver` | bei `ACTION_BOOT_COMPLETED`: `goAsync`, `WatchScheduler.scheduleDailyCheck()`, `PlanReminderSync.rescheduleAll` |

**Funktionsweise (drei Stufen):**
1. **Sofort** in `onReceive` (Hauptthread): `ReminderNotifications.post(..., outlook = null)` – ohne Datei- oder Netzzugriff, die Termindaten kommen komplett aus dem Intent.
2. `goAsync()`, dann im `scope`: `NightWeatherSource.forContext(appContext)`. Ohne Ort (`hasPlace == false`) Ende. Sonst `source.stored(notice.night)` (nur Platten-Cache); liegt ein Ergebnis vor, wird an derselben ID neu gepostet.
3. `withTimeoutOrNull(20 s) { source.fresh(notice.night) }`. Neu gepostet wird nur, wenn `fresh.fetchedAtMillis` von dem der Stufe 2 abweicht (sonst hat das Netz nur den Ablagestand bestätigt, bzw. `WeatherRepository.forecast` hat selbst auf die Ablage zurückgegriffen).
`pending.finish()` steht im `finally`; Ausnahmen werden nur geloggt.

**Zusammenspiel:** Alarm von `ReminderScheduler` → hier → `ReminderNotifications`, `NightWeatherSource` (→ `WeatherRepository`, `ForecastCache`, `NightOutlooks`). Im Manifest nicht exportiert; `BootReceiver` hört auf `BOOT_COMPLETED` (`RECEIVE_BOOT_COMPLETED`).

**Ändern/Fallstricke:** Nichts Langsames vor Stufe 1 einbauen – die Erinnerung muss auch ohne Netz/Ort erscheinen. Das Zeitbudget schützt nur begrenzt: `HttpURLConnection` blockiert und reagiert nicht auf Koroutinen-Abbruch, `withTimeoutOrNull` kehrt daher womöglich erst nach den Socket-Timeouts zurück (siehe `WeatherRepository`). `BootReceiver` steht in dieser Datei (DEV_PLAN S2 schlägt eine eigene vor). Nach einem Neustart setzt `BootReceiver` die Merklisten-Kette **unbedingt** neu, auch bei leerer Merkliste; sie hält sich dann beim ersten Lauf selbst an (`WatchCheckReceiver.check` → `cancelDailyCheck`).

#### [`WatchedObject.kt`](app/src/main/java/com/starwindow/app/data/planning/WatchedObject.kt)

Datenmodell eines Merklisten-Eintrags („sag Bescheid, wenn es passt") und die Wetteranforderung.

| Element | Art | Aufgabe |
|---|---|---|
| `WeatherDemand` | `@Serializable enum` | `GOOD_ONLY` (nur Urteil `GOOD`), `ALSO_PARTLY` (auch `PARTLY`); `acceptsPartly` |
| `WatchedObject` | `@Serializable data class` | `id`, `objectId`, `label`, `type: ObjectType`, `raDeg`/`decDeg` (J2000, redundant zum Katalog, damit der Receiver den Katalog nicht laden muss), `minAltitudeDeg` (Vorgabe 30°), `minUsableHours` (1,0 h), `weatherDemand` (`GOOD_ONLY`), `quietNights` (3), `lastNotifiedEpochDay: Long?`, `enabled` (true), `createdAtMillis` |
| `position`, `minUsableMillis` | Properties | `Equatorial(raDeg, decDeg)`, Stunden → ms |
| `mayNotifyFor(date)` | fun | `false` bei `!enabled`; sonst `true`, wenn noch nie gemeldet oder `date.toEpochDay() - lastNotifiedEpochDay >= quietNights` (**Ruhezeit** in Nächten) |
| `of(obj, id, nowMillis)` | companion fun | Eintrag aus einem `SkyObject` mit Vorgabewerten |

**Ändern/Fallstricke:** Neue Felder mit Vorgabewert anlegen (wie bei `PlannedSession`). Die Ruhezeit-Logik ist in `NightOutlookTest` (`WatchEvaluatorTest` „the quiet period suppresses a second report") abgesichert. Die Enum-Namen von `WeatherDemand` und `ObjectType` stehen ohne `@SerialName` in der Datei – Umbenennen bricht gespeicherte Merklisten.

#### [`WatchlistRepository.kt`](app/src/main/java/com/starwindow/app/data/planning/WatchlistRepository.kt)

Persistenz der Merkliste; startet und beendet die tägliche Prüfkette.

| Element | Art | Aufgabe |
|---|---|---|
| `WatchlistRepository(context)` | class | Datei `filesDir/watchlist.json`, `Mutex`, `MutableStateFlow<List<WatchedObject>>` |
| `watched` | `StateFlow` | Einträge, zuletzt hinzugefügter zuerst |
| `load()` | suspend fun | Datei → Zustand |
| `add(obj: SkyObject)` | suspend fun | gibt vorhandenen Eintrag zurück oder legt neuen vorn an; danach `WatchScheduler.scheduleDailyCheck()` |
| `remove(id)` / `removeObject(objectId)` | suspend fun | entfernen; bei leerer Liste `WatchScheduler.cancelDailyCheck()` |
| `update(id, transform)` | suspend fun | Eintrag per Lambda ändern (Einstellungen im `WatchSheet`) |
| `markNotified(ids, date)` | suspend fun | setzt `lastNotifiedEpochDay`; arbeitet bewusst auf `readFile()` statt auf dem (im Receiver-Prozess leeren) Zustand |
| `readOnce()` | suspend fun | Datei lesen, ohne den Zustand zu ändern (für den Receiver) |
| `isWatched`, `forObject` | fun | synchrone Abfragen |

**Funktionsweise:** Gleiches Speichermuster wie `PlanRepository`: `Dispatchers.IO` + Mutex, Temp-Datei `watchlist.json.tmp` + `renameTo`, defekte Datei → `watchlist.json.corrupt`; JSON mit `prettyPrint`, `ignoreUnknownKeys`, `encodeDefaults`.

**Zusammenspiel:** `ObjectDetailViewModel` (`load`, `add`, `removeObject`), `CalendarViewModel` (`load`, `update`, `remove`), `WatchCheckReceiver` (eigene Instanz: `readOnce`, `markNotified`).

**Ändern/Fallstricke:** `WatchCheckReceiver` erzeugt eine **zweite Instanz** mit eigenem Mutex auf derselben Datei (DEV_PLAN S2). Läuft die Prüfung, während die App offen ist, aktualisiert `markNotified` nur die Receiver-Instanz; die Container-Instanz behält den alten Stand und würde ihn beim nächsten `update`/`remove` zurückschreiben (Vermutung aus dem Code, kein Test). `add`/`update`/`remove` arbeiten auf `state.value` – vor `load()` aufgerufen, würden sie eine leere Liste als Basis nehmen.

#### [`WatchScheduler.kt`](app/src/main/java/com/starwindow/app/data/planning/WatchScheduler.kt)

Setzt den **einen** täglichen Alarm für die Merklisten-Prüfung als selbstverlängernde Kette plus bis zu zwei Wiederholungen.

| Element | Art | Aufgabe |
|---|---|---|
| `scheduleDailyCheck(zone = systemDefault)` | fun | `ensureChannel`; nächster `CHECK_TIME` (heute, wenn noch mehr als `LEAD_MILLIS` = 60 s entfernt, sonst +24 h), `attempt = 0` |
| `scheduleRetry(attempt)` | fun | `now + RETRY_DELAY_MILLIS`, solange `attempt <= MAX_ATTEMPTS` |
| `cancelDailyCheck()` | fun | PendingIntents für `attempt` 0…`MAX_ATTEMPTS` abbrechen |
| `ensureChannel()` | fun | Kanal `watch_opportunities` („Günstige Nächte", `IMPORTANCE_DEFAULT`) |
| `CHECK_TIME` | `val` | `15:00` |
| `RETRY_DELAY_MILLIS` | const | 45 min |
| `MAX_ATTEMPTS` | const | 2 (also 15:00, 15:45, 16:30) |
| `ACTION_CHECK` (`com.starwindow.app.CHECK_WATCHLIST`), `EXTRA_ATTEMPT`, `REQUEST_BASE` (900 000) | const | Intent und Request-Codes `900000 + attempt` |

**Funktionsweise:** Ebenfalls `setAndAllowWhileIdle(RTC_WAKEUP, …)` (inexakt, Doze-fest). Kein `setRepeating`, weil das im Doze-Modus nicht feuert; stattdessen setzt jeder reguläre Durchlauf den nächsten. Idempotent durch gleichen Request-Code + `FLAG_UPDATE_CURRENT`.

**Zusammenspiel:** Aufgerufen von `WatchlistRepository.add/remove`, `CalendarViewModel` (beim Öffnen, wenn Merkliste nicht leer; `ensureChannel` beim Öffnen eines Eintrags), `ObjectDetailViewModel` (`ensureChannel`), `BootReceiver`, `WatchCheckReceiver`. `WatchSheet` zeigt `CHECK_TIME` an.

**Ändern/Fallstricke:** Wer die Uhrzeit ändert, ändert nur `CHECK_TIME` (UI liest sie mit). Wer `MAX_ATTEMPTS` erhöht, bekommt automatisch mehr Request-Codes; `cancelDailyCheck` deckt den Bereich mit ab. Die Kette rechnet in der **Gerätezone**, der Receiver bestimmt „heute" in der Zone des Wetterorts.

#### [`WatchCheckReceiver.kt`](app/src/main/java/com/starwindow/app/data/planning/WatchCheckReceiver.kt)

Die tägliche Prüfung: Steht heute Nacht ein vorgemerktes Objekt gut, und wird es klar?

| Element | Art | Aufgabe |
|---|---|---|
| `WatchCheckReceiver` | `BroadcastReceiver` | prüft `ACTION_CHECK`, setzt bei `attempt == 0` **zuerst** die Prüfung für morgen, dann `goAsync` + `check` |
| `check(context, scheduler, attempt)` | private suspend fun | eigentliche Auswertung |
| `NETWORK_BUDGET_MILLIS` | const | `25_000` ms |

**Funktionsweise:** In `check`: (1) `WatchlistRepository(context).readOnce()`, nur `enabled`; leer → `cancelDailyCheck()` und Ende. (2) `NightWeatherSource.forContext`; ohne Ort Ende (kein Retry, da Einstellungssache). (3) `zone = place.zone ?: systemDefault`, `tonight = LocalDate.now(zone)`. (4) `outlook = withTimeoutOrNull(25 s) { source.fresh(tonight) } ?: source.stored(tonight)`; `null` → `scheduleRetry(attempt + 1)`. (5) `WatchEvaluator.evaluateAll(watched, place.toObserverLocation(), zone, tonight, outlook)` – dort: `mayNotifyFor` (Ruhezeit), Wetterurteil passend zu `weatherDemand`, `ObservationPlanner.nightFor` mit `minAltitudeDeg`, längste Überschneidung von brauchbaren Wetterstunden und Objekt-Fenster ≥ `minUsableMillis`; Treffer nach Länge sortiert. (6) Treffer → `WatchNotifications.post`, **danach** `markNotified(ids, tonight)` (lieber doppelt als gar nicht melden).

**Ändern/Fallstricke:** Die Reihenfolge „erst nächsten Alarm setzen, dann rechnen" ist tragend: Sonst würde eine Ausnahme die Kette dauerhaft beenden. Wiederholungsversuche (`attempt > 0`) verlängern die Kette nicht. Die Bewertungslogik selbst liegt in `domain/WatchEvaluator` (Tests: `NightOutlookTest` → `WatchEvaluatorTest`, `UsableWindowTest`). Eigene Repository-Instanz: siehe `WatchlistRepository`.

#### [`WatchNotification.kt`](app/src/main/java/com/starwindow/app/data/planning/WatchNotification.kt)

Baut **eine** Sammelbenachrichtigung für alle Treffer einer Nacht.

| Element | Art | Aufgabe |
|---|---|---|
| `WatchNotifications.post(context, hits, zone)` | fun | leer oder ohne Berechtigung (`ReminderNotifications.canPost`) → nichts; sonst Benachrichtigung im Kanal `WatchScheduler.CHANNEL_ID` |
| `NOTIFICATION_ID` | const | `0x5741` – fest, ein späterer Treffer derselben Nacht ersetzt den vorigen |
| `WATCH_REQUEST_CODE` | const | `1` (PendingIntent zum Öffnen von `MainActivity`) |
| `LISTED` | const | 3 Ziele werden namentlich genannt, der Rest gezählt |

**Funktionsweise:** Ein Treffer: Titel „Heute Nacht: ‹Label›", Text `hit.headline(zone)`, ausgeklappt `hit.detail(zone)`. Mehrere: „N vorgemerkte Ziele stehen heute Nacht gut", Liste der ersten drei mit `headline`, „und N weitere", darunter Prognose (`outlook.headline`, `outlook.source`) des ersten Treffers. Texte für `headline`/`detail` kommen aus `domain/WatchHit`. Kein `setOnlyAlertOnce`.

#### [`PlannedPath.kt`](app/src/main/java/com/starwindow/app/data/planning/PlannedPath.kt)

Übersetzt einen gespeicherten Termin in ein `TrackedObject` mit Bahnbogen für den Sucher („Pfad zeigen").

| Element | Art | Aufgabe |
|---|---|---|
| `PlannedPath.track(container, session): Boolean` | suspend fun | Objekt und Nacht auflösen, an `trackingStore` übergeben; `false`, wenn Objekt oder Standort fehlen |

**Funktionsweise:** Objekt aus `catalogRepository.objects()` per `objectId`. Standort: Wetterort aus den Einstellungen (als `ObserverLocation`), sonst `manualLocation`, sonst `locationTracker.lastKnown()`. Zone: Zeitzone des Wetterorts, sonst Gerätezone. `ObservationPlanner.plan(obj, observer, zone, session.date, days = 1)` liefert das Dunkelfenster (`darkFromMillis`/`darkToMillis`); fehlt es (z. B. Mittsommer), gilt 21:00 Ortszeit bis +10 h. Ergebnis: `TrackedObject(obj, pathFromMillis, pathToMillis, pathLabel = "Nacht auf <Folgetag>")`.

**Zusammenspiel:** Aufgerufen aus `StarWindowNavHost` (Kalender → „Pfad zeigen"), danach Navigation zum Sucher.

**Ändern/Fallstricke:** `PlannedPathTest` testet **nicht** `track` selbst (braucht `AppContainer`), sondern dieselbe Rechnung mit `ObservationPlanner` + `SkyTrackBuilder` („the path is sampled inside the planned night, three months out" u. a.). Die Standortreihenfolge weicht von `NightWeatherSource.place` ab (dort kein GPS-Rückfall).

#### Ablauf Erinnerung

```
PlanningViewModel.togglePlan ─► PlanRepository.add ─► writeFile(observation_plan.json)
CalendarViewModel.toggleReminder/setNote ─► PlanRepository.toggleReminder/setNote ─┐
                                                                                    ▼
      ReminderScheduler.reschedule (cancel → ensureChannel → je Vorlauf in der Zukunft)
        └► AlarmManager.setAndAllowWhileIdle(RTC_WAKEUP, Tag − daysBefore, 17:00 Gerätezone)
             PendingIntent.getBroadcast(requestCode = hash(sessionId)*8 + ordinal, Extras)
                                                                                    ▼
ReminderReceiver.onReceive ─► ReminderNotifications.post(outlook = null)   [Stufe 1, sofort]
   └► goAsync ─► NightWeatherSource.stored(night) ─► post(gleiche ID)      [Stufe 2, Platte]
             └► withTimeoutOrNull(20 s) fresh(night) ─► post(gleiche ID)   [Stufe 3, Netz]
Neustart: BootReceiver ─► PlanReminderSync.rescheduleAll ─► neues PlanRepository.load ─► rescheduleAll
Kalender öffnen: CalendarViewModel.init ─► PlanRepository.load ─► rescheduleAll
```

#### Ablauf Merkliste

```
ObjectDetailViewModel ─► WatchlistRepository.add ─► watchlist.json + WatchScheduler.scheduleDailyCheck
   └► AlarmManager.setAndAllowWhileIdle(15:00, requestCode 900000, attempt=0)
WatchCheckReceiver.onReceive
   ├► (attempt==0) scheduleDailyCheck  → morgen 15:00                    [Kette zuerst]
   └► goAsync ─► WatchlistRepository(neu).readOnce ─► NightWeatherSource.fresh ?: stored (heute)
        ├─ kein Outlook ─► scheduleRetry(attempt+1)  (+45 min, max. 2×)
        └─ WatchEvaluator.evaluateAll (Ruhezeit, Wetterurteil, Höhe, Überschneidung ≥ minUsableHours)
             └► WatchNotifications.post (eine Sammelmeldung, ID 0x5741) ─► markNotified(tonight)
Neustart: BootReceiver ─► WatchScheduler.scheduleDailyCheck
Kalender öffnen: CalendarViewModel ─► scheduleDailyCheck (falls Merkliste nicht leer)
Letzter Eintrag entfernt: WatchlistRepository.remove ─► cancelDailyCheck
```

#### Typische Änderungen in diesem Bereich
- **Erinnerungszeitpunkt ändern (z. B. 18 Uhr statt 17 Uhr):** `ReminderLead.NOTIFY_TIME` in `Reminder.kt`. Test `ReminderTest` („a reminder fires in the late afternoon of the right day") anpassen. Bereits gesetzte Alarme werden beim nächsten Öffnen des Kalenders (`rescheduleAll`) bzw. Neustart umgestellt.
- **Neuen Vorlauf (z. B. „2 Wochen vorher"):** Eintrag **am Ende** von `ReminderLead` mit eigenem `@SerialName`, in `ReminderLead.ordered` einsortieren, Titeltext in `ReminderNotifications.title` (das `when` ist erschöpfend und erzwingt das). Maximal 8 Einträge wegen `requestCode`. UI-Chips lesen `ordered`.
- **Neues Feld in `PlannedSession`:** Feld mit Vorgabewert anlegen; soll es in der Benachrichtigung erscheinen, als `EXTRA_*` in `ReminderScheduler.pendingIntent` mitgeben, in `ReminderNotice`/`from` lesen, in `ReminderNotifications.expanded`/`summary` ausgeben. Muss es änderbar sein: Methode in `PlanRepository` nach dem Muster `setNote` (inkl. `scheduler.reschedule`). Befüllen in `PlanningViewModel.togglePlan`. Roundtrip-Test in `PlanningPersistenceTest` ergänzen.
- **Neue Merklisten-Einstellung (z. B. maximale Mondphase):** Feld mit Vorgabe in `WatchedObject`, Auswertung in `domain/WatchEvaluator.evaluate`, Bedienelement im `ui/planning/WatchSheet` über `CalendarViewModel.updateWatch`. Test in `NightOutlookTest` (`WatchEvaluatorTest`).
- **Prüfzeit/Wiederholungen der Merkliste ändern:** `WatchScheduler.CHECK_TIME`, `RETRY_DELAY_MILLIS`, `MAX_ATTEMPTS`; Kommentare in `WatchScheduler` und `WatchCheckReceiver` mitziehen.
- **Tippen auf Benachrichtigung soll direkt in den Kalender führen:** `openApp` in `ReminderNotifications`/`WatchNotifications` mit Extra versehen und in `MainActivity`/`StarWindowNavHost` auswerten (gibt es bisher nicht).

<a id="pkg-domain-1"></a>

### `domain` (Teil 1) — Durchgänge, Laufbahnen, Suche und Auswahl

Dieser Teil der Fachlogik beantwortet drei Fragen: **Was zieht wann durch ein gespeichertes Fenster?** (Durchgangsberechnung samt Dämmerung, Mond und Genauigkeit), **Welchen Weg nimmt ein Objekt dabei?** (Laufbahnen für Diagramme und Sucher) und **Was davon zeigt die App wo?** (Suche, Filter, Sortierung, Auswahl für den Sucher, Bewertung „lohnt sich fotografisch"). Alle Dateien sind reines Kotlin ohne Android-Abhängigkeiten und werden durch JVM-Unit-Tests in `app/src/test/java/com/starwindow/app/` abgesichert.

Der Weg von Fenster + Katalog + Zeitraum zur angezeigten Durchgangsliste:

```
WindowDetailViewModel.search()
  │  Zeitraum: now + startHoursOffset h … now + endHoursOffset h  (Vorgabe 0…24 h, UTC-ms)
  │  CatalogRepository.objectsVisibleFrom(Breite, Grenzgröße)   ← grober Vorfilter (data/)
  ▼
TransitCalculator.search(window, objects, from, to)
  │  1. window.shape.membershipTest()          einmal: Punkt-im-Fenster-Test (core/geometry)
  │  2. Vorfilter: Deklinationsband + Höhenband (Sonne/Mond ausgenommen)
  │  3. Precession.forEpoch(from)                einmal; jedes Objekt einmal aufs Datum
  │  4. DarkSpans.over(from, to, observer)       einmal: astronomische Dunkelheit
  │  5. je Kandidat (parallel ab 200):  IntervalScanner.scan(...)
  │        60-s-Raster (Sonne/Mond 30 s) → Bisektion 12× an jedem Wechsel
  │  6. je Intervall: withConditions() → darkMillis, Mondhöhe, Mondbeleuchtung
  ▼
TransitSearchResult(transits: List<ObjectTransit>, sortiert nach erstem Eintritt)
  ▼
TransitListView.build(transits, ResultFilter, query, TransitSort, onlyDark)
  │  Filter (Art, nur dunkel, Suchtext via ObjectSearch) → Sortierung (PhotographicInterest u. a.)
  ▼
Liste in WindowDetailScreen   (+ ConstellationTransitCalculator für Sternbilder,
                               SkyTrackBuilder für die Laufbahnen im Diagramm)
```

#### [`IntervalScanner.kt`](app/src/main/java/com/starwindow/app/domain/IntervalScanner.kt)

Gemeinsamer Kern aller Durchgangsrechnungen: findet die Zeitabschnitte, in denen eine Bedingung („liegt im Fenster") gilt. Abtasten plus Bisektion statt analytischer Lösung, weil „Punkt im beliebigen Himmelspolygon" keine geschlossene Form hat.

| Element | Art | Aufgabe |
|---|---|---|
| `ScoredInterval` | data class | Ein Abschnitt: `enterMillis`, `exitMillis`, `clippedAtStart`/`clippedAtEnd` (galt schon zu Beginn / noch am Ende der Suche), `bestScore` + `bestMillis` (bester Wert innerhalb), `durationMillis` |
| `IntervalScanner` | internal object | Enthält den Scan |
| `IntervalScanner.scan(fromMillis, toMillis, stepMillis, refineIterations, isInside, score)` | fun | Liefert alle Abschnitte, in denen `isInside(t)` wahr ist |
| `refine(...)` | private fun | Bisektion eines Intervalls, in dem der Zustand wechselt |

**Funktionsweise:**
1. `isInside(fromMillis)` wird ausgewertet; ist es wahr, beginnt sofort ein Abschnitt mit `clippedAtStart = true` und `bestScore = score(fromMillis)`.
2. Danach wird in Schritten von `stepMillis` abgetastet (`time = from + step, from + 2·step, …` solange `time <= toMillis`).
3. Wechsel außen→innen: Eintrittszeit = `refine(previousTime, time)`; `bestScore` startet mit `score(time)` am ersten Innen-Abtastpunkt.
4. Wechsel innen→außen: Austrittszeit = `refine(...)`, Abschnitt wird abgeschlossen.
5. Innen→innen: `score(time)` wird mit `bestScore` verglichen (Maximum + Zeitpunkt).
6. Ist am Ende der Zustand noch „innen", endet der Abschnitt bei `toMillis` mit `clippedAtEnd = true`.

`refine` halbiert das Klammerintervall `refineIterations`-mal, vergleicht dabei mit dem Zustand am unteren Rand und gibt die Mitte des verbliebenen Intervalls zurück. Genauigkeit: `stepMillis / 2^refineIterations` – bei 60 s und 12 Iterationen ≈ 15 ms. `score` ist frei wählbar: Höhe in Grad (Einzelobjekte) oder Anzahl Figursterne (Sternbilder). Der Bestwert wird nur an Rasterpunkten gemessen, nicht an den verfeinerten Rändern.

**Zusammenspiel:** Aufgerufen von `TransitCalculator.intervalsFor`/`movingIntervalsFor` und `ConstellationTransitCalculator.search`. Ruft nur die übergebenen Lambdas auf.

**Ändern/Fallstricke:**
- Die Bisektion existiert bewusst nur hier; Änderungen wirken auf Objekte **und** Sternbilder. `ConstellationAndTrackTest` („sidereal timing still holds after moving the scan into a shared helper") und `TransitTest` („entry and exit times are refined below the sampling step") sichern das ab.
- Abschnitte, die kürzer als ein Abtastschritt sind und zwischen zwei Rasterpunkten komplett liegen, werden nicht gefunden (Grundprinzip des Rasters).
- Liegt `toMillis` nicht auf dem Raster, wird das Reststück nach dem letzten Rasterpunkt nicht mehr abgetastet: Ein Eintritt dort fehlt, ein Austritt dort wird als `exitMillis = toMillis`, `clippedAtEnd = true` gemeldet.
- `stepMillis <= 0` wirft `IllegalArgumentException`.

#### [`TransitCalculator.kt`](app/src/main/java/com/starwindow/app/domain/TransitCalculator.kt)

Die zentrale Durchgangsberechnung für Katalogobjekte inklusive Sonne und Mond. Liefert je Objekt alle Abschnitte im Fenster, ergänzt um Dunkelheits- und Mondangaben. Positionen enthalten die atmosphärische Refraktion (`CoordinateTransforms.apparentHorizontalAtLst`), passend zu dem, was die Kamera beim Einzeichnen sah.

| Element | Art | Aufgabe |
|---|---|---|
| `TransitDarkness` | enum (`DARK` „dunkel", `PARTLY` „teils Dämmerung", `BRIGHT` „zu hell") | Brauchbarkeit eines Durchgangs |
| `TransitInterval` | data class | Ein Durchgang: Ein-/Austritt (UTC-ms), Clipping-Flags, `bestAltitudeDeg` + `bestMillis`, `darkMillis`, `moonAltitudeDeg` (Vorgabe −90), `moonIlluminationPercent` |
| `TransitInterval.darkness` | val | `BRIGHT` bei `darkMillis <= 0`, `DARK` ab 90 % (`FULLY_DARK_FRACTION = 0.9`) der Dauer, sonst `PARTLY` |
| `TransitInterval.moonInterferes` | val | Mond über 0° **und** ≥ 30 % beleuchtet |
| `TransitInterval.conditionLabel` | val | Listentext, z. B. „dunkel · Mond 87 %" |
| `ObjectTransit` | data class | Objekt + Liste seiner Intervalle; `totalDurationMillis`, `firstEntryMillis`, `isInsideAtStart`, `darkDurationMillis`, `hasDarkTime`, `bestInterval` (meiste Dunkelzeit, bei Gleichstand längste Dauer) |
| `TransitSearchResult` | data class | Fenster, Zeitraum, `transits`, `objectsConsidered` (Zahl der Kandidaten nach Vorfilter), `computeMillis` |
| `TransitCalculator(stepSeconds = 60, refineIterations = 12)` | class | Die Berechnung; in `AppContainer` als `transitCalculator` erzeugt |
| `search(window, objects, fromMillis, toMillis)` | suspend fun | Vollständige Suche auf `Dispatchers.Default` |
| `objectsInsideAt(window, objects, atMillis)` | fun | Welche Objekte stehen **jetzt** im Fenster (je Objekt eine Positionsrechnung, kein Scan) |
| `declinationOfDirection(direction, latitudeDeg)` | internal fun | Deklination einer horizontfesten Richtung: `sin δ = sin φ·sin h + cos φ·cos h·cos A` |
| `SkyObject.canReach(bounds, lat)` | private fun | Höhenband-Test: max. Höhe `90 − abs(φ−δ)`, min. Höhe `abs(φ+δ) − 90` |
| `SkyObject.canReachDeclination(windowDec, reach)` | private fun | `abs(δ − δFenster) <= reach` |
| `DECLINATION_MARGIN_DEG = 1.5` | const | Zuschlag für Refraktion (bis 0,5°), J2000-vs.-Datum (≈ 0,4°) und Polygonradius |
| `PARALLEL_THRESHOLD = 200` | const | Ab so vielen Kandidaten wird parallelisiert |
| `TransitInterval.withConditions(dark, observer)` | private fun | Setzt `darkMillis`, Mondhöhe und -beleuchtung |

**Funktionsweise von `search`:**
1. `require(toMillis > fromMillis)`. Einmalig: `inside = window.shape.membershipTest()` (geschlossene Funktion über unveränderliche Geometrie, wird von allen Threads geteilt) und `bounds = window.altitudeBounds()` (Höhenband des Umrisses ±0,5°, definiert in `ConstellationTransitCalculator.kt`).
2. **Vorfilter (der Performance-Trick):** Weil das Fenster am Horizont hängt, wandert beim Drehen des Himmels nur seine Rektaszension – seine **Deklination ist konstant**. `windowDec = declinationOfDirection(shape.center(), lat)`, `reach = shape.angularRadiusDeg() + 1.5`. Ein Objekt bleibt Kandidat, wenn es `isMoving` ist **oder** (`canReachDeclination` **und** `canReach`). Der Höhentest allein wäre zu großzügig (ein zirkumpolares Objekt bei +80° erreicht von Berlin aus 45° Höhe, aber nie im Süden). Ergebnis laut README: 12-h-Suche über den ganzen Katalog in ~60–130 ms statt ~800 ms.
3. `precession = Precession.forEpoch(fromMillis)` einmal; feste Objekte werden einmal mit `obj.positionAt(precession)` aufs Datum gebracht – in der Zeitschleife ändert sich nur die Sternzeit (`AstroTime.lstDeg`).
4. `dark = DarkSpans.over(from, to, observer)` einmal für den ganzen Zeitraum.
5. `scanInParallel`: unter 200 Kandidaten sequenziell, sonst in `availableProcessors().coerceIn(2, 8)` Blöcke per `async` aufgeteilt.
   - Feste Objekte (`intervalsFor`): `IntervalScanner.scan` mit `stepSeconds * 1000` = 60 s, Score = Höhe.
   - **Sonne/Mond** (`movingIntervalsFor`): Position wird bei **jeder** Abtastung neu über `obj.positionAtMillis(millis)` (Ephemeride) gerechnet; Schritt `stepSeconds * 500` = 30 s (halbiert, weil der Mond ~0,55°/h Eigenbewegung hat und Fenster oft kaum größer sind).
6. Jedes Intervall bekommt per `withConditions` die Überlappung mit den dunklen Abschnitten (`dark.overlap`) sowie Mondhöhe (`LunarEphemeris.topocentricAltitudeDeg`) und Beleuchtung (`LunarEphemeris.illuminationAt`), beide in der **Mitte** des Durchgangs ausgewertet.
7. Objekte ohne Intervall fallen weg; Ergebnis sortiert nach `firstEntryMillis`.

**Zusammenspiel:** Aufrufer sind `ui/windows/WindowDetailViewModel.search()` (Fensterdetail, Zeitraum per Schieberegler) und `ui/search/ObjectSearchViewModel` (`objectsInsideAt` + `search` über 12 h für „jetzt im Fenster, noch X min" und den Filter „durchs Fenster", jeweils nur über `PhotographicInterest.isPhotoTarget`-Objekte). Die UI liest `TransitDarkness`/`conditionLabel` in `WindowDetailScreen`. Benutzt `core/astro` (`AstroTime`, `CoordinateTransforms`, `Precession`, `LunarEphemeris`), `core/geometry/SkyWindow` und `DarkSpans`.

**Ändern/Fallstricke:**
- Sonne und Mond **müssen** von beiden Vorfiltern ausgenommen bleiben: Ihre `decDeg` im Katalogeintrag ist ein Platzhalter (0,0, siehe `EphemerisCatalog`). `EphemerisTransitTest` („the moon is never rejected for its declination", „the moon passes through a window pointed at it", „a moving body does not cross like a fixed one") sichert das.
- Wer `DECLINATION_MARGIN_DEG` verkleinert, riskiert, echte Durchgänge am Rand zu verlieren – `TransitTest` („nothing that really crosses the window is rejected", „an object just outside the window edge is still considered", „an object that reaches the right altitude in the wrong direction is not a transit").
- Die Mondschwelle 30 % steht als Literal sowohl hier als auch in `ObservationPlanner` (`moonInterferes`); bei Änderung beide anpassen.
- Präzession wird für den ganzen Zeitraum am Startzeitpunkt fixiert – bei Zeiträumen von Tagen unkritisch, bei Monaten nicht mehr exakt.
- `ObjectTransit.firstEntryMillis` (`minOf`) würde bei leerer Intervallliste werfen; `search` erzeugt solche Einträge nie.
- `TransitTest` prüft außerdem: sofortiger Fund bei Start im Fenster, Durchgangsdauer passt zur Erddrehung, zirkumpolare Wiederkehr nach einem Sterntag, Kreis und gleichwertiges Rechteck liefern denselben Durchgang, `objectsInsideAt` stimmt mit der Suche überein. `EphemerisTransitTest` prüft „a pass at midnight is dark" / „a pass at noon is bright".

#### [`ConstellationTransitCalculator.kt`](app/src/main/java/com/starwindow/app/domain/ConstellationTransitCalculator.kt)

Durchgänge ganzer Sternbildfiguren. Da ein Fenster fast nie eine ganze Figur fasst, gilt ein Sternbild als „durchziehend", solange **mindestens ein Figurstern** im Fenster ist; gemeldet wird zusätzlich, wie viele Figursterne maximal gleichzeitig drin waren.

| Element | Art | Aufgabe |
|---|---|---|
| `ConstellationInterval` | data class | Ein-/Austritt, Clipping-Flags, `peakStarsInside` + `peakMillis` |
| `ConstellationTransit` | data class | Sternbild + Intervalle; `totalDurationMillis`, `firstEntryMillis`, `peakStarsInside`, `figureStarCount`, `showsMostOfTheFigure` (Spitze ≥ halbe Figur) |
| `ConstellationTransitCalculator(stepSeconds = 60, refineIterations = 12)` | class | In `AppContainer` als `constellationTransitCalculator` |
| `search(window, constellations, from, to)` | suspend fun | Durchgänge aller Figuren |
| `figureAt(constellation, window, atMillis)` | fun | Liniensegmente der Figur in Az/Höhe zu einem Zeitpunkt (für die Vorschau) |
| `SkyWindow.altitudeBounds()` | internal fun | Min/Max-Höhe des Umrisses, ±0,5° gepolstert; auch vom `TransitCalculator` genutzt |
| `FigureStar.canReach(bounds, lat)` | internal fun | Derselbe Höhenband-Test wie für Katalogobjekte |

**Funktionsweise:** Membership-Test, Höhenband und Präzession einmal pro Suche. Je Sternbild: nur die Figursterne behalten, die das Höhenband überhaupt erreichen (sonst Sternbild verwerfen), Positionen einmal aufs Datum bringen. `countInside(t)` rechnet die Sternzeit und zählt die Sterne im Fenster. `IntervalScanner.scan` mit 60-s-Schritt, `isInside = countInside > 0`, `score = countInside` → `peakStarsInside` (mindestens 1). Ergebnis nach `firstEntryMillis` sortiert. Läuft sequenziell (keine Parallelisierung) auf `Dispatchers.Default`.

**Zusammenspiel:** `WindowDetailViewModel.search()` ruft es direkt nach dem `TransitCalculator`; `rebuildTracks()` nutzt `figureAt` zum Zeitpunkt `peakMillis` des Intervalls mit den meisten Sternen und baut für die ersten Figursterne Laufbahnen. `WindowDetailScreen` zeigt „X von Y Figursternen · Figur gut zu erkennen".

**Ändern/Fallstricke:**
- Anders als beim `TransitCalculator` gibt es hier **keinen** Deklinationsvorfilter, nur den Höhenband-Test (der KDoc von `FigureStar.canReach` spricht trotzdem von „declination rejection"). Wer die Sternbildsuche beschleunigen will, kann `declinationOfDirection` analog einsetzen.
- Abgesichert in `ConstellationAndTrackTest` („a figure crossing the window is reported", „only the part of the figure that fits is counted", „a figure that never reaches the window is skipped entirely", „the figure snapshot puts the stars where they belong").

#### [`TransitListView.kt`](app/src/main/java/com/starwindow/app/domain/TransitListView.kt)

Macht aus dem rohen Suchergebnis die angezeigte Liste: filtern, Suchtext anwenden, sortieren. Bewusst aus ViewModel und UI herausgezogen, damit es ohne Emulator testbar ist.

| Element | Art | Aufgabe |
|---|---|---|
| `TransitSort` | enum (`INTEREST`, `ENTRY`, `DURATION`, `BRIGHTNESS`, `SIZE`, `DARK`) | Sortierart mit `label` (Chip) und `listNote` (Kopfzeile, z. B. „lohnendste zuerst") |
| `TransitListView.build(transits, filter, query = "", sort = INTEREST, onlyDark = false)` | fun | Gefilterte und sortierte Liste |
| `TransitListView.highlightsByKind(transits, perKind = 3)` | fun | Je Art (Nebel, Galaxien, Haufen, Sterne) die besten 3 nach `PhotographicInterest`; leere Arten fallen weg |

**Funktionsweise von `build`:** Bei `ResultFilter.CONSTELLATIONS` leere Liste (`!filter.showsObjects`). Sonst bleibt ein Eintrag, wenn `filter.matches(obj)`, bei `onlyDark` zusätzlich `hasDarkTime`, und bei nicht leerem Suchtext `ObjectSearch.score(ObjectSearch.fold(query), obj) != NO_MATCH`. Sortierung:
- **Suchtext vorhanden:** überstimmt die gewählte Sortierung – absteigend nach Trefferqualität, dann `PhotographicInterest.score`.
- `INTEREST` (Vorgabe): `PhotographicInterest.score` absteigend, dann Gesamtdauer absteigend.
- `ENTRY`: `firstEntryMillis` aufsteigend. `DURATION`: `totalDurationMillis` absteigend.
- `BRIGHTNESS`: `magnitude` aufsteigend (fehlend = 99). `SIZE`: `sizeArcmin` absteigend (fehlend = −1).
- `DARK`: `darkDurationMillis` absteigend, dann `PhotographicInterest.score`.

Eine Gruppierung gibt es nur in Form von `highlightsByKind`; die Liste selbst ist flach.

**Zusammenspiel:** `WindowDetailViewModel` (`visibleObjects` ruft `build`, `highlights` ruft `highlightsByKind`); `WindowDetailScreen` zeigt die `TransitSort`-Chips. Nutzt `ResultFilter`, `ObjectSearch`, `PhotographicInterest`.

**Ändern/Fallstricke:** Neue Sortierart: Enum-Wert + `listNote`-Zweig + Zweig in `sorted` (der `else`-Zweig fängt derzeit `SIZE` ab – ein neuer Wert ohne eigenen Zweig landet stillschweigend bei „nach Größe"). Abgesichert in `TransitListViewTest` (Vorgabe „lohnendste zuerst", alle Sortierungen, Suchtext findet „m31"/„M 31"/Bezeichnung, Suchtext überstimmt Sortierung, Sternbildfilter blendet alle Objekte aus, Highlights).

#### [`DarkSpans.kt`](app/src/main/java/com/starwindow/app/domain/DarkSpans.kt)

Die Abschnitte astronomischer Dunkelheit (Sonne ≤ −18°) eines Zeitraums, **einmal** pro Suche berechnet und dann für jeden Durchgang nur noch geschnitten.

| Element | Art | Aufgabe |
|---|---|---|
| `DarkSpans` | class (privater Konstruktor) | Hält `spans: List<LongRange>` (aufsteigend, überlappungsfrei) |
| `overlap(fromMillis, toMillis)` | fun | Summe der dunklen Millisekunden im Intervall |
| `contains(millis)`, `isEmpty` | fun / val | Zeitpunkt dunkel? / gar keine Dunkelheit? |
| `DARK_SUN_ALTITUDE_DEG` | const | = `ObservationPlanner.ASTRONOMICAL_TWILIGHT_DEG` (−18°) |
| `STEP_MILLIS` | const | 5 min, wie `core/astro/Twilight` |
| `over(from, to, observer, thresholdDeg)` | fun | Erzeugt die Abschnitte |
| `NONE` | val | Leere Instanz („über Dunkelheit wird nichts behauptet") |

**Funktionsweise:** `over` tastet `SolarEphemeris.altitudeDeg` alle 5 Minuten ab (letzter Punkt exakt `toMillis`), ohne Bisektion. Ein Abschnitt beginnt am ersten dunklen Abtastpunkt und endet am ersten hellen; ist es am Ende noch dunkel, endet er bei `toMillis`. Die Ränder sind also auf das 5-min-Raster gerundet.

**Zusammenspiel:** Nur von `TransitCalculator.search` benutzt. `contains` und `NONE` werden im Hauptcode derzeit nicht verwendet (nur `NONE` in Tests).

**Ändern/Fallstricke:** Wer eine andere Dunkelheitsgrenze möchte (z. B. nautisch −12°), übergibt `thresholdDeg` in `TransitCalculator.search` – derzeit wird immer die Vorgabe benutzt. Abgesichert in `EphemerisTransitTest` (Novembernacht Berlin dunkel in der Mitte, Mittsommer nördlich des Polarkreises ohne Dunkelheit, leere Spanne).

#### [`WindowAccuracy.kt`](app/src/main/java/com/starwindow/app/domain/WindowAccuracy.kt)

Schätzt, wie weit ein gespeichertes Fenster tatsächlich von der eingezeichneten Lage abweichen könnte, und hält damit die Messqualität an der Messung fest.

| Element | Art | Aufgabe |
|---|---|---|
| `AccuracyBand` | enum (`GOOD` „gut", `FAIR` „brauchbar", `POOR` „fraglich") | Einstufung |
| `WindowAccuracy` | data class | `uncertaintyDeg` (halbe Fehlerbreite in °), `isMeasured`, `band`, `reason`; `headline` „± 1.2°" (gemessen) bzw. „etwa ± 10°" (geschätzt) |
| `SkyWindow.accuracy()` | Erweiterungsfunktion | Berechnet die Abschätzung |
| `SkyWindow.heldPenalty()` | private fun | Zuschlag, wenn der Kompass gestört war und der Gyro die Nordrichtung allein hielt |
| `MEASUREMENT_FLOOR_DEG = 0.5`, `MINIMUM_HELD_PENALTY_DEG = 3.0` | const | Untergrenze für Messwerte / Mindestzuschlag |

**Funktionsweise:**
- **Mit Kalibrierungsrest** (`calibrationResidualDeg != null`): `max(Rest, 0,5°) + heldPenalty()`, `isMeasured = true`, Band `GOOD` bis 2,0°, sonst `FAIR` (nie `POOR`).
- **Ohne Kalibrierung:** aus `compassAccuracy` (Android-Flag): 3 → 5°, 2 → 10°, 1 → 20°, sonst 30°, plus `heldPenalty()`; Band `GOOD` ≤ 6°, `FAIR` ≤ 12°, sonst `POOR`.
- `heldPenalty`: mit gespeicherter Dauer `headingHeldSeconds > 0` → `max(AttitudeFusion.heldHeadingDriftDeg(sekunden), 3°)`; nur Flag `headingHeld` (ältere Fenster) → pauschal 3°; sonst 0.

**Zusammenspiel:** `WindowDetailScreen` zeigt `AccuracyBar(window.accuracy(), window.shape.angularRadiusDeg())`. Nutzt `core/sensors/AttitudeFusion`.

**Ändern/Fallstricke:** Die Gradwerte für die Kompass-Flags sind Konventionen, keine Messungen – die UI kennzeichnet sie deshalb als „etwa". Zahlenformat über `String.format` mit Standard-Locale. Abgesichert in `WindowQualityTest` (gemessener Rest wird zitiert, zu kleiner Rest wird auf 0,5° angehoben, unkalibriert = Schätzung, schlechterer Kompass → breiter + schlechteres Band, gestörter Kompass vergrößert den Fehler).

#### [`SkyTrack.kt`](app/src/main/java/com/starwindow/app/domain/SkyTrack.kt)

Die „Laufbahn" eines Objekts als Folge von Az/Höhe-Punkten – für das Fensterdiagramm, die Höhenkurve und den geplanten Pfad im Sucher.

| Element | Art | Aufgabe |
|---|---|---|
| `TrackPoint` | data class | `millis` + `position: Horizontal` |
| `SkyTrack` | data class | `label`, `points`, `enterMillis`/`exitMillis` (der durchgezogen gezeichnete Teil); `isEmpty` (< 2 Punkte) |
| `SkyTrack.hourMarks()` | fun | Je überschrittener voller Stunde der Punkt, der ihr am nächsten liegt (kein Interpolieren) |
| `SkyTrackBuilder.overSpan(label, equatorial, observer, from, to, samples = 240)` | fun | Gleichmäßig über einen ganzen Zeitraum (Höhenkurve) |
| `SkyTrackBuilder.forInterval(label, equatorial, observer, enter, exit, samples = 160)` | fun | Um einen Durchgang herum, mit Vor-/Nachlauf |
| `LEAD_FRACTION = 0.45`, `MIN_LEAD_MILLIS` 4 min, `MAX_LEAD_MILLIS` 90 min | const | Vor-/Nachlauf = 45 % der Durchgangsdauer, begrenzt auf 4–90 min |

**Funktionsweise:** Beide Builder rechnen bei jedem Abtastpunkt `CoordinateTransforms.apparentHorizontalAtLst(equatorial, lat, lstDeg(millis))` mit **einer festen** Äquatorposition. `forInterval` tastet `enter − lead … exit + lead` in `samples − 1` gleichen Schritten ab.

**Zusammenspiel:** `WindowDetailViewModel` (`rebuildTracks` → `forInterval` für gewählte Objekte und Figursterne; Infoblatt → `overSpan`), `ObjectDetailViewModel` (Höhenkurve), `CaptureViewModel` (geplanter Pfad eines verfolgten Objekts, `overSpan`). Gezeichnet in `ui/windows/WindowTrackChart`, `AltitudeCurveChart`, `ObjectInfoSheet` und `ui/capture/SkyOverlay`.

**Ändern/Fallstricke:** Weil nur eine feste `Equatorial` übergeben wird, ist die Laufbahn für Sonne und Mond eine Näherung (die Aufrufer übergeben `obj.positionAt(precession)` zu einem festen Zeitpunkt; die Eigenbewegung des Mondes fließt nicht ein). Wer das ändern will, braucht eine Variante mit `(Long) -> Equatorial` (analog zu `SkyObject.positionAtMillis`). Abgesichert in `ConstellationAndTrackTest` (Vor-/Nachlauf, Bahn geht durch die Position, Westwärtsbewegung, Stundenmarken, leeres Intervall), `FilterAndSpanTest` (Spanne exakt, Kulmination, `overSpan` und `forInterval` stimmen überein) und `PlannedPathTest`.

#### [`OverlaySelection.kt`](app/src/main/java/com/starwindow/app/domain/OverlaySelection.kt)

Entscheidet, welche Katalogobjekte der Kamerasucher überhaupt zeichnet. Der Sucher projiziert jedes übergebene Objekt in jedem Frame; der volle Katalog wäre zu teuer und unlesbar.

| Element | Art | Aufgabe |
|---|---|---|
| `MAX_OBJECTS = 400` | const | Obergrenze aller Markierungen |
| `MAX_STARS = 150` | const | Davon höchstens so viele Sterne |
| `STAR_MAGNITUDE_LIMIT = 4.2` | const | Sterne nur bis zu dieser Helligkeit |
| `MIN_TARGET_SCORE = 35.0` | const | Mindest-`PhotographicInterest.score` für Deep-Sky-Objekte |
| `select(catalog, latitudeDeg?, magnitudeLimit)` | fun | Die Auswahl |
| `isWorthDrawing(obj, magnitudeLimit)` | fun | Fotoziel? Helligkeit (nur falls bekannt) ≤ Grenze? Score ≥ 35? |
| `everRises(obj, latitudeDeg)` | fun | `isMoving` oder `90 − abs(φ − δ) > 0` |

**Funktionsweise von `select`:**
1. **Breite:** Ist sie bekannt, nur Objekte, die je aufgehen (`everRises`). Ohne Breite wird nichts gestrichen.
2. **Sonne/Mond** (`isMoving`) kommen ohne weitere Prüfung dazu.
3. **Sterne:** `type.isStar`, Helligkeit ≤ `min(4.2, magnitudeLimit)`, hellste zuerst, max. 150.
4. **Ziele:** Nicht-Sterne ohne Sonne/Mond, `isWorthDrawing`, absteigend nach `PhotographicInterest.score`, auf den Rest des Budgets (`400 − Sterne − bewegte`) gekürzt.
5. Gesamtergebnis nach Helligkeit sortiert (fehlend = 99), damit die auffälligsten zuerst gezeichnet werden.

Die Benutzer-Grenzgröße wirkt nur verschärfend; ein Objekt ohne Helligkeitsangabe wird nicht deswegen verworfen (große Nebel).

**Zusammenspiel:** `CaptureViewModel.refreshOverlayCatalog()` ruft `select` auf `Dispatchers.Default`, wenn sich Katalog, Standort oder Einstellung ändern; das Ergebnis landet im UI-State als `catalog` für das Overlay.

**Ändern/Fallstricke:** Wer mehr Objekte zeigen will, ändert `MAX_OBJECTS`/`MAX_STARS`; die Konstanten sind öffentlich und werden in `OverlaySelectionTest` referenziert (harte Obergrenze, Sterne verdrängen keine Ziele, helle Sterne bleiben, Einstellung verschärft nur, Objekt genau am Polabstand geht noch auf). `EphemerisTransitTest` prüft „sun and moon are always drawn in the viewfinder".

#### [`ObjectSearch.kt`](app/src/main/java/com/starwindow/app/domain/ObjectSearch.kt)

Findet Objekte nach dem, wie Nutzer sie nennen, bewertet Treffer und liefert die Liste der Suchseite inklusive aktueller Höhe und Machbarkeit.

| Element | Art | Aufgabe |
|---|---|---|
| `ObjectSort` | enum (`RELEVANCE`, `FEASIBILITY`, `BRIGHTNESS`, `ALTITUDE`, `SIZE`, `NAME`) | Sortierung der Suchliste |
| `ObjectHit` | data class | `obj`, `position` (null ohne Standort), `score`, `feasibility`, `minutesLeftInWindow`; `altitudeDeg`, `isUp` |
| `ObjectSearch.search(query, objects, observer?, nowMillis, sort, limit = 200, conditions?)` | fun | Bewertet, positioniert, sortiert, kürzt |
| `ObjectSearch.score(needle, obj)` | fun | Trefferqualität eines **bereits gefalteten** Suchtexts oder `NO_MATCH` (−1) |
| `ObjectSearch.fold(text)` | fun | Normalisierung |
| Punktwerte | private const | `EXACT_ID` 1000, `EXACT_NAME` 900, `ID_PREFIX` 700, `NAME_PREFIX` 620, `WORD_PREFIX` 520, `ID_CONTAINS` 360, `NAME_CONTAINS` 300 |

**Funktionsweise:**
- **`fold`:** Kleinschreibung; ä→ae, ö→oe, ü→ue, ß→ss; alles außer Buchstaben/Ziffern fällt weg; **führende Nullen** jeder Ziffernfolge werden gestrichen (`C 020` → `c20`, `B033` → `b33`), Nullen innerhalb bleiben (`NGC 100` → `ngc100`). Damit falten „M31", „M 31", „m-31" gleich; „NGC 224" → `ngc224`.
- **`score`:** Bester Einzeltreffer gewinnt (keine Summe). Über `obj.allIdentifiers` (`id` + `catalogIds`): exakt 1000, Präfix 700 + Bonus, enthalten 360. Über `name` + `alternativeNames`: exakt 900, Präfix 620 + Bonus, Wortanfang (Wörter getrennt an Leerzeichen, `-`, `/`, `,`, Klammern) 520, enthalten 300. Präfixbonus = `100 · Länge(needle) / Länge(Kandidat)`. Entspricht der Suchtext genau dem Sternbildkürzel (z. B. „ori"), gibt es mindestens 300 (Rest-Treffer).
- **`search`:** Bei leerem Suchtext Score 0 für alle (ganze Liste). Position je Treffer über `obj.positionAt(Precession.forEpoch(nowMillis))` und Sternzeit – für Sonne/Mond liefert `positionAt` intern die Ephemeride zum Präzessionszeitpunkt. Mit `conditions` zusätzlich `Feasibility.assess`. Sortierung: `RELEVANCE` nach Score, dann Helligkeit (ohne Suchtext nur Helligkeit); `FEASIBILITY` nach Urteil (fehlend = `BELOW`), dann `PhotographicInterest.score`; `ALTITUDE` absteigend (ohne Position ganz unten); `NAME` nach gefaltetem Namen bzw. ID. Danach `take(limit)`.

**Zusammenspiel:** `ui/search/ObjectSearchViewModel` (Hauptsuche; Favoriten- und Fensterfilter vor, übrige `CatalogFilter`-Kriterien nach der Suche), `ui/guide/PhotoGuideViewModel` (ohne Standort, `limit = 30`), `TransitListView` (`fold`/`score` für den Suchtext der Durchgangsliste). `fold` wird außerdem als Schlüsselnormalisierung in `data/catalog/ObjectNotesRepository` und `domain/SeasonalHighlights` benutzt.

**Ändern/Fallstricke:** Jede Änderung an `fold` ändert auch die Schlüssel in `ObjectNotesRepository` (die Notizdatei muss in gefalteter Form vorliegen – `ObjectDescriptionTest` „every key is already in folded form") und in `SeasonalHighlights`. Das Ranking hängt an den Abständen der Punktwerte: Der Präfixbonus (bis 100) darf eine Stufe nicht überspringen, sonst schlägt z. B. ein ID-Präfix einen exakten Namen. Abgesichert in `ObjectSearchTest` (Schreibweisen, gepolsterte Nummern, Nullen innerhalb, Umlaute, exakte Bezeichnung vor „enthält", Alternativnamen, Wortanfang, Sternbild als letzter Ausweg, Sortierungen, ohne Beobachter keine Höhen).

#### [`CatalogFilter.kt`](app/src/main/java/com/starwindow/app/domain/CatalogFilter.kt)

Bündelt alle Einschränkungen der Katalogsuche in einem Objekt, damit die Chip-Zeile über der Liste immer genau das zeigt, was tatsächlich angewendet wird.

| Element | Art | Aufgabe |
|---|---|---|
| `CatalogFilter` | data class | Felder: `kind: ResultFilter`, `magnitudeLimit`, `minSizeArcmin` (′), `constellation` (IAU-Kürzel), `minAltitudeDeg`, `hideImpossible`, `onlyThroughWindow`, `windowId` (null = neuestes Fenster), `onlyFavorites`; alle Vorgaben „keine Einschränkung" |
| `isEmpty`, `activeCount` | val | Nichts aktiv? / Anzahl aktiver Einschränkungen (`windowId` zählt nicht) |
| `activeLabels` | val | Chips: „Favoriten", Art, „< 9.0 mag", „> 30'"/„> 2°", Kürzel, „über 20°", „nur machbar", „durchs Fenster" |
| `matches(obj, altitudeDeg?, conditions, favorites)` | fun | Prüft alles **außer** dem Fenster |
| `without(field)` | fun | Setzt ein Feld zurück (× am Chip) |
| `NONE`, `formatSize(arcmin)` | companion | Leerer Filter; Größe in ′ bzw. ab 60′ in ° |
| `FilterField` | enum | Welches Feld ein Chip vertritt |
| `FilterChip` | data class | `field` + `label` |

**Funktionsweise von `matches`:** Reihenfolge Favoriten → `kind.matches` → Helligkeit (fehlend = 99, fällt also bei gesetzter Grenze heraus) → Größe (fehlend = 0) → Sternbild (Groß-/Kleinschreibung egal) → Höhe (fehlend = −90) → `hideImpossible` über `Feasibility.assess(...).isPossible`. Der Fensterfilter wird nicht hier, sondern im ViewModel per Durchgangssuche gelöst.

**Zusammenspiel:** `ui/search/ObjectSearchViewModel`, `ObjectSearchScreen`, `CatalogFilterSheet`. Nutzt `ResultFilter` und `Feasibility` (anderer Bearbeiter).

**Ändern/Fallstricke:** Siehe „Neues Filterkriterium" unten – Feld, `isEmpty`, `activeCount`, `activeLabels`, `matches`, `without` und `FilterField` müssen gemeinsam angepasst werden. Abgesichert in `FeasibilityTest` (leerer Filter, jede Einschränkung, Chips, Chip entfernen, Machbarkeitsfilter, Sternbild ohne Groß-/Kleinschreibung).

#### [`ResultFilter.kt`](app/src/main/java/com/starwindow/app/domain/ResultFilter.kt)

Die Art-Auswahl („Alle", „Fotoziele", „Sternbilder", „Sterne", „Nebel", „Galaxien", „Haufen") für Durchgangsliste und Katalogsuche.

| Element | Art | Aufgabe |
|---|---|---|
| `ResultFilter` | enum mit `label` | Die sieben Arten |
| `matches(obj)` | fun | `ALL` alles; `CONSTELLATIONS` nichts; `PHOTO` = kein Stern **und** (Name vorhanden **oder** Größe ≥ 3′); `STARS` = `type.isStar`; `NEBULAE` = alle Nebeltypen inkl. Planetarischer Nebel, SNR, Haufen mit Nebel; `GALAXIES` = Galaxie/Galaxiengruppe; `CLUSTERS` = offene + Kugelsternhaufen |
| `showsConstellations`, `showsObjects` | val | Ob Sternbild- bzw. Objektzeilen sichtbar sind |
| `PHOTO_MIN_SIZE_ARCMIN = 3.0` | const | Mindestgröße für unbenannte Fotoziele |

**Zusammenspiel:** `TransitListView`, `CatalogFilter`, `WindowDetailViewModel`/`Screen`, `CatalogFilterSheet`.

**Ändern/Fallstricke:** `PHOTO` ist **nicht** identisch mit `PhotographicInterest.isPhotoTarget` (dort 1,5′, Messier-Prüfung, Doppelsterne zählen). Neue `ObjectType`-Werte müssen hier ggf. einer Gruppe zugeordnet werden. Abgesichert in `FilterAndSpanTest` (jeder Filter nur seine Arten, Nebelfilter vollständig, Fotoziele benannt oder groß, Sternbildfilter).

#### [`ObjectDescription.kt`](app/src/main/java/com/starwindow/app/domain/ObjectDescription.kt)

Erzeugt die Klartextbeschreibung im Infoblatt: was für ein Objekt das ist, eine optionale Notiz und aus den Katalogzahlen abgeleitete Eigenschaften.

| Element | Art | Aufgabe |
|---|---|---|
| `ObjectDescription` | data class | `whatItIs` (immer), `note` (optional), `traits: List<String>` |
| `describe(obj, note?, nowMillis)` | companion fun | Baut die Beschreibung; leere Notiz zählt als keine; für Sonne/Mond (`obj.body`) wird die Notiz über `EphemerisCatalog.stateText(body, nowMillis)` aktuell erzeugt |
| `whatItIs(type)` | companion fun | Fester deutscher Erklärtext je `ObjectType` (vollständiges `when`) |
| `traits(obj)` | private fun | Eigenschaftssätze aus den Zahlen |
| `spectralHint`, `morphologyHint` | private fun | Spektralklasse → Farbe; Morphologiecode → Galaxientyp |
| `MOON_DIAMETER_ARCMIN = 31.0` | private const | Bezugsgröße Vollmond |

**Funktionsweise von `traits`:** Größe in Vollmonden: ≥ 2 → „rund N-mal so breit wie der Vollmond", ≥ 0,6 → „etwa so groß", ≥ 5′ → „deutlich ausgedehnt", sonst „klein – hier lohnt sich Brennweite". Flächenhelligkeit: ≤ 20 mag/□′ robust gegen Aufhellung, ≥ 23 braucht dunklen Himmel, dazwischen „halbwegs dunkel". Schmalbandhinweis bei `type.respondsToNarrowband`; Warnung „kein Schmalbandfilter" bei Reflexionsnebeln. Doppelsterne nach Abstand (< 3″, < 30″, sonst weit). Spektralklasse nach erstem Buchstaben (O/B, A, F, G, K, M, C/S). Morphologie nach Präfix (E, S0/SA0/SB0, SB, S, I).

**Zusammenspiel:** `ui/search/ObjectDetailViewModel` und `ui/windows/WindowDetailViewModel` rufen `describe(obj, objectNotesRepository.noteFor(obj))`; angezeigt in `ObjectInfoSheet`. Die Notizen stammen aus `data/catalog/ObjectNotesRepository`.

**Ändern/Fallstricke:** Ein neuer `ObjectType` erzwingt einen neuen Zweig in `whatItIs` (Compilerfehler sonst). Die Reihenfolge der Morphologie-Prüfungen ist wichtig (`SB0` vor `SB` vor `S`). Abgesichert in `ObjectDescriptionTest` (jeder Typ erklärt, Nebelarten und Filter, Dunkelnebel, Planetarischer Nebel, Größe gegen Mond, Flächenhelligkeit, Morphologie, leere Notiz, Notizdatei vorhanden und gefaltet).

#### [`PhotographicInterest.kt`](app/src/main/java/com/starwindow/app/domain/PhotographicInterest.kt)

Die eine Stelle, die entscheidet, wie lohnend ein Objekt fotografisch ist (Punktzahl 0–100) und ob es überhaupt als Fotoziel gilt. Rangiert nach **Winkelgröße**, nicht nach Helligkeit.

| Element | Art | Aufgabe |
|---|---|---|
| `score(obj)` | fun | Punktzahl 0–100 |
| `isPhotoTarget(obj)` | fun | Sonne/Mond: ja; Doppelstern: ja; sonstige Sterne: nein; Name oder Messier: ja; sonst Größe ≥ 1,5′ |
| `MIN_USEFUL_SIZE_ARCMIN = 1.5` | const | Darunter ist ein Objekt „ein Punkt" |
| `SIZE_SATURATION_ARCMIN = 90`, `NAMED_BONUS = 22`, `MESSIER_BONUS = 18` | private const | Größensättigung, Boni |

**Funktionsweise von `score`:**
- **Sterne** (`starScore`): `(6,5 − mag) · 3`, begrenzt 0–25 (fehlende mag = 7). Doppelsterne mit Abstand: +18 bei ≥ 5″, sonst +6, gedeckelt bei 55.
- **Alle anderen:** Summe aus
  - Größe: `42 · √(min(Größe, 90′) / 90′)`, 0 unter 1,5′ oder ohne Größe;
  - +22 mit Name, +18 bei Messier (`isMessier`: eine Kennung der Form „M" + 1–3 Ziffern);
  - Helligkeit: mit Flächenhelligkeit `(25 − SB) · 4`, sonst `(13 − mag) · 1,6`, jeweils 0–20; ohne beides 4;
  - Typ: Sonnensystem 12, Emissionsnebel/Haufen mit Nebel 8, Reflexionsnebel/SNR 7, Galaxie/Planetarischer Nebel/Kugelhaufen 5, Nebel/offener Haufen/Galaxiengruppe 4, Dunkelnebel 2;
  - Ergebnis auf 0–100 begrenzt.

**Zusammenspiel:** Benutzt von `TransitListView` (Sortierung `INTEREST`/`DARK`, Highlights), `OverlaySelection` (Ziele + `MIN_TARGET_SCORE`), `ObjectSearch` (Sortierung `FEASIBILITY`), `ObjectSearchViewModel` (Vorfilter für Fenster-Durchgangssuche) sowie von `Feasibility`, `PhotoGuide`, `SeasonalHighlights`, `TonightBoard` und `PhotoGuideViewModel`.

**Ändern/Fallstricke:** Eine Änderung verschiebt gleichzeitig die Vorgabesortierung der Durchgangsliste, die Auswahl im Sucher (Schwelle `OverlaySelection.MIN_TARGET_SCORE = 35` ist auf diese Skala abgestimmt: knapp über dem Namensbonus 22 + Grundhelligkeit) und mehrere Vorschlagslisten. `isMessier` erkennt nur Kennungen ohne Leerzeichen („M31", nicht „M 31"). Abgesichert in `FeasibilityTest` („a large faint nebula outranks a small bright one", „stars are not photographic targets, wide doubles are", „an anonymous speck is not a target, a named or Messier object always is", „the score stays inside its stated range").

#### Typische Änderungen in diesem Bereich

- **Abtastschritt oder Genauigkeit der Durchgangsberechnung ändern:** Konstruktorparameter `stepSeconds`/`refineIterations` von `TransitCalculator` bzw. `ConstellationTransitCalculator` – entweder als Vorgabewert in der Klasse oder beim Erzeugen in `AppContainer` (`StarWindowApplication.kt`). Sonne/Mond nutzen automatisch den halben Schritt (`stepSeconds * 500` in `movingIntervalsFor`). Faustregel: Endgenauigkeit = Schritt / 2^Iterationen; kleinerer Schritt kostet linear Rechenzeit, findet aber kürzere Durchgänge. Danach `TransitTest`, `EphemerisTransitTest`, `ConstellationAndTrackTest` laufen lassen.
- **Vorfilter lockern/verschärfen (Performance vs. Vollständigkeit):** `DECLINATION_MARGIN_DEG` und `canReachDeclination`/`canReach` in `TransitCalculator.kt`; Polster des Höhenbands in `SkyWindow.altitudeBounds()` (`ConstellationTransitCalculator.kt`). Bewegte Objekte (`isMoving`) immer ausnehmen.
- **Dunkelheitsgrenze ändern (z. B. nautische Dämmerung):** `DarkSpans.over(..., thresholdDeg = …)` im Aufruf in `TransitCalculator.search`; die 90-%-Schwelle für „ganz dunkel" ist `FULLY_DARK_FRACTION` in `TransitInterval`; die Mondschwelle (30 %, Höhe > 0°) in `TransitInterval.moonInterferes` – und dieselbe Zahl in `ObservationPlanner`.
- **Neue Sortierung der Durchgangsliste:** Wert in `TransitSort` (mit `label` und `listNote`), Zweig in `TransitListView.sorted`; die Chips in `WindowDetailScreen` iterieren über `TransitSort.entries` und erscheinen dadurch automatisch. Test in `TransitListViewTest` ergänzen.
- **Neues Filterkriterium in der Katalogsuche:** Feld in `CatalogFilter` (Vorgabe = keine Einschränkung), dazu `isEmpty`, `activeCount`, `activeLabels`, `matches`, `without` und neuer `FilterField`-Wert; Bedienelement in `ui/search/CatalogFilterSheet`; Test in `FeasibilityTest`. Ein Kriterium, das eine Zeitrechnung braucht (wie der Fensterfilter), gehört nicht in `matches`, sondern ins `ObjectSearchViewModel`.
- **Neue Objektart-Gruppe:** Wert in `ResultFilter` mit `matches`-Zweig (die Chips in `WindowDetailScreen` und `CatalogFilterSheet` iterieren über `ResultFilter.entries`); ggf. in `TransitListView.highlightsByKind` aufnehmen; `FilterAndSpanTest`.
- **Suchranking anpassen:** Punktwerte und `prefixBonus` in `ObjectSearch`; Normalisierung in `ObjectSearch.fold` (Achtung: wirkt auf `ObjectNotesRepository`- und `SeasonalHighlights`-Schlüssel). `ObjectSearchTest` und `TransitListViewTest` prüfen die Reihenfolgen.
- **Mehr (oder weniger) Objekte im Sucher zeigen:** `OverlaySelection.MAX_OBJECTS`, `MAX_STARS`, `STAR_MAGNITUDE_LIMIT`, `MIN_TARGET_SCORE`. Mehr Markierungen kosten pro Frame Projektion in `ui/capture/SkyOverlay`. `OverlaySelectionTest` anpassen.
- **Fotografische Bewertung ändern:** `PhotographicInterest.score`/`typeScore`/`sizeScore`/`brightnessScore`. Danach prüfen, ob `OverlaySelection.MIN_TARGET_SCORE` noch passt, und `FeasibilityTest`, `TransitListViewTest`, `OverlaySelectionTest`, `SeasonalHighlightsTest`, `PhotoGuideTest` laufen lassen.
- **Laufbahn länger/kürzer oder feiner zeichnen:** `LEAD_FRACTION`, `MIN_LEAD_MILLIS`, `MAX_LEAD_MILLIS` und `samples` in `SkyTrackBuilder`.
- **Beschreibungstexte ergänzen:** Typtexte in `ObjectDescription.whatItIs`, Schwellen in `traits`; objektspezifische Notizen nicht hier, sondern in der Notizdatei von `ObjectNotesRepository` (Schlüssel in `ObjectSearch.fold`-Form).

<a id="pkg-domain-2"></a>

### `domain` (Teil 2) — Nachtbewertung, Wetter, Jahresplanung, Motive und Fotoguide

Diese neun Dateien beantworten jeweils eine klar abgegrenzte Frage. Alle sind reines Kotlin ohne Android-Abhängigkeit (Ausnahme: sie importieren Datentypen aus `data/catalog`, `data/weather`, `data/planning`, `data/gear`) und werden über JVM-Unit-Tests in `app/src/test/java/com/starwindow/app/` abgesichert.

| Frage | Klasse | Datei |
|---|---|---|
| „Ist dieses Objekt *jetzt* unter diesem Himmel machbar?" | `Feasibility`, `SkyConditions` | `Feasibility.kt` |
| „In welchen Nächten des Jahres steht es gut? Wann ist die Saison?" | `ObservationPlanner`, `ObservationNight` | `ObservationPlanner.kt` |
| „Wird die Nacht klar, und was verdirbt sie?" (Stunde für Stunde) | `AstroWeather`, `AstroHour`, `AstroNight` | `AstroWeather.kt` |
| „Was sagt die Vorhersage über *diese eine* Nacht, und wie alt ist die Aussage?" | `NightOutlook`, `NightOutlooks` | `NightOutlook.kt` |
| „Wie hell ist der Himmel an diesem Ort?" | `BortleScale` | `BortleScale.kt` |
| „Soll die Merkliste heute Bescheid sagen?" | `WatchEvaluator`, `WatchHit` | `WatchEvaluator.kt` |
| „Was lohnt sich *jetzt gerade* / gleich?" (Suchbildschirm vor der Eingabe) | `TonightBoard` | `TonightBoard.kt` |
| „Was sind die Motive dieser Jahreszeit?" (Reiter „Motive") | `SeasonalHighlights`, `Season`, `Popularity` | `SeasonalHighlights.kt` |
| „Welche Einstellungen am Smart-Teleskop?" (Reiter „Guide") | `PhotoGuide`, `PhotoPlan` | `PhotoGuide.kt` |

Wichtig für das Verständnis: Es gibt **zwei unabhängige Dunkelheits-/Bewertungsrechnungen**. `ObservationPlanner` rechnet rein astronomisch (geschlossene Formeln, ganzes Jahr, Rückfall auf nautische Dämmerung), `AstroWeather` rechnet stundenweise mit Wetter und nutzt `core/astro/Twilight` (nur astronomische Dunkelheit zählt als „dunkel"). `WatchEvaluator` legt beide übereinander.

#### [`Feasibility.kt`](app/src/main/java/com/starwindow/app/domain/Feasibility.kt)

Liefert ein Ein-Wort-Urteil („leicht", „geht", „schwierig", „zu tief", „zu schwach", „unter Horizont") für ein Objekt bei gegebener Höhe und gegebenem Himmel. Enthält außerdem `SkyConditions`, die Beschreibung der Himmelshelligkeit (Bortle + Mond), die auch andere Klassen verwenden.

| Element | Art | Aufgabe |
|---|---|---|
| `Feasibility` | enum | `EASY`, `OK`, `HARD`, `LOW`, `TOO_FAINT`, `BELOW` – in dieser Reihenfolge von gut nach schlecht, so dass nach `ordinal` sortiert werden kann. Jeder Wert hat `label` und `explanation`. |
| `Feasibility.isPossible` | val | `true` für `EASY`/`OK`/`HARD`. |
| `Feasibility.assess(obj, altitudeDeg, conditions)` | fun (companion) | Das Urteil. |
| `Feasibility.contrastAgainstSky(obj, conditions)` | fun | `conditions.skyBrightness − obj.surfaceBrightness` (mag/□′); positiv = Objekt heller als der Himmel; `null` ohne Flächenhelligkeit. |
| `LOW_ALTITUDE_DEG` | const | `15.0`° |
| `SkyConditions` | data class | `bortleLevel` (1–9, Default 5), `moonIlluminationPercent` (0–100), `moonAltitudeDeg` (Default −90), `isEstimated`. |
| `SkyConditions.skyBrightness` | val | Grenzflächenhelligkeit in mag/□′. |
| `SkyConditions.moonInterferes` | val | Mond über Horizont **und** ≥ 25 % beleuchtet. |
| `SkyConditions.at(observer, bortleLevel, nowMillis, userSetBortle)` | fun | Baut die Bedingungen mit Mond aus `LunarEphemeris`; ohne Beobachter ohne Mond. |
| `SkyConditions.UNKNOWN` | val | Bortle 5, kein Mond. |

**Funktionsweise (`assess`):**
1. `altitudeDeg == null` oder `≤ 0` → `BELOW`; `< 15°` → `LOW`.
2. Sterne (`obj.type.isStar`): nur Helligkeit (`magnitude`, fehlend = 6.0): `≤ 4.0` → `EASY`, `≤ 6.0` → `OK`, sonst `HARD`.
3. Kontrast berechnen; `< −2.5` mag (`REACHABLE_CONTRAST_MAG`) → `TOO_FAINT`.
4. „Winzig" = `sizeArcmin` (fehlend = 0.0) im Bereich `0.0..PhotographicInterest.MIN_USEFUL_SIZE_ARCMIN` (1.5′) → `HARD`.
5. Kein Kontrast (keine Flächenhelligkeit): Rückfall auf Gesamthelligkeit: `≤ 9` → `EASY`, `≤ 12` → `OK`, sonst `HARD`.
6. Kontrast `≥ 0.5` (`EASY_CONTRAST_MAG`) **und** Höhe `≥ 30°` → `EASY`; Kontrast `≥ −1.0` (`WORKABLE_CONTRAST_MAG`) → `OK`; sonst `HARD`.

Die negativen Schwellen sind Absicht: Eine Kamera kann durch Stapeln unter den Himmelshintergrund greifen (siehe KDoc im Companion).

**`skyBrightness`:** `22.3 − (bortle − 1) · 0.52` mag/□′ (Bortle 1 ≈ 22.3, Bortle 9 ≈ 18.1). Steht der Mond über dem Horizont, wird abgezogen: `(Beleuchtung/100) · 1.6 · min(Mondhöhe/45°, 1)`, also bis zu 1.6 mag.

**Zusammenspiel:** Aufgerufen von `TonightBoard.build`, `ObjectSearch` (Verdikt je Trefferzeile) und `CatalogFilter` (Filter „nur Machbares"). `SkyConditions.at` wird in `ui/search/ObjectSearchViewModel` mit `BortleScale.resolve(place)` gefüttert. Nutzt `PhotographicInterest.MIN_USEFUL_SIZE_ARCMIN` und `LunarEphemeris`.

**Ändern/Fallstricke:**
- Fehlt `sizeArcmin`, gilt das Objekt als „winzig" (`0.0` liegt im Bereich) und wird `HARD` – Schritt 5 (Magnitude-Rückfall) wird also nur für Objekte mit bekannter Größe > 1.5′ erreicht.
- Die Mondschwelle für `moonInterferes` ist hier 25 %, in `ObservationNight.moonInterferes` 30 % – wer das vereinheitlichen will, muss beide Stellen ändern.
- `skyBrightness` (mag/□′, hier) und `PhotoGuide.SKY_BRIGHTNESS` (mag/arcsec², dort) sind zwei getrennte Bortle-Tabellen.
- Tests: `FeasibilityTest` (Bortle-Monotonie, Mond nur über Horizont, dünne Sichel keine Störung, Horizont-/Kontrast-/Größenregeln, Sterne, Reihenfolge der Enum-Werte, Machbarkeitsfilter); `ObjectSearchTest` (u. a. `TonightBoardTest`).

#### [`ObservationPlanner.kt`](app/src/main/java/com/starwindow/app/domain/ObservationPlanner.kt)

Die Jahresplanung „Monate im Voraus": für ein Objekt und einen Ort, Nacht für Nacht, wie viele Stunden es gleichzeitig hoch genug steht **und** der Himmel dunkel ist. Ohne Fenster – es zählt nur eine Mindesthöhe über dem freien Horizont.

| Element | Art | Aufgabe |
|---|---|---|
| `DarknessQuality` | enum | `ASTRONOMICAL` (Sonne < −18°), `NAUTICAL` (nur −12…−18°), `NONE`; `isUsable` = nicht `NONE`. |
| `ObservationNight` | data class | Ergebnis einer Nacht: `date` (Abenddatum), `darkFromMillis`/`darkToMillis`, `darkness`, `bestMillis`, `bestAltitudeDeg`, `usableMillis` (Summe), `usableFromMillis`/`usableToMillis` (längster Abschnitt), `moonIlluminationPercent`, `moonAltitudeDeg` (beim besten Moment), `score` 0–100. |
| `ObservationNight.moonInterferes` / `isWorthwhile` / `usableHours` | val | Mond > 0° und ≥ 30 %; `usableMillis > 0 && darkness.isUsable`; Stunden. |
| `ASTRONOMICAL_TWILIGHT_DEG` / `NAUTICAL_TWILIGHT_DEG` | const | `−18.0` / `−12.0` |
| `MIN_USEFUL_ALTITUDE_DEG` | const | `30.0`° (Luftmasse 2) |
| `MIN_WORTHWHILE_MILLIS` | const | 45 min – Schwelle für Saison und „ganzjährig". |
| `plan(obj, observer, zone, from, days = 365)` | fun | Liste von `ObservationNight`, eine je Tag. |
| `nightFor(positionJ2000, observer, zone, date, minAltitudeDeg = 30)` | fun | Eine Nacht für eine gespeicherte J2000-Position mit frei wählbarer Mindesthöhe (Merkliste, Hub, Guide, Kalender). |
| `bestNights(nights, limit = 12, minimumGapDays = 5)` | fun | Beste Nächte nach `score`, mindestens 5 Tage auseinander, nach Datum sortiert. |
| `season(nights)` | fun | Längste zusammenhängende Strecke mit `usableMillis ≥ 45 min`, als `ClosedRange<LocalDate>` oder `null`. |
| `isYearRound(nights)` | fun | `true`, wenn ≥ 95 % der Nächte die 45 min erreichen. |
| `hourAngleAtAltitude(altitudeDeg, decDeg, latitudeDeg)` | fun | Stundenwinkel H (Grad) aus `cos H = (sin h − sin φ sin δ)/(cos φ cos δ)`; `null`, wenn die Höhe nie gekreuzt wird. |

**Funktionsweise:**
1. `plan` präzediert die Katalogposition **einmal** auf das Startdatum (`Precession.forEpoch`). Für bewegte Objekte (`obj.isMoving`, Sonne/Mond/Planeten) wird je Nacht die Position zur Mitternacht nach dem Abend neu geholt; innerhalb einer Nacht bleibt sie fest.
2. `nightOf` (privat): Bezugszeit ist `midnightAfter(date)` – die Mitternacht **nach** dem Abend (eine Nacht gehört zum Abenddatum). Sonnenposition einmal zu dieser Mitternacht (`SolarEphemeris.at`).
3. `darkWindow`: Erst mit −18°, dann mit −12° den Stundenwinkel der Sonne geschlossen lösen; Abenddämmerung bei +H, Morgendämmerung bei −H (`timeOfHourAngle` wandelt Ziel-Sternzeit in eine Uhrzeit nahe der Mitternacht um, ±½ Sterntag). Gelingt keine Schwelle → `DarknessQuality.NONE`, Nacht mit `score = 0`.
4. Kulmination = Zeitpunkt mit Stundenwinkel 0; `maxAltitude = 90 − |φ − δ|`. Bester Moment = Kulmination, auf das Dunkelfenster begrenzt (`coerceIn`); dort wird die Höhe exakt gerechnet (`CoordinateTransforms.apparentHorizontalAtLst`, mit Refraktion).
5. `usableOverlap`: Objekt ist über `minAltitudeDeg` zwischen Kulmination ± H. Schnitt dieses Intervalls mit dem Dunkelfenster, für die Kulmination und die Durchgänge einen Sterntag davor/danach (`turn in -1..1`). Summe → `usableMillis`, längstes Teilstück → `usableFrom/To`. Ohne Kreuzung: zirkumpolar über der Schwelle → ganzes Dunkelfenster, sonst 0.
6. Mond (`LunarEphemeris`) einmal zum besten Moment: Höhe topozentrisch, Beleuchtung.
7. `score` (privat), nur wenn `usableMillis > 0` und Dunkelheit brauchbar:
   - Stunden: `55 · min(h, 6)/6` (6 h = volle Punktzahl)
   - Höhe: `30 · bestAltitude / maxAltitude` (relativ zur maximal erreichbaren Höhe am Ort, nicht zum Zenit)
   - Dunkelheit: `15` (astronomisch) oder `5` (nautisch)
   - Mondabzug: `25 · Beleuchtung · min(Mondhöhe/60°, 1)`, nur wenn Mond über Horizont
   - Ergebnis auf 0–100 begrenzt.
8. `season`: einfacher Lauf über die Liste; Läufe von Nächten mit `usableMillis ≥ MIN_WORTHWHILE_MILLIS` werden gezählt, der längste gewinnt. So wird die Totzeit (Objekt am Taghimmel) nicht mitgezählt (README „Die Saison ist die längste zusammenhängende Strecke").

**Warum das schnell genug ist:** Keine Abtastung. Je Nacht eine Sonnenposition, ein paar Arcuscosinus-Lösungen, eine exakte Höhenberechnung und eine Mondposition – ein Jahr sind so wenige hundert Trigonometrie-Auswertungen statt ~200 000 Ephemeriden-Abfragen bei 5-Minuten-Abtastung wie in `Twilight` (KDoc der Klasse; README „Warum das schnell genug ist"). Getestet in `ObservationPlannerTest` „a full year is computed without sampling the sky".

**Zusammenspiel:** `ui/planning/PlanningViewModel` (`plan`, `bestNights`, `season`, `isYearRound`), `ui/planning/PlanningScreen` (zeigt `MIN_USEFUL_ALTITUDE_DEG`), `ui/planning/CalendarViewModel` (`nightFor` für die nächste brauchbare Nacht jedes Merklisten-Eintrags, bis 120 Tage voraus), `ui/guide/PhotoGuideViewModel` (`nightFor` mit 20°), `data/planning/PlannedPath` (`plan(…, days = 1)` für die Bahnanzeige eines Termins), `WatchEvaluator`, `SeasonalHighlights`; `DarkSpans.DARK_SUN_ALTITUDE_DEG` übernimmt `ASTRONOMICAL_TWILIGHT_DEG`. Ruft `core/astro` (`SolarEphemeris`, `LunarEphemeris`, `Precession`, `AstroTime`, `CoordinateTransforms`, `Angles`) auf.

**Ändern/Fallstricke:**
- `MIN_USEFUL_ALTITUDE_DEG` gilt nur für `plan`; `nightFor` nimmt die Mindesthöhe als Parameter (Merkliste: `WatchedObject.minAltitudeDeg`, Default 30°; Hub und Guide: 20°).
- Innerhalb einer Nacht wird die Sonnendeklination als konstant angenommen, der Mond nur einmal ausgewertet; für „welche Nacht" genügt das (Kommentar in `plan`).
- `plan` präzediert nur auf das Startdatum; über ein Jahr ist das vernachlässigbar.
- Die gesamte Nacht wird „nautisch", sobald −18° nie erreicht wird; dann gibt es trotzdem `usableMillis` und einen (niedrigeren) Score – anders als in `AstroWeather`, wo solche Nächte `NO_DARKNESS` sind.
- Tests: `ObservationPlannerTest` (M31 Herbst vs. Sommer, Winter-/Sommernacht Berlin, Mittsommer ehrlich ohne astronomische Dunkelheit, Polartag/Polarnacht, geschlossenes Dunkelfenster stimmt mit abgetastetem überein, Sonne im Fenster wirklich unter Schwelle, nie aufgehende/zirkumpolare Objekte, `usable ≤ dark`, bester Moment im Fenster, Saison inkl. Lücken, gestreute beste Nächte, Mond senkt Score, Grenzfälle von `hourAngleAtAltitude`); außerdem `NightOutlookTest`, `EphemerisTransitTest`, `PlannedPathTest`.

#### [`AstroWeather.kt`](app/src/main/java/com/starwindow/app/domain/AstroWeather.kt)

Verknüpft die stündliche Wettervorhersage (`data/weather/WeatherForecast`, `WeatherHour`) mit Sonnen- und Mondstand zu einer Bewertung je Stunde (0–100) und je Nacht (Urteil mit „Haken"). Grundlage der Wetteransicht.

| Element | Art | Aufgabe |
|---|---|---|
| `NightLimiter` | enum | Was eine Stunde am meisten verdirbt: `NONE`, `TWILIGHT`, `CLOUDS`, `MOON`, `PRECIPITATION`, `DEW`, `WIND`. |
| `AstroHour` | data class | Eine bewertete Stunde: `millis`, unveränderte `weather`, `sunAltitudeDeg`, `moonAltitudeDeg`, `moonIllumination`, `score`, `limiter`; abgeleitet `phase`, `cloudPercent`, `isDark` (Sonne ≤ −18°), `moonUp`. |
| `NightVerdict` | enum | `GOOD` („geeignet"), `PARTLY`, `POOR`, `NO_DARKNESS`, `UNKNOWN`. |
| `AstroNight` | data class | Eine Nacht Mittag–Mittag (`times: NightTimes` aus `Twilight`): alle Stunden, `clearDarkMillis`, `usableDarkMillis`, `bestScore`, `verdict`, mittlere/minimale Bewölkung im Dunkeln, Mondbeleuchtung zur Mitte, höchster Mondstand im Dunkeln. |
| `AstroNight.bestHour` | val | Beste Stunde im Dunkeln (sonst insgesamt). |
| `AstroNight.moonSpoilsTheNight` | val | Klar, aber `usableDarkMillis < 45 min` und `bestHour.limiter == MOON`. |
| `AstroNight.stargazingRating` / `ratingLabel` | val | Gesamtzahl der Nacht und Wort dazu. |
| `AstroNight.USABLE_SCORE` | const | `50` – ab hier ist eine Stunde „brauchbar". |
| `CLEAR_CLOUD_PERCENT` | const | `30.0` % |
| `GOOD_NIGHT_MILLIS` / `PARTLY_NIGHT_MILLIS` | const | 90 min / 45 min |
| `nights(forecast, location, nowMillis, maxNights = 15)` | fun | Liste der Nächte, die die Vorhersage trägt. |
| `nightOf(date, times, zone, forecast, location)` | fun | Eine Nacht zusammensetzen. |
| `evaluate(hour, location)` | fun | Sonne/Mond rechnen und `rate` aufrufen. |
| `rate(hour, sunAlt, moonAlt, moonFraction)` → `Rating` | fun / data class | Die vier Faktoren und der Limiter; `Rating.score = round(d·c·m·cond·100)`. |
| `darknessFactor`, `cloudFactor`, `moonFactor`, `dewFactor`, `windFactor` | fun | Einzelfaktoren 0–1 (öffentlich, u. a. für das Diagramm). |

**Funktionsweise – Stundenbewertung (`rate`):** Produkt, keine Summe – jeder Faktor kann allein alles kippen.
- `darknessFactor(Sonnenhöhe)`: ≤ −18° → 1.0; −18…−12° → linear 1.0…0.55; −12…−6° → 0.55…0.15; −6°…`Twilight.HORIZON_DEG` (−0.833°) → 0.15…0; darüber 0.
- `cloudFactor(Bedeckung %)`: `(1 − c/100)^1.5`; fehlt die Angabe → 1.0. Bedeckung = `WeatherHour.effectiveCloudPercent` (Gesamt, sonst dichteste Schicht).
- `moonFactor(Höhe, Beleuchtungsanteil)`: unter Horizont 1.0, sonst `1 − 0.85 · f^1.5 · √sin(h)`, begrenzt auf 0.15…1.
- Bodenbedingungen: Niederschlag `≥ 0.05` mm → Faktor 0 (Abbruch, Limiter `PRECIPITATION`); sonst `dew · wind`.
  - `dewFactor`: Taupunktdifferenz `< 1 K` → 0.75, `< 3 K` → 0.90, sonst 1.0; zusätzlich Feuchte `≥ 95 %` → ×0.85.
  - `windFactor`: Böen bevorzugt: `> 40` km/h → 0.60, `> 25` → 0.85; ohne Böen Mittelwind: `> 30` → 0.70, `> 20` → 0.90.
- Limiter = Faktor mit dem kleinsten Wert (Dämmerung, Wolken, Mond, Tau, Wind); ist auch der kleinste `> 0.92`, heißt er `NONE`.

**Funktionsweise – Nacht (`nightOf`):**
1. Stunden im Fenster `times.fromMillis..toMillis` bewerten.
2. `durationWhere`: Für jedes Paar benachbarter Stützstellen wird das Intervall (auf das astronomische Dunkelfenster beschnitten) ganz gezählt, wenn **beide** Endpunkte die Bedingung erfüllen. Funktioniert auch bei 3-h-Raster (ECMWF hinten im Lauf).
3. `clearDarkMillis`: Bedeckung `≤ 30 %` und Niederschlag `< 0.05` mm. `usableDarkMillis`: `score ≥ 50`.
4. `verdictOf`: keine Stunden → `UNKNOWN`; `darkDurationMillis == 0` → `NO_DARKNESS`; klar `≥ 90 min` → `GOOD`; `≥ 45 min` → `PARTLY`; minimale Bedeckung im Dunkeln `≤ 60 %` → `PARTLY`; sonst `POOR`. Das Urteil zählt nur Wolken/Regen – der Mond wird separat über `moonSpoilsTheNight` gemeldet (README „Der Haken").
5. `stargazingRating = bestScore · (0.6 + 0.4 · usableDark/darkDuration)`, begrenzt 0–100. Eine Nacht mit nur einer guten Stunde behält mindestens 60 % ihres Bestwerts. `ratingLabel`: ≥ 75 „Sehr gut", ≥ 55 „Gut", ≥ 35 „Mäßig", ≥ 15 „Schlecht", sonst „Aussichtslos" (plus „keine Daten"/„keine Dunkelheit").

**`nights`:** Die erste Nacht ist vor 6 Uhr Ortszeit (`MORNING_CUTOFF_HOUR`) die von gestern, sonst die heutige. Es werden bis zu 15 Nächte erzeugt; abgebrochen wird, sobald der Sonnenuntergang (ersatzweise die Fenstermitte) hinter der letzten Vorhersagestunde liegt.

**Zusammenspiel:** `ui/weather/WeatherViewModel` (`nights`), `ui/weather/WeatherScreen` (Konstanten, `moonSpoilsTheNight`), `ui/weather/NightRatingCard` (`stargazingRating`, `ratingLabel`), `ui/weather/AstroWeatherChart` (`darknessFactor` für die Hintergrundfärbung); `NightOutlooks` ruft `nightOf`, `WatchEvaluator` nutzt `AstroHour.score` und `USABLE_SCORE`. Abhängig von `core/astro/Twilight` (`NightTimes`, `HORIZON_DEG`), `SolarEphemeris`, `LunarEphemeris`, `CoordinateTransforms`.

**Ändern/Fallstricke:**
- „Dunkel" ist hier immer astronomisch (`NightTimes.isDark`). Nächte ohne −18° (Mittsommer in Berlin) sind `NO_DARKNESS` – auch wenn `ObservationPlanner` sie als nautisch brauchbar führt.
- `AstroHour.isDark` hartcodiert `−18.0`; `darknessFactor` hartcodiert −18/−12/−6 statt `ObservationPlanner`-Konstanten zu nutzen.
- Der Mond-Faktor hier (`moonFactor`) ist eine andere Formel als in `ObservationPlanner.score` und in `PhotoGuide.moonFactor` – drei getrennte Mondmodelle.
- Tests: `AstroWeatherTest` (Taupunktdifferenz, Bewölkungs-Rückfall, Dunkelheitsfaktor monoton, „halbe Bedeckung kostet mehr als die Hälfte", Mond nur über Horizont, Regen beendet die Nacht, perfekte Stunde = 100 ohne Limiter, Limiter = schlechtester Faktor, Böen/Wind, klare Novembernacht/bedeckte Nacht, 6-Uhr-Grenze, Liste endet mit dem Lauf, beste Stunde im Dunkeln, Vollmondnacht markiert, Mittsommer Berlin / Zürich; enthält außerdem Bortle- und Wetter-Parser-Tests); `StargazingRatingTest` (Gesamtzahl und Label).

#### [`NightOutlook.kt`](app/src/main/java/com/starwindow/app/domain/NightOutlook.kt)

Dampft eine `AstroNight` auf die eine Textzeile für Benachrichtigungen und Kalenderkacheln ein – und nennt immer Modell und Abrufzeit. `NightOutlooks` trennt sauber „Nacht liegt außerhalb des Vorhersagelaufs" (`null`) von „Nacht wird schlecht".

| Element | Art | Aufgabe |
|---|---|---|
| `NightOutlook` | data class | `date`, `night: AstroNight`, `model: WeatherModel`, `fetchedAtMillis`, `nowMillis`. |
| `verdict`, `clearHours`, `usableHours`, `cloudPercent` | val | Durchgereicht aus `AstroNight` (Stunden als `Double`). |
| `ageMillis` / `isStale` | val | Alter des Abrufs; veraltet ab `STALE_AFTER_MILLIS` = 6 h. |
| `isPromising` / `isDiscouraging` | val | `GOOD` bzw. `POOR`/`NO_DARKNESS`. |
| `headline` | val | z. B. „geeignet · 4,2 h klar · Mond 78 %" oder „ungeeignet · 92 % bedeckt, nur 0,5 h klar". |
| `source` | val | „ICON-D2, abgerufen heute 14:05" / „gestern …" / „vor N Tagen". |
| `fullLine` | val | „Prognose: headline (source)". |
| `NightOutlook.hours(value)` | fun (companion) | Formatiert `%.1f h` mit `Locale.GERMAN` (Komma). |
| `NightOutlooks.forDate(forecast, location, date, zone, nowMillis)` | fun | `NightOutlook` oder `null`, wenn keine Vorhersagestunde in der Nacht liegt. |
| `NightOutlooks.forDates(...)` | fun | Dasselbe für mehrere Daten als `Map`. |

**Funktionsweise:** `forDate` holt `Twilight.nightOf(date, location, zone)`, prüft per `covers`, ob überhaupt eine Vorhersagestunde **in** der Nacht liegt (nicht nur: Lauf endet nach Nachtbeginn), ruft `AstroWeather.nightOf` und verwirft Nächte ohne Daten. Der Mond wird in der `headline` erwähnt, wenn `moonSpoilsTheNight` oder (Mond > 10° hoch **und** ≥ 60 % beleuchtet).

**Zusammenspiel:** `data/weather/NightWeatherSource` (`forDate`; liefert frische oder gespeicherte Ausblicke für Erinnerungen und Merkliste), `ui/planning/CalendarViewModel` (`forDates` für Kalenderkacheln), `data/planning/ReminderNotification` (`headline`, `isDiscouraging`), `ui/planning/SessionSheet`, `ui/planning/CalendarScreen`; `WatchEvaluator` / `WatchHit.detail`. Laut DEV_PLAN §11.4 S3 bildet der Import aus `data/weather` und `data/planning` einen Zyklus `data → domain → data`.

**Ändern/Fallstricke:**
- `relativeTime` rechnet in der Zone der Nacht, nicht der Gerätezone.
- `isStale` und `fullLine` werden im Produktivcode derzeit nicht verwendet (nur `isStale` im Test).
- Tests: `NightOutlookTest` (klare/bedeckte Nacht, Nacht hinter dem Lauf → `null`, Alter ausgeschrieben, Komma in Stunden; dazu die `WatchEvaluator`-Fälle).

#### [`BortleScale.kt`](app/src/main/java/com/starwindow/app/domain/BortleScale.kt)

Die neun Bortle-Stufen mit Texten und eine **Schätzung** der Stufe aus Einwohnerzahl des nächsten Ortes und Entfernung dazu (kein Lichtatlas, keine Netzabfrage). Ein von Hand gesetzter Wert hat immer Vorrang.

| Element | Art | Aufgabe |
|---|---|---|
| `BortleClass` | data class | `level`, `title`, `sky`, `consequence`, `nakedEyeLimit`. |
| `CLASSES` | val | Die neun Stufen. |
| `forLevel(level)` | fun | Stufe 1–9 (geklemmt). |
| `estimate(place)` | fun | Schätzung aus `place.population` (> 0) und `place.populationDistanceKm`; sonst `null`. |
| `resolve(place)` | fun | `place.bortleOverride ?: estimate(place)` – die angezeigte Stufe. |
| `brightnessRatio(population, distanceKm)` | fun | Künstliche Aufhellung relativ zum natürlichen Himmel. |
| `insideRatio(population)` | fun | Aufhellung im Ort. |
| `settlementRadiusKm(population)` | fun | Radius der bebauten Fläche. |
| `levelFor(ratio)` | fun | Verhältnis → Stufe. |

**Funktionsweise:**
1. Im Ort (`insideRatio`): `< 500` Einw. → 0.07; `< 5 000` → 0.20; `< 25 000` → 0.47; `< 100 000` → 0.93; `< 500 000` → 1.87; sonst 3.50.
2. Radius: `√(Einw. / (1500 · π))` km, mindestens 0.3 km (Dichte 1500/km², `SETTLEMENT_DENSITY_PER_KM2`).
3. Außerhalb des Radius: Walkers Gesetz `inside · (d/r)^−2.5`; innerhalb konstant.
4. `levelFor`: `< 0.01` → 1, `< 0.02` → 2, `< 0.04` → 3, `< 0.12` → 4, `< 0.33` → 5, `< 0.66` → 6, `< 1.32` → 7, `< 2.64` → 8, sonst 9 (Klassengrenzen nach Falchi u. a.).

**Zusammenspiel:** `ui/weather/WeatherViewModel` (`resolve`), `ui/weather/WeatherScreen` (`forLevel`, `CLASSES` für die Erklärliste), `ui/search/ObjectSearchViewModel` (`resolve` → `SkyConditions.at`), `ui/guide/PhotoGuideViewModel` (`estimate` als Vorschlag), `ui/guide/PhotoGuideScreen` (`CLASSES`). `WeatherPlace` (mit `population`, `populationDistanceKm`, `bortleOverride`) kommt aus `data/weather`.

**Ändern/Fallstricke:** Das Modell kennt nur **einen** Ort – Lichtglocken benachbarter Großstädte fehlen (KDoc). Die Monotonie (mehr Einwohner = heller, mehr Abstand = dunkler) und Stetigkeit am Ortsrand werden in `AstroWeatherTest` geprüft („the class boundaries follow the brightness ratio", „a bigger town means a brighter sky", „distance makes it darker, monotonically", „inside the built-up area the distance does not matter yet", „a set value beats the estimate", „every class carries its description"). Wer `insideRatio` ändert, muss auf diese Monotonie achten.

#### [`WatchEvaluator.kt`](app/src/main/java/com/starwindow/app/domain/WatchEvaluator.kt)

Fachlogik der Merkliste: Prüft, ob ein vorgemerktes Objekt (`data/planning/WatchedObject`) in einer bestimmten Nacht gleichzeitig hoch genug steht **und** die Vorhersage mitspielt. Gemeldet wird die **Überschneidung** beider Zeiträume.

| Element | Art | Aufgabe |
|---|---|---|
| `WatchHit` | data class | Treffer: `watch`, `date`, `night: ObservationNight`, `outlook: NightOutlook`, `goodFromMillis`/`goodToMillis`; `goodMillis`, `goodHours`, `bestAltitudeDeg`. |
| `WatchHit.headline(zone)` | fun | „4,2 h ab 22:10 · bis 62° hoch". |
| `WatchHit.detail(zone)` | fun | Mehrzeilig: Fenster, höchster Stand, ggf. Mond, Prognose-`headline` und `source`. |
| `WatchEvaluator.evaluate(watch, observer, zone, date, outlook)` | fun | Ein Treffer oder `null`. |
| `WatchEvaluator.evaluateAll(...)` | fun | Für die ganze Liste, nach `goodMillis` absteigend. |

**Funktionsweise (`evaluate`):**
1. `watch.mayNotifyFor(date)`: Eintrag aktiv und seit der letzten Meldung mindestens `quietNights` Nächte vergangen (Ruhezeit, Default 3 Nächte, definiert in `WatchedObject`).
2. Ohne `outlook` → kein Treffer (Absicht: ohne Wetterwissen keine Meldung). `accepts`: `WeatherDemand.GOOD_ONLY` verlangt `NightVerdict.GOOD`, `ALSO_PARTLY` auch `PARTLY`.
3. `ObservationPlanner.nightFor` mit `watch.position` (J2000) und `watch.minAltitudeDeg` (Default 30°); verlangt `isWorthwhile` und einen längsten Abschnitt `usableFrom/To`.
4. `bestOverlap`: Längster zusammenhängender Abschnitt innerhalb `usableFrom..usableTo`, in dem benachbarte Vorhersagestunden **beide** `score ≥ AstroNight.USABLE_SCORE` (50) haben; aneinandergrenzende Segmente werden verschmolzen.
5. Länge `≥ watch.minUsableMillis` (Default 1 h) → `WatchHit`.

**Zusammenspiel:** `data/planning/WatchCheckReceiver` (tägliche Prüfung: holt `NightWeatherSource.fresh(tonight)` mit 25 s Netzbudget, sonst `stored`, ruft `evaluateAll`, postet über `WatchNotifications`, vermerkt danach `markNotified`); `data/planning/WatchNotification` (formatiert `WatchHit`). Die Ruhezeit-Logik selbst liegt in `WatchedObject.mayNotifyFor`.

**Ändern/Fallstricke:**
- Nur der **längste** astronomische Abschnitt (`usableFrom/To`) wird betrachtet, nicht die Summe.
- Da `accepts` ein `GOOD`/`PARTLY`-Urteil verlangt und `AstroWeather` ohne astronomische Dunkelheit `NO_DARKNESS` liefert, meldet die Merkliste in rein nautischen Nächten (Mittsommer nördlich ~49°) nie.
- `WeatherDemand.acceptsPartly` existiert, wird hier aber nicht benutzt (`accepts` prüft per `when`).
- Tests: `NightOutlookTest` (klare Nacht + hohes Objekt = Treffer, Wolken = kein Treffer, ohne Vorhersage kein Treffer, eine klare Stunde nur bei `ALSO_PARTLY`, Ruhezeit unterdrückt zweite Meldung, nicht erfüllbare Anforderung, nie hoch genug, Fenster passt zur Summe, strengere Mindesthöhe verkürzt das Fenster).

#### [`TonightBoard.kt`](app/src/main/java/com/starwindow/app/domain/TonightBoard.kt)

Was der Suchbildschirm zeigt, bevor etwas eingetippt wird: drei kurze Abschnitte statt einer Katalogliste – „Jetzt im Fenster", „Steht jetzt hoch", „Kommt noch hoch".

| Element | Art | Aufgabe |
|---|---|---|
| `TonightSection` | enum | `IN_WINDOW`, `HIGH_NOW`, `RISING` mit Überschrift. |
| `TonightEntry` | data class | `obj`, `altitudeDeg`, `feasibility`, `minutesLeftInWindow` (nur IN_WINDOW), `risesAtMillis` (nur RISING). |
| `TonightGroup` | data class | Abschnitt, gezeigte Einträge, `moreCount`. |
| `ENTRIES_PER_SECTION` | const | 6 |
| `MIN_ALTITUDE_DEG` | const | 20.0° |
| `build(objects, observer, conditions, nowMillis, inWindowNow)` | fun | Baut die Gruppen; leere Gruppen werden weggelassen, ohne Beobachter leere Liste. |

**Funktionsweise:**
1. `IN_WINDOW`: Die vom Aufrufer gelieferten Objekte (mit Restminuten) – sortiert nach wenigster Restzeit.
2. Für alle übrigen Objekte mit `PhotographicInterest.isPhotoTarget` (Sterne fallen raus) die aktuelle Höhe (präzediert, mit Refraktion):
   - `≥ 20°` und `Feasibility.isPossible` → `HIGH_NOW`, sortiert nach `appeal = PhotographicInterest.score + 0.15 · Höhe + Bonus` (EASY +10, OK +4).
   - `−30° < Höhe < 20°` → `risingTime`: Abtastung alle 15 min (`RISING_STEP_MILLIS`) bis 6 h voraus (`RISING_HORIZON_HOURS`), erster Zeitpunkt ≥ 20°. Vorfilter: Objekte mit `90 − |φ − δ| < 20°` (nicht bewegt) werden übersprungen. Feasibility wird für 20° Höhe angenommen. Sortiert nach Aufgangszeit, dann `PhotographicInterest.score`.
3. Je Abschnitt maximal 6 Einträge, Rest als `moreCount`.

**Zusammenspiel:** `ui/search/ObjectSearchViewModel` (ruft `build` mit `state.conditions` und `inWindow`), `ui/search/ObjectSearchScreen` (rendert `TonightEntry`). Nutzt `Feasibility`, `PhotographicInterest`, `Precession`, `CoordinateTransforms`.

**Ändern/Fallstricke:** Die Tafel prüft **nicht**, ob es überhaupt dunkel ist – tagsüber erscheinen dieselben Vorschläge. Die Restzeit im Fenster rechnet der Aufrufer (nur er kennt die Fenster). Tests: `TonightBoardTest` in `ObjectSearchTest.kt` (nur Hochstehendes, ohne Position nichts, Abschnittslimit und `moreCount`, helle Sterne verdrängen keine Deep-Sky-Ziele, Fensterabschnitt nach Restzeit, nichts doppelt, jede Zeile mit Urteil).

#### [`SeasonalHighlights.kt`](app/src/main/java/com/starwindow/app/domain/SeasonalHighlights.kt)

Fachlogik des Reiters „Motive" (Stargazing Hub): die besten Motive einer Jahreszeit für einen Ort, berechnet für eine konkrete Nacht. Enthält außerdem die Jahreszeiten (`Season`), die Gruppierung (`TargetKind`) und die kuratierte Beliebtheitsliste (`Popularity`).

| Element | Art | Aufgabe |
|---|---|---|
| `Season` | enum | `SPRING`/`SUMMER`/`AUTUMN`/`WINTER` mit `label`, `subtitle`, `story`. |
| `Season.of(date, latitudeDeg)` | fun | Meteorologische Jahreszeit nach Monat (MAM/JJA/SON/DJF); südlich des Äquators um 6 Monate verschoben. |
| `TargetKind` | enum | `NEBULA`, `GALAXY`, `CLUSTER`, `OTHER`; `of(ObjectType)` – `CLUSTER_NEBULA` zählt als Nebel. |
| `SeasonalTarget` | data class | `obj`, `night: ObservationNight`, `kind`, `popularity`, `interest`, `score`; `usableHours`, `isAtItsBest` (`night.score ≥ 75`), `headline`. |
| `MIN_ALTITUDE_DEG` | const | 20.0° (bewusst niedriger als die 30° der Jahresplanung – M42 erreicht von Berlin nur ~32°). |
| `MIN_USEFUL_MILLIS` | const | 45 min |
| `build(objects, observer, zone, date, limit = 24)` | fun | Bewertete, entdoppelte Motivliste. |
| `rows(targets)` | fun | Gruppiert nach `TargetKind`; Reihen sortiert nach dem besten Score ihres ersten Eintrags. |
| `referenceDate(season, today, latitudeDeg)` | fun | Laufende Jahreszeit → heute; sonst erster Tag ihres nächsten Auftretens + 45 Tage. |
| `Popularity.of(obj)` | fun | 100 (ICONIC), 78 (CLASSIC), 56 (KNOWN) oder 0 – höchster Wert über alle Bezeichnungen. |

**Funktionsweise (`build`):**
1. `candidates`: nur nicht bewegte Fototargets (`!isMoving && PhotographicInterest.isPhotoTarget`). Alle kuratierten (`Popularity > 0`) plus die 420 (`CANDIDATE_LIMIT`) nach `PhotographicInterest.score` besten übrigen.
2. Je Kandidat `ObservationPlanner.nightFor(..., minAltitudeDeg = 20)`; unter 45 min nutzbar → verworfen.
3. `score = 0.40 · night.score + 0.35 · popularity + 0.25 · interest` (alle 0–100).
4. Absteigend sortieren, `dropDuplicates`: ein Eintrag näher als 0.75° (`DUPLICATE_SEPARATION_DEG`, J2000-Abstand über `SphericalGeometry`) an einem bereits behaltenen fällt weg (z. B. Rosette `NGC 2237/2238`, Cirrus in Teilen). Dann `take(limit)`.

`Popularity` vergleicht Bezeichnungen über `ObjectSearch.fold`, damit „M 45", „M45" usw. gleich behandelt werden. Die Listen `ICONIC`, `CLASSIC`, `KNOWN` sind von Hand gepflegt (Quelle laut KDoc: gängige Saisonlisten).

**Zusammenspiel:** `ui/hub/StargazingHubViewModel` (`Season.of`, `referenceDate`, `build`, `rows`), `ui/hub/StargazingHubScreen` (`headline`, `isAtItsBest`, `usableHours`); `Season.of` außerdem in `ui/guide/PhotoGuideViewModel` (Tau-Rückfall im `PhotoGuide`). Ruft `ObservationPlanner.nightFor`, `PhotographicInterest`, `ObjectSearch.fold`, `SphericalGeometry`.

**Ändern/Fallstricke:**
- Die Auswahl hängt stark von `PhotographicInterest.score` (anderer Bereich) ab – Änderungen dort verschieben auch den Hub.
- `SeasonalTarget.headline` formatiert mit `String.format` ohne Locale (Gerätesprache), anders als `NightOutlook.hours` (fest `Locale.GERMAN`).
- `build` rechnet je Kandidat eine Nacht (~ einige Hundert `nightFor`-Aufrufe); der ViewModel führt das auf `Dispatchers.Default` aus.
- Tests: `SeasonalHighlightsTest` (Nord-/Südjahreszeiten, laufende Saison = heute, Beliebtheit über alle Bezeichnungen, Orion führt den Winter / Andromeda den Herbst über Berlin, Hemisphären unterscheiden sich, Ranking verschiebt sich innerhalb der Saison, Polarsommer leer, keine Doppelten, nur Fototargets, Galaxien dominieren den Frühling, `rows` partitioniert).

#### [`PhotoGuide.kt`](app/src/main/java/com/starwindow/app/domain/PhotoGuide.kt)

Rechnet für Motiv + Smart-Teleskop (`data/gear/SmartTelescope`) + Bortle + Mond + Nacht einen fertigen Aufnahmeplan: Filter ja/nein, Einzelbelichtung, Mindest- und empfohlene Gesamtzeit, Bildausschnitt/Mosaik, Taurisiko und Gesamturteil. Grundlage ist ein einziger Zusammenhang: `t ∝ Hintergrund / Signal²`. Eine Gain-Empfehlung gibt es im Code nicht.

| Element | Art | Aufgabe |
|---|---|---|
| `DewRisk` | enum | `NONE`, `POSSIBLE`, `LIKELY`, `CERTAIN`; `needsAction`. |
| `FramingVerdict` | enum | `TOO_SMALL`, `SMALL`, `GOOD`, `TIGHT`, `NEEDS_MOSAIC`. |
| `GuideVerdict` | enum | `GOOD`, `MARGINAL`, `POOR`. |
| `PhotoPlan` | data class | Alle Ergebnisse; abgeleitet `subCount`, `fitsInTonight`, `nightCoverage`, `nightsNeeded` (Verteilung über mehrere Nächte, `null` bei einer). |
| `PhotoGuide.plan(obj, telescope, bortle, moonIlluminationPercent, moonAltitudeDeg, moonSeparationDeg?, dewSpreadK?, humidityPercent?, season?, availableHours)` | fun | Der Plan. |
| `skyFactor(bortle)` | fun | `10^(0.4·(SB(4) − SB(bortle)))` relativ zu Bortle 4 (`REFERENCE_BORTLE`). |
| `moonFactor(illum, alt, sep?)` | fun | Mondanteil am Hintergrund (≥ 1). |
| `subExposure(telescope, effectiveSky)` | fun | Längste erlaubte Einzelbelichtung. |
| `framing(obj, telescope)` | fun | Bildausschnitt-Urteil. |
| `dewRisk(spread?, humidity?, season?)` | fun | Taurisiko. |
| `FilterKind.skySuppression` / `transmissionFor(obj)` | private ext | Filterwirkung auf Himmel / Objekt. |

**Funktionsweise (`plan`):**
1. Himmel: `sky = skyFactor(bortle) · moonFactor(...)`. `SKY_BRIGHTNESS` (mag/arcsec²): 1→22.0, 2→21.7, 3→21.5, 4→21.3, 5→20.8, 6→20.3, 7→19.5, 8→18.5, 9→17.8.
2. Mond: unter Horizont oder `< 5 %` → 1.0; sonst `1 + 12 · phase² · sin(h) · (0.35 + 0.65 · Nähe)`, Nähe = `1 − Abstand/180°` (unbekannt: 0.5). `MOON_MAX_FACTOR = 12`.
3. Belichtungsfaktor je Filter: `sky / skySuppression / transmission² · difficulty(obj)`.
   - `skySuppression`: kein Filter 1.0, Breitband 1.6, Dualband 4.0.
   - `transmission`: Breitband 0.9 (Emissionsobjekt, `type.respondsToNarrowband`) / 0.75 (sonst); Dualband 0.85 / 0.12.
   - `difficulty`: `10^(0.4·(SB − 21.5))`, begrenzt 0.3…8; SB = Katalog-Flächenhelligkeit oder geschätzt `mag + 2.5·log10(π/4·a·b) + 8.89` (Umrechnung □′ → □″).
4. Filter an, wenn das Gerät einen hat und `gefiltert < 0.9 · ungefiltert`.
5. Zeiten: `minimum = 6 min (BASE_MINIMUM_MINUTES) · Faktor / telescope.lightGrasp` (`lightGrasp = (Öffnung/50 mm)²`); `minimumMinutes` auf 5…600 begrenzt, `recommendedMinutes = 2.5 · minimum` auf 10…1200.
6. Einzelbelichtung über `effectiveSky = sky / skySuppression` (falls Filter): `≥ 8` → max 10 s, `≥ 3` → 20 s, `≥ 1.2` → 30 s, sonst 60 s; gewählt wird der längste Wert aus `telescope.subExposuresSec`, der darunter liegt (sonst der kürzeste).
7. Framing: Größe fehlt → `SMALL`; `< 40` px (`MIN_USEFUL_PIXELS`) → `TOO_SMALL`; Füllgrad der **kurzen** Bildkante `> 1.0` → `NEEDS_MOSAIC`, `> 0.75` → `TIGHT`; `< 120` px (`SMALL_PIXELS`) → `SMALL`; sonst `GOOD`. `needsMosaic` ist bei `NEEDS_MOSAIC` **und** `TIGHT` gesetzt.
8. Tau: Taupunktdifferenz `< 2` K → `CERTAIN`, `< 4` → `LIKELY`, `< 7` → `POSSIBLE`; sonst Feuchte `≥ 92` → `CERTAIN`, `≥ 85` → `LIKELY`, `≥ 70` → `POSSIBLE`; sonst Jahreszeit: Herbst `LIKELY`, alle anderen/unbekannt `POSSIBLE`. `dewAction` unterscheidet Gerät mit/ohne Heizung (`hasDewHeater`).
9. Urteil: `TOO_SMALL` → `POOR`; `NEEDS_MOSAIC` ohne Mosaikmodus → `POOR`; verfügbare Zeit `< 0.6 · minimumMinutes` → `POOR`; `< minimumMinutes` → `MARGINAL`; sonst `GOOD`.

**Zusammenspiel:** `ui/guide/PhotoGuideViewModel` liefert die Eingaben: `availableHours` aus `ObservationPlanner.nightFor(..., minAltitudeDeg = 20)`, Mond zum besten Moment der Nacht (Höhe, Beleuchtung, Abstand zum Objekt), Taupunkt/Feuchte aus der zwischengespeicherten Vorhersage (nur wenn eine Stunde nahe genug liegt), `Season.of` als Rückfall, Bortle vom Nutzer oder `BortleScale.estimate`. `ui/guide/PhotoGuideScreen` zeigt `PhotoPlan`. Gerätedaten in `data/gear/SmartTelescope.kt` (`SmartTelescopes.ALL`: Seestar S30, S30 Pro, S50, DWARF 3, DWARF Mini).

**Ändern/Fallstricke:**
- Alle Geräte haben derzeit `FilterKind.DUAL_BAND`; der Breitband-Zweig ist nur über Tests/Zukunft erreichbar.
- `filterSpeedup = unfiltered / filtered` wird auch dann gesetzt, wenn kein Filter gewählt ist (Wert < 1 = Filter würde verlangsamen).
- Tests: `PhotoGuideTest` (Filter an für Emissionsnebel, aus für Galaxie, auch unter dunklem Himmel; kein Filter ohne Filter im Gerät; hellerer Himmel / kleinere Öffnung / naher Vollmond kosten Zeit; Mond unter Horizont kostenlos; heller Himmel verkürzt Einzelbelichtung; Empfehlung > Minimum; M31 braucht überall Mosaik; winziger PN lohnt nicht; Framing je Gerät; Tau-Kaskade und gerätegerechte Ratschläge; Nacht zu kurz; `nightsNeeded`; gute Nacht = `GOOD`).

#### Typische Änderungen in diesem Bereich

- **Gewichtung der stündlichen Nachtbewertung ändern:** In `AstroWeather` die Faktorfunktionen (`darknessFactor`, `cloudFactor`, `moonFactor`, `dewFactor`, `windFactor`) anpassen. Bleibt es beim Produkt in `Rating.score`, kippt weiterhin jeder Faktor allein die Stunde. Limiter-Schwelle `0.92` in `rate` mitbedenken. Danach `AstroWeatherTest` (z. B. „half cover costs more than half", „a perfect hour scores a hundred…") und `StargazingRatingTest` prüfen; `AstroWeatherChart` nutzt `darknessFactor` für die Färbung.
- **Schwellen „klar"/„geeignet" ändern:** `AstroWeather.CLEAR_CLOUD_PERCENT`, `GOOD_NIGHT_MILLIS`, `PARTLY_NIGHT_MILLIS`, die 60-%-Regel in `verdictOf`, `AstroNight.USABLE_SCORE`. `USABLE_SCORE` wirkt auch auf `WatchEvaluator.bestOverlap` (Merkliste) und `stargazingRating`. Der Hinweistext in `ui/weather/WeatherScreen` liest `CLEAR_CLOUD_PERCENT` automatisch.
- **Gesamtzahl/Label der Nacht ändern:** `AstroNight.stargazingRating` (Untergrenze 0.6) und `ratingLabel`-Stufen; `StargazingRatingTest`; Anzeige in `ui/weather/NightRatingCard`.
- **Mindesthöhe für die Jahresplanung ändern:** `ObservationPlanner.MIN_USEFUL_ALTITUDE_DEG` (wirkt auf `plan`, Default von `nightFor`, Text in `PlanningScreen`). Andere Mindesthöhen sind getrennt: `WatchedObject.DEFAULT_MIN_ALTITUDE_DEG` (Merkliste), `SeasonalHighlights.MIN_ALTITUDE_DEG` (Hub), `PhotoGuideViewModel.MIN_ALTITUDE_DEG` (Guide), `TonightBoard.MIN_ALTITUDE_DEG`, `Feasibility.LOW_ALTITUDE_DEG`. `ObservationPlannerTest` prüfen.
- **Score der Planungsnacht oder Saisondefinition ändern:** `ObservationPlanner.score` (55/30/15|5/−25), `MIN_WORTHWHILE_MILLIS` (Saison, ganzjährig), `isYearRound`-Quote 0.95, `bestNights`-Abstand. Achtung: `night.score` fließt mit 40 % in `SeasonalHighlights.build` und in `SeasonalTarget.isAtItsBest` (≥ 75) ein.
- **Merklisten-Kriterium anpassen:** Wetteranforderung in `WatchEvaluator.accepts` (und ggf. neue Werte in `WeatherDemand`, `data/planning/WatchedObject.kt`); Ruhezeit und Defaults (`DEFAULT_QUIET_NIGHTS`, `DEFAULT_MIN_HOURS`, `DEFAULT_MIN_ALTITUDE_DEG`, `mayNotifyFor`) in `WatchedObject`; Fensterlogik in `bestOverlap`. Tests in `NightOutlookTest`. Die Ausführung (Zeitplan, Netzbudget, Wiederholung) liegt in `data/planning/WatchCheckReceiver`.
- **Bortle-Schätzung verbessern:** `BortleScale.insideRatio`, `SETTLEMENT_DENSITY_PER_KM2`, Exponent −2.5 in `brightnessRatio`, Grenzen in `levelFor`. Monotonie-Tests in `AstroWeatherTest`. Bortle wirkt anschließend über `SkyConditions.skyBrightness` (Feasibility) und `PhotoGuide.skyFactor` – zwei getrennte Helligkeitstabellen.
- **Neues Teleskop im Fotoguide berücksichtigen:** Neuen `SmartTelescope`-Eintrag in `SmartTelescopes` (`data/gear/SmartTelescope.kt`) anlegen und in `ALL` aufnehmen (Öffnung, Bildfeld kurz/lang, Pixelskala, `subExposuresSec` aufsteigend, Filter, Mosaik, Heizung). `PhotoGuide` braucht keine Änderung, solange der Filter einer der drei `FilterKind`-Werte ist; ein neuer Filtertyp erfordert Einträge in `skySuppression` und `transmissionFor`. `PhotoGuideTest` („the same object frames differently…", „andromeda needs a mosaic on every one of these telescopes") prüft über alle Geräte.
- **Belichtungsempfehlung kalibrieren:** `BASE_MINIMUM_MINUTES`, `RECOMMENDED_MULTIPLE`, `REFERENCE_SURFACE_BRIGHTNESS`, `MOON_MAX_FACTOR`, Grenzen in `subExposure`, Klemmungen 5…600 / 10…1200 in `plan`.
- **Motive im Hub beeinflussen:** Kuratierte Listen in `Popularity` (`ICONIC`/`CLASSIC`/`KNOWN`, Bezeichnungen wie im Katalog, Leerzeichen egal), Gewichte 0.40/0.35/0.25 in `SeasonalHighlights.build`, `CANDIDATE_LIMIT`, `DUPLICATE_SEPARATION_DEG`. `SeasonalHighlightsTest` (Orion/Andromeda-Erwartungen) prüfen.
- **„Heute Abend"-Tafel ändern:** `TonightBoard.MIN_ALTITUDE_DEG`, `ENTRIES_PER_SECTION`, `RISING_HORIZON_HOURS`, `appeal`-Gewichte; Tests in `TonightBoardTest` (`ObjectSearchTest.kt`).

<a id="pkg-ui-nav"></a>

### `ui/nav` — Navigation und untere Leiste

Das Paket enthält den einzigen `NavHost` der App und die selbst gebaute untere Leiste. Hier wird für jeden Bildschirm das ViewModel aus dem `AppContainer` erzeugt und mit Navigations-Callbacks verbunden; die Bildschirme selbst kennen den `NavController` nicht.

**Benutzt:** `androidx.navigation.compose`, `appContainer` (`com.starwindow.app`), alle `XxxScreen`/`XxxViewModel` aus `ui/capture`, `ui/hub`, `ui/guide`, `ui/weather`, `ui/search`, `ui/planning`, `ui/calibration`, `ui/windows`, außerdem `data/planning/PlannedPath`, `ui/theme/StarWindowColors`. **Wird benutzt von:** `MainActivity` (`StarWindowNavHost()`); die Beschriftungen der Leiste von `baselineprofile/Journeys.kt`.

#### [`StarWindowNavHost.kt`](app/src/main/java/com/starwindow/app/ui/nav/StarWindowNavHost.kt)

Definiert alle Routen und den Navigationsgraphen.

| Element | Art | Aufgabe |
|---|---|---|
| `Routes` | object | String-Konstanten `CAPTURE`, `CALIBRATION`, `WINDOW_LIST`, `WINDOW_DETAIL`, `SEARCH`, `WEATHER`, `HUB`, `GUIDE`, `OBJECT_DETAIL`, `CALENDAR`, `PLANNING` |
| `Routes.windowDetail(id)` / `objectDetail(id)` / `planning(id)` | fun | Routen mit Argument bauen; Objekt-IDs per `Uri.encode` (Leerzeichen/Schrägstriche in „NGC 292") |
| `StarWindowNavHost(modifier, navController)` | @Composable | `Scaffold` + `NavHost` mit allen Zielen |
| `NavHostController.switchTo(route)` | private fun | Reiterwechsel ohne Stapelwachstum |

**Funktionsweise:**
- `container = LocalContext.current.appContainer`. Aus `currentBackStackEntryAsState()` wird die aktuelle Route bestimmt; ist sie eine der `BottomDestination.route`, zeigt der `Scaffold` die `StarWindowBottomBar`, sonst keine Leiste (Detailbildschirme, Suche, Kalibrierung, Planung).
- `Scaffold(containerColor = Night, contentWindowInsets = WindowInsets(0))`; der Inhalt bekommt `padding(padding).consumeWindowInsets(padding)`, damit Bildschirme mit `safeDrawingPadding` nicht noch einmal Platz für die Navigationsleiste reservieren.
- `NavHost(startDestination = Routes.CAPTURE)` ohne eigene `enterTransition`/`exitTransition` — es gilt die Standard-Überblendung der Navigation-Bibliothek (laut DEV_PLAN §11.3 P3 ca. 700 ms).
- Jeder `composable(...)`-Block erzeugt sein ViewModel per `viewModel(factory = XxxViewModel.factory(container))`; bei Routen mit Argument zusätzlich `key = id` und die ID in der Factory. Argumente: `navArgument("objectId"/"windowId") { type = NavType.StringType }`, gelesen über `entry.arguments?.getString(...)`.
- **Ziele und Übergänge:** Sucher → Kalibrierung, Suche, Objektblatt; Motive/Guide/Kalender/Suche → Objektblatt; Objektblatt → Planung. „Verfolgen"-Aktionen (Objektblatt `onStartTracking`, Fensterliste/-detail `onTrackWindow`, Kalender `onShowPath`) springen mit `popBackStack(Routes.CAPTURE, inclusive = false)` zurück in den Sucher. `onShowPath` ruft vorher in einer `rememberCoroutineScope`-Coroutine `PlannedPath.track(container, session)` auf. Der Kalibrierbildschirm bekommt zusätzlich `container.settingsStore.settings` als State.
- **Back-Stack:** `switchTo(route)` = `navigate(route) { popUpTo(CAPTURE) { saveState = true }; launchSingleTop = true; restoreState = true }`. Der Sucher bleibt immer Wurzel; Reiterwechsel ersetzen das Ziel darüber, Zustand (Scrollposition, ViewModel) des verlassenen Reiters wird gesichert und beim Zurückkehren wiederhergestellt. Zurück von einem Reiter führt daher zum Sucher, Zurück im Sucher beendet die App. Detailziele werden normal mit `navigate` obendrauf gelegt und mit `popBackStack()` verlassen.

**Zusammenspiel:** `MainActivity` → `StarWindowTheme` → `StarWindowNavHost`. Die Leiste ruft `switchTo(it.route)`.

**Ändern/Fallstricke:**
- Neue Argumentrouten immer mit Hilfsfunktion in `Routes` bauen und Freitext mit `Uri.encode` kodieren; das Dekodieren übernimmt Navigation automatisch.
- Ohne `key = id` würde ein zweites Objektblatt mit anderer ID das ViewModel des ersten wiederverwenden.
- Die Leiste erscheint nur, wenn die Route **exakt** einer `BottomDestination.route` entspricht.
- Eigene Übergänge (z. B. `enterTransition = { fadeIn(tween(150)) }`) können am `NavHost` gesetzt werden (offener Punkt P3).

#### [`StarWindowBottomBar.kt`](app/src/main/java/com/starwindow/app/ui/nav/StarWindowBottomBar.kt)

Eigene untere Leiste statt Material-`NavigationBar` (niedriger, dunkler, kein heller Rand).

| Element | Art | Aufgabe |
|---|---|---|
| `BottomDestination` | enum | 6 Reiter mit `route`, `label`, `icon`, `description`: `CAPTURE` „Sucher", `HUB` „Motive", `GUIDE` „Guide", `CALENDAR` „Kalender", `WEATHER` „Wetter", `WINDOWS` „Fenster" |
| `StarWindowBottomBar(current, onSelect, modifier)` | @Composable | `Surface` (Night, 94 % deckend) mit Haarlinie (`Outline`) und `Row(SpaceEvenly)` aller Einträge, `navigationBarsPadding()` |
| `BottomBarItem` | @Composable privat | `selectable(role = Role.Tab)`; Symbol in getönter Kapsel (`StarWindowColors.tint(WindowStroke, 0.16f)`) wenn gewählt; Beschriftung `labelSmall`, einzeilig |

Reihenfolge der Reiter = Reihenfolge der Enum-Einträge. `description` ist die `contentDescription` des Symbols (Barrierefreiheit), `label` der sichtbare Text — **den findet `Journeys.kt`**. Innenabstand bewusst eng (3 dp), damit sechs Beschriftungen auf eine Telefonbreite passen.

#### Typische Änderungen in diesem Bereich
- **Neuen Bildschirm (ohne Leiste) hinzufügen:**
  1. Screen + ViewModel mit `companion object { fun factory(container: AppContainer) = viewModelFactory { initializer { … } } }` anlegen (Muster: `WeatherViewModel.factory`); fehlende Abhängigkeiten im `AppContainer` (`StarWindowApplication.kt`) ergänzen.
  2. In `Routes` eine Konstante (`const val FOO = "foo"` bzw. `"foo/{id}"`) und ggf. Hilfsfunktion `fun foo(id: String) = "foo/${Uri.encode(id)}"` anlegen.
  3. In `StarWindowNavHost` einen `composable(Routes.FOO) { … }`-Block mit `viewModel(factory = …)` (bei Argument: `arguments = listOf(navArgument(...))`, `key = id`) und Callbacks (`onBack = { navController.popBackStack() }`) einfügen.
  4. Am Ausgangsbildschirm einen Callback-Parameter ergänzen und im NavHost mit `navController.navigate(Routes.foo(...))` verdrahten.
- **Neuen Reiter in der Leiste:** zusätzlich zu 1–3 einen Eintrag in `BottomDestination` (Position = Reihenfolge); die Leiste und die Leistensichtbarkeit folgen automatisch. Platz prüfen: sieben Beschriftungen passen evtl. nicht mehr. Danach `BOTTOM_TABS` in `baselineprofile/Journeys.kt` um das neue `label` ergänzen (Sucher zuletzt lassen).
- **Reiter umbenennen:** `label` in `BottomDestination` ändern **und** `BOTTOM_TABS` sowie ggf. `openTab("Kalender")` in `FrameBenchmarks.scrollCalendar` und `By.text("Sucher")` in `backToBottomBar` nachziehen.
- **Zurück-Verhalten ändern:** `switchTo` (Reiter) bzw. die `popBackStack(Routes.CAPTURE, …)`-Aufrufe (Verfolgen) anpassen.

<a id="pkg-ui-theme"></a>

### `ui/theme` — Farben, Typografie, Objektpalette

Zentrale Gestaltungs-Token: Farben (`StarWindowColors`), Eckenradien (`StarWindowShapes`), Abstände (`StarWindowSpacing`), Typografie und die Farbe je Objekttyp (`ObjectPalette`). Die App ist praktisch nur dunkel; viele Bildschirme verwenden die Tokens direkt statt `MaterialTheme.colorScheme`.

**Benutzt:** Material 3, `androidx.core.view.WindowCompat`, `data/catalog/ObjectType`. **Wird benutzt von:** nahezu allen `ui/*`-Dateien; `StarWindowTheme` von `MainActivity`; `ObjectPalette` von `ui/capture/SkyOverlay`, `ui/components/ObjectRow`, `ui/components/ObjectSymbol`, `ui/search/ObjectDetailScreen`, `ui/windows/WindowDetailScreen`.

#### [`Theme.kt`](app/src/main/java/com/starwindow/app/ui/theme/Theme.kt)

Farb-, Form-, Abstands- und Schrifttokens sowie die Theme-Composable.

| Element | Art | Aufgabe |
|---|---|---|
| `StarWindowColors` | object | Farbtokens (siehe unten) + `tint()` |
| `StarWindowColors.tint(accent, alpha = 0.14f)` | fun | Akzentfarbe mit reduzierter Deckkraft als Flächenfarbe (`accent.copy(alpha)`) |
| `StarWindowShapes` | val `Shapes` | Radien 8/12/18/24/28 dp (extraSmall…extraLarge) |
| `StarWindowSpacing` | object | `screen` 16 dp, `card` 16 dp, `between` 12 dp, `row` 8 dp |
| `StarWindowTypography` | private val | Material-Typografie: Titel/Headlines halbfett mit engerer Laufweite, größere Zeilenhöhen in `body*`, weitere Laufweite in `label*` |
| `MetricTextStyle` | val `TextStyle` | Große Kennzahl (30 sp, fett), z. B. Prozentzahl in `NightRatingCard` |
| `DarkScheme` / `LightScheme` | private val | Material-Farbschema aus den Tokens / Minimal-Hellschema |
| `StarWindowTheme(forceDark = true, content)` | @Composable | Setzt `MaterialTheme`; macht Status- und Navigationsleiste transparent, helle Statusleisten-Symbole aus |

**Farbtokens:**

| Token | Wert | Bedeutung / typische Verwendung |
|---|---|---|
| `Night` | `#0A0C16` | Grundhintergrund (Scaffold, Surface in `MainActivity`, Leiste) |
| `NightSurface` | `#141827` | Karten, Listen, Dialoge, Textfelder |
| `NightSurfaceHigh` | `#1E2338` | hervorgehobene Flächen, aufgeklappte Karte, Trennlinien, Chips |
| `NightSurfaceTop` | `#2A3050` | oberste Ebene, ausgewählte Elemente (z. B. Kalendertag, Sucher) |
| `Outline` | `#272D45` | Haarlinie um Karten, Leistenkante |
| `Starlight` | `#E3E7F7` | Haupttextfarbe |
| `Muted` | `#919AB9` | Nebentext, Achsen, inaktive Reiter |
| `WindowStroke` | `#62E8B4` (Grün) | Primärakzent: gewählt, „gut", Eignungskurve, Fensterumriss im Sucher |
| `WindowFill` | `#2262E8B4` | transparente Fensterfüllung, Kalendertage mit Sitzungen |
| `AnchorPoint` | `#FFB74D` (Orange) | Sekundärakzent: „mäßig", Mond, überfälliger Modelllauf |
| `Crosshair` | `#FF6B6B` (Rot) | Fehler, „schlecht", „jetzt"-Linie, Fadenkreuz |
| `Graticule` | `#55B0BEC5` | Himmelsgitter, Diagrammgitter |
| `CatalogMarker` | `#9FD8FF` | Katalogmarker, Auflösungsangabe, Bortle 4–5 |
| `TrackTarget` | `#E879F9` (Magenta) | verfolgtes Objekt und Pfeil im Sucher |

**Funktionsweise:** Da `forceDark` standardmäßig `true` ist und laut Grep niemand `false` übergibt, wird immer `DarkScheme` verwendet; `LightScheme` ist praktisch unbenutzt. Das `SideEffect` setzt `window.statusBarColor`/`navigationBarColor` (ab Android 15 bei Edge-to-Edge wirkungslos, aber unschädlich).
**Ändern/Fallstricke:** Eine Farbänderung wirkt app-weit, auch im Sucher-Overlay (`WindowStroke`, `Crosshair` …). Einige Bildschirme haben zusätzlich eigene lokale Farben (z. B. `AstroWeatherChart`: `CLOUD_FILL`, `TWILIGHT_COLOR`; `TwilightBand`, `MoonPhaseDisc`). Die Fensterhintergrundfarbe vor dem Compose-Start kommt aus `res/values/colors.xml` (`night`, `#07080F`) und weicht leicht von `Night` ab.

#### [`ObjectPalette.kt`](app/src/main/java/com/starwindow/app/ui/theme/ObjectPalette.kt)

Eine Farbe je Objektart, überall gleich (Listen, Symbole, Marker im Sucher).

| Element | Art | Aufgabe |
|---|---|---|
| `ObjectPalette` | object | Farben `Star`, `Galaxy`, `Emission`, `Reflection`, `Dark`, `Planetary`, `Remnant`, `OpenCluster`, `GlobularCluster`, `SolarSystem`, `Other` |
| `colorFor(type: ObjectType)` | fun | Zuordnung: STAR/DOUBLE_STAR → Star; GALAXY/GALAXY_GROUP → Galaxy; NEBULA/EMISSION_NEBULA → Emission; CLUSTER_NEBULA → OpenCluster; usw. |

Das `when` ist erschöpfend: Ein neuer `ObjectType` erzeugt einen Compilerfehler, bis hier eine Farbe zugeordnet ist.

#### Typische Änderungen in diesem Bereich
- **Farbe ändern:** Wert in `StarWindowColors` bzw. `ObjectPalette` ändern; prüfen, ob `DarkScheme` den Token referenziert (dann ändern sich auch Material-Komponenten). Lokale Farben in Zeichen-Composables separat suchen (`Color(0xFF`).
- **Neue Farbe/Token:** als `val` in `StarWindowColors` ergänzen; für Flächen statt eigener Konstante `StarWindowColors.tint(farbe, alpha)` verwenden.
- **Schrift anpassen:** `StarWindowTypography` (Material-Stufen) bzw. `MetricTextStyle`; eigene Schriftarten gibt es nicht.
- **Neuer Objekttyp:** `ObjectType` erweitern → `ObjectPalette.colorFor` ergänzen (sonst Kompilierfehler).

<a id="pkg-ui-components"></a>

### `ui/components` — Gemeinsame Bausteine: Design-System, Objektzeilen, Symbole, Bildgeometrie

Querschnittspaket mit wiederverwendbaren Composables und Helfern. Es enthält das kleine Design-System der App (`Design.kt`: Karten, Überschriften, Pills), die Darstellung von Katalogobjekten in Listen (`ObjectRow`, `ObjectSymbol`), das Laden von Himmelsbildern (`rememberSkyImage`) und die Geometrie-Helfer für den Sucher (`SkyViewport`, `DisplayRotation`). Farben, Formen, Abstände und Typografie selbst liegen in `ui/theme/Theme.kt` (`StarWindowColors`, `StarWindowShapes`, `StarWindowSpacing`, `MetricTextStyle`, `ObjectPalette`).

**Benutzt:** `ui/theme`, `data/catalog` (`SkyObject`, `ObjectType`), `data/images` (`SkyImageLoader`, `SkyImageRequest`), `domain.Feasibility`, `core/camera` (`SkyProjection`, `PreviewFit`), `ui/capture.PreviewStreamInfo`.

**Wird benutzt von:** praktisch allen Bildschirmen, siehe die Tabelle je Datei.

#### [`Design.kt`](app/src/main/java/com/starwindow/app/ui/components/Design.kt)

Die gemeinsamen Layout-Bausteine; sorgen für einheitliche Ecken (`MaterialTheme.shapes`), Abstände (`StarWindowSpacing`) und Farben.

| Element | Art | Aufgabe |
|---|---|---|
| `SectionCard(modifier, color, border, contentPadding, onClick, content)` | @Composable | Karte: Fläche `NightSurface`, 1-dp-Haarlinie `Outline` statt Schatten, Ecken `shapes.medium`, Innenabstand `StarWindowSpacing.card`, Zeilenabstand `StarWindowSpacing.row`; optional klickbar. |
| `SectionHeader(title, modifier, subtitle, trailing)` | @Composable | Abschnittsüberschrift in Versalien mit Laufweite, optional Unterzeile und Aktionen rechts. |
| `ScreenHeader(title, modifier, subtitle, onBack, actions)` | @Composable | Kopfzeile eines Bildschirms; `onBack` laut KDoc nur auf „hingegangenen" Bildschirmen, nicht auf den sechs Reitern. |
| `StatTile(value, label, modifier, tint, icon)` | @Composable | Große Zahl (`MetricTextStyle`) mit Beschriftung. |
| `StatusPill(text, modifier, tint, icon)` | @Composable | Farbige Plakette, Fläche `StarWindowColors.tint(tint, 0.16f)`. |
| `ValueRow(label, value, modifier, valueColor)` | @Composable | Zeile „Beschriftung links, Wert rechts" (Gewichte 1 : 1,4). |

**Zusammenspiel:** `SectionCard` – `WeatherScreen`, `NightRatingCard`, `ObjectInfoSheet`, `ObjectSearchScreen`; `SectionHeader` – `WeatherScreen`, `WindowDetailScreen`, `ObjectSearchScreen`, `StargazingHubScreen`; `ScreenHeader` – `CalendarScreen`, `PhotoGuideScreen`, `StargazingHubScreen`, `WindowListScreen`; `StatusPill` – `NightRatingCard` (der Sucher hat eine eigene private Variante). `StatTile` und `ValueRow` werden im Code derzeit nirgends aufgerufen.
**Ändern/Fallstricke:** Globale Abstände/Farben/Ecken in `ui/theme/Theme.kt` ändern, nicht hier. Der Sucher (`CaptureScreen`) und die Kalibrierung nutzen diese Bausteine nicht und haben eigene Surfaces/Farben.

#### [`DisplayRotation.kt`](app/src/main/java/com/starwindow/app/ui/components/DisplayRotation.kt)

`rememberDisplayRotationDegrees()` liefert 0/90/180/270 und hängt an `LocalConfiguration`, damit eine Drehung rekomponiert (die Activity wird wegen `configChanges` nicht neu erzeugt). `Context.displayRotationDegrees()` liest die Drehung über `DisplayManager` (funktioniert auch mit Application-Context). Einziger Nutzer: `rememberSkyViewport`.

#### [`SkyViewport.kt`](app/src/main/java/com/starwindow/app/ui/components/SkyViewport.kt)

Bündelt alles, was Overlay und Tipp-Verarbeitung brauchen, um Pixel in Richtungen umzurechnen – gemeinsam für Sucher und Kalibrierung, damit beide nicht auseinanderlaufen.

| Element | Art | Aufgabe |
|---|---|---|
| `SkyViewport(focalPx, baselineFocalPx, viewSize)` | data class | Brennweite in View-Pixeln mit bzw. ohne Kalibrierfaktor; `isReady`, `visibleFovDeg` (horizontales Bildfeld). |
| `rememberSkyViewport(streamInfo, viewSize, fovScale)` | @Composable | Rechnet über `SkyProjection.focalLengthInViewPixels(intrinsics, streamW/H, rotationDegreesFor(displayRotation), viewW/H, PreviewFit.FIT_CENTER, scale)`; vor Kamera/Größe: alles 0. |

**Zusammenspiel:** `CaptureScreen`, `CalibrationScreen`. `baselineFocalPx` ist die Bezugsgröße für den Schwenk-Löser.
**Ändern/Fallstricke:** `PreviewFit.FIT_CENTER` muss zum `ScaleType` in `CameraPreview` passen.

#### [`ObjectRow.kt`](app/src/main/java/com/starwindow/app/ui/components/ObjectRow.kt)

Eine Katalogzeile mit festen Spalten: Symbol, Kennung (in Typfarbe) + Name, graue Detailzeile, rechts die aktuelle Höhe.

| Element | Art | Aufgabe |
|---|---|---|
| `ObjectRow(obj, altitudeDeg, onClick, modifier, selected, feasibility, trailing)` | @Composable | Zeile; `trailing` ersetzt die rechte Spalte, `feasibility` die Beschriftung unter der Höhe. |
| `objectSubtitle(obj)` | fun | „Typ · Sternbild · Größe · mag". |
| `AltitudeBadge(altitudeDeg, modifier, feasibility)` | @Composable | Höhe, grün ≥ 25°, bernstein > 0°, grau darunter. |
| `verdictColor(feasibility)` | fun | Farbe je `Feasibility` (EASY grün, OK bernstein, HARD rot, Rest grau). |
| `formatArcmin(size)` | fun | Bogenminuten, ab 60′ in Grad. |

**Zusammenspiel:** `ObjectRow` – `ObjectSearchScreen`; `objectSubtitle` – `WindowDetailScreen`; `verdictColor` – `CalendarScreen`, `WeatherScreen`.

#### [`ObjectSymbol.kt`](app/src/main/java/com/starwindow/app/ui/components/ObjectSymbol.kt)

Gezeichnete (nicht aus einer Schrift geladene) Symbole je `ObjectType`, damit dasselbe Zeichen in Listen und über dem Kamerabild erscheint. Gestaltet für ca. 16 dp.

| Element | Art | Aufgabe |
|---|---|---|
| `ObjectSymbol(type, modifier, size, color)` | @Composable | `Canvas` der Größe `size`, Standardfarbe `ObjectPalette.colorFor(type)`. |
| `DrawScope.drawObjectSymbol(type, color, center, extent)` | fun | Dasselbe als Zeichenaufruf (für `SkyOverlay`). |

**Funktionsweise:** Ein `when (type)` wählt die Form: Stern = vierzackiger Funke, Doppelstern = zwei Funken, Galaxie = Spirale (`drawSpiral`), Galaxiengruppe = zwei geneigte Scheiben, offener/Kugelsternhaufen = feste Punktmuster (`openClusterDots`, `globularDots`), Nebel = Wolke (`cloudPath`, Vereinigung von Ovalen), Emissions-/Reflexions-/Haufennebel = Wolke + aufgehellte Marke (`highlight`), Dunkelnebel = Wolkenumriss + Sterne, Planetarischer Nebel = Ring + Punkt, Supernova-Rest = Strahlen, Sonnensystem = Scheibe mit Strahlenkranz, `OTHER` = gedrehtes Quadrat.
**Zusammenspiel:** `ObjectSymbol` – `CaptureScreen` (`TrackedTargetBar`), `ObjectRow`, `ObjectDetailScreen`, `PhotoGuideScreen`, `StargazingHubScreen`, `WindowDetailScreen`; `drawObjectSymbol` – `SkyOverlay.drawTargetMarker`.
**Ändern/Fallstricke:** Neuer `ObjectType` erzwingt hier einen neuen Zweig (das `when` ist erschöpfend). Pfade (z. B. `cloudPath`) werden bei jedem Zeichnen neu erzeugt.

#### [`SkyImage.kt`](app/src/main/java/com/starwindow/app/ui/components/SkyImage.kt)

`rememberSkyImage(obj, loader, sizePx = 512, widen = 1.0)` lädt das Himmelsbild eines Objekts, sobald das Composable in die Komposition kommt, und bricht beim Verlassen ab (an `LaunchedEffect` gebunden). Rückgabe ist `State<SkyImageState>` (`bitmap`, `isLoading`, `failed`). Bildausschnitt = `SkyImageLoader.frameForObject(sizeArcmin) * widen`, begrenzt auf 0,15–6°. `loader == null` ergibt sofort `failed`. `SKY_IMAGE_CREDIT` ist die Quellenangabe („DSS2 über hips2fits"). Nutzer: `StargazingHubScreen`, `PhotoGuideScreen`, `ObjectInfoSheet` (Credit auch dort und im Hub).

#### Typische Änderungen in diesem Bereich
- **Farbe/Abstand/Ecken global ändern:** `ui/theme/Theme.kt` (`StarWindowColors`, `StarWindowSpacing`, `StarWindowShapes`); die Bausteine in `Design.kt` übernehmen das automatisch. Overlay-Farben (`WindowStroke`, `AnchorPoint`, `Crosshair`, `TrackTarget` …) wirken auf Sucher und Listen gleichzeitig.
- **Neues Objektsymbol / neuer Objekttyp:** Zweig in `drawObjectSymbol`, Farbe in `ObjectPalette` (`ui/theme`).
- **Neuer gemeinsamer Baustein:** in `Design.kt` ergänzen und Farben/Abstände ausschließlich aus `ui/theme` nehmen; vorhandene private Doppelungen (z. B. `StatusPill` in `CaptureScreen`) bei Gelegenheit darauf umstellen.
- **Bildgröße/Ausschnitt der Himmelsbilder:** Parameter `sizePx`/`widen` beim Aufrufer von `rememberSkyImage`, Grenzen 0,15–6° in `SkyImage.kt`.

<a id="pkg-ui-capture"></a>

### `ui/capture` — Sucher: Kamerabild, Himmels-Overlay, Fenster einzeichnen

Das Paket ist der Startbildschirm der App (Route `capture`). Es zeigt das Livebild der Rückkamera, legt darüber ein aus Himmelskoordinaten gerendertes Overlay (Gitter, Katalogobjekte, Fensterkontur, verfolgtes Ziel samt Pfad, gesetzte Punkte, Fadenkreuz) und lässt den Nutzer durch Antippen ein „Fenster" einzeichnen, das in Azimut/Höhe als `SkyWindow` gespeichert wird. Einstiegspunkte sind `CaptureScreen` (UI) und `CaptureViewModel` (Zustand). `CameraPreview` und `SkyOverlay` sind zwar hier abgelegt, werden aber auch vom Kalibrierbildschirm benutzt (DEV_PLAN §11.4 S1 schlägt dafür ein eigenes Paket `ui/viewfinder` vor).

**Benutzt:** `core/camera` (`SkyProjection`, `TargetIndicator`, `EdgeInsets`, `CameraIntrinsics`, `ExposureCapabilities`, `ExposureSettings`, `NightVisionController`), `core/sensors` (`OrientationTracker`, `LocationTracker`, `DeviceAttitude`), `core/astro`, `core/geometry` (`WindowShape`, `PolygonWindow`, `CircleWindow`, `AltAzBoxWindow`, `SkyWindow`), `core/calibration.trustAt`, `data/catalog`, `data/tracking` (`TrackingStore`, `TrackTarget`), `data/windows` (`SettingsStore`, `SkyWindowRepository`), `domain` (`OverlaySelection`, `SkyTrackBuilder`), `ui/components` (`rememberSkyViewport`, `ObjectSymbol`, `drawObjectSymbol`), `ui/theme`.

**Wird benutzt von:** `ui/nav/StarWindowNavHost.kt` (Route `Routes.CAPTURE`), `ui/calibration/CalibrationScreen.kt` (`CameraPreview`, `SkyOverlay`, `PreviewStreamInfo`), `ui/components/SkyViewport.kt` (`PreviewStreamInfo`).

#### [`CaptureViewModel.kt`](app/src/main/java/com/starwindow/app/ui/capture/CaptureViewModel.kt)

Hält den gesamten Zustand des Suchers: Zeichenmodus und gesetzte Punkte, Standort, Einstellungen, Overlay-Katalog, Kamerageometrie, Meldungen und das verfolgte Ziel. Stellt außerdem die Lageströme (`attitude`, `hudAttitude`) und das aufgelöste Ziel (`trackedTarget`) bereit.

| Element | Art | Aufgabe |
|---|---|---|
| `DrawMode` | enum | `POLYGON`, `CIRCLE`, `BOX`; je Modus `label` (Chip-Text), `hint` (Hilfezeile) und `requiredAnchors` (3 bzw. 2). |
| `CaptureUiState` | data class | Bildschirmzustand, Felder siehe unten; dazu berechnete Eigenschaften `shape`, `canSave`, `missingAnchors`, `outlineCrossesItself`. |
| `buildShape(mode, anchors)` | fun (top-level) | Baut aus den Punkten die Form: `PolygonWindow` (≥ 3), `CircleWindow.fromCenterAndEdge` (Punkt 1 = Mitte, 2 = Rand), `AltAzBoxWindow.fromCorners`; sonst `null`. |
| `CaptureViewModel` | class (ViewModel) | Kombiniert Tracker/Repositories, bietet die Aktionen an. |
| `trackedTarget` | `StateFlow<SkyTarget?>` | Verfolgtes Objekt/Fenster, einmal pro Sekunde zu einer Richtung (und ggf. Pfad) aufgelöst. |
| `attitude` | `StateFlow<DeviceAttitude?>` | Volle Sensorrate (~50 Hz), nur für Overlay und Tipp-Handler. |
| `hudAttitude` | `StateFlow<DeviceAttitude?>` | Dieselbe Lage, per `sample(250)` auf 4 Hz gedrosselt, für die Text-Pills. |
| `hasOrientationSensor` | val | `orientationTracker.isAvailable`. |
| `factory(container)` | fun (companion) | `viewModelFactory`, holt die sechs Abhängigkeiten aus dem `AppContainer`. |

**`CaptureUiState`-Felder:** `mode` (aktueller `DrawMode`), `anchors` (gesetzte Punkte als `Horizontal`, Az/Höhe, wahrer Norden), `observer` (aktiver Standort, manuell oder GPS), `settings` (Kopie von `SettingsStore.settings`), `catalog` (die vom Overlay zu zeichnende Auswahl aus dem Gesamtkatalog), `streamInfo` (`PreviewStreamInfo` der laufenden Kamera, `null` bis die Kamera meldet), `cameraError`, `message` (Kurzmeldung, unten als „Toast" eingeblendet), `tracked` (rohes `TrackTarget` aus dem `TrackingStore`). `shape` ist ein Getter und ruft bei jedem Zugriff `buildShape` neu auf.

**Funktionsweise:**
- `init` startet vier Koroutinen im `viewModelScope`: (1) sammelt `settingsStore.settings` → übernimmt sie in den State, reicht die Kalibrierung per `orientationTracker.updateCalibration` an den Tracker (der „stempelt" sie auf jede Lage), ruft `applyObserver(manualLocation ?: lastFix)` und baut bei geänderter Grenzgröße den Overlay-Katalog neu; (2) lädt `catalogRepository.objects()` in `fullCatalog` und ruft `refreshOverlayCatalog()`; (3) spiegelt `trackingStore.target` in `tracked`; (4) `windowRepository.load()`. Dazu `startLocationUpdates()`.
- `startLocationUpdates()` bricht einen laufenden `locationJob` ab und sammelt `locationTracker.locations()` neu; jede Position wird `lastFix`, wirksam ist aber eine gesetzte manuelle Position. Muss nach erteilter Standortberechtigung erneut aufgerufen werden (macht `CaptureScreen`).
- `applyObserver` meldet den Standort an `orientationTracker.updateLocation` (für Missweisung/erwartetes Magnetfeld) und baut den Overlay-Katalog nur neu, wenn sich die **Breite** geändert hat.
- `refreshOverlayCatalog()` läuft auf `Dispatchers.Default`: `OverlaySelection.select(catalog, latitudeDeg, magnitudeLimit)` filtert die ~22 000 Einträge auf das, was hier aufgehen kann und hell genug ist. Ein vorheriger Lauf wird abgebrochen (`overlayJob`).
- `trackedTarget` = `combine(trackingStore.target, observer (distinctUntilChanged), Sekundenticker)` → `stateIn(WhileSubscribed(5_000))`. Für `TrackedWindow` ist die Richtung einfach `shape.center()` (horizontfest), dazu `shape` für die Kontur. Für `TrackedObject` wird die aktuelle Position per `CoordinateTransforms.apparentHorizontalAtLst` (Präzession `Precession.forEpoch(now)`, `AstroTime.lstDeg`) berechnet; hat das Ziel einen Pfad (`hasPath`, gesetzt über „Pfad zeigen" in der Planung), baut `SkyTrackBuilder.overSpan(...)` die Bahn für die geplante Nacht. **Mit Pfad zeigt der Pfeil auf den höchsten Bahnpunkt, nicht auf die Live-Position** (Kommentar im Code begründet das). Ohne Standort ist das Ergebnis für Objekte `null`.
- Zeichenaktionen: `setMode` (leert immer die Punkte), `addAnchor(direction)` (bei Kreis/Rechteck ersetzt ein dritter Tipp den zweiten Punkt; Polygon max. `MAX_POLYGON_ANCHORS = 32`, danach wird ebenfalls der letzte ersetzt), `undoAnchor`, `clearAnchors`.
- Kamera-Rückmeldungen: `onStreamInfo` (setzt `streamInfo`, löscht `cameraError`), `onCameraError`.
- Einstellungen (alles geht an `SettingsStore`, das Ergebnis kommt über den `settings`-Flow zurück): `setExposure`, `setExposureMode` (beim Wechsel auf `NIGHT` werden über `ExposureSettings.nightDefault(capabilities)` gerätespezifische Startwerte gesetzt, sofern noch nicht im Nachtmodus), `setManualLocation` (setzt zusätzlich sofort `applyObserver`), `toggleGraticule`, `toggleCatalogOverlay`.
- `saveWindow(name, visibleFovDeg)`: Ohne `shape` oder `observer` nur Meldung. Sonst wird ein `SkyWindow` mit neuer UUID gebaut — Name oder `defaultName` („Fenster 123°/45°"), Form, Standort, Zeitpunkt, und als Qualitätsmerkmale aus der aktuellen `attitude`: `magneticDeclinationDeg`, `compassAccuracy`, `headingHeld`, `headingHeldSeconds`; aus der Kalibrierung `calibrationResidualDeg`; `cameraFovDeg` = sichtbares Bildfeld. Danach `windowRepository.save` (Koroutine), Punkte leeren, Meldung „„Name" gespeichert".
- `stopTracking()` → `trackingStore.clear()`. `consumeMessage()` löscht `message`.

**Zusammenspiel:** Erzeugt in `StarWindowNavHost` per `viewModel(factory = CaptureViewModel.factory(container))`. Da der Sucher die Wurzel des Back-Stacks ist, lebt das ViewModel (und der Standortempfang) auch, während andere Reiter offen sind (DEV_PLAN §11.3 P6 „Standort").

**Ändern/Fallstricke:**
- `attitude` bewusst **nicht** in `uiState` falten – sonst rekomponiert der ganze Bildschirm mit Sensorrate.
- Neue Einstellungen für den Sucher gehören in `Settings`/`SettingsStore`; das ViewModel bekommt sie automatisch über den `settings`-Flow.
- `SkyTrackBuilder.overSpan` wird jede Sekunde neu gerechnet, obwohl sich die Bahn einer festen Nacht in Az/Höhe nicht ändert – Kandidat zum Zwischenspeichern.
- Wer einen neuen `DrawMode` ergänzt, muss `buildShape`, das Limit in `addAnchor` und ggf. `outlineCrossesItself` anpassen; die Chips in `CaptureControls` entstehen automatisch aus `DrawMode.entries`.
- Fehler beim Speichern (`windowRepository.save`) werden nicht abgefangen.

#### [`CaptureScreen.kt`](app/src/main/java/com/starwindow/app/ui/capture/CaptureScreen.kt)

Der Composable-Bildschirm des Suchers: fragt Berechtigungen an, stapelt `CameraPreview` und `SkyOverlay`, setzt per Tipp Punkte und zeigt oben ein HUD (Suche, Nachtsicht, Einstellungen, Status-Pills) und unten Zielleiste und Zeichensteuerung. Alle Dialoge werden von hier aus geöffnet.

| Element | Art | Aufgabe |
|---|---|---|
| `CaptureScreen(viewModel, onOpenCalibration, onOpenSearch, onOpenTrackedObject, modifier)` | @Composable (public) | Gesamter Bildschirm. |
| `CaptureHud` | @Composable (private) | Oberes Band: `SearchPill`, Nachtsicht-Knopf (Mond-Icon, im Nachtmodus grün), Einstellungen-Knopf, horizontal scrollbare Reihe von `StatusPill`s, ggf. `cameraError`. |
| `SearchPill` | @Composable (private) | Sieht aus wie ein Suchfeld, ist ein Knopf → `onOpenSearch`. |
| `StatusPill(icon, text, tint)` | @Composable (private) | Eigene Pill-Variante (Doppelung zu `ui/components/Design.kt` `StatusPill`, DEV_PLAN S7). |
| `TrackedTargetBar` | @Composable (private) | Leiste für das verfolgte Ziel: Symbol, Name, Az/Höhe bzw. „unter dem Horizont" bzw. Bahnhinweis, Info-Knopf (nur bei `TrackedObject`) und Schließen. |
| `CaptureControls` | @Composable (private) | Unteres Band: `FilterChip` je `DrawMode`, Hinweiszeile mit fehlenden Punkten, Fläche/Mitte der Form, Warnung bei Selbstüberschneidung, Rückgängig/Löschen/„Fenster speichern". |
| `PermissionPlaceholder` | @Composable (private) | Text + „Berechtigungen erteilen", wenn die Kamera fehlt. |
| `Context.isGranted` | fun (private) | `ContextCompat.checkSelfPermission`-Kurzform. |

**Funktionsweise:**
- **State-Zugriffe:** `uiState`, `hudAttitude` und `trackedTarget` werden per `collectAsStateWithLifecycle()` gelesen. `viewModel.attitude` wird als `State`-Objekt (`attitudeState`) geholt und **nicht** in der Komposition gelesen, sondern erst im Draw-Lambda von `SkyOverlay` und im Tipp-Callback. Weil `hudAttitude` ganz oben gelesen wird, läuft die gesamte Funktion etwa 4×/s neu (DEV_PLAN P6 „UiState").
- **Lokaler UI-State** (`remember`): `hasCameraPermission`, `showSaveDialog`, `showSettings`, `showNightVision`, `viewSize`, `pendingMode` (gewünschter Moduswechsel, der erst bestätigt werden muss), `hudHeightPx`/`controlsHeightPx` (gemessene Höhe der beiden Bänder).
- **Berechtigungen:** `rememberLauncherForActivityResult(RequestMultiplePermissions)`. Beim ersten Betreten (`LaunchedEffect(Unit)`) werden `CAMERA`, `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` angefragt, falls Kamera oder grober Standort fehlen. Bei erteiltem Standort ruft der Callback `viewModel.startLocationUpdates()`.
- **Geometrie:** `rememberSkyViewport(streamInfo, viewSize, calibration.fovScale)` liefert `focalPx` und `visibleFovDeg`.
- **Antippen → Punkt:** Der äußere `Box` hat `pointerInput(viewport) { detectTapGestures { … } }`. Im Callback wird mit der aktuellen Lage eine `SkyProjection(attitude, viewport.focalPx, size.width, size.height)` gebaut; ist sie `isUsable`, geht `projection.screenToSky(x, y)` an `viewModel.addAnchor`. Der Punkt existiert ab da nur noch als Az/Höhe.
- **Overlay-Parameter:** `SkyOverlay` bekommt `anchors`, `shape`, `catalog`, `observer`, die beiden Schalter aus `settings`, `trackedTarget` und `chromeInsets = EdgeInsets(top = hudHeightPx, bottom = controlsHeightPx)`, damit der Zielpfeil nicht hinter den Bändern landet.
- **Moduswechsel:** Sind Punkte gesetzt und der Modus ändert sich, wird `pendingMode` gesetzt → `DiscardAnchorsDialog`; sonst direkt `setMode`.
- **Dialoge:** `SaveWindowDialog` → `saveWindow(name, viewport.visibleFovDeg)`; `CaptureSettingsDialog` → Schalter, manueller Standort, „Kalibrierung öffnen" (schließt Dialog, ruft `onOpenCalibration`); `NightVisionDialog` → `setExposure`/`setExposureMode`.
- **Meldung:** Ist `state.message` gesetzt, erscheint unten eine Pill (fester Abstand `120.dp` vom unteren Rand); `LaunchedEffect(message)` ruft nach 2,5 s `consumeMessage()`.
- **Status-Pills (Texte stehen direkt hier):** Position (rot bei fehlender), Lagequelle + Kompassgüte (`compassAccuracyLabel`, bernstein bei Güte < 2), Warnungen nur bei Problemen (Kompass kalibrieren / Feldrichtung gestört / Magnetstörung / Kompass unsicher – jeweils „Nord gehalten" mit Drift), Kalibriervertrauen (`calibration.trustAt(observer, now)`), Bildfeld, Missweisung.

**Zusammenspiel:** Navigation-Callbacks aus dem NavHost: `onOpenCalibration` → `Routes.CALIBRATION`, `onOpenSearch` → `Routes.SEARCH`, `onOpenTrackedObject(id)` → `Routes.objectDetail(id)`. Rücksprünge „verfolgen"/„Pfad zeigen"/„anzeigen" landen per `popBackStack(Routes.CAPTURE)` hier; das Ziel kommt über den `TrackingStore`, nicht über Navigationsargumente.

**Ändern/Fallstricke:**
- Neue Höhe in den Bändern ist unkritisch – sie werden per `onSizeChanged` gemessen. Wer aber Bedienelemente **außerhalb** dieser zwei Bänder platziert, muss sie in `chromeInsets` berücksichtigen.
- Der Wiederholknopf in `PermissionPlaceholder` fragt nur `CAMERA` und `ACCESS_FINE_LOCATION` an (ohne `COARSE`), anders als die Erstanfrage.
- Die Datei ist groß (DEV_PLAN S6); neue Unterbereiche besser als eigene private Composables oder in eine neue Datei.

#### [`SkyOverlay.kt`](app/src/main/java/com/starwindow/app/ui/capture/SkyOverlay.kt)

Zeichnet alles über dem Kamerabild in einem einzigen `Canvas`. Alles wird aus Himmelskoordinaten bei **jedem Bild** neu projiziert; deshalb bleiben gesetzte Punkte horizontfest an ihrer Stelle, während das Handy bewegt wird.

| Element | Art | Aufgabe |
|---|---|---|
| `SkyTarget` | data class | Aufgelöstes Ziel: `label`, `direction`, optional `type` (Symbol), `shape` (Fenster), `path`, `pathHourMarks`, `pathLabel`. Wird im `CaptureViewModel` gebaut. |
| `SkyOverlay(attitudeState, focalPx, anchors, shape, catalog, observer, showGraticule, showCatalog, modifier, highlightDirection, highlightLabel, trackedTarget, chromeInsets)` | @Composable | Das Overlay. `highlight*` nutzt nur die Kalibrierung. |
| `drawGraticule` | private DrawScope-fun | Az/Höhe-Gitter alle 10°, Horizont dicker/rötlich, Himmelsrichtungen hervorgehoben und beschriftet (`N`/`O`/`S`/`W` über `cardinalLabel`). |
| `drawCatalog` | private | Katalogobjekte als Kreise in Typfarbe (`ObjectPalette.colorFor`), Radius nach Helligkeit; Beschriftung nur bei mag < 3,5 oder ausgedehnten Objekten. |
| `drawWindowShape` | private | Kontur einer `WindowShape` (`outline(96)`) gefüllt (13 % Alpha) + 3-dp-Strich. |
| `drawSkyTrack` | private | Pfad als Polylinie + Punkte für die Stundenmarken. |
| `drawTargetMarker` | private | Ziel im Bild: Ring mit vier Ticks, Objektsymbol, Beschriftung „Name · Abstand"; außerhalb: Randpfeil (`drawEdgeArrow`) + nach innen gezogene Beschriftung. |
| `drawAnchors` | private | Gestrichelte Verbindung der Punkte + nummerierte Kreise. |
| `drawCrosshair` | private | Fadenkreuz in Bildmitte + „Az … Alt …" der Bildmitte. |
| `drawSkyPolyline`, `skyPath` | private | Sky-Richtungen → Bildschirmlinien; verwerfen Punkte hinter der Kamera oder > 4× Bildgröße entfernt. |
| `OverlayPaints` | private class | Einmal pro Dichte erzeugte `android.graphics.Paint`s für Text (mit Schatten). |

**Funktionsweise – ein Bild:**
1. Das `Canvas`-Draw-Lambda liest `attitudeState.value` (Lesen im Draw-Phase → nur Neuzeichnen, keine Rekomposition). Ohne Lage: nichts zeichnen.
2. `SkyProjection(attitude, focalPx, size.width, size.height)`; nicht `isUsable` (z. B. `focalPx` noch 0) → nichts zeichnen.
3. Reihenfolge (später = oben): Gitter (falls `showGraticule`) → Katalog (falls `showCatalog` und Standort bekannt) → eigene Fensterkontur `shape` → `highlightDirection` (Kalibrierung, bernstein) → verfolgtes Ziel: ggf. Fensterkontur in Magenta, ggf. Pfad, dann Marker → gesetzte Punkte → Fadenkreuz.
4. `drawCatalog` rechnet **pro Bild** für jedes Objekt der Auswahl: einmal `AstroTime.lstDeg` und `Precession.forEpoch(now)` pro Bild, dann je Objekt `apparentHorizontalAtLst` → Höhe < −2° verwerfen → `skyToScreen` → außerhalb Bild + 24 dp Rand verwerfen → `drawCircle`/`drawLabel` (DEV_PLAN P2).
5. `drawTargetMarker` nutzt `TargetIndicator.locate(projection, direction, insets)` mit `insets = chromeInsets + 34 dp`; das funktioniert auch für Ziele hinter dem Betrachter, weil `TargetIndicator` mit dem Vektor im Display-Frame statt mit einem projizierten Punkt arbeitet. Der Abstand wird ab 10° ganzzahlig, darunter mit einer Nachkommastelle formatiert (`formatSeparation`).
6. Text geht über `drawIntoCanvas { nativeCanvas.drawText }` (`drawLabel`).

**Zusammenspiel:** Aufgerufen von `CaptureScreen` und `CalibrationScreen`. Benutzt `drawObjectSymbol` aus `ui/components/ObjectSymbol.kt`, Farben aus `StarWindowColors`/`ObjectPalette`.

**Ändern/Fallstricke:**
- Neue Elemente in der passenden Position der Reihenfolge im `Canvas`-Block einfügen; keine Astronomie im Draw-Lambda ergänzen, sondern im ViewModel vorberechnen (Muster: `SkyTarget`).
- Keine `Paint`-Objekte pro Bild anlegen; `centeredPaint(color)` färbt ein geteiltes Paint um.
- `skyPath` verwirft die **ganze** Kontur, sobald ein Punkt hinter der Kamera liegt – bei großen Fenstern verschwindet sie dann komplett.
- Textgrößen in `OverlayPaints` heißen `sizeSp`, werden aber nur mit `density.density` skaliert (keine Schriftgrößen-Einstellung des Systems).
- Pro Bild entstehen weiterhin Allokationen (`outline(96)`, `buildList` im Gitter, `String.format` am Fadenkreuz) – siehe DEV_PLAN P2.

#### [`CameraPreview.kt`](app/src/main/java/com/starwindow/app/ui/capture/CameraPreview.kt)

Bindet die Rückkamera per CameraX an den Lebenszyklus, zeigt sie in einer `PreviewView` und meldet die für die Projektion nötige Geometrie (`PreviewStreamInfo`). Wendet außerdem die Nachtsicht-Belichtung live an.

| Element | Art | Aufgabe |
|---|---|---|
| `PreviewStreamInfo` | data class | `intrinsics`, `streamWidth`/`streamHeight` (Puffer in Sensorausrichtung), `sensorOrientationDegrees`, `exposureCapabilities`; `rotationDegreesFor(displayRotationDegrees)` rechnet die Pufferdrehung live. |
| `CameraPreview(exposure, onStreamInfo, modifier, onError)` | @Composable | Kamera starten, Geometrie melden, Belichtung anwenden. |
| `cameraCharacteristics` | private fun | `CameraManager.getCameraCharacteristics(cameraId)`, Fehler → `null`. |
| `ListenableFuture.awaitResult` | private suspend fun | Wartet auf ein Guava-Future ohne Coroutine-Adapter. |

**Funktionsweise:** `PreviewView` mit `ScaleType.FIT_CENTER` (kein Beschnitt → bekannte Geometrie, dafür Balken) und `ImplementationMode.COMPATIBLE` (TextureView, damit das Compose-Overlay sicher darüber liegt; kostet ein Bild Latenz, DEV_PLAN P6). In `LaunchedEffect(previewView)`: `ProcessCameraProvider` holen → `Preview` bauen → `unbindAll()` → `bindToLifecycle(lifecycleOwner, DEFAULT_BACK_CAMERA, preview)`. Über Camera2-Interop (`Camera2CameraInfo.from(camera.cameraInfo).cameraId`) wird die **tatsächlich gebundene** Kamera ermittelt, deren `CameraCharacteristics` gelesen und daraus `CameraIntrinsics.fromCharacteristics` (sonst `CameraIntrinsics.FALLBACK`), `ExposureCapabilities` und `SENSOR_ORIENTATION` (Fallback 90°) bestimmt. Es entsteht ein `NightVisionController(camera, capabilities)`; `LaunchedEffect(nightVision, exposure)` ruft bei jeder Belichtungsänderung `nightVision.apply(exposure)` auf der laufenden Kamera (kein Neubinden). Fehler gehen an `onError` und ins Log (`TAG = "CameraPreview"`).

**Zusammenspiel:** Aufrufer sind `CaptureScreen` (→ `viewModel::onStreamInfo`, `viewModel::onCameraError`) und `CalibrationScreen` (lokaler State, kein `onError`). `PreviewStreamInfo` wird von `rememberSkyViewport` verbraucht. `lifecycleOwner` ist der jeweilige `NavBackStackEntry`; beim Wechsel zwischen Sucher und Kalibrierung bindet jeder Bildschirm per `unbindAll()` neu.

**Ändern/Fallstricke:** Wer auf `FILL_CENTER` umstellt, muss auch `PreviewFit.FIT_CENTER` in `rememberSkyViewport` ändern, sonst stimmt der Maßstab nicht. `rotationDegreesFor` nicht cachen (die Activity wird bei Drehung nicht neu erzeugt). Andere Kamera/Linse: `CameraSelector` hier ändern – die Intrinsics folgen automatisch.

#### [`CaptureDialogs.kt`](app/src/main/java/com/starwindow/app/ui/capture/CaptureDialogs.kt)

Die vier `AlertDialog`s des Suchers; reine UI ohne eigenen Zustand außer Eingabefeldern.

| Element | Art | Aufgabe |
|---|---|---|
| `DiscardAnchorsDialog(mode, anchorCount, onConfirm, onDismiss)` | @Composable | Rückfrage vor Moduswechsel, der Punkte verwirft. |
| `SaveWindowDialog(defaultName, onDismiss, onConfirm)` | @Composable | Namensfeld; leerer Name → ViewModel vergibt `defaultName`. |
| `NightVisionDialog(exposure, capabilities, onExposureChange, onModeChange, onDismiss)` | @Composable | Chips je `ExposureMode`; im Nachtmodus mit `MANUAL_SENSOR`: logarithmischer Belichtungszeit-Slider (`LogSlider`), ISO-Slider, „Fokus auf unendlich" (wenn `supportsFocusDistance`). Ohne Fähigkeit/ohne Kamera nur Erklärtext. |
| `LogSlider` | private @Composable | Slider 0..1, intern `ln`/`exp` zwischen `min` und `max`. |
| `CaptureSettingsDialog(settings, observer, onDismiss, onToggleGraticule, onToggleCatalog, onManualLocation, onOpenCalibration)` | @Composable | Schalter Gradnetz/Katalog, Kalibrierstatus + „Kalibrierung öffnen", manueller Standort (Breite/Länge, Komma oder Punkt), „Wieder GPS". |
| `SettingSwitch` | private @Composable | Beschriftung + `Switch`. |

**Funktionsweise:** Jede Slider-Bewegung ruft `onExposureChange` → `SettingsStore.setExposure` → JSON in SharedPreferences (DEV_PLAN P6). Ungültige Koordinaten werden beim „Übernehmen" stillschweigend ignoriert.
**Ändern/Fallstricke:** Neue Sucher-Einstellung: Schalter hier, Toggle im `CaptureViewModel`, Feld/Setter in `Settings`/`SettingsStore`.

#### Typische Änderungen in diesem Bereich
- **Neues Element im Sucher-Overlay zeichnen:** (1) Daten im `CaptureViewModel` vorbereiten (bei Astronomie: im Sekundenticker wie `trackedTarget`, Ergebnis als Az/Höhe), (2) als Parameter an `SkyOverlay` durchreichen (`CaptureScreen` und ggf. `CalibrationScreen`), (3) private `DrawScope.drawXxx(projection, …)` schreiben, die mit `projection.skyToScreen` arbeitet, (4) im `Canvas`-Block an der richtigen Stelle der Reihenfolge aufrufen, (5) Farbe in `StarWindowColors` anlegen.
- **Neuen Knopf in die Sucher-Leiste:** Oben in `CaptureHud` (neuer Callback-Parameter, `IconButton` neben Nachtsicht/Einstellungen) oder unten in `CaptureControls`; Callback in `CaptureScreen` mit einer ViewModel-Methode oder einem Navigations-Callback verbinden (neuer Parameter von `CaptureScreen` → in `StarWindowNavHost` belegen).
- **Neuen Fenstertyp (DrawMode):** Enum-Eintrag in `DrawMode`, Fall in `buildShape` (neue `WindowShape` in `core/geometry`), Punkt-Limit in `addAnchor`.
- **Zusätzliche Information beim Speichern eines Fensters:** Feld in `SkyWindow` (`core/geometry`, serialisierbar mit Standardwert), in `CaptureViewModel.saveWindow` befüllen.
- **Status-Pill ergänzen:** In `CaptureHud` in der `Row` mit `StatusPill(icon, text, tint)`; Werte aus `state` oder `attitude` (`hudAttitude`, nicht `attitude`!).

<a id="pkg-ui-calibration"></a>

### `ui/calibration` — Kalibrierung der Ausrichtung und des Bildfelds

Der Kalibrierbildschirm (Route `calibration`, erreichbar aus dem Einstellungsdialog des Suchers) bietet vier Verfahren, um die beiden Fehlerquellen der Projektion zu verkleinern: die **Ausrichtung** (Kompass-/Neigungsfehler, als Rotation) und das **Bildfeld** (Faktor `fovScale` auf die Brennweite). Die Mathematik liegt in `core/calibration` (`AttitudeFit`, `FieldOfViewSolver`); das Paket sammelt die Messungen, ruft die Löser auf und speichert das Ergebnis als `Calibration` im `SettingsStore`. Er nutzt denselben Sucher (`CameraPreview` + `SkyOverlay`) wie `ui/capture`.

**Benutzt:** `core/calibration` (`AttitudeFit`, `Calibration`, `CalibrationSource`, `DirectionPair`, `FieldOfViewSolver`, `FovFitException`, `PanSighting`, `trustAt`), `core/sensors`, `core/astro`, `core/geometry.SphericalGeometry`, `data/catalog.CatalogRepository`, `data/windows.SettingsStore`, `ui/capture` (`CameraPreview`, `SkyOverlay`, `PreviewStreamInfo`), `ui/components` (`SkyViewport`, `rememberSkyViewport`).

**Wird benutzt von:** `ui/nav/StarWindowNavHost.kt` (`CalibrationScreen(viewModel, settings, onBack)`, `settings` wird dort aus `container.settingsStore.settings` gesammelt).

#### [`CalibrationViewModel.kt`](app/src/main/java/com/starwindow/app/ui/calibration/CalibrationViewModel.kt)

Zustandsmaschine für die vier Verfahren: Messungen sammeln, auswerten, speichern, Rückmeldung geben.

| Element | Art | Aufgabe |
|---|---|---|
| `CalibrationMethod` | enum | `STARS`, `LANDMARK`, `PAN_SWEEP`, `MANUAL`; je `label`, `corrects` (was korrigiert wird), `needsClearSky`, `instructions` (Anleitungstext). |
| `StarSample` | data class | Gemessene (`measured`, unkalibriert) und Soll-Richtung (`reference`) eines Sterns; `errorDeg` = Winkelabstand. |
| `PanSample` | data class | Ein Schwenk-Antippen: `PanSighting` (Lage-Matrix + Pixelversatz) und die Bildschirmkoordinate. |
| `CalibrationUiState` | data class | Felder siehe unten; `selectedStar`, `canApplyStars` (≥ 1 Stern), `canApplyPan` (≥ 2 Antippungen). |
| `CalibrationViewModel` | class (ViewModel) | Siehe Funktionsweise. |
| `attitude` / `hudAttitude` | `StateFlow<DeviceAttitude?>` | Wie im Sucher: volle Rate bzw. `sample(250)`. |
| `MAX_PLAUSIBLE_ERROR_DEG` | const (25.0) | Ab diesem Fehler gilt eine Sternmessung als „falscher Stern". |
| `factory(container)` | fun | Baut das ViewModel mit `orientationTracker`, `locationTracker`, `catalogRepository`, `settingsStore`. |

**`CalibrationUiState`-Felder:** `method` (Standard `PAN_SWEEP`), `calibration` (aktuelle gespeicherte `Calibration`), `observer`, `candidateStars` (helle Sterne > 15° Höhe), `selectedStarId`, `starSamples`, `panSamples`, `landmarkBearingText` (Eingabefeld), `message` (Erfolg, grün), `error` (Fehler, rot).

**Funktionsweise:**
- **Datenquellen (`init`):** sammelt `settingsStore.settings` (übernimmt `calibration`, setzt `updateCalibration` am Tracker, nimmt eine manuelle Position), sammelt `locationTracker.locations()` (manuelle Position hat Vorrang, danach `refreshCandidates()`), und setzt einmalig `locationTracker.lastKnown()` als Start-Standort.
- `refreshCandidates()`: alle Katalogobjekte mit mag ≤ 2,5 → aktuelle Horizontposition (Präzession + LST) → Höhe > 15° → nach Helligkeit sortiert. Behält die Auswahl, wenn der Stern noch dabei ist, sonst den hellsten. Läuft im `viewModelScope` auf dem Main-Dispatcher (kein `withContext`).
- `currentPositionOf(star)`: aktuelle Az/Höhe für Zielhilfen (Chip-Beschriftung, Ring im Overlay).
- **Sternmuster (`STARS`):** Nutzer wählt Stern (`selectStar`), richtet Fadenkreuz aus, `captureStarSighting()` speichert `StarSample(measured = attitude.uncalibratedCameraDirection, reference = scheinbare Position jetzt)`. Bewusst **unkalibrierte** Richtung, damit die Korrektur nicht gegen sich selbst gefittet wird. Fehler > 25° → `error`. Pro Stern nur ein Sample (Ersetzen). `removeStarSample(id)`. `applyStarCalibration()` → `AttitudeFit.solve(List<DirectionPair>)` → `calibration.withAttitude(rotation, STAR_PATTERN, residualDeg, sampleCount, atMillis, measuredAt = observer)` → speichern; Meldung mit Korrekturwinkel, Restfehler und Hinweis, falls `!fit.correctsTiltToo` (ein zweiter, weit entfernter Stern korrigiert auch die Neigung).
- **Peilung (`LANDMARK`):** `setLandmarkBearingText`, dann `applyLandmarkBearing()`: Text (Komma erlaubt) → 0–360° prüfen → `AttitudeFit.solveHeadingOnly` mit einem `DirectionPair(measured, measured mit Azimut = Peilung)` → `withAttitude(..., LANDMARK_BEARING, sampleCount = 1, measuredAt = observer)`.
- **Schwenk (`PAN_SWEEP`):** Jeder Tipp ruft `capturePanSighting(x, y, viewW, viewH)` → `PanSighting(worldFromDisplay, offsetX = x − W/2, offsetY = H/2 − y)` (braucht weder Kompass noch Sterne, nur die Drehung zwischen den Taps). `clearPanSamples()`. `applyPanSweep(baselineFocalPx, viewWidthPx)` → `FieldOfViewSolver.solve(sightings, baselineFocalPx)` (`Result`; Fehler als `FovFitException`-Text) → `scale = baselineFocalPx / fit.focalPx` → `calibration.withFov(scale, PAN_SWEEP, residualDeg, sampleCount, atMillis)`; Meldung mit gemessenem Bildfeld (`FieldOfViewSolver.fieldOfViewDeg`).
- **Manuell (`MANUAL`):** `setManualFovScale(scale)` speichert direkt `withFov(scale, MANUAL, residualDeg = null, sampleCount = 0, …)`.
- **Zurücksetzen:** `resetAttitude()` (`clearAttitude()`), `resetFov()` (`clearFov()`).
- `storeCalibration` schreibt in `settingsStore.setCalibration`, meldet sofort an `orientationTracker.updateCalibration` und aktualisiert den State. `failWith(msg)` setzt nur `error`. `setMethod` löscht `message`/`error`; `consumeMessages()` ebenso (derzeit von der UI nicht aufgerufen).

**Zusammenspiel:** Der `OrientationTracker` ist eine geteilte Instanz – die hier gespeicherte Kalibrierung wirkt sofort auch im Sucher. Basis-Brennweite und Bildbreite liefert der `SkyViewport` des Bildschirms.

**Ändern/Fallstricke:**
- Ausrichtungsmessungen immer mit `uncalibratedCameraDirection`, nie mit `cameraDirection`.
- Die Sollrichtung eines Sterns muss die **scheinbare** Position zum Messzeitpunkt sein (Präzession!), sonst wird ein Drittelgrad als Kompassfehler eingemessen.
- `measuredAt` wird für die Vertrauensbewertung (`trustAt`, Anzeige im Sucher und hier) gebraucht; beim Bildfeld wird kein Ort gespeichert.
- Der Manuell-Slider schreibt bei jeder Bewegung in den `SettingsStore`.
- Wird die manuelle Position im Sucher entfernt, behält dieses ViewModel die alte (`settings.manualLocation ?: it.observer`), bis eine GPS-Position eintrifft.

#### [`CalibrationScreen.kt`](app/src/main/java/com/starwindow/app/ui/calibration/CalibrationScreen.kt)

Sucher mit Kalibrier-Overlay oben (Status) und Panel unten (Verfahrenswahl + verfahrensspezifische Bedienung).

| Element | Art | Aufgabe |
|---|---|---|
| `CalibrationScreen(viewModel, settings, onBack, modifier)` | @Composable | Bildschirm; `settings` liefert Belichtung und Gitter-Schalter. |
| `CalibrationHeader` | private @Composable | Zurück, Titel, Status von Ausrichtung/Bildfeld, Vertrauenswarnung (`trustAt` + `explanation`), `SensorHealthLine`. |
| `SensorHealthLine` | private @Composable | Sensorquelle, Kompassgüte, Feldstärke/-neigung vs. erwartet, Eigenmagnetismus, Warnungen (Acht schwenken, Feldrichtung/Stärke gestört, niedrige Güte), Kreisel-Drift. |
| `CalibrationPanel` | private @Composable | `FilterChip` je Methode, Anleitung, „braucht freien Himmel", Unterpanel, `error`/`message`. |
| `StarPanel` / `LandmarkPanel` / `PanSweepPanel` / `ManualPanel` | private @Composable | Bedienung je Verfahren (Sternauswahl als `LazyRow` mit Höhe; Peilungsfeld; Zähler + „Auswerten"/„Verwerfen"; Slider 0,7–1,4 in 0,01-Schritten). |

**Funktionsweise:** Liest `uiState`, `hudAttitude` (Werte) und `attitude` als `State` (für das Overlay). `streamInfo` und `viewSize` sind lokaler State; `rememberSkyViewport(streamInfo, viewSize, state.calibration.fovScale)`. Tipps werden nur im Verfahren `PAN_SWEEP` und bei `viewport.isReady` ausgewertet (`pointerInput(state.method, viewport)`). Das Overlay zeigt im Sternverfahren nur `candidateStars` als Katalog und den gewählten Stern als `highlightDirection` (über `viewModel.currentPositionOf`, in der Komposition berechnet). „Auswerten" ruft `applyPanSweep(viewport.baselineFocalPx, viewport.viewSize.width)`.

**Zusammenspiel:** `onBack` → `navController.popBackStack()`. Keine eigene Berechtigungsanfrage – die erteilt der Sucher; `CameraPreview` wird ohne `onError` aufgerufen. Kein `chromeInsets` an `SkyOverlay`, der Zielpfeil kann also unter dem Panel liegen.

**Ändern/Fallstricke:** Alle Texte (Anleitungen in `CalibrationMethod.instructions`, Meldungen im ViewModel, Hinweise hier) sind fest verdrahtete deutsche Strings.

#### Typische Änderungen in diesem Bereich
- **Kalibrierverfahren anpassen:** Mathematik in `core/calibration` (`AttitudeFit`/`FieldOfViewSolver`), Messablauf in der jeweiligen `CalibrationViewModel`-Methode (`capture…`/`apply…`), Schwellen über `MAX_PLAUSIBLE_ERROR_DEG` bzw. die Filter in `refreshCandidates` (mag ≤ 2,5, Höhe > 15°).
- **Neues Verfahren:** Enum-Eintrag in `CalibrationMethod` (Texte), Mess-/Auswerte-Methoden im ViewModel mit neuer `CalibrationSource` (core), neues `XxxPanel` und Fall im `when` von `CalibrationPanel`; wenn es Tipps braucht, die Bedingung im `pointerInput` erweitern.
- **Bereich des Manuell-Sliders ändern:** `valueRange`/`steps` in `ManualPanel`.

<a id="pkg-ui-windows"></a>

### `ui/windows` — Gespeicherte Fenster und ihre Durchgänge

Dieses Paket enthält den Reiter „Fenster“: die Liste der gespeicherten Himmelsfenster (`WindowListScreen`) und die Detailseite eines Fensters (`WindowDetailScreen`). Auf der Detailseite wird berechnet, welche Katalogobjekte und Sternbilder in einem wählbaren Zeitraum durch das Fenster ziehen. Dazu gehören zwei Diagramme: die Laufbahnen im Fenster (`WindowTrackChart`) und die Höhenkurve eines Objekts (`AltitudeCurveChart`). Außerdem liegen hier zwei Dinge, die auch andere Pakete benutzen: das Objekt-Infoblatt (`ObjectInfoSheet.kt`, Inhalt wird von `ui/search` wiederverwendet) und die Zeitformatierer (`TimeFormatting.kt`, genutzt von Suche, Planung und Wetter). DEV_PLAN §11.4 S1 sieht vor, beide nach `ui/components` zu verschieben.

**Benutzt:** `data/windows/SkyWindowRepository`, `data/tracking/TrackingStore` (`TrackedWindow`), `data/catalog/CatalogRepository`, `ConstellationRepository`, `ObjectNotesRepository`, `data/images/SkyImageLoader`, `domain/TransitCalculator`, `ConstellationTransitCalculator`, `TransitListView`/`TransitSort`, `ResultFilter`, `SkyTrack`/`SkyTrackBuilder`, `WindowAccuracy` (`accuracy()`), `ObjectDescription`, `core/geometry/SkyWindow`/`TangentPlane`, `core/astro` (`Precession`, `AstroTime`, `CoordinateTransforms`, `Angles`), `ui/components` (`ScreenHeader`, `ObjectSymbol`, `objectSubtitle`, `SectionCard`, `rememberSkyImage`), `ui/theme`.

**Wird benutzt von:** `ui/nav/StarWindowNavHost` (Routen `windows` und `windows/{windowId}`), `ui/search` (`ObjectInfo`, `ObjectInfoContent`, `ObjectInfoTitle`, `formatClock`), `ui/planning/PlanningScreen` (`formatClock`), `ui/weather/WeatherScreen` (`formatDuration`).

#### [`WindowListViewModel.kt`](app/src/main/java/com/starwindow/app/ui/windows/WindowListViewModel.kt)

Ein schlankes ViewModel für die Fensterliste. Es hat keinen eigenen `UiState`, sondern reicht zwei Flows durch.

| Element | Art | Aufgabe |
|---|---|---|
| `WindowListViewModel` | class | Hält `repository` (`SkyWindowRepository`) und `trackingStore` |
| `windows` | val (`StateFlow<List<SkyWindow>>`) | Direkt `repository.windows` |
| `trackedWindowId` | val (`StateFlow<String?>`) | `trackingStore.target`, auf die `windowId` eines `TrackedWindow` abgebildet (`map` + `stateIn(WhileSubscribed(5_000))`). Ist das Ziel ein Objekt, ergibt das `null` |
| `delete(id)` | fun | `repository.delete(id)` in einer Coroutine; danach sofort `trackingStore.clearWindow(id)`, damit kein Zeiger auf ein gelöschtes Fenster übrig bleibt |
| `track(window)` | fun | `trackingStore.track(TrackedWindow.of(window))`: Der Sucher zeigt dann auf das Fenster |
| `factory(container)` | companion fun | `viewModelFactory` mit `windowRepository` und `trackingStore` |

**Funktionsweise:** Im `init` wird `repository.load()` aufgerufen (liest die Datei auf `Dispatchers.IO`). Danach kommt jede Änderung über den StateFlow des Repositorys an.

**Ändern/Fallstricke:** Eine Umbenennen-Funktion gibt es weder hier noch in `WindowDetailViewModel` oder im Repository. Sie müsste neu gebaut werden (siehe „Typische Änderungen“). Gelöscht wird ohne Rückfrage.

#### [`WindowListScreen.kt`](app/src/main/java/com/starwindow/app/ui/windows/WindowListScreen.kt)

Der Bildschirm des Reiters „Fenster“: eine Karte je gespeichertem Fenster.

| Element | Art | Aufgabe |
|---|---|---|
| `WindowListScreen(viewModel, onOpenWindow, onTrackWindow)` | @Composable | Sammelt `windows` und `trackedWindowId` per `collectAsStateWithLifecycle`. Zeigt den Leerzustand oder eine `LazyColumn` (Key `window.id`) |
| `WindowCard` | private @Composable | Name, Mitte in Az/Alt, Fläche in °², RA/Dec bei Aufnahme (`centerEquatorialAtCapture()`), Aufnahmezeit (`formatTimestamp`). Dazu zwei Icon-Buttons: Verfolgen und Löschen |

**Zusammenspiel:** Ein Tipp auf die Karte ruft `onOpenWindow(window.id)` auf; der NavHost navigiert dann zu `Routes.windowDetail(id)`. Das Fadenkreuz-Symbol ruft zuerst `viewModel.track(window)` und dann `onTrackWindow()` auf. Der NavHost springt damit per `popBackStack(Routes.CAPTURE, inclusive = false)` zum Sucher zurück. Das Ziel steht zu diesem Zeitpunkt schon im `TrackingStore`. Das Symbol wird in `StarWindowColors.TrackTarget` eingefärbt, wenn genau dieses Fenster verfolgt wird. Alle Texte (Überschrift „Gespeicherte Fenster“, Leerzustand) stehen direkt im Composable.

#### [`WindowDetailViewModel.kt`](app/src/main/java/com/starwindow/app/ui/windows/WindowDetailViewModel.kt)

Das Herz des Pakets. Es lädt ein Fenster, stößt die Durchgangsberechnung für Objekte und Sternbilder an, hält Filter, Sortierung und Auswahl und baut daraus die Bahnen fürs Diagramm und die Daten für das Infoblatt.

| Element | Art | Aufgabe |
|---|---|---|
| `WindowDetailUiState` | data class | Gesamter Zustand der Detailseite, siehe unten |
| `WindowDetailViewModel(windowId, …)` | class | Bekommt `windowRepository`, `catalogRepository`, `constellationRepository`, `transitCalculator`, `constellationTransitCalculator`, `trackingStore`, `objectNotesRepository` |
| `setTimeRange(start, end)` | fun | Speichert die Stunden-Offsets (`start` 0–47, `end` mindestens `start+1`, höchstens 48). **Rechnet nicht neu**, das tut erst `search()` |
| `setMagnitudeLimit(limit)` | fun | Setzt die Grenzgröße und ruft `search()` auf (neue Berechnung) |
| `setFilter(filter)` / `setOnlyDark(b)` / `setQuery(q)` / `clearQuery()` | fun | Ändern nur die Listenansicht. Danach `rebuildTracks()`, keine neue Berechnung |
| `setSort(sort)` | fun | Ändert nur `sort` (ohne `rebuildTracks()`) |
| `toggleSelection(id)` / `clearSelection()` | fun | Nimmt eine Objekt- oder Sternbild-ID in `selectedIds` auf bzw. wieder heraus, danach `rebuildTracks()` |
| `openInfo(objectId)` / `closeInfo()` | fun | Baut ein `ObjectInfo` für das Infoblatt bzw. setzt `info = null` |
| `track(window)` | fun | `trackingStore.track(TrackedWindow.of(window))` |
| `search()` | fun | Die eigentliche Durchgangsberechnung, siehe unten |
| `rebuildTracks()` | private fun | Erzeugt `tracks`, `emphasisedTrackIndices` und `figureSegments` fürs Diagramm |
| `MAX_TRACKS = 6`, `MAX_FIGURE_TRACKS = 3`, `MAX_PASSES_PER_OBJECT = 4` | const | Grenzen für das Diagramm |
| `factory(container, windowId)` | companion fun | Factory, wird im NavHost mit `key = windowId` benutzt |

**`WindowDetailUiState`, Felder:** `window` (geladenes `SkyWindow` oder `null`), `result` (`TransitSearchResult` des `TransitCalculator`), `constellations` (`List<ConstellationTransit>`), `startHoursOffset`/`endHoursOffset` (Zeitraum in Stunden ab *jetzt*, Vorgabe 0–24), `magnitudeLimit` (Vorgabe 8.0), `filter` (`ResultFilter`, Vorgabe `ALL`), `query` (Freitext, filtert nur die Treffer, nicht den Katalog), `sort` (`TransitSort`, Vorgabe `INTEREST`), `onlyDark`, `selectedIds` (gewählte Objekt- oder Sternbild-IDs fürs Diagramm), `tracks` (`List<SkyTrack>`), `emphasisedTrackIndices`, `figureSegments` (Linien der Sternbildfigur als Paare von `Horizontal`), `info` (`ObjectInfo?`, offenes Infoblatt), `isSearching`, `error`.
**Abgeleitete Getter** (werden bei *jedem* Zugriff neu gerechnet): `visibleObjects` = `TransitListView.build(result.transits, filter, query, sort, onlyDark)`; `darkObjectCount`; `visibleConstellations` (nur bei `filter.showsConstellations` und leerer Suche); `isEmpty`; `hasQuery`; `totalObjects`; `highlights` = `TransitListView.highlightsByKind(...)`, nur bei Filter `ALL` und leerer Suche.

**Funktionsweise – Berechnung (`search()`):**
1. Ohne Fenster passiert nichts. Sonst wird in `viewModelScope.launch` zuerst `isSearching = true` gesetzt.
2. Der Zeitraum ergibt sich als `from = now + startHoursOffset·1 h`, `until = now + endHoursOffset·1 h`. `now` ist der Zeitpunkt des Aufrufs.
3. `catalogRepository.objectsVisibleFrom(latitude, magnitudeLimit)` filtert vorab: Objekte, die an dieser Breite nie aufgehen, und zu lichtschwache fallen weg. Sonne und Mond (`isMoving`) bleiben immer drin, ebenso Objekte ohne Helligkeit, wenn sie mindestens 3′ groß sind.
4. `transitCalculator.search(window, objects, from, until)` und danach `constellationTransitCalculator.search(window, constellationRepository.constellations(), from, until)`. Beide sind `suspend` und rechnen selbst auf `Dispatchers.Default`. Das ViewModel braucht deshalb kein eigenes `withContext`. Dämmerung und Mond je Durchgang (`DarkSpans`, `conditionLabel`, `darkness`, `moonInterferes`) setzt der `TransitCalculator` schon in die Intervalle ein.
5. Bei Erfolg werden `result` und `constellations` gesetzt, danach `rebuildTracks()`. Bei einem Fehler landet die Meldung in `error` (`runCatching`).

Neu gerechnet wird beim Öffnen (`init`: `windowRepository.load()`, `find(windowId)`, `search()`), beim Loslassen des Zeitraum-Sliders (`onValueChangeFinished` im Screen) und bei jeder Änderung der Grenzgröße. Filter, Suchtext, „nur nachts“ und Sortierung arbeiten nur auf dem vorhandenen Ergebnis. Eine Nacht-Auswahl im engeren Sinn gibt es nicht, nur den Stundenbereich 0–48 h ab jetzt.

**Funktionsweise – Diagrammbahnen (`rebuildTracks()`):** Ohne Auswahl werden die ersten `MAX_TRACKS` aus `visibleObjects` genommen, je nur mit dem ersten Durchgang und nicht hervorgehoben. Mit Auswahl kommen nur die gewählten Objekte ins Diagramm, je bis zu `MAX_PASSES_PER_OBJECT` Durchgänge, alle hervorgehoben. Jede Bahn baut `SkyTrackBuilder.forInterval(label, equatorial, observer, enter, exit)`; die Präzession wird dabei einmal für *jetzt* gerechnet. Ist ein Sternbild gewählt (nur das erste aus `selectedIds` zählt), dann
- liefert `constellationTransitCalculator.figureAt(...)` die Figurlinien zum Zeitpunkt `peakMillis` des Intervalls mit den meisten Sternen innen,
- kommen die ersten `MAX_FIGURE_TRACKS` Sterne der Figur als zusätzliche Bahnen dazu,
- und **nur diese** werden hervorgehoben.
Die Methode läuft synchron auf dem Main-Thread.

**Funktionsweise – Infoblatt (`openInfo`):** Das Objekt wird im aktuellen Ergebnis gesucht. Dann wird in einer Coroutine erst die Beschreibung geholt (`ObjectDescription.describe(obj, objectNotesRepository.noteFor(obj))`; `noteFor` liest beim ersten Mal die Asset-Datei). Erst danach wird `info` in einem Schritt gesetzt, mit folgendem Inhalt:
- Höhenkurve über den ganzen gewählten Zeitraum (`SkyTrackBuilder.overSpan`),
- `passes` aus den Intervallen (`WindowPass`),
- aktuelle Az/Höhe (`CoordinateTransforms.apparentHorizontalAtLst`),
- `fillFactor` = `obj.fillFactor(window.shape.angularRadiusDeg())`.

**Zusammenspiel:** Erzeugt wird das ViewModel im NavHost bei Route `windows/{windowId}`. Der `WindowDetailScreen` ruft die öffentlichen Methoden auf. „Verfolgen“ schreibt in den `TrackingStore`, der Sucher (`ui/capture`) liest von dort.

**Ändern/Fallstricke:**
- Wer den Zeitraum verändert, muss danach `search()` aufrufen; `setTimeRange` allein rechnet nicht neu.
- `search()` merkt sich keinen `Job`. Zwei schnell hintereinander gestartete Berechnungen werden nicht abgebrochen, das Ergebnis der zuletzt *fertigen* gewinnt.
- `setSort` ruft kein `rebuildTracks()` auf. Ohne Auswahl folgt die Beispielauswahl im Diagramm (erste 6 aus `visibleObjects`) also nicht sofort der neuen Sortierung.
- Die Getter in `WindowDetailUiState` sind nicht gecacht und werden pro Recomposition mehrfach ausgewertet (`isEmpty`, `visibleObjects`, `highlights`).
- `positionAt(precession)` liefert für Sonne und Mond eine Position zum Zeitpunkt der Präzession. In Diagramm und Höhenkurve wird sie als fest angenommen, die Bewegung über den Zeitraum fehlt also.
- Alle Fehlertexte („Fenster nicht gefunden“, „Berechnung fehlgeschlagen“) stehen hier im Code.

#### [`WindowDetailScreen.kt`](app/src/main/java/com/starwindow/app/ui/windows/WindowDetailScreen.kt)

Die Detailseite eines Fensters (888 Zeilen, DEV_PLAN S6). Sie ist ein reines Darstellungs-Composable über `WindowDetailUiState`: Kopfzeile, Diagramm, Geometrie-Karte, Bedienelemente, Ergebnisliste.

| Element | Art | Aufgabe |
|---|---|---|
| `WindowDetailScreen(viewModel, onBack, onTrackWindow)` | @Composable | Einstieg. Holt sich `skyImageLoader` direkt über `LocalContext.current.appContainer` (nicht über das ViewModel). Zeigt `ObjectInfoSheet`, sobald `state.info != null` |
| `trackPalette` | private val | Sechs Bahnfarben. Die Farbe richtet sich nach dem *Label* der Bahn, damit mehrere Durchgänge desselben Objekts gleich gefärbt sind |
| `AccuracyBar(accuracy, windowRadiusDeg)` | private @Composable | Balken zur Zeigegenauigkeit (`WindowAccuracy`). Volle Breite entspricht dem Fensterradius (Rückfall: 15°). Farbe nach `AccuracyBand` |
| `SectionHeader(title, count, note)` | private @Composable | Abschnittsüberschrift mit Zahl vorn. Nicht zu verwechseln mit `ui/components/SectionHeader` |
| `WindowSummaryCard(window)` | private @Composable | Geometrie, Beobachtungsort, magnetische Deklination, Zeigegenauigkeit, RA/Dec bei Aufnahme und jetzt, Sternzeit |
| `ConstellationCard` | private @Composable | Sternbild: Figursterne innen / gesamt, alle Intervalle |
| `IntervalRow(from, to, duration, peak)` | private @Composable | Eine Zeile pro Durchgang in drei Spalten. `"läuft"` steht da, wo das Intervall am Zeitraumrand abgeschnitten ist (`clippedAtStart`/`clippedAtEnd`) |
| `TransitCard` | private @Composable | Objektzeile: Symbol, ID und Name, `objectSubtitle`, Gesamtdauer im Fenster, Info-Button. Dazu der erste Durchgang mit „max x°“ und `conditionLabel` (Farbe nach `TransitDarkness`/`moonInterferes`) sowie „+ n weitere Durchgänge“ |
| `SelectableCard` | private @Composable | Karte, die bei Auswahl hinterlegt wird und einen Punkt bekommt |
| `ResultSearchField` | private @Composable | Suchfeld über den Treffern („In den Ergebnissen suchen“) |
| `HighlightRow` | private @Composable | „Die besten je Art“: bis zu 3 je Art. „alle zeigen“ ruft `setFilter(kind)` auf, ein Zeilentipp `openInfo` |

**Funktionsweise:** Aufbau der `LazyColumn`:
1. Diagramm `WindowTrackChart`; aus `state.tracks` werden dafür `ChartTrack`s mit Farbe und `emphasised` gebaut. Dazu der Erklärtext und, bei Auswahl, der Knopf „Alle“ (`clearSelection`).
2. `WindowSummaryCard`.
3. Bedienelemente:
   - `RangeSlider` 0–48 h mit `steps = 47`: `onValueChange` → `setTimeRange`, `onValueChangeFinished` → `search()`,
   - Grenzgrößen-Chips 3/6/8/12 mag → `setMagnitudeLimit`,
   - Art-Chips aus `ResultFilter.entries` → `setFilter`,
   - Chip „nur nachts (x von y)“ → `setOnlyDark`,
   - Sortier-Chips aus `TransitSort.entries` → `setSort`.
4. Das Suchfeld, nur wenn ein Ergebnis da ist.
5. Je nach Zustand: Ladekreis, Fehler, Leertext oder die Abschnitte Highlights → Sternbilder → Objekte.

Ein Tipp auf eine Karte ruft `toggleSelection` auf (das Diagramm zeigt dann nur die gewählten Bahnen), das Info-Symbol `openInfo`. Die Kopfzeile des Abschnitts „Objekte“ zeigt die Sortierbeschreibung (`TransitSort.listNote`) und die Rechenzeit `result.computeMillis`.

**Zusammenspiel:** Der Zurück-Pfeil ruft `onBack` auf. Das Fadenkreuz oben rechts ruft `viewModel.track(window)` und danach `onTrackWindow()` auf; der NavHost springt dann per `popBackStack(Routes.CAPTURE)` zum Sucher. Vom Infoblatt aus gibt es keinen Weg zum `ObjectDetailScreen`.

**Ändern/Fallstricke:**
- Die Uhrzeiten unter dem Slider („jetzt“, „+24 h“, „+48 h“) und der Zeitraumtext werden bei jeder Recomposition mit einem frischen `System.currentTimeMillis()` gerechnet. Die Berechnung benutzt das `now` vom Zeitpunkt des `search()`-Aufrufs.
- Die Grenzgrößen-Werte (`listOf(3.0, 6.0, 8.0, 12.0)`) stehen fest im Screen. Die Vorgabe `8.0` steht im `UiState` und muss in dieser Liste enthalten sein, sonst ist kein Chip markiert.
- Alle Texte (Erklärtexte, Leerzustände) stehen inline.

#### [`WindowTrackChart.kt`](app/src/main/java/com/starwindow/app/ui/windows/WindowTrackChart.kt)

Das Diagramm „Laufbahnen durch das Fenster“: Das Fenster steht still, die Objektbahnen ziehen hindurch. Gezeichnet wird auf der Tangentialebene der Fenstermitte und nicht in einem Az/Höhe-Raster, damit die Fensterform und die Bahnkrümmung unverzerrt bleiben.

| Element | Art | Aufgabe |
|---|---|---|
| `ChartTrack(track, color, emphasised)` | data class | Eine zu zeichnende Bahn mit Farbe und Hervorhebung |
| `WindowTrackChart(window, tracks, modifier, figureSegments)` | @Composable | `Canvas` mit 260 dp Höhe |
| `LabelSpace` | private class | Kollisionsvermeidung: Jede Beschriftung belegt ein Rechteck. Passt sie an keiner der probierten Ankerpositionen (`LabelAnchor` RIGHT/LEFT/ABOVE/BELOW), wird sie weggelassen |
| `ChartViewport` | private class | Abbildung Tangentialebene → Pixel mit **einem** gemeinsamen Maßstab für x und y (`fit(points, w, h, margin = 1.5)`) |
| `drawReferenceLines` | private fun | Hintergrund und Gradraster von der Mitte aus. Schrittweite aus 0.25…20° (sonst 30°), Ziel etwa 56 px; Beschriftung „Raster x°“ |
| `drawWindow` | private fun | Fensterumriss gefüllt und umrandet |
| `drawTrackGeometry` | private fun | Linie (durchgezogen innerhalb `enterMillis..exitMillis`, gestrichelt beim An- und Abflug), Pfeilspitze am Ende, Stundenpunkte (`track.hourMarks()`), Ein-/Austrittsmarker |
| `drawTrackName` / `drawEntryExitLabels` / `drawHourLabels` | private fun | Beschriftungen in dieser Rangfolge; hervorgehobene Bahnen zuerst. Stundenlabels: höchstens `MAX_HOUR_LABELS = 2` (bzw. `MAX_HOUR_LABELS_EMPHASISED = 5`), bei nicht hervorgehobenen Bahnen nur jede zweite Stunde |
| `drawOrientationLabels` | private fun | Feste Hinweise „↑ Zenit“ und „Osten →“, reservieren ihren Platz in `LabelSpace` |
| `ChartPaints` | private class | `android.graphics.Paint`s für den Text, abhängig von der Dichte (mit `remember(density)`) |
| `drawLabel` | private fun | Text über `nativeCanvas.drawText`. Doppelt vorhanden, siehe `AltitudeCurveChart` (DEV_PLAN S7) |

**Funktionsweise – Abbildung Az/Höhe → Pixel:**
1. `TangentPlane(window.centerHorizontal)` (mit `remember(window.id)`) projiziert jede `Horizontal`-Richtung gnomonisch auf `PlanarPoint(x, y)`. x zeigt entlang der horizontalen Basis der Ebene, y nach oben Richtung Zenit; die Einheit ist tan(Winkel). Punkte auf der abgewandten Halbkugel ergeben `null` und werden übersprungen.
2. `ChartViewport.fit` nimmt die Ausdehnung des projizierten Umrisses (`window.shape.outline(96)`) mal 1.5 und wählt `scale = min(w/spanX, h/spanY)`. So erscheint das Fenster unverzerrt und mittig.
3. `toCanvas` rechnet `x_px = w/2 + (x − cx)·scale` und `y_px = h/2 − (y − cy)·scale`; y wird gespiegelt, weil die Canvas nach unten wächst.
4. Linien außerhalb der Canvas (plus Rand, 400–600 px) werden nicht gezeichnet.

Reihenfolge beim Zeichnen: Raster, Orientierung, Sternbildfigur (`figureSegments`, gedämpft), Fenster, die Geometrie aller Bahnen, danach die Beschriftungen.

**Ändern/Fallstricke:**
- Wer weitere Beschriftungen will, muss sie über `LabelSpace.place` setzen, sonst überlappen sie.
- Der Hinweis „Osten →“ steht fest rechts oben, egal in welche Himmelsrichtung das Fenster zeigt. Ob das für alle Fensterrichtungen stimmt, ist unklar (vgl. `TangentPlane`-Basis in `core/geometry/SphericalGeometry.kt`).
- Der Kommentar an `drawOrientationLabels` („Drawn last“) passt nicht zum Code: Die Funktion wird früh aufgerufen.

#### [`AltitudeCurveChart.kt`](app/src/main/java/com/starwindow/app/ui/windows/AltitudeCurveChart.kt)

Die Höhenkurve „Höhe über dem Horizont“ eines Objekts gegen die Uhrzeit. Sie wird im Infoblatt und auf dem Objektblatt benutzt.

| Element | Art | Aufgabe |
|---|---|---|
| `WindowPass(fromMillis, toMillis)` | data class | Zeitspanne, in der das Objekt im Fenster steht |
| `AltitudeCurveChart(track, passes, modifier, nowMillis)` | @Composable | `Canvas` mit 210 dp (`CHART_HEIGHT_DP`); links ein Rand von 34 dp für die Gradzahlen, unten ein 24 dp hohes Band für die Stunden |
| `PlotArea` | private class | Zeichenrechteck ohne Achsenbeschriftung |
| `CurveScale` | private class | `x(millis)` linear über die erste bis letzte Track-Zeit, `y(alt)` linear über `minAltitude..maxAltitude`. `fit` schließt den Horizont (0°) immer ein, rundet auf 10° und nimmt mindestens 20° Spanne |
| `drawPasses` | private fun | Fensterzeiten als grüne Bänder im Hintergrund |
| `drawGrid` | private fun | Höhenlinien alle 15° (bzw. 30° bei mehr als 70° Spanne). Stundenstriche: Abstand 1/2/3/6 h je nach Gesamtdauer, Beschriftung `HH` |
| `drawHorizon` | private fun | Bereich unter 0° abgedunkelt, Linie und Text „Horizont“ |
| `drawCurve` | private fun | Kurve in `CURVE_COLOR`; innerhalb von `passes` dicker nachgezeichnet |
| `drawPeak` / `drawNow` | private fun | Höchster Punkt mit „x° um HH:mm“ (nur über dem Horizont); rote „jetzt“-Linie |
| `CurvePaints`, `hourFormatter`, `hourMinuteFormatter` | private | Text-Paints und eigene `DateTimeFormatter` (DEV_PLAN S7) |

**Ändern/Fallstricke:** Der Zeitbereich ergibt sich allein aus `track.points`. Wer eine andere Spanne will, ändert den Aufrufer (`SkyTrackBuilder.overSpan` in `WindowDetailViewModel.openInfo` bzw. `ObjectDetailViewModel.buildInfo`), nicht das Diagramm.

#### [`ObjectInfoSheet.kt`](app/src/main/java/com/starwindow/app/ui/windows/ObjectInfoSheet.kt)

Datenmodell und Darstellung „alles über ein Objekt“, aufgeteilt in eine Dialog-Hülle und den Inhalt. Den Inhalt benutzt auch das Vollbild-Objektblatt in `ui/search`.

| Element | Art | Aufgabe |
|---|---|---|
| `ObjectInfo` | data class | `obj`, `track` (für die Höhenkurve), `passes` (nur aus Fenstersicht), `currentPosition`, `fillFactor` (nur aus Fenstersicht), `description` |
| `ObjectInfoSheet(info, imageLoader, onDismiss)` | @Composable | Trotz des Namens ein `AlertDialog` (kein BottomSheet): Titel, scrollbarer Inhalt, Knopf „Schließen“ |
| `ObjectInfoTitle(obj)` | @Composable | Name (ersatzweise ID) über „ID · Typ · Sternbild“ |
| `ObjectInfoContent(info, imageLoader, modifier)` | @Composable | Drei Karten: Bild (`SkyImageView`), Beschreibung (`DescriptionBlock`), Höhenkurve und Zahlen (`InfoRow`s) |
| `DescriptionBlock` | private @Composable | Handgeschriebene Notiz (`description.note`), danach `whatItIs`, dann `traits` als Liste |
| `SkyImageView` | private @Composable | Bild über `rememberSkyImage(obj, imageLoader)` aus `ui/components`; Ladekreis, Fehlertext, Bildnachweis `SKY_IMAGE_CREDIT` |
| `InfoRow(label, value, hint)` | private @Composable | Bei `null`/leerem Wert wird nichts angezeigt, die Zeile fällt also einfach weg |
| `formatSize` | private fun | Größe als `a' × b'` oder `a'` |

**Funktionsweise:** Rein darstellend; alle Werte stellt das ViewModel bereit. Die Zahlenkarte zeigt Helligkeit, Flächenhelligkeit, Ausdehnung, Positionswinkel, Morphologie, RA/Dec (unter dem Label „Rektaszension“), „Jetzt“ (Az/Höhe), „Im Fenster“ (`fillFactor`: über 1 heißt größer als das Fenster), weitere Katalognummern (höchstens 8), weitere Namen und die Quelle.

**Ändern/Fallstricke:** Eine neue Info-Zeile wird als weiteres `InfoRow` in `ObjectInfoContent` eingetragen und erscheint dann an **beiden** Stellen (Fensterdialog und Objektblatt). Wird ein neues Feld in `ObjectInfo` gebraucht, müssen beide Erzeuger es füllen: `WindowDetailViewModel.openInfo` und `ObjectDetailViewModel.buildInfo`.

#### [`TimeFormatting.kt`](app/src/main/java/com/starwindow/app/ui/windows/TimeFormatting.kt)

Drei paketweit genutzte Formatierer, gebunden an `ZoneId.systemDefault()`, das beim Laden der Datei einmal festgelegt wird: `formatTimestamp` (`dd.MM.yyyy HH:mm`), `formatClock` (`HH:mm`) und `formatDuration` (`"2 h 07 min"`, `"41 min"` bzw. `"38 s"`). Benutzt werden sie auch in `ui/search`, `ui/planning` und `ui/weather`. Wer das Format ändert, ändert es für alle diese Stellen.

#### Typische Änderungen in diesem Bereich
- **Neue Info in der Durchgangsliste (Objektzeile):** Zuerst prüfen, ob der Wert schon in `ObjectTransit`/`TransitInterval` (`domain/TransitCalculator.kt`) steht. Falls nicht, dort ergänzen und in `TransitCalculator.search` bzw. in `withConditions` füllen. Danach in `WindowDetailScreen.TransitCard` (oder `IntervalRow`) anzeigen.
- **Zeitraum der Berechnung ändern (z. B. bis 72 h):** In `WindowDetailViewModel.setTimeRange` die `coerceIn`-Grenzen anpassen. In `WindowDetailScreen` beim `RangeSlider` `valueRange` und `steps` ändern, ebenso die drei Beschriftungen darunter. Die Vorgabe steht in `WindowDetailUiState.endHoursOffset`. Längere Zeiträume kosten im `TransitCalculator` linear mehr Rechenzeit (60-s-Schritte).
- **Weitere Grenzgröße oder neue Sortierung:** Grenzgrößen-Liste in `WindowDetailScreen`; eine Sortierung als neuen Eintrag in `TransitSort` samt `listNote` und Zweig in `TransitListView.sorted`. Die Chips entstehen automatisch aus `entries`.
- **Neuer Listenfilter (wie „nur nachts“):** Ein Feld in `WindowDetailUiState`, ein Setter im ViewModel, der `rebuildTracks()` aufruft, ein Parameter in `TransitListView.build`, ein Chip im Screen.
- **Fenster umbenennen (gibt es noch nicht):** In `SkyWindowRepository` eine Methode, die `save(window.copy(name = …))` aufruft, denn `save` ersetzt nach `id`. Dazu eine Methode im ViewModel (Liste oder Detail) und einen Dialog mit Textfeld im jeweiligen Screen. Danach `WindowDetailViewModel.window` neu setzen; das Detail-ViewModel beobachtet das Repository nicht, sondern lädt einmal mit `find`.
- **Mehr oder weniger Bahnen im Diagramm:** Die Konstanten `MAX_TRACKS`, `MAX_PASSES_PER_OBJECT` und `MAX_FIGURE_TRACKS` in `WindowDetailViewModel`. Mehr als 6 gleichzeitig sichtbare Objekte wiederholen die Farben aus `trackPalette` (in `WindowDetailScreen`).

<a id="pkg-ui-search"></a>

### `ui/search` — Katalogsuche und Objektblatt

Dieses Paket enthält die Katalogsuche (`ObjectSearchScreen`, Route `search`, vom Sucher aus geöffnet), das Filter-Sheet dazu (`CatalogFilterSheet`) und das Vollbild-Objektblatt (`ObjectDetailScreen`, Route `objects/{objectId}`). Das Objektblatt ist nicht nur von der Suche aus erreichbar, sondern auch vom Sucher (verfolgtes Objekt), vom Hub, vom Foto-Guide und vom Kalender (DEV_PLAN S1 schlägt dafür ein eigenes Paket vor). Ohne Eingabe und ohne Filter zeigt die Suche ein „Heute Nacht“-Brett (`TonightBoard`), sonst eine Trefferliste.

**Benutzt:** `data/catalog/CatalogRepository`, `ObjectNotesRepository`, `data/favorites/FavoritesRepository`, `data/planning/WatchlistRepository`, `WatchScheduler`, `data/tracking/TrackingStore` (`TrackedObject`), `data/windows/SettingsStore`, `SkyWindowRepository`, `core/sensors/LocationTracker`, `domain/ObjectSearch` (`ObjectHit`, `ObjectSort`), `CatalogFilter` (`FilterField`), `TonightBoard`, `SkyConditions`, `BortleScale`, `PhotographicInterest`, `TransitCalculator`, `ObjectDescription`, `SkyTrackBuilder`, `ui/windows` (`ObjectInfo`, `ObjectInfoContent`, `ObjectInfoTitle`, `formatClock`), `ui/components` (`ObjectRow`, `SectionCard`, `SectionHeader`, `ObjectSymbol`).

**Wird benutzt von:** `ui/nav/StarWindowNavHost`.

#### [`ObjectSearchViewModel.kt`](app/src/main/java/com/starwindow/app/ui/search/ObjectSearchViewModel.kt)

Steuert Zeitpunkt und Verdrahtung der Suche über den ganzen Katalog (rund 22.500 Einträge). Die Rangfolge selbst berechnet `ObjectSearch`, das Brett `TonightBoard`, das Urteil „machbar“ `Feasibility`.

| Element | Art | Aufgabe |
|---|---|---|
| `ObjectSearchUiState` | data class | Zustand, siehe unten |
| `ObjectSearchViewModel` | class | Bekommt `catalogRepository`, `locationTracker`, `settingsStore`, `trackingStore`, `windowRepository`, `transitCalculator`, `favoritesRepository` |
| `setQuery(q)` / `clearQuery()` | fun | Setzt `query`, dann `recompute(debounce = true)` |
| `setSort(sort)` | fun | `recompute(debounce = false)` |
| `setFilter(filter)` | fun | Leert den Fenster-Cache, wenn sich `windowId` ändert; dann `recompute(false)` |
| `clearFilterField(field)` / `clearAllFilters()` | fun | `filter.without(field)` bzw. `CatalogFilter.NONE` |
| `openFilterSheet()` / `closeFilterSheet()` | fun | Schaltet `filterSheetOpen` um |
| `recompute(debounce)` | private fun | Kern der Suche, siehe unten |
| `buildBoard(state)` | private suspend fun | `TonightBoard.build(catalog, observer, conditions, inWindowNow)` auf `Dispatchers.Default` |
| `objectsInsideWindowNow(state)` | private suspend fun | Für den Abschnitt „Jetzt im Fenster“ |
| `transitsForSelectedWindow(state)` | private suspend fun | Für den Filter „durchs Fenster“, mit Cache |
| `resolveObserver()` | private fun | `settingsStore.current.manualLocation ?: locationTracker.lastKnown()` |
| `resolveConditions()` | private fun | `SkyConditions.at(observer, BortleScale.resolve(weatherPlace), userSetBortle)` |
| `DEBOUNCE_MILLIS = 180`, `WINDOW_LOOKAHEAD_MILLIS = 12 h` | const | Verzögerung beim Tippen bzw. Vorausschau der Fensterrechnung |

**`ObjectSearchUiState`, Felder:** `query`, `sort` (`ObjectSort`, Vorgabe `RELEVANCE`), `filter` (`CatalogFilter`), `hits` (`List<ObjectHit>`), `board` (`List<TonightGroup>`), `conditions` (`SkyConditions`: Bortle, Mond), `observer`, `catalogSize`, `isLoading`, `isComputingWindow`, `trackedId` (ID des aktuellen Ziels im `TrackingStore`), `windows`, `filterSheetOpen`, `constellations` (Sternbild-Kürzel aus dem Katalog, sortiert), `favorites` (Set von IDs).
**Abgeleitete Getter:** `hasQuery`; `showsBoard` = keine Eingabe **und** `filter.isEmpty`; `selectedWindow` = Fenster mit `filter.windowId`, sonst das erste der Liste; `isEmpty`.

**Funktionsweise – Datenquellen (alle in `init`, je eine eigene Coroutine im `viewModelScope`):**
1. `catalogRepository.objects()` füllt das private Feld `catalog`, `catalogSize`, `observer`, `conditions` und `constellations` und setzt `isLoading = false`. Danach `recompute(false)`.
2. `windowRepository.load()`.
3. `favoritesRepository.favorites.collect`: aktualisiert `favorites`; ist der Favoritenfilter an, wird neu gerechnet.
4. `windowRepository.windows.collect`: aktualisiert `windows`. Gibt es keine Fenster mehr, wird `onlyThroughWindow` abgeschaltet.
5. `trackingStore.target.collect`: aktualisiert `trackedId`.
6. `locationTracker.locations().collect`: Bei jeder neuen Position werden `observer` und `conditions` neu bestimmt und `recompute(false)` aufgerufen. Höhenspalte, Brett und Machbarkeit hängen alle vom Standort ab.

Es gibt kein `combine`/`stateIn`; der Zustand wird über `MutableStateFlow.update` zusammengeführt.

**Funktionsweise – Suchablauf (`recompute`):**
1. Ein laufender `searchJob` wird abgebrochen und ein neuer gestartet. Beim Tippen wartet er zuerst 180 ms (`delay`); jeder weitere Tastendruck bricht ab und beginnt neu, das ist der Debounce.
2. Bei `showsBoard` wird `buildBoard` aufgerufen. Dafür prüft `objectsInsideWindowNow` zuerst billig, welche Fotoziele (`PhotographicInterest.isPhotoTarget`) *jetzt* im `selectedWindow` stehen (`transitCalculator.objectsInsideAt`). Nur für diese läuft dann eine 12-h-Durchgangsrechnung, um die Restminuten zu bestimmen.
3. Sonst gilt: Bei `onlyThroughWindow` wird `isComputingWindow = true` gesetzt und `transitsForSelectedWindow` aufgerufen. Das ist eine 12-h-`transitCalculator.search` über alle Fotoziele, gecacht in `windowTransits` als Paar aus Fenster-ID und Map ID → Restminuten.
4. Auf `Dispatchers.Default` laufen dann nacheinander:
   - Kandidaten = Katalog, eingeschränkt auf Fensterdurchgänge und (wenn gewählt) Favoriten. Beides wird **vor** der Suche gefiltert,
   - `ObjectSearch.search(query, candidates, observer, sort = sort, conditions = conditions)`, liefert höchstens 200 Treffer (`limit`-Vorgabe),
   - danach `filter.matches(obj, altitudeDeg, conditions, favorites)` für Art, Helligkeit, Größe, Sternbild, Mindesthöhe und Machbarkeit,
   - zuletzt wird `minutesLeftInWindow` an die Treffer geschrieben.
5. `hits` setzen, `isComputingWindow = false`.

**Ändern/Fallstricke:**
- Die Filter aus `CatalogFilter.matches` greifen erst **nach** der Begrenzung auf 200 Treffer. Bei leerer Eingabe und z. B. nur einem Sternbild-Filter enthält die Liste also nur die Objekte dieses Sternbilds, die unter den ersten 200 der gewählten Sortierung sind. Für Favoriten wurde genau dieses Problem schon behoben (Kommentar im Code), für die übrigen Filter nicht.
- Der Fenster-Cache wird nur bei einem Wechsel der `windowId` geleert, nicht mit der Zeit. Die Restminuten veralten also, solange das ViewModel lebt.
- `minutesLeftInWindow` wird an die Treffer geschrieben, aber `ResultList` im Screen zeigt es nicht an; nur das Brett nutzt die Restminuten (über `TonightEntry`).

#### [`ObjectSearchScreen.kt`](app/src/main/java/com/starwindow/app/ui/search/ObjectSearchScreen.kt)

Die Suchseite mit zwei Betriebsarten: das Brett „Heute Nacht“ oder die Trefferliste.

| Element | Art | Aufgabe |
|---|---|---|
| `ObjectSearchScreen(viewModel, onOpenObject, onBack)` | @Composable | Aufbau: Suchfeld, Filterzeile, Sortier-Chips (nur in der Trefferliste), `ResultHeader`, danach je nach Zustand `WindowSearchRunning` / `EmptyState` / `TonightBoardList` / `ResultList`. Öffnet `CatalogFilterSheet`, wenn `filterSheetOpen` gesetzt ist |
| `SearchField` | private @Composable | Zurück-Pfeil und `OutlinedTextField` („Name oder Katalognummer“), IME-Aktion Search blendet die Tastatur aus |
| `FilterRow` | private @Composable | Chip „Filter · n“ öffnet das Sheet; aktive Filter als `InputChip`s aus `filter.activeLabels` (Tipp entfernt den Filter); ab 2 aktiven Filtern „alle zurücksetzen“ |
| `ResultHeader` / `conditionsSummary` | private | „Heute Nacht“, „n Treffer von N“ usw. Ohne Standort erscheint ein Hinweis, sonst „Bortle x (gesch.) · Mond y %“ |
| `TonightBoardList` | private @Composable | Eine `SectionCard` je `TonightGroup`, mit `ObjectRow`s. Bei `IN_WINDOW` steht der Fenstername im Titel |
| `boardTrailing(entry)` | private @Composable | Rechte Spalte des Bretts: Restminuten im Fenster, Aufgangszeit („ab dann hoch“, `formatClock`) oder nichts |
| `ResultList` | private @Composable | `LazyColumn` mit `ObjectRow` (Höhe, `feasibility`, Markierung, wenn `trackedId` passt) |
| `WindowSearchRunning` / `EmptyState` | private @Composable | Ladeanzeige der Fensterrechnung; passende Leertexte je nach Ursache |

**Zusammenspiel:** Ein Tipp auf eine Zeile blendet die Tastatur aus und ruft `onOpenObject(id)` auf; der NavHost navigiert zu `Routes.objectDetail(id)` (ID mit `Uri.encode`). Ein `LaunchedEffect(showsBoard, board.isEmpty())` gibt dem Suchfeld nur dann den Fokus, wenn das Brett leer ist. Alle Texte stehen inline in den Composables.

#### [`CatalogFilterSheet.kt`](app/src/main/java/com/starwindow/app/ui/search/CatalogFilterSheet.kt)

`ModalBottomSheet` mit allen Filtern. Es gibt keinen „Anwenden“-Knopf: Jede Änderung geht sofort über `onChange(filter.copy(...))` an `ObjectSearchViewModel.setFilter`.

| Element | Art | Aufgabe |
|---|---|---|
| `CatalogFilterSheet(filter, constellations, windows, conditions, favoriteCount, onChange, onDismiss)` | @Composable | Folgende Abschnitte:<br>- Zurücksetzen<br>- „Nur Favoriten“<br>- Art (`ResultFilter` ohne `CONSTELLATIONS`)<br>- Helligkeit (0–14 mag, 14 = keine Grenze)<br>- Mindestgröße (0–60′, unter 1′ = beliebig)<br>- Mindesthöhe jetzt (0–70°, unter 5° = beliebig)<br>- Sternbild<br>- „Nur was heute Nacht machbar ist“ (`hideImpossible`)<br>- „Nur was durchs Fenster zieht“, nur wenn Fenster existieren; bei mehreren Fenstern zusätzlich eine Auswahl |
| `FilterSection`, `ChipRow<T>`, `SliderSection`, `SwitchRow` | private @Composable | Bausteine. `SliderSection` zeigt den Wert in Worten |
| `MAGNITUDE_MIN = 0.0`, `MAGNITUDE_MAX = 14.0` | const | Grenzen des Helligkeitsreglers |

**Ändern/Fallstricke:** Die Slider melden jeden Zwischenwert. Jede Bewegung löst also über `setFilter` ein `recompute` aus; es gibt kein `onValueChangeFinished`. Die Schwellen „bedeutet keine Grenze“ (`< MAGNITUDE_MAX`, `>= 1.0`, `>= 5.0`) stehen in den `onChange`-Lambdas.

#### [`ObjectDetailViewModel.kt`](app/src/main/java/com/starwindow/app/ui/search/ObjectDetailViewModel.kt)

Trägt für das Objektblatt zusammen: Katalogeintrag, Beschreibung, Höhenkurve, Verfolgungs-, Merklisten- und Favoritenstatus.

| Element | Art | Aufgabe |
|---|---|---|
| `ObjectDetailUiState` | data class | `obj`, `info` (`ObjectInfo?`, `null` ohne Standort), `observer`, `isTracked`, `isWatched` (Merkliste), `isFavorite`, `description`, `isLoading`, `error` |
| `ObjectDetailViewModel(objectId, …)` | class | Bekommt `catalogRepository`, `locationTracker`, `settingsStore`, `trackingStore`, `objectNotesRepository`, `watchlistRepository`, `watchScheduler`, `favoritesRepository` |
| `toggleFavorite()` | fun | `favoritesRepository.toggle(objectId)`. Der Zustand kommt über den Flow zurück, nicht direkt aus dem Klick |
| `track()` / `untrack()` | fun | `trackingStore.track(TrackedObject(obj))` / `trackingStore.clear()` |
| `toggleWatch()` | fun | Steht das Objekt auf der Merkliste, wird es entfernt (`removeObject`). Sonst wird zuerst `watchScheduler.ensureChannel()` aufgerufen (Benachrichtigungskanal), dann `watchlistRepository.add(obj)` |
| `buildInfo(obj, observer, description)` | private fun | Ohne Standort `null`. Sonst `ObjectInfo` mit Höhenkurve über 24 h ab jetzt (`CURVE_SPAN_MILLIS`) und aktueller Az/Höhe, ohne `passes` und ohne `fillFactor` |
| `factory(container, objectId)` | companion fun | Factory, im NavHost mit `key = objectId` |

**Funktionsweise:** In `init` laufen vier Coroutinen:
1. Objekt per `catalogRepository.objects().firstOrNull { it.id == objectId }` suchen (lineare Suche), Standort aus `SettingsStore.manualLocation` oder `lastKnown()`, Beschreibung aus `ObjectDescription.describe(obj, objectNotesRepository.noteFor(obj))`, dann `buildInfo`.
2. `trackingStore.target` → `isTracked`.
3. `watchlistRepository.watched` → `isWatched`.
4. `favoritesRepository.favorites` → `isFavorite`.

Die „Notiz“ ist der redaktionelle Text aus den Assets (`ObjectNotesRepository`), keine Notiz des Nutzers. Der Standort wird nur einmal gelesen. Kommt er erst später, bleibt das Blatt ohne Kurve, bis es neu geöffnet wird.

#### [`ObjectDetailScreen.kt`](app/src/main/java/com/starwindow/app/ui/search/ObjectDetailScreen.kt)

Das Vollbild-Objektblatt.

| Element | Art | Aufgabe |
|---|---|---|
| `ObjectDetailScreen(viewModel, onBack, onStartTracking, onOpenPlanning)` | @Composable | Kopfzeile mit `ObjectSymbol`, `ObjectInfoTitle` und Herz (`toggleFavorite`). Inhalt: Ladekreis / Fehler / `ObjectInfoContent` (mit `skyImageLoader` aus `appContainer`) / ohne Standort `NoPositionNotice` und Beschreibungstext. Unten die `TrackBar` |
| `TrackBar` | private @Composable | „Track“/„Im Sucher zeigen“ (+ „Beenden“, wenn verfolgt), „Planung“ und „Bescheid geben“/„Vorgemerkt“; dazu Erklärtexte |
| `NoPositionNotice` | private @Composable | Hinweis, dass ohne Standort keine Position berechnet werden kann |

**Zusammenspiel:**
- „Track“: `viewModel.track()`, dann `onStartTracking()`; der NavHost springt per `popBackStack(Routes.CAPTURE)` zum Sucher.
- „Planung“: `onOpenPlanning()`; der NavHost navigiert zu `Routes.planning(objectId)`.
- „Bescheid geben“: Ab Android 13 wird beim Einschalten zuerst `POST_NOTIFICATIONS` angefragt (`rememberLauncherForActivityResult`), dann `toggleWatch()`. Wird die Berechtigung abgelehnt, bleibt der Eintrag trotzdem auf der Merkliste.
- Für Sonne und Mond (`obj.isMoving`) wird der Merklisten-Knopf ausgeblendet (`canWatch`).

#### Typische Änderungen in diesem Bereich
- **Neues Filterkriterium in der Suche:**
  1. In `domain/CatalogFilter` das Feld anlegen und in `isEmpty`, `activeCount`, `activeLabels`, `matches` und `without` ergänzen.
  2. Einen neuen Wert in `FilterField` anlegen.
  3. Ein Bedienelement in `CatalogFilterSheet` (`SliderSection`/`SwitchRow`/`ChipRow`).
  4. Braucht das Kriterium Daten, die erst eine teure Rechnung liefert (wie das Fenster), gehört es in `ObjectSearchViewModel.recompute` **vor** `ObjectSearch.search`, sonst greift die 200er-Grenze vorher.
- **Neue Sortierung:** Einen Wert in `ObjectSort` anlegen und in `ObjectSearch.sorted` umsetzen. Die Chips entstehen automatisch.
- **Neue Aktion auf dem Objektblatt:** Eine Methode in `ObjectDetailViewModel` (bei Bedarf ein Repository aus `AppContainer` in Konstruktor und `factory` aufnehmen), ein Statusfeld in `ObjectDetailUiState` mit eigener `collect`-Coroutine in `init`, einen Knopf in `TrackBar` oder in der Kopfzeile. Braucht sie Navigation, einen neuen Callback-Parameter in `ObjectDetailScreen` und dessen Belegung im `StarWindowNavHost`.
- **Neue Info auf Objektblatt und Fenster-Infoblatt:** Ein `InfoRow` in `ui/windows/ObjectInfoContent`. Bei einem neuen Feld in `ObjectInfo` beide Erzeuger anpassen (`ObjectDetailViewModel.buildInfo`, `WindowDetailViewModel.openInfo`).
- **Debounce oder Trefferzahl ändern:** `DEBOUNCE_MILLIS` in `ObjectSearchViewModel`; die Trefferzahl über den Parameter `limit` von `ObjectSearch.search` (Vorgabe 200; wird im ViewModel derzeit nicht übergeben).

<a id="pkg-ui-planning"></a>

### `ui/planning` — Kalender, Jahresplanung, Termine und Merkliste

Das Paket enthält zwei Bildschirme und zwei Bottom-Sheets: den Reiter **Kalender** (`CalendarScreen` + `CalendarViewModel`), der alle geplanten Nächte als Jahr, als Liste und die Merkliste zeigt, und die **Jahresplanung** für ein einzelnes Objekt (`PlanningScreen` + `PlanningViewModel`), erreichbar vom Objektblatt über „Planung". Ein Termin (`PlannedSession`) entsteht ausschließlich in der Jahresplanung; bearbeitet (Erinnerung, Notiz, Pfad, Löschen) wird er im `SessionSheet`. Merklisten-Einträge (`WatchedObject`) entstehen im Objektblatt („Bescheid geben", `ObjectDetailViewModel` → `WatchlistRepository.add`) und werden im `WatchSheet` nachgeschärft. Außerdem liegt hier `PlanningLocation`, die gemeinsame Ortsauflösung, die auch Hub und Guide verwenden (keine Oberfläche, gehört fachlich eher nach `data`/`domain`, siehe DEV_PLAN §11.4 S1).

**Benutzt:** `data/planning` (`PlanRepository`, `PlannedSession`, `ReminderLead`, `WatchlistRepository`, `WatchedObject`, `WeatherDemand`, `WatchScheduler`), `data/weather` (`WeatherRepository`, `WeatherPlace`), `data/windows/SettingsStore`, `core/sensors/LocationTracker`, `data/catalog/CatalogRepository`, `domain` (`ObservationPlanner`, `ObservationNight`, `NightOutlook`, `NightOutlooks`, `NightVerdict`, `DarknessQuality`), `ui/components/ScreenHeader`, `ui/windows/formatClock`, `ui/theme`. **Wird benutzt von:** `ui/nav/StarWindowNavHost` (Routen `calendar` und `planning/{objectId}`), `ui/hub/StargazingHubViewModel` und `ui/guide/PhotoGuideViewModel` (nur `PlanningLocation`).

#### [`PlanningLocation.kt`](app/src/main/java/com/starwindow/app/ui/planning/PlanningLocation.kt)

Bestimmt, **für welchen Ort und in welcher Zeitzone** geplant wird. Reine Hilfsklasse ohne UI.

| Element | Art | Aufgabe |
|---|---|---|
| `PlanningLocation` | data class | `place: WeatherPlace?`, `observer: ObserverLocation?`, `zone: ZoneId` |
| `hasLocation` | val | `observer != null` |
| `label` | val | `place.label` oder `"aktueller Standort"` – erscheint in Kopfzeilen von Planung, Hub, Guide |
| `PlanningLocation.resolve(settings, locationTracker)` | fun (companion) | Löst den Ort auf |

**Funktionsweise:** Reihenfolge in `resolve`: 1. `settings.current.weatherPlace` (der in der Wetteransicht gewählte Ort, „wohin fahre ich"), 2. `settings.current.manualLocation` (von Hand eingetragener Standort), 3. `locationTracker.lastKnown()` (letzte GPS-Position). Eine Ortssuche gibt es hier nicht – die passiert in der Wetteransicht (`PlaceLookup`). Die Zeitzone kommt aus `place.zone`, sonst `ZoneId.systemDefault()`. Die Funktion ist synchron und liest nur den aktuellen Wert, sie beobachtet nichts.

**Zusammenspiel:** Aufgerufen von `CalendarViewModel` (Wetter und Merkliste), `PlanningViewModel`, `StargazingHubViewModel`, `PhotoGuideViewModel`.

**Ändern/Fallstricke:** Der Kommentar nennt auch „die nächtliche Prüfung der Merkliste" als Nutzer – tatsächlich verwendet `WatchCheckReceiver` aber `NightWeatherSource.place` (nur Wetterort, kein GPS-Fallback), und `PlannedPath.track` dupliziert die Auflösung von Hand. Wer die Reihenfolge ändert, muss diese beiden Stellen mitziehen. Ohne gewählten Wetterort rechnet die Planung mit der Gerätezeitzone.

#### [`CalendarViewModel.kt`](app/src/main/java/com/starwindow/app/ui/planning/CalendarViewModel.kt)

ViewModel des Kalender-Reiters. Liefert Monatsraster, kommende/vergangene Termine, Wetterurteile für die geplanten Nächte und die Merkliste mit der jeweils nächsten astronomisch passenden Nacht.

| Element | Art | Aufgabe |
|---|---|---|
| `CalendarMonth` | data class | Ein Monat (`yearMonth`) mit `sessionsByDay: Map<Int, List<PlannedSession>>`, `total` |
| `WatchEntry` | data class | `WatchedObject` + `nextNight: LocalDate?` + `nextUsableHours`; `hasOpportunity` |
| `CalendarUiState` | data class | Gesamter Bildschirmzustand (s. u.) |
| `CalendarViewModel` | class | Lädt, kombiniert, bietet Aktionen |
| `MONTHS_SHOWN` | const | `12` Monate ab dem laufenden |
| `WATCH_LOOKAHEAD_DAYS` | const | `120` Tage Vorschau für die Merkliste |
| `factory(container)` | fun | ViewModel-Factory aus `AppContainer` |

**`CalendarUiState`:** `months` (immer 12, auch ohne Termine), `upcoming` (Datum ≥ heute, früheste zuerst), `past` (vor heute, neueste zuerst), `selectedDate` (angetippter Tag → Popup), `openSession` / `openWatch` (welches Sheet offen ist, `null` = keins), `today`, `zone` (immer `ZoneId.systemDefault()`, wird nie gesetzt), `isLoading`, `outlooks: Map<Long, NightOutlook>` (Schlüssel = Epochentag), `isWeatherLoading`, `weatherNote` (Grund, warum kein Wetter dasteht), `watchlist: List<WatchEntry>`. Abgeleitet: `isEmpty`, `next` (erster kommender Termin), `daysUntilNext`, `outlookFor(session|date)`.

**Funktionsweise:** Kein `combine`/`stateIn` – das ViewModel hält ein `MutableStateFlow<CalendarUiState>` und startet in `init` drei Coroutinen in `viewModelScope`:
1. `planRepository.load()` + `watchlistRepository.load()` (Datei lesen, IO), danach `planRepository.rescheduleAll()` (alle Alarme neu setzen – idempotent, repariert verlorene Alarme nach Reboot/Datenlöschung) und, falls die Merkliste nicht leer ist, `watchScheduler.scheduleDailyCheck()` (Kette der täglichen 15‑Uhr-Prüfung wieder anknüpfen).
2. `planRepository.sessions.collect` → `rebuild(sessions)` (Monate, upcoming/past, `openSession` auf die gespeicherte Version aktualisieren) **und** `loadWeather(sessions)`.
3. `watchlistRepository.watched.collect` → `rebuildWatchlist(watched)`.

`loadWeather` holt **einen** Vorhersagelauf (`weatherRepository.forecast(place, settings.weatherModel)`) für den `PlanningLocation`-Ort und rechnet mit `NightOutlooks.forDates(...)` auf `Dispatchers.Default` das Urteil für alle kommenden Termindaten. Termine jenseits der Vorhersagereichweite bekommen einfach keinen Eintrag. Ohne `place` (nur GPS reicht hier nicht, es braucht einen Wetterort) bzw. bei Fehler wird `weatherNote` gesetzt. Gespeicherte Termin-Zahlen (Stunden, Höhe, Mond) werden bewusst **nicht** neu gerechnet; das Wetter legt sich nur daneben.

`rebuildWatchlist` rechnet für jeden Eintrag auf `Dispatchers.Default` bis zu 120 Nächte mit `ObservationPlanner.nightFor(position, observer, zone, date, minAltitudeDeg)` durch und nimmt die erste mit `usableMillis >= watch.minUsableMillis` – ohne Wetter. Ohne Ort gibt es Einträge mit `nextNight = null`.

**Aktionen:** `selectDate(date)` (toggelt Auswahl), `openSession(session)` (legt zuvor per `planRepository.ensureNotificationChannel()` den Benachrichtigungskanal an), `closeSession()`, `toggleReminder(lead)` → `planRepository.toggleReminder` (schreibt Datei und plant über `ReminderScheduler.reschedule` neu), `setNote(note)` → `planRepository.setNote` (plant ebenfalls neu, weil die Notiz in der Meldung steht), `remove(session)` → `planRepository.remove` (storniert Alarme), `openWatch(watch)` (legt per `watchScheduler.ensureChannel()` den Kanal an), `closeWatch()`, `updateWatch(transform)` → `watchlistRepository.update`, `removeWatch(watch)` → `watchlistRepository.remove`.

**Zusammenspiel:** Erzeugt in `StarWindowNavHost` (Route `calendar`). Schreibt nur über die Repositories; alle UI-Änderungen kommen über deren `StateFlow`s zurück.

**Ändern/Fallstricke:** Jede Änderung an `sessions` (auch Notiz oder Erinnerung) löst `loadWeather` und damit einen neuen Vorhersageabruf aus; ältere `loadWeather`-Coroutinen werden nicht abgebrochen. Die Merkliste wird nur bei Änderung der Merkliste neu gerechnet, nicht bei Ortswechsel. `isWeatherLoading` wird gesetzt, aber vom Screen nicht ausgewertet. Erinnerungszeiten (`ReminderScheduler`) rechnen mit der Gerätezeitzone, nicht mit `PlanningLocation.zone`.

#### [`CalendarScreen.kt`](app/src/main/java/com/starwindow/app/ui/planning/CalendarScreen.kt)

Der Kalender-Reiter (910 Zeilen, laut DEV_PLAN §11.4 S6 ein Kandidat zum Aufteilen). Eine `LazyColumn` mit festen Abschnitten in dieser Reihenfolge: **Nächster Termin** (Karte) → Leerhinweis → **12 Monatsraster** → **„Als Nächstes"** → **„Merkliste"** → **„Vorbei"**.

| Element | Art | Aufgabe |
|---|---|---|
| `CalendarScreen(viewModel, onOpenObject, onShowPath, modifier)` | @Composable | Einstieg; hält Berechtigungszustand, öffnet Sheets, baut die Liste |
| `NextSessionCard` | @Composable (privat) | Countdown, Objekt, Wetterzeile oder `weatherNote`, Notiz, nächste Erinnerung, Knopf „Pfad" |
| `EmptyHint` | @Composable (privat) | Erklärtext, wenn nichts geplant und nichts vorgemerkt ist |
| `MonthGrid` | @Composable (privat) | Ein Monat als 7‑Spalten-Raster (Wochenbeginn Montag) |
| `DayCell` | @Composable (privat) | Tageskachel; Zahl-Badge mit Anzahl Termine; nur antippbar bei Terminen oder heute |
| `DayPopup` + `DayPopupPositionProvider` | @Composable / class (privat) | Popup an der Kachel mit allen Terminen des Abends; Position unter/über der Kachel, am Rand geklemmt |
| `PopupSessionRow`, `SessionRow`, `WatchRow` | @Composable (privat) | Zeilen für Popup, Terminlisten, Merkliste (je mit Papierkorb) |
| `ForecastLine`, `verdictColor` | @Composable / fun (privat) | Wetterurteil als Punkt + Text (`NightOutlook.headline`), Farbe je `NightVerdict` |
| `watchStatus`, `countdown` | fun (privat) | Texte „ab …, 3,1 h über 30°" bzw. „Heute Nacht", „In 2 Wochen" … |
| `hasNotificationPermission` | fun (privat) | `POST_NOTIFICATIONS`-Prüfung, unter Android 13 immer `true` |

**Funktionsweise:** Zustand per `collectAsStateWithLifecycle()`. Lokaler Compose-Zustand `notificationsAllowed` plus `rememberLauncherForActivityResult(RequestPermission)`. Die Berechtigung `POST_NOTIFICATIONS` wird angefragt (a) beim Einschalten einer Erinnerung im `SessionSheet`, wenn sie fehlt – die Erinnerung wird trotzdem gespeichert, (b) über den Knopf „Freigeben" im `WatchSheet`. Exakte Alarme werden nicht angefragt (die Scheduler verwenden `setAndAllowWhileIdle`). Sheets werden einfach dann komponiert, wenn `state.openSession` bzw. `state.openWatch` nicht `null` ist. Eine Nacht wird überall als „Nacht auf ‹Datum + 1›" beschriftet (eine Nacht gehört zum Abend, an dem sie beginnt). Vergangene Termine öffnen beim Antippen das Objektblatt (`onOpenObject`), nicht das Sheet, und zeigen kein Wetter.

**Zusammenspiel:** Callbacks aus dem NavHost: `onOpenObject(objectId)` → Objektblatt; `onShowPath(session)` → `PlannedPath.track(container, session)` und Rücksprung zum Sucher (`Routes.CAPTURE`). Alle anderen Aktionen gehen an das ViewModel. Alle Texte (Überschriften „Als Nächstes", „Merkliste", „Vorbei", Hinweise) stehen direkt im Code, keine `strings.xml`.

**Ändern/Fallstricke:** Listen nutzen `items(size)` ohne `key`/`contentType` (DEV_PLAN P5). Das Datumsformat `dateFormat` ist pro Datei privat definiert (in `SessionSheet`/`WatchSheet` jeweils anders). Das Popup hängt bewusst **in** der Kachel, weil sich ein `Popup` an den Grenzen seines Eltern-Elements ausrichtet.

#### [`SessionSheet.kt`](app/src/main/java/com/starwindow/app/ui/planning/SessionSheet.kt)

`ModalBottomSheet` für einen geplanten Termin: Kopf mit gespeicherten Zahlen, Wetterkasten, „Pfad zeigen", Erinnerungsschalter, Notiz, Fertig/Löschen.

| Element | Art | Aufgabe |
|---|---|---|
| `SessionSheet(session, zone, outlook, notificationsAllowed, onToggleReminder, onNoteChange, onShowPath, onRemove, onDismiss)` | @Composable | Das Sheet |
| `ReminderRow` | @Composable (privat) | Ein Schalter pro `ReminderLead`; deaktiviert, wenn der Zeitpunkt vorbei ist |

**Funktionsweise:** Erinnerungen: für jedes `ReminderLead.ordered` (1 Woche, 3 Tage, 1 Tag, am Tag selbst) ein `Switch`; Uhrzeit ist immer `ReminderLead.NOTIFY_TIME` (17:00). `inThePast` = `lead.triggerMillis(session.date, zone) <= now`. Bei fehlender Berechtigung und vorhandenen Erinnerungen erscheint ein roter Hinweis. Die Notiz wird lokal (`remember(session.id)`) gehalten und erst beim Schließen (Wischen oder „Fertig") per `onNoteChange` gespeichert, wenn sie sich geändert hat – sonst würde jede Taste Datei schreiben und Alarme neu setzen. Bei ungünstigem Wetter (`outlook.isDiscouraging`) der Hinweis, dass Erinnerungen trotzdem kommen.

**Zusammenspiel:** Nur von `CalendarScreen` benutzt; Callbacks gehen an `CalendarViewModel.toggleReminder`/`setNote`/`remove`/`closeSession` bzw. an `onShowPath` des NavHosts.

**Ändern/Fallstricke:** „Löschen" speichert eine ungesicherte Notiz nicht mehr (unkritisch, Termin ist weg). Ein neues Feld braucht: Feld in `PlannedSession`, Setter in `PlanRepository`, Methode in `CalendarViewModel`, Callback hier.

#### [`WatchSheet.kt`](app/src/main/java/com/starwindow/app/ui/planning/WatchSheet.kt)

`ModalBottomSheet` für einen Merklisten-Eintrag: zeigt die nächste astronomisch passende Nacht und erlaubt drei Kriterien.

| Element | Art | Aufgabe |
|---|---|---|
| `WatchSheet(watch, entry, notificationsAllowed, onRequestPermission, onChange, onOpenObject, onRemove, onDismiss)` | @Composable | Das Sheet |
| `ChoiceRow` | @Composable (privat) | Reihe von `FilterChip`s für feste Werte |
| `ALTITUDES` | val (privat) | `25°, 30°, 40°` → `minAltitudeDeg` |
| `DURATIONS` | val (privat) | `0,5 / 1 / 2 h` → `minUsableHours` |

**Funktionsweise:** Kriterien: **Mindesthöhe**, **Mindestdauer**, **Wetter** (`WeatherDemand.entries`, z. B. nur gut / auch teils). Jede Auswahl ruft sofort `onChange { it.copy(...) }` → `CalendarViewModel.updateWatch` → `WatchlistRepository.update`; die neue Merkliste löst `rebuildWatchlist` aus, `entry` wird neu berechnet. Angezeigt werden außerdem `WatchScheduler.CHECK_TIME` (15:00) und `watch.quietNights` (Ruhepause nach einer Meldung, nicht einstellbar). Werte, die nicht in `ALTITUDES`/`DURATIONS` stehen, führen zu keinem markierten Chip.

**Zusammenspiel:** Nur von `CalendarScreen`. Die eigentliche Prüfung läuft später in `WatchCheckReceiver` → `WatchEvaluator`.

**Ändern/Fallstricke:** `WatchedObject.enabled` existiert, ist hier aber nicht bedienbar. Neue Chip-Werte nur in `ALTITUDES`/`DURATIONS` ergänzen.

#### [`PlanningViewModel.kt`](app/src/main/java/com/starwindow/app/ui/planning/PlanningViewModel.kt)

ViewModel der Jahresplanung für **ein** Objekt (`objectId` aus der Route).

| Element | Art | Aufgabe |
|---|---|---|
| `PlanningUiState` | data class | `obj`, `nights` (365 `ObservationNight`), `bestNights`, `season: ClosedRange<LocalDate>?`, `isYearRound`, `placeLabel`, `observer`, `zone`, `plannedDates: Set<Long>` (Epochentage), `isLoading`, `error`; abgeleitet `hasUsableNights`, `peak` |
| `PlanningViewModel` | class | Rechnet einmalig, bietet `togglePlan` |
| `togglePlan(night)` | fun | Nacht in den Kalender legen oder wieder herausnehmen |
| `factory(container, objectId)` | fun | Factory |

**Funktionsweise:** `init` startet zwei Coroutinen. (1) `planRepository.load()`, Objekt aus `catalogRepository.objects()` suchen (sonst `error = "Objekt nicht gefunden"`), Ort über `PlanningLocation.resolve` (ohne Ort `error`), dann einmalig auf `Dispatchers.Default` `ObservationPlanner.plan(obj, observer, zone, from = heute)` und daraus `bestNights`, `season`, `isYearRound`. Keine Neuberechnung bei Ortswechsel. (2) `planRepository.sessions.collect` → `plannedDates = planRepository.datesFor(objectId)`.
`togglePlan`: ist die Nacht schon geplant → `planRepository.removeFor(objId, date)`, sonst `planRepository.add(PlannedSession(...))` mit `usableMinutes`, `bestAltitudeDeg`, `moonIlluminationPercent` aus der Nacht. Neue Termine haben **keine** Erinnerungen (`reminders` ist leer) – die werden erst im Kalender eingeschaltet.

**Zusammenspiel:** Route `planning/{objectId}` (ViewModel mit `key = objectId`).

**Ändern/Fallstricke:** Was hier in `PlannedSession` geschrieben wird, zeigt der Kalender später unverändert an. Wer ein neues gespeichertes Feld will, setzt es hier.

#### [`PlanningScreen.kt`](app/src/main/java/com/starwindow/app/ui/planning/PlanningScreen.kt)

Darstellung der Jahresplanung: Kopf mit Zurück, Ortszeile, Jahresdiagramm, Saison-Zusammenfassung, Liste „Beste Nächte".

| Element | Art | Aufgabe |
|---|---|---|
| `PlanningScreen(viewModel, onBack, modifier)` | @Composable | Lade-/Fehler-/Ergebniszustand |
| `YearChart` | @Composable (privat) | `Canvas` mit einem Balken pro Nacht (belichtbare Stunden), Gitter alle 2 h, Monatskürzel |
| `barColor` | fun (privat) | Grau = nur nautische Dämmerung, Bernstein = Mond stört, Grün = mondfrei |
| `SeasonSummary` | @Composable (privat) | „Ganzjährig" oder „Saison: … bis …", beste Nacht (`peak`), Erklärung mit `ObservationPlanner.MIN_USEFUL_ALTITUDE_DEG` |
| `NightRow` | @Composable (privat) | Eine Kandidatennacht; Antippen → `viewModel.togglePlan(night)` |
| `PlaceLine`, `Box_Centered`, `monthLabels` | privat | Ortszeile, Zentrier-Hilfe, 12 Monatskürzel |

**Ändern/Fallstricke:** `PlaceLine` schreibt „Zeiten in ‹zone.id›", die Uhrzeit in `NightRow` kommt aber aus `formatClock` (Gerätezeitzone). Die Saison-Logik selbst steckt in `ObservationPlanner.season`, nicht hier.

#### Typische Änderungen in diesem Bereich
- **Neuen Abschnitt im Kalender:** Daten in `CalendarUiState` ergänzen und in `CalendarViewModel` befüllen (passenden Flow in `init` sammeln); in `CalendarScreen` an der gewünschten Stelle der `LazyColumn` ein `item { Überschrift }` + `items(...)` einfügen (dabei gleich `key` setzen); Untertitel im `ScreenHeader` ggf. anpassen; `CalendarUiState.isEmpty` erweitern.
- **Neues Feld im Termin-Sheet:** Feld in `PlannedSession` (serialisierbar, mit Default für alte Dateien) → Setter in `PlanRepository` (schreibt Datei; ggf. `scheduler.reschedule`, falls es in die Meldung soll, dann auch `ReminderNotification`) → Methode in `CalendarViewModel` (über `openSession`-ID) → Parameter/Callback in `SessionSheet` → Verdrahtung in `CalendarScreen`.
- **Weiteres Merklisten-Kriterium:** Feld in `WatchedObject` → Auswertung in `domain/WatchEvaluator` (und ggf. in `CalendarViewModel.rebuildWatchlist`, wenn es astronomisch ist) → neue `ChoiceRow` in `WatchSheet` mit `onChange { it.copy(...) }`.
- **Weitere Erinnerungsvorlaufzeit:** Neuer Wert in `ReminderLead` und in `ReminderLead.ordered`; das Sheet zeigt ihn automatisch.
- **Planungsort ändern:** nur `PlanningLocation.resolve`; zusätzlich `PlannedPath.track` und `WatchCheckReceiver` prüfen, die eigene Auflösungen haben.

<a id="pkg-ui-hub"></a>

### `ui/hub` — Motive (Stargazing Hub)

Der Reiter **Motive** zeigt, was an diesem Ort in einer Jahreszeit gut zu fotografieren ist: ein Hauptmotiv als großes Bild, eine Bestenliste und Reihen je Objektart. Die gesamte Auswahl und Bewertung macht `domain/SeasonalHighlights`; das Paket wählt nur Jahreszeit und Ort, rechnet im Hintergrund und zeigt die Bilder über den `SkyImageLoader` an.

**Benutzt:** `domain` (`SeasonalHighlights`, `SeasonalTarget`, `Season`, `TargetKind`), `data/catalog` (`CatalogRepository`, `ObjectNotesRepository`), `data/windows/SettingsStore`, `core/sensors/LocationTracker`, `ui/planning/PlanningLocation`, `data/images/SkyImageLoader` (über `context.appContainer`), `ui/components` (`ScreenHeader`, `SectionHeader`, `ObjectSymbol`, `rememberSkyImage`, `SKY_IMAGE_CREDIT`). **Wird benutzt von:** `StarWindowNavHost` (Route `hub`).

#### [`StargazingHubViewModel.kt`](app/src/main/java/com/starwindow/app/ui/hub/StargazingHubViewModel.kt)

| Element | Art | Aufgabe |
|---|---|---|
| `StargazingHubUiState` | data class | Zustand (s. u.) |
| `StargazingHubViewModel` | class | Rechnet `SeasonalHighlights` für Jahreszeit + Ort |
| `selectSeason(season)` | fun | Jahreszeit wechseln, neu rechnen |
| `factory(container)` | fun | Factory |

**`StargazingHubUiState`:** `season` (gewählt), `currentSeason` (tatsächlich laufend), `date` (Referenznacht der Rechnung), `placeLabel`, `targets` (max. 24, beste zuerst), `rows: List<Pair<TargetKind, List<SeasonalTarget>>>`, `notes: Map<objectId, String>` (Kurzbeschreibungen), `isLoading`, `hasLocation`. Abgeleitet: `hero` (= `targets[0]`), `topPicks` (Plätze 2–9), `isEmpty`, `isCurrent`, `monthLabel`.

**Funktionsweise:** `init` bestimmt über `PlanningLocation` die laufende Jahreszeit (`Season.of(heute, Breite)`) und ruft `load`. Zusätzlich wird `settingsStore.settings.map { it.weatherPlace }.distinctUntilChanged().drop(1)` gesammelt: Ändert sich der Wetterort (das ViewModel überlebt den Reiterwechsel), wird für die aktuelle Jahreszeit neu gerechnet. `load(season)` bricht einen laufenden `job` ab und startet neu: Ort auflösen; ohne Ort `hasLocation = false`; sonst `SeasonalHighlights.referenceDate(season, today, lat)` (heute bei laufender Jahreszeit, sonst Mitte des nächsten Auftretens), dann auf `Dispatchers.Default` `SeasonalHighlights.build(objects, observer, zone, date)`. Danach `notesRepository.noteFor(obj)` je Ziel (suspend) und `SeasonalHighlights.rows(targets)`. Die Bewertung (in `SeasonalHighlights.build`): Mindesthöhe 20°, mind. 45 min nutzbar, `score = 0,40·Nacht-Score + 0,35·Popularität + 0,25·PhotographicInterest`, Duplikate (< 0,75°) entfernt.

**Zusammenspiel:** Liest den Katalog und die Objektnotizen; schreibt nichts.

**Ändern/Fallstricke:** Eine Rechnung dauert laut Kommentar knapp eine Sekunde. Da der Collector auf den ganzen `WeatherPlace` reagiert, löst auch das Setzen von `bortleOverride` im Guide (das den `WeatherPlace` kopiert) eine Neuberechnung aus. `TonightBoard` wird hier **nicht** verwendet (das nutzt die Objektsuche).

#### [`StargazingHubScreen.kt`](app/src/main/java/com/starwindow/app/ui/hub/StargazingHubScreen.kt)

| Element | Art | Aufgabe |
|---|---|---|
| `StargazingHubScreen(viewModel, onOpenObject, modifier)` | @Composable | Kopfzeile, Jahreszeit-Reiter, `LazyColumn` |
| `SeasonTabs` | @Composable (privat) | 4 `FilterChip`s, laufende mit „· jetzt" |
| `SeasonStory` | @Composable (privat) | `season.subtitle` + `season.story` (Texte im Enum `Season` in `domain/SeasonalHighlights.kt`) |
| `HeroCard` | @Composable (privat) | Großes Bild (768 px), Badge, Name, `target.headline`, Notiz |
| `TargetRow` / `TargetCard` | @Composable (privat) | Horizontale Reihe (`LazyRow`, `key = obj.id`) mit Karten (Bild 384 px, Stunden, Höhe, Rang, „Bestzeit"-Badge bei `isAtItsBest`) |
| `Badge`, `LoadingBlock`, `NoLocationHint`, `NoNightHint`, `HintBlock`, `CreditLine` | @Composable (privat) | Kleinteile, Hinweise, Bildnachweis |
| `kindSubtitle` | fun (privat) | Ein-/Mehrzahl-Untertitel je `TargetKind` |

**Funktionsweise:** Während `isLoading` wird nur `SeasonStory` + Ladehinweis gezeigt (`return@LazyColumn`). Danach: Hauptmotiv, „Am besten …" (`topPicks`, Rang ab 2), Reihen je Art (`items(state.rows, key = kind.name)`), Bildnachweis. Jede Karte ruft `onOpenObject(obj.id)` → Objektblatt. Bilder lädt `rememberSkyImage(obj, loader, sizePx, widen)`.

**Ändern/Fallstricke:** Ohne Ort ist zugleich `isEmpty` wahr, sodass `NoLocationHint` **und** `NoNightHint` erscheinen. Texte stehen direkt im Code bzw. im Enum `Season`/`TargetKind`.

#### Typische Änderungen in diesem Bereich
- **Weiteres Motiv-Kriterium (Gewichtung):** in `domain/SeasonalHighlights.build` die Score-Formel bzw. `PhotographicInterest`/`Popularity` ändern; der Hub übernimmt das ohne UI-Änderung. Soll das Kriterium auf der Karte sichtbar sein: Feld in `SeasonalTarget`, Anzeige in `TargetCard`/`HeroCard`.
- **Mehr/weniger Motive:** `limit` in `SeasonalHighlights.build` (Standard 24) und `topPicks` (`drop(1).take(8)`) in `StargazingHubUiState`.
- **Neue Reihe (z. B. „Für Handykameras"):** Liste in `StargazingHubUiState` ergänzen, in `load` befüllen, in `StargazingHubScreen` als weiteres `item { TargetRow(...) }` einhängen.

<a id="pkg-ui-guide"></a>

### `ui/guide` — Fotoguide

Der Reiter **Guide** gibt für ein Motiv, ein Smart-Teleskop und eine Bortle-Stufe konkrete Aufnahmeempfehlungen für die heutige Nacht: Urteil, LP-Filter ja/nein, Einzelbelichtung und Gesamtzeit, Bildausschnitt/Mosaik, Taurisiko. Die Rechnung macht `domain/PhotoGuide.plan`; das ViewModel sammelt die Eingaben (Nacht aus `ObservationPlanner`, Mond, Taupunkt aus der zwischengespeicherten Vorhersage) und speichert die Auswahl in den Einstellungen.

**Benutzt:** `domain` (`PhotoGuide`, `PhotoPlan`, `GuideVerdict`, `FramingVerdict`, `DewRisk`, `BortleScale`, `ObservationPlanner`, `ObjectSearch`, `PhotographicInterest`, `Season`), `data/gear` (`SmartTelescope`, `SmartTelescopes`), `data/catalog`, `data/favorites/FavoritesRepository`, `data/weather/WeatherRepository`, `data/windows/SettingsStore`, `core/astro` (`LunarEphemeris`, `Precession`), `core/geometry/SphericalGeometry`, `ui/planning/PlanningLocation`, `ui/components`. **Wird benutzt von:** `StarWindowNavHost` (Route `guide`).

#### [`PhotoGuideViewModel.kt`](app/src/main/java/com/starwindow/app/ui/guide/PhotoGuideViewModel.kt)

| Element | Art | Aufgabe |
|---|---|---|
| `PhotoGuideUiState` | data class | Zustand (s. u.) |
| `PhotoGuideViewModel` | class | Eingaben sammeln, `PhotoGuide.plan` aufrufen |
| `setQuery`, `selectTarget`, `clearTarget`, `selectTelescope`, `setBortle` | fun | Benutzeraktionen |
| `DEFAULT_BORTLE` | const | `5` |
| `MIN_ALTITUDE_DEG` | const (privat) | `20.0` (wie im Hub) |
| `MAX_FORECAST_GAP_MILLIS` | const (privat) | 3 h – weiter entfernte Vorhersagestunden zählen nicht |

**`PhotoGuideUiState`:** `query`, `suggestions` (Favoriten zuerst, dann lohnende Katalogobjekte bzw. Suchtreffer, max. 12), `target`, `telescope` (Default `SmartTelescopes.DEFAULT` = Seestar S50), `bortle`, `suggestedBortle` (aus `BortleScale.estimate(place)`), `bortleIsManual`, `placeLabel`, `hasLocation`, `plan: PhotoPlan?`, `moonIlluminationPercent`, `moonAltitudeDeg`, `dewSource` (Text oder `null` = nur Jahreszeit), `isLoading`; abgeleitet `favoritesShown`.

**Funktionsweise:** `init`: Katalog laden, `favoritesRepository.load()`, Ort über `PlanningLocation`. Persistierte Auswahl wird aus `SettingsStore` gelesen: `telescopeId` (→ `SmartTelescopes.byId`), `guideObjectId` (→ `selectTarget`), Bortle-Override aus `weatherPlace.bortleOverride` oder sonst `settings.bortleOverride`. Ein zweiter Collector aktualisiert die Vorschläge, wenn sich `favoritesRepository.favorites` ändert und kein Suchtext eingegeben ist.
- `setQuery` sucht auf `Dispatchers.Default` mit `ObjectSearch.search(query, catalog, limit = 30)`, gefiltert auf `PhotographicInterest.isPhotoTarget`.
- `selectTarget` → `settingsStore.setGuideObject(id)` + `recompute()`; `clearTarget` → `setGuideObject(null)`.
- `selectTelescope` → `settingsStore.setTelescope(id)` + `recompute()`.
- `setBortle(level|null)` schreibt in den gewählten Wetterort (`setWeatherPlace(place.copy(bortleOverride = level))`), ohne Wetterort in `setBortleOverride`; `null` = zurück zum Vorschlag.
- `recompute()` bricht den vorigen `planJob` ab; berechnet die heutige Nacht mit `ObservationPlanner.nightFor(..., minAltitudeDeg = 20)` auf `Dispatchers.Default`; Mondposition/-höhe/-beleuchtung zum besten Zeitpunkt (`night.bestMillis`, sonst jetzt) und den Mondabstand zum Ziel; Taupunkt/Luftfeuchte aus `weatherRepository.lastKnown(place, model, fallbackToAnyModel = true)` (nur Cache, kein Netzabruf) in der Stunde, die dem Zeitpunkt am nächsten liegt (≤ 3 h); dann `PhotoGuide.plan(obj, telescope, bortle, moon…, dewSpreadK, humidityPercent, season, availableHours)`.

**Zusammenspiel:** Nur der Guide-Screen. Schreibt in `SettingsStore` (Teleskop, Guide-Objekt, Bortle); der Bortle-Wert im Wetterort ist derselbe, den die Wetteransicht zeigt.

**Ändern/Fallstricke:** `suggestedBortle`/`placeLabel` werden nur in `init` bestimmt; ein Ortswechsel in der Wetteransicht wird erst bei der nächsten Neuberechnung im Label sichtbar, der Bortle-Vorschlag gar nicht (anders als im Hub kein Settings-Collector). `defaultSuggestions()` sortiert den ganzen Katalog nach `PhotographicInterest.score` auf dem Main-Thread. Die 20° stehen zusätzlich als Text in `verdictExplanation` im Screen.

#### [`PhotoGuideScreen.kt`](app/src/main/java/com/starwindow/app/ui/guide/PhotoGuideScreen.kt)

Drei nummerierte Eingabeschritte, darunter fünf Ergebniskarten. Kein „Berechnen"-Knopf – jede Eingabe rechnet sofort neu.

| Element | Art | Aufgabe |
|---|---|---|
| `PhotoGuideScreen(viewModel, onOpenObject, modifier)` | @Composable | Aufbau, Zustandsweiche (Laden / kein Ziel / kein Ort / Plan) |
| `StepBlock` | @Composable (privat) | Nummerierter Schritt 1–3 |
| `TargetPicker` / `SuggestionRow` | @Composable (privat) | Suchfeld + Vorschläge bzw. gewähltes Ziel mit Bild, Info (→ `onOpenObject`) und Schließen (→ `clearTarget`); Favoriten-Herz über `appContainer.favoritesRepository.isFavorite` |
| `TelescopePicker` | @Composable (privat) | `LazyRow` über `SmartTelescopes.ALL` (Öffnung, Blende, Bildfeld) |
| `BortlePicker` | @Composable (privat) | Vorschlags-Chip, `Slider` 1–9, Klassentext aus `BortleScale.CLASSES` |
| `VerdictCard`, `FilterCard`, `ExposureCard`, `FramingCard`, `DewCard` | @Composable (privat) | Darstellung der `PhotoPlan`-Felder |
| `GuideCard`, `GuideLoading`, `GuideHint` | @Composable (privat) | Kartenrahmen, Ladeanzeige, Hinweise (`WAITING_FOR_TARGET`, `NO_LOCATION`) |
| `verdictExplanation`, `framingExplanation`, `formatMinutes` | fun (privat) | Erklärtexte und Zeitformat |

**Funktionsweise:** Alle Werte kommen aus `PhotoPlan` (`verdict`, `useFilter`, `filterSpeedup`, `subExposureSec`, `subCount`, `minimumMinutes`, `recommendedMinutes`, `availableMinutes`, `nightsNeeded`, `nightCoverage`, `fitsInTonight`, `framing`, `needsMosaic`, `fillFraction`, `objectPixels`, `dew`, `dewAction`). Die fachliche Logik steckt in `domain/PhotoGuide`; der Screen formuliert nur Texte.

**Ändern/Fallstricke:** Der Bortle-`Slider` ruft `setBortle` bei jedem Zwischenwert während des Ziehens auf (schreibt Einstellungen und startet eine Neuberechnung). Mehrere Erklärtexte setzen Strings mit `+` zusammen und rufen `.format(...)` nur auf das letzte Stück auf – Platzhalter in den vorderen Teilen werden dann nicht ersetzt. Alle Texte stehen direkt in dieser Datei.

#### Typische Änderungen in diesem Bereich
- **Neues Teleskop im Guide anzeigen:** Eintrag als `SmartTelescope` in `data/gear/SmartTelescope.kt` anlegen und in `SmartTelescopes.ALL` aufnehmen; `TelescopePicker` zeigt es automatisch, die Auswahl wird über `id` persistiert (eine stabile `id` wählen, sonst fällt `byId` auf `DEFAULT` zurück).
- **Neue Ergebniskarte:** Feld in `PhotoPlan` und Berechnung in `PhotoGuide.plan` → neue private `@Composable` mit `GuideCard` → im `else`-Zweig von `PhotoGuideScreen` als `item { … }` einfügen.
- **Neue Eingabe (z. B. Filterwahl):** Feld in `PhotoGuideUiState`, Setter im ViewModel (persistieren über `SettingsStore`, dann `recompute()`), Parameter an `PhotoGuide.plan`, neuer `StepBlock` im Screen.
- **Mindesthöhe ändern:** `MIN_ALTITUDE_DEG` in `PhotoGuideViewModel` **und** den Text „20°" in `verdictExplanation` anpassen (ggf. auch `SeasonalHighlights.MIN_ALTITUDE_DEG` für den Hub).

<a id="pkg-ui-weather"></a>

### `ui/weather` — Wetter für die Nacht

Der Reiter „Wetter" beantwortet die Frage „Lohnt sich heute Abend?": oben eine Bewertungskarte für die laufende bzw. kommende Nacht, darunter Diagramm und Zahlen, dann eine aufklappbare Liste der weiteren Nächte (bis zu 15, je nach Reichweite des Modells). Einstieg ist `WeatherScreen`, das allen Zustand aus `WeatherViewModel.uiState` bezieht. Die Fachlogik (Stundenbewertung, Urteil, Prozentzahl) steckt nicht hier, sondern in `domain/AstroWeather` (`AstroNight`, `AstroHour`, `NightVerdict`); das Paket ist reine Darstellung plus Lade-Steuerung.

**Benutzt:** `data/weather` (`WeatherRepository`, `PlaceLookup`, `WeatherModel`, `WeatherModelStatus`, `WeatherPlace`, `OpenMeteo.SOURCE_LABEL`), `domain` (`AstroWeather`, `AstroNight`, `AstroHour`, `NightVerdict`, `BortleScale`), `core/astro` (`NightTimes`, `MoonIllumination`), `core/sensors/LocationTracker`, `data/windows/SettingsStore`, `ui/components` (`SectionCard`, `SectionHeader`, `StatusPill`), `ui/theme`, `ui/windows/formatDuration`. **Wird benutzt von:** nur `ui/nav/StarWindowNavHost` (Route `Routes.WEATHER`). Alle übrigen Composables des Pakets werden ausschließlich innerhalb von `ui/weather` verwendet.

#### [`WeatherViewModel.kt`](app/src/main/java/com/starwindow/app/ui/weather/WeatherViewModel.kt)

Hält den gesamten Zustand der Wetteransicht und steuert Ortswahl, Modellwahl und das Laden der Vorhersage. Ein Ort + ein Modell → ein Abruf → alle Nächte auf einmal ausgewertet (kein Nachladen pro aufgeklapptem Tag).

| Element | Art | Aufgabe |
|---|---|---|
| `WeatherUiState` | data class | Unveränderlicher Bildschirmzustand (Felder siehe unten) |
| `WeatherUiState.tonight` / `upcoming` | abgeleitete Property | erste Nacht / alle weiteren aus `nights` |
| `WeatherUiState.bortleLevel` / `bortleIsManual` | abgeleitete Property | `BortleScale.resolve(place)`; manuell, wenn `place.bortleOverride != null` |
| `WeatherUiState.selectedStatus` | abgeleitete Property | `modelStatuses[model]` |
| `WeatherUiState.covers(model)` | fun | Deckt das Modell den Ort ab? Ohne Ort oder ohne Status: `true` (nichts behaupten) |
| `WeatherViewModel` | class (ViewModel) | Steuerung; Konstruktor: `WeatherRepository`, `PlaceLookup`, `LocationTracker`, `SettingsStore` |
| `refreshModelStatuses(forceReload)` | fun | Lauf-Infos aller sechs Modelle holen (`meta.json`) |
| `selectModel(model)` | fun | Modell wechseln, in `SettingsStore` speichern, neu laden |
| `setQuery` / `clearQuery` | fun | Ortssuche mit 350 ms Entprellung |
| `selectPlace(place, persist)` | fun | Suchtreffer übernehmen, speichern, laden |
| `useCurrentLocation()` | fun | Letzte GPS-Position → `PlaceLookup.describe` → laden |
| `refresh()` | fun | Modellstatus und Vorhersage mit `forceReload = true` |
| `toggleDay(date)` | fun | Eine Nacht der Liste auf-/zuklappen (nur eine gleichzeitig) |
| `setBortleOverride(level)` | fun | Bortle-Stufe von Hand setzen, `null` = Schätzung |
| `factory(container)` | companion fun | `viewModelFactory` aus `AppContainer` |

**`WeatherUiState`-Felder:** `place` (gewählter Ort, `WeatherPlace?`), `query`/`suggestions`/`isSearching` (Ortssuche), `isLoading`, `error` (Text für den Bildschirm), `nights: List<AstroNight>`, `expandedDate`, `fetchedAtMillis` (wann die App abgerufen hat), `zone` (Zeitzone des Ortes, anfangs Systemzone), `model` (Standard `WeatherModel.DEFAULT` = ECMWF IFS), `modelStatuses: Map<WeatherModel, WeatherModelStatus>`, `forecastEndMillis` (letzter Stundenwert der Vorhersage — bewusst nicht dasselbe wie das Ende des letzten Laufs).

**Funktionsweise:**
- **Start (`init`):** Modell aus `settingsStore.current.weatherModel`. Ist ein Ort gespeichert (`settingsStore.current.weatherPlace`), wird er mit `selectPlace(saved, persist = false)` geladen, sonst `useCurrentLocation()`. Danach sofort `refreshModelStatuses()`, damit die Statuszeile unter den Chips gleich erscheint.
- **Standort:** `useCurrentLocation()` nimmt `locationTracker.lastKnown()` (keine aktive Ortung). Ohne Position gibt es keine Ladeanzeige, sondern einen Fehlertext — unterschieden nach `locationTracker.hasPermission` („Noch keine Position …" bzw. „Ohne Ortungsfreigabe …"). Mit Position: `placeLookup.describe(fix)` (Geocoder + Ortssuche für Namen/Einwohnerzahl; schlägt laut Kommentar nie fehl), Ort wird gespeichert, dann `load(...)`.
- **Ortssuche:** `setQuery` bricht den laufenden `searchJob` ab, wartet `SEARCH_DEBOUNCE_MILLIS = 350`, setzt `isSearching` und ruft `weatherRepository.searchPlaces(query)` (Open-Meteo-Geocoding). Fehler ergeben einfach eine leere Liste.
- **Laden (`load`, privat):** bricht `loadJob` ab, setzt `isLoading = true, error = null`, ruft `weatherRepository.forecast(place, model, forceReload)`. Das Repository liefert ein `Result` und wirft nie: bei `forceReload = false` zuerst den Speichercache (30 min frisch), sonst Netz über ggf. mehrere URLs, bei Netzfehler hilfsweise alte Daten aus Speicher- oder Plattencache (`ForecastCache`); außerhalb des Modellgebiets eine `OutsideModelAreaException`. Bei Fehlschlag: `nights = emptyList()`, `error` = Ausnahmetext oder „Die Vorhersage ist gerade nicht erreichbar.".
- **Nachtbewertung:** Bei Erfolg `AstroWeather.nights(forecast, place.toObserverLocation())` in `withContext(Dispatchers.Default)` (Sonne/Mond werden für jede Stunde gerechnet — nicht im Hauptthread). Ergebnis: Liste `AstroNight` mit Stundenwerten (`AstroHour.score`, `cloudPercent`, `moonUp` …), `verdict`, `stargazingRating`, `ratingLabel` usw. Danach wird `place` durch `forecast.place` ersetzt (Gitterhöhe, Zeitzone), wobei `bortleOverride` des Nutzers erhalten bleibt; `zone`, `model`, `fetchedAtMillis`, `forecastEndMillis` werden aus der Vorhersage übernommen. Liefert das Modell keine ganze Nacht, steht `"<Modell> liefert für diesen Ort keine ganze Nacht."` in `error`.
- **Modellstatus:** `refreshModelStatuses(forceReload)` holt bei `forceReload` jeden Status einzeln neu (`modelStatus(it, forceReload = true)`, nacheinander), danach `modelStatuses()` (parallel, 5 min Cache im Repository). Ein leeres Ergebnis überschreibt vorhandene Statuswerte nicht.
- **Threading:** alles in `viewModelScope` (Main); Netz/IO in den Repository-Funktionen auf `Dispatchers.IO`, Auswertung auf `Dispatchers.Default`. Drei Jobs (`searchJob`, `loadJob`, `statusJob`) werden jeweils vor Neustart abgebrochen.
- **Persistenz:** Ort (inkl. Bortle-Override) und Modell landen in `SettingsStore` (`setWeatherPlace`, `setWeatherModel`).

**Zusammenspiel:** Erzeugt in `StarWindowNavHost` über `viewModel(factory = WeatherViewModel.factory(container))`; das ViewModel lebt damit im Back-Stack-Eintrag der Route `weather` und bleibt bei Reiterwechseln dank `saveState`/`restoreState` erhalten. `WeatherScreen` ruft ausschließlich die öffentlichen Funktionen oben.

**Ändern/Fallstricke:**
- Der Coroutine-Block in `useCurrentLocation()` ist **nicht** an `loadJob` gebunden und wird nicht abgebrochen. Wählt der Nutzer währenddessen einen Suchtreffer, kann das späte Ergebnis von `describe` den gewählten Ort überschreiben und speichern.
- `isLoading` wird bei vorhandenen Nächten nirgends sichtbar (der Spinner erscheint nur bei leerer Liste) — ein Neuladen läuft also ohne Rückmeldung.
- `selectModel` ohne gesetzten Ort speichert nur das Modell, lädt aber nichts.
- Fehlertexte sind fest verdrahtete deutsche Strings im ViewModel (keine `strings.xml`).
- `WeatherRepository.lastKnown(...)` (sofortige Anzeige aus dem Cache ohne Netz) wird hier nicht genutzt; beim Öffnen sieht man bis zum Ende des Abrufs nur den Spinner, außer der Speichercache ist frisch.

#### [`WeatherScreen.kt`](app/src/main/java/com/starwindow/app/ui/weather/WeatherScreen.kt)

Der Bildschirm selbst (ca. 930 Zeilen): Ortsleiste, Modellzeile, Inhalt je nach Zustand, zwei Dialoge, dazu die Textbausteine für Urteile und Formatierungen.

| Element | Art | Aufgabe |
|---|---|---|
| `WeatherScreen(viewModel, modifier)` | @Composable (öffentlich) | Wurzel; sammelt `uiState` per `collectAsStateWithLifecycle()` |
| `PlaceBar` | @Composable privat | Suchfeld (Platzhalter = aktueller Ortsname), Buttons „Eigenen Standort verwenden", „Vorhersage neu laden" |
| `SuggestionList` | @Composable privat | `LazyColumn` der Suchtreffer (Koordinaten, Höhe, Einwohner) |
| `EmptyState(error)` | @Composable privat | Symbol + Fehler- oder Hinweistext |
| `NightList` | @Composable privat | `LazyColumn`: Bewertungskarte, Karte „heute" mit Diagramm + Details, Liste der weiteren Nächte, Fußzeile |
| `NightRow` | @Composable privat | Listenzeile: Wochentag, Datum, Kurzurteil, Mond, `VerdictIcon` |
| `VerdictIcon` / `verdictColor` | @Composable / fun privat | Symbol und Farbe je `NightVerdict` |
| `NightDetails` | @Composable privat | Zahlentafel in Gruppen „Dunkelheit", „Mond", „Bewölkung", „Luft um …", „Ort" |
| `BortleRow` / `BortleDialog` | @Composable privat | Bortle-Chip mit Herkunft („geschätzt"/„selbst gesetzt"); Auswahl 1–9 bzw. „Schätzung verwenden" |
| `Footer` | @Composable privat | Quelle (Modell, Auflösung, Open-Meteo), Lauf-Veröffentlichung, Abrufzeit, Zeitzone, Fehler |
| `DataRow` / `GroupTitle` | @Composable privat | Zeile „Beschriftung – Wert – Zusatz"; leere Werte werden ausgelassen |
| `summaryOf(night)` | internal fun | Urteilssatz (auch von `NightRatingCard` benutzt) |
| `shortSummaryOf`, `cloudLayers`, `seeingHint`, `clock`, `formatPopulation` | private fun | Texte/Formatierung |
| `NightHeadline`, `SectionTitle` | @Composable privat | **nicht aufgerufen** (Überbleibsel) |

**Funktionsweise:** Aufbau `Column(fillMaxSize().safeDrawingPadding())`:
1. `PlaceBar` — Tastatur wird vor Aktionen per `LocalSoftwareKeyboardController` geschlossen. `ImeAction.Search` löst nichts aus (`onSearch = {}`); gesucht wird bereits beim Tippen.
2. `ModelRow` (aus `ModelPicker.kt`) — nur sichtbar, solange keine Suche läuft/keine Treffer da sind.
3. Inhalt per `when`: Treffer/Suche → `SuggestionList`; `isLoading` und keine Nächte → `CircularProgressIndicator`; keine Nächte → `EmptyState(state.error)`; sonst `NightList`.
4. Dialoge über lokale `remember`-Flags `modelDialogVisible` (→ `ModelDialog`) und `bortleDialogVisible` (→ `BortleDialog`).

`NightList` verwendet feste `item`-Keys (`"rating"`, `"tonight"`, `"upcoming_title"`, Datum je Nacht, `"source"`). Die heutige Nacht zeigt `NightRatingCard` und darunter eine `SectionCard` mit `AstroWeatherChart` + `NightDetails`. Jede weitere Nacht ist eine anklickbare `SectionCard` (`onClick → onToggleDay`), deren Inhalt über `AnimatedVisibility(visible = expanded)` aufklappt — die einzige eigene Animation im Wetter-Reiter. `NightDetails` nimmt für die Gruppen „Bewölkung → Schichten" und „Luft" die beste Stunde `night.bestHour`; Schwellen kommen aus `AstroWeather.CLEAR_CLOUD_PERCENT` und `AstroNight.USABLE_SCORE`. Taubeschlag-Warnung bei Taupunktabstand < 2 K; `seeingHint` stuft den Höhenwind (250 hPa) bei 60/120 km/h ein.

**Zusammenspiel:** `WeatherScreen` ← `StarWindowNavHost`. Ruft `NightRatingCard`, `AstroWeatherChart`, `ModelRow`, `ModelDialog`, `formatAge` (aus `ModelPicker.kt`) und `formatDuration` (aus `ui/windows`).

**Ändern/Fallstricke:**
- Alle Uhrzeiten laufen über `night.zone` bzw. `state.zone` (Zeitzone des Ortes, nicht des Telefons) — neue Zeitangaben ebenso formatieren (`clock(millis, zone)`).
- Datums-Formatter sind mit `Locale.GERMAN` fest; `clockFormatter` (`"HH:mm"`) ist locale-neutral.
- Die Bewertungsfarben sind an zwei Stellen definiert (`verdictColor`/`VerdictIcon` hier, `ratingColor` in `NightRatingCard`).
- `NightHeadline` und `SectionTitle` sind toter Code und können entfernt werden.
- Die Datei ist laut DEV_PLAN §11.4 S6 ein Kandidat zum Aufteilen.

#### [`AstroWeatherChart.kt`](app/src/main/java/com/starwindow/app/ui/weather/AstroWeatherChart.kt)

Das Nachtdiagramm: eine einzige y-Achse in Prozent („oben ist gut"), Zeit auf der x-Achse, gezeichnet mit `Canvas` und `DrawScope`, Texte über `android.graphics.Paint` auf `nativeCanvas`.

| Element | Art | Aufgabe |
|---|---|---|
| `AstroWeatherChart(night, modifier, nowMillis)` | @Composable | Das Diagramm, 218 dp hoch, volle Breite |
| `ChartWindow.of(night)` | private class + companion fun | Zeitausschnitt: Sonnenuntergang − 1 h bis Sonnenaufgang + 1 h, begrenzt auf vorhandene Stunden; unter 3 h → ganze Nacht |
| `PlotArea`, `ChartScale` | private class | Zeichenfläche (links 30 dp Rand, unten 22 dp Achsenband); Umrechnung Zeit→x, Prozent→y (0 unten, 100 oben, geklemmt) |
| `drawTwilight` | private DrawScope-fun | Hintergrund je Stundenintervall, Alpha aus `AstroWeather.darknessFactor(sunAltitudeDeg)` (max. 0,34) |
| `drawMoonBand` | private DrawScope-fun | 5 dp-Streifen oben, solange `moonUp`; Deckkraft nach Mondbeleuchtung; Beschriftung „Mond über dem Horizont" |
| `drawGrid` | private DrawScope-fun | Linien bei 0/25/50/75/100 %; Stundenstriche (jede Stunde, ab > 10 h Spanne jede zweite), Stundenzahlen `"HH"` in Ortszeit |
| `drawCloudBand` | private DrawScope-fun | Bewölkung hängt von oben: Fläche bis `100 − cloudPercent`, Kante als Linie |
| `drawScoreCurve` | private DrawScope-fun | Eignungskurve `AstroHour.score`; Schwellenlinie bei `USABLE_SCORE` (50); Abschnitte über der Schwelle 4 dp dick |
| `drawDarknessMarkers` | private DrawScope-fun | Senkrechte bei astronomischer Abend-/Morgendämmerung („Dunkelheit"/„Dämmerung") |
| `drawNow` | private DrawScope-fun | Rote Linie „jetzt", falls im Fenster |
| `drawCursor` / `drawReadout` | private DrawScope-fun | Fingerlinie, rastet auf die nächste Stunde; Kasten mit Uhrzeit, Bewölkung, Eignung, Mondhöhe/-anteil |
| `WeatherChartPaints` | private class | Sechs `Paint`s (Achse, Wolke, Eignung, Mond, jetzt, Kasten), Größe in sp × `density`, mit Schatten |
| `CLOUD_FILL`, `CLOUD_EDGE`, `TWILIGHT_COLOR` | private val | Diagramm-eigene Farben (nicht im Theme) |

**Funktionsweise:** Zeichenreihenfolge im `Canvas`-Block: Dämmerung → Mondband → Gitter → Wolkenband → Eignungskurve → Dunkelheitsmarken → „jetzt" → Cursor. Bei weniger als zwei Stundenwerten wird nur ein Hinweistext gezeichnet. `paints` hängen per `remember(density)` an der Dichte, `window` per `remember(night)`.
**Fingerbedienung:** `pointerInput(night.date)` mit `awaitEachGesture`. Der erste Druck wird *nicht* konsumiert (`awaitFirstDown(requireUnconsumed = false)`), der Cursor erscheint sofort. Überschreitet die Bewegung zuerst senkrecht den `touchSlop` (und `dy > dx`), wird der Cursor entfernt und die Geste an die `LazyColumn` abgegeben; waagerecht über `touchSlop` „beansprucht" das Diagramm die Geste und konsumiert die Ereignisse. Beim Loslassen bleibt der Cursor an der letzten Stelle stehen (er wird erst bei neuer Nacht oder senkrechtem Wisch zurückgesetzt). `cursorX` ist `remember(night.date)`-State.
**Fehlende Bewölkung** wird als 0 % gezeichnet (Fläche leer), im Kasten aber als „Bewölkung unbekannt" ausgewiesen.

**Zusammenspiel:** Aufgerufen aus `WeatherScreen.NightList` (heutige Nacht und jede aufgeklappte). Liest nur `AstroNight`/`AstroHour` und `AstroWeather.darknessFactor`.

**Ändern/Fallstricke:**
- Jede Fingerbewegung ändert `cursorX` und lässt den **gesamten** `Canvas` neu zeichnen (DEV_PLAN §11.3 P6). Eine Trennung in statische Ebene (z. B. `drawWithCache`) und Cursor-Ebene wäre der Ansatz.
- `clockFormatter` im Kasten nutzt `"EEE HH:mm"` **ohne `Locale`** — der Wochentag erscheint in der Systemsprache (DEV_PLAN P6).
- Texte gehen über `nativeCanvas.drawText` und skalieren mit `density.density`, nicht mit der Schriftgrößen-Einstellung (`fontScale`).
- Neue Größen müssen auf die Prozentachse passen (0–100, oben gut); andere Einheiten bräuchten eine zweite Achse, die das Konzept bewusst vermeidet.

#### [`TwilightBand.kt`](app/src/main/java/com/starwindow/app/ui/weather/TwilightBand.kt)

Ein 56 dp hohes Farbband der Nacht, Mitternacht fest in der Mitte, mit den acht Dämmerungsgrenzen aus `NightTimes` als Farbwechsel und Striche, darunter Beschriftungen.

| Element | Art | Aufgabe |
|---|---|---|
| `TwilightBand(times, zone, modifier, nowMillis)` | @Composable | Band + Zeile „Untergang · Mitternacht · Aufgang" + Zeilen „Abend"/„Morgen" |
| `bandWindow(times)` | private fun | Fenster symmetrisch um `(times.fromMillis + times.toMillis) / 2`; Halbbreite = weitester Abstand von Auf-/Untergang bzw. astronomischer Dämmerung + 45 min, 4–12 h |
| `boundaries(times)` | private fun | Acht Grenzen; Auf-/Untergang und astronomische Grenzen als „major" (kräftiger Strich) |
| `gradientStops(...)` | private fun | Je Grenze zwei Stützstellen (Farbe davor/danach, Abstand `EPSILON = 0.006`); erzwingt streng steigende Positionen |
| `DAY`, `CIVIL`, `NAUTICAL`, `ASTRONOMICAL`, `NIGHT` | private val | Farben der Stufen |

**Funktionsweise:** Fehlt eine Grenze (z. B. keine astronomische Dunkelheit im Sommer), entfallen ihre Stützstellen und die Nachbarfarben gehen ineinander über. Die Beschriftungen nennen, was **beginnt** („naut. ab 20:43"). Rote Linie für „jetzt", wenn im Fenster.
**Zusammenspiel:** nur aus `NightRatingCard`. **Ändern:** Die Mitte ist nur dann Mitternacht, wenn `NightTimes.fromMillis/toMillis` symmetrisch um Mitternacht liegen (Definition in `core/astro/Twilight`).

#### [`MoonPhaseDisc.kt`](app/src/main/java/com/starwindow/app/ui/weather/MoonPhaseDisc.kt)

Zeichnet den Mond in seiner aktuellen Phase aus `MoonIllumination.fraction` und `.waxing`, ohne Phasen-Fallunterscheidung.

| Element | Art | Aufgabe |
|---|---|---|
| `MoonPhaseDisc(illumination, modifier, size = 64.dp)` | @Composable | `Canvas` mit `contentDescription` „‹Phase›, x % beleuchtet" |
| `moonLimbDirection(waxing)` | internal fun | +1 = helle Seite rechts (zunehmend), −1 links |
| `moonTerminatorOffset(fraction, waxing)` | internal fun | x-Schnittpunkt des Terminators mit dem Äquator: `richtung · (1 − 2k)` in Radien |
| `litSpan(fraction, waxing)` | internal fun | Heller Bereich auf der Äquatorlinie (für Tests) |

**Geometrie:** Dunkle Scheibe (`MOON_DARK`, Blaugrau statt Schwarz) als Grund. Unter 0,5 % Beleuchtung nichts weiter, ab 99,5 % volle Scheibe. Sonst ein Pfad aus 72 Schritten: erst der helle Rand als Halbkreis auf der Seite `direction` (Pol oben → Pol unten), dann zurück über die Terminator-Halbellipse mit waagerechter Halbachse `R · moonTerminatorOffset`. Ist der Wert positiv (Sichel), liegt der Terminator auf der hellen Seite; negativ (Dreiviertel) auf der dunklen. Füllung mit radialem Verlauf (`fullMoonBrush`). Nur Nordhalbkugel-Ausrichtung.
**Zusammenspiel:** nur `NightRatingCard` (76 dp). Getestet in `app/src/test/java/com/starwindow/app/MoonPhaseDiscTest.kt` — wer die Formeln ändert, muss `drawMoon` und `litSpan` gemeinsam ändern, da beide dieselben Hilfsfunktionen nutzen.

#### [`NightRatingCard.kt`](app/src/main/java/com/starwindow/app/ui/weather/NightRatingCard.kt)

Oberste Karte der Wetteransicht: Tag, Bortle-Plakette, große Prozentzahl `night.stargazingRating` mit Wort `night.ratingLabel` und Fortschrittsbalken, daneben `MoonPhaseDisc`, darunter `summaryOf(night)` und `TwilightBand`.

| Element | Art | Aufgabe |
|---|---|---|
| `NightRatingCard(night, bortleLevel, bortleIsManual, onEditBortle, modifier)` | @Composable | Die Karte (in `SectionCard`) |
| `ratingColor(rating, night)` | private fun | Grau ohne Dunkelheit; ≥ 55 grün (`WindowStroke`), ≥ 35 orange (`AnchorPoint`), sonst rot (`Crosshair`) |
| `bortleTint(level)` | private fun | ≤ 3 grün, ≤ 5 `CatalogMarker`, ≤ 7 orange, sonst rot |

„Heute Abend" vs. „Diese Nacht" je nachdem, ob `night.date` dem heutigen Datum in `night.zone` entspricht. Die `StatusPill` „Bortle n (gesch.)" ist anklickbar und öffnet über `onEditBortle` den `BortleDialog` in `WeatherScreen`. **Fallstrick:** Die Farbschwellen 55/35 müssen zu den Stufen in `AstroNight.ratingLabel` passen (dort 75/55/35/15).

#### [`ModelPicker.kt`](app/src/main/java/com/starwindow/app/ui/weather/ModelPicker.kt)

Modellauswahl als Chipreihe mit Statuszeile sowie ein Vergleichsdialog aller sechs Modelle (`WeatherModel.ORDERED`, nach Maschenweite sortiert: ICON-CH1, AROME HD, HARMONIE, ICON-D2, ICON-EU, ECMWF IFS).

| Element | Art | Aufgabe |
|---|---|---|
| `ModelRow(state, onSelect, onOpenDetails)` | @Composable | `LazyRow` von `FilterChip`s „Label · Auflösung"; nicht abdeckende Modelle deaktiviert (außer dem gewählten) |
| `ModelStatusLine` | @Composable privat | „Anbieter Label · Gebiet" + „Lauf HH:mm UTC · aktualisiert vor … · Vorhersage bis …", orange bei `isOverdue()`; Antippen öffnet den Dialog |
| `ModelDialog(state, onSelect, onDismiss)` | @Composable | `AlertDialog` mit `ModelDetailRow` je Modell |
| `ModelDetailRow` | @Composable privat | Name, Auflösung, Anbieter, `about`-Text, Laufzusammenfassung oder „Rechnet nicht über …" |
| `runSummary`, `utcClock`, `localDay`, `formatInterval` | private fun | Texte zum Lauf (UTC-Laufzeit, Intervall, Laufende, Rohdatenschritt > 1 h) |
| `formatAge(millis)` | internal fun | „gerade eben", „vor 12 min", „vor 3 h 05 min", „vor einem Tag", „vor n Tagen" (auch im `Footer` benutzt) |

Achtung, zwei verschiedene Enden: „Vorhersage bis" (`state.forecastEndMillis`, aus der Vorhersage) vs. „dieser Lauf bis" (`status.dataEndMillis`, aus `meta.json`). Die Abdeckung (`state.covers`) beruht auf `WeatherModelStatus.bounds`; ohne Status gilt jedes Modell als abdeckend.

#### Typische Änderungen in diesem Bereich
- **Neue Größe ins Wetterdiagramm:** Wert muss in `AstroHour` (Domain, `domain/AstroWeather`) vorhanden sein → in `AstroWeatherChart.kt` eine `DrawScope.drawXxx(points, scale, plot, paints)` schreiben und in der Zeichenreihenfolge im `Canvas`-Block einhängen; ggf. eine eigene `Paint` in `WeatherChartPaints` und eine Zeile im Kasten (`drawCursor` → `lines`). Werte auf 0–100 % abbilden (`scale.y`).
- **Neue Zeile in der Zahlentafel:** in `WeatherScreen.NightDetails` `DataRow(label, value, hint)` ergänzen; `null`/leer wird automatisch ausgelassen.
- **Neues Wettermodell:** Eintrag in `data/weather/WeatherModel` (inkl. `resolutionKm`, `metaPath`); Chipreihe und Dialog übernehmen es automatisch über `WeatherModel.ORDERED`.
- **Bewertungsschwellen/-farben ändern:** Schwellen in `AstroNight` (`ratingLabel`, `USABLE_SCORE`) und `AstroWeather.CLEAR_CLOUD_PERCENT`; Farben in `NightRatingCard.ratingColor` und `WeatherScreen.verdictColor`/`VerdictIcon` gemeinsam anpassen.
- **Neuen Zustand/Fehlerfall anzeigen:** Feld in `WeatherUiState` ergänzen, im `WeatherViewModel` per `_uiState.update { it.copy(...) }` setzen, in `WeatherScreen` im `when`-Block bzw. `Footer` auswerten.

<a id="pkg-baselineprofile"></a>

### Modul `baselineprofile` — Startprofil und Messungen

Eigenes Gradle-Testmodul (`com.android.test`, Plugin `androidx.baselineprofile`, `targetProjectPath = ":app"`), das per UI Automator durch die App klickt. Es erzeugt das Baseline Profile (vorab kompilierte Methoden für Start, ersten Reiterwechsel, erstes Scrollen) und misst Start- und Bildzeiten (Macrobenchmark). Läuft nur auf einem angeschlossenen echten Gerät (`useConnectedDevices = true`), `minSdk = 28` (ohne Root ab Android 13).

**Benutzt:** `androidx.benchmark.macro.junit4`, `androidx.test.uiautomator`, `androidx.test.ext.junit`. **Wird benutzt von:** `app/build.gradle.kts` (`baselineProfile(project(":baselineprofile"))`, `dexLayoutOptimization = true`).

#### [`build.gradle.kts`](baselineprofile/build.gradle.kts) und [`AndroidManifest.xml`](baselineprofile/src/main/AndroidManifest.xml)

Modulkonfiguration: `namespace = "com.starwindow.baselineprofile"`, `compileSdk`/`targetSdk` 36, Java/JVM 17, Testrunner `AndroidJUnitRunner`. Das Manifest ist leer (`<manifest />`).

#### [`Journeys.kt`](baselineprofile/src/main/java/com/starwindow/baselineprofile/Journeys.kt)

Gemeinsame Bedienwege für Profil und Messungen, als Erweiterungsfunktionen auf `MacrobenchmarkScope`. **Bedienelemente werden über ihre sichtbaren Texte gefunden, nicht über Test-Tags.**

| Element | Art | Aufgabe |
|---|---|---|
| `TARGET_PACKAGE` | const | `"com.starwindow.app"` (Benchmark-Buildtypen ohne Suffix) |
| `BOTTOM_TABS` | val | `"Motive", "Guide", "Kalender", "Wetter", "Fenster", "Sucher"` — Sucher zuletzt |
| `grantPermissions()` | fun | `pm grant` für `CAMERA`, `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` |
| `startInViewfinder()` | fun | Home, App starten, warten (max. 10 s) auf Text, der mit **„Bildfeld"** beginnt (Anzeige „Bildfeld x,x°" in `CaptureScreen`) |
| `openTab(label)` | fun | Klickt das **anklickbare** Element mit Text `label` (Reiter), sonst `error(...)` |
| `flingCurrentList()` | fun | Erstes `scrollable`-Element einmal runter/rauf wischen (Randabstand gegen Zurück-Geste) |
| `searchAndOpenObject()` | fun | Klick auf **„Nebel, Stern oder Galaxie suchen"** (Suchfeld im Sucher), `EditText` → „M31", Treffer mit **„Andromeda"** öffnen, zurück |
| `backToBottomBar()` | private fun | Bis zu 4× Zurück, bis anklickbarer Text **„Sucher"** sichtbar ist |

**Fallstricke:** Wer einen dieser Texte in der App ändert (Reiter-`label`, „Bildfeld …", Suchfeld-Platzhalter, Objektname „Andromeda"), muss `Journeys.kt` anpassen. `openTab` bricht dann laut ab; `searchAndOpenObject` dagegen kehrt bei fehlendem Suchtext still zurück (`?.click() ?: return`), und `startInViewfinder` wartet einfach den Timeout ab — solche Brüche fallen also nicht unbedingt auf.

#### [`BaselineProfileGenerator.kt`](baselineprofile/src/main/java/com/starwindow/baselineprofile/BaselineProfileGenerator.kt)

`BaselineProfileRule` mit zwei Tests: `startup()` (`includeInStartupProfile = true`, nur Kaltstart bis Sucher — dient auch der DEX-Anordnung) und `journeys()` (Start, `searchAndOpenObject()`, dann jeder Reiter aus `BOTTOM_TABS` mit `openTab` + `flingCurrentList`). Ergebnis laut Kommentar/DEV_PLAN unter `app/src/release/generated/baselineProfiles/` — dieses Verzeichnis existiert im Repo derzeit noch nicht (Profil noch nicht erzeugt, DEV_PLAN §11.1 „Noch offen").

#### [`StartupBenchmarks.kt`](baselineprofile/src/main/java/com/starwindow/baselineprofile/StartupBenchmarks.kt)

Kaltstart (`StartupMode.COLD`, 10 Iterationen, `StartupTimingMetric`) einmal mit `CompilationMode.None()` und einmal mit `CompilationMode.Partial(BaselineProfileMode.Require)`. Letzterer schlägt absichtlich fehl, solange kein Profil vorhanden ist.

#### [`FrameBenchmarks.kt`](baselineprofile/src/main/java/com/starwindow/baselineprofile/FrameBenchmarks.kt)

`FrameTimingMetric`, je 5 Iterationen: `viewfinder()` (5 s ruhiger Sucher), `switchTabs()` (einmal durch `BOTTOM_TABS`), `scrollCalendar()` (Reiter „Kalender" öffnen, einmal wischen). Kennzahlen: `frameDurationCpuMs`, `frameOverrunMs`.

**Befehle (aus DEV_PLAN §11.2; Telefon per USB, entsperrt):**

```bash
./gradlew :app:generateBaselineProfile                            # Profil erzeugen, nach src/ kopieren
./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest   # alle Messungen
./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.starwindow.baselineprofile.FrameBenchmarks
```

Ergebnisse samt Perfetto-Traces zeigt Android Studio im Testfenster; Ausgangswerte gehören in die Tabelle in DEV_PLAN §11.2. Gemessen wird auf dem minifizierten Benchmark-Build (R8 aktiv).

#### Typische Änderungen in diesem Bereich
- **Benchmark-Journey anpassen:** neue Wege als `fun MacrobenchmarkScope.xxx()` in `Journeys.kt` anlegen (Elemente per `By.text(...)`, `By.textStartsWith(...)`, `By.clickable(true)`, warten mit `device.wait(Until..., TIMEOUT_MS)`), dann in `BaselineProfileGenerator.journeys()` und/oder als neuer `@Test` in `FrameBenchmarks` (`measureFrames(setup = { … }) { … }`) aufrufen.
- **UI-Text umbenannt:** in `Journeys.kt` (und `FrameBenchmarks.scrollCalendar`) per Suche nach dem alten Text nachziehen.
- **Neuer Reiter:** `BOTTOM_TABS` ergänzen, „Sucher" am Ende lassen.
- **Neue Berechtigung, die beim Start abgefragt wird:** in `grantPermissions()` ergänzen, sonst blockiert der Systemdialog jeden Lauf.

<a id="pkg-resources"></a>

### Ressourcen und R8-Regeln

Die App hat nur wenige Android-Ressourcen; Oberflächentexte stehen direkt im Kotlin-Code (es gibt keine Übersetzungen). Die Ressourcen dienen dem Manifest (Theme, Name, Symbol, Sicherungsregeln).

**Wird benutzt von:** `app/src/main/AndroidManifest.xml` (`android:theme="@style/Theme.StarWindow"`, `android:label="@string/app_name"`, `android:icon="@mipmap/ic_launcher"`, `android:fullBackupContent`, `android:dataExtractionRules`).

#### [`values/`](app/src/main/res/values/) — `colors.xml`, `strings.xml`, `themes.xml`

- `colors.xml`: `night` (`#07080F`, Fensterhintergrund bis Compose zeichnet) und `ic_launcher_background` (`#0B1026`).
- `strings.xml`: nur `app_name` = „StarWindow".
- `themes.xml`: `Theme.StarWindow` erbt von `android:Theme.Material.NoActionBar` (kein AppCompat), `windowBackground = @color/night`, Status- und Navigationsleiste transparent. Das eigentliche Aussehen kommt aus `StarWindowTheme` (Compose).

#### [`xml/backup_rules.xml`](app/src/main/res/xml/backup_rules.xml) und [`xml/data_extraction_rules.xml`](app/src/main/res/xml/data_extraction_rules.xml)

Auto-Backup (bis Android 11: `full-backup-content`; ab Android 12: `cloud-backup` und `device-transfer`) sichert gleichermaßen nur:
- `file`: `sky_windows.json` (gespeicherte Himmelsfenster, `SkyWindowRepository`)
- `sharedpref`: `starwindow_settings.xml` (Einstellungen inkl. Wetterort/-modell, `SettingsStore`)

**Nicht** gesichert werden damit u. a. `favorites.json` (`FavoritesRepository`), `observation_plan.json` (`PlanRepository`), `watchlist.json` (`WatchlistRepository`), die Prefs `starwindow_tracking` (`TrackingStore`) und `forecast_cache.json` (Cache, absichtlich verzichtbar). Unklar, ob das Weglassen von Favoriten, Plan und Merkliste beabsichtigt ist. Wer eine neue Datei sichern will, muss sie in **beiden** XML-Dateien (alle drei Abschnitte) eintragen.

#### [`drawable/ic_launcher_foreground.xml`](app/src/main/res/drawable/ic_launcher_foreground.xml) und `mipmap-anydpi-v26/ic_launcher*.xml`

Vektor-Symbol 108×108 dp: grüner Fensterrahmen (`#62E8B4`, = `WindowStroke`) mit Fensterkreuz, Stern (`#FFD9A0`) und kleinem blauem Punkt (`#9FD8FF`). `ic_launcher.xml` und `ic_launcher_round.xml` sind identische adaptive Icons (Hintergrund `@color/ic_launcher_background`, Vordergrund = obiges Drawable, auch als `monochrome` für Android-13-Themensymbole). Es gibt keine Bitmap-Mipmaps; da `minSdk = 26`, genügt `anydpi-v26`.

#### [`proguard-rules.pro`](app/proguard-rules.pro)

Zusätzliche R8-Regeln für den `release`-Build (`isMinifyEnabled = true`, `isShrinkResources = true`, zusammen mit `proguard-android-optimize.txt`). Inhalt ausschließlich für **kotlinx.serialization**:
- `-keepattributes *Annotation*, InnerClasses` — Annotationen und Innere-Klassen-Info bleiben erhalten.
- `-dontnote kotlinx.serialization.**` — unterdrückt Hinweise.
- Für jede `@Serializable`-Klasse bleibt das statische Feld `Companion` erhalten …
- … und in deren Companion-Klassen die Methode `serializer(...)`.

Hintergrund: `Json.decodeFromString<T>()` findet den generierten Serializer über `T.Companion.serializer()`. Entfernt R8 diesen Weg, scheitert das Laden gespeicherter Daten (Fenster, Plan, Merkliste, Einstellungen, Wetter-Cache) erst zur Laufzeit im Release-Build. Laut DEV_PLAN §11.1 bleiben die Serializer aller `@Serializable`-Klassen erhalten; der manuelle Durchgang durch den R8-Build steht noch aus.

#### Typische Änderungen in diesem Bereich
- **Neue persistente Datei sichern:** in `backup_rules.xml` und in beiden Abschnitten von `data_extraction_rules.xml` eintragen.
- **Neue Bibliothek mit Reflexion:** Keep-Regeln in `proguard-rules.pro` ergänzen und **Release-Build** testen (Debug-Build läuft ohne R8 und zeigt solche Fehler nicht).
- **App-Name/Symbol ändern:** `strings.xml` (`app_name`) bzw. `ic_launcher_foreground.xml` und `ic_launcher_background` in `colors.xml`.
- **Startfarbe angleichen:** `colors.xml` `night` an `StarWindowColors.Night` (`#0A0C16`) anpassen, falls beim Start ein Farbsprung sichtbar ist.

---

## 6. Daten, Dateien und Netz

### 6.1 Was wo gespeichert wird

Es gibt keine Datenbank. Alles Dauerhafte liegt als JSON (kotlinx.serialization) in Dateien unter
`filesDir` oder als JSON-Block in SharedPreferences.

| Ort | Klasse | Inhalt | Schreiben / Fehlerfall | Im Backup |
|---|---|---|---|---|
| `filesDir/sky_windows.json` | `SkyWindowRepository` | Gespeicherte Fenster, `List<SkyWindow>` (Form polymorph mit `"type"` = `"polygon"`, `"circle"` oder `"box"`) | `.tmp` + `renameTo`; unlesbar → `.corrupt` | ja |
| `filesDir/favorites.json` | `FavoritesRepository` | Objekt-IDs der Favoriten | `.tmp` + `renameTo` (Rückfall `copyTo`); unlesbar → `.corrupt` | **nein** |
| `filesDir/observation_plan.json` | `PlanRepository` | Termine, `List<PlannedSession>` inkl. Erinnerungen und Notiz | `.tmp` + `renameTo`; unlesbar → `.corrupt` | **nein** |
| `filesDir/watchlist.json` | `WatchlistRepository` | Merkliste, `List<WatchedObject>` | `.tmp` + `renameTo`; unlesbar → `.corrupt` | **nein** |
| `filesDir/forecast_cache.json` | `ForecastCache` | Die letzten 3 Vorhersageläufe (Ort, Modell, Stunden) | `.tmp` + `renameTo`; unlesbar → gelöscht | nein (Cache) |
| SharedPreferences `starwindow_settings`, Schlüssel `settings_json` | `SettingsStore` | Alle Einstellungen (`Settings`: Ort, Kalibrierung, Belichtung, Wetterort/-modell, Teleskop …) | ganzer Block bei jeder Änderung, `apply()` | ja |
| SharedPreferences `starwindow_tracking`, Schlüssel `target_json` | `TrackingStore` | Das verfolgte Ziel (`TrackTarget`, `"type"` = `"object"` oder `"window"`) mit vollständigem Objekt bzw. vollständiger Form | `apply()`; unlesbar → Schlüssel gelöscht | **nein** |
| `cacheDir/sky_images/<key>.jpg` | `SkyImageLoader` | Bildcache von hips2fits | ohne Größenlimit, Android darf `cacheDir` leeren | – |
| Arbeitsspeicher | `CatalogRepository` (ganzer Katalog), `WeatherRepository` (8 Vorhersagen à 30 min, Modellstatus 5 min), `SkyImageLoader` (32 Bitmaps, LRU) | | | – |
| AlarmManager (Systemzustand) | `ReminderScheduler` (Request-Code `hash(sessionId)·8 + lead.ordinal`), `WatchScheduler` (`900000 + attempt`) | Geplante Alarme | überleben keinen Neustart → `BootReceiver` | – |

**Regeln für alle gespeicherten Modelle** (sonst landen alte Dateien beim nächsten Start als `.corrupt` und
die Daten sind scheinbar weg):

* Neue Felder **immer mit Standardwert** anlegen.
* Feldnamen, `@SerialName`-Werte und Enum-Namen (`ObjectType`, `ReminderLead`, `WeatherModel`,
  `WeatherDemand` …) nicht umbenennen.
* Alle Leser verwenden `ignoreUnknownKeys = true`. Eine neuere Datei mit zusätzlichen Feldern kann eine
  ältere App also noch lesen; umgekehrt helfen nur die Standardwerte.
* Ein Umlauftest (serialisieren → deserialisieren) und ein Test „alte Datei ohne das neue Feld lädt" gehören
  dazu (Vorbilder in `SerializationTest`, `PlanningPersistenceTest`, `ReminderTest`).
* Im Release-Build entfernt R8 nicht benutzte Klassen. Die Keep-Regeln in `app/proguard-rules.pro` halten
  die Serializer **aller** `@Serializable`-Klassen; neue Modelle brauchen also keine eigene Regel, sollten
  aber einmal im Release-Build gespeichert und wieder geladen werden.

### 6.2 Netzzugriffe

Es gibt keine Netzwerkbibliothek, alle Abrufe laufen über `HttpURLConnection` auf `Dispatchers.IO` mit dem
User-Agent `StarWindow/0.1 (Android)`.

| Endpunkt | Klasse | Zweck | Timeouts |
|---|---|---|---|
| `https://api.open-meteo.com/v1/forecast` (`hourly=…`, `models=…`, `timeformat=unixtime`, `timezone=auto`, `forecast_days=…`) | `OpenMeteo.forecastUrl`, `WeatherRepository.forecast` | Stündliche Vorhersage; erst mit voller, bei Ablehnung mit reduzierter Variablenliste | 8 s / 15 s |
| `https://api.open-meteo.com/data/<metaPath>/static/meta.json` | `WeatherRepository.modelStatus` | Laufzeitpunkt, Veröffentlichung, Intervall, Modellgebiet (BBOX) | 8 s / 15 s |
| `https://geocoding-api.open-meteo.com/v1/search` (`name`, `count`, `language=de`) | `WeatherRepository.searchPlaces` (auch aus `PlaceLookup`) | Ortssuche mit Einwohnerzahl und Zeitzone | 8 s / 15 s |
| `https://alasky.cds.unistra.fr/hips-image-services/hips2fits`, Ausweichserver `alaskybis…` | `SkyImageLoader` | Objektbilder aus DSS2 (`CDS/P/DSS2/color`) | 5 s / 20 s |
| Android-`Geocoder` (Systemdienst) | `PlaceLookup.reverseGeocode` | Ortsname zur GPS-Position | – |

Kataloge, Rechnungen, Planung und Erinnerungen funktionieren ohne Netz. Fehlt das Netz, greift das Wetter
auf `ForecastCache` zurück; eine Erinnerung erscheint immer, die Wetterzeile nur, wenn etwas vorliegt.

### 6.3 Android-Komponenten, Alarme und Benachrichtigungen

| Komponente | Wo | Was |
|---|---|---|
| Activity | `MainActivity` | einzige Activity, Compose-Inhalt, Bildschirm bleibt an |
| Receiver | `ReminderReceiver` (`data/planning/ReminderReceiver.kt`) | macht aus einem fälligen Alarm die Terminerinnerung; Wetter wird per `goAsync` nachgetragen |
| Receiver | `WatchCheckReceiver` | tägliche Merklisten-Prüfung, setzt zuerst den Alarm für morgen |
| Receiver | `BootReceiver` (in `ReminderReceiver.kt`) | nach Neustart: `PlanReminderSync.rescheduleAll` und `WatchScheduler.scheduleDailyCheck` |
| Alarme | `ReminderScheduler`, `WatchScheduler` | `AlarmManager.setAndAllowWhileIdle(RTC_WAKEUP, …)`: feuert auch im Doze-Modus, aber nicht minutengenau. Keine Berechtigung für exakte Alarme nötig. Erinnerung: 17:00 Gerätezeit am Tag `Nacht − Vorlauf`; Merkliste: 15:00, Wiederholung nach 45 und 90 min, wenn kein Wetter zu bekommen war |
| Kanal `observation_reminders` | `ReminderScheduler.ensureChannel` | „Beobachtungstermine"; Benachrichtigungs-ID = `sessionId.hashCode()` (Nachträge ersetzen dieselbe Meldung, `setOnlyAlertOnce`) |
| Kanal `watch_opportunities` | `WatchScheduler.ensureChannel` | „Günstige Nächte"; feste ID `0x5741`, eine Sammelmeldung pro Nacht |
| Laufzeit-Berechtigungen | `CaptureScreen` (Kamera, Standort), `CalendarScreen` und `ObjectDetailScreen` (`POST_NOTIFICATIONS` ab Android 13) | Ohne Benachrichtigungsrecht werden Termine trotzdem gespeichert, es erscheint nur nichts |

Wo man im Logcat nachsieht: Die Klassen loggen unter ihrem Namen als Tag (z. B. `WeatherRepository`,
`NightVisionController`, `CameraPreview`, `ObjectNotes`); Lade- und Schreibfehler der Repositories erscheinen
als Warnung.

### 6.4 Assets und Import-Skripte: die Kataloge und wie sie entstehen

Die Kataloge liegen als JSON in `app/src/main/assets/catalog/` und werden von Python-Skripten in `scripts/` aus öffentlichen Quellen erzeugt. Die Skripte werden **nicht** im Gradle-Build ausgeführt; die erzeugten Dateien sind eingecheckt. Alle Koordinaten sind J2000 in Grad.

#### [`stars.json`](app/src/main/assets/catalog/stars.json)

`CatalogFile` mit `name = "Sterne (Bright Star Catalogue)"`, ~9.100 Sterne (README: 9.096), kompakt ohne Leerzeichen geschrieben (~1,8 MB), nach Helligkeit sortiert. Beispiel (gekürzt):

```json
{"id":"HR 7001","name":"Wega","type":"STAR","raDeg":279.2346,"decDeg":38.7836,"magnitude":0.03,
 "spectralType":"A0Va","constellation":"Lyr",
 "catalogIds":["α Lyr","Alpha Lyr","3 Lyr","HD 172167","SAO 67174","HIP 91262","Alp Lyr"],
 "alternativeNames":["Vega","α Lyrae","Alpha Lyrae","3 Lyrae"]}
```

| Feld | Herkunft |
|---|---|
| `id` | `HR <BSC-Nummer>` |
| `name` | Deutscher Name aus `german_star_names.json`, sonst IAU-WGSN-Name, sonst BSC-„Common"; leer, wenn keiner. |
| `type` | `STAR` oder `DOUBLE_STAR` (siehe `object_type` unten). |
| `magnitude` | BSC `Vmag`. |
| `separationArcsec` | Nur bei `DOUBLE_STAR`, BSC `Sep`. |
| `spectralType` | BSC `SpType`. |
| `constellation` | BSC-Sternbild, sonst aus den Sternbildgrenzen berechnet. |
| `catalogIds` | Bayer (Symbol und ausgeschrieben, z. B. `α Lyr`, `Alpha Lyr`), Flamsteed (`3 Lyr`), `HD`, `SAO`, `HIP` (aus IAU-Liste), Veränderlichenname (nicht rein numerisch). |
| `alternativeNames` | Englischer Name (wenn deutscher Name abweicht), BSC-Name, ausgeschriebene Bayer-/Flamsteed-Namen mit Genitiv. |

#### [`deepsky.json`](app/src/main/assets/catalog/deepsky.json)

`CatalogFile` mit `name = "Deep-Sky (OpenNGC, Sh2, Barnard, LBN)"` und `license` (CC-BY-SA-Pflichtangabe), 13.432 Objekte (~3,9 MB), nach Helligkeit sortiert (ohne Helligkeit am Ende). Keine Einzelsterne. Beispiel:

```json
{"id":"M31","name":"Andromedagalaxie","type":"GALAXY","raDeg":10.6848,"decDeg":41.2691,
 "sizeArcmin":177.83,"minorAxisArcmin":69.66,"positionAngleDeg":35.0,"surfaceBrightness":23.63,
 "magnitude":3.44,"constellation":"And","morphology":"Sb",
 "catalogIds":["NGC 224","2MASX J00424433+4116074","IRAS 00400+4059","MCG +07-02-016","PGC 002557","UGC 00454"],
 "alternativeNames":["Andromeda Galaxy"]}
```

Felder wie `SkyObject`; OpenNGC-Spalten: `MajAx`→`sizeArcmin`, `MinAx`→`minorAxisArcmin`, `PosAng`→`positionAngleDeg`, `SurfBr`→`surfaceBrightness`, `V-Mag` (sonst `B-Mag`)→`magnitude`, `Const`→`constellation`, `Hubble`→`morphology`. Zusatzkatalog-Einträge (z. B. `Sh2-103`) haben nur `id`, `name` (meist leer), `type`, Position, Größe und berechnetes Sternbild.

#### [`constellations.json`](app/src/main/assets/catalog/constellations.json)

`ConstellationFile` (`version`, `name = "Sternbildfiguren"`, `epoch`, `license`, `constellations`), eingerückt geschrieben (~100 KB), 88 Figuren. Je Figur `id` (IAU-Kürzel), `name` (deutsch, aus d3-celestial), `stars` (`{name, raDeg, decDeg}`, Name = Sternname oder Bayer-Bezeichnung aus `stars.json`, ggf. leer) und `lines` (Indexpaare in `stars`). `license` wird von `ConstellationFile` ignoriert (`ignoreUnknownKeys`).

#### [`object_notes.json`](app/src/main/assets/catalog/object_notes.json)

Handgepflegt, **kein** Skript. Felder: `version`, `name`, `comment` (wird ignoriert), `notes` (Schlüssel: gefaltete Bezeichnung wie `m1`, Wert: deutscher Beschreibungstext), `starNotes` (Schlüssel: gefalteter Sternname wie `wega`, `arktur`, `rigilkentaurus`). Die Schlüssel werden beim Laden ohnehin erneut mit `ObjectSearch.fold` gefaltet, Schreibweise ist also unkritisch.

#### [`scripts/import_bsc.py`](scripts/import_bsc.py)

Erzeugt `stars.json`. **Eingaben** (Download-Befehle stehen im Docstring): `bsc5.json` (Yale Bright Star Catalogue 5. Ausgabe als JSON von `brettonw/YaleBrightStarCatalog`), `IAU-CSN.txt` (IAU Catalog of Star Names, WGSN), `constbnd.dat` (Sternbildgrenzen VizieR VI/42) sowie `scripts/german_star_names.json`.
**Aufruf:** `python3 scripts/import_bsc.py bsc5.json IAU-CSN.txt constbnd.dat`

Ablauf:
1. `iau_names` liest die IAU-Liste als HR-Nummer → (Name, HIP). Der Name wird über feste Spalte gelesen, die HIP-Nummer **von rechts** abgezählt (UTF-8-Griechisch würde Spaltenpositionen verschieben).
2. Für jeden BSC-Eintrag mit `HR`, `Vmag` und `RAh` (sonst übersprungen): RA aus `RAh/RAm/RAs`·15, Dek aus `DE-/DEd/DEm/DEs` (J2000-Spalten), auf 4 Nachkommastellen.
3. `object_type`: `DOUBLE_STAR`, wenn `Sep` zwischen `DOUBLE_MIN_SEPARATION_ARCSEC = 1.0` und `DOUBLE_MAX_SEPARATION_ARCSEC = 300.0` liegt und `Dmag <= DOUBLE_MAX_MAGNITUDE_DIFFERENCE = 3.5`; sonst `STAR`.
4. `bayer_designations` erzeugt Bayer-/Flamsteed-Kennungen und ausgeschriebene Namen; `spell_greek` ersetzt griechische Buchstaben (`GREEK_SPELLED`) und hochgestellte Ziffern.
5. Namenspriorität: deutscher Name (Schlüssel ist der IAU-/englische Name) > IAU > BSC.
6. Sortierung nach Helligkeit, Ausgabe kompakt (`separators=(",", ":")`, `ensure_ascii=False`), Statistik auf der Konsole.

Die Tabelle `GENITIVE` ist im Skript definiert, wird aber nicht benutzt (die Genitivformen kommen fertig aus den BSC-Feldern `BayerF`/`FlamsteedF`).

#### [`scripts/import_deepsky.py`](scripts/import_deepsky.py)

Erzeugt `deepsky.json`. **Eingaben:** `NGC.csv` und `addendum.csv` (OpenNGC, Semikolon-CSV), `constbnd.dat` (VI/42), `sharpless.tsv` (VizieR VII/20), `barnard.tsv` (VII/220A), `lbn.tsv` (VII/9) sowie `scripts/german_names.json`. Die Zuordnung der Argumente erfolgt über die Endung (`.csv`, `.dat`, `.tsv`) und bei TSV über den **Dateinamen ohne Endung** (`sharpless`, `barnard`, `lbn` – genau so benennen; fehlende TSVs werden übersprungen).
**Aufruf:** `python3 scripts/import_deepsky.py NGC.csv addendum.csv constbnd.dat sharpless.tsv barnard.tsv lbn.tsv`

Ablauf:
1. OpenNGC-Zeilen mit RA/Dek und einem Typ aus `TYPE_MAP` werden übernommen (`G`→`GALAXY`, `GPair`/`GTrpl`/`GGroup`→`GALAXY_GROUP`, `OCl`, `GCl`, `Cl+N`, `PN`, `SNR`, `HII`/`EmN`→`EMISSION_NEBULA`, `RfN`, `DrkN`, `Neb`). Ausgeschlossen sind damit u. a. `Dup`, `NonEx`, `*`, `**`, `*Ass`.
2. `identifier`: Messier schlägt NGC schlägt IC (`M31`, sonst `NGC 224` ohne führende Nullen, sonst OpenNGC-Name wie `B033`, `ESO056-115`). `catalog_ids`: eigene Nummer, M/NGC/IC-Querverweise, Spalte `Identifiers`; entdoppelt, ohne die primäre ID. Bei doppelter ID gewinnt der erste Eintrag.
3. `name`: deutscher Name aus `german_names.json` (Schlüssel = primäre ID), sonst erster OpenNGC-„Common name"; übrige Namen → `alternativeNames`. `Se1`/`Se2` werden zu `Ser`.
4. `merge_nebulae` für Sh2 (`EMISSION_NEBULA`, Größe `Diam`), Barnard (`DARK_NEBULA`, `Diam`, ID `B <nr>`) und LBN (`NEBULA`, `Diam1`/`Diam2`): Liegt innerhalb `DUPLICATE_RADIUS_ARCMIN = 12.0` schon ein Objekt (räumlicher Index `DuplicateIndex` in 1°-Deklinationsbändern), wird nur die Bezeichnung an dessen `catalogIds` angehängt; sonst neuer Eintrag mit per `ConstellationBoundaries` berechnetem Sternbild.
5. Sortierung nach Helligkeit (fehlend = 99), Ausgabe kompakt, Statistik „neu / als weitere Bezeichnung".

#### [`scripts/import_constellations.py`](scripts/import_constellations.py)

Erzeugt `constellations.json`. **Voraussetzung:** `stars.json` muss bereits gebaut sein. **Eingaben:** `constlines.json` (Linienzüge) und `const.json` (Namen inkl. deutscher Form) aus d3-celestial (BSD-3-Clause).
**Aufruf:** `python3 scripts/import_constellations.py constlines.json const.json`

Ablauf: GeoJSON-Längen werden in RA 0–360° umgerechnet; Punkte näher als `SAME_POINT_DEG = 0.05` werden zu einem Stern zusammengelegt, aufeinanderfolgende Punkte eines Zugs zu Indexpaaren. Namen der Figursterne: hellster Stern aus `stars.json` (nur ≤ 6,0 mag) innerhalb `NAME_MATCH_DEG = 0.15`; ohne Eigennamen die Bayer-Bezeichnung (erste `catalogId`, die nicht mit Ziffer, `H` oder `S` beginnt), sonst die HR-ID. Die zwei Features der Schlange (`Ser`) werden mit Indexversatz zu einer Figur vereinigt; Figuren mit weniger als 2 Sternen entfallen. Ausgabe mit `indent=1`.

#### [`scripts/constellation_boundaries.py`](scripts/constellation_boundaries.py)

Hilfsmodul (wird von `import_bsc.py` und `import_deepsky.py` importiert): `ConstellationBoundaries(path).of(ra, dec)` liefert das IAU-Kürzel zu einer J2000-Position nach Roman (1987). Die Position wird mit der IAU-1976-Präzession (`_precession_matrix`) nach **B1875** gedreht (`to_b1875`), weil die Grenzen dort Rechtecke aus Stunden- und Deklinationskreisen sind; dann gewinnt die erste Zeile der nach Deklination absteigend sortierten Tabelle mit `dec >= dec_low` und `ra_low <= ra < ra_high` (Tabellen-RA in Stunden, intern ×15).

#### [`scripts/german_names.json`](scripts/german_names.json) und [`scripts/german_star_names.json`](scripts/german_star_names.json)

Handgepflegte Namenstabellen `{"comment": …, "names": {…}}`, die einen Neuimport überleben sollen. `german_names.json`: Schlüssel = primäre Deep-Sky-ID (`M31`, `NGC 7000`, `IC 434`, `LMC` …), Wert = deutscher Name (~55 Einträge). `german_star_names.json`: Schlüssel = IAU-Name (`Vega`), Wert = deutsche Schreibung (`Wega`); nur Abweichungen (11 Einträge). Die englische Form bleibt in `alternativeNames` suchbar (abgesichert in `SerializationTest` „the hand written German names survive the import").

#### Typische Änderungen in diesem Bereich
- **Katalog neu erzeugen:** Quelldateien laut Docstring des jeweiligen Skripts in ein separates Verzeichnis herunterladen; Reihenfolge `import_bsc.py` → `import_deepsky.py` → `import_constellations.py` (letzteres liest `stars.json`). Die Skripte schreiben direkt nach `app/src/main/assets/catalog/`. Danach die JVM-Tests laufen lassen: `SerializationTest` (inkl. `ConstellationDataTest`, `DeepSkyCatalogTest`) prüft Parsebarkeit, eindeutige IDs, Wertebereiche, keine Sterne im Deep-Sky-Katalog, keine ID-Überschneidung, Pflichtobjekte (M31 mit deutschem Namen, NGC 7000, IC 434, M42, M51), Bayer-Bezeichnungen und die Doppelstern-Regel. `SeasonalHighlightsTest` liest ebenfalls die Assets.
- **Deutschen Namen ergänzen:** Eintrag in `scripts/german_names.json` (Deep Sky, Schlüssel = primäre ID) bzw. `scripts/german_star_names.json` (Stern, Schlüssel = IAU-Name), dann das jeweilige Skript neu laufen lassen. Ein direktes Bearbeiten der Assets geht beim nächsten Import verloren.
- **Neuen Deep-Sky-Zusatzkatalog:** VizieR-TSV mit `_RAJ2000`/`_DEJ2000` laden, in `main()` von `import_deepsky.py` einen weiteren `merge_nebulae`-Aufruf mit Bezeichnungs-, Typ- und Größenfunktion ergänzen und den `license`-Text erweitern.
- **Neue Objektart aus OpenNGC übernehmen:** Kürzel in `TYPE_MAP` eintragen (ggf. neuen `ObjectType` in Kotlin anlegen, siehe `data/catalog`).

---

## 7. Tests

### 7.1 Was es gibt und wie man es benutzt

* **462 JVM-Unit-Tests** in 30 Dateien unter [`app/src/test/java/com/starwindow/app/`](app/src/test/java/com/starwindow/app/),
  JUnit 4 mit `kotlin.test`-Assertions. Ausführen: `gradlew :app:testDebugUnitTest` (in Android Studio
  auch per Rechtsklick auf Datei oder Klasse).
* Es gibt **keine** instrumentierten Tests und keine UI-Tests. `ui/*`, Sensor- und Kameraanbindung,
  Alarme und Benachrichtigungen sind nur kompiliert, nicht automatisch geprüft. Das Modul
  `:baselineprofile` misst Leistung, prüft aber keine Fachlogik.
* Die Tests prüfen **physikalische Invarianten** statt zufälliger Rückgabewerte (Zenit = Breitengrad,
  Drehung erhält Winkel, Rückkehr nach einem Sterntag, Meeus-Rechenbeispiele …). Schlägt einer fehl, ist
  meistens die Physik verletzt – den Test zu lockern ist fast nie die richtige Reaktion
  ([DEV_PLAN.md §6](DEV_PLAN.md)).
* Alle Dateien liegen flach im Paket `com.starwindow.app` und sind nach **Themen** benannt; eine Datei
  enthält mehrere Testklassen. Für `--tests` braucht man den Klassennamen aus der Tabelle unten, nicht
  den Dateinamen.
* Einige Tests lesen die echten Katalogdateien aus `src/main/assets/catalog/` (`SerializationTest`,
  `SeasonalHighlightsTest`, `ObjectDescriptionTest`). Sie erwarten als Arbeitsverzeichnis das Modul
  `app/` (bei Gradle der Standard); `ObjectDescriptionTest` hat dafür keinen Ausweichpfad.
* Tests für `suspend`-Funktionen (Durchgangsrechnung) laufen mit `runBlocking`.

### 7.2 Welche Datei was absichert

| Datei | Testklassen | Tests | Sichert ab |
|---|---|---:|---|
| `AstroMathTest.kt` | `AstroTimeTest`, `CoordinateTransformsTest` | 11 | Julianisches Datum, GMST/LST, Horizont ↔ Äquator (Zenit, Pol, Winkelerhalt, Kulmination), Refraktion |
| `PrecessionTest.kt` | `PrecessionTest` | 10 | IAU-2006-Präzession: Rate, Drehung, Ekliptikpol, Polaris, Wächter gegen rohe J2000-Koordinaten in der Suche |
| `EphemerisTest.kt` | `SolarEphemerisTest`, `LunarEphemerisTest`, `TwilightTest` | 17 | Sonne/Mond gegen Meeus-Beispiele, Beleuchtung, Parallaxe, Dämmerung Berlin/Zürich/Tromsø |
| `GeometryTest.kt` | `SphericalGeometryTest`, `WindowShapeTest` | 17 | Winkelabstände, Flächen, Tangentialebene, Fensterformen inkl. 0°-Naht und konkaver Polygone |
| `ProjectionTest.kt` | `DeviceAttitudeTest`, `SkyProjectionTest`, `CameraIntrinsicsTest` | 15 | Bildschirm ⇄ Himmel als exakte Umkehrung, Lage, Brennweite aus Kameradaten |
| `CalibrationTest.kt` | `Rotation3Test`, `AttitudeCorrectionTest`, `AttitudeFitTest`, `FieldOfViewSolverTest`, `CalibrationModelTest` | 32 | Drehungen, Lagekorrektur, Ausgleich aus Sternpaaren (auch mit Rauschen), Schwenk-Löser mit Ablehnung |
| `AttitudeFusionTest.kt` | `SmoothingTest`, `HeadingFusionTest`, `MagneticHealthTest` | 16 | Glättung unabhängig von der Abtastrate, Heading-Filter gegen Rauschen und Ausreißer, Magnetfeld-Prüfung |
| `MagneticDirectionTest.kt` | `InclinationTest`, `FieldDirectionDisturbanceTest`, `HeldHeadingDriftTest` | 11 | Feldneigung, Störung der Feldrichtung bei gleicher Feldstärke, Drift bei gehaltener Richtung |
| `TargetIndicatorTest.kt` | `TargetIndicatorTest` | 10 | Zielpfeil bleibt auf dem Bildschirm und außerhalb der Bedienbalken, Ziel im Rücken |
| `TransitTest.kt` | `TransitCalculatorTest`, `TransitDeclinationFilterTest` | 11 | Durchgangsrechnung: Zeiten, Sterntag, Verfeinerung unter die Schrittweite, Deklinations-Vorfilter |
| `EphemerisTransitTest.kt` | `EphemerisObjectTest`, `MovingTransitTest`, `DarkSpansTest`, `TransitConditionsTest` | 15 | Sonne/Mond als Katalogobjekte, Durchgang mit Eigenbewegung, Dunkelheitsabschnitte, Bedingungen je Durchgang |
| `ConstellationAndTrackTest.kt` | `ConstellationTransitTest`, `SkyTrackTest`, `IntervalScannerRegressionTest` | 11 | Sternbild-Durchgänge, Laufbahnen, Bisektion |
| `TransitListViewTest.kt` | `TransitListViewTest` | 11 | Sortierung und Gruppierung der Durchgangsliste |
| `FilterAndSpanTest.kt` | `ResultFilterTest`, `SkyObjectDataTest`, `AltitudeSpanTest` | 10 | Ergebnisfilter, Katalogdaten, Höhenspannen |
| `WindowQualityTest.kt` | `SelfIntersectionTest`, `WindowAccuracyTest`, `CalibrationTrustTest`, `ObserverDistanceTest` | 21 | Schleifenerkennung, Fenstergenauigkeit, ortsfremde Kalibrierung, Entfernungen über die Datumsgrenze |
| `OverlaySelectionTest.kt` | `OverlaySelectionTest` | 11 | Was der Sucher zeichnet (Grenzen, Sterne, Fotoziele) |
| `ObjectSearchTest.kt` | `ObjectSearchFoldingTest`, `ObjectSearchRankingTest`, `ObjectSearchSortTest`, `TonightBoardTest` | 25 | Normalisierung („M31" = „M 31"), Ranking, Sortierung, Tafel „heute Nacht" |
| `ObjectDescriptionTest.kt` | `ObjectDescriptionTest`, `ObjectNotesAssetTest` | 13 | Erzeugte Objekttexte, Inhalt von `object_notes.json` |
| `FeasibilityTest.kt` | `SkyConditionsTest`, `FeasibilityTest`, `PhotographicInterestTest`, `CatalogFilterTest` | 22 | Beobachtbarkeit, Fotointeresse, Katalogfilter |
| `ObservationPlannerTest.kt` | `ObservationPlannerTest` | 19 | Jahresplanung: beste Nächte, Saison |
| `SeasonalHighlightsTest.kt` | `SeasonTest`, `PopularityTest`, `SeasonalHighlightsTest` | 14 | Saison, Beliebtheit, Motive der Jahreszeit (liest `deepsky.json`) |
| `PhotoGuideTest.kt` | `PhotoGuideFilterTest`, `PhotoGuideExposureTest`, `PhotoGuideFramingTest`, `PhotoGuideDewTest`, `PhotoGuideVerdictTest`, `FavoritesFilterTest` | 24 | Fotoguide: Filter, Belichtung, Bildausschnitt, Tau, Urteil; Favoritenfilter |
| `AstroWeatherTest.kt` | `WeatherHourTest`, `AstroWeatherRatingTest`, `AstroNightTest`, `BortleScaleTest`, `OpenMeteoTest` | 43 | Nachtbewertung, Bortle-Schätzung, Open-Meteo-Parser und `meta.json`, Modellliste |
| `StargazingRatingTest.kt` | `StargazingRatingTest` | 6 | Bewertung einer Nacht fürs Beobachten (`AstroWeather`) |
| `NightOutlookTest.kt` | `NightOutlookTest`, `WatchEvaluatorTest`, `UsableWindowTest` | 14 | Nachtauskunft für Erinnerungen, Merklisten-Entscheidung |
| `ReminderTest.kt` | `ReminderLeadTest`, `PlannedSessionReminderTest`, `ReminderSchedulerKeyTest` | 13 | Erinnerungszeitpunkte, Zeitzonen, eindeutige Request-Codes |
| `PlanningPersistenceTest.kt` | `PlannedSessionTest` | 6 | Termine im JSON-Umlauf |
| `PlannedPathTest.kt` | `PlannedPathTest` | 4 | Pfad einer geplanten Nacht |
| `SerializationTest.kt` | `SerializationTest`, `CalibrationSerializationTest`, `ConstellationDataTest`, `DeepSkyCatalogTest`, `SkyImageRequestTest` | 24 | JSON-Umlauf von Fenstern/Kalibrierung, Datentests für `constellations.json` und `deepsky.json`, Bild-URL |
| `MoonPhaseDiscTest.kt` | `MoonPhaseDiscTest` | 6 | Geometrie der gezeichneten Mondphase (reine Rechnung aus `ui/weather`) |

### 7.3 Neue Tests schreiben

* Rechenlogik gehört in `core/astro`, `core/geometry`, `core/calibration` oder `domain` – dann ist sie ohne
  Weiteres testbar. Eine neue Testklasse kann in eine passende Themendatei oder in eine neue Datei.
* Lieber eine Invariante prüfen („nach einem Sterntag wieder im Fenster") als einen ausgerechneten Wert,
  den man aus dem Code abgeschrieben hat.
* Für Datenformate einen Umlauftest anlegen (serialisieren → deserialisieren → gleich) und einen Test,
  dass eine **alte** Datei ohne das neue Feld noch lädt (Vorbild: `ReminderTest`, „an entry without a
  reminders field still loads").

---

## 8. Kochbuch: typische Änderungen und Fehlersuche

Jeder Paketabschnitt in Kapitel 5 endet mit „Typische Änderungen in diesem Bereich". Hier stehen die
**übergreifenden** Rezepte, eine Fehlersuche nach Symptom und die wichtigsten Stellschrauben.

### 8.1 Übergreifende Rezepte

**Einen neuen Bildschirm anlegen**
1. `XxxViewModel` mit `XxxUiState` (eine `data class`) und `companion object { fun factory(container: AppContainer) = viewModelFactory { initializer { XxxViewModel(container.a, container.b) } } }`.
   Fehlende Abhängigkeiten als `val` im `AppContainer` ergänzen.
2. `XxxScreen(viewModel, onBack, …)`: Zustand mit `collectAsStateWithLifecycle()` lesen, Navigation nur über
   Callback-Parameter, nie über den `NavController`.
3. In `ui/nav/StarWindowNavHost.kt`: Konstante in `Routes` (bei Argumenten zusätzlich eine Bauhilfe mit
   `Uri.encode`), `composable(...)`-Block mit `viewModel(factory = …, key = id)`.
4. Soll es ein Reiter werden: Eintrag in `BottomDestination`, danach `BOTTOM_TABS` in
   `baselineprofile/…/Journeys.kt` ergänzen.

**Eine neue Einstellung**
Feld mit Standardwert in `Settings` (`data/windows/SettingsStore.kt`) → Setter über `update { it.copy(…) }`
→ im ViewModel aus `settingsStore.settings` lesen und den Setter aufrufen → Bedienelement im passenden
Dialog (Sucher: `CaptureSettingsDialog`). Die Einstellungen sind automatisch im Backup.
Slider erst in `onValueChangeFinished` speichern, sonst wird bei jedem Zwischenwert der ganze Block geschrieben.

**Ein neues gespeichertes Datenobjekt (neue Datei)**
Vorlage ist `PlanRepository`: `MutableStateFlow` + `Mutex`, alles auf `Dispatchers.IO`, Schreiben über
`<name>.tmp` + `renameTo`, unlesbare Datei → `<name>.corrupt`. Instanz im `AppContainer` anlegen, `load()`
im ViewModel aufrufen (die Repositories laden nicht selbst). Soll die Datei ins Backup, in
`res/xml/backup_rules.xml` **und** `res/xml/data_extraction_rules.xml` (beide Abschnitte) eintragen. Tests:
Umlauf und „alte Datei lädt". DEV_PLAN §11.4 S5 schlägt dafür einen gemeinsamen `JsonFileStore<T>` vor.

**Ein neues Feld in einem gespeicherten Modell** (`SkyWindow`, `PlannedSession`, `WatchedObject`, `Settings`, `SkyObject` …)
Feld mit Standardwert; nichts umbenennen; Umlauftest ergänzen. Soll ein Termin-Feld in der Erinnerung
erscheinen, muss es zusätzlich als Intent-Extra in `ReminderScheduler` und `ReminderNotice.from`.

**Eine neue Objektart (`ObjectType`)**
Der Compiler führt durch, weil mehrere `when`-Ausdrücke erschöpfend sind: `ObjectType.label`,
`ui/components/ObjectSymbol.drawObjectSymbol`, `ui/theme/ObjectPalette.colorFor`,
`domain/ObjectDescription.whatItIs`. Von Hand prüfen: `ObjectType.isStar`/`respondsToNarrowband`,
`ResultFilter.matches` (Gruppen), `PhotographicInterest` (Typpunkte), `TargetKind.of` (Hub),
`TYPE_MAP` in `scripts/import_deepsky.py`.

**Ein neuer Himmelskörper mit eigener Bewegung (z. B. Planeten)**
Ephemeride in `core/astro/Ephemeris.kt` (Ergebnis `Equatorial` zum Datum), Enumwert in
`data/catalog/EphemerisBody` mit `positionAt`, Eintrag in `EphemerisCatalog`. Alles, was `isMoving`
abfragt, behandelt ihn dann automatisch richtig. Achtung: Laufbahnen (`SkyTrackBuilder`) rechnen mit fester
Position ([Kapitel 9](#9-auffälligkeiten-und-bekannte-baustellen)).

**Texte ändern**
Alle Oberflächentexte stehen im Kotlin-Code (`strings.xml` enthält nur `app_name`). Per Volltextsuche
finden. Vorher in `baselineprofile/…/Journeys.kt` nachsehen, ob der Text dort gesucht wird (Reiternamen,
„Bildfeld", der Suchfeld-Platzhalter, „Andromeda").

**Katalog aktualisieren oder deutschen Namen ergänzen**
Siehe [Assets und Import-Skripte](#64-assets-und-import-skripte-die-kataloge-und-wie-sie-entstehen): Namen in
`scripts/german_names.json` bzw. `german_star_names.json`, Skripte in der Reihenfolge `import_bsc.py` →
`import_deepsky.py` → `import_constellations.py`, danach `SerializationTest` laufen lassen.

**Mathematik ändern**
Nur mit Test. Erst den passenden Test in Kapitel 7 suchen, die Invariante verstehen, dann ändern. Ein
fehlschlagender Invariantentest heißt meistens: Die Physik ist verletzt, nicht der Test zu streng.

### 8.2 Fehlersuche: Symptom → wo nachsehen

| Symptom | Wahrscheinliche Ursache | Wo nachsehen |
|---|---|---|
| Alle Markierungen im Sucher liegen um denselben Winkel seitlich daneben | Kompassfehler oder Missweisung | `DeviceAttitude.toTrueNorth`; die Missweisung braucht einen Standort (`OrientationTracker.updateLocation` aus `CaptureViewModel.applyObserver`). Abhilfe für Nutzer: Kalibrierung „Peilung" oder „Sternmuster" |
| In der Bildmitte stimmt es, am Rand nicht; Markierungen wandern beim Schwenken schneller oder langsamer als das Bild | Bildfeld (Brennweite) falsch | `CameraIntrinsics.fromCharacteristics`, `PreviewFit` gegen `PreviewView.scaleType` (`FIT_CENTER`), `PreviewStreamInfo.rotationDegreesFor`, `Calibration.fovScale`; Schwenk-Kalibrierung |
| Alles um etwa 0,4° versetzt | Präzession fehlt | Irgendwo werden `raDeg`/`decDeg` direkt statt `SkyObject.positionAt(precession)` benutzt (`PrecessionTest` hat einen Wächter für die Suche) |
| Im Querformat falsch, im Hochformat richtig | Displaydrehung | `OrientationTracker.remapForDisplay`, `rememberDisplayRotationDegrees`, `rotationDegreesFor` |
| Overlay zittert im Stillstand oder hängt beim Schwenken nach | Glättung | Zeitkonstanten in `AttitudeFusion` (`STILL_TIME_CONSTANT_S`, `FAST_TIME_CONSTANT_S`) |
| Overlay ruckelt beim Schwenken | Sensoren auf dem Main-Thread, Astronomie in jedem Bild | DEV_PLAN §11.3 P1 und P2 (`OrientationTracker.attitudes`, `SkyOverlay.drawCatalog`) |
| Warnung „Nord gehalten"/„Magnetstörung" erscheint ständig | Magnetfeld weicht vom Modell ab | `AttitudeFusion.isFieldPlausible`, `isInclinationPlausible`, `OrientationTracker.updateHeading` |
| Ein Objekt fehlt in der Durchgangsliste eines Fensters | Vorfilter, Zeitraum oder Listenfilter | `CatalogRepository.objectsVisibleFrom` (Grenzgröße; ohne Helligkeit nur ab 3′), `TransitCalculator` (`DECLINATION_MARGIN_DEG`, 60-s-Raster: kürzere Durchgänge gehen verloren), Filter „nur nachts", `ResultFilter`, Zeitraum-Schieber |
| Durchgangszeit des Mondes passt nicht zur gezeichneten Bahn | Bahn mit fester Mondposition | `SkyTrackBuilder` (Kapitel 9) |
| Suche findet ein Objekt nicht | Schreibweise oder Filter | `ObjectSearch.fold`/`score`; mit aktivem Filter greift erst die 200er-Grenze, dann der Filter (Kapitel 9) |
| Erinnerung kommt nicht | Berechtigung, Alarm nicht gesetzt, Hersteller-Akkusparen | `ReminderNotifications.canPost`; `ReminderScheduler.reschedule` setzt nur Vorläufe in der Zukunft; Kalender einmal öffnen (`rescheduleAll`); nach Neustart `BootReceiver`; Logcat `ReminderScheduler` |
| Erinnerung kommt, aber ohne Wetterzeile | kein Wetterort oder Nacht außerhalb der Vorhersage | `NightWeatherSource.place` (nur Wetterort oder manueller Ort, **kein** GPS); `NightOutlooks.forDate` liefert `null` jenseits der Reichweite |
| Merkliste meldet sich nie | Ort, Dunkelheit, Ruhezeit, Kriterien, unterbrochene Kette | `WatchCheckReceiver.check` (ohne Wetterort Abbruch), `WatchEvaluator` (ohne astronomische Dunkelheit nie), `WatchedObject.mayNotifyFor`, `minAltitudeDeg`/`minUsableHours`/`weatherDemand`; Kalender öffnen setzt die Kette neu |
| Wetter: „außerhalb des Modellgebiets" oder keine ganze Nacht | Kurzfristmodell deckt den Ort oder die Nacht nicht | `OutsideModelAreaException`, `OpenMeteo.trimTrailingGap`, Modell mit größerem Gebiet wählen |
| Wetter lädt nicht | Netz, Dienst lehnt ab | `WeatherRepository.forecast`/`httpGet` (Fehlertext des Dienstes wird übernommen), Logcat `WeatherRepository` |
| Nach einem Update sind gespeicherte Daten weg | Datei war nicht lesbar und wurde umbenannt | `filesDir/*.corrupt` ansehen (`adb shell run-as com.starwindow.app ls files`); meist ein neues Feld ohne Standardwert oder ein umbenanntes Feld |
| Nur im Release-Build: Absturz oder leere Daten beim Laden | R8 hat etwas entfernt | `app/proguard-rules.pro`, Release-Build lokal testen |
| Benchmark findet einen Knopf nicht | Text geändert | `baselineprofile/…/Journeys.kt` |

### 8.3 Die wichtigsten Stellschrauben

| Was | Konstante | Datei | Wert |
|---|---|---|---|
| Sucher: Objekte gesamt / davon Sterne / Sterngrenze / Mindestpunktzahl Deep Sky | `MAX_OBJECTS`, `MAX_STARS`, `STAR_MAGNITUDE_LIMIT`, `MIN_TARGET_SCORE` | `domain/OverlaySelection.kt` | 400 / 150 / 4,2 mag / 35 |
| Sucher: Punkte je Polygonfenster | `MAX_POLYGON_ANCHORS` | `ui/capture/CaptureViewModel.kt` | 32 |
| Durchgänge: Abtastschritt / Bisektionsschritte | `stepSeconds`, `refineIterations` (Konstruktor) | `domain/TransitCalculator.kt` | 60 s / 12 (Sonne/Mond 30 s) |
| Durchgänge: Zuschlag im Deklinations-Vorfilter | `DECLINATION_MARGIN_DEG` | `domain/TransitCalculator.kt` | 1,5° |
| Durchgang gilt als „dunkel" | `FULLY_DARK_FRACTION`, `DARK_SUN_ALTITUDE_DEG` | `TransitCalculator.kt`, `DarkSpans.kt` | 90 % der Dauer / Sonne ≤ −18° |
| „Mond stört" | `moonInterferes` | `TransitCalculator.kt`, `ObservationPlanner.kt`, `Feasibility.kt` | Mond über Horizont und ≥ 30 % (in `Feasibility` ≥ 25 %) |
| Jahresplanung: Mindesthöhe / Mindestzeit für Saison | `MIN_USEFUL_ALTITUDE_DEG`, `MIN_WORTHWHILE_MILLIS` | `domain/ObservationPlanner.kt` | 30° / 45 min |
| Hub, Guide, „Heute Nacht"-Tafel: Mindesthöhe | `MIN_ALTITUDE_DEG` | `SeasonalHighlights.kt`, `PhotoGuideViewModel.kt`, `TonightBoard.kt` | je 20° |
| Nachtbewertung: „klar" / brauchbare Stunde / gute bzw. teils gute Nacht | `CLEAR_CLOUD_PERCENT`, `USABLE_SCORE`, `GOOD_NIGHT_MILLIS`, `PARTLY_NIGHT_MILLIS` | `domain/AstroWeather.kt` | 30 % / 50 / 90 min / 45 min |
| Dämmerungsschwellen | `TwilightPhase`, `forSunAltitude`, Literale in `Twilight.between` | `core/astro/Twilight.kt` | −0,833° / −6° / −12° / −18° (an **drei** Stellen) |
| Erinnerung: Uhrzeit | `ReminderLead.NOTIFY_TIME` | `data/planning/Reminder.kt` | 17:00 |
| Merkliste: Prüfzeit / Wiederholabstand / Wiederholungen | `CHECK_TIME`, `RETRY_DELAY_MILLIS`, `MAX_ATTEMPTS` | `data/planning/WatchScheduler.kt` | 15:00 / 45 min / 2 |
| Merkliste: Standardwerte | `DEFAULT_MIN_ALTITUDE_DEG`, `DEFAULT_MIN_HOURS`, `DEFAULT_QUIET_NIGHTS` | `data/planning/WatchedObject.kt` | 30° / 1 h / 3 Nächte |
| Wetter: Cachedauer Vorhersage / Modellstatus / Orte im Speicher / Läufe auf der Platte | `CACHE_TTL_MILLIS`, `STATUS_TTL_MILLIS`, `MAX_CACHED_PLACES`, `MAX_ENTRIES` | `WeatherRepository.kt`, `ForecastCache.kt` | 30 min / 5 min / 8 / 3 |
| Wetter: Netz-Timeouts | `CONNECT_TIMEOUT_MS`, `READ_TIMEOUT_MS` | `data/weather/WeatherRepository.kt` | 8 s / 15 s |
| Bilder: Timeouts / Speichercache | `CONNECT_TIMEOUT_MS`, `READ_TIMEOUT_MS`, `MEMORY_CACHE_SIZE` | `data/images/SkyImageLoader.kt` | 5 s / 20 s / 32 Bilder |
| Kalibrierung gilt als älter / veraltet / ortsfremd | `AGING_MILLIS`, `STALE_MILLIS`, `MOVED_KM` | `core/calibration/CalibrationTrust.kt` | 2 Tage / 14 Tage / 5 km |
| Schwenk-Kalibrierung: Mindestschwenk / Mindestabstand / größter Restfehler | `MIN_PAN_ANGLE_DEG`, `MIN_SCREEN_SPAN_PX`, `MAX_RESIDUAL_DEG` | `core/calibration/FieldOfViewSolver.kt` | 4° / 80 px / 1,5° |
| Sensorglättung still / schnell / Kursfilter | `STILL_TIME_CONSTANT_S`, `FAST_TIME_CONSTANT_S`, `HEADING_TIME_CONSTANT_S` | `core/sensors/AttitudeFusion.kt` | 0,30 s / 0,02 s / 10 s |
| Suche: Entprellung / Trefferzahl | `DEBOUNCE_MILLIS`, Parameter `limit` | `ObjectSearchViewModel.kt`, `ObjectSearch.kt` | 180 ms / 200 |
| Dämmerungs-Abtastung | `SAMPLE_STEP_MILLIS` | `core/astro/Twilight.kt` | 5 min |

---

## 9. Auffälligkeiten und bekannte Baustellen

Diese Punkte sind beim Durcharbeiten des Codes aufgefallen (Stand Commit `ec26150`). Unter „Bestätigt"
steht nur, was am Code nachgeprüft wurde. Die geplanten Umbauten zu Performance und Struktur stehen
ausführlich in [DEV_PLAN.md §11](DEV_PLAN.md) (P1–P6, S1–S11) und werden hier nur wiederholt, wo ein
konkreter Fehler daraus folgt.

### 9.1 Bestätigte Fehler

| # | Wo | Was passiert | Möglicher Fix |
|---|---|---|---|
| F1 | [`ui/guide/PhotoGuideScreen.kt`](app/src/main/java/com/starwindow/app/ui/guide/PhotoGuideScreen.kt), `verdictExplanation` und `framingExplanation` (Fall `TOO_SMALL`) | Der Text wird mit `"… %.0f Pixeln …" + "…" + "…".format(...)` gebaut. `.format` bindet stärker als `+` und wirkt nur auf das **letzte** Stück, deshalb erscheinen `%.0f` bzw. `%.1f` wörtlich in der Oberfläche. | Den ganzen Ausdruck klammern: `("…" + "…" + "…").format(...)` oder String-Templates verwenden |
| F2 | [`ui/search/ObjectSearchViewModel.kt`](app/src/main/java/com/starwindow/app/ui/search/ObjectSearchViewModel.kt), `recompute` | `ObjectSearch.search` liefert höchstens 200 Treffer (`limit`), die Filter aus `CatalogFilter.matches` (Art, Helligkeit, Größe, Sternbild, Höhe, Machbarkeit) greifen erst **danach**. Bei leerer Eingabe und z. B. nur einem Sternbildfilter fehlen deshalb viele passende Objekte. Für Favoriten ist das bereits gelöst (Kommentar im Code). | Filter, die keine Höhe brauchen, vor die Suche ziehen; für die übrigen `limit` erst nach dem Filtern anwenden |
| F3 | [`data/planning/WatchCheckReceiver.kt`](app/src/main/java/com/starwindow/app/data/planning/WatchCheckReceiver.kt), `check` | Der Receiver legt ein **eigenes** `WatchlistRepository` an. Läuft die Prüfung, während die App offen ist, setzt `markNotified` das Datum nur in dieser Instanz und in der Datei; die Instanz im `AppContainer` behält den alten Stand. Die nächste Änderung an der Merkliste in der App schreibt dann das alte `lastNotifiedEpochDay` zurück, und die Ruhezeit geht verloren. | `context.appContainer.watchlistRepository` verwenden (DEV_PLAN S2) oder nach dem Receiver-Lauf in der App neu laden |
| F4 | [`ui/components/SkyImage.kt`](app/src/main/java/com/starwindow/app/ui/components/SkyImage.kt), `rememberSkyImage` | Für Sonne und Mond (`isMoving`) sind `raDeg`/`decDeg` Platzhalter (0/0). Infoblatt und Objektblatt fordern trotzdem ein Bild an und zeigen so einen DSS-Ausschnitt bei RA 0°, Dek 0° als „Bild" des Mondes oder der Sonne. | Für `obj.isMoving` kein Bild laden (oder ein eigenes Symbol zeigen) |
| F5 | [`ui/capture/CaptureScreen.kt`](app/src/main/java/com/starwindow/app/ui/capture/CaptureScreen.kt), `PermissionPlaceholder` | Der Knopf „Berechtigungen erteilen" fragt `CAMERA` und `ACCESS_FINE_LOCATION` an, aber **ohne** `ACCESS_COARSE_LOCATION`. Ab Android 12 ignoriert das System eine reine FINE-Anfrage; die Erstanfrage beim Start enthält beide. | `ACCESS_COARSE_LOCATION` ergänzen |
| F6 | [`data/weather/WeatherRepository.kt`](app/src/main/java/com/starwindow/app/data/weather/WeatherRepository.kt), `modelStatuses`/`modelStatus` | `statusCache` ist eine unsynchronisierte `HashMap`, in die sechs parallele `async`-Koroutinen auf `Dispatchers.IO` schreiben (Data Race). Auch `cache` ist nicht synchronisiert. | `ConcurrentHashMap` oder ein `Mutex` |
| F7 | `SkyTrackBuilder` ([`domain/SkyTrack.kt`](app/src/main/java/com/starwindow/app/domain/SkyTrack.kt)) mit Aufrufern in `WindowDetailViewModel`, `ObjectDetailViewModel`, `CaptureViewModel` | Laufbahnen und Höhenkurven werden mit **einer festen** Äquatorposition gerechnet. Für den Mond (≈ 0,55°/h Eigenbewegung) liegt die gezeichnete Bahn damit über Stunden mehrere Grad neben der Rechnung des `TransitCalculator`, der den Mond korrekt zeitabhängig rechnet. | Variante mit `(Long) -> Equatorial`, analog `SkyObject.positionAtMillis` |
| F8 | [`ui/weather/WeatherViewModel.kt`](app/src/main/java/com/starwindow/app/ui/weather/WeatherViewModel.kt), `useCurrentLocation` | Die Koroutine ist nicht an `loadJob` gebunden. Wählt man einen Suchtreffer, während `PlaceLookup.describe` noch läuft, überschreibt das späte Ergebnis den gewählten Ort und speichert ihn. | Job merken und bei `selectPlace` abbrechen |
| F9 | [`ui/planning/PlanningScreen.kt`](app/src/main/java/com/starwindow/app/ui/planning/PlanningScreen.kt), `PlaceLine`/`NightRow` | Die Kopfzeile sagt „Zeiten in ‹Zeitzone des Planungsorts›", die Uhrzeiten kommen aber aus `formatClock` in der **Gerätezeitzone**. Fällt nur auf, wenn man für einen Ort in einer anderen Zeitzone plant. | Uhrzeit mit `state.zone` formatieren |
| F10 | [`ui/hub/StargazingHubScreen.kt`](app/src/main/java/com/starwindow/app/ui/hub/StargazingHubScreen.kt) | Ohne Standort ist auch `isEmpty` wahr. Dann erscheinen `NoLocationHint` **und** `NoNightHint` („wird nicht dunkel genug"); der zweite Hinweis ist falsch. | `NoNightHint` nur bei `hasLocation` |
| F11 | [`domain/Feasibility.kt`](app/src/main/java/com/starwindow/app/domain/Feasibility.kt), `assess` | `obj.sizeArcmin ?: 0.0` liegt im Bereich „winzig" (0 … 1,5′), deshalb wird jedes Objekt **ohne Größenangabe** `HARD`. Der Rückfall auf die Gesamthelligkeit dahinter greift nur für Objekte mit bekannter Größe. | Fehlende Größe nicht als 0 werten |
| F12 | [`core/astro/Coordinates.kt`](app/src/main/java/com/starwindow/app/core/astro/Coordinates.kt), `Angles.formatRa`/`formatDec` | Minuten werden nach `toInt()` mit `%04.1f` formatiert; 59,96′ erscheinen als „12h 60.0m" bzw. „41° 60.0'". | Erst runden, dann auf Stunden/Grad übertragen |
| F13 | [`core/sensors/OrientationTracker.kt`](app/src/main/java/com/starwindow/app/core/sensors/OrientationTracker.kt), `onAccuracyChanged` | Laut Kommentar soll nur die Genauigkeit des Kompasses zählen, übernommen wird aber die jedes Sensors außer `GAME_ROTATION_VECTOR`, also auch die des Magnetometers. | Auf den Kompass-Sensortyp prüfen |
| F14 | [`ui/windows/WindowDetailViewModel.kt`](app/src/main/java/com/starwindow/app/ui/windows/WindowDetailViewModel.kt), `setSort` | Ruft kein `rebuildTracks()` auf. Ohne Auswahl folgt die Beispielauswahl im Diagramm (die ersten 6 der Liste) der neuen Sortierung erst nach der nächsten anderen Änderung. | `rebuildTracks()` ergänzen |

### 9.2 Wahrscheinliche Probleme und Randfälle (nicht abschließend geprüft)

* **Backup:** Gesichert werden nur `sky_windows.json` und die Einstellungen
  (`res/xml/backup_rules.xml`, `data_extraction_rules.xml`). Termine, Merkliste, Favoriten und das
  verfolgte Ziel gehen bei einem Gerätewechsel verloren. Ob das Absicht ist, steht nirgends.
* **Konkurrierende Koroutinen ohne Abbruch:** `WindowDetailViewModel.search`,
  `CalendarViewModel.loadWeather` (startet bei *jeder* Terminänderung einen neuen Wetterabruf) und
  `PhotoGuideViewModel.setQuery` merken sich keinen Job; das zuletzt *fertige* Ergebnis gewinnt, nicht das
  zuletzt angeforderte.
* **Zeitbudget der Receiver:** `withTimeoutOrNull(20 s)` bzw. `(25 s)` kann einen blockierenden
  `HttpURLConnection`-Aufruf nicht abbrechen. Im schlimmsten Fall dauert ein Abruf 2 × (8 + 15) s.
* **Request-Codes der Erinnerungen:** `sessionId.hashCode() * 8 + ordinal` läuft über; Kollisionen werden
  dadurch wahrscheinlicher, und bei einer Kollision überschreibt `FLAG_UPDATE_CURRENT` den Alarm eines
  anderen Termins (bei wenigen Terminen praktisch irrelevant).
* **Repository-Änderungen vor `load()`:** `PlanRepository`/`WatchlistRepository`/`FavoritesRepository`
  bauen Änderungen auf `state.value` auf. Wird eine ändernde Methode vor dem ersten `load()` aufgerufen,
  wird eine leere Liste als Grundlage geschrieben.
* **`PlanRepository.add`** ersetzt einen Termin mit gleichem Objekt und Datum, bricht aber dessen Alarme
  nicht ab, wenn die `id` eine andere ist (die Oberfläche ruft `add` derzeit nur, wenn noch nichts geplant ist).
* **Zwei Dunkelheitsmodelle:** `ObservationPlanner` kennt nautische Nächte als brauchbar, `AstroWeather`
  nicht (`NO_DARKNESS`). Folge: Die Merkliste meldet sich in Mittsommernächten nördlich von etwa 49° nie,
  obwohl der Kalender dort Nächte vorschlägt.
* **Uneinheitliche Schwellen:** „Mond stört" ab 30 % (`TransitCalculator`, `ObservationPlanner`) bzw. 25 %
  (`Feasibility`); drei verschiedene Mondmodelle (`ObservationPlanner.score`, `AstroWeather.moonFactor`,
  `PhotoGuide.moonFactor`); zwei Bortle-Helligkeitstabellen (`SkyConditions.skyBrightness`,
  `PhotoGuide.SKY_BRIGHTNESS`); zwei Definitionen von „Fotoziel" (`ResultFilter.PHOTO` ab 3′,
  `PhotographicInterest.isPhotoTarget` ab 1,5′ mit Messier-Prüfung); Dämmerungsschwellen dreifach in `Twilight.kt`.
* **Ortsauflösung an drei Stellen verschieden:** `PlanningLocation.resolve` (Wetterort → manueller Ort →
  GPS), `NightWeatherSource.place` (ohne GPS) und eine Kopie in `PlannedPath.track`. Der Kalender kann
  also per GPS eine Nacht anzeigen, während die nächtliche Merklisten-Prüfung ohne Wetterort gar nicht läuft.
* **Zeitzonen:** Erinnerungen und die Merklisten-Kette rechnen in der Gerätezone, `WatchCheckReceiver`
  bestimmt „heute" in der Zone des Wetterorts, `PlannedPath` und die Wetteransicht in der Zone des Orts.
* **`ObjectSearchViewModel`:** Der Cache der Fensterdurchgänge (Restminuten) altert nicht; er wird nur beim
  Wechsel des Fensters geleert.
* **Bortle-Override an zwei Orten:** `Settings.bortleOverride` und `WeatherPlace.bortleOverride`;
  `PhotoGuideViewModel.setBortle` schreibt je nach vorhandenem Wetterort in den einen oder den anderen. Der
  Bortle-Slider im Guide schreibt bei jedem Zwischenwert und löst jedes Mal eine Neuberechnung des Hubs aus.
* **`ExposureCapabilities.frameDurationFor`** liefert `min(Belichtung, maxFrameDuration)`; ist die
  Belichtung länger als `maxFrameDurationNs`, wird die Bilddauer kürzer als die Belichtung, was der Kommentar
  ausschließen will.
* **Polygonfenster über eine Halbkugel hinaus:** Liegt eine Ecke ≥ 90° vom Schwerpunkt, liefert
  `PolygonWindow.membershipTest()` immer `false` (und `isSelfIntersecting` ebenfalls), ohne Warnung beim Speichern.
* **Sucher-Overlay:** `skyPath` verwirft die ganze Fensterkontur, sobald ein Punkt hinter der Kamera liegt;
  große Fenster verschwinden dann komplett. Der Zielpfeil in der Kalibrierung bekommt kein `chromeInsets` und
  kann unter dem Bedienfeld liegen.
* **`AstroWeatherChart`** formatiert mit `"EEE HH:mm"` ohne `Locale` (englischer Wochentag auf englisch
  eingestellten Geräten, DEV_PLAN P6).
* **Mondparallaxe** (bis ~1°) fehlt in Durchgangssuche und Overlay; nur `Twilight` rechnet die Mondhöhe topozentrisch.
* **Daten:** Laut README findet „Sh2-49" denselben Eintrag wie „M16". Im Asset hängt Sh2-49 aber an
  `IC 4703` („Eagle Nebula"), einem eigenen Eintrag; der Adlernebel steht damit doppelt im Katalog.

### 9.3 Toter oder halb verdrahteter Code

| Wo | Was |
|---|---|
| `SettingsStore.setMagnitudeLimit` | Kein Aufrufer. `Settings.magnitudeLimit` wird zwar vom Sucher gelesen, bleibt aber auf dem Standardwert 6,0 |
| `CatalogRepository.refresh`, `RemoteCatalogSource`, `SkyObject.matches`, `EphemerisCatalog.forBody`, `EphemerisBody.degreesPerHour` | keine Aufrufer im App-Code (`matches` nur in einem Test) |
| `SkyWindowRepository.exportJson`, `SkyImageLoader.clearCache`, `ForecastCache.clear`, `SmartTelescopes.narrowestFovDeg` | keine Aufrufer; `exportJson` wäre die fertige Grundlage für einen Fensterexport |
| `FieldOfViewSolver.isTrustworthy`, `Rotation3.headingOffsetDeg` (in `AttitudeFit.kt`), privates Feld `headingHeld` in `OrientationTracker` | ungenutzt |
| `DarkSpans.contains`, `NightOutlook.isStale`/`fullLine`, `WeatherDemand.acceptsPartly` | ungenutzt (teils nur in Tests) |
| `Design.kt`: `StatTile`, `ValueRow`; `WeatherScreen`: `NightHeadline`, `SectionTitle` | nie aufgerufen |
| `ReminderScheduler.EXTRA_OBJECT_ID` | wird gesendet, aber nirgends gelesen (Tippen auf eine Erinnerung öffnet nur die App) |
| `CalendarUiState.isWeatherLoading`, `CalibrationViewModel.consumeMessages`, `PanSample.screenX/screenY`, `ObjectHit.minutesLeftInWindow` in der Trefferliste | gesetzt, aber von der Oberfläche nicht ausgewertet |
| `WatchedObject.enabled` | wird geprüft, ist aber in der Oberfläche nicht einstellbar |
| `Theme.kt`: `LightScheme` | praktisch unbenutzt, weil `forceDark` immer `true` ist |
| `scripts/import_bsc.py`: Tabelle `GENITIVE`; `scripts/import_deepsky.py`: `optional()` | ungenutzt bzw. Duplikat von `number()` |
| Fehlende Funktion | Fenster umbenennen gibt es nirgends; Tippen auf Benachrichtigungen führt nicht in den Kalender |

### 9.4 Kommentare, die nicht mehr zum Code passen

* `CatalogRepository` („a few thousand entries"), `ObjectNotesRepository` und `ObjectDescription` („three
  thousand entries"), `SeasonalHighlights` („Dreizehntausend Einträge"): Der Katalog hat rund 22.500 Einträge.
* `Equatorial` (KDoc: J2000 und „of date" würden gleich behandelt) ist seit `Precession` überholt;
  `ObserverLocation.elevationM` wird entgegen dem KDoc für keine Refraktionskorrektur genutzt;
  `CoordinateTransforms.maxAltitudeDeg` liefert für nie aufgehende Objekte nicht −90, sondern irgendeinen
  negativen Wert.
* `DarkSpans.STEP_MILLIS`: „Die Sonne läuft in fünf Minuten gut ein Achtelgrad, Fehler unter zwei Minuten" –
  ohne Bisektion sind die Grenzen auf das 5-Minuten-Raster gerundet, der Fehler kann bis zu 5 min betragen.
* `Ephemeris.kt`: Die Phasengrenzen (0,02 / 0,30 / 0,60 / 0,96) sind keine „Achtelteilung".
* `WeatherModel.isOverdue`: Laut KDoc überfällig ab einem Intervall, der Code prüft das **doppelte** Intervall.
* `WatchScheduler`: „bis 17 Uhr" – die letzte Wiederholung ist um 16:30.
* `AstroWeather.dewFactor`: KDoc nennt 2 K, der Code nutzt 1 K und 3 K. `TonightBoard`: „zwölf Stunden",
  `RISING_HORIZON_HOURS` ist 6. `TwilightBand`: „ein Prozent der Breite", der Wert ist 0,6 %.
* `FieldOfViewSolver`: „mean", berechnet wird ein RMS-Wert. `SkyImageLoader`: Speichercache „ein paar hundert
  Kilobyte", real eher 20–40 MB.
* `WindowTrackChart.drawOrientationLabels`: „Drawn last", wird aber früh aufgerufen; der Hinweis „Osten →"
  steht immer rechts, unabhängig von der Blickrichtung des Fensters (ob das in jeder Richtung stimmt, ist ungeprüft).
* `scripts/german_names.json` verweist auf `scripts/import_openngc.py`; gemeint ist `import_deepsky.py`.
* `res/values/colors.xml`: `night` (`#07080F`) weicht von `StarWindowColors.Night` (`#0A0C16`) ab (Farbsprung beim Start möglich).

---

## 10. Glossar

| Begriff | Bedeutung im Projekt |
|---|---|
| **Fenster** (`SkyWindow`) | Eingezeichneter Himmelsausschnitt, horizontfest in Azimut/Höhe gespeichert. Formen: Polygon, Kreis, Az/Höhe-Rechteck (`WindowShape`) |
| **Durchgang** (`ObjectTransit`, `TransitInterval`) | Zeitspanne, in der ein Objekt durch ein Fenster zieht, mit Ein-/Austritt, bester Höhe, Dunkel- und Mondangaben |
| **Sucher** | Der Kamerabildschirm (`ui/capture`) mit Overlay; Startbildschirm der App |
| **Verfolgen / Ziel** (`TrackTarget`) | Ein Objekt oder Fenster, zu dem der Sucher einen Pfeil zeigt; optional mit Bahn über eine geplante Nacht („Pfad zeigen") |
| **Termin** (`PlannedSession`) | Ein Objekt in einer bestimmten Nacht, im Kalender, mit Erinnerungen und Notiz |
| **Merkliste** (`WatchedObject`) | Vorgemerktes Ziel ohne festes Datum; die App prüft täglich, ob es in der Nacht gut steht und klar wird |
| **Nacht** | Gehört zum Abend, an dem sie beginnt; angezeigt als „Nacht auf ‹Folgetag›". `Twilight.nightOf` rechnet von Mittag bis Mittag in der Zeitzone des Orts |
| **Azimut / Höhe** (`Horizontal`) | Richtung am lokalen Himmel: Azimut ab geografisch Nord über Ost, Höhe über dem Horizont, beides in Grad |
| **RA / Dek** (`Equatorial`) | Rektaszension und Deklination; im Code beide in Grad (`raDeg`, 1 h = 15°) |
| **J2000 / „zum Datum"** | Katalogkoordinaten gelten für das Jahr 2000. `Precession` dreht sie auf das aktuelle Datum (heute etwa 0,37° Unterschied) |
| **GMST / LST** | Mittlere Sternzeit Greenwich bzw. lokale Sternzeit (`AstroTime`); sie bestimmt, welcher Teil des Himmels gerade im Süden steht |
| **Stundenwinkel** | H = LST − RA; 0 = Meridiandurchgang (Kulmination) |
| **Refraktion** | Anhebung durch die Atmosphäre, am Horizont ~0,5°; `CoordinateTransforms.refractionDeg` (Bennett) |
| **Missweisung** (magnetische Deklination) | Winkel zwischen magnetisch und geografisch Nord am Standort; aus `GeomagneticField` in `OrientationTracker` |
| **Inklination** | Neigung der Feldlinien gegen den Horizont; dient zur Erkennung von Magnetstörungen (`AttitudeFusion.isInclinationPlausible`) |
| **Hard-Iron** | Eigenmagnetismus des Telefons, vom Magnetometer geschätzt; ab 40 µT Hinweis „Acht schwenken" |
| **Lage / Attitude** (`DeviceAttitude`) | Ausrichtung des Telefons als Rotationsmatrix Display → Welt, plus Missweisung, Kalibrierung und Magnetfeld-Diagnose |
| **Quaternion, Slerp** | Darstellung einer Drehung bzw. Interpolation zwischen zwei Drehungen; damit glättet `AttitudeFusion` die Lage |
| **ENU** | Weltkoordinaten x = Ost, y = Nord, z = oben |
| **Lochkameramodell** | Pixel ↔ Strahl über die Brennweite in Pixeln (`SkyProjection`) |
| **`FIT_CENTER`** | Skalierung der Kameravorschau ohne Beschnitt (mit Balken); die Projektion setzt sie voraus |
| **Bildfeld / `fovScale`** | Sichtwinkel der Kamera; der Kalibrierfaktor korrigiert die Brennweite |
| **Lagekorrektur** (`Calibration.correction`) | Kleine Drehung im Weltsystem, die den Kompassfehler (und ab zwei Sternen die Kippung) ausgleicht |
| **Gnomonische Projektion** (`TangentPlane`) | Projektion auf eine Tangentialebene, bei der Großkreise zu Geraden werden; Grundlage des Punkt-im-Polygon-Tests |
| **Bisektion** | Halbierungsverfahren, mit dem `IntervalScanner` und `Twilight` die Zeitpunkte zwischen zwei Abtastungen eingrenzen |
| **Dämmerung** | bürgerlich (Sonne bis −6°), nautisch (bis −12°), astronomisch (bis −18°); „dunkel" heißt meist Sonne unter −18° |
| **Bortle-Skala** | Himmelshelligkeit in 9 Stufen (1 = dunkel, 9 = Innenstadt); hier aus Einwohnerzahl und Entfernung geschätzt (`BortleScale`) |
| **Flächenhelligkeit** | Helligkeit pro Fläche (mag/□′); entscheidet bei ausgedehnten Objekten mehr als die Gesamthelligkeit |
| **Grenzgröße** | Schwächste Helligkeit (mag), die berücksichtigt wird; größere Zahl = schwächer |
| **Seeing** | Luftunruhe; hier grob aus dem Höhenwind auf 250 hPa abgeschätzt |
| **Taupunktdifferenz** | Temperatur minus Taupunkt; klein = Beschlaggefahr |
| **Smart-Teleskop** | Teleskop mit eingebauter Kamera und Automatik (Seestar, DWARF); Gerätedaten in `data/gear` |
| **Mosaik** | Mehrere Aufnahmen nebeneinander, wenn ein Objekt größer als das Bildfeld ist |
| **OpenNGC, BSC, Sh2, Barnard, LBN** | Kataloge: Deep-Sky-Objekte (NGC/IC/Messier), Bright Star Catalogue, Sharpless-HII-Regionen, Barnard-Dunkelwolken, Lynds-Hellnebel |
| **hips2fits / DSS2** | Bilddienst des CDS Straßburg bzw. die Himmelsdurchmusterung, aus der die Objektbilder stammen |
| **Open-Meteo** | Wetterdienst ohne Schlüssel; liefert Vorhersage, Modellmetadaten (`meta.json`) und Ortssuche |
| **ICON, AROME, HARMONIE, ECMWF IFS** | Wettermodelle verschiedener Wetterdienste mit unterschiedlicher Maschenweite und Reichweite (`WeatherModel`) |
| **Baseline Profile** | Liste vorab kompilierter Methoden für schnelleren Start; erzeugt vom Modul `:baselineprofile` |
| **R8** | Code-Verkleinerung und -Optimierung im Release-Build; braucht Keep-Regeln für die Serialisierung |
| **`AppContainer`** | Handgeschriebener Service-Locator statt DI-Framework |
| **UiState** | Unveränderliche `data class` mit dem gesamten Zustand eines Bildschirms, als `StateFlow` vom ViewModel bereitgestellt |
