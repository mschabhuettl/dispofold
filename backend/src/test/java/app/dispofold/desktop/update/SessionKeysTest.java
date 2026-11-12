package app.dispofold.desktop.update;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** The vector agreed with the shell (same values in its Rust tests). */
class SessionKeysTest {

    static final byte[] KEY = SessionKeys.secret("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=");

    @Test
    void sharedVector() {
        assertThat(KEY).hasSize(32).startsWith(0, 1, 2).endsWith(31);
        assertThat(SessionKeys.proof(KEY, "ZGVmZ2hpamtsbW5vcHFycw==", 43210, 4242))
                .isEqualTo("AYue2OIBHSl9qTJ72vnA6EYg5Bfwo7h33kUQvks72S0=");
        assertThat(SessionKeys.response(KEY, "yMnKy8zNzs/Q0dLT1NXW1w==", 43210))
                .isEqualTo("2xRqM6Vq/DHnKq2h6WkHjjan37wHFoS7j8Q4lgK8HYg=");
        // Per app start: the nonce of that start's ready line.
        assertThat(SessionKeys.session(KEY, "ZGVmZ2hpamtsbW5vcHFycw=="))
                .isEqualTo("HlTrRK+7tRifwGl3ub6w2wHw7WUuIeQnomq4q3SD4OQ=");
        assertThat(SessionKeys.session(KEY, "yMnKy8zNzs/Q0dLT1NXW1w=="))
                .isEqualTo("jmVcnKTTvbihIjiQiwWuqos2IqxqnPowQXDW5JYRWEg=");
    }

    @Test
    void inputsAreChecked() {
        assertThatThrownBy(() -> SessionKeys.secret("AAEC")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SessionKeys.secret("not base64!")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SessionKeys.secret(null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(SessionKeys.isNonce(SessionKeys.nonce())).isTrue();
        assertThat(SessionKeys.nonce()).isNotEqualTo(SessionKeys.nonce());
        assertThat(SessionKeys.isNonce("AAEC")).isFalse();
        assertThat(SessionKeys.isNonce("%%%")).isFalse();
        assertThat(SessionKeys.isNonce(null)).isFalse();
        assertThat(SessionKeys.same("abc", "abc")).isTrue();
        assertThat(SessionKeys.same("abc", "abd")).isFalse();
        assertThat(SessionKeys.same("abc", null)).isFalse();
    }
}
