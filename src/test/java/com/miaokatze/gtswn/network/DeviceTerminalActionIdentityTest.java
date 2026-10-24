package com.miaokatze.gtswn.network;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import org.junit.Test;

import com.miaokatze.gtswn.common.items.ItemDeviceInfoTerminal;

public class DeviceTerminalActionIdentityTest {

    private static final class Player extends EntityPlayerMP {

        UUID id;
        ItemStack held;

        private Player() {
            super(null, null, null, null);
        }

        @Override
        public UUID getUniqueID() {
            return id;
        }

        @Override
        public ItemStack getHeldItem() {
            return held;
        }
    }

    private static Player player() throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        Field field = unsafe.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        Player player = (Player) unsafe.getMethod("allocateInstance", Class.class)
            .invoke(field.get(null), Player.class);
        player.id = UUID.randomUUID();
        player.inventory = new InventoryPlayer(player);
        return player;
    }

    private static ItemStack terminal(UUID id) {
        ItemStack stack = new ItemStack(new ItemDeviceInfoTerminal());
        stack.stackTagCompound = new NBTTagCompound();
        stack.stackTagCompound.setString(ItemDeviceInfoTerminal.NBT_UUID, id.toString());
        return stack;
    }

    @Test
    public void anOpenedInventoryTerminalNeverFallsBackToAnotherHeldTerminal() throws Exception {
        Player player = player();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        ItemStack first = terminal(a), opened = terminal(b);
        player.held = first;
        player.inventory.mainInventory[0] = first;
        player.inventory.mainInventory[12] = opened;
        Method find = DeviceTerminalActionQueue.class
            .getDeclaredMethod("findTerminalStack", EntityPlayerMP.class, UUID.class);
        find.setAccessible(true);
        assertSame(opened, find.invoke(null, player, b));
        player.inventory.mainInventory[12] = null;
        player.inventory.mainInventory[30] = opened;
        assertSame(opened, find.invoke(null, player, b));
        assertNull(find.invoke(null, player, null));
        player.inventory.mainInventory[30] = null;
        assertNull(find.invoke(null, player, b));
        assertSame(first, find.invoke(null, player, a));
    }
}
