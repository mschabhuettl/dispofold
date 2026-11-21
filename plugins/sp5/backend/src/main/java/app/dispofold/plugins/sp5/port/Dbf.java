package app.dispofold.plugins.sp5.port;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Strict reading of one SP5 table (DBF), the port of sp5-export's sp5export/dbf.py with the field parsing of its
 * vendored sp5lib.dbf_reader. Every problem is an error with a German message that names no field values. A record
 * is a map in field order: text as String, N and F as Long (Python int) or Double (Python float), D as an ISO date
 * String or null, L as Boolean, M as null, the binary C fields as byte[].
 */
public final class Dbf {

    /** The table cannot be read completely. */
    public static final class DbfError extends Exception {
        public DbfError(String message) {
            super(message);
        }
    }

    static final Map<Integer, String> CODE_PAGES = Map.ofEntries(Map.entry(0x00, "ISO-8859-1"),
            Map.entry(0x01, "IBM437"), Map.entry(0x02, "IBM850"), Map.entry(0x03, "windows-1252"),
            Map.entry(0x57, "windows-1252"), Map.entry(0x58, "windows-1252"), Map.entry(0x59, "windows-1252"),
            Map.entry(0x64, "IBM852"), Map.entry(0x65, "IBM866"), Map.entry(0x66, "IBM865"),
            Map.entry(0x67, "IBM861"), Map.entry(0x6A, "x-IBM737"), Map.entry(0x6B, "IBM857"),
            Map.entry(0xC8, "windows-1250"), Map.entry(0xC9, "windows-1251"), Map.entry(0xCA, "windows-1254"),
            Map.entry(0xCB, "windows-1253"));
    static final Set<String> BINARY_C_FIELDS = Set.of("DIGEST", "CREATIME", "UUID");
    static final int EOF_MARK = 0x1A;
    static final int DELETED = 0x2A;
    private static final Pattern PY_INT = Pattern.compile("[+-]?\\d+(?:_\\d+)*");
    private static final Pattern PY_FLOAT = Pattern.compile(
            "[+-]?(?:(?:\\d+(?:_\\d+)*)?\\.?(?:\\d+(?:_\\d+)*)?(?:[eE][+-]?\\d+(?:_\\d+)*)?)");
    private static final Pattern PY_FLOAT_WORD = Pattern.compile("[+-]?(?:inf|infinity|nan)", Pattern.CASE_INSENSITIVE);

    private record Spec(String name, char type, int len, int dec, boolean binary, int offset) {
    }

    private Dbf() {
    }

    public static List<Map<String, Object>> read(Path path) throws DbfError {
        byte[] data;
        try {
            data = Files.readAllBytes(path);
        } catch (IOException e) {
            throw new DbfError("nicht lesbar (" + pyErrorName(e) + ")");
        }
        return read(data);
    }

    /** The Python exception name of an OSError, as far as it can be told. */
    static String pyErrorName(IOException e) {
        if (e instanceof java.nio.file.NoSuchFileException) {
            return "FileNotFoundError";
        }
        if (e instanceof java.nio.file.AccessDeniedException) {
            return "PermissionError";
        }
        return "OSError";
    }

    public static List<Map<String, Object>> read(byte[] data) throws DbfError {
        if (data.length < 32) {
            throw new DbfError("beschädigt (Dateikopf unvollständig)");
        }
        ByteBuffer header = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        long count = Integer.toUnsignedLong(header.getInt(4));
        int headerSize = Short.toUnsignedInt(header.getShort(8));
        int recordSize = Short.toUnsignedInt(header.getShort(10));
        List<String> names = new ArrayList<>();
        List<char[]> types = new ArrayList<>();
        List<int[]> sizes = new ArrayList<>();
        for (int pos = 32; pos < Math.min(headerSize, data.length) - 31; pos += 32) {
            if ((data[pos] & 0xFF) == 0x0D) {
                break;
            }
            int nameEnd = pos;
            while (nameEnd < pos + 11 && data[nameEnd] != 0) {
                nameEnd++;
            }
            names.add(Py.strip(ascii(data, pos, nameEnd)));
            types.add(new char[] { (char) (data[pos + 11] & 0xFF) });
            sizes.add(new int[] { data[pos + 16] & 0xFF, data[pos + 17] & 0xFF });
        }
        int sum = sizes.stream().mapToInt(s -> s[0]).sum();
        if (names.isEmpty() || recordSize != 1 + sum) {
            throw new DbfError("beschädigt (Feldbeschreibung passt nicht zur Satzlänge)");
        }
        long end = headerSize + count * recordSize;
        if (data.length < end) {
            long complete = Math.max(0, data.length - headerSize) / recordSize;
            throw new DbfError("unvollständig: laut Dateikopf " + count + " Datensätze, vorhanden " + complete);
        }
        if (data.length > end && (data[(int) end] & 0xFF) != EOF_MARK) {
            throw new DbfError("laut Dateikopf " + count + " Datensätze, die Datei enthält mehr");
        }
        String encoding = CODE_PAGES.get(data[29] & 0xFF);
        if (encoding == null) {
            throw new DbfError(String.format(Locale.ROOT, "unbekannte Codepage (Dateikopf Byte 29 = 0x%02X)",
                    data[29] & 0xFF));
        }
        Charset charset = Charset.forName(encoding);
        List<String> keys = dedupe(names);
        List<Spec> specs = new ArrayList<>();
        int offset = 1;
        for (int i = 0; i < names.size(); i++) {
            int len = sizes.get(i)[0];
            specs.add(new Spec(keys.get(i), types.get(i)[0], len, sizes.get(i)[1],
                    BINARY_C_FIELDS.contains(names.get(i)), offset));
            offset += len;
        }
        List<Map<String, Object>> records = new ArrayList<>();
        for (long i = 0; i < count; i++) {
            int start = (int) (headerSize + i * recordSize);
            if ((data[start] & 0xFF) == DELETED) {
                continue;
            }
            Map<String, Object> record = new LinkedHashMap<>();
            for (Spec s : specs) {
                int from = start + s.offset();
                int to = from + s.len();
                record.put(s.name(), field(data, from, to, s));
                if (s.type() == 'C' && !s.binary()) {
                    // sp5lib's heuristic misses one-character UTF-16 text ("F\0\0\0  "): its second byte is a UTF-16
                    // high byte (below 0x08), which code-page text never has
                    int len = to - from;
                    if (len >= 2 && (data[from + 1] & 0xFF) < 0x08 && !(data[from] == 0 && data[from + 1] == 0)
                            && !isUtf16Le(data, from, to)) {
                        record.put(s.name(), Py.strip(new String(data, from, 2, StandardCharsets.UTF_16LE)));
                    } else if (!isUtf16Le(data, from, to)) {
                        int e = to;
                        while (e > from && (data[e - 1] == 0 || data[e - 1] == 0x20)) {
                            e--;
                        }
                        record.put(s.name(), Py.strip(decodeReplace(charset, data, from, e)));
                    }
                }
            }
            records.add(record);
        }
        return records;
    }

    static List<String> dedupe(List<String> names) {
        Map<String, Integer> seen = new HashMap<>();
        List<String> result = new ArrayList<>();
        for (String n : names) {
            int k = seen.merge(n, 1, Integer::sum);
            result.add(k == 1 ? n : n + k);
        }
        return result;
    }

    /** _is_utf16_le of sp5lib on data[from, to). */
    static boolean isUtf16Le(byte[] data, int from, int to) {
        int len = to - from;
        if (len < 4) {
            return len >= 2 && (data[from + 1] & 0xFF) < 0x08;
        }
        int sample = Math.min(8, len);
        int odd = 0;
        int high = 0;
        for (int i = 1; i < sample; i += 2) {
            odd++;
            if ((data[from + i] & 0xFF) < 0x08) {
                high++;
            }
        }
        return high > odd / 2;
    }

    private static Object field(byte[] data, int from, int to, Spec s) {
        switch (s.type()) {
            case 'C' -> {
                if (s.binary()) {
                    return java.util.Arrays.copyOfRange(data, from, to);
                }
                return decodeString(data, from, to);
            }
            case 'D' -> {
                return parseDate(Py.strip(ascii(data, from, to)));
            }
            case 'N', 'F' -> {
                String t = Py.strip(ascii(data, from, to));
                if (t.isEmpty() || t.equals(".")) {
                    return 0L;
                }
                Object v = t.contains(".") || s.dec() > 0 ? pyFloat(t) : pyInt(t);
                return v == null ? 0L : v;
            }
            case 'L' -> {
                String t = Py.strip(ascii(data, from, to));
                return t.equals("T") || t.equals("t") || t.equals("Y") || t.equals("y") || t.equals("1");
            }
            case 'M' -> {
                return null;
            }
            default -> {
                return Py.strip(ascii(data, from, to));
            }
        }
    }

    /** _decode_string of sp5lib. */
    static String decodeString(byte[] data, int from, int to) {
        if (to == from) {
            return "";
        }
        if (isUtf16Le(data, from, to)) {
            int end = to;
            for (int i = from; i < to - 1; i += 2) {
                if (data[i] == 0 && data[i + 1] == 0) {
                    end = i;
                    break;
                }
            }
            if (end == from) {
                return "";
            }
            try {
                return Py.strip(StandardCharsets.UTF_16LE.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(data, from, end - from)).toString());
            } catch (CharacterCodingException e) {
                // as sp5lib: fall through to Latin-1
            }
        }
        int e = to;
        while (e > from && (data[e - 1] == 0 || data[e - 1] == 0x20)) {
            e--;
        }
        return Py.strip(new String(data, from, e - from, StandardCharsets.ISO_8859_1));
    }

    /** bytes.decode(encoding, errors="replace") of a single-byte code page. */
    static String decodeReplace(Charset charset, byte[] data, int from, int to) {
        try {
            return charset.newDecoder().onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE).replaceWith("�")
                    .decode(ByteBuffer.wrap(data, from, to - from)).toString();
        } catch (CharacterCodingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** bytes.decode("ascii", errors="replace"). */
    static String ascii(byte[] data, int from, int to) {
        StringBuilder b = new StringBuilder(to - from);
        for (int i = from; i < to; i++) {
            int c = data[i] & 0xFF;
            b.append(c < 0x80 ? (char) c : '�');
        }
        return b.toString();
    }

    /** _parse_date of sp5lib: an ISO date or null. */
    static String parseDate(String s) {
        if (s.length() != 8 || s.chars().anyMatch(c -> c < '0' || c > '9')) {
            return null;
        }
        int year = Integer.parseInt(s.substring(0, 4));
        int month = Integer.parseInt(s.substring(4, 6));
        int day = Integer.parseInt(s.substring(6, 8));
        if (year <= 0) {
            return null;
        }
        try {
            return LocalDate.of(year, month, day).toString();
        } catch (DateTimeException e) {
            return null;
        }
    }

    /** int(s) of Python, or null where it raises ValueError. */
    static Long pyInt(String s) {
        if (!PY_INT.matcher(s).matches()) {
            return null;
        }
        try {
            return Long.parseLong(s.replace("_", "").replace("+", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** float(s) of Python, or null where it raises ValueError. */
    static Double pyFloat(String s) {
        if (PY_FLOAT_WORD.matcher(s).matches()) {
            String w = s.toLowerCase(Locale.ROOT).replace("+", "");
            boolean neg = w.startsWith("-");
            w = neg ? w.substring(1) : w;
            double v = w.equals("nan") ? Double.NaN : Double.POSITIVE_INFINITY;
            return neg ? -v : v;
        }
        if (!PY_FLOAT.matcher(s).matches() || !s.chars().anyMatch(c -> c >= '0' && c <= '9')) {
            return null;
        }
        String mantissa = s.split("[eE]")[0];
        if (!mantissa.chars().anyMatch(c -> c >= '0' && c <= '9')) {
            return null;
        }
        try {
            return Double.parseDouble(s.replace("_", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
