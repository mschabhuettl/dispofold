# Planen und Prüfen

### Planen

1. **Planen** startet den Solver. Er rechnet im Hintergrund, die Laufzeit steht in der Zeile über dem Plan.
   Während der Planung ist der Plan gesperrt: Zellen, Export und Monatsabschluss gehen erst danach.
2. Die Planung endet von selbst nach der Schrittzahl, spätestens nach dem Zeitlimit. **Stopp** beendet sie
   früher. Der beste Stand bis dahin bleibt erhalten.

Eine neue Planung baut auf dem gespeicherten Plan auf. **Fixierungen lösen** löst alle Fixierungen des Monats;
Vorgaben aus den **Eingaben** bleiben dabei erhalten.

Unter **Erweitert** (zugeklappt; alle Felder optional, ein leeres Feld nimmt den voreingestellten Wert):

- **Startwert (Seed):** Gleiche Daten, gleicher Ausgangsplan, gleicher Startwert und gleiche Schrittzahl ergeben
  denselben Plan, sofern die Planung vor dem Zeitlimit endet. Ein anderer Startwert ergibt einen anderen, ähnlich
  guten Plan.
- **Zeitlimit (s):** Sicherheitsnetz für die Rechenzeit.
- **Schrittzahl:** Obergrenze der Rechenschritte.
- **Neu beginnen:** Der gespeicherte Plan wird nicht als Ausgangspunkt genommen, nur fixierte Dienste und
  Vorgaben bleiben. Hilft, wenn der Plan in einer schlechten Lösung festsitzt. Eine Wiederholung mit demselben
  Ergebnis gelingt nur mit diesem Häkchen.
- **Score:** die Bewertung des Plans in den drei Gruppen.

Die voreingestellten Werte (im Serverbetrieb Startwert 1, 250 000 Schritte, 300 Sekunden) legt die IT fest
(siehe [betrieb.md](../betrieb.md)).

### Das Ergebnis lesen

Die Zeile über dem Plan:

- **Überschrift** in Farbe: grün „Plan verwendbar: keine Regelverstöße, keine Lücken“, gelb die Zahl der Lücken
  mit den fehlenden Personenstunden (fehlende Personen mal Stunden), rot die Zahl der Regelverstöße.
- **Regeln mit Treffern** darunter: je Regel mit Regelverstößen und je Qualitätsregel mit großem Anteil an der
  Bewertung (höchstens die fünf größten) ein Knopf mit der Zahl der Treffer. Ein Klick listet Person und Tag mit
  einer kurzen Erklärung; ein Klick auf einen Eintrag öffnet die Seite der Person. Die ganze Liste steht unter
  [Prüfen](#prüfen).
- **Status:** **Entwurf** oder **abgeschlossen**.
- **Überbesetzung:** Personenstunden über der Höchstbesetzung (wo keine gesetzt ist, über der Mindestbesetzung)
  und Dienste, die keinen Bedarf abdecken.
- **Einzelne freie Tage:** freie Tage zwischen zwei Diensten (wenig Erholung); hat die Regel oben einen Knopf,
  steht die Zahl nur dort. **Tage außerhalb der
  Blocklänge:** um wie viele Tage die Dienstblöcke kürzer oder länger sind als unter **Regeln** eingestellt.
  Beide sollen klein sein; sie sind keine Fehler, sondern ein Maß für die Güte des Plans.
- **Plan veraltet: neu planen**, siehe [unten](#hinweis-plan-veraltet-neu-planen).

Ein Klick auf die Zahl eines Tages in der Zeile **Bedarfslücken** listet die Lücken dieses Tages mit Bereich,
Uhrzeit und fehlender Besetzung, jeweils mit der Liste **Auf Anfrage verfügbar**.

Wichtige Regeln im Einzelnen (alle mit **i** auf der Seite **Regeln**):

- **Freie Sonntage:** Jede Person hat im Kalendermonat mindestens so viele Sonntage ohne Dienst wie eingestellt
  (Standard 2; 0 schaltet die Regel aus). Ein Sonntag ist nur frei, wenn kein Dienst ihn berührt, auch kein
  Nachtdienst von Samstag auf Sonntag; Abwesenheiten zählen als frei.
- **Höchststunden im Monat:** Die Stunden einer Person im Monat (Dienste, angerechnete Abwesenheitstage und
  Ersatzruhe) dürfen das Ziel höchstens um eine Toleranz übersteigen, voreingestellt 10 %. Das Programm plant nie
  darüber. Ein von Hand gesetzter Dienst darf darüber gehen, zählt dann aber als Regelverstoß. Die gesetzlichen
  Grenzen wie die Höchstarbeitszeit je Woche gelten unabhängig davon. Für die Obergrenze zählt immer das echte,
  nicht das verkleinerte Ziel.
- **Bedarfslücken** sind kein Fehler des Solvers: Reicht das Personal nicht, bleibt die Lücke sichtbar. Ist
  jemand frei und darf den Dienst machen, wird eine Lücke immer zuerst gefüllt.

### Hinweis „Plan veraltet: neu planen“

Neben dem Status kann der Hinweis **Plan veraltet: neu planen** stehen. Er bedeutet: Der Plan passt nicht mehr
ganz zu den Daten, bitte neu planen. Der Grund steht im Tooltip:

- Eingaben (Abwesenheiten, Wünsche, Vorgaben), Stammdaten, Regeln oder Gewichte wurden nach dieser Planung
  geändert, oder die Historie der Vormonate.
- Der Plan des Vormonats wurde nach dieser Planung geändert. Die Ruhezeiten am Monatsanfang hängen von den
  letzten Diensten des Vormonats ab, deshalb nimmt der Solver sie aus dem gespeicherten Plan des Vormonats.

## Prüfen

![Prüfen](../screenshots/pruefen.png)

**Prüfen** hat zwei Reiter: **Probleme** und **Fairness**. Die Rolle „nur lesen“ hat statt **Prüfen** den
Menüpunkt **Fairness**.

### Probleme

Die Seite listet alles, was am Plan des gewählten Monats nicht stimmt, in drei Abschnitten:

- **Regelverstöße und Qualität:** jeder Regelverstoß, jede Überbesetzung und die Qualitätsregeln mit großem
  Anteil an der Bewertung (dieselben wie die Knöpfe über dem Dienstplan), ein Eintrag je Treffer. **Gruppieren
  nach** ordnet die Liste nach **Regel**, **Person** oder **Tag**; die drei Auswahlen daneben zeigen nur eine
  Regel, eine Person oder einen Tag. Jede Gruppe klappt mit einem Klick auf ihre Überschrift auf und zu; bei
  langen Listen ist nur die erste offen. Ein Eintrag nennt die Gruppe (**Regelverstoß**, **Bedarf**,
  **Qualität**), die Person (ein Klick öffnet ihre Seite) oder bei Bedarfseinträgen die **Zeitachse** (sie öffnet
  sich auf Bereich und Tag des Eintrags), den Tag und eine kurze Erklärung.
- **Offener Bedarf:** die Lücken nach Tagen, je Lücke Bereich, Uhrzeit, wie viele Personen fehlen, welche
  Funktionen des Bereichs in diese Zeit fallen und ein Link zur **Zeitachse** auf Bereich und Tag. **Auf Anfrage verfügbar** klappt die Personen auf, die man fragen
  kann, siehe [Dienste nur auf Anfrage](plan-bearbeiten.md#dienste-nur-auf-anfrage). Während einer Planung und in einem
  abgeschlossenen Monat fehlt dieser Knopf.
- **Stammdaten:** unabhängig vom Plan, was in den Stammdaten fehlt oder nicht zusammenpasst, zum Beispiel eine
  Person ohne Bereich, ein Bereich ohne Bedarf oder eine gewohnte Verteilung, die nicht 100 % ergibt. Jeder
  Eintrag sagt, was zu tun ist, und verweist auf die Person, die **Stammdaten** oder die **Regeln**.

![Offener Bedarf mit der Liste Auf Anfrage verfügbar](../screenshots/pruefen-bedarf.png)

Ohne Plan zeigt die Seite nur die Prüfung der Stammdaten.
