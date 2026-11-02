package com.miaokatze.gtswn.common.device;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.miaokatze.gtswn.common.gui.TerminalSupplyStore;
import com.miaokatze.gtswn.common.items.ItemDeviceInfoTerminal;

public class DeviceTerminalConfirmationTest {

    private static final UUID ID = UUID.fromString("12345678-1234-5678-1234-567812345678");

    private static final class Player extends EntityPlayerMP {

        UUID id;

        private Player() {
            super(null, null, null, null);
        }

        @Override
        public UUID getUniqueID() {
            return id;
        }
    }

    @Before
    @After
    public void clearPending() {
        DeviceTerminalTeleport.clear();
    }

    @Test
    public void cancellationConsumesTheNonceAndAReplayCannotChargeAnyAccount() throws Exception {
        Player player = player();
        player.experienceLevel = 9;
        TerminalSupplyStore.set(player, TerminalSupplyStore.Kind.TELEPORT_ANCHOR, 1);
        Object quote = quote(player, 12, false);
        quotes().put(player.id, quote);
        DeviceTerminalTeleport.confirm(player, 6, false);
        assertSame(quote, quotes().get(player.id));
        Player replacementConnection = player();
        replacementConnection.id = player.id;
        DeviceTerminalTeleport.confirm(replacementConnection, 7, false);
        assertSame(quote, quotes().get(player.id));
        DeviceTerminalTeleport.confirm(player, 7, false);
        assertFalse(quotes().containsKey(player.id));
        DeviceTerminalTeleport.confirm(player, 7, true);
        DeviceTerminalTeleport.confirm(player, 7, false);
        assertEquals(9, player.experienceLevel);
        assertEquals(1, TerminalSupplyStore.count(player, TerminalSupplyStore.Kind.TELEPORT_ANCHOR));
    }

    @Test
    public void confirmationRequiresTheSameConnectionWorldDimensionSlotAndTerminalBinding() throws Exception {
        Player player = player();
        Object quote = quote(player, 12, false);
        assertTrue(matches(quote, player, 100));
        assertTrue(matches(quote, player, 1300));
        assertFalse(matches(quote, player, 1301));
        assertFalse(matches(quote, player, 99));
        assertFalse(matches(quote, player(), 100));

        ItemStack terminal = player.inventory.mainInventory[12];
        player.inventory.mainInventory[12] = terminal.copy();
        assertFalse(matches(quote, player, 100));
        player.inventory.mainInventory[12] = terminal;
        terminal.stackTagCompound.setString(
            ItemDeviceInfoTerminal.NBT_UUID,
            UUID.randomUUID()
                .toString());
        assertFalse(matches(quote, player, 100));
        terminal.stackTagCompound.setString(ItemDeviceInfoTerminal.NBT_UUID, ID.toString());
        player.dimension = 1;
        assertFalse(matches(quote, player, 100));
        player.dimension = 0;
        World oldWorld = player.worldObj;
        player.worldObj = (World) allocate(WorldServer.class);
        assertFalse(matches(quote, player, 100));
        player.worldObj = oldWorld;
        player.inventory.mainInventory[12] = null;
        player.inventory.mainInventory[20] = terminal;
        assertFalse(matches(quote, player, 100));
    }

    @Test
    public void worldMarkerConfirmationAlsoKeepsTheSelectedHotbarSource() throws Exception {
        Player player = player();
        Object quote = quote(player, 0, true);
        assertTrue(matches(quote, player, 100));
        player.inventory.currentItem = 1;
        assertFalse(matches(quote, player, 100));
    }

    private static Object quote(Player player, int slot, boolean visual) throws Exception {
        Class<?> type = Class.forName(DeviceTerminalTeleport.class.getName() + "$Quote");
        Object quote = allocate(type);
        ItemStack terminal = new ItemStack(new ItemDeviceInfoTerminal());
        terminal.stackTagCompound = new NBTTagCompound();
        terminal.stackTagCompound.setString(ItemDeviceInfoTerminal.NBT_UUID, ID.toString());
        player.inventory.mainInventory[slot] = terminal;
        set(quote, "player", player);
        set(quote, "world", player.worldObj);
        set(quote, "dimension", player.dimension);
        set(quote, "slot", slot);
        set(quote, "terminal", terminal);
        set(quote, "terminalId", ID);
        set(quote, "visual", visual);
        set(quote, "tick", 100L);
        set(quote, "token", 7L);
        return quote;
    }

    private static boolean matches(Object quote, Player player, long tick) throws Exception {
        Method method = quote.getClass()
            .getDeclaredMethod("matches", EntityPlayerMP.class, long.class);
        method.setAccessible(true);
        return (Boolean) method.invoke(quote, player, tick);
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, Object> quotes() throws Exception {
        Field field = DeviceTerminalTeleport.class.getDeclaredField("QUOTES");
        field.setAccessible(true);
        return (Map<UUID, Object>) field.get(null);
    }

    private static Player player() throws Exception {
        Player player = (Player) allocate(Player.class);
        player.id = UUID.randomUUID();
        player.worldObj = (World) allocate(WorldServer.class);
        player.inventory = new InventoryPlayer(player);
        return player;
    }

    private static void set(Object object, String name, Object value) throws Exception {
        Field field = object.getClass()
            .getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }

    private static Object allocate(Class<?> type) throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        Field field = unsafe.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return unsafe.getMethod("allocateInstance", Class.class)
            .invoke(field.get(null), type);
    }
}
