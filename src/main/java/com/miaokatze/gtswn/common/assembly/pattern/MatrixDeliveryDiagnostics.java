// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.common.assembly.pattern;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fluids.FluidStack;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.util.concurrent.RateLimiter;
import com.miaokatze.gtswn.main.GTSimpleWirelessNetwork;

import appeng.api.storage.data.IAEFluidStack;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEStack;
import appeng.util.inv.MEInventoryCrafting;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ModContainer;

/** Read-only rejection details for diagnosing the provider that built the delivery table. */
final class MatrixDeliveryDiagnostics {

    private static final boolean ENABLED = Boolean.getBoolean("gtswn.matrix.debugDelivery");
    private static final int MAX_SLOTS = 24;
    private static final int MAX_CALLERS = 20;

    private MatrixDeliveryDiagnostics() {}

    private static final class Recent {

        private static final Cache<String, Boolean> LOGGED = CacheBuilder.newBuilder()
            .maximumSize(128)
            .expireAfterWrite(30, TimeUnit.SECONDS)
            .build();
        private static final RateLimiter LIMIT = RateLimiter.create(1.0);

        private static synchronized boolean take(String key) {
            if (LOGGED.getIfPresent(key) != null || !LIMIT.tryAcquire()) return false;
            LOGGED.put(key, Boolean.TRUE);
            return true;
        }
    }

    static void log(OrderedPatternLayout layout, InventoryCrafting table, PatternValidationException rejection) {
        if (!ENABLED) return;
        try {
            String encoding = tagHash(
                layout.pattern()
                    .getTagCompound());
            int actualSize = table.getSizeInventory();
            String key = encoding + '/' + actualSize + '/' + rejection.getMessage();
            if (!Recent.take(key)) return;

            StackTraceElement[] trace = rejection.getStackTrace();
            ModContainer neoEco = Loader.instance()
                .getIndexedModList()
                .get("neoecoae");
            boolean verifiedFastDelivery = Loader.isModLoaded("neoecoae") && NeoEcoCondensedDelivery.matches(trace);
            GTSimpleWirelessNetwork.LOG.warn(
                "[Matrix delivery] {} side={} neoeco={} fastDelivery={} pattern={} details={} table={} raw={} nonEmpty={} actual={} expectedSlots={} actualSlots={} callers={}",
                rejection.getMessage(),
                FMLCommonHandler.instance()
                    .getSide(),
                neoEco == null ? "absent" : neoEco.getVersion(),
                verifiedFastDelivery,
                encoding,
                layout.details()
                    .getClass()
                    .getName(),
                table.getClass()
                    .getName(),
                layout.rawInputs()
                    .size(),
                layout.items()
                    .size()
                    + layout.fluids()
                        .size(),
                actualSize,
                expectedSlots(layout.rawInputs()),
                actualSlots((MEInventoryCrafting) table),
                callers(trace));
        } catch (RuntimeException failure) {
            GTSimpleWirelessNetwork.LOG.debug("Could not capture matrix delivery diagnostics", failure);
        }
    }

    static List<String> expectedSlots(List<OrderedPatternLayout.Input> inputs) {
        List<String> slots = new ArrayList<>();
        for (OrderedPatternLayout.Input input : inputs) {
            if (input.kind() != OrderedPatternLayout.Kind.EMPTY) {
                slots.add(input.rawIndex() + "=" + resource(input.itemStack(), input.fluidStack(), input.amount()));
            }
        }
        return slots;
    }

    static List<String> actualSlots(MEInventoryCrafting table) {
        List<String> slots = new ArrayList<>();
        for (int i = 0; i < Math.min(table.getSizeInventory(), MAX_SLOTS); i++) {
            IAEStack<?> stack = table.getAEStackInSlot(i);
            if (stack instanceof IAEItemStack item)
                slots.add(resource(item.getItemStack(), null, stack.getStackSize()));
            else if (stack instanceof IAEFluidStack fluid)
                slots.add(resource(null, fluid.getFluidStack(), stack.getStackSize()));
            else if (stack == null) {
                ItemStack nativeStack = table.getStackInSlot(i);
                slots.add(
                    nativeStack == null ? "empty" : "native/" + resource(nativeStack, null, nativeStack.stackSize));
            } else slots.add(
                stack.getClass()
                    .getName() + " x"
                    + stack.getStackSize());
        }
        return slots;
    }

    private static String resource(ItemStack item, FluidStack fluid, long amount) {
        if (item != null) return "item/" + Item.itemRegistry.getNameForObject(
            item.getItem()) + '@' + item.getItemDamage() + '#' + tagHash(item.getTagCompound()) + " x" + amount;
        if (fluid != null) return "fluid/" + fluid.getFluid()
            .getName() + '#' + tagHash(fluid.tag) + " x" + amount;
        return "empty";
    }

    private static String tagHash(NBTTagCompound tag) {
        return tag == null || tag.hasNoTags() ? "-" : Integer.toHexString(tag.hashCode());
    }

    private static List<String> callers(StackTraceElement[] trace) {
        List<String> callers = new ArrayList<>();
        for (int i = 0; i < Math.min(trace.length, MAX_CALLERS); i++) {
            callers.add(trace[i].getClassName() + '.' + trace[i].getMethodName());
        }
        return callers;
    }
}
