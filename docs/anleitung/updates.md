# Updates

## Neue Version

Gibt es eine neuere Version von Dispofold, steht oben der Hinweis **Neue Version x.y.z verfügbar**. Ihn sieht nur
die Planung, die Rolle **nur lesen** nie. **Was ist neu?** zeigt die Notizen zu dieser Version und die Seite der
Version auf GitHub. Das **×** blendet den Hinweis für diese Version aus; erst bei der nächsten Version erscheint
er wieder.

- **Server (Docker):** Der Server sucht selbst, beim Start und einmal am Tag. Einspielen muss die IT das Update;
  das Fenster nennt die Schritte (Datenbank sichern, `TAG` auf die neue Version setzen, Images holen, neu
  starten). Die IT kann die Suche abschalten.
- **Programm am eigenen Rechner:** Beim ersten Start fragt das Programm einmal: **Soll Dispofold automatisch nach
  neuen Versionen suchen?** Mit **Ja** sucht es bei jedem Start auf GitHub, mit **Nein** nicht. Gesendet werden
  dabei keine Daten. **Jetzt installieren** im Fenster des Hinweises spielt das Update ein, Dispofold startet
  danach neu. Ändern lässt sich das jederzeit unter [Updates](#updates-im-programm-am-eigenen-rechner).

## Updates im Programm am eigenen Rechner

Nur im Programm am eigenen Rechner: die installierte Version, die **Update-Quelle** (eine Adresse oder ein Ordner,
nach **Ja** beim ersten Start die Versionen auf GitHub, nach **Nein** leer; leer heißt: keine Suche),
**Beim Start nach Updates suchen**, **Jetzt suchen** und **Update installieren**. **Update
aus Datei einspielen** ist für Rechner ohne Internetzugang: ein signiertes Update-Paket auswählen. Dispofold
startet danach neu. Einzelheiten in [desktop.md](../desktop.md).
