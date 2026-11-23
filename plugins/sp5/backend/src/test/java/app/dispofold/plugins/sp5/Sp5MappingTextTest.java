package app.dispofold.plugins.sp5;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import app.dispofold.plugins.sp5.port.PyJson;
import app.dispofold.plugins.sp5.port.Sp5Export;

/** Every problem the port names in a mapping comes to the pages in plain German, with the editor's names. */
class Sp5MappingTextTest {

    private static final List<String> BAD = List.of("[]",
            "{\"extra\": 1}",
            "{\"areas\": 1, \"shifts\": 1, \"groups\": 1, \"ignoreShifts\": 1, \"ignoreShiftsAsAbsence\": 1}",
            "{\"areas\": [], \"shifts\": [], \"groups\": []}",
            "{\"areas\": [3, {\"id\": \"AN\"}, {\"id\": \"AN\", \"name\": \"x\", \"color\": \"y\"},"
                    + " {\"name\": \"a\", \"color\": \"b\"}],"
                    + " \"shifts\": [3, {}, {\"match\": \"\", \"areaId\": \"XX\", \"category\": \"FOO\", \"unpopular\": 1},"
                    + " {\"match\": \"re:(\", \"areaId\": \"AN\"}],"
                    + " \"groups\": [{\"match\": \"x\", \"areaIds\": []}, {\"match\": \"y\", \"areaIds\": [\"ZZ\"]}],"
                    + " \"ignoreAbsences\": [3]}");

    @Test
    void everyProblemOfThePortIsReworded() throws Exception {
        List<String> all = new ArrayList<>();
        for (String bad : BAD) {
            for (String message : Sp5Export.mappingErrors(PyJson.load(bad))) {
                String plain = Sp5MappingText.plain(message);
                assertThat(plain).as(message).isNotEqualTo(message).doesNotContain("Eintrag")
                        .doesNotContainPattern("'(areas|shifts|groups|areaId|areaIds|match|id|name|color)'")
                        .endsWith(".");
                all.add(plain);
            }
        }
        assertThat(all).hasSize(28).contains("Es ist noch kein Bereich eingetragen.",
                "Die Zuordnung muss ein JSON-Objekt sein, also mit { beginnen und mit } enden.",
                "Der Abschnitt „Schichtregeln“ fehlt.",
                "Unbekannter Schlüssel „extra“: Der Import kennt ihn nicht und weist die Zuordnung mit ihm ab.",
                "Bereich 3: Die Id „AN“ gibt es schon.",
                "Schichtregel 2: Es ist kein Bereich gewählt.",
                "Schichtregel 3: Der Bereich „XX“ ist unter „Bereiche“ nicht eingetragen.",
                "Schichtregel 4: Das Muster „re:(“ ist kein gültiger regulärer Ausdruck (missing ), unterminated "
                        + "subpattern, Position 0).",
                "Gruppenregel 1: Es ist kein Bereich angekreuzt.",
                "Ignorierte Abwesenheit 1: Das Muster ist leer oder kein Text.");
    }

    @Test
    void anUnknownMessagePassesUnchanged() {
        assertThat(Sp5MappingText.plain("etwas anderes")).isEqualTo("etwas anderes");
    }
}
