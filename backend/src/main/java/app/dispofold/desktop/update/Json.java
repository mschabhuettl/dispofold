package app.dispofold.desktop.update;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON reader and writer for the small files of the desktop mode (bundle.json, state.json, instanz.json).
 * The launcher has no dependencies, so it cannot use Jackson. Objects become {@code LinkedHashMap}, arrays
 * {@code List}, numbers {@code Long} or {@code Double}.
 */
public final class Json {

    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    public static Object parse(String text) {
        Json p = new Json(text);
        p.ws();
        Object v = p.value(0);
        p.ws();
        if (p.i != text.length()) {
            throw p.error("Zeichen nach dem Ende");
        }
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        if (parse(text) instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        throw new IllegalArgumentException("JSON: Objekt erwartet");
    }

    private Object value(int depth) {
        if (depth > 32) {
            throw error("zu tief verschachtelt");
        }
        if (i >= s.length()) {
            throw error("unerwartetes Ende");
        }
        char c = s.charAt(i);
        switch (c) {
            case '{' -> {
                i++;
                Map<String, Object> map = new LinkedHashMap<>();
                ws();
                if (peek('}')) {
                    return map;
                }
                do {
                    ws();
                    String key = string();
                    ws();
                    expect(':');
                    ws();
                    if (map.put(key, value(depth + 1)) != null) {
                        throw error("doppelter Schlüssel");
                    }
                    ws();
                } while (peek(','));
                expect('}');
                return map;
            }
            case '[' -> {
                i++;
                List<Object> list = new ArrayList<>();
                ws();
                if (peek(']')) {
                    return list;
                }
                do {
                    ws();
                    list.add(value(depth + 1));
                    ws();
                } while (peek(','));
                expect(']');
                return list;
            }
            case '"' -> {
                return string();
            }
            default -> {
                if (s.startsWith("true", i)) {
                    i += 4;
                    return Boolean.TRUE;
                }
                if (s.startsWith("false", i)) {
                    i += 5;
                    return Boolean.FALSE;
                }
                if (s.startsWith("null", i)) {
                    i += 4;
                    return null;
                }
                return number();
            }
        }
    }

    private Object number() {
        int start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
            i++;
        }
        String n = s.substring(start, i);
        try {
            if (n.matches("-?\\d{1,18}")) {
                return Long.valueOf(n);
            }
            return Double.valueOf(n);
        } catch (NumberFormatException e) {
            throw error("ungültiger Wert");
        }
    }

    private String string() {
        expect('"');
        StringBuilder b = new StringBuilder();
        while (true) {
            if (i >= s.length()) {
                throw error("Text nicht abgeschlossen");
            }
            char c = s.charAt(i++);
            if (c == '"') {
                return b.toString();
            }
            if (c < 0x20) {
                throw error("Steuerzeichen im Text");
            }
            if (c != '\\') {
                b.append(c);
                continue;
            }
            if (i >= s.length()) {
                throw error("Text nicht abgeschlossen");
            }
            char e = s.charAt(i++);
            switch (e) {
                case '"', '\\', '/' -> b.append(e);
                case 'b' -> b.append('\b');
                case 'f' -> b.append('\f');
                case 'n' -> b.append('\n');
                case 'r' -> b.append('\r');
                case 't' -> b.append('\t');
                case 'u' -> {
                    if (i + 4 > s.length()) {
                        throw error("ungültiges Escape");
                    }
                    try {
                        b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    } catch (NumberFormatException ex) {
                        throw error("ungültiges Escape");
                    }
                    i += 4;
                }
                default -> throw error("ungültiges Escape");
            }
        }
    }

    private void ws() {
        while (i < s.length() && " \t\r\n".indexOf(s.charAt(i)) >= 0) {
            i++;
        }
    }

    private boolean peek(char c) {
        if (i < s.length() && s.charAt(i) == c) {
            i++;
            return true;
        }
        return false;
    }

    private void expect(char c) {
        if (!peek(c)) {
            throw error("'" + c + "' erwartet");
        }
    }

    private IllegalArgumentException error(String what) {
        return new IllegalArgumentException("JSON: " + what + " an Position " + i);
    }

    /** One line without a line break at the end, for line protocols (strings never contain a raw line break). */
    public static String line(Object value) {
        return write(value).replaceAll("\n *", "");
    }

    /** Writes maps, lists, strings, numbers, booleans and null, objects with two-space indentation. */
    public static String write(Object value) {
        StringBuilder b = new StringBuilder();
        write(b, value, "");
        return b.append('\n').toString();
    }

    private static void write(StringBuilder b, Object v, String indent) {
        if (v == null) {
            b.append("null");
        } else if (v instanceof String str) {
            quote(b, str);
        } else if (v instanceof Number || v instanceof Boolean) {
            b.append(v);
        } else if (v instanceof Map<?, ?> map) {
            if (map.isEmpty()) {
                b.append("{}");
                return;
            }
            b.append("{\n");
            Iterator<? extends Map.Entry<?, ?>> it = map.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<?, ?> e = it.next();
                b.append(indent).append("  ");
                quote(b, String.valueOf(e.getKey()));
                b.append(": ");
                write(b, e.getValue(), indent + "  ");
                b.append(it.hasNext() ? ",\n" : "\n");
            }
            b.append(indent).append('}');
        } else if (v instanceof List<?> list) {
            b.append('[');
            for (int k = 0; k < list.size(); k++) {
                if (k > 0) {
                    b.append(", ");
                }
                write(b, list.get(k), indent);
            }
            b.append(']');
        } else {
            throw new IllegalArgumentException("JSON: nicht darstellbar: " + v.getClass().getSimpleName());
        }
    }

    private static void quote(StringBuilder b, String str) {
        b.append('"');
        for (int k = 0; k < str.length(); k++) {
            char c = str.charAt(k);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        b.append('"');
    }
}
