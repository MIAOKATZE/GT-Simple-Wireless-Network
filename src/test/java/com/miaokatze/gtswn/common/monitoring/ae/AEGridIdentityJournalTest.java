// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.common.monitoring.ae;

import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;

import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

public class AEGridIdentityJournalTest {

    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void migrationPreservesOldFileAndOnlyWritesChangedMappingsAndRetirements() throws IOException {
        Path folder = temporary.getRoot()
            .toPath();
        Path legacy = folder.resolve(AEGridIdentityData.DATA_NAME + ".dat");
        AEGridIdentityData previous = new AEGridIdentityData(AEGridIdentityData.DATA_NAME);
        for (int i = 0; i < 4096; i++) assertTrue(previous.commit(i, new GridIdentityState.Origin(UUID.randomUUID())));
        UUID original = identity(previous, 0);
        NBTTagCompound body = new NBTTagCompound(), root = new NBTTagCompound();
        previous.writeToNBT(body);
        NBTTagList historical = new NBTTagList();
        for (int i = 0; i <= 2048; i++) {
            NBTTagCompound record = new NBTTagCompound();
            record.setString("identity", new UUID(0, i % 2048).toString());
            historical.appendTag(record);
        }
        body.setTag("retired", historical); // Older data can contain duplicate tombstones.
        root.setTag("data", body);
        try (var stream = Files.newOutputStream(legacy)) {
            CompressedStreamTools.writeCompressed(root, stream);
        }
        byte[] legacyBytes = Files.readAllBytes(legacy);

        AEGridIdentityData data = open(folder);
        assertEquals(original, identity(data, 0));
        data.flushPending();
        assertTrue(Files.notExists(batch(folder, 1)));
        UUID intermediate = UUID.randomUUID(), latest = UUID.randomUUID();
        assertTrue(data.commit(0, new GridIdentityState.Origin(intermediate)));
        assertTrue(data.commit(0, new GridIdentityState.Origin(latest)));
        data.flushPending();
        assertEquals(84L, Files.size(batch(folder, 1))); // Header, one mapping, two retirements, checksum.
        data.closeJournal(true);
        assertArrayEquals(legacyBytes, Files.readAllBytes(legacy));

        AEGridIdentityData recovered = open(folder);
        assertEquals(latest, identity(recovered, 0));
        assertEquals(identity(previous, 4095), identity(recovered, 4095));
        assertFalse(recovered.commit(5500, new GridIdentityState.Origin(original)));
        assertFalse(recovered.commit(5501, new GridIdentityState.Origin(intermediate)));
        try (var stream = Files.newInputStream(recovered.exportLegacy())) {
            AEGridIdentityData downgrade = new AEGridIdentityData(AEGridIdentityData.DATA_NAME);
            NBTTagCompound exported = CompressedStreamTools.readCompressed(stream)
                .getCompoundTag("data");
            assertEquals(
                2050,
                exported.getTagList("retired", 10)
                    .tagCount());
            downgrade.readFromNBT(exported);
            assertEquals(latest, identity(downgrade, 0));
            assertFalse(downgrade.commit(5501, new GridIdentityState.Origin(intermediate)));
            for (int i = 0; i < 2048; i++) {
                UUID retired = new UUID(0, i);
                assertFalse(recovered.commit(5502, new GridIdentityState.Origin(retired)));
                assertFalse(downgrade.commit(5502, new GridIdentityState.Origin(retired)));
            }
        }
        recovered.closeJournal(true);
    }

    @Test
    public void failedPublishKeepsPendingChangesForRetry() throws IOException {
        Path folder = temporary.getRoot()
            .toPath();
        AEGridIdentityData data = open(folder);
        UUID id = UUID.randomUUID();
        assertTrue(data.commit(5, new GridIdentityState.Origin(id)));
        Files.createDirectory(batch(folder, 1));
        assertThrows(IOException.class, data::flushPending);
        Files.delete(batch(folder, 1));
        data.flushPending();
        data.closeJournal(true);
        AEGridIdentityData restarted = open(folder);
        assertEquals(id, identity(restarted, 5));
        restarted.closeJournal(true);
    }

    @Test
    public void checkpointReplaysLaterBatchesAndPreservesRetirements() throws IOException {
        Path directory = temporary.getRoot()
            .toPath();
        UUID old = UUID.randomUUID(), current = UUID.randomUUID(), later = UUID.randomUUID();
        AEGridIdentityJournal journal = new AEGridIdentityJournal(directory);
        journal.open(new AEGridIdentityJournal.State(0, ImmutableMap.of(5L, old), ImmutableSet.of(), false));
        journal.save(ImmutableMap.of(5L, current), ImmutableSet.of(old));
        journal.save(ImmutableMap.of(6L, later), ImmutableSet.of());
        journal.compact(1);
        journal.close(true);
        AEGridIdentityJournal reopened = new AEGridIdentityJournal(directory);
        AEGridIdentityJournal.State result = reopened.open(empty());
        assertEquals(ImmutableMap.of(5L, current, 6L, later), result.storages);
        assertEquals(ImmutableSet.of(old), result.retired);
        reopened.compact(2);
        assertTrue(Files.notExists(directory.resolve("0000000000000000001.delta")));
        assertTrue(Files.exists(directory.resolve("0000000000000000002.delta")));
        reopened.close(true);
    }

    @Test
    public void uncleanStopFencesOldNativeTagsAndKeepsNewIdentitiesAcrossCleanRestarts() throws IOException {
        Path folder = temporary.getRoot()
            .toPath();
        AEGridIdentityData interrupted = open(folder);
        UUID old = UUID.randomUUID(), fresh = UUID.randomUUID(), unrecorded = UUID.randomUUID();
        assertTrue(interrupted.commit(7, new GridIdentityState.Origin(old)));
        interrupted.flushPending();
        assertTrue(interrupted.commit(7, new GridIdentityState.Origin(unrecorded)));

        AEGridIdentityData recovered = open(folder);
        assertTrue(recovered.restore(7, tagged(old), null).retired);
        assertTrue(recovered.restore(50, tagged(unrecorded), null).retired);
        assertThrows(IOException.class, recovered::exportLegacy);
        assertTrue(recovered.commit(7, new GridIdentityState.Origin(fresh)));
        recovered.closeJournal(true);

        AEGridIdentityData restarted = open(folder);
        assertEquals(fresh, identity(restarted, 7));
        assertFalse(restarted.commit(50, new GridIdentityState.Origin(unrecorded)));
        restarted.closeJournal(true);
    }

    @Test
    public void damagedCommittedBatchBlocksRecoveryWithoutSilentlyRestoringStaleMappings() throws IOException {
        Path folder = temporary.getRoot()
            .toPath();
        AEGridIdentityData data = open(folder);
        UUID id = UUID.randomUUID();
        assertTrue(data.commit(7, new GridIdentityState.Origin(id)));
        data.closeJournal(true);
        Path batch = batch(folder, 1);
        byte[] bytes = Files.readAllBytes(batch);
        bytes[25] ^= 1;
        Files.write(batch, bytes);

        AEGridIdentityData blocked = new AEGridIdentityData(AEGridIdentityData.DATA_NAME);
        blocked.openJournal(folder);
        assertFalse(blocked.isAvailable());
        assertTrue(blocked.restore(7, tagged(id), null).retired);
        assertFalse(blocked.commit(7, new GridIdentityState.Origin(UUID.randomUUID())));
        assertArrayEquals(bytes, Files.readAllBytes(batch));
    }

    private static AEGridIdentityJournal.State empty() {
        return new AEGridIdentityJournal.State(0, ImmutableMap.of(), ImmutableSet.of(), false);
    }

    private static UUID identity(AEGridIdentityData data, long storage) {
        return data.restore(storage, new NBTTagCompound(), null).id;
    }

    private static AEGridIdentityData open(Path path) {
        AEGridIdentityData data = new AEGridIdentityData(AEGridIdentityData.DATA_NAME);
        data.openJournal(path);
        assertTrue(data.isAvailable());
        return data;
    }

    private static Path batch(Path path, int revision) {
        return path.resolve(AEGridIdentityData.DATA_NAME + "_journal")
            .resolve(String.format(Locale.ROOT, "%019d.delta", revision));
    }

    private static NBTTagCompound tagged(UUID id) {
        NBTTagCompound root = new NBTTagCompound(), tag = new NBTTagCompound();
        tag.setInteger("version", 1);
        tag.setString("id", id.toString());
        root.setTag(MonitorIdentityGridCache.TAG, tag);
        return root;
    }
}
