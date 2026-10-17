package com.miaokatze.gtswn.client;

import net.minecraft.client.settings.GameSettings;
import net.minecraft.util.StatCollector;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** Concise conventional labels for defaults, preserving the actual name of all other bindings. */
@SideOnly(Side.CLIENT)
public final class TerminalKeyNames {

    private TerminalKeyNames() {}

    public static String name(int code) {
        if (code == -100) return StatCollector.translateToLocal("gtswn.input.mouse.left");
        if (code == -99) return StatCollector.translateToLocal("gtswn.input.mouse.right");
        if (code == -98) return StatCollector.translateToLocal("gtswn.input.mouse.middle");
        if (code == Keyboard.KEY_LSHIFT || code == Keyboard.KEY_RSHIFT) return "Shift";
        if (code == Keyboard.KEY_LCONTROL || code == Keyboard.KEY_RCONTROL) return "Ctrl";
        if (code == Keyboard.KEY_LMENU || code == Keyboard.KEY_RMENU) return "Alt";
        if (code == Keyboard.KEY_ESCAPE) return "Esc";
        if (code == Keyboard.KEY_SPACE) return StatCollector.translateToLocal("gtswn.input.key.space");
        if (code == Keyboard.KEY_RETURN || code == Keyboard.KEY_NUMPADENTER)
            return StatCollector.translateToLocal("gtswn.input.key.enter");
        return GameSettings.getKeyDisplayString(code);
    }

    public static boolean isDown(int code) {
        if (code < 0) {
            int button = code + 100;
            return Mouse.isCreated() && button >= 0 && button < Mouse.getButtonCount() && Mouse.isButtonDown(button);
        }
        return Keyboard.isCreated() && code > 0 && code < Keyboard.KEYBOARD_SIZE && Keyboard.isKeyDown(code);
    }
}
