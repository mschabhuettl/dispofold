package app.dispofold.desktop.update;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Builds and signs update bundles (used by the release scripts through {@link SignTool}). */
public final class BundleMaker {

    private static final FileTime FIXED_TIME = FileTime.fromMillis(1767225600000L); // 2026-01-01T00:00:00Z

    private BundleMaker() {
    }

    /** Reads a private key file: Base64 of the PKCS#8 DER encoding. */
    public static PrivateKey readPrivateKey(Path keyFile) throws IOException {
        try {
            byte[] der = Base64.getMimeDecoder().decode(Files.readString(keyFile, StandardCharsets.US_ASCII).trim());
            return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IOException("Not an Ed25519 private key (Base64 PKCS#8): " + keyFile.getFileName());
        }
    }

    public static String sign(PrivateKey key, byte[] data) {
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initSign(key);
            s.update(data);
            return Base64.getEncoder().encodeToString(s.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** bundle.json for the files of a Quarkus app folder, paths sorted. */
    public static byte[] manifest(Path appDir, Version version) throws IOException {
        Map<String, Object> files = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(appDir)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (a.isDirectory()) {
                    continue;
                }
                String rel = appDir.relativize(p).toString().replace('\\', '/');
                if (!a.isRegularFile() || !BundleVerifier.safeRelativePath(rel)) {
                    throw new IOException("Unsupported file in app folder: " + rel);
                }
                files.put(rel, BundleVerifier.sha256(p));
            }
        }
        if (files.isEmpty()) {
            throw new IOException("App folder is empty: " + appDir);
        }
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("version", version.toString());
        doc.put("files", files);
        return Json.write(doc).getBytes(StandardCharsets.UTF_8);
    }

    /** Writes {@code outDir/dispofold-<version>-update.zip} and returns its path. */
    public static Path make(Path appDir, Version version, PrivateKey key, Path outDir) throws IOException {
        byte[] json = manifest(appDir, version);
        String sig = sign(key, json);
        Files.createDirectories(outDir);
        Path zip = outDir.resolve("dispofold-" + version + "-update.zip");
        Map<String, String> files = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(appDir)) {
            walk.filter(Files::isRegularFile)
                    .forEach(p -> files.put(appDir.relativize(p).toString().replace('\\', '/'), p.toString()));
        }
        try (OutputStream os = Files.newOutputStream(zip); ZipOutputStream out = new ZipOutputStream(os)) {
            put(out, BundleVerifier.BUNDLE_JSON, json);
            put(out, BundleVerifier.SIGNATURE, sig.getBytes(StandardCharsets.US_ASCII));
            for (Map.Entry<String, String> f : files.entrySet()) {
                put(out, BundleVerifier.APP + "/" + f.getKey(), Files.readAllBytes(Path.of(f.getValue())));
            }
        }
        return zip;
    }

    private static void put(ZipOutputStream out, String name, byte[] data) throws IOException {
        ZipEntry e = new ZipEntry(name);
        e.setLastModifiedTime(FIXED_TIME);
        out.putNextEntry(e);
        out.write(data);
        out.closeEntry();
    }
}
