package app.dispofold.plugins.sp5.port;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Python semantics the port needs to give the same bytes: float repr, round, str.strip, floor division. */
public final class Py {

    private Py() {
    }

    /** repr(float): the shortest digits that round-trip, fixed notation for exponents -4 to 15, else e-notation. */
    public static String floatRepr(double d) {
        if (Double.isNaN(d)) {
            return "NaN";
        }
        if (Double.isInfinite(d)) {
            return d > 0 ? "Infinity" : "-Infinity";
        }
        if (d == 0) {
            return (1 / d < 0) ? "-0.0" : "0.0";
        }
        // Java 19 and later: Double.toString gives the shortest digits that round-trip, as Python's repr
        BigDecimal b = new BigDecimal(Double.toString(d)).stripTrailingZeros();
        String digits = b.unscaledValue().abs().toString();
        int exp = digits.length() - 1 - b.scale(); // decimal exponent of the first digit
        String sign = d < 0 ? "-" : "";
        if (exp >= -5 + 1 && exp < 16) {
            // fixed: Python uses it for -4 <= exp < 16
            if (exp < 0) {
                return sign + "0." + "0".repeat(-exp - 1) + digits;
            }
            if (digits.length() <= exp + 1) {
                return sign + digits + "0".repeat(exp + 1 - digits.length()) + ".0";
            }
            return sign + digits.substring(0, exp + 1) + "." + digits.substring(exp + 1);
        }
        String mantissa = digits.length() == 1 ? digits : digits.charAt(0) + "." + digits.substring(1);
        return sign + mantissa + "e" + (exp < 0 ? "-" : "+") + (Math.abs(exp) < 10 ? "0" : "") + Math.abs(exp);
    }

    /** str(x) of a Python number held as Long or Double. */
    public static String str(Object number) {
        return number instanceof Double d ? floatRepr(d) : String.valueOf(number);
    }

    /** round(x, n) of a float: the exact binary value rounded half to even, as Python does. */
    public static double round(double x, int n) {
        if (Double.isNaN(x) || Double.isInfinite(x)) {
            return x;
        }
        return new BigDecimal(x).setScale(n, RoundingMode.HALF_EVEN).doubleValue();
    }

    /** round(x) of a float: an int, half to even. */
    public static long round(double x) {
        return new BigDecimal(x).setScale(0, RoundingMode.HALF_EVEN).longValueExact();
    }

    /** f"{x:.nf}". */
    public static String fixed(double x, int n) {
        return new BigDecimal(x).setScale(n, RoundingMode.HALF_EVEN).toPlainString();
    }

    /** a // b for ints. */
    public static long floorDiv(long a, long b) {
        return Math.floorDiv(a, b);
    }

    /** a % b for ints (sign of b). */
    public static long mod(long a, long b) {
        return Math.floorMod(a, b);
    }

    /** str.isspace() of one character (Python's whitespace: Unicode White_Space plus the separators 0x1C-0x1F). */
    public static boolean isSpace(int c) {
        return switch (c) {
            case 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x1C, 0x1D, 0x1E, 0x1F, 0x20, 0x85, 0xA0, 0x1680, 0x2028, 0x2029,
                    0x202F, 0x205F, 0x3000 -> true;
            default -> c >= 0x2000 && c <= 0x200A;
        };
    }

    /** str.strip(). */
    public static String strip(String s) {
        int a = 0;
        int b = s.length();
        while (a < b && isSpace(s.charAt(a))) {
            a++;
        }
        while (b > a && isSpace(s.charAt(b - 1))) {
            b--;
        }
        return s.substring(a, b);
    }
}
