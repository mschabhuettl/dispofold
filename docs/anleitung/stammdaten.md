# Bereiche, Schichten und Bedarf

### Stammdaten

![Stammdaten](../screenshots/stammdaten.png)

Unter **Stammdaten** stehen Bereiche, Abwesenheitsarten, Schichten, Bedarf und Feiertage, jede Tabelle mit einem
Feld **Filtern**. Personen werden unter [Personen](personen.md) bearbeitet (Verweis **Personen bearbeiten** oben).

- **Neu** über jeder Tabelle legt einen Eintrag an, **Bearbeiten** und **Entfernen** stehen in jeder Zeile. Es
  ist immer nur ein Formular offen. **Speichern** übernimmt die Änderung sofort, **Abbrechen** verwirft sie.
- **Ids:** Neue Schichten bekommen ihre Id vom Programm. Bereiche und Abwesenheitsarten haben als Id ihr Kürzel,
  das im Plan steht. Es wird beim Anlegen eingegeben und lässt sich danach nicht mehr ändern. Ein entferntes
  Kürzel bleibt gesperrt und kann nicht neu vergeben werden.
- **Schichten** haben eine Kategorie (Früh, Tag, Spät, Nacht oder „aus den Zeiten“) für Vorwärtsrotation,
  Abwechslung, gewohnte Verteilung und Einschränkungen; **Unbeliebt** markiert Schichten für die Fairness.
- **Bedarf** gilt entweder an bestimmten Tagen (Montag bis Sonntag, Feiertag) oder an einem Datum, mit
  Mindestbesetzung und auf Wunsch Höchstbesetzung.
- **Abwesenheitsarten** legen fest, ob ein Tag das Soll senkt (zählt wie gearbeitet), das Stundenkonto belastet
  oder Ersatzruhe ist.
- Das Programm prüft jede Änderung wie einen Import. Ist etwas falsch, wird nichts gespeichert, und die Liste der
  Fehler erscheint im Formular.

**Entfernen:** Wird ein Eintrag noch verwendet (in offenen Monaten oder in anderen Stammdaten, zum Beispiel eine
Schicht in Einschränkungen oder im Plan), nennt das Programm die Zahl der Verweise und die Monate und fragt, ob er
mit allen Verweisen entfernt werden soll. Kommt ein Eintrag in einem abgeschlossenen Monat vor, lässt er sich
nicht entfernen. Ein Bereich lässt sich erst entfernen, wenn seine Schichten entfernt sind. Während einer Planung
sind Änderungen an betroffenen Monaten gesperrt. Offene Monate, die eine Änderung betrifft, zeigen danach „Plan
veraltet: neu planen“. Wie bei den Personen fragt das Programm nach, wenn jemand anderer inzwischen gespeichert
hat.
