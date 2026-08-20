# StarWindow – was noch fehlt

Stand: Grundgerüst, Nachtsicht-Sucher und Kalibrierung gepusht. Die Rechenkette Bildschirm → Himmel
steht und ist durch 89 Unit-Tests abgesichert; die Android-/Compose-Schicht ist noch von keinem
Compiler gesehen worden.

Reihenfolge ist bewusst: Abschnitt 1 blockiert alles andere, Abschnitt 2 sind Stellen, die ich beim
Nachlesen des eigenen Codes als tatsächlich unfertig verifiziert habe (kein Raten).

---

## 1. Zuerst: zum Laufen bringen  ⛔ blockiert alles Weitere

- [ ] **Projekt in Android Studio synchronisieren und kompilieren.** Google Maven war in der
      Bauumgebung nicht erreichbar, deswegen konnte die UI-Schicht (Compose, CameraX, Sensoren)
      nicht übersetzt werden. Erwartbar sind Import- und Signaturkorrekturen, kein Umbau.
- [ ] **`./gradlew :app:testDebugUnitTest`** laufen lassen – muss grün sein (89 Tests).
- [ ] **Auf echtem Gerät starten.** Emulatoren haben weder brauchbaren Kompass noch Kamera.
- [ ] **Feldabgleich am Himmel:** auf einen bekannten hellen Stern zielen und prüfen, ob dessen
      Katalogmarkierung darauf sitzt. Sitzt sie daneben → Kompass kalibrieren (Achterbewegung).
      Wandert sie beim Schwenken schneller/langsamer als das Bild → Bildfeld-Faktor in den
      Einstellungen nachziehen. Das ist der eigentliche Abnahmetest der ganzen App.
- [ ] Abgleich in **Hoch- und Querformat** sowie mit **Haupt- und Ultraweitwinkelkamera**.

---

## 2. Bekannte Lücken im aktuellen Stand

### 2.1 Fehler

- [x] ~~Bildfeld stimmt nach Gerätedrehung nicht mehr.~~ `PreviewStreamInfo` merkt sich jetzt die
      statische Sensororientierung; die Drehung wird über `rememberDisplayRotationDegrees()` live
      aus dem Display gelesen. **Auf dem Gerät im Querformat gegenprüfen.**
- [x] ~~Kamera-ID wird geraten.~~ `Camera2CameraInfo.from(camera.cameraInfo).cameraId` liefert die
      Kamera, an die CameraX tatsächlich gebunden hat.

### 2.2 Halb verdrahtet – API da, Bedienung fehlt

- [x] ~~Standort von Hand eingeben.~~ Eingabefelder für Breite und Länge im Einstellungsdialog,
      mit Rückschalter auf GPS.
- [ ] **Grenzgröße in der Kameraansicht.** `Settings.magnitudeLimit` wird gespeichert, aber
      nirgends gelesen – das Overlay zeichnet immer alle 115 Objekte. Die Detailansicht hat eine
      eigene, davon unabhängige Auswahl. Beides auf eine Quelle zusammenführen.
- [ ] **Export.** `SkyWindowRepository.exportJson()` hat keinen Aufrufer. Teilen-Intent ergänzen.
- [ ] **Notizen.** `SkyWindow.notes` wird gespeichert, aber weder gesetzt noch angezeigt.
- [ ] **Fenster nachbearbeiten:** umbenennen, Punkte verschieben, löschen einzelner Ecken.

### 2.3 Bewusst offen gelassen

- [ ] **Online-Kataloge.** `RemoteCatalogSource` ist ein leerer Platzhalter mit der geplanten
      VizieR/SIMBAD-TAP-Abfrage im Kommentar. Zusammen mit einem Offline-Cache bauen – im Feld
      gibt es kein Netz. Anfrage sinnvollerweise auf das Deklinationsband beschränken, das ein
      Fenster überhaupt sehen kann.
- [ ] **Oberflächentexte nach `strings.xml`.** Stehen aktuell fest im Compose-Code, nur Deutsch.

---

## 3. Damit es nachts wirklich benutzbar ist

- [x] ~~Lange Belichtung / hohe Empfindlichkeit~~ über Camera2-Interop
      (`NightVisionController`): Belichtungszeit und ISO von Hand, Fokus auf unendlich, umgeschaltet
      am laufenden Bild ohne Neubinden. **Auf echter Hardware gegenprüfen** – die tatsächlich
      erreichbaren Belichtungszeiten unterscheiden sich stark zwischen Geräten.
- [x] ~~Belichtungskorrektur~~ als Rückfallebene für Geräte ohne `MANUAL_SENSOR`.
- [ ] **Rotlichtmodus** für die Bedienelemente (Dunkeladaption der Augen).
- [ ] **Kompasskalibrierung im UI:** bei niedriger Genauigkeit die Achterbewegung erklären, nicht
      nur „unzuverlässig" anzeigen.
- [ ] **Zoom** (Pinch) – erhöht die Zeigegenauigkeit deutlich, muss aber zwingend über
      `ZoomState.zoomRatio` in die Brennweitenberechnung einfließen, sonst stimmt die Projektion nicht.
- [ ] **Bildschirm nur in der Kameraansicht wachhalten**, nicht in Liste und Detail
      (`FLAG_KEEP_SCREEN_ON` sitzt derzeit an der ganzen Activity).
- [ ] **Bildstapelung**: mehrere Langzeitbelichtungen mitteln, damit auch schwächere Sterne
      erscheinen. Deutlich aufwendiger als die Einzelbelichtung, aber der nächste echte Schritt.

---

## 4. Kataloge und Objekte

- [ ] **Mond, Sonne, Planeten.** Brauchen Ephemeriden statt fester RA/Dec. Für den Mond ist das der
      aufwendigste Teil (Parallaxe, schnelle Eigenbewegung), aber „wann zieht der Mond durch mein
      Fenster" ist die naheliegendste Frage überhaupt.
- [ ] **Dämmerungszeiten und Mondstörung** in der Detailansicht: ein Durchgang um 14 Uhr nützt
      nichts. Setzt die Sonnen-Ephemeride aus dem Punkt darüber voraus.
- [ ] **Satelliten (ISS, Starlink)** über TLE + SGP4 – passt konzeptionell perfekt zum Fenster,
      ist aber ein eigenes Teilprojekt.
- [ ] **Größerer Basiskatalog:** vollständige Messier- und Caldwell-Liste, Sterne bis 6 mag.
- [ ] **Ausdehnung berücksichtigen:** aktuell zählt nur der Mittelpunkt eines Objekts. M31 ist über
      3° lang und ragt in ein Fenster hinein, lange bevor die Mitte drin ist.
- [ ] **Suche und Filter** in der Ergebnisliste (nach Typ, Helligkeit, Dauer).
- [ ] **Sternbilder als Ganzes.** Die Durchgangsliste nennt heute Einzelobjekte, das Sternbild nur
      als Kürzel am Objekt (`constellation`). Für „welches Sternbild zieht durch das Fenster" fehlt
      die Ebene darüber: Sternbildgrenzen (IAU) oder wenigstens die Verbindungslinien, dazu eine
      Aussage wie „Orion tritt von 22:14 bis 23:40 durch, davon der Gürtel vollständig". Das ist
      die naheliegende Ausbaustufe der bestehenden Durchgangsrechnung – die Geometrie kann es
      bereits, es fehlen die Daten und die Zusammenfassung.

---

## 5. Genauigkeit

- [x] ~~Kalibrierung an bekannten Sternen~~ – und drei weitere Wege, von denen keiner Pflicht ist:
      Sternmuster (Ausrichtung, ab zwei Sternen auch die Neigung), Peilung auf einen Punkt bekannter
      Richtung, Schwenk über ein beliebiges Merkmal (Bildfeld, ohne Himmel und ohne Kompass) und
      der manuelle Regler. **Auf dem Gerät gegenprüfen**, besonders die Schwenkmethode.
- [ ] **Kalibrierung altert:** Der Kompassfehler ist ortsabhängig (Eisen, Fahrzeuge, Gebäude). Eine
      an einem Ort gemessene Ausrichtungskorrektur sollte nach Ortswechsel oder nach einer gewissen
      Zeit als fraglich markiert werden statt stillschweigend weiterzugelten.
- [ ] **Kalibrierung pro Kamera** ablegen, sobald der Zoom oder ein Objektivwechsel dazukommt –
      Haupt- und Ultraweitwinkelkamera haben völlig verschiedene Bildfelder.
- [ ] **Präzession** von J2000 auf das Datum (~0,4° bis 2050). Liegt heute unter dem
      Kompassfehler, wird aber relevant, sobald die Sternkalibrierung darüber existiert.
- [ ] **Kompassgüte in die gespeicherten Daten übernehmen** und in der Detailansicht als
      Fehlerbalken zeigen – ein bei „unzuverlässig" aufgenommenes Fenster kann mehrere Grad
      danebenliegen, das sollte man später noch sehen.
- [ ] **Fenster über 90° Ausdehnung.** `TangentPlane` deckt nur eine Halbkugel ab, sehr große
      Polygone liefern `false`. Dokumentierte Grenze, für den Regelfall unkritisch.
- [ ] **Selbstüberschneidende Polygone** erkennen und warnen – tippt man die Ecken in falscher
      Reihenfolge, entsteht eine Schleife mit überraschendem Ergebnis.

---

## 6. Bedienung

- [ ] **Zeitpunkt wählen** in der Detailansicht (aktuell immer „ab jetzt"). Für Planung braucht man
      „nächste Woche Freitagnacht".
- [ ] **Zeitleiste** statt reiner Textliste: welche Objekte wann und wie lange – als Balkendiagramm
      viel schneller zu erfassen.
- [ ] **Fenster wiederfinden:** Pfeil im Sucher, der zum gespeicherten Fenster zurückführt.
- [ ] **Benachrichtigung** „M31 ist in 10 Minuten in deinem Fenster".
- [ ] **Horizontprofil** statt Einzelfenster: die ganze Skyline einmal umlaufend aufnehmen und
      daraus jede Sichtbarkeit ableiten. Die konsequente Fortsetzung der Grundidee.
- [ ] **Querformat-Layout** prüfen, die Bedienleiste wird dort eng.
- [ ] **Bestätigung beim Moduswechsel**, der löscht aktuell kommentarlos alle gesetzten Punkte.

---

## 7. Projekt und Infrastruktur

- [ ] **`main`-Branch anlegen** – das Repository hat aktuell nur den Feature-Branch.
- [ ] **GitHub Actions:** Unit-Tests und `assembleDebug` bei jedem Push. Hätte die fehlenden
      Compile-Prüfungen aus Abschnitt 1 automatisch abgefangen.
- [ ] **Instrumentierte Tests** für Kamerabindung und Berechtigungsablauf.
- [ ] **Lint und Formatierung** (ktlint oder Spotless) verbindlich machen.
- [ ] **Signierung und Release-Konfiguration**, `isMinifyEnabled` für Release aktivieren und die
      ProGuard-Regeln gegen einen echten Release-Build prüfen.
- [ ] **Datenschutzhinweis:** Standort und Kamera werden nur lokal verarbeitet – für den Play
      Store ohnehin nötig.
