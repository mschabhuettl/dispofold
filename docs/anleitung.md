# Anleitung für die Dienstplanung

Diese Anleitung beschreibt die tägliche Arbeit mit Dispofold: Daten laden, einen Monat planen, das Ergebnis
prüfen, von Hand nachbessern, den Monat abschließen und den Plan exportieren. Nach welchen Regeln der Solver
plant, steht in [regeln.md](regeln.md).

Oben auf jeder Seite steht der **Planungsmonat**. Mit den Pfeilen daneben wechselt man den Monat. Alles auf den
Seiten bezieht sich auf den gewählten Monat.

## Anmelden

- **Server (Docker):** Die Adresse kommt von der IT. Benutzername und Passwort eingeben, **Anmelden**.
  **Abmelden** meldet ab. Neben dem Namen steht die Rolle: **Planung** oder **nur lesen**.
- **Programm am eigenen Rechner:** Es gibt keinen Benutzernamen. Beim Start fragt das Programm nach dem Passwort
  der Daten. Beim allerersten Start legt man dieses Passwort fest (mindestens 12 Zeichen, zweimal eingeben).
  **Ohne dieses Passwort sind die Daten verloren.** Es gibt keine Möglichkeit, es wiederherzustellen. Am
  eigenen Rechner hat man immer die Rolle Planung. Beendet wird das Programm mit **Beenden** oder durch Schließen
  des Fensters. Unter Linux öffnet sich die Oberfläche im Standardbrowser. Ein kleines Fenster „Dispofold
  läuft“ hält das Programm dann in Betrieb: **Öffnen** zeigt die Oberfläche wieder im Browser, **Beenden**
  beendet das Programm.

## Daten laden

Unter **Import/Export**:

- **Datensatz importieren:** Eine Datei im Format von [datenformat.md](datenformat.md) auswählen. Der Import
  prüft die ganze Datei. Ist etwas falsch, wird nichts übernommen, und die Liste der Fehler erscheint (nur mit
  Ids, ohne Namen). Ein Import ersetzt die Stammdaten und die Planungsmonate, die in der Datei stehen.
  Vorhandene Pläne bleiben. Ein abgeschlossener Monat kann nicht überschrieben werden.
- **Demodaten laden (Jänner 2027):** 150 erfundene Personen in fünf Bereichen. Gut zum Ausprobieren. Die
  Demodaten ersetzen alle Stammdaten.

Unter **Stammdaten** stehen danach Personen, Bereiche, Abwesenheitsarten, Schichten und Bedarf. Bei Personen
lassen sich einzelne Angaben bearbeiten (zum Beispiel Eintritt und Austritt).

## Eingaben des Monats

Unter **Eingaben** stehen für den gewählten Monat:

- **Abwesenheiten:** Person, Datum und Art (zum Beispiel Urlaub oder Krankenstand). An diesen Tagen plant der
  Solver keinen Dienst.
- **Wünsche:** frei, Dienst, eine bestimmte Schicht oder eine bestimmte Schicht nicht. Je Person zählen
  höchstens so viele Wünsche, wie unter **Regeln & Gewichte** eingestellt ist (Standard 6), und zwar die ersten
  nach Datum. Die Spalte **Wünsche der Person** zeigt, wenn es zu viele sind.
- **Fixe Dienste:** Dienste, die fest vorgegeben sind. Der Solver ändert sie nicht.

Mit **Hinzufügen** und **Entfernen** ändert man die Listen, mit **Speichern** übernimmt man sie. Solange etwas
nicht gespeichert ist, steht **Ungespeicherte Änderungen** da.

**Wenn jemand anderer inzwischen gespeichert hat** (nur im Serverbetrieb mit mehreren Planerinnen und Planern
möglich), fragt das Programm beim Speichern nach:

- **OK:** Die eigenen Änderungen werden in die neue Fassung übernommen. Hat die andere Person denselben Eintrag
  (gleiche Person, gleicher Tag) anders geändert, gilt die eigene Änderung. Die Meldung nennt, wie viele Einträge
  das betrifft. Danach die Eingaben prüfen und noch einmal **Speichern**.
- **Abbrechen:** Die eigenen Änderungen werden verworfen, die neue Fassung wird geladen.

## Einen Monat planen

Unter **Dienstplan**:

1. **Planen** startet den Solver. Er rechnet im Hintergrund, Laufzeit und Score sind oben sichtbar. Während
   der Planung ist der Plan gesperrt: Zellen, Export und Monatsabschluss gehen erst danach.
2. Die Planung endet von selbst nach der Schrittzahl, spätestens nach dem Zeitlimit. **Stopp** beendet sie
   früher. Der beste Stand bis dahin bleibt erhalten.

Einstellungen neben **Planen** (alle optional; ein leeres Feld nimmt den voreingestellten Wert):

- **Startwert (Seed):** voreingestellt 1. Gleiche Daten, gleicher Ausgangsplan, gleicher Startwert und gleiche
  Schrittzahl ergeben denselben Plan, sofern die Planung vor dem Zeitlimit endet. Weil eine neue Planung auf
  dem gespeicherten Plan aufbaut, gilt das für eine Wiederholung nur mit **Neu beginnen**. Ein anderer
  Startwert ergibt einen anderen, ähnlich guten Plan.
- **Schrittzahl:** Obergrenze der Rechenschritte, voreingestellt 250 000.
- **Zeitlimit (s):** Sicherheitsnetz für die Rechenzeit, voreingestellt 300 Sekunden.

Die voreingestellten Werte legt im Serverbetrieb die IT fest (siehe [betrieb.md](betrieb.md)).
- **Neu beginnen:** Der gespeicherte Plan wird nicht als Ausgangspunkt genommen. Nur fixierte Dienste bleiben.
  Ohne dieses Häkchen baut eine neue Planung auf dem gespeicherten Plan auf.

## Das Ergebnis lesen

Oben im **Dienstplan** stehen:

- **Harte Verstöße:** Muss 0 sein. Harte Regeln sind Ruhezeiten, Höchstarbeitszeiten, Qualifikation und
  Abwesenheiten. Ein Plan mit harten Verstößen darf so nicht verwendet werden. Meist kommen sie von Hand
  gesetzten oder fixierten Diensten. Rot markierte Zellen zeigen einen Dienst trotz Abwesenheit. Welche Regel
  an welchem Tag verletzt ist, zeigt die Seite **Person**.
- **Bedarfslücken:** Zeitabschnitte, in denen weniger Personen eingeteilt sind als der Bedarf verlangt. Die
  Zeile **Bedarfslücken** unter dem Plan zeigt sie je Tag, **Ungedeckter Bedarf** die Summe in
  Personenstunden. Lücken sind kein Fehler des Solvers: Reicht das Personal nicht, bleibt die Lücke sichtbar,
  statt dass eine harte Regel verletzt wird.
- **Score:** Die Bewertung in drei Stufen: hart, Bedarf, weich. Einzelheiten in [regeln.md](regeln.md).

Weitere Seiten:

- **Bereichs-Zeitachse:** Dienste eines Bereichs als Tag oder Woche und die Besetzung gegen den Bedarf. Lücken
  sind rot markiert und darunter als Liste mit Uhrzeit und fehlender Besetzung aufgeführt.
- **Person:** Eine Person auswählen. Die Seite zeigt Soll, geplante Stunden, Stundenkonto vorher und danach,
  Nächte, Wochenenden, Feiertage, unbeliebte Dienste, erfüllte Wünsche und Ersatzruhe. Unter **Warum dieser
  Dienst?** steht für jeden Tag (oder **Ganzer Monat**), welche Regeln gegriffen haben, mit ihrer Stufe
  (**Hart**, **Bedarf**, **Weich**).
- **Fairness-Übersicht:** Alle Personen nebeneinander, je Kennzahl mit Minimum, Mittelwert und Maximum, auch über
  die Vormonate (Historie). Mit **Auf 40 Wochenstunden normiert** werden Teilzeitkräfte vergleichbar. Bei der
  Fairness zählt die Abweichung vom Mittelwert: Ein Treffer in der Erklärung erscheint nur bei Personen über dem
  Mittelwert.
- **Regeln & Gewichte:** Regelwerte (zum Beispiel Ruhezeiten) und die Gewichte der weichen Regeln. Die tägliche
  Ruhezeit kann nicht unter 11 Stunden gesetzt werden.

## Von Hand ändern und fixieren

Eine Zelle im **Dienstplan** anklicken (oder mit den Pfeiltasten hingehen und Enter drücken). Es öffnet sich
eine Auswahl:

- Einen Dienst, **frei** oder **Ersatzruhe** wählen. Eine von Hand gesetzte Zelle ist danach **fixiert**: Die
  nächste Planung behält sie bei.
- **Fixieren** oder **Fixierung lösen** schaltet die Fixierung einer Zelle um, ohne den Dienst zu ändern.
- **Fixierungen lösen** (oben) löst alle Fixierungen des Monats. Fixe Dienste aus den **Eingaben** bleiben
  dabei erhalten. Sie lassen sich nur unter **Eingaben** entfernen.
- Abwesenheiten werden nicht im Plan geändert, sondern unter **Eingaben**.

Ein von Hand gesetzter Dienst kann eine harte Regel verletzen. Dann steigen die **Harten Verstöße**. Vor dem
Abschluss prüfen.

## Hinweise „Eingaben geändert“ und „Vormonat geändert“

Neben dem Status können zwei Hinweise stehen. Beide bedeuten: Der Plan passt nicht mehr ganz zu den Daten,
bitte neu planen.

- **Eingaben geändert:** Eingaben (Abwesenheiten, Wünsche, fixe Dienste), Stammdaten, Regeln oder Gewichte
  oder die Historie der Vormonate wurden nach dieser Planung geändert.
- **Vormonat geändert:** Der Plan des Vormonats wurde nach dieser Planung geändert. Die Ruhezeiten am
  Monatsanfang hängen von den letzten Diensten des Vormonats ab, deshalb nimmt der Solver sie aus dem
  gespeicherten Plan des Vormonats.

## Monatsabschluss

Unter **Import/Export**, **Monat abschließen**. Vorher sollte der Plan fertig sein: keine harten Verstöße,
Lücken geklärt.

- **Was gespeichert wird:** Je Person die Kennzahlen des Monats (Nächte, Wochenenden, Feiertage, unbeliebte
  Dienste, Wünsche), die geplanten Stunden, das Stundenkonto nach dem Monat und die Ersatzruhe nach dem Monat
  (genommen und neu erworben, ein Dienst an einem Feiertag bringt einen Ersatzruhetag). Diese Werte sind die
  Grundlage für Fairness und Stundenkonto der Folgemonate. Von außen importierte Ist-Werte haben Vorrang und
  werden nie überschrieben.
- **Reihenfolge:** Monate werden der Reihe nach abgeschlossen. Gibt es einen Plan für den Vormonat, muss dieser
  zuerst abgeschlossen sein.
- **Abgeschlossen heißt nur lesen:** Plan, Eingaben und Import sind für diesen Monat gesperrt. Der Status zeigt
  **abgeschlossen**.
- **Wieder öffnen:** **Monat wieder öffnen** auf derselben Seite. Monate werden von hinten her geöffnet: Ist der
  Folgemonat abgeschlossen, muss er zuerst wieder geöffnet werden. Beim Öffnen verlassen die Abschlusswerte die
  Historie. Beim erneuten Abschließen werden sie neu geschrieben.

## Exportieren

Unter **Import/Export**, **Plan exportieren**, als XLSX (Excel), CSV oder JSON. Im Programmfenster der Desktop-Version
landet die Datei im Ordner Downloads, eine Meldung nennt den Namen.

- Nur die Rolle Planung kann exportieren. Während einer Planung ist der Export gesperrt.
- Hat der Plan harte Verstöße, endet der Dateiname auf `-mit-verstoessen`. In XLSX heißt das Blatt
  „Dienstplan mit Verstößen“ und trägt den Hinweis „Achtung: … harte Regelverstöße, Plan nicht verwendbar“; in
  CSV steht dieser Hinweis in der ersten Zeile.
- **Plan importieren** auf derselben Seite ersetzt den Plan des Monats durch einen früher exportierten Plan im
  JSON-Format.

## Rolle „nur lesen“

Im Serverbetrieb gibt es neben der Planung die Rolle **nur lesen** (`viewer`). Sie sieht Dienstplan,
Zeitachse, Personen und Fairness, kann aber nichts ändern, nicht planen, nicht abschließen und nicht
exportieren. Aus Datenschutzgründen sieht sie außerdem nicht:

- Wünsche und die Quote erfüllter Wünsche,
- Einschränkungen und Familienregeln von Personen,
- den Grund einer Abwesenheit (sie erscheint nur als „abwesend“),
- das Stundenkonto.
