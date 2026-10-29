package com.miaokatze.gtswn.client.gui;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import org.junit.Test;

/** Old native controls cannot be rebound to the next build's mutable row/source fields. */
public class PortableMonitorControlRevisionTest {

    @Test
    public void rebuildRequestInvalidatesAlreadyCapturedPinAndRemoveCallbacks() throws Exception {
        GuiPortableMonitor gui = allocate();
        Runnable pin = control(gui, 100), remove = control(gui, 4);
        Method rebuild = GuiPortableMonitor.class.getDeclaredMethod("rebuild");
        rebuild.setAccessible(true);
        rebuild.invoke(gui);
        // This unmounted fixture has no Minecraft/cache state. An action reaching either would fail.
        pin.run();
        remove.run();
        Field pending = GuiPortableMonitor.class.getDeclaredField("rebuildPending");
        pending.setAccessible(true);
        assertTrue(pending.getBoolean(gui));
    }

    @Test
    public void replacementBuildCannotGiveAnOldNumericIdNewMeaning() throws Exception {
        GuiPortableMonitor gui = allocate();
        Runnable old = control(gui, 101);
        Field revision = GuiPortableMonitor.class.getDeclaredField("controlsRevision");
        revision.setAccessible(true);
        revision.setLong(gui, revision.getLong(gui) + 1);
        old.run();
    }

    private static Runnable control(GuiPortableMonitor gui, int id) throws Exception {
        Method control = GuiPortableMonitor.class.getDeclaredMethod("controlAction", int.class);
        control.setAccessible(true);
        return (Runnable) control.invoke(gui, id);
    }

    private static GuiPortableMonitor allocate() throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        Field singleton = unsafe.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        return (GuiPortableMonitor) unsafe.getMethod("allocateInstance", Class.class)
            .invoke(singleton.get(null), GuiPortableMonitor.class);
    }
}
