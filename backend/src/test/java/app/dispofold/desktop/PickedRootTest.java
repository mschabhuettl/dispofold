package app.dispofold.desktop;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;

/** Desktop program: the window adds the folder the user picked; the page alone cannot. */
@QuarkusTest
@TestProfile(DesktopProfile.class)
class PickedRootTest {

    @Test
    void onlyTheWindowAddsAPickedFolder() throws Exception {
        Path folder = Files.createDirectories(Path.of(DesktopProfile.BASE, "gewaehlt"));
        String body = "{\"path\":\"" + folder + "\"}";
        DesktopProfile.local().contentType(ContentType.JSON).body(body).post("/api/files/roots").then()
                .statusCode(403).body("code", equalTo("files.pickInWindow"));
        DesktopProfile.local().get("/api/files/roots").then().statusCode(200).body("size()", equalTo(0));
        DesktopProfile.local().contentType(ContentType.JSON).header("X-Dispofold-Picked", "1")
                .body("{\"path\":\"relativ\"}").post("/api/files/roots").then().statusCode(404);
        DesktopProfile.local().contentType(ContentType.JSON).header("X-Dispofold-Picked", "1").body(body)
                .post("/api/files/roots").then().statusCode(200).body("id", equalTo(folder.toString()));
        DesktopProfile.local().get("/api/files/roots").then().statusCode(200).body("id", hasItem(folder.toString()));
        DesktopProfile.local().queryParam("root", folder.toString()).get("/api/files/list").then().statusCode(200);
    }
}
