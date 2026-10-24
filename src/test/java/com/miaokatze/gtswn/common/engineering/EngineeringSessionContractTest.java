package com.miaokatze.gtswn.common.engineering;

import static org.junit.Assert.*;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import org.junit.Test;

import com.miaokatze.gtswn.network.PacketEngineeringSubscribe;

public class EngineeringSessionContractTest {

    private static final class Player extends EntityPlayerMP {

        UUID id;

        private Player() {
            super(null, null, null, null);
        }

        @Override
        public UUID getUniqueID() {
            return id;
        }
    }

    private static Player player(UUID id) throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field unsafeField = unsafeClass.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Player player = (Player) unsafeClass.getMethod("allocateInstance", Class.class)
            .invoke(unsafeField.get(null), Player.class);
        player.id = id;
        return player;
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, Object> map(String name) throws Exception {
        Field field = EngineeringDashboardSessions.class.getDeclaredField(name);
        field.setAccessible(true);
        return (Map<UUID, Object>) field.get(null);
    }

    private static PacketEngineeringSubscribe request(long id, boolean boundOnly) {
        return new PacketEngineeringSubscribe(id, 2, 0, -1, -1, 0, 0, false, false, false, boundOnly);
    }

    @Test
    public void networkBurstRetainsOnlyOnePendingRequestPerActualActor() throws Exception {
        Map<UUID, Object> pending = map("PENDING");
        pending.clear();
        try {
            Player player = player(UUID.randomUUID());
            for (int i = 1; i <= 1000; i++) EngineeringDashboardSessions.enqueue(player, request(i, false));
            assertEquals(1, pending.size());
            EngineeringDashboardSessions.enqueue(player(UUID.randomUUID()), request(1, false));
            assertEquals(2, pending.size());
            EngineeringDashboardSessions
                .enqueue(player, new PacketEngineeringSubscribe(1, 8, 0, 0, 0, 0, 0, false, false, false));
            assertEquals(2, pending.size());
        } finally {
            pending.clear();
        }
    }

    @Test
    public void sessionsRequireSamePlayerObjectActualStackDimensionAndScope() throws Exception {
        Player owner = player(UUID.randomUUID());
        ItemStack actual = new ItemStack(new Item());
        Class<?> type = Class.forName(EngineeringDashboardSessions.class.getName() + "$Session");
        Constructor<?> constructor = type
            .getDeclaredConstructor(EntityPlayerMP.class, ItemStack.class, PacketEngineeringSubscribe.class);
        constructor.setAccessible(true);
        Object session = constructor.newInstance(owner, actual, request(1, false));
        Method same = type
            .getDeclaredMethod("same", EntityPlayerMP.class, ItemStack.class, PacketEngineeringSubscribe.class);
        same.setAccessible(true);
        assertTrue((Boolean) same.invoke(session, owner, actual, request(1, false)));
        assertFalse((Boolean) same.invoke(session, owner, actual.copy(), request(1, false)));
        assertFalse((Boolean) same.invoke(session, player(owner.id), actual, request(1, false)));
        assertFalse((Boolean) same.invoke(session, owner, actual, request(1, true)));
        assertFalse((Boolean) same.invoke(session, owner, actual, request(2, false)));
        owner.dimension = -1;
        assertFalse((Boolean) same.invoke(session, owner, actual, request(1, false)));
    }

    @Test
    public void switchingSessionIdsCannotResetThePerPlayerPublicationCooldown() throws Exception {
        Map<UUID, Object> sent = map("LAST_SENT");
        Player owner = player(UUID.randomUUID());
        Class<?> type = Class.forName(EngineeringDashboardSessions.class.getName() + "$Session");
        Constructor<?> constructor = type
            .getDeclaredConstructor(EntityPlayerMP.class, ItemStack.class, PacketEngineeringSubscribe.class);
        constructor.setAccessible(true);
        Field lastSent = type.getDeclaredField("sent");
        lastSent.setAccessible(true);
        try {
            sent.put(owner.id, Long.valueOf(123));
            for (int id = 1; id < 5; id++) {
                Object session = constructor.newInstance(owner, new ItemStack(new Item()), request(id, false));
                assertEquals(123, lastSent.getLong(session));
            }
        } finally {
            sent.remove(owner.id);
        }
    }

    @Test
    public void saturatedSubscriptionsRotateAcrossEightyOneAndOneHundredSixtyPlayers() throws Exception {
        assertFairRotation(81, 4);
        assertFairRotation(160, 4);
    }

    @Test
    public void rotationAlsoProgressesWhenTimeBudgetAllowsOnlyOneRefresh() throws Exception {
        assertFairRotation(81, 1);
    }

    @Test
    public void serverStopDropsPendingSubscriptionsAndPublicationCooldowns() throws Exception {
        Player player = player(UUID.randomUUID());
        EngineeringDashboardSessions.enqueue(player, request(1, false));
        map("LAST_SENT").put(player.id, Long.valueOf(123));
        EngineeringDashboardSessions.clear();
        assertTrue(map("PENDING").isEmpty());
        assertTrue(map("SESSIONS").isEmpty());
        assertTrue(map("LAST_SENT").isEmpty());
    }

    private static void assertFairRotation(int count, int perTick) throws Exception {
        Map<UUID, Object> sessions = map("SESSIONS");
        Class<?> type = Class.forName(EngineeringDashboardSessions.class.getName() + "$Session");
        Constructor<?> constructor = type
            .getDeclaredConstructor(EntityPlayerMP.class, ItemStack.class, PacketEngineeringSubscribe.class);
        constructor.setAccessible(true);
        Field sent = type.getDeclaredField("sent");
        sent.setAccessible(true);
        Field tick = EngineeringDashboardSessions.class.getDeclaredField("tick");
        tick.setAccessible(true);
        long originalTick = tick.getLong(null);
        Method next = EngineeringDashboardSessions.class.getDeclaredMethod("nextDueSession");
        next.setAccessible(true);
        Map<Object, Integer> counts = new IdentityHashMap<>();
        sessions.clear();
        try {
            tick.setLong(null, 0);
            for (int i = 0; i < count; i++) {
                Player player = player(new UUID(0, i));
                Object session = constructor.newInstance(player, new ItemStack(new Item()), request(1, false));
                sent.setLong(session, -EngineeringDashboardSessions.UPDATE_TICKS);
                sessions.put(player.id, session);
                counts.put(session, 0);
            }
            for (int now = 0; now < count * 3; now++) {
                tick.setLong(null, now);
                for (int attempt = 0; attempt < perTick; attempt++) {
                    Object selected = next.invoke(null);
                    assertNotNull(selected);
                    assertTrue(now - sent.getLong(selected) >= EngineeringDashboardSessions.UPDATE_TICKS);
                    sent.setLong(selected, now);
                    counts.put(selected, counts.get(selected) + 1);
                }
            }
            int min = Integer.MAX_VALUE, max = 0;
            for (int served : counts.values()) {
                min = Math.min(min, served);
                max = Math.max(max, served);
            }
            assertTrue("Every subscription must eventually be served", min > 0);
            assertTrue("Overload must slow all subscribers fairly", max - min <= 1);
        } finally {
            sessions.clear();
            tick.setLong(null, originalTick);
        }
    }
}
