package app.dispofold.desktop;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

/** Records exit codes instead of ending the test app (enabled by {@link DesktopProfile}). */
@Alternative
@Priority(1)
@ApplicationScoped
public class RecordingExit extends DesktopExit {

    private final List<Integer> codes = new CopyOnWriteArrayList<>();

    /** A method, not the field: the test sees the bean through its client proxy. */
    public List<Integer> codes() {
        return codes;
    }

    @Override
    public void exitSoon(int code) {
        codes.add(code);
    }
}
