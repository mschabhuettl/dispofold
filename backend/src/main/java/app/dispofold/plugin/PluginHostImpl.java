package app.dispofold.plugin;

import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import app.dispofold.api.CorrectionsService;
import app.dispofold.model.Dataset;
import app.dispofold.model.Period;
import app.dispofold.plugin.api.PluginFiles;
import app.dispofold.plugin.api.PluginHost;
import app.dispofold.store.Store;

@ApplicationScoped
public class PluginHostImpl implements PluginHost {

    @Inject
    Plugins plugins;

    @Inject
    FileRoots files;

    @Inject
    Store store;

    @Inject
    CorrectionsService corrections;

    @Override
    public JsonNode settings(String pluginId) {
        return plugins.settings(pluginId);
    }

    @Override
    public void saveSettings(String pluginId, JsonNode settings) {
        plugins.saveSettings(pluginId, settings);
    }

    /** The same as GET /api/data/corrections ({@code DataResource}). */
    @Override
    public Optional<ObjectNode> corrections() {
        return corrections.effective().map(CorrectionsService.Effective::file);
    }

    @Override
    public Optional<Dataset> lastImport(List<YearMonth> months) {
        synchronized (Store.DATASET_LOCK) {
            return store.baseline().map(b -> {
                List<Period> periods = months.stream().distinct().map(store::period).flatMap(Optional::stream)
                        .toList();
                return new Dataset(b.format(), b.version(), b.source(), b.areas(), b.shifts(), b.demand(),
                        b.holidays(), b.absenceTypes(), b.people(), b.restrictions(), b.history(), periods);
            });
        }
    }

    @Override
    public PluginFiles files() {
        return files;
    }
}
