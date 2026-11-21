package app.dispofold.plugin.api;

import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A plugin, as a CDI bean ({@code @ApplicationScoped}). Name, version and description come from its
 * {@code plugins/<id>/plugin.json}.
 */
public interface Plugin {

    /** Lower-case letters and digits, starting with a letter; the same as in plugin.json. */
    String id();

    /** The version of the form of the plugin's settings; raise it and migrate in {@link #migrateSettings}. */
    default int settingsVersion() {
        return 1;
    }

    /**
     * The settings stored in an older form, in the current form. Called when they are read, before the plugin sees
     * them; the result is stored with {@link #settingsVersion()}.
     *
     * @param fromVersion the version they were stored with (0: never stored, the settings are an empty object)
     */
    default JsonNode migrateSettings(int fromVersion, JsonNode settings) {
        return settings;
    }

    /** The plugin's import source, if it has one. */
    default Optional<ImportSource> importSource() {
        return Optional.empty();
    }
}
