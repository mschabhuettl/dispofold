# Abwesenheiten, Wünsche und Vorgaben

Unter **Eingaben** steht, was für den gewählten Monat feststeht oder gewünscht ist: wer abwesend ist, was sich
Personen wünschen und welche Dienste fest gelten. Die Eingaben kommen meist mit dem Datensatz aus dem Quellsystem;
hier ergänzt und ändert man sie, zum Beispiel bei einer Krankmeldung nach dem Planen.

![Die Seite Eingaben, nummeriert](../screenshots/eingaben.png)

1. **Person:** zeigt nur die Einträge einer Person (Standard: **Alle Personen**). Neue Einträge sind dann für
   diese Person vorbelegt. Gespeichert wird trotzdem immer der ganze Monat.
2. [Abwesenheiten](#abwesenheiten)
3. [Wünsche](#wünsche)
4. [Vorgaben](#vorgaben)
5. **Speichern** übernimmt alle Änderungen der Seite auf einmal. Der Knopf steht in einer Leiste, die beim
   Scrollen unten im Fenster bleibt.

Die Links **Abwesenheiten**, **Wünsche** und **Vorgaben** unter dem Titel springen zur jeweiligen Liste.

So trägt man etwas ein: in der Zeile über einer Liste die Felder wählen, **Hinzufügen**, dann **Speichern**. Die
neue Zeile ist hervorgehoben, das Programm scrollt zu ihr, und sie trägt bis zum Speichern den Hinweis
„Hinzugefügt, noch nicht gespeichert“. **Entfernen** am Ende einer Zeile nimmt einen Eintrag heraus. Solange etwas
nicht gespeichert ist, steht **Ungespeicherte Änderungen** neben dem Knopf; wer die Seite oder den Monat dann
wechselt oder den Browser schließt, wird gefragt, ob die Änderungen verloren gehen sollen.

Nach dem Speichern zeigt ein schon geplanter Monat „Plan veraltet: neu planen“: Der Plan kennt die neuen Eingaben
noch nicht. In einem abgeschlossenen Monat sind die Eingaben nur lesbar; zum Ändern den Monat zuerst wieder öffnen
(siehe [Monat abschließen](monatsabschluss.md#monat-wieder-öffnen)).

## Abwesenheiten

Felder: **Person**, **Von**, **Bis** (der letzte Tag zählt mit; für einen einzelnen Tag dasselbe Datum) und **Art**
(eine Abwesenheitsart aus den Stammdaten, zum Beispiel „U Urlaub“ oder „K Krankenstand“). Eine Abwesenheit über
mehrere Tage steht als ein Eintrag mit Zeitraum und Zahl der Tage in der Liste.

- An diesen Tagen plant das Programm keinen Dienst. Ein Dienst trotz Abwesenheit ist ein Regelverstoß; er ist im
  Plan rot.
- Ob die Tage wie gearbeitet zählen (das Soll senken) oder vom Stundenkonto gehen, legt die Abwesenheitsart fest
  (siehe [Abwesenheitsarten](stammdaten.md#abwesenheitsarten)).
- **Wann ändern:** sobald eine Abwesenheit bekannt wird. Kommt eine Krankmeldung nach dem Planen, zeigt der
  Dienstplan danach einen Regelverstoß an diesem Tag; wie man ihn behebt, zeigt
  [Ein Monat Schritt für Schritt](beispiel.md#8-eine-krankmeldung-nach-dem-planen).

## Wünsche

Felder: **Person**, **Datum**, **Art** und **Schicht (bei Schichtwunsch)**; die Schicht lässt sich nur bei
„diese Schicht“ und „diese Schicht nicht“ wählen. Die Arten:

| Art | Bedeutung |
|---|---|
| frei | kein Dienst an diesem Tag |
| Dienst | irgendein Dienst |
| diese Schicht | genau die gewählte Schicht |
| diese Schicht nicht | jede andere Schicht oder frei |

- Je Person und Tag gibt es einen Wunsch; ein neuer ersetzt den alten.
- Die Planung erfüllt Wünsche, so gut es geht, und verteilt die Erfüllung gleichmäßig: Wem in den Vormonaten
  weniger Wünsche erfüllt wurden, der kommt eher zum Zug.
- Je Person zählen höchstens so viele Wünsche, wie unter [Regeln](einstellungen.md#planungsziele) eingestellt ist
  (Standard 6), und zwar die ersten nach Datum. Die Spalte **Wünsche dieser Person (max. 6)** zeigt, wie viele es
  sind (in der Überschrift steht die eingestellte Zahl), und
  „(zu viele)“, wenn es mehr sind; die späteren zählen dann nicht.
- Ein Wunsch ist keine Zusage. Was fest gelten muss, ist eine Vorgabe.

## Vorgaben

Felder: **Person**, **Datum** und **Dienst**: eine Schicht aus den Bereichen der Person oder **frei (freier Tag)**
(Standard).

- Eine Vorgabe gilt fest. Die Planung ändert sie nie, auch nicht beim **Fixierungen lösen**. Entfernen lässt sie
  sich nur hier.
- Je Person und Tag gibt es eine Vorgabe; eine neue ersetzt die alte.
- **Wann verwenden:** für Dienste, die schon vereinbart sind (eine Schulung als Dienst, ein Tausch, ein Termin),
  und für freie Tage, die fest zugesagt sind. Für Änderungen, die nur diesen Plan betreffen, reicht es, die Zelle
  im Dienstplan von Hand zu setzen (sie ist dann [fixiert](begriffe.md#fixiert)).
- Eine Vorgabe kann eine Regel verletzen, etwa die Ruhezeit nach einem Nachtdienst. Dann zeigt die Prüfung einen
  Regelverstoß, und die Vorgabe ist zu ändern.

## Wenn jemand anderer inzwischen gespeichert hat

Im Serverbetrieb können mehrere Personen zugleich planen. Hat jemand anderer die Eingaben desselben Monats
gespeichert, seit man die Seite geöffnet hat, fragt das Programm beim **Speichern** in einem Fenster des Browsers:

- **OK:** Die eigenen Änderungen werden in die neue Fassung übernommen. Hat die andere Person denselben Eintrag
  (gleiche Person, gleicher Tag) anders geändert, gilt die eigene Änderung. Die Meldung nennt, wie viele Einträge
  das betrifft. Danach die Eingaben prüfen und noch einmal **Speichern**.
- **Abbrechen:** Die eigenen Änderungen werden verworfen, die neue Fassung wird geladen.
