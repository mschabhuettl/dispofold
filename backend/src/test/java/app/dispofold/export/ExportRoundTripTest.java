package app.dispofold.export;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import jakarta.inject.Inject;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import app.dispofold.api.FakeFacadeProfile;
import app.dispofold.api.FakePlanningFacade;
import app.dispofold.api.TestData;
import app.dispofold.model.Dataset;
import app.dispofold.model.Plan;
import app.dispofold.model.PlanAssignment;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;

@QuarkusTest
@TestProfile(FakeFacadeProfile.class)
@TestSecurity(user = "planer", roles = "planner")
class ExportRoundTripTest {

    static final YearMonth MONTH = YearMonth.of(2031, 5);
    static final String BASE = "/api/plans/" + MONTH;

    @Inject
    ObjectMapper mapper;

    Dataset dataset = TestData.dataset(MONTH);

    @BeforeEach
    void solvedPlan() throws Exception {
        FakePlanningFacade.solveMillis = 100;
        FakePlanningFacade.fail = false;
        given().contentType(ContentType.JSON).body(mapper.writeValueAsString(dataset)).post("/api/import/dataset")
                .then().statusCode(200);
        given().post(BASE + "/solve").then().statusCode(202);
        await().atMost(Duration.ofSeconds(10))
                .until(() -> !given().get(BASE).then().extract().<Boolean> path("solving"));
        // a day without duty on the absence day and a compensatory rest day, both pinned
        for (PlanAssignment cell : List.of(new PlanAssignment("P0001", MONTH.atDay(3), null, false, true),
                new PlanAssignment("P0001", MONTH.atDay(4), null, true, true))) {
            given().contentType(ContentType.JSON).body(mapper.writeValueAsString(cell)).put(BASE + "/cells")
                    .then().statusCode(200);
        }
    }

    Plan stored() {
        return mapper.convertValue(given().get(BASE).then().extract().jsonPath().getMap("plan"), Plan.class);
    }

    @Test
    void jsonRoundTrip() throws Exception {
        Plan before = stored();
        byte[] json = given().get(BASE + "/export?format=json").then().statusCode(200)
                .header("Content-Disposition", containsString("dienstplan-2031-05.json")).extract().asByteArray();
        Plan exported = mapper.readValue(json, Plan.class);
        assertThat(exported.format()).isEqualTo(Plan.FORMAT);
        assertThat(new String(json, StandardCharsets.UTF_8)).contains("\"month\" : \"2031-05\"");

        // clear the plan, then import the export again
        List<PlanAssignment> other = before.assignments().stream()
                .map(a -> new PlanAssignment(a.personId(), a.date(), null, false, false)).toList();
        Plan blank = new Plan(Plan.FORMAT, 1, MONTH, before.status(), null, 0, null, other);
        given().contentType(ContentType.JSON).body(mapper.writeValueAsString(blank)).post(BASE + "/import").then()
                .statusCode(200);
        assertThat(stored().assignments()).isNotEqualTo(before.assignments());

        given().contentType(ContentType.JSON).body(json).post(BASE + "/import").then().statusCode(200);
        assertThat(stored().assignments()).isEqualTo(before.assignments());
        assertThat(stored().seed()).isEqualTo(before.seed());

        // duplicate rows, wrong month or format are rejected
        List<PlanAssignment> doubled = new ArrayList<>(before.assignments());
        doubled.add(before.assignments().get(0));
        given().contentType(ContentType.JSON)
                .body(mapper.writeValueAsString(new Plan(Plan.FORMAT, 1, MONTH, null, null, 0, null, doubled)))
                .post(BASE + "/import").then().statusCode(400);
        given().contentType(ContentType.JSON).body(json).post("/api/plans/2031-06/import").then().statusCode(400);
        given().contentType(ContentType.JSON).body("{\"format\":\"x\",\"version\":1}").post(BASE + "/import")
                .then().statusCode(400);
    }

    @Test
    void csvRoundTrip() {
        byte[] csv = given().get(BASE + "/export?format=csv").then().statusCode(200)
                .contentType(containsString("text/csv")).extract().asByteArray();
        assertThat(csv[0]).isEqualTo((byte) 0xEF); // UTF-8 BOM
        String text = new String(csv, StandardCharsets.UTF_8).substring(1);
        List<String> lines = text.lines().toList();
        assertThat(lines.get(0)).isEqualTo(PlanExporter.CSV_HEADER);

        List<PlanAssignment> parsed = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            String[] f = line.split(";", -1);
            assertThat(f).hasSize(11);
            parsed.add(new PlanAssignment(f[0], LocalDate.parse(f[2]), f[4].isEmpty() ? null : f[4],
                    Boolean.parseBoolean(f[8]), Boolean.parseBoolean(f[9])));
            if (f[4].equals("DR-N")) {
                assertThat(f[3]).isEqualTo("R4");
                assertThat(f[5] + "-" + f[6] + " " + f[7]).isEqualTo("19:00-07:00 12,00");
            }
        }
        assertThat(parsed).isEqualTo(stored().assignments());
        assertThat(lines.get(1)).startsWith("P0001;Testperson1 Vorname1;");
    }

    @Test
    void xlsxOpensWithCodesAndColours() throws Exception {
        byte[] xlsx = given().get(BASE + "/export?format=xlsx").then().statusCode(200)
                .contentType(containsString(PlanExporter.XLSX)).extract().asByteArray();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            Sheet plan = wb.getSheet("Dienstplan");
            int days = MONTH.lengthOfMonth();
            assertThat(plan.getPhysicalNumberOfRows()).isEqualTo(2 + dataset.people().size());
            assertThat(plan.getRow(0).getCell(2).getStringCellValue()).isEqualTo("Do"); // 2031-05-01
            assertThat(plan.getRow(1).getCell(2).getStringCellValue()).isEqualTo("01.05.");
            assertThat(plan.getRow(0).getCell(days + 2).getStringCellValue()).isEqualTo("Stunden");

            for (PlanAssignment a : stored().assignments()) {
                Row row = plan.getRow(2 + Integer.parseInt(a.personId().substring(1)) - 1);
                assertThat(row.getCell(0).getStringCellValue()).isEqualTo(a.personId());
                String code = row.getCell(1 + a.date().getDayOfMonth()).getStringCellValue();
                // the absence of the period (P0001 on day 3) appears as its code
                String free = a.compRest() ? "ER" : a.date().getDayOfMonth() == 3 ? "U" : "";
                String expected = a.shiftId() == null ? free
                        : dataset.shifts().stream().filter(s -> s.id().equals(a.shiftId())).findFirst()
                                .orElseThrow().code();
                assertThat(code).as(a.toString()).isEqualTo(expected);
            }
            // P0002 works AN-T (#F6E3D3, 12 h) on every day
            XSSFCellStyle style = (XSSFCellStyle) plan.getRow(3).getCell(2).getCellStyle();
            assertThat(style.getFillForegroundXSSFColor().getARGBHex()).endsWith("F6E3D3");
            assertThat(plan.getRow(3).getCell(days + 2).getNumericCellValue()).isEqualTo(days * 12.0);

            Sheet figures = wb.getSheet("Kennzahlen");
            assertThat(figures.getPhysicalNumberOfRows()).isEqualTo(1 + dataset.people().size());
            assertThat(figures.getRow(0).getLastCellNum()).isEqualTo((short) (1 + PlanExporter.STATS_HEADER.size()));
            assertThat(figures.getRow(1).getCell(1).getStringCellValue()).isEqualTo("P0001");
        }
    }
}
