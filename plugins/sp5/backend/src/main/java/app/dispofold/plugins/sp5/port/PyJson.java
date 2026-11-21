package app.dispofold.plugins.sp5.port;

import java.math.BigDecimal;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * JSON exactly as Python's {@code json.dump(value, f, ensure_ascii=False, indent=1)} writes it, for values made of
 * {@link Map} (insertion order), {@link List}, {@link String}, {@link Long} or {@link Integer} (Python int),
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
            out.append(Py.floatRepr(d));
        } else if (value instanceof Float f) {
            out.append(Py.floatRepr(f.doubleValue()));
        } else if (value instanceof Long || value instanceof Integer || value instanceof Short) {
            out.append(value);
        } else if (value instanceof BigDecimal b) {
            out.append(Py.floatRepr(b.doubleValue()));
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
}
