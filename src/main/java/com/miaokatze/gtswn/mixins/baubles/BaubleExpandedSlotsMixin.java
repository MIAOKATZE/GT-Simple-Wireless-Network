// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.mixins.baubles;

import org.apache.logging.log4j.LogManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import com.miaokatze.gtswn.common.baubles.TerminalBaubleSlotPolicy;
import com.miaokatze.gtswn.common.util.DebugTestGate;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.LoaderState;

/** Every INIT slot override retains existing order plus the terminal minimum capacity. */
@Pseudo
@Mixin(targets = "baubles.api.expanded.BaubleExpandedSlots", remap = false)
public abstract class BaubleExpandedSlotsMixin {

    @ModifyVariable(method = "overrideSlots", at = @At("HEAD"), argsOnly = true, remap = false)
    private static String[] gtswn$preserveTerminalCapacity(String[] assignments) {
        if (Loader.instance()
            .getLoaderState() != LoaderState.INITIALIZATION) return assignments;
        String[] merged = TerminalBaubleSlotPolicy.withRequiredSlots(assignments);
        if (DebugTestGate.isEnabled())
            com.miaokatze.gtswn.common.diagnostics.SlotDiagnostics.observe(assignments, merged);
        LogManager.getLogger("GTSWN")
            .debug(
                "[GTSWN] Baubles INIT manual/slot override applied: retained {} existing slots; append count {} (terminal minimum 1/1/1/5)",
                assignments.length,
                merged.length - assignments.length);
        return merged;
    }
}
