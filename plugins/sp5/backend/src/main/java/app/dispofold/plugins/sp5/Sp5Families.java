package app.dispofold.plugins.sp5;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import app.dispofold.plugins.sp5.Sp5Proposals.Group;
import app.dispofold.plugins.sp5.Sp5Proposals.Settings;
import app.dispofold.plugins.sp5.port.Py;
import app.dispofold.plugins.sp5.port.Sp5Export;

/**
 * Proposals with areas from shift name families, for a folder whose groups do not separate the shifts (a few umbrella
 * groups hold almost everyone, the areas are families of shift names). The same shape as {@link Sp5Proposals}, which
 * proposes the areas from the groups; {@link #propose(Path, Object, Settings, String)} picks one of the two.
 * <ol>
 * <li>Shifts: the same as from the groups: those the mapping meets keep their rule, hidden and rare ones are proposed
 * to be ignored; the others are the families' shifts.</li>
 * <li>The leading code of a short name is the part before the first space, or before a time at its end ("AX 7-19",
 * "AX7-19" -> AX); a name without either is its own code ("A1", "X1-T"). One family per leading code.</li>
 * <li>Families merge, the strongest pair first, while one reaches the share (familyShare): a person works a family
 * with at least familyMinDuties duties in it; related names (the same part before "-" or "/" without digits at its
 * end, as BR-F and BR or A1 and A2, or one a part of the other, as K/KU and KU) merge when people who also work the larger family worked the share of the
 * smaller one's duties; other names only when that holds both ways.</li>
 * <li>Each family is an area (an existing one when the mapping gives shifts of the same leading code one area), its
 * rule a pattern per leading code ("AX *"), or the exact codes when a pattern would also catch a shift of another
 * family.</li>
 * <li>Groups get the areas in which at least the share of their members with duties worked; a group whose members
 * spread over two or more areas below the share is an umbrella group and ignored, as from the groups.</li>
 * </ol>
 * Which basis fits a folder: the areas from the groups for an empty mapping against the stems of the shifts' leading
 * codes (BR-F and BR are one, A1 and A2 too; hidden and rare shifts left out), which depend on the names alone; the
 * families when the groups give fewer areas than familyRatio percent of the stems.
 */
final class Sp5Families {

    static final String GROUPS = "groups";
    static final String FAMILIES = "families";

    /** A time at the end of a short name: 7-19, 07:00-19:00, 6.30-14. */
    private static final Pattern TIME = Pattern.compile("\\d{1,2}(?:[:.]?\\d{2})?\\s*-\\s*\\d{1,2}(?:[:.]?\\d{2})?$");

    private Sp5Families() {
    }

    /**
     * The proposals from the groups or the families (basis null: the recommended one), with "basis", "recommended",
     * "basisReason" (a German sentence with the numbers), "groupAreas" and "stems" (the counts for an empty mapping).
     */
    static Map<String, Object> propose(Path folder, Object mapping, Settings s, String basis) {
        int groupAreas = ((List<?>) Sp5Proposals.propose(folder, Map.of(), s.withoutDiscarded()).get("areas")).size();
        Set<String> stems = new TreeSet<>();
        for (Family fam : families(read(folder, Map.of(), s), s, false)) {
            fam.prefixes.forEach(p -> stems.add(stem(p)));
        }
        int families = stems.size();
        boolean byFamilies = groupAreas * 100 < s.familyRatio() * families;
        String recommended = byFamilies ? FAMILIES : GROUPS;
        String used = basis == null ? recommended : basis;
        Map<String, Object> result = new LinkedHashMap<>(FAMILIES.equals(used) ? proposeFamilies(folder, mapping, s)
                : Sp5Proposals.propose(folder, mapping, s));
        result.put("basis", used);
        result.put("recommended", recommended);
        result.put("groupAreas", groupAreas);
        result.put("stems", families);
        result.put("basisReason", "Aus den Gruppen ergeben sich " + count(groupAreas, "Bereich", "Bereiche")
                + ", die Schichtnamen haben " + count(families, "Kürzel", "verschiedene Kürzel") + ". "
                + (byFamilies ? "Hier passen die Schichtnamen: die Gruppen trennen die Dienste kaum (weniger als "
                        + s.familyRatio() + " % so viele Bereiche wie Kürzel)."
                        : "Hier passen die Gruppen: sie trennen die Dienste (mindestens " + s.familyRatio()
                                + " % so viele Bereiche wie Kürzel)."));
        return result;
    }

    // ---- families ----

    /** A family: its leading codes, shifts and duties per person. */
    private static final class Family {
        final TreeSet<String> prefixes = new TreeSet<>();
        final List<Object> shifts = new ArrayList<>();
        final Map<Object, Integer> people = new HashMap<>();
        final List<String> merged = new ArrayList<>(); // why other families came in
        int duties;

        String name() {
            return String.join(", ", prefixes);
        }

        /** The leading code with the most duties (ties by name), for the area id. */
        String main(Map<String, Integer> prefixDuties) {
            return prefixes.stream().max(Comparator.<String>comparingInt(p -> prefixDuties.getOrDefault(p, 0))
                    .thenComparing(Comparator.<String>reverseOrder())).orElseThrow();
        }
    }

    /** The leading code of a short name. */
    static String prefix(String code) {
        String c = code.strip();
        int space = -1;
        for (int i = 0; i < c.length(); i++) {
            if (Character.isWhitespace(c.charAt(i))) {
                space = i;
                break;
            }
        }
        String head = space >= 0 ? c.substring(0, space) : c;
        if (space < 0) {
            Matcher t = TIME.matcher(head);
            if (t.find() && t.start() > 0) {
                head = head.substring(0, t.start());
            }
        }
        head = head.replaceAll("[-/_.]+$", "");
        return head.isEmpty() ? c : head;
    }

    private static List<String> parts(String prefix) {
        return List.of(prefix.split("[-/]"));
    }

    /** The first part without digits at its end: BR-F -> BR, A2 -> A, X1-T -> X. */
    private static String stem(String prefix) {
        String first = parts(prefix).get(0);
        String letters = first.replaceAll("\\d+$", "");
        return letters.isEmpty() ? first : letters;
    }

    /** The same stem (BR-F and BR, A1 and A2), or one of them a part of the other (K/KU and KU). */
    private static boolean related(Family a, Family b) {
        for (String x : a.prefixes) {
            for (String y : b.prefixes) {
                if (stem(x).equals(stem(y)) || parts(x).contains(y) || parts(y).contains(x)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The share of b's duties worked by people who work a (with at least the minimum of duties in it). */
    private static double by(Family b, Family a, int min) {
        if (b.duties == 0) {
            return 0;
        }
        int n = 0;
        for (Map.Entry<Object, Integer> e : b.people.entrySet()) {
            if (a.people.getOrDefault(e.getKey(), 0) >= min) {
                n += e.getValue();
            }
        }
        return (double) n / b.duties;
    }

    /** The data the families come from. */
    private record Folder(Map<String, Object> codes, Map<Object, Map<String, Object>> shiftById, List<Object[]> duties,
            Map<Object, List<Object>> byShift, Set<Object> withDemand, Set<Object> noProposal) {
    }

    @SuppressWarnings("unchecked")
    private static Folder read(Path folder, Object mapping, Settings s) {
        Map<?, ?> m = mapping instanceof Map<?, ?> map ? map : Map.of();
        Map<String, Object> codes = Sp5Codes.codes(folder, m, new ArrayList<>());
        List<Map<String, Object>> shifts = (List<Map<String, Object>>) codes.get("shifts");
        List<Map<String, Object>> rows = Sp5Codes.table(folder, "5SHIFT");
        Map<Object, Map<String, Object>> shiftById = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        Set<Object> noProposal = new HashSet<>();
        for (int i = 0; i < shifts.size(); i++) {
            Object id = Py.key(rows.get(i).get("ID"));
            if (id == null || shiftById.containsKey(id)) {
                continue;
            }
            String code = (String) shifts.get(i).get("code");
            if (!seen.add(code) || code.isEmpty()) {
                noProposal.add(id);
            }
            shiftById.put(id, shifts.get(i));
        }
        List<Object[]> duties = Sp5Proposals.duties(folder, s.months());
        Map<Object, List<Object>> byShift = new HashMap<>();
        for (Object[] d : duties) {
            byShift.computeIfAbsent(d[1], k -> new ArrayList<>()).add(d[0]);
        }
        Set<Object> withDemand = new HashSet<>();
        for (String table : List.of("5SHDEM", "5SPDEM")) {
            for (Map<String, Object> r : Sp5Codes.table(folder, table)) {
                if (Py.truthy(r.get("MIN")) || Py.truthy(r.get("MAX"))) {
                    withDemand.add(Py.key(r.get("SHIFTID")));
                }
            }
        }
        return new Folder(codes, shiftById, duties, byShift, withDemand, noProposal);
    }

    /** Whether a shift without a rule is proposed to be ignored, and why (null: it belongs to a family). */
    private static String ignoredWhy(Folder f, Object id, Settings s) {
        Map<String, Object> sh = f.shiftById().get(id);
        int n = f.byShift().getOrDefault(id, List.of()).size();
        if (Boolean.TRUE.equals(sh.get("hidden"))) {
            return "In Schichtplaner 5 ausgeblendet.";
        }
        if (!f.withDemand().contains(id) && n < Math.max(1, s.minDuties())) {
            return n == 0 ? "Keine Dienste im Zeitraum und kein Bedarf."
                    : "Nur " + n + (n == 1 ? " Dienst" : " Dienste") + " im Zeitraum (weniger als " + s.minDuties()
                            + ") und kein Bedarf.";
        }
        return null;
    }

    private static boolean open(Map<String, Object> shift) {
        return "none".equals(((Map<?, ?>) shift.get("result")).get("kind"));
    }

    /** The families of the shifts the mapping does not meet, merged (or one per leading code). */
    private static List<Family> families(Folder f, Settings s, boolean merge) {
        Map<String, Family> byPrefix = new TreeMap<>();
        for (Map.Entry<Object, Map<String, Object>> e : f.shiftById().entrySet()) {
            Object id = e.getKey();
            if (!open(e.getValue()) || f.noProposal().contains(id) || ignoredWhy(f, id, s) != null) {
                continue;
            }
            String p = prefix((String) e.getValue().get("code"));
            Family fam = byPrefix.computeIfAbsent(p, k -> new Family());
            fam.prefixes.add(p);
            fam.shifts.add(id);
            for (Object person : f.byShift().getOrDefault(id, List.of())) {
                fam.people.merge(person, 1, Integer::sum);
                fam.duties++;
            }
        }
        List<Family> families = new ArrayList<>(byPrefix.values());
        if (!merge) {
            return families;
        }
        double share = s.familyShare() / 100.0;
        int min = Math.max(1, s.familyMinDuties());
        while (true) {
            Family bestInto = null;
            Family bestFrom = null;
            double best = -1;
            String why = null;
            for (int i = 0; i < families.size(); i++) {
                for (int j = i + 1; j < families.size(); j++) {
                    Family a = families.get(i);
                    Family b = families.get(j);
                    // the smaller one goes into the larger (ties: the later name into the earlier)
                    Family large = b.duties > a.duties ? b : a;
                    Family small = large == a ? b : a;
                    double into = by(small, large, min);
                    double score;
                    String text;
                    if (related(a, b)) {
                        score = into;
                        text = small.name() + " zu " + large.name() + ": " + Sp5Proposals.percent(into) + " der "
                                + dienste(small.duties) + " von " + small.name() + " leisten Personen, die auch "
                                + large.name() + " arbeiten.";
                    } else {
                        double back = by(large, small, min);
                        score = Math.min(into, back);
                        text = small.name() + " zu " + large.name() + ": " + Sp5Proposals.percent(into) + " der "
                                + dienste(small.duties) + " von " + small.name() + " leisten Personen, die auch "
                                + large.name() + " arbeiten, und " + Sp5Proposals.percent(back) + " der "
                                + dienste(large.duties) + " von " + large.name() + " Personen, die auch "
                                + small.name() + " arbeiten.";
                    }
                    if (score >= share && score > best) {
                        best = score;
                        bestInto = large;
                        bestFrom = small;
                        why = text;
                    }
                }
            }
            if (bestInto == null) {
                return families;
            }
            bestInto.merged.addAll(bestFrom.merged);
            bestInto.merged.add(why);
            bestInto.prefixes.addAll(bestFrom.prefixes);
            bestInto.shifts.addAll(bestFrom.shifts);
            Family into = bestInto;
            bestFrom.people.forEach((p, n) -> into.people.merge(p, n, Integer::sum));
            bestInto.duties += bestFrom.duties;
            families.remove(bestFrom);
        }
    }

    // ---- proposals ----

    @SuppressWarnings("unchecked")
    private static Map<String, Object> proposeFamilies(Path folder, Object mapping, Settings s) {
        Map<?, ?> m = mapping instanceof Map<?, ?> map ? map : Map.of();
        Folder f = read(folder, m, s);
        double share = s.share() / 100.0;
        Map<Object, Map<String, Object>> shiftProposal = new LinkedHashMap<>();
        Map<Object, String> shiftArea = new HashMap<>(); // shift -> area id (existing rules, then proposals)
        Map<String, Set<String>> prefixAreas = new HashMap<>(); // leading code -> areas the mapping gives its shifts
        for (Map.Entry<Object, Map<String, Object>> e : f.shiftById().entrySet()) {
            Map<String, Object> r = (Map<String, Object>) e.getValue().get("result");
            if ("rule".equals(r.get("kind")) && r.get("areaId") instanceof String a) {
                shiftArea.put(e.getKey(), a);
                prefixAreas.computeIfAbsent(prefix((String) e.getValue().get("code")), k -> new TreeSet<>()).add(a);
            }
            if (open(e.getValue()) && !f.noProposal().contains(e.getKey())) {
                String why = ignoredWhy(f, e.getKey(), s);
                if (why != null) {
                    shiftProposal.put(e.getKey(), Sp5Proposals.ignoredShift(e.getValue(), why));
                }
            }
        }
        List<Family> families = families(f, s, true);

        // ---- areas: an existing one for a leading code the mapping already places, a new one per other family ----
        Set<String> usedIds = new HashSet<>();
        List<String> usedColors = new ArrayList<>();
        Map<String, String> areaByName = new HashMap<>();
        for (Object a : m.get("areas") instanceof List<?> l ? l : List.of()) {
            if (a instanceof Map<?, ?> area) {
                if (area.get("id") instanceof String id) {
                    usedIds.add(id);
                    if (area.get("name") instanceof String n) {
                        areaByName.putIfAbsent(n, id);
                    }
                }
                if (area.get("color") instanceof String c) {
                    usedColors.add(c.toUpperCase(Locale.ROOT));
                }
            }
        }
        Map<String, Integer> prefixDuties = new HashMap<>();
        for (Family fam : families) {
            for (Object id : fam.shifts) {
                prefixDuties.merge(prefix((String) f.shiftById().get(id).get("code")),
                        f.byShift().getOrDefault(id, List.of()).size(), Integer::sum);
            }
        }
        List<Map<String, Object>> areaProposals = new ArrayList<>();
        Map<String, Map<String, Object>> areaById = new HashMap<>();
        Map<Family, String> familyArea = new LinkedHashMap<>();
        Map<Family, String> familyWhy = new HashMap<>();
        for (Family fam : families) {
            Set<String> placed = new TreeSet<>();
            fam.prefixes.forEach(p -> placed.addAll(prefixAreas.getOrDefault(p, Set.of())));
            if (placed.size() == 1) {
                String a = placed.iterator().next();
                familyArea.put(fam, a);
                familyWhy.put(fam, "Schichten mit dem Kürzel " + fam.name() + " gehören schon zum Bereich " + a + ".");
                continue;
            }
            if (areaByName.containsKey(fam.name())) {
                familyArea.put(fam, areaByName.get(fam.name()));
                familyWhy.put(fam, "Schichtfamilie " + fam.name() + " (Bereich " + areaByName.get(fam.name()) + ").");
                continue;
            }
            // several leading codes with one stem (LK, LK-F, LK-S): the stem; else the leading code with most duties
            Set<String> stems = new TreeSet<>();
            fam.prefixes.forEach(p -> stems.add(stem(p)));
            String main = fam.prefixes.size() > 1 && stems.size() == 1 ? stems.iterator().next() : fam.main(prefixDuties);
            String base = main.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
            String id = base.isEmpty() || base.length() > 6 ? Sp5Proposals.areaId(fam.name(), usedIds) : base;
            for (int i = 2; usedIds.contains(id); i++) {
                id = base + i;
            }
            usedIds.add(id);
            String color = Sp5Proposals.COLORS.stream().filter(c -> !usedColors.contains(c)).findFirst()
                    .orElse(Sp5Proposals.COLORS.get(areaProposals.size() % Sp5Proposals.COLORS.size()));
            usedColors.add(color);
            Set<Object> working = new HashSet<>();
            fam.people.forEach((p, n) -> {
                if (n >= Math.max(1, s.familyMinDuties())) {
                    working.add(p);
                }
            });
            Map<String, Object> area = new LinkedHashMap<>();
            area.put("key", "area:" + fam.name());
            area.put("id", id);
            area.put("name", fam.name());
            area.put("color", color);
            area.put("family", fam.name());
            area.put("reason", "Schichtfamilie " + fam.name() + ": " + covers(f, fam.shifts) + "; "
                    + count(working.size(), "Person", "Personen") + " mit mindestens "
                    + count(Math.max(1, s.familyMinDuties()), "Dienst", "Diensten") + " darin."
                    + (fam.merged.isEmpty() ? "" : " Zusammengelegt: " + String.join(" ", fam.merged)));
            areaProposals.add(area);
            areaById.put(id, area);
            familyArea.put(fam, id);
            familyWhy.put(fam, "Schichtfamilie " + fam.name() + " (neuer Bereich " + id + ").");
        }

        // ---- shift rules: a pattern per leading code, or the exact codes ----
        Map<Object, Family> familyOf = new HashMap<>();
        families.forEach(fam -> fam.shifts.forEach(id -> familyOf.put(id, fam)));
        for (Family fam : families) {
            String area = familyArea.get(fam);
            Map<String, List<Object>> byPrefix = new TreeMap<>();
            for (Object id : fam.shifts) {
                byPrefix.computeIfAbsent(prefix((String) f.shiftById().get(id).get("code")), k -> new ArrayList<>())
                        .add(id);
            }
            byPrefix.forEach((p, ids) -> {
                for (Map.Entry<String, List<Object>> rule : rules(f, p, ids, familyOf, fam).entrySet()) {
                    List<Object> covered = rule.getValue();
                    covered.forEach(id -> shiftArea.put(id, area));
                    Map<String, Object> proposal = new LinkedHashMap<>();
                    proposal.put("key", "shift:" + rule.getKey());
                    proposal.put("code", rule.getKey());
                    proposal.put("name", "");
                    proposal.put("match", rule.getKey());
                    proposal.put("kind", "rule");
                    proposal.put("areaId", area);
                    proposal.put("covers", covered.stream().map(id -> Map.of("code",
                            f.shiftById().get(id).get("code"), "duties",
                            f.byShift().getOrDefault(id, List.of()).size())).toList());
                    // a hidden or rare shift the pattern also catches: its own proposal ignores it
                    List<String> alsoIgnored = new ArrayList<>();
                    shiftProposal.forEach((k, q) -> {
                        if ("ignored".equals(q.get("kind")) && Sp5Export.matches(rule.getKey(), (String) q.get("code"))) {
                            alsoIgnored.add((String) q.get("code"));
                        }
                    });
                    proposal.put("reason", familyWhy.get(fam) + " Deckt heute " + covers(f, covered) + "."
                            + (alsoIgnored.isEmpty() ? "" : " Das Muster fängt auch " + String.join(", ", alsoIgnored)
                                    + "; dafür gibt es den Vorschlag „ignorieren“, ohne ihn " + (alsoIgnored.size() == 1
                                    ? "käme sie" : "kämen sie") + " in diesen Bereich."));
                    shiftProposal.put("rule:" + rule.getKey(), proposal);
                }
            });
        }

        // ---- groups: the areas their members worked ----
        Sp5Proposals.Groups gd = Sp5Proposals.groups(folder, f.codes());
        Map<Object, Group> groups = gd.groups();
        Map<Object, Set<Object>> shiftsOf = new HashMap<>();
        for (Object[] d : f.duties()) {
            shiftsOf.computeIfAbsent(d[0], k -> new HashSet<>()).add(d[1]);
        }
        Map<Object, List<String>> groupAreas = new LinkedHashMap<>();
        for (Group g : groups.values()) {
            List<String> existing = Sp5Proposals.existingAreas(g.result());
            if (existing != null) {
                groupAreas.put(g.id(), existing);
            }
        }
        Map<Object, Map<String, Object>> groupProposal = new LinkedHashMap<>();
        for (Group g : groups.values()) {
            if (!"none".equals(g.result().get("kind")) || gd.repeated().contains(g.id()) || g.name().isEmpty()
                    || Sp5Proposals.inheritsArea(g.id(), groups, groupAreas)) {
                continue;
            }
            Set<Object> members = gd.members().get(g.id());
            Set<Object> w = new HashSet<>();
            members.forEach(p -> {
                if (shiftsOf.containsKey(p)) {
                    w.add(p);
                }
            });
            if (members.isEmpty()) {
                groupProposal.put(g.id(), Sp5Proposals.ignoredGroup(g, "Die Gruppe hat keine Mitglieder."));
                continue;
            }
            if (w.isEmpty()) {
                groupProposal.put(g.id(), Sp5Proposals.ignoredGroup(g, "Die " + members.size()
                        + " Mitglieder haben keine Dienste im Zeitraum."));
                continue;
            }
            List<Map.Entry<String, Integer>> ranked = Sp5Proposals.ranked(Sp5Proposals.coverage(w, shiftsOf, shiftArea));
            List<Map.Entry<String, Integer>> strong = ranked.stream().filter(e -> e.getValue() >= share * w.size())
                    .toList();
            if (!strong.isEmpty()) {
                List<String> areas = strong.stream().map(Map.Entry::getKey).toList();
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("key", "group:" + g.name());
                p.put("name", g.name());
                p.put("kind", "rule");
                p.put("areaIds", areas);
                p.put("reason", strong.stream().map(e -> e.getValue() + " von " + w.size()
                        + " Mitgliedern mit Diensten (" + Sp5Proposals.percent(e.getValue(), w.size())
                        + ") haben Dienste im Bereich " + e.getKey()).collect(Collectors.joining(", ")) + ".");
                groupProposal.put(g.id(), p);
                groupAreas.put(g.id(), areas);
            } else if (ranked.size() >= 2) {
                groupProposal.put(g.id(), Sp5Proposals.ignoredGroup(g, "Sammelgruppe: die " + w.size()
                        + " Mitglieder mit Diensten verteilen sich auf die Bereiche " + ranked.stream()
                                .map(e -> e.getKey() + " (" + Sp5Proposals.percent(e.getValue(), w.size()) + ")")
                                .collect(Collectors.joining(", ")) + "; keiner erreicht " + s.share() + " %."));
            }
        }

        // a new area travels with each proposal that needs it
        for (Map<String, Object> p : shiftProposal.values()) {
            p.put("newAreas", p.get("areaId") instanceof String a && areaById.containsKey(a)
                    ? List.of(Sp5Proposals.areaEntry(areaById.get(a))) : List.of());
        }
        for (Map<String, Object> p : groupProposal.values()) {
            List<Object> needed = new ArrayList<>();
            for (Object a : p.get("areaIds") instanceof List<?> l ? l : List.of()) {
                if (areaById.containsKey(a)) {
                    needed.add(Sp5Proposals.areaEntry(areaById.get(a)));
                }
            }
            p.put("newAreas", needed);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        int[] discarded = { 0 };
        result.put("areas", Sp5Proposals.kept(areaProposals, s.discarded(), discarded));
        result.put("shifts", Sp5Proposals.kept(new ArrayList<>(shiftProposal.values()), s.discarded(), discarded));
        result.put("groups", Sp5Proposals.kept(new ArrayList<>(groupProposal.values()), s.discarded(), discarded));
        result.put("compensatoryRest", Sp5Proposals.kept(Sp5Proposals.restProposals(f.codes(), s), s.discarded(),
                discarded));
        result.put("discarded", discarded[0]);
        result.put("duties", f.duties().size());
        return result;
    }

    /**
     * The rules of one leading code: {pattern: shifts it covers}. "AX *" for codes with a space after it, "AX[0-9]*"
     * for a time right after it, the code itself when it is the leading code; the exact codes instead when such a
     * pattern would also catch a shift without a rule outside this family.
     */
    private static Map<String, List<Object>> rules(Folder f, String prefix, List<Object> ids,
            Map<Object, Family> familyOf, Family fam) {
        Map<String, List<Object>> rules = new LinkedHashMap<>();
        String exact = Sp5Proposals.exact(prefix);
        for (Object id : ids) {
            String code = ((String) f.shiftById().get(id).get("code")).strip();
            String rest = code.substring(Math.min(prefix.length(), code.length()));
            String pattern = code.equals(prefix) ? exact : !rest.isEmpty() && Character.isWhitespace(rest.charAt(0))
                    ? exact + " *" : !rest.isEmpty() && Character.isDigit(rest.charAt(0)) ? exact + "[0-9]*"
                            : Sp5Proposals.exact(code);
            rules.computeIfAbsent(pattern, k -> new ArrayList<>()).add(id);
        }
        Map<String, List<Object>> safe = new LinkedHashMap<>();
        rules.forEach((pattern, covered) -> {
            boolean wide = false;
            for (Map.Entry<Object, Map<String, Object>> e : f.shiftById().entrySet()) {
                if (open(e.getValue()) && !covered.contains(e.getKey()) && familyOf.get(e.getKey()) != null
                        && familyOf.get(e.getKey()) != fam
                        && Sp5Export.matches(pattern, (String) e.getValue().get("code"))) {
                    wide = true;
                }
            }
            if (wide) {
                covered.forEach(id -> safe.put(Sp5Proposals.exact((String) f.shiftById().get(id).get("code")),
                        List.of(id)));
            } else {
                safe.put(pattern, covered);
            }
        });
        return safe;
    }

    /** "AX 7-19 (120 Dienste), AX 19-7 (98 Dienste)". */
    private static String covers(Folder f, List<Object> ids) {
        return ids.stream().map(id -> f.shiftById().get(id).get("code") + " ("
                + dienste(f.byShift().getOrDefault(id, List.of()).size()) + ")").collect(Collectors.joining(", "));
    }

    private static String dienste(int n) {
        return count(n, "Dienst", "Dienste");
    }

    private static String count(int n, String one, String more) {
        return n + " " + (n == 1 ? one : more);
    }
}
