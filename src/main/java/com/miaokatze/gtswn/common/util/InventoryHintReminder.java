package com.miaokatze.gtswn.common.util;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ChatComponentTranslation;

/** Persistent inventory guidance, called once per second while its inventory condition is met. */
public final class InventoryHintReminder {

    private static final int MAX_HINTS = 3;
    private static final int INTERVAL_TICKS = 12000;

    private InventoryHintReminder() {}

    public static void remind(EntityPlayer player, String countKey, String heldTicksKey, String translationKey) {
        NBTTagCompound root = player.getEntityData();
        if (!root.hasKey(EntityPlayer.PERSISTED_NBT_TAG, 10)) {
            root.setTag(EntityPlayer.PERSISTED_NBT_TAG, new NBTTagCompound());
        }
        NBTTagCompound persisted = root.getCompoundTag(EntityPlayer.PERSISTED_NBT_TAG);
        int count = Math.max(0, persisted.getInteger(countKey));
        if (count >= MAX_HINTS) return;
        int held = Math.max(0, persisted.getInteger(heldTicksKey));
        if (held >= count * INTERVAL_TICKS) {
            player.addChatMessage(new ChatComponentTranslation(translationKey, MAX_HINTS - count - 1));
            persisted.setInteger(countKey, count + 1);
        }
        persisted.setInteger(heldTicksKey, held + 20);
    }
}
