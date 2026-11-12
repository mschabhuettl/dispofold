# Desktop-Modus (Installer und portabel)

Dispofold läuft auch als Programm auf einem einzelnen Rechner, ohne Server und ohne Docker. Das Programm besteht
aus dem **Programmfenster** (Tauri, `desktop-shell/`), dem **Launcher** (`dispofold-launcher.jar`, reines Java
ohne Abhängigkeiten), der mitgelieferten **App** (die Quarkus-App aus dem Desktop-Build) und einer gebündelten
Java-Laufzeit. Die Daten liegen verschlüsselt in einer H2-Datenbankdatei.

Wo die Oberfläche erscheint:

- **Windows und macOS:** immer im Programmfenster (WebView2 bzw. WKWebView).
- **Linux:** standardmäßig im Standardbrowser des Benutzers (Chromium-Browser im App-Modus, sonst ein normales
  Fenster), weil WebKitGTK ohne Grafikbeschleunigung nicht flüssig läuft. Das Programm öffnet dazu einen Proxy auf
  `http://<zufälliger Name>.localhost:<port>` (nur Loopback, `127.0.0.1` und `[::1]` auf demselben Port) mit einem
  eigenen Sitzungs-Cookie und einem einmaligen Startcode; ein kleines Fenster "Dispofold läuft" bietet "Öffnen" und
  "Beenden", sein Schließen beendet Dispofold ebenfalls. Mit `--window`
  erscheint die Oberfläche auch unter Linux im Programmfenster. Einzelheiten: `desktop-shell/`.

In beiden Fällen ist das Programm der einzige Client der App: dasselbe Zeilenprotokoll, derselbe Beweis, dieselbe
Challenge und derselbe Header `X-Dispofold-Session` (siehe unten). Der Browser bekommt das Geheimnis der App nie
zu sehen; sein Cookie gilt nur für den Proxy des Programms.

## Zwei Builds aus einer Quelle

| | Server (Docker) | Desktop |
|---|---|---|
| Build | `./mvnw package` | `./mvnw package -Dquarkus.profile=desktop` |
| Datenbank | PostgreSQL | H2-Datei `daten/db/dispofold.mv.db`, `CIPHER=AES` |
| Anmeldung | Formular, Benutzer aus `APP_USERS` | Header `X-Dispofold-Session`, gesetzt vom Programm, Benutzer `lokal`, Rolle `planner` |
| Netzwerk | alle Adressen, `X-Forwarded-*` vertraut | nur `127.0.0.1`, keine Weiterleitungs-Header |
| `/api/me` | `"desktop": false` | `"desktop": true` |

Die Desktop-Beans (`app.dispofold.desktop.DesktopAuth`, `ChallengeResource`, `ReadyLine`, `BodyLimit`,
`DesktopResource`, `UpdateService`, `DesktopExit`) tragen `@IfBuildProfile("desktop")`. Sie existieren nur im Desktop-Build. Der Server-Build lässt sich daher
nicht über Konfiguration oder Umgebungsvariablen in den Desktop-Modus schalten (geprüft in `ServerBuildTest`).
Die Konfiguration steht in `application.properties` unter `%desktop.`.

Der Build legt außerdem `backend/target/dispofold-launcher.jar` an (Pakete `app.dispofold.desktop.launcher` und
`app.dispofold.desktop.update`, Main-Class `Launcher`). Maven warnt dabei, dass `finalName` schreibgeschützt ist.
Der Name der Datei stimmt trotzdem.

## Programmordner

Aufbau, wie ihn das Programmfenster ausliefert (Packaging: siehe `scripts/desktop/`):

```
<Programmordner>/
  dispofold[.exe]          Programmfenster
  runtime/                 Java-Laufzeit (jlink)
  app/dispofold-launcher.jar
  app/bundle.json          Version der mitgelieferten App (unsigniert, siehe unten)
  app/app/quarkus-run.jar  die Quarkus-App aus backend/target/quarkus-app
  LICENSE, THIRD_PARTY.md, PORTABLE (nur portabel)
```

Das Fenster startet `runtime/bin/java[w] -jar app/dispofold-launcher.jar --shell --program-dir <Programmordner>`.
`--program-dir` bestimmt, wo `PORTABLE` gesucht und App Translocation erkannt wird. Die mitgelieferte App liegt im
Ordner der Launcher-Jar (`--app <Ordner>` überschreibt das für Tests). `bundle.json` erzeugt man nach dem Kopieren
der App:

```
java -cp dispofold-launcher.jar app.dispofold.desktop.update.SignTool manifest app <version> bundle.json
```

Die mitgelieferte App gilt als vertrauenswürdig und wird nicht auf eine Signatur geprüft.

Ohne `--shell` startet der Launcher Dispofold nicht ("Dispofold wird über das Programm geöffnet", Code 2). Übrig
bleibt nur `--change-password` (siehe unten).

Die App wird als Kindprozess gestartet:

```
<java.home>/bin/java -Ddispofold.desktop=true -Ddispofold.data-dir=<daten> -Ddispofold.versions-dir=<versionen>
  -Dquarkus.http.port=0 -jar <app>/quarkus-run.jar
```

Unter Windows wird `javaw.exe` verwendet, wenn es vorhanden ist. Die gebündelte Laufzeit muss also `bin/java`
enthalten, darf daher nicht mit `--strip-native-commands` gebaut werden. Sie braucht die Module des Launchers
(`java.base`, `java.desktop` für die Dialoge von `--change-password`) und die der App (unter anderem `java.sql`,
`java.naming`, `java.management`, `jdk.unsupported`, `java.net.http`).

## Datenordner

| Fall | Basisordner |
|---|---|
| Portabel: Datei `PORTABLE` im Programmordner | der Programmordner |
| Windows | `%LOCALAPPDATA%\Dispofold` |
| macOS | `~/Library/Application Support/Dispofold` |
| Linux | `${XDG_DATA_HOME:-~/.local/share}/dispofold` |
| `--data <Ordner>` (Tests, Netzlaufwerke) | dieser Ordner |

Den Programmordner gibt das Programm mit `--program-dir` an: unter Windows den Installations- bzw. ZIP-Ordner,
unter Linux `/usr/lib/Dispofold`, unter macOS `Dispofold.app/Contents/Resources`. Die Angabe ist Pflicht; fehlt
sie, endet der Launcher mit `failed desktop.launcher.programDirMissing`.

macOS startet ein heruntergeladenes Programm, das nicht mit dem Finder verschoben wurde (etwa direkt aus dem
eingehängten dmg oder aus "Downloads"), aus einer schreibgeschützten Kopie unter
`/private/var/folders/.../AppTranslocation/` (App Translocation). Der Launcher erkennt das am Programmordner
(`--program-dir`, unter macOS `Dispofold.app/Contents/Resources`), startet nicht und erklärt auf Deutsch, was zu
tun ist: Dispofold im Finder in "Programme" ziehen oder `xattr -dr com.apple.quarantine <Ordner>/Dispofold.app`.

Der Pfad des Datenordners darf weder `;` noch `${` enthalten. Er wird Teil der Datenbankadresse, und `;` würde
dort H2-Einstellungen beginnen, `${` einen Konfigurationsausdruck. Der Launcher und die App lehnen solche Pfade
mit einer Meldung ab.

Im Basisordner:

```
daten/
  db/dispofold.mv.db       verschlüsselte Datenbank
  einstellungen.json       Update-Einstellungen { "update": { "source": "...", "checkOnStart": true } }
  instanz.json             laufende Instanz ohne Geheimnis: { "host", "pid", "version", "started" }
  dispofold.lock           Sperre: nur ein Launcher pro Datenordner
  sicherungen/             vor-<version>-<zeit>/ (vor einer neuen Version), fehlgeschlagen-<version>-<zeit>/
  logs/app-<version>.log   Ausgabe der App (nur Ids, keine Namen)
versionen/
  <version>/               installierte Updates (bundle.json, bundle.json.sig, app/)
  eingang/                 heruntergeladenes, geprüftes Update für den nächsten Start
  state.json               { "current", "previous", "trial", "backup", "lastStarted", "bad": [] }
```

Der Rechnername in `instanz.json` kommt unter Windows aus `COMPUTERNAME`, sonst aus
`InetAddress.getLocalHost()`.

**Datenordner auf einem Netzlaufwerk (A9):** davon wird abgeraten, H2 über SMB oder NFS kann die Datenbank
beschädigen. Der Launcher erkennt einen UNC-Pfad, ein Windows-Netzlaufwerk (Laufwerkstyp über
`sun.nio.fs.WindowsFileStore`, die Launcher-Jar öffnet das Paket mit `Add-Opens`) und unter Linux und macOS die
Dateisystemtypen cifs, smb*, nfs*, sshfs, 9p, afpfs und webdav. Existiert der Ordner noch nicht, entscheidet der
nächste vorhandene übergeordnete Ordner. Dann schickt er die Warnung `desktop.dataFolder.network`; das Fenster
zeigt sie einmal pro Start. Gestartet wird trotzdem.

## Programmfenster, Launcher und App

Die Prozesskette: Programmfenster, darunter der Launcher (`--shell`), darunter die App. Zwischen Fenster und
Launcher läuft ein Zeilenprotokoll, ein JSON-Objekt pro Zeile (Klasse `ShellUi`):

- Fenster an Launcher (stdin): zuerst einmal `{"type":"hello","secret":"<Base64, 32 Byte>","protocol":1}`, dann
  `{"type":"unlock","password":"..."}` auf Anfrage und `{"type":"quit"}`. Schließt das Fenster stdin, gilt das
  als `quit`.
- Launcher an Fenster (stdout, nichts anderes geht dorthin; Meldungen und das Protokoll der App gehen nach stderr
  bzw. `daten/logs/`): `needPassword` (`firstStart`, `error`: `desktop.password.wrong` oder
  `desktop.password.short`), `locked` (`desktop.lock.held`), `starting` (`version`, vor jedem Start der App),
  `ready` (`port`, `pid`, `nonce`, `proof`), `warning` und `failed` (`code`, `params`) und als allerletzte Zeile
  `exit` (`code`).

Ablauf:

1. Der Launcher liest `hello`; fehlt es oder ist es falsch: `failed desktop.protocol.invalid`, `exit 2`.
2. Er sperrt `daten/dispofold.lock`. Ist die Sperre belegt: `locked`, `exit 1`.
3. Er fragt mit `needPassword` nach dem Passwort. Beim ersten Start (noch keine Datenbank) verlangt er mindestens
   12 Zeichen, bevor er die App startet; die zweite Eingabe prüft die Seite des Fensters.
4. Er wählt die Version (siehe Updates), schickt `starting` und startet die App. Auf ihre Standardeingabe schreibt
   er eine JSON-Zeile `{"password", "secret"}` und lässt sie offen. Schließt sie sich, beendet sich die App.
5. `DesktopMain` prüft das Passwort an der Datenbank (falsch: Code 4, der Launcher fragt erneut mit
   `desktop.password.wrong`). Die App bindet `127.0.0.1:0`. Sobald der HTTP-Server lauscht, schreibt `ReadyLine`
   auf ihre Standardausgabe `{"type":"ready","port","pid","nonce","proof"}` mit
   `proof = HMAC-SHA256(secret, "ready|" + nonce + "|" + port + "|" + pid)` und einem neuen Nonce bei jedem
   Start. Das Protokoll der App geht nach stderr (`%desktop.quarkus.log.console.stderr=true`).
6. Der Launcher prüft den Beweis, wartet, bis `/q/health/ready` auf diesem Port antwortet, und gibt die Zeile als
   `ready` weiter. Nach einem Update (Code 3) oder Rollback folgen erneut `starting` und `ready`.
7. Das Fenster prüft den Beweis und schickt `POST /api/desktop/challenge` mit `{"challenge":"<Base64, 16 Byte>"}`.
   Die App antwortet `{"response": HMAC-SHA256(secret, "challenge|" + challenge + "|" + port)}`. Erst dann leitet
   das Fenster Seiten an die App weiter.

Schlüssel und Nachrichten: Der HMAC-Schlüssel sind die 32 dekodierten Byte des Geheimnisses. Nonce und Challenge
gehen als ihr Base64-Text in die Nachricht ein, Port und PID dezimal, Ergebnisse sind Base64 mit Auffüllung
(`SessionKeys`, Testvektor in `SessionKeysTest`, derselbe wie im Fenster).

Beenden: "Beenden" in der Oberfläche (`POST /api/desktop/quit`) beendet die App mit Code 0, der Launcher schickt
`exit 0`. Beendet der Benutzer das Programm (Fenster schließen, unter Linux im Browser-Modus "Beenden" im kleinen
Fenster), schickt es `quit`; der Launcher schließt die Standardeingabe der
App, wartet bis zu 20 Sekunden und beendet sie notfalls.

### Anmeldung an der App

Jede Anfrage braucht den Header `X-Dispofold-Session: base64(HMAC-SHA256(secret, "session|" + nonce))`, Vergleich
in konstanter Zeit. `nonce` ist der Base64-Text aus der `ready`-Zeile dieses Starts der App; ein Wert aus einem
früheren Start wird abgelehnt, nach einem Neustart rechnet das Programm den Header neu. Es gibt kein Cookie und keinen Schlüssel in einer Adresse; das Geheimnis steht in keiner
Befehlszeile, keiner Umgebungsvariable und keiner Datei. Zusätzlich gilt (`DesktopAuth`):

- Der `Host`-Header muss genau `127.0.0.1:<eigener Port>` sein. Das schützt vor DNS-Rebinding.
- Ein `Origin` muss `dispofold://localhost` oder `http://dispofold.localhost` sein, bei allen Methoden.
- `Sec-Fetch-Site` muss, wenn vorhanden, `same-origin` oder `none` sein.
- Offen sind nur `/q/health/*` und, ohne Session-Header, aber mit den übrigen Prüfungen,
  `POST /api/desktop/challenge` (eine ungültige Challenge gibt `400 desktop.challenge.invalid`).

Ohne gültigen Header antwortet `/api/*` mit `401` (`{"code": "security.desktopLogin", "params": {}, "error":
"..."}`), Seiten mit `401` und einer kurzen Seite "Bitte Dispofold über das Programm öffnen". Fremde Herkunft gibt
`403` (`security.desktopForeign`).

### Meldungen des Launchers

`warning`: `desktop.dataFolder.network`, `desktop.update.refused` (`reason`: der `desktop.bundle.*`-Code, dazu
dessen Parameter), `desktop.update.rolledBack` (`version`), `desktop.version.damaged` (`version`, `reason`),
`desktop.state.damaged`.

`failed`: `desktop.protocol.invalid`, `desktop.launcher.usage`, `desktop.launcher.programDirMissing`,
`desktop.launcher.error` (`type`),
`desktop.translocated`, `desktop.dataFolder.unsafe`, `desktop.version.dataNewer` (`version`, `lastStarted`),
`desktop.app.startFailed` (`version`), `desktop.app.crashed` (`version`, `code`).

### Passwort und Datenbank

Das eingegebene Passwort wird mit PBKDF2-HMAC-SHA256 (600 000 Iterationen) und einem zufälligen Salz von
16 Byte gestreckt. Das Salz liegt in `daten/db/dispofold.salt`, also im selben Ordner wie die Datenbank, damit
Sicherungen und Rollback es mitnehmen. Aus den 64 Byte entsteht das H2-Passwort
`"<Dateipasswort> <Benutzerpasswort>"` (beide hexadezimal), Benutzer `sa`. Eine neue Datenbank verlangt
mindestens 12 Zeichen. Das ist ein gespeichertes Format: Wer es ändert, macht bestehende Datenbanken unlesbar.
Fehlt `dispofold.salt` neben einer bestehenden Datenbank, startet die App nicht und sagt das.

**Passwort ändern:** Dispofold beenden, dann `java -jar app/dispofold-launcher.jar --change-password --program-dir <Programmordner>` (fragt das
bisherige und zweimal das neue Passwort in Dialogen oder auf der Konsole; ohne beides `DISPOFOLD_PASSWORD` und
`DISPOFOLD_NEW_PASSWORD`). Die App bekommt beide Passwörter als eine JSON-Zeile auf ihrer Standardeingabe. Der Launcher lässt die App die
geschlossene Datenbank in einer Kopie neu verschlüsseln (neues Salz, H2 `ChangeFileEncryption` und
`ALTER USER`), prüft, dass die Kopie sich mit dem neuen Passwort öffnen lässt, und tauscht erst dann die Ordner.
Bricht der Wechsel zwischen den beiden Umbenennungen ab, holt der nächste Start `daten/db.alt` zurück.

**Es gibt keine Wiederherstellung ohne Passwort.** Ein verlorenes Passwort bedeutet verlorene Daten. Auch die
Sicherungen unter `daten/sicherungen/` sind verschlüsselt, und zwar mit dem Passwort, das beim Anlegen der
Sicherung galt. Nach einem Passwortwechsel brauchen ältere Sicherungen das alte Passwort. Zum Weitergeben von Daten gibt es den Export (siehe `docs/api.md`).

## REST-Erweiterungen (nur Desktop, Rolle `planner`)

| Methode | Pfad | Antwort |
|---|---|---|
| `GET` | `/api/me` | zusätzlich `"desktop": true` (im Server-Build `false`) |
| `POST` | `/api/desktop/quit` | `204`, danach beendet sich die App (Code 0) und mit ihr der Launcher |
| `GET` | `/api/desktop/update?check=true\|false` | `{ "current", "source", "checkOnStart", "available": { "version", "notes" } \| null, "error", "errorCode", "errorParams" }` (die drei `error*` sind `null` ohne Fehler) |
| `PUT` | `/api/desktop/update/settings` | Body `{ "source": "...", "checkOnStart": bool }`, Antwort die gespeicherten Einstellungen. `400` bei ungültiger Quelle |
| `POST` | `/api/desktop/update/install` | lädt das neueste Update der Quelle, prüft es, `202 { "version" }`, dann Neustart (Code 3). `400` mit Grund |
| `POST` | `/api/desktop/update/file` | Body: die Update-Datei (`application/octet-stream`, bis 1 GB), sonst wie `install` |

`check=false` liest nur die Einstellungen, `check=true` fragt die Quelle. Ist die Quelle nicht erreichbar, steht
der Grund in `errorCode` und `errorParams` (deutscher Ersatztext in `error`). Fehler von `settings`, `install` und
`file` haben die Form `{ "code", "params", "error" }` mit Codes `desktop.update.*` und `desktop.bundle.*` (Liste
in `docs/api.md`, Abschnitt "Fehlercodes"). Weder Codes noch Texte enthalten Pfade oder Meldungen von
Ausnahmen. Die Oberfläche zeigt ihn beim automatischen Prüfen nicht an.

Die Update-Quelle ist eine http(s)-Adresse oder ein Ordner. Voreingestellt ist keine Quelle: Solange keine
eingetragen ist, stellt das Programm keine Verbindung ins Netz her, und `check=true` antwortet mit dem Fehlercode
`desktop.update.noSource`. Eine leere Quelle in den Einstellungen schaltet die Prüfung wieder ab.

- **Adresse:** eine Releases-API im Format von GitHub (Gitea liefert dasselbe Format), entweder ein einzelnes Release (`.../releases/latest`)
  oder eine Liste davon. Aus `tag_name` wird die Version gelesen (mit oder ohne `v`), `body` sind die Notizen.
  Das Asset `dispofold-<version>-update.zip` wird geladen.
- **Ordner:** die neueste Datei `dispofold-<version>-update.zip` darin, zum Beispiel auf einem Netzlaufwerk.

Versionen aus der `bad`-Liste des Launchers werden nicht angeboten und nicht angenommen.

## Updates

### Format

`dispofold-<version>-update.zip` enthält:

```
bundle.json        { "version": "x.y.z", "files": { "<pfad unter app/>": "<sha256 hex>", ... } }
bundle.json.sig    Base64-Ed25519-Signatur über die exakten Bytes von bundle.json
app/...            die Quarkus-App des Desktop-Builds (platformunabhängig)
```

Laufzeit und Launcher ändern sich nur mit einem neuen Installer oder ZIP.

### Prüfung

Die Prüfung steckt in `BundleVerifier`. Die App verwendet sie vor dem Ablegen in `eingang/`, der Launcher beim
Entpacken und vor jedem Start einer installierten Version.

- Die Signatur wird mit dem einkompilierten öffentlichen Schlüssel (`BundleVerifier.PUBLIC_KEY`) geprüft.
  Ein Property oder eine Umgebungsvariable dafür gibt es nicht.
- Jede Datei unter `app/` muss ihren SHA-256-Wert haben. Es darf keine Datei fehlen, keine dazukommen, und es
  darf keine Verknüpfungen oder Sonderdateien geben.
- Die Version muss streng neuer sein als die laufende (`x.y.z`, numerisch). Gleiche oder ältere Versionen sind
  abgelehnt (kein Zurückspielen).
- Beim Entpacken gilt:
  - Erlaubt sind nur `bundle.json`, `bundle.json.sig` und `app/**`.
  - Pfade mit `..`, `/` am Anfang, `\` oder `:` sind abgelehnt.
  - Kein Name darf doppelt vorkommen.
  - Höchstens 20 000 Einträge und 1 GB, gezählt an den tatsächlich gelesenen Bytes.
  - Zuerst werden `bundle.json` und die Signatur im Archiv geprüft (mit Größengrenze), bevor irgendetwas
    entpackt wird. Danach werden nur die in `bundle.json` gelisteten Dateien entpackt und dabei gehasht. Eine
    nicht gelistete Datei bricht die Prüfung ab, bevor etwas geschrieben wird. Die entpackten Dateien werden
    zum Schluss noch einmal geprüft.

### Ablauf

1. Die App legt das geprüfte Update als `versionen/eingang/dispofold-<v>-update.zip` ab und endet mit Code 3.
2. Der Launcher prüft es erneut, entpackt es atomar nach `versionen/<v>/` und kopiert `daten/db` nach
   `daten/sicherungen/vor-<v>-<zeit>/`. Danach setzt er in `state.json` `trial=<v>`. Die Datei in `eingang/`
   wird danach gelöscht, auch wenn sie abgelehnt wurde. Der Grund erscheint als Meldung.
3. Die Probeversion startet. Wird sie innerhalb von 3 Minuten (`--ready-timeout`) bereit, gilt
   `current=<v>`, `previous=<alt>`. Zuerst wird `state.json` geschrieben, dann werden ältere Versionsordner
   außer `previous` entfernt. Lässt sich ein Ordner nicht löschen (etwa eine Dateisperre unter Windows), läuft
   die neue Version trotzdem weiter, der Ordner wird beim nächsten Update erneut entfernt.
4. Endet sie vorher oder wird sie nicht bereit, stoppt der Launcher sie. Er legt die Datenbank des Fehlstarts
   unter `sicherungen/fehlgeschlagen-<v>-<zeit>/` ab, spielt die Sicherung zurück, trägt `<v>` in `bad` ein und
   startet die vorherige Version. Der Benutzer erhält eine Meldung. Auch hier wird `state.json` vor dem
   Löschen des Versionsordners geschrieben.

Von `vor-*` und `fehlgeschlagen-*` bleiben in `daten/sicherungen/` jeweils die neuesten 5 erhalten
(`--keep-backups <n>`), ältere werden nach jeder neuen Sicherung gelöscht. Andere Ordner dort bleiben unberührt.

Bei jedem Start gilt diese Reihenfolge:

1. die Probeversion
2. `current`, wenn sie neuer ist als die mitgelieferte
3. `previous`
4. die mitgelieferte Version

Eine installierte Version wird vor jedem Start vollständig geprüft. Ist sie beschädigt, wird sie übersprungen.
Versionen aus `bad` werden nie gestartet oder erneut angenommen. Bringt ein neuer Installer eine neuere Version
mit, gewinnt diese.

`lastStarted` ist die letzte Version, die bereit wurde. Die Datenbank hat deren Stand. Daraus folgt:

- **Neue Version:** Vor dem ersten Start einer anderen Version als `lastStarted` sichert der Launcher
  `daten/db` nach `daten/sicherungen/vor-<v>-<zeit>/`. Das gilt auch für die Version eines neuen Installers. Bei
  einer Probeversion ist diese Sicherung schon beim Ablegen entstanden.
- **Ältere Version:** Eine Version, die älter ist als `lastStarted`, startet nicht. Das kann passieren, wenn
  `current` beschädigt ist und nur eine ältere übrig bleibt. Dann erscheint eine Meldung, man soll mindestens
  `lastStarted` neu installieren. Die Daten bleiben unverändert, eine ältere Version läuft also nie auf einem
  neueren Schema.

`state.json` und `einstellungen.json` werden atomar geschrieben: temporäre Datei, `fsync`, dann Umbenennen. Ist
`state.json` trotzdem unlesbar, legt der Launcher sie als `state.json.beschaedigt` beiseite und meldet das. Er
setzt die neueste installierte Version, die die Prüfung besteht, als `current` und `lastStarted` und startet
normal.

### Bekannte Restrisiken

- **Gerätenamen unter Windows:** Signatur und Dateiliste werden geprüft, bevor etwas entpackt wird, und nur
  gelistete Dateien werden entpackt (siehe Prüfung). Windows-Gerätenamen wie `CON` oder `LPT1` in Pfaden einer
  signierten Dateiliste sind aber nicht eigens abgefangen; ein solches Update müsste mit dem Release-Schlüssel
  signiert sein.
- **Zeit zwischen Prüfung und Start:** Eine installierte Version wird geprüft, bevor sie startet. Die Jars lädt
  die JVM aber erst danach. Wer in diesem Moment in `versionen/` schreiben kann, kann Dateien tauschen. Das ist
  nur von Bedeutung, wenn andere Benutzer dort Schreibrechte haben, etwa im portablen Betrieb auf einer
  Netzwerkfreigabe.

Exit-Codes der App: 0 beendet, 3 Update abgelegt (Neustart), 4 falsches Passwort, alles andere ist ein Fehler.

### Signieren

Der private Schlüssel liegt nie im Repository. Lokal liegt er unter `~/.config/dispofold/update-signing.key`
(Base64 von PKCS#8-DER, Modus 600), im Release-Workflow im Secret `UPDATE_SIGNING_KEY`. Tests brauchen ihn nie.

Der öffentliche Schlüssel kommt beim Bauen aus der Maven-Eigenschaft `dispofold.update.public-key` (Vorgabe in
`backend/pom.xml`: der echte Release-Schlüssel). Testbuilds setzen einen eigenen:
`./mvnw package -Dquarkus.profile=desktop -Ddispofold.update.public-key=<Base64 X.509>`.
`scripts/desktop/verify-bundle.sh <update.zip>` prüft eine Update-Datei gegen den Schlüssel im gebauten
`backend/target/dispofold-launcher.jar` (anderer Build: `LAUNCHER_JAR=...`) und endet sonst mit einem Fehler.

```
# Update-Datei aus einem Desktop-Build bauen und signieren
java -cp dispofold-launcher.jar app.dispofold.desktop.update.SignTool bundle <keyfile> backend/target/quarkus-app <version> <ausgabeordner>
# bundle.json einzeln signieren (schreibt bundle.json.sig daneben)
java -cp dispofold-launcher.jar app.dispofold.desktop.update.SignTool sign <keyfile> <bundle.json>
# prüfen, mit dem einkompilierten Schlüssel oder --key <öffentlicher Schlüssel>
java -cp dispofold-launcher.jar app.dispofold.desktop.update.SignTool verify <update.zip>
# Schlüsselpaar für Tests
java -cp dispofold-launcher.jar app.dispofold.desktop.update.SignTool keygen <privat> <öffentlich>
```

Ein neuer Release-Schlüssel braucht einen neuen Launcher und eine neue App mit dem neuen öffentlichen Schlüssel. Er kommt
also nur mit einem neuen Installer.

## Prüfen

- Unit- und Quarkus-Tests: `cd backend && ./mvnw -B test`. Dazu gehören `BundleVerifierTest`, `SupervisorTest`
  (Zustandsautomat mit simulierter App), `DesktopAuthTest` und `DesktopResourceTest` (Profil `desktop`) sowie
  `ServerBuildTest`.
- Ende-zu-Ende unter Linux oder macOS, ohne Bildschirm: `backend/src/test/desktop/e2e.sh`. Das Skript baut
  0.9.0, 0.9.1 und eine absichtlich kaputte 0.9.2 in einer Kopie und prüft Erststart, Demoimport, Update mit
  erhaltenen Daten, abgelehnte manipulierte Datei (Upload und `eingang/`), Rollback, falsches Passwort und
  Passwortwechsel.
  Teil 1 baut mit `scripts/desktop/package.sh` das deb, entpackt es und lässt den Selbsttest des echten
  Programmfensters laufen (`dispofold --selftest`, unter Xvfb und D-Bus): Passwort, Prüfung der App, Oberfläche,
  Demodaten, Planen, Export, Beenden. Teil 2 prüft, was der Selbsttest nicht abdeckt (Updates, manipulierte Datei,
  Rollback, falsches Passwort, Passwortwechsel, abgelehnte Host-, Origin- und Session-Header); dort spielt
  `backend/src/test/desktop/ShellDriver.java` das Fenster über das Zeilenprotokoll. Das deb zu bauen braucht
  mehrere GB: `TMPDIR` auf eine Platte legen; `DISPOFOLD_E2E_DEB=<deb>` nimmt ein fertiges deb.
  Es erzeugt bei jedem Lauf ein Wegwerf-Schlüsselpaar und baut die Testversionen mit dessen öffentlichem
  Schlüssel; der Release-Schlüssel wird nicht gebraucht. Es braucht `java`, `jq`, `zip`, `unzip`, `curl`,
  `rsync` und ein gebautes `frontend/dist`. Fehlt etwas, schlägt es fehl. Mit `KEEP=1` bleibt der
  Arbeitsordner erhalten.
