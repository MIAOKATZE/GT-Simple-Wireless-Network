// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.common.baubles;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.entity.player.PlayerCapabilities;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.util.IChatComponent;
import net.minecraft.world.WorldServer;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal;
import com.miaokatze.gtswn.common.items.WirelessEnergyTap;
import com.miaokatze.gtswn.mixins.baubles.PacketSyncAllBaubleMixin;
import com.miaokatze.gtswn.network.PacketTerminalBaubleAction;

import baubles.api.expanded.BaubleExpandedSlots;
import baubles.common.container.InventoryBaubles;
import baubles.common.container.SlotBauble;
import baubles.common.lib.PlayerHandler;

/** Uses Baubles' real validation/setter and vanilla slotClick, without a running FML transport. */
public class TerminalBaubleNativeInventoryTest {

    private static final int LINK_SLOT = 5;
    private List<String> oldAssignments;
    private List<String> oldTypes;
    private boolean oldAvailable;
    private Player player;
    private NativeInventory baubles;
    private NativeContainer container;

    @SuppressWarnings("unchecked")
    private static List<String> slotList(String name) throws Exception {
        Field field = BaubleExpandedSlots.class.getDeclaredField(name);
        field.setAccessible(true);
        return (List<String>) field.get(null);
    }

    @Before
    public void setUp() throws Exception {
        oldAssignments = new ArrayList<>(slotList("assignedSlots"));
        oldTypes = new ArrayList<>(slotList("registeredTypes"));
        slotList("registeredTypes").add("gtswn_link");
        slotList("registeredTypes").add("gtswn_quantum");
        slotList("assignedSlots").clear();
        for (int i = 0; i < 8; i++)
            slotList("assignedSlots").add(i == LINK_SLOT ? "gtswn_link" : i == 7 ? "gtswn_quantum" : "ring");
        Field available = TerminalBaubles.class.getDeclaredField("baublesAvailable");
        available.setAccessible(true);
        oldAvailable = available.getBoolean(null);
        available.setBoolean(null, true);
        player = (Player) allocate(Player.class);
        player.id = UUID.randomUUID();
        player.worldObj = (WorldServer) allocate(WorldServer.class);
        player.inventory = new InventoryPlayer(player);
        player.capabilities = new PlayerCapabilities();
        player.playerNetServerHandler = new NetHandlerPlayServer(null, new ClosedNetwork(), player);
        baubles = new NativeInventory(player);
        PlayerHandler.setPlayerBaubles(player, baubles);
        container = new NativeContainer(player, baubles);
        player.inventoryContainer = container;
        player.openContainer = container;
    }

    @After
    public void tearDown() throws Exception {
        if (player != null) {
            TerminalBaubles.clear(player);
            PlayerHandler.clearPlayerBaubles(player);
        }
        slotList("assignedSlots").clear();
        slotList("assignedSlots").addAll(oldAssignments);
        slotList("registeredTypes").clear();
        slotList("registeredTypes").addAll(oldTypes);
        Field available = TerminalBaubles.class.getDeclaredField("baublesAvailable");
        available.setAccessible(true);
        available.setBoolean(null, oldAvailable);
    }

    @Test
    public void nativeManualPlacementAcceptsBothLinkModesAndPreservesNbtAndCount() {
        for (int mode = 0; mode <= 1; mode++) {
            ItemStack terminal = link(mode);
            ItemStack expected = terminal.copy();
            assertTrue(baubles.isItemValidForSlot(LINK_SLOT, terminal));
            assertTrue(
                container.getSlot(0)
                    .isItemValid(terminal));
            player.inventory.setItemStack(terminal);
            container.slotClick(0, 0, 0, player);
            assertNull(player.inventory.getItemStack());
            ItemStack placed = baubles.getStackInSlot(LINK_SLOT);
            assertTrue(ItemStack.areItemStacksEqual(expected, placed));
            assertEquals(0, terminal.stackSize);
            assertEquals(LINK_SLOT, baubles.syncedSlot);
            assertSame(placed, baubles.synced);
            container.slotClick(0, 0, 0, player);
            assertNull(baubles.getStackInSlot(LINK_SLOT));
            assertSame(placed, player.inventory.getItemStack());
            player.inventory.setItemStack(null);
        }
    }

    @Test
    public void cEquipBorrowUseAndReturnKeepOriginalReferenceAndNbtWithoutReplay() {
        ItemStack terminal = link(0);
        player.inventory.setInventorySlotContents(0, terminal);
        TerminalBaubles.service(player, PacketTerminalBaubleAction.EQUIP, -1);
        assertNull(player.getHeldItem());
        assertSame(terminal, baubles.getStackInSlot(LINK_SLOT));
        TerminalBaubles.service(player, PacketTerminalBaubleAction.SELECT, 1);
        assertSame(terminal, player.getHeldItem());
        assertNull(baubles.getStackInSlot(LINK_SLOT));
        terminal.setItemDamage(1);
        terminal.stackTagCompound.setBoolean("OutputMode", true);
        terminal.stackTagCompound.setLong("LastUseTime", 12345);
        TerminalBaubles.service(player, PacketTerminalBaubleAction.RETURN, -1);
        assertNull(player.getHeldItem());
        assertSame(terminal, baubles.getStackInSlot(LINK_SLOT));
        assertTrue(terminal.stackTagCompound.getBoolean("OutputMode"));
        assertEquals(12345, terminal.stackTagCompound.getLong("LastUseTime"));
        TerminalBaubles.service(player, PacketTerminalBaubleAction.RETURN, -1);
        assertSame(terminal, baubles.getStackInSlot(LINK_SLOT));
        assertNull(player.getHeldItem());
    }

    @Test
    public void cBorrowFullBackpackAndReplacedStackCannotMoveOrDuplicateItems() {
        ItemStack terminal = link(0);
        baubles.setInventorySlotContents(LINK_SLOT, terminal);
        for (int i = 0; i < 36; i++) player.inventory.setInventorySlotContents(i, new ItemStack(new Item()));
        ItemStack held = player.getHeldItem();
        TerminalBaubles.service(player, PacketTerminalBaubleAction.SELECT, 1);
        assertSame(terminal, baubles.getStackInSlot(LINK_SLOT));
        assertSame(held, player.getHeldItem());
        player.inventory.setInventorySlotContents(9, null);
        TerminalBaubles.service(player, PacketTerminalBaubleAction.SELECT, 1);
        assertSame(terminal, player.getHeldItem());
        assertSame(held, player.inventory.getStackInSlot(9));
        ItemStack replacement = terminal.copy();
        player.inventory.setInventorySlotContents(0, replacement);
        TerminalBaubles.service(player, PacketTerminalBaubleAction.RETURN, -1);
        assertNull(baubles.getStackInSlot(LINK_SLOT));
        assertSame(replacement, player.getHeldItem());
    }

    @Test
    public void wheelEnergyAndPowerModesChangeOnlyAfterSuccessfulNativeBorrowAndReturnSameStack() {
        for (int action : new int[] { PacketTerminalBaubleAction.WHEEL_OUTER_FIRST,
            PacketTerminalBaubleAction.WHEEL_OUTER_SECOND }) {
            boolean output = action == PacketTerminalBaubleAction.WHEEL_OUTER_SECOND;
            ItemStack terminal = link(output ? 0 : 1);
            baubles.setInventorySlotContents(LINK_SLOT, terminal);
            for (int i = 0; i < 36; i++) player.inventory.setInventorySlotContents(i, new ItemStack(new Item()));
            ItemStack oldHeld = player.getHeldItem();
            TerminalBaubles.service(player, action, 1);
            assertSame(terminal, baubles.getStackInSlot(LINK_SLOT));
            assertSame(oldHeld, player.getHeldItem());
            assertEquals(!output, WirelessEnergyTap.getOutputModeStatic(terminal));
            assertEquals(output ? 0 : 1, terminal.getItemDamage());
            player.inventory.setInventorySlotContents(9, null);
            TerminalBaubles.service(player, action, 1);
            assertSame(terminal, player.getHeldItem());
            assertSame(oldHeld, player.inventory.getStackInSlot(9));
            assertEquals(output, WirelessEnergyTap.getOutputModeStatic(terminal));
            assertEquals(output ? 1 : 0, terminal.getItemDamage());
            assertEquals("preserved", terminal.stackTagCompound.getString("unrelated"));
            TerminalBaubles.service(player, PacketTerminalBaubleAction.RETURN, -1);
            assertSame(terminal, baubles.getStackInSlot(LINK_SLOT));
            assertNull(player.getHeldItem());
            baubles.setInventorySlotContents(LINK_SLOT, null);
        }
    }

    @Test
    public void wheelActionsRejectMissingOrInvalidSelectionAndQuantumClientOnlyInnerAction() {
        ItemStack terminal = link(0);
        baubles.setInventorySlotContents(LINK_SLOT, terminal);
        for (int selection : new int[] { -1, 0, 2, 8 })
            TerminalBaubles.service(player, PacketTerminalBaubleAction.WHEEL_OUTER_SECOND, selection);
        assertSame(terminal, baubles.getStackInSlot(LINK_SLOT));
        assertFalse(WirelessEnergyTap.getOutputModeStatic(terminal));
        assertNull(player.getHeldItem());
        ItemStack quantum = new ItemStack(new ItemNetworkQuantumTerminal());
        baubles.setInventorySlotContents(7, quantum);
        TerminalBaubles.service(player, PacketTerminalBaubleAction.WHEEL_INNER, 3);
        assertSame(quantum, baubles.getStackInSlot(7));
        assertNull(quantum.stackTagCompound);
        assertNull(player.getHeldItem());
    }

    @Test
    public void wheelCannotOverwriteAnActiveBorrowOrIgnoreNativeUnequipVeto() {
        ItemStack first = link(0), other = new ItemStack(new WirelessEnergyTap() {

            @Override
            public boolean canUnequip(ItemStack stack, net.minecraft.entity.EntityLivingBase entity) {
                return false;
            }
        });
        baubles.setInventorySlotContents(LINK_SLOT, first);
        TerminalBaubles.service(player, PacketTerminalBaubleAction.WHEEL_OUTER_FIRST, 1);
        baubles.setInventorySlotContents(LINK_SLOT, other);
        TerminalBaubles.service(player, PacketTerminalBaubleAction.WHEEL_OUTER_SECOND, 1);
        assertSame(first, player.getHeldItem());
        assertSame(other, baubles.getStackInSlot(LINK_SLOT));
        baubles.setInventorySlotContents(LINK_SLOT, null);
        TerminalBaubles.service(player, PacketTerminalBaubleAction.RETURN, -1);
        assertSame(first, baubles.getStackInSlot(LINK_SLOT));
        baubles.setInventorySlotContents(LINK_SLOT, other);
        TerminalBaubles.service(player, PacketTerminalBaubleAction.WHEEL_OUTER_SECOND, 1);
        assertNull(player.getHeldItem());
        assertSame(other, baubles.getStackInSlot(LINK_SLOT));
        assertFalse(WirelessEnergyTap.getOutputModeStatic(other));
    }

    @Test
    public void fullSnapshotResetKeepsInventoryReferencedByTheSamePlayersOpenSlots() throws Exception {
        baubles.setInventorySlotContents(LINK_SLOT, link(0));
        resetForSnapshot(player);
        assertSame(baubles, PlayerHandler.getPlayerBaubles(player));
        assertSame(baubles, container.getSlot(0).inventory);
        assertSame(player, baubles.player.get());
        assertNull(baubles.getStackInSlot(LINK_SLOT));
    }

    @Test
    public void fullSnapshotResetRebuildsNameCachedInventoryWhenRespawnChangesItsOwner() throws Exception {
        Player replacement = (Player) allocate(Player.class);
        replacement.id = player.id;
        replacement.worldObj = player.worldObj;
        assertSame(baubles, PlayerHandler.getPlayerBaubles(replacement));
        assertSame(player, baubles.player.get());
        resetForSnapshot(replacement);
        InventoryBaubles rebuilt = PlayerHandler.getPlayerBaubles(replacement);
        assertNotSame(baubles, rebuilt);
        assertSame(replacement, rebuilt.player.get());
    }

    private static void resetForSnapshot(EntityPlayer player) throws Exception {
        Method reset = PacketSyncAllBaubleMixin.class
            .getDeclaredMethod("gtswn$preserveOpenInventory", EntityPlayer.class);
        reset.setAccessible(true);
        reset.invoke(new PacketSyncAllBaubleMixin() {}, player);
    }

    private static ItemStack link(int mode) {
        ItemStack stack = new ItemStack(new WirelessEnergyTap(), 1, mode);
        stack.stackTagCompound = new NBTTagCompound();
        stack.stackTagCompound.setBoolean("OutputMode", mode == 1);
        stack.stackTagCompound.setString("unrelated", "preserved");
        return stack;
    }

    private static Object allocate(Class<?> type) throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        Field field = unsafe.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return unsafe.getMethod("allocateInstance", Class.class)
            .invoke(field.get(null), type);
    }

    private static final class Player extends EntityPlayerMP {

        UUID id;

        private Player() {
            super(null, null, null, null);
        }

        @Override
        public UUID getUniqueID() {
            return id;
        }

        @Override
        public String getCommandSenderName() {
            return id.toString();
        }

        @Override
        public void addChatMessage(IChatComponent message) {}
    }

    private static final class ClosedNetwork extends NetworkManager {

        ClosedNetwork() {
            super(false);
        }

        @Override
        public boolean isChannelOpen() {
            return false;
        }
    }

    private static final class NativeInventory extends InventoryBaubles {

        int syncedSlot = -1;
        ItemStack synced;

        NativeInventory(EntityPlayer player) {
            super(player);
        }

        @Override
        public void syncSlotToClients(int slot) {
            syncedSlot = slot;
            synced = getStackInSlot(slot);
        }
    }

    private static final class NativeContainer extends Container {

        NativeContainer(EntityPlayer player, InventoryBaubles baubles) {
            addSlotToContainer(new SlotBauble(baubles, "gtswn_link", LINK_SLOT, 0, 0));
            for (int i = 0; i < 36; i++) addSlotToContainer(new Slot(player.inventory, i, 0, 0));
        }

        @Override
        public boolean canInteractWith(EntityPlayer player) {
            return true;
        }
    }
}
