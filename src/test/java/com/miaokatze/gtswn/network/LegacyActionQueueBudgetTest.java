package com.miaokatze.gtswn.network;

import static org.junit.Assert.*;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.concurrent.ArrayBlockingQueue;

import net.minecraft.entity.player.EntityPlayerMP;

import org.junit.Test;

import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

public class LegacyActionQueueBudgetTest {

    private static EntityPlayerMP actor() throws Exception {
        Class<?> type = Class.forName("sun.misc.Unsafe");
        Field field = type.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (EntityPlayerMP) type.getMethod("allocateInstance", Class.class)
            .invoke(field.get(null), EntityPlayerMP.class);
    }

    @SuppressWarnings("unchecked")
    private static ArrayBlockingQueue<Object> queue(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return (ArrayBlockingQueue<Object>) field.get(null);
    }

    @Test
    public void panelKeepsAcceptedFifoActionsAndCapsAdmissionAndTickWork() throws Exception {
        ArrayBlockingQueue<Object> pending = queue(PanelActionQueue.class, "PENDING");
        PanelActionQueue.clear();
        try {
            EntityPlayerMP player = actor();
            for (int i = 0; i < 1100; i++) PanelActionQueue.enqueueConfig(player, 0, 0, 0, i, "");
            assertEquals(1024, pending.size());
            Field action = pending.peek()
                .getClass()
                .getDeclaredField("action");
            action.setAccessible(true);
            int expected = 0;
            for (Object queued : pending) assertEquals(expected++, action.getInt(queued));
            // Disconnected actors are rejected, but each rejected request still consumes the tick budget.
            PanelActionQueue.drain();
            assertEquals(960, pending.size());
            assertEquals(64, action.getInt(pending.peek()));
            Constructor<PanelActionQueue> constructor = PanelActionQueue.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            constructor.newInstance()
                .onLogout(new PlayerEvent.PlayerLoggedOutEvent(player));
            assertTrue(pending.isEmpty());
            PanelActionQueue.enqueueConfig(player, 0, 0, 0, 1, "");
            assertEquals(1, pending.size());
            PanelActionQueue.clear();
            assertTrue(pending.isEmpty());
        } finally {
            PanelActionQueue.clear();
        }
    }

    @Test
    public void swapRetainsDistinctIntentsAndCapsDrainIncludingStaleConnections() throws Exception {
        ArrayBlockingQueue<Object> pending = queue(PacketQuantumTerminalSwapBus.class, "QUEUE");
        Class<?> job = Class.forName(PacketQuantumTerminalSwapBus.class.getName() + "$SwapJob");
        Constructor<?> constructor = job
            .getDeclaredConstructor(EntityPlayerMP.class, int.class, int.class, int.class, int.class, boolean.class);
        constructor.setAccessible(true);
        PacketQuantumTerminalSwapBus.clear();
        try {
            EntityPlayerMP player = actor();
            for (int i = 0; i < 300; i++) pending.offer(constructor.newInstance(player, i, 0, 0, 1, i % 2 == 0));
            assertEquals(256, pending.size());
            Field x = job.getDeclaredField("x"), remove = job.getDeclaredField("removeCore");
            x.setAccessible(true);
            remove.setAccessible(true);
            int expected = 0;
            for (Object queued : pending) {
                assertEquals(expected, x.getInt(queued));
                assertEquals(expected++ % 2 == 0, remove.getBoolean(queued));
            }
            PacketQuantumTerminalSwapBus.DrainListener listener = new PacketQuantumTerminalSwapBus.DrainListener();
            listener.onServerTick(new TickEvent.ServerTickEvent(TickEvent.Phase.END));
            assertEquals(248, pending.size());
            listener.onLogout(new PlayerEvent.PlayerLoggedOutEvent(player));
            assertTrue(pending.isEmpty());
            pending.offer(constructor.newInstance(player, 0, 0, 0, 1, false));
            PacketQuantumTerminalSwapBus.clear();
            assertTrue(pending.isEmpty());
        } finally {
            PacketQuantumTerminalSwapBus.clear();
        }
    }
}
