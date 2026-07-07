package com.miaokatze.gtswn.common.panel;

import java.math.BigInteger;

import net.minecraft.nbt.NBTTagCompound;

public class NetworkInfoSample {

    public final long timeMs;
    public final long tick;
    public final BigInteger eu;
    public final double eut;

    public NetworkInfoSample(long timeMs, long tick, BigInteger eu, double eut) {
        this.timeMs = timeMs;
        this.tick = tick;
        this.eu = eu == null ? BigInteger.ZERO : eu;
        this.eut = eut;
    }

    public NBTTagCompound toNBT() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setLong("timeMs", timeMs);
        tag.setLong("tick", tick);
        tag.setString("eu", eu.toString());
        tag.setDouble("eut", eut);
        return tag;
    }

    public static NetworkInfoSample fromNBT(NBTTagCompound tag) {
        BigInteger value;
        try {
            value = new BigInteger(tag.getString("eu"));
        } catch (NumberFormatException e) {
            value = BigInteger.ZERO;
        }
        return new NetworkInfoSample(tag.getLong("timeMs"), tag.getLong("tick"), value, tag.getDouble("eut"));
    }
}
