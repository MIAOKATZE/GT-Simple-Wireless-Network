package com.miaokatze.gtswn.common.testworld;

import static org.junit.Assert.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

import org.junit.Test;

/** Executes real registry mutations with a loader that rejects every optional test subsystem class. */
public class TestHarnessProductionIsolationTest {

    @Test
    public void defaultProductionMutationDoesNotLoadProbeClassesOrTheirEnums() throws Exception {
        ClassLoader isolated = new ClassLoader(getClass().getClassLoader()) {

            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                synchronized (getClassLoadingLock(name)) {
                    if (name.startsWith("com.miaokatze.gtswn.common.testworld."))
                        throw new AssertionError("Disabled production path loaded " + name);
                    if (name.equals("com.miaokatze.gtswn.common.util.DebugTestGate")
                        || name.startsWith("com.miaokatze.gtswn.common.device.DeviceRegistryData")) {
                        Class<?> type = findLoadedClass(name);
                        if (type == null) {
                            try (InputStream source = getParent()
                                .getResourceAsStream(name.replace('.', '/') + ".class")) {
                                if (source == null) throw new ClassNotFoundException(name);
                                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                                byte[] buffer = new byte[4096];
                                int length;
                                while ((length = source.read(buffer)) != -1) bytes.write(buffer, 0, length);
                                byte[] code = bytes.toByteArray();
                                type = defineClass(name, code, 0, code.length);
                            } catch (IOException ex) {
                                throw new ClassNotFoundException(name, ex);
                            }
                        }
                        if (resolve) resolveClass(type);
                        return type;
                    }
                    return super.loadClass(name, resolve);
                }
            }
        };
        Class<?> type = isolated.loadClass("com.miaokatze.gtswn.common.device.DeviceRegistryData");
        Object registry = type.getConstructor(String.class)
            .newInstance("disabled-production");
        assertEquals(
            true,
            type.getMethod("register", String.class, UUID.class, String.class, int.class)
                .invoke(registry, "0:1:64:2", UUID.randomUUID(), "production machine", 1));
        assertEquals(
            true,
            type.getMethod("unregister", String.class)
                .invoke(registry, "0:1:64:2"));
    }
}
