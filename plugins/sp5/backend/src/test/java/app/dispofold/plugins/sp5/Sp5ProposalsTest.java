package app.dispofold.plugins.sp5;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;

import app.dispofold.plugins.sp5.port.ProposalFolder;
import app.dispofold.plugins.sp5.port.PyJson;
import app.dispofold.plugins.sp5.port.Sp5Export;

/** The mapping proposals on a small synthetic folder ({@link ProposalFolder}). */
@SuppressWarnings("unchecked")
class Sp5ProposalsTest {

    static Sp5Proposals.Settings settings(int share, int minDuties, int months, List<String> words, Set<String> gone) {
        return new Sp5Proposals.Settings(share, minDuties, months, words, gone);
    }

    static final Sp5Proposals.Settings DEFAULTS = settings(80, 3, 0, List.of("Ersatzruhe"), Set.of());

    static Map<String, Object> propose(Path dir, Object mapping, Sp5Proposals.Settings s) {
        return Sp5Proposals.propose(dir, mapping, s);
    }

    /** "code or name -> what is proposed" of a section, in a short form. */
    static Map<String, String> brief(Map<String, Object> proposals, String section) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map<String, Object> p : (List<Map<String, Object>>) proposals.get(section)) {
            String key = (String) p.getOrDefault("code", p.get("name"));
            Object kind = p.get("kind");
            out.put(key, switch (section) {
            case "areas" -> p.get("id") + " " + p.get("name");
            case "compensatoryRest" -> "rest";
            default -> "rule".equals(kind) ? (p.containsKey("areaId") ? (String) p.get("areaId")
                    : String.join("+", (List<String>) p.get("areaIds")))
                    : "choice".equals(kind) ? "choice " + ((List<Map<String, Object>>) p.get("choices")).stream()
                            .map(c -> c.get("areaId") + " " + c.get("share")).toList()
                            : String.valueOf(kind);
            });
        }
        return out;
    }

    @Test
    void anEmptyMappingGetsAFullFirstMappingWithAReasonEach(@TempDir Path dir) throws Exception {
        ProposalFolder.write(dir, "2027-01");
        Map<String, Object> p = propose(dir, Map.of(), DEFAULTS);
        assertThat(brief(p, "areas")).containsExactly(Map.entry("Team Annahme", "TA Team Annahme"),
                Map.entry("Team Disposition", "TD Team Disposition"), Map.entry("Technik", "TE Technik"),
                Map.entry("Nord", "NO Nord"), Map.entry("Süd", "SU Süd"));
        assertThat(brief(p, "shifts")).containsOnly(Map.entry("A1", "TA"), Map.entry("A2", "TA"),
                Map.entry("R1", "TD"), Map.entry("M1", "choice [TA 50, TD 50]"), Map.entry("Z1", "ignored"),
                Map.entry("H1", "ignored"), Map.entry("T1", "TE"), Map.entry("T2", "TE"), Map.entry("N1", "NO"),
                Map.entry("S2", "SU"), Map.entry("Z0", "ignored"));
        // no proposal for the shift without a short name (no pattern can name it) nor for EXT (nobody in a group)
        // Technik Nacht inherits from Technik: nothing of its own
        assertThat(brief(p, "groups")).containsOnly(Map.entry("Team Annahme", "TA"),
                Map.entry("Team Disposition", "TD"), Map.entry("Springer Annahme", "TA"),
                Map.entry("Schicht A", "ignored"), Map.entry("Gesamt", "ignored"), Map.entry("Leer", "ignored"),
                Map.entry("Ruhend", "ignored"), Map.entry("Technik", "TE"), Map.entry("Leitstelle", "ignored"),
                Map.entry("Nord", "NO"), Map.entry("Süd", "SU"));
        assertThat(brief(p, "compensatoryRest")).containsOnlyKeys("ER");
        Map<String, String> why = reasons(p);
        assertThat(why.get("shift:A1")).contains("Bedarf in Schichtplaner 5 bei den Gruppen Team Annahme, Schicht A")
                .doesNotContain("Sammelgruppe")
                .contains("Gruppen Team Annahme, Springer Annahme (Bereich TA) haben 100 % der 10 Dienste");
        assertThat(why.get("shift:R1")).isEqualTo("Bedarf in Schichtplaner 5 nur bei der Gruppe Team Disposition."
                + " Mitglieder der Gruppe Team Disposition (Bereich TD) haben 100 % der 6 Dienste.");
        // Gesamt has all its duties but is an umbrella group: named, not counted
        assertThat(why.get("shift:M1")).contains("Kein Bedarf in Schichtplaner 5. Sammelgruppe, nicht gezählt: Gesamt.").contains("Kein Bereich erreicht 80 % der 4 Dienste");
        assertThat(why.get("shift:H1")).isEqualTo("In Schichtplaner 5 ausgeblendet.");
        assertThat(why.get("shift:Z1")).isEqualTo("Nur 2 Dienste im Zeitraum (weniger als 3) und kein Bedarf.");
        assertThat(why.get("shift:Z0")).isEqualTo("Keine Dienste im Zeitraum und kein Bedarf.");
        assertThat(why.get("group:Springer Annahme")).isEqualTo(
                "2 von 2 Mitgliedern mit Diensten (100 %) haben Dienste im Bereich TA.");
        assertThat(why.get("group:Gesamt")).startsWith("Sammelgruppe: die 14 Mitglieder mit Diensten verteilen sich");
        assertThat(why.get("group:Leer")).isEqualTo("Die Gruppe hat keine Mitglieder.");
        assertThat(why.get("group:Ruhend")).isEqualTo("Die 1 Mitglieder haben keine Dienste im Zeitraum.");
        assertThat(why.get("group:Leitstelle")).isEqualTo(
                "Obergruppe von Gruppen mit verschiedenen Bereichen: Nord (NO), Süd (SU).");
        assertThat(why.get("rest:ER")).contains("„Ersatzruhe“");
        assertThat(p.get("discarded")).isEqualTo(0);
        // the new area travels with the rules that need it
        Map<String, Object> a1 = ((List<Map<String, Object>>) p.get("shifts")).stream()
                .filter(x -> x.get("code").equals("A1")).findFirst().orElseThrow();
        assertThat(a1.get("newAreas")).isEqualTo(List.of(Map.of("id", "TA", "name", "Team Annahme", "color", "#B45309")));
    }

    @Test
    void cyclesCountUpToTheNewestDutyOrTodayAndAnAreaTakenAloneIsUsedAgain(@TempDir Path dir) throws Exception {
        ProposalFolder.write(dir, "2027-01");
        // S2: three duties and the cycle from 20 January; all months end at the newest duty or today
        LocalDate end = LocalDate.now().isAfter(LocalDate.of(2027, 1, 28)) ? LocalDate.now() : LocalDate.of(2027, 1, 28);
        long cycle = ChronoUnit.DAYS.between(LocalDate.of(2027, 1, 20), end) + 1;
        assertThat(reasons(propose(dir, Map.of(), DEFAULTS)).get("shift:S2")).endsWith("der " + (3 + cycle) + " Dienste.");
        // the last month only: up to its end
        assertThat(reasons(propose(dir, Map.of(), settings(80, 3, 1, List.of("Ersatzruhe"), Set.of()))).get("shift:S2"))
                .endsWith("der 15 Dienste.");
        // the area of a proposal taken alone: the next run uses it and proposes no second one
        Object mapping = PyJson.load("{\"areas\": [{\"id\": \"TA\", \"name\": \"Team Annahme\", \"color\": \"#B45309\"}],"
                + " \"shifts\": [], \"groups\": []}");
        Map<String, Object> again = propose(dir, mapping, DEFAULTS);
        assertThat(brief(again, "areas")).doesNotContainKey("Team Annahme");
        assertThat(brief(again, "shifts")).containsEntry("A1", "TA");
        assertThat(brief(again, "groups")).containsEntry("Team Annahme", "TA");
    }

    @Test
    void oddFoldersGiveNoCrashAndNoInvalidEntry(@TempDir Path dir) throws Exception {
        ProposalFolder.write(dir, "2027-01");
        // without memberships nobody is in a group: shifts without demand get no owner, no crash
        Files.delete(dir.resolve("5GRASG.DBF"));
        Map<String, Object> p = propose(dir, Map.of(), DEFAULTS);
        assertThat(brief(p, "shifts")).doesNotContainKeys("M1", "EXT");
        Object python = PyJson.load(new ObjectMapper().writeValueAsBytes(Sp5Proposals.taken(p)));
        // without any area the mapping is not complete yet, but nothing taken points to a missing area
        assertThat(Sp5Export.mappingErrors(python)).containsExactly("'areas' ist leer");
    }

    @Test
    void takingEveryProposalGivesAValidMappingThatMeetsEachCode(@TempDir Path dir) throws Exception {
        ProposalFolder.write(dir, "2027-01");
        Map<String, Object> mapping = Sp5Proposals.taken(propose(dir, Map.of(), DEFAULTS));
        Object python = PyJson.load(new ObjectMapper().writeValueAsBytes(mapping));
        assertThat(Sp5Export.mappingErrors(python)).isEmpty();
        Map<String, Object> codes = Sp5Codes.codes(dir, python, new ArrayList<>());
        for (String section : List.of("shifts", "groups")) {
            for (Map<String, Object> c : (List<Map<String, Object>>) codes.get(section)) {
                if ("".equals(c.get("code")) || "EXT".equals(c.get("code"))) {
                    continue; // no pattern names an empty code; nobody in a group worked EXT
                }
                assertThat(((Map<?, ?>) c.get("result")).get("kind")).as(section + " " + c.get("name"))
                        .isNotEqualTo("none");
            }
        }
        // nothing left to propose, and the export runs with it
        Map<String, Object> again = propose(dir, python, DEFAULTS);
        for (String section : List.of("areas", "shifts", "groups", "compensatoryRest")) {
            assertThat((List<?>) again.get(section)).as(section).isEmpty();
        }
        Sp5Export.Result r = Sp5Export.run(new Sp5Export.Request(dir, "2027-01", null, 1, null, null, "history", 1, 3,
                3, (Map<String, Object>) python, null, false));
        assertThat((List<?>) r.dataset().get("areas")).hasSize(5);
    }

    static Map<String, String> reasons(Map<String, Object> p) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String section : List.of("areas", "shifts", "groups", "compensatoryRest")) {
            for (Map<String, Object> x : (List<Map<String, Object>>) p.get(section)) {
                out.put((String) x.get("key"), (String) x.get("reason"));
            }
        }
        return out;
    }

    @Test
    void anExistingMappingKeepsItsEntriesAndOnlyTheRestIsProposed(@TempDir Path dir) throws Exception {
        ProposalFolder.write(dir, "2027-01");
        Object mapping = PyJson.load("""
                {"areas": [{"id": "AN", "name": "Annahme", "color": "#B45309"}],
                 "shifts": [{"match": "A*", "areaId": "AN"}],
                 "groups": [{"match": "Team Annahme", "areaIds": ["AN"]}], "ignoreGroups": ["Gesamt"]}""");
        Map<String, Object> p = propose(dir, mapping, DEFAULTS);
        // no new area for Team Annahme, no rule for A1, A2 or the team; its stand-ins and M1 use the existing area
        assertThat(brief(p, "areas")).doesNotContainKey("Team Annahme").containsKey("Team Disposition");
        assertThat(brief(p, "shifts")).doesNotContainKeys("A1", "A2").containsEntry("M1", "choice [AN 50, TD 50]");
        assertThat(brief(p, "groups")).doesNotContainKeys("Team Annahme", "Gesamt")
                .containsEntry("Springer Annahme", "AN");
        assertThat(brief(p, "areas").values()).noneMatch(v -> v.startsWith("AN "));
    }

    @Test
    void aDiscardedProposalStaysAwayAndComesBackAfterTheReset(@TempDir Path dir) throws Exception {
        ProposalFolder.write(dir, "2027-01");
        Map<String, Object> p = propose(dir, Map.of(), settings(80, 3, 0, List.of("Ersatzruhe"),
                Set.of("shift:Z1", "group:Gesamt", "rest:ER")));
        assertThat(brief(p, "shifts")).doesNotContainKey("Z1");
        assertThat(brief(p, "groups")).doesNotContainKey("Gesamt");
        assertThat(brief(p, "compensatoryRest")).isEmpty();
        assertThat(p.get("discarded")).isEqualTo(3);
        Map<String, Object> reset = propose(dir, Map.of(), DEFAULTS);
        assertThat(brief(reset, "shifts")).containsKey("Z1");
        assertThat(brief(reset, "groups")).containsKey("Gesamt");
    }

    @Test
    void everySettingChangesTheResult(@TempDir Path dir) throws Exception {
        ProposalFolder.write(dir, "2026-11");
        // share 50 %: M1 is a rule of the stronger (equal: the first) area
        assertThat(brief(propose(dir, Map.of(), settings(50, 3, 0, List.of("Ersatzruhe"), Set.of())), "shifts"))
                .containsEntry("M1", "TA");
        // two duties are enough: Z1 goes to the team that worked it
        assertThat(brief(propose(dir, Map.of(), settings(80, 2, 0, List.of("Ersatzruhe"), Set.of())), "shifts"))
                .containsEntry("Z1", "TA");
        // the last month only: Z1 (November) has no duties in it
        Map<String, Object> recent = propose(dir, Map.of(), settings(80, 2, 1, List.of("Ersatzruhe"), Set.of()));
        assertThat(brief(recent, "shifts")).containsEntry("Z1", "ignored");
        assertThat(reasons(recent).get("shift:Z1")).isEqualTo("Keine Dienste im Zeitraum und kein Bedarf.");
        // other words: one match is a proposal, two or none are not
        assertThat(brief(propose(dir, Map.of(), settings(80, 3, 0, List.of("ruhe"), Set.of())), "compensatoryRest"))
                .containsOnlyKeys("ER");
        assertThat(brief(propose(dir, Map.of(), settings(80, 3, 0, List.of("Krank", "Ersatz"), Set.of())),
                "compensatoryRest")).isEmpty();
        assertThat(brief(propose(dir, Map.of(), settings(80, 3, 0, List.of("Kur"), Set.of())), "compensatoryRest"))
                .isEmpty();
    }

    @Test
    void patternsMatchExactlyTheCodeAndAreaIdsAreUnique() {
        assertThat(Sp5Proposals.exact("X*")).isEqualTo("X[*]");
        assertThat(Sp5Proposals.exact("a?[b")).isEqualTo("a[?][[]b");
        assertThat(Sp5Proposals.exact("re:x")).isEqualTo("[r]e:x");
        for (String code : List.of("X*", "a?[b", "re:x", "N-1")) {
            assertThat(Sp5Export.matches(Sp5Proposals.exact(code), code)).as(code).isTrue();
        }
        assertThat(Sp5Export.matches(Sp5Proposals.exact("X*"), "X1")).isFalse();
        Set<String> used = new HashSet<>(Set.of("TA"));
        assertThat(Sp5Proposals.areaId("Team Annahme", used)).isEqualTo("TA2");
        assertThat(Sp5Proposals.areaId("Lageführung", used)).isEqualTo("LA");
        assertThat(Sp5Proposals.areaId("Straße Süd Ost West", used)).isEqualTo("SSO");
        assertThat(Sp5Proposals.areaId("--", used)).isEqualTo("B");
    }
}
