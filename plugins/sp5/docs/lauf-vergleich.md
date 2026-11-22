# Schichtplaner 5: Vergleich

Die Seite **Schichtplaner 5 → Vergleich** zeigt, was der letzte Lauf der [Übersicht](plugin-sp5-uebersicht.md)
gegenüber dem letzten Import ändern würde, bevor man importiert.

![Der Vergleich des letzten Laufs mit dem letzten Import](img/vergleich.png)

## Womit verglichen wird

- **Vergleichen mit dem letzten Import** (Standard): mit den Stammdaten, wie sie zuletzt importiert wurden, und den
  Monaten des Laufs, wie Dispofold sie jetzt hat. Personen, die schon beim letzten Import mit **Aus dem
  Quellsystem nicht mehr übernehmen** ausgeschlossen waren und im Lauf fehlen, zählen nicht als Unterschied: Der
  Import behält sie.
- **Vergleichen mit einer Datensatz-Datei:** mit einem früheren Datensatz, etwa aus **Daten → Export** oder aus
  einem gespeicherten Ergebnis. Vor dem ersten Import ist das der einzige Vergleich.
- **Korrekturen anwenden** (Standard an): Der Lauf ist dann genau das, was der Import laden würde. Ohne zeigt der
  Vergleich auch die Unterschiede, die die Korrekturen ausmachen.

## Was er zeigt

- **Anzahl je Abschnitt:** Personen, Bereiche, Schichten, Bedarfsregeln, Feiertage, Abwesenheitsarten,
  Einschränkungen, Historie und, für die Monate des Laufs, Dienste, Dienste davor, Abwesenheiten und Wünsche,
  alt, neu und die Differenz.
- **Personen neu** und **Personen entfernt**, nach Id sortiert; eine Person, die schon in Dispofold ist, ist ein
  Link auf ihre Seite. Wer seit dem letzten Import in Dispofold ausgeschlossen wurde, steht unter **Personen
  entfernt** mit dem Hinweis, dass der Import die Person behält; ihre Historie und ihre Einträge der Monate
  zählen dann in der Anzahl mit.
- **Geänderte Felder:** je Person und Feld der alte und der neue Wert; **Einschränkungen** als neu (+) und
  entfernt (−).
- **Dienste je Monat und Bereich:** wo sich die Zahl der Dienste geändert hat.
- **Bedarf geändert:** je Bereich, Tagen oder Datum und Zeit die Besetzung alt und neu, als mindestens bis
  höchstens; eine Zahl allein heißt: die Mindestbesetzung ist auch die Höchstbesetzung, „–“: keine Regel.
