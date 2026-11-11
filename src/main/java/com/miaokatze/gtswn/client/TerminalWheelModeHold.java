// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.client;

/** One mode change per uninterrupted left-button press, measured in monotonic milliseconds. */
public final class TerminalWheelModeHold {

    public static final long DURATION_MS = 1000;
    private long started;
    private boolean pressed;
    private boolean consumed;
    private float progress;

    public void press(long now) {
        started = now;
        pressed = true;
        consumed = false;
        progress = 0;
    }

    public boolean update(long now, boolean valid) {
        if (!valid) cancel();
        if (!pressed || consumed) return false;
        progress = Math.min(1f, Math.max(0, now - started) / (float) DURATION_MS);
        if (progress < 1) return false;
        consumed = true;
        return true;
    }

    public void cancel() {
        pressed = false;
        progress = 0;
    }

    public float progress() {
        return progress;
    }
}
