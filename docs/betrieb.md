# Betrieb (für die IT)

Dieses Dokument beschreibt Installation, Update, Sicherung, Wiederherstellung, Umzug, Protokolle und
Deinstallation jeder Auslieferung. Alle Dateien kommen aus dem Release einer Version auf GitHub
(<https://github.com/mschabhuettl/dispofold/releases>), zusammen mit SHA-256-Prüfsummen
(`dispofold-<version>-SHA256SUMS.txt` für die Images, `dispofold-<version>-linux-SHA256SUMS.txt`,
`-windows-SHA256SUMS.txt` und, falls gebaut, `-macos-SHA256SUMS.txt`). Repository und Releases sind öffentlich;
der Download braucht kein GitHub-Konto.

| Auslieferung | Dateien im Release | Für | Daten |
|---|---|---|---|
| Server (Docker) | `dispofold-<version>-images.tar.gz` (amd64) oder `dispofold-<version>-images-arm64.tar.gz` (arm64), `docker-compose.yml`, `env.example`; Images für beide Plattformen auch in der Registry `ghcr.io/mschabhuettl` | mehrere Planerinnen und Planer im Netz | PostgreSQL im Docker-Volume |
| Windows-Setup | `Dispofold-<version>-windows-setup.exe` | ein Arbeitsplatz, ohne Adminrechte | verschlüsselte Datei im Benutzerprofil |
| Windows portabel | `Dispofold-<version>-windows-portable.zip` | ohne Installation | verschlüsselte Datei im Ordner `daten\` neben dem Programm |
| Linux (x86_64) | `Dispofold-<version>-linux-amd64.deb`, `Dispofold-<version>-linux-x86_64.rpm` | ein Arbeitsplatz | verschlüsselte Datei im Benutzerordner |
| Linux (arm64) | `Dispofold-<version>-linux-arm64.deb`, `Dispofold-<version>-linux-aarch64.rpm` | ein Arbeitsplatz mit ARM-Prozessor | verschlüsselte Datei im Benutzerordner |
| macOS (Apple Silicon) | `Dispofold-<version>-macos.dmg` | ein Mac mit M-Prozessor | verschlüsselte Datei im Benutzerordner |
| Update für alle Desktop-Varianten | `dispofold-<version>-update.zip` (signiert) | Updates ohne neuen Installer | |

Die Bedienung für die Dienstplanung steht in [der Anleitung](anleitung/index.md), technische Einzelheiten der
Desktop-Version in [desktop.md](desktop.md).

## Server mit Docker

### Voraussetzungen

- Linux mit Docker Engine 24 oder neuer und dem Compose-Plugin (`docker compose`).
- 2 CPU-Kerne und 4 GB RAM. Der Solver rechnet mit einem Kern je laufender Planung.
- Etwa 3 GB Plattenplatz für die Images.
- Zur Laufzeit stellt der Server nur eine Verbindung ins Internet her: die Suche nach einer neuen Version (siehe
  [Neue Version](#neue-version)), abschaltbar mit `DISPOFOLD_UPDATE_CHECK=false`. Mit dem Archiv braucht der Server
  auch für die Installation keinen Internetzugang: Es enthält alle drei Images.

Der Stack hat drei Dienste: `db` (PostgreSQL 17), `backend` (Java, Solver, REST) und `frontend` (nginx, liefert
die Oberfläche aus und leitet `/api` an das Backend weiter, Port 8080).

### Geheimnisse und Benutzer

Alle Einstellungen stehen in `.env` (Vorlage: `env.example` aus dem Release). Die Geheimnisse müssen echte,
zufällige Werte sein. Das Backend startet nicht, wenn der Sitzungsschlüssel kürzer als 32 Zeichen ist, wenn
Sitzungsschlüssel oder Datenbankpasswort wie ein Platzhalter aussehen (zum Beispiel `changeme`, `bitte-aendern`,
`example`, `passwort`, `dienstplanung`) oder wenn ein Benutzer kein bcrypt-Passwort hat.

```bash
openssl rand -hex 24      # Wert für DB_PASSWORD
openssl rand -base64 48   # Wert für SESSION_ENCRYPTION_KEY
```

Benutzer stehen in `APP_USERS` als `name:bcrypt-hash:rolle`, mehrere durch Komma getrennt. Klartext-Passwörter
werden abgelehnt. Einen Hash erzeugt (das Passwort wird zweimal abgefragt):

```bash
docker run --rm -it httpd:2.4-alpine htpasswd -nBC 10 planer
```

Die Ausgabe ist `planer:$2y$10$...`. Gebraucht wird der Teil nach dem ersten Doppelpunkt. Den ganzen Wert in
einfache Anführungszeichen setzen, damit Compose die `$` nicht ersetzt:

```bash
APP_USERS='planer:$2y$10$....:planner,leitung:$2y$10$....:viewer'
```

Rollen: `planner` darf alles (Import, Eingaben, Planen, Ändern, Gewichte, Export, Monatsabschluss). `viewer`
darf nur lesen: keine Exporte, keine Wünsche, keine Einschränkungen, Abwesenheiten nur als „abwesend“.

`scripts/make-env.sh` im Quellcode erzeugt eine `.env` mit Zufallswerten für Teststacks (CI). Für den Betrieb
die Werte selbst wählen wie oben.

| Variable | Standard | Bedeutung |
|---|---|---|
| `REGISTRY` | `dispofold.invalid` | Herkunft der Images: `dispofold.invalid` für Images aus dem Archiv (ein Name, den es im Netz nicht gibt, damit Docker nie versehentlich fremde Images lädt), sonst `ghcr.io/mschabhuettl` |
| `TAG` | (Pflicht) | Version der Images, z. B. `1.0.0` |
| `POSTGRES_IMAGE` | `postgres:17-alpine@sha256:...` | Datenbank-Image; die `env.example` des Releases setzt es auf das Image aus dem Archiv, bei Installation aus der Registry leer lassen |
| `HTTP_PORT` | `8080` | Port der Oberfläche am Server |
| `DB_NAME`, `DB_USER` | `dienstplanung` | Name und Benutzer der Datenbank |
| `DB_PASSWORD` | (Pflicht) | Passwort der Datenbank |
| `APP_USERS` | (Pflicht) | Benutzer, siehe oben |
| `SESSION_ENCRYPTION_KEY` | (Pflicht) | Schlüssel für das Anmelde-Cookie, mindestens 32 Zeichen |
| `SOLVER_TIME_LIMIT_SECONDS` | leer | Zeitlimit einer Planung als Sicherheitsnetz, wenn in der Oberfläche keines eingetragen ist. Leer: nach der Gründlichkeit unter Regeln (schnell 300, normal 1200, gründlich 3600) |
| `SOLVER_UNIMPROVED_SECONDS` | `30` | Planung endet früher, wenn sich das Ergebnis so lange nicht verbessert. Wirkt nur mit `SOLVER_MOVE_LIMIT=0` |
| `SOLVER_SEED` | `1` | Startwert, wenn in der Oberfläche keiner eingetragen ist |
| `SOLVER_MOVE_LIMIT` | leer | Planung endet nach so vielen Zügen (0: aus). Leer: nach der Gründlichkeit unter Regeln (schnell 250000, normal 1000000, gründlich 3000000). Gleicher Startwert, gleiche Daten und gleiches Zuglimit ergeben denselben Plan, sofern die Planung vor dem Zeitlimit endet |
| `JAVA_OPTS` | `-XX:MaxRAMPercentage=75` | Speicher des Backends |
| `DISPOFOLD_UPDATE_CHECK` | `true` | Beim Start und einmal am Tag nach einer neuen Version suchen (siehe [Neue Version](#neue-version)); `false` schaltet das ab |

### Erstinstallation mit dem Archiv

```bash
mkdir -p /srv/dispofold && cd /srv/dispofold
# Release-Dateien hierher kopieren, dann:
sha256sum -c dispofold-1.0.0-SHA256SUMS.txt --ignore-missing
docker load -i dispofold-1.0.0-images.tar.gz
cp env.example .env
chmod 600 .env
nano .env          # TAG ist gesetzt; Geheimnisse und Benutzer eintragen (siehe oben)
docker compose up -d
docker compose ps  # alle Dienste "healthy"
```

Auf einem Server mit ARM-Prozessor (arm64) statt `dispofold-1.0.0-images.tar.gz` das Archiv
`dispofold-1.0.0-images-arm64.tar.gz` laden; die Images darin heißen gleich. `docker version` zeigt unter
`OS/Arch`, welche Plattform der Server hat.

Ist das Archiv im Release in Teile zerlegt (`...images.tar.gz.part-00`, `-01`, ...), vorher zusammensetzen:
`cat dispofold-1.0.0-images.tar.gz.part-* > dispofold-1.0.0-images.tar.gz`. Die SHA-256-Datei enthält dann das
ganze Archiv und die Teile.

Die Images im Archiv heißen `dispofold.invalid/dispofold-backend`, `dispofold.invalid/dispofold-frontend` und
`dispofold.invalid/dispofold-postgres`, jeweils mit der Version als Tag. Die `env.example` aus dem Release setzt dazu
`REGISTRY=dispofold.invalid` und `POSTGRES_IMAGE=dispofold.invalid/dispofold-postgres:<version>`; beides unverändert lassen.
Docker muss dann nichts nachladen.

### Erstinstallation aus der Registry

Die Images liegen öffentlich in der GitHub Container Registry unter `ghcr.io/mschabhuettl`; `docker compose pull`
braucht keine Anmeldung. Ohne Zugang zum Internet bleibt die Installation mit dem Archiv.

```bash
cd /srv/dispofold
# docker-compose.yml und env.example aus dem Release holen, .env anlegen wie oben,
# dazu REGISTRY=ghcr.io/mschabhuettl; POSTGRES_IMAGE weglassen (dann kommt PostgreSQL von Docker Hub)
docker compose pull
docker compose up -d
```

Die Registry hat die Images für amd64 und arm64 unter demselben Namen; Docker holt die des Servers.

### Reverse Proxy, HTTPS und Anmeldebegrenzung

Die Oberfläche spricht nur HTTP auf `HTTP_PORT`. Für den Zugriff im Netz gehört ein Reverse Proxy mit HTTPS
davor (zum Beispiel der vorhandene der IT). Er soll `X-Forwarded-Proto` und `X-Forwarded-For` setzen. Der Port
`HTTP_PORT` sollte dann nur für den Proxy erreichbar sein.

nginx im Container begrenzt Anmeldeversuche auf 10 je Minute (kurz bis zu 5 mehr) je Client-Adresse, danach
antwortet es mit `429`. Gezählt wird die Adresse, von der nginx die Verbindung bekommt. **Hinter einem Reverse
Proxy ist das die Adresse des Proxys.** Dann teilen sich alle Benutzer einen Zähler: Viele Fehlversuche einer
Person sperren die Anmeldung kurz für alle. Das ist so gewollt, solange der Proxy der einzige Client ist. Wer
eine Begrenzung je Benutzeradresse braucht, richtet sie im Proxy ein.

### Härtung

- `backend` und `frontend` laufen schreibgeschützt (`read_only`), ohne Linux-Capabilities (`cap_drop: [ALL]`),
  mit `no-new-privileges` und als unprivilegierter Benutzer (nginx in der Variante „unprivileged“). Beschreibbar
  sind nur `tmpfs`-Ordner.
- `db` ist das offizielle PostgreSQL-Image ohne diese Einschränkungen; es ist von außen nicht erreichbar.
- nginx setzt eine Content-Security-Policy, `X-Frame-Options: DENY` und `X-Content-Type-Options: nosniff`.
- Die Images sind an die Version (`TAG`) gebunden, die Basis-Images mit Digest.

### Erweiterungen

Erweiterungen (Plugins) sind Teil von Dispofold und kommen mit den Images; nachgeladen wird nichts. Jede ist aus, bis
die Planung sie unter **Einstellungen → Erweiterungen** einschaltet (siehe [Anleitung](anleitung/erweiterungen.md)).
Eine eingeschaltete Erweiterung sieht alle Planungsdaten. Zwei Einstellungen in `.env` gehören dem Betrieb:

- `DISPOFOLD_PLUGINS_HIDDEN`: die Kennungen der Erweiterungen, die es auf diesem Server nicht geben soll, durch
  Kommas getrennt. Eine ausgeblendete Erweiterung steht nicht in der Liste, lässt sich nicht einschalten, und jede
  ihrer Adressen antwortet mit 404, auch wenn sie vorher eingeschaltet war. Ihre gespeicherten Einstellungen
  bleiben in der Datenbank.
- `DISPOFOLD_PLUGIN_ROOTS`: die Ordner, die Erweiterungen lesen dürfen, als Pfade im Container, durch Kommas
  getrennt. Leer (Standard) heißt: keiner. In der Oberfläche sind nur diese Ordner und ihre Unterordner wählbar;
  absolute Pfade, `..` und symbolische Links, die hinausführen, weist der Server ab.

Ein Ordner kommt schreibgeschützt in den Container, am einfachsten mit einer `docker-compose.override.yml` neben
`docker-compose.yml` (Compose liest sie von selbst):

```yaml
services:
  backend:
    volumes:
      - /srv/dispofold-quellen:/quellen:ro
```

Dazu in `.env` `DISPOFOLD_PLUGIN_ROOTS=/quellen`, dann `docker compose up -d`. Der Container läuft als
unprivilegierter Benutzer: Die Dateien müssen für alle lesbar sein (`chmod -R o+rX /srv/dispofold-quellen`).
Der Ordner gehört nicht in die Sicherung von Dispofold; seine Daten bleiben, wo sie sind.

Die Erweiterung für den Import aus dem bisherigen Planungssystem liest dessen Datenordner, nur lesend. Der Ordner
(oder eine Kopie, die regelmäßig erneuert wird) wird genau so eingebunden und freigegeben; in der Erweiterung
wählt die Planung ihn dann aus. Was sie liest und wie man sie einstellt, steht auf ihrer
[Hilfeseite](anleitung/plugin-sp5-sp5.md).

### Neue Version

Das Backend fragt beim Start und danach einmal am Tag die öffentliche Releases-API von GitHub nach dem neuesten
Release: eine Anfrage ohne Anmeldung an
`https://api.github.com/repos/mschabhuettl/dispofold/releases/latest`, mit kurzem Zeitlimit. Gesendet wird nichts,
GitHub sieht nur die IP-Adresse des Servers. Vorabversionen zählen nicht. Ist das Release neuer als die laufende
Version, zeigt die Oberfläche der Rolle `planner` oben den Hinweis „Neue Version x.y.z verfügbar“. Er öffnet die
Notizen des Release, einen Link auf seine Seite und die Schritte unter [Update](#update). Ausgeblendet wird er je
Version (im jeweiligen Browser), bei der nächsten Version erscheint er wieder. Die Rolle `viewer` sieht ihn nie,
`GET /api/release` antwortet ihr mit `403`. Einspielen muss die IT das Update selbst, der Server aktualisiert sich
nie von allein.

- **Abschalten:** `DISPOFOLD_UPDATE_CHECK=false` in `.env` (in Portainer bei den Umgebungsvariablen des Stacks)
  setzen, dann `docker compose up -d`. Dann stellt der Server keine Verbindung ins Internet her.
- **Netz:** Die Abfrage braucht eine ausgehende HTTPS-Verbindung (Port 443) des Backend-Containers zu
  `api.github.com`. Viele IT-Abteilungen erlauben Servern keine ausgehenden Verbindungen. Dann schlägt die
  Abfrage fehl, das Backend schreibt nur eine Zeile ins Log (`Release check failed` oder `Release check: HTTP
  <status>`) und läuft sonst normal weiter. Wer den Hinweis nicht braucht, schaltet die Abfrage ab.

### Update

1. Datenbank sichern (siehe unten).
2. Neue Images bereitstellen: Archiv prüfen und `docker load -i ...`, oder nach Schritt 3 `docker compose pull`.
3. In `.env` `TAG` auf die neue Version setzen (in Portainer bei den Umgebungsvariablen des Stacks). Die
   `docker-compose.yml` aus dem Release übernehmen; neue Einträge aus `env.example` in die eigene `.env`
   übertragen.
4. `docker compose up -d` (in Portainer: den Stack neu bereitstellen). Das Backend spielt Datenbankänderungen beim
   Start selbst ein.
5. Prüfen: `docker compose ps` zeigt alle Dienste „healthy“, `docker compose logs backend` enthält die Zeile
   `dispofold-backend <neue Version> on JVM ... started` und keine Zeile mit `ERROR` (siehe
   [Protokolle](#protokolle)). Dann anmelden und einen Monat öffnen.
6. Alte Images erst entfernen, wenn die neue Version läuft. Bis dahin braucht ein Rückweg sie:
   `docker image rm dispofold.invalid/dispofold-backend:<alt> dispofold.invalid/dispofold-frontend:<alt>` (mit Registry deren
   Namen).

`docker compose up -d` legt die Container neu an. Damit sind auch ihre bisherigen Protokolle weg. Wer sie noch
braucht, sichert sie vor Schritt 4 (siehe [Protokolle](#protokolle)).

Zurück auf die vorige Version: `docker compose stop backend`, die Sicherung von vor dem Update einspielen (siehe
unten), `TAG` zurücksetzen, `docker compose up -d`. Die alte Version darf nicht auf der schon geänderten
Datenbank starten. Schritt für Schritt:

1. `docker compose stop frontend backend`
2. Die alten Images müssen vorhanden sein (`docker image ls | grep dispofold`); sonst das Archiv der alten Version
   wieder mit `docker load -i ...` laden oder mit Registry nach Schritt 4 `docker compose pull`.
3. Die Sicherung von vor dem Update einspielen:
   `docker compose exec -T db pg_restore -U dienstplanung -d dienstplanung --clean --if-exists < <sicherung>.dump`
4. In `.env` `TAG` auf die alte Version setzen und die `docker-compose.yml` der alten Version zurücklegen.
5. `docker compose up -d`, dann prüfen wie in Schritt 5 oben, mit der alten Version in der Startzeile.

Eingaben, die nach der Sicherung gemacht wurden, fehlen danach.

### Sicherung und Wiederherstellung

Die Befehle laufen im Ordner mit `docker-compose.yml`. `dienstplanung` ist der Standard für `DB_USER` und
`DB_NAME`; wer sie in `.env` geändert hat, setzt die eigenen Werte ein.

Sicherung (im laufenden Betrieb):

```bash
docker compose exec -T db pg_dump -U dienstplanung -Fc dienstplanung > dispofold-$(date +%F).dump
```

Wiederherstellung (überschreibt den Inhalt der Datenbank):

```bash
docker compose stop backend
docker compose exec -T db pg_restore -U dienstplanung -d dienstplanung --clean --if-exists < dispofold-2027-01-15.dump
docker compose start backend
```

Die Sicherungsdatei enthält Personaldaten: sicher ablegen. Die Datenbank liegt im Docker-Volume
`dispofold_db-data`.

Zu einer vollständigen Sicherung gehören neben dem Dump die Dateien `.env` (Geheimnisse und Benutzer) und
`docker-compose.yml`. Ohne `.env` lässt sich der Dump zwar einspielen, aber Benutzer und Schlüssel müssen neu
angelegt werden. `.env` enthält Geheimnisse: getrennt vom Dump und genauso geschützt ablegen.

Es gibt keine eingebaute regelmäßige Sicherung. Ein Beispiel für eine tägliche Sicherung um 2 Uhr mit cron
(Ordner und Aufbewahrung anpassen; in der crontab muss `%` als `\%` stehen):

```bash
0 2 * * * cd /srv/dispofold && docker compose exec -T db pg_dump -U dienstplanung -Fc dienstplanung > sicherungen/dispofold-$(date +\%F).dump && find sicherungen -name 'dispofold-*.dump' -mtime +30 -delete
```

Den Ordner `sicherungen` vorher anlegen (`mkdir -m 700 /srv/dispofold/sicherungen`) und die Dumps zusätzlich auf
ein anderes System kopieren: Eine Sicherung auf demselben Server hilft nicht, wenn der Server ausfällt.

### Wiederherstellung testen

Eine Sicherung ist erst brauchbar, wenn sie sich einspielen lässt. Der Test spielt sie in eine zweite, leere
Datenbank im selben Container ein. Die laufende Datenbank bleibt unberührt, die Oberfläche läuft weiter.

1. Die Datei lesen lassen. Die Liste der Einträge muss erscheinen, ohne Fehlermeldung:
   `docker compose exec -T db pg_restore --list < dispofold-2027-01-15.dump | head`
2. Eine Testdatenbank anlegen und die Sicherung hinein spielen:

   ```bash
   docker compose exec -T db createdb -U dienstplanung restoretest
   docker compose exec -T db pg_restore -U dienstplanung -d restoretest < dispofold-2027-01-15.dump
   ```

3. Inhalt vergleichen, einmal mit `-d restoretest`, einmal mit `-d dienstplanung`. Die Zahlen und der Stand
   der Datenbank sollen zum Zeitpunkt der Sicherung passen:

   ```bash
   docker compose exec -T db psql -U dienstplanung -d restoretest \
     -c "select version from flyway_schema_history order by installed_rank desc limit 1" -c "select count(*) from plan" -c "select count(*) from dataset"
   ```

4. Testdatenbank wieder löschen: `docker compose exec -T db dropdb -U dienstplanung restoretest`

Wer auch die Oberfläche mit der Sicherung prüfen will, spielt sie auf einem Testserver ein, wie unter
[Umzug auf einen neuen Server](#umzug-auf-einen-neuen-server) beschrieben. `scripts/smoke-test.sh` aus dem
Quellcode taugt dafür nicht: Es ersetzt die Stammdaten durch Demodaten.

### Umzug auf einen neuen Server

Auf dem neuen Server läuft zuerst dieselbe Version wie auf dem alten. Ein Update folgt erst, wenn der Umzug
geprüft ist.

1. **Neuen Server vorbereiten:** Docker installieren (siehe [Voraussetzungen](#voraussetzungen)), den Ordner
   anlegen (`mkdir -p /srv/dispofold`) und die Images derselben Version bereitstellen: Archiv prüfen und
   `docker load -i dispofold-<version>-images.tar.gz`; aus der Registry holt sie später `docker compose pull`. Die Version
   steht in `.env` des alten Servers unter `TAG`. Noch nichts starten.
2. **Alten Server anhalten und sichern:** Ab hier keine Eingaben mehr. Im Ordner mit `docker-compose.yml`:

   ```bash
   docker compose stop frontend backend
   docker compose exec -T db pg_dump -U dienstplanung -Fc dienstplanung > dispofold-umzug.dump
   ```

   Die Datenbank läuft weiter, damit `pg_dump` sie lesen kann; Backend und Oberfläche sind aus, damit sich danach
   nichts mehr ändert.
3. **Dateien kopieren:** `.env`, `docker-compose.yml` und den Dump auf den neuen Server, zum Beispiel mit
   `scp .env docker-compose.yml dispofold-umzug.dump neuer-server.example.org:/srv/dispofold/`. Dort
   `chmod 600 .env`. `.env` unverändert übernehmen: `DB_PASSWORD` wird beim ersten Start in die neue Datenbank
   übernommen, `APP_USERS` behält die Benutzer, und mit demselben `SESSION_ENCRYPTION_KEY` bleiben auch
   Anmeldungen gültig (mit einem neuen müssen sich alle neu anmelden). Nur `HTTP_PORT` und, wenn die Images jetzt
   aus einer anderen Quelle kommen, `REGISTRY` und `POSTGRES_IMAGE` anpassen.
4. **Nur die Datenbank starten und die Sicherung einspielen** (auf dem neuen Server, in `/srv/dispofold`):

   ```bash
   docker compose up -d --wait db
   docker compose exec -T db pg_restore -U dienstplanung -d dienstplanung --clean --if-exists < dispofold-umzug.dump
   docker compose up -d
   docker compose ps   # alle Dienste "healthy"
   ```

5. **Prüfen:** Der Abgleich aus [Wiederherstellung testen](#wiederherstellung-testen), Schritt 3, gibt auf beiden
   Servern dieselben Zahlen (mit `-d dienstplanung`). `docker compose logs backend` zeigt
   `Schema "public" is up to date` und keine Zeile mit `ERROR`. `curl -fsS http://localhost:8080/q/health/ready`
   (mit dem eigenen `HTTP_PORT`) antwortet mit `"status": "UP"`. Dann mit einem Benutzer anmelden und einen Monat
   öffnen.
6. **Umschalten:** Reverse Proxy oder DNS-Namen auf den neuen Server richten.
7. **Alten Server abbauen:** erst, wenn der neue eine Weile läuft. Bis dahin bleibt er angehalten, sein Volume ist
   der Rückweg. Dann `docker compose down` und, wenn die Daten nicht mehr gebraucht werden, `docker compose down -v`
   (siehe [Deinstallation](#deinstallation)). Dump und Kopien von `.env` auf dem Weg dorthin löschen oder sicher
   ablegen.

Eine Datei aus einer Sicherung lässt sich genauso auf einem neuen Server einspielen, etwa nach einem Ausfall: ab
Schritt 1, dann Schritt 3 mit `.env` und `docker-compose.yml` aus der Sicherung und Schritt 4 mit dem Dump.

### Protokolle

Jeder Dienst schreibt sein Protokoll auf die Konsole des Containers. Docker hebt es auf, solange es den Container
gibt; `docker compose down` oder ein neu angelegter Container (etwa beim Update) löscht es. `docker-compose.yml`
setzt keinen eigenen Logtreiber und keine Größengrenze, es gelten die Einstellungen von Docker auf dem Server
(`/etc/docker/daemon.json`). Die Befehle laufen im Ordner mit `docker-compose.yml`:

```bash
docker compose logs backend                  # alles vom Backend
docker compose logs --since 2h --timestamps backend frontend db
docker compose logs -f --tail 100 backend    # laufend mitlesen, Ende mit Ctrl+C
```

| Dienst | Inhalt | Worauf achten |
|---|---|---|
| `backend` | Start, Datenbankänderungen (Flyway), Planungen, Suche nach neuer Version. Format `Datum Zeit Stufe [Klasse] Meldung`, Zeit in UTC | `startup.<code>` mit `ERROR`: das Backend startet nicht (`sessionKeyMissing`, `sessionKeyPlaceholder`, `sessionKeyShort`, `dbPasswordMissing`, `dbPasswordPlaceholder`; siehe [Geheimnisse und Benutzer](#geheimnisse-und-benutzer)). `startup.noUsers`: `APP_USERS` ist leer. `APP_USERS: entry <n> ...`: Eintrag Nummer n ist falsch. `Solving <Monat> failed`: eine Planung ist abgebrochen. `Release check failed` oder `Release check: HTTP <status>`: siehe [Neue Version](#neue-version). Die Startzeile `dispofold-backend <version> on JVM ... started` nennt die laufende Version |
| `frontend` | ein Zugriff je Zeile (nginx): Adresse des Clients, Zeit, Anfrage, Status, Browser | Status `429` bei `j_security_check`: die Anmeldebegrenzung greift (siehe [Reverse Proxy, HTTPS und Anmeldebegrenzung](#reverse-proxy-https-und-anmeldebegrenzung)). `502` oder `504`: das Backend antwortet nicht, dann dessen Protokoll ansehen. Alle paar Sekunden `GET /login.html` von `127.0.0.1` ist der Gesundheitscheck |
| `db` | PostgreSQL | `password authentication failed`: `DB_PASSWORD` in `.env` passt nicht zur Datenbank. PostgreSQL übernimmt das Passwort nur beim ersten Start mit leerem Volume; ein später geänderter Wert in `.env` gilt dort nicht |

`docker compose ps` zeigt den Zustand der Gesundheitschecks. `http://<server>:<HTTP_PORT>/q/health/ready` ist auch
von außen erreichbar und meldet, ob das Backend die Datenbank erreicht.

**Protokolle für die Fehlersuche weitergeben:** Das Backend nennt in seinen Meldungen nur interne Ids und Codes,
keine Namen und keine Inhalte von Anfragen. Das Protokoll von `frontend` enthält dagegen die IP-Adressen der Clients
(hinter einem Proxy die des Proxys), Adressen der Anfragen und Browserkennungen. Deshalb nur den nötigen Zeitraum
weitergeben und IP-Adressen ersetzen:

```bash
docker compose logs --no-color --since 24h backend > backend.log
docker compose logs --no-color --since 24h frontend | sed -E 's/([0-9]{1,3}\.){3}[0-9]{1,3}/x.x.x.x/g' > frontend.log
```

Vor dem Versand jede Datei durchsehen. Meldungen von PostgreSQL (`db`) können bei Fehlern Werte aus der Datenbank
enthalten; sie nur weitergeben, wenn sie gebraucht werden, und dann genauso prüfen. `.env`, Dumps und Volumes
gehören nie dazu.

### Deinstallation

```bash
docker compose down       # Container weg, Volume mit den Daten bleibt
docker compose down -v    # löscht auch das Volume, also alle Daten
docker image rm dispofold.invalid/dispofold-backend:<version> dispofold.invalid/dispofold-frontend:<version>
```

## Desktop-Programm

### Gemeinsam für alle Systeme

- Das Programm bringt seine eigene Java-Laufzeit mit und braucht keinen Server. Es lädt zur Laufzeit nichts
  aus dem Netz, solange keine Update-Quelle eingetragen ist. Ob es nach neuen Versionen sucht, fragt es einmal
  beim ersten Start (siehe [Updates](#updates)).
- Die Oberfläche läuft im Programmfenster. Unter Linux öffnet sie sich standardmäßig im Standardbrowser (siehe
  Linux).
- **Passwort:** Beim ersten Start legt man ein Passwort fest (mindestens 12 Zeichen). Damit ist die Datenbank
  verschlüsselt. **Wer das Passwort verliert, verliert die Daten.** Es gibt keine Hintertür und keine
  Wiederherstellung, auch nicht durch die IT.
- **Nur ein Start je Datenordner:** Ein zweiter Start holt das offene Fenster nach vorn.
- Eine Anmeldung mit Benutzer und Rolle gibt es nicht: Wer das Passwort kennt, hat die Rolle Planung.

**Wo die Daten liegen** (im Basisordner liegen `daten\` mit der Datenbank, Einstellungen, Sicherungen und Logs
und `versionen\` mit eingespielten Updates):

| Variante | Basisordner |
|---|---|
| Windows-Setup | `%LOCALAPPDATA%\Dispofold` (derselbe Ordner wie das Programm) |
| Windows portabel | der entpackte Programmordner (Datei `PORTABLE` darin) |
| Linux | `~/.local/share/dispofold` (bzw. `$XDG_DATA_HOME/dispofold`) |
| macOS | `~/Library/Application Support/Dispofold` |

Daneben legt das Programmfenster einen Ordner für den Zwischenspeicher der Webansicht an (unter Windows
`%LOCALAPPDATA%\app.dispofold`). Er enthält keine Planungsdaten.

**Datenordner auf einem Netzlaufwerk: davon raten wir ab.** Die Datenbank (H2) ist eine einzelne Datei. Über
SMB oder NFS kann sie bei Verbindungsabbrüchen oder durch Zwischenspeicher beschädigt werden. Liegt der
Datenordner auf einem Netzlaufwerk, einem UNC-Pfad oder einer Netzwerkfreigabe, warnt das Programm bei jedem
Start, startet aber trotzdem. Das portable ZIP gehört deshalb auf ein lokales Laufwerk.

### Windows: Setup

- **Installieren:** `Dispofold-<version>-windows-setup.exe` ausführen. Das Setup installiert nur für den
  angemeldeten Benutzer, standardmäßig nach `%LOCALAPPDATA%\Dispofold`, und braucht keine Adminrechte. Die
  Daten liegen immer in `%LOCALAPPDATA%\Dispofold`, auch wenn im Setup ein anderer Programmordner gewählt
  wird. Startmenü-Eintrag:
  Dispofold.
- **WebView2:** Das Programmfenster braucht die WebView2-Runtime von Microsoft. Unter Windows 10 und 11 ist sie
  meist vorhanden. Fehlt sie, lädt das Setup sie von Microsoft herunter. Rechner ohne Internetzugang brauchen
  vorher Microsofts eigenständigen WebView2-Installer („Evergreen Standalone Installer“), verteilt wie andere
  Software auch.
- **Update:** siehe [Updates](#updates). Für eine neue Java-Laufzeit oder ein neues Programmfenster das neue
  Setup über die bestehende Installation ausführen. Die Daten bleiben.
- **Deinstallieren:** Einstellungen, Apps, Dispofold, Deinstallieren. Das Kästchen zum Löschen der App-Daten
  löscht nur den Zwischenspeicher der Webansicht. Die Ordner `daten\` und `versionen\` in
  `%LOCALAPPDATA%\Dispofold` bleiben. Wer auch die Daten löschen will, löscht danach diesen Ordner.

### Windows: portables ZIP

- **Installieren:** Vor dem Entpacken die Sperre aufheben (siehe [Unsignierte Programme](#unsignierte-programme)),
  dann `Dispofold-<version>-windows-portable.zip` auf ein lokales Laufwerk entpacken und
  `Dispofold\dispofold.exe` starten. Der Ordner muss beschreibbar sein: Die Daten liegen in `daten\` daneben.
  Das ZIP installiert WebView2 nicht. Fehlt sie (selten unter Windows 10 und 11), vorher Microsofts
  eigenständigen WebView2-Installer ausführen.
- **Update:** siehe [Updates](#updates). Für eine neue Laufzeit: Programm beenden, das neue ZIP in einen neuen
  Ordner entpacken und die Ordner `daten\` und `versionen\` aus dem alten Ordner hinein verschieben.
- **Deinstallieren:** Programm beenden, Ordner löschen. Damit sind auch die Daten weg.

### Linux: .deb und .rpm

- **Installieren:** `sudo apt install ./Dispofold-<version>-linux-amd64.deb` oder
  `sudo dnf install ./Dispofold-<version>-linux-x86_64.rpm`; auf einem Rechner mit ARM-Prozessor (arm64,
  `uname -m` zeigt `aarch64`) `sudo apt install ./Dispofold-<version>-linux-arm64.deb` oder
  `sudo dnf install ./Dispofold-<version>-linux-aarch64.rpm`. Das Paket heißt `dispofold`, das Programm liegt in
  `/usr/lib/Dispofold`, der Befehl ist `dispofold`, dazu ein Eintrag im Anwendungsmenü. Voraussetzungen: GTK 3
  und WebKitGTK 4.1 (zieht der Paketmanager nach).
- **Oberfläche:** Standardmäßig öffnet sich die Oberfläche im Standardbrowser, weil die eingebaute Webansicht
  (WebKitGTK) ohne Grafikbeschleunigung zu langsam ist. Ein kleines Fenster „Dispofold läuft“ hält das Programm
  in Betrieb. Der Browser bekommt einen einmaligen Startlink über eine Datei, die nur der Benutzer lesen kann,
  und erreicht das Programm nur über `127.0.0.1`. `dispofold --window` öffnet stattdessen das Programmfenster.
- **Update:** siehe [Updates](#updates). Für eine neue Laufzeit das neue Paket genauso installieren.
- **Deinstallieren:** `sudo apt remove dispofold` oder `sudo dnf remove dispofold`. Die Daten in
  `~/.local/share/dispofold` bleiben.

### macOS: .dmg

Für Macs mit Apple Silicon (M-Prozessor). Ältere Macs mit Intel-Prozessor werden nicht unterstützt.

- **Installieren:** dmg öffnen und Dispofold in den Ordner „Programme“ ziehen. Erst von dort starten. Startet
  man direkt aus dem dmg oder aus „Downloads“, kopiert macOS das Programm in einen versteckten, schreibgeschützten
  Ordner (App Translocation). Dispofold erkennt das, startet nicht und erklärt, dass es nach „Programme“
  gehört.
- **Update:** siehe [Updates](#updates). Für eine neue Laufzeit das neue dmg genauso installieren.
- **Deinstallieren:** Dispofold aus „Programme“ in den Papierkorb ziehen. Die Daten in
  `~/Library/Application Support/Dispofold` bleiben.

### Updates

Ein Update (`dispofold-<version>-update.zip`) ersetzt die Anwendung, nicht die Daten. Es gilt für alle
Systeme.

- **Erster Start:** Sind noch keine Update-Einstellungen gespeichert (neue Installation oder erstes Update auf
  eine Version mit dieser Frage), fragt das Programm einmal: „Soll Dispofold automatisch nach neuen Versionen
  suchen?“ (**Ja** ist vorausgewählt, **Nein**). **Ja** trägt die Releases-API von GitHub
  (`https://api.github.com/repos/mschabhuettl/dispofold/releases/latest`) als Quelle ein und schaltet die Suche
  beim Start ein. **Nein** lässt die Quelle leer. Vor der Antwort geht keine Anfrage ins Netz. Wer schon eine
  Quelle eingetragen oder die Einstellungen einmal gespeichert hat, wird nicht gefragt; eine eingetragene Adresse
  oder ein Ordner bleibt unverändert.
- Unter **Einstellungen > Updates** lässt sich das jederzeit ändern: die **Update-Quelle** (die Adresse oben, eine
  andere Releases-API oder ein Ordner, etwa eine Netzwerkfreigabe, in den die IT die Update-Dateien legt) und
  **Beim Start nach Updates suchen**. Eine leere Quelle schaltet die Suche ganz ab. Das Programm schickt keine
  Anmeldedaten mit: Die Releases müssen ohne Anmeldung lesbar sein. Mit einer Quelle sucht das Programm beim Start
  (wenn eingeschaltet) oder mit **Jetzt suchen**. Gesendet wird dabei nichts, die Quelle sieht nur die IP-Adresse.
- Findet es eine neuere Version, erscheint oben der Hinweis „Neue Version x.y.z verfügbar“ mit den Notizen des
  Release und **Jetzt installieren**. Er lässt sich je Version ausblenden.
- **Ohne Quelle** greift das Programm nie aufs Netz zu und arbeitet vollständig offline.
- **Update aus Datei einspielen** nimmt eine Update-Datei direkt, für Rechner ohne Netz.
- Jedes Update ist signiert. Ein verändertes oder unsigniertes Update wird abgelehnt, ebenso eine gleiche oder
  ältere Version.
- Vor dem ersten Start einer neuen Version sichert das Programm die Datenbank. Wird die neue Version nicht
  innerhalb von 3 Minuten bereit, kehrt es mit dieser Sicherung zur vorigen Version zurück und meldet das.
- Java-Laufzeit, Launcher und Programmfenster werden nur mit einem neuen Setup, ZIP, Paket oder dmg erneuert.
- **Zurück zur vorigen Version von Hand:** Ein Update lässt sich nicht mit einer älteren Update-Datei
  zurücknehmen, ältere Versionen lehnt das Programm ab. Wer zurück will, weil die neue Version zwar startet, aber
  nicht wie gewünscht arbeitet, braucht eine Kopie von `daten` und `versionen` von vor dem Update (siehe den
  nächsten Abschnitt): Programm beenden, beide Ordner durch die
  Kopie ersetzen, starten. Kam die neue Version mit einem neuen Setup, ZIP, Paket oder dmg, vorher auch die vorige
  Version davon wieder installieren, denn eine neuere mitgelieferte Version gewinnt. Eingaben seit der Kopie fehlen
  danach. Vor einem Update deshalb beide Ordner kopieren.

### Sicherung und Wiederherstellung

- **Automatisch:** Nur vor dem ersten Start einer neuen Version kopiert das Programm die Datenbank nach
  `daten/sicherungen/vor-<version>-<zeit>/`. Nach einem gescheiterten Update liegt die Datenbank des Fehlstarts
  unter `daten/sicherungen/fehlgeschlagen-<version>-<zeit>/`. Von jeder Art bleiben die neuesten 5. Eine andere Zahl
  stellt der Programmstart mit `--keep-backups <n>` ein (zum Beispiel `dispofold --keep-backups 10`, unter
  Windows in der Verknüpfung). Es gibt keine regelmäßige automatische Sicherung.
- **Regelmäßig sichern:** die Ordner `daten` und `versionen` im Basisordner (siehe Tabelle oben) kopieren,
  **nur während das Programm geschlossen ist.** Eine Kopie im laufenden Betrieb kann unbrauchbar sein. Die
  Kopie ist mit dem Passwort verschlüsselt, das beim Kopieren galt.
- **Wiederherstellen:** Programm beenden. `daten` und `versionen` durch die Kopie ersetzen. Oder nur die
  Datenbank: `daten/db` umbenennen (zum Beispiel in `db.defekt`) und einen Ordner aus `daten/sicherungen/` als
  `daten/db` hineinkopieren (er enthält `dispofold.mv.db` und `dispofold.salt`). Dann starten und das Passwort
  eingeben, das zur Zeit der Sicherung galt. Eine Sicherung aus einer älteren Version bringt die neue Version
  beim Start selbst auf den aktuellen Stand.
- **Wiederherstellung testen:** Das Programm lässt sich mit einem anderen Basisordner starten
  (`--data <Ordner>`), so bleiben die echten Daten unberührt. Dispofold vorher beenden: Ein zweiter Start, auch mit
  `--data`, holt nur das offene Fenster nach vorn. Die Kopie von `daten` und `versionen` in
  einen leeren Testordner legen, dann mit diesem Ordner starten:

  ```
  Windows (Setup):   "%LOCALAPPDATA%\Dispofold\dispofold.exe" --data C:\Temp\dispofold-test
  Windows (ZIP):     <Ordner>\dispofold.exe --data C:\Temp\dispofold-test
  Linux:             dispofold --data ~/dispofold-test
  macOS:             /Applications/Dispofold.app/Contents/MacOS/dispofold --data ~/dispofold-test
  ```

  Das Passwort zur Zeit der Sicherung eingeben, einen Monat öffnen und die Daten ansehen. Danach Dispofold beenden
  und den Testordner löschen; er enthält Personaldaten.

### Umzug auf einen neuen Rechner

Die Daten hängen nicht am Rechner: Die Ordner `daten` und `versionen` lassen sich auf einen anderen Rechner,
auch mit einem anderen Betriebssystem oder von der portablen in die installierte Variante, übertragen. Das
Passwort bleibt dasselbe.

1. **Alter Rechner:** Dispofold beenden. Die Version steht in `versionen/state.json` unter `lastStarted`.
2. **Neuer Rechner:** Dispofold in dieser oder einer neueren Version installieren (siehe die Abschnitte oben).
   Eine ältere Version startet auf diesen Daten nicht. Möglichst noch nicht starten. Wurde es schon gestartet,
   wieder beenden; was dabei angelegt wurde, wird im nächsten Schritt ersetzt.
3. **Kopieren:** `daten` und `versionen` aus dem Basisordner des alten Rechners in den Basisordner des neuen
   kopieren (Tabelle unter [Gemeinsam für alle Systeme](#gemeinsam-für-alle-systeme)) und dort vorhandene Ordner
   gleichen Namens ersetzen. Unterwegs, etwa auf einem USB-Stick, ist die Datenbank mit dem Passwort
   verschlüsselt; `daten/logs` und `daten/einstellungen.json` sind es nicht.
4. **Prüfen:** Dispofold starten, das bisherige Passwort eingeben, einen Monat öffnen. Die Update-Einstellungen
   kommen in `daten/einstellungen.json` mit.
5. **Alter Rechner:** erst, wenn der neue läuft, Dispofold dort deinstallieren und die Ordner löschen (siehe die
   Abschnitte oben). Die Kopie auf dem Transportmedium ebenfalls löschen.

### Protokolle des Desktop-Programms

Die App schreibt ihr Protokoll in `daten/logs/app-<version>.log`, eine Datei je Version. Neue Zeilen kommen ans
Ende, das Programm kürzt oder löscht die Dateien nicht.

| Variante | Ordner der Protokolle |
|---|---|
| Windows-Setup | `%LOCALAPPDATA%\Dispofold\daten\logs` |
| Windows portabel | `<Programmordner>\daten\logs` |
| Linux | `~/.local/share/dispofold/daten/logs` (bzw. `$XDG_DATA_HOME/dispofold/daten/logs`) |
| macOS | `~/Library/Application Support/Dispofold/daten/logs` |

Das Format ist dasselbe wie beim Server: `Datum Zeit Stufe [Klasse] Meldung`. Worauf achten: Zeilen mit `ERROR`,
`Solving <Monat> failed` (eine Planung ist abgebrochen), `Update <version> staged` (ein Update wurde abgelegt),
`versionen/state.json is unreadable` und `daten/einstellungen.json is unreadable, using defaults`. Meldet das
Programm einen Absturz, einen gescheiterten Start oder ein zurückgenommenes Update, nennt die Meldung die Datei,
zum Beispiel `daten/logs/app-1.2.0.log`.

Programmfenster und Launcher schreiben keine eigene Datei. Ihre Meldungen gehen auf die Standardfehlerausgabe und
sind nur zu sehen, wenn man das Programm in einem Terminal startet: unter Linux `dispofold`, unter macOS
`/Applications/Dispofold.app/Contents/MacOS/dispofold`. Unter Windows gibt es dafür keine Konsole.

**Protokolle für die Fehlersuche weitergeben:** Die App nennt in ihren Meldungen nur interne Ids und Codes,
keine Namen. Weitergeben nur die Datei der betroffenen Version, nie die Datenbank oder Ordner aus
`daten/sicherungen`. Vorher durchsehen; kommt doch ein Pfad mit dem Benutzernamen des Rechners vor (etwa
`C:\Users\<name>\...` oder `/home/<name>/...`), diesen Teil ersetzen.

### Passwort ändern

Programm beenden, dann den Launcher mit der mitgelieferten Java-Laufzeit aufrufen. Er fragt das bisherige und
zweimal das neue Passwort ab (mindestens 12 Zeichen). Ältere Sicherungen bleiben mit dem alten Passwort
verschlüsselt.

```
Windows (Setup):   "%LOCALAPPDATA%\Dispofold\runtime\bin\java.exe" -jar "%LOCALAPPDATA%\Dispofold\app\dispofold-launcher.jar" --change-password --program-dir "%LOCALAPPDATA%\Dispofold"
Windows (ZIP):     <Ordner>\runtime\bin\java.exe -jar <Ordner>\app\dispofold-launcher.jar --change-password --program-dir <Ordner>
Linux:             /usr/lib/Dispofold/runtime/bin/java -jar /usr/lib/Dispofold/app/dispofold-launcher.jar --change-password
macOS:             /Applications/Dispofold.app/Contents/Resources/runtime/bin/java -jar /Applications/Dispofold.app/Contents/Resources/app/dispofold-launcher.jar --change-password
```

`--program-dir` ist beim ZIP nötig, damit der Launcher den portablen Datenordner findet. Wurde das Setup in
einen anderen Ordner installiert, die Pfade entsprechend anpassen. Ohne Bildschirm nimmt er
die Passwörter aus den Umgebungsvariablen `DISPOFOLD_PASSWORD` (bisher) und `DISPOFOLD_NEW_PASSWORD` (neu).

### Unsignierte Programme

Setup, ZIP und dmg sind nicht mit einem Code-Signing-Zertifikat signiert. Die Betriebssysteme warnen deshalb beim
ersten Start. Die Echtheit lässt sich mit den SHA-256-Prüfsummen aus dem Release prüfen. Die Update-Pakete sind
davon unabhängig mit einem eigenen Schlüssel signiert und werden immer geprüft.

Eine Signatur der Windows-Dateien über die SignPath Foundation gibt es derzeit nicht: Die Foundation hat den
ersten Antrag im November 2026 abgelehnt, weil das Projekt noch jung und wenig bekannt ist. Ein neuer Antrag ist
geplant, sobald das Projekt bekannter ist (siehe [Code signing policy](code-signing-policy.md)). Bis dahin die
Prüfsummen vergleichen: unter Windows `Get-FileHash -Algorithm SHA256 .\Dispofold-<version>-windows-setup.exe`
mit der Zeile in `dispofold-<version>-windows-SHA256SUMS.txt`, unter Linux und macOS
`sha256sum -c --ignore-missing dispofold-<version>-windows-SHA256SUMS.txt` im Download-Ordner. Sobald eine
Signatur gilt, steht das in den Notizen des Release, und
signiert sind dann das Setup und `Dispofold\dispofold.exe` im ZIP. Das vom Setup installierte `dispofold.exe` und
das Deinstallationsprogramm bleiben unsigniert; `runtime\bin\java.exe`, `runtime\bin\javaw.exe` und die DLLs der
Java-Laufzeit sind schon heute von Eclipse Adoptium oder Microsoft signiert. Prüfen lässt sich die Signatur so:

- **Explorer:** rechte Maustaste auf die Datei, **Eigenschaften**, Registerkarte **Digitale Signaturen**. Dort
  steht „SignPath Foundation“; **Details** zeigt, ob die Signatur gültig ist. Fehlt die Registerkarte, ist die
  Datei nicht signiert.
- **PowerShell:** `Get-AuthenticodeSignature .\Dispofold-<version>-windows-setup.exe | Format-List`. Erwartet:
  `Status` ist `Valid`, `SignerCertificate` nennt im Subject die SignPath Foundation. Für das ZIP erst entpacken
  und dann `Get-AuthenticodeSignature .\Dispofold\dispofold.exe` prüfen.
- Mit gültiger Signatur kann die IT das Setup und das Programm aus dem ZIP auch über eine Herausgeberregel
  (Zertifikat der SignPath Foundation, Produktname „Dispofold“) freigeben. Für das vom Setup installierte
  Programm bleibt es bei den Regeln unten.

Für Benutzerinnen und Benutzer:

- **Windows-Setup (SmartScreen):** „Der Computer wurde durch Windows geschützt“. Auf **Weitere Informationen**
  klicken, dann **Trotzdem ausführen**.
- **Windows-ZIP:** Vor dem Entpacken mit der rechten Maustaste auf die ZIP-Datei, **Eigenschaften**, unten
  **Zulassen** anhaken, **OK**. Erst dann entpacken. Sonst warnt Windows bei jeder Datei.
- **Kopie von einer Netzwerkfreigabe:** Wer Setup oder ZIP von einer Freigabe im eigenen Netz kopiert statt aus
  dem Browser herunterlädt, sieht in der Regel keine Warnung, weil die Datei keine Herkunftsmarke aus dem
  Internet trägt.
- **macOS (Gatekeeper):** „Dispofold kann nicht geöffnet werden, da der Entwickler nicht verifiziert werden
  kann“. Im Finder mit gedrückter Ctrl-Taste auf Dispofold klicken, **Öffnen** wählen und bestätigen. Bietet
  macOS das nicht an: **Systemeinstellungen**, **Datenschutz & Sicherheit**, **Dennoch öffnen**. Vorher
  Dispofold nach „Programme“ ziehen (siehe macOS).
- **Linux:** keine Warnung.

Die Prüfsummen der übrigen Dateien stehen in `dispofold-<version>-linux-SHA256SUMS.txt`,
`dispofold-<version>-linux-arm64-SHA256SUMS.txt`, `dispofold-<version>-macos-SHA256SUMS.txt` und (Server)
`dispofold-<version>-SHA256SUMS.txt`; geprüft wird genauso.

Für die IT (Freigabe per Richtlinie, zum Beispiel mit AppLocker oder App Control for Business):

- **Regel über den Dateihash:** Die SHA-256-Werte von Setup und ZIP stehen in
  `dispofold-<version>-windows-SHA256SUMS.txt` des Releases. Sie gelten für diese Dateien, nicht für die
  installierten Programmdateien (`dispofold.exe`, `runtime\bin\java.exe`, `runtime\bin\javaw.exe`). Für deren
  Hashes das Setup einmal auf einem Testrechner installieren und die Hashes dort bilden
  (`Get-FileHash -Algorithm SHA256`). Hash-Regeln müssen mit jeder Version erneuert werden.
- **Regel über den Pfad:** einfacher und versionsunabhängig: den Programmordner freigeben, also den Ordner
  `Dispofold` unter `AppData\Local` im Benutzerprofil, oder einen festen Ordner für das ZIP. Wie der Pfad
  geschrieben wird (Variablen, Platzhalter), hängt vom Richtlinienwerkzeug ab. Der Pfad muss dann vor Schreibzugriffen anderer geschützt sein. Bei der
  Installation im Benutzerprofil kann der Benutzer selbst dort schreiben; das ist bei der Abwägung zu
  berücksichtigen.
- Neben `dispofold.exe` startet das Programm `runtime\bin\javaw.exe`. Beide brauchen die Freigabe.
