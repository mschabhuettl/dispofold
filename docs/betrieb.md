# Betrieb (für die IT)

Dieses Dokument beschreibt Installation, Update, Sicherung, Wiederherstellung und Deinstallation jeder
Auslieferung. Alle Dateien kommen aus dem Release einer Version auf GitHub
(<https://github.com/mschabhuettl/dispofold/releases>), zusammen mit SHA-256-Prüfsummen
(`dispofold-<version>-SHA256SUMS.txt` für die Images, `dispofold-<version>-linux-SHA256SUMS.txt`,
`-windows-SHA256SUMS.txt` und, falls gebaut, `-macos-SHA256SUMS.txt`). Solange das Repository privat ist, braucht
der Download ein GitHub-Konto mit Lesezugriff, den der Eigentümer einrichtet.

| Auslieferung | Dateien im Release | Für | Daten |
|---|---|---|---|
| Server (Docker) | `dispofold-<version>-images.tar.gz`, `docker-compose.yml`, `env.example`; Images auch in der Registry `ghcr.io/mschabhuettl` | mehrere Planerinnen und Planer im Netz | PostgreSQL im Docker-Volume |
| Windows-Setup | `Dispofold-<version>-windows-setup.exe` | ein Arbeitsplatz, ohne Adminrechte | verschlüsselte Datei im Benutzerprofil |
| Windows portabel | `Dispofold-<version>-windows-portable.zip` | ohne Installation | verschlüsselte Datei im Ordner `daten\` neben dem Programm |
| Linux | `Dispofold-<version>-linux-amd64.deb`, `Dispofold-<version>-linux-x86_64.rpm` | ein Arbeitsplatz | verschlüsselte Datei im Benutzerordner |
| macOS | `Dispofold-<version>-macos.dmg` (nur, wenn ein macOS-Runner eingetragen ist) | ein Arbeitsplatz | verschlüsselte Datei im Benutzerordner |
| Update für alle Desktop-Varianten | `dispofold-<version>-update.zip` (signiert) | Updates ohne neuen Installer | |

Die Bedienung für die Dienstplanung steht in [anleitung.md](anleitung.md), technische Einzelheiten der
Desktop-Version in [desktop.md](desktop.md).

## Server mit Docker

### Voraussetzungen

- Linux mit Docker Engine 24 oder neuer und dem Compose-Plugin (`docker compose`).
- 2 CPU-Kerne und 4 GB RAM. Der Solver rechnet mit einem Kern je laufender Planung.
- Etwa 3 GB Plattenplatz für die Images.
- Zur Laufzeit gibt es keine Aufrufe ins Internet. Mit dem Archiv braucht der Server auch für die Installation
  keinen Internetzugang: Es enthält alle drei Images.

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
| `SOLVER_TIME_LIMIT_SECONDS` | `300` | Zeitlimit einer Planung als Sicherheitsnetz, wenn in der Oberfläche keines eingetragen ist |
| `SOLVER_UNIMPROVED_SECONDS` | `30` | Planung endet früher, wenn sich das Ergebnis so lange nicht verbessert. Wirkt nur mit `SOLVER_MOVE_LIMIT=0` |
| `SOLVER_SEED` | `1` | Startwert, wenn in der Oberfläche keiner eingetragen ist |
| `SOLVER_MOVE_LIMIT` | `250000` | Planung endet nach so vielen Zügen (0: aus). Gleicher Startwert, gleiche Daten und gleiches Zuglimit ergeben denselben Plan, sofern die Planung vor dem Zeitlimit endet |
| `JAVA_OPTS` | `-XX:MaxRAMPercentage=75` | Speicher des Backends |

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

Ist das Archiv im Release in Teile zerlegt (`...images.tar.gz.part-00`, `-01`, ...), vorher zusammensetzen:
`cat dispofold-1.0.0-images.tar.gz.part-* > dispofold-1.0.0-images.tar.gz`. Die SHA-256-Datei enthält dann das
ganze Archiv und die Teile.

Die Images im Archiv heißen `dispofold.invalid/dispofold-backend`, `dispofold.invalid/dispofold-frontend` und
`dispofold.invalid/dispofold-postgres`, jeweils mit der Version als Tag. Die `env.example` aus dem Release setzt dazu
`REGISTRY=dispofold.invalid` und `POSTGRES_IMAGE=dispofold.invalid/dispofold-postgres:<version>`; beides unverändert lassen.
Docker muss dann nichts nachladen.

### Erstinstallation aus der Registry

Die Images liegen in der GitHub Container Registry unter `ghcr.io/mschabhuettl`. Solange die Pakete privat
sind, braucht die Anmeldung ein GitHub-Konto mit Zugriff und einen Token (classic) mit dem Recht
`read:packages`; beides stellt der Eigentümer aus. Ohne Zugang bleibt die Installation mit dem Archiv.

```bash
docker login ghcr.io -u <github-konto>   # Passwort: der Token
cd /srv/dispofold
# docker-compose.yml und env.example aus dem Release holen, .env anlegen wie oben,
# dazu REGISTRY=ghcr.io/mschabhuettl; POSTGRES_IMAGE weglassen (dann kommt PostgreSQL von Docker Hub)
docker compose pull
docker compose up -d
```

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

### Update

1. Datenbank sichern (siehe unten).
2. Neue Images bereitstellen: Archiv prüfen und `docker load -i ...`, oder nach Schritt 3 `docker compose pull`.
3. In `.env` `TAG` auf die neue Version setzen. Die `docker-compose.yml` aus dem Release übernehmen; neue
   Einträge aus `env.example` in die eigene `.env` übertragen.
4. `docker compose up -d`. Das Backend spielt Datenbankänderungen beim Start selbst ein.
5. Alte Images entfernen:
   `docker image rm dispofold.invalid/dispofold-backend:<alt> dispofold.invalid/dispofold-frontend:<alt>` (mit Registry deren
   Namen).

Zurück auf die vorige Version: `docker compose stop backend`, die Sicherung von vor dem Update einspielen (siehe
unten), `TAG` zurücksetzen, `docker compose up -d`. Die alte Version darf nicht auf der schon geänderten
Datenbank starten.

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

### Deinstallation

```bash
docker compose down       # Container weg, Volume mit den Daten bleibt
docker compose down -v    # löscht auch das Volume, also alle Daten
docker image rm dispofold.invalid/dispofold-backend:<version> dispofold.invalid/dispofold-frontend:<version>
```

## Desktop-Programm

### Gemeinsam für alle Systeme

- Das Programm bringt seine eigene Java-Laufzeit mit und braucht keinen Server. Es lädt zur Laufzeit nichts
  aus dem Netz, solange keine Update-Quelle eingetragen ist.
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
  `sudo dnf install ./Dispofold-<version>-linux-x86_64.rpm`. Das Paket heißt `dispofold`, das Programm liegt in
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

Gibt es nur, wenn der Eigentümer einen macOS-Runner eingetragen hat (siehe [release.md](release.md)).

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

- Unter **Einstellungen** trägt man eine **Update-Quelle** ein: die Adresse der Releases-API von GitHub
  (`https://api.github.com/repos/mschabhuettl/dispofold/releases/latest`) oder einen Ordner, etwa eine
  Netzwerkfreigabe, in den die IT die Update-Dateien legt. Das Programm schickt keine Anmeldedaten mit: Die
  Releases müssen ohne Anmeldung lesbar sein. Solange das Repository privat ist, geht das nicht; dann einen
  Ordner verwenden. Mit einer Quelle sucht das Programm beim Start (wenn
  eingeschaltet) oder mit **Jetzt suchen** und fragt vor dem Einspielen.
- **Voreingestellt ist keine Quelle.** Dann greift das Programm nie aufs Netz zu und arbeitet vollständig
  offline.
- **Update aus Datei einspielen** nimmt eine Update-Datei direkt, für Rechner ohne Netz.
- Jedes Update ist signiert. Ein verändertes oder unsigniertes Update wird abgelehnt, ebenso eine gleiche oder
  ältere Version.
- Vor dem ersten Start einer neuen Version sichert das Programm die Datenbank. Wird die neue Version nicht
  innerhalb von 3 Minuten bereit, kehrt es mit dieser Sicherung zur vorigen Version zurück und meldet das.
- Java-Laufzeit, Launcher und Programmfenster werden nur mit einem neuen Setup, ZIP, Paket oder dmg erneuert.

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
