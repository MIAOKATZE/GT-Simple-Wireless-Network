package com.miaokatze.gtswn.common.gui;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.play.server.S2FPacketSetSlot;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.IChatComponent;

import com.miaokatze.gtswn.common.api.enums.GTSWNItemList;
import com.miaokatze.gtswn.common.util.LaserHatchUtil;

/** Integer supplies in Forge's death-persistent player data; never oversized ItemStacks. */
public final class TerminalSupplyStore {

    public static final int CAPACITY = 640;

    public enum Kind {

        ANCHOR,
        TUBE,
        TELEPORT_ANCHOR;

        public ItemStack reference() {
            return switch (this) {
                case ANCHOR -> GTSWNItemList.Quantum_Incorporation_Anchor.get(1);
                case TUBE -> LaserHatchUtil.getLaserPipeItemStack();
                case TELEPORT_ANCHOR -> GTSWNItemList.Network_Teleport_Anchor.get(1);
            };
        }

        public String labelKey() {
            return switch (this) {
                case ANCHOR -> "gtswn.gui.terminal.anchor";
                case TUBE -> "gtswn.gui.terminal.tube";
                case TELEPORT_ANCHOR -> "gtswn.gui.terminal.teleport_anchor";
            };
        }

        public boolean matches(ItemStack stack) {
            ItemStack reference = reference();
            return SupplyInventoryTransfer.matches(stack, reference);
        }
    }

    private TerminalSupplyStore() {}

    public static int count(EntityPlayer player, Kind kind) {
        return count(data(player), kind);
    }

    static int count(NBTTagCompound data, Kind kind) {
        return Math.max(0, Math.min(CAPACITY, data.getInteger("GTSWN_Supply_" + kind.name())));
    }

    private static NBTTagCompound data(EntityPlayer player) {
        return player.getEntityData()
            .getCompoundTag(EntityPlayer.PERSISTED_NBT_TAG);
    }

    public static void set(EntityPlayer player, Kind kind, int count) {
        if (player.worldObj.isRemote) return;
        NBTTagCompound data = data(player);
        set(data, kind, count);
        player.getEntityData()
            .setTag(EntityPlayer.PERSISTED_NBT_TAG, data);
    }

    static void set(NBTTagCompound data, Kind kind, int count) {
        data.setInteger("GTSWN_Supply_" + kind.name(), Math.max(0, Math.min(CAPACITY, count)));
    }

    public static boolean available(EntityPlayer player, Kind kind) {
        if (count(player, kind) > 0) return true;
        return inventorySlot(player, kind) >= 0;
    }

    /** Keep old consumables' item/meta fallback; the new anchor always uses exact item/meta/NBT. */
    public static int inventorySlot(EntityPlayer player, Kind kind) {
        ItemStack reference = kind.reference();
        for (int slot = 0; slot < Math.min(36, player.inventory.mainInventory.length); slot++) {
            ItemStack stack = player.inventory.mainInventory[slot];
            if (inventoryMatches(kind, stack, reference)) return slot;
        }
        return -1;
    }

    static boolean inventoryMatches(Kind kind, ItemStack stack, ItemStack reference) {
        return kind == Kind.TELEPORT_ANCHOR ? SupplyInventoryTransfer.matches(stack, reference)
            : stack != null && stack.stackSize > 0
                && reference != null
                && stack.getItem() == reference.getItem()
                && stack.getItemDamage() == reference.getItemDamage();
    }

    /** Call only after success; preserve the last consumed supply's zero and capture delayed chat counts. */
    public static IChatComponent appendRemaining(IChatComponent message, EntityPlayer player, Kind kind, int before) {
        return appendRemaining(message, kind, before, count(player, kind));
    }

    static IChatComponent appendRemaining(IChatComponent message, Kind kind, int before, int remaining) {
        if (before > 0) message.appendSibling(
            new ChatComponentTranslation(
                "gtswn.chat.terminal.preloaded_remaining",
                new ChatComponentTranslation(kind.labelKey()),
                remaining));
        return message;
    }

    /** Shared supply first; preserve the existing item+damage backpack consumption fallback. */
    public static boolean consume(EntityPlayer player, Kind kind) {
        if (player.worldObj.isRemote) return false;
        int count = count(player, kind);
        if (count > 0) {
            set(player, kind, count - 1);
            return true;
        }
        int slot = inventorySlot(player, kind);
        if (slot < 0) return false;
        player.inventory.decrStackSize(slot, 1);
        syncInventory(player, slot);
        return true;
    }

    /** Server-thread escrow: remove one before movement, then commit or release exactly once. */
    public static Reservation reserveTeleportAnchor(EntityPlayer player, boolean reserve) {
        if (player.worldObj.isRemote) return null;
        Kind kind = Kind.TELEPORT_ANCHOR;
        if (reserve) {
            int count = count(player, kind);
            if (count <= 0) return null;
            Reservation receipt = new Reservation(player, -1, kind.reference());
            set(player, kind, count - 1);
            return receipt;
        }
        int slot = inventorySlot(player, kind);
        if (slot < 0) return null;
        ItemStack removed = player.inventory.decrStackSize(slot, 1);
        return new Reservation(player, slot, removed);
    }

    public static final class Reservation {

        private final EntityPlayer player;
        private final int slot;
        private final ItemStack item;
        private boolean settled;

        private Reservation(EntityPlayer player, int slot, ItemStack item) {
            this.player = player;
            this.slot = slot;
            this.item = item.copy();
            this.item.stackSize = 1;
        }

        public void commit() {
            if (settled) return;
            settled = true;
            syncInventory(player, slot);
        }

        public void release() {
            if (settled) return;
            settled = true;
            // A dimension hook can change inventory contents. Prefer the exact source, then the
            // player's death-persistent reserve; never overwrite an unrelated slot or discard a refund.
            if (slot >= 0) {
                ItemStack current = player.inventory.getStackInSlot(slot);
                if (current == null) {
                    player.inventory.setInventorySlotContents(slot, item.copy());
                    syncInventory(player, slot);
                    return;
                }
                if (SupplyInventoryTransfer.matches(current, item) && current.stackSize < current.getMaxStackSize()) {
                    current.stackSize++;
                    syncInventory(player, slot);
                    return;
                }
            }
            int count = count(player, Kind.TELEPORT_ANCHOR);
            if (count < CAPACITY) set(player, Kind.TELEPORT_ANCHOR, count + 1);
            else if (SupplyInventoryTransfer.withdraw(player.inventory, item, 1, -1) > 0)
                player.entityDropItem(item.copy(), 0);
            syncInventory(player, slot);
        }
    }

    private static void syncInventory(EntityPlayer player, int slot) {
        player.inventory.markDirty();
        if (player instanceof EntityPlayerMP mp && mp.playerNetServerHandler != null && slot >= 0)
            mp.playerNetServerHandler
                .sendPacket(new S2FPacketSetSlot(0, slot < 9 ? 36 + slot : slot, player.inventory.mainInventory[slot]));
        if (player.openContainer != null) player.openContainer.detectAndSendChanges();
    }
}
