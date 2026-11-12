package app.dispofold.desktop.launcher;

import java.util.Map;

/**
 * What the launcher tells and asks: the program window over the line protocol ({@link ShellUi}), or the console
 * and dialogs for {@code --change-password} ({@link SystemUi}). Every message has a stable code, params (ids,
 * versions, numbers; never paths) and a German text for the console.
 */
interface Ui {

    /** A note; the launcher goes on. */
    void warn(String code, Map<String, Object> params, String text);

    /** The launcher gives up after this. */
    void fail(String code, Map<String, Object> params, String text);

    /** A success note (console only). */
    default void info(String code, String text) {
        warn(code, Map.of(), text);
    }

    /**
     * Asks for the password. On the first start (no database yet) it asks twice and warns that a lost password
     * means lost data. {@code retry}: the previous password was wrong. Returns null when cancelled or impossible.
     */
    String password(boolean firstStart, boolean retry);

    /** Before each start of the app. */
    default void starting(String version) {
    }

    /** The app is up; {@code line} is its checked ready line. */
    default void ready(Map<String, Object> line) {
    }
}
