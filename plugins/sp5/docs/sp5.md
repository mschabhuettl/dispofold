# Import aus Schichtplaner 5

Die Erweiterung **Schichtplaner 5** liest einen Datenordner von Schichtplaner 5 und importiert daraus Stammdaten,
Abwesenheiten, die Dienste der letzten Monate als Historie, Feiertage und den Bedarf in Dispofold. Den Ordner liest
sie nur; in Schichtplaner 5 ändert sich nichts. Den Weg Schritt für Schritt beschreibt
[Echte Daten aus Schichtplaner 5](plugin-sp5-echte-daten.md).

> „Schichtplaner 5“ ist eine Marke ihres Inhabers. Diese Erweiterung ist ein unabhängiges Projekt und steht in
> keiner Verbindung zum Hersteller von Schichtplaner 5.

## Einschalten und einstellen

1. Unter [Einstellungen → Erweiterungen](erweiterungen.md#einschalten-und-ausschalten) die Erweiterung
   **Schichtplaner 5** einschalten.
2. Darunter erscheinen ihre Einstellungen:
   - **Ordner von Schichtplaner 5:** der Datenordner (mit `5EMPL.DBF` und den anderen Tabellen). Am Server sind nur
     die Ordner wählbar, die die IT freigegeben hat (siehe [Betrieb](../betrieb.md#erweiterungen)); am eigenen
     Rechner öffnet **Ordner wählen …** den Ordnerdialog.
   - **Zuordnung (JSON):** welche Schichten, Gruppen und Abwesenheiten zu welchen Bereichen gehören, als Text.
     Eine Datei wählen oder den Text einfügen; beim Speichern wird sie geprüft, alle Fehler stehen in der Meldung.
     Bequemer geht es auf der Seite [Zuordnung](plugin-sp5-zuordnung.md), mit Vorschlägen aus dem Ordner.
   - **Optionen:** wie der Import rechnet. Jede hat ein „i“ mit der Erklärung; die Standardwerte passen meist.
3. **Einstellungen speichern.** Ausschalten der Erweiterung behält sie.

| Einstellung | Standard |
|---|---|
| Monate Historie | 12 |
| Bereiche aus der Historie ab … Diensten | leer (aus) |
| Schichten seltener als … Dienste nur auf Anfrage | leer (aus) |
| Bedarf | aus der Historie |
| Gewohnte Verteilung übernehmen, ab … Diensten | an, 10 |
| Ausgeblendete Schichten mit Diensten in den … Monaten davor | 3 |
| Bereiche ohne Dienst in den letzten … Monaten nur auf Anfrage | 3 |
| Nachtschichten automatisch fair verteilen | aus |

Ohne die letzte Option wird eine Schicht nur fair verteilt, wenn ihre Schichtregel in der Zuordnung „Fair
verteilen: ja“ sagt; Nächte haben ohnehin ihre eigene Fairness-Regel. Eine Regel mit „ja“ oder „nein“ gilt immer
so.

## Importieren

Importiert wird an einer Stelle: auf der Seite [Lauf und Import](plugin-sp5-uebersicht.md) im Menü
**Schichtplaner 5**. Dort den ersten und, für mehrere Monate, den letzten Monat wählen, **Ausführen**, das
Ergebnis ansehen und dann unter **Diesen Lauf importieren** importieren. **Änderungen aus Dispofold behalten**
anhaken, wenn seit dem letzten Import in Dispofold etwas an den Stammdaten geändert wurde; die Vorschau zeigt, was
übernommen wird und wo es Konflikte gibt (siehe [Daten und Korrekturdatei](daten.md)).

Die Korrekturen kommen von selbst: Die Erweiterung wendet die Korrekturdatei an, die Dispofold aus den Änderungen
seit dem letzten Import bildet, und vermerkt sie im Datensatz. Eine Datei `korrekturen.json` braucht es dafür
nicht mehr.

Den ganzen Weg mit Bericht, Zuordnung und Vergleich beschreibt [Ablauf](plugin-sp5-ablauf.md).

## Abwesenheiten auf Diensttagen

In Schichtplaner 5 bleibt ein geplanter Dienst stehen, wenn an seinem Tag eine ganztägige Abwesenheit
eingetragen wird. Der Import übernimmt solche Dienste nicht (der Bericht nennt sie, mit einer Warnung) und gibt
an der Abwesenheit auch keine Stunden mit. Die Gutschrift „Stunden des geplanten Dienstes“ einer Abwesenheitsart
(Seite Stammdaten) gilt deshalb nur für Abwesenheiten, die nach der Planung in Dispofold eingetragen werden;
importierte Abwesenheiten zählen mit Wochenstunden / 5, und die Personenseite sagt das. Die Erweiterung lässt die
Gutschrift aller Arten beim Standard.

## Für die IT

Wie sich die Erweiterung zum früheren Programm `sp5_export.py` verhält (gleiches Ergebnis, die gewollten
Unterschiede, die Optionen der Kommandozeile) und wie man das mit echten Daten prüft, steht unter
[Technik und Vergleich mit sp5-export](plugin-sp5-technik.md).
