package app.dispofold.plugins.sp5;

import java.nio.file.Path;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import app.dispofold.plugins.sp5.port.Py;
import app.dispofold.plugins.sp5.port.Sp5Export;

/**
 * Proposals of mapping entries from the folder, for the shifts, groups and absence types that no rule of the mapping
 * meets yet ({@link Sp5Codes} "none"); existing entries are never changed. Each proposal has a key (to discard it),
 * a reason in German with the numbers behind it, and the entry it would add. Nothing here is fixed for a site: the
 * share, the rare-shift count, the history window and the words are settings.
 * <ol>
 * <li>Duties: 5MASHI (not TYPE 1), 5SPSHI with a shift and the cycles, in the window (all, or the last months up to
 * the newest duty). A group's members include those of its subgroups (SUPERID), as the import gives them its
 * rule.</li>
 * <li>Demand (5SHDEM, 5SPDEM) links a shift to groups directly and comes first: a shift with demand in one group
 * belongs to that group. With demand in several groups, or none, the group whose members worked the largest share
 * of its duties, leaving out umbrella groups (below); ties go to the smaller group.</li>
 * <li>A group that owns a shift and has no rule becomes a new area. A shift goes to its owner's area when the members
 * of that area's groups worked at least the share of its duties (or it has demand and no duties); below, the two
 * strongest areas are a choice for the planner.</li>
 * <li>Hidden shifts are ignored; so are shifts without demand that have fewer duties than the minimum (none
 * included). A shift with demand is in use even with few duties.</li>
 * <li>A group that owns no shift goes to the area in which at least the share of its members with duties worked;
 * if they spread over two or more areas below the share, or its subgroups have different areas, it is an umbrella
 * group and ignored; without members or duties it is ignored. A subgroup under a group with an area inherits it
 * and gets nothing.</li>
 * <li>Compensatory rest: the one absence type whose name or short name contains one of the words.</li>
 * </ol>
 */
final class Sp5Proposals {

    /** Colours of new areas, in turn after those the mapping uses. */
    static final List<String> COLORS = List.of("#B45309", "#15803D", "#2563EB", "#7C3AED", "#0E7490", "#BE123C",
            "#4D7C0F", "#A16207", "#1D4ED8", "#9333EA", "#0F766E", "#C2410C");

    record Settings(int share, int minDuties, int months, List<String> restWords, Set<String> discarded) {
    }

    private record Group(Object id, String name, Object parent, Map<String, Object> result) {
    }

    private Sp5Proposals() {
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> propose(Path folder, Object mapping, Settings s) {
        Map<?, ?> m = mapping instanceof Map<?, ?> map ? map : Map.of();
        Map<String, Object> codes = Sp5Codes.codes(folder, m, new ArrayList<>());
        double share = s.share() / 100.0;

        // ---- groups and members ----
        List<Map<String, Object>> groupRows = Sp5Codes.table(folder, "5GROUP");
        List<Object> groupCodes = (List<Object>) codes.get("groups");
        Map<Object, Group> groups = new LinkedHashMap<>();
        for (int i = 0; i < groupRows.size(); i++) {
            Map<String, Object> r = groupRows.get(i);
            Object id = Py.key(r.get("ID"));
            groups.put(id, new Group(id, Sp5Codes.text(r.get("NAME")),
                    Py.truthy(r.get("SUPERID")) ? Py.key(r.get("SUPERID")) : null,
                    (Map<String, Object>) ((Map<String, Object>) groupCodes.get(i)).get("result")));
        }
        Map<Object, Set<Object>> direct = new HashMap<>();
        for (Map<String, Object> a : Sp5Codes.table(folder, "5GRASG")) {
            direct.computeIfAbsent(Py.key(a.get("GROUPID")), k -> new HashSet<>()).add(Py.key(a.get("EMPLOYEEID")));
        }
        Map<Object, List<Object>> children = new HashMap<>();
        for (Group g : groups.values()) {
            if (g.parent() != null && groups.containsKey(g.parent()) && !g.parent().equals(g.id())) {
                children.computeIfAbsent(g.parent(), k -> new ArrayList<>()).add(g.id());
            }
        }
        Map<Object, Set<Object>> members = new HashMap<>();
        for (Object id : groups.keySet()) {
            Set<Object> all = new HashSet<>();
            Set<Object> seen = new HashSet<>();
            List<Object> todo = new ArrayList<>(List.of(id));
            while (!todo.isEmpty()) {
                Object g = todo.remove(todo.size() - 1);
                if (seen.add(g)) {
                    all.addAll(direct.getOrDefault(g, Set.of()));
                    todo.addAll(children.getOrDefault(g, List.of()));
                }
            }
            members.put(id, all);
        }

        // ---- duties in the window ----
        List<Object[]> duties = duties(folder, s.months()); // {employee, shift}
        Map<Object, List<Object>> byShift = new HashMap<>();
        Map<Object, Set<Object>> shiftsOf = new HashMap<>();
        for (Object[] d : duties) {
            byShift.computeIfAbsent(d[1], k -> new ArrayList<>()).add(d[0]);
            shiftsOf.computeIfAbsent(d[0], k -> new HashSet<>()).add(d[1]);
        }
        Map<Object, Set<Object>> working = new HashMap<>();
        Set<Object> allWorking = new HashSet<>();
        for (Object id : groups.keySet()) {
            Set<Object> w = new HashSet<>();
            for (Object e : members.get(id)) {
                if (shiftsOf.containsKey(e)) {
                    w.add(e);
                }
            }
            working.put(id, w);
            allWorking.addAll(w);
        }
        // groups the mapping leaves out or ignores take no part; the others with working members are candidates
        List<Object> candidates = new ArrayList<>();
        for (Group g : groups.values()) {
            if (!blocked(g.result()) && !working.get(g.id()).isEmpty()) {
                candidates.add(g.id());
            }
        }
        Map<Object, Set<Object>> demand = new HashMap<>();
        for (String table : List.of("5SHDEM", "5SPDEM")) {
            for (Map<String, Object> r : Sp5Codes.table(folder, table)) {
                Object group = Py.key(r.get("GROUPID"));
                if ((Py.truthy(r.get("MIN")) || Py.truthy(r.get("MAX"))) && candidates.contains(group)) {
                    demand.computeIfAbsent(Py.key(r.get("SHIFTID")), k -> new LinkedHashSet<>()).add(group);
                }
            }
        }

        // ---- shifts: areas of existing rules, owners of the others ----
        List<Map<String, Object>> shifts = (List<Map<String, Object>>) codes.get("shifts");
        Map<Object, Map<String, Object>> shiftById = new LinkedHashMap<>();
        Map<Object, String> shiftArea = new HashMap<>(); // shift -> area id (existing rules, then proposals)
        List<Map<String, Object>> shiftRows = Sp5Codes.table(folder, "5SHIFT");
        for (int i = 0; i < shifts.size(); i++) {
            Map<String, Object> sh = shifts.get(i);
            Object id = Py.key(shiftRows.get(i).get("ID"));
            shiftById.put(id, sh);
            Map<String, Object> r = (Map<String, Object>) sh.get("result");
            if ("rule".equals(r.get("kind")) && r.get("areaId") instanceof String a) {
                shiftArea.put(id, a);
            }
        }
        Map<Object, Map<String, Object>> shiftProposal = new LinkedHashMap<>();
        Map<Object, Object> owner = new LinkedHashMap<>();
        Map<Object, String> ownerWhy = new HashMap<>();
        List<Object> open = new ArrayList<>();
        for (Map.Entry<Object, Map<String, Object>> e : shiftById.entrySet()) {
            Map<String, Object> sh = e.getValue();
            if (!"none".equals(((Map<?, ?>) sh.get("result")).get("kind"))) {
                continue;
            }
            Object id = e.getKey();
            int n = byShift.getOrDefault(id, List.of()).size();
            Set<Object> d = demand.getOrDefault(id, Set.of());
            if (Boolean.TRUE.equals(sh.get("hidden"))) {
                shiftProposal.put(id, ignoredShift(sh, "In Schichtplaner 5 ausgeblendet."));
            } else if (d.isEmpty() && n < Math.max(1, s.minDuties())) {
                shiftProposal.put(id, ignoredShift(sh, n == 0 ? "Keine Dienste im Zeitraum und kein Bedarf."
                        : "Nur " + n + (n == 1 ? " Dienst" : " Dienste") + " im Zeitraum (weniger als "
                                + s.minDuties() + ") und kein Bedarf."));
            } else if (d.size() == 1) {
                Object g = d.iterator().next();
                owner.put(id, g);
                ownerWhy.put(id, "Bedarf in Schichtplaner 5 nur bei der Gruppe " + name(groups, g) + ".");
            } else {
                open.add(id);
            }
        }
        // provisional areas of the owners so far: for the umbrella test of the remaining shifts
        Map<Object, String> provisional = new HashMap<>(shiftArea);
        owner.forEach((sh, g) -> provisional.put(sh, "#" + g));
        Set<Object> byDemand = new HashSet<>(owner.values()); // owners by demand alone are area groups already
        for (Object id : open) {
            Set<Object> d = demand.getOrDefault(id, Set.of());
            Collection<Object> pool = d.isEmpty() ? candidates : d;
            List<Object> umbrellas = new ArrayList<>();
            List<Object> usable = new ArrayList<>();
            for (Object g : pool) {
                Set<Object> family = new HashSet<>();
                demand.forEach((sh, gs) -> {
                    if (gs.contains(g)) {
                        family.add(sh);
                    }
                });
                Map<Object, String> areas = new HashMap<>(provisional);
                family.forEach(sh -> areas.put(sh, "#" + g));
                Map<String, Integer> cov = coverage(working.get(g), shiftsOf, areas);
                if (!byDemand.contains(g) && umbrella(cov, working.get(g).size(), share)) {
                    umbrellas.add(g);
                } else {
                    usable.add(g);
                }
            }
            List<Object> workers = byShift.getOrDefault(id, List.of());
            Comparator<Object> bySize = Comparator.comparingInt(g -> members.get(g).size());
            Object best;
            if (!usable.isEmpty()) {
                best = usable.stream().sorted(Comparator.<Object>comparingDouble(g -> -part(workers, working.get(g)))
                        .thenComparing(bySize).thenComparing(g -> name(groups, g))).findFirst().orElseThrow();
            } else {
                double all = allWorking.size();
                best = pool.stream().sorted(Comparator.<Object>comparingDouble(
                        g -> -part(workers, working.get(g)) / (working.get(g).size() / all))
                        .thenComparing(bySize).thenComparing(g -> name(groups, g))).findFirst().orElseThrow();
            }
            owner.put(id, best);
            double won = part(workers, working.get(best));
            umbrellas.removeIf(g -> part(workers, working.get(g)) < won); // only those that would have won
            String names = d.isEmpty() ? "Kein Bedarf in Schichtplaner 5."
                    : "Bedarf in Schichtplaner 5 bei den Gruppen " + names(groups, d) + ".";
            if (!umbrellas.isEmpty()) {
                names += " " + (umbrellas.size() == 1 ? "Sammelgruppe" : "Sammelgruppen") + ", nicht gezählt: "
                        + names(groups, umbrellas) + ".";
            }
            ownerWhy.put(id, names);
        }

        // ---- areas: those of existing group rules, new ones for owners without a rule ----
        Set<String> usedIds = new HashSet<>();
        List<String> usedColors = new ArrayList<>();
        for (Object a : m.get("areas") instanceof List<?> l ? l : List.of()) {
            if (a instanceof Map<?, ?> area) {
                if (area.get("id") instanceof String id) {
                    usedIds.add(id);
                }
                if (area.get("color") instanceof String c) {
                    usedColors.add(c.toUpperCase(Locale.ROOT));
                }
            }
        }
        Map<Object, List<String>> groupAreas = new LinkedHashMap<>(); // group -> its area ids (existing or proposed)
        for (Group g : groups.values()) {
            List<String> existing = existingAreas(g.result());
            if (existing != null) {
                groupAreas.put(g.id(), existing);
            }
        }
        List<Map<String, Object>> areaProposals = new ArrayList<>();
        Map<Object, String> newArea = new LinkedHashMap<>();
        for (Object g : new LinkedHashSet<>(owner.values())) {
            if (groupAreas.containsKey(g) || inheritsArea(g, groups, groupAreas)) {
                continue;
            }
            String id = areaId(name(groups, g), usedIds);
            usedIds.add(id);
            newArea.put(g, id);
            groupAreas.put(g, List.of(id));
            String color = COLORS.stream().filter(c -> !usedColors.contains(c)).findFirst()
                    .orElse(COLORS.get(areaProposals.size() % COLORS.size()));
            usedColors.add(color);
            List<String> own = owner.entrySet().stream().filter(e -> e.getValue().equals(g))
                    .map(e -> (String) shiftById.get(e.getKey()).get("code")).toList();
            Map<String, Object> area = new LinkedHashMap<>();
            area.put("key", "area:" + name(groups, g));
            area.put("id", id);
            area.put("name", name(groups, g));
            area.put("color", color);
            area.put("group", name(groups, g));
            area.put("reason", "Gruppe " + name(groups, g) + " mit " + working.get(g).size() + " von "
                    + members.get(g).size() + " Mitgliedern mit Diensten; eigene " + (own.size() == 1 ? "Schicht " : "Schichten ")
                    + String.join(", ", own) + ".");
            areaProposals.add(area);
        }
        owner.forEach((sh, g) -> {
            List<String> a = areasOf(g, groups, groupAreas);
            if (a != null && a.size() == 1) {
                shiftArea.put(sh, a.get(0));
            }
        });

        // ---- groups that own no shift: by the areas their members worked ----
        Map<Object, Map<String, Object>> groupProposal = new LinkedHashMap<>();
        Set<Object> owners = new HashSet<>(owner.values());
        for (Group g : groups.values()) {
            if (!"none".equals(g.result().get("kind")) || owners.contains(g.id())
                    || inheritsArea(g.id(), groups, groupAreas)) {
                continue;
            }
            Set<Object> w = working.get(g.id());
            if (members.get(g.id()).isEmpty()) {
                groupProposal.put(g.id(), ignoredGroup(g, "Die Gruppe hat keine Mitglieder."));
                continue;
            }
            if (w.isEmpty()) {
                groupProposal.put(g.id(), ignoredGroup(g, "Die " + members.get(g.id()).size()
                        + " Mitglieder haben keine Dienste im Zeitraum."));
                continue;
            }
            Map<String, List<String>> childAreas = new TreeMap<>();
            for (Object c : children.getOrDefault(g.id(), List.of())) {
                List<String> a = groupAreas.get(c);
                if (a != null) {
                    childAreas.computeIfAbsent(String.join(", ", a), k -> new ArrayList<>()).add(name(groups, c));
                }
            }
            if (childAreas.size() >= 2) {
                groupProposal.put(g.id(), ignoredGroup(g, "Obergruppe von Gruppen mit verschiedenen Bereichen: "
                        + childAreas.entrySet().stream().map(e -> String.join(", ", e.getValue()) + " (" + e.getKey()
                                + ")").collect(Collectors.joining(", ")) + "."));
                continue;
            }
            Map<String, Integer> cov = coverage(w, shiftsOf, shiftArea);
            List<Map.Entry<String, Integer>> ranked = ranked(cov);
            if (ranked.isEmpty()) {
                continue; // its members work only shifts without an area: nothing to say
            }
            Map.Entry<String, Integer> top = ranked.get(0);
            if (top.getValue() >= share * w.size() || ranked.size() == 1) {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("key", "group:" + g.name());
                p.put("name", g.name());
                p.put("kind", "rule");
                p.put("areaIds", List.of(top.getKey()));
                p.put("reason", top.getValue() + " von " + w.size() + " Mitgliedern mit Diensten ("
                        + percent(top.getValue(), w.size()) + ") haben Dienste im Bereich " + top.getKey() + ".");
                groupProposal.put(g.id(), p);
                groupAreas.put(g.id(), List.of(top.getKey()));
            } else {
                groupProposal.put(g.id(), ignoredGroup(g, "Sammelgruppe: die " + w.size()
                        + " Mitglieder mit Diensten verteilen sich auf die Bereiche " + ranked.stream()
                                .map(e -> e.getKey() + " (" + percent(e.getValue(), w.size()) + ")")
                                .collect(Collectors.joining(", ")) + "."));
            }
        }
        for (Object g : owners) {
            Group group = groups.get(g);
            if ("none".equals(group.result().get("kind")) && newArea.containsKey(g)) {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("key", "group:" + group.name());
                p.put("name", group.name());
                p.put("kind", "rule");
                p.put("areaIds", List.of(newArea.get(g)));
                p.put("reason", "Die Gruppe hat eigene Schichten: neuer Bereich " + newArea.get(g) + ".");
                groupProposal.put(g, p);
            }
        }

        // ---- shift rules: the owner's area by the share its members worked ----
        Map<String, Set<Object>> areaWorkers = new HashMap<>();
        Map<String, List<String>> areaGroups = new TreeMap<>();
        groupAreas.forEach((g, as) -> {
            for (String a : as) {
                areaWorkers.computeIfAbsent(a, k -> new HashSet<>()).addAll(working.getOrDefault(g, Set.of()));
                areaGroups.computeIfAbsent(a, k -> new ArrayList<>()).add(name(groups, g));
            }
        });
        for (Map.Entry<Object, Object> e : owner.entrySet()) {
            Object id = e.getKey();
            Map<String, Object> sh = shiftById.get(id);
            List<Object> workers = byShift.getOrDefault(id, List.of());
            List<String> own = areasOf(e.getValue(), groups, groupAreas);
            String why = ownerWhy.get(id);
            if (workers.isEmpty() && own != null && own.size() == 1) {
                shiftProposal.put(id, shiftRule(sh, own.get(0), why + " Keine Dienste im Zeitraum."));
                continue;
            }
            Map<String, Double> shares = new TreeMap<>();
            areaWorkers.forEach((a, ws) -> {
                double p = part(workers, ws);
                if (p > 0) {
                    shares.put(a, p);
                }
            });
            List<Map.Entry<String, Double>> top = shares.entrySet().stream()
                    .sorted(Map.Entry.<String, Double>comparingByValue().reversed()).toList();
            if (own != null && own.size() == 1 && shares.getOrDefault(own.get(0), 0.0) >= share) {
                String a = own.get(0);
                shiftProposal.put(id, shiftRule(sh, a, why + " Mitglieder der " + groupsWord(areaGroups.get(a))
                        + " " + String.join(", ", areaGroups.get(a)) + " (Bereich " + a + ") haben "
                        + percent(shares.get(a)) + " der " + workers.size() + " Dienste."));
            } else if (!top.isEmpty()) {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("key", "shift:" + sh.get("code"));
                p.put("code", sh.get("code"));
                p.put("name", sh.get("name"));
                p.put("kind", "choice");
                List<Map<String, Object>> choices = new ArrayList<>();
                for (Map.Entry<String, Double> c : top.subList(0, Math.min(2, top.size()))) {
                    choices.add(Map.of("areaId", c.getKey(), "share", Math.round(c.getValue() * 100)));
                }
                p.put("choices", choices);
                p.put("reason", why + " Kein Bereich erreicht " + s.share() + " % der " + workers.size()
                        + " Dienste: " + choices.stream().map(c -> c.get("areaId") + " " + c.get("share") + " %")
                                .collect(Collectors.joining(", ")) + ". Bitte wählen.");
                shiftProposal.put(id, p);
            }
        }

        // ---- compensatory rest ----
        List<Map<String, Object>> rest = new ArrayList<>();
        List<Map<String, Object>> types = (List<Map<String, Object>>) codes.get("absenceTypes");
        boolean hasRest = types.stream().anyMatch(t -> "compensatoryRest".equals(((Map<?, ?>) t.get("result")).get("kind")));
        if (!hasRest) {
            List<Map<String, Object>> found = new ArrayList<>();
            for (Map<String, Object> t : types) {
                if (!"none".equals(((Map<?, ?>) t.get("result")).get("kind"))) {
                    continue;
                }
                for (String word : s.restWords()) {
                    String w = word.toLowerCase(Locale.ROOT);
                    if (!w.isBlank() && (((String) t.get("name")).toLowerCase(Locale.ROOT).contains(w)
                            || ((String) t.get("code")).toLowerCase(Locale.ROOT).contains(w))) {
                        Map<String, Object> p = new LinkedHashMap<>();
                        p.put("key", "rest:" + t.get("code"));
                        p.put("code", t.get("code"));
                        p.put("name", t.get("name"));
                        p.put("reason", "Der Name „" + t.get("name") + "“ oder das Kürzel enthält „" + word + "“.");
                        found.add(p);
                        break;
                    }
                }
            }
            if (found.size() == 1) {
                rest.add(found.get(0));
            }
        }

        // ---- result without the discarded ones ----
        Map<String, Object> result = new LinkedHashMap<>();
        int[] discarded = { 0 };
        result.put("areas", kept(areaProposals, s.discarded(), discarded));
        result.put("shifts", kept(new ArrayList<>(shiftProposal.values()), s.discarded(), discarded));
        result.put("groups", kept(new ArrayList<>(groupProposal.values()), s.discarded(), discarded));
        result.put("compensatoryRest", kept(rest, s.discarded(), discarded));
        result.put("discarded", discarded[0]);
        result.put("duties", duties.size());
        return result;
    }

    /** The mapping an empty one becomes when every proposal is taken (a choice by its stronger area). */
    @SuppressWarnings("unchecked")
    static Map<String, Object> taken(Map<String, Object> p) {
        Map<String, Object> m = new LinkedHashMap<>();
        List<Object> areas = new ArrayList<>();
        List<Object> shifts = new ArrayList<>();
        List<Object> groups = new ArrayList<>();
        List<Object> ignoreShifts = new ArrayList<>();
        List<Object> ignoreGroups = new ArrayList<>();
        List<Object> rest = new ArrayList<>();
        for (Map<String, Object> a : (List<Map<String, Object>>) p.get("areas")) {
            areas.add(Map.of("id", a.get("id"), "name", a.get("name"), "color", a.get("color")));
        }
        for (Map<String, Object> s : (List<Map<String, Object>>) p.get("shifts")) {
            switch ((String) s.get("kind")) {
            case "rule" -> shifts.add(Map.of("match", s.get("match"), "areaId", s.get("areaId")));
            case "choice" -> shifts.add(Map.of("match", s.get("match"), "areaId",
                    ((List<Map<String, Object>>) s.get("choices")).get(0).get("areaId")));
            default -> ignoreShifts.add(s.get("match"));
            }
        }
        for (Map<String, Object> g : (List<Map<String, Object>>) p.get("groups")) {
            if ("rule".equals(g.get("kind"))) {
                groups.add(Map.of("match", g.get("match"), "areaIds", g.get("areaIds")));
            } else {
                ignoreGroups.add(g.get("match"));
            }
        }
        for (Map<String, Object> r : (List<Map<String, Object>>) p.get("compensatoryRest")) {
            rest.add(r.get("match"));
        }
        m.put("areas", areas);
        m.put("shifts", shifts);
        m.put("ignoreShifts", ignoreShifts);
        m.put("groups", groups);
        m.put("ignoreGroups", ignoreGroups);
        m.put("compensatoryRestAbsences", rest);
        return m;
    }

    // ---- duties ----

    /** {employee, shift} of every duty in the window: all of them, or the last months up to the newest duty. */
    private static List<Object[]> duties(Path folder, int months) {
        List<Object[]> rows = new ArrayList<>(); // {employee, shift, date}
        for (Map<String, Object> r : Sp5Codes.table(folder, "5MASHI")) {
            if (!Long.valueOf(1).equals(Py.key(r.get("TYPE")))) {
                rows.add(new Object[] { Py.key(r.get("EMPLOYEEID")), Py.key(r.get("SHIFTID")), Sp5Export.date(r.get("DATE")) });
            }
        }
        for (Map<String, Object> r : Sp5Codes.table(folder, "5SPSHI")) {
            if (Py.truthy(r.get("SHIFTID"))) {
                rows.add(new Object[] { Py.key(r.get("EMPLOYEEID")), Py.key(r.get("SHIFTID")), Sp5Export.date(r.get("DATE")) });
            }
        }
        LocalDate newest = rows.stream().map(r -> (LocalDate) r[2]).filter(d -> d != null)
                .max(Comparator.naturalOrder()).orElse(LocalDate.now());
        LocalDate from = months > 0 ? YearMonth.from(newest).minusMonths(months - 1L).atDay(1) : LocalDate.of(1900, 1, 1);
        LocalDate to = months > 0 ? YearMonth.from(newest).atEndOfMonth() : LocalDate.of(9999, 12, 31);
        try {
            for (Map<String, Object> r : Sp5Export.cycleDuties(Sp5Codes.table(folder, "5CYASS"),
                    Sp5Codes.table(folder, "5CYCLE"), Sp5Codes.table(folder, "5CYENT"),
                    Sp5Codes.table(folder, "5CYEXC"), from, to)) {
                rows.add(new Object[] { Py.key(r.get("EMPLOYEEID")), Py.key(r.get("SHIFTID")), Sp5Export.date(r.get("DATE")) });
            }
        } catch (RuntimeException e) {
            // cycle tables the import would reject: the proposals go without them
        }
        List<Object[]> result = new ArrayList<>();
        for (Object[] r : rows) {
            LocalDate d = (LocalDate) r[2];
            if (d != null && !d.isBefore(from) && !d.isAfter(to) && Py.truthy(r[1])) {
                result.add(new Object[] { r[0], r[1] });
            }
        }
        return result;
    }

    // ---- helpers ----

    /** Excluded or ignored by the mapping, also through the group above. */
    @SuppressWarnings("unchecked")
    private static boolean blocked(Map<String, Object> result) {
        Object kind = result.get("kind");
        if ("inherited".equals(kind)) {
            return blocked((Map<String, Object>) result.get("result"));
        }
        return "excluded".equals(kind) || "ignored".equals(kind);
    }

    /** The area ids of an existing group rule (also inherited), or null. */
    @SuppressWarnings("unchecked")
    private static List<String> existingAreas(Map<String, Object> result) {
        Object kind = result.get("kind");
        if ("inherited".equals(kind)) {
            return existingAreas((Map<String, Object>) result.get("result"));
        }
        if ("rule".equals(kind) && result.get("areaIds") instanceof List<?> l) {
            List<String> ids = l.stream().filter(String.class::isInstance).map(String.class::cast).toList();
            return ids.isEmpty() ? null : ids;
        }
        return null;
    }

    /** The areas of a group: its own, else those of the nearest group above it that has some. */
    private static List<String> areasOf(Object g, Map<Object, Group> groups, Map<Object, List<String>> groupAreas) {
        Set<Object> seen = new HashSet<>();
        Object cur = g;
        while (cur != null && seen.add(cur) && groups.containsKey(cur)) {
            if (groupAreas.containsKey(cur)) {
                return groupAreas.get(cur);
            }
            cur = groups.get(cur).parent();
        }
        return null;
    }

    private static boolean inheritsArea(Object g, Map<Object, Group> groups, Map<Object, List<String>> groupAreas) {
        Object parent = groups.get(g).parent();
        return parent != null && !parent.equals(g) && areasOf(parent, groups, groupAreas) != null;
    }

    /** Per area: how many of these people worked at least one duty in it. */
    private static Map<String, Integer> coverage(Set<Object> people, Map<Object, Set<Object>> shiftsOf,
            Map<Object, String> areas) {
        Map<String, Integer> cov = new HashMap<>();
        for (Object e : people) {
            Set<String> worked = new HashSet<>();
            for (Object sh : shiftsOf.getOrDefault(e, Set.of())) {
                if (areas.containsKey(sh)) {
                    worked.add(areas.get(sh));
                }
            }
            worked.forEach(a -> cov.merge(a, 1, Integer::sum));
        }
        return cov;
    }

    /** Two or more areas and none of them with the share of the people. */
    private static boolean umbrella(Map<String, Integer> cov, int people, double share) {
        return cov.size() >= 2 && cov.values().stream().noneMatch(c -> c >= share * people);
    }

    private static List<Map.Entry<String, Integer>> ranked(Map<String, Integer> cov) {
        return cov.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                .thenComparing(Map.Entry.comparingByKey())).toList();
    }

    /** The share of these duties (their people) worked by one of these people. */
    private static double part(List<Object> workers, Set<Object> people) {
        if (workers.isEmpty()) {
            return 0;
        }
        long n = workers.stream().filter(people::contains).count();
        return (double) n / workers.size();
    }

    private static String percent(int part, int whole) {
        return percent((double) part / whole);
    }

    private static String percent(double share) {
        return Math.round(share * 100) + " %";
    }

    private static String name(Map<Object, Group> groups, Object g) {
        return groups.get(g).name();
    }

    private static String names(Map<Object, Group> groups, Collection<Object> ids) {
        return ids.stream().map(g -> name(groups, g)).collect(Collectors.joining(", "));
    }

    private static String groupsWord(List<String> names) {
        return names.size() == 1 ? "Gruppe" : "Gruppen";
    }

    /**
     * An area id from a group name: the first letters of up to three words, or the first two of a single word, in
     * capitals without accents; a number makes it unique.
     */
    static String areaId(String name, Set<String> used) {
        String plain = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .replace("ß", "ss").toUpperCase(Locale.ROOT);
        List<String> words = new ArrayList<>();
        for (String w : plain.split("[^A-Z0-9]+")) {
            if (!w.isEmpty()) {
                words.add(w);
            }
        }
        String base;
        if (words.isEmpty()) {
            base = "B";
        } else if (words.size() == 1) {
            base = words.get(0).substring(0, Math.min(2, words.get(0).length()));
        } else {
            base = words.stream().limit(3).map(w -> w.substring(0, 1)).collect(Collectors.joining());
        }
        String id = base;
        for (int i = 2; used.contains(id); i++) {
            id = base + i;
        }
        return id;
    }

    /** A pattern that matches exactly this value (fnmatch with its special characters in brackets; not "re:"). */
    static String exact(String value) {
        StringBuilder b = new StringBuilder();
        for (char c : value.toCharArray()) {
            b.append(c == '*' || c == '?' || c == '[' ? "[" + c + "]" : String.valueOf(c));
        }
        String p = b.toString();
        return p.startsWith("re:") ? "[r]" + p.substring(1) : p;
    }

    private static Map<String, Object> shiftRule(Map<String, Object> sh, String area, String reason) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("key", "shift:" + sh.get("code"));
        p.put("code", sh.get("code"));
        p.put("name", sh.get("name"));
        p.put("match", exact((String) sh.get("code")));
        p.put("kind", "rule");
        p.put("areaId", area);
        p.put("reason", reason);
        return p;
    }

    private static Map<String, Object> ignoredShift(Map<String, Object> sh, String reason) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("key", "shift:" + sh.get("code"));
        p.put("code", sh.get("code"));
        p.put("name", sh.get("name"));
        p.put("match", exact((String) sh.get("code")));
        p.put("kind", "ignored");
        p.put("reason", reason);
        return p;
    }

    private static Map<String, Object> ignoredGroup(Group g, String reason) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("key", "group:" + g.name());
        p.put("name", g.name());
        p.put("kind", "ignored");
        p.put("reason", reason);
        return p;
    }

    private static List<Map<String, Object>> kept(List<Map<String, Object>> list, Set<String> discarded, int[] count) {
        List<Map<String, Object>> kept = new ArrayList<>();
        for (Map<String, Object> p : list) {
            if (discarded.contains((String) p.get("key"))) {
                count[0]++;
            } else {
                if (p.containsKey("name") && !p.containsKey("match") && !"area".equals(kind(p))) {
                    p.put("match", exact((String) p.get(p.containsKey("code") ? "code" : "name")));
                }
                kept.add(p);
            }
        }
        return kept;
    }

    private static String kind(Map<String, Object> p) {
        return ((String) p.get("key")).substring(0, ((String) p.get("key")).indexOf(':'));
    }
}
