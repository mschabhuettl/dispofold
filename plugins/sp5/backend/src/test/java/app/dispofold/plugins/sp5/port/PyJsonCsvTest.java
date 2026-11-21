package app.dispofold.plugins.sp5.port;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** json.dump(indent=1, ensure_ascii=False), json.load and csv.writer(delimiter=";") as Python 3.14 writes and reads. */
class PyJsonCsvTest {

    @Test
    void dumpIsPythonsIndentOne() {
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("b", null);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("a", List.of(1L, 2.0, inner));
        m.put("c", "é\u0001\"\\");
        m.put("d", List.of());
        m.put("e", Map.of());
        m.put("f", Double.NaN);
        m.put("g", -0.0);
        m.put("h", 1e16);
        assertThat(PyJson.dump(m)).isEqualTo("""
                {
                 "a": [
                  1,
                  2.0,
                  {
                   "b": null
                  }
                 ],
                 "c": "é\\u0001\\"\\\\",
                 "d": [],
                 "e": {},
                 "f": NaN,
                 "g": -0.0,
                 "h": 1e+16
                }""");
    }

    @Test
    void loadKeepsOrderIntAndFloatAndTheLastOfRepeatedKeys() throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) PyJson.load("{\"a\": 1, \"b\": 2.0, \"a\": 3, \"n\": NaN, "
                + "\"big\": 123456789012345678901234567890, \"s\": \"\\ud83d\\ude00\\u00e9\", \"x\": -Infinity}");
        assertThat(new ArrayList<>(m.keySet())).containsExactly("a", "b", "n", "big", "s", "x");
        assertThat(m.get("a")).isEqualTo(3L);
        assertThat(m.get("b")).isEqualTo(2.0);
        assertThat((Double) m.get("n")).isNaN();
        assertThat(m.get("big")).isEqualTo(new BigInteger("123456789012345678901234567890"));
        assertThat(m.get("s")).isEqualTo("😀é");
        assertThat(m.get("x")).isEqualTo(Double.NEGATIVE_INFINITY);
        assertThat(PyJson.load("1e400")).isEqualTo(Double.POSITIVE_INFINITY);
        assertThat(PyJson.load("-0")).isEqualTo(0L);
    }

    @Test
    void loadRefusesWhatPythonRefuses() {
        for (String bad : List.of("﻿{}", "{} x", "[1,]", "01", "{'a': 1}", "\"a\u0001\"", "", "[1 2]", ".5")) {
            assertThatThrownBy(() -> PyJson.load(bad)).as(bad).isInstanceOf(PyJson.DecodeError.class)
                    .hasMessage("JSONDecodeError");
        }
        assertThatThrownBy(() -> PyJson.load(new byte[] { '"', (byte) 0xFF, '"' }))
                .hasMessage("UnicodeDecodeError");
    }

    @Test
    void csvQuotesOnlyWhereNeeded() {
        StringBuilder out = new StringBuilder();
        PyCsv.row(out, Arrays.asList("a;b", "q\"x", "c\rd", 1.0, 3L, null, true, "", Double.POSITIVE_INFINITY));
        PyCsv.row(out, List.of(""));
        PyCsv.row(out, List.of());
        assertThat(out.toString()).isEqualTo("\"a;b\";\"q\"\"x\";\"c\rd\";1.0;3;;True;;inf\r\n\"\"\r\n\r\n");
    }

    @Test
    void reportBytesAreUtf8WithTheHeader() {
        Sp5Export.Result r = new Sp5Export.Result(Map.of(), List.of(new ArrayList<>(List.of("person", "E1", "", "",
                4L, "Ärger"))), List.of());
        String text = new String(Sp5Export.reportBytes(r), StandardCharsets.UTF_8);
        assertThat(text).startsWith("pruefung;person;bereich;schicht;").endsWith("\r\nperson;E1;;;4;Ärger\r\n");
    }
}
