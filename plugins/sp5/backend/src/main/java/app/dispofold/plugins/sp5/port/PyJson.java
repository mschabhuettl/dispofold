package app.dispofold.plugins.sp5.port;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON exactly as Python's {@code json.dump(value, f, ensure_ascii=False, indent=1)} writes it, for values made of
 * {@link Map} (insertion order), {@link List}, {@link String}, {@link Long}, {@link Integer} or {@link BigInteger} (Python int),
 * {@link Double} (Python float), {@link Boolean} and null. The SP5 import produces such values so that its files are
 * byte for byte the ones of the Python program.
 */
public final class PyJson {

    private PyJson() {
    }

    public static String dump(Object value) {
        StringBuilder out = new StringBuilder();
        write(out, value, 0);
        return out.toString();
    }

    private static void write(StringBuilder out, Object value, int depth) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof Boolean b) {
            out.append(b ? "true" : "false");
        } else if (value instanceof String s) {
            string(out, s);
        } else if (value instanceof Double d) {
            out.append(Double.isNaN(d) ? "NaN" : Double.isInfinite(d) ? (d > 0 ? "Infinity" : "-Infinity")
                    : Py.floatRepr(d));
        } else if (value instanceof Long || value instanceof Integer || value instanceof BigInteger) {
            out.append(value);
        } else if (value instanceof Map<?, ?> map) {
            if (map.isEmpty()) {
                out.append("{}");
                return;
            }
            out.append('{');
            Iterator<? extends Map.Entry<?, ?>> it = map.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<?, ?> e = it.next();
                newline(out, depth + 1);
                string(out, String.valueOf(e.getKey()));
                out.append(": ");
                write(out, e.getValue(), depth + 1);
                if (it.hasNext()) {
                    out.append(',');
                }
            }
            newline(out, depth);
            out.append('}');
        } else if (value instanceof List<?> list) {
            if (list.isEmpty()) {
                out.append("[]");
                return;
            }
            out.append('[');
            for (int i = 0; i < list.size(); i++) {
                newline(out, depth + 1);
                write(out, list.get(i), depth + 1);
                if (i + 1 < list.size()) {
                    out.append(',');
                }
            }
            newline(out, depth);
            out.append(']');
        } else {
            throw new IllegalArgumentException("no JSON form for " + value.getClass().getSimpleName());
        }
    }

    private static void newline(StringBuilder out, int depth) {
        out.append('\n');
        out.append(" ".repeat(depth));
    }

    /** As json.encoder.py_encode_basestring (ensure_ascii=False). */
    static void string(StringBuilder out, String s) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    /** json.load failed; `type` is the Python exception name (JSONDecodeError, UnicodeDecodeError, ValueError). */
    public static final class DecodeError extends Exception {
        private final String type;

        DecodeError(String type) {
            super(type);
            this.type = type;
        }

        public String type() {
            return type;
        }
    }

    /** json.load of a file opened with encoding="utf-8": the bytes must be UTF-8, then as {@link #load(String)}. */
    public static Object load(byte[] data) throws DecodeError {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString();
        } catch (CharacterCodingException e) {
            throw new DecodeError("UnicodeDecodeError");
        }
        return load(text);
    }

    /**
     * json.loads of Python: objects in key order (a repeated key keeps its first place and takes the last value),
     * int as Long (BigInteger when larger), float as Double, NaN, Infinity and -Infinity accepted, no BOM.
     */
    public static Object load(String s) throws DecodeError {
        Parser p = new Parser(s);
        p.ws();
        Object value = p.value();
        p.ws();
        if (p.pos != s.length()) {
            throw new DecodeError("JSONDecodeError");
        }
        return value;
    }

    private static final class Parser {
        final String s;
        int pos;

        Parser(String s) {
            this.s = s;
        }

        void ws() {
            while (pos < s.length() && " \t\n\r".indexOf(s.charAt(pos)) >= 0) {
                pos++;
            }
        }

        DecodeError fail() {
            return new DecodeError("JSONDecodeError");
        }

        boolean at(String word) {
            if (s.startsWith(word, pos)) {
                pos += word.length();
                return true;
            }
            return false;
        }

        Object value() throws DecodeError {
            if (pos >= s.length()) {
                throw fail();
            }
            char c = s.charAt(pos);
            switch (c) {
                case '"' -> {
                    pos++;
                    return string();
                }
                case '{' -> {
                    pos++;
                    return object();
                }
                case '[' -> {
                    pos++;
                    return array();
                }
                default -> {
                    if (at("null")) {
                        return null;
                    }
                    if (at("true")) {
                        return Boolean.TRUE;
                    }
                    if (at("false")) {
                        return Boolean.FALSE;
                    }
                    if (at("NaN")) {
                        return Double.NaN;
                    }
                    if (at("Infinity")) {
                        return Double.POSITIVE_INFINITY;
                    }
                    if (at("-Infinity")) {
                        return Double.NEGATIVE_INFINITY;
                    }
                    return number();
                }
            }
        }

        Object number() throws DecodeError {
            int start = pos;
            if (pos < s.length() && s.charAt(pos) == '-') {
                pos++;
            }
            if (pos < s.length() && s.charAt(pos) == '0') {
                pos++;
            } else if (pos < s.length() && s.charAt(pos) >= '1' && s.charAt(pos) <= '9') {
                while (pos < s.length() && isDigit(s.charAt(pos))) {
                    pos++;
                }
            } else {
                pos = start;
                throw fail();
            }
            int intEnd = pos;
            boolean isFloat = false;
            if (pos + 1 < s.length() && s.charAt(pos) == '.' && isDigit(s.charAt(pos + 1))) {
                pos += 2;
                while (pos < s.length() && isDigit(s.charAt(pos))) {
                    pos++;
                }
                isFloat = true;
            }
            if (pos < s.length() && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
                int e = pos + 1;
                if (e < s.length() && (s.charAt(e) == '+' || s.charAt(e) == '-')) {
                    e++;
                }
                if (e < s.length() && isDigit(s.charAt(e))) {
                    while (e < s.length() && isDigit(s.charAt(e))) {
                        e++;
                    }
                    pos = e;
                    isFloat = true;
                }
            }
            String text = s.substring(start, pos);
            if (isFloat) {
                return Double.parseDouble(text);
            }
            if (intEnd - start > 4300) {
                throw new DecodeError("ValueError"); // int() refuses more than 4300 digits
            }
            BigInteger b = new BigInteger(text);
            return b.bitLength() < 64 ? (Object) b.longValue() : b;
        }

        static boolean isDigit(char c) {
            return c >= '0' && c <= '9';
        }

        String string() throws DecodeError {
            StringBuilder b = new StringBuilder();
            while (true) {
                if (pos >= s.length()) {
                    throw fail();
                }
                char c = s.charAt(pos++);
                if (c == '"') {
                    return b.toString();
                }
                if (c < 0x20) {
                    throw fail(); // strict: no control characters
                }
                if (c != '\\') {
                    b.append(c);
                    continue;
                }
                if (pos >= s.length()) {
                    throw fail();
                }
                char e = s.charAt(pos++);
                switch (e) {
                    case '"' -> b.append('"');
                    case '\\' -> b.append('\\');
                    case '/' -> b.append('/');
                    case 'b' -> b.append('\b');
                    case 'f' -> b.append('\f');
                    case 'n' -> b.append('\n');
                    case 'r' -> b.append('\r');
                    case 't' -> b.append('\t');
                    case 'u' -> {
                        int u = hex4();
                        if (u >= 0xD800 && u <= 0xDBFF && s.startsWith("\\u", pos)) {
                            int save = pos;
                            pos += 2;
                            int low = hex4();
                            if (low >= 0xDC00 && low <= 0xDFFF) {
                                b.append((char) u).append((char) low);
                                continue;
                            }
                            pos = save;
                        }
                        b.append((char) u);
                    }
                    default -> throw fail();
                }
            }
        }

        int hex4() throws DecodeError {
            if (pos + 4 > s.length()) {
                throw fail();
            }
            int v = 0;
            for (int i = 0; i < 4; i++) {
                int d = Character.digit(s.charAt(pos + i), 16);
                if (d < 0 || s.charAt(pos + i) > 0x7F) {
                    throw fail();
                }
                v = v * 16 + d;
            }
            pos += 4;
            return v;
        }

        Map<String, Object> object() throws DecodeError {
            Map<String, Object> m = new LinkedHashMap<>();
            ws();
            if (pos < s.length() && s.charAt(pos) == '}') {
                pos++;
                return m;
            }
            while (true) {
                if (pos >= s.length() || s.charAt(pos) != '"') {
                    throw fail();
                }
                pos++;
                String key = string();
                ws();
                if (pos >= s.length() || s.charAt(pos) != ':') {
                    throw fail();
                }
                pos++;
                ws();
                m.put(key, value());
                ws();
                if (pos < s.length() && s.charAt(pos) == '}') {
                    pos++;
                    return m;
                }
                if (pos >= s.length() || s.charAt(pos) != ',') {
                    throw fail();
                }
                pos++;
                ws();
            }
        }

        List<Object> array() throws DecodeError {
            List<Object> l = new ArrayList<>();
            ws();
            if (pos < s.length() && s.charAt(pos) == ']') {
                pos++;
                return l;
            }
            while (true) {
                l.add(value());
                ws();
                if (pos < s.length() && s.charAt(pos) == ']') {
                    pos++;
                    return l;
                }
                if (pos >= s.length() || s.charAt(pos) != ',') {
                    throw fail();
                }
                pos++;
                ws();
            }
        }
    }
}
