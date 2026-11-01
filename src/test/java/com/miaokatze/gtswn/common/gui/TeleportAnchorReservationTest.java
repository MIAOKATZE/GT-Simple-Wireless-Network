package com.miaokatze.gtswn.common.gui;

import static org.junit.Assert.*;

import java.lang.reflect.Field;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.miaokatze.gtswn.common.api.enums.GTSWNItemList;

public class TeleportAnchorReservationTest {

    private Item item;
    private Field stackField, unsetField;
    private Object previousStack, previousUnset;

    @Before
    public void registerFixtureReference() throws Exception {
        item = new Item().setMaxStackSize(64);
        stackField = GTSWNItemList.class.getDeclaredField("mStack");
        unsetField = GTSWNItemList.class.getDeclaredField("mHasNotBeenSet");
        stackField.setAccessible(true);
        unsetField.setAccessible(true);
        previousStack = stackField.get(GTSWNItemList.Network_Teleport_Anchor);
        previousUnset = unsetField.get(GTSWNItemList.Network_Teleport_Anchor);
        stackField.set(GTSWNItemList.Network_Teleport_Anchor, new ItemStack(item));
        unsetField.set(GTSWNItemList.Network_Teleport_Anchor, false);
    }

    @After
    public void restoreReference() throws Exception {
        stackField.set(GTSWNItemList.Network_Teleport_Anchor, previousStack);
        unsetField.set(GTSWNItemList.Network_Teleport_Anchor, previousUnset);
    }

    @Test
    public void reserveCommitAndFailedTravelReleaseSettleExactlyOnce() throws Exception {
        EntityPlayer player = player();
        TerminalSupplyStore.set(player, TerminalSupplyStore.Kind.TELEPORT_ANCHOR, 1);
        TerminalSupplyStore.set(player, TerminalSupplyStore.Kind.ANCHOR, 77);
        TerminalSupplyStore.set(player, TerminalSupplyStore.Kind.TUBE, 23);
        TerminalSupplyStore.Reservation failed = TerminalSupplyStore.reserveTeleportAnchor(player, true);
        assertEquals(0, count(player));
        failed.release();
        failed.release();
        failed.commit();
        assertEquals(1, count(player));
        TerminalSupplyStore.Reservation success = TerminalSupplyStore.reserveTeleportAnchor(player, true);
        success.commit();
        success.commit();
        success.release();
        assertEquals(0, count(player));
        assertNull(TerminalSupplyStore.reserveTeleportAnchor(player, true));
        assertEquals(77, TerminalSupplyStore.count(player, TerminalSupplyStore.Kind.ANCHOR));
        assertEquals(23, TerminalSupplyStore.count(player, TerminalSupplyStore.Kind.TUBE));
    }

    @Test
    public void backpackRefundKeepsExactSourceAndSurvivesAReplacedSlot() throws Exception {
        EntityPlayer player = player();
        player.inventory.setInventorySlotContents(12, new ItemStack(item));
        TerminalSupplyStore.Reservation failed = TerminalSupplyStore.reserveTeleportAnchor(player, false);
        assertNull(player.inventory.getStackInSlot(12));
        failed.release();
        assertEquals(1, player.inventory.getStackInSlot(12).stackSize);
        assertEquals(0, count(player));

        TerminalSupplyStore.Reservation interrupted = TerminalSupplyStore.reserveTeleportAnchor(player, false);
        ItemStack replacement = new ItemStack(new Item());
        player.inventory.setInventorySlotContents(12, replacement);
        interrupted.release();
        assertSame(replacement, player.inventory.getStackInSlot(12));
        assertEquals(1, count(player));
    }

    @Test
    public void newAnchorRequiresTheSameItemMetaAndNbtForSlotAndPayment() throws Exception {
        EntityPlayer player = player();
        ItemStack decorated = new ItemStack(item);
        decorated.stackTagCompound = new NBTTagCompound();
        decorated.stackTagCompound.setString("custom", "keep");
        player.inventory.setInventorySlotContents(0, decorated);
        player.inventory.setInventorySlotContents(1, new ItemStack(item, 1, 1));
        assertEquals(-1, TerminalSupplyStore.inventorySlot(player, TerminalSupplyStore.Kind.TELEPORT_ANCHOR));
        assertFalse(TerminalSupplyStore.available(player, TerminalSupplyStore.Kind.TELEPORT_ANCHOR));
        assertNull(TerminalSupplyStore.reserveTeleportAnchor(player, false));
        assertEquals(1, decorated.stackSize);
        assertFalse(SupplyInventoryTransfer.matches(decorated, new ItemStack(item)));
        assertTrue(
            TerminalSupplyStore.inventoryMatches(TerminalSupplyStore.Kind.ANCHOR, decorated, new ItemStack(item)));
        assertTrue(TerminalSupplyStore.inventoryMatches(TerminalSupplyStore.Kind.TUBE, decorated, new ItemStack(item)));
        player.inventory.setInventorySlotContents(35, new ItemStack(item));
        TerminalSupplyStore.Reservation accepted = TerminalSupplyStore.reserveTeleportAnchor(player, false);
        assertNotNull(accepted);
        assertNull(player.inventory.getStackInSlot(35));
        accepted.commit();
        assertEquals(1, decorated.stackSize);
    }

    private static int count(EntityPlayer player) {
        return TerminalSupplyStore.count(player, TerminalSupplyStore.Kind.TELEPORT_ANCHOR);
    }

    private static EntityPlayer player() throws Exception {
        EntityPlayer player = (EntityPlayer) allocate(EntityPlayerMP.class);
        World world = (World) allocate(WorldServer.class);
        world.isRemote = false;
        player.worldObj = world;
        player.inventory = new InventoryPlayer(player);
        return player;
    }

    private static Object allocate(Class<?> type) throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        Field field = unsafe.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return unsafe.getMethod("allocateInstance", Class.class)
            .invoke(field.get(null), type);
    }
}
