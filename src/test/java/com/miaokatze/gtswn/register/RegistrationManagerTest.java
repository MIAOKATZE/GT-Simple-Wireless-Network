// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.register;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class RegistrationManagerTest {

    private final RegistrationManager manager = RegistrationManager.getInstance();

    @Before
    @After
    public void clear() {
        manager.clear();
    }

    @Test
    public void normalRegistrationsRunInDeclaredOrder() {
        List<Integer> registered = new ArrayList<>();
        manager.addRegistrar(() -> registered.add(1));
        manager.addRegistrar(() -> registered.add(2));
        manager.registerAll();
        assertEquals(Arrays.asList(1, 2), registered);
    }

    @Test
    public void requiredFailureEscapesAndLaterRegistrationCannotReportSuccess() {
        List<Integer> registered = new ArrayList<>();
        IllegalAccessError cause = new IllegalAccessError("GT registration window closed");
        manager.addRegistrar(() -> registered.add(1));
        manager.addRegistrar(() -> { throw cause; });
        manager.addRegistrar(() -> registered.add(3));
        try {
            manager.registerAll();
            fail("Required registration failure must reach the loader");
        } catch (IllegalAccessError failure) {
            assertSame(cause, failure);
        }
        assertEquals(Arrays.asList(1), registered);
    }
}
