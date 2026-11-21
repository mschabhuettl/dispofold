package app.dispofold.plugin.api;

import java.nio.file.Path;
import java.util.List;

/**
 * Read access to allowed folders: on the server the ones the operator lists ({@code DISPOFOLD_PLUGIN_ROOTS}, none by
 * default), in the desktop program the ones the user picked in the folder dialog. Nothing outside them is reachable:
 * no absolute paths, no {@code ..} and no symbolic links that lead outside.
 */
public interface PluginFiles {

    /** An allowed folder; its id is its absolute path. */
    record Root(String id) {
    }

    List<Root> roots();

    /**
     * The real path of {@code relative} in the root {@code root}, which must exist.
     *
     * @throws jakarta.ws.rs.WebApplicationException 404 {@code files.notFound} if the root is not allowed, the path
     *         leaves it or does not exist
     */
    Path resolve(String root, String relative);
}
