// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.common.testworld;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;

import org.junit.Test;

import com.miaokatze.gtswn.common.util.DebugTestGate;

public class TestHarnessVariantAvailabilityTest {

    @Test
    public void ordinaryArtifactStaysDisabledAndExplicitOptInClearlyRejectsMissingVariant() throws Exception {
        URL main = DebugTestGate.class.getProtectionDomain()
            .getCodeSource()
            .getLocation();
        try (URLClassLoader loader = new URLClassLoader(new URL[] { main }, getClass().getClassLoader()) {

            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                synchronized (getClassLoadingLock(name)) {
                    if (name.startsWith("com.miaokatze.gtswn.common.testworld.")
                        || name.startsWith("com.miaokatze.gtswn.client.testworld."))
                        throw new ClassNotFoundException(name);
                    if (name.startsWith(DebugTestGate.class.getName())) {
                        Class<?> type = findLoadedClass(name);
                        if (type == null) type = findClass(name);
                        if (resolve) resolveClass(type);
                        return type;
                    }
                    return super.loadClass(name, resolve);
                }
            }
        }) {
            Class<?> gate = loader.loadClass(DebugTestGate.class.getName());
            assertEquals(
                Boolean.FALSE,
                gate.getMethod("isEnabled")
                    .invoke(null));
            assertEquals(
                Boolean.FALSE,
                gate.getMethod("probesActive")
                    .invoke(null));
            try {
                gate.getMethod("latchAtStartup", boolean.class)
                    .invoke(null, true);
                fail("Main artifact cannot enable a missing testlab variant");
            } catch (InvocationTargetException failure) {
                assertTrue(failure.getCause() instanceof IllegalStateException);
                assertTrue(
                    failure.getCause()
                        .getMessage()
                        .contains("-testlab"));
            }
            assertEquals(
                Boolean.FALSE,
                gate.getMethod("isEnabled")
                    .invoke(null));
        }
    }
}
