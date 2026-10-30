package com.miaokatze.gtswn.common.gui;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.util.List;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.ICrafting;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.storage.WorldInfo;

import org.junit.Test;

import com.cleanroommc.modularui.factory.GuiData;
import com.cleanroommc.modularui.screen.UISettings;
import com.cleanroommc.modularui.value.sync.ItemSlotSH;
import com.cleanroommc.modularui.value.sync.ModularSyncManager;
import com.cleanroommc.modularui.value.sync.PanelSyncManager;
import com.cleanroommc.modularui.widgets.slot.PlayerSlotGroup;
import com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal;

/** Real container/name/NBT paths; no running client, network, AE grid or native window. */
public class QuantumTerminalRenameTest {

    private static class Player extends EntityPlayerMP {

        boolean alive = true;

        private Player() {
            super(null, null, null, null);
        }

        @Override
        public boolean isEntityAlive() {
            return alive;
        }
    }

    private static Object allocate(Class<?> type) throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        Field field = unsafe.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return unsafe.getMethod("allocateInstance", Class.class)
            .invoke(field.get(null), type);
    }

    private Player player() throws Exception {
        Player player = (Player) allocate(Player.class);
        player.alive = true;
        World world = (World) allocate(WorldServer.class);
        Field worldInfo = World.class.getDeclaredField("worldInfo");
        worldInfo.setAccessible(true);
        worldInfo.set(world, new WorldInfo(new NBTTagCompound()));
        player.worldObj = world;
        player.inventory = new InventoryPlayer(player);
        player.inventory.currentItem = 3;
        ItemStack terminal = new ItemStack(new ItemNetworkQuantumTerminal());
        NBTTagCompound nbt = new NBTTagCompound();
        nbt.setBoolean("QT_Bound", true);
        nbt.setString("QT_BoundName", "controller name stays intact");
        nbt.setInteger("QT_AnchorX", 17);
        nbt.setString("GTSWN_QTEncryptionKey", "native-key");
        nbt.setInteger("GTSWN_NetworkColor", 12);
        nbt.setBoolean("GTSWN_NetworkSafetyEnabled", true);
        NBTTagCompound display = new NBTTagCompound();
        display.setInteger("color", 0xAA55CC);
        nbt.setTag("display", display);
        terminal.setTagCompound(nbt);
        player.inventory.setInventorySlotContents(3, terminal);
        return player;
    }

    private ContainerTerminal open(Player player, boolean quantum) {
        ContainerTerminal container = new ContainerTerminal(player, quantum, new ItemStack(new Item()));
        container.windowId = 19;
        player.openContainer = container;
        return container;
    }

    private boolean rename(ContainerTerminal container, Player player, String name) {
        return container.renameTerminal(
            player,
            container.windowId,
            3,
            container.renameToken(),
            ContainerTerminal.customName(player.getHeldItem()),
            name);
    }

    @Test
    public void muiSlotsRegisterOnceAndRenameSurvivesTemporaryScreensButNotClosedSessions() throws Exception {
        Player player = player();
        ContainerTerminal container = new ContainerTerminal(player, true, new ItemStack(new Item()), true);
        container.windowId = 19;
        player.openContainer = container;
        assertEquals(0, container.inventorySlots.size());
        ModularSyncManager session = new ModularSyncManager(false) {

            @Override
            public void detectAndSendChanges(boolean init) {
                // Exercise real registration/lifecycle without a running FML network channel.
            }
        };
        PanelSyncManager sync = new PanelSyncManager(session, true);
        sync.registerSlotGroup(new PlayerSlotGroup(PlayerSlotGroup.NAME));
        for (int i = 0; i < 37; i++) sync.syncValue("terminal_slot", i, new ItemSlotSH(container.terminalSlot(i)));
        container.construct(player, session, new UISettings(), "quantum_terminal", new GuiData(player));
        assertEquals(37, container.inventorySlots.size());
        assertEquals(
            36,
            sync.getSlotGroup(PlayerSlotGroup.NAME)
                .getSlots()
                .size());
        for (int i = 0; i < 37; i++) {
            assertSame(container.terminalSlot(i), container.getSlot(i));
            assertTrue(
                container.terminalSlot(i)
                    .isInitialized());
        }
        assertEquals(
            0,
            container.getSlot(0)
                .getSlotIndex());
        assertEquals(
            9,
            container.getSlot(1)
                .getSlotIndex());
        assertEquals(
            0,
            container.getSlot(28)
                .getSlotIndex());
        session.onOpen();
        assertTrue(rename(container, player, "MUI2 session"));
        container.onContainerClosed(player);
        assertTrue(rename(container, player, "Returned from NEI"));
        session.onClose();
        assertFalse(rename(container, player, "closed request"));
        session.onOpen();
        assertTrue(rename(container, player, "Reopened"));
        assertEquals(37, container.inventorySlots.size());
        session.onClose();
        session.dispose();
        for (int i = 0; i < 37; i++) assertFalse(
            container.terminalSlot(i)
                .isInitialized());
    }

    @Test
    public void nativeRenameAndBlankResetPreserveAllOtherTerminalAndDisplayData() throws Exception {
        Player player = player();
        ContainerTerminal container = open(player, true);
        ItemStack held = player.getHeldItem();
        NBTTagCompound original = (NBTTagCompound) held.getTagCompound()
            .copy();
        long token = container.renameToken();
        // No key pairing or SECURITY capability is requested for the player's own item's name.
        assertTrue(rename(container, player, "量子终端 E / 中文"));
        assertEquals("量子终端 E / 中文", held.getDisplayName());
        assertTrue(held.hasDisplayName());
        NBTTagCompound expected = (NBTTagCompound) original.copy();
        expected.getCompoundTag("display")
            .setString("Name", "量子终端 E / 中文");
        assertEquals(expected, held.getTagCompound());
        assertNotEquals(token, container.renameToken());
        assertFalse(container.renameTerminal(player, 19, 3, token, "", "replayed"));
        assertTrue(rename(container, player, "   \t\n"));
        assertFalse(held.hasDisplayName());
        assertEquals(original, held.getTagCompound());
        assertEquals(0, player.experienceLevel);
    }

    @Test
    public void contextRejectsSlotStackWindowOwnerClosedSessionDeadPlayerAndStaleName() throws Exception {
        Player player = player();
        ContainerTerminal container = open(player, true);
        ItemStack original = player.getHeldItem();
        long token = container.renameToken();
        assertFalse(container.renameTerminal(player, 20, 3, token, "", "bad"));
        assertFalse(container.renameTerminal(player, 19, 4, token, "", "bad"));
        assertFalse(container.renameTerminal(player, 19, 3, 0, "", "bad"));
        assertFalse(container.renameTerminal(player, 19, 3, token, "outdated", "bad"));
        assertFalse(container.renameTerminal(player(), 19, 3, token, "", "bad"));
        player.inventory.currentItem = 4;
        assertFalse(container.renameTerminal(player, 19, 3, token, "", "bad"));
        player.inventory.currentItem = 3;
        player.inventory.setInventorySlotContents(3, original.copy());
        assertFalse(container.renameTerminal(player, 19, 3, token, "", "bad"));
        player.inventory.setInventorySlotContents(3, original);
        player.alive = false;
        assertFalse(container.renameTerminal(player, 19, 3, token, "", "bad"));
        player.alive = true;
        ContainerTerminal reopened = open(player, true);
        assertNotEquals(token, reopened.renameToken());
        assertFalse(container.renameTerminal(player, 19, 3, token, "", "bad"));
        assertFalse(reopened.renameTerminal(player, 19, 3, token, "", "bad"));
        reopened.onContainerClosed(player);
        assertFalse(rename(reopened, player, "bad"));
        assertFalse(original.hasDisplayName());
    }

    @Test
    public void expiredRequestsCannotRenameEvenBeforeTheNextContainerSyncAndLinkHasNoCapability() throws Exception {
        Player player = player();
        ContainerTerminal container = open(player, true);
        Field issued = ContainerTerminal.class.getDeclaredField("renameIssuedNanos");
        issued.setAccessible(true);
        issued.setLong(container, System.nanoTime() - 61_000_000_000L);
        assertFalse(rename(container, player, "expired"));
        ContainerTerminal link = open(player, false);
        assertEquals(0L, link.renameToken());
        assertFalse(rename(link, player, "link"));
        Player reconnected = player();
        ContainerTerminal fresh = open(reconnected, true);
        assertFalse(fresh.renameTerminal(reconnected, 19, 3, container.renameToken(), "", "old login"));
    }

    @Test
    public void namesAreBoundedFilteredAndSentThroughNativeSlotsAndSignedShortProgressUpdates() throws Exception {
        assertEquals("中E文", TerminalNameRules.normalize("中\u0000E\u007f\u00a7文"));
        assertEquals("", TerminalNameRules.normalize(" \n\t "));
        assertEquals(
            40,
            TerminalNameRules.normalize(new String(new char[40]).replace('\0', '中'))
                .length());
        assertNull(TerminalNameRules.normalize(new String(new char[41]).replace('\0', '中')));
        assertNull(TerminalNameRules.normalize(null));
        Player player = player();
        ContainerTerminal server = open(player, true);
        Player clientPlayer = player();
        clientPlayer.worldObj.isRemote = true;
        ContainerTerminal client = open(clientPlayer, true);
        final ItemStack[] received = { null };
        server.addCraftingToCrafters(new ICrafting() {

            @Override
            public void sendContainerAndContentsToPlayer(Container container, List stacks) {}

            @Override
            public void sendSlotContents(Container container, int slot, ItemStack stack) {
                if (slot == 31) received[0] = stack.copy();
            }

            @Override
            public void sendProgressBarUpdate(Container container, int id, int value) {
                client.updateProgressBar(id, (short) value);
            }
        });
        received[0] = null;
        assertEquals(server.renameToken(), client.renameToken());
        assertTrue(rename(server, player, "Name E"));
        assertNotNull(received[0]);
        assertEquals("Name E", received[0].getDisplayName());
        assertEquals(server.renameToken(), client.renameToken());
        assertFalse(rename(server, player, new String(new char[41]).replace('\0', 'a')));
    }

    @Test
    public void paletteContextRequiresTheExactOpenStackAndOriginalBinding() throws Exception {
        Player player = player();
        ContainerTerminal original = open(player, true);
        assertTrue(original.matchesColorContext(player));
        ItemStack terminal = player.getHeldItem();
        player.inventory.setInventorySlotContents(3, terminal.copy());
        assertFalse(original.matchesColorContext(player));
        player.inventory.setInventorySlotContents(3, terminal);
        assertTrue(original.matchesColorContext(player));
        terminal.getTagCompound()
            .setInteger("QT_AnchorX", 18);
        assertFalse(original.matchesColorContext(player));
        terminal.getTagCompound()
            .setInteger("QT_AnchorX", 17);
        assertTrue(original.matchesColorContext(player));
        terminal.getTagCompound()
            .setString("QT_EncryptionKey", "new-key");
        assertFalse(original.matchesColorContext(player));
        terminal.getTagCompound()
            .setString("QT_EncryptionKey", "");
        assertTrue(original.matchesColorContext(player));
        ContainerTerminal reopened = open(player, true);
        assertFalse(original.matchesColorContext(player));
        assertTrue(reopened.matchesColorContext(player));
        reopened.onModularContainerClosed();
        assertFalse(reopened.matchesColorContext(player));
    }
}
