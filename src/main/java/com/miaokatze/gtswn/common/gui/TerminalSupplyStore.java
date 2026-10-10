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
        TUBE;

        public ItemStack reference() {
            return this == ANCHOR ? GTSWNItemList.Quantum_Incorporation_Anchor.get(1)
                : LaserHatchUtil.getLaserPipeItemStack();
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
        for (ItemStack stack : player.inventory.mainInventory) {
            ItemStack reference = kind.reference();
            if (stack != null && stack.stackSize > 0
                && reference != null
                && stack.getItem() == reference.getItem()
                && stack.getItemDamage() == reference.getItemDamage()) return true;
        }
        return false;
    }

    /** Call only after success; preserve the last consumed supply's zero and capture delayed chat counts. */
    public static IChatComponent appendRemaining(IChatComponent message, EntityPlayer player, Kind kind, int before) {
        return appendRemaining(message, kind, before, count(player, kind));
    }

    static IChatComponent appendRemaining(IChatComponent message, Kind kind, int before, int remaining) {
        if (before > 0) message.appendSibling(
            new ChatComponentTranslation(
                "gtswn.chat.terminal.preloaded_remaining",
                new ChatComponentTranslation(
                    kind == Kind.ANCHOR ? "gtswn.gui.terminal.anchor" : "gtswn.gui.terminal.tube"),
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
        ItemStack reference = kind.reference();
        if (reference == null) return false;
        for (int i = 0; i < player.inventory.mainInventory.length; i++) {
            ItemStack stack = player.inventory.mainInventory[i];
            if (stack == null || stack.stackSize <= 0
                || stack.getItem() != reference.getItem()
                || stack.getItemDamage() != reference.getItemDamage()) continue;
            player.inventory.decrStackSize(i, 1);
            player.inventory.markDirty();
            if (player instanceof EntityPlayerMP mp) mp.playerNetServerHandler
                .sendPacket(new S2FPacketSetSlot(0, i < 9 ? 36 + i : i, player.inventory.mainInventory[i]));
            player.openContainer.detectAndSendChanges();
            return true;
        }
        return false;
    }
}
