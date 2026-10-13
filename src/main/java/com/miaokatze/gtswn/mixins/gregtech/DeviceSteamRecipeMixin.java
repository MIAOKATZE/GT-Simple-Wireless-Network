package com.miaokatze.gtswn.mixins.gregtech;

import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidStack;

import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.miaokatze.gtswn.common.device.DeviceFlowMonitor;
import com.miaokatze.gtswn.common.device.DeviceSteamAdapter;

import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.MTEBasicMachine;

/** Basic recipes mutate their own tanks directly, without multiblock hatch transactions. */
@Mixin(value = MTEBasicMachine.class, remap = false)
public abstract class DeviceSteamRecipeMixin {

    @Unique
    private Fluid gtswn$outputFluid;
    @Unique
    private int gtswn$outputAmount;

    @Redirect(
        method = "onPostTick",
        at = @At(
            value = "INVOKE",
            target = "Lgregtech/api/metatileentity/implementations/MTEBasicMachine;checkRecipe()I"),
        require = 1)
    private int gtswn$actualRecipeInput(MTEBasicMachine machine) {
        if (!DeviceFlowMonitor.active()) return machine.checkRecipe();
        FluidStack before = machine.getFillableStack();
        Fluid steam = DeviceSteamAdapter.isSteam(before) ? before.getFluid() : null;
        int amount = steam == null ? 0 : before.amount;
        int result = machine.checkRecipe();
        if (steam != null) {
            FluidStack after = machine.getFillableStack();
            int remaining = after != null && after.getFluid() == steam ? after.amount : 0;
            DeviceFlowMonitor.steamCurrent(Math.max(0L, (long) amount - remaining), false);
        }
        return result;
    }

    @Inject(
        method = "onPostTick",
        at = @At(
            value = "FIELD",
            target = "Lgregtech/api/metatileentity/implementations/MTEBasicMachine;mOutputFluid:Lnet/minecraftforge/fluids/FluidStack;",
            opcode = Opcodes.GETFIELD,
            ordinal = 0),
        require = 1)
    private void gtswn$beforeCompletedOutput(IGregTechTileEntity tile, long tick, CallbackInfo ci) {
        gtswn$outputFluid = null;
        gtswn$outputAmount = 0;
        if (!DeviceFlowMonitor.active()) return;
        FluidStack stored = ((MTEBasicMachine) (Object) this).getDrainableStack();
        if (DeviceSteamAdapter.isSteam(stored)) {
            gtswn$outputFluid = stored.getFluid();
            gtswn$outputAmount = stored.amount;
        }
    }

    @Inject(
        method = "onPostTick",
        at = @At(
            value = "INVOKE",
            target = "Lgregtech/api/metatileentity/implementations/MTEBasicMachine;endProcess()V"),
        require = 1)
    private void gtswn$completedOutput(IGregTechTileEntity tile, long tick, CallbackInfo ci) {
        if (!DeviceFlowMonitor.active()) return;
        FluidStack stored = ((MTEBasicMachine) (Object) this).getDrainableStack();
        if (DeviceSteamAdapter.isSteam(stored)) {
            long previous = stored.getFluid() == gtswn$outputFluid ? gtswn$outputAmount : 0;
            DeviceFlowMonitor.steamCurrent(Math.max(0L, stored.amount - previous), true);
        }
        gtswn$outputFluid = null;
    }
}
