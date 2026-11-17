# Planungsregeln

Dieses Dokument beschreibt alle Regeln, nach denen der Solver den Dienstplan rechnet: was jede Regel tut,
in welcher Gruppe sie wirkt, woher sie kommt und welcher Parameter sie steuert. Es richtet sich an die
Dienstplanung und an alle, die die Betriebsvereinbarung dazu abstimmen.

Quelle der Standardwerte: ein für Leitstellen typischer Kollektivvertrag, im Folgenden "KV". Die genauen
Fundstellen im jeweils anwendbaren KV hält die einführende Organisation selbst fest. Wo der Text eine Frage
offenlässt oder die Betriebsvereinbarung entscheidet, ist der Wert ein Parameter. Das Programm legt dort nichts
selbst aus.

## Gruppen

Der Score hat drei Teile (Gruppen), die streng nacheinander zählen. Technisch heißen sie `hard`, `medium` und
`soft`:

| Gruppe | Score | Bedeutung | Ziel |
|---|---|---|---|
| Regelverstoß | `hard` | Regeln aus KV und Qualifikation | muss 0 sein |
| Bedarf | `medium` | nicht gedeckter Bedarf in Personenminuten | 0, sonst Lückenliste |
| Qualität | `soft` | Wünsche, Fairness, Blöcke usw., mit sichtbaren Gewichten | so gut wie möglich |

Ist der Bedarf mit dem vorhandenen Personal nicht erfüllbar, bleibt die Gruppe Regelverstoß trotzdem bei 0. Die
fehlende Besetzung steht dann als Lücke je Bereich und Zeitabschnitt in der Analyse.

Alle Parameter stehen in `Rules` (Oberfläche: Einstellungen > Regeln). Gewichte gibt es nur für Qualitätsregeln;
die Regeln der Gruppe Regelverstoß und der Bedarf haben kein einstellbares Gewicht.

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
  Personen, die am weitesten unter ihrem Soll liegen, und hält dabei die Regeln der Gruppe Regelverstoß ein. Einen Dienst, der
  keinen offenen Bedarf deckt, setzt er nicht, auch nicht für das Soll. Danach verbessert
  die lokale Suche den Plan gegen alle Regeln.
- Vorgaben (`fixed`) und von Hand fixierte Zellen ändert der Solver nicht. Verletzen sie eine Regel,
  zeigt die Analyse das als Treffer.
- Ist eine Zelle im Plan von Hand fixiert (`pinned`), gilt ihr Wert auch dann, wenn für denselben Tag ein
  eine Vorgabe in `fixed` steht. Nicht fixierte Zellen, etwa aus einer früheren Planung, ersetzt die Vorgabe.

## Regelverstoß

| Id | Regel | Quelle | Parameter (Standard) |
|---|---|---|---|
| `dailyRest` | Mindestens 11 h Ruhe zwischen zwei Diensten | KV, Ruhezeit | `minDailyRestHours` (11) |
| `weeklyRest` | Mindestens 36 h ununterbrochene Ruhe je Kalenderwoche | KV, Ruhezeit | `minWeeklyRestHours` (36) |
| `maxDutyLength` | Ein Dienst dauert höchstens 12 h | KV, Durchrechnung | `maxDutyHours` (12) |
| `maxWeekHours` | Höchstens 60 h Dienst je Kalenderwoche | KV, Durchrechnung | `maxWeekHours` (60) |
| `maxMonthHours` | Im Monat höchstens das Ziel plus eine Toleranz (Dienste, angerechnete Abwesenheiten und Ersatzruhe) | Betrieb | `maxOverTargetPercent` (10 % des Ziels) |
| `freeSundays` | Mindestens N Sonntage je Kalendermonat ohne Dienst | Betriebsvereinbarung, anpassbar | `minFreeSundays` (2, 0 = aus) |
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
  zählen nicht als gearbeitet. Die Regel gehört dann zur Gruppe Regelverstoß, wenn die Historie den ganzen Zeitraum vor diesem Monat
  abdeckt; sonst wird der Durchschnitt nur angezeigt (Kennzahl `averageWeekHours`, `averageComplete` falsch).
- **Fehlende Schicht.** Ist eine fixierte Zelle mit einer Schicht belegt, die es nicht mehr gibt, wird sie
  nicht stillschweigend zu einem freien Tag, sondern als Regelverstoß gemeldet.
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

## Bedarf: Lücken und Überbesetzung

| Id | Regel | Gewicht (fest) |
|---|---|---|
| `demand` | Fehlende Besetzung je Bereich und Zeitabschnitt, in Personenminuten | 1 000 000 000 je Personenminute |
| `overstaffing` | Überbesetzung: Personen über der Höchstbesetzung, ohne Höchstbesetzung über der Mindestbesetzung, in Personenminuten | 1 je Personenminute |
| `uselessDuty` | Dienst, der keinen Bedarfsabschnitt seines Bereichs berührt, mit seinen ganzen Minuten | 1 je Minute |

- Warum Überbesetzung in der Gruppe Bedarf steht: Gehörte sie zur Qualität, würde der Solver Positionen doppelt besetzen,
  nur um Personen auf ihr Ziel zu bringen; die Abweichung vom Ziel (quadriert) wiegt dort schwerer. In der
  Gruppe Bedarf wiegt jede Minute über dem Bedarf schwerer als alle Qualitätsregeln zusammen. Ist der Bedarf
  gedeckt, bleiben Personen unter ihrem Soll, statt Positionen doppelt zu besetzen.
- Warum fehlender Bedarf 1 000 000 000 wiegt: Eine fehlende Minute wiegt mehr als alle Überbesetzung, die ein
  ganzer Monatsplan haben kann (auch mit 20 000 Personen). Der Solver lässt also nie eine Lücke offen, um
  Überbesetzung zu vermeiden, auch wenn das Schließen mehrere Dienste verschiebt. Weil die Überbesetzung unter
  diesem Gewicht bleibt, lassen sich beide Teile genau aus dem Score `medium` lesen: ungedeckte Minuten =
  Wert von `medium` / 1 000 000 000, überbesetzte Minuten = der Rest.
- Von Hand gesetzte Dienste und Vorgaben dürfen überbesetzen; sie erscheinen dann in der Gruppe Bedarf und in
  der Analyse. Die Statuszeile zeigt ungedeckten Bedarf und Überbesetzung getrennt in Personenstunden.
- Gespeicherte Gewichte für `overstaffing` und `uselessDuty` (früher Qualität) werden ignoriert.

- Die Bedarfsfenster eines Tages werden an allen Beginn- und Endzeiten der Schichten des Bereichs und an
  den Fenstergrenzen geteilt. In jedem Abschnitt ist die Besetzung konstant.
- Ein Fenster gehört zum Tag seines Beginns. Ein Fenster mit `to <= from` endet am Folgetag.
- Gültigkeit: Regeln mit `date` ersetzen für den Bereich an diesem Tag alle anderen. An Feiertagen ersetzen
  die `HOL`-Regeln eines Bereichs dessen Wochentagsregeln, wenn der Bereich `HOL`-Regeln hat.
- Überlappen sich zwei Fenster desselben Bereichs, werden die Mindestbesetzungen addiert.
- Ein Abschnitt ohne einen einzigen Dienst wird voll gezählt (Mindestbesetzung mal Minuten).
- Vormonatsdienste zählen zur Besetzung, z. B. die Nacht vom 31. auf den 1.

## Qualität

Jede Qualitätsregel hat eine Id, ein Gewicht (Standard in Klammern) und erscheint mit ihren Treffern in
der Erklärung je Person.

| Id | Regel | Wirkung | Standardgewicht |
|---|---|---|---|
| `targetHours` | Stunden treffen | Abweichung in Viertelstunden, quadriert | 20 |
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
| `shiftMix` | Gewohnte Verteilung | quadrierte Abweichung in Zehntel-Diensten je Kategorie und Bereich, siehe unten | 100 |
| `family` | Familienregel | je Dienst gegen die Regel | 800 |
| `onRequest` | Dienst nur auf Anfrage, von Hand gesetzt | je solchem Dienst; Gewicht fest, nicht einstellbar | 1000 |

### Stunden treffen

- Soll = Wochenstunden / 5 × Anzahl der Tage Montag bis Freitag, die kein Feiertag sind und in der
  Beschäftigung liegen (anteilig bei Eintritt oder Austritt im Monat).
- Abwesenheiten: Eine Abwesenheitsart mit `reducesTarget` ("senkt das Soll") senkt das Soll an solchen
  Werktagen um Wochenstunden / 5. Hat sie zusätzlich `chargesAccount` ("belastet das Stundenkonto", z. B.
  Zeitausgleich), werden diese Stunden vom Stundenkonto abgebucht. Urlaub: senkt das Soll, belastet das Konto
  nicht. Krankenstand: ebenso. Zeitausgleich: beides. Geplante Ersatzruhetage senken das Soll ebenfalls um
  Wochenstunden / 5.
- Das Ziel des Monats wird um einen Teil des Stundenkontos korrigiert: Ziel = Soll - `accountCarryShare`
  × Stundenkonto (Standard 0,25; die Oberfläche fragt „Ausgleich Stundenkonto je Monat (%)“ und nimmt 25 %). Plusstunden senken also das Ziel, Minusstunden erhöhen es. Das Stundenkonto
  am Monatsbeginn ist das Konto nach dem Vormonat aus der Historie, sonst der Wert der Person im Import.
- Stundenkonto nach dem Monat = vorher + geplant + Soll-Senkung - Soll - abgebuchte Stunden.
- Obergrenze (`maxMonthHours`, Gruppe Regelverstoß): Die angerechneten Stunden des Monats (Dienste, angerechnete
  Abwesenheiten und Ersatzruhe) dürfen das Ziel um höchstens `maxOverTargetPercent` Prozent des Ziels
  überschreiten (Standard 10 %, auf der Seite Regeln einstellbar, 0 erlaubt). Abwesenheiten zählen mit, senken
  also, was für Dienste bleibt. Strafe: Minuten darüber. Warum Regelverstoß: Ungedeckter Bedarf (Gruppe Bedarf) wiegt
  immer schwerer als das Ziel (Qualität); ohne Obergrenze füllt der Solver Lücken mit Teilzeitkräften bis
  zur gesetzlichen Wochengrenze auf. Die gesetzlichen Grenzen gelten weiter. Ein von Hand gesetzter oder fixer
  Dienst darf die Grenze überschreiten, erscheint dann aber als Regelverstoß; die Liste „Auf Anfrage
  verfügbar“ nennt niemanden, den der Dienst über die Grenze brächte. Regeln, die vor dieser Grenze gespeichert
  wurden, bekommen den Standard.
- Verkleinertes Ziel: Braucht der Bedarf weniger Arbeit, als die Ziele aller Personen zusammen ergeben,
  wird das Ziel jeder Person um denselben Anteil verkleinert. Angerechnete Abwesenheitstage bleiben, der Rest
  des Ziels (die Arbeit) wird mit Bedarf / Summe der Arbeit malgenommen. Der Bedarf ist die Mindestbesetzung
  aller Abschnitte des Monats in Personenminuten, vor der Planung fest. Teilzeitkräfte bekommen so anteilig
  weniger. Warum: Überbesetzung gehört zur Gruppe Bedarf, die vollen Ziele sind dann nicht erreichbar,
  und die quadrierte Abweichung würde alle anderen Qualitätsregeln überdecken; der Solver würde nur noch
  ausgleichen, wer wie weit unter dem Ziel bleibt, statt Blöcke zu bilden. Braucht der Bedarf alle Arbeit oder
  mehr, ändert sich nichts. Wer seinen Anteil nicht arbeiten kann (Sperren, wenige erlaubte Schichten), wird
  nicht eigens berücksichtigt: Die quadrierte Abweichung verteilt den Rest gleichmäßig auf die anderen, und die
  Obergrenze bleibt beim echten Ziel. Personenansicht und Analyse zeigen beide Ziele.
- Die Strafe ist die Abweichung in Viertelstunden (vom verkleinerten Ziel), quadriert, damit sie sich auf viele Personen verteilt statt
  bei wenigen hängen zu bleiben. Beispiel: 4 h Abweichung sind 16 Viertelstunden, Strafe 256 mal Gewicht.

### Freie Sonntage

- Jede Person hat im Kalendermonat mindestens `minFreeSundays` Sonntage ohne Dienst (Standard 2; Quelle: eine
  Betriebsvereinbarung für den Schichtdienst; ob sie für jede Leitstelle gilt, entscheidet der Betrieb, deshalb
  einstellbar, 0 schaltet die Regel aus). Gruppe Regelverstoß: Strafe je Sonntag zu viel.
- Ein Sonntag ist frei, wenn kein Dienst ihn von 0 bis 24 Uhr berührt. Ein Nachtdienst von Samstag auf Sonntag
  nimmt den Sonntag also mit, auch wenn er am Morgen endet; so ist der Sonntag wirklich frei. Ein Nachtdienst
  ab Sonntagabend nimmt ihn ebenfalls.
- Abwesenheiten (Urlaub, Krankenstand usw.) und Tage außerhalb der Beschäftigung haben keinen Dienst und
  zählen damit als frei. Wer im Monat ein- oder austritt oder an den meisten Sonntagen abwesend ist, erfüllt
  die Regel so ohne Sonderfall.
- Von Hand gesetzte Dienste und Vorgaben dürfen die Regel verletzen; sie erscheint dann als Regelverstoß. Die
  Liste „Auf Anfrage verfügbar“ nennt niemanden, dem der Dienst einen freien Sonntag zu wenig ließe.

### Gewohnte Verteilung

- Eine Person kann eine gewohnte Verteilung haben (`shiftMix` in den Stammdaten, z. B. aus der Historie
  exportiert): Anteile ihrer Dienste je Kategorie (Früh, Tag, Spät, Nacht) und je Bereich. Die Planung
  korrigiert sie in den Stammdaten oder entfernt sie.
- Je Kategorie und Bereich wird verglichen: geplante Dienste des Monats gegen Anteil mal alle geplanten
  Dienste der Person. Die Strafe ist die quadrierte Abweichung in Zehntel-Diensten, summiert. Beispiel: Eine
  Person mit 90 % Tag und 10 % Nacht hat 10 Dienste; 9 Tag und 1 Nacht kosten nichts, 5 und 5 kosten
  2 × 40² = 3200 mal Gewicht. Quadriert, damit kleine Abweichungen wenig und eine ganz andere Verteilung viel
  kosten. Gewicht 100, damit die Verteilung den Plan sichtbar prägt: Im Demomonat mit klaren Verteilungen für
  92 Personen sank die Abweichung der Anteile von 0,55 auf 0,30; ein höheres Gewicht (200: 0,26) kostet
  deutlich mehr Blöcke und Abweichung vom Ziel. Bedarf und Überbesetzung (Gruppe Bedarf) gehen immer vor.
- Wechselwirkung: Wer eine Verteilung je Kategorie hat (Früh, Tag, Spät, Nacht), zählt nicht für die
  Abwechslung Dienste, solange die Verteilung besteht; sonst würde diese Regel die Verteilung wieder einebnen.
  Für die Fairness Nachtdienste (den Nachtausgleich) entscheidet die Nachtverteilung (siehe unten). Wer eine
  Verteilung je Bereich hat, zählt nicht für die Abwechslung der Bereiche. Wochenenden, Feiertage und
  unbeliebte Dienste bleiben fair verteilt. Personen ohne Verteilung werden geplant wie bisher.
- Die Personenansicht zeigt die geplante Verteilung neben der gewohnten.

### Nachtverteilung

Eine Einstellung der Regeln, `nightDistribution`:

- `HABIT` (Standard, „Wie bisher: jede Person behält ihren gewohnten Nachtanteil“): Wer eine Verteilung je
  Kategorie hat (auch einen Nachtanteil oder keinen), ist nicht im Nachtausgleich; die Nächte folgen dem
  Nachtanteil der Verteilung. Regeln, die vor der Einstellung gespeichert wurden (ohne das Feld), gelten als
  `HABIT` und planen unverändert.
- `EVEN` („Gleichmäßig: Nachtdienste fair auf alle verteilen“): Nur der Nachtanteil der Verteilung zählt nicht.
  Die übrigen Anteile (Früh, Tag, Spät) werden auf zusammen 100 % hochgerechnet und gelten für die Dienste
  der Person, die nicht der Kategorie Nacht angehören: verglichen wird je Kategorie die Zahl der geplanten
  Dienste gegen Anteil mal Zahl dieser Dienste. Beispiel: 45 % Früh, 45 % Tag, 10 % Nacht wird 50 % Früh und
  50 % Tag; bei 8 Diensten außer Nächten kosten 4 Früh und 4 Tag nichts, gleich wie viele Nächte dazukommen.
  Die Verteilung je Bereich bleibt unverändert und zählt alle Dienste. Die Person ist im Nachtausgleich. Für die
  Abwechslung Dienste zählt sie weiter nicht, solange ohne den Nachtanteil Anteile übrig bleiben; eine
  Verteilung nur aus Nächten fällt ganz weg.
- In beiden Fällen ist nicht im Nachtausgleich (wird also auch für fehlende Nächte nicht bestraft), wer nie
  Nacht macht:
  - keine Nachtschicht in den eigenen Bereichen;
  - eine Sperre „nie“ für jeden Wochentag (Wochentag 0), die den ganzen Planungsmonat gilt, deckt jede
    Nachtschicht der eigenen Bereiche ab, als Kategorie Nacht oder Schicht für Schicht. Eine Sperre nur für
    einige Wochentage, nur für einen Teil des Monats oder nur für eine von mehreren Nachtschichten genügt
    nicht;
  - eine Familienregel „keine Nacht“ gilt an allen Wochentagen 1 bis 7 (eine Regel ohne Wochentage wirkt wie die
    Regel `family` selbst an keinem Tag).
  „Nie Nacht“ geht vor der gewohnten Verteilung.
- Demomonat mit klaren Verteilungen für 92 von 150 Personen, je 300 s: `HABIT` vergleicht 41 Personen (Nächte je
  Person 0 bis 10, Mittel 1,5), `EVEN` 120 (0 bis 13, Mittel 2,3); die Strafe der Verteilung sinkt von rund
  10,0 auf 4,5 Millionen, Einzeldienste 152 und 145, Bedarf in beiden Fällen gedeckt.
- Die Personenansicht zeigt bei den Nächten „folgt gewohnter Verteilung (10 % Nacht)“, „im Nachtausgleich:
  Belastung x, Mittel y“ (die Last der Fairness Nachtdienste, siehe unten, als Nächte je 30 verfügbare Tage)
  oder „nicht im Nachtausgleich (nie Nacht)“. Die Fairness-Übersicht markiert, wer nicht verglichen wird, und
  warum. Für Ansichtsrollen bleibt beides leer, weil es die Verteilung, Sperren oder Familienregeln verraten
  würde.

### Fairness

- Gezählt werden je Person Nachtdienste, gearbeitete Wochenenden, Dienste an Feiertagen und unbeliebte
  Dienste (Schicht mit `unpopular`), jeweils zusammen mit den Summen der letzten `historyMonths` Monate
  (Standard 3) aus der Historie.
- Verglichen wird die Last: Dienste je 30 verfügbare Tage einer 40-Stunden-Kraft, in Tausendsteln. Verfügbar
  sind die Tage in der Beschäftigung ohne Abwesenheit, dazu je Historienmonat rund 30 Tage. Wer im Monat
  eintritt oder lange abwesend war, bekommt dadurch nicht alle Nachtdienste; Teilzeit trägt anteilig weniger.
- Verglichen werden alle, die solche Dienste machen können (z. B. Nachtdienste nur Personen im Nachtausgleich,
  siehe Nachtverteilung). Die Strafe ist die Wurzel aus der Summe der quadrierten Abweichungen vom Mittelwert:
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
- Gewichte (ab 0.6.0): Einzelner freier Tag 10000 je Fall, Blocklänge 6000 je Tag. Mit dem verkleinerten
  Ziel prägen sie den Plan sichtbar: Im Demomonat sanken die einzelnen freien Tage von 261 auf 76, die
  Tage außerhalb der Blocklänge (2 bis 4) von 602 auf 328; der Preis: unerfüllte Wünsche stiegen von 24 auf 38. Mit
  deutlich weniger Bedarf als Arbeit sanken die einzelnen freien Tage von 123 auf 16. Wer die Gewichte auf der
  Seite Regeln einmal gespeichert hat, behält die gespeicherten Werte; dort lassen sie sich auf die neuen
  Standards setzen. Die Analyse zeigt beide Zahlen (`singleDaysOff`, `blockDays`).
- Vorwärtsrotation: die Reihenfolge Früh, Tag, Spät, Nacht. Ein Wechsel von einem Tag auf den nächsten in
  eine frühere Kategorie kostet. Nacht auf Früh ohne genug Ruhe ist ohnehin als Regelverstoß verboten.
- Kategorie einer Schicht: aus dem Schichtkatalog; fehlt sie, gilt Nacht (siehe oben), Früh (Beginn vor
  7:00), Tag (Ende bis 19:00), sonst Spät.
- Ganze Wochenenden: Samstag und Sonntag desselben Wochenendes, von denen nur einer einen Dienst hat (ein
  Wochenende über den Monatsbeginn zählt mit dem Samstag aus dem Vormonat). Ist einer der beiden Tage ein Abwesenheitstag, zählt das Wochenende nicht. Die faire Verteilung der
  gearbeiteten Wochenenden steckt in `fairWeekends`.

### Ersatzruhe

- Personen mit offenem Guthaben bekommen Ersatzruhetage geplant (Bonus je Tag), höchstens bis zum Guthaben
  (Gruppe Regelverstoß).
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

- Familienregel je Person und Wochentag: spätestes Dienstende, frühester Dienstbeginn, keine Nacht. Die
  Uhrzeiten beziehen sich auf den Tag des Dienstbeginns; ein Nachtdienst endet also immer nach einem
  spätesten Ende am selben Tag.
- Sperren der Art "nur auf Anfrage" plant der Solver nie ein, auch nicht, wenn sonst eine Bedarfslücke bleibt:
  Die Planung fragt die Person und trägt den Dienst dann von Hand ein (fixiert) oder als Vorgabe in den
  Eingaben. Ein solcher Dienst ist kein Regelverstoß; er erscheint als Treffer der Regel `onRequest`, damit
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
   gerechnet und gehört zur Gruppe Regelverstoß, wo die Historie den Zeitraum abdeckt. Ob die Durchrechnung rollierend oder je
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
8. **Gewichte der Qualitätsregeln.** Die Standardgewichte sind ein Startpunkt. Welche Regel wie viel zählt,
   ist eine Entscheidung der Dienstplanung und der Betriebsvereinbarung.
