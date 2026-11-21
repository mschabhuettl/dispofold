package app.dispofold.plugin;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import app.dispofold.plugin.api.Plugin;
import app.dispofold.store.Store;
import app.dispofold.store.Store.PluginRow;

/**
 * The plugins built into this program (docs/plugins.md): their plugin.json, whether a planner switched them on, and
 * their settings. A plugin the operator hides ({@code DISPOFOLD_PLUGINS_HIDDEN}) does not exist for the program.
 */
@ApplicationScoped
public class Plugins {

    static final Pattern ID = Pattern.compile("[a-z][a-z0-9]{1,30}");

    /** plugin.json, copied by the build to {@code META-INF/dispofold-plugins/<id>/plugin.json}. */
    public record Manifest(String id, String name, String version, String description, boolean testOnly) {
    }

    /** A plugin as the settings page lists it. */
    public record Info(String id, String name, String version, String description, boolean enabled,
            boolean importSource) {
    }

    private final Map<String, Plugin> plugins = new LinkedHashMap<>();
    private final Map<String, Manifest> manifests = new LinkedHashMap<>();
    private final Store store;
    private final ObjectMapper mapper;

    @Inject
    public Plugins(Instance<Plugin> all, @ConfigProperty(name = "dispofold.plugins.hidden") Optional<List<String>> hidden,
            Store store, ObjectMapper mapper) {
        this.store = store;
        this.mapper = mapper;
        Set<String> hiddenIds = Set.copyOf(hidden.orElse(List.of()).stream().map(String::trim).toList());
        List<Plugin> sorted = new ArrayList<>(all.stream().toList());
        sorted.sort(Comparator.comparing(Plugin::id));
        for (Plugin p : sorted) {
            Manifest m = manifest(p.id());
            if (plugins.containsKey(p.id())) {
                throw new IllegalStateException("two plugins with the id " + p.id());
            }
            if (!hiddenIds.contains(p.id())) {
                plugins.put(p.id(), p);
                manifests.put(p.id(), m);
            }
        }
    }

    private Manifest manifest(String id) {
        if (!ID.matcher(id).matches()) {
            throw new IllegalStateException("plugin id must match " + ID + ": " + id);
        }
        String path = "META-INF/dispofold-plugins/" + id + "/plugin.json";
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("missing " + path);
            }
            Manifest m = mapper.readValue(in, Manifest.class);
            if (!id.equals(m.id()) || blank(m.name()) || blank(m.version()) || blank(m.description())) {
                throw new IllegalStateException(path + " needs the id " + id + ", a name, version and description");
            }
            return m;
        } catch (IOException e) {
            throw new IllegalStateException("unreadable " + path);
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /** All plugins not hidden by the operator, by id. */
    public List<Info> list() {
        return plugins.values().stream().map(p -> {
            Manifest m = manifests.get(p.id());
            return new Info(p.id(), m.name(), m.version(), m.description(), enabled(p.id()),
                    p.importSource().isPresent());
        }).toList();
    }

    /** The plugin if it exists, is not hidden and is switched on. */
    public Optional<Plugin> active(String id) {
        Plugin p = plugins.get(id);
        return p != null && enabled(id) ? Optional.of(p) : Optional.empty();
    }

    public boolean exists(String id) {
        return plugins.containsKey(id);
    }

    private boolean enabled(String id) {
        return store.plugin(id).map(PluginRow::enabled).orElse(false);
    }

    /** Switches a plugin on or off; its settings stay. */
    public void setEnabled(String id, boolean enabled) {
        store.setPluginEnabled(id, enabled);
    }

    /** The plugin's settings in the current form; older forms are migrated by the plugin and stored. */
    public synchronized JsonNode settings(String id) {
        Plugin p = known(id);
        Optional<PluginRow> row = store.plugin(id);
        int version = row.map(PluginRow::settingsVersion).orElse(0);
        JsonNode stored = row.map(r -> read(r.settings())).orElseGet(mapper::createObjectNode);
        if (version >= p.settingsVersion()) {
            return stored;
        }
        JsonNode migrated = p.migrateSettings(version, stored);
        store.savePluginSettings(id, p.settingsVersion(), write(migrated));
        return migrated;
    }

    public synchronized void saveSettings(String id, JsonNode settings) {
        store.savePluginSettings(id, known(id).settingsVersion(), write(settings));
    }

    private Plugin known(String id) {
        Plugin p = plugins.get(id);
        if (p == null) {
            throw new IllegalArgumentException("unknown plugin " + id);
        }
        return p;
    }

    private JsonNode read(String json) {
        try {
            return mapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored plugin settings are unreadable");
        }
    }

    private String write(JsonNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialise plugin settings");
        }
    }
}
