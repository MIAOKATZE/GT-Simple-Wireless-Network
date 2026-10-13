package com.miaokatze.gtswn.common.charging;

import java.lang.reflect.Method;

import net.minecraft.item.ItemStack;

import appeng.api.config.AccessRestriction;
import appeng.api.config.PowerUnits;
import appeng.api.implementations.items.IAEItemPowerStorage;
import cpw.mods.fml.common.Loader;
import gregtech.api.GregTechAPI;
import gregtech.api.enums.GTValues;
import ic2.api.item.IElectricItem;
import ic2.api.item.IElectricItemManager;
import ic2.api.item.ISpecialElectricItem;

/** Target energy APIs, expressed in EU; battery installation still uses only GT/IC2 batteries. */
final class ItemCharging {

    interface Adapter {

        double charge(ItemStack stack, double amount, int tier, int ticks, boolean simulate);
    }

    private ItemCharging() {}

    static boolean supports(ItemStack stack) {
        return stack != null
            && (stack.getItem() instanceof IAEItemPowerStorage || stack.getItem() instanceof IElectricItem
                || stack.getItem() instanceof ISpecialElectricItem
                || Rf.supports(stack) && Loader.isModLoaded("CoFHCore"));
    }

    static Adapter adapter(ItemStack stack) {
        if (stack != null && stack.getItem() instanceof IAEItemPowerStorage) return ItemCharging::chargeAe;
        return adapter(stack, stack != null && Rf.supports(stack) && Loader.isModLoaded("CoFHCore"));
    }

    static Adapter adapter(ItemStack stack, boolean rfLoaded) {
        if (stack == null) return null;
        // AE's native API survives optional IC2/RF interface stripping and avoids their integer rounding.
        if (stack.getItem() instanceof IAEItemPowerStorage) return ItemCharging::chargeAe;
        // Match GT's Wireless Charger preference for RF on other items implementing both energy APIs.
        if (rfLoaded && Rf.supports(stack)) return Rf::charge;
        IElectricItemManager manager = MonitorBattery.manager(stack);
        return manager == null ? null
            : (target, amount, tier, ticks, simulate) -> manager.charge(target, amount, tier, ticks > 1, simulate);
    }

    private static double chargeAe(ItemStack stack, double amount, int tier, int ticks, boolean simulate) {
        IAEItemPowerStorage item = (IAEItemPowerStorage) stack.getItem();
        if (!item.getPowerFlow(stack)
            .hasPermission(AccessRestriction.WRITE)) return 0;
        double ratio = PowerUnits.EU.convertTo(PowerUnits.AE, 1);
        if (!Double.isFinite(ratio) || ratio <= 0) return 0;
        double offered = Math.min(amount * ratio, item.getAEMaxPower(stack) - item.getAECurrentPower(stack));
        if (!Double.isFinite(offered) || offered <= 0) return 0;
        if (simulate) return offered / ratio;
        // Unlike IC2/RF, injectAEPower returns the remainder, not the accepted amount.
        double remainder = item.injectAEPower(stack, offered);
        return Double.isFinite(remainder) ? Math.max(0, offered - Math.max(0, remainder)) / ratio : 0;
    }

    /** Cached reflective API detection: no mandatory runtime linkage to CoFH. */
    static final class Rf {

        private static final Class<?> API;
        private static final Method RECEIVE;

        static {
            Class<?> api = null;
            Method receive = null;
            try {
                // The optional CoFH API is not on this project's compile classpath. Cache its public API once.
                api = Class.forName("cofh.api.energy.IEnergyContainerItem");
                receive = api.getMethod("receiveEnergy", ItemStack.class, int.class, boolean.class);
            } catch (ReflectiveOperationException | LinkageError ignored) {
                api = null;
            }
            API = api;
            RECEIVE = receive;
        }

        private Rf() {}

        static boolean supports(ItemStack stack) {
            return API != null && API.isInstance(stack.getItem());
        }

        static double charge(ItemStack stack, double amount, int tier, int ticks, boolean simulate) {
            if (!supports(stack) || GregTechAPI.mEUtoRF <= 0) return 0;
            double ratio = GregTechAPI.mEUtoRF / 100.0;
            int offered = (int) Math.min(Integer.MAX_VALUE, Math.floor(amount * ratio));
            if (offered <= 0) return 0;
            int accepted = 0;
            try {
                int perTick = (int) Math.min(Integer.MAX_VALUE, Math.floor(GTValues.V[tier] * ratio));
                // RF has no ignore-transfer-limit flag. Replay at most one call per batched toolbox tick.
                for (int tick = 0; tick < Math.min(ticks, ToolboxCharging.INTERVAL) && accepted < offered; tick++) {
                    int request = Math.min(offered - accepted, perTick);
                    int received = (Integer) RECEIVE.invoke(stack.getItem(), stack, request, simulate);
                    if (received <= 0) break;
                    accepted += Math.min(request, received);
                    if (simulate) break;
                }
            } catch (ReflectiveOperationException ignored) {
                // Earlier successful batched calls still consume the monitor's cache.
            }
            return accepted / ratio;
        }
    }
}
