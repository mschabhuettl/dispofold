package app.dispofold.api;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response.Status;

import app.dispofold.model.Area;
import app.dispofold.model.Dataset;
import app.dispofold.model.Person;
import app.dispofold.store.Store;

@Path("/api/dataset")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({ "planner", "viewer" })
public class DatasetResource {

    @Inject
    Store store;

    @Inject
    PlanService plans;

    /** The master data, or an empty dataset before the first import. */
    @GET
    public Dataset get() {
        return store.dataset().orElseGet(() -> new Dataset(Dataset.FORMAT, 1, null, List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of()));
    }

    @PUT
    @Path("people/{personId}")
    @RolesAllowed("planner")
    public Person updatePerson(@PathParam("personId") String personId, Person person) {
        synchronized (Store.DATASET_LOCK) {
            return update(personId, person);
        }
    }

    private Person update(String personId, Person person) {
        if (person == null || !personId.equals(person.id())) {
            throw ApiErrors.error(Status.BAD_REQUEST, "person.idMismatch", Map.of("personId", personId),
                    "Die Id im Pfad und im Body muss gleich sein");
        }
        Dataset d = plans.dataset();
        if (d.people().stream().noneMatch(p -> p.id().equals(personId))) {
            throw ApiErrors.error(Status.NOT_FOUND, "person.unknown", Map.of("personId", personId),
                    "Unbekannte Person");
        }
        Set<String> areas = d.areas().stream().map(Area::id).collect(Collectors.toSet());
        if (person.areaIds() == null || !areas.containsAll(person.areaIds())) {
            throw ApiErrors.error(Status.BAD_REQUEST, "person.unknownArea", Map.of("personId", personId),
                    "Unbekannter Bereich");
        }
        List<Person> people = d.people().stream().map(p -> p.id().equals(personId) ? person : p).toList();
        store.saveDataset(new Dataset(d.format(), d.version(), d.source(), d.areas(), d.shifts(), d.demand(),
                d.holidays(), d.absenceTypes(), people, d.restrictions(), d.history(), List.of()));
        return person;
    }
}
