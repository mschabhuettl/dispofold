package app.dispofold.desktop.update;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.util.Base64;

/**
 * Command line tool for update bundles, part of dispofold-launcher.jar:
 *
 * <pre>
 * java -cp dispofold-launcher.jar app.dispofold.desktop.update.SignTool keygen &lt;private-out&gt; &lt;public-out&gt;
 * java -cp dispofold-launcher.jar app.dispofold.desktop.update.SignTool sign &lt;keyfile&gt; &lt;bundle.json&gt;
 * java -cp dispofold-launcher.jar app.dispofold.desktop.update.SignTool bundle &lt;keyfile&gt; &lt;quarkus-app&gt; &lt;version&gt; &lt;out-dir&gt;
 * java -cp dispofold-launcher.jar app.dispofold.desktop.update.SignTool manifest &lt;quarkus-app&gt; &lt;version&gt; &lt;out bundle.json&gt;
 * java -cp dispofold-launcher.jar app.dispofold.desktop.update.SignTool verify [--key &lt;public-keyfile&gt;] &lt;bundle.zip&gt;
 * </pre>
 *
 * Key files hold Base64 text: PKCS#8 DER for private keys, X.509 DER for public keys. {@code verify} uses the
 * compiled in release key unless {@code --key} is given. {@code manifest} writes an unsigned bundle.json, used
 * next to the bundled app of an installer so that the launcher knows its version.
 */
public final class SignTool {

    private SignTool() {
    }

    public static void main(String[] args) {
        try {
            System.exit(run(args));
        } catch (IOException | BundleException | IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        }
    }

    static int run(String[] args) throws IOException, BundleException {
        String cmd = args.length > 0 ? args[0] : "";
        switch (cmd) {
            case "keygen" -> {
                need(args, 3);
                KeyPair pair = keyPair();
                writePrivate(Path.of(args[1]), Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()));
                Files.writeString(Path.of(args[2]),
                        Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()) + "\n");
                return 0;
            }
            case "sign" -> {
                need(args, 3);
                Path json = Path.of(args[2]);
                String sig = BundleMaker.sign(BundleMaker.readPrivateKey(Path.of(args[1])), Files.readAllBytes(json));
                Files.writeString(json.resolveSibling(BundleVerifier.SIGNATURE), sig, StandardCharsets.US_ASCII);
                return 0;
            }
            case "bundle" -> {
                need(args, 5);
                Path zip = BundleMaker.make(Path.of(args[2]), Version.parse(args[3]),
                        BundleMaker.readPrivateKey(Path.of(args[1])), Path.of(args[4]));
                System.out.println(zip);
                return 0;
            }
            case "manifest" -> {
                need(args, 4);
                Files.write(Path.of(args[3]), BundleMaker.manifest(Path.of(args[1]), Version.parse(args[2])));
                return 0;
            }
            case "verify" -> {
                BundleVerifier verifier = new BundleVerifier();
                int i = 1;
                if (args.length > 2 && args[1].equals("--key")) {
                    verifier = new BundleVerifier(readPublicKey(Path.of(args[2])));
                    i = 3;
                }
                need(args, i + 1);
                Path zip = Path.of(args[i]).toAbsolutePath();
                Version v = verifier.verifyZip(zip, zip.getParent(), null);
                System.out.println("OK " + v);
                return 0;
            }
            default -> {
                System.err.println("Usage: SignTool keygen <private-out> <public-out> | sign <keyfile> <bundle.json>"
                        + " | bundle <keyfile> <quarkus-app> <version> <out-dir>"
                        + " | manifest <quarkus-app> <version> <out bundle.json>"
                        + " | verify [--key <public-keyfile>] <bundle.zip>");
                return 2;
            }
        }
    }

    private static void need(String[] args, int n) {
        if (args.length < n) {
            throw new IllegalArgumentException("missing arguments for " + args[0]);
        }
    }

    public static KeyPair keyPair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static PublicKey readPublicKey(Path file) throws IOException {
        return BundleVerifier.publicKey(Files.readString(file, StandardCharsets.US_ASCII));
    }

    private static void writePrivate(Path file, String base64) throws IOException {
        Files.deleteIfExists(file);
        try {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException e) {
            Files.createFile(file);
        }
        Files.writeString(file, base64 + "\n", StandardCharsets.US_ASCII);
    }
}
