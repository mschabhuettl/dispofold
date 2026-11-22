package app.dispofold.plugin;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.Statement;
import java.time.YearMonth;
import java.util.List;

import javax.sql.DataSource;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;

import app.dispofold.api.TestData;
import app.dispofold.model.Dataset;
import app.dispofold.model.Period;
import app.dispofold.model.Person;
import app.dispofold.plugin.api.PluginHost;
import app.dispofold.store.Store;

/** The extension point {@link PluginHost#lastImport}: the baseline with the periods Dispofold holds now. */
@QuarkusTest
@TestSecurity(user = "planer", roles = "planner")
class PluginHostTest {

    static final YearMonth MONTH = YearMonth.of(2035, 3);

    @Inject
    PluginHost host;

    @Inject
    Store store;

    @Inject
    DataSource dataSource;

    @Inject
    ObjectMapper mapper;

    @Test
    void lastImportIsTheBaselineWithThePeriodsAsTheyAreNow() throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("update dataset set baseline = null");
        }
        assertThat(host.lastImport(List.of(MONTH))).isEmpty();

        Dataset imported = TestData.dataset(MONTH);
        given().contentType(ContentType.JSON).body(mapper.writeValueAsString(imported))
                .post("/api/import/dataset?replace=true").then().statusCode(200);
        // changed in Dispofold since: the master data stay as imported, the period is as it is now
        long revision = given().get("/api/dataset").then().extract().jsonPath().getLong("revision");
        given().contentType(ContentType.JSON).header("If-Match", revision).body("true")
                .put("/api/dataset/people/P0002/excluded").then().statusCode(200);
        Period now = new Period(MONTH, List.of(), List.of(), imported.periods().get(0).fixed(), List.of());
        store.savePeriod(now);

        Dataset last = host.lastImport(List.of(MONTH, MONTH.plusMonths(1))).orElseThrow();
        assertThat(last.people()).extracting(Person::id).contains("P0001", "P0002", "P0006");
        assertThat(last.people()).filteredOn(p -> p.id().equals("P0002")).singleElement()
                .extracting(Person::excludedFrom).isNull();
        assertThat(last.areas()).isEqualTo(imported.areas());
        assertThat(last.shifts()).isEqualTo(imported.shifts());
        assertThat(last.demand()).isEqualTo(imported.demand());
        assertThat(last.periods()).containsExactly(now);
        assertThat(host.lastImport(List.of()).orElseThrow().periods()).isEmpty();
    }
}
