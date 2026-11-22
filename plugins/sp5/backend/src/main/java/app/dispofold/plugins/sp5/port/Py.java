package app.dispofold.plugins.sp5.port;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Python semantics the port needs to give the same bytes: float repr, round, str.strip, floor division, str() and
 * repr() of values, truthiness, comparison, int() and float() of values. Python values are held as plain Java: dict
 * as Map, list and tuple as List, str as String, int as Long (BigInteger when it does not fit), float as Double, bool
 * as Boolean, None as null, date as LocalDate.
 */
public final class Py {

    private Py() {
    }

    /** An exception Python would raise where the program does not expect one: its type and the place in Python. */
    public static final class PyException extends RuntimeException {
        private final String type;
        private final String file;
        private final int line;

        public PyException(String type, String file, int line) {
            super(type + " in " + file + ":" + line);
            this.type = type;
            this.file = file;
            this.line = line;
        }

        public String type() {
            return type;
        }

        public String file() {
            return file;
        }

        public int line() {
            return line;
        }
    }

    /** A Python exception of `type` without a place yet (filled in by {@link #at}). */
    static final class Raised extends RuntimeException {
        final String type;

        Raised(String type) {
            super(type);
            this.type = type;
        }
    }

    /** Runs `body`; a Python exception raised in it is reported at `file`:`line` (the place Python names). */
    static <T> T at(String file, int line, java.util.function.Supplier<T> body) {
        try {
            return body.get();
        } catch (Raised e) {
            throw new PyException(e.type, file, line);
        }
    }

    /** repr(float): the shortest digits that round-trip, fixed notation for exponents -4 to 15, else e-notation. */
    public static String floatRepr(double d) {
        if (Double.isNaN(d)) {
            return "nan";
        }
        if (Double.isInfinite(d)) {
            return d > 0 ? "inf" : "-inf";
        }
        if (d == 0) {
            return (1 / d < 0) ? "-0.0" : "0.0";
        }
        // Java 19 and later: Double.toString gives the shortest digits that round-trip, as Python's repr, except that
        // it may keep two digits where one would do (it then takes the closer of the two-digit ones)
        BigDecimal b = new BigDecimal(Double.toString(d)).stripTrailingZeros();
        if (b.precision() == 2) {
            BigDecimal one = b.round(new java.math.MathContext(1, RoundingMode.DOWN));
            BigDecimal up = b.round(new java.math.MathContext(1, RoundingMode.UP));
            boolean downOk = one.doubleValue() == d;
            boolean upOk = up.doubleValue() == d;
            if (downOk || upOk) {
                BigDecimal exact = new BigDecimal(d);
                b = !upOk || (downOk && exact.subtract(one).abs().compareTo(up.subtract(exact).abs()) <= 0) ? one : up;
                b = b.stripTrailingZeros();
            }
        }
        String digits = b.unscaledValue().abs().toString();
        int exp = digits.length() - 1 - b.scale(); // decimal exponent of the first digit
        String sign = d < 0 ? "-" : "";
        if (exp >= -4 && exp < 16) {
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

    /** str(x) of a Python value. */
    public static String str(Object v) {
        if (v instanceof String s) {
            return s;
        }
        if (v instanceof Double d) {
            return floatRepr(d);
        }
        if (v instanceof Long || v instanceof Integer || v instanceof BigInteger) {
            return v.toString();
        }
        if (v instanceof LocalDate d) {
            return d.toString();
        }
        return repr(v);
    }

    /** repr(x) of a Python value. */
    public static String repr(Object v) {
        if (v == null) {
            return "None";
        }
        if (v instanceof Boolean b) {
            return b ? "True" : "False";
        }
        if (v instanceof String s) {
            return strRepr(s);
        }
        if (v instanceof Double d) {
            return floatRepr(d);
        }
        if (v instanceof Long || v instanceof Integer || v instanceof BigInteger) {
            return v.toString();
        }
        if (v instanceof LocalDate d) {
            return "datetime.date(" + d.getYear() + ", " + d.getMonthValue() + ", " + d.getDayOfMonth() + ")";
        }
        if (v instanceof Map<?, ?> m) {
            StringBuilder b = new StringBuilder("{");
            Iterator<? extends Map.Entry<?, ?>> it = m.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<?, ?> e = it.next();
                b.append(repr(e.getKey())).append(": ").append(repr(e.getValue()));
                if (it.hasNext()) {
                    b.append(", ");
                }
            }
            return b.append('}').toString();
        }
        if (v instanceof List<?> l) {
            StringBuilder b = new StringBuilder("[");
            for (int i = 0; i < l.size(); i++) {
                b.append(i > 0 ? ", " : "").append(repr(l.get(i)));
            }
            return b.append(']').toString();
        }
        if (v instanceof byte[] bytes) {
            StringBuilder b = new StringBuilder("b'");
            for (byte x : bytes) {
                int c = x & 0xFF;
                if (c == '\'' || c == '\\') {
                    b.append('\\').append((char) c);
                } else if (c == '\t') {
                    b.append("\\t");
                } else if (c == '\n') {
                    b.append("\\n");
                } else if (c == '\r') {
                    b.append("\\r");
                } else if (c < 0x20 || c >= 0x7F) {
                    b.append(String.format(java.util.Locale.ROOT, "\\x%02x", c));
                } else {
                    b.append((char) c);
                }
            }
            return b.append('\'').toString();
        }
        return v.toString();
    }

    /** repr(str): single quotes unless the text has one and no double quote; escapes as Python. */
    public static String strRepr(String s) {
        char quote = s.indexOf('\'') >= 0 && s.indexOf('"') < 0 ? '"' : '\'';
        StringBuilder b = new StringBuilder().append(quote);
        for (int i = 0; i < s.length(); ) {
            int c = s.codePointAt(i);
            i += Character.charCount(c);
            if (c == quote || c == '\\') {
                b.append('\\').appendCodePoint(c);
            } else if (c == '\t') {
                b.append("\\t");
            } else if (c == '\n') {
                b.append("\\n");
            } else if (c == '\r') {
                b.append("\\r");
            } else if (c < 0x20 || c == 0x7F) {
                b.append(String.format(java.util.Locale.ROOT, "\\x%02x", c));
            } else if (c < 0x7F || isPrintable(c)) {
                b.appendCodePoint(c);
            } else if (c <= 0xFF) {
                b.append(String.format(java.util.Locale.ROOT, "\\x%02x", c));
            } else if (c <= 0xFFFF) {
                b.append(String.format(java.util.Locale.ROOT, "\\u%04x", c));
            } else {
                b.append(String.format(java.util.Locale.ROOT, "\\U%08x", c));
            }
        }
        return b.append(quote).toString();
    }

    /** str.isprintable() of one character: not in the categories Cc, Cf, Cs, Co, Cn, Zl, Zp, Zs (except space). */
    static boolean isPrintable(int c) {
        if (c == ' ') {
            return true;
        }
        return switch (Character.getType(c)) {
            case Character.CONTROL, Character.FORMAT, Character.SURROGATE, Character.PRIVATE_USE,
                    Character.UNASSIGNED, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR,
                    Character.SPACE_SEPARATOR -> false;
            default -> true;
        };
    }

    /** round(x, n) of a float: the exact binary value rounded half to even, as Python does (keeps the sign of 0). */
    public static double round(double x, int n) {
        if (Double.isNaN(x) || Double.isInfinite(x)) {
            return x;
        }
        return Math.copySign(new BigDecimal(x).setScale(n, RoundingMode.HALF_EVEN).doubleValue(), x);
    }

    /** round(x) of a float: an int, half to even. */
    public static long round(double x) {
        return new BigDecimal(x).setScale(0, RoundingMode.HALF_EVEN).longValueExact();
    }

    /** f"{x:.nf}". */
    public static String fixed(double x, int n) {
        if (Double.isNaN(x)) {
            return "nan";
        }
        if (Double.isInfinite(x)) {
            return x > 0 ? "inf" : "-inf";
        }
        String s = new BigDecimal(x).setScale(n, RoundingMode.HALF_EVEN).toPlainString();
        return (x < 0 || (x == 0 && 1 / x < 0)) && !s.startsWith("-") ? "-" + s : s;
    }

    /** f"{x:.nf}" of a Python number (int or float). */
    public static String fixed(Object x, int n) {
        return fixed(toDouble(x), n);
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

    /** bool(x). */
    public static boolean truthy(Object v) {
        if (v == null) {
            return false;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof Long l) {
            return l != 0;
        }
        if (v instanceof Integer i) {
            return i != 0;
        }
        if (v instanceof Double d) {
            return d != 0; // NaN is true
        }
        if (v instanceof BigInteger b) {
            return b.signum() != 0;
        }
        if (v instanceof String s) {
            return !s.isEmpty();
        }
        if (v instanceof byte[] b) {
            return b.length > 0;
        }
        if (v instanceof Collection<?> c) {
            return !c.isEmpty();
        }
        if (v instanceof Map<?, ?> m) {
            return !m.isEmpty();
        }
        return true;
    }

    /** `x or 0`. */
    static Object orZero(Object v) {
        return truthy(v) ? v : 0L;
    }

    /** isinstance(v, int) (bool included, as in Python). */
    static boolean isIntLike(Object v) {
        return v instanceof Long || v instanceof Integer || v instanceof BigInteger || v instanceof Boolean;
    }

    /** isinstance(v, int) and not isinstance(v, bool). */
    static boolean isInt(Object v) {
        return v instanceof Long || v instanceof Integer || v instanceof BigInteger;
    }

    /** A Python number (int, float or bool) as BigDecimal for exact comparison; null for NaN, inf or no number. */
    private static BigDecimal exact(Object v) {
        if (v instanceof Boolean b) {
            return b ? BigDecimal.ONE : BigDecimal.ZERO;
        }
        if (v instanceof Long l) {
            return BigDecimal.valueOf(l);
        }
        if (v instanceof Integer i) {
            return BigDecimal.valueOf(i);
        }
        if (v instanceof BigInteger b) {
            return new BigDecimal(b);
        }
        if (v instanceof Double d && Double.isFinite(d)) {
            return new BigDecimal(d);
        }
        return null;
    }

    static boolean isNumber(Object v) {
        return v instanceof Boolean || v instanceof Long || v instanceof Integer || v instanceof BigInteger
                || v instanceof Double;
    }

    /** A value as dict key: numbers that are equal in Python (1, 1.0, True) give the same key. */
    public static Object key(Object v) {
        if (v instanceof Boolean b) {
            return b ? 1L : 0L;
        }
        if (v instanceof Integer i) {
            return (long) i;
        }
        if (v instanceof Double d && Double.isFinite(d) && d == Math.rint(d) && Math.abs(d) < 9.2e18) {
            return (long) (double) d;
        }
        if (v instanceof BigInteger b && b.bitLength() < 64) {
            return b.longValue();
        }
        return v;
    }

    /** x == y of Python for the values of the port. */
    static boolean eq(Object a, Object b) {
        if (isNumber(a) && isNumber(b)) {
            if (a instanceof Double x && Double.isNaN(x) || b instanceof Double y && Double.isNaN(y)) {
                return false;
            }
            if (a instanceof Double x && Double.isInfinite(x) || b instanceof Double y && Double.isInfinite(y)) {
                return toDouble(a) == toDouble(b);
            }
            return exact(a).compareTo(exact(b)) == 0;
        }
        if (a instanceof List<?> x && b instanceof List<?> y) {
            if (x.size() != y.size()) {
                return false;
            }
            for (int i = 0; i < x.size(); i++) {
                if (x.get(i) != y.get(i) && !eq(x.get(i), y.get(i))) {
                    return false;
                }
            }
            return true;
        }
        if (a instanceof Map<?, ?> x && b instanceof Map<?, ?> y) {
            if (x.size() != y.size()) {
                return false;
            }
            for (Map.Entry<?, ?> e : x.entrySet()) {
                if (!y.containsKey(e.getKey()) || !eq(e.getValue(), y.get(e.getKey()))) {
                    return false;
                }
            }
            return true;
        }
        return java.util.Objects.equals(a, b) && !(a instanceof byte[]);
    }

    /** a < b etc. of Python: numbers with numbers, str with str, lists (tuples) element by element, dates. */
    static int compare(Object a, Object b) {
        if (isNumber(a) && isNumber(b)) {
            double x = toDouble(a);
            double y = toDouble(b);
            if (Double.isNaN(x) || Double.isNaN(y)) {
                return 0; // NaN is neither less nor greater; sorting keeps its place as Python's timsort may not
            }
            BigDecimal p = exact(a);
            BigDecimal q = exact(b);
            return p != null && q != null ? p.compareTo(q) : Double.compare(x, y);
        }
        if (a instanceof String x && b instanceof String y) {
            return compareStr(x, y);
        }
        if (a instanceof LocalDate x && b instanceof LocalDate y) {
            return x.compareTo(y);
        }
        if (a instanceof List<?> x && b instanceof List<?> y) {
            for (int i = 0; i < Math.min(x.size(), y.size()); i++) {
                if (!eq(x.get(i), y.get(i))) {
                    return compare(x.get(i), y.get(i));
                }
            }
            return Integer.compare(x.size(), y.size());
        }
        throw new Raised("TypeError");
    }

    /** str comparison by code point. */
    static int compareStr(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            int x = a.codePointAt(i);
            int y = b.codePointAt(j);
            if (x != y) {
                return Integer.compare(x, y);
            }
            i += Character.charCount(x);
            j += Character.charCount(y);
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }

    /**
     * sum(values) of CPython 3.12 and later: ints add up exactly until the first float, from there on a float with
     * Neumaier's compensation (ints included as floats). Returns Long, BigInteger or Double.
     */
    static Object sum(Iterable<?> values) {
        BigInteger exactSum = BigInteger.ZERO;
        Iterator<?> it = values.iterator();
        double f = 0;
        boolean isFloat = false;
        while (it.hasNext()) {
            Object v = it.next();
            if (isIntLike(v)) {
                exactSum = exactSum.add(v instanceof BigInteger b ? b : BigInteger.valueOf(pyInt(v)));
                continue;
            }
            f = toDouble(exactSum) + toDouble(v);
            isFloat = true;
            break;
        }
        if (!isFloat) {
            return exactSum.bitLength() < 64 ? (Object) exactSum.longValue() : exactSum;
        }
        double c = 0;
        while (it.hasNext()) {
            double x = toDouble(it.next());
            double t = f + x;
            if (Math.abs(f) >= Math.abs(x)) {
                c += (f - t) + x;
            } else {
                c += (x - t) + f;
            }
            f = t;
        }
        if (c != 0 && Double.isFinite(c)) {
            f += c;
        }
        return f;
    }

    /** float(x) of a number (bool and int included). */
    static double toDouble(Object v) {
        if (v instanceof Double d) {
            return d;
        }
        if (v instanceof Long l) {
            return l;
        }
        if (v instanceof Integer i) {
            return i;
        }
        if (v instanceof Boolean b) {
            return b ? 1 : 0;
        }
        if (v instanceof BigInteger b) {
            double d = b.doubleValue();
            if (Double.isInfinite(d)) {
                throw new Raised("OverflowError");
            }
            return d;
        }
        throw new Raised("TypeError");
    }

    /** float(x) of any value: numbers, and str as Python parses it; raises ValueError or TypeError as Python. */
    static double pyFloat(Object v) {
        if (v instanceof String s) {
            Double d = Dbf.pyFloat(strip(digitsToAscii(s)));
            if (d == null) {
                throw new Raised("ValueError");
            }
            return d;
        }
        return toDouble(v);
    }

    /** int(x) of any value: truncates floats, parses str as Python; raises ValueError, TypeError or OverflowError. */
    static long pyInt(Object v) {
        if (v instanceof Long l) {
            return l;
        }
        if (v instanceof Integer i) {
            return i;
        }
        if (v instanceof Boolean b) {
            return b ? 1 : 0;
        }
        if (v instanceof Double d) {
            if (Double.isNaN(d)) {
                throw new Raised("ValueError");
            }
            if (Double.isInfinite(d)) {
                throw new Raised("OverflowError");
            }
            return (long) (double) d;
        }
        if (v instanceof String s) {
            Long l = Dbf.pyInt(strip(digitsToAscii(s)));
            if (l == null) {
                throw new Raised("ValueError");
            }
            return l;
        }
        if (v instanceof BigInteger b) {
            return b.longValue();
        }
        throw new Raised("TypeError");
    }

    /** Unicode decimal digits as ASCII digits (int() and float() of str accept them). */
    private static String digitsToAscii(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); ) {
            int c = s.codePointAt(i);
            i += Character.charCount(c);
            if (c > 0x7F && Character.getType(c) == Character.DECIMAL_DIGIT_NUMBER) {
                b.append((char) ('0' + Character.digit(c, 10)));
            } else {
                b.appendCodePoint(c);
            }
        }
        return b.toString();
    }
}
