// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.client.testworld;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Rule;
import org.junit.Test;

import com.miaokatze.gtswn.common.testworld.TestHarnessOptInRule;

public class ClientTestPermissionTest {

    @Rule
    public final TestHarnessOptInRule harness = new TestHarnessOptInRule(true);

    @Test
    public void directUiAndPagedCallsRequireTheServerReceiptBeforeTouchingMinecraft() {
        try {
            UiDebugSuite.INSTANCE.start("current", false);
            fail("Direct UI call must not bypass server OP2 authorization");
        } catch (IllegalStateException rejected) {
            assertTrue(
                rejected.getMessage()
                    .contains("server OP2 receipt"));
        }
        try {
            DevicePagedClientCheck.start();
            fail("Direct paged call must not bypass server OP2 authorization");
        } catch (IllegalStateException rejected) {
            assertTrue(
                rejected.getMessage()
                    .contains("server OP2 receipt"));
        }
        assertFalse(UiDebugSuite.INSTANCE.running());
    }
}
