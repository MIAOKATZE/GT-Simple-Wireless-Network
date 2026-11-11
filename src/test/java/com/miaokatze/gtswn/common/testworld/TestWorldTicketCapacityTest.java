// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.common.testworld;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TestWorldTicketCapacityTest {

    @Test
    public void positiveForgeLimitsSplitTicketsWhileNonPositiveDepthsRemainUnbounded() {
        assertTrue(TestWorldManager.ticketHasRoom(25, 24));
        assertFalse(TestWorldManager.ticketHasRoom(25, 25));
        assertFalse(TestWorldManager.ticketHasRoom(25, 26));
        assertTrue(TestWorldManager.ticketHasRoom(0, 100));
        assertTrue(TestWorldManager.ticketHasRoom(-1, 100));
    }
}
