package com.miaokatze.gtswn.mixins.minecraft;

import net.minecraft.client.settings.KeyBinding;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.miaokatze.gtswn.client.TerminalWheelInput;

/** Client only: stop held GUI input from becoming movement or queued world actions after a wheel closes. */
@Mixin(KeyBinding.class)
public abstract class TerminalWheelInputMixin {

    @Shadow
    private int keyCode;
    @Shadow
    private boolean pressed;
    @Shadow
    private int pressTime;

    @Inject(method = "onTick", at = @At("HEAD"), cancellable = true, require = 1)
    private static void gtswn$blockWheelPress(int code, CallbackInfo callback) {
        if (TerminalWheelInput.suppresses(code)) callback.cancel();
    }

    @Inject(method = "setKeyBindState", at = @At("HEAD"), cancellable = true, require = 1)
    private static void gtswn$blockWheelState(int code, boolean down, CallbackInfo callback) {
        if (!down) TerminalWheelInput.released(code);
        else if (TerminalWheelInput.suppresses(code)) callback.cancel();
    }

    @Inject(method = { "getIsKeyPressed", "isPressed" }, at = @At("HEAD"), cancellable = true, require = 1)
    private void gtswn$blockWheelRead(CallbackInfoReturnable<Boolean> callback) {
        if (TerminalWheelInput.suppresses(keyCode)) {
            pressed = false;
            pressTime = 0;
            callback.setReturnValue(false);
        }
    }
}
