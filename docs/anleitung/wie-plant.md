# Wie das Programm plant

Diese Seite erklärt, was in eine Planung hineingeht, was herauskommt und wie das Programm zwischen Regeln, Bedarf
und Wünschen abwägt. Für die tägliche Arbeit braucht man sie nicht; sie hilft, wenn ein Ergebnis überrascht.

## Wer rechnet

Dispofold plant den Monatsdienstplan. Das Rechnen übernimmt **Timefold Solver**, eine freie Planungs-Engine. Sie
probiert sehr viele mögliche Pläne aus, bewertet jeden nach Regeln und behält einen guten. Von Dienstplänen weiß
sie von sich aus nichts. Alles andere ist Dispofold: die Regeln aus dem Kollektivvertrag und die Planungsregeln
mit ihren Gewichten, die Daten (Personen, Bereiche, Schichten, Bedarf, Abwesenheiten, Wünsche, Historie,
Stundenkonto), Import und Export, die Seiten zum Planen, Prüfen, Ändern und Abschließen, die Auswertungen und
„Warum dieser Dienst?“. Kurz gesagt: Timefold rechnet, Dispofold sagt, was zu rechnen ist, und zeigt das Ergebnis.

Dispofold arbeitet ganz ohne Internet. Timefold läuft in Dispofold, auf dem eigenen Rechner oder dem eigenen
Server, und keine Daten verlassen ihn. Was ins Netz gehen kann, steht unter
[Häufige Fragen](fragen.md#häufige-fragen) unter „Braucht Dispofold Internet?“.

## Was hineingeht

- **[Stammdaten](begriffe.md#stammdaten):** Personen (mit Wochenstunden, Bereichen, Stundenkonto, Einschränkungen,
  Familienregeln und gewohnter Verteilung), Bereiche, Schichten, Bedarf je Bereich und Tageszeit,
  Abwesenheitsarten und Feiertage. Sie ändern sich selten und kommen meist als Datensatz aus dem Quellsystem.
- **[Eingaben](begriffe.md#eingaben) des Monats:** Abwesenheiten, Wünsche und Vorgaben.
- **Regeln:** feste Grenzen (Ruhezeiten, Höchstarbeitszeiten und Wochenschnitt nach dem Kollektivvertrag, freie
  Sonntage als einstellbarer Standardwert) und die Planungsziele mit ihren Gewichten.
- **[Historie](begriffe.md#historie):** die Zahlen der abgeschlossenen Vormonate (gearbeitete Stunden,
  Wochenstunden, Nächte, Wochenenden, Feiertage, fair verteilte Dienste, erfüllte Wünsche, Stundenkonto,
  Ersatzruhe).

## Was herauskommt

Ein Dienstplan für den Monat, eine Zelle je Person und Tag: frei, ein Dienst aus den Schichten ihrer Bereiche oder
ein Ersatzruhetag.

## Wie der Solver abwägt

Der [Solver](begriffe.md#solver) ist der Teil des Programms, der plant. Er bewertet jeden Plan in drei Gruppen, die
streng nacheinander zählen:

1. **[Regelverstoß](begriffe.md#regelverstoß):** Ruhezeiten, Höchstarbeitszeiten, Höchststunden im Monat, der
   Wochenschnitt über die [Durchrechnung](begriffe.md#durchrechnung) (je Person die eigenen Wochenstunden plus
   Spielraum), freie Sonntage, frei mitten in einer Abwesenheit, Qualifikation, Abwesenheiten, Sperren. Muss 0
   sein. Ein Plan mit Regelverstößen darf so nicht verwendet werden.
2. **Bedarf:** fehlende Besetzung ([Lücken](begriffe.md#lücke)) und [Überbesetzung](begriffe.md#überbesetzung),
   in Personenminuten. Eine fehlende Minute wiegt schwerer als jeder ganze Dienst über dem Bedarf.
3. **[Qualität](begriffe.md#qualität):** Stunden treffen, faire Verteilung, Wünsche, Dienstblöcke, Familienregeln,
   gewohnte Verteilung und weitere Regeln, jede mit einem einstellbaren [Gewicht](begriffe.md#gewicht).

Ein Plan mit weniger Regelverstößen ist immer besser, egal wie es um Bedarf und Qualität steht; bei gleich vielen
Regelverstößen entscheidet der Bedarf, erst dann die Qualität. Reicht das Personal nicht, bleibt deshalb eine
Lücke sichtbar, statt dass ein Regelverstoß entsteht. Und wer unter seinem Soll liegt, bleibt darunter, statt dass
Stellen doppelt besetzt werden. Die drei Zahlen zusammen heißen [Bewertung](begriffe.md#bewertung); im Dienstplan
stehen sie unter **Erweitert**.

## Soll und Ziel

Das [Soll](begriffe.md#soll) sind die vertraglichen Stunden des Monats (Wochenstunden durch 5 je Arbeitstag). Das
[Ziel](begriffe.md#ziel) ist das Soll, korrigiert um einen Teil des [Stundenkontos](begriffe.md#stundenkonto) (ein
Plus senkt es, ein Minus erhöht es). Braucht der Bedarf weniger Arbeit, als alle Ziele zusammen ergeben, plant das
Programm gegen ein verkleinertes Ziel: Jedes Ziel schrumpft um denselben Anteil, so muss niemand Stunden über den
Bedarf hinaus sammeln. Die Seite einer Person sagt, warum Ziel und Soll sich unterscheiden.

## Was fest ist und was nur gewünscht

- **[Vorgaben](begriffe.md#vorgabe)** (unter **Eingaben**) sind Dienste oder freie Tage, die fest gelten. Der
  Solver ändert sie nicht.
- **[Fixiert](begriffe.md#fixiert)** ist eine Zelle im Plan, die die nächste Planung beibehält. Jede von Hand
  gesetzte Zelle ist fixiert; **Plan fixieren** fixiert alle Dienste des Monats auf einmal. Fixierungen lassen
  sich lösen, Vorgaben nicht (sie werden nur unter **Eingaben** entfernt).
- **Abwesenheiten** sind ebenfalls fest: An diesen Tagen gibt es keinen Dienst.
- **[Wünsche](begriffe.md#wunsch)** erfüllt der Solver, so gut es geht, und verteilt die Erfüllung gleichmäßig auf
  alle.
- **Familienregeln** (persönliche Regeln je Wochentag) und die **gewohnte Verteilung** sind Qualitätsregeln: Sie
  werden eingehalten, wenn es geht.
- **Nur von Hand:** Eine Schicht mit dieser Einschränkung plant das Programm für die Person nie selbst ein.
  Siehe [Dienste nur von Hand](plan-bearbeiten.md#dienste-nur-von-hand).

## Monate und Historie

Jeder Monat hat seine Eingaben und seinen Plan. Ein Plan ist zuerst **Entwurf**: Er kann geändert und neu geplant
werden. Ist der Monat fertig, wird er **abgeschlossen**: Seine Zahlen kommen in die Historie, er ist danach nur
lesbar. Die Historie zählt für die Fairness, die Wünsche und den Wochenschnitt der Folgemonate, das Stundenkonto
und die Ersatzruhe nach dem Monat für den nächsten. Die letzten Dienste des Vormonats zählen für die Ruhezeiten am
Monatsanfang. Monate werden der Reihe nach abgeschlossen.

> **Mehr Hintergrund:** Jede Regel mit ihren Zahlen, Gewichten und Beispielen steht in [regeln.md](../regeln.md).
