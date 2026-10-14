package com.miaokatze.gtswn.mixins.minecraft;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.miaokatze.gtswn.common.quantum.QuantumControllerIdentity;
import com.miaokatze.gtswn.common.quantum.QuantumIncorporationIdentity;
import com.miaokatze.gtswn.common.quantum.QuantumTileLifecycle;

@Mixin(TileEntity.class)
public abstract class QuantumIncorporationIdentityMixin
    implements QuantumIncorporationIdentity, QuantumControllerIdentity {

    @Unique
    private String gtswn$incorporationIdentity = "";

    @Unique
    private String gtswn$controllerIdentity = "";

    @Override
    public String gtswn$getControllerIdentity() {
        return gtswn$controllerIdentity;
    }

    @Override
    public void gtswn$setControllerIdentity(String identity) {
        gtswn$controllerIdentity = identity;
    }

    @Override
    public String gtswn$getIncorporationIdentity() {
        return gtswn$incorporationIdentity;
    }

    @Override
    public void gtswn$setIncorporationIdentity(String identity) {
        gtswn$incorporationIdentity = identity;
    }

    @Inject(method = "writeToNBT", at = @At("RETURN"))
    private void gtswn$writeIdentity(NBTTagCompound tag, CallbackInfo callback) {
        if (!gtswn$incorporationIdentity.isEmpty())
            tag.setString("GTSWN_IncorporationIdentity", gtswn$incorporationIdentity);
        else tag.removeTag("GTSWN_IncorporationIdentity");
        if (!gtswn$controllerIdentity.isEmpty()) tag.setString("GTSWN_ControllerIdentity", gtswn$controllerIdentity);
        else tag.removeTag("GTSWN_ControllerIdentity");
    }

    @Inject(method = "readFromNBT", at = @At("RETURN"))
    private void gtswn$readIdentity(NBTTagCompound tag, CallbackInfo callback) {
        gtswn$incorporationIdentity = tag.getString("GTSWN_IncorporationIdentity");
        gtswn$controllerIdentity = tag.getString("GTSWN_ControllerIdentity");
    }

    @Inject(method = "invalidate", at = @At("HEAD"))
    private void gtswn$removeRegistration(CallbackInfo callback) {
        QuantumTileLifecycle.removed((TileEntity) (Object) this);
    }

    @Inject(method = "onChunkUnload", at = @At("HEAD"), remap = false)
    private void gtswn$unloadRegistration(CallbackInfo callback) {
        QuantumTileLifecycle.unloaded((TileEntity) (Object) this);
    }

    @Inject(method = "validate", at = @At("HEAD"))
    private void gtswn$validateRegistration(CallbackInfo callback) {
        QuantumTileLifecycle.validated((TileEntity) (Object) this);
    }
}
