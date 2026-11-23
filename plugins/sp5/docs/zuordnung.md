# Schichtplaner 5: Zuordnung

Die Seite **Schichtplaner 5 → Zuordnung** bearbeitet die Zuordnung: welche Schichten, Gruppen und Abwesenheiten
von Schichtplaner 5 zu welchen Bereichen von Dispofold gehören. Sie ist dieselbe Zuordnung wie in den
Einstellungen der Erweiterung und hat dasselbe Format wie eine `mapping.json`. Für den Anfang reicht
**Zuordnung vorschlagen** (siehe [Vorschläge aus dem Ordner](#vorschläge-aus-dem-ordner)); fehlt die Zuordnung
noch, führt der Hinweis „Die Zuordnung fehlt“ auf der Seite Lauf und Import mit **Zur Zuordnung** hierher.

![Die Zuordnung mit Bereichen und Schichtregeln](img/zuordnung.png)

## Abschnitte

- **Bereiche:** die Bereiche, die der Import anlegt, mit Id (kurz, etwa `AN`), Name und Farbe.
- **Schichtregeln:** ordnen Schichten nach ihrem Kürzel einem Bereich zu; die erste passende Regel gilt.
  **Kategorie** leer heißt: aus den Zeiten der Schicht; **Fair verteilen**: Dienste dieser Schicht werden über die Fairness gleichmäßig auf alle verteilt, die
  sie machen können; ohne Angabe nein, Nachtschichten nur mit der Option „Nachtschichten automatisch fair verteilen“
  (Lauf und Import, Optionen). Die
  Spalte **Trifft** zeigt die Kürzel aus dem Ordner, die die Regel trifft, oder „trifft nichts“.
- **Ignorierte Schichten:** Schichten, die der Import nicht übernimmt, auch wenn eine Regel passt.
- **Gruppen:** Personen in einer Gruppe, deren Name passt, bekommen die angekreuzten Bereiche.
- **Ignorierte Gruppen** geben keine Bereiche; **Ausgeschlossene Gruppen**: ihre Personen übernimmt der Import
  nicht.
- **Ersatzruhe** und **Ignorierte Abwesenheiten:** Kürzel von Abwesenheitsarten.
- **Dienste auf ignorierten Schichten:** ob ein solcher Dienst den Tag als Abwesenheit sperrt (Vorgabe: ja).
- **Unbekannte Schlüssel** bleiben beim Speichern erhalten; der Export weist eine Zuordnung mit ihnen aber ab.

Muster: `*` und `?` als Platzhalter (Groß- und Kleinschreibung zählt), oder `re:` und ein regulärer Ausdruck, der
am Anfang des Kürzels passen muss.

## Bearbeiten

- Jeder Abschnitt hat **… hinzufügen**; jede Zeile hat Knöpfe zum Verschieben nach oben und unten und zum
  Entfernen, auch mit der Tastatur erreichbar.
- **Prüfung:** Nach jeder Änderung prüft der Server die Zuordnung wie der Export und nennt jedes Problem mit dem
  Eintrag, wie ihn die Seite nennt, etwa „Es ist noch kein Bereich eingetragen.“ oder „Schichtregel 2: Das Muster
  „re:(“ ist kein gültiger regulärer Ausdruck (…).“
- **Zuordnung speichern** speichert sie in den Einstellungen; der Server prüft sie dabei noch einmal und weist
  eine fehlerhafte ab. Ungespeicherte Änderungen sind markiert, und beim Verlassen der Seite fragt Dispofold nach.
- **mapping.json laden** ersetzt die Zuordnung im Editor durch eine Datei, **Als mapping.json speichern**
  speichert sie als Datei. **JSON anzeigen und bearbeiten** zeigt sie als Text; **Übernehmen** ersetzt den Editor
  damit.

## Vorschläge aus dem Ordner

Fast alles, was die Zuordnung braucht, steht schon im Ordner von Schichtplaner 5: die Gruppen, ihre Mitglieder,
der Bedarf je Gruppe und die Dienste. **Zuordnung vorschlagen** liest das und schlägt Einträge vor, für alles, was
die Zuordnung im Editor noch nicht trifft (in **Im Ordner** „keine Regel“). Bei einer leeren Zuordnung ist das eine
ganze erste Zuordnung. Bestehende Einträge ändert ein Vorschlag nie.

![Vorschläge für Schichten und Gruppen mit ihren Gründen](img/zuordnung-vorschlaege.png)

Jeder Vorschlag nennt seinen Grund mit den Zahlen dahinter. **Übernehmen** trägt ihn in den Editor ein,
**Verwerfen** nimmt ihn aus der Liste, **Alle übernehmen** nimmt alle eines Abschnitts. Gespeichert wird erst mit
**Zuordnung speichern**, mit derselben Prüfung wie sonst. Ein verworfener Vorschlag kommt nicht wieder, bis man
**Verworfene zurücksetzen** wählt. Nach jedem Lauf nennt die Seite Lauf und Import die Zahl der neuen Vorschläge mit einem
Link hierher.

So entstehen die Vorschläge (die Beispiele stammen aus einem erfundenen Ordner):

- **Dienste:** Gezählt werden die Dienste im Ordner, auch die aus Dienstzyklen, im gewählten Zeitraum; „alle“
  reicht bis zum neuesten Dienst oder bis heute, was später ist. Zu einer Gruppe gehören auch die Mitglieder ihrer
  Untergruppen. Eine Schicht ohne Kürzel und eine Schicht, die nur Personen ohne Gruppe gemacht haben, bekommen
  keinen Vorschlag.
- **Bedarf zuerst:** Der Bedarf in Schichtplaner 5 verbindet eine Schicht direkt mit Gruppen. Hat nur eine Gruppe
  Bedarf für die Schicht, gehört die Schicht zu ihr. Beispiel: „R1: Bedarf in Schichtplaner 5 nur bei der Gruppe
  Team Disposition. Mitglieder der Gruppe Team Disposition (Bereich TD) haben 100 % der 42 Dienste.“ Haben mehrere
  Gruppen Bedarf oder keine, entscheidet, wessen Mitglieder den größten Teil der Dienste gemacht haben.
  Sammelgruppen zählen dabei nicht.
- **Bereiche:** Eine Gruppe, der so eine Schicht gehört, wird ein neuer Bereich, mit einer Id aus ihrem Namen
  (Team Annahme → `TA`, änderbar) und einer Farbe. Beispiel: „Gruppe Team Annahme mit 68 von 71 Mitgliedern mit
  Diensten; eigene Schichten A1, A2, A3, A4.“
- **Schichtregeln:** Eine Schicht kommt in den Bereich, wenn die Mitglieder seiner Gruppen mindestens den
  **Anteil** ihrer Dienste gemacht haben, Standard 80 %. Darunter stehen die zwei stärksten Bereiche zur Wahl, und
  man wählt selbst. Beispiel: „Kein Bereich erreicht 80 % der 4 Dienste: TA 50 %, TD 50 %. Bitte wählen.“ Die
  Regeln nennen genau das Kürzel; ein Muster wie `A*` kann man danach selbst daraus machen.
- **Gruppen:** Die Gruppe eines neuen Bereichs bekommt diesen Bereich. Eine Gruppe ohne eigene Schicht kommt in
  den Bereich, in dem mindestens der Anteil ihrer Mitglieder mit Diensten gearbeitet hat. Beispiel: „40 von 45
  Mitgliedern mit Diensten (89 %) haben Dienste im Bereich TA.“ Eine Untergruppe unter einer Gruppe mit Bereich
  übernimmt deren Regel und bekommt keinen Vorschlag.
- **Sammelgruppen ignorieren:** Verteilen sich die Mitglieder auf zwei oder mehr Bereiche, und keiner erreicht
  den Anteil, ist die Gruppe eine Sammelgruppe (etwa „alle“ oder eine Schichtgruppe quer durch die Bereiche).
  Beispiel: „Sammelgruppe: die 147 Mitglieder mit Diensten verteilen sich auf die Bereiche TA (64 %), TD (46 %),
  TT (25 %).“ Eine Obergruppe über Gruppen mit verschiedenen Bereichen wird ebenso ignoriert, auch eine Gruppe
  ohne Mitglieder oder ohne Dienste.
- **Schichten ignorieren:** in Schichtplaner 5 ausgeblendete Schichten, Schichten ohne Dienste und Schichten mit
  weniger Diensten als die Grenze (Standard 3). Das gilt nur für Schichten ohne Bedarf: Eine Schicht mit Bedarf
  ist in Gebrauch, auch wenn sie im Zeitraum selten oder gar nicht vorkam. Beispiel: „Nur 2 Dienste im Zeitraum
  (weniger als 3) und kein Bedarf.“
- **Ersatzruhe:** die Abwesenheitsart, deren Name oder Kürzel eines der Wörter enthält (Standard „Ersatzruhe“).
  Passen keine oder mehrere, gibt es keinen Vorschlag.

Was der Ordner nicht wissen kann, entscheidet man selbst: ob eine Gruppe wie ein Servicetelefon oder die
Verwaltung überhaupt in den Plan gehört (dann ausschließen statt übernehmen), und die Kategorie einer Schicht
(leer heißt: aus ihren Zeiten).

**Einstellungen der Vorschläge** (unter der Schaltfläche, gespeichert mit **Einstellungen speichern**):

| Einstellung | Standard | Wirkung |
|---|---|---|
| Anteil für eine Schicht | 80 % | ab diesem Anteil der Dienste ein Bereich, darunter die Wahl; derselbe Anteil für Gruppen ohne eigene Schicht |
| Seltene Schicht: weniger Dienste als | 3 | eine Schicht ohne Bedarf mit weniger Diensten wird ignoriert |
| Zeitraum in Monaten | 0 (alle) | nur die Dienste der letzten Monate bis zum neuesten Dienst |
| Wörter für die Ersatzruhe | Ersatzruhe | durch Komma getrennt, ohne Groß- und Kleinschreibung |

## Im Ordner

Die Tabellen **Schichten im Ordner**, **Gruppen im Ordner** und **Abwesenheitsarten im Ordner** zeigen alles aus
dem Ordner von Schichtplaner 5 und was die Zuordnung im Editor damit macht: welche Regel gilt („Regel 3 → AN,
Früh“), dass es ignoriert oder ausgeschlossen wird, dass eine Gruppe ohne eigene Regel die der übergeordneten
Gruppe übernimmt, oder „keine Regel“. Darunter stehen die Kürzel ohne Regel, die nicht ignoriert werden: Sie
werden nicht importiert, und der Lauf warnt.

![Die Schichten und Gruppen im Ordner mit ihren Regeln](img/zuordnung-ordner.png)
