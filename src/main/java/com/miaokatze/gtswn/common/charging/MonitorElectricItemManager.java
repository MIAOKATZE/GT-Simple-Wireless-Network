package com.miaokatze.gtswn.common.charging;

import static com.gtnewhorizon.gtnhlib.util.numberformatting.NumberFormatUtil.formatNumber;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.StatCollector;

import gregtech.api.enums.GTValues;
import gregtech.api.items.MetaBaseItem;
import ic2.api.item.IElectricItem;
import ic2.api.item.IElectricItemManager;

/** IC2 facade over the terminal's authoritative cache, never a second charge NBT store. */
public final class MonitorElectricItemManager implements IElectricItemManager {

    public static final MonitorElectricItemManager INSTANCE = new MonitorElectricItemManager();

    private MonitorElectricItemManager() {}

    @Override
    public double charge(ItemStack stack, double amount, int tier, boolean ignoreLimit, boolean simulate) {
        if (!MonitorBattery.hasBattery(stack) || !(amount > 0) || tier < electric(stack).getTier(stack)) return 0;
        double accepted = Math.min(amount, BatteryCharge.available(BatteryCharge.missing(MonitorBattery.data(stack))));
        if (!ignoreLimit) accepted = Math.min(accepted, electric(stack).getTransferLimit(stack));
        if (!Double.isFinite(accepted) || accepted <= 0) return 0;
        if (!simulate) {
            BatteryCharge.set(
                MonitorBattery.data(stack),
                BatteryCharge.get(MonitorBattery.data(stack)).add(BatteryCharge.value(accepted)));
            if (MonitorBattery.data(stack)
                .getInteger("Status") == 0)
                MonitorBattery.data(stack)
                    .setInteger("Status", 1);
        }
        return accepted;
    }

    @Override
    public double discharge(ItemStack stack, double amount, int tier, boolean ignoreLimit, boolean external,
        boolean simulate) {
        if (external || !MonitorBattery.hasBattery(stack) || !(amount > 0) || tier < electric(stack).getTier(stack))
            return 0;
        double used = Math.min(amount, BatteryCharge.available(BatteryCharge.get(MonitorBattery.data(stack))));
        if (!ignoreLimit) used = Math.min(used, electric(stack).getTransferLimit(stack));
        if (!Double.isFinite(used) || used <= 0) return 0;
        if (!simulate) BatteryCharge.set(
            MonitorBattery.data(stack),
            BatteryCharge.get(MonitorBattery.data(stack)).subtract(BatteryCharge.value(used)));
        return used;
    }

    @Override
    public double getCharge(ItemStack stack) {
        if (!MonitorBattery.hasBattery(stack)) return 0;
        return BatteryCharge.available(BatteryCharge.get(MonitorBattery.data(stack)));
    }

    @Override
    public boolean canUse(ItemStack stack, double amount) {
        return MonitorBattery.hasBattery(stack) && Double.isFinite(amount) && amount >= 0
            && BatteryCharge.get(MonitorBattery.data(stack)).compareTo(BatteryCharge.value(amount)) >= 0;
    }

    @Override
    public boolean use(ItemStack stack, double amount, EntityLivingBase entity) {
        if (!canUse(stack, amount)) return false;
        discharge(stack, amount, Integer.MAX_VALUE, true, false, false);
        return true;
    }

    @Override
    public void chargeFromArmor(ItemStack stack, EntityLivingBase entity) {}

    @Override
    public String getToolTip(ItemStack stack) {
        // The item appends its own native line, following MetaBaseItem's duplicate prevention.
        return null;
    }

    public static String getChargeTooltip(ItemStack stack) {
        // Delegate the exact GT/IC2 formatting to the installed battery's native manager.
        ItemStack battery = MonitorBattery.remove(stack);
        IElectricItemManager manager = MonitorBattery.manager(battery);
        if (battery != null && battery.getItem() instanceof MetaBaseItem) {
            Long[] stats = ((MetaBaseItem) battery.getItem()).getElectricStats(battery);
            if (stats == null) return null;
            int tier = (int) Math.max(0, Math.min(GTValues.V.length - 1, stats[2]));
            return EnumChatFormatting.AQUA + StatCollector.translateToLocalFormatted(
                "gt.item.desc.eu_info",
                formatNumber(((MetaBaseItem) battery.getItem()).getRealCharge(battery)),
                formatNumber(Math.abs(stats[0])),
                formatNumber(GTValues.V[tier])) + EnumChatFormatting.GRAY;
        }
        return manager == null ? null : manager.getToolTip(battery);
    }

    private static IElectricItem electric(ItemStack stack) {
        return (IElectricItem) stack.getItem();
    }
}
