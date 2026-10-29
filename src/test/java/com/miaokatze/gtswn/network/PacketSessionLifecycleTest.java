package com.miaokatze.gtswn.network;

import static org.junit.Assert.*;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;

import org.junit.Test;

import com.miaokatze.gtswn.common.device.DeviceTerminalRequestQueue;
import com.miaokatze.gtswn.common.util.PlayerRequestQueue;

import cpw.mods.fml.common.gameevent.PlayerEvent;

public class PacketSessionLifecycleTest {

    private static final class Player extends EntityPlayerMP {

        UUID id;

        private Player() {
            super(null, null, null, null);
        }

        @Override
        public UUID getUniqueID() {
            return id;
        }

        @Override
        public String getCommandSenderName() {
            return "same-player";
        }
    }

    private static Object allocate(Class<?> type) throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        Field field = unsafe.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return unsafe.getMethod("allocateInstance", Class.class).invoke(field.get(null), type);
    }

    private static Player player(UUID id) throws Exception {
        Player player = (Player) allocate(Player.class);
        player.id = id;
        return player;
    }

    private static Object field(Class<?> owner, Object instance, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(instance);
    }

    private static Object job(Class<?> owner, EntityPlayerMP player) throws Exception {
        String nested = owner == PacketTerminalGesture.class ? "Request" : "Job";
        Object job = allocate(Class.forName(owner.getName() + "$" + nested));
        Field field = job.getClass().getDeclaredField("player");
        field.setAccessible(true);
        field.set(job, player);
        return job;
    }

    @SuppressWarnings("unchecked")
    @Test
    public void quantumLogoutDiscardsOnlyTheDepartingEntityAndStopReleasesAllPendingActions() throws Exception {
        UUID shared = UUID.randomUUID();
        Player departing = player(shared), replacement = player(shared);
        for (Class<?> owner : new Class<?>[] { PacketQuantumIncorporation.class, PacketRequestQuantumReveal.class,
            PacketTerminalGesture.class, PacketSetQuantumNetworkColor.class }) {
            Method clear = owner.getMethod("clearServerState");
            Map<UUID, Object> pending = (Map<UUID, Object>) field(owner, null, "PENDING");
            clear.invoke(null);
            try {
                Object oldJob = owner == PacketRequestQuantumReveal.class ? departing : job(owner, departing);
                Object newJob = owner == PacketRequestQuantumReveal.class ? replacement : job(owner, replacement);
                Object listener = Class.forName(owner.getName() + "$Drain").getDeclaredConstructor().newInstance();
                Method logout = listener.getClass().getMethod("logout", PlayerEvent.PlayerLoggedOutEvent.class);
                pending.put(shared, newJob);
                logout.invoke(listener, new PlayerEvent.PlayerLoggedOutEvent(departing));
                assertSame(newJob, pending.get(shared));
                pending.put(shared, oldJob);
                logout.invoke(listener, new PlayerEvent.PlayerLoggedOutEvent(departing));
                assertTrue(pending.isEmpty());
                pending.put(shared, newJob);
                clear.invoke(null);
                assertTrue(pending.isEmpty());
            } finally {
                clear.invoke(null);
            }
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    public void aDepartedPlayersCancellationCannotClearAReplacementCharge() throws Exception {
        UUID id = UUID.randomUUID();
        Player departing = player(id);
        Object cancellation = job(PacketTerminalGesture.class, departing);
        Field action = cancellation.getClass().getDeclaredField("action");
        action.setAccessible(true);
        action.setInt(cancellation, PacketTerminalGesture.CANCEL_REVEAL);
        Object replacementCharge = allocate(Class.forName(PacketTerminalGesture.class.getName() + "$Charge"));
        Map<UUID, Object> charges = (Map<UUID, Object>) field(PacketTerminalGesture.class, null, "CHARGES");
        Method handle = PacketTerminalGesture.class.getDeclaredMethod("handle", cancellation.getClass());
        handle.setAccessible(true);
        PacketTerminalGesture.clearServerState();
        try {
            charges.put(id, replacementCharge);
            handle.invoke(null, cancellation);
            assertSame(replacementCharge, charges.get(id));
        } finally {
            PacketTerminalGesture.clearServerState();
        }
    }

    @Test
    public void aDepartedPlayersMigrationCancelCannotAdvanceTheNextConnectionsSessionBarrier() throws Exception {
        Player departing = player(UUID.randomUUID());
        PacketQuantumMigration.Request cancellation = new PacketQuantumMigration.Request(
            departing, new PacketQuantumMigration(PacketQuantumMigration.CANCEL, Long.MAX_VALUE, 0, 0, 0));
        Method handle = PacketQuantumMigration.class.getDeclaredMethod("handle", PacketQuantumMigration.Request.class);
        handle.setAccessible(true);
        PacketQuantumMigration.clearServerState();
        try {
            handle.invoke(null, cancellation);
            assertTrue(((Map<?, ?>) field(PacketQuantumMigration.class, null, "LAST_SESSION")).isEmpty());
        } finally {
            PacketQuantumMigration.clearServerState();
        }
    }

    @Test
    public void deviceVisualAndFactoryActionsNeverAttachToAReplacementEntityWithTheSameProfile() throws Exception {
        UUID id = UUID.randomUUID();
        Player original = player(id), replacement = player(id);
        Class<?> pending = Class.forName(DeviceTerminalVisualActionQueue.class.getName() + "$Pending");
        Method belongs = pending.getDeclaredMethod("belongsTo", EntityPlayerMP.class);
        belongs.setAccessible(true);
        for (Class<?> messageType : new Class<?>[] { PacketDeviceTerminalVisualAction.class,
            PacketEngineeringDeviceAction.class }) {
            Constructor<?> constructor = pending.getDeclaredConstructor(EntityPlayerMP.class, messageType);
            constructor.setAccessible(true);
            Object message = allocate(messageType);
            Object action = constructor.newInstance(original, message);
            assertTrue((Boolean) belongs.invoke(action, original));
            assertFalse((Boolean) belongs.invoke(action, replacement));
            assertFalse((Boolean) belongs.invoke(action, new Object[] { null }));
        }
    }

    private static Collection<?> queued(Object queue) throws Exception {
        return (Collection<?>) field(PlayerRequestQueue.class, queue, "pending");
    }

    @Test
    public void deviceStopAndLogoutReleaseInheritedRequestsNotOnlyDeliveryMetadata() throws Exception {
        DeviceTerminalRequestQueue.clear();
        Object queue = field(DeviceTerminalRequestQueue.class, null, "INSTANCE");
        Player departing = player(UUID.randomUUID()), replacement = player(departing.id);
        try {
            DeviceTerminalRequestQueue.enqueue(departing);
            DeviceTerminalRequestQueue.enqueue(replacement);
            assertEquals(2, queued(queue).size());
            DeviceTerminalRequestQueue.onPlayerLoggedOut(departing);
            assertEquals(1, queued(queue).size());
            DeviceTerminalRequestQueue.clear();
            assertTrue(queued(queue).isEmpty());
            DeviceTerminalRequestQueue.enqueue(replacement);
            assertEquals(1, queued(queue).size());
        } finally {
            DeviceTerminalRequestQueue.clear();
        }
    }

    @Test
    public void safetyResetAlsoDropsPendingIntentsAndReopensAdmission() throws Exception {
        Object queue = field(PacketSetQuantumNetworkSafety.class, null, "QUEUE");
        Method enqueue = queue.getClass().getDeclaredMethod("enqueue", EntityPlayerMP.class,
            PacketSetQuantumNetworkSafety.class);
        enqueue.setAccessible(true);
        Player player = player(UUID.randomUUID());
        PacketSetQuantumNetworkSafety.clearContexts();
        try {
            enqueue.invoke(queue, player, new PacketSetQuantumNetworkSafety());
            assertEquals(1, queued(queue).size());
            PacketSetQuantumNetworkSafety.clearContexts();
            assertTrue(queued(queue).isEmpty());
            enqueue.invoke(queue, player, new PacketSetQuantumNetworkSafety());
            assertEquals(1, queued(queue).size());
            new PacketSetQuantumNetworkSafety.Drain().logout(new PlayerEvent.PlayerLoggedOutEvent(player));
            assertTrue(queued(queue).isEmpty());
        } finally {
            PacketSetQuantumNetworkSafety.clearContexts();
        }
    }
}
