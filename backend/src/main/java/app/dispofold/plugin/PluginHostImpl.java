package app.dispofold.plugin;

import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import app.dispofold.api.PlanService;
import app.dispofold.export.Corrections;
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
    PlanService plans;

    @Inject
    ObjectMapper mapper;

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
        Dataset base;
        Dataset current;
        JsonNode applied;
        synchronized (Store.DATASET_LOCK) {
            Optional<Dataset> baseline = store.baseline();
            if (baseline.isEmpty()) {
                return Optional.empty();
            }
            base = baseline.get();
            current = plans.dataset();
            applied = store.appliedCorrections().orElse(null);
        }
        return Optional.of(Corrections.export(base, current, applied, mapper).file());
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
