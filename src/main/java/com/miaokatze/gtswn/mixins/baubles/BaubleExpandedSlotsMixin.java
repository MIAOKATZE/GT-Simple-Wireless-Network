package com.miaokatze.gtswn.mixins.baubles;

import org.apache.logging.log4j.LogManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import com.miaokatze.gtswn.common.baubles.TerminalBaubleSlotPolicy;

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
        LogManager.getLogger("GTSWN")
            .info(
                "[GTSWN] Baubles INIT manual/slot override applied: retained {} existing slots; append count {} (terminal minimum 1/1/1/5)",
                assignments.length,
                merged.length - assignments.length);
        return merged;
    }
}
