package com.miaokatze.gtswn.network;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import net.minecraft.entity.player.EntityPlayerMP;

import org.junit.After;
import org.junit.Test;

import com.miaokatze.gtswn.common.covers.GTswn_Cover_EnergyWireless;
import com.miaokatze.gtswn.common.util.PlayerRequestQueue;

import cpw.mods.fml.common.gameevent.PlayerEvent;

/** Real admission/drain methods; offline actors deliberately require no server or channel. */
public class LegacyRequestQueueBudgetTest {

    private static Object allocate(Class<?> type) throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        Field field = unsafe.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return unsafe.getMethod("allocateInstance", Class.class).invoke(field.get(null), type);
    }

    private static Object field(Class<?> owner, Object object, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private static Collection<?> pending(Class<?> queue) throws Exception {
        Object pending = field(queue, null, "PENDING");
        return pending instanceof Collection<?> ? (Collection<?>) pending
            : (Collection<?>) field(PlayerRequestQueue.class, pending, "pending");
    }

    private static void call(Class<?> owner, String name) throws Exception {
        Method method = owner.getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(null);
    }

    private static void enqueue(Class<?> queue, EntityPlayerMP player) throws Exception {
        queue.getMethod("enqueue", EntityPlayerMP.class).invoke(null, player);
    }

    @After
    public void clear() {
        NodeRevealRequestQueue.clearServerState();
        LinkNodeDismantleMarkerQueue.clearServerState();
    }

    @Test
    public void repeatedNoPayloadPacketsDoNotRetainTwoHundredThousandPlayerReferences() throws Exception {
        EntityPlayerMP player = (EntityPlayerMP) allocate(EntityPlayerMP.class);
        for (Class<?> queue : new Class<?>[] { NodeRevealRequestQueue.class, LinkNodeDismantleMarkerQueue.class }) {
            for (int i = 0; i < 200_000; i++) enqueue(queue, player);
            assertEquals(1, pending(queue).size());
            call(queue, "drain");
            assertTrue(pending(queue).isEmpty());
        }
    }

    @Test
    public void eachDrainIsFiniteAndReplenishingFrontPlayerCannotOvertakeEarlierWaiters() throws Exception {
        for (Class<?> queue : new Class<?>[] { NodeRevealRequestQueue.class, LinkNodeDismantleMarkerQueue.class }) {
            int budget = queue == NodeRevealRequestQueue.class ? 8 : 64;
            List<EntityPlayerMP> players = new ArrayList<>();
            for (int i = 0; i < 300; i++) {
                EntityPlayerMP player = (EntityPlayerMP) allocate(EntityPlayerMP.class);
                players.add(player);
                enqueue(queue, player);
            }
            int processed = 0;
            while (processed < players.size()) {
                call(queue, "drain");
                processed = Math.min(300, processed + budget);
                if (processed < 300) assertSame(players.get(processed), pending(queue).iterator().next());
                // A sender starts its next burst as soon as its previous request is consumed.
                for (int repeat = 0; repeat < 1000; repeat++) enqueue(queue, players.get(0));
            }
            call(queue, "drain");
            assertTrue(pending(queue).isEmpty());
        }
    }

    @Test
    public void logoutAndStopRemoveRequestsAndResetTheirAdmissionGates() throws Exception {
        EntityPlayerMP old = (EntityPlayerMP) allocate(EntityPlayerMP.class);
        EntityPlayerMP replacement = (EntityPlayerMP) allocate(EntityPlayerMP.class);
        for (Class<?> queue : new Class<?>[] { NodeRevealRequestQueue.class, LinkNodeDismantleMarkerQueue.class }) {
            enqueue(queue, old);
            enqueue(queue, replacement);
            java.lang.reflect.Constructor<?> ctor = queue.getDeclaredConstructor();
            ctor.setAccessible(true);
            queue.getMethod("onLogout", PlayerEvent.PlayerLoggedOutEvent.class)
                .invoke(ctor.newInstance(), new PlayerEvent.PlayerLoggedOutEvent(old));
            assertEquals(1, pending(queue).size());
            assertSame(replacement, pending(queue).iterator().next());
            call(queue, "clearServerState");
            assertTrue(pending(queue).isEmpty());
            enqueue(queue, replacement);
            assertEquals(1, pending(queue).size());
            call(queue, "clearServerState");
        }
    }

    private static final class RegisteredCover extends GTswn_Cover_EnergyWireless {

        int calls;
        boolean ready;

        private RegisteredCover() {
            super(null);
        }

        @Override
        public boolean registerIntoNodeIndex() {
            calls++;
            return ready;
        }
    }

    private static RegisteredCover registration(boolean ready) throws Exception {
        RegisteredCover cover = (RegisteredCover) allocate(RegisteredCover.class);
        cover.ready = ready;
        NodeRevealRequestQueue.enqueueNodeRegistration(cover);
        return cover;
    }

    @Test
    public void largeStartupBacklogProgressesEvenWhenNewRegistrationsExceedThePerTickBudget() throws Exception {
        List<RegisteredCover> startup = new ArrayList<>(), later = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) startup.add(registration(true));
        for (int tick = 0; tick < 10; tick++) {
            for (int arrival = 0; arrival < 1500; arrival++) later.add(registration(true));
            call(NodeRevealRequestQueue.class, "drainNodeRegistrations");
            long calls = startup.stream().mapToLong(c -> c.calls).sum() + later.stream().mapToLong(c -> c.calls).sum();
            assertEquals((tick + 1) * 1024L, calls);
        }
        for (RegisteredCover cover : startup) assertEquals(1, cover.calls);
        Collection<?> pending = (Collection<?>) field(NodeRevealRequestQueue.class, null, "PENDING_NODE_REGISTRATIONS");
        while (!pending.isEmpty()) call(NodeRevealRequestQueue.class, "drainNodeRegistrations");
        for (RegisteredCover cover : later) assertEquals(1, cover.calls);
    }

    @Test
    public void aNotReadyRegistrationRetriesOnlyOncePerTickAndIsNotDroppedByABudgetBoundary() throws Exception {
        RegisteredCover waiting = registration(false);
        call(NodeRevealRequestQueue.class, "drainNodeRegistrations");
        assertEquals(1, waiting.calls);
        for (int i = 0; i < 2048; i++) registration(true);
        waiting.ready = true;
        call(NodeRevealRequestQueue.class, "drainNodeRegistrations");
        assertEquals(2, waiting.calls);
        NodeRevealRequestQueue.clearServerState();
        assertTrue(((Collection<?>) field(NodeRevealRequestQueue.class, null, "PENDING_NODE_REGISTRATIONS")).isEmpty());
    }
}
