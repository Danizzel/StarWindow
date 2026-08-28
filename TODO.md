# StarWindow – was noch fehlt

Stand: Grundgerüst, Nachtsicht-Sucher, Kalibrierung, Sternbilder, Laufbahnen, Deep-Sky-Katalog,
Objektsuche und Verfolgung im Sucher, Wettervorhersage für die Nacht. Die Rechenkette
Bildschirm → Himmel steht und ist durch 381 Unit-Tests abgesichert; die App übersetzt und läuft auf
einem echten Gerät.

Reihenfolge ist bewusst: Abschnitt 2 sind Stellen, die ich beim Nachlesen des eigenen Codes als
tatsächlich unfertig verifiziert habe (kein Raten). Was noch aussteht, ist zum großen Teil nicht
mehr Code, sondern **Gegenprüfung am Himmel** – siehe Abschnitt 1.

---

## 1. Gegenprüfung am Himmel  ⛔ das Einzige, was Code nicht ersetzen kann

- [x] ~~Projekt synchronisieren und kompilieren.~~ Werkzeugkette auf Gradle 9.4.1, AGP 9.2.1,
      Kotlin 2.3.21, SDK 36 angehoben.
- [x] ~~`./gradlew :app:testDebugUnitTest`~~ – 381 Tests, grün.
- [x] ~~Auf echtem Gerät starten.~~ Läuft auf einem Pixel 9.
- [ ] **Feldabgleich am Himmel:** auf einen bekannten hellen Stern zielen und prüfen, ob dessen
      Katalogmarkierung darauf sitzt. Sitzt sie daneben → Kompass kalibrieren (Achterbewegung).
      Wandert sie beim Schwenken schneller/langsamer als das Bild → Bildfeld-Faktor in den
      Einstellungen nachziehen. Das ist der eigentliche Abnahmetest der ganzen App.
- [ ] Abgleich in **Hoch- und Querformat** sowie mit **Haupt- und Ultraweitwinkelkamera**.
- [ ] **Sensorfusion gegenprüfen:** steht in der Statusleiste „Kreisel + Kompass"? Stehen die
      Markierungen beim Stillhalten wirklich still? Meldet die App eine Magnetstörung, wenn man
      das Handy neben ein Auto hält, und hält sie danach die Nordrichtung? Die Fusionsmathematik
      ist getestet, die Sensorverdrahtung nicht.

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
- [x] ~~**Der Sucher zeigt nur noch Fotoziele.**~~ `OverlaySelection` gibt dem Overlay höchstens
      **400** Markierungen statt der 22.528 Katalogeinträge: die 250 fotografisch lohnendsten
      Objekte, die von dieser Breite je aufgehen (`PhotographicInterest`), und 150 Sterne bis
      4,2 mag als Orientierung – die braucht es, denn an einem bekannten hellen Stern wird geprüft,
      ob die Projektion überhaupt stimmt, und die Sternkalibrierung misst gegen sie. Der Rest ist
      über Suchfeld und Verfolgung erreichbar, wo man ein bestimmtes Objekt ohnehin sucht; im
      Sucher hätte es niemand gefunden. `Settings.magnitudeLimit` wird dabei gelesen und kann die
      Auswahl nur enger machen, nie weiter.
- [ ] **Regler für die Grenzgröße in der Kameraansicht.** Der Wert wirkt, ist dort aber noch nicht
      einstellbar – nur die Fensteransicht hat einen eigenen, davon unabhängigen. Beides auf eine
      Quelle zusammenführen.
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
- [x] ~~**Kompasskalibrierung im UI.**~~ Der Kalibrierbildschirm zeigt Feldstärke, Feldneigung und
      Eigenmagnetismus mit den jeweils erwarteten Werten und unterscheidet in Klartext, was zu tun
      ist: liegende Acht schwenken (Sensor nicht eingemessen), ein paar Schritte weggehen
      (Feldrichtung verbogen), warten (Störung der Stärke). Der Sucher zeigt dieselbe
      Unterscheidung kurz in der Statusleiste.

---

## 4. Kataloge und Objekte

- [x] ~~**Ephemeriden für Sonne und Mond.**~~ `core/astro/Ephemeris.kt` nach Meeus Kap. 25 und 47:
      Sonne auf 0,01°, Mond auf 0,02° in der Länge, samt Parallaxe, Beleuchtungsgrad und Phase.
      `Twilight` findet daraus Dämmerungsschwellen, Auf- und Untergänge.
- [ ] **Mond und Sonne als Katalogobjekte.** Die Ephemeriden liegen vor, aber die
      Durchgangsberechnung kennt weiterhin nur feste RA/Dec — „wann zieht der Mond durch mein
      Fenster" ist die naheliegendste Frage überhaupt und geht noch nicht. Nötig: ein
      `SkyObject`, dessen Position eine Funktion der Zeit ist, und ein `TransitCalculator`, der
      das aushält.
- [ ] **Planeten.** Brauchen zusätzlich VSOP87 oder eine gekürzte Fassung davon.
- [ ] **Dämmerungszeiten und Mondstörung in der Fensteransicht:** ein Durchgang um 14 Uhr nützt
      nichts. Die Rechnung dafür steht jetzt in `Twilight` und `AstroWeather` — sie muss nur noch
      in die Durchgangsliste hinein.
- [ ] **Satelliten (ISS, Starlink)** über TLE + SGP4 – passt konzeptionell perfekt zum Fenster,
      ist aber ein eigenes Teilprojekt.
- [x] ~~OpenNGC lokal mitliefern.~~ Inzwischen der **vollständige** Bestand: 13.432 Deep-Sky-Objekte,
      dazu Sharpless, Barnard und LBN für die Nebel, die im NGC fehlen. Reproduzierbar über
      `scripts/import_deepsky.py`. Die alte Grenze bei 13 mag ist gefallen -- sie stand an der
      falschen Stelle, siehe `domain/OverlaySelection.kt`.
- [x] ~~**Sternkatalog bis ~7 mag** lokal.~~ 9.096 Sterne aus dem Yale Bright Star Catalogue,
      386 davon mit Namen, dazu Bayer- und Flamsteed-Bezeichnung in allen Schreibweisen,
      Spektraltyp, Sternbild und bei 938 Doppelsternen der Abstand. `scripts/import_bsc.py`.
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
      bewertete Treffer (`ObjectSearch`) statt bloßem Filtern, Katalognummer wie Name, Sortierung
      nach Treffer/Machbarkeit/Helligkeit/Höhe/Größe/Name.
- [x] ~~**Heute Nacht statt Katalogliste.**~~ Ohne Eingabe zeigt der Bildschirm keine Liste mehr,
      sondern drei kurze Abschnitte (`TonightBoard`): was gerade im gespeicherten Fenster steht –
      mit der Zeit, die es dort noch hat –, was hoch steht, und was in den nächsten Stunden
      hochkommt. Nötig geworden durch den großen Katalog: die alte Rangfolge belohnte Helligkeit,
      und 9.096 Sterne sind heller als fast jedes Deep-Sky-Objekt, also füllte sich der Vorschlag
      lautlos mit Sternen. Der Ausweg war nicht eine bessere Sortierung derselben Liste, sondern
      gar keine Liste.
- [x] ~~**Machbarkeit in jeder Zeile.**~~ `Feasibility` beurteilt jeden Eintrag für heute Nacht,
      von hier: leicht / geht / schwierig / zu tief / zu schwach. Entscheidend ist die
      Flächenhelligkeit gegen den Himmelshintergrund, der aus Bortle-Stufe und Mond folgt
      (`SkyConditions`) – **fotografisch** gerechnet, nicht visuell: ein Objekt darf bis etwa
      2,5 mag unter dem Himmel liegen und ist trotzdem erreichbar. Damit darf eine Trefferliste
      lang sein, weil man sie an der rechten Kante entlangliest.
- [x] ~~**Filterblatt statt zweier Chip-Reihen.**~~ Über der Liste steht nur noch, was aktiv ist,
      als Chips zum Wegtippen. Das Blatt darunter (`CatalogFilterSheet`) hat Art, Helligkeit,
      Mindestgröße, Mindesthöhe, Sternbild, „nur machbar" und „nur durchs Fenster".
- [x] ~~**Katalog durchs Fenster gefiltert.**~~ Der Fensterfilter rechnet die Durchgänge der
      nächsten zwölf Stunden und lässt nur stehen, was tatsächlich hindurchzieht. Auf Fotoziele
      beschränkt und pro Fenster zwischengespeichert – ein Zwölf-Stunden-Sweep über 9.096 Sterne
      wäre Sekunden für eine Liste, die niemand will.
- [ ] **Zeitpunkt für den Fensterfilter wählen.** Er rechnet immer „ab jetzt". Für die Planung
      einer Nacht in drei Tagen braucht es dasselbe mit einem anderen Startzeitpunkt – hängt am
      selben offenen Punkt wie die Detailansicht (Abschnitt 6).
- [x] ~~**Suche und Reihenfolge in der Ergebnisliste eines Fensters.**~~ Eigenes Suchfeld über der
      Liste (filtert die Treffer, nicht den Katalog) und fünf Reihenfolgen in `TransitListView`:
      lohnendste zuerst als Voreinstellung, dazu Eintritt, Dauer, Helligkeit und Größe. Darüber
      steht „Die besten je Art" – die drei besten Nebel, Galaxien, Haufen und Sterne dieses
      Fensters, denn dass eine Lücke ein Galaxienfenster ist, sieht man in keiner Sortierung der
      Gesamtliste.
- [x] ~~Sternbilder als Ganzes.~~ Alle 88 Figuren mit 767 Figursternen mitgeliefert, eigene
      Durchgangsrechnung, Angabe wie viel der Figur gleichzeitig im Fenster steht.
- [x] ~~**Beschreibungstext zu jedem Objekt.**~~ Zwei Schichten: `ObjectDescription` erklärt für alle
      22.528 Objekte die **Art** (Emissions- gegen Reflexionsnebel samt der Folge für den Filter,
      Planetarischer Nebel hat nichts mit Planeten zu tun, …) und übersetzt die Zahlen des Eintrags
      in Aussagen – Ausdehnung im Vergleich zum Vollmond, Flächenhelligkeit als Anspruch an den
      Himmel, Morphologiecode ausgeschrieben. Darüber liegen rund 150 handgeschriebene Notizen in
      `assets/catalog/object_notes.json` für die Objekte, die man wirklich anschaut.
- [ ] **Mehr Objektnotizen** – abgedeckt sind Messier, die bekannten NGC/IC-Namen und die hellen
      Sterne. Die restlichen benannten Einträge fehlen noch; ohne Notiz greift die erzeugte
      Beschreibung, das ist kein Loch, nur weniger.
- [x] ~~**Mehr Sternbilder**~~ – jetzt alle 88, mit deutschen Namen. Die Schlange steht als einziges
      Sternbild in zwei getrennten Stücken am Himmel und wird zu einer Figur zusammengelegt.
- [ ] **IAU-Sternbildgrenzen** zusätzlich zu den Figuren, für die Frage „in welchem Sternbild liegt
      mein Fenster" (im Unterschied zu „welche Figur zieht hindurch").

---

## 5. Genauigkeit

- [x] ~~Kalibrierung an bekannten Sternen~~ – und drei weitere Wege, von denen keiner Pflicht ist:
      Sternmuster (Ausrichtung, ab zwei Sternen auch die Neigung), Peilung auf einen Punkt bekannter
      Richtung, Schwenk über ein beliebiges Merkmal (Bildfeld, ohne Himmel und ohne Kompass) und
      der manuelle Regler. **Auf dem Gerät gegenprüfen**, besonders die Schwenkmethode.
- [x] ~~**Kalibrierung altert.**~~ Die Ausrichtungskorrektur merkt sich jetzt, **wo** sie gemessen
      wurde. `CalibrationTrust` beurteilt sie beim Benutzen: frisch, älter (> 2 Tage), veraltet
      (> 14 Tage) oder anderer Ort (> 5 km). Sucher und Kalibrierbildschirm sagen es an, sobald sie
      fraglich wird. Entfernung schlägt Alter – eine heute Morgen drei Orte weiter gemessene
      Korrektur ist weniger wert als eine eine Woche alte von diesem Balkon. Bewusst getrennt von
      der Magnetstörungserkennung: die fängt „hier steht gerade Eisen neben dir", diese fängt „was
      du gemessen hast, beschreibt nicht mehr, wo du bist".
- [ ] **Kalibrierung pro Kamera** ablegen, sobald der Zoom oder ein Objektivwechsel dazukommt –
      Haupt- und Ultraweitwinkelkamera haben völlig verschiedene Bildfelder. Hängt am Zoom
      (Abschnitt 3); vorher gibt es nichts zu unterscheiden.
- [x] ~~**Präzession** von J2000 auf das Datum.~~ IAU-2006-Reihe (P03) in `Precession`, als
      umkehrbare Drehung von Richtungsvektoren; jede Stelle, die eine Katalogposition an den Himmel
      hängt, geht über `positionAt()`. Rund 0,37° Mitte der 2020er. Die Einschätzung „liegt unter
      dem Kompassfehler" galt nur unkalibriert – die Sternkalibrierung hätte die Präzession sonst
      als Kompassfehler eingemessen und auf alles angewendet.
- [x] ~~**Nutation und jährliche Aberration** – gestrichen.~~ Kein offener Punkt, sondern eine
      Entscheidung: zusammen unter 0,01°, zwei Größenordnungen unter dem, was der Sensor auflöst.
      Wieder aufmachen, falls die App je an einer Montierung hängt.
- [x] ~~**Sensorfusion statt roher Rotationsvektor.**~~ `TYPE_GAME_ROTATION_VECTOR` (Kreisel,
      ohne Magnetsensor) trägt die Lage, der Kompass steuert nur die Nordrichtung bei, über einen
      Filter mit 10 s Zeitkonstante und drei Gütetoren. Glättung mit adaptiver Zeitkonstante statt
      festem Gewicht, damit sie unabhängig von der Abtastrate des Geräts ist. **Auf dem Gerät
      gegenprüfen** – die Fusionsmathematik ist getestet, die Sensorverdrahtung nicht.
- [x] ~~**Magnetstörung erkennen.**~~ Gemessene Feldstärke gegen `GeomagneticField` geprüft; passt
      sie nicht, wird die Nordrichtung vom Kreisel gehalten statt vom gestörten Kompass gezogen.
      Sucher und Kalibrierbildschirm sagen es an.
- [x] ~~**Feldrichtung prüfen, nicht nur die Feldstärke.**~~ Die Stärkeprüfung allein übersieht
      genau den Fall, auf den es ankommt: Eisen in der Nähe addiert einen Vektor zum Erdfeld, und
      diese Summe kann nahezu gleich lang bleiben und trotzdem 20° schief zeigen – der Fehler
      landet dann ungebremst in der Nordrichtung. Zusätzlich wird deshalb die **Inklination**
      (Neigungswinkel der Feldlinien) gegen `GeomagneticField.getInclination()` geprüft. Sie hängt
      an der Schwerkraft und am Feld, nie an der Nordrichtung – deswegen darf sie über den Kompass
      urteilen, ohne aus ihm abgeleitet zu sein.
- [x] ~~**Eigenmagnetismus des Geräts ausweisen.**~~ Statt `TYPE_MAGNETIC_FIELD` wird
      `TYPE_MAGNETIC_FIELD_UNCALIBRATED` gelesen: derselbe Sensor liefert damit zusätzlich die
      Hard-Iron-Schätzung der Plattform (Lautsprecher-, Kamera- und Akkumagnete). Deren Größe
      trennt zwei Fälle, die der Nutzer völlig verschieden behandeln muss – „neben dir steht
      Eisen, geh ein paar Schritte weiter" gegen „der Sensor ist noch nicht eingemessen, schwenk
      eine liegende Acht". Bisher hieß beides „unzuverlässig".
- [x] ~~**Gierdrift des Kreisels beziffern.**~~ „Nord gehalten" war ein Ja/Nein-Kennzeichen – drei
      Sekunden und zwanzig Minuten sahen gleich aus. Jetzt zählt die App die Haltedauer und rechnet
      sie über eine dokumentierte, bewusst konservative Driftrate in einen Winkel um, der im Sucher
      steht und mit ins Fenster gespeichert wird. Oberhalb von 30° hört die ehrliche Antwort auf,
      eine Zahl zu sein. **Die Rate ist eine Annahme, keine Messung** – am Gerät nachmessen und
      `GYRO_DRIFT_DEG_PER_MINUTE` ersetzen.
- [x] ~~**ARCore / Visual Inertial Odometry** – geprüft und verworfen.~~ Wäre gegen Kreiseldrift
      das stärkste Mittel, scheitert hier aber an der Anwendung selbst: ARCore verfolgt
      *Bildmerkmale*, und ein dunkler Nachthimmel hat keine – genau dort, wo die App benutzt wird,
      hätte es nichts zum Verfolgen. Dazu übernimmt ARCore die Kamerasitzung, was mit dem manuellen
      Nachtsicht-Sucher (Belichtungszeit, ISO, Fokus über Camera2) kollidiert, der hier zentral ist.
      Und Norden liefert es von sich aus auch nicht: ARCores Weltsystem ist schwerkraftbezogen mit
      beliebigem Gierwinkel; die Geospatial-API bräuchte Netz und Street-View-Abdeckung. Wieder
      aufmachen, falls die App je bei Tageslicht gegen eine Skyline ausgerichtet werden soll –
      dafür wäre es tatsächlich das richtige Werkzeug (siehe Horizontprofil, Abschnitt 6).
- [x] ~~**Kompassgüte in die gespeicherten Daten übernehmen**~~ und als Fehlerbalken zeigen. Ein
      Fenster merkt sich jetzt zusätzlich die **gemessene Restabweichung** der Kalibrierung, die
      beim Aufnehmen galt, und ob der Kompass dabei gestört war. `WindowAccuracy` macht daraus eine
      Zahl – und unterscheidet ausdrücklich, ob sie **gemessen** ist (Kalibrierrest) oder nur aus
      dem Kompass-Gütekennzeichen **geschätzt**; Android nennt dafür nie einen Winkel, und „±5,0°"
      aus einem Kennzeichen zu zitieren wäre erfundene Genauigkeit. Der Balken ist auf den
      Fensterradius skaliert: ein halbes Grad Fehler ist in einer 5°-Lücke nichts und in einem
      halben Grad Schlitz alles.
- [x] ~~**Fenster über 90° Ausdehnung** – gestrichen.~~ Dokumentierte Grenze, kein Auftrag: ein
      Fenster ist eine Lücke zwischen zwei Dächern, und über eine Halbkugel hinweg ist das keine
      Lücke mehr. `TangentPlane` liefert dort `false`, und die Überschneidungsprüfung wertet
      Ecken auf der Gegenhalbkugel ausdrücklich nicht als Schleife.
- [x] ~~**Selbstüberschneidende Polygone** erkennen und warnen.~~ `hasSelfIntersection()` auf der
      Tangentialebene, echte Kreuzungen statt bloßer Berührungen; berührende Nachbarkanten und
      konkave Konturen (die L-Form zwischen zwei Dächern) lösen bewusst nichts aus. Die Warnung
      steht direkt bei der Flächenangabe, blockiert aber das Speichern nicht. Nötig, weil beide
      Folgen stumm sind: die Fläche fällt zu klein aus, weil sich die Schleifen aufheben, und der
      Enthaltensein-Test antwortet für die falsche Hälfte.

---

## 6. Bedienung

- [x] ~~**Jahresplanung.**~~ Neuer Kalender-Tab in der Kameraansicht und ein Knopf **Planung** unten
      im Info-Blatt jedes Objekts. `ObservationPlanner` rechnet für jede Nacht des kommenden Jahres
      aus, wie lange das Objekt gleichzeitig über 30° steht **und** der Himmel dunkel ist – das ist
      die Gesamtbelichtung, die eine Nacht hergibt, und damit die Zahl, nach der geplant wird. Die
      Dämmerungsgrenzen werden analytisch gelöst statt abgetastet (ein Jahr in Millisekunden statt
      zweihunderttausend Ephemeriden), gegengeprüft gegen `Twilight` auf unter sechs Minuten.
      Vorgemerkte Nächte liegen in `PlanRepository`.
- [ ] **Wetter mit der Planung verbinden.** Der Kalender kennt die Nächte, die Wetteransicht kennt
      15 Tage Vorhersage – für die vorgemerkten Nächte in Reichweite ließe sich beides
      zusammenbringen („Freitag geplant, Prognose sagt bedeckt").
- [x] ~~**Erinnerung an eine geplante Nacht.**~~ Je Termin einstellbar: 1 Woche, 3 Tage, 1 Tag
      vorher oder am Tag selbst, jeweils um 17 Uhr, mehrere gleichzeitig. Über `AlarmManager` als
      **ungenaue** Alarme (`setAndAllowWhileIdle`) – Minutengenauigkeit ist eine Woche im Voraus
      wertlos, und `SCHEDULE_EXACT_ALARM` dafür zu verlangen wäre ein schlechter Tausch. Ein
      `BootReceiver` setzt sie nach einem Neustart neu auf. Dazu eine **Notiz** je Termin, die mit
      in die Benachrichtigung wandert.
- [x] ~~**Nächster Termin im Kalender.**~~ Eigene Kachel ganz oben mit Countdown, Eckdaten, Notiz
      und der nächsten fälligen Erinnerung.
- [x] ~~**Pfad am Himmel zeigen.**~~ „Pfad zeigen" am Termin öffnet die Kamera und zeichnet die
      ganze Bahn der Nacht ein, mit Stundenpunkten; der Pfeil führt zum **höchsten Punkt der Bahn**
      statt zur aktuellen Position – bei einer Nacht drei Monate im Voraus liegen die an
      entgegengesetzten Enden des Himmels. Beantwortet die Frage, die man draußen nicht beantworten
      kann: steht in sechs Wochen um zwei Uhr ein Dach im Weg?
- [ ] **Erinnerung nur bei brauchbarem Wetter.** Die Benachrichtigung kommt heute unabhängig von der
      Vorhersage. Für den 1-Tag- und den Am-Tag-Vorlauf ließe sich die Nachtbewertung nachschlagen
      und „aber es ist bedeckt" dazuschreiben.
- [ ] **Erinnerung an eine Nacht ohne Termin.** Nur vorgemerkte Nächte melden sich; „sag mir
      Bescheid, wenn M31 wieder gut steht" gibt es nicht.
- [ ] **Planung gegen ein Fenster rechnen.** Die Jahresplanung nimmt den freien Horizont an; wer ein
      Fenster gespeichert hat, will die Nächte, in denen das Objekt *dort hindurch* zieht.
- [ ] **Zeitpunkt wählen** in der Detailansicht (aktuell immer „ab jetzt"). Für Planung braucht man
      „nächste Woche Freitagnacht".
- [x] ~~Laufbahn-Darstellung~~ – die Fensteransicht zeigt das stehende Fenster mit den Bahnen,
      Stundenmarken und Ein-/Austrittszeiten.
- [x] ~~**Laufbahn-Diagramm entwirren.**~~ Es zeichnete für jede der sechs Bahnen jede volle Stunde
      plus Ein- und Austritt plus Namen – sechzig Beschriftungen auf einer Fläche für fünfzehn.
      Jetzt beanspruchen sie Rechtecke und werden weggelassen statt übereinandergedruckt, vergeben
      nach Wert (Name → Ein/Aus → volle Stunden). Die Stundenpunkte bleiben immer stehen.
- [x] ~~**Auswahl filtert das Diagramm.**~~ Ohne Auswahl weiter eine Handvoll Bahnen als Überblick,
      mit Auswahl nur noch die gewählten – mehrere gleichzeitig, jede mit allen Durchgängen. Die
      Ergebniskarten setzen ihre drei Zahlen (Zeitraum, Dauer, Bestzeit) jetzt in feste Spalten
      statt in einen Fließtext.
- [ ] **Zeitleiste** als Ergänzung: welche Objekte wann und wie lange, als Balkendiagramm über die
      Nacht – beantwortet „wann lohnt sich das Rausgehen" schneller als die Bahnen.
- [x] ~~**Bahn über mehrere Durchgänge**.~~ Vom ausgewählten Objekt werden alle Durchgänge
      gezeichnet (bis zu vier), von den übrigen weiterhin nur der erste – alle von allen wären ein
      Dickicht. Die Farbe hängt jetzt am Eintrag statt an der Bahn, sonst läse sich ein
      zirkumpolares Objekt mit drei Durchgängen wie drei verschiedene Dinge.
- [x] ~~**Objekt wiederfinden:**~~ „Track" auf dem Objektblatt führt zurück in den Sucher, wo ein
      Pfeil am Bildschirmrand die Richtung zeigt (`TargetIndicator`) – auch wenn das Objekt hinter
      dem Rücken steht, wo die Projektion selbst keine Antwort mehr gibt.
- [x] ~~**Fenster wiederfinden:**~~ derselbe Pfeil für ein gespeichertes Fenster, aus der Liste
      oder aus der Detailansicht. Das Verfolgungsziel ist dafür ein Summentyp geworden
      (`TrackTarget`): ein Katalogobjekt hängt am Himmel und wandert durchs Bild, ein Fenster hängt
      am Horizont und steht still – nur das Objekt braucht Sternzeit und Präzession. Ist das
      Fenster wieder im Bild, zeichnet der Sucher seine Kontur, denn der Pfeil sagt nur „dorthin",
      nicht „du bist da". Ein gelöschtes Fenster hört auf, verfolgt zu werden.
- [ ] **Benachrichtigung** „M31 ist in 10 Minuten in deinem Fenster".
- [ ] **Horizontprofil** statt Einzelfenster: die ganze Skyline einmal umlaufend aufnehmen und
      daraus jede Sichtbarkeit ableiten. Die konsequente Fortsetzung der Grundidee.
- [ ] **Querformat-Layout** prüfen, die Bedienleiste wird dort eng.
- [x] ~~**Bestätigung beim Moduswechsel.**~~ Gefragt wird nur, wenn tatsächlich Punkte verloren
      gingen – ein Dialog, der jedes Mal erscheint, wird zum Reflex und dann nicht mehr gelesen.

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

---

## 8. Wetter und Nachtplanung

- [x] ~~**Wetteransicht.**~~ Eigener Tab über dem Sucher: Urteil und Kurve für heute Abend,
      Dämmerung, Mond, Bewölkung nach Schichten, Taupunkt, Wind, Höhenwind und Bortle-Stufe,
      darunter bis zu 15 Nächte mit Wochentag, Datum und Haken zum Aufklappen. Vorhersage aus
      sechs wählbaren Modellen über Open-Meteo (ICON-CH1 bis ECMWF IFS) samt Zeitpunkt des letzten
      Laufs, Ortssuche über deren Geocoder.
- [ ] **Auf dem Gerät bedienen.** Parser und Modellzustand sind gegen echte Antworten des Dienstes
      geprüft und die Bewertung gegen vollständige Läufe aller vier mitteleuropäischen Modelle, aber
      die Ansicht selbst hat noch niemand in der Hand gehabt: Fingerbedienung der Kurve,
      Modellwechsel, Ortssuche mit Tastatur, Verhalten ohne Netz.
- [ ] **Echter Lichtatlas statt Einwohnerschätzung.** Die Bortle-Angabe kommt aus Ortsgröße und
      Entfernung und kennt immer nur *einen* Ort – die Lichtglocke der Stadt hinter dem Hügel fehlt
      ihr. Ein gekachelter Auszug des Weltatlas als mitgeliefertes Asset wäre die saubere Lösung;
      zu prüfen ist, wie groß eine brauchbare Auflösung wird.
- [ ] **Ensemble statt Einzellauf.** Open-Meteo führt auch die IFS-Ensembles. Aus der Streuung der
      50 Läufe ließe sich sagen, wie *sicher* eine Vorhersage ist – bei einer Planung fünf Tage im
      Voraus ist das die interessantere Zahl als der Mittelwert.
- [x] ~~**Kurzfristmodelle für heute Abend.**~~ Sechs Modelle zur Wahl, oben in der Ansicht und
      nach Maschenweite sortiert: ICON-CH1 (1 km), AROME HD (1,5 km), HARMONIE (2 km), ICON-D2
      (2,2 km), ICON-EU (7 km) und ECMWF IFS (25 km, 15 Tage, Voreinstellung). Zu jedem steht der
      Zeitpunkt des letzten Laufs aus `meta.json` des Dienstes, dazu Laufintervall und Reichweite;
      Modelle außerhalb ihres Gebiets sind ausgegraut.
- [ ] **Modelle nebeneinander zeigen.** Wenn ICON-D2 „teilweise" sagt und ECMWF „geeignet", ist
      genau das die interessante Information — bisher sieht man immer nur eines. Zwei Kurven
      übereinander oder ein Streuungsband wären der nächste Schritt.
- [ ] **Modell nach Vorlauf automatisch wechseln:** für heute Abend das feinste verfügbare, für
      nächste Woche ECMWF. Open-Meteos `best_match` täte das, liefert dann aber keinen einzelnen
      Lauf-Zeitpunkt mehr — die Angabe „zuletzt aktualisiert" müsste anders gelöst werden.
- [ ] **Wetter mit den Fenstern verbinden:** „In deinem Fenster zieht am Donnerstag M31 durch, und
      das Wetter passt." Braucht nur, dass die Durchgangsliste die Nachtbewertung nachschlägt.
- [ ] **Benachrichtigung bei Aufklaren** für eine Nacht, die man vorgemerkt hat.
- [ ] **Vorhersage zwischenspeichern**, damit die Ansicht auch ohne Netz noch die letzte bekannte
      Lage zeigt. Derzeit hält der Zwischenspeicher nur die laufende Sitzung.
