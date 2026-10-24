package com.miaokatze.gtswn.common.util;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import net.minecraft.entity.player.EntityPlayerMP;

import org.junit.Test;

public class PlayerRequestQueueBudgetTest {

    private static EntityPlayerMP actor() throws Exception {
        Class<?> type = Class.forName("sun.misc.Unsafe");
        Field f = type.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return (EntityPlayerMP) type.getMethod("allocateInstance", Class.class)
            .invoke(f.get(null), EntityPlayerMP.class);
    }

    private static final class ReplenishingQueue extends PlayerRequestQueue<Integer> {

        final EntityPlayerMP player;
        int processed;

        ReplenishingQueue(EntityPlayerMP player) {
            this.player = player;
        }

        @Override
        protected EntityPlayerMP playerOf(Integer value) {
            return player;
        }

        @Override
        protected void process(Integer value) {
            processed++;
            offer(player, value + 1);
        }

        void seed() {
            offer(player, 0);
        }

        void tick(int budget) {
            drainAtMost(budget);
        }
    }

    @Test
    public void workThatReenqueuesItselfCannotStarveTheServerTick() throws Exception {
        ReplenishingQueue queue = new ReplenishingQueue(actor());
        queue.seed();
        queue.tick(64);
        assertEquals(64, queue.processed);
        queue.tick(1);
        assertEquals(65, queue.processed);
        queue.tick(0);
        assertEquals(65, queue.processed);
    }

    private static final class RecordingQueue extends PlayerRequestQueue<EntityPlayerMP> {

        final List<EntityPlayerMP> processed = new ArrayList<>();

        @Override
        protected EntityPlayerMP playerOf(EntityPlayerMP player) {
            return player;
        }

        @Override
        protected void process(EntityPlayerMP player) {
            processed.add(player);
        }
    }

    @Test
    public void capacityRejectsNewQueriesWithoutRetainingTheirDeduplicationGate() throws Exception {
        RecordingQueue queue = new RecordingQueue();
        for (int i = 0; i < 1024; i++) {
            EntityPlayerMP player = actor();
            queue.offer(player, player);
            queue.offer(player, player);
        }
        EntityPlayerMP overflow = actor();
        queue.offer(overflow, overflow);
        queue.drainAtMost(2048);
        assertEquals(1024, queue.processed.size());
        queue.offer(overflow, overflow);
        queue.drainAtMost(1);
        assertSame(overflow, queue.processed.get(1024));
    }

    @Test
    public void logoutAndStopReleasePayloadsAndAdmissionGates() throws Exception {
        RecordingQueue queue = new RecordingQueue();
        EntityPlayerMP departing = actor(), retained = actor();
        queue.offer(departing, departing);
        queue.offer(retained, retained);
        queue.discardPlayer(departing);
        queue.drainAtMost(64);
        assertEquals(1, queue.processed.size());
        assertSame(retained, queue.processed.get(0));
        queue.offer(departing, departing);
        queue.clearPending();
        queue.drainAtMost(64);
        assertEquals(1, queue.processed.size());
        queue.offer(departing, departing);
        queue.drainAtMost(64);
        assertSame(departing, queue.processed.get(1));
    }

    @Test
    public void concurrentAdmissionCannotExceedTheGlobalCapacity() throws Exception {
        RecordingQueue queue = new RecordingQueue();
        EntityPlayerMP[] players = new EntityPlayerMP[2048];
        for (int i = 0; i < players.length; i++) players[i] = actor();
        ExecutorService workers = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> submitted = new ArrayList<>();
        try {
            for (int worker = 0; worker < 4; worker++) {
                final int offset = worker;
                submitted.add(workers.submit(() -> {
                    start.await();
                    for (int i = offset; i < players.length; i += 4) queue.offer(players[i], players[i]);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> task : submitted) task.get();
            queue.drainAtMost(4096);
            assertEquals(1024, queue.processed.size());
        } finally {
            workers.shutdownNow();
        }
    }
}
