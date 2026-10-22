package com.miaokatze.gtswn.client.engineering.board;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Properties;

/** Atomic per-source preference files; a malformed file falls back to usable defaults. */
public final class PortableBoardStore {

    private final Path directory;

    public PortableBoardStore(Path directory) {
        this.directory = directory;
    }

    public PortableBoardLayout load(String identity, List<String> panels) {
        Properties p = new Properties();
        Path file = path(identity);
        if (Files.isRegularFile(file)) try (InputStream in = Files.newInputStream(file)) {
            p.load(in);
        } catch (IOException | IllegalArgumentException ignored) {
            p.clear();
        }
        return PortableBoardLayout.read(p, panels);
    }

    public boolean save(String identity, PortableBoardLayout layout) {
        Path file = path(identity), temp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(directory);
            try (OutputStream out = Files.newOutputStream(temp)) {
                layout.write()
                    .store(out, "GTSWN local engineering board layout");
            }
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }

    private Path path(String identity) {
        if (!identity.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid board identity");
        return directory.resolve(identity + ".properties");
    }
}
