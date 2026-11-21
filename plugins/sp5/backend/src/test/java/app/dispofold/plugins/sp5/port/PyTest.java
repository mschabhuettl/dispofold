package app.dispofold.plugins.sp5.port;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** The Python semantics of the port, with the values Python 3.14 gives (in the comments where not obvious). */
class PyTest {

    @Test
    void floatRepr() {
        assertThat(Py.floatRepr(1e16)).isEqualTo("1e+16");
        assertThat(Py.floatRepr(1e15)).isEqualTo("1000000000000000.0");
        assertThat(Py.floatRepr(1e-05)).isEqualTo("1e-05");
        assertThat(Py.floatRepr(0.0001)).isEqualTo("0.0001");
        assertThat(Py.floatRepr(0.1)).isEqualTo("0.1");
        assertThat(Py.floatRepr(40.0)).isEqualTo("40.0");
        assertThat(Py.floatRepr(-0.0)).isEqualTo("-0.0");
        assertThat(Py.floatRepr(5e-324)).isEqualTo("5e-324"); // Java's Double.toString says 4.9E-324
        assertThat(Py.floatRepr(1.0 / 3)).isEqualTo("0.3333333333333333");
        assertThat(Py.floatRepr(Double.POSITIVE_INFINITY)).isEqualTo("inf");
    }

    @Test
    void roundAndFormatAreHalfEvenOnTheBinaryValue() {
        assertThat(Py.round(2.675, 2)).isEqualTo(2.67); // 2.675 is 2.67499999... in binary
        assertThat(Py.round(0.125, 2)).isEqualTo(0.12);
        assertThat(Py.round(0.375, 2)).isEqualTo(0.38);
        assertThat(Py.floatRepr(Py.round(-0.001, 2))).isEqualTo("-0.0");
        assertThat(Py.fixed(2.675, 2)).isEqualTo("2.67");
        assertThat(Py.fixed(0.25, 1)).isEqualTo("0.2");
        assertThat(Py.fixed(0.35, 1)).isEqualTo("0.3"); // 0.34999...
        assertThat(Py.fixed(-0.04, 1)).isEqualTo("-0.0");
        assertThat(Py.fixed(-0.0, 1)).isEqualTo("-0.0");
        assertThat(Py.fixed(3L, 3)).isEqualTo("3.000");
    }

    @Test
    void floorDivisionAndModulo() {
        assertThat(Py.floorDiv(-7, 2)).isEqualTo(-4);
        assertThat(Py.mod(-1, 7)).isEqualTo(6);
    }

    @Test
    void sumCompensatesFloatsAsCPython() {
        assertThat(Py.sum(List.of(0.1, 0.2, 0.3))).isEqualTo(0.6); // a plain loop gives 0.6000000000000001
        assertThat(Py.sum(Arrays.asList(0.1, 10000000000000000L, 0.1, -10000000000000000L))).isEqualTo(0.2);
        assertThat(Py.sum(Arrays.asList(1L, 1e100, 1L, -1e100))).isEqualTo(1.0);
        assertThat(Py.sum(List.of(1L, 2L))).isEqualTo(3L);
        assertThat(Py.sum(List.of())).isEqualTo(0L);
    }

    @Test
    void stripAndSplitUsePythonsWhitespace() {
        assertThat(Py.strip("\u001c a   ")).isEqualTo("a");
        assertThat(Py.strip("​")).isEqualTo("​"); // zero width space is not whitespace
        assertThat(Sp5Calc.splitWhitespace(" 1\u00851  0 ")).containsExactly("1", "1", "0");
    }

    @Test
    void reprOfValues() {
        assertThat(Py.strRepr("A2")).isEqualTo("'A2'");
        assertThat(Py.strRepr("it's")).isEqualTo("\"it's\"");
        assertThat(Py.strRepr("a'b\"c")).isEqualTo("'a\\'b\"c'");
        assertThat(Py.strRepr("ä\n\u0085​\t")).isEqualTo("'ä\\n\\x85\\u200b\\t'");
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("x", null);
        m.put("y", List.of(true, 1.5, 3L));
        assertThat(Py.repr(m)).isEqualTo("{'x': None, 'y': [True, 1.5, 3]}");
    }

    @Test
    void intAndFloatOfValues() {
        assertThat(Py.pyInt(" 12 ")).isEqualTo(12L);
        assertThat(Py.pyInt("١٢")).isEqualTo(12L); // Arabic-Indic digits
        assertThat(Py.pyInt(2.9)).isEqualTo(2L);
        assertThat(Py.pyInt(true)).isEqualTo(1L);
        assertThatThrownBy(() -> Py.pyInt("1.5")).isInstanceOf(Py.Raised.class).hasMessage("ValueError");
        assertThat(Py.pyFloat(" 1_0.5 ")).isEqualTo(10.5);
    }

    @Test
    void equalityAndOrderAcrossNumbers() {
        assertThat(Py.eq(1L, 1.0)).isTrue();
        assertThat(Py.eq(true, 1L)).isTrue();
        assertThat(Py.eq(Double.NaN, Double.NaN)).isFalse();
        assertThat(Py.key(5.0)).isEqualTo(5L);
        assertThat(Py.compare(new BigInteger("100000000000000000000"), 1e20)).isZero();
        assertThat(Py.compare(Arrays.asList("E1", 2L), Arrays.asList("E1", 10L))).isNegative();
        assertThat(Py.compareStr("￿", "😀")).isNegative(); // by code point, not UTF-16 unit
        List<Object> mixed = new ArrayList<>(List.of("a", 1L));
        assertThatThrownBy(() -> mixed.sort(Py::compare)).isInstanceOf(Py.Raised.class);
    }
}
