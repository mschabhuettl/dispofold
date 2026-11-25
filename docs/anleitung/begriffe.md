# Begriffe

Die Wörter, die Dispofold auf seinen Seiten und in dieser Anleitung verwendet, jeweils mit einem Verweis auf die
Seite, die mehr dazu sagt. Jeder Begriff steht für genau eine Sache; die Oberfläche verwendet keine anderen Namen
dafür.

## Abgeschlossen

Status eines Monats, dessen Plan fertig ist. Seine Zahlen stehen in der [Historie](#historie), Plan und Eingaben
sind nur noch lesbar. Gegenstück: [Entwurf](#entwurf). Siehe [Monat abschließen](monatsabschluss.md).

## Abwesenheit

Ein Tag oder Zeitraum, an dem eine Person nicht da ist, etwa Urlaub oder Krankenstand. An diesen Tagen plant das
Programm keinen Dienst. Die **Abwesenheitsart** legt fest, ob die Tage das [Soll](#soll) senken, das
[Stundenkonto](#stundenkonto) belasten oder [Ersatzruhe](#ersatzruhe) sind. Siehe
[Abwesenheiten, Wünsche und Vorgaben](eingaben.md#abwesenheiten).

Im Plan steht eine Abwesenheit mit dem [Code](#code) ihrer Art, kursiv. Die Demodaten haben diese Arten; echte Daten
bringen ihre eigenen mit, die Liste steht unter **Einstellungen**, **Stammdaten**, **Abwesenheitsarten**:

| Code | Abwesenheit | Code | Abwesenheit |
|---|---|---|---|
| U | Urlaub | KA | Karenz |
| K | Krankenstand | PF | Pflegefreistellung |
| ER | Ersatzruhe | SU | Sonderurlaub |
| ZA | Zeitausgleich | FB | Fortbildung |
| KUR | Kur und Rehabilitation | REI | Dienstreise |
| AU | Arbeitsunfall | PM | Papamonat |

Steht in einer Zelle ein Code mit Schrägstrich, etwa „K/S1“, ist das ein Konflikt: ein Dienst trotz Abwesenheit.

## Abzüge der Planqualität

Die Zahl der Gruppe [Qualität](#qualität) in der [Bewertung](#bewertung): Jede Qualitätsregel zieht für jede
Abweichung etwas ab, mal ihrem [Gewicht](#gewicht). Die Zahl ist nie 0 und hat für sich keine Bedeutung; sie hilft
nur, zwei Pläne desselben Monats zu vergleichen. Kein Fehler.

## Angerechnet

Stunden, die zählen, als wäre gearbeitet worden: Abwesenheitstage, die das Soll senken, und Ersatzruhetage, je
Arbeitstag Wochenstunden durch 5 oder, je nach Abwesenheitsart, die Stunden des Dienstes, den die Abwesenheit
ersetzt hat. Die Planung vergleicht geplante plus angerechnete Stunden mit dem [Ziel](#ziel).

## Von Hand einsetzbar

Die Liste der Personen, die man für eine [Lücke](#lücke) fragen kann: Die Schicht geht für sie nur von Hand, sie sind
an dem Tag frei, und der Dienst ergibt keinen Regelverstoß. Siehe
[Dienste nur von Hand](plan-bearbeiten.md#dienste-nur-von-hand).

## Bedarf

Wie viele Personen ein [Bereich](#bereich) zu welcher Uhrzeit braucht: eine **Mindestbesetzung** (Spalte
**Mindestens**) und auf Wunsch eine **Höchstbesetzung** (Spalte **Höchstens**; „= Mindestens“ heißt, es ist keine
eigene gesetzt, dann gilt die Mindestbesetzung auch als Höchstbesetzung), an bestimmten Wochentagen, an Feiertagen
oder an einem Datum. Fehlt jemand, ist das eine [Lücke](#lücke); sind mehr Personen da, ist das
[Überbesetzung](#überbesetzung). Der Bedarf ist neben den
Regeln die wichtigste Vorgabe für die Planung. Siehe [Bereiche, Schichten und Bedarf](stammdaten.md#bedarf).

## Belastung

Wie viele Nächte, Wochenenden, Feiertage und [fair verteilte Dienste](#fair-verteilte-dienste) eine Person hat, umgerechnet
auf 30 verfügbare Tage bei 40 Wochenstunden, mit den Vormonaten. So sind Teilzeit und lange Abwesenheit
vergleichbar. Die [Fairness](#fairness) vergleicht die Belastung. Siehe [Auswertungen](auswertungen.md#fairness).

## Bereich

Ein Arbeitsplatz oder Aufgabengebiet mit eigenen Schichten und eigenem Bedarf, im Demodatensatz zum Beispiel
„Notrufannahme“. Eine Person wird nur in ihren Bereichen eingeplant. Siehe
[Bereiche, Schichten und Bedarf](stammdaten.md).

## Bewertung

Wie gut ein Plan ist, in den drei Gruppen [Regelverstoß](#regelverstoß), Bedarf und [Qualität](#qualität), die
streng nacheinander zählen. Sie steht im Dienstplan unter **Erweitert** neben **Planen** in Worten, etwa
„Regelverstöße 0, fehlende Besetzung 0 Personenstunden, Überbesetzung 90 Personenstunden, Qualität: Abzüge
5 952 198“. Je kleiner jede Zahl, desto besser; nur Regelverstöße und fehlende Besetzung sind Fehler. Die Rohzahl
(technisch der Score, etwa `0hard/-5400medium/-5952198soft`) steht im Tooltip. Für den Alltag reicht die Farbe der
Überschrift über dem Plan. Siehe [Wie das Programm plant](wie-plant.md).

## Code

Wie eine Schicht, ein Bereich oder eine Abwesenheitsart im Plan steht, zum Beispiel „A1“ für eine Schicht oder „U“
für Urlaub. Bei Bereichen und Abwesenheitsarten ist der Code zugleich die Id und lässt sich nach dem Anlegen nicht
mehr ändern. Personen haben stattdessen ein [Kürzel](#kürzel). Siehe [Ids und Codes](stammdaten.md#ids-und-codes).

## Datensatz

Eine JSON-Datei mit allen [Stammdaten](#stammdaten) und den Eingaben von Planungsmonaten, wie sie das
[Exportprogramm](#exportprogramm) liefert oder Dispofold mit **Datensatz exportieren** speichert. Siehe
[Daten und Korrekturdatei](daten.md).

## Dienstblock

Mehrere Diensttage hintereinander. Die Planung bevorzugt Blöcke in der eingestellten Länge (Standard 2 bis 4 Tage);
kürzere und längere Blöcke kosten in der [Qualität](#qualität). Siehe [Regeln einstellen](einstellungen.md).

## Durchrechnung

Der Zeitraum, über den der Wochenschnitt einer Person zählt, Standard das Kalenderhalbjahr (Jänner bis Juni, Juli
bis Dezember). Im Schnitt dieses Zeitraums darf eine Person höchstens ihre eigenen Wochenstunden plus einen
Spielraum arbeiten (Standard 10 %, nie mehr als 46,16 h je Woche); einzelne Wochen dürfen darüber liegen. Die
Vormonate kommen aus der [Historie](#historie). Siehe [Regeln einstellen](einstellungen.md).

## Eingaben

Was in einem Monat dazukommt: Abwesenheiten, Wünsche und Vorgaben, unter dem Menüpunkt **Eingaben**. Sie gelten
nur für ihren Monat. Gegenstück: die [Stammdaten](#stammdaten), die für alle Monate gelten. Siehe
[Abwesenheiten, Wünsche und Vorgaben](eingaben.md).

## Einlesen

Der erste Schritt unter **Daten**, **Übernehmen**: Dispofold liest die Quelle (eine Datensatz-Datei oder, mit einer
Erweiterung, deren Quelle) und zeigt, was darin steht. Das ändert noch nichts in Dispofold; erst
[Übernehmen](#übernehmen) tut das. Siehe [Übernehmen](daten.md#übernehmen).

## Einschränkung

Eine Ausnahme vom [Zustand eines Bereichs](#zustand-eines-bereichs): eine Schicht oder alle Schichten einer
[Schichtart](#schichtart), an jedem Tag oder an einem Wochentag, auf Wunsch befristet. **nie** heißt: Das Programm
plant es nie, auch nicht von Hand ohne Regelverstoß. **nur von Hand** heißt: Das Programm plant es nie von selbst,
die Planung kann den Dienst aber nach Rückfrage setzen. Bis Version 0.25 hieß das „nur auf Anfrage“. Siehe
[Person bearbeiten](personen.md#person-bearbeiten).

## Einzelne freie Tage

Ein freier Tag zwischen zwei Diensten, ohne Abwesenheit und ohne Ersatzruhe an diesem Tag. Er erholt wenig und
kostet deshalb in der Qualität. Die Zahl steht über dem Dienstplan; sie ist kein Fehler, sondern ein Maß für die
Güte des Plans.

## Entwurf

Status eines Plans, der noch geändert und neu geplant werden kann. Vor der ersten Planung steht als Status „noch
kein Plan“. Gegenstück: [abgeschlossen](#abgeschlossen).

## Ersatzruhe

Ein freier Tag als Ausgleich für einen Dienst an einem Feiertag, im Plan „ER“. Wer einen Feiertagsdienst hat,
erwirbt einen Ersatzruhetag; offene Ersatzruhetage plant das Programm in den Folgemonaten ein.

## Erweitert

Ein zugeklappter Abschnitt mit Einstellungen, die man für den Anfang nicht braucht. Es gibt ihn dreimal: im
**Dienstplan** neben **Planen** (Startwert, Zeitlimit, Schrittzahl, **Neu beginnen** und die [Bewertung](#bewertung)), unter
**Regeln** (Nachtzeit, die [Gewichte](#gewicht) und alle Regeln mit Beschreibung) und unter **Daten**,
**Übernehmen** (die Demodaten).

## Exportprogramm

Das Programm, das den [Datensatz](#datensatz) aus dem [Quellsystem](#quellsystem) erzeugt. Es wendet dabei die
[Korrekturdatei](#korrekturdatei) an. Es gehört nicht zu Dispofold; die IT richtet es ein.

## Fair verteilte Dienste

Dienste in Schichten, die in den Stammdaten **Fair verteilen** angekreuzt haben. Die Fairness verteilt sie
gleichmäßig auf alle, die sie machen können. Markiert wird nur, was jemand ankreuzt; Nächte haben ohnehin ihre
eigene Fairness-Regel.

## Fairness

Wie gleichmäßig die [Belastung](#belastung) verteilt ist. Die Planung gleicht über die letzten Monate aus. Siehe
[Auswertungen](auswertungen.md#fairness) und [regeln.md](../regeln.md#fairness).

## Familienregel

Eine persönliche Regel für bestimmte Wochentage, zum Beispiel wegen Kinderbetreuung: Ende spätestens, Beginn
frühestens oder keine Nacht. Die Planung hält sie ein, wenn es geht. Siehe
[Person bearbeiten](personen.md#person-bearbeiten).

## Fixiert

Eine Zelle im Plan, die die nächste Planung beibehält. Jede von Hand gesetzte Zelle ist fixiert; im Plan ist sie
eingerahmt. **Plan fixieren** fixiert alle Dienste und Ersatzruhetage des Monats (freie Tage bleiben offen), **Fixierungen
lösen** gibt alle frei. Anders als eine [Vorgabe](#vorgabe) gehört eine Fixierung zum
Plan, nicht zu den Eingaben. Siehe [Plan bearbeiten](plan-bearbeiten.md#von-hand-ändern-und-fixieren).

## Funktion

Eine Schicht eines Bereichs, so wie der Reiter **Funktionen** den Plan zeigt: eine Zeile je besetzter Position.
Siehe [Plan nach Funktion](plan-bearbeiten.md#plan-nach-funktion).

## Gewicht

Wie stark eine Regel der Gruppe [Qualität](#qualität) zählt, eine ganze Zahl ab 0 (0 schaltet die Regel aus). Die
Gewichte stehen unter **Regeln**, **Erweitert**; meist reicht die [Wichtigkeit](#wichtigkeit).

## Gewohnte Verteilung

Wie sich die Dienste einer Person üblicherweise verteilen, in Prozent je [Schichtart](#schichtart) und je Bereich,
zum Beispiel 90 % Tag und 10 % Nacht. Im Datensatz heißt das Feld `shiftMix`. Die Planung nähert die Dienste diesen
Anteilen an. Siehe [Person bearbeiten](personen.md#person-bearbeiten).

## Historie

Die Zahlen der abgeschlossenen Vormonate je Person: gearbeitete Stunden, Wochenstunden, Nächte, Wochenenden,
Feiertage, fair verteilte Dienste, Wünsche, Stundenkonto und Ersatzruhe. Sie zählt für die Fairness, für den
Wochenschnitt (Durchrechnung) und als Ausgangspunkt für Stundenkonto und Ersatzruhe des nächsten Monats.

## Höchststunden im Monat

Die Grenze, wie weit die Stunden einer Person über ihrem [Soll](#soll) liegen dürfen (Standard 10 %; beim festen
Anteil je Monat über ihrem [Ziel](#ziel)). Darüber ist es ein [Regelverstoß](#regelverstoß).

## Korrekturdatei

Die Datei `korrekturen.json` mit allen Änderungen, die in Dispofold an Personen, Einschränkungen und Bedarf gemacht
wurden. Das [Exportprogramm](#exportprogramm) wendet sie bei jedem Export an, so kommen die Änderungen mit jedem
neuen Datensatz wieder mit. Eine vorhandene Korrekturdatei lässt sich auch in Dispofold laden, etwa beim Umstieg
auf eine Import-Erweiterung; der nächste Import über die Erweiterung wendet sie dann an. Siehe
[Daten und Korrekturdatei](daten.md#korrekturdatei) und [Korrekturdatei laden](daten.md#korrekturdatei-laden).

## Kürzel

Das Namenskürzel einer Person, zum Beispiel „WER“. Der Dienstplan kann statt der Namen die Kürzel zeigen (Anzeige
**Kürzel**). Schichten, Bereiche und Abwesenheitsarten haben stattdessen einen [Code](#code).

## Lücke

Eine Zeit, in der weniger Personen eingeplant sind, als der Bedarf verlangt, auch **Bedarfslücke**. Die Größe steht
in Personenstunden: fehlende Personen mal Stunden. Eine Lücke ist kein Fehler des Programms, sondern heißt: Es war
niemand mehr frei, der den Dienst machen darf. Siehe [Planen und Prüfen](planen.md#offener-bedarf).

## Nachtausgleich

Die Fairness der Nachtdienste. Wer im Nachtausgleich ist, hängt von der Einstellung **Nachtdienste** ab: Bei „Wie
bisher“ folgen die Nächte der [gewohnten Verteilung](#gewohnte-verteilung), bei „Gleichmäßig“ werden sie auf alle
verteilt. Wer [nie Nacht](#nie-nacht) macht, ist nie im Nachtausgleich.

## Nie Nacht

Eine Person, die keine Nachtdienste macht, in einem dieser drei Fälle:

- Ihre Bereiche haben keine Nachtschicht.
- Sie hat Einschränkungen „Nie“ an **jedem Tag** (nicht sieben einzelne Wochentage), die zusammen jede Nachtschicht
  ihrer Bereiche abdecken, als Schichtart Nacht oder Schicht für Schicht, und die den ganzen Planungsmonat gelten. Eine Einschränkung nur für
  einige Wochentage, nur für einen Teil des Monats oder nur für eine von mehreren Nachtschichten genügt nicht.
- Sie hat eine Familienregel „keine Nacht“ an allen sieben Wochentagen.

Siehe [regeln.md](../regeln.md#nachtverteilung).

## Personenstunden

Fehlende oder überzählige Personen mal Stunden. „Lücke (8,5 Personenstunden)“ heißt zum Beispiel: Eine Person
fehlt 8,5 Stunden lang, oder zwei Personen fehlen je 4 Stunden und 15 Minuten. „Überbesetzung 90 Personenstunden“
im ganzen Monat heißt: Zusammengezählt waren 90 Stunden lang eine Person mehr da als nötig, verteilt auf viele
Tage. Siehe [Lücke](#lücke) und [Überbesetzung](#überbesetzung).

## Plan veraltet

Hinweis über dem Dienstplan: Seit der letzten Planung haben sich Eingaben, Stammdaten, Regeln, die Historie oder
der Plan des Vormonats geändert. Abhilfe: neu planen. Siehe
[Hinweis „Plan veraltet: neu planen“](planen.md#hinweis-plan-veraltet-neu-planen).

## Planungsmonat

Der Monat, um den es auf einer Seite geht. Er steht oben auf jeder Seite, die zu einem Monat gehört.

## Pseudonymisieren

Namen, Kürzel und Ids der Personen durch Pseudonyme (P001, P002, ...) ersetzen, damit Datensatz und Plan ohne
Personendaten weitergegeben werden können, etwa für eine Fehlersuche. Der Schlüssel dazu bleibt in der Planung.
Siehe [Pseudonymisieren](daten.md#pseudonymisieren).

## Qualität

Die dritte Gruppe der [Bewertung](#bewertung) eines Plans: wie gut der Plan ist, wenn er keine Regel verletzt und
den Bedarf deckt. Die Qualitätsregeln heißen im Programm so, auch auf den Knöpfen über dem Dienstplan
([Regeln mit Treffern](#regeln-mit-treffern)): **Stunden treffen**, **Fairness Nachtdienste**, **Fairness
Wochenenden**, **Fairness Feiertage**, **Fairness fair verteilte Dienste**, **Wünsche**, **Dienstblöcke**,
**Einzelne freie Tage**, **Vorwärtsrotation**, **Ganze Wochenenden frei**, **Ersatzruhe abbauen**, **Abwechslung
Dienste**, **Abwechslung Bereiche**, **Gewohnte Verteilung**, **Familienregel**, **Dienst nur von Hand**.
Treffer dieser Regeln sind keine Fehler: Der Plan ist trotzdem verwendbar. Siehe
[Wie das Programm plant](wie-plant.md).

## Quellsystem

Das System, in dem die Personen und ihre Verträge gepflegt werden, etwa die Personalverwaltung. Dispofold bekommt
die Daten als [Datensatz](#datensatz) über das [Exportprogramm](#exportprogramm).

## Regeln mit Treffern

Die Knöpfe über dem Dienstplan: je Regel mit Regelverstößen und je Qualitätsregel mit großem Anteil an der
Bewertung einer, mit der Zahl der Treffer. Ein Klick listet Person und Tag. Dieselben Regeln stehen auf der Seite
**Prüfen**. Siehe [Das Ergebnis lesen](planen.md#das-ergebnis-lesen).

## Regelverstoß

Die erste und wichtigste Gruppe der Bewertung: Ruhezeiten, Höchstarbeitszeiten je Dienst und Woche, Höchststunden
im Monat, der Wochenschnitt über die Durchrechnung (je Person die eigenen Wochenstunden plus Spielraum), freie
Sonntage, frei mitten in einer Abwesenheit, Qualifikation, Abwesenheiten, Sperren. Ein Plan mit Regelverstößen darf so nicht verwendet werden. Siehe
[regeln.md](../regeln.md#regelverstoß).

## Schicht

Ein Dienst mit Beginn und Ende in einem Bereich, im Plan mit seinem [Code](#code), zum Beispiel „A1“. Jede Schicht hat eine
**Schichtart** (Kategorie): Früh, Tag, Spät oder Nacht, oder „aus den Zeiten“.

## Schichtart

Früh, Tag, Spät oder Nacht. Einschränkungen, die gewohnte Verteilung und die Abwechslung der Dienste beziehen sich
auf die Schichtart, nicht auf die einzelne Schicht.

## Soll

Die vertraglichen Stunden des Monats: Wochenstunden durch 5 je Arbeitstag (Montag bis Freitag ohne Feiertage),
ohne die Tage vor dem Eintritt und nach dem Austritt.

## Solver

Der Teil des Programms, der plant. Er probiert in vielen Schritten Änderungen am Plan aus und behält, was die
Bewertung verbessert.

## Stammdaten

Was sich selten ändert: Personen, Bereiche, Schichten, Bedarf, Abwesenheitsarten und Feiertage. Sie kommen meist
als [Datensatz](#datensatz) aus dem Quellsystem und lassen sich in Dispofold ändern.

## Stand des letzten Imports

Was Dispofold beim letzten Import gespeichert hat, um später zu erkennen, was seither in Dispofold geändert wurde.
Daraus entstehen die [Korrekturdatei](#korrekturdatei) und die Vorschau von **Änderungen aus Dispofold behalten**.

## Stundenkonto

Die Plus- oder Minusstunden einer Person. Ein Teil davon fließt in das [Ziel](#ziel) jedes Monats; nach dem
Monatsabschluss steht der neue Stand in der Historie.

## Zustand eines Bereichs

Für jede Person und jeden ihrer Bereiche: **planbar** (die Planung darf sie in jeder Schicht des Bereichs
einplanen), **nur von Hand** (die Planung plant sie dort nie selbst, ein Dienst lässt sich aber von Hand setzen)
oder **nie** (ein Dienst dort ist ein Regelverstoß). Was für einzelne Schichten, Tage oder Zeiten abweicht, ist eine
[Einschränkung](#einschränkung). Siehe [Person bearbeiten](personen.md#person-bearbeiten) und
[Bereiche aller Personen](personen.md#bereiche-aller-personen).

## Überbesetzung

Mehr Personen eingeplant, als die Höchstbesetzung erlaubt (wo keine gesetzt ist, als die Mindestbesetzung
verlangt), oder Dienste, die gar keinen Bedarf abdecken. Gehört zur Gruppe Bedarf und steht in
[Personenstunden](#personenstunden). Kein Fehler: Sie entsteht vor allem, wo eine Schicht über mehrere Abschnitte
des Bedarfs reicht und der nächste Abschnitt weniger Personen braucht. Siehe
[Was die Zahlen bedeuten](zahlen.md#überbesetzung).

## Übernehmen

Der zweite Schritt unter **Daten**, **Übernehmen**, nach dem [Einlesen](#einlesen): Das Eingelesene ersetzt die
[Stammdaten](#stammdaten) und die Planungsmonate, die darin stehen, auf Wunsch mit den Änderungen aus Dispofold.
Siehe [Übernehmen](daten.md#übernehmen).

## Vorgabe

Ein Dienst oder freier Tag, der für einen Tag fest gilt, eingetragen unter **Eingaben**. Die Planung ändert ihn
nie, auch nicht beim Lösen der Fixierungen. Siehe [Abwesenheiten, Wünsche und Vorgaben](eingaben.md#vorgaben).

## Wichtigkeit

Die Wahl **weniger**, **normal** oder **mehr** bei einem Planungsziel auf der Seite **Regeln**. Sie setzt die
[Gewichte](#gewicht) der Regeln dieses Ziels auf einmal. Siehe [Regeln einstellen](einstellungen.md#planungsziele).

## Wunsch

Was sich eine Person für einen Tag wünscht: frei, Dienst, eine bestimmte Schicht oder eine Schicht nicht. Die
Planung erfüllt Wünsche, so gut es geht, und verteilt die Erfüllung gleichmäßig. Siehe
[Abwesenheiten, Wünsche und Vorgaben](eingaben.md#wünsche).

## Ziel

Die Stunden, die die Planung für eine Person im Monat anstrebt: das [Soll](#soll), korrigiert um einen Teil des
[Stundenkontos](#stundenkonto). Plusstunden senken das Ziel, Minusstunden erhöhen es. Standard: Das Konto soll am
Ende des Kalenderhalbjahrs bei 0 stehen, jeder Monat gleicht seinen Teil aus. Beispiel: Soll 160 h und
Stundenkonto +20 h im April (bis Ende Juni noch drei Monate) ergeben ein Ziel von 160 - 20 / 3 = 153,3 h. Statt
dessen lässt sich ein fester Anteil je Monat einstellen (25 %), siehe [Regeln einstellen](einstellungen.md). Braucht der Bedarf weniger Arbeit, als alle Ziele
zusammen ergeben, plant das Programm gegen ein **verkleinertes Ziel**: Jedes Ziel schrumpft um denselben Anteil.
