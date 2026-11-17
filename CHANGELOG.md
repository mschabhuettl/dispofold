# Änderungen

Jede Version steht auch als Release auf GitHub, mit Images, Installationsdateien und Prüfsummen. Installation
und Update: [docs/betrieb.md](docs/betrieb.md).

## 0.9.0 (2026-11-17)

Einfachere Oberfläche: was am Plan nicht stimmt, steht oben; ein Name je Begriff. Die Planungsergebnisse sind
unverändert.

- **Dienstplan, Kopf:** Planen, Stopp und Fixierungen lösen stehen in einer Zeile. Startwert, Zeitlimit,
  Schrittzahl, Neu beginnen und der Score stehen unter „Erweitert“ (zugeklappt). Drucken, Export (XLSX, CSV,
  JSON) und Monat abschließen sind im Menü „Ausgabe“; Plan nach Funktion hat dasselbe Menü mit Drucken und
  Export. Die Legende ist zugeklappt und merkt sich ihren Zustand.
- **Statuszeile:** Eine farbige Schlagzeile statt des Scores, z. B. „Plan verwendbar: keine Regelverstöße,
  keine Lücken“, „3 Lücken (16 Personenstunden)“ oder „2 Regelverstöße“. Darunter ein Knopf je verletzter
  Regel und je Qualitätsregel mit großem Anteil am Score, meist mit der Zahl der Treffer; ein Klick listet, wen
  es an welchem Tag betrifft.
- **Lückenzeile:** Die Zeile „Bedarfslücken“ im Dienstplan und die Zeile „offen“ in Plan nach Funktion bleiben
  beim Scrollen oben stehen.
- **Regeln:** Speichern steht in einer Leiste, die immer sichtbar ist; vor dem Verlassen mit ungespeicherten
  Änderungen kommt eine Rückfrage. Gewichte sind Zahlenfelder mit Standardwert und „Zurücksetzen“. Die
  Regeltexte nennen die eingestellten Werte. „Ausgleich Stundenkonto je Monat“ wird in Prozent eingegeben
  (25 % statt 0,25; gespeichert wird wie bisher der Anteil).
- **Person:** Eine Kachel „x h von Ziel y h (Soll z h)“ mit einer Erklärung von Soll und Ziel statt fünf
  Kacheln. „Warum dieser Dienst?“ in Worten und Zahlen, ohne Score-Werte.
- **Begriffe, überall gleich (Oberfläche, Druck, Anleitung):**
  - Die drei Gruppen der Regeln heißen „Regelverstoß“, „Bedarf“ und „Qualität“ (statt hart, mittel, weich).
  - Die Regel „Sollstunden“ heißt „Stunden treffen“; für Stunden gibt es nur noch „Soll“ und „Ziel“.
  - „Fixe Dienste“ in den Eingaben heißen „Vorgaben“; „fixiert“ steht nur noch für fixierte Zellen im Plan.
  - „Plan veraltet: neu planen“ statt „Eingaben geändert“ und „Vormonat geändert“; der Grund steht im Tooltip.
  - „Familienregel“ statt „Familienwunsch“, „Gewohnte Verteilung“ als einziger Name der Verteilung.
  - „Tage außerhalb der Blocklänge“ statt „Blocktage abweichend“.
  - „Auf Anfrage verfügbar“ statt „Wer kann gefragt werden?“.
  - Stand des Plans am Bildschirm und im Druck: „Entwurf“ oder „abgeschlossen“.
- **Stammdaten:** Die Personentabelle zeigt Bereichsnamen statt Kürzel, das Formular Name und Kürzel, auch bei
  den Feldern der gewohnten Verteilung. Wo die gewohnte Verteilung bearbeitet oder gezeigt wird (Stammdaten,
  Person), steht: Wer Anteile je Schichtart hat, ist nicht im Nachtausgleich („Fairness Nachtdienste“) und
  nicht in der „Abwechslung Dienste“.
- **Monat abschließen:** Der Dialog zeigt eine Prüfliste (Regelverstöße, offene Lücken, Plan aktuell). Mit
  offenen Punkten braucht das Abschließen eine zweite Bestätigung. Neu auch im Menü „Ausgabe“ des Dienstplans.
- **Rolle Lesen:** Import/Export steht nicht mehr in der Navigation; die Adresse der Seite zeigt den Dienstplan.
  Die Legende zeigt nur, was diese Rolle sieht.
- **Import/Export:** Keine Dateipfade und Formatangaben mehr auf der Seite; „Demodaten laden“ steht unter
  „Erweitert“.
- **Schnittstelle:** neuer Lesezugriff `GET /api/plans/{month}/hits` liefert die Treffer des Plans je Regel in
  einer Anfrage (für die Liste hinter den Knöpfen der Statuszeile).
- Betrieb: keine Änderung an Datenbank, `docker-compose.yml` oder `.env`.

## 0.8.0 (2026-11-17)

Drucken aus beiden Planansichten, Dezimalwerte in den Formularen.

- **Drucken:** Dienstplan und Plan nach Funktion haben den Knopf „Drucken“. Die Druckansicht ist A4 quer, mit
  einer Kopfzeile (Monat, Stand „Entwurf“ oder „abgeschlossen“, Druckdatum) und einer Legende; der Monat steht
  in zwei Hälften (Tag 1 bis 15, Tag 16 bis Monatsende), jede auf einer neuen Seite, der Tabellenkopf auf jeder
  Seite. Menü und Bedienelemente werden nicht gedruckt. Der Dienstplan druckt dieselben Personen und Gruppen
  wie am Bildschirm, mit Abwesenheiten, Ersatzruhe, fixierten Diensten, geplanten Stunden und Bedarfslücken.
  Neu im Dienstplan: die Auswahl „Anzeige“ („Nachname Vorname“ oder „Kürzel“), auch für den Druck. Die Rolle
  Lesen druckt nur, was sie am Bildschirm sieht. Im Desktop-Programm öffnet sich der Druckdialog des Systems.
- **Formulare:** Wochenstunden und Stundenkonto mit zwei Nachkommastellen (z. B. 46,15 aus einem Import) ließen
  sich nicht speichern, auch wenn nur ein anderes Feld geändert wurde. Diese Felder und die Dezimalwerte auf der
  Seite Regeln nehmen jetzt jeden Wert, den das Programm speichert.
- Betrieb: keine Änderung an Datenbank, `docker-compose.yml` oder `.env`.

## 0.7.0 (2026-11-15)

Gewohnte Verteilung der Dienste je Person und freie Sonntage.

- **Gewohnte Verteilung:** Eine Person kann Anteile ihrer Dienste je Schichtart (Früh, Tag, Spät, Nacht) und
  je Bereich haben, z. B. aus der Historie exportiert (neues optionales Feld `shiftMix`). Die Planung folgt
  ihnen (weiche Regel „Gewohnte Verteilung“, Gewicht 100). Unter Stammdaten bearbeiten oder entfernen; die
  Personenansicht zeigt geplante gegen gewohnte Anteile. Für Personen mit Verteilung entfallen die
  Nacht-Fairness und die Abwechslung (sonst würden sie die Verteilung einebnen). Ältere Datensätze ohne das Feld
  laden unverändert.
- **Freie Sonntage:** Jede Person hat im Kalendermonat mindestens zwei Sonntage ohne Dienst (harte Regel,
  einstellbar auf der Seite Regeln, 0 schaltet sie aus). Ein Sonntag ist frei, wenn kein Dienst ihn von 0 bis
  24 Uhr berührt, auch kein Nachtdienst von Samstag auf Sonntag; Abwesenheiten zählen als frei.
- Rolle Lesen: Verteilung und ihre Treffer sind nur für die Planung sichtbar.
- Betrieb: keine Änderung an Datenbank, `docker-compose.yml` oder `.env`.

## 0.6.0 (2026-11-15)

Blöcke statt einzelner freier Tage, auch wenn der Bedarf weniger Arbeit braucht, als die Ziele hergeben.

- **Ziel für diesen Plan:** Braucht der Bedarf weniger Arbeit, als die Ziele aller Personen zusammen ergeben,
  wird jedes Ziel um denselben Anteil verkleinert (angerechnete Abwesenheiten und offene Ersatzruhe bleiben).
  Die weiche Sollstunden-Regel misst gegen dieses Ziel, die harte Obergrenze weiter gegen das echte Ziel. Die
  Personenansicht zeigt Soll, Ziel und Ziel für diesen Plan.
- **Blöcke bevorzugt:** Standardgewichte „Einzelner freier Tag“ 10000 (vorher 400) und „Blöcke von 2 bis 4
  Diensten“ 6000 (vorher 600). Im Demomonat sanken die einzelnen freien Tage von 261 auf 76, unerfüllte
  Wünsche stiegen von 24 auf 38. Gespeicherte Gewichte bleiben; auf der Seite Regeln lassen sie sich auf die
  neuen Standards setzen.
- **Statuszeile und Analyse:** Zahl der einzelnen freien Tage und der Blocktage außerhalb von 2 bis 4
  (`singleDaysOff`, `blockDays`); in den Kennzahlen je Person `goalHours` und `scaledGoalHours`.
- Rolle Lesen: Treffer zu Sollstunden und Höchststunden sind nur für die Planung sichtbar (sie enthalten das
  um das Stundenkonto korrigierte Ziel).
- Betrieb: keine Änderung an Datenbank, `docker-compose.yml` oder `.env`.

## 0.5.0 (2026-11-15)

Keine doppelt besetzten Positionen mehr, nur um Sollstunden zu füllen.

- **Überbesetzung zählt jetzt wie fehlender Bedarf auf der mittleren Stufe:** Personen über der Höchstbesetzung
  (ohne Höchstbesetzung über der Mindestbesetzung) und Dienste ganz ohne Bedarf wiegen schwerer als jede weiche
  Regel, auch die Sollstunden. Ist der Bedarf gedeckt, bleiben übrige Sollstunden offen, statt Positionen
  doppelt zu besetzen. Fehlender Bedarf wiegt weiter schwerer als jede mögliche Überbesetzung: Eine Lücke wird
  gefüllt, wenn jemand frei ist.
- **Statuszeile:** ungedeckter Bedarf und Überbesetzung getrennt in Personenstunden, auch während der Planung.
- Von Hand gesetzte und fixe Dienste dürfen überbesetzen; sie erscheinen dann in der mittleren Stufe.
- Regeln: Die Gewichte von `overstaffing` und `uselessDuty` sind fest. Gespeicherte Werte laden weiter und
  werden ignoriert.
- REST-Schnittstelle: Die Analyse liefert `uncoveredHours` und `overstaffedHours`.
- Betrieb: keine Änderung an Datenbank, `docker-compose.yml` oder `.env`.

## 0.4.0 (2026-11-15)

Der Solver plant niemanden mehr weit über sein Ziel, und Lücken stehen im Plan nach Funktion einmal je Bereich.
Passt zum Bedarf aus der tatsächlichen Besetzung der Vormonate, den der Export jetzt standardmäßig erzeugt.

- **Höchststunden im Monat (harte Regel `maxMonthHours`):** Die angerechneten Stunden einer Person (Dienste,
  angerechnete Abwesenheiten, Ersatzruhe) dürfen das Ziel des Monats um höchstens eine Toleranz überschreiten,
  Standard 10 %, auf der Seite Regeln einstellbar. Bisher füllte der Solver Lücken mit Teilzeitkräften bis zur
  gesetzlichen Wochengrenze auf. Von Hand gesetzte Dienste darüber erscheinen als harter Verstoß; die Liste
  „Wer kann gefragt werden?“ nennt niemanden, den der Dienst über die Grenze brächte.
- **Plan nach Funktion:** Eine Bedarfslücke steht nur noch einmal je Bereich, in der Zeile „offen“ mit Zahl und
  Uhrzeit, statt bei jeder Schicht, die sie überschneidet.
- Betrieb: keine Änderung an Datenbank, `docker-compose.yml` oder `.env`. Gespeicherte Regeln ohne die neue
  Toleranz bekommen 10 %.

## 0.3.0 (2026-11-15)

„Nur auf Anfrage“ heißt jetzt: Die Planung fragt, das Programm plant es nie selbst ein. Neu ist die Ansicht
Plan nach Funktion.

- **„Nur auf Anfrage“ plant das Programm nie selbst ein**, auch nicht, wenn dadurch eine Bedarfslücke bleibt.
  Die Planung fragt die Person und setzt den Dienst von Hand (fixiert) oder als fixen Dienst in den
  Eingaben; ein solcher Dienst ist kein Verstoß und bleibt als Anfrage-Dienst markiert. Ein nicht fixierter
  Anfrage-Dienst aus einem früheren Plan fällt bei der nächsten Planung weg.
- **Wer kann gefragt werden?** Zu jeder Bedarfslücke (Zeile „Bedarfslücken“ im Dienstplan, Lückenliste der
  Bereichs-Zeitachse, Plan nach Funktion) zeigt Dispofold die Personen, die den Dienst auf Anfrage übernehmen
  könnten, die mit den wenigsten Stunden zum Soll zuerst. Ein Klick auf den Namen setzt den Dienst.
- **Plan nach Funktion:** eine zweite Ansicht desselben Plans mit den Schichten je Bereich als Zeilen und den
  Tagen als Spalten; fehlende Besetzung steht als „fehlt“ in der Zelle. In den Zellen wahlweise Kürzel oder
  „Nachname Vorname“.
- Regeln: Das Gewicht von `onRequest` ist fest und auf der Seite Regeln nicht mehr einstellbar. Gespeicherte
  Einstellungen mit diesem Gewicht laden weiter.
- Betrieb: keine Änderung an der Datenbank, an `docker-compose.yml` oder `.env`.
- REST-Schnittstelle: neu `GET /api/plans/{month}/candidates`; `GET /api/constraints` liefert `tunable`.

## 0.2.0 (2026-11-15)

Stammdaten werden jetzt in Dispofold gepflegt. Ein Import aus dem bisherigen System ist nur noch zum Start nötig.

- **Stammdaten bearbeiten** (Rolle Planung): Personen, Bereiche, Schichten, Bedarf, Feiertage und
  Abwesenheitsarten anlegen, ändern und entfernen. Je Person auch Einschränkungen und Familienregeln; beide
  stehen jetzt auch in der Personenansicht.
- Jede Änderung wird mit derselben Prüfung wie ein Import geprüft; bei Fehlern wird nichts gespeichert.
- **Entfernen:** Was ein abgeschlossener Monat oder die importierte Historie verwendet, bleibt (für Personen
  stattdessen den Austritt eintragen). Verweise in offenen Monaten werden nach einer Rückfrage mit entfernt;
  betroffene Pläne zeigen „Eingaben geändert“.
- Neue Personen und Schichten bekommen ihre Id vom Programm (`PN1`, `SN1`, ...). Bereiche und
  Abwesenheitsarten behalten ihr Kürzel als Id; es wird beim Anlegen eingegeben und nach dem Entfernen nicht
  wieder vergeben.
- Hat jemand anderer inzwischen gespeichert, fragt Dispofold wie bei den Eingaben eines Monats nach.
- **Datensatz exportieren** unter Import/Export: Stammdaten und alle Planungsmonate als Datensatz zum
  Wiedereinspielen. Die Datei enthält Personaldaten.
- **Import:** Wurden die Stammdaten seit dem letzten Import in Dispofold geändert, fragt der Import vorher
  nach und bietet den Export an.
- Betrieb: Beim ersten Start ergänzt Dispofold die Datenbank selbst (Migration V3). `docker-compose.yml` und
  `.env` bleiben unverändert.
- REST-Schnittstelle: Der Endpunkt `PUT /api/dataset/people/{id}` verlangt jetzt die Revision (`If-Match`) und
  antwortet mit `MasterChange`; siehe [docs/api.md](docs/api.md).

## 0.1.0 (2026-11-13)

Erste Version: Monatsplanung mit Solver, Eingaben, Monatsabschluss, Exporte, Import aus dem Datenformat,
Server-Betrieb mit Docker und Desktop-Programm für Windows, Linux und macOS mit signierten Updates.
