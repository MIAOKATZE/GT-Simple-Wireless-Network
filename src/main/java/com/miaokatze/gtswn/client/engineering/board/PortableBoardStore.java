package com.miaokatze.gtswn.client.engineering.board;

import java.nio.file.Path;
import java.util.List;

import com.miaokatze.gtswn.client.engineering.LocalPropertiesFile;

/** Per-source schema owner; byte persistence is shared with desktop preferences. */
public final class PortableBoardStore {

    public static final int MAX_FILE_BYTES = 4 * 1024 * 1024;
    private final Path directory;

    public PortableBoardStore(Path directory) {
        this.directory = directory;
    }

    public PortableBoardLayout load(String identity, List<String> panels) {
        return PortableBoardLayout
            .read(LocalPropertiesFile.read(LocalPropertiesFile.path(directory, identity), MAX_FILE_BYTES), panels);
    }

    public boolean save(String identity, PortableBoardLayout layout) {
        return LocalPropertiesFile.write(
            LocalPropertiesFile.path(directory, identity),
            layout.write(),
            "GTSWN local engineering board layout",
            MAX_FILE_BYTES);
    }
}
