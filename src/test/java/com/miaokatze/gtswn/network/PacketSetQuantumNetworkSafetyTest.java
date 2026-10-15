package com.miaokatze.gtswn.network;

import static org.junit.Assert.*;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import org.junit.Test;

import com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal;
import com.miaokatze.gtswn.common.quantum.QuantumNetworkData;
import com.miaokatze.gtswn.common.quantum.QuantumTerminalAccess;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public class PacketSetQuantumNetworkSafetyTest {

    private static class FixturePlayer extends EntityPlayerMP {

        UUID id;

        private FixturePlayer() {
            super(null, null, null, null);
        }

        @Override
        public UUID getUniqueID() {
            return id;
        }
    }

    private static Object allocate(Class<?> type) throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        Field field = unsafe.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return unsafe.getMethod("allocateInstance", Class.class)
            .invoke(field.get(null), type);
    }

    private static EntityPlayerMP player() throws Exception {
        FixturePlayer player = (FixturePlayer) allocate(FixturePlayer.class);
        player.id = UUID.randomUUID();
        player.inventory = new InventoryPlayer(player);
        player.inventory.currentItem = 3;
        return player;
    }

    @Test
    public void actorTokensStaySeparateForSharedAnchorAndRotateOnStackSwitchAndRelogin() throws Exception {
        PacketSetQuantumNetworkSafety.clearContexts();
        try {
            FixturePlayer owner = (FixturePlayer) player(), stranger = (FixturePlayer) player();
            ItemStack ownerStack = terminal(), strangerStack = terminal();
            QuantumNetworkData shared = new QuantumNetworkData();
            shared.online = true;
            shared.networkSafetyIdentity = "controller-A";
            long ownerToken = PacketSetQuantumNetworkSafety.issueToken(owner, ownerStack, shared, true);
            assertTrue(ownerToken != 0L);
            assertEquals(ownerToken, PacketSetQuantumNetworkSafety.issueToken(owner, ownerStack, shared, true));
            assertEquals(0L, PacketSetQuantumNetworkSafety.issueToken(stranger, strangerStack, shared, false));
            assertFalse(shared.canChangeNetworkSafety);
            assertEquals(0L, shared.networkSafetyRequestToken);
            long switchedToken = PacketSetQuantumNetworkSafety.issueToken(owner, ownerStack.copy(), shared, true);
            assertTrue(ownerToken != switchedToken);
            FixturePlayer reconnected = (FixturePlayer) player();
            reconnected.id = owner.id;
            long reconnectedToken = PacketSetQuantumNetworkSafety.issueToken(reconnected, ownerStack, shared, true);
            assertTrue(switchedToken != reconnectedToken);
            assertEquals(0L, PacketSetQuantumNetworkSafety.issueToken(owner, ownerStack, shared, false));
        } finally {
            PacketSetQuantumNetworkSafety.clearContexts();
        }
    }

    private static ItemStack terminal() {
        ItemStack stack = new ItemStack(new ItemNetworkQuantumTerminal());
        NBTTagCompound tag = new NBTTagCompound();
        tag.setByte(ItemNetworkQuantumTerminal.NBT_BOUND, (byte) 1);
        tag.setInteger(ItemNetworkQuantumTerminal.NBT_ANCHOR_DIM, -1);
        tag.setInteger(ItemNetworkQuantumTerminal.NBT_ANCHOR_X, 11);
        tag.setInteger(ItemNetworkQuantumTerminal.NBT_ANCHOR_Y, 64);
        tag.setInteger(ItemNetworkQuantumTerminal.NBT_ANCHOR_Z, -23);
        tag.setString(QuantumTerminalAccess.NBT_ENCRYPTION_KEY, "real-native-key");
        stack.setTagCompound(tag);
        return stack;
    }

    @Test
    public void intentRoundTripsBothModesAndRejectsMalformedSlotAndMode() throws Exception {
        Field valid = PacketSetQuantumNetworkSafety.class.getDeclaredField("valid");
        valid.setAccessible(true);
        QuantumNetworkData data = new QuantumNetworkData();
        data.anchorDim = -1;
        data.anchorX = 11;
        data.anchorY = 64;
        data.anchorZ = -23;
        data.networkSafetyIdentity = "controller-generation";
        data.networkSafetyRequestToken = 913L;
        for (boolean enabled : new boolean[] { false, true }) {
            ByteBuf input = Unpooled.buffer(), output = Unpooled.buffer();
            try {
                new PacketSetQuantumNetworkSafety(3, data, enabled).toBytes(input);
                PacketSetQuantumNetworkSafety decoded = new PacketSetQuantumNetworkSafety();
                decoded.fromBytes(input);
                assertTrue(valid.getBoolean(decoded));
                decoded.toBytes(output);
                assertEquals(3, output.readUnsignedByte());
                assertEquals(-1, output.readInt());
                assertEquals(11, output.readInt());
                assertEquals(64, output.readInt());
                assertEquals(-23, output.readInt());
                assertEquals("controller-generation", cpw.mods.fml.common.network.ByteBufUtils.readUTF8String(output));
                assertEquals(913L, output.readLong());
                assertEquals(enabled, output.readBoolean());
                input.setIndex(0, input.writerIndex());
                input.setByte(0, 9);
                decoded.fromBytes(input);
                assertFalse(valid.getBoolean(decoded));
                input.setIndex(0, input.writerIndex());
                input.setByte(0, 3);
                input.setByte(input.writerIndex() - 1, 2);
                decoded.fromBytes(input);
                assertFalse(valid.getBoolean(decoded));
                input.setIndex(0, input.writerIndex() - 1);
                decoded.fromBytes(input);
                assertFalse(valid.getBoolean(decoded));
            } finally {
                input.release();
                output.release();
            }
        }
    }

    @Test
    public void serverContextRejectsCopiedOrSwitchedTerminalActorAnchorKeyGenerationAndExpiry() throws Exception {
        EntityPlayerMP player = player();
        ItemStack held = terminal();
        Class<?> type = Class.forName(PacketSetQuantumNetworkSafety.class.getName() + "$Context");
        Constructor<?> constructor = type
            .getDeclaredConstructor(EntityPlayerMP.class, ItemStack.class, String.class, long.class);
        constructor.setAccessible(true);
        Object context = constructor.newInstance(player, held, "controller-A", 85L);
        Method matches = type.getDeclaredMethod("matches", EntityPlayerMP.class, ItemStack.class, String.class);
        matches.setAccessible(true);
        assertTrue((Boolean) matches.invoke(context, player, held, "controller-A"));
        assertFalse((Boolean) matches.invoke(context, player, held.copy(), "controller-A"));
        assertFalse((Boolean) matches.invoke(context, player(), held, "controller-A"));
        assertFalse((Boolean) matches.invoke(context, player, held, "controller-B"));
        player.inventory.currentItem = 4;
        assertFalse((Boolean) matches.invoke(context, player, held, "controller-A"));
        player.inventory.currentItem = 3;
        held.getTagCompound()
            .setInteger(ItemNetworkQuantumTerminal.NBT_ANCHOR_X, 12);
        assertFalse((Boolean) matches.invoke(context, player, held, "controller-A"));
        held.getTagCompound()
            .setInteger(ItemNetworkQuantumTerminal.NBT_ANCHOR_X, 11);
        held.getTagCompound()
            .setString(QuantumTerminalAccess.NBT_ENCRYPTION_KEY, "changed-key");
        assertFalse((Boolean) matches.invoke(context, player, held, "controller-A"));
        held.getTagCompound()
            .setString(QuantumTerminalAccess.NBT_ENCRYPTION_KEY, "real-native-key");
        assertTrue((Boolean) matches.invoke(context, player, held, "controller-A"));
        Field lastSeen = type.getDeclaredField("lastSeenNanos");
        lastSeen.setAccessible(true);
        lastSeen.setLong(context, System.nanoTime() - 31_000_000_000L);
        assertFalse((Boolean) matches.invoke(context, player, held, "controller-A"));
    }
}
