package app.dispofold.plugins.sp5.port;

import static app.dispofold.plugins.sp5.port.TestTables.c;
import static app.dispofold.plugins.sp5.port.TestTables.d;
import static app.dispofold.plugins.sp5.port.TestTables.f;
import static app.dispofold.plugins.sp5.port.TestTables.l;
import static app.dispofold.plugins.sp5.port.TestTables.n;
import static app.dispofold.plugins.sp5.port.TestTables.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class DbfTest {

    static byte[] latin(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    @Test
    void textIsUtf16AndNumbersKeepIntAndFloat() throws Exception {
        byte[] t = TestTables.table(0x03, List.of(n("ID", 11), c("NAME", 40), f("HRS", 10, 2), n("PLAIN", 5), d("DAY"),
                l("OK"), c("WORKDAYS", 16)), List.<Object[]>of(row(7L, "Ärztin Ωmega", 38.5, "2.50", "2027-01-31", true,
                        latin("1 1 1 1 1 0 0 0"))));
        Map<String, Object> r = Dbf.read(t).get(0);
        assertThat(r.get("ID")).isEqualTo(7L);
        assertThat(r.get("NAME")).isEqualTo("Ärztin Ωmega");
        assertThat(r.get("HRS")).isEqualTo(38.5);
        assertThat(r.get("PLAIN")).isEqualTo(2.5); // a point makes a float, as int() fails and float() is used
        assertThat(r.get("DAY")).isEqualTo("2027-01-31");
        assertThat(r.get("OK")).isEqualTo(true);
        assertThat(r.get("WORKDAYS")).isEqualTo("1 1 1 1 1 0 0 0");
    }

    @Test
    void oneCharacterUtf16TextIsNotTakenForCodePageText() throws Exception {
        // "F\0\0\0  ": sp5lib's heuristic sees only one high byte of three and would decode "F" plus garbage
        byte[] t = TestTables.table(0x03, List.of(c("SHORTNAME", 8)), List.<Object[]>of(row(new byte[] { 'F', 0, 0, 0, ' ', ' ',
                ' ', ' ' })));
        assertThat(Dbf.read(t).get(0).get("SHORTNAME")).isEqualTo("F");
    }

    @Test
    void codePageTextFollowsHeaderByte29() throws Exception {
        List<TestTables.Field> fields = List.of(c("NAME", 8));
        List<Object[]> rows = List.<Object[]>of(row(new byte[] { 'M', (byte) 0xE4, 'r', 'z', (byte) 0x81 }));
        // cp1252: 0xE4 is ä, 0x81 is not defined and becomes U+FFFD (errors="replace")
        assertThat(Dbf.read(TestTables.table(0x03, fields, rows)).get(0).get("NAME")).isEqualTo("März�");
        // cp850: 0x84 is ä
        List<Object[]> dos = List.<Object[]>of(row(new byte[] { 'M', (byte) 0x84, 'r', 'z' }));
        assertThat(Dbf.read(TestTables.table(0x02, fields, dos)).get(0).get("NAME")).isEqualTo("März");
        // no code page (0): Latin-1, every byte kept
        assertThat(Dbf.read(TestTables.table(0x00, fields, rows)).get(0).get("NAME")).isEqualTo("März\u0081");
        assertThatThrownBy(() -> Dbf.read(TestTables.table(0x7F, fields, rows))).isInstanceOf(Dbf.DbfError.class)
                .hasMessage("unbekannte Codepage (Dateikopf Byte 29 = 0x7F)");
    }

    @Test
    void deletedRecordsAreSkipped() throws Exception {
        byte[] t = TestTables.table(0x03, List.of(n("ID", 3)), List.of(row(1L), row(TestTables.DELETED, 2L), row(3L)));
        assertThat(Dbf.read(t)).extracting(r -> r.get("ID")).containsExactly(1L, 3L);
    }

    @Test
    void damagedTablesAreErrors() {
        byte[] t = TestTables.table(0x03, List.of(n("ID", 3)), List.of(row(1L), row(2L), row(3L)));
        assertThatThrownBy(() -> Dbf.read(Arrays.copyOf(t, 20))).hasMessage("beschädigt (Dateikopf unvollständig)");
        assertThatThrownBy(() -> Dbf.read(Arrays.copyOf(t, t.length - 5)))
                .hasMessage("unvollständig: laut Dateikopf 3 Datensätze, vorhanden 2");
        byte[] longer = Arrays.copyOf(t, t.length + 4);
        longer[t.length - 1] = ' '; // the end mark replaced by a fourth record
        assertThatThrownBy(() -> Dbf.read(longer)).hasMessage("laut Dateikopf 3 Datensätze, die Datei enthält mehr");
        byte[] wrongSize = t.clone();
        wrongSize[10] = 9;
        assertThatThrownBy(() -> Dbf.read(wrongSize))
                .hasMessage("beschädigt (Feldbeschreibung passt nicht zur Satzlänge)");
    }

    @Test
    void numbersAsPythonParsesThem() {
        assertThat(Dbf.pyInt("1_000")).isEqualTo(1000L);
        assertThat(Dbf.pyInt("12x")).isNull();
        assertThat(Dbf.pyFloat("1e3")).isEqualTo(1000.0);
        assertThat(Dbf.pyFloat("-inf")).isEqualTo(Double.NEGATIVE_INFINITY);
        assertThat(Dbf.pyFloat(".")).isNull();
        assertThat(Dbf.parseDate("20230231")).isNull();
        assertThat(Dbf.parseDate("20240229")).isEqualTo("2024-02-29");
    }
}
