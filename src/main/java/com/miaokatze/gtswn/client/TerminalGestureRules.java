package com.miaokatze.gtswn.client;

/** 保证显形修饰键不会落入方块并入分支。 */
public final class TerminalGestureRules {

    private TerminalGestureRules() {}

    public static boolean canIncorporate(boolean alt, boolean shift, boolean block) {
        return alt && !shift && block;
    }
}
