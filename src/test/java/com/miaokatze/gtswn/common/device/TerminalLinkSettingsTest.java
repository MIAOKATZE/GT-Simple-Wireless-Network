package com.miaokatze.gtswn.common.device;

import static org.junit.Assert.*;

import java.lang.reflect.Field;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.IChatComponent;

import org.junit.Test;

import com.miaokatze.gtswn.common.items.ItemDeviceInfoTerminal;
import com.miaokatze.gtswn.common.items.WirelessEnergyTap;

public class TerminalLinkSettingsTest {

    @Test
    public void preferenceIsDefaultOffAndSurvivesTerminalChangesAndDeathCopy() throws Exception {
        Player player = player();
        assertFalse(TerminalLinkSettings.autoEnabled(player));
        NBTTagCompound persisted = new NBTTagCompound();
        persisted.setInteger("GTSWN_Supply_ANCHOR", 123);
        player.root.setTag(EntityPlayer.PERSISTED_NBT_TAG, persisted);
        TerminalLinkSettings.setAutoEnabled(player, true);
        player.inventory.mainInventory[0] = new ItemStack(new WirelessEnergyTap());
        assertNotNull(TerminalLinkSettings.linkTerminal(player));
        player.inventory.mainInventory[0] = null;
        assertTrue(TerminalLinkSettings.autoEnabled(player));
        Player respawn = player();
        respawn.root = (NBTTagCompound) player.root.copy();
        assertTrue(TerminalLinkSettings.autoEnabled(respawn));
        assertEquals(
            123,
            respawn.root.getCompoundTag(EntityPlayer.PERSISTED_NBT_TAG)
                .getInteger("GTSWN_Supply_ANCHOR"));
    }

    @Test
    public void legacyHintKeysDoNotSuppressTheIndependentCombinationHint() throws Exception {
        Player player = player();
        NBTTagCompound persisted = new NBTTagCompound();
        persisted.setBoolean("GTSWNTerminalAcquisitionHint", true);
        persisted.setBoolean("GTSWNTerminalHint2", true);
        player.root.setTag(EntityPlayer.PERSISTED_NBT_TAG, persisted);
        player.inventory.mainInventory[5] = new ItemStack(new ItemDeviceInfoTerminal());
        player.inventory.mainInventory[12] = new ItemStack(new WirelessEnergyTap());
        TerminalLinkSettings.observeCombinedHint(player);
        assertEquals(1, player.messages);
        TerminalLinkSettings.observeCombinedHint(player);
        assertEquals(1, player.messages);
        Player respawn = player();
        respawn.root = (NBTTagCompound) player.root.copy();
        respawn.inventory.mainInventory = player.inventory.mainInventory.clone();
        TerminalLinkSettings.observeCombinedHint(respawn);
        assertEquals(0, respawn.messages);
    }

    private static Player player() throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        Field field = unsafe.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        Player player = (Player) unsafe.getMethod("allocateInstance", Class.class)
            .invoke(field.get(null), Player.class);
        player.root = new NBTTagCompound();
        player.inventory = new InventoryPlayer(player);
        return player;
    }

    private static final class Player extends EntityPlayerMP {

        NBTTagCompound root;
        int messages;

        private Player() {
            super(null, null, null, null);
        }

        @Override
        public NBTTagCompound getEntityData() {
            return root;
        }

        @Override
        public void addChatMessage(IChatComponent message) {
            messages++;
        }
    }
}
