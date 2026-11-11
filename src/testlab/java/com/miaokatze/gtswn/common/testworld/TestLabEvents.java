// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.common.testworld;

import com.miaokatze.miaokatzecore.api.testlab.LabEvents;
import com.miaokatze.miaokatzecore.api.testlab.LabResult;

final class TestLabEvents {

    private TestLabEvents() {}

    static LabResult result(String outcome) {
        if (outcome == null || "RUNNING".equals(outcome) || "BUILDING".equals(outcome)) return null;
        if ("PASS".equals(outcome)) return LabResult.PASS;
        if ("SKIP".equals(outcome)) return LabResult.SKIP;
        if ("STOPPED".equals(outcome)) return LabResult.CANCELLED;
        if ("INTERRUPTED".equals(outcome)) return LabResult.RECOVERY_PENDING;
        return LabResult.FAIL;
    }

    static void publish(String run, String task, String phase, String check, String outcome, String cleanup) {
        LabEvents.publish("gtswn", run, task, phase, check, result(outcome), cleanup);
    }
}
