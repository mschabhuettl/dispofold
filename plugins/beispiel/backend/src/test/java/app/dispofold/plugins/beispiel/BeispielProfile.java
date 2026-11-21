package app.dispofold.plugins.beispiel;

import java.nio.file.Path;
import java.util.Map;

import io.quarkus.test.junit.QuarkusTestProfile;

/** One allowed folder for plugins, below target; a sibling folder holds what must stay unreachable. */
public class BeispielProfile implements QuarkusTestProfile {

    static final Path ROOT = Path.of("target/plugin-files/erlaubt").toAbsolutePath();
    static final Path OUTSIDE = Path.of("target/plugin-files/geheim").toAbsolutePath();

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("dispofold.plugins.roots", ROOT.toString());
    }
}
