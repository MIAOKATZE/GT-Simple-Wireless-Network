package com.miaokatze.gtswn.common.baubles;

import static org.junit.Assert.*;

import java.lang.reflect.Method;

import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;

import org.junit.Test;

import com.miaokatze.gtswn.common.items.ItemDeviceInfoTerminal;

import baubles.common.container.InventoryBaubles;

public class TerminalBaubleVisualSyncTest {

    private static final class NativeInventory extends InventoryBaubles {

        int packets;
        int syncedSlot = -1;
        int dirty;
        ItemStack synced;

        NativeInventory() {
            super(null);
            stackList = new ItemStack[20];
        }

        @Override
        public void markDirty() {
            dirty++;
        }

        @Override
        public void syncSlotToClients(int slot) {
            packets++;
            syncedSlot = slot;
            synced = getStackInSlot(slot).copy();
        }
    }

    private static boolean sync(NativeInventory inventory, ItemStack changed) throws Exception {
        Class<?> integration = Class.forName(TerminalBaubles.class.getName() + "$Integration");
        Method sync = integration.getDeclaredMethod("syncChangedStack", IInventory.class, ItemStack.class);
        sync.setAccessible(true);
        return (boolean) sync.invoke(null, inventory, changed);
    }

    private static ItemStack device() {
        ItemStack stack = new ItemStack(new ItemDeviceInfoTerminal());
        ItemDeviceInfoTerminal.getOrCreateTerminalId(stack);
        stack.stackTagCompound.setString("unrelated", "preserved");
        return stack;
    }

    @Test
    public void bothVisualTransitionsSendExactNativeSlotWithCurrentNbtWithoutReplacingStacks() throws Exception {
        NativeInventory inventory = new NativeInventory();
        ItemStack terminal = device(), sameBinding = terminal.copy();
        inventory.stackList[2] = sameBinding;
        inventory.stackList[17] = terminal;
        for (boolean enabled : new boolean[] { true, false }) {
            ItemDeviceInfoTerminal.setVisualizationEnabled(terminal, enabled);
            assertTrue(sync(inventory, terminal));
            assertEquals(17, inventory.syncedSlot);
            assertEquals(enabled, ItemDeviceInfoTerminal.isVisualizationEnabled(inventory.synced));
            assertEquals(
                ItemDeviceInfoTerminal.readTerminalId(terminal),
                ItemDeviceInfoTerminal.readTerminalId(inventory.synced));
            assertEquals("preserved", inventory.synced.stackTagCompound.getString("unrelated"));
            assertSame(terminal, inventory.stackList[17]);
            assertSame(sameBinding, inventory.stackList[2]);
            assertFalse(ItemDeviceInfoTerminal.isVisualizationEnabled(sameBinding));
        }
        assertEquals(2, inventory.packets);
        assertEquals(2, inventory.dirty);
    }

    @Test
    public void missingOrReplacedReferenceCannotSyncAnotherTerminalEvenWithEqualUuid() throws Exception {
        NativeInventory inventory = new NativeInventory();
        ItemStack removed = device(), replacement = removed.copy();
        inventory.stackList[17] = replacement;
        assertFalse(sync(inventory, removed));
        assertFalse(sync(inventory, null));
        assertEquals(0, inventory.packets);
        assertEquals(0, inventory.dirty);
        assertSame(replacement, inventory.stackList[17]);
        inventory.stackList[17] = null;
        assertFalse(sync(inventory, removed));
    }
}
