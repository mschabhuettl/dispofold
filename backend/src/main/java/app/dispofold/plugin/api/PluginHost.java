package app.dispofold.plugin.api;

import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import app.dispofold.model.Dataset;

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

    /**
     * The master data as last imported (with the corrections its source applied) and, as periods, those of the given
     * months that Dispofold holds now, as it holds them; empty before the first import (the same state as
     * {@link #corrections()}).
     */
    Optional<Dataset> lastImport(List<YearMonth> months);

    /** Read access to the folders the operator or the user allowed. */
    PluginFiles files();
}
