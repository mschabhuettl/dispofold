# Personen

## Liste der Personen

![Personen](../screenshots/personen.png)

**Personen** listet alle Personen mit Kürzel, Bereichen und Wochenstunden. **Suchen** findet nach Name, Kürzel
oder Bereich (mehrere Wörter müssen alle passen). Ein Klick auf den Namen öffnet die Seite der Person. **Person
anlegen** öffnet ein leeres Formular für eine neue Person. Personen, die nicht mehr aus dem Quellsystem übernommen
werden (siehe [Nicht mehr aus dem Quellsystem übernehmen](#nicht-mehr-aus-dem-quellsystem-übernehmen)), stehen
grau mit „nicht mehr übernommen“ in der Liste; die Zeile über der Liste zählt sie extra.

### Seite einer Person

![Seite einer Person](../screenshots/person.png)

Oben stehen Kürzel, Wochenstunden, Bereiche und der Beschäftigungszeitraum. **Bearbeiten** springt zum Formular.
Die Zahlen gelten für den gewählten Monat:

- **Stunden:** geplante und angerechnete Stunden gegen Ziel und Soll in einer Kachel („48,4 h (davon 20,4 h
  angerechnet) von Ziel 112,9 h (Soll 129,2 h)“; diese Summe vergleicht die Planung mit dem Ziel, **i** erklärt
  beide; Ansichtsrollen sehen nur die geplanten Stunden und das Soll), Stundenkonto vorher und danach, Dienste, verfügbare Tage, vom Stundenkonto
  abgezogene Stunden und der Wochenschnitt im Durchrechnungszeitraum („Historie unvollständig“, wenn die
  Vormonate nicht so weit zurückreichen).
- **Belastung und Wünsche:** Nächte, Wochenenden im Dienst und frei, Feiertage, unbeliebte Dienste,
  Einzeldienste, erfüllte Wünsche, Ersatzruhe geplant, erworben und offen danach. Die Kachel **Nächte** sagt, ob
  die Person ihrer gewohnten Verteilung folgt, nie Nacht macht oder im Nachtausgleich ist, dann mit ihrer
  Belastung und dem Mittel aller Verglichenen.
- **Verteilung der Dienste:** der geplante Anteil je Schichtart und Bereich neben der gewohnten Verteilung. Zählt
  der Nachtanteil nicht (Nachtdienste „Gleichmäßig“ oder die Person macht nie Nacht), zeigt die Spalte **Gilt
  (ohne Nächte)** die Anteile, die dann für die Dienste außer Nächten gelten.
- **Historie (Vormonate):** Nächte, Wochenenden, Feiertage, unbeliebte Dienste und Wünsche der Vormonate, die
  für die Fairness mitzählen.
- **Warum dieser Dienst?** Für jeden Tag (oder **Ganzer Monat**) steht in Worten, was zutrifft (zum Beispiel
  „verletzt keine Regel, 1 Nachteil“), darunter welche Regeln gegriffen haben, mit ihrer Gruppe
  (**Regelverstoß**, **Bedarf**, **Qualität**) und ob es ein Verstoß, ein Nachteil oder ein Vorteil ist. Bei der
  Fairness erscheint ein Treffer nur bei Personen über dem Mittel.

### Person bearbeiten

![Person bearbeiten](../screenshots/person-bearbeiten.png)

Unter **Person bearbeiten** steht alles zu einer Person in einem Formular, gespeichert mit einem Klick auf
**Speichern**:

- **Vorname**, **Nachname**, **Kürzel**, **Wochenstunden**, **Stundenkonto**, **Ersatzruhe offen**, **Externe
  Id** (die Kennung im Quellsystem), **Eintritt** und **Austritt**. Vor dem Eintritt und nach dem Austritt gibt es
  keine Dienste.
- **Bereiche:** in welchen Bereichen die Person eingeplant werden darf. Ohne Bereich wird sie nie eingeplant.
- **Gewohnte Verteilung:** wie sich die Dienste üblicherweise verteilen, in Prozent je Schichtart (Früh, Tag,
  Spät, Nacht) und je Bereich, zum Beispiel Tag 90 % und Nacht 10 %. Die Werte kommen meist aus dem Datensatz
  (Feld `shiftMix`). Eine ausgefüllte Gruppe muss zusammen 100 % ergeben, eine leere Gruppe heißt keine
  Verteilung; **Verteilung entfernen** leert alle Felder. Beim Planen nähert die Regel „Gewohnte Verteilung“ die
  Dienste an diese Anteile an. Was mit dem Nachtanteil geschieht, hängt von der Einstellung **Nachtdienste** ab
  (siehe [Regeln](einstellungen.md#regeln)); der Hinweis unter der Überschrift sagt es für die gewählte Einstellung, bei einer
  Person, die nie Nacht macht, für sie. Zählt der Nachtanteil nicht, nennt eine Zeile unter den Feldern die
  Anteile, die dann gelten (die übrigen, auf 100 % hochgerechnet). Für Personen
  mit Anteilen je Bereich gilt die Abwechslung Bereiche nicht.
- **Einschränkungen:** welche Schicht oder Schichtart (zum Beispiel alle Nachtschichten) die Person **Nie** oder
  **Nur auf Anfrage** macht, an jedem Tag oder an einem Wochentag, auf Wunsch nur für eine bestimmte Zeit
  (**Gültig ab**, **Gültig bis**). **Einschränkung hinzufügen** nimmt die Zeile in die Liste auf, **Entfernen**
  nimmt sie heraus. Für jeden Bereich der Person gibt es die Auswahl **Ganzer Bereich**: **keine**, **nur auf
  Anfrage** oder **nie** setzt alle Schichten des Bereichs auf einmal, für jeden Tag. „gemischt“ heißt, dass die
  Schichten des Bereichs verschieden eingeschränkt sind.
- **Familienregeln:** persönliche Regeln für gewählte Wochentage, zum Beispiel für Kinderbetreuung: **Ende
  spätestens**, **Beginn frühestens** oder keine Nacht, mit einer Notiz. **Familienregel hinzufügen** fügt eine
  Zeile an. Die Planung hält sie ein, wenn es geht; ein Dienst dagegen ist ein Nachteil in der Qualität.

Das Programm prüft jede Änderung wie einen Import. Ist etwas falsch, wird nichts gespeichert, und die Liste der
Fehler erscheint im Formular. Neue Personen bekommen ihre Id vom Programm. Offene Monate, die eine Änderung
betrifft, zeigen danach „Plan veraltet: neu planen“.

**Person entfernen:** Kommt die Person noch in offenen Monaten vor, nennt das Programm die Zahl der Verweise und
die Monate und fragt, ob sie mit allen Verweisen entfernt werden soll. Kommt sie in einem abgeschlossenen Monat
oder in der Historie vor, lässt sie sich nicht entfernen; dann trägt man den Austritt ein oder schaltet
**Aus dem Quellsystem nicht mehr übernehmen** ein.

### Nicht mehr aus dem Quellsystem übernehmen

Für Personen, die nicht mehr geplant werden sollen, im Quellsystem aber noch geführt werden (etwa nach einem
Austritt, der dort fehlt), und die sich nicht entfernen lassen, weil sie in abgeschlossenen Monaten oder in der
Historie vorkommen. Der Schalter steht auf der Seite der Person unter **Übernahme aus dem Quellsystem**, nur für die
Planung, und wirkt sofort (nach einer Rückfrage):

- **Eingeschaltet:** Die Person bleibt mit allem in den abgeschlossenen Monaten und in der Historie, dort ändert
  sich nichts. Ab dem ersten Tag nach dem letzten abgeschlossenen Monat (ohne abgeschlossenen Monat ab dem
  ersten Planungsmonat) wird sie nicht mehr geplant, wie nach einem Austritt; der Tag bleibt, auch wenn später
  weitere Monate abgeschlossen oder wieder geöffnet werden. Die Seite der Person nennt den Tag. Offene Monate, die
  schon geplant sind, zeigen „Plan veraltet: neu planen“; Dienste, die sie dort schon hat, meldet die Prüfung als
  Regelverstoß „Beschäftigungszeitraum“, die nächste Planung lässt sie frei (von Hand gesetzte Dienste nimmt man
  selbst heraus). Abwesenheiten und Wünsche in offenen Monaten bleiben stehen und wirken nicht mehr. Im
  Dienstplan steht sie weiter als Zeile ohne Dienste, wie eine Person nach dem Austritt.
- In der Korrekturdatei steht für sie `"exclude": true`. Das Exportprogramm lässt sie dann im nächsten Datensatz
  weg; Dispofold behält sie beim Import mit ihrer Historie und weiter ausgeschlossen, ohne Hinweis „fehlt im
  neuen Datensatz“.
- **Ausgeschaltet:** Die Person wird wieder geplant, und die nächste Korrekturdatei schließt sie nicht mehr aus,
  auch wenn der Ausschluss aus einer früheren Korrekturdatei stammt. Bis zum nächsten Export und Import plant
  Dispofold mit den Daten, die es von ihr hat; danach kommt sie mit den Daten aus dem Quellsystem. Hat sich
  sonst nichts geändert, speichert **Änderungen als Korrekturdatei exportieren** trotzdem eine Datei (ohne
  Einträge), denn sie muss die bisherige ersetzen. Das gilt auch, wenn der Schalter seit dem Import ein- und
  wieder ausgeschaltet wurde und dazwischen eine Korrekturdatei gespeichert wurde.

Hat das Exportprogramm eine Person ausgeschlossen, die Dispofold kennt (etwa mit einem Eintrag, der von Hand in
`korrekturen.json` geschrieben wurde), ist der Schalter nach dem Import eingeschaltet und lässt sich genauso
ausschalten. Solange eine Person ausgeschlossen ist, kommen Änderungen an ihren anderen Feldern nicht in die
Korrekturdatei; nach dem Ausschalten kommt sie mit den Werten aus dem Quellsystem zurück.

**Wenn jemand anderer inzwischen Stammdaten gespeichert hat**, fragt das Programm wie bei den Eingaben nach:
**OK** übernimmt die eigene Änderung in die neue Fassung, **Abbrechen** verwirft sie und lädt die neue Fassung.
Wurde dieselbe Person auch von der anderen Person geändert, speichert das Programm nicht von selbst: Die eigenen
Angaben stehen wieder im Formular, sie sind zu prüfen und noch einmal zu speichern.
