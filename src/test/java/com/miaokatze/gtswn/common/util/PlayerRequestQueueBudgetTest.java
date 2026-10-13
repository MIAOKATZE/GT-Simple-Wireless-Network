package com.miaokatze.gtswn.common.util;

import static org.junit.Assert.*;

import java.lang.reflect.Field;

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
}
