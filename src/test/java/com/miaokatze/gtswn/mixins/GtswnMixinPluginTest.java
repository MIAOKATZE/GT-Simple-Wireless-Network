package com.miaokatze.gtswn.mixins;

import static org.junit.Assert.*;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Test;

public class GtswnMixinPluginTest {

    private static final String PREFIX = "com.miaokatze.gtswn.mixins.";
    private static final AtomicBoolean TARGET_INITIALIZED = new AtomicBoolean();
    private final GtswnMixinPlugin plugin = new GtswnMixinPlugin();

    @Test
    public void absentOptionalTargetsNeverApplyTheirMixins() {
        for (String integration : new String[] { "mm", "baubles", "journeymap", "waila" }) {
            assertFalse(
                integration,
                plugin.shouldApplyMixin("missing.optional.mod.Target", PREFIX + integration + ".Test"));
        }
    }

    @Test
    public void presentOptionalTargetsRemainEnabled() {
        for (String integration : new String[] { "mm", "baubles", "journeymap", "waila" }) {
            assertTrue(integration, plugin.shouldApplyMixin("java.lang.String", PREFIX + integration + ".Test"));
        }
    }

    @Test
    public void presenceProbeDoesNotInitializeAnOptionalModClass() {
        assertFalse(TARGET_INITIALIZED.get());
        assertTrue(
            plugin.shouldApplyMixin(
                "com.miaokatze.gtswn.mixins.GtswnMixinPluginTest$InitializationTrap",
                PREFIX + "mm.Test"));
        assertFalse("Optional APIs must not initialize during the early presence probe", TARGET_INITIALIZED.get());
    }

    @Test
    public void requiredTargetsAreNotSilentlyDisabledByTheOptionalGuard() {
        for (String integration : new String[] { "ae2", "gregtech", "minecraft" }) {
            assertTrue(
                integration,
                plugin.shouldApplyMixin("missing.required.mod.Target", PREFIX + integration + ".Test"));
        }
    }

    public static final class InitializationTrap {

        static {
            TARGET_INITIALIZED.set(true);
        }
    }
}
