package com.miaokatze.gtswn.client;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import org.junit.Test;

import com.miaokatze.gtswn.common.hud.MonitorBindings;
import com.miaokatze.gtswn.common.items.PortableWirelessNetworkMonitor;
import com.miaokatze.gtswn.network.PacketMonitorCapabilities;
import com.miaokatze.gtswn.network.telemetry.MonitorCapabilities;

public class PortableMonitorOwnershipTest {

    private static final class ServerPlayer extends EntityPlayerMP {

        UUID id;

        private ServerPlayer() {
            super(null, null, null, null);
        }

        @Override
        public UUID getUniqueID() {
            return id;
        }
    }

    private static final class Player extends EntityClientPlayerMP {

        UUID id;

        private Player() {
            super(null, null, null, null, null);
        }

        @Override
        public UUID getUniqueID() {
            return id;
        }
    }

    private static Object allocate(Class<?> type) throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        Field allocator = unsafe.getDeclaredField("theUnsafe");
        allocator.setAccessible(true);
        return unsafe.getMethod("allocateInstance", Class.class)
            .invoke(allocator.get(null), type);
    }

    private static Field clientField(String name) throws Exception {
        Field field = Class.forName(MonitorCapabilities.class.getName() + "$Client")
            .getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    @Test
    public void offlineServerIdentityFixesOwnerUiWithoutRebindingOrGrantingSameNameBorrowers() throws Exception {
        Field singleton = Minecraft.class.getDeclaredField("theMinecraft");
        singleton.setAccessible(true);
        Object previous = singleton.get(null);
        Field connection = clientField("connection"), nonce = clientField("nonce"), identity = clientField("playerId"),
            bits = clientField("bits");
        Object oldConnection = connection.get(null), oldNonce = nonce.get(null), oldIdentity = identity.get(null),
            oldBits = bits.get(null);
        try {
            Minecraft mc = (Minecraft) allocate(Minecraft.class);
            singleton.set(null, mc);
            Player player = (Player) allocate(Player.class);
            mc.thePlayer = player;
            player.id = UUID.randomUUID(); // The authenticated launcher's GameProfile remains local in MC 1.7.10.
            player.inventory = new InventoryPlayer(player);
            Field sendQueue = EntityClientPlayerMP.class.getDeclaredField("sendQueue");
            sendQueue.setAccessible(true);
            sendQueue.set(player, allocate(NetHandlerPlayClient.class));
            connection.set(null, player.sendQueue);
            nonce.set(null, 42L);
            identity.set(null, null);
            UUID offlineId = UUID.nameUUIDFromBytes("OfflinePlayer:SameName".getBytes(StandardCharsets.UTF_8));
            ItemStack terminal = new ItemStack(new PortableWirelessNetworkMonitor());
            terminal.stackTagCompound = new NBTTagCompound();
            terminal.stackTagCompound.setBoolean(PortableWirelessNetworkMonitor.NBT_INITIALIZED, true);
            terminal.stackTagCompound.setString(PortableWirelessNetworkMonitor.NBT_OWNER_UUID, offlineId.toString());
            terminal.stackTagCompound.setString("OwnerName", "SameName");
            MonitorBindings.ensureId(terminal);
            player.inventory.mainInventory[0] = terminal;
            NBTTagCompound original = (NBTTagCompound) terminal.stackTagCompound.copy();

            assertFalse(PortableMonitorClientCache.canMutate(terminal)); // Reproduces the original false "borrowed"
                                                                         // state.
            MonitorCapabilities.receive(41, MonitorCapabilities.ALL, offlineId);
            assertFalse(PortableMonitorClientCache.canMutate(terminal)); // A stale nonce cannot establish identity.
            MonitorCapabilities.receive(42, MonitorCapabilities.ALL, offlineId);
            assertTrue(PortableMonitorClientCache.canMutate(terminal));
            assertEquals(original, terminal.stackTagCompound);

            terminal.stackTagCompound.setString(
                PortableWirelessNetworkMonitor.NBT_OWNER_UUID,
                UUID.randomUUID()
                    .toString());
            assertFalse(PortableMonitorClientCache.canMutate(terminal)); // Matching OwnerName grants nothing.
            terminal.stackTagCompound.setString(PortableWirelessNetworkMonitor.NBT_OWNER_UUID, offlineId.toString());
            MonitorCapabilities.receive(42, MonitorCapabilities.ALL); // A late v1 reply cannot erase known identity.
            assertTrue(PortableMonitorClientCache.canMutate(terminal));

            sendQueue.set(player, allocate(NetHandlerPlayClient.class));
            assertFalse(PortableMonitorClientCache.canMutate(terminal)); // A new connection never borrows the old
                                                                         // actor.
            MonitorCapabilities.receive(42, MonitorCapabilities.ALL, offlineId);
            assertFalse(PortableMonitorClientCache.canMutate(terminal));
            identity.set(null, null);
            connection.set(null, player.sendQueue);
            terminal.stackTagCompound.setString(PortableWirelessNetworkMonitor.NBT_OWNER_UUID, player.id.toString());
            MonitorCapabilities.receive(42, MonitorCapabilities.ALL);
            assertTrue(PortableMonitorClientCache.canMutate(terminal)); // Original behavior on legacy online servers.
        } finally {
            PortableMonitorClientCache.resetForHudSession();
            singleton.set(null, previous);
            connection.set(null, oldConnection);
            nonce.set(null, oldNonce);
            identity.set(null, oldIdentity);
            bits.set(null, oldBits);
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    public void delayedFallbackCannotDowngradePendingIdentityForTheSameNonce() throws Exception {
        Field field = MonitorCapabilities.class.getDeclaredField("PENDING");
        field.setAccessible(true);
        Map<EntityPlayerMP, PacketMonitorCapabilities> pending = (Map<EntityPlayerMP, PacketMonitorCapabilities>) field
            .get(null);
        Map<EntityPlayerMP, PacketMonitorCapabilities> previous = new HashMap<>(pending);
        try {
            pending.clear();
            ServerPlayer player = (ServerPlayer) allocate(ServerPlayer.class);
            player.id = UUID.randomUUID();
            MonitorCapabilities.enqueue(player, 42);
            assertNull(pending.get(player).playerId);
            MonitorCapabilities.enqueue(player, 42, true);
            assertEquals(player.id, pending.get(player).playerId);
            MonitorCapabilities.enqueue(player, 42);
            assertEquals(player.id, pending.get(player).playerId);
            MonitorCapabilities.enqueue(player, 43);
            assertEquals(43, pending.get(player).nonce);
            assertNull(pending.get(player).playerId);
        } finally {
            pending.clear();
            pending.putAll(previous);
        }
    }
}
