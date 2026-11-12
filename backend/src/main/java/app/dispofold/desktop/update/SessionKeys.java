package app.dispofold.desktop.update;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The HMACs between the program window (shell), the launcher and the backend (docs/desktop.md, "Fenster"). The
 * key is the decoded 32 byte secret the shell sends once; nonces and challenges are 16 random bytes and enter the
 * messages as their standard Base64 text; results are standard Base64 with padding.
 */
public final class SessionKeys {

    private static final SecureRandom RANDOM = new SecureRandom();

    private SessionKeys() {
    }

    /** The 32 byte key from its Base64 text; IllegalArgumentException if it is anything else. */
    public static byte[] secret(String base64) {
        byte[] key = Base64.getDecoder().decode(base64 == null ? "" : base64);
        if (key.length != 32) {
            throw new IllegalArgumentException("secret must be 32 bytes");
        }
        return key;
    }

    /** Base64 of 16 bytes (a nonce or challenge)? */
    public static boolean isNonce(String base64) {
        try {
            return base64 != null && Base64.getDecoder().decode(base64).length == 16;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public static String nonce() {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        return Base64.getEncoder().encodeToString(b);
    }

    /** {@code ready|<nonce>|<port>|<pid>}: the backend that holds the secret listens on this port. */
    public static String proof(byte[] key, String nonce, int port, long pid) {
        return hmac(key, "ready|" + nonce + "|" + port + "|" + pid);
    }

    /** {@code challenge|<challenge>|<port>}: the answer to the shell's challenge. */
    public static String response(byte[] key, String challenge, int port) {
        return hmac(key, "challenge|" + challenge + "|" + port);
    }

    /** {@code session|<nonce>}: the {@code X-Dispofold-Session} header for the app start with this ready nonce. */
    public static String session(byte[] key, String nonce) {
        return hmac(key, "session|" + nonce);
    }

    /** Constant-time comparison of two Base64 texts. */
    public static boolean same(String expected, String actual) {
        return actual != null && MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                actual.getBytes(StandardCharsets.US_ASCII));
    }

    private static String hmac(byte[] key, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
