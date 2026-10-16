package com.miaokatze.gtswn.common.quantum;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.profiler.Profiler;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.WorldProviderSurface;
import net.minecraft.world.WorldSavedData;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.storage.ISaveHandler;
import net.minecraft.world.storage.MapStorage;
import net.minecraftforge.common.DimensionManager;

import org.junit.Test;

import com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal;
import com.miaokatze.gtswn.common.tile.TileEntityNetworkQuantumNode;

public class QuantumTerminalUpgradeTest {

    private static final int DIM = 37281;

    @Test
    public void inventoryHookUpgradesWithoutUiAndPersistedMarkerSkipsAllWork() throws Exception {
        try (Fixture fixture = new Fixture(true)) {
            fixture.registry.readFromNBT(legacy());
            ItemStack stack = terminal();
            CountingTag tag = (CountingTag) stack.getTagCompound();
            tag.setString(QuantumTerminalAccess.NBT_OWNER_UUID, "saved-owner");
            fixture.item.onUpdate(stack, fixture.world, fixture.actor, 4, false);
            assertEquals(
                ItemNetworkQuantumTerminal.ANCHOR_SCHEMA,
                tag.getByte(ItemNetworkQuantumTerminal.NBT_ANCHOR_SCHEMA));
            assertEquals("saved-owner", tag.getString(QuantumTerminalAccess.NBT_OWNER_UUID));
            assertEquals(
                fixture.registry.canonicalAnchor(pos()).generation,
                tag.getString(ItemNetworkQuantumTerminal.NBT_ANCHOR_GENERATION));
            assertEquals(1, fixture.registry.resolutions);
            assertEquals(1, fixture.actor.inventory.dirty);
            QuantumTerminalAccess.synchronize(fixture.actor, stack);
            assertEquals("An already formatted access must not resolve aliases again", 1, fixture.registry.resolutions);
            QuantumTerminalAccess.synchronize(fixture.actor, stack);
            assertEquals(1, fixture.registry.resolutions);
            tag.writes = 0;
            int dirty = fixture.actor.inventory.dirty;
            for (int i = 0; i < 20; i++) fixture.item.onUpdate(stack, fixture.world, fixture.actor, 4, false);
            assertEquals(0, tag.writes);
            assertEquals(1, fixture.registry.resolutions);
            assertEquals(dirty, fixture.actor.inventory.dirty);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            CompressedStreamTools.writeCompressed(tag, bytes);
            stack.setTagCompound(CompressedStreamTools.readCompressed(new ByteArrayInputStream(bytes.toByteArray())));
            fixture.item.onUpdate(stack, fixture.world, fixture.actor, 4, false);
            assertEquals(1, fixture.registry.resolutions);
            assertEquals(
                ItemNetworkQuantumTerminal.ANCHOR_SCHEMA,
                stack.getTagCompound()
                    .getByte(ItemNetworkQuantumTerminal.NBT_ANCHOR_SCHEMA));
        }
    }

    @Test
    public void pendingUpgradeRetriesOnlyOnSourceEventsAndRecoversRetiredCoordinateHints() throws Exception {
        try (Fixture fixture = new Fixture(false)) {
            ItemStack stack = terminal();
            for (int i = 0; i < 10; i++) fixture.item.onUpdate(stack, fixture.world, fixture.actor, 0, true);
            assertEquals(0, fixture.registry.resolutions);
            assertEquals(0, fixture.actor.inventory.dirty);
            fixture.worlds.put(DIM, fixture.world);
            for (int i = 0; i < 10; i++) fixture.item.onUpdate(stack, fixture.world, fixture.actor, 0, true);
            assertEquals(1, fixture.registry.resolutions);
            assertEquals(
                0,
                stack.getTagCompound()
                    .getByte(ItemNetworkQuantumTerminal.NBT_ANCHOR_SCHEMA));
            fixture.registry.readFromNBT(legacy());
            fixture.item.onUpdate(stack, fixture.world, fixture.actor, 0, true);
            assertEquals(2, fixture.registry.resolutions);
            assertEquals(
                ItemNetworkQuantumTerminal.ANCHOR_SCHEMA,
                stack.getTagCompound()
                    .getByte(ItemNetworkQuantumTerminal.NBT_ANCHOR_SCHEMA));
            // A rebuilt, explicitly registered coordinate supersedes an obsolete saved generation hint.
            fixture.registry.dequantize(pos());
            fixture.registry.quantize(0, 64, 0);
            ItemStack stale = terminal();
            stale.getTagCompound()
                .setString(ItemNetworkQuantumTerminal.NBT_ANCHOR_GENERATION, "retired-generation");
            fixture.item.onUpdate(stale, fixture.world, fixture.actor, 0, true);
            int failed = fixture.registry.resolutions;
            for (int i = 0; i < 10; i++) fixture.item.onUpdate(stale, fixture.world, fixture.actor, 0, true);
            assertEquals(failed, fixture.registry.resolutions);
            QuantumMigrationJournal journal = QuantumMigrationJournal.get(fixture.world);
            journal.append(
                DIM,
                Collections.singleton(QuantumControllerRegistry.pack(99, 64, 0)),
                new AnchorKey(DIM, 100, 64, 0));
            fixture.item.onUpdate(stale, fixture.world, fixture.actor, 0, true);
            assertEquals(failed, fixture.registry.resolutions);
            assertEquals(
                ItemNetworkQuantumTerminal.ANCHOR_SCHEMA,
                stale.getTagCompound()
                    .getByte(ItemNetworkQuantumTerminal.NBT_ANCHOR_SCHEMA));
            assertEquals(
                fixture.registry.canonicalAnchor(pos()).generation,
                stale.getTagCompound()
                    .getString(ItemNetworkQuantumTerminal.NBT_ANCHOR_GENERATION));
        }
    }

    @Test
    public void currentJournalCursorDoesNotWriteButFutureMoveStillAppliesToMarkedItem() {
        QuantumMigrationJournal journal = new QuantumMigrationJournal("test");
        ItemStack stack = terminal();
        CountingTag tag = (CountingTag) stack.getTagCompound();
        tag.setByte(ItemNetworkQuantumTerminal.NBT_ANCHOR_SCHEMA, ItemNetworkQuantumTerminal.ANCHOR_SCHEMA);
        tag.writes = 0;
        assertFalse(QuantumTerminalAccess.applyPendingMigrations(stack, journal));
        assertEquals(0, tag.writes);
        journal.append(DIM, Collections.singleton(pos()), new AnchorKey(DIM + 1, 9, 70, 1));
        assertTrue(QuantumTerminalAccess.applyPendingMigrations(stack, journal));
        assertEquals(DIM + 1, tag.getInteger(ItemNetworkQuantumTerminal.NBT_ANCHOR_DIM));
        tag.writes = 0;
        assertFalse(QuantumTerminalAccess.applyPendingMigrations(stack, journal));
        assertEquals(0, tag.writes);
    }

    @Test
    public void repeatedAccessDoesNotCopyOrRewriteUnchangedTerminalNbt() throws Exception {
        try (Fixture fixture = new Fixture(false)) {
            ItemStack stack = terminal();
            assertTrue(QuantumTerminalAccess.synchronize(fixture.actor, stack));
            CountingTag tag = (CountingTag) stack.getTagCompound();
            tag.writes = 0;
            int dirty = fixture.actor.inventory.dirty;
            assertFalse(QuantumTerminalAccess.synchronize(fixture.actor, stack));
            assertEquals(0, tag.writes);
            assertEquals(dirty, fixture.actor.inventory.dirty);
        }
    }

    @Test
    public void nodeAndIncorporationPositiveCacheExpiresOnRegistryRevisionAndInstance() throws Exception {
        try (Fixture fixture = new Fixture(true)) {
            fixture.registry.readFromNBT(legacy());
            String generation = fixture.registry.canonicalAnchor(pos()).generation;
            Node node = new Node();
            try {
                node.setWorldObj(fixture.world);
                node.setAnchor(DIM, 0, 64, 0, generation);
                Method normalize = TileEntityNetworkQuantumNode.class.getDeclaredMethod("normalizeCanonicalAnchor");
                normalize.setAccessible(true);
                int initial = fixture.registry.resolutions;
                for (int i = 0; i < 20; i++) assertEquals(true, normalize.invoke(node));
                assertEquals(initial, fixture.registry.resolutions);
                QuantumIncorporationRegistry incorporation = new QuantumIncorporationRegistry("fixture");
                QuantumIncorporationRegistry.Entry entry = new QuantumIncorporationRegistry.Entry();
                entry.dim = DIM;
                entry.ax = 0;
                entry.ay = 64;
                entry.az = 0;
                entry.anchorGeneration = generation;
                Method incNormalize = QuantumIncorporationRegistry.class
                    .getDeclaredMethod("normalizeAnchor", QuantumIncorporationRegistry.Entry.class, WorldServer.class);
                incNormalize.setAccessible(true);
                assertEquals(true, incNormalize.invoke(incorporation, entry, fixture.world));
                int warm = fixture.registry.resolutions;
                for (int i = 0; i < 20; i++)
                    assertEquals(true, incNormalize.invoke(incorporation, entry, fixture.world));
                assertEquals(warm, fixture.registry.resolutions);
                fixture.registry.dequantize(pos());
                assertEquals(false, normalize.invoke(node));
                assertEquals(false, incNormalize.invoke(incorporation, entry, fixture.world));
                int failed = fixture.registry.resolutions;
                for (int i = 0; i < 10_000; i++) {
                    assertEquals(false, normalize.invoke(node));
                    assertEquals(false, incNormalize.invoke(incorporation, entry, fixture.world));
                }
                assertEquals(failed, fixture.registry.resolutions);
                CountingRegistry replacement = new CountingRegistry();
                replacement.quantize(0, 64, 0);
                fixture.world.perWorldStorage.setData(QuantumControllerRegistry.DATA_NAME, replacement);
                assertEquals(true, normalize.invoke(node));
                assertEquals(true, incNormalize.invoke(incorporation, entry, fixture.world));
                assertEquals(2, replacement.resolutions);
            } finally {
                QuantumNetworkColorSync.untrack(node);
            }
        }
    }

    @Test
    public void loadingAnIncorporationWithEmptyJournalDoesNotMigrateItsRemoteBinding() throws Exception {
        try (Fixture fixture = new Fixture(true)) {
            fixture.registry.readFromNBT(legacy());
            NBTTagCompound saved = new NBTTagCompound(), entry = new NBTTagCompound();
            entry.setLong("pos", QuantumControllerRegistry.pack(8, 64, 8));
            entry.setString("identity", "original-incarnation");
            entry.setString("owner", "original-owner");
            entry.setInteger("dim", DIM);
            entry.setInteger("y", 64);
            NBTTagList entries = new NBTTagList();
            entries.appendTag(entry);
            saved.setTag("entries", entries);
            QuantumIncorporationRegistry registry = new QuantumIncorporationRegistry("test");
            registry.readFromNBT(saved);
            registry.setDirty(false);
            registry.applyPendingMigrations(fixture.world, new QuantumMigrationJournal("test"));
            assertEquals(0, fixture.registry.resolutions);
            assertFalse(registry.isDirty());
            QuantumIncorporationRegistry.Entry unchanged = registry.snapshot()
                .iterator()
                .next();
            assertEquals("", unchanged.anchorGeneration);
            assertEquals("original-owner", unchanged.owner());
            assertEquals("original-incarnation", unchanged.identity());
        }
    }

    @Test
    public void markedTerminalsRefreshColorOnlyAfterRealEventsAndSessionChange() throws Exception {
        try (Fixture fixture = new Fixture(true)) {
            fixture.registry.readFromNBT(legacy());
            ItemStack stack = terminal();
            CountingTag tag = (CountingTag) stack.getTagCompound();
            tag.setString(QuantumTerminalAccess.NBT_OWNER_UUID, "persisted-owner");
            fixture.item.onUpdate(stack, fixture.world, fixture.actor, 0, true);
            QuantumNetworkColorRegistry colors = QuantumNetworkColorRegistry.get(fixture.world);
            int beforeColor = fixture.registry.resolutions;
            long epoch = QuantumTerminalSyncEpoch.current();
            colors.recolor(Collections.singleton(pos()), 5);
            assertNotEquals(epoch, QuantumTerminalSyncEpoch.current());
            fixture.item.onUpdate(stack, fixture.world, fixture.actor, 0, true);
            assertEquals(5, QuantumNetworkColor.get(stack));
            assertEquals(
                "A color notice does not repeat canonical conversion",
                beforeColor,
                fixture.registry.resolutions);
            assertEquals("persisted-owner", tag.getString(QuantumTerminalAccess.NBT_OWNER_UUID));
            assertEquals(QuantumTerminalSyncEpoch.current(), tag.getLong(ItemNetworkQuantumTerminal.NBT_SYNC_EPOCH));
            int resolved = fixture.registry.resolutions;
            tag.writes = 0;
            epoch = QuantumTerminalSyncEpoch.current();
            colors.recolor(Collections.singleton(pos()), 5);
            assertEquals(epoch, QuantumTerminalSyncEpoch.current());
            for (int i = 0; i < 20; i++) fixture.item.onUpdate(stack, fixture.world, fixture.actor, 0, true);
            assertEquals(resolved, fixture.registry.resolutions);
            assertEquals(0, tag.writes);
            colors.readFromNBT(new NBTTagCompound());
            fixture.item.onUpdate(stack, fixture.world, fixture.actor, 0, true);
            assertEquals(0, QuantumNetworkColor.get(stack));
            long persistedEpoch = tag.getLong(ItemNetworkQuantumTerminal.NBT_SYNC_EPOCH);
            QuantumTerminalSyncEpoch.beginSession();
            assertNotEquals(persistedEpoch, QuantumTerminalSyncEpoch.current());
            fixture.item.onUpdate(stack, fixture.world, fixture.actor, 0, true);
            assertEquals(QuantumTerminalSyncEpoch.current(), tag.getLong(ItemNetworkQuantumTerminal.NBT_SYNC_EPOCH));
            assertEquals(beforeColor + 1, fixture.registry.resolutions);
            assertEquals("persisted-owner", tag.getString(QuantumTerminalAccess.NBT_OWNER_UUID));
        }
    }

    @Test
    public void markedItemFollowsFutureExplicitMoveOnItsOwnInventoryTickWithoutUi() throws Exception {
        try (Fixture fixture = new Fixture(true)) {
            fixture.registry.readFromNBT(legacy());
            ItemStack stack = terminal();
            CountingTag tag = (CountingTag) stack.getTagCompound();
            tag.setString(QuantumTerminalAccess.NBT_OWNER_UUID, "original-owner");
            fixture.item.onUpdate(stack, fixture.world, fixture.actor, 0, false);
            String generation = tag.getString(ItemNetworkQuantumTerminal.NBT_ANCHOR_GENERATION);
            long target = QuantumControllerRegistry.pack(8, 64, 0);
            assertTrue(fixture.registry.quantizeTransferred(Collections.singleton(target), target, generation));
            QuantumMigrationJournal.get(fixture.world)
                .append(DIM, Collections.singleton(pos()), new AnchorKey(DIM, 8, 64, 0), generation, generation);
            fixture.registry.dequantize(pos());
            fixture.item.onUpdate(stack, fixture.world, fixture.actor, 0, false);
            assertEquals(8, tag.getInteger(ItemNetworkQuantumTerminal.NBT_ANCHOR_X));
            assertEquals(generation, tag.getString(ItemNetworkQuantumTerminal.NBT_ANCHOR_GENERATION));
            assertEquals("original-owner", tag.getString(QuantumTerminalAccess.NBT_OWNER_UUID));
            assertEquals(QuantumTerminalSyncEpoch.current(), tag.getLong(ItemNetworkQuantumTerminal.NBT_SYNC_EPOCH));
            int resolved = fixture.registry.resolutions;
            tag.writes = 0;
            for (int i = 0; i < 20; i++) fixture.item.onUpdate(stack, fixture.world, fixture.actor, 0, false);
            assertEquals(resolved, fixture.registry.resolutions);
            assertEquals(0, tag.writes);
        }
    }

    @Test
    public void colorSyncHasNoPlayerTickSubscriberToPollInventories() {
        for (Method method : QuantumNetworkColorSync.class.getDeclaredMethods())
            if (method.isAnnotationPresent(cpw.mods.fml.common.eventhandler.SubscribeEvent.class))
                for (Class<?> parameter : method.getParameterTypes()) assertFalse(
                    "Color updates must not register a global player-inventory poll",
                    cpw.mods.fml.common.gameevent.TickEvent.PlayerTickEvent.class.isAssignableFrom(parameter));
    }

    @Test
    public void lazyColorReadAcknowledgesItsFinalEpochWithoutAnotherCanonicalConversion() throws Exception {
        try (Fixture fixture = new Fixture(true)) {
            fixture.registry.readFromNBT(legacy());
            LazyColorStorage storage = new LazyColorStorage();
            storage.setData(QuantumControllerRegistry.DATA_NAME, fixture.registry);
            field(World.class, "perWorldStorage").set(fixture.world, storage);
            ItemStack stack = terminal();
            fixture.item.onUpdate(stack, fixture.world, fixture.actor, 0, false);
            assertEquals(7, QuantumNetworkColor.get(stack));
            assertEquals(
                QuantumTerminalSyncEpoch.current(),
                stack.getTagCompound()
                    .getLong(ItemNetworkQuantumTerminal.NBT_SYNC_EPOCH));
            assertEquals(1, fixture.registry.resolutions);
            assertEquals(1, storage.colorLoads);
            ((CountingTag) stack.getTagCompound()).writes = 0;
            fixture.item.onUpdate(stack, fixture.world, fixture.actor, 0, false);
            assertEquals(1, fixture.registry.resolutions);
            assertEquals(1, storage.colorLoads);
            assertEquals(0, ((CountingTag) stack.getTagCompound()).writes);
        }
    }

    @Test
    public void journalEntryArrivingDuringCanonicalConversionIsNotAcknowledgedBeforeReplay() throws Exception {
        try (Fixture fixture = new Fixture(true)) {
            fixture.registry.readFromNBT(legacy());
            QuantumMigrationJournal journal = QuantumMigrationJournal.get(fixture.world);
            fixture.registry.duringResolve = () -> journal.append(
                DIM,
                Collections.singleton(QuantumControllerRegistry.pack(99, 64, 0)),
                new AnchorKey(DIM, 100, 64, 0));
            ItemStack stack = terminal();
            fixture.item.onUpdate(stack, fixture.world, fixture.actor, 0, false);
            assertEquals(
                ItemNetworkQuantumTerminal.ANCHOR_SCHEMA,
                stack.getTagCompound()
                    .getByte(ItemNetworkQuantumTerminal.NBT_ANCHOR_SCHEMA));
            assertNotEquals(
                QuantumTerminalSyncEpoch.current(),
                stack.getTagCompound()
                    .getLong(ItemNetworkQuantumTerminal.NBT_SYNC_EPOCH));
            fixture.item.onUpdate(stack, fixture.world, fixture.actor, 0, false);
            assertEquals(
                1L,
                stack.getTagCompound()
                    .getLong(QuantumTerminalAccess.NBT_MIGRATION_CURSOR));
            assertEquals(
                QuantumTerminalSyncEpoch.current(),
                stack.getTagCompound()
                    .getLong(ItemNetworkQuantumTerminal.NBT_SYNC_EPOCH));
            assertEquals(
                "Unrelated new event replays without reconverting the canonical binding",
                1,
                fixture.registry.resolutions);
        }
    }

    @Test
    public void markedColorPullWaitsForUnavailableWorldWithoutAcknowledgingOrRevalidatingCanonical() throws Exception {
        try (Fixture fixture = new Fixture(true)) {
            fixture.registry.readFromNBT(legacy());
            ItemStack stack = terminal();
            CountingTag tag = (CountingTag) stack.getTagCompound();
            fixture.item.onUpdate(stack, fixture.world, fixture.actor, 0, false);
            long acknowledged = tag.getLong(ItemNetworkQuantumTerminal.NBT_SYNC_EPOCH);
            int resolved = fixture.registry.resolutions, dirty = fixture.actor.inventory.dirty;
            fixture.worlds.remove(DIM);
            QuantumNetworkColorRegistry.get(fixture.world)
                .recolor(Collections.singleton(pos()), 6);
            tag.writes = 0;
            for (int i = 0; i < 20; i++) fixture.item.onUpdate(stack, fixture.world, fixture.actor, 0, false);
            assertEquals(acknowledged, tag.getLong(ItemNetworkQuantumTerminal.NBT_SYNC_EPOCH));
            assertEquals(0, tag.writes);
            assertEquals(dirty, fixture.actor.inventory.dirty);
            assertEquals(resolved, fixture.registry.resolutions);
            fixture.worlds.put(DIM, fixture.world);
            QuantumTileLifecycle.worldLoaded(fixture.world);
            fixture.item.onUpdate(stack, fixture.world, fixture.actor, 0, false);
            assertEquals(6, QuantumNetworkColor.get(stack));
            assertEquals(QuantumTerminalSyncEpoch.current(), tag.getLong(ItemNetworkQuantumTerminal.NBT_SYNC_EPOCH));
            assertEquals(resolved, fixture.registry.resolutions);
        }
    }

    @Test
    public void savedCanceledGenerationNodesAndSchemaOneTerminalPersistOneCanonicalBindingWithoutChangingOwners()
        throws Exception {
        try (Fixture fixture = new Fixture(true); NodeMapping mapping = new NodeMapping()) {
            QuantumControllerRegistry old = new QuantumControllerRegistry("old");
            java.util.Set<Long> members = new java.util.HashSet<>();
            for (int x = 0; x < 3; x++) members.add(QuantumControllerRegistry.pack(x, 64, 0));
            old.quantizeAll(members, QuantumControllerRegistry.pack(1, 64, 0));
            String currentGeneration = old.canonicalAnchor(pos()).generation;
            NBTTagCompound saved = new NBTTagCompound();
            old.writeToNBT(saved);
            saved.setInteger("anchorSchema", 1);
            NBTTagList staleAliases = new NBTTagList();
            for (long member : members) {
                NBTTagCompound alias = new NBTTagCompound();
                alias.setLong("pos", member);
                alias.setString("generation", "old-canceled-generation");
                staleAliases.appendTag(alias);
            }
            saved.setTag("legacyAnchorAliases", staleAliases);
            fixture.registry.readFromNBT(saved);
            Method normalize = TileEntityNetworkQuantumNode.class.getDeclaredMethod("normalizeCanonicalAnchor");
            normalize.setAccessible(true);
            for (int oldX : new int[] { 0, 2 }) {
                Node node = new Node();
                try {
                    NBTTagCompound nodeSave = new NBTTagCompound();
                    nodeSave.setInteger("anchorDim", DIM);
                    nodeSave.setInteger("anchorX", oldX);
                    nodeSave.setInteger("anchorY", 64);
                    nodeSave.setString("anchorGeneration", "old-canceled-generation");
                    nodeSave.setString("placerUUID", "unchanged-node-owner");
                    nodeSave.setString("placerName", "unchanged name");
                    node.readFromNBT(nodeSave);
                    node.setWorldObj(fixture.world);
                    assertEquals(true, normalize.invoke(node));
                    NBTTagCompound normalizedSave = new NBTTagCompound();
                    node.writeToNBT(normalizedSave);
                    assertEquals(1, normalizedSave.getInteger("anchorX"));
                    assertEquals(currentGeneration, normalizedSave.getString("anchorGeneration"));
                    assertEquals("unchanged-node-owner", normalizedSave.getString("placerUUID"));
                    Node restored = new Node();
                    restored.readFromNBT(normalizedSave);
                    assertEquals(1, restored.getAnchorX());
                    assertEquals(currentGeneration, restored.getAnchorGeneration());
                    int reads = fixture.registry.resolutions;
                    for (int tick = 0; tick < 20; tick++) assertEquals(true, normalize.invoke(node));
                    assertEquals(reads, fixture.registry.resolutions);
                } finally {
                    QuantumNetworkColorSync.untrack(node);
                }
            }
            ItemStack terminal = terminal();
            NBTTagCompound tag = terminal.getTagCompound();
            tag.setByte(ItemNetworkQuantumTerminal.NBT_ANCHOR_SCHEMA, (byte) 1);
            tag.setString(ItemNetworkQuantumTerminal.NBT_ANCHOR_GENERATION, "old-canceled-generation");
            tag.setString(QuantumTerminalAccess.NBT_OWNER_UUID, "unchanged-terminal-owner");
            fixture.item.onUpdate(terminal, fixture.world, fixture.actor, 0, false);
            assertEquals(
                ItemNetworkQuantumTerminal.ANCHOR_SCHEMA,
                tag.getByte(ItemNetworkQuantumTerminal.NBT_ANCHOR_SCHEMA));
            assertEquals(1, tag.getInteger(ItemNetworkQuantumTerminal.NBT_ANCHOR_X));
            assertEquals(currentGeneration, tag.getString(ItemNetworkQuantumTerminal.NBT_ANCHOR_GENERATION));
            assertEquals("unchanged-terminal-owner", tag.getString(QuantumTerminalAccess.NBT_OWNER_UUID));
            int reads = fixture.registry.resolutions;
            ((CountingTag) tag).writes = 0;
            for (int tick = 0; tick < 20; tick++)
                fixture.item.onUpdate(terminal, fixture.world, fixture.actor, 0, false);
            assertEquals(0, ((CountingTag) tag).writes);
            assertEquals(reads, fixture.registry.resolutions);
        }
    }

    @Test
    public void retiredSaveUsesLoadedBindingMajorityOnceAndTerminalsAndNodesThenStayStable() throws Exception {
        try (Fixture fixture = new Fixture(true)) {
            Node[] nodes = { new Node(), new Node(), new Node(), new Node() };
            Method normalize = TileEntityNetworkQuantumNode.class.getDeclaredMethod("normalizeCanonicalAnchor");
            normalize.setAccessible(true);
            try {
                for (int i = 0; i < nodes.length; i++) {
                    nodes[i].setWorldObj(fixture.world);
                    nodes[i].setAnchor(DIM, i == 0 ? 0 : 1, 64, 0, "already-retired-generation");
                    assertEquals(false, normalize.invoke(nodes[i]));
                }
                java.util.Set<Long> members = new java.util.HashSet<>();
                members.add(pos());
                members.add(QuantumControllerRegistry.pack(1, 64, 0));
                assertTrue(fixture.registry.quantizeAll(members, pos()));
                assertEquals(1, QuantumControllerRegistry.unpackX(fixture.registry.canonicalAnchor(pos()).position));
                for (Node node : nodes) {
                    assertEquals(true, normalize.invoke(node));
                    assertEquals(1, node.getAnchorX());
                }
                ItemStack stack = terminal();
                NBTTagCompound tag = stack.getTagCompound();
                tag.setByte(ItemNetworkQuantumTerminal.NBT_ANCHOR_SCHEMA, (byte) 2);
                tag.setString(ItemNetworkQuantumTerminal.NBT_ANCHOR_GENERATION, "already-retired-generation");
                fixture.item.onUpdate(stack, fixture.world, fixture.actor, 0, false);
                assertEquals(1, tag.getInteger(ItemNetworkQuantumTerminal.NBT_ANCHOR_X));
                int resolved = fixture.registry.resolutions;
                ((CountingTag) tag).writes = 0;
                for (int tick = 0; tick < 10_000; tick++) {
                    fixture.item.onUpdate(stack, fixture.world, fixture.actor, 0, false);
                    for (Node node : nodes) assertEquals(true, normalize.invoke(node));
                }
                assertEquals(resolved, fixture.registry.resolutions);
                assertEquals(0, ((CountingTag) tag).writes);
            } finally {
                for (Node node : nodes) QuantumNetworkColorSync.untrack(node);
            }
        }
    }

    private static ItemStack terminal() {
        ItemStack stack = new ItemStack(new ItemNetworkQuantumTerminal());
        CountingTag tag = new CountingTag();
        tag.setByte(ItemNetworkQuantumTerminal.NBT_BOUND, (byte) 1);
        tag.setInteger(ItemNetworkQuantumTerminal.NBT_ANCHOR_DIM, DIM);
        tag.setInteger(ItemNetworkQuantumTerminal.NBT_ANCHOR_Y, 64);
        stack.setTagCompound(tag);
        tag.writes = 0;
        return stack;
    }

    private static long pos() {
        return QuantumControllerRegistry.pack(0, 64, 0);
    }

    private static NBTTagCompound legacy() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setIntArray("controllers", new int[] { DIM, 0, 64, 0 });
        return tag;
    }

    private static class CountingRegistry extends QuantumControllerRegistry {

        int resolutions;
        Runnable duringResolve;

        CountingRegistry() {
            super(DATA_NAME);
        }

        @Override
        public CanonicalAnchor resolveAnchor(long member, String generation) {
            resolutions++;
            if (duringResolve != null) {
                Runnable callback = duringResolve;
                duringResolve = null;
                callback.run();
            }
            return super.resolveAnchor(member, generation);
        }
    }

    private static class LazyColorStorage extends MapStorage {

        int colorLoads;

        LazyColorStorage() {
            super(null);
        }

        @Override
        public WorldSavedData loadData(Class<? extends WorldSavedData> type, String name) {
            if (type == QuantumNetworkColorRegistry.class && colorLoads == 0) {
                colorLoads++;
                NBTTagCompound tag = new NBTTagCompound(), color = new NBTTagCompound();
                color.setLong("pos", pos());
                color.setInteger("color", 7);
                NBTTagList colors = new NBTTagList();
                colors.appendTag(color);
                tag.setTag("colors", colors);
                QuantumNetworkColorRegistry registry = new QuantumNetworkColorRegistry(name);
                registry.readFromNBT(tag);
                setData(name, registry);
            }
            return super.loadData(type, name);
        }
    }

    private static class CountingTag extends NBTTagCompound {

        int writes;

        @Override
        public void setInteger(String key, int value) {
            writes++;
            super.setInteger(key, value);
        }

        @Override
        public void setString(String key, String value) {
            writes++;
            super.setString(key, value);
        }

        @Override
        public void setLong(String key, long value) {
            writes++;
            super.setLong(key, value);
        }

        @Override
        public void setByte(String key, byte value) {
            writes++;
            super.setByte(key, value);
        }

        @Override
        public NBTBase copy() {
            throw new AssertionError("Steady terminal access must not deep-copy NBT");
        }
    }

    private static class Node extends TileEntityNetworkQuantumNode {

        @Override
        public void markDirty() {}
    }

    /** Exact native tile IDs are needed by writeToNBT; leave both vanilla maps as they were. */
    private static final class NodeMapping implements AutoCloseable {

        private static final String ID = "gtswn.test.saved_anchor_upgrade";
        private final Map<String, Class<?>> names;
        private final Map<Class<?>, String> classes;
        private final String previous;

        @SuppressWarnings("unchecked")
        NodeMapping() throws Exception {
            names = (Map<String, Class<?>>) field(TileEntity.class, "nameToClassMap").get(null);
            classes = (Map<Class<?>, String>) field(TileEntity.class, "classToNameMap").get(null);
            previous = classes.get(Node.class);
            assertFalse(names.containsKey(ID));
            TileEntity.addMapping(Node.class, ID);
        }

        @Override
        public void close() {
            names.remove(ID);
            if (previous == null) classes.remove(Node.class);
            else classes.put(Node.class, previous);
        }
    }

    private static class Actor extends EntityPlayerMP {

        CountingInventory inventory;

        private Actor() {
            super(null, null, null, null);
        }

        @Override
        public UUID getUniqueID() {
            return new UUID(1, 2);
        }

        @Override
        public String getCommandSenderName() {
            return "upgrade-fixture";
        }
    }

    private static class CountingInventory extends InventoryPlayer {

        int dirty;

        CountingInventory(Actor actor) {
            super(actor);
        }

        @Override
        public void markDirty() {
            dirty++;
        }
    }

    private static class FixtureWorld extends WorldServer {

        private FixtureWorld() {
            super(null, (ISaveHandler) null, "fixture", DIM, (WorldSettings) null, (Profiler) null);
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
    }

    private static class Fixture implements AutoCloseable {

        final FixtureWorld world;
        final Actor actor;
        final CountingRegistry registry = new CountingRegistry();
        final ItemNetworkQuantumTerminal item = new ItemNetworkQuantumTerminal();
        final Map<Integer, WorldServer> worlds;
        final WorldServer previous;

        @SuppressWarnings("unchecked")
        Fixture(boolean publish) throws Exception {
            world = (FixtureWorld) allocate(FixtureWorld.class);
            WorldProviderSurface provider = new WorldProviderSurface();
            provider.dimensionId = DIM;
            field(World.class, "provider").set(world, provider);
            field(World.class, "perWorldStorage").set(world, new MapStorage(null));
            world.perWorldStorage.setData(QuantumControllerRegistry.DATA_NAME, registry);
            actor = (Actor) allocate(Actor.class);
            actor.worldObj = world;
            actor.inventory = new CountingInventory(actor);
            ((EntityPlayerMP) actor).inventory = actor.inventory;
            worlds = (Map<Integer, WorldServer>) field(DimensionManager.class, "worlds").get(null);
            previous = worlds.get(DIM);
            if (publish) worlds.put(DIM, world);
            else worlds.remove(DIM);
        }

        @Override
        public void close() {
            if (previous == null) worlds.remove(DIM);
            else worlds.put(DIM, previous);
        }
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Object allocate(Class<?> type) throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        return unsafe.getMethod("allocateInstance", Class.class)
            .invoke(field(unsafe, "theUnsafe").get(null), type);
    }
}
