package com.miaokatze.gtswn.common.items;

import net.minecraft.nbt.NBTTagCompound;

/** Dimension-local tick cooldown, with a one-time migration for old item NBT. */
final class WirelessTapCooldown {

    private static final String LAST_TIME = "LastUseTime";
    private static final String LAST_DIMENSION = "LastUseDimension";
    private static final long INTERVAL_TICKS = 4L;

    private WirelessTapCooldown() {}

    static boolean consume(NBTTagCompound tag, long now, int dimension) {
        long last = tag.getLong(LAST_TIME);
        // Missing dimension metadata includes both legacy tick and millisecond timestamps.
        // Different dimensions and a rolled-back world clock start a fresh cooldown.
        if (tag.hasKey(LAST_TIME, 4) && tag.hasKey(LAST_DIMENSION, 3)
            && tag.getInteger(LAST_DIMENSION) == dimension
            && last >= 0
            && now >= last
            && now - last < INTERVAL_TICKS) return false;
        tag.setLong(LAST_TIME, now);
        tag.setInteger(LAST_DIMENSION, dimension);
        return true;
    }
}
