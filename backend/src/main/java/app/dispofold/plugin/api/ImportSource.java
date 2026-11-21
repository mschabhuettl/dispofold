package app.dispofold.plugin.api;

import com.fasterxml.jackson.databind.JsonNode;

import app.dispofold.model.Dataset;

/**
 * Makes a dataset for the import of Dispofold. The core runs it through the same path as an uploaded file:
 * preview, keeping the changes made in Dispofold ({@code keep}), conflicts, the baseline and the corrections the
 * source applied. Preview and import each call {@link #load}: for the same request and the same files it must give
 * the same dataset.
 */
public interface ImportSource {

    /**
     * @param dataset the dataset in the documented format (docs/datenformat.md)
     * @param appliedCorrections the corrections the source applied, as a corrections file (see
     *        {@link PluginHost#corrections()}); null if none
     */
    record Data(Dataset dataset, JsonNode appliedCorrections) {
    }

    /**
     * @param request what the plugin's import form sent (its own JSON)
     * @throws jakarta.ws.rs.WebApplicationException made with {@link PluginErrors} when the request cannot be loaded
     */
    Data load(JsonNode request);
}
