package app.dispofold.desktop.update;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A release version {@code x.y.z}; nothing else is accepted, so it is safe as a folder name. */
public record Version(int major, int minor, int patch) implements Comparable<Version> {

    private static final Pattern FORMAT = Pattern.compile("(0|[1-9]\\d{0,8})\\.(0|[1-9]\\d{0,8})\\.(0|[1-9]\\d{0,8})");

    public static Version parse(String text) {
        Matcher m = text == null ? null : FORMAT.matcher(text);
        if (m == null || !m.matches()) {
            throw new IllegalArgumentException("Keine gültige Versionsnummer (x.y.z): " + text);
        }
        return new Version(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
    }

    public static boolean isValid(String text) {
        return text != null && FORMAT.matcher(text).matches();
    }

    public boolean isNewerThan(Version other) {
        return other == null || compareTo(other) > 0;
    }

    @Override
    public int compareTo(Version o) {
        int c = Integer.compare(major, o.major);
        if (c == 0) {
            c = Integer.compare(minor, o.minor);
        }
        return c != 0 ? c : Integer.compare(patch, o.patch);
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }
}
