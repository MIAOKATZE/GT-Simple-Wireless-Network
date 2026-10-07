package com.miaokatze.gtswn.mixins.minecraft;

import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import com.miaokatze.gtswn.client.ChargeStackSync;

/** Client only: keep active mining/use references when charge changes through S2F or S30. */
@Mixin(Slot.class)
public abstract class HeldChargeSyncMixin {

    @ModifyVariable(method = "putStack", at = @At("HEAD"), argsOnly = true)
    private ItemStack gtswn$preserveHeldCharge(ItemStack incoming) {
        Slot slot = (Slot) (Object) this;
        if (!(slot.inventory instanceof InventoryPlayer)) {
            return incoming;
        }
        InventoryPlayer inventory = (InventoryPlayer) slot.inventory;
        if (!inventory.player.worldObj.isRemote || slot.getSlotIndex() != inventory.currentItem) {
            return incoming;
        }
        return ChargeStackSync.mergeHeldCharge(inventory.player, inventory.getCurrentItem(), incoming);
    }
}
