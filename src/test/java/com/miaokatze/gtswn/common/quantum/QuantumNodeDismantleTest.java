package com.miaokatze.gtswn.common.quantum;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.entity.player.PlayerCapabilities;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.profiler.Profiler;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.storage.ISaveHandler;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;

import org.junit.Test;

import com.miaokatze.gtswn.common.block.BlockNetworkQuantumNode;
import com.miaokatze.gtswn.common.tile.TileEntityNetworkQuantumNode;

import appeng.api.implementations.items.IAEWrench;
import appeng.api.networking.IGridConnection;
import appeng.api.networking.IGridNode;
import appeng.parts.CableBusContainer;
import appeng.util.ReadOnlyCollection;

public class QuantumNodeDismantleTest {

    @Test
    public void ordinaryMachineHardnessAndEveryCoreDropRouteStayEmpty() {
        BlockNetworkQuantumNode block = new BlockNetworkQuantumNode();
        TileEntityNetworkQuantumNode node = new TileEntityNetworkQuantumNode();
        assertEquals(2.2F, block.getBlockHardness(null, 0, 64, 0), 0.0F);
        assertEquals("wrench", block.getHarvestTool(0));
        assertEquals(0, block.getHarvestLevel(0));
        assertTrue(
            block.getDrops(null, 0, 64, 0, 0, 10)
                .isEmpty());
        assertFalse(block.canSilkHarvest(null, null, 0, 64, 0, 0));
        assertNull(node.getWrenchDrop(null));
        assertEquals(1.0F, node.getWrenchDropRate(), 0.0F);
        assertFalse(node.wrenchCanSetFacing(null, 0));
    }

    @Test
    public void shiftWrenchEventConsumesTheWholeNodeGestureBeforePartPlacement() throws Exception {
        FixtureWorld world = world();
        FixturePlayer player = player(world);
        PlayerInteractEvent event = new CancelableInteractEvent(
            player,
            PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK,
            0,
            64,
            0,
            1,
            world);
        new QuantumControllerEventHandler().onNodeWrenchInteract(event);
        assertTrue(event.isCanceled());
        assertEquals(1, world.block.removals);
        assertFalse(world.block.willHarvest);
    }

    @Test
    public void blockActivationSharesTheSameWrenchRemovalAndClientDoesNotMutate() throws Exception {
        FixtureWorld world = world();
        FixturePlayer player = player(world);
        world.isRemote = true;
        assertTrue(world.block.onBlockActivated(world, 0, 64, 0, player, 1, .5F, .5F, .5F));
        assertEquals(0, world.block.removals);
        assertEquals(0, world.block.requests);
        world.isRemote = false;
        assertTrue(world.block.onBlockActivated(world, 0, 64, 0, player, 1, .5F, .5F, .5F));
        assertEquals(1, world.block.removals);
        assertEquals(0, world.block.requests);
    }

    @Test
    public void canceledClientInputForwardsOneRequestAndRepeatedDeliveryCannotDoubleSend() throws Exception {
        FixtureWorld world = world();
        world.isRemote = true;
        FixturePlayer player = player(world);
        PlayerInteractEvent event = new CancelableInteractEvent(
            player,
            PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK,
            0,
            64,
            0,
            1,
            world);
        QuantumControllerEventHandler handler = new QuantumControllerEventHandler();
        handler.onNodeWrenchInteract(event);
        handler.onNodeWrenchInteract(event);
        assertTrue(event.isCanceled());
        assertEquals(1, world.block.requests);
        assertEquals(0, world.block.removals);
    }

    @Test
    public void missingGestureOrUnusableWrenchCannotDismantle() throws Exception {
        FixtureWorld world = world();
        FixturePlayer player = player(world);
        player.sneaking = false;
        assertFalse(world.block.tryWrenchDismantle(world, 0, 64, 0, player, 1));
        player.sneaking = true;
        player.held = null;
        assertFalse(world.block.tryWrenchDismantle(world, 0, 64, 0, player, 1));
        player.held = new ItemStack(new Wrench(false));
        assertFalse(world.block.tryWrenchDismantle(world, 0, 64, 0, player, 1));
        assertEquals(0, world.block.removals);
    }

    @Test
    public void nativeEditChecksAndCanceledBreakEventsProtectBothWrenchEntrypoints() throws Exception {
        FixtureWorld world = world();
        FixturePlayer player = player(world);
        world.allowed = false;
        assertFalse(world.node.wrenchCanRemove(player));
        assertTrue(world.block.tryWrenchDismantle(world, 0, 64, 0, player, 1));
        world.allowed = true;
        player.allowed = false;
        assertFalse(world.node.wrenchCanRemove(player));
        assertTrue(world.block.tryWrenchDismantle(world, 0, 64, 0, player, 1));
        player.allowed = true;
        assertTrue("GT normal mining and IC2 removal do not require shift", world.node.wrenchCanRemove(player));
        world.block.breakAllowed = false;
        assertTrue(world.block.tryWrenchDismantle(world, 0, 64, 0, player, 1));
        assertEquals(1, world.block.breakChecks);
        assertEquals(0, world.block.removals);
    }

    @Test
    public void removalAndUnloadReclaimBridgeAndPartNodesWithoutErasingAnchor() throws Exception {
        for (boolean unload : new boolean[] { false, true }) {
            TileEntityNetworkQuantumNode node = new TileEntityNetworkQuantumNode();
            AtomicInteger partCleanup = new AtomicInteger(), bridgeCleanup = new AtomicInteger();
            set(node, "partContainer", new CableBusContainer(node) {

                @Override
                public void removeFromWorld() {
                    assertEquals(
                        "Parts must be isolated from the remote bridge before their removal",
                        1,
                        bridgeCleanup.get());
                    partCleanup.incrementAndGet();
                }
            });
            set(node, "anchorDim", 7);
            set(node, "anchorX", 19);
            ArrayList<IGridConnection> edges = new ArrayList<>();
            IGridNode endpoint = (IGridNode) Proxy.newProxyInstance(
                IGridNode.class.getClassLoader(),
                new Class<?>[] { IGridNode.class },
                (proxy, method, args) -> {
                    if (method.getName()
                        .equals("getConnections")) return new ReadOnlyCollection<>(edges);
                    return null;
                });
            IGridConnection connection = (IGridConnection) Proxy.newProxyInstance(
                IGridConnection.class.getClassLoader(),
                new Class<?>[] { IGridConnection.class },
                (proxy, method, args) -> {
                    if (method.getName()
                        .equals("equals")) return proxy == args[0];
                    if (method.getName()
                        .equals("a")) return endpoint;
                    if (method.getName()
                        .equals("destroy")) bridgeCleanup.incrementAndGet();
                    return null;
                });
            edges.add(connection);
            set(node, "connection", connection);
            if (unload) node.onChunkUnload();
            else node.invalidate();
            assertEquals(1, partCleanup.get());
            assertEquals(1, bridgeCleanup.get());
            assertTrue(node.hasAnchor());
            node.invalidate();
            assertEquals("Repeated removal cannot destroy the same bridge twice", 1, bridgeCleanup.get());
        }
    }

    private static class Wrench extends Item implements IAEWrench {

        private final boolean usable;

        Wrench(boolean usable) {
            this.usable = usable;
        }

        @Override
        public boolean canWrench(ItemStack stack, EntityPlayer player, int x, int y, int z) {
            return usable;
        }
    }

    private static class NodeBlock extends BlockNetworkQuantumNode {

        int removals;
        int requests;
        int breakChecks;
        boolean breakAllowed = true;
        boolean willHarvest;

        @Override
        protected boolean isWrenchBreakAllowed(World world, int x, int y, int z, EntityPlayer player) {
            // Native BreakEvent construction/registration requires Forge's Launch/ASM bootstrap.
            // The dedicated probe covers the real bus; this seam verifies removal honors its veto.
            breakChecks++;
            return breakAllowed;
        }

        @Override
        protected boolean isUsableWrench(EntityPlayer player, int x, int y, int z) {
            // AE2 Platform initializes a Launch-only integration registry. These unit tests
            // exercise gesture dispatch; the dedicated probe tests Platform with real AE/GT tools.
            ItemStack stack = player.getHeldItem();
            return stack != null && stack.getItem() instanceof IAEWrench
                && ((IAEWrench) stack.getItem()).canWrench(stack, player, x, y, z);
        }

        @Override
        protected void requestWrenchDismantle(EntityPlayer player, int x, int y, int z, int side) {
            requests++;
        }

        @Override
        public boolean removedByPlayer(World world, EntityPlayer player, int x, int y, int z, boolean harvest) {
            removals++;
            willHarvest = harvest;
            return true;
        }
    }

    private static class CancelableInteractEvent extends PlayerInteractEvent {

        CancelableInteractEvent(EntityPlayer player, Action action, int x, int y, int z, int face, World world) {
            super(player, action, x, y, z, face, world);
        }

        @Override
        public boolean isCancelable() {
            // Forge's runtime ASM normally supplies this override from @Cancelable.
            return true;
        }
    }

    private static FixtureWorld world() throws Exception {
        FixtureWorld world = (FixtureWorld) allocate(FixtureWorld.class);
        world.allowed = true;
        world.block = new NodeBlock();
        world.node = new TileEntityNetworkQuantumNode();
        world.node.yCoord = 64;
        world.node.setWorldObj(world);
        return world;
    }

    private static FixturePlayer player(FixtureWorld world) throws Exception {
        FixturePlayer player = (FixturePlayer) allocate(FixturePlayer.class);
        player.worldObj = world;
        player.capabilities = new PlayerCapabilities();
        player.inventory = new InventoryPlayer(player);
        player.allowed = true;
        player.sneaking = true;
        player.held = new ItemStack(new Wrench(true));
        return player;
    }

    private static void set(Object owner, String name, Object value) throws Exception {
        Field field = TileEntityNetworkQuantumNode.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(owner, value);
    }

    private static Object allocate(Class<?> type) throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        Field singleton = unsafe.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        return unsafe.getMethod("allocateInstance", Class.class)
            .invoke(singleton.get(null), type);
    }

    private static class FixturePlayer extends EntityPlayerMP {

        boolean allowed;
        boolean sneaking;
        ItemStack held;

        private FixturePlayer() {
            super(null, null, null, null);
        }

        @Override
        public ItemStack getHeldItem() {
            return held;
        }

        @Override
        public boolean isSneaking() {
            return sneaking;
        }

        @Override
        public boolean canPlayerEdit(int x, int y, int z, int side, ItemStack stack) {
            return allowed;
        }

        @Override
        public void addChatMessage(net.minecraft.util.IChatComponent message) {}

        @Override
        public boolean canCommandSenderUseCommand(int level, String command) {
            return false;
        }

        @Override
        public net.minecraft.util.ChunkCoordinates getPlayerCoordinates() {
            return new net.minecraft.util.ChunkCoordinates(0, 64, 0);
        }
    }

    private static class FixtureWorld extends World {

        NodeBlock block;
        TileEntityNetworkQuantumNode node;
        boolean allowed;

        private FixtureWorld() {
            super((ISaveHandler) null, "fixture", (WorldProvider) null, (WorldSettings) null, (Profiler) null);
        }

        @Override
        public Block getBlock(int x, int y, int z) {
            return block;
        }

        @Override
        public int getBlockMetadata(int x, int y, int z) {
            return 0;
        }

        @Override
        public TileEntity getTileEntity(int x, int y, int z) {
            return node;
        }

        @Override
        public boolean canMineBlock(EntityPlayer player, int x, int y, int z) {
            return allowed;
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
}
