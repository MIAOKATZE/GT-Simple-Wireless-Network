// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.common.quantum;

import static org.junit.Assert.*;

import org.junit.Test;

public class QuantumSecurityFeedbackTest {

    @Test
    public void onlyRepeatedSameTargetAndCauseAreThrottled() {
        String target = java.util.UUID.randomUUID()
            .toString();
        assertTrue(QuantumSecurityFeedback.allow(target, "missing", 10L));
        assertFalse(QuantumSecurityFeedback.allow(target, "missing", 11L));
        assertTrue(QuantumSecurityFeedback.allow(target, "permission", 11L));
        assertTrue(QuantumSecurityFeedback.allow(target + ":other", "missing", 11L));
        assertTrue(QuantumSecurityFeedback.allow(target, "missing", 2_000_000_010L));
    }
}
