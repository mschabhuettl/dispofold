package app.dispofold.desktop.launcher;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

/** Exclusive lock on {@code daten/dispofold.lock}, held for the life of the launcher. */
final class InstanceLock implements AutoCloseable {

    private final FileChannel channel;
    private final FileLock lock;

    private InstanceLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    /** Returns the lock, or empty if another instance holds it. */
    static Optional<InstanceLock> tryAcquire(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock l = ch.tryLock();
            if (l != null) {
                return Optional.of(new InstanceLock(ch, l));
            }
        } catch (OverlappingFileLockException e) {
            // Held by this process already.
        }
        ch.close();
        return Optional.empty();
    }

    @Override
    public void close() throws IOException {
        lock.release();
        channel.close();
    }
}
