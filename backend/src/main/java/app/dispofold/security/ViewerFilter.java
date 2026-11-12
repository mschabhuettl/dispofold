package app.dispofold.security;

import java.util.Iterator;
import java.util.Set;
import java.util.regex.Pattern;

import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.ext.Provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Viewers (role {@code viewer} without {@code planner}) see the roster but no personal wishes, restrictions, family
 * rules or absence reasons (health data). Their answers of {@code GET /api/dataset}, {@code /api/periods/{month}},
 * {@code /api/plans/{month}/analysis} and {@code /api/plans/{month}/people/{id}} are redacted here: wishes,
 * restrictions and family rules are empty, every absence has the type {@code ABW} ("abwesend"), wish counts (also
 * in the history) are 0, personal balances (hours account, compensatory rest, credited and charged absence hours)
 * are null
 * and constraint results about wishes, family rules, restrictions and absences are left out. Exports are planner
 * only (PlanResource).
 */
@Provider
public class ViewerFilter implements ContainerResponseFilter {

    public static final String NEUTRAL_ABSENCE = "ABW";
    /** Constraints whose results would tell wishes, family rules, restrictions or absences. */
    static final Set<String> HIDDEN_CONSTRAINTS = Set.of("wishes", "family", "neverRestriction", "onRequest",
            "absence");
    static final Set<String> WISH_STATS = Set.of("wishes", "wishesMet", "historyWishes", "historyWishesMet");
    /** Personal balances: hours account and compensatory rest (and the absence hours behind them). */
    static final Set<String> PERSON_BALANCES = Set.of("hoursAccount", "compRestBalance");
    static final Set<String> HISTORY_BALANCES = Set.of("hoursAccountAfter", "compRestBalanceAfter", "compRestUsed",
            "compRestEarned");
    static final Set<String> STATS_BALANCES = Set.of("accountBefore", "accountAfter", "compRestBalanceAfter",
            "compRestEarned", "chargedHours", "creditedHours");

    private static final Pattern PERIOD = Pattern.compile("api/periods/[^/]+");
    private static final Pattern ANALYSIS = Pattern.compile("api/plans/[^/]+/analysis");
    private static final Pattern PERSON = Pattern.compile("api/plans/[^/]+/people/[^/]+");

    @Inject
    ObjectMapper mapper;

    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        SecurityContext sc = request.getSecurityContext();
        if (!response.hasEntity() || response.getStatus() != 200 || !"GET".equals(request.getMethod()) || sc == null
                || sc.isUserInRole("planner") || !sc.isUserInRole("viewer")) {
            return;
        }
        // Segments without empty ones: "/api/dataset/" and "//api/dataset" reach the same method.
        String path = String.join("/", request.getUriInfo().getPathSegments().stream().map(s -> s.getPath())
                .filter(s -> !s.isEmpty()).toList());
        JsonNode node;
        if (path.equals("api/dataset")) {
            node = dataset(tree(response));
        } else if (PERIOD.matcher(path).matches()) {
            node = period(tree(response));
        } else if (ANALYSIS.matcher(path).matches()) {
            node = analysis(tree(response));
        } else if (PERSON.matcher(path).matches()) {
            node = explanation(tree(response));
        } else {
            return;
        }
        response.setEntity(node);
    }

    private JsonNode tree(ContainerResponseContext response) {
        return mapper.valueToTree(response.getEntity());
    }

    static JsonNode dataset(JsonNode d) {
        for (JsonNode p : d.path("people")) {
            ((ObjectNode) p).putArray("familyRules");
            blank(p, PERSON_BALANCES);
        }
        empty(d, "restrictions");
        for (JsonNode h : d.path("history")) {
            zero(h, Set.of("wishes", "wishesMet"));
            blank(h, HISTORY_BALANCES);
        }
        for (JsonNode period : d.path("periods")) {
            period(period);
        }
        return d;
    }

    static JsonNode period(JsonNode p) {
        empty(p, "wishes");
        for (JsonNode a : p.path("absences")) {
            ((ObjectNode) a).put("typeCode", NEUTRAL_ABSENCE);
        }
        return p;
    }

    static JsonNode analysis(JsonNode a) {
        removeHidden(a.path("constraints"), "id");
        for (JsonNode stats : a.path("people")) {
            stats(stats);
        }
        return a;
    }

    static JsonNode explanation(JsonNode e) {
        stats(e.path("stats"));
        removeHidden(e.path("hits"), "constraintId");
        for (JsonNode hit : e.path("hits")) {
            if (hit.path("params") instanceof ObjectNode params) {
                params.remove("typeCode");
            }
        }
        return e;
    }

    private static void stats(JsonNode stats) {
        zero(stats, WISH_STATS);
        blank(stats, STATS_BALANCES);
    }

    private static void blank(JsonNode node, Set<String> fields) {
        if (node instanceof ObjectNode o) {
            fields.forEach(k -> {
                if (o.has(k)) {
                    o.putNull(k);
                }
            });
        }
    }

    private static void zero(JsonNode node, Set<String> fields) {
        if (node instanceof ObjectNode o) {
            fields.forEach(k -> {
                if (o.has(k)) {
                    o.put(k, 0);
                }
            });
        }
    }

    private static void removeHidden(JsonNode list, String idField) {
        if (list instanceof ArrayNode array) {
            for (Iterator<JsonNode> it = array.iterator(); it.hasNext();) {
                if (HIDDEN_CONSTRAINTS.contains(it.next().path(idField).asText())) {
                    it.remove();
                }
            }
        }
    }

    private static void empty(JsonNode node, String field) {
        if (node instanceof ObjectNode o && o.has(field)) {
            o.putArray(field);
        }
    }
}
