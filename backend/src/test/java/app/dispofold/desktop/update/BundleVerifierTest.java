package app.dispofold.desktop.update;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

public class BundleVerifierTest {

    @TempDir
    Path tmp;

    KeyPair keys;
    BundleVerifier verifier;
    Path app;

    @BeforeEach
    void setUp() throws IOException {
        keys = SignTool.keyPair();
        verifier = new BundleVerifier(keys.getPublic());
        app = fakeApp(tmp.resolve("quarkus-app"));
    }

    /** A small stand-in for a Quarkus fast-jar folder. */
    public static Path fakeApp(Path dir) throws IOException {
        Files.createDirectories(dir.resolve("lib/main"));
        Files.writeString(dir.resolve("quarkus-run.jar"), "runner");
        Files.writeString(dir.resolve("lib/main/a.jar"), "library a");
        Files.writeString(dir.resolve("lib/main/b.jar"), "library b");
        return dir;
    }

    Path bundle(String version) throws IOException {
        return BundleMaker.make(app, Version.parse(version), keys.getPrivate(), tmp.resolve("out"));
    }

    /** Copies a zip, replacing (non-null value) or removing (null value) entries and adding new ones. */
    static Path rewrite(Path zip, Map<String, byte[]> changes) throws IOException {
        Path out = zip.resolveSibling("changed-" + zip.getFileName());
        Map<String, byte[]> todo = new LinkedHashMap<>(changes);
        try (ZipFile in = new ZipFile(zip.toFile()); OutputStream os = Files.newOutputStream(out);
                ZipOutputStream zos = new ZipOutputStream(os)) {
            Enumeration<? extends ZipEntry> en = in.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                byte[] data = in.getInputStream(e).readAllBytes();
                if (todo.containsKey(e.getName())) {
                    data = todo.remove(e.getName());
                    if (data == null) {
                        continue;
                    }
                }
                zos.putNextEntry(new ZipEntry(e.getName()));
                zos.write(data);
                zos.closeEntry();
            }
            for (Map.Entry<String, byte[]> e : todo.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue());
                zos.closeEntry();
            }
        }
        return out;
    }

    static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void goodBundleUnpacksToVersionFolder() throws Exception {
        Path versions = tmp.resolve("versionen");
        Version v = verifier.unpack(bundle("1.2.0"), versions, Version.parse("1.1.9"));
        assertThat(v).isEqualTo(Version.parse("1.2.0"));
        assertThat(versions.resolve("1.2.0/app/lib/main/a.jar")).hasContent("library a");
        assertThat(verifier.verifyDir(versions.resolve("1.2.0")).version()).isEqualTo(v);
        try (var left = Files.list(versions)) {
            assertThat(left).containsExactly(versions.resolve("1.2.0"));
        }
    }

    @Test
    void verifyZipLeavesNothingBehind() throws Exception {
        Path work = Files.createDirectories(tmp.resolve("work"));
        assertThat(verifier.verifyZip(bundle("2.0.0"), work, null)).isEqualTo(Version.parse("2.0.0"));
        try (var left = Files.list(work)) {
            assertThat(left).isEmpty();
        }
    }

    @Test
    void wrongSignatureIsRefused() throws Exception {
        BundleVerifier other = new BundleVerifier(SignTool.keyPair().getPublic());
        assertThatThrownBy(() -> other.verifyZip(bundle("1.0.0"), tmp, null))
                .isInstanceOf(BundleException.class).hasMessageContaining("Signatur");
        // bundle.json changed after signing
        Path zip = bundle("1.0.0");
        String json = new String(new ZipFile(zip.toFile()).getInputStream(new ZipEntry("bundle.json")).readAllBytes(),
                StandardCharsets.UTF_8);
        Path changed = rewrite(zip, Map.of("bundle.json", bytes(json.replace("1.0.0", "9.0.0"))));
        assertThatThrownBy(() -> verifier.verifyZip(changed, tmp, null)).hasMessageContaining("Signatur");
    }

    @Test
    void unsignedBundleIsRefused() throws Exception {
        Map<String, byte[]> drop = new LinkedHashMap<>();
        drop.put("bundle.json.sig", null);
        Path zip = rewrite(bundle("1.0.0"), drop);
        assertThatThrownBy(() -> verifier.verifyZip(zip, tmp, null)).hasMessageContaining("nicht signiert");
    }

    @Test
    void changedFileIsRefused() throws Exception {
        Path zip = rewrite(bundle("1.0.0"), Map.of("app/lib/main/a.jar", bytes("evil")));
        assertThatThrownBy(() -> verifier.verifyZip(zip, tmp, null)).hasMessageContaining("verändert");
    }

    @Test
    void extraFileIsRefused() throws Exception {
        Path inApp = rewrite(bundle("1.0.0"), Map.of("app/lib/main/extra.jar", bytes("x")));
        assertThatThrownBy(() -> verifier.verifyZip(inApp, tmp, null)).hasMessageContaining("nicht signierte");
        Path outside = rewrite(bundle("1.0.0"), Map.of("README.txt", bytes("x")));
        assertThatThrownBy(() -> verifier.verifyZip(outside, tmp, null)).hasMessageContaining("unerwartete");
    }

    @Test
    void missingFileIsRefused() throws Exception {
        Map<String, byte[]> drop = new LinkedHashMap<>();
        drop.put("app/lib/main/b.jar", null);
        Path zip = rewrite(bundle("1.0.0"), drop);
        assertThatThrownBy(() -> verifier.verifyZip(zip, tmp, null)).hasMessageContaining("fehlt");
    }

    @Test
    void olderOrEqualVersionIsRefused() throws Exception {
        Path zip = bundle("1.0.0");
        assertThatThrownBy(() -> verifier.verifyZip(zip, tmp, Version.parse("1.0.0")))
                .hasMessageContaining("nicht neuer");
        assertThatThrownBy(() -> verifier.unpack(zip, tmp.resolve("v"), Version.parse("1.0.1")))
                .hasMessageContaining("nicht neuer");
        assertThat(tmp.resolve("v/1.0.0")).doesNotExist();
        assertThat(verifier.verifyZip(zip, tmp, Version.parse("0.10.0"))).isEqualTo(Version.parse("1.0.0"));
    }

    @Test
    void zipSlipIsRefused() throws Exception {
        for (String evil : new String[] { "app/../../evil.txt", "../evil.txt", "/tmp/evil.txt", "app\\..\\evil.txt",
                "app/C:/evil.txt" }) {
            Path zip = rewrite(bundle("1.0.0"), Map.of(evil, bytes("x")));
            assertThatThrownBy(() -> verifier.unpack(zip, tmp.resolve("versionen"), null))
                    .as(evil).isInstanceOf(BundleException.class);
        }
        assertThat(tmp.resolve("evil.txt")).doesNotExist();
        assertThat(tmp.getParent().resolve("evil.txt")).doesNotExist();
    }

    @Test
    void duplicateEntryIsRefused() throws Exception {
        Path zip = tmp.resolve("dup.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            zos.putNextEntry(new ZipEntry("app/a"));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("app/A"));
            zos.closeEntry();
        }
        // On a case-insensitive file system both names are the same file; elsewhere the signature check fails.
        assertThatThrownBy(() -> verifier.verifyZip(zip, tmp, null)).isInstanceOf(BundleException.class);
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void linkInVersionFolderIsRefused() throws Exception {
        Path versions = tmp.resolve("versionen");
        verifier.unpack(bundle("1.0.0"), versions, null);
        Path a = versions.resolve("1.0.0/app/lib/main/a.jar");
        Files.delete(a);
        Files.createSymbolicLink(a, versions.resolve("1.0.0/app/lib/main/b.jar"));
        assertThatThrownBy(() -> verifier.verifyDir(versions.resolve("1.0.0"))).hasMessageContaining("Verknüpfung");
    }

    @Test
    void notAZipIsRefused() throws Exception {
        Path zip = Files.writeString(tmp.resolve("x.zip"), "no zip");
        assertThatThrownBy(() -> verifier.verifyZip(zip, tmp, null)).hasMessageContaining("ZIP");
    }

    @Test
    void releaseKeyIsCompiledIn() {
        // A default build carries the real release key (e2e.sh builds with -Ddispofold.update.public-key=...).
        assertThat(BundleVerifier.PUBLIC_KEY).isEqualTo("MCowBQYDK2VwAyEAnvb2seNwJz85ftDMydFiepJ41IOq0JpNTODfUhTLfO0=");
        assertThat(new BundleVerifier().signatureValid(bytes("x"), "AAAA")).isFalse();
    }

    static String code(Throwable t) {
        return ((BundleException) t).code();
    }

    @Test
    void signatureIsCheckedBeforeAnythingIsExtracted() throws Exception {
        // A bundle signed with another key whose file would exceed the size limit: the signature fails first.
        Path big = Files.write(app.resolve("lib/main/a.jar"), new byte[4096]);
        assertThat(big).exists();
        BundleVerifier other = new BundleVerifier(SignTool.keyPair().getPublic(), 1000);
        Path versions = Files.createDirectories(tmp.resolve("versionen"));
        assertThatThrownBy(() -> other.unpack(bundle("1.0.0"), versions, null))
                .satisfies(t -> assertThat(code(t)).isEqualTo("signature"));
        assertThat(versions).isEmptyDirectory();
    }

    @Test
    void oversizedListedFileIsRefusedWhileReading() throws Exception {
        // Zip bomb like: 64 MiB of zeros compress to a few KiB, the limit counts the bytes actually read.
        Files.write(app.resolve("lib/main/a.jar"), new byte[64 << 20]);
        Path zip = bundle("1.0.0");
        assertThat(Files.size(zip)).isLessThan(1 << 20);
        BundleVerifier small = new BundleVerifier(keys.getPublic(), 1 << 20);
        Path versions = Files.createDirectories(tmp.resolve("versionen"));
        assertThatThrownBy(() -> small.unpack(zip, versions, null))
                .satisfies(t -> assertThat(code(t)).isEqualTo("tooLarge"));
        assertThatThrownBy(() -> small.verifyZip(zip, tmp, null))
                .satisfies(t -> assertThat(code(t)).isEqualTo("tooLarge"));
        assertThat(versions).isEmptyDirectory();
    }

    @Test
    void oversizedManifestIsRefused() throws Exception {
        Path zip = rewrite(bundle("1.0.0"), Map.of("bundle.json", new byte[(int) BundleVerifier.MAX_JSON_BYTES + 1]));
        assertThatThrownBy(() -> verifier.verifyZip(zip, tmp, null))
                .satisfies(t -> assertThat(code(t)).isEqualTo("manifestTooLarge"));
    }

    @Test
    void unlistedEntryIsRefusedBeforeExtracting() throws Exception {
        Path zip = rewrite(bundle("1.0.0"), Map.of("app/lib/main/extra.jar", bytes("x")));
        Path versions = Files.createDirectories(tmp.resolve("versionen"));
        assertThatThrownBy(() -> verifier.unpack(zip, versions, null))
                .satisfies(t -> assertThat(code(t)).isEqualTo("unsignedFile"));
        assertThat(versions).isEmptyDirectory();
    }

    @Test
    void verifyZipWritesNothing() throws Exception {
        Path work = Files.createDirectories(tmp.resolve("work2"));
        assertThat(verifier.verifyZip(bundle("1.0.0"), work, null)).isEqualTo(Version.parse("1.0.0"));
        assertThat(work).isEmptyDirectory();
    }

    @Test
    void versionsCompareNumerically() {
        assertThat(Version.parse("0.10.0").isNewerThan(Version.parse("0.9.9"))).isTrue();
        assertThat(Version.isValid("1.0")).isFalse();
        assertThat(Version.isValid("1.0.0-SNAPSHOT")).isFalse();
        assertThat(Version.isValid("01.0.0")).isFalse();
        assertThat(Version.isValid("../1.0.0")).isFalse();
    }

    @Test
    void jsonRoundTrip() {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("a", "x\"y\\z\n\u00e4");
        doc.put("n", 5L);
        doc.put("b", java.util.List.of("1", "2"));
        doc.put("nil", null);
        assertThat(Json.parse(Json.write(doc))).isEqualTo(doc);
        assertThatThrownBy(() -> Json.parse("{\"a\":1,\"a\":2}")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Json.parse("{\"a\":1} x")).isInstanceOf(IllegalArgumentException.class);
    }
}
