# Änderungen

Jede Version steht auch als Release auf GitHub, mit Images, Installationsdateien und Prüfsummen. Installation
und Update: [docs/betrieb.md](docs/betrieb.md).

## 0.23.0 (2026-11-23)

- **Anleitung für Einsteiger:** Neu vorn in der Anleitung und oben auf der Startseite der
  [Schnellstart in 10 Schritten](docs/anleitung/schnellstart.md): ein Probemonat mit den Demodaten, eine Handlung
  und ein ganzes Bild je Schritt, „So sieht es aus, wenn es geklappt hat“. Neu sind auch [Was die Zahlen
  bedeuten](docs/anleitung/zahlen.md) (Zeile über dem Plan, Prüfen, Fairness, Seite einer Person: was ein Fehler ist
  und was nicht) und [Wie das Programm plant](docs/anleitung/wie-plant.md) (bisher „So funktioniert es“ im
  Überblick). Der Überblick beginnt mit dem typischen Monat, Erste Schritte mit „Welche Form habe ich?“ (Abmelden am
  Server, Beenden am eigenen Rechner) und nennt den Weg aus dem bisherigen Planungsprogramm. Ein Monat Schritt für
  Schritt nennt Person und Tage der Krankmeldung, das seitliche Scrollen, „Zum ersten Konflikt springen“, einen Weg,
  wenn niemand passt, und ein bis zwei Minuten Planung; vor jedem Ausschnitt steht die ganze Seite. Die häufigen
  Fragen erklären Timefold, Hunderte Hinweise unter Prüfen bei einem grünen Plan, Überbesetzung in Personenstunden
  und Dienste, die neu planen verschoben hat (vorher „Plan fixieren“). Die Begriffe erklären Bewertung, Abzüge der
  Planqualität, Durchrechnung, Eingaben, Personenstunden, die Namen der Qualitätsregeln und die Abwesenheitscodes im
  Plan. Nachschlageseiten markieren, was man für den Anfang überspringen kann. Die eingebaute Anleitung zeigt die
  Hilfe einer Erweiterung auch, solange sie ausgeschaltet ist.
- **Der erste Start:** Ohne Daten zeigt der Dienstplan „Noch keine Daten“ mit **Demodaten zum Ausprobieren laden**
  (wechselt gleich zum Monat der Demodaten), **Datensatz importieren** und dem Weg zu den Erweiterungen statt einer
  leeren Tabelle; **Planen** ist bis dahin grau, der Status heißt „noch kein Plan“. Die Seite einer Person zeigt
  vor dem Planen nur, was ohne Plan feststeht, sagt „Noch kein Plan für …“ und nennt neben Ziel und Soll den Grund,
  wenn beide sich unterscheiden.
- **Prüfen in der Reihenfolge der Arbeit:** Oben steht ein Urteil („Nichts zu tun: 0 Regelverstöße, 0 Lücken“ oder
  „Zuerst: …“, auch „neu planen“ bei einem veralteten Plan), dann die Regelverstöße, der offene Bedarf, die
  **Hinweise zur Qualität (keine Fehler)** zugeklappt und die Stammdaten; jede Gruppe ist zugeklappt.
- **Klare Worte:** Statt „Score“ steht die **Bewertung** in Worten unter **Erweitert** („Regelverstöße 0, fehlende
  Besetzung 0 Personenstunden, …“, die Rohzahl im Tooltip), statt „Strafe“ überall „Abzug“. Die Zeile über dem Plan
  vermerkt bei Überbesetzung, einzelnen freien Tagen und den Knöpfen der Qualitätsregeln „kein Fehler“; die
  Fairness nennt über den Streifen, wer auffällt („Fällt auf“); das Fenster vor dem Monatsabschluss sagt, was danach
  gilt und wo man wieder öffnet.
- **Eingaben:** Links zu Abwesenheiten, Wünschen und Vorgaben unter dem Titel, **Speichern** in einer Leiste, die
  unten stehen bleibt, eine neue Zeile markiert („Hinzugefügt, noch nicht gespeichert“) und ins Bild gerückt, die
  Zahl der Wünsche mit der echten Grenze, das Feld Schicht nur bei Wunscharten, die eine brauchen.
- **Krankmeldung nach dem Planen:** **Plan fixieren** neben **Fixierungen lösen** fixiert alle Dienste und
  Ersatzruhetage des Monats (freie Tage bleiben offen, neu planen füllt Lücken dann nur dort); **Zum ersten Konflikt
  springen** oder **Zur ersten Lücke springen** führt auch seitlich zur Zelle; über **Auf Anfrage verfügbar** steht,
  was ein Klick tut, und die Stunden heißen „geplant … h von Ziel … h“.
- **Anleitung überall offline:** **Anleitung** und „Mehr dazu in der Anleitung“ öffnen in jeder Form die
  eingebaute Anleitung dieser Version, auch am Server und für die Rolle „nur lesen“; die Website steht nur noch als
  Link unter jeder Seite. Fehlt eine Seite, sagt das Programm es deutlich.
- **Kleinere Änderungen der Oberfläche:** sichtbares Feld „Planungsmonat“, Dateifelder ganz zu sehen, die Seite
  einer Person mit eigenem Seitentext, „= Mindestens“ bei Bedarf ohne Höchstbesetzung, jedes Filterfeld der
  Stammdaten nach seiner Tabelle benannt, auf der Zeitachse die Besetzung über den Diensten, die Rolle „nur lesen“
  ohne Knöpfe der Qualitätsregeln. Auf Telefonbreite klappt das Menü hinter einen Knopf (☰), und die Seite scrollt
  nicht seitlich. Oberfläche und Anleitung sagen **Code** bei Bereichen, Schichten und Abwesenheitsarten; der Knopf unter
  **Pseudonymisieren** heißt **Pseudonymisierte Kopie erstellen**; die Obergrenze der Monatsstunden steht als
  Prozent über dem Soll (beim festen Anteil über dem Ziel); gleitende Durchrechnung nennt mindestens 7 Wochen; ein Import in einen abgeschlossenen Monat nennt den
  nächsten Schritt.
- **Import-Erweiterung 1.3.0:** Probleme der Zuordnung und ungültige Einstellungen in deutschen Worten („Es ist
  noch kein Bereich eingetragen.“), „Die Zuordnung fehlt“ führt mit **Zur Zuordnung** gleich dorthin,
  Seitentitel wie ihre Reiter, ein Ort für den Import (**Lauf und Import**, nicht mehr auch unter Daten),
  Monatsfelder nicht mehr abgeschnitten, ein abgeschlossener Monat mit dem nächsten Schritt. Neue Hilfeseite für den
  Anfang, Schritt für Schritt mit Bildern: [Echte Daten übernehmen](plugins/sp5/docs/echte-daten.md); Vergleich und Hinweise
  für Entwickler auf einer Seite [Technik](plugins/sp5/docs/technik.md), die beide gewollten Unterschiede nennt
  (Zyklen, und Nachtschichten standardmäßig nicht als fair zu verteilen markiert).
- **Anleitung und Dokumentation an den Code angeglichen:** Die Planungsregeln rechnen einen Historienmonat beim
  Kalenderhalbjahr mit seinen Tagen / 7 (365,25 / 12 / 7 Wochen nur gleitend), das Beispiel zur eigenen Grenze ergibt
  38,46 h, die Höchststunden im Monat stehen unter „Stunden treffen“, und gleitend mit 1 bis 6 Wochen wird der
  Wochenschnitt nie zum Regelverstoß (jetzt beschrieben: mindestens 7 Wochen eintragen). Wochenstunden sind immer
  größer als 0; der Fall „ohne Wochenstunden“ ist aus Regeln, Hilfe und Änderungen entfernt. Die zwei freien
  Sonntage sind ein Standardwert, einstellbar. Die Listen der Regelverstöße nennen den Wochenschnitt mit der eigenen
  Grenze und den freien Tag mitten in einer Abwesenheit; Historie und Monatsabschluss nennen gearbeitete Stunden und
  Wochenstunden; die Kachel Wochenschnitt zeigt die eigene Grenze; das Ziel gleicht das Stundenkonto standardmäßig
  bis Ende des Halbjahrs aus. Die Schnittstelle beschreibt das Laden einer Korrekturdatei (`/api/data/corrections/state`,
  `check`, `loaded`) mit den Codes `corrections.load.*`. Repository und Images sind öffentlich (Betrieb).
- **Ein Begriff je Sache:** In der Anleitung heißt das Zeichen einer Schicht, eines Bereichs oder einer
  Abwesenheitsart **Code**, das Namenskürzel einer Person **Kürzel**; dazu **Pseudonymisieren**, **Regeln mit
  Treffern** und die drei Abschnitte **Erweitert** in den Begriffen. Regeln, Schnittstelle und Datenformat sagen
  „Bewertung“ und „Abzug“ wie die Oberfläche; Messungen in den Regeln, die noch mit den früheren Demodaten
  entstanden, sind als solche vermerkt.
- **Demodaten:** Keine Schicht ist mehr „Fair verteilen“ angekreuzt, auch keine Nacht; Nächte haben ihre eigene
  Fairness-Regel. Die Abwesenheitsart Dienstreise heißt jetzt „REI“ statt „DR“, das auch der Bereich Disposition
  Rettung trägt. Pläne der Demodaten fallen deshalb etwas anders aus.

## 0.22.0 (2026-11-23)

- **Zuordnung vorschlagen (Import-Erweiterung):** Auf der Seite Zuordnung schlägt Dispofold aus Gruppen,
  Mitgliedern, Bedarf und Diensten des Ordners Einträge vor, für alles, was die Zuordnung noch nicht trifft: neue
  Bereiche aus Gruppen mit eigenen Schichten, Schichtregeln (der Bedarf im bisherigen Planungssystem zuerst, sonst
  wer die Dienste gemacht hat; unter dem Anteil von 80 % die zwei stärksten Bereiche zur Wahl), Gruppenregeln,
  Sammelgruppen, Gruppen ohne Dienste, ausgeblendete und seltene Schichten zum Ignorieren und die Ersatzruhe. Jeder
  Vorschlag nennt seinen Grund mit Zahlen; übernommen wird er in den Editor, gespeichert erst mit „Zuordnung
  speichern“. Bestehende Einträge bleiben unverändert, verworfene Vorschläge kommen bis zum Zurücksetzen nicht
  wieder. Anteil, seltene Schichten, Zeitraum und die Wörter für die Ersatzruhe sind Einstellungen. Die Übersicht
  nennt nach einem Lauf die Zahl der neuen Vorschläge. Import und Export rechnen unverändert. Hilfe:
  [Zuordnung](plugins/sp5/docs/zuordnung.md).
- **Stundenkonto bis Ende des Halbjahrs ausgleichen (neuer Standard):** Das Ziel eines Monats ist das Soll minus
  Stundenkonto geteilt durch die Monate, die im Kalenderhalbjahr noch bleiben (Jänner ein Sechstel, Juni der
  ganze Rest), damit das Konto am Halbjahresende bei 0 steht. Im Jänner und Juli beginnt das Konto bei 0: ein Plus
  wird außerhalb des Plans abgerechnet, ein Minus verfällt oder kommt mit (Einstellung „Minus ins nächste
  Halbjahr mitnehmen“, Standard aus). Die Personenseite und die Kennzahlen zeigen den Stand am Ende des vorigen
  Halbjahrs. Das Ziel bleibt zwischen 0 und Soll plus der Toleranz der Obergrenze; ein Rest, der nicht hineinpasst,
  ist ein Hinweis auf der Seite Prüfen, kein Regelverstoß. Der bisherige feste Anteil je Monat (25 %) bleibt als
  Alternative und gilt immer bei gleitender Durchrechnung. Erklärt mit einem Beispiel über sechs Monate in
  [Regeln einstellen](docs/anleitung/einstellungen.md).
- **Gutschrift einer Abwesenheit:** Je Abwesenheitsart lässt sich wählen, womit ein Tag angerechnet wird:
  Wochenstunden / 5 an Werktagen (Standard) oder die Stunden des geplanten Dienstes, den die Abwesenheit ersetzt
  hat, an jedem Wochentag (ohne Dienst nichts). Dispofold merkt sich den Dienst, wenn eine Abwesenheit in einen
  schon geplanten Monat eingetragen wird (neues, optionales Feld `replacedHours` im Datenformat; `credit` an der
  Abwesenheitsart). Ohne diese Angabe gilt Wochenstunden / 5, und die Personenseite sagt es. Ein Import lässt die
  in Dispofold gewählte Gutschrift stehen.
- **Hinweis zum Update:** Der neue Ausgleich gilt auch für schon gerechnete, offene Pläne, ohne dass sie als
  veraltet markiert werden; Ziele und Obergrenzen können sich dadurch ändern. Offene Monate nach dem Update prüfen
  und bei Bedarf neu planen. Abgeschlossene Monate behalten ihren gespeicherten Kontostand.
- **Mac mit Apple Silicon und Linux auf ARM:** Neu im Release sind das dmg für Macs mit M-Prozessor, die
  Linux-Pakete für arm64 (`Dispofold-<version>-linux-arm64.deb`, `Dispofold-<version>-linux-aarch64.rpm`, mit
  eigener Java-Laufzeit für aarch64) und die Docker-Images für arm64 (in der Registry unter demselben Namen wie
  für amd64, dazu das Archiv `dispofold-<version>-images-arm64.tar.gz`). Jedes Paket wird vor dem Hochladen auf
  einem Runner seiner Plattform installiert und mit dem Selbsttest des Programms gestartet. Unsigniert wie unter
  Windows; wie man es beim ersten Start öffnet, steht in [docs/betrieb.md](docs/betrieb.md#unsignierte-programme).
- **Vorschläge gegen die eigene Zuordnung prüfen:** Das Vergleichsskript der Erweiterung zeigt mit `--vorschlag`
  und der ZIP-Datei aus „Ergebnis speichern“, wie nah der Vorschlag an die eigene Zuordnung kommt, nur als Anzahlen
  ([Technik und Vergleich](plugins/sp5/docs/technik.md#vorschläge-gegen-die-eigene-zuordnung)).

## 0.21.0 (2026-11-23)

- **Wochenschnitt je Person:** Die Regel zum Durchschnitt der Wochenstunden misst jede Person an ihrem eigenen
  Vertrag: Grenze sind die Wochenstunden plus ein Spielraum, neu auf der Seite Regeln (Standard 10 %, 0 erlaubt),
  höchstens 46,16 h. Eine Person mit 20 h hat so 22 h, mit 40 h 44 h.
  Ändern sich die Wochenstunden im Durchrechnungszeitraum, zählt jeder Monat mit seinen eigenen, gewichtet mit
  seinen Wochen; der Monatsabschluss schreibt dafür die Wochenstunden in die Historie (neues, optionales Feld
  `weeklyHours`). Die Personenseite und die Erklärung eines Verstoßes nennen die eigene Grenze. 12 h je Dienst und
  60 h je Woche gelten weiter für alle gleich.
- **Durchrechnung je Kalenderhalbjahr:** Der Wochenschnitt zählt standardmäßig die Monate des Kalenderhalbjahrs
  (Jänner bis Juni, Juli bis Dezember) bis zum geplanten Monat, wie bei der üblichen Abrechnung je Halbjahr; die
  gleitende Durchrechnung über eine Zahl Wochen (Standard 52) lässt sich auf der Seite Regeln wählen.
- **Frei mitten in einer Abwesenheit:** Ein Feiertag, Samstag oder Sonntag, an dem der nächste Werktag davor und
  danach eine Abwesenheit hat, die das Soll senkt (Urlaub, Krankenstand, Zeitausgleich usw.), bekommt keinen Dienst
  mehr, etwa der Feiertag am Mittwoch in einer Urlaubswoche oder das Wochenende zwischen zwei Urlaubswochen; ebenso
  ein Feiertag, wenn alle anderen Werktage seiner Woche so eine Abwesenheit haben (Pfingstmontag, Urlaub Dienstag
  bis Freitag). Am Monatsrand zählen die eingetragenen Abwesenheiten des Nachbarmonats. Eine Vorgabe an so einem
  Tag bleibt und wird als Regelverstoß gemeldet; Soll und Stundenkonto ändern sich nicht. Abschaltbar auf der Seite
  Regeln.
- **Hinweis zum Update:** Die neuen Standards (eigene Grenze, Kalenderhalbjahr, freie Tage mitten in einer
  Abwesenheit) gelten auch für schon gerechnete Pläne, ohne dass diese als veraltet markiert werden. Eine Vorgabe
  an einem jetzt freien Tag oder ein Schnitt über der eigenen Grenze erscheint in der Analyse als Regelverstoß;
  nach dem Update offene Monate prüfen und bei Bedarf neu planen.
- **„Fair verteilen“:** Das Häkchen an einer Schicht heißt **Fair verteilen**: Dienste dieser Schicht werden über
  die Fairness gleichmäßig auf alle verteilt, die sie machen können; das Wort, das eine Schicht bewertete, ist
  überall ersetzt. Die Kennzahlen heißen „fair verteilte Dienste“. Im Datenformat bleibt das Feld `unpopular`, die
  Regel `fairUnpopular`.
- **Keine automatische Markierung von Nachtschichten:** In der Zuordnung der Import-Erweiterung heißt eine
  Schichtregel ohne Angabe jetzt „nicht fair verteilen“; Nächte haben ihre eigene Fairness-Regel. Die neue Option
  **Nachtschichten automatisch fair verteilen** (Standard aus) stellt das alte Verhalten wieder her. Eine Regel mit
  „ja“ oder „nein“ gilt wie bisher. Hilfe: [Einstellungen der Erweiterung](plugins/sp5/docs/sp5.md).
- **Prüfen → Stammdaten:** Die Meldung zu Bedarf, für den niemand eingeplant werden kann, kennt die freien Tage
  mitten in einer Abwesenheit.
- **Planungsregeln:** Die Doku nennt, was der KV zur Durchrechnung, zu Teilzeit und zum Ersatzruhetag für
  Feiertagsarbeit sagt, und korrigiert die offenen Punkte dazu. Hilfe: [Planungsregeln](docs/regeln.md).

## 0.20.0 (2026-11-23)

- **Seiten der Import-Erweiterung:** Die Erweiterung für das bisherige Planungssystem hat eine eigene Menügruppe
  mit vier Seiten, so dass der ganze Weg in Dispofold geht:
  - **Übersicht:** den Export für einen oder mehrere Monate ausführen, ohne zu importieren, mit Optionen nur für
    diesen Lauf, seine Zahlen ansehen und genau diesen Lauf importieren, mit Vorschau und „Änderungen behalten“.
  - **Bericht:** die Zeilen des Berichts nach Art, mit Erklärung, Suche, Filtern und Sortieren, Links auf die
    Personen, und die Warnungen des Laufs; der Bericht als CSV bleibt.
  - **Zuordnung:** ein Editor für die Zuordnung mit derselben Prüfung und denselben Meldungen wie der Export,
    der für jede Schicht, Gruppe und Abwesenheitsart des Ordners zeigt, welche Regel gilt und was ohne Regel
    bleibt; Laden und Speichern als `mapping.json` bleiben.
  - **Vergleich:** der Lauf gegen den letzten Import oder eine frühere Datensatz-Datei, mit und ohne Korrekturen:
    Personen neu, entfernt und geändert (je Feld, mit den Einschränkungen), Dienste je Monat und Bereich und
    geänderter Bedarf.

  Hilfe: [Ablauf](plugins/sp5/docs/ablauf.md) vom ersten Start bis zum Import und eine Seite je Seite, mit
  Bildern.
- **Korrekturdatei laden:** Unter **Daten → Korrekturdatei** lässt sich eine vorhandene Korrekturdatei laden, etwa
  beim Umstieg von einem Exportprogramm, das mit Korrekturdatei lief. Dispofold prüft sie wie das Exportprogramm und
  zeigt vor dem Speichern, was sie bringt und was nicht wirkt. Der nächste Import über eine Importquelle wendet sie
  an, als hätte ein früherer Import sie angewendet; Änderungen in Dispofold seit dem letzten Import gewinnen je
  Person und Feld. Das geht auch auf einer leeren Datenbank. Gibt es gar keine Korrekturen, sagt ein Hinweis über
  dem Import das. Die CI vergleicht den Import nach dem Laden Byte für Byte mit dem Exportprogramm und derselben
  Datei. Hilfe: [Korrekturdatei laden](docs/anleitung/daten.md#korrekturdatei-laden).
- **Prüfen → Stammdaten:** Neue Meldung, wenn für Bedarf eines Bereichs an Tagen des Monats niemand eingeplant
  werden kann (niemand hat den Bereich, alle sind ausgeschlossen, abwesend oder dürfen keine passende Schicht), mit
  Link auf die Zeile unter **Stammdaten → Bedarf**.
- **Import auf der Übersicht der Erweiterung:** Die Vorschau eines weiteren Imports nennt die Personen mit ihren
  aktuellen Kurznamen.
- **Für Entwickler:** `PluginHost.corrections()` liefert auch die geladene Korrekturdatei;
  `PluginHost.lastImport` (die Stammdaten des letzten Imports und die Monate, wie Dispofold sie
  hat), `importFlow` (der Import des Kerns auf der Seite einer Erweiterung), `sortableTable` und Texte und
  Formatierungen des Kerns über `plugin-api.js`; Hilfeseiten von Erweiterungen können Bilder zeigen
  ([docs/plugins.md](docs/plugins.md)).

## 0.19.0 (2026-11-22)

- **Import aus dem bisherigen Planungssystem als Erweiterung:** Die erste ausgelieferte Erweiterung liest dessen
  Datenordner (nur lesend) und importiert Stammdaten, Abwesenheiten, Historie, Feiertage und Bedarf über die Seite
  **Daten**, mit Vorschau und „Änderungen behalten“. Sie ist ein Port des bisherigen Exportprogramms nach Java
  und liefert dieselben Dateien Byte für Byte; die CI vergleicht das bei jeder Änderung. Die Korrekturdatei kommt
  aus Dispofold selbst. Einstellungen (Ordner, Zuordnung, Optionen) unter **Einstellungen → Erweiterungen**.
  Hilfe: [plugins/sp5/docs/sp5.md](plugins/sp5/docs/sp5.md); Vergleich mit echten Daten:
  [plugins/sp5/docs/technik.md](plugins/sp5/docs/technik.md#vergleich-mit-echten-daten).
- **Betrieb:** Der Datenordner wird am Server wie andere Ordner schreibgeschützt eingebunden und freigegeben
  ([docs/betrieb.md](docs/betrieb.md#erweiterungen)).
- **Erweiterungen:** Eine Erweiterung ohne eigene Seite (nur Einstellungen, Import oder Hilfe) ließ sich nicht
  einschalten; jetzt geht das.
- **CI:** Der Maven des Wrappers liegt im Cache, und sein Download wartet zwischen den Versuchen länger.

## 0.18.0 (2026-11-21)

- **Erweiterungen (Plugins):** Dispofold kann Erweiterungen mitbringen, die eigene Seiten, Einstellungen, einen
  Import und Hilfeseiten haben und so aussehen wie der Rest des Programms. Jede ist aus, bis die Planung sie unter
  **Einstellungen → Erweiterungen** einschaltet; Ausschalten behält ihre Einstellungen. Die Seite gibt es jetzt
  auch am Server. Diese Version enthält noch keine Erweiterung; die Seite sagt das. Anleitung:
  [Erweiterungen](docs/anleitung/erweiterungen.md), Entwicklung: [docs/plugins.md](docs/plugins.md).
- **Betrieb:** `DISPOFOLD_PLUGINS_HIDDEN` blendet eine Erweiterung am Server ganz aus, `DISPOFOLD_PLUGIN_ROOTS` gibt
  Ordner frei, die Erweiterungen lesen dürfen (schreibgeschützt eingebunden, siehe
  [docs/betrieb.md](docs/betrieb.md#erweiterungen)); ohne Eintrag keiner. Im Programm am eigenen Rechner wählt man
  den Ordner im Ordnerdialog.
- **Für Entwickler:** Plugin-API im Backend (`app.dispofold.plugin.api`) und im Front End
  (`frontend/src/lib/plugin-api.js`), eine Beispiel-Erweiterung nur für Tests, die jeden Erweiterungspunkt
  beweist, und Architekturtests, die den Kern frei von Erweiterungen halten.

## 0.17.0 (2026-11-21)

- **Einzelne freie Tage:** zählen nur noch echte freie Tage. Ein einzelner Abwesenheits- oder Ersatzruhetag
  zwischen zwei Diensten ist Vorgabe, die die Planung nicht verschieben kann, und kostet nichts mehr.
- **Anleitung in Seiten je Aufgabe:** [docs/anleitung/](docs/anleitung/index.md) statt einer langen Seite, auf der
  Website unter „Anleitung“: Überblick, Erste Schritte (Server und Programm am eigenen Rechner), Personen,
  Bereiche, Schichten und Bedarf, Abwesenheiten, Wünsche und Vorgaben, Planen und Prüfen, Plan bearbeiten,
  Auswertungen, Monat abschließen, Daten und Korrekturdatei, Regeln einstellen, Updates, Hinweise für die Rolle
  „nur lesen“. Jede Seite beschreibt jede Einstellung mit Standardwert und wann man sie ändert.
- **Ein Monat Schritt für Schritt:** ein durchgespieltes Beispiel mit den Demodaten, vom Import über eine
  Krankmeldung nach dem Planen bis zum Abschluss und zur Korrekturdatei.
- **Häufige Fragen und Fehlerbehebung:** die Meldungen des Programms (Anmeldung, Import, Konflikte beim Speichern,
  Planen, Monatsabschluss, Programm am eigenen Rechner, Updates) mit Ursache und was zu tun ist. **Begriffe:** die
  Wörter des Programms von A bis Z.
- **Bilder:** Jede Seite der Anleitung zeigt ihre Ansichten und Dialoge, wo es hilft mit nummerierten Markierungen.
  Alle Bilder kommen aus `frontend/scripts/screenshots.mjs` (synthetische Demodaten), verkleinert mit
  `scripts/optimize-png.py`; neu sind unter anderem der Schalter „Aus dem Quellsystem nicht mehr übernehmen“, die
  Korrekturdatei und der Hinweis auf eine neue Version.
- **Eingebaute Anleitung im Programm am eigenen Rechner:** zeigt dieselben Seiten mit Links zur Übersicht und zur
  vorigen und nächsten Seite; „Mehr dazu in der Anleitung“ führt am Server und am eigenen Rechner zur passenden
  Seite und zum Abschnitt. Ältere Adressen der Website mit `#abschnitt` landen auf der Übersicht.
- **Regeln:** [docs/regeln.md](docs/regeln.md) nennt zu jeder Regel ein Beispiel, was sie verhindert, und was ihre
  Wichtigkeit in der Praxis ändert (gemessen oder ausdrücklich als nicht gemessen); die Formel der Wünsche ist
  berichtigt.
- **Betrieb:** [docs/betrieb.md](docs/betrieb.md) beschreibt Schritt für Schritt, wie man eine Wiederherstellung
  testet, auf einen neuen Server oder Rechner umzieht, ein Update prüft und zurücknimmt und wo die Protokolle
  liegen.
- **Hilfetexte:** Das **i** beim Bedarf „an einem Datum“ sagt, dass er an diesem Tag allen anderen Bedarf des
  Bereichs ersetzt; das **i** der Familienregeln deutet nicht mehr an, offener Bedarf könne gegen sie abgewogen
  werden.
- **Build robuster gegen Aussetzer von Maven Central:** Maven wiederholt fehlgeschlagene Downloads bis zu 5-mal,
  auch bei 500, 502 und 504, und fragt mit `-U` erneut nach, was ein früherer Lauf nicht gefunden hat
  (`backend/.mvn/maven.config`, gilt für Wrapper, CI und Image-Build). Der Wrapper lädt Maven in CI und Release
  mit bis zu 5 Versuchen (`scripts/fetch-maven.sh`). Das Backend-Image hält das lokale Maven-Repository in einem
  BuildKit-Cache und lädt die Abhängigkeiten mit bis zu 3 Versuchen, statt einen Fehler dabei zu übergehen. Ein
  wirklich fehlendes Artefakt lässt den Build weiterhin scheitern; was gebaut wird, ändert sich nicht.
- **Image-Build in CI und Release fast ohne Anfragen an Maven Central:** Maven Central begrenzt die Anfragen je
  Adresse, und der Image-Build auf einem frischen Runner lud bisher den ganzen Baum. Der Job `compose` und der
  Release-Job `images` füllen jetzt das Maven-Repository des Runners (eigener Actions-Cache, beim ersten Lauf aus
  dem der Maven-Jobs, dann `go-offline` und `package`) und geben es dem Backend-Build als Build-Kontext `m2seed` mit (`.github/compose-maven-seed.yml`,
  `MAVEN_SEED` in `scripts/release/build-images.sh`). Ohne diesen Kontext baut das Image wie bisher und lädt alles
  selbst. Die sonst von der Maven-Version abhängigen Plugins (clean, install, deploy, site, dependency) sind in
  `backend/pom.xml` fest, damit Wrapper und Image dieselben auflösen. Im Image ändert sich nur die in die Anwendung
  eingebettete `pom.xml`.

## 0.16.0 (2026-11-19)

- **Aus dem Quellsystem nicht mehr übernehmen:** neuer Schalter auf der Seite einer Person (nur für die Planung)
  für Personen, die nicht mehr geplant werden sollen, sich aber nicht entfernen lassen, weil sie in
  abgeschlossenen Monaten oder in der Historie vorkommen. Die Person bleibt dort unverändert und wird ab dem
  ersten Tag nach dem letzten abgeschlossenen Monat nicht mehr geplant, wie nach einem Austritt. Die
  Korrekturdatei enthält für sie `"exclude": true`; lässt das Exportprogramm sie im nächsten Datensatz weg,
  behält Dispofold sie beim Import mit ihrer Historie und weiter ausgeschlossen. Ausgeschaltet wird sie wieder
  geplant, und die nächste Korrekturdatei schließt sie nicht mehr aus, auch wenn der Ausschluss aus einer früheren
  Korrekturdatei stammt (dann speichert der Export auch eine Datei ohne Einträge). Ein Ausschluss, den das
  Exportprogramm angewendet hat, zeigt den Schalter eingeschaltet. In der Liste der Personen stehen solche
  Personen grau mit „nicht mehr übernommen“.
- **Datenformat:** neues optionales Feld `excludedFrom` je Person (Version bleibt 2), nur in Datensätzen aus
  Dispofold; das JSON-Schema beschreibt es.
- **REST-Schnittstelle:** `PUT /api/dataset/people/{id}/excluded`; die Korrekturdatei nennt mit `withdrawn` die
  zurückgenommenen Ausschlüsse.

## 0.15.0 (2026-11-18)

- **Korrekturdatei vollständig:** **Änderungen als Korrekturdatei exportieren** schreibt alle geltenden
  Korrekturen: die, die das Exportprogramm beim letzten Import schon angewendet hatte, und die Änderungen seit dem
  Import, je Person und Feld mit dem neueren Wert (Einschränkungen und Bedarf eines Bereichs als Ganzes). Ein auf
  den Wert der Quelle zurückgeänderter Wert bleibt ausdrücklich in der Datei. Die neue Datei ersetzt die
  bisherige `korrekturen.json`; von Hand zusammenführen muss man nichts mehr. Die Meldung nennt, wie viele
  Einträge aus früheren Korrekturen stammen und wie viele seit dem Import geändert wurden.
- **Datenformat:** neuer optionaler Abschnitt `appliedCorrections` im Datensatz (Version bleibt 2): die
  Korrekturen, die die Quelle angewendet hat, im Format der Korrekturdatei. Dispofold speichert ihn mit dem Stand
  des Imports. Fehlt er (ältere Exportprogramme, andere Quellen), enthält die Korrekturdatei wie bisher nur die
  Änderungen seit dem Import, und die Meldung sagt das. Ältere Versionen von Dispofold ignorieren den Abschnitt.
  Das JSON-Schema beschreibt jetzt auch die Korrekturdatei.
- **Import mit "Änderungen aus Dispofold behalten":** Eine Änderung, die der neue Datensatz schon mit demselben
  Wert enthält (etwa über die Korrekturdatei), gilt nicht mehr als Konflikt.

## 0.14.0 (2026-11-18)

- **Windows-Signatur vorbereitet:** Die Windows-Dateien bleiben vorerst unsigniert: Die SignPath Foundation hat
  den ersten Antrag im November 2026 abgelehnt, weil das Projekt noch jung ist; ein neuer Antrag folgt später.
  Der Release-Workflow kann Setup und portables ZIP signieren lassen, bevor die Prüfsummen entstehen; signiert
  werden das Setup und `dispofold.exe` im ZIP. Ohne die SignPath-Einstellungen läuft er wie bisher. Einrichtung,
  Antragstext und Freigabe je Release: [docs/release.md](docs/release.md#windows-signatur-mit-signpath); die
  Artefaktkonfiguration liegt in `.signpath/artifact-configuration.xml`.
- **Code signing policy:** nennt die Dateien, die signiert werden, und dass das Windows-Setup nur dann den
  WebView2-Bootstrapper von Microsoft lädt, wenn WebView2 fehlt, mit den Datenschutzerklärungen von Microsoft und
  GitHub.
- **Metadaten der Desktop-Programme:** Der Copyright-Vermerk nennt die AGPL-3.0 statt „Alle Rechte vorbehalten“
  (Windows-Dateien, deb, rpm, dmg).
- **THIRD_PARTY.md:** dazu die Java-Bibliotheken, die nicht unter Apache 2.0 stehen, die Laufzeitbibliotheken von
  Microsoft in der Java-Laufzeit für Windows, die Plugins des Windows-Setups und Brotli; der WebView2-Bootstrapper
  wird nicht mitgeliefert, sondern bei Bedarf geladen.
- **Betrieb:** Unter [Unsignierte Programme](docs/betrieb.md#unsignierte-programme) steht, wie man die Signatur
  prüft, sobald sie gilt.

## 0.13.0 (2026-11-18)

- **Dokumentations-Website:** `docs/` und `README.md` als Website mit Suche, hell und dunkel, unter
  <https://mschabhuettl.github.io/dispofold/>. Sie wird bei jedem Release neu veröffentlicht und zeigt die
  Dokumentation des neuesten Releases; die CI baut sie bei jedem Push im strengen Modus (fehlende Seiten, Anker
  oder Bilder lassen sie scheitern).
- **Menüpunkt „Anleitung“** für Planung und „nur lesen“, dazu „Mehr dazu in der Anleitung“ im Text unter jedem
  Seitentitel, zum passenden Abschnitt. Am Server führen beide auf die Website (neuer Tab). Das Programm am eigenen
  Rechner zeigt die eingebaute Anleitung genau dieser Version, auch ohne Internet.
- **Personenformular:** Wer nie Nacht macht, bekommt bei der gewohnten Verteilung einen eigenen Hinweis statt des
  Hinweises zur Einstellung der Nachtdienste.
- **Gewohnte Verteilung:** Zählt der Nachtanteil nicht (Nachtdienste „Gleichmäßig“ oder nie Nacht), zeigen die
  Personenseite (Spalte „Gilt (ohne Nächte)“) und das Formular die Anteile, die dann gelten.
- **Personenseite:** Eine Kachel **Stunden** mit geplanten und angerechneten Stunden gegen Ziel und Soll („48,4 h
  (davon 20,4 h angerechnet) von Ziel 112,9 h (Soll 129,2 h)“), wie die Planung sie vergleicht; die eigene Kachel
  „Angerechnet“ entfällt.
- **Anleitung:** wie man in Dispofold neu angelegte Personen und geänderte Namen bei einem neuen Datensatz
  behandelt.
- **Regeln, Fairness:** [docs/regeln.md](docs/regeln.md) erklärt mit Messungen im Demomonat, warum die
  Wichtigkeit „Faire Verteilung“ die Fairness kaum bewegt (kleiner Anteil an der Strafe, geringe Wirkung eines
  einzelnen Dienstes, Historie). Die Gewichte bleiben unverändert.
- **Neue Version verfügbar:** Die Planung sieht oben einen Hinweis, wenn es eine neuere Version gibt, mit den
  Notizen des Release (als Text) und einem Link auf seine Seite. Er lässt sich je Version ausblenden; die Rolle
  „nur lesen“ sieht ihn nie. Vorabversionen zählen nicht.
- **Server:** Das Backend fragt beim Start und einmal am Tag die öffentliche Releases-API von GitHub (eine
  anonyme Anfrage, gesendet wird nichts). Abschalten mit `DISPOFOLD_UPDATE_CHECK=false` in `.env` (neu in
  `docker-compose.yml` und `env.example`). Der Hinweis nennt die Schritte für das Update; der Server aktualisiert
  sich nie selbst.
- **Programm am eigenen Rechner:** Der erste Start ohne gespeicherte Update-Einstellungen, auch nach dem Update auf
  diese Version, fragt einmal „Soll Dispofold automatisch nach neuen Versionen suchen?“. „Ja“ trägt GitHub als
  Update-Quelle ein und sucht bei jedem Start, „Nein“ lässt die Quelle leer. Vorher geht keine Anfrage ins Netz;
  ist schon eine Quelle eingetragen (Adresse oder Ordner), bleibt sie, und das Programm fragt nicht. Der Hinweis
  bietet „Jetzt installieren“. Bisher fragte „Beim Start nach Updates suchen“ die Quelle nicht wirklich ab; jetzt
  tut es das.
- **Codesignatur:** [Code signing policy](docs/code-signing-policy.md) für den Antrag bei der SignPath
  Foundation, verlinkt aus README und Release-Notizen.
- **Bilder:** Screenshots mit Menüpunkt „Anleitung“ und der neuen Stundenkachel; die Desktop-Bilder aus dem echten
  Programmfenster. Die Browser-Checks laufen auf Deutsch wie die Screenshots.

## 0.12.0 (2026-11-17)

Nachträge zur neuen Oberfläche: stärkere Wichtigkeit für die faire Verteilung, „nie Nacht“ geht der gewohnten
Verteilung vor, dazu kleinere Verbesserungen.

- **Wichtigkeit „Faire Verteilung“:** weniger, normal, mehr setzt die Gewichte der Fairness-Regeln jetzt auf das
  0,25-, 1- oder 4-Fache ihres Standards (die anderen Ziele bleiben beim 0,5-, 1- oder 2-Fachen). „Normal“ ist
  unverändert, bestehende Regeln und Pläne ändern sich nicht.
- **Nie Nacht geht vor:** Wer nie Nacht macht (Sperre aller Nachtschichten für jeden Tag oder Familienregel
  „keine Nacht“ an allen Wochentagen), dem zählt der Nachtanteil der gewohnten Verteilung in beiden Einstellungen
  der Nachtdienste nicht; die übrigen Anteile gelten für die Dienste außer Nächten. Personenseite, Fairness und
  Prüfen sagen es. Pläne solcher Personen können sich bei der nächsten Planung ändern.
- **Prüfen:** Der Link „Zeitachse“ bei einer Überbesetzung oder Lücke öffnet die Zeitachse auf Bereich und Tag des
  Eintrags.
- **Hilfe:** eigene Texte je Feld bei Eingaben, gewohnter Verteilung, Familienregeln und den Gesetzeszeilen.
- **Dienstplan:** Einzelne freie Tage und Tage außerhalb der Blocklänge stehen in der Statuszeile nur noch einmal;
  die Zeile „offen“ im Plan nach Funktion zeigt an Tagen mit vielen Lücken einige Zeilen und „+N weitere“ (alle im
  Tooltip und im Ausdruck).
- **Personenformular:** Die Meldung zur gewohnten Verteilung nennt den Bereich mit Namen.
- **Bilder:** Screenshots mit deutschen Datumsfeldern, die Desktop-Bilder mit dem neuen Menü.

## 0.11.0 (2026-11-17)

Neue Gliederung der Oberfläche, eine Seite zum Prüfen, eine Seite je Person, Korrekturdatei und Anonymisieren auf
der Seite Daten, Hilfe auf jeder Seite. Die Planungsergebnisse sind unverändert.

- **Navigation:** fünf Menüpunkte statt neun: Dienstplan (Reiter Personen, Funktionen, Zeitachse), Prüfen
  (Probleme, Fairness), Eingaben, Personen, Einstellungen (Regeln, Stammdaten, Daten, auf dem Desktop Updates).
  Die Monatsauswahl steht nur auf Seiten, die zu einem Monat gehören. Die Rolle Lesen sieht Dienstplan, Personen
  und Fairness.
- **Prüfen:** alle Regelverstöße, Überbesetzungen und auffälligen Qualitätsregeln des Plans, nach Regel, Person
  oder Tag gruppiert und filterbar; der offene Bedarf nach Tag mit „Auf Anfrage verfügbar“; Prüfungen der
  Stammdaten (Personen ohne Bereich oder ohne planbare Schicht, Bereiche ohne Bedarf, unstimmige gewohnte
  Verteilung), jeweils mit dem, was zu tun ist.
- **Fairness:** zeigt die Belastungen, die die Fairness-Regeln vergleichen (Nächte, Wochenenden, Feiertage,
  fair verteilte Dienste je verfügbare Tage, mit den Vormonaten), statt der Umrechnung auf 40 Wochenstunden.
- **Personen:** eine durchsuchbare Liste und eine Seite je Person mit Kennzahlen, „Warum dieser Dienst?“ und einem
  einzigen Formular für Stammdaten, Eintritt und Austritt, gewohnte Verteilung, Einschränkungen und
  Familienregeln. Neu bei den Einschränkungen: je Bereich „ganzer Bereich: keine / nur auf Anfrage / nie“, mit
  „gemischt“, wenn die Schichten verschieden sind. Die Stammdaten enthalten keine Personen mehr; jede Tabelle hat
  ein Suchfeld.
- **Eingaben:** Abwesenheiten werden als Zeitraum eingegeben und angezeigt (eine Zeile je Zeitraum), ein Filter
  nach Person gilt für Abwesenheiten, Wünsche und Vorgaben.
- **Daten:**
  - **Änderungen als Korrekturdatei exportieren:** Was seit dem letzten Import in Dispofold an Personen,
    Einschränkungen und Bedarf geändert wurde, als Korrekturdatei (Version 2) für das Exportprogramm; was die Datei
    nicht ausdrücken kann (etwa neu angelegte Personen), steht als Hinweis daneben.
  - **Import mit „Änderungen aus Dispofold behalten“:** wendet diese Änderungen auf den neuen Datensatz an; eine
    Vorschau zeigt vorher, was übernommen wird und wo auch der neue Datensatz etwas geändert hat (dort gilt der Wert
    aus Dispofold).
  - **Anonymisieren:** Datensatz und Plan des Monats mit Pseudonymen (P001, P002, ...) zum Weitergeben, dazu ein
    getrennter Schlüssel, der bei der Planung bleibt. Findet die Prüfung danach noch einen Namen, wird nichts
    erzeugt.
- **Hilfe:** eine Einleitung auf jeder Seite und ein Hilfeknopf „i“ bei jeder Regel, jedem Gewicht, jeder
  Einstellung, jeder Kennzahl und jedem Formularfeld; mit Maus, Tastatur und Touch bedienbar.
- **Anleitung** neu gegliedert, mit „So funktioniert es“ und „Typischer Ablauf“, neue Bilder.
- **Rolle Lesen:** bekommt die Personalnummer (`externalId`) nicht mehr; die Seite Fairness zeigt ihr die Anzahlen ohne
  Umrechnung und ohne die neuen Belastungen, die Seite einer Person keine gewohnte Verteilung.
- **Schnittstelle:** neu `GET /api/plans/{m}/problems`, `POST /api/import/preview`, `POST /api/import/dataset?keep=true`,
  `GET /api/data/corrections`, `GET /api/data/anonymized?month=`; in den Kennzahlen je Person `weekendLoad`,
  `holidayLoad`, `unpopularLoad` (für die Rolle Lesen leer). Details: [docs/api.md](docs/api.md).
- **Betrieb:** eine Datenbankänderung (V4, der unveränderte letzte Import), die beim Start automatisch läuft. Nach
  dem Update gibt es den Ausgangsstand erst mit dem nächsten Import; bis dahin melden Korrekturdatei und
  „Änderungen behalten“ das. Keine Änderung an `docker-compose.yml` oder `.env`.

## 0.10.0 (2026-11-17)

Eine Einstellung für die Nachtdienste und eine einfachere Seite Regeln & Gewichte.

- **Nachtdienste:** neue Einstellung oben auf der Seite Regeln. „Wie bisher“ (Standard, Verhalten wie bisher):
  Personen mit gewohnter Verteilung behalten ihren Nachtanteil und sind nicht im Nachtausgleich. „Gleichmäßig“:
  der Nachtanteil der gewohnten Verteilung zählt nicht, alle sind im Nachtausgleich; die übrigen Anteile (Früh,
  Tag, Spät) gelten weiter für die Dienste außer Nächten, die Anteile je Bereich bleiben.
- **Nie Nacht:** In beiden Einstellungen ist nicht im Nachtausgleich und wird für fehlende Nächte nicht bestraft,
  wer eine Einschränkung „nie“ auf alle Nachtschichten seiner Bereiche hat (für jeden Tag) oder eine Familienregel
  „keine Nacht“ an allen sieben Wochentagen. Im Demomonat sinkt dadurch die Zahl der Verglichenen bei „Wie
  bisher“ etwas.
- **Sichtbar:** Die Seite Person zeigt in der Kachel „Nächte“, ob die Person ihrer Verteilung folgt, nie Nacht
  macht oder im Nachtausgleich ist (mit Belastung und Mittel); die Fairness-Übersicht markiert, wer nicht
  verglichen wird. Bei „Wie bisher“ und Gewicht 0 der gewohnten Verteilung warnt die Regelseite, dass Personen mit
  Verteilung dann keine Regel für Nächte haben.
- **Regeln & Gewichte** in drei Abschnitten:
  - Planungsziele: Nachtdienste, Dienstblöcke (von ... bis ... Tagen), Wünsche (höchstens ... je Person),
    Faire Verteilung (über die letzten ... Monate), Stunden treffen (höchstens ... % über dem Ziel, Stundenkonto
    ausgleichen ... % je Monat), Familienregeln; je mit **Wichtigkeit** weniger, normal, mehr (halbe, Standard-,
    doppelte Gewichte). Von Hand gesetzte Gewichte zeigen „angepasst“.
  - Gesetz und Betriebsvereinbarung: Ruhezeiten, Höchstwerte je Dienst, Woche und Monat, Wochenschnitt, freie
    Sonntage, Feiertagsarbeit; als Text, „Ändern“ schaltet die Felder frei.
  - Erweitert (zugeklappt): alle Gewichte einzeln, das Nachtfenster, die übrigen Regeln und der ganze
    Regelkatalog.
- Gespeicherte Regeln älterer Versionen laden unverändert; ohne Einstellung gilt „Wie bisher“.
- **Schnittstelle:** `rules.nightDistribution` (`HABIT`, `EVEN`); in den Kennzahlen je Person `nightPool`,
  `nightReason`, `nightLoad`, `nightMean` (für die Rolle Lesen leer).
- Betrieb: keine Änderung an Datenbank, `docker-compose.yml` oder `.env`.

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
- **Formulare:** Wochenstunden und Stundenkonto mit zwei Nachkommastellen (z. B. 41,75 aus einem Import) ließen
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
