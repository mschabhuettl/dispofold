# Erweiterungen

Erweiterungen bringen zusätzliche Seiten und Funktionen ins Programm, zum Beispiel einen Import aus einem anderen
Planungssystem. Sie kommen mit Dispofold mit; nachgeladen wird nichts. Jede Erweiterung ist aus, bis die Planung
sie einschaltet. Die veröffentlichten Versionen enthalten eine Erweiterung für den Import aus dem bisherigen
Planungssystem. Was sie tut und wie man sie einstellt, steht auf ihren Hilfeseiten, die nach dem Einschalten in
dieser Anleitung hinter den Begriffen erscheinen. Der Einstieg, Schritt für Schritt mit Bildern:
[Echte Daten aus dem bisherigen Planungsprogramm übernehmen](plugin-sp5-echte-daten.md).

## Einschalten und ausschalten

Unter **Einstellungen → Erweiterungen** steht jede Erweiterung mit Name, Version und Beschreibung. Die Seite
sieht nur die Planung.

![Einstellungen → Erweiterungen mit der mitgelieferten Import-Erweiterung](plugin-sp5-img/erweiterungen.png)

1. Bei der Erweiterung **Eingeschaltet** anhaken (im Bild eingerahmt, wie der Reiter **Erweiterungen**).
2. Die Rückfrage bestätigen. Eine eingeschaltete Erweiterung sieht alle Planungsdaten, wie die Planung selbst.
3. Das Programm zeigt sich neu, mit den Seiten der Erweiterung im Menü. Hat sie Einstellungen, stehen sie darunter
   auf derselben Seite.

Ausschalten geht genauso. Die Seiten der Erweiterung verschwinden dann aus dem Menü; ihre Einstellungen bleiben
gespeichert und gelten wieder, sobald man sie einschaltet.

Enthält das Programm keine Erweiterung, steht dort **Dieses Programm enthält keine Erweiterungen.** Am Server
kann die IT eine Erweiterung ganz ausblenden; sie erscheint dann auch hier nicht (siehe
[Betrieb](../betrieb.md#erweiterungen)).

## Was Erweiterungen zeigen

- **Seiten:** im Menü, als eigener Eintrag oder als weiterer Reiter einer Gruppe. Seiten für die Rolle **nur lesen**
  sieht diese Rolle auch; alles andere nur die Planung.
- **Import:** Eine Erweiterung, die Daten liefert, importiert auf einer eigenen Seite; die mitgelieferte
  Erweiterung für das bisherige Planungsprogramm auf ihrer Seite **Lauf und Import**. Eine Erweiterung kann statt
  dessen auch einen eigenen Abschnitt auf der Seite **Daten** haben. Der Import läuft in beiden Fällen wie beim
  Hochladen eines Datensatzes: mit der Wahl, die Änderungen aus Dispofold zu behalten, und mit derselben Vorschau
  (siehe [Daten und Korrekturdatei](daten.md)).
- **Ordner:** Liest eine Erweiterung Dateien, wählt man den Ordner im Programm. Am Server sind nur die Ordner
  wählbar, die die IT freigegeben hat. Am eigenen Rechner öffnet **Ordner wählen …** den Ordnerdialog; der gewählte
  Ordner bleibt danach freigegeben.
- **Hilfe:** Die Hilfeseiten einer eingeschalteten Erweiterung stehen in dieser Anleitung hinter den Begriffen.
