package com.miaokatze.gtswn.client;

import net.minecraft.client.settings.KeyBinding;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** Used only by the client wheel and client-listed KeyBinding mixin. */
@SideOnly(Side.CLIENT)
public final class TerminalWheelInput {

    private static final TerminalInputReleaseBarrier BARRIER = new TerminalInputReleaseBarrier();

    private TerminalWheelInput() {}

    public static void open() {
        BARRIER.open();
        KeyBinding.unPressAllKeys();
    }

    public static void close() {
        int buttons = Mouse.isCreated() ? Mouse.getButtonCount() : 0;
        int[] codes = new int[Keyboard.KEYBOARD_SIZE + buttons];
        for (int code = 0; code < Keyboard.KEYBOARD_SIZE; code++) codes[code] = code;
        for (int button = 0; button < buttons; button++) codes[Keyboard.KEYBOARD_SIZE + button] = button - 100;
        if (BARRIER.close(codes, TerminalKeyNames::isDown)) KeyBinding.unPressAllKeys();
    }

    public static boolean suppresses(int code) {
        return BARRIER.suppresses(code, TerminalKeyNames::isDown);
    }

    public static void released(int code) {
        BARRIER.released(code);
    }

    public static void poll() {
        BARRIER.poll(TerminalKeyNames::isDown);
    }
}
