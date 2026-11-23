package app.dispofold.plugins.sp5;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The problems of a mapping in plain German for the pages ("Es ist noch kein Bereich eingetragen." instead of
 * "'areas' ist leer"). The port keeps the messages of sp5_export.py, which the byte comparison checks; this only
 * rewords them on their way to the pages, with the names the mapping editor uses. A message it does not know
 * passes unchanged.
 */
final class Sp5MappingText {

    /** The lists of the mapping as the editor names them: the section and one of its entries. */
    private static final Map<String, String[]> LISTS = Map.of(
            "areas", new String[] { "Bereiche", "Bereich" },
            "shifts", new String[] { "Schichtregeln", "Schichtregel" },
            "groups", new String[] { "Gruppen", "Gruppenregel" },
            "ignoreShifts", new String[] { "Ignorierte Schichten", "Ignorierte Schicht" },
            "ignoreGroups", new String[] { "Ignorierte Gruppen", "Ignorierte Gruppe" },
            "excludeGroups", new String[] { "Ausgeschlossene Gruppen", "Ausgeschlossene Gruppe" },
            "compensatoryRestAbsences", new String[] { "Ersatzruhe", "Ersatzruhe" },
            "ignoreAbsences", new String[] { "Ignorierte Abwesenheiten", "Ignorierte Abwesenheit" });

    private static final Pattern ENTRY = Pattern.compile("([A-Za-z]+), Eintrag (\\d+): (.*)", Pattern.DOTALL);
    private static final Pattern UNKNOWN = Pattern.compile("unbekannter Schlüssel (.+)", Pattern.DOTALL);
    private static final Pattern REQUIRED = Pattern.compile("Pflichtschlüssel '([A-Za-z]+)' fehlt");
    private static final Pattern NOT_LIST = Pattern.compile("'([A-Za-z]+)' muss eine Liste sein");
    private static final Pattern DOUBLE = Pattern.compile("Bereich (.+) doppelt", Pattern.DOTALL);
    private static final Pattern NO_AREA = Pattern.compile("'areaId' (.+) ist kein Bereich aus 'areas'", Pattern.DOTALL);
    private static final Pattern NO_AREA_IN = Pattern.compile("'areaIds' enthält (.+), keinen Bereich aus 'areas'",
            Pattern.DOTALL);
    private static final Pattern NO_TEXT = Pattern.compile("Muster (.+) ist kein Text", Pattern.DOTALL);
    private static final Pattern NO_REGEX = Pattern.compile(
            "Muster (.+) ist kein gültiger regulärer Ausdruck \\((.*)\\)", Pattern.DOTALL);

    private Sp5MappingText() {
    }

    static List<String> plain(List<String> messages) {
        return messages.stream().map(Sp5MappingText::plain).toList();
    }

    static String plain(String message) {
        switch (message) {
            case "die Zuordnung muss ein JSON-Objekt sein":
                return "Die Zuordnung muss ein JSON-Objekt sein, also mit { beginnen und mit } enden.";
            case "'areas' ist leer":
                return "Es ist noch kein Bereich eingetragen.";
            case "'ignoreShiftsAsAbsence' muss true oder false sein":
                return "„Dienste auf ignorierten Schichten“ muss ja oder nein sein.";
            default:
                break;
        }
        Matcher m;
        if ((m = UNKNOWN.matcher(message)).matches()) {
            return "Unbekannter Schlüssel " + quoted(m.group(1)) + ": Der Import kennt ihn nicht und weist die "
                    + "Zuordnung mit ihm ab.";
        }
        if ((m = REQUIRED.matcher(message)).matches() && LISTS.containsKey(m.group(1))) {
            return "Der Abschnitt „" + LISTS.get(m.group(1))[0] + "“ fehlt.";
        }
        if ((m = NOT_LIST.matcher(message)).matches() && LISTS.containsKey(m.group(1))) {
            return "Der Abschnitt „" + LISTS.get(m.group(1))[0] + "“ muss eine Liste sein.";
        }
        if ((m = ENTRY.matcher(message)).matches() && LISTS.containsKey(m.group(1))) {
            String problem = entry(m.group(3));
            if (problem != null) {
                return LISTS.get(m.group(1))[1] + " " + m.group(2) + ": " + problem;
            }
        }
        return message;
    }

    /** The problem of one entry, or null if unknown. */
    private static String entry(String problem) {
        switch (problem) {
            case "muss ein Objekt sein":
                return "Muss ein JSON-Objekt mit Feldern sein.";
            case "'id' fehlt oder ist kein Text":
                return "Die Id fehlt.";
            case "'name' fehlt oder ist kein Text":
                return "Der Name fehlt.";
            case "'color' fehlt oder ist kein Text":
                return "Die Farbe fehlt.";
            case "'match' fehlt":
                return "Das Muster fehlt.";
            case "'unpopular' muss true oder false sein":
                return "„Fair verteilen“ muss ja, nein oder keine Angabe sein.";
            case "'areaIds' muss eine nicht leere Liste sein":
                return "Es ist kein Bereich angekreuzt.";
            default:
                break;
        }
        if (problem.startsWith("'category' muss ")) {
            return "Die Kategorie muss Früh, Tag, Spät oder Nacht sein, oder leer (aus den Zeiten).";
        }
        Matcher m;
        if ((m = DOUBLE.matcher(problem)).matches()) {
            return "Die Id " + quoted(m.group(1)) + " gibt es schon.";
        }
        if ((m = NO_AREA.matcher(problem)).matches()) {
            // the editor leaves the area empty (''), a file may leave it out (None)
            return m.group(1).equals("None") || m.group(1).equals("''") ? "Es ist kein Bereich gewählt."
                    : "Der Bereich " + quoted(m.group(1)) + " ist unter „Bereiche“ nicht eingetragen.";
        }
        if ((m = NO_AREA_IN.matcher(problem)).matches()) {
            return "Der Bereich " + quoted(m.group(1)) + " ist unter „Bereiche“ nicht eingetragen.";
        }
        if ((m = NO_REGEX.matcher(problem)).matches()) {
            return "Das Muster " + quoted(m.group(1)) + " ist kein gültiger regulärer Ausdruck (" + m.group(2) + ").";
        }
        if ((m = NO_TEXT.matcher(problem)).matches()) {
            return "Das Muster ist leer oder kein Text.";
        }
        return null;
    }

    /**
     * A text as Python writes it ('AN', 're:\\d'), as typed and in German quotation marks („AN“, „re:\d“): escaped
     * backslashes and quotes undone, other escapes kept. Other values as they are.
     */
    private static String quoted(String repr) {
        if (repr.length() >= 2 && (repr.charAt(0) == '\'' || repr.charAt(0) == '"')
                && repr.charAt(repr.length() - 1) == repr.charAt(0)) {
            String inner = repr.substring(1, repr.length() - 1);
            StringBuilder out = new StringBuilder("„");
            for (int i = 0; i < inner.length(); i++) {
                char c = inner.charAt(i);
                if (c == '\\' && i + 1 < inner.length() && "\\'\"".indexOf(inner.charAt(i + 1)) >= 0) {
                    c = inner.charAt(++i);
                }
                out.append(c);
            }
            return out.append('“').toString();
        }
        return repr;
    }
}
