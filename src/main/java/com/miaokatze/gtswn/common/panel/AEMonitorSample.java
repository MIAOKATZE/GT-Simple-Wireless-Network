package com.miaokatze.gtswn.common.panel;

import net.minecraft.nbt.NBTTagCompound;

/**
 * AE 监视采样点（物品/流体数量与生成速率）。
 */
public class AEMonitorSample {

    public final long timeMs;
    public final long tick;
    public final long amount;
    public final double rate;

    public AEMonitorSample(long timeMs, long tick, long amount, double rate) {
        this.timeMs = timeMs;
        this.tick = tick;
        this.amount = amount < 0L ? 0L : amount;
        this.rate = rate;
    }

    public NBTTagCompound toNBT() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setLong("timeMs", timeMs);
        tag.setLong("tick", tick);
        tag.setLong("amount", amount);
        tag.setDouble("rate", rate);
        return tag;
    }

    public static AEMonitorSample fromNBT(NBTTagCompound tag) {
        return new AEMonitorSample(
            tag.getLong("timeMs"),
            tag.getLong("tick"),
            tag.getLong("amount"),
            tag.getDouble("rate"));
    }
}
