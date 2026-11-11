// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.common.quantum;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.profiler.Profiler;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.WorldProviderSurface;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.storage.ISaveHandler;
import net.minecraft.world.storage.MapStorage;

import org.junit.Test;

import com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal;
import com.miaokatze.gtswn.common.tile.TileEntityNetworkQuantumNode;

public class QuantumNetworkMigrationTest {

    private static final AnchorKey A = new AnchorKey(7, -17, 64, 2);
    private static final AnchorKey B = new AnchorKey(8, 32, 70, -3);
    private static final AnchorKey C = new AnchorKey(9, 99, 80, 4);

    @Test
    public void offlineMembersReplayAcrossDimensionsAndJournalSaveReload() {
        QuantumMigrationJournal journal = journal();
        journal.append(A.getDimension(), members(A, new AnchorKey(7, -16, 64, 2)), B);
        NBTTagCompound saved = new NBTTagCompound();
        journal.writeToNBT(saved);
        QuantumMigrationJournal reloaded = journal();
        reloaded.readFromNBT(saved);
        assertEquals(B, reloaded.resolve(A, 0).anchor);
        assertEquals(B, reloaded.resolve(new AnchorKey(7, -16, 64, 2), 0).anchor);
        assertEquals(1L, reloaded.currentSequence());
        assertEquals(C, reloaded.resolve(C, 0).anchor);
        assertEquals(1L, reloaded.resolve(C, 0).sequence);
    }

    @Test
    public void chainedMovesAndReturnToOldCoordinatesApplyEachEventOnlyOnce() {
        QuantumMigrationJournal journal = journal();
        journal.append(7, members(A), B);
        journal.append(8, members(B), C);
        journal.append(9, members(C), A);
        assertEquals(A, journal.resolve(A, 0).anchor);
        assertEquals(3L, journal.resolve(A, 0).sequence);
        assertEquals(A, journal.resolve(B, 1).anchor);
        journal.append(7, members(A), B);
        assertEquals(B, journal.resolve(A, 3).anchor);
        assertEquals(4L, journal.resolve(A, 0).sequence);
    }

    @Test
    public void trustedNativePairingFollowsEveryHopAfterJournalNbtReload() {
        QuantumMigrationJournal journal = journal();
        journal.appendClusterMigration(7, members(A), B, "network-generation", "101", "202");
        journal.appendClusterMigration(8, members(B), C, "network-generation", "202", "303");
        NBTTagCompound saved = new NBTTagCompound();
        journal.writeToNBT(saved);
        QuantumMigrationJournal restored = journal();
        restored.readFromNBT(saved);
        QuantumMigrationJournal.Resolution resolution = restored.resolve(A, 0, "network-generation", "101");
        assertEquals(C, resolution.anchor);
        assertEquals("network-generation", resolution.generation);
        assertEquals("303", resolution.encryptionKey);
        assertEquals(2L, resolution.sequence);
        assertEquals(
            "303",
            restored.resolve(C, resolution.sequence, resolution.generation, resolution.encryptionKey).encryptionKey);
        restored.appendClusterMigration(9, members(C), A, "network-generation", "303", "404");
        assertEquals("404", restored.resolve(C, 2, "network-generation", "303").encryptionKey);
        assertEquals("404", restored.resolve(A, 0, "network-generation", "101").encryptionKey);
        assertEquals(A, restored.resolve(A, 0, "network-generation", "101").anchor);
    }

    @Test
    public void journalNeverPairsEmptyForeignStaleGenerationOrUnrelatedTerminal() {
        QuantumMigrationJournal journal = journal();
        journal.appendClusterMigration(7, members(A), B, "network-generation", "101", "202");
        journal.appendClusterMigration(8, members(B), C, "network-generation", "202", "303");
        for (String wrongKey : new String[] { "", "999", "malformed", "202" }) {
            QuantumMigrationJournal.Resolution resolution = journal.resolve(A, 0, "network-generation", wrongKey);
            assertEquals(C, resolution.anchor);
            assertEquals(wrongKey, resolution.encryptionKey);
        }
        for (String wrongGeneration : new String[] { "", "rebuilt-generation" }) {
            QuantumMigrationJournal.Resolution resolution = journal.resolve(A, 0, wrongGeneration, "101");
            assertEquals(C, resolution.anchor); // Legacy coordinate recovery remains independent of key authority.
            assertEquals("101", resolution.encryptionKey);
        }
        assertEquals("101", journal.resolve(C, 0, "network-generation", "101").encryptionKey);
        assertEquals("101", journal.resolve(A, journal.currentSequence(), "network-generation", "101").encryptionKey);
    }

    @Test
    public void oldOrIncompleteJournalRecordsMoveBindingsWithoutInventingCredentials() {
        QuantumMigrationJournal journal = journal();
        journal.appendClusterMigration(7, members(A), B, "network-generation");
        assertEquals("202", journal.resolve(A, 0, "network-generation", "202").encryptionKey);
        assertEquals("", journal.resolve(A, 0, "network-generation", "").encryptionKey);
        NBTTagCompound saved = new NBTTagCompound();
        journal.writeToNBT(saved);
        NBTTagCompound event = saved.getTagList("events", 10)
            .getCompoundTagAt(0);
        event.setString("pairingGeneration", "network-generation");
        event.setString("fromKey", "101");
        // A partial/unrecognized credential record is not an authorization to erase or create a native key.
        QuantumMigrationJournal restored = journal();
        restored.readFromNBT(saved);
        QuantumMigrationJournal.Resolution resolution = restored.resolve(A, 0, "network-generation", "101");
        assertEquals(B, resolution.anchor);
        assertEquals("101", resolution.encryptionKey);
        assertEquals("", restored.resolve(A, 0, "network-generation", "").encryptionKey);
    }

    @Test
    public void invalidNativePairingCannotAppendAnEventOrAdvanceTheJournal() {
        QuantumMigrationJournal journal = journal();
        for (String[] fields : new String[][] { { "", "101", "202" }, { "g", "", "202" }, { "g", "101", "invalid" } }) {
            try {
                journal.appendClusterMigration(7, members(A), B, fields[0], fields[1], fields[2]);
                fail("Invalid credential intent must fail before commit");
            } catch (IllegalArgumentException expected) {
                assertEquals(0L, journal.currentSequence());
                assertEquals(A, journal.resolve(A, 0, "g", "101").anchor);
            }
        }
    }

    @Test
    public void heldOfflineAndCopiedTerminalsReplayNativeKeysWithoutChangingOtherNbt() {
        EncodingTerminal item = new EncodingTerminal();
        ItemStack held = terminal(item, A);
        held.getTagCompound()
            .setString(ItemNetworkQuantumTerminal.NBT_ANCHOR_GENERATION, "network-generation");
        held.getTagCompound()
            .setString(QuantumTerminalAccess.NBT_ENCRYPTION_KEY, "101");
        held.getTagCompound()
            .setString("customName", "preserved");
        ItemStack copied = held.copy();
        NBTTagCompound storedItem = held.writeToNBT(new NBTTagCompound());
        // This unit fixture has an unregistered Item; reconstruct its serialized payload without item-ID claims.
        ItemStack offline = new ItemStack(held.getItem());
        offline.setTagCompound(
            (NBTTagCompound) storedItem.getCompoundTag("tag")
                .copy());
        QuantumMigrationJournal journal = journal();
        journal.appendClusterMigration(7, members(A), B, "network-generation", "101", "202");
        journal.appendClusterMigration(8, members(B), C, "network-generation", "202", "303");
        for (ItemStack stack : new ItemStack[] { held, copied, offline }) {
            assertTrue(QuantumTerminalAccess.applyPendingMigrations(stack, journal));
            assertArrayEquals(new int[] { 9, 99, 80, 4 }, ItemNetworkQuantumTerminal.getAnchor(stack));
            assertEquals(
                "303",
                stack.getTagCompound()
                    .getString(QuantumTerminalAccess.NBT_ENCRYPTION_KEY));
            assertEquals(
                "preserved",
                stack.getTagCompound()
                    .getString("customName"));
            assertEquals(
                "network-generation",
                stack.getTagCompound()
                    .getString(ItemNetworkQuantumTerminal.NBT_ANCHOR_GENERATION));
            assertFalse(QuantumTerminalAccess.applyPendingMigrations(stack, journal));
        }
        assertEquals(3, item.encodings);
        for (String originalKey : new String[] { "", "999" }) {
            ItemStack rejected = terminal(item, A);
            rejected.getTagCompound()
                .setString(ItemNetworkQuantumTerminal.NBT_ANCHOR_GENERATION, "network-generation");
            if (!originalKey.isEmpty()) rejected.getTagCompound()
                .setString(QuantumTerminalAccess.NBT_ENCRYPTION_KEY, originalKey);
            assertTrue(QuantumTerminalAccess.applyPendingMigrations(rejected, journal));
            assertArrayEquals(new int[] { 9, 99, 80, 4 }, ItemNetworkQuantumTerminal.getAnchor(rejected));
            assertEquals(
                originalKey,
                rejected.getTagCompound()
                    .getString(QuantumTerminalAccess.NBT_ENCRYPTION_KEY));
            if (originalKey.isEmpty()) assertFalse(
                rejected.getTagCompound()
                    .hasKey(QuantumTerminalAccess.NBT_ENCRYPTION_KEY));
        }
        assertEquals(3, item.encodings);
    }

    @Test
    public void newBindingAtReusedControllerCoordinatesIgnoresHistoricalMoves() throws Exception {
        FixtureWorld world = world();
        QuantumMigrationJournal journal = QuantumMigrationJournal.get(world);
        QuantumControllerRegistry controllers = QuantumControllerRegistry.get(world);
        NBTTagCompound legacy = new NBTTagCompound();
        legacy.setIntArray("controllers", new int[] { 7, A.getX(), A.getY(), A.getZ() });
        controllers.readFromNBT(legacy);
        String previous = controllers
            .canonicalAnchor(QuantumControllerRegistry.pack(A.getX(), A.getY(), A.getZ())).generation;
        journal.append(7, members(A), B, previous, previous, members(A));
        controllers.dequantize(A.getX(), A.getY(), A.getZ());
        controllers.quantize(A.getX(), A.getY(), A.getZ());
        String rebuilt = controllers
            .canonicalAnchor(QuantumControllerRegistry.pack(A.getX(), A.getY(), A.getZ())).generation;
        assertTrue(
            controllers.legacyMembers(members(A), rebuilt)
                .isEmpty());
        Node fresh = new Node();
        fresh.setWorldObj(world);
        fresh.setAnchor(7, A.getX(), A.getY(), A.getZ(), rebuilt);
        fresh.applyPendingMigrations(journal);
        assertEquals(A, anchor(fresh));
        journal.append(7, members(A), C, rebuilt, rebuilt);
        fresh.applyPendingMigrations(journal);
        assertEquals(C, anchor(fresh));
        assertEquals(rebuilt, fresh.getAnchorGeneration());
        QuantumNetworkColorSync.untrack(fresh);
    }

    @Test
    public void nodeLazyMigrationKeepsProxyOwnerSnapshotAndExactPartContainer() {
        Node node = new Node();
        node.setAnchor(7, A.getX(), A.getY(), A.getZ());
        NBTTagCompound security = new NBTTagCompound();
        NBTTagCompound proxy = new NBTTagCompound();
        proxy.setInteger("playerID", 4711);
        proxy.setLong("securityKey", 128L);
        security.setTag("proxy", proxy);
        node.restoreProxyAfterReplacement(security);
        Object parts = node.getPartContainer();
        QuantumMigrationJournal journal = journal();
        journal.append(7, members(A), B, "generation", "generation", members(A));
        journal.append(8, members(B), C, "generation", "generation");
        node.applyPendingMigrations(journal);
        assertEquals(C, anchor(node));
        assertEquals("generation", node.getAnchorGeneration());
        assertSame(parts, node.getPartContainer());
        assertSame(security, node.snapshotProxyForReplacement());
        assertEquals(
            4711,
            node.snapshotProxyForReplacement()
                .getCompoundTag("proxy")
                .getInteger("playerID"));
    }

    @Test
    public void unloadedIncorporationReplaysBeforeControllerRemovalWithoutLosingOwnerOrIdentity() throws Exception {
        FixtureWorld world = world();
        QuantumIncorporationRegistry registry = new QuantumIncorporationRegistry("fixture");
        registry.readFromNBT(incorporationSave(A, 0));
        QuantumMigrationJournal journal = journal();
        journal.append(7, members(A), B, "generation", "generation", members(A));
        journal.append(8, members(B), C, "generation", "generation");
        registry.applyPendingMigrations(world, journal);
        registry.controllerRemoved(world, 7, members(A));
        NBTTagCompound saved = new NBTTagCompound();
        registry.writeToNBT(saved);
        NBTTagList entries = saved.getTagList("entries", 10);
        assertEquals(1, entries.tagCount());
        NBTTagCompound entry = entries.getCompoundTagAt(0);
        assertEquals("original-owner", entry.getString("owner"));
        assertEquals("original-identity", entry.getString("identity"));
        assertEquals("generation", entry.getString("anchorGeneration"));
        assertEquals(9, entry.getInteger("dim"));
        assertEquals(99, entry.getInteger("x"));
        assertEquals(2L, entry.getLong("lastAppliedMigration"));
        QuantumIncorporationRegistry restored = new QuantumIncorporationRegistry("fixture");
        restored.readFromNBT(saved);
        restored.applyPendingMigrations(world, journal);
        assertEquals(
            9,
            restored.snapshot()
                .iterator()
                .next().dim);
    }

    @Test
    public void incorporationCursorProtectsNewRegistrationsAtReusedCoordinates() throws Exception {
        QuantumMigrationJournal journal = journal();
        journal.append(7, members(A), B);
        QuantumIncorporationRegistry registry = new QuantumIncorporationRegistry("fixture");
        registry.readFromNBT(incorporationSave(A, journal.currentSequence()));
        registry.applyPendingMigrations(world(), journal);
        assertEquals(
            7,
            registry.snapshot()
                .iterator()
                .next().dim);
    }

    @Test
    public void visualSnapshotReentrySeesCommittedCursorAndUpdatedSourceIndex() throws Exception {
        FixtureWorld world = world();
        QuantumMigrationJournal journal = journal();
        journal.append(7, members(A), B);
        final int[] broadcasts = { 0 };
        QuantumIncorporationRegistry registry = new QuantumIncorporationRegistry("fixture") {

            @Override
            protected void migrationVisualsChanged(World localWorld, Set<Long> chunks) {
                assertEquals(1, ++broadcasts[0]);
                assertEquals(1, chunks.size());
                applyPendingMigrations(localWorld, journal);
                // This would remove the registration if the source index had not been rebuilt before notification.
                controllerRemoved(localWorld, 7, members(A));
                assertTrue(
                    snapshot().iterator()
                        .hasNext());
                assertEquals(
                    8,
                    snapshot().iterator()
                        .next().dim);
            }
        };
        registry.readFromNBT(incorporationSave(A, 0));
        registry.applyPendingMigrations(world, journal);
        assertEquals(1, broadcasts[0]);
    }

    @Test
    public void terminalsWaitForValidationBeforeApplyingCommittedMoves() throws Exception {
        FixtureWorld world = world();
        QuantumControllerRegistry.get(world)
            .quantize(B.getX(), B.getY(), B.getZ());
        ItemNetworkQuantumTerminal item = new ItemNetworkQuantumTerminal();
        ItemStack initiating = terminal(item, A);
        ItemStack other = terminal(item, A);
        NBTTagCompound original = (NBTTagCompound) other.getTagCompound()
            .copy();
        QuantumMigrationJournal journal = QuantumMigrationJournal.get(world);
        journal.append(7, members(A), B);
        ItemNetworkQuantumTerminal.writeAnchor(initiating, world, B.getX(), B.getY(), B.getZ());
        assertEquals(original, other.getTagCompound());
        assertArrayEquals(new int[] { 7, -17, 64, 2 }, ItemNetworkQuantumTerminal.getAnchor(other));
        assertArrayEquals(new int[] { 0, 32, 70, -3 }, ItemNetworkQuantumTerminal.getAnchor(initiating));
    }

    private static QuantumMigrationJournal journal() {
        return new QuantumMigrationJournal("fixture");
    }

    private static Set<Long> members(AnchorKey... anchors) {
        Set<Long> result = new HashSet<>();
        Arrays.stream(anchors)
            .forEach(a -> result.add(QuantumControllerRegistry.pack(a.getX(), a.getY(), a.getZ())));
        return result;
    }

    private static AnchorKey anchor(TileEntityNetworkQuantumNode node) {
        return new AnchorKey(node.getAnchorDim(), node.getAnchorX(), node.getAnchorY(), node.getAnchorZ());
    }

    private static NBTTagCompound incorporationSave(AnchorKey anchor, long cursor) {
        NBTTagCompound tag = new NBTTagCompound();
        NBTTagCompound entry = new NBTTagCompound();
        entry.setLong("pos", QuantumControllerRegistry.pack(500, 64, 500));
        entry.setString("owner", "original-owner");
        entry.setString("identity", "original-identity");
        entry.setString("block", "original-block");
        entry.setString("tile", "original-class");
        entry.setInteger("dim", anchor.getDimension());
        entry.setInteger("x", anchor.getX());
        entry.setInteger("y", anchor.getY());
        entry.setInteger("z", anchor.getZ());
        entry.setLong("lastAppliedMigration", cursor);
        NBTTagList list = new NBTTagList();
        list.appendTag(entry);
        tag.setTag("entries", list);
        return tag;
    }

    private static ItemStack terminal(ItemNetworkQuantumTerminal item, AnchorKey anchor) {
        ItemStack stack = new ItemStack(item);
        NBTTagCompound tag = new NBTTagCompound();
        tag.setByte(ItemNetworkQuantumTerminal.NBT_BOUND, (byte) 1);
        tag.setInteger(ItemNetworkQuantumTerminal.NBT_ANCHOR_DIM, anchor.getDimension());
        tag.setInteger(ItemNetworkQuantumTerminal.NBT_ANCHOR_X, anchor.getX());
        tag.setInteger(ItemNetworkQuantumTerminal.NBT_ANCHOR_Y, anchor.getY());
        tag.setInteger(ItemNetworkQuantumTerminal.NBT_ANCHOR_Z, anchor.getZ());
        stack.setTagCompound(tag);
        return stack;
    }

    private static FixtureWorld world() throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        Field singleton = unsafe.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        FixtureWorld world = (FixtureWorld) unsafe.getMethod("allocateInstance", Class.class)
            .invoke(singleton.get(null), FixtureWorld.class);
        Field provider = World.class.getDeclaredField("provider");
        provider.setAccessible(true);
        provider.set(world, new WorldProviderSurface());
        Field storage = World.class.getDeclaredField("perWorldStorage");
        storage.setAccessible(true);
        storage.set(world, new MapStorage(null));
        return world;
    }

    /** Unit-only encoder records INetworkEncodable dispatch; the test-dimension case uses the real AE registry. */
    private static final class EncodingTerminal extends ItemNetworkQuantumTerminal {

        int encodings;

        @Override
        public void setEncryptionKey(ItemStack stack, String key, String name) {
            encodings++;
            stack.getTagCompound()
                .setString(QuantumTerminalAccess.NBT_ENCRYPTION_KEY, key);
        }
    }

    private static final class Node extends TileEntityNetworkQuantumNode {

        @Override
        public void markDirty() {}
    }

    private static final class FixtureWorld extends World {

        private FixtureWorld() {
            super((ISaveHandler) null, "fixture", (WorldProvider) null, (WorldSettings) null, (Profiler) null);
        }

        @Override
        protected IChunkProvider createChunkProvider() {
            return null;
        }

        @Override
        protected int func_152379_p() {
            return 0;
        }

        @Override
        public Entity getEntityByID(int id) {
            return null;
        }

        @Override
        public boolean blockExists(int x, int y, int z) {
            return false;
        }

        @Override
        public TileEntity getTileEntity(int x, int y, int z) {
            throw new AssertionError("Migration must never read an unloaded chunk");
        }

        @Override
        public void markBlockForUpdate(int x, int y, int z) {}
    }
}
