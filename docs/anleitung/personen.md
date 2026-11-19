# Personen

Unter **Personen** stehen alle Personen, die geplant werden können, mit ihrer eigenen Seite. Dort sieht man ihre
Zahlen im gewählten Monat und bearbeitet alles, was zu ihr gehört: Wochenstunden, Bereiche, Einschränkungen,
Familienregeln und gewohnte Verteilung. Personen kommen meist mit dem Datensatz aus dem Quellsystem; was hier
geändert wird, kommt in die [Korrekturdatei](daten.md#korrekturdatei).

## Liste der Personen

![Die Liste der Personen, nummeriert](../screenshots/personen.png)

1. **Suchen** findet nach Name, Kürzel oder Bereich. Mehrere Wörter müssen alle passen, „Ahorn Notruf“ findet
   also alle mit „Ahorn“ im Namen, die in der Notrufannahme sind.
2. **Person anlegen** öffnet ein leeres Formular für eine neue Person (siehe [Person anlegen](#person-anlegen)).
3. Die Zeile über der Liste zählt die Personen und davon die, die nicht mehr aus dem Quellsystem übernommen werden.
4. Solche Personen stehen grau mit „nicht mehr übernommen“ in der Liste (siehe
   [Nicht mehr aus dem Quellsystem übernehmen](#nicht-mehr-aus-dem-quellsystem-übernehmen)).

Ein Klick auf einen Namen öffnet die Seite der Person. Die Liste zeigt Kürzel, Bereiche und Wochenstunden.

## Seite einer Person

![Die Seite einer Person](../screenshots/person.png)

Oben stehen Kürzel, Wochenstunden, Bereiche und der Beschäftigungszeitraum; **Alle Personen** führt zurück zur
Liste, **Bearbeiten** springt zum Formular. Darunter folgen die Zahlen der Person im gewählten Monat (Stunden,
Belastung und Wünsche, Verteilung der Dienste, Historie) und **Warum dieser Dienst?**. Was die Zahlen bedeuten,
erklärt [Auswertungen](auswertungen.md#die-zahlen-einer-person). Ganz unten stehen
**Übernahme aus dem Quellsystem** und das Formular **Person bearbeiten**.

## Person bearbeiten

![Das Formular einer Person mit Einschränkungen und einer Familienregel](../screenshots/person-bearbeiten.png)

Unter **Person bearbeiten** steht alles zu einer Person in einem Formular. Geändert wird erst mit **Speichern**;
**Abbrechen** stellt die gespeicherten Werte wieder her. Die Felder:

| Feld | Bedeutung | Wann ändern |
|---|---|---|
| Vorname, Nachname | Pflichtfelder | bei einer Namensänderung (kommt nicht in die Korrekturdatei, siehe unten) |
| Kürzel | Namenskürzel für die Anzeige „Kürzel“ in den Plänen, zum Beispiel „WER“ | wenn zwei Personen gleiche Kürzel hätten |
| Wochenstunden | vertragliche Wochenstunden (Pflichtfeld, ab 0); daraus kommen Soll und angerechnete Stunden | bei einer Vertragsänderung |
| Stundenkonto | Plus- oder Minusstunden (Pflichtfeld, darf negativ sein). Gilt für einen Monat nur, solange der Vormonat nicht abgeschlossen ist; sonst zählt der Stand nach dem Vormonat aus der Historie. | beim Start mit Dispofold oder wenn das Quellsystem einen anderen Stand kennt |
| Ersatzruhe offen | offene Ersatzruhetage (Pflichtfeld, ganze Zahl ab 0); gilt wie das Stundenkonto nur ohne abgeschlossenen Vormonat | wie beim Stundenkonto |
| Externe Id | die Kennung im Quellsystem, zum Beispiel die Personalnummer | selten; das Exportprogramm braucht sie |
| Eintritt, Austritt | erster und letzter Tag der Beschäftigung; davor und danach keine Dienste, das Soll zählt nur dazwischen. Leer: unbegrenzt. | bei Eintritt, Austritt oder einer Karenz über Monate |
| Bereiche | in welchen Bereichen die Person eingeplant werden darf; ohne Bereich wird sie nie eingeplant | wenn jemand neu eingeschult ist oder einen Bereich nicht mehr macht |

Über den Feldern steht die Id der Person. Neue Personen bekommen ihre Id vom Programm.

### Gewohnte Verteilung

Wie sich die Dienste der Person üblicherweise verteilen, in Prozent je Schichtart (Früh, Tag, Spät, Nacht) und je
Bereich, zum Beispiel Tag 90 % und Nacht 10 %. Die Werte kommen meist aus dem Datensatz (Feld `shiftMix`), ein
Feld je Schichtart und je Bereich der Person.

- Eine ausgefüllte Gruppe muss zusammen 100 % ergeben, eine leere Gruppe heißt keine Verteilung. **Verteilung
  entfernen** leert alle Felder.
- Beim Planen nähert die Regel „Gewohnte Verteilung“ die Dienste an diese Anteile an.
- Was mit dem Nachtanteil geschieht, hängt von der Einstellung **Nachtdienste** ab (siehe
  [Regeln einstellen](einstellungen.md#planungsziele)); der Hinweis unter der Überschrift sagt es für die gewählte
  Einstellung, bei einer Person, die [nie Nacht](begriffe.md#nie-nacht) macht, für sie. Zählt der Nachtanteil
  nicht, nennt eine Zeile unter den Feldern die Anteile, die dann gelten (die übrigen, auf 100 % hochgerechnet).
- Für Personen mit Anteilen je Bereich gilt die Abwechslung der Bereiche nicht.
- **Wann ändern:** wenn sich jemand dauerhaft anders einteilen lässt, etwa nach einer Vereinbarung über weniger
  Nachtdienste. Für einzelne Tage sind Wünsche oder Vorgaben der richtige Weg.

### Einschränkungen

Welche Schicht oder [Schichtart](begriffe.md#schichtart) (zum Beispiel alle Nachtschichten) die Person **Nie**
oder **Nur auf Anfrage** macht:

1. In der Zeile unter der Liste **Schicht oder Kategorie**, **Wochentag** (Standard **jeden Tag**) und **Art**
   wählen, auf Wunsch **Gültig ab** und **Gültig bis** (leer heißt: schon immer und auf Dauer).
2. **Einschränkung hinzufügen** nimmt die Zeile in die Liste auf, **Entfernen** in der Liste nimmt sie heraus.
3. **Speichern** speichert das ganze Formular.

- **Nie:** Die Person bekommt diesen Dienst nie. Ein Dienst dagegen, etwa von Hand gesetzt, ist ein Regelverstoß.
- **Nur auf Anfrage:** Die Planung plant den Dienst nie von selbst. Er darf aber nach Rückfrage von Hand gesetzt
  werden und steht dann in der Liste **Auf Anfrage verfügbar** (siehe
  [Dienste nur auf Anfrage](plan-bearbeiten.md#dienste-nur-auf-anfrage)).
- **Ganzer Bereich:** Für jeden Bereich der Person setzt die Auswahl **keine**, **nur auf Anfrage** oder **nie**
  alle Schichten des Bereichs auf einmal, für jeden Tag. „gemischt“ heißt, dass die Schichten des Bereichs
  verschieden eingeschränkt sind.
- **Wann verwenden:** für dauerhafte Grenzen (keine Nachtdienste aus gesundheitlichen Gründen, ein Bereich nur
  zur Aushilfe). Für einen einzelnen Tag ist ein Wunsch oder eine Vorgabe richtig.

### Familienregeln

Persönliche Regeln für gewählte Wochentage, zum Beispiel für Kinderbetreuung. **Familienregel hinzufügen** fügt
eine Regel an; jede hat:

| Feld | Bedeutung |
|---|---|
| Wochentage | an welchen Tagen sie gilt; mindestens einer |
| Ende spätestens | ein Dienst endet an diesen Tagen spätestens zu dieser Uhrzeit; leer: keine Grenze |
| Beginn frühestens | ein Dienst beginnt an diesen Tagen frühestens zu dieser Uhrzeit; leer: keine Grenze |
| keine Nacht | an diesen Tagen kein Nachtdienst |
| Notiz | freier Text, etwa der Grund; die Planung liest ihn nicht |

Die Planung hält Familienregeln ein, wenn es geht; ein Dienst dagegen ist ein Nachteil in der Qualität, kein
Regelverstoß. Wie stark sie zählen, stellt man unter [Regeln einstellen](einstellungen.md#planungsziele) bei
**Familienregeln** ein. Eine Regel „keine Nacht“ an allen sieben Wochentagen heißt: Die Person macht
[nie Nacht](begriffe.md#nie-nacht).

### Speichern und Fehler

Das Programm prüft jede Änderung wie einen Import. Ist etwas falsch (etwa eine gewohnte Verteilung, die nicht
100 % ergibt, oder ein Austritt vor dem Eintritt), wird nichts gespeichert, und die Liste der Fehler erscheint im
Formular. Offene Monate, die eine Änderung betrifft, zeigen danach „Plan veraltet: neu planen“.

Was in die Korrekturdatei kommt: Wochenstunden, Bereiche, Stundenkonto, Ersatzruhe, Eintritt und Austritt,
gewohnte Verteilung, Familienregeln und Einschränkungen. Nicht dorthin kommen neu angelegte Personen und
geänderte Namen und Kürzel; sie trägt man im Quellsystem nach (siehe
[Daten und Korrekturdatei](daten.md#neuer-datensatz-änderungen-aus-dispofold-behalten)).

## Person anlegen

**Person anlegen** in der Liste öffnet das Formular **Neue Person**. Pflichtfelder sind Vorname, Nachname,
Wochenstunden, Stundenkonto und Ersatzruhe offen; ohne Bereich wird die Person nie eingeplant. Die Id vergibt das
Programm. Eine in Dispofold angelegte Person kommt nicht in die Korrekturdatei: Wer dauerhaft bleibt, gehört ins
Quellsystem, sonst fehlt die Person nach dem nächsten Import wieder.

## Person entfernen

**Person entfernen** im Formular entfernt die Person nach einer Rückfrage. Kommt sie noch in offenen Monaten vor,
nennt das Programm die Zahl der Verweise und die Monate und fragt, ob sie mit allen Verweisen entfernt werden soll.
Kommt sie in einem abgeschlossenen Monat oder in der Historie vor, lässt sie sich nicht entfernen; dann trägt man
den Austritt ein oder schaltet **Aus dem Quellsystem nicht mehr übernehmen** ein.

## Nicht mehr aus dem Quellsystem übernehmen

Für Personen, die nicht mehr geplant werden sollen, im Quellsystem aber noch geführt werden (etwa nach einem
Austritt, der dort fehlt), und die sich nicht entfernen lassen, weil sie in abgeschlossenen Monaten oder in der
Historie vorkommen. Der Schalter steht auf der Seite der Person unter **Übernahme aus dem Quellsystem**, nur für die
Planung, und wirkt sofort (nach einer Rückfrage):

![Der eingeschaltete Schalter auf der Seite einer Person](../screenshots/person-ausschluss.png)

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

## Wenn jemand anderer inzwischen gespeichert hat

Im Serverbetrieb fragt das Programm wie bei den Eingaben nach, wenn jemand anderer seit dem Öffnen der Seite
Stammdaten gespeichert hat: **OK** übernimmt die eigene Änderung in die neue Fassung, **Abbrechen** verwirft sie und
lädt die neue Fassung. Wurde dieselbe Person auch von der anderen Person geändert, speichert das Programm nicht von
selbst: Die eigenen Angaben stehen wieder im Formular, sie sind zu prüfen und noch einmal zu speichern.
