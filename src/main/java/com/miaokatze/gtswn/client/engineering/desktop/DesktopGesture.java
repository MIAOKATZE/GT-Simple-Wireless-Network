package com.miaokatze.gtswn.client.engineering.desktop;

/** A captured press is a click until held, never a drag merely because the mouse jitters. */
public final class DesktopGesture {

    public static final long HOLD_NANOS = 350000000L;
    public static final double SLOP = 5;

    public enum Release {
        NONE,
        SELECT,
        ACTIVATE,
        DRAG
    }

    private boolean active, selectedBefore, moved, dragging, moving;
    private long pressedAt;
    private double startX, startY;

    public void begin(boolean selectedBefore, double x, double y, long now) {
        cancel();
        active = true;
        this.selectedBefore = selectedBefore;
        startX = x;
        startY = y;
        pressedAt = now;
    }

    public boolean active() {
        return active;
    }

    public boolean dragging() {
        return dragging;
    }

    /** Slop stays in screen pixels, so a small tremor cannot move several cells at overview zoom. */
    public boolean moveAllowed(double x, double y) {
        if (!active || !dragging) return false;
        double dx = x - startX, dy = y - startY;
        moving |= dx * dx + dy * dy > SLOP * SLOP;
        return moving;
    }

    /** Returns true exactly once when the hold threshold is reached. */
    public boolean update(double x, double y, long now) {
        if (!active || dragging) return false;
        double dx = x - startX, dy = y - startY;
        if (now - pressedAt < HOLD_NANOS && dx * dx + dy * dy > SLOP * SLOP) moved = true;
        if (moved || now - pressedAt < HOLD_NANOS) return false;
        dragging = true;
        return true;
    }

    public Release release(double x, double y, long now, boolean inside) {
        if (!active) return Release.NONE;
        update(x, y, now);
        Release result = dragging ? Release.DRAG
            : !inside || moved ? Release.NONE : selectedBefore ? Release.ACTIVATE : Release.SELECT;
        cancel();
        return result;
    }

    public void cancel() {
        active = selectedBefore = moved = dragging = moving = false;
    }
}
