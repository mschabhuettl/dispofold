package app.dispofold.desktop.launcher;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/** Starts the app as a child process; a seam so that the launcher logic can be tested without real processes. */
interface AppRunner {

    /** Child exit code: update staged, start again. */
    int EXIT_RESTART = 3;
    /** Child exit code: the database password is wrong. */
    int EXIT_WRONG_PASSWORD = 4;

    /** Starts the app on a free loopback port; it gets password and secret on its stdin. */
    Child start(Path quarkusApp, String version, String password, String secret) throws IOException;

    /** Runs the app's password change on the closed database and returns its exit code. */
    int changePassword(Path quarkusApp, String version, String oldPassword, String newPassword) throws IOException;

    interface Child {

        /** Waits until the app sent a valid ready line and answers ready; false if it exited or the time ran out. */
        boolean awaitReady(Duration timeout);

        /** The checked ready line ({@code port}, {@code pid}, {@code nonce}, {@code proof}) after awaitReady. */
        Map<String, Object> readyLine();

        /** Waits for the exit and returns the exit code. */
        int waitFor();

        /** Stops the process (end of its stdin, then terminate) and waits for it; returns the exit code. */
        int stop();
    }
}
