package app.dispofold.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;

@QuarkusTest
@TestProfile(FakeFacadeProfile.class)
@TestSecurity(user = "planer", roles = "planner")
class ImportTest {

    @Test
    void demoDataIsTheBundledSyntheticMonth() {
        given().post("/api/import/demo").then().statusCode(200).body("source", equalTo("synthetic"))
                .body("people", equalTo(150)).body("areas", equalTo(5)).body("months", equalTo(List.of("2027-01")))
                .body("warnings.size()", equalTo(0));
        given().get("/api/dataset").then().body("people.size()", equalTo(150));
        given().get("/api/periods/2027-01").then().statusCode(200).body("month", equalTo("2027-01"));
    }

    @Test
    void brokenUploadsAnswerWithoutContent() {
        // not JSON at all, and a type error inside: the answer names the position, never the content
        given().contentType(ContentType.JSON).body("{\"format\": Muster").post("/api/import/dataset").then()
                .statusCode(400).body("code", equalTo("import.parse")).body("params.line", equalTo(1))
                .body(not(containsString("Muster")));
        given().contentType(ContentType.JSON)
                .body("{\"format\":\"dispofold/dataset\",\"version\":\"Muster\"}")
                .post("/api/import/dataset").then().statusCode(400).body(not(containsString("Muster")));
    }

    @Test
    void uploadedDatasetIsValidated() {
        given().contentType(ContentType.JSON).body("{\"format\":\"other\",\"version\":2}").post("/api/import/dataset")
                .then().statusCode(400).body("code", equalTo("import.invalid"))
                .body("errors.code", hasItem("import.format"));
        String dataset = "{\"format\":\"dispofold/dataset\",\"version\":2,\"source\":\"synthetic\","
                + "\"areas\":[{\"id\":\"A\",\"name\":\"A\",\"color\":\"#000000\"}],"
                + "\"shifts\":[{\"id\":\"S1\",\"areaId\":\"B\",\"code\":\"X\",\"name\":\"x\",\"start\":\"08:00\","
                + "\"end\":\"18:00\",\"category\":null,\"color\":null,\"unpopular\":false}],"
                + "\"people\":[{\"id\":\"P1\",\"firstName\":\"Erika\",\"lastName\":\"Muster\",\"shortName\":\"EM\","
                + "\"weeklyHours\":40,\"areaIds\":[\"A\",\"Z\"],\"hoursAccount\":0,\"compRestBalance\":0},"
                + "{\"id\":\"P1\",\"firstName\":\"Erika\",\"lastName\":\"Muster\",\"weeklyHours\":40,\"areaIds\":[]}]}";
        // a broken dataset is rejected as a whole with codes and ids, never names
        given().contentType(ContentType.JSON).body(dataset).post("/api/import/dataset").then().statusCode(400)
                .body("code", equalTo("import.invalid"))
                .body("errors.code", hasItem("import.unknownArea"))
                .body("errors.code", hasItem("import.duplicateId"))
                .body(not(containsString("Muster")));
        given().get("/api/dataset").then().body("people.size()", not(equalTo(1)));
    }
}
