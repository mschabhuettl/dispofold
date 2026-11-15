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

**Wenn Stammdaten in Dispofold geändert wurden** (unter **Stammdaten**, siehe unten), würde ein Import diese
Änderungen überschreiben. Das Programm fragt dann nach:

- **Datensatz exportieren:** sichert den jetzigen Stand als Datei (siehe [Exportieren](#exportieren)).
- **Trotzdem importieren:** Der Import ersetzt die Stammdaten, die Änderungen in Dispofold gehen verloren.
- **Abbrechen:** Es wird nichts importiert.

## Stammdaten bearbeiten

Unter **Stammdaten** stehen Personen, Bereiche, Abwesenheitsarten, Schichten, Bedarf und Feiertage. Die Rolle
Planung pflegt sie hier, ein neuer Import ist dafür nicht nötig:

- **Neu** über jeder Tabelle legt einen Eintrag an, **Bearbeiten** und **Entfernen** stehen in jeder Zeile. Es
  ist immer nur ein Formular offen. **Speichern** übernimmt die Änderung sofort, **Abbrechen** verwirft sie.
- **Personen:** Name, Kürzel, Wochenstunden, Bereiche, Stundenkonto, offene Ersatzruhe, externe Id, Eintritt
  und Austritt sowie die **Familienregeln** (Wochentage, spätestes Ende, frühester Beginn, keine Nacht, Notiz).
  Mit **Einschränkungen** in der Zeile einer Person legt man fest, welche Schicht oder Kategorie (zum Beispiel
  alle Nachtschichten) sie nie oder nur auf Anfrage macht, an jedem Tag oder an einem Wochentag, auf Wunsch nur
  für eine bestimmte Zeit (gültig ab, gültig bis). Einschränkungen und Familienregeln stehen auch auf der Seite
  **Person**. Was „nur auf Anfrage“ beim Planen bedeutet, steht unter
  [Dienste nur auf Anfrage](#dienste-nur-auf-anfrage).
- **Ids:** Neue Personen und Schichten bekommen ihre Id vom Programm (`PN1`, `SN1`, ...). Bereiche und
  Abwesenheitsarten haben als Id ihr Kürzel, das im Plan steht. Es wird beim Anlegen eingegeben und lässt sich
  danach nicht mehr ändern. Ein entferntes Kürzel bleibt gesperrt und kann nicht neu vergeben werden.
- **Bedarf** gilt entweder an bestimmten Tagen (Montag bis Sonntag, Feiertag) oder an einem Datum.
- Das Programm prüft jede Änderung wie einen Import. Ist etwas falsch, wird nichts gespeichert, und die Liste
  der Fehler erscheint im Formular.

**Entfernen:**

- Wird ein Eintrag noch verwendet (in offenen Monaten oder in anderen Stammdaten, zum Beispiel eine Schicht in
  Einschränkungen oder im Plan), nennt das Programm die Zahl der Verweise und die Monate und fragt, ob er mit
  allen Verweisen entfernt werden soll.
- Kommt ein Eintrag in einem abgeschlossenen Monat vor, lässt er sich nicht entfernen. Bei Personen trägt man
  stattdessen den Austritt ein.
- Ein Bereich lässt sich erst entfernen, wenn seine Schichten entfernt sind. Während einer Planung sind
  Änderungen an betroffenen Monaten gesperrt.
- Offene Monate, die eine Änderung betrifft, zeigen danach „Eingaben geändert“.

**Wenn jemand anderer inzwischen Stammdaten gespeichert hat**, fragt das Programm wie bei den Eingaben nach:
**OK** übernimmt die eigene Änderung in die neue Fassung, **Abbrechen** verwirft sie und lädt die neue Fassung.
Wurde derselbe Eintrag auch von der anderen Person geändert, speichert das Programm nicht von selbst: Die eigenen
Angaben stehen wieder im Formular, sie sind zu prüfen und noch einmal zu speichern.

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
- **Höchststunden im Monat:** Die Stunden einer Person im Monat (Dienste, angerechnete Abwesenheitstage und
  Ersatzruhe) dürfen das Ziel (Soll mit Ausgleich des Stundenkontos) höchstens um eine Toleranz übersteigen,
  voreingestellt 10 % des Ziels. Die Toleranz steht unter **Regeln & Gewichte** bei **Höchstens über dem Ziel
  (%)**; die gesetzlichen Grenzen wie die Höchstarbeitszeit je Woche gelten unabhängig davon. Das Programm plant
  nie darüber. Ein von Hand gesetzter Dienst darf darüber gehen, zählt dann aber als harter Verstoß und steht auf
  der Seite **Person** unter **Ganzer Monat**. Die Liste **Wer kann gefragt werden?** lässt Personen aus, die
  der Dienst über diese Grenze brächte.
- **Ziel für diesen Plan:** Braucht der Bedarf weniger Arbeit, als die Ziele aller Personen zusammen ergeben,
  strebt das Programm ein verkleinertes Ziel an: Der Arbeitsanteil jedes Ziels (Ziel ohne angerechnete
  Abwesenheitstage) schrumpft um denselben Anteil. So muss niemand Stunden über den Bedarf hinaus sammeln, und
  der Plan kann zusammenhängende Dienstblöcke bevorzugen und einzelne freie Tage zwischen Diensten vermeiden.
  Die harte Obergrenze (Höchststunden im Monat) rechnet weiter mit dem echten Ziel. Die Seite **Person** zeigt
  Soll, Ziel und, wenn es abweicht, das Ziel für diesen Plan mit den geplanten und angerechneten Stunden
  daneben; die Zeile **Warum dieser Dienst?** nennt beide Ziele. Für Lesende sind die Ziele ausgeblendet, weil
  sie das Stundenkonto enthalten.
- **Bedarfslücken:** Zeitabschnitte, in denen weniger Personen eingeteilt sind als der Bedarf verlangt. Die
  Zeile **Bedarfslücken** unter dem Plan zeigt sie je Tag, **Ungedeckter Bedarf** die Summe in
  Personenstunden. Daneben steht **Überbesetzung**: Personenstunden über der Höchstbesetzung (wo keine gesetzt
  ist, über der Mindestbesetzung) und Dienste, die keinen Bedarf abdecken. Lücken sind kein Fehler des Solvers:
  Reicht das Personal nicht, bleibt die Lücke sichtbar, statt dass eine harte Regel verletzt wird. Ein Klick auf die Zahl eines Tages listet die Lücken dieses
  Tages mit Bereich, Uhrzeit und fehlender Besetzung, jeweils mit der Liste **Wer kann gefragt werden?** (siehe
  [Dienste nur auf Anfrage](#dienste-nur-auf-anfrage)).
- **Einzelne freie Tage / Blocktage abweichend:** Zwei Kennzahlen der Statuszeile nach der Planung.
  **Einzelne freie Tage** zählt freie Tage, die zwischen zwei Diensten liegen (wenig Erholung). **Blocktage
  abweichend** zählt die Tage, um die Dienstblöcke kürzer oder länger sind als bevorzugt. Beide sollen klein
  sein; sie sind keine Fehler, sondern Maß für die Güte des Plans.
- **Score:** Die Bewertung in drei Stufen: hart, mittel, weich. Mittel umfasst ungedeckten Bedarf und
  Besetzung über dem Bedarf, beides in der Statuszeile in Personenstunden. Eine fehlende Minute wiegt schwerer als
  jeder ganze Dienst über dem Bedarf: Ist jemand frei, wird eine Lücke immer zuerst gefüllt. Besetzung über dem
  Bedarf wiegt schwerer als jede weiche Regel: Wer unter seinem Soll liegt, bleibt darunter, statt dass Stellen
  doppelt besetzt werden. Einzelheiten in [regeln.md](regeln.md).

Weitere Seiten:

- **Plan nach Funktion:** derselbe Monat, aber je Funktion statt je Person, siehe
  [Plan nach Funktion](#plan-nach-funktion).
- **Bereichs-Zeitachse:** Dienste eines Bereichs als Tag oder Woche und die Besetzung gegen den Bedarf. Lücken
  sind rot markiert und darunter als Liste mit Uhrzeit und fehlender Besetzung aufgeführt. **Wer kann gefragt
  werden?** in einer Zeile der Liste klappt darunter die Personen auf, die man für diese Lücke fragen kann.
- **Person:** Eine Person auswählen. Die Seite zeigt Soll, Ziel, Ziel für diesen Plan, geplante Stunden, Stundenkonto vorher und danach,
  Nächte, Wochenenden, Feiertage, unbeliebte Dienste, erfüllte Wünsche und Ersatzruhe. Unter **Warum dieser
  Dienst?** steht für jeden Tag (oder **Ganzer Monat**), welche Regeln gegriffen haben, mit ihrer Stufe
  (**Hart**, **Bedarf**, **Weich**).
- **Fairness-Übersicht:** Alle Personen nebeneinander, je Kennzahl mit Minimum, Mittelwert und Maximum, auch über
  die Vormonate (Historie). Mit **Auf 40 Wochenstunden normiert** werden Teilzeitkräfte vergleichbar. Bei der
  Fairness zählt die Abweichung vom Mittelwert: Ein Treffer in der Erklärung erscheint nur bei Personen über dem
  Mittelwert.
- **Regeln & Gewichte:** Regelwerte (zum Beispiel Ruhezeiten) und die Gewichte der weichen Regeln. Die tägliche
  Ruhezeit kann nicht unter 11 Stunden gesetzt werden. Das Gewicht von **Dienst nur auf Anfrage** ist fest
  („fest“ statt eines Reglers): Solche Dienste plant das Programm ohnehin nie selbst ein.

## Plan nach Funktion

![Plan nach Funktion](screenshots/funktionen.png)

Unter **Plan nach Funktion** steht derselbe Monat wie im **Dienstplan**, aber je Funktion: Eine Funktion ist
eine Schicht eines Bereichs. Die Spalten sind die Tage des Monats (Wochenenden und Feiertage hinterlegt wie im
Dienstplan), die Zeilen die Funktionen, nach Bereichen gruppiert. Gezeigt werden Schichten mit mindestens einem
Dienst im Monat und Schichten, deren Zeit ein Bedarf des Bereichs überschneidet.

- **Eine Zeile je Position:** Arbeiten an einem Tag zwei Personen in derselben Schicht, stehen sie in zwei
  Zeilen untereinander (nach Namen sortiert). Eine Schicht hat so viele Zeilen, wie an ihrem vollsten Tag
  Personen eingeteilt sind.
- **In der Zelle** steht das Kürzel der Person, der ganze Name erscheint beim Darüberfahren. Unter **Anzeige**
  lässt sich stattdessen „Nachname Vorname“ wählen; der Browser merkt sich die Wahl. Fixierte Dienste,
  Konflikte und Dienste nur auf Anfrage sind markiert wie im Dienstplan.
- **Zeile „offen“** (rot): Hat ein Bereich im Monat Bedarfslücken, steht unter seinen Schichten eine Zeile
  „offen“. Jede Lücke steht dort genau einmal, am Tag ihres Beginns (eine Lücke von 19:00 bis 07:00 also am
  ersten Tag), mit der Zahl der fehlenden Personen und der Uhrzeit, zum Beispiel „1 · 07:00-19:00“. Mehrere
  Lücken eines Tages stehen untereinander in derselben Zelle.
- **Ein Klick auf einen Namen** öffnet dieselbe Auswahl wie im Dienstplan (Dienst ändern, frei, fixieren).
  **Ein Klick auf eine Zelle der Zeile „offen“** öffnet die Liste **Wer kann gefragt werden?** für die Lücken
  dieses Tages.
- Mit den Pfeiltasten bewegt man sich wie im Dienstplan durch die Zellen, Enter öffnet die Auswahl.
- **Drucken** (Druckfunktion des Browsers): A4 quer, der Monat in zwei Tabellen, Tag 1 bis 15 und Tag 16 bis
  zum Monatsende. Jede Hälfte beginnt auf einer neuen Seite; mit vielen Funktionen reicht eine Hälfte über
  mehrere Seiten, der Tabellenkopf steht dann auf jeder Seite. Fixierte Dienste sind eingerahmt, die Zeile
  „offen“ wird mitgedruckt.

## Dienste nur auf Anfrage

![Wer kann gefragt werden?](screenshots/anfrage.png)

Eine Einschränkung „nur auf Anfrage“ (unter **Stammdaten**, **Einschränkungen**) heißt: Das Programm plant
diese Schicht für diese Person nie von selbst ein. Fehlt dadurch Personal, bleibt die Lücke im Plan stehen.
Die Planung fragt die Person und setzt den Dienst dann von Hand.

- **Wer kann gefragt werden?** Diese Liste steht bei jeder Lücke: im **Dienstplan** nach einem Klick auf die
  Zahl in der Zeile **Bedarfslücken**, in **Plan nach Funktion** nach einem Klick auf eine Zelle der Zeile „offen“ und in der
  Lückenliste der **Bereichs-Zeitachse**. Sie nennt die Personen, für die eine Schicht des Bereichs in dieser
  Lücke an diesem Tag nur auf Anfrage geht, die an dem Tag frei sind (kein Dienst, keine Abwesenheit, nicht
  fixiert) und für die der Dienst keine harte Regel verletzt (auch nicht die Höchststunden im Monat). Je Person stehen Schicht, Datum und Stunden
  (geplant von Soll); wer am wenigsten Stunden hat, steht oben. Passt niemand, sagt die Liste das.
- **Ein Klick auf einen Namen** setzt den Dienst für diese Person und fixiert ihn. Die nächste Planung behält
  ihn bei.
- Im Plan ist ein solcher Dienst unten links mit einer kleinen Ecke markiert (**nur auf Anfrage**). Er zählt
  nicht als harter Verstoß.

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

Unter **Import/Export**, **Datensatz exportieren**, speichert man alle Stammdaten und Planungsmonate als Datei
im Format von [datenformat.md](datenformat.md). Die Datei lässt sich später wieder importieren. Sie enthält
personenbezogene Daten (auch Stundenkonten, Einschränkungen und Familienregeln) und ist entsprechend sicher
aufzubewahren.

Ebenfalls unter **Import/Export**, **Plan exportieren**, als XLSX (Excel), CSV oder JSON. Im Programmfenster der Desktop-Version
landet die Datei im Ordner Downloads, eine Meldung nennt den Namen.

- Nur die Rolle Planung kann exportieren. Während einer Planung ist der Export gesperrt.
- Hat der Plan harte Verstöße, endet der Dateiname auf `-mit-verstoessen`. In XLSX heißt das Blatt
  „Dienstplan mit Verstößen“ und trägt den Hinweis „Achtung: … harte Regelverstöße, Plan nicht verwendbar“; in
  CSV steht dieser Hinweis in der ersten Zeile.
- **Plan importieren** auf derselben Seite ersetzt den Plan des Monats durch einen früher exportierten Plan im
  JSON-Format.

## Rolle „nur lesen“

Im Serverbetrieb gibt es neben der Planung die Rolle **nur lesen** (`viewer`). Sie sieht Dienstplan, Plan nach
Funktion, Zeitachse, Personen und Fairness, kann aber nichts ändern, nicht planen, nicht abschließen und nicht
exportieren. Aus Datenschutzgründen sieht sie außerdem nicht:

- Wünsche und die Quote erfüllter Wünsche,
- Einschränkungen und Familienregeln von Personen (auch nicht die Markierung „nur auf Anfrage“ und nicht die
  Liste **Wer kann gefragt werden?**),
- den Grund einer Abwesenheit (sie erscheint nur als „abwesend“),
- das Stundenkonto.
