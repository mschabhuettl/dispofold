package app.dispofold.plugins.sp5;

import java.nio.file.Path;
import java.util.Map;

import io.quarkus.test.junit.QuarkusTestProfile;

/** One allowed folder for the plugin, below target; a sibling folder holds what must stay unreachable. */
public class Sp5Profile implements QuarkusTestProfile {

    static final Path ROOT = Path.of("target/plugin-files/sp5-erlaubt").toAbsolutePath();
    static final Path OUTSIDE = Path.of("target/plugin-files/sp5-geheim").toAbsolutePath();

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("dispofold.plugins.roots", ROOT.toString());
    }
}
