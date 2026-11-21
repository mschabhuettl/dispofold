package app.dispofold.plugin.api;

import java.util.Map;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response.Status;

import app.dispofold.api.ApiErrors;

/**
 * Error answers in Dispofold's scheme {@code {"code", "params", "error"}}. The code starts with
 * {@code plugin.<id>.}; its text is {@code error.plugin.<id>.…} in the plugin's texts. Params hold ids, numbers and
 * dates only, never names, paths or exception messages.
 */
public final class PluginErrors {

    private PluginErrors() {
    }

    public static WebApplicationException error(Status status, String code, Map<String, Object> params,
            String fallback) {
        return ApiErrors.error(status, code, params, fallback);
    }
}
