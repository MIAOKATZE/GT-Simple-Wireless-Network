package com.miaokatze.gtswn.common.testworld;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.rules.ExternalResource;

import com.miaokatze.gtswn.common.util.DebugTestGate;

/** Tests explicitly simulate process startup; production exposes no reset or runtime enable command. */
public final class TestHarnessOptInRule extends ExternalResource {

    private final boolean enabled;
    private final Map<Field, Object> original = new LinkedHashMap<>();

    public TestHarnessOptInRule(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    protected void before() throws Exception {
        for (String name : new String[] { "latched", "bootEnabled", "probeSessionActive" }) {
            Field field = DebugTestGate.class.getDeclaredField(name);
            field.setAccessible(true);
            original.put(field, field.get(null));
        }
        restart(enabled);
    }

    public void restart(boolean configuredEnabled) throws Exception {
        for (String name : new String[] { "latched", "bootEnabled", "probeSessionActive" }) {
            Field field = DebugTestGate.class.getDeclaredField(name);
            field.setAccessible(true);
            field.setBoolean(null, false);
        }
        DebugTestGate.latchAtStartup(configuredEnabled);
    }

    @Override
    protected void after() {
        try {
            for (Map.Entry<Field, Object> entry : original.entrySet()) entry.getKey()
                .set(null, entry.getValue());
        } catch (IllegalAccessException ex) {
            throw new AssertionError(ex);
        }
    }
}
