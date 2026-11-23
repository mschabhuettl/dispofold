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

## Angerechnet

Stunden, die zählen, als wäre gearbeitet worden: Abwesenheitstage, die das Soll senken, und Ersatzruhetage, je
Arbeitstag Wochenstunden durch 5. Die Planung vergleicht geplante plus angerechnete Stunden mit dem [Ziel](#ziel).

## Auf Anfrage verfügbar

Die Liste der Personen, die man für eine [Lücke](#lücke) fragen kann: Sie machen die Schicht nur auf Anfrage, sind
an dem Tag frei, und der Dienst ergibt keinen Regelverstoß. Siehe
[Dienste nur auf Anfrage](plan-bearbeiten.md#dienste-nur-auf-anfrage).

## Bedarf

Wie viele Personen ein [Bereich](#bereich) zu welcher Uhrzeit braucht: eine **Mindestbesetzung** und auf Wunsch
eine **Höchstbesetzung**, an bestimmten Wochentagen, an Feiertagen oder an einem Datum. Der Bedarf ist neben den
Regeln die wichtigste Vorgabe für die Planung. Siehe [Bereiche, Schichten und Bedarf](stammdaten.md#bedarf).

## Belastung

Wie viele Nächte, Wochenenden, Feiertage und [fair verteilte Dienste](#fair-verteilte-dienste) eine Person hat, umgerechnet
auf 30 verfügbare Tage bei 40 Wochenstunden, mit den Vormonaten. So sind Teilzeit und lange Abwesenheit
vergleichbar. Die [Fairness](#fairness) vergleicht die Belastung. Siehe [Auswertungen](auswertungen.md#fairness).

## Bereich

Ein Arbeitsplatz oder Aufgabengebiet mit eigenen Schichten und eigenem Bedarf, im Demodatensatz zum Beispiel
„Notrufannahme“. Eine Person wird nur in ihren Bereichen eingeplant. Siehe
[Bereiche, Schichten und Bedarf](stammdaten.md).

## Datensatz

Eine JSON-Datei mit allen [Stammdaten](#stammdaten) und den Eingaben von Planungsmonaten, wie sie das
[Exportprogramm](#exportprogramm) liefert oder Dispofold mit **Datensatz exportieren** speichert. Siehe
[Daten und Korrekturdatei](daten.md).

## Dienstblock

Mehrere Diensttage hintereinander. Die Planung bevorzugt Blöcke in der eingestellten Länge (Standard 2 bis 4 Tage);
kürzere und längere Blöcke kosten in der [Qualität](#qualität). Siehe [Regeln einstellen](einstellungen.md).

## Einschränkung

Was eine Person nicht macht: eine Schicht oder alle Schichten einer [Schichtart](#schichtart), an jedem Tag oder an
einem Wochentag, auf Wunsch befristet. **Nie** heißt: Das Programm plant es nie, auch nicht von Hand ohne
Regelverstoß. **Nur auf Anfrage** heißt: Das Programm plant es nie von selbst, die Planung kann den Dienst aber
nach Rückfrage setzen. Siehe [Person bearbeiten](personen.md#person-bearbeiten).

## Einzelne freie Tage

Ein freier Tag zwischen zwei Diensten, ohne Abwesenheit und ohne Ersatzruhe an diesem Tag. Er erholt wenig und
kostet deshalb in der Qualität. Die Zahl steht über dem Dienstplan; sie ist kein Fehler, sondern ein Maß für die
Güte des Plans.

## Entwurf

Status eines Plans, der noch geändert und neu geplant werden kann. Gegenstück: [abgeschlossen](#abgeschlossen).

## Ersatzruhe

Ein freier Tag als Ausgleich für einen Dienst an einem Feiertag, im Plan „ER“. Wer einen Feiertagsdienst hat,
erwirbt einen Ersatzruhetag; offene Ersatzruhetage plant das Programm in den Folgemonaten ein.

## Exportprogramm

Das Programm, das den [Datensatz](#datensatz) aus dem [Quellsystem](#quellsystem) erzeugt. Es wendet dabei die
[Korrekturdatei](#korrekturdatei) an. Es gehört nicht zu Dispofold; die IT richtet es ein.

## Fairness

Wie gleichmäßig die [Belastung](#belastung) verteilt ist. Die Planung gleicht über die letzten Monate aus. Siehe
[Auswertungen](auswertungen.md#fairness) und [regeln.md](../regeln.md#fairness).

## Familienregel

Eine persönliche Regel für bestimmte Wochentage, zum Beispiel wegen Kinderbetreuung: Ende spätestens, Beginn
frühestens oder keine Nacht. Die Planung hält sie ein, wenn es geht. Siehe
[Person bearbeiten](personen.md#person-bearbeiten).

## Fixiert

Eine Zelle im Plan, die die nächste Planung beibehält. Jede von Hand gesetzte Zelle ist fixiert; im Plan ist sie
eingerahmt. **Plan fixieren** fixiert alle Dienste des Monats, **Fixierungen lösen** gibt alle frei. Anders als eine [Vorgabe](#vorgabe) gehört eine Fixierung zum
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

Die Zahlen der abgeschlossenen Vormonate je Person: Nächte, Wochenenden, Feiertage, fair verteilte Dienste, Wünsche,
Stundenkonto und Ersatzruhe. Sie zählt für die Fairness und als Ausgangspunkt für Stundenkonto und Ersatzruhe des
nächsten Monats.

## Höchststunden im Monat

Die Grenze, wie weit die Stunden einer Person über ihrem [Ziel](#ziel) liegen dürfen (Standard 10 %). Darüber ist
es ein [Regelverstoß](#regelverstoß).

## Korrekturdatei

Die Datei `korrekturen.json` mit allen Änderungen, die in Dispofold an Personen, Einschränkungen und Bedarf gemacht
wurden. Das [Exportprogramm](#exportprogramm) wendet sie bei jedem Export an, so kommen die Änderungen mit jedem
neuen Datensatz wieder mit. Siehe [Daten und Korrekturdatei](daten.md#korrekturdatei).

## Lücke

Eine Zeit, in der weniger Personen eingeplant sind, als der Bedarf verlangt, auch **Bedarfslücke**. Die Größe steht
in Personenstunden: fehlende Personen mal Stunden. Eine Lücke ist kein Fehler des Programms, sondern heißt: Es war
niemand mehr frei, der den Dienst machen darf. Siehe [Planen und Prüfen](planen.md#offener-bedarf).

## Nachtausgleich

Die Fairness der Nachtdienste. Wer im Nachtausgleich ist, hängt von der Einstellung **Nachtdienste** ab: Bei „Wie
bisher“ folgen die Nächte der [gewohnten Verteilung](#gewohnte-verteilung), bei „Gleichmäßig“ werden sie auf alle
verteilt. Wer [nie Nacht](#nie-nacht) macht, ist nie im Nachtausgleich.

## Nie Nacht

Eine Person, die keine Nachtdienste macht: Sie hat eine Einschränkung „Nie“ für jeden Tag auf alle Nachtschichten
ihrer Bereiche oder eine Familienregel „keine Nacht“ an allen sieben Wochentagen.

## Plan veraltet

Hinweis über dem Dienstplan: Seit der letzten Planung haben sich Eingaben, Stammdaten, Regeln, die Historie oder
der Plan des Vormonats geändert. Abhilfe: neu planen. Siehe
[Hinweis „Plan veraltet: neu planen“](planen.md#hinweis-plan-veraltet-neu-planen).

## Planungsmonat

Der Monat, um den es auf einer Seite geht. Er steht oben auf jeder Seite, die zu einem Monat gehört.

## Qualität

Die dritte Gruppe der Bewertung eines Plans: wie gut der Plan ist, wenn er keine Regel verletzt und den Bedarf
deckt (Stunden, Fairness, Wünsche, Blöcke, Familienregeln, gewohnte Verteilung). Siehe
[So funktioniert es](index.md#so-funktioniert-es).

## Quellsystem

Das System, in dem die Personen und ihre Verträge gepflegt werden, etwa die Personalverwaltung. Dispofold bekommt
die Daten als [Datensatz](#datensatz) über das [Exportprogramm](#exportprogramm).

## Regelverstoß

Die erste und wichtigste Gruppe der Bewertung: Ruhezeiten, Höchstarbeitszeiten, Höchststunden im Monat, freie
Sonntage, Qualifikation, Abwesenheiten, Sperren. Ein Plan mit Regelverstößen darf so nicht verwendet werden. Siehe
[regeln.md](../regeln.md#regelverstoß).

## Schicht

Ein Dienst mit Beginn und Ende in einem Bereich, im Plan mit seinem Kürzel, zum Beispiel „A1“. Jede Schicht hat eine
**Schichtart** (Kategorie): Früh, Tag, Spät oder Nacht, oder „aus den Zeiten“.

## Schichtart

Früh, Tag, Spät oder Nacht. Einschränkungen, die gewohnte Verteilung und die Abwechslung der Dienste beziehen sich
auf die Schichtart, nicht auf die einzelne Schicht.

## Score

Die Bewertung des Plans in den drei Gruppen, etwa `0hard/-1680medium/-5952198soft`: Regelverstöße, Bedarf,
Qualität. Je näher an 0, desto besser; die Gruppen zählen streng nacheinander. Er steht unter **Erweitert** über
dem Dienstplan.

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

## Überbesetzung

Mehr Personen eingeplant, als die Höchstbesetzung erlaubt (wo keine gesetzt ist, als die Mindestbesetzung
verlangt), oder Dienste, die gar keinen Bedarf abdecken. Gehört zur Gruppe Bedarf.

## Fair verteilte Dienste

Dienste in Schichten, die in den Stammdaten **Fair verteilen** angekreuzt haben. Die Fairness verteilt sie
gleichmäßig auf alle, die sie machen können. Markiert wird nur, was jemand ankreuzt; Nächte haben ohnehin ihre
eigene Fairness-Regel.

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
[Stundenkontos](#stundenkonto) (Standard 25 %: Plusstunden senken das Ziel, Minusstunden erhöhen es). Beispiel:
Soll 160 h, Stundenkonto +20 h ergibt ein Ziel von 155 h. Braucht der Bedarf weniger Arbeit, als alle Ziele
zusammen ergeben, plant das Programm gegen ein **verkleinertes Ziel**: Jedes Ziel schrumpft um denselben Anteil.
