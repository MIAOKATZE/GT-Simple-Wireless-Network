package com.miaokatze.gtswn.common.assembly;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
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
import com.miaokatze.gtswn.common.assembly.pattern.OrderedPatternLayout;
import com.miaokatze.gtswn.test.FluidRegistryTestBootstrap;

import appeng.api.networking.IGridConnection;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.networking.crafting.ICraftingProviderHelper;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEStack;
import appeng.me.helpers.AENetworkProxy;
import appeng.util.ReadOnlyCollection;
import gregtech.api.interfaces.ITexture;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.api.metatileentity.CommonMetaTileEntity;
import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEHatchInput;
import gregtech.api.metatileentity.implementations.MTEHatchInputBus;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;

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
            group.master()
                .matrixPort()
                .patterns()
                .setStackInSlot(
                    0,
                    layout(1, new int[0]).details()
                        .getPattern());
            group.provideCrafting(
                (ICraftingProviderHelper) Proxy.newProxyInstance(
                    ICraftingProviderHelper.class.getClassLoader(),
                    new Class<?>[] { ICraftingProviderHelper.class },
                    (proxy, method, args) -> {
                        throw new AssertionError("An unformed group must not advertise its stored pattern");
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
    public void topologyInvalidationAndRecoveryNotifyThePatternCatalogueOnlyOnTransitions() throws Exception {
        Fixture fixture = new Fixture();
        List<MatrixAssemblyMember> items = fixture.items(false);
        OrderedAssemblyGroup.refreshTopology(items.get(0));
        OrderedAssemblyGroup group = items.get(0)
            .matrixPort()
            .group();
        MatrixAssemblyPort port = group.master()
            .matrixPort();
        Field patternChange = field(MatrixAssemblyPort.class, "patternChange");
        patternChange.setBoolean(port, false);
        group.invalidate("topology_changed");
        assertTrue(patternChange.getBoolean(port));
        patternChange.setBoolean(port, false);
        group.invalidate("topology_changed");
        assertFalse(patternChange.getBoolean(port));
        OrderedAssemblyGroup.refreshTopology(items.get(1));
        assertSame(
            group,
            items.get(1)
                .matrixPort()
                .group());
        assertTrue(group.editable());
        assertTrue(patternChange.getBoolean(port));
        patternChange.setBoolean(port, false);
        OrderedAssemblyGroup.refreshTopology(items.get(2));
        assertFalse(patternChange.getBoolean(port));
    }

    @Test
    public void capacityCheckUsesPhysicalPositionsAndTanksBeforeAnyNativeBinding() throws Exception {
        Fixture fixture = new Fixture();
        List<MatrixAssemblyMember> items = fixture.items(false);
        MatrixFluidInputHatch fluid = (MatrixFluidInputHatch) fixture.add(true, 0, 64, 1);
        OrderedAssemblyGroup.refreshTopology(items.get(0));
        OrderedAssemblyGroup group = items.get(0)
            .matrixPort()
            .group();
        assertNull(group.session());
        assertFalse(group.accepting());
        Method capacity = OrderedAssemblyGroup.class.getDeclaredMethod("capacityReason", OrderedPatternLayout.class);
        capacity.setAccessible(true);
        assertEquals("", capacity.invoke(group, layout(5, new int[0])));
        assertEquals("not_enough_members", capacity.invoke(group, layout(6, new int[0])));
        assertEquals("", capacity.invoke(group, layout(1, new int[] { fluid.getCapacity() })));
        assertEquals(Integer.MAX_VALUE, fluid.getCapacity());
        assertEquals("not_enough_members", capacity.invoke(group, layout(1, new int[] { 1, 1 })));
        for (MatrixAssemblyMember member : fixture.members) {
            assertNull(member.matrixItem());
            assertNull(member.matrixFluidStack());
            assertFalse(NativeAssemblyAdapter.permitsNativeAccess(member));
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
    public void aNativeRecheckPreservesTheCurrentGroupsRealBranchRefusal() throws Exception {
        Fixture fixture = new Fixture();
        List<MatrixAssemblyMember> items = fixture.items(false);
        OrderedAssemblyGroup.refreshTopology(items.get(0));
        OrderedAssemblyGroup group = items.get(0)
            .matrixPort()
            .group();
        NativeAssemblySession session = new NativeAssemblySession(null);
        field(OrderedAssemblyGroup.class, "session").set(group, session);
        field(OrderedAssemblyGroup.class, "nativeBound").setBoolean(group, true);
        MatrixAssemblyMember branch = fixture.add(false, 2, 65, 0);
        OrderedAssemblyGroup.structureChecked(items.get(0), session, true);
        assertSame(
            group,
            items.get(0)
                .matrixPort()
                .group());
        assertEquals("item_column", group.reason());
        assertFalse(group.editable());
        assertFalse(group.accepting());
        assertFalse(field(OrderedAssemblyGroup.class, "nativeBound").getBoolean(group));
        assertNull(
            branch.matrixPort()
                .group());
        for (MatrixAssemblyMember member : items) assertEquals(
            0,
            member.matrixPort()
                .connectionMask());
        fixture.world.tiles.remove(key(2, 65, 0));
        OrderedAssemblyGroup.refreshTopology(items.get(0));
        assertSame(
            group,
            items.get(0)
                .matrixPort()
                .group());
        assertTrue(group.editable());
        assertFalse(group.accepting());
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
        assertEquals("no_external_connection", restored.reason());
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

    @Test
    public void repeatedPositionsAccumulateWholePackagesAndOverflowRetainsEveryOtherPosition() throws Exception {
        Fixture fixture = new Fixture();
        List<MatrixAssemblyMember> items = fixture.items(false);
        fixture.add(true, 0, 64, 1);
        OrderedAssemblyGroup.refreshTopology(items.get(0));
        OrderedAssemblyGroup group = items.get(0)
            .matrixPort()
            .group();
        Graph graph = new Graph(fixture);
        graph.external(items.get(4));
        fixture.controller(group, 20);
        OrderedPatternLayout layout = bufferLayout(new int[] { 0, 1, 1, 2 });
        group.master()
            .matrixPort()
            .patterns()
            .setStackInSlot(0, layout.pattern());
        assertTrue(deliver(group, layout, 10));
        assertTrue(deliver(group, layout, 6));
        assertSame(
            items.get(4),
            group.itemMembers()
                .get(0));
        for (int i = 0; i < 4; i++) {
            ItemStack stored = group.itemMembers()
                .get(i)
                .matrixItem();
            assertEquals(16, stored.stackSize);
            assertEquals(new int[] { 0, 1, 1, 2 }[i], stored.getItemDamage());
        }
        assertNull(
            group.itemMembers()
                .get(4)
                .matrixItem());
        assertEquals(
            1600,
            group.fluidMembers()
                .get(0)
                .matrixFluidStack().amount);
        MatrixItemInputBus ordinary = (MatrixItemInputBus) group.itemMembers()
            .get(0);
        assertEquals(1, ordinary.getSizeInventory());
        assertEquals(16, ordinary.getStackInSlot(0).stackSize);
        ItemStack full = group.itemMembers()
            .get(3)
            .matrixItem();
        full.stackSize = Integer.MAX_VALUE;
        group.itemMembers()
            .get(3)
            .matrixSetItem(full);
        assertFalse(deliver(group, layout, 1));
        assertEquals("amount_overflow", group.reason());
        assertEquals(
            16,
            group.itemMembers()
                .get(0)
                .matrixItem().stackSize);
        assertEquals(
            Integer.MAX_VALUE,
            group.itemMembers()
                .get(3)
                .matrixItem().stackSize);
        assertEquals(
            1600,
            group.fluidMembers()
                .get(0)
                .matrixFluidStack().amount);
        assertFalse(deliver(group, bufferLayout(new int[] { 1, 1, 1, 2 }), 1));
        assertEquals("different_pattern", group.reason());
    }

    @Test
    public void invisibleExternalEdgesCountButInternalEdgesDoNotAndAnInflightOriginCannotMove() throws Exception {
        Fixture fixture = new Fixture();
        List<MatrixAssemblyMember> items = fixture.items(false);
        OrderedAssemblyGroup.refreshTopology(items.get(0));
        OrderedAssemblyGroup group = items.get(0)
            .matrixPort()
            .group();
        Graph graph = new Graph(fixture);
        assertEquals(0, group.externalConnectionCount());
        IGridConnection original = graph.external(items.get(4));
        fixture.controller(group, 20);
        assertEquals(1, group.externalConnectionCount());
        int reads = graph.reads();
        assertEquals(1, group.externalConnectionCount());
        assertTrue(group.permitsMachineWork());
        assertEquals(reads, graph.reads());
        field(OrderedAssemblyGroup.class, "stage").set(group, OrderedAssemblyGroup.Stage.STAGED);
        field(OrderedAssemblyGroup.class, "genericInput").setBoolean(group, true);
        items.get(0)
            .matrixSetItem(new ItemStack(new Item(), 10));
        IGridConnection extra = graph.external(items.get(0));
        assertEquals(2, group.externalConnectionCount());
        assertEquals("multiple_external_connections", group.reason());
        assertFalse(group.permitsMachineWork());
        assertNull(((MatrixItemInputBus) items.get(0)).getStackInSlot(0));
        graph.remove(original);
        assertEquals(1, group.externalConnectionCount());
        assertEquals("entry_changed", group.reason());
        assertEquals(
            10,
            items.get(0)
                .matrixItem().stackSize);
        graph.remove(extra);
        graph.external(items.get(4));
        assertTrue(group.permitsMachineWork());
        assertEquals(10, ((MatrixItemInputBus) items.get(0)).getStackInSlot(0).stackSize);
    }

    @Test
    public void genericWorkKeepsItsPatternLockUntilNativeCompletionAndSharedControllersStopTheGroup() throws Exception {
        Fixture fixture = new Fixture();
        List<MatrixAssemblyMember> items = fixture.items(false);
        OrderedAssemblyGroup.refreshTopology(items.get(0));
        OrderedAssemblyGroup group = items.get(0)
            .matrixPort()
            .group();
        Graph graph = new Graph(fixture);
        graph.external(items.get(0));
        MTEMultiBlockBase first = fixture.controller(group, 20);
        field(OrderedAssemblyGroup.class, "stage").set(group, OrderedAssemblyGroup.Stage.STAGED);
        field(OrderedAssemblyGroup.class, "genericInput").setBoolean(group, true);
        first.mMaxProgresstime = 40;
        group.tick();
        assertEquals(OrderedAssemblyGroup.Stage.STAGED, group.stage());
        assertFalse(group.editable());
        MTEMultiBlockBase second = fixture.controller(group, 24);
        assertFalse(group.permitsMachineWork());
        assertEquals("controller_conflict", group.reason());
        OrderedAssemblyGroup.controllerChecked(items.get(0), second, false);
        assertTrue(group.permitsMachineWork());
        first.mMaxProgresstime = 0;
        group.tick();
        assertEquals(OrderedAssemblyGroup.Stage.IDLE, group.stage());
        assertTrue(group.editable());
    }

    private static OrderedPatternLayout bufferLayout(int[] variants) throws Exception {
        Class<?> fixtures = Class.forName("com.miaokatze.gtswn.common.assembly.pattern.AssemblyPatternFixtures");
        invoke(fixtures, "initialize", new Class<?>[0]);
        IAEStack<?>[] inputs = new IAEStack<?>[variants.length + 1];
        for (int i = 0; i < variants.length; i++) {
            ItemStack item = (ItemStack) invoke(fixtures, "input", new Class<?>[] { int.class }, 1);
            item.setItemDamage(variants[i]);
            inputs[i] = (IAEStack<?>) invoke(fixtures, "item", new Class<?>[] { ItemStack.class }, item);
        }
        inputs[variants.length] = (IAEStack<?>) invoke(
            fixtures,
            "fluid",
            new Class<?>[] { net.minecraftforge.fluids.FluidStack.class },
            FluidRegistryTestBootstrap.water(100));
        IAEItemStack output = (IAEItemStack) invoke(
            fixtures,
            "item",
            new Class<?>[] { ItemStack.class },
            invoke(fixtures, "output", new Class<?>[] { int.class }, 1));
        return OrderedPatternLayout.decode(
            (ICraftingPatternDetails) invoke(
                fixtures,
                "ultimate",
                new Class<?>[] { IAEStack[].class, IAEItemStack.class },
                inputs,
                output));
    }

    private static boolean deliver(OrderedAssemblyGroup group, OrderedPatternLayout layout, int copies)
        throws Exception {
        IAEStack<?>[] inputs = layout.details()
            .getAEInputs()
            .clone();
        for (int i = 0; i < inputs.length; i++) inputs[i] = inputs[i].copy()
            .setStackSize(inputs[i].getStackSize() * copies);
        Class<?> fixtures = Class.forName("com.miaokatze.gtswn.common.assembly.pattern.AssemblyPatternFixtures");
        net.minecraft.inventory.InventoryCrafting table = (net.minecraft.inventory.InventoryCrafting) invoke(
            fixtures,
            "table",
            new Class<?>[] { IAEStack[].class },
            (Object) inputs);
        Method accept = OrderedAssemblyGroup.class.getDeclaredMethod(
            "acceptInstalled",
            OrderedPatternLayout.class,
            net.minecraft.inventory.InventoryCrafting.class,
            int.class);
        accept.setAccessible(true);
        return (Boolean) accept.invoke(group, layout, table, 0);
    }

    private static final class Graph {

        final Fixture fixture;
        final Map<MatrixAssemblyMember, Endpoint> nodes = new HashMap<>();

        Graph(Fixture fixture) throws Exception {
            this.fixture = fixture;
            for (MatrixAssemblyMember member : fixture.members) {
                Endpoint endpoint = new Endpoint();
                nodes.put(member, endpoint);
                field(MatrixAssemblyPort.class, "proxy")
                    .set(member.matrixPort(), new AENetworkProxy(null, "test", null, true) {

                        @Override
                        public IGridNode getNode() {
                            return endpoint.node;
                        }

                        @Override
                        public boolean isActive() {
                            return true;
                        }
                    });
            }
            for (int i = 1; i < fixture.members.size(); i++)
                connect(nodes.get(fixture.members.get(i - 1)), nodes.get(fixture.members.get(i)));
            fixture.members.get(0)
                .matrixPort()
                .networkChanged();
        }

        IGridConnection external(MatrixAssemblyMember member) {
            IGridConnection edge = connect(nodes.get(member), new Endpoint());
            member.matrixPort()
                .networkChanged();
            return edge;
        }

        void remove(IGridConnection edge) {
            for (Endpoint endpoint : nodes.values()) endpoint.edges.remove(edge);
            fixture.members.get(0)
                .matrixPort()
                .networkChanged();
        }

        int reads() {
            return nodes.values()
                .stream()
                .mapToInt(node -> node.reads)
                .sum();
        }

        private static IGridConnection connect(Endpoint left, Endpoint right) {
            IGridConnection edge = (IGridConnection) Proxy.newProxyInstance(
                IGridConnection.class.getClassLoader(),
                new Class<?>[] { IGridConnection.class },
                (proxy, method, args) -> {
                    if (method.getName()
                        .equals("getOtherSide")) return args[0] == left.node ? right.node : left.node;
                    if (method.getName()
                        .equals("equals")) return proxy == args[0];
                    throw new AssertionError(method);
                });
            left.edges.add(edge);
            right.edges.add(edge);
            return edge;
        }
    }

    private static final class Endpoint {

        final List<IGridConnection> edges = new ArrayList<>();
        int reads;
        final IGridNode node = (IGridNode) Proxy.newProxyInstance(
            IGridNode.class.getClassLoader(),
            new Class<?>[] { IGridNode.class },
            (proxy, method, args) -> {
                if (method.getName()
                    .equals("getConnections")) {
                    reads++;
                    return new ReadOnlyCollection<>(edges);
                }
                throw new AssertionError(method);
            });
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
            field(MatrixAssemblyPort.class, "proxy").set(port, new AENetworkProxy(null, "matrixProxy", null, true) {

                @Override
                public IGridNode getNode() {
                    return null;
                }
            });
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

        MTEMultiBlockBase controller(OrderedAssemblyGroup group, int x) throws Exception {
            MTEMultiBlockBase controller = new FixtureController();
            for (MatrixAssemblyMember member : group.itemMembers())
                controller.mInputBusses.add((MTEHatchInputBus) member);
            for (MatrixAssemblyMember member : group.fluidMembers())
                controller.mInputHatches.add((MTEHatchInput) member);
            attach(controller, x, 64, 20);
            controller.mMachine = true;
            OrderedAssemblyGroup.controllerChecked(group.master(), controller, true);
            return controller;
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

    /** Ordinary native controller state without the client-only tooltip class initializer. */
    private static final class FixtureController extends MTEMultiBlockBase {

        FixtureController() {
            super("matrix_topology_test");
        }

        @Override
        public String[] getDescription() {
            return new String[0];
        }

        @Override
        public IMetaTileEntity newMetaEntity(IGregTechTileEntity tile) {
            return new FixtureController();
        }

        @Override
        public ITexture[] getTexture(IGregTechTileEntity tile, ForgeDirection side, ForgeDirection facing, int color,
            boolean active, boolean redstone) {
            return new ITexture[0];
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
        public long getTotalWorldTime() {
            return 0;
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

    private static OrderedPatternLayout layout(int itemCount, int[] fluidAmounts) throws Exception {
        Class<?> fixtures = Class.forName("com.miaokatze.gtswn.common.assembly.pattern.AssemblyPatternFixtures");
        invoke(fixtures, "initialize", new Class<?>[0]);
        IAEStack<?>[] inputs = new IAEStack<?>[itemCount + fluidAmounts.length];
        for (int i = 0; i < itemCount; i++) {
            ItemStack stack = (ItemStack) invoke(fixtures, "input", new Class<?>[] { int.class }, Integer.MAX_VALUE);
            inputs[i] = (IAEStack<?>) invoke(fixtures, "item", new Class<?>[] { ItemStack.class }, stack);
        }
        for (int i = 0; i < fluidAmounts.length; i++) inputs[itemCount + i] = (IAEStack<?>) invoke(
            fixtures,
            "fluid",
            new Class<?>[] { net.minecraftforge.fluids.FluidStack.class },
            FluidRegistryTestBootstrap.water(fluidAmounts[i]));
        ItemStack output = (ItemStack) invoke(fixtures, "output", new Class<?>[] { int.class }, 1);
        IAEItemStack result = (IAEItemStack) invoke(fixtures, "item", new Class<?>[] { ItemStack.class }, output);
        ICraftingPatternDetails details = (ICraftingPatternDetails) invoke(
            fixtures,
            "ultimate",
            new Class<?>[] { IAEStack[].class, IAEItemStack.class },
            inputs,
            result);
        return OrderedPatternLayout.decode(details);
    }

    private static Object invoke(Class<?> type, String name, Class<?>[] signature, Object... arguments)
        throws Exception {
        Method method = type.getDeclaredMethod(name, signature);
        method.setAccessible(true);
        return method.invoke(null, arguments);
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
