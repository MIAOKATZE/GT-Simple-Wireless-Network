package com.miaokatze.gtswn.crossmod.bq;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.google.gson.JsonParser;

public class BqStampFileTest {

    private static final String STAMP_NAME = "gtswn-injected.json";

    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void freshWorldCreatesMissingParentBeforeWritingVersion() throws Exception {
        File parent = new File(temporary.getRoot(), "World/betterquesting");
        File stamp = new File(parent, STAMP_NAME);
        assertFalse(parent.exists());
        BqStampFile.write(stamp, "fresh-version");
        assertTrue(parent.isDirectory());
        assertEquals("fresh-version", version(stamp));
        assertOnlyStamp(parent);
    }

    @Test
    public void replacementWritesCompleteUtf8JsonAndLeavesNoTemporaryFile() throws Exception {
        File parent = temporary.newFolder("betterquesting");
        File stamp = new File(parent, STAMP_NAME);
        BqStampFile.write(stamp, "previous-version");
        BqStampFile.write(stamp, "new-版本-\"quoted\"");
        assertEquals("new-版本-\"quoted\"", version(stamp));
        assertOnlyStamp(parent);
    }

    @Test
    public void regularFileParentIsRejectedWithoutModifyingIt() throws Exception {
        File parent = temporary.newFile("betterquesting");
        byte[] original = "not a directory".getBytes(StandardCharsets.UTF_8);
        Files.write(parent.toPath(), original);
        File stamp = new File(parent, STAMP_NAME);
        try {
            BqStampFile.write(stamp, "new-version");
            fail("A regular-file parent must not be accepted");
        } catch (IOException expected) {}
        assertArrayEquals(original, Files.readAllBytes(parent.toPath()));
        assertFalse(stamp.exists());
    }

    @Test
    public void failedPublicationPreservesDestinationAndCleansTemporaryFile() throws Exception {
        File parent = temporary.newFolder("betterquesting");
        File stamp = new File(parent, STAMP_NAME);
        Files.createDirectory(stamp.toPath());
        Path marker = new File(stamp, "preserve-me").toPath();
        byte[] original = "existing contents".getBytes(StandardCharsets.UTF_8);
        Files.write(marker, original);
        try {
            BqStampFile.write(stamp, "new-version");
            fail("A nonempty directory must not be replaced by a stamp");
        } catch (IOException expected) {}
        assertArrayEquals(original, Files.readAllBytes(marker));
        assertOnlyStamp(parent);
    }

    @Test
    public void unwritableParentPreservesPreviousStampRatherThanTruncatingIt() throws Exception {
        File parent = temporary.newFolder("betterquesting");
        File stamp = new File(parent, STAMP_NAME);
        byte[] original = "{\"version\":\"previous-version\"}".getBytes(StandardCharsets.UTF_8);
        Files.write(stamp.toPath(), original);
        Path directory = parent.toPath();
        assumeTrue(Files.getFileAttributeView(directory, PosixFileAttributeView.class) != null);
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(directory);
        try {
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("r-x------"));
            assumeFalse("Privileged users may bypass directory permissions", Files.isWritable(directory));
            assertTrue(Files.isWritable(stamp.toPath()));
            try {
                BqStampFile.write(stamp, "new-version");
                fail("Unwritable parent must reject temporary-file creation");
            } catch (IOException expected) {}
            assertArrayEquals(original, Files.readAllBytes(stamp.toPath()));
            assertOnlyStamp(parent);
        } finally {
            Files.setPosixFilePermissions(directory, permissions);
        }
    }

    private String version(File file) throws IOException {
        return new JsonParser().parse(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8))
            .getAsJsonObject()
            .get("version")
            .getAsString();
    }

    private void assertOnlyStamp(File parent) {
        assertArrayEquals(new String[] { STAMP_NAME }, parent.list());
    }
}
