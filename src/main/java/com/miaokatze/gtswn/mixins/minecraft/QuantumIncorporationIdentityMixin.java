package com.miaokatze.gtswn.mixins.minecraft;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.miaokatze.gtswn.common.quantum.QuantumIncorporationIdentity;

@Mixin(TileEntity.class)
public abstract class QuantumIncorporationIdentityMixin implements QuantumIncorporationIdentity {

    @Unique
    private String gtswn$incorporationIdentity = "";

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
    }

    @Inject(method = "readFromNBT", at = @At("RETURN"))
    private void gtswn$readIdentity(NBTTagCompound tag, CallbackInfo callback) {
        gtswn$incorporationIdentity = tag.getString("GTSWN_IncorporationIdentity");
    }
}
