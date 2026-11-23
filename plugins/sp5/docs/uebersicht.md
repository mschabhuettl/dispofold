# Schichtplaner 5: Lauf und Import

Die Seite **Schichtplaner 5 → Lauf und Import** führt den Export aus, ohne zu importieren, zeigt sein Ergebnis und
importiert genau diesen Lauf. Sie ist die einzige Stelle, an der aus Schichtplaner 5 importiert wird. Den ganzen Weg
beschreibt [Ablauf](plugin-sp5-ablauf.md), den kurzen Weg für den Anfang
[Echte Daten aus Schichtplaner 5](plugin-sp5-echte-daten.md).

![Lauf und Import nach einem Lauf](img/uebersicht.png)

## Export ausführen

- **Erster Monat** und **Letzter Monat:** der Zeitraum, für mehrere Monate bis zu 12 Monate nach dem ersten.
  Leer beim letzten Monat heißt: nur der erste.
- **Optionen:** vorbelegt mit denen aus den Einstellungen. Was man hier ändert, gilt nur für diesen Lauf und
  seinen Import; **Als Standard speichern** übernimmt die Optionen in die Einstellungen. Was jede Option tut, sagt
  ihr „i“ und die Tabelle unter [Import aus Schichtplaner 5](plugin-sp5-sp5.md#einschalten-und-einstellen).
- **Ausführen:** liest den Ordner, wendet die Korrekturdatei von Dispofold an und rechnet den Datensatz. Danach
  stehen die Zahlen des Laufs da: Personen, Bereiche, Schichten, Dienste, Bedarfsregeln, Warnungen,
  Berichtszeilen und Zeilen der Korrekturdatei, dazu die Zeit des Laufs und die Monate.

Von dort führen Links zum [Bericht](plugin-sp5-bericht.md), zur [Zuordnung](plugin-sp5-zuordnung.md), wenn
Kürzel ohne Regel geblieben sind, zum [Vergleich](plugin-sp5-lauf-vergleich.md) und zum Import.

Fehlt noch die Zuordnung, steht oben „Die Zuordnung fehlt“ mit dem Link **Zur Zuordnung**; fehlt der Ordner, führt
der Link zu den Einstellungen der Erweiterung.

Der letzte Lauf bleibt, bis jemand den nächsten ausführt oder der Server neu startet. Danach sagen die Seiten
„Noch kein Lauf seit dem Start des Servers“; dann einfach noch einmal **Ausführen**.

## Diesen Lauf importieren

Importiert wird immer der letzte Lauf, mit seinen Monaten und Optionen. Hat sich seit dem Lauf etwas geändert
(jemand hat einen neuen Lauf ausgeführt, die Zuordnung, eine Korrektur in Dispofold, die Optionen oder der Ordner),
lehnt Dispofold den Import ab: dann noch einmal **Ausführen**, damit Bericht und Vergleich zeigen, was importiert
wird. **Änderungen aus Dispofold behalten** anhaken, wenn seit dem letzten Import in Dispofold etwas geändert
wurde, dann **Importieren**; die Vorschau zeigt, was übernommen wird, Konflikte und was sich nicht übertragen lässt
(siehe [Daten und Korrekturdatei](daten.md)).

![Die Vorschau des Imports](img/import.png)

Ist ein Monat des Laufs schon abgeschlossen, steht über dem Import ein Hinweis mit dem nächsten Schritt: Ändert der
Import einen abgeschlossenen Monat, lehnt Dispofold ihn ab. Dann einen späteren ersten Monat wählen und noch einmal
ausführen, oder den Monat unter **Einstellungen → Daten** mit **Monat wieder öffnen** öffnen und dann importieren.
Der Link im Hinweis führt dorthin und wählt den Monat schon; sind mehrere abgeschlossen, wird der neueste zuerst
wieder geöffnet.

Gibt es noch gar keine Korrekturen (keine geladene Korrekturdatei, keine Änderung in Dispofold), steht über dem
Import ein Hinweis mit dem Weg zu [Korrekturdatei laden](daten.md#korrekturdatei-laden). Wer bisher mit
`sp5_export.py --corrections` exportiert hat, lädt seine `korrekturen.json` dort einmal vor dem ersten Import.

![Der Hinweis über dem Import, solange es keine Korrekturen gibt](img/hinweis.png)

## Ergebnis speichern

**Ergebnis speichern** neben **Ausführen** (und in den Einstellungen der Erweiterung) speichert Datensatz, Bericht
und Warnungen eines Laufs als ZIP-Datei, für den Vergleich mit dem früheren Exportprogramm (siehe
[Technik und Vergleich mit sp5-export](plugin-sp5-technik.md#vergleich-mit-echten-daten)). Die Datei enthält
personenbezogene Daten.
