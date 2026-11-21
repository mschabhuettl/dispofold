package app.dispofold.plugins.beispiel;

import java.util.Map;

import io.quarkus.test.junit.QuarkusTestProfile;

/** The operator hides the example plugin. */
public class HiddenProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("dispofold.plugins.hidden", "andere, beispiel");
    }
}
