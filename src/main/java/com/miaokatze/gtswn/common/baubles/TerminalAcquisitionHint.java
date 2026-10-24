package com.miaokatze.gtswn.common.baubles;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraftforge.common.util.FakePlayer;

/** One acquisition hint per player, shared by every terminal and retained by Forge across death. */
public final class TerminalAcquisitionHint {

    private static final String SEEN = "GTSWNTerminalAcquisitionHint";

    private TerminalAcquisitionHint() {}

    /** Native inventory/worn callbacks cover new items and already owned items without inventory polling. */
    public static void observe(ItemStack stack, Entity entity) {
        if (!(entity instanceof EntityPlayerMP) || entity instanceof FakePlayer) return;
        observe(stack, entity, TerminalBaubles.isBaublesAvailable());
    }

    static void observe(ItemStack stack, Entity entity, boolean baublesAvailable) {
        if (!baublesAvailable || !(entity instanceof EntityPlayerMP player)
            || entity instanceof FakePlayer
            || !TerminalBaubles.isTerminal(stack)) return;
        NBTTagCompound root = player.getEntityData();
        NBTTagCompound persisted = root.getCompoundTag(EntityPlayer.PERSISTED_NBT_TAG);
        if (persisted.getBoolean(SEEN)) return;
        persisted.setBoolean(SEEN, true);
        root.setTag(EntityPlayer.PERSISTED_NBT_TAG, persisted);
        player.addChatMessage(new ChatComponentTranslation("gtswn.chat.terminal.baubles_hint"));
    }
}
