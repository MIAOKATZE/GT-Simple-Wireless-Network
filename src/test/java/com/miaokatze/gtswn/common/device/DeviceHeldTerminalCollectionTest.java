package com.miaokatze.gtswn.common.device;

import static org.junit.Assert.*;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;

import net.minecraft.inventory.InventoryBasic;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.miaokatze.gtswn.common.items.ItemDeviceInfoTerminal;

public class DeviceHeldTerminalCollectionTest {

    @Before
    @After
    public void clearHolders() {
        DeviceTerminalDataStore.clearActiveTerminals();
    }

    @Test
    public void inventoryAndEveryAccessorySlotFormOneDeduplicatedHolderSet() {
        ItemStack[] main = new ItemStack[36];
        InventoryBasic baubles = new InventoryBasic("fixture", false, 20);
        ItemStack carried = terminal(), worn = terminal();
        main[35] = carried;
        baubles.setInventorySlotContents(0, carried.copy());
        baubles.setInventorySlotContents(19, worn);
        Set<UUID> ids = DeviceEventHandler.collectHeldTerminalIds(main, baubles);
        assertEquals(2, ids.size());
        assertTrue(ids.contains(ItemDeviceInfoTerminal.readTerminalId(carried)));
        assertTrue(ids.contains(ItemDeviceInfoTerminal.readTerminalId(worn)));
        assertSame(carried, main[35]);
        assertSame(worn, baubles.getStackInSlot(19));
    }

    @Test
    public void reconciliationNeverInitializesMissingOrRepairsMalformedIdentity() {
        ItemStack fresh = new ItemStack(new ItemDeviceInfoTerminal());
        ItemStack malformed = new ItemStack(new ItemDeviceInfoTerminal());
        malformed.setTagCompound(new NBTTagCompound());
        malformed.stackTagCompound.setString(ItemDeviceInfoTerminal.NBT_UUID, "not-a-uuid");
        ItemStack other = new ItemStack(new Item());
        other.setTagCompound(new NBTTagCompound());
        other.stackTagCompound.setString(
            ItemDeviceInfoTerminal.NBT_UUID,
            UUID.randomUUID()
                .toString());
        InventoryBasic baubles = new InventoryBasic("fixture", false, 20);
        baubles.setInventorySlotContents(19, malformed);
        assertTrue(
            DeviceEventHandler.collectHeldTerminalIds(new ItemStack[] { null, fresh, other }, baubles)
                .isEmpty());
        assertNull(fresh.stackTagCompound);
        assertEquals("not-a-uuid", malformed.stackTagCompound.getString(ItemDeviceInfoTerminal.NBT_UUID));
    }

    @Test
    public void absentBaublesRetainsMainInventoryBehavior() {
        ItemStack carried = terminal();
        assertEquals(
            Collections.singleton(ItemDeviceInfoTerminal.readTerminalId(carried)),
            DeviceEventHandler.collectHeldTerminalIds(new ItemStack[] { null, carried }, null));
    }

    @Test
    public void equipUnequipDropAndSharedHolderPreserveExistingReferenceCounting() {
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        ItemStack terminal = terminal();
        UUID id = ItemDeviceInfoTerminal.readTerminalId(terminal);
        ItemStack[] main = new ItemStack[36];
        InventoryBasic baubles = new InventoryBasic("fixture", false, 20);
        main[0] = terminal;
        DeviceTerminalDataStore.onPlayerLoggedIn(first, DeviceEventHandler.collectHeldTerminalIds(main, baubles));
        main[0] = null;
        baubles.setInventorySlotContents(19, terminal);
        DeviceTerminalDataStore
            .synchronizeHeldTerminals(first, DeviceEventHandler.collectHeldTerminalIds(main, baubles));
        assertTrue(DeviceTerminalDataStore.isTerminalActive(id));
        DeviceTerminalDataStore.onPlayerLoggedIn(second, DeviceEventHandler.collectHeldTerminalIds(main, baubles));
        baubles.setInventorySlotContents(19, null);
        main[35] = terminal;
        DeviceTerminalDataStore
            .synchronizeHeldTerminals(first, DeviceEventHandler.collectHeldTerminalIds(main, baubles));
        assertTrue(DeviceTerminalDataStore.isTerminalActive(id));
        main[35] = null;
        DeviceTerminalDataStore
            .synchronizeHeldTerminals(first, DeviceEventHandler.collectHeldTerminalIds(main, baubles));
        assertTrue(DeviceTerminalDataStore.isTerminalActive(id));
        DeviceTerminalDataStore.onPlayerLoggedOut(second);
        assertFalse(DeviceTerminalDataStore.isTerminalActive(id));
    }

    private static ItemStack terminal() {
        ItemStack stack = new ItemStack(new ItemDeviceInfoTerminal());
        ItemDeviceInfoTerminal.getOrCreateTerminalId(stack);
        return stack;
    }
}
