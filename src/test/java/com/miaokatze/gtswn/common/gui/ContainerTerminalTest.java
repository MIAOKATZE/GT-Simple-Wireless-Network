package com.miaokatze.gtswn.common.gui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.util.List;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

import org.junit.Test;

/** Exercises the real Container slotClick/shift dispatch without booting a Minecraft server. */
public class ContainerTerminalTest {

    private final Item supply = new Item().setMaxStackSize(64);
    private final Item other = new Item().setMaxStackSize(64);

    private static class FixturePlayer extends EntityPlayerMP {

        private ContainerTerminal receiving;
        private int replies;

        private FixturePlayer() {
            super(null, null, null, null);
        }

        @Override
        public void sendContainerAndContentsToPlayer(Container container, List<ItemStack> stacks) {
            replies++;
            if (receiving == null) return;
            for (int i = 0; i < stacks.size(); i++) {
                ItemStack stack = stacks.get(i);
                assertTrue(stack == null || stack.stackSize <= 64);
                receiving.getSlot(i)
                    .putStack(stack == null ? null : stack.copy());
            }
            EntityPlayer client = ((InventoryPlayer) receiving.getSlot(1).inventory).player;
            ItemStack cursor = inventory.getItemStack();
            client.inventory.setItemStack(cursor == null ? null : cursor.copy());
        }

        @Override
        public void sendProgressBarUpdate(Container container, int id, int count) {
            if (receiving != null) receiving.updateProgressBar(id, count);
        }
    }

    private static Object allocate(Class<?> type) throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field singleton = unsafeClass.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        return unsafeClass.getMethod("allocateInstance", Class.class)
            .invoke(singleton.get(null), type);
    }

    private ContainerTerminal fixture(boolean remote, int count) throws Exception {
        // Skip constructors that require Forge's running world, network handler and mod registry.
        EntityPlayer player = (EntityPlayer) allocate(FixturePlayer.class);
        World world = (World) allocate(WorldServer.class);
        world.isRemote = remote;
        player.worldObj = world;
        player.inventory = new InventoryPlayer(player);
        player.inventory.currentItem = 8;
        player.inventory.setInventorySlotContents(8, new ItemStack(other));
        TerminalSupplyStore.set(player, TerminalSupplyStore.Kind.ANCHOR, count);
        ContainerTerminal container = new ContainerTerminal(player, true, new ItemStack(supply)) {

            @Override
            public boolean canInteractWith(EntityPlayer candidate) {
                return candidate == player;
            }

        };
        player.openContainer = container;
        container.updateProgressBar(0, count);
        return container;
    }

    private EntityPlayer owner(ContainerTerminal container) {
        return ((InventoryPlayer) container.getSlot(1).inventory).player;
    }

    private void clickBoth(ContainerTerminal client, ContainerTerminal server, int slot, int button, int mode) {
        ItemStack predicted = client.slotClick(slot, button, mode, owner(client));
        ItemStack confirmed = server.slotClick(slot, button, mode, owner(server));
        assertTrue(
            "C0E pre-click stack matches the server confirmation",
            ItemStack.areItemStacksEqual(predicted, confirmed));
        assertTrue(
            ItemStack
                .areItemStacksEqual(owner(client).inventory.getItemStack(), owner(server).inventory.getItemStack()));
        assertEquals(client.supplyCount, server.supplyCount);
        assertEquals(server.supplyCount, TerminalSupplyStore.count(owner(server), server.kind));
        for (int i = 0; i < client.inventorySlots.size(); i++) assertTrue(
            ItemStack.areItemStacksEqual(
                client.getSlot(i)
                    .getStack(),
                server.getSlot(i)
                    .getStack()));
    }

    @Test
    public void ordinaryDepositWithdrawAndRightClickHaveMatchingCursorAndConfirmation() throws Exception {
        ContainerTerminal client = fixture(true, 0);
        ContainerTerminal server = fixture(false, 0);
        owner(client).inventory.setItemStack(new ItemStack(supply, 64));
        owner(server).inventory.setItemStack(new ItemStack(supply, 64));
        clickBoth(client, server, 0, 0, 0);
        assertNull(owner(server).inventory.getItemStack());
        assertEquals(
            64,
            server.getSlot(0)
                .getStack().stackSize);
        clickBoth(client, server, 0, 0, 0);
        assertNull(
            server.getSlot(0)
                .getStack());
        assertEquals(64, owner(server).inventory.getItemStack().stackSize);
        clickBoth(client, server, 0, 1, 0);
        assertEquals(1, server.supplyCount);
        assertEquals(63, owner(server).inventory.getItemStack().stackSize);
        clickBoth(client, server, 0, 0, 0);
        clickBoth(client, server, 0, 1, 0);
        assertEquals(1, owner(server).inventory.getItemStack().stackSize);
        assertEquals(63, server.supplyCount);
    }

    @Test
    public void shiftIsOneBatchAndFullBackpackIsLossless() throws Exception {
        ContainerTerminal client = fixture(true, 640);
        ContainerTerminal server = fixture(false, 640);
        clickBoth(client, server, 0, 0, 1);
        assertEquals(576, server.supplyCount);
        assertEquals(64, owner(server).inventory.getStackInSlot(0).stackSize);
        clickBoth(client, server, 28, 0, 1);
        assertEquals(640, server.supplyCount);
        for (int i = 0; i < 36; i++) {
            owner(client).inventory.setInventorySlotContents(i, new ItemStack(other, 64));
            owner(server).inventory.setInventorySlotContents(i, new ItemStack(other, 64));
        }
        clickBoth(client, server, 0, 0, 1);
        assertEquals(640, server.supplyCount);
        owner(client).inventory.setInventorySlotContents(3, new ItemStack(supply, 62));
        owner(server).inventory.setInventorySlotContents(3, new ItemStack(supply, 62));
        clickBoth(client, server, 0, 0, 1);
        assertEquals(638, server.supplyCount);
        assertEquals(64, owner(server).inventory.getStackInSlot(3).stackSize);
    }

    @Test
    public void totalCountStaysIntegerAndNativeSlotStaysBounded() throws Exception {
        ContainerTerminal client = fixture(true, 640);
        assertEquals(640, client.supplyCount);
        assertEquals(
            64,
            client.getSlot(0)
                .getStack().stackSize);
        assertTrue(
            client.getSlot(0)
                .isItemValid(new ItemStack(supply)));
        assertTrue(
            client.getSlot(0)
                .canTakeStack(owner(client)));
        assertFalse(client.canDragIntoSlot(client.getSlot(0)));
        assertFalse(client.func_94530_a(new ItemStack(supply), client.getSlot(0)));
        client.updateProgressBar(0, 0);
        assertNull(
            client.getSlot(0)
                .getStack());
    }

    @Test
    public void authoritativeRepliesRepairStaleSharedCountAndCursorEvenWhenShiftMovesNothing() throws Exception {
        ContainerTerminal client = fixture(true, 64);
        ContainerTerminal server = fixture(false, 64);
        FixturePlayer sender = (FixturePlayer) owner(server);
        sender.receiving = client;
        TerminalSupplyStore.set(sender, server.kind, 1);
        client.slotClick(0, 0, 1, owner(client));
        server.slotClick(0, 0, 1, sender);
        assertEquals(1, owner(client).inventory.getStackInSlot(0).stackSize);
        assertEquals(0, client.supplyCount);
        assertNull(
            client.getSlot(0)
                .getStack());
        owner(client).inventory.setItemStack(new ItemStack(supply, 64));
        server.slotClick(0, 0, 1, sender);
        assertNull(owner(client).inventory.getItemStack());
        assertEquals(2, sender.replies);
    }

    @Test
    public void rejectedShiftRepairsSourceThatChangedOnServer() throws Exception {
        ContainerTerminal client = fixture(true, 0);
        ContainerTerminal server = fixture(false, 0);
        FixturePlayer sender = (FixturePlayer) owner(server);
        sender.receiving = client;
        owner(client).inventory.setInventorySlotContents(9, new ItemStack(supply, 64));
        sender.inventory.setInventorySlotContents(9, new ItemStack(other, 12));
        assertNull(client.slotClick(1, 0, 1, owner(client)));
        assertEquals(64, client.supplyCount);
        assertNull(server.slotClick(1, 0, 1, sender));
        assertEquals(0, client.supplyCount);
        assertEquals(
            12,
            client.getSlot(1)
                .getStack().stackSize);
        assertTrue(
            client.getSlot(1)
                .getStack()
                .getItem() == other);
        assertNull(
            client.getSlot(0)
                .getStack());
        assertEquals(1, sender.replies);
    }

    @Test
    public void ordinaryWithdrawalUsesLiveServerCountAndRepairsPredictedCursor() throws Exception {
        ContainerTerminal client = fixture(true, 64);
        ContainerTerminal server = fixture(false, 64);
        FixturePlayer sender = (FixturePlayer) owner(server);
        sender.receiving = client;
        TerminalSupplyStore.set(sender, server.kind, 1);
        ItemStack predicted = client.slotClick(0, 0, 0, owner(client));
        assertEquals(64, owner(client).inventory.getItemStack().stackSize);
        ItemStack confirmed = server.slotClick(0, 0, 0, sender);
        assertFalse(ItemStack.areItemStacksEqual(predicted, confirmed));
        assertEquals(1, owner(client).inventory.getItemStack().stackSize);
        assertEquals(0, client.supplyCount);
        assertNull(
            client.getSlot(0)
                .getStack());
        assertEquals(1, sender.replies);
    }
}
