# Erweiterungen entwickeln

Eine Erweiterung (Plugin) bringt eigene Seiten, Einstellungen, Routen, einen Import und Hilfeseiten in Dispofold.
Sie wird mit Dispofold gebaut und ausgeliefert, in diesem Repository; zur Laufzeit lädt das Programm keinen fremden
Code. Eine eingeschaltete Erweiterung sieht alle Planungsdaten, deshalb ist jede aus, bis die Planung sie unter
**Einstellungen → Erweiterungen** einschaltet, und der Betrieb kann sie ganz ausblenden
(`DISPOFOLD_PLUGINS_HIDDEN`, siehe [Betrieb](betrieb.md#erweiterungen)).

Das Beispiel `plugins/beispiel/` nutzt jeden Erweiterungspunkt einmal. Es gibt es nur in den Tests: Es ist in
keinem Image und keinem Programmpaket.

## Aufbau

Alles einer Erweiterung liegt an einer Stelle:

```text
plugins/<id>/
  plugin.json                      id, name, version, description, testOnly
  backend/src/main/java/app/dispofold/plugins/<id>/   Java: das Plugin, seine Routen
  backend/src/test/java/app/dispofold/plugins/<id>/   seine Backend-Tests
  frontend/index.js                sein Front End (Seiten, Einstellungen, Import-Formular, Texte)
  docs/<seite>.md                  seine Hilfeseiten
  dev/mock.mjs                     seine Routen für den Entwicklungsserver (optional)
```

Die `id` besteht aus Kleinbuchstaben und Ziffern und beginnt mit einem Buchstaben; der Ordner heißt wie sie. Die
`version` ist die der Erweiterung, unabhängig von der Version von Dispofold. `testOnly: true` hält eine
Erweiterung aus allen ausgelieferten Builds.

Gebaut wird sie mit dem Rest:

- **Backend:** Das Maven-Modul bleibt eines. `build-helper-maven-plugin` in `backend/pom.xml` nimmt die Quellen
  und `plugin.json` jeder Erweiterung auf, eine ausgelieferte mit `add-source` und `add-resource`, eine mit
  `testOnly` nur mit `add-test-source` und `add-test-resource`. `plugin.json` landet unter
  `META-INF/dispofold-plugins/<id>/plugin.json`. Eine ausgelieferte Erweiterung braucht außerdem
  `COPY plugins/<id> …` in `backend/Dockerfile` und `frontend/Dockerfile`.
- **Front End:** `frontend/scripts/build.mjs` bündelt `frontend/index.js` jeder Erweiterung als Modul
  `dispofold:plugins` und baut ihre Hilfeseiten in die eingebaute Anleitung. Erweiterungen mit `testOnly` nur mit
  `--test-plugins --out <ordner>`, nie nach `dist/`.
- **Website:** Der MkDocs-Hook `tools/docs/hooks.py` stellt die Hilfeseiten als
  `anleitung/plugin-<id>-<seite>.md` unter „Anleitung“ ein, nach den Seiten des Kerns.

## Regeln

- **Der Kern kennt keine Erweiterung.** Nichts unter `backend/src/main/java` und `frontend/src` verweist auf
  `plugins/` oder `app.dispofold.plugins`.
- **Eine Erweiterung nutzt nur die Plugin-API.** Java: `app.dispofold.plugin.api`, das Datenmodell
  `app.dispofold.model`, dazu `java`, `jakarta`, Jackson und MicroProfile Config. Front End: nur
  `frontend/src/lib/plugin-api.js` und eigene Dateien.
- **Routen** liegen unter `/api/plugins/<id>`, jede mit `@RolesAllowed("planner")` oder, was die Rolle „nur
  lesen“ sehen darf, `@RolesAllowed({"planner", "viewer"})`. Ohne Annotation ist eine Route gesperrt. Personendaten
  für Leser gibt eine Erweiterung so wenig heraus wie der Kern (siehe [API](api.md#leser-rolle)).
- **Keine eigenen Styles.** Eine Erweiterung nutzt die Elemente und Klassen des Kerns (`section`, `field`,
  `toolbar`, `btn`, `alert`, `muted`, …); CSS-Dateien und Stylesheets im Code weist der Build ab.
- **Texte** sind deutsch und heißen `plugin.<id>.…`, Fehlertexte `error.plugin.<id>.…`. Ein Text des Kerns oder
  einer anderen Erweiterung lässt sich nicht ersetzen.
- **Fehler** meldet das Backend mit `PluginErrors.error(status, "plugin.<id>.<fall>", params, ersatztext)`.
  Parameter enthalten nur Ids, Zahlen und Datumsangaben, nie Namen, Pfade oder Meldungen von Ausnahmen.
- **Keine echten Daten** im Repository, auch nicht in den Tests einer Erweiterung (siehe
  `scripts/check-repo.sh`).

`backend/src/test/java/app/dispofold/PluginArchitectureTest.java` und `frontend/test/plugins.test.js` prüfen diese
Regeln; der Build bricht sonst.

## Backend

Das Plugin ist eine CDI-Bean, die `Plugin` umsetzt:

```java
@ApplicationScoped
public class BeispielPlugin implements Plugin {
    @Override
    public String id() {
        return "beispiel";
    }
}
```

| Erweiterungspunkt | So geht es | Bewiesen von |
|---|---|---|
| Ein und aus | `PUT /api/extensions/<id>`; aus heißt: jede Route `404` `plugin.unknown` | `BeispielPluginTest.listedWithItsManifestAndOffByDefault`, `HiddenPluginTest` |
| Routen | JAX-RS-Ressource unter `/api/plugins/<id>` | `BeispielPluginTest.routesFollowTheRolesOnceOn`, `readersSeeOnlyViewerRoutesAndNothingToSwitch`, `anonymousGets401EvenWhenOff` |
| Einstellungen | `PluginHost.settings(id)` und `saveSettings(id, json)`; `settingsVersion()` und `migrateSettings(von, json)` | `BeispielPluginTest.settingsAreMigratedValidatedAndKeptWhenSwitchedOff` |
| Import | `importSource()` liefert eine `ImportSource`, deren `load(anfrage)` einen `Dataset` und die angewendeten Korrekturen | `BeispielPluginTest.importSourceGoesThroughTheImportPath` |
| Korrekturdatei | `PluginHost.corrections()`: die kumulierte Korrekturdatei, leer ohne gespeicherten Import | ebenda |
| Dateien | `PluginHost.files().resolve(ordner, pfad)` | `BeispielPluginTest.filesStayInsideTheAllowedFolders`, `PickedRootTest` |

**Einstellungen** sind JSON, je Erweiterung eine Zeile in der Tabelle `plugin_state`, mit der Version ihrer Form.
Liest die Erweiterung sie und ist die gespeicherte Version kleiner als `settingsVersion()`, ruft der Kern
`migrateSettings` (Version 0: noch nie gespeichert, ein leeres Objekt) und speichert das Ergebnis. Ausschalten
behält sie.

**Import:** Der Kern stellt `POST /api/import/plugin/<id>` und `…/preview` bereit; der Body ist die Anfrage des
Import-Formulars der Erweiterung. Der Datensatz läuft durch denselben Weg wie ein hochgeladener: Prüfung, Vorschau,
`keep=true` mit Konflikten, Stand des Imports, `appliedCorrections`. Vorschau und Import rufen `load` je einmal;
für dieselbe Anfrage und dieselben Dateien muss es denselben Datensatz liefern. Wendet die Quelle die
Korrekturdatei selbst an, gibt sie sie als `appliedCorrections` mit.

**Dateien:** Lesen darf eine Erweiterung nur in freigegebenen Ordnern: am Server die aus
`DISPOFOLD_PLUGIN_ROOTS`, im Programm am eigenen Rechner die, die jemand im Ordnerdialog gewählt hat. `resolve`
gibt den echten Pfad zurück oder antwortet `404` `files.notFound`: für absolute Pfade, `..`, symbolische Links
nach außen und fehlende Dateien. Den Ordnerdialog öffnet das Programmfenster (`POST /__shell/pick-folder`); es gibt
den Ordner selbst frei, mit einer Kopfzeile, die keine Seite senden kann. Gewählte Ordner bleiben freigegeben.

## Front End

`frontend/index.js` exportiert ein Objekt (alle Beschriftungen sind Textschlüssel):

```js
import { folderPicker, guarded, h, info, t } from '../../../frontend/src/lib/plugin-api.js';

export default {
  id: 'beispiel',
  texts: { 'plugin.beispiel.group': 'Beispiel', /* … */ },
  group: { label: 'plugin.beispiel.group' },  // eigener Menüeintrag; oder die Id einer Gruppe des Kerns
  pages: [{ path: 'start', tab: '…', intro: '…', help: 'beispiel', viewer: true, render(root, ctx, params) {} }],
  settings: { render(root, ctx) {} },        // unter Einstellungen → Erweiterungen, solange eingeschaltet
  importSource: { label: '…', render(root, ctx) { return () => anfrage; } },  // auf der Seite Daten
  help: [{ page: 'beispiel', title: 'Beispiel-Erweiterung' }],
};
```

| Erweiterungspunkt | Wo | Bewiesen von |
|---|---|---|
| Menüeintrag | eigene Gruppe oder als Reiter einer Gruppe des Kerns | `frontend/scripts/check-plugins.mjs`, `frontend/test/plugins.test.js` |
| Seiten | `#/plugin/<id>/<seite>`, mit Einleitung und Link in die Hilfe; `viewer: true` auch für „nur lesen“ | ebenda |
| Einstellungen | unter dem Eintrag der Erweiterung in **Einstellungen → Erweiterungen** | ebenda |
| Import | eigener Abschnitt auf der Seite **Daten**; das Formular liefert die Anfrage, der Rest ist der Import des Kerns | ebenda |
| Ordner | `folderPicker(ctx, { id, files })`: freigegebene Ordner und darunter, am eigenen Rechner mit Ordnerdialog | ebenda, auch im Desktop-Modus |
| Hilfeseiten | `docs/<seite>.md`, in der Anleitung als `plugin-<id>-<seite>`, nach den Seiten des Kerns | ebenda; die Website im CI-Job `docs` |
| Texte und Styles | Texte unter `plugin.<id>.`, keine eigenen Styles | `frontend/test/plugins.test.js`, `scripts/build.mjs` |

`ctx` ist derselbe wie für die Seiten des Kerns: `ctx.api` mit `ctx.api.plugin('<id>').get/put/post(pfad)` für die
eigenen Routen, `ctx.canEdit`, `ctx.me`, `ctx.month` und die übrigen. Hilfeseiten sind so geschrieben, als
lägen sie in `docs/anleitung/`: Links auf `daten.md` oder `erweiterungen.md#einschalten-und-ausschalten` gehen
auf die Seiten des Kerns. Bilder in Hilfeseiten von Erweiterungen kann der Build noch nicht.

## Prüfen

```bash
cd backend && ./mvnw test -Dtest='PluginArchitectureTest,BeispielPluginTest,HiddenPluginTest,PickedRootTest'
cd frontend && npm test && npm run check:plugins
DISPOFOLD_DOCS_TEST_PLUGINS=1 mkdocs build --strict -d /tmp/site-plugins
scripts/check-no-test-plugins.sh frontend/dist
```

`check:plugins` baut das Front End mit den Test-Erweiterungen nach `frontend/dist-plugins/` und prüft es im
Browser am Entwicklungsserver (`MOCK_TEST_PLUGINS=1`), am Server und im Desktop-Modus.
`scripts/check-no-test-plugins.sh` prüft, dass ein Build keine Erweiterung mit `testOnly` enthält; die CI ruft es
für `frontend/dist` und das Linux-Paket auf.
