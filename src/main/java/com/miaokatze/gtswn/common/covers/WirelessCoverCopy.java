package com.miaokatze.gtswn.common.covers;

import java.math.BigInteger;
import java.util.function.Predicate;

import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtswn.common.util.CoverMaths;

/** Copy configuration without creating stored or fractional EU. World-save NBT is untouched. */
public final class WirelessCoverCopy {

    private WirelessCoverCopy() {}

    public static NBTTagCompound configuration(NBTTagCompound source) {
        NBTTagCompound result = (NBTTagCompound) source.copy();
        NBTTagCompound data = result.getCompoundTag("d");
        data.setLong("storedEU", 0);
        data.setDouble("fractionalEU", 0);
        data.setLong("ticksSinceLastRefill", 0);
        data.setLong("ticksSinceLastUpload", 0);
        // Derived locally from V/A and current settings, not a transferable configuration value.
        data.removeTag("capacity");
        result.setTag("d", data);
        return result;
    }

    /** A declined transaction does not withdraw anything and leaves the destination empty. */
    public static long fundedBuffer(long capacity, double loss, Predicate<BigInteger> debit) {
        if (capacity <= 0 || debit == null) return 0;
        return debit.test(CoverMaths.downlinkDeduction(capacity, loss)) ? capacity : 0;
    }

    /** Preserve energy already owned by the destination and atomically fund only its missing capacity. */
    public static long refillBuffer(long existing, long capacity, double loss, Predicate<BigInteger> debit) {
        existing = Math.max(0, existing);
        if (capacity <= existing) return existing;
        return existing + fundedBuffer(capacity - existing, loss, debit);
    }

    /** Native cover tool copies settings while keeping the destination's own energy. */
    public static NBTTagCompound configurationPreservingBuffer(NBTTagCompound source, NBTTagCompound destination) {
        NBTTagCompound result = configuration(source);
        NBTTagCompound target = destination.getCompoundTag("d");
        result.getCompoundTag("d")
            .setLong("storedEU", target.getLong("storedEU"));
        result.getCompoundTag("d")
            .setDouble("fractionalEU", target.getDouble("fractionalEU"));
        return result;
    }
}
