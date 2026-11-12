package app.dispofold.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import app.dispofold.api.FakeFacadeProfile;
import app.dispofold.model.Dataset;
import app.dispofold.model.Plan;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/** The JSON samples of docs/datenformat.md survive a read and write with the application's mapper unchanged. */
@QuarkusTest
@TestProfile(FakeFacadeProfile.class)
class ContractJsonTest {

    @Inject
    ObjectMapper mapper;

    @Inject
    Store store;

    static List<String> samples() throws Exception {
        String doc = Files.readString(Path.of("../docs/datenformat.md"));
        Matcher m = Pattern.compile("```json\\n(.*?)```", Pattern.DOTALL).matcher(doc);
        List<String> blocks = new ArrayList<>();
        while (m.find()) {
            blocks.add(m.group(1));
        }
        assertThat(blocks).hasSize(2);
        return blocks;
    }

    @Test
    void samplesRoundTrip() throws Exception {
        List<String> samples = samples();
        JsonNode datasetJson = mapper.readTree(samples.get(0));
        Dataset dataset = mapper.readValue(samples.get(0), Dataset.class);
        assertThat(mapper.readTree(mapper.writeValueAsString(dataset))).isEqualTo(datasetJson);

        JsonNode planJson = mapper.readTree(samples.get(1));
        Plan plan = mapper.readValue(samples.get(1), Plan.class);
        assertThat(mapper.readTree(mapper.writeValueAsString(plan))).isEqualTo(planJson);
    }

    @Test
    void storeKeepsDocuments() throws Exception {
        Plan plan = mapper.readValue(samples().get(1), Plan.class);
        store.savePlan(plan, 2, 1234);
        Store.StoredPlan stored = store.plan(plan.month()).orElseThrow();
        assertThat(stored.plan()).isEqualTo(plan);
        assertThat(stored.hardViolations()).isEqualTo(2);
        assertThat(stored.elapsedMillis()).isEqualTo(1234);
    }
}
