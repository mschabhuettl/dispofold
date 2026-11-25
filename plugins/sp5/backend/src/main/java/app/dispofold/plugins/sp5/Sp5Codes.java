package app.dispofold.plugins.sp5;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import jakarta.ws.rs.core.Response.Status;

import app.dispofold.plugin.api.PluginErrors;
import app.dispofold.plugins.sp5.port.Dbf;
import app.dispofold.plugins.sp5.port.Py;
import app.dispofold.plugins.sp5.port.Sp5Export;

/**
 * Which rule of a mapping each shift, group and absence type of the folder meets, matched as the import matches:
 * shifts and absence types by their short name, groups by their name; a pattern that is no valid pattern matches
 * nothing. The tables are only read, without the import's warnings; a missing table gives no rows.
 */
final class Sp5Codes {

    private Sp5Codes() {
    }

    static Map<String, Object> codes(Path folder, Object mapping, List<String> errors) {
        Map<?, ?> m = mapping instanceof Map<?, ?> map ? map : Map.of();
        List<Object> shifts = new ArrayList<>();
        for (Map<String, Object> r : table(folder, "5SHIFT")) {
            String code = text(r.get("SHORTNAME"));
            Map<String, Object> result;
            int ignored = first(list(m, "ignoreShifts"), code);
            int rule = ignored < 0 ? firstRule(list(m, "shifts"), code) : -1;
            if (ignored >= 0) {
                result = kind("ignored", ignored);
            } else if (rule >= 0) {
                Map<?, ?> found = (Map<?, ?>) list(m, "shifts").get(rule);
                result = kind("rule", rule);
                result.put("areaId", found.get("areaId") instanceof String s ? s : null);
                result.put("category", found.get("category") instanceof String s && !s.isEmpty() ? s : null);
            } else {
                result = Map.of("kind", "none");
            }
            Map<String, Object> shift = new LinkedHashMap<>();
            shift.put("sp5Id", Py.str(r.get("ID")));
            shift.put("code", code);
            shift.put("name", text(r.get("NAME")));
            shift.put("hidden", Py.truthy(r.get("HIDE")));
            shift.put("result", result);
            shifts.add(shift);
        }
        Map<Object, Set<Object>> members = new LinkedHashMap<>();
        for (Map<String, Object> a : table(folder, "5GRASG")) {
            members.computeIfAbsent(Py.key(a.get("GROUPID")), k -> new HashSet<>()).add(Py.key(a.get("EMPLOYEEID")));
        }
        List<Object> groups = new ArrayList<>();
        for (Map<String, Object> g : table(folder, "5GROUP")) {
            String name = text(g.get("NAME"));
            Map<String, Object> result;
            int excluded = first(list(m, "excludeGroups"), name);
            int rule = excluded < 0 ? firstRule(list(m, "groups"), name) : -1;
            int ignored = excluded < 0 && rule < 0 ? first(list(m, "ignoreGroups"), name) : -1;
            if (excluded >= 0) {
                result = kind("excluded", excluded);
            } else if (rule >= 0) {
                result = kind("rule", rule);
                result.put("areaIds", ((Map<?, ?>) list(m, "groups").get(rule)).get("areaIds"));
            } else if (ignored >= 0) {
                result = kind("ignored", ignored);
            } else {
                result = Map.of("kind", "none");
            }
            Map<String, Object> group = new LinkedHashMap<>();
            group.put("sp5Id", Py.str(g.get("ID")));
            group.put("name", name);
            group.put("members", members.getOrDefault(Py.key(g.get("ID")), Set.of()).size());
            group.put("result", result);
            groups.add(group);
        }
        inherit(folder, groups);
        List<Object> types = new ArrayList<>();
        for (Map<String, Object> t : table(folder, "5LEAVT")) {
            String code = text(t.get("SHORTNAME"));
            if (code.isEmpty()) {
                continue; // the import leaves it out
            }
            int ignored = first(list(m, "ignoreAbsences"), code);
            int rest = ignored < 0 ? first(list(m, "compensatoryRestAbsences"), code) : -1;
            Map<String, Object> type = new LinkedHashMap<>();
            type.put("sp5Id", Py.str(t.get("ID")));
            type.put("code", code);
            type.put("name", text(t.get("NAME")));
            type.put("result", ignored >= 0 ? kind("ignored", ignored)
                    : rest >= 0 ? kind("compensatoryRest", rest) : Map.of("kind", "none"));
            types.add(type);
        }
        Map<String, Object> codes = new LinkedHashMap<>();
        codes.put("errors", errors);
        codes.put("shifts", shifts);
        codes.put("groups", groups);
        codes.put("absenceTypes", types);
        // the people of the folder (rows of its table of employees), for a line such as "86 Personen gefunden"
        codes.put("people", table(folder, "5EMPL").size());
        return codes;
    }

    private static Map<String, Object> kind(String kind, int index) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("kind", kind);
        r.put("index", index);
        return r;
    }

    private static List<?> list(Map<?, ?> mapping, String key) {
        return mapping.get(key) instanceof List<?> l ? l : List.of();
    }

    /** The index of the first valid pattern that matches, or -1. */
    private static int first(List<?> patterns, String value) {
        for (int i = 0; i < patterns.size(); i++) {
            if (Sp5Export.validPattern(patterns.get(i)) && Sp5Export.matches((String) patterns.get(i), value)) {
                return i;
            }
        }
        return -1;
    }

    /** The index of the first rule (an object) whose valid pattern "match" matches, or -1. */
    private static int firstRule(List<?> rules, String value) {
        for (int i = 0; i < rules.size(); i++) {
            if (rules.get(i) instanceof Map<?, ?> r && Sp5Export.validPattern(r.get("match"))
                    && Sp5Export.matches((String) r.get("match"), value)) {
                return i;
            }
        }
        return -1;
    }

    static String text(Object value) {
        return value instanceof String s ? Py.strip(s) : "";
    }

    /** The table as the import finds it (the file name in any case), or no rows. */
    static List<Map<String, Object>> table(Path folder, String name) {
        String wanted = (name + ".dbf").toLowerCase(Locale.ROOT);
        String[] entries = folder.toFile().list();
        for (String entry : entries == null ? new String[0] : entries) {
            if (entry.toLowerCase(Locale.ROOT).equals(wanted)) {
                try {
                    return Dbf.read(folder.resolve(entry));
                } catch (Dbf.DbfError | Py.PyException e) {
                    throw PluginErrors.error(Status.BAD_REQUEST, "plugin.sp5.failed", Map.of(),
                            "Tabelle " + name + ": " + (e instanceof Dbf.DbfError ? e.getMessage()
                                    : "nicht lesbar"));
                }
            }
        }
        return List.of();
    }

    /**
     * A group without a rule of its own takes that of the nearest group above it (SUPERID), as the import does:
     * {"kind": "inherited", "from": its id, "fromName", "result": what that group decides}.
     */
    @SuppressWarnings("unchecked")
    private static void inherit(Path folder, List<Object> groups) {
        List<Map<String, Object>> rows = table(folder, "5GROUP");
        Map<Object, Integer> index = new HashMap<>();
        Map<Object, Object> parent = new HashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            Object id = Py.key(rows.get(i).get("ID"));
            index.put(id, i);
            // as the import: no SUPERID is the group 0, which a group of that id with a rule would be
            parent.put(id, Py.key(Py.truthy(rows.get(i).get("SUPERID")) ? rows.get(i).get("SUPERID") : 0L));
        }
        List<Map<String, Object>> own = groups.stream()
                .map(g -> (Map<String, Object>) ((Map<String, Object>) g).get("result")).toList();
        for (int i = 0; i < rows.size(); i++) {
            if (!"none".equals(own.get(i).get("kind"))) {
                continue;
            }
            Set<Object> seen = new HashSet<>();
            Object cur = parent.get(Py.key(rows.get(i).get("ID")));
            while (cur != null && seen.add(cur) && index.containsKey(cur)
                    && "none".equals(own.get(index.get(cur)).get("kind"))) {
                cur = parent.get(cur);
            }
            if (cur != null && index.containsKey(cur) && !"none".equals(own.get(index.get(cur)).get("kind"))) {
                Map<String, Object> from = (Map<String, Object>) groups.get(index.get(cur));
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("kind", "inherited");
                result.put("from", from.get("sp5Id"));
                result.put("fromName", from.get("name"));
                result.put("result", own.get(index.get(cur)));
                ((Map<String, Object>) groups.get(i)).put("result", result);
            }
        }
    }
}
