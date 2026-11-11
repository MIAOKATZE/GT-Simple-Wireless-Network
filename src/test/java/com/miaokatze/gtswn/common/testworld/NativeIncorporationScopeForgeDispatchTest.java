package com.miaokatze.gtswn.common.testworld;

import static org.junit.Assert.assertFalse;

import java.lang.reflect.Field;

import org.junit.Test;

import cpw.mods.fml.common.eventhandler.ASMEventHandler;
import cpw.mods.fml.common.eventhandler.EventBus;
import cpw.mods.fml.common.eventhandler.ListenerList;
import cpw.mods.fml.common.gameevent.TickEvent;

public class NativeIncorporationScopeForgeDispatchTest {

    @Test
    public void actualForgeAsmDispatchReachesBothInactiveListenersForStartAndEnd() throws Exception {
        NativeIncorporationScopeScenario listener = new NativeIncorporationScopeScenario();
        EventBus bus = new EventBus((source, event, handlers, index, failure) -> {
            if (failure instanceof Error) throw (Error) failure;
            if (failure instanceof RuntimeException) throw (RuntimeException) failure;
            throw new AssertionError(failure);
        });
        Field id = EventBus.class.getDeclaredField("busID");
        id.setAccessible(true);
        int busId = id.getInt(bus);
        TickEvent.ServerTickEvent start = new TickEvent.ServerTickEvent(TickEvent.Phase.START);
        TickEvent.ServerTickEvent end = new TickEvent.ServerTickEvent(TickEvent.Phase.END);
        ASMEventHandler before = new ASMEventHandler(
            listener,
            NativeIncorporationScopeScenario.class.getMethod("beforeDrain", TickEvent.ServerTickEvent.class),
            null);
        ASMEventHandler capture = new ASMEventHandler(
            listener,
            NativeIncorporationScopeScenario.class
                .getMethod("captureIntroducedIdentity", TickEvent.ServerTickEvent.class),
            null);
        // Plain JUnit lacks Forge's injected event no-arg constructors. Register its actual generated wrappers
        // directly, as in ClientTestPermissionForgeDispatchTest, then dispatch through the real EventBus.
        start.getListenerList()
            .register(busId, before.getPriority(), before);
        start.getListenerList()
            .register(busId, capture.getPriority(), capture);
        try {
            assertFalse(bus.post(start)); // The real first failing runtime path was START, before any context use.
            assertFalse(bus.post(end)); // No active queued fixture: both guards must also be safe with null context.
        } finally {
            ListenerList.unregisterAll(busId, before);
            ListenerList.unregisterAll(busId, capture);
        }
    }
}
