package app.dispofold.desktop.update;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * Checks update bundles and installed version folders.
 * <p>
 * A bundle ({@code dispofold-<version>-update.zip}) holds {@code bundle.json} ({@code {"version": "x.y.z",
 * "files": {"<path under app/>": "<sha256 hex>"}}}), {@code bundle.json.sig} (Base64 Ed25519 signature over the
 * exact bytes of bundle.json) and the folder {@code app/}. Unpacked, the same three entries form a version folder.
 * A folder is valid when the signature matches, every file under {@code app/} has its listed hash, no file is
 * missing or extra, and nothing is a link or a special file.
 */
public final class BundleVerifier {

    /**
     * Ed25519 public key of the release signing key (X.509 SubjectPublicKeyInfo, Base64), set at build time from
     * the Maven property {@code dispofold.update.public-key}.
     */
    public static final String PUBLIC_KEY = compiledInKey();

    public static final String BUNDLE_JSON = "bundle.json";
    public static final String SIGNATURE = "bundle.json.sig";
    public static final String APP = "app";

    static final int MAX_ENTRIES = 20_000;
    static final long MAX_TOTAL_BYTES = 1L << 30;
    static final long MAX_JSON_BYTES = 8L << 20;

    private final PublicKey key;
    private final long maxTotalBytes;

    /** Verifier with the compiled in release key. */
    public BundleVerifier() {
        this(publicKey(PUBLIC_KEY));
    }

    public BundleVerifier(PublicKey key) {
        this(key, MAX_TOTAL_BYTES);
    }

    BundleVerifier(PublicKey key, long maxTotalBytes) {
        this.key = key;
        this.maxTotalBytes = maxTotalBytes;
    }

    private static String compiledInKey() {
        try (InputStream in = BundleVerifier.class.getResourceAsStream("update-public-key.txt")) {
            if (in == null) {
                throw new IllegalStateException("update-public-key.txt is missing from the build");
            }
            return new String(in.readAllBytes(), StandardCharsets.US_ASCII).trim();
        } catch (IOException e) {
            throw new IllegalStateException("update-public-key.txt is unreadable", e);
        }
    }

    public static PublicKey publicKey(String base64) {
        try {
            return KeyFactory.getInstance("Ed25519")
                    .generatePublic(new X509EncodedKeySpec(Base64.getMimeDecoder().decode(base64.trim())));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Invalid Ed25519 public key", e);
        }
    }

    /** Version and file list of a checked bundle.json. */
    public record Manifest(Version version, Map<String, String> files) {
    }

    /**
     * Checks a bundle without unpacking it: signature and manifest first, then every listed file is hashed while it
     * is read from the archive. {@code workDir} is not used any more and kept for the callers.
     */
    public Version verifyZip(Path zip, Path workDir, Version current) throws BundleException {
        return read(zip, null, current);
    }

    /**
     * Checks a bundle and unpacks it to {@code versionsDir/<version>}: signature and manifest first, then only the
     * listed files are extracted while they are hashed. An existing folder of that version is replaced. Nothing is
     * left behind on failure.
     */
    public Version unpack(Path zip, Path versionsDir, Version current) throws BundleException {
        Path tmp = versionsDir.resolve(".entpacken-" + UUID.randomUUID());
        try {
            Version v = read(zip, tmp, current);
            // Once more on disk: catches names that collide on this file system, links and special files.
            if (!verifyDir(tmp).version().equals(v)) {
                throw new BundleException("unpackFailed", "Das Update konnte nicht entpackt werden.");
            }
            Path target = versionsDir.resolve(v.toString());
            deleteTree(target);
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            return v;
        } catch (IOException e) {
            throw new BundleException("unpackFailed", "Das Update konnte nicht entpackt werden.", e);
        } finally {
            deleteQuietly(tmp);
        }
    }

    private static void requireNewer(Version v, Version current) throws BundleException {
        if (current != null && !v.isNewerThan(current)) {
            throw new BundleException("notNewer", Map.of("version", v.toString(), "current", current.toString()),
                    "Das Update (" + v + ") ist nicht neuer als die laufende Version (" + current
                    + ").");
        }
    }

    /** Checks an unpacked bundle or version folder. */
    public Manifest verifyDir(Path dir) throws BundleException {
        try {
            if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
                throw new BundleException("folderMissing", "Der Versionsordner fehlt.");
            }
            try (DirectoryStream<Path> top = Files.newDirectoryStream(dir)) {
                for (Path p : top) {
                    String name = p.getFileName().toString();
                    if (!Set.of(BUNDLE_JSON, SIGNATURE, APP).contains(name)) {
                        throw new BundleException("unexpectedFile", "Das Update enthält eine unerwartete Datei.");
                    }
                }
            }
            Path json = dir.resolve(BUNDLE_JSON);
            Path sig = dir.resolve(SIGNATURE);
            if (!Files.isRegularFile(json, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isRegularFile(sig, LinkOption.NOFOLLOW_LINKS)) {
                throw new BundleException("unsigned", "Das Update ist nicht signiert (bundle.json oder bundle.json.sig fehlt).");
            }
            if (Files.size(json) > MAX_JSON_BYTES || Files.size(sig) > 4096) {
                throw new BundleException("manifestTooLarge", "bundle.json ist zu groß.");
            }
            byte[] jsonBytes = Files.readAllBytes(json);
            if (!signatureValid(jsonBytes, Files.readString(sig, StandardCharsets.US_ASCII))) {
                throw new BundleException("signature", "Die Signatur des Updates ist ungültig.");
            }
            Manifest manifest = manifest(jsonBytes);
            checkFiles(dir.resolve(APP), manifest.files());
            return manifest;
        } catch (IOException e) {
            throw new BundleException("readFailed", "Das Update konnte nicht gelesen werden.", e);
        }
    }

    boolean signatureValid(byte[] data, String base64Signature) {
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initVerify(key);
            s.update(data);
            return s.verify(Base64.getMimeDecoder().decode(base64Signature.trim()));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    static Manifest manifest(byte[] jsonBytes) throws BundleException {
        Map<String, Object> doc;
        try {
            doc = Json.parseObject(new String(jsonBytes, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            throw new BundleException("manifestInvalid", "bundle.json ist kein gültiges JSON.");
        }
        Object version = doc.get("version");
        if (!(version instanceof String vs) || !Version.isValid(vs)) {
            throw new BundleException("manifestInvalid", "bundle.json enthält keine gültige Version.");
        }
        if (!(doc.get("files") instanceof Map<?, ?> files) || files.isEmpty()) {
            throw new BundleException("manifestInvalid", "bundle.json enthält keine Dateiliste.");
        }
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<?, ?> e : files.entrySet()) {
            String path = (String) e.getKey();
            if (!safeRelativePath(path) || !(e.getValue() instanceof String hash) || !hash.matches("[0-9a-f]{64}")) {
                throw new BundleException("manifestInvalid", "bundle.json enthält einen ungültigen Eintrag.");
            }
            result.put(path, hash);
        }
        return new Manifest(Version.parse(vs), result);
    }

    private static void checkFiles(Path app, Map<String, String> expected) throws IOException, BundleException {
        if (!Files.isDirectory(app, LinkOption.NOFOLLOW_LINKS)) {
            throw new BundleException("appMissing", "Das Update enthält keinen Ordner app/.");
        }
        Map<String, String> found = new HashMap<>();
        try (Stream<Path> walk = Files.walk(app)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (a.isDirectory()) {
                    continue;
                }
                String rel = app.relativize(p).toString().replace('\\', '/');
                if (!a.isRegularFile()) {
                    throw new BundleException("link", "Das Update enthält eine Verknüpfung oder Sonderdatei.");
                }
                String hash = expected.get(rel);
                if (hash == null) {
                    throw new BundleException("unsignedFile", "Das Update enthält eine nicht signierte Datei.");
                }
                if (!hash.equals(sha256(p))) {
                    throw new BundleException("changedFile", "Eine Datei des Updates wurde verändert.");
                }
                found.put(rel, hash);
            }
        }
        for (String rel : expected.keySet()) {
            if (!found.containsKey(rel)) {
                throw new BundleException("missingFile", "Im Update fehlt eine Datei.");
            }
        }
    }

    /** Relative path with forward slashes, no empty, "." or ".." segments, no drive letters or backslashes. */
    static boolean safeRelativePath(String path) {
        if (path == null || path.isEmpty() || path.length() > 512 || path.startsWith("/") || path.contains("\\")
                || path.contains(":") || path.chars().anyMatch(c -> c < 0x20)) {
            return false;
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                return false;
            }
        }
        return true;
    }

    /**
     * Checks a bundle in the archive and, with a {@code target}, extracts it into that new folder.
     * <ol>
     * <li>All entry names: limits on count, safe relative paths, only bundle.json, bundle.json.sig and app/**, no
     * name twice.</li>
     * <li>bundle.json and its signature, read with a size limit: signature valid, manifest valid, version newer than
     * {@code current}. Nothing has been extracted up to here.</li>
     * <li>Every file under app/ must be listed; it is hashed (and written to {@code target}) while it is read, with a
     * limit on the bytes actually read. A listed file that is missing fails the check.</li>
     * </ol>
     */
    Version read(Path zip, Path target, Version current) throws BundleException {
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            Map<String, ZipEntry> files = new HashMap<>();
            ZipEntry json = null;
            ZipEntry sig = null;
            Set<String> names = new HashSet<>();
            int count = 0;
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (++count > MAX_ENTRIES) {
                    throw new BundleException("tooManyFiles", "Das Update enthält zu viele Dateien.");
                }
                String name = e.getName();
                boolean dir = name.endsWith("/");
                String clean = dir ? name.substring(0, name.length() - 1) : name;
                if (!safeRelativePath(clean)) {
                    throw new BundleException("unsafePath", "Das Update enthält einen unzulässigen Pfad.");
                }
                if (!names.add(clean.toLowerCase(Locale.ROOT))) {
                    throw new BundleException("duplicate", "Das Update enthält eine Datei doppelt.");
                }
                if (dir) {
                    if (!clean.equals(APP) && !clean.startsWith(APP + "/")) {
                        throw new BundleException("unexpectedFile", "Das Update enthält eine unerwartete Datei.");
                    }
                } else if (clean.equals(BUNDLE_JSON)) {
                    json = e;
                } else if (clean.equals(SIGNATURE)) {
                    sig = e;
                } else if (clean.startsWith(APP + "/")) {
                    files.put(clean.substring(APP.length() + 1), e);
                } else {
                    throw new BundleException("unexpectedFile", "Das Update enthält eine unerwartete Datei.");
                }
            }
            if (json == null || sig == null) {
                throw new BundleException("unsigned",
                        "Das Update ist nicht signiert (bundle.json oder bundle.json.sig fehlt).");
            }
            byte[] jsonBytes = readLimited(zf, json, MAX_JSON_BYTES);
            byte[] sigBytes = readLimited(zf, sig, 4096);
            if (!signatureValid(jsonBytes, new String(sigBytes, StandardCharsets.US_ASCII))) {
                throw new BundleException("signature", "Die Signatur des Updates ist ungültig.");
            }
            Manifest manifest = manifest(jsonBytes);
            requireNewer(manifest.version(), current);
            for (String rel : files.keySet()) {
                if (!manifest.files().containsKey(rel)) {
                    throw new BundleException("unsignedFile", "Das Update enthält eine nicht signierte Datei.");
                }
            }
            for (String rel : manifest.files().keySet()) {
                if (!files.containsKey(rel)) {
                    throw new BundleException("missingFile", "Im Update fehlt eine Datei.");
                }
            }
            Path root = null;
            if (target != null) {
                Files.createDirectories(target.getParent());
                Files.createDirectory(target);
                root = target.toRealPath();
                Files.write(root.resolve(BUNDLE_JSON), jsonBytes, StandardOpenOption.CREATE_NEW);
                Files.write(root.resolve(SIGNATURE), sigBytes, StandardOpenOption.CREATE_NEW);
                Files.createDirectory(root.resolve(APP));
            }
            long total = jsonBytes.length + sigBytes.length;
            for (Map.Entry<String, String> f : manifest.files().entrySet()) {
                total += copyHashed(zf, files.get(f.getKey()), root == null ? null : root.resolve(APP), f.getKey(),
                        f.getValue(), maxTotalBytes - total);
            }
            return manifest.version();
        } catch (ZipException e) {
            throw new BundleException("notZip", "Die Update-Datei ist kein gültiges ZIP-Archiv.", e);
        } catch (IOException e) {
            throw new BundleException("readFailed", "Die Update-Datei konnte nicht gelesen werden.", e);
        }
    }

    private static byte[] readLimited(ZipFile zf, ZipEntry e, long limit) throws IOException, BundleException {
        try (InputStream in = zf.getInputStream(e)) {
            byte[] data = in.readNBytes((int) limit + 1);
            if (data.length > limit) {
                throw new BundleException("manifestTooLarge", "bundle.json ist zu groß.");
            }
            return data;
        }
    }

    /** Hashes one entry (and writes it below {@code app} if given); returns the bytes read. */
    private static long copyHashed(ZipFile zf, ZipEntry e, Path app, String rel, String expected, long budget)
            throws IOException, BundleException {
        MessageDigest md = sha256();
        Path out = null;
        if (app != null) {
            out = app.resolve(rel).normalize();
            if (!out.startsWith(app) || out.equals(app)) {
                throw new BundleException("unsafePath", "Das Update enthält einen unzulässigen Pfad.");
            }
            Files.createDirectories(out.getParent());
        }
        long read = 0;
        try (InputStream in = zf.getInputStream(e);
                OutputStream os = out == null ? OutputStream.nullOutputStream()
                        : Files.newOutputStream(out, StandardOpenOption.CREATE_NEW)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                read += n;
                if (read > budget) {
                    throw new BundleException("tooLarge", "Das Update ist zu groß.");
                }
                md.update(buf, 0, n);
                os.write(buf, 0, n);
            }
        } catch (java.nio.file.FileAlreadyExistsException ex) {
            throw new BundleException("duplicate", "Das Update enthält eine Datei doppelt.");
        }
        if (!HexFormat.of().formatHex(md.digest()).equals(expected)) {
            throw new BundleException("changedFile", "Eine Datei des Updates wurde verändert.");
        }
        return read;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sha256(Path file) throws IOException {
        MessageDigest md = sha256();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        }
        return HexFormat.of().formatHex(md.digest());
    }

    /** Deletes a folder tree without following links. */
    public static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) {
                Files.delete(p);
            }
        }
    }

    static void deleteQuietly(Path dir) {
        try {
            deleteTree(dir);
        } catch (IOException ignored) {
            // A leftover temporary folder does no harm; it is never started.
        }
    }
}
