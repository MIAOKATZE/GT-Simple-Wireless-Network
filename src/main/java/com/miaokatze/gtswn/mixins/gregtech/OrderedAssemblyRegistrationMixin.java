package com.miaokatze.gtswn.mixins.gregtech;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.miaokatze.gtswn.assembly.nativeintegration.NativeAssemblyAdapter;
import com.miaokatze.gtswn.assembly.nativeintegration.NativeAssemblyHooks;
import com.miaokatze.gtswn.assembly.nativeintegration.OrderedAssemblyMember;

import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;
import gregtech.common.tileentities.machines.multi.MTEAssemblyLine;

/** Native machines retain their own input adders; the guarded AL additionally requires its safety hooks. */
@Mixin(value = MTEMultiBlockBase.class, remap = false)
public abstract class OrderedAssemblyRegistrationMixin {

    @Unique
    private boolean gtswn$rejectOrderedMember(IGregTechTileEntity tile) {
        return tile != null && tile.getMetaTileEntity() instanceof OrderedAssemblyMember
            && (Object) this instanceof MTEAssemblyLine
            && (!NativeAssemblyAdapter.isSupportedController(this) || !NativeAssemblyHooks.ready());
    }

    @Inject(
        method = "addToMachineList(Lgregtech/api/interfaces/tileentity/IGregTechTileEntity;I)Z",
        at = @At("HEAD"),
        cancellable = true,
        require = 1,
        expect = 1,
        allow = 1)
    private void gtswn$guardGenericAdder(IGregTechTileEntity tile, int casing, CallbackInfoReturnable<Boolean> cir) {
        if (gtswn$rejectOrderedMember(tile)) cir.setReturnValue(false);
    }

    @Inject(
        method = "addInputBusToMachineList(Lgregtech/api/interfaces/tileentity/IGregTechTileEntity;I)Z",
        at = @At("HEAD"),
        cancellable = true,
        require = 1,
        expect = 1,
        allow = 1)
    private void gtswn$guardItemAdder(IGregTechTileEntity tile, int casing, CallbackInfoReturnable<Boolean> cir) {
        if (gtswn$rejectOrderedMember(tile)) cir.setReturnValue(false);
    }

    @Inject(
        method = "addInputHatchToMachineList(Lgregtech/api/interfaces/tileentity/IGregTechTileEntity;I)Z",
        at = @At("HEAD"),
        cancellable = true,
        require = 1,
        expect = 1,
        allow = 1)
    private void gtswn$guardFluidAdder(IGregTechTileEntity tile, int casing, CallbackInfoReturnable<Boolean> cir) {
        if (gtswn$rejectOrderedMember(tile)) cir.setReturnValue(false);
    }
}
