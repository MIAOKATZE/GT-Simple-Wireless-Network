package com.miaokatze.gtswn.common.charging;

import java.math.BigDecimal;
import java.util.List;

import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemTool;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.StatCollector;
import net.minecraftforge.oredict.OreDictionary;

import com.miaokatze.gtswn.common.items.PortableWirelessNetworkMonitor;

import gregtech.api.enums.GTValues;
import gregtech.api.items.MetaBaseItem;
import ic2.api.item.ElectricItem;
import ic2.api.item.IElectricItem;
import ic2.api.item.IElectricItemManager;
import ic2.api.item.ISpecialElectricItem;

/** The installed battery and its live cache travel with the single registered monitor item. */
public final class MonitorBattery {

    public static final String NBT_BATTERY = "GTSWNBattery";

    private MonitorBattery() {}

    public static boolean hasBattery(ItemStack stack) {
        return stack != null && stack.getItem() instanceof PortableWirelessNetworkMonitor
            && stack.hasTagCompound()
            && stack.getTagCompound()
                .hasKey(NBT_BATTERY, 10);
    }

    public static NBTTagCompound data(ItemStack stack) {
        return stack.getTagCompound()
            .getCompoundTag(NBT_BATTERY);
    }

    public static IElectricItemManager manager(ItemStack stack) {
        if (stack == null) return null;
        if (stack.getItem() instanceof ISpecialElectricItem)
            return ((ISpecialElectricItem) stack.getItem()).getManager(stack);
        return stack.getItem() instanceof IElectricItem ? ElectricItem.manager : null;
    }

    public static boolean isBattery(ItemStack stack) {
        if (stack == null || stack.stackSize <= 0
            || !(stack.getItem() instanceof IElectricItem)
            || stack.getItem() instanceof ItemTool
            || stack.getItem() instanceof ItemArmor) return false;
        stack = stack.copy();
        stack.stackSize = 1;
        IElectricItem electric = (IElectricItem) stack.getItem();
        if (manager(stack) == null || !electric.canProvideEnergy(stack)
            || !Double.isFinite(electric.getMaxCharge(stack))
            || electric.getMaxCharge(stack) <= 0
            || electric.getTier(stack) < 0
            || electric.getTier(stack) >= GTValues.V.length) return false;
        ItemStack probe = stack.copy();
        IElectricItemManager probeManager = manager(probe);
        probeManager.discharge(probe, Double.MAX_VALUE, Integer.MAX_VALUE, true, false, false);
        if (probeManager.charge(probe, 1, Integer.MAX_VALUE, true, true) <= 0) return false;
        if (stack.getItem() instanceof MetaBaseItem) {
            Long[] stats = ((MetaBaseItem) stack.getItem()).getElectricStats(stack);
            if (stats == null || stats[3] != -3) return false;
            for (int id : OreDictionary.getOreIDs(stack)) {
                if (OreDictionary.getOreName(id)
                    .startsWith("battery")) return true;
            }
            return false;
        }
        // IC2's rechargeable battery family; armour and energy tools cannot qualify by interface alone.
        return stack.getItem()
            .getClass()
            .getName()
            .startsWith("ic2.")
            && stack.getItem()
                .getClass()
                .getSimpleName()
                .contains("Battery");
    }

    public static ItemStack install(ItemStack monitor, ItemStack battery) {
        ItemStack result = monitor.copy();
        if (!result.hasTagCompound()) result.setTagCompound(new NBTTagCompound());
        NBTTagCompound data = new NBTTagCompound();
        ItemStack installed = battery.copy();
        installed.stackSize = 1;
        IElectricItem electric = (IElectricItem) installed.getItem();
        BigDecimal charge = BatteryCharge.nativeCharge(installed)
            .add(BatteryCharge.remainder(installed));
        BigDecimal capacity = BatteryCharge.nativeCapacity(installed);
        // A native charger may have filled the battery while its fractional escrow was attached.
        // Keep any excess on the nested physical item; do not silently discard it at the capacity clamp.
        BatteryCharge.remainder(installed, charge.subtract(capacity));
        data.setTag("Item", installed.writeToNBT(new NBTTagCompound()));
        data.setInteger("Tier", electric.getTier(installed));
        BatteryCharge.initialize(data, capacity, charge);
        data.setInteger(
            "Status",
            BatteryCharge.get(data)
                .signum() > 0 ? 1 : 0);
        result.getTagCompound()
            .setTag(NBT_BATTERY, data);
        return result;
    }

    public static ItemStack remove(ItemStack monitor) {
        if (!hasBattery(monitor)) return null;
        NBTTagCompound data = data(monitor);
        ItemStack battery = ItemStack.loadItemStackFromNBT(data.getCompoundTag("Item"));
        if (battery == null || manager(battery) == null) return null;
        // loadItemStackFromNBT retains the nested tag reference; detach before writing charge.
        battery = battery.copy();
        BigDecimal charge = BatteryCharge.get(data);
        if (battery.getItem() instanceof MetaBaseItem) {
            // GT stores long EU. Its double facade can round up even after an exact cache debit.
            ((MetaBaseItem) battery.getItem()).setCharge(battery, charge.longValue());
        } else {
            IElectricItemManager manager = manager(battery);
            manager.discharge(battery, Double.MAX_VALUE, Integer.MAX_VALUE, true, false, false);
            manager.charge(battery, BatteryCharge.available(charge), Integer.MAX_VALUE, true, false);
        }
        // Native GT cannot hold fractional EU; foreign double storage may also round down. Keep it
        // on the physical battery for reinstalling, not in the monitor that is about to lose its battery.
        BatteryCharge.remainder(
            battery,
            charge.subtract(BatteryCharge.nativeCharge(battery))
                .add(BatteryCharge.remainder(battery)));
        return battery;
    }

    public static ItemStack withoutBattery(ItemStack monitor) {
        if (!hasBattery(monitor)) return null;
        ItemStack result = monitor.copy();
        result.getTagCompound()
            .removeTag(NBT_BATTERY);
        return result;
    }

    public static int getStatus(ItemStack stack) {
        if (!hasBattery(stack) || BatteryCharge.get(data(stack))
            .signum() <= 0) return 0;
        return data(stack).getInteger("Status");
    }

    public static void addTooltip(ItemStack stack, List<String> lines) {
        addStateTooltip(stack, lines);
        addDescriptionTooltip(lines);
        addControlsTooltip(lines);
    }

    public static void addStateTooltip(ItemStack stack, List<String> lines) {
        if (hasBattery(stack)) {
            NBTTagCompound data = data(stack);
            ItemStack battery = ItemStack.loadItemStackFromNBT(data.getCompoundTag("Item"));
            if (battery != null) lines.add(
                StatCollector
                    .translateToLocalFormatted("gtswn.tooltip.monitor.battery.name", battery.getDisplayName()));
        } else {
            lines.add(StatCollector.translateToLocal("gtswn.tooltip.monitor.battery.none"));
        }
    }

    public static void addDescriptionTooltip(List<String> lines) {
        for (String suffix : new String[] { "refill", "targets" }) {
            lines.add(StatCollector.translateToLocal("gtswn.tooltip.monitor.charging." + suffix));
        }
    }

    public static void addControlsTooltip(List<String> lines) {
        for (String suffix : new String[] { "install", "replace", "remove" }) {
            lines.add(StatCollector.translateToLocal("gtswn.tooltip.monitor.charging." + suffix));
        }
    }
}
