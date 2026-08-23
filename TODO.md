# StarWindow – was noch fehlt

Stand: Grundgerüst, Nachtsicht-Sucher, Kalibrierung, Sternbilder, Laufbahnen und der
Deep-Sky-Katalog gepusht. Die Rechenkette Bildschirm → Himmel steht und ist durch 122 Unit-Tests
abgesichert; die Android-/Compose-Schicht ist noch von keinem Compiler gesehen worden.

Reihenfolge ist bewusst: Abschnitt 1 blockiert alles andere, Abschnitt 2 sind Stellen, die ich beim
Nachlesen des eigenen Codes als tatsächlich unfertig verifiziert habe (kein Raten).

---

## 1. Zuerst: zum Laufen bringen  ⛔ blockiert alles Weitere

- [ ] **Projekt in Android Studio synchronisieren und kompilieren.** Google Maven war in der
      Bauumgebung nicht erreichbar, deswegen konnte die UI-Schicht (Compose, CameraX, Sensoren)
      nicht übersetzt werden. Erwartbar sind Import- und Signaturkorrekturen, kein Umbau.
- [ ] **`./gradlew :app:testDebugUnitTest`** laufen lassen – muss grün sein (122 Tests).
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
- [x] ~~OpenNGC lokal mitliefern.~~ 3.241 fotografisch interessante Objekte, 179 KB gepackt,
      reproduzierbar über `scripts/import_openngc.py`.
- [ ] **Sternkatalog bis ~7 mag** lokal: 9.110 Sterne reichen bis zur Grenze des bloßen Auges
      (Yale Bright Star Catalogue), das sind rund 140 KB gepackt. Bisher sind nur 57 helle Sterne
      dabei.
- [ ] **Kein Gaia für Deep Sky.** Siehe Entscheidungstabelle in [DEV_PLAN.md](DEV_PLAN.md):
      Gaia ist ein Punktquellenkatalog ohne Ausdehnung und ohne gängige Namen, und ausgerechnet
      oberhalb 3 mag – bei den Sternen, auf die man das Handy richtet – lückenhaft.
- [ ] **Ausdehnung in der Durchgangsrechnung berücksichtigen:** die Größe steht jetzt im Katalog
      und wird im Info-Blatt gezeigt, aber der Durchgang prüft weiterhin nur den Mittelpunkt. M31
      ragt über 3° und damit lange vor seiner Mitte ins Fenster.
- [ ] **Bilddienst auf dem Gerät prüfen.** Der Abruf über hips2fits ist gebaut, konnte hier aber
      nicht getestet werden – aus der Bauumgebung war kein Bilddienst erreichbar. URL-Aufbau und
      Zwischenspeicher sind getestet, die Antwort des Dienstes nicht.
- [x] ~~**Suche im Katalog**~~ Eigener Suchbildschirm über das Feld oben in der Kameraansicht:
      bewertete Treffer (`ObjectSearch`) statt bloßem Filtern, Katalognummer wie Name, Filter nach
      Art und Sortierung nach Treffer/Helligkeit/Höhe/Größe/Name. Ohne Eingabe steht dort, was
      gerade hoch am Himmel steht.
- [ ] **Suche in der Ergebnisliste eines Fensters** – die Liste hat Filter nach Art, aber kein
      Suchfeld und keine Sortierung nach Dauer. `ObjectSearch` ließe sich dort wiederverwenden.
- [x] ~~Sternbilder als Ganzes.~~ 29 Figuren mit 203 Figursternen mitgeliefert, eigene
      Durchgangsrechnung, Angabe wie viel der Figur gleichzeitig im Fenster steht.
- [ ] **Mehr Sternbilder** – bisher die 29 auffälligsten. Die übrigen 59 ergänzen.
- [ ] **IAU-Sternbildgrenzen** zusätzlich zu den Figuren, für die Frage „in welchem Sternbild liegt
      mein Fenster" (im Unterschied zu „welche Figur zieht hindurch").

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
- [x] ~~**Präzession** von J2000 auf das Datum.~~ IAU-2006-Reihe (P03) in `Precession`, als
      umkehrbare Drehung von Richtungsvektoren; jede Stelle, die eine Katalogposition an den Himmel
      hängt, geht über `positionAt()`. Rund 0,37° Mitte der 2020er. Die Einschätzung „liegt unter
      dem Kompassfehler" galt nur unkalibriert – die Sternkalibrierung hätte die Präzession sonst
      als Kompassfehler eingemessen und auf alles angewendet.
- [ ] **Nutation und jährliche Aberration** – bewusst weggelassen: zusammen unter 0,01°, zwei
      Größenordnungen unter dem, was der Sensor auflöst. Erst interessant, wenn die App je an einer
      Montierung hängt.
- [x] ~~**Sensorfusion statt roher Rotationsvektor.**~~ `TYPE_GAME_ROTATION_VECTOR` (Kreisel,
      ohne Magnetsensor) trägt die Lage, der Kompass steuert nur die Nordrichtung bei, über einen
      Filter mit 10 s Zeitkonstante und drei Gütetoren. Glättung mit adaptiver Zeitkonstante statt
      festem Gewicht, damit sie unabhängig von der Abtastrate des Geräts ist. **Auf dem Gerät
      gegenprüfen** – die Fusionsmathematik ist getestet, die Sensorverdrahtung nicht.
- [x] ~~**Magnetstörung erkennen.**~~ Gemessene Feldstärke gegen `GeomagneticField` geprüft; passt
      sie nicht, wird die Nordrichtung vom Kreisel gehalten statt vom gestörten Kompass gezogen.
      Sucher und Kalibrierbildschirm sagen es an.
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
- [x] ~~Laufbahn-Darstellung~~ – die Fensteransicht zeigt das stehende Fenster mit den Bahnen,
      Stundenmarken und Ein-/Austrittszeiten.
- [ ] **Zeitleiste** als Ergänzung: welche Objekte wann und wie lange, als Balkendiagramm über die
      Nacht – beantwortet „wann lohnt sich das Rausgehen" schneller als die Bahnen.
- [ ] **Bahn über mehrere Durchgänge**: gezeichnet wird bisher der erste Durchgang eines Objekts;
      bei zirkumpolaren Objekten gibt es weitere.
- [x] ~~**Objekt wiederfinden:**~~ „Track" auf dem Objektblatt führt zurück in den Sucher, wo ein
      Pfeil am Bildschirmrand die Richtung zeigt (`TargetIndicator`) – auch wenn das Objekt hinter
      dem Rücken steht, wo die Projektion selbst keine Antwort mehr gibt.
- [ ] **Fenster wiederfinden:** derselbe Pfeil für ein gespeichertes Fenster. `TargetIndicator`
      arbeitet auf einer beliebigen Richtung, es fehlt nur der Weg von der Fensterliste dorthin.
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
