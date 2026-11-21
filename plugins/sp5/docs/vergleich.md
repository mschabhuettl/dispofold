# Vergleich mit sp5-export

Die Erweiterung **Schichtplaner 5** soll für denselben Ordner, dieselben Monate, Optionen, Zuordnung und
Korrekturen genau das liefern, was `sp5_export.py` liefert: denselben Datensatz, denselben Bericht und dieselben
Warnungen, Byte für Byte. Für synthetische Daten prüft das die CI bei jeder Änderung. Mit echten Daten prüft man
es einmal so; die Daten verlassen dabei den Rechner nicht.

## Was man braucht

- Dispofold mit der eingeschalteten und eingestellten Erweiterung (Server oder Programm am eigenen Rechner).
- Am Rechner mit dem Ordner von Schichtplaner 5: Python 3.10 oder neuer und sp5-export (der Ordner mit
  `sp5_export.py`), dazu `sp5-vergleich.py` aus dem Ordner `plugins/sp5/tools/` von Dispofold.

## Schritte

1. In Dispofold unter **Einstellungen → Erweiterungen → Schichtplaner 5** bei **Ergebnis als Dateien speichern**
   den Monat wählen und **Ergebnis speichern**. Es kommt eine ZIP-Datei, etwa `dispofold-sp5-ergebnis-2027-01.zip`.
   Sie enthält personenbezogene Daten: nur auf diesem Rechner ablegen.
2. In einer Eingabeaufforderung (Windows) oder einem Terminal (Linux):

   ```text
   python -X utf8 sp5-vergleich.py --ergebnis dispofold-sp5-ergebnis-2027-01.zip --daten <Ordner von Schichtplaner 5> --sp5-export <Ordner von sp5-export>
   ```

   Das Skript ruft `sp5_export.py` mit genau den Optionen, der Zuordnung und der Korrekturdatei aus der ZIP-Datei
   auf und vergleicht die drei Dateien.
3. Es zeigt das Ergebnis und speichert es als `sp5-vergleich.txt`. Diese Datei zurückschicken: Sie enthält nur, ob
   jede Datei identisch ist, und bei Unterschieden die Stellen im Datenformat mit Positionen statt Ids, Spalten des
   Berichts und Anzahlen, keine Namen, Kürzel, Ids, Daten oder Werte.
4. Die ZIP-Datei danach löschen.

Steht am Ende **ERGEBNIS: alles identisch**, liefert die Erweiterung mit diesen Daten dasselbe wie `sp5_export.py`.
Eine Zyklus-Zuordnung ohne Ende ist der einzige gewollte Unterschied (siehe
[Import aus Schichtplaner 5](plugin-sp5-sp5.md#zyklen)).
