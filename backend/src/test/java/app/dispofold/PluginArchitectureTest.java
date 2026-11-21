package app.dispofold;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The rules of docs/plugins.md, on the sources: the core never uses a plugin, a plugin uses only the plugin API and
 * the data model of Dispofold, lives in its own package and has its routes below /api/plugins/&lt;id&gt;.
 */
class PluginArchitectureTest {

    static final Path CORE = Path.of("src/main/java");
    static final Path PLUGINS = Path.of("../plugins");
    static final Pattern IMPORT = Pattern.compile("^import\\s+(?:static\\s+)?([\\w.]+)", Pattern.MULTILINE);
    static final Pattern PATH = Pattern.compile("@Path\\(\"([^\"]*)\"\\)");
    static final Pattern DISPOFOLD = Pattern.compile("\\bapp\\.dispofold(?:\\.\\w+)+");
    static final List<String> ALLOWED = List.of("java.", "jakarta.", "com.fasterxml.jackson.",
            "org.eclipse.microprofile.config.", "app.dispofold.plugin.api.", "app.dispofold.model.");

    static List<Path> java(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    @Test
    void coreNeverUsesAPlugin() throws IOException {
        List<String> found = new ArrayList<>();
        for (Path f : java(CORE)) {
            if (Files.readString(f).contains("app.dispofold.plugins.")) {
                found.add(f.toString());
            }
        }
        assertThat(found).isEmpty();
    }

    @Test
    void pluginsUseOnlyTheApiTheModelAndThemselves() throws IOException {
        List<Path> dirs;
        try (Stream<Path> s = Files.list(PLUGINS)) {
            dirs = s.filter(Files::isDirectory).toList();
        }
        assertThat(dirs).isNotEmpty();
        List<String> found = new ArrayList<>();
        for (Path dir : dirs) {
            String id = dir.getFileName().toString();
            JsonNode manifest = new ObjectMapper().readTree(dir.resolve("plugin.json").toFile());
            if (!id.equals(manifest.path("id").asText())) {
                found.add(dir + ": plugin.json id differs from the folder");
            }
            String own = "app.dispofold.plugins." + id;
            for (Path f : java(dir.resolve("backend/src/main/java"))) {
                String src = Files.readString(f);
                if (!src.contains("package " + own + ";") && !src.contains("package " + own + ".")) {
                    found.add(f + ": package outside " + own);
                }
                Matcher m = IMPORT.matcher(src);
                while (m.find()) {
                    String imported = m.group(1);
                    if (ALLOWED.stream().noneMatch(imported::startsWith) && !imported.startsWith(own + ".")) {
                        found.add(f + ": imports " + imported);
                    }
                }
                // also fully qualified uses in the code, not only imports
                Matcher q = DISPOFOLD.matcher(src);
                while (q.find()) {
                    String used = q.group();
                    if (!used.startsWith("app.dispofold.plugin.api.") && !used.startsWith("app.dispofold.model.")
                            && !used.startsWith(own + ".") && !used.equals(own)) {
                        found.add(f + ": uses " + used);
                    }
                }
                Matcher p = PATH.matcher(src);
                if (src.contains("@Path(") && p.find() && !p.group(1).equals("/api/plugins/" + id)) {
                    found.add(f + ": class path " + p.group(1) + " is not /api/plugins/" + id);
                }
            }
        }
        assertThat(found).isEmpty();
    }
}
