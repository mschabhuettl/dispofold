# Änderungen

Jede Version steht auch als Release auf GitHub, mit Images, Installationsdateien und Prüfsummen. Installation
und Update: [docs/betrieb.md](docs/betrieb.md).

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
