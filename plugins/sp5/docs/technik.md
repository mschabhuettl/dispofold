# Schichtplaner 5: Technik und Vergleich mit sp5-export

Diese Seite ist für die IT und für alle, die bisher mit dem Programm `sp5_export.py` aus sp5-export gearbeitet
haben. Für die Arbeit mit der Erweiterung braucht man sie nicht; der Weg für den Anfang steht unter
[Echte Daten aus Schichtplaner 5](plugin-sp5-echte-daten.md).

## Gleiches Ergebnis wie sp5_export.py

Die Erweiterung **Schichtplaner 5** soll für denselben Ordner, dieselben Monate, Optionen, Zuordnung und
Korrekturen dasselbe liefern wie `sp5_export.py`: denselben Datensatz, denselben Bericht und dieselben Warnungen,
Byte für Byte, ohne die Dateien vorher zu vereinheitlichen. Für synthetische Daten prüft das die CI bei jeder
Änderung; mit echten Daten prüft man es einmal so, wie unter
[Vergleich mit echten Daten](#vergleich-mit-echten-daten) beschrieben.

Es gibt zwei gewollte Unterschiede:

- **Nachtschichten fair verteilen:** `sp5_export.py` markiert jede Nachtschicht als „Fair verteilen“, wenn ihre
  Schichtregel in der Zuordnung dazu nichts sagt. Die Erweiterung tut das nur mit der Option **Nachtschichten
  automatisch fair verteilen**, die standardmäßig aus ist; Nächte haben in Dispofold ohnehin ihre eigene
  Fairness-Regel. Ist die Option aus, gibt `sp5-vergleich.py` dem Python-Programm eine Zuordnung, in der jede
  Schichtregel ohne Angabe „Fair verteilen: nein“ (`"unpopular": false`) sagt, und nennt das im Ergebnis
  („Nachtschichten nicht automatisch fair verteilt …“). Mit eingeschalteter Option rechnet es mit der Zuordnung,
  wie sie ist.
- **Zyklen ohne Ende oder Beginn:** Eine Zyklus-Zuordnung (`5CYASS`) ohne Ende oder ohne Beginn ergibt keine
  Dienste, wie in Schichtplaner 5 (so auch openschichtplaner5 ab 1.0.1). `sp5_export.py` liest eine Zuordnung
  ohne Ende dagegen als offen und setzt Dienste ein.

Sonst kennt die Erweiterung nur Unterschiede in Fällen, die in echten Daten kaum vorkommen:

- Ein Muster in der Zuordnung mit `[[` oder `--` in einer Zeichenklasse: Python schreibt dazu eine Warnung des
  Interpreters, die Erweiterung nicht. Ein Rückverweis innerhalb eines Lookbehind wird von der Erweiterung als
  ungültiges Muster abgelehnt.
- Zeichen, die erst Unicode 16 vergeben hat, in einer Meldung: Python und Java schreiben sie verschieden.
- Fälle, in denen `sp5_export.py` mit einem Python-Traceback abbricht, statt eine Meldung zu geben.
- Ganzzahl-Optionen über 2 147 483 647: Die Einstellungen lehnen sie ab.

Die Meldungen der Prüfung der Zuordnung zeigen die Seiten in einfachen Worten („Es ist noch kein Bereich
eingetragen.“); der Lauf selbst prüft die Zuordnung genau wie `sp5_export.py`.

## Optionen und Kommandozeile

Die Optionen der Erweiterung entsprechen denen von `sp5_export.py`, mit denselben Standardwerten:

| Option in Dispofold | Kommandozeile von `sp5_export.py` |
|---|---|
| Monate Historie | `--history-months` |
| Bereiche aus der Historie ab … Diensten | `--areas-from-history` |
| Schichten seltener als … Dienste nur auf Anfrage | `--shifts-from-history` |
| Bedarf | `--demand` |
| Gewohnte Verteilung übernehmen, ab … Diensten | `--no-shift-mix`, `--mix-min-duties` |
| Ausgeblendete Schichten mit Diensten in den … Monaten davor | `--hidden-shifts-months` |
| Bereiche ohne Dienst in den letzten … Monaten nur auf Anfrage | `--recent-months` |
| Nachtschichten automatisch fair verteilen | keine; `sp5_export.py` tut das immer (siehe oben) |

Die Zuordnung hat das Format von `mapping.json` aus sp5-export. Die Korrekturdatei, die Dispofold aus den
Änderungen seit dem letzten Import bildet, wendet die Erweiterung so an wie `sp5_export.py` mit `--corrections`.

## Abwesenheiten auf Diensttagen

In Schichtplaner 5 bleibt ein geplanter Dienst stehen, wenn an seinem Tag eine ganztägige Abwesenheit
eingetragen wird. Der Import übernimmt solche Dienste nicht (der Bericht nennt sie, mit einer Warnung) und gibt
an der Abwesenheit auch keine Stunden mit, damit das Ergebnis Byte für Byte dem von `sp5_export.py` gleicht.

## Fehlermeldungen

Ein Fehler beim Lauf nennt die Tabelle oder den Eintrag der Zuordnung. Ein unerwarteter Fehler nennt nur
Fehlerart, Datei und Zeile, nie Feldinhalte. Bericht und Warnungen enthalten keine Namen, aber die Ids und Kürzel
des Betriebs; Datensatz und Ergebnis-ZIP enthalten personenbezogene Daten. Für eine Fehlersuche die
pseudonymisierten Dateien von der Seite **Daten** weitergeben.

## Vergleich mit echten Daten

### Was man braucht

- Dispofold mit der eingeschalteten und eingestellten Erweiterung (Server oder Programm am eigenen Rechner).
- Am Rechner mit dem Ordner von Schichtplaner 5: Python 3.10 oder neuer und sp5-export (der Ordner mit
  `sp5_export.py`), dazu `sp5-vergleich.py` aus dem Ordner `plugins/sp5/tools/` von Dispofold.

### Schritte

1. In Dispofold auf der Seite **Schichtplaner 5 → Lauf und Import** den Monat wählen und **Ergebnis speichern**
   (dasselbe geht unter **Einstellungen → Erweiterungen → Schichtplaner 5** bei **Ergebnis als Dateien
   speichern**). Es kommt eine ZIP-Datei, etwa `dispofold-sp5-ergebnis-2027-01.zip`. Sie enthält
   personenbezogene Daten: nur auf diesem Rechner ablegen.
2. In einer Eingabeaufforderung (Windows) oder einem Terminal (Linux):

   ```text
   python -X utf8 sp5-vergleich.py --ergebnis dispofold-sp5-ergebnis-2027-01.zip --daten <Ordner von Schichtplaner 5> --sp5-export <Ordner von sp5-export>
   ```

   Das Skript ruft `sp5_export.py` mit genau den Optionen, der Zuordnung und der Korrekturdatei aus der ZIP-Datei
   auf (bei ausgeschalteter Option **Nachtschichten automatisch fair verteilen** mit der Zuordnung wie oben
   beschrieben) und vergleicht die drei Dateien.
3. Es zeigt das Ergebnis und speichert es als `sp5-vergleich.txt`. Diese Datei zurückschicken: Sie enthält nur, ob
   jede Datei identisch ist, und bei Unterschieden die Stellen im Datenformat mit Positionen statt Ids, Spalten des
   Berichts und Anzahlen, keine Namen, Kürzel, Ids, Daten oder Werte.
4. Die ZIP-Datei danach löschen.

Steht am Ende **ERGEBNIS: alles identisch**, liefert die Erweiterung mit diesen Daten dasselbe wie `sp5_export.py`.
Eine Zyklus-Zuordnung ohne Ende im Ordner zeigt sich dagegen als Unterschied (siehe oben).

### Vorschläge gegen die eigene Zuordnung

Wie nah kommt **Zuordnung vorschlagen** an eine Zuordnung, die man schon von Hand gepflegt hat? Die ZIP-Datei aus
Schritt 1 enthält dafür auch `vorschlag-vergleich.json`. Dispofold schlägt dazu für den Ordner eine Zuordnung von
Grund auf vor, übernimmt alle Vorschläge (bei einer Wahl den stärkeren Bereich) und vergleicht sie Kürzel für
Kürzel mit der eigenen Zuordnung. Die Bereiche werden über die Schichten einander zugeordnet, die sie gemeinsam
haben. Die Datei enthält nur Anzahlen.

```text
python -X utf8 sp5-vergleich.py --ergebnis dispofold-sp5-ergebnis-2027-01.zip --vorschlag
```

Dafür braucht es weder den Ordner noch sp5-export. Das Skript zeigt je Abschnitt (Bereiche, Schichten, Gruppen,
Ersatzruhe), wie viele Einträge gleich sind, wie viele verschieden, wie viele im Vorschlag fehlen und wie viele er
zusätzlich hat. Dasselbe steht in `sp5-vergleich.txt`, ohne Kürzel und ohne Namen, und lässt sich so
zurückschicken.
