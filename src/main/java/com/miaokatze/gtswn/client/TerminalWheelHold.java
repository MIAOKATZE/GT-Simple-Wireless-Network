package com.miaokatze.gtswn.client;

/** Wheel-key actions, including the opening-press fence and hold-to-open mode. */
public final class TerminalWheelHold {

    public static final int CONFIRM_TICKS = 10;
    public static final int IGNORED = 0, CYCLE = 1, TAKE = 2, CONFIRM = 3, CLOSE = 4;
    private boolean maintain;
    private boolean opening = true;
    private boolean pressed = true;
    private boolean consumed;
    private int ticks;

    public TerminalWheelHold() {}

    public TerminalWheelHold(boolean maintain) {
        this.maintain = maintain;
    }

    /** A change never closes the screen by itself; a fresh hold can arm a later release. */
    public void mode(boolean maintain, boolean down) {
        this.maintain = maintain;
        opening = down;
        pressed = down;
        consumed = false;
        ticks = 0;
    }

    public boolean maintain() {
        return maintain;
    }

    public int press(boolean leftShift, boolean rightShift) {
        if (pressed) return IGNORED;
        pressed = true;
        ticks = 0;
        consumed = false;
        if (!maintain && (leftShift || rightShift)) {
            consumed = true;
            return TAKE;
        }
        return IGNORED;
    }

    public int release() {
        if (!pressed) return IGNORED;
        if (maintain) {
            pressed = false;
            return CLOSE;
        }
        boolean cycle = !consumed && ticks < CONFIRM_TICKS && !opening;
        opening = false;
        pressed = false;
        consumed = false;
        ticks = 0;
        return cycle ? CYCLE : IGNORED;
    }

    public int tick(boolean down) {
        if (!down) return release();
        if (maintain) {
            pressed = true;
            return IGNORED;
        }
        if (!pressed || consumed || opening) return IGNORED;
        if (++ticks < CONFIRM_TICKS) return IGNORED;
        consumed = true;
        return CONFIRM;
    }

    public boolean isOpening() {
        return opening;
    }

    public float progress() {
        return Math.min(1f, ticks / (float) CONFIRM_TICKS);
    }
}
