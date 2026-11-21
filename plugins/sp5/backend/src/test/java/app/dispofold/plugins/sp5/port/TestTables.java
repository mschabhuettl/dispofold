package app.dispofold.plugins.sp5.port;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Writes small synthetic tables in the file format SP5 uses (dBASE): text as UTF-16LE with a two-byte terminator and
 * space padding, numbers right-aligned, dates as YYYYMMDD. For the tests of the port only.
 */
final class TestTables {

    record Field(String name, char type, int len, int dec) {
    }

    /** Marks a record as deleted when it is the first value of a row. */
    static final Object DELETED = new Object();

    private TestTables() {
    }

    static Field c(String name, int len) {
        return new Field(name, 'C', len, 0);
    }

    static Field n(String name, int len) {
        return new Field(name, 'N', len, 0);
    }

    static Field f(String name, int len, int dec) {
        return new Field(name, 'F', len, dec);
    }

    static Field d(String name) {
        return new Field(name, 'D', 8, 0);
    }

    static Field l(String name) {
        return new Field(name, 'L', 1, 0);
    }

    /** The bytes of a table: header (code page in byte 29), field descriptors, records, end mark. */
    static byte[] table(int codePage, List<Field> fields, List<Object[]> rows) {
        int recordSize = 1 + fields.stream().mapToInt(Field::len).sum();
        int headerSize = 32 + 32 * fields.size() + 1;
        ByteBuffer header = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
        header.put(0, (byte) 0x03);
        header.putInt(4, rows.size());
        header.putShort(8, (short) headerSize);
        header.putShort(10, (short) recordSize);
        header.put(29, (byte) codePage);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(header.array());
        for (Field f : fields) {
            byte[] desc = new byte[32];
            byte[] name = f.name().getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(name, 0, desc, 0, name.length);
            desc[11] = (byte) f.type();
            desc[16] = (byte) f.len();
            desc[17] = (byte) f.dec();
            out.writeBytes(desc);
        }
        out.write(0x0D);
        for (Object[] row : rows) {
            boolean deleted = row.length > 0 && row[0] == DELETED;
            Object[] values = deleted ? Arrays.copyOfRange(row, 1, row.length) : row;
            out.write(deleted ? 0x2A : 0x20);
            for (int i = 0; i < fields.size(); i++) {
                out.writeBytes(value(fields.get(i), i < values.length ? values[i] : null));
            }
        }
        out.write(0x1A);
        return out.toByteArray();
    }

    /** A field value: byte[] as it is, String in C fields as UTF-16LE, numbers right-aligned, dates YYYYMMDD. */
    static byte[] value(Field f, Object v) {
        byte[] b = new byte[f.len()];
        Arrays.fill(b, (byte) 0x20);
        byte[] content;
        if (v == null) {
            content = new byte[0];
        } else if (v instanceof byte[] raw) {
            content = raw;
        } else if (f.type() == 'C') {
            byte[] text = ((String) v).getBytes(StandardCharsets.UTF_16LE);
            content = Arrays.copyOf(text, text.length + 2);
        } else if (f.type() == 'D') {
            content = ((String) v).replace("-", "").getBytes(StandardCharsets.US_ASCII);
        } else if (f.type() == 'L') {
            content = ((Boolean) v ? "T" : "F").getBytes(StandardCharsets.US_ASCII);
        } else {
            String s = v.toString();
            content = (" ".repeat(Math.max(0, f.len() - s.length())) + s).getBytes(StandardCharsets.US_ASCII);
        }
        System.arraycopy(content, 0, b, 0, Math.min(content.length, b.length));
        return b;
    }

    static void write(Path folder, String name, List<Field> fields, Object[]... rows) throws IOException {
        Files.write(folder.resolve(name + ".DBF"), table(0x03, fields, List.of(rows)));
    }

    static Object[] row(Object... values) {
        return values;
    }
}
