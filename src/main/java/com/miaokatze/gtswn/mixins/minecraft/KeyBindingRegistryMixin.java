package com.miaokatze.gtswn.mixins.minecraft;

import java.util.List;

import net.minecraft.client.settings.KeyBinding;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.miaokatze.gtswn.client.KeyBindingRegistryCompat;

/** Client only: Controlling iterates this registry directly while processing input. */
@Mixin(KeyBinding.class)
public abstract class KeyBindingRegistryMixin {

    @Shadow
    @Final
    @Mutable
    private static List<KeyBinding> keybindArray;

    @Inject(method = "<clinit>", at = @At("TAIL"), require = 1)
    private static void gtswn$protectKeyRegistrations(CallbackInfo callbackInfo) {
        keybindArray = KeyBindingRegistryCompat.protectRegistrationList(keybindArray);
    }
}
