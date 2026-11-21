package app.dispofold.plugins.sp5.port;

import java.util.List;

/**
 * csv.writer(f, delimiter=";") of Python: quote character ", doubled inside, quoted only when needed (delimiter,
 * quote, \r or \n in the field, or a row that is one empty field), lines end with \r\n. None is the empty field,
 * numbers as str().
 */
public final class PyCsv {

    private PyCsv() {
    }

    public static void row(StringBuilder out, List<?> fields) {
        if (fields.size() == 1 && (fields.get(0) == null || "".equals(fields.get(0)))) {
            out.append("\"\"\r\n");
            return;
        }
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                out.append(';');
            }
            Object v = fields.get(i);
            String s = v == null ? "" : v instanceof Boolean b ? (b ? "True" : "False")
                    : (v instanceof Double || v instanceof Long || v instanceof Integer) ? Py.str(v) : v.toString();
            if (s.indexOf(';') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\r') >= 0 || s.indexOf('\n') >= 0) {
                out.append('"').append(s.replace("\"", "\"\"")).append('"');
            } else {
                out.append(s);
            }
        }
        out.append("\r\n");
    }
}
