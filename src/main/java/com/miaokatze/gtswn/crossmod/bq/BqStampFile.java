// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.crossmod.bq;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import com.google.gson.JsonObject;
import com.miaokatze.miaokatzecore.api.io.AtomicFilePublisher;

/** Completion receipt; callers must first verify the actual BQ definition file. */
final class BqStampFile {

    private BqStampFile() {}

    static void write(File file, String version) throws IOException {
        JsonObject stamp = new JsonObject();
        stamp.addProperty("version", version);
        java.nio.file.Files.createDirectories(
            file.toPath()
                .toAbsolutePath()
                .getParent());
        AtomicFilePublisher.publishAtomic(
            file.toPath(),
            stamp.toString()
                .getBytes(StandardCharsets.UTF_8));
    }
}
