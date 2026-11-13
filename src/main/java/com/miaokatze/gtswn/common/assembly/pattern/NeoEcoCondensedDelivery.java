// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.common.assembly.pattern;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import cpw.mods.fml.common.Loader;

/** The verified NeoECO 7.3.8 fast-planned native-medium delivery boundary, without an optional API dependency. */
final class NeoEcoCondensedDelivery {

    private static final String MATRIX = "com.miaokatze.gtswn.common.assembly.";
    private static final String CPU = "cn.dancingsnow.neoecoae.crafting.execution.ECOCraftingCPULogic";
    private static final String PROVIDER = "cn.dancingsnow.neoecoae.platform.ae2.crafting.NativeCraftingProvider";

    private NeoEcoCondensedDelivery() {}

    static boolean active() {
        // FML is initialized before crafting. Absence is silent and never captures a stack trace.
        return active(
            () -> Loader.isModLoaded("neoecoae"),
            () -> Thread.currentThread()
                .getStackTrace());
    }

    static boolean active(BooleanSupplier installed, Supplier<StackTraceElement[]> trace) {
        return installed.getAsBoolean() && matches(trace.get());
    }

    static boolean matches(StackTraceElement[] trace) {
        int index = 0;
        while (index < trace.length && !frame(trace[index], OrderedPatternLayout.class.getName(), "validateTable"))
            index++;
        // The exact contiguous boundary binds this particular table to the native provider's current push.
        // Forwarders and nested ordinary deliveries must not inherit authority from an older ancestor.
        if (++index + 4 >= trace.length || !frame(trace[index++], MATRIX + "OrderedAssemblyGroup", "acceptInstalled")
            || !frame(trace[index++], MATRIX + "OrderedAssemblyGroup", "accept")
            || !frame(trace[index++], MATRIX + "MatrixAssemblyPort", "pushPattern")) return false;
        StackTraceElement member = trace[index++];
        if (!frame(member, MATRIX + "MatrixItemInputBus", "pushPattern")
            && !frame(member, MATRIX + "MatrixFluidInputHatch", "pushPattern")) return false;
        if (!frame(trace[index++], PROVIDER, "pushPattern") || index >= trace.length
            || !frame(trace[index++], CPU, "invokeNormalProvider")) return false;
        // Fast and ordinary NeoECO plans share native pattern classes. Only fast-planned jobs enter this scheduler;
        // vanillaExecution branches to ECOVanillaCraftingExecutor before executeNormalCrafting is reached.
        for (; index < trace.length; index++) {
            StackTraceElement caller = trace[index];
            if (frame(caller, CPU, "executeNormalCrafting")) {
                return index + 2 < trace.length && frame(trace[index + 1], CPU, "executeCrafting")
                    && frame(trace[index + 2], CPU, "tickCraftingLogic");
            }
            if (caller.getClassName()
                .equals(PROVIDER)
                || caller.getClassName()
                    .equals(CPU))
                return false;
        }
        return false;
    }

    private static boolean frame(StackTraceElement frame, String owner, String method) {
        return frame.getClassName()
            .equals(owner)
            && frame.getMethodName()
                .equals(method);
    }
}
