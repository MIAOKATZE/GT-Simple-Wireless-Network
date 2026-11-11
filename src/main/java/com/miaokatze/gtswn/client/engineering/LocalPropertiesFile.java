// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.client.engineering;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import com.miaokatze.miaokatzecore.api.io.AtomicFilePublisher;

/** Local preference bytes only; schemas, dirty state and save sessions remain with the caller. */
public final class LocalPropertiesFile {

    private LocalPropertiesFile() {}

    public static Path path(Path directory, String identity) {
        if (identity == null || !identity.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("identity");
        return directory.resolve(identity + ".properties");
    }

    public static Properties read(Path file, int maxBytes) {
        Properties value = new Properties();
        try {
            if (Files.isRegularFile(file) && Files.size(file) <= maxBytes) {
                try (InputStream input = Files.newInputStream(file)) {
                    value.load(input);
                }
            }
        } catch (IOException | IllegalArgumentException ignored) {
            value.clear();
        }
        return value;
    }

    public static boolean write(Path file, Properties value, String comment, int maxBytes) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            value.store(bytes, comment);
            if (bytes.size() > maxBytes) return false;
            Files.createDirectories(
                file.toAbsolutePath()
                    .getParent());
            AtomicFilePublisher.publish(file, bytes.toByteArray());
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }
}
