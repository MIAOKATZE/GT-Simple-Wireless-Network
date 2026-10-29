package com.miaokatze.gtswn.common.charging;

import java.math.BigDecimal;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import gregtech.api.items.MetaBaseItem;

/** Exact persisted accounting; the legacy doubles are display/API projections, never running balances. */
final class BatteryCharge {

    static final String REMAINDER = "GTSWNChargeRemainder";

    private BatteryCharge() {}

    static BigDecimal capacity(NBTTagCompound data) {
        BigDecimal exact = read(data, "CapacityExact");
        if (exact != null) return exact;
        // Older monitors rounded GT's long capacity through the IC2 double facade.
        ItemStack battery = ItemStack.loadItemStackFromNBT(data.getCompoundTag("Item"));
        if (battery != null && battery.getItem() instanceof MetaBaseItem) return nativeCapacity(battery);
        return value(data.getDouble("Capacity"));
    }

    static BigDecimal get(NBTTagCompound data) {
        BigDecimal exact = read(data, "ChargeExact");
        return (exact == null ? value(data.getDouble("Charge")) : exact).min(capacity(data));
    }

    static BigDecimal missing(NBTTagCompound data) {
        return capacity(data).subtract(get(data));
    }

    static void initialize(NBTTagCompound data, BigDecimal capacity, BigDecimal charge) {
        write(data, "Capacity", capacity);
        set(data, charge);
    }

    static void set(NBTTagCompound data, BigDecimal charge) {
        BigDecimal capacity = capacity(data);
        write(data, "Capacity", capacity);
        write(data, "Charge", charge.max(BigDecimal.ZERO).min(capacity));
    }

    static BigDecimal nativeCapacity(ItemStack battery) {
        if (battery.getItem() instanceof MetaBaseItem) {
            Long[] stats = ((MetaBaseItem) battery.getItem()).getElectricStats(battery);
            return stats == null ? BigDecimal.ZERO : BigDecimal.valueOf(stats[0]).abs();
        }
        return value(((ic2.api.item.IElectricItem) battery.getItem()).getMaxCharge(battery));
    }

    static BigDecimal nativeCharge(ItemStack battery) {
        return battery.getItem() instanceof MetaBaseItem
            ? BigDecimal.valueOf(((MetaBaseItem) battery.getItem()).getRealCharge(battery)).max(BigDecimal.ZERO)
            : value(MonitorBattery.manager(battery).getCharge(battery));
    }

    static BigDecimal remainder(ItemStack battery) {
        BigDecimal remainder = battery.hasTagCompound() ? read(battery.getTagCompound(), REMAINDER) : null;
        return remainder == null ? BigDecimal.ZERO : remainder;
    }

    static void remainder(ItemStack battery, BigDecimal remainder) {
        if (remainder.signum() > 0) {
            if (!battery.hasTagCompound()) battery.setTagCompound(new NBTTagCompound());
            battery.getTagCompound().setString(REMAINDER, remainder.toPlainString());
        } else if (battery.hasTagCompound()) battery.getTagCompound().removeTag(REMAINDER);
    }

    /** Preserve the exact value of an API double, rather than its rounded decimal rendering. */
    static BigDecimal value(double value) {
        return Double.isFinite(value) && value > 0 ? new BigDecimal(value) : BigDecimal.ZERO;
    }

    /** An offer must not exceed the exact balance. Any rounding remainder stays in the persisted balance. */
    static double available(BigDecimal value) {
        double projected = value.doubleValue();
        return new BigDecimal(projected).compareTo(value) > 0 ? Math.nextDown(projected) : projected;
    }

    private static void write(NBTTagCompound data, String key, BigDecimal value) {
        data.setString(key + "Exact", value.toPlainString());
        data.setDouble(key, value.doubleValue());
    }

    private static BigDecimal read(NBTTagCompound data, String key) {
        if (!data.hasKey(key, 8)) return null;
        String text = data.getString(key);
        // Our writer emits plain decimal values, bounded by finite double precision/range.
        if (text.length() > 1400 || text.indexOf('E') >= 0 || text.indexOf('e') >= 0) return null;
        try {
            BigDecimal value = new BigDecimal(text);
            return value.signum() >= 0 && Double.isFinite(value.doubleValue()) ? value : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
