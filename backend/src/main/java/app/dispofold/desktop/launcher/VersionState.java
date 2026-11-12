package app.dispofold.desktop.launcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import app.dispofold.desktop.update.AtomicFiles;
import app.dispofold.desktop.update.Json;
import app.dispofold.desktop.update.Version;

/**
 * {@code versionen/state.json}: {@code current} and {@code previous} installed version (null: the bundled one),
 * {@code trial} a staged version on its first start, {@code backup} the database backup taken before the trial
 * (folder name below {@code daten/sicherungen/}, null when there was no database yet), {@code bad} versions that
 * failed their trial, {@code lastStarted} the last version that became ready (the database is at its level).
 */
final class VersionState {

    String current;
    String previous;
    String trial;
    String backup;
    String lastStarted;
    final List<String> bad = new ArrayList<>();
    /** The file existed but could not be read; it was moved aside and this state is empty. */
    boolean damaged;

    static VersionState read(Path file) throws IOException {
        VersionState s = new VersionState();
        if (!Files.exists(file)) {
            return s;
        }
        Map<String, Object> doc;
        try {
            doc = Json.parseObject(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException | java.nio.charset.CharacterCodingException e) {
            Files.move(file, file.resolveSibling("state.json.beschaedigt"), StandardCopyOption.REPLACE_EXISTING);
            s.damaged = true;
            return s;
        }
        s.current = version(doc.get("current"));
        s.previous = version(doc.get("previous"));
        s.trial = version(doc.get("trial"));
        s.lastStarted = version(doc.get("lastStarted"));
        s.backup = doc.get("backup") instanceof String b && b.matches("[A-Za-z0-9._-]+") ? b : null;
        if (doc.get("bad") instanceof List<?> list) {
            for (Object o : list) {
                String v = version(o);
                if (v != null) {
                    s.bad.add(v);
                }
            }
        }
        return s;
    }

    private static String version(Object o) {
        return o instanceof String v && Version.isValid(v) ? v : null;
    }

    void write(Path file) throws IOException {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("current", current);
        doc.put("previous", previous);
        doc.put("trial", trial);
        doc.put("backup", backup);
        doc.put("lastStarted", lastStarted);
        doc.put("bad", bad);
        AtomicFiles.write(file, Json.write(doc).getBytes(StandardCharsets.UTF_8), false);
    }
}
