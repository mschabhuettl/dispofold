# Import aus Schichtplaner 5

Die Erweiterung **Schichtplaner 5** liest einen Datenordner von Schichtplaner 5 und importiert daraus Stammdaten,
Abwesenheiten, die Dienste der letzten Monate als Historie, Feiertage und den Bedarf in Dispofold. Den Ordner liest
sie nur; in Schichtplaner 5 ändert sich nichts. Den Weg Schritt für Schritt beschreibt
[Echte Daten aus Schichtplaner 5](plugin-sp5-echte-daten.md).

> „Schichtplaner 5“ ist eine Marke ihres Inhabers. Diese Erweiterung ist ein unabhängiges Projekt und steht in
> keiner Verbindung zum Hersteller von Schichtplaner 5.

## Einschalten und einstellen

1. Unter [Einstellungen → Erweiterungen](erweiterungen.md#einschalten-und-ausschalten) die Erweiterung
   **Schichtplaner 5** einschalten. Ihre Karte sagt dann „Ordner, Zuordnung und Optionen stehen unter Daten →
   Übernehmen.“; **Einrichten** öffnet **Daten → Übernehmen** mit der Quelle **Schichtplaner 5**.
2. Dort unter **Quelle** (siehe [Quelle](plugin-sp5-uebersicht.md#quelle)):
   - **Ordner von Schichtplaner 5:** der Datenordner (mit `5EMPL.DBF` und den anderen Tabellen), dann **Ordner
     speichern**. Am Server sind nur die Ordner wählbar, die die IT freigegeben hat (siehe
     [Betrieb](../betrieb.md#erweiterungen)); am eigenen Rechner öffnet **Ordner wählen …** den Ordnerdialog.
   - **Zuordnung:** welche Schichten, Gruppen und Abwesenheiten zu welchen Bereichen gehören. **Zuordnung
     einrichten** führt zu [Daten → Zuordnung](plugin-sp5-zuordnung.md), mit Vorschlägen aus dem Ordner.
   - **Optionen:** wie der Import rechnet. Jede hat ein „i“ mit der Erklärung und einem Beispiel; die
     Standardwerte passen meist. Oben stehen die, die man kennen sollte; die übrigen stehen unter **Erweitert**.
     Die wichtigste ist **Bereiche einer Person aus** (siehe unten). Eine Änderung wird gleich gespeichert und
     gilt für jedes weitere Einlesen.
3. Ausschalten der Erweiterung behält Ordner, Zuordnung und Optionen.

| Einstellung | Standard |
|---|---|
| Monate Historie | 12 |
| Bereiche einer Person aus | Historie, Gruppen als Reserve (bei Einstellungen aus einer Version vor 0.25.0: Gruppen) |
| Bereich aus der Historie ab … Diensten | 3 |
| Bedarf | aus der Historie |
| Höchstbesetzung aus der Historie | übliche Spitze (9 von 10 Tagen) |
| Erweitert: Bei Gruppen: Bereiche aus der Historie ab … Diensten dazu | leer (aus) |
| Erweitert: Schichten seltener als … Dienste nur von Hand | leer (aus) |
| Erweitert: Gewohnte Verteilung übernehmen, ab … Diensten | an, 10 |
| Erweitert: Ausgeblendete Schichten mit Diensten in den … Monaten davor | 3 |
| Erweitert: Bereiche ohne Dienst in den letzten … Monaten nur von Hand | 3 |
| Erweitert: Nachtschichten automatisch fair verteilen | aus |

**Bereiche einer Person aus** sagt, in welchen Bereichen eine Person geplant werden kann:

- **Gruppen:** alle Bereiche ihrer Gruppen in Schichtplaner 5, auch wenn sie dort nie Dienst hatte. So rechnet
  auch `sp5_export.py`. Mit **Bei Gruppen: Bereiche aus der Historie ab … Diensten dazu** kommen Bereiche dazu, in
  denen sie in der Historie genug Dienste hatte.
- **Historie:** nur die Bereiche, in denen sie in der Historie mindestens so viele Dienste hatte wie bei **Bereich
  aus der Historie ab … Diensten** eingestellt (Standard 3), auch ohne passende Gruppe.
- **Historie, Gruppen als Reserve** (empfohlen): wie „Historie“; die übrigen Bereiche ihrer Gruppen bleiben, aber
  als „nur von Hand“: Dispofold plant die Person dort nicht von selbst, man kann sie von Hand einsetzen. Auf der
  Seite der Person und unter **Personen → Bereiche** steht das als Zustand des Bereichs, nicht als Liste von
  Einschränkungen.

Wer in keinem Bereich auf die Zahl kommt, etwa weil neu oder mit sehr wenigen Diensten, behält bei „Historie“ und
„Historie, Gruppen als Reserve“ die Bereiche seiner Gruppen. Beispiel: Wer in einer Gruppe für Annahme und
Disposition ist und in der Historie 10 Dienste in der Annahme und keinen in der Disposition hatte, bekommt bei
„Gruppen“ beide Bereiche, bei „Historie“ nur die Annahme und bei „Historie, Gruppen als Reserve“ die Annahme und
die Disposition nur von Hand. Die Seite der Person zeigt neben jedem Bereich, woher er kommt
(„Gruppe …“, „Historie: 10 Dienste“) und ob er nur von Hand ist.

**Höchstbesetzung aus der Historie** (ab 0.29.0) gilt nur für den Bedarf aus der Historie. Die Mindestbesetzung je
Bereich, Tagesart und Uhrzeit ist die Besetzung, die an der Hälfte der gleichen Tage mindestens Dienst hatte (der
Median). Die Höchstbesetzung ist bei **übliche Spitze** (Standard, wie `sp5_export.py`) die Besetzung, die an 9 von
10 solchen Tagen erreicht wurde; bis dahin ist mehr Besetzung erlaubt, und die Regel „Besetzung über dem
Mindestbedarf“ hält sie klein. Bei **wie Minimum** ist jede Person über dem Minimum eine Überbesetzung, auch zu
Zeiten mit Minimum 0; das gilt auch für Bedarf, den eine Korrekturdatei ersetzt hat. Gemessen am großen synthetischen Monat plant „wie Minimum“ nicht weniger, sondern mehr
Stunden (24 % statt 21 % über dem Minimum, „schnell“), alles darüber als Überbesetzung. Einstellungen aus einer Version davor behalten „übliche Spitze“.

**Anteil jeder Schicht in der Historie** (ab 0.30.0, ohne Option): Die Erweiterung zählt die Dienste der
übernommenen Personen in den Monaten der Historie je Schicht und schreibt jeder Schicht ihren Anteil an den Diensten
ihres Bereichs (`usage`, unter **Stammdaten → Schichten** in der Spalte „Anteil in der Historie“). Die Regel
**Gewohnte Schichten** meidet damit Schichten, die selten vorkamen. Ohne Historie oder in einem Bereich ohne solchen
Dienst bekommt keine Schicht einen Anteil; sie gelten dann alle als gewohnt.

**Schichten seltener als … Dienste nur von Hand** braucht es mit „Historie, Gruppen als Reserve“ meist nicht:
Dort hat eine Person ohnehin nur die Bereiche ganz, in denen sie gearbeitet hat. Mit echten Daten zeigt sich:
Reserve und diese Option zusammen geben einer Person viele Zeilen mit Ausnahmen je Schicht; bleibt die Option leer,
hat jeder Bereich einer Person einen klaren Zustand (planbar oder nur von Hand) ohne lange Liste darunter. Die
Option bleibt deshalb unter **Erweitert** und ist leer.

Einstellungen aus einer Version vor 0.25.0 bleiben bei „Gruppen“, damit dasselbe Einlesen dasselbe ergibt; die
Option **Bei Gruppen: Bereiche aus der Historie ab …** wirkt nur dort. **Bereiche ohne Dienst in den letzten …
Monaten nur von Hand** gilt bei jedem Wert, für Bereiche, in denen jemand früher gearbeitet hat und zuletzt
nicht mehr.

Ohne die letzte Option wird eine Schicht nur fair verteilt, wenn ihre Schichtregel in der Zuordnung „Fair
verteilen: ja“ sagt; Nächte haben ohnehin ihre eigene Fairness-Regel. Eine Regel mit „ja“ oder „nein“ gilt immer
so.

## Importieren

Importiert wird an einer Stelle: unter [Daten → Übernehmen](plugin-sp5-uebersicht.md) mit der Quelle
**Schichtplaner 5**. Jeden Monat: **Daten** öffnen (die Quelle ist gemerkt), den Monat wählen, **Einlesen**, den
Satz oben im Ergebnis lesen, dann **Übernehmen**. **Änderungen aus Dispofold behalten** anhaken, wenn seit dem
letzten Import in Dispofold etwas an den Stammdaten geändert wurde; die Vorschau zeigt, was übernommen wird und wo
es Konflikte gibt (siehe [Daten und Korrekturdatei](daten.md#neuer-datensatz-änderungen-aus-dispofold-behalten)).

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
