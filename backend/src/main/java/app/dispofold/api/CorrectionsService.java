package app.dispofold.api;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response.Status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import app.dispofold.export.Corrections;
import app.dispofold.export.LoadedCorrections;
import app.dispofold.importclient.Issue;
import app.dispofold.model.Dataset;
import app.dispofold.store.Store;

/**
 * The corrections in effect, for the corrections file of the Daten page and for import sources
 * ({@code PluginHost#corrections}): those applied to the last import and the master data changed in Dispofold since,
 * or, while a loaded corrections file waits for an import, that file with the changes from Dispofold over it (see
 * {@link LoadedCorrections}).
 */
@ApplicationScoped
public class CorrectionsService {

    /**
     * @param file the corrections file
     * @param notes changes the file cannot express
     * @param count fields, exclusions and demand areas in the file
     * @param earlier of these, the ones from the corrections applied to the last import (or from the loaded file),
     *        unchanged since
     * @param withdrawn exclusions taken back since the last import, no longer in the file
     * @param complete the earlier corrections are known (listed by the last import, or a loaded file); false: only
     *        the changes since the last import
     * @param loaded the file is based on a loaded corrections file
     */
    public record Effective(ObjectNode file, List<Issue> notes, int count, int earlier, int withdrawn,
            boolean complete, boolean loaded) {
    }

    /**
     * @param loaded when the waiting corrections file was loaded; null if none waits
     * @param summary what it brings, against the current data; null if none waits
     * @param none there are no corrections at all: nothing loaded, and no import or nothing applied to it or changed
     *        since
     */
    public record State(Instant loaded, LoadedCorrections.Summary summary, boolean none) {
    }

    @Inject
    Store store;

    @Inject
    PlanService plans;

    @Inject
    ObjectMapper mapper;

    /** The corrections in effect; empty without an import and without a loaded file. */
    public Optional<Effective> effective() {
        synchronized (Store.DATASET_LOCK) {
            Optional<Dataset> base = store.baseline();
            Optional<Store.LoadedCorrections> loaded = store.loadedCorrections();
            if (loaded.isEmpty()) {
                return base.map(b -> {
                    Corrections.Export e = Corrections.export(b, plans.dataset(),
                            store.appliedCorrections().orElse(null), mapper);
                    return new Effective(e.file(), e.notes(), e.count(), e.earlier(), e.withdrawn(), e.complete(),
                            false);
                });
            }
            ObjectNode file = LoadedCorrections.parse(loaded.get().doc(), mapper);
            LoadedCorrections.Merged m = LoadedCorrections.merge(file, base.orElse(null),
                    base.isPresent() ? plans.dataset() : null, mapper);
            int count = LoadedCorrections.count(m.file());
            return Optional.of(new Effective(m.file(), m.notes(), count, Math.max(0, count - m.changed()),
                    m.withdrawn(), true, true));
        }
    }

    /** What a corrections file would bring, without saving it; refused as {@code corrections.load.*}. */
    public LoadedCorrections.Summary check(String body) {
        ObjectNode file = parsed(body);
        synchronized (Store.DATASET_LOCK) {
            return summary(file);
        }
    }

    /** Saves a corrections file as the loaded one, replacing the one loaded before. */
    public LoadedCorrections.Summary load(String body) {
        ObjectNode file = parsed(body);
        synchronized (Store.DATASET_LOCK) {
            store.saveLoadedCorrections(body);
            return summary(file);
        }
    }

    /** Removes the loaded corrections file. */
    public void remove() {
        synchronized (Store.DATASET_LOCK) {
            store.clearLoadedCorrections();
        }
    }

    public State state() {
        synchronized (Store.DATASET_LOCK) {
            Optional<Store.LoadedCorrections> loaded = store.loadedCorrections();
            if (loaded.isPresent()) {
                return new State(loaded.get().loaded(), summary(LoadedCorrections.parse(loaded.get().doc(), mapper)),
                        false);
            }
            return new State(null, null, effective().map(e -> e.count() == 0).orElse(true));
        }
    }

    private LoadedCorrections.Summary summary(ObjectNode file) {
        Optional<Dataset> current = store.dataset();
        Optional<Dataset> base = store.baseline();
        JsonNode applied = store.appliedCorrections().orElse(null);
        return LoadedCorrections.summary(file, current.orElse(null), current.isPresent() ? base.orElse(null) : null,
                applied, mapper);
    }

    /**
     * The uploaded bytes as text, read as the export program reads the file: UTF-8 without a byte order mark; other
     * bytes make the file unreadable.
     */
    public static String text(byte[] body) {
        boolean bom = body.length >= 3 && (body[0] & 0xFF) == 0xEF && (body[1] & 0xFF) == 0xBB
                && (body[2] & 0xFF) == 0xBF;
        try {
            if (!bom) {
                return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString();
            }
        } catch (CharacterCodingException e) {
            // refused below
        }
        throw ApiErrors.error(Status.BAD_REQUEST, "corrections.load.unreadable", "Die Datei ist kein JSON-Objekt");
    }

    private ObjectNode parsed(String body) {
        try {
            return LoadedCorrections.parse(body, mapper);
        } catch (LoadedCorrections.Rejected e) {
            throw ApiErrors.error(Status.BAD_REQUEST, e.code(), e.getMessage());
        }
    }
}
