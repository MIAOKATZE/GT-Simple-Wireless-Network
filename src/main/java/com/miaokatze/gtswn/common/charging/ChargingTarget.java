package com.miaokatze.gtswn.common.charging;

import net.minecraft.item.ItemStack;

import gregtech.api.enums.GTValues;
import ic2.api.item.IElectricItemManager;

/** One tick's target snapshot: manager lookup and unsuccessful probes are shared between terminals. */
final class ChargingTarget {

    final ItemStack stack;
    private final IElectricItemManager manager;
    private final int ticks;
    private final double[] rejected = new double[GTValues.V.length];
    private boolean charged;

    ChargingTarget(ItemStack stack, int ticks) {
        this.stack = stack;
        this.manager = MonitorBattery.manager(stack);
        this.ticks = ticks;
    }

    double charge(double cache, int tier) {
        if (charged || manager == null || tier < 0 || tier >= rejected.length || cache <= 0) return 0;
        double offered = Math.min(cache, GTValues.V[tier]);
        if (offered <= rejected[tier]) return 0;
        double received;
        if (ticks == 1) {
            // Same manager dispatch and actual-result accounting as GT's Wireless Charger, with normal tick limits.
            received = manager.charge(stack, offered, tier, false, false);
        } else {
            // Probe one normal tick, including special managers' own tier and transfer restrictions.
            double accepted = manager.charge(stack, offered, tier, false, true);
            if (accepted <= 0 || !Double.isFinite(accepted)) {
                rejected[tier] = offered;
                return 0;
            }
            received = manager.charge(stack, Math.min(cache, Math.min(offered, accepted) * ticks), tier, true, false);
        }
        if (received <= 0 || !Double.isFinite(received)) {
            rejected[tier] = offered;
            return 0;
        }
        charged = true;
        return received;
    }
}
