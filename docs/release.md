# CI und Releases auf GitHub (für den Eigentümer)

GitHub Actions führt die Workflows in `.github/workflows/` aus:

- **CI** (`ci.yml`) bei jedem Push und Pull Request: Repository-Prüfung (Text und Bilder), Backend-Tests auf
  PostgreSQL und auf H2, Solver-Lauf mit 150 Personen, Demodaten- und Frontend-Tests, Tests des
  Programmfensters (`cargo test`), Linux-Pakete mit Selbsttest des installierten Programms, Prüfung der
  Browser-Oberfläche und Desktop-Ende-zu-Ende-Test (Update, Rollback, Passwortwechsel, mit einem
  Wegwerf-Schlüssel je Lauf), Start des ganzen Stacks mit Rauchtest. Mit `WINDOWS_TEST` zusätzlich der Windows-Test (siehe unten).
  Dazu baut die CI die Dokumentations-Website (mit Zensical, `python tools/docs/build.py`, siehe `mkdocs.yml`); eine fehlende Seite,
  ein fehlender Anker oder ein fehlendes Bild lässt sie scheitern.
- **Release** (`release.yml`) bei einem Tag `v*`, siehe [Release](#release).

Die Workflows enthalten keine Adressen. Was je Installation verschieden ist, kommt aus den Einstellungen des
Repositorys (siehe unten).

## Repository-Einstellungen

Auf GitHub unter **Settings**, **Secrets and variables**, **Actions**:

1. Variable `REGISTRY` = `ghcr.io/mschabhuettl`: Ziel für den Push der Images. Ohne die Variable werden keine
   Images gepusht; das Archiv im Release gibt es trotzdem.
2. Secret `UPDATE_SIGNING_KEY`: der private Ed25519-Schlüssel für die Update-Pakete (Inhalt der
   Schlüsseldatei, eine Zeile Base64 von PKCS#8). Ohne ihn schlägt das Release fehl. Nur der Schritt, der das
   Update-Paket signiert, bekommt ihn; die CI braucht ihn nie. Der Workflow prüft das Update-Paket danach gegen
   den im Programm eingebauten öffentlichen Schlüssel. Den privaten Schlüssel zusätzlich sicher außerhalb von
   GitHub aufbewahren.
3. Variable `WINDOWS_TEST` = `true`: Die CI installiert das unter Linux gebaute Setup auf einem Windows-Runner
   (`windows-latest`) und startet den Selbsttest.
4. Optional die Variable `MACOS_RUNNER` mit dem Label eines anderen macOS-Runners; ohne sie baut das Release das
   dmg auf dem gehosteten Runner `macos-15` (Apple Silicon), der für öffentliche Repositorys kostenlos ist.
5. Einmalig unter **Settings**, **Pages** die **Source** auf **GitHub Actions** stellen. Ohne diese Einstellung
   scheitert der letzte Schritt des Releases (Dokumentations-Website), das Release selbst ist dann schon
   veröffentlicht.
   Dazu unter **Settings**, **Environments**, `github-pages` bei **Deployment branches and tags** die Tags
   `v*` erlauben; sonst lehnt GitHub das Deployment eines Tags ab. Beides ist für dieses Repository eingestellt.
6. Optional die Einstellungen für die Windows-Signatur, siehe
   [Windows-Signatur mit SignPath](#windows-signatur-mit-signpath). Ohne die Variable
   `SIGNPATH_ORGANIZATION_ID` bleiben die Windows-Dateien unsigniert und der Workflow läuft wie bisher.

`RELEASE_TOKEN` und `API_URL` braucht es auf GitHub nicht: Das automatische Job-Token darf Releases anlegen und
Images nach `ghcr.io` pushen, und die Uploads gehen an die Standardadresse der API. Neue Pakete in `ghcr.io` sind
zunächst privat; die Sichtbarkeit stellt man unter **Packages** des Kontos ein.

Gitea hält nur eine Kopie des Codes (`main` und Tags per Push) und führt keine Workflows aus.

## Windows- und macOS-Pakete

- **Windows:** braucht keinen eigenen Runner. Setup und portables ZIP entstehen auf dem Linux-Runner
  (Cross-Build mit `cargo-xwin`; dabei lädt es das Windows-SDK von Microsoft, etwa 1 GB).
- **macOS:** nur auf Apple-Hardware (Lizenzbedingung von Apple). Die gehosteten Runner von GitHub genügen
  (`macos-15`, Apple Silicon). Ein dmg für Intel-Macs bräuchte einen zweiten Job auf `macos-15-intel` mit der
  Intel-JDK (in `build-runtime.sh` schon hinterlegt) und ein eigenes dmg; das gibt es derzeit nicht.
- **Linux arm64:** auf dem gehosteten Runner `ubuntu-24.04-arm` (deb, rpm, mit der aarch64-Laufzeit).
- **Prüflauf vor einem Release:** **Actions**, **ARM packages and images**, **Run workflow**. Baut dmg und arm64-Pakete,
  installiert sie, startet jeweils den Selbsttest des Programms (Fenster, Laufzeit, Backend: Demodaten, Planen,
  Export) und legt Ergebnis und Bildschirmfotos als Artefakte ab; dazu den Docker-Stack nativ auf arm64 mit dem
  Smoke-Test und die Image-Schritte des Releases für beide Plattformen. Veröffentlicht nichts.

## Release

Eine Version entsteht nur aus einem Tag der Form `vMAJOR.MINOR.PATCH` (zum Beispiel `v1.0.0`, ohne Zusätze wie
`-rc1`). Andere Tags `v*` lassen den Release-Workflow im ersten Schritt scheitern.

```bash
git tag -a v1.0.0 -m "Version 1.0.0"
git push origin v1.0.0   # GitHub; danach dasselbe Tag auch zur Kopie auf Gitea pushen
```

Vorher die Version in `CHANGELOG.md` als Abschnitt `## MAJOR.MINOR.PATCH (Datum)` beschreiben und mit
`scripts/release/set-version.sh vMAJOR.MINOR.PATCH` in alle Builds schreiben (beides committen). Der Abschnitt
wird der Text des Releases.

Der Release-Workflow macht dann:

1. **Versionsprüfung** des Tags und **die ganze CI** (`ci.yml`). Erst wenn beides grün ist, geht es weiter;
   vorher wird nichts veröffentlicht.
2. Das Release wird als **Entwurf** angelegt; niemand außer den Berechtigten sieht es.
3. Danach parallel, jeweils mit der Version aus dem Tag in allen Builds (`scripts/release/set-version.sh`):
   - **Images:** baut `backend` und `frontend` für amd64 und für arm64 (die Anwendung auf dem Runner, nur die
     letzte Stufe für arm64 unter QEMU), hängt die Archive `dispofold-<version>-images.tar.gz` (amd64) und
     `dispofold-<version>-images-arm64.tar.gz` (jeweils mit dem PostgreSQL-Image der Plattform), die
     SHA-256-Datei, `docker-compose.yml` und `env.example` an das Release und pusht die Images in die Registry,
     wenn `REGISTRY` gesetzt ist: je Plattform als `<version>-amd64` und `<version>-arm64`, dazu `<version>` und
     `latest` für beide.
   - **Linux:** `.deb` und `.rpm`, dazu das signierte Update-Paket `dispofold-<version>-update.zip`. Das
     Update-Paket wird gleich danach gegen den eingebauten öffentlichen Schlüssel geprüft. Fehlt der Schlüssel
     oder scheitert die Prüfung, scheitert das Release.
   - **Windows:** Setup und portables ZIP, auf dem Linux-Runner gebaut. Ist SignPath eingerichtet, werden sie
     vor den Prüfsummen signiert (siehe [Windows-Signatur mit SignPath](#windows-signatur-mit-signpath)).
   - **macOS und Linux arm64** (Workflow `desktop-packages.yml`): das dmg und die arm64-Pakete, jeweils
     installiert und mit dem Selbsttest des Programms geprüft, bevor sie hochgeladen werden. macOS verlangt eine
     Version, die nicht mit 0 beginnt; eine Version `0.y.z` wird dort als `1.y.z` verpackt, die Anwendung darin
     behält `0.y.z`.

   Jeder dieser Teile lädt seine Dateien mit einer eigenen `*-SHA256SUMS.txt` hoch.
4. Erst wenn alle Teile erfolgreich waren, wird der Entwurf **veröffentlicht**. Scheitert ein
   Teil, bleibt es beim Entwurf. Ausnahme: Die Images liegen dann schon in der Registry (Schritt 3), sind aber
   noch keinem veröffentlichten Release zugeordnet.
5. Nach dem Veröffentlichen baut der Job `pages` die Dokumentations-Website aus `docs/` und `README.md` dieses Tags
   und stellt sie auf GitHub Pages: <https://mschabhuettl.github.io/dispofold/>. Die Website zeigt immer die
   Dokumentation des neuesten Releases.

**Wenn ein Release scheitert:** Ursache beheben und den fehlgeschlagenen Lauf (oder einzelne Jobs) auf GitHub
neu starten. Das ist sicher: Ein Upload ersetzt eine vorhandene Datei gleichen Namens im Release, statt zu scheitern.
Mit SignPath gilt für den Windows-Teil die Einschränkung unter
[Was sich am Release ändert](#was-sich-am-release-ändert).
Muss der Code geändert werden, das Tag auf dem alten Commit lassen und eine neue Version taggen (zum Beispiel
`v1.0.1`). Ein schon veröffentlichtes Tag nicht verschieben.

**Signaturschlüssel:** Der öffentliche Gegenschlüssel zu `UPDATE_SIGNING_KEY` ist in jedes Programm
eingebaut. Geht der private Schlüssel verloren, kann kein installiertes Programm mehr ein Update annehmen. Dann
braucht es einen neuen Schlüssel, einen Build mit dessen öffentlichem Schlüssel (Maven-Eigenschaft
`dispofold.update.public-key` in `backend/pom.xml`) und auf jedem Rechner einen neuen Installer (Setup, ZIP, Paket
oder dmg). Dasselbe gilt, wenn der Schlüssel in fremde Hände gerät.

Das Image `frontend` ist bitgleich reproduzierbar (Zeitstempel aus dem Commit, Basis-Images mit festem
Digest). Das `backend`-Image unterscheidet sich zwischen zwei Builds: Die
Timefold-Erweiterung von Quarkus erzeugt beim Build eine Klasse (`Roster$Timefold$SolutionCloner`) jedes Mal mit
anderem Bytecode. Inhaltlich sind die Builds gleich.

Die Basis-Images sind in den Dockerfiles mit Digest festgelegt. Für Sicherheitsupdates den Digest erneuern, z. B.
`docker buildx imagetools inspect eclipse-temurin:21-jre-alpine`.

## Windows-Signatur mit SignPath

Die SignPath Foundation signiert Open-Source-Projekte kostenlos mit ihrem Zertifikat. Den ersten Antrag hat
sie am 2026-11-18 abgelehnt, weil das Projekt noch zu wenig öffentlich sichtbar ist; ein neuer Antrag ist
willkommen und geplant, sobald das Projekt bekannter ist. Bis dahin bleiben die Windows-Dateien unsigniert und
die Signaturschritte im Release-Workflow aus. Die Schritte unten gelten für diesen künftigen Antrag. Den Antrag
stellt der Eigentümer selbst; das Repository ist dafür vorbereitet: die
[Code signing policy](code-signing-policy.md), die Metadaten der Windows-Dateien (Produktname „Dispofold“,
Datei- und Produktversion gleich der Release-Version), die Artefaktkonfiguration
`.signpath/artifact-configuration.xml` und die abschaltbaren Schritte im Release-Workflow.

Signiert werden das Setup `Dispofold-<version>-windows-setup.exe` als Ganzes und `Dispofold\dispofold.exe` im
portablen ZIP. Das vom Setup installierte Programm und sein Deinstallationsprogramm bleiben unsigniert, weil
SignPath keine Dateien in einem NSIS-Setup signiert. Die Java-Laufzeit (`runtime\`) bringt Dateien von Eclipse
Adoptium und Microsoft mit, die deren Hersteller schon signiert haben; sie werden nicht noch einmal signiert. Das
Update-Paket enthält keine Windows-Programme und behält seine eigene Signatur.

### Antrag

Ein neuer Antrag geht über das Formular auf <https://signpath.org/apply>. Vorher den Text auf den aktuellen
Stand bringen (Versionen, Verbreitung des Projekts). Der Text dafür (Englisch, weil SignPath ihn
liest):

```text
Project name: Dispofold
Repository: https://github.com/mschabhuettl/dispofold
Homepage and documentation: https://mschabhuettl.github.io/dispofold/
Download / release page: https://github.com/mschabhuettl/dispofold/releases
License: AGPL-3.0-only. All bundled components are under OSI-approved licences or are system
  libraries; the list is in THIRD_PARTY.md
  (https://github.com/mschabhuettl/dispofold/blob/main/THIRD_PARTY.md).
Code signing policy: https://mschabhuettl.github.io/dispofold/code-signing-policy/
  (source: https://github.com/mschabhuettl/dispofold/blob/main/docs/code-signing-policy.md)

Description:
Dispofold is a duty roster planner for dispatch centres. Planners maintain staff, qualifications,
demand, wishes and absences; a solver (Timefold Solver Community Edition) computes the monthly roster
under working time rules, and planners check, change, fix and export it (Excel, CSV, JSON). It runs
as a server (Docker: Quarkus backend, PostgreSQL, web interface) or as a desktop program for one
computer: a Tauri program window (Rust) with a bundled Java runtime (Eclipse Temurin) and an
encrypted local database. The desktop program makes no network request without the user's consent;
the update check is asked for on the first start and can be switched off at any time.

Files to sign:
The Windows release files: Dispofold-<version>-windows-setup.exe (NSIS installer, per-user
installation) and dispofold.exe (the program window) inside Dispofold-<version>-windows-portable.zip.
Both have been published unsigned on the release page since version 0.1.0.

How the files are built:
Every release is built by the workflow .github/workflows/release.yml on GitHub-hosted runners from
the tagged commit of the public repository; nothing is built on a developer machine. A tag
vMAJOR.MINOR.PATCH starts it. It first runs the complete CI (tests, packaging, self tests of the
installed programs), then builds the Windows files on an ubuntu-latest runner (cross-build with
cargo-xwin and NSIS, pinned Windows SDK, Temurin JDK checked against pinned SHA-256 values). The
unsigned files are uploaded as a GitHub Actions artifact and submitted with
signpath/github-action-submit-signing-request; checksums are computed after signing, then the files
are attached to the release.

Team: mschabhuettl (https://github.com/mschabhuettl) is author, reviewer and approver. Every release
is approved individually in SignPath. MFA is enabled on GitHub and will be enabled on SignPath.
```

### Nach der Zusage

Auf SignPath (<https://app.signpath.io>). Was die Foundation bei der Zusage schon angelegt hat (Organisation,
Projekt, Policies), nur prüfen und die Slugs übernehmen. Die Namen der Menüs und Rollen folgen der Dokumentation von SignPath
(<https://docs.signpath.io>) und können in der Oberfläche abweichen:

1. Für das eigene Konto die Zwei-Faktor-Anmeldung einschalten; auf GitHub ebenso (verlangt SignPath für alle
   Beteiligten).
2. In der Organisation unter **Trusted Build Systems** das vordefinierte System **GitHub.com** hinzufügen. Die
   GitHub-App von SignPath braucht es nur für Prüfungen der GitHub-Regelsätze; ohne sie geht es auch.
3. Ein Projekt anlegen (Slug zum Beispiel `dispofold`), mit dem Repository
   `https://github.com/mschabhuettl/dispofold`, und das Trusted Build System **GitHub.com** mit dem Projekt
   verknüpfen.
4. Im Projekt eine Artefaktkonfiguration anlegen (Slug zum Beispiel `windows`) und den Inhalt von
   `.signpath/artifact-configuration.xml` einfügen. Sie erwartet den Parameter `version`, den der Workflow
   mitgibt. Ob SignPath die Versionsangaben als `0.14.0` oder als `0.14.0.0` vergleicht, ist noch nicht
   ausprobiert; scheitert die erste Anfrage an `product-version` oder `file-version`, dort `${version}.0`
   eintragen.
5. Eine Signing Policy mit dem Slug `release-signing` für das Zertifikat der Foundation anlegen (oder die von
   SignPath vorgegebene nehmen und ihren Slug als Variable eintragen, siehe unten), mit dem eigenen Konto als
   Approver und manueller Freigabe jeder Anfrage. Als Submitter einen CI-Benutzer anlegen und ihm die Policy
   erlauben; sein API-Token kommt als Secret nach GitHub.
6. Die Organisations-ID (unter **Settings** der Organisation) notieren.

Auf GitHub unter **Settings**, **Secrets and variables**, **Actions**:

| Art | Name | Inhalt |
|---|---|---|
| Secret | `SIGNPATH_API_TOKEN` | API-Token des CI-Benutzers aus SignPath |
| Variable | `SIGNPATH_ORGANIZATION_ID` | Organisations-ID; schaltet die Signatur ein |
| Variable | `SIGNPATH_PROJECT_SLUG` | Slug des Projekts, zum Beispiel `dispofold` |
| Variable | `SIGNPATH_ARTIFACT_CONFIGURATION_SLUG` | Slug der Artefaktkonfiguration, zum Beispiel `windows` |
| Variable | `SIGNPATH_SIGNING_POLICY_SLUG` | nur nötig, wenn die Policy nicht `release-signing` heißt |

Ist `SIGNPATH_ORGANIZATION_ID` gesetzt, fehlt aber das Secret oder `SIGNPATH_PROJECT_SLUG`, scheitert der
Windows-Teil des Releases mit einer Meldung; der Entwurf wird dann nicht veröffentlicht. Ohne
`SIGNPATH_ARTIFACT_CONFIGURATION_SLUG` nimmt SignPath die Standard-Artefaktkonfiguration des Projekts.

Danach in der [Code signing policy](code-signing-policy.md) den Abschnitt „Signing status“ auf „live“ ändern und
die Zeile „Free code signing provided by SignPath.io, certificate by SignPath Foundation“ aufnehmen, ebenso in
der Vorlage der Release-Notizen (`scripts/release/publish-release.sh`) und in `docs/betrieb.md`
(Unsignierte Programme). Erst dann, nicht vorher.

### Was sich am Release ändert

- Ablauf und Tag bleiben gleich. Neu: Der Windows-Teil lädt Setup und ZIP als Artefakt `windows-unsigned` hoch,
  schickt sie an SignPath und **wartet auf die Freigabe**. SignPath benachrichtigt die Approver; der Eigentümer
  gibt die Anfrage in SignPath unter **Signing Requests** frei (oder lehnt sie ab). Der Job wartet höchstens 2 Stunden; ohne
  Freigabe scheitert er und das Release bleibt Entwurf. Dann den Job neu starten, das baut neu und stellt eine
  neue Anfrage. Verbietet die Signing Policy Wiederholungen (Prüfung `disallow_reruns`), lehnt SignPath die
  Anfrage eines neu gestarteten Jobs ab; dann braucht es ein neues Tag (neue Version).
- Erst nach der Signatur prüft der Workflow, dass Setup und `dispofold.exe` im ZIP eine Signatur tragen, ersetzt
  die unsignierten Dateien und bildet `dispofold-<version>-windows-SHA256SUMS.txt`. Die Prüfsummen gelten damit
  für die signierten Dateien.
- Vor der Freigabe in SignPath prüfen, dass die Anfrage aus dem erwarteten Lauf kommt (Repository, Tag, Commit
  stehen in der Anfrage). Unerwartete Anfragen ablehnen.
- Nach dem Veröffentlichen eine Datei stichprobenartig prüfen, zum Beispiel unter Windows in PowerShell
  `Get-AuthenticodeSignature .\Dispofold-<version>-windows-setup.exe` (Status `Valid`, Aussteller SignPath
  Foundation).
- SmartScreen kann neue Versionen trotz Signatur anfangs noch melden, bis das Zertifikat genug Ansehen
  gesammelt hat.
