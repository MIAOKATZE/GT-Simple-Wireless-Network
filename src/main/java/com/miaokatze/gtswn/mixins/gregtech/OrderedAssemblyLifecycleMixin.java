package com.miaokatze.gtswn.mixins.gregtech;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.miaokatze.gtswn.assembly.nativeintegration.NativeAssemblyAdapter;
import com.miaokatze.gtswn.assembly.nativeintegration.NativeAssemblyController;
import com.miaokatze.gtswn.assembly.nativeintegration.NativeAssemblySession;

import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;
import gregtech.api.recipe.check.CheckRecipeResultRegistry;
import gregtech.api.util.shutdown.ShutDownReason;

/** Observes the native completion before its same-tick checkRecipe, without replacing runMachine. */
@Mixin(value = MTEMultiBlockBase.class, remap = false)
public abstract class OrderedAssemblyLifecycleMixin {

    @Unique
    private NativeAssemblySession gtswn$nativeSession() {
        return NativeAssemblyAdapter.isSupportedController(this)
            ? ((NativeAssemblyController) this).orderedAssemblySession()
            : null;
    }

    @Inject(method = "checkRecipe()Z", at = @At("HEAD"), cancellable = true, require = 1, expect = 1, allow = 1)
    private void gtswn$guardInputCallbacks(CallbackInfoReturnable<Boolean> cir) {
        NativeAssemblySession session = gtswn$nativeSession();
        if (session != null && !session.beginCheck()) {
            ((MTEMultiBlockBase) (Object) this).setResultIfFailure(CheckRecipeResultRegistry.NO_RECIPE);
            cir.setReturnValue(false);
        }
    }

    @Inject(
        method = "checkStructure(ZLgregtech/api/interfaces/tileentity/IGregTechTileEntity;)Z",
        at = @At(
            value = "INVOKE",
            target = "Lgregtech/api/metatileentity/implementations/MTEMultiBlockBase;onStructureCheckFinished(Lgregtech/api/interfaces/tileentity/IGregTechTileEntity;)V",
            shift = At.Shift.AFTER),
        require = 1,
        expect = 1,
        allow = 1)
    private void gtswn$observeStructure(boolean force, IGregTechTileEntity tile, CallbackInfoReturnable<Boolean> cir) {
        NativeAssemblySession session = gtswn$nativeSession();
        if (session != null) session.structureChecked(((MTEMultiBlockBase) (Object) this).mMachine);
    }

    @Redirect(
        method = "runMachine(Lgregtech/api/interfaces/tileentity/IGregTechTileEntity;J)V",
        at = @At(
            value = "INVOKE",
            target = "Lgregtech/api/metatileentity/implementations/MTEMultiBlockBase;addItemOutputs([Lnet/minecraft/item/ItemStack;)Z"),
        require = 1,
        expect = 1,
        allow = 1)
    private boolean gtswn$observeLocalOutput(MTEMultiBlockBase controller, ItemStack[] outputs) {
        boolean accepted = controller.addItemOutputs(outputs);
        NativeAssemblySession session = gtswn$nativeSession();
        if (session != null) session.outputAttempted(accepted);
        return accepted;
    }

    @Inject(
        method = "runMachine(Lgregtech/api/interfaces/tileentity/IGregTechTileEntity;J)V",
        at = @At(
            value = "INVOKE",
            target = "Lgregtech/api/metatileentity/implementations/MTEMultiBlockBase;outputAfterRecipe()V"),
        require = 1,
        expect = 1,
        allow = 1)
    private void gtswn$observeCompletion(IGregTechTileEntity tile, long tick, CallbackInfo ci) {
        NativeAssemblySession session = gtswn$nativeSession();
        if (session != null) session.nativeFinished();
    }

    @Inject(
        method = "stopMachine(Lgregtech/api/util/shutdown/ShutDownReason;)V",
        at = @At("HEAD"),
        require = 1,
        expect = 1,
        allow = 1)
    private void gtswn$observeStop(ShutDownReason reason, CallbackInfo ci) {
        NativeAssemblySession session = gtswn$nativeSession();
        if (session != null) session.nativeStopped(reason.getID());
    }

    @Inject(
        method = "saveNBTData(Lnet/minecraft/nbt/NBTTagCompound;)V",
        at = @At("RETURN"),
        require = 1,
        expect = 1,
        allow = 1)
    private void gtswn$saveIdentity(NBTTagCompound tag, CallbackInfo ci) {
        NativeAssemblySession session = gtswn$nativeSession();
        if (session != null) session.save(tag);
    }

    @Inject(
        method = "loadNBTData(Lnet/minecraft/nbt/NBTTagCompound;)V",
        at = @At("RETURN"),
        require = 1,
        expect = 1,
        allow = 1)
    private void gtswn$loadIdentity(NBTTagCompound tag, CallbackInfo ci) {
        NativeAssemblySession session = gtswn$nativeSession();
        if (session != null) session.load(tag);
    }

    @Inject(method = "onUnload()V", at = @At("HEAD"), require = 1, expect = 1, allow = 1)
    private void gtswn$invalidateUnloadedTopology(CallbackInfo ci) {
        NativeAssemblySession session = gtswn$nativeSession();
        if (session != null) session.structureChecked(false);
    }

    @Inject(method = "onRemoval()V", at = @At("HEAD"), require = 1, expect = 1, allow = 1)
    private void gtswn$invalidateRemovedController(CallbackInfo ci) {
        NativeAssemblySession session = gtswn$nativeSession();
        if (session != null) session.controllerRemoved();
    }
}
