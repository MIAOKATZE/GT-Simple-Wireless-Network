package com.miaokatze.gtswn.common.assembly;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.entity.Entity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.profiler.Profiler;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.WorldProviderSurface;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.storage.ISaveHandler;
import net.minecraftforge.common.util.ForgeDirection;

import org.junit.Test;

import com.miaokatze.gtswn.assembly.nativeintegration.NativeAssemblyAdapter;
import com.miaokatze.gtswn.assembly.nativeintegration.NativeAssemblySession;

import appeng.api.networking.crafting.ICraftingProviderHelper;
import appeng.me.helpers.AENetworkProxy;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.api.metatileentity.CommonMetaTileEntity;
import gregtech.api.metatileentity.MetaTileEntity;

public class MatrixTopologyTest {

    @Test
    public void horizontalBareColumnsConfigureWithZeroThroughFourFluidsWithoutGrantingNativeAccess() throws Exception {
        for (boolean alongZ : new boolean[] { false, true }) for (int fluidCount = 0; fluidCount <= 4; fluidCount++) {
            Fixture fixture = new Fixture();
            List<MatrixAssemblyMember> items = fixture.items(alongZ);
            for (int i = 0; i < fluidCount; i++) fixture.add(true, alongZ ? 1 : i, 64, alongZ ? i : 1);
            OrderedAssemblyGroup.refreshTopology(items.get(3));
            OrderedAssemblyGroup group = items.get(0)
                .matrixPort()
                .group();
            assertNotNull(group);
            assertNull(group.session());
            assertNull(group.diagnosticControllerPosition());
            assertTrue(group.editable());
            assertFalse(group.accepting());
            assertFalse(group.validateNativeState(new NativeAssemblySession(null)));
            OrderedAssemblyGroup.structureChecked(items.get(0), new NativeAssemblySession(null), false);
            assertSame(
                group,
                items.get(0)
                    .matrixPort()
                    .group());
            assertTrue(group.editable());
            assertEquals(
                fluidCount,
                group.fluidMembers()
                    .size());
            for (MatrixAssemblyMember member : fixture.members) {
                assertSame(
                    group,
                    member.matrixPort()
                        .group());
                assertFalse(NativeAssemblyAdapter.permitsNativeAccess(member));
                assertEquals(
                    fixture.expectedMask(member),
                    member.matrixPort()
                        .connectionMask());
            }
            group.provideCrafting(
                (ICraftingProviderHelper) Proxy.newProxyInstance(
                    ICraftingProviderHelper.class.getClassLoader(),
                    new Class<?>[] { ICraftingProviderHelper.class },
                    (proxy, method, args) -> {
                        throw new AssertionError("An unbound column cannot advertise crafting");
                    }));
            NBTTagCompound ledger = group.saveLedger();
            assertFalse(ledger.hasKey("controller"));
            assertFalse(ledger.hasKey("controllerPosition"));
            assertEquals(
                group.master()
                    .orderedMemberId()
                    .toString(),
                ledger.getString("master"));
        }
    }

    @Test
    public void verticalColumnsAndSideBranchesNeverFormAGroup() throws Exception {
        Fixture vertical = new Fixture();
        for (int i = 0; i < 5; i++) vertical.add(false, 0, 64 + i, 0);
        OrderedAssemblyGroup.refreshTopology(vertical.members.get(0));
        assertNull(
            vertical.members.get(0)
                .matrixPort()
                .group());

        Fixture branch = new Fixture();
        List<MatrixAssemblyMember> items = branch.items(false);
        branch.add(false, 2, 64, 1);
        OrderedAssemblyGroup.refreshTopology(items.get(0));
        assertNull(
            items.get(0)
                .matrixPort()
                .group());

        Fixture fluidBranch = new Fixture();
        items = fluidBranch.items(false);
        fluidBranch.add(true, 0, 64, 1);
        fluidBranch.add(true, 0, 64, 2);
        OrderedAssemblyGroup.refreshTopology(items.get(0));
        assertNull(
            items.get(0)
                .matrixPort()
                .group());
    }

    @Test
    public void fluidGapsUseOnlyRealFaceBridgesAndAnAddedItemKeepsTheExistingMaster() throws Exception {
        Fixture fixture = new Fixture();
        List<MatrixAssemblyMember> items = fixture.items(false);
        MatrixAssemblyMember fluid = fixture.add(true, 0, 64, 1);
        fixture.add(true, 3, 64, 1);
        OrderedAssemblyGroup.refreshTopology(items.get(0));
        OrderedAssemblyGroup first = items.get(0)
            .matrixPort()
            .group();
        assertNotNull(first);
        assertEquals(
            ForgeDirection.NORTH.flag,
            fluid.matrixPort()
                .connectionMask());
        MatrixAssemblyMember added = fixture.add(false, -1, 64, 0);
        OrderedAssemblyGroup.refreshTopology(added);
        OrderedAssemblyGroup expanded = added.matrixPort()
            .group();
        assertNotSame(first, expanded);
        assertSame(first.master(), expanded.master());
        assertEquals(
            ForgeDirection.EAST.flag,
            added.matrixPort()
                .connectionMask());
        for (MatrixAssemblyMember member : fixture.members) assertEquals(
            fixture.expectedMask(member),
            member.matrixPort()
                .connectionMask());
    }

    @Test
    public void anInflightMembershipChangeRetainsTheBatchAndPhysicalMaterials() throws Exception {
        Fixture fixture = new Fixture();
        List<MatrixAssemblyMember> items = fixture.items(false);
        OrderedAssemblyGroup.refreshTopology(items.get(0));
        OrderedAssemblyGroup group = items.get(0)
            .matrixPort()
            .group();
        field(OrderedAssemblyGroup.class, "stage").set(group, OrderedAssemblyGroup.Stage.STAGED);
        field(OrderedAssemblyGroup.class, "batch").set(group, UUID.randomUUID());
        field(OrderedAssemblyGroup.class, "recipeKey")
            .set(group, "v1:" + String.join("", Collections.nCopies(64, "0")));
        field(OrderedAssemblyGroup.class, "patternSlot").setInt(group, 0);
        items.get(0)
            .matrixSetItem(new ItemStack(new Item(), 7));
        NBTTagCompound before = group.saveLedger();
        MatrixAssemblyMember extra = fixture.add(false, -1, 64, 0);
        OrderedAssemblyGroup.refreshTopology(extra);
        assertNull(
            extra.matrixPort()
                .group());
        assertSame(
            group,
            items.get(0)
                .matrixPort()
                .group());
        assertEquals(OrderedAssemblyGroup.Stage.STAGED, group.stage());
        assertEquals(
            before.getString("batch"),
            group.saveLedger()
                .getString("batch"));
        assertEquals(
            7,
            items.get(0)
                .matrixItem().stackSize);
        assertFalse(group.editable());
        for (MatrixAssemblyMember member : items) assertEquals(
            0,
            member.matrixPort()
                .connectionMask());
    }

    @Test
    public void aLegacyReverseScanLedgerRetainsRecipeOrdinalsAndThePhysicalUiMasterBeforeNativeRebinding()
        throws Exception {
        Fixture fixture = new Fixture();
        List<MatrixAssemblyMember> items = fixture.items(false);
        OrderedAssemblyGroup.refreshTopology(items.get(0));
        OrderedAssemblyGroup group = items.get(0)
            .matrixPort()
            .group();
        MatrixAssemblyMember master = group.master();
        NBTTagCompound legacy = group.saveLedger();
        legacy.removeTag("master");
        legacy.setString(
            "controller",
            UUID.randomUUID()
                .toString());
        legacy.setIntArray("controllerPosition", new int[] { 0, 0, 66, 2 });
        NBTTagList original = legacy.getTagList("members", 10), reversed = new NBTTagList();
        for (int i = original.tagCount() - 1; i >= 0; i--) reversed.appendTag(
            original.getCompoundTagAt(i)
                .copy());
        legacy.setTag("members", reversed);
        for (MatrixAssemblyMember member : items) member.matrixPort()
            .bind(null);
        field(MatrixAssemblyPort.class, "restoredLedger").set(master.matrixPort(), legacy);
        OrderedAssemblyGroup.refreshTopology(items.get(2));
        OrderedAssemblyGroup restored = master.matrixPort()
            .group();
        assertNotSame(group, restored);
        assertSame(master, restored.master());
        assertSame(
            items.get(4),
            restored.itemMembers()
                .get(0));
        assertArrayEquals(new int[] { 4, 64, 0 }, restored.diagnosticMemberPosition(false, 0));
        assertArrayEquals(new int[] { 0, 0, 66, 2 }, restored.diagnosticControllerPosition());
        assertNull(restored.session());
        assertTrue(restored.editable());
        assertFalse(restored.accepting());
        assertFalse(restored.validateNativeState(new NativeAssemblySession(null)));
        assertEquals("structure_incomplete", restored.reason());
    }

    @Test
    public void aReloadedMasterKeepsTheSameBatchOnlyAtItsSavedIdentityAndPosition() throws Exception {
        Fixture fixture = new Fixture();
        List<MatrixAssemblyMember> items = fixture.items(false);
        OrderedAssemblyGroup.refreshTopology(items.get(0));
        OrderedAssemblyGroup group = items.get(0)
            .matrixPort()
            .group();
        field(OrderedAssemblyGroup.class, "stage").set(group, OrderedAssemblyGroup.Stage.STAGED);
        UUID batch = UUID.randomUUID();
        field(OrderedAssemblyGroup.class, "batch").set(group, batch);
        MatrixAssemblyMember replacement = fixture.add(false, 0, 64, 0);
        field(MatrixAssemblyPort.class, "memberId").set(
            replacement.matrixPort(),
            items.get(0)
                .orderedMemberId());
        OrderedAssemblyGroup.refreshTopology(items.get(1));
        assertSame(
            group,
            replacement.matrixPort()
                .group());
        assertSame(replacement, group.master());
        assertEquals(OrderedAssemblyGroup.Stage.STAGED, group.stage());
        assertEquals(
            batch.toString(),
            group.saveLedger()
                .getString("batch"));
        assertFalse(group.editable());
        assertFalse(group.accepting());
    }

    @Test
    public void missingWorldTilesAndForeignGroupOwnershipNeverRetainABridge() throws Exception {
        Fixture fixture = new Fixture();
        List<MatrixAssemblyMember> items = fixture.items(false);
        OrderedAssemblyGroup.refreshTopology(items.get(0));
        OrderedAssemblyGroup group = items.get(0)
            .matrixPort()
            .group();
        fixture.world.tiles.remove(key(4, 64, 0));
        java.lang.reflect.Method update = OrderedAssemblyGroup.class.getDeclaredMethod("updateConnections");
        update.setAccessible(true);
        update.invoke(group);
        assertEquals(
            ForgeDirection.WEST.flag,
            items.get(3)
                .matrixPort()
                .connectionMask());
        assertEquals(
            0,
            items.get(4)
                .matrixPort()
                .connectionMask());
        items.get(2)
            .matrixPort()
            .bind(null);
        update.invoke(group);
        assertEquals(
            ForgeDirection.WEST.flag,
            items.get(1)
                .matrixPort()
                .connectionMask());
        assertEquals(
            0,
            items.get(2)
                .matrixPort()
                .connectionMask());
        assertEquals(
            0,
            items.get(3)
                .matrixPort()
                .connectionMask());
    }

    @Test
    public void losingNativeFormationNotifiesThePatternCatalogueOnlyOnce() throws Exception {
        Fixture fixture = new Fixture();
        List<MatrixAssemblyMember> items = fixture.items(false);
        OrderedAssemblyGroup.refreshTopology(items.get(0));
        OrderedAssemblyGroup group = items.get(0)
            .matrixPort()
            .group();
        NativeAssemblySession session = new NativeAssemblySession(null);
        field(OrderedAssemblyGroup.class, "session").set(group, session);
        field(OrderedAssemblyGroup.class, "nativeBound").setBoolean(group, true);
        MatrixAssemblyPort port = group.master()
            .matrixPort();
        field(MatrixAssemblyPort.class, "patternChange").setBoolean(port, false);
        OrderedAssemblyGroup.structureChecked(items.get(0), session, false);
        assertFalse(field(OrderedAssemblyGroup.class, "nativeBound").getBoolean(group));
        assertTrue(field(MatrixAssemblyPort.class, "patternChange").getBoolean(port));
        field(MatrixAssemblyPort.class, "patternChange").setBoolean(port, false);
        OrderedAssemblyGroup.structureChecked(items.get(0), session, false);
        assertFalse(field(MatrixAssemblyPort.class, "patternChange").getBoolean(port));
        assertTrue(group.editable());
    }

    @Test
    public void nativeStoppedAfterGtClearsAMembersBaseStillRetainsTheBatchAndNotifiesLiveMembers() throws Exception {
        Fixture fixture = new Fixture();
        List<MatrixAssemblyMember> items = fixture.items(false);
        OrderedAssemblyGroup.refreshTopology(items.get(0));
        OrderedAssemblyGroup group = items.get(0)
            .matrixPort()
            .group();
        NativeAssemblySession session = new NativeAssemblySession(null);
        field(OrderedAssemblyGroup.class, "session").set(group, session);
        field(OrderedAssemblyGroup.class, "stage").set(group, OrderedAssemblyGroup.Stage.RUNNING);
        UUID batch = UUID.randomUUID();
        field(OrderedAssemblyGroup.class, "batch").set(group, batch);
        group.invalidate("member_unloaded");
        fixture.world.tiles.remove(key(4, 64, 0));
        field(MetaTileEntity.class, "mBaseMetaTileEntity").set(items.get(4), null);
        field(MatrixAssemblyPort.class, "patternChange").setBoolean(
            items.get(0)
                .matrixPort(),
            false);
        group.onNativeStopped(session, "structure_incomplete");
        assertEquals(OrderedAssemblyGroup.Stage.NATIVE_ABORTED, group.stage());
        assertEquals(
            batch.toString(),
            group.saveLedger()
                .getString("batch"));
        assertTrue(
            field(MatrixAssemblyPort.class, "patternChange").getBoolean(
                items.get(0)
                    .matrixPort()));
        assertFalse(group.accepting());
    }

    private static final class Fixture {

        final sun.misc.Unsafe unsafe;
        final FixtureWorld world;
        final List<MatrixAssemblyMember> members = new ArrayList<>();

        Fixture() throws Exception {
            Field singleton = field(sun.misc.Unsafe.class, "theUnsafe");
            unsafe = (sun.misc.Unsafe) singleton.get(null);
            world = (FixtureWorld) unsafe.allocateInstance(FixtureWorld.class);
            world.tiles = new HashMap<>();
            world.unloaded = new HashSet<>();
            field(World.class, "provider").set(world, new WorldProviderSurface());
        }

        List<MatrixAssemblyMember> items(boolean alongZ) throws Exception {
            List<MatrixAssemblyMember> items = new ArrayList<>();
            for (int i = 0; i < 5; i++) items.add(add(false, alongZ ? 0 : i, 64, alongZ ? i : 0));
            return items;
        }

        MatrixAssemblyMember add(boolean fluid, int x, int y, int z) throws Exception {
            Class<? extends MetaTileEntity> type = fluid ? MatrixFluidInputHatch.class : MatrixItemInputBus.class;
            MetaTileEntity meta = (MetaTileEntity) unsafe.allocateInstance(type);
            MatrixAssemblyMember member = (MatrixAssemblyMember) meta;
            MatrixAssemblyPort port = new MatrixAssemblyPort(member);
            field(type, "matrix").set(meta, port);
            field(CommonMetaTileEntity.class, "mInventory").set(meta, new ItemStack[fluid ? 0 : 1]);
            field(MatrixAssemblyPort.class, "proxy").set(port, new AENetworkProxy(null, "matrixProxy", null, true));
            attach(meta, x, y, z);
            members.add(member);
            return member;
        }

        void attach(MetaTileEntity meta, int x, int y, int z) {
            QuietTile tile = new QuietTile();
            tile.setWorldObj(world);
            tile.xCoord = x;
            tile.yCoord = y;
            tile.zCoord = z;
            meta.setBaseMetaTileEntity(tile);
            world.tiles.put(key(x, y, z), tile);
        }

        int expectedMask(MatrixAssemblyMember member) {
            BaseMetaTileEntity tile = (BaseMetaTileEntity) member.getBaseMetaTileEntity();
            int mask = 0;
            for (ForgeDirection face : ForgeDirection.VALID_DIRECTIONS) {
                TileEntity other = world.tiles
                    .get(key(tile.xCoord + face.offsetX, tile.yCoord + face.offsetY, tile.zCoord + face.offsetZ));
                if (other instanceof BaseMetaTileEntity gt && gt.getMetaTileEntity() instanceof MatrixAssemblyMember)
                    mask |= face.flag;
            }
            return mask;
        }
    }

    private static final class QuietTile extends BaseMetaTileEntity {

        @Override
        public void markDirty() {}
    }

    private static final class FixtureWorld extends WorldServer {

        Map<String, TileEntity> tiles;
        Set<String> unloaded;

        private FixtureWorld() {
            super(null, (ISaveHandler) null, "fixture", 0, (WorldSettings) null, (Profiler) null);
        }

        @Override
        public boolean blockExists(int x, int y, int z) {
            return !unloaded.contains(key(x, y, z));
        }

        @Override
        public TileEntity getTileEntity(int x, int y, int z) {
            assertTrue("Topology must not load a missing chunk", blockExists(x, y, z));
            return tiles.get(key(x, y, z));
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

    private static String key(int x, int y, int z) {
        return x + ":" + y + ":" + z;
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
