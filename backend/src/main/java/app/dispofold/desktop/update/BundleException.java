package app.dispofold.desktop.update;

import java.util.Map;

/**
 * A bundle or version folder failed the check. {@code code} is a stable key (the REST API answers
 * {@code desktop.bundle.<code>}), {@code params} hold versions only; the message is a German text for the user.
 */
public class BundleException extends Exception {

    private final String code;
    private final Map<String, Object> params;

    public BundleException(String code, String message) {
        this(code, Map.of(), message);
    }

    public BundleException(String code, Map<String, Object> params, String message) {
        super(message);
        this.code = code;
        this.params = params;
    }

    public BundleException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.params = Map.of();
    }

    public String code() {
        return code;
    }

    public Map<String, Object> params() {
        return params;
    }
}
