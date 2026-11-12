package app.dispofold.api;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.Map;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;

/**
 * Error bodies {@code {"code": "...", "params": {...}, "error": "..."}}. The code is a stable key for the texts
 * of the user interface ({@code error.<code>}); params hold ids, numbers and dates only, never names, paths or
 * exception messages. {@code error} is a German fallback. The codes are listed in docs/api.md.
 */
public final class ApiErrors {

    public record Error(String code, Map<String, Object> params, String error) {
    }

    private ApiErrors() {
    }

    public static WebApplicationException error(Status status, String code, Map<String, Object> params,
            String fallback) {
        return new WebApplicationException(Response.status(status).type(MediaType.APPLICATION_JSON)
                .entity(new Error(code, params == null ? Map.of() : params, fallback)).build());
    }

    public static WebApplicationException error(Status status, String code, String fallback) {
        return error(status, code, Map.of(), fallback);
    }

    /**
     * An error without a specific code.
     *
     * @deprecated use a code with {@link #error(Status, String, Map, String)}
     */
    @Deprecated
    public static WebApplicationException error(Status status, String message) {
        return error(status, "general", Map.of(), message);
    }

    public static YearMonth month(String value) {
        try {
            return YearMonth.parse(value);
        } catch (DateTimeParseException | NullPointerException e) {
            throw error(Status.BAD_REQUEST, "request.month", "Monat im Format JJJJ-MM erwartet");
        }
    }
}
