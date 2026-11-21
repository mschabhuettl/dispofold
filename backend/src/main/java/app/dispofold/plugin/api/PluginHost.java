package app.dispofold.plugin.api;

import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** What Dispofold offers a plugin; inject it ({@code @Inject PluginHost host}). */
public interface PluginHost {

    /** The plugin's settings in the current form (migrated if needed); an empty object if none are stored. */
    JsonNode settings(String pluginId);

    /** Stores the plugin's settings with its current {@link Plugin#settingsVersion()}. */
    void saveSettings(String pluginId, JsonNode settings);

    /**
     * The corrections applied to the last import and the changes made in Dispofold since, as a corrections file
     * (docs/datenformat.md), the same as "Korrekturdatei" offers; empty if the state of the last import is not stored.
     */
    Optional<ObjectNode> corrections();

    /** Read access to the folders the operator or the user allowed. */
    PluginFiles files();
}
