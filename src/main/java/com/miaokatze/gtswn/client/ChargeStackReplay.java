package com.miaokatze.gtswn.client;

import java.lang.reflect.Method;

import net.minecraft.item.ItemStack;

import appeng.api.config.AccessRestriction;
import appeng.api.implementations.items.IAEItemPowerStorage;

/** Recognize charge updates by reproducing them on a copy, without guessing third-party NBT keys. */
final class ChargeStackReplay {

    private ChargeStackReplay() {}

    static boolean matches(ItemStack current, ItemStack incoming) {
        try {
            if (current.getItem() instanceof IAEItemPowerStorage) return matchesAe(current, incoming);
            return Rf.matches(current, incoming);
        } catch (RuntimeException | LinkageError ignored) {
            // An unavailable or failing external API must preserve ordinary authoritative stack replacement.
            return false;
        }
    }

    private static boolean matchesAe(ItemStack current, ItemStack incoming) {
        IAEItemPowerStorage item = (IAEItemPowerStorage) current.getItem();
        ItemStack before = current.copy();
        ItemStack after = incoming.copy();
        double currentPower = item.getAECurrentPower(before);
        double incomingPower = item.getAECurrentPower(after);
        if (!Double.isFinite(currentPower) || !Double.isFinite(incomingPower)
            || currentPower < 0
            || incomingPower < 0
            || currentPower == incomingPower) return false;
        ItemStack lower = currentPower < incomingPower ? before : after;
        ItemStack higher = currentPower < incomingPower ? incoming : current;
        if (!item.getPowerFlow(lower)
            .hasPermission(AccessRestriction.WRITE)) return false;
        double remainder = item.injectAEPower(lower, Math.abs(incomingPower - currentPower));
        return remainder == 0 && ItemStack.areItemStacksEqual(lower, higher);
    }

    /** CoFH remains optional; public API methods are discovered once and all calls use detached copies. */
    static final class Rf {

        private static final int MAX_CALLS = 20;
        private static final Class<?> API;
        private static final Method STORED;
        private static final Method RECEIVE;

        static {
            Class<?> api = null;
            Method stored = null;
            Method receive = null;
            try {
                api = Class.forName("cofh.api.energy.IEnergyContainerItem");
                stored = api.getMethod("getEnergyStored", ItemStack.class);
                receive = api.getMethod("receiveEnergy", ItemStack.class, int.class, boolean.class);
            } catch (ReflectiveOperationException | LinkageError ignored) {
                api = null;
            }
            API = api;
            STORED = stored;
            RECEIVE = receive;
        }

        private Rf() {}

        static boolean matches(ItemStack current, ItemStack incoming) {
            if (API == null || !API.isInstance(current.getItem())) return false;
            try {
                ItemStack before = current.copy();
                ItemStack after = incoming.copy();
                int currentPower = stored(before);
                int incomingPower = stored(after);
                if (currentPower < 0 || incomingPower < 0 || currentPower == incomingPower) return false;
                ItemStack lower = currentPower < incomingPower ? before : after;
                ItemStack higher = currentPower < incomingPower ? incoming : current;
                int remaining = Math.abs(incomingPower - currentPower);
                // Replay a bounded number of normal transfers when a packet combines several ticks.
                for (int call = 0; call < MAX_CALLS && remaining > 0; call++) {
                    int previous = stored(lower);
                    int accepted = (Integer) RECEIVE.invoke(lower.getItem(), lower, remaining, false);
                    if (accepted <= 0 || accepted > remaining || (long) stored(lower) - previous != accepted)
                        return false;
                    remaining -= accepted;
                }
                return remaining == 0 && ItemStack.areItemStacksEqual(lower, higher);
            } catch (ReflectiveOperationException ignored) {
                return false;
            }
        }

        private static int stored(ItemStack stack) throws ReflectiveOperationException {
            return (Integer) STORED.invoke(stack.getItem(), stack);
        }
    }
}
