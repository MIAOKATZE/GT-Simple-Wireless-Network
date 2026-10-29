package com.miaokatze.gtswn.network;

import static org.junit.Assert.*;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.world.WorldServer;

import org.junit.Test;

public class HeldTerminalReceiptTest {

    private static final class Player extends EntityPlayerMP {

        private Player() {
            super(null, null, null, null);
        }
    }

    private static Object allocate(Class<?> type) throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        Field field = unsafe.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return unsafe.getMethod("allocateInstance", Class.class).invoke(field.get(null), type);
    }

    private static Player player() throws Exception {
        Player player = (Player) allocate(Player.class);
        player.worldObj = (WorldServer) allocate(WorldServer.class);
        player.inventory = new InventoryPlayer(player);
        player.inventory.currentItem = 3;
        player.inventory.mainInventory[3] = new ItemStack(new Item());
        return player;
    }

    @Test
    public void rejectsReplacementStackSlotInventoryWorldDimensionAndPlayer() throws Exception {
        Player actor = player();
        HeldTerminalReceipt receipt = new HeldTerminalReceipt(actor);
        ItemStack held = actor.getHeldItem();
        assertTrue(receipt.matches(actor));
        actor.inventory.mainInventory[3] = held.copy();
        assertFalse(receipt.matches(actor));
        actor.inventory.mainInventory[3] = held;
        actor.inventory.currentItem = 4;
        actor.inventory.mainInventory[4] = held;
        assertFalse(receipt.matches(actor));
        actor.inventory.currentItem = 3;
        assertTrue(receipt.matches(actor));
        actor.dimension++;
        assertFalse(receipt.matches(actor));
        actor.dimension--;
        net.minecraft.world.World world = actor.worldObj;
        actor.worldObj = (WorldServer) allocate(WorldServer.class); // Same dimension, different world lifetime.
        assertFalse(receipt.matches(actor));
        actor.worldObj = world;
        InventoryPlayer inventory = actor.inventory;
        actor.inventory = new InventoryPlayer(actor);
        actor.inventory.currentItem = 3;
        actor.inventory.mainInventory[3] = held;
        assertFalse(receipt.matches(actor));
        actor.inventory = inventory;
        held.stackSize = 0;
        assertFalse(receipt.matches(actor));
        held.stackSize = 1;
        assertTrue(receipt.matches(actor));
        assertFalse(receipt.matches(player()));
        assertFalse(receipt.matches(null));
    }

    @Test
    public void missingWorldInventoryOrTerminalCannotBecomeAValidReceiptLater() throws Exception {
        Player actor = player();
        actor.inventory.mainInventory[3] = null;
        HeldTerminalReceipt empty = new HeldTerminalReceipt(actor);
        actor.inventory.mainInventory[3] = new ItemStack(new Item());
        assertFalse(empty.matches(actor));
        actor.inventory = null;
        assertFalse(new HeldTerminalReceipt(actor).matches(actor));
        assertFalse(new HeldTerminalReceipt(null).matches(actor));
        actor = player();
        actor.worldObj = null;
        assertFalse(new HeldTerminalReceipt(actor).matches(actor));
    }

    @Test
    public void queuedWorldMutationsCaptureTheirReceiptBeforeTheSelectedStackChanges() throws Exception {
        for (String nested : new String[] { "PacketQuantumIncorporation$Job", "PacketQuantumTerminalSwapBus$SwapJob" }) {
            Player actor = player();
            Class<?> type = Class.forName("com.miaokatze.gtswn.network." + nested);
            Constructor<?> constructor = type.getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            Object job = nested.endsWith("$Job")
                ? constructor.newInstance(actor, new PacketQuantumIncorporation(1, 2, 3, false))
                : constructor.newInstance(actor, 1, 2, 3, 1, false);
            Field field = type.getDeclaredField("receipt");
            field.setAccessible(true);
            HeldTerminalReceipt receipt = (HeldTerminalReceipt) field.get(job);
            assertTrue(receipt.matches(actor));
            actor.inventory.mainInventory[3] = actor.getHeldItem().copy();
            assertFalse(receipt.matches(actor));
        }
    }

    @Test
    public void delayedProfileImportAndRecoveryFailOnceRatherThanMutateTheReplacementTerminal() throws Exception {
        for (int action : new int[] { C2SPacketMonitorProfileAction.IMPORT_HELD, C2SPacketMonitorProfileAction.RECOVER }) {
            Player actor = player();
            ItemStack original = actor.getHeldItem();
            C2SPacketMonitorProfileAction packet = new C2SPacketMonitorProfileAction(
                7, action, 0, UUID.randomUUID(), null, false, 20);
            C2SPacketMonitorProfileAction.Operation operation = new C2SPacketMonitorProfileAction.Operation(actor, packet);
            operation.requireCurrentHeld(actor);
            actor.inventory.mainInventory[3] = original.copy();
            final int[] mutations = { 0 };
            C2SPacketMonitorProfileAction.Results results = new C2SPacketMonitorProfileAction.Results();
            C2SPacketMonitorProfileAction.Outcome denied = results.apply(packet, () -> {
                operation.requireCurrentHeld(actor);
                mutations[0]++;
            });
            assertFalse(denied.success);
            assertTrue(denied.message.contains("Held terminal changed"));
            assertEquals(0, mutations[0]);
            actor.inventory.mainInventory[3] = original;
            assertSame(denied, results.apply(packet, () -> mutations[0]++));
            assertEquals(0, mutations[0]);
        }
    }

    @Test
    public void profileMetadataActionsDoNotRequireAHeldTerminal() {
        for (int action : new int[] { C2SPacketMonitorProfileAction.LIST, C2SPacketMonitorProfileAction.SHARE,
            C2SPacketMonitorProfileAction.REVOKE, C2SPacketMonitorProfileAction.OFFLINE,
            C2SPacketMonitorProfileAction.WEB, C2SPacketMonitorProfileAction.RENAME }) {
            new C2SPacketMonitorProfileAction.Operation(null,
                new C2SPacketMonitorProfileAction(1, action, 0, null, null, false, 20)).requireCurrentHeld(null);
        }
    }
}
