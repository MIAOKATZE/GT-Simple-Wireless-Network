package com.miaokatze.gtswn.common.baubles;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.item.ItemStack;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedPlayerList;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.management.ServerConfigurationManager;
import net.minecraft.util.IChatComponent;
import net.minecraft.world.WorldProviderSurface;
import net.minecraft.world.WorldServer;
import net.minecraft.world.storage.MapStorage;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.miaokatze.gtswn.common.device.DeviceScanManager;
import com.miaokatze.gtswn.common.items.ItemDeviceInfoTerminal;
import com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal;
import com.miaokatze.gtswn.common.items.WirelessEnergyTap;
import com.miaokatze.gtswn.network.NodeRevealRequestQueue;
import com.miaokatze.gtswn.network.PacketRequestQuantumReveal;
import com.miaokatze.gtswn.network.PacketTerminalBaubleAction;

import baubles.common.container.InventoryBaubles;
import baubles.common.lib.PlayerHandler;

/** Real wheel dispatch and scan admission with an online actor; no FML transport or game is started. */
public class TerminalWheelInstantActionTest {

    private Field singleton;
    private Object previousServer, previousExecutor;
    private boolean previousAvailable;
    private Player player;
    private Server server;
    private TestWorld world;
    private Accessories accessories;
    private CapturedExecutor executor;

    private static Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Object allocate(Class<?> type) throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        return unsafe.getMethod("allocateInstance", Class.class)
            .invoke(field(unsafe, "theUnsafe").get(null), type);
    }

    @Before
    public void setUp() throws Exception {
        for (Field candidate : MinecraftServer.class.getDeclaredFields())
            if (Modifier.isStatic(candidate.getModifiers()) && candidate.getType() == MinecraftServer.class) {
                candidate.setAccessible(true);
                singleton = candidate;
            }
        assertNotNull(singleton);
        previousServer = singleton.get(null);
        server = (Server) allocate(Server.class);
        singleton.set(null, server);
        world = (TestWorld) allocate(TestWorld.class);
        field(net.minecraft.world.World.class, "perWorldStorage").set(world, new MapStorage(null));
        field(net.minecraft.world.World.class, "provider").set(world, new WorldProviderSurface());
        field(net.minecraft.world.World.class, "loadedTileEntityList").set(world, new ArrayList<>());
        server.world = world;
        player = (Player) allocate(Player.class);
        player.id = UUID.randomUUID();
        player.worldObj = world;
        player.inventory = new InventoryPlayer(player);
        player.playerNetServerHandler = new NetHandlerPlayServer(null, new OpenNetwork(), player);
        player.inventoryContainer = new Container() {

            @Override
            public boolean canInteractWith(EntityPlayer player) {
                return true;
            }
        };
        player.openContainer = player.inventoryContainer;
        ServerConfigurationManager manager = (ServerConfigurationManager) allocate(DedicatedPlayerList.class);
        field(ServerConfigurationManager.class, "playerEntityList").set(manager, Collections.singletonList(player));
        server.func_152361_a(manager);
        previousAvailable = field(TerminalBaubles.class, "baublesAvailable").getBoolean(null);
        field(TerminalBaubles.class, "baublesAvailable").setBoolean(null, true);
        accessories = new Accessories(player);
        PlayerHandler.setPlayerBaubles(player, accessories);
        previousExecutor = field(DeviceScanManager.class, "scanExecutor").get(null);
        executor = new CapturedExecutor();
        field(DeviceScanManager.class, "scanExecutor").set(null, executor);
    }

    @After
    public void tearDown() throws Exception {
        TerminalBaubles.clear();
        NodeRevealRequestQueue.clearServerState();
        PacketRequestQuantumReveal.clearServerState();
        DeviceScanManager.clear();
        PlayerHandler.clearPlayerBaubles(player);
        field(DeviceScanManager.class, "scanExecutor").set(null, previousExecutor);
        field(TerminalBaubles.class, "baublesAvailable").setBoolean(null, previousAvailable);
        singleton.set(null, previousServer);
    }

    @Test
    public void linkAndQuantumClicksAuthorizeImmediatelyWithoutBeginOrAnyElapsedTickAndAreSingleUse() {
        ItemStack link = new ItemStack(new WirelessEnergyTap());
        ItemStack quantum = new ItemStack(new ItemNetworkQuantumTerminal());
        accessories.stackList[5] = link;
        accessories.stackList[7] = quantum;
        TerminalBaubles.service(player, PacketTerminalBaubleAction.WHEEL_INNER, 1);
        assertTrue(TerminalBaubles.consumeWheelReceipt(player, link));
        assertFalse(TerminalBaubles.consumeWheelReceipt(player, link));
        TerminalBaubles.service(player, PacketTerminalBaubleAction.WHEEL_OUTER_SECOND, 3);
        assertTrue(TerminalBaubles.consumeWheelReceipt(player, quantum));
        assertSame(link, accessories.stackList[5]);
        assertSame(quantum, accessories.stackList[7]);
        assertNull(player.getHeldItem());
    }

    @Test
    public void sourceReplacementWorldChangeAndDisconnectInvalidateQueuedClickAuthorization() throws Exception {
        ItemStack link = new ItemStack(new WirelessEnergyTap());
        accessories.stackList[5] = link;
        TerminalBaubles.service(player, PacketTerminalBaubleAction.WHEEL_INNER, 1);
        accessories.stackList[5] = link.copy();
        assertFalse(TerminalBaubles.consumeWheelReceipt(player, link));
        accessories.stackList[5] = link;
        TerminalBaubles.service(player, PacketTerminalBaubleAction.WHEEL_INNER, 1);
        player.worldObj = (TestWorld) allocate(TestWorld.class);
        assertFalse(TerminalBaubles.consumeWheelReceipt(player, link));
        player.worldObj = world;
        TerminalBaubles.service(player, PacketTerminalBaubleAction.WHEEL_INNER, 1);
        ((OpenNetwork) player.playerNetServerHandler.netManager).open = false;
        assertFalse(TerminalBaubles.consumeWheelReceipt(player, link));
    }

    @Test
    public void deviceScanClickEntersExistingScanManagerImmediatelyWithoutBeginOrReceiptAndDoesNotRestart()
        throws Exception {
        ItemStack device = new ItemStack(new ItemDeviceInfoTerminal());
        ItemDeviceInfoTerminal.getOrCreateTerminalId(device);
        accessories.stackList[2] = device;
        TerminalBaubles.service(player, PacketTerminalBaubleAction.WHEEL_OUTER_SECOND, 2);
        Map<?, ?> states = (Map<?, ?>) field(DeviceScanManager.class, "STATES").get(null);
        assertTrue(states.containsKey(player.id));
        assertEquals(1, executor.tasks.size());
        assertTrue(((Map<?, ?>) field(TerminalBaubles.class, "WHEEL_RECEIPTS").get(null)).isEmpty());
        Object running = states.get(player.id);
        TerminalBaubles.service(player, PacketTerminalBaubleAction.WHEEL_OUTER_SECOND, 2);
        assertSame(running, states.get(player.id));
        assertEquals(1, executor.tasks.size());
        assertSame(device, accessories.stackList[2]);
    }

    private static final class Player extends EntityPlayerMP {

        UUID id;

        private Player() {
            super(null, null, null, null);
        }

        @Override
        public UUID getUniqueID() {
            return id;
        }

        @Override
        public String getCommandSenderName() {
            return id.toString();
        }

        @Override
        public void addChatMessage(IChatComponent message) {}
    }

    private static final class Server extends DedicatedServer {

        WorldServer world;

        private Server() {
            super(null);
        }

        @Override
        public WorldServer worldServerForDimension(int dimension) {
            return world;
        }
    }

    private static final class TestWorld extends WorldServer {

        private TestWorld() {
            super(null, null, null, 0, null, null);
        }

        @Override
        public long getTotalWorldTime() {
            return 0;
        }
    }

    private static final class OpenNetwork extends NetworkManager {

        boolean open = true;

        OpenNetwork() {
            super(false);
        }

        @Override
        public boolean isChannelOpen() {
            return open;
        }
    }

    private static final class Accessories extends InventoryBaubles {

        Accessories(EntityPlayer player) {
            super(player);
            stackList = new ItemStack[8];
        }
    }

    private static final class CapturedExecutor extends AbstractExecutorService {

        final List<Runnable> tasks = new ArrayList<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        @Override
        public void shutdown() {}

        @Override
        public List<Runnable> shutdownNow() {
            return Collections.emptyList();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return false;
        }
    }
}
