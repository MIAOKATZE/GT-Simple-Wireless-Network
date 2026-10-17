package com.miaokatze.gtswn.client;

/** The opening tap only opens; later taps cycle on release. A ten-tick hold (0.5s) confirms without cycling. */
public final class TerminalWheelHold {

    public static final int CONFIRM_TICKS = 10;
    public static final int IGNORED = 0, CYCLE = 1, TAKE = 2, CONFIRM = 3;
    private boolean opening = true;
    private boolean pressed = true;
    private boolean consumed;
    private int ticks;

    public int press(boolean leftShift, boolean rightShift) {
        if (pressed) return IGNORED;
        pressed = true;
        ticks = 0;
        consumed = false;
        if (leftShift || rightShift) {
            consumed = true;
            return TAKE;
        }
        return IGNORED;
    }

    public int release() {
        if (!pressed) return IGNORED;
        boolean cycle = !consumed && ticks < CONFIRM_TICKS && !opening;
        opening = false;
        pressed = false;
        consumed = false;
        ticks = 0;
        return cycle ? CYCLE : IGNORED;
    }

    public int tick(boolean down) {
        if (!down) return release();
        if (!pressed || consumed) return IGNORED;
        if (++ticks < CONFIRM_TICKS) return IGNORED;
        consumed = true;
        return CONFIRM;
    }

    public boolean isOpening() {
        return opening;
    }

    public float progress() {
        return ticks / (float) CONFIRM_TICKS;
    }
}
