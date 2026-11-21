# Import aus Schichtplaner 5

Die Erweiterung **Schichtplaner 5** liest einen Datenordner von Schichtplaner 5 und importiert daraus Stammdaten,
Abwesenheiten, die Dienste der letzten Monate als Historie, Feiertage und den Bedarf in Dispofold. Sie macht
dasselbe wie das Programm `sp5_export.py` aus sp5-export, nur ohne Umweg über Dateien: Der Import läuft über die
Seite **Daten**, mit Vorschau und der Wahl, die Änderungen aus Dispofold zu behalten. Den Ordner liest sie nur;
in Schichtplaner 5 ändert sich nichts.

> „Schichtplaner 5“ ist eine Marke ihres Inhabers. Diese Erweiterung ist ein unabhängiges Projekt und steht in
> keiner Verbindung zum Hersteller von Schichtplaner 5.

## Einschalten und einstellen

1. Unter [Einstellungen → Erweiterungen](erweiterungen.md#einschalten-und-ausschalten) die Erweiterung
   **Schichtplaner 5** einschalten.
2. Darunter erscheinen ihre Einstellungen:
   - **Ordner von Schichtplaner 5:** der Datenordner (mit `5EMPL.DBF` und den anderen Tabellen). Am Server sind nur
     die Ordner wählbar, die die IT freigegeben hat (siehe [Betrieb](../betrieb.md#erweiterungen)); am eigenen
     Rechner öffnet **Ordner wählen …** den Ordnerdialog.
   - **Zuordnung (JSON):** welche Schichten, Gruppen und Abwesenheiten zu welchen Bereichen gehören, im Format von
     `mapping.json` aus sp5-export. Eine Datei wählen oder den Text einfügen. Beim Speichern wird sie geprüft wie
     von `sp5_export.py`; alle Fehler stehen in der Meldung.
   - **Optionen:** dieselben wie die von `sp5_export.py`, mit denselben Standardwerten. Jede hat ein „i“ mit der
     Erklärung.
3. **Einstellungen speichern.** Ausschalten der Erweiterung behält sie.

| Einstellung | Standard | Entspricht |
|---|---|---|
| Monate Historie | 12 | `--history-months` |
| Bereiche aus der Historie ab … Diensten | leer (aus) | `--areas-from-history` |
| Schichten seltener als … Dienste nur auf Anfrage | leer (aus) | `--shifts-from-history` |
| Bedarf | aus der Historie | `--demand` |
| Gewohnte Verteilung übernehmen, ab … Diensten | an, 10 | `--no-shift-mix`, `--mix-min-duties` |
| Ausgeblendete Schichten mit Diensten in den … Monaten davor | 3 | `--hidden-shifts-months` |
| Bereiche ohne Dienst in den letzten … Monaten nur auf Anfrage | 3 | `--recent-months` |

## Importieren

1. Auf der Seite **Daten** im Abschnitt **Aus Schichtplaner 5** den ersten und, für mehrere Monate, den letzten
   Monat wählen.
2. **Änderungen aus Dispofold behalten** anhaken, wenn seit dem letzten Import in Dispofold etwas an den
   Stammdaten geändert wurde, und **Importieren**. Die Vorschau zeigt, was übernommen wird und wo es Konflikte gibt
   (siehe [Daten und Korrekturdatei](daten.md)).

Die Korrekturen kommen von selbst: Die Erweiterung wendet die Korrekturdatei an, die Dispofold aus den Änderungen
seit dem letzten Import bildet, und vermerkt sie im Datensatz. Eine Datei `korrekturen.json` braucht es dafür
nicht mehr.

Den Bericht und die Warnungen des Exports gibt es in dieser Version als Dateien: unter den Einstellungen der
Erweiterung **Ergebnis speichern** (siehe [Vergleich mit sp5-export](plugin-sp5-vergleich.md)).

## Zyklen

Eine Zyklus-Zuordnung (`5CYASS`) ohne Ende oder ohne Beginn ergibt keine Dienste, wie in Schichtplaner 5 (so
auch openschichtplaner5 ab 1.0.1). `sp5_export.py` liest eine Zuordnung ohne Ende dagegen als offen und setzt
Dienste ein. Nur in diesem Fall weichen die Ergebnisse ab.
