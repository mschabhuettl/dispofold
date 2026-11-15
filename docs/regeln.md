# Planungsregeln

Dieses Dokument beschreibt alle Regeln, nach denen der Solver den Dienstplan rechnet: was jede Regel tut,
auf welcher Stufe sie wirkt, woher sie kommt und welcher Parameter sie steuert. Es richtet sich an die
Dienstplanung und an alle, die die Betriebsvereinbarung dazu abstimmen.

Quelle der Standardwerte: ein für Leitstellen typischer Kollektivvertrag, im Folgenden "KV". Die genauen
Fundstellen im jeweils anwendbaren KV hält die einführende Organisation selbst fest. Wo der Text eine Frage
offenlässt oder die Betriebsvereinbarung entscheidet, ist der Wert ein Parameter. Das Programm legt dort nichts
selbst aus.

## Stufen

Der Score hat drei Stufen, die streng nacheinander zählen:

| Stufe | Bedeutung | Ziel |
|---|---|---|
| hart | Regeln aus KV und Qualifikation | muss 0 sein |
| mittel | nicht gedeckter Bedarf in Personenminuten | 0, sonst Lückenliste |
| weich | Wünsche, Fairness, Blöcke usw., mit sichtbaren Gewichten | so gut wie möglich |

Ist der Bedarf mit dem vorhandenen Personal nicht erfüllbar, bleibt die harte Stufe trotzdem bei 0. Die
fehlende Besetzung steht dann als Lücke je Bereich und Zeitabschnitt in der Analyse.

Alle Parameter stehen in `Rules` (Oberfläche: Regeln und Gewichte). Gewichte gibt es nur für weiche
Regeln; harte Regeln und der Bedarf haben kein einstellbares Gewicht.

## Grundmodell

- Je Person und Tag gibt es genau eine Zelle: kein Dienst, ein Dienst aus den Schichten des Bereichs oder
  ein Ersatzruhetag. Mehr als ein Dienst am Tag ist damit ausgeschlossen.
- Der Bedarf gilt je Bereich und Tageszeit. Der Solver wählt die Schichtvariante.
- Alle Zeiten gelten in der Zeitzone `timeZone` (Standard Europe/Vienna). Dauer, Stunden und Ruhezeiten sind
  echte Minuten: In der Nacht der Umstellung auf Sommerzeit dauert ein Nachtdienst 20:00 bis 06:00 neun
  Stunden, bei der Umstellung auf Winterzeit elf; so zählen sie für Stunden, Ruhe und Bedarf. Die Höchstdauer
  eines Dienstes wird dagegen an der Dauer laut Schichtkatalog gemessen: Ein regulärer 12-Stunden-Nachtdienst in
  der Herbstnacht (13 echte Stunden) ist kein Verstoß.
- Ein Dienst, der nach Mitternacht endet (Nachtdienst), zählt für Ruhezeit, Wochenstunden und Bedarf mit
  seinen echten Uhrzeiten. Für die Monatsstunden zählt er ganz zum Tag seines Beginns.
- Vormonatsdienste gehen in Ruhezeit, Wochenruhe, Wochenstunden, Blöcke, Rotation und das Wochenende am
  Monatsanfang ein. Sie werden nicht geändert. Sie kommen aus dem gespeicherten Plan des Vormonats; nur wenn es
  keinen gibt, aus dem Import (`previousDuties`). Ändert sich der Plan des Vormonats, nachdem dieser Monat
  geplant wurde, zeigt die Oberfläche das an. Berücksichtigt werden die Dienste ab dem Montag der ersten Woche,
  mindestens eine Woche zurück; für die Abwechslung zählt der ganze Vormonat.
- Ein Wochenende, das im Vormonat beginnt (der 1. ist ein Sonntag), zählt mit dem Samstag aus den
  Vormonatsdiensten. Ein Samstag am Monatsende, dessen Sonntag im Folgemonat liegt, wird erst im Folgemonat
  bewertet.
- Beschäftigung: Tage vor `entryDate` oder nach `exitDate` einer Person werden nicht verplant.
- Ändern sich nach einer Planung die Eingaben (Stammdaten, Planungsmonat, Regeln, Vormonat), gilt der Plan
  als veraltet, bis er neu geplant wird. Die Planung ist ohne Angabe reproduzierbar: Sie endet nach einer
  festen Zahl von Zügen (`SOLVER_MOVE_LIMIT`), gleicher Stand und gleicher Startwert geben denselben Plan.
  Ein Kaltstart beginnt ohne den bisherigen Plan (nur fixierte Zellen bleiben).
- Der Solver startet mit einem einfachen Startplan: Tag für Tag deckt er den offenen Bedarf jedes Bereichs mit
  Personen, die am weitesten unter ihrem Soll liegen, und hält dabei die harten Regeln ein. Einen Dienst, der
  keinen offenen Bedarf deckt, setzt er nicht, auch nicht für die Sollstunden. Danach verbessert
  die lokale Suche den Plan gegen alle Regeln.
- Fixe Dienste (`fixed`) und von Hand fixierte Zellen ändert der Solver nicht. Verletzen sie eine Regel,
  zeigt die Analyse das als Treffer.
- Ist eine Zelle im Plan von Hand fixiert (`pinned`), gilt ihr Wert auch dann, wenn für denselben Tag ein
  fixer Dienst in `fixed` steht. Nicht fixierte Zellen, etwa aus einer früheren Planung, ersetzt der fixe Dienst.

## Harte Regeln

| Id | Regel | Quelle | Parameter (Standard) |
|---|---|---|---|
| `dailyRest` | Mindestens 11 h Ruhe zwischen zwei Diensten | KV, Ruhezeit | `minDailyRestHours` (11) |
| `weeklyRest` | Mindestens 36 h ununterbrochene Ruhe je Kalenderwoche | KV, Ruhezeit | `minWeeklyRestHours` (36) |
| `maxDutyLength` | Ein Dienst dauert höchstens 12 h | KV, Durchrechnung | `maxDutyHours` (12) |
| `maxWeekHours` | Höchstens 60 h Dienst je Kalenderwoche | KV, Durchrechnung | `maxWeekHours` (60) |
| `maxMonthHours` | Im Monat höchstens das Ziel plus eine Toleranz (Dienste, angerechnete Abwesenheiten und Ersatzruhe) | Betrieb | `maxOverTargetPercent` (10 % des Ziels) |
| `averageWeekHours` | Höchstens 46,16 h je Woche im Durchschnitt des Durchrechnungszeitraums | KV, Durchrechnung | `maxAverageWeekHours` (46,16), `averagingWeeks` (52) |
| `qualification` | Nur Personen, die für den Bereich eingeteilt werden dürfen | Stammdaten | `areaIds` der Person |
| `absence` | Kein Dienst und keine Ersatzruhe an Abwesenheitstagen | Abwesenheiten | |
| `employment` | Kein Dienst vor dem Eintritt oder nach dem Austritt | Stammdaten | `entryDate`, `exitDate` |
| `neverRestriction` | Sperren der Art "nie" (Person, Dienst oder Kategorie, Wochentag, Gültigkeit) | Stammdaten | |
| `compRestBalance` | Nicht mehr Ersatzruhetage planen als offen sind | Guthaben | `compRestBalance` |
| `missingShift` | Eine fixierte Schicht gibt es im Schichtkatalog nicht mehr (z. B. nach einem neuen Import) | Plan | |

Einzelheiten:

- **Tägliche Ruhezeit.** Die Strafe ist die fehlende Ruhe in Minuten. Die Verkürzung auf 9 h, die der
  KV für unvorhersehbare Ereignisse im Betrieb erlaubt, ist nicht für die Planung gedacht. Der Parameter kann
  deshalb nicht unter 11 h gesetzt werden (die Regeln werden sonst abgelehnt), und der Solver plant nie
  darunter. Ein Dienst, der noch während der Ruhe nach einem langen Dienst beginnt, zählt ebenfalls.
- **Durchschnitt je Woche.** Gezählt werden die gearbeiteten Stunden der Historie (`hours` der Monate im
  Durchrechnungszeitraum) und die geplanten Dienststunden dieses Monats, geteilt durch die Wochen. Abwesenheiten
  zählen nicht als gearbeitet. Die Regel ist hart, wenn die Historie den ganzen Zeitraum vor diesem Monat
  abdeckt; sonst wird der Durchschnitt nur angezeigt (Kennzahl `averageWeekHours`, `averageComplete` falsch).
- **Fehlende Schicht.** Ist eine fixierte Zelle mit einer Schicht belegt, die es nicht mehr gibt, wird sie
  nicht stillschweigend zu einem freien Tag, sondern als harter Verstoß gemeldet.
- **Wöchentliche Ruhezeit.** Geprüft wird je Kalenderwoche von Montag 00:00 bis Sonntag 24:00. Gesucht ist
  die längste dienstfreie Zeit innerhalb der Woche; Dienste über die Wochengrenze werden an der Grenze
  abgeschnitten. Für die erste Woche zählen die Vormonatsdienste mit. Zeit vor den gelieferten
  Vormonatsdiensten und Zeit nach dem Monatsende gilt als frei; die Planung des Folgemonats prüft diese Woche
  dann mit ihren eigenen Vormonatsdiensten.
- **Wochenstunden.** Je Kalenderwoche zählen die Minuten der Dienste, die in die Woche fallen; ein Dienst
  über Sonntag 24:00 wird geteilt. Vormonatsdienste zählen in der ersten Woche mit.
- **Ersatzruhe-Guthaben.** Bereits als Abwesenheit eingetragene Ersatzruhetage (Abwesenheitsart mit
  `compensatoryRest`) verringern das Guthaben, das der Solver noch verplanen darf. Im Monat durch
  Feiertagsarbeit neu erworbene Ersatzruhe wird bewusst nicht im selben Monat verplant (einfach und
  nachvollziehbar; sie steht in den Kennzahlen als `compRestEarned`).

## Mittlere Stufe: Bedarf und Überbesetzung

| Id | Regel | Gewicht (fest) |
|---|---|---|
| `demand` | Fehlende Besetzung je Bereich und Zeitabschnitt, in Personenminuten | 1 000 000 000 je Personenminute |
| `overstaffing` | Überbesetzung: Personen über der Höchstbesetzung, ohne Höchstbesetzung über der Mindestbesetzung, in Personenminuten | 1 je Personenminute |
| `uselessDuty` | Dienst, der keinen Bedarfsabschnitt seines Bereichs berührt, mit seinen ganzen Minuten | 1 je Minute |

- Warum Überbesetzung auf dieser Stufe steht: Wäre sie weich, würde der Solver Positionen doppelt besetzen,
  nur um Personen auf ihre Sollstunden zu bringen; die Sollstunden (quadriert) wiegen dort schwerer. Auf der
  mittleren Stufe wiegt jede Minute über dem Bedarf schwerer als alle weichen Regeln zusammen. Ist der Bedarf
  gedeckt, bleiben übrige Sollstunden offen (Personen unter dem Soll), statt Positionen doppelt zu besetzen.
- Warum fehlender Bedarf 1 000 000 000 wiegt: Eine fehlende Minute wiegt mehr als alle Überbesetzung, die ein
  ganzer Monatsplan haben kann (auch mit 20 000 Personen). Der Solver lässt also nie eine Lücke offen, um
  Überbesetzung zu vermeiden, auch wenn das Schließen mehrere Dienste verschiebt. Weil die Überbesetzung unter
  diesem Gewicht bleibt, lassen sich beide Teile genau aus der mittleren Stufe lesen: ungedeckte Minuten =
  mittlerer Wert / 1 000 000 000, überbesetzte Minuten = der Rest.
- Von Hand gesetzte und fixe Dienste dürfen überbesetzen; sie erscheinen dann in der mittleren Stufe und in
  der Analyse. Die Statuszeile zeigt ungedeckten Bedarf und Überbesetzung getrennt in Personenstunden.
- Gespeicherte Gewichte für `overstaffing` und `uselessDuty` (früher weich) werden ignoriert.

- Die Bedarfsfenster eines Tages werden an allen Beginn- und Endzeiten der Schichten des Bereichs und an
  den Fenstergrenzen geteilt. In jedem Abschnitt ist die Besetzung konstant.
- Ein Fenster gehört zum Tag seines Beginns. Ein Fenster mit `to <= from` endet am Folgetag.
- Gültigkeit: Regeln mit `date` ersetzen für den Bereich an diesem Tag alle anderen. An Feiertagen ersetzen
  die `HOL`-Regeln eines Bereichs dessen Wochentagsregeln, wenn der Bereich `HOL`-Regeln hat.
- Überlappen sich zwei Fenster desselben Bereichs, werden die Mindestbesetzungen addiert.
- Ein Abschnitt ohne einen einzigen Dienst wird voll gezählt (Mindestbesetzung mal Minuten).
- Vormonatsdienste zählen zur Besetzung, z. B. die Nacht vom 31. auf den 1.

## Weiche Regeln

Jede weiche Regel hat eine Id, ein Gewicht (Standard in Klammern) und erscheint mit ihren Treffern in
der Erklärung je Person.

| Id | Regel | Wirkung | Standardgewicht |
|---|---|---|---|
| `targetHours` | Sollstunden | Abweichung in Viertelstunden, quadriert | 20 |
| `fairNights` | Fairness Nachtdienste | Abweichung der Lasten vom Mittelwert, siehe Fairness | 3 |
| `fairWeekends` | Fairness Wochenenden | siehe Fairness | 3 |
| `fairHolidays` | Fairness Feiertage | siehe Fairness | 3 |
| `fairUnpopular` | Fairness unbeliebte Dienste | siehe Fairness | 2 |
| `wishes` | Wünsche mit gleicher Quote | siehe Wünsche | 300 |
| `blockLength` | Blöcke von 2 bis 4 Diensten | je Tag zu kurz oder zu lang | 6000 |
| `isolatedDayOff` | Kein einzelner freier Tag zwischen Diensten | je Fall | 10000 |
| `forwardRotation` | Vorwärtsrotation | je Rückwärtswechsel | 500 |
| `splitWeekend` | Ganze Wochenenden | je Wochenende mit nur einem Diensttag | 300 |
| `compRestUse` | Ersatzruhe abbauen | Bonus je geplantem Ersatzruhetag | 500 |
| `shiftVariety` | Abwechslung der Dienste | je Paar Dienste gleichen Bereichs und gleicher Kategorie, mit Vormonat | 5 |
| `areaVariety` | Abwechslung der Bereiche | je Paar Dienste im selben Bereich, mit Vormonat (nur bei mehreren Bereichen) | 1 |
| `family` | Familienwunsch | je Dienst gegen die Regel | 800 |
| `onRequest` | Dienst nur auf Anfrage, von Hand gesetzt | je solchem Dienst; Gewicht fest, nicht einstellbar | 1000 |

### Sollstunden

- Monatssoll = Wochenstunden / 5 × Anzahl der Tage Montag bis Freitag, die kein Feiertag sind und in der
  Beschäftigung liegen (anteilig bei Eintritt oder Austritt im Monat).
- Abwesenheiten: Eine Abwesenheitsart mit `reducesTarget` ("senkt das Soll") senkt das Soll an solchen
  Werktagen um Wochenstunden / 5. Hat sie zusätzlich `chargesAccount` ("belastet das Stundenkonto", z. B.
  Zeitausgleich), werden diese Stunden vom Stundenkonto abgebucht. Urlaub: senkt das Soll, belastet das Konto
  nicht. Krankenstand: ebenso. Zeitausgleich: beides. Geplante Ersatzruhetage senken das Soll ebenfalls um
  Wochenstunden / 5.
- Das Ziel des Monats wird um einen Teil des Stundenkontos korrigiert: Ziel = Soll - `accountCarryShare`
  × Stundenkonto (Standard 0,25). Plusstunden senken also das Ziel, Minusstunden erhöhen es. Das Stundenkonto
  am Monatsbeginn ist das Konto nach dem Vormonat aus der Historie, sonst der Wert der Person im Import.
- Stundenkonto nach dem Monat = vorher + geplant + Soll-Senkung - Soll - abgebuchte Stunden.
- Obergrenze (`maxMonthHours`, hart): Die angerechneten Stunden des Monats (Dienste, angerechnete
  Abwesenheiten und Ersatzruhe) dürfen das Ziel um höchstens `maxOverTargetPercent` Prozent des Ziels
  überschreiten (Standard 10 %, auf der Seite Regeln einstellbar, 0 erlaubt). Abwesenheiten zählen mit, senken
  also, was für Dienste bleibt. Strafe: Minuten darüber. Warum hart: Ungedeckter Bedarf (mittlere Stufe) wiegt
  immer schwerer als die Sollstunden (weich); ohne Obergrenze füllt der Solver Lücken mit Teilzeitkräften bis
  zur gesetzlichen Wochengrenze auf. Die gesetzlichen Grenzen gelten weiter. Ein von Hand gesetzter oder fixer
  Dienst darf die Grenze überschreiten, erscheint dann aber als harter Verstoß; die Liste „Wer kann gefragt
  werden?“ nennt niemanden, den der Dienst über die Grenze brächte. Regeln, die vor dieser Grenze gespeichert
  wurden, bekommen den Standard.
- Ziel für diesen Plan: Braucht der Bedarf weniger Arbeit, als die Ziele aller Personen zusammen ergeben,
  wird das Ziel jeder Person um denselben Anteil verkleinert. Angerechnete Abwesenheitstage bleiben, der Rest
  des Ziels (die Arbeit) wird mit Bedarf / Summe der Arbeit malgenommen. Der Bedarf ist die Mindestbesetzung
  aller Abschnitte des Monats in Personenminuten, vor der Planung fest. Teilzeitkräfte bekommen so anteilig
  weniger. Warum: Überbesetzung steht auf der mittleren Stufe, die vollen Ziele sind dann nicht erreichbar,
  und die quadrierte Abweichung würde alle anderen weichen Regeln überdecken; der Solver würde nur noch
  ausgleichen, wer wie weit unter dem Ziel bleibt, statt Blöcke zu bilden. Braucht der Bedarf alle Arbeit oder
  mehr, ändert sich nichts. Wer seinen Anteil nicht arbeiten kann (Sperren, wenige erlaubte Schichten), wird
  nicht eigens berücksichtigt: Die quadrierte Abweichung verteilt den Rest gleichmäßig auf die anderen, und die
  harte Obergrenze bleibt beim echten Ziel. Personenansicht und Analyse zeigen beide Ziele.
- Die Strafe ist die Abweichung in Viertelstunden (vom Ziel für diesen Plan), quadriert, damit sie sich auf viele Personen verteilt statt
  bei wenigen hängen zu bleiben. Beispiel: 4 h Abweichung sind 16 Viertelstunden, Strafe 256 mal Gewicht.

### Fairness

- Gezählt werden je Person Nachtdienste, gearbeitete Wochenenden, Dienste an Feiertagen und unbeliebte
  Dienste (Schicht mit `unpopular`), jeweils zusammen mit den Summen der letzten `historyMonths` Monate
  (Standard 3) aus der Historie.
- Verglichen wird die Last: Dienste je 30 verfügbare Tage einer 40-Stunden-Kraft, in Tausendsteln. Verfügbar
  sind die Tage in der Beschäftigung ohne Abwesenheit, dazu je Historienmonat rund 30 Tage. Wer im Monat
  eintritt oder lange abwesend war, bekommt dadurch nicht alle Nachtdienste; Teilzeit trägt anteilig weniger.
- Verglichen werden alle, die solche Dienste machen können (z. B. Nachtdienste nur Personen mit Nachtschichten
  in ihren Bereichen). Die Strafe ist die Wurzel aus der Summe der quadrierten Abweichungen vom Mittelwert:
  0, wenn alle gleich belastet sind. Die Erklärung je Person zeigt die Abweichung vom Mittelwert und nur bei
  Personen über dem Mittelwert einen Treffer.
- Nachtdienst: ein Dienst, der mindestens `nightMinHours` (2 h) im Nachtfenster `nightStart` bis `nightEnd`
  (22:00 bis 06:00) liegt.
- Wochenende gearbeitet: ein Dienst beginnt am Samstag oder Sonntag.
- Feiertagsdienst: ein Dienst beginnt an einem Feiertag.

### Wünsche

- Je Person zählen höchstens `maxWishesPerPerson` Wünsche des Monats (Standard 6), die ersten nach Datum.
- Arten: frei, Dienst, bestimmte Schicht, bestimmte Schicht nicht.
- Ziel ist eine gleiche Erfüllungsquote je Person, nicht die Reihenfolge der Eingabe. Die Strafe ist
  (nicht erfüllte Wünsche)² / (Wünsche), jeweils mit den Vormonaten. Ein weiterer unerfüllter Wunsch kostet
  bei der Person am meisten, deren Quote schon am schlechtesten ist.

### Blöcke, Rotation, Wochenenden

- Blöcke: aufeinanderfolgende Tage mit Dienst. Kürzer als `minBlock` (2) oder länger als `maxBlock` (4)
  kostet je Tag Unterschied. Ein Block, der am Monatsende endet, gilt nicht als zu kurz, weil er im
  Folgemonat weitergehen kann. Ersatzruhe zählt als freier Tag.
- Einzelner freier Tag: genau ein freier Tag zwischen zwei Diensten.
- Gewichte (ab 0.6.0): Einzelner freier Tag 10000 je Fall, Blocklänge 6000 je Tag. Mit dem Ziel für diesen
  Plan prägen sie den Plan sichtbar: Im Demomonat sanken die einzelnen freien Tage von 261 auf 38, die
  Blocktage außerhalb von 2 bis 4 von 602 auf 208; unerfüllte Wünsche stiegen leicht. Wer die Gewichte auf der
  Seite Regeln einmal gespeichert hat, behält die gespeicherten Werte; dort lassen sie sich auf die neuen
  Standards setzen. Die Analyse zeigt beide Zahlen (`singleDaysOff`, `blockDays`).
- Vorwärtsrotation: die Reihenfolge Früh, Tag, Spät, Nacht. Ein Wechsel von einem Tag auf den nächsten in
  eine frühere Kategorie kostet. Nacht auf Früh ohne genug Ruhe ist ohnehin hart verboten.
- Kategorie einer Schicht: aus dem Schichtkatalog; fehlt sie, gilt Nacht (siehe oben), Früh (Beginn vor
  7:00), Tag (Ende bis 19:00), sonst Spät.
- Ganze Wochenenden: Samstag und Sonntag desselben Wochenendes, von denen nur einer einen Dienst hat (ein
  Wochenende über den Monatsbeginn zählt mit dem Samstag aus dem Vormonat). Ist einer der beiden Tage ein Abwesenheitstag, zählt das Wochenende nicht. Die faire Verteilung der
  gearbeiteten Wochenenden steckt in `fairWeekends`.

### Ersatzruhe

- Personen mit offenem Guthaben bekommen Ersatzruhetage geplant (Bonus je Tag), höchstens bis zum Guthaben
  (harte Regel).
- KV, Feiertagsarbeit: Arbeit an einem Feiertag, der kein Sonntag ist, bringt einen Ersatzruhetag
  (`holidayWorkEarnsCompRest`, Standard ja). Das ist eine Kennzahl (`compRestEarned`) und erhöht das
  Guthaben nach dem Monat, aber keine Regel im Solver.

## Monatsabschluss

- Ein geplanter Monat wird abgeschlossen ("Monat abschließen"). Dann werden je Person die Kennzahlen, das
  Stundenkonto nach dem Monat und das Ersatzruhe-Guthaben nach dem Monat (genommene und neu erworbene Tage) in
  die Historie geschrieben, und der Monat ist schreibgeschützt (Status "abgeschlossen").
- Der Folgemonat beginnt mit diesen Salden. Importierte Ist-Werte (Historie mit `source: import`) haben
  Vorrang vor den Werten eines abgeschlossenen Plans und werden nie überschrieben.
- Ein abgeschlossener Monat kann nur ausdrücklich wieder geöffnet werden; seine Abschlusswerte verlassen dann
  die Historie, bis er erneut abgeschlossen wird.
- Weil die Salden von Monat zu Monat weitergegeben werden, werden Monate der Reihe nach abgeschlossen (ein
  geplanter Vormonat zuerst) und rückwärts wieder geöffnet (ein abgeschlossener Folgemonat zuerst).

### Familie, Sperren auf Anfrage, Bedarf

- Familienwunsch je Person und Wochentag: spätestes Dienstende, frühester Dienstbeginn, keine Nacht. Die
  Uhrzeiten beziehen sich auf den Tag des Dienstbeginns; ein Nachtdienst endet also immer nach einem
  spätesten Ende am selben Tag.
- Sperren der Art "nur auf Anfrage" plant der Solver nie ein, auch nicht, wenn sonst eine Bedarfslücke bleibt:
  Die Planung fragt die Person und trägt den Dienst dann von Hand ein (fixiert) oder als fixen Dienst in den
  Eingaben. Ein solcher Dienst ist kein harter Verstoß; er erscheint als Treffer der Regel `onRequest`, damit
  er sichtbar bleibt. Ihr Gewicht ist fest; ein gespeichertes Gewicht für `onRequest` wird ignoriert. Ein nicht
  fixierter Anfrage-Dienst aus einem früheren Plan fällt bei der nächsten Planung weg. Zu jeder Lücke zeigt die
  Oberfläche, wer gefragt werden kann (siehe [api.md](api.md), `candidates`).
- Sperren gelten für eine Schicht oder für alle Schichten einer Kategorie (z. B. keine Nachtdienste), je
  Wochentag und optional nur von `validFrom` bis `validTo`.
- Abwechslung: gezählt werden Paare von Diensten im selben Bereich und derselben Kategorie (Früh, Tag, Spät,
  Nacht) bzw. im selben Bereich, einschließlich der Dienste des Vormonats.

## Offene Punkte für die Betriebsvereinbarung

Diese Punkte legt der KV-Text nicht eindeutig fest. Das Programm verwendet die genannten Standardwerte; jeder
ist ein Parameter, entscheidet die Betriebsvereinbarung.

1. **Geltung der Durchrechnungsregel für die Disposition.** Ob 12 h je Dienst, 60 h je Woche und 46,16 h im
   Durchschnitt für Disponentinnen und Disponenten gelten, ist im KV nicht ausdrücklich gesagt. Parameter `maxDutyHours`, `maxWeekHours`, `maxAverageWeekHours`.
2. **Reichweite des Ersatzruhetags für Feiertagsarbeit.** Der KV-Text lässt offen, für welche Gruppen er gilt. Parameter `holidayWorkEarnsCompRest`.
3. **Nachtzeit.** Der für die Leitstelle maßgebliche KV-Teil definiert keine Nacht. 22:00 bis 06:00 ist nur ein
   Standardwert. Parameter `nightStart`, `nightEnd`, `nightMinHours`.
4. **Woche.** Wochenruhe und Wochenstunden werden je Kalenderwoche (Montag bis Sonntag) geprüft, nicht über
   ein gleitendes 7-Tage-Fenster.
5. **Durchschnitt 46,16 h.** Er wird über `averagingWeeks` Wochen (Standard 52) aus der Historie und dem Monat
   gerechnet und ist hart, wo die Historie den Zeitraum abdeckt. Ob die Durchrechnung rollierend oder je
   Kalenderjahr gilt und ob Abwesenheiten als Arbeitszeit zählen, ist festzulegen. Parameter
   `accountCarryShare` steuert, wie viel des Kontos ein Monat ausgleichen soll.
9. **Abwesenheitsarten.** Welche Abwesenheiten das Soll senken und welche das Stundenkonto belasten
   (`reducesTarget`, `chargesAccount`), legt der Import je Art fest; die Zuordnung ist mit der
   Personalverrechnung abzustimmen.
10. **Zeitumstellung.** Ein 12-Stunden-Nachtdienst dauert in der Herbstnacht 13 echte Stunden. Das Programm
    zählt diese Stunden, misst die Höchstdauer aber an der Dauer laut Schichtkatalog; ob das so gelten soll,
    entscheidet die Betriebsvereinbarung.
6. **KV: 24.12. und 31.12.** Diese Tage sind grundsätzlich dienstfrei; Arbeit an ihnen bringt einen
   Ersatzanspruch. Das ist nicht als Regel umgesetzt und muss geklärt werden (z. B. als Bedarfsregel mit
   `date` und eigener Abwesenheitsart).
7. **KV: Vorlaufzeit.** Der Dienstplan ist zwei Wochen, der Rahmendienstplan einen Monat im Voraus
   bekanntzugeben. Das betrifft den Ablauf der Planung, nicht den Solver.
8. **Gewichte der weichen Regeln.** Die Standardgewichte sind ein Startpunkt. Welche Regel wie viel zählt,
   ist eine Entscheidung der Dienstplanung und der Betriebsvereinbarung.
