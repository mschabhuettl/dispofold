# Änderungen

Jede Version steht auch als Release auf GitHub, mit Images, Installationsdateien und Prüfsummen. Installation
und Update: [docs/betrieb.md](docs/betrieb.md).

## Unveröffentlicht

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
