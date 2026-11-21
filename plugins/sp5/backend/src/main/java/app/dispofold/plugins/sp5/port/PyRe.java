package app.dispofold.plugins.sp5.port;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Python's re for str patterns, as far as the mapping needs it: {@link #compile} parses a pattern the way Python 3.14's
 * re._parser does (same errors, messages and positions in code points) and builds an equivalent java.util.regex
 * pattern; {@link #fnmatchcase} is fnmatch.fnmatchcase (fnmatch.translate, then compile). Known limits: a
 * back-reference inside a look-behind is refused by Java; \w takes the letters and numbers of Java's Unicode data.
 */
public final class PyRe {

    /** re.error: the message and the position (null where Python has None). */
    public static final class Error extends Exception {
        private final String msg;
        private final Integer pos;

        Error(String msg, Integer pos) {
            super(msg);
            this.msg = msg;
            this.pos = pos;
        }

        public String msg() {
            return msg;
        }

        public Integer pos() {
            return pos;
        }
    }

    static final int FLAG_IGNORECASE = 2;
    static final int FLAG_LOCALE = 4;
    static final int FLAG_MULTILINE = 8;
    static final int FLAG_DOTALL = 16;
    static final int FLAG_UNICODE = 32;
    static final int FLAG_VERBOSE = 64;
    static final int FLAG_ASCII = 256;
    static final int TYPE_FLAGS = FLAG_ASCII | FLAG_LOCALE | FLAG_UNICODE;
    static final long MAXREPEAT = 4294967295L;
    static final long MAXWIDTH = Long.MAX_VALUE;
    private static final Map<String, Pattern> CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    private PyRe() {
    }

    /** re.compile(pattern) as a Java pattern; raises re.error as Python. */
    public static Pattern compile(String pattern) throws Error {
        return compile(pattern, 0);
    }

    static Pattern compile(String pattern, int flags) throws Error {
        String key = flags + ":" + pattern;
        Pattern cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        Parser p = new Parser(pattern);
        p.flags = flags;
        List<Node> sub = p.parseSub(flags & FLAG_VERBOSE, 0);
        if (p.next != null) {
            throw p.error("unbalanced parenthesis", 0);
        }
        for (Map.Entry<Integer, Integer> e : p.grouprefpos.entrySet()) {
            if (e.getKey() >= p.groups()) {
                throw new Error("invalid group reference " + e.getKey(), e.getValue());
            }
        }
        checkLookbehind(sub, p);
        int global = p.flags;
        StringBuilder out = new StringBuilder();
        emit(out, sub, global);
        int javaFlags = Pattern.UNIX_LINES;
        if ((global & FLAG_IGNORECASE) != 0) {
            javaFlags |= (global & FLAG_ASCII) != 0 ? Pattern.CASE_INSENSITIVE : 0; // Unicode: classes spelled out
        }
        if ((global & FLAG_MULTILINE) != 0) {
            javaFlags |= Pattern.MULTILINE;
        }
        if ((global & FLAG_DOTALL) != 0) {
            javaFlags |= Pattern.DOTALL;
        }
        Pattern compiled;
        try {
            compiled = Pattern.compile(out.toString(), javaFlags);
        } catch (PatternSyntaxException e) {
            throw new Error("not supported by the Java port (" + e.getDescription() + ")", null);
        }
        if (CACHE.size() < 512) {
            CACHE.put(key, compiled);
        }
        return compiled;
    }

    /** re.match(pattern, value) is not None. */
    public static boolean match(Pattern pattern, String value) {
        return pattern.matcher(value).lookingAt();
    }

    // ── fnmatch ─────────────────────────────────────────────

    /** fnmatch.fnmatchcase(name, pat). */
    public static boolean fnmatchcase(String name, String pat) {
        try {
            return compile(translate(pat)).matcher(name).lookingAt();
        } catch (Error e) {
            throw new IllegalStateException("fnmatch pattern not translated: " + e.msg(), e);
        }
    }

    /** fnmatch.translate: the regular expression of the glob pattern (inner stars as atomic lazy groups). */
    static String translate(String text) {
        int[] pat = text.codePoints().toArray();
        List<String> res = new ArrayList<>();
        List<Integer> stars = new ArrayList<>();
        int i = 0;
        int n = pat.length;
        while (i < n) {
            int c = pat[i];
            i++;
            if (c == '*') {
                stars.add(res.size());
                res.add(".*");
                while (i < n && pat[i] == '*') {
                    i++;
                }
            } else if (c == '?') {
                res.add(".");
            } else if (c == '[') {
                int j = i;
                if (j < n && pat[j] == '!') {
                    j++;
                }
                if (j < n && pat[j] == ']') {
                    j++;
                }
                while (j < n && pat[j] != ']') {
                    j++;
                }
                if (j >= n) {
                    res.add("\\[");
                } else {
                    String stuff = sub(pat, i, j);
                    if (stuff.indexOf('-') < 0) {
                        stuff = stuff.replace("\\", "\\\\");
                    } else {
                        List<String> chunks = new ArrayList<>();
                        int k = pat[i] == '!' ? i + 2 : i + 1;
                        while (true) {
                            k = find(pat, '-', k, j);
                            if (k < 0) {
                                break;
                            }
                            chunks.add(sub(pat, i, k));
                            i = k + 1;
                            k = k + 3;
                        }
                        String chunk = sub(pat, i, j);
                        if (!chunk.isEmpty()) {
                            chunks.add(chunk);
                        } else {
                            chunks.set(chunks.size() - 1, chunks.get(chunks.size() - 1) + "-");
                        }
                        for (int m = chunks.size() - 1; m > 0; m--) {
                            int[] left = chunks.get(m - 1).codePoints().toArray();
                            int[] right = chunks.get(m).codePoints().toArray();
                            if (left[left.length - 1] > right[0]) {
                                chunks.set(m - 1, sub(left, 0, left.length - 1) + sub(right, 1, right.length));
                                chunks.remove(m);
                            }
                        }
                        StringBuilder joined = new StringBuilder();
                        for (int m = 0; m < chunks.size(); m++) {
                            joined.append(m > 0 ? "-" : "").append(chunks.get(m).replace("\\", "\\\\")
                                    .replace("-", "\\-"));
                        }
                        stuff = joined.toString();
                    }
                    i = j + 1;
                    if (stuff.isEmpty()) {
                        res.add("(?!)");
                    } else if (stuff.equals("!")) {
                        res.add(".");
                    } else {
                        stuff = stuff.replaceAll("([&~|])", "\\\\$1");
                        if (stuff.charAt(0) == '!') {
                            stuff = "^" + stuff.substring(1);
                        } else if (stuff.charAt(0) == '^' || stuff.charAt(0) == '[') {
                            stuff = "\\" + stuff;
                        }
                        res.add("[" + stuff + "]");
                    }
                }
            } else {
                res.add(escape(c));
            }
        }
        if (stars.isEmpty()) {
            return "(?s:" + String.join("", res) + ")\\z";
        }
        // _join_translated_parts: STAR fixed STAR fixed ... with no backtracking into the inner pairs
        StringBuilder out = new StringBuilder(String.join("", res.subList(0, stars.get(0))));
        i = stars.get(0) + 1;
        for (int k = 1; k < stars.size(); k++) {
            int j = stars.get(k);
            out.append("(?>.*?").append(String.join("", res.subList(i, j))).append(')');
            i = j + 1;
        }
        out.append(".*").append(String.join("", res.subList(i, res.size())));
        return "(?s:" + out + ")\\z";
    }

    private static String sub(int[] cps, int from, int to) {
        return new String(cps, from, Math.max(0, to - from));
    }

    private static int find(int[] cps, int c, int from, int to) {
        for (int i = from; i < to; i++) {
            if (cps[i] == c) {
                return i;
            }
        }
        return -1;
    }

    /** re.escape of one character. */
    private static String escape(int c) {
        String special = "()[]{}?*+-|^$\\.&~# \t\n\r\u000B\f";
        String s = new String(Character.toChars(c));
        return special.indexOf(c) >= 0 ? "\\" + s : s;
    }

    // ── parser (re._parser) ────────────────────────────────

    private enum Op {
        LITERAL, NOT_LITERAL, ANY, IN, AT, REPEAT, SUBPATTERN, ATOMIC, ASSERT, ASSERT_NOT, FAILURE, BRANCH, GROUPREF,
        GROUPREF_EXISTS
    }

    /** One item of a set: a literal (lo == hi), a range, or a category (cat != null). */
    private record SetItem(int lo, int hi, String cat) {
    }

    private static final class Node {
        Op op;
        int c; // LITERAL, NOT_LITERAL
        String at; // AT: BEGINNING, END, BEGINNING_STRING, END_STRING, BOUNDARY, NON_BOUNDARY
        boolean negate; // IN
        List<SetItem> set; // IN
        long min;
        long max; // REPEAT
        String kind; // REPEAT: "", "?" (lazy) or "+" (possessive)
        List<Node> p; // REPEAT item, SUBPATTERN, ATOMIC, ASSERT, ASSERT_NOT, GROUPREF_EXISTS yes
        List<Node> no; // GROUPREF_EXISTS
        List<List<Node>> items; // BRANCH
        Integer group; // SUBPATTERN, GROUPREF, GROUPREF_EXISTS
        int addFlags;
        int delFlags;
        int dir; // ASSERT

        Node(Op op) {
            this.op = op;
        }
    }

    private static Node literal(int c) {
        Node n = new Node(Op.LITERAL);
        n.c = c;
        return n;
    }

    private static Node at(String where) {
        Node n = new Node(Op.AT);
        n.at = where;
        return n;
    }

    private static Node category(String cat) {
        Node n = new Node(Op.IN);
        n.set = new ArrayList<>(List.of(new SetItem(0, 0, cat)));
        return n;
    }

    private static final class Parser {
        final int[] s;
        final String text;
        int index;
        String next;
        int flags;
        final Map<String, Integer> groupdict = new HashMap<>();
        final List<long[]> groupwidths = new ArrayList<>();
        Integer lookbehindgroups;
        boolean globalFlags; // set by group() when it read global flags
        final Map<Integer, Integer> grouprefpos = new java.util.LinkedHashMap<>();

        Parser(String text) throws Error {
            this.text = text;
            this.s = text.codePoints().toArray();
            groupwidths.add(null);
            advance();
        }

        int groups() {
            return groupwidths.size();
        }

        void advance() throws Error {
            if (index >= s.length) {
                next = null;
                return;
            }
            String ch = new String(Character.toChars(s[index]));
            int i = index;
            if (ch.equals("\\")) {
                i++;
                if (i >= s.length) {
                    throw new Error("bad escape (end of pattern)", s.length - 1);
                }
                ch += new String(Character.toChars(s[i]));
            }
            index = i + 1;
            next = ch;
        }

        boolean match(String c) throws Error {
            if (c.equals(next)) {
                advance();
                return true;
            }
            return false;
        }

        String get() throws Error {
            String t = next;
            advance();
            return t;
        }

        String getwhile(int n, String charset) throws Error {
            StringBuilder r = new StringBuilder();
            for (int k = 0; k < n; k++) {
                if (next == null || next.length() != 1 || charset.indexOf(next.charAt(0)) < 0) {
                    break;
                }
                r.append(next);
                advance();
            }
            return r.toString();
        }

        String getuntil(String terminator, String name) throws Error {
            StringBuilder r = new StringBuilder();
            while (true) {
                String c = next;
                advance();
                if (c == null) {
                    if (r.isEmpty()) {
                        throw error("missing " + name, 0);
                    }
                    throw error("missing " + terminator + ", unterminated name", len(r.toString()));
                }
                if (c.equals(terminator)) {
                    if (r.isEmpty()) {
                        throw error("missing " + name, 1);
                    }
                    break;
                }
                r.append(c);
            }
            return r.toString();
        }

        int tell() {
            return index - (next == null ? 0 : len(next));
        }

        void seek(int i) throws Error {
            index = i;
            advance();
        }

        Error error(String msg, int offset) {
            return new Error(msg, tell() - offset);
        }

        void checkgroupname(String name, int offset) throws Error {
            if (!isIdentifier(name)) {
                throw error("bad character in group name " + Py.strRepr(name), len(name) + offset);
            }
        }

        int opengroup(String name) throws Error {
            int gid = groups();
            groupwidths.add(null);
            if (name != null) {
                Integer ogid = groupdict.get(name);
                if (ogid != null) {
                    throw new Error("redefinition of group name " + Py.strRepr(name) + " as group " + gid
                            + "; was group " + ogid, null);
                }
                groupdict.put(name, gid);
            }
            return gid;
        }

        boolean checkgroup(int gid) {
            return gid < groups() && groupwidths.get(gid) != null;
        }

        void checklookbehindgroup(int gid) throws Error {
            if (lookbehindgroups != null) {
                if (!checkgroup(gid)) {
                    throw error("cannot refer to an open group", 0);
                }
                if (gid >= lookbehindgroups) {
                    throw error("cannot refer to group defined in the same lookbehind subpattern", 0);
                }
            }
        }

        List<Node> parseSub(int verbose, int nested) throws Error {
            List<List<Node>> items = new ArrayList<>();
            while (true) {
                items.add(parse(verbose, nested + 1, nested == 0 && items.isEmpty()));
                if (!match("|")) {
                    break;
                }
                if (nested == 0) {
                    verbose = flags & FLAG_VERBOSE;
                }
            }
            if (items.size() == 1) {
                return items.get(0);
            }
            Node b = new Node(Op.BRANCH);
            b.items = items;
            return new ArrayList<>(List.of(b));
        }

        List<Node> parse(int verbose, int nested, boolean first) throws Error {
            List<Node> sub = new ArrayList<>();
            while (true) {
                String t = next;
                if (t == null || t.equals("|") || t.equals(")")) {
                    break;
                }
                get();
                if (verbose != 0) {
                    if (t.length() == 1 && " \t\n\r\u000B\f".contains(t)) {
                        continue;
                    }
                    if (t.equals("#")) {
                        while (true) {
                            t = get();
                            if (t == null || t.equals("\n")) {
                                break;
                            }
                        }
                        continue;
                    }
                }
                if (t.charAt(0) == '\\' && t.length() > 1) {
                    sub.add(escape(t));
                } else if (!(t.length() == 1 && ".\\[{()*+?^$|".indexOf(t.charAt(0)) >= 0)) {
                    sub.add(literal(t.codePointAt(0)));
                } else if (t.equals("[")) {
                    sub.add(set());
                } else if ("*+?{".contains(t)) {
                    int here = tell();
                    long min;
                    long max;
                    if (t.equals("?")) {
                        min = 0;
                        max = 1;
                    } else if (t.equals("*")) {
                        min = 0;
                        max = MAXREPEAT;
                    } else if (t.equals("+")) {
                        min = 1;
                        max = MAXREPEAT;
                    } else {
                        if ("}".equals(next)) {
                            sub.add(literal('{'));
                            continue;
                        }
                        min = 0;
                        max = MAXREPEAT;
                        StringBuilder lo = new StringBuilder();
                        StringBuilder hi = new StringBuilder();
                        while (isDigit(next)) {
                            lo.append(get());
                        }
                        if (match(",")) {
                            while (isDigit(next)) {
                                hi.append(get());
                            }
                        } else {
                            hi.append(lo);
                        }
                        if (!match("}")) {
                            sub.add(literal('{'));
                            seek(here);
                            continue;
                        }
                        if (!lo.isEmpty()) {
                            min = repeatNumber(lo.toString());
                        }
                        if (!hi.isEmpty()) {
                            max = repeatNumber(hi.toString());
                            if (max < min) {
                                throw error("min repeat greater than max repeat", tell() - here);
                            }
                        }
                    }
                    Node item = sub.isEmpty() ? null : sub.get(sub.size() - 1);
                    if (item == null || item.op == Op.AT) {
                        throw error("nothing to repeat", tell() - here + t.length());
                    }
                    if (item.op == Op.REPEAT) {
                        throw error("multiple repeat", tell() - here + t.length());
                    }
                    Node r = new Node(Op.REPEAT);
                    r.min = min;
                    r.max = max;
                    r.p = List.of(item);
                    r.kind = match("?") ? "?" : match("+") ? "+" : "";
                    sub.set(sub.size() - 1, r);
                } else if (t.equals(".")) {
                    sub.add(new Node(Op.ANY));
                } else if (t.equals("(")) {
                    globalFlags = false;
                    Node g = group(verbose, nested, first, sub);
                    if (g != null) {
                        sub.add(g);
                    } else if (globalFlags) {
                        verbose = flags & FLAG_VERBOSE;
                    }
                } else if (t.equals("^")) {
                    sub.add(at("BEGINNING"));
                } else if (t.equals("$")) {
                    sub.add(at("END"));
                }
            }
            return sub;
        }

        long repeatNumber(String digits) {
            java.math.BigInteger v = new java.math.BigInteger(digits);
            if (v.compareTo(java.math.BigInteger.valueOf(MAXREPEAT)) >= 0) {
                throw new Py.Raised("OverflowError");
            }
            return v.longValue();
        }

        /** A group starting with "(", or null when it adds nothing (a comment, global flags). */
        Node group(int verbose, int nested, boolean first, List<Node> sub) throws Error {
            int start = tell() - 1;
            boolean capture = true;
            boolean atomic = false;
            String name = null;
            int addFlags = 0;
            int delFlags = 0;
            if (match("?")) {
                String ch = get();
                if (ch == null) {
                    throw error("unexpected end of pattern", 0);
                }
                if (ch.equals("P")) {
                    if (match("<")) {
                        name = getuntil(">", "group name");
                        checkgroupname(name, 1);
                    } else if (match("=")) {
                        name = getuntil(")", "group name");
                        checkgroupname(name, 1);
                        Integer gid = groupdict.get(name);
                        if (gid == null) {
                            throw error("unknown group name " + Py.strRepr(name), len(name) + 1);
                        }
                        if (!checkgroup(gid)) {
                            throw error("cannot refer to an open group", len(name) + 1);
                        }
                        checklookbehindgroup(gid);
                        Node ref = new Node(Op.GROUPREF);
                        ref.group = gid;
                        return ref;
                    } else {
                        ch = get();
                        if (ch == null) {
                            throw error("unexpected end of pattern", 0);
                        }
                        throw error("unknown extension ?P" + ch, len(ch) + 2);
                    }
                } else if (ch.equals(":")) {
                    capture = false;
                } else if (ch.equals("#")) {
                    while (true) {
                        if (next == null) {
                            throw error("missing ), unterminated comment", tell() - start);
                        }
                        if (")".equals(get())) {
                            break;
                        }
                    }
                    return null;
                } else if (ch.equals("=") || ch.equals("!") || ch.equals("<")) {
                    int dir = 1;
                    Integer saved = null;
                    if (ch.equals("<")) {
                        ch = get();
                        if (ch == null) {
                            throw error("unexpected end of pattern", 0);
                        }
                        if (!ch.equals("=") && !ch.equals("!")) {
                            throw error("unknown extension ?<" + ch, len(ch) + 2);
                        }
                        dir = -1;
                        saved = lookbehindgroups;
                        if (saved == null) {
                            lookbehindgroups = groups();
                        }
                    }
                    List<Node> p = parseSub(verbose, nested + 1);
                    if (dir < 0 && saved == null) {
                        lookbehindgroups = null;
                    }
                    if (!match(")")) {
                        throw error("missing ), unterminated subpattern", tell() - start);
                    }
                    Node a;
                    if (ch.equals("=")) {
                        a = new Node(Op.ASSERT);
                    } else if (!p.isEmpty()) {
                        a = new Node(Op.ASSERT_NOT);
                    } else {
                        return new Node(Op.FAILURE);
                    }
                    a.dir = dir;
                    a.p = p;
                    return a;
                } else if (ch.equals("(")) {
                    String condname = getuntil(")", "group name");
                    int condgroup;
                    if (!(isDecimal(condname) && condname.chars().noneMatch(x -> x > 0x7F))) {
                        checkgroupname(condname, 1);
                        Integer g = groupdict.get(condname);
                        if (g == null) {
                            throw error("unknown group name " + Py.strRepr(condname), len(condname) + 1);
                        }
                        condgroup = g;
                    } else {
                        java.math.BigInteger v = new java.math.BigInteger(condname);
                        if (v.signum() == 0) {
                            throw error("bad group number", len(condname) + 1);
                        }
                        if (v.compareTo(java.math.BigInteger.valueOf(1073741823)) >= 0) {
                            throw error("invalid group reference " + v, len(condname) + 1);
                        }
                        condgroup = v.intValue();
                        grouprefpos.putIfAbsent(condgroup, tell() - len(condname) - 1);
                    }
                    checklookbehindgroup(condgroup);
                    List<Node> yes = parse(verbose, nested + 1, false);
                    List<Node> no = null;
                    if (match("|")) {
                        no = parse(verbose, nested + 1, false);
                        if ("|".equals(next)) {
                            throw error("conditional backref with more than two branches", 0);
                        }
                    }
                    if (!match(")")) {
                        throw error("missing ), unterminated subpattern", tell() - start);
                    }
                    Node c = new Node(Op.GROUPREF_EXISTS);
                    c.group = condgroup;
                    c.p = yes;
                    c.no = no;
                    return c;
                } else if (ch.equals(">")) {
                    capture = false;
                    atomic = true;
                } else if (isFlag(ch) || ch.equals("-")) {
                    int[] f = parseFlags(ch);
                    if (f == null) {
                        if (!first || !sub.isEmpty()) {
                            throw error("global flags not at the start of the expression", tell() - start);
                        }
                        globalFlags = true;
                        return null;
                    }
                    addFlags = f[0];
                    delFlags = f[1];
                    capture = false;
                } else {
                    throw error("unknown extension ?" + ch, len(ch) + 1);
                }
            }
            Integer group = null;
            if (capture) {
                try {
                    group = opengroup(name);
                } catch (Error e) {
                    throw error(e.msg(), len(name) + 1);
                }
            }
            int subVerbose = ((verbose != 0 || (addFlags & FLAG_VERBOSE) != 0) && (delFlags & FLAG_VERBOSE) == 0)
                    ? FLAG_VERBOSE : 0;
            List<Node> p = parseSub(subVerbose, nested + 1);
            if (!match(")")) {
                throw error("missing ), unterminated subpattern", tell() - start);
            }
            if (group != null) {
                groupwidths.set(group, width(p, this));
            }
            Node g = new Node(atomic ? Op.ATOMIC : Op.SUBPATTERN);
            g.group = group;
            g.addFlags = addFlags;
            g.delFlags = delFlags;
            g.p = p;
            return g;
        }

        /** _parse_flags: {add, del}, or null for global flags (applied to `flags`). */
        int[] parseFlags(String ch) throws Error {
            int add = 0;
            int del = 0;
            if (!ch.equals("-")) {
                while (true) {
                    int flag = flagOf(ch);
                    if (ch.equals("L")) {
                        throw error("bad inline flags: cannot use 'L' flag with a str pattern", 0);
                    }
                    add |= flag;
                    if ((flag & TYPE_FLAGS) != 0 && (add & TYPE_FLAGS) != flag) {
                        throw error("bad inline flags: flags 'a', 'u' and 'L' are incompatible", 0);
                    }
                    ch = get();
                    if (ch == null) {
                        throw error("missing -, : or )", 0);
                    }
                    if (ch.equals(")") || ch.equals("-") || ch.equals(":")) {
                        break;
                    }
                    if (!isFlag(ch)) {
                        throw error(isAlpha(ch) ? "unknown flag" : "missing -, : or )", len(ch));
                    }
                }
            }
            if (ch.equals(")")) {
                flags |= add;
                return null;
            }
            if (ch.equals("-")) {
                ch = get();
                if (ch == null) {
                    throw error("missing flag", 0);
                }
                if (!isFlag(ch)) {
                    throw error(isAlpha(ch) ? "unknown flag" : "missing flag", len(ch));
                }
                while (true) {
                    int flag = flagOf(ch);
                    if ((flag & TYPE_FLAGS) != 0) {
                        throw error("bad inline flags: cannot turn off flags 'a', 'u' and 'L'", 0);
                    }
                    del |= flag;
                    ch = get();
                    if (ch == null) {
                        throw error("missing :", 0);
                    }
                    if (ch.equals(":")) {
                        break;
                    }
                    if (!isFlag(ch)) {
                        throw error(isAlpha(ch) ? "unknown flag" : "missing :", len(ch));
                    }
                }
            }
            if ((add & del) != 0) {
                throw error("bad inline flags: flag turned on and off", 1);
            }
            return new int[] { add, del };
        }

        Node set() throws Error {
            int here = tell() - 1;
            List<SetItem> items = new ArrayList<>();
            boolean negate = match("^");
            while (true) {
                String t = get();
                if (t == null) {
                    throw error("unterminated character set", tell() - here);
                }
                if (t.equals("]") && !items.isEmpty()) {
                    break;
                }
                SetItem code1 = t.charAt(0) == '\\' && t.length() > 1 ? classEscape(t)
                        : new SetItem(t.codePointAt(0), t.codePointAt(0), null);
                if (match("-")) {
                    String that = get();
                    if (that == null) {
                        throw error("unterminated character set", tell() - here);
                    }
                    if (that.equals("]")) {
                        items.add(code1);
                        items.add(new SetItem('-', '-', null));
                        break;
                    }
                    SetItem code2 = that.charAt(0) == '\\' && that.length() > 1 ? classEscape(that)
                            : new SetItem(that.codePointAt(0), that.codePointAt(0), null);
                    if (code1.cat() != null || code2.cat() != null || code2.lo() < code1.lo()) {
                        throw error("bad character range " + t + "-" + that, len(t) + 1 + len(that));
                    }
                    items.add(new SetItem(code1.lo(), code2.lo(), null));
                } else {
                    items.add(code1);
                }
            }
            Node n = new Node(Op.IN);
            n.negate = negate;
            n.set = items;
            return n;
        }

        SetItem classEscape(String escape) throws Error {
            String simple = simpleEscape(escape);
            if (simple != null) {
                return new SetItem(simple.codePointAt(0), simple.codePointAt(0), null);
            }
            String cat = categoryOf(escape);
            if (cat != null) {
                return new SetItem(0, 0, cat);
            }
            char c = escape.charAt(1);
            Integer v = numericEscape(escape, c);
            if (v != null) {
                return new SetItem(v, v, null);
            }
            if (c >= '0' && c <= '7') {
                escape += getwhile(2, "01234567");
                int o = Integer.parseInt(escape.substring(1), 8);
                if (o > 0377) {
                    throw error("octal escape value " + escape + " outside of range 0-0o377", len(escape));
                }
                return new SetItem(o, o, null);
            }
            if (c >= '0' && c <= '9') {
                throw error("bad escape " + escape, len(escape));
            }
            if (len(escape) == 2) {
                if (isAsciiLetter(escape.codePointAt(1))) {
                    throw error("bad escape " + escape, len(escape));
                }
                return new SetItem(escape.codePointAt(1), escape.codePointAt(1), null);
            }
            throw error("bad escape " + escape, len(escape));
        }

        Node escape(String escape) throws Error {
            switch (escape) {
                case "\\A" -> {
                    return at("BEGINNING_STRING");
                }
                case "\\b" -> {
                    return at("BOUNDARY");
                }
                case "\\B" -> {
                    return at("NON_BOUNDARY");
                }
                case "\\z", "\\Z" -> {
                    return at("END_STRING");
                }
                default -> {
                }
            }
            String cat = categoryOf(escape);
            if (cat != null) {
                return category(cat);
            }
            String simple = simpleEscape(escape);
            if (simple != null) {
                return literal(simple.codePointAt(0));
            }
            char c = escape.charAt(1);
            Integer v = numericEscape(escape, c);
            if (v != null) {
                return literal(v);
            }
            if (c == '0') {
                escape += getwhile(2, "01234567");
                return literal(Integer.parseInt(escape.substring(1), 8));
            }
            if (c >= '1' && c <= '9') {
                if (isDigit(next)) {
                    escape += get();
                    if (isOct(escape.charAt(1)) && isOct(escape.charAt(2)) && next != null && next.length() == 1
                            && isOct(next.charAt(0))) {
                        escape += get();
                        int o = Integer.parseInt(escape.substring(1), 8);
                        if (o > 0377) {
                            throw error("octal escape value " + escape + " outside of range 0-0o377", len(escape));
                        }
                        return literal(o);
                    }
                }
                int group = Integer.parseInt(escape.substring(1));
                if (group < groups()) {
                    if (!checkgroup(group)) {
                        throw error("cannot refer to an open group", len(escape));
                    }
                    checklookbehindgroup(group);
                    Node ref = new Node(Op.GROUPREF);
                    ref.group = group;
                    return ref;
                }
                throw error("invalid group reference " + group, len(escape) - 1);
            }
            if (len(escape) == 2) {
                if (isAsciiLetter(escape.codePointAt(1))) {
                    throw error("bad escape " + escape, len(escape));
                }
                return literal(escape.codePointAt(1));
            }
            throw error("bad escape " + escape, len(escape));
        }

        /** The escapes x, u, U and N (after a backslash); null for any other. */
        Integer numericEscape(String escape, char c) throws Error {
            if (c == 'x' || c == 'u' || c == 'U') {
                int digits = c == 'x' ? 2 : c == 'u' ? 4 : 8;
                escape += getwhile(digits, "0123456789abcdefABCDEF");
                if (len(escape) != digits + 2) {
                    throw error("incomplete escape " + escape, len(escape));
                }
                long v = Long.parseLong(escape.substring(2), 16);
                if (v > 0x10FFFF) {
                    throw error("bad escape " + escape, len(escape));
                }
                return (int) v;
            }
            if (c == 'N') {
                if (!match("{")) {
                    throw error("missing {", 0);
                }
                String charname = getuntil("}", "character name");
                int cp = -1;
                if (Py.strip(charname).equals(charname)) {
                    try {
                        cp = Character.codePointOf(charname);
                    } catch (IllegalArgumentException e) {
                        cp = -1;
                    }
                }
                if (cp < 0) {
                    throw error("undefined character name " + Py.strRepr(charname), len(charname) + 4);
                }
                return cp;
            }
            return null;
        }
    }

    private static String simpleEscape(String e) {
        return switch (e) {
            case "\\a" -> "\u0007";
            case "\\b" -> "\b";
            case "\\f" -> "\f";
            case "\\n" -> "\n";
            case "\\r" -> "\r";
            case "\\t" -> "\t";
            case "\\v" -> "\u000B";
            case "\\\\" -> "\\";
            default -> null;
        };
    }

    private static String categoryOf(String e) {
        return switch (e) {
            case "\\d" -> "DIGIT";
            case "\\D" -> "NOT_DIGIT";
            case "\\s" -> "SPACE";
            case "\\S" -> "NOT_SPACE";
            case "\\w" -> "WORD";
            case "\\W" -> "NOT_WORD";
            default -> null;
        };
    }

    private static boolean isFlag(String ch) {
        return ch.length() == 1 && "iLmsxau".indexOf(ch.charAt(0)) >= 0;
    }

    private static int flagOf(String ch) {
        return switch (ch) {
            case "i" -> FLAG_IGNORECASE;
            case "L" -> FLAG_LOCALE;
            case "m" -> FLAG_MULTILINE;
            case "s" -> FLAG_DOTALL;
            case "x" -> FLAG_VERBOSE;
            case "a" -> FLAG_ASCII;
            default -> FLAG_UNICODE;
        };
    }

    private static boolean isAlpha(String s) {
        return !s.isEmpty() && s.codePoints().noneMatch(c -> !Character.isLetter(c));
    }

    private static boolean isDigit(String t) {
        return t != null && t.length() == 1 && t.charAt(0) >= '0' && t.charAt(0) <= '9';
    }

    private static boolean isOct(char c) {
        return c >= '0' && c <= '7';
    }

    private static boolean isAsciiLetter(int c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static boolean isDecimal(String s) {
        return !s.isEmpty() && s.codePoints().noneMatch(c -> Character.getType(c) != Character.DECIMAL_DIGIT_NUMBER);
    }

    private static boolean isIdentifier(String s) {
        if (s.isEmpty()) {
            return false;
        }
        int first = s.codePointAt(0);
        if (first != '_' && !Character.isUnicodeIdentifierStart(first)) {
            return false;
        }
        return s.codePoints().skip(1).noneMatch(c -> !Character.isUnicodeIdentifierPart(c)
                || Character.isIdentifierIgnorable(c));
    }

    /** Length in code points (Python's len). */
    private static int len(String s) {
        return s.codePointCount(0, s.length());
    }

    // ── width (SubPattern.getwidth) and the look-behind check of the compiler ──

    private static long add(long a, long b) {
        return a >= MAXWIDTH - b ? MAXWIDTH : a + b;
    }

    private static long mul(long a, long b) {
        if (a == 0 || b == 0) {
            return 0;
        }
        return a >= MAXWIDTH / b ? MAXWIDTH : a * b;
    }

    private static long[] width(List<Node> sub, Parser state) {
        long lo = 0;
        long hi = 0;
        for (Node n : sub) {
            long[] w = switch (n.op) {
                case BRANCH -> {
                    long i = MAXWIDTH;
                    long j = 0;
                    for (List<Node> item : n.items) {
                        long[] x = width(item, state);
                        i = Math.min(i, x[0]);
                        j = Math.max(j, x[1]);
                    }
                    yield new long[] { i, j };
                }
                case ATOMIC, SUBPATTERN -> width(n.p, state);
                case REPEAT -> {
                    long[] x = width(n.p, state);
                    yield new long[] { mul(x[0], n.min), n.max == MAXREPEAT && x[1] != 0 ? MAXWIDTH : mul(x[1], n.max) };
                }
                case LITERAL, NOT_LITERAL, ANY, IN -> new long[] { 1, 1 };
                case GROUPREF -> state == null ? new long[] { 0, MAXWIDTH } : state.groupwidths.get(n.group);
                case GROUPREF_EXISTS -> {
                    long[] x = width(n.p, state);
                    if (n.no != null) {
                        long[] y = width(n.no, state);
                        yield new long[] { Math.min(x[0], y[0]), Math.max(x[1], y[1]) };
                    }
                    yield new long[] { 0, x[1] };
                }
                default -> new long[] { 0, 0 };
            };
            lo = add(lo, w[0]);
            hi = add(hi, w[1]);
        }
        return new long[] { lo, hi };
    }

    private static void checkLookbehind(List<Node> sub, Parser state) throws Error {
        for (Node n : sub) {
            if ((n.op == Op.ASSERT || n.op == Op.ASSERT_NOT) && n.dir < 0) {
                long[] w = width(n.p, state);
                if (w[0] != w[1]) {
                    throw new Error("look-behind requires fixed-width pattern", null);
                }
            }
            if (n.p != null) {
                checkLookbehind(n.p, state);
            }
            if (n.no != null) {
                checkLookbehind(n.no, state);
            }
            if (n.items != null) {
                for (List<Node> item : n.items) {
                    checkLookbehind(item, state);
                }
            }
        }
    }

    // ── Java pattern ───────────────────────────────────────

    private static final String ANY_CHAR = "[\\x{0}-\\x{10FFFF}]";
    private static final String SPACE = "\\t\\n\\x{B}\\f\\r\\x{1C}-\\x{20}\\x{85}\\x{A0}\\x{1680}\\x{2000}-\\x{200A}"
            + "\\x{2028}\\x{2029}\\x{202F}\\x{205F}\\x{3000}";

    private static String hex(int c) {
        return "\\x{" + Integer.toHexString(c) + "}";
    }

    /** The class body (without brackets) of a category. */
    private static String categoryClass(String cat, boolean ascii) {
        return switch (cat) {
            case "DIGIT" -> ascii ? "0-9" : "\\p{Nd}";
            case "NOT_DIGIT" -> ascii ? "^0-9" : "^\\p{Nd}";
            case "SPACE" -> ascii ? " \\t\\n\\r\\f\\x{B}" : SPACE;
            case "NOT_SPACE" -> "^" + (ascii ? " \\t\\n\\r\\f\\x{B}" : SPACE);
            case "WORD" -> ascii ? "a-zA-Z0-9_" : "\\p{L}\\p{N}_";
            default -> "^" + (ascii ? "a-zA-Z0-9_" : "\\p{L}\\p{N}_");
        };
    }

    private static void emitSet(StringBuilder out, List<SetItem> set, boolean ascii) {
        out.append('[');
        for (SetItem i : set) {
            if (i.cat() != null) {
                out.append('[').append(categoryClass(i.cat(), ascii)).append(']');
            } else if (i.lo() == i.hi()) {
                out.append(hex(i.lo()));
            } else {
                out.append(hex(i.lo())).append('-').append(hex(i.hi()));
            }
        }
        out.append(']');
    }

    /** The Java inline flags of a scope: ASCII case folding by Java, Unicode case folding spelled out by emit. */
    private static String javaFlags(int flags) {
        StringBuilder on = new StringBuilder();
        StringBuilder off = new StringBuilder("u");
        boolean ascii = (flags & FLAG_ASCII) != 0;
        ((flags & FLAG_IGNORECASE) != 0 && ascii ? on : off).append('i');
        ((flags & FLAG_MULTILINE) != 0 ? on : off).append('m');
        ((flags & FLAG_DOTALL) != 0 ? on : off).append('s');
        return on + "-" + off;
    }

    // ── Unicode case folding as sre does it (IGNORECASE without ASCII) ──

    /** re._casefix._EXTRA_CASES: lower-case characters that match each other besides lower(). */
    private static final int[][] EXTRA_CASES = {
            {0x0069, 0x0131}, {0x0073, 0x017F}, {0x00B5, 0x03BC}, {0x0131, 0x0069}, {0x017F, 0x0073},
            {0x0345, 0x03B9, 0x1FBE}, {0x0390, 0x1FD3}, {0x03B0, 0x1FE3}, {0x03B2, 0x03D0}, {0x03B5, 0x03F5},
            {0x03B8, 0x03D1}, {0x03B9, 0x0345, 0x1FBE}, {0x03BA, 0x03F0}, {0x03BC, 0x00B5}, {0x03C0, 0x03D6},
            {0x03C1, 0x03F1}, {0x03C2, 0x03C3}, {0x03C3, 0x03C2}, {0x03C6, 0x03D5}, {0x03D0, 0x03B2},
            {0x03D1, 0x03B8}, {0x03D5, 0x03C6}, {0x03D6, 0x03C0}, {0x03F0, 0x03BA}, {0x03F1, 0x03C1},
            {0x03F5, 0x03B5}, {0x0432, 0x1C80}, {0x0434, 0x1C81}, {0x043E, 0x1C82}, {0x0441, 0x1C83},
            {0x0442, 0x1C84, 0x1C85}, {0x044A, 0x1C86}, {0x0463, 0x1C87}, {0x1C80, 0x0432}, {0x1C81, 0x0434},
            {0x1C82, 0x043E}, {0x1C83, 0x0441}, {0x1C84, 0x0442, 0x1C85}, {0x1C85, 0x0442, 0x1C84}, {0x1C86, 0x044A},
            {0x1C87, 0x0463}, {0x1C88, 0xA64B}, {0x1E61, 0x1E9B}, {0x1E9B, 0x1E61}, {0x1FBE, 0x0345, 0x03B9},
            {0x1FD3, 0x0390}, {0x1FE3, 0x03B0}, {0xA64B, 0x1C88}, {0xFB05, 0xFB06}, {0xFB06, 0xFB05} };
    private static Map<Integer, int[]> fixes;
    private static Map<Integer, Integer> lowered; // every character whose lower() is another one

    private static synchronized void caseTables() {
        if (lowered != null) {
            return;
        }
        Map<Integer, int[]> f = new HashMap<>();
        for (int[] e : EXTRA_CASES) {
            f.put(e[0], java.util.Arrays.copyOfRange(e, 1, e.length));
        }
        Map<Integer, Integer> l = new HashMap<>();
        for (int c = 0; c <= Character.MAX_CODE_POINT; c++) {
            int lo = Character.toLowerCase(c);
            if (lo != c) {
                l.put(c, lo);
            }
        }
        fixes = f;
        lowered = l;
    }

    private static int lower(int c) {
        return lowered.getOrDefault(c, c);
    }

    /** _sre.unicode_iscased: lower() or upper() (the first character of the full mapping) differs. */
    private static boolean isCased(int c) {
        return lower(c) != c || new String(Character.toChars(c)).toUpperCase(java.util.Locale.ROOT).codePointAt(0) != c;
    }

    /**
     * The class body of the characters ch with lower(ch) among the lowered members of `set` and their EXTRA_CASES
     * (sre's IN_UNI_IGNORE and LITERAL_UNI_IGNORE); categories are kept as they are.
     */
    private static String foldedClass(List<SetItem> set, boolean ascii) {
        java.util.TreeSet<Integer> lows = new java.util.TreeSet<>(); // lowered members besides the ranges themselves
        List<SetItem> ranges = new ArrayList<>();
        StringBuilder body = new StringBuilder();
        for (SetItem i : set) {
            if (i.cat() != null) {
                body.append('[').append(categoryClass(i.cat(), ascii)).append(']');
            } else if (i.lo() == i.hi()) {
                lows.add(lower(i.lo()));
            } else {
                ranges.add(i);
                body.append(hex(i.lo())).append('-').append(hex(i.hi()));
            }
        }
        java.util.function.IntPredicate inRanges = v -> ranges.stream().anyMatch(r -> v >= r.lo() && v <= r.hi());
        for (Map.Entry<Integer, Integer> e : lowered.entrySet()) {
            if (inRanges.test(e.getKey())) {
                lows.add(e.getValue());
            }
        }
        java.util.function.IntPredicate base = v -> lows.contains(v) || (inRanges.test(v) && lower(v) == v);
        java.util.TreeSet<Integer> all = new java.util.TreeSet<>(lows);
        for (Map.Entry<Integer, int[]> e : fixes.entrySet()) {
            if (base.test(e.getKey())) {
                for (int k : e.getValue()) {
                    all.add(k);
                }
            }
        }
        java.util.function.IntPredicate inL = v -> all.contains(v) || (inRanges.test(v) && lower(v) == v);
        java.util.TreeSet<Integer> chars = new java.util.TreeSet<>();
        for (int v : all) {
            if (lower(v) == v) {
                chars.add(v);
            }
        }
        for (Map.Entry<Integer, Integer> e : lowered.entrySet()) {
            if (inL.test(e.getValue())) {
                chars.add(e.getKey());
            }
        }
        for (int c : chars) {
            body.append(hex(c));
        }
        return body.toString();
    }

    /** Whether a set takes part in case folding (sre's hascased); ranges count by their original characters. */
    private static boolean hasCased(List<SetItem> set) {
        for (SetItem i : set) {
            if (i.cat() == null && (i.lo() == i.hi() ? isCased(lower(i.lo()))
                    : java.util.stream.IntStream.rangeClosed(i.lo(), i.hi()).anyMatch(PyRe::isCased))) {
                return true;
            }
        }
        return false;
    }

    /** A literal or a set under Unicode IGNORECASE as an explicit class body, or null where sre matches exactly. */
    private static String folded(Node n, boolean ascii) {
        caseTables();
        if (n.op == Op.LITERAL || n.op == Op.NOT_LITERAL) {
            return isCased(n.c) ? foldedClass(List.of(new SetItem(n.c, n.c, null)), ascii) : null;
        }
        return hasCased(n.set) ? foldedClass(n.set, ascii) : null;
    }

    private static void emit(StringBuilder out, List<Node> sub, int flags) {
        boolean ascii = (flags & FLAG_ASCII) != 0;
        boolean fold = (flags & FLAG_IGNORECASE) != 0 && !ascii;
        String word = "[" + categoryClass("WORD", ascii) + "]";
        for (Node n : sub) {
            String folded = fold && (n.op == Op.LITERAL || n.op == Op.NOT_LITERAL || n.op == Op.IN) ? folded(n, ascii)
                    : null;
            if (folded != null) {
                boolean negate = n.op == Op.NOT_LITERAL || (n.op == Op.IN && n.negate);
                out.append(negate ? "(?:(?![" + folded + "])" + ANY_CHAR + ")" : "[" + folded + "]");
                continue;
            }
            switch (n.op) {
                case LITERAL -> out.append(hex(n.c));
                case NOT_LITERAL -> out.append("(?:(?!").append(hex(n.c)).append(')').append(ANY_CHAR).append(')');
                case ANY -> out.append('.');
                case IN -> {
                    if (n.negate) {
                        out.append("(?:(?!");
                        emitSet(out, n.set, ascii);
                        out.append(')').append(ANY_CHAR).append(')');
                    } else {
                        emitSet(out, n.set, ascii);
                    }
                }
                case AT -> out.append(switch (n.at) {
                    case "BEGINNING" -> (flags & FLAG_MULTILINE) != 0 ? "(?:(?<![\\x{0}-\\x{10FFFF}])|(?<=\\n))"
                            : "(?<![\\x{0}-\\x{10FFFF}])";
                    case "END" -> "$";
                    case "BEGINNING_STRING" -> "(?<![\\x{0}-\\x{10FFFF}])";
                    case "END_STRING" -> "\\z";
                    case "BOUNDARY" -> "(?:(?<=" + word + ")(?!" + word + ")|(?<!" + word + ")(?=" + word + "))";
                    default -> "(?:(?<=" + word + ")(?=" + word + ")|(?<!" + word + ")(?!" + word + "))";
                });
                case REPEAT -> {
                    out.append("(?:");
                    emit(out, n.p, flags);
                    out.append("){").append(n.min).append(',');
                    if (n.max != MAXREPEAT) {
                        out.append(n.max);
                    }
                    out.append('}').append(n.kind);
                }
                case SUBPATTERN -> {
                    int inner = (flags | n.addFlags) & ~n.delFlags;
                    if (n.group != null) {
                        out.append("(?<g").append(n.group).append('>');
                    }
                    out.append("(?").append(javaFlags(inner)).append(':');
                    emit(out, n.p, inner);
                    out.append(')');
                    if (n.group != null) {
                        out.append(")(?<e").append(n.group).append(">)");
                    }
                }
                case ATOMIC -> {
                    out.append("(?>");
                    emit(out, n.p, flags);
                    out.append(')');
                }
                case ASSERT, ASSERT_NOT -> {
                    out.append("(?").append(n.dir < 0 ? "<" : "").append(n.op == Op.ASSERT ? '=' : '!');
                    emit(out, n.p, flags);
                    out.append(')');
                }
                case FAILURE -> out.append("(?!)");
                case BRANCH -> {
                    out.append("(?:");
                    for (int i = 0; i < n.items.size(); i++) {
                        out.append(i > 0 ? "|" : "");
                        emit(out, n.items.get(i), flags);
                    }
                    out.append(')');
                }
                case GROUPREF -> out.append(fold ? "(?iu:" : "(?:").append("\\k<g").append(n.group).append(">)");
                case GROUPREF_EXISTS -> {
                    // Java has no conditional groups; the empty group after each group tells whether it matched
                    out.append("(?:\\k<e").append(n.group).append('>');
                    emit(out, n.p, flags);
                    out.append("|(?!\\k<e").append(n.group).append(">)");
                    if (n.no != null) {
                        emit(out, n.no, flags);
                    }
                    out.append(')');
                }
                default -> throw new IllegalStateException(n.op.name());
            }
        }
    }
}
