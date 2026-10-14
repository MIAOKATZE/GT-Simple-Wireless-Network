package com.miaokatze.gtswn.common.quantum;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ObjectIntIdentityMap;
import net.minecraft.util.RegistryNamespaced;
import net.minecraftforge.common.util.ForgeDirection;

import org.junit.BeforeClass;
import org.junit.Test;

import com.miaokatze.gtswn.common.tile.TileEntityNetworkQuantumNode;

import appeng.api.implementations.parts.IPartCable;
import appeng.api.networking.IGridConnection;
import appeng.api.networking.IGridNode;
import appeng.api.parts.BusSupport;
import appeng.api.parts.IPart;
import appeng.api.parts.IPartItem;
import appeng.api.parts.PartItemStack;
import appeng.parts.CableBusContainer;
import appeng.parts.networking.PartQuartzFiber;
import appeng.util.ReadOnlyCollection;

public class QuantumNodeCableTest {

    private static final Item RETURNED_CONTENT = new Item();
    private static final Item RETAINED_SIDE = new Item();
    private static final PartItem CABLE = new PartItem(true, true);
    private static final PartItem UNSUPPORTED = new PartItem(false, false);
    private static final PartItem BUS = new PartItem(false, true);
    private static final FiberItem FIBER = new FiberItem();

    @BeforeClass
    public static void registerFixtureItemIds() throws Exception {
        // Like MonitorBatteryRecipeTest, register only the vanilla ID identity map.
        // Forge's named mod registration requires a running LaunchClassLoader.
        ObjectIntIdentityMap ids = null;
        for (Field field : RegistryNamespaced.class.getDeclaredFields()) {
            if (field.getType() == ObjectIntIdentityMap.class) {
                field.setAccessible(true);
                ids = (ObjectIntIdentityMap) field.get(Item.itemRegistry);
            }
        }
        assertNotNull(ids);
        Item[] items = { RETURNED_CONTENT, RETAINED_SIDE, CABLE, UNSUPPORTED, BUS, FIBER };
        for (int i = 0; i < items.length; i++) ids.func_148746_a(items[i], 31101 + i);
    }

    private static class PartItem extends Item implements IPartItem {

        final boolean cable;
        final boolean supported;

        PartItem(boolean cable, boolean supported) {
            this.cable = cable;
            this.supported = supported;
        }

        @Override
        public IPart createPartFromItemStack(ItemStack stack) {
            final NBTTagCompound state = new NBTTagCompound();
            return (IPart) Proxy.newProxyInstance(
                IPart.class.getClassLoader(),
                new Class<?>[] { cable ? IPartCable.class : IPart.class },
                (part, method, args) -> {
                    switch (method.getName()) {
                        case "canBePlacedOn":
                            return supported && args[0] == BusSupport.CABLE;
                        case "supportsBuses":
                            return BusSupport.CABLE;
                        case "getItemStack":
                            return stack;
                        case "readFromNBT":
                            state.setInteger("contents", ((NBTTagCompound) args[0]).getInteger("contents"));
                            break;
                        case "getDrops":
                            int count = state.getInteger("contents");
                            if (count > 0) ((List<ItemStack>) args[0]).add(new ItemStack(RETURNED_CONTENT, count));
                            break;
                    }
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == int.class) return 0;
                    return null;
                });
        }
    }

    private static class FiberItem extends Item implements IPartItem {

        @Override
        public IPart createPartFromItemStack(ItemStack stack) {
            return new PartQuartzFiber(stack);
        }
    }

    private static class AcceptingContainer extends CableBusContainer {

        int additions;

        AcceptingContainer(TileEntityNetworkQuantumNode node) {
            super(node);
        }

        @Override
        public boolean canAddPart(ItemStack stack, ForgeDirection side) {
            return true;
        }

        @Override
        public ForgeDirection addPart(ItemStack stack, ForgeDirection side, EntityPlayer player) {
            additions++;
            return side;
        }
    }

    @Test
    public void hostChecksTheSharedRuleEvenWhenItsContainerWouldAcceptTheItem() throws Exception {
        TileEntityNetworkQuantumNode node = new TileEntityNetworkQuantumNode();
        AcceptingContainer container = new AcceptingContainer(node);
        Field stored = TileEntityNetworkQuantumNode.class.getDeclaredField("partContainer");
        stored.setAccessible(true);
        stored.set(node, container);
        for (Item item : new Item[] { CABLE, UNSUPPORTED, FIBER }) {
            ItemStack held = new ItemStack(item, 12);
            for (ForgeDirection side : ForgeDirection.values()) {
                assertFalse(node.canAddPart(held, side));
                assertNull(node.addPart(held, side, null));
                assertEquals(12, held.stackSize);
            }
        }
        assertEquals(0, container.additions);
        assertTrue(node.canAddPart(new ItemStack(BUS), ForgeDirection.NORTH));
        assertEquals(ForgeDirection.NORTH, node.addPart(new ItemStack(BUS), ForgeDirection.NORTH, null));
        assertEquals(1, container.additions);
    }

    @Test
    public void virtualCableHasNoSeparateItemGridLifecycleOrCollision() {
        TileEntityNetworkQuantumNode node = new TileEntityNetworkQuantumNode();
        assertSame(node.getCablePart(), node.getPart(ForgeDirection.UNKNOWN));
        assertEquals(
            BusSupport.CABLE,
            node.getCablePart()
                .supportsBuses());
        assertNull(
            node.getCablePart()
                .getGridNode());
        assertFalse(node.hasParts());
        for (PartItemStack type : PartItemStack.values()) assertNull(
            node.getCablePart()
                .getItemStack(type));
        List<ItemStack> drops = new ArrayList<>();
        node.getCablePart()
            .getDrops(drops, false);
        assertTrue(drops.isEmpty());
        node.removePart(ForgeDirection.UNKNOWN, false);
        assertSame(node.getCablePart(), node.getPart(ForgeDirection.UNKNOWN));
        node.getCablePart()
            .addToWorld();
        node.getCablePart()
            .removeFromWorld();
        assertNull(node.getCoreGridNode());
    }

    @Test
    public void migrationCopiesOnlyConflictingDefinitionsAndReturnsNativeBreakContents() {
        NBTTagCompound saved = new NBTTagCompound();
        saved.setTag("def:6", new ItemStack(CABLE).writeToNBT(new NBTTagCompound()));
        NBTTagCompound centerState = new NBTTagCompound();
        centerState.setInteger("contents", 3);
        saved.setTag("extra:6", centerState);
        saved.setTag("def:0", new ItemStack(FIBER).writeToNBT(new NBTTagCompound()));
        saved.setTag("extra:0", new NBTTagCompound());
        saved.setTag("def:2", new ItemStack(RETAINED_SIDE).writeToNBT(new NBTTagCompound()));
        NBTTagCompound inventory = new NBTTagCompound();
        inventory.setString("pattern", "encoded-pattern-with-NBT");
        inventory.setString("upgrade", "capacity-card-with-NBT");
        saved.setTag("extra:2", inventory);
        saved.setString("facade:4", "unchanged-facade");
        List<ItemStack> drops = new ArrayList<>();
        NBTTagCompound migrated = QuantumNodePartMigration.stripConflicts(saved, drops);
        assertTrue(saved.hasKey("def:6"));
        assertTrue(saved.hasKey("def:0"));
        assertFalse(migrated.hasKey("def:6"));
        assertFalse(migrated.hasKey("extra:6"));
        assertFalse(migrated.hasKey("def:0"));
        assertFalse(migrated.hasKey("extra:0"));
        assertEquals(saved.getCompoundTag("def:2"), migrated.getCompoundTag("def:2"));
        assertEquals(inventory, migrated.getCompoundTag("extra:2"));
        assertEquals("unchanged-facade", migrated.getString("facade:4"));
        assertEquals(3, drops.size());
        assertSame(
            FIBER,
            drops.get(0)
                .getItem());
        assertSame(
            CABLE,
            drops.get(1)
                .getItem());
        assertSame(
            RETURNED_CONTENT,
            drops.get(2)
                .getItem());
        assertEquals(3, drops.get(2).stackSize);
        assertTrue(
            QuantumNodePartMigration.stripConflicts(migrated, new ArrayList<>())
                .equals(migrated));
    }

    @Test
    public void pendingRecoverySurvivesSavingBeforeTheFirstWorldTick() {
        net.minecraft.tileentity.TileEntity.addMapping(TileEntityNetworkQuantumNode.class, "gtswn.test.cable_node");
        NBTTagCompound old = new NBTTagCompound();
        NBTTagCompound parts = new NBTTagCompound();
        parts.setTag("def:6", new ItemStack(CABLE).writeToNBT(new NBTTagCompound()));
        parts.setTag("extra:6", new NBTTagCompound());
        old.setTag("partContainer", parts);
        TileEntityNetworkQuantumNode node = new TileEntityNetworkQuantumNode();
        node.readFromNBT(old);
        NBTTagCompound persisted = new NBTTagCompound();
        node.writeToNBT(persisted);
        assertEquals(
            1,
            persisted.getTagList("recoveredPartDrops", 10)
                .tagCount());
        assertFalse(
            persisted.getCompoundTag("partContainer")
                .hasKey("def:6"));
        TileEntityNetworkQuantumNode reloaded = new TileEntityNetworkQuantumNode();
        reloaded.readFromNBT(persisted);
        List<ItemStack> drops = new ArrayList<>();
        reloaded.collectRecoveredPartDrops(drops);
        assertEquals(1, drops.size());
        assertSame(
            CABLE,
            drops.get(0)
                .getItem());
        reloaded.collectRecoveredPartDrops(drops);
        assertEquals(1, drops.size());
    }

    @Test
    public void dequantizationDestroysTheBridgeImmediatelyAndRetainsTheBinding() throws Exception {
        TileEntityNetworkQuantumNode node = new TileEntityNetworkQuantumNode();
        node.setAnchor(7, 11, 22, 33);
        java.util.concurrent.atomic.AtomicInteger destroyed = new java.util.concurrent.atomic.AtomicInteger();
        List<IGridConnection> localEdges = new ArrayList<>();
        List<IGridConnection> remoteEdges = new ArrayList<>();
        IGridNode local = endpoint(localEdges);
        IGridNode remote = endpoint(remoteEdges);
        IGridConnection bridge = (IGridConnection) Proxy.newProxyInstance(
            IGridConnection.class.getClassLoader(),
            new Class<?>[] { IGridConnection.class },
            (connection, method, args) -> {
                switch (method.getName()) {
                    case "a":
                        return local;
                    case "b":
                        return remote;
                    case "equals":
                        return connection == args[0];
                    case "hashCode":
                        return System.identityHashCode(connection);
                    case "destroy":
                        destroyed.incrementAndGet();
                        localEdges.remove(connection);
                        remoteEdges.remove(connection);
                        break;
                }
                return null;
            });
        localEdges.add(bridge);
        remoteEdges.add(bridge);
        Field stored = TileEntityNetworkQuantumNode.class.getDeclaredField("connection");
        stored.setAccessible(true);
        stored.set(node, bridge);
        node.disconnectFromDequantizedAnchor();
        assertEquals(1, destroyed.get());
        assertNull(stored.get(node));
        assertEquals(TileEntityNetworkQuantumNode.OfflineReason.ANCHOR_NOT_QUANTIZED, node.getOfflineReason());
        assertEquals(7, node.getAnchorDim());
        assertEquals(11, node.getAnchorX());
        assertEquals(22, node.getAnchorY());
        assertEquals(33, node.getAnchorZ());
        node.disconnectFromDequantizedAnchor();
        assertEquals(1, destroyed.get());
    }

    private static IGridNode endpoint(List<IGridConnection> edges) {
        return (IGridNode) Proxy.newProxyInstance(
            IGridNode.class.getClassLoader(),
            new Class<?>[] { IGridNode.class },
            (node, method, args) -> method.getName()
                .equals("getConnections") ? new ReadOnlyCollection<>(edges) : null);
    }

    @Test
    public void alreadyRemovedBridgeDoesNotDestroyOrRepathEitherEndpointAgain() throws Exception {
        TileEntityNetworkQuantumNode node = new TileEntityNetworkQuantumNode();
        List<IGridConnection> localEdges = new ArrayList<>();
        List<IGridConnection> remoteEdges = new ArrayList<>();
        IGridNode local = endpoint(localEdges), remote = endpoint(remoteEdges);
        java.util.concurrent.atomic.AtomicInteger destroyed = new java.util.concurrent.atomic.AtomicInteger();
        IGridConnection bridge = (IGridConnection) Proxy.newProxyInstance(
            IGridConnection.class.getClassLoader(),
            new Class<?>[] { IGridConnection.class },
            (connection, method, args) -> {
                switch (method.getName()) {
                    case "a":
                        return local;
                    case "b":
                        return remote;
                    case "equals":
                        return connection == args[0];
                    case "hashCode":
                        return System.identityHashCode(connection);
                    case "destroy":
                        destroyed.incrementAndGet();
                        localEdges.remove(connection);
                        remoteEdges.remove(connection);
                        break;
                }
                return null;
            });
        localEdges.add(bridge);
        remoteEdges.add(bridge);
        bridge.destroy(); // The controller endpoint's lifecycle already removed the edge.
        Field stored = TileEntityNetworkQuantumNode.class.getDeclaredField("connection");
        stored.setAccessible(true);
        stored.set(node, bridge);
        node.disconnectFromDequantizedAnchor();
        node.invalidate();
        node.onChunkUnload();
        assertEquals(1, destroyed.get());
        assertTrue(localEdges.isEmpty());
        assertTrue(remoteEdges.isEmpty());
        assertNull(stored.get(node));
    }
}
