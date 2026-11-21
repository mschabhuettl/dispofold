package app.dispofold.plugins.sp5.port;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;

import org.junit.jupiter.api.Test;

/** Python's re and fnmatch, with the messages and results of Python 3.14. */
class PyReTest {

    static void error(String pattern, String msg, Integer pos) {
        assertThatThrownBy(() -> PyRe.compile(pattern)).as(pattern).isInstanceOfSatisfying(PyRe.Error.class, e -> {
            assertThat(e.msg()).isEqualTo(msg);
            assertThat(e.pos()).isEqualTo(pos);
        });
    }

    @Test
    void errorsAsPython() {
        error("^(A|R", "missing ), unterminated subpattern", 1);
        error("*x", "nothing to repeat", 0);
        error("(", "missing ), unterminated subpattern", 0);
        error("[a", "unterminated character set", 0);
        error("a{2,1}", "min repeat greater than max repeat", 2);
        error("a**", "multiple repeat", 2);
        error("\\q", "bad escape \\q", 0);
        error("(?i)a(?m)", "global flags not at the start of the expression", 5);
        error("(?<=a*)b", "look-behind requires fixed-width pattern", null);
        error("(?P<x>a)(?P<x>b)", "redefinition of group name 'x' as group 2; was group 1", 12);
        error("a)", "unbalanced parenthesis", 1);
    }

    @Test
    void matchingAsPython() throws Exception {
        assertThat(PyRe.match(PyRe.compile("^[FSNT]$"), "F")).isTrue();
        assertThat(PyRe.match(PyRe.compile("^[FSNT]$"), "F\n")).isTrue(); // $ before a final newline
        assertThat(PyRe.match(PyRe.compile("^[FSNT]$"), "FS")).isFalse();
        assertThat(PyRe.match(PyRe.compile("A\\d"), "A٣")).isTrue(); // \d is Unicode
        assertThat(PyRe.match(PyRe.compile("(?i)straße"), "STRASSE")).isFalse();
        assertThat(PyRe.match(PyRe.compile("(a)?(?(1)b|c)"), "ab")).isTrue();
        assertThat(PyRe.match(PyRe.compile("(a)?(?(1)b|c)"), "c")).isTrue();
        assertThat(PyRe.match(PyRe.compile("(a)?(?(1)b|c)"), "ac")).isFalse();
        assertThat(PyRe.match(PyRe.compile("x{,2}y"), "xxy")).isTrue();
        assertThat(PyRe.match(PyRe.compile("a{"), "a{")).isTrue(); // not a repeat: literal
    }

    @Test
    void ignoreCaseFoldsAsSre() throws Exception {
        assertThat(PyRe.match(PyRe.compile("(?i)^gro[ß]team$"), "Groẞteam")).isTrue();
        assertThat(PyRe.match(PyRe.compile("(?i)[s-t]"), "ſ")).isTrue(); // the extra case of s
        assertThat(PyRe.match(PyRe.compile("(?i)[^s]"), "ſ")).isFalse();
        assertThat(PyRe.match(PyRe.compile("(?i)k"), "K")).isTrue(); // Kelvin sign
        assertThat(PyRe.match(PyRe.compile("(?ia)k"), "K")).isFalse(); // ASCII only
        assertThat(PyRe.match(PyRe.compile("(?i)(a)\\1"), "aA")).isTrue();
    }

    @Test
    void globStarsDoNotBacktrackIntoEachOther() {
        assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> assertThat(PyRe.fnmatchcase("a".repeat(49), "*a".repeat(12) + "b")).isFalse());
        assertThat(PyRe.translate("*a*b?c*")).isEqualTo("(?s:(?>.*?a)(?>.*?b.c).*)\\z");
    }

    @Test
    void fnmatchcaseAsPython() {
        assertThat(PyRe.fnmatchcase("X1-T", "X1-*")).isTrue();
        assertThat(PyRe.fnmatchcase("x1-t", "X1-*")).isFalse();
        assertThat(PyRe.fnmatchcase("-", "[a-]")).isTrue();
        assertThat(PyRe.fnmatchcase("b", "[!a]")).isTrue();
        assertThat(PyRe.fnmatchcase("!", "[!]")).isFalse();
        assertThat(PyRe.fnmatchcase("[", "[")).isTrue();
        assertThat(PyRe.fnmatchcase("z", "[z-a]")).isFalse();
        assertThat(PyRe.fnmatchcase("é", "?")).isTrue();
        assertThat(PyRe.fnmatchcase("a\nb", "a*b")).isTrue();
        assertThat(PyRe.fnmatchcase("Team (A)", "Team (*)")).isTrue();
    }
}
