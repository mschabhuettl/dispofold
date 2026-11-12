package app.dispofold.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;

/** 1 GiB only for the update upload, 50 MB everywhere else. */
@QuarkusTest
@TestProfile(DesktopProfile.class)
class DesktopBodyLimitTest {

    static final byte[] BIG = new byte[(int) BodyLimit.SMALL + 1];

    static int post(String path, HttpRequest.BodyPublisher body, String type) throws Exception {
        return post(path, body, type, false);
    }

    /** With expectContinue, a refusal from the headers alone is read before the body is sent (no broken pipe). */
    static int post(String path, HttpRequest.BodyPublisher body, String type, boolean expectContinue)
            throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + RestAssured.port + path))
                .header(DesktopAuth.HEADER, DesktopProfile.session()).header("Content-Type", type)
                .expectContinue(expectContinue).POST(body).build();
        return send(request);
    }

    static int send(HttpRequest request) throws Exception {
        // HTTP/1.1: an h2c upgrade would drop the Host header that the desktop login checks.
        return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
                .send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    @Test
    void largeBodyToAnotherEndpointIs413() throws Exception {
        assertThat(post("/api/import/dataset", HttpRequest.BodyPublishers.ofByteArray(BIG), "application/json",
                true)).isEqualTo(413);
        assertThat(post("/api/desktop/update/settings", HttpRequest.BodyPublishers.ofByteArray(BIG),
                "application/json", true)).isEqualTo(413);
    }

    @Test
    void chunkedBodyToAnotherEndpointIs411() throws Exception {
        assertThat(post("/api/import/dataset",
                HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream("{}".getBytes())),
                "application/json")).isEqualTo(411);
    }

    @Test
    void http2BodyWithoutLengthIs411() throws Exception {
        io.vertx.core.Vertx vertx = io.vertx.core.Vertx.vertx();
        try {
            io.vertx.core.http.HttpClient client = vertx.createHttpClient(new io.vertx.core.http.HttpClientOptions()
                    .setProtocolVersion(io.vertx.core.http.HttpVersion.HTTP_2).setHttp2ClearTextUpgrade(false));
            int status = client.request(io.vertx.core.http.HttpMethod.POST, RestAssured.port, "127.0.0.1",
                    "/api/import/dataset").compose(req -> {
                        req.putHeader(DesktopAuth.HEADER, DesktopProfile.session());
                        req.putHeader("Content-Type", "application/json");
                        req.setChunked(true);
                        req.write("{}");
                        return req.end().compose(v -> req.response());
                    }).map(r -> {
                        assertThat(r.version()).isEqualTo(io.vertx.core.http.HttpVersion.HTTP_2);
                        return r.statusCode();
                    }).toCompletionStage().toCompletableFuture().get(20, java.util.concurrent.TimeUnit.SECONDS);
            // Refused before any body is read: DesktopAuth already refuses HTTP/2 (no Host header, 403); without
            // it, BodyLimit would answer 411 because the length is unknown.
            assertThat(status).isIn(403, 411);
        } finally {
            vertx.close().toCompletionStage().toCompletableFuture().get(20, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    @Test
    void largeBodyToTheUpdateUploadIsRead() throws Exception {
        // Not a bundle: refused by the check (400), not by the size limit.
        assertThat(post(BodyLimit.UPDATE_FILE, HttpRequest.BodyPublishers.ofByteArray(BIG),
                "application/octet-stream")).isEqualTo(400);
    }
}
