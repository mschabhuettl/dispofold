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
   - **Optionen:** wie der Import rechnet. Jede hat ein „i“ mit der Erklärung und einem Beispiel; die
     Standardwerte passen meist. Oben stehen die, die man kennen sollte; die übrigen stehen unter **Erweitert**.
     Die wichtigste ist **Bereiche einer Person aus** (siehe unten).
3. **Einstellungen speichern.** Ausschalten der Erweiterung behält sie.

| Einstellung | Standard |
|---|---|
| Monate Historie | 12 |
| Bereiche einer Person aus | Historie, Gruppen als Reserve (bei Einstellungen aus einer Version vor 0.25.0: Gruppen) |
| Bereich aus der Historie ab … Diensten | 3 |
| Bedarf | aus der Historie |
| Erweitert: Bei Gruppen: Bereiche aus der Historie ab … Diensten dazu | leer (aus) |
| Erweitert: Schichten seltener als … Dienste nur auf Anfrage | leer (aus) |
| Erweitert: Gewohnte Verteilung übernehmen, ab … Diensten | an, 10 |
| Erweitert: Ausgeblendete Schichten mit Diensten in den … Monaten davor | 3 |
| Erweitert: Bereiche ohne Dienst in den letzten … Monaten nur auf Anfrage | 3 |
| Erweitert: Nachtschichten automatisch fair verteilen | aus |

**Bereiche einer Person aus** sagt, in welchen Bereichen eine Person geplant werden kann:

- **Gruppen:** alle Bereiche ihrer Gruppen in Schichtplaner 5, auch wenn sie dort nie Dienst hatte. So rechnet
  auch `sp5_export.py`. Mit **Bei Gruppen: Bereiche aus der Historie ab … Diensten dazu** kommen Bereiche dazu, in
  denen sie in der Historie genug Dienste hatte.
- **Historie:** nur die Bereiche, in denen sie in der Historie mindestens so viele Dienste hatte wie bei **Bereich
  aus der Historie ab … Diensten** eingestellt (Standard 3), auch ohne passende Gruppe.
- **Historie, Gruppen als Reserve** (empfohlen): wie „Historie“; die übrigen Bereiche ihrer Gruppen bleiben, aber
  jede Schicht darin ist „nur auf Anfrage“: Dispofold plant sie dort nicht von selbst, man kann sie von Hand
  einsetzen.

Wer in keinem Bereich auf die Zahl kommt, etwa weil neu oder mit sehr wenigen Diensten, behält bei „Historie“ und
„Historie, Gruppen als Reserve“ die Bereiche seiner Gruppen. Beispiel: Wer in einer Gruppe für Annahme und
Disposition ist und in der Historie 10 Dienste in der Annahme und keinen in der Disposition hatte, bekommt bei
„Gruppen“ beide Bereiche, bei „Historie“ nur die Annahme und bei „Historie, Gruppen als Reserve“ die Annahme und
die Disposition nur auf Anfrage. Die Seite der Person zeigt neben jedem Bereich, woher er kommt
(„Gruppe …“, „Historie: 10 Dienste“) und ob er nur auf Anfrage ist.

Einstellungen aus einer Version vor 0.25.0 bleiben bei „Gruppen“, damit derselbe Lauf dasselbe ergibt; die
Option **Bei Gruppen: Bereiche aus der Historie ab …** wirkt nur dort. **Bereiche ohne Dienst in den letzten …
Monaten nur auf Anfrage** gilt bei jedem Wert, für Bereiche, in denen jemand früher gearbeitet hat und zuletzt
nicht mehr.

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
