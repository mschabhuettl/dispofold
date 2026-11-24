package app.dispofold.plugins.sp5;

import static app.dispofold.plugins.sp5.Sp5ProposalsTest.DEFAULTS;
import static app.dispofold.plugins.sp5.Sp5ProposalsTest.brief;
import static app.dispofold.plugins.sp5.Sp5ProposalsTest.reasons;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import app.dispofold.plugins.sp5.port.FamilyFolder;
import app.dispofold.plugins.sp5.port.ProposalFolder;
import app.dispofold.plugins.sp5.port.PyJson;
import app.dispofold.plugins.sp5.port.Sp5Export;

/** The proposals from shift name families on a folder whose groups do not separate the shifts ({@link FamilyFolder}). */
@SuppressWarnings("unchecked")
class Sp5FamiliesTest {

    static Sp5Proposals.Settings with(int familyShare, int familyMinDuties, int familyRatio) {
        return new Sp5Proposals.Settings(80, 3, 0, List.of("Ersatzruhe"), Set.of(), familyShare, familyMinDuties,
                familyRatio);
    }

    static Map<String, Object> propose(Path dir, Object mapping, Sp5Proposals.Settings s, String basis) {
        return Sp5Families.propose(dir, mapping, s, basis);
    }

    /** "area id: its name" of the proposed areas. */
    static Map<String, String> areas(Map<String, Object> p) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map<String, Object> a : (List<Map<String, Object>>) p.get("areas")) {
            out.put((String) a.get("id"), (String) a.get("name"));
        }
        return out;
    }

    static Path folder(Path dir) throws Exception {
        FamilyFolder.write(dir);
        return dir;
    }

    @Test
    void theLeadingCodeIsThePartBeforeASpaceOrATime() {
        assertThat(Sp5Families.prefix("NW 7-19")).isEqualTo("NW");
        assertThat(Sp5Families.prefix("NW7-19")).isEqualTo("NW");
        assertThat(Sp5Families.prefix("NW 07:00-19:00")).isEqualTo("NW");
        assertThat(Sp5Families.prefix("LK-F 6-14")).isEqualTo("LK-F");
        assertThat(Sp5Families.prefix("P/PU 18-6")).isEqualTo("P/PU");
        // without a space or a time the whole name: digits are part of it
        assertThat(Sp5Families.prefix("A1")).isEqualTo("A1");
        assertThat(Sp5Families.prefix("X1-T")).isEqualTo("X1-T");
        assertThat(Sp5Families.prefix("7-19")).isEqualTo("7-19");
    }

    @Test
    void coarseGroupsGiveTheAreasFromTheShiftNames(@TempDir Path dir) throws Exception {
        Map<String, Object> p = propose(folder(dir), Map.of(), DEFAULTS, null);
        assertThat(p).containsEntry("basis", "families").containsEntry("recommended", "families")
                .containsEntry("groupAreas", 3).containsEntry("stems", 8);
        assertThat((String) p.get("basisReason")).contains("Aus den Gruppen ergeben sich 3 Bereiche",
                "8 verschiedene Anfänge", "Hier passen die Schichtnamen");
        // the variants of LK and the three names of P are one family each; M-QZ and W-QZ have different staff; the
        // stand-ins who work NW and LK now and then do not join them
        assertThat(areas(p)).containsExactly(Map.entry("DS", "DS"), Map.entry("FA", "FA"),
                Map.entry("LK", "LK, LK-F, LK-S"), Map.entry("MQZ", "M-QZ"), Map.entry("NW", "NW"),
                Map.entry("P", "P, P/PU, PU"), Map.entry("WQZ", "W-QZ"));
        assertThat(brief(p, "shifts")).containsExactly(Map.entry("NW 0-24", "ignored"), Map.entry("Q 8-12", "ignored"),
                Map.entry("DS *", "DS"), Map.entry("FA *", "FA"), Map.entry("LK *", "LK"), Map.entry("LK-F *", "LK"),
                Map.entry("LK-S *", "LK"), Map.entry("M-QZ *", "MQZ"), Map.entry("NW *", "NW"), Map.entry("P *", "P"),
                Map.entry("P/PU *", "P"), Map.entry("PU *", "P"), Map.entry("W-QZ *", "WQZ"));
        // the umbrella groups stay ignored, the small team gets its family
        assertThat(brief(p, "groups")).containsExactly(Map.entry("Tagdienst", "ignored"),
                Map.entry("Nachtdienst", "ignored"), Map.entry("Team P", "P"));
        assertThat(brief(p, "compensatoryRest")).containsOnlyKeys("ER");
        Map<String, String> why = reasons(p);
        assertThat(why.get("area:families:LK, LK-F, LK-S")).contains("LK-F 6-14 (40 Dienste)", "13 Personen",
                "LK zu LK-F, LK-S: 83 % der 36 Dienste von LK leisten Personen, die auch LK-F, LK-S arbeiten");
        assertThat(why.get("area:families:P, P/PU, PU")).contains("P/PU zu P: 100 % der 12 Dienste");
        assertThat(why.get("shift:families:NW *")).contains("Deckt heute NW 7-19 (54 Dienste), NW 19-7 (40 Dienste)",
                "fängt auch NW 0-24", "ignorieren");
        assertThat(why.get("group:families:Tagdienst")).startsWith("Sammelgruppe").contains("keiner erreicht 80 %");
        assertThat(why.get("group:families:Team P")).isEqualTo("6 von 6 Mitgliedern mit Diensten (100 %) haben Dienste im Bereich P.");
        // each rule names the codes it covers today with their duties
        Map<String, Object> nw = ((List<Map<String, Object>>) p.get("shifts")).stream()
                .filter(x -> "NW *".equals(x.get("code"))).findFirst().orElseThrow();
        assertThat((List<Map<String, Object>>) nw.get("covers")).containsExactly(
                Map.of("code", "NW 7-19", "duties", 54), Map.of("code", "NW 19-7", "duties", 40));
    }

    @Test
    void takingEveryFamilyProposalPlacesEachShiftAndLeavesNothingToPropose(@TempDir Path dir) throws Exception {
        folder(dir);
        Map<String, Object> mapping = Sp5Proposals.taken(propose(dir, Map.of(), DEFAULTS, null));
        Object python = PyJson.load(new ObjectMapper().writeValueAsBytes(mapping));
        assertThat(Sp5Export.mappingErrors(python)).isEmpty();
        Map<String, String> placed = new LinkedHashMap<>();
        for (Map<String, Object> c : (List<Map<String, Object>>) Sp5Codes.codes(dir, python, new ArrayList<>())
                .get("shifts")) {
            Map<?, ?> r = (Map<?, ?>) c.get("result");
            placed.put((String) c.get("code"), "rule".equals(r.get("kind")) ? (String) r.get("areaId")
                    : (String) r.get("kind"));
        }
        assertThat(placed).containsExactly(Map.entry("NW 7-19", "NW"), Map.entry("NW 19-7", "NW"),
                Map.entry("NW 0-24", "ignored"), Map.entry("LK-F 6-14", "LK"), Map.entry("LK-S 14-22", "LK"),
                Map.entry("LK 7-19", "LK"), Map.entry("P 7-19", "P"), Map.entry("PU 19-7", "P"),
                Map.entry("P/PU 18-6", "P"), Map.entry("M-QZ 7-19", "MQZ"), Map.entry("W-QZ 7-19", "WQZ"),
                Map.entry("DS 7-19", "DS"), Map.entry("DS 19-7", "DS"), Map.entry("FA 7-19", "FA"),
                Map.entry("Q 8-12", "ignored"));
        Map<String, Object> again = propose(dir, python, DEFAULTS, "families");
        for (String section : List.of("areas", "shifts", "groups", "compensatoryRest")) {
            assertThat((List<?>) again.get(section)).as(section).isEmpty();
        }
        Sp5Export.Result r = Sp5Export.run(new Sp5Export.Request(dir, "2027-01", null, 1, null, null, "history", 1, 3,
                3, (Map<String, Object>) python, null, false));
        assertThat((List<?>) r.dataset().get("areas")).hasSize(7);
    }

    @Test
    void anExistingMappingKeepsItsEntriesAndOnlyUnmatchedCodesGetProposals(@TempDir Path dir) throws Exception {
        folder(dir);
        // NW 7-19 already in an own area, an area taken earlier by its name, DS fully mapped
        Object mapping = PyJson.load("""
                {"areas": [{"id": "N9", "name": "Nordwest", "color": "#B45309"},
                           {"id": "LKX", "name": "LK, LK-F, LK-S", "color": "#15803D"},
                           {"id": "DS", "name": "DS", "color": "#2563EB"}],
                 "shifts": [{"match": "NW 7-19", "areaId": "N9"}, {"match": "DS *", "areaId": "DS"}],
                 "groups": [], "ignoreShifts": [], "ignoreGroups": [], "compensatoryRestAbsences": ["ER"]}
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Map<String, Object> p = propose(dir, mapping, DEFAULTS, "families");
        // no new area for NW, LK or DS: the leading code NW is placed already, LK's area is reused by its name
        assertThat(areas(p)).containsOnlyKeys("FA", "MQZ", "P", "WQZ");
        Map<String, String> shifts = brief(p, "shifts");
        assertThat(shifts).containsEntry("NW *", "N9").containsEntry("LK *", "LKX").doesNotContainKeys("DS *",
                "NW 7-19", "DS 7-19");
        assertThat(reasons(p).get("shift:families:NW *")).contains("gehören schon zum Bereich N9");
        assertThat((List<?>) p.get("compensatoryRest")).isEmpty();
        // the existing entries are untouched: the proposals only add, and the first rule still wins for NW 7-19
        Map<String, Object> taken = new LinkedHashMap<>((Map<String, Object>) mapping);
        List<Object> rules = new ArrayList<>((List<Object>) taken.get("shifts"));
        for (Map<String, Object> x : (List<Map<String, Object>>) p.get("shifts")) {
            if ("rule".equals(x.get("kind"))) {
                rules.add(Map.of("match", x.get("match"), "areaId", x.get("areaId")));
            }
        }
        taken.put("shifts", rules);
        Map<String, Object> codes = Sp5Codes.codes(dir, taken, new ArrayList<>());
        Map<String, Object> first = ((List<Map<String, Object>>) codes.get("shifts")).get(0);
        assertThat(first.get("code")).isEqualTo("NW 7-19");
        assertThat(((Map<?, ?>) first.get("result")).get("index")).isEqualTo(0);
    }

    @Test
    void takingSomeRulesKeepsTheFamiliesTheirAreasAndTheirKeys(@TempDir Path dir) throws Exception {
        folder(dir);
        Map<String, Object> first = propose(dir, Map.of(), DEFAULTS, "families");
        // only the rules LK-F * and P * taken, with their new areas
        Map<String, Object> some = new LinkedHashMap<>();
        some.put("areas", List.of(Map.of("id", "LK", "name", "LK, LK-F, LK-S", "color", "#B45309"),
                Map.of("id", "P", "name", "P, P/PU, PU", "color", "#15803D")));
        some.put("shifts", List.of(Map.of("match", "LK-F *", "areaId", "LK"), Map.of("match", "P *", "areaId", "P")));
        Map<String, Object> again = propose(dir, some, DEFAULTS, "families");
        assertThat(areas(again)).doesNotContainKeys("LK", "LK2", "P", "PU");
        assertThat(brief(again, "shifts")).containsEntry("LK *", "LK").containsEntry("LK-S *", "LK")
                .containsEntry("P/PU *", "P").containsEntry("PU *", "P");
        assertThat(brief(again, "groups")).containsEntry("Team P", "P");
        // the keys of what is still proposed are those of the first answer
        Set<String> keys = reasons(first).keySet();
        assertThat(keys).containsAll(reasons(again).keySet());
    }

    @Test
    void oddShortNamesGiveNoCrash(@TempDir Path dir) throws Exception {
        List<Object[]> shifts = new ArrayList<>(List.of(FamilyFolder.SHIFTS));
        shifts.add(new Object[] { "-", 0L, 41, 45, 3 });
        shifts.add(new Object[] { "/", 0L, 41, 45, 3 });
        shifts.add(new Object[] { "--", 0L, 41, 45, 3 });
        shifts.add(new Object[] { "-1", 0L, 41, 45, 3 });
        shifts.add(new Object[] { "CX\t7-19", 0L, 36, 40, 4 });
        FamilyFolder.write(dir, shifts.toArray(new Object[0][]));
        for (String basis : new String[] { null, "groups", "families" }) {
            assertThat(propose(dir, Map.of(), DEFAULTS, basis)).as(String.valueOf(basis)).containsKey("basis");
        }
        // a tab after the code: the exact code, since "CX *" would not meet it; taking everything places it
        Map<String, Object> p = propose(dir, Map.of(), DEFAULTS, "families");
        assertThat(brief(p, "shifts")).containsKey("CX\t7-19").doesNotContainKey("CX *");
        Object python = PyJson.load(new ObjectMapper().writeValueAsBytes(Sp5Proposals.taken(p)));
        for (Map<String, Object> c : (List<Map<String, Object>>) Sp5Codes.codes(dir, python, new ArrayList<>())
                .get("shifts")) {
            assertThat(((Map<?, ?>) c.get("result")).get("kind")).as((String) c.get("code")).isNotEqualTo("none");
        }
    }

    static Sp5Proposals.Settings untimed(boolean on) {
        return new Sp5Proposals.Settings(80, 3, 0, List.of("Ersatzruhe"), Set.of(), 70, 2, 50, on);
    }

    @Test
    void shiftsWithoutATimeRangeAndWithoutDemandAreProposedToBeIgnored(@TempDir Path dir) throws Exception {
        List<Object[]> shifts = new ArrayList<>(List.of(FamilyFolder.SHIFTS));
        shifts.add(new Object[] { "AUSB", 0L, 41, 45, 8 }); // training: no time range, no demand, 40 duties
        shifts.add(new Object[] { "HO", 0L, 36, 40, 4 }); // home office
        shifts.add(new Object[] { "LD", 0L, 1, 4, 5 }); // a duty officer's code: no time range, but demand
        FamilyFolder.write(dir, shifts.toArray(new Object[0][]), new long[] { shifts.size() });
        for (String basis : new String[] { "groups", "families" }) {
            Map<String, String> on = brief(propose(dir, Map.of(), untimed(true), basis), "shifts");
            assertThat(on).as(basis).containsEntry("AUSB", "ignored").containsEntry("HO", "ignored");
            assertThat(on.get("LD")).as(basis).isNotEqualTo("ignored");
            // a timed shift without demand stays a desk duty
            assertThat(on.getOrDefault("NW 7-19", on.get("NW *"))).as(basis).isNotEqualTo("ignored");
        }
        Map<String, Object> on = propose(dir, Map.of(), untimed(true), "families");
        assertThat(reasons(on).get("shift:AUSB")).isEqualTo(
                "Kein Zeitraum im Kürzel, kein Bedarf in Schichtplaner 5, 40 Dienste: vermutlich kein Platz im Dienstplan, etwa Ausbildung.");
        assertThat(areas(on)).doesNotContainKeys("AUSB", "HO").containsKey("LD");
        // not counted for the recommendation: 8 beginnings and LD
        assertThat(on).containsEntry("stems", 9);
        // the setting off: as 0.24.0, the codes are families of their own
        Map<String, Object> off = propose(dir, Map.of(), untimed(false), "families");
        assertThat(areas(off).values()).contains("AUSB, FA", "DS, HO"); // with the families of the same staff
        assertThat(off).containsEntry("stems", 11);
    }

    @Test
    void eachThresholdChangesTheResult(@TempDir Path dir) throws Exception {
        folder(dir);
        // a higher share: LK (83 %) stays apart from its variants
        assertThat(areas(propose(dir, Map.of(), with(90, 2, 50), "families"))).containsEntry("LK", "LK")
                .containsEntry("LK2", "LK-F, LK-S");
        // a lower share: still no merge through the stand-ins (NW and LK have different staff both ways)
        assertThat(areas(propose(dir, Map.of(), with(30, 2, 50), "families"))).containsEntry("NW", "NW");
        // more duties before a person counts: nobody works P (5 each) with 6, the three names stay apart
        assertThat(areas(propose(dir, Map.of(), with(70, 6, 50), "families"))).containsEntry("P", "P")
                .containsEntry("PU", "PU").containsEntry("PPU", "P/PU");
        // a lower ratio: 3 areas from the groups are enough against 8 codes
        Map<String, Object> p = propose(dir, Map.of(), with(70, 2, 30), null);
        assertThat(p).containsEntry("basis", "groups").containsEntry("recommended", "groups");
        assertThat((String) p.get("basisReason")).contains("Hier passen die Gruppen", "mindestens 30 %");
    }

    @Test
    void foldersWhoseGroupsSeparateTheShiftsKeepTheProposalsFromTheGroups(@TempDir Path dir) throws Exception {
        ProposalFolder.write(dir, "2027-01");
        Map<String, Object> p = propose(dir, Map.of(), DEFAULTS, null);
        assertThat(p).containsEntry("basis", "groups").containsEntry("groupAreas", 5).containsEntry("stems", 7);
        // exactly what the groups propose, with the basis next to it
        Map<String, Object> groups = Sp5Proposals.propose(dir, Map.of(), DEFAULTS);
        Map<String, Object> same = new LinkedHashMap<>(p);
        for (String k : List.of("basis", "recommended", "groupAreas", "stems", "basisReason")) {
            same.remove(k);
        }
        assertThat((JsonNode) new ObjectMapper().valueToTree(same)).isEqualTo(new ObjectMapper().valueToTree(groups));
        // the other basis on request
        assertThat(propose(dir, Map.of(), DEFAULTS, "families")).containsEntry("basis", "families")
                .containsEntry("recommended", "groups");
    }

    @Test
    void settingsFromAnOlderVersionGetTheDefaultsAndWrongValuesAreRejected() throws Exception {
        ObjectMapper m = new ObjectMapper();
        JsonNode older = m.readTree("""
                {"root": "", "path": "", "mapping": null,
                 "proposals": {"share": 75, "minDuties": 3, "months": 0, "restWords": ["Ersatzruhe"], "discarded": []}}
                """);
        Sp5Proposals.Settings s = Sp5Settings.proposals(Sp5Settings.withDefaults(m, older));
        assertThat(s.share()).isEqualTo(75);
        assertThat(List.of(s.familyShare(), s.familyMinDuties(), s.familyRatio())).containsExactly(70, 2, 50);
        assertThat(Sp5Settings.checked(m, older).get("proposals").get("familyRatio").asInt()).isEqualTo(50);
        for (String bad : List.of("{\"familyShare\": 0}", "{\"familyRatio\": 101}", "{\"familyMinDuties\": 0}")) {
            JsonNode body = m.readTree("{\"proposals\": " + bad + "}");
            assertThatThrownBy(() -> Sp5Settings.checked(m, body)).as(bad).isNotNull();
        }
    }
}
