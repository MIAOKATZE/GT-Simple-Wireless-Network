package com.miaokatze.gtswn.common.quantum;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.play.server.S35PacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.ForgeDirection;

import org.junit.Test;

import com.miaokatze.gtswn.common.tile.TileEntityNetworkQuantumNode;
import com.miaokatze.gtswn.mixins.ae2.TileCableBusAccessor;

import appeng.api.implementations.parts.IPartCable;
import appeng.api.networking.IGridConnection;
import appeng.api.networking.IGridNode;
import appeng.api.parts.IPart;
import appeng.api.parts.IPartHost;
import appeng.api.parts.PartItemStack;
import appeng.me.helpers.AENetworkProxy;
import appeng.parts.CableBusContainer;
import appeng.tile.networking.TileCableBus;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public class QuantumBusReplacementTest {

    private static class Bus extends TileCableBus implements TileCableBusAccessor {

        CableBusContainer container = new CableBusContainer(this);

        @Override
        public CableBusContainer getCableBus() {
            return container;
        }

        @Override
        public void clearContainer() {
            container = new CableBusContainer(this);
        }

        @Override
        public void validate() {
            // No Forge scheduler in this unit-test JVM; retain vanilla TileEntity validation.
            tileEntityInvalid = false;
        }

        @Override
        public void gtswn$setCableBus(CableBusContainer value) {
            container = value;
        }
    }

    private static class Parts extends CableBusContainer {

        Parts(Bus host) {
            super(host);
        }

        void put(ForgeDirection side, IPart part) {
            setSide(side, part);
        }

        void center(IPartCable part) {
            setCenter(part);
        }
    }

    /** Models AE2 <=1050: setHost changes the container host but leaves each part's tile stale. */
    private static class LegacyParts extends Parts {

        IPartHost currentHost;

        LegacyParts(Bus host) {
            super(host);
            currentHost = host;
        }

        @Override
        public void setHost(IPartHost host) {
            currentHost.clearContainer();
            currentHost = host;
        }

        @Override
        public TileEntity getTile() {
            return currentHost.getTile();
        }
    }

    private static class PartState {

        final NBTTagCompound inventory = new NBTTagCompound();
        final ItemStack definition = new ItemStack(new Item());
        final AtomicInteger destructiveCalls = new AtomicInteger();
        TileEntity tile;
        Object host;
        ForgeDirection side;
        boolean failRebind;
        final IPart part = (IPart) Proxy
            .newProxyInstance(IPart.class.getClassLoader(), new Class<?>[] { IPart.class }, (proxy, method, args) -> {
                if (method.getName()
                    .equals("setPartHostInfo")) {
                    if (failRebind) {
                        failRebind = false;
                        throw new IllegalStateException("rebind");
                    }
                    side = (ForgeDirection) args[0];
                    host = args[1];
                    tile = (TileEntity) args[2];
                } else if (method.getName()
                    .equals("getItemStack")) {
                        if (args[0] == PartItemStack.Wrench) destructiveCalls.incrementAndGet();
                        return definition;
                    } else if (method.getName()
                        .equals("removeFromWorld")
                        || method.getName()
                            .equals("getDrops")
                        || method.getName()
                            .equals("onPlacement")) {
                                destructiveCalls.incrementAndGet();
                            } else
                        if (method.getName()
                            .equals("writeToNBT")) {
                                ((NBTTagCompound) args[0]).setTag("inventory", inventory.copy());
                            }
                if (method.getReturnType() == boolean.class) return false;
                if (method.getReturnType() == int.class) return 0;
                return null;
            });

        PartState() {
            definition.setTagCompound(inventory);
            inventory.setString("pattern", "encoded-pattern-with-NBT");
            inventory.setString("upgrade", "capacity-card-with-NBT");
        }
    }

    @Test
    public void keepsSixOriginalPartsAndIsolatesOldHostLifecycle() throws Exception {
        Bus bus = new Bus();
        Parts container = new Parts(bus);
        bus.container = container;
        Field inWorld = CableBusContainer.class.getDeclaredField("inWorld");
        inWorld.setAccessible(true);
        inWorld.setBoolean(container, true);
        PartState[] parts = new PartState[6];
        for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) {
            parts[side.ordinal()] = new PartState();
            container.put(side, parts[side.ordinal()].part);
        }
        TileEntityNetworkQuantumNode node = new TileEntityNetworkQuantumNode();
        assertTrue(QuantumBusReplacement.replace(bus, node, new QuantumBusReplacement.Placement() {

            public boolean install() {
                assertTrue(bus.isEmpty());
                bus.invalidate();
                bus.onChunkUnload();
                return true;
            }

            public void restore() {
                fail("Successful migration must not roll back");
            }
        }));
        assertSame(container, node.getPartContainer());
        assertTrue(inWorld.getBoolean(container));
        for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) {
            PartState state = parts[side.ordinal()];
            assertSame(state.part, node.getPart(side));
            assertSame(node, state.tile);
            assertSame(container, state.host);
            assertSame(side, state.side);
            assertEquals("encoded-pattern-with-NBT", state.inventory.getString("pattern"));
            assertEquals("capacity-card-with-NBT", state.inventory.getString("upgrade"));
            assertEquals(0, state.destructiveCalls.get());
        }
    }

    @Test
    public void restoresSameContainerForFalseInstallAndInstallOrRebindExceptions() {
        for (int failure = 0; failure < 3; failure++) {
            Bus bus = new Bus();
            Parts container = new Parts(bus);
            bus.container = container;
            PartState state = new PartState();
            state.failRebind = failure == 2;
            container.put(ForgeDirection.NORTH, state.part);
            TileEntityNetworkQuantumNode node = new TileEntityNetworkQuantumNode();
            AtomicInteger restores = new AtomicInteger();
            final int mode = failure;
            try {
                assertFalse(QuantumBusReplacement.replace(bus, node, new QuantumBusReplacement.Placement() {

                    public boolean install() {
                        bus.invalidate();
                        if (mode == 1) throw new IllegalStateException("installation");
                        return false;
                    }

                    public void restore() {
                        restores.incrementAndGet();
                        bus.validate();
                    }
                }));
                assertEquals(0, failure);
            } catch (IllegalStateException expected) {
                assertTrue(failure > 0);
            }
            assertEquals(1, restores.get());
            assertSame(container, bus.getCableBus());
            assertSame(state.part, bus.getPart(ForgeDirection.NORTH));
            assertSame(bus, state.tile);
            assertTrue(node.isEmpty());
            assertFalse(bus.isInvalid());
            assertEquals(0, state.destructiveCalls.get());
        }
    }

    @Test
    public void explicitlyRebindsLegacyHostsAndKeepsExistingNbtAndS35Encoding() throws Exception {
        Bus bus = new Bus();
        LegacyParts container = new LegacyParts(bus);
        bus.container = container;
        PartState state = new PartState();
        container.put(ForgeDirection.SOUTH, state.part);
        NBTTagCompound expected = new NBTTagCompound();
        container.writeToNBT(expected);
        ByteBuf stream = Unpooled.buffer();
        byte[] expectedStream;
        try {
            container.writeToStream(stream);
            expectedStream = new byte[stream.readableBytes()];
            stream.readBytes(expectedStream);
        } finally {
            stream.release();
        }
        TileEntityNetworkQuantumNode node = new TileEntityNetworkQuantumNode();
        assertTrue(QuantumBusReplacement.replace(bus, node, new QuantumBusReplacement.Placement() {

            public boolean install() {
                return true;
            }

            public void restore() {
                fail();
            }
        }));
        assertSame(node, state.tile);
        assertSame(container, state.host);
        TileEntity.addMapping(TileEntityNetworkQuantumNode.class, "gtswn.test.quantum_node");
        NBTTagCompound nodeTag = new NBTTagCompound();
        node.writeToNBT(nodeTag);
        assertEquals(expected, nodeTag.getCompoundTag("partContainer"));
        NBTTagCompound update = ((S35PacketUpdateTileEntity) node.getDescriptionPacket()).func_148857_g();
        assertArrayEquals(expectedStream, update.getByteArray("cbParts"));
        assertFalse(update.hasKey("proxy"));
        assertEquals(0, state.destructiveCalls.get());
    }

    @Test
    public void rejectsEmptyAndCenterCableBeforeAnyMutation() {
        Bus bus = new Bus();
        Parts container = new Parts(bus);
        bus.container = container;
        TileEntityNetworkQuantumNode node = new TileEntityNetworkQuantumNode();
        QuantumBusReplacement.Placement forbidden = new QuantumBusReplacement.Placement() {

            public boolean install() {
                fail("Must reject before installation");
                return false;
            }

            public void restore() {
                fail("No rollback for untouched host");
            }
        };
        assertFalse(QuantumBusReplacement.replace(bus, node, forbidden));
        container.center(
            (IPartCable) Proxy.newProxyInstance(
                IPartCable.class.getClassLoader(),
                new Class<?>[] { IPartCable.class },
                (proxy, method, args) -> null));
        assertFalse(QuantumBusReplacement.replace(bus, node, forbidden));
        assertSame(container, bus.getCableBus());
        assertTrue(node.isEmpty());
    }

    @Test
    public void removingCoreRetainsSixLivePartsAndDestroysOnlyQuantumBridge() throws Exception {
        Bus seed = new Bus();
        Parts container = new Parts(seed);
        seed.container = container;
        PartState[] parts = new PartState[6];
        for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) {
            parts[side.ordinal()] = new PartState();
            container.put(side, parts[side.ordinal()].part);
        }
        TileEntityNetworkQuantumNode node = new TileEntityNetworkQuantumNode();
        node.setAnchor(-1, 10, 20, 30);
        node.adoptPartContainer(container);
        Field inWorld = CableBusContainer.class.getDeclaredField("inWorld");
        inWorld.setAccessible(true);
        inWorld.setBoolean(container, true);
        AtomicInteger bridgeDestroyed = new AtomicInteger();
        IGridConnection bridge = (IGridConnection) Proxy.newProxyInstance(
            IGridConnection.class.getClassLoader(),
            new Class<?>[] { IGridConnection.class },
            (proxy, method, args) -> {
                if (method.getName()
                    .equals("destroy")) bridgeDestroyed.incrementAndGet();
                return null;
            });
        Field connection = TileEntityNetworkQuantumNode.class.getDeclaredField("connection");
        connection.setAccessible(true);
        connection.set(node, bridge);
        NBTTagCompound expectedInventory = new NBTTagCompound();
        container.writeToNBT(expectedInventory);
        Bus nativeBus = new Bus();
        assertTrue(QuantumBusReplacement.removeCore(node, nativeBus, new QuantumBusReplacement.Placement() {

            public boolean install() {
                assertTrue(node.isEmpty());
                node.invalidate();
                node.onChunkUnload();
                return true;
            }

            public void restore() {
                fail("Successful removal must not roll back");
            }
        }));
        assertEquals(1, bridgeDestroyed.get());
        assertNull(connection.get(node));
        assertSame(container, nativeBus.getCableBus());
        assertTrue(inWorld.getBoolean(container));
        NBTTagCompound actualInventory = new NBTTagCompound();
        nativeBus.getCableBus()
            .writeToNBT(actualInventory);
        assertEquals(expectedInventory, actualInventory);
        for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) {
            PartState state = parts[side.ordinal()];
            assertSame(state.part, nativeBus.getPart(side));
            assertSame(nativeBus, state.tile);
            assertSame(container, state.host);
            assertEquals(0, state.destructiveCalls.get());
        }
    }

    @Test
    public void failedCoreRemovalRestoresOriginalPartsAnchorAndProxyNbt() throws Exception {
        for (int mode = 0; mode < 3; mode++) {
            Bus seed = new Bus();
            Parts container = new LegacyParts(seed);
            seed.container = container;
            PartState state = new PartState();
            container.put(ForgeDirection.WEST, state.part);
            TileEntityNetworkQuantumNode node = new TileEntityNetworkQuantumNode();
            node.setAnchor(7, 10, 20, 30);
            node.adoptPartContainer(container);
            state.failRebind = mode == 2;
            Field pending = TileEntityNetworkQuantumNode.class.getDeclaredField("pendingProxyNBT");
            pending.setAccessible(true);
            NBTTagCompound owner = new NBTTagCompound();
            owner.setInteger("playerID", 47);
            pending.set(node, owner);
            Bus nativeBus = new Bus();
            AtomicInteger restored = new AtomicInteger();
            final int failure = mode;
            try {
                assertFalse(QuantumBusReplacement.removeCore(node, nativeBus, new QuantumBusReplacement.Placement() {

                    public boolean install() {
                        node.invalidate();
                        if (failure == 1) throw new IllegalStateException("installation");
                        return false;
                    }

                    public void restore() {
                        assertTrue(nativeBus.isEmpty());
                        nativeBus.invalidate();
                        node.validate();
                        restored.incrementAndGet();
                    }
                }));
                assertEquals(0, mode);
            } catch (IllegalStateException expected) {
                assertTrue(mode > 0);
            }
            assertEquals(1, restored.get());
            assertSame(container, node.getPartContainer());
            assertSame(state.part, node.getPart(ForgeDirection.WEST));
            assertSame(node, state.tile);
            assertSame(owner, pending.get(node));
            assertEquals(7, node.getAnchorDim());
            assertEquals(10, node.getAnchorX());
            assertEquals(20, node.getAnchorY());
            assertEquals(30, node.getAnchorZ());
            assertFalse(node.isInvalid());
            assertEquals(0, state.destructiveCalls.get());
        }
    }

    @Test
    public void emptyNodeDoesNotEnterTheNativeContainerTransfer() {
        TileEntityNetworkQuantumNode node = new TileEntityNetworkQuantumNode();
        assertFalse(QuantumBusReplacement.removeCore(node, new Bus(), new QuantumBusReplacement.Placement() {

            public boolean install() {
                fail();
                return false;
            }

            public void restore() {
                fail();
            }
        }));
        assertTrue(node.isEmpty());
    }

    @Test
    public void failedRemovalSavesLiveProxyOwnerBeforeInvalidation() throws Exception {
        Bus seed = new Bus();
        Parts container = new Parts(seed);
        seed.container = container;
        container.put(ForgeDirection.UP, new PartState().part);
        TileEntityNetworkQuantumNode node = new TileEntityNetworkQuantumNode();
        node.adoptPartContainer(container);
        AtomicInteger destroyed = new AtomicInteger();
        IGridNode gridNode = (IGridNode) Proxy.newProxyInstance(
            IGridNode.class.getClassLoader(),
            new Class<?>[] { IGridNode.class },
            (proxy, method, args) -> {
                if (method.getName()
                    .equals("saveToNBT")) {
                    NBTTagCompound data = new NBTTagCompound();
                    data.setInteger("playerID", 47);
                    data.setLong("securityKey", 987654321L);
                    ((NBTTagCompound) args[1]).setTag((String) args[0], data);
                } else if (method.getName()
                    .equals("destroy")) destroyed.incrementAndGet();
                return null;
            });
        AENetworkProxy proxy = new AENetworkProxy(node, "proxy", null, true);
        Field liveNode = AENetworkProxy.class.getDeclaredField("node");
        liveNode.setAccessible(true);
        liveNode.set(proxy, gridNode);
        Field gridProxy = TileEntityNetworkQuantumNode.class.getDeclaredField("gridProxy");
        gridProxy.setAccessible(true);
        gridProxy.set(node, proxy);
        assertFalse(QuantumBusReplacement.removeCore(node, new Bus(), new QuantumBusReplacement.Placement() {

            public boolean install() {
                node.invalidate();
                return false;
            }

            public void restore() {
                node.validate();
            }
        }));
        assertEquals(1, destroyed.get());
        assertNull(liveNode.get(proxy));
        Field pending = TileEntityNetworkQuantumNode.class.getDeclaredField("pendingProxyNBT");
        pending.setAccessible(true);
        NBTTagCompound restored = (NBTTagCompound) pending.get(node);
        assertEquals(
            47,
            restored.getCompoundTag("proxy")
                .getInteger("playerID"));
        assertEquals(
            987654321L,
            restored.getCompoundTag("proxy")
                .getLong("securityKey"));
        assertSame(proxy, gridProxy.get(node));
    }
}
