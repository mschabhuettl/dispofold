package app.dispofold.desktop;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import jakarta.enterprise.context.ApplicationScoped;

import io.quarkus.arc.profile.IfBuildProfile;
import io.quarkus.runtime.Quarkus;

/** Ends the app shortly after the current response went out; a bean so that tests can replace it. */
@IfBuildProfile("desktop")
@ApplicationScoped
public class DesktopExit {

    /** Exit code: an update is staged, the launcher starts again. */
    public static final int RESTART = 3;

    public void exitSoon(int code) {
        CompletableFuture.delayedExecutor(500, TimeUnit.MILLISECONDS).execute(() -> Quarkus.asyncExit(code));
    }
}
