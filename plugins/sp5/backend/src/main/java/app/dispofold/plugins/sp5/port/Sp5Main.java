package app.dispofold.plugins.sp5.port;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The command line of sp5_export.py: the same options, defaults, checks, messages (German, on stderr), exit codes and
 * files (written as .tmp first, then moved into place; removed when the export fails). Usage errors exit with 2 like
 * argparse, with a shorter text.
 */
public final class Sp5Main {

    private static final List<String> VALUE_OPTIONS = List.of("--data", "--month", "--to", "--history-months",
            "--areas-from-history", "--shifts-from-history", "--demand", "--mix-min-duties", "--hidden-shifts-months",
            "--recent-months", "--report", "--corrections", "--mapping", "--out");
    private static final List<String> INT_OPTIONS = List.of("--history-months", "--areas-from-history",
            "--shifts-from-history", "--mix-min-duties", "--hidden-shifts-months", "--recent-months");
    private static final List<String> FLAG_OPTIONS = List.of("--no-shift-mix", "--help");
    private static final List<String> REQUIRED = List.of("--data", "--month", "--mapping", "--out");
    private static final Pattern NEGATIVE_NUMBER = Pattern.compile("^-\\d+$|^-\\d*\\.\\d+$");
    private static final String USAGE = "usage: sp5_export.py [-h] --data DATA --month MONTH [--to TO] "
            + "[--history-months HISTORY_MONTHS] [--areas-from-history N] [--shifts-from-history N] "
            + "[--demand {history,sp5}] [--mix-min-duties N] [--hidden-shifts-months N] [--recent-months N] "
            + "[--no-shift-mix] [--report REPORT] [--corrections CORRECTIONS] --mapping MAPPING --out OUT";

    private Sp5Main() {
    }

    /** argparse gave up: usage and message on stderr, exit code 2. */
    static final class UsageError extends Exception {
        UsageError(String message) {
            super(message);
        }
    }

    public static void main(String[] args) {
        PrintStream err = new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8);
        int code = run(args, err);
        err.flush();
        System.exit(code);
    }

    private static int fail(PrintStream err, String text) {
        err.print("Fehler: " + text + "\n");
        return 1;
    }

    /** The long option an argument names (exact or a unique prefix, as argparse), or a usage error. */
    private static String option(String arg) throws UsageError {
        List<String> all = new ArrayList<>(VALUE_OPTIONS);
        all.addAll(FLAG_OPTIONS);
        if (arg.equals("-h")) {
            return "--help";
        }
        if (all.contains(arg)) {
            return arg;
        }
        List<String> matches = all.stream().filter(o -> arg.startsWith("--") && o.startsWith(arg)).toList();
        if (matches.size() == 1) {
            return matches.get(0);
        }
        if (matches.size() > 1) {
            throw new UsageError("ambiguous option: " + arg + " could match " + String.join(", ", matches));
        }
        return null;
    }

    /** The options as argparse reads them: option -> value (a flag maps to ""). */
    static Map<String, String> parse(String[] args) throws UsageError {
        Map<String, String> values = new LinkedHashMap<>();
        List<String> extra = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("-") || arg.equals("-") || NEGATIVE_NUMBER.matcher(arg).matches()) {
                extra.add(arg);
                continue;
            }
            if (arg.equals("--")) {
                extra.addAll(List.of(args).subList(i + 1, args.length));
                break;
            }
            String name = arg;
            String inline = null;
            int eq = arg.indexOf('=');
            if (arg.startsWith("--") && eq > 0) {
                name = arg.substring(0, eq);
                inline = arg.substring(eq + 1);
            }
            String opt = option(name);
            if (opt == null) {
                extra.add(arg);
                continue;
            }
            if (FLAG_OPTIONS.contains(opt)) {
                if (inline != null) {
                    throw new UsageError("argument " + opt + ": ignored explicit argument '" + inline + "'");
                }
                values.put(opt, "");
                continue;
            }
            String value = inline;
            if (value == null) {
                if (i + 1 >= args.length || (args[i + 1].startsWith("-") && !args[i + 1].equals("-")
                        && !NEGATIVE_NUMBER.matcher(args[i + 1]).matches())) {
                    throw new UsageError("argument " + opt + ": expected one argument");
                }
                value = args[++i];
            }
            if (INT_OPTIONS.contains(opt)) {
                long number;
                try {
                    number = Py.pyInt(value);
                } catch (Py.Raised e) {
                    throw new UsageError("argument " + opt + ": invalid int value: " + Py.strRepr(value));
                }
                if (number != (int) number) {
                    // Python takes any int here; the port refuses instead of narrowing the value
                    throw new UsageError("argument " + opt + ": " + value + " is beyond the integers of the port");
                }
            }
            if (opt.equals("--demand") && !Sp5Export.DEMAND_SOURCES.contains(value)) {
                throw new UsageError("argument --demand: invalid choice: " + Py.strRepr(value)
                        + " (choose from history, sp5)");
            }
            values.put(opt, value);
        }
        if (values.containsKey("--help")) {
            return values;
        }
        List<String> missing = REQUIRED.stream().filter(o -> !values.containsKey(o)).toList();
        if (!missing.isEmpty()) {
            throw new UsageError("the following arguments are required: " + String.join(", ", missing));
        }
        if (!extra.isEmpty()) {
            throw new UsageError("unrecognized arguments: " + String.join(" ", extra));
        }
        return values;
    }

    private static Integer intOption(Map<String, String> o, String name, Integer otherwise) {
        return o.containsKey(name) ? Integer.valueOf((int) Py.pyInt(o.get(name))) : otherwise;
    }

    /** Path(text).resolve() of Python: absolute, symbolic links resolved where they exist, ".." taken lexically. */
    static Path resolve(String text) {
        Path abs = Path.of(text).toAbsolutePath();
        Path cur = abs.getRoot();
        for (Path part : abs) {
            String name = part.toString();
            if (name.equals(".") || name.isEmpty()) {
                continue;
            }
            if (name.equals("..")) {
                cur = cur.getParent() == null ? cur : cur.getParent();
                continue;
            }
            Path next = cur.resolve(name);
            if (Files.isSymbolicLink(next)) {
                try {
                    next = next.toRealPath();
                } catch (IOException e) {
                    // a broken link stays as it is
                }
            }
            cur = next;
        }
        return cur;
    }

    private static Path tmp(Path path) {
        return path.resolveSibling(path.getFileName() + ".tmp");
    }

    /** main of sp5_export.py; returns the exit code. */
    static int run(String[] args, PrintStream err) {
        Map<String, String> o;
        try {
            o = parse(args);
        } catch (UsageError e) {
            err.print(USAGE + "\nsp5_export.py: error: " + e.getMessage() + "\n");
            return 2;
        }
        if (o.containsKey("--help")) {
            System.out.print(USAGE + "\n\nSP5-Ordner (nur lesend) als Datensatz für Dispofold exportieren.\n");
            return 0;
        }
        for (String m : new String[] { o.get("--month"), o.get("--to") }) {
            try {
                Sp5Export.checkMonth(m);
            } catch (Sp5Export.Sp5Error e) {
                return fail(err, e.getMessage());
            }
        }
        Path data = resolve(o.get("--data"));
        Path out = resolve(o.get("--out"));
        String reportName = o.get("--report");
        Path report = reportName != null && !reportName.isEmpty() ? resolve(reportName) : null;
        for (Path path : new Path[] { out, report }) {
            if (path != null && path.startsWith(data)) {
                return fail(err, "Ausgabe- und Berichtsdatei dürfen nicht im SP5-Ordner liegen");
            }
        }
        if (out.equals(report)) {
            return fail(err, "Ausgabe- und Berichtsdatei müssen verschieden sein");
        }
        List<Path> targets = new ArrayList<>(List.of(out, tmp(out)));
        if (report != null) {
            targets.add(report);
            targets.add(tmp(report));
        }
        String correctionsName = o.get("--corrections");
        boolean withCorrections = correctionsName != null && !correctionsName.isEmpty();
        if (withCorrections && targets.contains(resolve(correctionsName))) {
            return fail(err, "Ausgabe- und Berichtsdatei dürfen nicht die Korrekturdatei sein");
        }
        String mappingName = o.get("--mapping");
        Map<String, Object> mapping;
        try {
            Path mappingPath = Path.of(mappingName);
            byte[] bytes;
            try {
                bytes = Files.readAllBytes(mappingPath);
            } catch (IOException e) {
                return fail(err, "Zuordnungsdatei " + mappingName + " nicht lesbar (" + Dbf.pyErrorName(e, mappingPath)
                        + ")");
            }
            mapping = Sp5Export.loadMapping(bytes);
        } catch (PyJson.DecodeError e) {
            return fail(err, "Zuordnungsdatei " + mappingName + " nicht lesbar (" + e.type() + ")");
        } catch (Sp5Export.Sp5Error e) {
            return fail(err, "Zuordnungsdatei " + mappingName + ": " + e.getMessage());
        }
        Map<String, Object> corrections = null;
        if (withCorrections) {
            try {
                corrections = Corrections.load(Path.of(correctionsName), correctionsName);
            } catch (Corrections.CorrectionsError e) {
                return fail(err, e.getMessage());
            }
        }
        Sp5Export.Result result;
        try {
            Sp5Export.Request request = new Sp5Export.Request(data, o.get("--month"), o.get("--to"),
                    intOption(o, "--history-months", Sp5Export.HISTORY_MONTHS), intOption(o, "--areas-from-history", null),
                    intOption(o, "--shifts-from-history", null), o.getOrDefault("--demand", "history"),
                    o.containsKey("--no-shift-mix") ? null : intOption(o, "--mix-min-duties", Sp5Export.MIX_MIN_DUTIES),
                    intOption(o, "--hidden-shifts-months", Sp5Export.HIDDEN_SHIFTS_MONTHS),
                    intOption(o, "--recent-months", Sp5Export.RECENT_MONTHS), mapping, corrections, true);
            result = Sp5Export.run(request);
        } catch (Sp5Export.Sp5Error e) {
            return fail(err, e.getMessage());
        } catch (Py.PyException e) {
            return fail(err, "unerwarteter Fehler " + e.type() + " in " + e.file() + ":" + e.line());
        } catch (RuntimeException e) {
            StackTraceElement where = e.getStackTrace().length > 0 ? e.getStackTrace()[0] : null;
            String type = e instanceof Py.Raised r ? r.type : e.getClass().getSimpleName();
            return fail(err, "unerwarteter Fehler " + type + " in " + (where == null ? "?" : where.getFileName() + ":"
                    + where.getLineNumber()));
        }
        List<Object[]> files = new ArrayList<>();
        files.add(new Object[] { out, o.get("--out"), "Ausgabedatei", Sp5Export.datasetBytes(result) });
        if (report != null) {
            files.add(new Object[] { report, reportName, "Berichtsdatei", Sp5Export.reportBytes(result) });
        }
        List<Path> written = new ArrayList<>();
        try {
            for (Object[] f : files) {
                Path tmp = tmp((Path) f[0]);
                try (OutputStream s = Files.newOutputStream(tmp)) {
                    written.add(tmp);
                    s.write((byte[]) f[3]);
                } catch (IOException e) {
                    return fail(err, f[2] + " " + f[1] + " nicht schreibbar (" + Dbf.pyErrorName(e, tmp) + ")");
                }
            }
            for (int i = 0; i < files.size(); i++) {
                try {
                    Files.move(written.get(i), (Path) files.get(i)[0], StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException e) {
                    return fail(err, "unerwarteter Fehler " + Dbf.pyErrorName(e, (Path) files.get(i)[0])
                            + " beim Verschieben");
                }
            }
            written.clear();
        } finally {
            for (Path tmp : written) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException e) {
                    // as Python's unlink(missing_ok=True) in a finally: nothing more to do
                }
            }
        }
        for (String w : result.warnings()) {
            err.print("Warnung: " + w + "\n");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> ds = result.dataset();
        err.print(((List<?>) ds.get("periods")).size() + " Planungsmonate, " + ((List<?>) ds.get("people")).size()
                + " Mitarbeiter, " + ((List<?>) ds.get("shifts")).size() + " Schichten, "
                + ((List<?>) ds.get("demand")).size() + " Bedarfsregeln, " + result.warnings().size()
                + " Warnungen; geschrieben: " + o.get("--out") + "\n");
        return 0;
    }
}
