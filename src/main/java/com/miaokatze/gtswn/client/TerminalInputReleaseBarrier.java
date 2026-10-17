package com.miaokatze.gtswn.client;

import java.util.HashSet;
import java.util.Set;
import java.util.function.IntPredicate;

/** Per-key release fence; closing a wheel never blocks a different, newly pressed key. */
public final class TerminalInputReleaseBarrier {

    private final Set<Integer> heldOnClose = new HashSet<>();
    private boolean open;

    public void open() {
        open = true;
        heldOnClose.clear();
    }

    public boolean close(int[] codes, IntPredicate physicallyDown) {
        if (!open) return false;
        open = false;
        for (int code : codes) if (code != 0 && physicallyDown.test(code)) heldOnClose.add(code);
        return true;
    }

    public boolean suppresses(int code, IntPredicate physicallyDown) {
        if (open) return true;
        if (!heldOnClose.contains(code)) return false;
        if (physicallyDown.test(code)) return true;
        heldOnClose.remove(code);
        return false;
    }

    public void released(int code) {
        heldOnClose.remove(code);
    }

    public void poll(IntPredicate physicallyDown) {
        heldOnClose.removeIf(code -> !physicallyDown.test(code));
    }
}
