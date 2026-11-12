# REST-Schnittstelle des Backends

Alle Endpunkte liegen unter `/api`, sprechen JSON und verlangen eine Anmeldung. Die Typen sind die Java-Records
in `backend/src/main/java/app/dispofold/model` (Felder wie dort, siehe auch `docs/datenformat.md`).

Rollen: `planner` (lesen und ändern) und `viewer` (nur lesen). Alle `GET` sind für beide Rollen erlaubt, alle
anderen Methoden nur für `planner`. Leser bekommen Gesundheits- und Personaldaten nicht zu sehen (siehe
[Leser-Rolle](#leser-rolle)).

## Anmeldung

| Methode | Pfad | Beschreibung |
|---|---|---|
| `POST` | `/j_security_check` | Formular-Login (`j_username`, `j_password`), setzt ein Sitzungs-Cookie |
| `POST` | `/api/logout` | meldet ab (löscht das Cookie), Antwort `204` |
| `GET` | `/api/me` | `{ "username": "...", "role": "planner" \| "viewer", "desktop": bool }` |

Die Anmeldeseite ist `/login.html`. Nicht angemeldete Aufrufe von `/api/*` bekommen `401`, andere Seiten
leiten auf `/login.html` um. Ohne Anmeldung erreichbar sind nur die Anmeldeseite mit ihrem Skript und dem
Theme-Skript (Dateinamen mit Inhalts-Hash), `/css/*`, `/fonts/*`, `/img/*`, `/favicon.*` und `/q/health/*`.
Im Docker-Betrieb begrenzt nginx die Anmeldeversuche auf 10 je Minute und Adresse (danach `429`).

## Stammdaten und Import

| Methode | Pfad | Antwort |
|---|---|---|
| `GET` | `/api/dataset` | `Dataset` mit allen Stammdaten, `periods` leer (vor dem ersten Import ein leerer Datensatz) |
| `PUT` | `/api/dataset/people/{personId}` | ändert eine Person (Body `Person`), Antwort `Person` |
| `POST` | `/api/import/dataset` | lädt ein `Dataset`-Dokument (Body, siehe `docs/datenformat.md`), Antwort `ImportSummary` |
| `POST` | `/api/import/demo` | lädt die mitgelieferten synthetischen Demodaten (150 Personen, Jänner 2027), Antwort `ImportSummary` |

`ImportSummary`: `{ "source", "people", "areas", "shifts", "months": ["YYYY-MM"], "warnings": [{ "code", "params" }] }`.
Ein Import ersetzt die Stammdaten und die Planungsmonate, die im Datensatz enthalten sind. Pläne bleiben.
Ein fehlerhafter Datensatz wird als Ganzes abgelehnt (`400` `import.invalid` mit allen Fehlern in `errors`);
nichts wird stillschweigend verworfen. Passt die Datei nicht zum Format (kein JSON, falsche Typen), nennt
`import.parse` nur die Stelle (Zeile, Spalte), nie den Inhalt. Während einer Planung (`import.solving`) und für
abgeschlossene Monate (`import.closed`) ist der Import gesperrt.

## Regeln und Gewichte

| Methode | Pfad | Antwort |
|---|---|---|
| `GET` | `/api/rules` | `Rules` |
| `PUT` | `/api/rules` | speichert `Rules` (Body), Antwort `Rules`; die Mindestruhe ist mindestens 11 Stunden |
| `GET` | `/api/constraints` | `[{ "id", "level", "defaultWeight", "weight" }]`; Name und Beschreibung jeder Regel stehen in der Oberfläche |

## Planungsmonate

| Methode | Pfad | Antwort |
|---|---|---|
| `GET` | `/api/months` | `[{ "month": "YYYY-MM", "hasPeriod": bool, "status": "EMPTY"..., "score": "..." }]` |
| `GET` | `/api/periods/{month}` | `{ ...Period, "revision": n }` (leer, wenn es noch keinen gibt) |
| `PUT` | `/api/periods/{month}` | speichert Abwesenheiten, Wünsche, fixe Dienste (Body `Period`), Antwort wie `GET` |

Ein `PUT` muss die gelesene Revision mitschicken (Header `If-Match: <n>` oder Feld `revision` im Body). Fehlt
sie, antwortet das Backend mit `428` `period.revisionMissing`; hat inzwischen jemand anderer gespeichert, mit
`409` `period.conflict`. Ungültige Einträge lehnen das ganze Speichern ab (`400` `period.invalid` mit `errors`).

## Pläne

| Methode | Pfad | Antwort |
|---|---|---|
| `GET` | `/api/plans/{month}` | `PlanState`: `{ "plan", "solving", "elapsedMillis", "hardViolations", "stale", "previousMonthChanged", "previousFromPlan" }` |
| `POST` | `/api/plans/{month}/solve` | startet die Planung im Hintergrund, Antwort sofort `202` mit `PlanState`. Body optional: `{ "seed", "timeLimitSeconds", "moveLimit", "coldStart" }` |
| `POST` | `/api/plans/{month}/stop` | beendet die Planung, der beste Stand bleibt |
| `PUT` | `/api/plans/{month}/cells` | ändert eine Zelle, Body `PlanAssignment`; eine von Hand gesetzte Zelle ist normalerweise `pinned: true`. Antwort `Plan` |
| `POST` | `/api/plans/{month}/unpin` | löst alle Fixierungen, die nicht aus `Period.fixed` kommen |
| `GET` | `/api/plans/{month}/analysis` | `PlanAnalysis` (Score, Summen je Regel, Bedarfslücken, Kennzahlen je Person) |
| `GET` | `/api/plans/{month}/people/{personId}` | `PersonExplanation` (Kennzahlen und alle Regel-Treffer der Person) |
| `GET` | `/api/plans/{month}/coverage/{areaId}` | Liste von `CoverageSegment` für den ganzen Monat |
| `POST` | `/api/plans/{month}/finalize` | schließt den Monat ab (Status `CLOSED`), Antwort Liste von `HistoryEntry` mit `source: "plan"` |
| `POST` | `/api/plans/{month}/reopen` | öffnet einen abgeschlossenen Monat wieder |
| `GET` | `/api/plans/{month}/export?format=xlsx\|csv\|json` | Datei-Download, nur für `planner` |
| `POST` | `/api/plans/{month}/import` | ersetzt den Plan durch ein `Plan`-JSON (Body) |

Planung:

- Ohne Angaben ist eine Planung reproduzierbar: gleicher Startwert, gleiche Daten und das voreingestellte
  Zuglimit ergeben denselben Plan. `coldStart: true` verwirft den gespeicherten Plan bis auf die Fixierungen.
- `stale: true` heißt, dass sich die Eingaben nach der Planung geändert haben. `previousMonthChanged: true`
  heißt, dass der Vormonat nach dieser Planung geändert wurde. Die Dienste der Vorwoche kommen aus dem
  gespeicherten Plan des Vormonats (`previousFromPlan: true`), sonst aus den importierten Vordiensten.
- Während einer laufenden Planung fragt die Oberfläche `GET /api/plans/{month}` etwa alle zwei Sekunden ab.
  Zellen, `unpin`, `finalize`, `import`, Exporte und ein zweites `solve` sind dann gesperrt (`409`). Der beste
  Stand wird höchstens alle zwei Sekunden gespeichert. Nach `stop` ist der Status `STOPPED`, nach Ablauf
  `SOLVED`, nach einem Fehler `FAILED`.

Monatsabschluss: `finalize` speichert je Person das Stundenkonto und die Ersatzruhe nach dem Monat (verbraucht
und neu erworben, Arbeit an einem Feiertag erwirbt eine). Importierte Ist-Werte (`source: "import"`) werden nie
überschrieben. Ein abgeschlossener Monat ist nur lesbar (`409` `plan.closed`), bis ihn `reopen` wieder öffnet.
Monate werden der Reihe nach abgeschlossen (`plan.previousOpen`) und rückwärts wieder geöffnet (`plan.nextClosed`).

Exporte: Hat der Plan harte Verstöße, endet der Dateiname auf `-mit-verstoessen` und die erste Zeile der CSV
nennt sie. CSV: Semikolon, UTF-8 mit BOM, eine Zeile je Person und Tag mit
`personId;Name;date;shiftCode;shiftId;start;end;hours;compRest;pinned;absenceCode` (Stunden mit Dezimalkomma).

## Leser-Rolle

Für `viewer` enthalten `/api/dataset`, `/api/periods/{month}`, Pläne, Analyse, Personenansicht, Abdeckung und
`/api/months` keine Wünsche, keine Einschränkungen und keine Familienregeln. Abwesenheiten erscheinen nur als Art
`ABW` (abwesend), ohne den Grund. Exporte antworten Lesern mit `403`.

## Desktop-Modus

Nur im Desktop-Build (siehe `docs/desktop.md`); im Server-Build gibt es diese Endpunkte nicht. Die Oberfläche
läuft im Programmfenster. Das Fenster reicht jede Anfrage an das Backend weiter und weist sich dabei mit einem
Geheimnis je Programmstart aus, das weder in einer Adresse noch in einem Cookie steht. `/api/me` antwortet mit
`lokal`, `planner`, `desktop: true`.

| Methode | Pfad | Antwort |
|---|---|---|
| `POST` | `/api/desktop/quit` | `204`, danach beendet sich das Programm |
| `GET` | `/api/desktop/update?check=true` | `{ "current", "source", "checkOnStart", "available": { "version", "notes" } \| null, "error", "errorCode", "errorParams" }` |
| `PUT` | `/api/desktop/update/settings` | speichert `{ "source", "checkOnStart" }`; Quelle ist eine URL, ein Ordner oder leer (keine Updates, kein Netzzugriff) |
| `POST` | `/api/desktop/update/install` | lädt das neueste signierte Update, prüft es, `202`, danach Neustart |
| `POST` | `/api/desktop/update/file` | Update-Datei als Body (`application/octet-stream`, bis 1 GiB), Prüfung wie oben, `202` oder `400` |

Beim Prüfen (`check=true`) stehen Fehler in `errorCode`, `errorParams` und `error` der Antwort, nicht als
Fehlerantwort. Während das Programm neu startet, antwortet das Fenster mit `503` `desktop.restarting`.

## Fehlercodes

Jede Fehlerantwort hat die Form `{ "code": "<code>", "params": { ... }, "error": "<deutscher Ersatztext>" }`.
Die Oberfläche zeigt den Text zu `error.<code>` aus `frontend/src/lib/texts.js` und setzt die Parameter ein;
`error` gilt nur, wenn sie den Code nicht kennt. Parameter enthalten nur Ids, Zahlen und Datumsangaben, nie
Namen, Pfade oder Meldungen von Ausnahmen. Anfragen über 50 MB enden mit `413`, Anfragen ohne Längenangabe mit
`411`, beide ohne Body (einzige Ausnahme: `/api/desktop/update/file`).

Anfrage und Daten:

| Code | Status | Parameter |
|---|---|---|
| `security.unauthenticated` | 401 | |
| `security.forbidden` | 403 | |
| `request.month`, `request.solveOptions`, `request.noCell`, `request.periodMissing`, `request.periodFormat` | 400 | |
| `dataset.missing` | 409 | |
| `person.unknown` | 404 | `personId` |
| `person.idMismatch`, `person.unknownArea` | 400 | `personId` |
| `area.unknown` | 404 | `areaId` |
| `rules.missing`, `rules.nightWindow`, `rules.values`, `rules.block`, `rules.accountShare`, `rules.weights`, `rules.timeZone` | 400 | |
| `rules.minDailyRest` | 400 | `minHours` |

Import:

| Code | Status | Parameter |
|---|---|---|
| `import.parse` | 400 | `line`, `column` |
| `import.invalid` | 400 | `count`; dazu `errors` und `warnings` als Listen von `{ code, params }` |
| `import.solving`, `import.closed` | 409 | `month` |
| `import.demoMissing` | 500 | |

Die Codes in `errors` und `warnings` stehen in `docs/datenformat.md` unter "Fehlercodes beim Import".

Planungsmonate und Pläne:

| Code | Status | Parameter |
|---|---|---|
| `period.revisionMissing` | 428 | |
| `period.conflict` | 409 | `month`, `revision` |
| `period.invalid` | 400 | `count`; dazu `errors` |
| `plan.solving`, `plan.closed`, `plan.notClosed`, `plan.empty` | 409 | `month` |
| `plan.previousOpen` | 409 | `month`, `previous` |
| `plan.nextClosed` | 409 | `month`, `next` |
| `plan.unavailable` | 503 | |
| `plan.startFailed` | 500 | |
| `cell.unknownPerson` | 400 | `personId` |
| `cell.outsideMonth` | 400 | `date`, `month` |
| `cell.unknownShift` | 400 | `shiftId` |
| `cell.compRestWithShift` | 400 | |
| `planImport.format` | 400 | `format`, `version` |
| `planImport.month` | 400 | `month` |
| `planImport.duplicate` | 400 | |
| `planImport.row` | 400 | `personId`, `date`, `shiftId` |
| `export.format` | 400 | |

Regel-Treffer (`ConstraintHit`: `{ constraintId, level, date, score, code, params }`) tragen den Code
`hit.<constraintId>`; die Oberfläche zeigt den Text zu diesem Schlüssel.

Desktop-Modus:

| Code | Status | Parameter |
|---|---|---|
| `security.desktopLogin` | 401 | |
| `security.desktopForeign` | 403 | |
| `desktop.restarting` | 503 | |
| `desktop.challenge.invalid` | 400 | |
| `desktop.update.settingsMissing`, `desktop.update.sourceMissing`, `desktop.update.sourceInvalid`, `desktop.update.sourceUnsupported` | 400 | |
| `desktop.update.noSource`, `desktop.update.folderUnreachable`, `desktop.update.folderUnreadable`, `desktop.update.sourceUnreachable`, `desktop.update.checkInterrupted`, `desktop.update.sourceVersionInvalid`, `desktop.update.noneAvailable`, `desktop.update.downloadFailed`, `desktop.update.downloadInterrupted`, `desktop.update.storeFailed`, `desktop.update.incomingFolder` | 400 | |
| `desktop.update.sourceStatus`, `desktop.update.downloadStatus` | 400 | `status` |
| `desktop.update.assetMissing` | 400 | `version` |
| `desktop.bundle.notZip`, `desktop.bundle.readFailed`, `desktop.bundle.tooManyFiles`, `desktop.bundle.unsafePath`, `desktop.bundle.duplicate`, `desktop.bundle.unexpectedFile`, `desktop.bundle.unsigned`, `desktop.bundle.manifestTooLarge`, `desktop.bundle.signature`, `desktop.bundle.manifestInvalid`, `desktop.bundle.unsignedFile`, `desktop.bundle.missingFile`, `desktop.bundle.changedFile`, `desktop.bundle.tooLarge`, `desktop.bundle.unpackFailed`, `desktop.bundle.folderMissing`, `desktop.bundle.appMissing`, `desktop.bundle.link` | 400 | |
| `desktop.bundle.notNewer` | 400 | `version`, `current` |
| `desktop.bundle.knownBad` | 400 | `version` |

Beim Start prüft das Backend seine Einstellungen und bricht mit einem dieser Codes im Log ab (nie in einer
Antwort): `startup.sessionKeyMissing`, `startup.sessionKeyShort`, `startup.sessionKeyPlaceholder`,
`startup.dbPasswordMissing`, `startup.dbPasswordPlaceholder`; `startup.noUsers` ist nur eine Warnung.
