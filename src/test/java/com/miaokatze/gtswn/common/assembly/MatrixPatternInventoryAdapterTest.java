package com.miaokatze.gtswn.common.assembly;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.profiler.Profiler;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.storage.ISaveHandler;

import org.junit.Test;

import appeng.api.implementations.ICraftingPatternItem;
import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.util.IInterfaceViewable;
import appeng.container.implementations.ContainerInterfaceTerminal;
import appeng.helpers.InventoryAction;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.api.metatileentity.CommonMetaTileEntity;

public class MatrixPatternInventoryAdapterTest {

    private final Item patternItem = new PatternItem();

    @Test
    public void bothMemberTypesExposeTheNativeFourByNineTerminalContract() throws Exception {
        for (Class<?> type : new Class<?>[] { MatrixItemInputBus.class, MatrixFluidInputHatch.class }) {
            IInterfaceViewable member = (IInterfaceViewable) allocate(type);
            MatrixAssemblyPort port = new MatrixAssemblyPort((MatrixAssemblyMember) member);
            set(type, member, "matrix", port);
            set(type, member, "interfacePatterns", new MatrixPatternInventoryAdapter(port));
            assertEquals(4, member.rows());
            assertEquals(9, member.rowSize());
            assertEquals(36, member.numSlots());
            assertFalse(member.allowsPatternOptimization());
            assertFalse(member.isCraftingPatternProvider());
            assertSame(member.getPatterns(), member.getPatterns());
            assertFalse(member.shouldDisplay());
            OrderedAssemblyGroup group = (OrderedAssemblyGroup) allocate(OrderedAssemblyGroup.class);
            set(OrderedAssemblyGroup.class, group, "master", member);
            set(MatrixAssemblyPort.class, port, "group", group);
            assertTrue(member.shouldDisplay());
            set(OrderedAssemblyGroup.class, group, "master", null);
            assertFalse(member.shouldDisplay());
        }
    }

    @Test
    public void aCachedViewFollowsTheCurrentMasterWithoutExposingMutablePatternStacks() throws Exception {
        Fixture fixture = new Fixture();
        MatrixPatternInventoryAdapter view = new MatrixPatternInventoryAdapter(fixture.members.get(4).port);
        view.setInventorySlotContents(35, pattern("first"));
        assertEquals(36, view.getSizeInventory());
        assertNotNull(
            fixture.members.get(0).port.patterns()
                .getStackInSlot(35));
        assertNull(
            fixture.members.get(4).port.patterns()
                .getStackInSlot(35));
        ItemStack detached = view.getStackInSlot(35);
        detached.stackSize = 9;
        detached.getTagCompound()
            .setString("label", "changed");
        assertEquals(1, view.getStackInSlot(35).stackSize);
        assertEquals(
            "first",
            view.getStackInSlot(35)
                .getTagCompound()
                .getString("label"));

        set(OrderedAssemblyGroup.class, fixture.group, "master", fixture.members.get(1).meta);
        fixture.members.get(1).port.patterns()
            .setStackInSlot(35, pattern("next"));
        assertEquals(
            "next",
            view.getStackInSlot(35)
                .getTagCompound()
                .getString("label"));
        view.setInventorySlotContents(35, null);
        assertNull(
            fixture.members.get(1).port.patterns()
                .getStackInSlot(35));
        assertNotNull(
            fixture.members.get(0).port.patterns()
                .getStackInSlot(35));
    }

    @Test
    public void directSetExtractAndClosingRetainThePatternHandlersValidationAndBusyLock() throws Exception {
        Fixture fixture = new Fixture();
        MatrixPatternInventoryAdapter view = new MatrixPatternInventoryAdapter(fixture.members.get(0).port);
        assertFalse(view.isItemValidForSlot(0, new ItemStack(new Item())));
        view.setInventorySlotContents(0, new ItemStack(new Item()));
        assertNull(view.getStackInSlot(0));
        assertTrue(view.isItemValidForSlot(0, pattern("stored")));
        view.setInventorySlotContents(0, pattern("stored"));
        set(OrderedAssemblyGroup.class, fixture.group, "stage", OrderedAssemblyGroup.Stage.STAGED);
        assertEquals(0, view.getSizeInventory());
        assertFalse(view.isItemValidForSlot(0, pattern("replacement")));
        view.setInventorySlotContents(0, null);
        view.setInventorySlotContents(1, pattern("new"));
        assertNull(view.decrStackSize(0, 1));
        assertNull(view.getStackInSlotOnClosing(0));
        assertNotNull(view.getStackInSlot(0));
        assertNull(view.getStackInSlot(1));
        set(OrderedAssemblyGroup.class, fixture.group, "stage", OrderedAssemblyGroup.Stage.IDLE);
        assertNotNull(view.decrStackSize(0, 1));
        assertNull(view.getStackInSlot(0));

        set(MatrixAssemblyPort.class, fixture.members.get(0).port, "group", null);
        assertEquals(0, view.getSizeInventory());
        view.setInventorySlotContents(0, pattern("ungrouped"));
        assertNull(view.getStackInSlot(0));
    }

    @Test
    public void realAeTerminalActionsStopBeforeAnyPlayerTransferWhileTheGroupIsLocked() throws Exception {
        Fixture fixture = new Fixture();
        MatrixPatternInventoryAdapter view = new MatrixPatternInventoryAdapter(fixture.members.get(0).port);
        ItemStack stored = pattern("stored");
        view.setInventorySlotContents(0, stored);
        ContainerInterfaceTerminal terminal = terminal(view);
        InventoryAction[] actions = { InventoryAction.PICKUP_OR_SET_DOWN, InventoryAction.SHIFT_CLICK,
            InventoryAction.MOVE_REGION, InventoryAction.MULTIPLY_PATTERN, InventoryAction.DIVIDE_PATTERN };
        for (OrderedAssemblyGroup.Stage stage : OrderedAssemblyGroup.Stage.values()) {
            if (stage == OrderedAssemblyGroup.Stage.IDLE) continue;
            set(OrderedAssemblyGroup.class, fixture.group, "stage", stage);
            assertEquals(0, view.getSizeInventory());
            assertTrue(ItemStack.areItemStacksEqual(stored, view.getStackInSlot(0)));
            for (InventoryAction action : actions) {
                EntityPlayerMP player = (EntityPlayerMP) allocate(EntityPlayerMP.class);
                player.inventory = new InventoryPlayer(player);
                terminal.doAction(player, action, 0, 7L);
                assertNull(player.inventory.getItemStack());
                for (ItemStack stack : player.inventory.mainInventory) assertNull(stack);
                assertTrue(ItemStack.areItemStacksEqual(stored, view.getStackInSlot(0)));
                ItemStack held = pattern("held");
                player.inventory.setItemStack(held);
                terminal.doAction(player, action, 1, 7L);
                assertSame(held, player.inventory.getItemStack());
                assertNull(view.getStackInSlot(1));
            }
        }
    }

    @Test
    public void aCachedTerminalViewStopsTransfersAfterGtClearsEitherTheMasterOrAnchorBase() throws Exception {
        for (int removed : new int[] { 0, 4 }) {
            Fixture fixture = new Fixture();
            MatrixPatternInventoryAdapter view = new MatrixPatternInventoryAdapter(fixture.members.get(4).port);
            ItemStack stored = pattern("stored");
            view.setInventorySlotContents(0, stored);
            ContainerInterfaceTerminal terminal = terminal(view);
            fixture.members.get(removed).meta.setBaseMetaTileEntity(null);
            assertEquals(0, view.getSizeInventory());
            assertTrue(ItemStack.areItemStacksEqual(stored, view.getStackInSlot(0)));
            view.markDirty();
            EntityPlayerMP player = (EntityPlayerMP) allocate(EntityPlayerMP.class);
            player.inventory = new InventoryPlayer(player);
            terminal.doAction(player, InventoryAction.PICKUP_OR_SET_DOWN, 0, 7L);
            terminal.doAction(player, InventoryAction.SHIFT_CLICK, 0, 7L);
            assertNull(player.inventory.getItemStack());
            for (ItemStack stack : player.inventory.mainInventory) assertNull(stack);
            assertTrue(ItemStack.areItemStacksEqual(stored, view.getStackInSlot(0)));
        }
    }

    private ItemStack pattern(String label) {
        ItemStack stack = new ItemStack(patternItem);
        stack.setTagCompound(new NBTTagCompound());
        stack.getTagCompound()
            .setString("label", label);
        return stack;
    }

    private static ContainerInterfaceTerminal terminal(IInventory patterns) throws Exception {
        // Keep AE's real dispatch method; only skip the constructor's live grid/network bootstrap.
        ContainerInterfaceTerminal terminal = (ContainerInterfaceTerminal) allocate(ContainerInterfaceTerminal.class);
        Class<?> trackerType = Class.forName(ContainerInterfaceTerminal.class.getName() + "$InvTracker");
        Object tracker = allocate(trackerType);
        set(trackerType, tracker, "patterns", patterns);
        set(trackerType, tracker, "numSlots", MatrixPatternInventory.SIZE);
        Map<Long, Object> trackedById = new HashMap<>();
        trackedById.put(7L, tracker);
        set(ContainerInterfaceTerminal.class, terminal, "trackedById", trackedById);
        return terminal;
    }

    private static Object allocate(Class<?> type) throws Exception {
        Field singleton = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        return ((sun.misc.Unsafe) singleton.get(null)).allocateInstance(type);
    }

    private static void set(Class<?> type, Object target, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static final class Fixture {

        final List<Member> members = new ArrayList<>();
        final OrderedAssemblyGroup group;

        Fixture() throws Exception {
            FixtureWorld world = (FixtureWorld) allocate(FixtureWorld.class);
            world.tiles = new HashMap<>();
            List<MatrixAssemblyMember> liveMembers = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                Member member = new Member(world, i);
                members.add(member);
                liveMembers.add(member.meta);
            }
            group = (OrderedAssemblyGroup) allocate(OrderedAssemblyGroup.class);
            set(OrderedAssemblyGroup.class, group, "master", members.get(0).meta);
            set(OrderedAssemblyGroup.class, group, "members", liveMembers);
            set(OrderedAssemblyGroup.class, group, "stage", OrderedAssemblyGroup.Stage.IDLE);
            set(OrderedAssemblyGroup.class, group, "verified", true);
            for (Member member : members) set(MatrixAssemblyPort.class, member.port, "group", group);
        }
    }

    private static final class Member {

        final MatrixItemInputBus meta;
        final MatrixAssemblyPort port;

        Member(FixtureWorld world, int x) throws Exception {
            meta = (MatrixItemInputBus) allocate(MatrixItemInputBus.class);
            port = new MatrixAssemblyPort(meta);
            set(MatrixItemInputBus.class, meta, "matrix", port);
            set(CommonMetaTileEntity.class, meta, "mInventory", new ItemStack[1]);
            QuietTile tile = new QuietTile();
            tile.setWorldObj(world);
            tile.xCoord = x;
            meta.setBaseMetaTileEntity(tile);
            world.tiles.put(x, tile);
        }
    }

    private static final class QuietTile extends BaseMetaTileEntity {

        @Override
        public void markDirty() {}
    }

    private static final class FixtureWorld extends WorldServer {

        Map<Integer, TileEntity> tiles;

        private FixtureWorld() {
            super(null, (ISaveHandler) null, "fixture", 0, (WorldSettings) null, (Profiler) null);
        }

        @Override
        public boolean blockExists(int x, int y, int z) {
            return tiles.containsKey(x);
        }

        @Override
        public TileEntity getTileEntity(int x, int y, int z) {
            return tiles.get(x);
        }
    }

    private static final class PatternItem extends Item implements ICraftingPatternItem {

        @Override
        public ICraftingPatternDetails getPatternForItem(ItemStack stack, World world) {
            return null;
        }
    }
}
