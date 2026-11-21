package app.dispofold.plugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response.Status;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import app.dispofold.api.ApiErrors;
import app.dispofold.plugin.api.PluginFiles;
import app.dispofold.store.Store;

/**
 * The folders plugins may read: the operator's list ({@code dispofold.plugins.roots}) and, in the desktop program,
 * the folders the user picked. Every path is checked on its real location (symbolic links resolved), so nothing
 * outside a root is reachable.
 */
@ApplicationScoped
public class FileRoots implements PluginFiles {

    /** An entry of a folder. */
    public record Entry(String name, boolean dir) {
    }

    @Inject
    Store store;

    @ConfigProperty(name = "dispofold.plugins.roots")
    Optional<List<String>> configured;

    @Override
    public List<Root> roots() {
        Set<String> all = new LinkedHashSet<>();
        configured.orElse(List.of()).stream().map(String::trim).filter(s -> !s.isEmpty()).map(FileRoots::normal)
                .forEach(all::add);
        all.addAll(store.pickedRoots());
        return all.stream().filter(p -> Files.isDirectory(Path.of(p))).map(Root::new).toList();
    }

    /** An absolute path in normal form, the id of a root. */
    static String normal(String path) {
        return Path.of(path).toAbsolutePath().normalize().toString();
    }

    @Override
    public Path resolve(String root, String relative) {
        if (root == null || roots().stream().noneMatch(r -> r.id().equals(root))) {
            throw notFound();
        }
        try {
            Path base = Path.of(root).toRealPath();
            Path rel = Path.of(relative == null ? "" : relative);
            if (rel.isAbsolute() || rel.getRoot() != null) {
                throw notFound();
            }
            for (Path part : rel) {
                if (part.toString().equals("..")) {
                    throw notFound();
                }
            }
            Path real = base.resolve(rel).normalize().toRealPath();
            if (!real.startsWith(base)) {
                throw notFound();
            }
            return real;
        } catch (IOException | InvalidPathException e) {
            throw notFound();
        }
    }

    /** The folders and files in a folder of a root, folders first; entries leading outside the root are left out. */
    public List<Entry> list(String root, String relative) {
        Path dir = resolve(root, relative);
        if (!Files.isDirectory(dir)) {
            throw notFound();
        }
        Path base = resolve(root, "");
        List<Entry> entries = new ArrayList<>();
        try (Stream<Path> children = Files.list(dir)) {
            for (Path c : children.toList()) {
                try {
                    Path real = c.toRealPath();
                    if (real.startsWith(base)) {
                        entries.add(new Entry(c.getFileName().toString(), Files.isDirectory(real)));
                    }
                } catch (IOException e) {
                    // a broken link: not listed
                }
            }
        } catch (IOException e) {
            throw notFound();
        }
        entries.sort(Comparator.comparing((Entry e) -> !e.dir()).thenComparing(Entry::name));
        return entries;
    }

    /** Desktop program: a folder the user picked in the folder dialog. */
    public void addPicked(String path) {
        Path p;
        try {
            p = Path.of(path);
        } catch (InvalidPathException | NullPointerException e) {
            throw notFound();
        }
        if (!p.isAbsolute() || !Files.isDirectory(p)) {
            throw notFound();
        }
        store.addPickedRoot(normal(path));
    }

    static WebApplicationException notFound() {
        return ApiErrors.error(Status.NOT_FOUND, "files.notFound", "Ordner oder Datei nicht gefunden oder nicht freigegeben");
    }
}
