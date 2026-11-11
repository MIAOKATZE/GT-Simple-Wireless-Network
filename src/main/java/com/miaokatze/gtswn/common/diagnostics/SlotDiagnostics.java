// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.common.diagnostics;

/** Passive startup observation; terminal slot policy is always decided by production code. */
public final class SlotDiagnostics {

    public interface Listener {

        void observe(String[] before, String[] after);
    }

    private static volatile Listener listener;

    private SlotDiagnostics() {}

    public static void install(Listener value) {
        listener = value;
    }

    public static void observe(String[] before, String[] after) {
        Listener current = listener;
        if (current != null) try {
            current.observe(before, after);
        } catch (RuntimeException | LinkageError ignored) {}
    }
}
