package com.miaokatze.gtswn.common.quantum;

import static com.miaokatze.gtswn.common.quantum.QuantumControllerPlacementTest.*;
import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.profiler.Profiler;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.StatCollector;
import net.minecraft.world.World;
import net.minecraft.world.WorldProviderSurface;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.storage.ISaveHandler;
import net.minecraft.world.storage.MapStorage;
import net.minecraftforge.common.DimensionManager;

import org.junit.Test;

import com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal;
import com.miaokatze.gtswn.config.Config;

import appeng.api.networking.IGridNode;

/** Coordinate recovery is separate from the native authorization applied when devices reconnect. */
public class QuantumControllerSuspensionTest {

    @Test
    public void disableIsOfflineAndResumeFromAnotherMemberKeepsOriginalRootAndTypedReferences() throws Exception {
        QuantumControllerPlacementTest.FixtureWorld world = world();
        controller(world, 0);
        controller(world, 1);
        QuantumControllerRegistry registry = QuantumControllerRegistry.get(world);
        registry.quantizeAll(members(0, 1), pos(1));
        String generation = registry.canonicalAnchor(pos(0)).generation;
        assertTrue(registry.suspendStructure(pos(0)));
        long suspendedRevision = registry.getRevision();
        assertTrue(registry.suspendStructure(pos(1)));
        assertEquals(suspendedRevision, registry.getRevision());
        assertSame(registry.getAll(), registry.getAll());
        assertTrue(
            registry.getAll()
                .isEmpty());
        assertFalse(registry.isQuantized(pos(1)));
        assertNull(registry.resolveAnchor(pos(0), generation));
        assertTrue(registry.isSuspendedAnchor(pos(0), generation));
        assertTrue(registry.quantizeAll(members(0, 1), pos(0)));
        assertEquals(pos(1), registry.resolveAnchor(pos(0), generation).position);
        assertEquals(generation, registry.canonicalAnchor(pos(1)).generation);
    }

    @Test
    public void legacyAliasesAndSuspensionSurviveRestartWithoutAdoptingNewCoordinateReferences() throws Exception {
        QuantumControllerPlacementTest.FixtureWorld world = world();
        controller(world, 0);
        controller(world, 1);
        QuantumControllerRegistry registry = QuantumControllerRegistry.get(world);
        registry.quantizeAll(members(0, 1), pos(0));
        NBTTagCompound legacy = save(registry);
        legacy.removeTag("anchorSchema");
        registry.readFromNBT(legacy);
        String generation = registry.resolveAnchor(pos(1), "").generation;
        assertTrue(registry.suspendStructure(pos(0)));
        registry.readFromNBT(save(registry));
        assertTrue(
            registry.getAll()
                .isEmpty());
        assertTrue(registry.isSuspendedAnchor(pos(0), ""));
        assertNull(registry.resolveAnchor(pos(1), ""));
        assertTrue(registry.quantizeAll(members(0, 1), pos(1)));
        assertEquals(generation, registry.resolveAnchor(pos(0), "").generation);
        assertEquals(generation, registry.resolveAnchor(pos(1), "").generation);
        assertNull(registry.resolveAnchor(pos(2), ""));
    }

    @Test
    public void partialLoadedResumeDefersAndLegitimateUnloadReloadRestoresSameGeneration() throws Exception {
        QuantumControllerPlacementTest.FixtureWorld world = world();
        controller(world, 0);
        QuantumControllerPlacementTest.Controller original = controller(world, 1);
        QuantumControllerRegistry registry = QuantumControllerRegistry.get(world);
        registry.quantizeAll(members(0, 1), pos(0));
        String generation = registry.canonicalAnchor(pos(0)).generation;
        String identity = original.identity;
        assertTrue(registry.suspendStructure(pos(0)));
        registry.unloadTile(original);
        world.unloaded = members(1);
        assertFalse(registry.quantizeAll(members(0), pos(0)));
        assertTrue(registry.isSuspendedAnchor(pos(1), generation));
        assertTrue(
            registry.getAll()
                .isEmpty());
        QuantumControllerPlacementTest.Controller reloaded = controller(world, 1);
        reloaded.identity = identity;
        world.unloaded.clear();
        assertTrue(registry.quantizeAll(members(0, 1), pos(1)));
        assertEquals(generation, registry.resolveAnchor(pos(1), generation).generation);
    }

    @Test
    public void memberAlreadyUnloadedBeforeDisableRetainsIdentityAndDefersResumeWithoutRetirement() throws Exception {
        QuantumControllerPlacementTest.FixtureWorld world = world();
        controller(world, 0);
        QuantumControllerPlacementTest.Controller original = controller(world, 1);
        QuantumControllerRegistry registry = QuantumControllerRegistry.get(world);
        registry.quantizeAll(members(0, 1), pos(1));
        String generation = registry.canonicalAnchor(pos(0)).generation, identity = original.identity;
        registry.unloadTile(original);
        world.unloaded = members(1);
        assertTrue(registry.suspendStructure(pos(0)));
        assertEquals(members(0, 1), registry.registeredStructure(pos(0)));
        assertTrue(registry.isSuspendedAnchor(pos(1), generation));
        NBTTagCompound suspended = save(registry);
        assertEquals(
            pos(1),
            suspended.getTagList("canonicalAnchors", 10)
                .getCompoundTagAt(0)
                .getLong("pos"));
        assertFalse(registry.quantizeAll(members(0), pos(0)));
        assertTrue(registry.isSuspendedAnchor(pos(1), generation));
        QuantumControllerPlacementTest.Controller reloaded = controller(world, 1);
        reloaded.identity = identity;
        world.unloaded.clear();
        assertTrue(registry.quantizeAll(members(0, 1), pos(0)));
        assertEquals(generation, registry.resolveAnchor(pos(0), generation).generation);
        assertEquals(pos(1), registry.canonicalAnchor(pos(0)).position);
    }

    @Test
    public void realRemovalWhileDisabledPreservesSurvivorsAndTheirBindings() throws Exception {
        QuantumControllerPlacementTest.FixtureWorld world = world();
        controller(world, 0);
        QuantumControllerPlacementTest.Controller removed = controller(world, 1);
        QuantumControllerRegistry registry = QuantumControllerRegistry.get(world);
        registry.quantizeAll(members(0, 1), pos(0));
        String generation = registry.canonicalAnchor(pos(0)).generation;
        registry.suspendStructure(pos(0));
        assertTrue(registry.ownsController(removed));
        world.tiles.remove(pos(1));
        QuantumTileLifecycle.removed(removed);
        assertEquals(members(0), registry.registeredStructure(pos(0)));
        assertTrue(registry.isSuspendedAnchor(pos(0), generation));
        controller(world, 1);
        registry.quantizeAll(members(0, 1), pos(1));
        assertEquals(generation, registry.canonicalAnchor(pos(0)).generation);
        assertEquals(
            registry.canonicalAnchor(pos(0)).generation,
            registry.resolveAnchor(pos(0), generation).generation);
    }

    @Test
    public void replacementBeforeResumeRecoversCoordinatesAndRejectsLateInvalidation() throws Exception {
        QuantumControllerPlacementTest.FixtureWorld world = world();
        QuantumControllerPlacementTest.Controller original = controller(world, 0);
        QuantumControllerRegistry registry = QuantumControllerRegistry.get(world);
        registry.quantize(0, 64, 0);
        String generation = registry.canonicalAnchor(pos(0)).generation;
        registry.suspendStructure(pos(0));
        QuantumControllerPlacementTest.Controller replacement = controller(world, 0);
        replacement.identity = original.identity;
        assertTrue(registry.quantizeAll(members(0), pos(0)));
        assertEquals(generation, registry.canonicalAnchor(pos(0)).generation);
        assertEquals(generation, registry.resolveAnchor(pos(0), generation).generation);
        QuantumTileLifecycle.removed(original);
        assertTrue(registry.isQuantized(pos(0)));
        assertTrue(registry.ownsController(replacement));
    }

    @Test
    public void inPlaceIdentityMutationAfterDisabledRestartRetainsCoordinateBindings() throws Exception {
        QuantumControllerPlacementTest.FixtureWorld world = world();
        QuantumControllerPlacementTest.Controller original = controller(world, 0);
        QuantumControllerRegistry registry = QuantumControllerRegistry.get(world);
        registry.quantize(0, 64, 0);
        String generation = registry.canonicalAnchor(pos(0)).generation;
        registry.suspendStructure(pos(0));
        registry.readFromNBT(save(registry));
        original.identity = "different-physical-controller";
        assertTrue(registry.quantizeAll(members(0), pos(0)));
        assertEquals(generation, registry.canonicalAnchor(pos(0)).generation);
        assertEquals(generation, registry.resolveAnchor(pos(0), generation).generation);
    }

    @Test
    public void persistedNativeOwnerChangeRetainsCoordinateHintWithoutChangingTheNewOwner() throws Exception {
        QuantumControllerPlacementTest.FixtureWorld world = world();
        QuantumControllerPlacementTest.Controller original = controller(world, 0);
        original.nativeNode = ownerNode(3);
        QuantumControllerRegistry registry = QuantumControllerRegistry.get(world);
        registry.quantize(0, 64, 0);
        String generation = registry.canonicalAnchor(pos(0)).generation;
        registry.suspendStructure(pos(0));
        registry.readFromNBT(save(registry));
        original.nativeNode = ownerNode(9);
        assertTrue(registry.quantizeAll(members(0), pos(0)));
        assertEquals(generation, registry.canonicalAnchor(pos(0)).generation);
        assertEquals(9, original.nativeNode.getPlayerID());
        assertEquals(generation, registry.resolveAnchor(pos(0), generation).generation);
    }

    @Test
    public void temporarilyUnavailableNativeProxyDoesNotVetoExplicitCoordinateRegistration() throws Exception {
        QuantumControllerPlacementTest.FixtureWorld world = world();
        QuantumControllerPlacementTest.Controller original = controller(world, 0);
        original.nativeNode = ownerNode(3);
        QuantumControllerRegistry registry = QuantumControllerRegistry.get(world);
        registry.quantize(0, 64, 0);
        String generation = registry.canonicalAnchor(pos(0)).generation;
        registry.suspendStructure(pos(0));
        original.nativeNode = null;
        assertTrue(registry.quantizeAll(members(0), pos(0)));
        assertFalse(registry.isSuspendedAnchor(pos(0), generation));
        original.nativeNode = ownerNode(3);
        assertTrue(registry.quantizeAll(members(0), pos(0)));
        assertEquals(generation, registry.canonicalAnchor(pos(0)).generation);
    }

    @Test
    public void explicitQuantizationResumesAndCoalescesBothPausedComponents() throws Exception {
        QuantumControllerPlacementTest.FixtureWorld world = world();
        controller(world, 0);
        controller(world, 2);
        QuantumControllerRegistry registry = QuantumControllerRegistry.get(world);
        registry.quantize(0, 64, 0);
        registry.quantize(2, 64, 0);
        String first = registry.canonicalAnchor(pos(0)).generation,
            second = registry.canonicalAnchor(pos(2)).generation;
        registry.suspendStructure(pos(0));
        registry.suspendStructure(pos(2));
        controller(world, 1);
        assertTrue(registry.quantizeAll(members(0, 1, 2), pos(1)));
        assertFalse(registry.isSuspendedAnchor(pos(0), first));
        assertFalse(registry.isSuspendedAnchor(pos(2), second));
        assertEquals(members(0, 1, 2), registry.getAll());
        assertSame(registry.canonicalAnchor(pos(0)), registry.canonicalAnchor(pos(2)));
    }

    @Test
    public void disabledGenerationCannotBeAbsorbedByAnAlreadyActiveNeighbor() throws Exception {
        QuantumControllerPlacementTest.FixtureWorld world = world();
        controller(world, 0);
        controller(world, 2);
        QuantumControllerRegistry registry = QuantumControllerRegistry.get(world);
        registry.quantize(0, 64, 0);
        registry.quantize(2, 64, 0);
        String generation = registry.canonicalAnchor(pos(0)).generation;
        registry.suspendStructure(pos(0));
        controller(world, 1);
        assertFalse(registry.quantizeAutomatically(members(0, 1, 2), pos(1)));
        assertTrue(registry.isSuspendedAnchor(pos(0), generation));
        assertEquals(Collections.singleton(pos(2)), registry.getAll());
    }

    @Test
    public void explicitQuantizationRefreshesAReplacedPausedMemberEvenWhenTheActiveDefaultWins() throws Exception {
        QuantumControllerPlacementTest.FixtureWorld world = world();
        controller(world, 0);
        controller(world, 2);
        QuantumControllerRegistry registry = QuantumControllerRegistry.get(world);
        registry.quantize(0, 64, 0);
        registry.quantize(2, 64, 0);
        QuantumControllerRegistry.CanonicalAnchor active = registry.canonicalAnchor(pos(0));
        assertTrue(registry.suspendStructure(pos(2)));
        QuantumControllerPlacementTest.Controller replacement = controller(world, 2);
        controller(world, 1);
        assertFalse(registry.quantizeAutomatically(members(0, 1, 2), pos(1)));
        assertTrue(registry.quantizeAll(members(0, 1, 2), pos(1)));
        assertSame(active, registry.canonicalAnchor(pos(2)));
        assertTrue(registry.isCurrentController(world, pos(2)));
        assertTrue(registry.ownsController(replacement));
        assertEquals(members(0, 1, 2), registry.getAll());
    }

    @Test
    public void resumingOneSplitFragmentKeepsTheOtherFragmentSuspendedAndBound() throws Exception {
        QuantumControllerPlacementTest.FixtureWorld world = world();
        controller(world, 0);
        controller(world, 1);
        controller(world, 2);
        QuantumControllerRegistry registry = QuantumControllerRegistry.get(world);
        registry.quantizeAll(members(0, 1, 2), pos(1));
        String generation = registry.canonicalAnchor(pos(0)).generation;
        registry.suspendStructure(pos(0));
        world.tiles.remove(pos(1));
        world.loadedAir = members(1);
        assertTrue(registry.quantizeAll(members(0), pos(0)));
        assertNotEquals(generation, registry.canonicalAnchor(pos(0)).generation);
        assertFalse(registry.isQuantized(pos(2)));
        assertTrue(registry.isSuspendedAnchor(pos(2), generation));
        assertNull(registry.resolveAnchor(pos(2), generation));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void ordinaryIncorporationMaintenanceRetainsDisabledLegacyAndTypedEntriesUnchanged() throws Exception {
        QuantumControllerPlacementTest.FixtureWorld local = world();
        controller(local, 0);
        controller(local, 10);
        QuantumControllerRegistry registry = QuantumControllerRegistry.get(local);
        registry.quantize(0, 64, 0);
        NBTTagCompound old = save(registry);
        old.removeTag("anchorSchema");
        registry.readFromNBT(old);
        String generation = registry.resolveAnchor(pos(0), "").generation;
        registry.suspendStructure(pos(0));
        int dimension = 19874;
        AnchorWorld anchor = (AnchorWorld) allocate(AnchorWorld.class);
        WorldProviderSurface provider = new WorldProviderSurface();
        provider.dimensionId = dimension;
        field(World.class, "provider").set(anchor, provider);
        field(World.class, "perWorldStorage").set(anchor, new MapStorage(null));
        anchor.perWorldStorage.setData(QuantumControllerRegistry.DATA_NAME, registry);
        Map<Integer, WorldServer> worlds = (Map<Integer, WorldServer>) field(DimensionManager.class, "worlds")
            .get(null);
        WorldServer previous = worlds.put(dimension, anchor);
        try {
            // A stale typed hint must have the same paused/offline semantics as its upgraded coordinate alias.
            for (String reference : new String[] { "", generation, "erased-by-old-cancel" }) {
                NBTTagCompound data = new NBTTagCompound(), value = new NBTTagCompound();
                value.setLong("pos", pos(10));
                value.setInteger("dim", dimension);
                value.setInteger("x", 0);
                value.setInteger("y", 64);
                value.setString("anchorGeneration", reference);
                value.setString("identity", "incarnation");
                value.setString("owner", "3afceebe-e136-3791-8868-21846f76a349");
                value.setString("ownerName", "original owner");
                value.setInteger("nativeOwnerID", 37);
                NBTTagList entries = new NBTTagList();
                entries.appendTag(value);
                data.setTag("entries", entries);
                QuantumIncorporationRegistry inc = new QuantumIncorporationRegistry("fixture");
                inc.readFromNBT(data);
                NBTTagCompound before = new NBTTagCompound();
                inc.writeToNBT(before);
                inc.controllerSuspended(dimension, members(0));
                Method maintain = QuantumIncorporationRegistry.class
                    .getDeclaredMethod("maintain", World.class, QuantumIncorporationRegistry.Entry.class);
                maintain.setAccessible(true);
                maintain.invoke(
                    inc,
                    local,
                    inc.snapshot()
                        .iterator()
                        .next());
                NBTTagCompound after = new NBTTagCompound();
                inc.writeToNBT(after);
                assertEquals(before, after);
                assertFalse(
                    inc.snapshot()
                        .iterator()
                        .next()
                        .isConnected());
            }
        } finally {
            if (previous == null) worlds.remove(dimension);
            else worlds.put(dimension, previous);
        }
    }

    @Test
    public void repeatedDisableResumePreservesNativeOwnerSafetyAndRootWhilePublishingStateEvents() throws Exception {
        QuantumControllerPlacementTest.FixtureWorld world = world();
        QuantumControllerPlacementTest.Controller original = controller(world, 0);
        original.nativeNode = ownerNode(7);
        original.safety = true;
        QuantumControllerRegistry registry = QuantumControllerRegistry.get(world);
        registry.quantize(0, 64, 0);
        String generation = registry.canonicalAnchor(pos(0)).generation, identity = original.identity;
        for (int i = 0; i < 5; i++) {
            long epoch = QuantumTerminalSyncEpoch.current(), revision = registry.getRevision();
            assertTrue(registry.suspendStructure(pos(0)));
            assertNotEquals(epoch, QuantumTerminalSyncEpoch.current());
            assertTrue(registry.getRevision() > revision);
            epoch = QuantumTerminalSyncEpoch.current();
            assertTrue(registry.quantizeAll(members(0), pos(0)));
            assertNotEquals(epoch, QuantumTerminalSyncEpoch.current());
            assertEquals(generation, registry.canonicalAnchor(pos(0)).generation);
            assertEquals(identity, original.identity);
            assertEquals(7, original.nativeNode.getPlayerID());
            assertTrue(original.safety);
        }
    }

    @Test
    public void rejectedEventAndActualShiftGesturePreservePhysicalRegistrationIdentityAndHeldBinding()
        throws Exception {
        initializeHeadlessPlatform();
        QuantumControllerPlacementTest.FixtureWorld world = world();
        world.provider.dimensionId = 19876;
        QuantumControllerPlacementTest.Controller original = controller(world, 0);
        QuantumControllerRegistry registry = QuantumControllerRegistry.get(world);
        registry.quantize(0, 64, 0);
        String generation = registry.canonicalAnchor(pos(0)).generation;
        // The current tile changed its identity; a failed disable is not a real removal callback.
        original.identity = "changed-controller-identity";
        long revision = registry.getRevision();
        assertFalse(QuantumControllerEventHandler.suspendStructure(world, pos(0)));
        assertEquals(revision, registry.getRevision());
        assertTrue(registry.isQuantized(pos(0)));
        assertEquals("changed-controller-identity", original.identity);
        PauseActor actor = (PauseActor) allocate(PauseActor.class);
        actor.worldObj = world;
        actor.inventory = new InventoryPlayer(actor);
        actor.messages = new ArrayList<>();
        ItemNetworkQuantumTerminal item = new ItemNetworkQuantumTerminal();
        actor.held = new ItemStack(item);
        NBTTagCompound tag = new NBTTagCompound();
        tag.setBoolean(ItemNetworkQuantumTerminal.NBT_BOUND, true);
        tag.setByte(ItemNetworkQuantumTerminal.NBT_ANCHOR_SCHEMA, ItemNetworkQuantumTerminal.ANCHOR_SCHEMA);
        tag.setInteger(ItemNetworkQuantumTerminal.NBT_ANCHOR_DIM, 19876);
        tag.setInteger(ItemNetworkQuantumTerminal.NBT_ANCHOR_X, 0);
        tag.setInteger(ItemNetworkQuantumTerminal.NBT_ANCHOR_Y, 64);
        tag.setString(ItemNetworkQuantumTerminal.NBT_ANCHOR_GENERATION, generation);
        tag.setString(
            "QT_OwnerUUID",
            actor.getUniqueID()
                .toString());
        tag.setString("QT_OwnerName", actor.getCommandSenderName());
        tag.setInteger("QT_Color", 0);
        actor.held.setTagCompound(tag);
        NBTTagCompound before = (NBTTagCompound) tag.copy();
        Method click = ItemNetworkQuantumTerminal.class.getDeclaredMethod(
            "handleControllerClick",
            ItemStack.class,
            EntityPlayer.class,
            World.class,
            appeng.tile.networking.TileController.class,
            int.class,
            int.class,
            int.class);
        click.setAccessible(true);
        boolean safety = Config.quantumSafetyEnabled;
        try {
            Config.quantumSafetyEnabled = false;
            assertEquals(true, click.invoke(item, actor.held, actor, world, original, 0, 64, 0));
        } finally {
            Config.quantumSafetyEnabled = safety;
        }
        assertEquals(before, actor.held.getTagCompound());
        assertTrue(registry.isQuantized(pos(0)));
        assertEquals(generation, registry.canonicalAnchor(pos(0)).generation);
        assertEquals("changed-controller-identity", original.identity);
        assertEquals(1, actor.messages.size());
        assertEquals(
            StatCollector.translateToLocal("gtswn.chat.quantum.pause_failed"),
            actor.messages.get(0)
                .getUnformattedText());
    }

    private static IGridNode ownerNode(int owner) {
        return (IGridNode) Proxy.newProxyInstance(
            IGridNode.class.getClassLoader(),
            new Class<?>[] { IGridNode.class },
            (proxy, method, args) -> method.getName()
                .equals("getPlayerID") ? owner : null);
    }

    private static Set<Long> members(int... positions) {
        Set<Long> result = new HashSet<>();
        Arrays.stream(positions)
            .forEach(x -> result.add(pos(x)));
        return result;
    }

    private static NBTTagCompound save(QuantumControllerRegistry registry) {
        NBTTagCompound saved = new NBTTagCompound();
        registry.writeToNBT(saved);
        return saved;
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

    private static void initializeHeadlessPlatform() throws Exception {
        // Platform only queries optional-mod presence while initializing. Supply the same minimal
        // headless Loader precondition as FluidRegistryTestBootstrap, without any native mod lifecycle.
        Class<?> type = cpw.mods.fml.common.Loader.class;
        Field instance = field(type, "instance");
        Object previous = instance.get(null);
        Object headless = allocate(type);
        field(type, "namedMods").set(headless, Collections.emptyMap());
        try {
            instance.set(null, headless);
            Class.forName("appeng.util.Platform", true, QuantumControllerSuspensionTest.class.getClassLoader());
        } finally {
            instance.set(null, previous);
        }
    }

    private static class AnchorWorld extends WorldServer {

        private AnchorWorld() {
            super(null, (ISaveHandler) null, "fixture", 19874, (WorldSettings) null, (Profiler) null);
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
    }

    private static class PauseActor extends EntityPlayerMP {

        ItemStack held;
        List<IChatComponent> messages;

        private PauseActor() {
            super(null, null, null, null);
        }

        @Override
        public UUID getUniqueID() {
            return new UUID(71, 74);
        }

        @Override
        public String getCommandSenderName() {
            return "pause-fixture";
        }

        @Override
        public boolean isSneaking() {
            return true;
        }

        @Override
        public ItemStack getCurrentEquippedItem() {
            return held;
        }

        @Override
        public void addChatMessage(IChatComponent message) {
            messages.add(message);
        }
    }
}
