package com.miaokatze.gtswn.crossmod.bq;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

/** Filesystem-only stamp persistence, independent of the optional BetterQuesting runtime. */
final class BqStampFile {

    private BqStampFile() {}

    /**
     * Create the fresh world's directory and publish only a fully written stamp. If atomic replacement is
     * unavailable, fail without truncating the previous stamp; the injector logs and retries next startup.
     */
    static void write(File file, String version) throws IOException {
        Path target = file.toPath()
            .toAbsolutePath();
        Path directory = target.getParent();
        // Also rejects a parent that exists as a regular file instead of a directory.
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, file.getName() + ".", ".tmp");
        try {
            JsonObject stamp = new JsonObject();
            stamp.addProperty("version", version);
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                new Gson().toJson(stamp, writer);
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
