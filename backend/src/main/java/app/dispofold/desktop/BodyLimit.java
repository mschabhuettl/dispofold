package app.dispofold.desktop;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import java.util.Set;

import io.quarkus.arc.profile.IfBuildProfile;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpVersion;
import io.vertx.ext.web.Router;

/**
 * The desktop build allows request bodies up to 1 GiB ({@code quarkus.http.limits.max-body-size}) because of the
 * update upload. Every other path gets the limit of the server build (50 MB): a larger {@code Content-Length} is
 * answered with 413, a body without {@code Content-Length} (chunked, or HTTP/2) with 411 before it is read.
 */
@IfBuildProfile("desktop")
@ApplicationScoped
public class BodyLimit {

    static final String UPDATE_FILE = "/api/desktop/update/file";
    static final long SMALL = 50L << 20;
    static final Set<HttpMethod> BODY_METHODS = Set.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH);

    void install(@Observes Router router) {
        // Right after Quarkus' own check of the global limit (order -2).
        router.route().order(-1).handler(ctx -> {
            boolean upload = UPDATE_FILE.equals(ctx.normalizedPath()) && ctx.request().method() == HttpMethod.POST;
            if (upload) {
                ctx.next();
                return;
            }
            // Also caps bodies that are read as a stream (VertxInputStream honours this key).
            ctx.put("io.quarkus.max-request-size", SMALL);
            String length = ctx.request().getHeader("Content-Length");
            String chunked = ctx.request().getHeader("Transfer-Encoding");
            int status = 0;
            if (length != null) {
                try {
                    status = Long.parseLong(length.trim()) > SMALL ? 413 : 0;
                } catch (NumberFormatException e) {
                    status = 400;
                }
            } else if (chunked != null || (ctx.request().version() == HttpVersion.HTTP_2
                    && !ctx.request().isEnded() && BODY_METHODS.contains(ctx.request().method()))) {
                // HTTP/1.1 chunked, or HTTP/2 without content-length: the size is unknown before reading.
                status = 411;
            }
            if (status != 0) {
                ctx.response().putHeader("Connection", "close").setStatusCode(status).end();
                return;
            }
            ctx.next();
        });
    }
}
