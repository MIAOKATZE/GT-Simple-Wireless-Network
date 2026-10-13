package com.miaokatze.gtswn.mixins.gregtech;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.miaokatze.gtswn.common.device.DeviceFlowMonitor;
import com.miaokatze.gtswn.common.device.DeviceSteamOutputTransaction;

import gregtech.api.enums.OutputHatchType;
import gregtech.api.interfaces.IOutputHatch;
import gregtech.api.interfaces.IOutputHatchTransaction;
import gregtech.api.util.FluidEjectionHelper;

/** Observe committed hatch transactions; capacity overflow and Void hatches never become delivered steam. */
@Mixin(value = FluidEjectionHelper.class, remap = false)
public abstract class DeviceSteamOutputMixin {

    @Redirect(
        method = "<init>(Ljava/util/List;ZZ)V",
        at = @At(
            value = "INVOKE",
            target = "Lgregtech/api/interfaces/IOutputHatch;createTransaction()Lgregtech/api/interfaces/IOutputHatchTransaction;"),
        require = 1)
    private IOutputHatchTransaction gtswn$observeTransaction(IOutputHatch hatch) {
        IOutputHatchTransaction transaction = hatch.createTransaction();
        if (!DeviceFlowMonitor.active() || hatch.getHatchType() == OutputHatchType.Void) return transaction;
        return new DeviceSteamOutputTransaction(transaction);
    }

}
