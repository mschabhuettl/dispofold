package app.dispofold.solver;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import app.dispofold.model.Dataset;
import app.dispofold.model.Rules;
import app.dispofold.planning.PlanningInput;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/** Loads the synthetic datasets in tests/fixtures/synthetic. */
final class Fixtures {

    static final String DEMO = "demo-dataset-2027-01.synthetic.json";
    static final String SMALL = "small-dataset-2027-01.synthetic.json";
    static final String SMALL_MARCH = "small-dataset-2027-03.synthetic.json";
    static final String SMALL_OCTOBER = "small-dataset-2027-10.synthetic.json";
    /** Real size: about 150 people, 250 shift variants, 1,300 restrictions. */
    static final String LARGE = "large-dataset-2027-01.synthetic.json";

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private Fixtures() {
    }

    static Dataset dataset(String name) {
        Path dir = Path.of("..", "tests", "fixtures", "synthetic");
        try {
            return MAPPER.readValue(Files.readString(dir.resolve(name)), Dataset.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static PlanningInput input(String name) {
        Dataset d = dataset(name);
        return new PlanningInput(d, d.periods().get(0), Rules.defaults(), List.of());
    }
}
