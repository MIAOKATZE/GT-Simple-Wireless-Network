// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import org.junit.Test;

public class LinkNodePackedCapabilityTest {

    private static final String HANDLER = "com.miaokatze.gtswn.client.LinkNodeVisualHandler";
    private static final String PACKED = "com.miaokatze.miaokatzecore.client.render.PackedVertexStream";

    @Test
    public void originalCoreWithoutPackedApiLoadsTheHandlerAndCachesTheCompleteFallback() throws Exception {
        assertUnavailable(false);
    }

    @Test
    public void optionalApiLinkageFailureIsAlsoCachedBeforeCreatingTheBridge() throws Exception {
        assertUnavailable(true);
    }

    private static void assertUnavailable(boolean linkageFailure) throws Exception {
        WithoutPacked loader = new WithoutPacked(linkageFailure);
        Class<?> handler = Class.forName(HANDLER, true, loader);
        Object instance = handler.getField("INSTANCE")
            .get(null);
        assertEquals(0, loader.packedLoads);
        Method prepare = handler.getDeclaredMethod("preparePacked");
        prepare.setAccessible(true);
        assertFalse((Boolean) prepare.invoke(instance));
        assertFalse((Boolean) prepare.invoke(instance));
        assertEquals(1, loader.packedLoads);
        Field packed = handler.getDeclaredField("packed");
        packed.setAccessible(true);
        assertNull(packed.get(instance));
        Field unavailable = handler.getDeclaredField("packedUnavailable");
        unavailable.setAccessible(true);
        assertTrue(unavailable.getBoolean(instance));
        assertEquals(1, loader.packedLoads);
    }

    /** Load a fresh handler while making only the optional API unavailable, without starting Minecraft or GL. */
    private static final class WithoutPacked extends ClassLoader {

        private final boolean linkageFailure;
        private int packedLoads;

        private WithoutPacked(boolean linkageFailure) {
            super(LinkNodePackedCapabilityTest.class.getClassLoader());
            this.linkageFailure = linkageFailure;
        }

        @Override
        protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.equals(PACKED) || name.startsWith(PACKED + "$")) {
                packedLoads++;
                if (linkageFailure) throw new NoClassDefFoundError(name);
                throw new ClassNotFoundException(name);
            }
            if (!name.equals(HANDLER) && !name.startsWith(HANDLER + "$")) return super.loadClass(name, resolve);
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                String resource = name.replace('.', '/') + ".class";
                try (InputStream input = getParent().getResourceAsStream(resource)) {
                    if (input == null) throw new ClassNotFoundException(name);
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    byte[] buffer = new byte[4096];
                    for (int count; (count = input.read(buffer)) >= 0;) bytes.write(buffer, 0, count);
                    byte[] bytecode = bytes.toByteArray();
                    loaded = defineClass(name, bytecode, 0, bytecode.length);
                } catch (IOException failure) {
                    throw new ClassNotFoundException(name, failure);
                }
            }
            if (resolve) resolveClass(loaded);
            return loaded;
        }
    }
}
