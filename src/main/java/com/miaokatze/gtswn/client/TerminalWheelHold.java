package com.miaokatze.gtswn.client;

/** The opening press only opens; later press edges cycle. Either press can confirm once after twenty GUI ticks. */
public final class TerminalWheelHold {

    public static final int CONFIRM_TICKS = 20;
    public static final int IGNORED = 0, CYCLE = 1, TAKE = 2;
    private boolean opening = true;
    private boolean pressed = true;
    private boolean confirmed;
    private int ticks;

    public boolean press() {
        if (opening || pressed) return false;
        pressed = true;
        ticks = 0;
        confirmed = false;
        return true;
    }

    public int press(boolean leftShift, boolean rightShift) {
        if (!press()) return IGNORED;
        if (leftShift || rightShift) {
            confirmed = true;
            return TAKE;
        }
        return CYCLE;
    }

    public void release() {
        opening = false;
        pressed = false;
        confirmed = false;
        ticks = 0;
    }

    public boolean tick(boolean down) {
        if (!down) {
            release();
            return false;
        }
        if (!pressed || confirmed) return false;
        if (++ticks < CONFIRM_TICKS) return false;
        confirmed = true;
        return true;
    }

    public boolean isOpening() {
        return opening;
    }

    public float progress() {
        return ticks / (float) CONFIRM_TICKS;
    }
}
