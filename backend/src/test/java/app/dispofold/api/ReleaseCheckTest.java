package app.dispofold.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import app.dispofold.api.ReleaseCheck.Release;

/** The server's release check against a local stand-in for the GitHub releases API. */
class ReleaseCheckTest {

    private HttpServer github;
    private final AtomicInteger requests = new AtomicInteger();
    private volatile int status = 200;
    private volatile String body = "";
    private ReleaseCheck check;

    @BeforeEach
    void start() throws IOException {
        github = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        github.createContext("/repos/mschabhuettl/dispofold/releases/latest", ex -> {
            requests.incrementAndGet();
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, b.length == 0 ? -1 : b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        github.start();
        check = new ReleaseCheck();
        check.mapper = new ObjectMapper();
        check.enabled = true;
        check.applicationVersion = "1.2.0";
        check.url = "http://127.0.0.1:" + github.getAddress().getPort() + "/repos/mschabhuettl/dispofold/releases/latest";
    }

    @AfterEach
    void stop() {
        check.stop();
        github.stop(0);
    }

    private static String release(String tag, boolean prerelease) {
        return "{\"tag_name\":\"" + tag + "\",\"name\":\"Dispofold " + tag + "\",\"prerelease\":" + prerelease
                + ",\"draft\":false,\"body\":\"<b>Neu</b>\\n- Wochenenden\","
                + "\"html_url\":\"https://github.com/mschabhuettl/dispofold/releases/tag/" + tag + "\"}";
    }

    @Test
    void aNewerReleaseIsFoundOnStart() {
        body = release("v1.3.0", false);
        check.onStart(null);
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> check.latest() != null);
        assertThat(check.latest()).isEqualTo(new Release("1.3.0", "Dispofold v1.3.0", "<b>Neu</b>\n- Wochenenden",
                "https://github.com/mschabhuettl/dispofold/releases/tag/v1.3.0"));
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void theSameOrAnOlderVersionIsNoNews() {
        body = release("v1.2.0", false);
        check.check();
        assertThat(check.latest()).isNull();
        body = release("1.1.9", false);
        check.check();
        assertThat(check.latest()).isNull();
        assertThat(requests.get()).isEqualTo(2);
    }

    @Test
    void preReleasesAreIgnored() {
        body = release("v1.3.0", true);
        check.check();
        assertThat(check.latest()).isNull();
        body = release("v1.3.0-rc.1", false);
        check.check();
        assertThat(check.latest()).isNull();
    }

    @Test
    void versionsCompareByNumberNotText() {
        check.applicationVersion = "1.9.0";
        body = release("v1.10.0", false);
        check.check();
        assertThat(check.latest().version()).isEqualTo("1.10.0");
    }

    @Test
    void errorsKeepTheLastResultAndThrowNothing() {
        body = release("v1.3.0", false);
        check.check();
        status = 500;
        body = "{\"message\":\"x\"}";
        check.check();
        assertThat(check.latest().version()).isEqualTo("1.3.0");
        status = 200;
        body = "not json";
        check.check();
        assertThat(check.latest().version()).isEqualTo("1.3.0");
        check.url = "http://127.0.0.1:1/unreachable";
        check.check();
        assertThat(check.latest().version()).isEqualTo("1.3.0");
    }

    @Test
    void aPageThatIsNotHttpsIsLeftOut() {
        body = "{\"tag_name\":\"v2.0.0\",\"html_url\":\"javascript:alert(1)\"}";
        check.check();
        assertThat(check.latest()).isEqualTo(new Release("2.0.0", null, null, null));
    }

    @Test
    void switchedOffItAsksNothing() throws InterruptedException {
        body = release("v1.3.0", false);
        check.enabled = false;
        check.onStart(null);
        Thread.sleep(500);
        assertThat(requests.get()).isZero();
        assertThat(check.latest()).isNull();
    }
}
