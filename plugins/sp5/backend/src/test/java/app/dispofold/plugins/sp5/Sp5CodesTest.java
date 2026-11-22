package app.dispofold.plugins.sp5;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;

import app.dispofold.plugins.sp5.port.Sp5ExportTest;

/** Groups without a rule of their own take that of the group above them, as the import does. */
class Sp5CodesTest {

    @Test
    @SuppressWarnings("unchecked")
    void aGroupWithoutARuleTakesThatOfTheGroupAbove(@TempDir Path dir) throws Exception {
        Sp5ExportTest.folder(dir);
        // 3 under 1 (a rule), 4 under 3 (two levels), 5 under 6 under 5 (a loop, no rule), 7 under an unknown group
        Sp5ExportTest.groups(dir, new Object[] { 1L, "Team Annahme", "TA", 0L },
                new Object[] { 2L, "Verwaltung", "VW", 0L }, new Object[] { 3L, "Annahme Nord", "AN", 1L },
                new Object[] { 4L, "Annahme Nord Nacht", "ANN", 3L }, new Object[] { 5L, "Ring A", "RA", 6L },
                new Object[] { 6L, "Ring B", "RB", 5L }, new Object[] { 7L, "Lose", "LO", 99L });
        Object mapping = new ObjectMapper().readValue(Sp5ExportTest.MAPPING, Map.class);
        Map<String, Object> codes = Sp5Codes.codes(dir, mapping, new ArrayList<>());
        Map<String, Object> results = new java.util.LinkedHashMap<>();
        for (Object g : (List<Object>) codes.get("groups")) {
            Map<String, Object> group = (Map<String, Object>) g;
            results.put((String) group.get("name"), group.get("result"));
        }
        Map<String, Object> rule = (Map<String, Object>) results.get("Team Annahme");
        assertThat(rule.get("kind")).isEqualTo("rule");
        for (String name : List.of("Annahme Nord", "Annahme Nord Nacht")) {
            assertThat(results.get(name)).as(name).isEqualTo(Map.of("kind", "inherited", "from", "1", "fromName",
                    "Team Annahme", "result", rule));
        }
        for (String name : List.of("Ring A", "Ring B", "Lose")) {
            assertThat(results.get(name)).as(name).isEqualTo(Map.of("kind", "none"));
        }
    }
}
