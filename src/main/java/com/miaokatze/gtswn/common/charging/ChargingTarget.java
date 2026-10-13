package com.miaokatze.gtswn.common.charging;

import net.minecraft.item.ItemStack;

import gregtech.api.enums.GTValues;

/** One tick's target snapshot: manager lookup and unsuccessful probes are shared between terminals. */
final class ChargingTarget {

    final ItemStack stack;
    private final ItemCharging.Adapter adapter;
    private final int ticks;
    private final double[] rejected = new double[GTValues.V.length];
    private boolean charged;

    ChargingTarget(ItemStack stack, int ticks) {
        this(stack, ticks, ItemCharging.adapter(stack));
    }

    ChargingTarget(ItemStack stack, int ticks, ItemCharging.Adapter adapter) {
        this.stack = stack;
        this.adapter = adapter;
        this.ticks = ticks;
    }

    double charge(double cache, int tier) {
        if (charged || adapter == null || tier < 0 || tier >= rejected.length || !Double.isFinite(cache) || cache <= 0)
            return 0;
        double offered = Math.min(cache, GTValues.V[tier]);
        if (offered <= rejected[tier]) return 0;
        double received;
        if (ticks == 1) {
            // Each adapter returns the actual EU accepted, within the normal one-tick offer.
            received = adapter.charge(stack, offered, tier, 1, false);
        } else {
            // Probe one normal tick, including special managers' own tier and transfer restrictions.
            double accepted = adapter.charge(stack, offered, tier, 1, true);
            if (accepted <= 0 || !Double.isFinite(accepted)) {
                rejected[tier] = offered;
                return 0;
            }
            received = adapter.charge(stack, Math.min(cache, Math.min(offered, accepted) * ticks), tier, ticks, false);
        }
        if (received <= 0 || !Double.isFinite(received)) {
            rejected[tier] = offered;
            return 0;
        }
        charged = true;
        return received;
    }
}
