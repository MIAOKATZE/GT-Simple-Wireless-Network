package com.miaokatze.gtswn.common.device;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtswn.common.baubles.TerminalBaubles;
import com.miaokatze.gtswn.common.items.ItemDeviceInfoTerminal;
import com.miaokatze.gtswn.common.items.WirelessEnergyTap;
import com.miaokatze.gtswn.network.GTSWNPacketHandler;
import com.miaokatze.gtswn.network.PacketTerminalLinkSettings;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** Player-owned preference, separate from server-wide teleport settings and all terminal item NBT. */
public final class TerminalLinkSettings {

    public static final String AUTO_KEY = "GTSWNAutoConfigureLinks";
    public static final String HINT_KEY = "GTSWNDeviceLinkHintSeen";

    private TerminalLinkSettings() {}

    public static boolean autoEnabled(EntityPlayer player) {
        return player.getEntityData()
            .getCompoundTag(EntityPlayer.PERSISTED_NBT_TAG)
            .getBoolean(AUTO_KEY);
    }

    public static void setAutoEnabled(EntityPlayer player, boolean enabled) {
        NBTTagCompound root = player.getEntityData();
        NBTTagCompound persisted = root.getCompoundTag(EntityPlayer.PERSISTED_NBT_TAG);
        persisted.setBoolean(AUTO_KEY, enabled);
        root.setTag(EntityPlayer.PERSISTED_NBT_TAG, persisted);
    }

    /** Main inventory and actual Baubles slots only; never recurse through third-party bag NBT. */
    public static ItemStack linkTerminal(EntityPlayer player) {
        if (player == null) return null;
        for (ItemStack stack : player.inventory.mainInventory) if (isLink(stack)) return stack;
        for (ItemStack stack : TerminalBaubles.terminals(player)) if (isLink(stack)) return stack;
        return null;
    }

    public static boolean holdsInfoTerminal(EntityPlayer player) {
        for (ItemStack stack : player.inventory.mainInventory)
            if (stack != null && stack.stackSize == 1 && stack.getItem() instanceof ItemDeviceInfoTerminal) return true;
        for (ItemStack stack : TerminalBaubles.terminals(player))
            if (stack != null && stack.getItem() instanceof ItemDeviceInfoTerminal) return true;
        return false;
    }

    private static boolean isLink(ItemStack stack) {
        return stack != null && stack.stackSize == 1 && stack.getItem() instanceof WirelessEnergyTap;
    }

    public static void observeCombinedHint(net.minecraft.entity.player.EntityPlayerMP player) {
        if (player instanceof net.minecraftforge.common.util.FakePlayer || linkTerminal(player) == null
            || !holdsInfoTerminal(player)) return;
        NBTTagCompound root = player.getEntityData();
        NBTTagCompound persisted = root.getCompoundTag(EntityPlayer.PERSISTED_NBT_TAG);
        if (persisted.getBoolean(HINT_KEY)) return;
        persisted.setBoolean(HINT_KEY, true);
        root.setTag(EntityPlayer.PERSISTED_NBT_TAG, persisted);
        player.addChatMessage(new net.minecraft.util.ChatComponentTranslation("gtswn.device.link.first_hint"));
    }

    @SideOnly(Side.CLIENT)
    public static boolean clientAutoEnabled() {
        return com.miaokatze.gtswn.client.TerminalLinkClientState.autoEnabled();
    }

    @SideOnly(Side.CLIENT)
    public static void requestSettings() {
        GTSWNPacketHandler.NETWORK.sendToServer(new PacketTerminalLinkSettings(false, false));
    }

    @SideOnly(Side.CLIENT)
    public static void requestAuto(boolean enabled) {
        GTSWNPacketHandler.NETWORK.sendToServer(new PacketTerminalLinkSettings(true, enabled));
    }
}
