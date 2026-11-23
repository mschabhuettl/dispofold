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
- Beschäftigung: Tage vor `entryDate` oder nach `exitDate` einer Person werden nicht verplant, ebenso Tage ab
  `excludedFrom` (nicht mehr aus dem Quellsystem übernommen).
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

Diese Regeln haben weder ein Gewicht noch eine Wichtigkeit: Ein Plan mit einem Verstoß ist immer schlechter als
einer ohne, gleich wie viel Bedarf dafür offen bleibt oder wie viel Qualität es kostet. Ein Verstoß entsteht
deshalb praktisch nur durch von Hand gesetzte Dienste und Vorgaben; die Analyse zeigt ihn. Ändern lassen sich nur
die Parameter (Seite Regeln, „Gesetz und Betriebsvereinbarung“).

| Id | Regel | Quelle | Parameter (Standard) |
|---|---|---|---|
| `dailyRest` | Mindestens 11 h Ruhe zwischen zwei Diensten | KV, Ruhezeit | `minDailyRestHours` (11) |
| `weeklyRest` | Mindestens 36 h ununterbrochene Ruhe je Kalenderwoche | KV, Ruhezeit | `minWeeklyRestHours` (36) |
| `maxDutyLength` | Ein Dienst dauert höchstens 12 h | KV, Durchrechnung | `maxDutyHours` (12) |
| `maxWeekHours` | Höchstens 60 h Dienst je Kalenderwoche | KV, Durchrechnung | `maxWeekHours` (60) |
| `maxMonthHours` | Im Monat höchstens das Ziel plus eine Toleranz (Dienste, angerechnete Abwesenheiten und Ersatzruhe) | Betrieb | `maxOverTargetPercent` (10 % des Ziels) |
| `freeSundays` | Mindestens N Sonntage je Kalendermonat ohne Dienst | Betriebsvereinbarung, anpassbar | `minFreeSundays` (2, 0 = aus) |
| `averageWeekHours` | Im Durchschnitt des Durchrechnungszeitraums höchstens die eigenen Wochenstunden plus Spielraum, nie mehr als 46,16 h je Woche | KV, Durchrechnung; Teilzeit siehe unten | `maxAverageWeekHours` (46,16), `averageTolerancePercent` (10 %), `averagingPeriod` (Kalenderhalbjahr), `averagingWeeks` (52, nur gleitend) |
| `qualification` | Nur Personen, die für den Bereich eingeteilt werden dürfen | Stammdaten | `areaIds` der Person |
| `absence` | Kein Dienst und keine Ersatzruhe an Abwesenheitstagen | Abwesenheiten | |
| `freeInsideAbsence` | Kein Dienst an einem Feiertag, Samstag oder Sonntag mitten in einer Abwesenheit, und an einem Feiertag in einer sonst ganz abwesenden Woche | Planungsregel des Betriebs | `freeInsideAbsence` (ein) |
| `employment` | Kein Dienst vor dem Eintritt oder nach dem Austritt | Stammdaten | `entryDate`, `exitDate`, `excludedFrom` |
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
- **Durchrechnungszeitraum.** Der KV erlaubt eine Durchrechnung über bis zu 52 Wochen und überlässt die
  Einzelheiten einer Betriebsvereinbarung. Standard (`averagingPeriod` = `HALF_YEAR`) ist das Kalenderhalbjahr,
  wie bei der üblichen Abrechnung je Halbjahr: Jänner bis Juni und Juli bis Dezember. Gezählt werden die Monate des
  Halbjahrs vor dem geplanten Monat und der geplante Monat, geteilt durch die Wochen vom Beginn des Halbjahrs bis
  zum Monatsende (Tage / 7). Vollständig ist die Historie, wenn alle Monate des Halbjahrs vor dem geplanten Monat
  darin stehen; im Jänner und im Juli zählt nur der geplante Monat, und die Regel gilt sofort. Im Juli zählen
  die Monate davor nicht mehr. Zum Stundenkonto am Halbjahresende siehe Stunden treffen. Gleitend
  (`ROLLING`): die letzten `averagingWeeks` Wochen (Standard 52) bis zum Monatsende, jeder Historienmonat mit
  365,25 / 12 / 7 Wochen.
- **Eigene Grenze des Durchschnitts.** Jede Person wird an ihrem eigenen Vertrag gemessen: Grenze = Wochenstunden
  plus `averageTolerancePercent` Prozent davon (Standard 10 %, 0 erlaubt), höchstens `maxAverageWeekHours`
  (46,16 h). Bei 10 % sind das für 40 h 44 h, für 20 h 22 h; für 42 h wären es 46,2 h, es gilt also 46,16 h. Eine
  Person ohne Wochenstunden hat die Grenze 46,16 h. Kennzahl `averageLimitHours`; die Erklärung eines Verstoßes
  nennt diese Grenze.
- **Wochenstunden geändert.** Jeder Monat des Zeitraums zählt mit seinen eigenen Wochenstunden, gewichtet mit
  seinen Wochen: Ein Historienmonat zählt 365,25 / 12 / 7 Wochen, dieser Monat seine Tage / 7. Die Wochenstunden
  eines Historienmonats stehen im Feld `weeklyHours` des Historieneintrags; der Monatsabschluss schreibt sie.
  Fehlt das Feld in einem importierten Eintrag, gilt der Wert aus dem abgeschlossenen Plan desselben Monats; gibt
  es keinen, zählen die heutigen Wochenstunden der Person.
  Beispiel (Kalenderhalbjahr): Juli mit 40 h, August mit 20 h, September und der geplante Oktober mit 40 h,
  Spielraum 10 %: die Grenze liegt bei (31 × 44 + 31 × 22 + 30 × 44 + 31 × 44) / 123 = 39,0 h je Woche.
- **Frei mitten in einer Abwesenheit.** Ein Feiertag, Samstag oder Sonntag, an dem die Person keine Abwesenheit
  hat, ist frei, wenn der nächste Werktag davor und der nächste Werktag danach (Montag bis Freitag, kein Feiertag)
  beide eine Abwesenheit haben, die das Soll senkt (`reducesTarget`: Urlaub, Krankenstand, Zeitausgleich usw.;
  die Arten dürfen verschieden sein). Ein Feiertag von Montag bis Freitag ist außerdem frei, wenn alle anderen
  Werktage seiner Kalenderwoche (im Beschäftigungszeitraum, mindestens einer) so eine Abwesenheit haben; das
  Wochenende dieser Woche wird dadurch nicht frei. Der Solver plant dort keinen Dienst. Eine Vorgabe oder ein von Hand fixierter
  Dienst an so einem Tag bleibt stehen und wird als Regelverstoß gemeldet. Der Tag ist keine Abwesenheit: Er
  ändert weder Soll noch Stundenkonto noch die Abwesenheitslisten. Am Monatsrand zählen die Abwesenheiten des
  Vor- bzw. Folgemonats, die eingetragen sind (Eingaben des Nachbarmonats); ist dort nichts eingetragen, ist der
  Tag nicht frei. Eine neue Abwesenheit im Nachbarmonat kann deshalb einen gerechneten Plan veralten lassen.
  Ausschalten mit `freeInsideAbsence` (Seite Regeln, „Gesetz und Betriebsvereinbarung“).
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

Beispiele, was jede Regel verhindert (Personen und Schichten erfunden, Standardwerte):

- **Beispiel `dailyRest`:** Anna hat Spät 14-22 und am nächsten Morgen Früh 06-14. Dazwischen lägen nur 8 h Ruhe,
  also 180 Minuten zu wenig; der Solver plant ihr das nicht.
- **Beispiel `weeklyRest`:** Ben hat Montag bis Freitag Früh 06-14 und am Samstag Spät 14-22. Der Sonntag ist frei,
  die längste Ruhe der Woche (Samstag 22 Uhr bis Sonntag 24 Uhr) sind aber nur 26 h. Ohne den Samstag wären es
  58 h (Freitag 14 Uhr bis Sonntag 24 Uhr).
- **Beispiel `maxDutyLength`:** Steht im Schichtkatalog eine Schicht Lang 06-20 (14 h), plant der Solver sie nie
  ein. Setzt die Planung sie von Hand, ist das ein Verstoß von 120 Minuten.
- **Beispiel `maxWeekHours`:** Carla hat in einer Woche fünfmal Tag 07-19, das sind 60 h. Ein sechster Dienst in
  derselben Woche ist ausgeschlossen, auch wenn dafür Bedarf offen bleibt.
- **Beispiel `maxMonthHours`:** Dora arbeitet Teilzeit, ihr Ziel im Monat sind 80 h. Mit 10 % Toleranz bekommt sie
  höchstens 88 h, auch wenn der Bereich jemanden für eine Lücke sucht.
- **Beispiel `freeSundays`:** Emil hätte im Monat mit vier Sonntagen an drei Sonntagen Dienst. Mit dem Standard 2
  darf er an höchstens zwei arbeiten; auch eine Nacht 19-07 von Samstag auf Sonntag nimmt den Sonntag.
- **Beispiel `averageWeekHours`:** Frida hat 20 Wochenstunden, ihre Grenze ist mit 10 % Spielraum 22 h. Sie hat im
  Durchrechnungszeitraum schon fast 22 h je Woche gearbeitet. In diesem Monat bekommt sie nur so viele Stunden,
  dass der Durchschnitt nicht darüber steigt, auch wenn ihre Monatsgrenze mehr zuließe. Bei Vollzeit gilt
  dasselbe mit 44 h (40 h plus 10 %), höchstens 46,16 h.
- **Beispiel `qualification`:** Gustav ist nur für Bereich A eingeteilt. In Bereich B bekommt er keinen Dienst,
  auch wenn dort jemand fehlt.
- **Beispiel `absence`:** Hanna hat am 10. Urlaub. An diesem Tag gibt es für sie weder Dienst noch Ersatzruhe.
- **Beispiel `freeInsideAbsence`:** Der Mittwoch ist ein Feiertag, Hanna hat Montag, Dienstag, Donnerstag und
  Freitag Urlaub: Der Mittwoch bleibt frei. Hat sie zwei Wochen hintereinander Urlaub (Montag bis Freitag), bleibt
  das Wochenende dazwischen frei. Hat sie nur eine Woche Urlaub, Montag bis Freitag, bleibt das Wochenende danach
  planbar. Urlaub am Freitag und Krankenstand am Montag machen das Wochenende dazwischen ebenfalls frei. Ist der
  Pfingstmontag ein Feiertag und hat Hanna Dienstag bis Freitag Urlaub, bleibt der Montag frei, obwohl davor kein
  Urlaubstag liegt; fehlt der Urlaub an einem dieser Tage, ist der Montag planbar.
- **Beispiel `employment`:** Ida tritt am 15. ein. Vorher bekommt sie keinen Dienst, ebenso nach einem Austritt
  und ab dem Tag, ab dem sie nicht mehr aus dem Quellsystem übernommen wird.
- **Beispiel `neverRestriction`:** Jonas hat die Sperre „nie Nacht am Freitag“. Freitags plant der Solver ihm
  keine Nacht 19-07.
- **Beispiel `compRestBalance`:** Klara hat zwei Ersatzruhetage offen. Der Solver plant ihr höchstens zwei, nicht
  drei.
- **Beispiel `missingShift`:** Am 3. ist ein Dienst Früh 06-14 fixiert, nach einem neuen Import fehlt diese Schicht
  im Katalog. Statt den Tag still frei zu machen, meldet die Analyse einen Verstoß.

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
- Die Gewichte dieser Gruppe sind fest, es gibt keine Wichtigkeit. Keine Wichtigkeit und kein Gewicht einer
  Qualitätsregel lässt eine Lücke offen oder eine Überbesetzung zu, um Wünsche, Blöcke oder Stunden zu verbessern.
- **Beispiel `demand`:** Bereich A braucht in der Nacht 19-07 zwei Personen, geplant ist eine: 720 Personenminuten
  Lücke. Ist jemand frei und darf (Ruhezeit, Qualifikation, Sperren), plant der Solver ihn ein, auch wenn das
  einen Wunsch, einen Block oder das Ziel dieser Person kostet.
- **Beispiel `overstaffing`:** Bereich A braucht tagsüber drei Personen, ohne Höchstbesetzung. Lukas liegt unter
  seinem Ziel, bekommt dort aber trotzdem keinen vierten Tagdienst: Jede Minute über dem Bedarf wiegt mehr als
  alle Qualitätsregeln zusammen.
- **Beispiel `uselessDuty`:** Ein Dienst Spät 14-22 in Bereich B, wo es zwischen 14 und 22 Uhr gar keinen Bedarf
  gibt, zählt mit 480 Minuten. Meist fehlt dann in den Stammdaten der Bedarf für diese Zeit.

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
| `fairUnpopular` | Fairness fair verteilte Dienste | siehe Fairness | 2 |
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

**Wichtigkeit.** Die Seite Regeln fasst neun Qualitätsregeln zu Planungszielen zusammen. Die Wichtigkeit eines
Ziels setzt die Gewichte seiner Regeln auf ein Vielfaches ihres Standards, gerundet auf ganze Zahlen:

| Planungsziel | Regeln | weniger / normal / mehr | Gewichte |
|---|---|---|---|
| Dienstblöcke | `blockLength`, `isolatedDayOff` | 0,5 / 1 / 2 | 3000 / 6000 / 12000 und 5000 / 10000 / 20000 |
| Wünsche | `wishes` | 0,5 / 1 / 2 | 150 / 300 / 600 |
| Faire Verteilung | `fairNights`, `fairWeekends`, `fairHolidays`, `fairUnpopular` | 0,25 / 1 / 4 | 1 / 3 / 12, fair verteilte 1 / 2 / 8 |
| Stunden treffen | `targetHours` | 0,5 / 1 / 2 | 10 / 20 / 40 |
| Familienregeln | `family` | 0,5 / 1 / 2 | 400 / 800 / 1600 |

- `forwardRotation`, `splitWeekend`, `compRestUse`, `shiftVariety`, `areaVariety` und `shiftMix` gehören zu keinem
  Ziel: Sie haben keine Wichtigkeit, nur ihr Gewicht unter Erweitert. `onRequest` hat ein festes Gewicht.
- Was die Wichtigkeit sicher bewirkt: Sie verschiebt nur das Verhältnis der Qualitätsregeln untereinander. Regeln
  der Gruppe Regelverstoß und der Bedarf gehen wegen der strengen Reihenfolge der Gruppen immer vor; keine Wahl
  erlaubt einen Verstoß oder lässt eine Lücke offen.
- Es zählen nur die Verhältnisse: das Doppelte bei einem Ziel wirkt wie die Hälfte bei allen anderen
  Qualitätsregeln zugleich. Werden alle Gewichte mit derselben Zahl malgenommen, bleibt die Reihenfolge aller
  Pläne gleich.
- Gemessen ist die Wirkung bisher nur für die faire Verteilung (siehe Fairness): dort ändert sie nichts über das
  Rauschen zwischen zwei Läufen. Für die anderen Ziele ist das 0,5- und 2-Fache nicht gemessen; was unten dazu
  steht, folgt aus den Formeln und ist eine Erwartung, keine Messung. Nachmessen lässt es sich mit
  `WeightFactorBenchmarkTest` im Backend (z. B. `-Dsolver.weightFactor=2 -Dsolver.weightFactor.ids=wishes`).
- Die Wahl einer Wichtigkeit überschreibt Gewichte, die unter Erweitert von Hand gesetzt wurden.

### Stunden treffen

- Soll = Wochenstunden / 5 × Anzahl der Tage Montag bis Freitag, die kein Feiertag sind und in der
  Beschäftigung liegen (anteilig bei Eintritt oder Austritt im Monat).
- Abwesenheiten: Eine Abwesenheitsart mit `reducesTarget` ("senkt das Soll") senkt das Soll an solchen
  Werktagen um Wochenstunden / 5. Hat sie zusätzlich `chargesAccount` ("belastet das Stundenkonto", z. B.
  Zeitausgleich), werden diese Stunden vom Stundenkonto abgebucht. Urlaub: senkt das Soll, belastet das Konto
  nicht. Krankenstand: ebenso. Zeitausgleich: beides. Geplante Ersatzruhetage senken das Soll ebenfalls um
  Wochenstunden / 5.
- Gutschrift einer Abwesenheitsart (`credit`): `WEEKLY_HOURS` (Standard) wie oben. `PLANNED_DUTY`: angerechnet
  werden die Stunden des Dienstes, den die Abwesenheit ersetzt hat, an jedem Wochentag (auch Samstag, Sonntag,
  Feiertag), ohne Dienst 0, wie Zeitaufzeichnungen Krankenstand oder Urlaub an einem Diensttag gutschreiben. Den
  ersetzten Dienst schreibt Dispofold an die Abwesenheit (`replacedHours`), wenn sie in einen Monat mit Plan
  eingetragen wird. Ein Monat, der noch geplant wird, hat keinen Dienst, den eine Abwesenheit ersetzt: Ohne
  `replacedHours` gilt Wochenstunden / 5 an Werktagen, und die Personenseite nennt die Tage.
- Ausgleich des Stundenkontos (`accountBalancing`). Standard `PERIOD_END`, bis Ende des Kalenderhalbjahrs: Am Ende
  des Durchrechnungszeitraums soll das Konto bei 0 stehen, denn über den Zeitraum sind die vereinbarten Stunden
  geschuldet, bei Teilzeit die Stunden laut Vertrag (die Normalarbeitszeit der Teilzeitkraft). Ziel = Soll -
  Stundenkonto / verbleibende Monate des Halbjahrs einschließlich des geplanten (Jänner und Juli 6, April 3, Juni
  1: der ganze Rest). Im Jänner und im Juli beginnt das Konto bei 0: Plusstunden vom Halbjahresende werden
  außerhalb des Plans abgerechnet (zum Beispiel als Überstunden) und nie mitgenommen; ein Minus verfällt
  (`carryMinusRest` aus, Standard) oder kommt mit (`carryMinusRest` ein). Das Konto am Ende des vorigen Halbjahrs
  zeigt die Personenseite als „Abgerechnet Ende Vorhalbjahr“, auch unter den Kennzahlen der Fairness. Das Ziel
  bleibt zwischen 0 und Soll plus `maxOverTargetPercent` Prozent; was darüber hinaus ausgeglichen werden müsste,
  ist der Rest: ein Hinweis auf der Seite Prüfen und auf der Personenseite (Person, Rest, Obergrenze), kein
  Regelverstoß. Alternative `MONTHLY_SHARE`: Ziel = Soll - `accountCarryShare` × Stundenkonto (Standard 0,25; die
  Oberfläche fragt „Ausgleich Stundenkonto je Monat (%)“ und nimmt 25 %), das Konto läuft über das Halbjahr
  weiter. Mit gleitender Durchrechnung (`averagingPeriod` = `ROLLING`) gilt immer der feste Anteil. Plusstunden
  senken also das Ziel, Minusstunden erhöhen es. Das Stundenkonto am Monatsbeginn ist das Konto nach dem
  Vormonat aus der Historie, sonst der Wert der Person im Import (bis Ende des Halbjahrs im Jänner und Juli 0,
  siehe oben). Schon geschlossene Monate behalten ihren gespeicherten Kontostand.
- Stundenkonto nach dem Monat = vorher + geplant + Soll-Senkung - Soll - abgebuchte Stunden.
- Obergrenze (`maxMonthHours`, Gruppe Regelverstoß): Die angerechneten Stunden des Monats (Dienste, angerechnete
  Abwesenheiten und Ersatzruhe) dürfen das Ziel um höchstens `maxOverTargetPercent` Prozent des Ziels
  überschreiten (Standard 10 %, auf der Seite Regeln einstellbar, 0 erlaubt). Beim Ausgleich bis Ende des
  Halbjahrs liegt sie immer bei Soll plus diesem Anteil, auch wenn Plusstunden das Ziel senken: Angerechnete
  Abwesenheiten müssen darunter Platz haben; zum Ziel zieht die Regel „Stunden treffen“. Abwesenheiten zählen mit, senken
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
- **Beispiel:** Mia und Noah bleiben zusammen 8 h unter ihren Zielen. Mia allein 8 h darunter kostet 32² = 1024
  mal Gewicht, Mia und Noah je 4 h darunter nur 2 × 256 = 512. Die Regel verhindert also, dass eine Person weit
  daneben liegt, während andere ihr Ziel genau treffen.
- **Wichtigkeit („Stunden treffen“, Gewicht 10, 20 oder 40):** Nicht gemessen. Erwartbar: „mehr“ bringt die
  Stunden näher an das Ziel und nimmt dafür eher schlechtere Blöcke, unerfüllte Wünsche und Abweichungen von der
  gewohnten Verteilung in Kauf; „weniger“ umgekehrt. Die Obergrenze über dem Ziel und der Ausgleich des
  Stundenkontos, die beim Ziel auf der Seite Regeln stehen, sind Parameter und ändern sich mit der Wichtigkeit
  nicht.

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
  fair verteilte Dienste bleiben gleichmäßig verteilt. Personen ohne Verteilung werden geplant wie bisher.
- Die Personenansicht zeigt die geplante Verteilung neben der gewohnten.
- **Beispiel:** Olga macht seit Jahren fast nur Tagdienste (90 % Tag, 10 % Nacht). Die Regel verhindert, dass sie
  in einem Monat plötzlich zur Hälfte Nächte bekommt, nur weil das bei anderen Regeln etwas spart.
- **Wichtigkeit:** Die Regel gehört zu keinem Planungsziel, ihr Gewicht steht nur unter Erweitert. Gemessen ist
  nur, was oben steht (Gewicht 100 und 200 gegen den Ausgangswert 0,55); ein Gewicht unter 100 ist nicht
  gemessen. Erwartbar weicht die Verteilung dann stärker ab, zugunsten von Blöcken und Stunden.

### Nachtverteilung

Eine Einstellung der Regeln, `nightDistribution`:

- `HABIT` (Standard, „Wie bisher: jede Person behält ihren gewohnten Nachtanteil“): Wer eine Verteilung je
  Kategorie hat (auch einen Nachtanteil oder keinen), ist nicht im Nachtausgleich; die Nächte folgen dem
  Nachtanteil der Verteilung. Ausnahme: wer nie Nacht macht (siehe unten). Regeln, die vor der Einstellung
  gespeichert wurden (ohne das Feld), gelten als `HABIT` und planen unverändert.
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
  „Nie Nacht“ geht vor der gewohnten Verteilung: Für eine solche Person zählt in beiden Fällen der Nachtanteil
  ihrer Verteilung nicht, genau wie bei `EVEN`. Die übrigen Anteile werden auf zusammen 100 % hochgerechnet und
  gelten für die Dienste außer Nächten, auch für die Abwechslung Dienste. Beispiel: 90 % Tag und 10 % Nacht wird
  100 % Tag. Wer im Nachtausgleich ist oder bei `HABIT` wegen seiner Verteilung nicht, bleibt unverändert.
- Demomonat mit klaren Verteilungen für 92 von 150 Personen, je 300 s: `HABIT` vergleicht 41 Personen (Nächte je
  Person 0 bis 10, Mittel 1,5), `EVEN` 120 (0 bis 13, Mittel 2,3); die Strafe der Verteilung sinkt von rund
  10,0 auf 4,5 Millionen, Einzeldienste 152 und 145, Bedarf in beiden Fällen gedeckt.
- Die Personenansicht zeigt bei den Nächten „folgt gewohnter Verteilung (10 % Nacht)“, „im Nachtausgleich:
  Belastung x, Mittel y“ (die Last der Fairness Nachtdienste, siehe unten, als Nächte je 30 verfügbare Tage)
  oder „nicht im Nachtausgleich (nie Nacht)“. Hat eine Person, die nie Nacht macht, einen Nachtanteil, sagen
  die Kachel der Nächte und der Hinweis bei der Verteilung, dass dieser Anteil nicht zählt. Die Seite Fairness
  markiert, wer nicht verglichen wird, und warum, ebenso den nicht gezählten Nachtanteil. Für Ansichtsrollen bleibt beides leer, weil es die Verteilung, Sperren oder Familienregeln verraten
  würde.

### Fairness

- Gezählt werden je Person Nachtdienste, gearbeitete Wochenenden, Dienste an Feiertagen und fair verteilte
  Dienste (Schicht mit `unpopular`), jeweils zusammen mit den Summen der letzten `historyMonths` Monate
  (Standard 3) aus der Historie.
- Verglichen wird die Last: Dienste je 30 verfügbare Tage einer 40-Stunden-Kraft, in Tausendsteln. Verfügbar
  sind die Tage in der Beschäftigung ohne Abwesenheit, dazu je Historienmonat rund 30 Tage. Wer im Monat
  eintritt oder lange abwesend war, bekommt dadurch nicht alle Nachtdienste; Teilzeit trägt anteilig weniger.
- Verglichen werden alle, die solche Dienste machen können (z. B. Nachtdienste nur Personen im Nachtausgleich,
  siehe Nachtverteilung). Die Strafe ist die Wurzel aus der Summe der quadrierten Abweichungen vom Mittelwert:
  0, wenn alle gleich belastet sind. Die Erklärung je Person zeigt die Abweichung vom Mittelwert und nur bei
  Personen über dem Mittelwert einen Treffer.
- **Beispiel `fairNights`:** Paul und Rita, beide Vollzeit und im Nachtausgleich: Paul hatte in den letzten drei
  Monaten zwölf Nächte, Rita vier. Neue Nächte gehen eher an Rita als an Paul.
- **Beispiel `fairWeekends`:** Sven hat in den letzten drei Monaten an acht Wochenenden gearbeitet, Tina an drei.
  Das nächste Wochenende im Dienst bekommt eher Tina.
- **Beispiel `fairHolidays`:** Uwe hatte im Vormonat Dienst an einem Feiertag, Vera nicht. Am nächsten Feiertag
  ist eher Vera dran.
- **Beispiel `fairUnpopular`:** Die Schicht Kurz 18-22 hat im Katalog „Fair verteilen“ angekreuzt. Sie geht nicht immer
  an dieselben, sondern verteilt sich auf alle, die sie machen können.
- Die Beispiele zeigen, was die Regeln vergleichen und bevorzugen. Wie wenig das den fertigen Plan bewegt, steht
  im nächsten Punkt.
- Wirkung der Wichtigkeit „Faire Verteilung“ (Messung im Demomonat mit klaren Verteilungen, 300 s): Das 0,25-,
  1- oder 4-Fache (Gewichte 1, 3 oder 12, fair verteilte 1, 2 oder 8) ändert die Fairness nicht über das Rauschen
  zwischen zwei Läufen. Die Gründe:
  - Die vier Fairness-Regeln sind zusammen rund 0,5 % der weichen Strafe. Den Rest tragen gewohnte Verteilung
    (47 %), Sollstunden (24 %), Blocklänge (15 %), einzelne freie Tage (8 %) und Wünsche (6 %).
  - Die Strafe ist eine Wurzel über alle Verglichenen. Wandert ein Dienst von einer Person über dem Mittel zu
    einer darunter, sinkt sie typisch nur um 15 bis 50 Punkte (mal Gewicht: 50 bis 150). Ein solcher Tausch zweier
    Personen am selben Tag kostet dagegen vor allem bei Sollstunden und Verteilung im Median 60 000 bis 110 000.
    Er lohnt sich im Median erst beim rund 500- bis 2000-Fachen des Gewichts; zwischen 0,25 und 4 lohnen sich
    praktisch dieselben Tausche.
  - Die Historie (3 Monate) trägt 80 bis 90 % der Last. Sie macht den Ausgleich nicht unmöglich: Ein Monat, der
    nur auf Fairness achtet und sonst nichts beachtet, senkte die Strafe höchstens um 25 bis 40 %; der fertige
    Plan liegt aber meist etwas über der Strafe der Historie allein.
  - Der Solver ist nach 300 s nicht am Ende (die weiche Strafe sinkt auch in der letzten Minute um rund 4 %) und
    verbessert vor allem die großen Posten. Die Fairness schwankt dabei um ± 5 % ohne Richtung.
  - Die Fairness wirkt deshalb wie ein Ausgleich unter sonst gleichwertigen Plänen. Proben mit 120 s: Ohne sie
    (Gewicht 0) waren die Wochenenden rund 15 % ungleicher, die übrigen kaum; das 100-Fache brachte 5 bis 13 %
    mehr Ausgleich (ein Lauf, Nächte und fair verteilte Dienste im Rauschen). Der Bereich 0,25 bis 4 ist also viel zu
    klein, um die Fairness spürbar zu verschieben; die Gewichte bleiben, wie sie sind. Nachrechnen:
    `FairnessAnalysisTest` im Backend.
- Nachtdienst: ein Dienst, der mindestens `nightMinHours` (2 h) im Nachtfenster `nightStart` bis `nightEnd`
  (22:00 bis 06:00) liegt.
- Wochenende gearbeitet: ein Dienst beginnt am Samstag oder Sonntag.
- Feiertagsdienst: ein Dienst beginnt an einem Feiertag.

### Wünsche

- Je Person zählen höchstens `maxWishesPerPerson` Wünsche des Monats (Standard 6), die ersten nach Datum.
- Arten: frei, Dienst, bestimmte Schicht, bestimmte Schicht nicht.
- Ziel ist eine gleiche Erfüllungsquote je Person, nicht die Reihenfolge der Eingabe. Die Strafe ist
  100 × (U² - V²) / W: U die nicht erfüllten Wünsche mit den Vormonaten, V die nicht erfüllten der Vormonate
  allein, W alle Wünsche mit den Vormonaten. Ein weiterer unerfüllter Wunsch kostet bei der Person am meisten,
  deren Quote schon am schlechtesten ist.
- **Beispiel:** Sara und Tom wünschen beide den 24. frei, aber nur eine Person kann frei bekommen. Beide hatten
  in den Vormonaten zehn Wünsche; Sara bekam alle erfüllt, Tom drei nicht. Toms Wunsch geht vor: Bei ihm kostet
  der unerfüllte Wunsch 100 × (16 - 9) / 11 = 63, bei Sara 100 × 1 / 11 = 9 (mal Gewicht). Ein Wunsch, der
  einen Regelverstoß oder eine Lücke bedeuten würde, bleibt bei jedem Gewicht unerfüllt.
- **Wichtigkeit („Wünsche“, Gewicht 150, 300 oder 600):** Das 0,5- und 2-Fache ist nicht gemessen. Erwartbar
  erfüllt „mehr“ mehr Wünsche und nimmt dafür eher einzelne freie Tage, Blöcke außerhalb der Blocklänge und
  Abweichungen vom Ziel in Kauf. Dass Wünsche und Blöcke gegeneinander stehen, legt 0.6.0 nahe: Mit viel
  höheren Blockgewichten (und zugleich dem verkleinerten Ziel) stiegen die unerfüllten Wünsche von 24 auf 38.

### Blöcke, Rotation, Wochenenden

- Blöcke: aufeinanderfolgende Tage mit Dienst. Kürzer als `minBlock` (2) oder länger als `maxBlock` (4)
  kostet je Tag Unterschied. Ein Block, der am Monatsende endet, gilt nicht als zu kurz, weil er im
  Folgemonat weitergehen kann. Ersatzruhe zählt als freier Tag.
- Einzelner freier Tag: genau ein freier Tag zwischen zwei Diensten. Ein einzelner Abwesenheits- oder
  Ersatzruhetag zwischen zwei Diensten zählt nicht, weil die Planung ihn nicht verschieben kann.
- **Beispiel `blockLength`:** Wim hätte Dienst am Montag, Mittwoch und Freitag, mit je einem freien Tag dazwischen:
  drei Einzeldienste, je ein Tag zu kurz (dazu zwei einzelne freie Tage). Ein Block von sechs Tagen wäre zwei
  Tage zu lang. Beides vermeidet die Regel, sie bevorzugt etwa drei Tage Dienst und dann mehrere freie.
- **Beispiel `isolatedDayOff`:** Xenia hätte Dienst am Montag und Dienstag, frei am Mittwoch, Dienst am Donnerstag
  und Freitag. Der einzelne freie Mittwoch erholt wenig; die Regel zieht die freien Tage zusammen.
- Gewichte (ab 0.6.0): Einzelner freier Tag 10000 je Fall, Blocklänge 6000 je Tag. Mit dem verkleinerten
  Ziel prägen sie den Plan sichtbar: Im Demomonat sanken die einzelnen freien Tage von 261 auf 76, die
  Tage außerhalb der Blocklänge (2 bis 4) von 602 auf 328; der Preis: unerfüllte Wünsche stiegen von 24 auf 38. Mit
  deutlich weniger Bedarf als Arbeit sanken die einzelnen freien Tage von 123 auf 16. Wer die Gewichte auf der
  Seite Regeln einmal gespeichert hat, behält die gespeicherten Werte; dort lassen sie sich auf die neuen
  Standards setzen. Die Analyse zeigt beide Zahlen (`singleDaysOff`, `blockDays`).
- **Wichtigkeit („Dienstblöcke“, Gewichte 3000, 6000 oder 12000 je Tag und 5000, 10000 oder 20000 je
  einzelnem freien Tag):** Gemessen ist nur der Sprung von den alten Standards (600 und 400, also das 10- und
  25-Fache) auf die heutigen, zusammen mit dem verkleinerten Ziel, mit den Zahlen oben. Das 0,5- und 2-Fache
  ist nicht gemessen. Erwartbar wirkt es in dieselbe Richtung, aber schwächer: „mehr“ gibt weniger Einzeldienste
  und einzelne freie Tage und kostet eher Wünsche und Stunden nahe am Ziel, „weniger“ umgekehrt.
- Vorwärtsrotation: die Reihenfolge Früh, Tag, Spät, Nacht. Ein Wechsel von einem Tag auf den nächsten in
  eine frühere Kategorie kostet. Nacht auf Früh ohne genug Ruhe ist ohnehin als Regelverstoß verboten.
- Kategorie einer Schicht: aus dem Schichtkatalog; fehlt sie, gilt Nacht (siehe oben), Früh (Beginn vor
  7:00), Tag (Ende bis 19:00), sonst Spät.
- **Beispiel `forwardRotation`:** Yvonne hat Spät 12-20 und am nächsten Tag Tag 08-16. Die Ruhezeit von 12 h ist
  eingehalten, der Wechsel geht aber rückwärts (Spät auf Tag) und kostet. Tag 08-16 und danach Spät 12-20 kostet
  nichts.
- **Wichtigkeit:** Die Regel gehört zu keinem Planungsziel, ihr Gewicht (500) steht nur unter Erweitert. Nicht
  gemessen; erwartbar gibt ein höheres Gewicht weniger Rückwärtswechsel, auf Kosten von Blöcken, Wünschen und
  Stunden.
- Ganze Wochenenden: Samstag und Sonntag desselben Wochenendes, von denen nur einer einen Dienst hat (ein
  Wochenende über den Monatsbeginn zählt mit dem Samstag aus dem Vormonat). Ist einer der beiden Tage ein Abwesenheitstag, zählt das Wochenende nicht. Die faire Verteilung der
  gearbeiteten Wochenenden steckt in `fairWeekends`.
- **Beispiel `splitWeekend`:** Zoe hätte am Samstag Dienst und am Sonntag frei. Die Regel bevorzugt ein ganz
  freies oder ein ganz gearbeitetes Wochenende: Mit nur einem freien Tag ist das Wochenende kaum Erholung.
- **Wichtigkeit:** Die Regel gehört zu keinem Planungsziel, ihr Gewicht (300) steht nur unter Erweitert. Nicht
  gemessen; erwartbar gibt ein höheres Gewicht mehr ganze Wochenenden, auf Kosten von Blöcken, Wünschen und
  Stunden. Die freien Sonntage (Regelverstoß) gelten bei jedem Gewicht.

### Ersatzruhe

- Personen mit offenem Guthaben bekommen Ersatzruhetage geplant (Bonus je Tag), höchstens bis zum Guthaben
  (Gruppe Regelverstoß).
- **Beispiel `compRestUse`:** Anton hat drei Ersatzruhetage offen. Ohne den Bonus plante der Solver sie nur, wo
  sie anderswo helfen (ein Ersatzruhetag zählt für das Ziel wie ein angerechneter Tag); so könnten sie Monat für
  Monat stehen bleiben. Der Bonus verhindert das.
- **Wichtigkeit:** Die Regel gehört zu keinem Planungsziel, ihr Gewicht (500 je Tag) steht nur unter Erweitert.
  Nicht gemessen; erwartbar baut ein höheres Gewicht mehr Ersatzruhe ab, höchstens bis zum Guthaben. Bei 0 plant
  der Solver Ersatzruhe nur, wenn sie aus anderen Gründen passt.
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
- **Beispiel `family`:** Clara holt montags ihr Kind ab und hat die Familienregel „spätestes Ende 16:00“. Spät
  14-22 am Montag verstößt dagegen und kostet; Früh 06-14 nicht. Fehlt am Montag sonst jemand für den Spätdienst
  und kann nur Clara ihn machen, bekommt sie ihn trotzdem, bei jedem Gewicht, weil der Bedarf vorgeht.
- **Wichtigkeit („Familienregeln“, Gewicht 400, 800 oder 1600):** Nicht gemessen. Die Wichtigkeit entscheidet nur
  zwischen Plänen mit gleicher Besetzung: „mehr“ bricht die Regel erwartbar seltener für bessere Blöcke, Wünsche
  oder Stunden, „weniger“ öfter. Soll ein Dienst auch bei einer Lücke ausgeschlossen sein, braucht es eine Sperre
  „nie“ (Regelverstoß).
- Sperren der Art "nur auf Anfrage" plant der Solver nie ein, auch nicht, wenn sonst eine Bedarfslücke bleibt:
  Die Planung fragt die Person und trägt den Dienst dann von Hand ein (fixiert) oder als Vorgabe in den
  Eingaben. Ein solcher Dienst ist kein Regelverstoß; er erscheint als Treffer der Regel `onRequest`, damit
  er sichtbar bleibt. Ihr Gewicht ist fest; ein gespeichertes Gewicht für `onRequest` wird ignoriert. Ein nicht
  fixierter Anfrage-Dienst aus einem früheren Plan fällt bei der nächsten Planung weg. Zu jeder Lücke zeigt die
  Oberfläche, wer gefragt werden kann (siehe [api.md](api.md), `candidates`).
- **Beispiel `onRequest`:** David macht Nacht 19-07 nur auf Anfrage. Der Solver plant ihm nie eine Nacht, auch
  nicht für eine Lücke. Sagt David zu, setzt die Planung den Dienst von Hand; der Treffer zeigt dann, dass es eine
  Ausnahme ist. Das feste Gewicht ändert keinen Plan, weil der Solver solche Dienste ohnehin nie setzt.
- Sperren gelten für eine Schicht oder für alle Schichten einer Kategorie (z. B. keine Nachtdienste), je
  Wochentag und optional nur von `validFrom` bis `validTo`.
- Abwechslung: gezählt werden Paare von Diensten im selben Bereich und derselben Kategorie (Früh, Tag, Spät,
  Nacht) bzw. im selben Bereich, einschließlich der Dienste des Vormonats.
- **Beispiel `shiftVariety`:** Emma hat keine gewohnte Verteilung. Zehn Frühdienste in Bereich A ergeben
  10 × 9 / 2 = 45 Paare, fünf Früh und fünf Spät nur 2 × 10 = 20. Die Regel verhindert, dass jemand ohne Grund
  immer dieselbe Art Dienst bekommt.
- **Beispiel `areaVariety`:** Felix darf in Bereich A und B arbeiten und hat keine Anteile je Bereich. Zehn Dienste
  nur in A ergeben 45 Paare, fünf in A und fünf in B nur 20; so bleibt er in beiden Bereichen geübt.
- **Wichtigkeit:** Beide Regeln gehören zu keinem Planungsziel, ihre Gewichte (5 und 1 je Paar) stehen nur unter
  Erweitert. Nicht gemessen; erwartbar bringt ein höheres Gewicht mehr Abwechslung, auf Kosten von Blöcken,
  Wünschen und Stunden. Für Personen mit Anteilen je Schichtart bzw. je Bereich gelten sie nicht (siehe Gewohnte
  Verteilung).

## Offene Punkte für die Betriebsvereinbarung

Diese Punkte legt der KV-Text nicht eindeutig fest. Das Programm verwendet die genannten Standardwerte; jeder
ist ein Parameter, entscheidet die Betriebsvereinbarung.

1. **Teilzeit im Durchschnitt.** Die Durchrechnungsregel gilt für die Disposition: Der regionale Anhang des KV
   nennt Leitstellendisponentinnen und Leitstellendisponenten ausdrücklich (Punkt 6.1) und erlaubt im Rettungs- und
   Krankentransportdienst eine Durchrechnung über 52 Wochen mit bis zu 12 h am Tag und einzelnen Wochen bis 60 h,
   wenn die Wochenarbeitszeit im Schnitt 46,16 h nicht übersteigt (Punkt 6.1.1). 46,16 h ist also der
   Vollzeitschnitt dieser Regelung; die allgemeine Vollzeit sind 40 h je Woche (§ 15). Teilzeit ist ein Vertrag
   unter der Vollzeit (§ 38); wie 46,16 h für Teilzeit gilt, sagen weder KV noch Anhang. Nach dem
   Arbeitszeitgesetz (§ 19d) ist die vereinbarte Stundenzahl die Normalarbeitszeit einer Teilzeitkraft, was darüber
   liegt, ist Mehrarbeit; § 38 KV gibt einen Anspruch auf mehr Vertragsstunden, wenn der Schnitt über sechs Monate
   die vereinbarten Stunden um zwei oder mehr übersteigt. Das Programm misst deshalb jede Person an ihren eigenen
   Wochenstunden plus einem Spielraum (`averageTolerancePercent`, Standard 10 %), höchstens 46,16 h. Wie groß der
   Spielraum sein darf, entscheidet die Betriebsvereinbarung. 12 h je Dienst und 60 h je Woche sind Obergrenzen
   des KV und gelten für alle gleich. Parameter `maxDutyHours`, `maxWeekHours`, `maxAverageWeekHours`,
   `averageTolerancePercent`.
2. **Ersatzruhetag für Feiertagsarbeit.** Der regionale Anhang des KV (Punkt 4.1) ist für die Gruppe geschrieben,
   zu der die Disponentinnen und Disponenten gehören: Arbeit an einem Feiertag, der kein Sonntag ist, bringt einen
   zusätzlichen Ersatzruhetag. Der Parameter bleibt, damit eine andere Regelung abgebildet werden kann. Parameter
   `holidayWorkEarnsCompRest`. Eine Regel gegen Dienste an einem Feiertag mitten im Urlaub enthält der KV nicht;
   `freeInsideAbsence` ist eine Planungsregel des Betriebs.
3. **Nachtzeit.** Der für die Leitstelle maßgebliche KV-Teil definiert keine Nacht. 22:00 bis 06:00 ist nur ein
   Standardwert. Parameter `nightStart`, `nightEnd`, `nightMinHours`.
4. **Woche.** Wochenruhe und Wochenstunden werden je Kalenderwoche (Montag bis Sonntag) geprüft, nicht über
   ein gleitendes 7-Tage-Fenster.
5. **Durchschnitt.** Er wird je Kalenderhalbjahr (Standard) oder gleitend über `averagingWeeks` Wochen aus der
   Historie und dem Monat gerechnet, je Person gegen ihre eigene Grenze (Punkt 1), und gehört zur Gruppe
   Regelverstoß, wo die Historie den Zeitraum abdeckt. Der KV erlaubt bis zu 52 Wochen und überlässt die
   Einzelheiten einer Betriebsvereinbarung; der Standard folgt der üblichen Abrechnung je Kalenderhalbjahr
   (`averagingPeriod`). Ob Abwesenheiten als Arbeitszeit zählen, ist festzulegen. Das Stundenkonto gleicht sich
   standardmäßig bis Ende des Halbjahrs aus (`accountBalancing`); `accountCarryShare` ist der feste Anteil der
   Alternative.
9. **Abwesenheitsarten.** Welche Abwesenheiten das Soll senken und welche das Stundenkonto belasten
   (`reducesTarget`, `chargesAccount`), legt der Import je Art fest; die Zuordnung ist mit der
   Personalverrechnung abzustimmen. Ebenso die Gutschrift (`credit`): Wochenstunden / 5 oder der ersetzte Dienst.
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
