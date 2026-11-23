# Anleitung für die Dienstplanung

Diese Anleitung beschreibt die Arbeit mit Dispofold für alle, die Dienstpläne machen: wie das Programm plant, wie
ein Monat abläuft und was auf jeder Seite steht. Wer neu ist, beginnt mit [Erste Schritte](erste-schritte.md) und
spielt dann [Ein Monat Schritt für Schritt](beispiel.md) mit den Demodaten durch. Die anderen Seiten schlägt man
nach, wenn man sie braucht. Wörter wie Soll, Ziel oder Vorgabe erklärt [Begriffe](begriffe.md).

Die Regeln, nach denen das Programm plant, stehen im Einzelnen in [regeln.md](../regeln.md), das Format der
Datensätze in [datenformat.md](../datenformat.md). Installation und Sicherung sind Sache der IT:
[betrieb.md](../betrieb.md).

## Seiten dieser Anleitung

| Seite | Inhalt |
|---|---|
| [Erste Schritte](erste-schritte.md) | Anmelden am Server, das Programm am eigenen Rechner, die ersten Daten |
| [Ein Monat Schritt für Schritt](beispiel.md) | ein ganzer Monat mit den Demodaten, vom Import bis zur Korrekturdatei |
| [Personen](personen.md) | Liste, Person anlegen und bearbeiten, Einschränkungen, nicht mehr aus dem Quellsystem übernehmen |
| [Bereiche, Schichten und Bedarf](stammdaten.md) | die Stammdaten: Bereiche, Abwesenheitsarten, Schichten, Bedarf, Feiertage |
| [Abwesenheiten, Wünsche und Vorgaben](eingaben.md) | die Eingaben eines Monats |
| [Planen und Prüfen](planen.md) | planen, das Ergebnis lesen, [Probleme](planen.md#probleme) und offener Bedarf |
| [Plan bearbeiten](plan-bearbeiten.md) | Dienstplan, Funktionen, Zeitachse, Zellen von Hand ändern, Dienste auf Anfrage, drucken |
| [Auswertungen](auswertungen.md) | Fairness, die Zahlen einer Person, „Warum dieser Dienst?“ |
| [Monat abschließen](monatsabschluss.md) | Abschluss, Historie, wieder öffnen |
| [Daten und Korrekturdatei](daten.md) | Import, Änderungen behalten, Export, Korrekturdatei, Pseudonymisieren |
| [Regeln einstellen](einstellungen.md) | Planungsziele, Gesetz und Betriebsvereinbarung, Gewichte, mit allen Standardwerten |
| [Updates](updates.md) | Hinweis auf neue Versionen, Update am Server und am eigenen Rechner |
| [Erweiterungen](erweiterungen.md) | Erweiterungen ein- und ausschalten, was sie zeigen |
| [Rolle „nur lesen“](nur-lesen.md) | was die Ansicht sieht und was nicht |
| [Häufige Fragen und Fehlerbehebung](fragen.md) | Meldungen des Programms, ihre Ursache und was zu tun ist |
| [Begriffe](begriffe.md) | die Wörter des Programms von A bis Z |

## So funktioniert es

**Was hineingeht:**

- **Stammdaten:** Personen (mit Wochenstunden, Bereichen, Stundenkonto, Einschränkungen, Familienregeln und
  gewohnter Verteilung), Bereiche, Schichten, Bedarf je Bereich und Tageszeit, Abwesenheitsarten und Feiertage.
  Sie ändern sich selten und kommen meist als Datensatz aus dem Quellsystem.
- **Eingaben des Monats:** Abwesenheiten, Wünsche und Vorgaben.
- **Regeln:** Grenzen aus Kollektivvertrag und Betriebsvereinbarung (Ruhezeiten, Höchstarbeitszeiten, freie
  Sonntage) und die Planungsziele mit ihren Gewichten.
- **Historie:** die Zahlen der abgeschlossenen Vormonate (Nächte, Wochenenden, Feiertage, fair verteilte Dienste,
  erfüllte Wünsche, Stundenkonto, Ersatzruhe).

**Was herauskommt:** ein Dienstplan für den Monat, eine Zelle je Person und Tag: frei, ein Dienst aus den
Schichten ihrer Bereiche oder ein Ersatzruhetag.

**Wie der Solver abwägt.** Der [Solver](begriffe.md#solver) ist der Teil des Programms, der plant. Er bewertet
jeden Plan in drei Gruppen, die streng nacheinander zählen:

1. **Regelverstoß:** Ruhezeiten, Höchstarbeitszeiten, Höchststunden im Monat, freie Sonntage, Qualifikation,
   Abwesenheiten, Sperren. Muss 0 sein. Ein Plan mit Regelverstößen darf so nicht verwendet werden.
2. **Bedarf:** fehlende Besetzung (Lücken) und Besetzung über dem Bedarf, in Personenminuten. Eine fehlende
   Minute wiegt schwerer als jeder ganze Dienst über dem Bedarf.
3. **Qualität:** Stunden treffen, faire Verteilung, Wünsche, Dienstblöcke, Familienregeln, gewohnte Verteilung
   und weitere Regeln, jede mit einem einstellbaren Gewicht.

Ein Plan mit weniger Regelverstößen ist immer besser, egal wie es um Bedarf und Qualität steht; bei gleich vielen
Regelverstößen entscheidet der Bedarf, erst dann die Qualität. Reicht das Personal nicht, bleibt deshalb eine
Lücke sichtbar, statt dass ein Regelverstoß entsteht. Und wer unter seinem Soll liegt, bleibt darunter, statt dass
Stellen doppelt besetzt werden.

**Soll und Ziel.** Das [Soll](begriffe.md#soll) sind die vertraglichen Stunden des Monats (Wochenstunden durch 5
je Arbeitstag). Das [Ziel](begriffe.md#ziel) ist das Soll, korrigiert um einen Teil des Stundenkontos (ein Plus
senkt es, ein Minus erhöht es). Braucht der Bedarf weniger Arbeit, als alle Ziele zusammen ergeben, plant das
Programm gegen ein verkleinertes Ziel: Jedes Ziel schrumpft um denselben Anteil, so muss niemand Stunden über den
Bedarf hinaus sammeln.

**Was fest ist und was nur gewünscht.**

- **Vorgaben** (unter **Eingaben**) sind Dienste oder freie Tage, die fest gelten. Der Solver ändert sie nicht.
- **Fixiert** ist eine Zelle im Plan, die die nächste Planung beibehält. Jede von Hand gesetzte Zelle ist
  fixiert. Fixierungen lassen sich lösen, Vorgaben nicht (sie werden nur unter **Eingaben** entfernt).
- **Abwesenheiten** sind ebenfalls fest: An diesen Tagen gibt es keinen Dienst.
- **Wünsche** erfüllt der Solver, so gut es geht, und verteilt die Erfüllung gleichmäßig auf alle.
- **Familienregeln** (persönliche Regeln je Wochentag) und die **gewohnte Verteilung** sind Qualitätsregeln: Sie
  werden eingehalten, wenn es geht.
- **Nur auf Anfrage:** Eine Schicht mit dieser Einschränkung plant das Programm für die Person nie selbst ein.
  Siehe [Dienste nur auf Anfrage](plan-bearbeiten.md#dienste-nur-auf-anfrage).

**Monate und Historie.** Jeder Monat hat seine Eingaben und seinen Plan. Ein Plan ist zuerst **Entwurf**: Er kann
geändert und neu geplant werden. Ist der Monat fertig, wird er **abgeschlossen**: Seine Zahlen kommen in die
Historie, er ist danach nur lesbar. Die Historie zählt für die Fairness und die Wünsche der Folgemonate, das
Stundenkonto und die Ersatzruhe nach dem Monat für den nächsten. Die letzten Dienste des Vormonats zählen für die
Ruhezeiten am Monatsanfang. Monate werden der Reihe nach abgeschlossen.

## Die Oberfläche

![Die Teile jeder Seite, nummeriert](../screenshots/uebersicht.png)

1. **Menü:** **Dienstplan**, **Prüfen**, **Eingaben**, **Personen**, **Einstellungen** und **Anleitung**. Die
   Rolle „nur lesen“ sieht weniger, siehe [Rolle „nur lesen“](nur-lesen.md). Darunter führt **Quellcode
   (AGPL-3.0)** zum Quellcode des Programms.
2. **Planungsmonat:** Auf den Seiten eines Monats steht oben der Monat, um den es geht. Die Pfeile daneben wechseln
   einen Monat zurück oder vor, die Auswahl reicht ein Jahr zurück und eines voraus. Beim ersten Öffnen ist der
   nächste Monat gewählt, wenn es für ihn Daten gibt, sonst der letzte Monat mit Daten; danach merkt sich der
   Browser die Wahl, bis er geschlossen wird. Seiten, die nicht von einem Monat abhängen (Regeln, Stammdaten,
   Updates), zeigen die Auswahl nicht.
3. **Reiter:** Hat ein Menüpunkt mehrere Seiten, stehen sie als Reiter oben, zum Beispiel **Personen**,
   **Funktionen** und **Zeitachse** im Dienstplan.
4. **Seitentext:** Unter jedem Seitentitel sagt ein kurzer Text, wozu die Seite da ist. **Mehr dazu in der
   Anleitung** führt zum passenden Abschnitt dieser Anleitung.
5. **Erklärungen:** Ein **i** neben einer Regel, einer Einstellung, einer Zahl oder einem Formularfeld öffnet eine
   Erklärung: was die Zahl zählt oder was ein höherer oder niedrigerer Wert bewirkt. Das **i** geht mit Klick,
   Tippen oder Tastatur (Tab, dann Enter oder Leertaste); Escape oder ein Klick daneben schließt die Erklärung. Es
   ist immer nur eine Erklärung offen.
6. **Benutzer und Rolle:** der Name, mit dem man angemeldet ist, und die Rolle **Planung** oder **nur lesen**.
   Daneben **Abmelden** (am Server) oder **Beenden** (Programm am eigenen Rechner).
7. **Hell und dunkel:** Der Knopf mit dem Mond wechselt zwischen hellem und dunklem Design. Ohne Wahl folgt
   Dispofold der Einstellung des Systems; eine Wahl merkt sich der Browser.

![Eine geöffnete Erklärung über dem Dienstplan](../screenshots/hilfe.png)

Meldungen erscheinen unten rechts: Erfolgsmeldungen („Eingaben gespeichert“) verschwinden nach einigen Sekunden,
Fehlermeldungen bleiben, bis man sie schließt. Was eine Meldung bedeutet, steht unter
[Häufige Fragen und Fehlerbehebung](fragen.md).

Der Menüpunkt **Anleitung** führt zu dieser Anleitung. Am Server öffnet er die Dokumentations-Website in einem
neuen Tab (mit Suche, Stand des neuesten Releases). Im Programm am eigenen Rechner zeigt er die eingebaute
Anleitung genau dieser Version, die auch ohne Internet geht; die Adresse der Website steht über jeder Seite, unten
führen Links zur Übersicht und zur vorigen und nächsten Seite.

## Typischer Ablauf

Ein Monat, von den Daten bis zum Abschluss. [Ein Monat Schritt für Schritt](beispiel.md) zeigt denselben Ablauf mit
Bildern.

1. **Daten laden** (einmal und nach jedem neuen Export aus dem Quellsystem): unter **Einstellungen**, **Daten**
   den Datensatz importieren. Was sich selten ändert (Schichten, Bedarf, Feiertage), pflegt man unter
   **Einstellungen**, **Stammdaten**, die Personen unter **Personen**. Siehe [Daten](daten.md#import).
2. **Eingaben erfassen:** unter **Eingaben** die Abwesenheiten (Urlaub, Krankenstand), die Wünsche und die
   Vorgaben des Monats eintragen und **Speichern**. Siehe [Eingaben](eingaben.md).
3. **Planen:** im **Dienstplan** auf **Planen** klicken. Der Solver rechnet einige Minuten im Hintergrund. Siehe
   [Planen](planen.md#planen).
4. **Prüfen:** Die Zeile über dem Plan sagt, ob er verwendbar ist. Unter **Prüfen** stehen alle Regelverstöße,
   der offene Bedarf mit den Personen, die man fragen kann, und Auffälligkeiten in den Stammdaten. Siehe
   [Prüfen](planen.md#probleme).
5. **Von Hand nachbessern:** Zellen im Dienstplan ändern, Lücken mit Diensten auf Anfrage füllen, bei Bedarf
   noch einmal **Planen** (die von Hand gesetzten Zellen bleiben dabei fixiert). Siehe
   [Von Hand ändern und fixieren](plan-bearbeiten.md#von-hand-ändern-und-fixieren).
6. **Drucken und exportieren:** im **Dienstplan** das Menü **Ausgabe**: **Drucken** oder Export als XLSX, CSV
   oder JSON. Siehe [Drucken](plan-bearbeiten.md#drucken) und [Exportieren](daten.md#exportieren).
7. **Monat abschließen:** im Menü **Ausgabe** oder unter **Einstellungen**, **Daten**. Die Zahlen des Monats
   kommen in die Historie, der nächste Monat baut darauf auf. Siehe [Monat abschließen](monatsabschluss.md).
8. **Änderungen zurückgeben:** Wurden Personen oder Bedarf in Dispofold geändert, die Korrekturdatei für das
   Exportprogramm speichern. Siehe [Korrekturdatei](daten.md#korrekturdatei).
