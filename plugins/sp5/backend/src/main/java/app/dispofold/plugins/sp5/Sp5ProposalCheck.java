package app.dispofold.plugins.sp5;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * How far the proposals for an empty mapping come to a planner's own mapping: every proposal taken (a choice by its
 * stronger area), then per code of the folder what each mapping decides, the proposed areas matched to the planner's
 * by the shifts they share. Per section the counts matched, different, missing (the own mapping has a rule, the
 * proposal none) and extra (the other way round); {@link #details} names the codes, for tests on synthetic data only.
 */
final class Sp5ProposalCheck {

    static final List<String> SECTIONS = List.of("areas", "shifts", "groups", "compensatoryRest");

    private Sp5ProposalCheck() {
    }

    /** {section: {matched, different, missing, extra}}. */
    static Map<String, Map<String, Integer>> counts(Path folder, Object own, Sp5Proposals.Settings settings) {
        Map<String, Map<String, Integer>> counts = new LinkedHashMap<>();
        for (String section : SECTIONS) {
            Map<String, Integer> c = new LinkedHashMap<>();
            for (String k : List.of("matched", "different", "missing", "extra")) {
                c.put(k, 0);
            }
            counts.put(section, c);
        }
        details(folder, own, settings).forEach(d -> counts.get(d.section()).merge(d.outcome(), 1, Integer::sum));
        return counts;
    }

    record Detail(String section, String code, String outcome, String own, String proposed) {
    }

    @SuppressWarnings("unchecked")
    static List<Detail> details(Path folder, Object own, Sp5Proposals.Settings settings) {
        Sp5Proposals.Settings all = new Sp5Proposals.Settings(settings.share(), settings.minDuties(), settings.months(),
                settings.restWords(), Set.of());
        Map<String, Object> proposed = Sp5Proposals.taken(Sp5Proposals.propose(folder, Map.of(), all));
        Map<String, Object> a = Sp5Codes.codes(folder, own, new ArrayList<>());
        Map<String, Object> b = Sp5Codes.codes(folder, proposed, new ArrayList<>());
        List<Map<String, Object>> shiftsA = (List<Map<String, Object>>) a.get("shifts");
        List<Map<String, Object>> shiftsB = (List<Map<String, Object>>) b.get("shifts");
        // proposed area -> own area: the one most of its shifts have in the own mapping, each own area once
        Map<String, Map<String, Integer>> votes = new HashMap<>();
        for (int i = 0; i < shiftsA.size(); i++) {
            String x = shiftArea(shiftsA.get(i));
            String y = shiftArea(shiftsB.get(i));
            if (x != null && y != null) {
                votes.computeIfAbsent(y, k -> new HashMap<>()).merge(x, 1, Integer::sum);
            }
        }
        List<String[]> pairs = new ArrayList<>(); // {count, proposed, own}
        votes.forEach((y, v) -> v.forEach((x, n) -> pairs.add(new String[] { String.format("%09d", n), y, x })));
        pairs.sort((p, q) -> q[0].compareTo(p[0]) != 0 ? q[0].compareTo(p[0])
                : (p[1] + p[2]).compareTo(q[1] + q[2]));
        Map<String, String> alias = new HashMap<>();
        Set<String> taken = new HashSet<>();
        for (String[] p : pairs) {
            if (!alias.containsKey(p[1]) && taken.add(p[2])) {
                alias.put(p[1], p[2]);
            }
        }
        List<Detail> out = new ArrayList<>();
        Set<String> ownAreas = new TreeSet<>(ids(own));
        for (String y : new TreeSet<>(ids(proposed))) {
            String x = alias.get(y);
            out.add(x == null ? new Detail("areas", y, "extra", null, y) : new Detail("areas", y, "matched", x, y));
            if (x != null) {
                ownAreas.remove(x);
            }
        }
        ownAreas.forEach(x -> out.add(new Detail("areas", x, "missing", x, null)));
        for (int i = 0; i < shiftsA.size(); i++) {
            out.add(detail("shifts", (String) shiftsA.get(i).get("code"), shift(shiftsA.get(i), Map.of()),
                    shift(shiftsB.get(i), alias)));
        }
        List<Map<String, Object>> groupsA = (List<Map<String, Object>>) a.get("groups");
        List<Map<String, Object>> groupsB = (List<Map<String, Object>>) b.get("groups");
        for (int i = 0; i < groupsA.size(); i++) {
            out.add(detail("groups", (String) groupsA.get(i).get("name"),
                    group((Map<String, Object>) groupsA.get(i).get("result"), Map.of()),
                    group((Map<String, Object>) groupsB.get(i).get("result"), alias)));
        }
        List<Map<String, Object>> typesA = (List<Map<String, Object>>) a.get("absenceTypes");
        List<Map<String, Object>> typesB = (List<Map<String, Object>>) b.get("absenceTypes");
        for (int i = 0; i < typesA.size(); i++) {
            out.add(detail("compensatoryRest", (String) typesA.get(i).get("code"), rest(typesA.get(i)),
                    rest(typesB.get(i))));
        }
        out.removeIf(d -> d.outcome() == null);
        return out;
    }

    private static Detail detail(String section, String code, String own, String proposed) {
        String outcome = own == null && proposed == null ? null : own == null ? "extra" : proposed == null ? "missing"
                : own.equals(proposed) ? "matched" : "different";
        return new Detail(section, code, outcome, own, proposed);
    }

    @SuppressWarnings("unchecked")
    private static String shiftArea(Map<String, Object> shift) {
        Map<String, Object> r = (Map<String, Object>) shift.get("result");
        return "rule".equals(r.get("kind")) && r.get("areaId") instanceof String s ? s : null;
    }

    @SuppressWarnings("unchecked")
    private static String shift(Map<String, Object> shift, Map<String, String> alias) {
        Map<String, Object> r = (Map<String, Object>) shift.get("result");
        return switch ((String) r.get("kind")) {
        case "ignored" -> "ignoriert";
        case "rule" -> "Bereich " + alias.getOrDefault((String) r.get("areaId"), (String) r.get("areaId"));
        default -> null;
        };
    }

    @SuppressWarnings("unchecked")
    private static String group(Map<String, Object> r, Map<String, String> alias) {
        return switch ((String) r.get("kind")) {
        case "inherited" -> group((Map<String, Object>) r.get("result"), alias);
        case "ignored" -> "ignoriert";
        case "excluded" -> "ausgeschlossen";
        case "rule" -> {
            Set<String> areas = new TreeSet<>();
            for (Object x : r.get("areaIds") instanceof List<?> l ? l : List.of()) {
                areas.add(alias.getOrDefault(String.valueOf(x), String.valueOf(x)));
            }
            yield "Bereiche " + String.join(", ", areas);
        }
        default -> null;
        };
    }

    @SuppressWarnings("unchecked")
    private static String rest(Map<String, Object> type) {
        return "compensatoryRest".equals(((Map<String, Object>) type.get("result")).get("kind")) ? "Ersatzruhe" : null;
    }

    private static Set<String> ids(Object mapping) {
        Set<String> ids = new HashSet<>();
        if (mapping instanceof Map<?, ?> m && m.get("areas") instanceof List<?> l) {
            for (Object a : l) {
                if (a instanceof Map<?, ?> area && area.get("id") instanceof String id) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }
}
