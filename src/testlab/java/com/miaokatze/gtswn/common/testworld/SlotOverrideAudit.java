package com.miaokatze.gtswn.common.testworld;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.miaokatze.gtswn.common.baubles.TerminalBaubleSlotPolicy;

/** Bounded startup evidence, loaded only when the local laboratory is enabled. */
public final class SlotOverrideAudit {

    private static final List<Observation> OBSERVATIONS = new ArrayList<>();
    private static int dropped;

    private SlotOverrideAudit() {}

    public static synchronized String[] apply(String[] assignments) {
        String[] before = assignments.clone();
        String[] merged = TerminalBaubleSlotPolicy.withRequiredSlots(assignments);
        if (OBSERVATIONS.size() < 64) OBSERVATIONS.add(new Observation(before, merged.clone()));
        else dropped++;
        return merged;
    }

    public static synchronized void observe(String[] before, String[] after) {
        if (OBSERVATIONS.size() < 64) OBSERVATIONS.add(new Observation(before.clone(), after.clone()));
        else dropped++;
    }

    static synchronized List<Observation> snapshot() {
        return Collections.unmodifiableList(new ArrayList<>(OBSERVATIONS));
    }

    static synchronized int dropped() {
        return dropped;
    }

    static final class Observation {

        final String[] before;
        final String[] after;

        Observation(String[] before, String[] after) {
            this.before = before;
            this.after = after;
        }
    }
}
