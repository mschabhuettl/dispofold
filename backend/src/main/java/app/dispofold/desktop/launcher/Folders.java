package app.dispofold.desktop.launcher;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Where the launcher keeps things.
 *
 * @param data     {@code daten/}: database, settings, backups, logs, lock
 * @param versions {@code versionen/}: installed updates, {@code eingang/}, state.json
 * @param bundled  the app version that came with the program (bundle layout: bundle.json and app/)
 */
record Folders(Path data, Path versions, Path bundled) {

    static final String PORTABLE_MARKER = "PORTABLE";
    static final String TRANSLOCATED = "macOS hat Dispofold aus Sicherheitsgründen in einen versteckten,"
            + " schreibgeschützten Ordner kopiert (App Translocation), weil das Programm aus dem Internet stammt."
            + " So fände Dispofold seine Daten nicht. Bitte Dispofold beenden, Dispofold.app im Finder in den Ordner"
            + " \"Programme\" ziehen und von dort neu starten. Alternativ im Terminal:"
            + " xattr -dr com.apple.quarantine /Applications/Dispofold.app";

    /** File system types of network shares (Linux {@code /proc/mounts}, macOS {@code statfs}). */
    static final Set<String> NETWORK_TYPES = Set.of("cifs", "smb", "smb2", "smb3", "smbfs", "nfs", "nfs4", "nfsd",
            "fuse.sshfs", "sshfs", "9p", "afpfs", "webdav", "davfs", "fuse.davfs2");

    static Folders of(Path base, Path bundled) {
        return new Folders(base.resolve("daten"), base.resolve("versionen"), bundled);
    }

    /** What decides whether a folder is on a network share; a seam for tests. */
    interface StoreKind {
        /** The file system type of the store holding {@code existing}, or "remote" for a Windows network drive. */
        String of(Path existing) throws IOException;
    }

    /** The real lookup: FileStore.type(), on Windows the drive type of the volume. */
    static final StoreKind SYSTEM = existing -> {
        FileStore store = Files.getFileStore(existing);
        return windowsRemote(store) ? "remote" : store.type();
    };

    /**
     * A9: the data folder is on a network path: a UNC path ({@code \\server\share}), a Windows network drive, or a
     * network file system type. A folder that does not exist yet is judged by its nearest existing parent.
     */
    static boolean onNetwork(Path dir, StoreKind kind) {
        if (unc(dir.toString())) {
            return true;
        }
        Path p = dir.toAbsolutePath().normalize();
        while (p != null && !Files.exists(p)) {
            p = p.getParent();
        }
        if (p == null) {
            return false;
        }
        try {
            String type = kind.of(p);
            return type != null && (type.equals("remote") || NETWORK_TYPES.contains(type.toLowerCase(Locale.ROOT)));
        } catch (IOException e) {
            return false;
        }
    }

    /** A Windows UNC path (two backslashes, server, share; also the long form with ?\UNC\). */
    static boolean unc(String path) {
        // Not the local device namespaces \\?\C:\ and \\.\C:\
        return path.startsWith("\\\\") && !path.startsWith("\\\\?\\") && !path.startsWith("\\\\.\\")
                || path.startsWith("\\\\?\\UNC\\");
    }

    /**
     * Windows only: the volume's drive type is DRIVE_REMOTE (4). The JDK keeps it in a private field of
     * sun.nio.fs.WindowsFileStore; dispofold-launcher.jar opens that package (manifest Add-Opens). Anything that
     * does not work counts as "not remote", so the check never stops a start.
     */
    static boolean windowsRemote(FileStore store) {
        if (!store.getClass().getName().equals("sun.nio.fs.WindowsFileStore")) {
            return false;
        }
        try {
            Field f = store.getClass().getDeclaredField("volType");
            f.setAccessible(true);
            return f.getInt(store) == 4;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    /** Name of this computer, stored in instanz.json so that other computers on a shared folder can tell. */
    static String hostName() {
        String name = System.getenv("COMPUTERNAME");
        if (name == null || name.isBlank()) {
            try {
                name = InetAddress.getLocalHost().getHostName();
            } catch (UnknownHostException e) {
                name = System.getenv("HOSTNAME");
            }
        }
        return name == null || name.isBlank() ? "unbekannt" : name;
    }

    /**
     * The data folder becomes part of the database URL: {@code ;} would start H2 settings and {@code ${} a config
     * expression. Returns the German message if the path contains either, else null.
     */
    static String unsafePath(Path data) {
        String p = data.toAbsolutePath().toString();
        if (p.contains(";") || p.contains("${")) {
            return "Der Datenordner darf weder \";\" noch \"${\" enthalten. Bitte Dispofold in einen anderen Ordner"
                    + " legen oder --data verwenden.";
        }
        return null;
    }

    Path db() {
        return data.resolve("db");
    }

    Path incoming() {
        return versions.resolve("eingang");
    }

    Path stateFile() {
        return versions.resolve("state.json");
    }

    Path instanceFile() {
        return data.resolve("instanz.json");
    }

    /**
     * macOS App Translocation: an app from a quarantined download that was not moved by the Finder runs from a
     * random read-only copy below {@code /private/var/folders/.../AppTranslocation/}, without the folder around it.
     */
    static boolean translocated(Path programDir) {
        return programDir.toAbsolutePath().toString().replace('\\', '/').contains("/AppTranslocation/");
    }

    /** Base folder: {@code PORTABLE} marker in the program folder, else the user data folder of the system. */
    static Path base(Path programDir, Map<String, String> env, String osName, String home) {
        if (Files.isRegularFile(programDir.resolve(PORTABLE_MARKER))) {
            return programDir;
        }
        String os = osName.toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            String local = env.get("LOCALAPPDATA");
            return local != null && !local.isBlank() ? Path.of(local, "Dispofold")
                    : Path.of(home, "AppData", "Local", "Dispofold");
        }
        if (os.contains("mac")) {
            return Path.of(home, "Library", "Application Support", "Dispofold");
        }
        String xdg = env.get("XDG_DATA_HOME");
        return xdg != null && !xdg.isBlank() ? Path.of(xdg, "dispofold") : Path.of(home, ".local", "share", "dispofold");
    }
}
