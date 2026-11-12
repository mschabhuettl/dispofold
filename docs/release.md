# CI und Releases auf GitHub (für den Eigentümer)

GitHub Actions führt die Workflows in `.github/workflows/` aus:

- **CI** (`ci.yml`) bei jedem Push und Pull Request: Repository-Prüfung (Text und Bilder), Backend-Tests auf
  PostgreSQL und auf H2, Solver-Lauf mit 150 Personen, Demodaten- und Frontend-Tests, Tests des
  Programmfensters (`cargo test`), Linux-Pakete mit Selbsttest des installierten Programms, Prüfung der
  Browser-Oberfläche und Desktop-Ende-zu-Ende-Test (Update, Rollback, Passwortwechsel, mit einem
  Wegwerf-Schlüssel je Lauf), Start des ganzen Stacks mit Rauchtest. Mit `WINDOWS_TEST` zusätzlich der Windows-Test (siehe unten).
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
4. Optional die Variable `MACOS_RUNNER` mit dem Label eines macOS-Runners, z. B. `macos-latest`; ohne sie wird
   das macOS-Paket übersprungen.

`RELEASE_TOKEN` und `API_URL` braucht es auf GitHub nicht: Das automatische Job-Token darf Releases anlegen und
Images nach `ghcr.io` pushen, und die Uploads gehen an die Standardadresse der API. Neue Pakete in `ghcr.io` sind
zunächst privat; die Sichtbarkeit stellt man unter **Packages** des Kontos ein.

Gitea hält nur eine Kopie des Codes (`main` und Tags per Push) und führt keine Workflows aus.

## Windows- und macOS-Pakete

- **Windows:** braucht keinen eigenen Runner. Setup und portables ZIP entstehen auf dem Linux-Runner
  (Cross-Build mit `cargo-xwin`; dabei lädt es das Windows-SDK von Microsoft, etwa 1 GB).
- **macOS:** nur auf Apple-Hardware (Lizenzbedingung von Apple). Die gehosteten Runner von GitHub genügen:
  `MACOS_RUNNER=macos-latest`.

## Release

Eine Version entsteht nur aus einem Tag der Form `vMAJOR.MINOR.PATCH` (zum Beispiel `v1.0.0`, ohne Zusätze wie
`-rc1`). Andere Tags `v*` lassen den Release-Workflow im ersten Schritt scheitern.

```bash
git tag -a v1.0.0 -m "Version 1.0.0"
git push origin v1.0.0   # GitHub; danach dasselbe Tag auch zur Kopie auf Gitea pushen
```

Der Release-Workflow macht dann:

1. **Versionsprüfung** des Tags und **die ganze CI** (`ci.yml`). Erst wenn beides grün ist, geht es weiter;
   vorher wird nichts veröffentlicht.
2. Das Release wird als **Entwurf** angelegt; niemand außer den Berechtigten sieht es.
3. Danach parallel, jeweils mit der Version aus dem Tag in allen Builds (`scripts/release/set-version.sh`):
   - **Images:** baut `backend` und `frontend`, hängt das Archiv `dispofold-<version>-images.tar.gz` (mit dem
     PostgreSQL-Image), die SHA-256-Datei, `docker-compose.yml` und `env.example` an das Release und pusht die
     Images in die Registry, wenn `REGISTRY` gesetzt ist.
   - **Linux:** `.deb` und `.rpm`, dazu das signierte Update-Paket `dispofold-<version>-update.zip`. Das
     Update-Paket wird gleich danach gegen den eingebauten öffentlichen Schlüssel geprüft. Fehlt der Schlüssel
     oder scheitert die Prüfung, scheitert das Release.
   - **Windows:** Setup und portables ZIP, auf dem Linux-Runner gebaut.
   - **macOS:** das dmg, nur wenn `MACOS_RUNNER` gesetzt ist. macOS verlangt eine Version, die nicht mit 0
     beginnt; eine Version `0.y.z` wird dort als `1.y.z` verpackt, die Anwendung darin behält `0.y.z`.

   Jeder dieser Teile lädt seine Dateien mit einer eigenen `*-SHA256SUMS.txt` hoch.
4. Erst wenn alle Teile erfolgreich waren (macOS darf fehlen), wird der Entwurf **veröffentlicht**. Scheitert ein
   Teil, bleibt es beim Entwurf. Ausnahme: Die Images liegen dann schon in der Registry (Schritt 3), sind aber
   noch keinem veröffentlichten Release zugeordnet.

**Wenn ein Release scheitert:** Ursache beheben und den fehlgeschlagenen Lauf (oder einzelne Jobs) auf GitHub
neu starten. Das ist sicher: Ein Upload ersetzt eine vorhandene Datei gleichen Namens im Release, statt zu scheitern.
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
