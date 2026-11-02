package com.miaokatze.gtswn.mixins.gregtech;

import java.util.ArrayList;
import java.util.Map;

import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.miaokatze.gtswn.assembly.nativeintegration.NativeAssemblyController;
import com.miaokatze.gtswn.assembly.nativeintegration.NativeAssemblySession;

import gregtech.api.metatileentity.implementations.MTEHatchInput;
import gregtech.api.metatileentity.implementations.MTEHatchInputBus;
import gregtech.api.recipe.check.CheckRecipeResult;
import gregtech.api.recipe.check.CheckRecipeResultRegistry;
import gregtech.api.util.GTRecipe.RecipeAssemblyLine;
import gregtech.api.util.GTUtility;
import gregtech.common.tileentities.machines.multi.MTEAssemblyLine;

/** Pinned .265 call sites: every required native operation has exactly one target. */
@Mixin(value = MTEAssemblyLine.class, remap = false)
public abstract class OrderedAssemblyControllerMixin implements NativeAssemblyController {

    @Unique
    private NativeAssemblySession gtswn$orderedSession;

    @Override
    public NativeAssemblySession orderedAssemblySession() {
        if (gtswn$orderedSession == null) {
            gtswn$orderedSession = new NativeAssemblySession((MTEAssemblyLine) (Object) this);
        }
        return gtswn$orderedSession;
    }

    @Inject(
        method = "checkProcessing()Lgregtech/api/recipe/check/CheckRecipeResult;",
        at = @At("HEAD"),
        cancellable = true,
        require = 1,
        expect = 1,
        allow = 1)
    private void gtswn$isolateInputs(CallbackInfoReturnable<CheckRecipeResult> cir) {
        if (!orderedAssemblySession().beginCheck()) cir.setReturnValue(CheckRecipeResultRegistry.NO_RECIPE);
    }

    @Redirect(
        method = "checkProcessing()Lgregtech/api/recipe/check/CheckRecipeResult;",
        at = @At(
            value = "INVOKE",
            target = "Lgregtech/api/util/GTRecipe$RecipeAssemblyLine;getItemConsumptionAmountArray(Ljava/util/ArrayList;Lgregtech/api/util/GTRecipe$RecipeAssemblyLine;)[I"),
        require = 1,
        expect = 1,
        allow = 1)
    private int[] gtswn$gateCandidate(ArrayList<MTEHatchInputBus> buses, RecipeAssemblyLine recipe) {
        return orderedAssemblySession().matchCandidate(buses, recipe);
    }

    @Redirect(
        method = "checkProcessing()Lgregtech/api/recipe/check/CheckRecipeResult;",
        at = @At(
            value = "INVOKE",
            target = "Lgregtech/api/util/GTRecipe$RecipeAssemblyLine;maxParallelCalculatedByInputItems(Ljava/util/ArrayList;I[ILjava/util/Map;)D"),
        require = 1,
        expect = 1,
        allow = 1)
    private double gtswn$readRealItemPositions(ArrayList<MTEHatchInputBus> buses, int maximum, int[] requirements,
        Map<GTUtility.ItemId, ItemStack> meInputs) {
        return orderedAssemblySession().maxParallelItems(buses, maximum, requirements, meInputs);
    }

    @Redirect(
        method = "checkProcessing()Lgregtech/api/recipe/check/CheckRecipeResult;",
        at = @At(
            value = "INVOKE",
            target = "Lgregtech/api/util/GTRecipe$RecipeAssemblyLine;maxParallelCalculatedByInputFluids(Ljava/util/ArrayList;I[Lnet/minecraftforge/fluids/FluidStack;Ljava/util/Map;)D"),
        require = 1,
        expect = 1,
        allow = 1)
    private double gtswn$readRealFluidPositions(ArrayList<MTEHatchInput> hatches, int maximum,
        FluidStack[] requirements, Map<Fluid, FluidStack> meInputs) {
        return orderedAssemblySession().maxParallelFluids(hatches, maximum, requirements, meInputs);
    }

    @Redirect(
        method = "checkProcessing()Lgregtech/api/recipe/check/CheckRecipeResult;",
        at = @At(
            value = "INVOKE",
            target = "Lgregtech/api/util/GTRecipe$RecipeAssemblyLine;consumeInputItems(Ljava/util/ArrayList;I[ILjava/util/Map;)V"),
        require = 1,
        expect = 1,
        allow = 1)
    private void gtswn$observeItemConsumption(ArrayList<MTEHatchInputBus> buses, int parallel, int[] requirements,
        Map<GTUtility.ItemId, ItemStack> meInputs) {
        orderedAssemblySession().consumeItems(buses, parallel, requirements, meInputs);
    }

    @Redirect(
        method = "checkProcessing()Lgregtech/api/recipe/check/CheckRecipeResult;",
        at = @At(
            value = "INVOKE",
            target = "Lgregtech/api/util/GTRecipe$RecipeAssemblyLine;consumeInputFluids(Ljava/util/ArrayList;I[Lnet/minecraftforge/fluids/FluidStack;Ljava/util/Map;)V"),
        require = 1,
        expect = 1,
        allow = 1)
    private void gtswn$observeFluidConsumption(ArrayList<MTEHatchInput> hatches, int parallel,
        FluidStack[] requirements, Map<Fluid, FluidStack> meInputs) {
        orderedAssemblySession().consumeFluids(hatches, parallel, requirements, meInputs);
    }
}
