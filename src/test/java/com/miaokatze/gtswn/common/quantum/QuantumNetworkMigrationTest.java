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
